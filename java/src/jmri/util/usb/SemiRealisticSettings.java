package jmri.util.usb;

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
 * Per-loco physics overrides ({@link #locoMassKg}, {@link #locoPowerKw},
 * {@link #locoTractiveEffortKn}) are boxed Float values: {@code null} means
 * "auto" (use roster value if {@code > 0}, else scenario default per
 * §2.4.1). Numeric values are explicit overrides (only meaningful when the
 * active scenario is {@link LoadScenario#CUSTOM}, but always persisted so
 * the operator can switch back to Custom and find their values intact).
 * <p>
 * See {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.5.
 */
public final class SemiRealisticSettings {

    /** Physical-decel-rate constants (§2.4.1, scenario-independent).
     *  Brake values are in WALL-CLOCK m/s² (operator-perceived rate per
     *  plan §1.0). Defaults chosen so brakes dominate rolling resistance
     *  at typical layout scales — e.g. at N (physicsTimeScale = 160) the
     *  coast term is ~3.14 m/s² wall-clock, so default mech brake at
     *  4.0 wins by ~25% standalone and a stacked mech+air gives a hard
     *  ~6 s 80-mph → 0 stop. */
    public static final float DEFAULT_BRAKE_MAX_DECEL      = 4.0f;
    public static final float DEFAULT_AIR_BRAKE_MAX_DECEL  = 6.0f;
    public static final float DEFAULT_DYN_BRAKE_MAX_DECEL  = 1.6f;
    public static final float DEFAULT_DYN_BRAKE_V_MIN_MPH  = 5f;
    public static final float DEFAULT_ROLLING_RESISTANCE   = 0.002f;
    public static final float DEFAULT_DRIVER_POWER_PCT     = 100f;
    public static final float DEFAULT_ADDITIONAL_TONNES    = 0f;

    /** ESU decoder-brake function defaults (§2.5 schema). */
    public static final int   DEFAULT_ESU_LOW_FUNCTION  = 4;
    public static final int   DEFAULT_ESU_MID_FUNCTION  = 5;
    public static final int   DEFAULT_ESU_HIGH_FUNCTION = 6;
    public static final int   DEFAULT_ESU_LOW_THRESH    = 30;
    public static final int   DEFAULT_ESU_MID_THRESH    = 60;
    public static final int   DEFAULT_ESU_HIGH_THRESH   = 98;

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

    /** Selected scenario. {@link LoadScenario#LIGHT_ENGINE} by default — the
     *  empty-roster fallback per §2.4.1. */
    public LoadScenario scenario = LoadScenario.LIGHT_ENGINE;

    /** Loco mass override in kg. {@code null} = "auto" (roster value if
     *  populated, else scenario default). */
    @CheckForNull public Float locoMassKg = null;
    /** Loco power override in kW. {@code null} = "auto". */
    @CheckForNull public Float locoPowerKw = null;
    /** Loco tractive effort override in kN. {@code null} = "auto". */
    @CheckForNull public Float locoTractiveEffortKn = null;

    public float additionalWeightTonnes = DEFAULT_ADDITIONAL_TONNES;
    public float driverPowerPercent     = DEFAULT_DRIVER_POWER_PCT;
    public float rollingResistanceCoeff = DEFAULT_ROLLING_RESISTANCE;

    public float brakeMaxDecel    = DEFAULT_BRAKE_MAX_DECEL;
    public float airBrakeMaxDecel = DEFAULT_AIR_BRAKE_MAX_DECEL;
    public float dynBrakeMaxDecel = DEFAULT_DYN_BRAKE_MAX_DECEL;
    public float dynBrakeVMinMph  = DEFAULT_DYN_BRAKE_V_MIN_MPH;

    /**
     * Multiplier applied to all prototype-physics force terms (currently only
     * rolling resistance) so they are visible at scale-time wall-clock per
     * §1.0 of the plan. {@code null} means {@code auto} = resolve to JMRI's
     * {@code SignalSpeedMap.getLayoutScale()} at engine attach. A numeric
     * override lets the operator dial in a specific value (e.g. 1 for
     * prototype 1:1 simulation, or 220 for Z scale on a layout that JMRI
     * reports differently). Validation range when explicit: 0.1–1000.
     */
    @CheckForNull public Float physicsTimeScale = null;

    public DecoderBrakeMode decoderBrakeMode = DecoderBrakeMode.NONE;
    public int esuLowFunction  = DEFAULT_ESU_LOW_FUNCTION;
    public int esuMidFunction  = DEFAULT_ESU_MID_FUNCTION;
    public int esuHighFunction = DEFAULT_ESU_HIGH_FUNCTION;
    public int esuLowThreshold  = DEFAULT_ESU_LOW_THRESH;
    public int esuMidThreshold  = DEFAULT_ESU_MID_THRESH;
    public int esuHighThreshold = DEFAULT_ESU_HIGH_THRESH;

    /** Resets every field to its built-in default (= disabled, Light-engine
     *  scenario, scenario-independent brake constants). Does not touch
     *  on-disk state. */
    public void resetToDefaults() {
        persistedEnabled = false;
        liveEnabled = false;
        scenario = LoadScenario.LIGHT_ENGINE;
        locoMassKg = null;
        locoPowerKw = null;
        locoTractiveEffortKn = null;
        additionalWeightTonnes = DEFAULT_ADDITIONAL_TONNES;
        driverPowerPercent = DEFAULT_DRIVER_POWER_PCT;
        rollingResistanceCoeff = DEFAULT_ROLLING_RESISTANCE;
        brakeMaxDecel = DEFAULT_BRAKE_MAX_DECEL;
        airBrakeMaxDecel = DEFAULT_AIR_BRAKE_MAX_DECEL;
        dynBrakeMaxDecel = DEFAULT_DYN_BRAKE_MAX_DECEL;
        dynBrakeVMinMph = DEFAULT_DYN_BRAKE_V_MIN_MPH;
        physicsTimeScale = null; // auto = layoutScale
        decoderBrakeMode = DecoderBrakeMode.NONE;
        esuLowFunction = DEFAULT_ESU_LOW_FUNCTION;
        esuMidFunction = DEFAULT_ESU_MID_FUNCTION;
        esuHighFunction = DEFAULT_ESU_HIGH_FUNCTION;
        esuLowThreshold = DEFAULT_ESU_LOW_THRESH;
        esuMidThreshold = DEFAULT_ESU_MID_THRESH;
        esuHighThreshold = DEFAULT_ESU_HIGH_THRESH;
    }

    public void copyFrom(@Nonnull SemiRealisticSettings other) {
        this.persistedEnabled = other.persistedEnabled;
        this.liveEnabled = other.liveEnabled;
        this.scenario = other.scenario;
        this.locoMassKg = other.locoMassKg;
        this.locoPowerKw = other.locoPowerKw;
        this.locoTractiveEffortKn = other.locoTractiveEffortKn;
        this.additionalWeightTonnes = other.additionalWeightTonnes;
        this.driverPowerPercent = other.driverPowerPercent;
        this.rollingResistanceCoeff = other.rollingResistanceCoeff;
        this.brakeMaxDecel = other.brakeMaxDecel;
        this.airBrakeMaxDecel = other.airBrakeMaxDecel;
        this.dynBrakeMaxDecel = other.dynBrakeMaxDecel;
        this.dynBrakeVMinMph = other.dynBrakeVMinMph;
        this.physicsTimeScale = other.physicsTimeScale;
        this.decoderBrakeMode = other.decoderBrakeMode;
        this.esuLowFunction = other.esuLowFunction;
        this.esuMidFunction = other.esuMidFunction;
        this.esuHighFunction = other.esuHighFunction;
        this.esuLowThreshold = other.esuLowThreshold;
        this.esuMidThreshold = other.esuMidThreshold;
        this.esuHighThreshold = other.esuHighThreshold;
    }

    /** Populates this instance from a {@code <semiRealistic>} XML element.
     *  Missing or empty children fall back to defaults (i.e. disabled,
     *  Light-engine, scenario-independent brake constants). */
    public void loadFrom(@CheckForNull Element semiRealistic) {
        if (semiRealistic == null) {
            // No <semiRealistic> subtree present → leave defaults.
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
        locoMassKg          = readAutoOrFloat(semiRealistic, "locoMassKg");
        locoPowerKw         = readAutoOrFloat(semiRealistic, "locoPowerKw");
        locoTractiveEffortKn = readAutoOrFloat(semiRealistic, "locoTractiveEffortKn");
        Float additional = readFloat(semiRealistic, "additionalWeightTonnes");
        if (additional != null) additionalWeightTonnes = additional;
        Float driver = readFloat(semiRealistic, "driverPowerPercent");
        if (driver != null) driverPowerPercent = driver;
        Float rr = readFloat(semiRealistic, "rollingResistanceCoeff");
        if (rr != null) rollingResistanceCoeff = rr;
        Float bm = readFloat(semiRealistic, "brakeMaxDecel");
        if (bm != null) brakeMaxDecel = bm;
        Float am = readFloat(semiRealistic, "airBrakeMaxDecel");
        if (am != null) airBrakeMaxDecel = am;
        Float dm = readFloat(semiRealistic, "dynBrakeMaxDecel");
        if (dm != null) dynBrakeMaxDecel = dm;
        Float vmin = readFloat(semiRealistic, "dynBrakeVMinMph");
        if (vmin != null) dynBrakeVMinMph = vmin;
        physicsTimeScale = readAutoOrFloat(semiRealistic, "physicsTimeScale");
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

    /** Writes this instance as a {@code <semiRealistic>} XML element. The
     *  caller is responsible for adding it to the root element. */
    public Element writeTo() {
        Element e = new Element("semiRealistic");
        e.addContent(child("enabled", Boolean.toString(persistedEnabled)));
        e.addContent(child("scenario", scenario.displayName()));
        e.addContent(child("locoMassKg", autoOrFloat(locoMassKg)));
        e.addContent(child("locoPowerKw", autoOrFloat(locoPowerKw)));
        e.addContent(child("locoTractiveEffortKn", autoOrFloat(locoTractiveEffortKn)));
        e.addContent(child("additionalWeightTonnes", Float.toString(additionalWeightTonnes)));
        e.addContent(child("driverPowerPercent", Float.toString(driverPowerPercent)));
        e.addContent(child("rollingResistanceCoeff", Float.toString(rollingResistanceCoeff)));
        e.addContent(child("brakeMaxDecel", Float.toString(brakeMaxDecel)));
        e.addContent(child("airBrakeMaxDecel", Float.toString(airBrakeMaxDecel)));
        e.addContent(child("dynBrakeMaxDecel", Float.toString(dynBrakeMaxDecel)));
        e.addContent(child("dynBrakeVMinMph", Float.toString(dynBrakeVMinMph)));
        e.addContent(child("physicsTimeScale", autoOrFloat(physicsTimeScale)));
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

    /** "auto" sentinel handling for the boxed-Float overrides: text "auto"
     *  (or empty / missing) maps to {@code null}; numeric text parses to a
     *  Float; non-numeric junk falls back to {@code null}. */
    @CheckForNull
    private static Float readAutoOrFloat(Element parent, String childName) {
        String t = readText(parent, childName);
        if (t == null || "auto".equalsIgnoreCase(t)) return null;
        try { return Float.parseFloat(t); }
        catch (NumberFormatException ex) { return null; }
    }

    private static String autoOrFloat(@CheckForNull Float v) {
        return v == null ? "auto" : Float.toString(v);
    }
}
