package com.hoptimization.core.setting;

import com.hoptimization.modules.misc.LanguageModule;
import org.lwjgl.input.Keyboard;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Keyboard keybind setting storing an LWJGL {@link Keyboard} key code ({@code 0} = unbound).
 */
public class KeybindSetting extends Setting<Integer> {
    private static final String NONE_DISPLAY_KEY = "setting.hoptimization.common.keybind.none";
    private String cachedKeyName;

    /**
     * Creates a keybind setting without a description key.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param defaultKey initial LWJGL key code ({@link Keyboard#KEY_NONE} for unbound)
     */
    public KeybindSetting(String id, String nameKey, int defaultKey) {
        this(id, nameKey, "", defaultKey);
    }

    /**
     * Creates a keybind setting with an optional description key.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param descKey localization key for description tooltip
     * @param defaultKey initial LWJGL key code ({@link Keyboard#KEY_NONE} for unbound)
     */
    public KeybindSetting(String id, String nameKey, String descKey, int defaultKey) {
        super(id, nameKey, descKey, Integer.valueOf(sanitizeKeyCode(defaultKey)));
        this.cachedKeyName = formatKeyName(this.value.intValue());
    }

    /**
     * Returns the LWJGL key code, or {@link Keyboard#KEY_NONE} ({@code 0}) when unbound.
     *
     * @return key code
     */
    public int getKeyCode() {
        return getValue().intValue();
    }

    /**
     * Checks whether a key is currently bound.
     *
     * @return true if bound to a non-zero key code
     */
    public boolean isBound() {
        return getKeyCode() != Keyboard.KEY_NONE;
    }

    /**
     * Clears the bound key back to {@link Keyboard#KEY_NONE}.
     */
    public void clear() {
        setValue(Integer.valueOf(Keyboard.KEY_NONE));
    }

    /**
     * Returns the cached LWJGL key name (for example {@code "R"}, {@code "LSHIFT"}, or {@code "NONE"}).
     *
     * @return cached LWJGL key name without allocation
     */
    public String getKeyName() {
        return this.cachedKeyName;
    }

    /**
     * Returns a user-facing key label, localizing unbound state via language files.
     *
     * @return display key label
     */
    public String getDisplayKeyName() {
        if (!isBound()) {
            return LanguageModule.format(NONE_DISPLAY_KEY);
        }
        return this.cachedKeyName;
    }

    /**
     * Safely resolves an LWJGL key code to its key name with bounds checking.
     *
     * @param keyCode LWJGL key code
     * @return key name such as {@code "RSHIFT"} or {@code "NONE"}
     */
    public static String formatKeyName(int keyCode) {
        // LWJGL2 Keyboard.getKeyName indexes directly into keyName[256] without bounds checks.
        if (keyCode <= Keyboard.KEY_NONE || keyCode >= Keyboard.KEYBOARD_SIZE) {
            return "NONE";
        }
        String name = Keyboard.getKeyName(keyCode);
        if (name == null || name.isEmpty()) {
            return "KEY_" + keyCode;
        }
        return name;
    }

    /**
     * Sanitizes a key code into the valid LWJGL key table range.
     *
     * @param keyCode candidate key code
     * @return valid key code, or {@link Keyboard#KEY_NONE} if out of range
     */
    public static int sanitizeKeyCode(int keyCode) {
        if (keyCode <= Keyboard.KEY_NONE || keyCode >= Keyboard.KEYBOARD_SIZE) {
            return Keyboard.KEY_NONE;
        }
        return keyCode;
    }

    /**
     * Parses an LWJGL key name such as {@code "RSHIFT"} or {@code "NONE"} into its key code.
     *
     * @param keyName key name string
     * @return key code, or {@link Keyboard#KEY_NONE} if unknown or {@code "NONE"}
     */
    public static int parseKeyName(String keyName) {
        if (keyName == null) {
            return Keyboard.KEY_NONE;
        }
        String normalized = keyName.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || "NONE".equals(normalized)) {
            return Keyboard.KEY_NONE;
        }
        return sanitizeKeyCode(Keyboard.getKeyIndex(normalized));
    }

    @Override
    protected Integer coerceValue(Integer rawValue) {
        if (rawValue == null) {
            return Integer.valueOf(Keyboard.KEY_NONE);
        }
        return Integer.valueOf(sanitizeKeyCode(rawValue.intValue()));
    }

    @Override
    protected void onValueChanged() {
        this.cachedKeyName = formatKeyName(this.value.intValue());
    }

    @Override
    public KeybindSetting visibleWhen(Supplier<Boolean> predicate) {
        super.visibleWhen(predicate);
        return this;
    }

    @Override
    public KeybindSetting withDescription(String descKey) {
        super.withDescription(descKey);
        return this;
    }
}
