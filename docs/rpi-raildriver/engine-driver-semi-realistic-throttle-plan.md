# RailDriver Semi-Realistic Throttle — EngineDriver-aligned Plan

> **Reference implementation:** [`JMRI/EngineDriver`](https://github.com/JMRI/EngineDriver),
> file `EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java`.
>
> **Research compendium:** [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md)
> documents the EngineDriver algorithm in full (line-by-line). All
> `[research §X]` references in this plan resolve there.
>
> **Supersedes:** [`semi-realistic-throttle-plan.md`](semi-realistic-throttle-plan.md)
> in its entirety. The previous plan's velocity / Davis-shape physics model,
> consist mass simulation, and full Westinghouse state machine are abandoned.
> This plan replaces them with a port of EngineDriver's much simpler
> target-speed + Δt-multiplier scheduler.

---

## 1. Goal

Add a "semi-realistic" mode to the JMRI RailDriver integration that gives the
operator the same feel as EngineDriver's `throttle_semi_realistic` activity:

- Moving the throttle lever sets a **target** decoder speed setting.
- The live `setSpeedSetting` value walks toward the target one fixed-size
  step every Δt ms.
- Δt is scaled by a multiplicative `targetAcceleration` term that is
  computed from the brake levers, the air-line state, the load setting, and
  the direction lever.
- Brakes can clip the target downward; load only stretches Δt, never the
  target.
- The mechanism deliberately matches EngineDriver section-for-section so
  operators familiar with that app on Android get the same behaviour on
  their RailDriver console.

**Non-goal:** prototype-accurate physics. There is no mass, no force, no
m/s² figures, no Davis equation, no per-roster speed profile inside the
engine. Δt and the target are integers; everything else is a small-arity
multiplier. The decoder + JMRI roster speed profile remain the sole
authority for actual model-train velocity.

---

## 2. What changes vs. the current code

The repository already contains an in-progress velocity-based physics
engine (`SemiRealisticThrottleEngine`, `LoadScenario`,
`SemiRealisticSettings`, `SemiRealisticSettingsPanel`). All four are
**rewritten** under this plan:

| File                             | Today (physics-based)                                             | Under this plan (EngineDriver-aligned)                                                                            |
|----------------------------------|-------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `SemiRealisticThrottleEngine`    | 50 ms tick, Davis A/B/C resistance, m/s² integrator, v_fs → DCC quantisation | Step-rate scheduler: one ±1 step per Δt ms toward `targetSpeed`; Δt = `baseDelay × targetAcceleration`.            |
| `LoadScenario`                   | 12 physics coefficients (top speed, vCorner, drag terms, brake decel, etc.) | Two fields: `displayName` + `loadMultiplier`. Multiplier feeds the EngineDriver `getLoadPcnt` slot directly.       |
| `SemiRealisticSettings`          | ~20 m/s² / m/s / 1/s coefficients                                  | EngineDriver-style integers: speed-step, base accel/decel delay, brake steps, max-brake %, load steps, max-load %. |
| `SemiRealisticSettingsPanel`     | Velocity-coefficient editors                                       | Number / step pickers matching the new settings model.                                                             |
| `RailDriverCalibration`          | `<semiRealistic>` subtree with physics block                       | `<semiRealistic>` subtree with EngineDriver-style block; calibration block (idle, full-throttle, brake range) unchanged. |
| `RailDriverMenuItem`             | Velocity inputs (`setIndepBrakeFraction`, etc.)                    | EngineDriver-style inputs (`setBrakeSliderStep`, `setAirLineValue`, `setDynBrakeStep`, etc.).                      |
| `SemiRealisticThrottleEngineTest` | Davis-shape regression cases                                      | EngineDriver-shape cases (delay multiplier matches `getLoadPcnt`, brake clip matches `getBrakeDecimalPcnt`, etc.). |

All RailDriver-hardware code outside the engine — calibration tabs, button
mapping, axis decoding, lifecycle wiring (`AddressListener` /
`ThrottleWindow` plumbing, EDT discipline) — is left alone. Only the
**physics math and its inputs/outputs** change.

---

## 3. EngineDriver model recap (one-screen summary)

Full derivation in
[`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md);
included here so this plan stands on its own.

```
slider position ──► sliderSpeed (0..maxThrottle)
                          │
                          ▼
                    setTargetSpeed()   <── brake slider, air line, load, direction
                          │
                          ▼
                    targetSpeed (int)
                    targetAcceleration (double, signed)
                          │
                          ▼
        every Δt ms:    speed += (sign of targetAcceleration) × stepSize
                        Δt = baseDelay × |targetAcceleration|
                        baseDelay = 300 ms (accel) | 800 ms (decel)
                          │
                          ▼
                     setSpeedSetting(speed/maxThrottle)
```

`setTargetSpeed` is the heart. It produces both:

- **`targetSpeed`** — the decoder speed step the loco is walking toward.
  Brakes can clip this downward; throttle slider sets the upper bound.
- **`targetAcceleration`** — a signed multiplier on Δt. Sign chooses
  ramp direction; magnitude scales the inter-step delay.

EngineDriver's defaults:

| Pref                                        | Default | Role                                                       |
|---------------------------------------------|---------|------------------------------------------------------------|
| `prefSemiRealisticThrottleSpeedStep`        | 2       | Speed-step units per ramp tick (in 0..126 WiThrottle space).|
| `prefSemiRealisticThrottleAccelerationRepeat`| 300 ms  | Base Δt while ramping up.                                  |
| `prefSemiRealisticThrottleDecelerationRepeat`| 800 ms  | Base Δt while ramping down.                                |
| `prefSemiRealisticThrottleNumberOfBrakeSteps`| 7       | Brake handle notches.                                      |
| `prefSemiRealisticMaximumBrakePcnt`         | 70      | Cap on brake force; 100 = "instant zero".                  |
| `prefSemiRealisticThrottleNumberOfLoadSteps`| 5       | Load slider notches.                                       |
| `prefSemiRealisticThrottleMaxLoadPcnt`      | 1000    | Max Δt multiplier from full load (10 ×).                   |
| `prefSemiRealisticThrottleAirRefreshRate`   | 2000 ms | Reservoir / line tick interval.                            |

We adopt the same names where they survive verbatim, and rename only
where the underlying meaning shifts (e.g. RailDriver's continuous
levers become real-valued positions rather than discrete step counts).

---

## 4. RailDriver hardware mapping

EngineDriver runs on a touchscreen and exposes three on-screen sliders
(throttle, brake, load) plus an Air on/off button. The RailDriver
console gives us a richer set of physical levers, so the mapping is
not 1:1. The choices below mirror
[`semi-realistic-throttle-info.md` §9](semi-realistic-throttle-info.md#9)
and the existing control inventory.

| EngineDriver concept              | RailDriver source                    | Notes                                                                                                  |
|-----------------------------------|--------------------------------------|--------------------------------------------------------------------------------------------------------|
| Throttle slider                   | **Throttle / Dyn Brake lever (#9, byte 1)**, **above** Idle High | Above-idle travel is normalised to `0..maxThrottle` against the calibrated Idle / Full Throttle byte values. |
| (No native equivalent)            | **Throttle / Dyn Brake lever (#9, byte 1)**, **below** Idle Low  | Below-idle travel becomes a virtual `dynBrakeStep` (see §6.3). EngineDriver has no dyn-brake input; we add it as another brake source.|
| Mechanical brake slider           | **Independent Brake lever (#11, byte 3)** | Calibrated Full Release → Full Application; quantised to `numberOfBrakeSteps`.                         |
| Air-line value (decreases as brake handle moves deeper) | **Auto Brake lever (#10, byte 2)**         | Replaces EngineDriver's "derived from brake slider" model. Auto-brake position **directly sets** `airLineValue` (0..100). |
| Air-line bail-off (transient)     | **Bail-off (#11 / byte 4 transient)**     | When asserted, the indep-brake portion of `effectiveBrake` is forced to "free" (1.0) until released; `airLineValue` is unchanged.|
| Load slider                       | **Software preset picker** (no physical control) | UI dropdown / calibration setting. The multiplier feeds the same `targetAcceleration ×= loadMultiplier` slot. **Computation is the open section — see §7.** |
| Direction lever                   | **Reverser (#8, byte 0)**            | Three detents: Forward / Neutral / Reverse. Reverser-at-zero-speed-only interlock enforced JMRI-side (matches EngineDriver). |
| Stop button                       | **E-Stop (#2, byte 11)** for hard E-Stop; one front-edge button for "soft stop" if desired | E-Stop bypasses the ramp (`setSpeedSetting(-1)`); the soft button can use EngineDriver's `THROTTLE_STOP_BRAKE_FULL` mode.|
| Air on/off button                 | **Not exposed.**                     | EngineDriver has the toggle because its single brake slider does double duty. We have separate Independent and Auto Brake levers, so the operator opts out of air dynamics by leaving the Auto Brake at Released. |
| ESU decoder brake mode            | Pure DCC-side; no physical control   | Brake-percent computed from Independent Brake position drives F4/F5/F6 mirroring (same threshold logic as EngineDriver). |

The polling-thread `dispatchValueEvent` switch in `RailDriverMenuItem`
is the wiring point for all axis inputs; one new method per input on
the engine class supersedes today's `setIndepBrakeFraction` /
`setDynBrakeFraction` etc. (see §5.4).

---

## 5. Engine specification

### 5.1 Class outline

```java
public final class SemiRealisticThrottleEngine {

    // ─── State (per active throttle; only one at a time on RailDriver) ─────
    private DccThrottle  throttle;
    private RosterEntry  rosterEntry;            // for ESU decoder brake passthrough
    private volatile int speedStep;              // current decoder step, 0..126
    private volatile int targetSpeed;            // EngineDriver targetSpeed
    private volatile double targetAcceleration;  // signed Δt multiplier

    // ─── Lever inputs (volatile; written by polling thread) ────────────────
    private volatile int     throttleSliderStep;     // 0..maxThrottle
    private volatile int     dynBrakeStep;           // 0..numberOfBrakeSteps
    private volatile int     indepBrakeStep;         // 0..numberOfBrakeSteps
    private volatile int     airLineValue;           // 0..100, directly from Auto Brake lever
    private volatile boolean bailoffAsserted;
    private volatile Direction direction;            // FORWARD / NEUTRAL / REVERSE

    // ─── Soft inputs ───────────────────────────────────────────────────────
    private volatile LoadScenario loadScenario;      // see §7

    // ─── Settings snapshot (replaced atomically on save) ───────────────────
    private volatile SemiRealisticSettings settings;

    // ─── Scheduler ────────────────────────────────────────────────────────
    private final ScheduledExecutorService exec;
    private ScheduledFuture<?> rampTask;
    private ScheduledFuture<?> reservoirTask;
    private ScheduledFuture<?> airLineTask;

    // ─── Lifecycle ────────────────────────────────────────────────────────
    public void attachThrottle(DccThrottle t, RosterEntry re);
    public void detachThrottle();
    public void setLiveEnabled(boolean enabled);
    public void dispose();
    public void updateSettings(SemiRealisticSettings s);

    // ─── Lever inputs (called from the polling thread) ────────────────────
    public void setThrottleSliderStep(int step);
    public void setDynBrakeStep(int step);
    public void setIndepBrakeStep(int step);
    public void setAirLineValue(int value);
    public void setBailoffAsserted(boolean asserted);
    public void setDirection(Direction d);

    // ─── EngineDriver math (private; invoked from input setters) ──────────
    private void setTargetSpeed(boolean fromSlider);
    private static double getBrakeDecimalPcnt(double step, double steps, double maxBrake);
    private static double getLoadMultiplier(LoadScenario sc, SemiRealisticSettings s);
}
```

### 5.2 The ramp scheduler

Equivalent to EngineDriver's `SemiRealisticTargetSpeedRptUpdater`
([research §2](semi-realistic-throttle-info.md#2-the-ramp-scheduler)).
A single one-shot worker runs on `exec` and reschedules itself:

```java
private void rampTick() {
    if (speedStep == targetSpeed) {
        rampTask = null;                                // arrived
        return;
    }
    int delta;
    if (targetAcceleration > 0) {                       // ramping up
        if (speedStep > targetSpeed) {                  // configuration changed under us
            emit(targetSpeed);
            rampTask = null;
            return;
        }
        delta = +settings.speedStep;
    } else {                                            // ramping down
        if (speedStep < targetSpeed) {
            emit(targetSpeed);
            rampTask = null;
            return;
        }
        delta = -settings.speedStep;
    }
    int next = clampStep(speedStep + delta);
    if (delta > 0 && next > targetSpeed) next = targetSpeed;
    if (delta < 0 && next < targetSpeed) next = targetSpeed;
    emit(next);

    long delayMs = Math.round(baseDelayFor(targetAcceleration)
                              * Math.abs(targetAcceleration));
    rampTask = exec.schedule(this::rampTick, delayMs, TimeUnit.MILLISECONDS);
}

private long baseDelayFor(double targetAccel) {
    return targetAccel > 0
            ? settings.accelRepeatMs       // default 300 ms
            : settings.decelRepeatMs;      // default 800 ms
}

private void emit(int newStep) {
    speedStep = newStep;
    final float fraction = newStep / (float) settings.maxThrottleStep;   // 126 default
    ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(fraction));
}
```

### 5.3 `setTargetSpeed` (the heart)

Direct port of EngineDriver
[research §3](semi-realistic-throttle-info.md#3-settargetspeed--the-heart-of-the-semi-realistic-logic)
with the RailDriver-shaped input substitutions from §4. `effectiveBrake`
is the same `(1 − retarding force)` value EngineDriver uses, so smaller
== more brake.

```java
private void setTargetSpeed(boolean fromSlider) {
    int sliderSpeed = sliderSpeedFromStep(throttleSliderStep);
    int target = fromSlider ? sliderSpeed : targetSpeed;
    int speed  = speedStep;
    double accel = 1.0;

    // ── Direction NEUTRAL: coast to zero at base decel delay ──
    if (direction == Direction.NEUTRAL) {
        target      = 0;
        sliderSpeed = 0;
        accel       = -1.0;
    }

    // ── Effective brake = max retardation among all sources ──
    int    brakeSteps  = settings.numberOfBrakeSteps;
    double maxBrake    = settings.maxBrakePcnt / 100.0;
    double indepPcnt   = getBrakeDecimalPcnt(indepBrakeStep, brakeSteps, maxBrake);
    double dynPcnt     = getBrakeDecimalPcnt(
                              effectiveDynBrakeStep(dynBrakeStep, speed),
                              brakeSteps, maxBrake);
    double airLineFrac = 1.0 - (airLineValue / 100.0);
    double airLineStep = Math.round(airLineFrac * brakeSteps);
    double airPcnt     = getBrakeDecimalPcnt(airLineStep, brakeSteps, maxBrake);

    double effectiveBrake = indepPcnt;
    if (airPcnt < effectiveBrake) effectiveBrake = airPcnt;
    if (dynPcnt < effectiveBrake) effectiveBrake = dynPcnt;

    // Bail-off temporarily releases only the loco-side (indep + dyn) brake.
    if (bailoffAsserted) {
        // air still applies; recompute using only air for the loco-side decision.
        effectiveBrake = airPcnt;
    }

    // ── Brake-aware target & accel ──
    if (effectiveBrake == 1.0) {                              // free-running
        target = sliderSpeed;
    } else if (effectiveBrake < 1.0) {
        if (target == 0) {                                    // brake only
            accel = -1.0 * effectiveBrake;
        } else {                                              // throttle vs brake
            target = (int) Math.round(sliderSpeed * effectiveBrake);
            if (target <= speed) {
                accel = -1.0 * (1.0 - effectiveBrake * settings.maxBrakeUnderPower);
            } else {
                accel = +1.0 + (1.0 - effectiveBrake * maxBrake);
            }
        }
    }

    // ── Load multiplier (open: see §7) ──
    accel = accel * getLoadMultiplier(loadScenario, settings);

    // ── Clamp + sign reconcile ──
    if (target < 0) target = 0;
    if (target > settings.maxThrottleStep) target = settings.maxThrottleStep;
    if ((target < speed && accel > 0) || (target > speed && accel < 0)) {
        accel = -accel;
    }

    targetSpeed        = target;
    targetAcceleration = accel;

    // ── Cancel & repost the ramp ──
    if (rampTask != null) rampTask.cancel(false);
    if (target != speed) {
        long delayMs = Math.round(baseDelayFor(accel) * Math.abs(accel));
        rampTask = exec.schedule(this::rampTick, delayMs, TimeUnit.MILLISECONDS);
    }
}

static double getBrakeDecimalPcnt(double step, double steps, double maxBrake) {
    if (step <= 0) return 1.0;
    double max = Math.sqrt(steps) * steps * maxBrake;
    return 1.0 - (Math.sqrt(step) * step * maxBrake / max * maxBrake);
}
```

### 5.4 Polling-thread → engine wiring

`RailDriverMenuItem.dispatchValueEvent` already decodes each axis. The
patches per axis are small, mechanical:

| Axis | Today                                                       | Under this plan                                                                           |
|------|-------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| 0 (Reverser)        | `engine.setDirection(...)`                          | unchanged.                                                                                |
| 1 (Throttle / Dyn)  | `engine.setThrottleFraction(...)`, dyn always 0     | Above-idle: `engine.setThrottleSliderStep(stepFromFraction(f, settings.maxThrottleStep))`. Below-idle: `engine.setDynBrakeStep(stepFromFraction(absBelow, settings.numberOfBrakeSteps))`. |
| 2 (Auto Brake)      | (logged, no engine call)                            | `engine.setAirLineValue(percentFromAutoBrake(byteValue, cal))`.                           |
| 3 (Indep Brake)     | `engine.setIndepBrakeFraction(f)`                   | `engine.setIndepBrakeStep(stepFromFraction(f, settings.numberOfBrakeSteps))`.             |
| 4 (Bail-off)        | (logged, no engine call)                            | `engine.setBailoffAsserted(byteValue >= bailoffThreshold)`.                               |

Each setter runs on the polling thread and just stores the new value
into a `volatile` field, then calls `setTargetSpeed(true)` to recompute
target + Δt. The math runs on the polling thread — same pattern as
EngineDriver, which runs the math on the UI thread directly. Only
`setSpeedSetting` is marshalled to the EDT (`runOnGUIEventually`).

### 5.5 Threading

EngineDriver uses six per-loco `android.os.Handler`s (one per
`maxThrottlesCurrentScreen`). On Java SE there's only ever one active
RailDriver throttle, so we collapse the per-loco arrays to single
fields and use a single `ScheduledExecutorService` with a single
worker thread for all three repeaters (ramp, reservoir, air-line).

EDT discipline: the engine itself **never blocks on the EDT**. The
only EDT crossing is the `runOnGUIEventually(() -> throttle.setSpeedSetting(...))`
emission inside `rampTick`, mirroring the existing
`RailDriverMenuItem.dispatchValueEvent` convention
([repo memory: EDT discipline](#)).

### 5.6 Logging

Follows `.github/instructions/jmri-logging.instructions.md`. Each
class declares the standard SLF4J logger:

```java
private static final org.slf4j.Logger log =
    org.slf4j.LoggerFactory.getLogger(SemiRealisticThrottleEngine.class);
```

All messages use the parameterised form (`log.debug("Found {}", x)`)
so unused arguments are not concatenated when the level is filtered
out. Levels by event class:

| Event class                                                   | Level                                  |
|---------------------------------------------------------------|----------------------------------------|
| Engine attach / detach, mode toggle on/off                    | `INFO` (one-shot lifecycle)            |
| Settings save / Apply, schema migration outcome (§9.13)        | `INFO` (one-shot lifecycle)            |
| Connection up / down, throttle acquired / released            | `INFO` (one-shot lifecycle)            |
| Repeated/recurring user-input warnings (e.g. lever validation)| `Log4JUtil.warnOnce` / `infoOnce`      |
| Schema-load v2 discard, malformed v3 child (§9.11.3 / §9.13)   | `WARN` *and* an `ErrorHandler` report  |
| `setTargetSpeed` per-axis recompute (one per lever change)    | `DEBUG` (guarded if argument is costly)|
| Ramp scheduler emit (every Δt — 300 / 800 ms steady state)        | `TRACE` only                           |
| Reservoir / air-line repeater tick (every 2000 ms steady state) | `TRACE` only                           |
| Per-byte HID payload trace                                    | `TRACE` only                           |

Guidelines that follow from those levels:

- Do **not** emit `INFO` from inside the ramp scheduler, the air
  repeaters, or any per-axis input setter. Steady-state INFO
  traffic is forbidden by the JMRI logging guidance ("ideally
  none after startup completes").
- Per-tick scheduler output stays at `TRACE`; if a value worth
  surfacing is computed by a method call, guard the `log.trace(...)`
  with `if (log.isTraceEnabled()) { ... }` to avoid the method
  call when tracing is off.
- Recurring `WARN`/`INFO` messages whose first occurrence is enough
  for the operator (e.g. “Bail-off pressed but no calibration
  captured”, “Auto Brake travel out of calibrated range”) use
  `Log4JUtil.warnOnce(log, ...)` / `Log4JUtil.infoOnce(log, ...)`.
- Exceptions thrown out of any persistence / migration / scheduler
  path log via `log.error("<context>: " + ex.getLocalizedMessage(), ex)`
  so the stack trace is preserved.

---

## 6. Brake systems

### 6.1 Independent brake (Independent lever, byte 3)

EngineDriver's "brake slider", byte-for-byte. Quantised from the
calibrated Full Release / Full Application byte range to
`numberOfBrakeSteps` notches. Position 0 = released. Each step
re-invokes `setTargetSpeed` so Δt updates immediately.

### 6.2 Air system (Auto brake, byte 2)

Two key departures from EngineDriver:

1. **`airLineValue` is read directly from the Auto Brake lever**, not
   derived from the mechanical brake handle. The Auto Brake has a
   continuous travel from Released (mapped to `airLineValue = 100`) to
   EMG (mapped to `airLineValue = 0`); the operator can sit at any
   intermediate pressure. EngineDriver had to fake this because its UI
   has only one brake slider; we have a real one.
2. **The reservoir / line refill behaviour is preserved verbatim** from
   EngineDriver's `SemiRealisticAirRptUpdater` and
   `SemiRealisticAirLineRptUpdater`
   ([research §4.2](semi-realistic-throttle-info.md#42-air-system-westinghouse-style-simulation)).
   The reservoir refills at +5 % every `airRefreshRateMs` (default
   2000 ms); when the lever is at Released, the line refills from the
   reservoir in 20 % chunks. Setting `airRefreshRateMs = 0` (or a "Air
   simulation off" toggle) freezes the line at whatever the lever
   commands.

Bail-off is RailDriver-only: while asserted, `setTargetSpeed` skips the
indep + dyn contributions to `effectiveBrake`, so the operator can
release the loco brake against a held trainline application.

### 6.3 Dynamic brake (Throttle/Dyn lever, byte 1, below Idle)

Below-idle travel of the throttle lever produces a virtual
`dynBrakeStep` in `0..numberOfBrakeSteps` that participates in
`effectiveBrake = min(indepPcnt, airPcnt, dynPcnt)`. Two
prototype-like adjustments in `effectiveDynBrakeStep`:

- **Low-speed taper:** real dyn brake is ineffective near zero speed
  (no current to dissipate). Below `dynBrakeMinSpeedStep` (default 8,
  i.e. ~6 mph in 0..126 space), the requested step is scaled down
  linearly to zero at `speed = 0`.
- **No air-line interaction:** dyn brake is electrical-only and does
  not pull from the air system. The reservoir / line repeaters ignore
  the dyn-brake state entirely.

This is the only place we add behaviour beyond EngineDriver's stock
algorithm. It's a small generalisation of "another input that pushes
`effectiveBrake` down" — the math itself is unchanged.

### 6.4 ESU decoder brake passthrough

Direct port of EngineDriver's `setDecoderBrake`
([research §4.3](semi-realistic-throttle-info.md#43-esu-decoder-brake-functions--passthrough-to-the-loco)).
Brake-percent is computed from the **Independent Brake** position
(not air-line, not dyn, not the combined effective brake). Three
operator-tunable thresholds (default 30 / 60 / 98 %) drive
function-on / function-off transitions on F4 / F5 / F6 (defaults). The
function numbers and thresholds are settings.

This is mode-gated by `decoderBrakeMode` (`NONE` | `ESU`); default
`NONE`. When `NONE`, the entire passthrough is short-circuited.

---

## 7. Load multiplier — open section (iterate after first review)

> **The user has explicitly flagged this as the section to iterate on
> after a first draft.** *How* the load multiplier is **used** is fixed
> (it's the same `targetAcceleration ×= loadMultiplier` slot
> EngineDriver uses, applied unconditionally at the end of
> `setTargetSpeed`). Only *how the multiplier is computed* is open. The
> proposal below is **a baseline starting point**, deliberately the
> simplest of the alternatives. We expect to revise this section based
> on the user's feedback before any code is written.

### 7.1 Proposed baseline: named-scenario picker

A small `LoadScenario` enum populates a dropdown on the Settings tab.
Each entry carries a single `loadMultiplier` field; the picker's
current value is persisted in `<semiRealistic>` XML and read fresh on
every `setTargetSpeed` call.

```java
public enum LoadScenario {
    LIGHT_ENGINE   ("Light engine",     1.0),
    SWITCHER       ("Switcher",         1.5),
    LOCAL_FREIGHT  ("Local freight",    2.5),
    THROUGH_FREIGHT("Through freight",  5.0),
    UNIT_TRAIN     ("Unit train",      10.0),
    CUSTOM         ("Custom",           /* read from settings.customLoadMultiplier */);

    public double loadMultiplier(SemiRealisticSettings s) { ... }
}
```

`Light engine` (1.0) reproduces EngineDriver's "load slider at zero"
behaviour exactly. The numeric values mirror the upper end of
EngineDriver's quadratic `getLoadPcnt` curve sampled at evenly spaced
slider positions (`maxLoad = 1000`):

```
EngineDriver step / 5  →  ((step² × 900) + 100·25) / (100·25)
0/5 → 1.00     1/5 → 1.36     2/5 → 2.44
3/5 → 4.24     4/5 → 6.76     5/5 → 10.00
```

(Mathematical aside: the named values 1.5 / 2.5 / 5.0 / 10.0 above
round those samples to nicer numbers — the curve is preserved
qualitatively but the operator sees memorable multipliers in the UI.)

### 7.2 Alternatives to consider during iteration

Listed for the user to pick from (or hybridise) in the next pass:

- **A. EngineDriver-exact:** keep the `numberOfLoadSteps` slider (5
  steps), keep `getLoadPcnt` verbatim, drive it from a small spinner
  on the Settings tab (no scenario list).
- **B. Per-roster-loco attribute:** read
  `RosterEntry.getAttribute("raildriver.loadMultiplier")` first; fall
  back to a session-level setting. Lets the operator pre-tune each
  loco / consist once.
- **C. Continuous numeric only:** drop the enum, expose just a
  `loadMultiplier` text field (1.0 .. 10.0), persist as a single
  number.
- **D. Speed-aware curve:** make the multiplier a function of current
  `speedStep` (e.g. heavier load applies more at low speed where
  starting torque dominates, less at high speed). This generalises
  the "feel" beyond EngineDriver's flat multiplier.
- **E. Asymmetric multipliers:** separate `accelLoadMultiplier` and
  `decelLoadMultiplier` so a loaded train can be made to take a long
  time to start *and* a long time to stop, without being symmetric.
- **F. Per-direction multiplier (gradients):** an extra knob for
  "downgrade" / "level" / "upgrade" applied as a coarse multiplier on
  top of the scenario. EngineDriver doesn't have this either.

Any combination (e.g. enum + roster attribute fallback + asymmetric
multipliers) is on the table; the engine-side wiring point doesn't
care. **Awaiting user direction in §7's iteration round.**

---

## 8. Direction & stop semantics

### 8.1 Direction lever

Same rules as EngineDriver
([research §5](semi-realistic-throttle-info.md#5-direction-handling)):

- `NEUTRAL` is always allowed; forces `target = 0, accel = -1`.
- Switching between `FORWARD` / `REVERSE` is allowed only at
  `speedStep == 0`; otherwise the engine ignores the change but keeps
  the lever's *target* direction so the operator can pre-set the
  reverser before stopping.

### 8.2 Stop / E-Stop

EngineDriver has four selectable stop modes
([research §6](semi-realistic-throttle-info.md#6-stop--e-stop-behaviour)).
On RailDriver:

- **E-Stop SPDT (#2):** hard E-Stop unconditionally. Bypasses the
  ramp; calls `throttle.setSpeedSetting(-1f)`. Same as today.
- **Soft stop (optional, on a front-edge user-assignable button):**
  EngineDriver's `THROTTLE_STOP_BRAKE_FULL` mode — sets
  `throttleSliderStep = 0`, `indepBrakeStep =
  numberOfBrakeSteps`, `airLineValue = 0`, calls `setTargetSpeed`,
  then unwinds those overrides as the operator manipulates the levers
  again. This is a stretch goal under the same plan; not mandatory in
  the first cut.

---

## 9. Settings, persistence & calibration UI

This section is ported from the existing plan's §2.3 / §2.4 / §2.5
because the operator-facing UX, the dirty-tracking model, and the
two-tab `RailDriverSettingsFrame` design are unchanged by the engine
swap. **Only the Settings tab's field list and the `<semiRealistic>`
XML element set differ** — the unified frame, the persisted-vs-live
split, the Save/Apply round-trip flow, and the Jynstrument toolbar
toggle (§10) are all preserved verbatim.

### 9.1 Two enable flags: `persistedEnabled` vs `liveEnabled`

The semi-realistic mode toggle is tracked as **two separate values**:

- **`persistedEnabled`** — last value loaded from the calibration XML
  (or default OFF when the file is absent), and the value most
  recently written by Save/Apply. The Settings tab displays and
  edits this value.
- **`liveEnabled`** — the value the engine and the Jynstrument act
  on. Initialised from `persistedEnabled` at attach time and after
  `reloadCalibration()`. Mutated either by the Settings tab Save/Apply
  (which also updates `persistedEnabled`) or by the Jynstrument click
  (which mutates only `liveEnabled`).

The Settings tab and the Jynstrument therefore display **different
values** when the operator has used the Jynstrument session-toggle
since the last Save: the Settings tab shows what's on disk (what
would load at next launch); the Jynstrument shows what the engine
is actually doing right now. This is by design — the Settings tab
is the persisted-state view, the Jynstrument is the session-state
view.

### 9.2 Mutation paths and persistence semantics

- **Settings window (Enable checkbox + Save/Apply):** edits
  `persistedEnabled` (in-window, dirty-tracked); on Save/Apply,
  writes XML, sets `liveEnabled = persistedEnabled`, notifies the
  engine. **Cancel** discards the in-window edit; both fields are
  unchanged.
- **Jynstrument toolbar click:** flips `liveEnabled` only. Does
  **not** touch `persistedEnabled`, does **not** write to disk, does
  **not** update the Settings tab's checkbox. Resets to
  `persistedEnabled` at next JMRI launch (or after a fresh
  `reloadCalibration()`).

If the operator wants a session-level Jynstrument change to become
the new persisted default, they open the Settings window, manually
flip the checkbox to match, and Save.

### 9.3 Mode-switch handover at speed

The operator can flip the mode toggle at any time — including while
the loco is moving. The engine's response when `liveEnabled` changes
for any reason:

- **OFF → ON:** at the moment `liveEnabled` flips to true, the engine
  reads `throttle.getSpeedSetting()` once on the EDT and seeds:

  ```java
  speedStep         = Math.round(currentFraction * maxThrottleStep);
  targetSpeed       = speedStep;          // no ramp until inputs change
  targetAcceleration = 1.0;
  airLineValue      = 100;                 // released-and-charged
  airReservoirPct   = 100;                 // released-and-charged
  // any pending repeater futures are cancelled; new ones are
  // (re)scheduled on the engine's ScheduledExecutorService only when
  // their start condition triggers (brake handle leaves zero, etc.)
  ```

  Then the engine refreshes its volatile lever inputs from the
  current axis snapshots and calls `setTargetSpeed(true)`. If the
  current lever positions ask for a different target than the
  decoder's current speed, the loco accelerates / decelerates under
  the EngineDriver math until it gets there. **No `setSpeedSetting`
  snap.** The loco's perceived speed is continuous across the toggle.

- **ON → OFF:** at the moment `liveEnabled` flips to false, the
  engine cancels its `rampTask`, `reservoirTask`, and `airLineTask`
  futures. `dispatchValueEvent` reverts to the direct
  `setSpeedSetting` / `setIsForward` / `setFunction(0, ...)` path. The
  next byte change on any axis writes the lever-derived value
  directly. **If the lever is far from the engine's last
  `speedStep`, the loco snaps to the lever-derived setting on the
  next dispatch.** Operators are expected to either move the lever
  to match the loco's current setting before flipping OFF, or to
  accept the snap as the cost of switching to direct control
  mid-motion.

### 9.4 API entry points

`RailDriverMenuItem` exposes two setters distinguished by
persistence:

```java
// Settings window Save/Apply: writes XML, then liveEnabled := persistedEnabled.
public void applyPersistedEnabled(boolean enabled);

// Jynstrument toolbar click: mutates liveEnabled only.
public void setSemiRealisticEnabledSessionOnly(boolean enabled);
```

Both setters perform the engine handover (§9.3) when `liveEnabled`
actually changes; they only differ on whether they touch
`persistedEnabled` and the XML.

Two distinct PCS events are fired on the settings listener:
`"persistedEnabledChanged"` (Settings tab subscribes; Jynstrument
ignores) and `"liveEnabledChanged"` (engine + Jynstrument
subscribe; Settings tab ignores). This guarantees the Settings tab
never reflects a session-only Jynstrument toggle, and the
Jynstrument never reflects an unsaved Settings-tab edit.

### 9.5 Unified two-tab Settings frame

There is exactly one Debug-menu entry for RailDriver configuration
(`Debug → RailDriver Settings...`), and one window the operator
opens to adjust either set of values. The existing standalone
calibration window (`RailDriverCalibrationFrame` /
`RailDriverCalibrationAction`) is retired as part of this feature;
the same calibration UI lives on the second tab of the new frame.

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

- The **Settings tab** is the one selected when the window opens
  (`setSelectedIndex(0)` in the constructor).
- The **Calibration tab** holds the existing visual-bar UI verbatim
  — bars, capture buttons, per-section "Reset to defaults" buttons,
  "Reset all to defaults" button. **None of that visual layout
  changes** under this plan; it's just hosted inside a tab now.
- The Settings tab holds the controls listed in §9.6.
- The bottom button bar is shared across tabs — Save / Apply /
  Cancel persist everything from both tabs regardless of which tab
  is currently visible.

### 9.6 Settings-tab content (EngineDriver-aligned)

The Settings tab is grouped into seven sections matching the
algorithm's input categories so the operator sees what they're
tuning:

**Mode**
- **Enable semi-realistic mode** checkbox (master switch — bound to
  `persistedEnabled`; see §9.1).

**Throttle / ramp**
- **Max throttle step:** integer 14..126, default 126. The DCC
  upper bound the engine ramps toward.
- **Step size per ramp tick:** integer 1..16, default 2. Speed-step
  units the ramp scheduler emits each tick.
- **Acceleration repeat (ms):** integer 1..10000, default 300. Base
  Δt while ramping up; multiplied by `targetAcceleration`.
- **Deceleration repeat (ms):** integer 1..10000, default 800. Base
  Δt while ramping down.

**Brake (independent + dynamic share these settings)**
- **Number of brake steps:** integer 1..16, default 7. Granularity
  of the Independent Brake lever quantisation and the dyn-brake
  region of the throttle lever.
- **Max brake percent:** integer 5..100, default 70. Cap on brake
  retardation; 100 = "instant zero" (Δt × |targetAcceleration|
  collapses to zero).
- **Max-brake-under-power offset:** real 0.0..1.0, default 0.20.
  Subtracted from `maxBrakePcnt/100` to compute
  `maxBrakeUnderPower` (the gentler curve when the throttle is
  fighting the brake — see §5.3).

**Dynamic brake**
- **Dyn-brake low-speed taper threshold (steps):** integer 0..32,
  default 8. Below this `speedStep` the dyn-brake fades linearly
  to zero (real dyn brake is ineffective at low speed).

**Air system**
- **Air refresh rate (ms):** integer 0..10000, default 2000. Tick
  interval for the reservoir / line repeaters. **0 disables the
  air simulation entirely** (`airLineValue` follows the Auto Brake
  lever directly with no recharge dynamics).
- **Reservoir replenish (% per tick):** integer 1..100, default 5.
- **Line recharge (% per tick):** integer 1..100, default 20.

**Load** *(see §7 — this section's exact UI shape is the open
iteration target)*
- **Scenario:** dropdown — `Light engine` (default) / `Switcher` /
  `Local freight` / `Through freight` / `Unit train` / `Custom`.
  Switching to a named scenario sets `customLoadMultiplier` to that
  scenario's value (greyed out unless `Custom` is selected).
- **Custom load multiplier:** real 0.1..100.0, editable when
  `Custom` is selected. Persisted as `customLoadMultiplier`; ignored
  by the engine when the scenario isn't `Custom`.

**Decoder integration**
- **Decoder-brake mode:** dropdown `None` (default) / `ESU`. When
  `ESU` is selected the six fields below become editable; when
  `None`, they are greyed out but their values are still persisted.
- **ESU low / mid / high function (F-number):** integers 0..28,
  defaults 4 / 5 / 6.
- **ESU low / mid / high threshold (% brake):** integers 0..100,
  defaults 30 / 60 / 98. Must be ascending (validated; see §9.10).

**Reset to defaults** button — settings-tab-scoped. Restores only
the semi-realistic fields to the active scenario's defaults. Does
not touch the Calibration tab's per-axis byte values.

### 9.7 Scenario defaults

Per the user requirement that *only* the load multiplier varies
across scenarios in this plan, scenarios are a **single-column
preset** rather than the existing plan's full coefficient sheet.
Switching scenarios changes `customLoadMultiplier` and nothing
else. Operators who want different ramp / brake / air feel between
scenarios continue to do that by manually editing the
non-scenario fields and saving — the Settings tab is the source of
truth for everything except the load multiplier.

| Scenario           | `customLoadMultiplier` | Mental model                                    |
|--------------------|-----------------------:|-------------------------------------------------|
| **Light engine**   |                    1.0 | Single loco, no cars. No load extension on Δt.  |
| Switcher           |                    1.5 | Yard work, a handful of cars.                   |
| Local freight      |                    2.5 | Mid-length way-freight, noticeable inertia.     |
| Through freight    |                    5.0 | Long road train; half-way to EngineDriver's 10×.|
| Unit train         |                   10.0 | Heavy unit coal / grain / oil; full ceiling.    |
| Custom             |       (operator-tuned) | Numeric input is editable; persisted as-is.     |

These multipliers mirror EngineDriver's `getLoadPcnt` curve at
evenly spaced slider steps, rounded to nicer numbers for the UI
(see §7.1 for the derivation). `Light engine` reproduces
EngineDriver's "load slider at zero" behaviour exactly — the
multiplier is 1.0, which is the multiplicative identity.

If §7's iteration round picks a different load-computation
approach (per-roster attribute, speed-aware curve, asymmetric
multipliers, etc.), this section gets rewritten accordingly. The
*shape* of the Settings tab (a small picker plus a numeric override)
should survive any of the §7 alternatives.

### 9.8 Bottom button bar

- **Save** — validates both tabs; on success writes XML, calls
  `RailDriverMenuItem.reloadCalibration()` (so polling/engine pick
  up new values), clears dirty, **closes window**. On validation
  failure the offending tab is auto-selected, an error is shown in
  the status line, the window stays open, dirty stays set.
- **Apply** — exactly the same behaviour as Save except it leaves
  the window open after success. Initially disabled; becomes
  enabled when either tab reports `dirty`; greys back out the
  moment Save or Apply completes successfully.
- **Cancel** — closes the window without writing. If `dirty` is
  true a confirmation prompt asks the operator whether to discard.
  The prompt uses
  [`jmri.util.swing.JmriJOptionPane`](https://www.jmri.org/JavaDoc/doc/jmri/util/swing/JmriJOptionPane.html),
  **not** `javax.swing.JOptionPane`. `JmriJOptionPane` plays
  correctly with always-on-top throttle frames; the standard
  `JOptionPane`'s modality blocks the entire JVM UI, which can
  hide the dialog behind always-on-top frames
  (`jmri-swing.instructions.md` §Misc).

### 9.9 Dirty-tracking model

Each tab is implemented as a `JPanel` subclass that exposes the
`RailDriverSettingsPane.DirtyTrackingTab` interface (already
established in the existing settings frame; see
[repo memory: RailDriver settings UI](#)):

```java
boolean isDirty();
void addDirtyChangeListener(Runnable listener);                // fires when isDirty() may have changed
boolean validateAndApplyTo(RailDriverCalibration target);      // false ⇒ failure (frame keeps window open)
void resetToFile(RailDriverCalibration freshFromDisk);         // reload from saved state, clears dirty
```

**Layout managers for new code in this section follow
`jmri-swing.instructions.md`:** the bottom button bar and any
row-of-controls grouping in the new Settings tab use
[`jmri.util.swing.WrapLayout`](https://www.jmri.org/JavaDoc/doc/jmri/util/swing/WrapLayout.html)
rather than `java.awt.FlowLayout`. `FlowLayout` does not display
the second row when contents wrap; `WrapLayout` does. The
Calibration tab's existing layout is unchanged (and is not
Flow-based today).

Internally each input control in a tab (`JTextField` document
listener, `JCheckBox` action listener, `JComboBox` action listener,
`JSpinner` change listener, capture-button presses on the
calibration tab, per-section / per-tab Reset buttons) calls
`markDirty()`. `markDirty()` flips a private `dirty` boolean if it
wasn't already true and notifies the listeners.

The frame holds a single
`uiDirty = settingsTab.isDirty() || calibrationTab.isDirty()` and
uses it to drive `applyButton.setEnabled(uiDirty)`. After a
successful Save / Apply, the frame calls
`resetToFile(freshlyReloadedCalibration)` on both tabs, which
clears their dirty flags and fires one final notification — Apply
greys out.

**Capture buttons (Calibration tab) mark dirty.** A capture-button
press writes the live byte into the working calibration's detent;
that's a value change ⇒ Apply enables.

### 9.10 Save / Apply persistence flow

Both buttons run the same sequence:

1. Build a fresh `RailDriverCalibration` instance representing the
   on-disk schema.
2. Call `settingsTab.validateAndApplyTo(working)` — write the
   `<semiRealistic>` subtree.
3. Call `calibrationTab.validateAndApplyTo(working)` — write the
   per-axis detent values.
4. Run **cross-field validation** on the populated `working`:

   **Blocking rules** (Save/Apply aborts; offending tab auto-selected):
   - `1 ≤ speedStep ≤ maxThrottleStep`
   - `accelRepeatMs ≥ 1`, `decelRepeatMs ≥ 1`
   - `5 ≤ maxBrakePcnt ≤ 100`
   - `numberOfBrakeSteps ≥ 1`
   - `0.0 ≤ maxBrakeUnderPower < 1.0` and
     `(maxBrakePcnt/100) − maxBrakeUnderPower ≥ 0.05` (some headroom
     between the under-power and full curves)
   - `customLoadMultiplier ∈ [0.1, 100.0]`
   - All ESU function numbers `∈ [0, 28]`
   - ESU thresholds ascending: `esuLowThresh ≤ esuMidThresh ≤ esuHighThresh`
   - ESU thresholds `∈ [0, 100]`
   - Air-system fields `≥ 0`; reservoir / line refill rates `∈ [1, 100]`

   **Non-blocking warnings** (logged in status line after Save/Apply
   success):
   - `decelRepeatMs < accelRepeatMs` — counter-prototype; usually a
     mistake but allowed.
   - `speedStep × maxThrottleStep / accelRepeatMs > 1.0` — full-range
     ramp in less than one second; will feel arcade-like.
   - `airRefreshRateMs == 0` and `<decoderBrakeMode> != none` —
     decoder brake still works but the air half of the simulation is
     off; surface as a heads-up.

5. If any per-field validation in steps 2–3 returned false, or any
   blocking cross-field rule in step 4 failed, abort: auto-select
   the offending tab, show the validation message in the status
   line, leave window open, leave dirty set.
6. Persist `working` to XML.
7. Call `RailDriverMenuItem.reloadCalibration()` so the polling
   thread + the semi-realistic engine pick up new values without
   restart.
8. Re-load `working` from disk (round-trip) and call
   `resetToFile(roundTripped)` on both tabs — guarantees the
   in-window state matches the file exactly, clears dirty.
9. Show any cross-field warnings recorded in step 4 in the status
   line.
10. Save closes the window via `dispose()`; Apply does not.

### 9.11 XML schema and migration

`RailDriverCalibration` continues to own the `<raildriver-calibration>`
root element. The schema bumps to **`version="3"`**. The
`<semiRealistic>` subtree's *content* is replaced wholesale (the v2
plan's velocity-physics elements are abandoned), but the rest of
the file (`<reverser>`, `<throttle>`, `<autoBrake>`, `<indepBrake>`,
`<bailoff>`, `<wiper>`, `<lights>`) is unchanged.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<raildriver-calibration version="3">
    <reverser>...</reverser>
    <throttle>...</throttle>
    <autoBrake>...</autoBrake>
    <indepBrake>...</indepBrake>
    <bailoff>...</bailoff>
    <wiper>...</wiper>
    <lights>...</lights>

    <semiRealistic>
        <enabled>true</enabled>

        <!-- Throttle / ramp -->
        <maxThrottleStep>126</maxThrottleStep>
        <speedStep>2</speedStep>
        <accelRepeatMs>300</accelRepeatMs>
        <decelRepeatMs>800</decelRepeatMs>

        <!-- Brake -->
        <numberOfBrakeSteps>7</numberOfBrakeSteps>
        <maxBrakePcnt>70</maxBrakePcnt>
        <maxBrakeUnderPower>0.20</maxBrakeUnderPower>

        <!-- Dyn brake -->
        <dynBrakeMinSpeedStep>8</dynBrakeMinSpeedStep>

        <!-- Air -->
        <airRefreshRateMs>2000</airRefreshRateMs>
        <airReservoirReplenishPcnt>5</airReservoirReplenishPcnt>
        <airLineRechargePcnt>20</airLineRechargePcnt>

        <!-- Load -->
        <load>
            <!--
              Persisted as the LoadScenario enum constant name (e.g.
              LIGHT_ENGINE / SWITCHER / LOCAL_FREIGHT / THROUGH_FREIGHT /
              UNIT_TRAIN / CUSTOM). The display strings ("Light engine",
              etc.) live in the UI bundle only; they must not appear in
              the XML. Read/written via
              `AbstractXmlAdapter.EnumIoNames` (or
              `EnumIoNamesNumbers` if numeric back-compat is ever
              needed) so the JMRI ErrorHandler is consulted on
              malformed values rather than silent coercion.
            -->
            <scenario>LIGHT_ENGINE</scenario>
            <customMultiplier>1.0</customMultiplier>
        </load>

### 9.11 Persistence: profile-aware `AuxiliaryConfiguration` fragments

The `jmri-preferences.instructions.md` guidance is to persist
operator preferences through `jmri.profile.AuxiliaryConfiguration`
(via `jmri.profile.ProfileUtils`) rather than a self-managed
freestanding XML file, so multiple JMRI managers cannot clobber each
other and so settings benefit from JMRI's existing
shared-vs-private-profile split. The current `RailDriverCalibration`
class writes a freestanding `<profile>/profile/raildriver-calibration.xml`,
which pre-dates the rest of this plan; this section migrates that to
the `AuxiliaryConfiguration` API.

**Decision: two `AuxiliaryConfiguration` fragments under different
spaces**, distinguishing per-machine hardware data from portable
operator preferences:

| Fragment                                   | Space   | Why                                                                                  |
|--------------------------------------------|---------|---------------------------------------------------------------------------------------|
| `<rd:hardwareCalibration>` (all detents)   | private | HID byte detents drift per RailDriver unit; differ across operator preference for idle deadband; cannot be cloud-synced safely. |
| `<rd:semiRealistic>` (EngineDriver-aligned)| shared  | Ramp / brake / air / scenario / decoder-brake settings describe how the operator wants the throttle to *feel*; portable across machines. |

Namespace: `http://jmri.org/xml/schema/raildriver/3` (declared as
`xmlns:rd="..."` on each fragment root). The `/3` suffix encodes the
schema generation; future breaking changes get a `/4`, `/5`, etc.
Namespace per fragment because that is what `AuxiliaryConfiguration`
uses to disambiguate a fragment's owner.

