package com.hoptimization.core.setting;

import java.util.function.Supplier;

/**
 * Boolean toggle setting rendered as a switch control.
 */
public class BooleanSetting extends Setting<Boolean> {

    /**
     * Creates a boolean setting without a description key.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param defaultValue initial boolean value
     */
    public BooleanSetting(String id, String nameKey, boolean defaultValue) {
        super(id, nameKey, Boolean.valueOf(defaultValue));
    }

    /**
     * Creates a boolean setting with an optional description key.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param descKey localization key for description tooltip
     * @param defaultValue initial boolean value
     */
    public BooleanSetting(String id, String nameKey, String descKey, boolean defaultValue) {
        super(id, nameKey, descKey, Boolean.valueOf(defaultValue));
    }

    /**
     * Returns the current state as a primitive boolean without null unboxing risk.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(getValue());
    }

    /**
     * Flips the current boolean value.
     */
    public void toggle() {
        setValue(Boolean.valueOf(!isEnabled()));
    }

    @Override
    protected Boolean coerceValue(Boolean rawValue) {
        return rawValue != null ? rawValue : getDefaultValue();
    }

    @Override
    public BooleanSetting visibleWhen(Supplier<Boolean> predicate) {
        super.visibleWhen(predicate);
        return this;
    }

    @Override
    public BooleanSetting withDescription(String descKey) {
        super.withDescription(descKey);
        return this;
    }
}
