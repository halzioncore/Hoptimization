package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernVanillaUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Updates {@link GuiOptionSlider} drag value without drawing vanilla's textured slider knob
 * when {@link ModernVanillaUI} is enabled.
 */
@Mixin(GuiOptionSlider.class)
public abstract class MixinGuiOptionSlider extends GuiButton {

    @Shadow
    private float sliderValue;

    @Shadow
    public boolean dragging;

    @Shadow
    private GameSettings.Options options;

    public MixinGuiOptionSlider(int buttonId, int x, int y, String buttonText) {
        super(buttonId, x, y, buttonText);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void hoptimization$modernSliderDrag(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
        if (!ModernVanillaUI.isButtonsEnabled()) {
            return;
        }
        if (this.visible && this.dragging && this.options != null) {
            this.sliderValue = (float) (mouseX - (this.xPosition + 4)) / (float) (this.width - 8);
            this.sliderValue = MathHelper.clamp_float(this.sliderValue, 0.0f, 1.0f);
            float denorm = this.options.denormalizeValue(this.sliderValue);
            mc.gameSettings.setOptionFloatValue(this.options, denorm);
            this.sliderValue = this.options.normalizeValue(denorm);
            this.displayString = mc.gameSettings.getKeyBinding(this.options);
        }
        ci.cancel();
    }
}
