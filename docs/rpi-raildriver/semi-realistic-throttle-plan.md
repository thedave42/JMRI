# RailDriver Semi-Realistic Throttle Support

> **Research source:** [`semi-realistic-throttle-info.md`](semi-realistic-throttle-info.md). All section references prefixed `[research §X]` resolve there.
> **Feature goal:** add semi-realistic throttle behaviour to the RailDriver path on a **model railroad layout**. Speed is no longer set directly from the throttle lever; instead the lever sets a target velocity and a single physics integration loop walks the live decoder speed toward it under net force `a = (F_drive − F_rr − F_brake_mech − F_brake_air − F_brake_dyn) / m_total`. Throttle position drives `F_drive`; Independent, Auto, and Dynamic brakes each contribute their own subtractive force; bail-off zeros the air-line term while held; a named-scenario picker selects the consist mass and operator-power-percent that feed the integration. The same loop runs in both directions — sign of `a` falls out of which forces are active, so accelerating, coasting, "throttle fighting brake", and stacked brakes are all the same code path with different terms turned on.

## 1. Scope

### 1.0 Physics framing — operator-controlled vs. prototype-physics forces

This is a model-railroad simulation, not a prototype simulator. The engine's force model is therefore deliberately a **two-class system**, and that distinction is load-bearing for everything below.

- **Operator-controlled forces** — drive (throttle position) and all three brake terms (mechanical / air / dynamic). The operator pushes a lever and expects the loco to respond at wall-clock human-perceptible rates. Their tunable constants are stated in **wall-clock m/s² (operator-perceived rate of velocity change)**, not in prototype frame. Defaults are scale-invariant: 1 m/s² mech brake at HO is the same 1 m/s² wall-clock decel at N or Z. The operator tunes these to taste.
- **Prototype-physics forces** — currently only rolling resistance; later may include aerodynamic drag, gradient gravity, curve resistance, journal-bearing breakaway. These follow physical laws independent of operator action. Their natural decel rates (e.g. ~0.02 m/s² for `c_rr = 0.002`) are imperceptible at 1:1 wall-clock — to be visible at human time scales on a scaled-down layout, they are multiplied by a **`physicsTimeScale` factor**, which defaults to JMRI's `layoutScale` (160 for N, 87 for HO, etc.).

Result: drop the throttle to idle on an N-scale layout and the loco visibly coasts to a stop in roughly 11 seconds wall-clock (rolling resistance × 160), not 30 minutes. Apply the indep brake fully and it stops in ~36 seconds wall-clock from 80 mph, regardless of layout scale. The two phenomena have different scaling because they have different physical and operator-perceptual semantics. Pretending otherwise is what produces unwatchable simulations.

The integration still runs in **prototype velocity** (`v_fs` in m/s prototype) so the roster speed profile, `getPhysicsMaxSpeedKmh()` cap, and the lever-derived `vTarget` continue to work in prototype terms. Time advances against wall-clock dt. The two-class distinction lives entirely in the unit conventions for the force coefficients — see §2.1 for the tick body.

### 1.1 In scope (split into stages 1–6, each independently shippable)

**Stage 1 — Refactor & off-EDT bug fix.** Pure refactoring with no new feature behaviour. Wraps every Swing-touching call in `RailDriverMenuItem.dispatchValueEvent` via `ThreadingUtil.runOnGUIEventually` (setters) or `ThreadingUtil.runOnGUIwithReturn` (getters that feed decision logic), closing the off-EDT-mutation latent issue from the existing RailDriver bring-up. Replaces the standalone `RailDriverCalibrationFrame` / `RailDriverCalibrationAction` with the unified two-tab `RailDriverSettingsFrame` (described in §2.4); the Calibration tab holds the existing visual-bar UI verbatim, the Settings tab is present but disabled (its fields land in stage 2). Migrates the Bundle key (`RdCalibrate` → `RdSettings`), updates `DebugMenu`, and bumps the calibration XML schema to `version="2"` with the `<semiRealistic>` element tolerated as absent. **No physics engine, no Jynstrument, no behaviour change for users** beyond the renamed Debug-menu entry and the (invisible) EDT bug fix. The migration of the existing `SwingUtilities.invokeLater` call site in `RailDriverMenuItem.java:244` to `ThreadingUtil.runOnGUIEventually` happens here.

**Stage 2 — Scale-aware physics integration engine + bypass switch + scenario picker + independent brake + toolbar mode toggle.** First stage where the operator can opt in and run the loco under physics. Adds the `SemiRealisticThrottleEngine` class with the **two-class force model from §1.0 baked in from day one**: drive and indep brake are operator-controlled (wall-clock m/s² decel), rolling resistance is prototype-physics (multiplied by `physicsTimeScale` to be visible at scale-time wall-clock). 50 ms tick on a worker thread, full integration body including the `F_brake_mech` term so the operator has a working brake. The Settings tab exposes the new `Physics time scale` field (default `auto` = `layoutScale`). Adds the `LoadScenario` enum and Settings-tab picker (all 6 scenarios incl. Custom), the persisted-vs-live `enabled` split (§2.3), the toolbar Jynstrument (§2.6), and the `hidDeviceAttached` re-enable for hot-plug PCS firing. Continuously-scheduled tick (no `a < 0` clamp) so the same code runs accelerating or decelerating. Brake-force terms `F_brake_air` and `F_brake_dyn` are present in the integration equation as zero-valued summands until stages 4–5. When semi-realistic mode is OFF (default), behaviour is identical to stage 1's bypass path. When ON, the throttle lever (Axis 1 above Idle High) sets `v_target`, the calibrated indep-brake position (Axis 3) drives a wall-clock-perceived `F_brake_mech`, and the integrator walks `v` toward target under the resulting net force. Defaults match a single-loco "Light engine" out of the box (see §2.4.1) so users with empty rosters get sensible behaviour with zero configuration.

**Stage 3 — Reverser interlock.** Direction-change-only-at-speed-0 interlock per [research §5]. E-Stop SPDT keeps its current `setSpeedSetting(-1)` behaviour. (EngineDriver's "soft stop button" mode is intentionally not adopted — the RailDriver's physical Independent Brake handle already gives the operator a more prototypical controlled-stop than a one-touch button would.) Independent of brake terms; only requires the engine's `v_fs` field, which exists from stage 2 onwards.

**Stage 4 — Auto brake (Axis 2) → air-line force term + bail-off (byte 4) override.** The Auto Brake lever drives `airLinePct` (Released → 0, EMG → 100, monotonic between — no derived-from-mechanical model per [research §10] item 2), which drives `F_brake_air = (airLinePct/100) · AIR_BRAKE_MAX_DECEL · m_total`. `AIR_BRAKE_MAX_DECEL` is operator-controlled (wall-clock m/s²), summed with the mechanical brake force in the integration. The bail-off switch (byte 4 transient) zeroes `F_brake_air` while held — direct level-triggered override on the air term, without affecting mechanical or dyn-brake terms. Replaces EngineDriver's reservoir-and-line refill repeaters [research §4.2] with a direct mapping (the operator's hand on the lever is the prototype).

