package jmri.jmrit.usb;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.jdom2.Element;

/**
 * Settings POJO for the EngineDriver-aligned semi-realistic throttle engine.
 * <p>
 * The engine uses a step-rate scheduler: the throttle lever sets a target
 * decoder speed step and the live speed walks toward that target one step
 * at a time, with a configurable delay between steps. Brakes, load, and
 * air-line state modify the delay (and thereby the ramp rate) rather than
 * applying continuous physics forces.
 * <p>
 * Field names and defaults match EngineDriver's
 * {@code throttle_semi_realistic.java} (SHA {@code 5e722d38}).
 * <p>
 * The {@code persistedEnabled} field is the value last loaded from XML or
 * last saved by the Settings tab; {@code liveEnabled} is the value the
 * engine and Jynstrument act on right now. These are deliberately
 * decoupled — the Settings tab edits {@code persistedEnabled}, the
 * Jynstrument toggles {@code liveEnabled}.
 */
public final class SemiRealisticSettings {

    // ======================== Defaults ========================

    /** Default ms between speed steps when accelerating. */
    public static final int DEFAULT_BASE_ACCEL_DELAY_MS = 300;
    /** Default ms between speed steps when decelerating (coast). */
    public static final int DEFAULT_BASE_DECEL_DELAY_MS = 800;
    /** Minimum interval between setSpeedSetting calls (deferred-emit). */
    public static final int DEFAULT_MIN_EMIT_INTERVAL_MS = 50;
    /** Number of quantised brake notches. */
    public static final int DEFAULT_NUMBER_OF_BRAKE_STEPS = 7;

    // Air brake defaults
    public static final int DEFAULT_AIR_LINE_RECHARGE_PCNT = 20;
    public static final int DEFAULT_AIR_REFRESH_RATE_MS = 2000;
    public static final int DEFAULT_AIR_RESERVOIR_REPLENISH_PCNT = 5;

    // Dynamic brake
    public static final int DEFAULT_DYN_BRAKE_MIN_SPEED_STEP = 8;

    // Load
    public static final int DEFAULT_NUMBER_OF_LOAD_STEPS = 5;
    public static final int DEFAULT_MAX_LOAD_PCNT = 1000;
    public static final int DEFAULT_LOAD_SLIDER_POSITION = 0;

    // Ramp step size
    /** Default speed steps per ramp tick. */
    public static final int DEFAULT_SPEED_STEP_INCREMENT = 1;

    // ESU decoder-brake function defaults
    public static final int DEFAULT_ESU_LOW_FUNCTION  = 4;
    public static final int DEFAULT_ESU_MID_FUNCTION  = 5;
    public static final int DEFAULT_ESU_HIGH_FUNCTION = 6;
    public static final int DEFAULT_ESU_LOW_THRESH    = 30;
    public static final int DEFAULT_ESU_MID_THRESH    = 60;
    public static final int DEFAULT_ESU_HIGH_THRESH   = 98;

    /** Decoder brake passthrough mode. */
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

    // ======================== Fields ========================

    /** Persisted enable state — what's on disk and what the Settings tab
     *  checkbox shows. */
    public boolean persistedEnabled = false;

    /** Live enable state — what the engine and Jynstrument act on. Initialised
     *  from {@link #persistedEnabled} on load. Not persisted to XML. */
    public boolean liveEnabled = false;

    // --- Ramp timing ---
    /** Milliseconds between speed steps when accelerating. */
    public int baseAccelDelayMs = DEFAULT_BASE_ACCEL_DELAY_MS;
    /** Milliseconds between speed steps when decelerating/coasting. */
    public int baseDecelDelayMs = DEFAULT_BASE_DECEL_DELAY_MS;
    /** Minimum interval between setSpeedSetting calls to the DCC throttle. */
    public int minEmitIntervalMs = DEFAULT_MIN_EMIT_INTERVAL_MS;

    // --- Brake ---
    /** Number of quantised brake notches for independent brake lever. */
    public int numberOfBrakeSteps = DEFAULT_NUMBER_OF_BRAKE_STEPS;

