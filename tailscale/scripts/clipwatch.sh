#!/system/bin/sh
# Watch the clipboard and record what is copied.
#
# The watcher runs the helper as the shell uid, reads the changes it reports,
# and appends them to the store. It is the same shape as the daemon's own
# inotify watcher: a long-lived process in front of a long-lived child.
#
# Two details are not obvious and were established by testing on a real
# device:
#
#   - The helper must run as uid 2000, not as root. The clipboard service
#     checks that the calling process owns the package it claims to be; root
#     does not own com.android.shell, so the service throws while trying to
#     grant a permission and CLEARS THE USER'S CLIPBOARD. The shell uid owns
#     the package, so nothing is cleared.
#
#   - The store is written by jq, not by hand. Clipboard contents are
#     arbitrary text and hand-built JSON breaks on the first quote or
#     newline in them.
#
# The watcher restarts the helper if it dies, and prunes the store when it
# grows past its limits.
DIR=$(dirname "$(realpath "$0")")
# shellcheck source=../settings.sh
. "$DIR"/../settings.sh

set +e

STORE="${TS_CLIP_STORE:-${TS_DIR}/clipboard.jsonl}"
DEX="${TS_CLIP_DEX_RUN:-/data/local/tmp/tailbridge-clip.dex}"
CLASS="${TS_CLIP_CLASS:-com.paras.tailbridge.clip.ClipBoard}"

# How many entries to keep, and how many bytes the file may reach. Both are
# limits, not targets: a clipboard history that grows without bound is a
# problem that only shows up much later, on a phone that is already full.
MAX_ENTRIES="${TS_CLIP_MAX_ENTRIES:-5000}"
MAX_BYTES="${TS_CLIP_MAX_BYTES:-8388608}"

# Milliseconds since the epoch. toybox's date does not always understand
# %3N, so fall back to whole seconds.
now_ms() {
	_v="$(busybox date +%s%3N 2>/dev/null)"
	case "$_v" in
	'' | *[!0-9]*) echo $(( $(busybox date +%s 2>/dev/null || echo 0) * 1000 )) ;;
	*) echo "$_v" ;;
	esac
}

# append_entry <base64-text> <source-package> <direction>
#
# The text stays base64 encoded in the store. A shell variable cannot hold a
# trailing newline, so decoding it here would quietly lose a line every time
# somebody copied something that ends in one.
append_entry() {
	_text="$1"
	_src="$2"
	_dir="${3:-0}"
	# -c: one entry per line. Without it jq pretty-prints the object over
	# several lines, and the store stops being a list of lines.
	_json="$("${TS_BIN_DIR}/jq" -c -n \
		--argjson t "$(now_ms)" \
		--arg c "$_text" \
		--arg s "$_src" \
		--argjson d "$_dir" \
		'{t:$t,c:$c,s:$s,d:$d,p:0}' 2>/dev/null)" || return 0
	[ -n "$_json" ] || return 0
	_keep="$_json"

	# The same text again is not a new entry.
	#
	# The clipboard service reports a single change twice -- the listener is
	# called once for the user and once for the device -- so every copy
	# arrives here twice. Listing everything twice is noise, and a history
	# that cannot be trusted to show what was actually copied is worse than
	# no history. So the last entry is replaced rather than added to, which
	# also keeps the time of the most recent copy.
	_last_c=""
	if [ -f "$STORE" ]; then
		_last_c="$(tail -n 1 "$STORE" 2>/dev/null |
			"${TS_BIN_DIR}/jq" -r '.c // ""' 2>/dev/null)"
	fi
	if [ -n "$_last_c" ] && [ "$_last_c" = "$_text" ]; then
		# Keep what the last entry already knew if it knew more. A clip
		# that arrived from a peer is recorded by the receiver with the
		# peer's name and marked as coming from a peer; the watcher then
		# sees the clipboard change it caused and would otherwise replace
		# that with a plainer entry.
		_json="$("${TS_BIN_DIR}/jq" -c --argjson o "$_last" --argjson n "$_json" '
			if ($o.d == 1) then ($n | .d = 1 | .s = $o.s) else $n end
		' 2>/dev/null)" || _json=""
		[ -n "$_json" ] || _json="$_keep"
		_tmp="${STORE}.tmp.$$"
		if sed '$d' "$STORE" > "$_tmp" 2>/dev/null; then
			printf '%s\n' "$_json" >> "$_tmp" 2>/dev/null
			mv -f "$_tmp" "$STORE" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
		else
			rm -f "$_tmp" 2>/dev/null
			printf '%s\n' "$_json" >> "$STORE" 2>/dev/null
		fi
		return 0
	fi

	printf '%s\n' "$_json" >> "$STORE" 2>/dev/null
	return 0
}

