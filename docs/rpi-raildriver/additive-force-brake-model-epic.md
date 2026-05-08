# Epic: Additive Force Brake Model for Semi-Realistic Throttle

## 1. Epic Name

**Additive Force Brake Model — Per-Source Load Scaling for RailDriver Semi-Realistic Throttle**

## 2. Goal

### Problem

The RailDriver semi-realistic throttle engine models the effect of train weight (load slider) on braking by applying a single global multiplier to the inter-step delay. This multiplier scales all deceleration equally — coast, air brakes, independent brake, and dynamic brake — regardless of the physical characteristics of each braking source.

On a real train, air brakes apply force at every car in the consist. A heavier train has proportionally more braking force from the air system, so the operator experiences roughly the same deceleration rate when using air brakes regardless of train weight. In contrast, the independent (locomotive-only) brake applies a fixed force that becomes less effective as train weight increases. The current implementation makes air brakes feel nearly useless at high load settings (10× slower stopping time), which does not match the operator experience the semi-realistic mode is designed to simulate.

The global multiplier was inherited from EngineDriver's model, which treats all brake sources identically. Our code added per-source brake force scaling on top of the global multiplier, creating a double-counting effect for loco-only brakes and contradicting the air brake exemption.

### Solution

Replace the single-scalar delay model (`delay = baseDelay × effectiveBrake × loadMultiplier`) with an additive force model where each deceleration source (rolling resistance, air brake, independent brake, dynamic brake) contributes its own force with its own load behavior. Forces combine additively, and the delay is derived from the inverse of total force. This naturally produces smooth transitions between coast and braking, correct per-source load behavior, and no discontinuities at brake onset.

### Impact

- Air brakes feel appropriately powerful at all load settings, matching the operator's expectation of distributed train braking
- Independent and dynamic brakes correctly degrade with load, reflecting fixed loco-only force against increasing mass
- Coasting remains load-dependent (heavy train has more inertia), with smooth transition into braking
- The reverser NEUTRAL position correctly allows brakes to still function (current code ignores brakes in NEUTRAL)
- Mixed braking (multiple sources simultaneously) produces physically intuitive additive results

## 3. User Personas

### Operator (Primary)

A model railroad enthusiast using a RailDriver Desktop controller with the JMRI semi-realistic throttle mode. They expect brake and throttle behavior that approximates the feel of operating a real locomotive, within the constraints of a step-rate speed model. They use the load slider to simulate different consist weights (light engine through heavy freight) and expect braking behavior to change proportionally — air brakes should always feel authoritative, while the independent brake should feel increasingly weak on heavier trains.

The operator interacts with four deceleration-related controls:

- **Throttle lever**: Sets target speed. At zero, the train coasts or brakes to a stop.
- **Independent brake lever**: Applies loco-only braking. Continuous range, quantized to notches internally.
- **Auto brake (air) lever**: Commands the train air brake system. Application is instant; release is gradual (Westinghouse model with line and reservoir dynamics).
- **Dynamic brake lever**: Applies electrical braking through the traction motors. Tapers to zero at low speeds.
- **Load slider**: Sets simulated train weight. Position 0 = light engine, position 5 (default max) = heavy freight.
- **Reverser**: Forward / Neutral / Reverse. NEUTRAL centers the reverser; the locomotive should coast (and brakes should still function).
- **Bail-off**: Momentary button that releases loco-side braking contribution.

## 4. High-Level User Journeys

### Journey 1: Heavy freight air brake stop

The operator is running a heavy freight consist (load slider at position 4-5) at moderate speed. They close the throttle and apply the auto brake to make a controlled stop.

**Current behavior**: The train barely responds to air brakes. Full air brake application at max load takes ~5 minutes to stop from full speed — slower than coasting at no load. The operator must use emergency procedures or accept unrealistically long stopping times.

**Expected behavior**: The train decelerates firmly under air brakes regardless of load setting. The stopping time at max load should be modestly longer than at no load (reflecting the slightly lower contribution of rolling resistance in the total force budget), but air brakes remain the dominant stopping force. The transition from coast to braked deceleration should be smooth — no sudden jumps when the auto brake lever first engages.

### Journey 2: Independent brake on heavy train

The operator is running heavy freight and applies only the independent brake (no air) to slow down.

**Current behavior**: The independent brake at max load is slower than unloaded coasting (7440ms/step vs 800ms/step). The brake makes things worse, not better.

