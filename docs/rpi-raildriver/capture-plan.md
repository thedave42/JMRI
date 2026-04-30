# RailDriver structured capture protocol

## 1. Problem statement

The existing captures under `docs/rpi-raildriver/test-data/` (five `xxd -c 14`
streams, 2,046 reports total) record the device's 14-byte HID input report
shape but do not pair any HID byte to any physical control, because no record
was kept of which control was being touched at which moment in the stream.

This document specifies a structured protocol — capture plus analysis —
whose explicit goal is to produce three data products from the device:

1. For every momentary switch, button, and hat direction in
   `control-inventory.md`: the set of byte indices in the 14-byte report
   that change when the control is asserted, the bit mask of changes within
   each such byte, and the distinct byte values observed at rest and while
   the control is asserted.
2. For each physical position in the 28-button front-edge grid: the same
   byte-index, bit-mask, rest-value, and asserted-value data. The inventory
   does not define which corner is item 14 versus item 41, so the protocol's
   output for these controls is keyed by physical position (`btn-back-01`,
   `btn-front-14`, etc.) unless a human later supplies an inventory-number
   convention.
3. For every continuous analog control in `control-inventory.md`: the set of byte
   indices that change when the control is moved through its physical
   range, and the minimum and maximum byte values observed at each such
   index.

