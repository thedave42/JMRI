# RailDriver structured capture protocol

**Original Prompt**
>  I'd like to capture data from the device, but do it in a much more structured way.  One that both proves empirically
   which byte in the report layout corresponds to which input on the Raildriver controller, and that documents the
  different possible states of each input on the controller. The file @docs/rpi-raildriver/control-inventory.md
  contains a list of all of the labeled inputs on the Raildriver controller as well as what type of input each is.
  For buttons and switches we need to capture information about what happens when they are activated - i.e. pressing a
   button, pushing a spdt switch up or down, or pushing a hat switch in one of 8 directions.  For analog controls we
  need to understand the minimum and maximum values through their range.  We do not need to know the values of the
  stop points e.g. we don't need to know the idle position of the throttle, or the neutral position of the reverser.
  Those values will need to be dynamically configured as part of a calibration process in the final implementation of
  the plugin so the user can position the analog control apprpropriately and save the value of its position.  They are
   not needed to empirically validate the input report layout.  Create a plan that allows me to capture this data
  input by input.  Use the information in control-inventory.md to define the list of inputs. Create a script that
  prompts me with an action to perform, has an unlimited amount of time for me to perform that action, and that will
  continue to the next prompt (or redo the current capture) when the right key is press.  e.g. Press and release the
  bell button for 1 second, and press Enter when done, or (r) to redo. The script should let me run it multiple times
  and capture separate sets of data from each run so that I can review and compare them.  Present the plan to me and
  wait for my feedback before proceeding.

## 1. Problem statement

The existing captures under `docs/rpi-raildriver/test-data/` (five `xxd -c 14`
streams, 2,046 reports total) record the device's 14-byte HID input report
shape but do not pair any HID byte to any physical control, because no record
was kept of which control was being touched at which moment in the stream.

This document specifies a structured protocol — capture plus analysis —
whose explicit goal is to produce a per-input mapping from the device that
proves which byte in the 14-byte HID report corresponds to which physical
control, and documents the observable states of each input:

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
   control is moved through its physical range, and the minimum and maximum
   byte values observed at each such index.

## 2. Scope

For every labeled control in `control-inventory.md`:

- **Switches and buttons** — capture HID reports while the control is held in
  each of its asserted states, with rest-state framing immediately before and
  after. SPDT switches get one capture per direction. The hat switch gets one
  capture per cardinal direction (UP, RIGHT, DOWN, LEFT — see §3 *Established
  device characteristics*). Each individual button gets its own capture.
- **Analog controls** — capture HID reports while the control is swept through
  its full physical range so the minimum and maximum byte values are clearly
  visible.
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

`rd-record.sh` runs each action in one of two modes:

**Baseline actions (00 and 53):** the script prints positioning instructions
and waits for the operator to press `Enter` to start. It then captures a
fixed number of complete HID reports (default 60, about 5 seconds at the
device's measured report rate; see *Established device characteristics*
below), stops, and advances automatically.

**Non-baseline actions (01–52):** the script prints the action's prompt and
starts capturing immediately. No key press is required to start. During the
first portion of the capture the script learns the per-byte "rest band" of
values from the unchanged report stream. It then watches the live stream
and detects two events: an **onset** (the report stream leaves the rest
band) followed by a **settle** (the report stream stops changing). When
settle is detected the capture is stopped, the hex view is generated, and
the script advances to the next action.

In both modes the operator can interrupt the in-flight capture at any time
with `r` (redo), `s` (skip), or `q` (quit). The detailed event grammar and
timeouts are in §6 and §7.

### Established device characteristics

These facts have been measured empirically and are the basis for the
parameter defaults in §7. The supporting capture is
`docs/rpi-raildriver/test-data/hat-rate-capture.log` (481 reports captured
on 2026-05-01 with `docs/rpi-raildriver/hat-rate-probe.sh`).

- **HID report rate: ~11.5 Hz** (measured 11.52 Hz; ~88 ms inter-report
  period). The device polls slowly. All time-budget defaults in this plan
  are derived from this rate. This is *not* a buffering artifact; the probe
  reads `/dev/hidraw0` directly with `os.read()` and excludes any pipeline
  framing.
