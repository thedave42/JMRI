# RailDriver Semi-Realistic Throttle Support

> **Research source:** [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md). All section references prefixed `[research §X]` resolve there.
> **Feature goal:** add semi-realistic throttle behaviour to the RailDriver path on a **model railroad layout**. The throttle lever no longer maps directly to the DCC speed step; it sets a *target* setting and the engine ramps the live setting toward it at rate-limited speeds determined by operator-tunable constants. Acceleration, coast deceleration, brake response, and the Westinghouse air-brake reservoir/line dynamics are simulated entirely in **DCC throttle-fraction space** (`s` ∈ [0, 1]); there is no mass, no force, no prototype velocity, no scale factor anywhere in the engine math.

## 1. Scope

### 1.0 Operating model — throttle-fraction control with operator-tunable rates

This is a model-railroad simulation, not a prototype simulator. The engine works entirely in **DCC throttle-setting space**: `s` is the live `setSpeedSetting` fraction (0..1) the engine emits to the decoder, and `sTarget` is the throttle-lever-derived target. Each tick, `s` advances toward `sTarget` at a rate determined by lever positions, brake states, and per-scenario operator-tunable constants. **There is no mass, no force, no prototype velocity, no scale factor anywhere.** The decoder + speed profile remain the sole authority for physical loco speed; the engine never imposes a top-speed cap.

Operator expectations the engine matches:

- Throttle lever at X % → loco eventually reaches `setSpeedSetting(X / 100)`, regardless of any internal "physics."
- Acceleration takes wall-clock time, not instant snap. Default rate is mass-independent ("feel" parameter); operators can customise on the Settings tab.
- Reducing throttle → loco coasts down on a single operator-tunable rolling-drag rate (no aerodynamic term — operators don't care about air resistance; they want a simple coast curve).
- Brakes (independent, automatic, dynamic) decelerate the loco. Full indep + full auto brake produces a decel rate higher than max accel — so full-throttle-against-full-brake = no motion. This is a hard constraint enforced by the rate-vs-rate arithmetic.
- Heavier consists accelerate more slowly, decelerate more slowly from drag, and respond less to the *independent* brake (because indep is loco-only) but full-strength to the *automatic* brake (because pipe pressure propagates through the trainline to all cars).
- Auto brake on a consist simulates the **Westinghouse air-brake** functionality: brake-pipe pressure, aux-reservoir charge, and cylinder pressure dynamics produce the prototypical "running out of air after repeated applications" feel. Light-engine scenarios (no consist) bypass the Westinghouse model and use a direct lever → cylinder-pressure mapping.

Key separation from the rejected Newton's-law model:

- **Drive and brake do not "compete" via force summation.** Drive raises `s` at a fixed operator-tunable rate when `sTarget > s`. Brakes lower `s` at their own rates. The integrator subtracts brake rates from drive rate; if the result is non-positive while `s < sTarget`, `s` simply doesn't increase — no asymptote, no "drive force can't beat resistance" surprise.
- **Acceleration rate is mass-independent by default.** A simple "feel" parameter (e.g. 0.10 fraction/s wall-clock = 0 → full in 10 s) tuned for desired operator UX, not derived from prototype HP/TE. Per-scenario constants modulate it for "heavy train accelerates more slowly" UX.
- **Coast resistance is a single linear-in-`s` rate.** No quadratic aerodynamic term — eliminated per operator request. Decel from coast = `dragCoeff · s · consistDragFactor`.
- **Top speed is whatever the decoder produces at `s = 1.0`.** The engine never caps `s` below 1.0 unless brakes overcome drive. There is no `designTopSpeed` field, no scenario top-speed parameter, no `getPhysicsMaxSpeedKmh()` clamp inside the engine.

#### 1.0.1 Per-tick body sketch

```java
// 1. Lever-derived target
float sTarget = throttleAboveIdleFraction;        // 0..1; below idle → 0
float dynApply = throttleBelowIdleFraction;        // 0..1 only when in dyn-brake region

// 2. Westinghouse air-brake simulation (stage 4; light engine bypasses)
//    See §1.0.2 for the state machine. Result: cylinderPressure ∈ [0, 1].
float cylinderPressure = updateWestinghouseAndGetCylinderPressure(autoBrakeLever, dt);
float autoForce = bailoffPressed ? 0.0f : cylinderPressure;

// 3. Rate composition (all values dimensionless: fraction/sec wall-clock)
float driveRate = (sTarget > s) ? p.accelRate * p.consistAccelFactor : 0.0f;
float dragRate  = p.dragCoeff * s * p.consistDragFactor;
float mechRate  = indepBrakeLever * p.indepBrakeRate * p.indepConsistFactor;
float autoRate  = autoForce * p.autoBrakeRate;                          // not consist-scaled; pipe propagates through trainline
float dynRate   = (s > 0) ? dynApply * p.dynBrakeRate * p.dynConsistFactor * dynTaper(s) : 0.0f;

// 4. Integrate (no clamp on sign of dsdt — rate-of-change is what we want)
float dsdt = driveRate - dragRate - mechRate - autoRate - dynRate;
s += dsdt * TICK_SECONDS;
s = clamp01(s);

// 5. Emit on EDT
ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(s));
```

The engine emits `s` directly. No fraction ↔ velocity conversion, no roster speed profile lookup inside the engine, no top-speed clamp. The roster speed profile and `getPhysicsMaxSpeedKmh()` are decoder-layer concerns the engine doesn't touch.

#### 1.0.2 Westinghouse air-brake state machine (stage 4)

When a consist is present, the auto brake handle drives a 3-state pneumatic model:

- **`pipePressure`** ∈ [0, 1] — brake-pipe pressure (1 = released full pressure, 0 = empty / emergency).
- **`auxCharge`** ∈ [0, 1] — average auxiliary-reservoir charge across cars.
- **`cylinderPressure`** ∈ [0, 1] — average brake-cylinder pressure across cars.

Per-tick update with operator-tunable time constants (`T_pipe`, `T_recharge`, `T_cylinderRelease`, `T_auxDrain`, `cylinderMagnification`):

```java
// Pipe approaches target set by handle position.
float pipeTarget = 1.0f - autoBrakeLever;  // released = 1, EMG = 0
pipePressure += (pipeTarget - pipePressure) * dt / T_pipe;

if (pipePressure < auxCharge) {
    // Triple valve in apply position: aux feeds cylinder.
    float desiredCyl = clamp01((auxCharge - pipePressure) * cylinderMagnification);
    cylinderPressure += (desiredCyl - cylinderPressure) * dt / T_cylinderApply;
    auxCharge -= cylinderPressure * dt / T_auxDrain;     // aux drains as it feeds cylinder
} else {
    // Triple valve in release/recharge position: cylinder vents fast, aux recharges slow.
    cylinderPressure -= dt / T_cylinderRelease;
    auxCharge += (pipePressure - auxCharge) * dt / T_recharge;
}
auxCharge       = clamp01(auxCharge);
cylinderPressure = clamp01(cylinderPressure);
```

Operator-felt consequences (which the model reproduces by construction):

- Service application: handle to "Min Service" or beyond → pipe drops slightly → cylinder fills proportionally to (aux − pipe) × magnification → cars apply brake. Aux drains.
- Emergency: handle to EMG → pipe target = 0 → pipe drops fast → triple valve apply. Cylinder reaches max. Aux fully drains.
- Release: handle back to Released → pipe target = 1 → pipe recovers (T_pipe) → triple valve release → cylinder vents (fast, T_cylinderRelease) → aux recharges from main (slow, T_recharge ≈ 60+ s for a long train).
- "Running out of air": after several full-service applications without enough recovery time, `auxCharge` is well below 1 → next application produces weaker `cylinderPressure` → less braking. Operator must release and wait for recharge.

Bail-off zeros the auto-brake's contribution to the rate equation while held (matches §3.4 stage-4 behaviour and the §6 bail-off semantics decision); it does NOT touch `pipePressure` or `auxCharge`. So aux continues draining through cylinder while bail-off is held — release the bail-off and the (still-elevated) cylinder pressure is reapplied.

Light-engine scenarios (no consist) bypass the state machine entirely:
```java
cylinderPressure = autoBrakeLever;   // direct mapping; no pipe/aux dynamics
auxCharge = 1.0f;                    // always full; light engine has direct main → cylinder
pipePressure = 1.0f - autoBrakeLever;  // tracked for status/display only
```

This simulation lives in stage 4 (auto-brake stage); stages 2–3 keep `cylinderPressure = 0` so the auto-brake summand is inert.

### 1.1 In scope (split into stages 1–6, each independently shippable)

**Stage 1 — Refactor & off-EDT bug fix.** Pure refactoring with no new feature behaviour. Wraps every Swing-touching call in `RailDriverMenuItem.dispatchValueEvent` via `ThreadingUtil.runOnGUIEventually` (setters) or `ThreadingUtil.runOnGUIwithReturn` (getters that feed decision logic), closing the off-EDT-mutation latent issue from the existing RailDriver bring-up. Replaces the standalone `RailDriverCalibrationFrame` / `RailDriverCalibrationAction` with the unified two-tab `RailDriverSettingsFrame` (described in §2.4); the Calibration tab holds the existing visual-bar UI verbatim, the Settings tab is present but disabled (its fields land in stage 2). Migrates the Bundle key (`RdCalibrate` → `RdSettings`), updates `DebugMenu`, and bumps the calibration XML schema to `version="2"` with the `<semiRealistic>` element tolerated as absent. **No physics engine, no Jynstrument, no behaviour change for users** beyond the renamed Debug-menu entry and the (invisible) EDT bug fix.

**Stage 2 — Throttle-fraction physics engine + bypass switch + scenario picker + independent brake + toolbar mode toggle.** First stage where the operator can opt in and run the loco under physics. Adds the `SemiRealisticThrottleEngine` class with the **throttle-fraction-rate model from §1.0 baked in from day one**: per tick computes drive / drag / mech-brake rates in fraction/sec wall-clock, integrates `s` toward `sTarget`. 50 ms tick on a worker thread, full integration body including `mechRate` so the operator has a working brake. The Settings tab exposes the operator-tunable rates (drive, drag, indep brake) and the consist parameter. Adds the `LoadScenario` enum and Settings-tab picker (all 7 scenarios incl. Custom — each scenario is a feel-rate preset with consist parameters), the persisted-vs-live `enabled` split (§2.3), the toolbar Jynstrument (§2.6), and the `hidDeviceAttached` re-enable for hot-plug PCS firing. Brake terms `autoRate` and `dynRate` are present in the integration as zero-valued summands until stages 4–5. When semi-realistic mode is OFF (default), behaviour is identical to stage 1's bypass path. When ON, the throttle lever (Axis 1 above Idle High) sets `sTarget`, the calibrated indep-brake position (Axis 3) drives `mechRate`, and the integrator walks `s` toward target under the resulting net rate. Defaults match a "Light engine" feel preset out of the box (see §2.4.1).

**Stage 3 — Reverser interlock.** Direction-change-only-at-zero-`s` interlock per [research §5]. E-Stop SPDT keeps its current `setSpeedSetting(-1)` behaviour. Independent of brake terms; only requires the engine's `s` field.

**Stage 4 — Auto brake (Axis 2) → Westinghouse air-brake simulation + bail-off override.** The Auto Brake lever drives the Westinghouse state machine from §1.0.2. Pipe pressure tracks the lever's released-to-EMG position with time constant `T_pipe`. Triple-valve logic transfers air between aux reservoir and brake cylinder. The cylinder-pressure output drives the `autoRate` term in the per-tick integration. Light-engine scenarios bypass the state machine and use a direct lever → cylinder mapping. Bail-off (byte 4 transient) zeros `autoRate` while held without touching the underlying state. Per-scenario time constants (`T_pipe`, `T_recharge`, etc.) make consist-length-driven feel automatic — longer trains have longer pipe charge times and slower aux recharges by tuning, not by car-counting code.

**Stage 5 — Dynamic brake side of throttle lever (Axis 1 below Idle Low).** Below the calibrated Idle Low, the throttle lever produces `dynRate = dynApply · dynBrakeRate · dynConsistFactor · dynTaper(s)` where `dynTaper(s) = min(1, s / dynTaperThreshold)` and `dynTaperThreshold ≈ 0.05` (`s = 5 %`, roughly the RailDriver's calibrated low-speed cut-off; not scale-coupled because there is no scale). Acts on loco-only (dyn brake doesn't propagate through trainline air per [research §10] item 1) so its consist factor is small (e.g. 0.1 for a unit train). Stacks with mech and auto brake by simple rate summation. LED display shows `DBr` while in dyn-brake region.

**Stage 6 — ESU decoder-brake passthrough (optional, gated by user preference).** Per [research §4.3], computes effective brake percent from the indep + auto cylinder pressure and forwards F4/F5/F6 dispatch when the user opts in. Defaults to OFF.

### 1.2 Out of scope (deferred to future work)

- **Per-roster scenario default.** This feature ships with a session-level picker; reading `RosterEntry.getAttribute("raildriver.scenario")` to override the session default is deferred per [research §9.2.5].
- **Multi-throttle support.** EngineDriver runs up to 6 locos in parallel; we keep the existing single-throttle assumption from the RailDriver bring-up phases.
- **Aerodynamic drag.** Per operator direction, coast resistance is a single linear-in-`s` term — no quadratic / aerodynamic component. Operators don't care about air resistance for model railroad simulation.
- **Gradient gravity, curve resistance, journal-bearing breakaway.** Out of scope. The architecture admits additional rate terms cleanly if a future stage wants them.
- **Per-roster physics overrides.** `RosterEntry.getPhysicsWeightKg()` / `getPhysicsPowerKw()` / `getPhysicsTractiveEffortKn()` / `getPhysicsMaxSpeedKmh()` are **not consulted** by the engine — the throttle-fraction-rate model has no use for prototype mass / power / TE / top-speed values. The decoder + speed profile already handle physical top speed at `s = 1.0`. Per-loco rate-tuning overrides via roster attributes are deferred future work.
- **Deeper integration parameters configurable via UI.** The Settings tab exposes the per-scenario rate constants, the consist parameter, and the Westinghouse time constants as user-editable fields (in scope). Lower-level integration parameters (50 ms slice time, gear-pause thresholds and 3.5 s coast duration if used) stay hardcoded (out of scope).
- **EngineDriver's `Stop` button and its four behaviour modes.** The Stop button is an Android-touch UX device — useful when your only inputs are screen taps. On a RailDriver console the operator already has E-Stop (hard) and the Independent Brake handle (controlled) within reach. None of EngineDriver's four stop modes is adopted; the existing E-Stop SPDT keeps its current behaviour.
- **Tests.** Parent §4.5. Deferred.
- **Help / documentation updates.** Parent §4.6. Deferred.
- **Cross-platform verification** — community testers, not in scope.
- **Latent issues from parent §3 / §6** other than the off-EDT mutation, which is fixed as part of stage 1.

## 2. Architecture

### 2.1 New class: `SemiRealisticThrottleEngine`

Single-throttle engine. Owned by `RailDriverMenuItem`. Lifecycle parallels the polling thread: created lazily in `attachThrottleWindow` after `activeThrottleFrame` is set; disposed in `propertyChange`'s `"ancestor"` case alongside the `throttleDispatcher` deregistration.

The engine is built around a single integration loop that runs on a dedicated worker thread at a fixed 50 ms slice. The loop computes `ds/dt` (rate of change of the live DCC throttle setting `s`) per the **throttle-fraction-rate model from §1.0** — every coefficient is a rate in fraction/sec wall-clock — integrates `s`, clamps to [0, 1], and posts the result to the EDT for `setSpeedSetting`. **There is no separate "accel path" and "decel path"** — the same composition of drive / drag / brake rates runs every tick; whichever ones are non-zero determine `ds/dt`.

```java
public final class SemiRealisticThrottleEngine {
    // — Mode —
    private volatile boolean enabled;            // false => bypass (phase-3 direct setSpeedSetting path)

    // — Settings (loaded from calibration XML <semiRealistic> subtree) —
    private final SemiRealisticSettings settings;

    // — Operator-tunable rates and consist factors (resolved per session from scenario) —
    //   All rate fields are in fraction/sec wall-clock — apply directly to ds/dt.
    //   Consist factors ∈ [0, 1] dilute their respective rates for heavier consists.
    //   See §1.0 for the framing and §2.4.1 for per-scenario defaults.
    private volatile float accelRate;             // ds/dt while accelerating, no brake (e.g. 0.10 → 10s 0→full)
    private volatile float consistAccelFactor;    // 1.0 light engine, smaller for heavy consists
    private volatile float dragCoeff;             // ds/dt = dragCoeff·s during coast (linear in s)
    private volatile float consistDragFactor;     // 1.0 light engine, smaller for heavy consists
    private volatile float indepBrakeRate;        // ds/dt at 100% indep lever, before consist dilution
    private volatile float indepConsistFactor;    // smaller for heavy consist (loco-only effect)
    private volatile float autoBrakeRate;         // ds/dt at 100% cylinder pressure (post-Westinghouse)
    private volatile float dynBrakeRate;          // ds/dt at 100% dyn lever
    private volatile float dynConsistFactor;      // smaller for heavy consist (loco-only)
    private volatile float dynTaperThreshold;     // s below which dyn brake fades

    // — Westinghouse air-brake parameters (used in stage 4; ignored before) —
    private volatile int   consistCars;           // 0 = light engine bypass; >0 = state-machine active
    private volatile float tPipe;                 // pipe-pressure time constant (s, wall-clock)
    private volatile float tCylinderApply;        // cylinder fill time constant (s)
    private volatile float tCylinderRelease;      // cylinder vent time constant (s)
    private volatile float tAuxDrain;             // aux drain time during apply (s)
    private volatile float tRecharge;             // aux recharge time during release (s)
    private volatile float cylinderMagnification; // (aux − pipe) → cylinder pressure multiplier

    // — Live integration state (worker thread only, except where noted) —
    private float s = 0.0f;                       // current effective DCC throttle fraction
    // Westinghouse state (worker-thread-only):
    private float pipePressure = 1.0f;            // 1 = released full
    private float auxCharge = 1.0f;               // 1 = full charge
    private float cylinderPressure = 0.0f;        // 1 = full apply

    // — Latest physical inputs (set from polling thread; volatile) —
    private volatile float throttleAboveIdle;     // 0..1 above idle (drive region)
    private volatile float throttleBelowIdle;     // 0..1 below idle (dyn-brake region)
    private volatile float indepBrakeLever;       // 0..1 from Axis 3 (stage 2)
    private volatile float autoBrakeLever;        // 0..1 from Axis 2 (stage 4)
    private volatile boolean bailoffPressed;      // from byte 4 transient (stage 4)
    private volatile LoadScenario scenario;       // from picker (stage 2)
    private volatile int     direction;           // FORWARD / NEUTRAL / REVERSE from Axis 0

    // — Worker scheduler (50 ms fixed slice) —
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RailDriver-SemiRealistic-Physics");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> integrationTask;

    // — DccThrottle for setSpeedSetting / setIsForward / setFunction (acquired at attach time) —
    private DccThrottle throttle;
    ...
}
```

Per-tick logic (worker thread, every 50 ms while engine is enabled and either `s ≠ sTarget` or any brake is applied):

```java
final float DT = 0.050f;

// 1. Read input snapshot (volatile fields, set from polling thread)
float sTarget  = throttleAboveIdle;     // drive region: 0..1
float dynApply = throttleBelowIdle;     // dyn-brake region: 0..1, mutually exclusive with above
float indep    = indepBrakeLever;
float auto     = autoBrakeLever;
boolean bail   = bailoffPressed;

// 2. Westinghouse air-brake state machine (stage 4 only; bypassed for light engine)
//    Updates pipePressure, auxCharge, cylinderPressure; produces cylinderPressure for use below.
if (consistCars > 0) {
    float pipeTarget = 1.0f - auto;
    pipePressure += (pipeTarget - pipePressure) * DT / tPipe;
    if (pipePressure < auxCharge) {
        // Apply: aux feeds cylinder
        float desiredCyl = clamp01((auxCharge - pipePressure) * cylinderMagnification);
        cylinderPressure += (desiredCyl - cylinderPressure) * DT / tCylinderApply;
        auxCharge -= cylinderPressure * DT / tAuxDrain;
    } else {
        // Release: cylinder vents fast, aux recharges slow
        cylinderPressure -= DT / tCylinderRelease;
        auxCharge += (pipePressure - auxCharge) * DT / tRecharge;
    }
    auxCharge        = clamp01(auxCharge);
    cylinderPressure = clamp01(cylinderPressure);
} else {
    // Light engine: direct lever → cylinder mapping; pipe/aux tracked for status only
    cylinderPressure = auto;
    pipePressure     = 1.0f - auto;
    auxCharge        = 1.0f;
}
float autoForce = bail ? 0.0f : cylinderPressure;

// 3. Compute rate composition (all in fraction/sec wall-clock)
float driveRate = (sTarget > s) ? accelRate * consistAccelFactor : 0.0f;
float dragRate  = dragCoeff * s * consistDragFactor;
float mechRate  = indep * indepBrakeRate * indepConsistFactor;
float autoRate_ = autoForce * autoBrakeRate;                          // not consist-scaled
float dynTaper  = (dynTaperThreshold > 0) ? Math.min(1.0f, s / dynTaperThreshold) : 1.0f;
float dynRate   = (s > 0) ? dynApply * dynBrakeRate * dynConsistFactor * dynTaper : 0.0f;

// 4. Integrate
float dsdt = driveRate - dragRate - mechRate - autoRate_ - dynRate;
s += dsdt * DT;
s = clamp01(s);

// 5. Emit on EDT
final float sFinal = s;
ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(sFinal));
```

Key behaviours that fall out of the rate-vs-rate composition:

- **Throttle lever at X % → loco eventually reaches `s = X / 100`.** Drive rate raises `s` until `s ≥ sTarget`; then `driveRate = 0` and only drag + brakes act. With no brakes, `s` settles at `sTarget` (drag pulls it down briefly, drive picks it back up — net zero at equilibrium).
- **Full throttle against full brake → loco doesn't move.** At `s = 0`, `driveRate = accelRate · consistAccel`, but `mechRate + autoRate ≥ accelRate · consistAccel` whenever brake levers are at full. `dsdt ≤ 0` while `s = 0`, so `s` stays at 0. No motion.
- **Heavier consist accelerates more slowly.** `consistAccelFactor` < 1 for heavy scenarios → smaller `driveRate` → slower rise.
- **Heavier consist coasts slower.** `consistDragFactor` < 1 → smaller `dragRate` → slower decay.
- **Indep brake less effective on heavy train.** `indepConsistFactor` < 1 (e.g. 0.15 for unit train) so even full indep lever produces a small `mechRate`.
- **Auto brake propagates through trainline.** No consist factor on `autoRate` — full strength on every consist. Westinghouse aux-charge dynamics produce the "running out of air" feel naturally.
- **Top speed is whatever the decoder produces at `s = 1.0`.** The engine never caps `s` below 1. There is no `designTopSpeed`, no roster `maxSpeedKmh` clamp, no asymptote.

Roster fields (`getPhysicsWeightKg`, `getPhysicsPowerKw`, `getPhysicsTractiveEffortKn`, `getPhysicsMaxSpeedKmh`) are **not consulted** — the throttle-fraction-rate model has no use for prototype values, and the decoder + speed profile already handle physical loco speed at the DCC layer.

#### Worked example: wall-clock outcomes at default Light-engine constants

Defaults: `accelRate = 0.10`, `consistAccelFactor = 1.0`, `dragCoeff = 0.05`, `consistDragFactor = 1.0`, `indepBrakeRate = 0.30`, `indepConsistFactor = 1.0`. All values dimensionless rates (fraction/sec wall-clock).

| Phenomenon | Formula | Wall-clock outcome |
|---|---|---:|
| 0 → full under throttle, no brake, light engine | `s = accelRate · t − ∫dragCoeff·s dt` (linear approach to s = 1 against light drag) | **~13 s to reach s = 0.95** |
| Full → 0 coast (drop throttle to idle) | `ds/dt = −0.05·s` → exponential, time const 20 s | **~60 s to s = 0.05** (e<sup>−3</sup>) |
| Full → 0 with full indep brake | `ds/dt = −0.30 − 0.05·s` (s−term tiny) | **~3.3 s** |
| Full throttle, 50 % indep | `ds/dt = 0.10 − 0.15 − 0.05·s = −0.05 − 0.05·s` (always negative) | loco never accelerates; coasts to halt |
| Full throttle, 30 % indep | `ds/dt = 0.10 − 0.09 − 0.05·s` (positive at low s, zero around s = 0.2) | loco settles at s ≈ 0.2 |
| Full throttle, 100 % indep | `ds/dt = 0.10 − 0.30 − 0.05·s = −0.20 − 0.05·s` | loco doesn't move at all from s = 0 |
| At-rest (s = 0), throttle 50 %, no brake | `ds/dt = 0.10 − 0 = 0.10` | starts moving immediately |

Heavier scenarios scale `accelRate` and `consistDragFactor` down, so unit train at full throttle takes ~30 s to s = 0.95 and coasts down with a 60+ s time constant. Indep brake on unit train (consistFactor 0.15) is much weaker — at full lever, `mechRate = 0.30 × 0.15 = 0.045`, comparable to the drag term, so indep alone won't hold a moving unit train. The auto brake (no consist factor, full strength) is what stops it.

The worked-example numbers above are the simple analytical outcomes; the actual integrated behaviour matches because the dynamics are linear or near-linear in `s`.

### 2.2 Threading model

Three threads are involved. The EDT-discipline boundary is strict: **no Swing-touching code runs off the EDT, anywhere.** The pre-existing off-EDT mutation in the RailDriver bring-up code (parent §3 / §6) is fixed as part of stage 1 — see §3.1 for the wrapping work.

| Thread | What it does | What it must NOT do |
|---|---|---|
| **Polling thread** (`RailDriver`, existing) | Reads HID reports, fires `RawByte` and `Value` PCS events. `dispatchValueEvent` runs here as a PCS listener. | Touch any Swing component or call any method that reaches Swing (`setSpeedSetting`, `setIsForward`, `setFunction`, `JMenuItem.setEnabled`, etc.). |
| **Engine worker** (`RailDriver-SemiRealistic-Physics`, new) | Owned by the engine's single-thread `ScheduledExecutorService`. Runs the 50 ms integration tick. **Reads input snapshot, computes rates, integrates `s`, runs the Westinghouse state machine here** — all the math runs on this thread. | Touch Swing. |
| **EDT** (Swing's own thread) | All Swing-touching work: `setSpeedSetting`, `setIsForward`, `setFunction`, LED updates that route through Swing components, status-label changes, etc. | Block on the worker or polling thread (no `invokeAndWait`; would deadlock). |

The contract:

- `dispatchValueEvent` (polling thread) updates the engine's input fields (`throttleAboveIdle`, `throttleBelowIdle`, `indepBrakeLever`, `autoBrakeLever`, `bailoffPressed`, `direction`, `scenario`) — all `volatile`. **No Swing calls in the polling-thread path.** The integration task is permanently scheduled at fixed rate (50 ms); it picks up the new inputs on its next tick. **No cancel/reschedule churn** — the inputs are just `volatile` writes the worker reads on the next tick.

- The integration task's body runs on the engine worker. It computes `ds/dt` locally and integrates `s`:
  ```java
  float dsdt = driveRate - dragRate - mechRate - autoRate_ - dynRate;
  s += dsdt * 0.050f;       // worker thread, no Swing
  s = clamp01(s);
  ```
  Only after the math is final does it hand the result to the EDT:
  ```java
  ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(s));
  ```
  This keeps the *cadence* governed by the worker's `ScheduledExecutorService` (so timing is precise) and only the *application* of the value bounces through the EDT queue (so Swing stays consistent). If the EDT is busy, the lambda waits in the queue but the worker's next tick is unaffected — at worst the user sees one display-update of latency, never a missed integration slot.

- `dispatchValueEvent`'s phase-3 fall-back path (when semi-realistic mode is OFF) also wraps every Swing-touching call appropriately — see §3.1 deliverables. **Setters** (`throttle.setX`, `addressPanel.setX`, `throttleWindow.nextThrottleFrame` etc.) become `ThreadingUtil.runOnGUIEventually(() -> throttle.setX(...))` — fire-and-forget. **Getters whose return values feed the surrounding decision** (`throttle.getFunctions()`, `throttle.getFunctionMomentary(fNum)`, `throttle.getFunction(fNum)`) become `ThreadingUtil.runOnGUIwithReturn(() -> throttle.getFunctions())` — synchronous round-trip, blocks the polling thread for the duration of one EDT lambda. The math/decision logic stays where it is.

  We standardise on `ThreadingUtil` rather than direct `SwingUtilities.invokeLater` for consistency with the rest of JMRI (`jmri.jmrit.logix.Engineer`, `jmri.jmrit.throttle.AddressPanel`, etc. all use `ThreadingUtil`). `runOnGUIEventually(ta)` is a one-line wrapper around `SwingUtilities.invokeLater(ta)` (see `ThreadingUtil.java:262`) — runtime behaviour is bytecode-equivalent. `runOnGUIwithReturn(ta)` (`ThreadingUtil.java:223`) wraps `SwingUtilities.invokeAndWait` plus a `Reference<T>` shim, which `SwingUtilities` doesn't expose directly — so the synchronous-getter case needs `ThreadingUtil` regardless of stylistic preference. The single existing `SwingUtilities.invokeLater` call site in `RailDriverMenuItem.java` (line 244, in `attachThrottleWindow`) is migrated to `ThreadingUtil.runOnGUIEventually` as part of stage 1; the import for `javax.swing.SwingUtilities` is removed.

- The volatile input fields (`throttleAboveIdle`, `throttleBelowIdle`, `indepBrakeLever`, `autoBrakeLever`, `bailoffPressed`, `direction`) are written by the polling thread and read by the worker — single-writer, single-reader, no interleaved compound ops, so volatile semantics suffice. The volatile rate / consist-factor / Westinghouse-time-constant fields are written by the EDT (in `rebuildPhysics` after Save / scenario-change / attach) and read by the worker — same single-writer-single-reader pattern. The worker reads the relevant fields at the start of each tick and uses the read values for the rest of the tick body, so a mid-tick settings change can't produce torn calculations. `s` is normally touched only by the worker thread (no sync needed for tick-to-tick updates), **but** the mode-switch handover (§2.3) writes `s` from the EDT inside `engine.setLiveEnabled()`, and the reverser interlock (§3.3) reads `s` from the polling thread. All non-worker accesses to `s` go through the engine's `synchronized` block; `s` is also declared `volatile` so the polling-thread read sees the latest worker write without entering the monitor. The Westinghouse-state fields (`pipePressure`, `auxCharge`, `cylinderPressure`) are worker-thread-only; they are not exposed outside the tick body.

### 2.3 Bypass-mode wiring and mode-switch handover

When the engine's live `enabled` flag is `false`, the integration task is paused (or the integrator's body is a no-op) and `dispatchValueEvent` falls back to the bring-up-era direct `setSpeedSetting` / `setIsForward` / `setFunction(0, ...)` path. **No behavioural change for users who don't opt in.** The mode is per-profile; the persisted-on-disk default is OFF.

#### Two distinct values: persisted vs. live

The `enabled` flag is tracked as **two separate values**:

- **`persistedEnabled`:** the value last loaded from the calibration XML (or the default OFF when no XML exists), or the value most recently written to the XML by Save/Apply. This is the value the Settings tab displays and edits.
- **`liveEnabled`:** the value the engine and Jynstrument act on. Initialised from `persistedEnabled` at attach time and after `reloadCalibration()`. Mutated by either the Settings tab Save/Apply (which also updates `persistedEnabled`) or the Jynstrument click (which mutates only `liveEnabled`).

The Settings tab and the Jynstrument therefore display **different values** when the operator has used the Jynstrument session-toggle since the last Save: the Settings tab shows the persisted value (what's on disk and what would load at next launch), the Jynstrument shows the live value (what the engine is actually doing right now). This is by design — the Settings tab is the persisted-state view, the Jynstrument is the session-state view.

#### Mutation paths and persistence semantics

The two mutation paths have different effects:

- **Settings window (`Enable semi-realistic mode` checkbox + Save/Apply):** edits `persistedEnabled` (in-window, dirty-tracked); on Save/Apply, writes XML, sets `liveEnabled = persistedEnabled`, and notifies the engine. **Cancel** discards the in-window edit; `persistedEnabled` and `liveEnabled` are unchanged.
- **Jynstrument toolbar click:** flips `liveEnabled` only. Does **not** touch `persistedEnabled`, does **not** write to disk, does **not** update the Settings tab's checkbox display. Resets to `persistedEnabled` at next JMRI launch (or at the next `attachThrottleWindow()` after a fresh `reloadCalibration()`).

The split lets the operator opt in via the Settings window and then flip the mode mid-session via the toolbar without polluting the persisted preference, while keeping the Settings window's display unambiguous: the checkbox always shows what is (or would be) on disk, never the volatile session value.

If the operator wants a session-level Jynstrument change to become the new persisted default, they open the Settings window, manually flip the checkbox to match, and Save. This is one extra click compared to the previous (rejected) "checkbox tracks live value" model, but eliminates the ambiguity about what the checkbox represents.

#### Mode-switch handover at speed

The operator can flip the mode toggle at any time, including while the loco is moving. The engine's response (when `liveEnabled` changes for any reason):

- **OFF → ON:** at the moment `liveEnabled` flips to true, the engine reads `throttle.getSpeedSetting()` once (on the EDT) and writes that value into `engine.s` (under the engine's `synchronized` block). It also resets the Westinghouse state to the released-but-charged state (`pipePressure = 1`, `auxCharge = 1`, `cylinderPressure = 0`) so the operator's first auto-brake application starts from a known good state. Then refreshes the input snapshot from the current lever positions. The integration task starts ticking; on each tick it computes rate from current inputs and walks `s` toward `sTarget`. If the lever's target is far from the loco's current setting, the loco accelerates or decelerates under physics until it gets there. **No setting snap.** The loco's perceived speed is continuous across the toggle.

- **ON → OFF:** at the moment `liveEnabled` flips to false, the engine pauses its integration task and `dispatchValueEvent` reverts to the direct path. The very next byte change on any axis writes the lever-derived value via `setSpeedSetting()` directly. **If the lever is far from the engine's last `s`, the loco will snap to the lever-derived setting on the next dispatch.** Operators are expected to either move the lever to match the loco's current setting before flipping OFF, or to accept the snap as the cost of switching to direct control mid-motion.

#### Implementation: API entry points

There are **two** API entry points on `RailDriverMenuItem`, distinguished by persistence:

```java
// Settings window Save/Apply: writes XML, then sets liveEnabled = persistedEnabled.
public void applyPersistedEnabled(boolean enabled);

// Jynstrument toolbar click: mutates liveEnabled only.
public void setSemiRealisticEnabledSessionOnly(boolean enabled);
```

Both setters perform the engine handover (steps 1–3 below) when `liveEnabled` actually changes; they only differ on whether they touch `persistedEnabled` and the XML.

1. Compute new `liveEnabled` value on whichever thread called the setter (always EDT — both paths run on the EDT).
2. If `liveEnabled` is transitioning OFF → ON: read `throttle.getSpeedSetting()` on the EDT, post a `Runnable` to the engine that sets `s` to that fraction, resets the Westinghouse state to released-and-charged, and starts the integration task under `synchronized`.
3. If `liveEnabled` is transitioning ON → OFF: post a `Runnable` to the engine that pauses the integration task.
4. Update the in-memory `settings.liveEnabled` field.
5. **`applyPersistedEnabled` only:** also update `settings.persistedEnabled`, persist the calibration XML, and fire `"persistedEnabledChanged"` so any open Settings tab refreshes its displayed value (relevant only when the persisted value changes due to a Save from a different code path — implementation symmetry).
6. Fire the `"liveEnabledChanged"` PropertyChange (both setters) so the engine and the Jynstrument icon update.

Two distinct PCS event names — `"persistedEnabledChanged"` and `"liveEnabledChanged"` — are wired to distinct observer sets. The Settings tab's checkbox subscribes to `"persistedEnabledChanged"` only; the Jynstrument and the engine subscribe to `"liveEnabledChanged"` only. This guarantees the Settings tab never reflects a session-only Jynstrument toggle, and the Jynstrument never reflects an unsaved Settings-tab edit (because unsaved edits live in the tab's local pending-edit state, not in either of the canonical fields).

Repeated rapid toggles are safe — each transition is idempotent and serialised through the engine's `synchronized` block.

#### Auto-install of the toolbar Jynstrument

The auto-install of the toolbar Jynstrument has no XML state. The only persistence channel for the toolbar's *presence* is JMRI's existing throttle-layout XML (`ThrottleWindow.java:800–865`), saved via the throttle window's standard "Save throttle layout" workflow. Same workflow as for every other Jynstrument in JMRI.

### 2.4 Unified Settings window

**Decision: the existing phase-3 calibration window and the new semi-realistic settings UI are merged into a single two-tab `RailDriverSettingsFrame`.** Going forward there is exactly one Debug-menu entry for RailDriver configuration, and one window the operator opens to adjust either set of values.

Menu entry: `Debug → RailDriver Settings...` (replaces the current `Debug → RailDriver Calibration...` entry).

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

The Settings tab is grouped into five sections matching the §1.0 rate categories so the operator sees what they're tuning:

**Mode**
- **Enable semi-realistic mode** checkbox (the master switch).

**Loco scenario** (feel-rate preset)
- **Scenario:** dropdown — `Light engine` (default) / `Switcher` / `EMD NW2` / `Local freight` / `Through freight` / `Unit train` / `Custom` (with the per-rate override fields below editable when `Custom` is selected). Switching scenarios applies that scenario's tuned rates and consist factors to all fields below.

**Drive** (operator-tunable acceleration rate)
- **Acceleration rate (fraction/s):** numeric, scenario default. How fast `s` rises toward `sTarget` while accelerating, with no brakes. Default 0.10 = full lever takes 10 s wall-clock to reach `s = 1.0` from rest. Mass-independent — operators tune for desired snappiness.
- **Consist accel factor (0..1):** numeric, scenario default. Multiplies the acceleration rate to mimic "heavier consist accelerates more slowly." 1.0 for light engine, smaller for heavy consists (e.g. 0.3 for unit train). Operator can override for one-off heavy/light trains.

**Coast** (rolling drag — single linear-in-s rate; no aerodynamic term per operator request)
- **Rolling drag rate (1/s):** numeric, scenario default. `ds/dt = −dragCoeff · s` during coast. Default 0.05 = decay time constant 20 s for full → almost-zero coast on a light engine.
- **Consist drag factor (0..1):** numeric, scenario default. Heavier consist coasts slower. 1.0 for light engine, smaller for heavy consists.

**Brakes** (rate-of-change in fraction/s wall-clock; subtracted from `ds/dt`)
- **Independent brake rate (fraction/s):** numeric, scenario default. Max indep-brake rate at 100 % lever, before consist dilution. Default 0.30. **Indep brake is loco-only**, so on heavy consists `indepConsistFactor` makes it ineffective (matches the operator's description).
- **Independent consist factor (0..1):** numeric, scenario default. Multiplies indep brake rate. 1.0 light engine, ~0.15 unit train.
- **Auto brake rate (fraction/s):** numeric, scenario default. Max rate at 100 % cylinder pressure (post-Westinghouse). Default 0.50. **Auto brake propagates through trainline**, so no consist factor — full strength on every consist.
- **Dynamic brake rate (fraction/s):** numeric, scenario default. Max rate at 100 % dyn lever. Default 0.20.
- **Dynamic consist factor (0..1):** numeric, scenario default. Loco-only effect; heavy consists dilute it.
- **Dynamic taper threshold (s):** numeric, scenario default. Below this `s` value the dyn brake fades linearly to zero (real dyn brake fades at low speed).

**Westinghouse air brake** (consist > 0; ignored for light-engine scenarios)
- **Consist cars:** integer 0..200, scenario default. 0 = light engine, bypasses the Westinghouse state machine. >0 enables the pipe / aux / cylinder dynamics.
- **Pipe time constant (s):** wall-clock seconds for `pipePressure` to track the lever target. Larger for longer trains. Default 2.0.
- **Cylinder apply time constant (s):** how fast cylinder pressure responds to triple-valve "apply." Default 0.5.
- **Cylinder release time constant (s):** how fast cylinder vents on release. Default 1.0.
- **Aux drain time constant (s):** how fast aux reservoir drains while feeding cylinder. Default 30. Larger = more applications before "running out of air."
- **Aux recharge time constant (s):** how fast aux recharges from main pipe during release. Default 60. Larger = longer wait between heavy applications.
- **Cylinder magnification:** multiplier on (aux − pipe) to produce cylinder pressure. Default 2.5.

**Decoder integration**
- **Decoder-brake mode:** dropdown `None` / `ESU` (and ESU-only sub-fields when ESU is selected — F-numbers + thresholds).

**Reset to defaults** button (settings-tab-scoped — restores only the semi-realistic fields to the active scenario's defaults; does not touch calibration values).

#### 2.4.1 Scenario defaults

Scenarios are **feel-rate presets**, not prototype simulations. Each scenario is tuned for a distinct operator experience: Light Engine = nimble responsive single loco; Switcher = slower yard-feel; Local Freight = modestly weighted; Through Freight = sluggish, plan ahead; Unit Train = even more so. Switching scenarios is the operator's "weight selector" — heavier scenarios feel heavier because the rates and consist factors say so.

Drive / Coast / Brake rates (all in fraction/sec wall-clock):

| Scenario | accelRate | consistAccel | dragCoeff | consistDrag | indepRate | indepConsist | autoRate | dynRate | dynConsist | dynTaper |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Light engine (default)** | 0.10 | 1.00 | 0.05 | 1.00 | 0.30 | 1.00 | 0.50 | 0.20 | 1.00 | 0.05 |
| Switcher | 0.10 | 1.00 | 0.06 | 1.00 | 0.30 | 1.00 | 0.50 | 0.10 | 1.00 | 0.05 |
| EMD NW2 | 0.10 | 1.00 | 0.05 | 1.00 | 0.30 | 1.00 | 0.50 | 0.00 | 1.00 | 0.05 |
| Local freight | 0.07 | 0.70 | 0.04 | 0.70 | 0.30 | 0.50 | 0.50 | 0.20 | 0.50 | 0.05 |
| Through freight | 0.05 | 0.50 | 0.03 | 0.50 | 0.30 | 0.30 | 0.50 | 0.20 | 0.30 | 0.05 |
| Unit train | 0.03 | 0.30 | 0.02 | 0.30 | 0.30 | 0.15 | 0.50 | 0.20 | 0.10 | 0.05 |
| Custom | (operator-tunable; defaults inherited from Light engine on first switch) | | | | | | | | | |

Westinghouse air-brake parameters (all in seconds wall-clock; `cars` is integer):

| Scenario | cars | T_pipe | T_cylApply | T_cylRelease | T_auxDrain | T_recharge | cylMagnification |
|---|---:|---:|---:|---:|---:|---:|---:|
| Light engine | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| Switcher | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| EMD NW2 | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| Local freight | 15 | 2.0 | 0.5 | 1.0 | 30 | 60 | 2.5 |
| Through freight | 50 | 4.0 | 0.5 | 1.5 | 25 | 90 | 2.5 |
| Unit train | 100 | 6.0 | 0.7 | 2.0 | 20 | 120 | 2.5 |
| Custom | (operator-tunable) | | | | | | |

For light-engine scenarios (cars = 0), the Westinghouse state machine is bypassed: `cylinderPressure = autoBrakeLever` directly. The Westinghouse parameters are not editable on the Settings tab when cars = 0 (greyed out).

**EMD NW2 scenario** matches the operator's test loco — a real EMD NW2 switcher (1000 HP / 256 kN / 112 t / max 65 mph, typically geared 45 mph). The engine never imposes a top-speed cap; the decoder + speed profile already determine the loco's top physical speed at `s = 1.0`. NW2 has `dynRate = 0` because most NW2 units shipped without dynamic brake — the lever's dyn-brake region produces no decel. Operators with dyn-brake-equipped NW2s can switch to Custom and tune.

Roster fields are not consulted by the engine — the scenario is the source of truth. The decoder + speed profile remain the sole authority for physical loco top speed at `s = 1.0`.

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
6. Call `RailDriverMenuItem.reloadCalibration()` so the polling thread + (when stage 2 lands) the semi-realistic engine pick up the new values without restart.
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
        <scenario>Light engine</scenario>
        <!-- Drive (rate fraction/sec, plus consist factor) -->
        <accelRate>0.10</accelRate>
        <consistAccelFactor>1.0</consistAccelFactor>
        <!-- Coast (linear-in-s rate, plus consist factor) -->
        <dragCoeff>0.05</dragCoeff>
        <consistDragFactor>1.0</consistDragFactor>
        <!-- Brakes (rate fraction/sec; per-brake consist factors) -->
        <indepBrakeRate>0.30</indepBrakeRate>
        <indepConsistFactor>1.0</indepConsistFactor>
        <autoBrakeRate>0.50</autoBrakeRate>
        <dynBrakeRate>0.20</dynBrakeRate>
        <dynConsistFactor>1.0</dynConsistFactor>
        <dynTaperThreshold>0.05</dynTaperThreshold>
        <!-- Westinghouse (consistCars=0 disables the state machine) -->
        <consistCars>0</consistCars>
        <tPipe>2.0</tPipe>
        <tCylinderApply>0.5</tCylinderApply>
        <tCylinderRelease>1.0</tCylinderRelease>
        <tAuxDrain>30</tAuxDrain>
        <tRecharge>60</tRecharge>
        <cylinderMagnification>2.5</cylinderMagnification>
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

Schema migration: existing files (`version="1"`) load cleanly because the loader's tolerance for missing elements treats `<semiRealistic>` as absent ⇒ defaults (= disabled, no behaviour change for legacy files). Within `version="2"`, individual children that are missing or unparseable fall back to the active scenario's defaults. **Pre-throttle-fraction-refactor stage-2 files (containing `<locoMassKg>`, `<locoPowerKw>`, `<locoTractiveEffortKn>`, `<additionalWeightTonnes>`, `<rollingResistanceCoeff>`, `<rollingResistanceStatic>`, `<rollingResistanceLinear>`, `<aerodynamicDragCoeff>`, `<physicsTimeScale>`, `<maxAccelAtRestMs2>`, `<vCornerMps>`, `<designTopSpeedMps>`, `<steam>`, `<resistStaticMs2>`, `<resistLinearPerSec>`, `<resistQuadPerMeter>`, `<brakeMaxDecelMs2>`, `<airBrakeMaxDecelMs2>`, `<dynBrakeMaxDecelMs2>`, `<dynBrakeMassFraction>`, `<dynBrakeVMinMph>`, `<driverPowerPercent>`) are not migrated** — the rate-based refactor replaces every coefficient with a different unit, and existing development calibrations are expected to be deleted before testing. Those legacy elements are silently ignored on load; the rate fields fall back to scenario defaults.

### 2.6 Throttle-toolbar mode toggle (Jynstrument)

The Settings window's `Enable semi-realistic mode` checkbox is the authoritative toggle, but burying it inside a tabbed dialog opened from the Debug menu is too much friction for a setting an operator might flip multiple times per session (e.g. switching between yard-switching mode and over-the-road mode). A second, faster path lives directly on the throttle window's toolbar.

JMRI ships a documented extension framework called **Jynstruments** [[`jython/Jynstruments/README`](../../jython/Jynstruments/README)] for exactly this purpose. A `.jyn` folder containing a Jython class extending `jmri.jmrit.jython.Jynstrument` (which itself extends `JPanel`) can be installed onto the throttle window's toolbar; JMRI calls its `init()` and adds the panel as a toolbar item. The framework supports right-click popup menus and persists installed Jynstruments in the throttle layout XML. ~12 Jynstruments ship with JMRI today, all between 35-110 lines of Jython.

#### What the Jynstrument exposes

A single toolbar button:
- **Icon** — green when semi-realistic mode is ON, grey when OFF, plus a transient "binding…" icon during the State 2 → State 4 handover. Three PNGs ship with the `.jyn` folder.
- **Click** — toggles `settings.enabled` **for the current session only**; does **not** write the calibration XML. Notifies the engine + Settings window so all three stay coherent in memory. Persistent changes go through the Settings window (§2.3).
- **Right-click** — popup with one item: `Settings...` → opens the unified Settings frame to the Settings tab (same `RailDriverSettingsAction` used by the Debug menu).
- **Tooltip** — `RailDriver semi-realistic throttle: ON / OFF (session)`.

Scope is intentionally narrow: the Jynstrument is the mode-toggle UI, not a full RailDriver control panel. The Debug menu remains the entry point for first-time attach; everything else (calibration, advanced settings, persistent enable) goes through the Settings window.

#### Java-side support

`RailDriverMenuItem` gains a small public API to support the Jynstrument (and any future toolbar / status surface):

```java
public boolean isSemiRealisticLiveEnabled();                   // current liveEnabled value (engine + Jynstrument)
public boolean isSemiRealisticPersistedEnabled();               // current persistedEnabled value (Settings tab)
public void applyPersistedEnabled(boolean enabled);             // Settings tab Save/Apply: persistedEnabled := enabled, write XML, liveEnabled := persistedEnabled, notify
public void setSemiRealisticEnabledSessionOnly(boolean enabled); // Jynstrument click: liveEnabled := enabled, notify (no XML write)

public boolean isRailDriverConnected();                 // device present in USB
public ThrottleFrame getActiveThrottleFrame();          // currently bound throttle, or null
public boolean isAttachInProgress();                    // true between requestAttachToThrottle() and the resulting "activeThrottleFrame" PCS event
public void requestAttachToThrottle(ThrottleFrame tf);  // ensureDeviceAndPolling + attachThrottleWindow against tf, asynchronous

public void addSettingsListener(PropertyChangeListener l);
public void removeSettingsListener(PropertyChangeListener l);
```

PCS events fired on the settings listener:
- `"liveEnabledChanged"` — `newValue` = boolean. Fired by both `applyPersistedEnabled` and `setSemiRealisticEnabledSessionOnly` whenever `liveEnabled` actually changes. The engine listens to drive the integration tick on/off; the Jynstrument listens to update its icon. The Settings tab does **not** subscribe to this event — its checkbox is bound to `persistedEnabled` and pending-edit state.
- `"persistedEnabledChanged"` — `newValue` = boolean. Fired by `applyPersistedEnabled` only, after the XML write. The Settings tab subscribes to refresh its checkbox display when the persisted value changes via a code path other than the in-window edit (e.g. a `reloadCalibration()` triggered externally).
- `"railDriverConnected"` — `newValue` = boolean. Fired from the existing `HidServicesListener` callbacks (`hidDeviceAttached` / `hidDeviceDetached`) when our VID/PID is involved. **Note:** the existing `hidDeviceAttached` body in `RailDriverMenuItem.java:539–546` is currently commented out — earlier bring-up plans gated auto-`setupRailDriver()` on a now-removed `invokeOnMenuOnly` flag. Stage 2 re-enables a *minimal* version of that body that fires `"railDriverConnected"` on VID/PID match **without** auto-calling `setupRailDriver()` (the Debug-menu workflow keeps owning the polling lifecycle, so cold-plug behaviour is unchanged for users who don't have the Jynstrument installed). `hidDeviceDetached` already nulls `hidDevice` on VID/PID match; stage 2 adds a `firePropertyChange` next to that line.
- `"activeThrottleFrame"` — `oldValue` and `newValue` = `ThrottleFrame` (either may be null). Fired whenever `attachThrottleWindow()` binds a new frame or the existing frame is detached. Lets each Jynstrument tell whether IT is the bound one.
- `"attachInProgress"` — `newValue` = boolean. Fired around the `requestAttachToThrottle()` async window: `true` when the async attach starts, `false` when the resulting `"activeThrottleFrame"` event has been delivered. Lets the Jynstrument display the transient "binding…" icon and ignore extra clicks during the window.

(Standard observer pattern; no risk of feedback loops on `semiRealisticEnabled` because PCS doesn't fire when old equals new.)

#### Jynstrument click behaviour by state

The Jynstrument can find itself in one of five states at any moment. Behaviour for each:

| # | State | Trigger | Click behaviour | Visual |
|---|---|---|---|---|
| 1 | **No device** | `isRailDriverConnected() == false` | No-op | Greyed icon, tooltip `"RailDriver not detected"` |
| 2 | **Device present, no throttle bound** | `isRailDriverConnected() == true` && `getActiveThrottleFrame() == null` && `!isAttachInProgress()` | **Auto-bootstrap with deferred toggle:** call `requestAttachToThrottle(getContext())` (where `getContext()` is the Jynstrument's `ThrottleWindow`'s current `ThrottleFrame`); set the Jynstrument's local `pendingSessionToggle = true`. The toggle is applied later, when the `"activeThrottleFrame"` PCS event arrives confirming this Jynstrument's frame is bound (transition to state 4). | Normal icon + tooltip `"Click to attach RailDriver to this throttle and toggle semi-realistic mode"` |
| 2.5 | **Attach in progress** | `isAttachInProgress() == true` | No-op (extra clicks during the attach window are absorbed) | Transient "binding…" icon, tooltip `"RailDriver attaching to this throttle…"` |
| 3 | **Device present, bound to a different throttle** | `isRailDriverConnected() == true` && `getActiveThrottleFrame() != null` && `getActiveThrottleFrame() != this` | No-op | Greyed icon, tooltip `"RailDriver already bound to another throttle window"` |
| 4 | **Fully operational** | `isRailDriverConnected() == true` && `getActiveThrottleFrame() == this` | `setSemiRealisticEnabledSessionOnly(!isSemiRealisticLiveEnabled())` | On/off icon per current `liveEnabled` state; tooltip `"RailDriver semi-realistic throttle: ON / OFF (session)"` |

The Jynstrument re-evaluates state on every PCS event (`railDriverConnected`, `activeThrottleFrame`, `attachInProgress`, `liveEnabledChanged`) and updates its icon / tooltip / enabled-ness accordingly.

**State 2 → State 4 deferred-toggle handling:** because `requestAttachToThrottle` is **asynchronous** (the actual binding happens inside a `ThreadingUtil.runOnGUIEventually` posted from the polling-lifecycle code, which can race with other EDT work), the Jynstrument cannot rely on the bind being complete by the time its click handler returns. Instead:

1. State-2 click: call `requestAttachToThrottle(getContext())`; set `pendingSessionToggle = true`. Visual transitions to State 2.5 (binding…) on the `"attachInProgress"` PCS event.
2. The polling-lifecycle code completes the bind, fires `"activeThrottleFrame"` with `newValue == getContext()`, then fires `"attachInProgress"` with `newValue == false`.
3. The Jynstrument's `"activeThrottleFrame"` listener sees that `newValue == this.getContext()` and `pendingSessionToggle == true`; it calls `setSemiRealisticEnabledSessionOnly(!isSemiRealisticLiveEnabled())` and clears the flag. Visual transitions to State 4.
4. If `newValue` is some *other* throttle frame (e.g. the user clicked the toggle on Jynstrument A but binding ended up on Jynstrument B's throttle), `pendingSessionToggle` is cleared without action — Jynstrument A returns to State 3.
5. If the user clicks the Jynstrument *again* during State 2.5, the click is absorbed (no-op) — `pendingSessionToggle` stays at its existing value, no re-issued `requestAttachToThrottle`.

State 2's "if not bound elsewhere" check is implicit in the table: the precondition `getActiveThrottleFrame() == null` means there is no other throttle to displace. If RailDriver is already bound somewhere else, the Jynstrument is in State 3 and clicking does nothing.

#### Bootstrap / install

The `.jyn` folder ships in JMRI's standard tree at `jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/`. To avoid forcing the operator to drag-and-drop on first use, `RailDriverMenuItem.attachThrottleWindow()` auto-installs it after a successful bind:

```java
// In attachThrottleWindow, after binding succeeds:
String jynPath = FileUtil.getProgramPath()
    + "jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn";
if (!hasJynstrumentInstalled(throttleWindow, "RailDriverModeToggle")) {
    throttleWindow.ynstrument(jynPath);
}
```

`hasJynstrumentInstalled(ThrottleWindow tw, String classNameSuffix)` is a recursive descent over `tw.getContentPane().getComponents()`: at each `Container` it walks children, at each `JToolBar` it inspects the `Jynstrument` instances it contains and matches by class-name suffix. (`ThrottleWindow.throttleToolBar` is private with no public getter — verified at `ThrottleWindow.java:57` — so we can't index it directly; the recursive walk is the supported workaround.) The same Jynstrument-walking pattern is established precedent inside `ThrottleWindow` itself: the close-handler at `ThrottleWindow.java:160–167` and the save-Jynstruments code at `ThrottleWindow.java:801–810` both iterate `throttleToolBar.getComponents()` and `instanceof Jynstrument`-check each child. Our walk extends the pattern only by adding the recursive descent (because we don't have direct access to the toolbar reference).

The auto-install is idempotent within a single `attachThrottleWindow()` call via the `hasJynstrumentInstalled` pre-check. **It does not track removal in any persisted state** — there is no `<jynstrumentAutoInstallSuppressed>` flag and no calibration-XML state for the toolbar's presence. The only gate on auto-install is `hasJynstrumentInstalled`, which catches both (a) repeat attaches within a session where the toggle is already present, and (b) the case where saved-layout-XML restoration (`ThrottleWindow.java:800–865`) already installed the toggle before our auto-install check runs.

**Within a single session,** if the operator removes the Jynstrument via right-click → Quit, the toggle stays gone for the rest of that throttle window's lifetime — `quit()` runs once, `Jynstrument.exit()` removes the panel from the toolbar, and nothing in the engine re-adds it on its own. If the operator then re-clicks `Debug → RailDriver Throttle (built in)` (re-running `attachThrottleWindow()`), the auto-install treats that as a fresh attach and re-adds the toggle — this is acceptable because re-running the Debug menu is a deliberate operator action.

**Across JMRI restarts,** persistence of the toolbar's presence relies on JMRI's existing throttle-layout XML save/restore (`ThrottleWindow.java:800–865`): a saved layout that omits the toggle restores without it, and as long as the operator does not re-trigger `Debug → RailDriver Throttle (built in)`, the auto-install never runs and the toggle stays absent. If the operator does re-trigger the Debug menu, the auto-install fires (per the within-session rule above). This matches the persistence model for every other Jynstrument in JMRI and respects the "only persist things the operator changes via the Settings window" rule from §2.3.

The Jynstrument's `quit()` hook (called from `Jynstrument.exit()` at `Jynstrument.java:69–83` after `JynstrumentPopupMenu.actionPerformed` fires for the user's right-click → Quit) deregisters its PCS listener so the disposed instance does not leak listener subscriptions. Concretely, the Jython side looks like this:

```python
# RailDriverModeToggle.py — abbreviated
import java
import jmri.jmrit.jython.Jynstrument as Jynstrument
import jmri.util.usb.RailDriverMenuItem as RailDriverMenuItem
from javax.swing import JButton, JPopupMenu, JMenuItem, ImageIcon
from java.beans import PropertyChangeListener

class RailDriverModeToggle(Jynstrument):
    def getExpectedContextClassName(self):
        return "jmri.jmrit.throttle.ThrottleWindow"

    def init(self):
        self.menuItem = RailDriverMenuItem.getInstance()
        if self.menuItem is None:
            return  # Debug menu not opened yet; toggle is dormant
        self.iconOn   = ImageIcon(self.getFolder() + "/icons/raildriver-on.png")
        self.iconOff  = ImageIcon(self.getFolder() + "/icons/raildriver-off.png")
        self.iconWait = ImageIcon(self.getFolder() + "/icons/raildriver-binding.png")
        self.button = JButton(self.iconOff)
        self.button.actionPerformed = self.onClick
        self.add(self.button)
        self.pendingSessionToggle = False
        self.listener = self._makeListener()
        self.menuItem.addSettingsListener(self.listener)
        self._refreshState()
        # right-click popup
        popup = JPopupMenu()
        item = JMenuItem("Settings...")
        item.actionPerformed = lambda evt: java.lang.Class.forName(
            "jmri.util.usb.RailDriverSettingsAction").newInstance().actionPerformed(evt)
        popup.add(item)
        self.setPopUpMenu(popup)

    def quit(self):
        # Called from Jynstrument.exit() on user right-click → Quit.
        # Only deregisters listeners — does NOT persist the removal anywhere.
        # Persistence of removal is handled by saving the throttle layout XML.
        if self.menuItem is not None and self.listener is not None:
            self.menuItem.removeSettingsListener(self.listener)
        self.listener = None

    def _makeListener(self):
        outer = self
        class L(PropertyChangeListener):
            def propertyChange(self, evt):
                outer._onPCS(evt)
        return L()

    def _onPCS(self, evt):
        name = evt.getPropertyName()
        if name == "activeThrottleFrame":
            if evt.getNewValue() is self.getContext() and self.pendingSessionToggle:
                self.menuItem.setSemiRealisticEnabledSessionOnly(
                    not self.menuItem.isSemiRealisticLiveEnabled())
                self.pendingSessionToggle = False
            elif evt.getNewValue() is not self.getContext():
                self.pendingSessionToggle = False
        self._refreshState()

    def onClick(self, evt):
        if not self.menuItem.isRailDriverConnected():
            return  # State 1
        if self.menuItem.isAttachInProgress():
            return  # State 2.5
        active = self.menuItem.getActiveThrottleFrame()
        if active is None:
            self.pendingSessionToggle = True
            self.menuItem.requestAttachToThrottle(self.getContext())  # State 2 → 2.5
        elif active is self.getContext():
            self.menuItem.setSemiRealisticEnabledSessionOnly(  # State 4
                not self.menuItem.isSemiRealisticLiveEnabled())
        # State 3: bound elsewhere → no-op

    def _refreshState(self):
        # Update icon and tooltip based on current state. Implementation elided.
        pass
```

Total ~80 lines. The key contract is that `quit()` is purely a listener-deregistration hook — it never writes to disk, never sets a "suppress" flag, and never reaches back into Java state beyond removing its own subscription.

If the operator opens a *plain* throttle window (not via Debug → RailDriver), no auto-install fires — but in that case `attachThrottleWindow()` hasn't run either, so RailDriver isn't bound to that window and the toggle would have nothing to toggle. The two paths are coherent: the Jynstrument only appears on a throttle window that has RailDriver attached.

##### Relationship to legacy `RailDriverModernDesktop.py`

JMRI ships an older Jython-based RailDriver Jynstrument at `jython/Jynstruments/ThrottleWindowToolBar/USBThrottle.jyn/RailDriverModernDesktop.py` (~226 lines, JInput-backed, Windows-only per its own header comment). It's a **separate, parallel** code path with no shared state and no callbacks into our Java engine. Operators using the legacy script are not the audience for this feature; operators using `Debug → RailDriver Throttle (built in)` are. The new `RailDriverModeToggle.jyn` only attaches when the Java-side `RailDriverMenuItem` binds, so the two never overlap on the same throttle window in practice. No deprecation, no migration; the legacy script remains for users on the old path.

#### Why a Jynstrument and not a JMRI core change

JMRI's `ThrottleWindow` has no Java SPI for adding toolbar buttons or menu items. We considered three alternatives — direct core modification (rejected: couples a USB device to general-purpose throttle code), adding a generic extension hook upstream (viable but requires a JMRI-core PR with separate review timeline), and reflection-based runtime injection (rejected: brittle). The Jynstrument framework is the existing supported path; using it keeps every change inside our own files.

## 3. Implementation stages

Each stage is independently buildable, installable, and testable on a real DCC loco. Acceptance criteria are listed for each.

### 3.1 Stage 1 — Refactor & off-EDT bug fix

**Goal:** land the off-EDT-mutation latent issue fix and the Settings-window UI rework without introducing any new feature behaviour. The Calibration UI moves into a tab inside the unified `RailDriverSettingsFrame`; the Settings tab is present but disabled (its fields land in stage 2). After this stage the operator-perceptible behaviour is identical to the existing RailDriver bring-up except for the renamed Debug-menu entry and the (invisible) elimination of the off-EDT mutation.

**New / modified / deleted files:**

*New:*
- `java/src/jmri/util/usb/RailDriverSettingsFrame.java` — the `JmriJFrame` host described in §2.4: holds a `JTabbedPane` (Settings / Calibration), the bottom Save/Apply/Cancel button bar, status line, and the dirty-tracking glue. Listens for `"RawByte"` events and forwards them to the calibration tab so the live cursor still works while that tab is visible. The Settings tab is constructed in stage 1 as an empty / disabled placeholder (a single `JLabel("Semi-realistic settings ship in stage 2.")` is sufficient); fields land in stage 2.
- `java/src/jmri/util/usb/RailDriverSettingsAction.java` — `AbstractAction` opening the unified frame. Calls `RailDriverMenuItem.ensureDeviceAndPolling()` before showing the window (same precondition the existing calibration action enforces today).
- `java/src/jmri/util/usb/CalibrationTabPanel.java` — the Calibration tab. Created by extracting the entire visual-bar UI body from the existing `RailDriverCalibrationFrame` (everything in the current `buildHeader` / `buildSections` / per-axis `build*Section` / capture-row helpers) into a `JPanel` subclass, dropping the bottom Save / Reset-all / Cancel row (those move to the frame), and implementing the `isDirty / addDirtyChangeListener / validateAndApplyTo / resetToFile` contract from §2.4. Capture-button and per-section "Reset to defaults" presses now flip dirty.

*Modified:*
- `java/src/jmri/util/usb/RailDriverCalibration.java` — bump `SCHEMA_VERSION` to `"2"`. Add tolerant load of a `<semiRealistic>` child (an empty or absent element loads cleanly; saving in stage 1 still writes only the existing six children plus the new `version="2"` attribute on the root). The `<semiRealistic>` subtree itself is populated in stage 2.
- `java/src/jmri/util/usb/RailDriverMenuItem.java`:
   1. **Wrap every Swing-touching call in `dispatchValueEvent` via `ThreadingUtil`** — this fixes the pre-existing off-EDT mutation per §2.2's threading contract. The wrapping splits into two cases by the call's nature, not by which axis it's on:

      **Setters / fire-and-forget mutators → `ThreadingUtil.runOnGUIEventually(() -> ...)`:**
      - Axis 0: `throttle.setIsForward(...)`
      - Axis 1: `throttle.setSpeedSetting(...)`
      - Axis 6: `throttle.setFunction(0, ...)` (lights toggle)
      - Inner-switch: `addressPanel.selectRosterEntry(...)`, `addressPanel.dispatchAddress(...)`, `addressPanel.setRosterSelectedIndex(...)`, `throttleWindow.nextThrottleFrame()`, `throttleWindow.previousThrottleFrame()`, `throttle.setSpeedSetting(...)`, `throttle.setFunction(...)`

      **Getters whose return values feed the surrounding decision → `ThreadingUtil.runOnGUIwithReturn(() -> ...)`:**
      - `throttle.getFunctions()` (used in `RailDriverMenuItem.java:883` to bound-check `fNum`)
      - `throttle.getFunctionMomentary(fNum)` (line 884; gates whether to toggle vs. set)
      - `throttle.getFunction(fNum)` (line 886; current state for toggle computation)

      **This corrects a latent bug the original bring-up plan introduced** by listing those getters alongside the setters: wrapping a getter in fire-and-forget `invokeLater` returns `null`/garbage to the calling code because the lambda hasn't run yet. `runOnGUIwithReturn` blocks the polling thread until the EDT lambda completes — slightly higher polling-thread latency on the inner-switch path, but the function-state read is now actually correct. The polling thread can afford the round-trip; HID reports come at ~11.5 Hz so even a 50 ms EDT round-trip is well within budget.

      **No wrapping needed:** `setLEDs(...)` calls `sendMessage`/`hidDevice` only (verified `RailDriverMenuItem.java:446`) — no Swing path. The decision logic (which axis case matched, what value to compute) stays on the polling thread; only the final mutator/getter call against a Swing-backed object crosses to the EDT.

      **Migration of the existing `SwingUtilities.invokeLater` call site:** `RailDriverMenuItem.java:244` (the `attachThrottleWindow` deferred-listener-wiring `invokeLater` — verified to be the **only** `SwingUtilities.invokeLater` site in the file) becomes `ThreadingUtil.runOnGUIEventually` — same behaviour, JMRI-canonical idiom. The `import javax.swing.SwingUtilities;` line is removed; `import jmri.util.ThreadingUtil;` is added. The other pre-existing `SwingUtilities.invokeLater` site in `RailDriverCalibrationFrame.java:423` is moot — that file is deleted in this stage.
- `java/src/apps/jmrit/DebugMenu.java` — replace the `new jmri.util.usb.RailDriverCalibrationAction()` line with `new jmri.util.usb.RailDriverSettingsAction()`.
- `java/src/jmri/util/usb/Bundle.properties` — replace `RdCalibrate = RailDriver Calibration...` with `RdSettings = RailDriver Settings...`. The 5 existing locale Bundle files (`Bundle_ca`, `Bundle_cs`, `Bundle_de`, `Bundle_fr`, `Bundle_nl`) fall through to English for any keys they don't override; translators can add localized values later. (Stage 2 adds the additional Settings-tab labels.)

*Deleted:*
- `java/src/jmri/util/usb/RailDriverCalibrationFrame.java` — replaced by `RailDriverSettingsFrame` + `CalibrationTabPanel`.
- `java/src/jmri/util/usb/RailDriverCalibrationAction.java` — replaced by `RailDriverSettingsAction`.

`CalibrationBar.java` is unchanged — `CalibrationTabPanel` uses it exactly as the old frame did.

**Acceptance:**
1. `Debug → RailDriver Settings...` opens the unified window. The Calibration tab is fully functional and behaves identically to the old standalone calibration window. The Settings tab is selected by default and shows the placeholder content.
2. Calibration-tab editing/capture/per-section Reset enables the bottom Apply button; Save/Apply persists from the calibration tab; Apply greys back out after success; Save closes the window; Cancel with no pending changes closes; Cancel with pending changes prompts the operator.
3. Throttle behaviour is operator-perceptibly identical to the existing RailDriver bring-up: same `setSpeedSetting` / `setIsForward` / `setFunction` mappings, same LED feedback, same E-Stop, same reverser. Only difference: every Swing-touching call now routes through `ThreadingUtil` instead of touching Swing directly from the polling thread.
4. The `activeThrottleFrame` NPE invariant from the existing RailDriver bring-up still holds.
5. **Code audit:** every method call in `RailDriverMenuItem.dispatchValueEvent` (and any helpers it calls) that mutates a Swing component, or calls a JMRI throttle/address-panel API that is documented as EDT-only, is wrapped in `ThreadingUtil.runOnGUIEventually` (setters) or `ThreadingUtil.runOnGUIwithReturn` (getters). Verified by `grep` against the listed call sites (`grep -n 'throttle\.\|addressPanel\.\|throttleWindow\.next\|throttleWindow\.previous'` showing zero unwrapped occurrences) and by a 5-minute live lever-sweep session producing no visible UI corruption. The file no longer imports `javax.swing.SwingUtilities`.
6. Pre-existing XML files (schema `version="1"`) load cleanly into the new window — calibration fields load as before; saving from the unified window produces a `version="2"` file with the same six existing children (no `<semiRealistic>` element yet).
7. No `RailDriverCalibrationFrame` or `RailDriverCalibrationAction` references remain in the codebase (verified by `grep -r`).

### 3.2 Stage 2 — Throttle-fraction physics engine + bypass + scenario picker + independent brake + toolbar mode toggle

**Goal:** first stage where `liveEnabled = ON` produces a usable feature on a model railroad layout. The throttle lever sets `sTarget` and the integrator walks `s` toward it under the **throttle-fraction-rate model from §1.0** — every coefficient is a rate in fraction/sec wall-clock, no mass, no force, no scale, no `designTopSpeed`. The calibrated independent-brake lever (Axis 3) provides a working `mechRate` term so the operator can stop. The scenario picker is wired so the operator can change feel-rate presets. The Settings tab exposes the rate constants (drive, drag, indep) and consist factors. The toolbar Jynstrument is auto-installed for fast session-level mode toggling.

The integration body computes `ds/dt` directly from the rate composition:
```
driveRate = (sTarget > s) ? accelRate · consistAccelFactor : 0
dragRate  = dragCoeff · s · consistDragFactor
mechRate  = indepLever · indepBrakeRate · indepConsistFactor
ds/dt     = driveRate − dragRate − mechRate     // autoRate / dynRate are 0 until stages 4–5
s        += ds/dt · 0.050
s         = clamp(s, 0, 1)
setSpeedSetting(s)
```

The engine emits `s` directly via `setSpeedSetting(s)` — no roster speed profile lookup, no velocity conversion, no top-speed clamp inside the engine. The decoder + speed profile remain the sole authority for physical loco speed at `s = 1.0`. The Westinghouse state machine is not active in stage 2 (`autoRate = 0`); stage 4 wires it.

**New / modified files:**

*New:*
- `java/src/jmri/util/usb/SemiRealisticThrottleEngine.java` — engine with the integration tick / `ScheduledExecutorService` / DccThrottle access. Throttle and indep-brake paths fully wired with the §1.0 throttle-fraction-rate model from day one. Auto-brake / dyn-brake input fields stay zero until stages 4–5 (Westinghouse fields exist but are inert until stage 4). Worker thread does the math; EDT does the `setSpeedSetting`.
- `java/src/jmri/util/usb/SemiRealisticSettings.java` — settings POJO with load + save methods, mirroring `RailDriverCalibration`'s structure. Holds the rate fields (`accelRate`, `dragCoeff`, `indepBrakeRate`, `autoBrakeRate`, `dynBrakeRate`), the consist factors (`consistAccelFactor`, `consistDragFactor`, `indepConsistFactor`, `dynConsistFactor`), the dyn-taper threshold, the Westinghouse parameters (`consistCars`, `tPipe`, `tCylinderApply`, `tCylinderRelease`, `tAuxDrain`, `tRecharge`, `cylinderMagnification`), the scenario enum reference, the `persistedEnabled` and `liveEnabled` fields, and (placeholder, used by stage 6) the `decoderBrakeMode` enum.
- `java/src/jmri/util/usb/LoadScenario.java` — enum with the seven scenarios from §2.4.1 (Light engine, Switcher, **EMD NW2**, Local freight, Through freight, Unit train, Custom) and their tuned rate / consist / Westinghouse defaults.
- `java/src/jmri/util/usb/SemiRealisticSettingsPanel.java` — the Settings tab content. Replaces stage 1's placeholder. Implements the `isDirty / addDirtyChangeListener / validateAndApplyTo / resetToFile` contract from §2.4. Field grouping per §2.4 (Mode / Loco scenario / Drive / Coast / Brakes / Westinghouse / Decoder integration). Disables fields based on the `Enable semi-realistic mode` checkbox; switches per-rate rows between read-only "scenario default" display and editable when scenario = Custom. Westinghouse parameter rows greyed out when `consistCars == 0`. Decoder-brake-mode dropdown is present but has only `None` available (the `ESU` option is enabled in stage 6).
- `jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/RailDriverModeToggle.py` — the Jynstrument from §2.6. Implementation per §2.6.
- `jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/icons/raildriver-on.png`, `raildriver-off.png`, `raildriver-binding.png` — toolbar icons.

*Modified:*
- `java/src/jmri/util/usb/RailDriverCalibration.java` — populate the `<semiRealistic>` subtree on save and on load (§2.5 schema). Hold a `SemiRealisticSettings` field accessor.
- `java/src/jmri/util/usb/RailDriverMenuItem.java`:
   1. Instantiate the engine in `attachThrottleWindow`; route `dispatchValueEvent` Axis 1 dispatch through the engine when `settings.liveEnabled`. The polling-thread setter is now `engine.setThrottleAboveIdle(fractionAboveIdle)` and `engine.setThrottleBelowIdle(fractionBelowIdle)` (the latter for stage 5; passes 0 in stage 2).
   2. Add the Axis 3 case to `dispatchValueEvent`: compute `indepBrakeFraction = clamp((calibratedFullRelease - byteValue) / (calibratedFullRelease - calibratedFullApplication), 0, 1)`; set `engine.setIndepBrakeLever(fraction)`. The integration tick reads this volatile field on each slice.
   3. Extend `reloadCalibration()` to notify the engine of new semi-realistic settings.
   4. Add `isSemiRealisticLiveEnabled()`, `isSemiRealisticPersistedEnabled()`, `setSemiRealisticEnabledSessionOnly(boolean)`, `isRailDriverConnected()`, `getActiveThrottleFrame()`, `isAttachInProgress()`, `requestAttachToThrottle(ThrottleFrame)`, `addSettingsListener(PropertyChangeListener)`, `removeSettingsListener(PropertyChangeListener)` per §2.6 (unchanged from previous plan revisions).
   5. Auto-install the Jynstrument at the end of `attachThrottleWindow`'s success path (unchanged from previous plan revisions).
- `java/src/jmri/util/usb/Bundle.properties` — no changes in stage 2 (English-literal labels in `SemiRealisticSettingsPanel.java`).

**Acceptance:**
1. The Settings tab is functional: scenario picker shows all 7 scenarios; selecting `Custom` enables all per-rate fields; non-Custom scenarios show the active scenario's preset values as read-only; switching scenarios snaps all per-rate fields to the new scenario's defaults. Westinghouse parameter rows greyed out when `consistCars == 0`. Field validation matches §6's validation ranges.
2. Mode OFF: throttle behaves exactly as in stage 1 (operator-perceptibly identical).
3. Mode ON, default Light-engine, indep brake at Full Release: moving the throttle lever from idle to full produces a visible ramp on the loco. Default `accelRate = 0.10` → loco reaches `s ≈ 0.95` in ~13 s wall-clock against light drag. **No snap, no jitter.** Loco eventually reaches `s = 1.0` (lever-target = 1.0, drive overcomes drag) — top speed is whatever the decoder produces at `s = 1.0`.
4. Mode ON, indep brake at Full Application + throttle at zero, Light-engine: `ds/dt = −0.30 − 0.05·s` → s decays exponentially with brake-dominated rate → time from `s = 1.0` to `s ≈ 0.05` is **~3.3 s wall-clock**.
5. Mode ON, full throttle + full indep brake from rest, Light-engine: `ds/dt = 0.10 − 0.30 − 0.05·s = −0.20 − 0.05·s` ≤ 0 always. **Loco does not move.** Validates "full brake can prevent motion under full throttle" — the operator-described constraint. Reducing brake to 30 %: `ds/dt = 0.10 − 0.09 − 0.05·s` → marginally positive for low s, asymptote at `s ≈ 0.2`. Brake can fully or partially overcome drive, depending on relative rates.
6. Mode ON, releasing the indep brake while at speed: `mechRate = 0` on the next tick; integrator resumes accelerating toward the lever's target.
7. Switching scenarios mid-accel (e.g. `Light engine` → `Unit train`): the integrator picks up the new scenario's rates on the next 50 ms tick; rate of accel drops because `consistAccelFactor` shrinks (e.g. 1.0 → 0.30). The transition is immediate.
8. Roster fields not consulted. Decoder + speed profile determine top physical speed at `s = 1.0`; engine never imposes a cap below 1.0.
9. Mode OFF → ON at any setting: engine reads `throttle.getSpeedSetting()`, sets `s` to that value, resets Westinghouse state to released-and-charged (relevant in stage 4), integrates smoothly toward lever target — no snap.
10. Mode ON → OFF: engine pauses; next byte change writes lever-derived value directly (operator-accepted snap if lever far from current `s`).
11. **Coast-down acceptance.** Mode ON, dropping throttle from full to idle (no brake input): `ds/dt = −dragCoeff · s · consistDragFactor` → exponential decay with time constant `1 / (dragCoeff · consistDragFactor)`. Light Engine: ~20 s time constant → ~60 s to `s ≈ 0.05`. Through Freight (consistDragFactor = 0.5): ~40 s time constant → ~120 s. Heavier consist coasts more slowly — the operator-described behaviour.
12. **No-asymptote invariant.** Under full throttle and zero brake, `ds/dt = accelRate · consistAccelFactor − dragCoeff · s · consistDragFactor`. At `s = 1.0` the equation must be ≥ 0 for the loco to reach `s = 1.0`. For all default scenarios: `0.10 · consistAccelFactor − 0.05 · 1.0 · consistDragFactor` (and similarly tuned others) ≥ 0 by construction (consistAccelFactor and consistDragFactor are tuned together). Verified per scenario in the worked-example table in §2.1.
13. The toolbar mode-toggle Jynstrument auto-installs on first `Debug → RailDriver Throttle (built in)` click. The icon shows the current **live** `enabled` state. Clicking it flips the live state **for this session only** (no XML write).
14. Jynstrument idempotent on repeat attaches within a session; removal-via-Quit sticky for that throttle window's lifetime; re-clicking the Debug menu re-adds it.
15. RailDriver hot-plug: unplugging fires `"railDriverConnected"` (false); replugging fires `"railDriverConnected"` (true). Debug-menu lifecycle unaffected.
16. Pre-existing XML files (schema `version="2"` from stage 1, no `<semiRealistic>` subtree) load cleanly — semi-realistic fields populate from defaults (disabled, Light engine); saving produces a `version="2"` file with the new `<semiRealistic>` subtree.

### 3.3 Stage 3 — Reverser interlock

**Goal:** [research §5] direction can only change at zero throttle setting (`s ≈ 0`).

**Modified:** `dispatchValueEvent` Axis 0 case to suppress `setIsForward(...)` when `settings.liveEnabled && engine.getS() > epsilon`. E-Stop SPDT keeps `setSpeedSetting(-1)`. Reverser to NEUTRAL at any setting forces the engine to treat `sTarget = 0` (lever input zeroed), so the loco coasts to a stop on drag plus any active brakes.

The interlock consults `engine.getS()` (the engine's view of current setting) rather than `throttle.getSpeedSetting()`. The two are consistent in semi-realistic mode — `s` is the value just emitted via `setSpeedSetting()` on the previous tick — but `s` is a `volatile` field on the engine, readable from the polling thread without crossing the EDT. The `settings.liveEnabled` guard ensures the field is only consulted while the engine is actively maintaining it.

**Pending-direction-change behaviour: byte-edge-triggered, not retried on stop.** When the operator moves the reverser lever during deceleration, the dispatch suppresses the direction change at the moment the byte changes. Once the loco reaches `s ≈ 0`, the dispatch does NOT retroactively apply the held lever position — the operator must nudge the reverser lever again (any byte change re-evaluates the interlock) to actually flip the direction. Matches prototype behaviour.

**Acceptance:**
1. Loco at `s > 0` + reverser moved to opposite direction: direction does NOT flip; an INFO log line records the suppression. Direction lever change with `s ≈ 0` works.
2. Loco decelerating + reverser already moved to opposite direction: direction still does NOT flip when `s` reaches 0. Operator must release-and-re-move the reverser. Documented as expected behaviour.
3. Reverser to NEUTRAL at any setting: loco coasts to a stop on drag plus any active brakes (per [research §3.3]).
4. E-Stop SPDT at any setting: loco hard-stops via `setSpeedSetting(-1)`. Same as the existing RailDriver bring-up behaviour.
5. With Mode OFF (`liveEnabled == false`): the interlock is skipped; reverser changes propagate immediately as in stage 1.

### 3.4 Stage 4 — Auto brake → Westinghouse air-brake simulation + bail-off override

**Goal:** Auto Brake (Axis 2) drives the Westinghouse pneumatic state machine from §1.0.2; the resulting cylinder pressure feeds `autoRate` in the per-tick integration. Bail-off (byte 4) zeros `autoRate` while held without affecting the underlying state.

**Modified:**
- `dispatchValueEvent` Axis 2 case computes `autoBrakeLever = clamp((calibratedReleased - byteValue) / (calibratedReleased - calibratedEmg), 0, 1)` (Released → 0, EMG → 1) and calls `engine.setAutoBrakeLever(fraction)`.
- Button dispatch for the bail-off switch sets `engine.setBailoffPressed(bool)` based on byte-4 threshold-crossing.
- `SemiRealisticThrottleEngine.java`'s tick body activates the Westinghouse state machine when the active scenario has `consistCars > 0`. Each tick advances `pipePressure`, `auxCharge`, `cylinderPressure` per §1.0.2. The auto-brake's contribution to `ds/dt` is `autoRate = (bailoffPressed ? 0 : cylinderPressure) · autoBrakeRate`. For light-engine scenarios (`consistCars == 0`), the state machine is bypassed and `cylinderPressure = autoBrakeLever` directly (equivalent to the legacy direct-mapping behaviour).
- Mode OFF→ON handover (§2.3) resets the Westinghouse state to `pipePressure = 1.0`, `auxCharge = 1.0`, `cylinderPressure = 0.0` so the operator's first auto-brake application starts from a known good state.

The Westinghouse simulation reproduces the operator-felt consequences of repeated brake applications:

- **Service application** (auto handle to a fraction): pipe drops to `1 − lever` over `T_pipe`. Triple valve transfers air from aux to cylinder. `cylinderPressure ≈ (auxCharge − pipePressure) · cylinderMagnification`. Aux drains over `T_auxDrain`.
- **Emergency** (handle to EMG = 1.0): pipe target = 0 → fast pipe drop → cylinder reaches max → aux fully drained.
- **Release** (handle back to Released = 0): pipe target = 1 → pipe recovers (T_pipe) → cylinder vents (T_cylinderRelease) → aux recharges (T_recharge).
- **"Running out of air":** several full-service applications without recovery → `auxCharge` low → next application produces weaker `cylinderPressure` → less braking. Operator must release and wait `T_recharge` for full recovery.
- **Bail-off:** zeros `autoRate` while held; does NOT touch `pipePressure` / `auxCharge`. Pipe stays at the lever-derived value; aux continues feeding cylinder; release the bail-off and the (continued) cylinder pressure resumes contributing to `autoRate`.

**Acceptance:**
1. Light engine (`consistCars == 0`), auto brake at Released: `cylinderPressure = 0`, `autoRate = 0`, integrator behaves identically to stage 2.
2. Light engine, auto brake at EMG: `cylinderPressure = 1.0` (direct mapping), `autoRate = 0.50` (default `autoBrakeRate`), `s` decays at ~0.50 fraction/sec → `s = 1.0` to 0 in **~2 s wall-clock**.
3. Light engine, auto brake at 50 %: `cylinderPressure = 0.5`, `autoRate = 0.25`, time from `s = 1.0` to 0 ≈ 4 s.
4. Local freight (`consistCars = 15`), auto brake to EMG, throttle at zero, starting from `s = 0.5`, aux fully charged: pipe drops over T_pipe = 2 s; cylinder fills over T_cylinderApply = 0.5 s; cylinder reaches ~`(1.0 − 0) · 2.5 = 2.5` clamped to 1.0. `autoRate = 0.50` at full cylinder. Time from `s = 0.5` to 0 ≈ 1 s after cylinder fills, plus ~0.5 s fill ramp.
5. **Westinghouse "running out of air":** Local freight scenario, three consecutive full-service-then-release cycles with only 10 s recovery between (T_recharge = 60 s; recovers ~16 % per 10 s). After 3 cycles, `auxCharge` ≈ 0.3. Fourth full application: `cylinderPressure ≈ (0.3 − 0) · 2.5 = 0.75` clamped (was 1.0). `autoRate ≈ 0.375`. Operator feels weaker braking — the prototype reality.
6. **Recovery:** after the depleted state in #5, releasing the auto brake and waiting 60+ s allows aux to recharge to ~1.0; next application has full effect again.
7. Auto brake at SUP/CS + bail-off pressed: `autoRate = 0` (bailoff override), only mech brake (if any) remains. Pipe / aux unchanged; cylinder continues filling from aux. Release bail-off and `autoRate` resumes at the cylinder pressure.
8. Auto brake at 50 % + indep brake at 50 %: rates sum. `autoRate = 0.25` (post-Westinghouse), `mechRate = 0.30 · 0.50 · indepConsistFactor`. For Local Freight (indepConsistFactor = 0.5): `mechRate = 0.075`. Total decel rate = 0.325 fraction/sec. **Indep brake is weaker on heavy consist** (per operator description), but auto brake is full strength.
9. OFF→ON handover resets Westinghouse state to released-charged so first application after re-enabling has full effect.

### 3.5 Stage 5 — Dynamic brake (lever below idle)

**Goal:** the part of the throttle lever below idle (toward DYN BRAKE label) adds a `dynRate` term scaled by `dynConsistFactor` and tapered at low `s`.

**Modified:** `dispatchValueEvent` Axis 1 case computes `throttleBelowIdle = clamp((calibratedIdleLow - byteValue) / (calibratedIdleLow - calibratedFullDynBrake), 0, 1)` when the byte is below Idle Low, and calls `engine.setThrottleBelowIdle(fraction)` (with `setThrottleAboveIdle(0)` in this regime). The integration tick adds `dynRate = throttleBelowIdle · dynBrakeRate · dynConsistFactor · dynTaper(s)` where `dynTaper(s) = min(1, s / dynTaperThreshold)`. The `DBr` LED becomes the indication that the dyn-brake region is active.

Two notable properties:
- **`dynConsistFactor` is per-scenario** (1.0 for Light Engine, ~0.10 for Unit Train). Real dyn brake doesn't propagate through trainline air [research §10 item 1] — only loco traction motors generate the braking, so heavier consists dilute it. Encoded as a coefficient because the engine is mass-free.
- **Speed-tapered.** Real dyn brake fades to zero at low speed because traction motors lose torque. `dynTaper(s)` is a linear approximation: below `dynTaperThreshold` (default 0.05 = `s = 5 %`) the term scales linearly to zero. Note the threshold is in `s`-space, not mph — the engine has no velocity. Operators tune for "below this throttle-setting equivalent, dyn brake fades."

Stacks with mech and auto brake by simple rate summation. No `min/max` selection.

**Acceptance:**
1. Lever at Idle Low or above: `throttleBelowIdle = 0`, `dynRate = 0`, integrator behaves identically to stage 4.
2. Lever at full DYN BRAKE, Light Engine, throttle/auto/indep brakes off, at `s = 0.5`: `dynRate = 1.0 · 0.20 · 1.0 · 1.0 = 0.20 fraction/sec`. Plus `dragRate = 0.05 · 0.5 = 0.025`. Total = 0.225 → s decays to 0 in ~2.2 s wall-clock. LED shows `DBr`.
3. Same at `s = 0.03` (below taper threshold 0.05): `dynTaper = 0.03/0.05 = 0.6` → `dynRate = 0.12`. Tapers smoothly to zero as `s` approaches 0.
4. Lever at full DYN + Auto brake at EMG, Light Engine at `s = 0.5`: `dynRate = 0.20`, `autoRate = 0.50`, `dragRate = 0.025`, total = 0.725 → s decays to 0 in ~0.7 s.
5. Switching to Unit Train scenario with same lever positions: `dynRate = 1.0 · 0.20 · 0.10 · 1.0 = 0.020`, `autoRate = 0.50` (unchanged). Auto-brake dominates; dyn brake's contribution is small — prototypically correct.

### 3.6 Stage 6 — ESU decoder-brake passthrough (optional)

**Goal:** for users with ESU decoders, mirror brake percent to F4/F5/F6 [research §4.3]. Off by default.

**Modified:** `SemiRealisticSettings.java` (enable the `ESU` option in the `decoderBrakeMode` enum; ESU function/threshold fields), `SemiRealisticSettingsPanel.java` (enable the Decoder-brake Mode dropdown's `ESU` option + ESU-only sub-fields), `SemiRealisticThrottleEngine.java` integration tick (after computing rates, derive `effectiveBrakePct = clamp(indepBrakeLever · 100 + cylinderPressure · 100, 0, 100)` and dispatch the function changes with the same three-pass logic from `setDecoderBrake` in [research §4.3]).

**Acceptance:**
1. Decoder-brake mode = None: no F4/F5/F6 dispatch from semi-realistic logic.
2. Decoder-brake mode = ESU: applying indep brake to ≥30 % toggles F4 ON; ≥60 % toggles F4 OFF + F5 ON; ≥98 % toggles F5 OFF + F6 ON. Backing off reverses the chain. Auto brake (via `cylinderPressure`) contributes additively.
3. The threshold/function fields are user-configurable per loco family.

## 4. Acceptance criteria (overall)

1. With semi-realistic mode OFF, behaviour is identical to the existing RailDriver bring-up (no regression).
2. With mode ON, all per-stage acceptance criteria pass on a real DCC loco.
3. **Throttle-fraction-rate invariants per §1.0**: the engine emits `setSpeedSetting(s)` where `s ∈ [0, 1]` is integrated from a sum of operator-tunable rates in fraction/sec wall-clock. There is no mass, no force, no scale factor, no top-speed cap inside the engine. The decoder + speed profile remain the sole authority for physical loco speed at `s = 1.0`.
4. **No-asymptote invariant.** Under full throttle (`sTarget = 1.0`) and zero brake, default scenario coefficients produce `ds/dt > 0` over `s ∈ [0, 1)`. The loco reaches `s = 1.0` in a finite wall-clock time. Verified per scenario in §2.1's worked-example table.
5. **Brake-can-overcome-drive invariant.** Under full throttle and full indep + auto brake, default scenario coefficients produce `ds/dt ≤ 0` at `s = 0`. The loco does not move from rest. Operators feel "throttle can't beat brake" — the operator-described constraint.
6. Calibration XML round-trips through Save / Load with the new schema; pre-existing files (version `"1"`) still load cleanly with semi-realistic defaults.
7. The `activeThrottleFrame == null` invariant from the existing RailDriver bring-up still holds — the engine acquires its `DccThrottle` from `activeThrottleFrame` at attach time and never holds the reference past the throttle's `"ancestor"` close.
8. The pre-existing noise hysteresis filter still applies — the engine never sees byte-level jitter as a "lever moved".
9. No new `messages.log` exceptions during a 30-minute ops session involving repeated brake / throttle work.
10. **Empty-roster path:** with mode ON, no roster fields populated, default Light-engine scenario: the loco accelerates and decelerates under the scenario's defaults from §2.4.1. Roster fields are not consulted.
11. **Westinghouse fidelity:** consist scenarios produce the operator-felt "running out of air" pattern after repeated full-service applications; release allows aux to recharge over T_recharge wall-clock seconds, restoring full braking. Light-engine scenarios bypass the state machine and behave with direct lever → cylinder mapping.

## 5. Deliverables

Per stage, listed in §3. Total across stages 1–6:

- 7 new Java files (`SemiRealisticThrottleEngine`, `SemiRealisticSettings`, `LoadScenario` enum, `RailDriverSettingsAction`, `RailDriverSettingsFrame`, `SemiRealisticSettingsPanel`, `CalibrationTabPanel`).
- 1 new Jynstrument (`RailDriverModeToggle.jyn` — Jython script + 3 icons).
- 2 deleted Java files (`RailDriverCalibrationFrame`, `RailDriverCalibrationAction`) — replaced by the unified Settings frame.
- ~4 modified files (`RailDriverCalibration`, `RailDriverMenuItem`, `DebugMenu`, `Bundle.properties`).
- New per-profile XML subtree (schema bumped to version `"2"`).
- No changes to native libs, hid4java, udev rules, or build.xml / pom.xml.
- **Reuse:** none. `SemiRealisticThrottleEngine` shares no code with `RosterSpeedProfile`; the integration math is rewritten from scratch.

## 6. Open design questions for review

All design questions for this feature have been resolved. See "Resolved decisions" below.

### Resolved decisions

- **Engine architecture (decided 2026-05-02; iteratively refactored 2026-05-03 to current throttle-fraction-rate model):** the engine works in DCC throttle-setting space `s ∈ [0, 1]`. Each tick computes `ds/dt = driveRate − dragRate − mechRate − autoRate − dynRate` where every rate is operator-tunable in fraction/sec wall-clock. The engine never imposes a top-speed cap; the decoder + speed profile remain the sole authority for physical loco speed at `s = 1.0`. There is no mass, no force, no prototype velocity, no scale factor. **Two earlier architectures were rejected in iteration:** (1) prototype Davis × physicsTimeScale — F_drive vs. F_resist competed in incompatible force universes; F_resist always won at speed. (2) wall-clock m/s² Davis-shape — drive and resist still competed, producing scenario-dependent asymptotes that capped top speed below the lever's request (Switcher reached only 33 % of design top because resist quadratic exceeded operator-tunable drive at that speed). The current rate-based model has no force competition: drive raises `s` toward `sTarget` at a fixed rate, brakes lower `s` at their own rates, and `s` always reaches the lever's target unless brakes overcome drive. See §1.0 for the framing, §2.1 for the engine class skeleton + tick body, §2.2 for the threading contract.

- **No top-speed cap (decided 2026-05-03):** the engine never clamps `s` below 1.0. The decoder + speed profile already determine physical top speed at `setSpeedSetting(1.0)`. No `designTopSpeed`, no roster `getPhysicsMaxSpeedKmh()` clamp, no asymptote. Per the operator's explicit direction: "this plan shouldn't be changing the top speed of a locomotive."

- **Drive is rate-based, not force-based (decided 2026-05-03):** acceleration rate (`accelRate`) is the operator-tunable "feel" parameter — how fast `s` rises toward `sTarget`. Default 0.10 = full-lever ramp from 0 to 1.0 in 10 s wall-clock. Mass-independent; `consistAccelFactor` modulates it for "heavier consist accelerates more slowly" UX. No prototype P/TE derivation, no `vCorner` curve, no constant-power physics shape.

- **Brakes can overcome drive (decided 2026-05-03):** in the rate composition, `ds/dt = driveRate − brakeTotal − dragRate`. When brakes win at full throttle (`brakeTotal > driveRate + dragRate`), `s` doesn't rise. At `s = 0` this means the loco doesn't move under full throttle when the brake levers are sufficiently applied — matches the operator's explicit description ("moving the throttle to full might not overcome a full application of the independent or auto brake to allow the train to move").

- **Coast resistance is a single linear-in-`s` rate (decided 2026-05-03):** `dragRate = dragCoeff · s · consistDragFactor`. No quadratic / aerodynamic term — operator explicitly directed "I don't care about air resistance." Heavier consists coast more slowly via `consistDragFactor`.

- **Brake consist semantics (decided 2026-05-03):**
  - **Independent brake** is loco-only — `mechRate = indepLever · indepBrakeRate · indepConsistFactor`. Heavier consist → smaller `indepConsistFactor` (e.g. 0.15 for unit train) → indep brake nearly ineffective on the consist's mass. Matches the operator's description ("the independent brake should not affect a heavy train with a lot of cars as much as the auto brake because the independent brake only applies to the loco itself").
  - **Auto brake** propagates through trainline — `autoRate = cylinderPressure · autoBrakeRate`, no consist factor. Full strength on every consist. Westinghouse aux/pipe dynamics produce consist-mass-felt response naturally.
  - **Dynamic brake** is loco-only with speed taper — `dynRate = dynLever · dynBrakeRate · dynConsistFactor · dynTaper(s)`. Like indep, diluted on heavy consists.

- **Westinghouse air-brake simulation (decided 2026-05-03):** auto-brake handle drives a 3-state pneumatic model (`pipePressure`, `auxCharge`, `cylinderPressure`) with operator-tunable time constants per scenario (T_pipe, T_recharge, T_cylinderApply, T_cylinderRelease, T_auxDrain, cylinderMagnification). The triple-valve apply / release transitions produce the prototypical "running out of air after repeated applications" feel. Bail-off zeros `autoRate` while held without touching the underlying state. Light-engine scenarios (consistCars = 0) bypass the state machine and use direct lever → cylinder mapping. See §1.0.2 for the state machine, §3.4 for stage-4 wiring.

- **Roster fields not consulted (decided 2026-05-03):** `RosterEntry.getPhysicsWeightKg()`, `getPhysicsPowerKw()`, `getPhysicsTractiveEffortKn()`, **and** `getPhysicsMaxSpeedKmh()` are all ignored by the engine. The throttle-fraction-rate model has no use for prototype mass / power / TE / max-speed values. The decoder + speed profile already determine physical top speed at `s = 1.0`. Per-loco rate-tuning overrides via roster attributes are deferred future work.

- **Scenario defaults (decided 2026-05-03):** seven scenarios (Light engine, Switcher, EMD NW2, Local freight, Through freight, Unit train, Custom). Each carries tuned rates and consist factors per §2.4.1. Switching scenarios is the operator's "weight selector" — heavier scenarios feel heavier because the rates and consist factors say so, not because mass enters the math.

- **EDT discipline (decided 2026-05-02): Option B with worker-thread-math mitigation, standardised on `jmri.util.ThreadingUtil`.** Every Swing-touching call from a non-EDT thread is wrapped in `ThreadingUtil.runOnGUIEventually` (setters) or `ThreadingUtil.runOnGUIwithReturn` (getters). Engine worker does math; EDT does setSpeedSetting. See §2.2.
- **UI surface (decided 2026-05-02): one unified `RailDriver Settings...` window with two tabs (Settings + Calibration), plus an Apply button.** See §2.4.
- **Framing (decided 2026-05-02; reinforced 2026-05-03): this is a model-railroad simulation, not a prototype simulator.** The throttle-fraction-rate model exists precisely because operators want operator-perceptible decel rates that look right at scale-time. Six stages numbered 1–6 within this document.
- **Mode-toggle UI on the throttle window (decided 2026-05-02):** Jynstrument-based toolbar button, session-only persistence semantics. See §2.6.
- **Persistence split between Settings window and Jynstrument (decided 2026-05-02):** `persistedEnabled` (Settings tab, on disk) vs. `liveEnabled` (engine + Jynstrument). See §2.3.
- **Attach-in-progress visibility on the Jynstrument (decided 2026-05-02):** `"attachInProgress"` PCS event brackets the async window. See §2.6.
- **`hidDeviceAttached` re-enabled (decided 2026-05-02):** fires `"railDriverConnected"` on VID/PID match without auto-calling `setupRailDriver()`. See §3.2.
- **Bail-off semantics (decided 2026-05-02; revised 2026-05-03 for Westinghouse):** zeros `autoRate` while held; does not touch the Westinghouse state (pipe / aux / cylinder). Mech and dyn brake terms unaffected. See §3.4.
- **Mode-switch handover at speed (decided 2026-05-02; revised 2026-05-03 for `s`-based engine):** OFF → ON sets `engine.s = throttle.getSpeedSetting()` and resets Westinghouse state to released-charged. ON → OFF pauses; next byte change writes lever-derived value directly. See §2.3.
- **Reverser-interlock setting source (decided 2026-05-02; revised 2026-05-03):** polling-thread Axis 0 dispatch reads `engine.getS()` (volatile, no EDT crossing) and suppresses `setIsForward()` when `settings.liveEnabled && s > epsilon`. See §3.3.
- **Pending direction change at stop (decided 2026-05-02):** not retried on `s == 0`. Operator must nudge the reverser. See §3.3.
- **Settings-field validation ranges (decided 2026-05-03):** accelRate 0.001–5.0 fraction/s; consistAccelFactor 0.0–1.0; dragCoeff 0.0–5.0 1/s; consistDragFactor 0.0–1.0; indepBrakeRate / autoBrakeRate / dynBrakeRate 0.0–10.0 fraction/s; indepConsistFactor / dynConsistFactor 0.0–1.0; dynTaperThreshold 0.0–1.0; consistCars 0–500; T_pipe / T_cylinderApply / T_cylinderRelease 0.1–60 s; T_auxDrain / T_recharge 5–600 s; cylinderMagnification 0.5–10.0; ESU thresholds 1–100 monotonically increasing; ESU function numbers 0–28. Validation runs on Save/Apply per §2.4's flow.
- **Jynstrument click behaviour by state (decided 2026-05-02):** five states (no device / device-no-throttle / attaching / bound-elsewhere / fully operational) with distinct behaviours. See §2.6.

## 7. Known limitations accepted in this feature

- **Single-throttle only.** This feature doesn't introduce multi-loco support.
- **Per-roster scenario default deferred** (see [research §9.2.5]).
- **No prototype-physics derivation.** Every coefficient in the engine is operator-feel-tuned, not derived from prototype mass / power / TE / Davis values. Real prototype data informs the *shape* of the Westinghouse air-brake dynamics (pipe / aux / cylinder behaviour) but not the absolute rate constants.
- **Roster physics fields not consulted.** `RosterEntry.getPhysicsWeightKg()` / `getPhysicsPowerKw()` / `getPhysicsTractiveEffortKn()` / `getPhysicsMaxSpeedKmh()` are all ignored. The decoder + speed profile already determine physical loco speed at `s = 1.0`. Per-loco rate-tuning overrides via roster attributes are deferred future work.
- **Single rolling-drag rate.** Coast resistance is `dragCoeff · s · consistDragFactor` — linear in `s`, no quadratic / aerodynamic component. Per operator direction.
- **Westinghouse simulation is simplified.** Single aux-charge value (averaged across the consist), single cylinder pressure (averaged), no per-car air dynamics, no propagation delay along the trainline. Sufficient for operator-felt "running out of air" behaviour but not a faithful prototype simulator.
- **Dyn brake taper threshold is in `s`-space, not mph.** The engine has no velocity, so the threshold is "below this throttle setting" not "below 5 mph." Operators tune for desired feel.
- **No tests.** Parent §4.5 / deferred.
- **No help-page documentation.** Parent §4.6 / deferred.
- **All latent issues from parent §3 / §6 except the off-EDT mutation are still untouched.** The off-EDT issue is fixed in stage 1 (see §3.1's `RailDriverMenuItem.java` modifications).

## 8. Future work

- Tests (parent §4.5) — byte-parser, settings persistence round-trip, integration determinism with a stub DccThrottle (mirror existing JMRI test patterns for `AbstractThrottle` rather than introducing a new throttle proxy). Westinghouse state-machine unit tests (apply / release / depletion / recovery cycles).
- **Gradient gravity, curve resistance, journal-bearing breakaway** — additional rate terms in the per-tick composition, slotted into `ds/dt = ... − aGradient + aCurve − aBreakaway`. Each is operator-tuned in fraction/sec wall-clock units. Deferred until needed.
- **Per-roster rate-tuning overrides** via `RosterEntry.getAttribute("raildriver.semiRealistic.accelRate")` etc. Lets operators tune individual locos differently from their scenario default. Deferred.
- **Per-roster scenario default** via `RosterEntry.getAttribute("raildriver.scenario")`.
- **Per-car air-brake dynamics.** Currently the Westinghouse simulation tracks single average aux-charge and cylinder-pressure values for the consist. A more faithful simulation would propagate brake-pipe pressure along the trainline with delay, model individual cars' aux reservoirs and cylinders, and allow specific cars to be cut out. Deferred — not justified by single-throttle-on-a-model-railroad UX.
- Help / documentation updates (parent §4.6).
- i18n pass over the RailDriver UI: extract hardcoded English labels in `SemiRealisticSettingsPanel.java` and `CalibrationTabPanel.java` to `Bundle.properties` keys.
- Optional: a Settings-tab "Restore toolbar toggle" button.
- Remaining latent-issue fixes from parent §3 / §6.

## 9. Cross-references

- Research source: [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md).
- Parent: [`plan.md`](plan.md).
- RailDriver bring-up phase 1: [`plan-impl-phase1.md`](plan-impl-phase1.md) — connect & verify MVP.
- RailDriver bring-up phase 2: [`plan-impl-phase2.md`](plan-impl-phase2.md) — wire existing mappings, throttle-direction fix, F0/F28 redesign, slot 0..27 → F1..F28.
- RailDriver bring-up phase 3: [`plan-impl-phase3.md`](plan-impl-phase3.md) — calibration framework, visual bar UI, idle-range model, polling lifecycle decouple.
- Canonical bit-for-bit map: [`control-inventory.md`](control-inventory.md).
