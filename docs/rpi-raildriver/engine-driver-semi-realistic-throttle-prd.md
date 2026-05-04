# Epic PRD: RailDriver Semi-Realistic Throttle — EngineDriver-Aligned

## 1. Epic Name

**RailDriver Semi-Realistic Throttle — EngineDriver-Aligned**

Port EngineDriver's `throttle_semi_realistic` algorithm to the JMRI RailDriver desktop integration, replacing the in-progress velocity-based physics engine with a simpler target-speed + Δt-multiplier step-rate scheduler.

---

## 2. Goal

### Problem

The current in-progress physics engine (`SemiRealisticThrottleEngine`) uses a Davis-equation velocity model with ~20 m/s² coefficients, drag terms, and a quantisation step. This level of fidelity is hard to tune, hard to test, and doesn't match any reference implementation operators are familiar with. Operators moving between EngineDriver on Android and the RailDriver on desktop experience fundamentally different throttle behaviour, creating a fragmented and confusing user experience. Additionally, the physics engine's complexity makes it a maintenance and testing burden for contributors.

### Solution

Replace the velocity-based physics engine with a faithful port of EngineDriver's `throttle_semi_realistic` algorithm — a step-rate scheduler where the throttle lever sets a target decoder speed step and the live speed walks toward that target one fixed-size step every Δt milliseconds. Δt is scaled by brake position, air-line state, load scenario, and direction. The RailDriver's physical levers (independent brake, auto brake, dynamic brake, bail-off, reverser) are mapped onto this algorithm, extending it with per-source brake load scaling and Westinghouse air dynamics that the separate physical levers make operationally meaningful.

### Impact

- **Operator experience:** Consistent throttle feel across EngineDriver (Android) and RailDriver (desktop), eliminating the learning curve of switching platforms.
- **Developer productivity:** A simpler, reference-verified algorithm reduces debugging time, testing complexity, and onboarding cost for new contributors.
- **Code quality:** Relocation to `jmri.jmrit.usb`, migration to JMRI SPI patterns for settings/persistence, and comprehensive test coverage bring the RailDriver integration up to current JMRI architecture standards.
- **Operator immersion:** The Westinghouse air brake model and multi-source brake system with realistic load interaction give desktop operators a substantially more realistic railroading experience than either the current direct-mapped or in-progress physics engine.

---

## 3. User Personas

### Primary: Model Railroad Operator (RailDriver Console User)

A model railroad enthusiast who owns a RailDriver Modern Desktop USB controller and uses it with JMRI (PanelPro or DecoderPro) to operate trains on their layout. They expect the physical controls — throttle, independent brake, auto brake, reverser, and E-Stop — to feel like operating a real locomotive. They may also use EngineDriver on Android for mobile operation and expect a consistent experience across platforms.

- **Technical comfort:** Moderate. Comfortable with JMRI Preferences and basic configuration but not expected to edit XML or write code.
- **Railroading knowledge:** High. Understands concepts like air brakes, independent brakes, dynamic brakes, bail-off, and load effects. Wants these modelled realistically.
- **Platform:** Linux (including Raspberry Pi), macOS, or Windows running JMRI with a RailDriver connected via USB.

### Secondary: EngineDriver User (Cross-Platform Operator)

An operator who regularly uses EngineDriver on Android for wireless operation and the RailDriver for stationary console operation. They expect the same throttle feel, ramp timing, and load behaviour on both platforms. The semi-realistic algorithm's fidelity to EngineDriver's source is directly valuable to this persona.

### Tertiary: JMRI Developer / Contributor

A developer who maintains or extends the RailDriver integration. They benefit from the codebase being relocated to the correct JMRI package (`jmri.jmrit.usb`), following JMRI SPI patterns, using standard persistence and preferences infrastructure, and having comprehensive test coverage as a safety net.

---

## 4. High-Level User Journeys

### Journey 1: First-Time Semi-Realistic Setup

1. Operator opens JMRI Preferences → RailDriver.
2. On the Semi-Realistic panel, they enable the semi-realistic throttle mode.
3. They review the default ramp delays, brake steps, and load slider — adjusting if desired.
4. They save. A dialog tells them to close and reopen the throttle window for the mode change to take effect.
5. They open a new throttle window and acquire a locomotive.
6. The semi-realistic engine attaches, seeding from the loco's current speed. The RailDriver is now in semi-realistic mode.

