# Brillo Adaptable Teléfono → PC

App Android que usa el **sensor de luz ambiente del teléfono** para controlar el
**brillo de la pantalla de la PC** vía Bluetooth.

## Arquitectura

```
[Teléfono Android]                      [PC Fedora]
 Sensor de luz (lux)
   → mapeo lux→brillo % (log)
   → throttle (solo si Δ ≥ 3%)
   → envío por Bluetooth  ──RFCOMM/SPP──►  Servidor Bluetooth (Python)
                                            → recibe brillo %
                                            → brightnessctl set N%
```

## Decisiones tomadas (Parte 0)

1. **Transporte:** Bluetooth Classic **SPP/RFCOMM** (no BLE). Canal tipo puerto
   serie, simple y robusto para teléfono↔PC.
2. **Pantalla a controlar:** **pantalla interna de la laptop** con
   `brightnessctl`. Más confiable que `ddcutil` (que depende del DDC/CI del
   monitor y del cable). Si más adelante se quiere monitor externo → `ddcutil
   setvcp 10 N`.
3. **Mapeo lux→brillo:** curva **logarítmica** recortada:
   ```
   brillo% = clamp( log10(lux + 1) / log10(5000) * 100 , 5 , 100 )
   ```
   (lux ~0 oscuro, ~10.000+ sol directo; 5000 como tope práctico).
4. **Frecuencia de envío:** enviar **solo cuando el brillo calculado cambie ≥ 3%**.
   No mandar cada lectura del sensor.

UUID SPP estándar: `00001101-0000-1000-8000-00805F9B34FB`
Protocolo: una línea de texto por mensaje, el porcentaje entero. Ej: `"75\n"`.

---

## Parte A — Android (Java)

Proyecto: `Android/app/src/main/java/com/example/cai_app/`

### A1. Permisos — `AndroidManifest.xml`
```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<!-- Android < 12 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-feature android:name="android.hardware.sensor.light" />
```
Android 12+: pedir `BLUETOOTH_CONNECT` en **runtime** con `ActivityResultLauncher`.

### A2. Sensor de luz
- `SensorManager` + `getDefaultSensor(Sensor.TYPE_LIGHT)`.
- `SensorEventListener` → `event.values[0]` = lux.
- Registrar con `SENSOR_DELAY_NORMAL`.

### A3. Conexión Bluetooth
- Emparejar teléfono↔PC **una vez** desde Ajustes de Android (fuera de la app).
- `BluetoothAdapter.getBondedDevices()` → elegir la PC.
- `device.createRfcommSocketToServiceRecord(UUID_SPP)` → `socket.connect()`.
- **Todo el Bluetooth en hilo aparte** (no UI thread). `ExecutorService`/`Thread`.

### A4. Envío
- Al cambiar el brillo calculado (Δ ≥ 3%), escribir `"<N>\n"` en
  `socket.getOutputStream()`.

### A5. UI mínima — `activity_main.xml`
- `TextView`: lux actual + brillo %.
- `Button` "Conectar" + estado de conexión.
- (Opcional) `Switch` activar/desactivar envío.

### A6. Robustez
- Reconexión si se cae el socket.
- (Opcional/futuro) `Foreground Service` para seguir en background; sin él,
  Android corta el socket al minimizar. Empezar dentro de la Activity.

---

## Parte B — PC Fedora (Python)

### B1. Dependencias
```bash
sudo dnf install bluez brightnessctl
sudo dnf install python3-pybluez   # o: pip install pybluez (necesita bluez-libs-devel)
```

### B2. Exponer canal SPP en BlueZ
```bash
sudo sdptool add SP        # registra Serial Port Profile
bluetoothctl               # power on / agent on / discoverable on / pairable on
```

### B3. Servidor SPP (Python / D-Bus, IMPLEMENTADO ASÍ)
PyBluez NO sirve (Python 3.14). Solución final:
1. Registrar perfil SPP en BlueZ vía D-Bus `ProfileManager1.RegisterProfile`
   (UUID SPP, Role=server) → BlueZ anuncia el servicio por SDP.
2. BlueZ llama `Profile1.NewConnection(device, fd, props)` con el fd ya conectado.
3. `socket.socket(..., fileno=fd)` + `setblocking(True)` → leer líneas → aplicar brillo.

### B4. Aplicar brillo
- Escritura directa a sysfs `/sys/class/backlight/intel_backlight/brightness`
  (no se usó `brightnessctl`). Ver `backlight.py`.
- Permisos: usuario en grupo `video` + regla udev que hace el archivo `g+w`.
  ```bash
  sudo usermod -aG video $USER   # relogin después
  ```
