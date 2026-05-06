package jmri.jmrit.usb;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Per-profile calibration data for the RailDriver Modern Desktop's analog controls.
 * <p>
 * Default values match {@code docs/rpi-raildriver/control-inventory.md}. Each
 * captured position is stored as a boxed Integer (byte 0..255) or {@code null}
 * meaning "not yet captured — fall back to the default".
 * <p>
 * Persistence is handled by {@link RailDriverPreferencesManager} via
 * AuxiliaryConfiguration fragments. This class is a pure data holder.
 *
 * @author the Dave (phase 3)
 */
public final class RailDriverCalibration {

    // Defaults from control-inventory.md. Centre/middle positions that the
    // capture protocol intentionally does not measure are filled with sensible
    // mid-range values so that derived thresholds reproduce phase-2 behavior
    // when no calibration file is present.
    public static final int DEF_REVERSER_FORWARD   = 0x42;
    public static final int DEF_REVERSER_NEUTRAL   = 0x80;   // mid; not captured by inventory
    public static final int DEF_REVERSER_REVERSE   = 0xcf;

    public static final int DEF_THROTTLE_FULL       = 0xdd;   // lever DOWN per inventory
    public static final int DEF_THROTTLE_IDLE_LOW   = 0x80;   // bipolar zero — lower bound of default idle range
    public static final int DEF_THROTTLE_IDLE_HIGH  = 0x86;   // ~6 bytes above bipolar zero — upper bound of default idle range, reproduces phase-2's 0.05 deadband
    public static final int DEF_THROTTLE_FULLDYN    = 0x3a;   // lever UP per inventory

    public static final int DEF_AUTOBRAKE_RELEASED = 0xb7;
    public static final int DEF_AUTOBRAKE_SUP      = 0xa0;   // mid placeholder; not captured (closer to RELEASED)
    public static final int DEF_AUTOBRAKE_CS       = 0x80;   // mid placeholder; not captured (closer to EMG)
    public static final int DEF_AUTOBRAKE_EMG      = 0x4f;

    public static final int DEF_INDEPBRAKE_FULLRELEASE      = 0xc0;
    public static final int DEF_INDEPBRAKE_FULLAPP          = 0x41;
    public static final int DEF_INDEPBRAKE_BAILOFF_REST     = 0x96;
    public static final int DEF_INDEPBRAKE_BAILOFF_PRESSED  = 0xd0;

    public static final int DEF_WIPER_OFF          = 0x66;
    public static final int DEF_WIPER_SLOW         = 0x90;   // mid placeholder; not captured
    public static final int DEF_WIPER_FULL         = 0xba;

    public static final int DEF_LIGHTS_OFF         = 0x52;
    public static final int DEF_LIGHTS_DIM         = 0x77;   // mid placeholder; not captured
    public static final int DEF_LIGHTS_FULL        = 0x9c;

    /** Mutable POJO for the Reverser detents. */
    public static final class ReverserCal {
        public Integer forward;
        public Integer neutral;
        public Integer reverse;
    }

    /**
     * Mutable POJO for the Throttle / Dyn Brake lever.
     * <p>
     * Idle is captured as a {@code [idleLow, idleHigh]} byte range rather
     * than a single point, so the lever's mechanical rest slop and the
     * operator's preferred "fudge zone" can be modelled directly on the
     * calibration bar. Bytes within the captured idle range are treated
     * as no-movement; bytes above {@code idleHigh} accelerate the loco
     * toward {@code fullThrottle}; bytes below {@code idleLow} are also
     * no-movement today (the dynamic-brake side is not yet wired —
     * phase 4+).
     */
    public static final class ThrottleCal {
        public Integer fullThrottle;
        public Integer idleLow;
        public Integer idleHigh;
        public Integer fullDynBrake;
    }

    /** Mutable POJO for the Auto Brake lever's named positions. */
    public static final class AutoBrakeCal {
        public Integer released;
        public Integer sup;
        public Integer cs;
        public Integer emg;
    }

    /**
     * Mutable POJO for the Independent Brake (bytes 3 + 4).
     * <p>
     * Byte 3 carries the continuous up/down lever travel between
     * full release and full application. Byte 4 carries a momentary
     * bail-off switch ("press the lever right") that activates
     * independently of byte-3 position; the reported byte value is
     * pressure-graded but JMRI treats it as binary against a midpoint
     * threshold between the Rest and Pressed calibration values.
     */
    public static final class IndepBrakeCal {
        public Integer fullRelease;       // byte 3
        public Integer fullApplication;   // byte 3
        public Integer bailoffRest;       // byte 4 — typical value with bail-off switch released
        public Integer bailoffPressed;    // byte 4 — typical value with bail-off switch pressed
    }

