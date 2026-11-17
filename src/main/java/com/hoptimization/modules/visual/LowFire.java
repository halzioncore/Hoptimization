package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Placeholder module for lowering the first-person fire overlay.
 */
public class LowFire extends Module {
    private final NumberSetting height;

    /**
     * Creates the Low Fire placeholder module and registers its settings.
     */
    public LowFire() {
        super("low_fire", Category.VISUAL);
        this.height = addSetting(
            new NumberSetting("height", "setting.hoptimization.low_fire.height.name", 40.0D, 0.0D, 100.0D, 1.0D, "%")
        );
    }

    /**
     * Returns the fire overlay height percentage setting.
     *
     * @return height setting
     */
    public NumberSetting getHeightSetting() {
        return this.height;
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
