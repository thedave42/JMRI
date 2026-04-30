# RailDriver structured capture protocol

> **Status:** Plan / approved, script not yet implemented.
> **Companion docs:** `control-inventory.md` (the authoritative list of physical
> controls), `plan.md` (the umbrella RailDriver-on-Linux project plan).
> **Companion script (to be written):** `rd-record.sh` — see §10.

## 1. Problem statement

The existing captures under `docs/rpi-raildriver/test-data/` (five `xxd -c 14`
streams, 2,046 reports total) prove the *shape* of the device's HID input
report — 14 bytes per report, repeating, byte 13 constant `0x35` across every
report observed. They do **not** prove which byte (or which bit, for the
button bytes) corresponds to which physical control on the RailDriver Modern
Desktop, because no record was kept of which control was being touched at
which moment in the stream.

This document specifies a structured capture protocol whose explicit goal is
to produce the byte-by-byte and bit-by-bit mapping from each labeled control
in `control-inventory.md` to its position in the 14-byte input report.

## 2. Scope

### In scope

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

### Out of scope

- **Detent values.** The named positions on analog controls (Forward / Neutral
  / Reverse on the reverser; idle on the throttle; SUP / CS / EMG on the auto
  brake; bail-off on the independent brake; Off / Slow / Full on the wiper;
  Off / Dim / Full on the lights) are calibration data that the user must set
  in the final plugin. They are not needed to validate the report layout, and
  capturing them here would couple this protocol to one operator's hand
  position rather than the device's electrical behaviour.
- **Hat-switch diagonals.** The inventory states only one cardinal direction
  asserts at a time. Diagonals are not part of v1; they can be added later if
  needed.
- **Multi-control combinations** (e.g. holding two buttons at once). The
  per-control captures are sufficient to assign each control a unique
  byte/bit; combinations only matter if there are observed conflicts during
  analysis, in which case follow-up captures can be added.
- **Analysis or interpretation.** This protocol is the capture phase only.
  A separate `rd-analyze.sh` (future task; not in this plan) will diff
  each per-action capture against the pre-baseline and emit a summary
  table. The output of *this* protocol is the raw evidence; conclusions are
  drawn separately so the evidence can be re-analysed if the analysis logic
  ever changes.

## 3. Approach overview

A single bash script, `docs/rpi-raildriver/rd-record.sh`, walks the operator
through a **fixed, numbered list of 52 actions** in a stable order. For each
action the script:

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
│   └── README.md                 # auto-generated index: action #, slug,
│                                 # prompt text, file name, byte count, or
│                                 # SKIPPED flag
└── run-002/
    └── …
```

Run directories auto-increment (`run-001`, `run-002`, …). The flag
`--run-name foo` overrides to `run-foo` for named runs (e.g.
`run-postcalibration`, `run-second-operator`).

Captures are intended to be **gitignored by default**. They are large-ish,
operator-specific, and the protocol document plus a future analysis output
table is enough for the repo. Recommendation: add
`docs/rpi-raildriver/captures/` to `.gitignore` when the script lands.
Individual runs can still be committed manually if a reference set is ever
needed.

## 5. Action list (52 actions, fixed order)

### Phase 0 — pre-baseline (1 action)

| NN | slug             | prompt |
|----|------------------|--------|
| 00 | baseline-pre     | Do not touch the controller. Wait until the live counter shows ~300 reports captured (~3 s at 125 Hz), then press Enter. |

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
14 and which is item 41. To avoid building that ambiguity into the data, the
capture protocol uses **neutral physical-position slugs** rather than
inventory numbers. The mapping from physical position to inventory number is
established during the analysis phase by cross-referencing each capture's
asserted bit against the user's inventory numbering.

Convention used here:
- Two rows: `top` and `bot` (`top` is the row physically further from the
  operator; `bot` is the row closer to the operator's body).
- 14 columns per row, numbered `01` through `14` from left to right as the
  operator faces the controller.
- Slug format: `btn-<row>-<col>` (e.g. `btn-top-01`, `btn-bot-14`).

| NN  | slug            | prompt |
|-----|-----------------|--------|
| 17  | btn-top-01      | Press only the **top-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter. |
| 18  | btn-top-02      | Press only the **top-row, column 2** button (counting from the left). Hold for ~1 second, then release. Press Enter. |
| …   | …               | … (top row, columns 3 through 14, identical pattern) |
| 30  | btn-top-14      | Press only the **top-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter. |
| 31  | btn-bot-01      | Press only the **bottom-row, leftmost** button (column 1). Hold for ~1 second, then release. Press Enter. |
| …   | …               | … (bottom row, columns 2 through 13, identical pattern) |
| 44  | btn-bot-14      | Press only the **bottom-row, rightmost** button (column 14). Hold for ~1 second, then release. Press Enter. |

### Phase 3 — analog sweeps (6 actions)

| NN | slug              | prompt |
|----|-------------------|--------|
| 45 | reverser-sweep    | Move the **Reverser** slowly from full Forward to full Reverse, pausing briefly at each end, then return to Neutral. Press Enter. |
| 46 | throttle-sweep    | Move the **Throttle / Dynamic Brake** slowly from full Throttle to full Dynamic Brake (passing through center), pausing briefly at each end, then return to center. Press Enter. |
| 47 | auto-brake-sweep  | Move the **Auto Brake** slowly from fully RELEASED through SUP, CS, to EMG, pausing briefly at each end, then return to RELEASED. Press Enter. |
| 48 | indep-brake-sweep | Move the **Independent Brake** slowly through its full range including both bail-off positions, pausing briefly at each end, then return to release. Press Enter. |
| 49 | wiper-cycle       | Cycle the **Wiper** Off → Slow → Full → Slow → Off, pausing briefly at each position. Press Enter. |
| 50 | lights-cycle      | Cycle the **Lights** Off → Dim → Full → Dim → Off, pausing briefly at each position. Press Enter. |

### Phase 4 — post-baseline (1 action)

| NN | slug             | prompt |
|----|------------------|--------|
| 51 | baseline-post    | Do not touch the controller. Wait until the live counter shows ~300 reports captured, then press Enter. |

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
pointer to the udev / `plugdev` setup in `plan.md` §4.3).

### Capture command

For each action:

```sh
cat "$DEVICE" > "$run_dir/$NN-$slug.bin" &
cat_pid=$!
# … display live counter, wait for operator key …
kill -TERM "$cat_pid" 2>/dev/null
wait "$cat_pid" 2>/dev/null
```

`SIGTERM` (not `SIGKILL`) is used so `cat`'s stdio buffers flush cleanly. The
file descriptor closes when `cat` exits, which forces a final write of any
in-flight bytes. On a 14-byte-per-report device at ~125 Hz, the worst-case
data loss between SIGTERM and exit is one report.

### Live report counter

While capture is running, the script polls the size of the in-flight `.bin`
file every 250 ms and updates a single status line in place
(`printf "\r…"`):

```
[03 estop-up] capturing… 187 reports (2618 bytes)   [Enter=accept r=redo s=skip q=quit]
```

The counter helps the operator pace analog sweeps (a 5-second sweep should
show ~600+ reports before pressing Enter). It uses simple integer division
(`bytes / 14 = reports`); no parsing of the binary.

### Hex view generation

On accept: `xxd -c 14 "$run_dir/$NN-$slug.bin" > "$run_dir/$NN-$slug.hex"`.
This is synchronous and runs after `cat` has been reaped, so the `.bin` is
final.

### Pre-flight checks at script start

1. `[ -r "$DEVICE" ]` — fail with udev/plugdev guidance if not readable.
2. `command -v xxd >/dev/null` — fail clearly if `xxd` is absent.
3. The resolved device's `HID_ID` matches `0003:000005F3:000000D2` — fail
   loudly if the operator passed `--device` for a non-RailDriver hidraw node
   (prevents silently capturing the wrong device's bytes).

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
columns `#`, `slug`, `prompt`, `file`, `bytes`, `reports`, `status`. `status`
is one of `captured`, `skipped`, `not-reached` (for actions after a `q`
quit). Missing values appear as `—`.

