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
#   - The store is written by the desk, the one process that holds the
#     keystore open and the only writer of the history this version has.
#     What the watcher does here is ask it and report how the asking went;
#     the fallback that writes the line itself runs only when the desk
#     cannot be reached at all.
#
# The watcher restarts the helper if it dies. The store's limits are the
# desk's to keep.
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
# Did this copy come from a peer?
#
# A peer that sends a clip sets the phone's clipboard, and the watcher
# sees that as a change like any other. Pushing it back would mean two
# devices pushing the same clip to each other for ever.
from_peer() {
	[ -f "$STORE" ] || return 1
	_last="$(tail -n 1 "$STORE" 2>/dev/null)"
	[ -n "$_last" ] || return 1
	[ "$(printf '%s' "$_last" | "${TS_BIN_DIR}/jq" -r '.d // 0' 2>/dev/null)" = "1" ]
}

# Push the copy to every peer that is online.
#
# This is what makes the sharing automatic: a copy made on the phone lands
# on the other machines' clipboards without anybody asking.
#
# The text is the one the watcher already has, so nothing is read from the
# clipboard again -- reading it means starting a program, and that takes a
# couple of seconds on this phone.
#
# The pushes run in the background. The clipboard service holds its lock
# while it tells everybody about a change, and a slow push in the middle of
# that holds it up for everybody.
share_clip() {
	_b64="$1"
	_src="$2"
	[ -n "$_b64" ] || return 0
	# It can be switched on, in the clipboard's own configuration -- and
	# it is off by default. Copying a password and having it pushed to
	# every machine before anybody has looked at it is the kind of
	# default that is only ever regretted once: sharing is something the
	# user turns on, and the setting from before this release is not
	# upgraded silently.
	[ "$(clip_conf share false)" = "true" ] || return 0
	# And it can be narrowed. A list of peers in the configuration -- the
	# names as tailscale knows them, comma separated -- turns a push to
	# everybody into a push to those machines only. An empty list means
	# every online peer: sharing was switched on explicitly.
	_wanted="$(clip_conf share_peers '')"
	# Not a copy that came from a peer: that would be two machines
	# pushing the same thing to each other for ever.
	from_peer && return 0
	# Nor one that came from the phone's own clipboard sharing. The
	# computer it came from already has it, and pushing it back is how a
	# loop starts.
	case "$_src" in
	*kdeconnect*) return 0 ;;
	esac
	_dir="${TS_DIR}/tmp"
	mkdir -p "$_dir" 2>/dev/null || return 0
	_now="$(date +%s 2>/dev/null || echo 0)"
	_peers="$("${TS_BIN_DIR}/tailscale" status --json 2>/dev/null |
		"${TS_BIN_DIR}/jq" -r '.Peer | to_entries[] | select(.value.Online == true) | .value.DNSName' 2>/dev/null)"
	[ -n "$_peers" ] || return 0
	for _peer in $_peers; do
		# The allowlist, when it lists anybody, is the whole of who may
		# receive. A machine not on it gets nothing, however online it
		# is. Names are compared with the trailing dots removed, because
		# tailscale spells the very same peer with one here and without
		# one there.
		if [ -n "$_wanted" ]; then
			_peer_name="$(printf '%s' "$_peer" | tr -d '.')"
			_hit=""
			for _one in $(printf '%s' "$_wanted" | tr ',' ' '); do
				[ -n "$_one" ] || continue
				case "$_peer_name" in
				"$(printf '%s' "$_one" | tr -d '.')") _hit=1 ;;
				esac
			done
			[ -n "$_hit" ] || continue
		fi
		# The name has to be clip-*.clip: the receiver on the other side
		# looks for that, and anything else is an ordinary file.
		_mk="$_dir/clip-${_now}-${_peer}.clip"
		{
			printf 'TBCLIP1\n'
			printf '{"t":%s,"s":%s}\n' "$_now" \
				"$("${TS_BIN_DIR}/jq" -Rn --arg s "$_src" '$s')"
			printf '%s' "$_b64"
		} > "$_mk" 2>/dev/null || continue
		# Sixty seconds to deliver, or not at all. A peer that is half
		# gone must not fill the background with pushes that will land
		# nowhere, holding the tmp directory hostage.
		(timeout 60 "${TS_BIN_DIR}/tailscale" file cp "$_mk" "${_peer}:" >/dev/null 2>&1
		 rm -f "$_mk" 2>/dev/null) &
	done
	return 0
}

