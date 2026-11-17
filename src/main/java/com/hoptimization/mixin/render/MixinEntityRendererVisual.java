package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.HurtCam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link EntityRenderer#hurtCameraEffect(float)} to cancel or scale hurt camera roll.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererVisual {
    @Shadow
    private Minecraft mc;

    /**
     * Target: {@code EntityRenderer.hurtCameraEffect(float)} HEAD.
     * Purpose: Suppresses or scales damage camera roll via {@link HurtCam}.
     * Controlled by: {@link HurtCam#isEnabled()}.
     */
    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true, require = 0)
    private void hoptimization$onHurtCameraEffect(float partialTicks, CallbackInfo ci) {
        if (HurtCam.handleHurtCameraEffect(this.mc, partialTicks)) {
            ci.cancel();
        }
    }
}
