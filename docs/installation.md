# Installing, upgrading and restoring Lumen TV

The short version is in the README's [Install](../README.md#install) section. This page covers every route, upgrades, restoring the original home screen, and common problems. Coding agents should follow [AGENTS.md](../AGENTS.md), which has the full, step-by-step procedure.

## 1. Before you start

Lumen needs Android 9 (API 28) or newer. Put the computer and the TV on the same network, turn on network ADB debugging on the TV (Android Debug Bridge, which lets the computer connect to the TV and install apps), and note the TV's IP address. The first time the computer connects, choose **Allow** on the TV and tick **Always allow from this computer**. How to turn on ADB for each brand is in [AGENTS.md, step 0](../AGENTS.md#0-what-you-need).

Releases are published at https://github.com/luumlabs/lumen-tv/releases. Each one has:

- `Lumen-vX.Y.Z.apk`, the standard build, for most TVs;
- `Lumen-guard-vX.Y.Z.apk`, the guard build, for TCL and FFalcon China firmware, which refuse any APK that declares itself a home screen;
- `SHA256SUMS-vX.Y.Z.txt`, to verify both.

## 2. Install

### Ask an agent (recommended)

Send this to an agent that can run commands on your computer and has permission to, such as Claude Code or Codex. Fill in your TV's IP address:

```text
Please install the open-source TV home screen Lumen TV on my TV, and make it the default home screen or set up the home screen guardian.
TV IP: <TV_IP>
Keep ADB debugging on (Xiaomi only): yes

Installation instructions: https://github.com/luumlabs/lumen-tv/blob/main/AGENTS.md
If that page doesn't open: https://raw.githubusercontent.com/luumlabs/lumen-tv/main/AGENTS.md

Read the instructions in full first, then run the commands on my computer yourself; don't just tell me the steps.
Tell me when I need to do something on the TV. Don't reboot, uninstall an older version, clear settings or disable the stock launcher without asking me.
If the signature doesn't match, look for the old signing key first; don't uninstall automatically.
At the end, paste the raw output of these commands, as in the "Verification" section, with -s <TV_IP>:5555 on every adb command:
- adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
- adb shell settings get secure enabled_accessibility_services
- after pressing Home and waiting two seconds: adb shell "dumpsys window | grep mCurrentFocus"
Report installation, the Home key, standby/wake and boot separately; don't say an untested check passed.
```

What matters is that the agent **can run commands on your computer**. A chat-only phone app or web page can't install anything for you. Judge the result by the `mCurrentFocus` line in the verification output: it should contain **`com.luumlabs.lumen`**. An agent saying "done" isn't enough. With the home guard, the default HOME may still be the stock launcher; what counts is what is in front after pressing Home.

### One command (macOS / Linux)

Install Android Platform-Tools (`adb`), `curl` and Python 3 first, and use your TV's IP address:

```bash
curl -fsSL https://raw.githubusercontent.com/luumlabs/lumen-tv/main/install.sh | TV=192.168.1.20 bash
```

The script:

1. saves the original home screen, screensaver, accessibility services and notification listeners on this computer, so that it can restore them later;
2. downloads the latest **stable** release from `luumlabs/lumen-tv` and checks its SHA-256 (it never installs pre-releases);
3. installs it with `adb install -r`, makes the grants the home guard needs, and sets Lumen as the home screen;
4. turns on the home guard on Xiaomi, TCL and FFalcon, or wherever Lumen didn't become the default home;
5. opens Lumen and prints the verification output.

It never uninstalls or disables an app, and never reboots or powers off the TV.

Options:

- `KEEP_ADB=1` turns on **Keep ADB debugging on**, for firmwares that switch ADB off after standby (Xiaomi). `KEEP_ADB=0` turns it off. Without it, an existing choice is kept (off on a new install).
- `VERSION=v3.4.0` installs that exact stable release.
- `APK=<file>` installs a local APK instead of downloading one.
- For the guard build, end the command with `bash -s -- -s guard`.

```bash
curl -fsSL https://raw.githubusercontent.com/luumlabs/lumen-tv/main/install.sh | KEEP_ADB=1 TV=192.168.1.20 bash
```

If TCL refuses the adb install (`INSTALL_FAILED_VERIFICATION_FAILURE`), the script stops without changing anything. Push the guard build with TCL's own T-Cast (多屏互动) as described in [AGENTS.md, step 3](../AGENTS.md#3-install), then make the grants and turn on the guard as in [step 4](../AGENTS.md#4-make-it-the-home-screen). Running the script again would stop at the same refused install.

### Manually

