package com.hoptimization.mixin.render;

import com.hoptimization.modules.visual.ModernVanillaUI;
import com.hoptimization.ui.vanilla.ModernVanillaRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Minecraft's main menu rendering with {@link ModernVanillaRenderer#renderMainMenu}
 * when {@link ModernVanillaUI} is enabled.
 */
@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu extends GuiScreen {

    @Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
    private void hoptimization$drawModernMainMenu(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!ModernVanillaUI.isMainMenuEnabled()) {
            return;
        }
        ModernVanillaRenderer.renderMainMenu((GuiMainMenu) (Object) this, this.buttonList, mouseX, mouseY, partialTicks);
        ci.cancel();
    }
}
