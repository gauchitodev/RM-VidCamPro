# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for security problems.

Report them privately through GitHub instead: go to the **Security** tab of this
repository and choose **Report a vulnerability**. You'll get an answer there, and the
fix will be credited to you unless you'd rather stay anonymous.

Helpful things to include: what an attacker could do, the steps to reproduce it, and the
Android version and device you tested on.

## Supported versions

Only the latest version on `main` gets fixes.

## Security model

RM-VidCamPro is designed to have as little attack surface as a camera app can have:

| Area | What it does |
|---|---|
| **Permissions** | Camera and microphone only. **No internet permission**: nothing the app records or logs can leave the phone through it. No storage permission either. |
| **Recordings** | Written through MediaStore to `Movies/Camara`, like any camera. No location is embedded in the files. |
| **Exposed components** | One activity, the launcher one, which other apps can open. It runs with an empty task affinity, so another app can't hijack its task. Nothing else is exported: no services, receivers or providers. |
| **Test hooks** | The ADB test extras (`modo`, `estab`, `medir`) are **ignored unless a key file exists** in the app's own storage, which since Android 11 only ADB (or the app itself) can create. Without that check, any installed app could open the camera with `medir=true` and make it record audio and video. Extras that can't be parsed are ignored too. |
| **Stored data** | Settings and the lens-switch calibration live in private app preferences, with backup disabled. Values that are corrupt or out of range are ignored, so bad data can't stop the app from opening. |
| **Diagnostic log** | `registro.txt`, kept in the app-specific storage that other apps can't read. It holds camera settings and timings, no personal data, and rotates at 256 KB. |
| **Interrupted recordings** | If the app dies mid-recording, the unfinished (unplayable) file is deleted the next time it starts, instead of lingering hidden for a week. Only the app's own files are touched. |
| **Vendor camera modes** | The undocumented ZTE/Qualcomm session modes are used **only on the RedMagic 10 Pro (NX789J)**, and only if its combined camera has the expected layout. Anywhere else the app falls back to standard sessions, so those values are never sent to an unknown camera HAL. |
| **Build** | Built without third-party libraries, so there are no dependencies to keep patched. Not debuggable. Signed with APK Signature Scheme v3. The signing key is never stored in this repository. |

### Out of scope

- Attackers who already have ADB or root access to the phone: they can do anything this
  app can.
- Bugs in the phone's camera HAL or firmware.

## Verifying an APK

Official builds are signed with this certificate:

```
Signer #1 certificate SHA-256 digest: 9a6be5332bf426668a8b7fffd203aa7e655caca558e5fac1dcdf2cbdee7962c9
```

Check an APK before installing it:

```sh
apksigner verify --print-certs camara.apk
```

The line `Signer #1 certificate SHA-256 digest` has to match exactly. If it doesn't, the APK wasn't built from this project, or it was
tampered with.
