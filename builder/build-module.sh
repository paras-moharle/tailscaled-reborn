#!/usr/bin/env bash
# Build the installable module zip.
#
#   builder/build-module.sh            slim zip (installer downloads tailscaled)
#   builder/build-module.sh --full     also bundle the daemon + jq, for offline installs
#
# The app APK is always bundled and installed by customize.sh, so a flash gives
# you a working interface with no second download.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
dist="$root/dist"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

FULL=0
[ "${1:-}" = "--full" ] && FULL=1

version="$(sed -n 's/^version=//p' "$root/module.prop")"
name="Tailscaled-Reborn-${version}"
arch="arm64"

say() { printf '%s\n' "$*"; }

# --- app ---------------------------------------------------------------------
# Always rebuild, and then check the APK agrees with module.prop.
#
# This used to build only when the APK was absent, so a stale one was packaged
# without a word. Because build.sh stamps the version in from module.prop, an
# APK built before a version bump reports the *previous* version: v1.0.21 was
# released carrying an app that called itself v1.0.20. Nobody noticed for a
# release because nothing compared the two.
apk="$root/share-app/build/TailBridge.apk"
say "==> building TailBridge"
"$root/share-app/build.sh" >/dev/null
[ -f "$apk" ] || { say "!! app APK missing"; exit 1; }

mod_version="$(sed -n 's/^version=//p' "$root/module.prop" | head -1)"
apk_version=""
if command -v aapt >/dev/null 2>&1; then
	apk_version="$(aapt dump badging "$apk" 2>/dev/null |
		sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
elif command -v aapt2 >/dev/null 2>&1; then
	apk_version="$(aapt2 dump badging "$apk" 2>/dev/null |
		sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
fi
if [ -n "$apk_version" ] && [ "$apk_version" != "$mod_version" ]; then
	say "!! the app reports $apk_version but module.prop says $mod_version"
	say "!! rebuild share-app/build.sh after changing the version, then retry"
	exit 1
fi

# --- stage -------------------------------------------------------------------
say "==> building the clipboard helper"
# The clipboard helper is a small program that runs under app_process to read
# and watch the clipboard. It is built here when the Android SDK is present.
# Without one, the committed copy is used -- but the check below refuses to
# ship a copy that is older than its sources, because a stale helper would
# fail in a way that looks like the clipboard is broken.
if ! "$root/builder/build-clip-dex.sh" >/dev/null 2>&1; then
	say "    !! no Android SDK; using the committed helper"
	[ -f "$root/tailscale/clip/clip.dex" ] || fail "tailscale/clip/clip.dex is missing and cannot be built"
	_newest_src="$(find "$root/tailscale/clip/src" -name '*.java' -newer "$root/tailscale/clip/clip.dex" 2>/dev/null | head -1)"
	[ -z "$_newest_src" ] || fail "tailscale/clip/clip.dex is older than its sources; build it with builder/build-clip-dex.sh"
fi

say "==> staging"
mkdir -p "$stage/app" "$stage/tailscale/scripts" "$stage/tailscale/clip" "$stage/META-INF/com/google/android"
cp "$root/module.prop" "$root/customize.sh" "$root/service.sh" \
   "$root/uninstall.sh" "$root/LICENSE" "$root/THIRD-PARTY-NOTICES.md" "$stage/"
cp -r "$root/META-INF/com/google/android/." "$stage/META-INF/com/google/android/"
cp "$root/tailscale/settings.sh" "$stage/tailscale/"
cp "$root/tailscale/scripts/"*.sh "$root/tailscale/scripts/tailscaled.inotify" \
   "$root/tailscale/scripts/tailscaled.service" "$root/tailscale/scripts/tailscaled.api" \
   "$root/tailscale/scripts/tailscaled.update" "$root/tailscale/scripts/tailscaled.receive" \
   "$root/tailscale/scripts/keepalive.sh" \
   "$stage/tailscale/scripts/" 2>/dev/null || \
cp "$root/tailscale/scripts/"* "$stage/tailscale/scripts/"
cp "$root/tailscale/clip/clip.dex" "$stage/tailscale/clip/"
cp "$apk" "$stage/app/TailBridge.apk"

# --- sanity: never ship a package that cannot install -----------------------
chmod +x "$stage/tailscale/scripts/"*
fail() { say "!! $1"; exit 1; }

