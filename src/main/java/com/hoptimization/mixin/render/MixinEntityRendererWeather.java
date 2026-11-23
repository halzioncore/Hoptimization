package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.Atmosphere;
import com.hoptimization.ui.render.AtmosphereRenderer;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Minecraft's default rain/snow sheets with {@link AtmosphereRenderer#renderCustomRain(float)}
 * when {@link Atmosphere} custom weather is active.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererWeather {

    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void hoptimization$renderCustomRain(float partialTicks, CallbackInfo ci) {
        if (Atmosphere.isCustomRainEnabled()) {
            AtmosphereRenderer.renderCustomRain(partialTicks);
            ci.cancel();
        }
    }
}
