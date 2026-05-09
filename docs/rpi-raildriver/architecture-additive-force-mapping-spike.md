---
title: "Additive Force Model — Force Mapping & Numeric Validation"
category: "Architecture & Design"
status: "🟢 Complete"
priority: "High"
timebox: "3 hours"
created: 2025-05-08
updated: 2025-07-15
owner: "thedave42"
tags: ["technical-spike", "architecture", "research", "semi-realistic", "braking"]
---

# Additive Force Model — Force Mapping & Numeric Validation

## Summary

**Spike Objective:** Derive and validate the force mapping formula that converts EngineDriver brake
percentages (`brakePcnt` from `getBrakeDecimalPcnt`) into additive force values, such that the
resulting `delay = baseDecelDelayMs / totalForce` matches current no-load behavior and produces
correct per-source load scaling at all load levels.

**Why This Matters:** The implementation plan (Phase 1, TASK-001 through TASK-004) depends on a
validated formula before any production code is written. A naive mapping (`force = 1 - brakePcnt`)
changes no-load full-brake delay from 240ms to 470ms — a 96% regression. The correct mapping must
be derived and verified against the existing delay curve before implementation proceeds.

**Timebox:** 3 hours

**Decision Deadline:** Before Phase 2 of the implementation plan begins.

## Research Question(s)

**Primary Question:** What force mapping formula `brakeForce(brakePcnt)` produces delay values
that (a) match the current model within 5% at load 0, and (b) achieve the additive-force load
scaling goals (air ≤ 3× ratio, loco ≈ 10× ratio, smooth coast→brake transitions)?

**Secondary Questions:**

- Q1: How does the Regime C under-power softening (`MAX_BRAKE_UNDER_POWER = 0.50`) translate
  into the force model? Does a simple scaling factor on brake forces produce equivalent delays at
  load 0?
- Q2: What does the full numeric trace look like for all 7 brake notches × 6 load positions ×
  3 brake sources? Does the coast→brake transition stay within 2× at every load level?
- Q3: Does the NEUTRAL + brakes path produce sensible delay values when the force model includes
  active brake forces?

## Investigation Plan

### Research Tasks

- [x] **RT-1**: Derive the force mapping algebraically from the constraint that at load 0,
  `baseDecel / (coastForce + brakeForce) = baseDecel × brakePcnt`. This gives
  `coastForce + brakeForce = 1/brakePcnt`, and with `coastForce = 1.0` at load 0,
  `brakeForce = (1/brakePcnt) - 1`. Verify this identity holds for all brakePcnt values
  in the range output by `getBrakeDecimalPcnt(0..7, 7, 0.70)`.

- [x] **RT-2**: Compute `getBrakeDecimalPcnt(step, 7, 0.70)` for steps 0–7 and tabulate the raw
  brakePcnt values. Then compute `brakeForce = (1/brakePcnt) - 1` for each. Verify
  `baseDecel / (1.0 + brakeForce) = baseDecel × brakePcnt` for each row.

- [x] **RT-3**: Compute the full delay table for air brake (notches 0–7) × load positions (0–5).
  For each cell: `coastForce = 1/loadMultiplier`, `airForce = (1/airBrakePcnt) - 1` (where
  airBrakePcnt comes from the notch), `totalForce = coastForce + airForce`,
  `delay = 800 / totalForce`. Verify:
  - Load 0, full brake delay ≈ 240ms (SM-4: within 5%)
  - Load 5, full brake delay ≤ 720ms (SM-1: ≤ 3× of load-0 value)
  - Coast→1st notch transition ≤ 2× at each load level (SM-3)

- [x] **RT-4**: Compute the full delay table for independent brake (notches 0–7) × load
  positions (0–5). For each cell: `coastForce = 1/loadMultiplier`,
  `indepForce = ((1/indepBrakePcnt) - 1) / loadMultiplier`, `totalForce = coastForce + indepForce`,
  `delay = 800 / totalForce`. Verify:
  - Load 0 matches air brake table (same formula at load 0)
  - Load 5, full brake delay ≈ 2400ms (SM-2: ≈ 10× of load-0 value)

