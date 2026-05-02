# Semi-realistic throttle — research report (EngineDriver)

> **Source:** [JMRI/EngineDriver](https://github.com/JMRI/EngineDriver), file
> [`EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java`](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java)
> at SHA `17876ddd` (file most recently touched in commit `5e722d38`, 2026-04-26).
> Parent class: `throttle.java` at SHA `a1891195`.
> Default values from `EngineDriver/src/main/res/values/strings.xml` (SHA
> `d2efbae8`).
>
> Compiled directly from a fresh fetch of the upstream source on
> 2026-05-02. All line numbers below reference these specific blobs.

This report describes how EngineDriver implements its "semi-realistic"
throttle mode, with a focus on:

- the **target-speed / current-speed split** (the throttle does not push
  speed values directly to the decoder; it sets a *target*, and a
  separate timer thread ramps the live speed toward it);
- the **time-per-step delay** that controls the ramp rate, and how
  brakes / air / load modify that delay (or short-circuit it
  entirely);
- the four brake systems that live alongside the throttle slider:
  **Brake slider** (mechanical/decoder brake), **Air reservoir +
  Air line** (Westinghouse-style air-brake simulation), **ESU
  decoder-brake functions** (forwarded to the decoder), and the
  **Load slider** (cargo mass / inertia).

The end of the report cross-walks the EngineDriver model onto the
RailDriver Modern Desktop's physical controls so we can reuse the parts
that fit and identify where the hardware is materially different.

---

## 1. Mental model

EngineDriver's semi-realistic mode replaces JMRI's normal "slider value
= throttle setting" relationship with a **two-tier model**:

```
  ┌──────────────┐           setTargetSpeed()           ┌──────────────┐
  │ Throttle bar │  ──────────────────────────────────▶ │ targetSpeed  │
  │ (slider /    │           computes target +          │  (per-loco)  │
  │  notches)    │           accel sign                 │              │
  └──────────────┘                                      └──────────────┘
                                                               │
                                                               ▼
                                            ┌────────────────────────────────┐
                                            │ SemiRealisticTargetSpeedRpt    │
                                            │ Updater (Handler.postDelayed)  │
                                            │                                │
                                            │  every Δt ms,                   │
                                            │   if currentSpeed != target:   │
                                            │     incrementSpeed(±1 step)    │
                                            │     reschedule itself          │
                                            └────────────────────────────────┘
                                                               │
                                                               ▼
                                                       ┌──────────────┐
                                                       │ DCC throttle │
                                                       │  setSpeed()  │
                                                       └──────────────┘
```

The user manipulates the **target**. The displayed/decoder speed walks
toward that target one step at a time on a `Handler.postDelayed`
schedule. The brake / air / load logic only modifies (a) what the target
becomes and (b) how big the inter-step delay Δt is. The increment itself
is a fixed-size step; the *rate* is what gets brake/load/air shaping.

Per-loco arrays hold the model state (`throttle.java:278-283`):

```java
protected static int[]    targetSpeeds              = {0,0,0,0,0,0};   // per loco, 0..maxThrottle
protected static int[]    prevTargetSpeeds          = {0,0,0,0,0,0};
protected static int[]    prevLoads                 = {0,0,0,0,0,0};
protected static double[] targetAccelerations       = {0,0,0,0,0,0};
                          // sign + magnitude:
                          //   -4   = full brake
                          //   +1   = normal acceleration
                          //    0   = at target, no movement
protected static String[] targetAccelerationsForDisplay = {"", "", "", "", "", ""};
```

`targetAcceleration` is a **multiplier on the inter-step delay**, not a
m/s² figure. Sign tells the runnable whether to step up or down; the
absolute value scales Δt.

Six independent `Handler` instances (one per active loco/throttle) drive
the ramps so multiple locos can ramp in parallel
(`throttle_semi_realistic.java:107-120`):

```java
protected Handler[] semiRealisticTargetSpeedUpdateHandlers = {null,...};
protected Handler[] semiRealisticSpeedButtonUpdateHandlers = {null,...};
protected Handler[] semiRealisticAirUpdateHandlers         = {null,...};
protected Handler[] semiRealisticAirLineUpdateHandlers     = {null,...};
```

---

## 2. The ramp scheduler — `SemiRealisticTargetSpeedRptUpdater`

The inner class that does the actual speed walk
(`throttle_semi_realistic.java:2030-2068`):

```java
protected class SemiRealisticTargetSpeedRptUpdater implements Runnable {
    int whichThrottle;
    int delayMillis;             // signed: + = up, − = down

    protected SemiRealisticTargetSpeedRptUpdater(int myWhichThrottle, int myRepeatDelay) {
        whichThrottle = myWhichThrottle;
        delayMillis   = myRepeatDelay;
    }

    @Override public void run() {
        if (mainapp.appIsFinishing) return;
        if (getSpeed(whichThrottle) == targetSpeeds[whichThrottle]) return;  // arrived

        if (delayMillis > 0) {                       // need to accelerate
            if (getSpeed(whichThrottle) > targetSpeeds[whichThrottle]) {
                // overshot during configuration change -> snap to target, stop
                setSpeed(whichThrottle, targetSpeeds[whichThrottle], speed_commands_from_type.BUTTONS);
                return;
            }
            incrementSpeed(whichThrottle, speed_commands_from_type.BUTTONS,
                           1, prefSemiRealisticThrottleSpeedStep);
            restartSemiRealisticThrottleTargetSpeedRepeater(whichThrottle, delayMillis);
        } else {                                     // delayMillis < 0  ⇒ decelerate
            if (getSpeed(whichThrottle) < targetSpeeds[whichThrottle]) {
                setSpeed(whichThrottle, targetSpeeds[whichThrottle], speed_commands_from_type.BUTTONS);
                return;
            }
            delayMillis = delayMillis * -1;          // make it positive for the next post
            decrementSpeed(whichThrottle, speed_commands_from_type.BUTTONS,
                           1, prefSemiRealisticThrottleSpeedStep);
            restartSemiRealisticThrottleTargetSpeedRepeater(whichThrottle, delayMillis);
        }
    }
}
```

Key points:

- One step per tick, where step size is
  `prefSemiRealisticThrottleSpeedStep` (default `2`, in JMRI 0..126
  speed-step units). With 126 max steps and step=2, full-range acceleration
  takes ~63 ticks.
- The runnable reschedules itself for the *next* step using the same
  `delayMillis` it received. If `setTargetSpeed` is invoked again
  mid-ramp (e.g. brake slider moves), it cancels and reposts with a
  freshly computed Δt:
  ```java
  semiRealisticTargetSpeedUpdateHandlers[whichThrottle].removeCallbacksAndMessages(null);
  restartSemiRealisticThrottleTargetSpeedRepeater(whichThrottle,
      getSemiRealisticTargetSpeedRptDelay(whichThrottle));
  ```
- `delayMillis` carries direction in its **sign** so a single runnable
  handles both up- and down-ramps. The reschedule branch `delayMillis = delayMillis * -1`
  (line 2061) keeps `postDelayed` happy with a positive value while the
  next iteration re-derives sign.

### 2.1 The Δt formula

```java
int getSemiRealisticTargetSpeedRptDelay(int whichThrottle) {
    return getSemiRealisticTargetSpeedRptDelay(whichThrottle, getRepeatDelay(whichThrottle));
}
int getSemiRealisticTargetSpeedRptDelay(int whichThrottle, int repeatDelay) {
    return (int) Math.round(((double) repeatDelay) * getTargetAcceleration(whichThrottle));
}

int getRepeatDelay(int whichThrottle, int myRepeatDelay) {
    int repeatDelay = myRepeatDelay;
    if (repeatDelay == 0) {
        if (mSemiRealisticAutoIncrementOrDecrement[whichThrottle] == auto_increment_or_decrement_type.INCREMENT) {
            repeatDelay = prefSemiRealisticThrottleAccelerationRepeat;   // default 300 ms
        } else {
            repeatDelay = prefSemiRealisticThrottleDecelerationRepeat;   // default 800 ms
        }
    }
    return repeatDelay;
}
```

So:

```
Δt = baseDelay × targetAcceleration

  baseDelay        ∈ {prefSemiRealisticThrottleAccelerationRepeat (default 300 ms),
                      prefSemiRealisticThrottleDecelerationRepeat  (default 800 ms)}
                   chosen by the auto-increment/decrement direction.

  targetAcceleration ∈ ℝ; sign and magnitude come from `setTargetSpeed`.
                       Defaults to 1 (no modification); brake/load/air
                       multiply it together.
```

Net behaviour of the defaults: a clean acceleration walks 300 ms/step
(63 steps × 300 ms ≈ 19 s for 0 → full); a clean deceleration walks
800 ms/step (~50 s for full → 0). A real heavy freight train has
roughly that kind of timing, which is why the tunables exist.

---

## 3. `setTargetSpeed` — the heart of the semi-realistic logic

`throttle_semi_realistic.java:1430-1559`. This is where the throttle
slider, brake slider, air line, load slider, and direction lever all
fold into a single `(targetSpeed, targetAcceleration)` pair handed to
the ramp scheduler.

### 3.1 Inputs

| Input | Source | Range |
|---|---|---|
| `targetSpeed` | either the slider (`fromSlider==true`) or a caller-supplied value (`setTargetSpeed(int, int)` overload) | 0 .. `maxThrottle` (= `MAX_SPEED_VAL_WIT × maxThrottlePcnt/100`, default 126) |
| `sliderSpeed` | always read from the slider, used as the "where the operator wants to go" reference even when the call is programmatic | 0 .. `maxThrottle` |
| `targetDirection` | direction lever state | FORWARD / NEUTRAL / REVERSE |
| `brakeSliderPosition` | mechanical-brake slider (handle position) | 0 .. `prefSemiRealisticThrottleNumberOfBrakeSteps` (default 7) |
| `loadSliderPosition` | load slider (cargo mass) | 0 .. `prefSemiRealisticThrottleNumberOfLoadSteps` (default 5) |
| `airLineValue` | percentage of air pressure remaining in the train brake line | 0 .. 100 |
| `speed` | live decoder speed | 0 .. 126 |

### 3.2 Initial defaults

```java
double targetAcceleration  = 1;   // multiplicative — 1.0 means "no modifier"
```

A `targetAcceleration` of `+1.0` ⇒ Δt = base delay × 1 = base delay
(default 300 ms acceleration, 800 ms deceleration).

### 3.3 Direction NEUTRAL short-circuit

```java
if (targetDirection == direction_type.NEUTRAL) {
    targetSpeed         = 0;
    sliderSpeed         = 0;
    targetAcceleration  = -1;     // negative ⇒ ramp DOWN at base delay
}
```

NEUTRAL forces the loco to coast to a stop at base deceleration delay
regardless of slider position.

### 3.4 Air system folded into a virtual brake

The air-line pressure value is converted into "effective brake steps"
so the air system can override the mechanical brake slider when the
line is depleted (`throttle_semi_realistic.java:1463-1470`):

```java
double airLine            = 1.0 - (getAirLineValue(whichThrottle) / 100.0);
double airLineAsBrakeStep = Math.round(airLine * prefSemiRealisticThrottleNumberOfBrakeSteps);
double airLineAsBrakePcnt = getBrakeDecimalPcnt(airLineAsBrakeStep,
                                                 prefSemiRealisticThrottleNumberOfBrakeSteps,
                                                 maxBrake);

double brakePcnt          = getBrakeDecimalPcnt(brakeSliderPosition,
                                                 prefSemiRealisticThrottleNumberOfBrakeSteps,
                                                 maxBrake);

double effectiveBrake     = brakePcnt;

if (!prefSemiRealisticThrottleDisableAir && airEnabled) {
    if (airLineAsBrakePcnt < brakePcnt) {  // smaller value = MORE braking
        effectiveBrake = airLineAsBrakePcnt;
    }
}
```

Note the inversion convention: `getBrakeDecimalPcnt` returns **(1 − retarding force)**,
so smaller numbers mean *more* brake force. A "free-running" state is
`effectiveBrake == 1`. The choice between mechanical brake and air-line
brake takes whichever applies *more* retardation.

The `getBrakeDecimalPcnt` curve is non-linear and `maxBrake`-clamped
(`throttle_semi_realistic.java:1564-1570`):

```java
static double getBrakeDecimalPcnt(double step, double steps, double maxBrakeDecimal) {
    double max = Math.sqrt(steps) * steps * maxBrakeDecimal;
    return 1 - (Math.sqrt(step) * step * maxBrakeDecimal / max * maxBrakeDecimal);
}
```

`maxBrake` defaults to `0.70` (70 %), capped by the
`prefSemiRealisticMaximumBrakePcnt` setting. At 100 % the formula
collapses to "instant zero" (per the user-facing summary string).

### 3.5 Brake-aware target & acceleration computation

`throttle_semi_realistic.java:1474-1505`. Three regimes, expressed in
the `effectiveBrake` value:

**Regime A — `effectiveBrake == 1` (no brake force):**

```java
targetSpeed = sliderSpeed;       // no override; ramp toward whatever the slider asks
// targetAcceleration stays at 1 ⇒ Δt = base delay
```

**Regime B — `effectiveBrake < 1` AND throttle slider at zero
(`targetSpeed == 0`):**

The brake is the only thing acting. Use `targetSpeed = 0`, and set
`targetAcceleration = -effectiveBrake`. The negative sign pushes the
ramp down; the magnitude (small ⇒ short Δt ⇒ fast ramp) gets the loco
to zero quickly when the brake is firmly applied.

```java
intermediateBrake  = -1 * effectiveBrake;
targetAcceleration = intermediateBrake;
```

**Regime C — `effectiveBrake < 1` AND throttle slider not at zero:**

The slider asks for some non-zero speed but the brake also wants to
slow the loco. EngineDriver folds both into a single new `targetSpeed`
(the "throttle defeated by brake" notion: brake wins by clipping the
slider's request) and picks an acceleration that points at it.

```java
targetSpeed = (int) (Math.round(sliderSpeed)
                     - Math.round(sliderSpeed) * (1 - effectiveBrake));

if (targetSpeed <= getSpeed(whichThrottle)) {
    // need to slow down; under-power braking is gentler than full braking
    intermediateBrake = -1 * (1 - effectiveBrake * maxBrakeUnderPower);
} else {
    // we'll still accelerate, but more slowly
    intermediateBrake = 1 + (1 - effectiveBrake * maxBrake);
}
targetAcceleration = intermediateBrake;
```

The `maxBrakeUnderPower` constant (default = `maxBrake − 0.20`) is the
"throttle is fighting brake" curve. EngineDriver's design intent (per
the comments and the formula): when you're trying to power against the
brake, the brake feels softer than when you've released the throttle —
the loco still drags but doesn't slam to a stop.

### 3.6 Load slider — multiplicative on Δt

`throttle_semi_realistic.java:1509-1512`:

```java
if (loadSliderPosition > 0) {
    targetAcceleration = targetAcceleration
            * getLoadPcnt(loadSliderPosition, prefSemiRealisticThrottleNumberOfLoadSteps, maxLoad);
}
```

```java
static double getLoadPcnt(double step, double steps, double maxLoadPcnt) {
    double load = step / steps;
    return (((load * load * (maxLoadPcnt - 100))) + 100) / 100;
}
```

`maxLoad` defaults to **1000** (1000 %, i.e. up to 10 ×). Quadratic
ramp: at half-load the multiplier is roughly
`((0.25 × 900) + 100) / 100 ≈ 3.25 ×`, at full load it's
`((1 × 900) + 100) / 100 = 10 ×`.

Because Δt = base × targetAcceleration, **a heavier load
multiplies the inter-step delay** — the loco accelerates and decelerates
more slowly. Same step size; longer between steps.

### 3.7 Sign reconciliation + dispatch

```java
if (targetSpeed < 0)              targetSpeed = 0;
if (targetSpeed > maxThrottle)    targetSpeed = maxThrottle;

// flip the sign if the math came out wanting to ramp the wrong way
if ( ((targetSpeed < speed) && (targetAcceleration > 0))
  || ((targetSpeed > speed) && (targetAcceleration < 0)) ) {
    targetAcceleration = targetAcceleration * -1;
}

targetAccelerations[whichThrottle] = targetAcceleration;
targetSpeeds[whichThrottle]        = targetSpeed;

if ((targetSpeed != speed)
    && ((targetSpeed != prevTargetSpeeds[whichThrottle])
        || (loadSliderPosition != prevLoads[whichThrottle]))
    && !semiRealisticSpeedButtonLongPressActive) {
    if (targetSpeed > speed) {
        setSemiRealisticAutoIncrement(whichThrottle);
    } else {
        setSemiRealisticAutoDecrement(whichThrottle);
    }
    semiRealisticTargetSpeedUpdateHandlers[whichThrottle].removeCallbacksAndMessages(null);
    restartSemiRealisticThrottleTargetSpeedRepeater(whichThrottle,
        getSemiRealisticTargetSpeedRptDelay(whichThrottle));
}
```

The handler is **always cancelled and reposted** when a new target
arrives. Δt is recomputed from the new `targetAcceleration`, so
dragging the brake mid-ramp shortens the ticks and the loco visibly
slows faster.

---

## 4. Brake systems in detail

### 4.1 Brake slider (mechanical / loco brake)

A discrete-step vertical slider. Range:
`0 .. prefSemiRealisticThrottleNumberOfBrakeSteps` (default `7`).

- Position 0 = released, no retardation, `effectiveBrake == 1`.
- Position N = full position; `effectiveBrake = (1 - bigQuadratic ×
  maxBrake)`. With `maxBrake = 0.70`, full-position `effectiveBrake ≈ 0.30`.
- The setting `prefSemiRealisticMaximumBrakePcnt` (5 .. 100 %, default
  70) caps how aggressive the brake gets at full position. Setting it
  to 100 ⇒ "immediate zero" behaviour (the ramp Δt collapses).

Slider movement triggers `setTargetSpeed(whichThrottle, true)` on every
notch (`updateBrakeSliderAndTargetSpeed`,
`throttle_semi_realistic.java:1366-1372`), so each click instantly
re-evaluates target + Δt.

### 4.2 Air system (Westinghouse-style simulation)

Two independent pressure tanks per loco
(`throttle_semi_realistic.java:113-119`):

```java
protected int[] airValues       = {100,100,100,100,100,100};   // reservoir, 0..100
protected int[] airLineValues   = {100,100,100,100,100,100};   // train brake line, 0..100
protected boolean isAirRecharging     = false;
protected boolean isAirLineRecharging = false;
```

Two independent `Handler` repeaters refill them on a fixed cadence
(default `prefSemiRealisticThrottleAirRefreshRate = 2000` ms):

**Reservoir repeater** (`SemiRealisticAirRptUpdater`,
`throttle_semi_realistic.java:2127-2155`):

```java
public void run() {
    if (getAirValue(whichThrottle) >= 100) {
        isAirRecharging = false;
        return;
    }
    setAirValue(whichThrottle, getAirValue(whichThrottle) + 5);   // +5 % per 2000 ms
    setTargetSpeed(whichThrottle, false);   // re-evaluate, since air has changed
    semiRealisticAirUpdateHandlers[whichThrottle]
        .postDelayed(new SemiRealisticAirRptUpdater(whichThrottle, prefSemiRealisticThrottleAirRefreshRate),
                     prefSemiRealisticThrottleAirRefreshRate);
}
```

**Brake-line repeater** (`SemiRealisticAirLineRptUpdater`,
`throttle_semi_realistic.java:2167-2206`) — only refills when the brake
slider is *back at 0*, and pulls air *from the reservoir* in 20-percent
chunks. If the reservoir is depleted, the line can't refill.

```java
if ((getAirLineValue(whichThrottle) >= 100) || (getBrakeSliderPosition(whichThrottle) > 0)) {
    isAirLineRecharging = false;
    if ((airValues[whichThrottle] < 100) && (!isAirRecharging))
        startSemiRealisticThrottleAirRepeater(whichThrottle);
    return;
}
if (airValues[whichThrottle] >= 20) {
    setAirLineValue(whichThrottle, getAirLineValue(whichThrottle) + 20);
    setAirValue   (whichThrottle, airValues[whichThrottle] - 20);
    if (!isAirRecharging) startSemiRealisticThrottleAirRepeater(whichThrottle);
} else if (airValues[whichThrottle] >= 0) {
    int availableAir = airValues[whichThrottle];
    setAirLineValue(whichThrottle, getAirLineValue(whichThrottle) + availableAir);
    setAirValue   (whichThrottle, airValues[whichThrottle] - availableAir);
    if (!isAirRecharging) startSemiRealisticThrottleAirRepeater(whichThrottle);
}
```

The connection from "brake handle moves" to "air pressure drops" is in
`setBothAirValues` (`throttle_semi_realistic.java:1729-1750`):

```java
if (getBrakeSliderPosition(whichThrottle) > previousBrakePosition[whichThrottle]) {
    double airLine = ((double)(prefSemiRealisticThrottleNumberOfBrakeSteps
                              - getBrakeSliderPosition(whichThrottle))
                     / (double) prefSemiRealisticThrottleNumberOfBrakeSteps) * 100;
    if (!prefSemiRealisticThrottleDisableAir && airEnabled) {
        setAirLineValue(whichThrottle, (airLine <= 100) ? (int) Math.round(airLine) : 100);
    }
    showAirLineIndicator(whichThrottle);
}
if (getBrakeSliderPosition(whichThrottle) == 0) {
    if (!isAirRecharging)     startSemiRealisticThrottleAirRepeater(whichThrottle);
    if (!isAirLineRecharging) startSemiRealisticThrottleAirLineRepeater(whichThrottle);
    ...
}
```

So **moving the brake handle deeper drops the air-line pressure**
proportionally to (full − position)/full (i.e. `position N / steps` is
the lost-pressure fraction). **Returning the brake to zero starts both
repeaters refilling** — line first, drawing from reservoir, with the
reservoir then refilling itself at +5 %/refresh.

The air-line value is what `setTargetSpeed` reads via `getAirLineValue`
in §3.4 — so a depleted line acts like a brake even with the mechanical
slider at zero.

The user can disable the whole simulation with the Air button
(`AirButtonTouchListener`, `throttle_semi_realistic.java:1042-1046`):

```java
@Override public void onClick(View v) {
    airEnabled = !airEnabled;
    showAirButtonState(whichThrottle);
}
```

When `airEnabled` is false the `setTargetSpeed` air check
(`if (!prefSemiRealisticThrottleDisableAir && airEnabled)`) is bypassed
and only the mechanical brake slider matters.

### 4.3 ESU decoder-brake functions — passthrough to the loco

The ESU decoder family supports software brake functions (typically
F4/F5/F6) that engage three increasing levels of "decoder-side
deceleration" (the loco computes and applies its own retarding curve
when the function is on). EngineDriver mirrors this into three brake
thresholds and forwards function-on / function-off to the consist
(`throttle_semi_realistic.java:947-998`):

