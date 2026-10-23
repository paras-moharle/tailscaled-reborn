#!/system/bin/sh
#
# `set -e` is on, and this file is *sourced* by every script in the module, so
# it applies to the sourcing shell too. That makes any command here that
# fails fatal: the script that sourced us dies before it has printed
# anything, which is the worst possible moment to fail silently. Every
# command below is therefore written to survive its own failure.
set -e

# Files this module writes are root's alone until something proves
# otherwise. Every file created after this line inherits this PERMISSION:
# 077 removes group and other, so a log, a store, a token file or a
# temporary clip lands at 0600 and nothing readable-but-rootless can open
# it. The old install ran at the caller's umask, which was 0666/0722
# depending on who sourced us, which is how a clipboard store readable by
# every app on the phone happened in the first place.
umask 077

# Custom settings

# Set tailscale directory variables
TS_DIR="/data/adb/tailscale"
TS_BIN_DIR="${TS_DIR}/bin"
TS_SCRIPTS_DIR="${TS_SCRIPTS_DIR:-${TS_DIR}/scripts}"

# Set Magisk Tailscale module variables
# The module directory is named after `id` in module.prop, so it cannot be
# hardcoded: the id has changed over the life of this project, and a build
# installed under a different id then could not find its own module.prop.
#
# The installer writes the path down, but it has to write the path the module
# will LIVE at, not the one it is unpacked into: the manager unpacks a module
# into modules_update/ and only moves it to modules/ once the install is
# finished. Recording the temporary path meant that every later read of
# module.prop looked at a directory that no longer existed -- and because
# those reads happen under `set -e`, a failing read took the whole boot
# script down with it. The daemon, the receiver and the supervisor all
# failed to start, silently, on a phone that had been working the day before.
#
# So: trust the recorded path only if module.prop is actually there, and
# otherwise go looking for the module.
TS_MOD_DIR=""
if [ -f "${TS_DIR}/module-dir" ]; then
	TS_MOD_DIR="$(cat "${TS_DIR}/module-dir" 2>/dev/null)"
fi
if [ -n "$TS_MOD_DIR" ] && [ ! -f "${TS_MOD_DIR}/module.prop" ]; then
	TS_MOD_DIR=""
