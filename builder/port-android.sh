#!/usr/bin/env bash
# port-android.sh — turn an upstream Tailscale checkout into an Android-capable one.
#
# Design note
# -----------
# The Android support that this module relies on was written by the author of
# Magisk-Tailscaled against Tailscale 1.98.8. Since then upstream has refactored
# (notably ssh/tailssh was split into many files, linuxfw marks moved to tsconst,
# wlynxg/anet was dropped), so the original diff cannot be applied blindly.
#
# To survive that, the port is split in two:
#
#   1. TRANSFORMS  - small, single-line edits (almost all of them build-tag
#                    flips). Applied by string replacement with a hard
#                    assertion per entry, so an upstream change makes the build
#                    FAIL LOUDLY instead of silently producing a broken binary.
#
#   2. CORE PATCH  - new files and substantial code blocks. Applied with
#                    `git apply`; these rarely conflict because they are
#                    mostly additions.
#
# Usage: port-android.sh <path-to-tailscale-checkout>
set -euo pipefail

SRC="${1:?usage: port-android.sh <tailscale-checkout>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Release repo the in-binary `tailscale update` will pull from.
UPDATE_REPO="${TS_UPDATE_REPO:-paras-moharle/tailscaled-reborn}"

cd "$SRC"

FAILED=0
APPLIED=0

# replace <file> <exact-old> <exact-new>
# Exact literal replacement (never regex). Fails the build if the expected
# text is not found, so an upstream refactor can't silently produce a binary
# that builds but misbehaves on device.
replace() {
	local file="$1" old="$2" new="$3" rc=0
	python3 -c '
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    s = open(path, encoding="utf-8").read()
except FileNotFoundError:
    sys.exit(2)
if new in s and old not in s:
    sys.exit(0)          # already applied
if old not in s:
    sys.exit(1)
if s.count(old) != 1:
    print(f"  ! {path}: pattern is not unique ({s.count(old)} matches)", file=sys.stderr)
    sys.exit(3)
open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
' "$file" "$old" "$new" || rc=$?
	case "$rc" in
	0) APPLIED=$((APPLIED + 1)) ;;
	1) echo "  ✗ $file: expected text not found:" >&2
	   echo "      $old" >&2
	   FAILED=1 ;;
	2) echo "  ✗ $file: MISSING" >&2
	   FAILED=1 ;;
	3) echo "  ✗ $file: ambiguous pattern (see above)" >&2
	   FAILED=1 ;;
	*) echo "  ✗ $file: unexpected error (rc=$rc)" >&2
	   FAILED=1 ;;
	esac
}

echo "==> Transforms (build tags & one-liners)"

# --- ssh/tailssh: upstream excludes android from these files entirely.
# Enable the Linux implementations on Android.
replace ssh/tailssh/c2n.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build linux || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/hostkeys.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build linux || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/listen.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build linux || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/process_unix.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build linux || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/session.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build linux || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/sftp.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build linux || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/user_unix.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build linux || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/incubator_linux.go \
	'//go:build linux && !android' \
	'//go:build linux'
# 1.104.5 split ssh-monitor, its client, the forwarded child and the
# connection/session monitors into their own files, all behind the same
# non-Android guard. Android is a Linux userspace and runs them; without the
# tag flip incubator.go references symbols no file defines ("undefined:
# beMonitor") and the port stops building.
for _f in connmonitor fwdchild fwdmonitor monitor_client monitor monitor_rpc sessmonitor; do
	replace "ssh/tailssh/$_f.go" \
		'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd' \
		'//go:build linux || (darwin && !ios) || freebsd || openbsd'
done
replace ssh/tailssh/monitor_linux.go \
	'//go:build linux && !android' \
	'//go:build linux'
# these two already mention android separately, just add the term back
replace ssh/tailssh/tailssh.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build (linux && !android) || android || (darwin && !ios) || freebsd || openbsd || plan9'
replace ssh/tailssh/user.go \
	'//go:build (linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9' \
	'//go:build (linux && !android) || android || (darwin && !ios) || freebsd || openbsd || plan9'

# --- feature/ssh + version check formatting
replace feature/ssh/ssh.go \
	'//go:build ((linux && !android) || (darwin && !ios) || freebsd || openbsd || plan9) && !ts_omit_ssh' \
	'//go:build (linux || (darwin && !ios) || freebsd || openbsd || plan9) && !ts_omit_ssh'
replace version/version_checkformat.go \
	'//go:build tailscale_go && android' \
	'//go:build ignore'

# --- net/netmon: use the Linux interface poller, disable the Android stub
replace net/netmon/netmon_linux.go '//go:build !android' '//go:build linux'
replace net/netmon/interfaces_linux.go '//go:build !android' '//go:build linux'
replace net/netmon/netmon_polling.go \
	'//go:build (!linux && !freebsd && !windows && !darwin) || android' \
	'//go:build (!linux && !freebsd && !windows && !darwin)'
replace net/netmon/interfaces_android.go \
	'package netmon' \
	'//go:build ignore
package netmon'

# --- net/netns
replace net/netns/netns_android.go '//go:build android' '//go:build ignore'

