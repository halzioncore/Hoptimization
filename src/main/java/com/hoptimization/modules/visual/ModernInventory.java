package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;

/**
 * Replaces Minecraft's vanilla container/inventory textures with a clean, restrained, modern theme-matched
 * UI (supporting frosted glass, soft rounded slot cells, modern creative tabs, and sleek potion cards).
 */
public class ModernInventory extends Module {
    public static final String ID = "modern_inventory";

    public static final String SLOT_SOFT = "soft";
    public static final String SLOT_OUTLINED = "outlined";

    private static volatile ModernInventory instance;

    private final BooleanSetting frostedBackdrop;
    private final ModeSetting slotStyle;
    private final BooleanSetting showPotions;

    /**
     * Creates the Modern Inventory module (enabled by default).
     */
    public ModernInventory() {
        super(ID, Category.VISUAL);
        this.frostedBackdrop = addSetting(
            new BooleanSetting("frosted_backdrop", "setting.hoptimization.modern_inventory.frosted_backdrop.name", true)
        );
        this.slotStyle = addSetting(
            new ModeSetting(
                "slot_style",
                "setting.hoptimization.modern_inventory.slot_style.name",
                SLOT_SOFT,
                SLOT_SOFT,
                SLOT_OUTLINED
            )
        );
        this.showPotions = addSetting(
            new BooleanSetting("show_potions", "setting.hoptimization.modern_inventory.show_potions.name", true)
        );
        instance = this;
        setEnabled(true);
    }

    @Override
    public void resetToDefaults() {
        super.resetToDefaults();
        setEnabled(true);
    }

    /**
     * Returns whether Modern Inventory container styling is active.
     *
     * @return {@code true} if enabled
     */
    public static boolean isActive() {
        ModernInventory inst = instance;
        return inst != null && inst.isEnabled();
    }

    public static boolean isFrostedBackdropEnabled() {
        ModernInventory inst = instance;
        return inst == null || inst.frostedBackdrop.isEnabled();
    }

    public static boolean isOutlinedSlots() {
        ModernInventory inst = instance;
        return inst != null && SLOT_OUTLINED.equals(inst.slotStyle.getValue());
    }

    public static boolean isShowPotionsEnabled() {
        ModernInventory inst = instance;
        return inst == null || inst.showPotions.isEnabled();
    }
}