- [x] **RT-5**: Compute mixed braking table (full air + full indep) at load positions 0–5. Verify
  delay is shorter than either source alone at each load level.

- [x] **RT-6**: Analyze Regime C under-power. Current formula:
  `targetAcceleration = -1 × (1 - effectiveBrake × MAX_BRAKE_UNDER_POWER)` where
  `MAX_BRAKE_UNDER_POWER = 0.50`. At load 0, the current delay is
  `baseDecel × |targetAcceleration| = baseDecel × (1 - effectiveBrake × 0.50)`.
  Determine the force-model equivalent. Options:
  - (a) Scale brake forces by `MAX_BRAKE_UNDER_POWER / MAX_BRAKE = 0.50/0.70 ≈ 0.714`
  - (b) Use a different formula derived from matching the current delay curve
  Compute delay for effectiveBrake = 0.30, 0.50, 0.70, 1.0 at load 0 under both the current
  model and each candidate. Pick the option with ≤ 5% error.

- [x] **RT-7**: Trace the NEUTRAL + brakes path. With NEUTRAL, `targetSpeedStep = 0`. If brakes
  are active, the force model gives `delay = baseDecel / (coastForce + brakeForces)`. At load 0
  with full air brake: `delay = 800 / (1.0 + 2.333) = 240ms`. At load 5: `delay = 800 / (0.1 + 2.333) = 329ms`.
  Compare to current NEUTRAL behavior (brakes ignored: `delay = baseDecel × loadMultiplier`).
  Document the behavioral change and confirm it matches the epic's Journey 6 expectation.

- [x] **RT-8**: Document all findings in the "Research Findings" section below with complete tables.

### Success Criteria

**This spike is complete when:**

- [x] The force mapping formula is algebraically derived and numerically validated across all
  7 brake notches × 6 load positions.
- [x] Delay values at load 0 match the current model within 5% for all brake notches and both
  Regime B and Regime C under-power.
- [x] The four success metrics from the implementation plan (SM-1 through SM-4) are confirmed
  or rejected with specific numbers.
- [x] The Regime C under-power softening approach is chosen with ≤ 5% error vs current at load 0.
- [x] A clear recommendation is documented for the implementation plan.

## Technical Context

**Related Components:**
- `SemiRealisticThrottleEngine.getBrakeDecimalPcnt(step, steps, maxBrake)` — the EngineDriver
  brake curve formula. Returns brakePcnt in range \[0.30, 1.0\] for steps 0–7 with maxBrake=0.70.
- `SemiRealisticThrottleEngine.getLoadPcnt(step, steps, maxLoadPcnt)` — the quadratic load
  formula. Returns multiplier in range \[1.0, 10.0\] for steps 0–5 with maxLoadPcnt=1000.
- `computeRampDelay()` — currently: `delay = baseDecelDelayMs × |targetAcceleration|` for decel.
- Regime B: `targetAcceleration = -effectiveBrake`
- Regime C decel under power: `targetAcceleration = -1 × (1 - effectiveBrake × 0.50)`

**Dependencies:** Implementation plan Phase 2+ depends on the formula chosen here.

**Constraints:**
- `MAX_BRAKE = 0.70` (full brake brakePcnt = 0.30)
- `MAX_BRAKE_UNDER_POWER = 0.50`
- `baseDecelDelayMs = 800` (default)
- `numberOfBrakeSteps = 7` (default)
- `numberOfLoadSteps = 5`, `maxLoadPcnt = 1000` (default: multiplier 1.0–10.0)
- Load multiplier values: {1.0, 1.36, 2.44, 4.24, 6.76, 10.0} for positions 0–5

## Research Findings

### Investigation Results

#### RT-1: Algebraic Derivation

