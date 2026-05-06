package jmri.jmrit.usb.swing;

import java.beans.PropertyChangeListener;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

import jmri.InstanceManager;
import jmri.jmrit.usb.RailDriverCalibration;
import jmri.jmrit.usb.RailDriverMenuItem;
import jmri.jmrit.usb.RailDriverPreferencesManager;
import jmri.swing.PreferencesPanel;

import org.openide.util.lookup.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JMRI Preferences panel for RailDriver HID hardware calibration.
 * Appears as the "Calibration" tab under the "RailDriver" group in
 * the standard JMRI Preferences window.
 * <p>
 * Delegates all editing to {@link CalibrationTabPanel} and persists
 * changes through {@link RailDriverPreferencesManager}. Wires live
 * {@code "RawByte"} events from the active {@link RailDriverMenuItem}
 * so the calibration bars track lever movements in real time.
 */
@ServiceProvider(service = PreferencesPanel.class)
public class RailDriverCalibrationPreferencesPanel
        extends JPanel implements PreferencesPanel {

    private CalibrationTabPanel editor;
    private PropertyChangeListener rawByteForwarder;
    private boolean rawByteWired = false;

    public RailDriverCalibrationPreferencesPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    private void initEditor() {
        if (editor != null) return;
        editor = new CalibrationTabPanel(msg -> { /* status messages are logged */ log.debug(msg); });
        add(editor);
        rawByteForwarder = editor;
        wireRawByteListener();
    }

    /**
     * Idempotently subscribe to RawByte events from RailDriverMenuItem
     * so the calibration bars show a live cursor. Also kicks off device
     * polling if the device is present but not yet started.
     */
    private void wireRawByteListener() {
        if (rawByteWired) return;
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi == null) {
            editor.onLiveListenerWired(false);
            return;
        }
        mi.ensureDeviceAndPolling();
        mi.addPropertyChangeListener("RawByte", rawByteForwarder);
        editor.onLiveListenerWired(mi.isPollingActive());
        rawByteWired = true;
    }

    // -------- PreferencesPanel --------

    @Override
    public String getPreferencesItem() {
        return Bundle.getMessage("RdPreferencesItem");
    }

    @Override
    public String getPreferencesItemText() {
        return Bundle.getMessage("RdPreferencesItemText");
    }

    @Override
    public String getTabbedPreferencesTitle() {
        return Bundle.getMessage("RdCalibrationTabTitle");
    }

    @Override
    public String getLabelKey() {
        return Bundle.getMessage("RdCalibrationLabel");
    }

    @Override
    public JComponent getPreferencesComponent() {
        initEditor();
        return this;
    }

    @Override
    public boolean isPersistant() {
        return false;
    }

    @Override
    public String getPreferencesTooltip() {
        return Bundle.getMessage("RdPreferencesTooltip");
    }

    @Override
    public void savePreferences() {
        initEditor();

        RailDriverPreferencesManager mgr = InstanceManager.getNullableDefault(
                RailDriverPreferencesManager.class);
        if (mgr == null) {
            log.error("RailDriverPreferencesManager not available — calibration not saved.");
            return;
        }

        RailDriverCalibration working = new RailDriverCalibration();
        if (!editor.validateAndApplyTo(working)) {
            log.warn("Calibration validation failed; not saved.");
            return;
        }

        mgr.applyCalibration(working);

        // Reset editor dirty state from the now-persisted calibration.
        editor.resetToFile(mgr.getCalibration());

        log.info("RailDriver hardware calibration saved.");
    }

    @Override
    public boolean isDirty() {
        return editor != null && editor.isDirty();
    }

    @Override
    public boolean isRestartRequired() {
        return false;
    }

    @Override
    public boolean isPreferencesValid() {
        return true;
    }

    @Override
    public int getSortOrder() {
        return 2;
    }

    private static final Logger log =
            LoggerFactory.getLogger(RailDriverCalibrationPreferencesPanel.class);
}
