package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import org.lwjgl.input.Keyboard;

/**
 * Cheat-gated module removing Minecraft 1.8.9's 10-tick attack cooldown after missing a hit.
 */
public class HitDelayFix extends Module {
    public static final String ID = "hit_delay_fix";
    private static HitDelayFix instance;

    /**
     * Creates the Hit Delay Fix module (disabled by default, requires Cheat Mode).
     */
    public HitDelayFix() {
        super(ID, Category.COMBAT, Keyboard.KEY_NONE, true, true);
        instance = this;
    }

    /**
     * Fast hot-path check queried by {@link com.hoptimization.mixin.combat.MixinMinecraftCombat}.
     *
     * @return true when enabled and unlocked by Cheat Mode
     */
    public static boolean isActive() {
        HitDelayFix module = instance;
        return module != null && module.isEnabled() && module.isAvailable();
    }
}
