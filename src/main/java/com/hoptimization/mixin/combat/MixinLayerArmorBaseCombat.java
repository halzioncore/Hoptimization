package com.hoptimization.mixin.combat;

import com.hoptimization.modules.combat.HitColor;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@link LayerArmorBase#shouldCombineTextures()} so worn armor also receives hurt tinting
 * when {@link HitColor} is set to Armor mode.
 */
@Mixin(LayerArmorBase.class)
public class MixinLayerArmorBaseCombat {

    /**
     * Target: {@code LayerArmorBase.shouldCombineTextures()} (HEAD).
     * Purpose: Enable 1.7-style armor damage tinting when HitColor is active in Armor mode.
     * Controlled by: {@link HitColor#isArmorModeActive()}.
     *
     * @param cir return callback controlling texture combine flag
     */
    @Inject(method = "shouldCombineTextures", at = @At("HEAD"), cancellable = true, require = 0)
    private void hoptimization$onShouldCombineTextures(CallbackInfoReturnable<Boolean> cir) {
        if (HitColor.isArmorModeActive()) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
