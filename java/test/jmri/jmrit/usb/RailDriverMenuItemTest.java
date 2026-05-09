package jmri.jmrit.usb;

import org.junit.jupiter.api.*;

/**
 *
 * @author Paul Bender Copyright (C) 2017
 */
public class RailDriverMenuItemTest {

    @Test
    public void testCTor() {
        RailDriverMenuItem t = new RailDriverMenuItem();
        Assertions.assertNotNull( t, "exists");
    }

    @Test
    public void testFormatAddress_noThrottleFrame() {
        RailDriverMenuItem t = new RailDriverMenuItem();
        // No active throttle frame → "Pro"
        Assertions.assertEquals("Pro", t.formatAddress());
    }

    @Test
    public void testFormatSpeed_noThrottleFrame() {
        RailDriverMenuItem t = new RailDriverMenuItem();
        // No active throttle frame → "  0"
        Assertions.assertEquals("  0", t.formatSpeed());
    }

    @Test
    public void testLedDisplayModeEnum() {
        // Verify all enum values exist
        Assertions.assertEquals(5, RailDriverMenuItem.LedDisplayMode.values().length);
        Assertions.assertNotNull(RailDriverMenuItem.LedDisplayMode.valueOf("IDLE"));
        Assertions.assertNotNull(RailDriverMenuItem.LedDisplayMode.valueOf("ADDRESS"));
        Assertions.assertNotNull(RailDriverMenuItem.LedDisplayMode.valueOf("SPEED"));
        Assertions.assertNotNull(RailDriverMenuItem.LedDisplayMode.valueOf("FUNCTION_FLASH"));
        Assertions.assertNotNull(RailDriverMenuItem.LedDisplayMode.valueOf("DYN_BRAKE"));
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
