#!/bin/bash
# rd-record.sh - operator-facing capture script for the RailDriver
# structured capture protocol. See docs/rpi-raildriver/capture-plan.md
# for the full specification (this script implements §3 through §7).

set -euo pipefail
shopt -s nullglob

readonly SCRIPT_VERSION="1.0"
readonly EXPECTED_HID_ID="0003:000005F3:000000D2"
readonly REPORT_SIZE=14
readonly DEFAULT_CAPTURE_DIR="docs/rpi-raildriver/captures"
readonly FRAMING_TEXT="\
  Leave all controls at rest briefly after capture starts.
  Perform the requested action. After releasing or returning the
  control to rest, wait briefly before pressing Enter."

# CLI / runtime state
DEVICE=""
DEVICE_FROM_USER="no"
RUN_NAME=""
OPERATOR=""
NOTES=""
CAPTURE_DIR=""
RUN_DIR=""
SCRIPT_GIT_SHA=""

# Action data (parallel arrays, populated by load_actions)
ACTION_NUMS=()
ACTION_SLUGS=()
ACTION_PROMPTS=()
ACTION_COUNT=0

# Per-action runtime state
CAPTURE_PID=""
CURRENT_BIN=""
CURRENT_HEX=""
CURRENT_SKIPPED=""
LAST_REACHED=-1    # highest index whose .bin was written or .skipped sentinel created
STOP_RC=0          # exit code from the most recent stop_capture invocation

# Per-action status (parallel to ACTION_NUMS): captured | skipped | not-reached
ACTION_STATUS=()

# Termination state
ENDED_BY=""        # completed | operator-quit | signal
RUN_COMPLETE=""    # yes | no
LAST_ACTION_NUM="" # most recent action number reached (string), or empty
FINALIZED=0

usage() {
    cat <<'EOF'
Usage: rd-record.sh [OPTIONS]

Operator-facing RailDriver HID capture script. Walks the operator through
the fixed list of 58 actions defined in docs/rpi-raildriver/capture-plan.md
and writes one run-NNN/ directory of raw HID byte streams plus hex views.

Options:
  --device PATH        Use the named hidraw device instead of auto-detect.
                       The device's HID_ID must match the expected RailDriver
                       VID/PID.
  --run-name NAME      Use docs/rpi-raildriver/captures/run-NAME instead of
                       the auto-incremented run-NNN. NAME must match
                       [A-Za-z0-9._-]+.
  --operator NAME      Optional operator identifier recorded in manifest.txt.
  --notes TEXT         Optional free-text note recorded in manifest.txt.
  --capture-dir DIR    Override the captures parent directory (default
                       docs/rpi-raildriver/captures, relative to the
                       repository root).
  -h, --help           Show this message and exit.

Operator key bindings (during a capture):
  Enter   accept this capture, generate hex view, advance to next action
  r       discard current capture, redo current action
  s       skip this action (writes a .skipped sentinel) and advance
  q       quit gracefully; remaining actions are marked not-reached
EOF
}

die() {
    printf 'rd-record.sh: error: %s\n' "$*" >&2
    exit 1
}

warn() {
    printf 'rd-record.sh: %s\n' "$*" >&2
}

