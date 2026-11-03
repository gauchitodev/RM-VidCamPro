# RM-VidCamPro

**A video-only camera app for the RedMagic 10 Pro that switches rear lenses mid-recording, with no cut and no jump.**

The stock camera can't change lenses while you record. RM-VidCamPro can: start on the
ultra-wide, glide to the main camera and push to 5×, all in one continuous clip, with the
phone's own hardware stabilization still on.

It's a small, dependency-free Android app (plain Java, no Gradle, no libraries). It's
built and signed on the phone itself.

---

## Highlights

- **Lens switching while recording.** 0.6× · 1× · 2× · 5× buttons, plus pinch-to-zoom,
  all available mid-clip.
- **A real zoom across the lens switch.** The handover from the ultra-wide to the main
  camera is smoothed on the GPU, so the image doesn't jump. [How it works](#how-the-seamless-switch-works).
- **Factory-grade stabilization.** Uses the same Qualcomm/ZTE EIS session modes as the
  stock camera app (normal and "+"), at 30 and 60 fps.
- **Tap to focus and meter; long-press to lock** focus, exposure and white balance.
- **PRO panel** with live readouts of what the camera is doing:

  | Control | Range |
  |---|---|
  | Exposure compensation | −2 to +2 EV |
  | ISO | 100 – 6400 |
  | Shutter | 1/30 – 1/8000 s |
  | White balance | Auto, Locked, Warm, Fluorescent, Daylight, Cloudy, Shade |
  | Focus | Auto or manual, ∞ to ~11 cm |
  | Profile | Normal, **Flat** (for grading), Vivid |
  | Codec | H.264 or H.265 (HEVC, ~30 % smaller) |
  | Grid | Rule-of-thirds overlay |

- **4K 30, 1080p 60, 1080p 30.** Clips are saved to `Movies/Camara` and show up in the
  gallery right away. The last one is a tap away from the viewfinder.
- **Works in any orientation.** Controls rotate with the phone and clips are saved upright.
- Your settings are remembered between sessions.

---

## How the seamless switch works

The RedMagic 10 Pro exposes a **logical camera** (ID `4`) that combines the ultra-wide
(physical `2`) and the main camera (physical `0`), with a zoom range of 0.67×–10×. In
theory, changing `CONTROL_ZOOM_RATIO` is all a seamless switch needs. In practice,
measuring the output frame by frame showed this:

> **The HAL does not zoom the ultra-wide.** From 0.67× up to ~1.05× it always delivers the
> full ultra-wide frame, ignoring both `CONTROL_ZOOM_RATIO` and `SCALER_CROP_REGION`. At
> ≈1.052× it hands over to the main camera, already zoomed in. The result is a **1.6× size
> jump in a single frame**, plus a ~30 % brightness change and a focus hunt.

So RM-VidCamPro takes over that part:

1. **GPU pipeline.** The camera writes into our own `SurfaceTexture`. Every frame is drawn
   with OpenGL ES into a landscape framebuffer, then copied to the viewfinder (rotated) and
   to the encoder.
2. **Our own zoom on the ultra-wide.** Below 1.06× the app asks the HAL for the full
   ultra-wide and does the zoom on the GPU. Above that, the main camera zooms natively.
   Both meet at the same field of view.
