## v1.0.27  2026-10-20

Fixes a regression introduced in v1.0.26, which broke the boot.

- **The daemon, the receiver and the supervisor now start at boot.** v1.0.26
  recorded the module's directory during installation, when the manager
  unpacks a module into `modules_update/` and only moves it to `modules/`
  afterwards. Every later read of `module.prop` therefore pointed at a
  directory that no longer existed, and because those reads happen under
  `set -e`, the failing read took the boot script down with it -- on its
  very first line, before it had started anything. The phone came up with a
  daemon that had not been started, and nothing in any log to say so.
- **A send no longer looks like it failed.** With the receiver dead, the
  daemon held the delivery and it arrived 25-40 seconds later, if at all --
  long enough for the send to look broken. It now arrives in about four
  seconds.
- **The root manager no longer says "running" when it is not.** The module
  description was compared against a state file of our own rather than
  against what the description actually said, so a stale description stayed
  stale forever. It is now read back from the file and corrected.
- **The boot script survives any single failure.** Each step is independent:
  a daemon that refuses to start no longer stops the receiver and the
  supervisor from starting.
- **Rotating the daemon's log is no longer fatal.** On a fresh boot the log
  file does not exist, and the test that checked for it aborted the script
  that starts the daemon.
- **The receiver can no longer kill an unrelated process.** Its pid file
  outlived the process, and the kernel is free to hand that process number
  to something else; stopping the receiver could then signal a stranger. The
  process is now checked before it is signalled, and the pid file is removed
  on exit.

## v1.0.26  2026-10-19

- **The daemon no longer disappears at boot.** The module used to move its
  boot script into KernelSU's "general scripts" directory, where the manager
  app can switch a script off. Switching one off truncates it to zero bytes
  and removes its execute permission, and a script in that state is skipped
  at boot with one line in the log that nobody reads. The phone then came up
  with no daemon and no complaint anywhere -- the only symptom was the app
  reporting a refused socket. The boot script stays in the module directory
  now, which both Magisk and KernelSU run anyway, and the installer removes
  the old copy and refuses to finish if the script did not install properly.
- **A supervisor.** Something now notices when the daemon or the Taildrop
  receiver dies and puts them back, within about half a minute. Until now
  the boot script ran once and nothing ever checked again, so a daemon that
  died stayed dead until the next reboot. A daemon switched off on purpose --
  with the app's toggle, or with the module's `disable` file -- is left
  alone.
- **An update can no longer leave the phone without a daemon.** Every
  failure path in the updater now checks the daemon is up before returning.
- **The root manager no longer says "running" when it is not.** The module
  description showed the daemon's state, but it was only written when the
  daemon was started or stopped, so a daemon that was killed left the
  manager claiming it was running. The state is now re-checked rather than
  remembered.
- **The app shows the supervisor's state**, so it is visible whether the
  module is working or merely quiet.
- **The boot wait is bounded.** The script waited for the boot to complete
  forever, with no limit and no message.
- **The boot script no longer blocks on the update check**, which can take
  minutes on a slow link.
- **The module directory is no longer hardcoded.** It is named after the id
  in module.prop, which has changed over the life of the project; the
  installer now writes down where it actually is.
- **`settings.sh` can no longer abort a script by accident.** It is sourced
  by every script under `set -e`, so a single failing command in it killed
  the script that sourced it, silently.

## v1.0.25  2026-10-17

- Nautilus menu no longer freezes on large folders. Packing a folder into a zip
  happened on Nautilus main thread, freezing the file manager for as long as the
  archive took. Staging now happens inside the detached sender process, so the
  menu click returns immediately.
- Peer connection status: direct vs relay. The app shows whether each peer is
  reached directly or via a DERP relay (with region name). Fixed the API
  reporting the home relay instead of the active relay.
- Offline peer label. A peer that is known but unreachable now reads "offline".
- Seamless network toggle. ConnectivityManager NetworkCallback refreshes the
  peer list on wifi/mobile-data toggle without an app restart.

## v1.0.24

### The context menu disappeared when no device was reachable

The entry was hidden unless a peer was online, which is indistinguishable from
the extension not being installed at all: the first time it happened the phone
had dropped off the tailnet, and the sensible conclusion was that Nautilus had
stopped loading the file. It now always appears, with the reason inside it.

Offline devices are listed and sendable, marked "(offline)". Taildrop reaches a
sleeping device through a relay, so hiding them prevented sends that worked.

### Folders are sent as zip, not tar.gz

Both are one file either way, so the choice is about what the receiver can
open. Android handles zip far more widely: the Files app offers "Extract to..."
for a zip, while a tar.gz is a dead end without a third-party archive manager.

Empty directories inside a folder are preserved. `os.walk` reaches them but
reports no files under them, so they were dropped and the extracted folder did
not match the one sent.

### The README describes what this actually does

It listed what the module was rather than what it does, and omitted the
receiver, the log, folder packing, and the troubleshooting steps that exist
because each of those cost real time to work out.

## v1.0.23

### A folder arrived called `taildrop-f2dsoszy-Holiday.tar.gz`

The archive was built with `tempfile.mkstemp(prefix="taildrop-")`, which puts its
own random string in front of the name. The temporary name is where the file
sits during the send, not what it is called, and the receiver only ever sees
the name.

Both receivers also took the extension to be whatever followed the last dot, so
a second delivery of `photos.tar.gz` became `photos.tar-1.gz`, which is not a
name anyone has. Compound extensions are now split as a unit, and a dotfile is
no longer numbered into `-1.gitignore`.

## v1.0.22

### The released zip shipped an app that called itself the previous version

build-module.sh built the APK only when one was absent, so a stale APK was
packaged without comment. build.sh stamps the version into the app from
module.prop, so an APK built before a version bump carries the old one: v1.0.21
was published with an app reporting v1.0.20. The app is rebuilt on every
package now, and the build fails if the APK's version does not match
module.prop.