    // --- Air brake ---
    /** Percent of air line recharged per air-repeater tick. */
    public int airLineRechargePcnt = DEFAULT_AIR_LINE_RECHARGE_PCNT;
    /** Milliseconds between air-repeater ticks. 0 = flat-mapping (no dynamics). */
    public int airRefreshRateMs = DEFAULT_AIR_REFRESH_RATE_MS;
    /** Percent of reservoir replenished per reservoir-repeater tick. */
    public int airReservoirReplenishPcnt = DEFAULT_AIR_RESERVOIR_REPLENISH_PCNT;

    // --- Dynamic brake ---
    /** Speed step below which dynamic brake effect tapers linearly to zero. */
    public int dynBrakeMinSpeedStep = DEFAULT_DYN_BRAKE_MIN_SPEED_STEP;

    // --- Load ---
    /** Number of load-slider positions (0 = light engine). */
    public int numberOfLoadSteps = DEFAULT_NUMBER_OF_LOAD_STEPS;
    /** Maximum load percentage × 100 (e.g. 1000 = 10×). */
    public int maxLoadPcnt = DEFAULT_MAX_LOAD_PCNT;
    /** Current load slider position (0 = light engine). */
    public int loadSliderPosition = DEFAULT_LOAD_SLIDER_POSITION;

    // --- Ramp step size ---
    /** Speed steps per ramp tick (1 = smoothest, higher = coarser/faster). */
    public int speedStepIncrement = DEFAULT_SPEED_STEP_INCREMENT;

    // --- ESU decoder brake ---
    public DecoderBrakeMode decoderBrakeMode = DecoderBrakeMode.NONE;
    public int esuLowFunction  = DEFAULT_ESU_LOW_FUNCTION;
    public int esuMidFunction  = DEFAULT_ESU_MID_FUNCTION;
    public int esuHighFunction = DEFAULT_ESU_HIGH_FUNCTION;
    public int esuLowThreshold  = DEFAULT_ESU_LOW_THRESH;
    public int esuMidThreshold  = DEFAULT_ESU_MID_THRESH;
    public int esuHighThreshold = DEFAULT_ESU_HIGH_THRESH;

    // ======================== Construction ========================

    public SemiRealisticSettings() {
        // All fields initialised to defaults above.
    }

    /** Defensive-copy constructor. */
    public SemiRealisticSettings(@Nonnull SemiRealisticSettings other) {
        copyFrom(other);
    }

    // ======================== Operations ========================

    /** Resets every field to defaults. */
    public void resetToDefaults() {
        persistedEnabled = false;
        liveEnabled = false;
        baseAccelDelayMs = DEFAULT_BASE_ACCEL_DELAY_MS;
        baseDecelDelayMs = DEFAULT_BASE_DECEL_DELAY_MS;
        minEmitIntervalMs = DEFAULT_MIN_EMIT_INTERVAL_MS;
        numberOfBrakeSteps = DEFAULT_NUMBER_OF_BRAKE_STEPS;
        airLineRechargePcnt = DEFAULT_AIR_LINE_RECHARGE_PCNT;
        airRefreshRateMs = DEFAULT_AIR_REFRESH_RATE_MS;
        airReservoirReplenishPcnt = DEFAULT_AIR_RESERVOIR_REPLENISH_PCNT;
        dynBrakeMinSpeedStep = DEFAULT_DYN_BRAKE_MIN_SPEED_STEP;
        numberOfLoadSteps = DEFAULT_NUMBER_OF_LOAD_STEPS;
        maxLoadPcnt = DEFAULT_MAX_LOAD_PCNT;
        loadSliderPosition = DEFAULT_LOAD_SLIDER_POSITION;
        speedStepIncrement = DEFAULT_SPEED_STEP_INCREMENT;
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
        this.baseAccelDelayMs = other.baseAccelDelayMs;
        this.baseDecelDelayMs = other.baseDecelDelayMs;
        this.minEmitIntervalMs = other.minEmitIntervalMs;
        this.numberOfBrakeSteps = other.numberOfBrakeSteps;
        this.airLineRechargePcnt = other.airLineRechargePcnt;
        this.airRefreshRateMs = other.airRefreshRateMs;
        this.airReservoirReplenishPcnt = other.airReservoirReplenishPcnt;
        this.dynBrakeMinSpeedStep = other.dynBrakeMinSpeedStep;
        this.numberOfLoadSteps = other.numberOfLoadSteps;
        this.maxLoadPcnt = other.maxLoadPcnt;
        this.loadSliderPosition = other.loadSliderPosition;
        this.speedStepIncrement = other.speedStepIncrement;
        this.decoderBrakeMode = other.decoderBrakeMode;
        this.esuLowFunction = other.esuLowFunction;
        this.esuMidFunction = other.esuMidFunction;
        this.esuHighFunction = other.esuHighFunction;
        this.esuLowThreshold = other.esuLowThreshold;
        this.esuMidThreshold = other.esuMidThreshold;
        this.esuHighThreshold = other.esuHighThreshold;
    }

