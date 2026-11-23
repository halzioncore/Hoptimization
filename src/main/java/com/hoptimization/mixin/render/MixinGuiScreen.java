package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernVanillaUI;
import com.hoptimization.ui.vanilla.ModernVanillaRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's dirt options background and harsh pause-menu gradient with {@link ModernVanillaRenderer}.
 */
@Mixin(GuiScreen.class)
public abstract class MixinGuiScreen {

    @Shadow
    public Minecraft mc;

    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true)
    private void hoptimization$drawModernBackground(int tint, CallbackInfo ci) {
        if (!ModernVanillaUI.isBackgroundsEnabled()) {
            return;
        }
        ModernVanillaRenderer.renderScreenBackground((GuiScreen) (Object) this, false);
        ci.cancel();
    }

    @Inject(method = "drawWorldBackground", at = @At("HEAD"), cancellable = true)
    private void hoptimization$drawModernWorldBackground(int tint, CallbackInfo ci) {
        if (!ModernVanillaUI.isBackgroundsEnabled()) {
            return;
        }
        boolean inWorld = this.mc != null && this.mc.theWorld != null;
        ModernVanillaRenderer.renderScreenBackground((GuiScreen) (Object) this, inWorld);
        ci.cancel();
    }
}
