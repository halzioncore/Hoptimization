package com.hoptimization.modules.visual;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Lowers the first-person fire overlay proportionally to improve visibility while burning.
 */
public class LowFire extends Module {
    public static final String ID = "low_fire";
    private static final float MAX_DOWNWARD_OFFSET = -0.55F;

    private final NumberSetting height;

    /**
     * Creates the Low Fire module and registers its settings.
     */
    public LowFire() {
        super(ID, Category.VISUAL);
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

    /**
     * Returns whether Low Fire is currently registered and enabled.
     *
     * @return true if first-person fire should be lowered
     */
    public static boolean isActive() {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        LowFire module = manager != null ? manager.getModule(LowFire.class) : null;
        return module != null && module.isEnabled();
    }

    /**
     * Computes the vertical GL translation offset corresponding to the configured fire height percentage.
     *
     * @return downward Y offset in first-person fire space, or {@code 0.0F} when disabled
     */
    public static float getFireOffsetY() {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        LowFire module = manager != null ? manager.getModule(LowFire.class) : null;
        if (module == null || !module.isEnabled()) {
            return 0.0F;
        }
        float ratio = module.height.getFloatValue() / 100.0F;
        if (ratio < 0.0F) {
            ratio = 0.0F;
        } else if (ratio > 1.0F) {
            ratio = 1.0F;
        }
        // At 70-deg hand FOV and z=-0.5, -0.55 places the fire quad top below the viewport bottom at 0%.
        return MAX_DOWNWARD_OFFSET * (1.0F - ratio);
    }
}
