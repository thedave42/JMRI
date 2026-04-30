#!/usr/bin/env bash
# Phase 2: per-action RailDriver HID capture, advanced manually with Enter.
#
# For each action in the predefined list:
#   1. Script prints the action label and starts recording immediately.
#   2. You perform the action at your own pace (no time limit, no countdown).
#   3. Press Enter ONCE when you are DONE with that action.
#   4. The capture for that action is saved to its own labelled file, and
#      the script moves on to the next action.
#
# Single-character options at each Enter prompt:
#   <empty>   accept and continue
#   r         redo this action (capture again)
#   s         skip this action (no file written)
#   q         quit immediately
#
# Output (under $RD_OUTDIR, default ~/rd-capture/):
#   NNN-<action_label>.log    one xxd -c 14 file per accepted action
#
# Pre-flight:
#   - Quit JMRI completely so it isn't holding the device.
#
# Environment knobs:
#   RD_HIDRAW    /dev/hidrawN node for the RailDriver (default /dev/hidraw0)
#   RD_OUTDIR    output directory (default ~/rd-capture)
#   RD_START     numeric prefix offset (default 100)
#
# See issue #1 for the broader capture plan.

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

# Predefined action list. Performed in order. One per output file.
ACTIONS=(
    "throttle_centre_to_full_forward_and_back_to_centre"
    "throttle_centre_to_full_back_and_back_to_centre"
    "reverser_neutral_to_forward_and_back_to_neutral"
    "reverser_neutral_to_reverse_and_back_to_neutral"
    "autobrake_release_to_full_apply_and_back_to_release"
    "indepbrake_release_to_full_apply_and_back_to_release"
    "bailoff_press_right_and_release"
    "wiper_rotary_through_all_positions"
    "lights_rotary_through_all_positions"
    "bell_press_and_release"
    "horn_lever_forward_and_back_to_centre"
    "horn_lever_back_and_back_to_centre"
    "zoom_rocker_up_press_and_release"
    "zoom_rocker_down_press_and_release"
    "pov_up_press_and_release"
    "pov_down_press_and_release"
    "pov_left_press_and_release"
    "pov_right_press_and_release"
    "gear_up_press_and_release"
    "gear_down_press_and_release"
    "estop_up_press_and_release"
    "estop_down_press_and_release"
    "alerter_press_and_release"
    "sander_press_and_release"
    "pantograph_press_and_release"
    "blue_button_row1_pos1_press_and_release"
    "blue_button_row1_pos2_press_and_release"
    "blue_button_row1_pos3_press_and_release"
    "blue_button_row1_pos4_press_and_release"
    "blue_button_row1_pos5_press_and_release"
    "blue_button_row1_pos6_press_and_release"
    "blue_button_row1_pos7_press_and_release"
    "blue_button_row1_pos8_press_and_release"
    "blue_button_row1_pos9_press_and_release"
    "blue_button_row1_pos10_press_and_release"
    "blue_button_row1_pos11_press_and_release"
    "blue_button_row1_pos12_press_and_release"
    "blue_button_row1_pos13_press_and_release"
    "blue_button_row1_pos14_press_and_release"
    "blue_button_row2_pos1_press_and_release"
    "blue_button_row2_pos2_press_and_release"
    "blue_button_row2_pos3_press_and_release"
    "blue_button_row2_pos4_press_and_release"
    "blue_button_row2_pos5_press_and_release"
    "blue_button_row2_pos6_press_and_release"
    "blue_button_row2_pos7_press_and_release"
    "blue_button_row2_pos8_press_and_release"
    "blue_button_row2_pos9_press_and_release"
    "blue_button_row2_pos10_press_and_release"
    "blue_button_row2_pos11_press_and_release"
    "blue_button_row2_pos12_press_and_release"
    "blue_button_row2_pos13_press_and_release"
    "blue_button_row2_pos14_press_and_release"
)

idx=$START
recorded=0
skipped=0

record_one() {
    local label="$1"
    local sanitized
    sanitized=$(echo "$label" | tr '[:upper:] ' '[:lower:]_' | tr -cd 'a-z0-9_-')
    while true; do
        local file path bin pid ans
        file=$(printf '%03d-%s.log' "$idx" "$sanitized")
        path="$OUTDIR/$file"
        bin="${path%.log}.bin"

        echo
        echo "===================================================================="
        printf '  ACTION (%d): %s\n' "$idx" "$label"
        echo "  RECORDING. Perform the action, then press Enter."
        echo "  [Enter]=accept  [r]=redo  [s]=skip  [q]=quit"

        # Capture raw bytes from the device. dd uses raw syscalls (no stdio
        # buffering) so killing it cannot lose the trailing data.
        sudo dd if="$DEV" of="$bin" bs=14 status=none &
        pid=$!

        # Read a single line from the user. They press Enter when done.
        IFS= read -r ans

        # Stop the capture.
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
                        echo "           not have produced any byte changes. Use 'r' to redo."
                    fi
                    recorded=$((recorded+1))
                else
                    sudo rm -f "$bin"
                    echo "  WARNING: no data captured for $label (the device may be busy)"
                fi
                idx=$((idx+1))
                return
                ;;
        esac
    done
}

for action in "${ACTIONS[@]}"; do
    record_one "$action"
done

echo
echo "===================================================================="
echo "DONE. Recorded=$recorded, skipped=$skipped."
echo "Files in $OUTDIR/:"
ls -1 "$OUTDIR" | grep '\.log$' | head -10
test "$(ls -1 "$OUTDIR" | grep -c '\.log$')" -gt 10 && echo "(... more, run \`ls $OUTDIR\` to see all)"