### The third-party notices did not describe what ships

They listed `hev-socks5-tunnel`, which this module does not use and which is
not in the zip, left over from the module it was forked from. They did not list
jq, which is bundled in both zips and carries its own MIT licence plus notices
for the code it incorporates.

module.prop also called the module "Magisk Tailscaled" and described it with
Tailscale's own marketing line. Everything else in the project says Tailscaled
Reborn.

## v1.0.21

### One send could deliver the same file more than once

dispatch() built a de-duplicated copy of the target list and then iterated the
original, so the copy did nothing. The addresses do arrive de-duplicated from
currentTargets(), but that is a convention three call sites had to remember,
and a file delivered three times cannot be undone at the receiving end.

### The receiver waited in a poll loop instead of being told

`tailscale file get --wait` blocks until a delivery arrives. The receiver called
it on a five-second timer, so a send could sit uncollected for five seconds and
a subprocess started every few seconds for the whole login session. Taking a
destination name now uses link(2), which fails rather than clobbering.

Logs went to stdout, which is also the channel `deliver()` returns its count
on, so every arrival produced `[: <log line>: integer expected` in the journal.

## v1.0.20

### The desktop receiver was rewritten to watch a directory the daemon never writes to

This one was introduced as a fix and broke the receiver. Linux and Android
tailscaled disagree about Taildrop: Android's daemon writes each delivery
straight into the download folder, Linux's holds it until somebody calls
`tailscale file get`, and `tailscale file cp` reports success throughout.

The receiver had been switched from fetching to watching `~/Downloads` on the
assumption that both platforms behaved like Android. On Linux the daemon never
writes there, so a send from the phone reported success and arrived nowhere.
Fetching is restored, into a staging directory the receiver owns.

## v1.0.19


### The Nautilus menu built a menu and then went quiet

Three separate faults sat between "Send via Taildrop" appearing and a file
arriving, and each one had to be found before the next was visible.

**A GObject was read from a background thread.** The sender called
`fileinfo.get_uri()` on a `Nautilus.FileInfo` after the click. That is a
GObject call off the main thread, and it did not raise: it blocked. Every send
stopped dead at the same log line, with no error and no notification. The
selection is now read on the main thread while the menu is built, and the
sender receives plain strings.

**A thread could not run anyway.** Even without that, CPython will not schedule
a background thread while Nautilus's GTK main loop holds the GIL. Measured:
a click at 20:31:32 did not reach `tailscale file cp` until 20:31:52, with an
empty loop in between. The send now runs in a detached process, which has its
own GIL, is not scheduled around anything Nautilus does, and survives the file
manager being closed mid-send. A daemon thread died with the interpreter and
the file was lost unreported.

**A delivered file was reported as a failure.** The check was
`rc == 0 and stderr empty`. `tailscale file cp` exits 0 when it delivers and
writes a warning to stderr when the phone has been idle --
`# warning: ... is not replying; trying anyway` -- then delivers over a relay.
So "Sent 0/1" for files that had already arrived, and because the warning only
appears when the phone is asleep, sending looked broken at random. The exit
status is authoritative now; stderr is read only for lines that are not a
warning. The app's own send path had the same assumption in the form of a
fixed list of error words, and now uses the same rule.

### Sending a folder killed the sender thread

`_stage` returned the user's own folder as the thing to delete once the send
succeeded, so a successful folder send ended in `os.unlink()` on a directory:
IsADirectoryError, thread dead, no file, no notification. It returns the
tarball it made now, and the sender body is wrapped whole.

### Resending the same file stopped working entirely

Four faults stacked. A seen-list keyed on filename meant that once a name had
been seen, every later delivery of it was skipped forever. The receiver copied
files out instead of moving them, so they piled up in the delivery folder
permanently. The daemon does not overwrite a name that is already there, it
holds the file -- so a leftover did not merely go unannounced, it blocked every
later delivery of that name. Adding size and mtime did not help, because
re-sending the same file produces the same bytes with the same timestamp.

The seen-list is gone. A file is unhandled exactly while it is still in the
delivery folder, and the receiver moves it out, so presence is the whole test
and there is no bookkeeping to get wrong. The backlog this had been hiding was
flushed on the first sweep: eleven files, including one sent hours earlier that
had never arrived and had been written off as lost.

### The update said "Done" when the update had failed

`api_update` ran the updater with `|| true`, discarded its output, and returned
a plain status, so a failed download produced the same "Done" as a successful
install. It reports the version either side of the run and the state the
updater recorded, and fails with the reason when it did not succeed. The row
checks first and says so when there is nothing to do, instead of downloading a
binary and restarting a daemon to change nothing.

### The desktop receiver watched a directory that never fills

It looped on `tailscale file get`, which returns success and collects nothing
on a current daemon -- which is how `proof.txt` came to sit in `~/Downloads/`
unclaimed. It now watches the download directory the daemon actually writes to,
and collects only files that appeared since it last looked, so it never moves
your own downloads. The first run adopts what is already there.

### One send could deliver the same file more than once

dispatch() built a de-duplicated copy of the target list and then iterated the
original, so the copy did nothing. Any address appearing twice in the list sent
the file twice to the same machine. The addresses do arrive de-duplicated from
currentTargets(), but that is a convention three call sites have to remember,
and a file delivered three times cannot be undone at the receiving end: it
becomes three files and three notifications there, which is what it looks like
from the other machine. The de-duplication is now done where the send happens.

### The receiver waited in a poll loop instead of being told

`tailscale file get --wait` blocks until a delivery arrives. The receiver was
calling it on a five-second timer instead, which meant up to five seconds
between a send and the file being collected, and a subprocess started every
few seconds for the whole login session. It now waits, following the shape of
Omarchy's own receiver: fetch with --wait, move whatever landed, wait again.

