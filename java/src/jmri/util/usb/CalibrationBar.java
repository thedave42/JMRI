package jmri.util.usb;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import javax.swing.JComponent;

/**
 * Visual calibration bar for a single analog axis byte.
 * <p>
 * The bar represents the byte range relevant to a control. Each named
 * <em>detent</em> (Forward / Neutral / Reverse, Off / Slow / Full, etc.)
 * is drawn as a downward-pointing triangle above the track at its
 * calibrated byte position, labelled, and coloured to indicate whether
 * the user has explicitly captured that detent (blue, filled) or is
 * still using the inventory default (light grey).
 * <p>
 * The live byte reported by the polling thread is drawn as a red
 * upward-pointing triangle below the track, with the current byte
 * shown alongside it. As the user moves the physical lever, the live
 * cursor slides along the track, and the user clicks the corresponding
 * Capture button (in the parent panel) when the lever sits at the
 * desired detent position.
 *
 * @author the Dave (phase 3)
 */
public final class CalibrationBar extends JComponent {

    /** A named position on the bar. */
    private static final class Detent {
        final String label;
        final IntSupplier byteValue;     // current calibrated or default byte
        final BooleanSupplier captured;  // true if explicitly calibrated by the user
        Detent(String label, IntSupplier byteValue, BooleanSupplier captured) {
            this.label = label;
            this.byteValue = byteValue;
            this.captured = captured;
        }
    }

    private static final Color TRACK_FILL    = new Color(225, 225, 225);
    private static final Color TRACK_BORDER  = new Color(120, 120, 120);
    private static final Color CAPTURED      = new Color(20, 100, 200);
    private static final Color UNCAPTURED    = new Color(160, 160, 160);
    private static final Color LIVE_CURSOR   = new Color(220, 30, 30);
    private static final Color TEXT_COLOR    = Color.BLACK;
    private static final Color OUT_OF_RANGE  = new Color(255, 130, 0);  // amber

    private static final int   PAD_LEFT      = 12;
    private static final int   PAD_RIGHT     = 12;
    private static final int   TRACK_HEIGHT  = 10;
    private static final int   MARKER_TIP    = 8;   // triangle height
    private static final int   LABEL_GAP     = 2;
    private static final int   LIVE_LABEL_GAP= 4;

    private final List<Detent> detents = new ArrayList<>();
    private int liveByte = -1;  // -1 = unknown; 0..255 otherwise

    public CalibrationBar() {
        setOpaque(false);
    }

    /**
     * Adds a named detent. {@code byteValue} should return the current
     * calibrated byte for this detent (or its default if uncaptured).
     * {@code captured} should return true iff the user has explicitly
     * captured this detent.
     */
    public void addDetent(String label, IntSupplier byteValue, BooleanSupplier captured) {
        detents.add(new Detent(label, byteValue, captured));
        repaint();
    }

    /** Updates the live cursor position. Pass -1 to hide it. */
    public void setLiveByte(int b) {
        this.liveByte = b;
        repaint();
    }

    /** Repaint after underlying calibration has changed (e.g. after Capture). */
    public void refresh() {
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(420, 78);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(260, 78);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // Determine the byte range covered by the bar. Use the min/max
            // detent byte values, with a small padding on each side so
            // markers at the extremes don't clip and the live cursor has
            // a bit of room when the lever is just past a captured extreme.
            int minByte = 0, maxByte = 255;
            if (!detents.isEmpty()) {
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (Detent d : detents) {
                    int v = d.byteValue.getAsInt();
                    if (v < lo) lo = v;
                    if (v > hi) hi = v;
                }
                int pad = Math.max(4, (hi - lo) / 12);
                minByte = Math.max(0, lo - pad);
                maxByte = Math.min(255, hi + pad);
            }
            if (maxByte <= minByte) {
                maxByte = minByte + 1;  // avoid divide-by-zero for degenerate cases
            }

            int barLeft  = PAD_LEFT;
            int barRight = w - PAD_RIGHT;
            int barWidth = barRight - barLeft;
            int barCentreY = h / 2;
            int trackTop  = barCentreY - TRACK_HEIGHT / 2;
            int trackBot  = barCentreY + TRACK_HEIGHT / 2;

            // Track
            g2.setColor(TRACK_FILL);
            g2.fillRoundRect(barLeft, trackTop, barWidth, TRACK_HEIGHT, 4, 4);
            g2.setColor(TRACK_BORDER);
            g2.drawRoundRect(barLeft, trackTop, barWidth, TRACK_HEIGHT, 4, 4);

            // Detent markers (above the track)
            Font detentFont = g2.getFont().deriveFont(Font.PLAIN, 11f);
            g2.setFont(detentFont);
            FontMetrics fm = g2.getFontMetrics();

            for (Detent d : detents) {
                int b = d.byteValue.getAsInt();
                int x = byteToX(b, minByte, maxByte, barLeft, barRight);

                // Triangle pointing down at the track
                int[] xs = { x - 5, x + 5, x };
                int[] ys = { trackTop - MARKER_TIP, trackTop - MARKER_TIP, trackTop };
                g2.setColor(d.captured.getAsBoolean() ? CAPTURED : UNCAPTURED);
                g2.fillPolygon(xs, ys, 3);
                g2.setColor(TRACK_BORDER);
                g2.drawPolygon(xs, ys, 3);

                // Label above the triangle
                String label = d.label;
                int textW = fm.stringWidth(label);
                int textX = x - textW / 2;
                if (textX < barLeft) textX = barLeft;
                if (textX + textW > barRight) textX = barRight - textW;
                g2.setColor(TEXT_COLOR);
                g2.drawString(label, textX, trackTop - MARKER_TIP - LABEL_GAP);
            }

            // Live cursor (below the track)
            if (liveByte >= 0) {
                boolean inRange = liveByte >= minByte && liveByte <= maxByte;
                int rawX = byteToX(liveByte, minByte, maxByte, barLeft, barRight);
                int x = Math.max(barLeft, Math.min(barRight, rawX));

                // Triangle pointing up at the track from below
                int[] xs = { x - 5, x + 5, x };
                int[] ys = { trackBot + MARKER_TIP, trackBot + MARKER_TIP, trackBot };
                g2.setColor(inRange ? LIVE_CURSOR : OUT_OF_RANGE);
                g2.fillPolygon(xs, ys, 3);
                g2.setColor(TRACK_BORDER);
                g2.drawPolygon(xs, ys, 3);

                // Vertical guide line through the track
                g2.setColor(inRange ? LIVE_CURSOR : OUT_OF_RANGE);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(x, trackTop, x, trackBot);

                // Label below
                Font liveFont = g2.getFont().deriveFont(Font.BOLD, 11f);
                g2.setFont(liveFont);
                FontMetrics lfm = g2.getFontMetrics();
                String label = String.format("0x%02x  (%d)%s",
                        liveByte, liveByte, inRange ? "" : "  out of range");
                int textW = lfm.stringWidth(label);
                int textX = x - textW / 2;
                if (textX < barLeft) textX = barLeft;
                if (textX + textW > barRight) textX = barRight - textW;
                g2.setColor(TEXT_COLOR);
                g2.drawString(label, textX, trackBot + MARKER_TIP + lfm.getAscent() + LIVE_LABEL_GAP);
            }
        } finally {
            g2.dispose();
        }
    }

    private static int byteToX(int byteValue, int minByte, int maxByte,
                               int barLeft, int barRight) {
        double frac = (byteValue - minByte) / (double) (maxByte - minByte);
        return barLeft + (int) Math.round(frac * (barRight - barLeft));
    }
}
