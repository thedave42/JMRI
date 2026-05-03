package jmri.util.usb;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
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

import jmri.util.usb.RailDriverSettingsFrame.DirtyTrackingTab;
import jmri.util.usb.SemiRealisticSettings.DecoderBrakeMode;

/**
 * Settings tab content for the unified RailDriver settings window. Exposes
 * the semi-realistic-throttle physics fields described in
 * {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.4 and the
 * scenario-driven defaults from §2.4.1.
 * <p>
 * Validation ranges (§6 resolved decisions):
 * <ul>
 *   <li>Loco mass 1–500 t; loco power 1–10 000 kW; loco TE 1–2000 kN.</li>
 *   <li>Additional consist mass 0–50 000 t; driver power 0–100 %.</li>
 *   <li>Rolling resistance 0.0001–0.05.</li>
 *   <li>Mechanical / air brake max decel 0.1–5.0 m/s².</li>
 *   <li>Dyn brake max decel 0.0–2.0 m/s²; dyn brake taper 0–20 mph.</li>
 * </ul>
 * Stage 2 only enables {@code Decoder-brake mode = None}; the {@code ESU}
 * option is greyed out until stage 6 wires the decoder-brake passthrough.
 */
public final class SemiRealisticSettingsPanel extends JPanel implements DirtyTrackingTab {

    private final SemiRealisticSettings working = new SemiRealisticSettings();

    private final JCheckBox enableCheckbox = new JCheckBox("Enable semi-realistic mode");
    private final JComboBox<LoadScenario> scenarioCombo = new JComboBox<>(LoadScenario.values());

    private final JFormattedTextField locoMassTonnesField;
    private final JFormattedTextField locoPowerKwField;
    private final JFormattedTextField locoTractiveEffortKnField;
    private final JFormattedTextField additionalWeightField;
    private final JFormattedTextField driverPowerField;
    private final JFormattedTextField rollingResistanceField;

    private final JFormattedTextField brakeMaxDecelField;
    private final JFormattedTextField airBrakeMaxDecelField;
    private final JFormattedTextField dynBrakeMaxDecelField;
    private final JFormattedTextField dynBrakeVMinField;

    private final JComboBox<DecoderBrakeMode> decoderBrakeCombo = new JComboBox<>(DecoderBrakeMode.values());

    private final List<Runnable> dirtyListeners = new ArrayList<>();
    private boolean dirty = false;
    private boolean populating = false;

    public SemiRealisticSettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Working starts at defaults so a user opening the dialog before
        // stage-1 has loaded any persisted settings still sees sensible
        // values.
        working.resetToDefaults();

        locoMassTonnesField        = makeFractionField(1f, 500f);
        locoPowerKwField           = makeFractionField(1f, 10_000f);
        locoTractiveEffortKnField  = makeFractionField(1f, 2_000f);
        additionalWeightField      = makeFractionField(0f, 50_000f);
        driverPowerField           = makeFractionField(0f, 100f);
        rollingResistanceField     = makeFractionField(0.0001f, 0.05f);
        brakeMaxDecelField         = makeFractionField(0.1f, 5.0f);
        airBrakeMaxDecelField      = makeFractionField(0.1f, 5.0f);
        dynBrakeMaxDecelField      = makeFractionField(0.0f, 2.0f);
        dynBrakeVMinField          = makeFractionField(0.0f, 20.0f);

