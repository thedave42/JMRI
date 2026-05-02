package jmri.util.usb;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import jmri.util.JmriJFrame;
import jmri.util.usb.RailDriverCalibration.AutoBrakeCal;
import jmri.util.usb.RailDriverCalibration.IndepBrakeCal;
import jmri.util.usb.RailDriverCalibration.LightsCal;
import jmri.util.usb.RailDriverCalibration.ReverserCal;
import jmri.util.usb.RailDriverCalibration.ThrottleCal;
import jmri.util.usb.RailDriverCalibration.WiperCal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Free-form calibration panel for the RailDriver Modern Desktop's analog
 * controls. See {@code docs/rpi-raildriver/plan-impl-phase3.md} §2.3.
 * <p>
 * Sections per control with rows (label / editable byte field / Capture /
 * Reset). Live byte readouts at the top update from the polling thread's
 * {@code RawByte} property-change events. Save / Reset all / Cancel at the
 * bottom.
 *
 * @author the Dave (phase 3)
 */
public final class RailDriverCalibrationFrame extends JmriJFrame implements PropertyChangeListener {

    private static final int AXIS_COUNT = 7;

    /**
     * Latest byte value seen per axis (0..6). Updated from
     * {@link #propertyChange} when a {@code RawByte} event arrives.
     * Initialized to -1 so we can show "—" until data arrives.
     */
    private final int[] liveBytes = new int[AXIS_COUNT];
    private final JLabel[] liveByteLabels = new JLabel[AXIS_COUNT];

    /** Working copy of the calibration — edits stay here until Save. */
    private final RailDriverCalibration working;

    /** Capture buttons get disabled if no menu instance is registered. */
    private final List<JButton> captureButtons = new ArrayList<>();

    /** Status label at the top — explains pre-condition state. */
    private final JLabel statusLabel = new JLabel(" ");

    /** Direct references to the editable text fields so Reset and Reset-all can update them. */
    private final List<Runnable> rowRefreshers = new ArrayList<>();

    public RailDriverCalibrationFrame() {
        super(Bundle.getMessage("RdCalibrate"));
        for (int i = 0; i < AXIS_COUNT; i++) {
            liveBytes[i] = -1;
        }
        // Load a fresh working copy from disk so the frame's edits don't
        // touch the menu item's live calibration until Save.
        working = RailDriverCalibration.loadOrDefault(RailDriverCalibration.getDefaultFile());

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(buildTopPanel(), BorderLayout.NORTH);
        content.add(new JScrollPane(buildSectionsPanel()), BorderLayout.CENTER);
        content.add(buildBottomButtons(), BorderLayout.SOUTH);
        setContentPane(content);

        setPreferredSize(new Dimension(640, 760));
        pack();

        // Subscribe to live-byte events and update the pre-condition message.
        wireUpLiveListener();

        // Refresh every text field from the working copy so they show
        // current values right after construction.
        rowRefreshers.forEach(Runnable::run);
    }

    // -------- top: live readouts + status --------

    private JPanel buildTopPanel() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JLabel header = new JLabel("Live byte readouts (move a control to watch it change):");
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(header);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 6, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;

        String[] axisLabels = {
            "Reverser   (byte 0):",
            "Throttle   (byte 1):",
            "Auto Brake (byte 2):",
            "Indep Brk  (byte 3):",
            "Bail-off   (byte 4):",
            "Wiper      (byte 5):",
            "Lights     (byte 6):"
        };
        for (int i = 0; i < AXIS_COUNT; i++) {
            int col = (i % 2) * 2;
            int row = i / 2;
            gbc.gridx = col; gbc.gridy = row;
            grid.add(new JLabel(axisLabels[i]), gbc);
            gbc.gridx = col + 1;
            JLabel value = new JLabel("—");
            value.setPreferredSize(new Dimension(80, 18));
            liveByteLabels[i] = value;
            grid.add(value, gbc);
        }
        top.add(grid);

        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(Box.createVerticalStrut(4));
        top.add(statusLabel);

