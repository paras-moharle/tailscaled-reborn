#!/usr/bin/env bash
# build.sh — package the flashable module zips.
#
#   Tailscaled-Reborn-<ver>.zip       downloads tailscaled at install time
#   Tailscaled-Reborn-<ver>-full.zip  embeds tailscaled, no download needed
#
# The second form only differs when a binary for every requested architecture
# is actually published; otherwise it is skipped rather than shipping a zip
# that fails to install.
set -euo pipefail

cd "$(dirname "$0")/../.."

VERSION="${1:-$(grep '^version=' module.prop | cut -d= -f2)}"
DIST_DIR="dist"
BIN_DIR="tailscale/bin"
TS_BIN_REPO="${TS_BIN_REPO:-paras-moharle/tailscaled-reborn}"

mkdir -p "$BIN_DIR" "$DIST_DIR"

echo "Packaging Magisk-Tailscaled ${VERSION}"
echo "========================================"

# Discover binaries through the release-asset feed, not the GitHub API.
# Unauthenticated api.github.com allows 60 requests/hour per IP, and CI plus a
# phone behind CGNAT can easily exhaust that; /releases/latest/download/ is a
# plain redirect with no limit.
FEED_URL="${TS_BIN_FEED:-https://raw.githubusercontent.com/${TS_BIN_REPO}/main/tailscale-latest.json}"

echo "- Querying ${FEED_URL}"
FEED_JSON="$(curl -fsSL "$FEED_URL" 2>/dev/null)" || FEED_JSON=""
if [ -z "$FEED_JSON" ]; then
	echo "!! Could not reach the pinned feed (${FEED_URL})" >&2
	echo "!! Falling back to the GitHub API (rate limited)." >&2
	FEED_JSON="$(curl -fsSL "https://api.github.com/repos/${TS_BIN_REPO}/releases/latest" 2>/dev/null)" || {
		echo "!! Both feed and API unreachable; cannot embed binaries." >&2
		exit 1
	}
fi
# The feed names the release the binaries actually live in, and optionally the
# repo that release belongs to. Guessing is what broke this: the daemon build
# and the module zip share a repo, so /releases/latest is whichever one was
# published last, and a hardcoded repo points at a tag that may not exist.
FEED_TAG="$(printf '%s' "$FEED_JSON" | jq -r '.tag // empty' 2>/dev/null)" || FEED_TAG=""
FEED_REPO="$(printf '%s' "$FEED_JSON" | jq -r '.repo // empty' 2>/dev/null)" || FEED_REPO=""
[ -n "$FEED_REPO" ] && TS_BIN_REPO="$FEED_REPO"
echo "    tag: ${FEED_TAG:-<none>}  repo: ${TS_BIN_REPO}"

AVAILABLE_ARCH=""
for ARCH in arm64 arm; do
	# latest.json lists asset names; the GitHub API lists objects with URLs.
	# Accept either, and never let a jq hiccup abort the build under pipefail.
	NAME="$(jq -r --arg a "$ARCH" \
		'if (.assets|type)=="array" then
		   (.assets[] | select(.name | endswith("_" + $a + ".tgz")) | .name)
		 elif (.assets|type)=="object" then
		   (.assets | to_entries[] | select(.value | endswith("_" + $a + ".tgz")) | .value)
		 else empty end' <<<"$FEED_JSON" 2>/dev/null | head -1)" || NAME=""

	if [ -z "$NAME" ]; then
		echo "  ! no ${ARCH} binary published; skipping"
		continue
	fi

	URL="$(jq -r --arg n "$NAME" \
		'if (.assets|type)=="array" then
		   (.assets[] | select(.name == $n) | .browser_download_url)
		 else empty end' <<<"$FEED_JSON" 2>/dev/null | head -1)" || URL=""

	if [ -z "$URL" ]; then
		# pinned feed gives us the exact release tag: use it so we do not
		# guess against /releases/latest, which now also publishes module zips.
		if [ -n "$FEED_TAG" ]; then
			URL="https://github.com/${TS_BIN_REPO}/releases/download/${FEED_TAG}/${NAME}"
		else
			URL="https://github.com/${TS_BIN_REPO}/releases/latest/download/${NAME}"
		fi
	fi

	# The installer has this repository hardcoded in customize.sh, and it is