Reads use
`ProfileUtils.getAuxiliaryConfiguration(profile).getConfigurationFragment(name, namespace, shared)`
and writes use
`ProfileUtils.getAuxiliaryConfiguration(profile).putConfigurationFragment(JDOMUtil.toW3CElement(element), shared)`,
following the existing `StartupActionsManager` precedent
([source](https://github.com/JMRI/JMRI/blob/master/java/src/jmri/util/startup/StartupActionsManager.java)).
The two fragments are loaded and saved independently; there is no
longer a single document owning both.

### 9.11.1 `<rd:hardwareCalibration>` (private)

Lives inside the per-node `profile.xml` (the `jmri-<UUID>-<ID>`
subdirectory). One fragment, holding the seven detent children
unchanged from today:

```xml
<rd:hardwareCalibration
    xmlns:rd="http://jmri.org/xml/schema/raildriver/3"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://jmri.org/xml/schema/raildriver/3
                        http://jmri.org/xml/schema/raildriver-hardware-calibration-3.xsd">
    <rd:reverser>...</rd:reverser>
    <rd:throttle>...</rd:throttle>
    <rd:autoBrake>...</rd:autoBrake>
    <rd:indepBrake>...</rd:indepBrake>
    <rd:bailoff>...</rd:bailoff>
    <rd:wiper>...</rd:wiper>
    <rd:lights>...</rd:lights>
</rd:hardwareCalibration>
```

Detent children inherit their existing structure verbatim from the
current `RailDriverCalibration` writer; only the wrapping element
namespace and storage location change.

### 9.11.2 `<rd:semiRealistic>` (shared)

Lives at the root of the profile directory in the shared
`profile.xml`. One fragment, holding all of the EngineDriver-aligned
operator preferences:

```xml
<rd:semiRealistic
    xmlns:rd="http://jmri.org/xml/schema/raildriver/3"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://jmri.org/xml/schema/raildriver/3
                        http://jmri.org/xml/schema/raildriver-semi-realistic-3.xsd">
    <rd:enabled>true</rd:enabled>

    <!-- Throttle / ramp -->
    <rd:maxThrottleStep>126</rd:maxThrottleStep>
    <rd:speedStep>2</rd:speedStep>
    <rd:accelRepeatMs>300</rd:accelRepeatMs>
    <rd:decelRepeatMs>800</rd:decelRepeatMs>

    <!-- Brake -->
    <rd:numberOfBrakeSteps>7</rd:numberOfBrakeSteps>
    <rd:maxBrakePcnt>70</rd:maxBrakePcnt>
    <rd:maxBrakeUnderPower>0.20</rd:maxBrakeUnderPower>

    <!-- Dyn brake -->
    <rd:dynBrakeMinSpeedStep>8</rd:dynBrakeMinSpeedStep>

    <!-- Air -->
    <rd:airRefreshRateMs>2000</rd:airRefreshRateMs>
    <rd:airReservoirReplenishPcnt>5</rd:airReservoirReplenishPcnt>
    <rd:airLineRechargePcnt>20</rd:airLineRechargePcnt>

    <!-- Load -->
    <rd:load>
        <!--
          Persisted as the LoadScenario enum constant name (e.g.
          LIGHT_ENGINE / SWITCHER / LOCAL_FREIGHT / THROUGH_FREIGHT /
          UNIT_TRAIN / CUSTOM). The display strings ("Light engine",
          etc.) live in the UI bundle only; they must not appear in
          the XML. Read/written via
          `AbstractXmlAdapter.EnumIoNames` (or `EnumIoNamesNumbers`
          if numeric back-compat is ever needed) so the JMRI
          ErrorHandler is consulted on malformed values rather than
          silent coercion.
        -->
        <rd:scenario>LIGHT_ENGINE</rd:scenario>
        <rd:customMultiplier>1.0</rd:customMultiplier>
    </rd:load>

    <!-- Decoder integration -->
    <rd:decoderBrake>
        <rd:mode>none</rd:mode>
        <rd:esuLowFunction>4</rd:esuLowFunction>
        <rd:esuMidFunction>5</rd:esuMidFunction>
        <rd:esuHighFunction>6</rd:esuHighFunction>
        <rd:esuLowThresh>30</rd:esuLowThresh>
        <rd:esuMidThresh>60</rd:esuMidThresh>
        <rd:esuHighThresh>98</rd:esuHighThresh>
    </rd:semiRealistic>
```

`<rd:enabled>` is the persisted `persistedEnabled` value from §9.1.
`liveEnabled` is **never** persisted; it is a session-only field on
the in-memory settings record.

### 9.11.3 Per-fragment validation and error reporting

Loading is per-fragment but follows the same rules as §9.10's
cross-field validation:

- **Missing fragment.** A fresh profile has neither fragment yet.
  `getConfigurationFragment(...)` returns `null`; the loader applies
  the schema's static defaults silently. Same behaviour as today's
  "file absent" path — no `ErrorHandler` traffic.
- **Missing child within a present fragment.** Treated as the
  schema's static default, silently. Same rationale as the previous
  draft's per-child default fallback.
- **Present-but-unparseable child** (e.g. non-integer
  `<rd:speedStep>`, unknown `<rd:scenario>` enum constant,
  out-of-range `<rd:maxBrakePcnt>`). Routed through the
  `AbstractXmlAdapter` parsing helpers so an error-level
  `ErrorHandler` report is emitted before the default is applied.
- **Cross-field invalid combination** (e.g.
  `speedStep > maxThrottleStep`, ESU thresholds out of order):
  identical to the unparseable case — error-level `ErrorHandler`,
  default substituted for the offending field(s). The blocking
  rules from §9.10 are run on load, not just on Save / Apply.
- **Fragment with an unknown namespace** (e.g. a future
  `http://jmri.org/xml/schema/raildriver/4` fragment loaded by an
  older JMRI). Treated as missing; static defaults applied. The
  warn-level legacy-migration report (§9.13) is **not** triggered
  for this case — no settings are silently overwritten because
  the older JMRI has no concept of v4 settings to overwrite.

The matching `SemiRealisticSettings` record is unchanged from the
previous draft (still authoritative for the in-memory shape of the
shared fragment):

```java
public final class SemiRealisticSettings {

    // Lifecycle (§9.1)
    public boolean persistedEnabled        = false;
    public boolean liveEnabled             = false;

    // Throttle / ramp
    public int     maxThrottleStep         = 126;
    public int     speedStep               = 2;
    public int     accelRepeatMs           = 300;
    public int     decelRepeatMs           = 800;

    // Brake
    public int     numberOfBrakeSteps      = 7;
    public int     maxBrakePcnt            = 70;
    public double  maxBrakeUnderPower      = 0.20;     // offset; effective = (maxBrakePcnt/100) - this

    // Dyn brake
    public int     dynBrakeMinSpeedStep    = 8;

    // Air
    public int     airRefreshRateMs        = 2000;     // 0 = simulation off
    public int     airReservoirReplenishPcnt = 5;
    public int     airLineRechargePcnt     = 20;

    // Load (see §7).
    // loadScenario is persisted as the enum's constant name (e.g.
    // "LIGHT_ENGINE") via AbstractXmlAdapter.EnumIoNames so the on-disk
    // form is decoupled from UI display strings.
    public LoadScenario loadScenario       = LoadScenario.LIGHT_ENGINE;
    public double  customLoadMultiplier    = 1.0;

    // Decoder integration
    public DecoderBrakeMode decoderBrakeMode = DecoderBrakeMode.NONE;
    public int     esuLowFunction          = 4;
    public int     esuMidFunction          = 5;
    public int     esuHighFunction         = 6;
    public int     esuLowThresh            = 30;
    public int     esuMidThresh            = 60;
    public int     esuHighThresh           = 98;
}
```

### 9.12 XSD schema and validation

Any change to what JMRI writes in either fragment must be reflected
in the matching XSD under `xml/schema/`, per the repository's XML
conventions (see `.github/instructions/jmri-xml.instructions.md` and
`jmri-xml-persistence.instructions.md`). One XSD per fragment, both
in the `http://jmri.org/xml/schema/raildriver/3` namespace:

- `xml/schema/raildriver-hardware-calibration-3.xsd` — schema for
  the `<rd:hardwareCalibration>` fragment (§9.11.1).
- `xml/schema/raildriver-semi-realistic-3.xsd` — schema for the
  `<rd:semiRealistic>` fragment (§9.11.2).

The per-fragment work breaks down as:

- **Author the XSDs.** Use the **Venetian Blinds** pattern — the
  top-level fragment element has a named complex type; inner
  elements (e.g. `<rd:load>`, `<rd:decoderBrake>`) are defined
  anonymously inside that type. Reuse the standard helper types in
  `xml/schema/types/general.xsd` (e.g. `trueFalseType` for
  `<rd:enabled>` / `<rd:mode>` flags) where they apply.
- **Schema-location reference.** When the writer emits each
  fragment, the root element gets `xmlns:rd`,
  `xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"`, and
  `xsi:schemaLocation` pointing at the matching XSD URL. Both URLs
  use the JMRI `http://jmri.org/xml/schema/...` convention so a
  browser or `xmllint` invocation can resolve the schema directly.
  The JMRI standard `<?xml-stylesheet?>` PI is added to the **XSD**
  files themselves (so they render readably in a browser); the
  fragments do not get one because they are embedded inside
  `profile.xml` and never opened on their own.
- **Validate the schemas themselves.** Before commit:

  ```sh
  xmllint -noout -schema http://www.w3.org/2001/XMLSchema.xsd \
      xml/schema/raildriver-hardware-calibration-3.xsd
  xmllint -noout -schema http://www.w3.org/2001/XMLSchema.xsd \
      xml/schema/raildriver-semi-realistic-3.xsd
  ```

- **Validate sample fragment files.** The test fixtures in
  `valid/` (§12.4) are stand-alone fragment files (i.e. one
  fragment XML per fixture), each with the appropriate
  `xsi:schemaLocation`. For each file:

  ```sh
  xmllint -noout <fixture>.xml
  ```

  (the schema is resolved from the in-file location). For each
  `invalid/` fixture, the same command must fail.
- **Annotate the new types with the responsible class.** Per the
  schema-development guidance, add an `<xs:annotation>`/`<xs:appinfo>`
  block on each top-level complex type identifying the `*Xml`
  reader/writer (e.g.
  `<jmri:usingclass configurexml="true">jmri.jmrix.raildriver.configurexml.RailDriverHardwareCalibrationXml</jmri:usingclass>`,
  exact FQNs deferred to the implementation phase per the
  package-naming note in §3 of the structure instructions).
- **No schema versioning yet.** The `/3` namespace is the first
  generation of the `AuxiliaryConfiguration` form. Future breaking
  changes get a new namespace (`/4`, `/5`, …) plus a new XSD;
  older fixtures stay valid forever because their namespaces never
  change.

### 9.13 Migration from the legacy freestanding XML file

The current `RailDriverCalibration.loadOrDefault(File)` /
`RailDriverCalibration.save(File)` pair writes a single freestanding
`<profile>/profile/raildriver-calibration.xml` (root element
`<raildriver-calibration version="2">`). Operators with existing
v1 / v2 files therefore have content on disk that must be moved
into the two new `AuxiliaryConfiguration` fragments without losing
their captured detents.

Migration runs once per profile, the first time a JMRI build with
the new code initialises that profile:

1. **Detect.** On startup, the calibration loader checks both:
   - whether the new `<rd:hardwareCalibration>` fragment exists in
     `getAuxiliaryConfiguration(profile)` (private space), and
   - whether the legacy file
     `<profile>/profile/raildriver-calibration.xml` exists.
2. **If only the legacy file exists**, parse it with the existing
   v1 / v2-tolerant JDOM2 path (already in
   `RailDriverCalibration.loadOrDefault`):
   - Copy every captured detent from the legacy file into a fresh
     `<rd:hardwareCalibration>` fragment and write that fragment to
     `getAuxiliaryConfiguration(profile)` in **private** space.
   - The legacy file's `<semiRealistic>` subtree (only present in
     v2 files) is **discarded** and triggers the warn-level
     `ErrorHandler` + `log.warn` report previously specified in
     §9.11; the new `<rd:semiRealistic>` fragment is written with
     defaults to **shared** space. Operators who had in-progress
     velocity-physics calibrations are not migrated — the meaning
     of every field changed (m/s² → integer milliseconds / steps),
     so silent migration would produce nonsense values.
   - Rename the legacy file to
     `<profile>/profile/raildriver-calibration.xml.bak` (do **not**
     delete it; the operator may want it for forensic purposes).
     Log an `INFO`-level message identifying the legacy file path,
     the new fragment locations, and the `.bak` filename.
3. **If both the new fragment(s) and the legacy file exist**
   (e.g. the operator manually copied the legacy file back from a
   backup), the new fragments win. Emit a warn-level
   `ErrorHandler` report telling the operator the legacy file is
   being ignored and where to find the active settings. Do **not**
   rename the legacy file in this case — the operator put it there
   on purpose.
4. **If neither exists**, write nothing; defaults apply on first
   load and a fresh fragment is created on first save.

This migration runs from the same lifecycle hook that today calls
`RailDriverCalibration.loadOrDefault(...)` (i.e. eagerly during
`RailDriverMenuItem` setup or lazily on first `getCalibration()`).
It is idempotent: repeated calls find the new fragments already in
place and short-circuit.

The legacy `RailDriverCalibration.getDefaultFile()` /
`loadOrDefault(File)` / `save(File)` API is **retained**, but its
storage path becomes the migration source only; its primary use is
delegated to two new helpers that round-trip the fragments:

```java
static RailDriverHardwareCalibration loadHardwareCalibration(@Nonnull Profile profile);
static void saveHardwareCalibration(@Nonnull Profile profile, @Nonnull RailDriverHardwareCalibration cal);

static SemiRealisticSettings loadSemiRealisticSettings(@Nonnull Profile profile);
static void saveSemiRealisticSettings(@Nonnull Profile profile, @Nonnull SemiRealisticSettings s);
```

Both pairs use
`ProfileUtils.getAuxiliaryConfiguration(profile).getConfigurationFragment(...)` /
`putConfigurationFragment(...)` and pass the appropriate `shared`
flag (private for hardware, shared for semi-realistic) following the
`StartupActionsManager` precedent.

---

## 10. Throttle-toolbar mode toggle (Jynstrument)

Ported from the existing plan's §2.6 verbatim. The Jynstrument is
mode-toggle UI only; its behaviour is independent of which engine
algorithm is running underneath.

The Settings window's `Enable semi-realistic mode` checkbox is the
authoritative toggle, but burying it inside a tabbed dialog opened
from the Debug menu is too much friction for a setting an operator
might flip multiple times per session (e.g. switching between
yard-switching mode and over-the-road mode). A second, faster path
lives directly on the throttle window's toolbar.

### 10.1 What the Jynstrument exposes

A single toolbar button:

- **Icon** — green when semi-realistic mode is ON, grey when OFF,
  plus a transient "binding…" icon during the State 2 → State 4
  handover. Three PNGs ship with the `.jyn` folder.
- **Click** — toggles `liveEnabled` **for the current session
  only**; does **not** write the calibration XML. Notifies the
  engine + Settings window via PCS so all three stay coherent in
  memory. Persistent changes go through the Settings window (§9).
- **Right-click** — popup with one item: `Settings...` → opens the
  unified Settings frame to the Settings tab.
- **Tooltip** — `RailDriver semi-realistic throttle: ON / OFF (session)`.

### 10.2 Java-side support

`RailDriverMenuItem` gains a small public API to support the
Jynstrument (and any future toolbar / status surface):

```java
public boolean isSemiRealisticLiveEnabled();
public boolean isSemiRealisticPersistedEnabled();
public void applyPersistedEnabled(boolean enabled);             // §9.4
public void setSemiRealisticEnabledSessionOnly(boolean enabled); // §9.4

public boolean isRailDriverConnected();
public ThrottleFrame getActiveThrottleFrame();
public boolean isAttachInProgress();
public void requestAttachToThrottle(ThrottleFrame tf);

public void addSettingsListener(PropertyChangeListener l);
public void removeSettingsListener(PropertyChangeListener l);
```

PCS events fired on the settings listener:

- `"liveEnabledChanged"` — fired by both `applyPersistedEnabled` and
  `setSemiRealisticEnabledSessionOnly`. Engine + Jynstrument
  subscribe.
- `"persistedEnabledChanged"` — fired by `applyPersistedEnabled`
  only, after the XML write. Settings tab subscribes.
- `"railDriverConnected"` — fired from the existing
  `HidServicesListener` callbacks.
- `"activeThrottleFrame"` — fired around `attachThrottleWindow`
  binds.
- `"attachInProgress"` — fired around the async attach window.

### 10.3 Jynstrument click behaviour by state

The Jynstrument can find itself in five states:

| # | State                  | Trigger                                        | Click                                           | Visual                                  |
|---|------------------------|------------------------------------------------|-------------------------------------------------|-----------------------------------------|
| 1 | **No device**          | `!isRailDriverConnected()`                     | No-op                                           | Greyed; "RailDriver not detected"       |
| 2 | **Device, unbound**    | connected & no active frame & no attach pending | Auto-attach to this frame; `pendingSessionToggle = true` | Normal; "Click to attach + toggle"      |
| 2.5 | **Attach in progress** | `isAttachInProgress()`                         | No-op                                           | Transient "binding…"                    |
| 3 | **Bound elsewhere**    | connected & active frame ≠ this                | No-op                                           | Greyed; "RailDriver bound elsewhere"    |
| 4 | **Operational**        | connected & active frame == this               | `setSemiRealisticEnabledSessionOnly(!isSemiRealisticLiveEnabled())` | On/off icon; tooltip per current state  |

State-2 deferred-toggle handling: the click triggers an async
`requestAttachToThrottle`; when the resulting `"activeThrottleFrame"`
PCS event lands, if `pendingSessionToggle` is set and the bind
happened on this Jynstrument's frame, the toggle is applied;
otherwise the flag is cleared.

### 10.4 Bootstrap / install

The `.jyn` folder ships at
`jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/`.
`RailDriverMenuItem.attachThrottleWindow()` auto-installs it after a
successful bind, idempotently, via a recursive `hasJynstrumentInstalled`
check that walks the throttle window's component tree (because
`ThrottleWindow.throttleToolBar` has no public getter). Same pattern
the existing `ThrottleWindow` close-handler and save-Jynstruments
code use.