## 8. Design decisions

The following decisions were resolved before authoring this document. They
are recorded so future maintainers can see why each choice was made.

| # | Question | Decision | Rationale |
|---|----------|----------|-----------|
| 1 | Should prompts use the inventory's item numbers (14..41) or neutral physical-position labels for the 28-button grid? | **Neutral position labels** (`btn-<row>-<col>`). | The inventory does not specify which corner is item 14 vs item 41, so building inventory numbers into the prompts would either invent a convention or confuse the operator. Neutral labels are unambiguous; the inventory-number mapping is an analysis-phase output. |
| 2 | Should hat-switch diagonals (UR / DR / DL / UL) be captured? | **No, deferred.** | The inventory says only one cardinal direction asserts at a time. v1 stays minimal; diagonals can be added as actions 13a–16a if any analysis surprise warrants it. |
| 3 | Should the script display a live report counter during capture? | **Yes.** | Analog sweeps need pacing; a per-action byte count after the fact is too late. Cheap to implement; no extra dependencies. |
| 4 | Should the script detect and recover from device-detach mid-capture? | **No, deferred.** | A yanked-cable mid-run is rare in practice. The operator can notice (counter stops advancing) and use `r` to redo. v1 does not need automated recovery; the existing `cat` exits cleanly on EOF and the `.bin` is preserved (just short). |

## 9. Why this protocol is sufficient

- **Stable indices and slugs** make `diff run-001/05-alert.hex
  run-002/05-alert.hex` a meaningful comparison and let operators re-run
  individual actions with consistent file names.
- **Pre- and post-baselines** bracket the run, so any device-state drift
  during the run is visible in raw form rather than silently averaged into
  the per-action data.
- **Single-control captures** mean a byte-diff between two adjacent reports
  inside one capture file isolates exactly the bits that respond to that one
  control — no noise from simultaneous inputs.
- **Hold-and-release windows** for buttons capture the sequence "rest /
  asserted / rest", confirming both *which* bits assert and *that* they
  release back to baseline.
- **Multiple runs** in separate `run-NNN` directories let us check that the
  byte/bit mapping is reproducible across runs and that the device has no
  run-to-run quirks.
- **Raw binary plus hex view** gives forensic accuracy (binary is the source
  of truth) plus human-readable review (hex). Later analysis tooling can
  re-render the binary in any other format without re-running the captures.

## 10. Deliverables

| Artifact | Status | Notes |
|----------|--------|-------|
| `docs/rpi-raildriver/capture-plan.md` | **This document.** | Committed alongside the script. |
| `docs/rpi-raildriver/rd-record.sh`    | To be written. | Implements §3–§7 above. Name matches the existing reference in `control-inventory.md` line 70. |
| `.gitignore` entry for `docs/rpi-raildriver/captures/` | To be written. | Recommended default; individual reference runs can still be committed manually. |
| `docs/rpi-raildriver/rd-analyze.sh`   | **Out of scope** for this plan. | A future analysis script that diffs each per-action capture against the pre-baseline and emits the byte-by-bit mapping table. Mentioned only so the file names above make sense in context. |

After this document and the script land, an explicit follow-up task is to
update `docs/rpi-raildriver/plan.md` §1 to soften the byte-13 claim once the
first run's data confirms (or refutes) the "constant 0x35" hypothesis under
this stricter protocol.
