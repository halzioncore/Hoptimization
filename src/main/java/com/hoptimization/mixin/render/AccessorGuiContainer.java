package com.hoptimization.mixin.render;

import net.minecraft.client.gui.inventory.GuiContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link GuiContainer} layout bounds for {@link com.hoptimization.ui.container.ModernContainerRenderer}.
 */
@Mixin(GuiContainer.class)
public interface AccessorGuiContainer {

    @Accessor("guiLeft")
    int hoptimization$getGuiLeft();

    @Accessor("guiTop")
    int hoptimization$getGuiTop();

    @Accessor("xSize")
    int hoptimization$getXSize();

    @Accessor("ySize")
    int hoptimization$getYSize();
}
