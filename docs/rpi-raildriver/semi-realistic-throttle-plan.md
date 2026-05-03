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

The engine is built around a single integration loop that runs on a dedicated worker thread at a fixed 50 ms slice. The loop computes net wall-clock acceleration per the **wall-clock feel-tuning model from §1.0** — every coefficient is already a deceleration rate in m/s², no `F = m·a` step, no scale factors — integrates velocity, quantises to a DCC speed step, and posts the result to the EDT for `setSpeedSetting`. **There is no separate "accel path" and "decel path"** — sign of `a` falls out of which terms are active in any given slice.

```java
public final class SemiRealisticThrottleEngine {
    // — Mode —
    private volatile boolean enabled;            // false => bypass (phase-3 direct setSpeedSetting path)

    // — Settings (loaded from calibration XML <semiRealistic> subtree) —
    private final SemiRealisticSettings settings;

    // — Wall-clock feel-tuning coefficients (resolved per session from scenario) —
    //   Every value below is a wall-clock m/s² (or m/s² × shape-function-of-v) so the
    //   tick body is pure decel arithmetic. Mass cancels out everywhere because each
    //   coefficient is already a deceleration rate. See §1.0 for the framing.
    private volatile float maxAccelAtRestMs2;     // m/s² wall-clock at v=0, lever=full, no resist
    private volatile float vCornerMps;            // m/s above which a_drive falls as vCorner/v
    private volatile float driverPowerPct;        // 0.0..1.0, scenario-driven aggressiveness
    private volatile float designTopSpeedMps;     // scenario top speed; vTarget interpretation
    private volatile boolean steam;               // steam-power exponent flag (a_drive ∝ v^0.85)
    // Davis-shape coast resistance (wall-clock units, not prototype Davis):
    private volatile float resistStaticMs2;       // m/s² constant (dominates near halt)
    private volatile float resistLinearPerSec;    // 1/s — decel = b·v
    private volatile float resistQuadPerMeter;    // 1/m — decel = c·v²
    // Operator-controlled brake decels (wall-clock m/s²):
    private volatile float brakeMaxDecelMs2;      // mech brake @ 100% lever
    private volatile float airBrakeMaxDecelMs2;   // air brake @ 100% lever
    private volatile float dynBrakeMaxDecelMs2;   // dyn brake @ 100% lever (peak, before taper)
    private volatile float dynBrakeMassFraction;  // 0..1 dilution: 1.0 light engine, ≈0.1 unit train
    private volatile float dynBrakeVMinMps;       // m/s prototype velocity threshold (taper to 0 below)

    // — Live integration state (worker thread only, except where noted) —
    private float v_fs;                           // current prototype velocity, m/s
    private volatile float vTarget_fs;            // lever-derived target prototype velocity, m/s (set from polling thread)

    // — Latest physical inputs (units after calibration application; set from polling thread) —
    private volatile int     leverThrottleStep;       // 0..126 from Axis 1 above Idle High
    private volatile float   leverDynBrakeFraction;   // 0.0..1.0 from Axis 1 below Idle Low (stage 5)
    private volatile float   indepBrakeFraction;      // 0.0..1.0 from Axis 3 (stage 2)
    private volatile float   airLineFraction;         // 0.0..1.0 from Axis 2 (stage 4)
    private volatile boolean bailoffPressed;          // from byte 4 transient (stage 4)
    private volatile LoadScenario scenario;           // from picker (stage 2)
    private volatile int     direction;               // FORWARD / NEUTRAL / REVERSE from Axis 0

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

Per-tick logic (worker thread, every 50 ms while engine is enabled and either `v_fs` is nonzero or `vTarget_fs ≠ v_fs`):

```java
// 1. Read current input snapshot (volatile fields, set from polling thread)
float vTarget = Math.min(vTarget_fs, vCap_fs);   // prototype m/s, vCap_fs from getPhysicsMaxSpeedKmh()
float lever   = leverThrottleStep / 126.0f;     // 0..1
float brake   = indepBrakeFraction;
float air     = bailoffPressed ? 0.0f : airLineFraction;
float dyn     = leverDynBrakeFraction;
boolean drive = (leverThrottleStep > 0) && (v_fs >= 0);

// 2. Compute wall-clock decel/accel — pure m/s² arithmetic, no mass, no scale factor
float v_guard   = Math.max(0.01f, v_fs);
float steamMul  = steam ? (float) Math.pow(v_guard / Math.max(0.01f, designTopSpeedMps), 0.85f) : 1.0f;

// Drive: constant maxAccelAtRest below vCorner, falls 1/v above (P/v shape but with operator-tuned values)
float aDriveMax = (v_fs < vCornerMps)
    ? maxAccelAtRestMs2
    : maxAccelAtRestMs2 * (vCornerMps / v_guard);
float aDrive    = drive ? lever * driverPowerPct * steamMul * aDriveMax : 0.0f;

// Coast resistance: Davis-shape (static + linear + quadratic) in wall-clock decel units
float aResist   = resistStaticMs2
                + resistLinearPerSec * v_fs
                + resistQuadPerMeter * v_fs * v_fs;

// Brakes: simple multiplication, all in wall-clock m/s²
float aBrakeM   = brake * brakeMaxDecelMs2;
float aBrakeA   = air   * airBrakeMaxDecelMs2;
float taper     = Math.min(1.0f, v_fs / dynBrakeVMinMps);
float aBrakeD   = dyn * dynBrakeMaxDecelMs2 * dynBrakeMassFraction * taper;

// 3. Integrate (no clamp on sign of a) — a is wall-clock m/s²
float a   = aDrive - aResist - aBrakeM - aBrakeA - aBrakeD;
v_fs     += a * 0.050f;
if (v_fs < 0.0f)    v_fs = 0.0f;
if (v_fs > vCap_fs) v_fs = vCap_fs;              // hard cap: roster max-speed wins
if (Math.abs(v_fs - vTarget) < epsilon && Math.signum(a) != 0) v_fs = vTarget;