**Stage 5 — Dynamic brake side of throttle lever (Axis 1 below Idle Low).** Below the calibrated Idle Low, the throttle lever produces `F_brake_dyn = (dynPct/100) · DYN_BRAKE_MAX_DECEL · m_loco · speedTaper(v)` where `speedTaper(v) = min(1, v / V_dyn_min)` and `V_dyn_min ≈ 5 mph` (prototype velocity threshold; not scale-compressed). `DYN_BRAKE_MAX_DECEL` is operator-controlled (wall-clock m/s²). Acts on loco mass only (dyn brake doesn't propagate through trainline air per [research §10] item 1) and tapers to zero near stop (real dyn brake fades below ~5 mph). Stacks with mechanical and air brake by simple force summation — no min/max selection, no "reinforce" special case. LED display shows `DBr` while in dyn-brake region.

**Stage 6 — ESU decoder-brake passthrough (optional, gated by user preference).** Per [research §4.3], computes brake-percent from the calibrated indep-brake position and forwards F4/F5/F6 dispatch when the user opts in. Defaults to OFF.

### 1.2 Out of scope (deferred to future work)

- **Per-roster scenario default.** This feature ships with a session-level picker; reading `RosterEntry.getAttribute("raildriver.scenario")` to override the session default is deferred per [research §9.2.5].
- **Multi-throttle support.** EngineDriver runs up to 6 locos in parallel; we keep the existing single-throttle assumption from the RailDriver bring-up phases.
- **Aerodynamic drag**, gradient gravity, curve resistance, journal-bearing breakaway. These would all be additional **prototype-physics** terms with `× physicsTimeScale` semantics, slotted into the same integration equation as rolling resistance. Out of scope for stage 2 but the architecture admits them cleanly.
- **Deeper integration parameters configurable via UI.** The Settings tab exposes `BRAKE_MAX_DECEL`, `AIR_BRAKE_MAX_DECEL`, `DYN_BRAKE_MAX_DECEL`, `DYN_BRAKE_V_MIN`, `ROLLING_RESISTANCE_COEFF`, `physicsTimeScale`, and per-scenario `additionalWeightTonnes` / `driverPowerPercent` / loco mass / power / TE overrides as user-editable fields (in scope). Lower-level integration parameters (50 ms slice time, gear-pause thresholds 15/27/41 mph and 3.5 s coast duration) stay hardcoded (out of scope).
- **Per-force `physicsTimeScale` overrides.** Currently a single scalar applies to all prototype-physics terms (only rolling resistance today). If future force terms need different time compression, the single field grows into a per-term map. Deferred until a second prototype-physics term lands.
- **EngineDriver's `Stop` button and its four behaviour modes.** The Stop button is an Android-touch UX device — useful when your only inputs are screen taps. On a RailDriver console the operator already has E-Stop (hard) and the Independent Brake handle (controlled) within reach. None of EngineDriver's four stop modes (`THROTTLE_STOP`, `THROTTLE_STOP_BRAKE_FULL`, `SPEED_ZERO`, `SPEED_ZERO_BRAKE_ZERO`) is adopted; the existing E-Stop SPDT keeps its current behaviour.
- **Tests.** Parent §4.5. Deferred.
- **Help / documentation updates.** Parent §4.6. Deferred.
- **Cross-platform verification** — community testers, not in scope.
- **Latent issues from parent §3 / §6** other than the off-EDT mutation, which is fixed as part of stage 1.

## 2. Architecture

### 2.1 New class: `SemiRealisticThrottleEngine`

Single-throttle engine. Owned by `RailDriverMenuItem`. Lifecycle parallels the polling thread: created lazily in `attachThrottleWindow` after `activeThrottleFrame` is set; disposed in `propertyChange`'s `"ancestor"` case alongside the `throttleDispatcher` deregistration.

The engine is built around a single physics integration loop that runs on a dedicated worker thread at a fixed 50 ms slice. The loop computes net force per the **two-class force model from §1.0** — operator-controlled forces in wall-clock m/s² perceived rate, prototype-physics forces multiplied by `physicsTimeScale` to be visible at scale-time wall-clock — integrates velocity, quantises to a DCC speed step, and posts the result to the EDT for `setSpeedSetting`. **There is no separate "accel path" and "decel path"** — sign of `a` falls out of which forces are active in any given slice.

```java
public final class SemiRealisticThrottleEngine {
    // — Mode —
    private volatile boolean enabled;            // false => bypass (phase-3 direct setSpeedSetting path)

    // — Settings (loaded from calibration XML <semiRealistic> subtree) —
    private final SemiRealisticSettings settings;

    // — Physics parameters (resolved per session from scenario + roster overrides) —
    //   Operator-controlled force coefficients are stated in WALL-CLOCK m/s² perceived
    //   rate (or convert to such via mass × decel = N below). Prototype-physics
    //   coefficients are stated dimensionlessly or in physical units (kg, W, N) and
    //   are scaled by physicsTimeScale in the tick body to be visible at scale-time
    //   wall-clock. See §1.0 for the framing.
    private volatile float locoMassKg;            // prototype mass (kg). Used for F = m·a calculations.
    private volatile float locoPowerW;            // prototype power (W). Drives F_drive at high speed.
    private volatile float locoTractiveEffortN;   // prototype tractive effort (N). Drives F_drive at low speed.
    private volatile float additionalMassKg;      // scenario-driven consist mass (kg)
    private volatile float driverPowerPct;        // 0.0..1.0, scenario-driven aggressiveness
    private volatile float rollingResistanceCoeff;// c_rr, dimensionless prototype-physics coefficient. Default 0.002.
    private volatile float brakeMaxDecel;         // wall-clock m/s² decel @ 100% indep brake. Default 1.0.
    private volatile float airBrakeMaxDecel;      // wall-clock m/s² decel @ 100% air line.    Default 1.5.
    private volatile float dynBrakeMaxDecel;      // wall-clock m/s² decel @ 100% dyn brake (peak, before taper). Default 0.4.
    private volatile float dynBrakeVMinMps;       // prototype velocity threshold (m/s) below which dyn brake tapers to zero.
    private volatile float physicsTimeScale;      // multiplier applied to prototype-physics force terms. Default = layoutScale.
    private volatile float layoutScaleRatio;      // 87 for HO, 160 for N, etc. Used for speed-profile unit conversion only.

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
float brake   = indepBrakeFraction;
float air     = bailoffPressed ? 0.0f : airLineFraction;
float dyn     = leverDynBrakeFraction;
boolean drive = (leverThrottleStep > 0) && (v_fs >= 0);

// 2. Compute forces — TWO-CLASS FORCE MODEL (see §1.0)
float massTotal = locoMassKg + additionalMassKg;
float P_avail   = locoPowerW * driverPowerPct * powerExp(scenario);
float TE_avail  = locoTractiveEffortN * driverPowerPct;
float v_guard   = Math.max(0.01f, v_fs);

// Operator-controlled forces — produce wall-clock-perceived deceleration directly.
// F = (wall-clock m/s² target) × m_total, so a = F/m_total comes out in wall-clock m/s².
float F_drive   = drive ? Math.min(TE_avail, P_avail / v_guard) : 0.0f;       // operator-controlled, prototype N → wall-clock m/s²
float F_brakeM  = brake * brakeMaxDecel    * massTotal;                       // operator-controlled
float F_brakeA  = air   * airBrakeMaxDecel * massTotal;                       // operator-controlled
float taper     = Math.min(1.0f, v_fs / dynBrakeVMinMps);
float F_brakeD  = dyn * dynBrakeMaxDecel * locoMassKg * taper;                // operator-controlled, loco mass only

// Prototype-physics forces — scaled by physicsTimeScale so they're visible at wall-clock.
// c_rr · m · g = real prototype rolling-resistance force. Multiplying by physicsTimeScale
// makes the resulting deceleration physicsTimeScale times faster wall-clock — i.e., a
// 30-minute prototype coast becomes (30 min)/physicsTimeScale wall-clock. With the default
// physicsTimeScale = layoutScale (160 for N), an N-scale 80 mph coast is ~11 s wall-clock.
float F_rr      = rollingResistanceCoeff * massTotal * 9.80665f * physicsTimeScale;

// 3. Integrate (no clamp on sign of a) — a is in wall-clock m/s²
float a   = (F_drive - F_rr - F_brakeM - F_brakeA - F_brakeD) / massTotal;
v_fs     += a * 0.050f;
if (v_fs < 0.0f)    v_fs = 0.0f;
if (v_fs > vCap_fs) v_fs = vCap_fs;              // hard cap: roster max-speed wins
if (Math.abs(v_fs - vTarget) < epsilon && Math.signum(a) != 0) v_fs = vTarget;

// 4. Quantise to DCC step via roster speed profile (or linear fallback) and emit
int dccStep = velocityToDccStep(v_fs);
ThreadingUtil.runOnGUIEventually(() -> throttle.setSpeedSetting(dccStep / 126.0f));
```

`powerExp(scenario)` returns `v^0.85` for steam locomotives and the linear `driverPowerPct` for everything else; gear-pause logic for mechanical-transmission locos coasts (drive force zero) for 3.5 s at 15/27/41 mph crossings.

`vCap_fs` is the per-attach roster-derived speed cap, computed once at engine attach and on every settings reload from `Math.max(0.0f, RosterEntry.getPhysicsMaxSpeedKmh()) / 3.6f`. When the field is 0 (the JMRI default for a fresh roster entry), `vCap_fs` is set to `Float.POSITIVE_INFINITY` so the cap is effectively disabled and the integration is bounded only by the scenario's design top speed via the linear `velocityToDccStep` fallback. When the field is populated, both the lever-derived target and the integrated `v_fs` are clamped — a yard goat with `maxSpeedKmh = 30` cannot exceed 30 km/h regardless of throttle position or scenario.

`physicsTimeScale` is resolved at engine attach and on every settings reload from the operator's Settings-tab override (numeric value), or from the `auto` sentinel, which resolves to JMRI's `SignalSpeedMap.getLayoutScale()` (= 160 for N, 87 for HO, 220 for Z, etc.). Validation range: 0.1 to 1000. Default is `auto`. An operator targeting prototype 1:1 simulation explicitly sets `physicsTimeScale = 1`.

Roster physics fields override scenario defaults whenever `> 0`. With an empty roster (the default JMRI install), all physics fields read 0 from `RosterEntry`, and the engine substitutes the active scenario's defaults — which are Light-engine values in the default scenario, so a brand-new install gets prototype-realistic single-loco behaviour with zero configuration.

#### Worked example: wall-clock outcomes at default constants

To make the operator-perceived behaviour explicit, here are the wall-clock timings at default constants for two common scales:

| Phenomenon | Force class | Formula | HO (`physicsTimeScale = 87`) | N (`physicsTimeScale = 160`) |
|---|---|---|---:|---:|
| Coast 80 mph → 0 (`c_rr = 0.002`) | prototype-physics | `v / (c_rr · g · S)` | ~21 s | ~11 s |
| Mech brake 80 mph → 0 (`BRAKE_MAX_DECEL = 1.0`) | operator-controlled | `v / decel` | ~36 s | ~36 s |
| Air brake EMG 80 mph → 0 (`AIR_BRAKE_MAX_DECEL = 1.5`) | operator-controlled | `v / decel` | ~24 s | ~24 s |
| Dyn brake full 60 mph → 5 mph (`DYN_BRAKE_MAX_DECEL = 0.4`) | operator-controlled | `(v − v_min) / decel` | ~62 s | ~62 s |
| Light Engine accel 0 → 80 mph | operator-controlled | depends on F_drive curve | ~13 s | ~13 s |

Operator-controlled timings are scale-invariant by design. Coast is the only column that varies with `physicsTimeScale`. An operator who explicitly sets `physicsTimeScale = 1` to simulate a full-scale prototype gets a 30-minute coast — which is the prototype-physics-correct answer; the operator has signalled they want that.

### 2.2 Threading model

Three threads are involved. The EDT-discipline boundary is strict: **no Swing-touching code runs off the EDT, anywhere.** The pre-existing off-EDT mutation in the RailDriver bring-up code (parent §3 / §6) is fixed as part of stage 1 — see §3.1 for the wrapping work.

| Thread | What it does | What it must NOT do |
|---|---|---|
| **Polling thread** (`RailDriver`, existing) | Reads HID reports, fires `RawByte` and `Value` PCS events. `dispatchValueEvent` runs here as a PCS listener. | Touch any Swing component or call any method that reaches Swing (`setSpeedSetting`, `setIsForward`, `setFunction`, `JMenuItem.setEnabled`, etc.). |
| **Engine worker** (`RailDriver-SemiRealistic-Physics`, new) | Owned by the engine's single-thread `ScheduledExecutorService`. Runs the 50 ms physics integration tick. **Reads input snapshot, computes forces, integrates `v`, quantises to DCC step here** — all the math runs on this thread. | Touch Swing. |
| **EDT** (Swing's own thread) | All Swing-touching work: `setSpeedSetting`, `setIsForward`, `setFunction`, LED updates that route through Swing components, status-label changes, etc. | Block on the worker or polling thread (no `invokeAndWait`; would deadlock). |

The contract:

- `dispatchValueEvent` (polling thread) updates the engine's input fields (`leverThrottleStep`, `indepBrakeFraction`, `airLineFraction`, `leverDynBrakeFraction`, `bailoffPressed`, `direction`, `scenario`) — all `volatile` — and computes a fresh `vTarget_fs` from the lever calibration. **No Swing calls in the polling-thread path.** The integration task is permanently scheduled at fixed rate (50 ms); it picks up the new inputs on its next tick. **No cancel/reschedule churn** — the inputs are just `volatile` writes the worker reads on the next tick.

- The integration task's body runs on the engine worker. It computes net force locally and integrates velocity:
  ```java
  float a = (F_drive - F_rr - F_brakeM - F_brakeA - F_brakeD) / massTotal;
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

- The volatile input fields (`leverThrottleStep`, `indepBrakeFraction`, `airLineFraction`, `leverDynBrakeFraction`, `bailoffPressed`, `direction`) are written by the polling thread and read by the worker — single-writer, single-reader, no interleaved compound ops, so volatile semantics suffice for those fields. The volatile physics-parameter fields (`locoMassKg`, `locoPowerW`, `locoTractiveEffortN`, `additionalMassKg`, `driverPowerPct`, `rollingResistanceCoeff`, `brakeMaxDecel`, `airBrakeMaxDecel`, `dynBrakeMaxDecel`, `dynBrakeVMinMps`, `physicsTimeScale`, `scenario`) are written by the EDT (in `rebuildPhysics` after Save / scenario-change / attach) and read by the worker — same single-writer-single-reader pattern. The worker reads the relevant fields at the start of each tick and uses the read values for the rest of the tick body, so a mid-tick settings change can't produce torn calculations. `v_fs` is a different case: it is normally touched only by the worker thread (no sync needed for tick-to-tick updates), **but** the mode-switch handover (§2.3) writes `v_fs` from the EDT inside `engine.setLiveEnabled()`, and the reverser interlock (§3.3) reads `v_fs` from the polling thread. All non-worker accesses to `v_fs` (write from `engine.setLiveEnabled`, read from polling-thread Axis 0 dispatch) go through the engine's `synchronized` block; `v_fs` is also declared `volatile` so the polling-thread read sees the latest worker write without entering the monitor.

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

The Settings tab is grouped into three sections to make the §1.0 force classification visible to the operator:

**Mode**
- **Enable semi-realistic mode** checkbox (the master switch).

**Loco & consist** (operator-perceptible / prototype-realistic loco selection)
- **Scenario:** dropdown — `Light engine` (default) / `Switcher` / `Local freight` / `Through freight` / `Unit train` / `Custom` (with the per-loco override fields below editable when `Custom` is selected).
- **Loco mass (t):** numeric override; default = scenario's loco mass; "auto" sentinel uses `RosterEntry.getPhysicsWeightKg()` when populated.
- **Loco power (kW):** numeric override; default = scenario's loco power; "auto" sentinel uses `RosterEntry.getPhysicsPowerKw()` when populated.
- **Loco tractive effort (kN):** numeric override; default = scenario's loco TE; "auto" sentinel uses `RosterEntry.getPhysicsTractiveEffortKn()` when populated.
- **Additional consist mass (t):** numeric, scenario-provided default; the operator can tune up or down for one-off heavy/light trains without changing scenario.
- **Driver power (%):** numeric 0–100, scenario-provided default; throttles applied power and TE.

**Operator-controlled brake decel rates** (wall-clock m/s² perceived rate, scale-invariant)
- **Mechanical brake max decel (m/s² wall-clock):** numeric, default 1.0. Force applied per 100 % indep brake = this × `m_total`. Operator-controlled per §1.0; default produces ~36 s 80 mph → 0 stops at any layout scale.
- **Air brake max decel (m/s² wall-clock):** numeric, default 1.5. Force applied per 100 % air-line setting = this × `m_total`. Operator-controlled.
- **Dynamic brake max decel (m/s² wall-clock):** numeric, default 0.4. Force applied per 100 % dyn-brake setting (× `m_loco` only, not consist), before speed taper. Operator-controlled.
- **Dynamic brake taper threshold (mph prototype):** numeric, default 5. Prototype velocity threshold (not wall-clock); below this, dyn-brake force scales linearly to zero.

**Prototype-physics terms** (× `physicsTimeScale` to be visible at scale-time wall-clock)
- **Rolling resistance coefficient (c_rr, dimensionless):** numeric, default 0.002. Prototype-physics term per §1.0; coast-down decel = `c_rr · g · physicsTimeScale` wall-clock m/s².
- **Physics time scale:** numeric or `auto`, default `auto`. Multiplier applied to all prototype-physics force terms (currently rolling resistance only) so they're visible at scale-time wall-clock. `auto` resolves to JMRI's `SignalSpeedMap.getLayoutScale()` at attach time (160 for N, 87 for HO, etc.). Numeric range 0.1–1000. Set to 1 for prototype 1:1 simulation.

**Decoder integration**
- **Decoder-brake mode:** dropdown `None` / `ESU` (and ESU-only sub-fields when ESU is selected — F-numbers + thresholds).

**Reset to defaults** button (settings-tab-scoped — restores only the semi-realistic fields to the active scenario's defaults plus the global brake/coast/scale defaults; does not touch calibration values).

#### 2.4.1 Scenario defaults

Light-engine values are also the all-scenarios fallback when roster fields are absent — so a fresh JMRI install with an empty roster gets prototype-realistic single-loco behaviour out of the box.

| Scenario | Loco mass | Loco power | Loco TE | Additional consist mass | Driver power |
|---|---:|---:|---:|---:|---:|
| **Light engine (default)** | 130 t | 2200 kW | 350 kN | 0 t | 100 % |
| Switcher | 100 t | 1100 kW | 200 kN | 200 t | 80 % |
| Local freight | 130 t | 2200 kW | 350 kN | 1500 t | 90 % |
| Through freight | 130 t | 2200 kW | 350 kN | 5000 t | 100 % |
| Unit train | 130 t | 2200 kW | 350 kN | 10 000 t | 100 % |
| Custom | (operator) | (operator) | (operator) | (operator) | (operator) |

Scenario-independent defaults, applied to every scenario unless individually overridden on the Settings tab:

| Constant | Default | Force class | Notes |
|---|---:|---|---|
| `BRAKE_MAX_DECEL` | 1.0 m/s² wall-clock | operator-controlled | Indep brake at 100 % decel rate |
| `AIR_BRAKE_MAX_DECEL` | 1.5 m/s² wall-clock | operator-controlled | Auto brake at EMG decel rate |
| `DYN_BRAKE_MAX_DECEL` | 0.4 m/s² wall-clock | operator-controlled | Dyn brake peak (before speed taper) |
| `DYN_BRAKE_V_MIN` | 5 mph prototype | — | Speed below which dyn brake tapers; prototype frame |
| `c_rr` | 0.002 | prototype-physics | Dimensionless; effective decel = `c_rr · g · physicsTimeScale` |
| `physicsTimeScale` | `auto` (= layoutScale) | — | Multiplier on all prototype-physics force terms |

Order of precedence resolving each loco-physics field (mass / power / TE) at attach time and on every Settings reload:
1. Custom-scenario operator override (Settings tab) if non-blank.
2. `RosterEntry.getPhysicsWeightKg()` / `getPhysicsPowerKw()` / `getPhysicsTractiveEffortKn()` if `> 0`.
3. Active scenario's default from the table above.

`physicsTimeScale` resolution:
1. Numeric override on Settings tab if set.
2. `auto` sentinel resolves to `SignalSpeedMap.getLayoutScale()` at attach (= 87 / 160 / 220 etc.); falls back to 87 (HO) if JMRI's scale lookup throws.

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
        <!-- "auto" = use roster value if > 0 else scenario default; numeric = explicit override -->
        <locoMassKg>auto</locoMassKg>
        <locoPowerKw>auto</locoPowerKw>
        <locoTractiveEffortKn>auto</locoTractiveEffortKn>
        <additionalWeightTonnes>0</additionalWeightTonnes>
        <driverPowerPercent>100</driverPowerPercent>
        <!-- Prototype-physics term: dimensionless coefficient -->
        <rollingResistanceCoeff>0.002</rollingResistanceCoeff>
        <!-- Operator-controlled brake max decels: wall-clock m/s² perceived rate -->
        <brakeMaxDecel>1.0</brakeMaxDecel>
        <airBrakeMaxDecel>1.5</airBrakeMaxDecel>
        <dynBrakeMaxDecel>0.4</dynBrakeMaxDecel>
        <!-- Prototype velocity threshold (mph), not wall-clock; not scale-compressed -->
        <dynBrakeVMinMph>5</dynBrakeVMinMph>
        <!-- Multiplier applied to all prototype-physics force terms.
             "auto" = JMRI SignalSpeedMap.getLayoutScale() at attach;
             numeric = explicit override (1 = prototype 1:1; 160 = N scale, etc.) -->
        <physicsTimeScale>auto</physicsTimeScale>
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

Schema migration: existing files (`version="1"`) load cleanly because the loader's existing tolerance for missing elements treats `<semiRealistic>` as absent ⇒ defaults (= disabled, which is the bring-up-era fallback ⇒ no behaviour change for legacy files). Within `version="2"`, individual children that are missing or unparseable also fall back to defaults — including `<physicsTimeScale>`, which defaults to `auto` and thus to whatever `SignalSpeedMap.getLayoutScale()` reports at attach. Files written before this design (which never persisted `<physicsTimeScale>`) load with `auto` semantics and therefore pick up scale-aware physics on next launch — which is the intended behaviour change. The `version` attribute exists so a later schema-3 change can branch cleanly.

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

### 3.2 Stage 2 — Scale-aware physics engine + bypass + scenario picker + independent brake + toolbar mode toggle

**Goal:** first stage where `liveEnabled = ON` produces a usable feature on a model railroad layout. The throttle lever sets `vTarget_fs` and the integrator walks `v_fs` toward it under the **two-class force model from §1.0** — operator-controlled forces (drive, indep brake) in wall-clock m/s² perceived rate, prototype-physics forces (rolling resistance) multiplied by `physicsTimeScale` to be visible at scale-time wall-clock. The calibrated independent-brake lever (Axis 3) provides a working `F_brake_mech` term so the operator can stop. The scenario picker is wired so the operator can change consist mass / driver power. The Settings tab exposes `physicsTimeScale` (default `auto` = JMRI layout scale) so the operator can tune the prototype-physics compression. The toolbar Jynstrument is auto-installed for fast session-level mode toggling.

The integration body uses `F_drive = min(TE_avail, P/v_guard)` (with `P` and `TE` scaled by `driverPowerPct` and the steam-power exponent) — operator-controlled, no time compression; `F_rr = c_rr · m_total · g · physicsTimeScale` — prototype-physics, time-compressed; and `F_brake_mech = indepBrakeFraction · brakeMaxDecel · m_total` — operator-controlled, no time compression. The remaining brake terms `F_brake_air` and `F_brake_dyn` are present in the equation as zero-valued summands until stages 4–5. Mechanical-transmission locos coast at 15/27/41 mph crossings (3.5 s gear pause; mph thresholds are prototype values, the 3.5 s coast duration is wall-clock). The integration runs symmetrically (no `a < 0` clamp) so accel and decel are the same code path with different forces active.

Velocity ↔ DCC step conversion uses `RosterSpeedProfile.getSpeed(throttleStep, isForward)` when a calibrated speed profile exists, falling back to a linear `dccStep = round(v_fs / vMax_fs · 126)` mapping (where `vMax_fs` is the scenario's design top speed) when it doesn't. With the linear fallback the loco's prototype-realistic behaviour is preserved; only the absolute speed-step calibration is approximate.

**New / modified files:**

*New:*
- `java/src/jmri/util/usb/SemiRealisticThrottleEngine.java` — engine with the integration tick / `ScheduledExecutorService` / DccThrottle access. Throttle and indep-brake paths fully wired with the §1.0 two-class force model from day one. `physicsTimeScale` resolved on attach and on every settings reload from JMRI's `SignalSpeedMap.getLayoutScale()` (when `auto`) or the operator override. Air-brake / dyn-brake input fields stay zero until stages 4–5. Worker thread does the math; EDT does the `setSpeedSetting`.
- `java/src/jmri/util/usb/SemiRealisticSettings.java` — settings POJO with load + save methods, mirroring `RailDriverCalibration`'s structure. Holds the operator-controlled brake decels (`brakeMaxDecel`, `airBrakeMaxDecel`, `dynBrakeMaxDecel` — wall-clock m/s²), the prototype-physics constants (`rollingResistanceCoeff`, `physicsTimeScale` — with `null` = auto), `dynBrakeVMinMph` (prototype velocity threshold), per-scenario defaults table, the `persistedEnabled` and `liveEnabled` fields, and (placeholder, used by stage 6) the `decoderBrakeMode` enum.
- `java/src/jmri/util/usb/LoadScenario.java` — enum with the six scenarios from §2.4.1 and their default mass/power/TE/consist/driver values.
- `java/src/jmri/util/usb/SemiRealisticSettingsPanel.java` — the Settings tab content. Replaces stage 1's placeholder. Implements the `isDirty / addDirtyChangeListener / validateAndApplyTo / resetToFile` contract from §2.4. Field grouping per §2.4 (Mode / Loco & consist / Operator-controlled brake decels / Prototype-physics terms / Decoder integration). Disables fields based on the `Enable semi-realistic mode` checkbox; switches loco-mass/power/TE rows between read-only "auto" display and editable when scenario = Custom. Decoder-brake-mode dropdown is present but has only `None` available (the `ESU` option is enabled in stage 6). `physicsTimeScale` field accepts numeric override or empty/blank for auto.
- `jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/RailDriverModeToggle.py` — the Jynstrument from §2.6. ~80 lines of Jython following the pattern of existing toolbar Jynstruments (`DCCThrottle.jyn`, `WiimoteThrottle.jyn`). Implements `init()`, `quit()`, `getExpectedContextClassName()` (returns `"jmri.jmrit.throttle.ThrottleWindow"`), creates a single `JButton` showing the on/off/binding icon, registers a Java `PropertyChangeListener` on `RailDriverMenuItem` for the `"liveEnabledChanged"` / `"railDriverConnected"` / `"activeThrottleFrame"` / `"attachInProgress"` events (subscribes to `liveEnabledChanged`, NOT `persistedEnabledChanged` — the Jynstrument shows the live session value, not the persisted value), tracks an internal `pendingSessionToggle` flag for the State 2 → State 4 deferred toggle described in §2.6, and builds a one-item `JPopupMenu` with `Settings...` invoking `RailDriverSettingsAction`. `quit()` is a pure listener-deregistration hook with no persistence side effects.
- `jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn/icons/raildriver-on.png`, `raildriver-off.png`, and `raildriver-binding.png` — three 24×24 (or whatever size matches existing toolbar icons; check `resources/icons/throttles/*.png` for the convention) icons in the Jynstrument folder. The "binding" icon is shown during the State 2.5 attach-in-progress window.

*Modified:*
- `java/src/jmri/util/usb/RailDriverCalibration.java` — populate the `<semiRealistic>` subtree on save and on load (§2.5 schema, including the new `<physicsTimeScale>` element). Hold a `SemiRealisticSettings` field accessor.
- `java/src/jmri/util/usb/RailDriverMenuItem.java`:
   1. Instantiate the engine in `attachThrottleWindow`; route `dispatchValueEvent` Axis 1 dispatch through the engine when `settings.liveEnabled`.
   2. Add the Axis 3 case to `dispatchValueEvent`: compute `indepBrakeFraction = clamp((calibratedFullRelease - byteValue) / (calibratedFullRelease - calibratedFullApplication), 0, 1)`; set `engine.indepBrakeFraction`. The integration tick reads this volatile field on each slice and contributes `F_brake_mech = indepBrakeFraction · brakeMaxDecel · m_total` to net force. No regime detection, no "throttle fighting brake" branch — when both throttle and brake are applied, the integrator sums forces and the sign of `a` falls out.
   3. Extend `reloadCalibration()` to notify the engine of new semi-realistic settings (or add a sibling `reloadSemiRealisticSettings()` if the engine needs distinct hooks — implementation detail).
   4. Add `isSemiRealisticLiveEnabled()`, `isSemiRealisticPersistedEnabled()`, `setSemiRealisticEnabledSessionOnly(boolean)`, `isRailDriverConnected()`, `getActiveThrottleFrame()`, `isAttachInProgress()`, `requestAttachToThrottle(ThrottleFrame)`, `addSettingsListener(PropertyChangeListener)`, `removeSettingsListener(PropertyChangeListener)` per §2.6. `setSemiRealisticEnabledSessionOnly` snapshots the old `liveEnabled` value, mutates it in memory only (no XML write), runs the §2.3 mode-switch handover via `engine.setLiveEnabled`, and fires `"liveEnabledChanged"`. The Settings-tab Save flow lives in `RailDriverSettingsFrame.doSaveOrApply` — it builds a fresh `RailDriverCalibration working`, has both tabs write into it via their `validateAndApplyTo` methods (the Settings tab sets `working.semiRealistic.persistedEnabled := checkbox` and `working.semiRealistic.liveEnabled := working.semiRealistic.persistedEnabled`; the Calibration tab uses `copyCalibrationFieldsFrom` so it doesn't clobber the `<semiRealistic>` subtree), then calls `working.save(file)` and `mi.reloadCalibration()`. `reloadCalibration` snapshots the OLD `persistedEnabled`/`liveEnabled` from the in-memory calibration *before* replacing it, reloads from disk, pushes the new settings to the engine, and fires `"persistedEnabledChanged"` / `"liveEnabledChanged"` for the values that changed. `requestAttachToThrottle` is a no-op if the requested frame is already the bound one; otherwise sets an internal `attachInProgress = true` and fires `"attachInProgress"` (true), then schedules the bind via `ThreadingUtil.runOnGUIEventually`. The bind path (`ensureDeviceAndPolling()` then `attachThrottleWindow()` against the requested frame) fires `"activeThrottleFrame"` on success and finally `"attachInProgress"` (false) once the activeThrottleFrame event has been dispatched. The existing `HidServicesListener` callbacks are extended for VID/PID hot-plug awareness: `hidDeviceAttached` (currently a commented-out no-op at `RailDriverMenuItem.java:539–546`, originally gated on a now-removed `invokeOnMenuOnly` flag) is re-enabled to fire `firePropertyChange("railDriverConnected", false, true)` on VID/PID match **without** auto-calling `setupRailDriver()` — the Debug-menu workflow keeps owning the polling lifecycle, so cold-plug behaviour is unchanged for users without the Jynstrument; only the Jynstrument's icon state is affected. `hidDeviceDetached` (which already nulls `hidDevice` on VID/PID match at line 552–557) gains a sibling `firePropertyChange("railDriverConnected", true, false)` call.
   5. Auto-install the Jynstrument at the end of `attachThrottleWindow`'s success path, idempotent across repeat calls within a single attach. Helper `private static boolean hasJynstrumentInstalled(ThrottleWindow tw, String classNameSuffix)` is a recursive descent over `tw.getContentPane().getComponents()` (`ThrottleWindow.throttleToolBar` is private with no public getter, verified at `ThrottleWindow.java:57`; the recursive walk inspects every contained `JToolBar` for `Jynstrument` instances and matches by class-name suffix). The walk extends a precedent established inside `ThrottleWindow` itself: the close-handler at `ThrottleWindow.java:160–167` and the save-Jynstruments code at `ThrottleWindow.java:801–810` both iterate `throttleToolBar.getComponents()` and `instanceof Jynstrument`-check each child; our walk only adds the recursive descent because we lack direct access to the toolbar reference. Auto-install is gated **only** by `hasJynstrumentInstalled`. **There is no `<jynstrumentAutoInstallSuppressed>` flag, no `seenQuitThisSession` flag, and no other in-memory or on-disk state for the toolbar's presence.** Saved-layout-XML restoration (`ThrottleWindow.java:800–865`) runs before `attachThrottleWindow`, so a saved layout that includes the Jynstrument is detected by the walk and auto-install becomes a no-op for that session. A saved layout that omits the Jynstrument restores the empty toolbar; auto-install then re-adds it — meaning "save throttle layout with toggle removed" by itself is **not** sufficient to keep the toggle out across `Debug → RailDriver Throttle (built in)` re-clicks, because the auto-install treats a re-click as a deliberate operator action equivalent to the first attach. Operators who want the removal to be sticky across re-clicks must use the deferred-future "Restore toolbar toggle" workflow (currently: drag-install only) to manage the toggle's presence explicitly. The Jynstrument's `quit()` hook is solely a session-local listener-deregistration cleanup — see §2.6's Jython code sketch.
- `java/src/jmri/util/usb/Bundle.properties` — no changes in stage 2. Settings-tab labels are kept as hardcoded English literals in `SemiRealisticSettingsPanel.java`, matching the existing pattern in `CalibrationTabPanel.java`. Externalising both panels' labels to Bundle keys is deferred to a future i18n pass — see §8 future work.

**Acceptance:**
1. The Settings tab is now functional: scenario picker shows all 6 scenarios; selecting `Custom` enables loco-mass/power/TE/consist/driver-power fields; physics-constant fields (mechanical brake decel, rolling resistance, physics time scale, etc.) are editable and validated per §6's validation ranges. The `Physics time scale` field accepts numeric or blank (= auto).
2. Mode OFF: throttle behaves exactly as in stage 1 (operator-perceptibly identical).
3. Mode ON, default Light-engine scenario, empty roster, indep brake at Full Release: moving the throttle lever from idle to full speed produces a visible ramp on the loco. Time from 0 to top speed is on the order of **30–60 s wall-clock** (operator-controlled drive force; scale-invariant). **No snap, no jitter, no missed slots** during a 5-minute lever-sweep session.
4. Mode ON, indep brake at Full Application + throttle at zero, Light-engine defaults: loco decelerates at **1.0 m/s² wall-clock** (= `BRAKE_MAX_DECEL`). Time from 80 mph (35.8 m/s prototype) to 0 ≈ **36 s wall-clock at any layout scale** (operator-controlled brake; not scale-compressed).
5. Mode ON, indep brake mid-travel while throttle is at full: integrator computes net force in wall-clock m/s². With Light-engine defaults at low speed, F_drive ≈ 350 kN, F_brake_mech at 50 % = 65 kN. Net force = +285 kN, loco continues to accelerate at ~2.2 m/s² wall-clock. At higher speeds where `F_drive = P/v` drops below `F_brake_mech`, loco settles at the equilibrium speed. **Emergent behaviour, not coded as a special case.**
6. Mode ON, releasing the indep brake while at speed: `F_brake_mech = 0` on the next tick; integrator resumes accelerating toward the lever's target.
7. Switching scenarios mid-accel (e.g. `Light engine` → `Unit train`): the integrator picks up the new mass on the next 50 ms tick; accel rate jumps immediately because `a = F_drive/m_total` recomputes from the new total mass.
8. With roster physics fields populated (`getPhysicsWeightKg() > 0` etc.), those values override the scenario defaults per §2.4.1's precedence rule. Operator can switch scenarios to layer in different consist masses while the loco's own physics stays roster-driven. The `getPhysicsMaxSpeedKmh()` cap clamps both `vTarget` and integrated `v_fs`.
9. Mode OFF → ON at speed: engine reads `throttle.getSpeedSetting()`, converts to `v_fs`, integrates smoothly toward lever target — no snap (per §2.3 handover contract).
10. Mode ON → OFF at speed: engine pauses; next byte change writes lever-derived value directly via `setSpeedSetting()` (operator-accepted snap if lever is far from `v_fs`).
11. **Coast-down — scale-aware acceptance.** Mode ON, dropping the throttle to idle while at speed (no brake input): the loco coasts down on rolling resistance alone at `decel = c_rr · g · physicsTimeScale` wall-clock m/s². At `physicsTimeScale = auto` (= layoutScale):
    - HO (`physicsTimeScale = 87`): 80 mph → 0 in **~21 s wall-clock**, decel ≈ 1.71 m/s².
    - N  (`physicsTimeScale = 160`): 80 mph → 0 in **~11 s wall-clock**, decel ≈ 3.14 m/s².
    Setting `physicsTimeScale = 1` (operator opts into prototype 1:1 simulation) reverts to ~30-minute coast-down — that is the prototype-physics-correct answer; the operator has signalled they want it.
12. The toolbar mode-toggle Jynstrument auto-installs on first `Debug → RailDriver Throttle (built in)` click. The icon shows the current **live** `enabled` state. Clicking it flips the live state **for this session only** (no XML write). The Settings tab's `Enable semi-realistic mode` checkbox shows the **persisted** value (last-saved or default), independent of any session-only Jynstrument toggles. Right-clicking the Jynstrument shows a `Settings...` item that opens the unified Settings frame. To make a session-level Jynstrument change persist, the operator opens the Settings window, sets the checkbox to match, and Saves.
13. The Jynstrument is idempotent on repeat attaches within a session — opening the throttle, closing it, and re-opening via the Debug menu does NOT add a second copy of the toggle to the toolbar.
14. If the operator explicitly removes the Jynstrument from the toolbar (right-click → Quit), it stays removed for the rest of the throttle window's lifetime. Re-clicking `Debug → RailDriver Throttle (built in)` triggers `attachThrottleWindow()` again, which **does** re-add the toggle — this is treated as a deliberate operator action equivalent to a fresh attach.
15. RailDriver hot-plug: unplugging the device fires `"railDriverConnected"` (false), greying the Jynstrument; replugging fires `"railDriverConnected"` (true), un-greying it. The Debug-menu lifecycle is unaffected.
16. Pre-existing XML files (schema `version="2"` from stage 1, no `<semiRealistic>` subtree) load cleanly — semi-realistic fields populate from defaults (disabled, Light engine, `physicsTimeScale = auto`); saving produces a `version="2"` file with the new `<semiRealistic>` subtree.

### 3.3 Stage 3 — Reverser interlock

**Goal:** [research §5] direction can only change at speed 0.

**Modified:** `dispatchValueEvent` Axis 0 case to suppress `setIsForward(...)` when `settings.liveEnabled && engine.v_fs > epsilon`. E-Stop SPDT keeps `setSpeedSetting(-1)`. Reverser to NEUTRAL at any speed forces a coast-down per [research §3.3] (engine treats `v_target = 0` and zero `F_drive`; loco decelerates under `F_rr` plus any active brakes).

The interlock consults `engine.v_fs` (the engine's view of current velocity) rather than `throttle.getSpeedSetting()`. The two are consistent in semi-realistic mode — `v_fs` is the value just integrated into `setSpeedSetting()` on the previous tick — but `v_fs` is a `volatile` field on the engine, readable from the polling thread without crossing the EDT. The `settings.liveEnabled` guard ensures the field is only consulted while the engine is actively maintaining it (per the §2.3 handover contract: `v_fs` is set on OFF→ON and continuously updated thereafter; in OFF mode it is not consulted).

**Pending-direction-change behaviour: byte-edge-triggered, not retried on stop.** When the operator moves the reverser lever during deceleration, the dispatch suppresses the direction change at the moment the byte changes. Once the loco reaches `v_fs == 0`, the dispatch does NOT retroactively apply the held lever position — the operator must nudge the reverser lever again (any byte change re-evaluates the interlock) to actually flip the direction. This matches prototype behaviour: on a real locomotive the engineer holds the reverser handle in the new position while waiting for speed=0, then either the handle physically engages or the engineer moves it the rest of the way once the train is stopped. We don't add engine-side state for "pending direction change" because that's not how the prototype works.

**Acceptance:**
1. Loco at speed > 0 + reverser moved to opposite direction: direction does NOT flip; an INFO log line records the suppression. Direction lever change with loco at speed 0 still works.
2. Loco decelerating + reverser already moved to opposite direction (held there during the ramp-down): direction still does NOT flip when `v_fs` reaches 0. Operator must release-and-re-move the reverser to trigger the byte-edge-driven direction change. Documented as expected behaviour, not a bug.
3. Reverser to NEUTRAL at any speed: loco coasts to a stop on the deceleration curve regardless of throttle/brake levers (per [research §3.3]).
4. E-Stop SPDT at any speed: loco hard-stops via `setSpeedSetting(-1)`. Same as the existing RailDriver bring-up behaviour.
5. With Mode OFF (`liveEnabled == false`): the interlock is skipped; reverser changes propagate immediately as in stage 1.

### 3.4 Stage 4 — Auto brake → air-line force + bail-off override

**Goal:** Auto Brake (Axis 2) drives `airLineFraction`, contributing `F_brake_air`; bail-off (byte 4) zeroes the air term while held.

**Modified:** `dispatchValueEvent` Axis 2 case (compute `airLineFraction = clamp((calibratedReleased - byteValue) / (calibratedReleased - calibratedEmg), 0, 1)`; Released → 0, EMG → 1; set `engine.airLineFraction`); button dispatch for the bail-off switch (set `engine.bailoffPressed = true/false` based on byte-4 threshold-crossing); `SemiRealisticThrottleEngine.java`'s integration tick now reads the air force via `air = bailoffPressed ? 0.0f : airLineFraction` and adds `F_brake_air = air · airBrakeMaxDecel · m_total` as another subtractive force.

This replaces EngineDriver's reservoir-and-line repeater simulation [research §4.2] with a direct mapping. It's both simpler in code and more prototypical (the operator's hand position *is* the air pressure on a real RailDriver). The reservoir-with-recharge state machine is **out of scope** unless the operator specifically wants to simulate "running out of air" — which they don't on a console with a real Auto Brake handle.

**Acceptance:**
1. Auto brake at Released: `F_brake_air = 0`, integrator walks toward lever target as in stage 2.
2. Auto brake at EMG, throttle at zero, Light-engine: loco decelerates at **1.5 m/s² wall-clock** (= `AIR_BRAKE_MAX_DECEL`). 80 mph → 0 in **~24 s wall-clock at any layout scale** (operator-controlled, scale-invariant).
3. Auto brake at 50 % + indep brake at Full Release: `F_brake_air = 0.5 · 1.5 · m_total`. Combined with `F_brake_mech = 0`, net decel ≈ 0.75 m/s² wall-clock. When indep brake is also applied at 50 %: `F_brake_total = 0.5·1.5·m + 0.5·1.0·m = 1.25·m`, decel ≈ 1.25 m/s² wall-clock — **forces sum, no `min(...)` clipping. Stronger braking than either alone, which is the prototype reality**: the EngineDriver `min(airLineAsBrakePcnt, brakePcnt)` rule [research §3.4] was an arcade simplification we deliberately don't reproduce.
4. Auto brake at SUP/CS + bail-off pressed: air-line term zeroes, only mechanical brake (if any) remains. Loco accelerates again under throttle if mechanical brake is also released.
5. Releasing bail-off restores the air-line term immediately (next tick).

### 3.5 Stage 5 — Dynamic brake (lever UP)

**Goal:** the half of the throttle lever above center (toward DYN BRAKE label) finally does something — adds `F_brake_dyn` term that acts on loco mass only and tapers near zero speed.

**Modified:** `dispatchValueEvent` Axis 1 case to compute `leverDynBrakeFraction = clamp((calibratedIdleLow - byteValue) / (calibratedIdleLow - calibratedFullDynBrake), 0, 1)` when the byte is below Idle Low (= the dyn-brake side); `SemiRealisticThrottleEngine.java`'s integration tick adds `F_brake_dyn = leverDynBrakeFraction · dynBrakeMaxDecel · locoMassKg · speedTaper(v_fs)` where `speedTaper(v) = min(1.0, v / dynBrakeVMinMps)`. The `DBr` LED, currently a TODO from the existing RailDriver bring-up, becomes the indication that the dyn-brake region is active.

Two notable physics differences from mechanical/air brake:
- Acts on `locoMassKg` only, not `m_total`. Real dyn brake doesn't propagate through trainline air [research §10 item 1] — only the loco's traction motors generate the braking torque, so the brake force is limited by loco mass × adhesion.
- Speed-tapered. Real dyn brake fades to zero below ~5 mph because traction motors lose torque at low speed. `speedTaper(v)` is the linear approximation; below `dynBrakeVMinMps` (default 5 mph = 2.24 m/s) the term scales linearly to zero.

Stacks with mechanical and air brake by simple force summation in the integration. No `max(...)` selection, no "reinforce" special case.

**Acceptance:**
1. Lever at Idle Low or above (in throttle region): `dynBrakeFraction = 0`, integrator behaves identically to stage 4.
2. Lever at full DYN BRAKE, Light-engine, throttle and brakes off, at 60 mph: `F_brake_dyn = 1.0 · 0.4 · 130 000 · 1.0 = 52 kN`, decel **0.4 m/s² wall-clock** (operator-controlled, scale-invariant). LED shows `DBr`.
3. Same as #2 but at 3 mph prototype velocity (below taper threshold): `speedTaper(3 mph) = min(1, 1.34/2.24) = 0.6`, decel ≈ 0.24 m/s² wall-clock. Below ~0.5 mph dyn brake contributes essentially nothing.
4. Lever at full DYN BRAKE + Auto brake at EMG simultaneously, Light-engine at 60 mph: `F_brake_dyn = 52 kN`, `F_brake_air = 1.0 · 1.5 · 130 000 = 195 kN`, total = 247 kN, decel ≈ 1.9 m/s² wall-clock — emergent from force summation, no special-case code. Heavier (e.g. Unit train) consist scales `F_brake_air` up but not `F_brake_dyn`, so dyn brake's relative contribution is much smaller — prototypically correct (loco-only dyn brake on a heavy train is not the primary deceleration mechanism).

### 3.6 Stage 6 — ESU decoder-brake passthrough (optional)

**Goal:** for users with ESU decoders, mirror brake percent to F4/F5/F6 [research §4.3]. Off by default.

**Modified:** `SemiRealisticSettings.java` (enable the `ESU` option in the `decoderBrakeMode` enum; ESU function/threshold fields), `SemiRealisticSettingsPanel.java` (enable the Decoder-brake Mode dropdown's `ESU` option + ESU-only sub-fields), `SemiRealisticThrottleEngine.java` integration tick (after computing forces, derive `effectiveBrakePct = clamp(indepBrakeFraction · 100 + airLineFraction · airBrakeMaxDecel/brakeMaxDecel · 100, 0, 100)` and dispatch the function changes with the same three-pass logic from `setDecoderBrake` in [research §4.3]).

**Acceptance:**
1. Decoder-brake mode = None: no F4/F5/F6 dispatch from semi-realistic logic.
2. Decoder-brake mode = ESU: applying indep brake to ≥30 % toggles F4 ON; ≥60 % toggles F4 OFF + F5 ON; ≥98 % toggles F5 OFF + F6 ON. Backing off reverses the chain.
3. The threshold/function fields are user-configurable per loco family.

## 4. Acceptance criteria (overall)

1. With semi-realistic mode OFF, behaviour is identical to the existing RailDriver bring-up (no regression).
2. With mode ON, all per-stage acceptance criteria pass on a real DCC loco.
3. **Force-class invariants per §1.0**: operator-controlled brake decels (mechanical, air, dynamic) produce wall-clock 80 mph → 0 stop times within ±10 % of the §1.0/§2.4.1 expected values **regardless of layout scale**. Coast-down decel (rolling resistance) produces wall-clock times that depend on `physicsTimeScale` per `decel = c_rr · g · physicsTimeScale`. Setting `physicsTimeScale = 1` reverts coast-down to prototype 1:1 wall-clock.
4. Calibration XML round-trips through Save / Load with the new schema; pre-existing files (version `"1"`) still load cleanly with semi-realistic defaults; pre-existing `version="2"` files without `<physicsTimeScale>` load with `auto` default and pick up scale-aware physics on next launch.
5. The `activeThrottleFrame == null` invariant from the existing RailDriver bring-up still holds — the engine acquires its `DccThrottle` from `activeThrottleFrame` at attach time and never holds the reference past the throttle's `"ancestor"` close.
6. The pre-existing noise hysteresis filter still applies — the engine never sees byte-level jitter as a "lever moved".
7. No new `messages.log` exceptions during a 30-minute ops session involving repeated brake / throttle work.
8. **Empty-roster path:** with mode ON, no roster physics fields populated, default Light-engine scenario, `physicsTimeScale = auto`: the loco accelerates and decelerates under the scenario's defaults from §2.4.1, with coast-down compressed to scale-time. No "physics ramp disabled" warnings (the AutoEngineer fallback path is irrelevant — we always have valid scenario defaults).
9. **Roster-physics-override path:** with `getPhysicsWeightKg() > 0` etc. on the active loco's `RosterEntry`, the engine uses those values instead of scenario defaults for `locoMassKg` / `locoPowerW` / `locoTractiveEffortN` per §2.4.1's precedence rule.

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

- **Engine architecture (decided 2026-05-02): two-class force model on a 50 ms tick.** Per §1.0, forces split into operator-controlled (drive, all three brake terms) and prototype-physics (rolling resistance, future drag/gradient/curve). Operator-controlled coefficients are stated in wall-clock m/s² perceived rate; prototype-physics coefficients are dimensionless and multiplied by `physicsTimeScale` to be visible at scale-time wall-clock. A single integration loop computes net force `a = (F_drive − F_rr · S − F_brake_mech − F_brake_air − F_brake_dyn) / m_total` (where `S = physicsTimeScale`) on a 50 ms tick; the same loop runs accelerating or decelerating with the sign of `a` falling out of which forces are active — no regime detection, no "throttle fighting brake" branch, no separate accel/decel paths. `getPhysicsMaxSpeedKmh()` clamps both `vTarget` and integrated `v_fs`. See §2.1 for the engine class skeleton, §1.0 for the force-class framing, and §2.2 for the threading contract.
- **`physicsTimeScale` default and resolution (decided 2026-05-02): `auto` resolves to JMRI's `SignalSpeedMap.getLayoutScale()`.** The Settings tab field accepts numeric override or `auto` (default). At engine attach and on every settings reload, the engine resolves `auto` to `SignalSpeedMap.getLayoutScale()` (= 87 / 160 / 220 etc.), falling back to 87 (HO) if the lookup throws. Numeric override range: 0.1 to 1000. Operators wanting prototype 1:1 simulation set the value to 1 explicitly. The single scalar applies to all prototype-physics force terms; per-term overrides are deferred to future work (see §1.2). See §2.4.1 and §2.5.
- **Physics defaults (decided 2026-05-02): scenario-driven, with Light-engine values as the universal fallback.** When `RosterEntry.getPhysicsWeightKg()`/`PowerKw()`/`TractiveEffortKn()` return 0 (the JMRI default), the engine substitutes the active scenario's loco-physics defaults from §2.4.1. The Light-engine scenario (130 t / 2200 kW / 350 kN / 0 t consist / 100 % driver power) is the default scenario, so a brand-new install with an empty roster gets prototype-realistic single-loco behaviour with zero configuration. Roster-populated values override scenario defaults when present per §2.4.1's precedence rule.
- **Brake constants (decided 2026-05-02): scenario-independent, operator-controlled, stated in wall-clock m/s² perceived rate.** `BRAKE_MAX_DECEL = 1.0 m/s² wall-clock`, `AIR_BRAKE_MAX_DECEL = 1.5 m/s² wall-clock`, `DYN_BRAKE_MAX_DECEL = 0.4 m/s² wall-clock` (peak, before speed taper), `DYN_BRAKE_V_MIN = 5 mph prototype` (velocity threshold; not wall-clock, not scale-compressed), `c_rr = 0.002` (dimensionless prototype-physics coefficient). All exposed as Settings-tab fields. Brake forces are `decelRate · m_total` (mechanical and air, scaling with consist mass) or `decelRate · m_loco · speedTaper(v)` (dyn brake, loco-only and tapered) — so heavier consist gets the same brake decel rate but smaller dyn-brake contribution, prototypically correct. Brake decel rates are scale-invariant by design — same wall-clock time-to-stop at HO, N, or any other scale.
- **EDT discipline (decided 2026-05-02): Option B with worker-thread-math mitigation, standardised on `jmri.util.ThreadingUtil`.** Every Swing-touching call from a non-EDT thread — both the new engine dispatch AND the existing direct-dispatch path — is wrapped in `ThreadingUtil.runOnGUIEventually(...)` (fire-and-forget setters) or `ThreadingUtil.runOnGUIwithReturn(...)` (synchronous getters whose return values feed decision logic). The single existing `SwingUtilities.invokeLater` call site in `RailDriverMenuItem.java` (line 244, in `attachThrottleWindow`) is migrated to `ThreadingUtil.runOnGUIEventually` for file-level uniformity and consistency with the rest of JMRI (`Engineer.java`, `AddressPanel.java`, etc.). The mitigation: the engine worker thread does all the integration math locally, then hands the final DCC step value to the EDT for application. This keeps tick cadence governed by the `ScheduledExecutorService` (precise timing) and only the value-application bounces through the event queue (Swing consistency). Closes the parent §3 / §6 off-EDT latent issue **and** the latent bug where the bring-up-era plan listed `getFunctions()` / `getFunctionMomentary()` / `getFunction()` alongside setters for `invokeLater` wrapping — those are now correctly routed through `runOnGUIwithReturn`. See §2.2 for the threading contract and §3.1 for the wrapping deliverables.
- **UI surface (decided 2026-05-02): one unified `RailDriver Settings...` window with two tabs (Settings + Calibration), plus a new Apply button alongside Save and Cancel.** Replaces the standalone `RailDriver Calibration...` entry that ships with the existing RailDriver bring-up. See §2.4 for layout, dirty-tracking model, and Save/Apply/Cancel behaviour. The frame infrastructure (tabs, Save/Apply/Cancel, dirty tracking, Calibration tab) ships in stage 1 (§3.1) with the Settings tab as a placeholder; the Settings tab content (semi-realistic fields including `physicsTimeScale`) is filled in by stage 2 (§3.2).
- **Framing (decided 2026-05-02): this is a model-railroad simulation, not a prototype simulator.** The two-class force model exists precisely because operators want operator-perceptible decel rates AND scale-aware coast-down on the same layout. The plan is structured around six stages numbered 1–6 within this document; they don't extend the phase-1/2/3 numbering of the predecessor RailDriver bring-up plans.
- **Mode-toggle UI on the throttle window (decided 2026-05-02): Jynstrument-based toolbar button, session-only persistence semantics.** Adds a single icon to the throttle window's toolbar via JMRI's existing Jynstruments framework — no JMRI core modifications needed. The Jynstrument shows and mutates `liveEnabled`; the Settings tab's checkbox shows and mutates `persistedEnabled` (with in-window dirty tracking). They are deliberately decoupled: the Settings tab never reflects a session-only Jynstrument toggle, and the Jynstrument never reflects an unsaved Settings-tab edit. Right-click → `Settings...`. Auto-installed by `RailDriverMenuItem.attachThrottleWindow()`, gated solely by `hasJynstrumentInstalled` (no in-memory or on-disk suppression state). Removal-via-Quit sticks for the lifetime of that throttle window; re-clicking `Debug → RailDriver Throttle (built in)` re-adds the toggle (treated as a deliberate fresh-attach action). Cross-session persistence of toggle absence relies on saved-layout-XML restore happening before any auto-install fires. There is no `<jynstrumentAutoInstallSuppressed>` flag in the calibration XML. See §2.3 for the persistence model, §2.6 for the full Jynstrument design, and §3.2 acceptance bullets 12–14.

- **Persistence split between Settings window and Jynstrument (decided 2026-05-02).** Two distinct values: `persistedEnabled` (last loaded or saved XML value, displayed and edited by the Settings tab) and `liveEnabled` (current engine/Jynstrument state). The Settings tab Save/Apply mutates `persistedEnabled`, writes XML, then sets `liveEnabled := persistedEnabled`. The Jynstrument click mutates `liveEnabled` only. The Settings tab checkbox is bound to `persistedEnabled` (plus in-window pending-edit state) and never reflects session-only Jynstrument toggles — so the operator always sees what's on disk in the Settings tab, and what the engine is currently doing on the Jynstrument icon. To make a session-level Jynstrument change persist, the operator opens the Settings window, sets the checkbox to match, and Saves. Two PCS event names (`"liveEnabledChanged"` for engine + Jynstrument; `"persistedEnabledChanged"` for the Settings tab) keep the observer sets disjoint. See §2.3.

- **Attach-in-progress visibility on the Jynstrument (decided 2026-05-02).** `requestAttachToThrottle()` is asynchronous (the bind work is posted to the EDT via `ThreadingUtil.runOnGUIEventually`), so the Jynstrument cannot rely on the bind being complete by the time its click handler returns. A new `"attachInProgress"` PCS event brackets the async window, and the Jynstrument tracks an internal `pendingSessionToggle` flag: State-2 click sets the flag and triggers the attach, the `"activeThrottleFrame"` event delivers (transitioning to State 4), and the listener applies the deferred `setSemiRealisticEnabledSessionOnly` toggle. Extra clicks during the window are absorbed (State 2.5). See §2.6.

- **`hidDeviceAttached` re-enabled (decided 2026-05-02).** The currently-disabled body at `RailDriverMenuItem.java:539–546` (originally gated on a now-removed `invokeOnMenuOnly` flag) is re-enabled in stage 2 with a minimal change: fire `"railDriverConnected"` PCS event on VID/PID match **without** auto-calling `setupRailDriver()`. The Debug-menu workflow keeps owning the polling lifecycle, so cold-plug behaviour for users without the Jynstrument is unchanged; only the Jynstrument's icon state reacts to hot-plug. See §3.2 step 4.
- **Bail-off semantics (decided 2026-05-02): latched while the byte is above the calibrated threshold.** Stage 4 sets `engine.bailoffPressed = true` whenever the byte 4 value exceeds `bailoffThreshold()` and `false` otherwise. The engine zeros `F_brake_air` while `bailoffPressed` is true — direct level-triggered semantics that match how a real bail-off handle behaves on the prototype. Mechanical and dyn brake terms are unaffected.
- **Mode-switch handover at speed (decided 2026-05-02).** OFF → ON adopts the loco's current `throttle.getSpeedSetting()` as the engine's starting `v_fs` (converted to full-scale velocity via the roster speed profile when available, linear fallback otherwise), then integrates smoothly toward the lever-derived target. ON → OFF pauses the integration task and the next byte-change writes the lever-derived value directly via `setSpeedSetting()` — the loco may snap if the lever is far from the engine's last commanded speed. Operators are expected to either align the lever before flipping OFF or to accept the snap. See §2.3 for the implementation contract.
- **Reverser-interlock speed source (decided 2026-05-02): `engine.v_fs`, gated by `settings.liveEnabled`.** The polling-thread Axis 0 dispatch reads `engine.v_fs` (volatile, no EDT crossing) and suppresses `setIsForward()` when `settings.liveEnabled && v_fs > epsilon`. The `settings.liveEnabled` guard ensures the field is only consulted while the engine maintains it (per the §2.3 handover contract). See §3.3 for stage details.
- **Pending direction change at stop (decided 2026-05-02): not retried on `v_fs == 0`.** When the operator moves the reverser during deceleration the dispatch suppresses the direction change at byte-change time and does NOT retroactively apply the lever's held position when the loco eventually stops. The operator must nudge the reverser to trigger another byte change. Matches prototype operator behaviour (engineer holds the handle in position then moves it the rest of the way at stop). See §3.3 acceptance bullet 2.
- **Settings-field validation ranges (decided 2026-05-02):** loco mass 1–500 t; loco power 1–10 000 kW; loco TE 1–2000 kN; additional consist mass 0–50 000 t; driver power 0–100 %; rolling resistance coefficient 0.0001–0.05; mechanical brake max decel 0.1–5.0 m/s² wall-clock; air brake max decel 0.1–5.0 m/s² wall-clock; dynamic brake max decel 0.0–2.0 m/s² wall-clock; dynamic brake taper threshold 0–20 mph prototype; physics time scale 0.1–1000 (or `auto`); ESU thresholds 1–100 monotonically increasing; ESU function numbers 0–28. Validation runs on Save and Apply per §2.4's flow; failures auto-select the offending tab and leave dirty set.
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
- **Single `physicsTimeScale` scalar applies to all prototype-physics terms.** Currently rolling resistance is the only such term. If aerodynamic drag or other prototype-physics forces are added (see §8), they'll initially share the same scalar. Per-term overrides are deferred until a second prototype-physics term lands.
- **Reservoir-and-line refill model from EngineDriver §4.2 is replaced with the direct lever-driven model.** Operators who want "ran out of air, must release brake to recharge" gameplay will have to wait for a future feature that simulates a virtual reservoir behind the Auto Brake — this is out of scope here because the RailDriver's physical Auto Brake gives us the real signal.
- **Brake constants are scenario-independent.** The plan exposes `BRAKE_MAX_DECEL` etc. as Settings-tab fields, but doesn't vary them per scenario (heavy freight cars and passenger cars both use the same default 1.0 m/s² wall-clock mechanical brake max decel). Operators can edit them manually if needed; per-scenario brake-constant tables are deferred.
- **Linear `v ↔ DCC step` fallback when no speed profile.** Without a calibrated `RosterSpeedProfile` for the loco, the engine maps `v_fs` to DCC step linearly. The integration is still correct (forces are real); only the absolute speed-step calibration is approximate. Users with calibrated profiles automatically get exact mapping via `getSpeed(...)`.
- **Prototype 1:1 simulation requires explicit opt-in.** Operators wanting prototype-realistic 30-minute coast-downs must explicitly set `physicsTimeScale = 1` on the Settings tab. The default `auto` resolves to JMRI's `layoutScale` so out-of-the-box behaviour is scale-aware.
- **No tests.** Parent §4.5 / deferred.
- **No help-page documentation.** Parent §4.6 / deferred.
- **All latent issues from parent §3 / §6 except the off-EDT mutation are still untouched.** The off-EDT issue is fixed in stage 1 (see §3.1's `RailDriverMenuItem.java` modifications).

## 8. Future work

- Tests (parent §4.5) — byte-parser, settings persistence round-trip, integration determinism with a stub DccThrottle (mirror existing JMRI test patterns for `AbstractThrottle` rather than introducing a new throttle proxy).
- **Aerodynamic drag** as a second prototype-physics force class element. Adds `F_drag = ½ · c_d · ρ · A · v² · physicsTimeScale` (or a simplified `c_d_eff · v² · physicsTimeScale`) for visible high-speed deceleration. Slots into the existing two-class force model cleanly: it's prototype-physics, so it gets the time-compression treatment, and it stays scale-aware automatically.
- **Gradient gravity, curve resistance, journal-bearing breakaway** — additional prototype-physics force terms with `× physicsTimeScale` semantics.
- **Per-force `physicsTimeScale` overrides.** If future prototype-physics terms need different time compression (e.g. drag wants `physicsTimeScale^0.5` for Froude-similar high-speed feel while rolling resistance wants linear `physicsTimeScale`), the single scalar grows into a per-term map. Deferred until needed.
- Per-roster scenario default via `RosterEntry.getAttribute("raildriver.scenario")`.
- Per-scenario brake constants (e.g. passenger trains with shorter stopping distances).
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
