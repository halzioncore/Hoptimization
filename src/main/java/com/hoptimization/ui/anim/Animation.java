package com.hoptimization.ui.anim;

/**
 * Frame-rate independent scalar animation driven by {@link System#nanoTime()}.
 * Supports mid-flight retargeting from the current interpolated value and global reduce-motion.
 */
public class Animation {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static volatile boolean reduceMotion = false;

    private float fromValue;
    private float targetValue;
    private long startTimeNanos;
    private long defaultDurationNanos;
    private long activeDurationNanos;
    private Easing easing;

    /**
     * Creates an animation settled at {@code initialValue} with the given duration token.
     *
     * @param initialValue initial value
     * @param duration     duration token providing timing and default easing
     */
    public Animation(float initialValue, AnimDuration duration) {
        this(initialValue, initialValue, duration, safeEasing(duration));
    }

    /**
     * Creates an animation settled at {@code initialValue} with the given duration and easing.
     *
     * @param initialValue initial value
     * @param duration     duration token
     * @param easing       easing curve
     */
    public Animation(float initialValue, AnimDuration duration, Easing easing) {
        this(initialValue, initialValue, duration, easing);
    }

    /**
     * Creates and starts an animation from {@code from} to {@code to} using the token's default easing.
     *
     * @param from     starting value
     * @param to       target value
     * @param duration duration token
     */
    public Animation(float from, float to, AnimDuration duration) {
        this(from, to, duration, safeEasing(duration));
    }

    /**
     * Creates and starts an animation from {@code from} to {@code to}.
     *
     * @param from     starting value
     * @param to       target value
     * @param duration duration token
     * @param easing   easing curve
     */
    public Animation(float from, float to, AnimDuration duration, Easing easing) {
        this(from, to, duration != null ? duration.getMillis() : 0L, easing);
    }

    /**
     * Creates and starts an animation from {@code from} to {@code to} with a millisecond duration.
     *
     * @param from           starting value
     * @param to             target value
     * @param durationMillis duration in milliseconds
     * @param easing         easing curve
     */
    public Animation(float from, float to, long durationMillis, Easing easing) {
        long safeNanos = Math.max(0L, durationMillis) * NANOS_PER_MILLI;
        this.fromValue = from;
        this.targetValue = to;
        this.defaultDurationNanos = safeNanos;
        this.activeDurationNanos = safeNanos;
        this.easing = easing != null ? easing : Easing.EASE_OUT_CUBIC;
        this.startTimeNanos = System.nanoTime();
    }

    /**
     * Returns whether global reduce-motion mode is enabled.
     *
     * @return {@code true} when all animation durations are effectively zero
     */
    public static boolean isReduceMotion() {
        return reduceMotion;
    }

    /**
     * Enables or disables global reduce-motion mode.
     *
     * @param enabled {@code true} to skip animations immediately
     */
    public static void setReduceMotion(boolean enabled) {
        reduceMotion = enabled;
    }

    /**
     * Returns the current animated value at {@link System#nanoTime()}.
     *
     * @return current interpolated value
     */
    public float get() {
        return get(System.nanoTime());
    }

    /**
     * Returns the current animated value at the given timestamp in nanoseconds.
     *
     * @param nowNanos timestamp from {@link System#nanoTime()}
     * @return current interpolated value
     */
    public float get(long nowNanos) {
        if (reduceMotion || activeDurationNanos <= 0L || Float.compare(fromValue, targetValue) == 0) {
            return targetValue;
        }
        long elapsed = nowNanos - startTimeNanos;
        if (elapsed <= 0L) {
            return fromValue;
        }
        if (elapsed >= activeDurationNanos) {
            return targetValue;
        }
        float raw = (float) ((double) elapsed / (double) activeDurationNanos);
        float eased = easing.apply(raw);
        return fromValue + (targetValue - fromValue) * eased;
    }

    /**
     * Smoothly retargets the animation from its current value to {@code newTarget} using the default duration.
     * Calling this repeatedly with the same target value is a no-op so frame loops do not reset progress.
     *
     * @param newTarget new target value
     */
    public void setTarget(float newTarget) {
        setTargetNanos(newTarget, defaultDurationNanos);
    }

