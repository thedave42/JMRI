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
3. For every continuous analog or multi-position control in
   `control-inventory.md`: the set of byte indices that change when the
   control is moved through its physical range, the minimum and maximum byte
   values observed at each such index, and stable plateau values observed at
   named detents or positions.

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
  its full physical range, briefly pausing at mechanical extremes and named
  detents/positions so minimum, maximum, and plateau byte values are clearly
  visible. The independent brake range and the two bail-off positions are
  captured separately.
- **Pre- and post-run baselines** — bracket each pass with "do not touch the
  controller" captures so the resting byte pattern is documented and any
  drift between start and end of run is visible.

## 3. Approach overview

Two bash scripts live under `docs/rpi-raildriver/`:

- **`rd-record.sh`** — operator-facing capture script. Walks the operator
  through the fixed, numbered list of 54 actions in §5 and produces one
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
   - `q` then Enter — quit gracefully; previously-completed files are kept
     and the run is marked incomplete.
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
│   ├── 53-baseline-post.bin
│   ├── 53-baseline-post.hex
│   ├── NN-<slug>.skipped         # zero-byte sentinel for skipped actions
│   │                             # (no .bin or .hex)
│   ├── SHA256SUMS                # checksums for retained run artifacts
│   └── README.md                 # auto-generated index: action #, slug,
│                                 # prompt text, file name, byte count, or
│                                 # SKIPPED / NOT-REACHED flag
└── run-002/
    └── …
```

Run directories auto-increment (`run-001`, `run-002`, …). The implementation
must create the chosen directory with `mkdir` and fail if it already exists,
so two runs cannot silently write into the same directory. The flag
`--run-name foo` overrides to `run-foo` for named runs (e.g.
`run-postcalibration`, `run-second-operator`). The supplied name must match
`[A-Za-z0-9._-]+`; values containing `/`, `..`, whitespace, or shell
metacharacters are rejected before any directory is created.

Exploratory captures may be **gitignored by default** because they are
operator-specific and can be large. Any run cited as evidence for a published
mapping must retain enough material for another person to verify it: the raw
`.bin` files, the generated `.hex` files, `manifest.txt`, `README.md`, the
analysis outputs, and checksum files covering all retained artifacts.

That evidence set can be committed, attached to an issue/PR, or archived
elsewhere, but analysis output alone is not sufficient evidence because it is
not independently re-checkable.

## 5. Action list (54 actions, fixed order)

For actions 01–52, the script shows a shared framing instruction before the
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

Orientation diagram:

```
operator faces the controller from below this diagram

back row:  17 18 19 20 21 22 23 24 25 26 27 28 29 30
front row: 31 32 33 34 35 36 37 38 39 40 41 42 43 44
           left --------------------------------> right
