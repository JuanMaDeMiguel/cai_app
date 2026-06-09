# Explicación del código — Brillo adaptable Teléfono → PC

Documento para entender y explicar cómo funciona cada parte del proyecto y cómo
se relacionan entre sí. Dividido en: lado PC (Python), lado Android (Java + XML)
y el flujo completo de datos.

---

## 1. Visión general del flujo

```
SENSOR DE LUZ (teléfono)
   │  lux (ej. 350.0)
   ▼
MainActivity.onSensorChanged()         ── Java, hilo principal de la UI
   │  luxToPercent(lux) -> 62
   │  ¿cambió >= 3% respecto al último enviado? sí
   ▼
send(62)  -> "62\n" por Bluetooth      ── Java, hilo de I/O (ExecutorService)
   │
   ▼  (RFCOMM / SPP, perfil Serial Port)
   │
bt_server.py  Profile1.NewConnection   ── Python, callback de BlueZ vía D-Bus
   │  read_loop(): recibe "62\n", parsea 62
   ▼
backlight.fade_to(62)                  ── Python, hilo del cliente
   │  interpola el valor RAW y lo escribe
   ▼
/sys/class/backlight/intel_backlight/brightness   ── el kernel cambia el brillo
```

El "contrato" entre ambos lados es **una línea de texto por mensaje**: un número
entero (el porcentaje de brillo) terminado en `\n`. Simple de generar en Java y
de parsear en Python.

---

## 2. Lado PC (Python, carpeta `pc/`)

### 2.1 `backlight.py` — control físico del brillo
Es la única parte que toca el hardware. No sabe nada de Bluetooth; solo
lee/escribe el brillo. Así se puede probar de forma aislada.

- `_find_device()`: busca el dispositivo de backlight en
  `/sys/class/backlight/*` (en esta laptop, `intel_backlight`). Lo cachea.
- `get_max()`: lee `max_brightness` (en esta máquina, 96000). Es el valor RAW
  máximo que entiende el kernel.
- `get_percent()`: lee el brillo actual y lo convierte a porcentaje.
- `set_percent(pct)`: convierte el % a valor RAW (`pct/100 * max`) y lo escribe.
  Recorta a 5–100 para no apagar la pantalla del todo. Cambio **instantáneo**.
- `fade_to(pct, duration, steps)`: transición **suave**. Interpola sobre el
  valor RAW (0–96000), no sobre el %, escribiendo `steps` valores intermedios.
  Hacerlo en RAW aprovecha toda la resolución del backlight y evita escalones.
- `_write_raw(raw)`: helper que abre el archivo sysfs y escribe el número.

**Permisos:** escribir en `/sys/.../brightness` normalmente requiere root. Se
resolvió con una regla udev que hace el archivo del grupo `video` con permiso de
escritura, y agregando el usuario a ese grupo. Por eso el script puede cambiar
el brillo sin `sudo`.

### 2.2 `bt_server.py` — servidor Bluetooth (programa principal de la PC)
Recibe los porcentajes del teléfono y los aplica llamando a `backlight.fade_to`.
Usa **BlueZ a través de D-Bus**, no PyBluez (que no compila en Python 3.14).

- `main()`:
  - Crea el bucle de eventos GLib y se conecta al **System Bus** de D-Bus.
  - Instancia la clase `Profile` (un objeto D-Bus en `PROFILE_PATH`).
  - Llama a `ProfileManager1.RegisterProfile(...)` con el **UUID de SPP**
    (`00001101-...`), rol `server` y un canal. Al registrar el perfil, BlueZ
    **anuncia el servicio por SDP**, que es lo que permite al teléfono
    descubrirlo y conectarse.
  - Arranca `GLib.MainLoop()` y queda esperando conexiones.
- `class Profile` (implementa la interfaz `org.bluez.Profile1` que BlueZ invoca):
  - `NewConnection(device, fd, properties)`: BlueZ llama a este método cuando el
    teléfono se conecta, y entrega un **file descriptor ya conectado**. Se
    envuelve ese fd en un `socket` de Python. **Detalle clave:** el fd viene en
    modo **no-bloqueante**, así que hay que hacer `sock.setblocking(True)` o
    `recv()` falla con `BlockingIOError`. Luego lanza un hilo `read_loop`.
  - `RequestDisconnection(device)` / `Release()`: callbacks de ciclo de vida.
