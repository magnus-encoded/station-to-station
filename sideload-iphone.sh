#!/usr/bin/env bash
# Re-sign and reinstall the newest CI build on the paired iPhone.
#
# Free Apple ID certificates die after 7 days. This runs on a 5-day stamp, so a
# phone that touches the desk even occasionally never reaches expiry, and the
# two days of slack absorb a week where it doesn't.
set -euo pipefail

REPO_DIR=/home/dizzi90/Documents/station-to-station
UDID=00008110-001671043C00A01E
WORK="${XDG_CACHE_HOME:-$HOME/.cache}/station-resign"
STAMP="$WORK/last-install"
MAX_AGE=$((5 * 24 * 60 * 60))

mkdir -p "$WORK"

# The timer fires whether or not the phone is on the desk. Nothing to do is a
# success — otherwise every unattended run on a normal day reports as broken.
if ! idevice_id -l 2>/dev/null | grep -qx "$UDID"; then
    echo "phone not connected — nothing to do"
    exit 0
fi

if [ -f "$STAMP" ]; then
    age=$(($(date +%s) - $(stat -c %Y "$STAMP")))
    if [ "$age" -lt "$MAX_AGE" ] && [ "${1:-}" != "--force" ]; then
        echo "signed $((age / 86400))d ago — still inside the 7-day window"
        exit 0
    fi
fi

cd "$REPO_DIR"
gh release download --pattern StationToStation-debug.ipa --dir "$WORK" --clobber

# Deliberately no -i. An unattended run cannot answer a password prompt, so a
# session that has gone stale must fail here and be refreshed by hand rather
# than hang forever holding the device.
sideloader install --udid "$UDID" "$WORK/StationToStation-debug.ipa"

touch "$STAMP"
echo "installed $(date -Is)"
