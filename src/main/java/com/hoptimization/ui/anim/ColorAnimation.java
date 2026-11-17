package com.hoptimization.ui.anim;

/**
 * Zero-allocation ARGB channel-by-channel color animation driven by {@link System#nanoTime()}.
 * Supports mid-flight retargeting without quantization drift and respects {@link Animation#isReduceMotion()}.
 */
public class ColorAnimation {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private float fromA;
    private float fromR;
    private float fromG;
    private float fromB;

    private int targetArgb;
    private int targetA;
    private int targetR;
    private int targetG;
    private int targetB;

    private long startTimeNanos;
    private long defaultDurationNanos;
    private long activeDurationNanos;
    private Easing easing;

    /**
     * Creates a color animation settled at {@code initialArgb}.
     *
     * @param initialArgb initial ARGB color
     * @param duration    duration token providing timing and default easing
     */
    public ColorAnimation(int initialArgb, AnimDuration duration) {
        this(initialArgb, initialArgb, duration, safeEasing(duration));
    }

    /**
     * Creates and starts a color animation from {@code fromArgb} to {@code toArgb}.
     *
     * @param fromArgb starting ARGB color
     * @param toArgb   target ARGB color
     * @param duration duration token
     */
    public ColorAnimation(int fromArgb, int toArgb, AnimDuration duration) {
        this(fromArgb, toArgb, duration, safeEasing(duration));
    }

    /**
     * Creates and starts a color animation from {@code fromArgb} to {@code toArgb}.
     *
     * @param fromArgb starting ARGB color
     * @param toArgb   target ARGB color
     * @param duration duration token
     * @param easing   easing curve
     */
    public ColorAnimation(int fromArgb, int toArgb, AnimDuration duration, Easing easing) {
        this(fromArgb, toArgb, duration != null ? duration.getMillis() : 0L, easing);
    }

    /**
     * Creates and starts a color animation with an explicit millisecond duration.
     *
     * @param fromArgb       starting ARGB color
     * @param toArgb         target ARGB color
     * @param durationMillis duration in milliseconds
     * @param easing         easing curve
     */
    public ColorAnimation(int fromArgb, int toArgb, long durationMillis, Easing easing) {
        long safeNanos = Math.max(0L, durationMillis) * NANOS_PER_MILLI;
        this.defaultDurationNanos = safeNanos;
        this.activeDurationNanos = safeNanos;
        this.easing = easing != null ? easing : Easing.EASE_OUT_CUBIC;
        setFromChannels(fromArgb);
        setTargetChannels(toArgb);
        this.startTimeNanos = System.nanoTime();
    }

    /**
     * Returns the current interpolated ARGB color at {@link System#nanoTime()}.
     *
     * @return packed ARGB color
     */
    public int get() {
        return get(System.nanoTime());
    }