# Everything Magisk/KernelSU runs at install time or boot must parse, or the
# device silently ends up with a half-installed module.
for f in "$stage"/*.sh "$stage/tailscale/settings.sh" "$stage/tailscale/scripts/"*; do
	[ -f "$f" ] || continue
	sh -n "$f" 2>/dev/null || fail "shell syntax error in ${f#$stage/}"
done

# A stray CR turns every line into a syntax error on the device. Only text
# files are checked; the APK is binary and will of course match.
crlf=$(find "$stage" -type f \
	\( -name '*.sh' -o -name '*.prop' -o -name '*.json' -o -name '*.md' -o -name 'LICENSE' \) \
	-exec grep -lU $'\r' {} + 2>/dev/null || true)
[ -z "$crlf" ] || fail "CRLF line endings in: $(echo "$crlf" | tr '\n' ' ')"

# The WebUI was removed; make sure nothing brings it back.
[ -e "$stage/webroot" ] && fail "webroot/ must not ship"
[ -e "$stage/action.sh" ] && fail "action.sh must not ship (it only existed for the WebUI)"

for req in module.prop customize.sh service.sh uninstall.sh \
	   META-INF/com/google/android/update-binary \
	   META-INF/com/google/android/updater-script \
	   tailscale/settings.sh app/TailBridge.apk \
	   tailscale/scripts/keepalive.sh \
	   tailscale/scripts/tailscaled.service \
	   tailscale/scripts/tailscaled.receive; do
	[ -e "$stage/$req" ] || fail "missing $req"
done

# The boot script is the only thing that starts the daemon when the phone
# boots. An empty or missing one means a phone with no Tailscale and no
# complaint, which is exactly what happened when a broken install shipped a
# zero-byte copy of it.
[ -s "$stage/service.sh" ] || fail "service.sh is empty"
[ -s "$stage/tailscale/scripts/keepalive.sh" ] || fail "keepalive.sh is empty"

# The installer must not move the boot script into KernelSU's general
# scripts directory: the manager app can disable a script there, and a
# disabled one is skipped at boot with nothing to show for it.
if grep -q 'service.d.*tailscaled_service\|tailscaled_service.sh' "$stage/customize.sh"; then
	grep -q 'rm -f "$SERVICE_DIR/tailscaled_service.sh"' "$stage/customize.sh" ||
		fail "customize.sh still installs into the general scripts directory"
fi

# The clipboard helper. Without it the clipboard commands fail at run time,
# and the symptom is a refused socket rather than a missing file.
[ -f "$stage/tailscale/clip/clip.dex" ] || fail "missing tailscale/clip/clip.dex"
[ -s "$stage/tailscale/clip/clip.dex" ] || fail "tailscale/clip/clip.dex is empty"

# The APK is the only interface this fork has, so an empty one is a broken zip.
[ -s "$stage/app/TailBridge.apk" ] || fail "app APK is empty"
unzip -l "$stage/app/TailBridge.apk" | grep -q 'classes.dex' || fail "app APK has no classes.dex"

say "    checks passed"

# Magisk and KernelSU execute uninstall.sh as a program, not through sh, so a
# non-executable one fails with permission denied: the module is removed and
# everything it installed -- including the TailBridge app -- stays behind.
chmod +x "$stage"/*.sh

say "==> zipping"
mkdir -p "$dist"
( cd "$stage" && zip -qr9 "$dist/${name}.zip" . -x '.*' )

if [ "$FULL" = 1 ]; then
	# The slim zip above must stay slim: the installer downloads tailscaled and
	# jq from the release feed so a version bump does not need a new module.
	#
	# Binaries may already be in tailscale/bin (CI downloads them there first)
	# or in builder/seed-bin (local builds).
	seed=""
	for cand in "$root/tailscale/bin" "$here/seed-bin"; do
		[ -n "$(find "$cand" -maxdepth 1 -name 'tailscaled*' 2>/dev/null)" ] && { seed="$cand"; break; }
	done
	[ -n "$seed" ] || { say "!! --full needs tailscaled + jq in tailscale/bin or builder/seed-bin"; exit 1; }
	mkdir -p "$stage/tailscale/bin"
	cp "$seed/tailscaled-${arch}" "$stage/tailscale/bin/tailscaled-${arch}" 2>/dev/null ||
		cp "$seed/tailscaled" "$stage/tailscale/bin/tailscaled-${arch}"
	cp "$seed/jq-${arch}" "$stage/tailscale/bin/jq-${arch}" 2>/dev/null ||
		cp "$seed/jq" "$stage/tailscale/bin/jq-${arch}"
	[ -s "$stage/tailscale/bin/tailscaled-${arch}" ] || fail "tailscaled missing from $seed"
	[ -s "$stage/tailscale/bin/jq-${arch}" ] || fail "jq missing from $seed"
	( cd "$stage" && zip -qr9 "$dist/${name}-full.zip" . -x '.*' )
fi

for z in "$dist/${name}.zip" "$dist/${name}-full.zip"; do
	[ -f "$z" ] || continue
	say ""
	say "==> $(basename "$z")  ($(du -h "$z" | cut -f1))"
	unzip -l "$z" | grep -E 'module.prop|TailBridge|customize.sh|settings.sh|bin/' || true
done
say ""
say "Done."