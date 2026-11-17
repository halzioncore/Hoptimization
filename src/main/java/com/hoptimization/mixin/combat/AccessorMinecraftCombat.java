package com.hoptimization.mixin.combat;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Mixin accessor/invoker exposing click cooldown and click dispatch methods for combat modules.
 */
@Mixin(Minecraft.class)
public interface AccessorMinecraftCombat {

    /**
     * Returns vanilla's remaining left-click miss cooldown in ticks.
     *
     * @return current leftClickCounter value
     */
    @Accessor("leftClickCounter")
    int hoptimization$getLeftClickCounter();

    /**
     * Updates vanilla's left-click miss cooldown in ticks.
     *
     * @param leftClickCounter new cooldown value
     */
    @Accessor("leftClickCounter")
    void hoptimization$setLeftClickCounter(int leftClickCounter);

    /**
     * Invokes vanilla left-click handling on the client.
     */
    @Invoker("clickMouse")
    void hoptimization$clickMouse();

    /**
     * Invokes vanilla right-click handling on the client.
     */
    @Invoker("rightClickMouse")
    void hoptimization$rightClickMouse();
}
