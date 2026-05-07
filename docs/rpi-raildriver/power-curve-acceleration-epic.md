# Epic: Power Curve Acceleration for Semi-Realistic Throttle Engine

## 1. Epic Name

**Power Curve Acceleration (Logarithmic Delay Ramp)**

## 2. Goal

### Problem

The semi-realistic throttle engine currently accelerates at a constant rate — every DCC speed step takes the same 300ms interval regardless of where the train is in its acceleration. In reality, when an engineer opens the throttle on a locomotive the tractive effort produces rapid initial acceleration that tapers off as the train gains speed and rolling resistance increases. The current linear ramp feels artificial and "robotic" to operators using the RailDriver hardware controller; pushing the throttle lever to 25% produces a metronomic, evenly-paced speed increase that does not simulate the feel of real motive power.

Additionally, the current engine does not distinguish between small and large throttle inputs in terms of acceleration urgency. Pushing the throttle to 50% should produce a noticeably more aggressive initial acceleration than pushing to 25%, because more power is being applied — but today both ramp at the same per-step delay.

### Solution

Introduce a **logarithmic delay curve** to the acceleration ramp. When the throttle lever moves to a new higher position, the inter-step delay starts near the minimum DCC emit interval (~50ms) and increases toward the configured base acceleration delay (~300ms) as the current speed approaches the target, following the formula `delay = minDelay + (maxDelay - minDelay) × (e^(progress×k) - 1) / (e^k - 1)`. A single configurable parameter `k` (range 0.1–1.0) controls the curve shape.

### Impact

- **Realism:** Operators experience a power-delivery feel that closely mimics real locomotive acceleration characteristics — fast initial pull followed by a gradual settling toward cruising speed.
- **Non-disruptive:** The power curve integrates naturally into the existing semi-realistic throttle engine.

## 3. User Personas

### RailDriver Operator

A model railroad enthusiast using a RailDriver Modern Desktop USB controller connected to JMRI. They value realistic train handling and expect the physical controls to produce behavior that mirrors real locomotive operation. They have configured the semi-realistic throttle engine and want the acceleration to feel like real motive power rather than a linear speed ramp.

## 4. High-Level User Journeys

### Journey 1: First-Time Power Curve Setup

1. Operator opens **Debug → RailDriver Settings** (or equivalent settings UI).
2. Operator navigates to the Semi-Realistic Engine settings tab.
3. Operator sees a new **Power Curve** section with a **Curve Steepness** spinner (range 0.1–1.0).
4. Operator adjusts the curve steepness to taste and saves settings.
5. When the operator pushes the throttle lever forward, the train accelerates quickly at first, then smoothly settles toward the target speed — the feel is noticeably more realistic than the previous linear ramp.

### Journey 2: Mid-Ramp Throttle Adjustment

1. Operator is accelerating toward 40% with the power curve active.
2. At ~20% current speed, the operator pushes the lever further to 60%.
3. The ramp resets — a new power curve begins from the current 20% position toward 60%, with a fresh fast-start burst proportional to the new 40-step displacement.
4. The transition feels natural: the train surges as more power is applied.

### Journey 4: Power Curve Under Load

1. Operator has configured a heavy consist (load slider at position 3 of 5).
2. Operator pushes throttle to 40%.
3. The power curve still starts near `minEmitIntervalMs`, but the delay ceiling is higher (300ms × load multiplier), so the logarithmic curve stretches over a wider delay range.
4. The initial burst is still present but the overall acceleration is slower due to load — matching the feel of a heavy train that responds to throttle but takes longer to reach speed.

### Journey 5: Tuning the Curve Feel

1. Operator finds the default curve too aggressive (train seems to "jump" at the start).
2. Operator opens settings, reduces **Curve Steepness** from 1.0 to 0.3.
3. The curve becomes more gradual — the delay rises more evenly across the ramp rather than staying pinned at the minimum for most of it.

## 5. Business Requirements

### Functional Requirements

- **FR-1:** The engine shall track the speed step at which the current acceleration ramp began (`rampStartStep`), reset each time `recomputeTarget()` initiates a new ramp.
- **FR-2:** When the engine is accelerating (currentSpeedStep < targetSpeedStep), the inter-step delay shall be computed as an interpolation between `minEmitIntervalMs` (floor) and `baseAccelDelayMs` (ceiling), based on ramp progress (0.0 at ramp start → 1.0 at target), shaped by the exponential curve formula.
- **FR-3:** The delay curve shall use the formula: `delay = minDelay + (maxDelay - minDelay) × (e^(progress × k) - 1) / (e^k - 1)`, where `k` is the configurable curve steepness (range 0.1–1.0).
- **FR-4:** The power curve shall apply only to acceleration. Deceleration (coast-down, braking) shall continue to use the existing constant-delay behavior.
- **FR-7:** Existing load multiplier and brake modifier effects on `targetAcceleration` shall continue to function, modifying the delay ceiling that the logarithmic curve approaches.
- **FR-8:** When the throttle lever is moved to a new position during an active ramp, `rampStartStep` shall be reset to the current speed step and a new power curve shall begin from that point.
- **FR-9:** The speed step increment shall remain at 1 per tick (preserving smooth DCC speed granularity) — the power curve affects only the timing between steps.
- **FR-10:** One new setting shall be added to `SemiRealisticSettings`:
  - `powerCurveSteepness` (double, valid range 0.1–1.0)