parse_args() {
    while (( $# > 0 )); do
        case "$1" in
            --device)
                [[ $# -ge 2 ]] || die "--device requires an argument"
                DEVICE="$2"
                DEVICE_FROM_USER="yes"
                shift 2
                ;;
            --device=*)
                DEVICE="${1#--device=}"
                DEVICE_FROM_USER="yes"
                shift
                ;;
            --run-name)
                [[ $# -ge 2 ]] || die "--run-name requires an argument"
                RUN_NAME="$2"
                shift 2
                ;;
            --run-name=*)
                RUN_NAME="${1#--run-name=}"
                shift
                ;;
            --operator)
                [[ $# -ge 2 ]] || die "--operator requires an argument"
                OPERATOR="$2"
                shift 2
                ;;
            --operator=*)
                OPERATOR="${1#--operator=}"
                shift
                ;;
            --notes)
                [[ $# -ge 2 ]] || die "--notes requires an argument"
                NOTES="$2"
                shift 2
                ;;
            --notes=*)
                NOTES="${1#--notes=}"
                shift
                ;;
            --capture-dir)
                [[ $# -ge 2 ]] || die "--capture-dir requires an argument"
                CAPTURE_DIR="$2"
                shift 2
                ;;
            --capture-dir=*)
                CAPTURE_DIR="${1#--capture-dir=}"
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                die "unknown option: $1 (use --help)"
                ;;
        esac
    done

    if [[ -n "$RUN_NAME" ]]; then
        if [[ ! "$RUN_NAME" =~ ^[A-Za-z0-9._-]+$ ]]; then
            die "--run-name must match [A-Za-z0-9._-]+ (got: $RUN_NAME)"
        fi
    fi

    if [[ -z "$CAPTURE_DIR" ]]; then
        CAPTURE_DIR="$DEFAULT_CAPTURE_DIR"
    fi
}

# Resolve the script's repository root by walking up from the script's location.
resolve_repo_root() {
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    local d="$script_dir"
    while [[ "$d" != "/" ]]; do
        if [[ -d "$d/.git" ]]; then
            printf '%s\n' "$d"
            return 0
        fi
        d="$(dirname "$d")"
    done
    # Fall back to current working directory if not in a git checkout.
    pwd
}

resolve_script_version() {
    local repo="$1" sha=""
    if command -v git >/dev/null 2>&1 && [[ -d "$repo/.git" ]]; then
        sha="$(git -C "$repo" rev-parse --short HEAD 2>/dev/null || true)"
        if [[ -n "$sha" ]]; then
            if ! git -C "$repo" diff --quiet -- "${BASH_SOURCE[0]}" 2>/dev/null; then
                sha="${sha}-dirty"
            fi
            printf '%s\n' "$sha"
            return 0
        fi
    fi
    printf 'uncommitted\n'
}

preflight_tools() {
    command -v xxd >/dev/null 2>&1 || die "xxd is required but not found in PATH"
    command -v dd  >/dev/null 2>&1 || die "dd is required but not found in PATH"
    if ! dd if=/dev/null of=/dev/null bs=14 iflag=fullblock status=none count=0 \
            >/dev/null 2>&1; then
        die "this dd does not support 'iflag=fullblock' or 'status=none' (a recent GNU dd is required)"
    fi
}

# Read HID_ID for one hidraw device (e.g. hidraw0) by parsing
# /sys/class/hidraw/<name>/device/uevent. Empty string if not found.
read_hid_id() {
    local hid_name="$1"
    local uevent="/sys/class/hidraw/$hid_name/device/uevent"
    [[ -r "$uevent" ]] || return 0
    local line
    while IFS= read -r line; do
        if [[ "$line" == HID_ID=* ]]; then
            printf '%s\n' "${line#HID_ID=}"
            return 0
        fi
    done < "$uevent"
}

resolve_device() {
    if [[ "$DEVICE_FROM_USER" == "yes" ]]; then
        [[ -e "$DEVICE" ]] || die "device $DEVICE does not exist"
        local base
        base="$(basename "$DEVICE")"
        local hid_id
        hid_id="$(read_hid_id "$base")"
        if [[ -z "$hid_id" ]]; then
            die "could not read HID_ID for $DEVICE (missing /sys/class/hidraw/$base/device/uevent)"
        fi
        # Compare case-insensitively; sysfs uses uppercase hex.
        local got_lower="${hid_id,,}" want_lower="${EXPECTED_HID_ID,,}"
        if [[ "$got_lower" != "$want_lower" ]]; then
            die "device $DEVICE has HID_ID=$hid_id but RailDriver requires $EXPECTED_HID_ID"
        fi
    else
        local matches=()
        local hid_dir
        for hid_dir in /sys/class/hidraw/hidraw*; do
            local name
            name="$(basename "$hid_dir")"
            local hid_id
            hid_id="$(read_hid_id "$name")"
            if [[ "${hid_id,,}" == "${EXPECTED_HID_ID,,}" ]]; then
                matches+=("/dev/$name")
            fi
        done
        if (( ${#matches[@]} == 0 )); then
            die "no RailDriver hidraw device found (no /sys/class/hidraw/*/device/uevent matched HID_ID=$EXPECTED_HID_ID); plug in the controller or pass --device"
        fi
        if (( ${#matches[@]} > 1 )); then
            warn "multiple RailDriver hidraw devices match $EXPECTED_HID_ID:"
            local m
            for m in "${matches[@]}"; do
                warn "  $m"
            done
            die "rerun with --device <path> to pick one"
        fi
        DEVICE="${matches[0]}"
    fi

    if [[ ! -r "$DEVICE" ]]; then
        die "$DEVICE is not readable by the current user; see docs/rpi-raildriver/plan.md §4.4 for the udev / plugdev setup"
    fi
}

# Auto-increment run-NNN under $CAPTURE_DIR. Returns the next numeric name on stdout.
next_run_number() {
    local n=1 max=0 d
    for d in "$CAPTURE_DIR"/run-*; do
        [[ -d "$d" ]] || continue
        local b="${d##*/run-}"
        if [[ "$b" =~ ^[0-9]+$ ]]; then
            n=$((10#$b))
            if (( n > max )); then
                max=$n
            fi
        fi
    done
    printf 'run-%03d\n' $((max + 1))
}

create_run_dir() {
    mkdir -p "$CAPTURE_DIR" || die "could not create capture directory $CAPTURE_DIR"
    local name
    if [[ -n "$RUN_NAME" ]]; then
        name="run-$RUN_NAME"
    else
        name="$(next_run_number)"
    fi
    RUN_DIR="$CAPTURE_DIR/$name"
    if [[ -e "$RUN_DIR" ]]; then
        die "run directory $RUN_DIR already exists; choose a different --run-name"
    fi
    mkdir "$RUN_DIR" || die "could not create $RUN_DIR"
}

# The fixed action list. Format: NN|slug|prompt
load_actions() {
    local line num slug prompt
    while IFS='|' read -r num slug prompt; do
        [[ -z "$num" || "$num" == \#* ]] && continue
        ACTION_NUMS+=("$num")
        ACTION_SLUGS+=("$slug")
        ACTION_PROMPTS+=("$prompt")
    done <<'EOF'
00|baseline-pre|Set all analog controls to their baseline positions: **Reverser** to full Forward, **Throttle / Dynamic Brake** to full Throttle, **Auto Brake** to fully RELEASED, **Independent Brake** to full release (no bail-off), **Wiper** to Off, **Lights** to Off. Do not touch the controller. Wait several seconds until the live counter stabilizes, then press Enter.
01|range-up|Push the **Range** switch UP and hold for ~1 second, then release. Press Enter.
02|range-down|Push the **Range** switch DOWN and hold for ~1 second, then release. Press Enter.
03|estop-up|Push the **E-Stop** switch UP and hold for ~1 second, then release. Press Enter.
04|estop-down|Push the **E-Stop** switch DOWN and hold for ~1 second, then release. Press Enter.
05|alert|Press the **Alert** button and hold for ~1 second, then release. Press Enter.
06|sand|Press the **Sand** button and hold for ~1 second, then release. Press Enter.
07|p-button|Press the **P** button and hold for ~1 second, then release. Press Enter.
08|bell|Press the **Bell** button and hold for ~1 second, then release. Press Enter.
09|horn-up|Push the **Horn** switch UP and hold for ~1 second, then release. Press Enter.
10|horn-down|Push the **Horn** switch DOWN and hold for ~1 second, then release. Press Enter.
11|spdt42-up|Push the **user-assignable SPDT** (item 42) UP and hold for ~1 second, then release. Press Enter.
12|spdt42-down|Push the **user-assignable SPDT** (item 42) DOWN and hold for ~1 second, then release. Press Enter.
13|hat43-up|Push the **hat switch** (item 43) UP and hold for ~1 second, then release. Press Enter.
14|hat43-up-right|Push the **hat switch** (item 43) UP-RIGHT and hold for ~1 second, then release. Press Enter.
15|hat43-right|Push the **hat switch** (item 43) RIGHT and hold for ~1 second, then release. Press Enter.
16|hat43-down-right|Push the **hat switch** (item 43) DOWN-RIGHT and hold for ~1 second, then release. Press Enter.
17|hat43-down|Push the **hat switch** (item 43) DOWN and hold for ~1 second, then release. Press Enter.
18|hat43-down-left|Push the **hat switch** (item 43) DOWN-LEFT and hold for ~1 second, then release. Press Enter.
19|hat43-left|Push the **hat switch** (item 43) LEFT and hold for ~1 second, then release. Press Enter.
20|hat43-up-left|Push the **hat switch** (item 43) UP-LEFT and hold for ~1 second, then release. Press Enter.
21|btn-back-01|Press only the **back-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter.
22|btn-back-02|Press only the **back-row, column 2** button (counting from the left). Hold for ~1 second, then release. Press Enter.
23|btn-back-03|Press only the **back-row, column 3** button (counting from the left). Hold for ~1 second, then release. Press Enter.
24|btn-back-04|Press only the **back-row, column 4** button (counting from the left). Hold for ~1 second, then release. Press Enter.
25|btn-back-05|Press only the **back-row, column 5** button (counting from the left). Hold for ~1 second, then release. Press Enter.
26|btn-back-06|Press only the **back-row, column 6** button (counting from the left). Hold for ~1 second, then release. Press Enter.
27|btn-back-07|Press only the **back-row, column 7** button (counting from the left). Hold for ~1 second, then release. Press Enter.
28|btn-back-08|Press only the **back-row, column 8** button (counting from the left). Hold for ~1 second, then release. Press Enter.
29|btn-back-09|Press only the **back-row, column 9** button (counting from the left). Hold for ~1 second, then release. Press Enter.
30|btn-back-10|Press only the **back-row, column 10** button (counting from the left). Hold for ~1 second, then release. Press Enter.
31|btn-back-11|Press only the **back-row, column 11** button (counting from the left). Hold for ~1 second, then release. Press Enter.
32|btn-back-12|Press only the **back-row, column 12** button (counting from the left). Hold for ~1 second, then release. Press Enter.
33|btn-back-13|Press only the **back-row, column 13** button (counting from the left). Hold for ~1 second, then release. Press Enter.
34|btn-back-14|Press only the **back-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter.
35|btn-front-01|Press only the **front-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter.
36|btn-front-02|Press only the **front-row, column 2** button (counting from the left). Hold for ~1 second, then release. Press Enter.
37|btn-front-03|Press only the **front-row, column 3** button (counting from the left). Hold for ~1 second, then release. Press Enter.
38|btn-front-04|Press only the **front-row, column 4** button (counting from the left). Hold for ~1 second, then release. Press Enter.
39|btn-front-05|Press only the **front-row, column 5** button (counting from the left). Hold for ~1 second, then release. Press Enter.
40|btn-front-06|Press only the **front-row, column 6** button (counting from the left). Hold for ~1 second, then release. Press Enter.
41|btn-front-07|Press only the **front-row, column 7** button (counting from the left). Hold for ~1 second, then release. Press Enter.
42|btn-front-08|Press only the **front-row, column 8** button (counting from the left). Hold for ~1 second, then release. Press Enter.
43|btn-front-09|Press only the **front-row, column 9** button (counting from the left). Hold for ~1 second, then release. Press Enter.
44|btn-front-10|Press only the **front-row, column 10** button (counting from the left). Hold for ~1 second, then release. Press Enter.
45|btn-front-11|Press only the **front-row, column 11** button (counting from the left). Hold for ~1 second, then release. Press Enter.
46|btn-front-12|Press only the **front-row, column 12** button (counting from the left). Hold for ~1 second, then release. Press Enter.
47|btn-front-13|Press only the **front-row, column 13** button (counting from the left). Hold for ~1 second, then release. Press Enter.
48|btn-front-14|Press only the **front-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter.
49|reverser-sweep|Move the **Reverser** slowly from full Forward to full Reverse, pausing briefly at each end, then return to full Forward. Press Enter.
50|throttle-sweep|Move the **Throttle / Dynamic Brake** slowly from full Throttle to full Dynamic Brake, pausing briefly at each end, then return to full Throttle. Press Enter.
51|auto-brake-sweep|Move the **Auto Brake** slowly from fully RELEASED to EMG, pausing briefly at each end, then return to RELEASED. Press Enter.
52|indep-brake-sweep|Move the **Independent Brake** through its brake range only, from full release to full application, pausing briefly at each end, then return to full release. Do not use either bail-off position during this capture. Press Enter.
53|bailoff-1|Move the **Independent Brake bail-off** control to the first bail-off position, hold for ~1 second, then release back to rest. Press Enter.
54|bailoff-2|Move the **Independent Brake bail-off** control to the second / farthest bail-off position, hold for ~1 second, then release back to rest. Press Enter.
55|wiper-cycle|Move the **Wiper** slowly through its full physical range and back, pausing briefly at the mechanical extremes. Press Enter.
56|lights-cycle|Move the **Lights** slowly through its full physical range and back, pausing briefly at the mechanical extremes. Press Enter.
57|baseline-post|Return all analog controls to the same baseline positions used for the pre-baseline: **Reverser** full Forward, **Throttle** full Throttle, **Auto Brake** fully RELEASED, **Independent Brake** full release (no bail-off), **Wiper** Off, **Lights** Off. Do not touch the controller. Wait several seconds until the live counter stabilizes, then press Enter.
EOF
    ACTION_COUNT=${#ACTION_NUMS[@]}
    if (( ACTION_COUNT != 58 )); then
        die "internal: expected 58 actions, got $ACTION_COUNT"
    fi
    local i
    for (( i = 0; i < ACTION_COUNT; i++ )); do
        ACTION_STATUS+=("not-reached")
    done
}

is_baseline_index() {
    local idx="$1"
    [[ "$idx" -eq 0 || "$idx" -eq $((ACTION_COUNT - 1)) ]]
}

# Render manifest.txt with the current state. Idempotent.
write_manifest() {
    local complete="$1" ended="$2" last_action="$3"
    local hostname_str uname_str captured_at
    captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    hostname_str="$(hostname 2>/dev/null || echo unknown)"
    uname_str="$(uname -a 2>/dev/null || echo unknown)"
    local run_basename="${RUN_DIR##*/}"
    local operator_field="${OPERATOR:-(not specified)}"
    local notes_field="${NOTES:-}"

    {
        printf 'captured_at:    %s\n' "$captured_at"
        printf 'hostname:       %s\n' "$hostname_str"
        printf 'uname:          %s\n' "$uname_str"
        printf 'device:         %s\n' "$DEVICE"
        printf 'hid_id:         %s\n' "$EXPECTED_HID_ID"
        printf 'script_version: %s\n' "$SCRIPT_GIT_SHA"
        printf 'operator:       %s\n' "$operator_field"
        printf 'run_dir:        %s\n' "$run_basename"
        printf 'notes:          %s\n' "$notes_field"
        printf 'run_complete:   %s\n' "$complete"
        printf 'ended_by:       %s\n' "$ended"
        printf 'last_action:    %s\n' "$last_action"
    } > "$RUN_DIR/manifest.txt.tmp"
    mv "$RUN_DIR/manifest.txt.tmp" "$RUN_DIR/manifest.txt"
}

# Generate README.md from current ACTION_STATUS.
write_readme() {
    local complete="$1" ended="$2" last_action="$3"
    local readme="$RUN_DIR/README.md"
    local i
    {
        printf '# Run %s\n\n' "${RUN_DIR##*/}"
        printf -- '- run_complete: %s\n' "$complete"
        printf -- '- ended_by: %s\n' "$ended"
        printf -- '- last_action: %s\n\n' "$last_action"
        printf '| # | slug | prompt | file | bytes | reports | partial_trailing_bytes | status |\n'
        printf '|---|------|--------|------|-------|---------|------------------------|--------|\n'
        for (( i = 0; i < ACTION_COUNT; i++ )); do
            local nn="${ACTION_NUMS[i]}"
            local slug="${ACTION_SLUGS[i]}"
            local prompt="${ACTION_PROMPTS[i]}"
            local status="${ACTION_STATUS[i]}"
            local stem="$nn-$slug"
            local file="—" bytes="—" reports="—" partial="—"
            case "$status" in
                captured)
                    file="$stem.bin"
                    if [[ -f "$RUN_DIR/$stem.bin" ]]; then
                        local sz
                        sz="$(stat -c %s -- "$RUN_DIR/$stem.bin" 2>/dev/null || echo 0)"
                        bytes="$sz"
                        reports=$(( sz / REPORT_SIZE ))
                        partial=$(( sz % REPORT_SIZE ))
                    fi
                    ;;
                skipped)
                    file="$stem.skipped"
                    ;;
                not-reached)
                    file="—"
                    ;;
            esac
            # Escape pipe characters in the prompt so the table renders.
            local prompt_escaped="${prompt//|/\\|}"
            printf '| %s | %s | %s | %s | %s | %s | %s | %s |\n' \
                "$nn" "$slug" "$prompt_escaped" "$file" "$bytes" "$reports" "$partial" "$status"
        done
    } > "$readme.tmp"
    mv "$readme.tmp" "$readme"
}

# Recompute SHA256SUMS over all retained run artifacts.
write_checksums() {
    (
        cd "$RUN_DIR"
        # Collect names in a stable order.
        local files=()
        local f
        for f in *.bin *.hex *.skipped manifest.txt README.md; do
            [[ -f "$f" ]] && files+=("$f")
        done
        if (( ${#files[@]} > 0 )); then
            sha256sum -- "${files[@]}" > SHA256SUMS.tmp
            mv SHA256SUMS.tmp SHA256SUMS
        else
            : > SHA256SUMS
        fi
    )
}

# Stop any running capture process. Returns the dd exit code on stdout.
# Treats 0 and 143 (SIGTERM) as success.
# Sets STOP_RC = exit status of the capture process (0 = clean, 143 = TERM,
# anything else = abnormal). Called for its side effect, never via command
# substitution: `wait` only works on direct children of the current shell,
# and a $(...) subshell would always report rc=-1 for the parent's child.
stop_capture() {
    local pid="$1"
    STOP_RC=0
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
    fi
    set +e
    if [[ -n "$pid" ]]; then
        wait "$pid" 2>/dev/null
        STOP_RC=$?
    fi
    set -e
}

# Drain pending input from the terminal so it isn't consumed by the
# next read_key invocation.
drain_stdin() {
    if [[ -t 0 ]]; then
        local _junk
        while IFS= read -r -t 0 -n 1 _junk 2>/dev/null; do
            :
        done
    fi
}

finalize_once() {
    if (( FINALIZED == 1 )); then
        return 0
    fi
    FINALIZED=1
    # If a capture is still running (e.g. signal during action), kill it
    # and discard the in-flight bin (it was never accepted).
    if [[ -n "$CAPTURE_PID" ]]; then
        stop_capture "$CAPTURE_PID"
        CAPTURE_PID=""
    fi
    if [[ -n "$CURRENT_BIN" && -f "$CURRENT_BIN" ]]; then
        rm -f "$CURRENT_BIN"
    fi
    CURRENT_BIN=""
    CURRENT_HEX=""
    CURRENT_SKIPPED=""

    # Default termination state if not set by main flow.
    if [[ -z "$ENDED_BY" ]]; then
        ENDED_BY="signal"
    fi
    if [[ -z "$RUN_COMPLETE" ]]; then
        RUN_COMPLETE="no"
    fi
    if [[ -z "$LAST_ACTION_NUM" && "$LAST_REACHED" -ge 0 ]]; then
        LAST_ACTION_NUM="${ACTION_NUMS[$LAST_REACHED]}"
    fi

    # Only finalize if the run dir was successfully created.
    if [[ -n "$RUN_DIR" && -d "$RUN_DIR" ]]; then
        write_manifest "$RUN_COMPLETE" "$ENDED_BY" "$LAST_ACTION_NUM"
        write_readme   "$RUN_COMPLETE" "$ENDED_BY" "$LAST_ACTION_NUM"
        write_checksums
    fi
}

# shellcheck disable=SC2317  # invoked via trap
on_signal() {
    local sig="$1"
    ENDED_BY="signal"
    RUN_COMPLETE="no"
    finalize_once
    # Restore default disposition and re-raise so the parent shell sees the signal.
    trap - "$sig" EXIT INT TERM
    kill -"$sig" $$ 2>/dev/null || exit 128
}

# shellcheck disable=SC2317  # invoked via trap
on_exit() {
    finalize_once
}

# Print the framing block (only for non-baseline actions).
print_framing() {
    printf '\n%b\n\n' "$FRAMING_TEXT"
}

# Print the prompt line for the current action.
print_prompt() {
    local idx="$1"
    local nn="${ACTION_NUMS[idx]}"
    local slug="${ACTION_SLUGS[idx]}"
    local prompt="${ACTION_PROMPTS[idx]}"
    printf '== Action %s/%02d: %s ==\n' "$nn" $((ACTION_COUNT - 1)) "$slug"
    printf '%s\n' "$prompt"
}

# Result of run_capture / handle_error: accept | redo | skip | quit | error
CAPTURE_OUTCOME=""

# Run the capture for one action. Sets $CAPTURE_OUTCOME. Side effects:
# writes $CURRENT_BIN; on accept also writes $CURRENT_HEX.
run_capture() {
    local idx="$1"
    local nn="${ACTION_NUMS[idx]}"
    local slug="${ACTION_SLUGS[idx]}"
    local stem="$nn-$slug"
    CURRENT_BIN="$RUN_DIR/$stem.bin"
    CURRENT_HEX="$RUN_DIR/$stem.hex"
    CURRENT_SKIPPED="$RUN_DIR/$stem.skipped"
    CAPTURE_OUTCOME=""

    # Pre-clean any leftover from a previous attempt of this action.
    rm -f "$CURRENT_BIN" "$CURRENT_HEX"

    drain_stdin

    # Start capture in background. Redirect dd's stdin so it can't compete
    # with the operator's terminal input.
    dd if="$DEVICE" of="$CURRENT_BIN" bs="$REPORT_SIZE" iflag=fullblock status=none </dev/null &
    CAPTURE_PID=$!

    local action_label="[$nn $slug]"
    local hint='[Enter=accept r=redo s=skip q=quit]'
    local reply bytes reports partial line

    # Counter loop: poll file size, refresh status line, look for input.
    while true; do
        bytes=0
        if [[ -f "$CURRENT_BIN" ]]; then
            bytes="$(stat -c %s -- "$CURRENT_BIN" 2>/dev/null || echo 0)"
        fi
        reports=$(( bytes / REPORT_SIZE ))
        partial=$(( bytes % REPORT_SIZE ))
        if (( partial > 0 )); then
            line="$action_label capturing... $reports reports ($bytes bytes, +$partial partial) $hint"
        else
            line="$action_label capturing... $reports reports ($bytes bytes) $hint"
        fi
        printf '\r\033[K%s' "$line"

        # Verify the capture process is still alive; if it died unexpectedly,
        # break out and handle the error.
        if ! kill -0 "$CAPTURE_PID" 2>/dev/null; then
            break
        fi

        # Wait up to 0.5s for an operator key.
        if IFS= read -r -t 0.5 reply; then
            case "$reply" in
                "")  # Enter -> accept
                    stop_capture "$CAPTURE_PID"
                    CAPTURE_PID=""
                    if [[ "$STOP_RC" -ne 0 && "$STOP_RC" -ne 143 ]]; then
                        printf '\r\033[K'
                        warn "dd exited with code $STOP_RC; partial capture preserved at $CURRENT_BIN"
                        CAPTURE_OUTCOME="error"
                        return 0
                    fi
                    # Flush any final reports xxd needs to see.
                    sync 2>/dev/null || true
                    if ! xxd -c "$REPORT_SIZE" -- "$CURRENT_BIN" > "$CURRENT_HEX"; then
                        warn "xxd failed for $CURRENT_BIN"
                        rm -f "$CURRENT_HEX"
                        CAPTURE_OUTCOME="error"
                        return 0
                    fi
                    printf '\r\033[K'
                    CAPTURE_OUTCOME="accept"
                    return 0
                    ;;
                r|R)
                    stop_capture "$CAPTURE_PID"
                    CAPTURE_PID=""
                    rm -f "$CURRENT_BIN" "$CURRENT_HEX"
                    printf '\r\033[K'
                    CAPTURE_OUTCOME="redo"
                    return 0
                    ;;
                s|S)
                    stop_capture "$CAPTURE_PID"
                    CAPTURE_PID=""
                    rm -f "$CURRENT_BIN" "$CURRENT_HEX"
                    : > "$CURRENT_SKIPPED"
                    printf '\r\033[K'
                    CAPTURE_OUTCOME="skip"
                    return 0
                    ;;
                q|Q)
                    stop_capture "$CAPTURE_PID"
                    CAPTURE_PID=""
                    rm -f "$CURRENT_BIN" "$CURRENT_HEX"
                    printf '\r\033[K'
                    CAPTURE_OUTCOME="quit"
                    return 0
                    ;;
                *)
                    : # ignore everything else
                    ;;
            esac
        fi
    done

    # We get here only if the capture process died on its own.
    set +e
    wait "$CAPTURE_PID" 2>/dev/null
    rc=$?
    set -e
    CAPTURE_PID=""
    printf '\r\033[K'
    warn "dd terminated unexpectedly with code $rc; partial capture preserved at $CURRENT_BIN"
    CAPTURE_OUTCOME="error"
    return 0
}

# Handle the "error" outcome from run_capture: re-prompt the operator with
# redo/skip/quit options. Sets $CAPTURE_OUTCOME.
handle_error() {
    local reply
    while true; do
        printf 'Choose: (r)edo / (s)kip / (q)uit > '
        if ! IFS= read -r reply; then
            printf '\n'
            CAPTURE_OUTCOME="quit"
            return 0
        fi
        case "$reply" in
            r|R) CAPTURE_OUTCOME="redo"; return 0 ;;
            s|S) CAPTURE_OUTCOME="skip"; return 0 ;;
            q|Q) CAPTURE_OUTCOME="quit"; return 0 ;;
            *)   warn "please answer r, s, or q" ;;
        esac
    done
}

run_action_loop() {
    local i outcome
    for (( i = 0; i < ACTION_COUNT; i++ )); do
        printf '\n'
        if ! is_baseline_index "$i"; then
            print_framing
        fi
        print_prompt "$i"

        while true; do
            run_capture "$i"
            outcome="$CAPTURE_OUTCOME"
            case "$outcome" in
                error)
                    handle_error
                    outcome="$CAPTURE_OUTCOME"
                    case "$outcome" in
                        redo) continue ;;
                        skip)
                            : > "${RUN_DIR}/${ACTION_NUMS[i]}-${ACTION_SLUGS[i]}.skipped"
                            ACTION_STATUS[i]="skipped"
                            LAST_REACHED=$i
                            LAST_ACTION_NUM="${ACTION_NUMS[i]}"
                            break
                            ;;
                        quit)
                            ENDED_BY="operator-quit"
                            RUN_COMPLETE="no"
                            LAST_ACTION_NUM="${ACTION_NUMS[i]}"
                            return 0
                            ;;
                    esac
                    ;;
                redo)
                    continue
                    ;;
                accept)
                    ACTION_STATUS[i]="captured"
                    LAST_REACHED=$i
                    LAST_ACTION_NUM="${ACTION_NUMS[i]}"
                    break
                    ;;
                skip)
                    ACTION_STATUS[i]="skipped"
                    LAST_REACHED=$i
                    LAST_ACTION_NUM="${ACTION_NUMS[i]}"
                    break
                    ;;
                quit)
                    ENDED_BY="operator-quit"
                    RUN_COMPLETE="no"
                    LAST_ACTION_NUM="${ACTION_NUMS[i]}"
                    return 0
                    ;;
                *)
                    die "internal: unknown capture outcome '$outcome'"
                    ;;
            esac
        done
    done

    ENDED_BY="completed"
    RUN_COMPLETE="yes"
    LAST_ACTION_NUM="${ACTION_NUMS[ACTION_COUNT - 1]}"
}

main() {
    parse_args "$@"
    local repo
    repo="$(resolve_repo_root)"
    cd "$repo"

    SCRIPT_GIT_SHA="$(resolve_script_version "$repo")"

    preflight_tools
    resolve_device
    load_actions
    create_run_dir

    # Install traps after run dir exists so finalize has somewhere to write.
    trap 'on_signal INT' INT
    trap 'on_signal TERM' TERM
    trap on_exit EXIT

    # Initial manifest before any captures so the run is recoverable.
    write_manifest "no" "in-progress" ""

    printf 'rd-record.sh %s\n' "$SCRIPT_VERSION"
    printf 'device:    %s\n' "$DEVICE"
    printf 'run_dir:   %s\n' "$RUN_DIR"
    printf 'operator:  %s\n' "${OPERATOR:-(not specified)}"
    printf 'actions:   %d\n' "$ACTION_COUNT"

    run_action_loop
    finalize_once
    printf '\nRun complete. Output in %s\n' "$RUN_DIR"
    exit 0
}

main "$@"
