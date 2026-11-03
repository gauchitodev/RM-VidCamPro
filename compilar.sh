#!/bin/bash
# Compila el Cámara acá mismo, en el Arch del teléfono, sin Gradle ni GitHub.
#   ./compilar.sh            arma y firma camara.apk
#   ./compilar.sh instalar   además la instala por ADB y la prende
#
# Herramientas (todas en ~/herramientas/android, bajadas con firma o suma verificada):
#   Java 21 (Arch Linux ARM), aapt2 (Debian trixie arm64), d8 + apksigner (build-tools 35)
#   y android.jar (plataforma 34) de Google.
set -euo pipefail

VERSION_CODE=7
VERSION_NAME=3.1

AQUI="$(cd "$(dirname "$0")" && pwd)"
H="$HOME/herramientas/android"
J="$H/jdk/usr/lib/jvm/java-21-openjdk/bin"
D="$H/deb"
SDK="$H/sdk"
FIRMA="$HOME/.config/camara-firma"
B="$AQUI/build"
export LD_LIBRARY_PATH="$D/usr/lib/aarch64-linux-gnu:$D/usr/lib/aarch64-linux-gnu/android:$D/usr/lib/7zip"
AAPT2="$D/usr/lib/android-sdk/build-tools/debian/aapt2"

decir() { printf '\033[1;32m›\033[0m %s\n' "$*"; }

if [ ! -f "$FIRMA/camara.p12" ]; then
  decir "Llave de firma nueva en $FIRMA (una sola vez; si se pierde, la próxima no instala encima)"
  mkdir -p "$FIRMA"; chmod 700 "$FIRMA"
  python3 -I -c 'import secrets; print(secrets.token_urlsafe(24), end="")' > "$FIRMA/clave.txt"
  chmod 600 "$FIRMA/clave.txt"
  "$J/keytool" -genkeypair -keystore "$FIRMA/camara.p12" -storetype PKCS12 -alias camara \
    -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Camara, O=gauchitodev" \
    -storepass:file "$FIRMA/clave.txt" 2>&1 | grep -v '^$' || true
  chmod 600 "$FIRMA/camara.p12"
fi

# La llave y su clave solo para este usuario, siempre (por si algo les cambió los permisos)
chmod 700 "$FIRMA"; chmod 600 "$FIRMA/camara.p12" "$FIRMA/clave.txt"

rm -rf "$B"; mkdir -p "$B/clases" "$B/dex"

decir "Recursos (aapt2)"
"$AAPT2" compile --dir "$AQUI/res" -o "$B/res.zip"
"$AAPT2" link -I "$SDK/android.jar" --manifest "$AQUI/AndroidManifest.xml" \
  --min-sdk-version 34 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

decir "Java (javac)"
"$J/javac" -J-Xshare:auto --release 17 -encoding UTF-8 -Xlint:-options -nowarn \
  -classpath "$SDK/android.jar" -d "$B/clases" $(find "$AQUI/src" "$B/gen" -name "*.java")

decir "Dex (d8)"
"$J/java" -cp "$SDK/d8.jar" com.android.tools.r8.D8 --release --min-api 34 \
  --lib "$SDK/android.jar" --output "$B/dex" $(find "$B/clases" -name '*.class')

decir "Empaquetar y alinear"
python3 -I "$AQUI/empaquetar.py" "$B/base.apk" "$B/dex/classes.dex" "$B/sin-firma.apk"

decir "Firmar (apksigner)"
"$J/java" -jar "$SDK/apksigner.jar" sign --ks "$FIRMA/camara.p12" --ks-type PKCS12 \
  --ks-key-alias camara --ks-pass "file:$FIRMA/clave.txt" --v4-signing-enabled false \
  --out "$AQUI/camara.apk" "$B/sin-firma.apk"
"$J/java" -jar "$SDK/apksigner.jar" verify --print-certs "$AQUI/camara.apk" | grep -E 'SHA-256' | head -1
decir "Listo: $AQUI/camara.apk ($(du -k "$AQUI/camara.apk" | cut -f1) KB, versión $VERSION_NAME)"

if [ "${1:-}" = "instalar" ]; then
  export ADB_SERVER_SOCKET="localfilesystem:$HOME/.android/adb-server.sock"
  adb -s 127.0.0.1:5555 install -r "$AQUI/camara.apk"
fi
