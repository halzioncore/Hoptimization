package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;

/**
 * Replaces Minecraft's vanilla main menu, settings/options backgrounds, buttons, and sliders with
 * a sleek, theme-consistent modern UI.
 */
public class ModernVanillaUI extends Module {
    public static final String ID = "modern_vanilla_ui";

    private static volatile ModernVanillaUI instance;

    private final BooleanSetting modernMainMenu;
    private final BooleanSetting modernButtons;
    private final BooleanSetting modernBackgrounds;
    private final BooleanSetting waveStripes;

    /**
     * Creates the Modern Vanilla UI module (enabled by default).
     */
    public ModernVanillaUI() {
        super(ID, Category.VISUAL);
        this.modernMainMenu = addSetting(
            new BooleanSetting("modern_main_menu", "setting.hoptimization.modern_vanilla_ui.modern_main_menu.name", true)
        );
        this.modernButtons = addSetting(
            new BooleanSetting("modern_buttons", "setting.hoptimization.modern_vanilla_ui.modern_buttons.name", true)
        );
        this.modernBackgrounds = addSetting(
            new BooleanSetting("modern_backgrounds", "setting.hoptimization.modern_vanilla_ui.modern_backgrounds.name", true)
        );
        this.waveStripes = addSetting(
            new BooleanSetting("wave_stripes", "setting.hoptimization.modern_vanilla_ui.wave_stripes.name", true)
        );
        instance = this;
        setEnabled(true);
    }

    @Override
    public void resetToDefaults() {
        super.resetToDefaults();
        setEnabled(true);
    }

    public static boolean isActive() {
        ModernVanillaUI inst = instance;
        return inst != null && inst.isEnabled();
    }

    public static boolean isMainMenuEnabled() {
        ModernVanillaUI inst = instance;
        return isActive() && (inst == null || inst.modernMainMenu.isEnabled());
    }

    public static boolean isButtonsEnabled() {
        ModernVanillaUI inst = instance;
        return isActive() && (inst == null || inst.modernButtons.isEnabled());
    }

    public static boolean isBackgroundsEnabled() {
        ModernVanillaUI inst = instance;
        return isActive() && (inst == null || inst.modernBackgrounds.isEnabled());
    }

    public static boolean isWaveStripesEnabled() {
        ModernVanillaUI inst = instance;
        return isActive() && (inst == null || inst.waveStripes.isEnabled());
    }
}
