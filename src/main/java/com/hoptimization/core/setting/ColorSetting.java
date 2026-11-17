package com.hoptimization.core.setting;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Color setting storing an ARGB integer with configurable alpha channel editing.
 */
public class ColorSetting extends Setting<Integer> {
    private final boolean allowAlpha;
    private String cachedHex;

    /**
     * Creates a color setting without a description key.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param defaultArgb initial color in {@code 0xAARRGGBB} format
     * @param allowAlpha true to allow adjustable alpha, false to lock alpha at 255
     */
    public ColorSetting(String id, String nameKey, int defaultArgb, boolean allowAlpha) {
        this(id, nameKey, "", defaultArgb, allowAlpha);
    }

    /**
     * Creates a color setting with an optional description key.
     *
     * @param id stable setting id
     * @param nameKey localization key for label
     * @param descKey localization key for description tooltip
     * @param defaultArgb initial color in {@code 0xAARRGGBB} format
     * @param allowAlpha true to allow adjustable alpha, false to lock alpha at 255
     */
    public ColorSetting(String id, String nameKey, String descKey, int defaultArgb, boolean allowAlpha) {
        super(id, nameKey, descKey, Integer.valueOf(allowAlpha ? defaultArgb : (defaultArgb | 0xFF000000)));
        this.allowAlpha = allowAlpha;
        this.cachedHex = formatHexColor(this.value.intValue(), allowAlpha);
    }

    /**
     * Returns whether alpha editing is enabled for this color setting.
     *
     * @return true if alpha channel is configurable
     */
    public boolean isAllowAlpha() {
        return this.allowAlpha;
    }

    /**
     * Returns the current color as a packed {@code 0xAARRGGBB} integer.
     *
     * @return ARGB color integer
     */
    public int getArgb() {
        return getValue().intValue();
    }

    /**
     * Returns the alpha component in {@code 0..255}.
     *
     * @return alpha byte
     */
    public int getAlpha() {
        return (getArgb() >>> 24) & 0xFF;
    }

    /**
     * Returns the red component in {@code 0..255}.
     *
     * @return red byte
     */
    public int getRed() {
        return (getArgb() >>> 16) & 0xFF;
    }

    /**
     * Returns the green component in {@code 0..255}.
     *
     * @return green byte
     */
    public int getGreen() {
        return (getArgb() >>> 8) & 0xFF;
    }

    /**
     * Returns the blue component in {@code 0..255}.
     *
     * @return blue byte
     */
    public int getBlue() {
        return getArgb() & 0xFF;
    }

    /**
     * Sets the color from individual RGBA components clamped to {@code 0..255}.
     *
     * @param r red component
     * @param g green component
     * @param b blue component
     * @param a alpha component (ignored when {@link #isAllowAlpha()} is false)
     */
    public void setRgba(int r, int g, int b, int a) {
        int cr = clampChannel(r);
        int cg = clampChannel(g);
        int cb = clampChannel(b);
        int ca = this.allowAlpha ? clampChannel(a) : 0xFF;
        int packed = (ca << 24) | (cr << 16) | (cg << 8) | cb;
        setValue(Integer.valueOf(packed));
    }

    /**
     * Returns the cached {@code #RRGGBB} or {@code #RRGGBBAA} string without allocation.
     *
     * @return hex color string
     */
    public String getHexString() {
        return this.cachedHex;
    }

    /**
     * Parses a {@code #RRGGBB} or {@code #RRGGBBAA} string and updates this color if valid.
     *
     * @param hex candidate hex color string
     * @return true if parsed and applied, false if invalid
     */
    public boolean setFromHex(String hex) {
        Integer parsed = parseHexColor(hex, this.allowAlpha);
        if (parsed == null) {
            return false;
        }
        setValue(parsed);
        return true;
    }

    /**
     * Formats a packed {@code 0xAARRGGBB} color into {@code #RRGGBB} or {@code #RRGGBBAA} (alpha last).
     *
     * @param argb packed ARGB integer
     * @param includeAlpha true to output 8 hex digits ({@code #RRGGBBAA}), false for 6 ({@code #RRGGBB})
     * @return uppercase hex string with leading {@code #}
     */
    public static String formatHexColor(int argb, boolean includeAlpha) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (includeAlpha) {
            int a = (argb >>> 24) & 0xFF;
            return String.format(Locale.ROOT, "#%02X%02X%02X%02X",
                Integer.valueOf(r), Integer.valueOf(g), Integer.valueOf(b), Integer.valueOf(a));
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X",
            Integer.valueOf(r), Integer.valueOf(g), Integer.valueOf(b));
    }

    /**
     * Parses {@code #RRGGBB} or {@code #RRGGBBAA} (alpha at the end, per spec) into packed ARGB.
     *
     * @param hex input hex string, with or without leading {@code #}
     * @param allowAlpha when false, alpha is forced to {@code 0xFF}
     * @return packed ARGB integer, or null if syntax is invalid
     */
    public static Integer parseHexColor(String hex, boolean allowAlpha) {
        if (hex == null) {
            return null;
        }
        String trimmed = hex.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() != 6 && trimmed.length() != 8) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(trimmed.substring(0, 6), 16);
            int alpha = 0xFF;
            if (trimmed.length() == 8 && allowAlpha) {
                alpha = Integer.parseInt(trimmed.substring(6, 8), 16);
            }
            return Integer.valueOf((alpha << 24) | (rgb & 0x00FFFFFF));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected Integer coerceValue(Integer rawValue) {
        if (rawValue == null) {
            return getValue();
        }
        int argb = rawValue.intValue();
        if (!this.allowAlpha) {
            argb |= 0xFF000000;
        }
        return Integer.valueOf(argb);
    }

    @Override
    protected void onValueChanged() {
        this.cachedHex = formatHexColor(this.value.intValue(), this.allowAlpha);
    }

    @Override
    public ColorSetting visibleWhen(Supplier<Boolean> predicate) {
        super.visibleWhen(predicate);
        return this;
    }

    @Override
    public ColorSetting withDescription(String descKey) {
        super.withDescription(descKey);
        return this;
    }

    private static int clampChannel(int channel) {
        if (channel < 0) {
            return 0;
        }
        if (channel > 255) {
            return 255;
        }
        return channel;
    }
}
