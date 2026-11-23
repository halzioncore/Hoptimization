package com.hoptimization.core.config;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.KeybindSetting;
import org.lwjgl.input.Keyboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent UI and presentation preferences stored in {@code ui.json}.
 */
public class UiConfig {
    public static final int CURRENT_SCHEMA = 2;
    public static final String DEFAULT_PROFILE = "default";
    public static final String DEFAULT_THEME_ID = "obsidian_gold";
    public static final String DEFAULT_DISPLAY_STYLE = "standard";
    public static final float DEFAULT_BLUR_STRENGTH = 12.0f;
    public static final float MIN_BLUR_STRENGTH = 4.0f;
    public static final float MAX_BLUR_STRENGTH = 24.0f;
    public static final float MIN_UI_SCALE_MULTIPLIER = 0.1f;
    public static final float MAX_UI_SCALE_MULTIPLIER = 2.0f;
    public static final float DEFAULT_CLICK_GUI_SCALE = 1.0f;
    public static final float MIN_CLICK_GUI_SCALE = 0.1f;
    public static final float MAX_CLICK_GUI_SCALE = 2.0f;

    private int schema = CURRENT_SCHEMA;
    private String activeProfile = DEFAULT_PROFILE;
    private String themeId = DEFAULT_THEME_ID;
    private String displayStyle = DEFAULT_DISPLAY_STYLE;
    private float blurStrength = DEFAULT_BLUR_STRENGTH;
    private String accentOverride = "";
    private float uiScaleMultiplier = 1.0f;
    private float clickGuiScale = DEFAULT_CLICK_GUI_SCALE;
    private boolean reduceMotion = false;
    private int clickGuiKeybind = Keyboard.KEY_RSHIFT;
    private String lastCategory = Category.COMBAT.getId();
    private boolean windowPositionSet = false;
    private float windowX = 0.0f;
    private float windowY = 0.0f;
    private final Map<String, HudLayout> hudLayouts = new LinkedHashMap<String, HudLayout>();

    /**
     * Creates a {@link UiConfig} initialized with default values.
     */
    public UiConfig() {
    }

    /**
     * Resets all fields back to their defaults.
     */
    public void resetToDefaults() {
        this.schema = CURRENT_SCHEMA;
        this.activeProfile = DEFAULT_PROFILE;
        this.themeId = DEFAULT_THEME_ID;
        this.displayStyle = DEFAULT_DISPLAY_STYLE;
        this.blurStrength = DEFAULT_BLUR_STRENGTH;
        this.accentOverride = "";
        this.uiScaleMultiplier = 1.0f;
        this.clickGuiScale = DEFAULT_CLICK_GUI_SCALE;
        this.reduceMotion = false;
        this.clickGuiKeybind = Keyboard.KEY_RSHIFT;
        this.lastCategory = Category.COMBAT.getId();
        this.windowPositionSet = false;
        this.windowX = 0.0f;
        this.windowY = 0.0f;
        this.hudLayouts.clear();
    }

    /**
     * Creates an independent deep copy for thread-safe snapshots.
     *
     * @return deep copy of this configuration
     */
    public UiConfig copy() {
        UiConfig copy = new UiConfig();
        copy.schema = this.schema;
        copy.activeProfile = this.activeProfile;
        copy.themeId = this.themeId;
        copy.displayStyle = this.displayStyle;
        copy.blurStrength = this.blurStrength;
        copy.accentOverride = this.accentOverride;
        copy.uiScaleMultiplier = this.uiScaleMultiplier;
        copy.clickGuiScale = this.clickGuiScale;
        copy.reduceMotion = this.reduceMotion;
        copy.clickGuiKeybind = this.clickGuiKeybind;
        copy.lastCategory = this.lastCategory;
        copy.windowPositionSet = this.windowPositionSet;
        copy.windowX = this.windowX;
        copy.windowY = this.windowY;
        for (Map.Entry<String, HudLayout> entry : this.hudLayouts.entrySet()) {
            if (entry.getValue() != null) {
                copy.hudLayouts.put(entry.getKey(), entry.getValue().copy());
            }
        }
        return copy;
    }

    /**
     * Returns the configuration schema version.
     *
     * @return schema version
     */
    public int getSchema() {
        return this.schema;
    }

    /**
     * Sets the schema version when positive.
     *
     * @param schema schema version
     */
    public void setSchema(int schema) {
        if (schema > 0) {
            this.schema = schema;
        }
    }

    /**
     * Returns the active module profile name.
     *
     * @return active profile name
     */
    public String getActiveProfile() {
        return this.activeProfile;
    }

    /**
     * Sets the active module profile name if non-blank.
     *
     * @param activeProfile profile name
     */
    public void setActiveProfile(String activeProfile) {
        if (activeProfile != null && !activeProfile.trim().isEmpty()) {
            this.activeProfile = activeProfile.trim();
        }
    }

    /**
     * Returns the selected theme identifier.
     *
     * @return theme id
     */
    public String getThemeId() {
        return this.themeId;
    }

