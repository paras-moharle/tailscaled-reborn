#!/usr/bin/env bash
# Build the clipboard helper into a DEX.
#
#   builder/build-clip-dex.sh
#
# The helper is a small program that runs under `app_process` to read, write
# and watch the clipboard. It is compiled here rather than committed as a
# binary blob, but the result is kept in the tree so that a build without an
# Android SDK still has one to ship.
#
# The output is tiny -- about 7 KB -- because it holds two classes and a
# hand-written copy of one hidden interface.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/tailscale/clip/src"
out="$root/tailscale/clip/clip.dex"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT_VER="${BT_VER:-34.0.0}"
BT="$SDK/build-tools/$BT_VER"
JAR="$SDK/platforms/android-34/android.jar"

if [ ! -x "$BT/d8" ] || [ ! -f "$JAR" ]; then
  echo "!! no Android SDK (need build-tools/$BT_VER and platforms/android-34)" >&2
  echo "!!   SDK looked for: $SDK" >&2
  exit 1
fi

echo "==> compiling the clipboard helper"
find "$src" -name '*.java' > "$work/sources.txt"
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d "$work/classes" \
  @"$work/sources.txt"

mkdir -p "$work/dex"
find "$work/classes" -name '*.class' > "$work/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$work/dex" @"$work/classes.txt"

[ -f "$work/dex/classes.dex" ] || { echo "!! d8 produced no classes.dex" >&2; exit 1; }
install -m 0644 "$work/dex/classes.dex" "$out"
echo "==> $out ($(wc -c < "$out") bytes)"