The protocol is designed to report *what the device does*. It is not
designed to confirm or refute any prior claim about the report layout
(including but not limited to the byte-13-is-constant claim in `plan.md` §1
or the parser's `i >= 7` treatment in `RailDriverMenuItem.java`). Comparison
of this protocol's output to any prior claim is a separate, manual,
post-hoc human activity outside the scope of either script.

## 2. Scope

For every labeled control in `control-inventory.md`:

- **Switches and buttons** — capture HID reports while the control is held in
  each of its asserted states, with rest-state framing immediately before and
  after. SPDT switches get one capture per direction. The hat switch gets one
  capture per cardinal direction. Each individual button gets its own capture.
- **Analog controls** — capture HID reports while the control is swept through
  its full physical range, briefly pausing at the mechanical extremes so the
  minimum and maximum byte values are clearly visible.
- **Pre- and post-run baselines** — bracket each pass with "do not touch the
  controller" captures so the resting byte pattern is documented and any
  drift between start and end of run is visible.

## 3. Approach overview

Two bash scripts live under `docs/rpi-raildriver/`:

- **`rd-record.sh`** — operator-facing capture script. Walks the operator
  through the fixed, numbered list of 52 actions in §5 and produces one
  `run-NNN/` directory of raw HID byte streams plus hex views, as described
  in §4. Operator interaction is detailed in §6, and the capture mechanism
  is detailed in §7.
- **`rd-analyze.sh`** — analysis script. Reads one or more `run-NNN/`
  directories produced by `rd-record.sh` and emits the per-run and cross-run
  data products defined in §9. The analysis script is fully automatic; it
  does not prompt the operator and does not require the device to be
  attached.

For each capture action, `rd-record.sh`:

1. Prints the action's number, slug, and a one-line "what to do" prompt.
2. Starts capturing the raw `/dev/hidraw0` byte stream into a per-action
   binary file in the background.
3. Displays a live report counter so the operator can pace themselves.
4. Waits for an operator key:
   - `Enter` — accept this capture, generate hex view, advance.
   - `r` then Enter — discard and redo the current action.
   - `s` then Enter — skip this action; write a `.skipped` sentinel; advance.
   - `q` then Enter — quit gracefully; previously-completed files are kept.
5. Stops the capture, runs `xxd -c 14` to produce a hex view, moves on.

Each invocation of the script creates a **new run directory** so multiple
passes can be compared without overwriting each other. Action indices and
slugs are stable across runs so equivalent files in different runs correspond
to the same physical action.

## 4. Output layout

```
docs/rpi-raildriver/captures/
├── run-001/
│   ├── manifest.txt              # ISO-8601 UTC timestamp, hostname,
│   │                             # `uname -a`, resolved hidraw path,
│   │                             # VID/PID (hex), script git SHA / version,
│   │                             # operator name (optional, --operator flag)
│   ├── 00-baseline-pre.bin       # raw HID byte stream, exact device output
│   ├── 00-baseline-pre.hex       # `xxd -c 14` view of the .bin
│   ├── 01-range-up.bin
│   ├── 01-range-up.hex
│   ├── …
│   ├── 51-baseline-post.bin
│   ├── 51-baseline-post.hex
│   ├── NN-<slug>.skipped         # zero-byte sentinel for skipped actions
│   │                             # (no .bin or .hex)
│   ├── SHA256SUMS                # checksums for retained run artifacts
│   └── README.md                 # auto-generated index: action #, slug,
│                                 # prompt text, file name, byte count, or
│                                 # SKIPPED flag
└── run-002/
    └── …
```

Run directories auto-increment (`run-001`, `run-002`, …). The flag
`--run-name foo` overrides to `run-foo` for named runs (e.g.
`run-postcalibration`, `run-second-operator`).

Exploratory captures may be **gitignored by default** because they are
operator-specific and can be large. Any run cited as evidence for a published
mapping must retain enough material for another person to verify it: the raw
`.bin` files, the generated `.hex` files, `manifest.txt`, `README.md`, the
analysis outputs, and a `SHA256SUMS` file for all retained artifacts.

That evidence set can be committed, attached to an issue/PR, or archived
elsewhere, but analysis output alone is not sufficient evidence because it is
not independently re-checkable.

## 5. Action list (52 actions, fixed order)

For actions 01–50, the script shows a shared framing instruction before the
per-action prompt:

> Leave all controls at rest for about 0.5 seconds after capture starts.
> Perform the requested action. After releasing or returning the control to
> rest, wait about 0.5 seconds before pressing Enter.

### Phase 0 — pre-baseline (1 action)

| NN | slug             | prompt |
|----|------------------|--------|
| 00 | baseline-pre     | Do not touch the controller. Wait until the live counter shows at least ~375 reports captured (~3 s at 125 Hz), then press Enter. |

### Phase 1 — named switches and buttons (16 actions)

| NN | slug         | physical control                           | prompt |
|----|--------------|--------------------------------------------|--------|
| 01 | range-up     | Range switch                               | Push the **Range** switch UP and hold for ~1 second, then release. Press Enter. |
| 02 | range-down   | Range switch                               | Push the **Range** switch DOWN and hold for ~1 second, then release. Press Enter. |
| 03 | estop-up     | E-Stop switch                              | Push the **E-Stop** switch UP and hold for ~1 second, then release. Press Enter. |
| 04 | estop-down   | E-Stop switch                              | Push the **E-Stop** switch DOWN and hold for ~1 second, then release. Press Enter. |
| 05 | alert        | Alert button                               | Press the **Alert** button and hold for ~1 second, then release. Press Enter. |
| 06 | sand         | Sand button                                | Press the **Sand** button and hold for ~1 second, then release. Press Enter. |
| 07 | p-button     | P button (item 5)                          | Press the **P** button and hold for ~1 second, then release. Press Enter. |
| 08 | bell         | Bell button                                | Press the **Bell** button and hold for ~1 second, then release. Press Enter. |
| 09 | horn-up      | Horn switch                                | Push the **Horn** switch UP and hold for ~1 second, then release. Press Enter. |
| 10 | horn-down    | Horn switch                                | Push the **Horn** switch DOWN and hold for ~1 second, then release. Press Enter. |
| 11 | spdt42-up    | user-assignable SPDT (item 42)             | Push the **user-assignable SPDT** (item 42) UP and hold for ~1 second, then release. Press Enter. |
| 12 | spdt42-down  | user-assignable SPDT (item 42)             | Push the **user-assignable SPDT** (item 42) DOWN and hold for ~1 second, then release. Press Enter. |
| 13 | hat43-up     | hat switch (item 43)                       | Push the **hat switch** (item 43) UP and hold for ~1 second, then release. Press Enter. |
| 14 | hat43-right  | hat switch (item 43)                       | Push the **hat switch** (item 43) RIGHT and hold for ~1 second, then release. Press Enter. |
| 15 | hat43-down   | hat switch (item 43)                       | Push the **hat switch** (item 43) DOWN and hold for ~1 second, then release. Press Enter. |
| 16 | hat43-left   | hat switch (item 43)                       | Push the **hat switch** (item 43) LEFT and hold for ~1 second, then release. Press Enter. |

### Phase 2 — front-edge button grid, items 14..41 (28 actions)

The inventory describes items 14..41 as "28 buttons in a 2 × 14 layout along
the front edge of the controller", but does not specify which corner is item
14 and which is item 41. To avoid inventing a convention, the capture protocol
uses **neutral physical-position slugs** rather than inventory numbers. The
analysis output for these controls is keyed by physical position. Mapping
those physical positions back to inventory item numbers requires a separate
human-supplied numbering convention; `rd-analyze.sh` does not infer it.

Convention used here:
- Two rows: `back` and `front`. `back` is the row physically further from the
  operator (slightly inset from the front edge); `front` is the row closer to
  the operator (right at the front edge).
- 14 columns per row, numbered `01` through `14` from left to right as the
  operator faces the controller.
- Slug format: `btn-<row>-<col>` (e.g. `btn-back-01`, `btn-front-14`).

| NN  | slug             | prompt |
|-----|------------------|--------|
| 17  | btn-back-01      | Press only the **back-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter. |
| 18  | btn-back-02      | Press only the **back-row, column 2** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| …   | …                | … (back row, columns 3 through 14, identical pattern) |
| 30  | btn-back-14      | Press only the **back-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter. |
| 31  | btn-front-01     | Press only the **front-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter. |
| …   | …                | … (front row, columns 2 through 13, identical pattern) |
| 44  | btn-front-14     | Press only the **front-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter. |

Implementer note: the table above abbreviates the middle 24 of 28 button
prompts behind `…` for readability. `rd-record.sh` MUST emit all 28 prompts
in full; do not copy the elided form into the script.

### Phase 3 — analog / multi-position sweeps (6 actions)

| NN | slug              | prompt |
|----|-------------------|--------|
| 45 | reverser-sweep    | Move the **Reverser** slowly from full Forward to full Reverse, pausing briefly at each end, then return to Neutral. Press Enter. |
| 46 | throttle-sweep    | Move the **Throttle / Dynamic Brake** slowly from full Throttle to full Dynamic Brake (passing through center), pausing briefly at each end, then return to center. Press Enter. |
| 47 | auto-brake-sweep  | Move the **Auto Brake** slowly from fully RELEASED through SUP, CS, to EMG, pausing briefly at each end, then return to RELEASED. Press Enter. |
| 48 | indep-brake-sweep | Move the **Independent Brake** slowly through its full range including both bail-off positions, pausing briefly at each end, then return to release. Press Enter. |
| 49 | wiper-cycle       | Move the **Wiper** slowly through its full physical range and back, pausing briefly at the mechanical extremes. Press Enter. |
| 50 | lights-cycle      | Move the **Lights** slowly through its full physical range and back, pausing briefly at the mechanical extremes. Press Enter. |

### Phase 4 — post-baseline (1 action)

| NN | slug             | prompt |
|----|------------------|--------|
| 51 | baseline-post    | Do not touch the controller. Wait until the live counter shows at least ~375 reports captured (~3 s at 125 Hz), then press Enter. |

## 6. Operator key bindings

Inside an action prompt, while capture is running:

| Key             | Effect |
|-----------------|--------|
| `Enter`         | Accept this capture; generate hex view; advance to next action. |
| `r` then `Enter` | Discard current capture (delete the partial `.bin`); redo current action. |
| `s` then `Enter` | Skip this action; write a `NN-<slug>.skipped` sentinel; advance. |
| `q` then `Enter` | Quit gracefully. Already-accepted action files are preserved. The script writes whatever it can to `README.md` and exits 0. |

Anything else the operator types is silently ignored.

## 7. Capture mechanism

### Device resolution

Default device path: auto-detected by scanning
`/sys/class/hidraw/*/device/uevent` for `HID_ID=0003:000005F3:000000D2`.
Override with `--device /dev/hidrawN`. The script fails with a clear
diagnostic and exits non-zero if no matching device is found, or if the
matching device exists but is not readable by the current user (with a
pointer to the udev / `plugdev` setup in `plan.md` §4.4).

### Capture command

For each action:

```sh
stdbuf -o0 cat "$DEVICE" > "$run_dir/$NN-$slug.bin" &
cat_pid=$!
# … display live counter, wait for operator key …
kill -TERM "$cat_pid" 2>/dev/null
wait "$cat_pid" 2>/dev/null
cat_rc=$?
# cat_rc == 143 (128 + SIGTERM) is the expected controlled-stop result and
# must be treated as success. Anything else (other than 0) is a real error.
```

`stdbuf -o0` disables stdio block buffering on `cat`'s stdout, so each 14-byte
HID report is written through to the `.bin` file as it arrives instead of
sitting in a ~4 KiB libc buffer. Without `-o0`, both (a) the live counter
would lag reality by up to several seconds and then jump, and (b) anything in
the buffer at the moment we send `SIGTERM` would be lost when `cat` is killed
(the default `SIGTERM` action is immediate termination; `cat` installs no
handler that would flush). For analog sweeps, the lost buffer can include the
mechanical extrema the protocol exists to capture.

The capture may still end with a trailing partial HID report depending on
exactly when termination interrupts a read; the analysis step must detect and
report any trailing byte count that is not a complete 14-byte record.

### Live report counter

While capture is running, the script polls the size of the in-flight `.bin`
file every 250 ms and updates a single status line in place
(`printf "\r…"`):

```
[03 estop-up] capturing… 187 reports (2618 bytes)   [Enter=accept r=redo s=skip q=quit]
```

The counter helps the operator pace analog sweeps (a 5-second sweep should
show ~600+ reports before pressing Enter). It uses simple integer division
(`bytes / 14 = complete reports`); any remainder is displayed separately as
partial bytes. No parsing of the binary is performed by the counter. The
counter assumes `stdbuf -o0` is in effect for the capture command (see
above); without it the file size lags real device traffic and the counter
becomes useless for pacing.

### Hex view generation

On accept: `xxd -c 14 "$run_dir/$NN-$slug.bin" > "$run_dir/$NN-$slug.hex"`.
This is synchronous and runs after `cat` has been reaped, so the `.bin` is
final.

### Pre-flight checks at script start

1. `[ -r "$DEVICE" ]` — fail with udev/plugdev guidance if not readable.
2. `command -v xxd >/dev/null` and `command -v stdbuf >/dev/null` — fail
   clearly if either is absent.
3. When `--device` is supplied, the resolved device's `HID_ID` must match
   `0003:000005F3:000000D2`; fail loudly if it does not (prevents silently
   capturing the wrong device's bytes). This check is skipped under
   auto-detect, where `HID_ID` is already the selection criterion.

### `manifest.txt`

Written **before** any captures. Format:

```
captured_at:    2026-04-30T20:45:33Z
hostname:       <hostname>
uname:          <output of uname -a>
device:         /dev/hidraw0
hid_id:         0003:000005F3:000000D2
script_version: <git SHA short, or "uncommitted">
operator:       <value of --operator, or "(not specified)">
run_dir:        run-NNN
notes:          <value of --notes, or empty>
```

### `README.md`

Written **after** the last action (or on `q`). Format: a markdown table with
columns `#`, `slug`, `prompt`, `file`, `bytes`, `reports`,
`partial_trailing_bytes`, and `status`. `status` is one of `captured`,
`skipped`, `not-reached` (for actions after a `q` quit). Missing values
appear as `—`.

## 8. Evidence retention

Each run directory includes raw captures and generated summaries. If a run is
used as evidence for the controller mapping, retain the full run directory or
archive it unchanged. The script also writes `SHA256SUMS` so a reviewer can
verify that analysis was run against the same raw captures.

Exploratory runs that are not cited as evidence can remain local and ignored.
Reference runs used in issue/PR discussion should be attached or committed
with their checksum file.

## 9. Analysis output specification

`rd-analyze.sh` reads one or more `run-NNN/` directories produced by
`rd-record.sh` and emits two outputs: a per-run analysis and a cross-run
summary. The analysis is purely descriptive: it reports what the captured
data shows. It does not reference, compare against, or verify any prior
claim about the report layout, the parser's behaviour, or the inventory's
labels.

### 9.1 Per-run analysis

For a single run directory, the script processes each non-skipped action's
`.bin` file together with that run's `00-baseline-pre.bin` and
`51-baseline-post.bin` as follows:

1. Split each `.bin` into complete 14-byte reports. If a file length is not
   divisible by 14, ignore the trailing partial bytes for report analysis and
   record their count in `partial_trailing_bytes`.
2. Build a baseline model from all complete reports in `00-baseline-pre.bin`
   and `51-baseline-post.bin`. For each byte index, record:
   - the set of distinct baseline byte values;
   - the modal baseline value (most common value; lowest value wins ties);
   - the baseline minimum and maximum.

   Missing or empty baselines:
   - If both `00-baseline-pre.bin` and `51-baseline-post.bin` are missing,
     skipped, or contain zero complete 14-byte reports, the script must
     refuse to analyze the run, write a single-line `analysis.md` explaining
     why, and exit non-zero.
   - If exactly one baseline is usable, build the baseline model from that
     one file and record `baseline_source: pre-only` or
     `baseline_source: post-only` in `analysis.md`'s header so the reader
     knows the model is single-sided.
   - In both partial cases the script must not silently fall back to an
     empty baseline (which would misclassify every observed value as
     asserted).

   The bit-mask tie-break rule above (lowest value wins) is arbitrary and
   only matters when a byte's baseline distribution has more than one mode.
   When that happens, `bit_mask` becomes informational rather than
   definitive for that byte; `baseline_values`, `rest_values`, and
   `asserted_values` remain the authoritative columns.
3. For each action capture, identify changed byte indices: byte index `i`
   changed if any complete action report contains a value for byte `i` that
   is outside that byte's baseline value set.
4. For each changed byte index, record:
   - `rest_values`: action values for that byte that are also present in the
     baseline value set;
   - `asserted_values`: action values for that byte that are not present in
     the baseline value set;
   - `bit_mask`: the OR of `(value XOR modal_baseline_value)` for every
     action value in `asserted_values`;
   - `min_max`: the minimum and maximum action values for that byte;
   - `changed_report_count`: the count of complete action reports whose byte
     value is outside the baseline value set.

For analog / multi-position sweep actions, including Wiper and Lights,
`min_max` is the primary data product, with `observed_values`/value-set data
available from the same columns for review. For switches, buttons, and hat
directions, `rest_values`, `asserted_values`, and `bit_mask` are the primary
data products. The same columns are emitted for every action so the output
remains machine-readable.

Output: `run-NNN/analysis.md` — markdown table, one row per non-skipped
action, with columns:

| column | meaning |
|--------|---------|
| `action#` | The action's stable index, `00`..`51`. |
| `slug` | The action's slug (e.g. `01-range-up`). |
| `status` | `captured`, `skipped`, or `not-reached`. |
| `report_count` | Number of complete 14-byte HID reports in the action's `.bin`. |
| `partial_trailing_bytes` | Number of ignored trailing bytes after the last complete 14-byte report. |
| `byte_indices_changed` | Comma-separated list of byte indices (0..13) whose values went outside the baseline value set at any point during the action. Empty if no byte changed. |
| `bit_masks` | For each changed byte, the bit mask of bits that changed relative to that byte's modal baseline value, in the form `byte<i>=0x<hex>`; multiple entries comma-separated. |
| `baseline_values` | For each changed byte, the baseline value set, in the form `byte<i>={0x<hex>,0x<hex>,...}`. |
| `rest_values` | For each changed byte, the action values that were also present in the baseline value set. |
| `asserted_values` | For each changed byte, the action values that were not present in the baseline value set. |
| `min_max` | For each changed byte, `byte<i>=[min,max]` in hex. |
| `changed_report_count` | For each changed byte, the number of complete reports outside the baseline value set. |

A companion `run-NNN/analysis.csv` is also written with the same columns
for programmatic consumption.

The script does no inference about *which* control caused which bit/byte
change. The slug is reported as captured; the operator's `slug → physical
control` mapping is documented in §5 of this plan and in `README.md` of each
run, both of which are produced earlier in the workflow.

Skipped actions appear in the table with `status=skipped` and empty data
columns. Actions whose `.bin` is missing (e.g. interrupted mid-action)
appear with `status=not-reached` and empty data columns.

### 9.2 Cross-run analysis

When multiple `run-NNN/` directories exist, the script also writes
`docs/rpi-raildriver/captures/cross-run-analysis.md` (and `.csv`). For each
action present in any run, it compares per-run analyses across all runs and
reports:

| column | meaning |
|--------|---------|
| `action#` | The action's stable index. |
| `slug` | The action's slug. |
| `runs_compared` | Comma-separated list of run directory names included for this action (only runs that captured this action contribute). |
| `byte_indices_consistent` | `yes` if `byte_indices_changed` is identical across all compared runs; otherwise `no`. |
| `bit_masks_consistent` | `yes` if the per-byte bit masks are identical across all compared runs; otherwise `no`. |
| `per_run_min_max` | For each changed byte, the `[min,max]` interval reported by each run. |
| `extrema_spread` | For each changed byte, `min_spread=(max(run_mins)-min(run_mins))` and `max_spread=(max(run_maxes)-min(run_maxes))`. |
| `discrepancies` | If any of the above are `no`, a free-text description of which runs disagreed and how. Otherwise empty. |

The cross-run script does not pick a "winner" between disagreeing runs and
does not annotate any run as correct or incorrect. For analog extrema it does
not reduce the data to a pass/fail boolean; it reports the per-run values and
their spread for human review.

### 9.3 Constraints on the analysis script

- The script must not reference, compare to, or be aware of any prior
  claim about the report layout — including, but not limited to, the
  byte-13 claim in `plan.md` §1, the `i >= 7` treatment in
  `RailDriverMenuItem.java`, and any inventory-item-to-physical-position
  mapping supplied outside the run directory.
- The script must not generate prose conclusions, mappings to inventory
  item numbers, or interpretive statements about what the data means.
- The script's only inputs are `run-NNN/` directories produced by
  `rd-record.sh` and command-line flags; it must not read `plan.md`,
  `control-inventory.md`, or any source file under `java/`.
- The script's only outputs are the per-run and cross-run files described
  above.

### 9.4 Command-line interface

- `rd-analyze.sh` — with no arguments: scan
  `docs/rpi-raildriver/captures/run-*/`, write each one's `analysis.md` and
  `analysis.csv`, and write the cross-run summary if more than one run
  exists.
- `rd-analyze.sh --run-dir <path>` — analyze only the named run directory;
  no cross-run output.
- `rd-analyze.sh --out <dir>` — override the default output paths
  (defaults: `<run-dir>/analysis.{md,csv}` per run; cross-run files under
  `docs/rpi-raildriver/captures/`).

## 10. Deliverables

| Artifact | Notes |
|----------|-------|
| `docs/rpi-raildriver/capture-plan.md` | This document. |
| `docs/rpi-raildriver/rd-record.sh`    | Implements §3–§7. |
| `docs/rpi-raildriver/rd-analyze.sh`   | Implements §9. Reads `run-NNN/` directories produced by `rd-record.sh`; writes per-run `analysis.md` / `analysis.csv` and cross-run `cross-run-analysis.md` / `cross-run-analysis.csv`. Does not reference any prior claim. |
| `.gitignore` entry for `docs/rpi-raildriver/captures/` | Recommended default for exploratory runs; reference evidence sets can still be committed, attached to an issue/PR, or archived with `SHA256SUMS`. |

This table lists source artifacts only. The per-run and cross-run output
files (`analysis.md`, `analysis.csv`, `cross-run-analysis.md`,
`cross-run-analysis.csv`, `manifest.txt`, `README.md`, `SHA256SUMS`,
plus the raw `.bin` and `.hex` captures) are produced by the scripts at
runtime and live under `docs/rpi-raildriver/captures/`.
