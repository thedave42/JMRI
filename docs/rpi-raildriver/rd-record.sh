#!/usr/bin/env bash
# Phase 2: Continuous RailDriver capture with post-hoc segmentation.
#
# Workflow (no walking back and forth needed):
#   1. Run this script at the keyboard.
#   2. Press Enter once when prompted.
#   3. Walk to the controller. There is NO time pressure.
#   4. Perform every action in the predefined list in order, leaving a clear
#      ~3-second pause between actions. The pauses are how the segmenter
#      knows where one action ends and the next begins.
#   5. Walk back to the keyboard and press Ctrl-C ONCE.
#   6. The script automatically splits the raw capture at idle gaps and
#      labels each segment from the predefined action list.
#
# Output (under $RD_OUTDIR, default ~/rd-capture/):
#   raw-capture-YYYYMMDD-HHMMSS.log   one big timestamped capture
#   001-throttle_centre_to_full_forward.log
#   002-throttle_centre_to_full_back.log
#   ...
#   segmentation-summary.txt          summary of what was found
#
# Pre-flight:
#   - Quit JMRI completely so it isn't holding the device.
#   - sudo apt install -y moreutils    (provides `ts` for timestamps)
#
# Environment knobs:
#   RD_HIDRAW    /dev/hidrawN node for the RailDriver (default /dev/hidraw0)
#   RD_OUTDIR    output directory (default ~/rd-capture)
#   RD_IDLE_GAP  seconds of unchanged bytes that ends a segment (default 3.0)
#   RD_GRACE     countdown seconds before recording starts (default 8)
#
# See issue #1 for the broader capture plan.

set -uo pipefail

DEV="${RD_HIDRAW:-/dev/hidraw0}"
OUTDIR="${RD_OUTDIR:-${HOME}/rd-capture}"
IDLE_GAP="${RD_IDLE_GAP:-3.0}"
GRACE="${RD_GRACE:-8}"

mkdir -p "$OUTDIR"

if ! command -v ts >/dev/null 2>&1; then
    echo "ERROR: 'ts' not found. Install: sudo apt install -y moreutils" >&2
    exit 1
fi
if [[ ! -e "$DEV" ]]; then
    echo "ERROR: $DEV does not exist. Set RD_HIDRAW=/dev/hidrawN." >&2
    exit 1
fi

# ---- Predefined action list (50 actions) -----------------------------------
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

# ---- Print cheat sheet -----------------------------------------------------
clear || true
cat <<'EOF'
====================================================================
 RailDriver continuous-capture session
====================================================================

This script records a single long capture from /dev/hidraw0 while you
work the controller, then post-processes the capture to split it at
idle gaps and label each segment from the action list below.

Workflow:
  1. Press Enter at this keyboard.
  2. You'll get an 8-second countdown to walk to the controller.
  3. Perform each action in the listed order. Pause for at least
     3 SECONDS between actions (a clear "rest" beat) so the
     segmenter can tell where one action ends and the next begins.
  4. Walk back to the keyboard and press Ctrl-C ONE TIME.
  5. Segmentation runs automatically.

Tips:
  - Print this list, or photograph it on your phone, and keep it
     visible while you're at the controller.
  - If you flub one action, just continue: every action lands
     somewhere in the segment list, and you can re-record only
     the bad ones manually later (the raw file is preserved).
  - The terminal will beep at the start and at the end of recording.
EOF

printf "\nACTION LIST (perform in this exact order, ~3s pause between each):\n\n"
i=0
for a in "${ACTIONS[@]}"; do
    i=$((i+1))
    printf '  %2d. %s\n' "$i" "${a//_/ }"
done

printf "\nTotal: %d actions.\n\n" "${#ACTIONS[@]}"

read -r -p "Press Enter when you have the cheat sheet handy and are ready... "

# ---- Countdown -------------------------------------------------------------
echo
echo "Recording starts after this countdown. Walk to the controller now."
for ((s = GRACE; s > 0; s--)); do
    printf '  %ds...\r' "$s"
    sleep 1
done
printf 'GO!     \n'
printf '\a'

# ---- Capture ---------------------------------------------------------------
RAW="$OUTDIR/raw-capture-$(date +%Y%m%d-%H%M%S).log"
echo ">>> Capturing to $RAW"
echo ">>> Press Ctrl-C ONCE when finished with all actions."

sudo -v || exit 1

capture_pid=
on_int() {
    if [[ -n "$capture_pid" ]] && kill -0 "$capture_pid" 2>/dev/null; then
        sudo kill -INT "$capture_pid" 2>/dev/null || true
        wait "$capture_pid" 2>/dev/null || true
    fi
}
trap on_int INT

sudo -n bash -c "exec xxd -c 14 '$DEV' | ts '%.s' > '$RAW'" &
capture_pid=$!

