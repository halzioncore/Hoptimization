package com.hoptimization.ui.vanilla;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.modules.visual.ModernVanillaUI;
import com.hoptimization.ui.render.BackdropBlurRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.ThemeMode;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Clean, restrained, theme-matched renderer for vanilla Minecraft menus, backgrounds, buttons, and sliders.
 */
public final class ModernVanillaRenderer {
    private static final int WAVE_SEGMENTS = 64;
    private static float smoothedMouseNormX = 0.5f;
    private static float smoothedMouseNormY = 0.5f;

    private ModernVanillaRenderer() {
    }

    /**
     * Renders a sleek, theme-matched main menu screen replacing the rotating skybox panorama and pixel logo.
     */
    public static void renderMainMenu(
        GuiMainMenu menu,
        List<GuiButton> buttonList,
        int mouseX,
        int mouseY,
        float partialTicks
    ) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        int w = menu.width;
        int h = menu.height;
        ThemeData theme = ThemeManager.current();
        boolean isLight = theme.getMode() == ThemeMode.LIGHT;

        // Clean atmospheric gradient backdrop using surface0 & surface1.
        RenderUtil.drawVerticalGradient(0.0f, 0.0f, w, h, theme.surface0(), theme.surface1());

        if (ModernVanillaUI.isWaveStripesEnabled()) {
            renderInteractiveWaves(w, h, mouseX, mouseY, theme, isLight);
        }

        // Gentle breathing ambient accent glow behind the menu card.
        double timeSec = (System.nanoTime() / 1_000_000_000.0D);
        float driftX = (float) Math.sin(timeSec * 0.45D) * 28.0f;
        float driftY = (float) Math.cos(timeSec * 0.35D) * 16.0f;
        int orbColor = RenderUtil.withAlpha(theme.accent(), isLight ? 0.14f : 0.11f);
        RenderUtil.drawShadow(w * 0.5f - 120.0f + driftX, h * 0.25f + driftY, 240.0f, 120.0f, 60.0f, 90.0f, orbColor);

        // Subtle center card framing the brand header and menu buttons.
        float cardW = Math.min(248.0f, w - 32.0f);
        float cardH = Math.min(244.0f, h - 48.0f);
        float cardX = (w - cardW) * 0.5f;
        float cardY = Math.max(24.0f, h * 0.22f - 18.0f);
        if (cardY + cardH > h - 28.0f) {
            cardY = Math.max(12.0f, (h - cardH) * 0.5f);
        }

        RenderUtil.drawRoundedRectWithShadow(
            cardX, cardY, cardW, cardH,
            theme.radiusWindow(),
            RenderUtil.withAlpha(theme.surface1(), isLight ? 0.85f : 0.78f),
            theme.border(),
            UiTokens.BORDER_WIDTH,
            0.0f,
            12.0f,
            36.0f,
            theme.windowShadowColor()
        );

        FontRenderer fr = mc.fontRendererObj;
        if (fr != null) {
            // Crisp modern brand title & mod subtitle.
            String title = "MINECRAFT";
            float titleScale = 2.1f;
            float titleW = fr.getStringWidth(title) * titleScale;
            GlStateManager.pushMatrix();
            GlStateManager.translate(w * 0.5f - titleW * 0.5f, cardY + 18.0f, 0.0f);
            GlStateManager.scale(titleScale, titleScale, 1.0f);
            fr.drawStringWithShadow(title, 0.0f, 0.0f, theme.textPrimary());
            GlStateManager.popMatrix();

            String sub = HoptimizationMod.NAME + " " + HoptimizationMod.VERSION + "  \u00B7  1.8.9";
            int subW = fr.getStringWidth(sub);
            fr.drawStringWithShadow(sub, w * 0.5f - subW * 0.5f, cardY + 41.0f, theme.accent());

            // Subtle accent divider line.
            float divW = 72.0f;
            RenderUtil.drawRoundedRect(
                w * 0.5f - divW * 0.5f,
                cardY + 55.0f,
                divW,
                1.5f,
                0.75f,
                RenderUtil.withAlpha(theme.accent(), 0.65f)
            );
        }

