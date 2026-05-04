# Epic: RailDriver Semi-Realistic Throttle — EngineDriver-Aligned

## Summary

Port EngineDriver's `throttle_semi_realistic` algorithm to the JMRI RailDriver desktop integration, replacing the in-progress velocity-based physics engine with a simpler target-speed + Δt-multiplier step-rate scheduler. The result gives RailDriver console operators the same throttle feel as EngineDriver's Android semi-realistic mode — the throttle lever sets a target decoder speed step, and the live speed walks toward that target one fixed-size step every Δt milliseconds, where Δt is scaled by brake position, air-line state, load scenario, and direction.

## Motivation

The current in-progress physics engine (`SemiRealisticThrottleEngine`) uses a Davis-equation velocity model with ~20 m/s² coefficients, drag terms, and a quantisation step — a level of fidelity that is hard to tune, hard to test, and doesn't match any reference implementation operators are familiar with. EngineDriver's semi-realistic mode is well-understood, well-tested on Android, and deliberately simple: integer step counts, millisecond delays, and small-arity multipliers. Aligning with it gives operators a consistent experience across platforms and gives developers a clear, verified reference implementation to port from.

**Non-goal:** Prototype-accurate physics. There is no mass, no force, no m/s² figures, no Davis equation, no per-roster speed profile inside the engine. The decoder and JMRI roster speed profile remain the sole authority for actual model-train velocity.

## Scope

All work is contained within the JMRI desktop codebase. The RailDriver USB integration, throttle window plumbing, and JMRI Preferences framework are the primary integration surfaces. No changes to EngineDriver, WiThrottle protocol, or DCC decoder behaviour are in scope.

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

**Description:** Implement three independent brake sources (independent brake, air system, dynamic brake) whose retardation values combine via `effectiveBrake = min(indepPcnt, airPcnt, dynPcnt)`. Each source maps a physical RailDriver lever to the EngineDriver brake math.

#### 2a: Independent Brake (Lever #11, byte 3)

- Quantised from calibrated Full Release / Full Application byte range to `numberOfBrakeSteps` notches (default 7).
- Position 0 = released. Each step immediately re-invokes `setTargetSpeed`.
- Matches EngineDriver's "brake slider" byte-for-byte.

#### 2b: Air System (Auto Brake lever #10, byte 2)

- Auto Brake lever position **directly sets** `airLineValue` (0..100) — no derived-from-brake-slider intermediary.
- Reservoir refill behaviour preserved from EngineDriver: +5% every `airRefreshRateMs` (default 2000 ms), self-rescheduling via `runOnLayoutDelayed`.
- EngineDriver's line repeater is **not ported** (redundant — the lever directly controls line pressure).
- Air simulation disabled by parking Auto Brake at Released or by setting `airRefreshRateMs = 0`.

#### 2c: Dynamic Brake (Throttle/Dyn lever #9, below Idle)

- Below-idle throttle travel produces a virtual `dynBrakeStep` in `0..numberOfBrakeSteps`.
- Low-speed taper: below `dynBrakeMinSpeedStep` (default 8), dyn-brake effect fades linearly to zero at speed 0.
- No air-line interaction — dyn brake is electrical-only.

#### 2d: Bail-Off (byte 4, transient)

- While asserted, `setTargetSpeed` skips the indep + dyn contributions to `effectiveBrake` — only air still applies.
- Allows the operator to release the loco brake against a held trainline application.

**User Stories:**

- As an operator, I want the Independent Brake lever to slow and stop my loco progressively.
- As an operator, I want the Auto Brake lever to apply air braking that models reservoir dynamics.
- As an operator, I want below-idle throttle travel to apply dynamic braking that fades at low speed.
- As an operator, I want the bail-off to temporarily release only the loco-side brake.

**Acceptance Criteria:**
- [ ] Indep brake full + throttle 50% → loco settles at ~15% of full speed.
- [ ] Auto Brake EMG → hard decel at ~15 seconds at defaults.
- [ ] Bail-off held during EMG → indep portion drops out; loco still decels via air alone.
- [ ] Dyn brake below `dynBrakeMinSpeedStep` fades linearly to zero.
- [ ] Air reservoir refills at +5% per tick when `airRefreshRateMs > 0`.

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

### Feature 6: JMRI Preferences Integration (Settings UI)

**Description:** Retire the bespoke `RailDriverSettingsFrame` and deliver settings through two `jmri.swing.PreferencesPanel` SPI providers grouped under "RailDriver" in the standard JMRI Preferences window. A new `RailDriverPreferencesManager` (`jmri.spi.PreferencesManager` SPI provider) owns persistence and the in-memory settings records.

**Panels:**

| Panel                                       | Role                                     | Persistence space |
|---------------------------------------------|------------------------------------------|-------------------|
| `RailDriverSemiRealisticPreferencesPanel`   | Operator-feel preferences (ramp, brake, air, load, decoder integration) | Shared            |
| `RailDriverCalibrationPreferencesPanel`     | Per-machine HID calibration (existing visual-bar UI) | Private           |

