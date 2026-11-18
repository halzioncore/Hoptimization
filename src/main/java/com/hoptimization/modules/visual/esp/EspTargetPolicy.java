package com.hoptimization.modules.visual.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Policy contract supplied by the ESP module to determine which players are eligible for rendering.
 */
public interface EspTargetPolicy {
    /**
     * Returns whether the given player should be rendered by the ESP renderer.
     *
     * @param target candidate player entity
     * @param mc     Minecraft client instance
     * @return {@code true} if the target should be rendered
     */
    boolean isTargetEligible(EntityPlayer target, Minecraft mc);
}
