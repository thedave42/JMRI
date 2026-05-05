package jmri.jmrit.usb;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RailDriverPreferencesManager}.
 * Focuses on fragment I/O, legacy migration, and PCS events.
 */
public class RailDriverPreferencesManagerTest {

    @Test
    public void testConstructor() {
        RailDriverPreferencesManager mgr = new RailDriverPreferencesManager();
        assertNotNull(mgr);
        // Not yet initialized — getCalibration returns a fresh default.
        RailDriverCalibration cal = mgr.getCalibration();
        assertNotNull(cal);
        assertFalse(cal.semiRealistic().persistedEnabled);
    }

    @Test
    public void testApplySettings_firesPcsEvent() {
        RailDriverPreferencesManager mgr = new RailDriverPreferencesManager();
        List<PropertyChangeEvent> events = new ArrayList<>();
        mgr.addPropertyChangeListener(events::add);

        SemiRealisticSettings s = new SemiRealisticSettings();
        s.baseAccelDelayMs = 500;
        mgr.applySettings(s);

        boolean found = events.stream()
                .anyMatch(e -> RailDriverPreferencesManager.SETTINGS_CHANGED
                        .equals(e.getPropertyName()));
        assertTrue(found, "applySettings should fire SETTINGS_CHANGED");
        assertEquals(500, mgr.getCalibration().semiRealistic().baseAccelDelayMs);
    }

    @Test
    public void testApplyCalibration_firesPcsEvent() {
        RailDriverPreferencesManager mgr = new RailDriverPreferencesManager();
        List<PropertyChangeEvent> events = new ArrayList<>();
        mgr.addPropertyChangeListener(events::add);

        RailDriverCalibration cal = new RailDriverCalibration();
        cal.reverser().forward = 42;
        mgr.applyCalibration(cal);

        boolean found = events.stream()
                .anyMatch(e -> RailDriverPreferencesManager.CALIBRATION_CHANGED
                        .equals(e.getPropertyName()));
        assertTrue(found, "applyCalibration should fire CALIBRATION_CHANGED");
        assertEquals(Integer.valueOf(42),
                mgr.getCalibration().reverser().forward);
    }

    @Test
    public void testApplyAndSave_firesBothEvents() {
        RailDriverPreferencesManager mgr = new RailDriverPreferencesManager();
        List<String> events = new ArrayList<>();
        mgr.addPropertyChangeListener(e -> events.add(e.getPropertyName()));

        RailDriverCalibration cal = new RailDriverCalibration();
        cal.reverser().forward = 99;
        cal.semiRealistic().baseAccelDelayMs = 700;
        mgr.applyAndSave(cal);

        assertTrue(events.contains(RailDriverPreferencesManager.CALIBRATION_CHANGED));
        assertTrue(events.contains(RailDriverPreferencesManager.SETTINGS_CHANGED));
    }

    @Test
    public void testRemoveListener_stopsEvents() {
        RailDriverPreferencesManager mgr = new RailDriverPreferencesManager();
        List<PropertyChangeEvent> events = new ArrayList<>();
        java.beans.PropertyChangeListener listener = events::add;
        mgr.addPropertyChangeListener(listener);

        mgr.applySettings(new SemiRealisticSettings());
        assertFalse(events.isEmpty(), "Should receive events before removal");

        int count = events.size();
        mgr.removePropertyChangeListener(listener);
        mgr.applySettings(new SemiRealisticSettings());
        assertEquals(count, events.size(), "Should NOT receive events after removal");
    }

    @Test
    public void testValidateEsuThresholds_delegatesToEngine() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        assertNull(SemiRealisticThrottleEngine.validateEsuThresholds(s));
    }

    @Test
    public void testConstants() {
        assertEquals("http://jmri.org/xml/schema/raildriver/3",
                RailDriverPreferencesManager.NAMESPACE);
        assertEquals("hardwareCalibration",
                RailDriverPreferencesManager.HW_CAL_ELEMENT);
        assertEquals("semiRealistic",
                RailDriverPreferencesManager.SETTINGS_ELEMENT);
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