```java
void setDecoderBrake(int whichThrottle) {
    if (!useEsuDecoderBrakes) return;

    double brakePcnt = (1 - getBrakeDecimalPcnt(getBrakeSliderPosition(whichThrottle),
                              prefSemiRealisticThrottleNumberOfBrakeSteps, maxBrake)) * 100;

    int foundBrakeLevel = -1;
    // pass 1: turn everything off
    for (int i = 0; i <= 2; i++) { ... functionShouldBeOnOff[esuBrakeFunctions[i][j]] = 0; ... }
    // pass 2: starting from the highest threshold, turn on the first one that fits
    for (int i = 2; i >= 0; i--) {
        if ((brakePcnt >= esuBrakeLevels[i]) && (foundBrakeLevel < 0)) {
            for (int j = 0; j < 5; j++) {
                if (esuBrakeFunctions[i][j] >= 0) {
                    functionShouldBeOnOff[esuBrakeFunctions[i][j]] = 1;
                }
            }
            foundBrakeLevel = i;
            esuBrakeActive[i] = true;
        } else { esuBrakeActive[i] = false; }
    }
    // pass 3: forward only the changes (don't toggle a function that's already in the right state)
    for (int k = 0; k < threaded_application.MAX_FUNCTIONS; k++) {
        if (functionShouldBeOnOff[k] == 1 && !functionIsOn[k]) {
            sendFunctionToConsistLocos(..., button_press_message_type.DOWN, ...);
        } else if (functionShouldBeOnOff[k] == 0 && functionIsOn[k]) {
            sendFunctionToConsistLocos(..., button_press_message_type.UP,   ...);
        }
    }
}
```