The auto-install does not track removal in any persisted state.
Within a session, the operator can right-click → Quit to remove the
toggle; it stays gone until the next `Debug → RailDriver Throttle`
re-attach. Across JMRI restarts, persistence relies on the standard
throttle-layout XML (`ThrottleWindow.java:800–865`).

The Jynstrument's `quit()` hook deregisters its PCS listener so the
disposed instance does not leak listener subscriptions.

---

## 11. Implementation phases

### 11.0 Package locations

All RailDriver code already lives under `jmri.util.usb`, reflecting
its status as a USB-HID **peripheral** (it drives an existing
`DccThrottle` rather than implementing its own layout connection)
and not a `jmri.jmrix.<vendor>` system connection. This plan does
**not** relocate any existing class. New code goes into the same
tree, but `jmri-swing.instructions.md` and
`jmri-xml-persistence.instructions.md` require Swing UI and
configurexml adapters to live in dedicated subpackages so the
non-UI code stays headless-friendly.

Concrete package locations under this plan:

| Class                                                           | Package                                  | Notes                                                     |
|-----------------------------------------------------------------|------------------------------------------|------------------------------------------------------------|
| `SemiRealisticThrottleEngine`, `SemiRealisticSettings`, `LoadScenario`, `DecoderBrakeMode`, `RailDriverHardwareCalibration` | `jmri.util.usb`                          | Engine + settings POJOs; **no Swing imports**.            |
| `RailDriverCalibration` (legacy facade + new helpers)           | `jmri.util.usb`                          | Existing class; new `loadHardware*` / `loadSemiRealistic*` helpers stay non-Swing. |
| `RailDriverMenuItem`                                            | `jmri.util.usb`                          | Existing location; the Swing-touching call sites continue to use `ThreadingUtil.runOnGUIEventually`. |
| `RailDriverSettingsPane`, `SemiRealisticSettingsPanel`, `CalibrationTabPanel`, `CalibrationBar` | `jmri.util.usb.swing` *(new)* | All Swing UI. Existing `*Panel` / `*Frame` classes move into this subpackage in Phase 2. |
| `RailDriverSettingsAction` (if retained) and any future `JmriNamedPaneAction` users | `jmri.util.usb.swing`                    | Action classes ship next to the panes they open.          |
| `RailDriverHardwareCalibrationXml`, `SemiRealisticSettingsXml`  | `jmri.util.usb.configurexml` *(new)*     | Per-fragment `*Xml` adapters that own the XML read/write logic for the `AuxiliaryConfiguration` fragments (§9.11). The runtime classes stay free of XML logic. |
| `RailDriverModeToggle.jyn`                                      | `jython/Jynstruments/ThrottleWindowToolBar/` | Distribution content; remains exactly where the Jynstrument loader expects it. |

