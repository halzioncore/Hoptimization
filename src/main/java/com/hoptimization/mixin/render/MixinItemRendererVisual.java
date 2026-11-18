package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.esp.EspRenderer;
import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores pass depth and blend state after {@link ItemRenderer#renderItem} so translucent
 * held blocks cannot leak {@code depthMask(true)} into subsequent player render layers.
 */
@Mixin(ItemRenderer.class)
public class MixinItemRendererVisual {

    /**
     * Target: {@code ItemRenderer.renderItem(EntityLivingBase, ItemStack, TransformType)} RETURN.
     * Purpose: Restores pass {@code depthMask}, {@code depthFunc}, and blend state after
     * {@code isBlockTranslucent} re-enables {@code depthMask(true)} on exit.
     * Controlled by: {@link EspRenderer#isInternalRenderPass()} and
     * {@link KillEffectsRenderer#isInternalRenderPass()}.
     *
     * @param entityIn  entity holding or wearing the rendered item
     * @param heldStack rendered item stack
     * @param transform item camera transform type
     * @param ci        mixin callback info
     */
    @SuppressWarnings("deprecation")
    @Inject(
        method = "renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V",
        at = @At("RETURN"),
        require = 0
    )
    private void hoptimization$onRenderItemReturn(
        EntityLivingBase entityIn,
        ItemStack heldStack,
        ItemCameraTransforms.TransformType transform,
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