### Journey 2: Normal Running with Multi-Source Braking

1. Operator pushes the throttle lever forward. The loco accelerates gradually toward the commanded speed.
2. Approaching a station, they pull the Auto Brake lever to a partial application position. The brakes engage immediately — the loco decelerates.
3. They hold the lever at a mid position (lap). Brake pipe pressure stabilises; the loco coasts at a reduced speed.
4. They release the auto brake. The brakes bleed off gradually (10+ seconds from full application) as the line recharges from the reservoir.
5. Meanwhile, they use the Independent Brake for fine control of the locomotive itself during a switching move.

### Journey 3: Heavy Train Operations (Load Interaction)

1. Operator slides the load slider from 0 (light engine) to 3 (moderate load) on the preferences panel and saves.
2. Acceleration becomes noticeably slower — the loco is "pulling" more weight.
3. They apply the independent brake. Because it only brakes the loco (not the train), it barely slows the heavy consist.
4. They apply the auto brake instead. The trainline engages brakes on all cars — the consist decelerates effectively despite the heavy load.

### Journey 4: Bail-Off Coupling Move

1. Operator has the auto brake applied, holding the consist stationary on a grade.
2. They press the bail-off button, venting the locomotive's brake cylinders. The loco is now free-rolling; only the cars' brakes hold position.
3. They apply gentle throttle. The loco creeps forward under power while the consist stays braked — stretching the slack for a controlled coupling.
4. They release bail-off; the loco's brakes re-engage.

### Journey 5: Emergency Stop and Recovery

1. Operator flips the E-Stop switch. The loco halts instantly. All ramp callbacks are cancelled.
2. After clearing the emergency, they move any lever. The engine resumes normal ramp behaviour automatically.

### Journey 6: Air Brake Emergency and Slow Recovery

1. Operator slams the auto brake lever to EMG. Brake pipe drops to zero instantly; maximum braking force applied.
2. After the loco stops, they move the lever to Released. The line begins recharging — but the reservoir was depleted during the emergency, so the recharge is slow.
3. The operator waits 15–20+ seconds for the reservoir to refill and the line to recharge. Brakes gradually release.

### Journey 7: Profile Migration (Upgrade)

1. Operator upgrades JMRI to the version containing this epic.
2. On first launch, the legacy `raildriver-calibration.xml` file is detected.
3. Calibration detents are automatically migrated to the new `<rd:hardwareCalibration>` fragment. If the legacy file had v2 semi-realistic settings, they are discarded with a warning (field meanings changed incompatibly).
4. The legacy file is renamed to `.bak`. Semi-realistic settings start at defaults.

### Journey 8: Connectivity Awareness

1. Operator opens a throttle window. A small RailDriver icon appears on the toolbar.
2. While the RailDriver is connected via USB, the icon is active (normal colour).
3. If the USB cable is disconnected, the icon turns grey immediately.
4. Clicking the icon opens JMRI Preferences → RailDriver for quick access to settings.

---

## 5. Business Requirements

### Functional Requirements

**Core Algorithm (Feature 1)**
- The throttle lever sets a target speed step; the live speed walks toward it one step per Δt ms.
- Δt = `baseDelay × |targetAcceleration|`, with configurable base delays (default 300 ms accel, 800 ms decel).
- Self-rescheduling via `ThreadingUtil.runOnLayoutDelayed` with epoch-counter cancellation.
- Engine seeds from the throttle's current speed on attach (no ramp from zero).
- Decoder-side dispatch throttled by configurable minimum emit interval (default 50 ms).

**Multi-Source Brake System (Feature 2)**
- Independent brake: quantised to configurable notches (default 7); loco-only, load-reduced.
- Air system: simplified Westinghouse model with instant application, gradual release (line repeater), reservoir gating, lap behaviour, and emergency dynamics.
- Dynamic brake: below-idle throttle travel; loco-only, load-reduced; low-speed taper.
- Bail-off: vents all loco-side braking; only train-side (car) brakes remain.
- Effective brake: `min(effectiveIndepPcnt, effectiveAirPcnt, effectiveDynPcnt)` after per-source load scaling.
- Disabling air simulation (`airRefreshRateMs = 0`) flat-maps the auto brake lever directly.