append_entry() {
	_text="$1"
	_src="$2"
	_dir="${3:-0}"
	# After a busy desk the function calls itself once, having waited; this
	# marks the call that must not wait again.
	_first="${4:-first}"

	# The text is encrypted before it is written down, with a key that lives
	# in the device's hardware keystore. The key never leaves the secure
	# hardware, so what lands on disk is ciphertext: a copy of the storage is
	# a copy of something that cannot be read, not a list of passwords.
	#
	# The writing is the desk's job now. One call asks it to seal the copy
	# and record it, collapse it into the last line if the text repeats,
	# and bring the history back under its limits -- which used to be three
	# shell steps, run by three different programs against one file, each
	# stepping on the others. The desk is the single writer; the watcher
	# asks it and reports whether it worked.
	#
	# The sealed entry that comes back is the store's own line for this
	# copy. From the desk it is the v2 shape: the text and its source
	# inside the seal, the rest bound to it as authenticated data. Anything
	# without that shape is the one-shot helper's answer from when the
	# desk itself was down -- sealed, but not yet written down, and so the
	# shell writes it down below.
	# The source has to reach the desk whole. Sources carry spaces --
	# "this computer", app names with gaps in them -- and a command line
	# is split on whitespace, which used to hand the desk the first word
	# and eat the direction behind it. So the source goes as base64, or
	# as a bare dash when there is none, which the desk reads back whole.
	_s_b64="$(printf '%s' "${_src:-}" | "${TS_BIN_DIR}/jq" -Rrs '@base64' 2>/dev/null | tr -d '\n\r')"
	[ -n "$_s_b64" ] || _s_b64="-"
	_out="$(printf '%s' "$_text" | clip_crypto seal "$_s_b64" "$_dir" 2>/dev/null)"
	case "$_out" in
	err:*|'') : ;;
	*'"id":"'*)
		# The desk did the whole job. The answer is the line it wrote.
		# Nothing for the shell to do but tell the app.
		( am broadcast -a com.paras.tailbridge.action.CLIPBOARD_CHANGED \
			-p com.paras.tailbridge >/dev/null 2>&1 & ) || true
		return 0
		;;
	*)
		# The desk itself was down; the one-shot helper sealed the copy
		# and left the writing to whoever asked. The writing is still
		# what the watcher has always done: append at the end -- with the
		# one collapse into the last line that a repeated copy asks for,
		# because the desk's dedup is what it is not being given.
		_h="$(printf '%s' "$_out" | "${TS_BIN_DIR}/jq" -r '.h // ""' 2>/dev/null)"
		[ -n "$_h" ] || _out=""
		if [ -n "$_out" ]; then
			mkdir -p "$TS_DIR" 2>/dev/null
			_last_h=""
			if [ -f "$STORE" ]; then
				_last_h="$(tail -n 1 "$STORE" 2>/dev/null |
					"${TS_BIN_DIR}/jq" -r '.h // ""' 2>/dev/null)"
			fi
			if [ -n "$_last_h" ] && [ "$_last_h" = "$_h" ]; then
				# Repeated copy: replace the last line, so the
				# clipboard service's double report does not land
				# twice. Keep a peer identity if the line carried one.
				_out="$("${TS_BIN_DIR}/jq" -c -n --argjson o "$(tail -n 1 "$STORE" 2>/dev/null)" \
					--argjson n "$_out" 'if ($o.d == 1) then ($n | .d = 1 | .s = $o.s) else $n end' 2>/dev/null)" \
					|| _out="$(tail -n 1 "$STORE" 2>/dev/null)"
				_tmp="${STORE}.tmp.$$"
				if sed '$d' "$STORE" > "$_tmp" 2>/dev/null; then
					printf '%s\n' "$_out" >> "$_tmp" 2>/dev/null
					mv -f "$_tmp" "$STORE" 2>/dev/null || rm -f "$_tmp" 2>/dev/null
				else
					rm -f "$_tmp" 2>/dev/null
					printf '%s\n' "$_out" >> "$STORE" 2>/dev/null
				fi
			else
				printf '%s\n' "$_out" >> "$STORE" 2>/dev/null
			fi
			chmod 0600 "$STORE" 2>/dev/null
			( am broadcast -a com.paras.tailbridge.action.CLIPBOARD_CHANGED \
				-p com.paras.tailbridge >/dev/null 2>&1 & ) || true
			return 0
		fi
		;;
	esac

	#
	# The desk did not answer. Why it did not decides what happens.
	#
	case "$_out" in
	*"desk is busy"*)
		# Every seat taken. They are let go in milliseconds, so it is
		# worth waiting once rather than dropping a copy into the
		# void: wait a second, and ask again through this very
		# function, marking it as the one last try.
		if [ "$_first" = "first" ]; then
			sleep 1
			append_entry "$_text" "$_src" "$_dir" again
			return
		fi
		;;
	esac

	# A busy desk that stayed busy, or too large, or anything else the desk
	# said no to: the copy is not written down at all. Writing plain text
	# here, because the sealing path exists but said no, would be a step
	# backwards -- the history's promise is that it is ciphertext, and a
	# promise kept on every copy except the ones holding passwords is no
	# promise at all. The copy is dropped, loudly.
	echo "$(date +%s) [Warning]: clipboard desk refused a copy: ${_out:-no answer}" \
		>>"${TS_RUN_LOG_FILE}" 2>/dev/null
	return 1
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
	printf 'share=false\n' >> "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
	printf 'share_peers=\n' >> "${TS_CLIP_CONF:-${TS_DIR}/clipboard.conf}" 2>/dev/null
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

# A store that was written before there was any encryption is the desk's
# problem now, not the shell's. The desk migrates the whole file to the v2
# shape once, when it starts, with the single-writer lock held; a shell
# rewrite here would race that and, worse, would leave the desk's index of
# byte offsets pointing at a file it no longer describes. The old watcher
# did exactly that on every start, and only the desk's short runs kept it
# harmless.

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
			# And on to the other machines, without anybody asking.
			share_clip "$_b64" "$_src"
			;;
		esac
	done

	# The helper ended. It is meant to run until it is killed, so ending
	# means it died. Start it again.
	sleep 5
done