**Derivation.** At load 0 the current model gives `delay = baseDecel × brakePcnt`. We need
`baseDecel / totalForce = baseDecel × brakePcnt`, so `totalForce = 1/brakePcnt`. With
`coastForce = 1.0` at load 0, we get:

```
brakeForce = totalForce - coastForce = (1/brakePcnt) - 1
```

**Verification identity** (must hold for every step):

```
totalForce = 1.0 + (1/brakePcnt) - 1 = 1/brakePcnt
delay      = baseDecel / (1/brakePcnt) = baseDecel × brakePcnt  ✓
```

Numerically verified for all 8 steps (0–7). Identity holds to floating-point precision
(< 0.0001 ms error). **RT-1 PASSED.**

#### RT-2: brakePcnt and brakeForce Values

Computed using `getBrakeDecimalPcnt(step, 7, 0.70)` from `SemiRealisticThrottleEngine`:

| Step | brakePcnt | effectiveBrake | brakeForce | 800×brakePcnt | 800/(1+brakeForce) |
|------|-----------|----------------|------------|---------------|---------------------|
| 0    | 1.000000  | 0.000000       | 0.000000   | 800.00        | 800.00              |
| 1    | 0.962204  | 0.037796       | 0.039281   | 769.76        | 769.76              |
| 2    | 0.893096  | 0.106904       | 0.119701   | 714.48        | 714.48              |
| 3    | 0.803604  | 0.196396       | 0.244394   | 642.88        | 642.88              |
| 4    | 0.697628  | 0.302372       | 0.433428   | 558.10        | 558.10              |
| 5    | 0.577423  | 0.422577       | 0.731833   | 461.94        | 461.94              |
| 6    | 0.444508  | 0.555492       | 1.249679   | 355.61        | 355.61              |
| 7    | 0.300000  | 0.700000       | 2.333333   | 240.00        | 240.00              |

Both columns match exactly for every row. **RT-2 PASSED.**

#### RT-3: Air Brake Delay Table (Load-Invariant Forces)

Load multipliers from `getLoadPcnt(step, 5, 1000)`:

| Load Pos | loadMultiplier |
|----------|----------------|
| 0        | 1.0000         |
| 1        | 1.3600         |
| 2        | 2.4400         |
| 3        | 4.2400         |
| 4        | 6.7600         |
| 5        | 10.0000        |

**New model** — `delay = 800 / (coastForce + airForce)` where `coastForce = 1/loadMult`,
`airForce = (1/brakePcnt) - 1` (constant across loads):

| Notch | brakePcnt | airForce | L0    | L1     | L2     | L3     | L4     | L5     |
|-------|-----------|----------|-------|--------|--------|--------|--------|--------|
| 0     | 1.000000  | 0.000   | 800   | 1088   | 1952   | 3392   | 5408   | 8000   |
| 1     | 0.962204  | 0.039   | 770   | 1033   | 1781   | 2908   | 4273   | 5744   |
| 2     | 0.893096  | 0.120   | 714   | 936    | 1511   | 2250   | 2989   | 3641   |
| 3     | 0.803604  | 0.244   | 643   | 817    | 1223   | 1666   | 2039   | 2323   |
| 4     | 0.697628  | 0.433   | 558   | 685    | 949    | 1195   | 1376   | 1500   |
| 5     | 0.577423  | 0.732   | 462   | 545    | 701    | 827    | 909    | 962    |
| 6     | 0.444508  | 1.250   | 356   | 403    | 482    | 539    | 572    | 593    |
| 7     | 0.300000  | 2.333   | 240   | 261    | 292    | 311    | 322    | 329    |

**Current model** — `delay = 800 × brakePcnt × loadMultiplier`:

| Notch | brakePcnt | L0    | L1     | L2     | L3     | L4     | L5     |
|-------|-----------|-------|--------|--------|--------|--------|--------|
| 0     | 1.000000  | 800   | 1088   | 1952   | 3392   | 5408   | 8000   |
| 1     | 0.962204  | 770   | 1047   | 1878   | 3264   | 5204   | 7698   |
| 2     | 0.893096  | 714   | 972    | 1743   | 3029   | 4830   | 7145   |
| 3     | 0.803604  | 643   | 874    | 1569   | 2726   | 4346   | 6429   |
| 4     | 0.697628  | 558   | 759    | 1362   | 2366   | 3773   | 5581   |
| 5     | 0.577423  | 462   | 628    | 1127   | 1959   | 3123   | 4619   |
| 6     | 0.444508  | 356   | 484    | 868    | 1508   | 2404   | 3556   |
| 7     | 0.300000  | 240   | 326    | 586    | 1018   | 1622   | 2400   |

**SM checks (new model):**

- **SM-4** ✅ Load 0, full brake = 240.0 ms (target ~240, 0% error)
- **SM-1** ✅ Load 5, full brake = 328.8 ms, ratio = 1.37× (≤ 3× required)
- **SM-3** ✅ Coast→notch 1 ratios all ≤ 2×:

  | Load | Coast (ms) | Notch 1 (ms) | Ratio |
  |------|------------|--------------|-------|
  | 0    | 800        | 770          | 1.04  |
  | 1    | 1088       | 1033         | 1.05  |
  | 2    | 1952       | 1781         | 1.10  |
  | 3    | 3392       | 2908         | 1.17  |
  | 4    | 5408       | 4273         | 1.27  |
  | 5    | 8000       | 5744         | 1.39  |

**Key observation:** The new model collapses the delay range under load. At load 5 the current
model gives 2400 ms at full brake; the new model gives 329 ms. Air brakes are **load-invariant**
by design — the air force is a constant that dominates coastForce at high loads, producing a
nearly flat delay curve. This is the desired railroad behavior.

**RT-3 PASSED.**

#### RT-4: Independent Brake Delay Table (Load-Degraded Forces)

**New model** — `delay = 800 / (coastForce + indepForce)` where
`indepForce = ((1/brakePcnt) - 1) / loadMultiplier`:

| Notch | brakePcnt | rawForce | L0    | L1     | L2     | L3     | L4     | L5     |
|-------|-----------|----------|-------|--------|--------|--------|--------|--------|
| 0     | 1.000000  | 0.000    | 800   | 1088   | 1952   | 3392   | 5408   | 8000   |
| 1     | 0.962204  | 0.039    | 770   | 1047   | 1878   | 3264   | 5204   | 7698   |
| 2     | 0.893096  | 0.120    | 714   | 972    | 1743   | 3029   | 4830   | 7145   |
| 3     | 0.803604  | 0.244    | 643   | 874    | 1569   | 2726   | 4346   | 6429   |
| 4     | 0.697628  | 0.433    | 558   | 759    | 1362   | 2366   | 3773   | 5581   |
| 5     | 0.577423  | 0.732    | 462   | 628    | 1127   | 1959   | 3123   | 4619   |
| 6     | 0.444508  | 1.250    | 356   | 484    | 868    | 1508   | 2404   | 3556   |
| 7     | 0.300000  | 2.333    | 240   | 326    | 586    | 1018   | 1622   | 2400   |

**SM checks:**

- ✅ Load 0 matches air brake table exactly (at load 0, dividing by loadMult=1.0 is a no-op)
- **SM-2** ✅ Load 5, full brake = 2400.0 ms, ratio = 10.0× (target ~10× achieved exactly)

