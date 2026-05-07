package jmri.jmrit.usb.swing;

import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JSlider;

import jmri.jmrit.usb.SemiRealisticSettings;
import jmri.jmrit.usb.SemiRealisticThrottleEngine;
import jmri.util.JUnitUtil;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RailDriverAirStatusPanel}.
 */
public class RailDriverAirStatusPanelTest {

    private SemiRealisticThrottleEngine engine;
    private SemiRealisticSettings settings;

    @BeforeEach
    public void setUp() {
        JUnitUtil.setUp();
        engine = new SemiRealisticThrottleEngine();
        settings = new SemiRealisticSettings();
    }

    @AfterEach
    public void tearDown() {
        JUnitUtil.tearDown();
    }

    @Test
    public void testConstructor() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        assertNotNull(panel);
        panel.destroy();
    }

    @Test
    public void testSubscribeInitializesGauges() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, null);
        // Engine defaults to airLineValue = 100, airReservoirPct = 100
        assertEquals(100, panel.getAirLineGauge().getValue());
        assertEquals("100%", panel.getAirLineReadout().getText());
        assertEquals(100, panel.getAirReservoirGauge().getValue());
        assertEquals("100%", panel.getAirReservoirReadout().getText());
        panel.destroy();
    }

    @Test
    public void testLoadSliderDefaultTickCount() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, null);
        JSlider slider = panel.getLoadSlider();
        assertEquals(0, slider.getMinimum());
        assertEquals(5, slider.getMaximum()); // default numberOfLoadSteps = 5
        assertEquals(0, slider.getValue());   // default loadSliderPosition = 0
        panel.destroy();
    }

    @Test
    public void testLoadSliderIsVertical() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        JSlider slider = panel.getLoadSlider();
        assertEquals(JSlider.VERTICAL, slider.getOrientation());
        panel.destroy();
    }

    @Test
    public void testGaugesAreVertical() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        assertEquals(JSlider.VERTICAL, panel.getAirLineGauge().getOrientation());
        assertEquals(JSlider.VERTICAL, panel.getAirReservoirGauge().getOrientation());
        panel.destroy();
    }

    @Test
    public void testGaugesUseRailDriverSliderUI() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        assertInstanceOf(RailDriverSliderUI.class, panel.getAirLineGauge().getUI());
        assertInstanceOf(RailDriverSliderUI.class, panel.getAirReservoirGauge().getUI());
        panel.destroy();
    }

    @Test
    public void testGaugesAreReadOnly() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        RailDriverSliderUI airLineUI = (RailDriverSliderUI) panel.getAirLineGauge().getUI();
        RailDriverSliderUI reservoirUI = (RailDriverSliderUI) panel.getAirReservoirGauge().getUI();
        assertTrue(airLineUI.isReadOnly());
        assertTrue(reservoirUI.isReadOnly());
        panel.destroy();
    }

    @Test
    public void testLoadSliderUsesRailDriverSliderUI() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        assertInstanceOf(RailDriverSliderUI.class, panel.getLoadSlider().getUI());
        panel.destroy();
    }

    @Test
    public void testLoadSliderCustomTickCount() {
        settings.numberOfLoadSteps = 3;
        settings.loadSliderPosition = 2;
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, null);
        JSlider slider = panel.getLoadSlider();
        assertEquals(0, slider.getMinimum());
        assertEquals(3, slider.getMaximum());
        assertEquals(2, slider.getValue());
        panel.destroy();
    }

    @Test
    public void testLoadLabelTextsDefaultSettings() {
        // Default: numberOfLoadSteps=5, maxLoadPcnt=1000
        String[] labels = RailDriverAirStatusPanel.buildLoadLabelTexts(5, 1000);

        assertEquals(6, labels.length); // 0 through 5
        assertEquals("100%", labels[0]);
        assertEquals("136%", labels[1]);
        assertEquals("244%", labels[2]);
        assertEquals("424%", labels[3]);
        assertEquals("676%", labels[4]);
        assertEquals("1000%", labels[5]);
    }

    @Test
    public void testLoadLabelTextsStep0AlwaysLightEngine() {
        String[] labels = RailDriverAirStatusPanel.buildLoadLabelTexts(3, 500);
        assertEquals("100%", labels[0]);
    }

    @Test
    public void testLoadLabelTextsCustomSettings() {
        String[] labels = RailDriverAirStatusPanel.buildLoadLabelTexts(3, 500);

        assertEquals(4, labels.length);
        assertEquals("100%", labels[0]);

        double expected1 = SemiRealisticThrottleEngine.getLoadPcnt(1, 3, 500);
        assertEquals(Math.round(expected1 * 100) + "%", labels[1]);

        assertEquals("500%", labels[3]);
    }

    @Test
    public void testLoadSliderChangeCallback() {
        AtomicInteger received = new AtomicInteger(-1);
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, received::set);
        JSlider slider = panel.getLoadSlider();

        slider.setValue(3);
        assertEquals(3, received.get());
        panel.destroy();
    }

    @Test
    public void testDestroyIsIdempotent() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, null);
        panel.destroy();
        assertDoesNotThrow(() -> panel.destroy());
    }

    @Test
    public void testUnsubscribeFromEngine() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, null);
        panel.unsubscribeFromEngine();
        // Should not throw on second call
        assertDoesNotThrow(() -> panel.unsubscribeFromEngine());
        panel.destroy();
    }

    @Test
    public void testUpdateSettingsChangesSlider() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, null);
        JSlider slider = panel.getLoadSlider();
        assertEquals(5, slider.getMaximum());

        SemiRealisticSettings newSettings = new SemiRealisticSettings();
        newSettings.numberOfLoadSteps = 8;
        newSettings.maxLoadPcnt = 2000;
        newSettings.loadSliderPosition = 4;
        panel.updateSettings(newSettings);

        assertEquals(8, slider.getMaximum());
        assertEquals(4, slider.getValue());
        panel.destroy();
    }

    @Test
    public void testLoadSliderSnapsToTicks() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        JSlider slider = panel.getLoadSlider();
        assertTrue(slider.getSnapToTicks());
        panel.destroy();
    }

    @Test
    public void testSetEnabledDisablesControls() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.setEnabled(false);
        assertFalse(panel.getLoadSlider().isEnabled());
        assertFalse(panel.getAirLineGauge().isEnabled());
        assertFalse(panel.getAirReservoirGauge().isEnabled());

        panel.setEnabled(true);
        assertTrue(panel.getLoadSlider().isEnabled());
        assertTrue(panel.getAirLineGauge().isEnabled());
        assertTrue(panel.getAirReservoirGauge().isEnabled());
        panel.destroy();
    }

    @Test
    public void testGetXmlRoundTrip() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel();
        panel.subscribeToEngine(engine, settings, null);
        panel.getLoadSlider().setValue(3);
        panel.setSize(200, 300);
        panel.setLocation(50, 60);

        org.jdom2.Element xml = panel.getXml();
        assertNotNull(xml);
        assertEquals("AirStatusPanel", xml.getName());
        assertEquals("3", xml.getAttributeValue("loadSliderPosition"));

        // Restore into a fresh panel
        RailDriverAirStatusPanel panel2 = new RailDriverAirStatusPanel();
        panel2.setXml(xml);
        // WindowPreferences restores size/location
        assertEquals(200, panel2.getWidth());
        assertEquals(300, panel2.getHeight());

        panel.destroy();
        panel2.destroy();
    }

    @Test
    public void testBuildLoadLabelTextsMonotonicIncrease() {
        for (int steps = 1; steps <= 10; steps++) {
            for (int maxLoad : new int[]{200, 500, 1000, 2000}) {
                String[] labels =
                        RailDriverAirStatusPanel.buildLoadLabelTexts(steps, maxLoad);
                int prev = 0;
                for (int i = 0; i <= steps; i++) {
                    String text = labels[i].replace("%", "");
                    int val = Integer.parseInt(text);
                    assertTrue(val >= prev,
                            "Step " + i + " (" + val + "%) should be >= step " + (i - 1)
                            + " (" + prev + "%) with steps=" + steps + ", maxLoad=" + maxLoad);
                    prev = val;
                }
            }
        }
    }
}