Taking a destination name uses link(2), which fails rather than clobbering, so
two arrivals that want the same name cannot race between the check and the
move. It is probed rather than assumed, because hard links need one filesystem.

Logs went to stdout, which is also the channel `deliver()` returns its count
on, so the log lines were fed into that variable: "[: <log line>: integer
expected" once per file, for every arrival.

### Not leaking

The detached sender is nobody's reaper, so it bounds itself: 120s per file
instead of 300s per attempt, a ten minute ceiling for a whole send, and temp
archives removed on every exit path including the timeout. Six back-to-back
sends leave no orphaned process and no file in `/tmp`.

### The extension had no diagnostics at all

It logged only under `NAUTILUS_DEBUG=1`, which the process that matters never
has, so nothing recorded whether a menu was built or a click arrived. It now
always writes `~/.cache/taildrop-nautilus.log`, size-capped, with the
selection, the peers found, and the result of each send.

## v1.0.18

### Nautilus: a delivered file was reported as a failure

The send checked `rc == 0 and stderr empty`. `tailscale file cp` exits 0 when
it delivers, and it writes a warning to stderr when the receiving device has
been idle:

    # warning: 100.76.39.7 is not replying; trying anyway

and then delivers the file anyway, over a relay. The check called that a
failure. The extension reported "Sent 0/1" for transfers that had already
arrived on the phone, and because the warning only appears when the phone has
been asleep, the same file from the same machine failed or succeeded at
random. That is what "sending is broken" actually was.

The exit status is now authoritative: non-zero means it could not deliver, and
it always is when it cannot ("can't send to ...: unknown target"). stderr is
still read, but only for lines that are not a warning, so an error printed
alongside a zero exit is not quietly accepted.

### Nautilus: sending a folder killed the sender thread

`_stage` returned the user's own folder as the thing to delete once the send
succeeded, so a successful folder send ended in `os.unlink()` on a directory:
IsADirectoryError, thread dead, no file, no notification, nothing in any log.
It now returns the tarball it made. The sender body is also wrapped whole, so
nothing can take the thread down silently again.

### Nautilus: the extension had no diagnostics at all

It logged only when `NAUTILUS_DEBUG=1`, and Nautilus is started from the
desktop session where that variable is never set, so the file that matters
produced no output. Every report of it being fragile was unanswerable because
nothing recorded whether the menu was built, which peers it listed, or whether
a click arrived. It now always appends to `~/.cache/taildrop-nautilus.log`,
size-capped, with every failure to write swallowed.

### The update said "Done" when the update had failed

`api_update` ran the updater with `|| true`, discarded its output and returned
a plain status, so the app reported success after a failed download exactly as
after a successful install. The daemon's own log recorded "Could not obtain the
new binary; keeping 1.104.1" at the moment the app claimed Done.

It now reports the version either side of the run and the state the updater
recorded, and fails with the reason when the run did not succeed. The row
checks first: if the running version is already the latest it says so and stops,
instead of downloading a binary and restarting a daemon to change nothing.

`available` is now a real JSON boolean in both branches. The fallback branch
hardcoded it to true, so a dry-run naming the version already installed
reported an available update whose `latest` equalled `current`.

## v1.0.17

### The received-files count was wired to a handler that did not exist

The app asked the API for the last Taildrop delivery and displayed "N files
received while you were away". The API script dispatched that command to a
function that was never written, so the call returned a shell error, the app
caught it, and the count stayed at zero with nothing on screen to say why.

The handler is now implemented, reading the file the receiver writes. It parses
`at=`, `count=`, `names=` and `dir=` as the key/value lines they actually are.
It had been read as JSON, which fails silently and would have kept the count at
zero even with the handler present. The reply is flat, because that is what the
app reads.

### Peer ping: removed rather than finished

A ping handler was written for this version. The feature was already gone from
the app and nobody called it, so it was deleted instead of shipped. One less
way to be wrong about an endpoint's behaviour.

### The Nautilus extension no longer runs a permanent poller

The menu read a peer list from a background thread re-querying every 3 seconds
for the whole life of the file manager, and also queried again on every
right-click. So it paid for a query nobody was reading, and the menu's contents
still depended on when that thread happened to run. Adding a device and
right-clicking could easily show a list that did not include it.

The list is now fetched when a menu opens, and reused only if it is under five
seconds old. A failed query no longer empties a menu that worked a moment ago.
Peer IPs are still resolved fresh at send time, because a tailnet IP changes
when a device re-registers and sending to a stale one loses the file.

### The Linux receiver was watching an inbox the daemon stopped filling

Same mistake as the phone, in `~/.local/bin/taildrop-receiver`: it looped on
`tailscale file get`, which returns nothing on a current daemon because
deliveries arrive directly in `~/Downloads/Taildrop`. Files arrived and were
never announced, which is exactly how `proof.txt` ended up sitting in
`~/Downloads/` unclaimed.

It now sweeps the delivery directory as well as the inbox, with the same
persistent seen-list as the phone: a name-based record that survives restarts,
so a file arriving between two sweeps is still announced. First run adopts what
is already on disk instead of announcing a backlog.

### A read that hangs now fails in 30 seconds

Every API call shared login's 240-second budget, because login waits on a person
opening a link. A status read answers in well under a second normally, but a
daemon mid-restart can hang the shell, and that left the UI waiting four
minutes with no way to tell a slow phone from a dead one. Reads get 30 seconds;
update and rollback keep a long budget because they really do take minutes.

### Smaller corrections

- A failed login showed an alert with an empty body, because the fallback
  string was only used when the key was absent, not when it was blank.
- Folders were reported as `resource/folder`, which is not a MIME type any
  other component recognises. Now the standard document-directory type.
- `tailscaled.service` help pointed at `logs.sh` and `service.sh`, neither of
  which exists. Now the commands that do.
- An unknown subcommand ran `return` at the top level of the script.
- A comment claimed the installer and the updater read the same repository. They
  read two, which both carry the same assets. The comment says so now.

