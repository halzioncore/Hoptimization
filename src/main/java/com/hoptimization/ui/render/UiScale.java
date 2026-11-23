package com.hoptimization.ui.render;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;

/**
 * Independent UI logical-to-physical scale system decoupled from Minecraft's GUI Scale.
 * Computes {@code clamp(displayHeight / 1080.0f * userMultiplier * 2.0f, 0.15f, 5.0f)},
 * making the new {@code 1.0x} match the previous {@code 2.0x} visual size.
 */
public final class UiScale {
    public static final float MIN_SCALE = 0.15f;
    public static final float MAX_SCALE = 5.0f;
    public static final float BASE_HEIGHT = 1080.0f;
    public static final float BASE_SCALE_FACTOR = 2.0f;
    public static final float DEFAULT_MULTIPLIER = 1.0f;
    public static final float DEFAULT_CLICKGUI_MULTIPLIER = 1.0f;
    public static final float MIN_CLICKGUI_MULTIPLIER = 0.1f;
    public static final float MAX_CLICKGUI_MULTIPLIER = 2.0f;

    private static final float[] PRESET_MULTIPLIERS = new float[] {
        0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f
    };

    private static volatile float userMultiplier = DEFAULT_MULTIPLIER;

    private UiScale() {
    }

    /**
     * Returns the configured user scale multiplier (default 1.0).
     *
     * @return user multiplier
     */
    public static float getUserMultiplier() {
        return userMultiplier;
    }

    /**
     * Sets the user scale multiplier; non-finite or non-positive values revert to {@link #DEFAULT_MULTIPLIER}.
     *
     * @param multiplier desired user scale multiplier
     */
    public static void setUserMultiplier(float multiplier) {
        if (Float.isNaN(multiplier) || Float.isInfinite(multiplier) || multiplier <= 0.0f) {
            userMultiplier = DEFAULT_MULTIPLIER;
            return;
        }
        userMultiplier = multiplier;
    }

    /**
     * Computes the clamped UI scale factor for an explicit display height and multiplier.
     *
     * @param displayHeight physical display height in pixels
     * @param multiplier    user scale multiplier
     * @return clamped scale factor in [{@link #MIN_SCALE}, {@link #MAX_SCALE}]
     */
    public static float compute(int displayHeight, float multiplier) {
        float safeMultiplier = (Float.isNaN(multiplier) || Float.isInfinite(multiplier) || multiplier <= 0.0f)
            ? DEFAULT_MULTIPLIER
            : multiplier;
        float safeHeight = Math.max(0, displayHeight);
        float raw = (safeHeight / BASE_HEIGHT) * safeMultiplier * BASE_SCALE_FACTOR;
        if (raw <= MIN_SCALE) {
            return MIN_SCALE;
        }
        if (raw >= MAX_SCALE) {
            return MAX_SCALE;
        }
        return raw;
    }

    /**
     * Returns the current UI scale factor based on the display height and active user multiplier.
     *
     * @return active UI scale factor
     */
    public static float getScale() {
        return compute(getDisplayHeight(), userMultiplier);
    }

    /**
     * Returns the UI scale factor using the current display height and a candidate multiplier.
     *
     * @param multiplier candidate multiplier
     * @return clamped scale factor
     */
    public static float getScale(float multiplier) {
        return compute(getDisplayHeight(), multiplier);
    }

    /**
     * Computes the independent ClickGUI scale factor for a ClickGUI multiplier clamped to
     * [{@link #MIN_CLICKGUI_MULTIPLIER}, {@link #MAX_CLICKGUI_MULTIPLIER}].
     *
     * @param multiplier ClickGUI scale multiplier
     * @return clamped scale factor in [{@link #MIN_SCALE}, {@link #MAX_SCALE}]
     */
    public static float getClickGuiScale(float multiplier) {
        float safeMultiplier;
        if (Float.isNaN(multiplier) || Float.isInfinite(multiplier) || multiplier <= 0.0f) {
            safeMultiplier = DEFAULT_CLICKGUI_MULTIPLIER;
        } else if (multiplier < MIN_CLICKGUI_MULTIPLIER) {
            safeMultiplier = MIN_CLICKGUI_MULTIPLIER;
        } else if (multiplier > MAX_CLICKGUI_MULTIPLIER) {
            safeMultiplier = MAX_CLICKGUI_MULTIPLIER;
        } else {
            safeMultiplier = multiplier;
        }
        return compute(getDisplayHeight(), safeMultiplier);
    }

    /**
     * Returns the current physical display width in pixels (at least 1).
     *
     * @return display width in physical pixels
     */
    public static int getDisplayWidth() {
        if (Display.isCreated()) {
            return Math.max(1, Display.getWidth());
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            return Math.max(1, mc.displayWidth);
        }
        return 1;
    }

    /**
     * Returns the current physical display height in pixels (at least 1).
     *
     * @return display height in physical pixels
     */
    public static int getDisplayHeight() {
        if (Display.isCreated()) {
            return Math.max(1, Display.getHeight());
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            return Math.max(1, mc.displayHeight);
        }
        return 1;
    }

