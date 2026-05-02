package jmri.util.usb;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
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
 * Visual calibration UI for the RailDriver Modern Desktop's analog controls.
 * Each control is shown as one (or two, for Indep Brake) horizontal
 * {@link CalibrationBar} with detent markers and a live cursor that tracks
 * the user's lever movements. The user moves a lever to a detent, clicks
 * the corresponding {@code Capture &lt;Name&gt;} button, and the current
 * byte is recorded as that detent's calibrated value.
 * <p>
 * See {@code docs/rpi-raildriver/plan-impl-phase3.md} §2.3.
 *
 * @author the Dave (phase 3)
 */
public final class RailDriverCalibrationFrame extends JmriJFrame implements PropertyChangeListener {

    private static final int AXIS_COUNT = 7;

    private final int[] liveBytes = new int[AXIS_COUNT];
    private final CalibrationBar[] barsByAxis = new CalibrationBar[AXIS_COUNT];

    private final RailDriverCalibration working;

    private final List<JButton> captureButtons = new ArrayList<>();
    private final List<CalibrationBar> allBars = new ArrayList<>();
    private final List<Runnable> auxRefreshers = new ArrayList<>();

    private final JLabel statusLabel = new JLabel(" ");

    public RailDriverCalibrationFrame() {
        super(Bundle.getMessage("RdCalibrate"));
        for (int i = 0; i < AXIS_COUNT; i++) {
            liveBytes[i] = -1;
        }
        working = RailDriverCalibration.loadOrDefault(RailDriverCalibration.getDefaultFile());

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(new JScrollPane(buildSections()), BorderLayout.CENTER);
        content.add(buildBottomButtons(), BorderLayout.SOUTH);
        setContentPane(content);

        setPreferredSize(new Dimension(720, 820));
        pack();

        wireUpLiveListener();
        auxRefreshers.forEach(Runnable::run);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Move each control to a detent and click Capture for that position.");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);

        JLabel legend = new JLabel(
            "<html><span style='color:#1464C8'>\u25BC blue</span> = captured detent &nbsp; "
            + "<span style='color:#A0A0A0'>\u25BC grey</span> = inventory default &nbsp; "
            + "<span style='color:#DC1E1E'>\u25B2 red</span> = current live position</html>");
        legend.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(legend);

        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(Box.createVerticalStrut(4));
        header.add(statusLabel);

        return header;
    }

