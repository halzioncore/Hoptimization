package com.hoptimization.modules.visual.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Policy contract supplied by the ESP module to determine target eligibility and ARGB colors.
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

    /**
     * Resolves the ARGB color for a target in either the visible or wall-occluded pass.
     *
     * @param target   target player entity
     * @param occluded {@code true} when resolving the wall-occluded pass color
     * @return ARGB color integer
     */
    int resolveTargetColorArgb(EntityPlayer target, boolean occluded);
}
