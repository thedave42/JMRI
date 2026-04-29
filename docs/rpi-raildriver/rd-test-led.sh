#!/usr/bin/env bash
# Phase 3: Verify the RailDriver LED output report path.
#
# Sends a sequence of LED-segment buffers as HID output report 0x86, pausing
# between each test for the user to record what the LED actually shows.
#
# Tests:
#   1. "Pro"     -- what the Java code shows on a successful device open
#   2. "123"     -- digit encoding sanity
#   3. "8.8.8."  -- all segments + decimal points lit
#   4. "???"     -- the question-mark glyph
#   5. clear     -- all segments off
#
# Usage:
#   docs/rpi-raildriver/rd-test-led.sh
#
# Pre-flight:
#   - Quit JMRI so it isn't holding the device.
#
# At the end, observations are written interactively to
# $RD_OUTDIR/30-led-observations.txt.
#
# See issue #1 for the broader capture plan.

set -uo pipefail

DEV="${RD_HIDRAW:-/dev/hidraw0}"
OUTDIR="${RD_OUTDIR:-${HOME}/rd-capture}"
OBS="$OUTDIR/30-led-observations.txt"

mkdir -p "$OUTDIR"

if [[ ! -e "$DEV" ]]; then
    echo "ERROR: $DEV does not exist. Set RD_HIDRAW=/dev/hidrawN." >&2
    exit 1
fi

echo ">>> Output device      : $DEV"
echo ">>> Observations file  : $OBS"
echo ">>> sudo will be requested once to write to $DEV"
sudo -v || exit 1

: > "$OBS"
echo "RailDriver LED test observations -- $(date)" >> "$OBS"
echo "Device: $DEV" >> "$OBS"
echo >> "$OBS"

# Send `data` to $DEV via Python (cleanest way to write raw bytes through sudo).
send_bytes() {
    local label="$1"; shift
    local pyargs
    pyargs=$(printf '0x%02x,' "$@")
    pyargs="${pyargs%,}"
    sudo python3 -c "import os
data = bytes([$pyargs])
fd = os.open('$DEV', os.O_WRONLY)
n = os.write(fd, data); os.close(fd)
print('wrote', n, 'bytes for $label')"
}

run_test() {
    local n="$1" label="$2"; shift 2
    echo
    echo "===================================================================="
    echo "  TEST $n: $label"
    send_bytes "$label" "$@"
    read -r -p "  What does the LED show? Type observation, then Enter: " obs
    printf 'Test %d (%s): %s\n' "$n" "$label" "$obs" >> "$OBS"
}

# Buffer layouts (report id 0x86 then 7 segment bytes):
# Pro:    [0x86, 0x5C, 0x50, 0x73, 0,0,0,0]  -- 'o','r','P' from SevenSegmentAlpha
# 123:    [0x86, 0x4F, 0x5B, 0x06, 0,0,0,0]  -- '3','2','1' from SevenSegment
# 8.8.8.: [0x86, 0xFF, 0xFF, 0xFF, 0,0,0,0]  -- all segments + DPs
# ???:    [0x86, 0x53, 0x53, 0x53, 0,0,0,0]
# clear:  [0x86, 0x00, 0x00, 0x00, 0,0,0,0]

run_test 1 "Pro"     0x86 0x5C 0x50 0x73 0x00 0x00 0x00 0x00
run_test 2 "123"     0x86 0x4F 0x5B 0x06 0x00 0x00 0x00 0x00
run_test 3 "8.8.8."  0x86 0xFF 0xFF 0xFF 0x00 0x00 0x00 0x00
run_test 4 "???"     0x86 0x53 0x53 0x53 0x00 0x00 0x00 0x00
run_test 5 "clear"   0x86 0x00 0x00 0x00 0x00 0x00 0x00 0x00

echo
read -r -p "Any general notes / oddities (Enter to skip): " notes
if [[ -n "$notes" ]]; then
    printf '\nNotes: %s\n' "$notes" >> "$OBS"
fi

echo
echo "DONE. Observations saved to $OBS"
cat "$OBS"
