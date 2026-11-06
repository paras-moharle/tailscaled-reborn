#!/system/bin/sh
# Remove everything this module installed.
rm -rf /data/adb/tailscale

# Older versions of this module moved the boot script into KernelSU's
# "general scripts" directory. That copy is not ours any more -- the boot
# script lives in the module directory now -- but leaving a file behind in
# /data/adb/service.d means a script that runs after the module is gone.
# Remove it if it is there.
SERVICE_DIR="/data/adb/service.d"
if [ -f "$SERVICE_DIR/tailscaled_service.sh" ]; then
    rm -f "$SERVICE_DIR/tailscaled_service.sh"
fi

# The supervisor has to be stopped too, or it keeps trying to restart a
# daemon that is about to disappear.
# Matched on the full path, so a script of the user's that happens to be
# called keepalive.sh is not touched.
for PID in $(pgrep -f '/tailscale/scripts/keepalive.sh' 2>/dev/null); do
    kill -9 "$PID" 2>/dev/null
done

# Older versions ran a clipboard watcher and a keystore helper. They are
# not part of this build, but a phone that upgraded in place can still have
# one running, holding the socket and the keystore open. Stop them and drop
# the helper copy the shell uid could read.
for PID in $(pgrep -f '/tailscale/scripts/clipwatch.sh' 2>/dev/null); do
    kill -9 "$PID" 2>/dev/null
done
for PID in $(pgrep -f 'com.paras.tailbridge.clip.ClipStore' 2>/dev/null); do
    kill -9 "$PID" 2>/dev/null
done
rm -f /data/local/tmp/tailbridge-clip.dex 2>/dev/null

# TailBridge is installed as an ordinary user app by customize.sh, so removing
# the module used to leave it behind: a launcher icon with no daemon behind it,
# which can only ever say "grant root permission".
#
# Unconditionally, because deciding "is this the module that installed it?"
# means trusting $0, and uninstall.sh is not reliably invoked with its own path.
# If you are mid-migration between module ids, flash the new module afterwards
# and customize.sh will install the app again.
if pm list packages 2>/dev/null | grep -q "com.paras.tailbridge"; then
    pm uninstall com.paras.tailbridge
fi

exit 0
