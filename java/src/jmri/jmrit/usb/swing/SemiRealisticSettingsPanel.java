package jmri.jmrit.usb.swing;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import jmri.jmrit.usb.swing.RailDriverSettingsFrame.DirtyTrackingTab;
import jmri.jmrit.usb.RailDriverCalibration;
import jmri.jmrit.usb.SemiRealisticSettings;
import jmri.jmrit.usb.SemiRealisticSettings.DecoderBrakeMode;

/**
 * Settings tab content for the unified RailDriver settings window.
 * Exposes EngineDriver-aligned step-rate fields.
 * <p>
 * This is a temporary implementation that will be replaced by a proper
 * {@code PreferencesPanel} SPI in Phase 9.
 */
public final class SemiRealisticSettingsPanel extends JPanel implements DirtyTrackingTab {

    private final SemiRealisticSettings working = new SemiRealisticSettings();

    private final JCheckBox enableCheckbox = new JCheckBox("Enable semi-realistic mode");

    // Ramp timing
    private final JSpinner accelDelaySpinner = new JSpinner(new SpinnerNumberModel(300, 10, 5000, 10));
    private final JSpinner decelDelaySpinner = new JSpinner(new SpinnerNumberModel(800, 10, 5000, 10));
    private final JSpinner emitIntervalSpinner = new JSpinner(new SpinnerNumberModel(50, 10, 1000, 10));

    // Brake
    private final JSpinner brakeStepsSpinner = new JSpinner(new SpinnerNumberModel(7, 1, 20, 1));

