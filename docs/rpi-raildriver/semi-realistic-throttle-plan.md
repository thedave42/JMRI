# RailDriver Semi-Realistic Throttle Support

> **Research source:** [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md). All section references prefixed `[research §X]` resolve there.
> **Feature goal:** add EngineDriver-style semi-realistic throttle behaviour to the RailDriver path. Speed is no longer set directly from the throttle lever; instead the lever sets a *target* and a separate ramp scheduler walks the live decoder speed toward it on a brake-/scenario-aware schedule. Independent and Auto brakes shape the ramp's Δt; bail-off restores the air line; the dynamic-brake side of the throttle lever finally does something; a named-scenario picker stands in for EngineDriver's continuous load slider.

## 1. Scope

### In scope (split into stages 1–7, each independently shippable)

**Stage 1 — Ramp engine + bypass switch + unified Settings window.** New `SemiRealisticThrottleEngine` class that owns `targetSpeed` / `targetAcceleration` / a `ScheduledExecutorService`-driven ramp scheduler. When semi-realistic mode is OFF (default, until the user opts in), behaviour is identical to the existing RailDriver bring-up. When ON, the throttle lever (Axis 1 above Idle High) sets `targetSpeed`; the scheduler ticks toward it at the base acceleration / deceleration delay. No brakes shape the ramp yet — purely target-and-walk. Stage 1 also rolls up the existing standalone Calibration window and the new feature settings into a single tabbed `RailDriver Settings...` Debug-menu entry (see §2.4).

**Stage 2 — Scenario picker (load multiplier).** Adds the named-scenario enum and picker UI per [research §9.2]. Multiplies `targetAcceleration` so heavier scenarios visibly extend Δt. Persisted in the calibration XML under a new `<semiRealistic>` subtree (schema bumped to version `"2"`).

**Stage 3 — Independent brake (Axis 3) → mechanical brake clip + accel shaping.** Calibrated Indep-brake position becomes EngineDriver's `brakeSliderPosition`, quantised to a configurable number of steps. Folds into the existing `setTargetSpeed` brake regimes [research §3.5] — clipping the target and selecting between `effectiveBrake` and `maxBrakeUnderPower`-curve acceleration depending on whether the throttle is fighting the brake.

**Stage 4 — Auto brake (Axis 2) → air-line value + bail-off (byte 4) restore.** The Auto Brake lever directly drives `airLineValue` (no derived-from-mechanical model — see [research §10] item 2). Released → 100, EMG → 0, monotonic between. The bail-off switch (byte 4 transient) immediately restores `airLineValue` to 100 while held. Replaces EngineDriver's reservoir-and-line refill repeaters [research §4.2] with a simpler direct-from-lever mapping (the operator's hand on the lever is the prototype).

**Stage 5 — Dynamic brake side of throttle lever (Axis 1 below Idle Low).** Below the calibrated Idle Low, the throttle lever produces a negative `targetAcceleration` term, separate from the air-line and indep-brake terms. Distinct from the indep-brake because real dyn-brake doesn't use trainline air [research §10 item 1]. LED display shows `DBr` while in dyn-brake region.

**Stage 6 — Reverser interlock.** Direction-change-only-at-speed-0 interlock per [research §5]. E-Stop SPDT keeps its current `setSpeedSetting(-1)` behaviour. (EngineDriver's "soft stop button" mode is intentionally not adopted — the RailDriver's physical Independent Brake handle already gives the operator a more prototypical controlled-stop than a one-touch button would.)

**Stage 7 — ESU decoder-brake passthrough (optional, gated by user preference).** Per [research §4.3], computes brake-percent from the calibrated indep-brake position and forwards F4/F5/F6 dispatch when the user opts in. Defaults to OFF.

### Out of scope (deferred to future work)

- **Per-roster scenario default.** This feature ships with a session-level picker; reading `RosterEntry.getAttribute("raildriver.scenario")` to override the session default is deferred per [research §9.2.5].
- **Multi-throttle support.** EngineDriver runs up to 6 locos in parallel; we keep the existing single-throttle assumption from the RailDriver bring-up phases.
- **Configurable ramp parameters via UI.** This feature exposes `accelerationDelay` / `decelerationDelay` / `speedStep` / `brakeSteps` / `maxBrakePcnt` as fields on the Settings tab; advanced curves (e.g. user-configurable load multiplier table) stay hardcoded.
- **EngineDriver's `Stop` button and its four behaviour modes.** The Stop button is an Android-touch UX device — useful when your only inputs are screen taps. On a RailDriver console the operator already has E-Stop (hard) and the Independent Brake handle (controlled) within reach. None of EngineDriver's four stop modes (`THROTTLE_STOP`, `THROTTLE_STOP_BRAKE_FULL`, `SPEED_ZERO`, `SPEED_ZERO_BRAKE_ZERO`) is adopted; the existing E-Stop SPDT keeps its current behaviour.
- **Tests.** Parent §4.5. Deferred.
- **Help / documentation updates.** Parent §4.6. Deferred.
- **Cross-platform verification** — community testers, not in scope.
- **Latent issues from parent §3 / §6** other than the off-EDT mutation, which is fixed as part of stage 1.