# --- net/dns: Android has its own manager; keep the default one off Android.
replace net/dns/manager_default.go \
	'//go:build (!linux || android) && !freebsd && !openbsd && !windows && !darwin && !illumos && !solaris && !plan9' \
	'//go:build !linux && !android && !freebsd && !openbsd && !windows && !darwin && !illumos && !solaris && !plan9'
replace net/dns/resolvconfpath_default.go \
	'//go:build !gokrazy' \
	'//go:build !gokrazy && !android'

# --- ipn: cert/actor OS switches
replace ipn/localapi/cert.go \
	'//go:build !ios && !android && !js && !ts_omit_acme' \
	'//go:build !ios && !js && !ts_omit_acme'
replace ipn/localapi/disabled_stubs.go \
	'//go:build ios || android || js' \
	'//go:build ios || !android || js'
replace ipn/ipnserver/actor.go \
	'	case "darwin", "linux", "illumos", "solaris", "openbsd", "freebsd":' \
	'	case "darwin", "linux", "android", "illumos", "solaris", "openbsd", "freebsd":'

# --- cli / tstun / taildrop / envknob
replace cmd/tailscale/cli/set.go '	case "linux":' '	case "linux", "android":'
replace cmd/tailscale/cli/up.go '	case "linux":' '	case "linux","android":'
replace net/tstun/tun.go \
	'		if runtime.GOOS != "linux" {' \
	'		if runtime.GOOS != "linux" && runtime.GOOS != "android" {'
replace feature/taildrop/fileops_fs.go '//go:build !android
' ''
replace envknob/featureknob/featureknob.go \
	'func CanRunTailscaleSSH() error {
	switch runtime.GOOS {
	case "linux":' \
	'func CanRunTailscaleSSH() error {
	switch runtime.GOOS {
	case "android":
	case "linux":'

if [ "$FAILED" -ne 0 ]; then
	echo >&2
	echo "!! Transform step failed — upstream Tailscale changed shape." >&2
	echo "!! Fix the relevant entry in builder/port-android.sh and bump" >&2
	echo "!! builder/patches/android-core.patch accordingly." >&2
	exit 1
fi
echo "    $APPLIED transform(s) OK"

echo "==> Core patch"
if ! git apply --verbose "$HERE/patches/android-core.patch" 2>&1 | sed 's/^/    /'; then
	echo "!! Core patch failed to apply — resolve conflicts and regenerate:" >&2
	echo "   builder/patches/android-core.patch" >&2
	exit 1
fi

echo "==> Point the in-binary updater at $UPDATE_REPO"
if [ -f clientupdate/clientupdate_android.go ]; then
	# NB: the original constant ends in "/releases". updateAndroid() appends
	# "/latest" or "/tags/v<ver>-android" to it, so dropping that suffix makes
	# every request 404. Keep it.
	python3 -c '
import sys, re
p = "clientupdate/clientupdate_android.go"
s = open(p, encoding="utf-8").read()
new_repo = sys.argv[1]
s2, n = re.subn(
    r"const androidGitHubRepoURL = \"https://api\.github\.com/repos/[^\"]+\"",
    "const androidGitHubRepoURL = \"https://api.github.com/repos/" + new_repo + "/releases\"",
    s, count=1)
if n != 1:
    print("  ! androidGitHubRepoURL not found", file=sys.stderr)
    sys.exit(1)
if "/releases\"" not in s2:
    print("  ! rewritten URL lost its /releases suffix", file=sys.stderr)
    sys.exit(1)
open(p, "w", encoding="utf-8").write(s2)
' "$UPDATE_REPO" || exit 1
	grep -q "repos/${UPDATE_REPO}/releases\"" clientupdate/clientupdate_android.go || {
		echo "!! could not rewrite androidGitHubRepoURL correctly" >&2
		exit 1
	}
	echo "    ok -> https://api.github.com/repos/${UPDATE_REPO}/releases"
else
	echo "!! clientupdate/clientupdate_android.go missing — core patch incomplete" >&2
	exit 1
fi

echo
echo "==> Android port complete"

# ---------------------------------------------------------------------------
# Dependency note
# ---------------------------------------------------------------------------
# net/netmon/state.go (added by the core patch) enumerates interfaces through
# github.com/wlynxg/anet on Android, because Android restricts netlink for
# non-VPN apps. Upstream 1.104.1 no longer depends on that module at all, so we
# reintroduce it — pointed at the BieHDC fork that carries the Android fix
# (upstream wlynxg/anet v0.0.5 does not build on GOOS=android).
#
# Requires Go on PATH; builder/build-binaries.sh guarantees that.
ANET_VERSION="v0.0.5"
ANET_REPLACE="v0.0.6-0.20241226223613-d47f8b766b3c"
if ! command -v go >/dev/null 2>&1; then
	echo "!! go not on PATH — cannot restore the anet dependency." >&2
	echo "!! Use builder/build-binaries.sh instead of calling this directly." >&2
	exit 1
fi
echo "==> Restoring github.com/wlynxg/anet dependency"
go mod edit \
	-require="github.com/wlynxg/anet@${ANET_VERSION}" \
	-replace="github.com/wlynxg/anet=github.com/BieHDC/anet@${ANET_REPLACE}"
GOFLAGS=-mod=mod go mod download github.com/wlynxg/anet
grep -q 'BieHDC/anet' go.mod || {
	echo "!! failed to add anet replace directive" >&2
	exit 1
}
echo "    ok"