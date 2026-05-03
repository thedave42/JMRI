package jmri.util.usb;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import jmri.DccThrottle;
import jmri.InstanceManager;
import jmri.implementation.SignalSpeedMap;
import jmri.jmrit.roster.RosterEntry;
import jmri.jmrit.roster.RosterSpeedProfile;
import jmri.util.ThreadingUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-throttle physics engine for the RailDriver semi-realistic mode.
 * <p>
 * Owns one worker thread that runs a 50 ms fixed-rate integration tick. Each
 * tick reads the current input snapshot (volatile fields written by the
 * polling thread), computes a net force from drive / rolling-resistance /
 * brake terms, integrates {@code v_fs} (prototype full-scale velocity in
 * m/s), quantises to a DCC throttle setting via the loco's
 * {@link RosterSpeedProfile} when available (linear fallback otherwise),
 * and posts the result to the EDT for {@code setSpeedSetting}.
 * <p>
 * Lifecycle (driven by the host {@link RailDriverMenuItem}):
 * <ol>
 *   <li>Construct in {@code attachThrottleWindow} after listener wiring.
 *       Engine starts in DETACHED state — no throttle, no tick scheduled.
 *   <li>{@link #attachThrottle} on {@code AddressListener.notifyAddressThrottleFound}:
 *       resolves physics from settings + roster, transitions to
 *       ATTACHED_DISABLED.
 *   <li>{@link #setLiveEnabled}{@code (true)}: reads current
 *       {@code throttle.getSpeedSetting()} on the EDT, seeds {@code v_fs},
 *       schedules the tick, transitions to ATTACHED_ENABLED.
 *   <li>{@link #setLiveEnabled}{@code (false)}: cancels the tick,
 *       transitions to ATTACHED_DISABLED. The polling-thread direct path
 *       takes over until the next OFF→ON.
 *   <li>{@link #detachThrottle} on {@code notifyAddressReleased}: cancels
 *       tick, clears throttle/roster refs, transitions to DETACHED.
 *   <li>{@link #dispose} on throttle-window close: shuts down the worker
 *       thread.
 * </ol>
 * Stage 2 wires only the throttle-driven {@code F_drive} and the
 * indep-brake-driven {@code F_brake_mech}; the air-brake and dyn-brake
 * input fields exist on this class but stay zero until stages 4 and 5.
 * <p>
 * See {@code docs/rpi-raildriver/semi-realistic-throttle-plan.md} §2.1
 * for the engine spec and §2.2 for the threading contract.
 */
public final class SemiRealisticThrottleEngine {

    /** Reverser direction. The engine itself does not enforce the stage-3
     *  reverser interlock; it only tracks the latest direction so future
     *  stages can reach into it. */
    public enum Direction { FORWARD, NEUTRAL, REVERSE }

    /** Linear-fallback minimum velocity guard for the {@code P/v} drive
     *  computation: at very low speed, raw {@code P/v} can blow up. */
    private static final float V_GUARD_MIN_MPS = 0.01f;

    /** Standard gravity, m/s². */
    private static final float G_MPS2 = 9.80665f;

    /** Conversion: 1 mph = 0.44704 m/s. */
    private static final float MPH_TO_MPS = 0.44704f;

    /** Tick slice in seconds (50 ms). */
    private static final float TICK_SECONDS = 0.050f;
    private static final long TICK_MS = 50L;

    /** Snapping threshold: when {@code |v_fs - vTarget| < EPS_VFS}, we
     *  pin {@code v_fs} to {@code vTarget} to avoid oscillation. */
    private static final float EPS_VFS = 0.005f;

    /**
     * Immutable snapshot of all physics-tunable parameters resolved from
     * settings + roster. Published by {@link #rebuildPhysics()} via a
     * single {@code volatile} reference swap so each tick reads a
     * coherent configuration even if the operator edits settings mid-run.
     */
    private static final class ResolvedPhysics {
        final float locoMassKg;
        final float locoPowerW;
        final float locoTractiveEffortN;
        final float additionalMassKg;
        final float driverPowerPct;          // 0.0..1.0
        final float rollingResistanceCoeff;  // c_rr — prototype-physics, dimensionless
        final float brakeMaxDecel;            // wall-clock m/s² @ 100% indep brake (operator-controlled)
        final float airBrakeMaxDecel;         // wall-clock m/s² @ 100% air line   (operator-controlled)
        final float dynBrakeMaxDecel;         // wall-clock m/s² @ 100% dyn brake  (operator-controlled, peak)
        final float dynBrakeVMinMps;          // prototype velocity taper threshold (m/s)
        final float vCapMps;                  // POSITIVE_INFINITY when uncapped
        final float designTopSpeedMps;        // linear-fallback denominator
        final boolean steamPowerCurve;
        final float layoutScale;              // for layout mm/s ↔ prototype m/s (speed-profile lookup)
        /** Multiplier applied to all prototype-physics force terms so they are
         *  visible at scale-time wall-clock. Equals the operator's explicit
         *  override when set, otherwise JMRI's {@code SignalSpeedMap.getLayoutScale()}.
         *  See plan §1.0 for the two-class force model. */
        final float physicsTimeScale;
        final boolean useSpeedProfileForward;
        final boolean useSpeedProfileReverse;

        ResolvedPhysics(float locoMassKg, float locoPowerW, float locoTractiveEffortN,
                        float additionalMassKg, float driverPowerPct,
                        float rollingResistanceCoeff,
                        float brakeMaxDecel, float airBrakeMaxDecel,
                        float dynBrakeMaxDecel, float dynBrakeVMinMps,
                        float vCapMps, float designTopSpeedMps,
                        boolean steamPowerCurve, float layoutScale,
                        float physicsTimeScale,
                        boolean useSpeedProfileForward, boolean useSpeedProfileReverse) {
            this.locoMassKg = locoMassKg;
            this.locoPowerW = locoPowerW;
            this.locoTractiveEffortN = locoTractiveEffortN;
            this.additionalMassKg = additionalMassKg;
            this.driverPowerPct = driverPowerPct;
            this.rollingResistanceCoeff = rollingResistanceCoeff;
            this.brakeMaxDecel = brakeMaxDecel;
            this.airBrakeMaxDecel = airBrakeMaxDecel;
            this.dynBrakeMaxDecel = dynBrakeMaxDecel;
            this.dynBrakeVMinMps = dynBrakeVMinMps;
            this.vCapMps = vCapMps;
            this.designTopSpeedMps = designTopSpeedMps;
            this.steamPowerCurve = steamPowerCurve;
            this.layoutScale = layoutScale;
            this.physicsTimeScale = physicsTimeScale;
            this.useSpeedProfileForward = useSpeedProfileForward;
            this.useSpeedProfileReverse = useSpeedProfileReverse;
        }

        float totalMass() { return locoMassKg + additionalMassKg; }
    }

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RailDriver-SemiRealistic-Physics");
            t.setDaemon(true);
            return t;
        });

    // — Mutable lifecycle state, guarded by `this` (writes) but flagged
    //   volatile for the polling thread's lock-free isDriving() read —
    @CheckForNull private DccThrottle throttle;
    @CheckForNull private RosterEntry rosterEntry;
    /** Volatile so the worker can read this outside the monitor when
     *  doing speed-profile lookups. Written under `synchronized(this)`. */
    @CheckForNull private volatile RosterSpeedProfile speedProfile;
    @CheckForNull private SemiRealisticSettings settings;
    @CheckForNull private ScheduledFuture<?> tickHandle;
    /** Volatile so the polling thread's {@link #isDriving()} sees fresh
     *  values without taking the monitor. Written under
     *  {@code synchronized(this)}. */
    private volatile boolean attached = false;
    /** Volatile so the polling thread's {@link #isDriving()} sees fresh
     *  values without taking the monitor. Written under
     *  {@code synchronized(this)}. */
    private volatile boolean enabled  = false;

    // — Physics snapshot, single-volatile-swap publication —
    @CheckForNull private volatile ResolvedPhysics physics;

    // — Live integration state —
    /** Worker-thread-only except during mode-switch handover, where it is
     *  written from the EDT under {@code synchronized(this)}. Declared
     *  volatile so the polling-thread reads (stage 3 reverser interlock)
     *  see the latest worker write. */
    private volatile float v_fs = 0.0f;

    /** Lever-derived target velocity (prototype m/s). Computed by the
     *  worker each tick from {@link #throttleFraction} and the active
     *  scenario's {@code designTopSpeedMps} (or {@code vCap_fs} when set). */
    private volatile float vTarget_fs = 0.0f;

    // — Latest physical inputs, polling thread → worker (volatile, no compound ops) —
    private volatile float throttleFraction      = 0.0f;
    @SuppressWarnings("unused") // used by stage 5
    private volatile float dynBrakeFraction      = 0.0f;
    private volatile float indepBrakeFraction    = 0.0f;
    @SuppressWarnings("unused") // used by stage 4
    private volatile float airLineFraction       = 0.0f;
    @SuppressWarnings("unused") // used by stage 4
    private volatile boolean bailoffPressed      = false;
    @SuppressWarnings("unused") // used by stage 3
    private volatile Direction direction         = Direction.NEUTRAL;

    /** Tracks whether the engine emitted a non-zero setSpeedSetting since
     *  the last "both at zero" tick, so we can emit one final zero before
     *  going quiet on a stop. */
    private boolean emittedNonZero = false;

    // ==================== Lifecycle (EDT-only setters) ====================

    /**
     * Records the currently-bound throttle and roster, rebuilds the
     * physics snapshot from settings + roster, and starts ticking if
     * already enabled. Must be called from the EDT (typically
     * {@code AddressListener.notifyAddressThrottleFound}).
     */
    public synchronized void attachThrottle(@Nonnull DccThrottle t,
                                            @CheckForNull RosterEntry re) {
        this.throttle = t;
        this.rosterEntry = re;
        this.speedProfile = (re != null) ? re.getSpeedProfile() : null;
        this.attached = true;
        rebuildPhysicsLocked();
        maybeStartTickingLocked();
        log.debug("Engine attached: throttle={}, roster={}", t.getLocoAddress(),
                re == null ? "<none>" : re.getId());
    }

    /**
     * Releases the throttle and roster references and cancels ticking.
     * Called from {@code AddressListener.notifyAddressReleased}.
     */
    public synchronized void detachThrottle() {
        stopTickingLocked();
        this.throttle = null;
        this.rosterEntry = null;
        this.speedProfile = null;
        this.attached = false;
        // Reset velocity so that a subsequent attach + enable starts from
        // the new throttle's getSpeedSetting() rather than carrying state
        // from the previous loco.
        this.v_fs = 0.0f;
        this.emittedNonZero = false;
        log.debug("Engine detached");
    }

    /**
     * Pushes a new settings snapshot. Rebuilds {@link ResolvedPhysics}
     * atomically so the next tick sees a coherent configuration. Called
     * from the EDT (e.g. via {@code RailDriverMenuItem.reloadCalibration}
     * after Save).
     */
    public synchronized void updateSettings(@Nonnull SemiRealisticSettings s) {
        this.settings = s;
        rebuildPhysicsLocked();
    }

    /**
     * Mode toggle. OFF→ON reads {@code throttle.getSpeedSetting()} on the
     * EDT, converts to {@code v_fs}, and schedules the tick. ON→OFF
     * cancels the tick. Idempotent; no-op when {@code enabled == this.enabled}.
     */
    public synchronized void setLiveEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        boolean wasEnabled = this.enabled;
        this.enabled = enabled;
        if (!wasEnabled && enabled && attached && throttle != null) {
            // OFF → ON: seed v_fs from the loco's current throttle setting
            // so there is no perceived snap. Reads getSpeedSetting on the
            // EDT (we are already on the EDT here, so runOnGUIwithReturn
            // executes synchronously per ThreadingUtil.java:224).
            final DccThrottle t = throttle;
            float fraction;
            try {
                fraction = ThreadingUtil.runOnGUIwithReturn(t::getSpeedSetting);
            } catch (RuntimeException ex) {
                log.warn("Failed to read throttle.getSpeedSetting() on OFF→ON; defaulting to 0", ex);
                fraction = 0.0f;
            }
            // Negative values denote emergency stop; treat as zero for v_fs.
            if (fraction < 0.0f) fraction = 0.0f;
            v_fs = fractionToVfsMps(fraction, currentDirectionForward());
            emittedNonZero = false;
            startTickingLocked();
            log.debug("Engine OFF→ON: seeded v_fs = {} m/s (from setting {})", v_fs, fraction);
        } else if (wasEnabled && !enabled) {
            stopTickingLocked();
            log.debug("Engine ON→OFF: tick cancelled");
        }
        // OFF stays OFF, or ON stays ON (no-op above).
    }

    /** Whether the engine is currently driving the loco (live enabled and
     *  attached and ticking). Polled from the polling thread to decide
     *  whether to route Axis 1 through the engine or fall through to the
     *  direct-dispatch path. Volatile reads only; no lock needed. */
    public boolean isDriving() {
        return enabled && attached;
    }

    /** Shuts down the worker thread. Engine is unusable after dispose. */
    public synchronized void dispose() {
        stopTickingLocked();
        scheduler.shutdownNow();
        this.throttle = null;
        this.rosterEntry = null;
        this.speedProfile = null;
        this.attached = false;
        this.enabled = false;
    }

    // ==================== Polling-thread input setters (volatile only) ====================

    public void setThrottleFraction(float fraction) {
        this.throttleFraction = clamp01(fraction);
    }

    public void setDynBrakeFraction(float fraction) {
        this.dynBrakeFraction = clamp01(fraction);
    }

    public void setIndepBrakeFraction(float fraction) {
        this.indepBrakeFraction = clamp01(fraction);
    }

    public void setAirLineFraction(float fraction) {
        this.airLineFraction = clamp01(fraction);
    }

    public void setBailoffPressed(boolean pressed) {
        this.bailoffPressed = pressed;
    }

    public void setDirection(@Nonnull Direction d) {
        this.direction = d;
    }

    /** Volatile read; used by stage-3 reverser interlock. */
    public float getVfsMps() { return v_fs; }

    // ==================== Internals ====================

    /** Caller must hold {@code this}. */
    private void maybeStartTickingLocked() {
        if (attached && enabled && tickHandle == null) {
            startTickingLocked();
        }
    }

    /** Caller must hold {@code this}. */
    private void startTickingLocked() {
        if (tickHandle != null) return;
        tickHandle = scheduler.scheduleAtFixedRate(this::safeTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Caller must hold {@code this}. */
    private void stopTickingLocked() {
        if (tickHandle != null) {
            tickHandle.cancel(false);
            tickHandle = null;
        }
    }

    /** Caller must hold {@code this}. */
    private void rebuildPhysicsLocked() {
        SemiRealisticSettings s = this.settings;
        if (s == null) {
            this.physics = null;
            return;
        }
        LoadScenario scenario = s.scenario != null ? s.scenario : LoadScenario.LIGHT_ENGINE;

        // Resolve loco physics per §2.4.1 precedence: Custom override → roster → scenario default.
        float locoMassKg = resolveLocoFloat(s.locoMassKg, scenario.defaultLocoMassKg(),
                rosterEntry == null ? 0f : rosterEntry.getPhysicsWeightKg(),
                scenario == LoadScenario.CUSTOM);
        float locoPowerW = resolveLocoFloat(
                s.locoPowerKw == null ? null : s.locoPowerKw * 1000f,
                scenario.defaultLocoPowerW(),
                rosterEntry == null ? 0f : rosterEntry.getPhysicsPowerKw() * 1000f,
                scenario == LoadScenario.CUSTOM);
        float locoTractiveEffortN = resolveLocoFloat(
                s.locoTractiveEffortKn == null ? null : s.locoTractiveEffortKn * 1000f,
                scenario.defaultLocoTractiveEffortN(),
                rosterEntry == null ? 0f : rosterEntry.getPhysicsTractiveEffortKn() * 1000f,
                scenario == LoadScenario.CUSTOM);

        // Roster max speed → vCap, only when populated.
        float vCapMps = Float.POSITIVE_INFINITY;
        if (rosterEntry != null && rosterEntry.getPhysicsMaxSpeedKmh() > 0f) {
            vCapMps = rosterEntry.getPhysicsMaxSpeedKmh() / 3.6f;
        }

        float additionalMassKg = scenario == LoadScenario.CUSTOM
                ? s.additionalWeightTonnes * 1000f
                // Scenario non-Custom: blend scenario default with operator additional-mass tweak.
                // Per §2.4 the operator can tune additional mass for one-off heavy/light trains
                // without changing scenario, so the persisted value overrides the scenario default
                // when it differs from the scenario's table value.
                : (s.additionalWeightTonnes != SemiRealisticSettings.DEFAULT_ADDITIONAL_TONNES
                        ? s.additionalWeightTonnes * 1000f
                        : scenario.defaultAdditionalMassKg());

        float driverPowerPct = (s.driverPowerPercent / 100f);

        float layoutScale;
        try {
            layoutScale = InstanceManager.getDefault(SignalSpeedMap.class).getLayoutScale();
            if (layoutScale <= 0f) layoutScale = 87f;
        } catch (Exception ex) {
            layoutScale = 87f; // HO fallback
        }

        // Resolve physicsTimeScale per plan §2.4.1: explicit numeric override
        // wins; otherwise "auto" (= null) resolves to JMRI's layoutScale. Per
        // plan §1.0 this multiplier applies only to prototype-physics force
        // terms (currently rolling resistance) so they are visible at
        // scale-time wall-clock — operator-controlled forces (drive, brakes)
        // are NOT multiplied.
        float physicsTimeScale = (s.physicsTimeScale != null) ? s.physicsTimeScale : layoutScale;
        if (physicsTimeScale <= 0f) physicsTimeScale = layoutScale;

        boolean useSpFwd = false;
        boolean useSpRev = false;
        if (speedProfile != null) {
            useSpFwd = speedProfile.hasForwardSpeeds();
            useSpRev = speedProfile.hasReverseSpeeds();
        }

        this.physics = new ResolvedPhysics(
                locoMassKg, locoPowerW, locoTractiveEffortN,
                additionalMassKg, driverPowerPct,
                s.rollingResistanceCoeff,
                s.brakeMaxDecel, s.airBrakeMaxDecel,
                s.dynBrakeMaxDecel, s.dynBrakeVMinMph * MPH_TO_MPS,
                vCapMps, scenario.designTopSpeedMps(),
                scenario.steamPowerCurve(), layoutScale,
                physicsTimeScale,
                useSpFwd, useSpRev);
    }

    /** Resolve the precedence "Custom override > roster > scenario default"
     *  per §2.4.1. The Custom override only applies when the active scenario
     *  IS Custom (the override values are persisted across scenario switches
     *  but only consulted when Custom is active). */
    private static float resolveLocoFloat(@CheckForNull Float customOverride,
                                          float scenarioDefault,
                                          float rosterValue,
                                          boolean scenarioIsCustom) {
        if (scenarioIsCustom && customOverride != null) {
            return customOverride;
        }
        if (rosterValue > 0f) {
            return rosterValue;
        }
        return scenarioDefault;
    }

    /**
     * Wrapper around {@link #tick} that swallows exceptions so a single
     * bad tick doesn't kill the {@link ScheduledExecutorService}'s task
     * (its cancel-on-exception semantics would silently stop integration
     * forever otherwise).
     */
    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException ex) {
            log.error("Semi-realistic engine tick threw; continuing", ex);
        }
    }

    /** Worker thread tick. */
    private void tick() {
        ResolvedPhysics p = this.physics;
        if (p == null) return;

        DccThrottle t;
        synchronized (this) {
            t = this.throttle;
        }
        if (t == null) return;

        boolean isForward = currentDirectionForward();

        // 1. Read input snapshot
        float vCap = p.vCapMps;
        float lever = throttleFraction;
        float vTarget = lever * (Float.isInfinite(vCap) ? p.designTopSpeedMps : Math.min(vCap, p.designTopSpeedMps));
        if (vTarget > vCap) vTarget = vCap;
        this.vTarget_fs = vTarget;

        float brakeM = indepBrakeFraction;
        // Stage 4–5: airLineFraction / dynBrakeFraction are zero until those
        // stages wire the polling-thread setters; the integration body
        // includes them as zero-valued summands so the same code path
        // handles every stage's force composition.
        float air = bailoffPressed ? 0.0f : airLineFraction;
        float dyn = dynBrakeFraction;

        boolean drive = (lever > 0f) && (v_fs >= 0f);

        // 2. Compute forces — TWO-CLASS FORCE MODEL (plan §1.0).
        //    Operator-controlled forces (drive, brakes) produce wall-clock
        //    perceived deceleration directly: F = wall-clock-decel × mass.
        //    Prototype-physics forces (rolling resistance) are multiplied by
        //    physicsTimeScale so they are visible at scale-time wall-clock.
        float massTotal = p.totalMass();
        if (massTotal <= 0f) return; // pathological config; nothing to do
        float pAvail  = p.locoPowerW * p.driverPowerPct;
        float teAvail = p.locoTractiveEffortN * p.driverPowerPct;
        float vGuard  = Math.max(V_GUARD_MIN_MPS, v_fs);
        float pCurve = p.steamPowerCurve ? (float) Math.pow(vGuard / Math.max(0.01f, p.designTopSpeedMps), 0.85f) : 1.0f;

        // Operator-controlled — no time-scale multiplier:
        float fDrive  = drive ? Math.min(teAvail, pAvail * pCurve / vGuard) : 0.0f;
        float fBrakeM = brakeM * p.brakeMaxDecel * massTotal;
        float fBrakeA = air    * p.airBrakeMaxDecel * massTotal;
        float taper   = p.dynBrakeVMinMps > 0 ? Math.min(1.0f, v_fs / p.dynBrakeVMinMps) : 1.0f;
        float fBrakeD = dyn * p.dynBrakeMaxDecel * p.locoMassKg * taper;

        // Prototype-physics — × physicsTimeScale to be visible at scale-time wall-clock:
        float fRr     = p.rollingResistanceCoeff * massTotal * G_MPS2 * p.physicsTimeScale;

        // 3. Integrate (no clamp on sign of a — same code path accel & decel)
        float a = (fDrive - fRr - fBrakeM - fBrakeA - fBrakeD) / massTotal;
        v_fs += a * TICK_SECONDS;
        if (v_fs < 0.0f) v_fs = 0.0f;
        if (v_fs > vCap) v_fs = vCap;
        // Pin to target when very close to avoid cosmetic oscillation once
        // we've reached steady-state; only when not actively diverging.
        if (Math.abs(v_fs - vTarget) < EPS_VFS) {
            v_fs = vTarget;
        }

        // 4. Skip emission entirely when both v and target are zero AND we
        //    have already emitted the zero-stop cycle. Avoids pointless EDT
        //    setSpeedSetting churn at idle.
        if (v_fs == 0f && vTarget == 0f && !emittedNonZero) {
            return;
        }

        // 5. Quantise to throttle setting and emit
        float setting = vfsToFraction(v_fs, isForward);
        // Track whether we just emitted a non-zero so the next idle tick
        // can emit one final zero before going quiet.
        if (setting > 0f) {
            emittedNonZero = true;
        } else if (v_fs == 0f) {
            // emitting a zero now — next idle tick can be skipped.
            emittedNonZero = false;
        }

        final float settingFinal = setting;
        ThreadingUtil.runOnGUIEventually(() -> {
            // Re-read throttle under monitor to handle the rare detach race
            DccThrottle current;
            synchronized (this) {
                current = this.throttle;
            }
            if (current != null) {
                current.setSpeedSetting(settingFinal);
            }
        });
    }

    private boolean currentDirectionForward() {
        Direction d = this.direction;
        // Treat NEUTRAL as forward for v_fs ↔ throttle conversions; physical
        // direction with zero force still requires a sign for the speed
        // profile lookup. NEUTRAL implies F_drive == 0 anyway.
        return d != Direction.REVERSE;
    }

    // -------- Fraction ↔ v_fs conversion (uses speed profile when valid) --------

    /**
     * Convert a throttle fraction [0, 1] to prototype m/s using the
     * speed profile when it has data for {@code isForward}; otherwise
     * use a linear fallback against the active scenario's design top
     * speed (or the roster vCap if that is set).
     */
    private float fractionToVfsMps(float fraction, boolean isForward) {
        ResolvedPhysics p = this.physics;
        if (p == null) return 0f;
        boolean useProfile = (isForward ? p.useSpeedProfileForward : p.useSpeedProfileReverse);
        if (useProfile && speedProfile != null) {
            float layoutMms = speedProfile.getSpeed(fraction, isForward);
            // mm/s layout × scale = mm/s prototype = m/s prototype × 1000
            // → m/s prototype = mm/s layout × scale / 1000
            return layoutMms * p.layoutScale / 1000.0f;
        }
        float top = Float.isInfinite(p.vCapMps) ? p.designTopSpeedMps : Math.min(p.vCapMps, p.designTopSpeedMps);
        return fraction * top;
    }

    /**
     * Convert a prototype m/s velocity to a throttle setting [0, 1] using
     * the speed profile when it has data; otherwise linear fallback. The
     * setting is clamped to [0, 1] regardless of fallback path so a
     * runaway integrator can never push a value outside the DCC range.
     */
    private float vfsToFraction(float vfsMps, boolean isForward) {
        ResolvedPhysics p = this.physics;
        if (p == null) return 0f;
        boolean useProfile = (isForward ? p.useSpeedProfileForward : p.useSpeedProfileReverse);
        if (useProfile && speedProfile != null) {
            // m/s prototype → mm/s layout: layoutMms = vfs × 1000 / scale
            float layoutMms = vfsMps * 1000.0f / p.layoutScale;
            float setting = speedProfile.getThrottleSetting(layoutMms, isForward);
            return clamp01(setting);
        }
        float top = Float.isInfinite(p.vCapMps) ? p.designTopSpeedMps : Math.min(p.vCapMps, p.designTopSpeedMps);
        if (top <= 0f) return 0f;
        return clamp01(vfsMps / top);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static final Logger log = LoggerFactory.getLogger(SemiRealisticThrottleEngine.class);
}
