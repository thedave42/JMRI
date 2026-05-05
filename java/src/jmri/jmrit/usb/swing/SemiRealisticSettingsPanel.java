package jmri.jmrit.usb.swing;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.NumberFormatter;

import jmri.jmrit.usb.swing.RailDriverSettingsFrame.DirtyTrackingTab;
import jmri.jmrit.usb.LoadScenario;
import jmri.jmrit.usb.RailDriverCalibration;
import jmri.jmrit.usb.SemiRealisticSettings;
import jmri.jmrit.usb.SemiRealisticSettings.DecoderBrakeMode;

/**
 * Settings tab content for the unified RailDriver settings window. Exposes
 * the wall-clock-only feel-tuning fields described in
 * {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.4 and the
 * scenario-driven defaults from §2.4.1.
 * <p>
 * Validation ranges (§6 resolved decisions, wall-clock-only model):
 * <ul>
 *   <li>maxAccelAtRest 0.1–10.0 m/s²; vCorner 1.0–100.0 m/s.</li>
 *   <li>driver power 0–100 %; designTopSpeed 1.0–100.0 m/s.</li>
 *   <li>resistStatic 0.0–10.0 m/s²; resistLinear 0.0–1.0 1/s; resistQuadratic 0.0–0.1 1/m.</li>
 *   <li>mechanical / air brake 0.1–20.0 m/s²; dyn brake 0.0–10.0 m/s².</li>
 *   <li>dynBrakeMassFraction 0.0–1.0; dyn brake taper 0–20 mph.</li>
 * </ul>
 * Stage 2 only enables {@code Decoder-brake mode = None}; the {@code ESU}
 * option is greyed out until stage 6 wires the decoder-brake passthrough.
 */
public final class SemiRealisticSettingsPanel extends JPanel implements DirtyTrackingTab {

    private final SemiRealisticSettings working = new SemiRealisticSettings();

    private final JCheckBox enableCheckbox = new JCheckBox("Enable semi-realistic mode");
    private final JComboBox<LoadScenario> scenarioCombo = new JComboBox<>(LoadScenario.values());

    // Drive coefficients
    private final JFormattedTextField maxAccelAtRestField;
    private final JFormattedTextField vCornerField;
    private final JFormattedTextField driverPowerField;
    private final JFormattedTextField designTopSpeedField;
    private final JCheckBox steamCheckbox = new JCheckBox("Steam locomotive (a_drive \u00d7 (v/vTop)^0.85)");

    // Davis-shape coast resistance
    private final JFormattedTextField resistStaticField;
    private final JFormattedTextField resistLinearField;
    private final JFormattedTextField resistQuadField;

    // Brakes
    private final JFormattedTextField brakeMaxDecelField;
    private final JFormattedTextField airBrakeMaxDecelField;
    private final JFormattedTextField dynBrakeMaxDecelField;
    private final JFormattedTextField dynBrakeMassFractionField;
    private final JFormattedTextField dynBrakeVMinField;

    private final JComboBox<DecoderBrakeMode> decoderBrakeCombo = new JComboBox<>(DecoderBrakeMode.values());

    private final List<Runnable> dirtyListeners = new ArrayList<>();
    private boolean dirty = false;
    private boolean populating = false;

    public SemiRealisticSettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Working starts at Light-engine defaults so a user opening the
        // dialog before stage-1 has loaded any persisted settings still
        // sees sensible values.
        working.resetToDefaults();

        maxAccelAtRestField     = makeFractionField(0.1f, 10.0f);
        vCornerField            = makeFractionField(1.0f, 100.0f);
        driverPowerField        = makeFractionField(0f, 100f);
        designTopSpeedField     = makeFractionField(1.0f, 100.0f);
        resistStaticField       = makeFractionField(0.0f, 10.0f);
        resistLinearField       = makeFractionField(0.0f, 1.0f);
        resistQuadField         = makeFractionField(0.0f, 0.1f);
        brakeMaxDecelField      = makeFractionField(0.1f, 20.0f);
        airBrakeMaxDecelField   = makeFractionField(0.1f, 20.0f);
        dynBrakeMaxDecelField   = makeFractionField(0.0f, 10.0f);
        dynBrakeMassFractionField = makeFractionField(0.0f, 1.0f);
        dynBrakeVMinField       = makeFractionField(0.0f, 20.0f);

