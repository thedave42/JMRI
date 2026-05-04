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
`SemiRealisticSettings`, `SemiRealisticSettingsPanel`). The first
three are **rewritten** under this plan; the fourth is **retired**
and replaced by an SPI `PreferencesPanel` (§9.5). All RailDriver
classes also relocate from `jmri.util.usb` to `jmri.jmrit.usb`
(§11.0).

| File                             | Today (physics-based)                                             | Under this plan (EngineDriver-aligned)                                                                            |
|----------------------------------|-------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `SemiRealisticThrottleEngine`    | 50 ms tick, Davis A/B/C resistance, m/s² integrator, v_fs → DCC quantisation | Step-rate scheduler: one ±1 step per Δt ms toward `targetSpeed`; Δt = `baseDelay × targetAcceleration`.            |
| `LoadScenario`                   | 12 physics coefficients (top speed, vCorner, drag terms, brake decel, etc.) | Two fields: `displayName` + `loadMultiplier`. Multiplier feeds the EngineDriver `getLoadPcnt` slot directly.       |
| `SemiRealisticSettings`          | ~20 m/s² / m/s / 1/s coefficients                                  | EngineDriver-style integers: speed-step, base accel/decel delay, brake steps, max-brake %, load steps, max-load %. |
| `SemiRealisticSettingsPanel`     | Velocity-coefficient editors hosted in a Debug-menu frame         | **Retired.** Replaced by `RailDriverSemiRealisticPreferencesPanel` (a `jmri.swing.PreferencesPanel` SPI provider) in `jmri.jmrit.usb.swing`. |
| *(new)* `RailDriverPreferencesManager` | n/a                                                          | `jmri.spi.PreferencesManager` SPI provider; owns `liveEnabled` / `persistedEnabled`, the in-memory settings + calibration records, the §9.13 legacy migration, and PCS event dispatch. |
| `RailDriverCalibration`          | `<semiRealistic>` subtree with physics block, freestanding XML file | `<rd:semiRealistic>` (shared) + `<rd:hardwareCalibration>` (private) `AuxiliaryConfiguration` fragments; legacy file migrated and renamed to `*.bak` (§9.13). |
| `RailDriverMenuItem`             | Velocity inputs (`setIndepBrakeFraction`, etc.); also held the persisted-vs-live enable plumbing | EngineDriver-style inputs (`setBrakeSliderStep`, `setAirLineValue`, `setDynBrakeStep`, etc.); enable plumbing moves to `RailDriverPreferencesManager`. |
| `SemiRealisticThrottleEngineTest` | Davis-shape regression cases                                      | EngineDriver-shape cases (delay multiplier matches `getLoadPcnt`, brake clip matches `getBrakeDecimalPcnt`, etc.). |

All RailDriver-hardware code outside the engine — calibration tabs, button
mapping, axis decoding, lifecycle wiring (`AddressListener` /
`ThrottleWindow` plumbing, EDT discipline) — is left functionally
alone, just relocated and adapted to the SPI persistence path. The
**physics math, its inputs/outputs, the package home, and the
Settings-UI delivery path** are what change.

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
| Load slider                       | **Software preset picker** (no physical control) | UI dropdown / calibration setting (§7). The selected scenario's multiplier feeds the same `targetAcceleration ×= loadMultiplier` slot EngineDriver uses. |
| Direction lever                   | **Reverser (#8, byte 0)**            | Three detents: Forward / Neutral / Reverse. Reverser-at-zero-speed-only interlock enforced JMRI-side (matches EngineDriver). |
| Stop button                       | **E-Stop (#2, byte 11)**             | Hard E-Stop only; bypasses the ramp (`setSpeedSetting(-1)`). No soft-stop button is wired — operators stop a moving loco by walking the levers down themselves, which matches real-loco practice.|
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
    private volatile int speedStep;              // current decoder step, 0..126
    private volatile int targetSpeed;            // EngineDriver targetSpeed
    private volatile double targetAcceleration;  // signed Δt multiplier

    // ─── Lever inputs (volatile; written by polling thread) ────────────────
    private volatile int     throttleSliderStep;     // 0..maxThrottle
    private volatile int     dynBrakeStep;           // 0..numberOfBrakeSteps
    private volatile int     indepBrakeStep;         // 0..numberOfBrakeSteps
    private volatile int     airLineValue;           // 0..100, directly from Auto Brake lever
    private volatile int     airReservoirPct;        // 0..100, reservoir-tank fill
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

    // ─── Emit-rate clamp (§5.2) ───────────────────────────────────────────
    private volatile long lastEmitMs;             // monotonic-ms timestamp of last decoder write
    private volatile int  pendingEmitStep = -1;   // -1 = nothing deferred
    private ScheduledFuture<?> deferredEmitTask;

    // ─── Lifecycle ────────────────────────────────────────────────────────
    public void attachThrottle(DccThrottle t);
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

The engine has **no compile-time dependency on `jmri.jmrit.roster`**.
ESU decoder-brake values (function numbers, percent thresholds; see
§6.4) come from the in-memory `SemiRealisticSettings` snapshot only.
There is no per-roster override path, no roster attribute lookup,
and no extensibility hook accepting per-loco values — the engine
reads ESU configuration from `SemiRealisticSettings` directly when
`decoderBrakeMode == ESU`, and short-circuits the entire passthrough
when `decoderBrakeMode == NONE`. This keeps the engine
headless-friendly and free of jmrit type references in its API
surface.

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
    // Throttle decoder-side dispatch to at least settings.minEmitIntervalMs apart
    // (default 50 ms). The internal speedStep advances at full ramp rate; only the
    // setSpeedSetting call to the underlying DCC throttle is gated, so successive
    // ticks faster than the floor coalesce into the most recent value.
    long now = System.nanoTime() / 1_000_000L;
    long sinceLast = now - lastEmitMs;
    if (sinceLast < settings.minEmitIntervalMs) {
        // Reschedule a single deferred emit; subsequent ticks will overwrite
        // pendingEmitStep until the floor elapses.
        pendingEmitStep = newStep;
        if (deferredEmitTask == null) {
            long defer = settings.minEmitIntervalMs - sinceLast;
            deferredEmitTask = exec.schedule(this::flushDeferredEmit,
                                             defer, TimeUnit.MILLISECONDS);
        }
        return;
    }
    lastEmitMs = now;
    final float fraction = newStep / (float) settings.maxThrottleStep;   // 126 default
    ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(fraction));
}