Defaults (`strings.xml`):
- Low brake: F4 at 30 % brake-percent threshold
- Mid brake: F5 at 60 %
- High brake: F6 at 98 %

The thresholds are independent of the air / target-speed math above —
ESU-decoder users get *both* the EngineDriver-side ramp slowdown *and*
a mirroring DCC function activation. The ESU decoder then internally
applies its own ramp on the wire too. Effectively the brake handle
changes both how fast EngineDriver tells the decoder its target speed
*and* what brake function the decoder is running in parallel.

The mode is enabled by
`prefDisplaySemiRealisticThrottleDecoderBrakeType = "esu"`; default
is `"none"`.

### 4.4 Load slider — cargo mass / inertia

Already discussed in §3.6. Independent slider, range
`0 .. prefSemiRealisticThrottleNumberOfLoadSteps` (default `5`).
Quadratic curve up to `maxLoad` (default 1000 %). Acts as a
**multiplier** on `targetAcceleration` so heavier load directly
extends Δt for both up- and down-ramps.

Functionally it simulates train mass / number of cars: a fully loaded
train accelerates and brakes much more slowly than a light engine.
Unlike the brake slider, the load slider doesn't change the *target*
the loco walks toward — only the *rate*.

---

## 5. Direction handling

`changeTargetDirectionIfAllowed` (`throttle_semi_realistic.java:1338-1352`):