fi
if [ -z "$TS_MOD_DIR" ]; then
	# Find it by the one thing every copy of this module has: the update
	# feed in its module.prop.
	for _prop in /data/adb/modules/*/module.prop; do
		[ -f "$_prop" ] || continue
		grep -q 'tailscaled-reborn' "$_prop" 2>/dev/null || continue
		TS_MOD_DIR="${_prop%/module.prop}"
		break
	done
fi
[ -n "$TS_MOD_DIR" ] || TS_MOD_DIR="/data/adb/modules/paras-tailscaled"
export TS_MOD_DIR
export TS_MOD_PROP="${TS_MOD_DIR}/module.prop"

# Where "what version is current?" is answered from. This is a release asset
# behind GitHub's /releases/latest/ redirect, not the GitHub API:
# unauthenticated api.github.com allows only 60 requests/hour per IP, and
# phones sit behind CGNAT where a whole carrier can share one address, so an
# API-based check gets 403'd for hours at a time.
# The feed is a file on the default branch, not /releases/latest/download/:
# this repo publishes BOTH module releases and (for historical builds) daemon binaries
# (v1.105.0-android), so "latest" would flip between the two and break the
# check roughly half the time. One pinned URL cannot do that.
export TS_LATEST_URL="${TS_LATEST_URL:-https://raw.githubusercontent.com/paras-moharle/tailscaled-reborn/main/tailscale-latest.json}"

# Set path environment for busybox & other.
export PATH="${TS_DIR}/bin:${TS_DIR}/scripts:/data/adb/magisk:/data/adb/ksu/bin:$PATH:/system/bin:${TS_MOD_DIR}/system/bin"
export HOME="/data/adb/tailscale/" # Because tailscaled will write log to $HOME

# tailscaled inherits TMPDIR from whoever started it. Start it once from an app
# and it keeps pointing at that app's cache directory forever — and when that
# app is later uninstalled the path vanishes, incoming Taildrop files fail to
# stage, and every send reports success while nothing is ever written.
# Pin it inside the module so the daemon is independent of its launcher.
export TMPDIR="${TS_DIR}/tmp"
# Not fatal if it fails: a missing TMPDIR is reported by the daemon
# itself, which is a better place for the news than a silent exit here.
mkdir -p "$TMPDIR" 2>/dev/null || true

# Set tailscaled & tailscale configuration
export TS_DAEMON_CMD="tailscaled -no-logs-no-support"

# Set tailscaled directory variables
export TS_RUN_DIR="${TS_DIR}/run"

# Set tailscaled log variables
export TS_LOG_FILE="${TS_RUN_DIR}/tailscaled.log"
export TS_RUN_LOG_FILE="${TS_RUN_DIR}/runs.log"

# The tailscale CLI. Defined here rather than only in tailscaled.api so every
# script that sources settings.sh can reach it -- an undefined TS_CLI expands to
# nothing and turns "tailscale" into a no-op that silently succeeds.
export TS_CLI="${TS_BIN_DIR}/tailscale"

# Where Taildrop deliveries are placed so they show up in the Files app.
export TS_TAILDROP_DIR="${TS_TAILDROP_DIR:-/sdcard/Download/Tailscale}"

# ---------------------------------------------------------------------------
# Clipboard helper
# ---------------------------------------------------------------------------
#
# The helper is a small program that runs under app_process to read, write and
# watch the clipboard. It has to run as the shell uid, and the clipboard
# service checks that the calling process owns the package it claims to be --
# so the helper always claims com.android.shell, which the shell uid owns.
#
# Two things about where it lives:
#
#   - It cannot live under /data/adb. That directory is mode 0700, so the
#     shell uid cannot even traverse it, and the helper would die with a
#     ClassNotFoundException that says nothing about permissions.
#
#   - The copy the helper loads must not be writable by the caller. ART
#     refuses to load a dex file the caller could modify, and the failure is
#     an abort with no message.
#
# So the canonical copy lives with the rest of the module, and a read-only
# copy is placed where the shell uid can read it. ensure_clip_dex() puts it
# back if it goes missing.
# The clipboard's own settings: what to record, how much to keep, and which
# apps to leave alone. A plain key=value file next to the store, because the
# watcher is a separate process from the app and both have to agree on it.
TS_CLIP_CONF="${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}"
TS_CLIP_DEX="${TS_DIR}/clip/clip.dex"
TS_CLIP_DEX_RUN="${TS_CLIP_DEX_RUN:-/data/local/tmp/tailbridge-clip.dex}"
TS_CLIP_CLASS="com.paras.tailbridge.clip.ClipBoard"
TS_CLIP_STORE="${TS_CLIP_STORE:-${TS_DIR}/clipboard.jsonl}"

# The crypto helper, and how to run it.
#
# The key is in the device's hardware keystore, so the history on disk is
# ciphertext that a copy of the storage cannot read. The helper has to run as
# root, because that is whose keystore namespace the key belongs to, and
# because it is the one that can read the store.
TS_CLIP_CRYPTO_CLASS="com.paras.tailbridge.clip.ClipCrypto"

# clip_crypto <mode> [args...]
#
# Base64 in, base64 out, or a whole store in and out.
#
# The keystore keeps a separate set of keys for every user of the device,
# so the helper has to run as the same user every time: a history written
# with root's key cannot be read with the shell's. Which user works is
# asked once and remembered, in a file that is kept when the module is
# flashed, because a history that suddenly cannot be read is worse than no
# history at all.
#
# Root is tried first. Some devices will not generate a key for a process
# with no package behind it, and the shell uid is the fallback: it has its
# own namespace in the same keystore, and a key there is just as much a
# key that cannot be copied out of the hardware.
clip_crypto_who() {
	_who="$(cat "${TS_DIR}/clipboard.keyns" 2>/dev/null)"
	case "$_who" in
	root | shell) printf '%s' "$_who"; return 0 ;;
	esac
	_who=root
	if ! CLASSPATH="$TS_CLIP_DEX" app_process /system/bin "$TS_CLIP_CRYPTO_CLASS" probe >/dev/null 2>&1; then
		_who=shell
		# The shell uid cannot read /data/adb, so it uses the copy of
		# the helper that lives where the shell can reach it.
		_dex="$TS_CLIP_DEX_RUN"
		if ! su 2000 -c "CLASSPATH='$_dex' app_process /system/bin $TS_CLIP_CRYPTO_CLASS probe" >/dev/null 2>&1; then
			# Neither works. The keystore cannot be reached from
			# here, and the history will have to be kept in plain
			# text, which the watcher says out loud.
			printf ''
			return 1
		fi
	fi
	printf '%s' "$_who" > "${TS_DIR}/clipboard.keyns" 2>/dev/null
	printf '%s' "$_who"
}

clip_crypto() {
	# The text goes in on stdin and the answer comes out on stdout.
	_mode="$1"
	shift
	_cmd="$_mode"
	for _a in "$@"; do
		_cmd="$_cmd $_a"
	done
	_payload="$(cat 2>/dev/null)"

	# The desk.
	#
	# It is a program that stays running with the keystore open, so the
	# work takes a few tens of milliseconds. The alternative -- running the
	# helper on its own -- takes a couple of seconds on this phone, and
	# that is a couple of seconds on every copy.
	_port="$(cat "${TS_DIR}/clip.port" 2>/dev/null)"
	_token="$(cat "${TS_DIR}/clip.token" 2>/dev/null)"
	if [ -n "$_port" ] && [ -n "$_token" ]; then
		_out="$(printf '%s\n%s\n%s' "$_token" "$_cmd" "$_payload" |
			nc -w 15 127.0.0.1 "$_port" 2>/dev/null)"
		case "$_out" in
		'' | err:*) ;;
		*)
			printf '%s' "$_out"
			return 0
			;;
		esac
	fi

	# The helper on its own. It does the same work, slowly.
	[ -f "$TS_CLIP_DEX" ] || return 1
	_who="$(clip_crypto_who)"
	[ -n "$_who" ] || return 1
	if [ "$_who" = "root" ]; then
		printf '%s' "$_payload" | CLASSPATH="$TS_CLIP_DEX" app_process /system/bin \
			"$TS_CLIP_CRYPTO_CLASS" "$_mode" "$@" 2>/dev/null
	else
		printf '%s' "$_payload" | su 2000 -c "CLASSPATH='$TS_CLIP_DEX_RUN' app_process /system/bin \
			$TS_CLIP_CRYPTO_CLASS $_mode $*" 2>/dev/null
	fi
}

# clip_conf <key> [default]
#
# One setting from the clipboard's configuration file.
clip_conf() {
	_k="$1"
	_d="${2:-}"
	[ -f "$TS_CLIP_CONF" ] || { printf '%s' "$_d"; return 0; }
	_v="$(grep -m1 "^${_k}=" "$TS_CLIP_CONF" 2>/dev/null | cut -d= -f2-)"
	[ -n "$_v" ] || _v="$_d"
	printf '%s' "$_v"
}

# clip_conf_set <key> <value>
#
# The file is rewritten rather than edited in place, so a reader never sees a
# half-written line.
clip_conf_set() {
	_k="$1"
	_v="$2"
	mkdir -p "$TS_DIR" 2>/dev/null
	_tmp="${TS_CLIP_CONF}.tmp.$$"
	: > "$_tmp" 2>/dev/null
	if [ -f "$TS_CLIP_CONF" ]; then
		grep -v "^${_k}=" "$TS_CLIP_CONF" 2>/dev/null >> "$_tmp" 2>/dev/null
	fi
	printf '%s=%s\n' "$_k" "$_v" >> "$_tmp" 2>/dev/null
	chmod 0600 "$_tmp" 2>/dev/null
	mv -f "$_tmp" "$TS_CLIP_CONF" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
	return 0
}

ensure_clip_dex() {
	[ -f "$TS_CLIP_DEX" ] || return 1
	# Already there, already read-only for the caller, and already the same
	# file: nothing to do.
	#
	# The comparison is on the contents and not only on the permissions.
	# The copy in /data/local/tmp is the one that actually runs, and a
	# module update brings a new helper; leaving the old one in place
	# would mean running last month's helper for ever.
	if [ -f "$TS_CLIP_DEX_RUN" ]; then
		_perm="$(ls -l "$TS_CLIP_DEX_RUN" 2>/dev/null | cut -c1-10)"
		case "$_perm" in
		r--------r-------- | r--------r--r-- | r--r--r--r-- | r--r--r---)
			if cmp -s "$TS_CLIP_DEX" "$TS_CLIP_DEX_RUN" 2>/dev/null; then
				return 0
			fi
			;;
		esac
	fi
	# Replace it: a file the caller can write to would be refused by ART.
	rm -f "$TS_CLIP_DEX_RUN" 2>/dev/null
	cp "$TS_CLIP_DEX" "$TS_CLIP_DEX_RUN" 2>/dev/null || return 1
	chown 0:0 "$TS_CLIP_DEX_RUN" 2>/dev/null || true
	chmod 0444 "$TS_CLIP_DEX_RUN" 2>/dev/null || true
	[ -f "$TS_CLIP_DEX_RUN" ]
}

# ---------------------------------------------------------------------------
# Module status in the root manager
# ---------------------------------------------------------------------------
#
# The root manager shows a module's description line in its list, and this
# module puts the daemon's state there. That is worth doing -- it is the
# first place a person looks to see whether the daemon is alive -- but it
# used to be written only when the daemon was started or stopped.
#
# A daemon that was killed rather than stopped never produced a "stopped"
# message, so the root manager went on saying "running" for hours, on a
# phone that had no Tailscale at all. The line was not wrong when it was
# written; it was simply never revised.
#
# So the state is now re-checked rather than remembered. The description is
# rewritten only when the state actually changes, because module.prop lives
# on flash and rewriting it every few seconds is wear for nothing.
#
#   running  -> "✔ tailscaled service is running!!!"
#   stopped  -> "✘ tailscaled is not running"
TS_MODULE_STATE_FILE="${TS_RUN_DIR}/module.state"

ts_module_desc() {
	case "$1" in
	running) printf '[ ⏲ %s | ✔ tailscaled service is running!!! ] ' "$CURRENT_TIME" ;;
	stopped) printf '[ ⏲ %s | ✘ tailscaled is not running ] ' "$CURRENT_TIME" ;;
	*) return 1 ;;
	esac
}

# ts_module_status <running|stopped>
#
# Writes the state into module.prop, but only when it differs from the last
# state we wrote. A missing or unreadable module.prop is not fatal: the
# module still works, it just does not advertise itself in the manager.
ts_module_status() {
	_want="$1"

	# What the file actually says right now.
	#
	# Not what we last wrote: after a reboot the file and our memory of it
	# can both be stale -- the description says "running" from yesterday, and
	# the state file says "running" too, so comparing the two would agree
	# with a lie. The file is the only thing the root manager ever reads, so
	# the file is what has to be compared.
	_have="unknown"
	if [ -f "$TS_MOD_PROP" ]; then
		_line="$(sed -n 's/^description=\[\([^]]*\)\].*/\1/p' "$TS_MOD_PROP" 2>/dev/null | head -1)"
		case "$_line" in
		*'tailscaled service is running'*) _have=running ;;
		*'tailscaled is not running'*) _have=stopped ;;
		esac
	fi
	[ "$_have" = "$_want" ] && return 0

	mkdir -p "$TS_RUN_DIR" 2>/dev/null
	echo "$_want" > "$TS_MODULE_STATE_FILE" 2>/dev/null || true

	[ -f "$TS_MOD_PROP" ] || return 0
	_desc="$(ts_module_desc "$_want")" || return 0
	# The description carries a sentence of its own after the status, so the
	# status is replaced rather than the whole line.
	_tmp="$TS_MOD_PROP.ts.$$"
	# The status prefix is optional: a freshly installed module.prop has a
	# plain description and no prefix at all, and the first write has to put
	# one there. The delimiter is @ and the match stops at the first ], since
	# the description text itself contains a "|" and a "]", either of which
	# would break a naive substitution.
	if sed -E "s@^description=(\[[^]]*\][[:space:]]*)?@description=${_desc}@" \
		"$TS_MOD_PROP" > "$_tmp" 2>/dev/null; then
		mv -f "$_tmp" "$TS_MOD_PROP" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
	else
		rm -f "$_tmp" 2>/dev/null
	fi
	return 0
}

