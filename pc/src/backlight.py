"""Control del brillo de la pantalla interna via sysfs (intel_backlight)."""
import glob
import os
import time

BACKLIGHT_DIR = None


def _find_device():
    global BACKLIGHT_DIR
    if BACKLIGHT_DIR:
        return BACKLIGHT_DIR
    devs = glob.glob("/sys/class/backlight/*")
    if not devs:
        raise RuntimeError("No se encontro ningun dispositivo en /sys/class/backlight")
    BACKLIGHT_DIR = devs[0]
    return BACKLIGHT_DIR


def get_max():
    with open(os.path.join(_find_device(), "max_brightness")) as f:
        return int(f.read().strip())


def get_percent():
    dev = _find_device()
    with open(os.path.join(dev, "brightness")) as f:
        cur = int(f.read().strip())
    return round(cur / get_max() * 100)


def _write_raw(raw):
    with open(os.path.join(_find_device(), "brightness"), "w") as f:
        f.write(str(raw))


def set_percent(pct):
    """Aplica un brillo en porcentaje (se recorta a 5..100), instantaneo."""
    pct = max(5, min(100, int(pct)))
    _write_raw(round(pct / 100 * get_max()))
    return pct


def fade_to(pct, duration=0.4, steps=60):
    """Transicion suave desde el brillo actual hasta `pct` en `duration` seg.

    Interpola sobre el valor RAW (no el %), asi aprovecha toda la resolucion
    del backlight (max ~96000) y no se ven escalones.
    """
    pct = max(5, min(100, int(pct)))
    mx = get_max()
    start = int(get_percent() / 100 * mx)
    target = round(pct / 100 * mx)
    if start == target:
        _write_raw(target)
        return pct
    dt = duration / steps
    for i in range(1, steps + 1):
        raw = round(start + (target - start) * i / steps)
        _write_raw(raw)
        time.sleep(dt)
    return pct


if __name__ == "__main__":
    print(f"device      : {_find_device()}")
    print(f"max_brightness: {get_max()}")
    print(f"actual       : {get_percent()}%")
