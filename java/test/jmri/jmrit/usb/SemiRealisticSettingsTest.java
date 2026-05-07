package jmri.jmrit.usb;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SemiRealisticSettings}.
 */
public class SemiRealisticSettingsTest {

    @Test
    public void testDefaults() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        assertFalse(s.persistedEnabled);
        assertFalse(s.liveEnabled);
        assertEquals(300, s.baseAccelDelayMs);
        assertEquals(800, s.baseDecelDelayMs);
        assertEquals(50, s.minEmitIntervalMs);
        assertEquals(7, s.numberOfBrakeSteps);
        assertEquals(20, s.airLineRechargePcnt);
        assertEquals(2000, s.airRefreshRateMs);
        assertEquals(5, s.airReservoirReplenishPcnt);
        assertEquals(8, s.dynBrakeMinSpeedStep);
        assertEquals(5, s.numberOfLoadSteps);
        assertEquals(1000, s.maxLoadPcnt);
        assertEquals(0, s.loadSliderPosition);
        assertEquals(1, s.speedStepIncrement);
        assertEquals(SemiRealisticSettings.DecoderBrakeMode.NONE, s.decoderBrakeMode);
    }

    @Test
    public void testCopyConstructor() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.persistedEnabled = true;
        s.baseAccelDelayMs = 500;
        s.numberOfBrakeSteps = 10;
        SemiRealisticSettings copy = new SemiRealisticSettings(s);
        assertTrue(copy.persistedEnabled);
        assertEquals(500, copy.baseAccelDelayMs);
        assertEquals(10, copy.numberOfBrakeSteps);
        // Verify it's a true copy, not aliased
        copy.baseAccelDelayMs = 999;
        assertEquals(500, s.baseAccelDelayMs);
    }

    @Test
    public void testResetToDefaults() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.persistedEnabled = true;
        s.baseAccelDelayMs = 999;
        s.resetToDefaults();
        assertFalse(s.persistedEnabled);
        assertEquals(300, s.baseAccelDelayMs);
    }

    @Test
    public void testXmlRoundTrip() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.persistedEnabled = true;
        s.baseAccelDelayMs = 500;
        s.baseDecelDelayMs = 1200;
        s.numberOfBrakeSteps = 10;
        s.decoderBrakeMode = SemiRealisticSettings.DecoderBrakeMode.ESU;
        s.esuLowThreshold = 25;

        org.jdom2.Element xml = s.writeTo();
        SemiRealisticSettings loaded = new SemiRealisticSettings();
        loaded.loadFrom(xml);

        assertTrue(loaded.persistedEnabled);
        assertEquals(500, loaded.baseAccelDelayMs);
        assertEquals(1200, loaded.baseDecelDelayMs);
        assertEquals(10, loaded.numberOfBrakeSteps);
        assertEquals(SemiRealisticSettings.DecoderBrakeMode.ESU, loaded.decoderBrakeMode);
        assertEquals(25, loaded.esuLowThreshold);
    }

    @Test
    public void testLoadFromNull() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.loadFrom(null); // Should not throw
        assertEquals(300, s.baseAccelDelayMs); // unchanged
    }

    @Test
    public void testLoadFromLegacyIgnoresUnknownElements() {
        // Simulate a legacy file with old physics elements
        org.jdom2.Element legacy = new org.jdom2.Element("semiRealistic");
        legacy.addContent(new org.jdom2.Element("enabled").setText("true"));
        legacy.addContent(new org.jdom2.Element("scenario").setText("Light engine"));
        legacy.addContent(new org.jdom2.Element("maxAccelAtRestMs2").setText("2.5"));

        SemiRealisticSettings s = new SemiRealisticSettings();
        s.loadFrom(legacy);
        assertTrue(s.persistedEnabled);
        // Old physics fields should be silently ignored; defaults preserved
        assertEquals(300, s.baseAccelDelayMs);
    }

    @Test
    public void testDecoderBrakeModeFromToken() {
        assertEquals(SemiRealisticSettings.DecoderBrakeMode.NONE,
                SemiRealisticSettings.DecoderBrakeMode.fromToken("none"));
        assertEquals(SemiRealisticSettings.DecoderBrakeMode.ESU,
                SemiRealisticSettings.DecoderBrakeMode.fromToken("ESU"));
        assertEquals(SemiRealisticSettings.DecoderBrakeMode.NONE,
                SemiRealisticSettings.DecoderBrakeMode.fromToken("bogus"));
        assertEquals(SemiRealisticSettings.DecoderBrakeMode.NONE,
                SemiRealisticSettings.DecoderBrakeMode.fromToken(null));
    }

    @Test
    public void testSpeedStepIncrementDefault() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        assertEquals(1, s.speedStepIncrement);
    }

    @Test
    public void testSpeedStepIncrementCopyAndReset() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.speedStepIncrement = 5;
        SemiRealisticSettings copy = new SemiRealisticSettings(s);
        assertEquals(5, copy.speedStepIncrement);
        copy.resetToDefaults();
        assertEquals(1, copy.speedStepIncrement);
        assertEquals(5, s.speedStepIncrement); // original unchanged
    }

    @Test
    public void testSpeedStepIncrementXmlRoundTrip() {
        SemiRealisticSettings s = new SemiRealisticSettings();
        s.speedStepIncrement = 3;
        org.jdom2.Element xml = s.writeTo();
        SemiRealisticSettings loaded = new SemiRealisticSettings();
        loaded.loadFrom(xml);
        assertEquals(3, loaded.speedStepIncrement);
    }

    @Test
    public void testSpeedStepIncrementMissingElementDefaultsTo1() {
        // Simulate a legacy XML file without the speedStepIncrement element
        org.jdom2.Element xml = new org.jdom2.Element("semiRealistic");
        xml.addContent(new org.jdom2.Element("enabled").setText("true"));
        SemiRealisticSettings loaded = new SemiRealisticSettings();
        loaded.loadFrom(xml);
        assertEquals(1, loaded.speedStepIncrement); // default
    }

    @Test
    public void testSpeedStepIncrementClampedToMinimum1() {
        org.jdom2.Element xml = new org.jdom2.Element("semiRealistic");
        xml.addContent(new org.jdom2.Element("speedStepIncrement").setText("0"));
        SemiRealisticSettings loaded = new SemiRealisticSettings();
        loaded.loadFrom(xml);
        assertEquals(1, loaded.speedStepIncrement); // clamped
    }
}