# ---------------------------------------------------------------------------
# CLI symlink
# ---------------------------------------------------------------------------
# The `tailscale` CLI is the same binary as `tailscaled`. Upstream's
# createTailscaleSymlink() tries to maintain this itself, but it derives the
# link from os.Executable(), and because we install atomically via rename the
# running daemon can end up resolving to a replaced inode. That has been
# observed leaving `tailscale -> tailscaled.prev`, which makes every
# `tailscale` invocation silently run the OLD binary (wrong version, wrong
# update feed). Repair it explicitly wherever we start or update the daemon.
ensure_cli_symlink() {
	[ -f "${TS_BIN_DIR}/tailscaled" ] || return 0
	[ "$(readlink "${TS_BIN_DIR}/tailscale" 2>/dev/null || true)" = "tailscaled" ] && return 0
	ln -sf "tailscaled" "${TS_BIN_DIR}/tailscale" 2>/dev/null || true
	return 0
}

# ts_fetch <url> — print a URL to stdout.
#
# A stock Android kernel has no standalone wget/curl; busybox provides one.
# IMPORTANT: this busybox build does NOT validate TLS certificates. Only use
# this for unauthenticated, non-sensitive reads such as the version feed.
# Never use it to download binaries — those go through the Go updater inside
# tailscaled, which does verify certificates.
ts_fetch() {
	if command -v curl >/dev/null 2>&1; then
		timeout 30 curl -fsSL "$1" 2>/dev/null
	elif command -v wget >/dev/null 2>&1; then
		timeout 30 wget -qO- "$1" 2>/dev/null
	elif command -v busybox >/dev/null 2>&1; then
		timeout 30 busybox wget -qO- "$1" 2>/dev/null
	else
		return 1
	fi
}

