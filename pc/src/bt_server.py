"""Servidor Bluetooth SPP: recibe % de brillo del telefono y lo aplica.

Registra un perfil Serial Port (SPP) en BlueZ via D-Bus (ProfileManager1).
Asi el servicio queda anunciado por SDP y el telefono lo encuentra con
createRfcommSocketToServiceRecord(SPP_UUID), sin hacks de canal.

Cada mensaje es una linea de texto con un porcentaje entero ("75\\n").

Requisitos: python3-dbus, python3-gobject.
Uso: python3 bt_server.py
"""
import os
import socket
import threading

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

import backlight

SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
PROFILE_PATH = "/org/bluez/spp_brightness"
CHANNEL = 22


def read_loop(sock, addr):
    print(f"[+] Conectado: {addr}")
    buf = b""
    try:
        while True:
            data = sock.recv(1024)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    pct = int(float(line))
                except ValueError:
                    print(f"    ignorado: {line!r}")
                    continue
                applied = backlight.fade_to(pct, duration=0.3)
                print(f"    {pct:>3} -> {applied:>3}%")
    finally:
        sock.close()
        print(f"[-] Desconectado: {addr}")


class Profile(dbus.service.Object):
    @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
    def Release(self):
        print("Profile.Release")

    @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}",
                         out_signature="")
    def NewConnection(self, device, fd, properties):
        fd = fd.take()
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                             socket.BTPROTO_RFCOMM, fileno=fd)
        sock.setblocking(True)
        threading.Thread(target=read_loop, args=(sock, str(device)),
                         daemon=True).start()

    @dbus.service.method("org.bluez.Profile1", in_signature="o",
                         out_signature="")
    def RequestDisconnection(self, device):
        print(f"RequestDisconnection: {device}")


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    Profile(bus, PROFILE_PATH)

    manager = dbus.Interface(
        bus.get_object("org.bluez", "/org/bluez"),
        "org.bluez.ProfileManager1")
    opts = {
        "Name": "Brillo SPP",
        "Role": "server",
        "Channel": dbus.UInt16(CHANNEL),
        "RequireAuthentication": dbus.Boolean(False),
        "RequireAuthorization": dbus.Boolean(False),
    }
    manager.RegisterProfile(PROFILE_PATH, SPP_UUID, opts)
    print(f"Perfil SPP registrado (canal {CHANNEL}). Esperando conexion del telefono...")

    loop = GLib.MainLoop()
    try:
        loop.run()
    except KeyboardInterrupt:
        print("\nSaliendo.")


if __name__ == "__main__":
    main()