- **Hat switch: 4-direction hardware with software diagonals.** The hat
  reports four single bits (one per cardinal direction). Diagonals are
  produced by two adjacent cardinal bits being asserted simultaneously when
  the operator pushes the hat between two switches; there are no separate
  bits for diagonals. Bit map established by the probe:
    | Direction | Byte | Bit  |
    |-----------|------|------|
    | UP        | 10   | 0x40 |
    | RIGHT     | 10   | 0x80 |
    | DOWN      | 11   | 0x01 |
    | LEFT      | 11   | 0x02 |

  These specific byte/bit values are restated here only for context. The
  analysis script (§9.4) must not rely on them; it must rediscover the
  mapping from each run's captured data.

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
│   │                             # operator name (optional, --operator flag),
│   │                             # detection-parameter values used for the run
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

## 5. Action list (54 actions, fixed order)

For actions 01–52, the script shows a shared framing instruction before the
per-action prompt:

> Wait briefly at the start of the capture so the script can learn the
> resting state. Perform the requested action. Then return the control to
> rest — the script will detect that and advance automatically.

For actions 00 and 53, the script shows positioning instructions and waits
for the operator to press `Enter` to start. The capture then runs for about
5 seconds and advances automatically.

### Phase 0 — pre-baseline (1 action)

| NN | slug             | prompt |
|----|------------------|--------|
| 00 | baseline-pre     | Set all analog controls to their baseline positions before starting the capture: **Reverser** to full Forward, **Throttle / Dynamic Brake** to full Throttle, **Auto Brake** to fully RELEASED, **Independent Brake** to full release (no bail-off), **Wiper** to Off, **Lights** to Off. When all controls are in position, press Enter to start. Do not touch the controller during the capture; it runs for about 5 seconds and advances automatically. |

### Phase 1 — named switches and buttons (16 actions)

| NN | slug         | physical control                           | prompt |
|----|--------------|--------------------------------------------|--------|
| 01 | range-up     | Range switch                               | Push the **Range** switch UP and hold for ~1 second, then release. |
| 02 | range-down   | Range switch                               | Push the **Range** switch DOWN and hold for ~1 second, then release. |
| 03 | estop-up     | E-Stop switch                              | Push the **E-Stop** switch UP and hold for ~1 second, then release. |
| 04 | estop-down   | E-Stop switch                              | Push the **E-Stop** switch DOWN and hold for ~1 second, then release. |
| 05 | alert        | Alert button                               | Press the **Alert** button and hold for ~1 second, then release. |
| 06 | sand         | Sand button                                | Press the **Sand** button and hold for ~1 second, then release. |
| 07 | p-button     | P button (item 5)                          | Press the **P** button and hold for ~1 second, then release. |
| 08 | bell         | Bell button                                | Press the **Bell** button and hold for ~1 second, then release. |
| 09 | horn-up      | Horn switch                                | Push the **Horn** switch UP and hold for ~1 second, then release. |
| 10 | horn-down    | Horn switch                                | Push the **Horn** switch DOWN and hold for ~1 second, then release. |
| 11 | spdt42-up    | user-assignable SPDT (item 42)             | Push the **user-assignable SPDT** (item 42) UP and hold for ~1 second, then release. |
| 12 | spdt42-down  | user-assignable SPDT (item 42)             | Push the **user-assignable SPDT** (item 42) DOWN and hold for ~1 second, then release. |
| 13 | hat43-up     | hat switch (item 43)                       | Push the **hat switch** (item 43) straight UP (cardinal only — not toward a corner) and hold for ~1 second, then release. |
| 14 | hat43-right  | hat switch (item 43)                       | Push the **hat switch** (item 43) straight RIGHT (cardinal only — not toward a corner) and hold for ~1 second, then release. |
| 15 | hat43-down   | hat switch (item 43)                       | Push the **hat switch** (item 43) straight DOWN (cardinal only — not toward a corner) and hold for ~1 second, then release. |
| 16 | hat43-left   | hat switch (item 43)                       | Push the **hat switch** (item 43) straight LEFT (cardinal only — not toward a corner) and hold for ~1 second, then release. |

