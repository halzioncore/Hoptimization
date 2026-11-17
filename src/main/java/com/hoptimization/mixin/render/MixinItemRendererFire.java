package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.LowFire;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lowers the first-person fire overlay when {@link LowFire} is enabled.
 */
@Mixin(ItemRenderer.class)
public class MixinItemRendererFire {
    @Unique
    private boolean hoptimization$pushedFireMatrix;

    /**
     * Target: {@code ItemRenderer.renderFireInFirstPerson(float)} HEAD.
     * Purpose: Pushes the GL matrix and translates downward proportionally to {@link LowFire}.
     * Controlled by: {@link LowFire#isActive()}.
     */
    @Inject(method = "renderFireInFirstPerson", at = @At("HEAD"), require = 0)
    private void hoptimization$onRenderFireHead(float partialTicks, CallbackInfo ci) {
        this.hoptimization$pushedFireMatrix = false;
        if (LowFire.isActive()) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0F, LowFire.getFireOffsetY(), 0.0F);
            this.hoptimization$pushedFireMatrix = true;
        }
    }

    /**
     * Target: {@code ItemRenderer.renderFireInFirstPerson(float)} RETURN.
     * Purpose: Restores the GL matrix if {@link #hoptimization$onRenderFireHead} pushed one.
     * Controlled by: {@link #hoptimization$pushedFireMatrix}.
     */
    @Inject(method = "renderFireInFirstPerson", at = @At("RETURN"), require = 0)
    private void hoptimization$onRenderFireReturn(float partialTicks, CallbackInfo ci) {
        if (this.hoptimization$pushedFireMatrix) {
            GlStateManager.popMatrix();
            this.hoptimization$pushedFireMatrix = false;
        }
    }
}
