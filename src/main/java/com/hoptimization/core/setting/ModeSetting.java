package com.hoptimization.core.setting;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.modules.misc.LanguageModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Discrete option setting rendered as a segmented control or dropdown depending on option count.
 */
public class ModeSetting extends Setting<String> {
    private final List<String> modes;
    private final String[] optionKeys;
    private int cachedIndex;

    /**
     * Creates a mode setting. Use {@link #withDescription(String)} to attach an optional tooltip description.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param defaultMode default mode identifier (must exist in {@code modes})
     * @param modes ordered list of valid mode identifiers
     */
    public ModeSetting(String id, String nameKey, String defaultMode, String... modes) {
        super(id, nameKey, "", canonicalizeRequired(defaultMode, modes));
        if (modes == null || modes.length == 0) {
            throw new IllegalArgumentException("ModeSetting requires at least one mode");
        }
        List<String> list = new ArrayList<String>(modes.length);
        Set<String> seenLower = new HashSet<String>(modes.length);
        for (int i = 0; i < modes.length; i++) {
            String mode = modes[i];
            if (mode == null || mode.trim().isEmpty()) {
                throw new IllegalArgumentException("ModeSetting option must not be blank");
            }
            String lower = mode.toLowerCase(Locale.ROOT);
            if (!seenLower.add(lower)) {
                throw new IllegalArgumentException("Duplicate mode option: " + mode);
            }
            list.add(mode);
        }
        this.modes = Collections.unmodifiableList(list);
        this.optionKeys = buildOptionKeys(nameKey, list);
        this.cachedIndex = indexOfMode(this.value);
    }

    /**
     * Returns all valid mode identifiers in definition order.
     *
     * @return unmodifiable list of mode ids
     */
    public List<String> getModes() {
        return this.modes;
    }

    /**
     * Returns the total number of available options.
     *
     * @return mode option count
     */
    public int getModeCount() {
        return this.modes.size();
    }

    /**
     * Returns the zero-based index of the currently selected mode without allocation.
     *
     * @return active option index
     */
    public int getIndex() {
        return this.cachedIndex;
    }

    /**
     * Selects a mode by its zero-based index.
     *
     * @param index target option index
     */
    public void setIndex(int index) {
        if (index < 0 || index >= this.modes.size()) {
            HoptimizationMod.LOGGER.warn("Ignoring out-of-bounds mode index {} for setting {}", index, getId());
            return;
        }
        setValue(this.modes.get(index));
    }

    /**
     * Advances selection to the next mode, wrapping around at the end.
     */
    public void cycle() {
        int next = (this.cachedIndex + 1) % this.modes.size();
        setValue(this.modes.get(next));
    }

    /**
     * Case-insensitively checks whether the current mode matches the given identifier.
     *
     * @param mode candidate mode id
     * @return true if currently active
     */
    public boolean is(String mode) {
        return mode != null && getValue().equalsIgnoreCase(mode);
    }

    /**
     * Returns the precomputed localization key for the option at the given index.
     *
     * @param index option index
     * @return option localization key
     */
    public String getOptionKey(int index) {
        return this.optionKeys[index];
    }

    /**
     * Returns the localization key for the specified mode identifier, or null if unknown.
     *
     * @param mode mode identifier
     * @return option localization key, or null if not found
     */
    public String getOptionKey(String mode) {
        int index = indexOfMode(mode);
        if (index < 0) {
            return null;
        }
        return this.optionKeys[index];
    }

    /**
     * Returns the localization key for the currently selected option.
     *
     * @return current option localization key
     */
    public String getCurrentOptionKey() {
        return this.optionKeys[this.cachedIndex];
    }

    /**
     * Returns the localized display name for the option at the given index.
     *
     * @param index option index
     * @return localized option label
     */
    public String getOptionDisplayName(int index) {
        return LanguageModule.format(this.optionKeys[index]);
    }

    /**
     * Returns the localized display name for the currently active option.
     *
     * @return localized active option label
     */
    public String getCurrentDisplayName() {
        return LanguageModule.format(this.optionKeys[this.cachedIndex]);
    }

    @Override
    protected String coerceValue(String rawValue) {
        int index = indexOfMode(rawValue);
        if (index < 0) {
            HoptimizationMod.LOGGER.warn(
                "Unknown mode '{}' for setting '{}', keeping '{}'",
                rawValue,
                getId(),
                this.value
            );
            return this.value;
        }
        return this.modes.get(index);
    }

    @Override
    protected void onValueChanged() {
        int index = indexOfMode(this.value);
        this.cachedIndex = index >= 0 ? index : 0;
    }

    @Override
    public ModeSetting visibleWhen(Supplier<Boolean> predicate) {
        super.visibleWhen(predicate);
        return this;
    }

    @Override
    public ModeSetting withDescription(String descKey) {
        super.withDescription(descKey);
        return this;
    }

    private int indexOfMode(String mode) {
        if (mode == null) {
            return -1;
        }
        for (int i = 0; i < this.modes.size(); i++) {
            if (this.modes.get(i).equalsIgnoreCase(mode)) {
                return i;
            }
        }
        return -1;
    }

    private static String canonicalizeRequired(String defaultMode, String[] modes) {
        if (defaultMode == null || modes == null) {
            throw new IllegalArgumentException("ModeSetting defaultMode and modes must not be null");
        }
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] != null && modes[i].equalsIgnoreCase(defaultMode)) {
                return modes[i];
            }
        }
        throw new IllegalArgumentException("Default mode '" + defaultMode + "' is not in modes list");
    }

    private static String[] buildOptionKeys(String nameKey, List<String> modes) {
        String prefix;
        if (nameKey.endsWith(".name")) {
            prefix = nameKey.substring(0, nameKey.length() - ".name".length()) + ".option.";
        } else {
            prefix = nameKey + ".option.";
        }
        String[] keys = new String[modes.size()];
        for (int i = 0; i < modes.size(); i++) {
            keys[i] = prefix + modes.get(i).toLowerCase(Locale.ROOT);
        }
        return keys;
    }
}
