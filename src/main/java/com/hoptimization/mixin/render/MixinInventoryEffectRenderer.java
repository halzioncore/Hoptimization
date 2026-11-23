package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernInventory;
import com.hoptimization.ui.container.ModernContainerRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.inventory.Container;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes vanilla's missing {@code GL_BLEND} state in {@link InventoryEffectRenderer#drawActivePotionEffects()}
 * (which caused black borders/corners on potion boxes) and replaces them with modern theme-matched cards when
 * {@link ModernInventory} is enabled.
 */
@Mixin(InventoryEffectRenderer.class)
public abstract class MixinInventoryEffectRenderer extends GuiContainer {

    protected MixinInventoryEffectRenderer(Container inventorySlotsIn) {
        super(inventorySlotsIn);
    }

    @Inject(method = "drawActivePotionEffects", at = @At("HEAD"), cancellable = true)
    private void hoptimization$renderPotionEffects(CallbackInfo ci) {
        if (ModernInventory.isActive()) {
            ModernContainerRenderer.renderInventoryPotions(this, this.guiLeft, this.guiTop);
            ci.cancel();
            return;
        }
        // Vanilla 1.8.9 forgets to enable blend/alpha before drawing textured potion boxes, leaving black corners.
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
        );
        GlStateManager.enableAlpha();
    }
}
