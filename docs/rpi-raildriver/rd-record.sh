#!/usr/bin/env bash
# Phase 2: per-action RailDriver HID capture with baseline + diff verification.
#
# Workflow:
#
#   Step 0 -- BASELINE.
#     The user is asked to place EVERY control at its named rest position
#     (Reverser:Neutral, Throttle:Idle, Auto Brake:Release, Indep Brake:
#     Release, Wiper:Off, Lights:Off; no buttons or switches held). The
#     script then records ~1 s of /dev/hidraw0 traffic and verifies the
#     reports during that window are all identical. The single payload
#     is saved as 000-baseline.log.
#
#     Once a baseline exists, every subsequent test's diff identifies
#     which byte(s) the user moved -- so the script can verify that the
#     user did the right action without needing to know the byte-to-
#     control mapping ahead of time.
#
#   Step 1 -- per-action tests.
#     For each action in the list, the script announces what to do,
#     starts a continuous capture, waits for the user to press Enter,
#     stops the capture, then:
#       * extracts the FIRST and LAST 14-byte payloads from the capture
#       * diffs each against the baseline
#       * reports which bytes varied during the capture and what
#         distinct values each saw
#       * prompts the user to accept / redo / skip / quit
#
#     For multi-position controls the prompt asks for a sweep through
#     every named position (returning to rest at the end). For SPDT
#     switches and buttons the prompt asks for a single press+release.
#     For the hat switch each direction is its own capture.
#
# Per-prompt options:
#   <Enter>   accept and continue
#   r         redo this action (capture again)
#   s         skip this action (no file written)
#   q         quit immediately
#
# Output (under $RD_OUTDIR, default ~/rd-capture/):
#   <prefix-><000-baseline.log>
#   <prefix-><NNN-itemNN-*.log>
#   ... etc, in inventory order.
#
# Pre-flight:
#   - Quit JMRI completely so it isn't holding the device.
#   - sudo apt install -y xxd  (already present on most systems)
#
# Command-line options:
#   -o PREFIX   Prepend PREFIX (followed by a "-" separator) to every
#               output filename. Use this to produce multiple independent
#               data sets in the same output directory across multiple
#               runs of the script. Example:
#                 rd-record.sh -o test1   ->  test1-000-baseline.log,
#                                              test1-100-item01-range-up.log,
#                                              ...
#               PREFIX is sanitized to [a-zA-Z0-9_-]; other characters
#               are stripped. Empty prefix (the default) preserves the
#               original filenames "000-baseline.log", "100-...", etc.
#   -h          Print this usage message and exit.
#
# Environment knobs:
#   RD_HIDRAW    /dev/hidrawN node (default /dev/hidraw0)
#   RD_OUTDIR    output directory (default ~/rd-capture)
#   RD_START     numeric prefix offset for action files (default 100)
#
# See issue #1 for the broader plan and
# docs/rpi-raildriver/control-inventory.md for the inventory.

set -uo pipefail

DEV="${RD_HIDRAW:-/dev/hidraw0}"
OUTDIR="${RD_OUTDIR:-${HOME}/rd-capture}"
START="${RD_START:-100}"
PREFIX=""

usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^#//; s/^ //'
    exit "${1:-0}"
}

while getopts ":o:h" opt; do
    case "$opt" in
        o) PREFIX="$OPTARG" ;;
        h) usage 0 ;;
        \?) echo "ERROR: unknown option -$OPTARG" >&2; usage 2 ;;
        :)  echo "ERROR: option -$OPTARG requires an argument" >&2; usage 2 ;;
    esac
done
shift $((OPTIND - 1))

# Sanitize the prefix to safe filename characters.
PREFIX=$(echo "$PREFIX" | tr -cd 'a-zA-Z0-9_-')

# When non-empty, file_prefix is "<prefix>-"; when empty, it's "".
# Used as a literal string in front of every output filename.
if [[ -n "$PREFIX" ]]; then
    FILE_PREFIX="${PREFIX}-"