The hat is 4-direction hardware (see §3 *Established device characteristics*).
Diagonals are not separately captured: any diagonal is the simultaneous
assertion of two adjacent cardinal bits, so the four cardinal captures fully
characterise the hat. Operators must therefore push each cardinal cleanly,
without rolling toward a neighbouring direction.

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

(The numbers in this diagram are this protocol's action numbers, not
inventory item numbers.)

| NN  | slug             | prompt |
|-----|------------------|--------|
| 17  | btn-back-01      | Press only the **back-row, leftmost** button (column 1). Hold for ~1 second, then release. |
| 18  | btn-back-02      | Press only the **back-row, column 2** button (counting from the left). Hold for ~1 second, then release. |
| 19  | btn-back-03      | Press only the **back-row, column 3** button (counting from the left). Hold for ~1 second, then release. |
| 20  | btn-back-04      | Press only the **back-row, column 4** button (counting from the left). Hold for ~1 second, then release. |
| 21  | btn-back-05      | Press only the **back-row, column 5** button (counting from the left). Hold for ~1 second, then release. |
| 22  | btn-back-06      | Press only the **back-row, column 6** button (counting from the left). Hold for ~1 second, then release. |
| 23  | btn-back-07      | Press only the **back-row, column 7** button (counting from the left). Hold for ~1 second, then release. |
| 24  | btn-back-08      | Press only the **back-row, column 8** button (counting from the left). Hold for ~1 second, then release. |
| 25  | btn-back-09      | Press only the **back-row, column 9** button (counting from the left). Hold for ~1 second, then release. |
| 26  | btn-back-10      | Press only the **back-row, column 10** button (counting from the left). Hold for ~1 second, then release. |
| 27  | btn-back-11      | Press only the **back-row, column 11** button (counting from the left). Hold for ~1 second, then release. |
| 28  | btn-back-12      | Press only the **back-row, column 12** button (counting from the left). Hold for ~1 second, then release. |
| 29  | btn-back-13      | Press only the **back-row, column 13** button (counting from the left). Hold for ~1 second, then release. |
| 30  | btn-back-14      | Press only the **back-row, rightmost** button (column 14). Hold for ~1 second, then release. |
| 31  | btn-front-01     | Press only the **front-row, leftmost** button (column 1). Hold for ~1 second, then release. |
| 32  | btn-front-02     | Press only the **front-row, column 2** button (counting from the left). Hold for ~1 second, then release. |
| 33  | btn-front-03     | Press only the **front-row, column 3** button (counting from the left). Hold for ~1 second, then release. |
| 34  | btn-front-04     | Press only the **front-row, column 4** button (counting from the left). Hold for ~1 second, then release. |
| 35  | btn-front-05     | Press only the **front-row, column 5** button (counting from the left). Hold for ~1 second, then release. |
| 36  | btn-front-06     | Press only the **front-row, column 6** button (counting from the left). Hold for ~1 second, then release. |
| 37  | btn-front-07     | Press only the **front-row, column 7** button (counting from the left). Hold for ~1 second, then release. |
| 38  | btn-front-08     | Press only the **front-row, column 8** button (counting from the left). Hold for ~1 second, then release. |
| 39  | btn-front-09     | Press only the **front-row, column 9** button (counting from the left). Hold for ~1 second, then release. |
| 40  | btn-front-10     | Press only the **front-row, column 10** button (counting from the left). Hold for ~1 second, then release. |
| 41  | btn-front-11     | Press only the **front-row, column 11** button (counting from the left). Hold for ~1 second, then release. |
| 42  | btn-front-12     | Press only the **front-row, column 12** button (counting from the left). Hold for ~1 second, then release. |
| 43  | btn-front-13     | Press only the **front-row, column 13** button (counting from the left). Hold for ~1 second, then release. |
| 44  | btn-front-14     | Press only the **front-row, rightmost** button (column 14). Hold for ~1 second, then release. |

