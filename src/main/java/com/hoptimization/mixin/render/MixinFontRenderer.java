package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernFont;
import com.hoptimization.ui.container.ModernContainerRenderer;
import com.hoptimization.ui.font.VanillaTtfFontBridge;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Minecraft's primary {@link FontRenderer} when {@link ModernFont} is enabled,
 * delegating rendering and string metrics to {@link VanillaTtfFontBridge} while leaving
 * the enchanting table's {@code standardGalacticFontRenderer} untouched.
 */
@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {

    @Unique
    private boolean hoptimization$shouldUseModernFont() {
        if (!ModernFont.isActive()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return false;
        }
        return (Object) this == mc.fontRendererObj;
    }

    @ModifyVariable(method = "drawString(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int hoptimization$remapContainerForegroundColor(int color) {
        if (ModernContainerRenderer.isRenderingForeground() && (color & 0x00FFFFFF) == 0x00404040) {
            int alpha = (color >>> 24) & 0xFF;
            int accent = ThemeManager.current().accent();
            return (alpha > 0 ? (alpha << 24) : 0xFF000000) | (accent & 0x00FFFFFF);
        }
        return color;
    }

    @Inject(method = "drawString(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), cancellable = true)
    private void hoptimization$drawString(
        String text,
        float x,
        float y,
        int color,
        boolean dropShadow,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (!hoptimization$shouldUseModernFont()) {
            return;
        }
        if (text == null) {
            cir.setReturnValue(Integer.valueOf(0));
            return;
        }
        cir.setReturnValue(Integer.valueOf(VanillaTtfFontBridge.drawString(text, x, y, color, dropShadow)));
    }

    @Inject(method = "getStringWidth", at = @At("HEAD"), cancellable = true)
    private void hoptimization$getStringWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (!hoptimization$shouldUseModernFont()) {
            return;
        }
        cir.setReturnValue(Integer.valueOf(VanillaTtfFontBridge.getStringWidth(text)));
    }

    @Inject(method = "getCharWidth", at = @At("HEAD"), cancellable = true)
    private void hoptimization$getCharWidth(char character, CallbackInfoReturnable<Integer> cir) {
        if (!hoptimization$shouldUseModernFont()) {
            return;
        }
        cir.setReturnValue(Integer.valueOf(VanillaTtfFontBridge.getCharWidth(character)));
    }

    @Inject(method = "trimStringToWidth(Ljava/lang/String;IZ)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void hoptimization$trimStringToWidth(
        String text,
        int width,
        boolean reverse,
        CallbackInfoReturnable<String> cir
    ) {
        if (!hoptimization$shouldUseModernFont()) {
            return;
        }
        cir.setReturnValue(VanillaTtfFontBridge.trimStringToWidth(text, width, reverse));
    }
}
