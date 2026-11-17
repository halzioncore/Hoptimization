package com.hoptimization.core.setting;

import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Base class for all module configuration settings.
 *
 * @param <T> underlying value type held by this setting
 */
public abstract class Setting<T> {
    private final String id;
    private final String nameKey;
    private String descKey;
    private final T defaultValue;
    protected T value;
    private Supplier<Boolean> visibilityPredicate;
    private final List<Runnable> changeListeners = new ArrayList<Runnable>(2);

    /**
     * Creates a setting without a separate description translation key.
     *
     * @param id stable lowercase snake_case key used for persistence
     * @param nameKey localization key for the setting label
     * @param defaultValue initial and reset value
     */
    protected Setting(String id, String nameKey, T defaultValue) {
        this(id, nameKey, "", defaultValue);
    }

    /**
     * Creates a setting with an optional description translation key.
     *
     * @param id stable lowercase snake_case key used for persistence
     * @param nameKey localization key for the setting label
     * @param descKey localization key for tooltip description, or empty string if none
     * @param defaultValue initial and reset value
     */
    protected Setting(String id, String nameKey, String descKey, T defaultValue) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Setting id must not be blank");
        }
        if (nameKey == null || nameKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Setting nameKey must not be blank");
        }
        this.id = id;
        this.nameKey = nameKey;
        this.descKey = descKey == null ? "" : descKey;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    /**
     * Returns the stable persistence identifier of this setting.
     *
     * @return setting id
     */
    public String getId() {
        return this.id;
    }

    /**
     * Returns the localization key for the setting label.
     *
     * @return name localization key
     */
    public String getNameKey() {
        return this.nameKey;
    }

    /**
     * Returns the localization key for the optional description tooltip.
     *
     * @return description localization key, or empty string if unset
     */
    public String getDescKey() {
        return this.descKey;
    }

    /**
     * Checks whether this setting defines a description key.
     *
     * @return true if a non-empty description key exists
     */
    public boolean hasDescription() {
        return !this.descKey.isEmpty();
    }

    /**
     * Returns the localized display name of this setting.
     *
     * @return localized setting label
     */
    public String getDisplayName() {
        return I18n.format(this.nameKey);
    }

    /**
     * Returns the localized description text, or empty string if unset.
     *
     * @return localized description
     */
    public String getDisplayDescription() {
        if (this.descKey.isEmpty()) {
            return "";
        }
        return I18n.format(this.descKey);
    }

    /**
     * Returns the default value used on initialization and reset.
     *
     * @return default value
     */
    public T getDefaultValue() {
        return this.defaultValue;
    }

    /**
     * Returns the current value of this setting.
     *
     * @return current value
     */
    public T getValue() {
        return this.value;
    }

    /**
     * Updates the setting value after validation/coercion and notifies listeners if changed.
     *
     * @param rawValue candidate new value
     */
    public final void setValue(T rawValue) {
        T coerced = coerceValue(rawValue);
        if (Objects.equals(this.value, coerced)) {
            return;
        }
        this.value = coerced;
        onValueChanged();
        notifyChangeListeners();
    }

    /**
     * Resets this setting back to its default value.
     */
    public void reset() {
        setValue(this.defaultValue);
    }

    /**
     * Attaches a conditional visibility predicate evaluated by the GUI.
     *
     * @param predicate predicate returning true when this setting should be shown
     * @return this setting instance for fluent chaining
     */
    public Setting<T> visibleWhen(Supplier<Boolean> predicate) {
        this.visibilityPredicate = predicate;
        return this;
    }

    /**
     * Sets the optional description localization key.
     *
     * @param descKey localization key for tooltip description
     * @return this setting instance for fluent chaining
     */
    public Setting<T> withDescription(String descKey) {
        this.descKey = descKey == null ? "" : descKey;
        return this;
    }

    /**
     * Evaluates whether this setting should currently be visible in the GUI.
     *
     * @return true if visible
     */
    public boolean isVisible() {
        if (this.visibilityPredicate == null) {
            return true;
        }
        return Boolean.TRUE.equals(this.visibilityPredicate.get());
    }

    /**
     * Registers a listener invoked whenever the setting value changes.
     *
     * @param listener listener callback
     */
    public void addChangeListener(Runnable listener) {
        if (listener != null && !this.changeListeners.contains(listener)) {
            this.changeListeners.add(listener);
        }
    }

    /**
     * Removes a previously registered change listener.
     *
     * @param listener listener callback to remove
     */
    public void removeChangeListener(Runnable listener) {
        if (listener != null) {
            this.changeListeners.remove(listener);
        }
    }

    /**
     * Validates and normalizes an incoming value before storing it.
     *
     * @param rawValue incoming candidate value
     * @return valid normalized value
     */
    protected abstract T coerceValue(T rawValue);

    /**
     * Hook invoked after {@link #value} changes so subclasses can refresh cached display state.
     */
    protected void onValueChanged() {
    }

    private void notifyChangeListeners() {
        // Indexed loop avoids Iterator allocation when settings change.
        for (int i = 0; i < this.changeListeners.size(); i++) {
            this.changeListeners.get(i).run();
        }
    }
}
