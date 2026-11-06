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
  else
    log Info "The module is disabled; not starting the daemon."
  fi
  return 0
}
start_inotifyd() {
  for PID in $(busybox pidof inotifyd); do
    if grep -q "tailscaled.inotify" "/proc/$PID/cmdline"; then
      kill -9 "$PID"
    fi
  done
  echo "${CURRENT_TIME} [Info]: Starting tailscaled inotify service" > "${TS_RUN_LOG_FILE}"
  inotifyd "tailscaled.inotify" "${TS_MOD_DIR}" >> "/dev/null" 2>&1 &
  return 0
}

# Taildrop is push on the sender and pull on the receiver, so nothing arrives
# on the phone unless something pulls it. Start that alongside the daemon:
# without it a file sent from a desktop sits in the inbox and the sender is
# told it worked.
start_receiver() {
  tailscaled.receive stop >/dev/null 2>&1
  tailscaled.receive run >> "/dev/null" 2>&1 &
  return 0
}

# The supervisor. The boot script runs once; anything that kills the daemon
# after that left the phone with no Tailscale and no error to explain it.
# This is the thing that notices and puts it back.
#
# It is a separate script so that it can be found and killed by name, and it
# checks the `disable` file and the wanted state itself, so it never undoes
# a stop the user asked for.
start_keepalive() {
  # The supervisor runs under /system/bin/sh, so its process name is "sh"
  # and pidof cannot find it. Match the full path instead.
  for PID in $(pgrep -f '/tailscale/scripts/keepalive.sh' 2>/dev/null); do
    [ "$PID" = "$$" ] && continue
    kill -9 "$PID" 2>/dev/null
  done
  nohup "${TS_SCRIPTS_DIR}/keepalive.sh" >> "/dev/null" 2>&1 &
  return 0
}

# The version is read, not needed. A read that fails must not stop the boot:
# this script is under `set -e`, and an empty version used to be enough to
# abort it -- which meant the daemon, the receiver and the supervisor were
# all never started, with nothing in any log to say so.
module_version=$(busybox awk -F'=' '!/^ *#/ && /version=/ { print $2 }' "$TS_MOD_PROP" 2>/dev/null || true)
[ -n "$module_version" ] || module_version="unknown"
log Info "Tailscaled Reborn version : ${module_version}."

# The scratch directory. Files waiting to be pushed carry copies of
# whatever was shared, so the directory holds them at root alone, and 0700
# for the directory itself so nobody can even list what is in it.
mkdir -p "${TS_DIR}/tmp" 2>/dev/null || true
chmod 0700 "${TS_DIR}/tmp" 2>/dev/null || true

# Logs grow without asking. The daemon's log went past a few megabytes on
# chatty days, and it is joined by the runs log, which the supervisor
# writes to every 30 seconds for as long as the phone is up. Both are cut
# here, once per boot: the last few thousand lines are kept, the rest is
# gone. It is not elegant, but it bounds a file that would otherwise grow
# for as long as the phone lives.
rotate_log() {
  _path="$1"
  _keep_lines="$2"
  _max_bytes="$3"
  [ -s "$_path" ] || return 0
  _bytes=$(wc -c < "$_path" 2>/dev/null || echo 0)
  case "$_bytes" in
    ''|*[!0-9]*) return 0 ;;
  esac
  [ "$_bytes" -le "$_max_bytes" ] && return 0
  tail -n "$_keep_lines" "$_path" > "$_path.tmp" 2>/dev/null && \
    mv -f "$_path.tmp" "$_path" 2>/dev/null
}
rotate_log "${TS_RUN_DIR}/tailscaled.log" 2000 5242880
rotate_log "${TS_RUN_LOG_FILE}" 2000 2097152

# Each step is independent. A failure in one must not stop the others: the
# receiver is what collects arriving files, and the supervisor is what
# notices a dead daemon, so both matter even if the daemon itself refuses
# to start.
start_service || log Error "The daemon did not start."
start_inotifyd || log Error "The inotify watcher did not start."
start_receiver || log Error "The Taildrop receiver did not start."
start_keepalive || log Error "The supervisor did not start."