private void flushDeferredEmit() {
    deferredEmitTask = null;
    int step = pendingEmitStep;
    pendingEmitStep = -1;
    if (step >= 0) {
        lastEmitMs = System.nanoTime() / 1_000_000L;
        final float fraction = step / (float) settings.maxThrottleStep;
        ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(fraction));
    }
}
```

**Why a min-interval floor.** The ramp scheduler's Δt can be as
short as `accelRepeatMs × |targetAcceleration|` — down toward 50 ms
at extreme settings. Some DCC backends (LocoNet, XpressNet, SPROG)
throttle or coalesce successive `setSpeedSetting` calls; emitting
faster than a backend will absorb causes the operator-perceived
speed to lag the engine's `speedStep`. The min-interval floor
(default `50 ms`) keeps the *internal* `speedStep` accurate while
clamping decoder-side dispatch to a rate every supported backend
can honour. Faster ramps coalesce into a single emit at the floor
boundary — the operator sees a smooth ramp; the underlying counter
stays honest. Operators who need a different floor for their
backend tune `minEmitIntervalMs` on the Semi-Realistic panel.
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
  Tests that exercise these paths follow the JMRI JUnit-page
  guidance for resetting the one-shot state between tests (see
  the §12 preamble); a unit test that fires `warnOnce` twice without
  resetting will only see the first invocation.
- Every `catch` clause that logs and continues uses the form
  `log.error("<context>: " + ex.getLocalizedMessage(), ex)` so
  both the localised exception text and the full stack trace are
  captured. A bare `log.error("failed", ex)` or
  `log.error(ex.getMessage(), ex)` is **not** acceptable per
  `jmri-logging.instructions.md`.

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
   reservoir in 20 % chunks.

**Disabling the air simulation.** There is **no session-level
"air off" toggle**. Two paths produce that effect, both natural:

- *Prototypical (transient):* park the Auto Brake at Released. The
  lever drives `airLineValue = 100` continuously; the reservoir is
  already topped up; the line repeater has nothing to do. Air sim
  is effectively inert without any UI surface.
- *Persistent:* set `airRefreshRateMs = 0` on the Semi-Realistic
  panel and Save. The reservoir / line repeaters are short-circuited
  on load (§9.6). The Auto Brake lever then sets `airLineValue`
  directly with no recharge dynamics for every session until
  changed.

Both match what real operators do: a real loco has no "air off"
switch, and operators who don't want air feel just don't actuate
the Auto Brake.

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

## 7. Load multiplier

*How* the load multiplier is **used** is fixed: it's the same
`targetAcceleration ×= loadMultiplier` slot EngineDriver uses, applied
unconditionally at the end of `setTargetSpeed`. *How* it's computed is
the **named-scenario picker** below. The computation surface is
deliberately small (one enum + one optional numeric override) so it
stays decoupled from the engine's other inputs.

### 7.1 Named-scenario picker

A small `LoadScenario` enum populates a dropdown on the Semi-Realistic
panel. Each entry carries a fixed multiplier; `CUSTOM` is a sentinel
that delegates to `SemiRealisticSettings.customLoadMultiplier`. The
selected scenario is persisted in the `<rd:semiRealistic>` fragment;
the engine reads `settings.loadScenario.loadMultiplier(settings)`
fresh on every `setTargetSpeed` call.

```java
public enum LoadScenario {
    LIGHT_ENGINE   (1.0),
    SWITCHER       (1.5),
    LOCAL_FREIGHT  (2.5),
    THROUGH_FREIGHT(5.0),
    UNIT_TRAIN    (10.0),
    CUSTOM         (Double.NaN);            // sentinel: read from settings.customLoadMultiplier

    private final double fixedMultiplier;
    LoadScenario(double fixedMultiplier) { this.fixedMultiplier = fixedMultiplier; }

    /** Multiplier applied to {@code targetAcceleration} every {@code setTargetSpeed} call. */
    public double loadMultiplier(SemiRealisticSettings s) {
        return Double.isNaN(fixedMultiplier) ? s.customLoadMultiplier : fixedMultiplier;
    }
}
```

The user-visible display string (`"Light engine"`, `"Switcher"`,
etc.) is **not** a field on the enum. It comes from the panel's
`Bundle.getMessage(...)` lookup keyed by the enum constant name
(e.g. `LoadScenarioLightEngine`), keeping localisation entirely on
the UI side and the on-disk form a constant name (\u00a79.11.2 / `EnumIoNames`).

`Light engine` (1.0) reproduces EngineDriver's "load slider at zero"
behaviour exactly. The numeric values mirror the upper end of
EngineDriver's quadratic `getLoadPcnt` curve sampled at evenly spaced
slider positions (`maxLoad = 1000`):

```
EngineDriver step / 5  →  ((step² × 900) + 100·25) / (100·25)
0/5 → 1.00     1/5 → 1.36     2/5 → 2.44
3/5 → 4.24     4/5 → 6.76     5/5 → 10.00
```

The named values 1.5 / 2.5 / 5.0 / 10.0 round those samples to nicer
numbers — the curve is preserved qualitatively but the operator sees
memorable multipliers in the UI. The `Custom` slot lets an operator
override the named value with any real in `[0.1, 100.0]` without
adding a new enum constant.

**Display strings (`"Light engine"`, etc.) are localisation keys**
resolved through the panel's `Bundle.getMessage(...)` lookup; they are
never persisted (the on-disk form is the constant name `LIGHT_ENGINE`,
per `EnumIoNames` — see §9.11.2). The UI treats `Custom` as a special
case: selecting it enables the **Custom load multiplier** numeric
field; selecting any other scenario sets `customLoadMultiplier` to
that scenario's value and disables the field.

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
On RailDriver only the hard E-Stop is wired:

- **E-Stop SPDT (#2):** hard E-Stop unconditionally. Bypasses the
  ramp; calls `throttle.setSpeedSetting(-1f)`. Same as today.

No "soft stop" button (EngineDriver's `THROTTLE_STOP_BRAKE_FULL`
mode) is implemented. Operators wishing to stop a moving loco walk
the throttle to idle and the brake to full themselves — which is
what they would do on a real loco anyway, and the lever positions
immediately reflect the operator's intent without an unwind step.

---

## 9. Settings, persistence & calibration UI

This section reworks the existing plan's §2.3 / §2.4 / §2.5 around
the JMRI SPI: the persisted-vs-live enable flags, the dirty-tracking
model, and the cross-field validation rules survive, but the bespoke
`RailDriverSettingsFrame` is retired. The Settings UI now ships as
two `jmri.swing.PreferencesPanel` providers grouped under "RailDriver"
in the standard JMRI Preferences window (§9.5), and the
persisted-vs-live state moves onto a new
`jmri.spi.PreferencesManager` provider, `RailDriverPreferencesManager`
(§9.4 / §11.0). The Jynstrument toolbar toggle (§10) is unchanged.

### 9.1 Two enable flags: `persistedEnabled` vs `liveEnabled`

`RailDriverPreferencesManager` (§9.4) tracks the semi-realistic mode
toggle as **two separate values**:

- **`persistedEnabled`** — the value most recently loaded from the
  `<rd:semiRealistic>` fragment (or default OFF when the fragment is
  absent), and the value most recently written by the
  Semi-Realistic `PreferencesPanel`'s `savePreferences()`. The panel's
  `Enable semi-realistic mode` checkbox displays and edits this value.
- **`liveEnabled`** — the value the engine and the Jynstrument act
  on. Initialised from `persistedEnabled` when the manager initialises
  the profile and any time persistence is reloaded. Mutated either by
  the panel's `savePreferences()` (which also updates
  `persistedEnabled`) or by the Jynstrument click (which mutates only
  `liveEnabled`).

The Semi-Realistic panel and the Jynstrument therefore display
**different values** when the operator has used the Jynstrument
session-toggle since the last Save: the panel shows what's on disk
(what would load at next launch); the Jynstrument shows what the
engine is actually doing right now. This is by design — the panel
is the persisted-state view, the Jynstrument is the session-state
view.

### 9.2 Mutation paths and persistence semantics

- **Semi-Realistic panel `savePreferences()`** (triggered by the
  Preferences-window Save or Apply button): writes the
  `<rd:semiRealistic>` fragment via
  `RailDriverPreferencesManager`, then sets
  `liveEnabled = persistedEnabled` and notifies the engine. Cancel
  / discard from the Preferences window invokes the panel's
  `restoreState()` and leaves both fields unchanged.
- **Jynstrument toolbar click:** flips `liveEnabled` only via
  `RailDriverPreferencesManager.setSemiRealisticEnabledSessionOnly(...)`.
  Does **not** touch `persistedEnabled`, does **not** write to disk,
  does **not** update the panel's checkbox. Resets to
  `persistedEnabled` at next JMRI launch.

If the operator wants a session-level Jynstrument change to become
the new persisted default, they open the JMRI Preferences window,
flip the Semi-Realistic panel's `Enable semi-realistic mode`
checkbox to match, and Save.

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

### 9.4 API entry points (on `RailDriverPreferencesManager`)

The `RailDriverPreferencesManager` SPI provider (§11.0) exposes the
two persistence-distinguished setters that the Settings panel and
the Jynstrument call into:

```java
// Acquired via InstanceManager.getDefault(RailDriverPreferencesManager.class).