- (Futuro, monitor externo) `ddcutil setvcp 10 N` — requiere `i2c-dev` y grupo `i2c`.

### B5. (Opcional) Autostart
- `systemd --user` service para arrancar el servidor solo.

---

## Orden de implementación

1. ✅ **PC sin Android (HECHO):** scripts en `pc/` aplican brillo por sysfs
   (`/sys/class/backlight/intel_backlight`, no se usó brightnessctl). Verificado
   con barrido y prueba manual. Permisos resueltos: archivo `root:video g+w`
   (regla udev `90-backlight.rules` lo persiste tras reboot) + usuario en grupo
   `video` (ya activo tras relogin, no hace falta `sg`).
   - `backlight.py`: `set_percent()` instantáneo y `fade_to()` suave (interpola
     sobre el RAW 0–96000, no sobre el %).
   - `apply_brightness.py`: lee % de stdin y aplica (simula el server Bluetooth).
   - `simulate.py`: barrido fino o modo `--lux` (curva log real).
   - `manual.py`: prueba interactiva, escribís un % y hace fade.
2. ✅ **Servidor Bluetooth en PC (HECHO):** `bt_server.py` registra un perfil
   **SPP por D-Bus** (BlueZ `ProfileManager1`), que anuncia el servicio por SDP.
   Adaptador BT: `F4:6D:3F:33:A3:96` (fedora).
   **Camino recorrido (importante para entender el código):**
   - Opción A descartada: canal fijo 1 + `createRfcommSocket(1)` por reflexión,
     SIN SDP. El teléfono daba "read failed, socket might be closed or timeout";
     el `connect()` no llegaba al server. Confirmado no confiable.
   - **Opción B adoptada (funciona):** registrar SPP por D-Bus → el teléfono lo
     descubre con `createInsecureRfcommSocketToServiceRecord(SPP_UUID)`. Requiere
     `python3-dbus` + `python3-gobject`. **OJO:** correr el server con
     `/usr/bin/python3` (el del sistema tiene dbus), NO con el `venv`.
   - Fix clave: el fd que entrega BlueZ viene **no-bloqueante** → `sock.setblocking(True)`.
3-5. ✅ **Android (HECHO, probado en teléfono real):** sensor + curva log +
   throttle 3% + Bluetooth por SDP + envío.
   - `AndroidManifest.xml`: permisos BLUETOOTH_CONNECT (API 31+) / BLUETOOTH(_ADMIN)
     (≤30) + `uses-feature` sensor de luz.
   - `activity_main.xml`: lux, % grande, estado, botón Conectar, switch enviar.
   - `MainActivity.java`: `SensorEventListener` (TYPE_LIGHT), `luxToPercent()`,
     conexión con `createInsecureRfcommSocketToServiceRecord(SPP_UUID)` en
     `ExecutorService`, permiso runtime para Android 12+, selector de dispositivo
     emparejado. Se quitó `cancelDiscovery()` (exigía BLUETOOTH_SCAN).
     Warning menor: `getDefaultAdapter()` deprecado (funciona).
6. ✅ **GUI de la PC (PyQt6, HECHO, sencilla):** `pc/gui/control_panel.py`.
   Botones para iniciar/detener el server y para hacer la PC visible/emparejable,
   sin terminal. Lanza `bt_server.py` como **subproceso** con `/usr/bin/python3`
   (evita el choque loop Qt vs GLib) y muestra estado, brillo en vivo y log.
   Requiere `PyQt6` (pip en venv) o `python3-pyqt6` (dnf). Pendiente: hacerla más
   linda.
7. **Pulir (pendiente):** reconexión automática, ajuste de curva, (opcional)
   Foreground Service para seguir en background, autostart del server con systemd.

## Estructura actual
`pc/src/` = scripts (server + brillo), `pc/gui/` = GUI PyQt6, `pc/*.md` + `requirements.txt`.

## Estado: ✅ FUNCIONA END-TO-END
Sensor del teléfono → app → Bluetooth SPP → `bt_server.py` → brillo de la PC
sigue la luz ambiente en tiempo real, con fade suave. GUI opcional para arrancar todo.

## Riesgos conocidos

- **PyBluez NO sirve** (Python 3.14) → se usan sockets stdlib `AF_BLUETOOTH` (ya resuelto).
- **Sin SDP**, las apps de terminal Bluetooth genéricas no descubren el servicio
  (sí funciona la conexión directa a canal 1 por reflexión desde nuestra app).
- **Android 12+:** sin permisos runtime, el Bluetooth falla en silencio.
- **Background:** sin Foreground Service, el socket se corta al minimizar.
- **brightnessctl:** requiere grupo `video` (o se cae por permisos en /sys).
