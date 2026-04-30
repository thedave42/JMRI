#!/usr/bin/env bash
# Phase 2: per-action RailDriver HID capture, advanced manually with Enter.
#
# Captures one xxd -c 14 file per action in the inventory at
# docs/rpi-raildriver/control-inventory.md. The action list below
# follows the inventory's item numbering (1..43). For controls with
# multiple distinct positions or directions, multiple captures are
# performed (e.g. the Reverser has 3 detents -> 3 captures).
#
# For each action:
#   1. Script prints the action label and starts recording immediately.
#   2. You perform the action at your own pace (no time limit, no countdown).
#   3. Press Enter ONCE when you are DONE with that action.
#   4. The capture is saved to its own labelled file, and the script
#      moves on to the next action.
#
# Single-character options at each Enter prompt:
#   <empty>   accept and continue
#   r         redo this action (capture again)
#   s         skip this action (no file written)
#   q         quit immediately
#
# Output filenames look like:
#   100-item01-range-press-up-and-release.log
#   101-item01-range-press-down-and-release.log
#   102-item02-estop-press-up-and-release.log
#   ...
#
# Pre-flight:
#   - Quit JMRI completely so it isn't holding the device.
#
# Environment knobs:
#   RD_HIDRAW    /dev/hidrawN node for the RailDriver (default /dev/hidraw0)
#   RD_OUTDIR    output directory (default ~/rd-capture)
#   RD_START     numeric prefix offset (default 100)
#
# See issue #1 for the broader capture plan, and
# docs/rpi-raildriver/control-inventory.md for the inventory this list
# is derived from.

set -uo pipefail

DEV="${RD_HIDRAW:-/dev/hidraw0}"
OUTDIR="${RD_OUTDIR:-${HOME}/rd-capture}"
START="${RD_START:-100}"

mkdir -p "$OUTDIR"

if [[ ! -e "$DEV" ]]; then
    echo "ERROR: $DEV does not exist. Set RD_HIDRAW=/dev/hidrawN." >&2
    exit 1
fi

echo ">>> Output directory : $OUTDIR"
echo ">>> Device           : $DEV"
echo ">>> sudo will be requested once to read $DEV during recordings"
sudo -v || exit 1