- `read_loop(sock, addr)`: bucle que lee bytes del socket, los acumula en un
  buffer y los **separa por `\n`** (porque un `recv` puede traer varios mensajes
  pegados o uno partido). Por cada línea: parsea el entero y llama a
  `backlight.fade_to(pct, 0.3)`. Imprime `  62 ->  62%` para depurar.

Se corre con `/usr/bin/python3` (el del sistema tiene los módulos `dbus` y `gi`;
el venv del proyecto no).

### 2.3 Scripts de prueba (no son parte del producto final)
Permiten validar el lado PC sin el teléfono. Todos terminan llamando a
`backlight`.

- `apply_brightness.py`: lee porcentajes de **stdin** (uno por línea) y los
  aplica. **Simula exactamente lo que hace el server** (recibir un % y aplicarlo),
  pero por tubería en vez de Bluetooth. Ej: `echo 30 | python3 apply_brightness.py`.
- `simulate.py`: **genera datos falsos**. Modo barrido (100→5→100) o modo
  `--lux` (genera lux aleatorios y los pasa por `lux_to_percent`, la **misma
  curva logarítmica** que usa la app Android — sirve para mostrar que la curva
  está bien). Se conecta por tubería a `apply_brightness.py`.
- `manual.py`: prueba **interactiva**. Escribís un % y la pantalla hace fade.
  Útil para demostrar `fade_to` en vivo.

Todos estos viven en `pc/src/`.

### 2.4 `gui/control_panel.py` — panel de control (PyQt6)
Interfaz gráfica para no depender de la terminal. **No reimplementa el server**:
lo lanza como **subproceso** y muestra su salida. Esto evita el choque entre el
bucle de eventos de Qt y el de GLib (cada uno corre en su propio proceso).

- `find_server()`: localiza `bt_server.py` (primero relativo al script, ya que
  `gui/` y `src/` son hermanos).
- **Botón "Start server"**: lanza `/usr/bin/python3 -u bt_server.py` con un
  `QProcess` y cambia a "Stop server". Se usa `/usr/bin/python3` porque es el que
  tiene `dbus` (el venv no), sin importar con qué intérprete corra la GUI.
- `on_output()`: lee la salida del server línea por línea y la vuelca al registro.
  Con una expresión regular detecta el `-> NN%` y actualiza el número grande de
  brillo, y detecta `[+] Conectado` / `[-] Desconectado` para el estado.
- **Botón "Make discoverable for pairing"**: corre `bluetoothctl` para dejar la
  PC visible y emparejable (solo hace falta la primera vez por teléfono).
- `closeEvent()`: detiene el server al cerrar la ventana.

Requiere `PyQt6` (pip en el venv) o `python3-pyqt6` (dnf).

---

## 3. Lado Android (carpeta `Android/app/src/main/`)

### 3.1 `AndroidManifest.xml` — permisos y capacidades
Declara lo que la app necesita del sistema:
- `BLUETOOTH_CONNECT` (Android 12 / API 31+): necesario para listar dispositivos
  emparejados y conectarse. Se pide **en tiempo de ejecución**.
- `BLUETOOTH` y `BLUETOOTH_ADMIN` con `maxSdkVersion=30`: equivalentes para
  Android 11 y anteriores (ahí se conceden al instalar).
- `<uses-feature ... sensor.light>`: declara que la app usa el sensor de luz.

### 3.2 `res/layout/activity_main.xml` — la interfaz
Define las vistas que el código busca por `id`:
- `luxText`: muestra los lux actuales.
- `brightnessText`: muestra el % de brillo (número grande).
- `statusText`: estado de la conexión ("Desconectado", "Conectando...", etc.).
- `connectButton`: botón "Conectar".
- `sendSwitch`: interruptor "Enviar brillo automáticamente" (arranca deshabilitado
  hasta que haya conexión).

### 3.3 `MainActivity.java` — sensor + cliente Bluetooth
Es el corazón de la app. Implementa `SensorEventListener`. Partes:

**Ciclo de vida y setup**
- `onCreate`: obtiene las vistas, el `SensorManager`, el sensor de luz y el
  `BluetoothAdapter`. Registra el `ActivityResultLauncher` para pedir el permiso
  de Bluetooth. Conecta los listeners del botón y del switch.
