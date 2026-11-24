package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernVanillaUI;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.vanilla.ModernVanillaRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla {@link GuiButton#drawButton} textured stone widgets with clean, theme-matched
 * rounded/frosted buttons with 120ms real-time hover interpolation.
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
    public boolean enabled;

    @Shadow
    public boolean visible;

    @Shadow
    protected boolean hovered;

    @Unique
    private float hoptimization$hoverProgress = 0.0f;

    @Unique
    private long hoptimization$lastHoverNanos = 0L;

    @Shadow
    protected abstract void mouseDragged(Minecraft mc, int mouseX, int mouseY);

    /**
     * 目标方法：GuiButton#drawButton(Lnet/minecraft/client/Minecraft;II)V
     * 目的：ModernVanillaUI 按钮开启时推进帧率无关 120ms 悬停进度并渲染现代圆角/毛玻璃按钮。
     */
    @Inject(method = "drawButton", at = @At("HEAD"), cancellable = true)
    private void hoptimization$drawModernButton(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
        if (!ModernVanillaUI.isButtonsEnabled()) {
            this.hoptimization$lastHoverNanos = 0L;
            return;
        }
        if (this.visible) {
            this.hovered = mouseX >= this.xPosition
                && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width
                && mouseY < this.yPosition + this.height;
            this.mouseDragged(mc, mouseX, mouseY);

            float target = (this.hovered && this.enabled) ? 1.0f : 0.0f;
            long now = System.nanoTime();
            if (Animation.isReduceMotion() || this.hoptimization$lastHoverNanos == 0L) {
                this.hoptimization$hoverProgress = target;
            } else {
                float dt = Math.min(0.05f, Math.max(0.0005f, (now - this.hoptimization$lastHoverNanos) / 1_000_000_000.0f));
                float factor = 1.0f - (float) Math.exp(-dt / 0.045f);
                this.hoptimization$hoverProgress += (target - this.hoptimization$hoverProgress) * factor;
                if (Math.abs(target - this.hoptimization$hoverProgress) < 0.003f) {
                    this.hoptimization$hoverProgress = target;
                }
            }
            this.hoptimization$lastHoverNanos = now;

            ModernVanillaRenderer.renderButton(
                (GuiButton) (Object) this,
                mc,
                mouseX,
                mouseY,
                this.hovered,
                this.hoptimization$hoverProgress
            );
        }
        ci.cancel();
    }
}
