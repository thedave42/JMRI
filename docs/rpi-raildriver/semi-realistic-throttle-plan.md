# RailDriver Phase 4 — Semi-realistic throttle support

> **Parent plan:** [`plan.md`](plan.md)
> **Predecessors:** [`plan-impl-phase1.md`](plan-impl-phase1.md), [`plan-impl-phase2.md`](plan-impl-phase2.md), [`plan-impl-phase3.md`](plan-impl-phase3.md) — all merged.
> **Research source:** [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md). All section references prefixed `[research §X]` resolve there.
> **Branch:** `rpi-raildriver`
> **Target platform:** Raspberry Pi 4, 64-bit Raspberry Pi OS / Debian 12+, JMRI 5.15.x.
> **Phase 4 goal:** add EngineDriver-style semi-realistic throttle behaviour to the RailDriver path. Speed is no longer set directly from the throttle lever; instead the lever sets a *target* and a separate ramp scheduler walks the live decoder speed toward it on a brake-/scenario-aware schedule. Independent and Auto brakes shape the ramp's Δt; bail-off restores the air line; the dynamic-brake side of the throttle lever finally does something; a named-scenario picker stands in for EngineDriver's continuous load slider.

## 1. Scope

### In scope (split into sub-phases 4a–4g, each independently shippable)

**4a — Ramp engine + bypass switch.** New `SemiRealisticThrottleEngine` class that owns `targetSpeed` / `targetAcceleration` / a `ScheduledExecutorService`-driven ramp scheduler. When semi-realistic mode is OFF (default, until the user opts in), behaviour is identical to phase 3. When ON, the throttle lever (Axis 1 above Idle High) sets `targetSpeed`; the scheduler ticks toward it at the base acceleration / deceleration delay. No brakes shape the ramp yet — purely target-and-walk.

**4b — Scenario picker (load multiplier).** Adds the named-scenario enum and picker UI per [research §9.2]. Multiplies `targetAcceleration` so heavier scenarios visibly extend Δt. Persisted in the calibration XML under a new `<semiRealistic>` subtree (schema bumped to version `"2"`).

**4c — Independent brake (Axis 3) → mechanical brake clip + accel shaping.** Calibrated Indep-brake position becomes EngineDriver's `brakeSliderPosition`, quantised to a configurable number of steps. Folds into the existing `setTargetSpeed` brake regimes [research §3.5] — clipping the target and selecting between `effectiveBrake` and `maxBrakeUnderPower`-curve acceleration depending on whether the throttle is fighting the brake.

**4d — Auto brake (Axis 2) → air-line value + bail-off (byte 4) restore.** The Auto Brake lever directly drives `airLineValue` (no derived-from-mechanical model — see [research §10] item 2). Released → 100, EMG → 0, monotonic between. The bail-off switch (byte 4 transient) immediately restores `airLineValue` to 100 while held. Replaces EngineDriver's reservoir-and-line refill repeaters [research §4.2] with a simpler direct-from-lever mapping (the operator's hand on the lever is the prototype).

**4e — Dynamic brake side of throttle lever (Axis 1 below Idle Low).** Below the calibrated Idle Low, the throttle lever produces a negative `targetAcceleration` term, separate from the air-line and indep-brake terms. Distinct from the indep-brake because real dyn-brake doesn't use trainline air [research §10 item 1]. LED display shows `DBr` while in dyn-brake region.

**4f — Reverser interlock.** Direction-change-only-at-speed-0 interlock per [research §5]. E-Stop SPDT keeps its current `setSpeedSetting(-1)` behaviour. (EngineDriver's "soft stop button" mode is intentionally not adopted — the RailDriver's physical Independent Brake handle already gives the operator a more prototypical controlled-stop than a one-touch button would.)

**4g — ESU decoder-brake passthrough (optional, gated by user preference).** Per [research §4.3], computes brake-percent from the calibrated indep-brake position and forwards F4/F5/F6 dispatch when the user opts in. Defaults to OFF.

### Out of scope (deferred to phase 5+)

