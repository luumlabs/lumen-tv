# Installing Lumen TV — instructions for AI agents

You are installing Lumen TV, an Android TV launcher (a fork of [Tigris](https://github.com/lesnolie/Tigris)), onto the user's TV from their computer. Follow these steps in order. Talk to the user in their language. Lumen's own interface is in English: when you point the user to a Lumen menu or button, give its English label exactly as it appears on the TV, and translate it in your explanation if they don't read English. The TV is someone's home device: whenever a step below says to ask, ask first.

| | |
|---|---|
| Package | `com.luumlabs.lumen` |
| Releases | https://github.com/luumlabs/lumen-tv/releases (stable releases only) |
| Release assets | `Lumen-vX.Y.Z.apk` (standard), `Lumen-guard-vX.Y.Z.apk` (guard), `SHA256SUMS-vX.Y.Z.txt` |
| Settings on the TV | Control Center (Back on the home screen) → **Settings**, the avatar in the top-right corner, or the **Home Screen Settings** app. The page is titled Settings and has the categories Appearance, Widgets, Wallpaper & Screen Saver, Boot Animation, Apps, Memory & Power and System, plus Software Updates and About. |

Lumen and Tigris (`com.leslie.tvhome`) are separate apps with different package names and signing keys. Installing Lumen neither upgrades nor removes Tigris, and it takes none of Tigris's settings.

## Hard rules

- Never run `svc power shutdown`, `svc power reboot`, `reboot` or `input keyevent KEYCODE_POWER` / `KEYCODE_SLEEP` without asking. A powered-off TV can only be turned back on with its remote.
- Never `pm uninstall` a system app or the TV's own launcher.
- **Never disable the stock launcher on Xiaomi / Redmi** (`com.mitv.tvhome`, PatchWall). The fallback home refuses third-party launchers too, so the TV boots to a black screen. On Xiaomi, TCL and similar firmwares use the home guard (section 10) instead. On other TVs, disable a launcher only with the user's consent (step 5), and tell them how to undo it.
- Before you send `KEYCODE_DPAD_CENTER` / `ENTER` on the TV, check what is focused (step 6). Several Lumen buttons turn the TV off: in the Control Center, **Standby** acts at once, and **Power off** and **Restart** ask for confirmation that a second OK accepts.
- Do not change other TV settings (animation scales, app permissions, background restrictions) unless the user asks.

## 0. What you need

- A computer on the same local network as the TV, with `adb` installed (Android SDK Platform-Tools). Check with `adb version`. If it is missing, install it: Windows `winget install Google.PlatformTools`, macOS `brew install android-platform-tools`, Debian/Ubuntu `apt install adb`.
- The TV's IP address. Ask the user. It is under Settings → Network or About on most TVs.
- ADB debugging enabled on the TV. Most users have never done this, so if `adb connect` fails in step 1, walk them through it for their brand. Ask for the brand if you don't know it. China-market firmware shows these menus in Chinese; the Chinese names are given in brackets.

| TV | How to turn on ADB |
|---|---|
| **Xiaomi / Redmi** | Settings (设置) → About (关于) → **Model** (产品型号): press OK repeatedly until it says developer mode is on. Then Settings → Account & Security (账号与安全): turn on **ADB debugging** (ADB 调试) and **Install apps from unknown sources** (安装未知来源的应用). |
| **TCL / FFalcon (雷鸟)** | Settings (设置) → About (关于) → **Device info** (本机信息), then press **Up, Down, Left, Right** on the remote, less than a second apart. A **More** (更多) group appears at the bottom; turn on **ADB** there. |
| **Sony BRAVIA** | Settings → System → About → press OK on **Build** (Android TV OS build) 7 times. Then Settings → System → Developer options → turn on **USB debugging**. |
| **Stock Android TV / Google TV**, Hisense and others | Settings → Device Preferences → About → press **Build** 7 times. Then turn on **USB debugging** in Developer options (sometimes called Network debugging or ADB debugging). |

The first time the computer connects, the TV asks "Allow USB debugging?" (允许 USB 调试吗？). The user must choose **Allow** and tick **Always allow from this computer**.

## 1. Connect

```sh
adb connect <TV_IP>:5555
adb -s <TV_IP>:5555 get-state     # should print "device"
```

If it prints `unauthorized`, the TV is showing "Allow USB debugging?". Ask the user to tick "Always allow from this computer" and press Allow, then retry. If the connection is refused, ADB is off (step 0). Xiaomi turns it off again on every wake from standby until Lumen's "keep ADB on" option is set (step 4). Use `-s <TV_IP>:5555` on every later command.

## 2. Check the TV

```sh
adb -s <TV_IP>:5555 shell getprop ro.build.version.sdk          # must be >= 28 (Android 9)
adb -s <TV_IP>:5555 shell getprop ro.product.manufacturer
adb -s <TV_IP>:5555 shell getprop ro.product.model
adb -s <TV_IP>:5555 shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <TV_IP>:5555 shell settings get secure screensaver_components
adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services
adb -s <TV_IP>:5555 shell settings get secure enabled_notification_listeners
adb -s <TV_IP>:5555 shell pm path com.leslie.tvhome                # Tigris already installed?
```

Note the current home activity (the stock launcher), the current screensaver, the accessibility services and the notification listeners; you need them to undo the install. Tell the user which features they will get: section 9 lists what each brand supports. Section 9 also covers adapting the app when something doesn't work.

If Tigris is installed and its home guard is on, both apps may fight over the Home key. Tell the user, and ask whether to turn Tigris's guard off before you continue (in Tigris's settings, or with Tigris's own broadcast: `adb -s <TV_IP>:5555 shell am broadcast -a com.leslie.tvhome.CONFIG -n com.leslie.tvhome/.ConfigReceiver --ez guard false`), or to restore and uninstall Tigris by its own instructions.

## 3. Install

There are two builds:

- `Lumen-vX.Y.Z.apk` (standard) declares itself a home screen. Use it everywhere except TCL.
- `Lumen-guard-vX.Y.Z.apk` declares no home screen and appears as an ordinary app. TCL refuses any APK that declares one unless TCL signed it. The guard build is otherwise identical; the home guard (section 10) keeps it in front.

**Always resolve the latest Release asset**; don't construct a `releases/latest/download/Lumen.apk` URL (each asset name includes its version). The helper reads the latest stable release of `luumlabs/lumen-tv`, verifies the APK against the release's `SHA256SUMS` file and prints the downloaded filename:

```sh
curl -fsSL -o download-release.py https://raw.githubusercontent.com/luumlabs/lumen-tv/main/tools/download-release.py
APK=$(python3 download-release.py standard)
adb -s <TV_IP>:5555 install -r "$APK"
```

`python3 download-release.py guard` fetches the guard build; `--version vX.Y.Z` fetches an exact stable release. On Windows PowerShell use `curl.exe` to download the helper, `$apk = python .\download-release.py standard`, and `adb -s <TV_IP>:5555 install -r $apk`.

On macOS and Linux, `install.sh` can do the Android version check and snapshot from step 2 and all of steps 3–4 in one go instead: it saves the original launcher, screensaver, accessibility services and notification listeners for restore, downloads and verifies the APK, installs it, makes the step 4 grants, sets Lumen as home (standard build), and turns the home guard on for Xiaomi, TCL and FFalcon, or wherever Lumen didn't become the default home. It never uninstalls, disables or reboots anything. Add `KEEP_ADB=1` only if the user agreed to keep ADB on (step 4):

```sh
curl -fsSL https://raw.githubusercontent.com/luumlabs/lumen-tv/main/install.sh | TV=<TV_IP> bash
# guard build: ... | TV=<TV_IP> bash -s -- -s guard
# exact version: VERSION=vX.Y.Z; a local APK instead of a download: APK=<file>
```

Its snapshot is kept on this computer in `~/.local/state/tigris/<TV_IP>:5555/` (or under `$XDG_STATE_HOME`, or `STATE_DIR` if set; the folder name is inherited from Tigris). An upgrade never overwrites it. Tell the user to keep it: the Restore section needs it.

**If installing fails with `INSTALL_FAILED_VERIFICATION_FAILURE`** (TCL: its verifier refuses every adb install), install the guard build through TCL's own 多屏互动 (T-Cast) instead. It needs Python 3 and the repository's `tools/tcast-push.py`:

```sh
GUARD_APK=$(python3 download-release.py guard)
curl -fsSL -o tcast-push.py https://raw.githubusercontent.com/luumlabs/lumen-tv/main/tools/tcast-push.py
python3 tcast-push.py <TV_IP> "$GUARD_APK"
```

The script serves the APK from this computer on port 8765. The TV shows "多屏互动：正在发起应用推送" (T-Cast: an app push is starting) in a corner. Tell the user to choose **同意** (Agree) on the TV. If the computer's firewall asks, allow the connection. If the script can't reach port 6553 (no 多屏互动 on that model), fall back to installing on the TV itself:

```sh
adb -s <TV_IP>:5555 push "$GUARD_APK" /sdcard/Download/Lumen-guard.apk
adb -s <TV_IP>:5555 shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity   # if 当贝市场 (Dangbei Market) is installed
```

Then ask the user to pick Lumen in Dangbei's package manager (or with any file manager on the TV) and install it. Check the result with `adb shell pm path com.luumlabs.lumen`. `install.sh` stops at the same refused adb install, so after either of these, continue with step 4 by hand.

If GitHub is unreachable, build from source instead: clone https://github.com/luumlabs/lumen-tv, then `./gradlew assembleStandardRelease assembleGuardRelease` (needs JDK 17 and an Android SDK with `platforms;android-36`; set `sdk.dir` in `local.properties`). The APKs are `app/build/outputs/apk/standard/release/app-standard-release.apk` and `app/build/outputs/apk/guard/release/app-guard-release.apk`. They are signed with this computer's debug key, not the release key (see 9.1).

## 4. Make it the home screen

Standard build:

```sh
adb -s <TV_IP>:5555 shell cmd package set-home-activity com.luumlabs.lumen/.HomeActivity
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME
```

Guard build: there is no home activity to set. Open it with `adb -s <TV_IP>:5555 shell am start -n com.luumlabs.lumen/.HomeActivity`, then make the grants below and turn on the home guard.

Then grant what only adb can grant. These are needed on every TV whose system keeps the Home key for its own launcher (Xiaomi, TCL, most Chinese firmwares), and harmless elsewhere:

```sh
adb -s <TV_IP>:5555 shell pm grant com.luumlabs.lumen android.permission.WRITE_SECURE_SETTINGS
adb -s <TV_IP>:5555 shell cmd notification allow_listener com.luumlabs.lumen/com.luumlabs.lumen.RevivalListener
adb -s <TV_IP>:5555 shell appops set com.luumlabs.lumen SYSTEM_ALERT_WINDOW allow
adb -s <TV_IP>:5555 shell appops set com.luumlabs.lumen APP_AUTO_START allow      # TCL only; other firmwares print an error, ignore it
adb -s <TV_IP>:5555 shell appops set com.luumlabs.lumen APP_ASSOC_START allow     # TCL only
```

| Grant | What it is for |
|---|---|
| `WRITE_SECURE_SETTINGS` | Switches the home guard back on after the system turns it off; keeps the photo frame as screensaver; keeps ADB on; Xiaomi's backlight slider |
| Notification listener | Restarts Lumen about half a second after the system kills it, and at boot. No notification is read |
| `SYSTEM_ALERT_WINDOW` | Opening the home screen from the background after boot |
| TCL `APP_AUTO_START` / `APP_ASSOC_START` | TCL blocks third-party apps from starting at boot otherwise |

If step 5 shows the stock launcher coming back, turn on the home guard (section 10). On Xiaomi, also ask whether to keep ADB on: Xiaomi switches ADB off on every wake, which makes later updates through adb impossible until the user turns it on again in settings.

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver --ez guard true --ez keep_adb true
# prints: Broadcast completed: result=0, data="guard=true keep_adb=true secure_settings=true background=… low_end=…"
```

The result line echoes every setting. `secure_settings=false` means the `WRITE_SECURE_SETTINGS` grant above did not take: the choice is saved, but the guard and keep-ADB do nothing until it is granted.

The user can change both later in Settings → System → **Home screen guardian** / **Keep ADB debugging on**. These rows only appear where they matter: Home screen guardian on Xiaomi, TCL, wherever Lumen is not the default home, or once the guard is on; Keep ADB debugging on on Xiaomi only, or once it is on.

## 5. Verify

Take a screenshot and look at it:

```sh
adb -s <TV_IP>:5555 exec-out screencap -p > tv.png
```

You should see a compact clock, two round buttons (network and Control Center) and an optional profile portrait in the top-right corner; a row of app tiles on a glass dock near the bottom; and, unless they were turned off, the weather and Top 10 widgets on glass above the dock. Back opens the Control Center: separate floating glass controls, or a dark panel on low-end TVs. Press Home (`input keyevent KEYCODE_HOME`) and check again.

If the stock launcher appears instead:

- On Xiaomi, TCL and other Chinese firmwares, turn on the home guard (step 4, section 10). **Do not disable the stock launcher on Xiaomi.**
- On other TVs, run step 4 again and press Home once more. If it still comes back, try the guard first. Only if that fails, ask the user whether to disable the vendor launcher, and only then:

```sh
adb -s <TV_IP>:5555 shell pm disable-user --user 0 <stock launcher package>
# undo: adb -s <TV_IP>:5555 shell pm enable <stock launcher package>
```

On first start the app downloads background photos (needs internet), and the weather and Top 10 widgets fetch their data; the first minute may show a gradient. After each boot a short boot animation plays before the home screen (on by default; any key skips it; Settings → Boot Animation).

## 6. Local debugging authorization (power, sleep, deep memory cleanup)

On first start Lumen shows "Enable power off and memory freeing". When the user presses **Start**, the TV shows "Allow debugging?" again, this time from the TV to itself. The user must tick **Always allow** and press **Allow**. Check the screen with `screencap` and guide them. You can verify afterwards: the Control Center (Back key on the home screen) no longer shows the line "Local debugging not authorized: power off, standby and deep clean unavailable", and Settings → System → **Local debugging authorization** reads Authorized (pressing OK there asks again). Once this is allowed, Lumen can also give itself the step 4 grants (Settings → System → Home screen guardian does that).

If you need to press buttons for the user, first find the focused element:

```sh
adb -s <TV_IP>:5555 shell uiautomator dump /sdcard/u.xml && adb -s <TV_IP>:5555 shell cat /sdcard/u.xml
```

Look for `focused="true"` and its `text` / `bounds` before sending `KEYCODE_DPAD_CENTER`.

## 7. Sony BRAVIA only: brightness, plus TV-side volume and HDMI

Skip this step if the manufacturer is not Sony. The TV's IP control API must accept a registration for client `tvhome:1` (the id is inherited from Tigris and kept so existing registrations still work):

```sh
BODY='{"method":"actRegister","id":1,"version":"1.0","params":[{"clientid":"tvhome:1","nickname":"TVHome","level":"private"},[{"value":"yes","function":"WOL"}]]}'
curl -s http://<TV_IP>/sony/accessControl -d "$BODY"
```

The TV now shows a 4-digit PIN for about a minute. Ask the user to read it out, or read it from a `screencap`. Then:

```sh
curl -s -u ":<PIN>" http://<TV_IP>/sony/accessControl -d "$BODY"   # {"result":[]} means success
```

If the first call returns an error or nothing, ask the user to enable Settings → Network & Internet → Remote device settings → "Control remotely" (the menu name varies by model), then retry. To check: open the Control Center. The brightness slider is shown, and **Input** lists the TV's sources.

## 8. Optional settings

Tell the user these exist; change them only if asked. All but the HDMI shortcut are in Settings (see the table at the top):

- **Wallpaper & Screen Saver → Background** (also the **Wallpaper** tile on the home screen): every choice shown as a picture, picked with one press — Landscape photos (Bing), Landscapes, Cute pets, Anime wallpapers, Fine art, My photos (uploaded), Custom link, or a gradient theme (Green, Blue, Purple, Orange, Dark gray); plus **Photo rotation interval** and **Change now**. The Wallpaper screen shows a QR code: a phone on the same Wi-Fi opens a page to upload its own photos, paste picture links (an image, a random-image API, or a page / JSON listing images; fetched daily), and pick the background and screensaver. That phone page is still in Chinese; its sections are 主屏幕背景 (home background), 屏保 (screensaver), 开机动画 (boot animation), 设置页背景 (settings background), 头像 (avatar), 设置图标 (settings icon), 我的照片 (my photos) and 自定义图片链接 (custom picture links).
- **Wallpaper & Screen Saver → Show frame when the TV is idle** (the photo frame as the system screensaver), **Start after** (idle time), **Screensaver content** (Photos, or Apple TV aerial videos streamed from Apple, about 15–20 Mbps; falls back to photos offline) and **Time per photo**. On Xiaomi, which shows its own screensaver instead of Android's, the home screen starts the frame 15 s before Xiaomi's would.
- **Widgets**: **Weather widget** (Open-Meteo), **Temperature** (Auto, Celsius, Fahrenheit), **Location** (found from the TV's IP address; a named city can only be set over adb, below), **Top 10 widget** (JustWatch) and its **Region**.
- HDMI inputs on the home screen: Control Center → **Input** → **Show on Home Screen…**.
- **Memory & Power**: **Apps kept when freeing memory**, automatic freeing, and what the remote's power button does.
- **Appearance**: **Time format**, **Clock position**, **Colour follows the focused app**, **Focus animation**, **Control Center style** (the last two default to Auto, which picks the cheaper look on low-end TVs), **Upload avatar** (opens the phone page at its avatar section; the private `profile_avatar.png` is separate from the settings icon), **Show avatar in the top-right corner**, **Settings background** and **Settings icon**.
- **Boot Animation**: **Boot animation** (Off, On boot — the default — or On boot and wake), **Animation content** (the built-in video, an uploaded video, or the Lumen wordmark), **Wait after wake**, **Sound** and **Preview**. A default video is bundled. The user can upload their own on the phone page, or you can push one with `adb push <video> /sdcard/Android/data/com.luumlabs.lumen/files/intro.mp4`. Don't download a trademarked clip for the user. The same goes for a custom settings icon (`files/settings_icon.png`) and a settings-page background (`files/settings_bg.jpg`, a 16:9 picture with its subject on the left), both also uploadable on the phone page.
- **Software Updates**: checks the latest stable release of `luumlabs/lumen-tv` and installs it after verifying the package and its signature; the TV may ask the user to allow Lumen to install unknown apps.

From a computer, the `CONFIG` broadcast sets exactly the options in the table below; everything else is set on the TV. For example:

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver   --ez clock_24 true --es clock_style corner --es focus_style auto --es cc_style auto --es intro boot --ez intro_sound true
adb -s <TV_IP>:5555 shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver   --ez weather true --es weather_unit c --es weather_city Madrid --ez charts true --es charts_country ES --ez app_tint true
```

| Extra | Values |
|---|---|
| `--ez guard`, `--ez keep_adb` | `true` / `false` (step 4) |
| `--es clock_style` | `corner` (default), `large`, `off` |
| `--ez clock_24` | `true` / `false` (default) |
| `--es focus_style` | `auto` (default), `tvos`, `simple` |
| `--es cc_style` | `auto` (default), `glass`, `panel` |
| `--ez app_tint` | `true` (default) / `false`: the background takes on the focused app's colours |
| `--es intro` | `off`, `boot` (default), `wake` |
| `--es intro_source` | `video` (default: the uploaded video, else the built-in one), `wordmark` (the Lumen wordmark) |
| `--ei wake_delay` | milliseconds of black before the animation on wake, 0–15000 (default 5000; OLED panels light up late) |
| `--ez intro_sound` | `true` (default) / `false` |
| `--ez own_icon` | `true` (default): the uploaded settings icon if there is one; `false`: the built-in gear. The file is kept either way |
| `--ez settings_bg` | `true` (default): the uploaded settings-page picture, else the built-in one; `false`: the home wallpaper, blurred. The file is kept either way |
| `--es background` | `daily` (Bing, default), `scenery`, `fresh`, `anime`, `art`, `mine`, `custom`, or a gradient: `green`, `blue`, `purple`, `orange`, `graphite` |
| `--ez weather`, `--ez charts` | `true` (default) / `false`: the weather and Top 10 widgets |
| `--es weather_unit` | `auto` (default: by the TV's region), `c`, `f` |
| `--es weather_city` | a city name, looked up by name; empty for the TV's own location (by IP address) |
| `--es charts_country` | a two-letter country code such as `US` or `ES`; empty for where the TV is |

`background` and `weather_unit` ignore values not listed, and `charts_country` ignores anything but two characters or an empty value. The other text options are stored as given, so use only the values above and check the result line, which echoes every setting. `adb shell` passes the command to the TV's shell, so a value with spaces needs a second pair of quotes, and an empty value is two single quotes: `--es weather_city "'New York'"`, `--es weather_city "''"`, `--es charts_country "''"`.

## 9. Adapting Lumen to a TV that isn't fully supported

Do this only when the user asks for it, or agrees after you report what doesn't work. Typical gaps on TVs that aren't Sony:

- The brightness slider is missing. Android has no standard backlight API.
- The volume slider moves but the sound doesn't change, for example when audio goes to an amplifier over ARC.
- The Control Center has no Input button (it is hidden when Lumen can't read the TV's inputs), or picking an HDMI input does nothing.

All three live in the [`tv/`](app/src/main/java/com/luumlabs/lumen/tv/) package, one adapter per brand:

```
tv/
├── TvControls.kt      # picks the first adapter whose matches() is true
├── AndroidAdapter.kt  # the TvAdapter interface + standard Android, which every brand extends
├── SonyAdapter.kt     # BRAVIA IP-control API on 127.0.0.1
├── XiaomiAdapter.kt
├── TclAdapter.kt
└── CoocaaAdapter.kt   # Skyworth / Coocaa: inputs only, untested on a real TV
```

A new brand is a new `<Brand>Adapter.kt` that extends `AndroidAdapter`, overrides `matches()` (usually `Build.MANUFACTURER` / `Build.BRAND`) and only what the firmware does differently, and is listed in `TvControls.brands`. Don't change other brands' adapters or `AndroidAdapter`'s behavior. What the existing ones do:

- Sony (`SonyAdapter`): backlight, volume and inputs through the BRAVIA API; each falls back to standard Android when the API doesn't answer.
- TCL / FFalcon China firmware (`TclAdapter`): inputs open LiveTV's own source picker, settings open `com.tcl.settings`, and brightness stays hidden.
- Xiaomi China firmware (`XiaomiAdapter`): backlight through `Settings.Global picture_backlight` (needs the `WRITE_SECURE_SETTINGS` grant), inputs through Xiaomi's player (`EXTSRC_PLAY`), settings and Wi-Fi through `com.xiaomi.mitv.settings`.
- Skyworth / Coocaa (`CoocaaAdapter`): inputs open the launcher's source picker (`SourceDialogActivity`) when no standard inputs exist; not yet tested on a real TV, and brightness and settings are still standard Android.
- Every other TV (`AndroidAdapter`): volume on Android's music stream, HDMI inputs from the TV input framework (switched by opening their passthrough channel), no brightness slider.

### 9.1 Set up a build

- Clone https://github.com/luumlabs/lumen-tv. You need JDK 17+ (`java -version`) and an Android SDK with `platforms;android-36` and `build-tools` (install them with `sdkmanager`). Put `sdk.dir=<path>` in `local.properties`. Build with `./gradlew assembleStandardRelease` (or `assembleGuardRelease` for TCL).
- Your build is signed with this computer's debug key, which differs from the Release APK. `adb install -r` will fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Ask the user before you `adb uninstall com.luumlabs.lumen` first: it resets the app's settings and grants, and afterwards you must redo step 4 and the user must allow local debugging again (step 6). Software Updates will not install an official release over a build with a different signature either.

### 9.2 Find what the TV offers

Work from adb. Before you write anything, note each value you read; restore the original values when you are done.

- **Volume**: `adb shell cmd media_session volume --stream 3 --get` shows what the app's slider controls. Change it with `--set <n>` and ask the user whether the sound changed. If it didn't, look in `dumpsys audio` for the active output device and whether HDMI-CEC volume control is in use (`dumpsys hdmi_control`).
- **HDMI inputs**: `adb shell dumpsys tv_input` lists the inputs with their ids and states; the HDMI ones usually have `HDMI` or `HW` in the id. Test a switch the way the app does it:
  `adb shell am start -a android.intent.action.VIEW -d "content://android.media.tv/passthrough/<url-encoded input id>"`
  If nothing happens, run `adb logcat -c`, ask the user to switch input with the remote, then read `adb logcat -d | grep -iE "START u0|tune|input"` to find the vendor's player activity and the intent it uses.
- **Brightness / backlight**: take a snapshot with `adb shell settings list system; settings list global; settings list secure`. Ask the user to change the backlight in the TV's picture settings, then take the snapshot again and diff the two. A key that changed (names like `backlight`, `picture_backlight`) is a candidate. Test it with `settings put`, ask the user whether the screen changed, then put the original value back. Also check logcat during the change, and `adb shell ss -ltn` for vendor control services on localhost, like Sony's port 80.

### 9.3 Implement and verify

- Write the brand's adapter in `tv/` and list it in `TvControls.brands`. `brightness()` returning a non-null `Level(value, min, max)` makes the slider appear; `setBrightness` is then called with values in that range. All of these except the two intents run on a background thread, so blocking I/O is fine. Pinned input tiles call `setInput` with uris saved earlier, so keep a brand's uri format stable.
- Writing settings: secure and global settings can be written directly once `WRITE_SECURE_SETTINGS` is granted (step 4; check with `Guard.hasSecureSettings`). For the system table, grant `WRITE_SETTINGS` with `adb shell appops set com.luumlabs.lumen WRITE_SETTINGS allow`, or run the command through the app's own local adb, `LocalAdb.shell(ctx, "settings put …")` in `Cleaner.kt`, which works once the user has allowed local debugging.
- Build, install, open the Control Center (Back on the home screen), and `screencap`. Move to the slider with D-pad keys, checking the focused element first (step 6). Then read the value back the same way as in 9.2 to confirm the change.

### 9.4 Finish

Tell the user what now works and what still doesn't. Their adapted build stays on their TV. If they want to share it, offer to commit the change on their own fork and open a pull request to `luumlabs/lumen-tv` with their GitHub account, naming the TV model and Android version. Never push to the upstream repository directly.

## 10. TVs whose system fights third-party launchers: the home guard

Many Chinese TV systems (Xiaomi PatchWall, TCL, Hisense VIDAA-on-Android, Skyworth and others) aren't stock Android TV. What is known (from Tigris's testing and from [Aerial](https://github.com/pengchujin/aerial-tv)'s device notes):

- **Xiaomi** accepts `set-home-activity` and the HOME role, and both report success, yet Home still opens PatchWall (`com.mitv.tvhome`): only a system whitelist decides. On standby Xiaomi force-stops every third-party app, which also switches off their accessibility services. It also turns ADB off on each wake.
- **TCL** refuses adb installs and home-screen APKs (step 3), blocks boot autostart for third-party apps, and may write its own screensaver back at boot.

Lumen handles this without root, and without disabling or uninstalling anything:

1. **Home guard**, an accessibility service ("Home Screen Guardian"). When the stock launcher's home screen comes to the front, Lumen is brought back on top (the user sees the stock launcher for about half a second). While the user deliberately opens the stock launcher from its tile, the Home key returns to Lumen. It watches window changes only, never screen content.
2. **Revival**: the notification listener from step 4. Android rebinds it about half a second after the system kills Lumen, so Lumen restarts and switches the guard back on (with `WRITE_SECURE_SETTINGS`). It also starts Lumen at boot.
3. **Keep ADB on** (optional): when Xiaomi switches ADB debugging off, Lumen switches it straight back on.
4. With the guard on, Lumen also puts its screensaver back when the firmware overwrites it.

Turn it on with the step 4 grants and the `CONFIG` broadcast, or on the TV in Settings → System → Home screen guardian (which grants itself through local adb once step 6 is done). Then check:

```sh
adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services   # contains com.luumlabs.lumen/com.luumlabs.lumen.HomeGuardService
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME; sleep 2
adb -s <TV_IP>:5555 shell dumpsys activity activities | grep -m1 ResumedActivity  # com.luumlabs.lumen/.HomeActivity
adb -s <TV_IP>:5555 logcat -d -s LumenGuard                                      # what the guard did and why
```

Then ask the user to put the TV in standby and wake it, and check again that Lumen is in front and the guard is still listed. If the user agrees to a reboot, check after boot too. The guard waits for a confirmed stock HOME window, then a short settling interval. Boot or wake alone never authorizes takeover. If no window event arrives, open Lumen or press Home; do not claim automatic boot takeover was verified. See [docs/guard-stability.md](docs/guard-stability.md).

If the guard is listed but `dumpsys accessibility` shows no bound service for it, Lumen re-registers it by itself within a few seconds of opening; if it stays unbound, toggle it off and on in Settings → System → Home screen guardian (the row reads "On (not running)" meanwhile).

If nothing works, tell the user plainly: that TV's system doesn't allow a replacement launcher, and Lumen can still be opened as an app.

## 11. Optional: remove preinstalled apps

Only on request. Lumen's long-press menu (hold OK on a tile) can remove preinstalled apps that appear on the home screen; Settings → Apps → **Removed preinstalled apps** restores them. For background-only ones (ad, push, tracking services with no icon), list them with `pm list packages -s`, identify each by its label and path, and propose a list to the user. Explain each entry and ask before removing anything.

- Remove with `adb shell pm uninstall -k --user 0 <pkg>`. Restore with `adb shell cmd package install-existing <pkg>`. Give the user both commands.
- Never remove settings, launchers, input-method, TV player/tuner/`tvinput`, system UI, update, Google Play services, or anything you can't identify. On Chinese TVs, also keep licensing services ("播控" such as `com.newtv.*`, CIBN, ktcp-related). Licensed video apps stop playing without them.
- After removing, reboot only if the user agrees, then check that the TV still starts, HDMI inputs work and video apps play.

## Restore

Keep Lumen installed unless explicitly asked to uninstall. For installs made with `install.sh`, run its restore on the original computer, with the original snapshot directory (step 3):

```sh
curl -fsSL https://raw.githubusercontent.com/luumlabs/lumen-tv/main/install.sh | TV=<TV_IP> bash -s -- restore
# or, from a checkout: TV=<TV_IP> bash install.sh restore      (add --uninstall only if asked)
```

It turns the guard and keep-ADB off, puts back the saved screensaver only if the current one is Lumen's, sets the saved home activity, removes Lumen's notification listener only if it wasn't enabled before the install, force-stops Lumen and prints the verification output. Do not guess a vendor launcher if the snapshot is missing or already points at Lumen; the script stops in that case.

For manual installs, use the original HOME component recorded in step 2. If unavailable, enumerate HOME activities with `cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME`, identify the installed vendor launcher, and ask if ambiguous. Then:

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver --ez guard false --ez keep_adb false
adb -s <TV_IP>:5555 shell cmd package set-home-activity <ORIGINAL_HOME_COMPONENT>
adb -s <TV_IP>:5555 shell am force-stop com.luumlabs.lumen
```

Restore the recorded screensaver (`settings put secure screensaver_components <original value>`) only if the current screensaver points at Lumen. Preserve other accessibility services; never overwrite the current full list with an old snapshot (turning the guard off removes only Lumen's entry). Remove Lumen's notification listener (`cmd notification disallow_listener com.luumlabs.lumen/com.luumlabs.lumen.RevivalListener`) only if it was added by this installation. Press HOME, wait two seconds and verify the original launcher is foreground. Paste the raw accessibility and current-focus outputs described below.

## Verification

Run all commands with `adb -s <TV_IP>:5555 shell`. Paste their raw output to the user:

1. `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
2. `settings get secure enabled_accessibility_services`
3. Send `input keyevent KEYCODE_HOME`, wait two seconds, then `dumpsys window | grep mCurrentFocus`.

After installation, actual foreground should contain `com.luumlabs.lumen`; guard installations may retain the vendor HOME resolver. Report installation, Home-key behavior, standby/wake and boot separately. Never claim an untested check passed. Do not reboot/sleep/power off without consent.

## Uninstall

First turn the guard off, so the accessibility list goes back to what it was (the value noted in step 2), and put the original screensaver back if the photo frame was made the screensaver:

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver --ez guard false --ez keep_adb false
adb -s <TV_IP>:5555 shell settings put secure screensaver_components <original value>
adb -s <TV_IP>:5555 uninstall com.luumlabs.lumen
```

The TV falls back to its stock launcher. If you disabled a launcher in step 5, re-enable it with `pm enable <package>`.