        // Render all GuiButtons (styled via MixinGuiButton).
        if (buttonList != null) {
            for (int i = 0; i < buttonList.size(); i++) {
                GuiButton btn = buttonList.get(i);
                if (btn != null) {
                    btn.drawButton(mc, mouseX, mouseY);
                }
            }
        }

        // Clean muted footer brandings.
        if (fr != null) {
            List<String> brandings = FMLCommonHandler.instance().getBrandings(true);
            int yPos = h - 12;
            for (int i = 0; i < brandings.size(); i++) {
                String brd = brandings.get(i);
                if (brd == null || brd.isEmpty()) {
                    continue;
                }
                fr.drawStringWithShadow(brd, 8, yPos, theme.textTertiary());
                yPos -= (fr.FONT_HEIGHT + 2);
            }
            String rightFooter = "Mojang AB";
            int rw = fr.getStringWidth(rightFooter);
            fr.drawStringWithShadow(rightFooter, w - rw - 8, h - 12, theme.textTertiary());
            ForgeHooksClient.renderMainMenu(menu, fr, w, h);
        }
    }

    /**
     * Renders a modern themed background for non-world screens (replacing vanilla dirt texture)
     * and in-world screens (supporting frosted glass blur + subtle backdrop).
     */
    public static void renderScreenBackground(GuiScreen screen, boolean inWorld) {
        ThemeData theme = ThemeManager.current();
        float w = screen.width;
        float h = screen.height;
        if (inWorld) {
            if (ThemeManager.isFrostedGlass()) {
                BackdropBlurRenderer.captureAndBlur(ThemeManager.getBlurStrength());
                RenderUtil.drawRect(0.0f, 0.0f, w, h, RenderUtil.withAlpha(theme.backdrop(), 0.45f));
            } else {
                RenderUtil.drawRect(0.0f, 0.0f, w, h, RenderUtil.withAlpha(theme.backdrop(), 0.78f));
            }
        } else {
            RenderUtil.drawVerticalGradient(0.0f, 0.0f, w, h, theme.surface0(), theme.surface1());
            if (ModernVanillaUI.isWaveStripesEnabled()) {
                int guiMouseX = Math.round(smoothedMouseNormX * w);
                int guiMouseY = Math.round(smoothedMouseNormY * h);
                if (Mouse.isCreated() && Minecraft.getMinecraft() != null) {
                    ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                    guiMouseX = Mouse.getX() * sr.getScaledWidth() / Math.max(1, Minecraft.getMinecraft().displayWidth);
                    guiMouseY = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / Math.max(1, Minecraft.getMinecraft().displayHeight) - 1;
                }
                renderInteractiveWaves(w, h, guiMouseX, guiMouseY, theme, theme.getMode() == ThemeMode.LIGHT);
            }
            int subtleGlow = RenderUtil.withAlpha(theme.accent(), theme.getMode() == ThemeMode.LIGHT ? 0.08f : 0.06f);
            RenderUtil.drawShadow(w * 0.5f - 140.0f, -40.0f, 280.0f, 120.0f, 60.0f, 100.0f, subtleGlow);
        }
    }

    /**
     * Renders restrained, mouse-reactive flowing wave ribbons across the background.
     */
    public static void renderInteractiveWaves(
        float width,
        float height,
        int mouseX,
        int mouseY,
        ThemeData theme,
        boolean isLight
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        float targetX = Math.max(0.0f, Math.min(1.0f, mouseX / width));
        float targetY = Math.max(0.0f, Math.min(1.0f, mouseY / height));
        smoothedMouseNormX += (targetX - smoothedMouseNormX) * 0.08f;
        smoothedMouseNormY += (targetY - smoothedMouseNormY) * 0.08f;

        double timeSec = System.nanoTime() / 1_000_000_000.0D;
        float mx = smoothedMouseNormX;
        float my = smoothedMouseNormY;

        RenderUtil.pushUntexturedBlendState(true);
        try {
            // Draw 3 layered translucent ribbons + 1 crisp hairline wave crest.
            drawWaveRibbon(width, height, 0.62f, 24.0f, 1.35f, (float) (timeSec * 0.65D + mx * 2.6f), mx, my,
                RenderUtil.withAlpha(theme.accent(), isLight ? 0.07f : 0.055f),
                RenderUtil.withAlpha(theme.accent(), 0.0f));
            drawWaveRibbon(width, height, 0.71f, 19.0f, 1.85f, (float) (timeSec * 0.85D - mx * 3.1f + 1.4f), mx, my,
                RenderUtil.withAlpha(theme.accent(), isLight ? 0.10f : 0.08f),
                RenderUtil.withAlpha(theme.accent(), 0.0f));
            drawWaveRibbon(width, height, 0.80f, 15.0f, 2.30f, (float) (timeSec * 1.05D + mx * 3.8f + 2.8f), mx, my,
                RenderUtil.withAlpha(theme.surface2(), isLight ? 0.45f : 0.36f),
                RenderUtil.withAlpha(theme.surface0(), 0.0f));

            drawWaveCrestLine(width, height, 0.71f, 19.0f, 1.85f, (float) (timeSec * 0.85D - mx * 3.1f + 1.4f), mx, my,
                RenderUtil.withAlpha(theme.accent(), isLight ? 0.28f : 0.24f));
        } finally {
            RenderUtil.popState();
        }
    }

    private static float evalWaveY(
        float normX,
        float baseFracY,
        float baseAmp,
        float freq,
        float phase,
        float mouseNormX,
        float mouseNormY,
        float height
    ) {
        float distToMouse = Math.abs(normX - mouseNormX);
        float mouseRipple = (float) Math.exp(-distToMouse * distToMouse * 14.0f);
        float dynamicAmp = baseAmp + mouseRipple * 18.0f * (0.5f + mouseNormY);
        float shiftY = (mouseNormY - 0.5f) * 26.0f * (0.35f + mouseRipple);
        float wave = (float) Math.sin(normX * Math.PI * 2.0D * freq + phase) * dynamicAmp
            + (float) Math.cos(normX * Math.PI * 3.2D - phase * 0.7f) * (baseAmp * 0.32f);
        return height * baseFracY + shiftY + wave;
    }

    private static void drawWaveRibbon(
        float width,
        float height,
        float baseFracY,
        float baseAmp,
        float freq,
        float phase,
        float mx,
        float my,
        int topColor,
        int bottomColor
    ) {
        int ta = (topColor >>> 24) & 0xFF;
        int tr = (topColor >>> 16) & 0xFF;
        int tg = (topColor >>> 8) & 0xFF;
        int tb = topColor & 0xFF;

        int ba = (bottomColor >>> 24) & 0xFF;
        int br = (bottomColor >>> 16) & 0xFF;
        int bg = (bottomColor >>> 8) & 0xFF;
        int bb = bottomColor & 0xFF;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= WAVE_SEGMENTS; i++) {
            float nx = i / (float) WAVE_SEGMENTS;
            float px = nx * width;
            float py = evalWaveY(nx, baseFracY, baseAmp, freq, phase, mx, my, height);
            wr.pos(px, py, 0.0D).color(tr, tg, tb, ta).endVertex();
            wr.pos(px, height, 0.0D).color(br, bg, bb, ba).endVertex();
        }
        tessellator.draw();
    }

    private static void drawWaveCrestLine(
        float width,
        float height,
        float baseFracY,
        float baseAmp,
        float freq,
        float phase,
        float mx,
        float my,
        int lineColor
    ) {
        int a = (lineColor >>> 24) & 0xFF;
        int r = (lineColor >>> 16) & 0xFF;
        int g = (lineColor >>> 8) & 0xFF;
        int b = lineColor & 0xFF;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= WAVE_SEGMENTS; i++) {
            float nx = i / (float) WAVE_SEGMENTS;
            float px = nx * width;
            float py = evalWaveY(nx, baseFracY, baseAmp, freq, phase, mx, my, height);
            wr.pos(px, py - 0.6f, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(px, py + 0.6f, 0.0D).color(r, g, b, a).endVertex();
        }
        tessellator.draw();
    }

    /**
     * Renders a clean, theme-matched rounded button (or slider) replacing vanilla's textured {@code widgets.png}.
     */
    public static void renderButton(GuiButton btn, Minecraft mc, int mouseX, int mouseY, boolean hovered) {
        if (!btn.visible) {
            return;
        }
        ThemeData theme = ThemeManager.current();
        boolean isLight = theme.getMode() == ThemeMode.LIGHT;
        float x = btn.xPosition;
        float y = btn.yPosition;
        float w = btn.width;
        float h = btn.height;
        float radius = theme.radiusControl();

        if (btn instanceof GuiOptionSlider) {
            renderSliderButton((GuiOptionSlider) btn, mc, x, y, w, h, radius, hovered, theme, isLight);
            return;
        }

        int bg;
        int border;
        int textColor;
        if (!btn.enabled) {
            bg = RenderUtil.withAlpha(theme.surface1(), 0.55f);
            border = RenderUtil.withAlpha(theme.border(), 0.55f);
            textColor = theme.textTertiary();
        } else if (hovered) {
            bg = RenderUtil.withAlpha(theme.surface3(), isLight ? 0.92f : 0.88f);
            border = theme.accent();
            textColor = theme.accent();
        } else {
            bg = RenderUtil.withAlpha(theme.surface2(), isLight ? 0.85f : 0.78f);
            border = theme.border();
            textColor = theme.textPrimary();
        }

        RenderUtil.drawRoundedRect(x, y, w, h, radius, bg, border, 1.0f);

        if (hovered && btn.enabled) {
            float barH = Math.max(8.0f, h - 8.0f);
            RenderUtil.drawRoundedRect(
                x + 2.0f,
                y + (h - barH) * 0.5f,
                2.0f,
                barH,
                1.0f,
                theme.accent()
            );
        }

        FontRenderer fr = mc != null ? mc.fontRendererObj : null;
        if (fr != null && btn.displayString != null && !btn.displayString.isEmpty()) {
            if (btn.packedFGColour != 0) {
                textColor = btn.packedFGColour;
            }
            int textW = fr.getStringWidth(btn.displayString);
            float textX = x + (w - textW) * 0.5f;
            float textY = y + (h - fr.FONT_HEIGHT) * 0.5f;
            fr.drawStringWithShadow(btn.displayString, textX, textY, textColor);
        }
    }

    private static void renderSliderButton(
        GuiOptionSlider slider,
        Minecraft mc,
        float x,
        float y,
        float w,
        float h,
        float radius,
        boolean hovered,
        ThemeData theme,
        boolean isLight
    ) {
        int bg = RenderUtil.withAlpha(hovered ? theme.surface3() : theme.surface2(), isLight ? 0.88f : 0.80f);
        int border = hovered ? theme.accent() : theme.border();
        RenderUtil.drawRoundedRect(x, y, w, h, radius, bg, border, 1.0f);

        float val = 0.0f;
        if (slider instanceof com.hoptimization.mixin.render.AccessorGuiOptionSlider) {
            val = ((com.hoptimization.mixin.render.AccessorGuiOptionSlider) slider).hoptimization$getSliderValue();
        }
        val = val < 0.0f ? 0.0f : (val > 1.0f ? 1.0f : val);

        float pad = 4.0f;
        float fillW = Math.max(0.0f, (w - pad * 2.0f) * val);
        if (fillW > 1.0f) {
            RenderUtil.drawRoundedRect(
                x + pad,
                y + h - 4.5f,
                fillW,
                2.5f,
                1.25f,
                theme.accent()
            );
        }

        FontRenderer fr = mc != null ? mc.fontRendererObj : null;
        if (fr != null && slider.displayString != null && !slider.displayString.isEmpty()) {
            int textColor = hovered ? theme.accent() : theme.textPrimary();
            int textW = fr.getStringWidth(slider.displayString);
            float textX = x + (w - textW) * 0.5f;
            float textY = y + (h - fr.FONT_HEIGHT) * 0.5f - 1.0f;
            fr.drawStringWithShadow(slider.displayString, textX, textY, textColor);
        }
    }
}