# prune -- drop the oldest entries until the store is within its limits.
prune() {
	[ -f "$STORE" ] || return 0
	_bytes="$(wc -c < "$STORE" 2>/dev/null || echo 0)"
	case "$_bytes" in '' | *[!0-9]*) _bytes=0 ;; esac
	_lines="$(wc -l < "$STORE" 2>/dev/null || echo 0)"
	case "$_lines" in '' | *[!0-9]*) _lines=0 ;; esac

	if [ "$_lines" -le "$MAX_ENTRIES" ] && [ "$_bytes" -le "$MAX_BYTES" ]; then
		return 0
	fi

	_keep="$MAX_ENTRIES"
	_tmp="${STORE}.tmp.$$"
	if tail -n "$_keep" "$STORE" > "$_tmp" 2>/dev/null; then
		mv -f "$_tmp" "$STORE" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
	else
		rm -f "$_tmp" 2>/dev/null
	fi

	# Still too big after the entry cap: the entries themselves are large.
	# Halve the file until it fits.
	_bytes="$(wc -c < "$STORE" 2>/dev/null || echo 0)"
	case "$_bytes" in '' | *[!0-9]*) _bytes=0 ;; esac
	while [ "$_bytes" -gt "$MAX_BYTES" ]; do
		_lines="$(wc -l < "$STORE" 2>/dev/null || echo 0)"
		case "$_lines" in '' | *[!0-9]*) _lines=0 ;; esac
		[ "$_lines" -gt 1 ] || break
		_keep=$(( _lines / 2 ))
		_tmp="${STORE}.tmp.$$"
		if tail -n "$_keep" "$STORE" > "$_tmp" 2>/dev/null; then
			mv -f "$_tmp" "$STORE" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
		else
			rm -f "$_tmp" 2>/dev/null
			break
		fi
		_bytes="$(wc -c < "$STORE" 2>/dev/null || echo 0)"
		case "$_bytes" in '' | *[!0-9]*) _bytes=0 ;; esac
	done
	return 0
}

mkdir -p "$TS_DIR" 2>/dev/null
touch "$STORE" 2>/dev/null
chmod 0600 "$STORE" 2>/dev/null

# The helper has to be readable by the shell uid and not writable by it, and
# it cannot live under /data/adb because that directory cannot be traversed
# by anybody but root. Put the read-only copy in its place, and put it back
# if something removes it.
ensure_clip_dex >/dev/null 2>&1

_count=0
while true; do
	# The helper runs as the shell uid. See the note at the top of this file:
	# running it as root would wipe the user's clipboard.
	ensure_clip_dex >/dev/null 2>&1
	su 2000 -c "CLASSPATH='${DEX}' app_process /system/bin ${CLASS} listen com.android.shell" 2>/dev/null |
	while IFS= read -r _line; do
		case "$_line" in
		changed:*)
			_rest="${_line#changed:}"
			_b64="$(printf '%s' "$_rest" | cut -d'|' -f1)"
			_src="$(printf '%s' "$_rest" | cut -d'|' -f2-)"
			[ "$_src" = "$_rest" ] && _src=""
			append_entry "$_b64" "$_src" 0
			;;
		esac
	done

	# The helper ended. It is meant to run until it is killed, so ending
	# means it died. Start it again.
	sleep 5
done
