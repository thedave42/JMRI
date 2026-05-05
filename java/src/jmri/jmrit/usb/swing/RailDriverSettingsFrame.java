package jmri.jmrit.usb.swing;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import jmri.jmrit.usb.RailDriverCalibration;
import jmri.jmrit.usb.RailDriverMenuItem;
import jmri.util.JmriJFrame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified RailDriver settings window. Hosts a {@link JTabbedPane} with
 * two tabs:
 * <ul>
 *   <li><b>Settings</b> — semi-realistic-throttle physics and brake
 *       settings. In stage 1 this tab is a placeholder; fields land in
 *       stage 2 of the semi-realistic-throttle plan.</li>
 *   <li><b>Calibration</b> — the visual-bar UI for capturing per-detent
 *       byte values for each analog control on the RailDriver Modern
 *       Desktop. Body unchanged from the predecessor standalone
 *       calibration window.</li>
 * </ul>
 * Save / Apply / Cancel live in a shared bottom button bar; Apply is
 * driven by either tab's dirty flag. The frame subscribes to
 * {@code "RawByte"} events on {@link RailDriverMenuItem} and forwards
 * them to the calibration tab so the live cursor still works while that
 * tab is visible.
 * <p>
 * See {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.4.
 */
public final class RailDriverSettingsFrame extends JmriJFrame {

    /**
     * Contract that both the Settings tab and the Calibration tab implement
     * so the host frame can drive the dirty-tracked Save/Apply/Cancel bar.
     */
    public interface DirtyTrackingTab {

        /** @return true if the tab has unsaved edits. */
        boolean isDirty();

        /**
         * Subscribes a listener invoked whenever {@link #isDirty()} may
         * have changed. Listeners run on the EDT.
         */
        void addDirtyChangeListener(Runnable listener);

        /**
         * Validates the tab's pending edits and copies them into
         * {@code target}.
         *
         * @return true on success; false if validation failed (the host
         *         frame will auto-select this tab and surface an error).
         */
        boolean validateAndApplyTo(RailDriverCalibration target);

        /**
         * Reloads tab state from a freshly-loaded-from-disk calibration.
         * Implementations clear their dirty flag.
         */
        void resetToFile(RailDriverCalibration freshFromDisk);
    }

    private final JTabbedPane tabs = new JTabbedPane();
    private final SemiRealisticSettingsPanel settingsTab;
    private final CalibrationTabPanel calibrationTab;
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton applyButton = new JButton("Apply");

    private final PropertyChangeListener rawByteForwarder;

    public RailDriverSettingsFrame() {
        super(Bundle.getMessage("RdSettings"));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        settingsTab = new SemiRealisticSettingsPanel();
        calibrationTab = new CalibrationTabPanel(this::showStatus);

        // Pre-populate the settings tab from the on-disk calibration so it
        // reflects what was last saved (or defaults on a fresh profile).
        java.io.File f = RailDriverCalibration.getDefaultFile();
        RailDriverCalibration cal = RailDriverCalibration.loadOrDefault(f);
        settingsTab.resetToFile(cal);

        // The Settings tab is shown first; users opening this dialog from
        // the Debug menu have most often reached for it for semi-realistic
        // settings (calibration is set once and seldom revisited).
        tabs.addTab("Settings",    settingsTab);
        tabs.addTab("Calibration", calibrationTab);
        tabs.setSelectedIndex(0);

        Runnable refreshApply = this::refreshApplyEnabled;
        settingsTab.addDirtyChangeListener(refreshApply);
        calibrationTab.addDirtyChangeListener(refreshApply);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(tabs, BorderLayout.CENTER);
        content.add(buildSouth(), BorderLayout.SOUTH);
        setContentPane(content);

        setPreferredSize(new Dimension(720, 820));
        pack();

        rawByteForwarder = calibrationTab;
        wireUpLiveListener();
        refreshApplyEnabled();
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));

        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(statusLabel);
        south.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton save = new JButton("Save");
        save.addActionListener(e -> doSaveOrApply(true));

        applyButton.addActionListener(e -> doSaveOrApply(false));

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> doCancel());

        buttons.add(Box.createHorizontalGlue());
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(applyButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(cancel);

        south.add(buttons);
        return south;
    }

    private void refreshApplyEnabled() {
        applyButton.setEnabled(settingsTab.isDirty() || calibrationTab.isDirty());
    }

    /**
     * Save (close on success) and Apply (leave open) share the same body.
     * They differ only in whether {@code dispose()} is called after a
     * successful round-trip.
     */
    private void doSaveOrApply(boolean closeAfterSuccess) {
        File file = RailDriverCalibration.getDefaultFile();
        if (file == null) {
            JOptionPane.showMessageDialog(this,
                    "No active JMRI profile — cannot save calibration.",
                    "Calibration save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        RailDriverCalibration working = new RailDriverCalibration();

        // Order matters per the plan §2.4: Settings tab first, Calibration
        // tab second. If either fails, auto-select that tab, surface the
        // failure on the status line so the user understands what
        // happened, log it for the messages.log, and bail.
        if (!settingsTab.validateAndApplyTo(working)) {
            tabs.setSelectedComponent(settingsTab);
            String msg = "Save failed: a Settings field is invalid or out of range.";
            showStatus(msg);
            log.warn(msg);
            return;
        }
        if (!calibrationTab.validateAndApplyTo(working)) {
            tabs.setSelectedComponent(calibrationTab);
            String msg = "Save failed: a Calibration field is invalid.";
            showStatus(msg);
            log.warn(msg);
            return;
        }

        try {
            working.save(file);
        } catch (IOException ex) {
            log.error("Failed to write RailDriver calibration to {}", file, ex);
            JOptionPane.showMessageDialog(this,
                    "Failed to save calibration:\n" + ex.getMessage(),
                    "Calibration save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi != null) {
            mi.reloadCalibration();
        }
        log.info("RailDriver calibration saved to {}", file.getAbsolutePath());

        // Round-trip from disk so the in-window state matches what was
        // actually persisted (and clear dirty on both tabs as a side
        // effect of resetToFile).
        RailDriverCalibration roundTripped = RailDriverCalibration.loadOrDefault(file);
        settingsTab.resetToFile(roundTripped);
        calibrationTab.resetToFile(roundTripped);

        if (closeAfterSuccess) {
            dispose();
        }
    }

    private void doCancel() {
        if (settingsTab.isDirty() || calibrationTab.isDirty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Discard unsaved changes?",
                    "Unsaved changes",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }
        dispose();
    }

    private void wireUpLiveListener() {
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi == null) {
            // Should not happen — DebugMenu constructs the menu item on
            // app startup so getInstance() is non-null by the time the
            // settings menu is reachable.
            statusLabel.setText("Internal error: RailDriverMenuItem not initialised.");
            calibrationTab.onLiveListenerWired(false);
            return;
        }
        // Subscribe specifically for "RawByte" events so we don't get
        // spurious property-change notifications (e.g. menu-popup
        // ancestor events) on the AWT path.
        mi.addPropertyChangeListener("RawByte", rawByteForwarder);
        calibrationTab.onLiveListenerWired(mi.isPollingActive());
    }

    @Override
    public void dispose() {
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi != null) {
            mi.removePropertyChangeListener("RawByte", rawByteForwarder);
        }
        super.dispose();
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
    }

    private static final Logger log = LoggerFactory.getLogger(RailDriverSettingsFrame.class);
}