    /** Mutable POJO for the Wiper rotary. */
    public static final class WiperCal {
        public Integer off;
        public Integer slow;
        public Integer full;
    }

    /** Mutable POJO for the Lights rotary. */
    public static final class LightsCal {
        public Integer off;
        public Integer dim;
        public Integer full;
    }

    private final ReverserCal   reverser   = new ReverserCal();
    private final ThrottleCal   throttle   = new ThrottleCal();
    private final AutoBrakeCal  autoBrake  = new AutoBrakeCal();
    private final IndepBrakeCal indepBrake = new IndepBrakeCal();
    private final WiperCal      wiper      = new WiperCal();
    private final LightsCal     lights     = new LightsCal();
    private final SemiRealisticSettings semiRealistic = new SemiRealisticSettings();

    public ReverserCal   reverser()   { return reverser; }
    public ThrottleCal   throttle()   { return throttle; }
    public AutoBrakeCal  autoBrake()  { return autoBrake; }
    public IndepBrakeCal indepBrake() { return indepBrake; }
    public WiperCal      wiper()      { return wiper; }
    public LightsCal     lights()     { return lights; }
    public SemiRealisticSettings semiRealistic() { return semiRealistic; }

    // -------- effective getters: calibrated value or default --------

    public int reverserForward() { return reverser.forward != null ? reverser.forward : DEF_REVERSER_FORWARD; }
    public int reverserNeutral() { return reverser.neutral != null ? reverser.neutral : DEF_REVERSER_NEUTRAL; }
    public int reverserReverse() { return reverser.reverse != null ? reverser.reverse : DEF_REVERSER_REVERSE; }

    public int throttleFull()     { return throttle.fullThrottle != null ? throttle.fullThrottle : DEF_THROTTLE_FULL; }
    public int throttleIdleLow()  { return throttle.idleLow      != null ? throttle.idleLow      : DEF_THROTTLE_IDLE_LOW; }
    public int throttleIdleHigh() { return throttle.idleHigh     != null ? throttle.idleHigh     : DEF_THROTTLE_IDLE_HIGH; }
    public int throttleFullDyn()  { return throttle.fullDynBrake != null ? throttle.fullDynBrake : DEF_THROTTLE_FULLDYN; }

    public int lightsOff()       { return lights.off != null ? lights.off : DEF_LIGHTS_OFF; }
    public int lightsFull()      { return lights.full != null ? lights.full : DEF_LIGHTS_FULL; }
    @CheckForNull public Integer lightsDim() { return lights.dim; }

    public int indepBrakeBailoffRest()    { return indepBrake.bailoffRest    != null ? indepBrake.bailoffRest    : DEF_INDEPBRAKE_BAILOFF_REST; }
    public int indepBrakeBailoffPressed() { return indepBrake.bailoffPressed != null ? indepBrake.bailoffPressed : DEF_INDEPBRAKE_BAILOFF_PRESSED; }

    /**
     * Threshold byte for the Independent Brake bail-off switch. byte 4 &gt;= this
     * value means bail-off is active; below means released. Computed as the
     * midpoint between the calibrated Rest and Pressed reference values.
     * Phase 3 stores this for phase 4+ wiring; Axis 4 dispatch is still
     * log-only at the moment.
     */
    public int bailoffThreshold() {
        return (indepBrakeBailoffRest() + indepBrakeBailoffPressed()) / 2;
    }

    // -------- derived thresholds for propertyChange dispatch --------

    /**
     * Threshold (in post-{@code (256-vInt)/256} value space) above which
     * the reverser is treated as "forward" (loco direction = forward).
     * <p>
     * Forward maps to a low byte → high value, so the threshold sits
     * <strong>between</strong> Neutral (centre value) and Forward (high value).
     */
    public double reverserForwardThreshold() {
        double fwd = (256 - reverserForward()) / 256.0;
        double neu = (256 - reverserNeutral()) / 256.0;
        return (fwd + neu) / 2.0;
    }

    /**
     * Threshold below which the reverser is treated as "reverse"
     * (loco direction = reverse). Reverse maps to a high byte → low value.
     */
    public double reverserReverseThreshold() {
        double rev = (256 - reverserReverse()) / 256.0;
        double neu = (256 - reverserNeutral()) / 256.0;
        return (neu + rev) / 2.0;
    }

    /**
     * Throttle's lower pin in {@code value} space — the bipolar value at
     * the upper edge of the calibrated idle range. Byte values within
     * the {@code [idleLow, idleHigh]} idle window all map to fraction = 0
     * (loco at speed 0); bytes above {@code idleHigh} accelerate the
     * loco toward {@link #throttleMax()}.
     * <p>
     * Throttle uses the bipolar transform {@code 1 - 2*(256-vInt)/256}.
     * Lever DOWN (toward THROTTLE label, byte ~0xdd) yields a high
     * positive value (~0.73); idle (mid byte) yields ~0; lever UP
     * yields negative.
     */
    public double throttleMin() {
        int idleHigh = throttleIdleHigh();
        return (2.0 * idleHigh - 256.0) / 256.0;
    }

