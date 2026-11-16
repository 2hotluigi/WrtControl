"""Compara dos .aar (o .jar) por su contenido: los ficheros de dentro, también los de un .jar
anidado (classes.jar), sin mirar fechas ni el orden del zip. Sale con 1 si difieren.

    python3 -I tools/compare-aar.py app/libs/wrtcvpn.aar /tmp/wrtcvpn.aar
"""
import hashlib
import io
import sys
import zipfile


def entries(data, prefix=""):
    """{ruta: sha256} de un zip, entrando en los .jar de dentro."""
    out = {}
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            body = z.read(info)
            name = prefix + info.filename
            if name.endswith(".jar"):
                out.update(entries(body, name + "!/"))
            else:
                out[name] = hashlib.sha256(body).hexdigest()
    return out


def main(a, b):
    left = entries(open(a, "rb").read())
    right = entries(open(b, "rb").read())
    diff = sorted(n for n in left.keys() | right.keys() if left.get(n) != right.get(n))
    for name in diff:
        state = "solo en " + (a if name not in right else b) if name not in left or name not in right else "distinto"
        print(f"  {state}: {name}")
    if diff:
        print(f"{a} y {b} difieren en {len(diff)} de {len(left.keys() | right.keys())} ficheros")
        return 1
    print(f"{a} y {b}: mismo contenido ({len(left)} ficheros)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