# Each entry is "label|long_description". The label is sanitized into
# the filename. The long description is shown to the user before each
# capture so they know what to do.
ACTIONS=(

    # ---- Item 1: Range (SPDT momentary, up/off/down) ----
    "item01-range-press-up-and-release|Item 1 (Range, SPDT momentary): press the Range switch UP, hold for ~1 second, then release. Centre is rest."
    "item01-range-press-down-and-release|Item 1 (Range, SPDT momentary): press the Range switch DOWN, hold for ~1 second, then release."

    # ---- Item 2: E-Stop (SPDT momentary, up/off/down) ----
    "item02-estop-press-up-and-release|Item 2 (E-Stop, SPDT momentary): press the E-Stop switch UP, hold ~1 s, release."
    "item02-estop-press-down-and-release|Item 2 (E-Stop, SPDT momentary): press the E-Stop switch DOWN, hold ~1 s, release."

    # ---- Item 3: Alert (button) ----
    "item03-alert-press-and-release|Item 3 (Alert button): press the Alert button, hold ~1 s, release."

    # ---- Item 4: Sand (button) ----
    "item04-sand-press-and-release|Item 4 (Sand button): press the Sand button, hold ~1 s, release."

    # ---- Item 5: P (button; ASSUMED Pantograph) ----
    "item05-p-press-and-release|Item 5 (P button, assumed Pantograph): press the P button, hold ~1 s, release."

    # ---- Item 6: Bell (button) ----
    "item06-bell-press-and-release|Item 6 (Bell button): press the Bell button, hold ~1 s, release."

    # ---- Item 7: Horn (SPDT momentary, up/off/down) ----
    "item07-horn-press-up-and-release|Item 7 (Horn, SPDT momentary): push the Horn lever UP, hold ~1 s, release."
    "item07-horn-press-down-and-release|Item 7 (Horn, SPDT momentary): push the Horn lever DOWN, hold ~1 s, release."

    # ---- Item 8: Reverser (3-detent analog) ----
    "item08-reverser-to-forward|Item 8 (Reverser): move the Reverser to the FORWARD detent and stop there."
    "item08-reverser-to-neutral|Item 8 (Reverser): move the Reverser to the NEUTRAL (centre) detent and stop there."
    "item08-reverser-to-reverse|Item 8 (Reverser): move the Reverser to the REVERSE detent and stop there."

    # ---- Item 9: Throttle / Dynamic Brake (continuous bipolar analog) ----
    "item09-throttle-idle-to-max-and-back-to-idle|Item 9 (Throttle/Dyn-Brake): starting from idle (centre), push DOWN to maximum throttle, then return to idle. (Down = throttle.)"
    "item09-dynbrake-idle-to-max-and-back-to-idle|Item 9 (Throttle/Dyn-Brake): starting from idle (centre), pull UP to maximum dynamic brake, then return to idle. (Up = dyn-brake.)"

    # ---- Item 10: Auto Brake (continuous, named positions) ----
    "item10-autobrake-to-release|Item 10 (Auto Brake): move the Auto Brake to the RELEASE position (no brake) and stop there."
    "item10-autobrake-to-sup|Item 10 (Auto Brake): move the Auto Brake to the SUP position and stop there."
    "item10-autobrake-to-cs|Item 10 (Auto Brake): move the Auto Brake to the CS position and stop there."
    "item10-autobrake-to-emg|Item 10 (Auto Brake): move the Auto Brake to the EMG (Emergency) position and stop there."

    # ---- Item 11: Independent Brake (continuous + bail-off positions) ----
    "item11-indepbrake-to-release|Item 11 (Independent Brake): move to the RELEASE position (no brake) and stop there."
    "item11-indepbrake-to-full-apply|Item 11 (Independent Brake): move to the FULL APPLY position and stop there."
    "item11-indepbrake-bailoff-on|Item 11 (Independent Brake): move to the BAIL-OFF ON position and stop there."
    "item11-indepbrake-bailoff-off|Item 11 (Independent Brake): move to the BAIL-OFF OFF position and stop there."

    # ---- Item 12: Wiper (3-position switch) ----
    "item12-wiper-to-off|Item 12 (Wiper): set the Wiper switch to OFF and stop there."
    "item12-wiper-to-slow|Item 12 (Wiper): set the Wiper switch to SLOW and stop there."
    "item12-wiper-to-full|Item 12 (Wiper): set the Wiper switch to FULL and stop there."

    # ---- Item 13: Lights (3-position switch) ----
    "item13-lights-to-off|Item 13 (Lights): set the Lights switch to OFF and stop there."
    "item13-lights-to-dim|Item 13 (Lights): set the Lights switch to DIM and stop there."
    "item13-lights-to-full|Item 13 (Lights): set the Lights switch to FULL and stop there."

    # ---- Items 14-41: 28 user-assignable buttons (2 x 14 layout) ----
    # Row 1 = top row, left to right (closest to the player). Row 2 = bottom row, left to right.
    "item14-button-row1-pos1-press-and-release|Item 14 (user-assignable button, ROW 1 POS 1, leftmost top-row): press, hold ~1 s, release."
    "item15-button-row1-pos2-press-and-release|Item 15 (user-assignable button, ROW 1 POS 2): press, hold ~1 s, release."
    "item16-button-row1-pos3-press-and-release|Item 16 (user-assignable button, ROW 1 POS 3): press, hold ~1 s, release."
    "item17-button-row1-pos4-press-and-release|Item 17 (user-assignable button, ROW 1 POS 4): press, hold ~1 s, release."
    "item18-button-row1-pos5-press-and-release|Item 18 (user-assignable button, ROW 1 POS 5): press, hold ~1 s, release."
    "item19-button-row1-pos6-press-and-release|Item 19 (user-assignable button, ROW 1 POS 6): press, hold ~1 s, release."
    "item20-button-row1-pos7-press-and-release|Item 20 (user-assignable button, ROW 1 POS 7): press, hold ~1 s, release."
    "item21-button-row1-pos8-press-and-release|Item 21 (user-assignable button, ROW 1 POS 8): press, hold ~1 s, release."
    "item22-button-row1-pos9-press-and-release|Item 22 (user-assignable button, ROW 1 POS 9): press, hold ~1 s, release."
    "item23-button-row1-pos10-press-and-release|Item 23 (user-assignable button, ROW 1 POS 10): press, hold ~1 s, release."
    "item24-button-row1-pos11-press-and-release|Item 24 (user-assignable button, ROW 1 POS 11): press, hold ~1 s, release."
    "item25-button-row1-pos12-press-and-release|Item 25 (user-assignable button, ROW 1 POS 12): press, hold ~1 s, release."
    "item26-button-row1-pos13-press-and-release|Item 26 (user-assignable button, ROW 1 POS 13): press, hold ~1 s, release."
    "item27-button-row1-pos14-press-and-release|Item 27 (user-assignable button, ROW 1 POS 14, rightmost top-row): press, hold ~1 s, release."
    "item28-button-row2-pos1-press-and-release|Item 28 (user-assignable button, ROW 2 POS 1, leftmost bottom-row): press, hold ~1 s, release."
    "item29-button-row2-pos2-press-and-release|Item 29 (user-assignable button, ROW 2 POS 2): press, hold ~1 s, release."
    "item30-button-row2-pos3-press-and-release|Item 30 (user-assignable button, ROW 2 POS 3): press, hold ~1 s, release."
    "item31-button-row2-pos4-press-and-release|Item 31 (user-assignable button, ROW 2 POS 4): press, hold ~1 s, release."
    "item32-button-row2-pos5-press-and-release|Item 32 (user-assignable button, ROW 2 POS 5): press, hold ~1 s, release."
    "item33-button-row2-pos6-press-and-release|Item 33 (user-assignable button, ROW 2 POS 6): press, hold ~1 s, release."
    "item34-button-row2-pos7-press-and-release|Item 34 (user-assignable button, ROW 2 POS 7): press, hold ~1 s, release."
    "item35-button-row2-pos8-press-and-release|Item 35 (user-assignable button, ROW 2 POS 8): press, hold ~1 s, release."
    "item36-button-row2-pos9-press-and-release|Item 36 (user-assignable button, ROW 2 POS 9): press, hold ~1 s, release."
    "item37-button-row2-pos10-press-and-release|Item 37 (user-assignable button, ROW 2 POS 10): press, hold ~1 s, release."
    "item38-button-row2-pos11-press-and-release|Item 38 (user-assignable button, ROW 2 POS 11): press, hold ~1 s, release."
    "item39-button-row2-pos12-press-and-release|Item 39 (user-assignable button, ROW 2 POS 12): press, hold ~1 s, release."
    "item40-button-row2-pos13-press-and-release|Item 40 (user-assignable button, ROW 2 POS 13): press, hold ~1 s, release."
    "item41-button-row2-pos14-press-and-release|Item 41 (user-assignable button, ROW 2 POS 14, rightmost bottom-row): press, hold ~1 s, release."

    # ---- Item 42: user-assignable SPDT (up/off/down) ----
    "item42-user-spdt-press-up-and-release|Item 42 (user-assignable SPDT): press the switch UP, hold ~1 s, release."
    "item42-user-spdt-press-down-and-release|Item 42 (user-assignable SPDT): press the switch DOWN, hold ~1 s, release."

    # ---- Item 43: user-assignable hat switch (up/right/down/left) ----
    "item43-hat-press-up-and-release|Item 43 (user-assignable hat switch): press the hat UP, hold ~1 s, release."
    "item43-hat-press-right-and-release|Item 43 (user-assignable hat switch): press the hat RIGHT, hold ~1 s, release."
    "item43-hat-press-down-and-release|Item 43 (user-assignable hat switch): press the hat DOWN, hold ~1 s, release."
    "item43-hat-press-left-and-release|Item 43 (user-assignable hat switch): press the hat LEFT, hold ~1 s, release."
)

