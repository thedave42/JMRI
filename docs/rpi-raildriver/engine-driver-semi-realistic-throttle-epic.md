# Epic: RailDriver Semi-Realistic Throttle — EngineDriver-Aligned

## Summary

Port EngineDriver's `throttle_semi_realistic` algorithm to the JMRI RailDriver desktop integration, replacing the in-progress velocity-based physics engine with a simpler target-speed + Δt-multiplier step-rate scheduler. The result gives RailDriver console operators the same throttle feel as EngineDriver's Android semi-realistic mode — the throttle lever sets a target decoder speed step, and the live speed walks toward that target one fixed-size step every Δt milliseconds, where Δt is scaled by brake position, air-line state, load scenario, and direction.

## Motivation

The current in-progress physics engine (`SemiRealisticThrottleEngine`) uses a Davis-equation velocity model with ~20 m/s² coefficients, drag terms, and a quantisation step — a level of fidelity that is hard to tune, hard to test, and doesn't match any reference implementation operators are familiar with. EngineDriver's semi-realistic mode is well-understood, well-tested on Android, and deliberately simple: integer step counts, millisecond delays, and small-arity multipliers. Aligning with it gives operators a consistent experience across platforms and gives developers a clear, verified reference implementation to port from.

**Non-goal:** Prototype-accurate physics. There is no mass, no force, no m/s² figures, no Davis equation, no per-roster speed profile inside the engine. The decoder and JMRI roster speed profile remain the sole authority for actual model-train velocity.

## Scope

All work is contained within the JMRI desktop codebase. The RailDriver USB integration, throttle window plumbing, and bespoke settings frame are the primary integration surfaces. No changes to EngineDriver, WiThrottle protocol, or DCC decoder behaviour are in scope.

---

## Features

### Feature 1: Step-Rate Throttle Engine (Core Algorithm)

**Description:** Rewrite `SemiRealisticThrottleEngine` as a step-rate scheduler that walks the live `setSpeedSetting` value toward a `targetSpeed` one fixed-size step every Δt ms. Δt = `baseDelay × |targetAcceleration|`, where `baseDelay` is 300 ms (accel) or 800 ms (decel) by default.

**User Stories:**

- As an operator, I want the throttle lever to set a target speed so that my loco accelerates and decelerates gradually rather than jumping to the commanded speed instantly.
- As an operator, I want the ramp rate to feel consistent and predictable, matching the behaviour I'm familiar with from EngineDriver on Android.
- As an operator, I want the engine to seed from the throttle's current speed when I attach, so an already-moving loco resumes smoothly without ramping from zero.

**Key Behaviours:**
- Single-threaded on the JMRI layout thread — no `ScheduledExecutorService`, no `volatile`, no `synchronized`. Self-rescheduling via `ThreadingUtil.runOnLayoutDelayed`; cancellation by epoch counters.
- Lever-input setters only store values; `recomputeTarget()` runs `setTargetSpeed`, bumps the ramp epoch, and posts a fresh ramp callback.
- Decoder-side dispatch is throttled by a configurable minimum emit interval (default 50 ms) so DCC backends that coalesce or rate-limit stay in sync.
- Lifecycle is two-state: DETACHED → ATTACHED. Settings are captured as a defensive copy at attach time. A running engine accepts mid-session settings updates via `updateSettings(SemiRealisticSettings s)` on the layout thread — the engine replaces its internal copy atomically (single-threaded, no lock needed) and calls `recomputeTarget()` so the new values take effect on the next ramp tick.

**Acceptance Criteria:**
- [ ] Pure-throttle ramp from idle to full at defaults takes ~19 seconds.
- [ ] Coast from full to idle at defaults takes ~50 seconds.
- [ ] Engine seeds `speedStep` from the throttle's current speed on attach.
- [ ] All public methods are `@InvokeOnLayoutThread` annotated.
- [ ] No `ScheduledExecutorService`, `java.util.Timer`, `javax.swing.Timer`, `volatile`, or `synchronized` in the engine class.

---

### Feature 2: Multi-Source Brake System