        // Bind change listeners for dirty tracking.
        enableCheckbox.addItemListener(e -> { if (!populating) markDirty(); refreshEnableState(); });
        steamCheckbox.addItemListener(e -> { if (!populating) markDirty(); });
        scenarioCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                if (!populating) {
                    // Snap all per-coefficient fields to the new scenario's
                    // defaults so the operator sees the preset's values.
                    LoadScenario sel = (LoadScenario) scenarioCombo.getSelectedItem();
                    if (sel != null) {
                        working.applyScenarioDefaults(sel);
                        renderToFields();
                    }
                    markDirty();
                }
                refreshEnableState();
            }
        });
        decoderBrakeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                if (!populating) markDirty();
            }
        });
        decoderBrakeCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                java.awt.Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == DecoderBrakeMode.ESU && c instanceof JLabel) {
                    ((JLabel) c).setEnabled(false);
                    ((JLabel) c).setText("ESU (stage 6)");
                }
                return c;
            }
        });

        attachDirtyOnEdits(maxAccelAtRestField);
        attachDirtyOnEdits(vCornerField);
        attachDirtyOnEdits(driverPowerField);
        attachDirtyOnEdits(designTopSpeedField);
        attachDirtyOnEdits(resistStaticField);
        attachDirtyOnEdits(resistLinearField);
        attachDirtyOnEdits(resistQuadField);
        attachDirtyOnEdits(brakeMaxDecelField);
        attachDirtyOnEdits(airBrakeMaxDecelField);
        attachDirtyOnEdits(dynBrakeMaxDecelField);
        attachDirtyOnEdits(dynBrakeMassFractionField);
        attachDirtyOnEdits(dynBrakeVMinField);

        JScrollPane scroll = new JScrollPane(buildBody());
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(null);
        add(scroll);

        renderToFields();
        refreshEnableState();
    }

    // -------- DirtyTrackingTab --------

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void addDirtyChangeListener(Runnable listener) {
        dirtyListeners.add(listener);
    }

    @Override
    public boolean validateAndApplyTo(RailDriverCalibration target) {
        if (!commitField(maxAccelAtRestField, "Max accel at rest") ||
            !commitField(vCornerField, "Power fall-off corner speed") ||
            !commitField(driverPowerField, "Driver power") ||
            !commitField(designTopSpeedField, "Design top speed") ||
            !commitField(resistStaticField, "Static rolling resistance") ||
            !commitField(resistLinearField, "Linear mechanical resistance") ||
            !commitField(resistQuadField, "Aerodynamic drag") ||
            !commitField(brakeMaxDecelField, "Mechanical brake max decel") ||
            !commitField(airBrakeMaxDecelField, "Air brake max decel") ||
            !commitField(dynBrakeMaxDecelField, "Dynamic brake max decel") ||
            !commitField(dynBrakeMassFractionField, "Dynamic brake mass fraction") ||
            !commitField(dynBrakeVMinField, "Dynamic brake taper threshold")) {
            return false;
        }

        // Pull field values into working
        working.persistedEnabled = enableCheckbox.isSelected();
        // After Save, liveEnabled := persistedEnabled per §2.3.
        working.liveEnabled = working.persistedEnabled;
        LoadScenario sel = (LoadScenario) scenarioCombo.getSelectedItem();
        working.scenario = sel != null ? sel : LoadScenario.LIGHT_ENGINE;
        working.steam    = steamCheckbox.isSelected();

        // All per-coefficient fields are committed to working regardless of
        // scenario. In non-Custom scenarios the fields are read-only on the
        // UI, so they always reflect the scenario's preset values; in
        // Custom they reflect the operator's edits. Either way the persisted
        // values are what the engine reads at runtime.
        working.maxAccelAtRestMs2    = floatOf(maxAccelAtRestField, sel != null ? sel.maxAccelAtRestMs2() : 2.5f);
        working.vCornerMps           = floatOf(vCornerField,        sel != null ? sel.vCornerMps()        : 35.76f);
        working.driverPowerPercent   = floatOf(driverPowerField,    sel != null ? sel.driverPowerPct()*100f : 100f);
        working.designTopSpeedMps    = floatOf(designTopSpeedField, sel != null ? sel.designTopSpeedMps() : 35.76f);
        working.resistStaticMs2      = floatOf(resistStaticField,   sel != null ? sel.resistStaticMs2()   : 1.0f);
        working.resistLinearPerSec   = floatOf(resistLinearField,   sel != null ? sel.resistLinearPerSec(): 0.0f);
        working.resistQuadPerMeter   = floatOf(resistQuadField,     sel != null ? sel.resistQuadPerMeter(): 0.001f);
        working.brakeMaxDecelMs2     = floatOf(brakeMaxDecelField,  sel != null ? sel.brakeMaxDecelMs2()  : 4.0f);
        working.airBrakeMaxDecelMs2  = floatOf(airBrakeMaxDecelField, sel != null ? sel.airBrakeMaxDecelMs2() : 6.0f);
        working.dynBrakeMaxDecelMs2  = floatOf(dynBrakeMaxDecelField, sel != null ? sel.dynBrakeMaxDecelMs2() : 1.6f);
        working.dynBrakeMassFraction = floatOf(dynBrakeMassFractionField, sel != null ? sel.dynBrakeMassFraction() : 1.0f);
        working.dynBrakeVMinMph      = floatOf(dynBrakeVMinField, SemiRealisticSettings.DEFAULT_DYN_BRAKE_V_MIN_MPH);

        DecoderBrakeMode mode = (DecoderBrakeMode) decoderBrakeCombo.getSelectedItem();
        working.decoderBrakeMode = (mode == null || mode == DecoderBrakeMode.ESU)
                ? DecoderBrakeMode.NONE : mode;

        target.semiRealistic().copyFrom(working);
        return true;
    }

    @Override
    public void resetToFile(RailDriverCalibration freshFromDisk) {
        working.copyFrom(freshFromDisk.semiRealistic());
        renderToFields();
        setDirty(false);
    }

    private void markDirty()    { setDirty(true); }
    private void setDirty(boolean v) {
        if (dirty != v) {
            dirty = v;
            for (Runnable r : dirtyListeners) r.run();
        }
    }

    // -------- UI build --------

    private JPanel buildBody() {
        JPanel body = new JPanel(new GridBagLayout());
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0; gc.gridy = 0;
        gc.gridwidth = 2;
        body.add(enableCheckbox, gc);
        gc.gridwidth = 1;
        gc.gridy++;

        addSectionLabel(body, gc, "Loco scenario");
        addLabeled(body, gc, "Scenario:", scenarioCombo);

        addSectionLabel(body, gc, "Drive coefficients (wall-clock m/s\u00b2 and m/s)");
        addLabeled(body, gc, "Max accel at rest (m/s\u00b2):", maxAccelAtRestField);
        addLabeled(body, gc, "Power fall-off corner speed (m/s):", vCornerField);
        addLabeled(body, gc, "Driver power (%):", driverPowerField);
        addLabeled(body, gc, "Design top speed (m/s):", designTopSpeedField);
        gc.gridx = 0; gc.gridwidth = 2;
        body.add(steamCheckbox, gc);
        gc.gridwidth = 1; gc.gridy++;

        addSectionLabel(body, gc, "Coast resistance (Davis-shape, wall-clock units)");
        addLabeled(body, gc, "Static rolling resistance (m/s\u00b2):", resistStaticField);
        addLabeled(body, gc, "Linear mechanical resistance (1/s):", resistLinearField);
        addLabeled(body, gc, "Aerodynamic drag (1/m):", resistQuadField);

        addSectionLabel(body, gc, "Brakes (wall-clock m/s\u00b2)");
        addLabeled(body, gc, "Mechanical brake max decel (m/s\u00b2):", brakeMaxDecelField);
        addLabeled(body, gc, "Air brake max decel (m/s\u00b2):", airBrakeMaxDecelField);
        addLabeled(body, gc, "Dynamic brake max decel (m/s\u00b2):", dynBrakeMaxDecelField);
        addLabeled(body, gc, "Dynamic brake mass fraction (0\u20131):", dynBrakeMassFractionField);
        addLabeled(body, gc, "Dynamic brake taper threshold (mph):", dynBrakeVMinField);

        addSectionLabel(body, gc, "Decoder integration");
        addLabeled(body, gc, "Decoder brake mode:", decoderBrakeCombo);

        // Filler so the grid stays top-aligned in a tall window.
        gc.gridx = 0; gc.gridy++; gc.gridwidth = 2; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH;
        body.add(Box.createGlue(), gc);
        return body;
    }

    private void addSectionLabel(JPanel body, GridBagConstraints gc, String text) {
        gc.gridx = 0; gc.gridwidth = 2; gc.fill = GridBagConstraints.HORIZONTAL;
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        body.add(l, gc);
        gc.gridwidth = 1; gc.gridy++;
    }

    private void addLabeled(JPanel body, GridBagConstraints gc, String label, java.awt.Component field) {
        gc.gridx = 0; gc.fill = GridBagConstraints.NONE;
        body.add(new JLabel(label), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        body.add(field, gc);
        gc.gridy++;
    }

    // -------- Field plumbing --------

    private static JFormattedTextField makeFractionField(float min, float max) {
        NumberFormat fmt = NumberFormat.getNumberInstance();
        fmt.setMaximumFractionDigits(6);
        fmt.setGroupingUsed(false);
        NumberFormatter formatter = new NumberFormatter(fmt);
        formatter.setValueClass(Double.class);
        formatter.setMinimum((double) min);
        formatter.setMaximum((double) max);
        formatter.setAllowsInvalid(true);
        formatter.setCommitsOnValidEdit(false);
        JFormattedTextField f = new JFormattedTextField(formatter);
        f.setColumns(10);
        return f;
    }

    private void attachDirtyOnEdits(JTextField f) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { if (!populating) markDirty(); }
            @Override public void removeUpdate(DocumentEvent e)  { if (!populating) markDirty(); }
            @Override public void changedUpdate(DocumentEvent e) { if (!populating) markDirty(); }
        });
    }

    private boolean commitField(JFormattedTextField f, String description) {
        try {
            f.commitEdit();
        } catch (java.text.ParseException ex) {
            f.requestFocusInWindow();
            return false;
        }
        Object v = f.getValue();
        if (v == null) {
            f.requestFocusInWindow();
            return false;
        }
        return true;
    }

    private static float floatOf(JFormattedTextField f, float fallback) {
        Object v = f.getValue();
        if (v instanceof Number) {
            return ((Number) v).floatValue();
        }
        return fallback;
    }

    private void renderToFields() {
        populating = true;
        try {
            enableCheckbox.setSelected(working.persistedEnabled);
            scenarioCombo.setSelectedItem(working.scenario);
            steamCheckbox.setSelected(working.steam);
            maxAccelAtRestField.setValue((double) working.maxAccelAtRestMs2);
            vCornerField.setValue((double) working.vCornerMps);
            driverPowerField.setValue((double) working.driverPowerPercent);
            designTopSpeedField.setValue((double) working.designTopSpeedMps);
            resistStaticField.setValue((double) working.resistStaticMs2);
            resistLinearField.setValue((double) working.resistLinearPerSec);
            resistQuadField.setValue((double) working.resistQuadPerMeter);
            brakeMaxDecelField.setValue((double) working.brakeMaxDecelMs2);
            airBrakeMaxDecelField.setValue((double) working.airBrakeMaxDecelMs2);
            dynBrakeMaxDecelField.setValue((double) working.dynBrakeMaxDecelMs2);
            dynBrakeMassFractionField.setValue((double) working.dynBrakeMassFraction);
            dynBrakeVMinField.setValue((double) working.dynBrakeVMinMph);
            decoderBrakeCombo.setSelectedItem(working.decoderBrakeMode);
        } finally {
            populating = false;
        }
    }

    /** Field enable/disable rules per §2.4: when the master checkbox is
     *  off, all rows are disabled; otherwise, per-coefficient rows are
     *  editable only when scenario = Custom. The scenario picker itself is
     *  always editable (so the operator can switch presets). */
    private void refreshEnableState() {
        boolean enabled = enableCheckbox.isSelected();
        boolean isCustom = scenarioCombo.getSelectedItem() == LoadScenario.CUSTOM;
        scenarioCombo.setEnabled(enabled);
        boolean tunable = enabled && isCustom;
        maxAccelAtRestField.setEnabled(tunable);
        vCornerField.setEnabled(tunable);
        driverPowerField.setEnabled(tunable);
        designTopSpeedField.setEnabled(tunable);
        steamCheckbox.setEnabled(tunable);
        resistStaticField.setEnabled(tunable);
        resistLinearField.setEnabled(tunable);
        resistQuadField.setEnabled(tunable);
        brakeMaxDecelField.setEnabled(tunable);
        airBrakeMaxDecelField.setEnabled(tunable);
        dynBrakeMaxDecelField.setEnabled(tunable);
        dynBrakeMassFractionField.setEnabled(tunable);
        dynBrakeVMinField.setEnabled(tunable);
        decoderBrakeCombo.setEnabled(enabled);
    }
}
