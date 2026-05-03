package jmri.util.usb;

/**
 * Catalogue of feel-coefficient presets used by the semi-realistic throttle
 * engine (see {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md}
 * §2.4.1).
 * <p>
 * Each scenario carries a complete set of operator-feel-tuned coefficients
 * for the wall-clock-only model (§1.0): drive (max accel at rest, vCorner,
 * driver-power %, design top speed, steam flag), Davis-shape coast resistance
 * (static / linear / quadratic, all in wall-clock decel units), and brakes
 * (mech / air / dyn / dyn-mass-fraction). All decel/accel values are wall-clock
 * m/s² — the engine has no scale factor. Scenarios are abstract feel presets
 * tuned for the desired operator experience; they are NOT derived from
 * prototype mass / power / TE / Davis values. Real prototype data informs
 * the <em>shape</em> of the curves but not the absolute values.
 * <p>
 * The {@link #CUSTOM} value lets the operator override every coefficient on
 * the Settings tab; its constants are placeholders that match Light engine
 * so a fresh switch to Custom inherits sensible starting values.
 * <p>
 * Light-engine values are also the empty-roster fallback per §2.4.1: a fresh
 * JMRI install with no populated roster gets sensible single-loco behaviour
 * out of the box.
 */
public enum LoadScenario {

    //                              top    aRest vCorn  pPct  rStat rLin     rQuad bMech bAir bDyn dynFr  steam
    LIGHT_ENGINE   ("Light engine",  35.76f, 2.5f, 35.76f, 1.00f, 1.0f, 0.0f, 0.001f,  4.0f, 6.0f, 1.6f,  1.00f, false),
    SWITCHER       ("Switcher",      13.41f, 2.0f, 13.41f, 0.80f, 1.5f, 0.0f, 0.005f,  4.0f, 6.0f, 1.0f,  0.70f, false),
    EMD_NW2        ("EMD NW2",       20.12f, 2.5f, 20.12f, 0.90f, 1.5f, 0.0f, 0.002f,  4.0f, 6.0f, 0.0f,  1.00f, false),
    LOCAL_FREIGHT  ("Local freight", 35.76f, 1.5f, 35.76f, 0.90f, 1.0f, 0.0f, 0.0008f, 3.0f, 5.0f, 0.8f,  0.30f, false),
    THROUGH_FREIGHT("Through freight",35.76f,1.0f, 35.76f, 1.00f, 0.8f, 0.0f, 0.0006f, 2.5f, 4.0f, 0.5f,  0.15f, false),
    UNIT_TRAIN     ("Unit train",    35.76f, 0.7f, 35.76f, 1.00f, 0.6f, 0.0f, 0.0004f, 2.0f, 3.5f, 0.3f,  0.08f, false),
    CUSTOM         ("Custom",        35.76f, 2.5f, 35.76f, 1.00f, 1.0f, 0.0f, 0.001f,  4.0f, 6.0f, 1.6f,  1.00f, false);

    private final String  displayName;
    private final float   designTopSpeedMps;
    private final float   maxAccelAtRestMs2;
    private final float   vCornerMps;
    private final float   driverPowerPct;        // 0..1
    private final float   resistStaticMs2;
    private final float   resistLinearPerSec;
    private final float   resistQuadPerMeter;
    private final float   brakeMaxDecelMs2;
    private final float   airBrakeMaxDecelMs2;
    private final float   dynBrakeMaxDecelMs2;
    private final float   dynBrakeMassFraction;  // 0..1
    private final boolean steam;

    LoadScenario(String displayName,
                 float designTopSpeedMps,
                 float maxAccelAtRestMs2,
                 float vCornerMps,
                 float driverPowerPct,
                 float resistStaticMs2,
                 float resistLinearPerSec,
                 float resistQuadPerMeter,
                 float brakeMaxDecelMs2,
                 float airBrakeMaxDecelMs2,
                 float dynBrakeMaxDecelMs2,
                 float dynBrakeMassFraction,
                 boolean steam) {
        this.displayName          = displayName;
        this.designTopSpeedMps    = designTopSpeedMps;
        this.maxAccelAtRestMs2    = maxAccelAtRestMs2;
        this.vCornerMps           = vCornerMps;
        this.driverPowerPct       = driverPowerPct;
        this.resistStaticMs2      = resistStaticMs2;
        this.resistLinearPerSec   = resistLinearPerSec;
        this.resistQuadPerMeter   = resistQuadPerMeter;
        this.brakeMaxDecelMs2     = brakeMaxDecelMs2;
        this.airBrakeMaxDecelMs2  = airBrakeMaxDecelMs2;
        this.dynBrakeMaxDecelMs2  = dynBrakeMaxDecelMs2;
        this.dynBrakeMassFraction = dynBrakeMassFraction;
        this.steam                = steam;
    }

    public String  displayName()              { return displayName; }
    /** Design top speed in m/s prototype. Used as the linear-fallback
     *  denominator when no roster speed profile is calibrated, and also as
     *  the steam power-curve denominator. */
    public float   designTopSpeedMps()        { return designTopSpeedMps; }
    /** Wall-clock m/s² acceleration at v=0 under full throttle, no resist. */
    public float   maxAccelAtRestMs2()        { return maxAccelAtRestMs2; }
    /** Velocity (m/s prototype) above which a_drive falls off as
     *  {@code vCorner / v}, mimicking the constant-power physics shape. */
    public float   vCornerMps()               { return vCornerMps; }
    /** 0.0..1.0 multiplier on the entire drive curve. */
    public float   driverPowerPct()           { return driverPowerPct; }
    /** Wall-clock m/s² constant decel (Davis A term, dominates near halt). */
    public float   resistStaticMs2()          { return resistStaticMs2; }
    /** Wall-clock 1/s coefficient (Davis B term: decel = b·v). */
    public float   resistLinearPerSec()       { return resistLinearPerSec; }
    /** Wall-clock 1/m coefficient (Davis C term: decel = c·v²). */
    public float   resistQuadPerMeter()       { return resistQuadPerMeter; }
    /** Wall-clock m/s² mech-brake decel @ 100 % indep brake. */
    public float   brakeMaxDecelMs2()         { return brakeMaxDecelMs2; }
    /** Wall-clock m/s² air-brake decel @ 100 % air line. */
    public float   airBrakeMaxDecelMs2()      { return airBrakeMaxDecelMs2; }
    /** Wall-clock m/s² dyn-brake decel @ 100 % dyn brake (peak, before
     *  taper and mass-fraction dilution). 0 disables dyn brake under this
     *  scenario (e.g. for a real EMD NW2 — most shipped without dyn brake). */
    public float   dynBrakeMaxDecelMs2()      { return dynBrakeMaxDecelMs2; }
    /** Multiplier on the dyn-brake decel capturing the prototype reality
     *  that dyn brake doesn't propagate through trainline air — diluted in
     *  heavier consists. 1.0 for light engine, ~0.08 for unit train. */
    public float   dynBrakeMassFraction()     { return dynBrakeMassFraction; }
    /** Steam locomotives multiply a_drive by {@code (v / vTop)^0.85} for
     *  the steam tractive-effort fall-off. */
    public boolean steamPowerCurve()          { return steam; }

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