## 2. Architecture

### 2.1 New class: `SemiRealisticThrottleEngine`

Single-throttle engine. Owned by `RailDriverMenuItem`. Lifecycle parallels the polling thread: created lazily in `attachThrottleWindow` after `activeThrottleFrame` is set; disposed in `propertyChange`'s `"ancestor"` case alongside the `throttleDispatcher` deregistration.

```java
public final class SemiRealisticThrottleEngine {
    // — Mode —
    private volatile boolean enabled;            // false => bypass (phase-3 direct setSpeedSetting path)

    // — Settings (loaded from calibration XML <semiRealistic> subtree) —
    private final SemiRealisticSettings settings;

    // — Target state —
    private volatile int    targetSpeed;          // 0..126
    private volatile double targetAcceleration;   // sign + magnitude (see research §2)

    // — Latest physical inputs (units after calibration application) —
    private volatile int    leverThrottleSpeed;       // 0..126 from Axis 1 above Idle High
    private volatile double leverDynBrakeFraction;    // 0.0..1.0 from Axis 1 below Idle Low (stage 5)
    private volatile int    indepBrakeStep;           // 0..brakeSteps from Axis 3 (stage 3)
    private volatile int    airLinePercent;           // 0..100 from Axis 2 (stage 4)
    private volatile boolean bailoffPressed;          // from byte 4 transient (stage 4)
    private volatile LoadScenario scenario;           // from picker (stage 2)
    private volatile int    direction;                // FORWARD / NEUTRAL / REVERSE from Axis 0

    // — Scheduler —
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RailDriver-SemiRealistic-Ramp");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> rampTask;

    // — Throttle proxy (replaces direct DccThrottle.setSpeedSetting calls) —
    private final ThrottleProxy proxy;
    ...
}
```

`ThrottleProxy` is a thin abstraction so the engine doesn't link directly to `DccThrottle` — easier to unit-test later, and easier to swap with a stub when the throttle is detached. Methods: `setSpeed(int 0..126)`, `setIsForward(boolean)`, `getCurrentSpeed()`, `getMaxFunctionCount()`, `setFunction(int n, boolean on)`. Backed by `activeThrottleFrame.getAddressPanel().getThrottle()`.

### 2.2 Threading model

Three threads are involved. The EDT-discipline boundary is strict: **no Swing-touching code runs off the EDT, anywhere.** The pre-existing off-EDT mutation in the RailDriver bring-up code (parent §3 / §6) is fixed as part of stage 1 — see §3.1 for the wrapping work.