```java
@Override
boolean changeTargetDirectionIfAllowed(int whichThrottle, int direction) {
    int speed = getSpeed(whichThrottle);
    int currentDirection = getDirection(whichThrottle);
    boolean result = false;

    if (direction == direction_type.NEUTRAL) {
        targetDirections[whichThrottle] = direction;
        result = true;
    } else if ((speed == 0) || (currentDirection == direction)) {
        targetDirections[whichThrottle] = direction;
        setEngineDirection(whichThrottle, direction, false);
        result = true;
    }
    return result;
}
```

**Direction can only be reversed when the loco is at speed 0**, which
matches real prototype (and most decoder) behaviour. NEUTRAL is always
allowed and forces a coast-down (per §3.3).

---

## 6. Stop / E-Stop behaviour

Stop button has four user-selectable behaviours
(`prefSemiRealisticThrottleStopButtonAction`, default `tz`):

| `prefSemiRealisticThrottleStopButtonAction` | Effect (`throttle_semi_realistic.java:1107-1133`) |
|---|---|
| `THROTTLE_STOP_BRAKE_FULL` | Sets brake slider to its max position, lets the ramp do the rest. |
| `SPEED_ZERO` | `setSpeed(whichThrottle, 0, BUTTONS)` — bypasses the ramp; instant zero. |
| `SPEED_ZERO_BRAKE_ZERO` | Brake slider to 0, then `setSpeed(0)`; instant. |
| `THROTTLE_STOP` (default `tz`) | Resets the throttle slider position to 0 and lets the ramp/brake/load chain do the deceleration. |

