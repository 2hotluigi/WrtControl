#!/usr/bin/env python3
"""Genera app/src/main/assets/oui.txt: de qué fabricante es cada prefijo de MAC.

Sale de la lista `manuf` de Wireshark, que a su vez sale de los registros públicos del IEEE
(MA-L, los bloques de 24 bits). La app la lleva dentro: nunca se manda una MAC a nadie para
saber de quién es.

    python tools/update-oui.py                # descarga la lista (se actualiza una vez por semana)
    python tools/update-oui.py ruta/a/manuf   # usa una ya descargada

Formato del resultado (texto UTF-8): una línea por fabricante, `Nombre<TAB>AABBCC AABBCD …`, con
los prefijos en hexadecimal y en mayúsculas. Va sin comprimir a propósito: el APK ya comprime los
assets, y a los que acaban en .gz Android los descomprime y les quita la extensión al empaquetar.
"""
import gzip
import os
import re
import sys
import urllib.request

SOURCE = 'https://www.wireshark.org/download/automated/data/manuf.gz'
TARGET = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'oui.txt')

# Coletillas legales que no dicen nada a quien mira la lista de su casa.
SUFFIXES = re.compile(
    r'[\s,]+(inc|incorporated|corp|corporation|co|company|ltd|limited|llc|gmbh|ag|sa|s\.a|srl|s\.r\.l|bv|b\.v|nv|ab|as|a/s|oy|plc|pte|'
    r'pty|kg|spa|s\.p\.a|sas|kk|k\.k|international|technologies|technology|electronics|communications?|group|holdings?)\.?$',
    re.IGNORECASE,
)


def clean(name):
    name = re.sub(r'\s+', ' ', name).strip()
    for _ in range(4):
        shorter = SUFFIXES.sub('', name).rstrip(' ,.&')
        if shorter == name or len(shorter) < 3:
            break
        name = shorter
    # Todo en mayúsculas ("SAMSUNG ELECTRO-MECHANICS") cansa; las siglas cortas se quedan.
    if name.isupper() and len(name) > 5:
        name = name.title()
    return name


def read(path):
    if path:
        data = open(path, 'rb').read()
    else:
        data = urllib.request.urlopen(urllib.request.Request(SOURCE, headers={'User-Agent': 'update-oui'}), timeout=60).read()
    if data[:2] == b'\x1f\x8b':
        data = gzip.decompress(data)
    return data.decode('utf-8', 'replace')


def main():
    vendors = {}
    for line in read(sys.argv[1] if len(sys.argv) > 1 else None).splitlines():
        parts = [p.strip() for p in line.split('\t')]
        # Solo los bloques de 24 bits: los de 28 y 36 son de fabricantes pequeños y llevan otro formato.
        if line.startswith('#') or len(parts) < 3 or not re.fullmatch(r'[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){2}', parts[0]):
            continue
        name = clean(parts[2])
        if name and not name.lower().startswith(('private', 'ieee registration')):
            vendors.setdefault(name, []).append(parts[0].replace(':', '').upper())
    text = ''.join('%s\t%s\n' % (name, ' '.join(sorted(prefixes))) for name, prefixes in sorted(vendors.items()))
    data = text.encode('utf-8')
    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    open(TARGET, 'wb').write(data)
    packed = len(gzip.compress(data, 9))
    print('%d prefijos de %d fabricantes, %d KB (unos %d KB dentro del APK)'
          % (sum(map(len, vendors.values())), len(vendors), len(data) // 1024, packed // 1024))


if __name__ == '__main__':
    main()