**Live-apply settings model.** When the operator saves settings via the JMRI Preferences window, the `RailDriverPreferencesManager` persists the new values and fires a `"settingsChanged"` PCS event. `RailDriverMenuItem` subscribes to this event and, if an engine is currently attached, pushes the new `SemiRealisticSettings` snapshot to the engine via `updateSettings()` on the layout thread. The engine replaces its internal settings copy and calls `recomputeTarget()` so the new values (ramp delays, brake parameters, load slider position, etc.) take effect on the next ramp tick — no close-and-reopen required.

**Exception: the `enabled` flag.** The dispatch strategy (engine vs direct) is still decided once at `notifyAddressThrottleFound` and fixed for the throttle frame's lifetime, because switching dispatch strategy mid-session while a loco is moving would require complex handover logic. Changes to the `enabled` checkbox require closing and reopening the throttle. A `JmriJOptionPane` alert surfaces this only when `enabled` actually changed across a save.

**Calibration follows the same live-apply model.** When the operator saves calibration, the manager fires a `"calibrationChanged"` PCS event. `RailDriverMenuItem` subscribes and picks up the new axis detents for subsequent HID byte → step conversions. No close-and-reopen required.

**User Stories:**

- As an operator, I want to configure semi-realistic throttle settings through the standard JMRI Preferences window, not a separate custom frame.
- As an operator, I want validation feedback when I enter invalid settings (e.g. brake thresholds out of order).
- As an operator, I want a clear "Reset to defaults" button that restores semi-realistic fields to default values.
- As an operator, I want saved settings to take effect immediately on my running throttle without closing and reopening it.
- As an operator, I want to be told when I change the enable/disable mode that I need to reopen the throttle for that specific change.

**Acceptance Criteria:**
- [ ] Both panels appear under a "RailDriver" group in JMRI Preferences.
- [ ] Save/Apply/Cancel use the standard JMRI Preferences framework — no custom button bar.
- [ ] All blocking validation rules from the spec are enforced on save.
- [ ] Non-blocking warnings are surfaced via status labels.
- [ ] Saving settings fires `"settingsChanged"` PCS event; `RailDriverMenuItem` pushes new settings to any attached engine immediately.
- [ ] Saving calibration fires `"calibrationChanged"` PCS event; `RailDriverMenuItem` picks up new axis detents immediately.
- [ ] The engine's `updateSettings()` replaces its internal copy and calls `recomputeTarget()` so new values take effect on the next ramp tick.
- [ ] `JmriJOptionPane` alert fires only when the `enabled` flag changed, telling the operator to reopen the throttle for mode changes.
- [ ] `isDirty()` tracks changes correctly across all input controls.
- [ ] The `enabled` checkbox state is persisted; dispatch strategy (engine vs direct) is fixed at bind time.

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

**Description:** A passive RailDriver-USB connectivity indicator on the throttle window toolbar. Two visual states only: active (connected) or greyed (disconnected). Clicking opens JMRI Preferences → RailDriver. Not mode-aware.

**User Stories:**

- As an operator, I want a quick visual indicator showing whether my RailDriver console is connected.
- As an operator, I want to reach RailDriver settings quickly by clicking the toolbar indicator.

**Acceptance Criteria:**
- [ ] Active icon when `isRailDriverConnected()` is true; greyed when false.
- [ ] Left-click opens JMRI Preferences → RailDriver group.
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
- `apps.jmrit.DebugMenu` import references updated

**Retired:**
- `RailDriverSettingsFrame` — replaced by PreferencesPanel SPI providers
- `RailDriverSettingsAction` — replaced by standard Preferences navigation
- `SemiRealisticSettingsPanel` — replaced by `RailDriverSemiRealisticPreferencesPanel`

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

- **EngineDriver reference:** The algorithm must match EngineDriver's `throttle_semi_realistic.java` section-for-section. The `getLoadPcnt` quadratic formula, its guard condition, and both curve-shaping preferences (`numberOfLoadSteps`, `maxLoadPcnt`) are ported verbatim. Any deviation must be documented and justified (only the dyn-brake low-speed taper is new).
- **JMRI threading conventions:** All timed events through `ThreadingUtil`, never `ScheduledExecutorService` or `java.util.Timer`.
- **JMRI SPI patterns:** Settings UI via `PreferencesPanel`, persistence via `PreferencesManager`, both discovered via `ServiceLoader`.
- **Backward compatibility:** Legacy calibration files must migrate without data loss (detents preserved, physics coefficients discarded with warning).
- **Live-apply for settings and calibration:** Saved settings and calibration are pushed to any attached engine / dispatcher immediately via PCS events. The engine accepts mid-session updates via `updateSettings()` on the layout thread. Exception: the `enabled` flag (dispatch strategy) is fixed at bind time — changing it requires closing and reopening the throttle.

## Cross-References

- [Engine-Driver-aligned spec](engine-driver-semi-realistic-throttle-spec.md) — full technical specification this epic derives from
- [EngineDriver algorithm research](semi-realistic-throttle-info.md) — line-by-line reference of the EngineDriver source
- [RailDriver control inventory](control-inventory.md) — physical control / HID byte mapping
- [EngineDriver source](https://github.com/JMRI/EngineDriver/blob/master/EngineDriver/src/main/java/jmri/enginedriver/throttle_semi_realistic.java) — upstream reference implementation
