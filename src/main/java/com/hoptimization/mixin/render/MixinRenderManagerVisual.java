package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.KillEffects;
import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses vanilla world rendering of dying entities already captured by {@link KillEffects}.
 */
@Mixin(RenderManager.class)
public class MixinRenderManagerVisual {

    /**
     * Target: {@code RenderManager.doRenderEntity(Entity, double, double, double, float, float, boolean)} HEAD.
     * Purpose: Prevents duplicate vanilla rendering of captured corpses outside internal effect passes.
     * Controlled by: {@link KillEffects#isActive()} and {@link KillEffectsRenderer#isInternalRenderPass()}.
     *
     * @param entity entity being rendered
     * @param x interpolated X offset relative to camera
     * @param y interpolated Y offset relative to camera
     * @param z interpolated Z offset relative to camera
     * @param entityYaw interpolated entity yaw
     * @param partialTicks render partial tick time
     * @param hideDebugBox whether debug bounding box rendering is suppressed
     * @param cir return callback controlling whether rendering occurred
     */
    @Inject(
        method = "doRenderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void hoptimization$onDoRenderEntity(
        Entity entity,
        double x,
        double y,
        double z,
        float entityYaw,
        float partialTicks,
        boolean hideDebugBox,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!KillEffectsRenderer.isInternalRenderPass()
            && KillEffects.isActive()
            && KillEffects.shouldSuppressOriginalRender(entity)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
