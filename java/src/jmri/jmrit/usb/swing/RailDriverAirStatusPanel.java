package jmri.jmrit.usb.swing;

import java.awt.Dimension;
import java.awt.Font;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Hashtable;
import java.util.function.IntConsumer;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;

import jmri.jmrit.usb.SemiRealisticSettings;
import jmri.jmrit.usb.SemiRealisticThrottleEngine;
import jmri.util.ThreadingUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-time air status and load display panel for the RailDriver
 * semi-realistic throttle mode.
 * <p>
 * Displays brake pipe (air line) pressure and main reservoir level as
 * labeled {@link JProgressBar} gauges with numeric readouts, and a
 * user-adjustable load {@link JSlider} whose tick labels show the
 * quadratic load percentages from
 * {@link SemiRealisticThrottleEngine#getLoadPcnt}.
 * <p>
 * The panel subscribes to the engine's {@code "airLineValue"} and
 * {@code "airReservoirPct"} PropertyChange events. Events fire on the
 * JMRI layout thread; all UI updates are marshalled to the GUI thread
 * via {@link ThreadingUtil#runOnGUIEventually}.
 * <p>
 * Call {@link #dispose()} when removing the panel to deregister all
 * PropertyChange listeners and prevent listener leaks.
 *
 * @since 5.13.1
 */
public class RailDriverAirStatusPanel extends JPanel {

    private final JProgressBar airLineBar;
    private final JProgressBar airReservoirBar;
    private final JLabel airLineReadout;
    private final JLabel airReservoirReadout;
    private final JSlider loadSlider;

    @CheckForNull
    private SemiRealisticThrottleEngine engine;

    @CheckForNull
    private IntConsumer loadChangeCallback;

    private final PropertyChangeListener engineListener = this::onEnginePropertyChange;

    /**
     * Creates the air status panel and subscribes to the engine's
     * PropertyChange events.
     *
     * @param engine   the semi-realistic throttle engine to monitor
     * @param settings snapshot of current settings (for slider config)
     * @param loadChangeCallback called on the EDT when the user releases
     *        the load slider; receives the new slider position (0-based)
     */
    public RailDriverAirStatusPanel(@Nonnull SemiRealisticThrottleEngine engine,
                                    @Nonnull SemiRealisticSettings settings,
                                    @CheckForNull IntConsumer loadChangeCallback) {
        this.engine = engine;
        this.loadChangeCallback = loadChangeCallback;

        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        Font smallFont = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

        // --- Air line gauge ---
        JPanel airLinePanel = new JPanel();
        airLinePanel.setLayout(new BoxLayout(airLinePanel, BoxLayout.Y_AXIS));
        JLabel airLineLabel = new JLabel(Bundle.getMessage("RailDriverAirLine"));
        airLineLabel.setFont(smallFont);
        airLineLabel.setAlignmentX(CENTER_ALIGNMENT);
        airLineBar = new JProgressBar(0, 100);
        airLineBar.setStringPainted(false);
        airLineBar.setPreferredSize(new Dimension(100, 14));
        airLineBar.setMaximumSize(new Dimension(120, 14));
        airLineReadout = new JLabel("100%");
        airLineReadout.setFont(smallFont);
        airLineReadout.setAlignmentX(CENTER_ALIGNMENT);
        airLinePanel.add(airLineLabel);
        airLinePanel.add(airLineBar);
        airLinePanel.add(airLineReadout);

        // --- Air reservoir gauge ---
        JPanel airReservoirPanel = new JPanel();
        airReservoirPanel.setLayout(new BoxLayout(airReservoirPanel, BoxLayout.Y_AXIS));
        JLabel airReservoirLabel = new JLabel(Bundle.getMessage("RailDriverAirReservoir"));
        airReservoirLabel.setFont(smallFont);
        airReservoirLabel.setAlignmentX(CENTER_ALIGNMENT);
        airReservoirBar = new JProgressBar(0, 100);
        airReservoirBar.setStringPainted(false);
        airReservoirBar.setPreferredSize(new Dimension(100, 14));
        airReservoirBar.setMaximumSize(new Dimension(120, 14));
        airReservoirReadout = new JLabel("100%");
        airReservoirReadout.setFont(smallFont);
        airReservoirReadout.setAlignmentX(CENTER_ALIGNMENT);
        airReservoirPanel.add(airReservoirLabel);
        airReservoirPanel.add(airReservoirBar);
        airReservoirPanel.add(airReservoirReadout);

        // --- Load slider ---
        JPanel loadPanel = new JPanel();
        loadPanel.setLayout(new BoxLayout(loadPanel, BoxLayout.Y_AXIS));
        JLabel loadLabel = new JLabel(Bundle.getMessage("RailDriverLoad"));
        loadLabel.setFont(smallFont);
        loadLabel.setAlignmentX(CENTER_ALIGNMENT);
        loadSlider = buildLoadSlider(settings, smallFont);
        loadPanel.add(loadLabel);
        loadPanel.add(loadSlider);

        add(airLinePanel);
        add(Box.createHorizontalStrut(8));
        add(airReservoirPanel);
        add(Box.createHorizontalStrut(8));
        add(loadPanel);

        // Subscribe to engine PCS events.
        engine.addPropertyChangeListener(engineListener);

        // Initialize from current engine state.
        setAirLineValue(engine.getAirLineValue());
        setAirReservoirPct(engine.getAirReservoirPct());
    }

    /**
     * Updates the load slider's range and tick labels from new settings.
     * Called when settings are reloaded.
     *
     * @param settings new settings snapshot
     */
    public void updateSettings(@Nonnull SemiRealisticSettings settings) {
        Font smallFont = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        int steps = Math.max(1, settings.numberOfLoadSteps);
        loadSlider.setMaximum(steps);
        loadSlider.setMajorTickSpacing(1);
        loadSlider.setLabelTable(buildLoadLabels(steps, settings.maxLoadPcnt, smallFont));
        loadSlider.setValue(Math.min(settings.loadSliderPosition, steps));
        loadSlider.revalidate();
        loadSlider.repaint();
    }

    /**
     * Deregisters all PropertyChange listeners from the engine.
     * Safe to call multiple times.
     */
    public void dispose() {
        if (engine != null) {
            engine.removePropertyChangeListener(engineListener);
            engine = null;
        }
        loadChangeCallback = null;
    }

    // ======================== Internal ========================

    private void onEnginePropertyChange(PropertyChangeEvent evt) {
        String prop = evt.getPropertyName();
        if (SemiRealisticThrottleEngine.AIR_LINE_VALUE.equals(prop)) {
            int val = (int) evt.getNewValue();
            ThreadingUtil.runOnGUIEventually(() -> setAirLineValue(val));
        } else if (SemiRealisticThrottleEngine.AIR_RESERVOIR_PCT.equals(prop)) {
            int val = (int) evt.getNewValue();
            ThreadingUtil.runOnGUIEventually(() -> setAirReservoirPct(val));
        }
    }

    private void setAirLineValue(int value) {
        airLineBar.setValue(value);
        airLineReadout.setText(value + "%");
    }

    private void setAirReservoirPct(int value) {
        airReservoirBar.setValue(value);
        airReservoirReadout.setText(value + "%");
    }

    private JSlider buildLoadSlider(@Nonnull SemiRealisticSettings settings,
                                    @Nonnull Font labelFont) {
        int steps = Math.max(1, settings.numberOfLoadSteps);
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, steps,
                Math.min(settings.loadSliderPosition, steps));
        slider.setMajorTickSpacing(1);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setSnapToTicks(true);
        slider.setLabelTable(buildLoadLabels(steps, settings.maxLoadPcnt, labelFont));

        slider.addChangeListener(e -> {
            if (!slider.getValueIsAdjusting()) {
                IntConsumer cb = loadChangeCallback;
                if (cb != null) {
                    cb.accept(slider.getValue());
                }
            }
        });

        return slider;
    }

    /**
     * Builds the tick label table for the load slider.
     *
     * @param steps total load slider positions (numberOfLoadSteps)
     * @param maxLoadPcnt maximum load percentage
     * @param font font for labels
     * @return label table for JSlider
     */
    static Hashtable<Integer, JLabel> buildLoadLabels(int steps, int maxLoadPcnt,
                                                       @Nonnull Font font) {
        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        for (int i = 0; i <= steps; i++) {
            double pct = SemiRealisticThrottleEngine.getLoadPcnt(i, steps, maxLoadPcnt);
            String text = Math.round(pct * 100) + "%";
            JLabel lbl = new JLabel(text);
            lbl.setFont(font);
            labels.put(i, lbl);
        }
        return labels;
    }

    // ======================== Package-visible for testing ========================

    /** @return the air line progress bar (for testing). */
    JProgressBar getAirLineBar() { return airLineBar; }

    /** @return the air reservoir progress bar (for testing). */
    JProgressBar getAirReservoirBar() { return airReservoirBar; }

    /** @return the air line numeric readout label (for testing). */
    JLabel getAirLineReadout() { return airLineReadout; }

    /** @return the air reservoir numeric readout label (for testing). */
    JLabel getAirReservoirReadout() { return airReservoirReadout; }

    /** @return the load slider (for testing). */
    JSlider getLoadSlider() { return loadSlider; }

    private static final Logger log = LoggerFactory.getLogger(RailDriverAirStatusPanel.class);
}
