package jmri.jmrit.usb.swing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelListener;
import java.util.Objects;

import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicSliderUI;

/**
 * Reusable custom {@link BasicSliderUI} for RailDriver status displays.
 * <p>
 * Provides configurable track colours, a value-interpolated gradient thumb,
 * optional tick marks with labels, snap-to-ticks, read-only (indicator) mode,
 * and sizing options. Create instances via the {@link Builder} inner class:
 * <pre>
 * JSlider slider = new JSlider(JSlider.VERTICAL, 0, 100, 50);
 * new RailDriverSliderUI.Builder(slider)
 *     .trackFill(Color.GREEN)
 *     .readOnly(true)
 *     .build();
 * </pre>
 * <p>
 * Default colours use the Tango palette to be visually consistent with
 * {@link jmri.jmrit.throttle.ControlPanelCustomSliderUI}.
 *
 * @since 5.13.1
 */
public class RailDriverSliderUI extends BasicSliderUI {

    // Tango palette defaults (package-visible for testing)
    static final Color DEFAULT_TRACK_BACKGROUND = new Color(0x88, 0x8a, 0x85, 0x88);
    static final Color DEFAULT_TRACK_FILL = new Color(0xf5, 0x79, 0x00, 0xCC);
    static final Color DEFAULT_THUMB_BOTTOM = new Color(0xcc, 0x00, 0x00);
    static final Color DEFAULT_THUMB_MIDDLE = new Color(0xd7, 0xd2, 0x7a);
    static final Color DEFAULT_THUMB_TOP = new Color(0x4e, 0x9a, 0x06);
    static final Color DEFAULT_THUMB_DISABLED = new Color(0x10, 0x10, 0x10);
    static final Color THUMB_CONTOUR = new Color(0x55, 0x57, 0x53);

    // Configuration (set by Builder before installUI)
    private Color trackBackground = DEFAULT_TRACK_BACKGROUND;
    private Color trackFillColor = DEFAULT_TRACK_FILL;
    private Color thumbBottom = DEFAULT_THUMB_BOTTOM;
    private Color thumbMiddle = DEFAULT_THUMB_MIDDLE;
    private Color thumbTop = DEFAULT_THUMB_TOP;
    private Color thumbDisabled = DEFAULT_THUMB_DISABLED;
    private int[] tickPositions;
    private String[] tickLabelTexts;
    private boolean readOnlyMode;
    private boolean fillParentMode;
    private Dimension fixedPreferredSize;

    // Listeners tracked for cleanup
    private HierarchyListener hierarchyListener;
    private ComponentListener parentResizeListener;
    private Container listenedParent;
    private MouseWheelListener readOnlyWheelListener;
    private KeyListener readOnlyKeyListener;

    private RailDriverSliderUI(JSlider slider) {
        super(slider);
    }

    // ======================== Builder ========================

    /**
     * Fluent builder for {@link RailDriverSliderUI}. Only the {@link JSlider}
     * is required; all colour and behaviour settings have sensible Tango
     * palette defaults.
     * <p>
     * Calling {@link #build()} installs the new UI on the slider via
     * {@link JSlider#setUI}.
     */
    public static class Builder {

        private final JSlider slider;
        private Color trackBackground = DEFAULT_TRACK_BACKGROUND;
        private Color trackFill = DEFAULT_TRACK_FILL;
        private Color thumbBottom = DEFAULT_THUMB_BOTTOM;
        private Color thumbMiddle = DEFAULT_THUMB_MIDDLE;
        private Color thumbTop = DEFAULT_THUMB_TOP;
        private Color thumbDisabled = DEFAULT_THUMB_DISABLED;
        private int[] ticks;
        private String[] tickLabels;
        private boolean snapToTicks;
        private boolean readOnly;
        private Dimension preferredSize;
        private boolean fillParent;

        /**
         * Creates a new builder for the given slider.
         *
         * @param slider the JSlider to customise; must not be null
         */
        public Builder(JSlider slider) {
            this.slider = Objects.requireNonNull(slider, "slider must not be null");
        }

