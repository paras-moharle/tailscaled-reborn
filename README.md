# Tailscaled Reborn

Tailscale on a rooted Android phone: the daemon built from upstream, kept up to
date on its own, with a companion app for sending files and a desktop
integration for the other direction.

[![module](https://img.shields.io/badge/module-v1.0.27-4ea1ff)](module.prop)
[![tailscale](https://img.shields.io/badge/tailscale-1.104.1-3ddc84)](https://tailscale.com)
[![license](https://img.shields.io/badge/license-BSD--3--Clause-a78bfa)](LICENSE)

---

## What you get

- **A daemon that keeps itself current.** Built from `tailscale/tailscale` with
  the Android port applied, and updated on the device — with a health check and
  an automatic rollback if the new binary does not come up.
- **TailBridge**, a small app with no internet permission of its own. It talks
  to the daemon over `su`, shows which devices are on your tailnet, sends
  files, and tells you when something arrives.
- **Taildrop**, so files move both ways: from the phone through the app or any
  app's share sheet, and from a desktop through a Nautilus menu entry or the
  supplied receiver.
- **No Google Play Services, no account of its own.** Your existing Tailscale
  login is the login.

---

## Install

1. Download **`Tailscaled-Reborn-v1.0.27-full.zip`** from
   [the releases page](https://github.com/paras-moharle/tailscaled-reborn/releases).
2. **Magisk / KernelSU → Modules → Install from storage.**
3. Reboot.
4. Open **TailBridge** → **System → Sign in**.

Use the `-full` zip. The slim one downloads the daemon during install, which
fails on devices with no working HTTPS client.

### Requirements

| | |
|---|---|
| Root | Magisk or KernelSU (KernelSU ≥ 0.0670) |
| Architecture | **arm64 only** |
| Android | 8.0 or newer |

32-bit ARM is not supported: `jq`, which the module's scripts need, has no
working 32-bit Android build. The installer says so and stops rather than
failing later.

### Upgrading

Flash the new zip over the old one. **Do not uninstall** — that deletes your
login and you will have to sign in again.

---

## Sending files

**From your phone.** Open TailBridge, tick a device, pick files. Or share from
any app: sharing a file and choosing TailBridge lands on the same Send screen
with the picker ready.

**From a desktop, GNOME Files.** Right-click → *Send via Taildrop* → a device.
The send runs in a detached process, so it survives closing the file manager
and does not queue behind it.

> Nautilus loads extensions once at startup. After editing the extension, run
> `nautilus -q` and reopen it, or the old code keeps running.

**Folders** are sent as a single zip named after the folder, arriving as
`MyFolder.zip`. Tap **Extract to…** in your file manager to get the folder
back, contents and empty directories intact. Zip rather than tar.gz because
Android handles zip far more widely: the platform's Files app extracts a zip
without help, while a tar.gz needs a third-party archive manager.

Taildrop cannot send a directory as one — it moves file streams, and
`tailscale file cp` takes a file path. Packing is the only way round that, and
which format to pack in is a decision about what the receiver can open.

---

## Receiving files

Files land in **`Download/Tailscale`** and raise a notification with *Open*
and *Open folder*. Nothing to tap on the phone: the module watches for
arrivals.

### On a desktop

Something has to fetch them. Unlike Android, the Linux daemon holds a delivery
until `tailscale file get` collects it, and `tailscale file cp` reports success
the entire time without delivering anything. The receiver does that, then
moves each file into `Downloads/Tailscale`.

```sh
curl -Lo ~/.local/bin/taildrop-receiver \
  https://raw.githubusercontent.com/paras-moharle/tailscaled-reborn/main/desktop/taildrop-receiver
chmod +x ~/.local/bin/taildrop-receiver

curl -Lo ~/.config/systemd/user/taildrop-receiver.service \
  https://raw.githubusercontent.com/paras-moharle/tailscaled-reborn/main/desktop/taildrop-receiver.service
mkdir -p ~/.config/systemd/user
mv ~/.config/systemd/user/taildrop-receiver.service \
   ~/.config/systemd/user/taildrop-receiver.service 2>/dev/null || true

systemctl --user daemon-reload
systemctl --user enable --now taildrop-receiver.service
```

It fetches into a staging directory of its own and moves results out, so it
never touches your other downloads. Names are taken atomically, so two
arrivals that want the same name cannot collide.

**Omarchy already ships a receiver.** Two collectors watching the same place
race for the same file. Before enabling this one, stop whatever is already
collecting:

```sh
# Disable any tailscale file-receiver service that is currently active.
# Omarchy's is `omarchy-tailscale-receive.service`; the loop below catches
# anything with that pattern and turns it off too, so a future distro
# variant does not silently steal arrivals from this receiver.
for svc in $(systemctl --user list-unit-files \
             --value --type=service --state=enabled \
             2>/dev/null | grep 'tailscale.*receive' || true); do
  systemctl --user disable --now "$svc" 2>/dev/null && \
    echo "disabled $svc"
done

systemctl --user enable --now taildrop-receiver.service
```

To see what is running afterwards: `pgrep -af 'taildrop|omarchy-tailscale'`.

**On macOS and Windows** the official Tailscale app already does this.

---

## Nautilus extension

```sh
curl -Lo ~/.local/share/nautilus-python/extensions/taildrop.py \
  https://raw.githubusercontent.com/paras-moharle/tailscaled-reborn/main/desktop/nautilus-taildrop.py
mkdir -p ~/.local/share/nautilus-python/extensions
nautilus -q
```

The menu entry is always there, even with no devices reachable — an empty
context menu is indistinguishable from the extension not being installed.
Offline devices are listed and sendable, because Taildrop reaches a sleeping
device through a relay.

It writes **`~/.cache/taildrop-nautilus.log`** on every menu open and every
send: the selection, the devices found, and the result. That is how a send
that fails gets diagnosed instead of guessed at.

---

## Build

```sh
./builder/build-binaries.sh v1.104.1   # the daemon, from an upstream tag
./share-app/build.sh                   # the app
./builder/build-module.sh --full       # the flashable zips
```

Go 1.27.1 or newer. No Android NDK: the daemon is cross-compiled for arm64
with `CGO_ENABLED=0`. Building the app needs the Android SDK (`build-tools` and
an `android-34` platform) and a JDK; it uses `javac`, `aapt2` and `d8` directly,
with no Gradle and no dependencies.

The packaging step rebuilds the app every time and fails if the APK's version
does not match `module.prop`, so a zip can never ship an app that reports a
different version from the module.

---

## Troubleshooting

**The daemon will not start.** From a terminal on the phone (or `adb shell` with
root), run `tailscaled.service status` — it reports backend, login state and
installed version. The same script takes `log daemon` to follow the log, and
`help` lists the rest.

```sh
/data/adb/tailscale/scripts/tailscaled.service status
```

**A send says it worked and nothing arrives.** `tailscale file cp` reports
success on some failures. On the phone, check `/data/adb/tailscale/run/runs.log`;
on a desktop, `journalctl --user -u taildrop-receiver`.

**The context menu entry is missing.** `nautilus -q`, then reopen Nautilus.
If it is present but lists nothing, the extension is fine and no device is
reachable — `tailscale status` on the desktop will say which.

**The app says root is missing.** TailBridge needs the same root manager as
the module, and has to be granted it once.

---

## Credits

- **[tailscale/tailscale](https://github.com/tailscale/tailscale)** — the
  daemon, BSD 3-Clause.
- **[anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled)** —
  the module this was forked from.
- **Magisk / libsu** (topjohnwu), **KernelSU** (tiann),
  **[jq](https://github.com/jqlang/jq)**.

Not affiliated with Tailscale Inc.

This project is BSD 3-Clause licensed — see [LICENSE](LICENSE).
Binaries carried in the zip are covered by [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
Full release history: [CHANGELOG.md](CHANGELOG.md).