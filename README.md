# Tailscaled Reborn

Tailscale on a rooted Android phone: the daemon built from upstream, kept up to
date on its own, with a companion app for sending files and a desktop
integration for the other direction.

[![module](https://img.shields.io/badge/module-v1.0.30-4ea1ff)](module.prop)
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
- **A clipboard history.** Everything you copy is recorded with the app it
  came from, and can be sent to your other machines in one tap.
- **No Google Play Services, no account of its own.** Your existing Tailscale
  login is the login.

---

## Install

1. Download **`Tailscaled-Reborn-v1.0.30-full.zip`** from
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

## Clipboard

Everything you copy is recorded, so it is there when you want it, and it can
be sent to your other machines without leaving the phone.

Open **TailBridge → Clip**. The history is newest first, and each entry says
which app it came from. Tap one to read it in full, or send it to a device.

**Send the clipboard to every online device** is one tap, and the clipboard
arrives there as a file it can paste from.

### How it works, and why it needs root

Android will not let an ordinary app read the clipboard while it is in the
background, and since Android 10 it will not let it write at all unless the
app is the focused one. What it does allow is the shell.

So the module runs a small helper as the shell user. It turns itself into a
system process, takes the system context from it, and reaches the clipboard
service through the binder proxy that `ClipboardManager` keeps in a private
field — the same approach ClipShare uses. The helper is about 10 KB, it is
built from source with `javac` and `d8`, and it is not a separate dependency.

**Two things that are easy to get wrong, and were:**

- The helper must run as the shell user, not as root. The clipboard service
  checks that the calling process owns the package it claims to be; root does
  not own `com.android.shell`, so the service throws while trying to grant a
  permission and **clears the user's clipboard**. Every call, silently.
- The copy of the helper that the shell user loads has to be read-only for
  it. ART refuses to load a dex file the caller could modify, and it does so
  by aborting with no message at all.

The history lives in the module, not in the app. It is a plain text file,
not a database — see below for where it is and what that means.

### Where the history is kept, and what that means

It is a file of JSON lines, not a database:

```
/data/adb/tailscale/clipboard.jsonl
```

One JSON object per line. **The text in it is encrypted.** A clipboard
history is a list of things people copied, and some of those things are
passwords, one-time codes and private messages, so the text is sealed with
a key that lives in the device's **hardware keystore** — the same secure
element that holds the fingerprint and the payment keys. The key is made on
first use, it never leaves the hardware, and it cannot be exported: not by
root, not by anyone with a copy of the storage.

```
/data/adb/tailscale/clipboard.jsonl
{"t":1757803210123,"c":"qXs9...","h":"1mYd...","s":"com.android.chrome","d":0,"p":0}
```

`t` when it was copied, `c` the encrypted text, `h` a hash of the text
keyed with the same hardware key, `s` the app it came from, `d` whether it
arrived from a peer, `p` whether it is starred.

| | |
|---|---|
| **Permissions** | `0600`, owned by root |
| **The directory** | `/data/adb` is mode `0700` root, so no app can reach it — not even a system app |
| **Against other apps** | Safe. Only root can open it, and what is inside is ciphertext anyway |
| **Against root** | Root can ask the hardware to use the key, so root can read it. On a rooted phone, root can read everything anyway |
| **Against a copy of `/data`** | **Ciphertext.** The key is in the hardware, and a copy of the storage does not carry it |
| **Reboots** | It survives. The key lives in the keystore, and the keystore is part of `/data` |
| **Flashing a new module** | It survives. An earlier version moved it into a backup folder, which is why a flashed phone came back with an empty history |
| **A factory reset** | **It does not survive.** The reset wipes the keystore, and the key with it, and a history that cannot be decrypted is a history that cannot be read |

It is not in the app's own storage on purpose: the watcher that records it
runs as root, and the app reads it through the module. If it lived in the
app's directory, the app could not read it without root either.

The `h` field is there so the watcher can tell that the same thing has been
copied twice — the clipboard service reports every change twice — without
decrypting anything. It is keyed with the hardware key rather than being a
plain hash, so the hash of a password cannot be attacked the way a plain
hash of one can.

**How the keystore is reached** is a little involved, and worth writing
down. The platform keeps the keystore in two providers — one that holds the
keys and makes them, one that does the cryptography — and the zygote
registers both for every app it starts. The module's helper is not started
by the zygote, so it registers them itself. Reaching the hardware from a
program the zygote did not fork was not obvious, and it took a while to
work out.

A Java program on this phone takes about two seconds to start, and the
keystore takes a moment to answer, so running the helper once per copy
would put every copy behind a wait of several seconds. So there is a small
program that stays running with the keystore open and answers over a
socket on the loopback address. Every request has to carry a secret from a
file that only root can read, so nothing else on the device can ask it
anything. The supervisor starts it at boot and starts it again if it dies.

**A factory reset wipes the keystore's keys**, and with them the history: a
phone that has been wiped cannot read a history that was encrypted on it
before. A module flash does not — that leaves `/data` alone, and the key
lives there.

**What to do about a clipboard history that holds secrets.** Put the app
that copies the secret on the ignore list, and its copies are never written
down at all:

```sh
# /data/adb/tailscale/clipboard.conf
ignore=com.example.passwordmanager
```

**If the keystore cannot be reached** — a device whose secure hardware does
not answer, or one where the keystore refuses a key for a process with no
package behind it — the history is written as plain text instead, and the
module says so in its log:

```sh
# /data/adb/tailscale/run/runs.log
1761000000 [Warning]: The clipboard history cannot be encrypted on this device; it is being stored as plain text.
```

That is a deliberate choice over failing: a clipboard history in plain text
is not as good as an encrypted one, but it is a history, and the warning is
there to be noticed.

### What it does not do

- **Text only.** A clipboard item can also be an image or a file; those are
  not handled yet.
- **The history is local.** Entries are recorded on the phone that saw them.
  Sending is explicit: nothing is sent until you tap Send.
- **A clipboard that arrives from a peer is put on the clipboard**, and is
  also recorded in the history marked as coming from a peer.

### On the desktop

The desktop receiver does not know about clipboard files yet. One sent from
the phone arrives as a file in `~/Downloads/Tailscale`; pasting it from
there is a copy and paste away.

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

## Known issues

The things that are known to be wrong, and what is being done about them,
are written down in [ISSUES.md](ISSUES.md). It is kept as a list rather
than hidden, because a project that only says what works is not telling the
truth.

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