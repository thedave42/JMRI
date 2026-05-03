package jmri.util.usb;

/**
 * Catalogue of consist scenarios used by the semi-realistic throttle engine
 * (see {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.4.1).
 * <p>
 * Each scenario carries default loco physics (mass / power / tractive effort),
 * the additional consist mass, the operator-power percentage applied to drive
 * forces, the design top speed used as the linear-fallback denominator when
 * no roster speed profile is calibrated, and a flag indicating whether the
 * loco should be modelled with the steam-power exponent. The {@link #CUSTOM}
 * value is a passthrough — its defaults are placeholders; the operator's
 * Settings-tab overrides take precedence whenever a Custom scenario is
 * selected.
 * <p>
 * Light-engine values are also the empty-roster fallback per §2.4.1: a fresh
 * JMRI install with no populated roster physics gets prototype-realistic
 * single-loco behaviour out of the box.
 */
public enum LoadScenario {

    LIGHT_ENGINE   ("Light engine",  130_000f, 2_200_000f, 350_000f,        0f, 1.00f, 35.76f, false),
    SWITCHER       ("Switcher",      100_000f, 1_100_000f, 200_000f,   200_000f, 0.80f, 13.41f, false),
    LOCAL_FREIGHT  ("Local freight", 130_000f, 2_200_000f, 350_000f, 1_500_000f, 0.90f, 35.76f, false),
    THROUGH_FREIGHT("Through freight",130_000f,2_200_000f, 350_000f, 5_000_000f, 1.00f, 35.76f, false),
    UNIT_TRAIN     ("Unit train",    130_000f, 2_200_000f, 350_000f,10_000_000f, 1.00f, 35.76f, false),
    CUSTOM         ("Custom",        130_000f, 2_200_000f, 350_000f,        0f, 1.00f, 35.76f, false);

    private final String displayName;
    private final float defaultLocoMassKg;
    private final float defaultLocoPowerW;
    private final float defaultLocoTractiveEffortN;
    private final float defaultAdditionalMassKg;
    private final float defaultDriverPowerPct;
    private final float designTopSpeedMps;
    private final boolean steam;

    LoadScenario(String displayName,
                 float defaultLocoMassKg,
                 float defaultLocoPowerW,
                 float defaultLocoTractiveEffortN,
                 float defaultAdditionalMassKg,
                 float defaultDriverPowerPct,
                 float designTopSpeedMps,
                 boolean steam) {
        this.displayName = displayName;
        this.defaultLocoMassKg = defaultLocoMassKg;
        this.defaultLocoPowerW = defaultLocoPowerW;
        this.defaultLocoTractiveEffortN = defaultLocoTractiveEffortN;
        this.defaultAdditionalMassKg = defaultAdditionalMassKg;
        this.defaultDriverPowerPct = defaultDriverPowerPct;
        this.designTopSpeedMps = designTopSpeedMps;
        this.steam = steam;
    }

    public String displayName()                  { return displayName; }
    public float defaultLocoMassKg()             { return defaultLocoMassKg; }
    public float defaultLocoPowerW()             { return defaultLocoPowerW; }
    public float defaultLocoTractiveEffortN()    { return defaultLocoTractiveEffortN; }
    public float defaultAdditionalMassKg()       { return defaultAdditionalMassKg; }
    public float defaultDriverPowerPct()         { return defaultDriverPowerPct; }
    /** Design top speed in m/s. Used as the denominator of the linear
     *  v_fs ↔ throttle-fraction conversion when no roster speed profile is
     *  calibrated for the loco's current direction. */
    public float designTopSpeedMps()             { return designTopSpeedMps; }
    public boolean steamPowerCurve()             { return steam; }

    /** Resolve a stored scenario name ("Light engine", etc.) back to its
     *  enum constant; falls back to {@link #LIGHT_ENGINE} when the value is
     *  null or unrecognised so a corrupted XML never breaks engine startup. */
    public static LoadScenario fromDisplayName(String s) {
        if (s != null) {
            for (LoadScenario sc : values()) {
                if (sc.displayName.equalsIgnoreCase(s)) {
                    return sc;
                }
            }
        }
        return LIGHT_ENGINE;
    }
}
