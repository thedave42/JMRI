---
goal: Port EngineDriver semi-realistic throttle algorithm to JMRI RailDriver desktop integration
version: 1.2
date_created: 2026-05-04
last_updated: 2026-05-07
owner: thedave42
status: 'In progress'
tags: [feature, refactor, architecture]
---

# Introduction

![Status: In progress](https://img.shields.io/badge/status-In_progress-yellow)

Replace the in-progress velocity-based physics engine (`SemiRealisticThrottleEngine`) with an EngineDriver-aligned step-rate scheduler. The throttle lever sets a target decoder speed step, and the live speed walks toward that target one fixed-size step every Δt milliseconds, where Δt is scaled by brake position, air-line state, load scenario, and direction. This plan covers 10 features from the [EngineDriver-aligned epic](engine-driver-semi-realistic-throttle-epic.md): core engine rewrite, multi-source brake system (independent, Westinghouse air with real-time throttle-panel status display, dynamic, bail-off), ESU decoder brake passthrough, EngineDriver-aligned load slider, direction/E-Stop semantics, configurable ramp step size, connectivity indicator Jynstrument, package relocation, custom status slider UI component, and comprehensive testing/documentation. Settings UI remains the bespoke `RailDriverSettingsFrame`. Phases 1–9 are complete; remaining work covers the custom slider UI component (Phase 10) and final testing/documentation (Phase 11).

## 1. Requirements & Constraints

- **REQ-001**: All timed/scheduled events must use `ThreadingUtil.runOnLayoutDelayed` — no `ScheduledExecutorService`, `java.util.Timer`, `javax.swing.Timer`, `volatile`, or `synchronized` in the engine class.
- **REQ-002**: The step-rate algorithm must match EngineDriver's `throttle_semi_realistic.java` section-for-section where ported. Documented deviations: dynamic-brake low-speed taper (Feature 2c) and per-source brake load scaling (Feature 2e).
- **REQ-003**: The `getLoadPcnt` quadratic formula must be identical to EngineDriver: `((load² × (maxLoadPcnt − 100)) + 100) / 100`.
- **REQ-004**: Westinghouse air model must implement asymmetric apply (instant) / release (gradual, reservoir-gated) dynamics matching EngineDriver's line repeater.
- **REQ-005**: Bail-off must release **all** loco-side braking (independent, dynamic, AND locomotive's share of automatic brake). Only car-brake retardation remains.
- **REQ-007**: Settings and calibration changes must apply live to any attached engine via PCS events. Exception: the `enabled` flag (dispatch strategy) is fixed at bind time.
- **REQ-008**: Air line and air reservoir status must be observable in real time from a panel in the JMRI throttle window when semi-realistic mode is enabled.
- **REQ-009**: The `RailDriverSliderUI` component must be reusable across both the Air Status Panel (read-only indicator mode) and the Load Slider (interactive mode with snap-to-ticks), configurable at construction time via a builder pattern.
- **SEC-001**: No secrets or credentials in persisted XML fragments.
- **CON-001**: All RailDriver classes must reside in `jmri.jmrit.usb` (not `jmri.util.usb`) to satisfy JMRI cross-tree dependency rules enforced by `ArchitectureTest`.
- **GUD-001**: Prefer child elements over attributes for stored data (JMRI XML convention).
- **GUD-002**: Use `EnumIoNames` for enum-valued attributes with `ErrorHandler` routing for invalid values.
- **GUD-003**: Use `jmri.util.swing.JmriJOptionPane` instead of `javax.swing.JOptionPane`.
- **PAT-001**: Self-rescheduling callbacks with epoch-counter cancellation (same pattern as existing JMRI codebase).
- **PAT-002**: Settings captured as defensive copy at attach time; mid-session updates via `updateSettings()` on the layout thread.
- **PAT-003**: `@InvokeOnLayoutThread` annotation on all public engine methods.

## 2. Implementation Steps

### Phase 1 — Package Relocation

- GOAL-001: ✅ Move all RailDriver classes from `jmri.util.usb` to `jmri.jmrit.usb` (with `.swing` and `.configurexml` sub-packages) to satisfy JMRI cross-tree dependency rules. (Epic Feature 10)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-001 | Create target packages: `java/src/jmri/jmrit/usb/`, `java/src/jmri/jmrit/usb/swing/`, `java/src/jmri/jmrit/usb/configurexml/`. Create parallel test packages under `java/test/`. | | |
| TASK-002 | Move core classes to `jmri.jmrit.usb`: `RailDriverMenuItem.java`, `RailDriverCalibration.java`, `SemiRealisticThrottleEngine.java`, `SemiRealisticSettings.java`, `LoadScenario.java`, `Bundle.java` + all `Bundle*.properties` locale files. Update `package` declarations and imports. | | |
| TASK-003 | Move Swing classes to `jmri.jmrit.usb.swing`: `RailDriverSettingsFrame.java`, `RailDriverSettingsAction.java`, `SemiRealisticSettingsPanel.java`, `CalibrationTabPanel.java`, `CalibrationBar.java`. Update `package` declarations and imports. | | |
| TASK-004 | Add old→new mappings to `java/src/jmri/configurexml/ClassMigration.properties` for every relocated class (e.g. `jmri.util.usb.RailDriverMenuItem=jmri.jmrit.usb.RailDriverMenuItem`). | | |
| TASK-005 | Update `apps.jmrit.DebugMenu` import references from `jmri.util.usb` to `jmri.jmrit.usb`. | | |
| TASK-006 | Move all existing test classes to `java/test/jmri/jmrit/usb/`. Update imports and package declarations. | | |
| TASK-007 | Verify `ArchitectureTest` passes with no new violations. Verify all existing tests pass after relocation. | | |

### Phase 2 — Data Model & Core Step-Rate Engine

- GOAL-002: ✅ Rewrite `SemiRealisticSettings` to replace physics coefficients with EngineDriver-aligned step/delay/notch fields. Rewrite `SemiRealisticThrottleEngine` as a single-threaded step-rate scheduler on the JMRI layout thread. Retire `LoadScenario.java`. (Epic Features 1, partial 2/4 field definitions)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-008 | Rewrite `SemiRealisticSettings.java` — remove all physics fields (`maxAccelAtRestMs2`, `vCornerMps`, `driverPowerPercent`, `designTopSpeedMps`, `steam`, `resistStaticMs2`, `resistLinearPerSec`, `resistQuadPerMeter`, `brakeMaxDecelMs2`, `airBrakeMaxDecelMs2`, `dynBrakeMaxDecelMs2`, `dynBrakeMassFraction`, `dynBrakeVMinMph`). Add EngineDriver-aligned fields: `enabled` (boolean, default false), `baseAccelDelayMs` (int, default 300), `baseDecelDelayMs` (int, default 800), `minEmitIntervalMs` (int, default 50), `numberOfBrakeSteps` (int, default 7), `airLineRechargePcnt` (int, default 20), `airRefreshRateMs` (int, default 2000), `airReservoirReplenishPcnt` (int, default 5), `dynBrakeMinSpeedStep` (int, default 8), `numberOfLoadSteps` (int, default 5), `maxLoadPcnt` (int, default 1000), `loadSliderPosition` (int, default 0), `decoderBrakeMode` (enum NONE\|ESU, default NONE), `esuLowFunction` (int, default 4), `esuMidFunction` (int, default 5), `esuHighFunction` (int, default 6), `esuLowThreshold` (int, default 30), `esuMidThreshold` (int, default 60), `esuHighThreshold` (int, default 98). Provide `resetToDefaults()`, `copyFrom()`, defensive-copy constructor. | | |
| TASK-009 | Delete `LoadScenario.java` — physics-tuned scenario presets have no equivalent in the step-rate model. All settings are flat operator-configurable values. | | |
| TASK-010 | Rewrite `SemiRealisticThrottleEngine.java` as a step-rate scheduler. Remove `ScheduledExecutorService`, `volatile`, `synchronized`, `ResolvedPhysics`, and all Davis-equation integration. New architecture: single-threaded on layout thread via `ThreadingUtil.runOnLayoutDelayed`; epoch-counter cancellation for ramp, air, and deferred-emit pipelines; two-state lifecycle (DETACHED ↔ ATTACHED). Lever-input setters (`setThrottleFraction`, `setIndepBrakeFraction`, `setAirBrakeFraction`, `setDynBrakeFraction`, `setBailoffPressed`, `setDirection`) only store values and call `recomputeTarget()`. | | |
| TASK-011 | Implement `recomputeTarget()` in the engine: compute `targetSpeed` from throttle fraction × max speed steps, compute `targetAcceleration` (sign + magnitude: +1 accel, -1 to -4 brake), bump ramp epoch, post fresh ramp callback via `ThreadingUtil.runOnLayoutDelayed`. | | |
| TASK-012 | Implement the ramp scheduler callback: if `currentSpeedStep != targetSpeedStep`, increment/decrement by 1, compute Δt = `baseDelay × |targetAcceleration|` (accel uses `baseAccelDelayMs`, decel uses `baseDecelDelayMs`), call `throttle.setSpeedSetting(step / maxSteps)`, re-post self with `runOnLayoutDelayed(Δt)`. Guard with epoch check — stale epoch = no-op return. | | |
| TASK-013 | Implement deferred-emit throttling: track `lastEmitTimeMs`; if `now - lastEmitTimeMs < minEmitIntervalMs`, defer the `setSpeedSetting` call via `runOnLayoutDelayed` for the remaining interval. Separate epoch counter for deferred-emit pipeline. | | |
| TASK-014 | Implement `attachThrottle(DccThrottle t)`: seed `currentSpeedStep` from `t.getSpeedSetting() × maxSteps` (round to nearest integer). Capture settings as defensive copy. Set lifecycle to ATTACHED. Implement `detachThrottle()`: bump all epochs, set lifecycle to DETACHED. | | |
| TASK-015 | Implement `updateSettings(SemiRealisticSettings s)`: replace internal settings copy, call `recomputeTarget()` so new values take effect on next ramp tick. Must be called on layout thread (no lock needed). | | |
| TASK-016 | Annotate all public methods with `@InvokeOnLayoutThread`. Add `ThreadingUtil.requireLayoutThread(log)` assertions at entry points. | | |
| TASK-017 | Write unit tests for the ramp scheduler: pure-throttle ramp idle→full at defaults verifies ~19 seconds; coast full→idle at defaults verifies ~50 seconds; seed-from-throttle on attach; epoch cancellation prevents stale callbacks. Use mock `DccThrottle` and `JUnitUtil.waitFor()` for time-dependent assertions. | | |

### Phase 3 — Direction & E-Stop Semantics

- GOAL-003: ✅ Implement EngineDriver's direction lever interlock and E-Stop handling. (Epic Feature 5)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-018 | Implement direction interlock in engine: NEUTRAL always allowed (forces `targetSpeed = 0, targetAcceleration = -1`). Forward↔Reverse only allowed when `currentSpeedStep == 0`; otherwise store the desired direction for application when the loco reaches zero. | | |
| TASK-019 | Implement `emergencyHalt()` in engine: bump all three pipeline epochs (ramp, air, deferred-emit), reset `currentSpeedStep = 0`, `targetSpeedStep = 0`, then directly call `throttle.setSpeedSetting(-1f)` for DCC E-Stop. | | |
| TASK-020 | Wire E-Stop to RailDriver SPDT #2 (byte 4 bit mapping) in `RailDriverMenuItem`. On E-Stop assertion, call `engine.emergencyHalt()`. Recovery is automatic — next lever-change event calls `recomputeTarget()`. | | |
| TASK-021 | Wire reverser lever (lever #8) in `RailDriverMenuItem` to call `engine.setDirection()` with FORWARD/NEUTRAL/REVERSE based on calibrated thresholds. | | |
| TASK-022 | Write unit tests: reverser flip at non-zero speed is ignored; reverser flip at zero speed takes effect; E-Stop cancels all three epochs; after E-Stop, next lever-change resumes normal ramp. | | |

### Phase 4 — Brake System: Independent, Dynamic, Bail-Off & Combination

- GOAL-004: ✅ Implement independent brake quantisation, dynamic brake with low-speed taper, bail-off, and the effective brake combination formula. (Epic Features 2a, 2c, 2d, 2e)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-023 | Implement independent brake quantisation in engine: lever #11 (byte 3) calibrated range → `numberOfBrakeSteps` notches. Position 0 = released. Each step change calls `recomputeTarget()`. Compute `rawIndepPcnt` from notch position (0.0 = released, 1.0 = full application as a percentage; but in EngineDriver convention, 1.0 = no braking, approaching 0.0 = full braking). | | |
| TASK-024 | Implement dynamic brake in engine: below-idle throttle travel (lever #9, below Idle threshold) produces `dynBrakeStep` in `0..numberOfBrakeSteps`. Compute `rawDynPcnt`. Low-speed taper: below `dynBrakeMinSpeedStep` (default 8), dyn-brake effect fades linearly to zero at speed 0. Implement `effectiveDynBrakeStep(currentSpeed, dynBrakeStep, dynBrakeMinSpeedStep)` as a static testable method. | | |
| TASK-025 | Implement bail-off in engine: `setBailoffPressed(boolean)`. While asserted, all loco-side braking is released — `effectiveIndepPcnt = 1.0`, `effectiveDynPcnt = 1.0`, and the locomotive's share of the auto brake is released. Only car-brake retardation continues. At light engine (loadMultiplier = 1.0, no cars), bail-off releases all braking entirely. | | |
| TASK-026 | Implement effective brake combination formula in `recomputeTarget()` or a dedicated `computeEffectiveBrake()` method. Normal: `effectiveIndepPcnt = 1.0 − ((1.0 − rawIndepPcnt) / loadMultiplier)`, `effectiveDynPcnt = 1.0 − ((1.0 − rawDynPcnt) / loadMultiplier)`, `effectiveAirPcnt = rawAirPcnt` (load-invariant). `effectiveBrake = min(effectiveIndepPcnt, effectiveAirPcnt, effectiveDynPcnt)`. Bail-off variant per epic §2e. | | |
| TASK-027 | Wire `effectiveBrake` into the ramp scheduler's `targetAcceleration` computation: `effectiveBrake` modifies the decel multiplier that scales Δt, matching EngineDriver's `setTargetSpeed` brake integration. | | |
| TASK-028 | Write unit tests: independent brake quantisation at each notch; dynamic brake low-speed taper fading to zero; bail-off releases all loco-side braking; bail-off at light engine = no braking; effective brake combination at light engine (1.0×) matches EngineDriver single-source `min()`; at full load (10×), indep brake retardation is 1/10th of unloaded; at full load, auto brake retardation is unchanged. Table-driven tests for `effectiveDynBrakeStep`. | | |

### Phase 5 — Brake System: Air / Westinghouse Model

- GOAL-005: ✅ Implement the simplified Westinghouse automatic air brake with asymmetric apply/release, reservoir-gated line recharge, lap behaviour, and emergency recovery. (Epic Feature 2b)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-029 | Add air system state variables to the engine: `airLineValue` (int 0..100, default 100), `airReservoirPct` (int 0..100, default 100), `demandedLineValue` (int, derived from Auto Brake lever position). Add a separate epoch counter for the air repeater pipeline. | | |
| TASK-030 | Implement brake application (instant drop): when `demandedLineValue < airLineValue`, set `airLineValue = demandedLineValue` immediately. Compute `rawAirPcnt` from `airLineValue` (100 = no braking, 0 = full braking). Call `recomputeTarget()`. | | |
| TASK-031 | Implement line repeater (gradual release): when `demandedLineValue > airLineValue`, start a self-rescheduling callback via `ThreadingUtil.runOnLayoutDelayed(airRefreshRateMs)`. Each tick: add `airLineRechargePcnt` (default 20) to `airLineValue`, capped at `demandedLineValue`, drawing the same amount from `airReservoirPct`. If reservoir too low, recharge only what's available. If reservoir empty, line cannot recharge. Stop when `airLineValue >= demandedLineValue`. Each tick calls `recomputeTarget()`. Guard with air epoch check. | | |
| TASK-032 | Implement reservoir repeater (background, always running while attached): self-rescheduling via `runOnLayoutDelayed(airRefreshRateMs)`. Each tick adds `airReservoirReplenishPcnt` (default 5) to `airReservoirPct`, capped at 100. Shares `airRefreshRateMs` timing. Separate from line repeater — runs unconditionally when engine is attached and `airRefreshRateMs > 0`. | | |
| TASK-033 | Implement emergency application: lever at EMG → `demandedLineValue = 0` → `airLineValue` drops to 0 immediately. Full brake force. Recovery requires lever to Released and waiting for line repeater to recharge — slow process since reservoir was also depleted. | | |
| TASK-034 | Implement flat-mapping mode: when `airRefreshRateMs == 0`, bypass both repeaters. Auto Brake lever directly sets `airLineValue` with no recharge dynamics. | | |
| TASK-035 | Wire Auto Brake lever #10 (byte 2) in `RailDriverMenuItem`: convert calibrated byte range to `demandedLineValue` (0..100). Map EMG/CS/SUP/REL positions from calibration thresholds. Call `engine.setAirBrakeDemand(demandedLineValue)`. | | |
| TASK-036 | Write unit tests: air application is instant (line drops immediately); air release is gradual (line walks up at configurable rate); reservoir gates release (depleted reservoir prevents recharge); full release from 0% at defaults takes ~10 seconds (5 ticks × 2s); lap behaviour (intermediate lever position holds line steady); emergency recovery takes longer than normal service release; reservoir refills at +5% per tick; `airRefreshRateMs = 0` disables dynamics. | | |
| TASK-074 | Expose `airLineValue` and `airReservoirPct` as observable properties via `PropertyChangeSupport` on the engine. Fire `"airLineValue"` PropertyChange events on instant-drop application, each line repeater tick, and flat-mapping direct-set. Fire `"airReservoirPct"` PropertyChange events on each reservoir repeater tick and on emergency depletion. Events carry `int` old/new values (0..100). Listeners subscribe via `engine.addPropertyChangeListener()`. Events fire on the layout thread (the engine's native thread); UI consumers must marshal to the GUI thread. | | |

### Phase 6 — Load Slider

- GOAL-006: ✅ Port EngineDriver's load slider with quadratic `getLoadPcnt` formula and per-source brake load scaling. (Epic Feature 4)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-037 | Implement `getLoadPcnt(int step, int numberOfLoadSteps, int maxLoadPcnt)` as a `static` method on the engine (or a utility class). Formula: `((load² × (maxLoadPcnt − 100)) + 100) / 100` where `load = step / numberOfLoadSteps`. Guard: when `step == 0`, return 1.0 (skip formula entirely, matching EngineDriver's `if (loadSliderPosition > 0)` guard). | | |
| TASK-038 | Wire load multiplier into `recomputeTarget()` / `setTargetSpeed()`: `targetAcceleration *= loadMultiplier`. Higher load = longer inter-step delay = slower acceleration. This is the same single wiring point EngineDriver uses. | | |
| TASK-039 | Wire per-source brake load scaling (already implemented in Phase 4 TASK-026 formula): verify independent and dynamic brake effectiveness is reduced by load, auto brake is load-invariant. Add `prevLoadStep` change detection — only restart ramp if load actually changed. | | |
| TASK-040 | Write table-driven unit tests for `getLoadPcnt`: at defaults (5 steps, maxLoadPcnt=1000), step 0→1.0×, step 1→1.36×, step 2→2.44×, step 3→4.24×, step 4→6.76×, step 5→10.0×. Non-default `maxLoadPcnt` values (500→5×, 200→2×). Guard condition at step 0. | | |

### Phase 7 — ESU Decoder Brake Passthrough

- GOAL-007: ✅ Port EngineDriver's `setDecoderBrake` function passthrough for ESU decoders. (Epic Feature 3)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-041 | Implement decoder brake passthrough in engine: when `decoderBrakeMode == ESU`, independent brake percent crossing each threshold (ascending: `esuLowThreshold`, `esuMidThreshold`, `esuHighThreshold`) toggles the configured function number (`esuLowFunction`, `esuMidFunction`, `esuHighFunction`) on the attached `DccThrottle`. Track previous function states to avoid redundant calls. | | |
| TASK-042 | Implement validation: thresholds must be ascending (`low < mid < high`), validated on save and on settings load. Function numbers must be non-negative. | | |
| TASK-043 | When `decoderBrakeMode == NONE` (default), short-circuit the entire passthrough — no function calls regardless of brake position. | | |
| TASK-044 | Write unit tests: ESU mode toggles functions at correct thresholds; NONE mode makes no function calls; invalid threshold ordering rejected on validation; function state tracking prevents redundant calls. | | |

### Phase 8 — Connectivity Indicator (Jynstrument)

- GOAL-008: ✅ Create a passive RailDriver-USB connectivity indicator on the throttle window toolbar. (Epic Feature 9)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-061 | Create `jmri.jmrit.usb.swing.RailDriverConnectivityIndicator` extending `Jynstrument`. Two visual states: active icon (connected) and greyed icon (disconnected). Listens to `RailDriverMenuItem.isRailDriverConnected()` via PCS. | ✅ | 2026-05-07 |
| TASK-062 | Implement click handlers: left-click opens the RailDriver Settings window via `RailDriverSettingsAction`. Right-click shows popup menu with "Settings..." item doing the same. Both click paths work in both visual states (no `setEnabled(false)` on the indicator). | ✅ | 2026-05-07 |
| TASK-063 | Implement `quit()`: deregister PCS listener from `RailDriverMenuItem` to prevent listener leak. | ✅ | 2026-05-07 |
| TASK-064 | Wire auto-installation: `RailDriverMenuItem.attachThrottleWindow()` calls indicator installation idempotently on the throttle toolbar. | ✅ | 2026-05-07 |
| TASK-065 | Create indicator icon assets (active + greyed SVG/PNG) in `resources/icons/throttles/` or appropriate JMRI icon directory. | ✅ | 2026-05-07 |

### Phase 9 — Air Status Throttle Panel

- GOAL-009: ✅ Create a real-time air status and load display panel embedded in the JMRI throttle window showing current brake pipe (air line) pressure, main reservoir level for the Westinghouse brake system, and user-adjustable load. Visible only when semi-realistic mode is enabled. (Epic Feature 2 user story: view air line and reservoir status)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-075 | Create `jmri.jmrit.usb.swing.RailDriverAirStatusPanel` extending `JPanel`. Display `airLineValue` (brake pipe pressure, 0–100) and `airReservoirPct` (main reservoir, 0–100) as labeled `JProgressBar` gauges with numeric readouts. Panel layout must be compact enough to fit in a throttle window without dominating the view. Subscribe to `"airLineValue"` and `"airReservoirPct"` PropertyChange events from the engine. Marshal updates to the GUI thread via `ThreadingUtil.runOnGUIEventually`. | ✅ | 2026-05-07 |
| TASK-076 | Wire panel installation into `RailDriverMenuItem.attachThrottleWindow()`: when semi-realistic mode is enabled and engine is attached, instantiate `RailDriverAirStatusPanel`, subscribe it to the engine's PropertyChange events, and add it to the throttle window panel area. Remove and dispose the panel on engine detach or throttle window close. Installation must be idempotent. | ✅ | 2026-05-07 |
| TASK-077 | Implement visibility gating: panel is only installed when the `Enable semi-realistic mode` checkbox is checked (semi-realistic mode enabled). When semi-realistic mode is disabled, do not install the panel. Since toggling the semi-realistic mode setting requires the user to reload the throttle, the panel is included or removed on the next throttle reload — no live add/remove transition is needed. | ✅ | 2026-05-07 |
| TASK-078 | Implement `dispose()` on `RailDriverAirStatusPanel`: deregister all PropertyChange listeners from the engine to prevent listener leaks. Called from `RailDriverMenuItem` on throttle window close or engine detach. | ✅ | 2026-05-07 |
| TASK-079 | Write unit tests for `RailDriverAirStatusPanel`: verify panel gauge values update on PropertyChange events; verify load slider tick count matches `numberOfLoadSteps` and tick labels show correct quadratic load percentages from Phase 6 formula; verify `dispose()` deregisters listeners (no listener leak); verify panel is not installed when semi-realistic mode is disabled; verify panel is installed when semi-realistic mode is enabled. | ✅ | 2026-05-07 |
| TASK-080 | Add a load slider to `RailDriverAirStatusPanel` whose tick count equals `numberOfLoadSteps` from the engine settings. Label each tick with the load percentage computed by the Phase 6 quadratic formula `getLoadPcnt(step, numberOfLoadSteps, maxLoadPcnt)` — i.e. `((load² × (maxLoadPcnt − 100)) + 100) / 100` where `load = step / numberOfLoadSteps`. The first tick (step 0) is labeled 100% (loco weight only); subsequent ticks show the quadratic curve values (e.g. at defaults of 5 steps / maxLoadPcnt=1000: 100%, 136%, 244%, 424%, 676%, 1000%). | ✅ | 2026-05-07 |

### Phase 10 — RailDriver Status Slider UI Component

- GOAL-010: Create a reusable custom `JSlider` UI component (`RailDriverSliderUI`) with configurable track colours, thumb colour gradient, tick marks with optional labels, snap-to-ticks, read-only mode, and sizing options. Retrofit the Air Status Panel and Load Slider to use it. (Epic Feature 9)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-081 | Create `jmri.jmrit.usb.swing.RailDriverSliderUI` extending `javax.swing.plaf.basic.BasicSliderUI`. Include a `Builder` inner class that accepts a `JSlider` and exposes fluent setters: `trackBackground(Color)`, `trackFill(Color)`, `thumbColorBottom(Color)`, `thumbColorMiddle(Color)`, `thumbColorTop(Color)`, `thumbColorDisabled(Color)`, `ticks(int[])`, `tickLabels(String[])`, `snapToTicks(boolean)`, `readOnly(boolean)`, `preferredSize(Dimension)`, `fillParent(boolean)`. Only the `JSlider` is required; all others have sensible defaults (Tango palette colours matching `ControlPanelCustomSliderUI`). | | |
| TASK-082 | Implement `paintTrack(Graphics g)`: fill the full track rect with `trackBackground`, then overpaint from the zero point to the thumb centre with `trackFill`. Both colours support alpha transparency. Support horizontal and vertical orientations. Draw tick lines at each caller-specified position in the tick colour (derived from `trackBackground` at full opacity). When tick labels are supplied, paint them adjacent to their tick marks in the slider's current font. | | |
| TASK-083 | Implement `paintThumb(Graphics g)`: draw a rectangular thumb with dark contour stroke. Fill colour is linearly interpolated between `thumbColorBottom` → `thumbColorMiddle` in the lower half and `thumbColorMiddle` → `thumbColorTop` in the upper half, blending all four RGBA channels based on current slider value. When disabled, use `thumbColorDisabled`. | | |
| TASK-084 | Implement snap-to-ticks mode: when `snapToTicks` is true in the builder, configure the `JSlider` via `setSnapToTicks(true)` and set tick spacing to match the provided tick positions. When false, the slider moves continuously through its full range. | | |
| TASK-085 | Implement read-only (indicator) mode: when `readOnly` is true, install a no-op `MouseListener`, `MouseMotionListener`, `MouseWheelListener`, and `KeyListener` that consume all input events. `JSlider.setValue()` continues to work programmatically. Thumb does not highlight on hover. | | |
| TASK-086 | Implement sizing options: when `preferredSize(Dimension)` is set, override `getPreferredSize()` to return that dimension. When `fillParent(true)` is set, register a `ComponentListener` on the parent container that updates the slider's preferred size to the parent's available space on resize. `preferredSize` and `fillParent` are mutually exclusive; setting one clears the other. When neither is set, use `BasicSliderUI`'s default preferred-size calculation. | | |
| TASK-087 | Retrofit `RailDriverAirStatusPanel` (Phase 9) to use `RailDriverSliderUI` for air line and reservoir gauges: replace `JProgressBar` widgets with `JSlider` instances using `RailDriverSliderUI` in read-only mode. Air line: green fill, red-at-0 → yellow-at-50 → green-at-100 thumb gradient. Reservoir: same gradient. Both vertical, `fillParent(true)`. | | |
| TASK-088 | Retrofit load slider on `RailDriverAirStatusPanel` to use `RailDriverSliderUI`: horizontal slider with `snapToTicks(true)`, tick positions at each load step (0..`numberOfLoadSteps`), tick labels showing quadratic multiplier values from `getLoadPcnt`. Orange fill. Green-at-0 → yellow-at-mid → red-at-max thumb gradient. | | |
| TASK-089 | Write unit tests for `RailDriverSliderUI`: thumb colour interpolation at min/mid/max/quarter values; read-only mode blocks mouse/keyboard events while `setValue()` works; snap-to-ticks rounds to nearest tick; tick and label painting with mock `Graphics2D`; builder defaults produce valid UI; horizontal and vertical orientations; `preferredSize` and `fillParent` mutually exclusive; `fillParent` updates on parent resize. | | |

### Phase 11 — Testing & Documentation

- GOAL-011: Comprehensive test suite with EngineDriver-verified expected values and user-facing documentation. (Epic Feature 10)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-066 | Write/verify pure-math unit tests: `getBrakeDecimalPcnt` at each notch, `getLoadPcnt` table-driven (including non-default `maxLoadPcnt`), `effectiveDynBrakeStep` with taper. All expected values verified against EngineDriver source. | | |
| TASK-067 | Write/verify engine integration tests with mock `DccThrottle`: pure-throttle ramp (idle→full, full→idle), brake clip (brake applied during ramp), brake to zero (full brake to stop), air depletion (reservoir empty prevents release), bail-off (loco free-rolls while cars braked), direction interlock (reject reverser flip at speed), load multiplier scaling (verify acceleration stretch). | | |
| TASK-069 | Verify `warnOnce`/`infoOnce` test paths follow JMRI JUnit reset guidance (`Log4JFixture.setUp()`/`tearDown()` reset one-shot state). | | |
| TASK-070 | Create help page `help/en/html/tools/usb/RailDriverSemiRealistic.shtml` — operator-facing documentation covering the semi-realistic throttle mode, brake system (independent, air/Westinghouse, dynamic, bail-off), load slider, ESU decoder brake, and direction/E-Stop semantics. Include the key multiplier table from the epic. | | |
| TASK-071 | Create help page `help/en/html/tools/usb/RailDriverConnectionIndicator.shtml` — operator-facing documentation for the toolbar connectivity indicator. | | |
| TASK-072 | Update existing help page `help/en/html/tools/usb/RailDriverSettings.shtml` — document the bespoke settings frame tabs (Settings + Calibration). | | |
| TASK-073 | Add Javadoc on every new public or protected API across all new/changed classes. Ensure `ant javadoc` produces no new warnings for the `jmri.jmrit.usb` package. | | |

## 3. Alternatives

- **ALT-001**: Keep the existing physics-based engine and add EngineDriver mode as a second option. Rejected: maintaining two divergent engines doubles testing and configuration surface without clear user benefit. The EngineDriver-aligned model is simpler, well-tested on Android, and provides a consistent cross-platform experience.
- **ALT-002**: Use `ScheduledExecutorService` for the ramp scheduler (matching the current implementation). Rejected: violates JMRI threading conventions (REQ-001). All timed events must use `ThreadingUtil.runOnLayoutDelayed` to remain on the layout thread.
- **ALT-003**: Migrate settings UI to standard JMRI Preferences window via `PreferencesPanel` SPI. Rejected: the bespoke `RailDriverSettingsFrame` provides a simpler, self-contained UI that is directly accessible from the Debug menu and Jynstrument click handlers without navigating the multi-level JMRI Preferences tree. The bespoke frame also supports integrated Save/Apply/Cancel with cross-tab validation that the Preferences framework's per-panel save model cannot express.
- **ALT-005**: Model prototype-accurate physics (mass, force, Davis equation). Rejected: this is an explicit non-goal of the epic. The decoder and JMRI roster speed profile remain the sole authority for actual model-train velocity.
- **ALT-006**: Implement air brake as a simple percentage mapping (no Westinghouse dynamics). Rejected: the Westinghouse model is the core differentiator for realistic train-handling feel. EngineDriver implements it; we port it faithfully and extend it for the continuous Auto Brake lever.
- **ALT-007**: Duplicate `ControlPanelCustomSliderUI` painting code for each RailDriver slider use case (air gauges, load slider). Rejected: a single configurable `RailDriverSliderUI` component with a builder pattern avoids code duplication, ensures visual consistency, and is testable in isolation.

## 4. Dependencies

- **DEP-001**: `jmri.util.ThreadingUtil` — `runOnLayoutDelayed()`, `runOnLayout()`, `requireLayoutThread()`. Already available in JMRI core.
- **DEP-006**: `jmri.configurexml.AbstractXmlAdapter.EnumIoNames` — enum serialisation helper. Already available in JMRI core.
- **DEP-007**: `hid4java` — USB HID library for RailDriver device access. Already in JMRI's `lib/` directory.
- **DEP-008**: EngineDriver reference source: [`throttle_semi_realistic.java`](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java) at SHA `5e722d38`. Used for algorithm verification, not runtime dependency.
- **DEP-009**: `javax.swing.plaf.basic.BasicSliderUI` — base class for `RailDriverSliderUI`. Standard JDK Swing component.

## 5. Files

### New files

- **FILE-001**: `java/src/jmri/jmrit/usb/SemiRealisticThrottleEngine.java` — step-rate scheduler engine (rewritten)
- **FILE-002**: `java/src/jmri/jmrit/usb/SemiRealisticSettings.java` — EngineDriver-aligned settings POJO (rewritten)
- **FILE-006**: `java/src/jmri/jmrit/usb/swing/RailDriverConnectivityIndicator.java` — Jynstrument toolbar indicator
- **FILE-009**: `help/en/html/tools/usb/RailDriverSemiRealistic.shtml` — new help page
- **FILE-010**: `help/en/html/tools/usb/RailDriverConnectionIndicator.shtml` — new help page
- **FILE-031**: `java/src/jmri/jmrit/usb/swing/RailDriverAirStatusPanel.java` — real-time air status display panel for the throttle window
- **FILE-033**: `java/src/jmri/jmrit/usb/swing/RailDriverSliderUI.java` — reusable custom slider UI component with builder pattern

### Relocated files (jmri.util.usb → jmri.jmrit.usb)

- **FILE-011**: `RailDriverMenuItem.java` — main lifecycle / polling / dispatch
- **FILE-012**: `RailDriverCalibration.java` — calibration POJO
- **FILE-013**: `Bundle.java` + `Bundle.properties` + 5 locale variants
- **FILE-014**: `swing/CalibrationTabPanel.java` — visual-bar calibration UI
- **FILE-015**: `swing/CalibrationBar.java` — single-axis calibration bar widget
- **FILE-016**: `swing/RailDriverSettingsFrame.java` — bespoke settings window (Settings + Calibration tabs)
- **FILE-017**: `swing/RailDriverSettingsAction.java` — Debug menu action to open settings
- **FILE-018**: `swing/SemiRealisticSettingsPanel.java` — settings tab content

### Retired files

- **FILE-019**: `jmri.util.usb.LoadScenario` — physics presets retired; replaced by flat configurable fields

### Modified files

- **FILE-020**: `java/src/jmri/configurexml/ClassMigration.properties` — add old→new package mappings
- **FILE-021**: `java/src/apps/jmrit/DebugMenu.java` — update imports from `jmri.util.usb` to `jmri.jmrit.usb`
- **FILE-022**: `help/en/html/tools/usb/RailDriverSettings.shtml` — update for bespoke settings frame

### Test files (new)

- **FILE-023**: `java/test/jmri/jmrit/usb/SemiRealisticThrottleEngineTest.java`
- **FILE-024**: `java/test/jmri/jmrit/usb/SemiRealisticSettingsTest.java`
- **FILE-032**: `java/test/jmri/jmrit/usb/swing/RailDriverAirStatusPanelTest.java` — air status panel unit tests
- **FILE-034**: `java/test/jmri/jmrit/usb/swing/RailDriverSliderUITest.java` — slider UI unit tests

## 6. Testing

- **TEST-001**: Pure-math unit tests for `getBrakeDecimalPcnt` — table-driven at each notch with EngineDriver-verified expected values.
- **TEST-002**: Pure-math unit tests for `getLoadPcnt` — table-driven at each default slider step (0→1.0×, 1→1.36×, 2→2.44×, 3→4.24×, 4→6.76×, 5→10.0×) and at non-default `maxLoadPcnt` values (500, 200).
- **TEST-003**: Pure-math unit tests for `effectiveDynBrakeStep` — taper fading to zero below threshold.
- **TEST-004**: Engine integration tests with mock `DccThrottle` — pure-throttle ramp (idle→full ~19s, full→idle ~50s at defaults), brake clip, brake to zero, direction interlock, E-Stop epoch cancellation.
- **TEST-005**: Air system integration tests — instant application, gradual release, reservoir depletion blocks release, lap behaviour, emergency recovery timing, flat-mapping mode (`airRefreshRateMs = 0`).
- **TEST-006**: Bail-off integration tests — all loco-side braking released, car-brake-only retardation at load, light engine bail-off = no braking.
- **TEST-007**: Load multiplier integration tests — acceleration stretch at each load step, per-source brake scaling (indep reduced, auto invariant), `prevLoadStep` change detection.
- **TEST-008**: ESU decoder brake tests — function toggle at thresholds, NONE mode inert, ascending threshold validation.
- **TEST-012**: `warnOnce`/`infoOnce` paths follow JMRI JUnit reset guidance.
- **TEST-013**: `ArchitectureTest` — no new violations from package relocation.
- **TEST-014**: Air status panel integration tests — PropertyChange-driven gauge updates reflect engine air state, load slider tick count and labels match quadratic formula, `dispose()` deregisters listeners (no leak), panel not installed when semi-realistic mode disabled, panel installed when semi-realistic mode enabled, panel included/removed on next throttle reload after toggling semi-realistic mode.
- **TEST-015**: `RailDriverSliderUI` unit tests — thumb colour interpolation at min/mid/max/quarter values, read-only mode blocks mouse/keyboard events while `setValue()` works, snap-to-ticks rounds to nearest tick, tick and label painting with mock `Graphics2D`, builder defaults produce valid UI with Tango palette, horizontal and vertical orientation support, `preferredSize` and `fillParent` mutual exclusion, `fillParent` responds to parent resize.

## 7. Risks & Assumptions

- **RISK-001**: EngineDriver algorithm drift — the reference source may change between plan creation and implementation completion. Mitigation: pin to SHA `5e722d38` for the `throttle_semi_realistic.java` reference. Document any upstream changes encountered.
- **RISK-002**: `ThreadingUtil.runOnLayoutDelayed` timing precision — ramp timing tests depend on millisecond-level scheduling accuracy. On loaded CI machines, timer callbacks may fire late. Mitigation: use tolerance ranges (e.g. ±20%) in time-dependent assertions; prefer step-count verification over wall-clock duration where possible.
- **RISK-003**: Air system complexity — the Westinghouse model with three interacting state variables (line, reservoir, demand) and two self-rescheduling repeaters is the most complex feature. Mitigation: extensive unit tests; separate the air state machine into testable pure functions fed by the repeater callbacks.
- **RISK-004**: Package relocation in Phase 1 may surface hidden dependencies from other parts of the codebase beyond `DebugMenu`. Mitigation: `ClassMigration.properties` handles XML-persisted references; grep for all `jmri.util.usb` import statements across the tree before committing.
- **RISK-006**: Throttle window panel integration — the JMRI throttle window layout is complex and may not have an obvious insertion point for the air status panel. The panel must be compact, non-intrusive, and compatible with all throttle window configurations (single, multiple, tabbed). Mitigation: use a small `JPanel` with gauges that fits naturally alongside existing throttle controls; test with multiple throttle window configurations.
- **RISK-007**: Slider UI look-and-feel consistency — `RailDriverSliderUI` extends `BasicSliderUI`, which may render differently under non-default Swing look-and-feel themes. Mitigation: the custom `paintTrack` / `paintThumb` overrides bypass the L&F's default painting, so visual consistency depends only on `Graphics2D` rendering, not the active theme. Test under Metal (default) and system L&F.
- **ASSUMPTION-001**: The JMRI layout thread is the correct thread for all engine operations. The epic and JMRI conventions confirm this, but no existing RailDriver code currently uses this pattern (the current engine uses a `ScheduledExecutorService` worker thread).
- **ASSUMPTION-003**: The bespoke `RailDriverSettingsFrame` with its `DirtyTrackingTab` interface provides sufficient UI for all settings and calibration needs. No JMRI Preferences window integration is required.
- **ASSUMPTION-004**: The RailDriver HID polling thread will continue to fire `PropertyChange` events to `RailDriverMenuItem`, which then dispatches to the engine on the layout thread. The polling-thread → layout-thread handoff already exists conceptually but must be verified/updated during engine rewrite.

## 8. Related Specifications / Further Reading

- [EngineDriver-Aligned Semi-Realistic Throttle Epic](engine-driver-semi-realistic-throttle-epic.md) — the source epic for this plan
- [EngineDriver Algorithm Research](semi-realistic-throttle-info.md) — line-by-line reference of the EngineDriver source
- [RailDriver Control Inventory](control-inventory.md) — physical control / HID byte mapping
- [EngineDriver Source (SHA 5e722d38)](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java) — upstream reference implementation
- [JMRI Threading Conventions](https://www.jmri.org/help/en/html/doc/Technical/Threads.shtml) — ThreadingUtil, runOnLayoutDelayed
- [JMRI Use of Swing](https://www.jmri.org/help/en/html/doc/Technical/Swing.shtml) — JmriPanel, BasicSliderUI conventions