## v1.0.16

### Taildrop was not broken. It was landing somewhere nobody looked.

The daemon no longer stages incoming files in a root-only inbox for
`tailscale file get` to pull. It delivers them straight into
`Downloads/Taildrop`, and leaves nothing behind to collect.

The receiver was still watching the old inbox, so:

- every send reported success and delivered a file
- the receiver found an empty inbox and announced nothing
- the files sat in `Download/Taildrop`, which the app never mentioned

This is why sending looked broken and no notification ever arrived. Not a
network fault, not a rejected transfer, not the address being stale. The
receiver was reading a directory the daemon had stopped writing to.

The receiver now sweeps the delivery folder as well as the inbox, so it works
across the daemon change either way.

### Deliveries that arrived while the receiver was down were never announced

The receiver decided what was new by listing the directory and comparing with
the previous poll. A file that landed between one poll's sweep and the next
poll's listing was, on the next poll, simply part of the listing and therefore
old. Every such delivery was skipped, silently, forever.

Names are now recorded in a list that survives restarts. The first run adopts
what is already on disk, so installing the module does not announce a backlog,
but anything arriving later is announced even if the receiver was not running.

### A share-capable rework was tried and pulled the same evening

The released v1.0.16 zip briefly carried two share-sheet experiments: a
translucent share window that hid the app's own chrome and launched with
`noHistory`, `excludeFromRecents` and an empty `taskAffinity`, and then a
direct-share target built against compile-only stubs of the hidden
`android.service.chooser` API. Both were pulled before v1.0.17.

The launch flags lived on the activity that is both the launcher icon and the
share target. `noHistory` destroys the app's only activity whenever it leaves
the foreground, `excludeFromRecents` means there is no recents entry to come
back to, and `taskAffinity=""` detaches it from every task — three ways of
making the app not persist while it is being used. Tapping Check for update
(which restarts the daemon behind a long status poll) made this visible as a
glitching UI and lost nav state; the app had to be closed the hard way. The
tinted share window read as broken rather than as a picker, and the device
picker itself was more machinery than the job needs. Sharing now behaves as it
did in v1.0.15: the sheet opens TailBridge on the Send tab with the picker
ready. The hidden-API stubs, the popup theme and every launch flag are gone
from the manifest, the code and the build.

The app itself is now true AMOLED black. The page was `#0C0F13`, a dark
blue-grey: lit pixels on an OLED panel, and a dark image scrolling past smears.

### `tailscale file cp` exits 0 when it delivers nothing

The exit code was being read as proof of delivery. It is not proof of anything.
The send path now requires a clean exit *and* no error text before reporting
success, and names the likely cause when it does fail.

An earlier version of this check went too far and required a `sent` line in the
output. This daemon prints nothing on success, so that reported every working
send as a failure. The check can now confirm failure; it cannot confirm
success, and no longer pretends to.

### Pull to refresh works on every tab

Only the Send tab did anything, and its feedback was a single 360-degree spin
of the header button. The other two tabs were wired to an empty lambda.

All three tabs now share one indicator: the arrow tracks the finger, rotates
with the drag, resists past the threshold so it is obvious that letting go will
commit, and commits on release. Firing the moment the threshold was crossed
made the list jump out from under your finger.

### Removed

- **Receive files.** The receiver pulls automatically every two seconds, so
  this button could only ever report "nothing received". It now shows how many
  deliveries landed and opens the folder.
- **Peer ping.** Removed. It had to guess at output format, failed often, and
  nobody wanted it.

## v1.0.15

### A broken status document blamed your root permission