    /**
     * Returns the logical viewport width at the current UI scale.
     *
     * @return logical width
     */
    public static float getLogicalWidth() {
        return getLogicalWidth(getScale());
    }

    /**
     * Returns the logical viewport width at an explicit scale factor.
     *
     * @param scale active UI scale factor
     * @return logical width
     */
    public static float getLogicalWidth(float scale) {
        float safeScale = scale > 0.0f ? scale : MIN_SCALE;
        return getDisplayWidth() / safeScale;
    }

    /**
     * Returns the logical viewport height at the current UI scale.
     *
     * @return logical height
     */
    public static float getLogicalHeight() {
        return getLogicalHeight(getScale());
    }

    /**
     * Returns the logical viewport height at an explicit scale factor.
     *
     * @param scale active UI scale factor
     * @return logical height
     */
    public static float getLogicalHeight(float scale) {
        float safeScale = scale > 0.0f ? scale : MIN_SCALE;
        return getDisplayHeight() / safeScale;
    }

    /**
     * Converts a logical coordinate or size to physical pixels.
     *
     * @param logical logical value
     * @return physical value
     */
    public static float logicalToPhysical(float logical) {
        return logical * getScale();
    }

    /**
     * Converts a physical pixel coordinate or size to logical units.
     *
     * @param physical physical pixel value
     * @return logical value
     */
    public static float physicalToLogical(float physical) {
        return physical / getScale();
    }

    /**
     * Converts a Minecraft ScaledResolution mouse X coordinate to logical UI X.
     *
     * @param mcMouseX      mouse X from GuiScreen
     * @param mcScaledWidth ScaledResolution.getScaledWidth_double()
     * @return logical mouse X
     */
    public static float mcScaledToLogicalX(int mcMouseX, double mcScaledWidth) {
        return mcScaledToLogicalX(mcMouseX, mcScaledWidth, getScale());
    }

    /**
     * Converts a Minecraft ScaledResolution mouse X coordinate to logical UI X for an explicit scale.
     *
     * @param mcMouseX      mouse X from GuiScreen
     * @param mcScaledWidth ScaledResolution.getScaledWidth_double()
     * @param scale         active UI scale factor
     * @return logical mouse X
     */
    public static float mcScaledToLogicalX(int mcMouseX, double mcScaledWidth, float scale) {
        if (mcScaledWidth <= 0.0D) {
            return 0.0f;
        }
        return (float) ((mcMouseX / mcScaledWidth) * getLogicalWidth(scale));
    }

    /**
     * Converts a Minecraft ScaledResolution mouse Y coordinate to logical UI Y.
     *
     * @param mcMouseY       mouse Y from GuiScreen
     * @param mcScaledHeight ScaledResolution.getScaledHeight_double()
     * @return logical mouse Y
     */
    public static float mcScaledToLogicalY(int mcMouseY, double mcScaledHeight) {
        return mcScaledToLogicalY(mcMouseY, mcScaledHeight, getScale());
    }

    /**
     * Converts a Minecraft ScaledResolution mouse Y coordinate to logical UI Y for an explicit scale.
     *
     * @param mcMouseY       mouse Y from GuiScreen
     * @param mcScaledHeight ScaledResolution.getScaledHeight_double()
     * @param scale          active UI scale factor
     * @return logical mouse Y
     */
    public static float mcScaledToLogicalY(int mcMouseY, double mcScaledHeight, float scale) {
        if (mcScaledHeight <= 0.0D) {
            return 0.0f;
        }
        return (float) ((mcMouseY / mcScaledHeight) * getLogicalHeight(scale));
    }

    /**
     * Converts a bottom-up LWJGL {@code Mouse.getY()} coordinate to top-down logical UI Y.
     *
     * @param lwjglMouseY physical bottom-up mouse Y
     * @return top-down logical mouse Y
     */
    public static float physicalMouseYToLogical(int lwjglMouseY) {
        return physicalMouseYToLogical(lwjglMouseY, getScale());
    }

    /**
     * Converts a bottom-up LWJGL {@code Mouse.getY()} coordinate to top-down logical UI Y for an explicit scale.
     *
     * @param lwjglMouseY physical bottom-up mouse Y
     * @param scale       active UI scale factor
     * @return top-down logical mouse Y
     */
    public static float physicalMouseYToLogical(int lwjglMouseY, float scale) {
        float safeScale = scale > 0.0f ? scale : MIN_SCALE;
        return (getDisplayHeight() - lwjglMouseY) / safeScale;
    }

    /**
     * Returns the number of standard UI multiplier presets defined by the design spec.
     *
     * @return preset count
     */
    public static int getPresetCount() {
        return PRESET_MULTIPLIERS.length;
    }

    /**
     * Returns the standard UI multiplier preset at {@code index}.
     *
     * @param index index in [0, {@link #getPresetCount()})
     * @return preset multiplier
     */
    public static float getPreset(int index) {
        return PRESET_MULTIPLIERS[index];
    }
}
