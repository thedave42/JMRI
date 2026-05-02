package jmri.util.usb;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;

/**
 * Action that opens the unified {@link RailDriverSettingsFrame}, hosting
 * both the semi-realistic-throttle settings tab and the per-detent
 * calibration tab.
 * <p>
 * Replaces the predecessor {@code RailDriverCalibrationAction} from the
 * RailDriver bring-up phase. See {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md}
 * §2.4 / §3.1.
 */
public class RailDriverSettingsAction extends AbstractAction {

    public RailDriverSettingsAction(String s) {
        super(s);
    }

    public RailDriverSettingsAction() {
        super(Bundle.getMessage("RdSettings"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        // Bring the device + polling thread up if not already running, so
        // the calibration tab's live-cursor stream works whether or not
        // the throttle menu has been opened first. Failure (no device
        // connected or hid4java init failure) is tolerated — the frame
        // still opens with hand-edit and Save available, and its status
        // label tells the user the device isn't live.
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi != null) {
            mi.ensureDeviceAndPolling();
        }
        new RailDriverSettingsFrame().setVisible(true);
    }
}
