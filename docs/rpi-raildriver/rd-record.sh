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
#   RD_JITTER    LSB tolerance for ADC noise on analog bytes 0-6
#                during baseline capture (default 2). Bytes 7-13 are
#                button bytes and must match exactly regardless.
#                Reporting during per-action captures also uses this
#                tolerance: changes <= RD_JITTER are flagged as
#                "(jitter)" rather than treated as a real movement.
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

# Analyze a captured xxd file. Two outputs:
#
#   1. Human-readable lines printed to stdout (for the operator).
#   2. A single tab-separated summary line appended to $RD_SUMMARY (if set).
#      Format: <type>\t<label>\t<KEY=VALUE>\t<KEY=VALUE>\t...\t<file>
#      Keys depend on $type:
#        button | spdt-direction | hat-direction:
#            byte=N  bit_mask=0xMM  bit_index=N  transitions=...  flag=...
#        analog | analog-bipolar | analog-positional:
#            byte=N  rest=0xRR  min=0xMM  max=0xMM  range=N  flag=...
#
# Args: <file> <baseline_payload> <type> <label>
report_capture() {
    local file="$1"
    local baseline_payload="$2"
    local type="$3"
    local label="$4"
    local jitter="${RD_JITTER:-2}"

    local n_reports
    n_reports=$(wc -l < "$file")
    echo "    reports captured       : $n_reports"
    echo "    control type           : $type"

    python3 -c '
import sys, re
from collections import Counter
baseline = sys.argv[1]
path     = sys.argv[2]
jitter   = int(sys.argv[3])
type_    = sys.argv[4]
label    = sys.argv[5]
summary_path = sys.argv[6]
file_basename = sys.argv[7]

hex_re = re.compile(r"[0-9a-fA-F]{2}")
rows = []
for line in open(path):
    try:
        _, rest = line.split(":", 1)
    except ValueError:
        continue
    pairs = hex_re.findall(rest.split("  ")[0])
    if len(pairs) >= 14:
        rows.append([p.lower() for p in pairs[:14]])

print(f"    distinct payloads      : {len({tuple(r) for r in rows})}")

# Per-byte distinct values seen
seen = [set() for _ in range(14)]
for r in rows:
    for i in range(14):
        seen[i].add(r[i])

# Classify each byte: real-change | jitter | unchanged
real_changed = []   # bytes where a button bit toggled OR analog spread > jitter
jitter_only = []    # analog bytes 0-6 with spread <= jitter
for i in range(14):
    bv = baseline[2*i:2*i+2]
    if not any(v != bv for v in seen[i]):
        continue
    if i < 7:
        max_dev = max(abs(int(v, 16) - int(bv, 16)) for v in seen[i])
        if max_dev <= jitter:
            jitter_only.append((i, sorted(seen[i]), bv, max_dev))
            continue
    real_changed.append((i, sorted(seen[i], key=lambda x: int(x, 16)), bv))

# Print jitter info regardless of type
def print_jitter():
    if jitter_only:
        info = ", ".join(f"byte {i} ({chr(177)}{dev})" for i, _, _, dev in jitter_only)
        print(f"    minor analog jitter    : {info} (within {chr(177)}{jitter} LSB, ignored)")

# ---- Decide what to extract based on type ----

flag = ""
extracted = {}    # KEY=VALUE pairs for the summary line

is_binary_type    = type_ in ("button", "spdt-direction", "hat-direction")
is_analog_type    = type_.startswith("analog")

if is_binary_type:
    # Expect exactly one button-byte (7-13) to have toggled bits.
    button_changes = [(i, vals, bv) for i, vals, bv in real_changed if i >= 7]
    analog_changes = [(i, vals, bv) for i, vals, bv in real_changed if i < 7]
    if not button_changes:
        print("    bytes that varied      : NONE in button range -- the user may not have done anything")
        flag = "no-change"
    elif len(button_changes) > 1:
        print("    WARNING: multiple button bytes changed (expected exactly one):")
        for i, vals, bv in button_changes:
            print(f"      byte {i:>2} (rest={bv}): {chr(32).join(vals)}")
        flag = "multi-byte"
    else:
        i, vals, bv = button_changes[0]
        # Compute changed bits across the capture (XOR of all values vs baseline).
        bv_int = int(bv, 16)
        bits_ever_set = 0
        for v in vals:
            bits_ever_set |= (int(v, 16) ^ bv_int)
        # Count transitions on each bit using the row sequence.
        transitions_per_bit = [0]*8
        prev = bv_int
        for r in rows:
            cur = int(r[i], 16)
            for b in range(8):
                if ((prev ^ cur) >> b) & 1:
                    transitions_per_bit[b] += 1
            prev = cur
        # Pick THE one bit with most transitions; warn if multiple.
        active_bits = [b for b in range(8) if transitions_per_bit[b] > 0]
        if not active_bits:
            print("    WARNING: button byte saw values but no bit transitions detected")
            flag = "no-bit"
        elif len(active_bits) > 1:
            print(f"    WARNING: multiple bits active in byte {i}: {active_bits}")
            print(f"             transitions per bit: {transitions_per_bit}")
            flag = "multi-bit"
            chosen_bit = max(active_bits, key=lambda b: transitions_per_bit[b])
        else:
            chosen_bit = active_bits[0]
        if active_bits:
            mask = 1 << chosen_bit
            up_count   = sum(1 for k in range(1, len(rows))
                             if (int(rows[k][i],16) ^ int(rows[k-1][i],16)) & mask
                             and (int(rows[k][i],16) & mask))
            down_count = sum(1 for k in range(1, len(rows))
                             if (int(rows[k][i],16) ^ int(rows[k-1][i],16)) & mask
                             and not (int(rows[k][i],16) & mask))
            print(f"    byte changed           : {i}")
            print(f"    bit changed            : 0x{mask:02x} (bit {chosen_bit})")
            print(f"    transitions seen       : {up_count} press, {down_count} release")
            extracted = dict(byte=i, bit_mask=f"0x{mask:02x}", bit_index=chosen_bit,
                             press=up_count, release=down_count)
    if analog_changes and not flag:
        # A binary capture also moved an analog axis -- worth flagging.
        print("    NOTE: analog byte(s) also moved during this binary capture:")
        for i, vals, bv in analog_changes:
            print(f"      byte {i:>2} (rest={bv}): "
                  f"min={vals[0]} max={vals[-1]}")
        flag = (flag + ";" if flag else "") + "analog-also-moved"

elif is_analog_type:
    button_changes = [(i, vals, bv) for i, vals, bv in real_changed if i >= 7]
    analog_changes = [(i, vals, bv) for i, vals, bv in real_changed if i < 7]
    if not analog_changes:
        print("    bytes that varied      : NONE in analog range -- the user may not have moved the lever far enough")
        flag = "no-change"
    elif len(analog_changes) > 1:
        print("    WARNING: multiple analog bytes changed (expected exactly one):")
        for i, vals, bv in analog_changes:
            mn = int(vals[0], 16); mx = int(vals[-1], 16)
            print(f"      byte {i:>2} (rest={bv}): min={vals[0]} max={vals[-1]} range={mx-mn}")
        flag = "multi-byte"
    else:
        i, vals, bv = analog_changes[0]
        bv_int = int(bv, 16)
        ints = [int(v, 16) for v in vals]
        mn = min(ints); mx = max(ints)
        print(f"    byte that varied       : {i}")
        print(f"    rest value             : 0x{bv_int:02x}")
        print(f"    min value seen         : 0x{mn:02x}  ({mn-bv_int:+d} from rest)")
        print(f"    max value seen         : 0x{mx:02x}  ({mx-bv_int:+d} from rest)")
        print(f"    range                  : {mx - mn}")
        if type_ == "analog-bipolar":
            if mn >= bv_int or mx <= bv_int:
                print("    WARNING: bipolar analog did not span both sides of rest")
                flag = "asymmetric"
        else:
            if mn >= bv_int and mx == bv_int:
                print("    WARNING: analog never moved away from rest")
                flag = "no-change"
        extracted = dict(byte=i, rest=f"0x{bv_int:02x}",
                         min=f"0x{mn:02x}", max=f"0x{mx:02x}", range=mx-mn)
    if button_changes and not flag:
        print("    NOTE: button byte(s) also changed during this analog capture:")
        for i, vals, bv in button_changes:
            print(f"      byte {i:>2} (rest={bv}): {chr(32).join(vals)}")
        flag = (flag + ";" if flag else "") + "button-also-moved"

else:
    print(f"    WARNING: unknown control type: {type_}")
    flag = "unknown-type"

print_jitter()

# ---- Append a summary line ----
if summary_path and extracted:
    flag_str = flag if flag else "ok"
    with open(summary_path, "a") as f:
        kv = "\t".join(f"{k}={v}" for k, v in extracted.items())
        f.write(type_ + "\t" + label + "\t" + kv + "\tflag=" + flag_str + "\tfile=" + file_basename + "\n")
' "$baseline_payload" "$file" "$jitter" "$type" "$label" "${RD_SUMMARY:-}" "$(basename "$file")"
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
    local jitter="${RD_JITTER:-2}"
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
  and runs for ~1 second.

  Note: the analog axes (potentiometers in the levers) typically
  jitter by 1-2 LSB at idle even when nothing is moving. The script
  tolerates per-byte spread of up to RD_JITTER (default 2) on the
  analog bytes (0-6) before flagging a problem; button bytes (7-13)
  must be exactly identical (button bits don't jitter).
BASELINE_PROMPT
        read -r -p "  [Enter]=record baseline  q=quit  : " ans
        if [[ "${ans:-}" =~ ^[qQ]$ ]]; then
            echo "Quit."
            exit 0
        fi

        local bin="$OUTDIR/${FILE_PREFIX}000-baseline.bin"
        echo "  Recording baseline (1 second, jitter tolerance ±${jitter} LSB on analog bytes)..."
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

        # Analyze the captured window. Outcome lines:
        #   STABLE <payload>                        -- single payload, ideal
        #   QUIET <payload> <byte:spread,...>       -- analog jitter only, within tolerance
        #   MOVING <payload> <reason>               -- real change detected; payload is the modal
        local result
        result=$(python3 -c '
import sys, re
from collections import Counter
path = sys.argv[1]
jitter = int(sys.argv[2])
hex_re = re.compile(r"[0-9a-fA-F]{2}")
rows = []
for line in open(path):
    try:
        _, rest = line.split(":", 1)
    except ValueError:
        continue
    pairs = hex_re.findall(rest.split("  ")[0])
    if len(pairs) >= 14:
        rows.append([p.lower() for p in pairs[:14]])
if not rows:
    print("EMPTY"); sys.exit()

unique = Counter(tuple(r) for r in rows)
if len(unique) == 1:
    chosen = list(unique.keys())[0]
    print("STABLE", "".join(chosen)); sys.exit()

# Per-byte analysis
button_bytes_constant = True
button_violations = []
analog_spreads = []   # list of (i, spread, vals_hex_sorted)
for i in range(14):
    vals_hex = sorted({r[i] for r in rows}, key=lambda x: int(x, 16))
    if i >= 7:  # button byte
        if len(vals_hex) > 1:
            button_bytes_constant = False
            button_violations.append((i, vals_hex))
    else:       # analog byte
        spread = int(vals_hex[-1], 16) - int(vals_hex[0], 16)
        analog_spreads.append((i, spread, vals_hex))

# Per-byte modal payload (most common value at each byte position)
modal = []
for i in range(14):
    cnt = Counter(r[i] for r in rows)
    modal.append(cnt.most_common(1)[0][0])
modal_payload = "".join(modal)

if button_bytes_constant:
    max_spread = max((s for _, s, _ in analog_spreads), default=0)
    if max_spread <= jitter:
        # Quiet -- normal ADC noise on analog axes only
        info = ",".join(f"byte{i}:spread{s}" for i, s, _ in analog_spreads if s > 0)
        if not info:
            info = "none"
        print("QUIET", modal_payload, info); sys.exit()

# Genuine movement
reasons = []
if button_violations:
    bv = ",".join(f"byte{i}:{{{chr(32).join(v)}}}" for i, v in button_violations)
    reasons.append(f"button-byte-changed[{bv}]")
big = [(i, s, v) for i, s, v in analog_spreads if s > jitter]
if big:
    bs = ",".join(f"byte{i}:spread{s}" for i, s, _ in big)
    reasons.append(f"analog-exceeded-tolerance[{bs}]")
print("MOVING", modal_payload, ";".join(reasons))
' "$baseline_log" "$jitter")

        case "$result" in
            "STABLE "*)
                BASELINE_PAYLOAD="${result#STABLE }"
                echo "  Baseline payload: $BASELINE_PAYLOAD"
                echo "  Window was perfectly steady (1 distinct payload)."
                echo "  Saved to $baseline_log"
                return
                ;;
            "QUIET "*)
                local rest_str="${result#QUIET }"
                BASELINE_PAYLOAD="${rest_str%% *}"
                local jitter_info="${rest_str#* }"
                echo "  Baseline payload: $BASELINE_PAYLOAD"
                echo "  Analog axes showed normal ADC jitter at rest: $jitter_info"
                echo "  (Within tolerance RD_JITTER=$jitter; treated as steady.)"
                echo "  Saved to $baseline_log"
                return
                ;;
            "EMPTY")
                echo "  ERROR: no parseable payloads in $baseline_log"
                rm -f "$baseline_log"
                continue
                ;;
            "MOVING "*)
                local rest_str="${result#MOVING }"
                local modal_payload="${rest_str%% *}"
                local why="${rest_str#* }"
                echo "  WARNING: baseline window contains REAL changes (not just ADC noise)."
                echo "           Cause: $why"
                echo "           Top observed payloads:"
                awk '{
                    gsub(/^[0-9a-fA-F]+: */, "")
                    sub(/ +[^ ]+$/, "")
                    gsub(/ /, "")
                    print tolower($0)
                }' "$baseline_log" | sort | uniq -c | sort -rn | head -5 \
                    | awk '{printf "             seen %d time(s): %s\n", $1, $2}'
                read -r -p "  [Enter]=retry  a=accept modal payload anyway  q=quit  : " ans2
                case "${ans2:-}" in
                    q|Q) exit 0 ;;
                    a|A)
                        BASELINE_PAYLOAD="$modal_payload"
                        echo "  Accepted modal payload as baseline: $BASELINE_PAYLOAD"
                        return
                        ;;
                    *)
                        rm -f "$baseline_log"
                        continue
                        ;;
                esac
                ;;
            *)
                echo "  ERROR: unexpected analyzer output: $result"
                rm -f "$baseline_log"
                continue
                ;;
        esac
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
    "item01-range-up|spdt-direction|Item 1 (Range, SPDT momentary):
    Press the Range switch UP and HOLD for ~1 second, then release.
    The switch is spring-return; centre is rest."

    "item01-range-down|spdt-direction|Item 1 (Range, SPDT momentary):
    Press the Range switch DOWN and HOLD for ~1 second, then release."

    # --- Item 2: E-Stop (SPDT momentary) ---
    "item02-estop-up|spdt-direction|Item 2 (E-Stop, SPDT momentary):
    Press the E-Stop switch UP and HOLD ~1 s, then release."

    "item02-estop-down|spdt-direction|Item 2 (E-Stop, SPDT momentary):
    Press the E-Stop switch DOWN and HOLD ~1 s, then release."

    # --- Items 3-6: simple buttons ---
    "item03-alert|button|Item 3 (Alert button):
    Press and HOLD the Alert button ~1 s, then release."

    "item04-sand|button|Item 4 (Sand button):
    Press and HOLD the Sand button ~1 s, then release."

    "item05-p|button|Item 5 (P button, assumed Pantograph):
    Press and HOLD the P button ~1 s, then release."

    "item06-bell|button|Item 6 (Bell button):
    Press and HOLD the Bell button ~1 s, then release."

    # --- Item 7: Horn (SPDT momentary) ---
    "item07-horn-up|spdt-direction|Item 7 (Horn, SPDT momentary):
    Push the Horn lever UP and HOLD ~1 s, then release."

    "item07-horn-down|spdt-direction|Item 7 (Horn, SPDT momentary):
    Push the Horn lever DOWN and HOLD ~1 s, then release."

    # --- Item 8: Reverser (3 detents) ---
    "item08-reverser-sweep|analog-positional|Item 8 (Reverser, 3 physical detents):
    From baseline (NEUTRAL), perform this sweep:
       Forward (pause ~1 s)
    -> Neutral (pause ~1 s)
    -> Reverse (pause ~1 s)
    -> back to Neutral.
    Press Enter when done."

    # --- Item 9: Throttle / Dyn-Brake (continuous bipolar) ---
    "item09-throttle-dynbrake-sweep|analog-bipolar|Item 9 (Throttle/Dyn-Brake, continuous bipolar):
    From baseline (Idle, centre), perform this sweep:
       Push DOWN to maximum throttle (pause ~1 s)
    -> back to Idle (centre, pause ~1 s)
    -> Pull UP to maximum dynamic brake (pause ~1 s)
    -> back to Idle.
    Press Enter when done."

    # --- Item 10: Auto Brake (continuous, 4 named positions) ---
    "item10-autobrake-sweep|analog-positional|Item 10 (Auto Brake):
    From baseline (Release), perform this sweep:
       Move to SUP (pause ~1 s)
    -> CS (pause ~1 s)
    -> EMG (pause ~1 s)
    -> back to Release.
    Press Enter when done."

    # --- Item 11a: Independent Brake lever (continuous) ---
    "item11a-indepbrake-lever-sweep|analog|Item 11a (Independent Brake LEVER ONLY -- not bail-off):
    From baseline (Release), perform this sweep:
       Move to FULL APPLY (pause ~1 s)
    -> back to Release.
    Do NOT engage the bail-off; that is the next test.
    Press Enter when done."

    # --- Item 11b: Bail-off (spring-loaded, button-style) ---
    "item11b-indepbrake-bailoff|button|Item 11b (Independent Brake BAIL-OFF only):
    With the Independent Brake LEVER at Release (baseline),
    engage the spring-loaded bail-off and HOLD ~1 s, then release.
    (The bail-off does not stay engaged on its own.)
    Press Enter when done."

    # --- Item 12: Wiper (3-position switch) ---
    "item12-wiper-sweep|analog-positional|Item 12 (Wiper, 3-position switch):
    From baseline (Off), perform this sweep:
       Move to SLOW (pause ~1 s)
    -> FULL (pause ~1 s)
    -> back to Off.
    Press Enter when done."

    # --- Item 13: Lights (3-position switch) ---
    "item13-lights-sweep|analog-positional|Item 13 (Lights, 3-position switch):
    From baseline (Off), perform this sweep:
       Move to DIM (pause ~1 s)
    -> FULL (pause ~1 s)
    -> back to Off.
    Press Enter when done."

    # --- Items 14-41: 28 user-assignable buttons (2 x 14 layout) ---
    "item14-button-row1-pos1|button|Item 14 (user button ROW 1 POS 1, leftmost top-row):
    Press and HOLD ~1 s, release."
    "item15-button-row1-pos2|button|Item 15 (user button ROW 1 POS 2):
    Press and HOLD ~1 s, release."
    "item16-button-row1-pos3|button|Item 16 (user button ROW 1 POS 3):
    Press and HOLD ~1 s, release."
    "item17-button-row1-pos4|button|Item 17 (user button ROW 1 POS 4):
    Press and HOLD ~1 s, release."
    "item18-button-row1-pos5|button|Item 18 (user button ROW 1 POS 5):
    Press and HOLD ~1 s, release."
    "item19-button-row1-pos6|button|Item 19 (user button ROW 1 POS 6):
    Press and HOLD ~1 s, release."
    "item20-button-row1-pos7|button|Item 20 (user button ROW 1 POS 7):
    Press and HOLD ~1 s, release."
    "item21-button-row1-pos8|button|Item 21 (user button ROW 1 POS 8):
    Press and HOLD ~1 s, release."
    "item22-button-row1-pos9|button|Item 22 (user button ROW 1 POS 9):
    Press and HOLD ~1 s, release."
    "item23-button-row1-pos10|button|Item 23 (user button ROW 1 POS 10):
    Press and HOLD ~1 s, release."
    "item24-button-row1-pos11|button|Item 24 (user button ROW 1 POS 11):
    Press and HOLD ~1 s, release."
    "item25-button-row1-pos12|button|Item 25 (user button ROW 1 POS 12):
    Press and HOLD ~1 s, release."
    "item26-button-row1-pos13|button|Item 26 (user button ROW 1 POS 13):
    Press and HOLD ~1 s, release."
    "item27-button-row1-pos14|button|Item 27 (user button ROW 1 POS 14, rightmost top-row):
    Press and HOLD ~1 s, release."
    "item28-button-row2-pos1|button|Item 28 (user button ROW 2 POS 1, leftmost bottom-row):
    Press and HOLD ~1 s, release."
    "item29-button-row2-pos2|button|Item 29 (user button ROW 2 POS 2):
    Press and HOLD ~1 s, release."
    "item30-button-row2-pos3|button|Item 30 (user button ROW 2 POS 3):
    Press and HOLD ~1 s, release."
    "item31-button-row2-pos4|button|Item 31 (user button ROW 2 POS 4):
    Press and HOLD ~1 s, release."
    "item32-button-row2-pos5|button|Item 32 (user button ROW 2 POS 5):
    Press and HOLD ~1 s, release."
    "item33-button-row2-pos6|button|Item 33 (user button ROW 2 POS 6):
    Press and HOLD ~1 s, release."
    "item34-button-row2-pos7|button|Item 34 (user button ROW 2 POS 7):
    Press and HOLD ~1 s, release."
    "item35-button-row2-pos8|button|Item 35 (user button ROW 2 POS 8):
    Press and HOLD ~1 s, release."
    "item36-button-row2-pos9|button|Item 36 (user button ROW 2 POS 9):
    Press and HOLD ~1 s, release."
    "item37-button-row2-pos10|button|Item 37 (user button ROW 2 POS 10):
    Press and HOLD ~1 s, release."
    "item38-button-row2-pos11|button|Item 38 (user button ROW 2 POS 11):
    Press and HOLD ~1 s, release."
    "item39-button-row2-pos12|button|Item 39 (user button ROW 2 POS 12):
    Press and HOLD ~1 s, release."
    "item40-button-row2-pos13|button|Item 40 (user button ROW 2 POS 13):
    Press and HOLD ~1 s, release."
    "item41-button-row2-pos14|button|Item 41 (user button ROW 2 POS 14, rightmost bottom-row):
    Press and HOLD ~1 s, release."

    # --- Item 42: user-assignable SPDT (up/off/down) ---
    "item42-userspdt-up|spdt-direction|Item 42 (user-assignable SPDT, up direction):
    Press the switch UP and HOLD ~1 s, release."
    "item42-userspdt-down|spdt-direction|Item 42 (user-assignable SPDT, down direction):
    Press the switch DOWN and HOLD ~1 s, release."

    # --- Item 43: user-assignable hat switch (4 directions, all spring-return) ---
    "item43-hat-up|hat-direction|Item 43 (hat switch, UP direction):
    Press the hat UP and HOLD ~1 s, release. The hat is spring-return."
    "item43-hat-right|hat-direction|Item 43 (hat switch, RIGHT direction):
    Press the hat RIGHT and HOLD ~1 s, release."
    "item43-hat-down|hat-direction|Item 43 (hat switch, DOWN direction):
    Press the hat DOWN and HOLD ~1 s, release."
    "item43-hat-left|hat-direction|Item 43 (hat switch, LEFT direction):
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
    local label type description
    label="${entry%%|*}"
    local rest="${entry#*|}"
    type="${rest%%|*}"
    description="${rest#*|}"

    while true; do
        local file path bin ans
        file=$(printf '%s%03d-%s.log' "$FILE_PREFIX" "$idx" "$label")
        path="$OUTDIR/$file"
        bin="${path%.log}.bin"

        echo
        echo "===================================================================="
        printf '  [%d] %s  (type: %s)\n' "$idx" "$label" "$type"
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
                report_capture "$path" "$BASELINE_PAYLOAD" "$type" "$label"

                # Final-state check: warn if the LAST report differs from baseline
                # (ignoring jitter on analog bytes).
                local last_payload
                last_payload=$(extract_payload "$path" '$')
                if [[ "$last_payload" != "$BASELINE_PAYLOAD" ]]; then
                    # Use python to apply jitter tolerance to the final-state diff.
                    local off_rest
                    off_rest=$(python3 -c '
import sys
last = sys.argv[1]; bl = sys.argv[2]; jitter = int(sys.argv[3])
out = []
for i in range(14):
    a = int(last[2*i:2*i+2], 16); b = int(bl[2*i:2*i+2], 16)
    if a != b:
        if i < 7 and abs(a-b) <= jitter:
            continue
        out.append(str(i))
print(" ".join(out))
' "$last_payload" "$BASELINE_PAYLOAD" "${RD_JITTER:-2}")
                    if [[ -n "$off_rest" ]]; then
                        echo "  NOTE: end-of-capture state DIFFERS from baseline (after jitter filter)."
                        echo "        Bytes still off-baseline: $off_rest"
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
                fi

                recorded=$((recorded+1))
                idx=$((idx+1))
                return
                ;;
        esac
    done
}

