"""Genera datos de brillo simulados para probar la PC sin el telefono.

Modo 1 (default): barrido de porcentajes 100 -> 5 -> 100.
Modo 2 (--lux): genera lux aleatorios y los pasa por la curva logaritmica
real (la misma que usara la app Android), imprimiendo el porcentaje.
"""
import math
import random
import sys
import time

DELAY = 0.03  # segundos entre valores


def lux_to_percent(lux):
    pct = math.log10(lux + 1) / math.log10(5000) * 100
    return max(5, min(100, round(pct)))


def sweep():
    seq = list(range(100, 4, -1)) + list(range(5, 101, 1))
    for pct in seq:
        print(pct, flush=True)
        time.sleep(DELAY)


def lux_mode():
    for _ in range(40):
        lux = random.choice([0, 5, 50, 200, 800, 3000, 10000])
        pct = lux_to_percent(lux)
        print(pct, flush=True)
        print(f"  lux={lux:>6} -> {pct}%", file=sys.stderr)
        time.sleep(DELAY)


if __name__ == "__main__":
    if "--lux" in sys.argv:
        lux_mode()
    else:
        sweep()
