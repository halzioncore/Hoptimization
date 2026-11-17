package com.hoptimization.mixin;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(method = "startGame", at = @At("RETURN"))
    private void hoptimization$onStartGame(CallbackInfo ci) {
        HoptimizationMod.LOGGER.info("Hoptimization mixins active.");
    }
}
