<div align="center">

# Lumen TV

**A calm, tvOS-inspired home screen for Android TV and Google TV.**

Liquid Glass dock · Weather and Top 10 widgets · Control Center · Photo-frame screensaver

[![Latest release](https://img.shields.io/github/v/release/luumlabs/lumen-tv?label=release&color=0a84ff)](https://github.com/luumlabs/lumen-tv/releases/latest)
[![Android CI](https://github.com/luumlabs/lumen-tv/actions/workflows/android.yml/badge.svg)](https://github.com/luumlabs/lumen-tv/actions/workflows/android.yml)
[![Android 9+](https://img.shields.io/badge/Android-9%2B-3ddc84)](#requirements)
[![License: MIT](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

[Download](https://github.com/luumlabs/lumen-tv/releases/latest) ·
[Install](#installation) ·
[Compatibility](#compatibility) ·
[Release notes](https://github.com/luumlabs/lumen-tv/releases) ·
[Español](README.es.md)

</div>

![Lumen TV home screen](docs/screenshots/home.jpg)

## Overview

Lumen TV replaces the home screen of an Android TV with one modelled on Apple TV: your apps on a
glass dock, the content of the focused app above it, and the controls you use every day one press
away. It has no ads, no tracking and no account, and it runs on modest hardware: every effect has a
lighter fallback that is chosen automatically on low-end TVs.

Lumen TV is an independent, English-language fork of [Tigris](https://github.com/lesnolie/Tigris)
by lesnolie. See [Credits](#credits).

## Features

**Home screen**

- A Liquid Glass dock with your most-used apps; the rest of the grid peeks out below it.
- tvOS focus: the selected tile lifts on a spring, tilts towards the direction of travel, shifts its
  layers in depth and catches a highlight. A resting tile drifts gently, as under a thumb on the
  Siri Remote.
- The background takes on the colours of the focused app, and the dock's glass refracts them.
- Folders, hidden apps, manual or most-recent ordering, and HDMI inputs pinned as tiles.
- Top Shelf content for supported apps.

**Widgets**

- **Weather**: the temperature, the conditions and the next six hours; press OK for a six-day
  forecast. It finds the TV by its internet address, or search for your city under *Settings →
  Widgets → Location*. Data by [Open-Meteo](https://open-meteo.com).
- **Top 10**: this week's most popular movies and shows in your country, and the best reviewed of
  them, with scores from IMDb, Rotten Tomatoes, TMDB and JustWatch, the cast and where to watch.
  Press OK on a title to choose where to open it: the installed streaming apps that carry it, Nuvio
  or Stremio, each opened at the title. Data by [JustWatch](https://www.justwatch.com).

**Control Center** (the Back key on the home screen)

- Power off, standby, restart, volume, input source, network and memory cleanup.
- Picture brightness on Sony BRAVIA and Xiaomi TVs.

**Screensaver and wallpapers**

- A photo-frame screensaver showing your wallpapers or Apple TV aerial films.
- Wallpapers from Bing, curated feeds, gradient themes or your own photos, uploaded from a phone by
  scanning a QR code.

**Maintenance**

- Built-in software updates, signed and verified before they install.
- Removal and restoration of preinstalled apps.
- A home-screen guard for firmwares that force their own launcher (Xiaomi, TCL and others).

| Top Shelf | All apps |
|---|---|
| ![Top Shelf](docs/screenshots/shelf.jpg) | ![All apps](docs/screenshots/grid.jpg) |
| **Control Center** | **Photo frame** |
| ![Control Center](docs/screenshots/cc.jpg) | ![Photo frame](docs/screenshots/frame.jpg) |

## Requirements

- An Android TV or Google TV device running **Android 9 (API 28) or newer**.
- A computer with [`adb`](https://developer.android.com/tools/releases/platform-tools) on the same
  network as the TV, for installation.
- Network debugging (ADB) enabled on the TV. It is usually under *Settings → Device Preferences →
  About*: press *Build* seven times, then turn on *USB debugging* or *Network debugging* in
  *Developer options*.

## Installation

### Which APK

Every release publishes two packages and a checksum file.

| Package | Use it on |
|---|---|
| `Lumen-vX.Y.Z.apk` (standard) | Most TVs. Declares itself a home screen. |
| `Lumen-guard-vX.Y.Z.apk` (guard) | TCL and FFalcon China firmware, which refuse third-party home-screen APKs. Opens as an app and is kept in front by the home guard. |

### Installer script

The script connects to the TV, downloads the latest release, verifies its SHA-256 checksum, installs
it, makes Lumen the home screen and saves a snapshot of the original launcher so it can be restored.

```sh
curl -fsSL -o install.sh https://raw.githubusercontent.com/luumlabs/lumen-tv/main/install.sh
TV=192.168.1.20 bash install.sh               # standard build
TV=192.168.1.20 bash install.sh -s guard      # guard build
```

The first time the computer connects, the TV asks *Allow USB debugging?*. Tick *Always allow* and
choose *Allow*.

### Manual installation

```sh
adb connect 192.168.1.20:5555
adb install -r Lumen-v3.4.0.apk
adb shell cmd package set-home-activity com.luumlabs.lumen/.HomeActivity
adb shell input keyevent KEYCODE_HOME
```

On firmwares that keep the Home key for their own launcher, grant the permissions the home guard
needs and turn it on:

```sh
P=com.luumlabs.lumen
adb shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener $P/$P.RevivalListener
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell am broadcast -a com.luumlabs.lumen.CONFIG -n $P/.ConfigReceiver --ez guard true
```

> Do not disable the stock launcher on Xiaomi or Redmi TVs (`com.mitv.tvhome`): their fallback
> home refuses third-party launchers, and the TV would start to a black screen. Use the home guard.

On first start, Lumen asks once for local debugging access, which power off, standby and deep
memory cleanup require. When the TV shows *Allow debugging?*, tick *Always allow* and choose
*Allow*.

### Updating

Lumen checks for new releases in the background. Open *Settings → Software Updates* to update
manually or to turn on automatic updates. Packages are verified against the release signing
certificate before installation.

### Restoring the original home screen

```sh
TV=192.168.1.20 bash install.sh restore              # keeps Lumen installed
TV=192.168.1.20 bash install.sh restore --uninstall  # also removes it
```

Run this from the computer used for the installation, which holds the launcher snapshot.

## Configuration

Everything is available on the TV under *Settings* (the gear in the Control Center, or the avatar in
the top-right corner). The same options can be set from a computer:

```sh
adb shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver \
  --es weather_city "Madrid" --es weather_unit c --es charts_country ES --ez clock_24 true
```

| Option | Values |
|---|---|
| `weather`, `charts` | `true` / `false`: show the widgets |
| `weather_unit` | `auto`, `c`, `f` |
| `weather_city` | a city name, optionally followed by its region or country (`"Valencia, Venezuela"`); empty to locate the TV by its IP address |
| `charts_country` | a two-letter country code; empty for automatic |
| `app_tint` | `true` / `false`: background colour follows the focused app |
| `clock_style` | `corner`, `large`, `off` |
| `clock_24` | `true` / `false` |
| `focus_style` | `auto`, `tvos`, `simple` |
| `cc_style` | `auto`, `glass`, `panel` |
| `background` | `daily`, `scenery`, `fresh`, `anime`, `art`, `mine`, `custom`, or a gradient: `green`, `blue`, `purple`, `orange`, `graphite` |
| `intro` | `off`, `boot`, `wake` |
| `guard`, `keep_adb` | `true` / `false` |

The broadcast prints the resulting configuration.

## Compatibility

Lumen works on any Android TV that meets the requirements. TV-specific controls (brightness, volume
routing, HDMI inputs, system settings) use a per-brand adapter:

| Brand | Support |
|---|---|
| Sony BRAVIA | Brightness, volume and inputs through the BRAVIA IP control API, with Android fallbacks. |
| Xiaomi / Redmi (China firmware) | Backlight, inputs through Xiaomi's player, vendor settings; home guard and *Keep ADB on*. |
| TCL / FFalcon (China firmware) | Guard build, inputs through LiveTV's source picker, vendor settings. Brightness is hidden. |
| Skyworth / Coocaa | Inputs through the launcher's source picker. Not yet tested on hardware. |
| Other brands | Standard Android TV controls. |

### Community reports

These rows are submitted by users and are **not verified** by the maintainers. A successful install
does not imply that every feature works on that TV.

<!-- TV_COMPATIBILITY_AUTO_START -->
No structured community reports yet. [Submit a report](https://github.com/luumlabs/lumen-tv/issues/new/choose).
<!-- TV_COMPATIBILITY_AUTO_END -->

To add your TV, [open a compatibility report](https://github.com/luumlabs/lumen-tv/issues/new/choose).
A workflow turns complete reports into a pull request for review.

## Privacy

Lumen has no analytics and no account. It connects to the internet only for the features you use:

| Feature | Service |
|---|---|
| Wallpapers | The selected source: Bing, curated image feeds, or your own links |
| Weather widget | Open-Meteo; the TV's approximate location from ipinfo.io, ipwho.is, ipapi.co or get.geojs.io, unless a city is chosen |
| Top 10 widget | JustWatch |
| Top Shelf | The public web API of the supported app |
| Aerial screensaver | Apple's aerial film servers |
| Software updates | GitHub Releases |

Both widgets can be turned off in *Settings → Widgets*.

## Building from source

Requirements: JDK 17 and the Android SDK with `platforms;android-36` and `build-tools;36.0.0`.
Point `sdk.dir` in `local.properties` at the SDK.

```sh
./gradlew testStandardDebugUnitTest testGuardDebugUnitTest   # unit tests
./gradlew assembleStandardRelease assembleGuardRelease      # both APKs
```

The APKs are written to `app/build/outputs/apk/{standard,guard}/release/`. Local builds are signed
with your debug key, so they cannot update an installed release build.

Releases are built and signed by [GitHub Actions](.github/workflows/release.yml) when a `v*` tag is
pushed; the release notes come from [`docs/releases`](docs/releases).

## Credits

Lumen TV is built on [Tigris](https://github.com/lesnolie/Tigris) by
[lesnolie](https://github.com/lesnolie), released under the MIT License. The original design,
architecture and most of the code are theirs; Lumen TV adds the English interface, the rebranding,
the widgets, the redesign and further changes. Please star the original project.

Third-party components and data sources are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

[MIT](LICENSE). The original Tigris copyright notice is preserved, as the license requires; see
[NOTICE](NOTICE).

Apple TV, tvOS and Siri Remote are trademarks of Apple Inc. IMDb, Rotten Tomatoes, TMDB, JustWatch
and the streaming services named in the app are trademarks of their respective owners. Lumen TV is
not affiliated with, endorsed by or sponsored by any of them.
