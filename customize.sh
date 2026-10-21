#!/system/bin/sh

# DEBUG=1
[ -n "${DEBUG:-}" ] && {
	PS4="+ \${0##*/}:\${LINENO}: "
	set -e
	set -u
	set -x
	set
} || true

# GitHub download helpers.
#
# These deliberately do NOT use api.github.com. The unauthenticated API allows
# only 60 requests/hour per IP, and module installs are exactly when that limit
# is most likely to be exhausted (everyone installing from the same CGNAT
# range). /releases/latest/download/<asset> is a plain 302 redirect with no
# rate limit.
GH_LATEST="https://github.com"

# fetch_latest_version <repo> — newest published Android build version.
fetch_latest_version() {
	_REPO="$1"
	wget --no-check-certificate --timeout=20 -qO- \
		"${GH_LATEST}/${_REPO}/releases/latest/download/latest.json" |
		grep -oE '"version"[[:space:]]*:[[:space:]]*"[^"]+"' |
		cut -d'"' -f4 |
		head -1
}

# gh_download <repo> <filename> — fetch a named asset from the latest release.
gh_download() {
	_REPO="$1"
	_FILENAME="$2"
	_URL="${GH_LATEST}/${_REPO}/releases/latest/download/${_FILENAME}"
	ui_print "- Downloading $_FILENAME..."
	if ! wget --no-check-certificate --timeout=100 -qO "$TMPDIR/$_FILENAME" "$_URL"; then
		ui_print "! Download failed: $_URL"
		return 1
	fi
	if [ ! -s "$TMPDIR/$_FILENAME" ]; then
		ui_print "! Downloaded file was empty: $_URL"
		return 1
	fi
	FILENAME="$_FILENAME"
}

# shellcheck disable=SC2034
SKIPUNZIP=1
# shellcheck disable=SC2034
SKIPMOUNT=false

if [ "$BOOTMODE" != true ]; then
	ui_print "! Please install in Magisk Manager or KernelSU Manager"
	ui_print "! Install from recovery is NOT supported"
	abort "-----------------------------------------------------------"
elif [ "$KSU" = true ] && [ "$KSU_VER_CODE" -lt 10670 ]; then
	abort "error: Please update your KernelSU and KernelSU Manager"
fi

SERVICE_DIR="/data/adb/service.d"

# Release repo that hosts the Android tailscaled builds. These are built from
# upstream Tailscale with the Android port applied (see builder/ in this repo),
# so a fresh install gets a current binary instead of whatever the upstream
# module happened to bundle when it was released.
# The daemon builds are published as release assets of the archived
# tailscale-android-cli repo, which is where build-binaries.sh has always
# uploaded them.
#
# The on-device updater reads tailscale-latest.json, whose "repo" field names
# this repo (tailscaled-reborn). Both repos carry the same assets and the same
# SHA256SUMS for a given daemon tag, so the installer and the updater both
# fetch a byte-identical binary. They are separate repositories by history, not
# by design, but they are kept in step by tagging both.
TS_BIN_REPO="paras-moharle/tailscale-android-cli"

TS_DIR="/data/adb/tailscale"
TS_BIN_DIR="$TS_DIR/bin"
TS_SCRIPTS_DIR="$TS_DIR/scripts"

case $ARCH in
arm) F_ARCH="armv7a" ;;
arm64) F_ARCH="aarch64" ;;
*)
	ui_print "Unsupported architecture: $ARCH"
	abort
	;;
esac
ui_print "- Detected architecture: $ARCH"

