# Cámara

Cámara de video propia para el RedMagic 10 Pro (NX789J). Cambia de lente trasera
mientras graba, sin cortar el video, con la estabilización de fábrica.

## Qué hace

- Graba con la cámara lógica 4 (gran angular + principal) en 4K 30, 1080 60 o 1080 30.
- Botones 0,6 · 1 · 2 · 5 y pellizco, también grabando.
- Entre 0,67× y 1,06× el HAL entrega la gran angular entera: el zoom lo hace el
  motor en la placa gráfica (`Motor.java`). En el cambio de cámara física mide la
  diferencia que queda (`Registro.java`, correlación de fase) y la disuelve.
- Estabilización con los modos de operación de la cámara de fábrica (32798–32801).
- Tocar para enfocar y medir; mantener para bloquear.
- Modo PRO: EV, ISO, obturador, balance, enfoque manual, perfil (Normal / Plano / Vivo),
  códec (H.264 / H.265) y cuadrícula.
- Videos en Galería → Movies/Camara.

## Compilar

Se compila en el mismo teléfono (Arch en chroot), sin Gradle:

    ./compilar.sh            # arma y firma camara.apk
    ./compilar.sh instalar   # además la instala por ADB

Usa las herramientas de `~/herramientas/android` (Java 21, aapt2, d8, apksigner,
android.jar 34). La llave de firma está en `~/.config/camara-firma` y no va en el
repositorio: si se pierde, la próxima versión no se instala encima.

## Pruebas por ADB

    am start -n dev.gauchito.camara/.Camara --ei modo 0..2 --ei estab 0..2
    am start -n dev.gauchito.camara/.Camara --ez medir true   # rampa de zoom grabando

Registro en `/sdcard/Android/data/dev.gauchito.camara/files/registro.txt`.
