#!/bin/bash
# Build, install and run one GossipRadioDeviceTest method against the Pi peer.
# Usage: scratchpad/radio-test.sh receive|controls [--no-build]
# Prints only a short summary. Full logs: scratchpad/radio-{build,instr,pi}.log
set -uo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
  receive)  method=publicPassesCrossTheRealGattLinkAndCloseTheStormGate; pi_args=6; flag=manual_ble_peer ;;
  controls) method=indirectControlsAreRejectedWithoutPoisoningDirectDelivery; pi_args="--controls"; flag=manual_ble_controls ;;
  *) echo "usage: $0 receive|controls [--no-build]"; exit 2 ;;
esac
pkg=io.github.magnusencoded.stationtostation
ssh_pi=(ssh -F /dev/null -o BatchMode=yes -o ConnectTimeout=10 -i "$HOME/.ssh/id_ed25519_pinet" pi@10.42.0.1)
log=scratchpad

if [ "${2:-}" != "--no-build" ]; then
  echo "building..."
  (cd android && ./gradlew-lf :app:assembleDebug :app:assembleDebugAndroidTest --console=plain) > $log/radio-build.log 2>&1 \
    || { echo "BUILD FAILED"; tail -n 15 $log/radio-build.log; exit 1; }
  for apk in android/app/build/outputs/apk/debug/app-debug.apk \
             android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    adb install -r "$apk" > /dev/null || { echo "INSTALL FAILED: $apk"; exit 1; }
  done
fi

"${ssh_pi[@]}" true || { echo "PI UNREACHABLE"; exit 1; }
start=$(date +%s)
adb shell am instrument -w -e $flag true -e class "$pkg.GossipRadioDeviceTest#$method" \
  "$pkg.debug.test/androidx.test.runner.AndroidJUnitRunner" > $log/radio-instr.log 2>&1 &
instr=$!
sleep 4
"${ssh_pi[@]}" "/home/pi/gossip-plain-att.sh $pi_args" > $log/radio-pi.log 2>&1
pi_exit=$?
wait $instr
echo "test: $method  wall: $(( $(date +%s) - start ))s  pi exit: $pi_exit"
grep -E '^(OK|FAILURES|Time:|Tests run)' $log/radio-instr.log
grep -iE 'fail|error|exception' $log/radio-instr.log | head -n 5
grep -iE 'trial|pass|ok|fail' $log/radio-pi.log | tail -n 8