- **Per-roster scenario default.** Phase 4 ships with a session-level picker; reading `RosterEntry.getAttribute("raildriver.scenario")` to override the session default is phase 5+ per [research §9.2.5].
- **Multi-throttle support.** EngineDriver runs up to 6 locos in parallel; we keep the phase-1..3 single-throttle assumption.
- **Configurable ramp parameters via UI.** Phase 4 exposes `accelerationDelay` / `decelerationDelay` / `speedStep` / `brakeSteps` / `maxBrakePcnt` as fields on the new settings window; advanced curves (e.g. user-configurable load multiplier table) stay hardcoded.
- **EngineDriver's `Stop` button and its four behaviour modes.** The Stop button is an Android-touch UX device — useful when your only inputs are screen taps. On a RailDriver console the operator already has E-Stop (hard) and the Independent Brake handle (controlled) within reach. None of EngineDriver's four stop modes (`THROTTLE_STOP`, `THROTTLE_STOP_BRAKE_FULL`, `SPEED_ZERO`, `SPEED_ZERO_BRAKE_ZERO`) is adopted; the existing E-Stop SPDT keeps its phase-1 behaviour.
- **Tests.** Parent §4.5. Phase 5+.
- **Help / documentation updates.** Parent §4.6. Phase 5+.
- **Cross-platform verification** — community testers, not in scope.
- **All latent issues from parent §3 / §6** still untouched.

## 2. Architecture

### 2.1 New class: `SemiRealisticThrottleEngine`

Single-throttle engine. Owned by `RailDriverMenuItem`. Lifecycle parallels the polling thread: created lazily in `attachThrottleWindow` after `activeThrottleFrame` is set; disposed in `propertyChange`'s `"ancestor"` case alongside the `throttleDispatcher` deregistration.

```java
public final class SemiRealisticThrottleEngine {
    // — Mode —
    private volatile boolean enabled;            // false => bypass (phase-3 direct setSpeedSetting path)

    // — Settings (loaded from calibration XML <semiRealistic> subtree) —
    private final SemiRealisticSettings settings;

    // — Target state —
    private volatile int    targetSpeed;          // 0..126
    private volatile double targetAcceleration;   // sign + magnitude (see research §2)

    // — Latest physical inputs (units after calibration application) —
    private volatile int    leverThrottleSpeed;       // 0..126 from Axis 1 above Idle High
    private volatile double leverDynBrakeFraction;    // 0.0..1.0 from Axis 1 below Idle Low (4e)
    private volatile int    indepBrakeStep;           // 0..brakeSteps from Axis 3 (4c)
    private volatile int    airLinePercent;           // 0..100 from Axis 2 (4d)
    private volatile boolean bailoffPressed;          // from byte 4 transient (4d)
    private volatile LoadScenario scenario;           // from picker (4b)
    private volatile int    direction;                // FORWARD / NEUTRAL / REVERSE from Axis 0

    // — Scheduler —
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RailDriver-SemiRealistic-Ramp");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> rampTask;

    // — Throttle proxy (replaces direct DccThrottle.setSpeedSetting calls) —
    private final ThrottleProxy proxy;
    ...
}
```

`ThrottleProxy` is a thin abstraction so the engine doesn't link directly to `DccThrottle` — easier to unit-test later, and easier to swap with a stub when the throttle is detached. Methods: `setSpeed(int 0..126)`, `setIsForward(boolean)`, `getCurrentSpeed()`, `getMaxFunctionCount()`, `setFunction(int n, boolean on)`. Backed by `activeThrottleFrame.getAddressPanel().getThrottle()`.

### 2.2 Threading model

- Polling thread (existing) reads HID, fires `RawByte` and `Value` PCS events.
- `dispatchValueEvent` (existing) handles per-axis logic. In semi-realistic mode it now updates the engine's `leverThrottleSpeed` / `leverDynBrakeFraction` / `indepBrakeStep` / `airLinePercent` / `bailoffPressed` / `direction` fields and calls `engine.recompute()`.
- `engine.recompute()` runs `setTargetSpeed`-equivalent logic, computes a new `(targetSpeed, targetAcceleration)`, cancels the in-flight `rampTask`, schedules a fresh one. Runs on the polling thread (cheap; no Swing, no DCC IO).
- `rampTask`'s body runs on the engine's `ScheduledExecutorService` worker. It computes the next step, then calls `SwingUtilities.invokeLater(() -> proxy.setSpeed(next))` so the actual `setSpeedSetting` happens on the EDT (matches the existing pre-phase-2 pattern; consistent with the rest of the JMRI throttle path).
- `targetSpeed` / `targetAcceleration` / current step value are accessed under a single `synchronized` block on the engine instance to keep the ramp scheduler and the recompute calls coherent.

