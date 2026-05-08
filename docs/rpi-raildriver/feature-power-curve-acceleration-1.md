---
goal: Implement logarithmic power curve acceleration for the semi-realistic throttle engine
version: 1.0
date_created: 2026-05-07
owner: thedave42
status: 'Planned'
tags:
  - feature
  - raildriver
  - semi-realistic-engine
  - acceleration
---

# Introduction

![Status: Planned](https://img.shields.io/badge/status-Planned-blue)

Implement a logarithmic delay curve for the acceleration ramp in the semi-realistic throttle engine. When the throttle lever moves to a higher position, the inter-step delay starts near `minEmitIntervalMs` (~50 ms) and increases toward `baseAccelDelayMs` (~300 ms) as the current speed approaches the target, following the formula `delay = minDelay + (maxDelay - minDelay) × (e^(progress×k) - 1) / (e^k - 1)`. A single configurable parameter `k` (range 0.1–1.0) controls the curve shape. The power curve applies only to acceleration — deceleration continues to use the existing constant-delay behavior.

**Epic:** [power-curve-acceleration-epic.md](power-curve-acceleration-epic.md)

## 1. Requirements & Constraints

- **FR-001**: Track `rampStartStep` — the speed step at which the current acceleration ramp began — reset each time `recomputeTarget()` initiates a new accelerating ramp.
- **FR-002**: When accelerating (`currentSpeedStep < targetSpeedStep`), compute inter-step delay as an interpolation between `minEmitIntervalMs` (floor) and `baseAccelDelayMs × loadMultiplier` (ceiling), shaped by the exponential curve formula based on ramp progress (0.0 → 1.0).
- **FR-003**: Use formula: `delay = minDelay + (maxDelay - minDelay) × (Math.expm1(progress × k) / Math.expm1(k))`, where `k` is the configurable curve steepness (range 0.1–1.0).
- **FR-004**: Power curve applies only to acceleration. Deceleration (coast-down, braking) continues to use the existing constant-delay behavior via the current `computeRampDelay()` path.
- **FR-005**: When the throttle lever moves during an active ramp, `rampStartStep` resets to the current speed step and a new power curve begins from that point.
- **FR-006**: Speed step increment remains at `settings.speedStepIncrement` per tick — power curve affects only timing between steps.
- **FR-007**: Load multiplier and brake modifier effects on `targetAcceleration` continue to function, modifying the delay ceiling that the logarithmic curve approaches.
- **FR-008**: One new field added to `SemiRealisticSettings`: `powerCurveSteepness` (double, valid range 0.1–1.0, default 0.5).
- **FR-009**: The new setting persists to and loads from the RailDriver calibration XML using the existing `SemiRealisticSettings` XML store/load pattern.
- **FR-010**: The settings UI exposes a **Curve Steepness** spinner (range 0.1–1.0, step 0.1) in the semi-realistic engine settings tab under a new "Power Curve" section, with a tooltip explaining the setting.

- **NFR-001**: All new logic executes on the JMRI layout thread. No new threads, executors, or timers. Timed events use `ThreadingUtil.runOnLayoutDelayed()` exclusively.
- **NFR-002**: The `Math.expm1()` call happens once per ramp tick (every 50–300 ms) — negligible overhead.
- **NFR-003**: Power curve does not change behavior of braking, load, air line, dynamic brake, bail-off, direction interlocking, or ESU decoder brake passthrough.
- **NFR-004**: The logarithmic delay computation is extractable as a `static` method for unit testing with known inputs and expected outputs.
- **NFR-005**: `rampStartStep` is invalidated when ramp epoch changes, preventing stale progress calculations across ramp boundaries.

- **CON-001**: All field additions to `SemiRealisticSettings` must also be added to `copyFrom()`, `resetToDefaults()`, `loadFrom()`, and `writeTo()`.
- **CON-002**: `SemiRealisticSettingsPanel` dirty-tracking and enable/disable must cover the new spinner.
- **CON-003**: Backward compatibility — XML files without `powerCurveSteepness` element must load cleanly using the default value.

- **PAT-001**: Follow existing `SemiRealisticSettings` field pattern: public field, default constant, `loadFrom`/`writeTo`/`copyFrom`/`resetToDefaults` coverage.
- **PAT-002**: Follow existing `SemiRealisticSettingsPanel` pattern: `SpinnerNumberModel`, `attachDirtyOnSpinner`, `renderToFields`, `refreshEnableState`, `validateAndApplyTo` coverage.
- **PAT-003**: Follow existing engine test pattern: pure static method tests with known inputs/expected outputs (like `getBrakeDecimalPcnt`, `getLoadPcnt`).
- **PAT-004**: A `readDouble` XML helper must be added to `SemiRealisticSettings` (only `readInt`/`readBool`/`readText` exist today).

## 2. Implementation Steps

### Phase 1: Core Delay Computation

- GOAL-001: Add the static `computePowerCurveDelay` method to `SemiRealisticThrottleEngine` and fully unit-test it in isolation before wiring it into the ramp pipeline.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-001 | Add `static int computePowerCurveDelay(int rampStartStep, int currentSpeedStep, int targetSpeedStep, int minDelayMs, int maxDelayMs, double steepnessK)` to `SemiRealisticThrottleEngine`. Returns the inter-step delay for the current ramp position using `delay = minDelay + (maxDelay - minDelay) × (Math.expm1(progress × k) / Math.expm1(k))` where `progress = (currentSpeedStep - rampStartStep) / (targetSpeedStep - rampStartStep)`. Clamp progress to [0.0, 1.0]. Guard against division by zero when `targetSpeedStep == rampStartStep` (return `maxDelayMs`). Clamp result to `[minDelayMs, maxDelayMs]`. | | |
| TASK-002 | Add unit tests in `SemiRealisticThrottleEngineTest` for `computePowerCurveDelay`: (a) progress=0.0 returns ~`minDelayMs`, (b) progress=1.0 returns ~`maxDelayMs`, (c) monotonically increasing delay across the ramp, (d) k=0.1 produces a more gradual curve vs k=1.0, (e) `rampStartStep == targetSpeedStep` returns `maxDelayMs`, (f) `currentSpeedStep` below `rampStartStep` clamps to progress=0.0, (g) `currentSpeedStep` above `targetSpeedStep` clamps to progress=1.0. | | |
| TASK-003 | Verify all existing tests still pass after adding the static method (no behavioral change yet). | | |

### Phase 2: Settings & XML Persistence

- GOAL-002: Add the `powerCurveSteepness` field to `SemiRealisticSettings` with full persistence support.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-004 | Add `public static final double DEFAULT_POWER_CURVE_STEEPNESS = 0.5` constant and `public double powerCurveSteepness = DEFAULT_POWER_CURVE_STEEPNESS` field to `SemiRealisticSettings`. | | |
| TASK-005 | Add private `readDouble(Element parent, String childName)` helper to `SemiRealisticSettings`, following the pattern of the existing `readInt` helper but using `Double.parseDouble`. | | |
| TASK-006 | Update `resetToDefaults()` to reset `powerCurveSteepness` to `DEFAULT_POWER_CURVE_STEEPNESS`. | | |
| TASK-007 | Update `copyFrom(SemiRealisticSettings other)` to copy `powerCurveSteepness`. | | |
| TASK-008 | Update `loadFrom(Element)` to read `powerCurveSteepness` via `readDouble`, clamping to [0.1, 1.0]. Missing element falls back to default. | | |
| TASK-009 | Update `writeTo()` to write `powerCurveSteepness` as a `<powerCurveSteepness>` child element using `Double.toString()`. | | |
| TASK-010 | Add/update unit tests in `SemiRealisticSettingsTest`: (a) default value assertion, (b) `copyFrom` covers new field, (c) `resetToDefaults` covers new field, (d) XML round-trip for `powerCurveSteepness`, (e) missing element falls back to default, (f) out-of-range value (e.g. 5.0) is clamped to 1.0, (g) negative value is clamped to 0.1. | | |

### Phase 3: Wire Power Curve into Ramp Pipeline

- GOAL-003: Integrate the power curve delay into the engine's acceleration ramp so that acceleration uses the logarithmic curve and deceleration is unchanged.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-011 | Add `private int rampStartStep = 0` field to `SemiRealisticThrottleEngine`. | | |
| TASK-012 | In `recomputeTarget()`, at the point where `rampEpoch` is bumped and a new ramp is started (line ~538), set `rampStartStep = currentSpeedStep` when the new ramp is accelerating (`targetSpeedStep > currentSpeedStep`). For decelerating ramps, `rampStartStep` is irrelevant (the power curve won't be used). | | |
| TASK-013 | In `recomputeTarget()`, reset `rampStartStep` in `detachThrottle()` and `emergencyHalt()` to 0 alongside the other state resets. | | |
| TASK-014 | Modify `computeRampDelay()` (or add overload): when `targetAcceleration > 0` (accelerating) and `settings.powerCurveSteepness > 0`, delegate to `computePowerCurveDelay(rampStartStep, currentSpeedStep, targetSpeedStep, settings.minEmitIntervalMs, baseDelay_with_load, settings.powerCurveSteepness)` where `baseDelay_with_load = (int) Math.round(settings.baseAccelDelayMs * Math.abs(targetAcceleration))`. When decelerating, use the existing constant-delay formula unchanged. | | |
| TASK-015 | In `rampCallback()`, the re-scheduling call at line ~831 already calls `computeRampDelay()` — verify that each tick sees a progressively longer delay as `currentSpeedStep` advances toward `targetSpeedStep`. No change needed to `rampCallback()` itself; the delay change is fully encapsulated in `computeRampDelay()`. | | |
| TASK-016 | Verify that mid-ramp re-targeting works: when `recomputeTarget()` fires during an active acceleration ramp (throttle lever moved further), `rampStartStep` resets to `currentSpeedStep`, the epoch bumps, and a new logarithmic curve begins from the current position. Validate by inspection and existing test suite. | | |
| TASK-017 | Run full existing test suite to confirm no regressions. | | |

### Phase 4: Settings UI

- GOAL-004: Expose the power curve steepness control in the semi-realistic engine settings tab.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-018 | Add a `JSpinner steepnessSpinner` field to `SemiRealisticSettingsPanel` with `SpinnerNumberModel(0.5, 0.1, 1.0, 0.1)`. | | |
| TASK-019 | In `buildBody()`, add a new "Power curve" section label (using `addSectionLabel`) between the "Ramp timing" section and the "Brakes" section. Add a labeled spinner `"Curve steepness (k):"` with tooltip: `"Controls how aggressively the power curve front-loads acceleration. Lower values (0.1) produce a gradual curve; higher values (1.0) produce a sharp initial burst."`. | | |
| TASK-020 | Wire the spinner into dirty tracking via `attachDirtyOnSpinner(steepnessSpinner)`. | | |
| TASK-021 | In `renderToFields()`, set the spinner value from `working.powerCurveSteepness`. | | |
| TASK-022 | In `refreshEnableState()`, enable/disable the steepness spinner based on the enable checkbox. | | |
| TASK-023 | In `validateAndApplyTo()`, read the spinner value and write it to `working.powerCurveSteepness`. | | |
| TASK-024 | Manually verify the UI: open Debug → RailDriver Settings, navigate to the Semi-Realistic tab, confirm the new "Power curve" section appears with the steepness spinner, tooltip is visible, dirty tracking works, save/reset round-trips the value. | | |

### Phase 5: Integration Testing & Polish

- GOAL-005: End-to-end validation that the power curve produces the expected acceleration feel and all existing behavior is preserved.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-025 | Manual test: with RailDriver attached, push throttle to 50% and observe that DCC speed steps increase quickly at first and slow down as they approach the target. Compare behavior with `powerCurveSteepness = 0.1` vs `1.0`. | | |
| TASK-026 | Manual test: mid-ramp re-targeting. While accelerating to 40%, push throttle to 60%. Verify a fresh burst of acceleration occurs from the current speed. | | |
| TASK-027 | Manual test: load interaction. Set load slider to position 3/5, accelerate to 40%. Verify the initial burst is present but overall acceleration is slower than at load 0. | | |
| TASK-028 | Manual test: deceleration is unchanged. Release throttle and verify coast-down uses the same constant delay as before (no power curve on decel). | | |
| TASK-029 | Run `ant headlesstest` to confirm no regressions in the full JMRI test suite. | | |

## 3. Alternatives

- **ALT-001**: Variable step size (increment >1 at start, 1 near target) instead of variable delay. Rejected because it produces coarser DCC speed granularity at the start of the ramp, which can cause visible speed jumps on decoders with low step counts. The delay-curve approach preserves 1-step-per-tick smoothness.
- **ALT-002**: Separate `powerCurveEnabled` boolean toggle in addition to steepness. Rejected because the steepness value itself suffices — any value in [0.1, 1.0] enables the curve, and the existing linear behavior can be approximated at very low k values. Adding a separate toggle increases UI complexity without clear benefit.
- **ALT-003**: Apply the power curve to deceleration as well. Deferred per epic scope — the deceleration feel (constant-rate coast or brake-modulated decel) is a separate tuning concern with different user expectations (braking should feel consistent, not front-loaded).

## 4. Dependencies

- **DEP-001**: `SemiRealisticThrottleEngine` — the ramp pipeline (`recomputeTarget`, `computeRampDelay`, `rampCallback`) is the integration point. Already exists and is well-tested.
- **DEP-002**: `SemiRealisticSettings` — the settings POJO with XML persistence. Already exists with a proven load/store pattern.
- **DEP-003**: `SemiRealisticSettingsPanel` — the Swing settings tab. Already exists with dirty-tracking infrastructure.
- **DEP-004**: `RailDriverCalibration` — the top-level calibration container that holds `SemiRealisticSettings`. Already persists and loads correctly.
- **DEP-005**: `java.lang.Math.expm1()` — standard JDK, no external dependency.

## 5. Files

- **FILE-001**: `java/src/jmri/jmrit/usb/SemiRealisticThrottleEngine.java` — Add `computePowerCurveDelay` static method, `rampStartStep` field, modify `computeRampDelay` and `recomputeTarget`.
- **FILE-002**: `java/src/jmri/jmrit/usb/SemiRealisticSettings.java` — Add `powerCurveSteepness` field, `DEFAULT_POWER_CURVE_STEEPNESS` constant, `readDouble` helper; update `resetToDefaults`, `copyFrom`, `loadFrom`, `writeTo`.
- **FILE-003**: `java/src/jmri/jmrit/usb/swing/SemiRealisticSettingsPanel.java` — Add steepness spinner, "Power curve" section, wire into dirty tracking and enable/disable logic.
- **FILE-004**: `java/test/jmri/jmrit/usb/SemiRealisticThrottleEngineTest.java` — Add `computePowerCurveDelay` unit tests.
- **FILE-005**: `java/test/jmri/jmrit/usb/SemiRealisticSettingsTest.java` — Add `powerCurveSteepness` persistence and default tests.

## 6. Testing

- **TEST-001**: `computePowerCurveDelay` at progress=0.0 returns `minDelayMs`.
- **TEST-002**: `computePowerCurveDelay` at progress=1.0 returns `maxDelayMs`.
- **TEST-003**: `computePowerCurveDelay` produces monotonically increasing delays across the full ramp range (step through every integer from `rampStartStep` to `targetSpeedStep`).
- **TEST-004**: `computePowerCurveDelay` with k=0.1 produces a more uniform delay distribution than k=1.0 (compare standard deviation or median delay).
- **TEST-005**: `computePowerCurveDelay` with `rampStartStep == targetSpeedStep` returns `maxDelayMs` (no ramp to traverse).
- **TEST-006**: `computePowerCurveDelay` clamps progress below 0.0 and above 1.0 gracefully.
- **TEST-007**: `SemiRealisticSettings.powerCurveSteepness` default is 0.5.
- **TEST-008**: `SemiRealisticSettings.copyFrom` copies `powerCurveSteepness`.
- **TEST-009**: `SemiRealisticSettings.resetToDefaults` resets `powerCurveSteepness` to 0.5.
- **TEST-010**: `SemiRealisticSettings` XML round-trip preserves `powerCurveSteepness`.
- **TEST-011**: `SemiRealisticSettings.loadFrom` with missing `powerCurveSteepness` element falls back to default.
- **TEST-012**: `SemiRealisticSettings.loadFrom` with out-of-range values clamps to [0.1, 1.0].
- **TEST-013**: All existing `SemiRealisticThrottleEngineTest` tests pass without modification (no regression).
- **TEST-014**: All existing `SemiRealisticSettingsTest` tests pass without modification (no regression).
- **TEST-015**: `ant headlesstest` passes (full JMRI test suite).

## 7. Risks & Assumptions

- **RISK-001**: The `Math.expm1()` precision at very small k values (0.1) could produce delays that are indistinguishable from a linear ramp. Mitigation: the minimum k is 0.1, which still produces a visually noticeable curve. The operator can increase k if the effect is too subtle.
- **RISK-002**: The `readDouble` helper must handle locale-sensitive decimal formatting in XML files. Mitigation: `Double.parseDouble` always uses `.` as the decimal separator (Java spec), and `Double.toString` always produces `.` format. JMRI XML is always written with Java's default formatting.
- **RISK-003**: Mid-ramp re-targeting resets `rampStartStep` to `currentSpeedStep`, which means a very small displacement (e.g. 2 steps) produces progress that jumps quickly from 0.0 to 1.0. Mitigation: this is actually desirable — a tiny throttle adjustment should not produce a dramatic acceleration burst.

- **ASSUMPTION-001**: The power curve steepness default of 0.5 provides a good middle-ground feel. This can be tuned after operator testing.
- **ASSUMPTION-002**: No `readDouble` helper exists in `SemiRealisticSettings` today — one must be added following the `readInt` pattern.
- **ASSUMPTION-003**: The existing `computeRampDelay()` return value is used in both `recomputeTarget()` (initial delay) and `rampCallback()` (re-scheduling delay), so modifying it to use the power curve automatically covers both call sites.
- **ASSUMPTION-004**: The `targetAcceleration` magnitude already incorporates the load multiplier by the time `computeRampDelay()` is called, so `baseAccelDelayMs × |targetAcceleration|` correctly serves as the load-adjusted delay ceiling for the power curve.

## 8. Related Specifications / Further Reading

- [Power Curve Acceleration Epic](power-curve-acceleration-epic.md)
- [Semi-Realistic Throttle Engine Driver Epic](engine-driver-semi-realistic-throttle-epic.md)
- [Semi-Realistic Throttle Info](semi-realistic-throttle-info.md)
- [JMRI Threading Conventions](../../.github/instructions/jmri-threads.instructions.md)