    /**
     * Sets the selected theme identifier.
     *
     * @param themeId theme id
     */
    public void setThemeId(String themeId) {
        if (themeId != null && !themeId.trim().isEmpty()) {
            this.themeId = themeId.trim();
        }
    }

    /**
     * Returns the selected global display style identifier ({@code "standard"} or {@code "frosted_glass"}).
     *
     * @return display style id
     */
    public String getDisplayStyle() {
        return this.displayStyle;
    }

    /**
     * Sets the selected global display style identifier.
     *
     * @param displayStyle display style id
     */
    public void setDisplayStyle(String displayStyle) {
        if (displayStyle == null || displayStyle.trim().isEmpty()) {
            return;
        }
        String normalized = displayStyle.trim().toLowerCase(java.util.Locale.ROOT);
        if ("standard".equals(normalized) || "frosted_glass".equals(normalized)) {
            this.displayStyle = normalized;
        }
    }

    /**
     * Returns the frosted glass blur strength in pixels (default {@code 12.0f}).
     *
     * @return blur strength
     */
    public float getBlurStrength() {
        return this.blurStrength;
    }

    /**
     * Sets the frosted glass blur strength, clamped to [{@link #MIN_BLUR_STRENGTH}, {@link #MAX_BLUR_STRENGTH}].
     *
     * @param blurStrength blur strength in pixels
     */
    public void setBlurStrength(float blurStrength) {
        if (Float.isNaN(blurStrength) || Float.isInfinite(blurStrength)) {
            return;
        }
        if (blurStrength < MIN_BLUR_STRENGTH) {
            this.blurStrength = MIN_BLUR_STRENGTH;
        } else if (blurStrength > MAX_BLUR_STRENGTH) {
            this.blurStrength = MAX_BLUR_STRENGTH;
        } else {
            this.blurStrength = blurStrength;
        }
    }

    /**
     * Returns the optional accent hex override ({@code #RRGGBB} or {@code #RRGGBBAA}), or empty to follow theme.
     *
     * @return accent hex override or empty string
     */
    public String getAccentOverride() {
        return this.accentOverride;
    }

    /**
     * Sets or clears the accent color override.
     *
     * @param accentOverride hex color string, or null/empty to follow the active theme
     */
    public void setAccentOverride(String accentOverride) {
        if (accentOverride == null || accentOverride.trim().isEmpty()) {
            this.accentOverride = "";
            return;
        }
        Integer parsed = ColorSetting.parseHexColor(accentOverride, true);
        if (parsed != null) {
            boolean hasAlpha = accentOverride.trim().replace("#", "").length() == 8;
            this.accentOverride = ColorSetting.formatHexColor(parsed.intValue(), hasAlpha);
        }
    }

    /**
     * Returns the user UI scale multiplier (default {@code 1.0f}).
     *
     * @return UI scale multiplier
     */
    public float getUiScaleMultiplier() {
        return this.uiScaleMultiplier;
    }

    /**
     * Sets the user UI scale multiplier, clamped to valid bounds.
     *
     * @param uiScaleMultiplier scale multiplier
     */
    public void setUiScaleMultiplier(float uiScaleMultiplier) {
        if (Float.isNaN(uiScaleMultiplier) || Float.isInfinite(uiScaleMultiplier)) {
            return;
        }
        if (uiScaleMultiplier < MIN_UI_SCALE_MULTIPLIER) {
            this.uiScaleMultiplier = MIN_UI_SCALE_MULTIPLIER;
        } else if (uiScaleMultiplier > MAX_UI_SCALE_MULTIPLIER) {
            this.uiScaleMultiplier = MAX_UI_SCALE_MULTIPLIER;
        } else {
            this.uiScaleMultiplier = uiScaleMultiplier;
        }
    }

    /**
     * Returns the independent ClickGUI scale multiplier (default {@code 1.25f}).
     *
     * @return ClickGUI scale multiplier
     */
    public float getClickGuiScale() {
        return this.clickGuiScale;
    }

    /**
     * Sets the independent ClickGUI scale multiplier, clamped to [{@code 0.75f}, {@code 2.0f}].
     *
     * @param clickGuiScale ClickGUI scale multiplier
     */
    public void setClickGuiScale(float clickGuiScale) {
        if (Float.isNaN(clickGuiScale) || Float.isInfinite(clickGuiScale)) {
            return;
        }
        if (clickGuiScale < MIN_CLICK_GUI_SCALE) {
            this.clickGuiScale = MIN_CLICK_GUI_SCALE;
        } else if (clickGuiScale > MAX_CLICK_GUI_SCALE) {
            this.clickGuiScale = MAX_CLICK_GUI_SCALE;
        } else {
            this.clickGuiScale = clickGuiScale;
        }
    }

    /**
     * Returns whether animations should use zero duration.
     *
     * @return true if reduce motion is enabled
     */
    public boolean isReduceMotion() {
        return this.reduceMotion;
    }

