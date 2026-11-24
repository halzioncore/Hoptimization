package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernInventory;
import com.hoptimization.ui.container.ModernContainerRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.inventory.Container;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@link InventoryEffectRenderer} screens strictly centered regardless of active potion effects,
 * fixes vanilla's missing {@code GL_BLEND} state in {@link InventoryEffectRenderer#drawActivePotionEffects()},
 * and delegates to {@link ModernContainerRenderer} when {@link ModernInventory} is enabled.
 */
@Mixin(InventoryEffectRenderer.class)
public abstract class MixinInventoryEffectRenderer extends GuiContainer {

    @Shadow
    protected boolean hasActivePotionEffects;

    protected MixinInventoryEffectRenderer(Container inventorySlotsIn) {
        super(inventorySlotsIn);
    }

    /**
     * 目标方法：InventoryEffectRenderer#updateActivePotionEffects()V
     * 目的：保持 guiLeft 始终居中于 (width - xSize) / 2，避免因药水效果生效或过期而向右偏移 60px；全局生效。
     */
    @Inject(method = "updateActivePotionEffects", at = @At("HEAD"), cancellable = true)
    private void hoptimization$keepInventoryCentered(CallbackInfo ci) {
        this.guiLeft = (this.width - this.xSize) / 2;
        this.hasActivePotionEffects = ModernContainerRenderer.hasVisiblePotionEffects(this.mc);
        ci.cancel();
    }

    /**
     * 目标方法：InventoryEffectRenderer#drawActivePotionEffects()V
     * 目的：ModernInventory 开启时渲染现代自适应药水卡片，关闭时补齐原版缺失的混合状态。
     */
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
