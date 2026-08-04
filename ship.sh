#!/usr/bin/env bash
# Newest CI build onto the hardware: APK to every connected adb target,
# IPA into ios/dist ready for signing.
#
#   ./ship.sh              # current branch
#   ./ship.sh main         # a named branch
set -euo pipefail

branch="${1:-$(git rev-parse --abbrev-ref HEAD)}"
dist="$(git rev-parse --show-toplevel)/ios/dist"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Newest *successful* run, so a red branch doesn't silently ship a stale build.
run() { gh run list --branch "$branch" --workflow="$1" --status success --limit 1 \
        --json databaseId,headSha -q '.[0] // empty | "\(.databaseId) \(.headSha[0:7])"'; }

head=$(git rev-parse --short "origin/$branch" 2>/dev/null || git rev-parse --short "$branch")
echo "branch: $branch @ $head"

# A green run can lag the branch head — CI still running, or the head is red.
# Shipping the older artifact silently is how you debug a fix that isn't in the
# binary, so say so rather than just printing a sha nobody diffs.
# An `if`, not `&&`: a false test as the last statement exits non-zero under set -e.
stale() {
  if [ "$1" != "$head" ]; then
    echo "       ^ NOT $head — that build is behind the branch head"
  fi
}

read -r android_run sha < <(run android.yml) || true
if [ -n "${android_run:-}" ]; then
  gh run download "$android_run" -n app-debug -D "$tmp/apk" >/dev/null
  apk=$(find "$tmp/apk" -name '*.apk' | head -1)
  # `adb devices` lists offline/unauthorized targets too; only install to ready ones.
  targets=$(adb devices | awk '$2=="device" {print $1}')
  if [ -z "$targets" ]; then
    echo "apk    $sha  (no adb target connected, skipped)"
  else
    for t in $targets; do
      echo -n "apk    $sha -> $t  "
      adb -s "$t" install -r "$apk" 2>&1 | grep -qi success && echo "ok" || echo "FAILED"
    done
  fi
  stale "$sha"
else
  echo "apk    no successful Android CI run on $branch"
fi

read -r ios_run sha < <(run ios.yml) || true
if [ -n "${ios_run:-}" ]; then
  gh run download "$ios_run" -n SetlistToSpotify-ipa -D "$tmp/ipa" >/dev/null
  mkdir -p "$dist"
  cp "$tmp/ipa/SetlistToSpotify.ipa" "$dist/SetlistToSpotify-$sha.ipa"
  cp "$tmp/ipa/SetlistToSpotify.ipa" "$dist/SetlistToSpotify.ipa"
  echo "ipa    $sha -> ios/dist/SetlistToSpotify.ipa"; stale "$sha"
else
  echo "ipa    no successful iOS CI run on $branch"
fi