The two new subpackages (`jmri.util.usb.swing`,
`jmri.util.usb.configurexml`) are created in Phase 2 alongside the
first classes that need them. Phase 2 also relocates the existing
`SemiRealisticSettingsPanel`, `RailDriverSettingsFrame` (→
`RailDriverSettingsPane`), `CalibrationTabPanel`, and
`CalibrationBar` from flat `jmri.util.usb` into
`jmri.util.usb.swing` so the `swing`-subpackage convention is
actually enforced (today they are flat).

### Phase 1 — Engine port

- Rewrite `SemiRealisticThrottleEngine` with the §5 class outline.
- Reduce `LoadScenario` to `(displayName, defaultMultiplier)` per
  §7.1 (placeholder — final shape is TBD after §7 iteration).
- Reduce `SemiRealisticSettings` to the §9.11 field set.
- Unit tests: replicate EngineDriver's expected outputs for
  `getBrakeDecimalPcnt`, `getLoadPcnt`-equivalent (whatever §7
  resolves to), and a couple of `setTargetSpeed` table-driven cases
  taken from the EngineDriver source ([research §3](semi-realistic-throttle-info.md#3-settargetspeed--the-heart-of-the-semi-realistic-logic)).
- No RailDriver hardware changes yet; new engine compiles and is
  exercised solely from the test harness.
- **Logging (§5.6).** Add the standard `private static final
  org.slf4j.Logger log = ...` field to every new engine class.
  Per-tick scheduler and per-axis recompute output land at
  `TRACE` and `DEBUG` respectively; do **not** add `INFO` from
  steady-state code paths.
- **Javadoc (§12.6).** Author Javadoc for every new public /
  protected method on the engine, settings record, and helper
  classes added in this phase.

### Phase 2 — Settings & persistence rebuild

- Land §9.1 / §9.2 / §9.4 (`persistedEnabled` / `liveEnabled` split,
  `applyPersistedEnabled` + `setSemiRealisticEnabledSessionOnly`).
- Replace `SemiRealisticSettingsPanel` with the §9.6 field
  layout, implementing `DirtyTrackingTab`.
- **Rework the unified Settings UI as a `JmriPanel`** per §9.5:
  - Add `jmri.util.usb.swing.RailDriverSettingsPane` (a
    `JmriPanel`) that owns the tabbed UI, dirty tracking, button
    bar, validation, and status line.
  - Move the existing `SemiRealisticSettingsPanel`,
    `CalibrationTabPanel`, and `CalibrationBar` from
    `jmri.util.usb` into `jmri.util.usb.swing` so the
    `swing`-subpackage convention (§11.0) is enforced.
  - Replace the Debug-menu wiring with
    `new jmri.util.swing.JmriNamedPaneAction("...", new
    jmri.util.swing.sdi.JmriJFrameInterface(),
    "jmri.util.usb.swing.RailDriverSettingsPane")`. Retire
    `RailDriverSettingsFrame`; if `RailDriverSettingsAction` is
    still needed, slim it to a thin `JmriAbstractAction`.
  - Use `jmri.util.swing.WrapLayout` (not `java.awt.FlowLayout`)
    for the bottom button bar and any wrapping rows in the
    Settings tab (§9.9).
  - Use `jmri.util.swing.JmriJOptionPane` for the dirty-Cancel
    confirmation dialog (§9.8).
- Implement the §9.10 Save / Apply round-trip with the new
  cross-field validation rules.
- **Migrate persistence to `AuxiliaryConfiguration`** (§9.11):
  - Add a `RailDriverHardwareCalibration` type holding the seven
    detent records, separate from `SemiRealisticSettings`.
  - Add `loadHardwareCalibration` / `saveHardwareCalibration`
    (private space) and `loadSemiRealisticSettings` /
    `saveSemiRealisticSettings` (shared space) to
    `RailDriverCalibration`, both using
    `ProfileUtils.getAuxiliaryConfiguration(profile)` per the
    `StartupActionsManager` precedent. Implementation lives in
    `jmri.util.usb.configurexml.RailDriverHardwareCalibrationXml`
    and `jmri.util.usb.configurexml.SemiRealisticSettingsXml`
    (§11.0).
  - Replace every existing call site of
    `RailDriverCalibration.loadOrDefault(getDefaultFile())` and
    `cal.save(getDefaultFile())` with the new pair.
  - Route the v3 fragment load — plus the §9.13 legacy
    `<semiRealistic>` discard — through `ErrorHandler` per
    §9.11.3.
- **Implement §9.13 legacy-file migration.** On profile
  initialisation, if `<rd:hardwareCalibration>` is missing **and**
  `<profile>/profile/raildriver-calibration.xml` exists, parse the
  legacy file with the existing v1 / v2 path, populate the two new
  fragments, and rename the legacy file to `*.xml.bak`. Idempotent
  on subsequent runs.
- **XSDs.** Add `xml/schema/raildriver-hardware-calibration-3.xsd`
  and `xml/schema/raildriver-semi-realistic-3.xsd`. Each follows
  the Venetian-Blinds pattern, reuses shared types from
  `types/general.xsd`, and is annotated with the responsible `*Xml`
  reader/writer class via `<xs:annotation>`/`<xs:appinfo>`. The
  fragment writer emits the matching `xsi:schemaLocation` on each
  fragment root.
- **Schema validation.** Run
  `xmllint -noout -schema http://www.w3.org/2001/XMLSchema.xsd
  xml/schema/raildriver-hardware-calibration-3.xsd` and
  `xml/schema/raildriver-semi-realistic-3.xsd`, plus the per-fixture
  `xmllint -noout <fixture>.xml` checks against every file under
  `valid/` / `invalid/` / `load/` (§12.4) before commit.
- **`EnumIoNames`-style enum persistence.** Wire `LoadScenario`
  and `DecoderBrakeMode` through `AbstractXmlAdapter.EnumIoNames`
  (or `EnumIoNamesNumbers` if numeric back-compat is later
  required) so the on-disk form is the constant name and bad
  values land in `ErrorHandler`.
- **Test fixtures (§12.4 / §12.5).** Add the per-fragment
  validation samples, intentionally-invalid samples, the v1 / v2
  legacy-file migration fixtures, and the matching `SchemaTest`
  and `LoadAndStoreTest` classes.
- **Logging (§5.6).** Add `private static final
  org.slf4j.Logger log = ...` to every new class, place attach /
  detach / migration / save messages at `INFO`, the v2 discard
  + malformed-child events at `WARN` (paired with `ErrorHandler`),
  and any per-axis recompute trace at `DEBUG`.
- **Javadoc (§12.6).** Author Javadoc for every new public /
  protected API in `RailDriverMenuItem`, the engine, the new
  settings record, the `RailDriverSettingsPane`, and the new
  `*Xml` adapters before the phase ships.
- **Help pages (§12.6).** Add the Settings-tab and migration-notes
  help pages so the operator-facing documentation lands with the
  feature.

### Phase 3 — RailDriver lever wiring

- Patch `RailDriverMenuItem.dispatchValueEvent` per §5.4 (one case
  per axis). Each setter just stores into a `volatile` field on the
  engine and triggers `setTargetSpeed(true)`.
- Wire `setLEDs` integration for the dyn-brake region (today's TODO
  at `RailDriverMenuItem.java:962`).
- Bail-off threshold & polarity are read from the existing
  `RailDriverCalibration` block.
- Manual smoke test on a real RailDriver: each lever produces the
  expected engine state per the per-tick log line.

### Phase 4 — Air system & dyn brake

- Implement the reservoir + air-line repeaters as `ScheduledFuture`s
  on the engine's executor (§6.2).
- Wire `setAirLineValue` from Auto Brake; `setBailoffAsserted` from
  byte 4.
- Implement the low-speed dyn-brake taper (§6.3).
- `airRefreshRateMs == 0` short-circuits the repeaters (§9.6).

### Phase 5 — ESU decoder brake passthrough

- Port `setDecoderBrake` ([research §4.3](semi-realistic-throttle-info.md#43-esu-decoder-brake-functions--passthrough-to-the-loco))
  using JMRI's `DccThrottle.setFunction(int, boolean)` API.
- Three-pass logic (clear-all, find-highest-fitting, dispatch
  changes-only) is preserved verbatim.
- Mode toggle on the Settings tab gates the entire passthrough.

### Phase 6 — Throttle-toolbar Jynstrument

- Ship `RailDriverModeToggle.jyn` (§10).
- Implement the five-state click logic and PCS event subscriptions
  in the Jython class.
- `RailDriverMenuItem.attachThrottleWindow()` auto-installs the
  toggle on first bind via `hasJynstrumentInstalled`.

### Phase 7 — Stop modes (optional)

- Wire E-Stop SPDT to `throttle.setSpeedSetting(-1f)` (today's
  behaviour — bypasses the ramp).
- Optionally wire one front-edge button to the soft-stop sequence
  per §8.2.
- Skippable for the first cut; revisit if operator feedback asks
  for it.

---

## 12. Testing strategy

### 12.1 Unit tests (pure math, no JMRI runtime)

Table-driven tests for the static helpers, with expected values copied
from EngineDriver's source so any future drift from the upstream is
caught immediately:

```java
@Test public void brakeDecimalPcnt_matchesEngineDriver() {
    // step=0 → 1.0 (free)
    // step=7, steps=7, max=0.7 → ≈ 0.30 (full)
    // ... three or four points along the curve
}

@Test public void loadMultiplier_lightEngineIsOne() { /* ... */ }
@Test public void loadMultiplier_unitTrainIsTen()   { /* ... */ }
```

### 12.2 Engine integration tests

Run the engine with a mock `DccThrottle`, drive lever inputs through
the polling-thread setters, sample the emissions:

- **Pure-throttle ramp:** `throttleSliderStep = 64`, no brake → emit
  sequence walks in `+speedStep` increments at `accelRepeatMs`
  intervals, finishing at fraction = 64/126.
- **Brake clip:** `throttleSliderStep = 64`, `indepBrakeStep =
  numberOfBrakeSteps/2` → engine settles at `target = 64 * brakePcnt`,
  not 64.
- **Brake to zero:** `throttleSliderStep = 0`, `indepBrakeStep = full`
  → emit walks down at `decelRepeatMs × |effectiveBrake|`.
- **Air depletion:** Auto Brake from Released to half travel →
  `airLineValue` drops → engine clips target proportionally.
- **Bail-off:** with indep brake at half and Auto Brake at half,
  toggle bail-off → effective brake jumps *up* (only air contributes)
  and target rises.
- **Direction interlock:** at non-zero speed, `setDirection(REVERSE)`
  is rejected (current direction unchanged); at zero speed it's
  accepted.
- **Load multiplier:** for each scenario, verify Δt scales by exactly
  the resolved multiplier vs `LIGHT_ENGINE`.

### 12.3 Manual hardware tests

End-to-end on a real RailDriver console with a connected layout
(or a JMRI debug-throttle), validating that each lever's feel
matches the Δt expectation. Smoke list:

- Throttle slam from idle → full: ~19 s ramp at defaults.
- Throttle full → idle: ~50 s coast (drag-only) at defaults.
- Indep brake full + throttle 50 %: loco settles at ~15 % of full.
- Auto Brake EMG: hard decel at ~3 s (effectiveBrake ≈ 0.3,
  baseDecel × 0.3 = 240 ms/step × 63 steps).
- Bail-off held during EMG: indep portion drops out; loco still
  decels via auto-brake alone but more gently.
- Reverser flip at any non-zero speed: ignored; at zero speed:
  takes effect.

### 12.4 Schema-validation tests (`SchemaTest`)

Follows the JMRI `SchemaTest` pattern (see
[`test/jmri/configurexml/SchemaTest.java`](https://github.com/JMRI/JMRI/blob/master/java/test/jmri/configurexml/SchemaTest.java)).
A new `RailDriverFragmentSchemaTest` (or extension of an existing
RailDriver schema test, if one exists) validates every XML
fragment file in two sibling subdirectories beneath the test
package. Each fixture is a stand-alone XML document containing one
fragment with the appropriate `xmlns:rd` and `xsi:schemaLocation`,
so `xmllint`-equivalent in-JVM validation can resolve the schema
directly.

- `valid/` — files that **must** parse against the matching
  fragment XSD. Initial fixtures, split per-fragment:
  - **`<rd:hardwareCalibration>`** (validated against
    `raildriver-hardware-calibration-3.xsd`):
    - `valid/raildriver-hardware-calibration-defaults.xml` — every
      detent at its default byte value.
    - `valid/raildriver-hardware-calibration-captured.xml` —
      representative captured-byte values for each detent.
  - **`<rd:semiRealistic>`** (validated against
    `raildriver-semi-realistic-3.xsd`):
    - `valid/raildriver-semi-realistic-defaults.xml` — every
      `<rd:semiRealistic>` field at its default value.
    - `valid/raildriver-semi-realistic-custom-load.xml` — scenario
      `CUSTOM` with a non-default `<rd:customMultiplier>`.
    - `valid/raildriver-semi-realistic-esu-mode.xml` — decoder-brake
      mode `ESU` with non-default function numbers and thresholds.
    - `valid/raildriver-semi-realistic-air-off.xml` — air simulation
      off (`<rd:airRefreshRateMs>0</rd:airRefreshRateMs>`).
- `invalid/` — files that **must** fail validation. Each file gets
  an XML comment naming the rule it violates so future maintainers
  understand why it lives there. Initial fixtures (all in the
  `<rd:semiRealistic>` fragment unless noted):
  - `invalid/raildriver-semi-realistic-bad-enum.xml` — `<rd:scenario>`
    contains the display string `Light engine` (with space)
    instead of the enum constant `LIGHT_ENGINE`. Catches the
    §9.11 "persist by constant name" rule.
  - `invalid/raildriver-semi-realistic-out-of-range.xml` —
    `<rd:maxBrakePcnt>200</rd:maxBrakePcnt>`. Catches the type's
    documented `[5, 100]` range.
  - `invalid/raildriver-semi-realistic-thresholds-descending.xml`
    — ESU thresholds violate the ascending-order rule from
    §9.10. (Schema can express the per-field range; ascending
    order is enforced in cross-field validation, so this file may
    be a `LoadAndStoreTest` candidate rather than a pure schema
    check — final placement decided when the XSD is written.)
  - `invalid/raildriver-semi-realistic-malformed-int.xml` —
    `<rd:speedStep>two</rd:speedStep>`. Catches the integer parse
    path that should land in `ErrorHandler`.
  - `invalid/raildriver-hardware-calibration-bad-byte.xml` —
    detent value `<rd:forward>999</rd:forward>` (out of byte
    range). Catches the type's documented `[0, 255]` range on
    detent values.

Validation is automatic: the `SchemaTest` parameterised test runs
in-JVM validation (the same approach `jmri.configurexml.SchemaTest`
already uses) over each file in both directories.

### 12.5 Load / store round-trip tests (`LoadAndStoreTest`)

Follows the JMRI `LoadAndStoreTest` pattern (see
[`test/jmri/configurexml/LoadAndStoreTest.java`](https://github.com/JMRI/JMRI/blob/master/java/test/jmri/configurexml/LoadAndStoreTest.java)).
New test class `RailDriverPersistenceLoadAndStoreTest` exercises
the full read → in-memory model → write path through
`AuxiliaryConfiguration`, plus the §9.13 legacy-file migration.
All tests are headless (no GUI windows opened) and use a
temporary `Profile` so the host JMRI install's profiles are not
touched.

Fixtures fall into two groups:

**Group A: fragment round-trip** — stand-alone fragment XML files
are loaded into a temporary profile's `AuxiliaryConfiguration`
(via `putConfigurationFragment`), the in-memory model is read
back via the new `loadHardwareCalibration` /
`loadSemiRealisticSettings` helpers, the model is written back
via the matching save helpers, and the resulting fragment XML is
byte-compared against either the original or a paired `loadref/`
file.

- `load/raildriver-hardware-calibration-defaults.xml` — every
  detent at default values; expected to round-trip byte-identical.
- `load/raildriver-hardware-calibration-captured.xml` — captured
  values; expected to round-trip byte-identical.
- `load/raildriver-semi-realistic-defaults.xml`,
  `load/raildriver-semi-realistic-custom-load.xml`,
  `load/raildriver-semi-realistic-esu-mode.xml` — same content as
  the corresponding `valid/` fixtures; expected to round-trip
  byte-identical.
- `load/raildriver-semi-realistic-malformed-child.xml` — a
  fragment with one unparseable child (e.g.
  `<rd:speedStep>two</rd:speedStep>`). The test asserts that load
  succeeds *and* exactly one error-level `ErrorHandler` event was
  raised for that field. The `loadref` reflects the default value
  substituted on load.

**Group B: legacy-file migration** — each fixture is a
freestanding `<profile>/profile/raildriver-calibration.xml` file
(the legacy v1 / v2 form). The test:

1. Copies the fixture into a temporary profile root.
2. Invokes the migration entry point (§9.13).
3. Asserts that `<rd:hardwareCalibration>` is now present in
   private space and `<rd:semiRealistic>` is present in shared
   space, with detents copied across and `<rd:semiRealistic>` at
   defaults.
4. Asserts the legacy file has been renamed to `*.xml.bak` and
   the new file is gone.
5. For v2 inputs, asserts exactly one warn-level `ErrorHandler`
   event was raised describing the discarded `<semiRealistic>`
   subtree.
6. Re-runs the migration entry point and asserts no further
   filesystem changes occur (idempotency).

- `load/legacy-v1-no-semiRealistic.xml` — a v1 file (no
  `<semiRealistic>` element). Detents migrate; defaults applied to
  the new shared fragment; **no** `ErrorHandler` event.
- `load/legacy-v2-with-discard.xml` — a v2 file with a
  velocity-physics-style `<semiRealistic>` block. Detents
  migrate; warn-level `ErrorHandler` event fires; the new shared
  fragment is at defaults.
- `load/legacy-v2-detents-only.xml` — a v2 file whose
  `<semiRealistic>` is empty. Detents migrate; no event (empty
  subtree is treated as missing).
- `load/legacy-coexists-with-new-fragments.xml` — starting state
  has both the legacy file *and* both new fragments already
  present. Asserts the legacy file is **not** renamed, the new
  fragments are **not** overwritten, and a single warn-level
  `ErrorHandler` event tells the operator the legacy file is
  being ignored.

The test class asserts:

1. Round-trip byte-compares pass for each Group A fixture
   (reading `loadref/<file>` if present, otherwise the original).
2. The captured `ErrorHandler` events for malformed and v2
   inputs contain the expected severity and rule name (see
   §9.11.3 / §9.13). This is the only way to confirm the
   discard / malformed-value reporting actually fires; reading
   the file alone cannot.
3. The resolved `RailDriverHardwareCalibration` and
   `SemiRealisticSettings` records match the expected post-load
   state for every fixture, including the legacy-migration paths.

No schema migration is performed for v2 inputs beyond the
`<semiRealistic>` discard — §9.11 / §9.13 explicitly choose
discard over migration. The test files exist to lock that
contract in place so future code changes cannot silently
re-introduce a lossy migration.

---

## 13. Open design questions for review

1. **Load multiplier computation (§7).** The proposed baseline is the
   named-scenario picker with EngineDriver-derived round-number
   multipliers. Iterate after first review per user request.
2. **Soft-stop button assignment.** Which front-edge button (if any)
   should map to the `THROTTLE_STOP_BRAKE_FULL` sequence?
3. **Air-system simulation off.** Should we expose a session-level
   "Air off" toggle (sets `airLineValue = 100` and freezes the
   repeaters), or rely on the operator parking the Auto Brake at
   Released and ignoring it? The latter is more prototypical; the
   former is the EngineDriver behaviour.
4. **Emit-rate vs decoder DCC update rate.** EngineDriver emits one
   step per Δt, which can be as fast as 300 ms. JMRI's DCC dispatch
   may coalesce successive `setSpeedSetting` calls. If that's a
   problem, we throttle emissions to a min interval (e.g. 50 ms)
   while keeping the underlying step counter accurate. Validate
   during phase 2 hardware testing.
5. **`AuxiliaryConfiguration` namespace and XSD URLs.** §9.11 / §9.12
   pick `http://jmri.org/xml/schema/raildriver/3` as the fragment
   namespace, two XSDs at
   `http://jmri.org/xml/schema/raildriver-hardware-calibration-3.xsd`
   and `http://jmri.org/xml/schema/raildriver-semi-realistic-3.xsd`,
   and a shared/private split (hardware → private, semi-realistic →
   shared). §9.13 migrates the legacy `raildriver-calibration.xml`
   into both fragments. Confirm the namespace shape and the
   `*Xml` reader/writer FQNs to embed in the `<jmri:usingclass>`
   annotations.

---

## 14. Cross-references

- [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md) —
  full EngineDriver algorithm reference.
- [`control-inventory.md`](control-inventory.md) — RailDriver Modern
  Desktop physical control / HID byte mapping used by §4.
- [`semi-realistic-throttle-plan.md`](semi-realistic-throttle-plan.md) —
  superseded velocity-physics plan; kept in repo for history.
- EngineDriver source:
  [`throttle_semi_realistic.java`](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java).