The peer `direct` flag added in v1.0.14 was written as invalid jq:

    direct: (.PeerRelay // "") == "" and (.Online // false),

`and` at that precedence is a syntax error, so jq refused the **entire**
program. Every status read failed, the app got no JSON, and its error screen
said **"Grant root permission"** — sending you to a setting that could not
possibly fix it. Root worked the whole time.

It parses now, and `direct` and `lastSeen` come through with real values.

### The app no longer blames root for everything

There were three ways a read could fail and the app collapsed them into one
message. They now say different things:

| | |
|---|---|
| `su` refused | "Grant root permission" — correct |
| `su` worked, module absent or broken | "Module not working — reflash the module zip" |
| `su` worked, module answered with garbage | "Module is installed but not answering" plus what it actually said |

The old behaviour cost an evening of debugging: the app insisted on a permission
that had already been granted, while the actual fault was a syntax error in a
shell script.

## v1.0.14

### Header sat under the status bar

The header's top padding was a fixed `22dp`. That is either cramped under a
tall status bar or wasteful under a short one, and it is measured per device
rather than guessed. The real system-bar inset is now read at runtime and added
to that floor, so the title clears the clock on any device, including cutouts
and gesture bars.

### "Sign in" was offered to people already signed in

The row was unconditional, so an authenticated user saw both Sign in and Sign
out, next to a Tailnet showing their address. It reads as though the app never
noticed. Whichever action makes sense is now the only one shown.

### Refreshing no longer waits on the internet

Every refresh kicked off a **network** update check behind the status read, so a
tap on refresh looked like it hung on a local root call. Throttled to once an
hour. The "N files arrived" note is also a second `su` round-trip and is now
fetched once per launch, off the render path, instead of on every rebuild.

### Exit nodes

You could *advertise* this phone as an exit node but there was no way to
*choose* one, which is the half people actually use. **Settings → Use an exit
node** lists only devices that offer themselves, marks offline ones, and can
disconnect. The current one is shown on the row.

### Peer quality

- **ping** on a peer row: latency, and whether the path is direct or relayed.
  An online peer can still be a slow one, and "it is going via Bangalore" is a
  different problem from "Tailscale is broken".
- **last seen** on offline peers: "offline · 4m ago" tells you whether to wait
  or give up.
- **pull to refresh** on all three tabs, alongside the button.
- **files received while the app was closed**, on the Send tab.

## v1.0.13

### The phone installs updates itself now

`tailscale update --yes` is gone from the install path. That command talks to a
URL baked into the binary at compile time, pointing at `/releases/latest` of
whichever repo was configured at build time. This repo publishes both flashable
module zips and daemon builds, so "latest" is whichever came last, and the
daemon release lives elsewhere entirely. It reported success and installed
nothing.

The updater now downloads the release the feed names, itself:

- reads `tag` and `repo` from `tailscale-latest.json`
- fetches `tailscale_<ver>_arm64.tgz` from that repo's release
- verifies it against `SHA256SUMS` and refuses on mismatch
- checks the extracted binary actually executes before installing it
- snapshots, swaps, restarts, health-checks, and rolls back as before

Verified on the device end to end: download, checksum, extract, executable
check, swap, restart, health check, `rc=0`. The verifier was also seen
correctly refusing a download whose checksum did not match.

The feed is read **once** per operation. Two fetches could disagree, and the
second could simply fail -- which showed up as an update that was decided on
and then not installed.

### The daemon release no longer shows as the module

Republishing the 1.104.1 archive here (the archived repo is read-only, and its
`SHA256SUMS` did not match the asset it shipped) created a release that GitHub
put at the top as *Latest* -- so a user landing on Releases would have been
offered a 9 MB binary instead of the zip to flash. It is marked a pre-release
now, and the module is Latest again.

### Removing the module removes the app

`uninstall.sh` shipped **without the executable bit**. KernelSU runs it as a
program rather than through `sh`, so it failed with permission denied: the
module was removed and TailBridge stayed behind, as an icon leading to a screen
that could only say "grant root permission".

It is `0755` in the zip now, and `customize.sh` sets it explicitly as well.

## v1.0.12

### The desktop extension survives an address change

A peer's tailnet address moves when it re-registers, and the extension sent to
whatever address the menu happened to be built with. `tailscale file cp` does
not fail for a stale address -- it queues the file and prints "sent" -- so the
transfer was silently lost while the sender was told it worked. Sending again
appeared to be the fix; it was the only recovery.

Sends resolve the address by device name at send time, and a failure
re-resolves once and retries before reporting anything.

Measured after: 23ms for the CLI to accept, **404ms** for the file to be on the
phone.

## v1.0.11

### The notification buttons actually open things

"Open" and "Open folder" both did nothing, and the reason was not a typo. They
pointed at a storage document URI:

    content://com.android.externalstorage.documents/document/primary%3ADownload%2FTailscale

A document URI may only be used by an app that was *handed* it through SAF
(ACTION_OPEN_DOCUMENT and friends). `FLAG_GRANT_READ_URI_PERMISSION` cannot
manufacture that grant for a URI we do not own, so every tap threw:

    SecurityException: UID 10384 does not have permission to
    content://com.android.externalstorage.documents/document/primary%3ADownload%2FTailscale

Two of my earlier "it works now" claims rested on running the *intent* by hand
and seeing it resolve. That proved the URI was well-formed. It proved nothing
about whether a tap had permission, which is a different question, and it is
the one that was broken.

- **Open** goes through a small read-only `FilesProvider`. We wrote the file, so
  we own the descriptor and the grant is real. Hand-rolled rather than androidx
  FileProvider, because this app has no AndroidX and should not gain one to
  open a text file.
- **Open folder** goes through a real `DocumentsProvider`, which is what lets a
  file browser list the folder at all. It uses the standard
  `vnd.android.document/directory`, not `resource/folder`: the latter is what
  MiXplorer registers, but MiXplorer cannot browse a DocumentsProvider, so it
  only offered the folder to an app that would fail. The system file manager is
  offered instead.

Both providers are scoped to `Download/Tailscale`, canonicalised, and refuse
anything else.

### Receiving is 5x faster

The poll went 10s to 2s. An empty poll costs about 10ms.

`tailscale file get --wait` is what the Linux receiver blocks on. It was tried
here as the trigger and is **not** one: measured on this device it blocks, but it
does not wake when a delivery arrives -- a file sent 5s in was still not returned
after 40s, while the poll had already picked it up. Polling has to be the
trigger. Name collisions are now handled by tailscale itself with
`--conflict=rename`, rather than by a hand-rolled suffix loop.

### The Nautilus extension no longer stalls the file manager

Nautilus calls `get_file_items()` on its main thread, on every selection change.
The extension was doing two `tailscale status --json` spawns per right-click,
and -- the real one -- **staging the selection while building the menu, including
tarring a whole folder**. Compressing a large directory on the UI thread freezes
Nautilus for as long as it takes. That is almost certainly the freeze you saw.

The menu now does no work at all: a background thread keeps a status snapshot
warm, the menu reads it (0.01ms), and staging plus sending happen after you
pick a device, on a worker thread. Every path is wrapped so an error costs a
missing menu entry rather than the file manager.

## v1.0.10

### Sharing asks where to send, instead of dumping you on a screen

A share dropped the files on the Send tab and left you to find them, tick a
device and press Send. Coming from the share sheet the destination is the only
thing left to decide, so it is the only thing asked: a picker appears with the
devices, their online state, and multi-select.

Three bugs, all of them mine, all of them silent:

- **`dispatch()` takes addresses, not names.** The picker passed peer names, so
  `api_send` rejected every one as an invalid peer. The in-app picker passed
  addresses and worked, which is exactly why the two paths disagreed.
- **The Send button's click listener was overwritten** while adding the
  "recolour it when something is selected" polish. The button recoloured
  itself and did nothing else -- the picker looked alive and sent nothing.
- **A share sent as a string URI** was dropped: `getParcelableExtra` returns
  null for it. Now parsed as a fallback.

The dialog uses the app's own theme. It was a stock light Material dialog on a
dark screen, which read as a different app dropped on top of this one.

### The receiver stopped leaking

`tailscale file get` never removes what it copied, so the inbox was a permanent
archive of every file the device had ever received. The inbox copy is now
removed once the export to `Downloads/Tailscale` is confirmed; a failed export
is retried instead. That also halves the storage a delivery costs.

Failures are no longer silent. Three consecutive failed pulls -- a restart
looks like one, a broken daemon looks like three -- raise a notification
instead of quietly never arriving again. The poll interval went 5s to 10s,
which costs nothing for an alert a human reads.

## v1.0.10

### The notification buttons actually work now, and there is an "Open" too

"Open folder" did nothing. The URI was hand-written as
`.../root/primary/Download/Tailscale`, but the storage documents provider
addresses a document as `.../document/primary%3ADownload%2FTailscale`; the
hand-rolled form has no `/document/` segment and resolves to nothing.
`DocumentsContract.buildDocumentUri` builds it correctly.

Two more things the URI needed:

- **The MIME type has to be pinned.** Without it the intent matches everything
  that handles `content://` loosely -- a browser and a chat app came back for a
  text file. The folder needs `vnd.android.document/directory` or DocumentsUI
  is not among the candidates at all.
- **"Open" is a new action**, for the first file received, typed from its
  extension. One action per file would exceed the three-action limit and the
  platform would silently drop the rest, so it is a single "Open".

"Open" sits first because the file is the reason for the alert.

### Offline devices are visible, and cannot be selected

The device list showed no presence at all, so an offline machine and a deleted
one looked identical -- and you could tick an offline device, get a success
toast, and have nothing delivered. Taildrop reports success on a queued send
regardless.

Peers now carry `online` through to the UI with a green or red dot. Offline
peers stay in the list, greyed, with their checkbox force-unchecked, their send
arrow disabled and a tap that explains why. `chosen` is pruned against the
online set on every refresh, so a device that goes down stops being a target by
itself. The button reads "No device online" rather than being a dead "Send
files". A device that is down should look down, not gone.

### Refresh button

The header has one. It forces a fresh root round-trip rather than re-rendering
what is already held, because peer presence, daemon state and the address list
all change on the device and not in the app. Without it the only way to see a
device come back was to close and reopen TailBridge.

### "No internet" is now distinguishable from "signed out"

Both showed an amber dot and said nothing. They are different problems: a
signed-out device cannot reach anything, while a signed-in device with no
internet still connects to peers on the same network directly -- it only loses
login, relays and MagicDNS. The header says which one it is, using the
daemon's own `Self.Online`.

### On INTERNET permission

Deliberately **not** added. TailBridge opens no sockets: it execs
`/system/bin/su` and talks to the daemon through that, and tailscaled does its
own networking outside the app's sandbox. The only permission the app declares
is `POST_NOTIFICATIONS`. Adding INTERNET would grant a capability it never uses
and tell users the app phones home, which it does not.

## v1.0.9

### Files sent from a desktop now arrive by themselves

Taildrop is push on the sender and pull on the receiver. `tailscale file cp`
drops a file in the phone's inbox and returns; the sender is told it worked and
the file goes nowhere, because the only thing that could pull it was a button in
an app that had to already be open. Linux had the mirror-image problem, and
`desktop/taildrop-receiver` covers that side.

`tailscaled.receive` now runs alongside the daemon: it pulls on an interval,
copies each delivery into `Downloads/Tailscale` so the Files app sees it, and
alerts you. Three bugs were found and fixed getting it there, all of them
silent:

- **`settings.sh` runs under `set -e`**, so killing a stale pid tore the whole
  script down. Every non-zero exit in a daemon loop is ordinary, so the loop
  now runs with errexit off.
- **The drain ran after `file get --wait`.** `--wait` pulled the file into the
  inbox first, so the snapshot that decides what is new had already seen it and
  classified every arrival as old. Files arrived and were never announced.
- **`case` with embedded newlines does not match under Android's shell**, so the
  same "is this new?" test silently answered yes to everything. Replaced with
  `grep -Fxq`.

The inbox is also never emptied by `tailscale file get`, so the receiver
compares against a pre-pull snapshot instead of re-announcing every file ever
received.

### Arrival alerts, with a button

`cmd notification post` returns success and posts nothing you can act on: it
creates a transient shell channel that is not in the shade afterwards and cannot
carry an action at all. So the script hands the delivery to TailBridge over an
explicit broadcast, and the app posts the real thing -- its own channel, high
importance, and an **Open folder** action that hands the path to the system file
manager via the storage documents provider.

One button, not two, on purpose. Opening a specific file needs a `FileProvider`
and a `file://` URI is not shareable across processes on Android 7+; opening the
folder needs neither. Same three taps, no manifest plumbing.

`POST_NOTIFICATIONS` is declared and requested on launch. It was missing
entirely, which is why the first attempt appeared to do nothing: the broadcast
reached the receiver, the process started, and the platform dropped the
notification on the floor.

## v1.0.8 -- release build verified

The v1.0.6 and v1.0.7 tags predate the feed-repo fix, so their `Build` steps ran
the old script and 404ed on the daemon binary. This tag is the first built with
the corrected script, and the first whose zips came from CI rather than by hand.

## v1.0.7

### Removed the original author's Telegram group

`tailscaled.service _troubleshoot` told users who hit a startup failure to
"Join telegram group at https://t.me/systembinsh/158". That is the original
author's support channel, inherited along with the rest of the module. It is
not ours to point people at, and sending strangers to someone else's group is
exactly the sort of thing that should not ship by accident.

The failure path now prints the commands that actually help -- follow the log,
check status, check for an update, start again -- instead of a link.

The repository's own homepage and description also pointed at that group and
copied the upstream summary; both now describe this fork instead.

## v1.0.6

### In-app sends did nothing at all

Tapping "Send to <peer>" opened the file picker, let you choose a file, and
then silently did nothing. No toast, no error, no log line -- the app behaved
as though the send had succeeded.

`launchPicker(List<String> ips)` was given the chosen peers and dropped them on
the floor. The list that `onActivityResult` dispatches to, `lastPick`, was
declared and read but never written to anywhere in the file, so it was always
empty and the result handler took its early-return every time.

The share sheet was unaffected because it routes through `pendingUris` and never
touches `lastPick`. That is why a share-sheet send reached the machine while an
in-app send vanished, which is the clearest evidence the two paths were never
equivalent.

The peers are now recorded before the picker opens, and the drop-it-quietly
branch logs its inputs and tells the user when nothing was picked -- a completed
picker result that goes nowhere should never again be invisible.

## v1.0.5 -- repo renamed

GitHub repo `paras-moharle/magisk-tailscaled` renamed to `paras-moharle/tailscaled-reborn`. Old URLs redirect automatically, but settings.sh, module.prop, update.json, the app's about link, and the install zip name now point at the new name directly. The second repo, `paras-moharle/tailscale-android-cli`, has been archived -- it was used for the original binary builds, which build-android.yml now runs from this repository.

## v1.0.4

### The auto-updater had never worked

`version_is_newer <a> <b>` returns true when **a** is newer than **b**, but all
three call sites passed `(installed, published)` and read the result as "an
update is available" -- the opposite sense. Measured against the real functions:

| feed | correct | before |
|---|---|---|
| 1.100 (older than installed 1.104.1) | hold | **update** -- would have downgraded the phone |
| 1.105, settled | update | **hold** -- would never have installed it |
| 1.105, fresh | hold | hold |
| 1.104 (same) | hold | hold |

So the updater would never install a newer build, and would have installed an
*older* one the moment the feed moved backwards. The "same version" row is the
only one that looked right, which is why this survived: installed and published
were equal in every state the module was ever in, so the one test anyone would
run by hand passes.

The arguments are now the other way round, the contract is spelled out above the
function, and `up_to_date`, the status line and `check` were all corrected.

### Settling window

A build published minutes ago is not offered for `TS_UPDATE_SETTLE_HOURS`
(default 6). The check interval was never the risk -- the timing was: without a
window, a bad upstream release reaches every phone within minutes of CI
publishing it, which is too fast to notice and withdraw. Rollback still protects
each device, but the fleet should not have to roll back at all.

The publication time travels in `tailscale-latest.json` as `publishedAtEpoch`, an
epoch integer rather than ISO-8601, because Android's toybox `date` cannot parse
a formatted timestamp. A feed with no such field is treated as settled, so an
older feed file can never block updates.

### Failures are reported now

A failed Android port build files an issue instead of dying in a CI log. See
v1.0.3.

## v1.0.3

An audit pass over the app and the module, mostly fixing things that were wrong
in ways that did not announce themselves.

### The send path lied

- **A shared file could go to the wrong device.** The picker's destination list
  was stored in a field that was never cleared, so after one picker-based send
  every later shared file was delivered to whichever device that picker had
  been opened for -- ignoring the checkboxes the user had just ticked.
- **"Sent 0 file(s)" was reported as success.** If every file failed to stage,
  nothing was transferred and no counter recorded the failure, so the run took
  the all-clear branch. Staging failures are now counted.
- **The per-device send arrow destroyed the selection.** Its comment said it
  would not touch it; it cleared the set *and* persisted the result. It now
  sends to that one device and leaves the selection alone.
- **The Send button could lie.** Offline peers stayed in the ticked set, so it
  read "Send to laptop" while there was nothing to send to. The set is now
  pruned against what is actually online.

### The poll died and never came back

A tick that arrived while a slow `su` call was still running returned early
*without rescheduling*, ending the poll chain for the rest of the session. The
header would then freeze until the app was backgrounded and resumed.

### Lifecycle and threading

- No `onDestroy` cleanup existed, so work queued on the UI handler could try to
  show a dialog on a window that was gone.
- `onNewIntent` never called `setIntent`, so after any recreation the app read
  the original launcher intent instead of the share. Staged shares are now saved
  and restored across rotation and process death.
- `Root` read an error buffer without the monitor its writer thread appends
  under, while that thread could still be running.

### Taildrop could receive nothing

The receive path existed but was never reachable from the UI, so inbound files
sat in a root-only inbox nobody could open. **System → Receive files** now moves
them into `Downloads/Tailscale` and lists what arrived.

### Smaller things

- "Run tailscaled" followed `backend == "Running"` and so showed an
  unauthenticated daemon as stopped; it now uses the module's own `daemon` field.
- `latestVersion` was written on worker threads and read on the UI thread with no
  synchronisation.
- The "X is available" sub-label on the update row was unreachable, so the row
  never showed why it mattered.
- `<queries>` was missing, so on API 30+ the root-manager button could not see
  any root manager and silently fell through to Android app settings.
- Detached views were left reachable after the root gate, letting a late failed
  write flip a checkbox nobody can see.
- Removed: the refresh button (dead since the poll), `materialize()`,
  `Peer.toString()`, `join()`, `daemonSwitch`, `rootError`, the unreachable
  `refresh(userInitiated)` branch, two unused imports, 16 unused strings, three
  unused drawables, two unused styles and an unused id. `Peer.exitNode` was
  parsed and never shown -- it is now.
- `.gitignore` had duplicate entries.

## v1.0.2

### Sign-in did not work, and failed silently

`tailscaled.api login` ran `tailscale up`. That is the wrong command for
authentication: `up` changes settings, and it refuses to run at all when the
node has any non-default flag set -- which is precisely the state anyone who has
ever touched a setting is in:

```
Error: changing settings via 'tailscale up' requires mentioning all
non-default flags. To proceed, either re-run your command with --reset ...
```

So the URL was never produced, the app had nothing to open, and tapping "Sign
in" appeared to do nothing. It now uses `tailscale login`, which only
authenticates and ignores preferences.

Two more things had to be true for it to actually work:

- **The login has to outlive the app's shell session.** `tailscale login` blocks
  until the browser round-trip finishes. It used to run as a child of the script
  the app invokes, and the app tears that `su` session down the moment it has
  read the output -- killing the pending login, so even a correct URL led to a
  device that silently stayed signed out. It is now detached with `nohup` and
  reparented to init.
- **Failure is now reported.** If no URL can be produced, the endpoint returns
  `ok:false` with the command's own output, and the app shows it instead of
  reporting a sign-in that never happened.

### The daemon switch disagreed with reality

"Run tailscaled" was driven by `backend == "Running"`, so an unauthenticated or
starting daemon rendered as *stopped* while it was plainly alive. It now follows
the module's own `daemon` field. The Send button keeps requiring a signed-in
node, because that is when peers are actually reachable.

### No more internal names

The header showed Tailscale's raw backend enums -- "NeedsLogin", "NoState".
Those are now "Not signed in", "Connecting…", "Stopped". The placeholder
hostname `localhost` is hidden until you are actually signed in.

### Root permission gets a real screen

Missing root was a line of text above an otherwise normal screen. It is now a
full-screen gate: large lock, the message centred, and one obvious action that
opens your **root manager** -- Android's app settings is not where superuser
permission lives, so sending you there was pointless. The nav bar is hidden
while it is up, because nothing behind it is reachable.

### Uninstalled cleanly

`uninstall.sh` left TailBridge installed: a launcher icon with no daemon behind
it that could only ever say "grant root permission". Removing the module now
removes the app too.

### Dropped the refresh button

It was redundant next to the five-second poll, and redundant controls that do
nothing visible are worse than no control at all.

## v1.0.1

### The module is no longer confused with the original

`module.prop` carried `id=magisk-tailscaled`, which is the same id as
[anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled).
Magisk and KernelSU key modules by id, so the two are the same module as far as
the manager is concerned — which is why managers kept offering "update" for a
module that was already current.

The id is now `paras-tailscaled`, and `TS_MOD_DIR` follows it.

**Migration:** if you had the old id installed, remove it before flashing this
one. Both modules drive `/data/adb/tailscale`, and running them together will
fight over the daemon. Your login survives — `tailscaled.state` lives in
`/data/adb/tailscale`, which `uninstall.sh` does not touch on a module update,
and is preserved across installs either way.

### The update feed advertised a stale build

`update.json` still said `v2.1.0.0` / `02010000` from the fork, so every manager
correctly concluded that an update was available — and would have installed the
old build. It is now generated from `module.prop` on each release, version and
versionCode included, and points at the `-full` zip.

### TailBridge

- **Live updates.** Renaming a device in the admin console now shows up within
  about five seconds instead of waiting for a manual refresh. Only the header
  and the name rows are repainted, so scrolling and in-progress taps survive.
- **Root is checked first.** On launch the app asks for root before doing
  anything else, so a missing grant reads as "grant root permission" rather than
  as a broken daemon.
- **Account in the app.** The tailnet you are signed in to is shown under
  System, with sign-in and sign-out. Sign-in opens the browser directly — there
  is no interstitial dialog, because Tailscale has no headless auth flow.
- **A real log viewer.** The daemon log was a plain message box. It is now
  monospace, colour-coded by severity, with the timestamp dimmed, a
  problems-only filter, copy and refresh.

## v2.1.0.0

### Tailscale binaries now track upstream

The bundled `tailscaled` is no longer frozen at whatever upstream shipped when
this module was released. Binaries are built from upstream Tailscale releases
by `builder/build-binaries.sh` and published as `v<version>-android` releases.

- Ships Tailscale **1.104.1** (was 1.90.6 in v2.0.0.1)
- `tailscale update` now works on-device — the Android client-update path is
  part of the port and points at our release feed
- `tailscaled.update` (new) schedules that check at boot and supervises it

### New: auto-update with rollback

```
tailscaled.update status     # current + latest, last check, backup
tailscaled.update check      # query (rate limited to once per interval)
tailscaled.update run        # update now if newer
tailscaled.update rollback   # restore the previous binary
```

- Runs from the boot service, once per `TS_UPDATE_INTERVAL_HOURS` (default 24)
- The download is done by the Go-based updater inside `tailscaled`, not by
  shell — a rooted phone has no other TLS-capable HTTP client (busybox `wget`
  is built without CA validation)
- Snapshots the outgoing binary first; if the daemon does not report a healthy
  backend after the update, it is restored automatically
- Opt out with `TS_UPDATE_AUTO=false` in `/data/adb/tailscale/settings.sh`

### Installer

- `customize.sh` verifies the downloaded binary actually executes on the device
  and reports its version, instead of failing later at boot
- Binaries come from `paras-moharle/tailscale-android-cli`

### Upstream-port maintenance

The Android port is now split into scripted transforms (asserted, one per edit)
plus `builder/patches/android-core.patch`, so an upstream refactor fails the
build loudly rather than silently producing a broken binary.

### Fixes found while testing on device

- The port no longer lets the binary create its own `tailscale` symlink.
  Upstream's `createTailscaleSymlink()` derives the link from
  `os.Executable()`, so once the link pointed at the `tailscaled.prev` left by
  a rollback, every later CLI call rewrote the same broken link — the CLI kept
  reporting (and updating from) the old version while the daemon ran the new
  one. The module now owns the link via `ensure_cli_symlink()`.
- `TS_BIN_DIR` was never defined in `settings.sh`, so anything using it
  resolved against `/`. It is defined now, and pinned defensively in the
  updater.

### Verified on device (OnePlus Nord 3, Android 15, KernelSU)

1.90.6 -> 1.104.1, state preserved, health warning gone, rollback and
auto-update both exercised end to end.
