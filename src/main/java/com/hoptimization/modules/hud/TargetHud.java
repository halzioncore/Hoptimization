package com.hoptimization.modules.hud;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;

/**
 * Placeholder HUD module for showing combat target health and armor info.
 */
public class TargetHud extends Module {
    private final BooleanSetting showArmor;
    private final BooleanSetting showHealthNumber;
    private final BooleanSetting animate;

    /**
     * Creates the Target HUD placeholder module and registers its settings.
     */
    public TargetHud() {
        super("target_hud", Category.HUD);
        this.showArmor = addSetting(
            new BooleanSetting("show_armor", "setting.hoptimization.target_hud.show_armor.name", true)
        );
        this.showHealthNumber = addSetting(
            new BooleanSetting("show_health_number", "setting.hoptimization.target_hud.show_health_number.name", true)
        );
        this.animate = addSetting(
            new BooleanSetting("animate", "setting.hoptimization.target_hud.animate.name", true)
        );
    }

    /**
     * Returns the armor display toggle setting.
     *
     * @return show armor setting
     */
    public BooleanSetting getShowArmorSetting() {
        return this.showArmor;
    }

    /**
     * Returns the numeric health display toggle setting.
     *
     * @return show health number setting
     */
    public BooleanSetting getShowHealthNumberSetting() {
        return this.showHealthNumber;
    }

    /**
     * Returns the health bar animation toggle setting.
     *
     * @return animation setting
     */
    public BooleanSetting getAnimateSetting() {
        return this.animate;
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
