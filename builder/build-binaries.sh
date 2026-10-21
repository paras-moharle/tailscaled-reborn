#!/usr/bin/env bash
# build-binaries.sh — build Android tailscaled binaries for a given Tailscale
# release.
#
#   ./builder/build-binaries.sh                 # latest upstream stable release
#   ./builder/build-binaries.sh v1.104.1        # a specific tag
#   ./builder/build-binaries.sh --with-arm v1.104.1
#
# Produces in dist/:
#   tailscale_<ver>_arm64.tgz      (always)
#   tailscale_<ver>_arm.tgz        (only with --with-arm)
#   SHA256SUMS
#
# The tgz layout (a single file named `tailscaled` at the root) is what the
# in-binary `tailscale update` expects to extract, and what customize.sh
# untars on install. Keep it that way.
set -euo pipefail

WITH_ARM=0
while [ $# -gt 0 ]; do
	case "$1" in
	--with-arm) WITH_ARM=1; shift ;;
	-*) echo "unknown option: $1" >&2; exit 1 ;;
	*) break ;;
	esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
UPSTREAM="https://github.com/tailscale/tailscale.git"
TS_UPDATE_REPO="${TS_UPDATE_REPO:-paras-moharle/tailscaled-reborn}"

TAG="${1:-}"
if [ -z "$TAG" ]; then
	echo "==> Resolving latest Tailscale stable release"
	TAG="$(git ls-remote --tags --refs "$UPSTREAM" \
		| awk '{print $2}' | sed 's|refs/tags/||' \
		| grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)"
	[ -n "$TAG" ] || { echo "!! could not resolve latest release" >&2; exit 1; }
fi
echo "==> Building Tailscale $TAG"

VERSION="${TAG#v}"                      # 1.104.1
SHORT="${VERSION%%-*}"                  # strip -pre / -dev suffixes for asset names

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Fetching upstream $TAG"
git clone -q --depth 1 --branch "$TAG" "$UPSTREAM" "$WORKDIR/tailscale"

echo "==> Applying Android port"
TS_UPDATE_REPO="$TS_UPDATE_REPO" "$HERE/port-android.sh" "$WORKDIR/tailscale"

cd "$WORKDIR/tailscale"

eval "$(./build_dist.sh shellvars)"
echo "==> upstream reports: $VERSION_LONG ($VERSION_GIT_HASH)"

BUILD_TAGS="$(GOOS= GOARCH= go run ./cmd/featuretags \
	--remove 'aws,bird,tap,kube,completion,completion_scripts,wakeonlan,capture,systray,syspolicy,appconnectors,identityfederation,usermetrics,logtail,netlog,linuxdnsfight,tpm' \
	--add 'cli')"

LDFLAGS="-X tailscale.com/version.longStamp=${VERSION_LONG} \
-X tailscale.com/version.shortStamp=${VERSION_SHORT} \
-X tailscale.com/version.gitCommitStamp=${VERSION_GIT_HASH} \
-w -s"

mkdir -p "$REPO_ROOT/dist"

# CGO_ENABLED=0 produces a self-contained binary with no NDK requirement.
# The upstream author builds with cgo+NDK; cgo=0 builds cleanly for arm64 and
# removes the NDK from CI entirely. Tailscale DNS on Android goes through the
# patched net/dns manager, not the libc resolver, so this is safe.
#
# Caveat: Go's 32-bit android/arm port REQUIRES external linking, so the
# legacy arm build needs cgo plus the Android NDK. arm64 is the only arch
# built by default — every 64-bit Android device is covered.
export CGO_ENABLED=0

build_arch() {
	local ARCH="$1"
	case "$ARCH" in
	arm64) GOARCH_TARGET=arm64 ;;
	arm) GOARCH_TARGET=arm ;;
	esac
	echo "==> Building $ARCH (cgo=$CGO_ENABLED)"
	GOOS=android GOARCH="$GOARCH_TARGET" go build \
		-tags="$BUILD_TAGS" \
		-ldflags="$LDFLAGS" \
		-o "$WORKDIR/tailscaled.$ARCH" \
		-trimpath ./cmd/tailscaled

	# Sanity check: must be an Android ELF, not a host binary.
	file "$WORKDIR/tailscaled.$ARCH" | grep -q 'ELF' || {
		echo "!! $ARCH: not an ELF binary" >&2; exit 1; }
	file "$WORKDIR/tailscaled.$ARCH" | grep -q 'linker6\|linker' || {
		echo "!! $ARCH: not an Android-linked binary" >&2; exit 1; }

	tar -czf "$REPO_ROOT/dist/tailscale_${SHORT}_${ARCH}.tgz" \
		-C "$WORKDIR" --transform="s/tailscaled.${ARCH}/tailscaled/" "tailscaled.${ARCH}"
	echo "    -> dist/tailscale_${SHORT}_${ARCH}.tgz ($(du -h "$REPO_ROOT/dist/tailscale_${SHORT}_${ARCH}.tgz" | cut -f1))"
}

build_arch arm64

if [ "$WITH_ARM" -eq 1 ]; then
	if [ -z "${ANDROID_NDK_PATH:-}" ]; then
		echo "!! --with-arm needs the Android NDK." >&2
		echo "!! Set ANDROID_NDK_PATH to \$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" >&2
		exit 1
	fi
	echo "==> Building legacy arm (cgo=1, NDK r27c required)"
	CGO_ENABLED=1 \
	PATH="${ANDROID_NDK_PATH}:$PATH" \
	build_arch arm
fi

cd "$REPO_ROOT/dist"
sha256sum tailscale_${SHORT}_*.tgz > SHA256SUMS

# Machine-readable "what is current" pointer.
#
# Deliberately NOT the GitHub API: unauthenticated api.github.com is limited to
# 60 requests/hour PER IP, and phones sit behind CGNAT where a whole mobile
# carrier can share one address. Checking for updates through the API means a
# phone can get 403'd for hours. Attach this to the release and read it via
# https://github.com/<owner>/<repo>/releases/latest/download/latest.json,
# which is a plain redirect with no rate limit.
cat > latest.json <<JSON
{
  "version": "${SHORT}",
  "tag": "v${SHORT}-android",
  "longVersion": "${VERSION_LONG}",
  "gitCommit": "${VERSION_GIT_HASH}",
  "assets": {
    "arm64": "tailscale_${SHORT}_arm64.tgz"
  }
}
JSON

echo
echo "==> Done"
ls -1 tailscale_${SHORT}_*.tgz
echo "SHA256SUMS and latest.json written."
echo
echo "Release tag to publish:  v${SHORT}-android"
echo "Assets must be named:   tailscale_${SHORT}_arm.tgz, tailscale_${SHORT}_arm64.tgz"
echo "                        SHA256SUMS, latest.json"