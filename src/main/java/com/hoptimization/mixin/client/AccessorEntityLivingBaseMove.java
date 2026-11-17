package com.hoptimization.mixin.client;

import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor exposing {@link EntityLivingBase#jumpTicks} for movement delay control.
 */
@Mixin(EntityLivingBase.class)
public interface AccessorEntityLivingBaseMove {

    /**
     * Reads the remaining jump cooldown ticks on an entity.
     *
     * @return current jump cooldown ticks
     */
    @Accessor("jumpTicks")
    int hoptimization$getJumpTicks();

    /**
     * Sets the remaining jump cooldown ticks on an entity.
     *
     * @param jumpTicks target jump cooldown ticks
     */
    @Accessor("jumpTicks")
    void hoptimization$setJumpTicks(int jumpTicks);
}