        /** Sets the track background colour (full track area). */
        public Builder trackBackground(Color c) {
            this.trackBackground = Objects.requireNonNull(c);
            return this;
        }

        /** Sets the track fill colour (from zero point to thumb centre). */
        public Builder trackFill(Color c) {
            this.trackFill = Objects.requireNonNull(c);
            return this;
        }

        /** Sets the thumb colour at the slider minimum. */
        public Builder thumbColorBottom(Color c) {
            this.thumbBottom = Objects.requireNonNull(c);
            return this;
        }

        /** Sets the thumb colour at the slider midpoint. */
        public Builder thumbColorMiddle(Color c) {
            this.thumbMiddle = Objects.requireNonNull(c);
            return this;
        }

        /** Sets the thumb colour at the slider maximum. */
        public Builder thumbColorTop(Color c) {
            this.thumbTop = Objects.requireNonNull(c);
            return this;
        }

        /** Sets the thumb colour when the slider is disabled. */
        public Builder thumbColorDisabled(Color c) {
            this.thumbDisabled = Objects.requireNonNull(c);
            return this;
        }

        /**
         * Sets the value positions at which tick lines are drawn on the track.
         * Pass {@code null} for no tick marks.
         */
        public Builder ticks(int[] positions) {
            this.ticks = positions != null ? positions.clone() : null;
            return this;
        }

        /**
         * Sets labels to display adjacent to tick marks. Must be the same
         * length as the {@link #ticks(int[])} array. Pass {@code null} for
         * no labels.
         */
        public Builder tickLabels(String[] labels) {
            this.tickLabels = labels != null ? labels.clone() : null;
            return this;
        }

        /** When {@code true}, configures the slider to snap to ticks. */
        public Builder snapToTicks(boolean snap) {
            this.snapToTicks = snap;
            return this;
        }

        /**
         * When {@code true}, installs no-op input listeners so the slider
         * ignores all mouse, keyboard, and wheel input.
         * {@link JSlider#setValue(int)} continues to work programmatically.
         */
        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        /**
         * Sets an explicit preferred size. Mutually exclusive with
         * {@link #fillParent(boolean)}; setting this clears fillParent.
         */
        public Builder preferredSize(Dimension d) {
            this.preferredSize = d != null ? new Dimension(d) : null;
            this.fillParent = false;
            return this;
        }

        /**
         * When {@code true}, registers a listener on the parent container
         * that updates the slider's preferred size to fill the parent's
         * available space on resize. Mutually exclusive with
         * {@link #preferredSize(Dimension)}; setting this clears preferredSize.
         */
        public Builder fillParent(boolean fill) {
            this.fillParent = fill;
            if (fill) {
                this.preferredSize = null;
            }
            return this;
        }

        /**
         * Builds and installs the UI on the slider.
         *
         * @return the installed UI instance
         * @throws IllegalStateException if tickLabels is set without ticks,
         *         or if their lengths differ
         */
        public RailDriverSliderUI build() {
            if (tickLabels != null && ticks == null) {
                throw new IllegalStateException("tickLabels requires ticks");
            }
            if (tickLabels != null && ticks != null
                    && tickLabels.length != ticks.length) {
                throw new IllegalStateException(
                        "ticks and tickLabels must have the same length");
            }

            RailDriverSliderUI ui = new RailDriverSliderUI(slider);
            ui.trackBackground = this.trackBackground;
            ui.trackFillColor = this.trackFill;
            ui.thumbBottom = this.thumbBottom;
            ui.thumbMiddle = this.thumbMiddle;
            ui.thumbTop = this.thumbTop;
            ui.thumbDisabled = this.thumbDisabled;
            ui.tickPositions = this.ticks;
            ui.tickLabelTexts = this.tickLabels != null ? this.tickLabels.clone() : null;
            ui.readOnlyMode = this.readOnly;
            ui.fillParentMode = this.fillParent;
            ui.fixedPreferredSize = this.preferredSize;

            // Configure slider tick/label mechanism
            if (this.snapToTicks) {
                slider.setSnapToTicks(true);
            }

            // We draw custom tick lines on the track; disable standard ticks
            slider.setPaintTicks(false);

            // Labels are painted inside the track by paintTrack();
            // disable standard external label painting.
            slider.setPaintLabels(false);

            // Install — triggers installUI() which uses readOnlyMode etc.
            slider.setUI(ui);
            return ui;
        }
    }

