package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernInventory;
import com.hoptimization.ui.container.ModernContainerRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepts {@link GuiContainer#drawScreen} rendering passes to replace vanilla container textures,
 * slot hover highlights, and dark-grey foreground labels with {@link ModernContainerRenderer} when
 * {@link ModernInventory} is active.
 */
@Mixin(GuiContainer.class)
public abstract class MixinGuiContainer extends GuiScreen {

    @Shadow
    protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);

    @Shadow
    protected abstract void drawGuiContainerForegroundLayer(int mouseX, int mouseY);

    @Redirect(
        method = "drawScreen",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawDefaultBackground()V"
        )
    )
    private void hoptimization$redirectDefaultBackground(GuiContainer instance) {
        if (ModernInventory.isActive()) {
            ModernContainerRenderer.renderScreenBackdrop(instance);
            return;
        }
        instance.drawDefaultBackground();
    }

    @Redirect(
        method = "drawScreen",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerBackgroundLayer(FII)V"
        )
    )
    private void hoptimization$redirectBackgroundLayer(
        GuiContainer instance,
        float partialTicks,
        int mouseX,
        int mouseY
    ) {
        if (ModernInventory.isActive()) {
            ModernContainerRenderer.renderContainerBackground(instance, partialTicks, mouseX, mouseY);
            return;
        }
        this.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
    }

    @Redirect(
        method = "drawScreen",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGradientRect(IIIIII)V"
        )
    )
    private void hoptimization$redirectSlotHoverHighlight(
        GuiContainer instance,
        int left,
        int top,
        int right,
        int bottom,
        int startColor,
        int endColor
    ) {
        if (ModernInventory.isActive()) {
            ModernContainerRenderer.renderSlotHoverHighlight(left, top, right - left);
            return;
        }
        this.drawGradientRect(left, top, right, bottom, startColor, endColor);
    }

    @Redirect(
        method = "drawScreen",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerForegroundLayer(II)V"
        )
    )
    private void hoptimization$redirectForegroundLayer(GuiContainer instance, int mouseX, int mouseY) {
        if (ModernInventory.isActive()) {
            ModernContainerRenderer.setRenderingForeground(true);
            try {
                this.drawGuiContainerForegroundLayer(mouseX, mouseY);
            } finally {
                ModernContainerRenderer.setRenderingForeground(false);
            }
            return;
        }
        this.drawGuiContainerForegroundLayer(mouseX, mouseY);
    }
}