    private JComponent buildSections() {
        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));

        sections.add(buildReverserSection());
        sections.add(Box.createVerticalStrut(4));
        sections.add(buildThrottleSection());
        sections.add(Box.createVerticalStrut(4));
        sections.add(buildAutoBrakeSection());
        sections.add(Box.createVerticalStrut(4));
        sections.add(buildIndepBrakeSection());
        sections.add(Box.createVerticalStrut(4));
        sections.add(buildWiperSection());
        sections.add(Box.createVerticalStrut(4));
        sections.add(buildLightsSection());

        return sections;
    }

    private JPanel buildReverserSection() {
        ReverserCal r = working.reverser();
        JPanel section = section("Reverser  (#8 / byte 0)");
        CalibrationBar bar = newBarForAxis(0);
        bar.addDetent("Forward",
                () -> r.forward != null ? r.forward : RailDriverCalibration.DEF_REVERSER_FORWARD,
                () -> r.forward != null);
        bar.addDetent("Neutral",
                () -> r.neutral != null ? r.neutral : RailDriverCalibration.DEF_REVERSER_NEUTRAL,
                () -> r.neutral != null);
        bar.addDetent("Reverse",
                () -> r.reverse != null ? r.reverse : RailDriverCalibration.DEF_REVERSER_REVERSE,
                () -> r.reverse != null);
        section.add(bar);
        section.add(buildCaptureButtonRow(0, bar,
                row("Forward", v -> r.forward = v, () -> r.forward = null),
                row("Neutral", v -> r.neutral = v, () -> r.neutral = null),
                row("Reverse", v -> r.reverse = v, () -> r.reverse = null)));
        return section;
    }

    private JPanel buildThrottleSection() {
        ThrottleCal t = working.throttle();
        JPanel section = section("Throttle / Dyn Brake  (#9 / byte 1)  — DOWN = throttle, UP = dyn brake");
        CalibrationBar bar = newBarForAxis(1);
        bar.addDetent("Full Throttle",
                () -> t.fullThrottle != null ? t.fullThrottle : RailDriverCalibration.DEF_THROTTLE_FULL,
                () -> t.fullThrottle != null);
        bar.addDetent("Idle",
                () -> t.idle != null ? t.idle : RailDriverCalibration.DEF_THROTTLE_IDLE,
                () -> t.idle != null);
        bar.addDetent("Full Dyn Brake",
                () -> t.fullDynBrake != null ? t.fullDynBrake : RailDriverCalibration.DEF_THROTTLE_FULLDYN,
                () -> t.fullDynBrake != null);
        section.add(bar);
        section.add(buildCaptureButtonRow(1, bar,
                row("Full Dyn Brake", v -> t.fullDynBrake = v, () -> t.fullDynBrake = null),
                row("Idle",           v -> t.idle         = v, () -> t.idle         = null),
                row("Full Throttle",  v -> t.fullThrottle = v, () -> t.fullThrottle = null)));
        section.add(buildDeadbandRow(t));
        return section;
    }

    private JPanel buildAutoBrakeSection() {
        AutoBrakeCal a = working.autoBrake();
        JPanel section = section("Auto Brake  (#10 / byte 2)  — calibration captured but not yet wired");
        CalibrationBar bar = newBarForAxis(2);
        bar.addDetent("Released", () -> a.released != null ? a.released : RailDriverCalibration.DEF_AUTOBRAKE_RELEASED, () -> a.released != null);
        bar.addDetent("SUP",      () -> a.sup      != null ? a.sup      : RailDriverCalibration.DEF_AUTOBRAKE_SUP,      () -> a.sup      != null);
        bar.addDetent("CS",       () -> a.cs       != null ? a.cs       : RailDriverCalibration.DEF_AUTOBRAKE_CS,       () -> a.cs       != null);
        bar.addDetent("EMG",      () -> a.emg      != null ? a.emg      : RailDriverCalibration.DEF_AUTOBRAKE_EMG,      () -> a.emg      != null);
        section.add(bar);
        section.add(buildCaptureButtonRow(2, bar,
                row("EMG",      v -> a.emg      = v, () -> a.emg      = null),
                row("CS",       v -> a.cs       = v, () -> a.cs       = null),
                row("SUP",      v -> a.sup      = v, () -> a.sup      = null),
                row("Released", v -> a.released = v, () -> a.released = null)));
        return section;
    }

    private JPanel buildIndepBrakeSection() {
        IndepBrakeCal i = working.indepBrake();
        JPanel section = section("Independent Brake  (#11 / bytes 3 + 4)  — calibration captured but not yet wired");

        JLabel sub3 = new JLabel("Main travel  (byte 3):");
        sub3.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(sub3);
        CalibrationBar bar3 = newBarForAxis(3);
        bar3.addDetent("Full Release",     () -> i.fullRelease     != null ? i.fullRelease     : RailDriverCalibration.DEF_INDEPBRAKE_FULLRELEASE, () -> i.fullRelease     != null);
        bar3.addDetent("Full Application", () -> i.fullApplication != null ? i.fullApplication : RailDriverCalibration.DEF_INDEPBRAKE_FULLAPP,     () -> i.fullApplication != null);
        section.add(bar3);
        section.add(buildCaptureButtonRow(3, bar3,
                row("Full Application", v -> i.fullApplication = v, () -> i.fullApplication = null),
                row("Full Release",     v -> i.fullRelease     = v, () -> i.fullRelease     = null)));

        JLabel sub4 = new JLabel("Bail-off (transient momentary switch, byte 4):");
        sub4.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(Box.createVerticalStrut(4));
        section.add(sub4);
        CalibrationBar bar4 = newBarForAxis(4);
        bar4.addDetent("Rest",     () -> i.bailoffRest    != null ? i.bailoffRest    : RailDriverCalibration.DEF_INDEPBRAKE_BAILOFF_REST,    () -> i.bailoffRest    != null);
        bar4.addDetent("Bail-off", () -> i.bailoffPressed != null ? i.bailoffPressed : RailDriverCalibration.DEF_INDEPBRAKE_BAILOFF_PRESSED, () -> i.bailoffPressed != null);
        section.add(bar4);
        section.add(buildCaptureButtonRow(4, bar4,
                row("Rest",     v -> i.bailoffRest    = v, () -> i.bailoffRest    = null),
                row("Bail-off", v -> i.bailoffPressed = v, () -> i.bailoffPressed = null)));
        return section;
    }

    private JPanel buildWiperSection() {
        WiperCal w = working.wiper();
        JPanel section = section("Wiper  (#12 / byte 5)  — calibration captured but not yet wired");
        CalibrationBar bar = newBarForAxis(5);
        bar.addDetent("Off",  () -> w.off  != null ? w.off  : RailDriverCalibration.DEF_WIPER_OFF,  () -> w.off  != null);
        bar.addDetent("Slow", () -> w.slow != null ? w.slow : RailDriverCalibration.DEF_WIPER_SLOW, () -> w.slow != null);
        bar.addDetent("Full", () -> w.full != null ? w.full : RailDriverCalibration.DEF_WIPER_FULL, () -> w.full != null);
        section.add(bar);
        section.add(buildCaptureButtonRow(5, bar,
                row("Off",  v -> w.off  = v, () -> w.off  = null),
                row("Slow", v -> w.slow = v, () -> w.slow = null),
                row("Full", v -> w.full = v, () -> w.full = null)));
        return section;
    }

    private JPanel buildLightsSection() {
        LightsCal l = working.lights();
        JPanel section = section("Lights  (#13 / byte 6)  — drives F0 (headlight)");
        CalibrationBar bar = newBarForAxis(6);
        bar.addDetent("Off",  () -> l.off  != null ? l.off  : RailDriverCalibration.DEF_LIGHTS_OFF,  () -> l.off  != null);
        bar.addDetent("Dim",  () -> l.dim  != null ? l.dim  : RailDriverCalibration.DEF_LIGHTS_DIM,  () -> l.dim  != null);
        bar.addDetent("Full", () -> l.full != null ? l.full : RailDriverCalibration.DEF_LIGHTS_FULL, () -> l.full != null);
        section.add(bar);
        section.add(buildCaptureButtonRow(6, bar,
                row("Off",  v -> l.off  = v, () -> l.off  = null),
                row("Dim",  v -> l.dim  = v, () -> l.dim  = null),
                row("Full", v -> l.full = v, () -> l.full = null)));
        return section;
    }

    private JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private CalibrationBar newBarForAxis(int axisIndex) {
        CalibrationBar bar = new CalibrationBar();
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        barsByAxis[axisIndex] = bar;
        allBars.add(bar);
        return bar;
    }

    private static final class RowSpec {
        final String label;
        final Consumer<Integer> capturer;
        final Runnable resetter;
        RowSpec(String label, Consumer<Integer> capturer, Runnable resetter) {
            this.label = label; this.capturer = capturer; this.resetter = resetter;
        }
    }

    private static RowSpec row(String label, Consumer<Integer> capturer, Runnable resetter) {
        return new RowSpec(label, capturer, resetter);
    }

    private JPanel buildCaptureButtonRow(int axisIndex, CalibrationBar bar, RowSpec... rows) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (RowSpec r : rows) {
            JButton capture = new JButton("Capture " + r.label);
            capture.addActionListener(e -> {
                int b = liveBytes[axisIndex];
                if (b < 0) {
                    showStatus("No live data on byte " + axisIndex
                            + " yet. Move the control once after opening the RailDriver throttle menu.");
                    return;
                }
                r.capturer.accept(b);
                bar.refresh();
                showStatus(String.format("Captured %s = 0x%02x  (%d). Click Save to persist.",
                        r.label, b, b));
            });
            captureButtons.add(capture);
            p.add(capture);
        }
        JButton resetSection = new JButton("Reset to defaults");
        resetSection.addActionListener(e -> {
            for (RowSpec r : rows) {
                r.resetter.run();
            }
            bar.refresh();
        });
        p.add(resetSection);
        return p;
    }

    private JPanel buildDeadbandRow(ThrottleCal t) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(new JLabel("Idle deadband (0.0..0.5):"));

        JTextField field = new JTextField(6);
        Runnable refresh = () -> {
            Double cur = t.idleDeadband;
            field.setText(cur == null ? "" : cur.toString());
        };
        auxRefreshers.add(refresh);
        refresh.run();
        Runnable commit = () -> {
            String text = field.getText().trim();
            if (text.isEmpty()) {
                t.idleDeadband = null;
                return;
            }
            try {
                double v = Double.parseDouble(text);
                if (v < 0.0 || v > 0.5) {
                    showStatus("Idle deadband must be in 0.0..0.5. Reverting.");
                    field.setText("");
                    t.idleDeadband = null;
                    return;
                }
                t.idleDeadband = v;
            } catch (NumberFormatException ex) {
                showStatus("'" + text + "' is not a valid number. Reverting.");
                field.setText("");
                t.idleDeadband = null;
            }
        };
        field.addActionListener(e -> commit.run());
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { commit.run(); }
        });
        p.add(field);

        p.add(new JLabel(String.format("(default %.2f)", RailDriverCalibration.DEF_IDLE_DEADBAND)));

        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            t.idleDeadband = null;
            field.setText("");
        });
        p.add(reset);

        return p;
    }

    private JComponent buildBottomButtons() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JButton save = new JButton("Save");
        save.addActionListener(e -> doSave());

        JButton resetAll = new JButton("Reset all to defaults");
        resetAll.addActionListener(e -> {
            working.resetToDefaults();
            allBars.forEach(CalibrationBar::refresh);
            auxRefreshers.forEach(Runnable::run);
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
        RailDriverMenuItem mi = RailDriverMenuItem.getInstance();
        if (mi != null) {
            mi.reloadCalibration();
        }
        showStatus("Saved to " + file.getAbsolutePath());
    }

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
            statusLabel.setText("Move any control to verify the live cursor; click Capture when at the desired detent.");
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (!"RawByte".equals(evt.getPropertyName())) {
            return;
        }
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
            CalibrationBar bar = barsByAxis[finalAxis];
            if (bar != null) {
                bar.setLiveByte(b);
            }
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