### 2.3 Bypass-mode wiring

When `enabled == false`, `engine.recompute()` is a no-op and `dispatchValueEvent` falls back to the phase-3 direct `setSpeedSetting` / `setIsForward` / `setFunction(0, ...)` path. **No behavioural change for users who don't opt in.** The mode is per-profile, persisted alongside the rest of the calibration; default OFF.

### 2.4 Settings window

A new `Debug → RailDriver Semi-Realistic Settings...` menu entry opens a modal `JmriJFrame` with:

- **Enable semi-realistic mode** checkbox (the master switch).
- **Scenario:** dropdown — `Light engine` / `Switcher` / `Local freight` / `Through freight` / `Unit train` / `Custom` (with a numeric field exposed when `Custom` is selected).
- **Acceleration delay (ms):** numeric, default 300.
- **Deceleration delay (ms):** numeric, default 800.
- **Speed step:** numeric, default 2.
- **Brake steps:** numeric, default 7.
- **Maximum brake percent:** numeric, default 70.
- **Decoder-brake mode:** dropdown `None` / `ESU` (and ESU-only sub-fields when ESU is selected — F-numbers + thresholds).

Save behaves like the calibration window's Save (writes XML, asks the engine to reload settings, closes window). Reset-to-defaults restores all fields to the EngineDriver default values.

The two windows (calibration + semi-realistic settings) are deliberately separate because they answer different questions: calibration is "what bytes does my hardware emit at each detent"; semi-realistic settings is "how should those calibrated levers shape my loco's behaviour". Different mental models, different audiences (every user calibrates; only some opt into semi-realistic mode).

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
        <scenario>Through freight</scenario>
        <customScenarioMultiplier>3.5</customScenarioMultiplier>
        <accelerationDelayMs>300</accelerationDelayMs>
        <decelerationDelayMs>800</decelerationDelayMs>
        <speedStep>2</speedStep>
        <brakeSteps>7</brakeSteps>
        <maxBrakePercent>70</maxBrakePercent>
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

Schema migration: phase-3 files (`version="1"`) load cleanly because the loader's existing tolerance for missing elements treats `<semiRealistic>` as absent ⇒ defaults (= disabled, which is the phase-3 fallback ⇒ no behaviour change for legacy files). The `version` attribute exists so a later schema-3 change can branch cleanly.

## 3. Sub-phase staging

Each sub-phase is independently buildable, installable, and testable on a real DCC loco. Acceptance criteria are listed for each.

### 3.1 Sub-phase 4a — Ramp engine + bypass switch

**Goal:** verify the ramp scheduler works end-to-end without any brake/load complexity. The throttle lever sets a target; the loco walks toward it at constant base delay.

**New / modified files:**
- `java/src/jmri/util/usb/SemiRealisticThrottleEngine.java` (new) — engine with the `recompute()` / scheduler / `ThrottleProxy`. In 4a only the throttle path is wired; brake/load fields are present but unused.
- `java/src/jmri/util/usb/SemiRealisticSettings.java` (new) — settings POJO with load + save methods, mirroring `RailDriverCalibration`'s structure.
- `java/src/jmri/util/usb/RailDriverCalibration.java` — bump schema to `"2"`, add `<semiRealistic>` subtree population/build, hold a `SemiRealisticSettings` field.
- `java/src/jmri/util/usb/RailDriverMenuItem.java` — instantiate the engine in `attachThrottleWindow`; route `dispatchValueEvent` Axis 1 dispatch through the engine when `settings.enabled`.
- `java/src/jmri/util/usb/SemiRealisticSettingsAction.java` (new) — `AbstractAction` opening the new settings window.
- `java/src/jmri/util/usb/SemiRealisticSettingsFrame.java` (new) — only the Enable checkbox + Acceleration / Deceleration delay / Speed step inputs in 4a; other rows greyed out / not yet implemented.
- `java/src/apps/jmrit/DebugMenu.java` — add the new menu entry.
- `java/src/jmri/util/usb/Bundle.properties` — add `RdSemiRealisticSettings` key.

