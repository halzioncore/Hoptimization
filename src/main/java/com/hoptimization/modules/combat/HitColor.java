package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Placeholder module for customizing entity damage tint color and opacity.
 */
public class HitColor extends Module {
    private final ColorSetting color;
    private final NumberSetting opacity;
    private final ModeSetting mode;

    /**
     * Creates the Hit Color placeholder module and registers its settings.
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

    @Override
    protected void onEnable() {
        // TODO: 占位模块，无功能
    }

    @Override
    protected void onDisable() {
        // TODO: 占位模块，无功能
    }
}
