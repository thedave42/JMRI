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

    @BeforeEach
    public void setUp() {
        jmri.util.JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        jmri.util.JUnitUtil.tearDown();
    }
}
