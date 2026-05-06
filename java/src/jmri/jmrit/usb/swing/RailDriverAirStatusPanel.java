package jmri.jmrit.usb.swing;

import java.awt.BorderLayout;
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
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.WindowConstants;

import jmri.DccThrottle;
import jmri.LocoAddress;
import jmri.jmrit.throttle.AddressListener;
import jmri.jmrit.throttle.AddressPanel;
import jmri.jmrit.throttle.WindowPreferences;
import jmri.jmrit.usb.SemiRealisticSettings;
import jmri.jmrit.usb.SemiRealisticThrottleEngine;
import jmri.util.ThreadingUtil;

import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link JInternalFrame} that displays real-time air status and load for the
 * RailDriver semi-realistic throttle mode.
 * <p>
 * Shows brake pipe (air line) pressure and main reservoir level as vertical
 * {@link JProgressBar} gauges with numeric readouts, and a user-adjustable
 * vertical {@link JSlider} for load whose tick labels show the quadratic load
 * percentages from {@link SemiRealisticThrottleEngine#getLoadPcnt}.
 * <p>
 * Follows the standard JMRI throttle panel pattern established by
 * {@code ControlPanel} and {@code SpeedPanel}: extends {@code JInternalFrame},
 * implements {@code PropertyChangeListener} and {@code AddressListener}, and
 * provides {@link #getXml()}/{@link #setXml(Element)} for persistence and
 * {@link #destroy()} for cleanup.
 * <p>
 * The panel subscribes to the engine's {@code "airLineValue"} and
 * {@code "airReservoirPct"} PropertyChange events. Events fire on the
 * JMRI layout thread; all UI updates are marshalled to the GUI thread
 * via {@link ThreadingUtil#runOnGUIEventually}.
 *
 * @since 5.13.1
 */
public class RailDriverAirStatusPanel extends JInternalFrame
        implements PropertyChangeListener, AddressListener {

    private JProgressBar airLineBar;
    private JProgressBar airReservoirBar;
    private JLabel airLineReadout;
    private JLabel airReservoirReadout;
    private JSlider loadSlider;

    @CheckForNull
    private DccThrottle throttle;

    @CheckForNull
    private AddressPanel addressPanel;

    @CheckForNull
    private SemiRealisticThrottleEngine engine;

    @CheckForNull
    private IntConsumer loadChangeCallback;

    private final PropertyChangeListener engineListener = this::onEnginePropertyChange;

    /**
     * Creates the air status panel. Call {@link #setAddressPanel} and
     * {@link #subscribeToEngine} after construction to wire lifecycle events.
     */
    public RailDriverAirStatusPanel() {
        initGUI();
    }

    // ======================== GUI setup ========================

    private void initGUI() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.X_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        this.setContentPane(mainPanel);
        this.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        Font smallFont = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

        // --- Air line gauge (vertical) ---
        JPanel airLinePanel = new JPanel(new BorderLayout());
        JLabel airLineLabel = new JLabel(Bundle.getMessage("RailDriverAirLine"), JLabel.CENTER);
        airLineLabel.setFont(smallFont);
        airLineBar = new JProgressBar(JProgressBar.VERTICAL, 0, 100);
        airLineBar.setStringPainted(false);
        airLineReadout = new JLabel("100%", JLabel.CENTER);
        airLineReadout.setFont(smallFont);
        airLinePanel.add(airLineLabel, BorderLayout.NORTH);
        airLinePanel.add(airLineBar, BorderLayout.CENTER);
        airLinePanel.add(airLineReadout, BorderLayout.SOUTH);

        // --- Air reservoir gauge (vertical) ---
        JPanel airReservoirPanel = new JPanel(new BorderLayout());
        JLabel airReservoirLabel = new JLabel(Bundle.getMessage("RailDriverAirReservoir"), JLabel.CENTER);
        airReservoirLabel.setFont(smallFont);
        airReservoirBar = new JProgressBar(JProgressBar.VERTICAL, 0, 100);
        airReservoirBar.setStringPainted(false);
        airReservoirReadout = new JLabel("100%", JLabel.CENTER);
        airReservoirReadout.setFont(smallFont);
        airReservoirPanel.add(airReservoirLabel, BorderLayout.NORTH);
        airReservoirPanel.add(airReservoirBar, BorderLayout.CENTER);
        airReservoirPanel.add(airReservoirReadout, BorderLayout.SOUTH);

        // --- Load slider (vertical) ---
        JPanel loadPanel = new JPanel(new BorderLayout());
        JLabel loadLabel = new JLabel(Bundle.getMessage("RailDriverLoad"), JLabel.CENTER);
        loadLabel.setFont(smallFont);
        loadSlider = buildLoadSlider(new SemiRealisticSettings(), smallFont);
        loadPanel.add(loadLabel, BorderLayout.NORTH);
        loadPanel.add(loadSlider, BorderLayout.CENTER);

        mainPanel.add(airLinePanel);
        mainPanel.add(Box.createHorizontalStrut(6));
        mainPanel.add(airReservoirPanel);
        mainPanel.add(Box.createHorizontalStrut(6));
        mainPanel.add(loadPanel);
    }

    // ======================== Lifecycle ========================

    /**
     * Sets the AddressPanel this panel listens to for throttle events.
     * Matches the pattern used by {@code ControlPanel} and {@code SpeedPanel}.
     *
     * @param ap the address panel
     */
    public void setAddressPanel(@CheckForNull AddressPanel ap) {
        this.addressPanel = ap;
    }

    /**
     * Subscribes this panel to the semi-realistic engine's air system
     * PropertyChange events. Called by {@code RailDriverMenuItem} when the
     * engine is available and semi-realistic mode is enabled.
     *
     * @param eng the engine to subscribe to
     * @param settings current settings snapshot (for slider config)
     * @param callback called on the EDT when the user releases the load
     *        slider; receives the new slider position (0-based). May be null.
     */
    public void subscribeToEngine(@Nonnull SemiRealisticThrottleEngine eng,
                                  @Nonnull SemiRealisticSettings settings,
                                  @CheckForNull IntConsumer callback) {
        if (this.engine != null) {
            this.engine.removePropertyChangeListener(engineListener);
        }
        this.engine = eng;
        this.loadChangeCallback = callback;
        engine.addPropertyChangeListener(engineListener);
        updateSettings(settings);
        setAirLineValue(engine.getAirLineValue());
        setAirReservoirPct(engine.getAirReservoirPct());
    }

    /**
     * Unsubscribes from the engine's PropertyChange events. Called by
     * {@code RailDriverMenuItem} on engine detach or throttle window close.
     */
    public void unsubscribeFromEngine() {
        if (engine != null) {
            engine.removePropertyChangeListener(engineListener);
            engine = null;
        }
        loadChangeCallback = null;
    }

    /**
     * Updates the load slider's range and tick labels from new settings.
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
     * Destructor — deregisters all listeners. Called from
     * {@code ThrottleFrame.dispose()}.
     */
    public void destroy() {
        if (addressPanel != null) {
            addressPanel.removeAddressListener(this);
            addressPanel = null;
        }
        if (throttle != null) {
            throttle.removePropertyChangeListener(this);
            throttle = null;
        }
        unsubscribeFromEngine();
    }

    // ======================== AddressListener ========================

    @Override
    public void notifyAddressChosen(LocoAddress address) {
        // Address selected but throttle not yet available — no-op.
    }

    @Override
    public void notifyAddressThrottleFound(DccThrottle t) {
        if (throttle != null) {
            log.debug("notifyAddressThrottleFound() throttle non null, called for loc {}", t.getLocoAddress());
            return;
        }
        throttle = t;
        throttle.addPropertyChangeListener(this);
        setEnabled(true);
    }

    @Override
    public void notifyAddressReleased(LocoAddress la) {
        if (throttle == null) {
            log.debug("notifyAddressReleased() throttle already null, called for loc {}", la);
            return;
        }
        setEnabled(false);
        throttle.removePropertyChangeListener(this);
        throttle = null;
    }

    @Override
    public void notifyConsistAddressChosen(LocoAddress address) {
        notifyAddressChosen(address);
    }

    @Override
    public void notifyConsistAddressReleased(LocoAddress la) {
        notifyAddressReleased(la);
    }

    @Override
    public void notifyConsistAddressThrottleFound(DccThrottle t) {
        notifyAddressThrottleFound(t);
    }

    // ======================== PropertyChangeListener ========================

    @Override
    public void propertyChange(PropertyChangeEvent e) {
        // Throttle property changes are handled here but we don't currently
        // need any DCC throttle properties — air state comes from the engine.
    }

    // ======================== Enable/Disable ========================

    @Override
    public void setEnabled(boolean isEnabled) {
        super.setEnabled(isEnabled);
        loadSlider.setEnabled(isEnabled);
        airLineBar.setEnabled(isEnabled);
        airReservoirBar.setEnabled(isEnabled);
    }

    // ======================== XML Persistence ========================

    /**
     * Collects this panel's preferences into an XML Element.
     *
     * @return XML element with window position/size and load slider position
     */
    public Element getXml() {
        Element me = new Element("AirStatusPanel");
        me.setAttribute("loadSliderPosition", String.valueOf(loadSlider.getValue()));
        java.util.ArrayList<Element> children = new java.util.ArrayList<>(1);
        children.add(WindowPreferences.getPreferences(this));
        me.setContent(children);
        return me;
    }

    /**
     * Restores preferences from an XML Element.
     *
     * @param e the XML element
     */
    public void setXml(Element e) {
        Element window = e.getChild("window");
        WindowPreferences.setPreferences(this, window);
        if (e.getAttribute("loadSliderPosition") != null) {
            try {
                int pos = e.getAttribute("loadSliderPosition").getIntValue();
                if (pos >= loadSlider.getMinimum() && pos <= loadSlider.getMaximum()) {
                    loadSlider.setValue(pos);
                }
            } catch (org.jdom2.DataConversionException ex) {
                log.debug("Could not parse loadSliderPosition attribute", ex);
            }
        }
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
        JSlider slider = new JSlider(JSlider.VERTICAL, 0, steps,
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