        return top;
    }

    // -------- middle: per-control sections --------

    private JPanel buildSectionsPanel() {
        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));

        sections.add(buildReverserSection());
        sections.add(buildThrottleSection());
        sections.add(buildAutoBrakeSection());
        sections.add(buildIndepBrakeSection());
        sections.add(buildWiperSection());
        sections.add(buildLightsSection());

        return sections;
    }

    private JPanel buildReverserSection() {
        ReverserCal r = working.reverser();
        JPanel p = section("Reverser  (#8 / byte 0)");
        addByteRow(p, "Forward", 0,
                () -> r.forward,
                v -> r.forward = v,
                RailDriverCalibration.DEF_REVERSER_FORWARD);
        addByteRow(p, "Neutral", 0,
                () -> r.neutral,
                v -> r.neutral = v,
                RailDriverCalibration.DEF_REVERSER_NEUTRAL);
        addByteRow(p, "Reverse", 0,
                () -> r.reverse,
                v -> r.reverse = v,
                RailDriverCalibration.DEF_REVERSER_REVERSE);
        return p;
    }

    private JPanel buildThrottleSection() {
        ThrottleCal t = working.throttle();
        JPanel p = section("Throttle / Dyn Brake  (#9 / byte 1)");
        addByteRow(p, "Full Throttle", 1,
                () -> t.fullThrottle,
                v -> t.fullThrottle = v,
                RailDriverCalibration.DEF_THROTTLE_FULL);
        addByteRow(p, "Idle", 1,
                () -> t.idle,
                v -> t.idle = v,
                RailDriverCalibration.DEF_THROTTLE_IDLE);
        addByteRow(p, "Full Dyn Brake", 1,
                () -> t.fullDynBrake,
                v -> t.fullDynBrake = v,
                RailDriverCalibration.DEF_THROTTLE_FULLDYN);
        // User-configurable idle deadband (decimal, 0.0..0.5)
        addDoubleRow(p, "Idle deadband (0.0..0.5)",
                () -> t.idleDeadband,
                v -> t.idleDeadband = v,
                RailDriverCalibration.DEF_IDLE_DEADBAND,
                0.0, 0.5);
        return p;
    }

    private JPanel buildAutoBrakeSection() {
        AutoBrakeCal a = working.autoBrake();
        JPanel p = section("Auto Brake  (#10 / byte 2)  — calibration captured but not yet wired");
        addByteRow(p, "Released", 2, () -> a.released, v -> a.released = v, RailDriverCalibration.DEF_AUTOBRAKE_RELEASED);
        addByteRow(p, "SUP",      2, () -> a.sup,      v -> a.sup = v,      RailDriverCalibration.DEF_AUTOBRAKE_SUP);
        addByteRow(p, "CS",       2, () -> a.cs,       v -> a.cs = v,       RailDriverCalibration.DEF_AUTOBRAKE_CS);
        addByteRow(p, "EMG",      2, () -> a.emg,      v -> a.emg = v,      RailDriverCalibration.DEF_AUTOBRAKE_EMG);
        return p;
    }

    private JPanel buildIndepBrakeSection() {
        IndepBrakeCal i = working.indepBrake();
        JPanel p = section("Independent Brake  (#11 / bytes 3 + 4)  — calibration captured but not yet wired");
        addByteRow(p, "Full Release  (byte 3)",     3, () -> i.fullRelease,     v -> i.fullRelease = v,     RailDriverCalibration.DEF_INDEPBRAKE_FULLRELEASE);
        addByteRow(p, "Full Application (byte 3)",  3, () -> i.fullApplication, v -> i.fullApplication = v, RailDriverCalibration.DEF_INDEPBRAKE_FULLAPP);
        addByteRow(p, "Bail-off rest low  (byte 4)", 4, () -> i.bailoffRestLow,  v -> i.bailoffRestLow = v,  RailDriverCalibration.DEF_INDEPBRAKE_BAILOFF_LOW);
        addByteRow(p, "Bail-off rest high (byte 4)", 4, () -> i.bailoffRestHigh, v -> i.bailoffRestHigh = v, RailDriverCalibration.DEF_INDEPBRAKE_BAILOFF_HIGH);
        addByteRow(p, "Full Bail-off  (byte 4)",    4, () -> i.bailoffFull,     v -> i.bailoffFull = v,     RailDriverCalibration.DEF_INDEPBRAKE_BAILOFF_FULL);
        return p;
    }

    private JPanel buildWiperSection() {
        WiperCal w = working.wiper();
        JPanel p = section("Wiper  (#12 / byte 5)  — calibration captured but not yet wired");
        addByteRow(p, "Off",  5, () -> w.off,  v -> w.off = v,  RailDriverCalibration.DEF_WIPER_OFF);
        addByteRow(p, "Slow", 5, () -> w.slow, v -> w.slow = v, RailDriverCalibration.DEF_WIPER_SLOW);
        addByteRow(p, "Full", 5, () -> w.full, v -> w.full = v, RailDriverCalibration.DEF_WIPER_FULL);
        return p;
    }

    private JPanel buildLightsSection() {
        LightsCal l = working.lights();
        JPanel p = section("Lights  (#13 / byte 6)  — drives F0 (headlight)");
        addByteRow(p, "Off",  6, () -> l.off,  v -> l.off = v,  RailDriverCalibration.DEF_LIGHTS_OFF);
        addByteRow(p, "Dim",  6, () -> l.dim,  v -> l.dim = v,  RailDriverCalibration.DEF_LIGHTS_DIM);
        addByteRow(p, "Full", 6, () -> l.full, v -> l.full = v, RailDriverCalibration.DEF_LIGHTS_FULL);
        return p;
    }

    private JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /**
     * Adds one row to a section: position label, editable byte field
     * (decimal 0..255 or empty = default), Capture button (sets to current
     * live byte), Reset button (sets to default).
     */
    private void addByteRow(JPanel section, String label, int axisIndex,
                            java.util.function.Supplier<Integer> getter,
                            java.util.function.Consumer<Integer> setter,
                            int defaultValue) {
        int row = section.getComponentCount() / 4;
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = row;
        section.add(new JLabel(label), gbc);

        JTextField field = new JTextField(5);
        Runnable refresh = () -> {
            Integer cur = getter.get();
            field.setText(cur == null ? "" : cur.toString());
        };
        rowRefreshers.add(refresh);
        refresh.run();
        // Commit text-field edits back to the working calibration as the user types.
        field.addActionListener(e -> commitByteFieldEdit(field, setter));
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                commitByteFieldEdit(field, setter);
            }
        });
        gbc.gridx = 1;
        section.add(field, gbc);

        JButton capture = new JButton("Capture");
        capture.addActionListener(e -> {
            int b = liveBytes[axisIndex];
            if (b < 0) {
                showStatus("No live data on byte " + axisIndex
                        + " yet. Move the control once after opening the RailDriver throttle menu.");
                return;
            }
            setter.accept(b);
            field.setText(Integer.toString(b));
        });
        captureButtons.add(capture);
        gbc.gridx = 2;
        section.add(capture, gbc);

        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            setter.accept(null);
            field.setText("");  // empty = use default
        });
        gbc.gridx = 3;
        section.add(reset, gbc);
    }

    /**
     * Adds one row to a section for a Double-valued user setting (e.g. the
     * idle deadband). Range-checked on commit.
     */
    private void addDoubleRow(JPanel section, String label,
                              java.util.function.Supplier<Double> getter,
                              java.util.function.Consumer<Double> setter,
                              double defaultValue, double min, double max) {
        int row = section.getComponentCount() / 4;
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = row;
        section.add(new JLabel(label), gbc);

        JTextField field = new JTextField(6);
        Runnable refresh = () -> {
            Double cur = getter.get();
            field.setText(cur == null ? "" : cur.toString());
        };
        rowRefreshers.add(refresh);
        refresh.run();
        field.addActionListener(e -> commitDoubleFieldEdit(field, setter, min, max));
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                commitDoubleFieldEdit(field, setter, min, max);
            }
        });
        gbc.gridx = 1;
        section.add(field, gbc);

        // Pad the remaining columns so the row layout matches byte rows.
        gbc.gridx = 2;
        section.add(new JLabel(String.format("(default %.2f)", defaultValue)), gbc);

        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            setter.accept(null);
            field.setText("");
        });
        gbc.gridx = 3;
        section.add(reset, gbc);
    }

    private void commitByteFieldEdit(JTextField field, java.util.function.Consumer<Integer> setter) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            setter.accept(null);
            return;
        }
        try {
            int v = Integer.parseInt(text);
            if (v < 0 || v > 255) {
                showStatus("Byte values must be in 0..255 (decimal). Reverting.");
                Integer cur = (Integer) null;
                field.setText("");  // user can re-Capture or hand-edit
                setter.accept(cur);
                return;
            }
            setter.accept(v);
        } catch (NumberFormatException ex) {
            showStatus("'" + text + "' is not a valid integer. Reverting.");
            field.setText("");
            setter.accept(null);
        }
    }

    private void commitDoubleFieldEdit(JTextField field, java.util.function.Consumer<Double> setter,
                                       double min, double max) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            setter.accept(null);
            return;
        }
        try {
            double v = Double.parseDouble(text);
            if (v < min || v > max) {
                showStatus(String.format("Value must be in %.2f..%.2f. Reverting.", min, max));
                field.setText("");
                setter.accept(null);
                return;
            }
            setter.accept(v);
        } catch (NumberFormatException ex) {
            showStatus("'" + text + "' is not a valid number. Reverting.");
            field.setText("");
            setter.accept(null);
        }
    }

    // -------- bottom: action buttons --------

    private JPanel buildBottomButtons() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JButton save = new JButton("Save");
        save.addActionListener(e -> doSave());

        JButton resetAll = new JButton("Reset all to defaults");
        resetAll.addActionListener(e -> {
            working.resetToDefaults();
            rowRefreshers.forEach(Runnable::run);
            showStatus("All values reset to defaults. Click Save to persist.");
        });

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());

        bottom.add(Box.createHorizontalGlue());
        bottom.add(save);
        bottom.add(Box.createHorizontalStrut(8));
        bottom.add(resetAll);
        bottom.add(Box.createHorizontalStrut(8));
        bottom.add(cancel);
        return bottom;
    }

    private void doSave() {
        File file = RailDriverCalibration.getDefaultFile();
        if (file == null) {
            JOptionPane.showMessageDialog(this,
                    "No active JMRI profile — cannot save calibration.",
                    "Calibration save failed", JOptionPane.ERROR_MESSAGE);
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
        // Tell the open RailDriver menu (if any) to re-read from disk so the
        // running session uses the new values immediately.
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi != null) {
            mi.reloadCalibration();
        }
        showStatus("Saved to " + file.getAbsolutePath());
    }

    // -------- live-byte event wiring --------

    private void wireUpLiveListener() {
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi == null) {
            for (JButton b : captureButtons) {
                b.setEnabled(false);
            }
            statusLabel.setText("Open the RailDriver throttle menu first "
                    + "(Debug \u2192 RailDriver Throttle (built in)) to enable live readouts and Capture.");
        } else {
            mi.addPropertyChangeListener(this);
            statusLabel.setText("Move any analog control to verify live byte readouts. Then Capture each position.");
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (!"RawByte".equals(evt.getPropertyName())) {
            return;
        }
        // oldValue is "Byte N", newValue is decimal byte string.
        String byteName = String.valueOf(evt.getOldValue());
        if (!byteName.startsWith("Byte ")) {
            return;
        }
        int axis;
        try {
            axis = Integer.parseInt(byteName.substring("Byte ".length()));
        } catch (NumberFormatException ex) {
            return;
        }
        if (axis < 0 || axis >= AXIS_COUNT) {
            return;
        }
        int b;
        try {
            b = Integer.parseInt(String.valueOf(evt.getNewValue()));
        } catch (NumberFormatException ex) {
            return;
        }
        liveBytes[axis] = b;
        final int finalAxis = axis;
        SwingUtilities.invokeLater(() -> {
            liveByteLabels[finalAxis].setText(String.format("0x%02x  (%d)", b, b));
        });
    }

    @Override
    public void dispose() {
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi != null) {
            mi.removePropertyChangeListener(this);
        }
        super.dispose();
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
    }

    private static final Logger log = LoggerFactory.getLogger(RailDriverCalibrationFrame.class);
}