        // Bind change listeners for dirty tracking.
        enableCheckbox.addItemListener(e -> { if (!populating) markDirty(); refreshEnableState(); });
        scenarioCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                if (!populating) markDirty();
                refreshEnableState();
            }
        });
        decoderBrakeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                if (!populating) markDirty();
            }
        });
        // ESU option: present in dropdown but disabled in stage 2 — wired in stage 6.
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

        attachDirtyOnEdits(locoMassTonnesField);
        attachDirtyOnEdits(locoPowerKwField);
        attachDirtyOnEdits(locoTractiveEffortKnField);
        attachDirtyOnEdits(additionalWeightField);
        attachDirtyOnEdits(driverPowerField);
        attachDirtyOnEdits(rollingResistanceField);
        attachDirtyOnEdits(brakeMaxDecelField);
        attachDirtyOnEdits(airBrakeMaxDecelField);
        attachDirtyOnEdits(dynBrakeMaxDecelField);
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
        // Range validation comes for free from JFormattedTextField's
        // NumberFormatter; if a field's value is null after parsing it
        // means the user typed something out of range or non-numeric.
        // The three "auto" fields (loco mass / power / TE) accept empty
        // text → null (= auto), so they get the nullable code path.
        if (!commitField(locoMassTonnesField, "Loco mass", true) ||
            !commitField(locoPowerKwField, "Loco power", true) ||
            !commitField(locoTractiveEffortKnField, "Loco tractive effort", true) ||
            !commitField(additionalWeightField, "Additional consist mass", false) ||
            !commitField(driverPowerField, "Driver power", false) ||
            !commitField(rollingResistanceField, "Rolling resistance", false) ||
            !commitField(brakeMaxDecelField, "Mechanical brake max decel", false) ||
            !commitField(airBrakeMaxDecelField, "Air brake max decel", false) ||
            !commitField(dynBrakeMaxDecelField, "Dynamic brake max decel", false) ||
            !commitField(dynBrakeVMinField, "Dynamic brake taper threshold", false)) {
            return false;
        }

        // Pull field values into working
        working.persistedEnabled = enableCheckbox.isSelected();
        // After Save, liveEnabled := persistedEnabled per §2.3.
        working.liveEnabled = working.persistedEnabled;
        working.scenario = (LoadScenario) scenarioCombo.getSelectedItem();

        // Loco mass / power / TE: per §2.4.1 precedence the operator-typed
        // override only takes effect in the CUSTOM scenario. In non-Custom
        // scenarios the loco-physics rows are read-only "auto" displays of
        // the scenario default; persist them as null so the engine resolves
        // them via the scenario fallback at runtime (otherwise switching
        // scenarios later would incorrectly carry the previous scenario's
        // displayed value as a sticky override).
        if (working.scenario == LoadScenario.CUSTOM) {
            working.locoMassKg          = parseAutoFloat(locoMassTonnesField, 1000f);
            working.locoPowerKw         = parseAutoFloat(locoPowerKwField, 1f);
            working.locoTractiveEffortKn = parseAutoFloat(locoTractiveEffortKnField, 1f);
        } else {
            working.locoMassKg          = null;
            working.locoPowerKw         = null;
            working.locoTractiveEffortKn = null;
        }
        working.additionalWeightTonnes = floatOf(additionalWeightField, SemiRealisticSettings.DEFAULT_ADDITIONAL_TONNES);
        working.driverPowerPercent     = floatOf(driverPowerField, SemiRealisticSettings.DEFAULT_DRIVER_POWER_PCT);
        working.rollingResistanceCoeff = floatOf(rollingResistanceField, SemiRealisticSettings.DEFAULT_ROLLING_RESISTANCE);
        working.brakeMaxDecel    = floatOf(brakeMaxDecelField, SemiRealisticSettings.DEFAULT_BRAKE_MAX_DECEL);
        working.airBrakeMaxDecel = floatOf(airBrakeMaxDecelField, SemiRealisticSettings.DEFAULT_AIR_BRAKE_MAX_DECEL);
        working.dynBrakeMaxDecel = floatOf(dynBrakeMaxDecelField, SemiRealisticSettings.DEFAULT_DYN_BRAKE_MAX_DECEL);
        working.dynBrakeVMinMph  = floatOf(dynBrakeVMinField, SemiRealisticSettings.DEFAULT_DYN_BRAKE_V_MIN_MPH);

        DecoderBrakeMode mode = (DecoderBrakeMode) decoderBrakeCombo.getSelectedItem();
        // ESU is not yet wired; force NONE in stage 2 even if the dropdown
        // somehow surfaces ESU.
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

        addLabeled(body, gc, "Scenario:", scenarioCombo);
        addLabeled(body, gc, "Loco mass (t):", locoMassTonnesField);
        addLabeled(body, gc, "Loco power (kW):", locoPowerKwField);
        addLabeled(body, gc, "Loco tractive effort (kN):", locoTractiveEffortKnField);
        addLabeled(body, gc, "Additional consist mass (t):", additionalWeightField);
        addLabeled(body, gc, "Driver power (%):", driverPowerField);
        addLabeled(body, gc, "Rolling resistance coefficient:", rollingResistanceField);
        addLabeled(body, gc, "Mechanical brake max decel (m/s\u00b2):", brakeMaxDecelField);
        addLabeled(body, gc, "Air brake max decel (m/s\u00b2):", airBrakeMaxDecelField);
        addLabeled(body, gc, "Dynamic brake max decel (m/s\u00b2):", dynBrakeMaxDecelField);
        addLabeled(body, gc, "Dynamic brake taper threshold (mph):", dynBrakeVMinField);
        addLabeled(body, gc, "Decoder brake mode:", decoderBrakeCombo);

        // Filler so the grid stays top-aligned in a tall window.
        gc.gridx = 0; gc.gridy++; gc.gridwidth = 2; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH;
        body.add(Box.createGlue(), gc);
        return body;
    }

    private void addLabeled(JPanel body, GridBagConstraints gc, String label, JPanel field) {
        gc.gridx = 0; gc.fill = GridBagConstraints.NONE;
        body.add(new JLabel(label), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        body.add(field, gc);
        gc.gridy++;
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
        formatter.setAllowsInvalid(true); // allow transient invalid input; we validate on commit
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

    /** Commit the field's edited text and verify the result is in range.
     *  When {@code nullable} is true, an empty text body is accepted (it
     *  means "auto" for the loco-physics override fields). On failure,
     *  request focus on the offending field. */
    private boolean commitField(JFormattedTextField f, String description, boolean nullable) {
        if (nullable) {
            String text = f.getText();
            if (text == null || text.trim().isEmpty()) {
                return true;
            }
        }
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

    /**
     * Parse an "auto" sentinel-aware field. When the user has cleared the
     * field text we persist {@code null} (= auto). When they typed a
     * number we persist that number multiplied by {@code unitScale}
     * (e.g. tonnes → kg for the mass row, 1.0 for kW / kN rows that need
     * no unit conversion).
     */
    private static @javax.annotation.CheckForNull Float parseAutoFloat(JFormattedTextField f, float unitScale) {
        String text = f.getText();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        Object v = f.getValue();
        if (v instanceof Number) {
            return ((Number) v).floatValue() * unitScale;
        }
        return null;
    }

    private void renderToFields() {
        populating = true;
        try {
            enableCheckbox.setSelected(working.persistedEnabled);
            scenarioCombo.setSelectedItem(working.scenario);
            // Scenario defaults are in persisted units (kg / W / N); the
            // override scale converts them to user units (t / kW / kN) so
            // they fit the field's formatter range. The override branch
            // applies the same scale to the override value.
            renderAutoField(locoMassTonnesField, working.locoMassKg, working.scenario.defaultLocoMassKg() * 0.001f, 0.001f);
            renderAutoField(locoPowerKwField, working.locoPowerKw, working.scenario.defaultLocoPowerW() / 1000f, 1f);
            renderAutoField(locoTractiveEffortKnField, working.locoTractiveEffortKn, working.scenario.defaultLocoTractiveEffortN() / 1000f, 1f);
            additionalWeightField.setValue((double) working.additionalWeightTonnes);
            driverPowerField.setValue((double) working.driverPowerPercent);
            rollingResistanceField.setValue((double) working.rollingResistanceCoeff);
            brakeMaxDecelField.setValue((double) working.brakeMaxDecel);
            airBrakeMaxDecelField.setValue((double) working.airBrakeMaxDecel);
            dynBrakeMaxDecelField.setValue((double) working.dynBrakeMaxDecel);
            dynBrakeVMinField.setValue((double) working.dynBrakeVMinMph);
            decoderBrakeCombo.setSelectedItem(working.decoderBrakeMode);
        } finally {
            populating = false;
        }
    }

    /** "auto" override fields show the resolved scenario default when null
     *  (rendered in user-facing units) and the explicit override otherwise.
     *  {@code overrideUnitScale} converts the persisted-unit override into
     *  the user-facing display unit (e.g. 0.001 for kg→t). */
    private static void renderAutoField(JFormattedTextField f,
                                        @javax.annotation.CheckForNull Float override,
                                        float scenarioDefaultUserUnit,
                                        float overrideUnitScale) {
        if (override == null) {
            f.setValue((double) scenarioDefaultUserUnit);
        } else {
            f.setValue((double) (override.floatValue() * overrideUnitScale));
        }
    }

    /** Field enable/disable rules per §2.4: when the master checkbox is
     *  off, all rows are disabled; otherwise, loco-physics rows are
     *  editable only when scenario = Custom. */
    private void refreshEnableState() {
        boolean enabled = enableCheckbox.isSelected();
        boolean isCustom = scenarioCombo.getSelectedItem() == LoadScenario.CUSTOM;
        scenarioCombo.setEnabled(enabled);
        locoMassTonnesField.setEnabled(enabled && isCustom);
        locoPowerKwField.setEnabled(enabled && isCustom);
        locoTractiveEffortKnField.setEnabled(enabled && isCustom);
        additionalWeightField.setEnabled(enabled);
        driverPowerField.setEnabled(enabled);
        rollingResistanceField.setEnabled(enabled);
        brakeMaxDecelField.setEnabled(enabled);
        airBrakeMaxDecelField.setEnabled(enabled);
        dynBrakeMaxDecelField.setEnabled(enabled);
        dynBrakeVMinField.setEnabled(enabled);
        decoderBrakeCombo.setEnabled(enabled);
    }
}
