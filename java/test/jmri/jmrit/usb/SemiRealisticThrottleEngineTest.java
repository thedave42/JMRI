package jmri.jmrit.usb;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;

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

    @BeforeEach
    public void setUp() {
        jmri.util.JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        jmri.util.JUnitUtil.tearDown();
    }
}
