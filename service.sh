#!/system/bin/sh
#
# Start the Tailscale daemon at boot.
#
# This file must stay inside the module directory. It used to be moved to
# /data/adb/service.d, KernelSU's "general scripts" directory, so the manager
# app could switch the service on and off. That turned out to be a trap: the
# manager's toggle works by truncating the script to zero bytes and removing
# its execute permission, and a script in that state is skipped at boot with
# one line in the log that nobody reads. The phone then came up with no
# daemon at all and no complaint. KernelSU and Magisk both run a module's own
# service.sh, so there is never a reason to move it.

TS_SCRIPTS="/data/adb/tailscale/scripts"
TS_RUN_DIR="/data/adb/tailscale/run"

mkdir -p "$TS_RUN_DIR" 2>/dev/null

# Wait for the boot to complete -- but not forever. The property is compared
# for exactly "1", so a device that reports "01", or a boot where it never
# arrives, used to hang this loop with no daemon started and nothing in the
# log to say why. Five minutes is far longer than any real boot needs.
waited=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
	sleep 1
	waited=$((waited + 1))
	if [ "$waited" -ge 300 ]; then
		echo "$(date +'%H:%M:%S' 2>/dev/null) [Warning]: boot did not complete after 300s; starting anyway" \
			>>"${TS_RUN_DIR}/runs.log" 2>/dev/null
		break
	fi
done

# The network needs a moment after boot before the daemon can reach the
# control plane.
sleep 5

# Start the daemon, the Taildrop receiver and the supervisor that keeps them
# alive.
"$TS_SCRIPTS/start.sh"

# Keep the daemon binary current. This runs in the background: it can take
# minutes on a slow link, and the daemon must not wait for it. The updater
# has its own timeouts, its own health check and its own rollback.
( "$TS_SCRIPTS/tailscaled.update" run >>"/dev/null" 2>&1 & )
