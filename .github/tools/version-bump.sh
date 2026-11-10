#!/bin/bash
# Bump the module version.
#
# The module uses a three-part version (v1.0.34) and an eight-digit code in
# which the patch number is the last two digits: v1.0.34 -> 01000034. The
# previous version of this script wrote a fourth component (`v1.0.34.0`) and
# a code of 01003400, which the release workflow then had to correct -- and
# which the module manager compared against a different scheme.
set -euo pipefail

BUMP_TYPE="${1:-patch}"

case "$BUMP_TYPE" in
  major | minor | patch) ;;
  *)
    echo "Usage: $0 [major|minor|patch]" >&2
    exit 1
    ;;
esac

CURRENT=$(grep '^version=' module.prop | cut -d'=' -f2 | tr -d 'v')
IFS='.' read -r MAJOR MINOR PATCH _ <<< "$CURRENT"
MAJOR=${MAJOR:-0}
MINOR=${MINOR:-0}
PATCH=${PATCH:-0}

case "$BUMP_TYPE" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac

NEW_VERSION="v${MAJOR}.${MINOR}.${PATCH}"
NEW_VERSION_CODE=$(printf "%02d%02d00%02d" "$MAJOR" "$MINOR" "$PATCH")

# Commits since the last tag, with the bot's own version bumps filtered out.
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
if [ -n "$LAST_TAG" ]; then
  COMMITS=$(git log --oneline "$LAST_TAG"..HEAD --pretty=format:"%an|%h|%s" \
    | grep -v -E "^github-actions|Bump version" \
    | sed 's/^[^|]*|\([^|]*\)|/- \1 /' || true)
else
  COMMITS=$(git log --oneline --pretty=format:"%an|%h|%s" | head -10 \
    | grep -v -E "^github-actions|Bump version" \
    | sed 's/^[^|]*|\([^|]*\)|/- \1 /' || true)
fi

if [ -n "${COMMITS:-}" ]; then
  TEMP_CHANGELOG=$(mktemp)
  {
    echo "## $NEW_VERSION"
    echo ""
    echo "$COMMITS"
    echo ""
    cat CHANGELOG.md
  } > "$TEMP_CHANGELOG"
  mv "$TEMP_CHANGELOG" CHANGELOG.md
  echo "Added changelog entry for $NEW_VERSION"
fi

sed -i "s/^version=.*/version=$NEW_VERSION/" module.prop
sed -i "s/^versionCode=.*/versionCode=$NEW_VERSION_CODE/" module.prop

# The README carries the version twice, in the module badge and in the
# install filename. Both used to drift several releases behind because no
# part of the release flow rewrote them.
if [ -f README.md ]; then
  sed -i "s|module-v[0-9][0-9.]*-|module-${NEW_VERSION#v}-|" README.md
  sed -i "s|Tailscaled-Reborn-v[0-9][0-9.]*-full\.zip|Tailscaled-Reborn-${NEW_VERSION}-full.zip|g" README.md
fi

# One feed file, not three. update-arm.json / update-arm64.json were left over
# from a multi-architecture layout this module no longer publishes, and the
# loop silently skipped them when they were absent.
jq --arg v "$NEW_VERSION" --arg vc "$NEW_VERSION_CODE" \
  '.version = $v
   | .versionCode = $vc
   | .zipUrl = "https://github.com/paras-moharle/tailscaled-reborn/releases/download/\($v)/Tailscaled-Reborn-\($v)-full.zip"
   | .changelog = "https://raw.githubusercontent.com/paras-moharle/tailscaled-reborn/main/CHANGELOG.md"' \
  update.json > update.json.tmp
mv update.json.tmp update.json

echo "Bumped $BUMP_TYPE: v$CURRENT -> $NEW_VERSION (code: $NEW_VERSION_CODE)"
echo ""
echo "Next steps:"
echo "  git add module.prop update.json CHANGELOG.md"
echo "  git commit -m 'Bump version to $NEW_VERSION'"
echo "  git tag $NEW_VERSION"
echo "  git push && git push --tags"
