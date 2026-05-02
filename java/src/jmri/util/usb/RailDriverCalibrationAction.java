package jmri.util.usb;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;

/**
 * Action that opens a {@link RailDriverCalibrationFrame} for the user to
 * record per-position byte values for each analog control on the
 * RailDriver Modern Desktop.
 *
 * @author the Dave (phase 3)
 */
public class RailDriverCalibrationAction extends AbstractAction {

    public RailDriverCalibrationAction(String s) {
        super(s);
    }

    public RailDriverCalibrationAction() {
        super(Bundle.getMessage("RdCalibrate"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        // Bring the device + polling thread up if not already running, so
        // the calibration frame's live-cursor stream works whether or not
        // the throttle menu has been opened first. Failure (no device
        // connected or hid4java init failure) is tolerated — the frame
        // still opens with hand-edit and Save available, and its status
        // label tells the user the device isn't live.
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi != null) {
            mi.ensureDeviceAndPolling();
        }
        new RailDriverCalibrationFrame().setVisible(true);
    }
}