### Phase 3 — analog / multi-position sweeps (8 actions)

The independent brake sweep and the two bail-off positions are captured as
separate actions.

| NN | slug              | prompt |
|----|-------------------|--------|
| 45 | reverser-sweep    | Move the **Reverser** smoothly from full Forward to full Reverse, then return to full Forward. |
| 46 | throttle-sweep    | Move the **Throttle / Dynamic Brake** smoothly from full Throttle to full Dynamic Brake, then return to full Throttle. |
| 47 | auto-brake-sweep  | Move the **Auto Brake** smoothly from fully RELEASED to EMG, then return to RELEASED. |
| 48 | indep-brake-sweep | Move the **Independent Brake** smoothly through its brake range only, from full release to full application, then return to full release. Do not use either bail-off position during this capture. |
| 49 | bailoff-1         | Move the **Independent Brake bail-off** control to the first bail-off position, hold for ~1 second, then release back to rest. |
| 50 | bailoff-2         | Move the **Independent Brake bail-off** control to the second / farthest bail-off position, hold for ~1 second, then release back to rest. |
| 51 | wiper-cycle       | Move the **Wiper** smoothly through its full physical range and back. |
| 52 | lights-cycle      | Move the **Lights** smoothly through its full physical range and back. |

### Phase 4 — post-baseline (1 action)

| NN | slug             | prompt |
|----|------------------|--------|
| 53 | baseline-post    | Return all analog controls to the same baseline positions used for the pre-baseline: **Reverser** full Forward, **Throttle** full Throttle, **Auto Brake** fully RELEASED, **Independent Brake** full release (no bail-off), **Wiper** Off, **Lights** Off. When all controls are in position, press Enter to start. Do not touch the controller during the capture; it runs for about 5 seconds and advances automatically. |

## 6. Operator key bindings

| Context | Key | Effect |
|---|---|---|
| Baseline pre-start prompt (actions 00, 53) | `Enter` | Start the baseline capture. |
| Baseline pre-start prompt | `s` | Skip this baseline; write `NN-<slug>.skipped` sentinel; advance. |
| Baseline pre-start prompt | `q` | Quit gracefully. Already-completed action files are preserved; the script writes `README.md`, records the run as incomplete in `manifest.txt`, marks later actions `not-reached`, and exits 0. |
| Capture in progress (baseline or non-baseline) | `r` | Discard the in-flight capture (delete the partial `.bin`); redo the current action. |
| Capture in progress | `s` | Discard the in-flight capture; write `NN-<slug>.skipped` sentinel; advance. |
| Capture in progress | `q` | Same as the baseline `q` above. |
| Timeout re-prompt (no onset detected, or no settle within budget) | `r` / `s` / `q` | Same as the corresponding capture-in-progress effect. |

`Enter` has no effect during a non-baseline capture or at a timeout
re-prompt; the script auto-advances on settle detection. Anything else the
operator types is silently ignored.

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

### Capture process

For each action, the script invokes a Python helper that opens
`/dev/hidraw0` directly and writes complete 14-byte HID reports to the
action's `.bin` file as they arrive. The helper owns the per-action
lifecycle:

- it reads the device with non-blocking I/O so it can simultaneously
  monitor the operator's stdin for `r`, `s`, `q`, and (for baseline
  actions) `Enter`;
- it implements the termination logic for the action's mode (baseline or
  non-baseline);
- it returns control to the bash script with one of the outcomes
  `accept`, `redo`, `skip`, `quit`, or `timeout`, plus the final byte and
  report counts.

The bash script handles everything around the capture: arg parsing,
prompts, run-directory creation, manifest/README/SHA256SUMS, and signal
traps. The Python helper handles the live byte stream and the detection
state machine. The split puts the byte-by-byte stream processing in a
language with non-blocking `select`/`read` and clean integer/byte
arithmetic, while leaving overall control flow in bash.

### Baseline action mode (00, 53)

