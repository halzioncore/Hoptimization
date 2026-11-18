package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.esp.EspRenderer;
import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guards {@link RenderItem} state during internal {@link KillEffectsRenderer} and
 * {@link EspRenderer} passes so held items and enchanted tools do not reset {@code depthFunc},
 * {@code depthMask}, {@code alphaFunc}, or {@code GL_BLEND} mid-pass.
 */
@Mixin(RenderItem.class)
public class MixinRenderItemVisual {

    /**
     * Target: {@code RenderItem.renderEffect(IBakedModel)} HEAD.
     * Purpose: Prevents enchanted held-item glint from resetting {@code depthFunc(515)} and
     * {@code depthMask(true)} mid-pass.
     * Controlled by: {@link KillEffectsRenderer#isInternalRenderPass()} and
     * {@link EspRenderer#isInternalRenderPass()}.
     *
     * @param model baked item model receiving enchantment effect
     * @param ci    mixin callback info
     */
    @Inject(
        method = "renderEffect(Lnet/minecraft/client/resources/model/IBakedModel;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void hoptimization$onRenderEffect(IBakedModel model, CallbackInfo ci) {
        if (KillEffectsRenderer.isInternalRenderPass() || EspRenderer.isInternalRenderPass()) {
            ci.cancel();
        }
    }

    /**
     * Target: {@code RenderItem.renderItem(ItemStack, IBakedModel)} HEAD.
     * Purpose: Restores ESP pass {@code alphaFunc}, blend, and depth state right before item
     * geometry renders, overriding {@code renderItemModelTransform}'s {@code alphaFunc(0.1F)}.
     * Controlled by: {@link EspRenderer#isInternalRenderPass()}.
     *
     * @param stack rendered item stack
     * @param model baked model for the item
     * @param ci    mixin callback info
     */
    @Inject(
        method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/resources/model/IBakedModel;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void hoptimization$onRenderItemModelPre(
        ItemStack stack,
        IBakedModel model,
        CallbackInfo ci
    ) {
        if (EspRenderer.isInternalRenderPass()) {
            EspRenderer.onBeforeRenderLayers();
        }
    }

    /**
     * Target: {@code RenderItem.renderItemModelTransform(ItemStack, IBakedModel, TransformType)} RETURN.
     * Purpose: Restores blend, alpha, and depth pass state after {@code renderItemModelTransform}
     * disables {@code GL_BLEND} on exit.
     * Controlled by: {@link EspRenderer#isInternalRenderPass()} and
     * {@link KillEffectsRenderer#isInternalRenderPass()}.
     *
     * @param stack               rendered item stack
     * @param model               baked item model
     * @param cameraTransformType active transform type
     * @param ci                  mixin callback info
     */
    @SuppressWarnings("deprecation")
    @Inject(
        method = "renderItemModelTransform(Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/client/resources/model/IBakedModel;"
            + "Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V",
        at = @At("RETURN"),
        require = 0
    )
    private void hoptimization$onRenderItemModelTransformReturn(
        ItemStack stack,
        IBakedModel model,
        ItemCameraTransforms.TransformType cameraTransformType,
        CallbackInfo ci
    ) {
        if (EspRenderer.isInternalRenderPass()) {
            EspRenderer.onAfterRenderLayers();
        }
        if (KillEffectsRenderer.isInternalRenderPass()) {
            KillEffectsRenderer.onAfterRenderLayers();
        }
    }
}
