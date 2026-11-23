package com.hoptimization.mixin.render;

import com.hoptimization.modules.ui.ScoreboardHud;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Minecraft's default sidebar scoreboard rendering when {@link ScoreboardHud} is enabled.
 */
@Mixin(GuiIngame.class)
public abstract class MixinGuiIngameScoreboard {

    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void hoptimization$cancelVanillaScoreboard(
        ScoreObjective objective,
        ScaledResolution scaledRes,
        CallbackInfo ci
    ) {
        if (ScoreboardHud.isActive()) {
            ci.cancel();
        }
    }
}