# the installer that a person runs when they flash the module. If the two
# ever disagree, the installer's download 404s and nobody finds out until a
# phone tries to install it -- which is exactly what happened when the
# binaries moved out of the archived second repository.
FEED_REPO_NAME="$(printf '%s' "$FEED_JSON" | jq -r '.repo // empty' 2>/dev/null)" || FEED_REPO_NAME=""
INSTALLER_REPO="$(grep -oE '^TS_BIN_REPO="[^"]+"' customize.sh 2>/dev/null | head -1 | cut -d'"' -f2)" || INSTALLER_REPO=""
if [ -n "$FEED_REPO_NAME" ] && [ -n "$INSTALLER_REPO" ] && [ "$FEED_REPO_NAME" != "$INSTALLER_REPO" ]; then
	echo "!! The installer downloads from ${INSTALLER_REPO} but the feed names ${FEED_REPO_NAME}." >&2
	echo "!! Every install of the small zip would fail with a download error." >&2
	exit 1
fi

echo "- Downloading ${NAME}..."
	if ! curl -fsSL "$URL" | tar -xz -C "$BIN_DIR"; then
		echo "!! failed to fetch ${NAME}" >&2
		exit 1
	fi
	# The tgz holds a single file named `tailscaled`.
	if [ ! -f "$BIN_DIR/tailscaled" ]; then
		echo "!! archive for ${ARCH} did not contain tailscaled" >&2
		exit 1
	fi
	mv -f "$BIN_DIR/tailscaled" "$BIN_DIR/tailscaled-${ARCH}"
	chmod 0755 "$BIN_DIR/tailscaled-${ARCH}"
	AVAILABLE_ARCH="$AVAILABLE_ARCH $ARCH"
done

rm -f "$DIST_DIR"/Magisk-Tailscaled-*.zip

# jq is as necessary as tailscaled: customize.sh aborts without it, and
# "full" has to mean offline-installable. It was never fetched here, so the
# -full zip shipped without it and the install fell back to a download that
# fails on devices with no HTTPS-capable wget.
# Asset names are the Android ABI names, not the module's arch names: the repo
# publishes jq-aarch64-linux-android, not jq-arm64-*. armv7a is spelled
# "...androideabi" upstream as well.
JQ_REPO="${JQ_REPO:-theshoqanebi/jq-build-for-android}"
echo "- Downloading jq (aarch64)"
if curl -fsSL "https://github.com/${JQ_REPO}/releases/latest/download/jq-aarch64-linux-android" \
		-o "$BIN_DIR/jq-arm64"; then
	chmod 0755 "$BIN_DIR/jq-arm64"
else
	echo "!! could not fetch jq; -full would not install offline" >&2
	exit 1
fi

# Packaging lives in builder/build-module.sh so a CI build and a local build
# produce the same package. It stages an explicit file list (rather than zipping
# the whole tree, which used to ship share-app/ source but never the built APK),
# bundles TailBridge.apk, and refuses to emit a package that would fail to
# install.
echo "- Building TailBridge"
./share-app/build.sh

echo "- Packaging (slim + full)"
if [ -n "$AVAILABLE_ARCH" ]; then
	./builder/build-module.sh --full
else
	./builder/build-module.sh
fi

rm -f "$BIN_DIR"/*

echo ""
echo "TailBridge:  $(ls -lh share-app/build/TailBridge.apk 2>/dev/null | awk '{print $5}')"
echo ""
echo "Build completed successfully!"
echo "========================================"
ls -lh "$DIST_DIR"
echo "========================================"
echo "Binaries embedded in full zip:${AVAILABLE_ARCH:- none}"