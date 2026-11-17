package com.hoptimization.mixin.combat;

import com.hoptimization.modules.combat.HitColor;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.FloatBuffer;

/**
 * Hooks {@link RendererLivingEntity#setBrightness(EntityLivingBase, float, boolean)} to apply
 * {@link HitColor}'s custom hurt flash tint and alpha while preserving OpenGL texture unit state.
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntityCombat {

    @Shadow
    protected FloatBuffer brightnessBuffer;

    /**
     * Target: {@code RendererLivingEntity.setBrightness(EntityLivingBase, float, boolean)} (RETURN).
     * Purpose: Replace vanilla red hurt tint with configured HitColor RGBA without altering non-hurt flashes.
     * Controlled by: {@link HitColor#isActive()}.
     *
     * @param entitylivingbaseIn rendered living entity
     * @param partialTicks render partial tick time
     * @param combineTextures whether layer allows brightness combine
     * @param cir return callback indicating whether brightness was applied
     */
    @Inject(method = "setBrightness", at = @At("RETURN"), require = 0)
    private void hoptimization$onSetBrightnessReturn(
        EntityLivingBase entitylivingbaseIn,
        float partialTicks,
        boolean combineTextures,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!HitColor.isActive() || !cir.getReturnValueZ() || entitylivingbaseIn == null) {
            return;
        }
        if (entitylivingbaseIn.hurtTime <= 0 && entitylivingbaseIn.deathTime <= 0) {
            return;
        }
        this.brightnessBuffer.position(0);
        this.brightnessBuffer.put(HitColor.getRed());
        this.brightnessBuffer.put(HitColor.getGreen());
        this.brightnessBuffer.put(HitColor.getBlue());
        this.brightnessBuffer.put(HitColor.getAlpha());
        this.brightnessBuffer.flip();

        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, this.brightnessBuffer);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }
}