# latest_version — newest published Android build, or empty if unreachable.
latest_version() {
	feed_field '.version // empty'
}

# When the current feed entry was published, as a unix timestamp.
#
# An epoch integer rather than ISO-8601 on purpose: Android's toybox date has no
# `-d`, so parsing a formatted timestamp would fail on the one machine that
# needs it. An absent or empty field means "settled", so an older feed file can
# never block updates.
# The feed, fetched once per operation.
#
# Everything below reads FEED_JSON rather than calling the network again. The
# decision "is there an update" and the install "fetch this exact release" must
# be made from the same bytes: two fetches can disagree, and the second can
# simply fail, which on the device surfaced as an update that was decided on
# and then not installed.
FEED_JSON=""

fetch_feed() {
	[ -n "$FEED_JSON" ] && return 0
	FEED_JSON="$(ts_fetch "$TS_LATEST_URL" 2>/dev/null || true)"
	[ -n "$FEED_JSON" ]
}

feed_field() {
	fetch_feed || return 1
	printf '%s' "$FEED_JSON" | "${TS_BIN_DIR}/jq" -r "$1" 2>/dev/null
}

# The release the feed names, e.g. v1.104.1-android.
#
# The tag and the repository are both read from the feed rather than assumed.
# A repo that publishes both module zips and daemon builds makes
# /releases/latest ambiguous, which is exactly what broke the installer.
latest_release_tag() {
	feed_field '.tag // empty'
}