wait "$capture_pid" 2>/dev/null || true
trap - INT
printf '\a'
echo
echo ">>> Capture stopped."

if [[ ! -s "$RAW" ]]; then
    echo "ERROR: $RAW is empty. Did the device send any reports?" >&2
    exit 1
fi
echo "    raw size: $(wc -l < "$RAW") lines, $(stat -c%s "$RAW") bytes"

# ---- Segment ---------------------------------------------------------------
echo
echo ">>> Segmenting (idle threshold ${IDLE_GAP}s)..."

ACTLIST=$(mktemp)
printf '%s\n' "${ACTIONS[@]}" > "$ACTLIST"

python3 - "$RAW" "$OUTDIR" "$ACTLIST" "$IDLE_GAP" <<'PYEOF'
import sys, os, re

raw_path, outdir, actions_path, idle_gap_s = sys.argv[1:]
idle_gap = float(idle_gap_s)

with open(actions_path) as f:
    actions = [line.strip() for line in f if line.strip()]

hex_pattern = re.compile(r'[0-9a-fA-F]{2}')

segments = []
current = None
last_change_ts = None
last_payload = None
total_lines = 0

with open(raw_path) as f:
    for line in f:
        line = line.rstrip("\n")
        if not line:
            continue
        total_lines += 1
        try:
            ts_str, rest = line.split(" ", 1)
            ts = float(ts_str)
        except ValueError:
            continue
        try:
            _, hex_part = rest.split(":", 1)
        except ValueError:
            hex_part = rest
        hex_only = "".join(hex_pattern.findall(hex_part.split("  ")[0]))
        payload = hex_only[:28]
        if len(payload) < 28:
            continue

        if last_payload is None:
            last_payload = payload
            last_change_ts = ts
            continue

        if payload != last_payload:
            if current is None:
                current = {'start': ts, 'end': ts, 'lines': []}
            current['lines'].append(line)
            current['end'] = ts
            last_payload = payload
            last_change_ts = ts
        else:
            if current is not None and (ts - last_change_ts) >= idle_gap:
                segments.append(current)
                current = None

if current is not None and current['lines']:
    segments.append(current)

print(f"  raw lines parsed       : {total_lines}")
print(f"  segments found         : {len(segments)}")
print(f"  actions in cheat sheet : {len(actions)}")

def safe(s):
    return re.sub(r'[^a-z0-9._-]', '_', s.lower())

written = []
for i, seg in enumerate(segments):
    if i < len(actions):
        name = f"{i+1:03d}-{safe(actions[i])}.log"
    else:
        name = f"{i+1:03d}-extra.log"
    path = os.path.join(outdir, name)
    with open(path, "w") as g:
        g.write("\n".join(seg['lines']) + "\n")
    written.append((name, len(seg['lines']), seg['start'], seg['end']))

summary_path = os.path.join(outdir, "segmentation-summary.txt")
with open(summary_path, "w") as f:
    f.write(f"raw file:                {raw_path}\n")
    f.write(f"idle threshold (s):      {idle_gap}\n")
    f.write(f"segments found:          {len(segments)}\n")
    f.write(f"actions in cheat sheet:  {len(actions)}\n\n")
    f.write(f"{'idx':>3}  {'reports':>7}  {'duration_s':>10}  filename\n")
    for i, (name, n, s, e) in enumerate(written):
        f.write(f"{i+1:>3}  {n:>7}  {e-s:>10.2f}  {name}\n")
    if len(segments) != len(actions):
        f.write("\n")
        if len(segments) < len(actions):
            f.write(f"WARNING: missing {len(actions) - len(segments)} action(s) "
                    f"at the end of the recording.\n")
        else:
            f.write(f"WARNING: {len(segments) - len(actions)} extra segment(s) "
                    f"recorded; saved as 'NNN-extra.log'.\n")

print()
print("Per-segment file list (idx, #reports, duration, filename):")
for i, (name, n, s, e) in enumerate(written):
    print(f"  {i+1:>3}  {n:>7}  {e-s:>6.2f}s  {name}")

print()
print(f"Summary written to: {summary_path}")
if len(segments) != len(actions):
    print()
    print("MISMATCH between segment count and action count.")
    if len(segments) < len(actions):
        print(f"  -> missing {len(actions) - len(segments)} action(s) at the end")
        print("     Re-run the script and finish the action list, OR")
        print("     manually rename and add files for the missing actions.")
    else:
        print(f"  -> {len(segments) - len(actions)} extra segment(s); these are")
        print("     saved as NNN-extra.log. Likely causes: an accidental control")
        print("     bump between actions, or two actions performed without enough")
        print("     pause between them. Review the 'extra' files and rename or")
        print("     discard as appropriate.")
PYEOF

rm -f "$ACTLIST"

echo
echo "DONE."
echo "Raw capture preserved at: $RAW"
echo "Per-action files and summary in: $OUTDIR"
