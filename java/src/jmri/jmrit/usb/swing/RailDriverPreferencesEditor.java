package jmri.jmrit.usb.swing;

import jmri.jmrit.usb.RailDriverCalibration;

/**
 * Contract for RailDriver settings/calibration editor panels that
 * participate in the JMRI Preferences dirty-tracked save lifecycle.
 * <p>
 * Extracted from the former {@code RailDriverSettingsFrame.DirtyTrackingTab}
 * inner interface so the panels can be hosted by both the standalone
 * settings frame and the standard JMRI Preferences window.
 */
public interface RailDriverPreferencesEditor {

    /** @return true if the editor has unsaved edits. */
    boolean isDirty();

    /**
     * Subscribes a listener invoked whenever {@link #isDirty()} may
     * have changed. Listeners run on the EDT.
     */
    void addDirtyChangeListener(Runnable listener);

    /**
     * Validates the editor's pending edits and copies them into
     * {@code target}.
     *
     * @param target the calibration object to write validated edits into
     * @return true on success; false if validation failed
     */
    boolean validateAndApplyTo(RailDriverCalibration target);

    /**
     * Reloads editor state from a freshly-loaded calibration.
     * Implementations clear their dirty flag.
     *
     * @param cal the calibration to reset from
     */
    void resetFromCalibration(RailDriverCalibration cal);
}