E-Stop (`else { setEStop(whichThrottle); ... }`) calls the parent
class's `setEStop`, which sends the JMRI `EStop` (`-1` speed value) to
the decoder — bypassing all of the ramp logic.

The Stop button's "auto-decrement" mode tag
(`mSemiRealisticAutoIncrementOrDecrement[whichThrottle] = DECREMENT`) is
set so that, after the Stop, the next `setTargetSpeed` invocation
correctly picks the deceleration base delay (800 ms vs 300 ms).

---

## 7. Speed-step granularity

Two separate step settings:

| Preference | Default | Used by |
|---|---|---|
| `prefSemiRealisticThrottleSpeedStep` | `2` | The **ramp** scheduler (§2). 1 tick = 2 speed-step units. |
| `prefSpeedButtonsSpeedStep` | `4` | Manual `+` / `−` button presses on the speed buttons (`updateSemiRealisticThrottleSliderAndTargetSpeed`, `throttle_semi_realistic.java:1245-1250`). |

The slider itself moves in 1-step increments unless the user has
notched mode enabled
(`prefDisplaySemiRealisticThrottleNotches`, default 8 visible notches).
With 8 notches over 126 max speed, each notch = ~16 speed-step units,
each ramp tick still steps by 2, so each notch up takes ~8 ticks to
visit. At default 300 ms acceleration that's ~2.4 s/notch.

