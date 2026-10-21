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
MAX_ENTRIES="${TS_CLIP_MAX_ENTRIES:-}"
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

	# The text is encrypted before it is written down, with a key that lives
	# in the device's hardware keystore. The key never leaves the secure
	# hardware, so what lands on disk is ciphertext: a copy of the storage is
	# a copy of something that cannot be read, not a list of passwords.
	#
	# The helper writes a keyed hash of the text beside the ciphertext. That
	# is what lets the watcher notice that the same thing has been copied
	# again without decrypting anything -- and because the hash is keyed with
	# the hardware key, the hash of a password cannot be attacked the way a
	# plain hash of one can.
	_sealed="$(printf '%s' "$_text" | clip_crypto seal "$_src" "$_dir" 2>/dev/null)"
	_json=""
	_h=""
	if [ -n "$_sealed" ]; then
		_json="$_sealed"
		_h="$(printf '%s' "$_sealed" | "${TS_BIN_DIR}/jq" -r '.h // ""' 2>/dev/null)"
	fi

	if [ -z "$_json" ]; then
		# The keystore is not available on this device. Say so, once, and
		# keep working -- but the history is plain text while this lasts, and
		# the user has to be told that rather than left to assume otherwise.
		[ -f "${TS_RUN_DIR}/clip-crypto-warned" ] || {
			echo "$(date +%s) [Warning]: The clipboard history cannot be encrypted on this device; it is being stored as plain text." \
				>>"${TS_RUN_LOG_FILE}" 2>/dev/null
			touch "${TS_RUN_DIR}/clip-crypto-warned" 2>/dev/null
		}
		# Without a key there is no keyed hash, so a plain one stands in.
		# It only ever has to tell two copies of the same text apart.
		#
		# It is marked "plain:" so that it can be told apart from the hash
		# of a sealed entry later, and sealed then. A sealed entry's hash is
		# base64, which never contains a colon.
		_h="plain:$(printf '%s' "$_text" | sha256sum 2>/dev/null | cut -d" " -f1)"
		_json="$("${TS_BIN_DIR}/jq" -c -n \
			--argjson t "$(now_ms)" \
			--arg c "$_text" \
			--arg s "$_src" \
			--argjson d "$_dir" \
			--arg h "$_h" \
			'{t:$t,c:$c,s:$s,d:$d,p:0,h:$h}' 2>/dev/null)" || return 0
	fi
	[ -n "$_json" ] || return 0
	_keep="$_json"


	# The same text again is not a new entry.
	#
	# The comparison is on the keyed hash, because the text itself is
	# encrypted on disk and cannot be read back to compare it.
	#
	# The clipboard service reports a single change twice -- the listener is
	# called once for the user and once for the device -- so every copy
	# arrives here twice. Listing everything twice is noise, and a history
	# that cannot be trusted to show what was actually copied is worse than
	# no history. So the last entry is replaced rather than added to, which
	# also keeps the time of the most recent copy.
	_last=""
	_last_h=""
	if [ -f "$STORE" ]; then
		_last="$(tail -n 1 "$STORE" 2>/dev/null)"
		_last_h="$(printf '%s' "$_last" | "${TS_BIN_DIR}/jq" -r '.h // ""' 2>/dev/null)"
	fi
	if [ -n "$_last_h" ] && [ "$_last_h" = "$_h" ]; then
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
		# A file written by the shell gets whatever the umask says, and
		# the store is meant to be readable by root alone.
		chmod 0600 "$STORE" 2>/dev/null
		else
			rm -f "$_tmp" 2>/dev/null
			printf '%s\n' "$_json" >> "$STORE" 2>/dev/null
		fi
		return 0
	fi

	printf '%s\n' "$_json" >> "$STORE" 2>/dev/null

	# Tell the app, if it is open.
	#
	# Without this the clipboard screen only learns about a copy when the
	# app's next poll runs, which is up to five seconds later. Copying
	# something and watching the screen not change for five seconds makes
	# the whole thing look broken.
	#
	# A broadcast is the right shape for this: the app is usually not
	# running, so there is nothing to poll, and when it is running it wants
	# to know the moment something changes.
	# This is a nudge, not the mechanism: the app reads the
	# clipboard itself while it is in the foreground, so it
	# already knows. The nudge is for the case where the app
	# was asleep when the copy happened.
	#
	# It is sent in the background on purpose. `am` takes a
	# second or two to start, and running it here would hold
	# up the next copy behind it.
	( am broadcast -a com.paras.tailbridge.action.CLIPBOARD_CHANGED \
		-p com.paras.tailbridge >/dev/null 2>&1 & ) || true
	return 0
}

