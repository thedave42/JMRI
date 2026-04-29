#!/usr/bin/env bash
# Guided RailDriver capture session.
#
# For each action the script announces, press Enter to begin a fixed-length
# recording window, perform the action ONCE during the window (press+release,
# or sweep the lever), then accept / redo / skip / quit at the prompt.
#
# Each capture is saved to a uniquely named file under $HOME/rd-capture/.
# Filenames include the action label, so no time-alignment is needed.
#
# Output format is `xxd -c 14`-formatted text (one 14-byte HID input report
# per line). This is the same format already in use under
# docs/rpi-raildriver/test-data/.
#
# Usage:
#   chmod +x docs/rpi-raildriver/rd-record.sh
#   docs/rpi-raildriver/rd-record.sh
#
# Environment knobs:
#   RD_HIDRAW   /dev/hidrawN node for the RailDriver (default: /dev/hidraw0)
#   RD_WINDOW   recording window length in seconds (default: 6)
#   RD_START    starting numeric prefix for output files (default: 100)
#   RD_OUTDIR   output directory (default: $HOME/rd-capture)
#
# Pre-flight:
#   - Quit JMRI completely so it isn't holding the device.
#   - Confirm the right hidraw node:
#       sudo udevadm info --query=all --name=/dev/hidraw0 | grep -E "VENDOR|PRODUCT|ID_MODEL"
#   - Install the dependency packages once:
#       sudo apt install -y usbutils  # for lsusb (only used by Phase 1, not by this script)
#
# See issue #1 (https://github.com/thedave42/JMRI/issues/1) for the broader
# capture plan this script implements (Phase 2).

set -u

OUTDIR="${RD_OUTDIR:-${HOME}/rd-capture}"
DEV="${RD_HIDRAW:-/dev/hidraw0}"
WINDOW="${RD_WINDOW:-6}"
START_INDEX="${RD_START:-100}"

mkdir -p "$OUTDIR"
cd "$OUTDIR"

if [[ ! -e "$DEV" ]]; then
    echo "ERROR: $DEV does not exist." >&2
    echo "       Set RD_HIDRAW=/dev/hidrawN to point at the RailDriver." >&2
    exit 1
fi

echo ">>> Output directory : $OUTDIR"
echo ">>> Device           : $DEV"
echo ">>> Recording window : ${WINDOW}s"
echo ">>> Starting index   : $START_INDEX"
echo ">>> sudo will be requested once to read $DEV during recordings"
sudo -v || exit 1

COUNT=$START_INDEX
SKIPPED=0
RECORDED=0

record() {
    local label="$1"
    local sanitized
    sanitized=$(echo "$label" | tr '[:upper:] ' '[:lower:]_' | tr -cd 'a-z0-9_-')
    while true; do
        local file
        file=$(printf '%03d-%s.log' "$COUNT" "$sanitized")
        echo
        echo "===================================================================="
        printf '  ACTION (%d): %s\n' "$COUNT" "$label"
        echo "  Get hands on the controller, then press Enter to begin a ${WINDOW}-second capture."
        echo "  Perform the action ONCE during the window (press+release, or sweep the lever)."
        read -r -p "  [Enter]=record  s=skip  q=quit  : " ans
        case "$ans" in
            q|Q) echo "Quitting (recorded=$RECORDED, skipped=$SKIPPED)."; exit 0 ;;
            s|S) echo "  -> skipped"; SKIPPED=$((SKIPPED+1)); COUNT=$((COUNT+1)); return ;;
        esac
        echo "  Recording for ${WINDOW}s now..."
        sudo timeout "$WINDOW" cat "$DEV" 2>/dev/null | xxd -c 14 > "$file"
        local n unique
        n=$(wc -l < "$file" 2>/dev/null || echo 0)
        unique=$(awk '{print $2,$3,$4,$5,$6,$7,$8}' "$file" 2>/dev/null | sort -u | wc -l)
        echo "  -> saved $file ($n reports, $unique unique payloads)"
        if [[ "$n" -lt 5 ]]; then
            echo "  WARNING: very few reports captured. Device may be busy or no events fired."
        fi
        read -r -p "  [Enter]=accept  r=redo  s=skip&continue  q=quit  : " confirm
        case "$confirm" in
            r|R) rm -f "$file"; continue ;;
            s|S) rm -f "$file"; SKIPPED=$((SKIPPED+1)); COUNT=$((COUNT+1)); return ;;
            q|Q) echo "Quitting (recorded=$RECORDED, skipped=$SKIPPED)."; exit 0 ;;
            *)   RECORDED=$((RECORDED+1)); COUNT=$((COUNT+1)); return ;;
        esac
    done
}

# ------- Action list -------
# Baseline: do nothing during the window
record "baseline all controls at rest"

# Levers — sweep each through its full physical range during the window
record "throttle from centre to full forward and back to centre"
record "throttle from centre to full back and back to centre"
record "reverser from neutral to forward and back to neutral"
record "reverser from neutral to reverse and back to neutral"
record "autobrake from release to full apply and back to release"
record "indepbrake from release to full apply and back to release"
record "bailoff press right and release"
record "wiper rotary through all positions"
record "lights rotary through all positions"

# Named buttons / spring-return controls
record "bell press and release"
record "horn lever forward and back to centre"
record "horn lever back and back to centre"
record "zoom rocker up press and release"
record "zoom rocker down press and release"
record "pov up press and release"
record "pov down press and release"
record "pov left press and release"
record "pov right press and release"
record "gear up press and release"
record "gear down press and release"
record "estop up press and release"
record "estop down press and release"
record "alerter press and release"
record "sander press and release"
record "pantograph press and release"

# Blue function buttons — one per action so we can map every bit individually.
# 28 captures here, each ~6 s. About 3 minutes for this whole block.
for row in 1 2; do
    for n in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do
        record "blue button row${row} pos${n} press and release"
    done
done

echo
echo "===================================================================="
echo "DONE. Recorded=$RECORDED, skipped=$SKIPPED."
echo "Files in $OUTDIR/:"
ls -1 "$OUTDIR" | grep '\.log$'
