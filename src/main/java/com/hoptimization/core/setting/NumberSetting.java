package com.hoptimization.core.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Supplier;

/**
 * Bounded numeric setting with step granularity and optional unit suffix.
 */
public class NumberSetting extends Setting<Double> {
    private static final int MAX_FORMAT_DECIMALS = 4;

    private final double min;
    private final double max;
    private final double step;
    private final String unit;
    private final int baseScale;
    private String cachedFormattedValue;

    /**
     * Creates a numeric setting without a unit suffix.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param defaultValue initial numeric value
     * @param min inclusive lower bound
     * @param max inclusive upper bound
     * @param step normal slider increment step
     */
    public NumberSetting(String id, String nameKey, double defaultValue, double min, double max, double step) {
        this(id, nameKey, "", defaultValue, min, max, step, "");
    }

    /**
     * Creates a numeric setting with an optional unit suffix.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param defaultValue initial numeric value
     * @param min inclusive lower bound
     * @param max inclusive upper bound
     * @param step normal slider increment step
     * @param unit display unit such as "%" or "s", or empty if none
     */
    public NumberSetting(
        String id,
        String nameKey,
        double defaultValue,
        double min,
        double max,
        double step,
        String unit
    ) {
        this(id, nameKey, "", defaultValue, min, max, step, unit);
    }

    /**
     * Creates a numeric setting with description and optional unit suffix.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param descKey localization key for description tooltip
     * @param defaultValue initial numeric value
     * @param min inclusive lower bound
     * @param max inclusive upper bound
     * @param step normal slider increment step
     * @param unit display unit such as "%" or "s", or empty if none
     */
    public NumberSetting(
        String id,
        String nameKey,
        String descKey,
        double defaultValue,
        double min,
        double max,
        double step,
        String unit
    ) {
        super(id, nameKey, descKey, Double.valueOf(clampStatic(validateFinite(defaultValue, "defaultValue"), min, max)));
        validateFinite(min, "min");
        validateFinite(max, "max");
        validateFinite(step, "step");
        if (min > max) {
            throw new IllegalArgumentException("NumberSetting min (" + min + ") must be <= max (" + max + ")");
        }
        if (step <= 0.0D) {
            throw new IllegalArgumentException("NumberSetting step must be > 0, got " + step);
        }
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit == null ? "" : unit;
        this.baseScale = computeScale(step);
        this.cachedFormattedValue = formatNumber(this.value.doubleValue(), this.baseScale);
    }

    /**
     * Returns the inclusive lower bound.
     *
     * @return minimum value
     */
    public double getMin() {
        return this.min;
    }

    /**
     * Returns the inclusive upper bound.
     *
     * @return maximum value
     */
    public double getMax() {
        return this.max;
    }

    /**
     * Returns the standard step size.
     *
     * @return step size
     */
    public double getStep() {
        return this.step;
    }

    /**
     * Returns the display unit string, or empty string if none.
     *
     * @return unit string
     */
    public String getUnit() {
        return this.unit;
    }

    /**
     * Returns the current value as a primitive double.
     *
     * @return current double value
     */
    public double getDoubleValue() {
        return getValue().doubleValue();
    }

    /**
     * Returns the current value as a float.
     *
     * @return current float value
     */
    public float getFloatValue() {
        return getValue().floatValue();
    }

    /**
     * Returns the current value rounded to the nearest integer.
     *
     * @return rounded int value
     */
    public int getIntValue() {
        return (int) Math.round(getValue().doubleValue());
    }

    /**
     * Returns normalized progress within {@code [0.0, 1.0]}.
     *
     * @return normalized slider position
     */
    public double getProgress() {
        double range = this.max - this.min;
        if (range <= 0.0D) {
            return 0.0D;
        }
        double ratio = (getValue().doubleValue() - this.min) / range;
        if (ratio < 0.0D) {
            return 0.0D;
        }
        if (ratio > 1.0D) {
            return 1.0D;
        }
        return ratio;
    }

    /**
     * Updates the value by snapping to either the standard step or fine step ({@code step / 10}).
     *
     * @param rawValue candidate value
     * @param fine true for Shift-precision mode ({@code step / 10})
     */
    public void setSteppedValue(double rawValue, boolean fine) {
        if (Double.isNaN(rawValue) || Double.isInfinite(rawValue)) {
            return;
        }
        double clamped = clampStatic(rawValue, this.min, this.max);
        double activeStep = fine ? (this.step * 0.1D) : this.step;
        double snapped = this.min + Math.round((clamped - this.min) / activeStep) * activeStep;
        int snapScale = Math.min(MAX_FORMAT_DECIMALS, this.baseScale + (fine ? 1 : 0));
        // Round after snapping to eliminate IEEE-754 drift such as 2.5000000000000004.
        double clean = BigDecimal.valueOf(snapped)
            .setScale(snapScale, RoundingMode.HALF_UP)
            .doubleValue();
        setValue(Double.valueOf(clampStatic(clean, this.min, this.max)));
    }

    /**
     * Sets the value from a normalized {@code [0.0, 1.0]} slider ratio.
     *
     * @param progress normalized ratio
     * @param fine true for Shift-precision mode
     */
    public void setFromProgress(double progress, boolean fine) {
        if (Double.isNaN(progress) || Double.isInfinite(progress)) {
            return;
        }
        double clampedProgress = clampStatic(progress, 0.0D, 1.0D);
        double mapped = this.min + clampedProgress * (this.max - this.min);
        setSteppedValue(mapped, fine);
    }

    /**
     * Returns the pre-formatted display string for the current value without hot-path allocations.
     *
     * @return cached numeric string (without unit)
     */
    public String getFormattedValue() {
        return this.cachedFormattedValue;
    }

    @Override
    protected Double coerceValue(Double rawValue) {
        if (rawValue == null || Double.isNaN(rawValue.doubleValue()) || Double.isInfinite(rawValue.doubleValue())) {
            return getValue();
        }
        return Double.valueOf(clampStatic(rawValue.doubleValue(), this.min, this.max));
    }

    @Override
    protected void onValueChanged() {
        this.cachedFormattedValue = formatNumber(this.value.doubleValue(), this.baseScale);
    }

    @Override
    public NumberSetting visibleWhen(Supplier<Boolean> predicate) {
        super.visibleWhen(predicate);
        return this;
    }

    @Override
    public NumberSetting withDescription(String descKey) {
        super.withDescription(descKey);
        return this;
    }

    private static double validateFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("NumberSetting " + name + " must be finite");
        }
        return value;
    }

    private static double clampStatic(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static int computeScale(double step) {
        BigDecimal bd = BigDecimal.valueOf(step).stripTrailingZeros();
        return Math.max(0, Math.min(MAX_FORMAT_DECIMALS, bd.scale()));
    }

    private static String formatNumber(double value, int baseScale) {
        int displayScale = Math.min(MAX_FORMAT_DECIMALS, baseScale + 2);
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(displayScale, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return rounded.stripTrailingZeros().toPlainString();
    }
}
