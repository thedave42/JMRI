#!/usr/bin/env bash
# Phase 4: Verify the RailDriver speaker output report path.
#
# Sends report 0x85 with byte[5]=1 (on), waits 0.5 s, sends byte[5]=0 (off),
# then asks the user whether anything was audible.
#
# Usage:
#   docs/rpi-raildriver/rd-test-speaker.sh
#
# Pre-flight:
#   - Quit JMRI so it isn't holding the device.
#
# Observations are written to $RD_OUTDIR/31-speaker-observations.txt.
#
# See issue #1 for the broader capture plan.

set -uo pipefail

DEV="${RD_HIDRAW:-/dev/hidraw0}"
OUTDIR="${RD_OUTDIR:-${HOME}/rd-capture}"
OBS="$OUTDIR/31-speaker-observations.txt"

mkdir -p "$OUTDIR"

if [[ ! -e "$DEV" ]]; then
    echo "ERROR: $DEV does not exist. Set RD_HIDRAW=/dev/hidrawN." >&2
    exit 1
fi

echo ">>> Output device      : $DEV"
echo ">>> Observations file  : $OBS"
echo ">>> sudo will be requested once to write to $DEV"
sudo -v || exit 1

echo
echo "Sending speaker-on burst for 0.5 seconds..."
sudo python3 -c "
import os, time
on  = bytes([0x85, 0,0,0,0,0,1,0])
off = bytes([0x85, 0,0,0,0,0,0,0])
fd = os.open('$DEV', os.O_WRONLY)
os.write(fd, on); time.sleep(0.5)
os.write(fd, off); os.close(fd)
print('done')
"

echo
read -r -p "Did you hear anything? (y/n): " heard
read -r -p "If yes, describe the sound (continuous tone / pulse train / click / other): " desc

{
    echo "RailDriver speaker test observations -- $(date)"
    echo "Device: $DEV"
    echo "Heard:  $heard"
    echo "Desc:   $desc"
} > "$OBS"

echo
echo "DONE. Observations saved to $OBS"
cat "$OBS"
