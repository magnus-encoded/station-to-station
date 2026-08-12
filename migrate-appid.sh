#!/usr/bin/env bash
# The applicationId rename makes the new build a separate install, so the old
# app's data does not come with it. This carries it across: a tar out of the
# old package's private dir, the same tar into the new one, and the one edit
# the move actually needs — every stored gig_photos ref is a FileProvider uri
# whose authority is the *package name*, so refs written by the old install
# name an authority the new install does not serve.
#
# Non-destructive: it only reads the old package. Run with both installed,
# before uninstalling anything, and keep the old install until the new one
# has been opened and checked.
#
# Usage: ANDROID_SERIAL=<serial> ./migrate-appid.sh [backup.tar]
#   with no argument it pulls a fresh full tar first (4.5G — USB, not wifi).
set -euo pipefail

OLD=io.github.magnusencoded.setlist2spotify
NEW=io.github.magnusencoded.stationtostation
TAR=${1:-}

adb shell pm list packages | tr -d '\r' | grep -qx "package:$NEW" ||
  { echo "install the new build first: $NEW is not on the device"; exit 1; }

if [ -z "$TAR" ]; then
  TAR=$(mktemp -t sts-migrate-XXXX.tar)
  echo "pull   $OLD"
  adb exec-out run-as "$OLD" tar -c files > "$TAR"
fi
echo "tar    $TAR ($(du -h "$TAR" | cut -f1))"

# Force-stop rather than trust the app to be idle: TimelineStore holds the
# cache in memory and writes it whole, so a live process would overwrite the
# restore with what it had at launch.
adb shell am force-stop "$NEW"

# /data/local/tmp is the one place adb push and run-as can both reach — the
# app's own external dir is not, run-as cannot write there.
adb push "$TAR" /data/local/tmp/sts-migrate.tar >/dev/null
adb shell run-as "$NEW" tar -x -f /data/local/tmp/sts-migrate.tar
adb shell rm /data/local/tmp/sts-migrate.tar
echo "restore into $NEW"

# The only rewrite. content://<pkg>.fileprovider/gig_photos/<file> is the app
# pointing at its own copy; whoever holds the bytes, the authority has to be
# the package that serves them.
adb shell run-as "$NEW" sed -i "s/$OLD\.fileprovider/$NEW\.fileprovider/g" files/timelines.json

# Counted as occurrences, not matching lines: timelines.json is one long line,
# so `grep -c` would report 1 however many refs it holds.
left=$(adb exec-out run-as "$NEW" cat files/timelines.json | grep -o "$OLD\.fileprovider" | wc -l)
now=$(adb exec-out run-as "$NEW" cat files/timelines.json | grep -o "$NEW\.fileprovider" | wc -l)
echo "refs   ${now:-0} on the new authority, ${left:-0} left on the old"
[ "${left:-0}" = "0" ] || { echo "FAILED: old authority still present"; exit 1; }