**Key observation:** Independent brake delays match the current model's `delay = 800 × brakePcnt
× loadMultiplier` exactly. This is because `coastForce + indepForce = (1 + brakeForce) /
loadMult = (1/brakePcnt) / loadMult`, and `800 / ((1/brakePcnt)/loadMult) = 800 × brakePcnt ×
loadMult`. The independent brake reproduces the current multiplicative load scaling perfectly.

**RT-4 PASSED.**

#### RT-5: Mixed Braking (Full Air + Full Indep)

| Load | loadMult | Air Only | Indep Only | Mixed  | Mixed < min? |
|------|----------|----------|------------|--------|--------------|
| 0    | 1.0000   | 240.0    | 240.0      | 141.2  | ✅ YES       |
| 1    | 1.3600   | 260.7    | 326.4      | 167.2  | ✅ YES       |
| 2    | 2.4400   | 291.6    | 585.6      | 216.2  | ✅ YES       |
| 3    | 4.2400   | 311.4    | 1017.6     | 256.5  | ✅ YES       |
| 4    | 6.7600   | 322.4    | 1622.4     | 283.0  | ✅ YES       |
| 5    | 10.0000  | 328.8    | 2400.0     | 300.0  | ✅ YES       |

Mixed braking delay is strictly less than either source alone at every load level. The additive
model naturally produces the correct behavior: multiple brake sources combine to give shorter
delays (faster deceleration). **RT-5 PASSED.**

#### RT-6: Regime C Under-Power Analysis

The current Regime C formula is:
`targetAcceleration = -1.0 × (1.0 - effectiveBrake × MAX_BRAKE_UNDER_POWER)`
where `effectiveBrake = brakePcnt` and `MAX_BRAKE_UNDER_POWER = 0.50`.
Delay: `800 × (1.0 - brakePcnt × 0.50)`.

**Candidate A** — simple force scaling: `brakeForceUnderPower = brakeForce × (0.50/0.70)`:

| Notch | brakePcnt | Current Delay | Cand A Delay | Error   |
|-------|-----------|---------------|--------------|---------|
| 0     | 1.000000  | 400.0         | 800.0        | 100.0%  |
| 1     | 0.962204  | 415.1         | 778.2        | 87.5%   |
| 2     | 0.893096  | 442.8         | 737.0        | 66.5%   |
| 3     | 0.803604  | 478.6         | 681.1        | 42.3%   |
| 4     | 0.697628  | 521.0         | 610.9        | 17.3%   |
| 5     | 0.577423  | 569.0         | 525.4        | 7.7%    |
| 6     | 0.444508  | 622.2         | 422.7        | 32.1%   |
| 7     | 0.300000  | 680.0         | 300.0        | 55.9%   |

❌ **Candidate A REJECTED.** Errors range from 7.7% to 100%. Far exceeds the 5% threshold.

**Candidate B** — exact-match formula derived algebraically:
`underPowerForce = (brakePcnt × 0.50) / (1.0 - brakePcnt × 0.50)`:

| Notch | brakePcnt | Current Delay | Cand B Delay | Error |
|-------|-----------|---------------|--------------|-------|
| 0     | 1.000000  | 400.0         | 400.0        | 0.0%  |
| 1     | 0.962204  | 415.1         | 415.1        | 0.0%  |
| 2     | 0.893096  | 442.8         | 442.8        | 0.0%  |
| 3     | 0.803604  | 478.6         | 478.6        | 0.0%  |
| 4     | 0.697628  | 521.0         | 521.0        | 0.0%  |
| 5     | 0.577423  | 569.0         | 569.0        | 0.0%  |
| 6     | 0.444508  | 622.2         | 622.2        | 0.0%  |
| 7     | 0.300000  | 680.0         | 680.0        | 0.0%  |

✅ **Candidate B ACCEPTED.** 0% error by construction. Derivation:

```
We need: 800 / (1 + f) = 800 × (1 - eb × 0.50)
    ⟹  1 + f = 1 / (1 - eb × 0.50)
    ⟹  f = (eb × 0.50) / (1 - eb × 0.50)