    // ======================== XML persistence ========================

    /** Populates this instance from a {@code <semiRealistic>} XML element.
     *  Missing or empty children fall back to defaults. Legacy physics
     *  elements ({@code scenario}, {@code maxAccelAtRestMs2},
     *  {@code vCornerMps}, etc.) are silently ignored. */
    public void loadFrom(@CheckForNull Element semiRealistic) {
        if (semiRealistic == null) {
            return;
        }
        Boolean enabled = readBool(semiRealistic, "enabled");
        if (enabled != null) {
            persistedEnabled = enabled;
            liveEnabled = enabled;
        }
        Integer i;
        if ((i = readInt(semiRealistic, "baseAccelDelayMs"))   != null) baseAccelDelayMs   = i;
        if ((i = readInt(semiRealistic, "baseDecelDelayMs"))   != null) baseDecelDelayMs   = i;
        if ((i = readInt(semiRealistic, "minEmitIntervalMs"))  != null) minEmitIntervalMs  = i;
        if ((i = readInt(semiRealistic, "numberOfBrakeSteps")) != null) numberOfBrakeSteps = i;
        if ((i = readInt(semiRealistic, "airLineRechargePcnt"))       != null) airLineRechargePcnt       = i;
        if ((i = readInt(semiRealistic, "airRefreshRateMs"))          != null) airRefreshRateMs          = i;
        if ((i = readInt(semiRealistic, "airReservoirReplenishPcnt")) != null) airReservoirReplenishPcnt = i;
        if ((i = readInt(semiRealistic, "dynBrakeMinSpeedStep")) != null) dynBrakeMinSpeedStep = i;
        if ((i = readInt(semiRealistic, "numberOfLoadSteps")) != null) numberOfLoadSteps = i;
        if ((i = readInt(semiRealistic, "maxLoadPcnt"))        != null) maxLoadPcnt        = i;
        if ((i = readInt(semiRealistic, "loadSliderPosition")) != null) loadSliderPosition = i;
        if ((i = readInt(semiRealistic, "speedStepIncrement")) != null) speedStepIncrement = Math.max(1, i);

        String mode = readText(semiRealistic, "decoderBrakeMode");
        if (mode != null) decoderBrakeMode = DecoderBrakeMode.fromToken(mode);
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
        e.addContent(child("baseAccelDelayMs", Integer.toString(baseAccelDelayMs)));
        e.addContent(child("baseDecelDelayMs", Integer.toString(baseDecelDelayMs)));
        e.addContent(child("minEmitIntervalMs", Integer.toString(minEmitIntervalMs)));
        e.addContent(child("numberOfBrakeSteps", Integer.toString(numberOfBrakeSteps)));
        e.addContent(child("airLineRechargePcnt", Integer.toString(airLineRechargePcnt)));
        e.addContent(child("airRefreshRateMs", Integer.toString(airRefreshRateMs)));
        e.addContent(child("airReservoirReplenishPcnt", Integer.toString(airReservoirReplenishPcnt)));
        e.addContent(child("dynBrakeMinSpeedStep", Integer.toString(dynBrakeMinSpeedStep)));
        e.addContent(child("numberOfLoadSteps", Integer.toString(numberOfLoadSteps)));
        e.addContent(child("maxLoadPcnt", Integer.toString(maxLoadPcnt)));
        e.addContent(child("loadSliderPosition", Integer.toString(loadSliderPosition)));
        e.addContent(child("speedStepIncrement", Integer.toString(speedStepIncrement)));
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
    private static Integer readInt(Element parent, String childName) {
        String t = readText(parent, childName);
        if (t == null) return null;
        try { return Integer.parseInt(t); }
        catch (NumberFormatException ex) { return null; }
    }
}
