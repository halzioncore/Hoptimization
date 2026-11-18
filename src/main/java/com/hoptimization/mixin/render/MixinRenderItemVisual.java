package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.esp.EspRenderer;
import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses enchanted item glint rendering during internal {@link KillEffectsRenderer} and
 * {@link EspRenderer} passes so {@link RenderItem} does not reset {@code depthFunc} or
 * {@code depthMask} mid-pass.
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
     * @param ci mixin callback info
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
}