**Description:** Implement three independent brake sources (independent brake, air system, dynamic brake) whose retardation values combine to produce `effectiveBrake`. Each source maps a physical RailDriver lever to the EngineDriver brake math. Because the RailDriver has separate physical levers for independent and auto brake (unlike EngineDriver's single slider with a toggle), load interacts differently with each brake source — matching real locomotive physics.

#### 2a: Independent Brake (Lever #11, byte 3)

- Quantised from calibrated Full Release / Full Application byte range to `numberOfBrakeSteps` notches (default 7).
- Position 0 = released. Each step immediately re-invokes `setTargetSpeed`.
- **Load interaction:** The independent brake acts only on the locomotive, not the cars. When a load is present, the loco's brakes must resist the inertia of the entire consist, so the independent brake becomes proportionally less effective. The load multiplier **reduces** `indepPcnt`'s contribution to deceleration — at full load (10×), the independent brake alone produces significantly less retardation than at light engine (1.0×). Concretely, `indepPcnt` is scaled toward 1.0 (free-running) by the load: `effectiveIndepPcnt = 1.0 − ((1.0 − indepPcnt) / loadMultiplier)`. At light engine (multiplier 1.0) this is a no-op; at full load (10×) the independent brake's retardation is reduced to 1/10th of its unloaded value.

#### 2b: Air System — Westinghouse Model (Auto Brake lever #10, byte 2)

The air system models a simplified Westinghouse automatic air brake. The Auto Brake lever controls a **brake valve** that regulates brake pipe (trainline) pressure, not brake cylinder pressure directly. Three state variables track the system:

- `airLineValue` (0..100) — brake pipe / trainline pressure. 100 = fully charged (brakes released), 0 = fully depleted (maximum braking).
- `airReservoirPct` (0..100) — main reservoir fill level. The energy source for recharging the brake pipe.
- `demandedLineValue` — the lever's current demanded pressure, derived from the Auto Brake lever position. This is what the operator is *commanding*; `airLineValue` is what the system has *achieved*.

**Brake application (lever deeper → line drops immediately):**

Moving the Auto Brake lever toward Application/EMG reduces `demandedLineValue` proportionally. When `demandedLineValue < airLineValue`, the engine drops `airLineValue` immediately to match — modelling the fast exhaust of the brake valve venting air from the brake pipe. Application is effectively instant because the brake valve vents to atmosphere; there is no supply constraint on exhausting air.

**Brake release (lever toward Released → line rises gradually):**

Moving the Auto Brake lever toward Released raises `demandedLineValue`. When `demandedLineValue > airLineValue`, the line does **not** snap to the demanded value. Instead, a **line repeater** (self-rescheduling via `runOnLayoutDelayed`, same epoch-cancellation pattern as the ramp scheduler) gradually walks `airLineValue` upward:

- Each tick adds `airLineRechargePcnt` (default 20) to `airLineValue`, drawing the same amount from `airReservoirPct`.
- If `airReservoirPct` is too low to supply a full chunk, the line recharges only as much as the reservoir can provide. If the reservoir is empty, the line **cannot recharge at all** — brakes stay applied even with the lever at Released. The operator must wait for the reservoir to refill.
- The line repeater stops when `airLineValue >= demandedLineValue` or when the lever moves back toward application (which drops `demandedLineValue` below `airLineValue`, switching back to the instant-drop path).
- Each tick calls `recomputeTarget()` so the gradual line rise progressively reduces braking force.
- Tick interval is `airRefreshRateMs` (default 2000 ms), matching EngineDriver's line repeater timing.

This models the real Westinghouse dynamic: moving the brake handle to Release doesn't instantly release the brakes — the brake pipe must recharge through the length of the train from the main reservoir. A fully depleted line at defaults takes 5 ticks × 2 seconds = **~10 seconds** to fully release (if the reservoir is charged). After an emergency application where the reservoir is also depleted, release takes considerably longer.

**Reservoir repeater (background, always running):**

The reservoir refills autonomously at `airReservoirReplenishPcnt` (default +5%) every `airRefreshRateMs` (default 2000 ms), modelling the compressor continuously charging the main reservoir. The reservoir is the supply that the line repeater draws from — it is no longer decorative.

**Lap (lever stationary at an intermediate position):**

The operator holds the Auto Brake lever at a partially applied position. `demandedLineValue` holds at a value below 100 but above 0. If `airLineValue` has already dropped to or below `demandedLineValue`, neither the instant-drop nor the line-recharge path fires — the line pressure holds steady. This naturally models the real "Lap" position where the brake valve is closed in both directions and the brake pipe holds its current pressure.

**Emergency application:**

Lever at EMG → `demandedLineValue = 0` → `airLineValue` drops to 0 immediately. All brake force is at maximum. Recovery from emergency requires the operator to move the lever to Released and wait for the line repeater to recharge — a slow process, especially since the reservoir was depleted to fill brake cylinders.

**Disabling the air simulation:** Set `airRefreshRateMs = 0` on the preferences panel. This short-circuits both repeaters; the lever directly sets `airLineValue` with no recharge dynamics (flat-mapping mode for operators who don't want air feel).

- **Load interaction:** The auto brake engages brakes on every car in the consist plus the locomotive — braking force scales with the number of cars, proportionally matching the additional mass. The auto brake's `airPcnt` contribution to `effectiveBrake` is therefore **not reduced** by load. At any load level, full auto brake application produces roughly the same deceleration rate. This matches real-world behaviour: a properly charged trainline stops a 100-car unit train at a comparable rate to a 20-car local because the braking force scales with the consist length.

**Extends EngineDriver's air model.** EngineDriver simulates Westinghouse dynamics with the same asymmetric apply/release and reservoir-gated line recharge. Our implementation ports that model faithfully. The RailDriver's continuous Auto Brake lever additionally enables intermediate-position lap and partial release to a specific pressure — capabilities the real Westinghouse brake valve supports but EngineDriver's discrete slider UI cannot express. These are natural extensions, not deviations from the underlying air model.

#### 2c: Dynamic Brake (Throttle/Dyn lever #9, below Idle)

- Below-idle throttle travel produces a virtual `dynBrakeStep` in `0..numberOfBrakeSteps`.
- Low-speed taper: below `dynBrakeMinSpeedStep` (default 8), dyn-brake effect fades linearly to zero at speed 0.
- No air-line interaction — dyn brake is electrical-only.
- **Load interaction:** Dynamic brake is loco-only (electrical resistance in the traction motors), same as the independent brake. Load reduces its effectiveness using the same formula as 2a: `effectiveDynPcnt = 1.0 − ((1.0 − dynPcnt) / loadMultiplier)`.

#### 2d: Bail-Off (byte 4, transient)

On a real locomotive, the bail-off valve vents the **locomotive's brake cylinders** to atmosphere — regardless of whether those cylinders were pressurised by the independent brake or by the automatic brake. The loco's brake cylinders don't know which valve filled them; bail-off dumps them all. The trainline and car brakes are completely unaffected.

Our model matches this: while bail-off is asserted, **all loco-side braking is released** — independent brake, dynamic brake, *and* the locomotive's share of the automatic brake application. Only the train-side retardation from the air system (the cars' brakes, which the loco cannot locally vent) continues to apply. The loco itself is free-rolling; only the braked cars behind it provide deceleration.

This enables the real-world bail-off use cases:
- **Grade descending:** auto brakes hold the cars; bail-off frees the loco wheels to prevent flat spots.
- **Switching/coupling:** auto brakes hold the consist; bail-off lets the loco creep forward under power for a controlled coupling.
- **Starting on a grade:** auto brakes hold the train; bail-off + throttle stretches the slack before releasing the train brakes.
- **Emergency recovery:** bail-off lets the loco move to clear a crossing while train brakes are still bleeding off through the slow recharge cycle.

#### 2e: Effective brake combination

The final `effectiveBrake` is the minimum (strongest braking) across all sources, after load scaling has been applied per-source:

**Normal (bail-off not asserted):**
```
effectiveIndepPcnt = 1.0 − ((1.0 − rawIndepPcnt) / loadMultiplier)   // loco-only, load-reduced
effectiveDynPcnt   = 1.0 − ((1.0 − rawDynPcnt)   / loadMultiplier)   // loco-only, load-reduced
effectiveAirPcnt   = rawAirPcnt                                        // whole-train, load-invariant

effectiveBrake = min(effectiveIndepPcnt, effectiveAirPcnt, effectiveDynPcnt)
```

**Bail-off asserted (loco brake cylinders vented):**
```
effectiveIndepPcnt = 1.0    // released — loco cylinders vented
effectiveDynPcnt   = 1.0    // released — electrical brake disengaged
effectiveAirPcnt   = rawAirPcnt adjusted for train-only retardation
                     // The air system's braking still decelerates the consist via
                     // the cars' brakes, but the loco itself is free-rolling.
                     // The effective retardation is reduced because only the cars
                     // are braking, not the loco — modelled by scaling airPcnt
                     // by the load multiplier (more cars = more train-side braking
                     // force relative to total mass).

effectiveBrake = effectiveAirPcnt   // only train-side braking remains
```

At light engine (loadMultiplier = 1.0, no cars), bail-off releases all braking entirely — there are no car brakes to contribute. This is correct: bailing off a light engine with the auto brake applied means nothing is braking at all.

At light engine (loadMultiplier = 1.0), all three sources pass through unchanged — identical to EngineDriver's single-source `effectiveBrake = min(...)`.

**Deviation from EngineDriver:** EngineDriver has one brake slider with a toggle, so load applies uniformly to all braking. Our per-source load scaling is new and has no EngineDriver equivalent. It is justified by the RailDriver's separate physical levers, which make the independent-vs-trainline distinction operationally real.

**User Stories:**

- As an operator, I want the Independent Brake lever to slow and stop my loco progressively.
- As an operator, I want the Auto Brake lever to simulate Westinghouse air dynamics — application is fast but release is gradual, just like a real train.
- As an operator, I want to feel the brakes bleed off slowly after I release the auto brake, not snap off instantly.
- As an operator, I want to hold the auto brake at an intermediate position and have the brake pipe pressure stabilise there (lap behaviour).
- As an operator, I want emergency brake recovery to take noticeably longer than a normal service release.
- As an operator, I want below-idle throttle travel to apply dynamic braking that fades at low speed.
- As an operator, I want the bail-off to vent the locomotive's brake cylinders so I can free-roll the loco while the train stays braked — just like a real bail-off valve.
- As an operator doing a switching move, I want to apply the auto brake to hold my consist, then bail off and creep forward under power for a controlled coupling.
- As an operator pulling a heavy train, I want the auto brake to stop me effectively while the independent brake alone barely slows me, just like a real locomotive.
- As an operator running light engine, I want both brakes to feel equally effective since there are no cars to worry about.
- As an operator, I want to view the current status of my air line and air reservoir for my Westinghouse brakes in a panel of the JMRI throttle.

**Acceptance Criteria:**
- [ ] At light engine (loadMultiplier = 1.0), all three brake sources pass through unchanged — behaviour is identical to EngineDriver.
- [ ] At full load (step 5, multiplier 10×), independent brake full application produces roughly 1/10th the retardation of the same application at light engine.
- [ ] At full load, auto brake full application produces roughly the same retardation as at light engine.
- [ ] Dynamic brake follows the same load-reduction formula as independent brake.
- [ ] Indep brake full + throttle 50% at light engine → loco settles at ~15% of full speed. At full load → loco settles at a much higher speed (independent brake alone can barely overcome the train's inertia).
- [ ] **Air application is immediate:** moving the Auto Brake lever deeper drops `airLineValue` to the demanded value instantly.
- [ ] **Air release is gradual:** moving the Auto Brake lever toward Released does NOT snap `airLineValue` up. The line repeater walks it up at `airLineRechargePcnt` per `airRefreshRateMs` tick, drawing from the reservoir.
- [ ] **Reservoir gates release:** if `airReservoirPct` is depleted, the line cannot recharge — brakes stay applied even with the lever at Released.
- [ ] Full line release from 0% at defaults (~20% per 2s tick, reservoir charged) takes ~10 seconds.
- [ ] **Lap behaviour:** holding the lever stationary at an intermediate position holds `airLineValue` steady — neither dropping nor rising.
- [ ] **Emergency recovery:** after EMG (line and reservoir both depleted), full release takes considerably longer than a normal service release because the reservoir must refill first.
- [ ] Reservoir refills at +5% per tick when `airRefreshRateMs > 0`.
- [ ] `airRefreshRateMs = 0` disables both repeaters — lever directly sets `airLineValue` with no dynamics (flat-mapping mode).
- [ ] Auto Brake EMG at any load → comparable hard decel rate.
- [ ] **Bail-off releases all loco-side braking:** with bail-off asserted, indep, dyn, AND the loco's share of the auto brake are all released. Only the cars' brakes (train-side air retardation) continue to decelerate the consist.
- [ ] **Bail-off at light engine:** with no load (no cars), bail-off + auto brake applied = no braking at all (nothing is braking — loco cylinders are vented and there are no car brakes).
- [ ] **Bail-off coupling move:** auto brake applied + bail-off + throttle → loco creeps forward under power while consist stays braked.
- [ ] Dyn brake below `dynBrakeMinSpeedStep` fades linearly to zero.

---

### Feature 3: ESU Decoder Brake Passthrough

**Description:** Port EngineDriver's `setDecoderBrake` function passthrough. Brake-percent from the Independent Brake position drives F4/F5/F6 on/off transitions at three configurable thresholds (default 30/60/98%). Gated by `decoderBrakeMode` setting (`NONE` | `ESU`); default `NONE` short-circuits the entire passthrough.

**User Stories:**

- As an operator with an ESU decoder, I want the Independent Brake lever to automatically trigger decoder brake functions so I don't have to press function buttons manually.
- As an operator without ESU decoders, I want this feature completely inert by default.

**Acceptance Criteria:**
- [ ] When `decoderBrakeMode == ESU`, indep brake crossing each threshold toggles the configured function number.
- [ ] When `decoderBrakeMode == NONE`, no function calls are made regardless of brake position.
- [ ] Function numbers and thresholds are configurable (defaults: F4/F5/F6 at 30/60/98%).
- [ ] Thresholds must be ascending (validated on save and on load).

---

### Feature 4: Load Slider (EngineDriver-Aligned)

**Description:** Port EngineDriver's load slider and quadratic `getLoadPcnt` formula. A discrete-step slider (default 0–5 positions) on the Semi-Realistic preferences panel feeds the same runtime quadratic that EngineDriver uses: `((load² × (maxLoadPcnt − 100)) + 100) / 100`, where `load = step / numberOfLoadSteps`. The result multiplies `targetAcceleration` at the end of `setTargetSpeed`, stretching Δt without ever modifying `targetSpeed`. Both curve-shaping parameters (`numberOfLoadSteps`, `maxLoadPcnt`) are exposed as operator-configurable settings, matching EngineDriver's preferences.

**EngineDriver's `getLoadPcnt` formula (defaults: 5 steps, maxLoadPcnt = 1000):**

| Slider step | load = step/5 | Multiplier                         |
|-------------|---------------|------------------------------------|
| 0           | 0.00          | Guard skips block → effective 1.0× |
| 1           | 0.20          | 1.36×                              |
| 2           | 0.40          | 2.44×                              |
| 3           | 0.60          | 4.24×                              |
| 4           | 0.80          | 6.76×                              |
| 5           | 1.00          | 10.00×                             |

The curve is quadratic — load ramps steeply in the upper half. The operator controls both the granularity (number of steps) and the ceiling (max load percent), allowing the curve to be reshaped without code changes.

**How load is applied.** The load multiplier feeds two places in `setTargetSpeed`:

1. **Acceleration Δt scaling** (matches EngineDriver): `targetAcceleration *= loadMultiplier`. Higher load = longer inter-step delay = slower acceleration. This is the same single wiring point EngineDriver uses.
2. **Per-source brake effectiveness** (RailDriver-only, see Feature 2): the load multiplier reduces the retardation of loco-only brake sources (independent brake, dynamic brake) while leaving train-wide braking (auto/air brake) unaffected. This models the real-world difference: applying only the loco brakes on a heavy train barely slows it, while the trainline brakes engage the entire consist. See Feature 2e for the formula.

At light engine (loadMultiplier = 1.0), both paths are no-ops — behaviour is identical to EngineDriver.

**UI.** The load slider is a `JSlider` (integer, 0..`numberOfLoadSteps`) on the Semi-Realistic preferences panel with tick labels showing the computed multiplier at each position (e.g. "1.0×", "2.44×", "10.0×"). There are no named scenario presets or enum — just the slider, matching EngineDriver's single SeekBar. The slider position is persisted as `loadSliderPosition` in the `<rd:semiRealistic>` fragment and is pushed live to any attached engine on Save/Apply (see Feature 6).

**RailDriver input mapping.** EngineDriver uses a touchscreen SeekBar. The RailDriver has no physical load lever, so load is a **software-only input** controlled via the preferences panel slider. The slider position is pushed to the engine on save/apply and takes effect immediately.

**Key Behaviours:**
- **Quadratic formula matches EngineDriver exactly:** `getLoadPcnt(step, steps, maxLoadPcnt)` is a static method with the same signature and computation as EngineDriver's.
- **Guard condition preserved:** When `loadSliderPosition == 0`, the multiplication is skipped entirely (matching EngineDriver's `if (loadSliderPosition > 0)` guard). This is functionally equivalent to multiplying by 1.0 but avoids a redundant floating-point operation.
- **Immediate application on save:** When the operator saves settings, the new `loadSliderPosition` is pushed to any attached engine via `updateSettings()`, which calls `recomputeTarget()` — matching EngineDriver's `onProgressChanged` → `setTargetSpeed` immediate-recalculation path.
- **Change detection:** The engine tracks `prevLoadStep` and only kicks the ramp repeater if the load step actually changed since the last `setTargetSpeed` call, matching EngineDriver's `prevLoads[]` optimisation.
- **Configurable curve:** `numberOfLoadSteps` (default 5) sets the slider granularity and the formula denominator. `maxLoadPcnt` (default 1000) sets the curve ceiling — at 1000 the full-slider multiplier is 10×; at 500 it's 5×; at 200 it's 2×.

**User Stories:**

- As an operator, I want a load slider so my loco feels heavier or lighter, with the same quadratic feel as EngineDriver's load slider.
- As an operator, I want to adjust the curve ceiling (`maxLoadPcnt`) so I can limit or extend the maximum load effect for my layout.
- As an operator, I want a load change to take effect immediately when I save, even if the throttle is active.

**Acceptance Criteria:**
- [ ] `getLoadPcnt` formula matches EngineDriver's source exactly: `((load² × (maxLoadPcnt − 100)) + 100) / 100`.
- [ ] At defaults (5 steps, maxLoadPcnt = 1000), step 0 → 1.0×, step 5 → 10.0×, intermediate steps match EngineDriver's quadratic values.
- [ ] When `loadSliderPosition == 0`, the load multiplication is skipped (guard condition matches EngineDriver).
- [ ] `numberOfLoadSteps` and `maxLoadPcnt` are configurable settings (persisted, validated on save and load).
- [ ] Changing `maxLoadPcnt` reshapes the curve — e.g. `maxLoadPcnt = 500` → full-slider multiplier is 5×.
- [ ] Load slider is a `JSlider` with tick labels showing computed multipliers at each step.
- [ ] Saving settings pushes the new load slider position to any attached engine immediately.
- [ ] Engine tracks `prevLoadStep` for change detection; redundant calls don't restart the ramp.
- [ ] `loadSliderPosition` is persisted as an integer in the `<rd:semiRealistic>` fragment.

---

### Feature 5: Direction & Stop Semantics

**Description:** Implement EngineDriver's direction lever interlock and E-Stop handling on the RailDriver reverser and E-Stop switch.

**Key Behaviours:**

- **Direction lever:** NEUTRAL always allowed (forces `target = 0, accel = −1`). Forward ↔ Reverse only allowed at `speedStep == 0`; otherwise the lever's target direction is stored for when the loco stops.
- **E-Stop (SPDT #2):** Hard E-Stop unconditionally. Calls `engine.emergencyHalt()` (bumps all three pipeline epochs, resets speed state), then writes `setSpeedSetting(-1f)` directly. Recovery is automatic — next lever-change event calls `recomputeTarget()`.
- **No soft-stop button.** Operators stop by walking levers down, matching real-loco practice.

**User Stories:**

- As an operator, I want the reverser to be interlocked so I can't reverse direction while the loco is moving.
- As an operator, I want E-Stop to instantly halt the loco and cancel all in-flight ramp callbacks.
- As an operator, I want recovery from E-Stop to be automatic when I move a lever.

**Acceptance Criteria:**
- [ ] Reverser flip at non-zero speed is ignored; at zero speed it takes effect.
- [ ] E-Stop cancels all three pipeline epochs (ramp, air, deferred-emit).
- [ ] After E-Stop, the next lever-change event resumes normal ramp behaviour.

---

---

### Feature 7: Profile-Aware Persistence (`AuxiliaryConfiguration`)

**Description:** Migrate persistence from a freestanding `raildriver-calibration.xml` file to two `AuxiliaryConfiguration` fragments under namespace `http://jmri.org/xml/schema/raildriver/3`:

| Fragment                      | Space   | Content                                    |
|-------------------------------|---------|--------------------------------------------|
| `<rd:hardwareCalibration>`    | Private | Per-machine HID byte detents (7 axes)      |
| `<rd:semiRealistic>`          | Shared  | All EngineDriver-aligned operator preferences |

**Key Behaviours:**
- Two XSD schemas under `xml/schema/raildriver/` (Venetian Blinds pattern).
- Missing fragment → schema defaults applied silently.
- Unparseable attribute → `ErrorHandler` report + default substituted.
- Unknown namespace (future `/4`) → treated as missing; defaults applied.

**User Stories:**

- As an operator, I want my semi-realistic preferences to travel with my profile when I share it across computers.
- As an operator, I want my per-machine HID calibration to stay private to each computer.

**Acceptance Criteria:**
- [ ] Shared fragment persists to root `profile.xml`; private fragment persists to per-node `profile.xml`.
- [ ] Both XSDs validate via `xmllint -schema http://www.w3.org/2001/XMLSchema.xsd`.
- [ ] Standard JMRI `trueFalseType` used for the `enabled` attribute.
- [ ] Enum-valued attributes use `EnumIoNames` with `ErrorHandler` routing.
- [ ] Schema annotations include `<jmri:usingclass>` identifying the reader/writer class.

---

### Feature 8: Legacy File Migration

**Description:** One-time migration from the freestanding `<profile-root>/profile/raildriver-calibration.xml` (v1/v2) to the new `AuxiliaryConfiguration` fragments. Runs automatically on first load of a profile with the new code.

**Migration Rules:**
1. **Only legacy file exists:** Detents copied to `<rd:hardwareCalibration>` (private). v2 `<semiRealistic>` subtree **fully discarded** (field meanings changed incompatibly). Fresh `<rd:semiRealistic>` at defaults written to shared space. Legacy file renamed to `.bak`. Warn-level `ErrorHandler` report for v2 discard.
2. **Both exist:** New fragments win. Legacy file left in place. Warn-level report.
3. **Neither exists:** Defaults on first load; fragment created on first save.
4. **Idempotent:** Repeated calls short-circuit.

**User Stories:**

- As an operator upgrading from an older JMRI version, I want my captured calibration detents preserved automatically.
- As an operator, I want the old file renamed (not deleted) so I can review it if needed.

**Acceptance Criteria:**
- [ ] v1 file → detents migrate, defaults for semi-realistic, no error report.
- [ ] v2 file → detents migrate, v2 semi-realistic discarded, warn-level error report fires.
- [ ] Legacy file renamed to `.bak` (not deleted).
- [ ] Migration is idempotent — running twice produces no further filesystem changes.

---

### Feature 9: Throttle-Toolbar Connectivity Indicator (Jynstrument)

**Description:** A passive RailDriver-USB connectivity indicator on the throttle window toolbar. Two visual states only: active (connected) or greyed (disconnected). Clicking opens the RailDriver Settings window. Not mode-aware.

**User Stories:**

- As an operator, I want a quick visual indicator showing whether my RailDriver console is connected.
- As an operator, I want to reach RailDriver settings quickly by clicking the toolbar indicator.

**Acceptance Criteria:**
- [ ] Active icon when `isRailDriverConnected()` is true; greyed when false.
- [ ] Left-click opens the RailDriver Settings window.
- [ ] Right-click shows a popup with a "Settings..." item that does the same.
- [ ] Both click paths work in both visual states (no `setEnabled(false)`).
- [ ] `quit()` deregisters the PCS listener (no listener leak).
- [ ] Auto-installed by `RailDriverMenuItem.attachThrottleWindow()`, idempotently.

---

### Feature 10: Package Relocation

**Description:** Relocate all RailDriver classes from `jmri.util.usb` to `jmri.jmrit.usb` (with `.swing` and `.configurexml` sub-packages). This satisfies the JMRI structure rules: `jmrit` is the correct home for user-level tools, and references to `jmri.jmrit.throttle` / `jmri.jmrit.roster` become legal cross-tree references.

**Relocated (functionally unchanged except import updates):**
- `CalibrationTabPanel`, `CalibrationBar` — calibration UI components
- `Bundle.properties` (+ 5 locale variants)
- `RailDriverMenuItem` — button mapping, axis decoding, lifecycle wiring
- `RailDriverSettingsFrame`, `RailDriverSettingsAction`, `SemiRealisticSettingsPanel` — bespoke settings UI
- `apps.jmrit.DebugMenu` import references updated

**User Stories:**

- As a developer, I want RailDriver code in the correct JMRI package (`jmrit`) so cross-tree dependency rules are satisfied.

**Acceptance Criteria:**
- [ ] No RailDriver classes remain in `jmri.util.usb`.
- [ ] `ArchitectureTest` passes with no new violations.
- [ ] All existing functionality preserved after relocation.

---

### Feature 11: Testing & Documentation

**Description:** Comprehensive test suite and documentation for all features.

**Testing:**

| Test Type                | Scope                                                          |
|--------------------------|----------------------------------------------------------------|
| Unit tests (pure math)   | `getBrakeDecimalPcnt`, `getLoadPcnt`, `effectiveDynBrakeStep` — table-driven with EngineDriver-verified expected values (load tests verify the quadratic at each default slider step and at non-default `maxLoadPcnt` values) |
| Engine integration tests | Mock `DccThrottle`; lever inputs → emission sequence verification (pure-throttle ramp, brake clip, brake to zero, air depletion, bail-off, direction interlock, load multiplier scaling) |
| Schema validation        | `SchemaTest` over `valid/` and `invalid/` fixture directories for both fragments |
| Load/store round-trip    | `LoadAndStoreTest` for fragment round-trip and legacy-file migration (Groups A and B) |
| Manual hardware tests    | Smoke tests on real RailDriver console with connected layout or debug-throttle |

**Documentation:**

| Deliverable                                              | Type          |
|----------------------------------------------------------|---------------|
| `help/en/html/tools/usb/RailDriverSemiRealistic.shtml`  | New help page |
| `help/en/html/tools/usb/RailDriverConnectionIndicator.shtml` | New help page |
| `help/en/html/tools/usb/RailDriverSettings.shtml`       | Updated       |
| Javadoc on all new/changed public APIs                   | Code docs     |

**Acceptance Criteria:**
- [ ] All unit tests pass with EngineDriver-verified expected values.
- [ ] All `SchemaTest` fixtures validate (valid/) or fail (invalid/) as expected.
- [ ] All `LoadAndStoreTest` fixtures round-trip correctly.
- [ ] Legacy migration test fixtures exercise all four migration paths.
- [ ] `warnOnce`/`infoOnce` test paths follow JMRI JUnit reset guidance.
- [ ] Help pages ship for semi-realistic mode, connection indicator, and updated settings.
- [ ] Javadoc on every new public or protected API.

---

## Dependencies & Constraints

- **EngineDriver reference:** The algorithm must match EngineDriver's `throttle_semi_realistic.java` section-for-section where possible. The `getLoadPcnt` quadratic formula, its guard condition, both curve-shaping preferences (`numberOfLoadSteps`, `maxLoadPcnt`), and Westinghouse air dynamics (asymmetric apply/release, reservoir-gated line recharge) are all ported from EngineDriver. The RailDriver's continuous Auto Brake lever extends the air model with intermediate-position lap and partial release, which EngineDriver's discrete slider cannot express but the underlying model supports. Two documented deviations exist:
  - **Dyn-brake low-speed taper** (Feature 2c) — EngineDriver has no dynamic brake input; we add one with a prototype-like low-speed fade.
  - **Per-source brake load scaling** (Feature 2e) — EngineDriver applies load uniformly because it has a single brake slider. We differentiate: loco-only brakes (independent, dynamic) are reduced by load; train-wide braking (auto/air) is load-invariant. Justified by the RailDriver's separate physical levers and real-locomotive physics.
- **JMRI threading conventions:** All timed events through `ThreadingUtil`, never `ScheduledExecutorService` or `java.util.Timer`.
- **JMRI SPI patterns:** Persistence via `PreferencesManager`, discovered via `ServiceLoader`.
- **Backward compatibility:** Legacy calibration files must migrate without data loss (detents preserved, physics coefficients discarded with warning).
- **Live-apply for settings and calibration:** Saved settings and calibration are pushed to any attached engine / dispatcher immediately via PCS events. The engine accepts mid-session updates via `updateSettings()` on the layout thread. Exception: the `enabled` flag (dispatch strategy) is fixed at bind time — changing it requires closing and reopening the throttle.

## Cross-References

- [EngineDriver algorithm research](semi-realistic-throttle-info.md) — line-by-line reference of the EngineDriver source
- [RailDriver control inventory](control-inventory.md) — physical control / HID byte mapping
- [EngineDriver source](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java) — upstream reference implementation
