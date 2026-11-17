package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Customizes entity and armor damage flash tint color and opacity during hurt animations.
 */
public class HitColor extends Module {
    private static HitColor instance;

    private final ColorSetting color;
    private final NumberSetting opacity;
    private final ModeSetting mode;

    private float cachedRed;
    private float cachedGreen;
    private float cachedBlue;
    private float cachedAlpha;
    private boolean cachedArmorMode;

    /**
     * Creates the Hit Color module and registers its tint settings.
     */
    public HitColor() {
        super("hit_color", Category.COMBAT);
        this.color = addSetting(
            new ColorSetting("color", "setting.hoptimization.hit_color.color.name", 0xFFFF0000, false)
        );
        this.opacity = addSetting(
            new NumberSetting("opacity", "setting.hoptimization.hit_color.opacity.name", 60.0D, 0.0D, 100.0D, 1.0D, "%")
        );
        this.mode = addSetting(
            new ModeSetting("mode", "setting.hoptimization.hit_color.mode.name", "entity", "entity", "armor")
        );

        Runnable cacheUpdater = new Runnable() {
            @Override
            public void run() {
                refreshCachedValues();
            }
        };
        this.color.addChangeListener(cacheUpdater);
        this.opacity.addChangeListener(cacheUpdater);
        this.mode.addChangeListener(cacheUpdater);
        refreshCachedValues();
        instance = this;
    }

    /**
     * Returns the hit tint color setting.
     *
     * @return color setting
     */
    public ColorSetting getColorSetting() {
        return this.color;
    }

    /**
     * Returns the hit tint opacity percentage setting.
     *
     * @return opacity setting
     */
    public NumberSetting getOpacitySetting() {
        return this.opacity;
    }

    /**
     * Returns the hit tint target mode setting.
     *
     * @return mode setting
     */
    public ModeSetting getModeSetting() {
        return this.mode;
    }

    /**
     * Returns whether HitColor is currently enabled.
     *
     * @return true if active
     */
    public static boolean isActive() {
        HitColor module = instance;
        return module != null && module.isEnabled();
    }

    /**
     * Returns whether HitColor is enabled and configured to tint worn armor as well.
     *
     * @return true when armor damage tinting should apply
     */
    public static boolean isArmorModeActive() {
        HitColor module = instance;
        return module != null && module.isEnabled() && module.cachedArmorMode;
    }

    /**
     * Returns the cached normalized red channel in {@code [0, 1]}.
     *
     * @return red component
     */
    public static float getRed() {
        HitColor module = instance;
        return module != null ? module.cachedRed : 1.0F;
    }

    /**
     * Returns the cached normalized green channel in {@code [0, 1]}.
     *
     * @return green component
     */
    public static float getGreen() {
        HitColor module = instance;
        return module != null ? module.cachedGreen : 0.0F;
    }

    /**
     * Returns the cached normalized blue channel in {@code [0, 1]}.
     *
     * @return blue component
     */
    public static float getBlue() {
        HitColor module = instance;
        return module != null ? module.cachedBlue : 0.0F;
    }

    /**
     * Returns the cached normalized hurt flash opacity in {@code [0, 1]}.
     *
     * @return alpha/tint weight
     */
    public static float getAlpha() {
        HitColor module = instance;
        return module != null ? module.cachedAlpha : 0.3F;
    }

    private void refreshCachedValues() {
        this.cachedRed = this.color.getRed() / 255.0F;
        this.cachedGreen = this.color.getGreen() / 255.0F;
        this.cachedBlue = this.color.getBlue() / 255.0F;
        float alpha = this.opacity.getFloatValue() / 100.0F;
        if (alpha < 0.0F) {
            alpha = 0.0F;
        } else if (alpha > 1.0F) {
            alpha = 1.0F;
        }
        this.cachedAlpha = alpha;
        this.cachedArmorMode = this.mode.is("armor");
    }
}