else
    FILE_PREFIX=""
fi

mkdir -p "$OUTDIR"

if [[ ! -e "$DEV" ]]; then
    echo "ERROR: $DEV does not exist. Set RD_HIDRAW=/dev/hidrawN." >&2
    exit 1
fi

echo ">>> Output directory : $OUTDIR"
echo ">>> Device           : $DEV"
if [[ -n "$PREFIX" ]]; then
    echo ">>> Filename prefix  : $PREFIX (files will be named ${FILE_PREFIX}NNN-...)"
else
    echo ">>> Filename prefix  : (none)"
fi
echo ">>> sudo will be requested once for /dev/hidraw access"
sudo -v || exit 1

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Extract the 14-byte hex payload (28 lower-case hex chars, no spaces) from
# the Nth line of an xxd -c 14 file. Args: <file> <line-spec, e.g. "1" or "$">
extract_payload() {
    local file="$1"
    local line_spec="$2"
    awk -v ln="$line_spec" '
        BEGIN { target = (ln == "$") ? -1 : ln + 0 }
        {
            gsub(/^[0-9a-fA-F]+: */, "")
            sub(/ +[^ ]+$/, "")
            gsub(/ /, "")
            if (target == -1) { last = tolower($0) }
            else if (NR == target) { print tolower($0); exit }
        }
        END { if (target == -1 && last != "") print last }
    ' "$file"
}

# Compare two 28-char hex payloads. Print, one per line, the byte indices
# (0..13) that differ.
diff_payloads() {
    local a="$1" b="$2"
    local i
    for ((i = 0; i < 14; i++)); do
        local ah="${a:$((i*2)):2}"
        local bh="${b:$((i*2)):2}"
        if [[ "$ah" != "$bh" ]]; then
            printf '%d ' "$i"
        fi
    done
    echo
}

# For a captured xxd file, report which byte positions varied at any point
# in the file (compared to the baseline payload). For each varying byte,
# list the distinct values seen.
report_capture() {
    local file="$1"
    local baseline_payload="$2"

    local n_reports
    n_reports=$(wc -l < "$file")
    echo "    reports captured       : $n_reports"

    # Single python invocation. File path is passed as arg; nothing piped to
    # stdin, so there is no collision with the python -c form.
    python3 -c '
import sys, re
baseline = sys.argv[1]
path = sys.argv[2]
hex_re = re.compile(r"[0-9a-fA-F]{2}")
seen = [set() for _ in range(14)]
unique_payloads = set()
for line in open(path):
    try:
        _, rest = line.split(":", 1)
    except ValueError:
        continue
    hex_part = rest.split("  ")[0]
    pairs = hex_re.findall(hex_part)
    if len(pairs) < 14:
        continue
    payload = "".join(p.lower() for p in pairs[:14])
    unique_payloads.add(payload)
    for i in range(14):
        seen[i].add(pairs[i].lower())
print(f"    distinct payloads      : {len(unique_payloads)}")
diffs = []
for i, vals in enumerate(seen):
    bv = baseline[2*i:2*i+2]
    if any(v != bv for v in vals):
        diffs.append((i, sorted(vals), bv))
if not diffs:
    print("    bytes that varied      : NONE -- the user may not have done anything")
else:
    print("    bytes that varied vs baseline:")
    for i, vals, bv in diffs:
        labelled = []
        for v in vals:
            if v == bv:
                labelled.append(f"{v}(rest)")
            else:
                labelled.append(v)
        print(f"      byte {i:>2} (rest={bv}): {chr(32).join(labelled)}  [{len(vals)} distinct]")
' "$baseline_payload" "$file"
}

# Capture in the background until the user presses Enter, then stop.
# Args: <bin output path>
# Returns: pid of the background dd in the global capture_pid var.
capture_pid=""
start_capture() {
    local bin="$1"
    sudo -n dd if="$DEV" of="$bin" bs=14 status=none &
    capture_pid=$!
}
stop_capture() {
    if [[ -n "$capture_pid" ]] && kill -0 "$capture_pid" 2>/dev/null; then
        sudo kill "$capture_pid" 2>/dev/null || true
        wait "$capture_pid" 2>/dev/null || true
    fi
    capture_pid=""
}

