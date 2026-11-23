package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernVanillaUI;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.vanilla.ModernVanillaRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces {@link GuiSlot}'s stone/dirt container & header/footer overlay textures (used in
 * Multiplayer, Singleplayer World Select, Language, Resource Packs, and Keybinds) with our
 * theme-matched modern background and mouse-reactive wave stripes.
 */
@Mixin(GuiSlot.class)
public abstract class MixinGuiSlot {

    @Shadow
    @Final
    protected Minecraft mc;

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Shadow
    public int top;

    @Shadow
    public int bottom;

    @Inject(method = "drawContainerBackground", at = @At("HEAD"), cancellable = true, remap = false)
    private void hoptimization$modernContainerBg(Tessellator tessellator, CallbackInfo ci) {
        if (!ModernVanillaUI.isBackgroundsEnabled()) {
            return;
        }
        if (this.mc != null && this.mc.currentScreen != null) {
            boolean inWorld = this.mc.theWorld != null;
            ModernVanillaRenderer.renderScreenBackground(this.mc.currentScreen, inWorld);
        } else {
            ThemeData theme = ThemeManager.current();
            RenderUtil.drawVerticalGradient(0.0f, 0.0f, this.width, this.height, theme.surface0(), theme.surface1());
        }
        ci.cancel();
    }

    @Inject(method = "overlayBackground", at = @At("HEAD"), cancellable = true)
    private void hoptimization$modernOverlayBg(int startY, int endY, int startAlpha, int endAlpha, CallbackInfo ci) {
        if (!ModernVanillaUI.isBackgroundsEnabled()) {
            return;
        }
        ThemeData theme = ThemeManager.current();
        RenderUtil.drawRect(
            0.0f,
            startY,
            this.width,
            Math.max(0.0f, endY - startY),
            RenderUtil.withAlpha(theme.surface0(), 0.72f)
        );
        ci.cancel();
    }
}
