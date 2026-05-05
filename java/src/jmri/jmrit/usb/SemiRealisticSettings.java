package jmri.jmrit.usb;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.jdom2.Element;

/**
 * Persisted-and-live settings POJO for the semi-realistic throttle engine.
 * Mirrors the structure of {@link RailDriverCalibration} for easy
 * round-tripping through the Settings-tab dirty-tracking model.
 * <p>
 * The {@code persistedEnabled} field is the value last loaded from XML or
 * last saved by the Settings tab; {@code liveEnabled} is the value the
 * engine and Jynstrument act on right now. Per §2.3, these are deliberately
 * decoupled — the Settings tab edits {@code persistedEnabled}, the
 * Jynstrument toggles {@code liveEnabled}.
 * <p>
 * <b>Wall-clock-only model (§1.0).</b> Every coefficient below is in
 * wall-clock m/s² (or 1/s, 1/m) decel/accel space. There is no mass, no
 * force, no scale factor anywhere. The Davis-equation <em>shape</em>
 * (static + linear + quadratic) is preserved as the velocity-shape of
 * coast resistance, but the coefficients are operator-feel-tuned for the
 * desired wall-clock experience, NOT prototype Davis values.
 * <p>
 * Pre-wall-clock-refactor stage-2 XML files (with prototype-frame
 * elements like {@code <locoMassKg>}, {@code <rollingResistanceCoeff>},
 * {@code <physicsTimeScale>}, etc.) load with all wall-clock fields
 * defaulting to scenario values; the legacy elements are silently
 * ignored. Operators are expected to delete development calibration XMLs
 * before testing the wall-clock build (no migration is performed).
 * <p>
 * See {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.5.
 */
public final class SemiRealisticSettings {

    /** ESU decoder-brake function defaults (§2.5 schema). */
    public static final int   DEFAULT_ESU_LOW_FUNCTION  = 4;
    public static final int   DEFAULT_ESU_MID_FUNCTION  = 5;
    public static final int   DEFAULT_ESU_HIGH_FUNCTION = 6;
    public static final int   DEFAULT_ESU_LOW_THRESH    = 30;
    public static final int   DEFAULT_ESU_MID_THRESH    = 60;
    public static final int   DEFAULT_ESU_HIGH_THRESH   = 98;

    /** Dynamic-brake taper threshold (mph prototype) — scenario-independent
     *  per §2.4.1. */
    public static final float DEFAULT_DYN_BRAKE_V_MIN_MPH = 5f;

    /** Decoder brake passthrough mode. ESU is wired in stage 6. */
    public enum DecoderBrakeMode {
        NONE("none"), ESU("ESU");
        private final String token;
        DecoderBrakeMode(String token) { this.token = token; }
        public String token() { return token; }
        public static DecoderBrakeMode fromToken(String s) {
            if (s != null) {
                for (DecoderBrakeMode m : values()) {
                    if (m.token.equalsIgnoreCase(s)) return m;
                }
            }
            return NONE;
        }
    }

    /** Persisted enable state — what's on disk and what the Settings tab
     *  checkbox shows. */
    public boolean persistedEnabled = false;

    /** Live enable state — what the engine and Jynstrument act on. Initialised
     *  from {@link #persistedEnabled} on load. Not persisted to XML. */
    public boolean liveEnabled = false;

    /** Selected scenario. {@link LoadScenario#LIGHT_ENGINE} by default. */
    public LoadScenario scenario = LoadScenario.LIGHT_ENGINE;

    // — Drive coefficients (wall-clock m/s² and m/s prototype) —
    /** Wall-clock m/s² acceleration at v=0 under full throttle. */
    public float maxAccelAtRestMs2;
    /** m/s prototype above which a_drive falls as vCorner/v. */
    public float vCornerMps;
    /** 0..100 % multiplier on the entire drive curve. */
    public float driverPowerPercent;
    /** m/s prototype design top speed (linear-fallback denominator,
     *  steam-curve denominator, lever-derived vTarget anchor). */
    public float designTopSpeedMps;
    /** Steam tractive-effort fall-off flag. */
    public boolean steam;