---

## 8. Default values (current as of 2026-04-26)

From `strings.xml`. All values can be changed in the EngineDriver
preferences UI.

| Pref | Default | Meaning |
|---|---:|---|
| `prefMaximumThrottle` | 100 % | Max throttle as % of MAX_SPEED_VAL_WIT (126). |
| `prefSemiRealisticThrottleSpeedStep` | 2 | Speed-step units per ramp tick. |
| `prefSemiRealisticThrottleAccelerationRepeat` | 300 ms | Base Δt when `auto = INCREMENT`. |
| `prefSemiRealisticThrottleDecelerationRepeat` | 800 ms | Base Δt when `auto = DECREMENT`. |
| `prefSemiRealisticThrottleNumberOfBrakeSteps` | 7 | Brake slider notches. |
| `prefSemiRealisticMaximumBrakePcnt` | 70 | Cap on brake force; 100 = "immediate zero". |
| `prefSemiRealisticThrottleAirRefreshRate` | 2000 ms | Reservoir / line tick interval. |
| `prefSemiRealisticThrottleNumberOfLoadSteps` | 5 | Load slider notches. |
| `prefSemiRealisticThrottleMaxLoadPcnt` | 1000 | Max Δt multiplier from full load (10 ×). |
| `prefDisplaySemiRealisticThrottleNotches` | 8 | Notches drawn on the throttle slider (100 = continuous). |
| `prefSpeedButtonsSpeedStep` | 4 | Speed-step per `+`/`−` press. |
| `prefSpeedButtonsRepeat` | 100 ms | Manual button auto-repeat delay. |
| `prefSemiRealisticThrottleStopButtonAction` | `tz` | Stop-button behaviour selector. |
| `prefSemiRealisticThrottleDisableAir` | true | If true, the air system is bypassed. |
| `prefDisplaySemiRealisticThrottleDecoderBrakeType` | `none` | Set to `esu` to mirror brake to F4/F5/F6. |
| `prefSemiRealisticThrottleDecoderBrakeType{Low/Mid/High}FunctionEsu` | 4 / 5 / 6 | Functions to drive for low / mid / high decoder brake. |
| `prefSemiRealisticThrottleDecoderBrakeType{Low/Mid/High}ValueEsu` | 30 / 60 / 98 | Brake-percent thresholds at which each function is engaged. |

---

## 9. Cross-walk to the RailDriver Modern Desktop

EngineDriver's UI gives the operator three vertical sliders:
**Throttle** (combined with dyn-brake context), **Brake** (mechanical
loco brake), **Load** (cargo mass), plus on-screen Air-on/off and
ESU-mode buttons. The RailDriver has more knobs and they map naturally
but not 1:1.