// PreferencesPanel.savePreferences() path: writes XML, then liveEnabled := persistedEnabled.
public void applyPersistedEnabled(boolean enabled);

// Jynstrument toolbar click: mutates liveEnabled only.
public void setSemiRealisticEnabledSessionOnly(boolean enabled);
```

Both setters perform the engine handover (§9.3) when `liveEnabled`
actually changes; they only differ on whether they touch
`persistedEnabled` and the XML.

Two distinct PCS events are fired on the manager's settings
listener:

- `"persistedEnabledChanged"` — `RailDriverSemiRealisticPreferencesPanel`
  subscribes (so its `Enable semi-realistic mode` checkbox reflects
  the latest persisted state); the Jynstrument ignores it.
- `"liveEnabledChanged"` — the engine and the Jynstrument subscribe;
  the PreferencesPanel ignores it (so a session-only Jynstrument
  toggle does not silently appear as a "dirty" edit in the panel).

The engine, the Jynstrument, the two PreferencesPanels, and
`RailDriverMenuItem` all consume the manager via
`InstanceManager.getDefault(RailDriverPreferencesManager.class)`;
`RailDriverMenuItem` itself no longer carries the persisted-vs-live
state. The manager registers as the well-known instance via
`InstanceManagerAutoDefault` so the first lookup creates it.

### 9.5 Settings UI as `PreferencesPanel` SPI providers

The bespoke single-window `RailDriverSettingsFrame` (with a
`JTabbedPane` and Save/Apply/Cancel button bar) is **retired**.
The Settings UI ships instead as **two `jmri.swing.PreferencesPanel`
implementations**, both registered with
`@ServiceProvider(service = jmri.swing.PreferencesPanel.class)`:

| Class                                          | Role                                                  | Group / display title                          |
|------------------------------------------------|-------------------------------------------------------|-------------------------------------------------|
| `RailDriverSemiRealisticPreferencesPanel`      | Operator-feel preferences (§9.6).                     | Group "RailDriver"; tab title "Semi-Realistic". |
| `RailDriverCalibrationPreferencesPanel`        | Per-machine HID calibration (existing visual-bar UI). | Group "RailDriver"; tab title "Calibration".   |

Both panels return `"RailDriver"` from their `getPreferencesItem()`
method so the standard JMRI Preferences window groups them onto a
single top-level "RailDriver" entry with a sub-tabbed view. The
existing Debug-menu wiring (`RailDriverSettingsAction` /
`RailDriverSettingsFrame` / `RailDriverCalibrationAction`) is
removed; operators reach both panels through **JMRI Preferences →
RailDriver**, plus the toolbar Jynstrument shortcut described in
§10.

Why this matters in practice:

- **Save / Apply / Cancel are owned by the JMRI Preferences window.**
  The panels do not draw their own button bar. JMRI's framework
  calls `savePreferences()` on each `PreferencesPanel` whose
  `isDirty()` is true when the user clicks Save or Apply, and calls
  the panel's reset path on Cancel. The cross-field validation rules
  from §9.10 still apply, just invoked at `savePreferences()` time
  rather than from a custom button handler.
- **Two panels persist independently.** The semi-realistic
  preferences land in the shared `<rd:semiRealistic>` fragment;
  the calibration data lands in the private `<rd:hardwareCalibration>`
  fragment (see §9.11). Each panel's `savePreferences()` calls into
  `RailDriverPreferencesManager.saveSemiRealisticSettings(...)` /
  `saveHardwareCalibration(...)` rather than rolling its own XML I/O.
- **`PreferencesPanel` lifecycle.** Each panel's constructor only
  builds the layout; cross-component wiring (engine PCS listeners,
  capture-button bindings on the calibration panel, dirty-state
  notifications) happens in `initComponents()` and is torn down in
  `dispose()`. JMRI's Preferences window may construct and destroy
  panels multiple times across a session, so listener registration
  must be symmetric or the engine + manager will accumulate
  subscriptions.
- **No `JmriJOptionPane` Cancel prompt.** The standard Preferences
  window already handles "discard unsaved changes" prompts. The
  bespoke `JmriJOptionPane`-based Cancel prompt from the prior
  design is no longer needed; remove it.
- **Layout.** Per `jmri-swing.instructions.md`, both panels use
  [`jmri.util.swing.WrapLayout`](https://www.jmri.org/JavaDoc/doc/jmri/util/swing/WrapLayout.html)
  for any wrapping rows of controls (`FlowLayout` does not display
  the second row when contents wrap). The Calibration panel's
  existing visual-bar layout is unchanged.

### 9.6 Semi-Realistic panel content (EngineDriver-aligned)

`RailDriverSemiRealisticPreferencesPanel` is grouped into seven
sections matching the algorithm's input categories so the operator
sees what they're tuning:

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

**Decoder dispatch**
- **Min emit interval (ms):** integer 10..1000, default 50. Floor
  on how frequently the engine writes `setSpeedSetting` to the
  underlying DCC throttle (§5.2). The internal `speedStep` always
  advances at full ramp rate; this only clamps decoder-side
  dispatch so backends that coalesce or rate-limit (LocoNet,
  XpressNet, SPROG) stay in sync with the engine's view.

**Load** *(see §7)*
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

**Reset to defaults** button — panel-scoped. Restores only the
semi-realistic fields to the active scenario's defaults. Does not
touch the Calibration panel's per-axis byte values.

### 9.7 Scenario defaults

Per the user requirement that *only* the load multiplier varies
across scenarios in this plan, scenarios are a **single-column
preset** rather than the existing plan's full coefficient sheet.
Switching scenarios changes `customLoadMultiplier` and nothing
else. Operators who want different ramp / brake / air feel between
scenarios continue to do that by manually editing the
non-scenario fields and saving — the Semi-Realistic panel is the
source of truth for everything except the load multiplier.

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

### 9.8 Save / Apply via the JMRI Preferences framework

Save / Apply / Cancel come from the **standard JMRI Preferences
window**, which calls into each registered `PreferencesPanel`
through the `jmri.swing.PreferencesPanel` interface. There is no
bespoke button bar.

The framework's flow per `Save` / `Apply` click:

1. The framework asks each registered panel `isDirty()`.
2. For each dirty panel, the framework calls `savePreferences()`.
3. `savePreferences()` performs the panel's per-field validation
   plus the §9.10 cross-field rules, hands the validated record to
   `RailDriverPreferencesManager` (which writes the appropriate
   `<rd:semiRealistic>` or `<rd:hardwareCalibration>` fragment via
   `AuxiliaryConfiguration`), then calls
   `restoreState()` on its own controls so they reflect what was
   just saved (this also clears the panel's dirty flag).
4. The manager fires `"persistedEnabledChanged"` and (where
   appropriate) `"liveEnabledChanged"` PCS events, letting the
   engine and the Jynstrument react without a restart.
5. Save dismisses the Preferences window; Apply leaves it open.

Validation failures: `savePreferences()` reports a localised error
string back through the standard `PreferencesPanel` mechanism;
JMRI's framework surfaces it next to the offending field and keeps
the window open, with the panel's dirty flag still set. The
specific blocking and warning rules are listed in §9.10.

### 9.9 Dirty tracking

Each `PreferencesPanel` maintains its own `dirty` boolean and
listener set. The standard `PreferencesPanel` interface contract
applies:

- Each input control (`JTextField` document listener, `JCheckBox`
  action listener, `JComboBox` action listener, `JSpinner` change
  listener, capture-button presses on the calibration panel,
  per-section / per-panel Reset buttons) calls `markDirty()`.
- `markDirty()` flips the private `dirty` boolean if it wasn't
  already true and notifies the listener set.
- The framework polls `isDirty()` to drive its own Save / Apply
  enable state. After a successful `savePreferences()`, the panel
  calls its internal `restoreState(...)` which clears `dirty` and
  notifies listeners.

**Capture buttons (Calibration panel) mark dirty.** A capture-button
press writes the live byte into the working calibration's detent;
that's a value change and the panel goes dirty.

The **Reset to defaults** button on the Semi-Realistic panel
marks dirty even if it brings every value back to the saved
default (because the framework cannot tell the difference until
`isDirty()` returns false on the next observation, and the
operator's intent was an explicit reset).

### 9.10 Validation rules

Validation runs in two places per `savePreferences()`: per-field
and cross-field. Both sets are identical to (and shared with) the
load-time validation that the persistence layer applies in
§9.11.3.

**Blocking rules** (panel reports error; framework keeps
window open and the panel dirty):

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
- `10 ≤ minEmitIntervalMs ≤ 1000`

**Non-blocking warnings** (after a successful save the panel
surfaces them through JMRI's standard `getPreferencesTooltip()`
or a status `JLabel` so the operator sees the heads-up):

- `decelRepeatMs < accelRepeatMs` — counter-prototype; usually a
  mistake but allowed.
- `speedStep × maxThrottleStep / accelRepeatMs > 1.0` — full-range
  ramp in less than one second; will feel arcade-like.
- `airRefreshRateMs == 0` and `decoderBrakeMode != NONE` — decoder
  brake still works but the air half of the simulation is off;
  surface as a heads-up.
- `accelRepeatMs < minEmitIntervalMs` or
  `decelRepeatMs < minEmitIntervalMs` — the ramp scheduler will
  call `emit` faster than the floor allows, so successive ticks
  coalesce on the wire. Engine state stays accurate but the
  decoder will see fewer steps than the scheduler computes.

Both blocking and warning rules are also evaluated by
`RailDriverPreferencesManager` when it loads a fragment from
disk; a malformed value or out-of-range entry is replaced with the
schema default and reported via `ErrorHandler` (see §9.11.3).

### 9.11 Persistence: profile-aware `AuxiliaryConfiguration` fragments

The `jmri-preferences.instructions.md` guidance is to persist
operator preferences through `jmri.profile.AuxiliaryConfiguration`
(via `jmri.profile.ProfileUtils`) rather than a self-managed
freestanding XML file, so multiple JMRI managers cannot clobber each
other and so settings benefit from JMRI's existing
shared-vs-private-profile split. The current `RailDriverCalibration`
class writes a freestanding
`<profile-root>/profile/raildriver-calibration.xml` — where the
literal `profile/` segment is the `jmri.profile.Profile.PROFILE`
subdirectory under the profile root (the same sibling of the
`jmri-<UUID>-<ID>` private subfolders that holds shared profile
content) — which pre-dates the rest of this plan; this section
migrates that to the `AuxiliaryConfiguration` API.

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
                        http://jmri.org/xml/schema/raildriver/raildriver-hardware-calibration-3.xsd">
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
                        http://jmri.org/xml/schema/raildriver/raildriver-semi-realistic-3.xsd">
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

    <!-- Decoder dispatch -->
    <rd:minEmitIntervalMs>50</rd:minEmitIntervalMs>

    <!-- Load -->
    <rd:load>
        <!--
          Persisted as the LoadScenario enum constant name (e.g.
          LIGHT_ENGINE / SWITCHER / LOCAL_FREIGHT / THROUGH_FREIGHT /
          UNIT_TRAIN / CUSTOM). The display strings ("Light engine",
          etc.) live in the UI bundle only; they must not appear in
          the XML. Read/written via `AbstractXmlAdapter.EnumIoNames`
          so the JMRI ErrorHandler is consulted on malformed values
          rather than silent coercion.
        -->
        <rd:scenario>LIGHT_ENGINE</rd:scenario>
        <rd:customMultiplier>1.0</rd:customMultiplier>
    </rd:load>

    <!-- Decoder integration -->
    <rd:decoderBrake>
        <rd:mode>NONE</rd:mode>
        <rd:esuLowFunction>4</rd:esuLowFunction>
        <rd:esuMidFunction>5</rd:esuMidFunction>
        <rd:esuHighFunction>6</rd:esuHighFunction>
        <rd:esuLowThresh>30</rd:esuLowThresh>
        <rd:esuMidThresh>60</rd:esuMidThresh>
        <rd:esuHighThresh>98</rd:esuHighThresh>
    </rd:decoderBrake>
</rd:semiRealistic>
```

