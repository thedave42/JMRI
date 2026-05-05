package jmri.jmrit.usb;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ESU decoder brake passthrough in
 * {@link SemiRealisticThrottleEngine} and threshold validation in
 * {@link SemiRealisticSettings}.
 */
public class EsuDecoderBrakeTest {

    private SemiRealisticThrottleEngine engine;
    private MockDccThrottle mockThrottle;
    private SemiRealisticSettings settings;

    @BeforeEach
    public void setUp() {
        jmri.util.JUnitUtil.setUp();
        engine = new SemiRealisticThrottleEngine();
        mockThrottle = new MockDccThrottle();
        settings = new SemiRealisticSettings();
        settings.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        settings.esuLowFunction = 4;
        settings.esuMidFunction = 5;
        settings.esuHighFunction = 6;
        settings.esuLowThreshold = 30;
        settings.esuMidThreshold = 60;
        settings.esuHighThreshold = 98;
    }

    @AfterEach
    public void tearDown() {
        if (engine != null) engine.dispose();
        jmri.util.JUnitUtil.tearDown();
    }

    // ========== ESU mode: threshold crossing tests ==========

    @Test
    public void testEsuLowThresholdOn() {
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);

        engine.setIndepBrakeFraction(0.30f); // 30% = exactly at low threshold
        assertTrue(mockThrottle.getFunction(4), "F4 should be ON at 30%");
        assertFalse(mockThrottle.getFunction(5), "F5 should be OFF at 30%");
        assertFalse(mockThrottle.getFunction(6), "F6 should be OFF at 30%");
    }

    @Test
    public void testEsuMidThresholdOn() {
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);

        engine.setIndepBrakeFraction(0.60f); // 60% = at mid threshold
        assertTrue(mockThrottle.getFunction(4), "F4 should be ON at 60%");
        assertTrue(mockThrottle.getFunction(5), "F5 should be ON at 60%");
        assertFalse(mockThrottle.getFunction(6), "F6 should be OFF at 60%");
    }

    @Test
    public void testEsuHighThresholdOn() {
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);

        engine.setIndepBrakeFraction(0.98f); // 98% = at high threshold
        assertTrue(mockThrottle.getFunction(4), "F4 should be ON at 98%");
        assertTrue(mockThrottle.getFunction(5), "F5 should be ON at 98%");
        assertTrue(mockThrottle.getFunction(6), "F6 should be ON at 98%");
    }

    @Test
    public void testEsuFullBrakeThenRelease() {
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);

        // Apply full brake
        engine.setIndepBrakeFraction(1.0f);
        assertTrue(mockThrottle.getFunction(4));
        assertTrue(mockThrottle.getFunction(5));
        assertTrue(mockThrottle.getFunction(6));

        // Release brake
        engine.setIndepBrakeFraction(0.0f);
        assertFalse(mockThrottle.getFunction(4), "F4 should be OFF after release");
        assertFalse(mockThrottle.getFunction(5), "F5 should be OFF after release");
        assertFalse(mockThrottle.getFunction(6), "F6 should be OFF after release");
    }

    @Test
    public void testEsuBelowLowThresholdNoFunctions() {
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);

        engine.setIndepBrakeFraction(0.29f); // 29% = below low threshold
        assertFalse(mockThrottle.getFunction(4));
        assertFalse(mockThrottle.getFunction(5));
        assertFalse(mockThrottle.getFunction(6));
    }

    @Test
    public void testEsuRedundantCallsPrevented() {
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);

        engine.setIndepBrakeFraction(0.50f); // 50% = F4 on
        int callCount4 = mockThrottle.getFunctionCallCount(4);

        engine.setIndepBrakeFraction(0.51f); // Still only F4 on — no change
        assertEquals(callCount4, mockThrottle.getFunctionCallCount(4),
                "No additional setFunction call when state unchanged");
    }

    // ========== NONE mode: short-circuit ==========

    @Test
    public void testNoneModeNoFunctionCalls() {
        settings.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.NONE;
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);

        engine.setIndepBrakeFraction(1.0f); // Full brake
        assertFalse(mockThrottle.getFunction(4), "F4 should be OFF in NONE mode");
        assertFalse(mockThrottle.getFunction(5), "F5 should be OFF in NONE mode");
        assertFalse(mockThrottle.getFunction(6), "F6 should be OFF in NONE mode");
        assertEquals(0, mockThrottle.getTotalFunctionCallCount(),
                "No function calls in NONE mode");
    }

    // ========== Detach clears functions ==========

    @Test
    public void testDetachClearsFunctions() {
        engine.updateSettings(settings);
        engine.attachThrottle(mockThrottle);
        engine.setIndepBrakeFraction(1.0f);
        assertTrue(mockThrottle.getFunction(6));

        engine.detachThrottle();
        assertFalse(mockThrottle.getFunction(4));
        assertFalse(mockThrottle.getFunction(5));
        assertFalse(mockThrottle.getFunction(6));
    }

    // ========== Threshold validation ==========

    @Test
    public void testValidThresholdsPass() {
        assertNull(settings.validateEsuThresholds());
    }

    @Test
    public void testInvalidThresholdsEqualLowMid() {
        settings.esuLowThreshold = 50;
        settings.esuMidThreshold = 50;
        assertNotNull(settings.validateEsuThresholds());
    }

    @Test
    public void testInvalidThresholdsDescending() {
        settings.esuLowThreshold = 90;
        settings.esuMidThreshold = 60;
        settings.esuHighThreshold = 30;
        assertNotNull(settings.validateEsuThresholds());
    }

    @Test
    public void testInvalidNegativeFunction() {
        settings.esuLowFunction = -1;
        assertNotNull(settings.validateEsuThresholds());
    }

    @Test
    public void testValidCustomThresholds() {
        settings.esuLowThreshold = 10;
        settings.esuMidThreshold = 50;
        settings.esuHighThreshold = 90;
        assertNull(settings.validateEsuThresholds());
    }

    // ========== Mock DccThrottle ==========

    /**
     * Minimal mock throttle for testing function calls. Tracks function
     * on/off state and call counts.
     */
    private static class MockDccThrottle extends jmri.jmrix.AbstractThrottle {
        private final int[] callCounts = new int[29];
        private int totalCalls = 0;

        MockDccThrottle() {
            super(null);
            setSpeedStepMode(jmri.SpeedStepMode.NMRA_DCC_128);
        }

        @Override
        public jmri.LocoAddress getLocoAddress() {
            return new jmri.DccLocoAddress(3, false);
        }

        @Override
        protected void throttleDispose() {
            // no-op for test
        }

        @Override protected void sendFunctionGroup1() {}
        @Override protected void sendFunctionGroup2() {}
        @Override protected void sendFunctionGroup3() {}
        @Override protected void sendFunctionGroup4() {}
        @Override protected void sendFunctionGroup5() {}

        @Override
        public void setFunction(int num, boolean value) {
            super.setFunction(num, value);
            if (num >= 0 && num < callCounts.length) {
                callCounts[num]++;
            }
            totalCalls++;
        }

        int getFunctionCallCount(int num) {
            return (num >= 0 && num < callCounts.length) ? callCounts[num] : 0;
        }

        int getTotalFunctionCallCount() { return totalCalls; }
    }
}