    /**
     * Enables or disables reduced motion.
     *
     * @param reduceMotion true to disable animation durations
     */
    public void setReduceMotion(boolean reduceMotion) {
        this.reduceMotion = reduceMotion;
    }

    /**
     * Returns the LWJGL key code that toggles the ClickGUI.
     *
     * @return ClickGUI keybind code
     */
    public int getClickGuiKeybind() {
        return this.clickGuiKeybind;
    }

    /**
     * Updates the LWJGL key code for opening the ClickGUI.
     *
     * @param clickGuiKeybind LWJGL key code
     */
    public void setClickGuiKeybind(int clickGuiKeybind) {
        this.clickGuiKeybind = KeybindSetting.sanitizeKeyCode(clickGuiKeybind);
    }

    /**
     * Returns the last selected category id in the ClickGUI sidebar.
     *
     * @return category id
     */
    public String getLastCategory() {
        return this.lastCategory;
    }

    /**
     * Sets the last selected category if valid.
     *
     * @param categoryId category id
     */
    public void setLastCategory(String categoryId) {
        Category resolved = Category.fromId(categoryId);
        if (resolved != null) {
            this.lastCategory = resolved.getId();
        }
    }

    /**
     * Sets the last selected category directly.
     *
     * @param category category enum value
     */
    public void setLastCategory(Category category) {
        if (category != null) {
            this.lastCategory = category.getId();
        }
    }

    /**
     * Returns whether a custom window position has been persisted.
     *
     * @return true if {@link #getWindowX()} and {@link #getWindowY()} are valid saved coordinates
     */
    public boolean isWindowPositionSet() {
        return this.windowPositionSet;
    }

    /**
     * Returns the saved ClickGUI logical X position.
     *
     * @return logical X coordinate
     */
    public float getWindowX() {
        return this.windowX;
    }

    /**
     * Returns the saved ClickGUI logical Y position.
     *
     * @return logical Y coordinate
     */
    public float getWindowY() {
        return this.windowY;
    }

    /**
     * Updates and marks the saved ClickGUI logical window coordinates.
     *
     * @param windowX logical X coordinate
     * @param windowY logical Y coordinate
     */
    public void setWindowPosition(float windowX, float windowY) {
        if (Float.isNaN(windowX) || Float.isInfinite(windowX)
            || Float.isNaN(windowY) || Float.isInfinite(windowY)) {
            return;
        }
        this.windowPositionSet = true;
        this.windowX = windowX;
        this.windowY = windowY;
    }

    /**
     * Clears any saved window position so the next open centers the ClickGUI.
     */
    public void clearWindowPosition() {
        this.windowPositionSet = false;
        this.windowX = 0.0f;
        this.windowY = 0.0f;
    }

    /**
     * Returns an unmodifiable view of saved HUD module layouts keyed by module id.
     *
     * @return HUD layout map
     */
    public Map<String, HudLayout> getHudLayouts() {
        return Collections.unmodifiableMap(this.hudLayouts);
    }

    /**
     * Returns the saved HUD layout for a given module id, or null if none exists.
     *
     * @param moduleId HUD module id
     * @return HUD layout or null
     */
    public HudLayout getHudLayout(String moduleId) {
        if (moduleId == null) {
            return null;
        }
        return this.hudLayouts.get(moduleId);
    }

    /**
     * Stores or removes the HUD layout for a given module id.
     *
     * @param moduleId HUD module id
     * @param layout layout coordinates, or null to remove
     */
    public void setHudLayout(String moduleId, HudLayout layout) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            return;
        }
        if (layout == null) {
            this.hudLayouts.remove(moduleId);
        } else {
            this.hudLayouts.put(moduleId, layout.copy());
        }
    }

    /**
     * Position and scale record for an individual HUD element.
     */
    public static final class HudLayout {
        private float x;
        private float y;
        private float scale;

        /**
         * Creates a HUD layout at the specified logical coordinates and scale.
         *
         * @param x logical X coordinate
         * @param y logical Y coordinate
         * @param scale element scale factor
         */
        public HudLayout(float x, float y, float scale) {
            this.x = Float.isFinite(x) ? x : 0.0f;
            this.y = Float.isFinite(y) ? y : 0.0f;
            this.scale = (Float.isFinite(scale) && scale > 0.0f) ? scale : 1.0f;
        }

        /**
         * Returns the logical X coordinate.
         *
         * @return X coordinate
         */
        public float getX() {
            return this.x;
        }

        /**
         * Returns the logical Y coordinate.
         *
         * @return Y coordinate
         */
        public float getY() {
            return this.y;
        }

        /**
         * Returns the HUD element scale factor.
         *
         * @return scale factor
         */
        public float getScale() {
            return this.scale;
        }

        /**
         * Returns a copy of this layout record.
         *
         * @return copied layout
         */
        public HudLayout copy() {
            return new HudLayout(this.x, this.y, this.scale);
        }
    }
}