```

This is NOT a simple scaling of the Regime B brakeForce — it is a structurally different
formula. The current Regime C model is fundamentally multiplicative (not additive), so mapping
it into an additive force requires this exact-match transform.

**Implementation note:** In the force model, Regime C under-power can use this formula as
`underPowerBrakeForce(brakePcnt)` at load 0. For loads > 0, the same load-scaling rules (air
vs independent) apply on top.

**RT-6 PASSED.**

#### RT-7: NEUTRAL + Brakes Behavioral Change

**Current model:** In NEUTRAL, `targetAcceleration = -1.0` (constant), brakes are ignored.
Delay = `800 × 1.0 × loadMultiplier`.

**New model:** Brakes contribute forces even in NEUTRAL.
Delay = `800 / (coastForce + airForce)`.

With full air brake (notch 7, airForce = 2.333):

| Load | loadMult | Current Delay | New Delay | Change  |
|------|----------|---------------|-----------|---------|
| 0    | 1.0000   | 800           | 240       | −70.0%  |
| 1    | 1.3600   | 1088          | 261       | −76.0%  |
| 2    | 2.4400   | 1952          | 292       | −85.1%  |
| 3    | 4.2400   | 3392          | 311       | −90.8%  |
| 4    | 6.7600   | 5408          | 322       | −94.0%  |
| 5    | 10.0000  | 8000          | 329       | −95.9%  |

Without brakes in NEUTRAL (coast only), both models give identical results:
`delay = 800 × loadMult`.

**Behavioral change:** This is an **intentional improvement**. The current model's behavior of
ignoring brakes in NEUTRAL is a known limitation identified in the epic (Journey 6). The force
model naturally resolves it — applying brakes in NEUTRAL produces faster deceleration, which is
the expected railroad behavior (engineer puts reverser in NEUTRAL and applies emergency brakes).

**RT-7 PASSED.**

### Key Formula Reference

**Current model (Regime B):**
```
delay = baseDecelDelayMs × effectiveBrake
     = baseDecelDelayMs × brakePcnt     (at load 0, no global scaling)
```

**Candidate additive model:**
```
brakeForce(brakePcnt) = (1 / brakePcnt) - 1       when brakePcnt < 1.0
                      = 0                           when brakePcnt >= 1.0

coastForce(loadMult)  = 1.0 / loadMultiplier

airForce              = brakeForce(airBrakePcnt)                        (load-invariant)
indepForce            = brakeForce(indepBrakePcnt) / loadMultiplier     (load-degraded)
dynForce              = brakeForce(dynBrakePcnt) / loadMultiplier       (load-degraded)

totalForce            = coastForce + airForce + indepForce + dynForce
delay                 = baseDecelDelayMs / totalForce
```

**Verification identity (must hold at load 0):**
```
coastForce = 1.0
brakeForce = (1/brakePcnt) - 1
totalForce = 1.0 + (1/brakePcnt) - 1 = 1/brakePcnt
delay      = baseDecel / (1/brakePcnt) = baseDecel × brakePcnt  ✓ (matches current)
```

### Prototype/Testing Notes

All values computed using Java with the exact `getBrakeDecimalPcnt` and `getLoadPcnt` formulas
from `SemiRealisticThrottleEngine.java`. Source: `SpikeCompute.java` (single-file Java program
using the exact same math as the engine).

#### Summary of Constants

```
MAX_BRAKE              = 0.70
MAX_BRAKE_UNDER_POWER  = 0.50
baseDecelDelayMs       = 800
numberOfBrakeSteps     = 7
numberOfLoadSteps      = 5
maxLoadPcnt            = 1000  (multiplier range 1.0–10.0)
```

#### Load Multiplier Reference

```
Load 0: 1.0000    Load 1: 1.3600    Load 2: 2.4400
Load 3: 4.2400    Load 4: 6.7600    Load 5: 10.0000
```

#### brakeForce Reference (all 8 steps)

```
Step 0: brakeForce = 0.000000  (brakePcnt = 1.000000, no braking)
Step 1: brakeForce = 0.039281  (brakePcnt = 0.962204)
Step 2: brakeForce = 0.119701  (brakePcnt = 0.893096)
Step 3: brakeForce = 0.244394  (brakePcnt = 0.803604)
Step 4: brakeForce = 0.433428  (brakePcnt = 0.697628)
Step 5: brakeForce = 0.731833  (brakePcnt = 0.577423)
Step 6: brakeForce = 1.249679  (brakePcnt = 0.444508)
Step 7: brakeForce = 2.333333  (brakePcnt = 0.300000, full braking)
```

#### Success Metrics Summary

| Metric | Criterion | Measured | Status |
|--------|-----------|----------|--------|
| SM-1   | Air brake load-5 delay ≤ 3× load-0 | 328.8 / 240.0 = 1.37× | ✅ PASS |
| SM-2   | Indep brake load-5 delay ≈ 10× load-0 | 2400.0 / 240.0 = 10.0× | ✅ PASS |
| SM-3   | Coast→notch 1 transition ≤ 2× all loads | max 1.39× (at load 5) | ✅ PASS |
| SM-4   | Load 0 full brake ≈ 240 ms (within 5%) | 240.00 ms (0% error) | ✅ PASS |

#### Regime C Under-Power: Chosen Formula

```java
// Candidate B (exact match, 0% error):
underPowerForce = (brakePcnt * MAX_BRAKE_UNDER_POWER)
               / (1.0 - brakePcnt * MAX_BRAKE_UNDER_POWER);