# ---------------------------------------------------------------------------
# Step 0 -- baseline
# ---------------------------------------------------------------------------

establish_baseline() {
    local baseline_log="$OUTDIR/${FILE_PREFIX}000-baseline.log"
    while true; do
        cat <<'BASELINE_PROMPT'

====================================================================
  STEP 0 -- BASELINE
====================================================================

  Place EVERY control at its rest position:

    Reverser           -> Neutral (centre detent)
    Throttle/Dyn-Brake -> Idle (centre)
    Auto Brake         -> Release
    Independent Brake  -> Release
    Wiper              -> Off
    Lights             -> Off
    All buttons / SPDT switches -> not pressed, not held
    Hat switch         -> at centre, not pressed in any direction

  Once everything is at rest, press Enter. Recording starts on Enter
  and runs for ~1 second to verify the report stays steady.
BASELINE_PROMPT
        read -r -p "  [Enter]=record baseline  q=quit  : " ans
        if [[ "${ans:-}" =~ ^[qQ]$ ]]; then
            echo "Quit."
            exit 0
        fi

        local bin="$OUTDIR/${FILE_PREFIX}000-baseline.bin"
        echo "  Recording baseline (1 second)..."
        sudo -n timeout 1 dd if="$DEV" of="$bin" bs=14 status=none 2>/dev/null || true

        if [[ ! -s "$bin" ]]; then
            sudo rm -f "$bin"
            echo "  ERROR: no data captured. Is the device sending reports?"
            echo "         Try wiggling a control briefly and press Enter again."
            continue
        fi

        sudo chown "$USER:$USER" "$bin" 2>/dev/null || true
        xxd -c 14 "$bin" > "$baseline_log"
        sudo rm -f "$bin"

        local n_unique baseline_payload
        n_unique=$(awk '{
            gsub(/^[0-9a-fA-F]+: */, "")
            sub(/ +[^ ]+$/, "")
            gsub(/ /, "")
            print tolower($0)
        }' "$baseline_log" | sort -u | wc -l)

        if [[ "$n_unique" -ne 1 ]]; then
            echo "  WARNING: $n_unique distinct payloads in the baseline window."
            echo "           Something was moving. Holding everything STILL is required."
            awk '{
                gsub(/^[0-9a-fA-F]+: */, "")
                sub(/ +[^ ]+$/, "")
                gsub(/ /, "")
                print tolower($0)
            }' "$baseline_log" | sort | uniq -c | sort -rn | head -5 \
                | awk '{printf "             seen %d time(s): %s\n", $1, $2}'
            read -r -p "  [Enter]=retry  a=accept anyway (use most-frequent)  q=quit  : " ans2
            case "${ans2:-}" in
                q|Q) exit 0 ;;
                a|A)
                    baseline_payload=$(awk '{
                        gsub(/^[0-9a-fA-F]+: */, "")
                        sub(/ +[^ ]+$/, "")
                        gsub(/ /, "")
                        print tolower($0)
                    }' "$baseline_log" | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')
                    BASELINE_PAYLOAD="$baseline_payload"
                    echo "  Accepted baseline payload: $baseline_payload"
                    return
                    ;;
                *)
                    rm -f "$baseline_log"
                    continue
                    ;;
            esac
        fi

        baseline_payload=$(extract_payload "$baseline_log" 1)
        BASELINE_PAYLOAD="$baseline_payload"
        echo "  Baseline payload: $baseline_payload"
        echo "  Saved to $baseline_log"
        return
    done
}

establish_baseline
echo
echo "===================================================================="
echo "  Baseline established. Proceeding to per-action tests."
echo "  Between each test, return all controls to baseline rest positions."
echo "===================================================================="