`<rd:mode>` carries the `DecoderBrakeMode` enum constant name
(`NONE` / `ESU`) so `AbstractXmlAdapter.EnumIoNames` round-trips it
through `Enum.name()` without a case-mapping coercion. The matching
`LoadScenario` field uses the same convention
(e.g. `<rd:scenario>LIGHT_ENGINE</rd:scenario>`).

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

    // Decoder dispatch (§5.2)
    public int     minEmitIntervalMs       = 50;       // floor on decoder-side setSpeedSetting calls

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

- `xml/schema/raildriver/raildriver-hardware-calibration-3.xsd` — schema for
  the `<rd:hardwareCalibration>` fragment (§9.11.1).
- `xml/schema/raildriver/raildriver-semi-realistic-3.xsd` — schema for the
  `<rd:semiRealistic>` fragment (§9.11.2).

The per-fragment work breaks down as:

- **Author the XSDs.** Use the **Venetian Blinds** pattern — the
  top-level fragment element has a named complex type; inner
  elements (e.g. `<rd:load>`, `<rd:decoderBrake>`) are defined
  anonymously inside that type. Reuse the standard helper types in
  `xml/schema/types/general.xsd` where they apply: in particular
  `trueFalseType` for genuinely boolean fields such as
  `<rd:enabled>`. Enum-valued fields like `<rd:scenario>` and
  `<rd:mode>` are **not** booleans; define an inline
  `xs:simpleType` restricting `xs:string` to the `xs:enumeration`
  of the matching Java enum's constant names (`LIGHT_ENGINE`,
  `SWITCHER`, …; `NONE`, `ESU`) so the schema validates the same
  string form `AbstractXmlAdapter.EnumIoNames` reads/writes.
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
      xml/schema/raildriver/raildriver-hardware-calibration-3.xsd
  xmllint -noout -schema http://www.w3.org/2001/XMLSchema.xsd \
      xml/schema/raildriver/raildriver-semi-realistic-3.xsd
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
  reader/writer:

  ```xml
  <!-- inside raildriver-hardware-calibration-3.xsd -->
  <xs:annotation>
      <xs:appinfo>
          <jmri:usingclass configurexml="true"
              >jmri.jmrit.usb.configurexml.RailDriverHardwareCalibrationXml</jmri:usingclass>
      </xs:appinfo>
  </xs:annotation>

  <!-- inside raildriver-semi-realistic-3.xsd -->
  <xs:annotation>
      <xs:appinfo>
          <jmri:usingclass configurexml="true"
              >jmri.jmrit.usb.configurexml.SemiRealisticSettingsXml</jmri:usingclass>
      </xs:appinfo>
  </xs:annotation>
  ```

  Both FQNs match the post-relocation package layout in §11.0
  (`jmri.jmrit.usb.configurexml`, not the historical
  `jmri.util.usb.*` or any speculative `jmri.jmrix.raildriver.*`).