```

Candidate A (simple scaling by 0.50/0.70) was rejected with errors up to 100%.

#### Unit Test Expected Values

Key values for unit tests (round to the precision shown):

```
// Regime B, load 0 (must match current model exactly):
assertDelay(notch=0, load=0, expected=800.0);   // coast
assertDelay(notch=4, load=0, expected=558.1);   // mid brake
assertDelay(notch=7, load=0, expected=240.0);   // full brake

// Air brake, high load (SM-1):
assertDelay(airNotch=7, load=5, expected=328.8); // ≤ 720

// Independent brake, high load (SM-2):
assertDelay(indepNotch=7, load=5, expected=2400.0); // ≈ 10×

// Mixed braking (SM-5):
assertDelay(air=7, indep=7, load=0, expected=141.2); // < 240
assertDelay(air=7, indep=7, load=5, expected=300.0); // < 328.8

// Coast→notch 1 transition (SM-3):
assertDelay(notch=1, load=5, expected=5743.8);  // ratio 1.39
```

### External Resources

- [EngineDriver throttle_semi_realistic.java](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java)
- [Implementation plan](./feature-additive-force-brake-model-1.md)
- [Epic PRD](./additive-force-brake-model-epic.md)

## Decision

### Recommendation

**Proceed with the additive force model using the validated formulas.** The force mapping is
confirmed correct across all brake notches, load positions, and operating regimes. Implement
as specified in the implementation plan Phases 2–4.

**Formulas to implement:**

```java
// Core force mapping (Regime B — no throttle power)
brakeForce(brakePcnt) = (brakePcnt < 1.0) ? (1.0 / brakePcnt) - 1.0 : 0.0;
coastForce(loadMult)  = 1.0 / loadMultiplier;

// Per-source force scaling
airForce    = brakeForce(airBrakePcnt);                       // load-invariant
indepForce  = brakeForce(indepBrakePcnt) / loadMultiplier;    // load-degraded
dynForce    = brakeForce(dynBrakePcnt)   / loadMultiplier;    // load-degraded

// Total force and delay
totalForce  = coastForce + airForce + indepForce + dynForce;
delay       = (int) Math.round(baseDecelDelayMs / totalForce);

// Regime C under-power (exact-match formula)
underPowerForce(brakePcnt) = (brakePcnt * MAX_BRAKE_UNDER_POWER)
                           / (1.0 - brakePcnt * MAX_BRAKE_UNDER_POWER);