    /**
     * Throttle's upper pin in {@code value} space — full Throttle byte
     * after the bipolar transform. Above this the loco runs at speed 1.0.
     */
    public double throttleMax() {
        return (2.0 * throttleFull() - 256.0) / 256.0;
    }

    /**
     * Lights threshold in {@code value} space.
     * F0 is off when the live value &gt;= this threshold (i.e. the rotary
     * is at OFF — high byte / high value); F0 is on when the live value
     * is below this threshold (i.e. rotary at Dim or Full).
     * <p>
     * If Dim has been calibrated, the threshold sits between OFF and Dim.
     * Otherwise it sits between OFF and Full (matching phase 2's hardcoded
     * 0.6 by default).
     */
    public double lightsThreshold() {
        double off = (256 - lightsOff()) / 256.0;
        Integer dim = lightsDim();
        if (dim != null) {
            return (off + (256 - dim) / 256.0) / 2.0;
        }
        double full = (256 - lightsFull()) / 256.0;
        return (off + full) / 2.0;
    }

    // -------- persistence removed (TASK-053) --------
    // File-based save/loadOrDefault/getDefaultFile methods have been
    // removed. Persistence is now handled exclusively by
    // RailDriverPreferencesManager via AuxiliaryConfiguration.

    /**
     * Copies every field from {@code other} into this instance. Used by
     * the unified Settings frame to push pending in-window edits into a
     * fresh persistence target on Save/Apply, and to reload tab state
     * from disk after a successful round-trip.
     */
    /**
     * Copies the analog-control calibration fields (reverser, throttle,
     * auto-brake, indep brake, wiper, lights) from {@code other} into this
     * instance, but leaves {@link #semiRealistic} unchanged. Used by
     * {@code CalibrationTabPanel.validateAndApplyTo} so that a Save from
     * the unified Settings frame does not overwrite the Settings-tab
     * subtree the {@code SemiRealisticSettingsPanel.validateAndApplyTo}
     * call already wrote a moment earlier.
     */
    public void copyCalibrationFieldsFrom(@Nonnull RailDriverCalibration other) {
        this.reverser.forward = other.reverser.forward;
        this.reverser.neutral = other.reverser.neutral;
        this.reverser.reverse = other.reverser.reverse;
        this.throttle.fullThrottle = other.throttle.fullThrottle;
        this.throttle.idleLow      = other.throttle.idleLow;
        this.throttle.idleHigh     = other.throttle.idleHigh;
        this.throttle.fullDynBrake = other.throttle.fullDynBrake;
        this.autoBrake.released = other.autoBrake.released;
        this.autoBrake.sup      = other.autoBrake.sup;
        this.autoBrake.cs       = other.autoBrake.cs;
        this.autoBrake.emg      = other.autoBrake.emg;
        this.indepBrake.fullRelease     = other.indepBrake.fullRelease;
        this.indepBrake.fullApplication = other.indepBrake.fullApplication;
        this.indepBrake.bailoffRest     = other.indepBrake.bailoffRest;
        this.indepBrake.bailoffPressed  = other.indepBrake.bailoffPressed;
        this.wiper.off  = other.wiper.off;
        this.wiper.slow = other.wiper.slow;
        this.wiper.full = other.wiper.full;
        this.lights.off  = other.lights.off;
        this.lights.dim  = other.lights.dim;
        this.lights.full = other.lights.full;
    }

    public void copyFrom(@Nonnull RailDriverCalibration other) {
        copyCalibrationFieldsFrom(other);
        this.semiRealistic.copyFrom(other.semiRealistic);
    }

    /**
     * Resets every field to {@code null} so all derived getters fall back
     * to defaults. Does not touch on-disk state.
     */
    public void resetToDefaults() {
        reverser.forward = null;
        reverser.neutral = null;
        reverser.reverse = null;
        throttle.fullThrottle = null;
        throttle.idleLow = null;
        throttle.idleHigh = null;
        throttle.fullDynBrake = null;
        autoBrake.released = null;
        autoBrake.sup = null;
        autoBrake.cs = null;
        autoBrake.emg = null;
        indepBrake.fullRelease = null;
        indepBrake.fullApplication = null;
        indepBrake.bailoffRest = null;
        indepBrake.bailoffPressed = null;
        wiper.off = null;
        wiper.slow = null;
        wiper.full = null;
        lights.off = null;
        lights.dim = null;
        lights.full = null;
        semiRealistic.resetToDefaults();
    }
}
