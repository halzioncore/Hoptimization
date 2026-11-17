package com.hoptimization.ui.clickgui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import net.minecraft.util.ResourceLocation;

/**
 * Pre-allocated monochrome icon resource locations under {@code assets/hoptimization/texture/icon/}.
 */
public final class ClickGuiIcons {
    public static final ResourceLocation SWORD = icon("sword");
    public static final ResourceLocation WIND = icon("wind");
    public static final ResourceLocation EYE = icon("eye");
    public static final ResourceLocation LAYOUT = icon("layout");
    public static final ResourceLocation GAUGE = icon("gauge");
    public static final ResourceLocation SLIDERS = icon("sliders");
    public static final ResourceLocation SEARCH = icon("search");
    public static final ResourceLocation SETTINGS = icon("settings");
    public static final ResourceLocation CHEVRON_DOWN = icon("chevron-down");
    public static final ResourceLocation CHECK = icon("check");
    public static final ResourceLocation CLOSE = icon("x");
    public static final ResourceLocation KEYBOARD = icon("keyboard");
    public static final ResourceLocation MOVE = icon("move");

    private ClickGuiIcons() {
    }

    /**
     * Resolves the icon texture for the given module category without runtime allocations.
     *
     * @param category target module category
     * @return monochrome icon {@link ResourceLocation}
     */
    public static ResourceLocation forCategory(Category category) {
        if (category == null) {
            return SLIDERS;
        }
        switch (category) {
            case COMBAT:
                return SWORD;
            case MOVEMENT:
                return WIND;
            case VISUAL:
                return EYE;
            case UI:
                return LAYOUT;
            case PERFORMANCE:
                return GAUGE;
            case MISC:
            default:
                return SLIDERS;
        }
    }

    private static ResourceLocation icon(String name) {
        return new ResourceLocation(HoptimizationMod.MODID, "texture/icon/" + name + ".png");
    }
}
