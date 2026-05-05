package jmri.jmrit.usb;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import jmri.DccThrottle;
import jmri.InvokeOnLayoutThread;
import jmri.util.ThreadingUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EngineDriver-aligned step-rate throttle scheduler for the RailDriver
 * semi-realistic mode.
 * <p>
 * The throttle lever sets a target decoder speed step (integer). A
 * self-rescheduling callback on the JMRI layout thread walks the current
 * speed step toward the target one step at a time, with a configurable
 * delay between steps. Brakes, load, and air-line state modify the delay
 * (and thereby the ramp rate) rather than applying continuous physics.
 * <p>
 * Threading contract: <b>all public methods must be called on the layout
 * thread</b> (enforced by {@code @InvokeOnLayoutThread} annotations and
 * runtime assertions). The engine contains no {@code volatile},
 * {@code synchronized}, {@code ScheduledExecutorService}, or
 * {@code java.util.Timer} — all scheduling uses
 * {@link ThreadingUtil#runOnLayoutDelayed}.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Construct (DETACHED state).
 *   <li>{@link #attachThrottle}: seed current speed from throttle,
 *       capture settings, transition to ATTACHED.
 *   <li>Input setters ({@link #setThrottleFraction}, etc.) call
 *       {@link #recomputeTarget} to start/adjust the ramp.
 *   <li>{@link #detachThrottle}: bump all epochs, transition to DETACHED.
 *   <li>{@link #dispose}: same as detach; engine is unusable after.
 * </ol>
 * <p>
 * Algorithm reference: EngineDriver {@code throttle_semi_realistic.java}
 * (SHA {@code 5e722d38}).
 */
public final class SemiRealisticThrottleEngine {

    /** Reverser direction. */
    public enum Direction { FORWARD, NEUTRAL, REVERSE }

    // ======================== State ========================

    private boolean attached = false;

    @CheckForNull private DccThrottle throttle;
    @CheckForNull private SemiRealisticSettings settings;

    /** Number of non-stop speed steps for the attached throttle (e.g. 126
     *  for 128-step mode, 28 for 28-step mode). Derived from the throttle
     *  at attach time. */
    private int maxSpeedSteps = 126;

    private int currentSpeedStep = 0;
    private int targetSpeedStep = 0;

    /** Multiplicative modifier on inter-step delay. Sign indicates
     *  direction: positive = accelerate, negative = decelerate. Magnitude
     *  scales the base delay (1.0 = unmodified). Computed by
     *  {@link #recomputeTarget}. */
    private double targetAcceleration = 0;

    /** Epoch counter for the ramp pipeline. Bumped on every
     *  {@link #recomputeTarget} and {@link #detachThrottle} call; stale
     *  callbacks with a non-matching epoch no-op. */
    private int rampEpoch = 0;

    /** Epoch counter for the deferred-emit pipeline. */
    private int deferredEmitEpoch = 0;

    /** Timestamp of the last actual {@code setSpeedSetting} call, for
     *  deferred-emit throttling. */
    private long lastEmitTimeMs = 0;

    // --- Latest physical inputs (written by input setters) ---
    private float throttleFraction      = 0.0f;
    private float indepBrakeFraction    = 0.0f;
    private float dynBrakeFraction      = 0.0f;
    private float airLineFraction       = 0.0f;
    private boolean bailoffPressed      = false;
    private Direction direction         = Direction.NEUTRAL;

    /** Desired direction stored when a reverser flip is requested while
     *  {@code currentSpeedStep > 0}. Applied automatically when the loco
     *  reaches step 0. {@code null} means no deferred flip is pending. */
    @CheckForNull private Direction pendingDirection = null;

    /** Epoch counter for the air-repeater pipeline (Phase 5). Bumped by
     *  {@link #emergencyHalt} and {@link #detachThrottle} so stale air
     *  callbacks cancel. */
    private int airEpoch = 0;

    // ======================== Lifecycle ========================

    /**
     * Binds the engine to a DCC throttle. Seeds {@code currentSpeedStep}
     * from the throttle's current speed setting. Captures a defensive
     * copy of the settings. Transitions to ATTACHED.
     *
     * @param t the DCC throttle to drive
     */
    @InvokeOnLayoutThread
    public void attachThrottle(@Nonnull DccThrottle t) {
        this.throttle = t;

        // Derive max speed steps from the throttle's speed-step mode.
        try {
            float increment = t.getSpeedIncrement();
            if (increment > 0f) {
                this.maxSpeedSteps = Math.round(1.0f / increment);
            }
        } catch (Exception ex) {
            log.warn("Could not derive maxSpeedSteps from throttle; using 126", ex);
            this.maxSpeedSteps = 126;
        }
        if (this.maxSpeedSteps <= 0) this.maxSpeedSteps = 126;

        // Seed current speed from what the throttle is already doing.
        float currentSetting = t.getSpeedSetting();
        if (currentSetting < 0f) currentSetting = 0f; // E-stop → treat as zero
        this.currentSpeedStep = Math.round(currentSetting * maxSpeedSteps);
        this.targetSpeedStep = this.currentSpeedStep;
        this.targetAcceleration = 0;
        this.attached = true;
        this.lastEmitTimeMs = 0;

        log.debug("Engine attached: loco={}, maxSteps={}, seeded step={}",
                t.getLocoAddress(), maxSpeedSteps, currentSpeedStep);
    }

    /**
     * Releases the throttle reference and cancels all pending callbacks
     * by bumping all epoch counters.
     */
    @InvokeOnLayoutThread
    public void detachThrottle() {
        rampEpoch++;
        deferredEmitEpoch++;
        airEpoch++;
        this.throttle = null;
        this.settings = null;
        this.attached = false;
        this.currentSpeedStep = 0;
        this.targetSpeedStep = 0;
        this.targetAcceleration = 0;
        this.pendingDirection = null;
        log.debug("Engine detached");
    }

    /**
     * Pushes a new settings snapshot. Takes a defensive copy so the
     * caller can continue modifying their instance. Calls
     * {@link #recomputeTarget} so new values take effect on the next
     * ramp tick.
     */
    @InvokeOnLayoutThread
    public void updateSettings(@Nonnull SemiRealisticSettings s) {
        this.settings = new SemiRealisticSettings(s);
        recomputeTarget();
    }

    /** Whether the engine is currently driving the loco (attached with
     *  settings). Safe to call from any thread — reads a plain boolean. */
    public boolean isDriving() {
        return attached && settings != null;
    }

    /** Shuts down the engine. Equivalent to {@link #detachThrottle}. */
    @InvokeOnLayoutThread
    public void dispose() {
        detachThrottle();
    }

    /**
     * Emergency halt: bumps all three pipeline epochs (ramp, air,
     * deferred-emit) to cancel any pending callbacks, resets speed state,
     * and issues a DCC E-Stop ({@code setSpeedSetting(-1)}) directly.
     * <p>
     * Recovery is automatic — the next lever-change event calls
     * {@link #recomputeTarget} which starts a fresh ramp from step 0.
     */
    @InvokeOnLayoutThread
    public void emergencyHalt() {
        rampEpoch++;
        deferredEmitEpoch++;
        airEpoch++;
        currentSpeedStep = 0;
        targetSpeedStep = 0;
        targetAcceleration = 0;
        pendingDirection = null;
        if (throttle != null) {
            throttle.setSpeedSetting(-1f);
        }
        log.info("Engine: emergency halt issued");
    }

    // ======================== Input setters ========================
    // Called from the layout thread (via ThreadingUtil.runOnLayout* from
    // the polling thread). Each stores the value and calls recomputeTarget.

    @InvokeOnLayoutThread
    public void setThrottleFraction(float fraction) {
        this.throttleFraction = clamp01(fraction);
        recomputeTarget();
    }

    @InvokeOnLayoutThread
    public void setIndepBrakeFraction(float fraction) {
        this.indepBrakeFraction = clamp01(fraction);
        recomputeTarget();
    }

    @InvokeOnLayoutThread
    public void setDynBrakeFraction(float fraction) {
        this.dynBrakeFraction = clamp01(fraction);
        recomputeTarget();
    }

    @InvokeOnLayoutThread
    public void setAirLineFraction(float fraction) {
        this.airLineFraction = clamp01(fraction);
        recomputeTarget();
    }

    @InvokeOnLayoutThread
    public void setBailoffPressed(boolean pressed) {
        this.bailoffPressed = pressed;
        recomputeTarget();
    }

    /**
     * Sets the reverser direction with interlock: NEUTRAL is always
     * allowed (forces coast-to-stop). Forward↔Reverse is only allowed
     * when {@code currentSpeedStep == 0}; otherwise the desired direction
     * is stored as {@link #pendingDirection} and applied automatically
     * when the loco reaches step 0.
     */
    @InvokeOnLayoutThread
    public void setDirection(@Nonnull Direction d) {
        if (d == Direction.NEUTRAL) {
            // NEUTRAL always accepted immediately.
            this.direction = d;
            this.pendingDirection = null;
            recomputeTarget();
        } else if (currentSpeedStep == 0) {
            // At standstill — accept the direction change immediately.
            this.direction = d;
            this.pendingDirection = null;
            recomputeTarget();
        } else {
            // Moving — defer the direction change until loco stops.
            this.pendingDirection = d;
            log.debug("Direction change to {} deferred (currentSpeedStep={})", d, currentSpeedStep);
        }
    }

    /** Returns the current speed step (0..maxSpeedSteps). */
    public int getCurrentSpeedStep() { return currentSpeedStep; }

    /** Returns the target speed step (0..maxSpeedSteps). */
    public int getTargetSpeedStep() { return targetSpeedStep; }

    /** Returns the max speed steps for the attached throttle. */
    public int getMaxSpeedSteps() { return maxSpeedSteps; }

    /** Returns the active direction. */
    public Direction getDirection() { return direction; }

    /** Returns the pending direction (deferred reverser flip), or null. */
    @CheckForNull
    public Direction getPendingDirection() { return pendingDirection; }

    // ======================== Core algorithm ========================

    /** EngineDriver's maxBrake constant — maximum brake effectiveness as a
     *  fraction. At 1.0 (100%) braking is "instant zero". Default 0.70. */
    private static final double MAX_BRAKE = 0.70;

    /** EngineDriver's maxBrakeUnderPower — softer braking when throttle is
     *  also applied. Default = maxBrake - 0.20. */
    private static final double MAX_BRAKE_UNDER_POWER = MAX_BRAKE - 0.20;

    /**
     * EngineDriver's {@code getBrakeDecimalPcnt} — non-linear brake curve.
     * Returns a value where 1.0 = no braking, approaching 0.0 = full braking.
     * <p>
     * Formula: {@code 1 - (sqrt(step) * step * maxBrake / (sqrt(steps) * steps * maxBrake) * maxBrake)}
     *
     * @param step  current brake notch (0 = released)
     * @param steps total number of brake notches
     * @param maxBrakeDecimal maximum brake effectiveness (e.g. 0.70)
     * @return brake percentage where 1.0 = free-rolling, 0.0 = full stop
     */
    static double getBrakeDecimalPcnt(double step, double steps, double maxBrakeDecimal) {
        if (steps <= 0 || step <= 0) return 1.0;
        double max = Math.sqrt(steps) * steps * maxBrakeDecimal;
        return 1.0 - (Math.sqrt(step) * step * maxBrakeDecimal / max * maxBrakeDecimal);
    }

    /**
     * Computes the effective dynamic brake step with low-speed taper.
     * Below {@code dynBrakeMinSpeedStep}, the dynamic brake fades linearly
     * to zero at speed 0.
     *
     * @param currentSpeed current speed step
     * @param dynBrakeStep raw dynamic brake notch (0..numberOfBrakeSteps)
     * @param dynBrakeMinSpeedStep threshold below which taper applies
     * @return effective dynamic brake step (may be fractional due to taper)
     */
    static double effectiveDynBrakeStep(int currentSpeed, int dynBrakeStep,
                                        int dynBrakeMinSpeedStep) {
        if (dynBrakeStep <= 0) return 0.0;
        if (dynBrakeMinSpeedStep <= 0) return dynBrakeStep;
        if (currentSpeed >= dynBrakeMinSpeedStep) return dynBrakeStep;
        // Linear taper: at currentSpeed == 0, effect is 0;
        // at currentSpeed == dynBrakeMinSpeedStep, effect is full.
        double taper = (double) currentSpeed / (double) dynBrakeMinSpeedStep;
        return dynBrakeStep * taper;
    }

    /**
     * Computes the target speed step and acceleration from the current
     * input state, bumps the ramp epoch, and posts a fresh ramp callback.
     * Matches EngineDriver's {@code setTargetSpeed} logic.
     */
    private void recomputeTarget() {
        if (!attached || settings == null) return;

        int sliderSpeed = Math.round(throttleFraction * maxSpeedSteps);
        if (sliderSpeed > maxSpeedSteps) sliderSpeed = maxSpeedSteps;
        if (sliderSpeed < 0) sliderSpeed = 0;

        // Direction NEUTRAL short-circuits to coast-to-stop.
        if (direction == Direction.NEUTRAL) {
            targetSpeedStep = 0;
            targetAcceleration = -1.0;
        } else {
            targetSpeedStep = sliderSpeed;
            targetAcceleration = 1.0; // default: no modifier
        }

        // --- Compute effective brake from all sources ---
        // Independent brake: quantise lever fraction to notches.
        int indepNotch = Math.round(indepBrakeFraction * settings.numberOfBrakeSteps);
        // Dynamic brake: quantise and apply low-speed taper.
        int dynNotch = Math.round(dynBrakeFraction * settings.numberOfBrakeSteps);
        double effDynStep = effectiveDynBrakeStep(currentSpeedStep, dynNotch,
                settings.dynBrakeMinSpeedStep);
        // Air brake: convert air line fraction (1.0 = released, 0.0 = full app)
        // to brake steps. airLineFraction is 0..1 where 0=no braking, 1=full braking
        // (same convention as indep/dyn). Convert to EngineDriver's air-line-as-brake.
        double airBrakeStep = Math.round(airLineFraction * settings.numberOfBrakeSteps);

        // Bail-off: releases all loco-side braking (indep, dyn, loco share of auto).
        // Only car-brake retardation continues. At light engine (load=0), bail-off
        // releases all braking entirely.
        double effIndepNotch = bailoffPressed ? 0 : indepNotch;
        double effAirStep = bailoffPressed ? 0 : airBrakeStep;
        effDynStep = bailoffPressed ? 0 : effDynStep;

        // Compute brake percentages (EngineDriver convention: 1.0 = no braking).
        double indepBrakePcnt = getBrakeDecimalPcnt(effIndepNotch,
                settings.numberOfBrakeSteps, MAX_BRAKE);
        double dynBrakePcnt = getBrakeDecimalPcnt(effDynStep,
                settings.numberOfBrakeSteps, MAX_BRAKE);
        double airBrakePcnt = getBrakeDecimalPcnt(effAirStep,
                settings.numberOfBrakeSteps, MAX_BRAKE);

        // Effective brake = min of all sources (smaller = more braking).
        double effectiveBrake = Math.min(indepBrakePcnt,
                Math.min(dynBrakePcnt, airBrakePcnt));

        // --- Apply brake to target/acceleration (EngineDriver §3.5) ---
        if (direction != Direction.NEUTRAL) {
            if (effectiveBrake >= 1.0) {
                // Regime A: no brake force — ramp toward slider.
                if (targetSpeedStep > currentSpeedStep) {
                    targetAcceleration = 1.0;
                } else if (targetSpeedStep < currentSpeedStep) {
                    targetAcceleration = -1.0;
                } else {
                    targetAcceleration = 0;
                }
            } else if (targetSpeedStep == 0) {
                // Regime B: throttle at zero + brake applied.
                targetAcceleration = -1.0 * effectiveBrake;
            } else {
                // Regime C: throttle and brake both active.
                // Brake clips the target speed.
                int brakeReducedTarget = (int) Math.round(
                        sliderSpeed - sliderSpeed * (1.0 - effectiveBrake));
                if (brakeReducedTarget < 0) brakeReducedTarget = 0;
                targetSpeedStep = brakeReducedTarget;

                if (targetSpeedStep <= currentSpeedStep) {
                    // Slowing down under power — softer braking
                    targetAcceleration = -1.0 * (1.0 - effectiveBrake * MAX_BRAKE_UNDER_POWER);
                } else {
                    // Still accelerating but slower
                    targetAcceleration = 1.0 + (1.0 - effectiveBrake * MAX_BRAKE);
                }
            }
        }

        // --- Load scaling on targetAcceleration (Phase 6 will provide UI) ---
        // For now, loadSliderPosition is always 0 (light engine), so this
        // is a no-op. The formula is wired so Phase 6 just needs to set
        // the setting value.
        if (settings.loadSliderPosition > 0) {
            targetAcceleration = targetAcceleration
                    * getLoadPcnt(settings.loadSliderPosition,
                            settings.numberOfLoadSteps, settings.maxLoadPcnt);
        }

        // Start a fresh ramp if there is work to do.
        if (currentSpeedStep != targetSpeedStep) {
            rampEpoch++;
            final int epoch = rampEpoch;
            int delayMs = computeRampDelay();
            ThreadingUtil.runOnLayoutDelayed(() -> rampCallback(epoch), delayMs);
        }
    }

    /**
     * EngineDriver's quadratic load formula.
     * {@code ((load² × (maxLoadPcnt − 100)) + 100) / 100}
     * where {@code load = step / numberOfLoadSteps}.
     *
     * @param step current load slider position (0 = light engine)
     * @param steps total load slider positions
     * @param maxLoadPcnt maximum load percentage (e.g. 1000 = 10×)
     * @return load multiplier (1.0 at step 0, up to maxLoadPcnt/100 at max)
     */
    static double getLoadPcnt(int step, int steps, int maxLoadPcnt) {
        if (step <= 0 || steps <= 0) return 1.0;
        double load = (double) step / (double) steps;
        return ((load * load * (maxLoadPcnt - 100)) + 100) / 100.0;
    }

    /**
     * Computes the inter-step delay in milliseconds.
     * {@code Δt = baseDelay × |targetAcceleration|}
     * where baseDelay is accel or decel depending on direction.
     */
    private int computeRampDelay() {
        if (settings == null) return 300;
        int baseDelay;
        if (targetAcceleration > 0) {
            baseDelay = settings.baseAccelDelayMs;
        } else {
            baseDelay = settings.baseDecelDelayMs;
        }
        double magnitude = Math.abs(targetAcceleration);
        if (magnitude < 0.01) magnitude = 1.0;

        // EngineDriver: Δt = baseDelay × targetAcceleration
        // targetAcceleration > 1 means MORE delay (slower ramp — heavier load)
        // targetAcceleration < 1 (but > 0) means LESS delay (faster ramp — braking)
        // For braking, we use inverse: stronger brake = smaller delay = faster stop
        if (targetAcceleration < 0) {
            // Braking: more brake notches = faster deceleration = shorter delay
            // magnitude of -1 = coast (baseDecelDelay), -4 = full brake (baseDecelDelay/4)
            return Math.max(1, (int) Math.round(baseDelay / magnitude));
        } else {
            // Accelerating: load multiplier increases delay (slower accel)
            return Math.max(1, (int) Math.round(baseDelay * magnitude));
        }
    }

    /**
     * Self-rescheduling ramp callback. Steps {@code currentSpeedStep}
     * toward {@code targetSpeedStep} by one, emits the throttle setting,
     * and re-posts itself for the next step.
     */
    private void rampCallback(int epoch) {
        if (epoch != rampEpoch) return; // stale — cancelled by a newer recomputeTarget
        if (!attached || throttle == null || settings == null) return;
        if (currentSpeedStep == targetSpeedStep) return; // arrived

        // Step by 1 in the appropriate direction
        if (currentSpeedStep < targetSpeedStep) {
            currentSpeedStep++;
        } else {
            currentSpeedStep--;
        }

        // Emit the new speed setting
        emitSpeedSetting(epoch);

        // Apply deferred direction change when we reach standstill.
        if (currentSpeedStep == 0 && pendingDirection != null) {
            direction = pendingDirection;
            pendingDirection = null;
            log.debug("Deferred direction change applied: {}", direction);
            recomputeTarget();
            return; // recomputeTarget starts a fresh ramp if needed
        }

        // Re-post if not yet at target
        if (currentSpeedStep != targetSpeedStep) {
            int delayMs = computeRampDelay();
            ThreadingUtil.runOnLayoutDelayed(() -> rampCallback(epoch), delayMs);
        }
    }

    /**
     * Emits a {@code setSpeedSetting} call, with deferred-emit throttling
     * to respect {@code minEmitIntervalMs}.
     */
    private void emitSpeedSetting(int rampEpochAtCall) {
        if (throttle == null || settings == null) return;

        float setting = (float) currentSpeedStep / (float) maxSpeedSteps;
        if (setting < 0f) setting = 0f;
        if (setting > 1f) setting = 1f;

        long now = System.currentTimeMillis();
        long elapsed = now - lastEmitTimeMs;

        if (elapsed >= settings.minEmitIntervalMs || lastEmitTimeMs == 0) {
            // Emit immediately
            throttle.setSpeedSetting(setting);
            lastEmitTimeMs = now;
        } else {
            // Defer to respect minimum interval
            deferredEmitEpoch++;
            final int deferEpoch = deferredEmitEpoch;
            final float deferredSetting = setting;
            long remaining = settings.minEmitIntervalMs - elapsed;
            ThreadingUtil.runOnLayoutDelayed(() -> {
                if (deferEpoch != deferredEmitEpoch) return; // stale
                if (throttle != null) {
                    throttle.setSpeedSetting(deferredSetting);
                    lastEmitTimeMs = System.currentTimeMillis();
                }
            }, (int) remaining);
        }
    }

    // ======================== Utilities ========================

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static final Logger log = LoggerFactory.getLogger(SemiRealisticThrottleEngine.class);
}
