package com.hoptimization.mixin.client;

import com.hoptimization.modules.performance.ParticleLimit;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Intercepts {@link EffectRenderer#addEffect(EntityFX)} to enforce {@link ParticleLimit} caps and filters.
 */
@Mixin(EffectRenderer.class)
public class MixinEffectRendererPerf {
    @Shadow
    private List<EntityFX>[][] fxLayers;

    /**
     * Target: {@code EffectRenderer.addEffect(EntityFX)} HEAD.
     * Purpose: Drops excess or filtered particles before allocating into particle buckets.
     * Controlled by: {@link ParticleLimit#shouldSpawnEffect(EntityFX, List[][])}.
     */
    @Inject(method = "addEffect", at = @At("HEAD"), cancellable = true, require = 0)
    private void hoptimization$onAddEffect(EntityFX effect, CallbackInfo ci) {
        if (!ParticleLimit.shouldSpawnEffect(effect, this.fxLayers)) {
            ci.cancel();
        }
    }
}
