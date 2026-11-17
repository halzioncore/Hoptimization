package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;

/**
 * Placeholder module for classic 1.7 combat item and swing animations.
 */
public class OldAnimations extends Module {
    private final BooleanSetting swing;
    private final BooleanSetting block;
    private final BooleanSetting bow;

    /**
     * Creates the Old Animations placeholder module and registers its settings.
     */
    public OldAnimations() {
        super("old_animations", Category.COMBAT);
        this.swing = addSetting(
            new BooleanSetting("swing", "setting.hoptimization.old_animations.swing.name", true)
        );
        this.block = addSetting(
            new BooleanSetting("block", "setting.hoptimization.old_animations.block.name", true)
        );
        this.bow = addSetting(
            new BooleanSetting("bow", "setting.hoptimization.old_animations.bow.name", true)
        );
    }

    /**
     * Returns the swing animation toggle setting.
     *
     * @return swing setting
     */
    public BooleanSetting getSwingSetting() {
        return this.swing;
    }

    /**
     * Returns the block-hit animation toggle setting.
     *
     * @return block setting
     */
    public BooleanSetting getBlockSetting() {
        return this.block;
    }

    /**
     * Returns the bow pull animation toggle setting.
     *
     * @return bow setting
     */
    public BooleanSetting getBowSetting() {
        return this.bow;
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