1. The script prints the action's positioning instructions and waits for
   the operator to press `Enter` (`s` and `q` are also accepted at this
   prompt and behave per §6).
2. On `Enter`, the helper starts capturing and updates a single live
   status line:

   ```
   [00 baseline-pre] baseline… 187/250 reports                         [r=redo s=skip q=quit]
   ```

3. When `target_baseline_reports` complete reports have been written
   (default: 60, about 5 seconds at the device's measured report rate of
   ~11.5 Hz), the helper stops, the script generates the hex view, accepts,
   and advances.
4. `r`/`s`/`q` keys are honored throughout the capture and behave per §6.

### Non-baseline action mode (01–52)

1. The script prints the framing instruction (§5) and the per-action
   prompt, then immediately starts the capture with no further key press.
2. The helper accumulates the first `pre_action_window` complete reports
   (default: 12, about 1 second at the measured ~11.5 Hz report rate) into
   the **rest band**: for each byte index `0..13`, the set of distinct
   values seen during that window. The live status line shows progress
   through the rest-band-learning phase:

   ```
   [01 range-up] learning rest band… 32/50 reports                     [r=redo s=skip q=quit]
   ```

3. After the rest-band-learning phase the helper watches each new report.
   For each byte, the helper computes whether the byte's current value is
   *out of band*. The test depends on byte index:
   - **Bytes 0..6 (analog):** out of band iff `min(|value − v| for v in
     rest_band[i]) > noise_tolerance`. This absorbs ±`noise_tolerance` LSB
     ADC jitter that did not appear during the 1-second learning window
     but routinely surfaces seconds later when fingers rest on a lever.
   - **Bytes 7..13 (button bits):** out of band iff `value not in
     rest_band[i]`. Button bits do not jitter; they are bit-exact.

   An **onset** is declared when `onset_threshold` consecutive reports
   each contain at least one byte that is out of band by the byte-class
   rule above (default: 3 consecutive reports, ~260 ms at the measured
   ~11.5 Hz report rate). The status line transitions to:

   ```
   [01 range-up] capturing… 87 reports — waiting for action…           [r=redo s=skip q=quit]
   ```

4. After onset, the helper watches for **settle**: a window of
   `settle_threshold` consecutive reports during which each byte's value
   is stable (per-byte max minus min ≤ 1 across the window) (default: 10
   consecutive reports, ~870 ms at the measured ~11.5 Hz report rate). The
   status line shows:

   ```
   [01 range-up] capturing… 134 reports — onset at 102, waiting for rest…  [r=redo s=skip q=quit]
   ```

5. When settle is achieved, the helper stops, the script generates the
   hex view, accepts, and advances.
6. `r`/`s`/`q` keys are honored throughout the capture and behave per §6.

### Timeouts

The helper enforces a single per-action time budget,
`max_action_time` (default: 60 seconds), measured from the start of the
capture. If the budget elapses while either:

- no onset has been detected, or
- onset has been detected but settle has not,

the helper stops the capture, discards the in-flight `.bin`, and the
script prints a short diagnostic indicating which condition fired
(`no input detected` or `action did not return to rest`) followed by a
prompt with `r`/`s`/`q` only. The operator's choice determines the next
step. Successive timeouts on the same action just re-display the same
prompt; there is no automatic skip.

### Tuning parameters

All parameters are settable on the command line and have conservative
defaults appropriate for the device's measured report rate of ~11.5 Hz
(see §3 *Established device characteristics*):

| Flag | Default | Meaning |
|---|---|---|
| `--target-baseline-reports N` | 60 | Number of complete reports to capture per baseline action before auto-accepting (~5 s at ~11.5 Hz). |
| `--pre-action-window N` | 12 | Number of complete reports used to learn the rest band at the start of each non-baseline action (~1 s at ~11.5 Hz). |
| `--onset-threshold N` | 3 | Number of consecutive out-of-band reports required to declare onset (~260 ms at ~11.5 Hz). |
| `--noise-tolerance N` | 1 | Absolute LSB tolerance applied to analog bytes (0..6) when testing out-of-band: byte `i` is out of band only if its distance to the nearest value in `rest_band[i]` exceeds `N`. Does not apply to button-bit bytes (7..13). |
| `--settle-threshold N` | 10 | Number of consecutive stable reports required to declare settle after onset (~870 ms at ~11.5 Hz). |
| `--max-action-time S` | 60 | Per-action time budget, in seconds, after which the capture is aborted and the operator is re-prompted. |

