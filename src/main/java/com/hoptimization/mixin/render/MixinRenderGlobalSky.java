package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.Atmosphere;
import com.hoptimization.ui.render.AtmosphereRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Minecraft's default skybox (sun/moon/blocky clouds) with {@link AtmosphereRenderer#renderAbstractSky()}
 * when {@link Atmosphere} custom sky is active.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalSky {

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true)
    private void hoptimization$renderAbstractSky(float partialTicks, int pass, CallbackInfo ci) {
        if (Atmosphere.isCustomSkyEnabled()) {
            AtmosphereRenderer.renderAbstractSky();
            ci.cancel();
        }
    }
}
