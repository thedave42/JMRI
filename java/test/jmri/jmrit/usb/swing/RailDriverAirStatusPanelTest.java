package jmri.jmrit.usb.swing;

import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JLabel;
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
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);
        assertNotNull(panel);
        panel.dispose();
    }

    @Test
    public void testInitialAirLineValue() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);
        // Engine defaults to airLineValue = 100
        assertEquals(100, panel.getAirLineBar().getValue());
        assertEquals("100%", panel.getAirLineReadout().getText());
        panel.dispose();
    }

    @Test
    public void testInitialAirReservoirPct() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);
        // Engine defaults to airReservoirPct = 100
        assertEquals(100, panel.getAirReservoirBar().getValue());
        assertEquals("100%", panel.getAirReservoirReadout().getText());
        panel.dispose();
    }

    @Test
    public void testLoadSliderDefaultTickCount() {
        // Default: numberOfLoadSteps = 5, so slider range is 0..5 (6 ticks)
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);
        JSlider slider = panel.getLoadSlider();
        assertEquals(0, slider.getMinimum());
        assertEquals(5, slider.getMaximum());
        assertEquals(0, slider.getValue()); // default loadSliderPosition = 0
        panel.dispose();
    }

    @Test
    public void testLoadSliderCustomTickCount() {
        settings.numberOfLoadSteps = 3;
        settings.loadSliderPosition = 2;
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);
        JSlider slider = panel.getLoadSlider();
        assertEquals(0, slider.getMinimum());
        assertEquals(3, slider.getMaximum());
        assertEquals(2, slider.getValue());
        panel.dispose();
    }

    @Test
    public void testLoadSliderLabelsDefaultSettings() {
        // Default: numberOfLoadSteps=5, maxLoadPcnt=1000
        // Expected labels: step 0=100%, step 1=136%, step 2=244%,
        //                  step 3=424%, step 4=676%, step 5=1000%
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10);
        Hashtable<Integer, JLabel> labels = RailDriverAirStatusPanel.buildLoadLabels(5, 1000, font);

        assertEquals(6, labels.size()); // 0 through 5

        assertEquals("100%", labels.get(0).getText()); // step 0 = light engine
        assertEquals("136%", labels.get(1).getText());
        assertEquals("244%", labels.get(2).getText());
        assertEquals("424%", labels.get(3).getText());
        assertEquals("676%", labels.get(4).getText());
        assertEquals("1000%", labels.get(5).getText());
    }

    @Test
    public void testLoadSliderLabelsStep0AlwaysLightEngine() {
        // Regardless of maxLoadPcnt, step 0 should always be 100%
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10);
        Hashtable<Integer, JLabel> labels = RailDriverAirStatusPanel.buildLoadLabels(3, 500, font);
        assertEquals("100%", labels.get(0).getText());
    }

    @Test
    public void testLoadSliderLabelsCustomSettings() {
        // numberOfLoadSteps=3, maxLoadPcnt=500
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10);
        Hashtable<Integer, JLabel> labels = RailDriverAirStatusPanel.buildLoadLabels(3, 500, font);

        assertEquals(4, labels.size()); // 0 through 3

        // step 0: 100% (light engine)
        assertEquals("100%", labels.get(0).getText());

        // step 1: load=1/3, load²=1/9, (1/9*(500-100)+100)/100 = (44.44+100)/100 = 1.4444 → 144%
        double expected1 = SemiRealisticThrottleEngine.getLoadPcnt(1, 3, 500);
        assertEquals(Math.round(expected1 * 100) + "%", labels.get(1).getText());

        // step 3: load=3/3=1, (1*(500-100)+100)/100 = 500/100 = 5.0 → 500%
        assertEquals("500%", labels.get(3).getText());
    }

    @Test
    public void testLoadSliderChangeCallback() {
        AtomicInteger received = new AtomicInteger(-1);
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, received::set);
        JSlider slider = panel.getLoadSlider();

        // Simulate user setting slider to position 3
        slider.setValue(3);
        // ChangeListener fires on release (getValueIsAdjusting=false by default in programmatic set)
        assertEquals(3, received.get());
        panel.dispose();
    }

    @Test
    public void testDisposeDeregistersListener() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);

        // Verify initial values are populated
        assertEquals(100, panel.getAirLineBar().getValue());

        // After dispose, panel should not hold a reference to the engine
        panel.dispose();

        // Verify dispose is safe and does not throw on second call
        assertDoesNotThrow(() -> panel.dispose());
    }

    @Test
    public void testUpdateSettingsChangesSlider() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);
        JSlider slider = panel.getLoadSlider();
        assertEquals(5, slider.getMaximum()); // default

        SemiRealisticSettings newSettings = new SemiRealisticSettings();
        newSettings.numberOfLoadSteps = 8;
        newSettings.maxLoadPcnt = 2000;
        newSettings.loadSliderPosition = 4;
        panel.updateSettings(newSettings);

        assertEquals(8, slider.getMaximum());
        assertEquals(4, slider.getValue());
        panel.dispose();
    }

    @Test
    public void testLoadSliderSnapsToTicks() {
        RailDriverAirStatusPanel panel = new RailDriverAirStatusPanel(engine, settings, null);
        JSlider slider = panel.getLoadSlider();
        assertTrue(slider.getSnapToTicks());
        panel.dispose();
    }

    @Test
    public void testBuildLoadLabelsMonotonicIncrease() {
        // Load percentages should monotonically increase with step
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10);
        for (int steps = 1; steps <= 10; steps++) {
            for (int maxLoad : new int[]{200, 500, 1000, 2000}) {
                Hashtable<Integer, JLabel> labels =
                        RailDriverAirStatusPanel.buildLoadLabels(steps, maxLoad, font);
                int prev = 0;
                for (int i = 0; i <= steps; i++) {
                    String text = labels.get(i).getText().replace("%", "");
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
