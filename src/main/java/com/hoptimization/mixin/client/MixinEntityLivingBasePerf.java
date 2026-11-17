package com.hoptimization.mixin.client;

import com.hoptimization.modules.performance.MouseDelayFix;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates the 1-tick head-look delay in MC 1.8.9 when {@link MouseDelayFix} is enabled.
 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBasePerf extends Entity {

    protected MixinEntityLivingBasePerf(World worldIn) {
        super(worldIn);
    }

    /**
     * Target: {@code EntityLivingBase.getLook(float)} HEAD.
     * Purpose: Delegates local player look vector calculation to {@link Entity#getLook(float)}
     * so raytracing uses real-time {@code rotationYaw} instead of 1-tick-delayed {@code rotationYawHead}.
     * Controlled by: {@link MouseDelayFix#isFixActive()}.
     */
    @Inject(method = "getLook", at = @At("HEAD"), cancellable = true, require = 0)
    private void hoptimization$onGetLook(float partialTicks, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof EntityPlayerSP && MouseDelayFix.isFixActive()) {
            cir.setReturnValue(super.getLook(partialTicks));
        }
    }
}
