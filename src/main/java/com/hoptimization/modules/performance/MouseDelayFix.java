package com.hoptimization.modules.performance;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;

/**
 * Fixes the MC 1.8.9 one-tick head-yaw raytrace delay on the local player.
 */
public class MouseDelayFix extends Module {
    public static final String ID = "mouse_delay_fix";

    /**
     * Creates the Mouse Delay Fix module.
     */
    public MouseDelayFix() {
        super(ID, Category.PERFORMANCE);
    }

    /**
     * Returns whether Mouse Delay Fix is currently registered and enabled.
     *
     * @return true if local player look vector should bypass {@code rotationYawHead} lag
     */
    public static boolean isFixActive() {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        MouseDelayFix module = manager != null ? manager.getModule(MouseDelayFix.class) : null;
        return module != null && module.isEnabled();
    }
}