- **Schema-filename suffix.** XSD filenames are suffixed with the
  **fragment-namespace generation** (`-3`), matching the trailing
  segment of the namespace URL
  (`http://jmri.org/xml/schema/raildriver/3`). This deliberately
  diverges from the JMRI release-suffix convention
  (`layout-2-9-6.xsd`, `turnouts-3-7-3.xsd`) for two reasons:

  - Both fragments are introduced together in a brand-new
    namespace, so there is no prior schema to compare against
    when picking a release-style suffix.
  - Filename and namespace stay lockstep: a future breaking
    change bumps both together (`-4` filename + `/4` namespace),
    so a quick glance at either tells you the format generation.
    A release-style suffix would drift relative to the namespace
    over time — e.g. a `-5-15-6.xsd` paired with `/3` namespace
    would invite confusion about which version is authoritative.

  The unsuffixed `raildriver-hardware-calibration.xsd` /
  `raildriver-semi-realistic.xsd` form is reserved per the
  convention for a primordial pre-namespaced predecessor that
  does not exist for this fragment family.
- **Future breaking changes** get a new namespace (`/4`, `/5`, …)
  plus a fresh pair of XSDs with the matching `-4` / `-5` suffix;
  older fixtures stay valid forever because their namespaces
  never change.

### 9.13 Migration from the legacy freestanding XML file

The current `RailDriverCalibration.loadOrDefault(File)` /
`RailDriverCalibration.save(File)` pair writes a single freestanding
`<profile-root>/profile/raildriver-calibration.xml` (root element
`<raildriver-calibration version="2">`); the literal `profile/`
segment is the `Profile.PROFILE` subdirectory inside the profile
root, where shared profile content lives alongside the per-node
`jmri-<UUID>-<ID>` private subfolders. Operators with existing v1 /
v2 files therefore have content on disk that must be moved into the
two new `AuxiliaryConfiguration` fragments without losing their
captured detents.

**Path resolution.** All `java.io.File` / `java.nio` operations on
the legacy file go through `jmri.util.FileUtil.getExternalFilename(...)`
first (or the existing `RailDriverCalibration.getDefaultFile()`
helper, which already builds the absolute path from
`Profile.getPath()` and never persists a portable `profile:` string).
If any new code path stores or compares this path elsewhere, persist
it in the portable form `profile:profile/raildriver-calibration.xml`
and resolve via `FileUtil.getExternalFilename(...)` before use, per
`jmri-filenames.instructions.md`.

Migration runs once per profile, the first time a JMRI build with
the new code initialises that profile:

1. **Detect.** On startup, the calibration loader checks both:
   - whether the new `<rd:hardwareCalibration>` fragment exists in
     `getAuxiliaryConfiguration(profile)` (private space), and
   - whether the legacy file
     `<profile-root>/profile/raildriver-calibration.xml` exists.
