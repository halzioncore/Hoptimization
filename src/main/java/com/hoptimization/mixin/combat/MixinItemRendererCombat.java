package com.hoptimization.mixin.combat;

import com.hoptimization.modules.combat.OldAnimations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks first-person item rendering in {@link ItemRenderer} for 1.7-style swing-while-using
 * and sword block-hit visuals. Never touches {@code renderFireInFirstPerson}.
 */
@Mixin(ItemRenderer.class)
public class MixinItemRendererCombat {

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private ItemStack itemToRender;

    @Unique
    private float hoptimization$partialTicks;

    /**
     * Target: {@code ItemRenderer.renderItemInFirstPerson(float)} (HEAD).
     * Purpose: Cache current partialTicks for swing progress interpolation when transforming held items.
     * Controlled by: {@link OldAnimations#isActive()}.
     *
     * @param partialTicks partial render tick time
     * @param ci mixin callback info
     */
    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"), require = 0)
    private void hoptimization$capturePartialTicks(float partialTicks, CallbackInfo ci) {
        this.hoptimization$partialTicks = partialTicks;
    }

    /**
     * Target: {@code ItemRenderer.transformFirstPersonItem(float, float)} (HEAD, swingProgress arg).
     * Purpose: Restore 1.7 swing-while-using (block-hit, bow, eat/drink) when 1.8 passes 0.0F.
     * Controlled by: {@link OldAnimations#shouldSwingItemInUse(net.minecraft.item.EnumAction)}.
     *
     * @param swingProgress incoming swing progress parameter
     * @return restored swing progress when using an enabled action, otherwise vanilla value
     */
    @ModifyVariable(
        method = "transformFirstPersonItem",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 1,
        require = 0
    )
    private float hoptimization$modifySwingProgress(float swingProgress) {
        if (swingProgress != 0.0F || !OldAnimations.isActive()) {
            return swingProgress;
        }
        if (this.mc == null || this.mc.thePlayer == null || this.itemToRender == null) {
            return swingProgress;
        }
        if (this.mc.thePlayer.getItemInUseCount() <= 0) {
            return swingProgress;
        }
        if (!OldAnimations.shouldSwingItemInUse(this.itemToRender.getItemUseAction())) {
            return swingProgress;
        }
        return this.mc.thePlayer.getSwingProgress(this.hoptimization$partialTicks);
    }

    /**
     * Target: {@code ItemRenderer.doBlockTransformations()} (HEAD).
     * Purpose: Apply 1.7-style sword blocking stance offset inside first-person item matrix scope.
     * Controlled by: {@link OldAnimations#isBlockTransformActive()}.
     *
     * @param ci mixin callback info
     */
    @Inject(method = "doBlockTransformations", at = @At("HEAD"), require = 0)
    private void hoptimization$onDoBlockTransformations(CallbackInfo ci) {
        if (OldAnimations.isBlockTransformActive()) {
            GlStateManager.translate(-0.15F, 0.16F, 0.0F);
        }
    }
}