**Acceptance:**
1. `Debug → RailDriver Semi-Realistic Settings...` opens the window. Toggling the Enable checkbox + Save persists to disk and reloads.
2. Mode OFF: throttle behaves exactly as in phase 3 (direct `setSpeedSetting`).
3. Mode ON: moving the throttle lever from idle to full speed produces a visible ramp on the loco — the loco's speed slider (in JMRI throttle window) walks up over ~19 s by default (63 steps × 300 ms, with default speed step = 2).
4. Mode ON: moving the lever back to idle produces a ~50 s ramp down (default 800 ms × 63 steps).
5. Reverser still works, just with the ramp engine in between.
6. Polling-thread NPE invariant from phase 3 still holds.

### 3.2 Sub-phase 4b — Scenario picker

**Goal:** the load multiplier visibly extends ramp Δt.

**Modified:** `SemiRealisticSettings.java` (add `scenario` + `customScenarioMultiplier`), `SemiRealisticSettingsFrame.java` (enable the Scenario row), `SemiRealisticThrottleEngine.java` (multiply `targetAcceleration` by `scenario.multiplier()` before scheduling).

**Acceptance:**
1. Picker shows all 6 scenarios; Custom shows a numeric field that's only honoured when Custom is selected.
2. Switching from `Light engine` to `Unit train` makes a 0 → full-speed ramp take ~10 × longer (≈ 3 minutes).
3. Switching mid-ramp picks up on the next `recompute()` (next lever movement or every brake update).

### 3.3 Sub-phase 4c — Independent brake → mechanical brake

**Goal:** Indep-brake lever (Axis 3) shapes the target and Δt per [research §3.5].

**Modified:** `SemiRealisticThrottleEngine.java` (add the `effectiveBrake` chain — but with `airLinePercent == 100` constant for now, so only the mechanical side is in play), `dispatchValueEvent` Axis 3 case (update `engine.indepBrakeStep`).

Quantisation: `indepBrakeStep = round((calibratedFullRelease - byteValue) / (calibratedFullRelease - calibratedFullApplication) * brakeSteps)`. `brakeSteps` from settings (default 7).

**Acceptance:**
1. Indep brake at Full Release: throttle behaves as in 4a/4b (ramp toward lever target).
2. Indep brake mid-travel while throttle is at full: loco drops to a partial speed (the EngineDriver "throttle defeated by brake" curve) and decelerates to it on the brake-shaped Δt.
3. Indep brake at Full Application + throttle at zero: loco stops on a fast deceleration (the regime-B curve, `Δt = base × −effectiveBrake` ≈ 90 ms with 70 % maxBrake).
4. Releasing the indep brake while at speed: loco resumes accelerating toward the lever's target on the normal curve.

### 3.4 Sub-phase 4d — Auto brake → air line + bail-off restore

**Goal:** Auto Brake (Axis 2) directly drives `airLinePercent`; bail-off (byte 4) restores it to 100 transiently.

**Modified:** `dispatchValueEvent` Axis 2 case (compute `airLinePercent = round((byteValue - calibratedEmg) / (calibratedReleased - calibratedEmg) * 100)`; clamp to 0..100); button dispatch for the bail-off switch (set `engine.bailoffPressed = true/false` based on byte-4 threshold-crossing); `SemiRealisticThrottleEngine.recompute()` honours `bailoffPressed` by treating the air line as 100 % regardless of Axis 2 position while the switch is held.

