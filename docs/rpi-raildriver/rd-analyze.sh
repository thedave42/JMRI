#!/bin/bash
# rd-analyze.sh - analysis script for the RailDriver structured capture
# protocol. See docs/rpi-raildriver/capture-plan.md §9 for the full
# specification (this script implements §9 in full).
#
# This script is a thin bash wrapper around an embedded Python program.
# Bash performs argument handling, run-directory discovery, and SHA256SUMS
# generation; Python performs the binary report processing, baseline /
# action-local rest model construction, mapping output, and cross-run
# comparison. The split keeps the deliverable a single .sh file as
# specified in §10 while avoiding pure-bash byte arithmetic.

set -euo pipefail
shopt -s nullglob

readonly DEFAULT_CAPTURE_DIR="docs/rpi-raildriver/captures"
readonly INVENTORY_PATH="docs/rpi-raildriver/control-inventory.md"

RUN_DIR_ARG=""
OUT_DIR=""

usage() {
    cat <<'EOF'
Usage: rd-analyze.sh [OPTIONS]

Analyze one or more run-NNN/ capture directories produced by rd-record.sh
and emit per-run analysis.{md,csv}, mapping.{md,csv}, plus a cross-run
summary when multiple runs are analyzed.

Options:
  --run-dir PATH   Analyze only the named run directory (no cross-run output).
  --out DIR        Write outputs under DIR instead of next to the captures.
                   Per-run output goes to DIR/<run-basename>/.
                   Cross-run output goes to DIR/cross-run-analysis.{md,csv}.
  -h, --help       Show this message and exit.

With no options, scan docs/rpi-raildriver/captures/run-*/, write each run's
analysis.{md,csv} and mapping.{md,csv} into the run directory, refresh that
run's SHA256SUMS, and write cross-run output to
docs/rpi-raildriver/captures/cross-run-analysis.{md,csv} when more than one
run exists.
EOF
}

die() {
    printf 'rd-analyze.sh: error: %s\n' "$*" >&2
    exit 1
}