# Which repository that release lives in. Absent means this repo.
latest_repo() {
	feed_field '.repo // empty'
}

latest_published_epoch() {
	feed_field '.publishedAtEpoch // empty'
}

# Take the current time
CURRENT_TIME=$(date +"%I:%M %P" 2>/dev/null || echo "--:-- --")

# ---------------------------------------------------------------------------
# Auto-update settings
# ---------------------------------------------------------------------------
# The tailscaled binary is static and never updates itself, so without this it
# drifts behind upstream and Tailscale's own health check nags about it.
# Set TS_UPDATE_AUTO=false in this file to opt out entirely.
# Where `tailscale update` fetches the v<version>-android release from. This
# must be the repo the build workflow actually publishes to.
export TS_UPDATE_REPO="${TS_UPDATE_REPO:-paras-moharle/tailscaled-reborn}"
export TS_UPDATE_AUTO="${TS_UPDATE_AUTO:-true}"
export TS_UPDATE_INTERVAL_HOURS="${TS_UPDATE_INTERVAL_HOURS:-24}"

# A freshly published build is held back for this long before phones are offered
# it. The check interval is not the risk; the timing is. Without a window, a bad
# upstream release reaches every phone within minutes of our CI finishing, which
# is too fast to notice and withdraw. Rollback still protects each device, but
# it is better not to roll the whole fleet back in the first place.
export TS_UPDATE_SETTLE_HOURS="${TS_UPDATE_SETTLE_HOURS:-6}"

# Coloring
normal="\033[0m"
orange="\033[1;38;5;208m"
red="\033[1;31m"
green="\033[1;32m"
yellow="\033[1;33m"
blue="\033[1;34m"

# Logs function
log() {
  # Selects the text color according to the parameters
  case $1 in
  Info) color="${blue}" ;;
  Success) color="${green}" ;;
  Error) color="${red}" ;;
  Warning) color="${yellow}" ;;
  Debug) color="${orange}" ;;
  *) color="${normal}" ;;
  esac
  # Add messages to time and parameters
  message="${CURRENT_TIME} [$1]: $2"
  if [ -z "$color" ]; then
    echo "${CURRENT_TIME} [Debug]: $1 $2" >>${TS_RUN_LOG_FILE} 2>&1 || true
    return
  fi
  if [ -t 1 ]; then
    # Prints messages to the console
    echo "${color}${message}${normal}" || true
    echo "${message}" >>${TS_RUN_LOG_FILE} 2>&1 || true
  else
    # Print messages to a log file
    echo "${message}" >>${TS_RUN_LOG_FILE} 2>&1 || true
  fi
}

[ -n "${DEBUG:-}" ] && set -u && set -x && PS4='+ ${0##*/}:${LINENO}: ' || true