- **FR-11:** The new settings shall be persisted to and loaded from the RailDriver calibration XML using the existing `SemiRealisticSettings` XML store/load pattern.
- **FR-12:** The settings UI shall expose the power curve controls in the semi-realistic engine settings tab, matching the existing control style (labeled spinners with tooltips).

### Non-Functional Requirements

- **NFR-1: Threading compliance.** All new logic shall execute on the JMRI layout thread. No new threads, executors, or timers shall be introduced. Timed events shall use `ThreadingUtil.runOnLayoutDelayed()` exclusively.
- **NFR-2: Performance.** The exponential computation in `computeRampDelay()` shall add negligible overhead — `Math.expm1()` is called once per ramp tick (every 50–300ms), which is trivially cheap.
- **NFR-3: Non-interference.** The power curve shall not change the behavior of any other engine feature (braking, load, air line, dynamic brake, bail-off, direction interlocking).
- **NFR-4: Testability.** The logarithmic delay computation shall be extractable as a pure static method for unit testing with known inputs and expected outputs.
- **NFR-5: Epoch safety.** The `rampStartStep` shall be invalidated (or ignored) when the ramp epoch changes, preventing stale progress calculations from leaking across ramp boundaries.

## 6. Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Operator-perceived realism | Subjective improvement over linear ramp | Operator feedback during testing |
| Logarithmic acceleration feel | Speed increases visibly faster at ramp start than end | Operator feedback during testing |
| No regression in existing behavior | All existing unit tests pass | CI / `ant headlesstest` |
| Settings persist correctly | Power curve settings round-trip through XML save/load | Unit test: save → load → compare |
| Load interaction correctness | Power curve delay ceiling scales with load multiplier | Manual test at load positions 0, 3, 5 |

## 7. Out of Scope

- **Deceleration power curve.** This epic covers acceleration only. Applying a logarithmic curve to coast-down or braking is a separate future enhancement.
- **Non-linear lever-to-target mapping.** The throttle lever position continues to map linearly to the target speed step. A separate "throttle response curve" feature (remapping the lever input itself) is out of scope.
- **Variable step size (Approach B/C).** The step increment remains at 1 per tick. Combining delay curves with proportional step sizing is a potential future enhancement if Approach A alone does not produce sufficient initial punch.
- **Speed profile integration.** The power curve operates independently of any DCC decoder speed tables or JMRI speed profiles. Interaction with decoder momentum CVs is the user's responsibility.
- **New UI panels or windows.** The settings are added to the existing semi-realistic engine settings tab — no new frames or panels are created.

## 8. Business Value

**High**

The semi-realistic throttle engine is the core differentiator for realistic RailDriver operation in JMRI. The linear acceleration ramp is the single most frequently noticed unrealistic behavior — every throttle movement exposes it. A logarithmic power curve directly addresses this and transforms the feel from "computer-controlled" to "locomotive-like" with minimal implementation risk (opt-in, isolated to one method, all existing tests unaffected).

## 9. User Stories

### Story 1: Logarithmic Acceleration Delay

**As a** RailDriver operator,
**I want** the DCC speed to increase quickly when I first push the throttle and then gradually slow its rate of increase as it nears my target speed,
**so that** acceleration feels like real motive power being applied to a train rather than a constant-rate computer ramp.

**Acceptance Criteria:**
- When the throttle lever moves to a higher position, the first inter-step delay is near `minEmitIntervalMs` (~50ms).
- The inter-step delay increases logarithmically as `currentSpeedStep` approaches `targetSpeedStep`.
- The final inter-step delays near the target are approximately equal to `baseAccelDelayMs` (modified by load/brake as today).
- The speed step increment remains at 1 per tick throughout.

### Story 2: Mid-Ramp Re-Targeting

**As a** RailDriver operator,
**I want** the power curve to reset naturally when I adjust the throttle during an active acceleration ramp,
**so that** pushing the lever further produces a fresh burst of acceleration from my current speed.

**Acceptance Criteria:**
- When the throttle lever moves to a new position during an active ramp, `rampStartStep` resets to the current speed step.
- A new logarithmic curve begins from the current position to the new target.

### Story 3: Power Curve Settings Persistence

**As a** RailDriver operator,
**I want** my power curve steepness setting to be saved and restored with my RailDriver calibration,
**so that** I don't have to reconfigure them every session.

**Acceptance Criteria:**
- `powerCurveSteepness` is stored in the calibration XML.
- The setting round-trips correctly through save and load.

### Story 4: Power Curve Settings UI

**As a** RailDriver operator,
**I want** controls in the semi-realistic engine settings tab to enable and tune the power curve,
**so that** I can adjust the feel to my preference without editing XML.

**Acceptance Criteria:**
- A **Curve Steepness** spinner (range 0.1–1.0) appears in the settings tab.
- A tooltip explains what the setting does in plain language.
- The controls follow the existing dirty-tracking and save/reset pattern of the settings tab.

### Story 5: Load Interaction with Power Curve

**As a** RailDriver operator running a heavy consist,
**I want** the power curve to respect the load multiplier so that heavy trains still accelerate slowly overall but retain the fast-start feel,
**so that** the power curve and load simulation work together realistically.

**Acceptance Criteria:**
- The delay ceiling used by the logarithmic curve is `baseAccelDelayMs × loadMultiplier` (not just `baseAccelDelayMs`).
- At high load, the initial burst is still present (delay starts near `minEmitIntervalMs`) but the curve stretches over a wider delay range, producing slower overall acceleration.
- At zero load (light engine), the power curve operates with the unmodified `baseAccelDelayMs` ceiling.