    /**
     * Returns the current interpolated ARGB color at {@code nowNanos}.
     *
     * @param nowNanos timestamp from {@link System#nanoTime()}
     * @return packed ARGB color
     */
    public int get(long nowNanos) {
        if (Animation.isReduceMotion() || activeDurationNanos <= 0L) {
            return targetArgb;
        }
        long elapsed = nowNanos - startTimeNanos;
        if (elapsed >= activeDurationNanos) {
            return targetArgb;
        }
        float eased = computeEasedProgress(elapsed);
        int a = clampByte(Math.round(fromA + (targetA - fromA) * eased));
        int r = clampByte(Math.round(fromR + (targetR - fromR) * eased));
        int g = clampByte(Math.round(fromG + (targetG - fromG) * eased));
        int b = clampByte(Math.round(fromB + (targetB - fromB) * eased));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Smoothly transitions from the current color to {@code newTargetArgb} using the default duration.
     * Repeated calls with the same target ARGB value are no-ops.
     *
     * @param newTargetArgb target ARGB color
     */
    public void setTarget(int newTargetArgb) {
        setTargetNanos(newTargetArgb, defaultDurationNanos);
    }

    /**
     * Smoothly transitions from the current color to {@code newTargetArgb} using {@code duration}.
     *
     * @param newTargetArgb target ARGB color
     * @param duration      transition duration token
     */
    public void setTarget(int newTargetArgb, AnimDuration duration) {
        long nanos = duration != null ? duration.getNanos() : defaultDurationNanos;
        setTargetNanos(newTargetArgb, nanos);
    }

    /**
     * Smoothly transitions from the current color to {@code newTargetArgb} over {@code durationMillis}.
     *
     * @param newTargetArgb  target ARGB color
     * @param durationMillis transition duration in milliseconds
     */
    public void setTarget(int newTargetArgb, long durationMillis) {
        setTargetNanos(newTargetArgb, Math.max(0L, durationMillis) * NANOS_PER_MILLI);
    }

    private void setTargetNanos(int newTargetArgb, long transitionNanos) {
        if (newTargetArgb == targetArgb) {
            return;
        }
        long now = System.nanoTime();
        if (Animation.isReduceMotion() || transitionNanos <= 0L) {
            snapTo(newTargetArgb);
            return;
        }
        // Snapshot unrounded float channels so rapid retargeting does not accumulate rounding drift.
        float eased = computeEasedProgress(now - startTimeNanos);
        this.fromA = fromA + (targetA - fromA) * eased;
        this.fromR = fromR + (targetR - fromR) * eased;
        this.fromG = fromG + (targetG - fromG) * eased;
        this.fromB = fromB + (targetB - fromB) * eased;
        setTargetChannels(newTargetArgb);
        this.activeDurationNanos = transitionNanos;
        this.startTimeNanos = now;
    }

    /**
     * Immediately snaps to {@code argb} without transitioning.
     *
     * @param argb target ARGB color
     */
    public void snapTo(int argb) {
        setFromChannels(argb);
        setTargetChannels(argb);
        this.startTimeNanos = System.nanoTime();
    }

    /**
     * Restarts the animation explicitly from {@code fromArgb} to {@code toArgb}.
     *
     * @param fromArgb starting ARGB color
     * @param toArgb   target ARGB color
     */
    public void reset(int fromArgb, int toArgb) {
        setFromChannels(fromArgb);
        setTargetChannels(toArgb);
        this.activeDurationNanos = defaultDurationNanos;
        this.startTimeNanos = System.nanoTime();
    }

    /**
     * Returns whether the color transition has completed.
     *
     * @return {@code true} if finished or reduce-motion is enabled
     */
    public boolean isFinished() {
        return isFinished(System.nanoTime());
    }

    /**
     * Returns whether the color transition has completed at {@code nowNanos}.
     *
     * @param nowNanos timestamp from {@link System#nanoTime()}
     * @return {@code true} if finished or reduce-motion is enabled
     */
    public boolean isFinished(long nowNanos) {
        if (Animation.isReduceMotion() || activeDurationNanos <= 0L) {
            return true;
        }
        if (Math.round(fromA) == targetA
            && Math.round(fromR) == targetR
            && Math.round(fromG) == targetG
            && Math.round(fromB) == targetB) {
            return true;
        }
        return (nowNanos - startTimeNanos) >= activeDurationNanos;
    }

    /**
     * Returns the current target ARGB color.
     *
     * @return target ARGB color
     */
    public int getTarget() {
        return targetArgb;
    }

    /**
     * Updates the default duration token for subsequent {@link #setTarget(int)} calls.
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

    /**
     * Performs zero-allocation channel-by-channel ARGB linear interpolation.
     *
     * @param fromArgb start color
     * @param toArgb   end color
     * @param t        normalized factor clamped to [0, 1]
     * @return interpolated ARGB color
     */
    public static int lerpArgb(int fromArgb, int toArgb, float t) {
        if (t <= 0.0f) {
            return fromArgb;
        }
        if (t >= 1.0f || fromArgb == toArgb) {
            return toArgb;
        }
        int a0 = (fromArgb >>> 24) & 0xFF;
        int r0 = (fromArgb >>> 16) & 0xFF;
        int g0 = (fromArgb >>> 8) & 0xFF;
        int b0 = fromArgb & 0xFF;

        int a1 = (toArgb >>> 24) & 0xFF;
        int r1 = (toArgb >>> 16) & 0xFF;
        int g1 = (toArgb >>> 8) & 0xFF;
        int b1 = toArgb & 0xFF;

        int a = clampByte(Math.round(a0 + (a1 - a0) * t));
        int r = clampByte(Math.round(r0 + (r1 - r0) * t));
        int g = clampByte(Math.round(g0 + (g1 - g0) * t));
        int b = clampByte(Math.round(b0 + (b1 - b0) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private float computeEasedProgress(long elapsedNanos) {
        if (activeDurationNanos <= 0L || elapsedNanos >= activeDurationNanos) {
            return 1.0f;
        }
        if (elapsedNanos <= 0L) {
            return 0.0f;
        }
        float raw = (float) ((double) elapsedNanos / (double) activeDurationNanos);
        return easing.apply(raw);
    }

    private void setFromChannels(int argb) {
        this.fromA = (argb >>> 24) & 0xFF;
        this.fromR = (argb >>> 16) & 0xFF;
        this.fromG = (argb >>> 8) & 0xFF;
        this.fromB = argb & 0xFF;
    }

    private void setTargetChannels(int argb) {
        this.targetArgb = argb;
        this.targetA = (argb >>> 24) & 0xFF;
        this.targetR = (argb >>> 16) & 0xFF;
        this.targetG = (argb >>> 8) & 0xFF;
        this.targetB = argb & 0xFF;
    }

    private static int clampByte(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= 255) {
            return 255;
        }
        return value;
    }

    private static Easing safeEasing(AnimDuration duration) {
        return duration != null ? duration.getDefaultEasing() : Easing.EASE_OUT_CUBIC;
    }
}
