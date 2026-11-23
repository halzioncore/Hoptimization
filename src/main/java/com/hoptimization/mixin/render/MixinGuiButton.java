package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernVanillaUI;
import com.hoptimization.ui.vanilla.ModernVanillaRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla {@link GuiButton#drawButton} textured stone widgets with clean, theme-matched rounded buttons.
 */
@Mixin(GuiButton.class)
public abstract class MixinGuiButton {

    @Shadow
    public int xPosition;

    @Shadow
    public int yPosition;

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Shadow
    public boolean visible;

    @Shadow
    protected boolean hovered;

    @Shadow
    protected abstract void mouseDragged(Minecraft mc, int mouseX, int mouseY);

    @Inject(method = "drawButton", at = @At("HEAD"), cancellable = true)
    private void hoptimization$drawModernButton(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
        if (!ModernVanillaUI.isButtonsEnabled()) {
            return;
        }
        if (this.visible) {
            this.hovered = mouseX >= this.xPosition
                && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width
                && mouseY < this.yPosition + this.height;
            this.mouseDragged(mc, mouseX, mouseY);
            ModernVanillaRenderer.renderButton((GuiButton) (Object) this, mc, mouseX, mouseY, this.hovered);
        }
        ci.cancel();
    }
}