| EngineDriver concept | Closest RailDriver control(s) | Notes |
|---|---|---|
| Throttle slider (notches above center → speed up, idle band, dyn-brake region not directly mapped) | **Throttle / Dyn Brake lever (#9, byte 1)** lever DOWN → throttle, lever UP → dyn brake | We already calibrate Idle Low / Idle High / Full Throttle / Full Dyn Brake. The "above idle" travel is the throttle slider; the "below idle" travel is the dyn-brake region (EngineDriver fakes this with the brake slider; we have a real lever). |
| Mechanical brake slider (# of notches) | **Independent Brake (#11, byte 3)** | Calibrated Full Release (0xc0) → Full Application (0x41). Maps 1:1 onto a stepped brake slider with the right number of notches. |
| Air-line pressure (decreases when brake slider is moved deeper) | **Auto Brake lever (#10, byte 2)** | The Auto Brake on a real RailDriver IS the trainline / air-brake handle, with mechanical detents at Released, SUP, CS, and EMG. EngineDriver simulates this as a derived value of the brake slider; we have a real continuous lever for it. |
| Air-line bail-off (release of trainline brake while keeping mechanical brake) | **Bail-off momentary switch (#11 / byte 4 transient)** | EngineDriver doesn't have an explicit bail-off control; it derives air-line state from brake slider movement. We'd dispatch the bail-off press as "force `airLineValue` back toward 100 instantaneously" or, more authentically, as an immediate release of the air-line component of the effective brake until the switch is released. |
| Load slider (cargo mass) | No physical control — exposed as a **named-scenario picker** in JMRI | EngineDriver's continuous load slider becomes a small enumerated list of named operating scenarios (e.g. *Light engine*, *Switcher*, *Local freight*, *Through freight*, *Unit train*, *Custom*) that the operator picks at session start. Each scenario maps to a Δt multiplier internally; *Light engine* = 1.0 (no mass simulation), the heavier presets ramp up quadratically toward the EngineDriver ceiling of 10×. See §9.2 for details. |
| Direction lever (FORWARD / NEUTRAL / REVERSE — only changeable at speed 0) | **Reverser (#8, byte 0)** | We already calibrate Forward / Neutral / Reverse detents. The "speed 0 only" interlock would have to be enforced JMRI-side. |
| Stop button (4 modes) | **E-Stop SPDT switch (#2, slots 36/37)** | Currently does `setSpeedSetting(-1)`; in the semi-realistic context this would map to "E-Stop" mode, with maybe one of the front-edge buttons mapped to the gentler `THROTTLE_STOP_BRAKE_FULL` mode. |
| ESU decoder-brake functions (F4/F5/F6 with thresholds) | Pure DCC-side, no physical control needed | Brake-percent computed from RailDriver's Independent Brake position; same threshold-driven F-function dispatch logic carries over. |
| Air on/off button | **Likely not needed at all on the RailDriver.** | EngineDriver only has *one* brake slider, so the Air toggle exists to let the operator suppress the simulated air-line dynamics derived from that slider. We have *two* independent physical levers — Auto Brake (the trainline / air handle) and Independent Brake (the loco-only mechanical brake). An operator who doesn't want air dynamics just keeps the Auto Brake at Released and uses only the Independent Brake. The toggle was compensating for ED's single-slider limitation; with separate levers it's redundant. |

### 9.1 Implementation hints for adapting the model

The EngineDriver code does **not** depend on the physical slider being
software — `setTargetSpeed` reads slider position via accessor methods
(`getBrakeSliderPosition`, `getLoadSliderPosition`,
`getSemiRealisticThrottleSlider`). For our adaptation, those accessors
become "read the latest live byte from the calibrated RailDriver
control, mapped to a step or fraction".

Concretely:

- `getSemiRealisticThrottleSliderSpeed(whichThrottle)` → derive from
  byte 1 above Idle High, normalised to `0..maxThrottle` against
  `(throttleMax - throttleMin)`. This is what we already do today in
  `RailDriverMenuItem.dispatchValueEvent`'s Axis 1 case — except today
  we feed the result directly to `setSpeedSetting` instead of into a
  `targetSpeed`.
- `getBrakeSliderPosition(whichThrottle)` → derive from byte 3 between
  calibrated Full Release and Full Application, quantised to whatever
  number-of-brake-steps preference we expose.
- `getAirLineValue(whichThrottle)` → derive from byte 2 (Auto Brake)
  between calibrated Released and EMG. The Auto Brake gives us a
  *real* analog air-line position rather than the EngineDriver
  derived-from-mechanical model; the bail-off switch then becomes
  an immediate 100 %-restore on byte 4 transient.
- `getLoadSliderPosition(whichThrottle)` → user preference; not bound
  to a physical control.

The two `Handler.postDelayed` repeaters (`SemiRealisticTargetSpeedRptUpdater`,
`SemiRealisticAirRptUpdater`) translate cleanly to `javax.swing.Timer`
on the EDT, or to `java.util.concurrent.ScheduledExecutorService` if
we want to keep the math off the EDT and just `invokeLater` the
`setSpeedSetting` call. The ramp scheduler's "cancel and repost on
every input change" pattern is straightforward with either one.

### 9.2 Load slider — named-scenario picker

EngineDriver's continuous load slider becomes a small enumerated list
on the RailDriver because we have no physical analog control to bind a
slider to. Operators of physical RailDriver consoles tend to think
in scenarios anyway ("we're running the morning local freight"), so
naming the load steps and pre-canning them is a UX win even compared
to a numeric slider.

#### 9.2.1 The preset list

| Scenario | Δt multiplier | Mental model |
|---|---:|---|
| `Light engine` | 1.0 | Single loco, no cars. No mass simulation; ramp at base delay. |
| `Switcher` | 1.5 | Yard work, a handful of cars. Slightly heavier than light. |
| `Local freight` | 2.5 | Mid-length way-freight. Noticeable inertia. |
| `Through freight` | 5.0 | Long road train, mixed loads. Half-way to ED's ceiling. |
| `Unit train` | 10.0 | Heavy unit coal / grain / oil. Full ED ceiling. |
| `Custom` | user-defined | Numeric input (1.0..10.0), persisted; for users who want to tune their own number. |

The multipliers are picked to mirror EngineDriver's quadratic load
curve (`getLoadPcnt`) at evenly spaced slider steps: 0/5 → 1.0,
1/5 → 1.36, 2/5 → 2.44, 3/5 → 4.24, 4/5 → 6.76, 5/5 → 10.0. The named
presets above approximate that curve while picking round numbers that
read naturally in the UI.

`Light engine` reproduces phase-3 behaviour exactly (multiplier 1.0,
which is what `targetAcceleration` defaults to today). Picking any
heavier scenario directly multiplies whatever `targetAcceleration`
`setTargetSpeed` produces — same wiring point as EngineDriver's load
slider, just driven from an enum rather than a SeekBar.

#### 9.2.2 Where the picker lives

A small dropdown / button group on the RailDriver throttle window
(or on the calibration window — TBD during implementation) labelled
**Scenario:**, with the six options. The current selection is
displayed prominently so the operator can verify "I'm in *Through
freight*" at a glance. Switching scenarios mid-session takes effect
on the next `setTargetSpeed` invocation; the in-flight ramp continues
with the old multiplier until the user touches a control or reaches
the current target.

#### 9.2.3 Persistence

Stored as a single string in the same per-profile XML used by the
calibration data:

```xml
<raildriver-calibration version="2">
    ...
    <semiRealistic>
        <scenario>Through freight</scenario>
        <customLoadMultiplier>3.5</customLoadMultiplier>  <!-- only honoured when scenario == Custom -->
    </semiRealistic>
    ...
</raildriver-calibration>
```

Schema bumps to `version="2"` because `<semiRealistic>` is a new
top-level subtree (the loader's existing tolerance for missing
elements still keeps phase-3 calibration files loadable; the
scenario simply defaults to `Light engine` until the user picks
something).

#### 9.2.4 Implementation sketch

```java
public enum LoadScenario {
    LIGHT_ENGINE("Light engine",   1.0),
    SWITCHER    ("Switcher",       1.5),
    LOCAL       ("Local freight",  2.5),
    THROUGH     ("Through freight",5.0),
    UNIT_TRAIN  ("Unit train",    10.0),
    CUSTOM      ("Custom",         /* read from RailDriverCalibration */ );

    public double multiplier() { ... }
}
```

`setTargetSpeed`'s existing load multiplication line:

```java
if (loadSliderPosition > 0) {
    targetAcceleration = targetAcceleration
            * getLoadPcnt(loadSliderPosition, prefSemiRealisticThrottleNumberOfLoadSteps, maxLoad);
}
```

…becomes:

```java
LoadScenario scenario = settings.scenario();   // from the picker
if (scenario != LoadScenario.LIGHT_ENGINE) {
    targetAcceleration = targetAcceleration * scenario.multiplier();
}
```

Same effect on the ramp Δt; same place in the math. The only thing
that changes is *where the multiplier comes from* — an enum-backed
preference rather than a slider position.

#### 9.2.5 Future extensibility (deferred)

If we later want per-loco defaults (the option-2 path I sketched
during research), the picker adds a "Use roster default" option that
reads `RosterEntry.getAttribute("raildriver.scenario")`. Implementing
that is separable from the named-scenario picker itself — once the
enum and the multiplication are in place, the only change is *where
the active scenario is sourced from*. So phase 4 can ship with a
session-level picker only, and phase 5+ can add the roster-attribute
fallback if the operators end up wanting it.

---

## 10. Differences from the RailDriver hardware that we'll have to design around

1. **EngineDriver has one combined throttle slider, RailDriver has a
   bipolar throttle/dyn-brake lever.** The semi-realistic logic we
   adopt should keep the existing "below-idle = dyn brake side" idea
   from our phase 2 work, but now the dyn-brake side actually does
   something — analogous to how the brake slider acts on
   `targetAcceleration`. We can wire the dyn-brake region of the lever
   so that its "depth" past Idle Low scales the same kind of negative
   `targetAcceleration` term that EngineDriver's brake slider produces,
   and skip the air-line interaction (dyn brake doesn't use trainline air
   in real prototypes).
2. **EngineDriver derives air-line state from the brake handle.** The
   RailDriver gives us *both* the loco brake (Indep Brake byte 3) and
   the train brake (Auto Brake byte 2) as independent levers, plus
   bail-off. So our semi-realistic implementation can be more
   prototypical: byte 2 directly drives `airLineValue`; byte 3 directly
   drives the loco brake; bail-off pulses an air-line-restore.
3. **No load slider on the device.** Exposed instead as a JMRI-side
   **named-scenario picker** at session start (see §9.2). The operator
   picks "what they're doing today" — `Light engine`, `Switcher`,
   `Local freight`, `Through freight`, `Unit train`, or `Custom` — and
   the corresponding Δt multiplier feeds into the same
   `targetAcceleration` slot EngineDriver's slider does. No physical
   knob is needed.
4. **No Air on/off button — and we likely don't need one.** EngineDriver
   has the toggle because its single brake slider has to do double duty
   (mechanical brake *and* derived air-line pressure); the toggle lets
   the operator opt out of the air half of that. We have two real
   levers — the Auto Brake *is* the air-line handle, the Independent
   Brake *is* the loco-only mechanical brake — so an operator who
   doesn't want air dynamics simply leaves the Auto Brake at Released
   and only uses the Independent Brake. The toggle is solving a problem
   we don't have.
5. **Stop modes.** The RailDriver E-Stop is a full E-Stop today; we
   have plenty of buttons available to add a "throttle to zero, brake
   full" softer-stop button if we want it.
6. **EngineDriver runs per-throttle (it can drive 6 in parallel).**
   The RailDriver only ever drives the single active throttle frame,
   so we can collapse the per-loco arrays to single fields.
7. **EngineDriver runs on Android and uses `Handler.postDelayed`.**
   We're on Swing/Java SE; equivalent is either `javax.swing.Timer`
   (fires on EDT) or `ScheduledExecutorService` (worker thread, with
   `SwingUtilities.invokeLater` for any UI update). The ramp scheduler
   itself doesn't need the EDT; only `setSpeedSetting` does, so a
   `ScheduledExecutorService` is the natural fit.

---

## 11. Summary

EngineDriver's semi-realistic throttle is a **target-speed ramp model
parameterised by a multiplicative acceleration value**. The user
manipulates target via a slider; a per-loco
`Handler.postDelayed`-driven runnable walks the live decoder speed
toward the target one fixed-size step every Δt ms, where Δt is
recomputed every time the target or any modifier changes:

```
Δt = base × targetAcceleration

base               = pref{Acc | Dec}elerationRepeat   (default 300 / 800 ms)
targetAcceleration = sign × magnitude
                       sign      ∈ { +1 (up), −1 (down) }
                       magnitude built from:
                         · effectiveBrake     (mechanical slider OR depleted air-line, whichever bites harder)
                         · maxBrakeUnderPower (softer curve when fighting the brake with throttle)
                         · loadSliderPcnt     (quadratic up to 10× from a 5-step slider)
```

The brake slider also clips the *target* — at full brake the target is
forced down to 0 even with the throttle slider asking for full. The
air system is a 0..100 % reservoir + line, both refilled on a 2-second
tick when the brake handle is at zero and the reservoir has spare
pressure to give. ESU-decoder mode mirrors three brake thresholds onto
F4/F5/F6 in parallel.

The whole thing fits neatly onto the RailDriver Modern Desktop's
control set, with the device offering *more* prototypical inputs
(separate auto-brake, indep-brake, and bail-off) than EngineDriver's
single brake + air-line derivation model. The "pure software" pieces
(load, air-enable toggle, decoder-brake mode) can either be JMRI
preferences or assigned to user-assignable front-edge buttons / hat
positions.
