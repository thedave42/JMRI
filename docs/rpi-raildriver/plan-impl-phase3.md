# RailDriver Phase 3 — Calibration framework for analog controls

> **Parent plan:** [`plan.md`](plan.md)
> **Predecessors:** [`plan-impl-phase1.md`](plan-impl-phase1.md), [`plan-impl-phase2.md`](plan-impl-phase2.md) (both merged)
> **Branch:** `rpi-raildriver`
> **Target platform:** Raspberry Pi 4, 64-bit Raspberry Pi OS / Debian 12+, JMRI 5.15.x.
> **Phase 3 goal:** let the user record the byte values their physical RailDriver emits at each detent / extreme / centre position of every analog control, persist that data per JMRI profile, and use it to drive the corresponding throttle behavior. Replaces the current hardcoded thresholds (0.45 / 0.55 for Reverser; 0.125 / 0.7 for Throttle; 0.6 for Lights) with values derived from the user's actual hardware.

## 1. Scope

### In scope

1. **Calibration data model** — one record per analog control, holding the byte value (and/or computed `value` after Java's transform) for each named position the user can reach physically.
2. **Per-profile persistence** so calibration survives JMRI restart and is per-profile (not global).
3. **A calibration UI** that lets the user pick a control, walk through its positions, capture each one, and save.
4. **Apply calibration to the three already-wired analog controls**: Reverser (Axis 0), Throttle (Axis 1, throttle side), and Lights rotary (Axis 6 → F0). Behavior under calibrated thresholds replaces the existing hardcoded ones.
5. **Allow capture for not-yet-wired controls** too — Auto Brake (Axis 2), Independent Brake (Axes 3 + 4), Wiper (Axis 5). The captured data is stored but does not yet affect behavior; this lets the user calibrate everything in one sitting and have the data ready when those axes are wired in phase 4+.
6. **Default calibration values** matching `control-inventory.md`, so the controller works out-of-the-box for users who don't run the calibration UI.

### Out of scope (deferred to phase 4+)

- **Functional wiring of Auto Brake, Independent Brake, Wiper.** Phase 3 stores calibration data for these but does not change their behavior (still log-only). Wiring them to throttle/loco actions is its own design task and stays in phase 4+.
- **Dynamic-brake half of the Throttle lever.** Still has the `//TODO: dynamic braking` comment; phase 3 calibrates the byte value at full Dyn Brake but does not wire the action.
- **Per-locomotive calibration.** Calibration is per-RailDriver-instance (the user's controller), applied to whatever loco is being driven. Locos differ in function maps, not in lever positions.
- **Button calibration.** Buttons are discrete bits; nothing to calibrate.
- **Multi-profile calibration management** (e.g. import / export, multiple named calibrations). One calibration per JMRI profile.
- **JUnit tests** for the calibration code (parent §4.5 still phase 4+).
- **Help / documentation updates** (parent §4.6 still phase 4+).
- **Cross-platform verification** (community testers).
- **All latent issues from parent §3 / §6.**

## 2. Architecture

### 2.1 Calibration data model

For each analog control, we record:

- The byte value at each named physical position the control supports.
- The `value` after Java's `(256 - vInt)/256` transform (and the bipolar flip for Axis 1) is computed at use time, not stored — storing the byte is canonical, the transformed value is derived.

| Control | Byte | Positions captured |
|---|---|---|
| #8 Reverser | 0 | Forward, Neutral, Reverse |
| #9 Throttle / Dyn Brake | 1 | Full Throttle, Idle, Full Dyn Brake |
| #10 Auto Brake | 2 | Released, SUP, CS, EMG |
| #11 Independent Brake (primary) | 3 | Full Release, Full Application |
| #11 Independent Brake (secondary / bail-off) | 4 | Rest band lower, Rest band upper, Full Bail-off |
| #12 Wiper | 5 | Off, Slow, Full |
| #13 Lights | 6 | Off, Dim, Full |

Defaults match `control-inventory.md`. Missing fields after a partial calibration fall back to defaults.

### 2.2 Storage

**Format:** JMRI-idiomatic XML, written and read with JDOM2 (already a JMRI dependency). The file lives in the active profile's preferences directory.

**Location:** `<jmri-profile>/profile/raildriver-calibration.xml`, where `<jmri-profile>` is the directory returned by `jmri.profile.ProfileManager.getDefault().getActiveProfile().getPath()` (e.g. `~/.jmri/My_JMRI_Railroad.jmri/profile/`).

**Schema sketch:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<raildriver-calibration version="1">
    <!-- Byte values are 0..255, decimal. Comments indicate the inventory default. -->
    <reverser>
        <forward>66</forward>          <!-- default 0x42 -->
        <neutral>128</neutral>         <!-- default ~0x80 (mid; not captured by inventory) -->
        <reverse>207</reverse>         <!-- default 0xcf -->
    </reverser>
    <throttle>
        <fullThrottle>221</fullThrottle>     <!-- default 0xdd -->
        <idle>126</idle>                      <!-- default ~0x7e (mid; not captured by inventory) -->
        <fullDynBrake>58</fullDynBrake>       <!-- default 0x3a -->
        <idleDeadband>0.05</idleDeadband>    <!-- user-configurable; see §2.4 -->
    </throttle>
    <autoBrake>
        <released>79</released>
        <sup/>                                <!-- empty = use default / not yet captured -->
        <cs/>
        <emg>183</emg>
    </autoBrake>
    <indepBrake>
        <fullRelease>192</fullRelease>
        <fullApplication>65</fullApplication>
        <bailoffRestLow>149</bailoffRestLow>
        <bailoffRestHigh>168</bailoffRestHigh>
        <bailoffFull>212</bailoffFull>
    </indepBrake>
    <wiper>
        <off>102</off>
        <slow/>
        <full>186</full>
    </wiper>
    <lights>
        <off>82</off>
        <dim/>
        <full>156</full>
    </lights>
</raildriver-calibration>
```

**Why JDOM2 with a self-managed file (rather than `ConfigureManager` registration):**
- Keeps the calibration self-contained: load on RailDriver-menu open, save on calibration-panel Save. No coupling to the user's main panel-save flow.
- JDOM2 is already a JMRI dependency; no new libraries.
- Per-profile path is the JMRI-idiomatic location.
- Empty-element semantics (`<sup/>`) cleanly represent "not yet captured" without nulls or sentinel values.
- Easier to re-load mid-session (after Save) than `ConfigureManager`-managed state.
- A future migration to `ConfigureManager` registration is possible without changing the on-disk file (the adapter would just register the same load/save methods).

**Versioning:** the root element carries `version="1"`. Future schema changes bump this; the loader migrates older versions or falls back to defaults.

**Defaults & partial calibration:** missing or empty elements fall back to the per-position default (which mirrors `control-inventory.md`). A brand-new profile with no calibration file produces phase-2 behavior unchanged.

### 2.3 Calibration UI

**Form factor:** a free-form non-modal `JmriJFrame` opened from a new `Debug → RailDriver Calibration…` menu entry (sibling of the existing `RailDriver Throttle (built in)` entry). Non-modal so the user can keep the throttle window visible and watch the loco respond as they save calibration changes.

**Layout sketch:**

```
┌─ RailDriver Calibration ─────────────────────────────────────────┐
│                                                                  │
│  Live byte readouts (move a control to watch it change):         │
│    Reverser   (byte 0):  0x80     Throttle (byte 1):  0x7e       │
│    Auto Brk   (byte 2):  0x4f     Indep Brk (byte 3): 0xc0       │
│    Bail-off   (byte 4):  0xa0     Wiper   (byte 5):   0x66       │
│    Lights     (byte 6):  0x52                                    │
│                                                                  │
│  ┌─ Reverser  (#8 / byte 0) ──────────────────────────┐          │
│  │  Forward     [  66  ] [Capture] [Reset]            │          │
│  │  Neutral     [ 128  ] [Capture] [Reset]            │          │
│  │  Reverse     [ 207  ] [Capture] [Reset]            │          │
│  └────────────────────────────────────────────────────┘          │
│  ┌─ Throttle / Dyn Brake  (#9 / byte 1) ──────────────┐          │
│  │  Full Throttle  [ 221 ] [Capture] [Reset]          │          │
│  │  Idle           [ 126 ] [Capture] [Reset]          │          │
│  │  Full Dyn Brake [  58 ] [Capture] [Reset]          │          │
│  │  Idle deadband  [ 0.05 ]   (0.0..0.5)              │          │
│  └────────────────────────────────────────────────────┘          │
│  ┌─ Auto Brake  (#10 / byte 2) ──────── ... etc.       │         │
│  ┌─ Independent Brake  (#11 / bytes 3 + 4) ── ... etc. │         │
│  ┌─ Wiper  (#12 / byte 5) ───────────── ... etc.       │         │
│  ┌─ Lights  (#13 / byte 6) ─────────── ... etc.        │         │
│                                                                  │
│  [ Save ]   [ Reset all to defaults ]   [ Cancel ]               │
└──────────────────────────────────────────────────────────────────┘
```

Notes:
- Every analog control gets a labelled section. Each captured position is one row with: position name, the currently-calibrated byte (editable text field, shown decimal), a Capture button, and a Reset button (sets that one position back to default).
- The byte fields accept hand-typed numeric input as a power-user override; Capture isn't the only path to a value.
- Capture grabs the current live byte for that axis at the moment the user clicks. The user is responsible for moving the lever to the right position first; no enforced ordering.
- The Reverser section's "Neutral" position is captured by holding the lever in the centre detent; same for Throttle's "Idle".
- The Throttle section has an extra **Idle deadband** numeric field (decimal, units of the post-transform `value`, default `0.05`, range `0.0..0.5` enforced). This addresses §6 / decision 8 — user-configurable rather than hardcoded.
- The Independent Brake section has rows for both byte 3 (full release / full application) and byte 4 (rest band lower / rest band upper / full bail-off). The two bytes are presented separately because they represent different physical aspects of the same lever (continuous vs. transient bail-off).
- "Save" writes the in-memory values to the profile XML and notifies `RailDriverMenuItem` to reload.
- "Reset all to defaults" sets every value back to the inventory default but does **not** save until "Save" is clicked.
- "Cancel" closes without saving.

**Pre-condition — the throttle menu must be open first.** The calibration frame relies on the polling thread for live byte readouts. If the user opens the calibration frame before clicking `Debug → RailDriver Throttle (built in)`, the frame shows a clear message ("Open the RailDriver throttle menu entry first to enable live readouts") and disables the Capture buttons until the device is open. This avoids two device-open paths and keeps error handling simple.

**Live data wiring:** the calibration frame adds itself as a `PropertyChangeListener` on the `RailDriverMenuItem`. Each `RawByte` event (see §2.5 / §3.3) updates the corresponding live-byte readout label.

### 2.4 Application of calibration to behavior

Calibration is read from disk at the time `RailDriverMenuItem.setupRailDriver()` runs (or when the calibration frame saves, whichever is later). The loaded `RailDriverCalibration` instance is kept in a field on `RailDriverMenuItem` and consulted every time `propertyChange` dispatches an analog axis.

Per-axis use:

- **Reverser (Axis 0):** instead of fixed thresholds 0.45 / 0.55, compute thresholds dynamically as the midpoints between calibrated Neutral and the two extremes:
  - `forwardThreshold = (reverserCal.forwardValue + reverserCal.neutralValue) / 2`
  - `reverseThreshold = (reverserCal.neutralValue + reverserCal.reverseValue) / 2`
  - Direction is set when the live value crosses these.
  - The default threshold values reproduce today's 0.45/0.55 (Forward = 0x42, Neutral = ~0x80, Reverse = 0xcf gives midpoints near 0.55 / 0.45). No surprise on existing setups.

- **Throttle (Axis 1):** instead of fixed `throttle_min = 0.125, throttle_max = 0.7`:
  - `idleValue` (calibrated) becomes the centre, computed via the bipolar transform from the calibrated Idle byte.
  - `throttle_min = idleValue + idleDeadband` — the deadband is the user-configurable value from the Throttle calibration section (default `0.05`).
  - `throttle_max = fullThrottleValue` (calibrated, after the bipolar transform).
  - The existing pin-and-fraction math stays unchanged; only the constants are replaced.

- **Lights (Axis 6 → F0):** instead of hardcoded threshold 0.6:
  - `threshold = (offValue + dimValue) / 2` if dim is calibrated, otherwise `(offValue + fullValue) / 2`.
  - Off byte yields the highest `value` after the `(256 - vInt)/256` transform, so `value >= threshold → F0 off; value < threshold → F0 on`. (Same direction as today; only the threshold magnitude changes.)

- **Auto Brake / Independent Brake / Wiper:** still log-only in phase 3. Calibration data is recorded (per decision 6) but not consumed by any throttle behavior.

### 2.5 Default behavior when no calibration exists

If `raildriver-calibration.xml` is absent or partial, the loader falls back to defaults that reproduce today's behavior exactly. Users who never open the calibration frame see no behavior change.

## 3. Steps

### 3.1 Calibration data class

New: `java/src/jmri/util/usb/RailDriverCalibration.java`.

- Per-control inner records (or POJO classes) for the seven calibration groups.
- Each calibration field stores a boxed `Integer` (byte value 0..255) or `null` ("not captured — use default"). Use of `null` matches the empty-element XML semantics from §2.2.
- `static final` defaults matching `control-inventory.md`.
- `load(File)` / `save(File)` using JDOM2 (already a JMRI dependency).
- `loadOrDefault(File)` returns a non-null instance even if the file is missing, malformed, or partial.
- Derived getters that propertyChange consumes: `getReverserForwardThreshold()`, `getThrottleMin()`, `getThrottleMax()`, `getLightsThreshold()`, etc. These apply the inventory's transform and return ready-to-compare `value`-space numbers, not raw bytes.
- `getThrottleIdleDeadband()` returns the user-configurable deadband (default `0.05`).

### 3.2 Calibration frame and action

New:
- `java/src/jmri/util/usb/RailDriverCalibrationAction.java` — a `JmriAbstractAction` that opens the calibration frame.
- `java/src/jmri/util/usb/RailDriverCalibrationFrame.java` — the free-form `JmriJFrame` from §2.3.

The frame:
- Subscribes to the `RailDriverMenuItem`'s `RawByte` events to drive live readouts.
- Owns an in-memory `RailDriverCalibration` working copy.
- Save → writes to the per-profile XML path; the open `RailDriverMenuItem` reloads the calibration so the new values take effect immediately.
- Disables Capture buttons (and shows a help message) when the RailDriver menu entry isn't open.

### 3.3 `RailDriverMenuItem` integration

Modify: `java/src/jmri/util/usb/RailDriverMenuItem.java`.

- Add a `RailDriverCalibration calibration` field, loaded in `setupRailDriver()` from the per-profile XML path.
- Replace hardcoded thresholds in `propertyChange` Axis 0 / Axis 1 / Axis 6 cases with `calibration.getX()` calls.
- Expose a public `reloadCalibration()` method the frame calls after Save.
- For bytes 0..6, in addition to firing `firePropertyChange("Value", "Axis N", value)`, also fire `firePropertyChange("RawByte", "Byte N", byteValue)` so the calibration frame can display the raw byte value without re-deriving it from the post-transform value (decision 5).

### 3.4 Menu wiring

Modify: `java/src/apps/jmrit/DebugMenu.java`.

Add a third entry in the same Debug-menu section as the existing two RailDriver entries:

```java
add(new jmri.util.usb.RailDriverMenuItem());
add(new jmri.util.usb.RailDriverCalibrationAction());  // <-- new
```

Bundle key for the label: `RdCalibrate` (or similar) added to `jmri/util/usb/Bundle.properties`. (English only in phase 3; localized files are phase 4+ doc work.)

### 3.5 Default-calibration source-of-truth alignment

The default values baked into `RailDriverCalibration` must match `control-inventory.md` for every control they cover. A short paragraph at the top of the calibration class file references the inventory so future edits stay synchronized.

### 3.6 Manual acceptance procedure

On the dev Pi, with the new build installed:

1. Launch JMRI with no calibration file present. Open a throttle on the user's loco. Open `Debug → RailDriver Throttle (built in)`. Sweep each lever — behavior should be **identical to phase 2** (defaults reproduce existing thresholds).
2. Open `Debug → RailDriver Calibration…`. Confirm the frame opens, all sections are visible, and live byte readouts respond to lever movement.
3. In the **Throttle / Dyn Brake** section: hold the lever at full throttle, click Capture next to "Full Throttle". Hold at idle, click Capture next to "Idle". Hold at full dyn brake, click Capture next to "Full Dyn Brake". Optionally edit the **Idle deadband** value. Click Save.
4. Sweep the throttle again. Verify:
   - Loco doesn't move when lever is at the user's *actual* idle position (even if that's slightly off-centre) — proving the calibrated idle is in effect.
   - Full throttle gives full speed.
   - The deadband behavior matches the configured value (e.g. if deadband is `0.10`, the loco starts moving slightly later as the lever leaves idle).
5. Repeat for **Reverser**: capture Forward / Neutral / Reverse. Verify direction switches happen at the calibrated detents.
6. Repeat for **Lights**: capture Off / Dim / Full. Verify F0 toggles at the right rotary position (Off → off; Dim or Full → on).
7. Capture all three positions for each of **Auto Brake**, **Independent Brake**, **Wiper** (calibration is stored even though those axes are still log-only).
8. Quit JMRI. Re-launch. Confirm `<profile>/profile/raildriver-calibration.xml` exists and the calibration is automatically loaded — no need to re-capture.
9. Open the calibration frame → click "Reset all to defaults" → Save. Confirm behavior reverts to phase-2 defaults and the XML file's per-position elements revert to default values (or are emptied).
10. Hand-edit `raildriver-calibration.xml` with a syntactically invalid value. Re-launch JMRI. Confirm `messages.log` shows a warning and behavior falls back to defaults (no crash).

## 4. Acceptance criteria

1. `Debug → RailDriver Calibration…` exists and opens a free-form panel listing every analog control.
2. With no calibration file present: Reverser, Throttle, and Lights behave exactly as in phase 2 (no behavior regression).
3. Live byte readouts in the calibration frame respond to lever movement (and are disabled when the RailDriver menu entry isn't open, with a clear message instead of stale data).
4. After capturing all three currently-wired controls and clicking Save:
   - `<profile>/profile/raildriver-calibration.xml` exists and contains the captured byte values in the schema from §2.2.
   - Subsequent control movements use the calibrated thresholds.
5. After JMRI restart, calibration is automatically loaded.
6. The calibration frame's "Reset all to defaults" + Save reverts on-disk and in-memory calibration to phase-2 behavior.
7. For Auto Brake, Indep Brake, Wiper: capture-and-save persists in the XML, but axis behavior is unchanged from phase 2 (still log-only).
8. Idle deadband is editable in the Throttle section and takes effect at the next saved calibration.
9. No `NullPointerException` on first launch with no calibration file. No exception when the file is malformed (logged warning + fallback to defaults).
10. Phase-2 acceptance criteria still hold (X11 quietness, polling-thread liveness, all phase-2 controls working).

## 5. Deliverables

1. `java/src/jmri/util/usb/RailDriverCalibration.java` (new)
2. `java/src/jmri/util/usb/RailDriverCalibrationAction.java` (new)
3. `java/src/jmri/util/usb/RailDriverCalibrationFrame.java` (or `…Dialog.java`) (new)
4. `java/src/jmri/util/usb/Bundle.properties` — add `RdCalibrate` key (modify)
5. `java/src/jmri/util/usb/RailDriverMenuItem.java` — load + apply calibration; expose reload (modify)
6. `java/src/apps/jmrit/DebugMenu.java` — wire the new menu entry (modify)
7. `docs/rpi-raildriver/plan-impl-phase3.md` — this document (already drafted)

No changes to `pom.xml`, `build.xml`, native libs, or udev rules.

## 6. Design decisions (locked-in from review)

These were the open questions in the prior draft; all eight are now fixed:

| # | Decision | Rationale |
|---|---|---|
| 1 | **Storage format: JMRI XML** (JDOM2, self-managed file at `<profile>/profile/raildriver-calibration.xml`) | Idiomatic JMRI. Empty-element semantics (`<sup/>`) cleanly model "not captured". JDOM2 already a dependency. |
| 2 | **UI shape: free-form panel** (one section per control, every position visible at once, capture in any order) | More flexible than a wizard; user can re-capture a single value without walking through everything. |
| 3 | **Menu placement: Debug menu** (next to existing `RailDriver Throttle (built in)`) | Same place as the throttle menu; users discover both together. |
| 4 | **Pre-condition: throttle menu must be open first** (no auto-open from the calibration frame) | One device-open path, simpler error handling. Frame disables Capture and shows a clear message until the menu entry is opened. |
| 5 | **Live-byte event: new `RawByte` PCS event** fired from the polling thread | Cleaner than reverse-engineering the byte from the existing `Value` event; doesn't couple the frame to the transform formula. |
| 6 | **Calibrate not-yet-wired axes: yes** (Auto Brake, Indep Brake, Wiper) | Lets the user capture everything in one sitting; data is ready when phase 4+ wires those axes. |
| 7 | **Capture full Dyn Brake on Throttle: yes** | Cheap during phase 3, future-proofs the dyn-brake wiring in phase 4+. |
| 8 | **Idle deadband: user-configurable** | Numeric field in the Throttle calibration section, default `0.05`, range `0.0..0.5`. Different operators may want different jitter tolerance. |

## 7. Known limitations accepted in phase 3

- **Auto Brake, Independent Brake, Wiper still don't drive any JMRI behavior.** Phase 3 is calibration-only for those.
- **Dynamic-brake half of throttle still unimplemented.** Phase 3 may capture its byte but does not wire it.
- **No calibration import / export.** The XML file is per-profile; users wanting to share calibration between profiles copy the file by hand.
- **No JUnit coverage.** Phase 4+.
- **No help-page documentation** explaining the calibration workflow. Phase 4+.
- **All latent issues from parent §3 / §6** still untouched.

## 8. What unlocks phase 4

Phase 4 picks up whatever is highest-priority once phase 3 lands. Likely candidates:

- **Wire Auto Brake (Axis 2) and Independent Brake (Axes 3 + 4) to JMRI behavior.** Design decisions: function-key dispatch vs. speed-clamp vs. extended throttle API. Calibration data from phase 3 supplies the threshold inputs.
- **Wire Wiper (Axis 5) to a function key** (typical mapping: a sound-effect F-key on rosters that have one).
- **Wire dynamic-brake half of Throttle.** Same options as Auto/Indep brake.
- **Tests** (parent §4.5).
- **Help page updates** (parent §4.6).
- **Latent-issue fixes** (parent §3 / §6).

## 9. Cross-references

- Parent: [`plan.md`](plan.md)
- Phase 1: [`plan-impl-phase1.md`](plan-impl-phase1.md) — connect & verify MVP.
- Phase 2: [`plan-impl-phase2.md`](plan-impl-phase2.md) — wire existing mappings + udev mitigation + post-test fixes (throttle sign flip, F0 un-drop, Lights→F0 + slot 0..27 → F1..F28 redesign).
- Canonical bit-for-bit map: [`control-inventory.md`](control-inventory.md). Phase 3 default calibration values must match this document.
