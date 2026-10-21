#!/system/bin/sh
#
# Supervisor for the daemon and the Taildrop receiver.
#
# The boot script runs once. Anything that killed the daemon after that left
# the phone with no Tailscale at all: no daemon, no receiver, and nothing
# anywhere saying so. The only symptom was the app reporting that the local
# socket was refused, which reads like a broken module rather than a process
# that is simply not running.
#
# This loop is the missing supervisor. It restarts the daemon if it dies, and
# the receiver with it, because the receiver is what collects files that
# arrive from a desktop -- and the daemon reports a send as successful the
# moment the file is handed over. A dead receiver therefore means sends that
# look fine and arrive nowhere.
#
# Two things are respected, because the user asked for them:
#
#   - the `disable` file in the module directory switches the daemon off;
#   - a daemon that was switched off on purpose (the app's toggle) is left
#     alone, which is why tailscaled.service records the wanted state.
#
# Started detached by start.sh, so it outlives the boot script. It is run
# without `set -e` on purpose: a failed `pgrep` is an ordinary answer, not a
# reason to end the loop.

DIR=$(dirname "$(realpath "$0")")
# shellcheck source=../settings.sh
. "$DIR"/../settings.sh

set +e

# How often to look. Short enough that a dead daemon is noticed quickly,
# long enough that the check costs nothing measurable.
INTERVAL=30

daemon_running() {
	# The name, not the command line: see the note in tailscaled.api. A
	# false match here means the supervisor thinks a dead daemon is alive
	# and never restarts it, which is the one thing it is for.
	busybox pgrep -x tailscaled >/dev/null 2>&1
}

receiver_running() {
	busybox pgrep -f 'tailscaled.receive' >/dev/null 2>&1
}

# What the user last asked for. "stopped" means the daemon was switched off
# deliberately, and the supervisor must not switch it back on.
wanted_state() {
	if [ -f "${TS_RUN_DIR}/daemon.wanted" ]; then
		cat "${TS_RUN_DIR}/daemon.wanted" 2>/dev/null
	else
		echo "running"
	fi
}

log_keepalive() {
	mkdir -p "${TS_RUN_DIR}" 2>/dev/null
	echo "${CURRENT_TIME} [${1}]: ${2}" >>"${TS_RUN_LOG_FILE}" 2>/dev/null
}

# The update check, every few hours.
#
# The boot script runs it once, and at boot the network is usually not up
# yet. The updater waits for the feed now, but a phone that stays offline
# for longer than that would still miss the check until its next boot.
# This is the second chance.
# How many of the supervisor's cycles make an hour, and how many hours
# between update checks.
_UPDATE_CHECK_HOURS="${TS_UPDATE_CHECK_EVERY_HOURS:-6}"
_CYCLES_PER_HOUR=$(( 3600 / INTERVAL ))
[ "$_CYCLES_PER_HOUR" -ge 1 ] || _CYCLES_PER_HOUR=1
_UPDATE_EVERY=$(( _CYCLES_PER_HOUR * _UPDATE_CHECK_HOURS ))
_since_update=0

while true; do
	sleep "$INTERVAL"

	_since_update=$((_since_update + 1))
	if [ "$_since_update" -ge "$_UPDATE_EVERY" ]; then
		_since_update=0
		log_keepalive "Info" "Checking for a newer daemon."
		"${TS_SCRIPTS_DIR}/tailscaled.update" run >>"${TS_RUN_LOG_FILE}" 2>&1 || true
	fi

	# The module's own off switch.
	if [ -f "${TS_MOD_DIR}/disable" ]; then
		ts_module_status stopped
		continue
	fi

	# Deliberately stopped by the user. Say so, rather than letting the
	# manager keep claiming the daemon is running.
	if [ "$(wanted_state)" != "running" ]; then
		ts_module_status stopped
		continue
	fi

	if ! daemon_running; then
		# Say so before trying to fix it. The root manager shows this state in
		# its module list, and a line that still said "running" while the
		# daemon was gone is worse than no line at all.
		ts_module_status stopped
		log_keepalive "Warning" "tailscaled was not running; starting it again."
		"${TS_SCRIPTS_DIR}/tailscaled.service" start >>"${TS_RUN_LOG_FILE}" 2>&1
		sleep 5
		# Say plainly whether it came back. A restart that silently fails is
		# the same invisible failure this whole script exists to prevent.
		if daemon_running; then
			ts_module_status running
			log_keepalive "Info" "tailscaled is running again."
			# Tell the app, if it is open: the daemon came back without
			# anybody asking it to, and the screen should say so.
			am broadcast -a com.paras.tailbridge.action.STATE_CHANGED \
				-p com.paras.tailbridge >/dev/null 2>&1 || true
		else
			ts_module_status stopped
			log_keepalive "Error" "tailscaled did not come back up."
		fi
	else
		ts_module_status running
	fi

	# The clipboard watcher.
	#
	# It is the thing that records what is copied, and it has to be running
	# for the history to be anything. The daemon and the receiver are not
	# enough: a phone whose daemon is switched off still records copies, and
	# a phone whose watcher has died records nothing at all, quietly.
	if ! busybox pgrep -f 'tailscale/scripts/clipwatch.sh' >/dev/null 2>&1; then
		log_keepalive "Warning" "The clipboard watcher is not running; starting it again."
		for _p in $(pgrep -f 'tailscale/scripts/clipwatch.sh' 2>/dev/null); do
			kill -9 "$_p" 2>/dev/null
		done
		nohup "${TS_SCRIPTS_DIR}/clipwatch.sh" >>"/dev/null" 2>&1 &
		sleep 2
		if busybox pgrep -f 'tailscale/scripts/clipwatch.sh' >/dev/null 2>&1; then
			log_keepalive "Info" "The clipboard watcher is running again."
		else
			log_keepalive "Error" "The clipboard watcher did not come back up."
		fi
	fi

	if ! receiver_running; then
		log_keepalive "Warning" "The Taildrop receiver was not running; starting it again."
		"${TS_SCRIPTS_DIR}/tailscaled.receive" stop >/dev/null 2>&1
		"${TS_SCRIPTS_DIR}/tailscaled.receive" run >>"${TS_RUN_LOG_FILE}" 2>&1 &
		sleep 2
		if receiver_running; then
			log_keepalive "Info" "The Taildrop receiver is running again."
		else
			log_keepalive "Error" "The Taildrop receiver did not come back up."
		fi
	fi
done
