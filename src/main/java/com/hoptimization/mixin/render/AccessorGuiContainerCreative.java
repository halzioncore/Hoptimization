package com.hoptimization.mixin.render;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link GuiContainerCreative} tab, scroll, and search field state for modern container rendering.
 */
@Mixin(GuiContainerCreative.class)
public interface AccessorGuiContainerCreative {

    @Accessor("selectedTabIndex")
    static int hoptimization$getSelectedTabIndex() {
        throw new AssertionError();
    }

    @Accessor(value = "tabPage", remap = false)
    static int hoptimization$getTabPage() {
        throw new AssertionError();
    }

    @Accessor("currentScroll")
    float hoptimization$getCurrentScroll();

    @Accessor("searchField")
    GuiTextField hoptimization$getSearchField();

    @Invoker("needsScrollBars")
    boolean hoptimization$needsScrollBars();
}
