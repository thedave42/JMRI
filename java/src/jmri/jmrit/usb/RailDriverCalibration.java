package jmri.jmrit.usb;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import jmri.profile.Profile;
import jmri.profile.ProfileManager;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-profile calibration data for the RailDriver Modern Desktop's analog controls.
 * <p>
 * Default values match {@code docs/rpi-raildriver/control-inventory.md}. Each
 * captured position is stored as a boxed Integer (byte 0..255) or {@code null}
 * meaning "not yet captured — fall back to the default".
 * <p>
 * Persistence is a self-managed XML file at
 * {@code <profile>/profile/raildriver-calibration.xml}, read with JDOM2. See
 * {@code docs/rpi-raildriver/plan-impl-phase3.md} §2.2 for the schema.
 *
 * @author the Dave (phase 3)
 */
public final class RailDriverCalibration {

    private static final String FILE_NAME = "raildriver-calibration.xml";
    // Schema version bumped to "2" for the semi-realistic-throttle feature
    // (see docs/rpi-raildriver/semi-realistic-throttle-plan.md). Stage 1 still
    // writes only the existing six children and tolerates absence of the new
    // <semiRealistic> subtree on read; stage 2 adds parsing/serialisation of
    // that subtree. Pre-existing version="1" files load cleanly because the
    // loader does not branch on version.
    private static final String SCHEMA_VERSION = "2";

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

    // -------- persistence --------

    /**
     * Returns the canonical per-profile calibration file location, or
     * {@code null} if no profile is active.
     */
    @CheckForNull
    public static File getDefaultFile() {
        Profile profile = ProfileManager.getDefault().getActiveProfile();
        if (profile == null) {
            return null;
        }
        return new File(new File(profile.getPath(), Profile.PROFILE), FILE_NAME);
    }

    /**
     * Loads calibration from the given file, falling back to defaults for
     * any field that is missing, empty, or unparseable. Always returns a
     * non-null instance.
     *
     * @param file the XML file to read; may be {@code null} (returns defaults)
     */
    @Nonnull
    public static RailDriverCalibration loadOrDefault(@CheckForNull File file) {
        RailDriverCalibration cal = new RailDriverCalibration();
        if (file == null || !file.exists() || !file.canRead()) {
            return cal;
        }
        try {
            Document doc = new SAXBuilder().build(file);
            Element root = doc.getRootElement();
            // Tolerate any version (currently "1" or "2") or attribute absence:
            // unknown children — including a future <semiRealistic> subtree
            // populated by stage 2 of the semi-realistic throttle plan — are
            // silently ignored at this stage so a forward-saved file still
            // round-trips its known fields.
            populateReverser(cal.reverser,     root.getChild("reverser"));
            populateThrottle(cal.throttle,     root.getChild("throttle"));
            populateAutoBrake(cal.autoBrake,   root.getChild("autoBrake"));
            populateIndepBrake(cal.indepBrake, root.getChild("indepBrake"));
            populateWiper(cal.wiper,           root.getChild("wiper"));
            populateLights(cal.lights,         root.getChild("lights"));
            cal.semiRealistic.loadFrom(         root.getChild("semiRealistic"));
        } catch (IOException | JDOMException ex) {
            log.warn("Failed to parse RailDriver calibration file '{}'; falling back to defaults", file, ex);
        }
        return cal;
    }