| Thread | What it does | What it must NOT do |
|---|---|---|
| **Polling thread** (`RailDriver`, existing) | Reads HID reports, fires `RawByte` and `Value` PCS events. `dispatchValueEvent` runs here as a PCS listener. | Touch any Swing component or call any method that reaches Swing (`setSpeedSetting`, `setIsForward`, `setFunction`, `JMenuItem.setEnabled`, etc.). |
| **Engine worker** (`RailDriver-SemiRealistic-Ramp`, new) | Owned by the engine's single-thread `ScheduledExecutorService`. Runs `rampTask` on the configured Δt. **Computes the next speed step here** — the math (current ± step, clamps, target reached?) all happens on this thread. | Touch Swing. |
| **EDT** (Swing's own thread) | All Swing-touching work: `setSpeedSetting`, `setIsForward`, `setFunction`, LED updates that route through Swing components, status-label changes, etc. | Block on the worker or polling thread (no `invokeAndWait`; would deadlock). |

The contract:

- `dispatchValueEvent` (polling thread) updates the engine's input fields (`leverThrottleSpeed` etc.) and calls `engine.recompute()`. `recompute()` runs `setTargetSpeed`-equivalent logic, computes a fresh `(targetSpeed, targetAcceleration)`, cancels the in-flight `rampTask`, schedules a new one on the worker. **No Swing calls in the polling-thread path.**

- `rampTask`'s body runs on the engine worker. It computes the next step locally:
  ```java
  int next = current + signedStep;       // worker thread, no Swing
  if (overshoot) next = targetSpeed;
  current = next;
  ```
  Only after the math is final does it hand the result to the EDT:
  ```java
  SwingUtilities.invokeLater(() -> proxy.setSpeed(next));
  ```
  This keeps the *cadence* governed by the worker's `ScheduledExecutorService` (so timing is precise) and only the *application* of the value bounces through the EDT queue (so Swing stays consistent). If the EDT is busy, the lambda waits in the queue but the worker's next tick is unaffected — at worst the user sees one display-update of latency, never a missed scheduling slot.

- `dispatchValueEvent`'s phase-3 fall-back path (when semi-realistic mode is OFF) also wraps every Swing-touching call in `invokeLater` — see §3.1 deliverables. Same pattern: any `throttle.setX` / `addressPanel.setX` call from `dispatchValueEvent` becomes `SwingUtilities.invokeLater(() -> throttle.setX(...))`. The math/decision logic stays where it is.

- `targetSpeed` / `targetAcceleration` / `current` are accessed under a single `synchronized` block on the engine instance to keep the polling-thread `recompute` and the worker's `rampTask` coherent. Each access is a few field reads/writes; contention is negligible.

### 2.3 Bypass-mode wiring

When `enabled == false`, `engine.recompute()` is a no-op and `dispatchValueEvent` falls back to the phase-3 direct `setSpeedSetting` / `setIsForward` / `setFunction(0, ...)` path. **No behavioural change for users who don't opt in.** The mode is per-profile, persisted alongside the rest of the calibration; default OFF.

### 2.4 Unified Settings window

**Decision: the existing phase-3 calibration window and the new semi-realistic settings UI are merged into a single two-tab `RailDriverSettingsFrame`.** Going forward there is exactly one Debug-menu entry for RailDriver configuration, and one window the operator opens to adjust either set of values.

Menu entry: `Debug → RailDriver Settings...` (replaces the standalone `Debug → RailDriver Calibration...` entry that ships with the existing RailDriver bring-up).

Window layout (top-down):

```
┌─ RailDriver Settings ────────────────────────────────────┐
│ [ Settings | Calibration ]                               │ ← JTabbedPane
│ ┌──────────────────────────────────────────────────────┐ │
│ │ (active tab content)                                 │ │
│ │                                                      │ │
│ └──────────────────────────────────────────────────────┘ │
│                                                          │
│ status line: "Polling active." / save errors / etc.     │
│                                                          │
│              [Save]  [Apply]  [Cancel]                   │ ← bottom button bar
└──────────────────────────────────────────────────────────┘
```

- The **Settings tab** is the one selected when the window opens (`setSelectedIndex(0)` in the constructor).
- The **Calibration tab** holds the existing visual-bar UI verbatim — bars, capture buttons, per-section "Reset to defaults" buttons, "Reset all to defaults" button. None of that visual layout changes; it's just hosted inside a tab now instead of being the entire window.
- The Settings tab holds the controls listed below.
- The bottom button bar is shared across tabs — clicking Save or Apply persists everything from both tabs, regardless of which tab is currently visible.

#### Settings-tab controls

- **Enable semi-realistic mode** checkbox (the master switch).
- **Scenario:** dropdown — `Light engine` / `Switcher` / `Local freight` / `Through freight` / `Unit train` / `Custom` (with a numeric field exposed when `Custom` is selected).
- **Acceleration delay (ms):** numeric, default 300.
- **Deceleration delay (ms):** numeric, default 800.
- **Speed step:** numeric, default 2.
- **Brake steps:** numeric, default 7.
- **Maximum brake percent:** numeric, default 70.
- **Decoder-brake mode:** dropdown `None` / `ESU` (and ESU-only sub-fields when ESU is selected — F-numbers + thresholds).
- **Reset to defaults** button (settings-tab-scoped — restores only the semi-realistic fields to their EngineDriver defaults; does not touch calibration values).

#### Bottom button bar

- **Save** — validates both tabs; on success writes XML, calls `RailDriverMenuItem.reloadCalibration()` (so polling/engine pick up new values), clears dirty, **closes window**. On validation failure the offending tab is auto-selected, an error is shown in the status line, the window stays open, dirty stays set.
- **Apply** — exactly the same behaviour as Save except it leaves the window open after success. Initially disabled; becomes enabled when either tab reports `dirty`; greys back out the moment Save or Apply completes successfully (because both tabs reset their dirty flag in `resetToFile(...)`).
- **Cancel** — closes the window without writing. If `dirty` is true a confirmation prompt asks the operator whether to discard changes.

#### Dirty-tracking model

Each tab is implemented as a `JPanel` subclass that exposes:

```java
boolean isDirty();
void addDirtyChangeListener(Runnable listener);     // fires when isDirty() may have changed
boolean validateAndApplyTo(RailDriverCalibration target);  // false ⇒ failure (frame keeps window open)
void resetToFile(RailDriverCalibration freshFromDisk);     // reload from saved state, clears dirty
```

Internally each input control in a tab (`JTextField` document listener, `JCheckBox` action listener, `JComboBox` action listener, `JSpinner` change listener, capture-button presses on the calibration tab, per-section/per-tab Reset buttons) calls `markDirty()`. `markDirty()` flips a private `dirty` boolean if it wasn't already true and notifies the listeners.

The frame holds a single `boolean uiDirty = settingsTab.isDirty() || calibrationTab.isDirty()` and uses it to drive `applyButton.setEnabled(uiDirty)`. It registers itself as a dirty listener on both tabs at construction time. After a successful Save / Apply the frame calls `resetToFile(freshlyReloadedCalibration)` on both tabs, which clears their dirty flags and fires one final notification → Apply greys out.

**Capture buttons mark dirty.** A capture-button press writes the live byte into the working calibration's detent; that's a value change ⇒ Apply enables. The capture flow inherited from the existing calibration window is unchanged otherwise (live cursor, detent markers, etc.).

#### Save/Apply persistence flow

Both buttons run the same sequence:
1. Build a fresh `RailDriverCalibration` instance representing the on-disk schema.
2. Call `settingsTab.validateAndApplyTo(working)` — write the semi-realistic subtree.
3. Call `calibrationTab.validateAndApplyTo(working)` — write the per-axis detent values.
4. If either returned false, abort — auto-select that tab, show the validation message in the status line, leave window open, leave dirty set.
5. Persist `working` to XML.
6. Call `RailDriverMenuItem.reloadCalibration()` so the polling thread + (when stage 1 lands) the semi-realistic engine pick up the new values without restart.
7. Re-load `working` from disk (round-trip) and call `resetToFile(roundTripped)` on both tabs — guarantees the in-window state matches the file exactly, clears dirty.
8. Save closes the window via `dispose()`; Apply does not.

This consolidation rolls the existing standalone calibration UI together with this feature's settings UI into a single window. **The pre-existing `RailDriverCalibrationFrame` and `RailDriverCalibrationAction` are deleted as part of stage 1; the `RdCalibrate` Bundle key is replaced with `RdSettings`.** Operators who used `Debug → RailDriver Calibration...` will find the same calibration UI on the second tab of `Debug → RailDriver Settings...`.

### 2.5 Settings persistence

XML schema bumps to `version="2"`. The new `<semiRealistic>` subtree is added at the top level alongside existing controls:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<raildriver-calibration version="2">
    <reverser>...</reverser>
    <throttle>...</throttle>
    <autoBrake>...</autoBrake>
    <indepBrake>...</indepBrake>
    <wiper>...</wiper>
    <lights>...</lights>
    <semiRealistic>
        <enabled>true</enabled>
        <scenario>Through freight</scenario>
        <customScenarioMultiplier>3.5</customScenarioMultiplier>
        <accelerationDelayMs>300</accelerationDelayMs>
        <decelerationDelayMs>800</decelerationDelayMs>
        <speedStep>2</speedStep>
        <brakeSteps>7</brakeSteps>
        <maxBrakePercent>70</maxBrakePercent>
        <decoderBrakeMode>none</decoderBrakeMode>
        <esuLowFunction>4</esuLowFunction>
        <esuMidFunction>5</esuMidFunction>
        <esuHighFunction>6</esuHighFunction>
        <esuLowThreshold>30</esuLowThreshold>
        <esuMidThreshold>60</esuMidThreshold>
        <esuHighThreshold>98</esuHighThreshold>
    </semiRealistic>
</raildriver-calibration>
```

Schema migration: existing files (`version="1"`) load cleanly because the loader's existing tolerance for missing elements treats `<semiRealistic>` as absent ⇒ defaults (= disabled, which is the bring-up-era fallback ⇒ no behaviour change for legacy files). The `version` attribute exists so a later schema-3 change can branch cleanly.

## 3. Implementation stages

Each stage is independently buildable, installable, and testable on a real DCC loco. Acceptance criteria are listed for each.

### 3.1 Stage 1 — Ramp engine + bypass switch + unified Settings window

**Goal:** verify the ramp scheduler works end-to-end without any brake/load complexity. The throttle lever sets a target; the loco walks toward it at constant base delay. Also closes out the parent §3 / §6 off-EDT-mutation latent issue by routing every Swing-touching call (in both the new engine path AND the existing direct-dispatch path) through `SwingUtilities.invokeLater`. Replaces the standalone calibration window from the existing RailDriver bring-up with the unified two-tab Settings window described in §2.4.

**New / modified / deleted files:**

*New:*
- `java/src/jmri/util/usb/SemiRealisticThrottleEngine.java` — engine with the `recompute()` / scheduler / `ThrottleProxy`. In stage 1 only the throttle path is wired; brake/load fields are present but unused. Worker thread does the math; EDT does the `setSpeedSetting`.
- `java/src/jmri/util/usb/SemiRealisticSettings.java` — settings POJO with load + save methods, mirroring `RailDriverCalibration`'s structure.
- `java/src/jmri/util/usb/RailDriverSettingsFrame.java` — the `JmriJFrame` host described in §2.4: holds a `JTabbedPane` (Settings / Calibration), the bottom Save/Apply/Cancel button bar, status line, and the dirty-tracking glue. Listens for `"RawByte"` events and forwards them to the calibration tab so the live cursor still works while that tab is visible.
- `java/src/jmri/util/usb/RailDriverSettingsAction.java` — `AbstractAction` opening the unified frame. Calls `RailDriverMenuItem.ensureDeviceAndPolling()` before showing the window (same precondition the existing calibration action enforces today).
- `java/src/jmri/util/usb/SemiRealisticSettingsPanel.java` — the Settings tab. Implements the `isDirty / addDirtyChangeListener / validateAndApplyTo / resetToFile` contract from §2.4. Disables fields based on the `enabled` checkbox and the Decoder-brake mode dropdown.
- `java/src/jmri/util/usb/CalibrationTabPanel.java` — the Calibration tab. Created by extracting the entire visual-bar UI body from the existing `RailDriverCalibrationFrame` (everything in the current `buildHeader` / `buildSections` / per-axis `build*Section` / capture-row helpers) into a `JPanel` subclass, dropping the bottom Save / Reset-all / Cancel row (those move to the frame), and implementing the same `isDirty` contract. Capture-button and per-section "Reset to defaults" presses now flip dirty.

*Modified:*
- `java/src/jmri/util/usb/RailDriverCalibration.java` — bump schema to `"2"`, add `<semiRealistic>` subtree population/build, hold a `SemiRealisticSettings` field.
- `java/src/jmri/util/usb/RailDriverMenuItem.java`:
   1. Instantiate the engine in `attachThrottleWindow`; route `dispatchValueEvent` Axis 1 dispatch through the engine when `settings.enabled`.
   2. **Wrap every Swing-touching call in `dispatchValueEvent` in `SwingUtilities.invokeLater`** — this fixes the pre-existing off-EDT mutation per §2.2's threading contract. Affects: Axis 0 `throttle.setIsForward`; Axis 1 `throttle.setSpeedSetting` + `setLEDs` (when `setLEDs` reaches Swing — verify; if it only touches `HidDevice` it can stay on the worker); Axis 6 `throttle.setFunction`; the inner-switch's `addressPanel.selectRosterEntry` / `dispatchAddress` / `setRosterSelectedIndex` / `throttleWindow.nextThrottleFrame` / `previousThrottleFrame` / `throttle.setSpeedSetting` / `throttle.setFunction` / `throttle.getFunctionMomentary` / `throttle.getFunctions`. The decision logic (which case matched, what value to compute) stays on the polling thread; only the final mutator/getter call against a Swing-backed object goes through `invokeLater`.
   3. `reloadCalibration()` already covers the calibration reload; extend it to also notify the engine of new semi-realistic settings (or add a sibling `reloadSemiRealisticSettings()` if the engine needs distinct hooks — implementation detail).
- `java/src/apps/jmrit/DebugMenu.java` — replace the `new jmri.util.usb.RailDriverCalibrationAction()` line with `new jmri.util.usb.RailDriverSettingsAction()`.
- `java/src/jmri/util/usb/Bundle.properties` — replace `RdCalibrate = RailDriver Calibration...` with `RdSettings = RailDriver Settings...`. (The new action and frame title reference `RdSettings`.)

*Deleted:*
- `java/src/jmri/util/usb/RailDriverCalibrationFrame.java` — replaced by `RailDriverSettingsFrame` + `CalibrationTabPanel`.
- `java/src/jmri/util/usb/RailDriverCalibrationAction.java` — replaced by `RailDriverSettingsAction`.

`CalibrationBar.java` is unchanged — `CalibrationTabPanel` uses it exactly as the old frame did.

**Acceptance:**
1. `Debug → RailDriver Settings...` opens the unified window. The Settings tab is selected by default. The Calibration tab shows the same visual-bar UI as the existing standalone calibration window.
2. With either tab visible: editing any field, toggling any checkbox, capturing any byte, or pressing any per-section / settings-tab Reset button enables the Apply button. The Apply button greys back out as soon as Save or Apply completes successfully.
3. Save: persists everything from both tabs in one XML write, reloads, **closes window**.
4. Apply: same persistence behaviour, **leaves window open**, dirty greys out after a successful write.
5. Cancel with no pending changes closes the window. Cancel with pending changes prompts the operator; "Yes / discard" closes, "No / keep editing" leaves the window open with dirty intact.
6. Validation failure on either tab during Save or Apply: the offending tab is auto-selected, the status line shows the message, the window stays open, dirty stays set. (No pre-existing calibration field can fail validation today; the failure path is exercised by the new Settings-tab numeric inputs.)
7. Mode OFF: throttle behaves exactly as before (direct `setSpeedSetting`, but now wrapped in `invokeLater` — operator-perceptibly identical).
8. Mode ON: moving the throttle lever from idle to full speed produces a visible ramp on the loco — the loco's speed slider (in JMRI throttle window) walks up over ~19 s by default (63 steps × 300 ms, with default speed step = 2).
9. Mode ON: moving the lever back to idle produces a ~50 s ramp down (default 800 ms × 63 steps).
10. Reverser still works, just with the ramp engine in between.
11. The `activeThrottleFrame` NPE invariant from the existing RailDriver bring-up still holds.
12. **Code audit:** every method call in `RailDriverMenuItem.dispatchValueEvent` (and any helpers it calls) that mutates a Swing component, or calls a JMRI throttle/address-panel API that is documented as EDT-only, is wrapped in `SwingUtilities.invokeLater`. Verified by `grep` against the listed call sites and by a 5-minute live lever-sweep session in both modes producing no visible UI corruption.
13. Pre-existing XML files (schema `version="1"`) load cleanly into the new window — semi-realistic fields populate from defaults (disabled), calibration fields load as before; saving from the unified window produces a `version="2"` file.

### 3.2 Stage 2 — Scenario picker

**Goal:** the load multiplier visibly extends ramp Δt.

**Modified:** `SemiRealisticSettings.java` (add `scenario` + `customScenarioMultiplier`), `SemiRealisticSettingsPanel.java` (enable the Scenario row), `SemiRealisticThrottleEngine.java` (multiply `targetAcceleration` by `scenario.multiplier()` before scheduling).

**Acceptance:**
1. Picker shows all 6 scenarios; Custom shows a numeric field that's only honoured when Custom is selected.
2. Switching from `Light engine` to `Unit train` makes a 0 → full-speed ramp take ~10 × longer (≈ 3 minutes).
3. Switching mid-ramp picks up on the next `recompute()` (next lever movement or every brake update).

### 3.3 Stage 3 — Independent brake → mechanical brake

**Goal:** Indep-brake lever (Axis 3) shapes the target and Δt per [research §3.5].

**Modified:** `SemiRealisticThrottleEngine.java` (add the `effectiveBrake` chain — but with `airLinePercent == 100` constant for now, so only the mechanical side is in play), `dispatchValueEvent` Axis 3 case (update `engine.indepBrakeStep`).

Quantisation: `indepBrakeStep = round((calibratedFullRelease - byteValue) / (calibratedFullRelease - calibratedFullApplication) * brakeSteps)`. `brakeSteps` from settings (default 7).

**Acceptance:**
1. Indep brake at Full Release: throttle behaves as in stages 1-2 (ramp toward lever target).
2. Indep brake mid-travel while throttle is at full: loco drops to a partial speed (the EngineDriver "throttle defeated by brake" curve) and decelerates to it on the brake-shaped Δt.
3. Indep brake at Full Application + throttle at zero: loco stops on a fast deceleration (the regime-B curve, `Δt = base × −effectiveBrake` ≈ 90 ms with 70 % maxBrake).
4. Releasing the indep brake while at speed: loco resumes accelerating toward the lever's target on the normal curve.

### 3.4 Stage 4 — Auto brake → air line + bail-off restore

**Goal:** Auto Brake (Axis 2) directly drives `airLinePercent`; bail-off (byte 4) restores it to 100 transiently.

**Modified:** `dispatchValueEvent` Axis 2 case (compute `airLinePercent = round((byteValue - calibratedEmg) / (calibratedReleased - calibratedEmg) * 100)`; clamp to 0..100); button dispatch for the bail-off switch (set `engine.bailoffPressed = true/false` based on byte-4 threshold-crossing); `SemiRealisticThrottleEngine.recompute()` honours `bailoffPressed` by treating the air line as 100 % regardless of Axis 2 position while the switch is held.

This replaces EngineDriver's reservoir-and-line repeater simulation [research §4.2] with a direct mapping. It's both simpler in code and more prototypical (the operator's hand position *is* the air pressure on a real RailDriver). The reservoir-with-recharge state machine is **out of scope** unless the operator specifically wants to simulate "running out of air" — which they don't on a console with a real Auto Brake handle.

**Acceptance:**
1. Auto brake at Released: throttle ramps to lever target as in stage 3.
2. Auto brake at EMG: loco drops to zero on the air-line-as-brake curve, fast deceleration.
3. Auto brake mid-travel + indep brake at Full Release: the air line dominates because it bites harder (`min(airLineAsBrakePcnt, brakePcnt)` per [research §3.4]).
4. Auto brake at SUP/CS + bail-off pressed: loco accelerates again because air line is treated as 100 while bail-off is held — even though the lever is still applying.
5. Releasing bail-off restores brake immediately.

### 3.5 Stage 5 — Dynamic brake (lever UP)

**Goal:** the half of the throttle lever above center (toward DYN BRAKE label) finally does something.

**Modified:** `dispatchValueEvent` Axis 1 case to compute `leverDynBrakeFraction = (calibratedIdleLow - byteValue) / (calibratedIdleLow - calibratedFullDynBrake)` clamped to 0..1 when the byte is below Idle Low (= the dyn-brake side); `SemiRealisticThrottleEngine.recompute()` adds a separate `dynBrakeAcceleration` term that's strictly subtractive, distinct from `effectiveBrake`. The `DBr` LED, currently a TODO from the existing RailDriver bring-up, becomes the indication that the dyn-brake region is active.

Dyn brake doesn't use trainline air, so it stacks orthogonally with `airLinePercent` — both contribute deceleration. The combined effective `targetAcceleration` magnitude is the larger (i.e. shorter Δt) of the two terms when both are active.

**Acceptance:**
1. Lever at Idle Low or above: dyn brake is inactive; behaviour identical to stage 4.
2. Lever at full DYN BRAKE: loco decelerates to zero on a fast curve; LED shows `DBr`.
3. Lever just past Idle Low: loco decelerates slowly, LED still shows `DBr`.
4. Auto brake also applied while in dyn brake: deceleration is at least as fast as the most aggressive of the two — they don't cancel, they reinforce.

### 3.6 Stage 6 — Reverser interlock

**Goal:** [research §5] direction can only change at speed 0.

**Modified:** `dispatchValueEvent` Axis 0 case to suppress `setIsForward(...)` when `engine.currentSpeed > 0` (when in semi-realistic mode). E-Stop SPDT keeps `setSpeedSetting(-1)`. Reverser to NEUTRAL at any speed forces a coast-down per [research §3.3].

**Acceptance:**
1. Loco at speed > 0 + reverser moved to opposite direction: direction does NOT flip; an INFO log line records the suppression. Direction lever change with loco at speed 0 still works.
2. Reverser to NEUTRAL at any speed: loco coasts to a stop on the deceleration curve regardless of throttle/brake levers (per [research §3.3]).
3. E-Stop SPDT at any speed: loco hard-stops via `setSpeedSetting(-1)`. Same as the existing RailDriver bring-up behaviour.

### 3.7 Stage 7 — ESU decoder-brake passthrough (optional)

**Goal:** for users with ESU decoders, mirror brake percent to F4/F5/F6 [research §4.3]. Off by default.

**Modified:** `SemiRealisticSettings.java` (`decoderBrakeMode` enum; ESU function/threshold fields), `SemiRealisticSettingsPanel.java` (enable the Decoder-brake Mode dropdown + ESU-only sub-fields), `SemiRealisticThrottleEngine.recompute()` (after computing `effectiveBrake`, dispatch the function changes with the same three-pass logic from `setDecoderBrake` in [research §4.3]).

**Acceptance:**
1. Decoder-brake mode = None: no F4/F5/F6 dispatch from semi-realistic logic.
2. Decoder-brake mode = ESU: applying indep brake to ≥30 % toggles F4 ON; ≥60 % toggles F4 OFF + F5 ON; ≥98 % toggles F5 OFF + F6 ON. Backing off reverses the chain.
3. The threshold/function fields are user-configurable per loco family.

## 4. Acceptance criteria (overall)

1. With semi-realistic mode OFF, behaviour is identical to the existing RailDriver bring-up (no regression).
2. With mode ON, all per-stage acceptance criteria pass on a real DCC loco.
3. Calibration XML round-trips through Save / Load with the new schema; pre-existing files (version `"1"`) still load cleanly with semi-realistic defaults.
4. The `activeThrottleFrame == null` invariant from the existing RailDriver bring-up still holds — the engine acquires its `ThrottleProxy` from `activeThrottleFrame` at attach time and never holds the reference past the throttle's `"ancestor"` close.
5. The pre-existing noise hysteresis filter still applies — the engine never sees byte-level jitter as a "lever moved".
6. No new `messages.log` exceptions during a 30-minute ops session involving repeated brake / throttle work.

## 5. Deliverables

Per stage, listed in §3. Total across stages 1–7:

- 7 new Java files (`SemiRealisticThrottleEngine`, `SemiRealisticSettings`, `LoadScenario` enum, `RailDriverSettingsAction`, `RailDriverSettingsFrame`, `SemiRealisticSettingsPanel`, `CalibrationTabPanel`).
- 2 deleted Java files (`RailDriverCalibrationFrame`, `RailDriverCalibrationAction`) — replaced by the unified Settings frame.
- ~4 modified files (`RailDriverCalibration`, `RailDriverMenuItem`, `DebugMenu`, `Bundle.properties`).
- New per-profile XML subtree (schema bumped to version `"2"`).
- No changes to native libs, hid4java, udev rules, or build.xml / pom.xml.

## 6. Open design questions for review

These are not yet resolved; please decide before stage 1 starts.

1. **Mode toggle location.** Settings tab only (proposed), or also a quick toggle button on the JMRI throttle window?
2. **`maxBrakeUnderPower`** — derive as `maxBrake - 0.20` per EngineDriver (proposed), or expose as a separate user setting?
3. **Bail-off semantics.** The existing RailDriver bring-up doesn't dispatch byte-4 transitions to anything functional. Stage 4 makes byte 4 a binary "bail-off pressed" flag using the calibrated `bailoffThreshold()`. Is that the desired semantic (latched while the byte is above the threshold), or do we want a one-shot pulse on the rising edge?
4. **Defaults for the new settings.** Match EngineDriver's defaults exactly (proposed: 300 / 800 / 2 / 7 / 70 / `Light engine`), or pre-tune for the typical small-railroad operator (e.g. `Local freight` default scenario)?

### Resolved decisions

- **EDT discipline (decided 2026-05-02): Option B with worker-thread-math mitigation.** Every Swing-touching call from a non-EDT thread — both the new ramp dispatch AND the existing direct-dispatch path — is wrapped in `SwingUtilities.invokeLater`. The mitigation: the engine worker thread does all the speed-step math locally, then hands the final `int next` value to the EDT for application. This keeps ramp cadence governed by the `ScheduledExecutorService` (precise timing) and only the value-application bounces through the event queue (Swing consistency). Closes the parent §3 / §6 off-EDT latent issue. See §2.2 for the threading contract and §3.1 for the wrapping deliverables.
- **UI surface (decided 2026-05-02): one unified `RailDriver Settings...` window with two tabs (Settings + Calibration), plus a new Apply button alongside Save and Cancel.** Replaces the standalone `RailDriver Calibration...` entry that ships with the existing RailDriver bring-up. See §2.4 for layout, dirty-tracking model, and Save/Apply/Cancel behaviour. Implementation is part of stage 1 (§3.1).
- **Framing (decided 2026-05-02): this is a standalone feature, not a fourth phase of the RailDriver bring-up work.** Stages are numbered 1–7 within this document and don't extend the phase-1/2/3 numbering of the predecessor plans.

## 7. Known limitations accepted in this feature

- **Single-throttle only.** This feature doesn't introduce multi-loco support.
- **Per-roster scenario default deferred** (see [research §9.2.5]).
- **Reservoir-and-line refill model from EngineDriver §4.2 is replaced with the direct lever-driven model.** Operators who want "ran out of air, must release brake to recharge" gameplay will have to wait for a future feature that simulates a virtual reservoir behind the Auto Brake — this is out of scope here because the RailDriver's physical Auto Brake gives us the real signal.
- **No tests.** Parent §4.5 / deferred.
- **No help-page documentation.** Parent §4.6 / deferred.
- **All latent issues from parent §3 / §6 except the off-EDT mutation are still untouched.** The off-EDT issue is fixed in stage 1 (see §3.1, item 2 of `RailDriverMenuItem.java` modifications).

## 8. Future work

- Tests (parent §4.5) — byte-parser, settings persistence round-trip, ramp scheduler determinism with a stub `ThrottleProxy`.
- Per-roster scenario default via `RosterEntry.getAttribute("raildriver.scenario")`.
- Help / documentation updates (parent §4.6).
- Optional: virtual reservoir / line model for users who want EngineDriver-style "run out of air" behaviour layered on top of the physical Auto Brake handle.
- Remaining latent-issue fixes from parent §3 / §6.

## 9. Cross-references

- Research source: [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md).
- Parent: [`plan.md`](plan.md).
- RailDriver bring-up phase 1: [`plan-impl-phase1.md`](plan-impl-phase1.md) — connect & verify MVP.
- RailDriver bring-up phase 2: [`plan-impl-phase2.md`](plan-impl-phase2.md) — wire existing mappings, throttle-direction fix, F0/F28 redesign, slot 0..27 → F1..F28.
- RailDriver bring-up phase 3: [`plan-impl-phase3.md`](plan-impl-phase3.md) — calibration framework, visual bar UI, idle-range model, polling lifecycle decouple.
- Canonical bit-for-bit map: [`control-inventory.md`](control-inventory.md).
