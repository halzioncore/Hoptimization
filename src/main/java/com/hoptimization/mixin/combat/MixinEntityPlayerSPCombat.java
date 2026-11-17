package com.hoptimization.mixin.combat;

import com.hoptimization.modules.combat.WTap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link EntityPlayerSP#setSprinting(boolean)} so {@link WTap} can keep sprint disabled
 * during the configured hold window even while the sprint key or ToggleSprint is active.
 */
@Mixin(EntityPlayerSP.class)
public class MixinEntityPlayerSPCombat {

    /**
     * Target: {@code EntityPlayerSP.setSprinting(boolean)} (HEAD).
     * Purpose: Prevent vanilla sprint key re-activation during an active W-Tap sprint reset window.
     * Controlled by: {@link WTap#shouldSuppressSprint()}.
     *
     * @param sprinting requested sprint state
     * @param ci mixin callback info
     */
    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void hoptimization$onSetSprinting(boolean sprinting, CallbackInfo ci) {
        if (!sprinting || !WTap.shouldSuppressSprint()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && (Object) this == mc.thePlayer) {
            ci.cancel();
        }
    }
}
