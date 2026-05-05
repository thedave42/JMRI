package jmri.jmrit.usb.swing;

import java.awt.Component;
import java.awt.FlowLayout;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import jmri.InstanceManager;
import jmri.jmrit.usb.RailDriverCalibration;
import jmri.jmrit.usb.RailDriverCalibration.AutoBrakeCal;
import jmri.jmrit.usb.RailDriverCalibration.IndepBrakeCal;
import jmri.jmrit.usb.RailDriverCalibration.LightsCal;
import jmri.jmrit.usb.RailDriverCalibration.ReverserCal;
import jmri.jmrit.usb.RailDriverCalibration.ThrottleCal;
import jmri.jmrit.usb.RailDriverCalibration.WiperCal;
import jmri.jmrit.usb.RailDriverPreferencesManager;

/**
 * Calibration editor for the RailDriver Modern Desktop's analog
 * controls: visual bars with detent markers and a live cursor that
 * tracks lever movements.
 * <p>
 * Hosted as a tab in the JMRI Preferences window under "RailDriver"
 * via {@link RailDriverCalibrationPreferencesPanel}.
 * <p>
 * Capture buttons, per-section "Reset to defaults" buttons, and the
 * "Reset all to defaults" button mark the panel dirty so the
 * Preferences Save button activates.
 * {@link #resetFromCalibration(RailDriverCalibration)} clears
 * the dirty state after a successful save round-trip.
 * <p>
 * See {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.4 and
 * §3.1.
 */
public final class CalibrationTabPanel extends JPanel implements RailDriverPreferencesEditor, PropertyChangeListener {

    private static final int AXIS_COUNT = 7;

    private final int[] liveBytes = new int[AXIS_COUNT];
    private final CalibrationBar[] barsByAxis = new CalibrationBar[AXIS_COUNT];

    private final RailDriverCalibration working;

    private final List<JButton> captureButtons = new ArrayList<>();
    private final List<CalibrationBar> allBars = new ArrayList<>();
    private final List<Runnable> auxRefreshers = new ArrayList<>();

    private final List<Runnable> dirtyListeners = new ArrayList<>();
    private boolean dirty = false;

    private final Consumer<String> statusSink;

    /**
     * @param statusSink callback for short status-line messages (e.g. capture
     *                   confirmations, reset notifications). The host frame
     *                   typically routes this to its bottom status label.
     */
    public CalibrationTabPanel(@javax.annotation.Nonnull Consumer<String> statusSink) {
        this.statusSink = statusSink;
        for (int i = 0; i < AXIS_COUNT; i++) {
            liveBytes[i] = -1;
        }
        // Load current calibration from PreferencesManager (or defaults).
        RailDriverPreferencesManager mgr = InstanceManager.getNullableDefault(
                RailDriverPreferencesManager.class);
        if (mgr != null) {
            this.working = new RailDriverCalibration();
            this.working.copyFrom(mgr.getCalibration());
        } else {
            this.working = new RailDriverCalibration();
        }

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildHeader());
        add(Box.createVerticalStrut(4));
        JScrollPane sectionsScroll = new JScrollPane(buildSections());
        sectionsScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(sectionsScroll);
        add(Box.createVerticalStrut(4));
        add(buildResetAllRow());

