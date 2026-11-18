package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.KillEffects;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts client-side entity death status packets and death ticks for {@link KillEffects}.
 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBaseVisual extends Entity {

    protected MixinEntityLivingBaseVisual(World worldIn) {
        super(worldIn);
    }

    /**
     * Target: {@code EntityLivingBase.handleStatusUpdate(byte)} RETURN.
     * Purpose: Captures dying client entities on status packet {@code 3} to spawn kill effects.
     * Controlled by: {@link KillEffects#isActive()}.
     *
     * @param id entity status byte id
     * @param ci mixin callback info
     */
    @Inject(method = "handleStatusUpdate(B)V", at = @At("RETURN"), require = 0)
    private void hoptimization$onHandleStatusUpdate(byte id, CallbackInfo ci) {
        if (id == 3 && KillEffects.isActive()) {
            KillEffects.onClientEntityDeathStatus((EntityLivingBase) (Object) this);
        }
    }

    /**
     * Target: {@code EntityLivingBase.onDeathUpdate()} HEAD.
     * Purpose: Handles client-side death tick fallback and cancels vanilla corpse countdown when captured.
     * Controlled by: {@link KillEffects#isActive()} and {@link KillEffects#onClientEntityDeathTick(EntityLivingBase)}.
     *
     * @param ci mixin callback info
     */
    @Inject(method = "onDeathUpdate()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void hoptimization$onDeathUpdate(CallbackInfo ci) {
        if (KillEffects.isActive()
            && this.worldObj != null
            && this.worldObj.isRemote
            && KillEffects.onClientEntityDeathTick((EntityLivingBase) (Object) this)) {
            ci.cancel();
        }
    }
}