3. **Measuring what's left.** On the exact frame where the physical camera changes
   (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`), two 256×144 thumbnails are compared with
   FFT phase correlation over a small range of scales. That gives the residual scale,
   offset and per-channel color gain between the two sensors.
4. **Dissolving it.** That correction is applied on the first new frame and released
   gradually: geometry over 0.7 s, color and brightness over 1.5 s, plus a 120 ms
   cross-fade. The last measurement is stored and reused the next time, so the correction
   is ready on the very first frame.
5. **Pre-focus.** Just before handing over, the main camera gets its last known focus
   distance, so it doesn't start hunting from scratch.

What can't be hidden: the HAL drops about one frame (≈33 ms) at the switch.

### Getting EIS to cooperate

Two things the stock app does, found by inspecting it, turned out to be essential:

- **EIS needs a vendor session mode.** Asking for `CONTROL_VIDEO_STABILIZATION_MODE` in a
  regular session crashes the camera HAL. The session has to be created with ZTE's
  operating modes: `32798` (EIS) / `32799` (EIS+) at 30 fps, `32800` / `32801` at 60 fps.
- **EIS needs an encoder stream.** With the GPU pipeline the HAL still wants an output it
  recognizes as video. RM-VidCamPro adds a `PRIVATE` `ImageReader` with
  `USAGE_VIDEO_ENCODE` and the `VIDEO_RECORD` stream use case, and simply discards its
  frames.

---

## Requirements

- **RedMagic 10 Pro (NX789J).** The camera IDs, zoom thresholds and session modes are
  specific to this phone. Other Nubia/RedMagic models on Snapdragon may work with tweaks.
  Anything else almost certainly won't.
- Android 14 or later (min/target SDK 34).
- Permissions: camera and microphone. No storage permission is needed: clips are written
  through MediaStore.

---

## Building

There's no Gradle. `compilar.sh` drives the Android build tools directly, and runs fine
on an ARM64 Linux (here, an Arch Linux chroot on the phone itself):

```sh
./compilar.sh            # build and sign camara.apk
./compilar.sh instalar   # build, then install over ADB
```

It expects the tools in `~/herramientas/android`: JDK 21, `aapt2`, `d8` and `apksigner`
(build-tools 35), and `android.jar` (platform 34). On first run it creates a signing key in
`~/.config/camara-firma`. Keep that key safe: without it, a new build won't install over
the old one.

## Project layout

| File | What it does |
|---|---|
| `Camara.java` | The activity: UI, camera session, zoom split between HAL and GPU, recording |
| `Motor.java` | GPU engine: EGL/GLES, digital zoom, switch correction, preview and encoder output |
| `Registro.java` | Image registration: FFT phase correlation, scale search, color gains |
| `Ajustes.java` | PRO settings and how they map to `CaptureRequest` keys |
| `Piezas.java` | Small custom views (grid, focus ring) |
| `Detector.java` | Diagnostic screen that lists every camera an app can open |

## Testing over ADB

The camera activity has to be open to other apps (the launcher starts it), so its test
extras are **ignored unless you unlock them first** with a key file. Only ADB can create it:

```sh
K=/sdcard/Android/data/dev.gauchito.camara/files/pruebas
adb shell touch $K                                                     # unlock the test hooks
adb shell am start -n dev.gauchito.camara/.Camara --ei modo 0 --ei estab 1   # 4K 30 with EIS
adb shell am start -n dev.gauchito.camara/.Camara --ez medir true            # zoom-ramp recording test
adb shell rm $K                                                        # lock them again
```

`modo`: 0 = 4K 30, 1 = 1080p 60, 2 = 1080p 30. `estab`: 0 = off, 1 = EIS, 2 = EIS+.
The test mode records a slow 0.67× → 1.3× ramp and logs every frame (timestamp, zoom,
active physical camera, ISO, exposure, focus). The log is written to
`/sdcard/Android/data/dev.gauchito.camara/files/`.

---

## Status

Working and in daily use, still being polished. On the list:

- Check that EIS also applies to the GPU stream, and compare it with the stock app
- 4K currently delivers ~29 of 30 fps
- Rotate the PRO panel with the phone
- Ideas: slow motion (the camera supports constrained high-speed video), 10-bit / HDR,
  histogram, horizon level, focus peaking

## Security

The app only asks for the camera and the microphone, has no internet permission, and
exposes nothing but its launcher screen. Read [SECURITY.md](SECURITY.md) for the full
model, how to verify an APK's signature, and how to report a vulnerability privately.

## A note on language

The code (identifiers and comments) is in Spanish, and so is the UI. Everything that
matters for understanding how it works is explained above.

## License

[MIT](LICENSE). Use it, change it, share it.

## Disclaimer

Not affiliated with, or endorsed by, Nubia, RedMagic, ZTE or Qualcomm. The vendor session
modes were found by inspecting the phone's own camera app. They're undocumented, may change
with a firmware update, and are used at your own risk. The app only uses them on the
RedMagic 10 Pro, and only when its combined camera has the expected layout. In testing, a
bad request only restarts the camera service. It doesn't harm the phone.