    // — Davis-shape coast resistance coefficients (wall-clock units) —
    /** m/s² constant decel (Davis A; dominates near halt). */
    public float resistStaticMs2;
    /** 1/s — decel = b·v (Davis B). Often 0. */
    public float resistLinearPerSec;
    /** 1/m — decel = c·v² (Davis C; aerodynamic-shape, dominates at speed). */
    public float resistQuadPerMeter;

    // — Operator-controlled brake decels (wall-clock m/s²) —
    public float brakeMaxDecelMs2;
    public float airBrakeMaxDecelMs2;
    public float dynBrakeMaxDecelMs2;
    /** 0..1 dilution coefficient — captures that real dyn brake doesn't
     *  propagate through trainline air; smaller in heavier scenarios. */
    public float dynBrakeMassFraction;
    /** mph prototype taper threshold; below this, dyn brake scales linearly to 0. */
    public float dynBrakeVMinMph = DEFAULT_DYN_BRAKE_V_MIN_MPH;

    public DecoderBrakeMode decoderBrakeMode = DecoderBrakeMode.NONE;
    public int esuLowFunction  = DEFAULT_ESU_LOW_FUNCTION;
    public int esuMidFunction  = DEFAULT_ESU_MID_FUNCTION;
    public int esuHighFunction = DEFAULT_ESU_HIGH_FUNCTION;
    public int esuLowThreshold  = DEFAULT_ESU_LOW_THRESH;
    public int esuMidThreshold  = DEFAULT_ESU_MID_THRESH;
    public int esuHighThreshold = DEFAULT_ESU_HIGH_THRESH;

    public SemiRealisticSettings() {
        resetToDefaults();
    }

    /** Resets every field to the active scenario's defaults (Light engine
     *  out of the box). Does not touch on-disk state. */
    public void resetToDefaults() {
        persistedEnabled = false;
        liveEnabled = false;
        scenario = LoadScenario.LIGHT_ENGINE;
        applyScenarioDefaults(scenario);
        dynBrakeVMinMph = DEFAULT_DYN_BRAKE_V_MIN_MPH;
        decoderBrakeMode = DecoderBrakeMode.NONE;
        esuLowFunction = DEFAULT_ESU_LOW_FUNCTION;
        esuMidFunction = DEFAULT_ESU_MID_FUNCTION;
        esuHighFunction = DEFAULT_ESU_HIGH_FUNCTION;
        esuLowThreshold = DEFAULT_ESU_LOW_THRESH;
        esuMidThreshold = DEFAULT_ESU_MID_THRESH;
        esuHighThreshold = DEFAULT_ESU_HIGH_THRESH;
    }

    /** Snaps every per-coefficient field to the given scenario's tuned
     *  defaults. Called when the operator switches scenarios on the
     *  Settings tab so the per-coefficient rows reflect the new preset. */
    public void applyScenarioDefaults(@Nonnull LoadScenario s) {
        maxAccelAtRestMs2     = s.maxAccelAtRestMs2();
        vCornerMps            = s.vCornerMps();
        driverPowerPercent    = s.driverPowerPct() * 100f;
        designTopSpeedMps     = s.designTopSpeedMps();
        steam                 = s.steamPowerCurve();
        resistStaticMs2       = s.resistStaticMs2();
        resistLinearPerSec    = s.resistLinearPerSec();
        resistQuadPerMeter    = s.resistQuadPerMeter();
        brakeMaxDecelMs2      = s.brakeMaxDecelMs2();
        airBrakeMaxDecelMs2   = s.airBrakeMaxDecelMs2();
        dynBrakeMaxDecelMs2   = s.dynBrakeMaxDecelMs2();
        dynBrakeMassFraction  = s.dynBrakeMassFraction();
    }

