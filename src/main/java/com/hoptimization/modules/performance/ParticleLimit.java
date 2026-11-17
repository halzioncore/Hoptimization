package com.hoptimization.modules.performance;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Placeholder performance module for limiting rendered particles.
 */
public class ParticleLimit extends Module {
    private final NumberSetting limit;
    private final ModeSetting mode;

    /**
     * Creates the Particle Limit placeholder module and registers its settings.
     */
    public ParticleLimit() {
        super("particle_limit", Category.PERFORMANCE);
        this.limit = addSetting(
            new NumberSetting(
                "limit",
                "setting.hoptimization.particle_limit.limit.name",
                1000.0D,
                0.0D,
                4000.0D,
                100.0D
            )
        );
        this.mode = addSetting(
            new ModeSetting(
                "mode",
                "setting.hoptimization.particle_limit.mode.name",
                "smart",
                "all",
                "decreased",
                "minimal",
                "off",
                "smart"
            )
        );
    }

    /**
     * Returns the maximum particle count setting.
     *
     * @return particle limit setting
     */
    public NumberSetting getLimitSetting() {
        return this.limit;
    }

    /**
     * Returns the particle filtering mode setting (5 options for dropdown UI demonstration).
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
