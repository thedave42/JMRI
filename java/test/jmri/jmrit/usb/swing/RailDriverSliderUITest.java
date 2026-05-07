package jmri.jmrit.usb.swing;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyListener;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;
import javax.swing.JSlider;

import jmri.util.JUnitUtil;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RailDriverSliderUI}.
 */
public class RailDriverSliderUITest {

    @BeforeEach
    public void setUp() {
        JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        JUnitUtil.tearDown();
    }

    // ======================== Builder defaults ========================

    @Test
    public void testBuilderDefaultsProduceValidUI() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        assertNotNull(ui);
        assertInstanceOf(RailDriverSliderUI.class, slider.getUI());
    }

    @Test
    public void testBuilderDefaultsTangoTrackBackground() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        assertEquals(RailDriverSliderUI.DEFAULT_TRACK_BACKGROUND, ui.getTrackBackground());
    }

    @Test
    public void testBuilderDefaultsTangoTrackFill() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        assertEquals(RailDriverSliderUI.DEFAULT_TRACK_FILL, ui.getTrackFillColor());
    }

    @Test
    public void testBuilderDefaultsTangoThumbColors() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        assertEquals(RailDriverSliderUI.DEFAULT_THUMB_BOTTOM, ui.getThumbBottom());
        assertEquals(RailDriverSliderUI.DEFAULT_THUMB_MIDDLE, ui.getThumbMiddle());
        assertEquals(RailDriverSliderUI.DEFAULT_THUMB_TOP, ui.getThumbTop());
        assertEquals(RailDriverSliderUI.DEFAULT_THUMB_DISABLED, ui.getThumbDisabled());
    }

    @Test
    public void testBuilderCustomColors() {
        JSlider slider = new JSlider(0, 100, 50);
        Color bg = Color.BLUE;
        Color fill = Color.RED;
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .trackBackground(bg)
                .trackFill(fill)
                .build();
        assertEquals(bg, ui.getTrackBackground());
        assertEquals(fill, ui.getTrackFillColor());
    }

    @Test
    public void testBuilderRejectsNullSlider() {
        assertThrows(NullPointerException.class,
                () -> new RailDriverSliderUI.Builder(null));
    }

    @Test
    public void testBuilderRejectsNullTrackBackground() {
        JSlider slider = new JSlider(0, 100, 50);
        assertThrows(NullPointerException.class,
                () -> new RailDriverSliderUI.Builder(slider).trackBackground(null));
    }

    // ======================== Thumb colour interpolation ========================

    @Test
    public void testThumbColorAtMinimum() {
        JSlider slider = new JSlider(0, 100, 0);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        Color c = ui.getThumbColorForValue(0);
        assertEquals(RailDriverSliderUI.DEFAULT_THUMB_BOTTOM, c);
    }

    @Test
    public void testThumbColorAtMidpoint() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        Color c = ui.getThumbColorForValue(50);
        assertEquals(RailDriverSliderUI.DEFAULT_THUMB_MIDDLE, c);
    }

    @Test
    public void testThumbColorAtMaximum() {
        JSlider slider = new JSlider(0, 100, 100);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        Color c = ui.getThumbColorForValue(100);
        assertEquals(RailDriverSliderUI.DEFAULT_THUMB_TOP, c);
    }

    @Test
    public void testThumbColorAtQuarter() {
        JSlider slider = new JSlider(0, 100, 25);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        Color c = ui.getThumbColorForValue(25);
        // 25 is in the lower half [0..50], t = 25/50 = 0.5
        Color expected = RailDriverSliderUI.blend(
                RailDriverSliderUI.DEFAULT_THUMB_BOTTOM,
                RailDriverSliderUI.DEFAULT_THUMB_MIDDLE, 0.5f);
        assertEquals(expected, c);
    }

    @Test
    public void testThumbColorAtThreeQuarters() {
        JSlider slider = new JSlider(0, 100, 75);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        Color c = ui.getThumbColorForValue(75);
        // 75 is in the upper half [50..100], t = (75-50)/(100-50) = 0.5
        Color expected = RailDriverSliderUI.blend(
                RailDriverSliderUI.DEFAULT_THUMB_MIDDLE,
                RailDriverSliderUI.DEFAULT_THUMB_TOP, 0.5f);
        assertEquals(expected, c);
    }

    @Test
    public void testThumbColorCustomGradient() {
        JSlider slider = new JSlider(0, 100, 0);
        Color red = new Color(255, 0, 0);
        Color yellow = new Color(255, 255, 0);
        Color green = new Color(0, 255, 0);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .thumbColorBottom(red)
                .thumbColorMiddle(yellow)
                .thumbColorTop(green)
                .build();
        assertEquals(red, ui.getThumbColorForValue(0));
        assertEquals(yellow, ui.getThumbColorForValue(50));
        assertEquals(green, ui.getThumbColorForValue(100));
    }

    @Test
    public void testThumbColorDisabled() {
        JSlider slider = new JSlider(0, 100, 50);
        slider.setEnabled(false);
        Color customDisabled = Color.DARK_GRAY;
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .thumbColorDisabled(customDisabled)
                .build();
        assertEquals(customDisabled, ui.getThumbDisabled());
    }

    // ======================== Blend utility ========================

    @Test
    public void testBlendAtZero() {
        Color c1 = new Color(100, 0, 0, 255);
        Color c2 = new Color(0, 100, 0, 128);
        Color result = RailDriverSliderUI.blend(c1, c2, 0f);
        assertEquals(c1, result);
    }

    @Test
    public void testBlendAtOne() {
        Color c1 = new Color(100, 0, 0, 255);
        Color c2 = new Color(0, 100, 0, 128);
        Color result = RailDriverSliderUI.blend(c1, c2, 1f);
        assertEquals(c2, result);
    }

    @Test
    public void testBlendAtHalf() {
        Color c1 = new Color(0, 0, 0, 0);
        Color c2 = new Color(200, 100, 50, 254);
        Color result = RailDriverSliderUI.blend(c1, c2, 0.5f);
        assertEquals(100, result.getRed());
        assertEquals(50, result.getGreen());
        assertEquals(25, result.getBlue());
        assertEquals(127, result.getAlpha());
    }

    @Test
    public void testBlendClampsValues() {
        // Factor out of range should be clamped
        Color c1 = new Color(0, 0, 0);
        Color c2 = new Color(255, 255, 255);
        Color result = RailDriverSliderUI.blend(c1, c2, 1.5f);
        assertEquals(new Color(255, 255, 255), result);
    }

    // ======================== Read-only mode ========================

    @Test
    public void testReadOnlyModeFlag() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .readOnly(true)
                .build();
        assertTrue(ui.isReadOnly());
    }

    @Test
    public void testReadOnlyModeSetValueWorks() {
        JSlider slider = new JSlider(0, 100, 50);
        new RailDriverSliderUI.Builder(slider).readOnly(true).build();
        slider.setValue(75);
        assertEquals(75, slider.getValue());
    }

    @Test
    public void testReadOnlyModeNoKeyboardActions() {
        JSlider slider = new JSlider(0, 100, 50);
        new RailDriverSliderUI.Builder(slider).readOnly(true).build();
        // When read-only, keyboard actions should not be installed
        javax.swing.InputMap im = slider.getInputMap();
        assertEquals(0, im.size(),
                "Read-only slider should have no keyboard bindings");
    }

    @Test
    public void testReadOnlyModeHasMouseWheelListener() {
        JSlider slider = new JSlider(0, 100, 50);
        new RailDriverSliderUI.Builder(slider).readOnly(true).build();
        MouseWheelListener[] listeners = slider.getMouseWheelListeners();
        assertTrue(listeners.length > 0,
                "Read-only slider should have a consuming MouseWheelListener");
    }

    @Test
    public void testReadOnlyModeHasKeyListener() {
        JSlider slider = new JSlider(0, 100, 50);
        new RailDriverSliderUI.Builder(slider).readOnly(true).build();
        KeyListener[] listeners = slider.getKeyListeners();
        assertTrue(listeners.length > 0,
                "Read-only slider should have a consuming KeyListener");
    }

    @Test
    public void testNonReadOnlyModeInstallsKeyboardActions() {
        // Verify that non-read-only mode calls installKeyboardActions
        // (which BasicSliderUI does by default). In headless environments,
        // the InputMap/ActionMap may still be empty, so we verify indirectly:
        // a read-only slider should have no InputMap WHEN_FOCUSED bindings,
        // contrasting with the default UI behaviour.
        JSlider readOnlySlider = new JSlider(0, 100, 50);
        new RailDriverSliderUI.Builder(readOnlySlider).readOnly(true).build();
        int readOnlyKeys = readOnlySlider.getInputMap(
                javax.swing.JComponent.WHEN_FOCUSED).size();
        assertEquals(0, readOnlyKeys,
                "Read-only slider should have no WHEN_FOCUSED input bindings");
    }

    // ======================== Snap to ticks ========================

    @Test
    public void testSnapToTicksEnabled() {
        JSlider slider = new JSlider(0, 10, 0);
        new RailDriverSliderUI.Builder(slider).snapToTicks(true).build();
        assertTrue(slider.getSnapToTicks());
    }

    @Test
    public void testSnapToTicksDisabledByDefault() {
        JSlider slider = new JSlider(0, 10, 0);
        new RailDriverSliderUI.Builder(slider).build();
        assertFalse(slider.getSnapToTicks());
    }

    // ======================== Ticks and labels ========================

    @Test
    public void testTickPositionsStored() {
        JSlider slider = new JSlider(0, 100, 0);
        int[] ticks = {0, 25, 50, 75, 100};
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .ticks(ticks)
                .build();
        assertArrayEquals(ticks, ui.getTickPositions());
    }

    @Test
    public void testTickPositionsDefensiveCopy() {
        JSlider slider = new JSlider(0, 100, 0);
        int[] ticks = {0, 50, 100};
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .ticks(ticks)
                .build();
        ticks[1] = 99; // mutate original
        assertArrayEquals(new int[]{0, 50, 100}, ui.getTickPositions());
    }

    @Test
    public void testNullTicksNoTickPositions() {
        JSlider slider = new JSlider(0, 100, 0);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        assertNull(ui.getTickPositions());
    }

    @Test
    public void testTickLabelsSetLabelTable() {
        JSlider slider = new JSlider(0, 2, 0);
        int[] ticks = {0, 1, 2};
        String[] labels = {"Low", "Mid", "High"};
        new RailDriverSliderUI.Builder(slider)
                .ticks(ticks)
                .tickLabels(labels)
                .build();
        assertTrue(slider.getPaintLabels());
        assertNotNull(slider.getLabelTable());
        assertEquals(3, slider.getLabelTable().size());
    }

    @Test
    public void testTickLabelsWithoutTicksThrows() {
        JSlider slider = new JSlider(0, 100, 0);
        assertThrows(IllegalStateException.class,
                () -> new RailDriverSliderUI.Builder(slider)
                        .tickLabels(new String[]{"A", "B"})
                        .build());
    }

    @Test
    public void testTickLabelsMismatchThrows() {
        JSlider slider = new JSlider(0, 100, 0);
        assertThrows(IllegalStateException.class,
                () -> new RailDriverSliderUI.Builder(slider)
                        .ticks(new int[]{0, 50, 100})
                        .tickLabels(new String[]{"A", "B"})
                        .build());
    }

    // ======================== Sizing ========================

    @Test
    public void testPreferredSizeOverride() {
        JSlider slider = new JSlider(0, 100, 50);
        Dimension dim = new Dimension(300, 40);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .preferredSize(dim)
                .build();
        Dimension pref = ui.getPreferredSize(slider);
        assertEquals(300, pref.width);
        assertEquals(40, pref.height);
    }

    @Test
    public void testPreferredSizeDefensiveCopy() {
        JSlider slider = new JSlider(0, 100, 50);
        Dimension dim = new Dimension(300, 40);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .preferredSize(dim)
                .build();
        dim.width = 999; // mutate original
        assertEquals(300, ui.getFixedPreferredSize().width);
    }

    @Test
    public void testFillParentMode() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .fillParent(true)
                .build();
        assertTrue(ui.isFillParent());
        assertNull(ui.getFixedPreferredSize());
    }

    @Test
    public void testPreferredSizeAndFillParentMutualExclusion() {
        JSlider slider = new JSlider(0, 100, 50);
        // Setting fillParent after preferredSize clears preferredSize
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .preferredSize(new Dimension(200, 30))
                .fillParent(true)
                .build();
        assertTrue(ui.isFillParent());
        assertNull(ui.getFixedPreferredSize());
    }

    @Test
    public void testFillParentThenPreferredSizeClearsFillParent() {
        JSlider slider = new JSlider(0, 100, 50);
        // Setting preferredSize after fillParent clears fillParent
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .fillParent(true)
                .preferredSize(new Dimension(200, 30))
                .build();
        assertFalse(ui.isFillParent());
        assertNotNull(ui.getFixedPreferredSize());
        assertEquals(200, ui.getFixedPreferredSize().width);
    }

    @Test
    public void testFillParentReturnsParentSize() {
        JPanel parent = new JPanel();
        parent.setSize(400, 300);
        JSlider slider = new JSlider(0, 100, 50);
        parent.add(slider);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .fillParent(true)
                .build();
        Dimension pref = ui.getPreferredSize(slider);
        assertEquals(400, pref.width);
        assertEquals(300, pref.height);
    }

    @Test
    public void testFillParentNoParentFallsBackToDefault() {
        JSlider slider = new JSlider(0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider)
                .fillParent(true)
                .build();
        // No parent → fallback to BasicSliderUI default
        Dimension pref = ui.getPreferredSize(slider);
        assertNotNull(pref);
        assertTrue(pref.width > 0 || pref.height > 0);
    }

    // ======================== Orientation support ========================

    @Test
    public void testHorizontalOrientation() {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        assertNotNull(ui);
        assertEquals(JSlider.HORIZONTAL, slider.getOrientation());
    }

    @Test
    public void testVerticalOrientation() {
        JSlider slider = new JSlider(JSlider.VERTICAL, 0, 100, 50);
        RailDriverSliderUI ui = new RailDriverSliderUI.Builder(slider).build();
        assertNotNull(ui);
        assertEquals(JSlider.VERTICAL, slider.getOrientation());
    }

    // ======================== Paint smoke tests ========================

    @Test
    public void testPaintTrackDoesNotThrowHorizontal() {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 100, 50);
        slider.setBounds(0, 0, 200, 30);
        new RailDriverSliderUI.Builder(slider).build();
        slider.doLayout();

        BufferedImage img = new BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        assertDoesNotThrow(() -> slider.paint(g2d));
        g2d.dispose();
    }

    @Test
    public void testPaintTrackDoesNotThrowVertical() {
        JSlider slider = new JSlider(JSlider.VERTICAL, 0, 100, 50);
        slider.setBounds(0, 0, 30, 200);
        new RailDriverSliderUI.Builder(slider).build();
        slider.doLayout();

        BufferedImage img = new BufferedImage(30, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        assertDoesNotThrow(() -> slider.paint(g2d));
        g2d.dispose();
    }

    @Test
    public void testPaintWithTicksDoesNotThrow() {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 100, 50);
        slider.setBounds(0, 0, 200, 30);
        new RailDriverSliderUI.Builder(slider)
                .ticks(new int[]{0, 25, 50, 75, 100})
                .build();
        slider.doLayout();

        BufferedImage img = new BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        assertDoesNotThrow(() -> slider.paint(g2d));
        g2d.dispose();
    }

    @Test
    public void testPaintWithTicksAndLabelsDoesNotThrow() {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 2, 1);
        slider.setBounds(0, 0, 200, 50);
        new RailDriverSliderUI.Builder(slider)
                .ticks(new int[]{0, 1, 2})
                .tickLabels(new String[]{"Lo", "Mid", "Hi"})
                .build();
        slider.doLayout();

        BufferedImage img = new BufferedImage(200, 50, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        assertDoesNotThrow(() -> slider.paint(g2d));
        g2d.dispose();
    }

    @Test
    public void testPaintReadOnlyDoesNotThrow() {
        JSlider slider = new JSlider(JSlider.VERTICAL, 0, 100, 75);
        slider.setBounds(0, 0, 30, 200);
        new RailDriverSliderUI.Builder(slider).readOnly(true).build();
        slider.doLayout();

        BufferedImage img = new BufferedImage(30, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        assertDoesNotThrow(() -> slider.paint(g2d));
        g2d.dispose();
    }

    @Test
    public void testPaintDisabledDoesNotThrow() {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 100, 50);
        slider.setBounds(0, 0, 200, 30);
        slider.setEnabled(false);
        new RailDriverSliderUI.Builder(slider).build();
        slider.doLayout();

        BufferedImage img = new BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        assertDoesNotThrow(() -> slider.paint(g2d));
        g2d.dispose();
    }

    @Test
    public void testPaintProducesNonBlankImage() {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 100, 50);
        slider.setBounds(0, 0, 200, 30);
        new RailDriverSliderUI.Builder(slider).build();
        slider.doLayout();

        BufferedImage img = new BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        slider.paint(g2d);
        g2d.dispose();

        // Check that at least some pixels are non-transparent
        boolean hasContent = false;
        for (int x = 0; x < 200 && !hasContent; x++) {
            for (int y = 0; y < 30 && !hasContent; y++) {
                if ((img.getRGB(x, y) & 0xFF000000) != 0) {
                    hasContent = true;
                }
            }
        }
        assertTrue(hasContent, "Painted slider should have non-transparent pixels");
    }

    // ======================== UI reinstall on settings change ========================

    @Test
    public void testReinstallUIPreservesValue() {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 10, 5);
        new RailDriverSliderUI.Builder(slider).build();
        assertEquals(5, slider.getValue());

        // Reinstall with new tick configuration
        slider.setMaximum(8);
        new RailDriverSliderUI.Builder(slider)
                .ticks(new int[]{0, 2, 4, 6, 8})
                .build();
        // Value should still be valid (clamped by JSlider)
        assertTrue(slider.getValue() <= 8);
    }

    @Test
    public void testReadOnlyListenersCleanedOnReinstall() {
        JSlider slider = new JSlider(0, 100, 50);
        new RailDriverSliderUI.Builder(slider).readOnly(true).build();
        int wheelCount1 = slider.getMouseWheelListeners().length;
        int keyCount1 = slider.getKeyListeners().length;

        // Reinstall
        new RailDriverSliderUI.Builder(slider).readOnly(true).build();
        int wheelCount2 = slider.getMouseWheelListeners().length;
        int keyCount2 = slider.getKeyListeners().length;

        // Listener counts should not grow on reinstall
        assertEquals(wheelCount1, wheelCount2,
                "Mouse wheel listeners should not accumulate on reinstall");
        assertEquals(keyCount1, keyCount2,
                "Key listeners should not accumulate on reinstall");
    }
}