    public void copyFrom(@Nonnull SemiRealisticSettings other) {
        this.persistedEnabled = other.persistedEnabled;
        this.liveEnabled = other.liveEnabled;
        this.scenario = other.scenario;
        this.maxAccelAtRestMs2    = other.maxAccelAtRestMs2;
        this.vCornerMps           = other.vCornerMps;
        this.driverPowerPercent   = other.driverPowerPercent;
        this.designTopSpeedMps    = other.designTopSpeedMps;
        this.steam                = other.steam;
        this.resistStaticMs2      = other.resistStaticMs2;
        this.resistLinearPerSec   = other.resistLinearPerSec;
        this.resistQuadPerMeter   = other.resistQuadPerMeter;
        this.brakeMaxDecelMs2     = other.brakeMaxDecelMs2;
        this.airBrakeMaxDecelMs2  = other.airBrakeMaxDecelMs2;
        this.dynBrakeMaxDecelMs2  = other.dynBrakeMaxDecelMs2;
        this.dynBrakeMassFraction = other.dynBrakeMassFraction;
        this.dynBrakeVMinMph      = other.dynBrakeVMinMph;
        this.decoderBrakeMode = other.decoderBrakeMode;
        this.esuLowFunction = other.esuLowFunction;
        this.esuMidFunction = other.esuMidFunction;
        this.esuHighFunction = other.esuHighFunction;
        this.esuLowThreshold = other.esuLowThreshold;
        this.esuMidThreshold = other.esuMidThreshold;
        this.esuHighThreshold = other.esuHighThreshold;
    }

    /** Populates this instance from a {@code <semiRealistic>} XML element.
     *  Missing or empty children fall back to the active scenario's defaults
     *  (Light engine when no scenario element is present). Legacy
     *  prototype-frame elements ({@code locoMassKg}, {@code locoPowerKw},
     *  {@code locoTractiveEffortKn}, {@code additionalWeightTonnes},
     *  {@code rollingResistanceCoeff}, {@code rollingResistanceStatic},
     *  {@code rollingResistanceLinear}, {@code aerodynamicDragCoeff},
     *  {@code physicsTimeScale}) are silently ignored — no migration. */
    public void loadFrom(@CheckForNull Element semiRealistic) {
        if (semiRealistic == null) {
            return;
        }
        Boolean enabled = readBool(semiRealistic, "enabled");
        if (enabled != null) {
            persistedEnabled = enabled;
            liveEnabled = enabled;
        }
        String scenarioText = readText(semiRealistic, "scenario");
        if (scenarioText != null) {
            scenario = LoadScenario.fromDisplayName(scenarioText);
        }
        // Start from the (possibly newly-loaded) scenario's defaults so any
        // missing children fall back to scenario values. Children that ARE
        // present overwrite the defaults below.
        applyScenarioDefaults(scenario);

        Float f;
        if ((f = readFloat(semiRealistic, "maxAccelAtRestMs2"))    != null) maxAccelAtRestMs2    = f;
        if ((f = readFloat(semiRealistic, "vCornerMps"))           != null) vCornerMps           = f;
        if ((f = readFloat(semiRealistic, "driverPowerPercent"))   != null) driverPowerPercent   = f;
        if ((f = readFloat(semiRealistic, "designTopSpeedMps"))    != null) designTopSpeedMps    = f;
        Boolean steamEl = readBool(semiRealistic, "steam");
        if (steamEl != null) steam = steamEl;
        if ((f = readFloat(semiRealistic, "resistStaticMs2"))      != null) resistStaticMs2      = f;
        if ((f = readFloat(semiRealistic, "resistLinearPerSec"))   != null) resistLinearPerSec   = f;
        if ((f = readFloat(semiRealistic, "resistQuadPerMeter"))   != null) resistQuadPerMeter   = f;
        if ((f = readFloat(semiRealistic, "brakeMaxDecelMs2"))     != null) brakeMaxDecelMs2     = f;
        if ((f = readFloat(semiRealistic, "airBrakeMaxDecelMs2"))  != null) airBrakeMaxDecelMs2  = f;
        if ((f = readFloat(semiRealistic, "dynBrakeMaxDecelMs2"))  != null) dynBrakeMaxDecelMs2  = f;
        if ((f = readFloat(semiRealistic, "dynBrakeMassFraction")) != null) dynBrakeMassFraction = f;
        if ((f = readFloat(semiRealistic, "dynBrakeVMinMph"))      != null) dynBrakeVMinMph      = f;

        String mode = readText(semiRealistic, "decoderBrakeMode");
        if (mode != null) decoderBrakeMode = DecoderBrakeMode.fromToken(mode);
        Integer i;
        if ((i = readInt(semiRealistic, "esuLowFunction"))  != null) esuLowFunction  = i;
        if ((i = readInt(semiRealistic, "esuMidFunction"))  != null) esuMidFunction  = i;
        if ((i = readInt(semiRealistic, "esuHighFunction")) != null) esuHighFunction = i;
        if ((i = readInt(semiRealistic, "esuLowThreshold"))  != null) esuLowThreshold  = i;
        if ((i = readInt(semiRealistic, "esuMidThreshold"))  != null) esuMidThreshold  = i;
        if ((i = readInt(semiRealistic, "esuHighThreshold")) != null) esuHighThreshold = i;
    }