    /**
     * Smoothly retargets the animation from its current value to {@code newTarget} over the specified token.
     * Does not overwrite the default duration stored on this animation instance.
     *
     * @param newTarget new target value
     * @param duration  duration token for this transition
     */
    public void setTarget(float newTarget, AnimDuration duration) {
        long nanos = duration != null ? duration.getNanos() : defaultDurationNanos;
        setTargetNanos(newTarget, nanos);
    }

    /**
     * Smoothly retargets the animation from its current value to {@code newTarget} over {@code durationMillis}.
     * Ideal for 0.75x close animations without changing the open duration.
     *
     * @param newTarget      new target value
     * @param durationMillis transition duration in milliseconds
     */
    public void setTarget(float newTarget, long durationMillis) {
        setTargetNanos(newTarget, Math.max(0L, durationMillis) * NANOS_PER_MILLI);
    }

    private void setTargetNanos(float newTarget, long transitionNanos) {
        if (Float.compare(newTarget, targetValue) == 0) {
            return;
        }
        long now = System.nanoTime();
        if (reduceMotion || transitionNanos <= 0L) {
            snapTo(newTarget);
            return;
        }
        // Snapshot the current interpolated value so mid-flight reversals never jump.
        this.fromValue = get(now);
        this.targetValue = newTarget;
        this.activeDurationNanos = transitionNanos;
        this.startTimeNanos = now;
    }

    /**
     * Immediately snaps the animation to {@code value} without transitioning.
     *
     * @param value target value to snap to
     */
    public void snapTo(float value) {
        this.fromValue = value;
        this.targetValue = value;
        this.startTimeNanos = System.nanoTime();
    }

    /**
     * Restarts the animation explicitly from {@code from} to {@code to}.
     *
     * @param from starting value
     * @param to   target value
     */
    public void reset(float from, float to) {
        this.fromValue = from;
        this.targetValue = to;
        this.activeDurationNanos = defaultDurationNanos;
        this.startTimeNanos = System.nanoTime();
    }

    /**
     * Returns whether the animation has reached its target value.
     *
     * @return {@code true} if finished or reduce-motion is enabled
     */
    public boolean isFinished() {
        return isFinished(System.nanoTime());
    }

    /**
     * Returns whether the animation has reached its target value at {@code nowNanos}.
     *
     * @param nowNanos timestamp from {@link System#nanoTime()}
     * @return {@code true} if finished or reduce-motion is enabled
     */
    public boolean isFinished(long nowNanos) {
        if (reduceMotion || activeDurationNanos <= 0L || Float.compare(fromValue, targetValue) == 0) {
            return true;
        }
        return (nowNanos - startTimeNanos) >= activeDurationNanos;
    }

    /**
     * Returns normalized elapsed progress in [0, 1].
     *
     * @return normalized linear time progress
     */
    public float getProgress() {
        if (reduceMotion || activeDurationNanos <= 0L || Float.compare(fromValue, targetValue) == 0) {
            return 1.0f;
        }
        long elapsed = System.nanoTime() - startTimeNanos;
        if (elapsed <= 0L) {
            return 0.0f;
        }
        if (elapsed >= activeDurationNanos) {
            return 1.0f;
        }
        return (float) ((double) elapsed / (double) activeDurationNanos);
    }

    /**
     * Returns the current target value.
     *
     * @return target value
     */
    public float getTarget() {
        return targetValue;
    }

    /**
     * Returns the starting value of the current transition segment.
     *
     * @return starting value
     */
    public float getFrom() {
        return fromValue;
    }

    /**
     * Updates the default duration token for future {@link #setTarget(float)} calls.
     *
     * @param duration new duration token
     */
    public void setDuration(AnimDuration duration) {
        if (duration != null) {
            this.defaultDurationNanos = duration.getNanos();
        }
    }

    /**
     * Updates the easing curve used for interpolation.
     *
     * @param easing new easing curve
     */
    public void setEasing(Easing easing) {
        if (easing != null) {
            this.easing = easing;
        }
    }

    private static Easing safeEasing(AnimDuration duration) {
        return duration != null ? duration.getDefaultEasing() : Easing.EASE_OUT_CUBIC;
    }
}