    // ======================== Install / Uninstall ========================

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        if (readOnlyMode) {
            installReadOnlyListeners();
        }
        if (fillParentMode) {
            installFillParentListeners();
        }
    }

    @Override
    public void uninstallUI(JComponent c) {
        removeReadOnlyListeners();
        removeFillParentListeners();
        super.uninstallUI(c);
    }

    // ======================== Paint ========================

    @Override
    public void paint(Graphics g, JComponent c) {
        if (g instanceof Graphics2D) {
            ((Graphics2D) g).setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
        }
        super.paint(g, c);
    }

    @Override
    public void paintTrack(Graphics g) {
        if (!(g instanceof Graphics2D)) {
            return;
        }
        Graphics2D g2d = (Graphics2D) g;
        Paint oldPaint = g2d.getPaint();

        // Background fill (full track area)
        g2d.setPaint(trackBackground);
        g2d.fillRect(trackRect.x, trackRect.y,
                trackRect.width, trackRect.height);

        // Value fill from zero point to thumb centre
        g2d.setPaint(trackFillColor);
        if (slider.getOrientation() == SwingConstants.HORIZONTAL) {
            int fillWidth = thumbRect.x + thumbRect.width / 2 - trackRect.x;
            if (fillWidth > 0) {
                g2d.fillRect(trackRect.x, trackRect.y,
                        fillWidth, trackRect.height);
            }
        } else {
            // Vertical: fill from thumb centre down to the bottom of the track
            int thumbCenter = thumbRect.y + thumbRect.height / 2;
            int fillHeight = trackRect.y + trackRect.height - thumbCenter;
            if (fillHeight > 0) {
                g2d.fillRect(trackRect.x, thumbCenter,
                        trackRect.width, fillHeight);
            }
        }

        // Tick lines at caller-specified positions
        if (tickPositions != null) {
            Color tickColor = new Color(trackBackground.getRed(),
                    trackBackground.getGreen(), trackBackground.getBlue());
            g2d.setPaint(tickColor);
            for (int val : tickPositions) {
                if (slider.getOrientation() == SwingConstants.HORIZONTAL) {
                    int x = xPositionForValue(val);
                    g2d.drawLine(x, trackRect.y,
                            x, trackRect.y + trackRect.height - 1);
                } else {
                    int y = yPositionForValue(val);
                    g2d.drawLine(trackRect.x, y,
                            trackRect.x + trackRect.width - 1, y);
                }
            }
        }

        // Tick labels painted inside the track, overlaying the fill
        if (tickPositions != null && tickLabelTexts != null) {
            Font labelFont = slider.getFont();
            g2d.setFont(labelFont);
            java.awt.FontMetrics fm = g2d.getFontMetrics(labelFont);
            for (int i = 0; i < tickPositions.length && i < tickLabelTexts.length; i++) {
                String text = tickLabelTexts[i];
                if (text == null || text.isEmpty()) {
                    continue;
                }
                int textW = fm.stringWidth(text);
                int textH = fm.getAscent();
                if (slider.getOrientation() == SwingConstants.HORIZONTAL) {
                    int x = xPositionForValue(tickPositions[i]) - textW / 2;
                    int y = trackRect.y + (trackRect.height + textH) / 2 - 1;
                    // Dark shadow for readability over fill
                    g2d.setPaint(THUMB_CONTOUR);
                    g2d.drawString(text, x + 1, y + 1);
                    g2d.setPaint(Color.WHITE);
                    g2d.drawString(text, x, y);
                } else {
                    int x = trackRect.x + (trackRect.width - textW) / 2;
                    int y = yPositionForValue(tickPositions[i]) + textH / 2 - 1;
                    g2d.setPaint(THUMB_CONTOUR);
                    g2d.drawString(text, x + 1, y + 1);
                    g2d.setPaint(Color.WHITE);
                    g2d.drawString(text, x, y);
                }
            }
        }

        g2d.setPaint(oldPaint);
    }

    @Override
    public void paintThumb(Graphics g) {
        if (!(g instanceof Graphics2D)) {
            return;
        }
        Graphics2D g2d = (Graphics2D) g;
        Paint oldPaint = g2d.getPaint();
        Stroke oldStroke = g2d.getStroke();

        // Thumb rectangle with insets for contour stroke
        int x1, y1, x2, y2;
        if (slider.getOrientation() == SwingConstants.HORIZONTAL) {
            x1 = thumbRect.x + 1;
            x2 = thumbRect.x + thumbRect.width - 2;
            y1 = thumbRect.y + 3;
            y2 = thumbRect.y + thumbRect.height - 5;
        } else {
            x1 = thumbRect.x + 3;
            x2 = thumbRect.x + thumbRect.width - 5;
            y1 = thumbRect.y + 1;
            y2 = thumbRect.y + thumbRect.height - 2;
        }

        // Fill with value-interpolated gradient colour
        Color fillColor = slider.isEnabled()
                ? getThumbColorForValue(slider.getValue())
                : thumbDisabled;
        g2d.setPaint(fillColor);
        g2d.fillRect(x1, y1, x2 - x1, y2 - y1);

        // Dark contour stroke
        g2d.setStroke(new BasicStroke(2f));
        g2d.setPaint(THUMB_CONTOUR);
        g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

        g2d.setStroke(oldStroke);
        g2d.setPaint(oldPaint);
    }

    @Override
    protected Dimension getThumbSize() {
        if (slider.getOrientation() == SwingConstants.HORIZONTAL) {
            return new Dimension(16,
                    Math.max(16, contentRect.height));
        } else {
            return new Dimension(
                    Math.max(16, contentRect.width), 16);
        }
    }

    // ======================== Thumb colour interpolation ========================

    /**
     * Computes the thumb fill colour for a given slider value via linear
     * interpolation: bottom→middle in the lower half of the range,
     * middle→top in the upper half, blending all four RGBA channels.
     *
     * @param value the slider value
     * @return the interpolated colour
     */
    Color getThumbColorForValue(int value) {
        int min = slider.getMinimum();
        int max = slider.getMaximum();
        int mid = (min + max) / 2;
        if (value <= mid) {
            float t = (mid == min) ? 0f
                    : (float) (value - min) / (mid - min);
            return blend(thumbBottom, thumbMiddle, t);
        } else {
            float t = (max == mid) ? 1f
                    : (float) (value - mid) / (max - mid);
            return blend(thumbMiddle, thumbTop, t);
        }
    }

    /**
     * Linearly blends two colours by factor {@code t} (0.0 = c1, 1.0 = c2),
     * interpolating all four RGBA channels.
     */
    static Color blend(Color c1, Color c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(c1.getRed() + t * (c2.getRed() - c1.getRed()));
        int g = Math.round(c1.getGreen() + t * (c2.getGreen() - c1.getGreen()));
        int b = Math.round(c1.getBlue() + t * (c2.getBlue() - c1.getBlue()));
        int a = Math.round(c1.getAlpha() + t * (c2.getAlpha() - c1.getAlpha()));
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    // ======================== Read-only mode ========================

    @Override
    protected TrackListener createTrackListener(JSlider s) {
        if (readOnlyMode) {
            return new TrackListener() {
                @Override public void mousePressed(MouseEvent e) { e.consume(); }
                @Override public void mouseReleased(MouseEvent e) { e.consume(); }
                @Override public void mouseClicked(MouseEvent e) { e.consume(); }
                @Override public void mouseDragged(MouseEvent e) { e.consume(); }
                @Override public void mouseMoved(MouseEvent e) { e.consume(); }
                @Override public void mouseEntered(MouseEvent e) { e.consume(); }
                @Override public void mouseExited(MouseEvent e) { e.consume(); }
            };
        }
        return super.createTrackListener(s);
    }

    @Override
    protected void installKeyboardActions(JSlider s) {
        if (!readOnlyMode) {
            super.installKeyboardActions(s);
        }
    }

    @Override
    protected void scrollDueToClickInTrack(int direction) {
        if (!readOnlyMode) {
            super.scrollDueToClickInTrack(direction);
        }
    }

    private void installReadOnlyListeners() {
        readOnlyWheelListener = e -> e.consume();
        slider.addMouseWheelListener(readOnlyWheelListener);

        readOnlyKeyListener = new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { e.consume(); }
            @Override public void keyReleased(KeyEvent e) { e.consume(); }
            @Override public void keyTyped(KeyEvent e) { e.consume(); }
        };
        slider.addKeyListener(readOnlyKeyListener);
    }

    private void removeReadOnlyListeners() {
        if (slider != null && readOnlyWheelListener != null) {
            slider.removeMouseWheelListener(readOnlyWheelListener);
            readOnlyWheelListener = null;
        }
        if (slider != null && readOnlyKeyListener != null) {
            slider.removeKeyListener(readOnlyKeyListener);
            readOnlyKeyListener = null;
        }
    }

    // ======================== Sizing ========================

    @Override
    public Dimension getPreferredSize(JComponent c) {
        if (fixedPreferredSize != null) {
            return new Dimension(fixedPreferredSize);
        }
        if (fillParentMode) {
            Container parent = c.getParent();
            if (parent != null && parent.getWidth() > 0 && parent.getHeight() > 0) {
                Insets insets = parent.getInsets();
                return new Dimension(
                        parent.getWidth() - insets.left - insets.right,
                        parent.getHeight() - insets.top - insets.bottom);
            }
        }
        return super.getPreferredSize(c);
    }

    // ======================== Fill-parent listeners ========================

    private void installFillParentListeners() {
        hierarchyListener = (HierarchyEvent e) -> {
            if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) {
                updateParentListener();
            }
        };
        slider.addHierarchyListener(hierarchyListener);
        updateParentListener();
    }

    private void updateParentListener() {
        if (listenedParent != null && parentResizeListener != null) {
            listenedParent.removeComponentListener(parentResizeListener);
            parentResizeListener = null;
            listenedParent = null;
        }
        Container parent = slider.getParent();
        if (parent != null) {
            parentResizeListener = new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent ce) {
                    slider.revalidate();
                }
            };
            parent.addComponentListener(parentResizeListener);
            listenedParent = parent;
        }
    }

    private void removeFillParentListeners() {
        if (slider != null && hierarchyListener != null) {
            slider.removeHierarchyListener(hierarchyListener);
            hierarchyListener = null;
        }
        if (listenedParent != null && parentResizeListener != null) {
            listenedParent.removeComponentListener(parentResizeListener);
            parentResizeListener = null;
            listenedParent = null;
        }
    }

    // ======================== Accessors (package-visible for testing) ========================

    /** @return true if read-only mode is active. */
    boolean isReadOnly() {
        return readOnlyMode;
    }

    /** @return true if fill-parent mode is active. */
    boolean isFillParent() {
        return fillParentMode;
    }

    /** @return the fixed preferred size, or null if not set. */
    Dimension getFixedPreferredSize() {
        return fixedPreferredSize != null ? new Dimension(fixedPreferredSize) : null;
    }

    /** @return the track background colour. */
    Color getTrackBackground() { return trackBackground; }

    /** @return the track fill colour. */
    Color getTrackFillColor() { return trackFillColor; }

    /** @return the thumb bottom colour. */
    Color getThumbBottom() { return thumbBottom; }

    /** @return the thumb middle colour. */
    Color getThumbMiddle() { return thumbMiddle; }

    /** @return the thumb top colour. */
    Color getThumbTop() { return thumbTop; }

    /** @return the thumb disabled colour. */
    Color getThumbDisabled() { return thumbDisabled; }

    /** @return a copy of the tick positions array, or null. */
    int[] getTickPositions() {
        return tickPositions != null ? tickPositions.clone() : null;
    }

    /** @return a copy of the tick label texts array, or null. */
    String[] getTickLabelTexts() {
        return tickLabelTexts != null ? tickLabelTexts.clone() : null;
    }

    // ======================== Utility ========================

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
