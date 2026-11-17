package com.hoptimization.modules.misc;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Placeholder misc module for boosting scene brightness in dark areas.
 */
public class Fullbright extends Module {
    private final NumberSetting brightness;

    /**
     * Creates the Fullbright placeholder module and registers its settings.
     */
    public Fullbright() {
        super("fullbright", Category.MISC);
        this.brightness = addSetting(
            new NumberSetting(
                "brightness",
                "setting.hoptimization.fullbright.brightness.name",
                100.0D,
                0.0D,
                100.0D,
                1.0D,
                "%"
            )
        );
    }

    /**
     * Returns the target brightness percentage setting.
     *
     * @return brightness setting
     */
    public NumberSetting getBrightnessSetting() {
        return this.brightness;
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
