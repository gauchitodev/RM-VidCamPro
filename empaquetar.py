"""Junta la base que arma aapt2 con el classes.dex y alinea lo que va sin comprimir.

Android 11+ exige resources.arsc sin comprimir y alineado a 4 bytes. En vez de zipalign
(binario solo x86_64), se alinea acá con el campo extra 0xD935 que usa el propio zipalign.

Uso: python3 -I empaquetar.py base.apk classes.dex salida.apk
"""
import struct
import sys
import zipfile

SIN_COMPRIMIR = {"resources.arsc", "classes.dex"}


def extra_alineado(desde, nombre, alinear=4):
    # Cabecera local: 30 bytes + nombre + extra; los datos tienen que caer en múltiplo de `alinear`.
    base = desde + 30 + len(nombre.encode())
    relleno = 0
    while (base + 6 + relleno) % alinear:
        relleno += 1
    return struct.pack("<HHH", 0xD935, 2 + relleno, alinear) + b"\0" * relleno


def main(base, dex, salida):
    with zipfile.ZipFile(base) as zb, zipfile.ZipFile(salida, "w") as zs:
        entradas = [(i, zb.read(i)) for i in zb.infolist()]
        entradas.append((zipfile.ZipInfo("classes.dex", date_time=(1981, 1, 1, 0, 0, 0)), open(dex, "rb").read()))
        for viejo, datos in entradas:
            info = zipfile.ZipInfo(viejo.filename, date_time=(1981, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            if viejo.filename in SIN_COMPRIMIR:
                info.compress_type = zipfile.ZIP_STORED
                info.extra = extra_alineado(zs.fp.tell(), viejo.filename)
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
            zs.writestr(info, datos)
    # Verificación: los que van sin comprimir quedaron alineados.
    with zipfile.ZipFile(salida) as z, open(salida, "rb") as f:
        for i in z.infolist():
            if i.filename in SIN_COMPRIMIR:
                f.seek(i.header_offset)
                cab = f.read(30)
                n, e = struct.unpack("<HH", cab[26:30])
                datos = i.header_offset + 30 + n + e
                assert i.compress_type == zipfile.ZIP_STORED and datos % 4 == 0, f"{i.filename} desalineado"


if __name__ == "__main__":
    main(*sys.argv[1:4])