**ESU Decoder Brake Passthrough (Feature 3)**
- Independent brake position drives F4/F5/F6 on/off at three configurable thresholds (default 30/60/98%).
- Gated by `decoderBrakeMode` setting (`NONE` | `ESU`); default `NONE` short-circuits all function calls.

**Load Slider (Feature 4)**
- Quadratic formula matches EngineDriver exactly: `((load² × (maxLoadPcnt − 100)) + 100) / 100`.
- Configurable `numberOfLoadSteps` (default 5) and `maxLoadPcnt` (default 1000).
- JSlider with tick labels showing computed multiplier at each position.
- Load change takes effect immediately on save (pushed to attached engine).

**Direction & Stop Semantics (Feature 5)**
- Reverser interlock: direction change only at speed 0.
- E-Stop cancels all pipeline epochs, writes `setSpeedSetting(-1f)` directly.
- Automatic recovery on next lever event.

**Settings UI (Feature 6)**
- Two `PreferencesPanel` SPI providers under "RailDriver" group in JMRI Preferences.
- Semi-Realistic panel (shared space) and Calibration panel (private space).
- Live-apply model: saved settings pushed to attached engine via PCS events.
- Exception: `enabled` flag fixed at bind time; mode change requires throttle reopen.

**Profile-Aware Persistence (Feature 7)**
- Two `AuxiliaryConfiguration` fragments under namespace `http://jmri.org/xml/schema/raildriver/3`.
- `<rd:hardwareCalibration>` → private; `<rd:semiRealistic>` → shared.
- Two XSD schemas (Venetian Blinds pattern) under `xml/schema/raildriver/`.

**Legacy File Migration (Feature 8)**
- One-time automatic migration from freestanding `raildriver-calibration.xml` (v1/v2).
- Detents preserved; v2 semi-realistic discarded with warning. Legacy file renamed to `.bak`.
- Idempotent. Four migration paths: legacy only, both exist, neither exist, repeated calls.

**Connectivity Indicator (Feature 9)**
- Passive toolbar indicator: active icon when connected, greyed when disconnected.
- Click opens JMRI Preferences → RailDriver. Right-click popup with "Settings..." item.
- Auto-installed by `RailDriverMenuItem.attachThrottleWindow()`, idempotently.

**Package Relocation (Feature 10)**
- All RailDriver classes moved from `jmri.util.usb` to `jmri.jmrit.usb` (with `.swing` and `.configurexml` sub-packages).
- `ClassMigration.properties` entries for old → new mappings.
- `ArchitectureTest` passes with no new violations.

**Testing & Documentation (Feature 11)**
- Unit tests with EngineDriver-verified expected values for all math functions.
- Engine integration tests with mock `DccThrottle`.
- Schema validation tests (`SchemaTest`).
- Load/store round-trip tests (`LoadAndStoreTest`).
- Legacy migration test fixtures.
- Help pages for semi-realistic mode, connection indicator, and updated settings.
- Javadoc on all new/changed public APIs.

### Non-Functional Requirements

- **Threading:** All timed events through `ThreadingUtil.runOnLayoutDelayed` / `runOnGUIDelayed`. No `ScheduledExecutorService`, `java.util.Timer`, `javax.swing.Timer`, `volatile`, or `synchronized` in the engine class.
- **Backward compatibility:** Legacy calibration files must migrate without data loss. Older JMRI profiles with the freestanding XML file must continue to work (detents preserved, physics coefficients discarded with warning).
- **JMRI architecture compliance:** Code in `jmri.jmrit.usb` (not `jmri.util.usb`). SPI patterns for `PreferencesPanel` and `PreferencesManager`. `AuxiliaryConfiguration` for persistence. No cross-tree dependency violations (`ArchitectureTest` must pass).
- **Performance:** Decoder-side emit interval ≥ 50 ms to avoid overwhelming DCC backends. Ramp callbacks are lightweight (no I/O, no allocation) and run on the layout thread.
- **Platform independence:** No platform-specific code in the engine or settings. The only platform-dependent component is HID axis calibration (persisted in private profile space).
- **Accessibility:** All settings accessible through the standard JMRI Preferences UI. No custom modal frames. Standard `JmriJOptionPane` for alerts.
- **Testability:** All math functions are pure static methods testable in isolation. Engine is testable with a mock `DccThrottle` (no real hardware required). Schema fixtures in `valid/` and `invalid/` subdirectories.

