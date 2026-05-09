---
goal: Replace single-scalar brake delay model with additive force model for per-source load scaling
version: 1
date_created: 2025-05-08
last_updated: 2025-05-08
owner: thedave42
status: 'In progress'
tags: [feature, semi-realistic, braking, raildriver]
---

# Introduction

![Status: Planned](https://img.shields.io/badge/status-Planned-blue)

Replace the global `delay = baseDecelDelayMs × |targetAcceleration × loadMultiplier|` deceleration
delay model in `SemiRealisticThrottleEngine` with an additive force model where each deceleration
source (rolling resistance, air brake, independent brake, dynamic brake) contributes force
independently with its own load behavior. The total force determines the delay via
`delay = baseDecelDelayMs / totalForce`. This fixes air brakes being disproportionately weak at
high load settings while preserving load-dependent coasting and smooth coast-to-brake transitions.

Epic: [additive-force-brake-model-epic.md](./additive-force-brake-model-epic.md)

## 1. Requirements & Constraints

- **REQ-001**: Air brake delay shall be approximately load-invariant (≤ 3× ratio between no-load and max-load, per SM-1).
- **REQ-002**: Loco-only brake (independent, dynamic) delay shall scale approximately proportionally with load (≈10× at max load, per SM-2).
- **REQ-003**: Coast delay shall scale with load (inertia). A heavy train coasts longer.
- **REQ-004**: Coast-to-first-brake-notch transition shall be smooth (≤ 2× delay change, per SM-3).
- **REQ-005**: At load 0, full brake delay shall match current behavior within 5% (per SM-4).
- **REQ-006**: NEUTRAL direction shall allow brakes to function (brakes affect deceleration rate, per FR-7).
- **REQ-007**: Regime C under-power braking shall apply `MAX_BRAKE_UNDER_POWER` softening (per FR-8).
- **REQ-008**: Acceleration delay computation (including power curve) shall be unchanged (per FR-10).
- **REQ-009**: Dynamic brake low-speed taper shall be preserved (per FR-11).
- **REQ-010**: Bail-off behavior shall be preserved (per FR-12).
- **REQ-011**: Regime C target speed clamping shall continue using per-source-scaled `effectiveBrake` (per FR-14).
- **CON-001**: All computation on layout thread. No new threads, locks, or volatile fields (per NFR-1).
- **CON-002**: Existing `SemiRealisticThrottleEngineTest` tests shall pass or be updated intentionally (per NFR-4).
- **PAT-001**: Force mapping must preserve no-load delay values. The critique identified that naive `force = 1 - brakePcnt` changes no-load full-brake delay from 240ms to 470ms. Use a mapping such as `force = (1/brakePcnt) - 1` that preserves the current curve at load 0.
- **PAT-002**: JMRI threading conventions — all scheduling via `ThreadingUtil.runOnLayoutDelayed`, no `ScheduledExecutorService` or `java.util.Timer`.

## 2. Implementation Steps

### Phase 1: Force mapping design and validation

- GOAL-001: Determine the exact force mapping formula that preserves no-load delay behavior while producing correct per-source load scaling. Validate with a numeric trace across all load levels and brake notches.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-001 | Derive the force mapping formula. Starting point: `brakeForce = (1 / brakePcnt) - 1` where `brakePcnt` is from `getBrakeDecimalPcnt()`. Verify that at load 0, `delay = baseDecelDelayMs / (coastForce + brakeForce) = baseDecelDelayMs × brakePcnt` matches the current delay formula `baseDecelDelayMs × |targetAcceleration|` for all regimes. | | |
| TASK-002 | Produce a numeric trace table for the force model across load positions 0–5, air brake notches 0–7, independent brake notches 0–7. Verify REQ-001 through REQ-005 are met by the numbers. Verify the coast→brake transition smoothness (REQ-004) at each load level. | | |
| TASK-003 | Determine how Regime C under-power softening (`MAX_BRAKE_UNDER_POWER`) maps into the force model. Define the scaling factor applied to brake forces when throttle is active and target ≤ current (REQ-007). Verify that at load 0, Regime C delay matches current behavior. | | |
| TASK-004 | Determine how NEUTRAL + brakes integrates with the force model (REQ-006). Current code: NEUTRAL skips regime logic, sets `targetAcceleration = -1.0`. New model: NEUTRAL sets `targetSpeedStep = 0` and uses additive force (including any active brakes) for delay. Define the exact code path. | | |

### Phase 2: Add static `computeDecelerationDelay` method

- GOAL-002: Implement the additive force delay calculation as a new `static` package-visible method on `SemiRealisticThrottleEngine`, independently testable without attaching a throttle. This method encapsulates the formula from Phase 1.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-005 | Add method `static int computeDecelerationDelay(double coastForce, double airForce, double locoForce, int baseDecelDelayMs)` to `SemiRealisticThrottleEngine`. Formula: `totalForce = coastForce + airForce + locoForce; return max(1, round(baseDecelDelayMs / totalForce))`. Clamp `totalForce` to a minimum of `0.001` to prevent division by zero. | | |
| TASK-006 | Add unit tests for `computeDecelerationDelay` in `SemiRealisticThrottleEngineTest`: (a) coast only at load 0 → 800ms, (b) coast only at load 5 → ~8000ms, (c) full air brake at load 0 → matches current delay within 5%, (d) full air brake at load 5 → ≤ 3× no-load delay, (e) full indep brake at load 5 → ≈10× no-load delay, (f) mixed air+indep at load 5 → shorter than either alone, (g) zero force clamped → very large but finite delay. | | |

### Phase 3: Add force computation helpers

- GOAL-003: Implement helper methods that compute the per-source force values from brake percentages, load multiplier, and regime context. These are called by `recomputeTarget` and feed `computeDecelerationDelay`.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-007 | Add method `static double brakeForce(double brakePcnt)` implementing the force mapping formula from TASK-001. Returns 0.0 when `brakePcnt >= 1.0` (brake released). | | |
| TASK-008 | Add method `static double coastForce(double loadMultiplier)` returning `1.0 / max(loadMultiplier, 1.0)`. At load 0, returns 1.0. At load 5 (multiplier 10), returns 0.1. | | |
| TASK-009 | Add unit tests for `brakeForce`: (a) released brake → 0.0, (b) full brake (0.30) → value that produces correct delay at load 0, (c) monotonically increasing as brakePcnt decreases from 1.0 to 0.30, (d) partial brake values match numeric trace from TASK-002. | | |
| TASK-010 | Add unit tests for `coastForce`: (a) load 0 → 1.0, (b) load 5 → 0.1, (c) intermediate loads match `1.0 / getLoadPcnt(step, steps, maxLoadPcnt)`. | | |

### Phase 4: Add instance fields for force state

- GOAL-004: Add instance fields to `SemiRealisticThrottleEngine` to hold the per-source force values and regime context computed by `recomputeTarget`, so `computeRampDelay` can access them.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-011 | Add three `private double` instance fields: `decelCoastForce`, `decelAirForce`, `decelLocoForce`, initialized to `1.0`, `0.0`, `0.0` respectively. Add a `private boolean` field `decelUnderPower` initialized to `false`. Add Javadoc explaining each field's role in the additive force model. | | |
| TASK-012 | Reset all four new fields in `detachThrottle()` and `emergencyHalt()` alongside the existing state resets. `decelCoastForce = 1.0; decelAirForce = 0.0; decelLocoForce = 0.0; decelUnderPower = false;` | | |

### Phase 5: Rewire `recomputeTarget` to compute force fields

- GOAL-005: Modify `recomputeTarget` to populate the force fields instead of applying global load scaling to `targetAcceleration`. This is the core behavioral change.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-013 | In `recomputeTarget`, after computing raw brake percentages (lines 481–487, BEFORE per-source scaling on lines 497–499), capture the raw values: `double rawIndepBrakePcnt = indepBrakePcnt; double rawDynBrakePcnt = dynBrakePcnt;` (airBrakePcnt is never per-source-scaled, so no separate raw needed). | | |
| TASK-014 | Remove the NEUTRAL short-circuit that skips regime logic (lines 453–456). Instead: (a) NEUTRAL still sets `targetSpeedStep = 0`, (b) NEUTRAL no longer forces `targetAcceleration = -1.0` — let the regime logic compute it from effectiveBrake, (c) NEUTRAL falls through to the regime block (remove the `direction != Direction.NEUTRAL` guard on line 507 or restructure so NEUTRAL participates in the brake regime). | | |
| TASK-015 | Replace lines 538–542 (global load scaling block) with force field computation: (a) `decelCoastForce = coastForce(loadMultiplier)`, (b) `decelAirForce = brakeForce(airBrakePcnt)` — using raw value (unaffected by per-source scaling), (c) compute effective dynamic brake force with low-speed taper already applied: `decelLocoForce = brakeForce(rawIndepBrakePcnt) / max(loadMultiplier, 1.0) + brakeForce(rawDynBrakePcnt) / max(loadMultiplier, 1.0)` where `rawDynBrakePcnt` was computed from the tapered `effDynStep`, (d) set `decelUnderPower = (direction != Direction.NEUTRAL && effectiveBrake < 1.0 && targetSpeedStep > 0 && targetSpeedStep <= currentSpeedStep)`, (e) if `decelUnderPower`, scale air and loco forces by `MAX_BRAKE_UNDER_POWER / MAX_BRAKE` to soften braking under throttle. | | |
| TASK-016 | Keep acceleration load scaling: `if (loadMultiplier > 1.0 && targetAcceleration > 0) { targetAcceleration *= loadMultiplier; }`. This preserves existing acceleration behavior (REQ-008). | | |

### Phase 6: Rewire `computeRampDelay` to use force model for deceleration

- GOAL-006: Modify the deceleration path of `computeRampDelay` to call `computeDecelerationDelay` using the force fields, replacing the `baseDecelDelayMs × |targetAcceleration|` formula.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-017 | In `computeRampDelay`, replace lines 860–863 (deceleration case) with: `return computeDecelerationDelay(decelCoastForce, decelAirForce, decelLocoForce, settings.baseDecelDelayMs);`. Keep the acceleration path (lines 847–857) unchanged. | | |
| TASK-018 | Update the `targetAcceleration` Javadoc (line 83–87) to note that for deceleration, the sign is still used to select the decel path in `computeRampDelay`, but the magnitude is no longer the delay multiplier — the additive force model computes the delay independently. | | |

### Phase 7: Update existing tests and add integration tests

- GOAL-007: Update tests that assert specific delay values or per-source scaling behavior to match the new model. Add integration tests that exercise the full `recomputeTarget` → `computeRampDelay` pipeline with a mock throttle.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-019 | Update `testPerSourceBrakeLoadScaling_lightEngine_unchanged` — per-source scaling on `effectiveBrake` is still used for Regime C target clamping; verify this test still passes or update assertions. | | |
| TASK-020 | Update `testPerSourceBrakeLoadScaling_fullLoad_indepReduced` — same: verify per-source scaling still applied to `effectiveBrake` for target clamping. | | |
| TASK-021 | Update `testPerSourceBrakeLoadScaling_airBrake_invariant` — verify air brake percentage is still not per-source-scaled. | | |
| TASK-022 | Add integration test: attach a `DebugThrottle`, set direction FORWARD, set throttle to 0, apply full air brake at load 0 and load 5. Capture the delay from `computeRampDelay` (make it package-visible or test via timing). Verify ratio ≤ 3× (SM-1). | | |
| TASK-023 | Add integration test: same setup, apply full independent brake at load 0 and load 5. Verify ratio ≈ 10× (SM-2). | | |
| TASK-024 | Add integration test: coast (no brakes) at load 0 vs load 5, verify delay scales with loadMultiplier. | | |
| TASK-025 | Add integration test: NEUTRAL direction with full air brake — verify brakes affect deceleration (delay is shorter than pure coast). This tests the NEUTRAL braking fix (REQ-006). | | |
| TASK-026 | Add integration test: coast → 1 notch air brake transition at load 5 — verify delay ratio ≤ 2× (SM-3). | | |
| TASK-027 | Add integration test: mixed braking (air + indep) produces shorter delay than either alone. | | |
| TASK-028 | Add integration test: bail-off zeroes brake forces, delay equals coast delay. | | |
| TASK-029 | Run full test suite (`ant headlesstest` or the usb test package) and fix any failures. | | |

### Phase 8: Documentation and cleanup

- GOAL-008: Update code comments, Javadoc, and the epic document to reflect the implemented model.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-030 | Update the class-level Javadoc on `SemiRealisticThrottleEngine` (lines 16–52) to describe the additive force model for deceleration alongside the step-rate scheduler description. | | |
| TASK-031 | Update the `recomputeTarget` method Javadoc (line 442–444) to describe the force field computation. | | |
| TASK-032 | Update `computeRampDelay` Javadoc (lines 833–842) to describe the additive force deceleration path. | | |
| TASK-033 | Remove or update the `loadChanged` variable (line 491) and `prevLoadStep` field (line 107) if they are no longer needed. If they are still used for other purposes (e.g., triggering ramp restart on load change), keep them. | | |
| TASK-034 | Update epic status in `docs/rpi-raildriver/additive-force-brake-model-epic.md`. | | |

## 3. Alternatives

- **ALT-001**: **Global load scaling only for acceleration (Approach A)**. Simple one-line change: `if (loadMultiplier > 1.0 && targetAcceleration > 0)`. Fixes air brake issue but loses load-dependent coasting entirely. A loaded train coasts at the same rate as a light engine, which doesn't simulate inertia. Rejected because coasting inertia is a core part of the operator experience.

- **ALT-002**: **Middle ground — skip load scaling when brakes active**. Condition: `if (loadMultiplier > 1.0 && (targetAcceleration > 0 || effectiveBrake >= 1.0))`. Preserves loaded coast and removes brake load scaling, but creates a 10× discontinuity at the coast→brake boundary at max load (8000ms → 770ms with first brake notch). Rejected because the discontinuity is jarring and scales with load.

- **ALT-003**: **Track dominant brake source (Approach B1)**. Binary flag `airDominant` to conditionally skip load scaling. Same discontinuity when dominance shifts, plus preserves double-counting on loco-only brakes. Rejected for both issues.

- **ALT-004**: **Separate per-source targetAcceleration (Approach B2)**. Run regime logic three times per source. Rejected due to regime side effects (Regime C modifies `targetSpeedStep`), ill-defined combination semantics, and significant refactoring cost.

- **ALT-005**: **Weighted blending (Approach B3)**. Blend load scaling by per-source contribution fraction. Rejected because zero-to-any-brake discontinuity persists (weight jumps from 0 to 1.0 when first air brake force appears) and loco-only brakes remain double-counted.

- **ALT-006**: **Match EngineDriver exactly**. Remove per-source scaling, keep only global load scaling. Would make air brakes behave identically to EngineDriver (all braking uniformly scaled by load). Rejected because the per-source distinction is the core improvement we're making over EngineDriver's simplified model.

## 4. Dependencies

- **DEP-001**: `SemiRealisticThrottleEngine.java` — the primary file being modified.
- **DEP-002**: `SemiRealisticSettings.java` — provides `baseDecelDelayMs`, `numberOfBrakeSteps`, `maxLoadPcnt`, `numberOfLoadSteps`. No changes needed.
- **DEP-003**: `SemiRealisticThrottleEngineTest.java` — existing tests to update/extend.
- **DEP-004**: `getBrakeDecimalPcnt()` static method — existing EngineDriver brake curve formula. Used as input to the force mapping. Not modified.
- **DEP-005**: `getLoadPcnt()` static method — existing EngineDriver quadratic load formula. Used to compute `loadMultiplier`. Not modified.
- **DEP-006**: `DebugThrottle` (from `jmri.jmrix.debugthrottle`) — used in tests as a mock DCC throttle.

## 5. Files

- **FILE-001**: `java/src/jmri/jmrit/usb/SemiRealisticThrottleEngine.java` — Primary change. Add force fields, helper methods, rewire `recomputeTarget` and `computeRampDelay`.
- **FILE-002**: `java/test/jmri/jmrit/usb/SemiRealisticThrottleEngineTest.java` — Update existing tests, add new unit and integration tests for the force model.
- **FILE-003**: `docs/rpi-raildriver/additive-force-brake-model-epic.md` — Update status on completion.
- **FILE-004**: `docs/rpi-raildriver/feature-additive-force-brake-model-1.md` — This plan file. Update status as phases complete.

## 6. Testing

- **TEST-001**: Unit test `computeDecelerationDelay` with known force values at load 0 and load 5 (TASK-006).
- **TEST-002**: Unit test `brakeForce` mapping preserves no-load delay curve (TASK-009).
- **TEST-003**: Unit test `coastForce` at each load step (TASK-010).
- **TEST-004**: Integration test: air brake delay ratio load-5/load-0 ≤ 3× (TASK-022, SM-1).
- **TEST-005**: Integration test: indep brake delay ratio load-5/load-0 ≈ 10× (TASK-023, SM-2).
- **TEST-006**: Integration test: coast→1-notch transition ≤ 2× at max load (TASK-026, SM-3).
- **TEST-007**: Integration test: no-load full-brake delay within 5% of current value (TASK-022, SM-4).
- **TEST-008**: Integration test: NEUTRAL + brakes → brakes affect delay (TASK-025).
- **TEST-009**: Integration test: mixed braking is additive (TASK-027).
- **TEST-010**: Integration test: bail-off releases all brake forces (TASK-028).
- **TEST-011**: Full test suite pass (TASK-029, SM-5).

## 7. Risks & Assumptions

- **RISK-001**: The force mapping formula `(1/brakePcnt) - 1` may not exactly preserve the EngineDriver brake curve shape across all notch levels. Phase 1 (TASK-001, TASK-002) mitigates this with numeric validation before any code changes.
- **RISK-002**: Changing NEUTRAL to allow brakes may surface pre-existing issues in how NEUTRAL interacts with the ramp callback or deferred direction changes. Phase 7 integration tests (TASK-025) cover this.
- **RISK-003**: The Regime C under-power softening factor `MAX_BRAKE_UNDER_POWER / MAX_BRAKE` applied to forces may not produce identical delay values to the current `targetAcceleration` formula for all effectiveBrake values. Phase 1 (TASK-003) validates this numerically.
- **RISK-004**: Dynamic brake low-speed taper is computed once in `recomputeTarget` and stored in force fields. Between `recomputeTarget` calls, `currentSpeedStep` changes in `rampCallback` but the taper is not recalculated. This is pre-existing behavior (the current code also computes `effDynStep` once per `recomputeTarget`). Accepted as-is.
- **ASSUMPTION-001**: The `getBrakeDecimalPcnt` formula and `MAX_BRAKE = 0.70` constant are correct and stable. The additive model builds on these values.
- **ASSUMPTION-002**: The `DebugThrottle` class is suitable for integration testing (it is already used in the existing test file).
- **ASSUMPTION-003**: `baseDecelDelayMs` default of 800ms is the correct baseline for the force model's `delay = baseDecel / totalForce` formula. At load 0 with no brakes, `totalForce = 1.0`, so `delay = 800ms` — matching the current coast delay.

## 8. Related Specifications / Further Reading

- [Additive Force Brake Model Epic](./additive-force-brake-model-epic.md)
- [Semi-Realistic Throttle Plan](./semi-realistic-throttle-plan.md)
- [EngineDriver throttle_semi_realistic.java](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java)