This replaces EngineDriver's reservoir-and-line repeater simulation [research §4.2] with a direct mapping. It's both simpler in code and more prototypical (the operator's hand position *is* the air pressure on a real RailDriver). The reservoir-with-recharge state machine is **out of scope** unless the operator specifically wants to simulate "running out of air" — which they don't on a console with a real Auto Brake handle.

**Acceptance:**
1. Auto brake at Released: throttle ramps to lever target as in 4c.
2. Auto brake at EMG: loco drops to zero on the air-line-as-brake curve, fast deceleration.
3. Auto brake mid-travel + indep brake at Full Release: the air line dominates because it bites harder (`min(airLineAsBrakePcnt, brakePcnt)` per [research §3.4]).
4. Auto brake at SUP/CS + bail-off pressed: loco accelerates again because air line is treated as 100 while bail-off is held — even though the lever is still applying.
5. Releasing bail-off restores brake immediately.

### 3.5 Sub-phase 4e — Dynamic brake (lever UP)

**Goal:** the half of the throttle lever above center (toward DYN BRAKE label) finally does something.

**Modified:** `dispatchValueEvent` Axis 1 case to compute `leverDynBrakeFraction = (calibratedIdleLow - byteValue) / (calibratedIdleLow - calibratedFullDynBrake)` clamped to 0..1 when the byte is below Idle Low (= the dyn-brake side); `SemiRealisticThrottleEngine.recompute()` adds a separate `dynBrakeAcceleration` term that's strictly subtractive, distinct from `effectiveBrake`. The `DBr` LED, currently a TODO from phase 2, becomes the indication that the dyn-brake region is active.

Dyn brake doesn't use trainline air, so it stacks orthogonally with `airLinePercent` — both contribute deceleration. The combined effective `targetAcceleration` magnitude is the larger (i.e. shorter Δt) of the two terms when both are active.

**Acceptance:**
1. Lever at Idle Low or above: dyn brake is inactive; behaviour identical to 4d.
2. Lever at full DYN BRAKE: loco decelerates to zero on a fast curve; LED shows `DBr`.
3. Lever just past Idle Low: loco decelerates slowly, LED still shows `DBr`.
4. Auto brake also applied while in dyn brake: deceleration is at least as fast as the most aggressive of the two — they don't cancel, they reinforce.

### 3.6 Sub-phase 4f — Reverser interlock

**Goal:** [research §5] direction can only change at speed 0.

**Modified:** `dispatchValueEvent` Axis 0 case to suppress `setIsForward(...)` when `engine.currentSpeed > 0` (when in semi-realistic mode). E-Stop SPDT keeps `setSpeedSetting(-1)`. Reverser to NEUTRAL at any speed forces a coast-down per [research §3.3].

**Acceptance:**
1. Loco at speed > 0 + reverser moved to opposite direction: direction does NOT flip; an INFO log line records the suppression. Direction lever change with loco at speed 0 still works.
2. Reverser to NEUTRAL at any speed: loco coasts to a stop on the deceleration curve regardless of throttle/brake levers (per [research §3.3]).
3. E-Stop SPDT at any speed: loco hard-stops via `setSpeedSetting(-1)`. Same as phase 1–3 behaviour.

### 3.7 Sub-phase 4g — ESU decoder-brake passthrough (optional)

**Goal:** for users with ESU decoders, mirror brake percent to F4/F5/F6 [research §4.3]. Off by default.

**Modified:** `SemiRealisticSettings.java` (`decoderBrakeMode` enum; ESU function/threshold fields), `SemiRealisticSettingsFrame.java` (enable the Decoder-brake Mode dropdown + ESU-only sub-fields), `SemiRealisticThrottleEngine.recompute()` (after computing `effectiveBrake`, dispatch the function changes with the same three-pass logic from `setDecoderBrake` in [research §4.3]).

**Acceptance:**
1. Decoder-brake mode = None: no F4/F5/F6 dispatch from semi-realistic logic.
2. Decoder-brake mode = ESU: applying indep brake to ≥30 % toggles F4 ON; ≥60 % toggles F4 OFF + F5 ON; ≥98 % toggles F5 OFF + F6 ON. Backing off reverses the chain.
3. The threshold/function fields are user-configurable per loco family.

## 4. Acceptance criteria (overall)

1. With semi-realistic mode OFF, behaviour is identical to phase 3 (no regression).
2. With mode ON, all sub-phase acceptance criteria pass on a real DCC loco.
3. Calibration XML round-trips through Save / Load with the new schema; phase-3 files (version `"1"`) still load cleanly with semi-realistic defaults.
4. The `activeThrottleFrame == null` invariant from phase 3 still holds — the engine acquires its `ThrottleProxy` from `activeThrottleFrame` at attach time and never holds the reference past the throttle's `"ancestor"` close.
5. The phase-3 noise hysteresis filter still applies — the engine never sees byte-level jitter as a "lever moved".
6. No new `messages.log` exceptions during a 30-minute ops session involving repeated brake / throttle work.

## 5. Deliverables

Per sub-phase, listed in §3. Total across phases 4a–4g:

- 5 new Java files (`SemiRealisticThrottleEngine`, `SemiRealisticSettings`, `LoadScenario` enum, `SemiRealisticSettingsAction`, `SemiRealisticSettingsFrame`).
- ~6 modified files (`RailDriverCalibration`, `RailDriverMenuItem`, `DebugMenu`, `Bundle.properties`, plus minor touches).
- New per-profile XML subtree (schema bumped to version `"2"`).
- No changes to native libs, hid4java, udev rules, or build.xml / pom.xml.

## 6. Open design questions for review

These are not yet resolved; please decide before sub-phase 4a starts.

1. **Mode toggle location.** Settings window only (proposed), or also a quick toggle button on the JMRI throttle window?
2. **Settings window vs. tab on calibration window.** Separate windows (proposed) or one window with two tabs?
3. **EDT discipline for `setSpeedSetting`.** Wrap in `SwingUtilities.invokeLater` (proposed; matches existing JMRI pattern) or call directly from the worker (parent §6 lists the off-EDT mutation as a pre-existing latent issue, but fixing it is part of phase 4 if we go this way).
4. **`maxBrakeUnderPower`** — derive as `maxBrake - 0.20` per EngineDriver (proposed), or expose as a separate user setting?
5. **Bail-off semantics.** Phase-3 currently doesn't dispatch byte-4 transitions to anything functional. Phase 4d makes byte 4 a binary "bail-off pressed" flag using the calibrated `bailoffThreshold()`. Is that the desired semantic (latched while the byte is above the threshold), or do we want a one-shot pulse on the rising edge?
6. **Defaults for the new settings.** Match EngineDriver's defaults exactly (proposed: 300 / 800 / 2 / 7 / 70 / `Light engine`), or pre-tune for the typical small-railroad operator (e.g. `Local freight` default scenario)?

## 7. Known limitations accepted in phase 4

- **Single-throttle only.** Phase 4 doesn't introduce multi-loco support.
- **Per-roster scenario default deferred to phase 5+** (see [research §9.2.5]).
- **Reservoir-and-line refill model from EngineDriver §4.2 is replaced with the direct lever-driven model.** Operators who want "ran out of air, must release brake to recharge" gameplay will have to wait for a hypothetical phase 5+ that simulates a virtual reservoir behind the Auto Brake — this is out of scope here because the RailDriver's physical Auto Brake gives us the real signal.
- **No tests.** Parent §4.5 / phase 5+.
- **No help-page documentation.** Parent §4.6 / phase 5+.
- **All latent issues from parent §3 / §6** still untouched.

## 8. What unlocks phase 5

- Tests (parent §4.5) — byte-parser, settings persistence round-trip, ramp scheduler determinism with a stub `ThrottleProxy`.
- Per-roster scenario default via `RosterEntry.getAttribute("raildriver.scenario")`.
- Help / documentation updates (parent §4.6).
- Optional: virtual reservoir / line model for users who want EngineDriver-style "run out of air" behaviour layered on top of the physical Auto Brake handle.
- Latent-issue fixes from parent §3 / §6.

## 9. Cross-references

- Research source: [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md).
- Parent: [`plan.md`](plan.md).
- Phase 1: [`plan-impl-phase1.md`](plan-impl-phase1.md) — connect & verify MVP.
- Phase 2: [`plan-impl-phase2.md`](plan-impl-phase2.md) — wire existing mappings, throttle-direction fix, F0/F28 redesign, slot 0..27 → F1..F28.
- Phase 3: [`plan-impl-phase3.md`](plan-impl-phase3.md) — calibration framework, visual bar UI, idle-range model, polling lifecycle decouple.
- Canonical bit-for-bit map: [`control-inventory.md`](control-inventory.md).