2. **If only the legacy file exists**, parse it with the existing
   v1 / v2-tolerant JDOM2 path (already in
   `RailDriverCalibration.loadOrDefault`):
   - Copy every captured detent from the legacy file into a fresh
     `<rd:hardwareCalibration>` fragment and write that fragment to
     `getAuxiliaryConfiguration(profile)` in **private** space.
   - The legacy file's `<semiRealistic>` subtree (only present in
     v2 files) is **mostly discarded**: the meaning of every
     numeric field changed under the EngineDriver-aligned port
     (m/s² / m/s drag coefficients → integer milliseconds and step
     counts), so a silent numeric copy would produce nonsense
     values. Per `jmri-xml.instructions.md` ("backward
     compatibility ... prefer additive changes") schema-versioned
     migration is preferred where possible, so the loader
     **does** carry across the small set of v2 fields whose
     meaning survives unchanged before applying defaults to the
     rest:

     | v2 element                  | v3 element       | Mapping                                 |
     |-----------------------------|------------------|------------------------------------------|
     | `<enabled>`                 | `<rd:enabled>`   | Boolean copy (`true`/`false`).           |
     | (none)                      | every other v3 field | Schema default applied; v2 value ignored. |

     Any v2 child not in the table above is ignored. The loader
     then writes the populated `<rd:semiRealistic>` fragment to
     **shared** space and emits one warn-level `ErrorHandler`
     report **plus** a `log.warn(...)` call summarising which
     fields were carried (`enabled`) and which were reset to
     defaults (the rest), so the operator knows their tuning was
     lost.
   - Rename the legacy file to
     `<profile-root>/profile/raildriver-calibration.xml.bak` (do
     **not** delete it; the operator may want it for forensic
     purposes). Log an `INFO`-level message identifying the
     legacy file path, the new fragment locations, and the `.bak`
     filename.
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

The Jynstrument is mode-toggle UI only; its behaviour is independent
of which engine algorithm is running underneath.

The Semi-Realistic `PreferencesPanel`'s `Enable semi-realistic mode`
checkbox (§9.6) is the authoritative persistent toggle, but reaching
it through JMRI Preferences is too much friction for a setting an
operator might flip multiple times per session (e.g. switching between
yard-switching mode and over-the-road mode). A second, faster path
lives directly on the throttle window's toolbar.

### 10.1 What the Jynstrument exposes

A single toolbar button:

- **Icon** — green when semi-realistic mode is ON, grey when OFF,
  plus a transient "binding…" icon during the State 2 → State 4
  handover. Three PNGs ship with the `.jyn` folder.
- **Click** — toggles `liveEnabled` **for the current session
  only**; does **not** write the calibration XML. Notifies the
  engine + Semi-Realistic `PreferencesPanel` via PCS so all three
  stay coherent in memory. Persistent changes go through the JMRI
  Preferences window (§9).
- **Right-click** — popup with one item: `Settings...` → opens the
  JMRI Preferences window on the Semi-Realistic panel.
- **Tooltip** — `RailDriver semi-realistic throttle: ON / OFF (session)`.

### 10.2 Java-side support

The persisted-vs-session enable plumbing lives on
`RailDriverPreferencesManager` (§9.4); the
attach-state plumbing lives on `RailDriverMenuItem`. The
Jynstrument talks to both:

```java
// RailDriverPreferencesManager — InstanceManager.getDefault(...)
public boolean isSemiRealisticLiveEnabled();
public boolean isSemiRealisticPersistedEnabled();
public void applyPersistedEnabled(boolean enabled);             // §9.4
public void setSemiRealisticEnabledSessionOnly(boolean enabled); // §9.4
public void addSettingsListener(PropertyChangeListener l);
public void removeSettingsListener(PropertyChangeListener l);

// RailDriverMenuItem — InstanceManager.getNullableDefault(...)
public boolean isRailDriverConnected();
public ThrottleFrame getActiveThrottleFrame();
public boolean isAttachInProgress();
public void requestAttachToThrottle(ThrottleFrame tf);
public void addAttachStateListener(PropertyChangeListener l);
public void removeAttachStateListener(PropertyChangeListener l);
```

`RailDriverMenuItem` lives in `jmri.jmrit.usb` post-relocation
(§11.0), so referencing `jmri.jmrit.throttle.ThrottleFrame`
directly in its public API is legal — no narrow-interface
abstraction is required.

PCS events:

- `"liveEnabledChanged"` (fired on the manager's settings
  listener) — fired by both `applyPersistedEnabled` and
  `setSemiRealisticEnabledSessionOnly`. Engine + Jynstrument
  subscribe.
- `"persistedEnabledChanged"` (manager) — fired by
  `applyPersistedEnabled` only, after the XML write. The
  Semi-Realistic `PreferencesPanel` subscribes.
- `"railDriverConnected"` (RailDriverMenuItem) — fired from the
  existing `HidServicesListener` callbacks.
- `"activeThrottleFrame"` (RailDriverMenuItem) — fired around
  `attachThrottleWindow` binds.
- `"attachInProgress"` (RailDriverMenuItem) — fired around the
  async attach window.

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

All new and existing RailDriver code lives under **`jmri.jmrit.usb`**.
The pre-existing classes are relocated from `jmri.util.usb` as part
of Phase 2; no new code is added under `jmri.util.usb`. This is a
**deliberate departure from the historical placement**, motivated by
two structural constraints from `jmri-structure.instructions.md`:

- The `util` tree is reserved for "general service classes that are
  not user-level tools". The RailDriver feature is a user-level tool
  (Settings UI, Jynstrument toolbar toggle, Debug-menu calibration
  window, scenario picker), so `jmrit` is the correct home.
- `util` code "must not depend on `jmri.jmrit` or `jmri.jmrix`". The
  engine and `RailDriverMenuItem` integrate with
  `jmri.jmrit.throttle.ThrottleFrame` (Jynstrument auto-install,
  active-frame tracking). Under `jmrit` those references are
  legal; under `util` they would fail the ArchUnit checks
  (`jmri.ArchitectureCheck` / `jmri.ArchitectureTest`).

The RailDriver does **not** become a `jmri.jmrix.<vendor>` system
connection: it is a USB-HID peripheral that drives an existing
`DccThrottle` rather than implementing its own layout connection,
so it has no `SystemConnectionMemo`, no `ConnectionTypeList`, and no
`PortAdapter` / `ConnectionConfig` chain. Future maintainers should
not retrofit one.

Concrete package locations under this plan:

| Class                                                           | Package                                  | Notes                                                     |
|-----------------------------------------------------------------|------------------------------------------|------------------------------------------------------------|
| `SemiRealisticThrottleEngine`, `SemiRealisticSettings`, `LoadScenario`, `DecoderBrakeMode`, `RailDriverHardwareCalibration` | `jmri.jmrit.usb` *(relocated)* | Engine + settings POJOs; **no Swing imports**. ESU configuration is read from `SemiRealisticSettings` only — there is no per-roster override path. |
| `RailDriverCalibration` (legacy facade + new helpers)           | `jmri.jmrit.usb` *(relocated)*            | Existing class; new `loadHardware*` / `loadSemiRealistic*` helpers stay non-Swing. |
| `RailDriverPreferencesManager`                                  | `jmri.jmrit.usb` *(new)*                  | `jmri.spi.PreferencesManager` SPI provider. Owns `liveEnabled` / `persistedEnabled`, the in-memory `SemiRealisticSettings` and `RailDriverHardwareCalibration`, the §9.13 legacy migration, and PCS event dispatch. Acquired via `InstanceManager.getDefault(RailDriverPreferencesManager.class)`. |
| `RailDriverMenuItem`                                            | `jmri.jmrit.usb` *(relocated)*            | Existing location; the Swing-touching call sites continue to use `ThreadingUtil.runOnGUIEventually`. Public API uses `ThrottleFrame` directly (legal jmrit→jmrit). |
| `RailDriverSemiRealisticPreferencesPanel`, `RailDriverCalibrationPreferencesPanel`, `CalibrationBar` | `jmri.jmrit.usb.swing` *(new)* | All Swing UI. The two panels are `jmri.swing.PreferencesPanel` SPI providers grouped under a "RailDriver" group hint; both appear in the standard JMRI Preferences window (§9.5). |
| `RailDriverHardwareCalibrationXml`, `SemiRealisticSettingsXml`  | `jmri.jmrit.usb.configurexml` *(new)*     | Per-fragment `*Xml` adapters extending `jmri.configurexml.AbstractXmlAdapter`. Own the XML read/write logic for the `AuxiliaryConfiguration` fragments (§9.11). The runtime classes stay free of XML logic. |
| `RailDriverModeToggle.jyn`                                      | `jython/Jynstruments/ThrottleWindowToolBar/` | Distribution content; remains exactly where the Jynstrument loader expects it. |

The two new subpackages (`jmri.jmrit.usb.swing`,
`jmri.jmrit.usb.configurexml`) are created in Phase 2. Phase 2 also
performs the **relocation** of every existing class from
`jmri.util.usb` to `jmri.jmrit.usb`, including `RailDriverCalibration`
and `RailDriverMenuItem`. Existing call sites are updated wholesale;
no compatibility shim is left behind under `jmri.util.usb` because
those classes have no third-party consumers (RailDriver is a
self-contained feature). The pre-existing `SemiRealisticSettingsPanel`,
`RailDriverSettingsFrame`, `RailDriverSettingsAction`, and
`CalibrationTabPanel` classes are **retired** as part of the SPI
migration (§9.5) — their function moves into the two new
`PreferencesPanel` providers and the `RailDriverPreferencesManager`.

**Persistence-class lineage.** Both new `*Xml` adapters
(`RailDriverHardwareCalibrationXml`, `SemiRealisticSettingsXml`)
extend `jmri.configurexml.AbstractXmlAdapter` so they pick up the
standard parsing helpers (`getAttributeIntegerValue`,
`getAttributeBooleanValue`, etc.) and the `EnumIO` infrastructure
(`EnumIoNames` for `LoadScenario` / `DecoderBrakeMode`) used
elsewhere in the plan. Persistence is invoked through
`RailDriverPreferencesManager`'s `load*` / `save*` paths rather
than through `ConfigXmlManager`'s automatic `*Xml` lookup, so the
`a.b.Foo` → `a.b.configurexml.FooXml` naming convention is
followed for human readability rather than for dispatch.

**SPI registration.** Three SPI bindings ship as part of this
feature, all generated automatically from `@ServiceProvider`
annotations into `target/classes/META-INF/services/`:

- `@ServiceProvider(service = jmri.spi.PreferencesManager.class)`
  on `RailDriverPreferencesManager`.
- `@ServiceProvider(service = jmri.swing.PreferencesPanel.class)`
  on `RailDriverSemiRealisticPreferencesPanel` and
  `RailDriverCalibrationPreferencesPanel`.

There is no `StartupActionFactory`, `ConnectionTypeList`, or
`ToolsMenuAction` provider — the operator-facing entry points are
the standard JMRI Preferences window plus the Jynstrument toolbar
toggle.

### Phase 1 — Engine port

- Rewrite `SemiRealisticThrottleEngine` with the §5 class outline.
- Reduce `LoadScenario` to the named-scenario picker per §7.1.
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

### Phase 2 — Relocation, SPI Settings UI, persistence rebuild

- **Relocation (§11.0).** Move every existing class from
  `jmri.util.usb` to `jmri.jmrit.usb`. Update every call site
  wholesale; do not leave compatibility shims under
  `jmri.util.usb`. Add the two new subpackages
  `jmri.jmrit.usb.swing` and `jmri.jmrit.usb.configurexml`. Run
  `jmri.ArchitectureCheck` after the move to confirm no
  cross-tree violations remain.
- **Land §9.1 / §9.2 / §9.4** on the new
  `RailDriverPreferencesManager` (not on `RailDriverMenuItem`):
  - Add `jmri.jmrit.usb.RailDriverPreferencesManager`
    implementing `jmri.spi.PreferencesManager` and registered
    with `@ServiceProvider(service = jmri.spi.PreferencesManager.class)`.
  - Manager owns `persistedEnabled` / `liveEnabled`, the
    in-memory `SemiRealisticSettings` and
    `RailDriverHardwareCalibration` records, the §9.13 legacy
    migration trigger, and the `addSettingsListener` /
    `removeSettingsListener` PCS dispatch.
  - `RailDriverMenuItem` becomes a consumer (acquires the
    manager via `InstanceManager`).
- **SPI Settings UI (§9.5).** Retire `RailDriverSettingsFrame`,
  `RailDriverSettingsAction`, `SemiRealisticSettingsPanel`, and
  `CalibrationTabPanel`. Remove the Debug-menu wiring entirely
  (no `JmriNamedPaneAction`). Replace with two
  `jmri.swing.PreferencesPanel` SPI providers in
  `jmri.jmrit.usb.swing`:
  - `RailDriverSemiRealisticPreferencesPanel` — content per
    §9.6, scenario picker per §9.7, `Bundle.getMessage(...)` for
    every visible label, `WrapLayout` for any wrapping rows.
  - `RailDriverCalibrationPreferencesPanel` — wraps the existing
    visual-bar UI (`CalibrationBar`) with no functional change.
  - Both classes annotated
    `@ServiceProvider(service = jmri.swing.PreferencesPanel.class)`,
    return `"RailDriver"` from `getPreferencesItem()` so they
    group together in the JMRI Preferences window, and give
    distinct tab titles via `getTabbedPreferencesTitle()` (e.g.
    `Bundle.getMessage("PreferencesTabSemiRealistic")` /
    `…("PreferencesTabCalibration")`).
  - Each panel implements its own `isDirty()` /
    `markDirty()` plumbing (§9.9) and runs the §9.10 validation
    inside `savePreferences()` before delegating to the
    `RailDriverPreferencesManager` save helpers.
- **Migrate persistence to `AuxiliaryConfiguration`** (§9.11):
  - Add a `RailDriverHardwareCalibration` type holding the seven
    detent records, separate from `SemiRealisticSettings`.
  - Implementation in
    `jmri.jmrit.usb.configurexml.RailDriverHardwareCalibrationXml`
    and `jmri.jmrit.usb.configurexml.SemiRealisticSettingsXml`,
    both extending `jmri.configurexml.AbstractXmlAdapter`.
  - Expose `loadHardwareCalibration` / `saveHardwareCalibration`
    (private space) and `loadSemiRealisticSettings` /
    `saveSemiRealisticSettings` (shared space) on
    `RailDriverPreferencesManager`, all using
    `ProfileUtils.getAuxiliaryConfiguration(profile)` per the
    `StartupActionsManager` precedent.
  - Replace every existing call site of
    `RailDriverCalibration.loadOrDefault(getDefaultFile())` and
    `cal.save(getDefaultFile())` with the manager's new pair.
  - Route the v3 fragment load — plus the §9.13 legacy
    `<semiRealistic>` discard — through `ErrorHandler` per
    §9.11.3.
- **Implement §9.13 legacy-file migration** inside
  `RailDriverPreferencesManager.initialize(...)`. If
  `<rd:hardwareCalibration>` is missing **and**
  `<profile-root>/profile/raildriver-calibration.xml` exists, parse the
  legacy file with the existing v1 / v2 path, populate the two
  new fragments (with the §9.13 best-effort field mapping), and
  rename the legacy file to `*.xml.bak`. Idempotent on
  subsequent runs.
- **XSDs.** Add `xml/schema/raildriver/raildriver-hardware-calibration-3.xsd`
  and `xml/schema/raildriver/raildriver-semi-realistic-3.xsd`. Each follows
  the Venetian-Blinds pattern, reuses shared types from
  `types/general.xsd` (notably `trueFalseType` for
  `<rd:enabled>`), defines inline `xs:enumeration` simpleTypes
  for `<rd:scenario>` and `<rd:mode>` matching the Java enum
  constant names, and is annotated with the responsible `*Xml`
  reader/writer class via `<xs:annotation>`/`<xs:appinfo>`. The
  fragment writer emits the matching `xsi:schemaLocation` on
  each fragment root.
- **Schema validation.** Run
  `xmllint -noout -schema http://www.w3.org/2001/XMLSchema.xsd
  xml/schema/raildriver/raildriver-hardware-calibration-3.xsd` and
  `xml/schema/raildriver/raildriver-semi-realistic-3.xsd`, plus the
  per-fixture `xmllint -noout <fixture>.xml` checks against every
  file under `valid/` / `invalid/` / `load/` (§12.4) before
  commit.
- **`EnumIoNames`-style enum persistence.** Wire `LoadScenario`
  and `DecoderBrakeMode` through `AbstractXmlAdapter.EnumIoNames`
  so the on-disk form is the constant name and bad values land in
  `ErrorHandler`.
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
  protected API on `RailDriverPreferencesManager`, the two
  `PreferencesPanel` classes, the engine, the settings /
  calibration records, and the new `*Xml` adapters before the
  phase ships. Update `RailDriverMenuItem`'s Javadoc to reflect
  its slimmer post-relocation API.
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
- Mode toggle on the Semi-Realistic `PreferencesPanel`
  (`decoderBrakeMode`) gates the entire passthrough.

### Phase 6 — Throttle-toolbar Jynstrument

- Ship `RailDriverModeToggle.jyn` (§10).
- Implement the five-state click logic and PCS event subscriptions
  in the Jython class.
- `RailDriverMenuItem.attachThrottleWindow()` auto-installs the
  toggle on first bind via `hasJynstrumentInstalled`.
- **Javadoc (§12.6).** Document the new public Jynstrument-support
  API on `RailDriverMenuItem` (event names, state semantics).
- **Help page (§12.6).** Write
  `help/en/html/tools/usb/RailDriverModeToggle.shtml` covering the
  five Jynstrument states and persistent-vs-session semantics.

---

## 12. Testing strategy

**Cross-cutting: `warnOnce` / `infoOnce` in tests.** Several code
paths in this design emit one-shot warnings via
`Log4JUtil.warnOnce` / `Log4JUtil.infoOnce` (see §5.6 — bail-off
without calibration, Auto Brake out of range, malformed-fragment
load, v2 discard, etc.). Per `jmri-logging.instructions.md`, these
need special handling in unit and CI tests: the one-shot state is
shared per-class for the lifetime of the JVM, so a second test
invocation in the same run will see the message suppressed and any
assertion that "the warning fired again" will mistakenly fail.
Tests that exercise such paths follow the JMRI JUnit-page guidance
for resetting the one-shot state between tests (typically by
calling `Log4JUtil.restartLogging()` in a `@BeforeEach` or by
asserting the suppression behaviour rather than the second
emission). Schema / load-and-store fixtures that intentionally
trigger these warnings (`load/raildriver-semi-realistic-malformed-child.xml`
in §12.5; the v2 discard in §12.5 Group B) must use this pattern.

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
freestanding `<profile-root>/profile/raildriver-calibration.xml` file
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

### 12.6 Javadoc and help-page deliverables

Per `.github/instructions/jmri-plugins.instructions.md`, new
functionality should ship with CI unit tests, Javadoc, and help
pages. The first two are covered by §12.1–§12.5; this section
covers Javadoc and help pages explicitly so they don't slip past
the implementation phases.

**Javadoc.** Every new public or protected API gets a class-level
and per-method Javadoc block before the phase that introduces it
ships. Concretely:

- `SemiRealisticThrottleEngine` — class header summarising the
  EngineDriver-aligned algorithm with a link to
  [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md);
  per-method Javadoc on every public lever-input setter, lifecycle
  method, and on `setTargetSpeed` (private but worth documenting
  given its role).
- `SemiRealisticSettings`, `RailDriverHardwareCalibration` — POJO
  field tables describing units, valid ranges, default values,
  and which `<rd:*>` element each persists into.
- `LoadScenario`, `DecoderBrakeMode` — enum constant Javadoc
  pointing at the matching XML form (`name()`) and the user-facing
  display string in the bundle.
- `RailDriverPreferencesManager` — Javadoc on every public method
  (`applyPersistedEnabled`, `setSemiRealisticEnabledSessionOnly`,
  `addSettingsListener` / `removeSettingsListener`, the four
  `load*` / `save*` helpers) plus the SPI lifecycle methods
  (`initialize`, `getRequires`, `isInitialized`). Class header
  documents the PCS event names fired
  (`"persistedEnabledChanged"`, `"liveEnabledChanged"`).
- `RailDriverSemiRealisticPreferencesPanel`,
  `RailDriverCalibrationPreferencesPanel` — Javadoc on the
  `PreferencesPanel` interface methods (`isDirty`,
  `savePreferences`, `getPreferencesItem`,
  `getTabbedPreferencesTitle`, `restoreState`) and on their
  internal `markDirty()` plumbing.
- `RailDriverHardwareCalibrationXml`, `SemiRealisticSettingsXml` —
  Javadoc covering which `AuxiliaryConfiguration` space the
  fragment lives in (private vs shared), the `EnumIoNames`
  helpers used, and the `AbstractXmlAdapter` parsing helpers
  inherited.
- New / changed public methods on `RailDriverMenuItem`
  (`isRailDriverConnected`, `getActiveThrottleFrame`,
  `isAttachInProgress`, `requestAttachToThrottle`,
  `addAttachStateListener` / `removeAttachStateListener`) —
  fully documented with the PCS event names they fire (§10.2).
  The persisted-vs-live enable methods moved to
  `RailDriverPreferencesManager` (above) and are no longer on
  `RailDriverMenuItem`.

The Javadoc tasks are scheduled on the same phase that lands the
class itself (Phase 1 for the engine, Phase 2 for the
settings UI / persistence, Phase 6 for the Jynstrument
support API).

**Help pages.** Two new pages and one update to existing content:

- `help/en/html/tools/usb/RailDriverSemiRealistic.shtml` *(new)*
  — overview of the semi-realistic throttle, the lever mapping
  (§4), the Semi-Realistic panel fields (§9.6), the `Light engine` →
  `Unit train` scenarios, and a short troubleshooting guide
  (e.g. "Loco accelerates immediately when I move the throttle
  → check Step size per ramp tick / acceleration repeat").
- `help/en/html/tools/usb/RailDriverModeToggle.shtml` *(new)*
  — explains the toolbar Jynstrument's five states (§10.3),
  what right-click → Settings does, and the persistent-vs-session
  semantics (§9.1 / §9.2).
- `help/en/html/tools/usb/RailDriverSettings.shtml` *(updated to
  reflect the §9.5 SPI `PreferencesPanel` delivery, the new
  fields, the JMRI Preferences-window Save / Apply / Cancel
  semantics, and the legacy-file migration in §9.13.)*

The two new pages are written in Phase 2 (Settings UI lands) and
Phase 6 (Jynstrument lands) respectively. The updated existing
page is touched as part of Phase 2 alongside the Settings UI
changes.

The Javadoc and help-page work items are surfaced explicitly in
the per-phase bullet lists in §11 so they don't slip.

---

## 13. Open design questions for review

The package placement, SPI integration, and engine cross-tree
coupling questions raised in the original draft are **resolved**:

- All RailDriver code relocates from `jmri.util.usb` to
  `jmri.jmrit.usb` (§11.0). This satisfies the `jmri-structure`
  rules: `jmrit` is the correct home for user-level tools, and
  references to `jmri.jmrit.throttle.ThrottleFrame` /
  `jmri.jmrit.roster.RosterEntry` from `RailDriverMenuItem` /
  caller code are now legal.
- The settings UI ships as two `jmri.swing.PreferencesPanel` SPI
  providers grouped under a "RailDriver" entry in the standard
  JMRI Preferences window (§9.5); `RailDriverSettingsFrame` /
  `RailDriverSettingsAction` are retired.
- Persistence and the `liveEnabled` / `persistedEnabled` plumbing
  live on a `jmri.spi.PreferencesManager` SPI provider,
  `RailDriverPreferencesManager` (§9.4 / §11.0).
- The engine reads ESU configuration from `SemiRealisticSettings`
  only — no per-roster overrides, no `RosterEntry` lookup, no
  `jmrit` types in the engine API surface (§5.1 / §6.4).
- Persistence shape: namespace
  `http://jmri.org/xml/schema/raildriver/3`, two XSDs under
  `xml/schema/raildriver/` suffixed with the matching
  fragment-namespace generation (`-3`), shared
  `<rd:semiRealistic>` and private `<rd:hardwareCalibration>`
  fragments, `<jmri:usingclass>` FQNs in
  `jmri.jmrit.usb.configurexml` (§9.11 / §9.12 / §11.0).

No open design questions remain.

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
