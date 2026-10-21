# Open issues

Everything here was found by measuring on the device, not by reading the
code and guessing. Each entry says how it was found, because the number that
matters is the one that was measured.

Ordered by what hurts the user most.

---

## Fixed in v1.0.29

### The clipboard was 2.5 s late, and up to 10 s late in the app

**Was:** the helper noticed a change after 2522 ms, and the app showed it up
to ten seconds later.

**Why**

- The module's `am broadcast` ran *synchronously* in the watcher's read loop,
  so the next copy queued behind it.
- The app only refreshed on its 5 s poll, because the module's broadcast
  never reached it: the app's receiver was registered
  `RECEIVER_NOT_EXPORTED`, and only the system and the app itself can reach
  such a receiver.
- The helper read the clipboard *on the binder callback thread*. The
  clipboard service holds its lock while it dispatches the notification, so
  the read waited for the service to finish telling everybody.

**Now**

- The broadcast runs in the background, so it can never block a copy.
- The app's receiver hears the module, and the app redraws when it does.
- The helper reads on its own thread.
- The app's poll is a 15 s backstop rather than the mechanism.

**Measured now:** the copy reaches the screen in about 400 ms.

The 2.5 s that started this turned out to be mostly the *test's* own doing —
the helper that made the copy took 2029 ms just to start a Java VM and a
system context. The real notification is about 170 ms.

### The daemon never updated itself

**Was:** `update.state` sat at `failed-update` for ever.

The updater ran once at boot, before the network was up, so the feed fetch
failed. Worse, `cmd_run` wrote the 24 hour stamp *before* it tried
anything, so a failed run consumed the window and the next attempt was a day
away. The next boot failed the same way. The daemon stayed on 1.104.1 for
ever.

**Now**

- The run waits for the feed instead of giving up on it.
- The clock starts only when the feed answered, so a failure is retried
  rather than waited out.
- The supervisor checks again every six hours, so a phone that boots with no
  network still updates later.

### The clipboard helper did not build

`d8: Unable to find method metafactory`, from a lambda compiled against
android.jar's `LambdaMetafactory` — which is a stub with no `metafactory`
method. It now compiles the way the app does: `--release 11 -classpath`.

### Smaller ones fixed along the way

- The app's search box was restored by Android but the filter was not, so
  the box showed what was typed while the list was not filtered by it.
- The app read the clipboard itself as well as reading the module's store,
  which showed every copy twice. One writer, one store now.
- The receiver could run twice and race for the same deliveries.

---

## Fixed

### The clipboard history did not survive a module flash — v1.0.30

Every install moved everything out of `/data/adb/tailscale/` into a backup
folder, and the clipboard's history and settings went with it. A phone that
was flashed came back with an empty history and a reset limit. The
installer keeps them now, and what had already been moved aside was put
back.

### "Is the daemon running" could be fooled — v1.0.30

It asked

```sh
busybox pgrep -f 'tailscaled -no-logs'
```

which matches **any** process whose command line contains that string. A
diagnostic shell whose own command line mentioned the daemon made the API
report `daemon: running` while the daemon was stopped, and the supervisor
uses the same test, so it could fail to restart a daemon that had really
died — the one job it has.

It is `pgrep -x tailscaled` now, which matches the process name exactly.

### The app fetched the whole clipboard history, every time — v1.0.30

`clipboard list` returned every entry, pretty-printed. At the 5000 entry
limit that is about **400 KB**, over a root round trip, on every refresh.

The list is limited and compact now, and the app asks for the two hundred
it is about to show.

### The clipboard history was a plain text file — v1.0.31

**Was:** the history sat in `/data/adb/tailscale/clipboard.jsonl` as plain
text, base64 encoded. Base64 is encoding, not encryption. Anyone with a
copy of the phone's storage could read every password, one-time code and
private message the user had copied, ever.

**Now**

- The text is encrypted with AES-256-GCM under a key in the device's
  **hardware keystore**. The key is made on first use, never leaves the
  secure hardware, and cannot be exported: not by root, and not by anyone
  holding a copy of the storage.
- Each entry carries a hash of the text, keyed with a second hardware key,
  so the watcher can tell that the same thing has been copied twice without
  decrypting anything — and so the hash of a password cannot be attacked
  the way a plain hash of one can.
- An entry that arrives from a peer is encrypted too. It was the one thing
  in the history that was in the clear.
- A store written before there was any encryption is sealed when the
  watcher starts.

**Getting at the keystore took some finding.** The platform keeps it in two
providers and the zygote registers both for every app it starts; a program
started with `app_process` is not started by the zygote, so it has to
register them itself. The first working recipe was found by trying the
provider's constructors on the device, one at a time.

**The cost of finding out** was that encrypting on every copy would have
taken about four and a half seconds: a Java program takes two seconds to
start on this phone, and the keystore takes a moment to answer. So the
keystore is now held open by a small program that answers over a socket on
the loopback address. A copy costs about **80 ms**. Every request carries a
secret from a root-only file, so nothing else on the device can ask it
anything.

**Measured on the device:** the store on disk holds ciphertext, the app
shows the history as before, the history survives a reboot with its key,
and a repeated copy is still collapsed into one entry.

**One thing that is still true:** a factory reset wipes the keystore, and
with it the key, and a history that cannot be decrypted is a history that
cannot be read. A module flash does not wipe it.

### The list and the startup both grew with the history — v1.0.31

**Was, in the first version of the encryption:** every `clipboard list`
opened every entry in the store, and the watcher opened every entry again
when it started to seal the ones that were still in the clear.