# ---------------------------------------------------------------------------
# Action list. Each entry: "label|description"
# ---------------------------------------------------------------------------

ACTIONS=(

    # --- Item 1: Range (SPDT momentary, up/off/down) ---
    "item01-range-up|Item 1 (Range, SPDT momentary):
    Press the Range switch UP and HOLD for ~1 second, then release.
    The switch is spring-return; centre is rest."

    "item01-range-down|Item 1 (Range, SPDT momentary):
    Press the Range switch DOWN and HOLD for ~1 second, then release."

    # --- Item 2: E-Stop (SPDT momentary) ---
    "item02-estop-up|Item 2 (E-Stop, SPDT momentary):
    Press the E-Stop switch UP and HOLD ~1 s, then release."

    "item02-estop-down|Item 2 (E-Stop, SPDT momentary):
    Press the E-Stop switch DOWN and HOLD ~1 s, then release."

    # --- Items 3-6: simple buttons ---
    "item03-alert|Item 3 (Alert button):
    Press and HOLD the Alert button ~1 s, then release."

    "item04-sand|Item 4 (Sand button):
    Press and HOLD the Sand button ~1 s, then release."

    "item05-p|Item 5 (P button, assumed Pantograph):
    Press and HOLD the P button ~1 s, then release."

    "item06-bell|Item 6 (Bell button):
    Press and HOLD the Bell button ~1 s, then release."

    # --- Item 7: Horn (SPDT momentary) ---
    "item07-horn-up|Item 7 (Horn, SPDT momentary):
    Push the Horn lever UP and HOLD ~1 s, then release."

    "item07-horn-down|Item 7 (Horn, SPDT momentary):
    Push the Horn lever DOWN and HOLD ~1 s, then release."

    # --- Item 8: Reverser (3 detents) ---
    "item08-reverser-sweep|Item 8 (Reverser, 3 physical detents):
    From baseline (NEUTRAL), perform this sweep:
       Forward (pause ~1 s)
    -> Neutral (pause ~1 s)
    -> Reverse (pause ~1 s)
    -> back to Neutral.
    Press Enter when done."

    # --- Item 9: Throttle / Dyn-Brake (continuous bipolar) ---
    "item09-throttle-dynbrake-sweep|Item 9 (Throttle/Dyn-Brake, continuous bipolar):
    From baseline (Idle, centre), perform this sweep:
       Push DOWN to maximum throttle (pause ~1 s)
    -> back to Idle (centre, pause ~1 s)
    -> Pull UP to maximum dynamic brake (pause ~1 s)
    -> back to Idle.
    Press Enter when done."

    # --- Item 10: Auto Brake (continuous, 4 named positions) ---
    "item10-autobrake-sweep|Item 10 (Auto Brake):
    From baseline (Release), perform this sweep:
       Move to SUP (pause ~1 s)
    -> CS (pause ~1 s)
    -> EMG (pause ~1 s)
    -> back to Release.
    Press Enter when done."

    # --- Item 11a: Independent Brake lever (continuous) ---
    "item11a-indepbrake-lever-sweep|Item 11a (Independent Brake LEVER ONLY -- not bail-off):
    From baseline (Release), perform this sweep:
       Move to FULL APPLY (pause ~1 s)
    -> back to Release.
    Do NOT engage the bail-off; that is the next test.
    Press Enter when done."

    # --- Item 11b: Bail-off (spring-loaded, button-style) ---
    "item11b-indepbrake-bailoff|Item 11b (Independent Brake BAIL-OFF only):
    With the Independent Brake LEVER at Release (baseline),
    engage the spring-loaded bail-off and HOLD ~1 s, then release.
    (The bail-off does not stay engaged on its own.)
    Press Enter when done."

    # --- Item 12: Wiper (3-position switch) ---
    "item12-wiper-sweep|Item 12 (Wiper, 3-position switch):
    From baseline (Off), perform this sweep:
       Move to SLOW (pause ~1 s)
    -> FULL (pause ~1 s)
    -> back to Off.
    Press Enter when done."

    # --- Item 13: Lights (3-position switch) ---
    "item13-lights-sweep|Item 13 (Lights, 3-position switch):
    From baseline (Off), perform this sweep:
       Move to DIM (pause ~1 s)
    -> FULL (pause ~1 s)
    -> back to Off.
    Press Enter when done."

    # --- Items 14-41: 28 user-assignable buttons (2 x 14 layout) ---
    "item14-button-row1-pos1|Item 14 (user button ROW 1 POS 1, leftmost top-row):
    Press and HOLD ~1 s, release."
    "item15-button-row1-pos2|Item 15 (user button ROW 1 POS 2):
    Press and HOLD ~1 s, release."
    "item16-button-row1-pos3|Item 16 (user button ROW 1 POS 3):
    Press and HOLD ~1 s, release."
    "item17-button-row1-pos4|Item 17 (user button ROW 1 POS 4):
    Press and HOLD ~1 s, release."
    "item18-button-row1-pos5|Item 18 (user button ROW 1 POS 5):
    Press and HOLD ~1 s, release."
    "item19-button-row1-pos6|Item 19 (user button ROW 1 POS 6):
    Press and HOLD ~1 s, release."
    "item20-button-row1-pos7|Item 20 (user button ROW 1 POS 7):
    Press and HOLD ~1 s, release."
    "item21-button-row1-pos8|Item 21 (user button ROW 1 POS 8):
    Press and HOLD ~1 s, release."
    "item22-button-row1-pos9|Item 22 (user button ROW 1 POS 9):
    Press and HOLD ~1 s, release."
    "item23-button-row1-pos10|Item 23 (user button ROW 1 POS 10):
    Press and HOLD ~1 s, release."
    "item24-button-row1-pos11|Item 24 (user button ROW 1 POS 11):
    Press and HOLD ~1 s, release."
    "item25-button-row1-pos12|Item 25 (user button ROW 1 POS 12):
    Press and HOLD ~1 s, release."
    "item26-button-row1-pos13|Item 26 (user button ROW 1 POS 13):
    Press and HOLD ~1 s, release."
    "item27-button-row1-pos14|Item 27 (user button ROW 1 POS 14, rightmost top-row):
    Press and HOLD ~1 s, release."
    "item28-button-row2-pos1|Item 28 (user button ROW 2 POS 1, leftmost bottom-row):
    Press and HOLD ~1 s, release."
    "item29-button-row2-pos2|Item 29 (user button ROW 2 POS 2):
    Press and HOLD ~1 s, release."
    "item30-button-row2-pos3|Item 30 (user button ROW 2 POS 3):
    Press and HOLD ~1 s, release."
    "item31-button-row2-pos4|Item 31 (user button ROW 2 POS 4):
    Press and HOLD ~1 s, release."
    "item32-button-row2-pos5|Item 32 (user button ROW 2 POS 5):
    Press and HOLD ~1 s, release."
    "item33-button-row2-pos6|Item 33 (user button ROW 2 POS 6):
    Press and HOLD ~1 s, release."
    "item34-button-row2-pos7|Item 34 (user button ROW 2 POS 7):
    Press and HOLD ~1 s, release."
    "item35-button-row2-pos8|Item 35 (user button ROW 2 POS 8):
    Press and HOLD ~1 s, release."
    "item36-button-row2-pos9|Item 36 (user button ROW 2 POS 9):
    Press and HOLD ~1 s, release."
    "item37-button-row2-pos10|Item 37 (user button ROW 2 POS 10):
    Press and HOLD ~1 s, release."
    "item38-button-row2-pos11|Item 38 (user button ROW 2 POS 11):
    Press and HOLD ~1 s, release."
    "item39-button-row2-pos12|Item 39 (user button ROW 2 POS 12):
    Press and HOLD ~1 s, release."
    "item40-button-row2-pos13|Item 40 (user button ROW 2 POS 13):
    Press and HOLD ~1 s, release."
    "item41-button-row2-pos14|Item 41 (user button ROW 2 POS 14, rightmost bottom-row):
    Press and HOLD ~1 s, release."

    # --- Item 42: user-assignable SPDT (up/off/down) ---
    "item42-userspdt-up|Item 42 (user-assignable SPDT, up direction):
    Press the switch UP and HOLD ~1 s, release."
    "item42-userspdt-down|Item 42 (user-assignable SPDT, down direction):
    Press the switch DOWN and HOLD ~1 s, release."

    # --- Item 43: user-assignable hat switch (4 directions, all spring-return) ---
    "item43-hat-up|Item 43 (hat switch, UP direction):
    Press the hat UP and HOLD ~1 s, release. The hat is spring-return."
    "item43-hat-right|Item 43 (hat switch, RIGHT direction):
    Press the hat RIGHT and HOLD ~1 s, release."
    "item43-hat-down|Item 43 (hat switch, DOWN direction):
    Press the hat DOWN and HOLD ~1 s, release."
    "item43-hat-left|Item 43 (hat switch, LEFT direction):
    Press the hat LEFT and HOLD ~1 s, release."
)

