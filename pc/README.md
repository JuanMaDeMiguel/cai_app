# Adaptive Brightness: Phone → PC over Bluetooth

Use an Android phone's **ambient light sensor** to drive the **screen brightness
of a Linux PC** in real time, over Bluetooth. When the phone detects more light,
the computer screen gets brighter; in the dark, it dims — with smooth fades.

The phone has a real ambient light sensor; most laptops don't expose theirs in a
usable way, so this project borrows the phone's sensor to give the PC adaptive
brightness.

## How it works

```
[Android phone]                                [Linux PC]
 Ambient light sensor (lux)
   -> map lux -> brightness % (log curve)
   -> throttle (send only if it changed >= 3%)
   -> Bluetooth (RFCOMM / SPP)  ───────────►  bt_server.py (SPP profile via D-Bus)
                                                -> receive "<percent>\n"
                                                -> fade the backlight smoothly
                                                -> write /sys/class/backlight/...
```

1. The app reads the light sensor (lux) and converts it to a brightness
   percentage using a logarithmic curve: `clamp(log10(lux+1)/log10(5000)*100, 5, 100)`.
2. It only transmits when the computed percentage changes by at least 3%, to
   avoid flooding the link.
3. The percentage is sent as a text line over a Bluetooth **SPP** (Serial Port
   Profile) connection.
4. On the PC, `bt_server.py` registers an SPP service through BlueZ over D-Bus,
   receives each percentage, and writes it to the laptop backlight via sysfs,
   interpolating for a smooth transition.

## Repository layout

```
CAI/
├── Android/                 Android Studio project (Java app)
│   └── app/src/main/
│       ├── AndroidManifest.xml          permissions + light-sensor feature
│       ├── java/.../MainActivity.java   sensor + Bluetooth client
│       └── res/layout/activity_main.xml UI
├── pc/                      Python code that runs on the computer
│   ├── backlight.py         read/write the screen brightness (sysfs)
│   ├── bt_server.py         Bluetooth SPP server (main program on the PC)
│   ├── apply_brightness.py  pipe percentages from stdin (testing)
│   ├── simulate.py          generate fake brightness/lux data (testing)
│   └── manual.py            interactive manual brightness test
├── PLANNING.md              development plan and decision log
├── CODE.md                  detailed explanation of every part (for the report)
├── README.md               this file
└── requirements.txt         PC dependencies
```

## Requirements

**PC (Linux / Fedora):**
- BlueZ (Bluetooth stack) — already present on most distros.
- `python3-dbus` and `python3-gobject` (system packages, see `requirements.txt`).
- The server must run with the **system** Python (`/usr/bin/python3`), because
  the D-Bus bindings are installed there (not inside a virtualenv).
- Write access to the backlight: user in the `video` group + a udev rule that
  makes `/sys/class/backlight/*/brightness` group-writable.

**Phone:**
- Android 7.0+ (minSdk 24) with an ambient light sensor.
- Paired with the PC over Bluetooth.

## Setup

### 1. PC — install dependencies
```bash
sudo dnf install bluez python3-dbus python3-gobject
```

### 2. PC — allow writing the backlight without root
```bash
echo 'ACTION=="add", SUBSYSTEM=="backlight", RUN+="/bin/chgrp video /sys/class/backlight/%k/brightness", RUN+="/bin/chmod g+w /sys/class/backlight/%k/brightness"' | sudo tee /etc/udev/rules.d/90-backlight.rules
sudo usermod -aG video $USER       # log out and back in afterwards
sudo chgrp video /sys/class/backlight/*/brightness
sudo chmod g+w  /sys/class/backlight/*/brightness
```

### 3. Pair the phone with the PC
On the PC:
```bash
bluetoothctl
# inside: power on / agent on / default-agent / pairable on / discoverable on
```
Then on the phone, open Bluetooth settings, find the PC (named after the
adapter, e.g. "fedora") and pair.

### 4. Build and install the Android app
With the phone connected via USB debugging (or wireless debugging):
```bash
cd Android && ./gradlew installDebug
```

## Running

1. Start the server on the PC (system Python):
   ```bash
   cd pc && /usr/bin/python3 bt_server.py
   ```
   It prints `Perfil SPP registrado ... Esperando conexion del telefono...`.
2. Open the app on the phone, tap **Conectar**, choose the PC, and turn on the
   **Enviar brillo automáticamente** switch.
3. Cover/uncover the phone's light sensor — the PC screen brightness follows.

## Testing the PC side without the phone

```bash
cd pc
python3 manual.py                              # type a % and watch it fade
python3 simulate.py | python3 apply_brightness.py   # automatic sweep
python3 simulate.py --lux | python3 apply_brightness.py  # simulate lux values
```
(If the `video` group isn't active yet in your shell, prefix with
`sg video -c "..."`.)

## Status

Working end-to-end: the phone's ambient light sensor controls the PC backlight
over Bluetooth in real time, with smooth fades.

Not done yet: automatic reconnection, running in the background (Android
foreground service), and auto-starting the PC server with systemd.

## Notes / lessons learned

- **PyBluez does not work** on Python 3.14, so the PC uses Python's built-in
  Bluetooth sockets plus BlueZ's D-Bus API instead.
- Connecting to a **fixed RFCOMM channel** via Android reflection was
  unreliable ("read failed, socket might be closed or timeout"). Registering a
  proper **SPP service over SDP** (D-Bus) and letting Android discover it is the
  robust approach.
- The file descriptor BlueZ hands over is **non-blocking**; it must be switched
  to blocking before reading.