// 4. Quantise to DCC step via roster speed profile (or linear fallback) and emit
int dccStep = velocityToDccStep(v_fs);
ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(dccStep / 126.0f));
```

The steam-locomotive flag triggers a `v^0.85` exponent on the drive term (rough fit for steam tractive-effort fall-off). Gear-pause logic for mechanical-transmission locos coasts (drive zero) for 3.5 s at 15/27/41 mph crossings.

`vCap_fs` is the per-attach roster-derived speed cap, computed once at engine attach and on every settings reload from `Math.max(0.0f, RosterEntry.getPhysicsMaxSpeedKmh()) / 3.6f`. When the field is 0 (the JMRI default for a fresh roster entry), `vCap_fs` is set to `Float.POSITIVE_INFINITY` so the cap is effectively disabled and the integration is bounded only by the scenario's design top speed via the linear `velocityToDccStep` fallback. When the field is populated, both the lever-derived target and the integrated `v_fs` are clamped — a yard goat with `maxSpeedKmh = 30` cannot exceed 30 km/h regardless of throttle position or scenario.

Roster prototype-physics fields (`getPhysicsWeightKg`, `getPhysicsPowerKw`, `getPhysicsTractiveEffortKn`) are **not consulted** — the wall-clock-only model has no use for prototype mass/power/TE. The active scenario's coefficients drive everything; per-loco wall-clock feel-tuning overrides via roster attributes are future work (§1.2).

#### Worked example: wall-clock outcomes at default Light-engine constants

The defaults below (`maxAccelAtRest = 2.5`, `vCorner = 35.76`, `resistStatic = 1.0`, `resistLinear = 0`, `resistQuadratic = 0.001`, `brakeMaxDecel = 4.0`) are tuned for the operator-feel target "watchable coast, brisk accel, brake-dominant stops at any layout scale." All values below are wall-clock m/s² because the engine has nothing else.

| Phenomenon | Formula | Wall-clock outcome |
|---|---|---:|
| At-rest accel under full throttle, no brake | `maxAccelAtRest − resistStatic = 2.5 − 1.0` | **1.5 m/s²** |
| At top speed (35.76 m/s), full throttle, no brake | `aDriveMax(v_top) − aResist(v_top) = 2.5 · (1.0) − (1.0 + 0.001·35.76²) ≈ 2.5 − 2.28` | **0.22 m/s²** (asymptote) |
| Coast 80 mph (35.76 m/s) → 0 (no drive, no brake) | integration of `aResist(v) = 1.0 + 0.001·v²` | **~22 s** |
| Mech brake full at 80 mph → 0 (no drive) | `1/(brakeMaxDecel + aResist(v))` integrated | **~7 s** |
| Air brake EMG at 80 mph → 0 (no drive) | `1/(airBrakeMaxDecel + aResist(v))` integrated | **~5 s** |
| 0 → 80 mph under full throttle | integration of `aDrive(v) − aResist(v)` | **~50 s** (asymptotes near top) |

**Same numbers at any layout scale.** There is no scale dimension in the math. An HO operator and a Z-scale operator with identical Settings tab values get identical wall-clock behaviour. The Davis-shape coast curve is **speed-dependent**: aerodynamic-shape (quadratic) term dominates at high speed, static term dominates at low speed, so the train decelerates moderately from highway speed and creeps gently to a halt — prototypically realistic, but the absolute timing is operator-tuned, not derived from prototype physics.

### 2.2 Threading model

Three threads are involved. The EDT-discipline boundary is strict: **no Swing-touching code runs off the EDT, anywhere.** The pre-existing off-EDT mutation in the RailDriver bring-up code (parent §3 / §6) is fixed as part of stage 1 — see §3.1 for the wrapping work.

| Thread | What it does | What it must NOT do |
|---|---|---|
| **Polling thread** (`RailDriver`, existing) | Reads HID reports, fires `RawByte` and `Value` PCS events. `dispatchValueEvent` runs here as a PCS listener. | Touch any Swing component or call any method that reaches Swing (`setSpeedSetting`, `setIsForward`, `setFunction`, `JMenuItem.setEnabled`, etc.). |
| **Engine worker** (`RailDriver-SemiRealistic-Physics`, new) | Owned by the engine's single-thread `ScheduledExecutorService`. Runs the 50 ms physics integration tick. **Reads input snapshot, computes forces, integrates `v`, quantises to DCC step here** — all the math runs on this thread. | Touch Swing. |
| **EDT** (Swing's own thread) | All Swing-touching work: `setSpeedSetting`, `setIsForward`, `setFunction`, LED updates that route through Swing components, status-label changes, etc. | Block on the worker or polling thread (no `invokeAndWait`; would deadlock). |

The contract:

- `dispatchValueEvent` (polling thread) updates the engine's input fields (`leverThrottleStep`, `indepBrakeFraction`, `airLineFraction`, `leverDynBrakeFraction`, `bailoffPressed`, `direction`, `scenario`) — all `volatile` — and computes a fresh `vTarget_fs` from the lever calibration. **No Swing calls in the polling-thread path.** The integration task is permanently scheduled at fixed rate (50 ms); it picks up the new inputs on its next tick. **No cancel/reschedule churn** — the inputs are just `volatile` writes the worker reads on the next tick.

- The integration task's body runs on the engine worker. It computes net wall-clock acceleration locally and integrates velocity:
  ```java
  float a = aDrive - aResist - aBrakeM - aBrakeA - aBrakeD;
  v_fs   += a * 0.050f;       // worker thread, no Swing
  int dccStep = velocityToDccStep(v_fs);
  ```
  Only after the math is final does it hand the result to the EDT:
  ```java
  ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(dccStep / 126.0f));
  ```
  This keeps the *cadence* governed by the worker's `ScheduledExecutorService` (so timing is precise) and only the *application* of the value bounces through the EDT queue (so Swing stays consistent). If the EDT is busy, the lambda waits in the queue but the worker's next tick is unaffected — at worst the user sees one display-update of latency, never a missed integration slot.

- `dispatchValueEvent`'s phase-3 fall-back path (when semi-realistic mode is OFF) also wraps every Swing-touching call appropriately — see §3.1 deliverables. **Setters** (`throttle.setX`, `addressPanel.setX`, `throttleWindow.nextThrottleFrame` etc.) become `ThreadingUtil.runOnGUIEventually(() -> throttle.setX(...))` — fire-and-forget. **Getters whose return values feed the surrounding decision** (`throttle.getFunctions()`, `throttle.getFunctionMomentary(fNum)`, `throttle.getFunction(fNum)`) become `ThreadingUtil.runOnGUIwithReturn(() -> throttle.getFunctions())` — synchronous round-trip, blocks the polling thread for the duration of one EDT lambda. The math/decision logic stays where it is.

  We standardise on `ThreadingUtil` rather than direct `SwingUtilities.invokeLater` for consistency with the rest of JMRI (`jmri.jmrit.logix.Engineer`, `jmri.jmrit.throttle.AddressPanel`, etc. all use `ThreadingUtil`). `runOnGUIEventually(ta)` is a one-line wrapper around `SwingUtilities.invokeLater(ta)` (see `ThreadingUtil.java:262`) — runtime behaviour is bytecode-equivalent. `runOnGUIwithReturn(ta)` (`ThreadingUtil.java:223`) wraps `SwingUtilities.invokeAndWait` plus a `Reference<T>` shim, which `SwingUtilities` doesn't expose directly — so the synchronous-getter case needs `ThreadingUtil` regardless of stylistic preference. The single existing `SwingUtilities.invokeLater` call site in `RailDriverMenuItem.java` (line 244, in `attachThrottleWindow`) is migrated to `ThreadingUtil.runOnGUIEventually` as part of stage 1; the import for `javax.swing.SwingUtilities` is removed. (The other pre-existing `invokeLater` site cited in earlier drafts of this plan was in `RailDriverCalibrationFrame.java:423`, which is moot — that file is deleted in stage 1.)

- The volatile input fields (`leverThrottleStep`, `indepBrakeFraction`, `airLineFraction`, `leverDynBrakeFraction`, `bailoffPressed`, `direction`) are written by the polling thread and read by the worker — single-writer, single-reader, no interleaved compound ops, so volatile semantics suffice for those fields. The volatile feel-tuning fields (`maxAccelAtRestMs2`, `vCornerMps`, `driverPowerPct`, `designTopSpeedMps`, `steam`, `resistStaticMs2`, `resistLinearPerSec`, `resistQuadPerMeter`, `brakeMaxDecelMs2`, `airBrakeMaxDecelMs2`, `dynBrakeMaxDecelMs2`, `dynBrakeMassFraction`, `dynBrakeVMinMps`, `scenario`) are written by the EDT (in `rebuildPhysics` after Save / scenario-change / attach) and read by the worker — same single-writer-single-reader pattern. The worker reads the relevant fields at the start of each tick and uses the read values for the rest of the tick body, so a mid-tick settings change can't produce torn calculations. `v_fs` is a different case: it is normally touched only by the worker thread (no sync needed for tick-to-tick updates), **but** the mode-switch handover (§2.3) writes `v_fs` from the EDT inside `engine.setLiveEnabled()`, and the reverser interlock (§3.3) reads `v_fs` from the polling thread. All non-worker accesses to `v_fs` (write from `engine.setLiveEnabled`, read from polling-thread Axis 0 dispatch) go through the engine's `synchronized` block; `v_fs` is also declared `volatile` so the polling-thread read sees the latest worker write without entering the monitor.

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

- **OFF → ON:** at the moment `liveEnabled` flips to true, the engine reads `throttle.getSpeedSetting()` once (on the EDT), converts that DCC fraction to a full-scale velocity using the loco's roster speed profile (or a linear fallback when no profile exists), and writes that value to `engine.v_fs` (under the engine's `synchronized` block). It then refreshes the input snapshot from the current lever positions. The integration task starts ticking; on each tick it computes net force from current inputs and walks `v_fs` toward the lever-derived `vTarget_fs`. If the lever's target is far from the loco's current speed, the loco accelerates or decelerates under physics until it gets there. **No speed snap.** The loco's perceived speed is continuous across the toggle.

- **ON → OFF:** at the moment `liveEnabled` flips to false, the engine pauses its integration task and `dispatchValueEvent` reverts to the direct path. The very next byte change on any axis writes the lever-derived value via `setSpeedSetting()` directly. **If the lever is far from the engine's last `v_fs`, the loco will snap to the lever-derived speed on the next dispatch.** Operators are expected to either move the lever to match the loco's current speed before flipping OFF, or to accept the snap as the cost of switching to direct control mid-motion.

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
2. If `liveEnabled` is transitioning OFF → ON: read `throttle.getSpeedSetting()` on the EDT, post a `Runnable` to the engine that converts the fraction to `v_fs` (via roster speed profile when available) and starts the integration task under `synchronized`.
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

The Settings tab is grouped into four sections corresponding to the §1.0 coefficient categories so the operator sees what they're tuning:

**Mode**
- **Enable semi-realistic mode** checkbox (the master switch).

**Loco scenario** (feel-coefficient preset)
- **Scenario:** dropdown — `Light engine` (default) / `Switcher` / `EMD NW2` / `Local freight` / `Through freight` / `Unit train` / `Custom` (with the per-coefficient override fields below editable when `Custom` is selected). Switching scenarios applies that scenario's tuned coefficients to all Drive / Coast / Brake fields below; if the operator wants to start from a scenario and tweak, they switch to that scenario, then change to `Custom` to unlock the fields without losing the values.

**Drive coefficients** (operator-tunable wall-clock acceleration profile)
- **Max accel at rest (m/s² wall-clock):** numeric, scenario default. Wall-clock acceleration the loco achieves at v=0 under full throttle, with no resistance, no brakes. Defines "how snappy does the loco feel when I open the throttle from a dead stop."
- **Power fall-off corner speed (m/s):** numeric, scenario default = `designTopSpeedMps`. Speed above which `a_drive` falls off as `vCorner / v` (mimicking the constant-power physics shape of a real loco). Below this, `a_drive` stays flat at `maxAccelAtRest · lever`.
- **Driver power (%):** numeric 0–100, scenario default. Multiplies the entire drive curve. Operators dial this back to soft-start a heavy train, or up for spirited driving.
- **Design top speed (mph or m/s):** numeric, scenario default. Determines what `vTarget` the throttle lever interprets to at full position; also clamps `v_fs` if no roster `getPhysicsMaxSpeedKmh` is set.
- **Steam locomotive:** checkbox, scenario default. When ON, `a_drive` is multiplied by `(v / vTopMps)^0.85` to approximate steam tractive-effort fall-off.

**Coast resistance — Davis-equation shape, wall-clock units**
- **Static rolling resistance (m/s² wall-clock):** numeric, scenario default. Constant-with-velocity decel near halt — bearing friction, journal drag. Dominant near v=0; defines how quickly the loco creeps to a halt at the end of a coast.
- **Linear mechanical resistance (1/s):** numeric, scenario default, often 0. Linear-in-velocity decel; mostly useful for fine-tuning the mid-speed coast curve.
- **Aerodynamic drag (1/m):** numeric, scenario default. Quadratic-in-velocity decel; dominant at speed. Defines how aggressively the loco coasts down from highway speed before settling onto the static term near halt.

**Brake decel rates** (operator-controlled, wall-clock m/s²)
- **Mechanical brake max decel (m/s² wall-clock):** numeric, scenario default. Decel applied per 100 % indep brake.
- **Air brake max decel (m/s² wall-clock):** numeric, scenario default. Decel applied per 100 % air-line setting.
- **Dynamic brake max decel (m/s² wall-clock):** numeric, scenario default. Peak decel at 100 % dyn-brake setting before speed taper.
- **Dynamic brake mass fraction (0..1):** numeric, scenario default. Scales the dyn-brake contribution by how much of the train mass is loco — 1.0 for a light engine, ~0.1 for a unit train. Captures that real dyn brake doesn't propagate through trainline air.
- **Dynamic brake taper threshold (mph prototype):** numeric, scenario default = 5. Below this prototype velocity, dyn brake scales linearly to zero.

**Decoder integration**
- **Decoder-brake mode:** dropdown `None` / `ESU` (and ESU-only sub-fields when ESU is selected — F-numbers + thresholds).

**Reset to defaults** button (settings-tab-scoped — restores only the semi-realistic fields to the active scenario's defaults; does not touch calibration values).

#### 2.4.1 Scenario defaults

Scenarios are **feel-coefficient presets**, not prototype simulations. Each scenario is tuned for a distinct operator experience: Light Engine = nimble responsive single loco; Switcher = slower top speed, brisk yard-style accel; Local Freight = noticeably weighted, modest brake response; Through Freight = sluggish accel, long coast, requires planning ahead; Unit Train = even more so. Switching scenarios is the operator's "weight selector" — heavier scenarios feel heavier because their coefficients say so, not because mass enters the math.

| Scenario | Top speed | Max accel @ rest | vCorner | Driver pwr | resist static | resist linear | resist quad | Brake mech | Brake air | Brake dyn | Dyn mass frac | Steam |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Light engine (default)** | 80 mph (35.76 m/s) | 2.5 | 35.76 | 100 % | 1.0 | 0.0 | 0.001 | 4.0 | 6.0 | 1.6 | 1.0 | no |
| Switcher | 30 mph (13.41 m/s) | 2.0 | 13.41 | 80 % | 1.5 | 0.0 | 0.005 | 4.0 | 6.0 | 1.0 | 0.7 | no |
| EMD NW2 | 45 mph (20.12 m/s) | 2.5 | 20.12 | 90 % | 1.5 | 0.0 | 0.002 | 4.0 | 6.0 | 0.0 | 1.0 | no |
| Local freight | 80 mph (35.76 m/s) | 1.5 | 35.76 | 90 % | 1.0 | 0.0 | 0.0008 | 3.0 | 5.0 | 0.8 | 0.3 | no |
| Through freight | 80 mph (35.76 m/s) | 1.0 | 35.76 | 100 % | 0.8 | 0.0 | 0.0006 | 2.5 | 4.0 | 0.5 | 0.15 | no |
| Unit train | 80 mph (35.76 m/s) | 0.7 | 35.76 | 100 % | 0.6 | 0.0 | 0.0004 | 2.0 | 3.5 | 0.3 | 0.08 | no |
| Custom | (operator-tunable; defaults inherited from Light engine when the operator first switches to Custom) | — | — | — | — | — | — | — | — | — | — | — |

(Units: top speed in m/s prototype; "Max accel @ rest", "Brake *" in m/s² wall-clock; "vCorner" in m/s prototype; "resist static" in m/s² wall-clock; "resist linear" in 1/s; "resist quad" in 1/m; "Driver pwr" as percentage; "Dyn mass frac" dimensionless 0..1; Steam is a flag.)

The dynamic-brake taper threshold is scenario-independent at 5 mph prototype; not exposed in the per-scenario table above.

**EMD NW2 scenario** is tuned to the real EMD NW2 switcher: 1000 HP / 256 kN starting TE / 112 t / max 65 mph (typically geared 45 mph). Most NW2 units shipped without dynamic brake, so `Brake dyn = 0` — the dyn-brake region of the throttle lever produces no decel under this scenario (operators who happen to have a dyn-brake-equipped NW2 can switch to Custom and dial in a value). Coefficients are operator-feel-tuned for an N-scale model railroad, not derived from prototype Davis values; the *shape* matches NW2 real-world coast character (Davis A:B:C ≈ 1.5:0:0.002 in wall-clock units mirrors the prototype A:B:C ratio of ≈ 1.6:0.044:0.00108 in lb/ton·mph units — A dominates near halt, C dominates at speed).

There is **no per-roster physics override** — the scenario is the source of truth. `RosterEntry.getPhysicsWeightKg()` / `getPhysicsPowerKw()` / `getPhysicsTractiveEffortKn()` are not consulted (the engine has no use for prototype mass / power / TE values). `RosterEntry.getPhysicsMaxSpeedKmh()` IS consulted as a wall-clock cap on `v_fs` — it's already a wall-clock-felt value and overrides scenario `designTopSpeed` per loco when populated. Per-loco wall-clock feel-tuning overrides via roster attributes are deferred future work.

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
        <!-- Drive coefficients (wall-clock m/s² and m/s) -->
        <maxAccelAtRestMs2>2.5</maxAccelAtRestMs2>
        <vCornerMps>35.76</vCornerMps>
        <driverPowerPercent>100</driverPowerPercent>
        <designTopSpeedMps>35.76</designTopSpeedMps>
        <steam>false</steam>
        <!-- Davis-shape coast resistance coefficients (wall-clock units) -->
        <resistStaticMs2>1.0</resistStaticMs2>           <!-- A: m/s² constant -->
        <resistLinearPerSec>0.0</resistLinearPerSec>     <!-- B: 1/s -->
        <resistQuadPerMeter>0.001</resistQuadPerMeter>   <!-- C: 1/m -->
        <!-- Brake decels (wall-clock m/s²) -->
        <brakeMaxDecelMs2>4.0</brakeMaxDecelMs2>
        <airBrakeMaxDecelMs2>6.0</airBrakeMaxDecelMs2>
        <dynBrakeMaxDecelMs2>1.6</dynBrakeMaxDecelMs2>
        <dynBrakeMassFraction>1.0</dynBrakeMassFraction>
        <dynBrakeVMinMph>5</dynBrakeVMinMph>
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

Schema migration: existing files (`version="1"`) load cleanly because the loader's existing tolerance for missing elements treats `<semiRealistic>` as absent ⇒ defaults (= disabled, which is the bring-up-era fallback ⇒ no behaviour change for legacy files). Within `version="2"`, individual children that are missing or unparseable also fall back to the active scenario's defaults. **Pre-wall-clock-refactor stage-2 files (with the old prototype-frame elements `<locoMassKg>`, `<locoPowerKw>`, `<locoTractiveEffortKn>`, `<additionalWeightTonnes>`, `<rollingResistanceCoeff>`, `<rollingResistanceStatic>`, `<rollingResistanceLinear>`, `<aerodynamicDragCoeff>`, `<physicsTimeScale>`) are not migrated** — the wall-clock refactor replaces every coefficient with a different unit, and existing development calibrations are expected to be deleted before testing. Those legacy elements are silently ignored on load; the wall-clock fields fall back to scenario defaults. The `version` attribute exists so a later schema-3 change can branch cleanly.

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

### 3.2 Stage 2 — Wall-clock physics engine + bypass + scenario picker + independent brake + toolbar mode toggle

**Goal:** first stage where `liveEnabled = ON` produces a usable feature on a model railroad layout. The throttle lever sets `vTarget_fs` and the integrator walks `v_fs` toward it under the **wall-clock-only model from §1.0** — every coefficient is a wall-clock m/s² (or m/s² shape function of `v`), no mass / force / scale dimensions in the math. The calibrated independent-brake lever (Axis 3) provides a working `a_brake_mech` term so the operator can stop. The scenario picker is wired so the operator can change feel-coefficient presets. The Settings tab exposes the drive coefficients (`maxAccelAtRest`, `vCorner`, `driverPowerPct`, `designTopSpeed`, `steam`), the Davis-shape coast resistance coefficients (`resistStatic`, `resistLinear`, `resistQuadratic`), and the brake decels (`brakeMaxDecel`, `airBrakeMaxDecel`, `dynBrakeMaxDecel`, `dynBrakeMassFraction`, `dynBrakeVMin`). The toolbar Jynstrument is auto-installed for fast session-level mode toggling.

The integration body computes wall-clock acceleration directly: `aDrive = lever · driverPowerPct · steamMul · (vCorner < v ? maxAccelAtRest · vCorner/v : maxAccelAtRest)`; `aResist = resistStatic + resistLinear · v + resistQuadratic · v²`; `aBrakeM = indepBrakeFraction · brakeMaxDecel`. The remaining brake terms `aBrakeA` and `aBrakeD` are present in the equation as zero-valued summands until stages 4–5. Mechanical-transmission locos coast at 15/27/41 mph crossings (3.5 s gear pause; mph thresholds are prototype values, the 3.5 s coast duration is wall-clock). The integration runs symmetrically (no `a < 0` clamp) so accel and decel are the same code path with different terms active.

Velocity ↔ DCC step conversion uses `RosterSpeedProfile.getSpeed(throttleStep, isForward)` when a calibrated speed profile exists, falling back to a linear `dccStep = round(v_fs / vMax_fs · 126)` mapping (where `vMax_fs` is the scenario's design top speed, or `getPhysicsMaxSpeedKmh()` when populated) when it doesn't. With the linear fallback the loco's wall-clock-tuned behaviour is preserved; only the absolute speed-step calibration is approximate.

**New / modified files:**

*New:*
- `java/src/jmri/util/usb/SemiRealisticThrottleEngine.java` — engine with the integration tick / `ScheduledExecutorService` / DccThrottle access. Throttle and indep-brake paths fully wired with the §1.0 wall-clock-only model from day one. Air-brake / dyn-brake input fields stay zero until stages 4–5. Worker thread does the math; EDT does the `setSpeedSetting`.
- `java/src/jmri/util/usb/SemiRealisticSettings.java` — settings POJO with load + save methods, mirroring `RailDriverCalibration`'s structure. Holds the drive coefficients (`maxAccelAtRestMs2`, `vCornerMps`, `driverPowerPct`, `designTopSpeedMps`, `steam`), the Davis-shape resistance coefficients (`resistStaticMs2`, `resistLinearPerSec`, `resistQuadPerMeter`), the brake decels (`brakeMaxDecelMs2`, `airBrakeMaxDecelMs2`, `dynBrakeMaxDecelMs2`, `dynBrakeMassFraction`, `dynBrakeVMinMph`), the scenario enum reference, the `persistedEnabled` and `liveEnabled` fields, and (placeholder, used by stage 6) the `decoderBrakeMode` enum.
- `java/src/jmri/util/usb/LoadScenario.java` — enum with the seven scenarios from §2.4.1 (Light engine, Switcher, **EMD NW2**, Local freight, Through freight, Unit train, Custom) and their tuned coefficient defaults. Each scenario constant carries values for all 13 feel coefficients.
- `java/src/jmri/util/usb/SemiRealisticSettingsPanel.java` — the Settings tab content. Replaces stage 1's placeholder. Implements the `isDirty / addDirtyChangeListener / validateAndApplyTo / resetToFile` contract from §2.4. Field grouping per §2.4 (Mode / Loco scenario / Drive coefficients / Coast resistance / Brakes / Decoder integration). Disables fields based on the `Enable semi-realistic mode` checkbox; switches per-coefficient rows between read-only "scenario default" display and editable when scenario = Custom. Decoder-brake-mode dropdown is present but has only `None` available (the `ESU` option is enabled in stage 6).
- `jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/RailDriverModeToggle.py` — the Jynstrument from §2.6. ~80 lines of Jython following the pattern of existing toolbar Jynstruments (`DCCThrottle.jyn`, `WiimoteThrottle.jyn`). Implements `init()`, `quit()`, `getExpectedContextClassName()` (returns `"jmri.jmrit.throttle.ThrottleWindow"`), creates a single `JButton` showing the on/off/binding icon, registers a Java `PropertyChangeListener` on `RailDriverMenuItem` for the `"liveEnabledChanged"` / `"railDriverConnected"` / `"activeThrottleFrame"` / `"attachInProgress"` events (subscribes to `liveEnabledChanged`, NOT `persistedEnabledChanged` — the Jynstrument shows the live session value, not the persisted value), tracks an internal `pendingSessionToggle` flag for the State 2 → State 4 deferred toggle described in §2.6, and builds a one-item `JPopupMenu` with `Settings...` invoking `RailDriverSettingsAction`. `quit()` is a pure listener-deregistration hook with no persistence side effects.
- `jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/icons/raildriver-on.png`, `raildriver-off.png`, and `raildriver-binding.png` — three 24×24 (or whatever size matches existing toolbar icons; check `resources/icons/throttles/*.png` for the convention) icons in the Jynstrument folder. The "binding" icon is shown during the State 2.5 attach-in-progress window.

*Modified:*
- `java/src/jmri/util/usb/RailDriverCalibration.java` — populate the `<semiRealistic>` subtree on save and on load (§2.5 schema). Hold a `SemiRealisticSettings` field accessor.
- `java/src/jmri/util/usb/RailDriverMenuItem.java`:
   1. Instantiate the engine in `attachThrottleWindow`; route `dispatchValueEvent` Axis 1 dispatch through the engine when `settings.liveEnabled`.
   2. Add the Axis 3 case to `dispatchValueEvent`: compute `indepBrakeFraction = clamp((calibratedFullRelease - byteValue) / (calibratedFullRelease - calibratedFullApplication), 0, 1)`; set `engine.indepBrakeFraction`. The integration tick reads this volatile field on each slice and contributes `aBrakeM = indepBrakeFraction · brakeMaxDecel` to net acceleration. No regime detection, no "throttle fighting brake" branch — when both throttle and brake are applied, the integrator sums decels and the sign of `a` falls out.
   3. Extend `reloadCalibration()` to notify the engine of new semi-realistic settings (or add a sibling `reloadSemiRealisticSettings()` if the engine needs distinct hooks — implementation detail).
   4. Add `isSemiRealisticLiveEnabled()`, `isSemiRealisticPersistedEnabled()`, `setSemiRealisticEnabledSessionOnly(boolean)`, `isRailDriverConnected()`, `getActiveThrottleFrame()`, `isAttachInProgress()`, `requestAttachToThrottle(ThrottleFrame)`, `addSettingsListener(PropertyChangeListener)`, `removeSettingsListener(PropertyChangeListener)` per §2.6. `setSemiRealisticEnabledSessionOnly` snapshots the old `liveEnabled` value, mutates it in memory only (no XML write), runs the §2.3 mode-switch handover via `engine.setLiveEnabled`, and fires `"liveEnabledChanged"`. The Settings-tab Save flow lives in `RailDriverSettingsFrame.doSaveOrApply` — it builds a fresh `RailDriverCalibration working`, has both tabs write into it via their `validateAndApplyTo` methods (the Settings tab sets `working.semiRealistic.persistedEnabled := checkbox` and `working.semiRealistic.liveEnabled := working.semiRealistic.persistedEnabled`; the Calibration tab uses `copyCalibrationFieldsFrom` so it doesn't clobber the `<semiRealistic>` subtree), then calls `working.save(file)` and `mi.reloadCalibration()`. `reloadCalibration` snapshots the OLD `persistedEnabled`/`liveEnabled` from the in-memory calibration *before* replacing it, reloads from disk, pushes the new settings to the engine, and fires `"persistedEnabledChanged"` / `"liveEnabledChanged"` for the values that changed. `requestAttachToThrottle` is a no-op if the requested frame is already the bound one; otherwise sets an internal `attachInProgress = true` and fires `"attachInProgress"` (true), then schedules the bind via `ThreadingUtil.runOnGUIEventually`. The bind path (`ensureDeviceAndPolling()` then `attachThrottleWindow()` against the requested frame) fires `"activeThrottleFrame"` on success and finally `"attachInProgress"` (false) once the activeThrottleFrame event has been dispatched. The existing `HidServicesListener` callbacks are extended for VID/PID hot-plug awareness: `hidDeviceAttached` (currently a commented-out no-op at `RailDriverMenuItem.java:539–546`, originally gated on a now-removed `invokeOnMenuOnly` flag) is re-enabled to fire `firePropertyChange("railDriverConnected", false, true)` on VID/PID match **without** auto-calling `setupRailDriver()` — the Debug-menu workflow keeps owning the polling lifecycle, so cold-plug behaviour is unchanged for users without the Jynstrument; only the Jynstrument's icon state is affected. `hidDeviceDetached` (which already nulls `hidDevice` on VID/PID match at line 552–557) gains a sibling `firePropertyChange("railDriverConnected", true, false)` call.
   5. Auto-install the Jynstrument at the end of `attachThrottleWindow`'s success path, idempotent across repeat calls within a single attach. Helper `private static boolean hasJynstrumentInstalled(ThrottleWindow tw, String classNameSuffix)` is a recursive descent over `tw.getContentPane().getComponents()` (`ThrottleWindow.throttleToolBar` is private with no public getter, verified at `ThrottleWindow.java:57`; the recursive walk inspects every contained `JToolBar` for `Jynstrument` instances and matches by class-name suffix). The walk extends a precedent established inside `ThrottleWindow` itself: the close-handler at `ThrottleWindow.java:160–167` and the save-Jynstruments code at `ThrottleWindow.java:801–810` both iterate `throttleToolBar.getComponents()` and `instanceof Jynstrument`-check each child; our walk only adds the recursive descent because we lack direct access to the toolbar reference. Auto-install is gated **only** by `hasJynstrumentInstalled`. **There is no `<jynstrumentAutoInstallSuppressed>` flag, no `seenQuitThisSession` flag, and no other in-memory or on-disk state for the toolbar's presence.** Saved-layout-XML restoration (`ThrottleWindow.java:800–865`) runs before `attachThrottleWindow`, so a saved layout that includes the Jynstrument is detected by the walk and auto-install becomes a no-op for that session. A saved layout that omits the Jynstrument restores the empty toolbar; auto-install then re-adds it — meaning "save throttle layout with toggle removed" by itself is **not** sufficient to keep the toggle out across `Debug → RailDriver Throttle (built in)` re-clicks, because the auto-install treats a re-click as a deliberate operator action equivalent to the first attach. Operators who want the removal to be sticky across re-clicks must use the deferred-future "Restore toolbar toggle" workflow (currently: drag-install only) to manage the toggle's presence explicitly. The Jynstrument's `quit()` hook is solely a session-local listener-deregistration cleanup — see §2.6's Jython code sketch.
- `java/src/jmri/util/usb/Bundle.properties` — no changes in stage 2. Settings-tab labels are kept as hardcoded English literals in `SemiRealisticSettingsPanel.java`, matching the existing pattern in `CalibrationTabPanel.java`. Externalising both panels' labels to Bundle keys is deferred to a future i18n pass — see §8 future work.

**Acceptance:**
1. The Settings tab is now functional: scenario picker shows all 7 scenarios; selecting `Custom` enables all per-coefficient fields; non-Custom scenarios show the active scenario's preset values as read-only; switching scenarios snaps all per-coefficient fields to the new scenario's defaults. Field validation matches §6's validation ranges.
2. Mode OFF: throttle behaves exactly as in stage 1 (operator-perceptibly identical).
3. Mode ON, default Light-engine scenario, indep brake at Full Release: moving the throttle lever from idle to full speed produces a visible ramp on the loco. With Light-engine defaults (`maxAccelAtRest = 2.5`, `resistStatic = 1.0`, `resistQuadratic = 0.001`), at-rest accel is **1.5 m/s² wall-clock** (= 2.5 − 1.0); at top speed of 80 mph (35.76 m/s) accel is **0.22 m/s² wall-clock** asymptote (drive 2.5 vs. resist 2.28). Time from 0 to top speed is on the order of **~50 s wall-clock** (full-throttle accel slows as resist grows). **No snap, no jitter, no missed slots** during a 5-minute lever-sweep session. **Same behaviour at any layout scale** because the model has no scale dimension.
4. Mode ON, indep brake at Full Application + throttle at zero, Light-engine: loco decelerates at **4.0 m/s² (= `brakeMaxDecel`) plus the Davis-shape coast resistance** at the current velocity. Time from 80 mph (35.76 m/s) to 0 is **~7 s wall-clock** (brake-dominated; coast resistance contributes a small additional decel that varies with v).
5. Mode ON, indep brake mid-travel while throttle is at full, default Light-engine: integrator computes net wall-clock decel directly. At rest with brake=50%, drive=full: `a = 2.5 − 1.0 − 0.5·4.0 = −0.5 m/s²`, loco decelerates slightly. With brake=25%: `a = 2.5 − 1.0 − 1.0 = +0.5 m/s²`, loco creeps forward. **Emergent behaviour, not coded as a special case.**
6. Mode ON, releasing the indep brake while at speed: `aBrakeM = 0` on the next tick; integrator resumes accelerating toward the lever's target (against the speed-dependent Davis-shape resistance).
7. Switching scenarios mid-accel (e.g. `Light engine` → `Unit train`): the integrator picks up the new scenario's coefficients on the next 50 ms tick; accel rate jumps because every coefficient changes — Unit Train's `maxAccelAtRest = 0.7` vs Light Engine's `2.5`. The transition is immediate (no inertia or interpolation between scenarios).
8. The `getPhysicsMaxSpeedKmh()` cap clamps both `vTarget` and integrated `v_fs`. Other roster physics fields are not consulted (the wall-clock-only model has no use for prototype mass / power / TE).
9. Mode OFF → ON at speed: engine reads `throttle.getSpeedSetting()`, converts to `v_fs`, integrates smoothly toward lever target — no snap (per §2.3 handover contract).
10. Mode ON → OFF at speed: engine pauses; next byte change writes lever-derived value directly via `setSpeedSetting()` (operator-accepted snap if lever is far from `v_fs`).
11. **Coast-down — Davis-shape acceptance.** Mode ON, dropping the throttle to idle while at speed (no brake input): the loco coasts down on the Davis-shape coast resistance alone — `aResist(v) = resistStatic + resistLinear·v + resistQuadratic·v²`. The decel is **speed-dependent**: aggressive at high speed (quadratic-dominated), gentle at low speed (static-dominated). With Light-engine defaults at 80 mph (35.76 m/s) → 0 wall-clock ≈ **22 s** (initial decel ~2.28 m/s² at 80 mph dropping to 1.0 m/s² at halt). **Same time at HO, N, Z, or any other layout scale.** Operators wanting different feel adjust the three Davis-shape coefficients on the Settings tab.
12. **No-stuck invariant.** With any scenario from §2.4.1 at default values, `maxAccelAtRest > resistStatic` so at-rest net accel under full throttle is positive at every supported scale (no scale dependence — every coefficient is wall-clock). Light-engine: 2.5 − 1.0 = +1.5; Unit Train: 0.7 − 0.6 = +0.1 (marginal but nonzero). The previous broken architectures (single-coefficient × physicsTimeScale and Davis × physicsTimeScale) violated this at scales above HO; the wall-clock model can't violate it because there is no scale multiplier to grow the resistance unboundedly.
13. The toolbar mode-toggle Jynstrument auto-installs on first `Debug → RailDriver Throttle (built in)` click. The icon shows the current **live** `enabled` state. Clicking it flips the live state **for this session only** (no XML write). The Settings tab's `Enable semi-realistic mode` checkbox shows the **persisted** value (last-saved or default), independent of any session-only Jynstrument toggles. Right-clicking the Jynstrument shows a `Settings...` item that opens the unified Settings frame. To make a session-level Jynstrument change persist, the operator opens the Settings window, sets the checkbox to match, and Saves.
14. The Jynstrument is idempotent on repeat attaches within a session — opening the throttle, closing it, and re-opening via the Debug menu does NOT add a second copy of the toggle to the toolbar.
15. If the operator explicitly removes the Jynstrument from the toolbar (right-click → Quit), it stays removed for the rest of the throttle window's lifetime. Re-clicking `Debug → RailDriver Throttle (built in)` triggers `attachThrottleWindow()` again, which **does** re-add the toggle — this is treated as a deliberate operator action equivalent to a fresh attach.
16. RailDriver hot-plug: unplugging the device fires `"railDriverConnected"` (false), greying the Jynstrument; replugging fires `"railDriverConnected"` (true), un-greying it. The Debug-menu lifecycle is unaffected.
17. Pre-existing XML files (schema `version="2"` from stage 1, no `<semiRealistic>` subtree) load cleanly — semi-realistic fields populate from defaults (disabled, Light engine); saving produces a `version="2"` file with the new `<semiRealistic>` subtree.

### 3.3 Stage 3 — Reverser interlock

**Goal:** [research §5] direction can only change at speed 0.

**Modified:** `dispatchValueEvent` Axis 0 case to suppress `setIsForward(...)` when `settings.liveEnabled && engine.v_fs > epsilon`. E-Stop SPDT keeps `setSpeedSetting(-1)`. Reverser to NEUTRAL at any speed forces a coast-down per [research §3.3] (engine treats `v_target = 0` and zero `aDrive`; loco decelerates under coast resistance plus any active brakes).

The interlock consults `engine.v_fs` (the engine's view of current velocity) rather than `throttle.getSpeedSetting()`. The two are consistent in semi-realistic mode — `v_fs` is the value just integrated into `setSpeedSetting()` on the previous tick — but `v_fs` is a `volatile` field on the engine, readable from the polling thread without crossing the EDT. The `settings.liveEnabled` guard ensures the field is only consulted while the engine is actively maintaining it (per the §2.3 handover contract: `v_fs` is set on OFF→ON and continuously updated thereafter; in OFF mode it is not consulted).

**Pending-direction-change behaviour: byte-edge-triggered, not retried on stop.** When the operator moves the reverser lever during deceleration, the dispatch suppresses the direction change at the moment the byte changes. Once the loco reaches `v_fs == 0`, the dispatch does NOT retroactively apply the held lever position — the operator must nudge the reverser lever again (any byte change re-evaluates the interlock) to actually flip the direction. This matches prototype behaviour: on a real locomotive the engineer holds the reverser handle in the new position while waiting for speed=0, then either the handle physically engages or the engineer moves it the rest of the way once the train is stopped. We don't add engine-side state for "pending direction change" because that's not how the prototype works.

**Acceptance:**
1. Loco at speed > 0 + reverser moved to opposite direction: direction does NOT flip; an INFO log line records the suppression. Direction lever change with loco at speed 0 still works.
2. Loco decelerating + reverser already moved to opposite direction (held there during the ramp-down): direction still does NOT flip when `v_fs` reaches 0. Operator must release-and-re-move the reverser to trigger the byte-edge-driven direction change. Documented as expected behaviour, not a bug.
3. Reverser to NEUTRAL at any speed: loco coasts to a stop on the deceleration curve regardless of throttle/brake levers (per [research §3.3]).
4. E-Stop SPDT at any speed: loco hard-stops via `setSpeedSetting(-1)`. Same as the existing RailDriver bring-up behaviour.
5. With Mode OFF (`liveEnabled == false`): the interlock is skipped; reverser changes propagate immediately as in stage 1.

### 3.4 Stage 4 — Auto brake → air-line decel + bail-off override

**Goal:** Auto Brake (Axis 2) drives `airLineFraction`, contributing `aBrakeA`; bail-off (byte 4) zeroes the air term while held.

**Modified:** `dispatchValueEvent` Axis 2 case (compute `airLineFraction = clamp((calibratedReleased - byteValue) / (calibratedReleased - calibratedEmg), 0, 1)`; Released → 0, EMG → 1; set `engine.airLineFraction`); button dispatch for the bail-off switch (set `engine.bailoffPressed = true/false` based on byte-4 threshold-crossing); `SemiRealisticThrottleEngine.java`'s integration tick now reads the air decel via `air = bailoffPressed ? 0.0f : airLineFraction` and adds `aBrakeA = air · airBrakeMaxDecel` as another subtractive decel.

This replaces EngineDriver's reservoir-and-line repeater simulation [research §4.2] with a direct mapping. It's both simpler in code and more prototypical (the operator's hand position *is* the air pressure on a real RailDriver). The reservoir-with-recharge state machine is **out of scope** unless the operator specifically wants to simulate "running out of air" — which they don't on a console with a real Auto Brake handle.

**Acceptance:**
1. Auto brake at Released: `aBrakeA = 0`, integrator walks toward lever target as in stage 2.
2. Auto brake at EMG, throttle at zero, Light-engine: loco decelerates at **6.0 m/s² wall-clock** (= `airBrakeMaxDecel`). 80 mph → 0 in **~5 s wall-clock** (Davis-shape coast resistance contributes a small additional decel). Same time at any layout scale.
3. Auto brake at 50 % + indep brake at Full Release: `aBrakeA = 0.5 · 6.0 = 3.0 m/s²`. Combined with `aBrakeM = 0`, net decel ≈ 3.0 m/s² wall-clock plus coast resistance. When indep brake is also applied at 50 %: `aBrakeA + aBrakeM = 3.0 + 2.0 = 5.0 m/s²`, total decel ≈ 5.0 m/s² wall-clock plus coast — **decels sum, no `min(...)` clipping. Stronger braking than either alone, which is the prototype reality**: the EngineDriver `min(airLineAsBrakePcnt, brakePcnt)` rule [research §3.4] was an arcade simplification we deliberately don't reproduce.
4. Auto brake at SUP/CS + bail-off pressed: air-line term zeroes, only mechanical brake (if any) remains. Loco accelerates again under throttle if mechanical brake is also released.
5. Releasing bail-off restores the air-line term immediately (next tick).

### 3.5 Stage 5 — Dynamic brake (lever UP)

**Goal:** the half of the throttle lever above center (toward DYN BRAKE label) finally does something — adds an `aBrakeD` term scaled by the per-scenario `dynBrakeMassFraction` and tapered near zero speed.

**Modified:** `dispatchValueEvent` Axis 1 case to compute `leverDynBrakeFraction = clamp((calibratedIdleLow - byteValue) / (calibratedIdleLow - calibratedFullDynBrake), 0, 1)` when the byte is below Idle Low (= the dyn-brake side); `SemiRealisticThrottleEngine.java`'s integration tick adds `aBrakeD = leverDynBrakeFraction · dynBrakeMaxDecel · dynBrakeMassFraction · speedTaper(v_fs)` where `speedTaper(v) = min(1.0, v / dynBrakeVMinMps)`. The `DBr` LED, currently a TODO from the existing RailDriver bring-up, becomes the indication that the dyn-brake region is active.

Two notable differences from mechanical/air brake:
- Scaled by `dynBrakeMassFraction` (per-scenario; 1.0 for Light Engine down to ~0.08 for Unit Train). Real dyn brake doesn't propagate through trainline air [research §10 item 1] — only the loco's traction motors generate the braking torque, so the brake decel is diluted by the consist mass. The dilution coefficient is encoded per-scenario rather than computed from a `m_loco / m_total` ratio because the engine is mass-free.
- Speed-tapered. Real dyn brake fades to zero below ~5 mph because traction motors lose torque at low speed. `speedTaper(v)` is the linear approximation; below `dynBrakeVMinMps` (default 5 mph = 2.24 m/s) the term scales linearly to zero.

Stacks with mechanical and air brake by simple decel summation in the integration. No `max(...)` selection, no "reinforce" special case.

**Acceptance:**
1. Lever at Idle Low or above (in throttle region): `dynBrakeFraction = 0`, integrator behaves identically to stage 4.
2. Lever at full DYN BRAKE, Light-engine, throttle and brakes off, at 60 mph: `aBrakeD = 1.0 · 1.6 · 1.0 · 1.0 = 1.6 m/s²` wall-clock plus Davis-shape coast resistance. LED shows `DBr`.
3. Same as #2 but at 3 mph prototype velocity (below taper threshold): `speedTaper(3 mph) = min(1, 1.34/2.24) = 0.6`, decel ≈ 0.96 m/s² wall-clock plus coast resistance. Below ~0.5 mph dyn brake contributes essentially nothing.
4. Lever at full DYN BRAKE + Auto brake at EMG simultaneously, Light-engine at 60 mph: `aBrakeD = 1.6`, `aBrakeA = 6.0`, total brake decel = 7.6 m/s² wall-clock plus coast resistance — emergent from decel summation, no special-case code. **Switching to Unit Train scenario** (where `dynBrakeMassFraction = 0.08`) at the same lever positions: `aBrakeD = 1.0 · 1.6 · 0.08 = 0.13 m/s²`, much smaller dyn-brake contribution — prototypically correct (loco-only dyn brake on a heavy train is not the primary deceleration mechanism).

### 3.6 Stage 6 — ESU decoder-brake passthrough (optional)

**Goal:** for users with ESU decoders, mirror brake percent to F4/F5/F6 [research §4.3]. Off by default.

**Modified:** `SemiRealisticSettings.java` (enable the `ESU` option in the `decoderBrakeMode` enum; ESU function/threshold fields), `SemiRealisticSettingsPanel.java` (enable the Decoder-brake Mode dropdown's `ESU` option + ESU-only sub-fields), `SemiRealisticThrottleEngine.java` integration tick (after computing decels, derive `effectiveBrakePct = clamp(indepBrakeFraction · 100 + airLineFraction · airBrakeMaxDecel/brakeMaxDecel · 100, 0, 100)` and dispatch the function changes with the same three-pass logic from `setDecoderBrake` in [research §4.3]).

**Acceptance:**
1. Decoder-brake mode = None: no F4/F5/F6 dispatch from semi-realistic logic.
2. Decoder-brake mode = ESU: applying indep brake to ≥30 % toggles F4 ON; ≥60 % toggles F4 OFF + F5 ON; ≥98 % toggles F5 OFF + F6 ON. Backing off reverses the chain.
3. The threshold/function fields are user-configurable per loco family.

## 4. Acceptance criteria (overall)

1. With semi-realistic mode OFF, behaviour is identical to the existing RailDriver bring-up (no regression).
2. With mode ON, all per-stage acceptance criteria pass on a real DCC loco.
3. **Wall-clock invariants per §1.0**: every coefficient in the engine produces wall-clock m/s² acceleration directly. Brake decels (mechanical, air, dynamic) produce wall-clock 80 mph → 0 stop times within ±10 % of the §1.0/§2.4.1 expected values, and the same time at any layout scale because the engine has no scale dimension. Coast-down decel follows the Davis-shape `aResist(v) = resistStatic + resistLinear·v + resistQuadratic·v²` in wall-clock units, so it varies only with v and the operator-tuned coefficients — never with scale.
4. **No-stuck invariant.** For every scenario in §2.4.1 at default values, `maxAccelAtRest > resistStatic`, so under full throttle on a stationary loco the net wall-clock accel is positive. The engine has no scale-multiplier in the math, so this invariant cannot break at any layout scale.
5. Calibration XML round-trips through Save / Load with the new schema; pre-existing files (version `"1"`) still load cleanly with semi-realistic defaults.
6. The `activeThrottleFrame == null` invariant from the existing RailDriver bring-up still holds — the engine acquires its `DccThrottle` from `activeThrottleFrame` at attach time and never holds the reference past the throttle's `"ancestor"` close.
7. The pre-existing noise hysteresis filter still applies — the engine never sees byte-level jitter as a "lever moved".
8. No new `messages.log` exceptions during a 30-minute ops session involving repeated brake / throttle work.
9. **Empty-roster path:** with mode ON, no roster fields populated, default Light-engine scenario: the loco accelerates and decelerates under the scenario's defaults from §2.4.1. No "physics ramp disabled" warnings (the AutoEngineer fallback path is irrelevant — we always have valid scenario defaults).
10. **Roster-max-speed path:** when `RosterEntry.getPhysicsMaxSpeedKmh()` is populated, `v_fs` is clamped to that value. Other roster physics fields are not consulted.

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

- **Engine architecture (decided 2026-05-02; substantially refactored 2026-05-03 to wall-clock-only model after Davis × physicsTimeScale was found to be mathematically broken): single wall-clock-decel arithmetic loop on a 50 ms tick.** Per §1.0, every coefficient in the engine is a wall-clock m/s² (or m/s² shape-function-of-v). The integration body computes `a = aDrive(v, lever) − aResist(v) − aBrakeM − aBrakeA − aBrakeD` directly — there is no mass, no force, no scale factor anywhere in the math. `getPhysicsMaxSpeedKmh()` clamps both `vTarget` and integrated `v_fs`. The same loop runs accelerating or decelerating with the sign of `a` falling out of which terms are active — no regime detection, no "throttle fighting brake" branch, no separate accel/decel paths. See §2.1 for the engine class skeleton, §1.0 for the framing, and §2.2 for the threading contract. The earlier "two-class force model with physicsTimeScale on prototype-physics terms" architecture was replaced because F_drive (in prototype N, treated as wall-clock) and F_resist (in prototype N × physicsTimeScale) live in incompatible force universes — F_resist always wins at speed, leaving the loco unable to reach design top speed regardless of coefficient values. The wall-clock-only model has no such math problem because all decels live in the same units; a coefficient that works at one scale works at every scale.

- **Davis-equation shape, wall-clock units (decided 2026-05-03):** coast resistance uses the Davis-equation shape `aResist(v) = resistStatic + resistLinear·v + resistQuadratic·v²` because that shape captures the prototypically correct character (aggressive coast from speed, gentle approach to halt) that operators recognize as "real-train feel." The three coefficients are stated in **wall-clock decel units** — `resistStatic` (m/s²), `resistLinear` (1/s), `resistQuadratic` (1/m) — *not* prototype Davis coefficients. Real-world prototype data (e.g. EMD NW2: A ≈ 0.0008 / B ≈ 0.0005 / C ≈ 3 N·s²/m²) is useful as reference for the *ratios* between terms (which dominates at which speed), but the absolute values for the engine are operator-feel-tuned. Defaults vary per scenario in §2.4.1.

- **No physicsTimeScale (decided 2026-05-03; replaces the 2026-05-02 "auto = layoutScale" decision).** The engine has no scale dimension. Earlier drafts proposed multiplying prototype-physics resistance by `physicsTimeScale` (= layoutScale by default) to make coast visible at scale-time wall-clock. This was found mathematically broken: at any model scale, prototype Davis × S overwhelms the operator-tunable F_drive at speed, leaving the loco unable to reach design top speed regardless of coefficient values. Even with verified real-NW2 prototype numbers (A=0.0008, m=112t, S=160), F_resist × 160 at 45 mph exceeds F_drive at 45 mph by 4×. The wall-clock-only refactor eliminates this conflict by defining every coefficient directly in wall-clock decel units — no scale factor anywhere. Per-loco wall-clock feel-tuning per scenario and per Custom-scenario operator overrides cover the use cases the old `physicsTimeScale` was meant to address.

- **Drive coefficients (decided 2026-05-03): operator-tuned wall-clock acceleration profile.** Drive is no longer derived from prototype power and tractive-effort values. The operator (or a scenario preset) specifies `maxAccelAtRest` (m/s² wall-clock at v=0, lever=full) and `vCorner` (velocity above which `a_drive` falls as `vCorner / v`, mimicking the constant-power physics shape). At any speed below `vCorner`, `a_drive = lever · driverPowerPct · maxAccelAtRest`; above, it falls as `1/v` to model real-loco-feel constant-power behaviour. Steam locomotives multiply by `(v / vTop)^0.85` for the steam tractive-effort fall-off. The values are hand-tuned per scenario for the desired operator experience, not derived from prototype P/TE. Real prototype P and TE values would, when divided by mass, produce wall-clock accels far smaller than what operators expect from a model railroad simulation.

- **Brake constants (decided 2026-05-02; per-scenario tuning added 2026-05-03): scenario-tuned, operator-controlled, stated in wall-clock m/s² perceived rate.** `brakeMaxDecel`, `airBrakeMaxDecel`, `dynBrakeMaxDecel` are exposed as Settings-tab fields with per-scenario defaults. Brake decels are simple multiplications: `aBrakeM = brake · brakeMaxDecel`, `aBrakeA = air · airBrakeMaxDecel`, `aBrakeD = dyn · dynBrakeMaxDecel · dynBrakeMassFraction · speedTaper(v)`. The `dynBrakeMassFraction` per-scenario coefficient (1.0 for Light Engine, ~0.08 for Unit Train) captures the prototype reality that dyn brake doesn't propagate through trainline air — diluted in heavier consists. Brake decel values are wall-clock-felt, identical at any layout scale. Defaults from the previous prototype-tradition values (1.0 / 1.5 / 0.4) were retuned to 4.0 / 6.0 / 1.6 m/s² so brakes meaningfully dominate the Davis-shape coast.

- **Physics defaults (decided 2026-05-02; revised 2026-05-03 to wall-clock units): scenario-driven feel presets.** Each scenario in §2.4.1 specifies tuned coefficient values for the entire engine — drive (`maxAccelAtRest`, `vCorner`, `driverPowerPct`, `designTopSpeedMps`, `steam`), coast (`resistStatic`, `resistLinear`, `resistQuadratic`), and brake (`brakeMaxDecel`, `airBrakeMaxDecel`, `dynBrakeMaxDecel`, `dynBrakeMassFraction`). Scenarios are abstract presets; switching to "Through Freight" feels heavier than "Light Engine" because the coefficients say so, not because mass enters the math. The Light-engine scenario is the default. Roster prototype-physics fields (`getPhysicsWeightKg`, `getPhysicsPowerKw`, `getPhysicsTractiveEffortKn`) are NOT consulted — the wall-clock-only model has no use for them. `getPhysicsMaxSpeedKmh()` IS consulted as a wall-clock cap on `v_fs`.

- **EDT discipline (decided 2026-05-02): Option B with worker-thread-math mitigation, standardised on `jmri.util.ThreadingUtil`.** Every Swing-touching call from a non-EDT thread — both the new engine dispatch AND the existing direct-dispatch path — is wrapped in `ThreadingUtil.runOnGUIEventually(...)` (fire-and-forget setters) or `ThreadingUtil.runOnGUIwithReturn(...)` (synchronous getters whose return values feed decision logic). The single existing `SwingUtilities.invokeLater` call site in `RailDriverMenuItem.java` (line 244, in `attachThrottleWindow`) is migrated to `ThreadingUtil.runOnGUIEventually` for file-level uniformity and consistency with the rest of JMRI (`Engineer.java`, `AddressPanel.java`, etc.). The mitigation: the engine worker thread does all the integration math locally, then hands the final DCC step value to the EDT for application. This keeps tick cadence governed by the `ScheduledExecutorService` (precise timing) and only the value-application bounces through the event queue (Swing consistency). Closes the parent §3 / §6 off-EDT latent issue **and** the latent bug where the bring-up-era plan listed `getFunctions()` / `getFunctionMomentary()` / `getFunction()` alongside setters for `invokeLater` wrapping — those are now correctly routed through `runOnGUIwithReturn`. See §2.2 for the threading contract and §3.1 for the wrapping deliverables.
- **UI surface (decided 2026-05-02): one unified `RailDriver Settings...` window with two tabs (Settings + Calibration), plus a new Apply button alongside Save and Cancel.** Replaces the standalone `RailDriver Calibration...` entry that ships with the existing RailDriver bring-up. See §2.4 for layout, dirty-tracking model, and Save/Apply/Cancel behaviour. The frame infrastructure (tabs, Save/Apply/Cancel, dirty tracking, Calibration tab) ships in stage 1 (§3.1) with the Settings tab as a placeholder; the Settings tab content (drive / coast / brake feel coefficients) is filled in by stage 2 (§3.2).
- **Framing (decided 2026-05-02; reinforced 2026-05-03): this is a model-railroad simulation, not a prototype simulator.** The wall-clock-only force model exists precisely because operators want operator-perceptible decel rates that look right at scale-time. The plan is structured around six stages numbered 1–6 within this document; they don't extend the phase-1/2/3 numbering of the predecessor RailDriver bring-up plans.
- **Mode-toggle UI on the throttle window (decided 2026-05-02): Jynstrument-based toolbar button, session-only persistence semantics.** Adds a single icon to the throttle window's toolbar via JMRI's existing Jynstruments framework — no JMRI core modifications needed. The Jynstrument shows and mutates `liveEnabled`; the Settings tab's checkbox shows and mutates `persistedEnabled` (with in-window dirty tracking). They are deliberately decoupled: the Settings tab never reflects a session-only Jynstrument toggle, and the Jynstrument never reflects an unsaved Settings-tab edit. Right-click → `Settings...`. Auto-installed by `RailDriverMenuItem.attachThrottleWindow()`, gated solely by `hasJynstrumentInstalled` (no in-memory or on-disk suppression state). Removal-via-Quit sticks for the lifetime of that throttle window; re-clicking `Debug → RailDriver Throttle (built in)` re-adds the toggle (treated as a deliberate fresh-attach action). Cross-session persistence of toggle absence relies on saved-layout-XML restore happening before any auto-install fires. There is no `<jynstrumentAutoInstallSuppressed>` flag in the calibration XML. See §2.3 for the persistence model, §2.6 for the full Jynstrument design, and §3.2 acceptance bullets 13–16.

- **Persistence split between Settings window and Jynstrument (decided 2026-05-02).** Two distinct values: `persistedEnabled` (last loaded or saved XML value, displayed and edited by the Settings tab) and `liveEnabled` (current engine/Jynstrument state). The Settings tab Save/Apply mutates `persistedEnabled`, writes XML, then sets `liveEnabled := persistedEnabled`. The Jynstrument click mutates `liveEnabled` only. The Settings tab checkbox is bound to `persistedEnabled` (plus in-window pending-edit state) and never reflects session-only Jynstrument toggles — so the operator always sees what's on disk in the Settings tab, and what the engine is currently doing on the Jynstrument icon. To make a session-level Jynstrument change persist, the operator opens the Settings window, sets the checkbox to match, and Saves. Two PCS event names (`"liveEnabledChanged"` for engine + Jynstrument; `"persistedEnabledChanged"` for the Settings tab) keep the observer sets disjoint. See §2.3.

- **Attach-in-progress visibility on the Jynstrument (decided 2026-05-02).** `requestAttachToThrottle()` is asynchronous (the bind work is posted to the EDT via `ThreadingUtil.runOnGUIEventually`), so the Jynstrument cannot rely on the bind being complete by the time its click handler returns. A new `"attachInProgress"` PCS event brackets the async window, and the Jynstrument tracks an internal `pendingSessionToggle` flag: State-2 click sets the flag and triggers the attach, the `"activeThrottleFrame"` event delivers (transitioning to State 4), and the listener applies the deferred `setSemiRealisticEnabledSessionOnly` toggle. Extra clicks during the window are absorbed (State 2.5). See §2.6.

- **`hidDeviceAttached` re-enabled (decided 2026-05-02).** The currently-disabled body at `RailDriverMenuItem.java:539–546` (originally gated on a now-removed `invokeOnMenuOnly` flag) is re-enabled in stage 2 with a minimal change: fire `"railDriverConnected"` PCS event on VID/PID match **without** auto-calling `setupRailDriver()`. The Debug-menu workflow keeps owning the polling lifecycle, so cold-plug behaviour for users without the Jynstrument is unchanged; only the Jynstrument's icon state reacts to hot-plug. See §3.2 step 4.
- **Bail-off semantics (decided 2026-05-02): latched while the byte is above the calibrated threshold.** Stage 4 sets `engine.bailoffPressed = true` whenever the byte 4 value exceeds `bailoffThreshold()` and `false` otherwise. The engine zeros `aBrakeA` while `bailoffPressed` is true — direct level-triggered semantics that match how a real bail-off handle behaves on the prototype. Mechanical and dyn brake terms are unaffected.
- **Mode-switch handover at speed (decided 2026-05-02).** OFF → ON adopts the loco's current `throttle.getSpeedSetting()` as the engine's starting `v_fs` (converted to full-scale velocity via the roster speed profile when available, linear fallback otherwise), then integrates smoothly toward the lever-derived target. ON → OFF pauses the integration task and the next byte-change writes the lever-derived value directly via `setSpeedSetting()` — the loco may snap if the lever is far from the engine's last commanded speed. Operators are expected to either align the lever before flipping OFF or to accept the snap. See §2.3 for the implementation contract.
- **Reverser-interlock speed source (decided 2026-05-02): `engine.v_fs`, gated by `settings.liveEnabled`.** The polling-thread Axis 0 dispatch reads `engine.v_fs` (volatile, no EDT crossing) and suppresses `setIsForward()` when `settings.liveEnabled && v_fs > epsilon`. The `settings.liveEnabled` guard ensures the field is only consulted while the engine maintains it (per the §2.3 handover contract). See §3.3 for stage details.
- **Pending direction change at stop (decided 2026-05-02): not retried on `v_fs == 0`.** When the operator moves the reverser during deceleration the dispatch suppresses the direction change at byte-change time and does NOT retroactively apply the lever's held position when the loco eventually stops. The operator must nudge the reverser to trigger another byte change. Matches prototype operator behaviour (engineer holds the handle in position then moves it the rest of the way at stop). See §3.3 acceptance bullet 2.
- **Settings-field validation ranges (decided 2026-05-02; revised 2026-05-03 for wall-clock-only model):** maxAccelAtRest 0.1–10.0 m/s²; vCorner 1.0–100.0 m/s; driver power 0–100 %; designTopSpeed 1.0–100.0 m/s; resistStatic 0.0–10.0 m/s²; resistLinear 0.0–1.0 1/s; resistQuadratic 0.0–0.1 1/m; mechanical brake max decel 0.1–20.0 m/s²; air brake max decel 0.1–20.0 m/s²; dynamic brake max decel 0.0–10.0 m/s²; dynamic brake mass fraction 0.0–1.0; dynamic brake taper threshold 0–20 mph prototype; ESU thresholds 1–100 monotonically increasing; ESU function numbers 0–28. Validation runs on Save and Apply per §2.4's flow; failures auto-select the offending tab and leave dirty set.
- **Jynstrument click behaviour by state (decided 2026-05-02):** five states with distinct behaviours per §2.6's table —
  - **State 1 (no device detected):** greyed, tooltip `"RailDriver not detected"`, click is no-op.
  - **State 2 (device present, no throttle bound):** auto-bootstrap with deferred toggle on click — calls `requestAttachToThrottle` against the Jynstrument's own `ThrottleFrame`, sets `pendingSessionToggle = true`, transitions to State 2.5. The toggle is applied when the resulting `"activeThrottleFrame"` PCS event delivers and confirms this Jynstrument's frame is bound. Implicitly checks "not bound elsewhere" via the `activeThrottleFrame == null` precondition.
  - **State 2.5 (attach in progress):** transient "binding…" icon, tooltip `"RailDriver attaching to this throttle…"`, click is no-op (extra clicks during the async attach window are absorbed).
  - **State 3 (device present, bound to a different throttle):** greyed, tooltip `"RailDriver already bound to another throttle window"`, click is no-op.
  - **State 4 (fully operational, this throttle is bound):** normal session-only toggle via `setSemiRealisticEnabledSessionOnly`.
  Driven by the new `"railDriverConnected"`, `"activeThrottleFrame"`, and `"attachInProgress"` PCS events on `RailDriverMenuItem`.

## 7. Known limitations accepted in this feature

- **Single-throttle only.** This feature doesn't introduce multi-loco support.
- **Per-roster scenario default deferred** (see [research §9.2.5]).
- **No prototype-physics derivation.** Engine coefficients are operator-feel-tuned in wall-clock units, not derived from prototype power / TE / mass / Davis values. Real prototype data informs the *shape* of the curves (which terms dominate at which speeds) but not absolute values. Operators wanting prototype-realistic 30-minute coast-downs cannot produce them with this engine — the wall-clock-only model has no scale dimension to compress prototype time into operator time, and pushing prototype-derived coefficients into the wall-clock fields produces unwatchably-slow behaviour. (The earlier `physicsTimeScale` design that promised this was mathematically broken and abandoned; see §6.)
- **Roster prototype-physics fields not consulted.** `RosterEntry.getPhysicsWeightKg()` / `getPhysicsPowerKw()` / `getPhysicsTractiveEffortKn()` are ignored — the wall-clock-only model has no use for prototype mass / power / TE values. The active scenario's coefficients drive everything. `getPhysicsMaxSpeedKmh()` IS consulted as a wall-clock cap on `v_fs`. Per-loco wall-clock feel-tuning overrides via roster attributes are deferred future work.
- **Reservoir-and-line refill model from EngineDriver §4.2 is replaced with the direct lever-driven model.** Operators who want "ran out of air, must release brake to recharge" gameplay will have to wait for a future feature that simulates a virtual reservoir behind the Auto Brake — this is out of scope here because the RailDriver's physical Auto Brake gives us the real signal.
- **Linear `v ↔ DCC step` fallback when no speed profile.** Without a calibrated `RosterSpeedProfile` for the loco, the engine maps `v_fs` to DCC step linearly. The integration is still correct (decels are real); only the absolute speed-step calibration is approximate. Users with calibrated profiles automatically get exact mapping via `getSpeed(...)`.
- **Same wall-clock behaviour at every layout scale.** A coefficient of `maxAccelAtRest = 2.5 m/s²` produces 2.5 m/s² wall-clock at HO, N, Z, or any other scale. There is no scale dimension in the math. Operators wanting different feel at different scales tune the coefficients explicitly.
- **No tests.** Parent §4.5 / deferred.
- **No help-page documentation.** Parent §4.6 / deferred.
- **All latent issues from parent §3 / §6 except the off-EDT mutation are still untouched.** The off-EDT issue is fixed in stage 1 (see §3.1's `RailDriverMenuItem.java` modifications).

## 8. Future work

- Tests (parent §4.5) — byte-parser, settings persistence round-trip, integration determinism with a stub DccThrottle (mirror existing JMRI test patterns for `AbstractThrottle` rather than introducing a new throttle proxy).
- **Gradient gravity, curve resistance, journal-bearing breakaway** — additional decel/accel terms in the wall-clock model, slotted into the same per-tick arithmetic (`a = aDrive − aResist − aBrakeM − aBrakeA − aBrakeD − aGradient + aCurve …`). Each is operator-tuned in wall-clock units.
- **Per-roster wall-clock feel-tuning overrides** via `RosterEntry.getAttribute("raildriver.semiRealistic.maxAccelAtRest")` etc. Lets operators tune individual locos differently from their scenario default. Deferred until needed.
- Per-roster scenario default via `RosterEntry.getAttribute("raildriver.scenario")`.
- Help / documentation updates (parent §4.6).
- i18n pass over the RailDriver UI: extract hardcoded English labels in `SemiRealisticSettingsPanel.java` and `CalibrationTabPanel.java` to `Bundle.properties` keys, and populate the existing locale Bundle files (`Bundle_ca`, `Bundle_cs`, `Bundle_de`, `Bundle_fr`, `Bundle_nl`) with translated strings. Currently both panels carry English literals; only the Debug-menu entries (`RdBuiltIn`, `RdSettings`) are externalised.
- Optional: virtual reservoir / line model for users who want EngineDriver-style "run out of air" behaviour layered on top of the physical Auto Brake handle.
- Optional: a Settings-tab "Restore toolbar toggle" button. Currently, if the operator removes the Jynstrument and saves the throttle layout, restoring the toggle requires drag-installing the `.jyn` folder again. A button on the Settings tab could re-trigger the auto-install path against the bound throttle frame.
- Remaining latent-issue fixes from parent §3 / §6.

## 9. Cross-references

- Research source: [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md).
- Parent: [`plan.md`](plan.md).
- RailDriver bring-up phase 1: [`plan-impl-phase1.md`](plan-impl-phase1.md) — connect & verify MVP.
- RailDriver bring-up phase 2: [`plan-impl-phase2.md`](plan-impl-phase2.md) — wire existing mappings, throttle-direction fix, F0/F28 redesign, slot 0..27 → F1..F28.
- RailDriver bring-up phase 3: [`plan-impl-phase3.md`](plan-impl-phase3.md) — calibration framework, visual bar UI, idle-range model, polling lifecycle decouple.
- Canonical bit-for-bit map: [`control-inventory.md`](control-inventory.md).