if [ -d "$TS_DIR" ]; then
	[ -f "$TS_DIR/tmp/tailscaled.state" ] && mv -f "$TS_DIR/tmp/tailscaled.state" "$TS_DIR/tailscaled.state"
	ui_print "- Backup old files"
	PROP_FILE="$(echo "$MODPATH" | sed 's/_update//')/module.prop"
	[ ! -f "$PROP_FILE" ] && PROP_FILE="$MODPATH/module.prop"
	versionCode=$(grep '^versionCode=' "$PROP_FILE" | cut -d= -f2)
	# shellcheck disable=SC2154
	rm -rf "$TS_DIR/backups/$versionCode/" && mkdir -p "$TS_DIR/backups/$versionCode/"
	# Everything a new version replaces is set aside, so a flash does not
	# leave a half-written file behind. The clipboard's own files are not
	# that: they are history and settings, and a phone that was flashed
	# came back with an empty clipboard and a reset limit, because the
	# history had been moved into a backup nobody was looking at.
	for p in "$TS_DIR"/*; do
		case "$(basename "$p")" in
		ssh | certs | backups | tailscaled.state | clipboard.jsonl | clipboard.conf | clipboard.keyns) ;;
		*)
			mv -f -- "$p" "$TS_DIR/backups/$versionCode/"
			;;
		esac
	done
fi

mkdir -p "$TS_BIN_DIR"
unzip -qqjo "$ZIPFILE" "tailscale/bin/*-$ARCH" -d "$TS_BIN_DIR" 2>/dev/null || true
for f in "$TS_BIN_DIR"/*-"$ARCH"; do
	[ -f "$f" ] && mv "$f" "${f%-"$ARCH"}"
done

[ -f "$TS_BIN_DIR/tailscaled" ] || {
	TS_LATEST_VER="$(fetch_latest_version "$TS_BIN_REPO")"
	if [ -z "$TS_LATEST_VER" ]; then
		ui_print "! Could not read the release feed for $TS_BIN_REPO"
		ui_print "!   $GH_LATEST/$TS_BIN_REPO/releases/latest/download/latest.json"
		ui_print "! Install the -full.zip instead if you are offline or rate limited."
		abort "error: Unable to determine the latest tailscaled version."
	fi
	ui_print "- Latest published tailscaled: $TS_LATEST_VER"
	gh_download "$TS_BIN_REPO" "tailscale_${TS_LATEST_VER}_${ARCH}.tgz" ||
		abort "error: Unable to download tailscaled ${TS_LATEST_VER} (${ARCH})."
	tar -xzf "$TMPDIR/$FILENAME" -C "$TS_BIN_DIR" || abort "error: Unable extract archive."
	chmod 0755 "$TS_BIN_DIR/tailscaled"
}

# Fail early and loudly rather than shipping a module that cannot run: a binary
# that does not execute here will not execute on the device either.
if [ -f "$TS_BIN_DIR/tailscaled" ]; then
	chmod 0755 "$TS_BIN_DIR/tailscaled"
	if ! "$TS_BIN_DIR/tailscaled" --version >/dev/null 2>&1; then
		ui_print "! Downloaded tailscaled will not run on this device."
		ui_print "! Check that your kernel supports the required syscalls."
		abort "error: unusable tailscaled binary."
	fi
	ui_print "- tailscaled: $("$TS_BIN_DIR/tailscaled" --version 2>/dev/null | head -1)"
else
	abort "error: tailscaled binary missing after download."
fi

[ -f "$TS_BIN_DIR/jq" ] || {
	# jq is required by tailscaled.api and tailscaled.update.
	# Upstream only publishes a working aarch64 build; the armv7a asset it
	# lists 404s, so say so plainly instead of failing with a cryptic error.
	if [ "$ARCH" = "arm" ]; then
		ui_print "! jq has no working 32-bit ARM Android build upstream."
		ui_print "! This module therefore supports arm64 devices only."
		ui_print "! (32-bit Android is below 0.5% of active devices.)"
		abort "error: unsupported architecture arm."
	fi
	gh_download "theshoqanebi/jq-build-for-android" "jq-${F_ARCH}-linux-android" ||
		abort "error: Unable to download jq."
	mv -f "$TMPDIR/$FILENAME" "$TS_BIN_DIR/jq" || abort "error: Unable to move file."
	chmod 0755 "$TS_BIN_DIR/jq"
}

ui_print "- Extracting files..."
unzip -qqo "$ZIPFILE" -x 'META-INF/*' 'tailscale/*' -d "$MODPATH"

mkdir -p "$TS_DIR" "$TS_SCRIPTS_DIR" "$SERVICE_DIR" "$MODPATH/system/bin/"
unzip -qqjo "$ZIPFILE" 'tailscale/scripts/*' -d "$TS_SCRIPTS_DIR"
unzip -qqjo "$ZIPFILE" 'tailscale/settings.sh' -d "$TS_DIR"

# The clipboard helper.
#
# It is kept with the rest of the module, and a read-only copy is placed
# where the shell uid can read it: the helper has to run as the shell uid,
# because that is the only uid the clipboard service trusts without a prompt
# from the user, and it cannot reach anything under /data/adb -- that
# directory is mode 0700.
#
# The copy has to be read-only for the caller as well. ART refuses to load a
# dex file the caller could modify, and it does so by aborting with no
# message at all.
mkdir -p "$TS_DIR/clip"
unzip -qqjo "$ZIPFILE" 'tailscale/clip/clip.dex' -d "$TS_DIR/clip" 2>/dev/null || true
if [ -f "$TS_DIR/clip/clip.dex" ]; then
	chmod 0644 "$TS_DIR/clip/clip.dex"
	rm -f /data/local/tmp/tailbridge-clip.dex
	cp "$TS_DIR/clip/clip.dex" /data/local/tmp/tailbridge-clip.dex
	chown 0:0 /data/local/tmp/tailbridge-clip.dex
	chmod 0444 /data/local/tmp/tailbridge-clip.dex
fi
ln -sf "$TS_BIN_DIR/tailscaled" "$TS_BIN_DIR/tailscale"
ln -sf "$TS_BIN_DIR/"* "$MODPATH/system/bin/"

ln -sf "$TS_SCRIPTS_DIR/tailscaled.service" "$MODPATH/system/bin/"
ln -sf "$TS_SCRIPTS_DIR/tailscaled.update" "$MODPATH/system/bin/"

ui_print "- Setting permissions"
set_perm_recursive "$TS_BIN_DIR/" 0 0 0755 0755 "u:object_r:system_file:s0"
set_perm_recursive "$TS_SCRIPTS_DIR/" 0 0 0755 0755 "u:object_r:system_file:s0"
set_perm_recursive "$MODPATH/system/bin/" 0 0 0755 0755 "u:object_r:system_file:s0"
set_perm "$MODPATH/service.sh" 0 0 0755 "u:object_r:system_file:s0"

# uninstall.sh must be executable or the module manager cannot run it.
# KernelSU executes it directly rather than through `sh`, so a non-executable
# uninstall.sh fails with permission denied -- and the module is removed while
# everything it installed, including the app, stays behind.
set_perm "$MODPATH/uninstall.sh" 0 0 0755 "u:object_r:system_file:s0"

# The boot script stays inside the module directory.
#
# It used to be moved into /data/adb/service.d -- KernelSU's "general scripts"
# directory -- so the manager app could switch the service on and off. That
# turned out to be a trap, and it cost a phone its Tailscale for a day.
#
# The manager's toggle works by truncating the script to zero bytes and
# removing its execute permission. A script in that state is skipped at boot
# with a single line in the log that nobody reads:
#
#   ksud::module: /data/adb/service.d/tailscaled_service.sh is not executable, skip
#
# The phone then came up with no daemon and no complaint anywhere. The only
# symptom was the app reporting that the local socket was refused, which reads
# like a broken module rather than a boot script that was switched off.
#
# KernelSU and Magisk both run a module's own service.sh at boot -- the boot
# log shows it executing service.sh for every module that has one -- so
# keeping the script here is both safer and simpler.
ui_print "- Keeping the boot script inside the module."

# Remove a general script left behind by an earlier install. It is a copy of
# what used to live here, and while it is still executable it would start a
# second daemon alongside the module's own.
if [ -f "$SERVICE_DIR/tailscaled_service.sh" ]; then
	rm -f "$SERVICE_DIR/tailscaled_service.sh" 2>/dev/null || true
	ui_print "- Removed the old general script (it would start a second daemon)."
fi

# The boot script is the only thing that starts the daemon when the phone
# boots, so a missing or non-executable copy means a phone with no Tailscale
# and no way to tell why. Check it here, where someone is still watching,
# rather than finding out at the next reboot.
if [ ! -s "$MODPATH/service.sh" ]; then
	abort "error: the boot script did not install correctly."
fi
if [ ! -x "$MODPATH/service.sh" ]; then
	set_perm "$MODPATH/service.sh" 0 0 0755 "u:object_r:system_file:s0"
fi
if [ ! -x "$MODPATH/service.sh" ]; then
	abort "error: the boot script is not executable."
fi

# The module directory is named after the id in module.prop, so the scripts
# cannot know it in advance. Write it down for them: hardcoding it meant that
# a build installed under a different id could not find its own module.prop,
# and the version read as empty.
# The path the module will LIVE at, not $MODPATH.
#
# The manager unpacks a module into modules_update/<id> and moves it to
# modules/<id> only after the install has finished, so $MODPATH is a
# temporary path. Writing that down left every later read of module.prop
# pointing at a directory that no longer existed, and under `set -e` that
# took the boot script down with it -- so the daemon, the receiver and the
# supervisor silently failed to start.
_ts_id="$(sed -n 's/^id=//p' "$MODPATH/module.prop" 2>/dev/null | head -1)"
[ -n "$_ts_id" ] || _ts_id="paras-tailscaled"
echo "/data/adb/modules/${_ts_id}" > "$TS_DIR/module-dir" 2>/dev/null || true

ui_print "- Installing the TailBridge app..."
# The companion app is the only interface this fork ships: it provides Taildrop
# (there is no other way to send a file, because this module deliberately leaves
# the Android VPN slot free so the Play Store app cannot take it) plus every
# setting known to take effect. A failed install must not abort the module --
# the daemon is the part people cannot do without.
APP_APK="$TS_DIR/app/TailBridge.apk"
mkdir -p "${TS_DIR}/app"
unzip -qqjo "$ZIPFILE" 'app/TailBridge.apk' -d "${TS_DIR}/app" 2>/dev/null || true
if [ -f "$APP_APK" ]; then
	set_perm "$APP_APK" 0 0 0644
	if pm install -r -g "$APP_APK" >/dev/null 2>&1; then
		ui_print "  TailBridge installed."
	elif pm install -r -g -d "$APP_APK" >/dev/null 2>&1; then
		# Downgrading: the new build has a lower version code than the one
		# installed, and Android refuses that without -d.
		ui_print "  TailBridge installed (downgraded)."
	else
		# Almost always a signing mismatch.
		#
		# Every build signs the app with a key generated on the spot, so two
		# builds have different signatures and Android refuses to replace one
		# with the other. Left alone, flashing a new module would leave the
		# old app installed: the daemon updates, the app does not, and the
		# two disagree about what the module can do.
		#
		# The app holds nothing of its own -- the daemon keeps the login and
		# the settings live in the module -- so removing it and installing
		# again loses nothing, and it makes a flash do what it says.
		ui_print "  Replacing TailBridge (the new one is signed differently)."
		pm uninstall com.paras.tailbridge >/dev/null 2>&1
		if pm install -g "$APP_APK" >/dev/null 2>&1; then
			ui_print "  TailBridge installed."
		else
			ui_print "  Could not install TailBridge automatically."
			ui_print "  Install it later with:"
			ui_print "    pm install -r $APP_APK"
		fi
	fi
else
	ui_print "  APK missing from this package."
fi
ui_print ""
ui_print "  AFTER REBOOT: open TailBridge and grant it root permission --"
ui_print "  KernelSU Manager -> Superuser -> TailBridge -> allow."
ui_print "  Without it TailBridge cannot reach the daemon and will say so."

ui_print "- Starting service in background."
${TS_SCRIPTS_DIR}/start.sh postinstall 2>&1 &
if [ ! -f "/system/bin/tailscale" ] || ! cmp --silent "/system/bin/tailscale" "$MODPATH/system/bin/tailscale"; then
	ui_print "- Link file to /dev/."
	ln -sf "$TS_SCRIPTS_DIR/tailscaled.service" /dev/tailscaled.service
	ln -sf "$TS_BIN_DIR/tailscaled" /dev/tailscaled
	ln -sf "$TS_BIN_DIR/tailscaled" /dev/tailscale
	ui_print "-----------------------------------------------------------"
	ui_print " Instructions       "
	ui_print "-----------------------------------------------------------"
	ui_print "- If you not reboot, execute with /dev/tailscale or /dev/tailscaled.service."
	ui_print "- After reboot, you can use tailscale and tailscaled.service directly."
	if [ ! -f "$TS_DIR/tailscaled.state" ]; then
		ui_print "- Quickstart to new user :"
		ui_print "  su -c '/dev/tailscale login'"
		ui_print "  su -c '/dev/tailscaled.service status'"
		ui_print "- Read the README.md"
	else
		ui_print "- Tailscaled service manager :"
		ui_print "  su -c '/dev/tailscaled.service'"
	fi
else
	if [ ! -f "$TS_DIR/tailscaled.state" ]; then
		ui_print "- Quickstart to login :"
		ui_print "  su -c 'tailscale login'"
		ui_print "  su -c 'tailscaled.service status'"
		ui_print "- Read the README.md"
	else
		ui_print "- Tailscaled service manager :"
		ui_print "  su -c 'tailscaled.service'"
	fi
fi
[ -n "${DEBUG:-}" ] && { set +ue; } || true