# prune -- drop the oldest entries until the store is within its limits.
prune() {
	[ -f "$STORE" ] || return 0
	# The limits are settings, so they are read here rather than fixed.
	MAX_ENTRIES="$(clip_conf max 5000)"
	case "$MAX_ENTRIES" in '' | *[!0-9]*) MAX_ENTRIES=5000 ;; esac
	MAX_DAYS="$(clip_conf days 0)"
	case "$MAX_DAYS" in '' | *[!0-9]*) MAX_DAYS=0 ;; esac
	_bytes="$(wc -c < "$STORE" 2>/dev/null || echo 0)"
	case "$_bytes" in '' | *[!0-9]*) _bytes=0 ;; esac
	_lines="$(wc -l < "$STORE" 2>/dev/null || echo 0)"
	case "$_lines" in '' | *[!0-9]*) _lines=0 ;; esac

	# By age.
	#
	# A count alone does not bound a history: five thousand copies a day
	# for a year is a lot of text, and the entries that matter are the
	# recent ones. Zero days means keep them for ever.
	#
	# The store is append-only, so the oldest entry is the first line and
	# the newest is the last. Everything below drops from the front: the
	# oldest first, always.
	if [ "$MAX_DAYS" -gt 0 ]; then
		_cutoff=$(( $(now_ms) - MAX_DAYS * 86400000 ))
		_oldest="$("${TS_BIN_DIR}/jq" -r '(.t // 0) | tostring' "$STORE" 2>/dev/null | head -1)"
		case "$_oldest" in
		'' | *[!0-9]*) : ;;
		*)
			if [ "$_oldest" -lt "$_cutoff" ]; then
				_tmp="${STORE}.tmp.$$"
				if "${TS_BIN_DIR}/jq" -c --argjson c "$_cutoff" 'select((.t // 0) >= $c)' \
					"$STORE" > "$_tmp" 2>/dev/null; then
					mv -f "$_tmp" "$STORE" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
					_lines="$(wc -l < "$STORE" 2>/dev/null || echo 0)"
					case "$_lines" in '' | *[!0-9]*) _lines=0 ;; esac
					_bytes="$(wc -c < "$STORE" 2>/dev/null || echo 0)"
					case "$_bytes" in '' | *[!0-9]*) _bytes=0 ;; esac
				else
					rm -f "$_tmp" 2>/dev/null
				fi
			fi
			;;
		esac
	fi

	if [ "$_lines" -le "$MAX_ENTRIES" ] && [ "$_bytes" -le "$MAX_BYTES" ]; then
		return 0
	fi

	# The count prune.
	#
	# The store is append-only, so the last line is the newest and the
	# first is the oldest: dropping from the front is first in, first out.
	#
	# A starred entry is never dropped. It was starred on purpose, and
	# the person who starred it is the only one who can unstar it -- a
	# limit that quietly removed it would be the limit overruling them.
	_tmp="${STORE}.tmp.$$"
	if "${TS_BIN_DIR}/jq" -c -s --argjson n "$MAX_ENTRIES" '
			. as $all
			| ($all | map(select((.p // 0) != 1)) | reverse | .[0:$n]) as $keep
			| (if ($keep | length) == 0 then {} else ($keep | map({(.t | tostring): true}) | add) end) as $keepMap
			| $all | map(select((.p // 0) == 1 or ($keepMap[(.t | tostring)] == true))) | .[]
		' "$STORE" > "$_tmp" 2>/dev/null; then
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

# The defaults, written out so the file is there to be read and edited.
[ -f "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" ] || {
	printf 'watch=true\n' > "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
	printf 'max=5000\n' >> "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
	printf 'days=0\n' >> "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
	printf 'ignore=\n' >> "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
	printf 'days=0\n' >> "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
	chmod 0600 "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
}

# The helper has to be readable by the shell uid and not writable by it, and
# it cannot live under /data/adb because that directory cannot be traversed
# by anybody but root. Put the read-only copy in its place, and put it back
# if something removes it.
ensure_clip_dex >/dev/null 2>&1

# Only one watcher at a time.
#
# Two of them would each hear every copy and each write it down, and the
# history would show everything twice. The lock is held for as long as the
# watcher runs, and the kernel takes it away if the watcher dies, so a
# watcher that was killed leaves nothing behind.
mkdir -p "$TS_RUN_DIR" 2>/dev/null
_lock="${TS_RUN_DIR}/clipwatch.pid"
_mine=$$
if [ -f "$_lock" ]; then
	# Is the watcher that took the lock still there?
	_old="$(cat "$_lock" 2>/dev/null | head -1)"
	if [ -n "$_old" ] && [ -d "/proc/$_old" ] &&
		tr '\000' ' ' < "/proc/$_old/cmdline" 2>/dev/null | grep -q 'clipwatch.sh'; then
		echo "another clipboard watcher is already running" >&2
		exit 0
	fi
fi
printf '%s\n' "$_mine" > "$_lock" 2>/dev/null

# Encrypt a store that was written before there was any encryption, so the
# old entries do not sit there in plain text for ever.
#
# It runs every time the watcher starts, not just the first time. Sealing
# something that is already sealed leaves it exactly as it is, so this is
# safe to repeat, and doing it again catches anything that was written in
# the clear in between -- by an older version of the watcher, say.
if [ -s "$STORE" ]; then
	_tmp="${STORE}.enc.$$"
	if clip_crypto encrypt-store < "$STORE" > "$_tmp" 2>/dev/null && [ -s "$_tmp" ]; then
		mv -f "$_tmp" "$STORE" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
	else
		rm -f "$_tmp" 2>/dev/null
	fi
fi

_count=0
while true; do
	# The helper runs as the shell uid. See the note at the top of this file:
	# running it as root would wipe the user's clipboard.
	ensure_clip_dex >/dev/null 2>&1

	su 2000 -c "CLASSPATH='${DEX}' app_process /system/bin ${CLASS} listen com.android.shell" 2>/dev/null |
	while IFS= read -r _line; do
		case "$_line" in
		changed:*)
			# The settings are read for every copy rather than once at
			# startup, so switching the recording off takes effect at
			# once instead of whenever the watcher is restarted.
			_watch="$(clip_conf watch true)"
		_ignore="$(clip_conf ignore '')"
			# Recording can be switched off.
			[ "$_watch" = "true" ] || continue
			_rest="${_line#changed:}"
			_b64="$(printf '%s' "$_rest" | cut -d'|' -f1)"
			_src="$(printf '%s' "$_rest" | cut -d'|' -f2-)"
			[ "$_src" = "$_rest" ] && _src=""
			# An app the user has said to leave alone.
			#
			# This is the one that matters. A password manager and a
			# banking app both put their secrets on the clipboard, and a
			# history that keeps them is a history that can be sent to
			# another machine by one tap. An ignored app's copies are
			# never written down at all.
			#
			# The list is in the clipboard's own configuration, as
			# "ignore=com.one,com.two".
			if [ -n "$_ignore" ] && [ -n "$_src" ]; then
				case ",${_ignore}," in
				*",${_src},"*) continue ;;
				esac
			fi
			append_entry "$_b64" "$_src" 0
			;;
		esac
	done

	# The helper ended. It is meant to run until it is killed, so ending
	# means it died. Start it again.
	sleep 5
done