Opening an entry costs the hardware a few milliseconds. At the 5000 entry
limit that is over a minute and a half for a list, and the same again at
every start of the watcher.

**Now**

- The list opens only the entries it is returning.
- What has been opened is kept, so the second list of the same entries is
  immediate. The text of an entry does not change once it is written.
- Sealing a store does not open every entry to see whether it is sealed.
  The hash says: an entry with a hash made by the hardware is sealed, an
  entry with no hash, or one marked `plain:`, is not.
- The desk is started at boot, with the watcher, rather than being left to
  the supervisor's next cycle. Otherwise the first copies after a boot
  would each pay for a Java program starting up.

**Measured now:** a list of 200 entries takes about 3.5 s the first time and
is instant after that.

**One thing this leaves, on purpose:** sealing a store that was written in
the clear costs the hardware about seventy milliseconds an entry, and the
watcher is not listening while it happens — a copy in that window is
missed, not corrupted. For a store of a few hundred entries, which is what
a phone that has had the clipboard history for a few days has, that is a
second or two, once. For a store of ten thousand entries it is minutes,
and the right answer is to seal it a piece at a time while the watcher
listens, which has not been done yet.

---

### A file sent to the phone arrived twice — v1.0.31

**Was:** sending one file to the phone put two copies of it in
`Downloads/Tailscale`, the second named `something-1.ext`.

**Why.** The receiver watches two places, because the daemon changed where
it delivers:

- `INBOX` — `tailscale file get` copied deliveries here on older builds.
- `DROP_DIR` — the daemon delivers here directly on current ones.

On a current daemon the same delivery is in **both**: the daemon writes it
to the delivery folder, and the pull copies it into the inbox as well. The
receiver swept both directories in the same cycle, so every file was
exported twice, and the second export found the name taken and wrote
`name-1.ext` instead.

**Now:** the daemon's own delivery folder is swept first, and the pull's
copy of a file that has just been exported is removed.

**Measured on the device:** three files sent to the phone, three files on
the phone, one copy of each.

---

## Still open


### 1. The desktop cannot use a clipboard the phone sends

The phone sends the clipboard as a Taildrop file named `clip-*.clip`. The
desktop receiver treats it as an ordinary file and drops it in
`~/Downloads/Tailscale`. It can be opened and copied from there, but nothing
puts it on the desktop's clipboard.

The desktop already has `wl-copy`, `wl-paste`, `xclip` and `xsel`
installed, so this is a small change to `desktop/taildrop-receiver`.

### 2. A new release never updates the app

Every build signs the app with a key generated on the spot, so two releases
have different signatures and Android refuses to replace one with the other.
`customize.sh` now notices this, removes the old app and installs the new
one, so a flash does the right thing — but the app loses nothing, because
it holds nothing.

The real fix is one signing key used by every release. The release workflow
already reads one from `TAILBRIDGE_KEYSTORE_B64` if the repository has it as
a secret; it does not yet.

---

### The app's audit — v1.0.31

The app was read through and used on the phone. What it found:

**Fixed**

- **Every switch reported that root was refused.** A root call that prints
  nothing is taken to mean that root was denied, because that is what an
  empty answer from a refused root shell looks like. `setprop` prints
  nothing, so the new wireless debugging switch always said "root
  permission denied" — after it had already done the thing. The command
  says when it is done now.
- **The store's permissions were `0666`.** The README said `0600`. Every
  rewrite of the store — sealing it, starring an entry, deleting one,
  pruning it — wrote a new file and took whatever the shell's umask said.
  The mode is put back now, in all four places.

**Still to look at**

- **The "SNAT subnet routes" switch does nothing on Android.** The daemon
  runs with `NetfilterMode: 2`, which is the userspace mode with netfilter
  off, so there is nothing for SNAT to change. The switch reports the
  daemon's own setting honestly, but turning it on and off has no effect
  and the screen does not say so. It should either be hidden or marked.
- **"Advertise as exit node" on a phone with netfilter off.** The daemon
  forwards traffic fine, but the masquerading that normally hides the
  phone's address behind it is a netfilter thing. Whether an exit node on
  this phone works, and what the other side sees, has not been measured.
- **The updater can lose a race at boot.** It waits eight times, twenty
  seconds apart, and gives up. On a phone that takes longer than that to
  get onto the network, the feed is unreachable and the message says so
  even though it is only a race. It retries six hours later. Measured: the
  update check works once the network is up.

## Not yet looked at

Known to be worth a look, not yet measured:

- **Images are not in the history.** The clipboard can hold an image, and
  the helper reads the text and the URI of whatever is there, but not the
  picture itself. An image is a different size of thing: a picture is
  megabytes, so it cannot live in the line of text the history is made of.
  It would need a file of its own, encrypted the same way, with the entry
  in the history pointing at it, and the app would have to show a
  thumbnail rather than a line of text. The byte limit would then be the
  one that matters, rather than the number of entries.
- **The store's size limit is a constant.** The number of entries and the
  number of days can be set in `clipboard.conf`, but the byte limit is
  eight megabytes in the script, and there is no way to change it without
  editing the module.

- The "N file(s) received" note is fetched once per launch, so it is stale
  for as long as the app is open.
- The supervisor's cycle is 30 s, so a daemon that dies can stay dead for
  half a minute.
- The store replaces the last entry when the same text is copied again, so
  copying the same thing twice shows one entry with the newer time. That
  is deliberate, but it is not obvious from the screen.
- A `.clip` arriving while the phone's screen is off: does the clipboard
  still get set?
- The receiver polls every 2 s. It is event-driven on the desktop but not
  here.
- The app asks for root on every call to the module. Each one is a trip
  through the root manager.
