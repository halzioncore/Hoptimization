package com.hoptimization.modules.ui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;

/**
 * Gate module controlling availability of gameplay-altering cheat modules.
 * Turning Cheat Mode off automatically disables all enabled cheat-gated modules.
 */
public class CheatMode extends Module {
    public static final String ID = "cheat_mode";

    /**
     * Creates the Cheat Mode toggle module (disabled by default).
     */
    public CheatMode() {
        super(ID, Category.UI);
    }

    @Override
    protected void onDisable() {
        ModuleManager manager = getOwnerManager() != null ? getOwnerManager() : HoptimizationMod.getModuleManager();
        if (manager != null) {
            manager.disableAllCheatModules();
        }
    }
}