```

### Rationale

1. **Exact load-0 match (RT-1, RT-2).** The mapping `brakeForce = (1/brakePcnt) - 1` is
   algebraically derived from the identity `baseDecel / (1 + brakeForce) = baseDecel ×
   brakePcnt`. It produces 0% error at load 0 for all 8 brake steps. No approximation is
   involved — this is an exact equivalence.

2. **All four success metrics pass (RT-3, RT-4, RT-5):**
   - SM-1: Air brake at load 5 is 1.37× load-0 (well within 3× limit).
   - SM-2: Independent brake at load 5 is exactly 10.0× load-0.
   - SM-3: Coast→notch 1 transition is at most 1.39× (well within 2× limit).
   - SM-4: Full brake at load 0 is exactly 240 ms.

3. **Independent brake reproduces current model exactly (RT-4).** By dividing brakeForce by
   loadMultiplier, the independent brake delay equals `800 × brakePcnt × loadMult` — identical
   to the current multiplicative model. This means independent brakes degrade with load exactly
   as the current system does, preserving backward compatibility.

4. **Mixed braking works correctly (RT-5).** Additive forces naturally produce shorter delays
   when multiple brake sources are combined, at every load level.

5. **Regime C under-power requires Candidate B, not simple scaling (RT-6).** The current
   Regime C formula `1 - brakePcnt × 0.50` is fundamentally multiplicative, not a scaled
   version of the Regime B formula. Candidate A (scaling brakeForce by 0.50/0.70) produces
   errors up to 100%. Candidate B (exact-match transform) achieves 0% error. Use Candidate B.

6. **NEUTRAL + brakes is an intentional improvement (RT-7).** The force model lets brakes
   function in NEUTRAL, resolving a known limitation. Coast-only NEUTRAL behavior is unchanged.

### Implementation Notes

1. **Regime C formula is structurally different.** Do not attempt to reuse the Regime B
   `brakeForce = (1/brakePcnt) - 1` formula with a scaling factor for Regime C. Use the
   dedicated `underPowerForce` formula. This is the single most important finding — Candidate A
   was a plausible but incorrect shortcut.

2. **Independent brake at load 0 is identical to air brake.** At `loadMultiplier = 1.0`,
   dividing by loadMult is a no-op, so `indepForce = brakeForce` and both sources produce the
   same delay. The differentiation only manifests at load > 0.

3. **NEUTRAL behavioral change needs documentation.** The change from "brakes ignored in
   NEUTRAL" to "brakes active in NEUTRAL" should be noted in release notes or user-facing docs.
   It is the correct railroad behavior but differs from the current model.

4. **Guard against brakePcnt = 0.** The formula `(1/brakePcnt) - 1` diverges as brakePcnt → 0.
   In practice `getBrakeDecimalPcnt` returns 0.30 at maximum braking (step 7, MAX_BRAKE = 0.70),
   so this cannot occur with valid inputs. However, add a defensive guard:
   `if (brakePcnt <= 0.0) brakePcnt = 0.01;` or clamp brakeForce to a maximum.

5. **Load multiplier values are quadratic.** The values {1.0, 1.36, 2.44, 4.24, 6.76, 10.0}
   are the exact outputs of `getLoadPcnt(0..5, 5, 1000)`. Unit tests should use these exact
   values.

### Follow-up Actions

- [x] Update implementation plan TASK-001 through TASK-004 with the validated formula
- [x] Update implementation plan TASK-005 with exact `computeDecelerationDelay` signature
- [x] Update implementation plan TASK-015 with exact force computation code
- [ ] Create/update unit test expected values based on the numeric tables

## Status History

| Date       | Status         | Notes                                          |
|------------|----------------|------------------------------------------------|
| 2025-05-08 | 🔴 Not Started | Spike created and scoped from implementation plan Phase 1 |
| 2025-07-15 | 🟢 Complete    | All 8 RTs executed. Formula validated. Candidate B chosen for Regime C. All SM criteria pass. |

---

_Last updated: 2025-07-15 by thedave42_
