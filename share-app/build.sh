#!/usr/bin/env bash
# build.sh — build the "Tailscale Share" companion APK.
#
# Deliberately avoids Gradle: the app has zero dependencies (no AndroidX, no
# libraries) so the raw SDK toolchain is enough. That keeps the build fast,
# hermetic and free of network access.
#
#   ./build.sh            -> share-app/build/TailBridge.apk
#
# The signing key is generated once and cached in share-app/keystore/. Keep it
# if you want updates to install over each other.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

# Pick the newest installed build-tools and platform. Check the directories
# first: `ls` under `set -e` used to abort with a bare "No such file or
# directory" that said nothing about what to install.
[ -d "$SDK/build-tools" ] || {
	echo "!! no build-tools under $SDK (set ANDROID_SDK_ROOT)" >&2
	exit 1
}
[ -d "$SDK/platforms" ] || {
	echo "!! no platforms under $SDK (set ANDROID_SDK_ROOT)" >&2
	exit 1
}
BT_VER="$(ls "$SDK/build-tools" | grep -E '^[0-9]+(\.[0-9]+)+$' | sort -V | tail -1)"
PLATFORM_VER="${2:-$(ls "$SDK/platforms" | grep -E '^android-[0-9]+$' | sed 's/android-//' | sort -n | tail -1)}"
[ -n "$BT_VER" ] || { echo "!! no versioned build-tools under $SDK/build-tools" >&2; exit 1; }
[ -n "$PLATFORM_VER" ] || { echo "!! no android-N platform under $SDK/platforms" >&2; exit 1; }
BT="$SDK/build-tools/$BT_VER"
JAR="$SDK/platforms/android-$PLATFORM_VER/android.jar"
# Compile against the newest installed platform, but target the SDK the app was
# written for. Targeting 35 turns on Android 15's forced edge-to-edge layout,
# which draws this custom UI under the status and navigation bars; CI builds
# with android-34, so pin it here too and keep local and release APKs identical.
TARGET_SDK="${TARGET_SDK:-34}"
case "$TARGET_SDK" in
'' | *[!0-9]*) echo "!! TARGET_SDK must be a number, got '$TARGET_SDK'" >&2; exit 1 ;;
esac

[ -d "$BT" ] || { echo "!! no build-tools in $SDK" >&2; exit 1; }
[ -f "$JAR" ] || { echo "!! no platform android-$PLATFORM_VER in $SDK" >&2; exit 1; }

echo "==> toolchain"
echo "    build-tools $BT_VER, platform android-$PLATFORM_VER, target $TARGET_SDK"

APK_NAME="TailBridge"
KS_DIR="$HERE/keystore"
KS="$KS_DIR/tailbridge.jks"
KS_PASS="tailbridge"
ALIAS="tailbridge"

# A key supplied from outside wins over the local one.
#
# This is what makes a release update the app in place. A build signs the app
# with a key generated on the spot, so two builds have different signatures
# and Android refuses to replace one with the other -- the symptom being a
# module flash that updates the daemon but leaves the old app installed.
#
# CI passes a key in through TAILBRIDGE_KEYSTORE, which keeps the key out of
# the repository. A local build without one uses the cached key, so a
# developer's own builds keep updating each other.
if [ -n "${TAILBRIDGE_KEYSTORE:-}" ]; then
	[ -f "${TAILBRIDGE_KEYSTORE}" ] || {
		echo "!! TAILBRIDGE_KEYSTORE is set but ${TAILBRIDGE_KEYSTORE} does not exist" >&2
		echo "!! refusing to fall back to a throwaway key: the update would not install over the old app" >&2
		exit 1
	}
	KS="${TAILBRIDGE_KEYSTORE}"
	echo "    using the key from TAILBRIDGE_KEYSTORE"
fi


# The signing key is deliberately NOT committed. Generate one on first build so
# a fresh clone produces a working APK with nothing secret in the repository.
if [ ! -f "$KS" ]; then
	echo "==> generating signing key"
	mkdir -p "$KS_DIR"
	keytool -genkeypair -v \
		-keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
		-alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
		-dname "CN=TailBridge, OU=Community, O=paras-moharle, C=IN" >/dev/null 2>&1 ||
		{ echo "    keytool failed"; exit 1; }
fi

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

# ---------------------------------------------------------------- resources
echo "==> compiling resources"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "==> linking resources"
# The manifest carries a placeholder version, because the app ships inside the
# module and must agree with it. Without this the installed app reports 1.0.0
# forever while the module is on v1.0.x, which makes "which version am I
# running?" unanswerable.
MOD_VERSION="$(sed -n 's/^version=//p' "$HERE/../module.prop" 2>/dev/null | head -1)"
[ -n "$MOD_VERSION" ] || MOD_VERSION="0.0.0"
MOD_VERSION_CODE="$(sed -n 's/^versionCode=//p' "$HERE/../module.prop" 2>/dev/null | head -1)"
[ -n "$MOD_VERSION_CODE" ] || MOD_VERSION_CODE="1"

MANIFEST="$OUT/AndroidManifest.xml"
sed -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$MOD_VERSION\"/" \
	-e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$MOD_VERSION_CODE\"/" \
	"$HERE/AndroidManifest.xml" > "$MANIFEST"
echo "    version $MOD_VERSION ($MOD_VERSION_CODE)"

"$BT/aapt2" link \
	-o "$OUT/unsigned-unaligned.apk" \
	-I "$JAR" \
	--manifest "$MANIFEST" \
	--min-sdk-version 26 \
	--target-sdk-version "$TARGET_SDK" \
	-R "$OUT/res.zip" \
	--java "$OUT/gen" \
	--auto-add-overlay

# ------------------------------------------------------------------- code
echo "==> compiling java"
find "$HERE/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# --release rather than -source/-target/-bootclasspath: JDK 12+ rejects the
# old bootclasspath form. android.jar still supplies every android.* and
# org.json.* class the app uses.
javac -nowarn \
	--release 11 \
	-classpath "$JAR" \
	-d "$OUT/classes" \
	@"$OUT/sources.txt"

echo "==> dexing"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
[ -s "$OUT/classes.txt" ] || { echo "!! javac produced no classes" >&2; exit 1; }
"$BT/d8" --release --min-api 26 --lib "$JAR" \
	--output "$OUT/dex" \
	@"$OUT/classes.txt"

[ -f "$OUT/dex/classes.dex" ] || { echo "!! d8 produced no classes.dex" >&2; exit 1; }

# ------------------------------------------------------------------ package
echo "==> packaging"
cp "$OUT/unsigned-unaligned.apk" "$OUT/$APK_NAME-unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/$APK_NAME-unsigned.apk" classes.dex )

echo "==> aligning"
"$BT/zipalign" -f -p 4 "$OUT/$APK_NAME-unsigned.apk" "$OUT/$APK_NAME-aligned.apk"

# ------------------------------------------------------------------ signing
# The key is guaranteed to exist here: it was generated above, before any
# compilation. This block used to repeat that generation with a different
# -dname, so a build that somehow reached it would have produced an APK
# signed with a different key than the cached one -- exactly the update
# failure the comments above describe.
echo "==> signing"
"$BT/apksigner" sign \
	--ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
	--ks-key-alias "$ALIAS" \
	--min-sdk-version 26 \
	--out "$OUT/$APK_NAME.apk" \
	"$OUT/$APK_NAME-aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME.apk" >/dev/null \
	&& echo "    signature OK"

echo
echo "==> built $OUT/$APK_NAME.apk ($(du -h "$OUT/$APK_NAME.apk" | cut -f1))"
echo "    install with: adb install -r $OUT/$APK_NAME.apk"