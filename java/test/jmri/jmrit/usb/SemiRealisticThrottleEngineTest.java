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
        // Even without attach, setDirection stores the value.
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

    @BeforeEach
    public void setUp() {
        jmri.util.JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        jmri.util.JUnitUtil.tearDown();
    }
}