```

| NN  | slug             | prompt |
|-----|------------------|--------|
| 17  | btn-back-01      | Press only the **back-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter. |
| 18  | btn-back-02      | Press only the **back-row, column 2** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 19  | btn-back-03      | Press only the **back-row, column 3** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 20  | btn-back-04      | Press only the **back-row, column 4** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 21  | btn-back-05      | Press only the **back-row, column 5** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 22  | btn-back-06      | Press only the **back-row, column 6** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 23  | btn-back-07      | Press only the **back-row, column 7** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 24  | btn-back-08      | Press only the **back-row, column 8** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 25  | btn-back-09      | Press only the **back-row, column 9** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 26  | btn-back-10      | Press only the **back-row, column 10** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 27  | btn-back-11      | Press only the **back-row, column 11** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 28  | btn-back-12      | Press only the **back-row, column 12** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 29  | btn-back-13      | Press only the **back-row, column 13** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 30  | btn-back-14      | Press only the **back-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter. |
| 31  | btn-front-01     | Press only the **front-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter. |
| 32  | btn-front-02     | Press only the **front-row, column 2** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 33  | btn-front-03     | Press only the **front-row, column 3** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 34  | btn-front-04     | Press only the **front-row, column 4** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 35  | btn-front-05     | Press only the **front-row, column 5** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 36  | btn-front-06     | Press only the **front-row, column 6** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 37  | btn-front-07     | Press only the **front-row, column 7** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 38  | btn-front-08     | Press only the **front-row, column 8** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 39  | btn-front-09     | Press only the **front-row, column 9** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 40  | btn-front-10     | Press only the **front-row, column 10** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 41  | btn-front-11     | Press only the **front-row, column 11** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 42  | btn-front-12     | Press only the **front-row, column 12** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 43  | btn-front-13     | Press only the **front-row, column 13** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| 44  | btn-front-14     | Press only the **front-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter. |

### Phase 3 — analog / multi-position sweeps (8 actions)

The independent brake sweep and the two bail-off positions are captured as
separate actions. A combined capture would show that some bytes changed, but
would not prove which bytes belong to the brake range versus which bytes
belong to either bail-off position.

| NN | slug              | prompt |
|----|-------------------|--------|
| 45 | reverser-sweep    | Move the **Reverser** slowly from Forward to Neutral to Reverse, pausing briefly at each detent, then return to Neutral and pause. Press Enter. |
| 46 | throttle-sweep    | Move the **Throttle / Dynamic Brake** slowly from full Throttle to center/idle to full Dynamic Brake, pausing briefly at each extreme and at center, then return to center. Press Enter. |
| 47 | auto-brake-sweep  | Move the **Auto Brake** slowly from RELEASED to SUP to CS to EMG, pausing briefly at each named position, then return to RELEASED and pause. Press Enter. |
| 48 | indep-brake-sweep | Move the **Independent Brake** through its brake range only, from release to full application, pausing briefly at each end, then return to release. Do not use either bail-off position during this capture. Press Enter. |
| 49 | bailoff-1         | Move the **Independent Brake bail-off** control to the first bail-off position, hold for ~1 second, then release back to rest. Press Enter. |
| 50 | bailoff-2         | Move the **Independent Brake bail-off** control to the second / farthest bail-off position, hold for ~1 second, then release back to rest. Press Enter. |
| 51 | wiper-cycle       | Move the **Wiper** from Off to Slow to Full, pausing briefly at each named position, then return to Off and pause. Press Enter. |
| 52 | lights-cycle      | Move the **Lights** from Off to Dim to Full, pausing briefly at each named position, then return to Off and pause. Press Enter. |

### Phase 4 — post-baseline (1 action)

| NN | slug             | prompt |
|----|------------------|--------|
| 53 | baseline-post    | Do not touch the controller. Wait until the live counter shows at least ~375 reports captured (~3 s at 125 Hz), then press Enter. |

## 6. Operator key bindings

Inside an action prompt, while capture is running:

| Key             | Effect |
|-----------------|--------|
| `Enter`         | Accept this capture; generate hex view; advance to next action. |
| `r` then `Enter` | Discard current capture (delete the partial `.bin`); redo current action. |
| `s` then `Enter` | Skip this action; write a `NN-<slug>.skipped` sentinel; advance. |
| `q` then `Enter` | Quit gracefully. Already-accepted action files are preserved. The script writes `README.md`, records the run as incomplete in `manifest.txt`, marks later actions `not-reached`, and exits 0. |

Anything else the operator types is silently ignored.

## 7. Capture mechanism

### Device resolution

Default device path: auto-detected by scanning
`/sys/class/hidraw/*/device/uevent` for `HID_ID=0003:000005F3:000000D2`.
Override with `--device /dev/hidrawN`. The script fails with a clear
diagnostic and exits non-zero if no matching device is found, if more than
one matching device is found under auto-detect, or if the matching device
exists but is not readable by the current user (with a pointer to the udev /
`plugdev` setup in `plan.md` §4.4). When multiple devices match, the
diagnostic lists the matching `/dev/hidrawN` paths and tells the operator to
rerun with `--device`.

### Capture command

For each action:

```sh
capture_file="$run_dir/$NN-$slug.bin"
dd if="$DEVICE" of="$capture_file" bs=14 iflag=fullblock status=none &
capture_pid=$!
# … display live counter, wait for operator key …
kill -TERM "$capture_pid" 2>/dev/null
wait "$capture_pid" 2>/dev/null
capture_rc=$?
# capture_rc == 143 (128 + SIGTERM) is the expected controlled-stop result and
# must be treated as success. Anything else (other than 0) is a real error:
# print the exit code and a short diagnostic, keep any partial .bin that was
# written, and re-display the action prompt with the redo/skip/quit options
# so the operator can decide how to proceed.
```

The capture command uses `dd` rather than relying on `cat` plus `stdbuf`.
GNU `cat` does not provide a portable report-flushing contract for this use,
and `stdbuf` only controls stdio buffering for programs that use stdio in the
relevant path. With `bs=14 iflag=fullblock`, `dd` writes complete report-sized
blocks as they are read from hidraw and keeps the live counter close to the
actual device stream. The script must install `INT`, `TERM`, and `EXIT` traps
that terminate and reap the active capture process before writing `README.md`
or exiting, so Ctrl-C cannot leave a background reader attached to the device.

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
partial bytes. No parsing of the binary is performed by the counter.

### Hex view generation

On accept: `xxd -c 14 "$run_dir/$NN-$slug.bin" > "$run_dir/$NN-$slug.hex"`.
This is synchronous and runs after the capture process has been reaped, so the
`.bin` is final.

### Pre-flight checks at script start

1. `[ -r "$DEVICE" ]` — fail with udev/plugdev guidance if not readable.
2. `command -v xxd >/dev/null`, `command -v dd >/dev/null`, and
   `dd if=/dev/null of=/dev/null bs=14 iflag=fullblock status=none count=0`
   — fail clearly if `xxd` is absent or if the available `dd` does not
   support the flags used by the capture command.
3. Under auto-detect, exactly one RailDriver must match. Zero matches and
   multiple matches are both errors; multiple matches must be listed in the
   diagnostic.
4. When `--device` is supplied, the resolved device's `HID_ID` must match
   `0003:000005F3:000000D2`; fail loudly if it does not (prevents silently
   capturing the wrong device's bytes). This check is skipped under
   auto-detect, where `HID_ID` is already the selection criterion.

### `manifest.txt`

Created **before** any captures and finalized after the last action, `q`, or a
signal-triggered shutdown. Format:

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
run_complete:   yes | no
ended_by:       completed | operator-quit | signal
last_action:    <last action number reached, or empty>
```

### `README.md`

Written **after** the last action (or on `q`). The header repeats
`run_complete`, `ended_by`, and `last_action` from `manifest.txt`. The body is
a markdown table with columns `#`, `slug`, `prompt`, `file`, `bytes`,
`reports`, `partial_trailing_bytes`, and `status`. `status` is one of
`captured`, `skipped`, or `not-reached` (for actions after a `q` quit or
signal-triggered shutdown). Missing values appear as `—`.

## 8. Evidence retention

Each run directory includes raw captures and generated summaries. If a run is
used as evidence for the controller mapping, retain the full run directory or
archive it unchanged. `rd-record.sh` writes `SHA256SUMS` after capture to cover
the raw `.bin`, generated `.hex`, `manifest.txt`, `README.md`, and `.skipped`
sentinels present at that point. When `rd-analyze.sh` writes analysis outputs
into the run directory, it refreshes that run's `SHA256SUMS`, preserving the
raw capture entries and adding `analysis.md` and `analysis.csv`. When
`rd-analyze.sh --out <dir>` writes analysis outputs outside the run directory,
it does not modify the source run; instead it writes a `SHA256SUMS` beside
each generated per-run output set under `<dir>/<run-basename>/`. Cross-run
outputs follow the same rule: the checksum file is written beside
`cross-run-analysis.md` and `cross-run-analysis.csv`, either under
`docs/rpi-raildriver/captures/` by default or under `<dir>/` when `--out` is
used.

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

For a single run directory, the script emits one row for every defined action
`00`..`53`. It processes any captured `.bin` files together with that run's
`00-baseline-pre.bin` and `53-baseline-post.bin` as follows:

1. Split each `.bin` into complete 14-byte reports. If a file length is not
   divisible by 14, ignore the trailing partial bytes for report analysis and
   record their count in `partial_trailing_bytes`.
2. Build a global baseline model from all complete reports in
   `00-baseline-pre.bin` and `53-baseline-post.bin`. For each byte index,
   record:
   - the set of distinct baseline byte values;
   - the modal baseline value (most common value; lowest value wins ties);
   - the baseline minimum and maximum.

   Missing or empty baselines:
   - If both `00-baseline-pre.bin` and `53-baseline-post.bin` are missing,
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
   definitive for that byte; `baseline_values`, `local_rest_values`,
   `rest_values`, `asserted_values`, and `observed_values` remain the
   authoritative columns.
3. For each captured non-baseline action, build an action-local rest model
   from the framing that the operator captured at the start and end of the
   action:
   - Use the first 50 complete reports and the last 50 complete reports as
     the local rest windows. The windows must not overlap.
   - If the action has fewer than 100 complete reports, set
     `local_rest_usable=no`, add `insufficient-local-rest` to
     `quality_flags`, and use only the global baseline as the rest model.
   - If the leading and trailing local rest windows disagree for a byte
     (i.e. the set of distinct byte values observed in the leading window
     differs from the set observed in the trailing window), or either local
     rest window contains values outside that byte's global baseline value
     set, record the byte in `quality_flags` as
     `byte<i>=local-rest-drift`. The script still reports the descriptive
     data; it does not suppress the action.
   - When local rest is usable, the effective rest value set for a byte is
     the union of that byte's global baseline values and action-local rest
     values. When local rest is not usable, the effective rest value set is
     the global baseline value set.
4. For each captured action, identify changed byte indices using a minimum
   changed-report threshold. Byte index `i` changed if at least three
   complete action reports contain a value for byte `i` outside the effective
   rest value set. One or two outside-rest reports do not mark the byte as
   changed; instead they are recorded in `quality_flags` as
   `byte<i>=below-threshold-outlier:<count>`. This prevents a single noisy
   report from becoming a false button or switch mapping while still making
   the anomaly visible.
5. For each changed byte index, record:
   - `baseline_values`: that byte's global baseline value set;
   - `local_rest_values`: that byte's action-local rest value set, or empty
     if local rest was not usable;
   - `rest_values`: action values for that byte that are also present in the
     effective rest value set;
   - `asserted_values`: action values for that byte that are not present in
     the effective rest value set;
   - `observed_values`: all distinct action values observed for that byte;
   - `bit_mask`: the OR of `(value XOR modal_baseline_value)` for every
     action value in `asserted_values`;
   - `min_max`: the minimum and maximum action values for that byte;
   - `plateau_values`: values that appear in runs of at least 10 consecutive
     complete reports for that byte;
   - `changed_report_count`: the count of complete action reports whose byte
     value is outside the effective rest value set.

For analog / multi-position sweep actions, including Wiper, Lights, and the
named detent captures, `min_max`, `observed_values`, and `plateau_values` are
the primary data products. For switches, buttons, and hat directions,
`rest_values`, `asserted_values`, and `bit_mask` are the primary data
products. The same columns are emitted for every action so the output remains
machine-readable.

Output: `run-NNN/analysis.md` — header fields followed by a markdown table,
one row per defined action. The header records
`baseline_source: pre+post | pre-only | post-only`,
`local_rest_window_reports: 50`, `min_changed_reports: 3`, and
`plateau_min_run_reports: 10`. The table has columns:

| column | meaning |
|--------|---------|
| `action#` | The action's stable index, `00`..`53`. |
| `slug` | The action's slug without its numeric prefix (e.g. `range-up`). |
| `file_stem` | The expected file stem, `NN-<slug>` (e.g. `01-range-up`). |
| `status` | `captured`, `skipped`, or `not-reached`. |
| `report_count` | Number of complete 14-byte HID reports in the action's `.bin`. |
| `partial_trailing_bytes` | Number of ignored trailing bytes after the last complete 14-byte report. |
| `local_rest_usable` | `yes` if the action had non-overlapping leading and trailing rest windows; otherwise `no`. Empty for baseline, skipped, and not-reached actions. |
| `byte_indices_changed` | Comma-separated list of byte indices (0..13) whose values went outside the effective rest value set at least three times during the action. Empty if no byte changed. |
| `bit_masks` | For each changed byte, the bit mask of bits that changed relative to that byte's modal baseline value, in the form `byte<i>=0x<hex>`; multiple entries comma-separated. |
| `baseline_values` | For each changed byte, the global baseline value set, in the form `byte<i>={0x<hex>,0x<hex>,...}`. |
| `local_rest_values` | For each changed byte, the action-local rest value set, in the form `byte<i>={0x<hex>,0x<hex>,...}`. |
| `rest_values` | For each changed byte, the action values that were also present in the effective rest value set. |
| `asserted_values` | For each changed byte, the action values that were not present in the effective rest value set. |
| `observed_values` | For each changed byte, all distinct action values observed for that byte. |
| `min_max` | For each changed byte, `byte<i>=[min,max]` in hex. |
| `plateau_values` | For each changed byte, values that appeared in runs of at least 10 consecutive complete reports. |
| `changed_report_count` | For each changed byte, the number of complete reports outside the effective rest value set. |
| `quality_flags` | Comma-separated descriptive flags such as `insufficient-local-rest`, `byte<i>=local-rest-drift`, or `byte<i>=below-threshold-outlier:<count>`. Empty if none. |

A companion `run-NNN/analysis.csv` is also written with the same columns
for programmatic consumption.

The script does no inference about *which* control caused which bit/byte
change. The slug is reported as captured; the operator's `slug → physical
control` mapping is documented in §5 of this plan and in `README.md` of each
run, both of which are produced earlier in the workflow.

Baseline actions `00` and `53` appear in the table with `status=captured`
when their `.bin` files exist, but their change-specific data columns are
empty. Skipped actions appear in the table with `status=skipped` and empty
data columns. Actions whose `.bin` is missing (e.g. interrupted mid-action)
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
| `plateau_values_consistent` | `yes` if the per-byte plateau values are identical across all compared runs; otherwise `no`. |
| `per_run_min_max` | For each changed byte, the `[min,max]` interval reported by each run. |
| `per_run_plateau_values` | For each changed byte, the `plateau_values` reported by each run. |
| `extrema_spread` | For each changed byte, `min_spread=(max(run_mins)-min(run_mins))` and `max_spread=(max(run_maxes)-min(run_maxes))`. |
| `quality_flags` | Union of per-run `quality_flags` values that affect this action. Empty if none. |
| `discrepancies` | If any of the above are `no`, a free-text description of which runs disagreed and how. Otherwise empty. |

The cross-run script does not pick a "winner" between disagreeing runs and
does not annotate any run as correct or incorrect. For analog extrema and
plateaus it does not reduce the data to a pass/fail boolean; it reports the
per-run values and their spread for human review.

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
- The script's only outputs are the per-run analysis files, cross-run analysis
  files, and checksum files described above.

### 9.4 Command-line interface

- `rd-analyze.sh` — with no arguments: scan
  `docs/rpi-raildriver/captures/run-*/`, write each one's `analysis.md` and
  `analysis.csv`, and write the cross-run summary if more than one run
  exists.
- `rd-analyze.sh --run-dir <path>` — analyze only the named run directory;
  no cross-run output.
- `rd-analyze.sh --out <dir>` — write outputs under `<dir>` instead of next
  to the captures. Per-run output is written to
  `<dir>/<run-basename>/analysis.md` and
  `<dir>/<run-basename>/analysis.csv`; for example, analyzing
  `docs/rpi-raildriver/captures/run-001` writes
  `<dir>/run-001/analysis.{md,csv}`. Cross-run output is written to
  `<dir>/cross-run-analysis.md` and `<dir>/cross-run-analysis.csv`.
  Without `--out`, per-run output defaults to
  `<run-dir>/analysis.{md,csv}` and cross-run output defaults to
  `docs/rpi-raildriver/captures/cross-run-analysis.{md,csv}`.

## 10. Deliverables

| Artifact | Notes |
|----------|-------|
| `docs/rpi-raildriver/capture-plan.md` | This document. |
| `docs/rpi-raildriver/rd-record.sh`    | Implements §3–§7. |
| `docs/rpi-raildriver/rd-analyze.sh`   | Implements §9. Reads `run-NNN/` directories produced by `rd-record.sh`; writes per-run `analysis.md` / `analysis.csv` and cross-run `cross-run-analysis.md` / `cross-run-analysis.csv`. Does not reference any prior claim. |
| `.gitignore` entry for `docs/rpi-raildriver/captures/` | Recommended default for exploratory runs; reference evidence sets can still be committed, attached to an issue/PR, or archived with `SHA256SUMS`. |

This table lists source artifacts only. The per-run and cross-run output
files (`analysis.md`, `analysis.csv`, `cross-run-analysis.md`,
`cross-run-analysis.csv`, `manifest.txt`, `README.md`, `SHA256SUMS` files,
plus the raw `.bin` and `.hex` captures) are produced by the scripts at
runtime and live under `docs/rpi-raildriver/captures/`.