# Open the per-run summary TSV that report_capture appends to.
export RD_SUMMARY="$OUTDIR/${FILE_PREFIX}summary.tsv"
: > "$RD_SUMMARY"
echo ">>> Per-action summary will be written to $RD_SUMMARY"

for entry in "${ACTIONS[@]}"; do
    record_one "$entry"
done

# ---------------------------------------------------------------------------
# Post-run human-readable summary
# ---------------------------------------------------------------------------

human_summary="$OUTDIR/${FILE_PREFIX}summary.txt"
python3 -c '
import sys
tsv_path = sys.argv[1]
out_path = sys.argv[2]
binary_rows = []
analog_rows = []
for line in open(tsv_path):
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 3:
        continue
    type_ = parts[0]; label = parts[1]; rest_kvs = parts[2:]
    kv = {}
    for p in rest_kvs:
        if "=" in p:
            k, v = p.split("=", 1); kv[k] = v
    if type_ in ("button", "spdt-direction", "hat-direction"):
        binary_rows.append((label, type_, kv))
    elif type_.startswith("analog"):
        analog_rows.append((label, type_, kv))

with open(out_path, "w") as f:
    f.write("RailDriver Phase 2 capture summary\n")
    f.write("=" * 70 + "\n\n")

    if binary_rows:
        f.write("BINARY CONTROLS (buttons / SPDT / hat)\n")
        f.write("-" * 70 + "\n")
        header = "label".ljust(46) + " " + "byte".rjust(4) + " " + "bit".rjust(4) + " " + "mask".rjust(6) + " " + "flag".ljust(14) + "\n"
        f.write(header)
        for label, type_, kv in binary_rows:
            row = (label.ljust(46) + " "
                   + kv.get("byte","").rjust(4) + " "
                   + kv.get("bit_index","").rjust(4) + " "
                   + kv.get("bit_mask","").rjust(6) + " "
                   + kv.get("flag","").ljust(14) + "\n")
            f.write(row)
        f.write("\n")

    if analog_rows:
        f.write("ANALOG CONTROLS\n")
        f.write("-" * 70 + "\n")
        header = ("label".ljust(46) + " "
                  + "byte".rjust(4) + " "
                  + "rest".rjust(5) + " "
                  + "min".rjust(5) + " "
                  + "max".rjust(5) + " "
                  + "range".rjust(5) + " "
                  + "flag".ljust(14) + "\n")
        f.write(header)
        for label, type_, kv in analog_rows:
            row = (label.ljust(46) + " "
                   + kv.get("byte","").rjust(4) + " "
                   + kv.get("rest","").rjust(5) + " "
                   + kv.get("min","").rjust(5) + " "
                   + kv.get("max","").rjust(5) + " "
                   + kv.get("range","").rjust(5) + " "
                   + kv.get("flag","").ljust(14) + "\n")
            f.write(row)
        f.write("\n")

    f.write(f"Total binary controls captured: {len(binary_rows)}\n")
    f.write(f"Total analog controls captured: {len(analog_rows)}\n")
print(f"Summary: {len(binary_rows)} binary + {len(analog_rows)} analog rows")
' "$RD_SUMMARY" "$human_summary"

echo
echo "===================================================================="
echo "DONE. Recorded=$recorded, skipped=$skipped, total actions=${#ACTIONS[@]}."
echo "Baseline:    $OUTDIR/${FILE_PREFIX}000-baseline.log"
echo "Action logs: $OUTDIR/${FILE_PREFIX}NNN-itemNN-*.log"
echo "Summary:     $human_summary"
echo "             $RD_SUMMARY  (machine-readable TSV)"
total_files=$(ls -1 "$OUTDIR" 2>/dev/null | grep -cE "^${FILE_PREFIX}[0-9]+-item[0-9]+")
echo "Action files written: $total_files"
