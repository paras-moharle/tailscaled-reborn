#!/system/bin/sh
DIR=$(dirname "$(realpath "$0")")
# shellcheck source=../settings.sh
. "$DIR"/../settings.sh
case "$1" in
    postinstall)
      rm -rf "$TS_RUN_DIR" && mkdir -p "$TS_RUN_DIR"
      tailscaled.service restart >> "/dev/null" 2>&1 &
      return 0
    ;;
esac
start_service() {
  if [ ! -f "${TS_MOD_DIR}/disable" ]; then
    ensure_cli_symlink
    tailscaled.service start >> "/dev/null" 2>&1
  fi
}
start_inotifyd() {
  for PID in $(busybox pidof inotifyd); do
    if grep -q "tailscaled.inotify" "/proc/$PID/cmdline"; then
      kill -9 "$PID"
    fi
  done
  echo "${CURRENT_TIME} [Info]: Starting tailscaled inotify service" > "${TS_RUN_LOG_FILE}"
  inotifyd "tailscaled.inotify" "${TS_MOD_DIR}" >> "/dev/null" 2>&1 &
}

# Taildrop is push on the sender and pull on the receiver, so nothing arrives
# on the phone unless something pulls it. Start that alongside the daemon:
# without it a file sent from a desktop sits in the inbox and the sender is
# told it worked.
start_receiver() {
  tailscaled.receive stop >/dev/null 2>&1
  tailscaled.receive run >> "/dev/null" 2>&1 &
}

# The supervisor. The boot script runs once; anything that kills the daemon
# after that left the phone with no Tailscale and no error to explain it.
# This is the thing that notices and puts it back.
#
# It is a separate script so that it can be found and killed by name, and it
# checks the `disable` file and the wanted state itself, so it never undoes
# a stop the user asked for.
start_keepalive() {
  for PID in $(busybox pidof keepalive.sh); do
    kill -9 "$PID" 2>/dev/null
  done
  nohup "${TS_SCRIPTS_DIR}/keepalive.sh" >> "/dev/null" 2>&1 &
}

module_version=$(busybox awk -F'=' '!/^ *#/ && /version=/ { print $2 }' "$TS_MOD_PROP" 2>/dev/null)
log Info "Tailscaled Reborn version : ${module_version}."
start_service
start_inotifyd
start_receiver
start_keepalive