The defaults are starting points; expect to tune them after the first real
run. The values used for a run are recorded in `manifest.txt` so analysis
output is interpretable in the context of the parameters that produced it.

### Hex view generation

On accept: `xxd -c 14 "$run_dir/$NN-$slug.bin" > "$run_dir/$NN-$slug.hex"`.
This is synchronous and runs after the capture has been stopped, so the
`.bin` is final.

### Pre-flight checks at script start

1. `[ -r "$DEVICE" ]` — fail with udev/plugdev guidance if not readable.
2. `command -v xxd >/dev/null`, `command -v python3 >/dev/null` — fail
   clearly if either tool is absent.
3. Under auto-detect, exactly one RailDriver must match. Zero matches and
   multiple matches are both errors; multiple matches must be listed in the
   diagnostic.
4. When `--device` is supplied, the resolved device's `HID_ID` must match
   `0003:000005F3:000000D2`; fail loudly if it does not (prevents silently
   capturing the wrong device's bytes). This check is skipped under
   auto-detect, where `HID_ID` is already the selection criterion.

### `manifest.txt`

Created **before** any captures and finalized after the last action, `q`,
or a signal-triggered shutdown. Format:

```
captured_at:               2026-04-30T20:45:33Z
hostname:                  <hostname>
uname:                     <output of uname -a>
device:                    /dev/hidraw0
hid_id:                    0003:000005F3:000000D2
script_version:            <git SHA short, or "uncommitted">
operator:                  <value of --operator, or "(not specified)">
run_dir:                   run-NNN
notes:                     <value of --notes, or empty>
target_baseline_reports:   60
pre_action_window:         12
onset_threshold:           3
noise_tolerance:           1
settle_threshold:          10
max_action_time:           60
run_complete:              yes | no
ended_by:                  completed | operator-quit | signal
last_action:               <last action number reached, or empty>
```

### `README.md`

Written **after** the last action (or on `q`). The header repeats
`run_complete`, `ended_by`, and `last_action` from `manifest.txt`. The body
is a markdown table with columns `#`, `slug`, `prompt`, `file`, `bytes`,
`reports`, `partial_trailing_bytes`, and `status`. `status` is one of
`captured`, `skipped`, or `not-reached` (for actions after a `q` quit or
signal-triggered shutdown). Missing values appear as `—`.

### Error handling

If the Python helper exits abnormally (non-zero exit code other than the
defined outcomes), the script prints the exit code and a short diagnostic,
keeps any partial `.bin` that was written so far, and re-displays the
action prompt with `r`/`s`/`q` so the operator can decide how to proceed.
The script must install `INT`, `TERM`, and `EXIT` traps that terminate
and reap the helper before writing `README.md` or exiting, so Ctrl-C
cannot leave the helper attached to the device.

The capture may still end with a trailing partial HID report depending on
exactly when termination interrupts a read; the analysis step must detect
and report any trailing byte count that is not a complete 14-byte record.

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
summary.

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
   - Use the leading and trailing complete reports as the local rest windows.
     The window size and the minimum report count for local rest to be usable
     are implementation details determined after the device's actual report
     rate is known.
   - If the action has too few complete reports for non-overlapping windows,
     set `local_rest_usable=no`, add `insufficient-local-rest` to
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
4. For each captured action, identify changed byte indices. Byte index `i`
   changed if more than a trivial number of complete action reports contain a
   value for byte `i` outside the effective rest value set. The exact
   threshold is an implementation detail. Reports below the threshold do not
   mark the byte as changed; instead they are recorded in `quality_flags` as
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
   - `changed_report_count`: the count of complete action reports whose byte
     value is outside the effective rest value set.

For analog / multi-position sweep actions, including Wiper and Lights,
`min_max` and `observed_values` are the primary data products. For switches, buttons, and hat directions,
`rest_values`, `asserted_values`, and `bit_mask` are the primary data
products. The same columns are emitted for every action so the output remains
machine-readable.

Output: `run-NNN/analysis.md` — header fields followed by a markdown table,
one row per defined action. The header records
`baseline_source: pre+post | pre-only | post-only`. The table has
columns:

| column | meaning |
|--------|---------|
| `action#` | The action's stable index, `00`..`53`. |
| `slug` | The action's slug without its numeric prefix (e.g. `range-up`). |
| `file_stem` | The expected file stem, `NN-<slug>` (e.g. `01-range-up`). |
| `status` | `captured`, `skipped`, or `not-reached`. |
| `report_count` | Number of complete 14-byte HID reports in the action's `.bin`. |
| `partial_trailing_bytes` | Number of ignored trailing bytes after the last complete 14-byte report. |
| `local_rest_usable` | `yes` if the action had non-overlapping leading and trailing rest windows; otherwise `no`. Empty for baseline, skipped, and not-reached actions. |
| `byte_indices_changed` | Comma-separated list of byte indices (0..13) whose values went outside the effective rest value set more than a trivial number of times during the action. Empty if no byte changed. |
| `bit_masks` | For each changed byte, the bit mask of bits that changed relative to that byte's modal baseline value, in the form `byte<i>=0x<hex>`; multiple entries comma-separated. |
| `baseline_values` | For each changed byte, the global baseline value set, in the form `byte<i>={0x<hex>,0x<hex>,...}`. |
| `local_rest_values` | For each changed byte, the action-local rest value set, in the form `byte<i>={0x<hex>,0x<hex>,...}`. |
| `rest_values` | For each changed byte, the action values that were also present in the effective rest value set. |
| `asserted_values` | For each changed byte, the action values that were not present in the effective rest value set. |
| `observed_values` | For each changed byte, all distinct action values observed for that byte. |
| `min_max` | For each changed byte, `byte<i>=[min,max]` in hex. |
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
| `per_run_min_max` | For each changed byte, the `[min,max]` interval reported by each run. |
| `extrema_spread` | For each changed byte, `min_spread=(max(run_mins)-min(run_mins))` and `max_spread=(max(run_maxes)-min(run_maxes))`. |
| `quality_flags` | Union of per-run `quality_flags` values that affect this action. Empty if none. |
| `discrepancies` | If any of the above are `no`, a free-text description of which runs disagreed and how. Otherwise empty. |

### 9.3 Mapping output

For each analyzed run, the script also writes `run-NNN/mapping.md` (and
`mapping.csv`). This is the primary human-readable deliverable — the
document that proves which byte corresponds to which input and records
the observed states.

The script reads `control-inventory.md` to obtain the input name and type
for each control, then joins that with the per-action analysis data using
the slug-to-inventory correspondence defined in §5. Each inventory input
gets one section in the mapping, containing:

**For buttons and switches (including hat directions):**

| field | meaning |
|-------|---------|
| `input` | Control name from the inventory (e.g. `Bell`, `Range`, `Hat switch (item 43)`). |
| `inventory_item` | Item number from the inventory, or physical position for the button grid. |
| `type` | Control type from the inventory (e.g. `Button`, `SPDT momentary`, `Hat switch`). |
| `state` | The asserted state captured (e.g. `pressed`, `up`, `down`, `up-right`). |
| `byte_index` | The byte index (0..13) that changed. If multiple bytes changed, one row per byte. |
| `rest_value` | The byte value at rest (hex). |
| `asserted_value` | The byte value when asserted (hex). |
| `bit_mask` | The bits that differ between rest and asserted (hex). |

**For analog controls:**

| field | meaning |
|-------|---------|
| `input` | Control name from the inventory (e.g. `Reverser`, `Throttle / Dynamic Brake`). |
| `inventory_item` | Item number from the inventory. |
| `type` | Control type from the inventory. |
| `byte_index` | The byte index (0..13) that changed. If multiple bytes changed, one row per byte. |
| `min_value` | The minimum byte value observed across the sweep (hex). |
| `max_value` | The maximum byte value observed across the sweep (hex). |

The mapping document is ordered by inventory item number, with the
button grid ordered by physical position. Each entry is derived entirely
from the captured data; the inventory supplies only the name and type.

If an action was skipped or not reached, the corresponding mapping entry
says `no data captured` rather than omitting the input.

### 9.4 Constraints on the analysis script

- The script must not reference, compare to, or be aware of any prior
  claim about the *byte layout* — including, but not limited to, the
  byte-13 claim in `plan.md` §1, the `i >= 7` treatment in
  `RailDriverMenuItem.java`, and any inventory-item-to-physical-position
  mapping supplied outside the run directory.
- The script reads `docs/rpi-raildriver/control-inventory.md` to obtain
  input names and types so it can produce the mapping output (§9.3). It
  must not use the inventory to make assumptions about which bytes
  correspond to which inputs — that is determined solely from the captured
  data.
- The script must not generate prose conclusions or interpretive statements
  about what the data means beyond the structured mapping.
- The script must not read `plan.md` or any source file under `java/`.
- The script's only outputs are the per-run analysis files, mapping files,
  cross-run analysis files, and checksum files described above.

### 9.5 Command-line interface

- `rd-analyze.sh` — with no arguments: scan
  `docs/rpi-raildriver/captures/run-*/`, write each one's `analysis.md`,
  `analysis.csv`, `mapping.md`, and `mapping.csv`, and write the cross-run
  summary if more than one run exists.
- `rd-analyze.sh --run-dir <path>` — analyze only the named run directory;
  no cross-run output.
- `rd-analyze.sh --out <dir>` — write outputs under `<dir>` instead of next
  to the captures. Per-run output is written to
  `<dir>/<run-basename>/analysis.md`,
  `<dir>/<run-basename>/analysis.csv`,
  `<dir>/<run-basename>/mapping.md`, and
  `<dir>/<run-basename>/mapping.csv`; for example, analyzing
  `docs/rpi-raildriver/captures/run-001` writes
  `<dir>/run-001/analysis.{md,csv}` and `<dir>/run-001/mapping.{md,csv}`. Cross-run output is written to
  `<dir>/cross-run-analysis.md` and `<dir>/cross-run-analysis.csv`.
  Without `--out`, per-run output defaults to
  `<run-dir>/analysis.{md,csv}` and `<run-dir>/mapping.{md,csv}`, and
  cross-run output defaults to
  `docs/rpi-raildriver/captures/cross-run-analysis.{md,csv}`.

## 10. Deliverables

| Artifact | Notes |
|----------|-------|
| `docs/rpi-raildriver/capture-plan.md` | This document. |
| `docs/rpi-raildriver/rd-record.sh`    | Implements §3–§7. |
| `docs/rpi-raildriver/rd-analyze.sh`   | Implements §9. Reads `run-NNN/` directories produced by `rd-record.sh` and `control-inventory.md`; writes per-run `analysis.md` / `analysis.csv`, `mapping.md` / `mapping.csv`, and cross-run `cross-run-analysis.md` / `cross-run-analysis.csv`. Does not reference any prior claim about the byte layout. |
| `.gitignore` entry for `docs/rpi-raildriver/captures/` | Recommended default for exploratory runs; reference evidence sets can still be committed, attached to an issue/PR, or archived with `SHA256SUMS`. |

This table lists source artifacts only. The per-run and cross-run output
files (`analysis.md`, `analysis.csv`, `mapping.md`, `mapping.csv`,
`cross-run-analysis.md`,
`cross-run-analysis.csv`, `manifest.txt`, `README.md`, `SHA256SUMS` files,
plus the raw `.bin` and `.hex` captures) are produced by the scripts at
runtime and live under `docs/rpi-raildriver/captures/`.