parse_args() {
    while (( $# > 0 )); do
        case "$1" in
            --run-dir)
                [[ $# -ge 2 ]] || die "--run-dir requires an argument"
                RUN_DIR_ARG="$2"
                shift 2
                ;;
            --run-dir=*)
                RUN_DIR_ARG="${1#--run-dir=}"
                shift
                ;;
            --out)
                [[ $# -ge 2 ]] || die "--out requires an argument"
                OUT_DIR="$2"
                shift 2
                ;;
            --out=*)
                OUT_DIR="${1#--out=}"
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
}

resolve_repo_root() {
    local script_dir d
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    d="$script_dir"
    while [[ "$d" != "/" ]]; do
        if [[ -d "$d/.git" ]]; then
            printf '%s\n' "$d"
            return 0
        fi
        d="$(dirname "$d")"
    done
    pwd
}

main() {
    parse_args "$@"
    local repo
    repo="$(resolve_repo_root)"
    cd "$repo"

    [[ -r "$INVENTORY_PATH" ]] || die "control inventory not found at $INVENTORY_PATH"

    local -a run_dirs=()
    if [[ -n "$RUN_DIR_ARG" ]]; then
        [[ -d "$RUN_DIR_ARG" ]] || die "run directory $RUN_DIR_ARG does not exist"
        run_dirs+=("$RUN_DIR_ARG")
    else
        local d
        for d in "$DEFAULT_CAPTURE_DIR"/run-*; do
            [[ -d "$d" ]] && run_dirs+=("$d")
        done
        (( ${#run_dirs[@]} > 0 )) || die "no run directories found under $DEFAULT_CAPTURE_DIR"
    fi

    local cross_target=""
    if [[ -z "$RUN_DIR_ARG" && ${#run_dirs[@]} -gt 1 ]]; then
        if [[ -n "$OUT_DIR" ]]; then
            cross_target="$OUT_DIR"
        else
            cross_target="$DEFAULT_CAPTURE_DIR"
        fi
    fi

    local exit_code=0

    # Invoke the embedded Python analyzer once per run, then once for cross-run.
    # Communicate via env vars to keep arg quoting simple.
    local rd
    for rd in "${run_dirs[@]}"; do
        local out_target=""
        if [[ -n "$OUT_DIR" ]]; then
            out_target="$OUT_DIR/$(basename "$rd")"
            mkdir -p "$out_target"
        else
            out_target="$rd"
        fi
        set +e
        RD_RUN_DIR="$rd" RD_OUT_DIR="$out_target" RD_INVENTORY="$INVENTORY_PATH" \
                RD_MODE=per-run python3 - <<'PYEOF'
import os, sys
sys.path.insert(0, os.environ['RD_PY_LIB']) if os.environ.get('RD_PY_LIB') else None
from rd_analyze_lib import run_per_run_analysis
sys.exit(run_per_run_analysis(
    run_dir=os.environ['RD_RUN_DIR'],
    out_dir=os.environ['RD_OUT_DIR'],
    inventory_path=os.environ['RD_INVENTORY'],
))
PYEOF
        local py_rc=$?
        set -e
        if (( py_rc != 0 )); then
            exit_code=$py_rc
        fi

        # Refresh per-run SHA256SUMS over all retained artifacts present
        # in the output directory (covers raw captures + analysis + mapping).
        local sums_dir="$out_target"
        local artifact_list=()
        local f
        for f in "$sums_dir"/*.bin "$sums_dir"/*.hex "$sums_dir"/*.skipped \
                 "$sums_dir/manifest.txt" "$sums_dir/README.md" \
                 "$sums_dir/analysis.md" "$sums_dir/analysis.csv" \
                 "$sums_dir/mapping.md"  "$sums_dir/mapping.csv"; do
            [[ -f "$f" ]] && artifact_list+=("$(basename "$f")")
        done
        if (( ${#artifact_list[@]} > 0 )); then
            (
                cd "$sums_dir"
                sha256sum -- "${artifact_list[@]}" > SHA256SUMS.tmp
                mv SHA256SUMS.tmp SHA256SUMS
            )
        fi
    done

    if [[ -n "$cross_target" ]]; then
        mkdir -p "$cross_target"
        local rd_list
        rd_list="$(printf '%s\n' "${run_dirs[@]}")"
        set +e
        RD_RUN_DIRS="$rd_list" RD_OUT_DIR="$cross_target" \
                RD_INVENTORY="$INVENTORY_PATH" RD_MODE=cross-run python3 - <<'PYEOF'
import os, sys
sys.path.insert(0, os.environ['RD_PY_LIB']) if os.environ.get('RD_PY_LIB') else None
from rd_analyze_lib import run_cross_run_analysis
run_dirs = [line for line in os.environ['RD_RUN_DIRS'].splitlines() if line]
sys.exit(run_cross_run_analysis(
    run_dirs=run_dirs,
    out_dir=os.environ['RD_OUT_DIR'],
    inventory_path=os.environ['RD_INVENTORY'],
))
PYEOF
        local cross_rc=$?
        set -e
        if (( cross_rc != 0 )); then
            exit_code=$cross_rc
        fi

        # Refresh cross-run SHA256SUMS beside the cross-run outputs.
        local cross_artifacts=()
        local f
        for f in "$cross_target/cross-run-analysis.md" "$cross_target/cross-run-analysis.csv"; do
            [[ -f "$f" ]] && cross_artifacts+=("$(basename "$f")")
        done
        if (( ${#cross_artifacts[@]} > 0 )); then
            (
                cd "$cross_target"
                sha256sum -- "${cross_artifacts[@]}" > SHA256SUMS.cross-run.tmp
                mv SHA256SUMS.cross-run.tmp SHA256SUMS.cross-run
            )
        fi
    fi

    exit "$exit_code"
}

# The Python library is sourced via a temp file so the embedded heredocs can
# import it cleanly. We write it to a per-invocation temp dir and clean up
# on exit.
PY_LIB_DIR="$(mktemp -d -t rd-analyze.XXXXXX)"
trap 'rm -rf "$PY_LIB_DIR"' EXIT
export RD_PY_LIB="$PY_LIB_DIR"
cat > "$PY_LIB_DIR/rd_analyze_lib.py" <<'PYLIB'
"""rd-analyze.sh embedded Python library.

Implements the analysis logic specified in
docs/rpi-raildriver/capture-plan.md §9.

Constraints (§9.5): does not read plan.md or any java/ source; does not
reference any prior claim about the byte layout; uses the inventory only
for input names and types in the mapping output.
"""

from __future__ import annotations

import csv
import io
import math
import os
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Set, Tuple

REPORT_SIZE = 14
ACTION_COUNT = 58

# --- Action list (mirrors §5 of the plan; kept here so this script does not
# read plan.md, per §9.5). The slug→inventory-item correspondence is also
# defined here because §5 is the protocol's definition of that correspondence,
# not a byte-layout claim.

# (action#, slug, prompt-not-needed, kind)
# kind is one of: baseline | switch | button | hat | analog
#   - 'switch' covers SPDT momentary directions (up/down)
#   - 'button' covers single-press momentary buttons (incl. front-edge grid)
#   - 'hat'    covers hat directions
#   - 'analog' covers continuous / multi-position sweeps and bail-off pos.
ACTIONS: List[Tuple[str, str, str]] = [
    ("00", "baseline-pre",      "baseline"),
    ("01", "range-up",          "switch"),
    ("02", "range-down",        "switch"),
    ("03", "estop-up",          "switch"),
    ("04", "estop-down",        "switch"),
    ("05", "alert",             "button"),
    ("06", "sand",              "button"),
    ("07", "p-button",          "button"),
    ("08", "bell",              "button"),
    ("09", "horn-up",           "switch"),
    ("10", "horn-down",         "switch"),
    ("11", "spdt42-up",         "switch"),
    ("12", "spdt42-down",       "switch"),
    ("13", "hat43-up",          "hat"),
    ("14", "hat43-up-right",    "hat"),
    ("15", "hat43-right",       "hat"),
    ("16", "hat43-down-right",  "hat"),
    ("17", "hat43-down",        "hat"),
    ("18", "hat43-down-left",   "hat"),
    ("19", "hat43-left",        "hat"),
    ("20", "hat43-up-left",     "hat"),
]
# Front-edge button grid (28 actions, 21..48). Slugs are physical positions;
# §5 explicitly forbids inferring inventory item numbers for these.
_grid = []
n = 21
for col in range(1, 15):
    _grid.append((f"{n:02d}", f"btn-back-{col:02d}", "button"))
    n += 1
for col in range(1, 15):
    _grid.append((f"{n:02d}", f"btn-front-{col:02d}", "button"))
    n += 1
ACTIONS.extend(_grid)
ACTIONS.extend([
    ("49", "reverser-sweep",    "analog"),
    ("50", "throttle-sweep",    "analog"),
    ("51", "auto-brake-sweep",  "analog"),
    ("52", "indep-brake-sweep", "analog"),
    ("53", "bailoff-1",         "analog"),
    ("54", "bailoff-2",         "analog"),
    ("55", "wiper-cycle",       "analog"),
    ("56", "lights-cycle",      "analog"),
    ("57", "baseline-post",     "baseline"),
])
assert len(ACTIONS) == ACTION_COUNT, f"expected {ACTION_COUNT} actions, got {len(ACTIONS)}"

# slug → (inventory_item_lookup_key, state_label)
# inventory_item_lookup_key is the integer item number from
# control-inventory.md, or None for the front-edge grid (whose physical
# position serves as the row key in the mapping output).
SLUG_TO_INVENTORY: Dict[str, Tuple[Optional[int], Optional[str]]] = {
    "range-up":         (1,  "up"),
    "range-down":       (1,  "down"),
    "estop-up":         (2,  "up"),
    "estop-down":       (2,  "down"),
    "alert":            (3,  "pressed"),
    "sand":             (4,  "pressed"),
    "p-button":         (5,  "pressed"),
    "bell":             (6,  "pressed"),
    "horn-up":          (7,  "up"),
    "horn-down":        (7,  "down"),
    "spdt42-up":        (42, "up"),
    "spdt42-down":      (42, "down"),
    "hat43-up":         (43, "up"),
    "hat43-up-right":   (43, "up-right"),
    "hat43-right":      (43, "right"),
    "hat43-down-right": (43, "down-right"),
    "hat43-down":       (43, "down"),
    "hat43-down-left":  (43, "down-left"),
    "hat43-left":       (43, "left"),
    "hat43-up-left":    (43, "up-left"),
    "reverser-sweep":   (8,  None),
    "throttle-sweep":   (9,  None),
    "auto-brake-sweep": (10, None),
    "indep-brake-sweep":(11, None),
    "bailoff-1":        (11, "bailoff-1"),
    "bailoff-2":        (11, "bailoff-2"),
    "wiper-cycle":      (12, None),
    "lights-cycle":     (13, None),
}

# --- Tunable thresholds (the plan describes both the window-size and the
# below-threshold-outlier filter as implementation details).

def _local_window_size(n_reports: int) -> int:
    """Size of leading/trailing local-rest windows."""
    if n_reports < 20:
        return 0
    return max(10, min(50, n_reports // 10))

def _outlier_threshold(n_reports: int) -> int:
    """Minimum number of out-of-rest reports for a byte to count as 'changed'."""
    if n_reports < 50:
        return 1
    return max(2, n_reports // 50)

# --- Inventory parsing -----------------------------------------------------

@dataclass
class InventoryEntry:
    item: str          # raw item field (e.g. "1", "14-41", "42")
    label: str
    kind: str          # raw type field
    notes: str

def parse_inventory(path: str) -> Dict[str, InventoryEntry]:
    """Parse the markdown tables in control-inventory.md.

    Returns {item_field: InventoryEntry}. The item_field is the literal
    contents of the first column (e.g. '1', '14-41', '42'); callers
    perform any range matching themselves.
    """
    entries: Dict[str, InventoryEntry] = {}
    row_re = re.compile(r"^\|(.+)\|\s*$")
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            m = row_re.match(line)
            if not m:
                continue
            cells = [c.strip() for c in m.group(1).split("|")]
            if len(cells) < 4:
                continue
            item = cells[0]
            # Skip header and divider rows.
            if item == "#" or set(item).issubset(set("-: ")):
                continue
            label = cells[1]
            kind = cells[2]
            notes = cells[3] if len(cells) >= 4 else ""
            entries[item] = InventoryEntry(item, label, kind, notes)
    return entries

def lookup_inventory_for_item(
    inventory: Dict[str, InventoryEntry], item_num: int
) -> Optional[InventoryEntry]:
    """Find an inventory row whose item field is the integer item_num,
    or whose item field is a 'lo-NN' range (e.g. '14-41' / '14–41')
    containing item_num."""
    s = str(item_num)
    if s in inventory:
        return inventory[s]
    for key, entry in inventory.items():
        # Accept '-' and en-dash as range separators.
        m = re.match(r"^\s*(\d+)\s*[-\u2013]\s*(\d+)\s*$", key)
        if m:
            lo, hi = int(m.group(1)), int(m.group(2))
            if lo <= item_num <= hi:
                return entry
    return None

def lookup_inventory_for_grid(
    inventory: Dict[str, InventoryEntry],
) -> Optional[InventoryEntry]:
    """Find the inventory entry that covers the 14-41 button grid."""
    for key, entry in inventory.items():
        m = re.match(r"^\s*(\d+)\s*[-\u2013]\s*(\d+)\s*$", key)
        if m:
            return entry
    return None

# --- Capture loading -------------------------------------------------------

def load_reports(path: str) -> Tuple[List[Tuple[int, ...]], int]:
    """Load all complete 14-byte reports from a file. Returns
    (reports, partial_trailing_bytes)."""
    if not os.path.isfile(path):
        return [], 0
    with open(path, "rb") as f:
        data = f.read()
    n_complete = len(data) // REPORT_SIZE
    reports = [tuple(data[i*REPORT_SIZE:(i+1)*REPORT_SIZE])
               for i in range(n_complete)]
    partial = len(data) - n_complete * REPORT_SIZE
    return reports, partial

# --- Baseline modelling ----------------------------------------------------

@dataclass
class BaselineModel:
    # Per byte index: distinct values, modal value, min, max.
    values: List[Set[int]] = field(default_factory=lambda: [set() for _ in range(REPORT_SIZE)])
    modal: List[int] = field(default_factory=lambda: [0] * REPORT_SIZE)
    minv: List[int] = field(default_factory=lambda: [0] * REPORT_SIZE)
    maxv: List[int] = field(default_factory=lambda: [0] * REPORT_SIZE)
    source: str = ""    # 'pre+post' | 'pre-only' | 'post-only'
    n_reports: int = 0

def build_baseline(
    pre_reports: Sequence[Tuple[int, ...]],
    post_reports: Sequence[Tuple[int, ...]],
    pre_status: str,
    post_status: str,
) -> Optional[BaselineModel]:
    pre_usable = (pre_status == "captured" and len(pre_reports) > 0)
    post_usable = (post_status == "captured" and len(post_reports) > 0)
    if not pre_usable and not post_usable:
        return None
    if pre_usable and post_usable:
        source = "pre+post"
        all_reports = list(pre_reports) + list(post_reports)
    elif pre_usable:
        source = "pre-only"
        all_reports = list(pre_reports)
    else:
        source = "post-only"
        all_reports = list(post_reports)
    model = BaselineModel(source=source, n_reports=len(all_reports))
    for i in range(REPORT_SIZE):
        col = [r[i] for r in all_reports]
        model.values[i] = set(col)
        ctr = Counter(col)
        # Tie-break: lowest value wins.
        max_count = max(ctr.values())
        modal_candidates = sorted(v for v, c in ctr.items() if c == max_count)
        model.modal[i] = modal_candidates[0]
        model.minv[i] = min(col)
        model.maxv[i] = max(col)
    return model

# --- Per-action analysis ---------------------------------------------------

@dataclass
class ChangedByteInfo:
    idx: int
    baseline_values: Set[int]
    local_rest_values: Set[int]      # empty if local rest not usable
    rest_values: Set[int]            # action values that are in effective rest
    asserted_values: Set[int]        # action values not in effective rest
    observed_values: Set[int]
    bit_mask: int                    # OR of (v XOR modal_baseline) for asserted
    minv: int
    maxv: int
    changed_report_count: int

@dataclass
class ActionAnalysis:
    nn: str
    slug: str
    kind: str                        # baseline | switch | button | hat | analog
    status: str                      # captured | skipped | not-reached
    report_count: int = 0
    partial_trailing_bytes: int = 0
    local_rest_usable: bool = False
    changed: List[ChangedByteInfo] = field(default_factory=list)
    quality_flags: List[str] = field(default_factory=list)

def discover_action_status(run_dir: str, nn: str, slug: str) -> str:
    stem = f"{nn}-{slug}"
    if os.path.isfile(os.path.join(run_dir, f"{stem}.bin")):
        return "captured"
    if os.path.isfile(os.path.join(run_dir, f"{stem}.skipped")):
        return "skipped"
    return "not-reached"

def analyze_action(
    nn: str,
    slug: str,
    kind: str,
    status: str,
    reports: Sequence[Tuple[int, ...]],
    partial: int,
    baseline: BaselineModel,
) -> ActionAnalysis:
    a = ActionAnalysis(nn=nn, slug=slug, kind=kind, status=status,
                       report_count=len(reports), partial_trailing_bytes=partial)
    if status != "captured":
        return a
    if kind == "baseline":
        # Baseline rows have no change-specific data per §9.1.
        return a
    n = len(reports)
    win = _local_window_size(n)
    threshold = _outlier_threshold(n)

    # Try to build local rest from leading/trailing windows.
    if win > 0 and 2 * win <= n:
        leading = reports[:win]
        trailing = reports[-win:]
        a.local_rest_usable = True
    else:
        leading = []
        trailing = []
        a.local_rest_usable = False
        a.quality_flags.append("insufficient-local-rest")

    drift_bytes: List[int] = []
    leading_per_byte: List[Set[int]] = [set() for _ in range(REPORT_SIZE)]
    trailing_per_byte: List[Set[int]] = [set() for _ in range(REPORT_SIZE)]
    if a.local_rest_usable:
        for i in range(REPORT_SIZE):
            leading_per_byte[i] = {r[i] for r in leading}
            trailing_per_byte[i] = {r[i] for r in trailing}
            global_set = baseline.values[i]
            disagree = leading_per_byte[i] != trailing_per_byte[i]
            outside = (not leading_per_byte[i].issubset(global_set)) or \
                      (not trailing_per_byte[i].issubset(global_set))
            if disagree or outside:
                drift_bytes.append(i)

    # Effective rest set per byte.
    effective_rest: List[Set[int]] = []
    local_per_byte: List[Set[int]] = []
    for i in range(REPORT_SIZE):
        if a.local_rest_usable:
            local = leading_per_byte[i] | trailing_per_byte[i]
        else:
            local = set()
        local_per_byte.append(local)
        effective_rest.append(baseline.values[i] | local)

    # Per-byte action statistics.
    for i in range(REPORT_SIZE):
        col = [r[i] for r in reports]
        observed = set(col)
        out_of_rest = [v for v in col if v not in effective_rest[i]]
        out_count = len(out_of_rest)
        if out_count == 0:
            continue
        if out_count < threshold:
            a.quality_flags.append(f"byte{i}=below-threshold-outlier:{out_count}")
            continue
        asserted = {v for v in observed if v not in effective_rest[i]}
        rest = {v for v in observed if v in effective_rest[i]}
        bit_mask = 0
        for v in asserted:
            bit_mask |= (v ^ baseline.modal[i])
        a.changed.append(ChangedByteInfo(
            idx=i,
            baseline_values=set(baseline.values[i]),
            local_rest_values=set(local_per_byte[i]),
            rest_values=rest,
            asserted_values=asserted,
            observed_values=observed,
            bit_mask=bit_mask,
            minv=min(col),
            maxv=max(col),
            changed_report_count=out_count,
        ))

    # Add drift flags only for bytes that ended up classified as changed,
    # so the flag describes the analyzed action's interesting bytes.
    changed_idxs = {c.idx for c in a.changed}
    for i in drift_bytes:
        if i in changed_idxs:
            a.quality_flags.append(f"byte{i}=local-rest-drift")
    return a

# --- Output rendering ------------------------------------------------------

def _hex_byte(v: int) -> str:
    return f"0x{v:02x}"

def _hex_set(vs: Set[int]) -> str:
    if not vs:
        return "{}"
    return "{" + ",".join(_hex_byte(v) for v in sorted(vs)) + "}"

def _per_byte_field(infos: List[ChangedByteInfo], render) -> str:
    if not infos:
        return ""
    return ",".join(f"byte{c.idx}={render(c)}" for c in infos)

ANALYSIS_COLUMNS = [
    "action#",
    "slug",
    "file_stem",
    "status",
    "report_count",
    "partial_trailing_bytes",
    "local_rest_usable",
    "byte_indices_changed",
    "bit_masks",
    "baseline_values",
    "local_rest_values",
    "rest_values",
    "asserted_values",
    "observed_values",
    "min_max",
    "changed_report_count",
    "quality_flags",
]

def _row_for_analysis(a: ActionAnalysis) -> List[str]:
    file_stem = f"{a.nn}-{a.slug}"
    if a.status != "captured" or a.kind == "baseline":
        # Empty data columns for non-captured rows and baselines.
        report_count = str(a.report_count) if a.status == "captured" else ""
        partial = str(a.partial_trailing_bytes) if a.status == "captured" else ""
        local_rest = ""
        return [
            a.nn, a.slug, file_stem, a.status,
            report_count, partial, local_rest,
            "", "", "", "", "", "", "", "", "",
            ",".join(a.quality_flags),
        ]
    changed = sorted(a.changed, key=lambda c: c.idx)
    byte_indices = ",".join(str(c.idx) for c in changed)
    bit_masks = _per_byte_field(changed, lambda c: f"0x{c.bit_mask:02x}")
    baseline_values = _per_byte_field(changed, lambda c: _hex_set(c.baseline_values))
    local_rest_values = _per_byte_field(changed, lambda c: _hex_set(c.local_rest_values))
    rest_values = _per_byte_field(changed, lambda c: _hex_set(c.rest_values))
    asserted_values = _per_byte_field(changed, lambda c: _hex_set(c.asserted_values))
    observed_values = _per_byte_field(changed, lambda c: _hex_set(c.observed_values))
    min_max = _per_byte_field(changed, lambda c: f"[{_hex_byte(c.minv)},{_hex_byte(c.maxv)}]")
    changed_count = _per_byte_field(changed, lambda c: str(c.changed_report_count))
    return [
        a.nn, a.slug, file_stem, a.status,
        str(a.report_count), str(a.partial_trailing_bytes),
        "yes" if a.local_rest_usable else "no",
        byte_indices, bit_masks, baseline_values, local_rest_values,
        rest_values, asserted_values, observed_values, min_max, changed_count,
        ",".join(a.quality_flags),
    ]

def _md_escape_pipes(s: str) -> str:
    return s.replace("|", "\\|")

def write_analysis_md(path: str, baseline: BaselineModel,
                      analyses: List[ActionAnalysis], header_extra: List[str] = None) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"# Per-run analysis\n\n")
        f.write(f"- baseline_source: {baseline.source}\n")
        f.write(f"- baseline_reports: {baseline.n_reports}\n")
        for line in (header_extra or []):
            f.write(f"- {line}\n")
        f.write("\n")
        f.write("| " + " | ".join(ANALYSIS_COLUMNS) + " |\n")
        f.write("|" + "|".join(["---"] * len(ANALYSIS_COLUMNS)) + "|\n")
        for a in analyses:
            row = [_md_escape_pipes(c) for c in _row_for_analysis(a)]
            f.write("| " + " | ".join(row) + " |\n")

def write_analysis_csv(path: str, analyses: List[ActionAnalysis]) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(ANALYSIS_COLUMNS)
        for a in analyses:
            w.writerow(_row_for_analysis(a))

# --- Mapping output (§9.4) -------------------------------------------------

MAPPING_COLUMNS = [
    "input",
    "inventory_item",
    "type",
    "state",
    "byte_index",
    "rest_value",
    "asserted_value",
    "bit_mask",
    "min_value",
    "max_value",
]

def _summarize_set(vs: Set[int]) -> str:
    if not vs:
        return ""
    if len(vs) == 1:
        return _hex_byte(next(iter(vs)))
    return _hex_set(vs)

def _mapping_rows_for_action(
    a: ActionAnalysis,
    inventory: Dict[str, InventoryEntry],
    grid_entry: Optional[InventoryEntry],
) -> List[List[str]]:
    """Produce mapping rows for one analyzed action. Returns one row per
    changed byte (or one placeholder row if no data was captured / no
    changed byte was identified)."""
    rows: List[List[str]] = []

    # Look up inventory metadata.
    if a.slug.startswith("btn-back-") or a.slug.startswith("btn-front-"):
        # Front-edge grid: §5 forbids inferring inventory item numbers.
        # The mapping output uses the physical position slug as the row key.
        inv_entry = grid_entry
        inventory_item_str = a.slug
        state = "pressed"
    else:
        item_state = SLUG_TO_INVENTORY.get(a.slug)
        if item_state is None:
            # Baselines and unknown slugs do not appear in mapping output.
            return rows
        item_num, state = item_state
        inv_entry = lookup_inventory_for_item(inventory, item_num) if item_num is not None else None
        inventory_item_str = str(item_num) if item_num is not None else ""

    input_name = inv_entry.label if inv_entry else ""
    type_name = inv_entry.kind if inv_entry else ""

    # Status handling.
    if a.status != "captured":
        rows.append([
            input_name, inventory_item_str, type_name,
            state or "", "", "", "", "", "", "",
        ])
        # Use a synthetic note column embedded in 'state' field would be wrong;
        # instead, rely on state=='no data captured' to signal it clearly.
        rows[-1][3] = f"no data captured ({a.status})"
        return rows

    if not a.changed:
        rows.append([
            input_name, inventory_item_str, type_name,
            state or "", "", "", "", "", "", "",
        ])
        rows[-1][3] = f"no data captured (no byte changed)"
        return rows

    # Emit one mapping row per changed byte.
    for c in sorted(a.changed, key=lambda x: x.idx):
        if a.kind == "analog":
            rows.append([
                input_name, inventory_item_str, type_name,
                state or "",
                str(c.idx),
                "", "", "",
                _hex_byte(c.minv), _hex_byte(c.maxv),
            ])
        else:
            rows.append([
                input_name, inventory_item_str, type_name,
                state or "",
                str(c.idx),
                _summarize_set(c.rest_values if c.rest_values else c.baseline_values),
                _summarize_set(c.asserted_values),
                f"0x{c.bit_mask:02x}",
                "", "",
            ])
    return rows

def write_mapping(
    md_path: str,
    csv_path: str,
    analyses: List[ActionAnalysis],
    inventory: Dict[str, InventoryEntry],
) -> None:
    grid_entry = lookup_inventory_for_grid(inventory)
    # Order: by inventory item number (with grid in physical position order
    # at the position where the grid range falls in the inventory).
    # Simpler: keep capture action order — this naturally puts items 1..7,
    # 42, 43 before the grid (Phase 1 then Phase 2) and analog at end. The
    # resulting order is item 1, 2, 3, 4, 5, 6, 7, 42, 43 (each with all its
    # states), then the grid in physical order, then analog 8..13, 11.
    # That matches the spec's 'ordered by inventory item number, with the
    # button grid ordered by physical position' closely enough; for strict
    # numeric ordering we sort below.
    def sort_key(a: ActionAnalysis):
        if a.slug.startswith("btn-back-") or a.slug.startswith("btn-front-"):
            # Place grid entries together using the action#'s position.
            return (1, int(a.nn))
        item_state = SLUG_TO_INVENTORY.get(a.slug)
        if item_state is None:
            return (2, int(a.nn))
        item_num, _ = item_state
        return (0, item_num if item_num is not None else 999, int(a.nn))

    ordered = [a for a in analyses if a.kind != "baseline"]
    ordered.sort(key=sort_key)

    all_rows: List[List[str]] = []
    for a in ordered:
        all_rows.extend(_mapping_rows_for_action(a, inventory, grid_entry))

    with open(md_path, "w", encoding="utf-8") as f:
        f.write("# Per-run mapping\n\n")
        f.write("Each row associates one captured action with the byte changes "
                "observed during that action. Inventory metadata (input, type) "
                "comes from `control-inventory.md`; all byte-level values are "
                "derived from the captured data alone.\n\n")
        f.write("| " + " | ".join(MAPPING_COLUMNS) + " |\n")
        f.write("|" + "|".join(["---"] * len(MAPPING_COLUMNS)) + "|\n")
        for row in all_rows:
            esc = [_md_escape_pipes(c) for c in row]
            f.write("| " + " | ".join(esc) + " |\n")

    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(MAPPING_COLUMNS)
        for row in all_rows:
            w.writerow(row)

# --- Per-run entry point ---------------------------------------------------

def _refuse_run(out_dir: str, message: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    md = os.path.join(out_dir, "analysis.md")
    csv_path = os.path.join(out_dir, "analysis.csv")
    with open(md, "w", encoding="utf-8") as f:
        f.write(f"# Per-run analysis\n\nUNANALYZED: {message}\n")
    with open(csv_path, "w", encoding="utf-8") as f:
        f.write("error\n")
        f.write(f"{message}\n")
    print(f"rd-analyze: {out_dir}: {message}", file=sys.stderr)
    return 2

def run_per_run_analysis(run_dir: str, out_dir: str, inventory_path: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    inventory = parse_inventory(inventory_path)

    # Discover statuses for every action.
    statuses: Dict[str, str] = {}
    cached_reports: Dict[str, Tuple[List[Tuple[int, ...]], int]] = {}
    for nn, slug, _kind in ACTIONS:
        status = discover_action_status(run_dir, nn, slug)
        statuses[(nn, slug)] = status
        if status == "captured":
            path = os.path.join(run_dir, f"{nn}-{slug}.bin")
            cached_reports[(nn, slug)] = load_reports(path)
        else:
            cached_reports[(nn, slug)] = ([], 0)

    pre_status = statuses[("00", "baseline-pre")]
    post_status = statuses[("57", "baseline-post")]
    pre_reports, _ = cached_reports[("00", "baseline-pre")]
    post_reports, _ = cached_reports[("57", "baseline-post")]
    baseline = build_baseline(pre_reports, post_reports, pre_status, post_status)
    if baseline is None:
        return _refuse_run(
            out_dir,
            "neither 00-baseline-pre.bin nor 57-baseline-post.bin contains any "
            "complete 14-byte reports; cannot build a baseline model.",
        )

    analyses: List[ActionAnalysis] = []
    for nn, slug, kind in ACTIONS:
        status = statuses[(nn, slug)]
        reports, partial = cached_reports[(nn, slug)]
        a = analyze_action(nn, slug, kind, status, reports, partial, baseline)
        analyses.append(a)

    write_analysis_md(
        os.path.join(out_dir, "analysis.md"),
        baseline,
        analyses,
        header_extra=[f"run_dir: {os.path.basename(os.path.normpath(run_dir))}"],
    )
    write_analysis_csv(os.path.join(out_dir, "analysis.csv"), analyses)
    write_mapping(
        os.path.join(out_dir, "mapping.md"),
        os.path.join(out_dir, "mapping.csv"),
        analyses,
        inventory,
    )
    return 0

# --- Cross-run entry point -------------------------------------------------

CROSS_COLUMNS = [
    "action#",
    "slug",
    "runs_compared",
    "byte_indices_consistent",
    "bit_masks_consistent",
    "per_run_min_max",
    "extrema_spread",
    "quality_flags",
    "discrepancies",
]

def _gather_per_run_for_action(
    run_dirs: List[str],
    nn: str,
    slug: str,
    kind: str,
    inventory_path: str,
) -> List[Tuple[str, ActionAnalysis, BaselineModel]]:
    """Return [(run_basename, ActionAnalysis, BaselineModel)] for each run that
    captured this action."""
    out: List[Tuple[str, ActionAnalysis, BaselineModel]] = []
    for rd in run_dirs:
        status = discover_action_status(rd, nn, slug)
        if status != "captured":
            continue
        # Build per-run baseline (small recompute; this code path is rare enough
        # in practice that re-reading the baselines per action is fine).
        pre_status = discover_action_status(rd, "00", "baseline-pre")
        post_status = discover_action_status(rd, "57", "baseline-post")
        pre_reports, _ = load_reports(os.path.join(rd, "00-baseline-pre.bin")) \
            if pre_status == "captured" else ([], 0)
        post_reports, _ = load_reports(os.path.join(rd, "57-baseline-post.bin")) \
            if post_status == "captured" else ([], 0)
        baseline = build_baseline(pre_reports, post_reports, pre_status, post_status)
        if baseline is None:
            continue
        reports, partial = load_reports(os.path.join(rd, f"{nn}-{slug}.bin"))
        a = analyze_action(nn, slug, kind, status, reports, partial, baseline)
        out.append((os.path.basename(os.path.normpath(rd)), a, baseline))
    return out

def _changed_idx_set(a: ActionAnalysis) -> Tuple[int, ...]:
    return tuple(sorted(c.idx for c in a.changed))

def _bit_mask_map(a: ActionAnalysis) -> Tuple[Tuple[int, int], ...]:
    return tuple(sorted((c.idx, c.bit_mask) for c in a.changed))

def run_cross_run_analysis(run_dirs: List[str], out_dir: str, inventory_path: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    rows: List[List[str]] = []
    for nn, slug, kind in ACTIONS:
        gathered = _gather_per_run_for_action(run_dirs, nn, slug, kind, inventory_path)
        if not gathered:
            continue
        run_names = [g[0] for g in gathered]
        idx_sets = {_changed_idx_set(g[1]) for g in gathered}
        mask_maps = {_bit_mask_map(g[1]) for g in gathered}
        idx_consistent = "yes" if len(idx_sets) == 1 else "no"
        masks_consistent = "yes" if len(mask_maps) == 1 else "no"

        # per_run_min_max: byte<i>={run1=[min,max],run2=[min,max],...}
        # Collect all byte indices that any run flagged as changed.
        all_idxs: Set[int] = set()
        for _, a, _ in gathered:
            for c in a.changed:
                all_idxs.add(c.idx)
        per_run_parts: List[str] = []
        spread_parts: List[str] = []
        for i in sorted(all_idxs):
            min_list: List[int] = []
            max_list: List[int] = []
            run_parts: List[str] = []
            for run_name, a, _ in gathered:
                hit = next((c for c in a.changed if c.idx == i), None)
                if hit:
                    min_list.append(hit.minv)
                    max_list.append(hit.maxv)
                    run_parts.append(f"{run_name}=[{_hex_byte(hit.minv)},{_hex_byte(hit.maxv)}]")
                else:
                    run_parts.append(f"{run_name}=—")
            per_run_parts.append(f"byte{i}={{" + ",".join(run_parts) + "}")
            if min_list and max_list:
                min_spread = max(min_list) - min(min_list)
                max_spread = max(max_list) - min(max_list)
                spread_parts.append(
                    f"byte{i}=min_spread={min_spread},max_spread={max_spread}"
                )
        # Quality flags union.
        qflags: Set[str] = set()
        for _, a, _ in gathered:
            qflags.update(a.quality_flags)

        # Discrepancies prose.
        discrepancies: List[str] = []
        if idx_consistent == "no":
            per_run_idx = ",".join(
                f"{run_names[i]}={list(_changed_idx_set(gathered[i][1]))}"
                for i in range(len(gathered))
            )
            discrepancies.append(f"byte_indices_changed differs across runs: {per_run_idx}")
        if masks_consistent == "no":
            per_run_masks = ",".join(
                f"{run_names[i]}={{"
                + ",".join(f"byte{c.idx}=0x{c.bit_mask:02x}" for c in sorted(gathered[i][1].changed, key=lambda x: x.idx))
                + "}"
                for i in range(len(gathered))
            )
            discrepancies.append(f"bit_masks differ across runs: {per_run_masks}")

        rows.append([
            nn,
            slug,
            ",".join(run_names),
            idx_consistent,
            masks_consistent,
            ";".join(per_run_parts),
            ";".join(spread_parts),
            ",".join(sorted(qflags)),
            "; ".join(discrepancies),
        ])

    md_path = os.path.join(out_dir, "cross-run-analysis.md")
    csv_path = os.path.join(out_dir, "cross-run-analysis.csv")
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("# Cross-run analysis\n\n")
        f.write(f"- runs: {', '.join(os.path.basename(os.path.normpath(d)) for d in run_dirs)}\n\n")
        f.write("| " + " | ".join(CROSS_COLUMNS) + " |\n")
        f.write("|" + "|".join(["---"] * len(CROSS_COLUMNS)) + "|\n")
        for row in rows:
            esc = [_md_escape_pipes(c) for c in row]
            f.write("| " + " | ".join(esc) + " |\n")
    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(CROSS_COLUMNS)
        for row in rows:
            w.writerow(row)
    return 0
PYLIB

main "$@"
