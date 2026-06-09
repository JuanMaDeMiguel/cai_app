"""Panel de control (PyQt6) para el server de brillo adaptable.

Lanza/detiene bt_server.py como subproceso y permite hacer la PC visible para
emparejar, sin tocar la terminal. Reusa el server ya testeado.

Requisitos: PyQt6 (pip en venv) o python3-pyqt6 (dnf). El server se lanza
internamente con /usr/bin/python3 (que tiene dbus), sin importar con qué
interprete corra esta GUI.
"""
import os
import re
import sys

from PyQt6.QtCore import QProcess, Qt
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import (QApplication, QHBoxLayout, QLabel, QPushButton,
                             QTextEdit, QVBoxLayout, QWidget)

# Ubicaciones posibles de bt_server.py. Primero relativo a este script
# (gui/ y src/ son hermanos dentro de pc/), luego rutas conocidas de fallback.
_HERE = os.path.dirname(os.path.abspath(__file__))
HOME = os.path.expanduser("~")
SERVER_CANDIDATES = [
    os.path.normpath(os.path.join(_HERE, "..", "src", "bt_server.py")),
    os.path.join(HOME, "Documents/CAI/Android/pc/src/bt_server.py"),
    os.path.join(HOME, "Documents/CAI/pc/src/bt_server.py"),
]
SYSTEM_PYTHON = "/usr/bin/python3"

BRIGHTNESS_RE = re.compile(r"->\s*(\d+)%")


def find_server():
    for path in SERVER_CANDIDATES:
        if os.path.isfile(path):
            return path
    return None


class ControlPanel(QWidget):
    def __init__(self):
        super().__init__()
        self.server_path = find_server()
        self.proc = None
        self._build_ui()

    def _build_ui(self):
        self.setWindowTitle("Adaptive Brightness - PC Panel")
        self.setMinimumWidth(420)

        self.serverStatus = QLabel()
        self.connStatus = QLabel("Disconnected")

        self.brightness = QLabel("-- %")
        f = QFont()
        f.setPointSize(40)
        f.setBold(True)
        self.brightness.setFont(f)
        self.brightness.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.startBtn = QPushButton("Start server")
        self.startBtn.clicked.connect(self.toggle_server)

        self.pairBtn = QPushButton("Make discoverable for pairing")
        self.pairBtn.clicked.connect(self.make_discoverable)

        self.log = QTextEdit()
        self.log.setReadOnly(True)
        self.log.setMinimumHeight(160)

        buttons = QHBoxLayout()
        buttons.addWidget(self.startBtn)
        buttons.addWidget(self.pairBtn)

        layout = QVBoxLayout(self)
        layout.addWidget(self.serverStatus)
        layout.addWidget(self.connStatus)
        layout.addWidget(self.brightness)
        layout.addLayout(buttons)
        layout.addWidget(QLabel("Log:"))
        layout.addWidget(self.log)

        if self.server_path is None:
            self.serverStatus.setText("⚠ bt_server.py not found")
            self.startBtn.setEnabled(False)
        else:
            self.set_server_running(False)

    # ---- estado visual ----

    def set_server_running(self, running):
        if running:
            self.serverStatus.setText("Server: ● running")
            self.serverStatus.setStyleSheet("color: green;")
            self.startBtn.setText("Stop server")
        else:
            self.serverStatus.setText("Server: ○ stopped")
            self.serverStatus.setStyleSheet("color: gray;")
            self.startBtn.setText("Start server")
            self.connStatus.setText("Disconnected")
            self.brightness.setText("-- %")

    # ---- servidor ----

    def toggle_server(self):
        if self.proc is None:
            self.start_server()
        else:
            self.stop_server()

    def start_server(self):
        self.proc = QProcess(self)
        self.proc.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.proc.readyReadStandardOutput.connect(self.on_output)
        self.proc.finished.connect(self.on_finished)
        self.proc.start(SYSTEM_PYTHON, ["-u", self.server_path])
        self.set_server_running(True)

    def stop_server(self):
        if self.proc is not None:
            self.proc.terminate()
            if not self.proc.waitForFinished(1500):
                self.proc.kill()

    def on_finished(self):
        self.proc = None
        self.set_server_running(False)

    def on_output(self):
        text = bytes(self.proc.readAllStandardOutput()).decode(errors="replace")
        for line in text.splitlines():
            self.log.append(line)
            if "[+] Conectado" in line:
                self.connStatus.setText("📱 Phone connected")
                self.connStatus.setStyleSheet("color: green;")
            elif "[-] Desconectado" in line:
                self.connStatus.setText("Disconnected")
                self.connStatus.setStyleSheet("color: gray;")
            m = BRIGHTNESS_RE.search(line)
            if m:
                self.brightness.setText(m.group(1) + " %")

    # ---- emparejar ----

    def make_discoverable(self):
        for args in (["discoverable-timeout", "0"],
                     ["pairable", "on"],
                     ["discoverable", "on"]):
            QProcess.execute("bluetoothctl", args)
        self.log.append(">> PC discoverable and pairable. Look for 'fedora' on your phone.")

    def closeEvent(self, event):
        self.stop_server()
        event.accept()


def main():
    app = QApplication(sys.argv)
    panel = ControlPanel()
    panel.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
