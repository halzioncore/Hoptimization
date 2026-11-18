package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses enchanted armor glint rendering during internal {@link KillEffectsRenderer} passes
 * so {@link LayerArmorBase} does not reset depth and blend state mid-pass.
 */
@Mixin(LayerArmorBase.class)
public class MixinLayerArmorBaseVisual {

    /**
     * Target: {@code LayerArmorBase.renderGlint(EntityLivingBase, ModelBase, float, ...)} HEAD.
     * Purpose: Prevents enchanted armor glint from resetting depthMask, depthFunc, and blend mid-pass.
     * Controlled by: {@link KillEffectsRenderer#isInternalRenderPass()}.
     *
     * @param entitylivingbaseIn rendered living entity
     * @param modelbaseIn armor piece model
     * @param limbSwing limb swing progress
     * @param limbSwingAmount limb swing amount
     * @param partialTicks render partial tick time
     * @param ageInTicks entity animation tick age
     * @param netHeadYaw relative head yaw
     * @param headPitch head pitch
     * @param scale model scale factor
     * @param ci mixin callback info
     */
    @Inject(
        method = "renderGlint(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/client/model/ModelBase;FFFFFFF)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void hoptimization$onRenderGlint(
        EntityLivingBase entitylivingbaseIn,
        ModelBase modelbaseIn,
        float limbSwing,
        float limbSwingAmount,
        float partialTicks,
        float ageInTicks,
        float netHeadYaw,
        float headPitch,
        float scale,
        CallbackInfo ci
    ) {
        if (KillEffectsRenderer.isInternalRenderPass()) {
            ci.cancel();
        }
    }
}