idx=$START
recorded=0
skipped=0

record_one() {
    local entry="$1"
    local label description
    label="${entry%%|*}"
    description="${entry#*|}"

    while true; do
        local file path bin pid ans
        file=$(printf '%03d-%s.log' "$idx" "$label")
        path="$OUTDIR/$file"
        bin="${path%.log}.bin"

        echo
        echo "===================================================================="
        printf '  [%d] %s\n' "$idx" "$label"
        echo
        printf '  %s\n' "$description"
        echo
        echo "  RECORDING. Press Enter when DONE.   [r]=redo  [s]=skip  [q]=quit"

        sudo dd if="$DEV" of="$bin" bs=14 status=none &
        pid=$!
        IFS= read -r ans
        sudo kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true

        case "${ans:-}" in
            q|Q)
                sudo rm -f "$bin"
                echo "  Quitting (recorded=$recorded, skipped=$skipped)."
                exit 0
                ;;
            s|S)
                sudo rm -f "$bin"
                echo "  -> skipped"
                skipped=$((skipped+1))
                idx=$((idx+1))
                return
                ;;
            r|R)
                sudo rm -f "$bin"
                echo "  -> redoing this action"
                continue
                ;;
            *)
                if [[ -s "$bin" ]]; then
                    sudo chown "$USER:$USER" "$bin" 2>/dev/null || true
                    xxd -c 14 "$bin" > "$path"
                    sudo rm -f "$bin"
                    local n unique
                    n=$(wc -l < "$path")
                    unique=$(awk '{print $2,$3,$4,$5,$6,$7,$8}' "$path" 2>/dev/null | sort -u | wc -l)
                    echo "  -> saved $file ($n reports, $unique unique payloads)"
                    if [[ "$unique" -lt 2 ]]; then
                        echo "     NOTE: only one payload in this file; the action may"
                        echo "           not have produced any byte changes. Consider 'r' to redo."
                    fi
                    recorded=$((recorded+1))
                else
                    sudo rm -f "$bin"
                    echo "  WARNING: no data captured (the device may be busy)."
                fi
                idx=$((idx+1))
                return
                ;;
        esac
    done
}

for entry in "${ACTIONS[@]}"; do
    record_one "$entry"
done

echo
echo "===================================================================="
echo "DONE. Recorded=$recorded, skipped=$skipped, total actions=${#ACTIONS[@]}."
echo "Files in $OUTDIR/ matching the inventory:"
ls -1 "$OUTDIR" | grep -E '^[0-9]+-item[0-9]+' | head -10
total_files=$(ls -1 "$OUTDIR" | grep -cE '^[0-9]+-item[0-9]+')
test "$total_files" -gt 10 && echo "(... $total_files total, run \`ls $OUTDIR\` to see all)"