    /**
     * Persists this calibration to the given file, creating parent
     * directories as needed.
     */
    public void save(@Nonnull File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create parent directory: " + parent);
        }
        Element root = new Element("raildriver-calibration");
        root.setAttribute("version", SCHEMA_VERSION);
        root.addContent(buildReverser(reverser));
        root.addContent(buildThrottle(throttle));
        root.addContent(buildAutoBrake(autoBrake));
        root.addContent(buildIndepBrake(indepBrake));
        root.addContent(buildWiper(wiper));
        root.addContent(buildLights(lights));
        root.addContent(semiRealistic.writeTo());
        Document doc = new Document(root);
        XMLOutputter fmt = new XMLOutputter(Format.getPrettyFormat()
                .setLineSeparator(System.lineSeparator()));
        try (FileWriter fw = new FileWriter(file)) {
            fmt.output(doc, fw);
        }
    }

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

    // -------- XML helpers --------

    private static void populateReverser(ReverserCal r, @CheckForNull Element e) {
        if (e == null) return;
        r.forward = readInt(e, "forward");
        r.neutral = readInt(e, "neutral");
        r.reverse = readInt(e, "reverse");
    }

    private static void populateThrottle(ThrottleCal t, @CheckForNull Element e) {
        if (e == null) return;
        t.fullThrottle = readInt(e, "fullThrottle");
        t.idleLow      = readInt(e, "idleLow");
        t.idleHigh     = readInt(e, "idleHigh");
        t.fullDynBrake = readInt(e, "fullDynBrake");
    }

    private static void populateAutoBrake(AutoBrakeCal a, @CheckForNull Element e) {
        if (e == null) return;
        a.released = readInt(e, "released");
        a.sup      = readInt(e, "sup");
        a.cs       = readInt(e, "cs");
        a.emg      = readInt(e, "emg");
    }

    private static void populateIndepBrake(IndepBrakeCal i, @CheckForNull Element e) {
        if (e == null) return;
        i.fullRelease     = readInt(e, "fullRelease");
        i.fullApplication = readInt(e, "fullApplication");
        i.bailoffRest     = readInt(e, "bailoffRest");
        i.bailoffPressed  = readInt(e, "bailoffPressed");
    }

    private static void populateWiper(WiperCal w, @CheckForNull Element e) {
        if (e == null) return;
        w.off  = readInt(e, "off");
        w.slow = readInt(e, "slow");
        w.full = readInt(e, "full");
    }

    private static void populateLights(LightsCal l, @CheckForNull Element e) {
        if (e == null) return;
        l.off  = readInt(e, "off");
        l.dim  = readInt(e, "dim");
        l.full = readInt(e, "full");
    }

    private static Element buildReverser(ReverserCal r) {
        Element e = new Element("reverser");
        e.addContent(intElement("forward", r.forward));
        e.addContent(intElement("neutral", r.neutral));
        e.addContent(intElement("reverse", r.reverse));
        return e;
    }

    private static Element buildThrottle(ThrottleCal t) {
        Element e = new Element("throttle");
        e.addContent(intElement("fullThrottle", t.fullThrottle));
        e.addContent(intElement("idleLow",      t.idleLow));
        e.addContent(intElement("idleHigh",     t.idleHigh));
        e.addContent(intElement("fullDynBrake", t.fullDynBrake));
        return e;
    }

    private static Element buildAutoBrake(AutoBrakeCal a) {
        Element e = new Element("autoBrake");
        e.addContent(intElement("released", a.released));
        e.addContent(intElement("sup",      a.sup));
        e.addContent(intElement("cs",       a.cs));
        e.addContent(intElement("emg",      a.emg));
        return e;
    }

    private static Element buildIndepBrake(IndepBrakeCal i) {
        Element e = new Element("indepBrake");
        e.addContent(intElement("fullRelease",     i.fullRelease));
        e.addContent(intElement("fullApplication", i.fullApplication));
        e.addContent(intElement("bailoffRest",     i.bailoffRest));
        e.addContent(intElement("bailoffPressed", i.bailoffPressed));
        return e;
    }

    private static Element buildWiper(WiperCal w) {
        Element e = new Element("wiper");
        e.addContent(intElement("off",  w.off));
        e.addContent(intElement("slow", w.slow));
        e.addContent(intElement("full", w.full));
        return e;
    }

    private static Element buildLights(LightsCal l) {
        Element e = new Element("lights");
        e.addContent(intElement("off",  l.off));
        e.addContent(intElement("dim",  l.dim));
        e.addContent(intElement("full", l.full));
        return e;
    }

    @CheckForNull
    private static Integer readInt(Element parent, String childName) {
        Element child = parent.getChild(childName);
        if (child == null) return null;
        String text = child.getTextTrim();
        if (text == null || text.isEmpty()) return null;
        try {
            int v = Integer.parseInt(text);
            if (v < 0 || v > 255) {
                log.warn("RailDriver calibration {} value {} out of byte range; ignoring", childName, v);
                return null;
            }
            return v;
        } catch (NumberFormatException ex) {
            log.warn("RailDriver calibration {} value '{}' is not a valid integer; ignoring", childName, text);
            return null;
        }
    }

    @CheckForNull
    private static Double readDouble(Element parent, String childName) {
        Element child = parent.getChild(childName);
        if (child == null) return null;
        String text = child.getTextTrim();
        if (text == null || text.isEmpty()) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            log.warn("RailDriver calibration {} value '{}' is not a valid double; ignoring", childName, text);
            return null;
        }
    }

    private static Element intElement(String name, @CheckForNull Integer value) {
        Element e = new Element(name);
        if (value != null) {
            e.setText(value.toString());
        }
        return e;
    }

    private static Element doubleElement(String name, @CheckForNull Double value) {
        Element e = new Element(name);
        if (value != null) {
            e.setText(value.toString());
        }
        return e;
    }

    private static final Logger log = LoggerFactory.getLogger(RailDriverCalibration.class);
}
