package jmri.jmrit.usb.swing;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

import jmri.InstanceManager;
import jmri.jmrit.usb.RailDriverCalibration;
import jmri.jmrit.usb.RailDriverPreferencesManager;
import jmri.jmrit.usb.SemiRealisticSettings;
import jmri.swing.PreferencesPanel;
import jmri.util.swing.JmriJOptionPane;

import org.openide.util.lookup.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JMRI Preferences panel for semi-realistic throttle engine settings.
 * Appears as the "Settings" tab under the "RailDriver" group in the
 * standard JMRI Preferences window.
 * <p>
 * Delegates all editing to {@link SemiRealisticSettingsPanel} and
 * persists changes through {@link RailDriverPreferencesManager}.
 */
@ServiceProvider(service = PreferencesPanel.class)
public class RailDriverSemiRealisticPreferencesPanel
        extends JPanel implements PreferencesPanel {

    private SemiRealisticSettingsPanel editor;
    private boolean enabledAtLoad;

    public RailDriverSemiRealisticPreferencesPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    private void initEditor() {
        if (editor != null) return;
        editor = new SemiRealisticSettingsPanel();
        add(editor);

        RailDriverPreferencesManager mgr = InstanceManager.getNullableDefault(
                RailDriverPreferencesManager.class);
        enabledAtLoad = (mgr != null)
                ? mgr.getCalibration().semiRealistic().persistedEnabled
                : false;
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
        return Bundle.getMessage("RdSettingsTabTitle");
    }

    @Override
    public String getLabelKey() {
        return Bundle.getMessage("RdSettingsLabel");
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
            log.error("RailDriverPreferencesManager not available — settings not saved.");
            return;
        }

        // Build a working settings from UI fields.
        RailDriverCalibration working = new RailDriverCalibration();
        if (!editor.validateAndApplyTo(working)) {
            log.warn("Semi-realistic settings validation failed; not saved.");
            return;
        }
        SemiRealisticSettings newSettings = working.semiRealistic();

        // Preserve liveEnabled — the Jynstrument toggle is session-only
        // and must not be overwritten by a Preferences save.
        SemiRealisticSettings current = mgr.getCalibration().semiRealistic();
        newSettings.liveEnabled = current.liveEnabled;

        boolean enabledChanged = (newSettings.persistedEnabled != enabledAtLoad);

        mgr.applySettings(newSettings);

        // Reset editor dirty state from the now-persisted calibration.
        editor.resetFromCalibration(mgr.getCalibration());
        enabledAtLoad = newSettings.persistedEnabled;

        // Alert the user if the enabled flag changed (TASK-058).
        if (enabledChanged) {
            JmriJOptionPane.showMessageDialog(this,
                    Bundle.getMessage("RdEnabledChangedMessage"),
                    Bundle.getMessage("RdEnabledChangedTitle"),
                    JmriJOptionPane.INFORMATION_MESSAGE);
        }

        log.info("RailDriver semi-realistic settings saved.");
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
        return 1;
    }

    private static final Logger log =
            LoggerFactory.getLogger(RailDriverSemiRealisticPreferencesPanel.class);
}
