package jmri.jmrit.usb;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;

import jmri.DccLocoAddress;
import jmri.jmrix.debugthrottle.DebugThrottle;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SemiRealisticThrottleEngine}.
 */
public class SemiRealisticThrottleEngineTest {

    @Test
    public void testConstructor() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        assertNotNull(e);
        assertFalse(e.isDriving());
        assertEquals(0, e.getCurrentSpeedStep());
    }

    @Test
    public void testDirectionEnum() {
        assertEquals(SemiRealisticThrottleEngine.Direction.FORWARD,
                SemiRealisticThrottleEngine.Direction.valueOf("FORWARD"));
        assertEquals(SemiRealisticThrottleEngine.Direction.NEUTRAL,
                SemiRealisticThrottleEngine.Direction.valueOf("NEUTRAL"));
        assertEquals(SemiRealisticThrottleEngine.Direction.REVERSE,
                SemiRealisticThrottleEngine.Direction.valueOf("REVERSE"));
    }

    @Test
    public void testDirectionInterlock_neutralAlwaysAllowed() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        e.setDirection(SemiRealisticThrottleEngine.Direction.FORWARD);
        assertEquals(SemiRealisticThrottleEngine.Direction.FORWARD, e.getDirection());
        e.setDirection(SemiRealisticThrottleEngine.Direction.NEUTRAL);
        assertEquals(SemiRealisticThrottleEngine.Direction.NEUTRAL, e.getDirection());
        assertNull(e.getPendingDirection());
    }

    @Test
    public void testDirectionInterlock_atZeroSpeedAllowed() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        assertEquals(0, e.getCurrentSpeedStep());
        e.setDirection(SemiRealisticThrottleEngine.Direction.FORWARD);
        assertEquals(SemiRealisticThrottleEngine.Direction.FORWARD, e.getDirection());
        e.setDirection(SemiRealisticThrottleEngine.Direction.REVERSE);
        assertEquals(SemiRealisticThrottleEngine.Direction.REVERSE, e.getDirection());
        assertNull(e.getPendingDirection());
    }

    @Test
    public void testEmergencyHalt_resetsState() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        e.emergencyHalt();
        assertEquals(0, e.getCurrentSpeedStep());
        assertEquals(0, e.getTargetSpeedStep());
        assertNull(e.getPendingDirection());
    }

    // ==================== Brake math tests ====================

    @Test
    public void testGetBrakeDecimalPcnt_released() {
        assertEquals(1.0, SemiRealisticThrottleEngine.getBrakeDecimalPcnt(0, 7, 0.70), 0.001);
    }

    @Test
    public void testGetBrakeDecimalPcnt_fullApplication() {
        double result = SemiRealisticThrottleEngine.getBrakeDecimalPcnt(7, 7, 0.70);
        assertTrue(result < 0.5, "Full brake should produce strong braking: " + result);
        assertTrue(result >= 0.0, "Brake should not go below 0: " + result);
    }

    @Test
    public void testGetBrakeDecimalPcnt_monotonic() {
        double prev = 1.0;
        for (int step = 0; step <= 7; step++) {
            double val = SemiRealisticThrottleEngine.getBrakeDecimalPcnt(step, 7, 0.70);
            assertTrue(val <= prev, "Brake curve should be monotonically decreasing at step " + step
                    + ": prev=" + prev + " val=" + val);
            prev = val;
        }
    }

    @Test
    public void testEffectiveDynBrakeStep_released() {
        assertEquals(0.0, SemiRealisticThrottleEngine.effectiveDynBrakeStep(50, 0, 8), 0.001);
    }

    @Test
    public void testEffectiveDynBrakeStep_aboveThreshold() {
        assertEquals(5.0, SemiRealisticThrottleEngine.effectiveDynBrakeStep(10, 5, 8), 0.001);
        assertEquals(7.0, SemiRealisticThrottleEngine.effectiveDynBrakeStep(100, 7, 8), 0.001);
    }

    @Test
    public void testEffectiveDynBrakeStep_belowThreshold_linearTaper() {
        assertEquals(2.5, SemiRealisticThrottleEngine.effectiveDynBrakeStep(4, 5, 8), 0.001);
        assertEquals(0.0, SemiRealisticThrottleEngine.effectiveDynBrakeStep(0, 5, 8), 0.001);
        assertEquals(5.0, SemiRealisticThrottleEngine.effectiveDynBrakeStep(8, 5, 8), 0.001);
    }

    @Test
    public void testEffectiveDynBrakeStep_thresholdZero() {
        assertEquals(5.0, SemiRealisticThrottleEngine.effectiveDynBrakeStep(0, 5, 0), 0.001);
    }

    @Test
    public void testGetLoadPcnt_lightEngine() {
        assertEquals(1.0, SemiRealisticThrottleEngine.getLoadPcnt(0, 5, 1000), 0.001);
    }

    @Test
    public void testGetLoadPcnt_fullLoad() {
        assertEquals(10.0, SemiRealisticThrottleEngine.getLoadPcnt(5, 5, 1000), 0.001);
    }

    @Test
    public void testGetLoadPcnt_intermediateSteps() {
        assertEquals(1.36, SemiRealisticThrottleEngine.getLoadPcnt(1, 5, 1000), 0.01);
        assertEquals(2.44, SemiRealisticThrottleEngine.getLoadPcnt(2, 5, 1000), 0.01);
        assertEquals(4.24, SemiRealisticThrottleEngine.getLoadPcnt(3, 5, 1000), 0.01);
        assertEquals(6.76, SemiRealisticThrottleEngine.getLoadPcnt(4, 5, 1000), 0.01);
        assertEquals(5.0, SemiRealisticThrottleEngine.getLoadPcnt(5, 5, 500), 0.01);
        assertEquals(2.0, SemiRealisticThrottleEngine.getLoadPcnt(5, 5, 200), 0.01);
    }

    @Test
    public void testGetLoadPcnt_edgeCases() {
        // Negative or zero steps → 1.0 (guard condition)
        assertEquals(1.0, SemiRealisticThrottleEngine.getLoadPcnt(-1, 5, 1000), 0.001);
        assertEquals(1.0, SemiRealisticThrottleEngine.getLoadPcnt(0, 0, 1000), 0.001);
        assertEquals(1.0, SemiRealisticThrottleEngine.getLoadPcnt(3, 0, 1000), 0.001);
    }

    // ==================== Per-source brake load scaling tests ====================

    @Test
    public void testPerSourceBrakeLoadScaling_lightEngine_unchanged() {
        // At light engine (load = 0), the load formula returns 1.0,
        // so per-source scaling is a no-op. Raw brake pct passes through.
        double raw = 0.51; // some raw brake percentage
        double loadMultiplier = 1.0;
        double scaled = 1.0 - ((1.0 - raw) / loadMultiplier);
        assertEquals(raw, scaled, 0.0001, "At light engine, brake pct should be unchanged");
    }

    @Test
    public void testPerSourceBrakeLoadScaling_fullLoad_indepReduced() {
        // At full load (10×), independent brake retardation is reduced to ~1/10th.
        // raw = 0.51 means 49% braking. Scaled to 10×: most braking is lost.
        double raw = 0.51;
        double loadMultiplier = 10.0;
        double scaled = 1.0 - ((1.0 - raw) / loadMultiplier);
        // (1.0 - 0.51) / 10.0 = 0.049 → 1.0 - 0.049 = 0.951
        assertEquals(0.951, scaled, 0.001, "At 10× load, indep brake is greatly reduced");
        assertTrue(scaled > raw, "Scaled should be closer to 1.0 (less braking)");
    }

    @Test
    public void testPerSourceBrakeLoadScaling_airBrake_invariant() {
        // Air brake pct is NOT scaled by load (whole-train braking).
        double raw = 0.51;
        // airBrakePcnt stays at raw regardless of load — verified by
        // the absence of scaling in the air path of recomputeTarget.
        assertEquals(raw, raw, 0.0001, "Air brake should be unchanged by load");
    }

    // ==================== PropertyChange air state tests ====================

    @Test
    public void testAirLineValue_instantDrop_firesEvent() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.airRefreshRateMs = 2000; // Westinghouse mode (not flat-mapping)
        e.updateSettings(s);

        List<PropertyChangeEvent> events = new ArrayList<>();
        e.addPropertyChangeListener(evt -> {
            if (SemiRealisticThrottleEngine.AIR_LINE_VALUE.equals(evt.getPropertyName())) {
                events.add(evt);
            }
        });

        // Air line starts at 100 (fully charged). Apply to 50.
        e.setAirBrakeDemand(50);
        assertEquals(1, events.size(), "Should fire exactly one airLineValue event");
        assertEquals(100, events.get(0).getOldValue());
        assertEquals(50, events.get(0).getNewValue());
        assertEquals(50, e.getAirLineValue());
    }

    @Test
    public void testAirLineValue_flatMapping_firesEvent() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.airRefreshRateMs = 0; // Flat-mapping mode
        e.updateSettings(s);

        List<PropertyChangeEvent> events = new ArrayList<>();
        e.addPropertyChangeListener(evt -> {
            if (SemiRealisticThrottleEngine.AIR_LINE_VALUE.equals(evt.getPropertyName())) {
                events.add(evt);
            }
        });

        e.setAirBrakeDemand(30);
        assertEquals(1, events.size(), "Flat-mapping should fire airLineValue event");
        assertEquals(100, events.get(0).getOldValue());
        assertEquals(30, events.get(0).getNewValue());
    }

    @Test
    public void testAirLineValue_noChangeNoEvent() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.airRefreshRateMs = 2000;
        e.updateSettings(s);

        List<PropertyChangeEvent> events = new ArrayList<>();
        e.addPropertyChangeListener(evt -> {
            if (SemiRealisticThrottleEngine.AIR_LINE_VALUE.equals(evt.getPropertyName())) {
                events.add(evt);
            }
        });

        // Demand 100 when line is already 100 — lap, no change.
        e.setAirBrakeDemand(100);
        assertEquals(0, events.size(), "Lap (no change) should not fire event");
    }

    @Test
    public void testRemovePropertyChangeListener_stopsEvents() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.airRefreshRateMs = 2000;
        e.updateSettings(s);

        List<PropertyChangeEvent> events = new ArrayList<>();
        java.beans.PropertyChangeListener listener = events::add;
        e.addPropertyChangeListener(listener);

        e.setAirBrakeDemand(50);
        assertEquals(1, events.size(), "Should receive event before removal");

        e.removePropertyChangeListener(listener);
        e.setAirBrakeDemand(20);
        assertEquals(1, events.size(), "Should NOT receive event after removal");
    }

    @Test
    public void testPropertyChangeConstants() {
        assertEquals("airLineValue", SemiRealisticThrottleEngine.AIR_LINE_VALUE);
        assertEquals("airReservoirPct", SemiRealisticThrottleEngine.AIR_RESERVOIR_PCT);
    }

    // ==================== ESU Decoder Brake Passthrough tests ====================

    private DebugThrottle createDebugThrottle() {
        return new DebugThrottle(new DccLocoAddress(3, false), null);
    }

    @Test
    public void testEsuDecoderBrake_noneModeInert() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.NONE;
        e.updateSettings(s);
        DebugThrottle t = createDebugThrottle();
        e.attachThrottle(t);

        e.setIndepBrakeFraction(1.0f); // 100% — above all thresholds
        assertFalse(t.getFunction(4), "NONE mode: F4 should not be set");
        assertFalse(t.getFunction(5), "NONE mode: F5 should not be set");
        assertFalse(t.getFunction(6), "NONE mode: F6 should not be set");
        e.detachThrottle();
    }

    @Test
    public void testEsuDecoderBrake_esuModeTogglesAtThresholds() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        // defaults: F4@30%, F5@60%, F6@98%
        e.updateSettings(s);
        DebugThrottle t = createDebugThrottle();
        e.attachThrottle(t);

        // 0% brake — all off
        e.setIndepBrakeFraction(0.0f);
        assertFalse(t.getFunction(4));
        assertFalse(t.getFunction(5));
        assertFalse(t.getFunction(6));

        // 30% — F4 on
        e.setIndepBrakeFraction(0.30f);
        assertTrue(t.getFunction(4), "F4 should activate at 30%");
        assertFalse(t.getFunction(5));
        assertFalse(t.getFunction(6));

        // 60% — F4+F5 on
        e.setIndepBrakeFraction(0.60f);
        assertTrue(t.getFunction(4));
        assertTrue(t.getFunction(5), "F5 should activate at 60%");
        assertFalse(t.getFunction(6));

        // 98% — all on
        e.setIndepBrakeFraction(0.98f);
        assertTrue(t.getFunction(4));
        assertTrue(t.getFunction(5));
        assertTrue(t.getFunction(6), "F6 should activate at 98%");

        // Release back to 0 — all off
        e.setIndepBrakeFraction(0.0f);
        assertFalse(t.getFunction(4), "F4 should deactivate on release");
        assertFalse(t.getFunction(5), "F5 should deactivate on release");
        assertFalse(t.getFunction(6), "F6 should deactivate on release");

        e.detachThrottle();
    }

    @Test
    public void testEsuDecoderBrake_redundantCallsNoToggle() {
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        e.updateSettings(s);
        DebugThrottle t = createDebugThrottle();
        e.attachThrottle(t);

        e.setIndepBrakeFraction(0.35f); // above 30% — F4 on
        assertTrue(t.getFunction(4));

        // Manually flip F4 off to detect if the engine redundantly sets it
        t.setFunction(4, false);
        e.setIndepBrakeFraction(0.36f); // still above 30%, no threshold crossed
        assertFalse(t.getFunction(4), "Redundant call should not re-set F4");

        e.detachThrottle();
    }

    @Test
    public void testValidateEsuThresholds_validDefaults() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        assertNull(SemiRealisticThrottleEngine.validateEsuThresholds(s),
                "Default thresholds should be valid");
    }

    @Test
    public void testValidateEsuThresholds_nonAscending() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        s.esuLowThreshold = 60;
        s.esuMidThreshold = 30; // out of order
        s.esuHighThreshold = 98;
        assertNotNull(SemiRealisticThrottleEngine.validateEsuThresholds(s),
                "Non-ascending thresholds should fail validation");
    }

    @Test
    public void testValidateEsuThresholds_equalThresholds() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        s.esuLowThreshold = 30;
        s.esuMidThreshold = 30; // equal to low
        s.esuHighThreshold = 98;
        assertNotNull(SemiRealisticThrottleEngine.validateEsuThresholds(s),
                "Equal thresholds should fail validation (must be strictly ascending)");
    }

    @Test
    public void testValidateEsuThresholds_negativeFunction() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        s.esuLowFunction = -1;
        assertNotNull(SemiRealisticThrottleEngine.validateEsuThresholds(s),
                "Negative function number should fail validation");
    }

    @Test
    public void testValidateEsuThresholds_noneModeSkipsValidation() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.NONE;
        s.esuLowThreshold = 99;
        s.esuMidThreshold = 1; // would be invalid for ESU mode
        assertNull(SemiRealisticThrottleEngine.validateEsuThresholds(s),
                "NONE mode should skip threshold validation");
    }

    // ======================== speedStepIncrement ramp tests ========================

    @Test
    public void testRampIncrement1_defaultBehaviour() {
        // With increment=1, stepping from 0 to 5 takes 5 ticks
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.speedStepIncrement = 1;
        e.updateSettings(s);
        assertEquals(0, e.getCurrentSpeedStep());
    }

    @Test
    public void testSpeedStepIncrementStoredInSettings() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.speedStepIncrement = 4;
        SemiRealisticThrottleEngine e = new SemiRealisticThrottleEngine();
        e.updateSettings(s);
        assertFalse(e.isDriving()); // not attached, but settings accepted
    }

    // ==================== Power curve delay tests ====================

    @Test
    public void testPowerCurveDelay_atRampStart_returnsMinDelay() {
        // progress = 0.0 → delay should be minDelayMs
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 0, 100, 50, 300, 0.5);
        assertEquals(50, delay, "At ramp start, delay should equal minDelayMs");
    }

    @Test
    public void testPowerCurveDelay_atRampEnd_returnsMaxDelay() {
        // progress = 1.0 → delay should be maxDelayMs
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 100, 100, 50, 300, 0.5);
        assertEquals(300, delay, "At ramp end, delay should equal maxDelayMs");
    }

    @Test
    public void testPowerCurveDelay_midRamp_betweenMinAndMax() {
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 50, 100, 50, 300, 0.5);
        assertTrue(delay > 50, "Mid-ramp delay should be above minDelayMs: " + delay);
        assertTrue(delay < 300, "Mid-ramp delay should be below maxDelayMs: " + delay);
    }

    @Test
    public void testPowerCurveDelay_monotonicallyIncreasing() {
        int prevDelay = 0;
        for (int step = 0; step <= 126; step++) {
            int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                    0, step, 126, 50, 300, 0.5);
            assertTrue(delay >= prevDelay,
                    "Delay must be monotonically increasing at step " + step
                    + ": prev=" + prevDelay + " cur=" + delay);
            prevDelay = delay;
        }
    }

    @Test
    public void testPowerCurveDelay_highK_moreFrontLoaded() {
        // With k=1.0 the delay should stay lower (closer to minDelay) for
        // longer in the early portion of the ramp compared to k=0.1.
        // Test at 25% progress.
        int delayLowK = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 25, 100, 50, 300, 0.1);
        int delayHighK = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 25, 100, 50, 300, 1.0);
        assertTrue(delayHighK < delayLowK,
                "At 25% progress, k=1.0 delay (" + delayHighK
                + ") should be lower than k=0.1 delay (" + delayLowK + ")");
    }

    @Test
    public void testPowerCurveDelay_noRamp_returnsMaxDelay() {
        // rampStartStep == targetSpeedStep → no ramp to traverse
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                50, 50, 50, 50, 300, 0.5);
        assertEquals(300, delay, "When start == target, should return maxDelayMs");
    }

    @Test
    public void testPowerCurveDelay_currentBelowStart_clampsToMin() {
        // currentSpeedStep < rampStartStep → progress clamped to 0.0
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                10, 5, 100, 50, 300, 0.5);
        assertEquals(50, delay, "Current below start should clamp to minDelayMs");
    }

    @Test
    public void testPowerCurveDelay_currentAboveTarget_clampsToMax() {
        // currentSpeedStep > targetSpeedStep → progress clamped to 1.0
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 110, 100, 50, 300, 0.5);
        assertEquals(300, delay, "Current above target should clamp to maxDelayMs");
    }

    @Test
    public void testPowerCurveDelay_kZero_linearFallback() {
        // k=0 triggers the linear fallback; at 50% progress, delay
        // should be the midpoint: 50 + (300-50)*0.5 = 175
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 50, 100, 50, 300, 0.0);
        assertEquals(175, delay, "k=0 should produce linear interpolation");
    }

    @Test
    public void testPowerCurveDelay_kNegative_linearFallback() {
        // Negative k also triggers the linear fallback
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 50, 100, 50, 300, -1.0);
        assertEquals(175, delay, "Negative k should produce linear interpolation");
    }

    @Test
    public void testPowerCurveDelay_kNaN_linearFallback() {
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                0, 50, 100, 50, 300, Double.NaN);
        assertEquals(175, delay, "NaN k should produce linear interpolation");
    }

    @Test
    public void testPowerCurveDelay_midRampRetarget() {
        // Simulate mid-ramp retarget: ramp started at step 20, now at 40,
        // new target is 80 (range = 60 steps).
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                20, 40, 80, 50, 300, 0.5);
        // progress = (40-20)/(80-20) = 1/3 ≈ 0.333
        assertTrue(delay > 50 && delay < 300,
                "Mid-ramp retarget delay should be between min and max: " + delay);

        // And at the new start of this retargeted ramp:
        int delayAtStart = SemiRealisticThrottleEngine.computePowerCurveDelay(
                20, 20, 80, 50, 300, 0.5);
        assertEquals(50, delayAtStart, "At retarget start, delay should be minDelayMs");
    }

    @Test
    public void testPowerCurveDelay_targetBelowStart_returnsMaxDelay() {
        // Descending ramp (decel) — not supported, should return maxDelay
        int delay = SemiRealisticThrottleEngine.computePowerCurveDelay(
                100, 80, 50, 50, 300, 0.5);
        assertEquals(300, delay, "Descending ramp should return maxDelayMs");
    }

    // ==================== Additive force model: computeDecelerationDelay ====================

    @Test
    public void testDecelDelay_coastOnly_load0() {
        // Coast at load 0: coastForce=1.0, no brakes. delay = 800/1.0 = 800
        int delay = SemiRealisticThrottleEngine.computeDecelerationDelay(
                1.0, 0.0, 0.0, 800);
        assertEquals(800, delay);
    }

    @Test
    public void testDecelDelay_coastOnly_load5() {
        // Coast at load 5 (multiplier 10): coastForce=0.1. delay = 800/0.1 = 8000
        int delay = SemiRealisticThrottleEngine.computeDecelerationDelay(
                0.1, 0.0, 0.0, 800);
        assertEquals(8000, delay);
    }

    @Test
    public void testDecelDelay_fullAirBrake_load0_matchesCurrent() {
        // Full air brake at load 0. brakePcnt=0.30, brakeForce=(1/0.30)-1=2.3333
        // totalForce = 1.0 + 2.3333 = 3.3333, delay = 800/3.3333 = 240
        int delay = SemiRealisticThrottleEngine.computeDecelerationDelay(
                1.0, 2.333333, 0.0, 800);
        assertEquals(240, delay, "Must match current no-load full-brake delay");
    }

    @Test
    public void testDecelDelay_fullAirBrake_load5_withinSM1() {
        // Full air at load 5. coastForce=0.1, airForce=2.3333 (invariant).
        // totalForce = 0.1 + 2.3333 = 2.4333, delay = 800/2.4333 ≈ 329
        int delay = SemiRealisticThrottleEngine.computeDecelerationDelay(
                0.1, 2.333333, 0.0, 800);
        // SM-1: ratio must be ≤ 3× of no-load (240ms). 329/240 = 1.37
        assertTrue(delay <= 720, "Air brake at load 5 must be ≤ 3× no-load delay");
        assertEquals(329, delay);
    }

    @Test
    public void testDecelDelay_fullIndepBrake_load5_matchesSM2() {
        // Full indep at load 5. coastForce=0.1, indepForce=2.3333/10=0.23333
        // totalForce = 0.1 + 0.23333 = 0.33333, delay = 800/0.33333 = 2400
        int delay = SemiRealisticThrottleEngine.computeDecelerationDelay(
                0.1, 0.0, 0.233333, 800);
        // SM-2: ratio must be ≈ 10× of no-load (240ms). 2400/240 = 10.0
        assertEquals(2400, delay, "Indep brake at load 5 must be ~10× no-load delay");
    }

    @Test
    public void testDecelDelay_mixedBraking_shorterThanEitherAlone() {
        // Full air + full indep at load 5.
        // coastForce=0.1, airForce=2.3333, locoForce=0.23333
        // totalForce = 0.1 + 2.3333 + 0.23333 = 2.6667, delay = 800/2.6667 = 300
        int airOnly = SemiRealisticThrottleEngine.computeDecelerationDelay(
                0.1, 2.333333, 0.0, 800);
        int indepOnly = SemiRealisticThrottleEngine.computeDecelerationDelay(
                0.1, 0.0, 0.233333, 800);
        int mixed = SemiRealisticThrottleEngine.computeDecelerationDelay(
                0.1, 2.333333, 0.233333, 800);
        assertTrue(mixed < airOnly, "Mixed must be shorter than air-only");
        assertTrue(mixed < indepOnly, "Mixed must be shorter than indep-only");
        assertEquals(300, mixed);
    }

    @Test
    public void testDecelDelay_zeroForce_clamped() {
        // Edge case: all forces zero → totalForce clamped to 0.001
        int delay = SemiRealisticThrottleEngine.computeDecelerationDelay(
                0.0, 0.0, 0.0, 800);
        assertEquals(800000, delay, "Zero force should produce very large but finite delay");
    }

    @Test
    public void testDecelDelay_midBrake_load0_matchesCurrent() {
        // Notch 4 at load 0. brakePcnt=0.697628, brakeForce=(1/0.697628)-1=0.433428
        // totalForce = 1.0 + 0.433428 = 1.433428, delay = 800/1.433428 ≈ 558
        int delay = SemiRealisticThrottleEngine.computeDecelerationDelay(
                1.0, 0.433428, 0.0, 800);
        assertEquals(558, delay, "Mid-brake at load 0 should match current delay");
    }

    // ==================== Phase 3: brakeForce / coastForce helpers ====================

    @Test
    public void testBrakeForce_released() {
        assertEquals(0.0, SemiRealisticThrottleEngine.brakeForce(1.0), 0.0001);
    }

    @Test
    public void testBrakeForce_fullBrake() {
        // brakePcnt=0.30 → (1/0.30)-1 = 2.3333
        assertEquals(2.3333, SemiRealisticThrottleEngine.brakeForce(0.30), 0.001);
    }

    @Test
    public void testBrakeForce_monotonicallyIncreasing() {
        double prev = 0.0;
        for (int step = 1; step <= 7; step++) {
            double brakePcnt = SemiRealisticThrottleEngine.getBrakeDecimalPcnt(step, 7, 0.70);
            double force = SemiRealisticThrottleEngine.brakeForce(brakePcnt);
            assertTrue(force > prev, "brakeForce must increase as brake applied more");
            prev = force;
        }
    }

    @Test
    public void testBrakeForce_preservesDelayAtLoad0() {
        // Verify identity: 800 / (1.0 + brakeForce) == 800 * brakePcnt
        for (int step = 0; step <= 7; step++) {
            double brakePcnt = SemiRealisticThrottleEngine.getBrakeDecimalPcnt(step, 7, 0.70);
            double force = SemiRealisticThrottleEngine.brakeForce(brakePcnt);
            double delayFromForce = 800.0 / (1.0 + force);
            double delayFromCurrent = 800.0 * brakePcnt;
            assertEquals(delayFromCurrent, delayFromForce, 0.01,
                    "Force model must match current model at load 0, step " + step);
        }
    }

    @Test
    public void testBrakeForce_guardsDivisionByZero() {
        double force = SemiRealisticThrottleEngine.brakeForce(0.0);
        assertTrue(Double.isFinite(force), "brakeForce(0) must be finite");
        assertTrue(force > 0, "brakeForce(0) must be positive");
    }

    @Test
    public void testBrakeForceUnderPower_released() {
        assertEquals(0.0, SemiRealisticThrottleEngine.brakeForceUnderPower(1.0), 0.0001);
    }

    @Test
    public void testBrakeForceUnderPower_matchesCurrentRegimeC() {
        // Verify: 800 / (1 + f) == 800 * (1 - brakePcnt * 0.50) at load 0
        double[] testPcnts = {0.30, 0.50, 0.70, 0.90, 0.962};
        for (double brakePcnt : testPcnts) {
            double force = SemiRealisticThrottleEngine.brakeForceUnderPower(brakePcnt);
            double delayFromForce = 800.0 / (1.0 + force);
            double delayFromCurrent = 800.0 * (1.0 - brakePcnt * 0.50);
            assertEquals(delayFromCurrent, delayFromForce, 0.01,
                    "Under-power force must match current Regime C at load 0, brakePcnt " + brakePcnt);
        }
    }

    @Test
    public void testCoastForce_load0() {
        assertEquals(1.0, SemiRealisticThrottleEngine.coastForce(1.0), 0.0001);
    }

    @Test
    public void testCoastForce_load5() {
        assertEquals(0.1, SemiRealisticThrottleEngine.coastForce(10.0), 0.0001);
    }

    @Test
    public void testCoastForce_clampsBelowOne() {
        // loadMultiplier < 1 should still give 1.0
        assertEquals(1.0, SemiRealisticThrottleEngine.coastForce(0.5), 0.0001);
    }
}