    // Air brake
    private final JSpinner airRechargePcntSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 100, 1));
    private final JSpinner airRefreshRateSpinner = new JSpinner(new SpinnerNumberModel(2000, 0, 10000, 100));
    private final JSpinner airReplenishPcntSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));

    // Dynamic brake
    private final JSpinner dynBrakeMinStepSpinner = new JSpinner(new SpinnerNumberModel(8, 0, 126, 1));

    // Load
    private final JSpinner loadStepsSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
    private final JSpinner maxLoadPcntSpinner = new JSpinner(new SpinnerNumberModel(1000, 100, 5000, 100));

    // Decoder brake
    private final JComboBox<DecoderBrakeMode> decoderBrakeCombo = new JComboBox<>(DecoderBrakeMode.values());

    private final List<Runnable> dirtyListeners = new ArrayList<>();
    private boolean dirty = false;
    private boolean populating = false;

    public SemiRealisticSettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        working.resetToDefaults();

        enableCheckbox.addItemListener(e -> { if (!populating) markDirty(); refreshEnableState(); });
        attachDirtyOnSpinner(accelDelaySpinner);
        attachDirtyOnSpinner(decelDelaySpinner);
        attachDirtyOnSpinner(emitIntervalSpinner);
        attachDirtyOnSpinner(brakeStepsSpinner);
        attachDirtyOnSpinner(airRechargePcntSpinner);
        attachDirtyOnSpinner(airRefreshRateSpinner);
        attachDirtyOnSpinner(airReplenishPcntSpinner);
        attachDirtyOnSpinner(dynBrakeMinStepSpinner);
        attachDirtyOnSpinner(loadStepsSpinner);
        attachDirtyOnSpinner(maxLoadPcntSpinner);
        decoderBrakeCombo.addItemListener(e -> { if (!populating) markDirty(); });

        JScrollPane scroll = new JScrollPane(buildBody());
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(null);
        add(scroll);

        renderToFields();
        refreshEnableState();
    }

    // -------- DirtyTrackingTab --------

    @Override public boolean isDirty() { return dirty; }

    @Override public void addDirtyChangeListener(Runnable listener) { dirtyListeners.add(listener); }

    @Override
    public boolean validateAndApplyTo(RailDriverCalibration target) {
        working.persistedEnabled = enableCheckbox.isSelected();
        working.liveEnabled = working.persistedEnabled;
        working.baseAccelDelayMs = intOf(accelDelaySpinner);
        working.baseDecelDelayMs = intOf(decelDelaySpinner);
        working.minEmitIntervalMs = intOf(emitIntervalSpinner);
        working.numberOfBrakeSteps = intOf(brakeStepsSpinner);
        working.airLineRechargePcnt = intOf(airRechargePcntSpinner);
        working.airRefreshRateMs = intOf(airRefreshRateSpinner);
        working.airReservoirReplenishPcnt = intOf(airReplenishPcntSpinner);
        working.dynBrakeMinSpeedStep = intOf(dynBrakeMinStepSpinner);
        working.numberOfLoadSteps = intOf(loadStepsSpinner);
        working.maxLoadPcnt = intOf(maxLoadPcntSpinner);
        DecoderBrakeMode mode = (DecoderBrakeMode) decoderBrakeCombo.getSelectedItem();
        working.decoderBrakeMode = mode != null ? mode : DecoderBrakeMode.NONE;
        target.semiRealistic().copyFrom(working);
        return true;
    }

    @Override
    public void resetToFile(RailDriverCalibration freshFromDisk) {
        working.copyFrom(freshFromDisk.semiRealistic());
        renderToFields();
        setDirty(false);
    }

    private void markDirty() { setDirty(true); }
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
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        body.add(enableCheckbox, gc);
        gc.gridwidth = 1; gc.gridy++;

        addSectionLabel(body, gc, "Ramp timing");
        addLabeled(body, gc, "Acceleration delay (ms/step):", accelDelaySpinner);
        addLabeled(body, gc, "Deceleration delay (ms/step):", decelDelaySpinner);
        addLabeled(body, gc, "Min emit interval (ms):", emitIntervalSpinner);

        addSectionLabel(body, gc, "Brakes");
        addLabeled(body, gc, "Independent brake steps:", brakeStepsSpinner);
        addLabeled(body, gc, "Dynamic brake min speed step:", dynBrakeMinStepSpinner);

        addSectionLabel(body, gc, "Air brake");
        addLabeled(body, gc, "Line recharge (%/tick):", airRechargePcntSpinner);
        addLabeled(body, gc, "Air refresh rate (ms, 0=flat):", airRefreshRateSpinner);
        addLabeled(body, gc, "Reservoir replenish (%/tick):", airReplenishPcntSpinner);

        addSectionLabel(body, gc, "Load");
        addLabeled(body, gc, "Number of load steps:", loadStepsSpinner);
        addLabeled(body, gc, "Max load percentage (\u00d7100):", maxLoadPcntSpinner);

        addSectionLabel(body, gc, "Decoder integration");
        addLabeled(body, gc, "Decoder brake mode:", decoderBrakeCombo);

        gc.gridx = 0; gc.gridy++; gc.gridwidth = 2; gc.weighty = 1.0;
        gc.fill = GridBagConstraints.BOTH;
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

    // -------- Helpers --------

    private void attachDirtyOnSpinner(JSpinner s) {
        s.addChangeListener(e -> { if (!populating) markDirty(); });
    }

    private static int intOf(JSpinner s) {
        Object v = s.getValue();
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private void renderToFields() {
        populating = true;
        try {
            enableCheckbox.setSelected(working.persistedEnabled);
            accelDelaySpinner.setValue(working.baseAccelDelayMs);
            decelDelaySpinner.setValue(working.baseDecelDelayMs);
            emitIntervalSpinner.setValue(working.minEmitIntervalMs);
            brakeStepsSpinner.setValue(working.numberOfBrakeSteps);
            airRechargePcntSpinner.setValue(working.airLineRechargePcnt);
            airRefreshRateSpinner.setValue(working.airRefreshRateMs);
            airReplenishPcntSpinner.setValue(working.airReservoirReplenishPcnt);
            dynBrakeMinStepSpinner.setValue(working.dynBrakeMinSpeedStep);
            loadStepsSpinner.setValue(working.numberOfLoadSteps);
            maxLoadPcntSpinner.setValue(working.maxLoadPcnt);
            decoderBrakeCombo.setSelectedItem(working.decoderBrakeMode);
        } finally {
            populating = false;
        }
    }

    private void refreshEnableState() {
        boolean enabled = enableCheckbox.isSelected();
        accelDelaySpinner.setEnabled(enabled);
        decelDelaySpinner.setEnabled(enabled);
        emitIntervalSpinner.setEnabled(enabled);
        brakeStepsSpinner.setEnabled(enabled);
        airRechargePcntSpinner.setEnabled(enabled);
        airRefreshRateSpinner.setEnabled(enabled);
        airReplenishPcntSpinner.setEnabled(enabled);
        dynBrakeMinStepSpinner.setEnabled(enabled);
        loadStepsSpinner.setEnabled(enabled);
        maxLoadPcntSpinner.setEnabled(enabled);
        decoderBrakeCombo.setEnabled(enabled);
    }
}