    /** Writes this instance as a {@code <semiRealistic>} XML element. */
    public Element writeTo() {
        Element e = new Element("semiRealistic");
        e.addContent(child("enabled", Boolean.toString(persistedEnabled)));
        e.addContent(child("scenario", scenario.displayName()));
        e.addContent(child("maxAccelAtRestMs2",   Float.toString(maxAccelAtRestMs2)));
        e.addContent(child("vCornerMps",          Float.toString(vCornerMps)));
        e.addContent(child("driverPowerPercent",  Float.toString(driverPowerPercent)));
        e.addContent(child("designTopSpeedMps",   Float.toString(designTopSpeedMps)));
        e.addContent(child("steam",               Boolean.toString(steam)));
        e.addContent(child("resistStaticMs2",     Float.toString(resistStaticMs2)));
        e.addContent(child("resistLinearPerSec",  Float.toString(resistLinearPerSec)));
        e.addContent(child("resistQuadPerMeter",  Float.toString(resistQuadPerMeter)));
        e.addContent(child("brakeMaxDecelMs2",    Float.toString(brakeMaxDecelMs2)));
        e.addContent(child("airBrakeMaxDecelMs2", Float.toString(airBrakeMaxDecelMs2)));
        e.addContent(child("dynBrakeMaxDecelMs2", Float.toString(dynBrakeMaxDecelMs2)));
        e.addContent(child("dynBrakeMassFraction", Float.toString(dynBrakeMassFraction)));
        e.addContent(child("dynBrakeVMinMph",     Float.toString(dynBrakeVMinMph)));
        e.addContent(child("decoderBrakeMode", decoderBrakeMode.token()));
        e.addContent(child("esuLowFunction", Integer.toString(esuLowFunction)));
        e.addContent(child("esuMidFunction", Integer.toString(esuMidFunction)));
        e.addContent(child("esuHighFunction", Integer.toString(esuHighFunction)));
        e.addContent(child("esuLowThreshold", Integer.toString(esuLowThreshold)));
        e.addContent(child("esuMidThreshold", Integer.toString(esuMidThreshold)));
        e.addContent(child("esuHighThreshold", Integer.toString(esuHighThreshold)));
        return e;
    }

    // -------- XML helpers --------

    private static Element child(String name, String text) {
        Element c = new Element(name);
        c.setText(text);
        return c;
    }

    @CheckForNull
    private static String readText(Element parent, String childName) {
        Element c = parent.getChild(childName);
        if (c == null) return null;
        String t = c.getTextTrim();
        return (t == null || t.isEmpty()) ? null : t;
    }

    @CheckForNull
    private static Boolean readBool(Element parent, String childName) {
        String t = readText(parent, childName);
        if (t == null) return null;
        return Boolean.parseBoolean(t);
    }

    @CheckForNull
    private static Float readFloat(Element parent, String childName) {
        String t = readText(parent, childName);
        if (t == null) return null;
        try { return Float.parseFloat(t); }
        catch (NumberFormatException ex) { return null; }
    }

    @CheckForNull
    private static Integer readInt(Element parent, String childName) {
        String t = readText(parent, childName);
        if (t == null) return null;
        try { return Integer.parseInt(t); }
        catch (NumberFormatException ex) { return null; }
    }
}
