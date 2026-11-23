package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.NumberSetting;
import org.lwjgl.input.Keyboard;

/**
 * Non-toggleable configuration module exposing ClickGUI scale and open keybind.
 */
public class ClickGuiModule extends Module {
    public static final String ID = "clickgui";
    public static final double MIN_SCALE = 0.1D;
    public static final double MAX_SCALE = 2.0D;
    public static final double SCALE_STEP = 0.05D;
    public static final double DEFAULT_SCALE = 1.0D;

    private final NumberSetting scale;

    /**
     * Creates the ClickGUI settings module (non-toggleable, default keybind RSHIFT).
     */
    public ClickGuiModule() {
        super(ID, Category.UI, Keyboard.KEY_RSHIFT, false, false);
        this.scale = addSetting(
            new NumberSetting(
                "scale",
                "setting.hoptimization.clickgui.scale.name",
                DEFAULT_SCALE,
                MIN_SCALE,
                MAX_SCALE,
                SCALE_STEP,
                "x"
            )
        );
    }

    /**
     * Returns the ClickGUI independent scale multiplier setting ({@code 0.75-2.0x}).
     *
     * @return ClickGUI scale setting
     */
    public NumberSetting getScaleSetting() {
        return this.scale;
    }
}