---

## 6. Success Metrics

| KPI | Target | Measurement |
|-----|--------|-------------|
| **Algorithm fidelity** | Pure-throttle ramp from idle to full ≈ 19s; coast from full to idle ≈ 50s at defaults | Automated engine integration test with time assertions |
| **EngineDriver formula parity** | `getLoadPcnt` and `getBrakeDecimalPcnt` produce identical outputs to EngineDriver's source for all default slider positions | Table-driven unit tests with EngineDriver-verified expected values |
| **Architecture compliance** | Zero new `ArchitectureTest` violations after package relocation | CI pass on `ArchitectureTest` |
| **Schema correctness** | Both XSD schemas pass `xmllint` validation against `http://www.w3.org/2001/XMLSchema.xsd` | Build-time schema validation |
| **Test coverage** | All 11 features have at least one passing unit or integration test exercising their acceptance criteria | CI pass on full headless test suite |
| **Legacy migration** | All four migration paths (v1 only, v2 only, both exist, neither exist) handled correctly | Migration test fixtures in `LoadAndStoreTest` |
| **Live-apply responsiveness** | Settings changes take effect within one ramp tick (~300 ms) of save | Engine integration test verifying `updateSettings()` → `recomputeTarget()` path |
| **Backward compatibility** | Existing profiles with `raildriver-calibration.xml` load without error; detents preserved | Migration integration tests |

---

## 7. Out of Scope

- **Prototype-accurate physics.** No mass, force, m/s², Davis equation, or per-roster speed profile inside the engine. The decoder and JMRI roster speed profile remain the sole authority for actual model-train velocity.
- **EngineDriver source changes.** No modifications to the EngineDriver Android app or its source code.
- **WiThrottle protocol changes.** No changes to the WiThrottle protocol or JMRI's WiThrottle server.
- **DCC decoder behaviour.** No changes to how JMRI communicates with decoders or interprets decoder responses.
- **Other USB controllers.** This epic is specific to the RailDriver Modern Desktop (HID ID `0003:000005F3:000000D2`). Other USB throttle devices are not in scope.
- **Multi-layout support.** JMRI has no provision for a single program to handle more than one layout; this epic does not change that.
- **Sound integration.** No integration with JMRI sound/audio subsystems (e.g. Virtual Sound Decoder). Brake squeal, compressor sounds, etc. are not modelled.
- **Consist/MU operations.** The engine operates on a single throttle address. Lash-up / consist behaviour is left to the existing JMRI consisting infrastructure.
- **Touch-screen or GUI throttle integration.** The semi-realistic algorithm is wired to RailDriver physical inputs only. The JMRI on-screen throttle panel remains direct-mapped.
- **Named load presets.** No "empty", "loaded", "grain" scenario enums. The load slider is a numeric JSlider matching EngineDriver — operators interpret the multiplier values themselves.

---

## 8. Business Value

**High**

**Justification:**

1. **Cross-platform consistency.** EngineDriver is the most popular Android throttle for JMRI. Aligning the RailDriver desktop experience with EngineDriver's well-tested algorithm eliminates a significant friction point for operators who use both platforms. A consistent experience increases user satisfaction and reduces support questions.

2. **Reduced maintenance burden.** The current in-progress physics engine is complex, under-tested, and has no reference implementation to validate against. Replacing it with a simpler, EngineDriver-verified algorithm reduces the ongoing maintenance cost and makes the codebase more approachable for new contributors.

3. **Architecture modernisation.** This epic brings the RailDriver integration up to current JMRI standards — correct package location, SPI patterns for settings and persistence, profile-aware storage, comprehensive test coverage. These improvements have compound value: every future RailDriver feature starts from a cleaner, better-tested foundation.

4. **Operator immersion.** The Westinghouse air brake model, multi-source brake load scaling, and bail-off mechanics deliver a substantially more realistic operating experience. For the RailDriver's target audience — enthusiasts who invested in a physical console specifically for realism — this is a direct value proposition.

5. **Low risk.** The algorithm is a port of well-tested, well-understood code. The reference implementation (EngineDriver) has been in production on thousands of Android devices. Deviations are documented, justified by the RailDriver's physical control differences, and designed to be no-ops at light engine (matching EngineDriver exactly in the base case).