Download the standard APK from the [latest release](https://github.com/luumlabs/lumen-tv/releases/latest) (for example `Lumen-v3.4.0.apk`), or let the helper below download and verify it. Record the original configuration first, and if a step fails, deal with it before you go on:

```bash
adb connect 192.168.1.20:5555
# Keep this output: you need it to restore the original home screen.
adb -s 192.168.1.20:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
curl -fsSL -o download-release.py https://raw.githubusercontent.com/luumlabs/lumen-tv/main/tools/download-release.py
APK=$(python3 download-release.py standard)
adb -s 192.168.1.20:5555 install -r "$APK"
adb -s 192.168.1.20:5555 shell pm grant com.luumlabs.lumen android.permission.WRITE_SECURE_SETTINGS
adb -s 192.168.1.20:5555 shell cmd notification allow_listener com.luumlabs.lumen/com.luumlabs.lumen.RevivalListener
adb -s 192.168.1.20:5555 shell appops set com.luumlabs.lumen SYSTEM_ALERT_WINDOW allow
adb -s 192.168.1.20:5555 shell cmd package set-home-activity com.luumlabs.lumen/.HomeActivity
adb -s 192.168.1.20:5555 shell am start -n com.luumlabs.lumen/.HomeActivity
```

On Windows, use an agent or PowerShell: download the helper with `curl.exe`, then run `$apk = python .\download-release.py standard` and `adb -s 192.168.1.20:5555 install -r $apk`; Bash isn't needed. If Xiaomi, TCL or another firmware still takes Home back to its own launcher, set up the home guard as described in [AGENTS.md, section 10](../AGENTS.md#10-tvs-whose-system-fights-third-party-launchers-the-home-guard):

```bash
adb -s 192.168.1.20:5555 shell am broadcast -a com.luumlabs.lumen.CONFIG -n com.luumlabs.lumen/.ConfigReceiver --ez guard true
```

### Without a computer

ADB tools for phones haven't been tested with Lumen. Installing the APK from a USB stick doesn't make it the default home screen or set up the guard, and doesn't start it at boot, so use a computer if you can.

## Upgrading

From version to version, use **Settings → Software Updates** on the TV. It checks the latest stable release of `luumlabs/lumen-tv`, picks the standard or guard build to match what is installed, and checks the package and its signature before installing. The TV may ask you to allow Lumen to install unknown apps.

Running the install command again also upgrades: `adb install -r` keeps your settings when the signature matches. Official releases are always signed with the same key. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed copy was signed with another key (a build of your own, for example). It can only be upgraded by an APK signed with that same key; don't uninstall it automatically, because uninstalling erases its settings and grants.

**Coming from Tigris?** Lumen has a different package name (`com.luumlabs.lumen`; Tigris is `com.leslie.tvhome`), so it installs alongside Tigris rather than upgrading it, and doesn't take over Tigris's settings. Turn Tigris's home guard off first, or restore and uninstall Tigris with its own instructions, so the two don't both try to own the Home key.

## Restoring

Use the computer, TV address and `STATE_DIR` (if you set one) from the original install:

```bash
curl -fsSL https://raw.githubusercontent.com/luumlabs/lumen-tv/main/install.sh | TV=192.168.1.20 bash -s -- restore
```

The script turns off Lumen's home guard and Keep ADB debugging on, and restores the recorded home screen. It restores the original screensaver only if the current one is Lumen's, and removes Lumen's notification listener only if it wasn't enabled before the install. Lumen and its settings are kept unless you add `--uninstall` (`bash -s -- restore --uninstall`).

The record of the original home screen is kept on the computer in `~/.local/state/tigris/<TV_IP>:5555/`, or under `$XDG_STATE_HOME` if you set it. (The folder is named `tigris` for compatibility with the installer Lumen inherited.) Upgrades never overwrite it. If the record is missing or already points at Lumen, the script stops. Then find the original home screen by hand as described in [AGENTS.md, Restore](../AGENTS.md#restore); don't guess the package name.

To have an agent restore it, send:

```text
Please read the Restore section of https://github.com/luumlabs/lumen-tv/blob/main/AGENTS.md in full,
and restore my TV (IP: <TV_IP>) to its original home screen. Don't uninstall Lumen yet.
Run the commands on my computer yourself, keep the other accessibility services, and don't guess the original launcher's package name.
At the end, paste the raw output of enabled_accessibility_services and of mCurrentFocus after pressing Home.
```

## Common problems

- **I installed the APK, but Home still opens the stock launcher.** Lumen also has to be the default HOME, or the home guard has to be set up (step 4 and section 10 of AGENTS.md).
- **TCL / FFalcon refuses the install.** Use the guard build and T-Cast (多屏互动); when the TV asks, choose **同意** (Agree).
- **What does `KEEP_ADB=1` do?** It turns on "Keep ADB debugging on", for firmwares that switch ADB off after standby. Without it, the existing choice is kept (off on a new install); restoring turns it off.
- **The TV is too old.** Lumen needs Android 9 or newer; `INSTALL_FAILED_OLDER_SDK` can't be solved by trying another installer. LG webOS, Samsung Tizen and other systems that aren't Android can't install it at all.
- **Do I keep the stock launcher, my accounts and subscriptions?** Yes. The installer doesn't uninstall or disable the stock launcher, and doesn't clear other apps' data. Ads that the firmware shows at boot aren't part of the launcher, and Lumen can't promise to remove them.
- **It installed, but the TV doesn't start into Lumen after a reboot.** The Home key, standby/wake and boot have to be checked separately, and rebooting needs your consent. If your TV doesn't, please [report it](https://github.com/luumlabs/lumen-tv/issues/new/choose) with its model and Android version.

## How it takes over the home screen

On stock Android TV, Lumen simply becomes the default home screen. Some Chinese firmwares keep the Home key for their own launcher. There, an accessibility service (the home guard) watches which window is in front and switches back from the stock launcher to Lumen, and a notification listener brings Lumen back after the system kills it. The guard doesn't read screen content, and the listener doesn't read notifications. The installer never disables the stock launcher, and never reboots or powers off the TV on its own.
