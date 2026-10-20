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

## Still open

### 1. "Is the daemon running" can be fooled

`api_status` and the supervisor both ask

```sh
busybox pgrep -f 'tailscaled -no-logs'
```

which matches **any** process whose command line contains that string.

This bit during this work: a diagnostic shell whose own command line
mentioned the daemon made the API report `daemon: running` while the daemon
was stopped. The supervisor uses the same test, so it could fail to restart
a daemon that had really died — the one job it has.

**Fix**: `pgrep -x tailscaled`, which matches the process name exactly and
cannot be matched by a command line that merely mentions it.

### 2. The app fetches the whole clipboard history, every time

`clipboard list` returns every entry, pretty-printed. Measured at 20
entries: 2387 bytes in 141 ms. At the 5000 entry limit that is about
**400 KB**, over a root round trip, on every refresh — and a refresh happens
on every broadcast.

**Fix**

- A limit on the API, and the app asks for the few hundred it is about to
  show.
- Compact output rather than pretty-printed.

### 3. The desktop cannot use a clipboard the phone sends

The phone sends the clipboard as a Taildrop file named `clip-*.clip`. The
desktop receiver treats it as an ordinary file and drops it in
`~/Downloads/Tailscale`. It can be opened and copied from there, but nothing
puts it on the desktop's clipboard.

The desktop already has `wl-copy`, `wl-paste`, `xclip` and `xsel`
installed, so this is a small change to `desktop/taildrop-receiver`.

### 4. A new release never updates the app

Every build signs the app with a key generated on the spot, so two releases
have different signatures and Android refuses to replace one with the other.
`customize.sh` now notices this, removes the old app and installs the new
one, so a flash does the right thing — but the app loses nothing, because
it holds nothing.

The real fix is one signing key used by every release. The release workflow
already reads one from `TAILBRIDGE_KEYSTORE_B64` if the repository has it as
a secret; it does not yet.

---

## Not yet looked at

Known to be worth a look, not yet measured:

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