**Expected behavior**: The independent brake provides a modest improvement over coast at max load. At light engine (load 0), the independent brake is effective. At max load, it provides noticeably less deceleration — the operator can feel that the loco-only brake is inadequate for a heavy train and should use air brakes instead. This teaches correct operating practice: independent brake for light moves, air brakes for train stops.

### Journey 3: Coast to stop at different loads

The operator closes the throttle with no brakes applied and lets the train coast.

**Current behavior**: A heavy train coasts very slowly to a stop (up to 10× longer than light engine). This gives the sensation of inertia.

**Expected behavior**: Same — a heavy train should coast noticeably longer than a light engine. The load slider's effect on coasting is the primary way the operator "feels" the train's weight. This behavior should be preserved.

### Journey 4: Transition from coast to braking

The operator is coasting (no brakes) and begins to apply the auto brake gradually.

**Current behavior**: At high loads, the first notch of air brake causes an abrupt change in deceleration rate (from very slow coast to much faster braked deceleration) — a 10× discontinuity at max load.

**Expected behavior**: The transition from coast to braked deceleration is smooth and proportional. Each additional notch of air brake produces a progressive increase in deceleration. There is no jarring step at the boundary between "no brake" and "first notch."

### Journey 5: Mixed braking

The operator applies both the independent brake and the auto brake simultaneously.

**Current behavior**: Only the strongest brake source affects deceleration (min-of-all-sources model). The weaker brake adds nothing.

**Expected behavior**: Both brake sources contribute to total deceleration. Air brakes provide the bulk of the stopping force; the independent brake adds a small additional contribution. The combined stop is slightly shorter than air alone. This matches real practice where both brakes are sometimes used together.

### Journey 6: Reverser in NEUTRAL with brakes

The operator places the reverser in NEUTRAL (common during switching or when stopping) and applies brakes.

**Current behavior**: NEUTRAL short-circuits to a fixed coast rate. Brake lever position has no effect on deceleration — the train coasts at the same rate whether brakes are applied or not.

**Expected behavior**: Brakes function normally in NEUTRAL. The reverser position affects the throttle (no tractive effort in NEUTRAL) but not the brakes. The operator can stop the train with brakes while in NEUTRAL, matching real locomotive behavior.

### Journey 7: Brake under power (Regime C)

The operator has the throttle partially open and applies brakes to reduce speed without fully closing the throttle.

**Current behavior**: Braking under power uses a softened brake effectiveness (`MAX_BRAKE_UNDER_POWER`) to simulate the throttle fighting the brakes.

**Expected behavior**: Same softening behavior is preserved. When both throttle and brakes are active, braking force is reduced to reflect the opposing forces. The per-source load behavior still applies: air brakes remain effective under power at high loads, while the independent brake contributes less.

### Journey 8: No-load operation

The operator is running with the load slider at position 0 (light engine).

**Current behavior**: All brakes are fully effective. Coast, air brake, independent brake, and dynamic brake all respond at their base rates.

**Expected behavior**: Same — at zero load, braking behavior should be identical to (or very close to) the current behavior. The additive force model should produce the same delay values at load 0 as the current model. No regression in the light-engine experience.

### Journey 9: Dynamic brake at low speed

The operator applies dynamic brake while at low speed (below the `dynBrakeMinSpeedStep` threshold).

**Current behavior**: Dynamic brake effect tapers linearly to zero at standstill, preventing the dynamic brake from holding the train at a stop.

**Expected behavior**: Same taper behavior. The dynamic brake force in the additive model should reflect the low-speed taper, reaching zero at standstill.

### Journey 10: Bail-off

The operator presses the bail-off button to release loco-side braking.

**Current behavior**: Bail-off zeros all brake sources (independent, dynamic, and the loco share of air) before computing brake percentages. At light engine, bail-off releases all braking entirely.

**Expected behavior**: Same — bail-off releases loco-side braking contribution. The current bail-off implementation zeros the effective brake steps before the brake percentage computation, which means all brake forces become zero in the additive model. This behavior is preserved.

## 5. Business Requirements

### Functional Requirements

