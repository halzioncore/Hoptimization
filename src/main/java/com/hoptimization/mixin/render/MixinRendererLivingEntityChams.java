package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.Chams;
import com.hoptimization.ui.render.ChamsRenderer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders Chams topographic contour lines and soft outer glow after the living entity model pass.
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntityChams<T extends EntityLivingBase> {
    @Shadow
    protected abstract void renderModel(
            T entitylivingbaseIn,
            float p_77036_2_,
            float p_77036_3_,
            float p_77036_4_,
            float p_77036_5_,
            float p_77036_6_,
            float scaleFactor);

    @Inject(method = "renderModel", at = @At("TAIL"))
    private void hoptimization$renderChamsContourGlow(
            T entitylivingbaseIn,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float scaleFactor,
            CallbackInfo ci) {
        if (ChamsRenderer.isRenderingChams() || !Chams.shouldRenderEntity(entitylivingbaseIn)) {
            return;
        }
        ChamsRenderer.renderOverlay(
                entitylivingbaseIn,
                () -> this.renderModel(
                        entitylivingbaseIn,
                        limbSwing,
                        limbSwingAmount,
                        ageInTicks,
                        netHeadYaw,
                        headPitch,
                        scaleFactor));
    }
}
