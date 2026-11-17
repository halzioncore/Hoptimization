package com.hoptimization.modules.hud;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;

/**
 * Placeholder HUD module for displaying real-time clicks per second.
 */
public class CpsDisplay extends Module {
    private final BooleanSetting showRightClick;
    private final ModeSetting style;

    /**
     * Creates the CPS Display placeholder module and registers its settings.
     */
    public CpsDisplay() {
        super("cps_display", Category.HUD);
        this.showRightClick = addSetting(
            new BooleanSetting("show_right_click", "setting.hoptimization.cps_display.show_right_click.name", true)
        );
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.cps_display.style.name", "card", "card", "text")
        );
    }

    /**
     * Returns the right-click CPS visibility toggle setting.
     *
     * @return show right-click setting
     */
    public BooleanSetting getShowRightClickSetting() {
        return this.showRightClick;
    }

    /**
     * Returns the HUD rendering style setting.
     *
     * @return style setting
     */
    public ModeSetting getStyleSetting() {
        return this.style;
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
