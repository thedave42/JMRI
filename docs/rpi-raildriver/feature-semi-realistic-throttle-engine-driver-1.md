---
goal: Port EngineDriver semi-realistic throttle algorithm to JMRI RailDriver desktop integration
version: 1.1
date_created: 2026-05-04
last_updated: 2026-05-05
owner: thedave42
status: 'Planned'
tags: [feature, refactor, architecture, migration]
---

# Introduction

![Status: Planned](https://img.shields.io/badge/status-Planned-blue)

Replace the in-progress velocity-based physics engine (`SemiRealisticThrottleEngine`) with an EngineDriver-aligned step-rate scheduler. The throttle lever sets a target decoder speed step, and the live speed walks toward that target one fixed-size step every Δt milliseconds, where Δt is scaled by brake position, air-line state, load scenario, and direction. This plan covers all 11 features from the [EngineDriver-aligned epic](engine-driver-semi-realistic-throttle-epic.md): core engine rewrite, multi-source brake system (independent, Westinghouse air with real-time throttle-panel status display, dynamic, bail-off), ESU decoder brake passthrough, EngineDriver-aligned load slider, direction/E-Stop semantics, JMRI Preferences integration, profile-aware persistence via `AuxiliaryConfiguration`, legacy file migration, connectivity indicator Jynstrument, package relocation, and comprehensive testing/documentation.

## 1. Requirements & Constraints

- **REQ-001**: All timed/scheduled events must use `ThreadingUtil.runOnLayoutDelayed` — no `ScheduledExecutorService`, `java.util.Timer`, `javax.swing.Timer`, `volatile`, or `synchronized` in the engine class.
- **REQ-002**: The step-rate algorithm must match EngineDriver's `throttle_semi_realistic.java` section-for-section where ported. Documented deviations: dynamic-brake low-speed taper (Feature 2c) and per-source brake load scaling (Feature 2e).
- **REQ-003**: The `getLoadPcnt` quadratic formula must be identical to EngineDriver: `((load² × (maxLoadPcnt − 100)) + 100) / 100`.
- **REQ-004**: Westinghouse air model must implement asymmetric apply (instant) / release (gradual, reservoir-gated) dynamics matching EngineDriver's line repeater.
- **REQ-005**: Bail-off must release **all** loco-side braking (independent, dynamic, AND locomotive's share of automatic brake). Only car-brake retardation remains.
- **REQ-006**: Legacy calibration files (`raildriver-calibration.xml` v1/v2) must migrate without data loss for detents. v2 `<semiRealistic>` subtree is discarded with a warn-level ErrorHandler report.
- **REQ-007**: Settings and calibration changes must apply live to any attached engine via PCS events. Exception: the `enabled` flag (dispatch strategy) is fixed at bind time.
- **SEC-001**: No secrets or credentials in persisted XML fragments.
- **CON-001**: All RailDriver classes must reside in `jmri.jmrit.usb` (not `jmri.util.usb`) to satisfy JMRI cross-tree dependency rules enforced by `ArchitectureTest`.
- **CON-002**: Settings UI must use standard JMRI Preferences framework (`jmri.swing.PreferencesPanel` SPI). No custom window frame.
- **CON-003**: Persistence must use `AuxiliaryConfiguration` fragments — no freestanding XML files.
- **CON-004**: XML schemas must follow the JMRI Venetian Blinds pattern and reside in `xml/schema/raildriver/`.
- **CON-005**: Backward compatibility: newer JMRI must load older profile data without error. Unknown namespace versions treated as missing; defaults applied.
- **GUD-001**: Prefer child elements over attributes for stored data (JMRI XML convention).
- **GUD-002**: Use `EnumIoNames` for enum-valued attributes with `ErrorHandler` routing for invalid values.
- **GUD-003**: Use `jmri.util.swing.JmriJOptionPane` instead of `javax.swing.JOptionPane`.
- **PAT-001**: Self-rescheduling callbacks with epoch-counter cancellation (same pattern as existing JMRI codebase).
- **PAT-002**: Settings captured as defensive copy at attach time; mid-session updates via `updateSettings()` on the layout thread.
- **PAT-003**: `@InvokeOnLayoutThread` annotation on all public engine methods.
- **REQ-008**: Air line and air reservoir status must be observable in real time from a panel in the JMRI throttle window when Westinghouse dynamics are active (`airRefreshRateMs > 0`).

## 2. Implementation Steps

### Phase 1 — Package Relocation

- GOAL-001: Move all RailDriver classes from `jmri.util.usb` to `jmri.jmrit.usb` (with `.swing` and `.configurexml` sub-packages) to satisfy JMRI cross-tree dependency rules. (Epic Feature 10)

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

- GOAL-002: Rewrite `SemiRealisticSettings` to replace physics coefficients with EngineDriver-aligned step/delay/notch fields. Rewrite `SemiRealisticThrottleEngine` as a single-threaded step-rate scheduler on the JMRI layout thread. Retire `LoadScenario.java`. (Epic Features 1, partial 2/4 field definitions)

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

- GOAL-003: Implement EngineDriver's direction lever interlock and E-Stop handling. (Epic Feature 5)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-018 | Implement direction interlock in engine: NEUTRAL always allowed (forces `targetSpeed = 0, targetAcceleration = -1`). Forward↔Reverse only allowed when `currentSpeedStep == 0`; otherwise store the desired direction for application when the loco reaches zero. | | |
| TASK-019 | Implement `emergencyHalt()` in engine: bump all three pipeline epochs (ramp, air, deferred-emit), reset `currentSpeedStep = 0`, `targetSpeedStep = 0`, then directly call `throttle.setSpeedSetting(-1f)` for DCC E-Stop. | | |
| TASK-020 | Wire E-Stop to RailDriver SPDT #2 (byte 4 bit mapping) in `RailDriverMenuItem`. On E-Stop assertion, call `engine.emergencyHalt()`. Recovery is automatic — next lever-change event calls `recomputeTarget()`. | | |
| TASK-021 | Wire reverser lever (lever #8) in `RailDriverMenuItem` to call `engine.setDirection()` with FORWARD/NEUTRAL/REVERSE based on calibrated thresholds. | | |
| TASK-022 | Write unit tests: reverser flip at non-zero speed is ignored; reverser flip at zero speed takes effect; E-Stop cancels all three epochs; after E-Stop, next lever-change resumes normal ramp. | | |

### Phase 4 — Brake System: Independent, Dynamic, Bail-Off & Combination

- GOAL-004: Implement independent brake quantisation, dynamic brake with low-speed taper, bail-off, and the effective brake combination formula. (Epic Features 2a, 2c, 2d, 2e)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-023 | Implement independent brake quantisation in engine: lever #11 (byte 3) calibrated range → `numberOfBrakeSteps` notches. Position 0 = released. Each step change calls `recomputeTarget()`. Compute `rawIndepPcnt` from notch position (0.0 = released, 1.0 = full application as a percentage; but in EngineDriver convention, 1.0 = no braking, approaching 0.0 = full braking). | | |
| TASK-024 | Implement dynamic brake in engine: below-idle throttle travel (lever #9, below Idle threshold) produces `dynBrakeStep` in `0..numberOfBrakeSteps`. Compute `rawDynPcnt`. Low-speed taper: below `dynBrakeMinSpeedStep` (default 8), dyn-brake effect fades linearly to zero at speed 0. Implement `effectiveDynBrakeStep(currentSpeed, dynBrakeStep, dynBrakeMinSpeedStep)` as a static testable method. | | |
| TASK-025 | Implement bail-off in engine: `setBailoffPressed(boolean)`. While asserted, all loco-side braking is released — `effectiveIndepPcnt = 1.0`, `effectiveDynPcnt = 1.0`, and the locomotive's share of the auto brake is released. Only car-brake retardation continues. At light engine (loadMultiplier = 1.0, no cars), bail-off releases all braking entirely. | | |
| TASK-026 | Implement effective brake combination formula in `recomputeTarget()` or a dedicated `computeEffectiveBrake()` method. Normal: `effectiveIndepPcnt = 1.0 − ((1.0 − rawIndepPcnt) / loadMultiplier)`, `effectiveDynPcnt = 1.0 − ((1.0 − rawDynPcnt) / loadMultiplier)`, `effectiveAirPcnt = rawAirPcnt` (load-invariant). `effectiveBrake = min(effectiveIndepPcnt, effectiveAirPcnt, effectiveDynPcnt)`. Bail-off variant per epic §2e. | | |
| TASK-027 | Wire `effectiveBrake` into the ramp scheduler's `targetAcceleration` computation: `effectiveBrake` modifies the decel multiplier that scales Δt, matching EngineDriver's `setTargetSpeed` brake integration. | | |
| TASK-028 | Write unit tests: independent brake quantisation at each notch; dynamic brake low-speed taper fading to zero; bail-off releases all loco-side braking; bail-off at light engine = no braking; effective brake combination at light engine (1.0×) matches EngineDriver single-source `min()`; at full load (10×), indep brake retardation is 1/10th of unloaded; at full load, auto brake retardation is unchanged. Table-driven tests for `effectiveDynBrakeStep`. | | |

### Phase 5 — Brake System: Air / Westinghouse Model

- GOAL-005: Implement the simplified Westinghouse automatic air brake with asymmetric apply/release, reservoir-gated line recharge, lap behaviour, and emergency recovery. (Epic Feature 2b)

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

- GOAL-006: Port EngineDriver's load slider with quadratic `getLoadPcnt` formula and per-source brake load scaling. (Epic Feature 4)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-037 | Implement `getLoadPcnt(int step, int numberOfLoadSteps, int maxLoadPcnt)` as a `static` method on the engine (or a utility class). Formula: `((load² × (maxLoadPcnt − 100)) + 100) / 100` where `load = step / numberOfLoadSteps`. Guard: when `step == 0`, return 1.0 (skip formula entirely, matching EngineDriver's `if (loadSliderPosition > 0)` guard). | | |
| TASK-038 | Wire load multiplier into `recomputeTarget()` / `setTargetSpeed()`: `targetAcceleration *= loadMultiplier`. Higher load = longer inter-step delay = slower acceleration. This is the same single wiring point EngineDriver uses. | | |
| TASK-039 | Wire per-source brake load scaling (already implemented in Phase 4 TASK-026 formula): verify independent and dynamic brake effectiveness is reduced by load, auto brake is load-invariant. Add `prevLoadStep` change detection — only restart ramp if load actually changed. | | |
| TASK-040 | Write table-driven unit tests for `getLoadPcnt`: at defaults (5 steps, maxLoadPcnt=1000), step 0→1.0×, step 1→1.36×, step 2→2.44×, step 3→4.24×, step 4→6.76×, step 5→10.0×. Non-default `maxLoadPcnt` values (500→5×, 200→2×). Guard condition at step 0. | | |

### Phase 7 — ESU Decoder Brake Passthrough

- GOAL-007: Port EngineDriver's `setDecoderBrake` function passthrough for ESU decoders. (Epic Feature 3)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-041 | Implement decoder brake passthrough in engine: when `decoderBrakeMode == ESU`, independent brake percent crossing each threshold (ascending: `esuLowThreshold`, `esuMidThreshold`, `esuHighThreshold`) toggles the configured function number (`esuLowFunction`, `esuMidFunction`, `esuHighFunction`) on the attached `DccThrottle`. Track previous function states to avoid redundant calls. | | |
| TASK-042 | Implement validation: thresholds must be ascending (`low < mid < high`), validated on save and on settings load. Function numbers must be non-negative. | | |
| TASK-043 | When `decoderBrakeMode == NONE` (default), short-circuit the entire passthrough — no function calls regardless of brake position. | | |
| TASK-044 | Write unit tests: ESU mode toggles functions at correct thresholds; NONE mode makes no function calls; invalid threshold ordering rejected on validation; function state tracking prevents redundant calls. | | |

### Phase 8 — Persistence & Legacy Migration

- GOAL-008: Migrate persistence from freestanding `raildriver-calibration.xml` to two `AuxiliaryConfiguration` fragments. Create XSD schemas. Implement one-time legacy file migration. (Epic Features 7, 8)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-045 | Create XSD schema `xml/schema/raildriver/hardwareCalibration.xsd` for the `<rd:hardwareCalibration>` fragment (namespace `http://jmri.org/xml/schema/raildriver/3`). Define elements for 7 axis calibration POJOs (reverser, throttle, autoBrake, indepBrake, wiper, lights — each with their detent byte values). Follow Venetian Blinds pattern. Include `<jmri:usingclass>` annotation. Use `<?xml-stylesheet href="schema2xhtml.xsl" type="text/xsl"?>` header. | | |
| TASK-046 | Create XSD schema `xml/schema/raildriver/semiRealistic.xsd` for the `<rd:semiRealistic>` fragment (same namespace). Define elements/attributes for all settings fields from TASK-008. Use standard JMRI types: `trueFalseType` for `enabled`, `EnumIoNames` pattern for `decoderBrakeMode`. | | |
| TASK-047 | Validate both schemas: `xmllint -noout -schema http://www.w3.org/2001/XMLSchema.xsd xml/schema/raildriver/hardwareCalibration.xsd` and same for `semiRealistic.xsd`. | | |
| TASK-048 | Implement `RailDriverPreferencesManager` in `jmri.jmrit.usb` — annotated `@ServiceProvider(service = PreferencesManager.class)`. Owns load/save of both fragments via `ProfileUtils.getAuxiliaryConfiguration(profile)`. On `initialize(profile)`: load `<rd:hardwareCalibration>` (private space) and `<rd:semiRealistic>` (shared space). Missing fragment → schema defaults applied silently. Unparseable attribute → `ErrorHandler` report + default substituted. Unknown namespace → treated as missing. | | |
| TASK-049 | Implement `save()` on `RailDriverPreferencesManager`: write `<rd:hardwareCalibration>` to private space, `<rd:semiRealistic>` to shared space via `putConfigurationFragment()`. Fire `"settingsChanged"` and `"calibrationChanged"` PCS events as appropriate. | | |
| TASK-050 | Implement legacy file migration in `RailDriverPreferencesManager.initialize()`: detect `<profile-root>/profile/raildriver-calibration.xml`. Rule 1 (only legacy exists): copy detents to `<rd:hardwareCalibration>` (private), discard v2 `<semiRealistic>` subtree with warn-level ErrorHandler report, write fresh `<rd:semiRealistic>` at defaults to shared space, rename legacy file to `.bak`. Rule 2 (both exist): new fragments win, legacy file left in place, warn-level report. Rule 3 (neither exists): defaults on first load, fragment created on first save. Rule 4: idempotent — repeated calls short-circuit (check for `.bak` existence or fragment existence). | | |
| TASK-051 | Create `SchemaTest` fixture directories: `java/test/jmri/jmrit/usb/valid/` with sample XML for both fragments, `java/test/jmri/jmrit/usb/invalid/` with intentionally malformed examples (missing required attributes, out-of-range values, bad enum values). Write `SchemaTest.java` in `java/test/jmri/jmrit/usb/`. | | |
| TASK-052 | Create `LoadAndStoreTest` fixtures in `java/test/jmri/jmrit/usb/load/` for fragment round-trip. Create legacy migration test fixtures: v1 calibration file (detents only), v2 calibration file (detents + `<semiRealistic>` subtree). Write migration test verifying all four migration paths. | | |
| TASK-053 | *(Deferred to Phase 9)* Delete `RailDriverCalibration.java`'s `save()` / `loadOrDefault()` file-based persistence methods. Retain the POJO structure for in-memory use or merge into `RailDriverPreferencesManager` as needed. Requires all callers (settings frame, calibration panel) to be migrated first. | | |

### Phase 9 — JMRI Preferences Integration (Settings UI)

- GOAL-009: Deliver settings through two `jmri.swing.PreferencesPanel` SPI providers grouped under "RailDriver" in the standard JMRI Preferences window. Retire `RailDriverSettingsFrame`, `RailDriverSettingsAction`, `SemiRealisticSettingsPanel`. (Epic Feature 6)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-053 | *(From Phase 8)* Delete `RailDriverCalibration.java`'s `save()` / `loadOrDefault()` / `getDefaultFile()` file-based persistence methods. All callers must be migrated to `RailDriverPreferencesManager` first (TASK-060 retires the old settings frame). | | |
| TASK-054 | Create `jmri.jmrit.usb.swing.RailDriverSemiRealisticPreferencesPanel` — annotated `@ServiceProvider(service = PreferencesPanel.class)`. Implements `PreferencesPanel` with `getPreferencesItemText()` returning "RailDriver" (group) and panel-specific tab text. Contains UI controls for all EngineDriver-aligned settings from TASK-008: ramp delays, brake steps, air parameters, load slider (`JSlider` with tick labels showing computed multipliers), decoder brake mode/functions/thresholds. Includes "Reset to defaults" button. Validation: ESU thresholds ascending, numeric ranges, etc. | | |
| TASK-055 | Implement the load slider UI: `JSlider` (integer, 0..`numberOfLoadSteps`) with tick labels computed from `getLoadPcnt()` at each position (e.g. "1.0×", "2.44×", "10.0×"). Slider position updates label dynamically when `numberOfLoadSteps` or `maxLoadPcnt` change. | | |
| TASK-056 | Create `jmri.jmrit.usb.swing.RailDriverCalibrationPreferencesPanel` — annotated `@ServiceProvider(service = PreferencesPanel.class)`. Same "RailDriver" group. Hosts the existing visual-bar calibration UI (relocated `CalibrationTabPanel` / `CalibrationBar`). Wired to receive live `"RawByte"` events from `RailDriverMenuItem` for real-time cursor display during calibration. | | |
| TASK-057 | Implement live-apply wiring in `RailDriverMenuItem`: subscribe to `"settingsChanged"` PCS event from `RailDriverPreferencesManager`. On event, if engine is attached, push new `SemiRealisticSettings` snapshot via `engine.updateSettings()` on layout thread. Subscribe to `"calibrationChanged"` for axis detent updates. | | |
| TASK-058 | Implement `enabled` flag change detection: on save, if `enabled` changed, show `JmriJOptionPane` alert informing operator to close and reopen the throttle for the dispatch-strategy change to take effect. Do NOT show alert for other settings changes. | | |
| TASK-059 | Implement `isDirty()` tracking across all input controls on both panels. Wire to JMRI Preferences framework's save/apply/cancel lifecycle. | | |
| TASK-060 | Delete `RailDriverSettingsFrame.java`, `RailDriverSettingsAction.java`, `SemiRealisticSettingsPanel.java`. Remove the "RdSettings" Debug menu entry that launched the old frame. Update `RailDriverMenuItem` to remove references to the retired settings frame. | | |

### Phase 10 — Connectivity Indicator (Jynstrument)

- GOAL-010: Create a passive RailDriver-USB connectivity indicator on the throttle window toolbar. (Epic Feature 9)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-061 | Create `jmri.jmrit.usb.swing.RailDriverConnectivityIndicator` extending `Jynstrument`. Two visual states: active icon (connected) and greyed icon (disconnected). Listens to `RailDriverMenuItem.isRailDriverConnected()` via PCS. | | |
| TASK-062 | Implement click handlers: left-click opens JMRI Preferences → RailDriver group (navigate to the RailDriver preferences panel). Right-click shows popup menu with "Settings..." item doing the same. Both click paths work in both visual states (no `setEnabled(false)` on the indicator). | | |
| TASK-063 | Implement `quit()`: deregister PCS listener from `RailDriverMenuItem` to prevent listener leak. | | |
| TASK-064 | Wire auto-installation: `RailDriverMenuItem.attachThrottleWindow()` calls indicator installation idempotently on the throttle toolbar. | | |
| TASK-065 | Create indicator icon assets (active + greyed SVG/PNG) in `resources/icons/throttles/` or appropriate JMRI icon directory. | | |

### Phase 11 — Air Status Throttle Panel

- GOAL-012: Create a real-time air status display panel embedded in the JMRI throttle window showing current brake pipe (air line) pressure and main reservoir level for the Westinghouse brake system. Visible only when Westinghouse dynamics are active. (Epic Feature 2 user story: view air line and reservoir status)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-075 | Create `jmri.jmrit.usb.swing.RailDriverAirStatusPanel` extending `JPanel`. Display `airLineValue` (brake pipe pressure, 0–100) and `airReservoirPct` (main reservoir, 0–100) as labeled `JProgressBar` gauges with numeric readouts. Panel layout must be compact enough to fit in a throttle window without dominating the view. Subscribe to `"airLineValue"` and `"airReservoirPct"` PropertyChange events from the engine. Marshal updates to the GUI thread via `ThreadingUtil.runOnGUIEventually`. | | |
| TASK-076 | Wire panel installation into `RailDriverMenuItem.attachThrottleWindow()`: when semi-realistic mode is enabled and engine is attached, instantiate `RailDriverAirStatusPanel`, subscribe it to the engine's PropertyChange events, and add it to the throttle window panel area. Remove and dispose the panel on engine detach or throttle window close. Installation must be idempotent. | | |
| TASK-077 | Implement visibility gating: the air status panel is only installed when `airRefreshRateMs > 0` (Westinghouse dynamics active). When `airRefreshRateMs == 0` (flat-mapping mode), do not install the panel — there is no dynamic air state to display. On live settings update via `"settingsChanged"` PCS, add or remove the panel if `airRefreshRateMs` transitioned to/from zero. | | |
| TASK-078 | Implement `dispose()` on `RailDriverAirStatusPanel`: deregister all PropertyChange listeners from the engine to prevent listener leaks. Called from `RailDriverMenuItem` on throttle window close or engine detach. | | |
| TASK-079 | Write unit tests for `RailDriverAirStatusPanel`: verify panel gauge values update on PropertyChange events; verify `dispose()` deregisters listeners (no listener leak); verify panel is not installed when `airRefreshRateMs == 0`; verify panel is installed when `airRefreshRateMs > 0`; verify panel is removed/added on live `airRefreshRateMs` transition. | | |

### Phase 12 — Testing & Documentation

- GOAL-011: Comprehensive test suite with EngineDriver-verified expected values and user-facing documentation. (Epic Feature 11)

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-066 | Write/verify pure-math unit tests: `getBrakeDecimalPcnt` at each notch, `getLoadPcnt` table-driven (including non-default `maxLoadPcnt`), `effectiveDynBrakeStep` with taper. All expected values verified against EngineDriver source. | | |
| TASK-067 | Write/verify engine integration tests with mock `DccThrottle`: pure-throttle ramp (idle→full, full→idle), brake clip (brake applied during ramp), brake to zero (full brake to stop), air depletion (reservoir empty prevents release), bail-off (loco free-rolls while cars braked), direction interlock (reject reverser flip at speed), load multiplier scaling (verify acceleration stretch). | | |
| TASK-068 | Verify all `SchemaTest` fixtures validate (valid/) or fail (invalid/) as expected. Verify `LoadAndStoreTest` fixtures round-trip correctly. Verify legacy migration test fixtures exercise all four migration paths. | | |
| TASK-069 | Verify `warnOnce`/`infoOnce` test paths follow JMRI JUnit reset guidance (`Log4JFixture.setUp()`/`tearDown()` reset one-shot state). | | |
| TASK-070 | Create help page `help/en/html/tools/usb/RailDriverSemiRealistic.shtml` — operator-facing documentation covering the semi-realistic throttle mode, brake system (independent, air/Westinghouse, dynamic, bail-off), load slider, ESU decoder brake, and direction/E-Stop semantics. Include the key multiplier table from the epic. | | |
| TASK-071 | Create help page `help/en/html/tools/usb/RailDriverConnectionIndicator.shtml` — operator-facing documentation for the toolbar connectivity indicator. | | |
| TASK-072 | Update existing help page `help/en/html/tools/usb/RailDriverSettings.shtml` — document the new JMRI Preferences integration replacing the old settings frame. Document shared vs private persistence. | | |
| TASK-073 | Add Javadoc on every new public or protected API across all new/changed classes. Ensure `ant javadoc` produces no new warnings for the `jmri.jmrit.usb` package. | | |

## 3. Alternatives

- **ALT-001**: Keep the existing physics-based engine and add EngineDriver mode as a second option. Rejected: maintaining two divergent engines doubles testing and configuration surface without clear user benefit. The EngineDriver-aligned model is simpler, well-tested on Android, and provides a consistent cross-platform experience.
- **ALT-002**: Use `ScheduledExecutorService` for the ramp scheduler (matching the current implementation). Rejected: violates JMRI threading conventions (REQ-001). All timed events must use `ThreadingUtil.runOnLayoutDelayed` to remain on the layout thread.
- **ALT-003**: Keep `RailDriverSettingsFrame` as a standalone window alongside the Preferences integration. Rejected: the JMRI Preferences framework is the standard UI for settings (CON-002). A parallel custom window confuses operators and duplicates persistence logic.
- **ALT-004**: Store persistence in a freestanding XML file (current approach with `raildriver-calibration.xml`). Rejected: `AuxiliaryConfiguration` is the JMRI standard for profile-aware settings (CON-003). Freestanding files don't participate in shared/private space semantics or profile portability.
- **ALT-005**: Model prototype-accurate physics (mass, force, Davis equation). Rejected: this is an explicit non-goal of the epic. The decoder and JMRI roster speed profile remain the sole authority for actual model-train velocity.
- **ALT-006**: Implement air brake as a simple percentage mapping (no Westinghouse dynamics). Rejected: the Westinghouse model is the core differentiator for realistic train-handling feel. EngineDriver implements it; we port it faithfully and extend it for the continuous Auto Brake lever.

## 4. Dependencies

- **DEP-001**: `jmri.util.ThreadingUtil` — `runOnLayoutDelayed()`, `runOnLayout()`, `requireLayoutThread()`. Already available in JMRI core.
- **DEP-002**: `jmri.profile.ProfileUtils` — `getAuxiliaryConfiguration(profile)` for fragment-based persistence. Already available in JMRI core.
- **DEP-003**: `jmri.profile.AuxiliaryConfiguration` — `getConfigurationFragment()`, `putConfigurationFragment()`, `removeConfigurationFragment()`. Already available in JMRI core.
- **DEP-004**: `jmri.spi.PreferencesManager` — SPI interface for preferences management. Already available in JMRI core.
- **DEP-005**: `jmri.swing.PreferencesPanel` — SPI interface for preferences UI panels. Already available in JMRI core.
- **DEP-006**: `jmri.configurexml.AbstractXmlAdapter.EnumIoNames` — enum serialisation helper. Already available in JMRI core.
- **DEP-007**: `hid4java` — USB HID library for RailDriver device access. Already in JMRI's `lib/` directory.
- **DEP-008**: EngineDriver reference source: [`throttle_semi_realistic.java`](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java) at SHA `5e722d38`. Used for algorithm verification, not runtime dependency.

## 5. Files

### New files

- **FILE-001**: `java/src/jmri/jmrit/usb/SemiRealisticThrottleEngine.java` — step-rate scheduler engine (rewritten)
- **FILE-002**: `java/src/jmri/jmrit/usb/SemiRealisticSettings.java` — EngineDriver-aligned settings POJO (rewritten)
- **FILE-003**: `java/src/jmri/jmrit/usb/RailDriverPreferencesManager.java` — SPI PreferencesManager, persistence owner
- **FILE-004**: `java/src/jmri/jmrit/usb/swing/RailDriverSemiRealisticPreferencesPanel.java` — SPI PreferencesPanel for operator settings
- **FILE-005**: `java/src/jmri/jmrit/usb/swing/RailDriverCalibrationPreferencesPanel.java` — SPI PreferencesPanel for calibration
- **FILE-006**: `java/src/jmri/jmrit/usb/swing/RailDriverConnectivityIndicator.java` — Jynstrument toolbar indicator
- **FILE-007**: `xml/schema/raildriver/hardwareCalibration.xsd` — XSD for `<rd:hardwareCalibration>` fragment
- **FILE-008**: `xml/schema/raildriver/semiRealistic.xsd` — XSD for `<rd:semiRealistic>` fragment
- **FILE-009**: `help/en/html/tools/usb/RailDriverSemiRealistic.shtml` — new help page
- **FILE-010**: `help/en/html/tools/usb/RailDriverConnectionIndicator.shtml` — new help page
- **FILE-031**: `java/src/jmri/jmrit/usb/swing/RailDriverAirStatusPanel.java` — real-time air status display panel for the throttle window

### Relocated files (jmri.util.usb → jmri.jmrit.usb)

- **FILE-011**: `RailDriverMenuItem.java` — main lifecycle / polling / dispatch
- **FILE-012**: `RailDriverCalibration.java` — calibration POJO (persistence methods removed)
- **FILE-013**: `Bundle.java` + `Bundle.properties` + 5 locale variants
- **FILE-014**: `swing/CalibrationTabPanel.java` — visual-bar calibration UI
- **FILE-015**: `swing/CalibrationBar.java` — single-axis calibration bar widget

### Retired files

- **FILE-016**: `jmri.util.usb.RailDriverSettingsFrame` — replaced by PreferencesPanel SPI
- **FILE-017**: `jmri.util.usb.RailDriverSettingsAction` — replaced by Preferences navigation
- **FILE-018**: `jmri.util.usb.SemiRealisticSettingsPanel` — replaced by `RailDriverSemiRealisticPreferencesPanel`
- **FILE-019**: `jmri.util.usb.LoadScenario` — physics presets retired; replaced by flat configurable fields

### Modified files

- **FILE-020**: `java/src/jmri/configurexml/ClassMigration.properties` — add old→new package mappings
- **FILE-021**: `java/src/apps/jmrit/DebugMenu.java` — update imports from `jmri.util.usb` to `jmri.jmrit.usb`
- **FILE-022**: `help/en/html/tools/usb/RailDriverSettings.shtml` — update for new Preferences integration

### Test files (new)

- **FILE-023**: `java/test/jmri/jmrit/usb/SemiRealisticThrottleEngineTest.java`
- **FILE-024**: `java/test/jmri/jmrit/usb/SemiRealisticSettingsTest.java`
- **FILE-025**: `java/test/jmri/jmrit/usb/RailDriverPreferencesManagerTest.java`
- **FILE-026**: `java/test/jmri/jmrit/usb/SchemaTest.java`
- **FILE-027**: `java/test/jmri/jmrit/usb/LoadAndStoreTest.java`
- **FILE-028**: `java/test/jmri/jmrit/usb/valid/` — valid XML fixture directory
- **FILE-029**: `java/test/jmri/jmrit/usb/invalid/` — invalid XML fixture directory
- **FILE-030**: `java/test/jmri/jmrit/usb/load/` — load/store fixture directory
- **FILE-032**: `java/test/jmri/jmrit/usb/swing/RailDriverAirStatusPanelTest.java` — air status panel unit tests

## 6. Testing

- **TEST-001**: Pure-math unit tests for `getBrakeDecimalPcnt` — table-driven at each notch with EngineDriver-verified expected values.
- **TEST-002**: Pure-math unit tests for `getLoadPcnt` — table-driven at each default slider step (0→1.0×, 1→1.36×, 2→2.44×, 3→4.24×, 4→6.76×, 5→10.0×) and at non-default `maxLoadPcnt` values (500, 200).
- **TEST-003**: Pure-math unit tests for `effectiveDynBrakeStep` — taper fading to zero below threshold.
- **TEST-004**: Engine integration tests with mock `DccThrottle` — pure-throttle ramp (idle→full ~19s, full→idle ~50s at defaults), brake clip, brake to zero, direction interlock, E-Stop epoch cancellation.
- **TEST-005**: Air system integration tests — instant application, gradual release, reservoir depletion blocks release, lap behaviour, emergency recovery timing, flat-mapping mode (`airRefreshRateMs = 0`).
- **TEST-006**: Bail-off integration tests — all loco-side braking released, car-brake-only retardation at load, light engine bail-off = no braking.
- **TEST-007**: Load multiplier integration tests — acceleration stretch at each load step, per-source brake scaling (indep reduced, auto invariant), `prevLoadStep` change detection.
- **TEST-008**: ESU decoder brake tests — function toggle at thresholds, NONE mode inert, ascending threshold validation.
- **TEST-009**: `SchemaTest` — valid fixtures pass, invalid fixtures fail, for both `hardwareCalibration.xsd` and `semiRealistic.xsd`.
- **TEST-010**: `LoadAndStoreTest` — fragment round-trip produces identical output.
- **TEST-011**: Legacy migration tests — v1 file (detents migrate, defaults for semi-realistic), v2 file (detents migrate, semi-realistic discarded with warn), both exist (new wins), neither exists (defaults). Idempotency verified.
- **TEST-012**: `warnOnce`/`infoOnce` paths follow JMRI JUnit reset guidance.
- **TEST-013**: `ArchitectureTest` — no new violations from package relocation.
- **TEST-014**: Air status panel integration tests — PropertyChange-driven gauge updates reflect engine air state, `dispose()` deregisters listeners (no leak), panel not installed in flat-mapping mode (`airRefreshRateMs == 0`), panel installed when Westinghouse dynamics active, panel added/removed on live `airRefreshRateMs` transitions.

## 7. Risks & Assumptions

- **RISK-001**: EngineDriver algorithm drift — the reference source may change between plan creation and implementation completion. Mitigation: pin to SHA `5e722d38` for the `throttle_semi_realistic.java` reference. Document any upstream changes encountered.
- **RISK-002**: `ThreadingUtil.runOnLayoutDelayed` timing precision — ramp timing tests depend on millisecond-level scheduling accuracy. On loaded CI machines, timer callbacks may fire late. Mitigation: use tolerance ranges (e.g. ±20%) in time-dependent assertions; prefer step-count verification over wall-clock duration where possible.
- **RISK-003**: Air system complexity — the Westinghouse model with three interacting state variables (line, reservoir, demand) and two self-rescheduling repeaters is the most complex feature. Mitigation: extensive unit tests; separate the air state machine into testable pure functions fed by the repeater callbacks.
- **RISK-004**: Package relocation in Phase 1 may surface hidden dependencies from other parts of the codebase beyond `DebugMenu`. Mitigation: `ClassMigration.properties` handles XML-persisted references; grep for all `jmri.util.usb` import statements across the tree before committing.
- **RISK-005**: Legacy migration edge cases — corrupted or partially-written v1/v2 files in the wild may have unexpected structure. Mitigation: wrap migration parsing in try/catch with ErrorHandler reporting; fall back to defaults on any parse failure.
- **RISK-006**: Throttle window panel integration — the JMRI throttle window layout is complex and may not have an obvious insertion point for the air status panel. The panel must be compact, non-intrusive, and compatible with all throttle window configurations (single, multiple, tabbed). Mitigation: use a small `JPanel` with `JProgressBar` gauges that fits naturally alongside existing throttle controls; test with multiple throttle window configurations.
- **ASSUMPTION-001**: The JMRI layout thread is the correct thread for all engine operations. The epic and JMRI conventions confirm this, but no existing RailDriver code currently uses this pattern (the current engine uses a `ScheduledExecutorService` worker thread).
- **ASSUMPTION-002**: `AuxiliaryConfiguration` is available for all active profiles. This is a standard JMRI API and should always be present, but has not been previously used by RailDriver code.
- **ASSUMPTION-003**: The JMRI Preferences window supports arbitrary grouping of `PreferencesPanel` providers. A "RailDriver" group with two tabs (Semi-Realistic + Calibration) is the target.
- **ASSUMPTION-004**: The RailDriver HID polling thread will continue to fire `PropertyChange` events to `RailDriverMenuItem`, which then dispatches to the engine on the layout thread. The polling-thread → layout-thread handoff already exists conceptually but must be verified/updated during engine rewrite.

## 8. Related Specifications / Further Reading

- [EngineDriver-Aligned Semi-Realistic Throttle Epic](engine-driver-semi-realistic-throttle-epic.md) — the source epic for this plan
- [EngineDriver Algorithm Research](semi-realistic-throttle-info.md) — line-by-line reference of the EngineDriver source
- [RailDriver Control Inventory](control-inventory.md) — physical control / HID byte mapping
- [EngineDriver Source (SHA 5e722d38)](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java) — upstream reference implementation
- [JMRI Threading Conventions](https://www.jmri.org/help/en/html/doc/Technical/Threads.shtml) — ThreadingUtil, runOnLayoutDelayed
- [JMRI XML Schema](https://www.jmri.org/help/en/html/doc/Technical/XmlSchema.shtml) — Venetian Blinds pattern, schema validation
- [JMRI Preferences Architecture](https://www.jmri.org/help/en/html/doc/Technical/AppPreferences.shtml) — PreferencesManager, PreferencesPanel, AuxiliaryConfiguration
- [JMRI Plug-in / SPI Patterns](https://www.jmri.org/help/en/html/doc/Technical/plugins.shtml) — ServiceProvider annotation, ServiceLoader
