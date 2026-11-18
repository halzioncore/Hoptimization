package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.Esp;
import com.hoptimization.modules.visual.esp.EspRenderer;
import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.EnumChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Customizes corpse orientation, ESP original-model suppression, nametag visibility, and blend/depth
 * restoration during internal {@link KillEffectsRenderer} and {@link EspRenderer} passes on
 * {@link RendererLivingEntity}.
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntityVisual {

    /**
     * Target: {@code RendererLivingEntity.rotateCorpse(EntityLivingBase, float, float, float)} HEAD.
     * Purpose: Keeps rendered kill-effect corpses upright while preserving vanilla Dinnerbone/Grumm inversion.
     * Controlled by: {@link KillEffectsRenderer#isInternalRenderPass()}.
     *
     * @param entityLivingBaseIn rendered living entity
     * @param ageInTicks entity animation tick age
     * @param rotationYaw interpolated body yaw offset
     * @param partialTicks render partial tick time
     * @param ci mixin callback info
     */
    @Inject(
        method = "rotateCorpse(Lnet/minecraft/entity/EntityLivingBase;FFF)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void hoptimization$onRotateCorpse(
        EntityLivingBase entityLivingBaseIn,
        float ageInTicks,
        float rotationYaw,
        float partialTicks,
        CallbackInfo ci
    ) {
        if (!KillEffectsRenderer.isInternalRenderPass()) {
            return;
        }
        GlStateManager.rotate(180.0F - rotationYaw, 0.0F, 1.0F, 0.0F);
        if (entityLivingBaseIn != null) {
            String unformattedName = EnumChatFormatting.getTextWithoutFormattingCodes(entityLivingBaseIn.getName());
            if (unformattedName != null
                && ("Dinnerbone".equals(unformattedName) || "Grumm".equals(unformattedName))
                && (!(entityLivingBaseIn instanceof EntityPlayer)
                    || ((EntityPlayer) entityLivingBaseIn).isWearing(EnumPlayerModelParts.CAPE))) {
                GlStateManager.translate(0.0F, entityLivingBaseIn.height + 0.1F, 0.0F);
                GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            }
        }
        ci.cancel();
    }

    /**
     * Target: {@code RendererLivingEntity.renderModel(EntityLivingBase, float, ...)} HEAD.
     * Purpose: Suppresses the vanilla world-pass player model when ESP replaces it while preserving nametags
     * and ground shadows.
     * Controlled by: {@link Esp#isActive()}, {@link Esp#shouldSuppressOriginalRender},
     * {@link KillEffectsRenderer#isInternalRenderPass()}, and {@link EspRenderer#isInternalRenderPass()}.
     *
     * @param entityLivingBaseIn rendered living entity
     * @param limbSwing limb swing progress
     * @param limbSwingAmount limb swing amount
     * @param ageInTicks entity animation tick age
     * @param netHeadYaw relative head yaw
     * @param headPitch head pitch
     * @param scaleFactor model scale factor
     * @param ci mixin callback info
     */
    @Inject(
        method = "renderModel(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void hoptimization$onRenderModel(
        EntityLivingBase entityLivingBaseIn,
        float limbSwing,
        float limbSwingAmount,
        float ageInTicks,
        float netHeadYaw,
        float headPitch,
        float scaleFactor,
        CallbackInfo ci
    ) {
        if (!KillEffectsRenderer.isInternalRenderPass()
            && !EspRenderer.isInternalRenderPass()
            && Esp.isActive()
            && Esp.shouldSuppressOriginalRender(entityLivingBaseIn)) {
            ci.cancel();
        }
    }

    /**
     * Target: {@code RendererLivingEntity.renderLayers(EntityLivingBase, float, ...)} HEAD.
     * Purpose: Restores ESP pass depth/blend state before layer rendering during internal {@link EspRenderer}
     * passes, and suppresses vanilla world-pass armor/held-item layers for players replaced by ESP.
     * Controlled by: {@link Esp#isActive()}, {@link Esp#shouldSuppressOriginalRender},
     * {@link KillEffectsRenderer#isInternalRenderPass()}, and {@link EspRenderer#isInternalRenderPass()}.
     *
     * @param entityLivingBaseIn rendered living entity
     * @param limbSwing limb swing progress
     * @param limbSwingAmount limb swing amount
     * @param partialTicks render partial tick time
     * @param ageInTicks entity animation tick age
     * @param netHeadYaw relative head yaw
     * @param headPitch head pitch
     * @param scaleFactor model scale factor
     * @param ci mixin callback info
     */
    @Inject(
        method = "renderLayers(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void hoptimization$onRenderLayersHead(
        EntityLivingBase entityLivingBaseIn,
        float limbSwing,
        float limbSwingAmount,
        float partialTicks,
        float ageInTicks,
        float netHeadYaw,
        float headPitch,
        float scaleFactor,
        CallbackInfo ci
    ) {
        if (EspRenderer.isInternalRenderPass()) {
            EspRenderer.onBeforeRenderLayers();
            return;
        }
        if (!KillEffectsRenderer.isInternalRenderPass()
            && Esp.isActive()
            && Esp.shouldSuppressOriginalRender(entityLivingBaseIn)) {
            ci.cancel();
        }
    }

    /**
     * Target: {@code RendererLivingEntity.canRenderName(EntityLivingBase)} HEAD.
     * Purpose: Hides entity nametags during internal {@link KillEffectsRenderer} and {@link EspRenderer} passes.
     * Controlled by: {@link KillEffectsRenderer#isInternalRenderPass()} and
     * {@link EspRenderer#isInternalRenderPass()}.
     *
     * @param entity rendered living entity
     * @param cir return callback controlling nametag visibility
     */
    @Inject(
        method = "canRenderName(Lnet/minecraft/entity/EntityLivingBase;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void hoptimization$onCanRenderName(
        EntityLivingBase entity,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (KillEffectsRenderer.isInternalRenderPass() || EspRenderer.isInternalRenderPass()) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    /**
     * Target: {@code RendererLivingEntity.renderLayers(EntityLivingBase, float, ...)} RETURN.
     * Purpose: Restores blending and depth state after armor/held-item layers during internal effect passes.
     * Controlled by: {@link KillEffectsRenderer#isInternalRenderPass()} and
     * {@link EspRenderer#isInternalRenderPass()}.
     *
     * @param entityLivingBaseIn rendered living entity
     * @param limbSwing limb swing progress
     * @param limbSwingAmount limb swing amount
     * @param partialTicks render partial tick time
     * @param ageInTicks entity animation tick age
     * @param netHeadYaw relative head yaw
     * @param headPitch head pitch
     * @param scaleFactor model scale factor
     * @param ci mixin callback info
     */
    @Inject(
        method = "renderLayers(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
        at = @At("RETURN"),
        require = 0
    )
    private void hoptimization$onRenderLayersReturn(
        EntityLivingBase entityLivingBaseIn,
        float limbSwing,
        float limbSwingAmount,
        float partialTicks,
        float ageInTicks,
        float netHeadYaw,
        float headPitch,
        float scaleFactor,
        CallbackInfo ci
    ) {
        if (KillEffectsRenderer.isInternalRenderPass()) {
            KillEffectsRenderer.onAfterRenderLayers();
        }
        if (EspRenderer.isInternalRenderPass()) {
            EspRenderer.onAfterRenderLayers();
        }
    }
}