        auxRefreshers.forEach(Runnable::run);
    }

    /**
     * Updates the polling-state-driven enable/disable for capture buttons
     * and the introductory status hint. Called by the host frame after it
     * has wired up the live-byte listener.
     */
    public void onLiveListenerWired(boolean pollingActive) {
        if (pollingActive) {
            statusSink.accept(
                "Move any control to verify the live cursor; click Capture when at the desired detent.");
        } else {
            for (JButton b : captureButtons) {
                b.setEnabled(false);
            }
            statusSink.accept(
                "RailDriver device not detected — values can still be edited and saved by hand.");
        }
    }

    // -------- RailDriverPreferencesEditor --------

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void addDirtyChangeListener(Runnable listener) {
        dirtyListeners.add(listener);
    }

    @Override
    public boolean validateAndApplyTo(RailDriverCalibration target) {
        // Captured byte values are validated at capture time (always in
        // [0,255] because they come from a single byte read off the HID
        // report) and at parse time by readInt(). There is nothing
        // additional to validate at Save time for the calibration tab.
        // Use copyCalibrationFieldsFrom so the calibration tab does NOT
        // clobber the <semiRealistic> subtree that the Settings tab
        // wrote in the same Save sequence.
        target.copyCalibrationFieldsFrom(working);
        return true;
    }

    @Override
    public void resetFromCalibration(RailDriverCalibration freshFromDisk) {
        working.copyFrom(freshFromDisk);
        allBars.forEach(CalibrationBar::refresh);
        auxRefreshers.forEach(Runnable::run);
        setDirty(false);
    }

    private void setDirty(boolean newDirty) {
        if (dirty != newDirty) {
            dirty = newDirty;
            for (Runnable r : dirtyListeners) {
                r.run();
            }
        }
    }

    // -------- UI build --------

    private JComponent buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("Move each control to a detent and click Capture for that position.");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);

        JLabel legend = new JLabel(
            "<html><span style='color:#1464C8'>\u25BC blue</span> = captured detent &nbsp; "
            + "<span style='color:#A0A0A0'>\u25BC grey</span> = inventory default &nbsp; "
            + "<span style='color:#DC1E1E'>\u25B2 red</span> = current live position</html>");
        legend.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(legend);

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
        // Bar markers in byte order (low byte left → high byte right):
        // Full Dyn Brake (0x3a) ── Idle Low (~0x80) ── Idle High (~0x86) ── Full Throttle (0xdd)
        bar.addDetent("Full Throttle",
                () -> t.fullThrottle != null ? t.fullThrottle : RailDriverCalibration.DEF_THROTTLE_FULL,
                () -> t.fullThrottle != null);
        bar.addDetent("Idle High",
                () -> t.idleHigh != null ? t.idleHigh : RailDriverCalibration.DEF_THROTTLE_IDLE_HIGH,
                () -> t.idleHigh != null);
        bar.addDetent("Idle Low",
                () -> t.idleLow != null ? t.idleLow : RailDriverCalibration.DEF_THROTTLE_IDLE_LOW,
                () -> t.idleLow != null);
        bar.addDetent("Full Dyn Brake",
                () -> t.fullDynBrake != null ? t.fullDynBrake : RailDriverCalibration.DEF_THROTTLE_FULLDYN,
                () -> t.fullDynBrake != null);
        section.add(bar);
        // Capture buttons in left-to-right bar order (matches the byte-axis layout).
        // Idle is captured as a [Idle Low, Idle High] range — bytes within that
        // range register as no-movement; bytes above Idle High accelerate the
        // loco toward Full Throttle. Below Idle Low is also no-movement today
        // (dyn-brake side not yet wired).
        section.add(buildCaptureButtonRow(1, bar,
                row("Full Dyn Brake", v -> t.fullDynBrake = v, () -> t.fullDynBrake = null),
                row("Idle Low",       v -> t.idleLow      = v, () -> t.idleLow      = null),
                row("Idle High",      v -> t.idleHigh     = v, () -> t.idleHigh     = null),
                row("Full Throttle",  v -> t.fullThrottle = v, () -> t.fullThrottle = null)));
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
                    statusSink.accept("No live data on byte " + axisIndex
                            + " yet. Move the control once after opening the RailDriver throttle menu.");
                    return;
                }
                r.capturer.accept(b);
                bar.refresh();
                setDirty(true);
                statusSink.accept(String.format("Captured %s = 0x%02x  (%d). Click Save to persist.",
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
            setDirty(true);
        });
        p.add(resetSection);
        return p;
    }

    private JComponent buildResetAllRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton resetAll = new JButton("Reset all to defaults");
        resetAll.addActionListener(e -> {
            working.resetToDefaults();
            allBars.forEach(CalibrationBar::refresh);
            auxRefreshers.forEach(Runnable::run);
            setDirty(true);
            statusSink.accept("All values reset to defaults. Click Save to persist.");
        });
        row.add(resetAll);
        return row;
    }

    // -------- live-byte property change --------

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
        jmri.util.ThreadingUtil.runOnGUIEventually(() -> {
            CalibrationBar bar = barsByAxis[finalAxis];
            if (bar != null) {
                bar.setLiveByte(b);
            }
        });
    }
}
