#!/system/bin/sh
#
# `set -e` is on, and this file is *sourced* by every script in the module, so
# it applies to the sourcing shell too. That makes any command here that
# fails fatal: the script that sourced us dies before it has printed
# anything, which is the worst possible moment to fail silently. Every
# command below is therefore written to survive its own failure.
set -e

# Custom settings

# Set tailscale directory variables
TS_DIR="/data/adb/tailscale"
TS_BIN_DIR="${TS_DIR}/bin"
TS_SCRIPTS_DIR="${TS_SCRIPTS_DIR:-${TS_DIR}/scripts}"

# Set Magisk Tailscale module variables
# The module directory is named after `id` in module.prop, so it cannot be
# hardcoded: the id has changed over the life of this project, and a build
# installed under a different id then could not find its own module.prop --
# so the version read as empty and the disable file was never seen.
#
# customize.sh writes the real path here at install time. The fallback is the
# current id, for an installation that somehow predates that.
if [ -f "${TS_DIR}/module-dir" ]; then
	TS_MOD_DIR="$(cat "${TS_DIR}/module-dir" 2>/dev/null)"
fi
[ -n "${TS_MOD_DIR:-}" ] || TS_MOD_DIR="/data/adb/modules/paras-tailscaled"
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
	_have="$(cat "$TS_MODULE_STATE_FILE" 2>/dev/null || true)"
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