- **FR-1**: Each deceleration source (rolling resistance / coast, air brake, independent brake, dynamic brake) shall have its own load-scaling behavior applied independently before the sources are combined.
- **FR-2**: Air brake deceleration force shall be approximately load-invariant — the same air brake application shall produce approximately the same deceleration rate regardless of load slider position.
- **FR-3**: Independent brake and dynamic brake deceleration force shall be inversely proportional to load — their effectiveness decreases as train weight increases, reflecting fixed loco-only force against increasing mass.
- **FR-4**: Coast deceleration (no brakes, throttle at zero) shall be inversely proportional to load — a heavier train coasts longer, reflecting inertia.
- **FR-5**: The transition from coast to any brake application shall be smooth and continuous — no discontinuous jumps in deceleration rate at brake onset.
- **FR-6**: Multiple simultaneous brake sources shall combine additively — total deceleration is the sum of all contributing forces.
- **FR-7**: Brakes shall function in NEUTRAL reverser position. NEUTRAL disables tractive effort only, not braking.
- **FR-8**: Braking under power (Regime C) shall apply the existing `MAX_BRAKE_UNDER_POWER` softening to brake forces in the additive model.
- **FR-9**: At load slider position 0 (light engine), braking delay values shall match the current behavior (no regression in the no-load experience).
- **FR-10**: Acceleration delay computation (including power curve) shall be unchanged. Load scaling for acceleration continues to use the existing global multiplier model.
- **FR-11**: Dynamic brake low-speed taper shall be preserved in the additive model.
- **FR-12**: Bail-off behavior shall be preserved — releasing loco-side braking contribution.
- **FR-13**: Air brake line/reservoir dynamics (Westinghouse model) shall be unaffected. The additive model uses the resulting `airBrakePcnt` as an input; it does not change how the air system state evolves.
- **FR-14**: Regime C target speed clamping (`targetSpeedStep = sliderSpeed × effectiveBrake`) shall continue to use per-source-scaled `effectiveBrake` so that loco-only brakes produce less target clamping at higher loads.

### Non-Functional Requirements

- **NFR-1**: All computation runs on the JMRI layout thread. No new threads, locks, or volatile fields.
- **NFR-2**: The additive force calculation must complete within the existing `recomputeTarget` / `computeRampDelay` call budget — no perceptible latency increase.
- **NFR-3**: The change shall be covered by unit tests verifying delay values for each brake source at multiple load levels, including the coast→brake transition smoothness.
- **NFR-4**: Existing `SemiRealisticThrottleEngineTest` tests shall pass or be updated to reflect the new delay model.

## 6. Success Metrics

- **SM-1**: Full air brake at max load produces a delay no more than 3× the no-load delay (current: 10×).
- **SM-2**: Full independent brake at max load produces a delay approximately 10× the no-load delay (reflecting fixed-force / mass relationship).
- **SM-3**: Coast-to-first-brake-notch transition at max load produces no more than a 2× change in delay (current: 10×).
- **SM-4**: At load 0, full air brake delay matches current value within 5%.
- **SM-5**: No existing test failures outside of intentional delay-value changes.

## 7. Out of Scope

- **Changes to air brake dynamics** (line/reservoir recharge rates, Westinghouse model behavior). The additive model consumes `airBrakePcnt` as an input; it does not change how that value evolves.
- **Changes to acceleration behavior**. The power curve, base acceleration delay, and load scaling for acceleration are unchanged.
- **Changes to ESU decoder brake passthrough**. The independent brake still evaluates ESU thresholds independently.
- **Changes to the Settings UI or calibration workflow**. No new settings are introduced; the existing `baseDecelDelayMs`, `numberOfBrakeSteps`, `maxLoadPcnt`, `numberOfLoadSteps`, and `MAX_BRAKE` / `MAX_BRAKE_UNDER_POWER` constants are reused.
- **Continuous-physics simulation**. The step-rate model (one speed step at a time with configurable delay) is preserved. This is not a force-integration physics engine.
- **Grade simulation**. No incline/decline effects are modeled.
- **Per-car brake modeling**. Air brake force is treated as a single aggregate proportional to mass, not modeled per-car.

## 8. Business Value

**High**

The semi-realistic throttle mode is the primary feature differentiating the JMRI RailDriver experience from a simple speed slider. Correct brake-vs-load interaction is central to the "feel" of operating a train. The current behavior (air brakes nearly useless at high load) breaks the operator immersion and teaches incorrect operating habits (avoiding air brakes in favor of emergency stops). Fixing this with a physically grounded additive model improves realism, operator satisfaction, and the pedagogical value of the simulator.