- `onResume` / `onPause`: registra y des-registra el listener del sensor (para no
  gastar batería cuando la app no está visible).
- `onDestroy`: cierra el socket y apaga el `ExecutorService`.

**Sensor y curva**
- `onSensorChanged(event)`: se llama cada vez que cambia la luz. Toma
  `event.values[0]` (lux), calcula el %, actualiza la UI y, si corresponde, envía.
- `luxToPercent(lux)`: la curva **logarítmica**
  `clamp(log10(lux+1)/log10(5000)*100, 5, 100)`. Es la misma fórmula que
  `simulate.py` en Python.
- **Throttle:** solo envía si `abs(pct - lastSentPct) >= 3`. Evita saturar el
  Bluetooth con cambios mínimos. `lastSentPct` empieza en -100 para forzar el
  primer envío.

**Bluetooth**
- `ensurePermissionThenConnect()`: valida que el Bluetooth esté disponible y
  encendido; si falta el permiso (Android 12+), lo pide; si está, abre el selector.
- `pickDeviceAndConnect()`: lista los dispositivos **emparejados**
  (`getBondedDevices()`) en un diálogo para que el usuario elija la PC.
- `connect(device)`: en el hilo de I/O, crea el socket con
  `createInsecureRfcommSocketToServiceRecord(SPP_UUID)` — esto hace una
  **búsqueda SDP** del servicio SPP que publica `bt_server.py` y se conecta al
  canal correcto. Si funciona, guarda el `OutputStream` y habilita el switch.
- `send(pct)`: en el hilo de I/O, escribe `pct + "\n"` en el `OutputStream`.
- `closeSocket()`: cierra stream y socket de forma segura.

**Modelo de hilos (importante para explicar):**
- El **sensor y la UI** corren en el hilo principal.
- **Toda la red Bluetooth** (conectar, enviar) corre en un `ExecutorService` de
  un solo hilo, para no bloquear la UI.
- Para volver a tocar la UI desde ese hilo se usa un `Handler` del hilo principal
  (`main.post(...)`).

---

## 4. Decisiones técnicas clave (para defender el proyecto)

1. **Protocolo de texto plano (`"<pct>\n"`)** en vez de algo binario: trivial de
   depurar (se ve en la consola del server) y de generar/parsear.
2. **Curva logarítmica** en vez de lineal: la luz ambiente abarca varios órdenes
   de magnitud (de ~0 a >10000 lux); el ojo y las pantallas responden de forma
   logarítmica, así que el mapeo log se siente natural.
3. **Throttle del 3%**: el sensor dispara muchísimas lecturas; enviar todas
   saturaría el enlace. Solo importan los cambios perceptibles.
4. **Fade interpolando el RAW**: transiciones suaves sin escalones visibles.
5. **SPP por SDP (D-Bus) en vez de canal fijo por reflexión**: la conexión a un
   canal RFCOMM fijo con la API oculta de Android era inestable
   ("read failed..."). Publicar el servicio SPP y dejar que Android lo descubra
   por SDP es el método estándar y confiable.
6. **Sockets Bluetooth nativos + BlueZ/D-Bus**: PyBluez no compila en Python 3.14,
   y la librería estándar + D-Bus cubren todo sin dependencias frágiles.

---

## 5. Resumen de archivos modificados/creados

| Archivo | Lado | Rol |
|---|---|---|
| `pc/src/backlight.py` | PC | Lee/escribe el brillo (sysfs), con fade |
| `pc/src/bt_server.py` | PC | Servidor Bluetooth SPP (programa principal) |
| `pc/src/apply_brightness.py` | PC | Prueba: aplica % desde stdin |
| `pc/src/simulate.py` | PC | Prueba: genera datos (curva log) |
| `pc/src/manual.py` | PC | Prueba: interactivo |
| `pc/gui/control_panel.py` | PC | GUI PyQt6: inicia/detiene el server con botones |
| `Android/.../AndroidManifest.xml` | Android | Permisos y feature del sensor |
| `Android/.../res/layout/activity_main.xml` | Android | Interfaz |
| `Android/.../java/.../MainActivity.java` | Android | Sensor + cliente Bluetooth |
