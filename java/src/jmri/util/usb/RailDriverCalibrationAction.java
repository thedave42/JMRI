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
        new RailDriverCalibrationFrame().setVisible(true);
    }
}
