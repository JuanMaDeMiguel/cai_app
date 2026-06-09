"""Prueba manual: escribi un porcentaje y Enter, la pantalla hace fade suave.

Uso:
    sg video -c "python3 manual.py"

Escribi un numero (5-100) y Enter. 'q' o Ctrl-D para salir.
"""
import backlight


def main():
    print(f"Brillo actual: {backlight.get_percent()}%")
    print("Escribi un % (5-100) y Enter. 'q' para salir.")
    while True:
        try:
            line = input("> ").strip()
        except EOFError:
            break
        if line in ("q", "quit", "exit"):
            break
        if not line:
            continue
        try:
            pct = int(float(line))
        except ValueError:
            print("  no es un numero")
            continue
        applied = backlight.fade_to(pct, duration=0.5)
        print(f"  -> {applied}%")


if __name__ == "__main__":
    main()
