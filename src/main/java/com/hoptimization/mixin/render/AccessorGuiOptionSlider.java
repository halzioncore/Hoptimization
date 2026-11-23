package com.hoptimization.mixin.render;

import net.minecraft.client.gui.GuiOptionSlider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link GuiOptionSlider#sliderValue} for modern slider rendering.
 */
@Mixin(GuiOptionSlider.class)
public interface AccessorGuiOptionSlider {

    @Accessor("sliderValue")
    float hoptimization$getSliderValue();
}