# ---------------------------------------------------------------------------
# Step 1 -- per-action tests
# ---------------------------------------------------------------------------

idx=$START
recorded=0
skipped=0

record_one() {
    local entry="$1"
    local label description
    label="${entry%%|*}"
    description="${entry#*|}"

    while true; do
        local file path bin ans
        file=$(printf '%s%03d-%s.log' "$FILE_PREFIX" "$idx" "$label")
        path="$OUTDIR/$file"
        bin="${path%.log}.bin"

        echo
        echo "===================================================================="
        printf '  [%d] %s\n' "$idx" "$label"
        echo
        printf '  %s\n' "$description"
        echo
        echo "  RECORDING. Press Enter when DONE."
        echo "  [r]=redo  [s]=skip  [q]=quit"

        start_capture "$bin"
        IFS= read -r ans
        stop_capture

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
                if [[ ! -s "$bin" ]]; then
                    sudo rm -f "$bin"
                    echo "  WARNING: no data captured. Device may be busy or"
                    echo "           not emitting reports. Use 'r' to redo."
                    continue
                fi
                sudo chown "$USER:$USER" "$bin" 2>/dev/null || true
                xxd -c 14 "$bin" > "$path"
                sudo rm -f "$bin"

                echo "  -> saved $file"
                report_capture "$path" "$BASELINE_PAYLOAD"

                # Final-state check: warn if the LAST report differs from baseline.
                local last_payload
                last_payload=$(extract_payload "$path" '$')
                if [[ "$last_payload" != "$BASELINE_PAYLOAD" ]]; then
                    local changed
                    changed=$(diff_payloads "$BASELINE_PAYLOAD" "$last_payload" | tr -s ' ')
                    echo "  NOTE: end-of-capture state DIFFERS from baseline."
                    echo "        Bytes still off-baseline: $changed"
                    echo "        If this was a sweep / button test that should return"
                    echo "        to rest, return the control to its rest position now"
                    echo "        before continuing. Use 'r' to redo this capture."
                    read -r -p "  [Enter]=accept  r=redo  s=skip&continue  q=quit  : " confirm
                    case "${confirm:-}" in
                        q|Q) exit 0 ;;
                        r|R) rm -f "$path"; continue ;;
                        s|S) rm -f "$path"; skipped=$((skipped+1)); idx=$((idx+1)); return ;;
                    esac
                fi

                recorded=$((recorded+1))
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
echo "Baseline:    $OUTDIR/${FILE_PREFIX}000-baseline.log"
echo "Action logs: $OUTDIR/${FILE_PREFIX}NNN-itemNN-*.log"
total_files=$(ls -1 "$OUTDIR" 2>/dev/null | grep -cE "^${FILE_PREFIX}[0-9]+-item[0-9]+")
echo "Action files written: $total_files"
