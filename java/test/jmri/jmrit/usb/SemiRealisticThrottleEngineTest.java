package jmri.jmrit.usb;

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
        assertEquals(5.0, SemiRealisticThrottleEngine.getLoadPcnt(5, 5, 500), 0.01);
    }

    @BeforeEach
    public void setUp() {
        jmri.util.JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        jmri.util.JUnitUtil.tearDown();
    }
}
