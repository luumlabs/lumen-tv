# Lumen TV

A calm, tvOS-inspired home screen for Android TV and Google TV. Light, fast, no ads,
no tracking — and fully in English.

Lumen TV is a fork of [Tigris](https://github.com/lesnolie/Tigris) by **lesnolie**, translated
to English and maintained here. See [Credits](#credits) and [NOTICE](NOTICE).

![Home screen](docs/screenshots/home.png)

## Features

- **Liquid Glass dock** with the apps you actually use, and a second row peeking underneath.
- **Focused app preview** — the selected tile lifts, tilts and catches a highlight.
- **Control Center** on the Back key: power, standby, volume, input source, brightness.
- **Photo frame screensaver** that replaces the built-in one.
- **Phone uploads**: scan a QR code to set the wallpaper, avatar or boot animation.
- **Software updates** built in, signed and verified before installing.
- **Folder support**, hidden apps, and manual ordering.

## Install

```bash
adb connect <TV_IP>:5555
adb install -r Lumen-v3.0.0.apk
adb shell cmd package set-home-activity com.luumlabs.lumen/.HomeActivity
```

Requires Android 9 (API 28) or newer. Enable ADB debugging on the TV first, and accept the
"Allow debugging?" prompt.

## Credits

This project would not exist without **Tigris** by [lesnolie](https://github.com/lesnolie/Tigris),
released under the MIT licence. Lumen TV is an independent fork: the English translation, the
rebranding and the changes on top are ours, but the original design, architecture and the vast
majority of the code are his work. Please star the original.

- Original project: https://github.com/lesnolie/Tigris
- Upstream translation PR: https://github.com/lesnolie/Tigris/pull/31

Apple TV, tvOS and related marks are trademarks of Apple Inc. This project is not affiliated
with, endorsed by, or sponsored by Apple. It merely takes visual inspiration from tvOS.

## Licence

MIT. The original copyright notice is preserved in [LICENSE](LICENSE) as the licence requires.
See [NOTICE](NOTICE) for the full attribution.

## Español

Pantalla de inicio para Android TV inspirada en tvOS: ligera, sin anuncios y sin telemetría.
Es un fork de [Tigris](https://github.com/lesnolie/Tigris) de **lesnolie**, traducido al inglés
y mantenido aquí. El diseño original y la mayor parte del código son suyos; el mérito es suyo.

Instalación:

```bash
adb connect <IP_DE_LA_TV>:5555
adb install -r Lumen-v3.0.0.apk
adb shell cmd package set-home-activity com.luumlabs.lumen/.HomeActivity
```
