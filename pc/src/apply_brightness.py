"""Lee porcentajes de brillo desde stdin (uno por linea) y los aplica.

Esto simula lo que hara el servidor Bluetooth: cada linea de texto que llega
es un porcentaje entero. Uso:

    python3 simulate.py | python3 apply_brightness.py
    echo 30 | python3 apply_brightness.py
"""
import sys

import backlight


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            pct = int(float(line))
        except ValueError:
            print(f"  ignorado (no es numero): {line!r}", file=sys.stderr)
            continue
        applied = backlight.set_percent(pct)
        print(f"  recibido {pct:>3} -> aplicado {applied:>3}%")


if __name__ == "__main__":
    main()
