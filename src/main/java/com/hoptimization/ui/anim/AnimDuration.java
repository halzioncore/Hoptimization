package com.hoptimization.ui.anim;

/**
 * Duration tokens for UI animations, including the 0.75x close-transition helper.
 */
public enum AnimDuration {
    /**
     * Short transitions such as hover color changes and switch thumbs (120 ms).
     */
    FAST(120L, Easing.EASE_OUT_CUBIC),

    /**
     * Standard transitions such as expand/collapse and category indicators (180 ms).
     */
    NORMAL(180L, Easing.EASE_OUT_CUBIC),

    /**
     * Larger transitions such as opening the main ClickGUI window (240 ms).
     */
    SLOW(240L, Easing.EASE_OUT_QUINT);

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final long millis;
    private final long nanos;
    private final long closeMillis;
    private final long closeNanos;
    private final Easing defaultEasing;

    AnimDuration(long millis, Easing defaultEasing) {
        this.millis = millis;
        this.nanos = millis * NANOS_PER_MILLI;
        this.closeMillis = closeMillis(millis);
        this.closeNanos = this.closeMillis * NANOS_PER_MILLI;
        this.defaultEasing = defaultEasing;
    }

    /**
     * Returns the open/forward duration in milliseconds.
     *
     * @return duration in milliseconds
     */
    public long getMillis() {
        return millis;
    }

    /**
     * Returns the open/forward duration in nanoseconds.
     *
     * @return duration in nanoseconds
     */
    public long getNanos() {
        return nanos;
    }

    /**
     * Returns the close/reverse duration (0.75x of open duration) in milliseconds.
     *
     * @return close duration in milliseconds
     */
    public long getCloseMillis() {
        return closeMillis;
    }

    /**
     * Returns the close/reverse duration (0.75x of open duration) in nanoseconds.
     *
     * @return close duration in nanoseconds
     */
    public long getCloseNanos() {
        return closeNanos;
    }

    /**
     * Returns the default easing paired with this duration token in the design spec.
     *
     * @return default easing curve
     */
    public Easing getDefaultEasing() {
        return defaultEasing;
    }

    /**
     * Computes the close duration (0.75x) for an arbitrary open duration in milliseconds.
     *
     * @param openMillis open duration in milliseconds
     * @return close duration in milliseconds, clamped to non-negative
     */
    public static long closeMillis(long openMillis) {
        if (openMillis <= 0L) {
            return 0L;
        }
        return Math.round(openMillis * 0.75D);
    }

    /**
     * Computes the close duration (0.75x) for an arbitrary open duration in nanoseconds.
     *
     * @param openNanos open duration in nanoseconds
     * @return close duration in nanoseconds, clamped to non-negative
     */
    public static long closeNanos(long openNanos) {
        if (openNanos <= 0L) {
            return 0L;
        }
        return (openNanos * 3L) / 4L;
    }
}
