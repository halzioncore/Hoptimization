package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Placeholder module for tracking consecutive combat hits.
 */
public class ComboCounter extends Module {
    private final NumberSetting timeout;

    /**
     * Creates the Combo Counter placeholder module and registers its settings.
     */
    public ComboCounter() {
        super("combo_counter", Category.COMBAT);
        this.timeout = addSetting(
            new NumberSetting(
                "timeout",
                "setting.hoptimization.combo_counter.timeout.name",
                3.0D,
                1.0D,
                5.0D,
                0.5D,
                "s"
            )
        );
    }

    /**
     * Returns the combo reset timeout setting in seconds.
     *
     * @return timeout setting
     */
    public NumberSetting getTimeoutSetting() {
        return this.timeout;
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
