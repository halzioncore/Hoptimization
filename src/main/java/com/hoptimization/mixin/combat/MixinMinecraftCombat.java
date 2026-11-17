package com.hoptimization.mixin.combat;

import com.hoptimization.modules.combat.HitDelayFix;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link Minecraft#clickMouse()} to eliminate 1.8.9's 10-tick missed-hit delay when
 * {@link HitDelayFix} is enabled and available.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftCombat {

    @Shadow
    private int leftClickCounter;

    /**
     * Target: {@code Minecraft.clickMouse()} (HEAD).
     * Purpose: Clear leftover missed-hit cooldown without bypassing the 10000-tick GUI/focus guard.
     * Controlled by: {@link HitDelayFix#isActive()}.
     *
     * @param ci mixin callback info
     */
    @Inject(method = "clickMouse", at = @At("HEAD"))
    private void hoptimization$onClickMouseHead(CallbackInfo ci) {
        if (HitDelayFix.isActive() && this.leftClickCounter <= 10) {
            this.leftClickCounter = 0;
        }
    }

    /**
     * Target: {@code Minecraft.clickMouse()} (RETURN).
     * Purpose: Clear the 10-tick missed-hit cooldown applied during a miss.
     * Controlled by: {@link HitDelayFix#isActive()}.
     *
     * @param ci mixin callback info
     */
    @Inject(method = "clickMouse", at = @At("RETURN"))
    private void hoptimization$onClickMouseReturn(CallbackInfo ci) {
        if (HitDelayFix.isActive() && this.leftClickCounter == 10) {
            this.leftClickCounter = 0;
        }
    }
}
