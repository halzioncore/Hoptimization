package com.hoptimization.ui.vanilla;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.mixin.render.AccessorGuiOptionSlider;
import com.hoptimization.modules.visual.ModernVanillaUI;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.FontStyle;
import com.hoptimization.ui.font.FontWeight;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.font.VanillaTtfFontBridge;
import com.hoptimization.ui.render.AmbientBackgroundRenderer;
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
 * Uses crisp high-DPI TTF typography, 120ms hover interpolation, and unified dual-material tokens.
 */
public final class ModernVanillaRenderer {
    private static final int WAVE_SEGMENTS = 64;
    private static final FontStyle HERO_TITLE_STYLE = FontStyle.of(20.0f, FontWeight.SEMIBOLD);
    private static final FontStyle HERO_SUB_STYLE = FontStyle.of(8.5f, FontWeight.MEDIUM);
    private static final float BUTTON_FONT_SIZE = 8.5f;
    private static final float SLIDER_FONT_SIZE = 8.0f;
    private static final float FOOTER_FONT_SIZE = 8.0f;

    private static float smoothedMouseNormX = 0.5f;
    private static float smoothedMouseNormY = 0.5f;
    private static long lastWaveNanos = 0L;

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
        boolean reduceMotion = Animation.isReduceMotion();

        if (ModernVanillaUI.isWaveStripesEnabled()) {
            AmbientBackgroundRenderer.renderBackground(w, h, mouseX, mouseY, theme);
        } else {
            RenderUtil.drawVerticalGradient(0.0f, 0.0f, w, h, theme.surface0(), theme.surface1());
        }

        double timeSec = reduceMotion ? 0.0D : (System.nanoTime() / 1_000_000_000.0D);
        float driftX = reduceMotion ? 0.0f : ((float) Math.sin(timeSec * 0.40D) * 20.0f);
        float driftY = reduceMotion ? 0.0f : ((float) Math.cos(timeSec * 0.30D) * 12.0f);
        int orbColor = RenderUtil.withAlpha(theme.accent(), isLight ? 0.09f : 0.07f);
        RenderUtil.drawShadow(
            w * 0.5f - 120.0f + driftX,
            h * 0.24f + driftY,
            240.0f,
            116.0f,
            60.0f,
            88.0f,
            orbColor
        );

        float cardW = Math.min(248.0f, w - UiTokens.SPACE_32);
        float cardH = Math.min(244.0f, h - 48.0f);
        float cardX = Math.round((w - cardW) * 0.5f);
        float cardY = Math.max(UiTokens.SPACE_24, h * 0.22f - UiTokens.SPACE_16);
        if (cardY + cardH > h - 28.0f) {
            cardY = Math.max(UiTokens.SPACE_12, (h - cardH) * 0.5f);
        }
        cardY = Math.round(cardY);

        float winAlpha = theme.style().getWindowOpacity();
        int cardFill = RenderUtil.withAlpha(theme.surface1(), (isLight ? 0.88f : 0.82f) * winAlpha);
        int cardBorder = ThemeManager.isFrostedGlass() ? theme.frostedBorder() : theme.border();
        RenderUtil.drawRoundedRectWithShadow(
            cardX, cardY, cardW, cardH,
            theme.radiusWindow(),
            cardFill,
            cardBorder,
            UiTokens.BORDER_WIDTH,
            0.0f,
            UiTokens.SHADOW_WINDOW_OFFSET_Y,
            UiTokens.SHADOW_WINDOW_BLUR,
            theme.windowShadowColor()
        );

        ScaledResolution sr = new ScaledResolution(mc);
        float srScale = Math.max(1.0f, (float) sr.getScaleFactor());
        TtfFontRenderer heroFont = FontManager.getInstance().getRenderer(HERO_TITLE_STYLE, srScale);
        TtfFontRenderer subFont = FontManager.getInstance().getRenderer(HERO_SUB_STYLE, srScale);

        String title = "MINECRAFT";
        heroFont.drawStringCentered(title, w * 0.5f, cardY + UiTokens.SPACE_16, theme.textPrimary());

        String sub = HoptimizationMod.NAME + " " + HoptimizationMod.VERSION + "  \u00B7  1.8.9";
        float subW = subFont.getWidth(sub);
        float dotSize = 4.0f;
        float badgeTotalW = dotSize + UiTokens.SPACE_6 + subW;
        float badgeStartX = w * 0.5f - badgeTotalW * 0.5f;
        float subY = cardY + UiTokens.SPACE_16 + heroFont.getHeight() + UiTokens.SPACE_4;
        RenderUtil.drawRoundedRect(
            badgeStartX,
            subY + (subFont.getHeight() - dotSize) * 0.5f,
            dotSize,
            dotSize,
            dotSize * 0.5f,
            theme.accent()
        );
        subFont.drawString(sub, badgeStartX + dotSize + UiTokens.SPACE_6, subY, theme.textSecondary());

        float divW = cardW - 48.0f;
        RenderUtil.drawRect(
            w * 0.5f - divW * 0.5f,
            subY + subFont.getHeight() + UiTokens.SPACE_8,
            divW,
            UiTokens.BORDER_WIDTH,
            theme.border()
        );

        if (buttonList != null) {
            for (int i = 0; i < buttonList.size(); i++) {
                GuiButton btn = buttonList.get(i);
                if (btn != null) {
                    btn.drawButton(mc, mouseX, mouseY);
                }
            }
        }

        List<String> brandings = FMLCommonHandler.instance().getBrandings(true);
        float yPos = h - UiTokens.SPACE_12;
        for (int i = 0; i < brandings.size(); i++) {
            String brd = brandings.get(i);
            if (brd == null || brd.isEmpty()) {
                continue;
            }
            VanillaTtfFontBridge.drawFormattedUiString(
                brd, UiTokens.SPACE_8, yPos, theme.textTertiary(), false, FOOTER_FONT_SIZE, srScale
            );
            yPos -= 10.0f;
        }
        String rightFooter = "Mojang AB";
        float rw = VanillaTtfFontBridge.measureFormattedUiWidth(rightFooter, FOOTER_FONT_SIZE, srScale);
        VanillaTtfFontBridge.drawFormattedUiString(
            rightFooter, w - rw - UiTokens.SPACE_8, h - UiTokens.SPACE_12,
            theme.textTertiary(), false, FOOTER_FONT_SIZE, srScale
        );

        FontRenderer fr = mc.fontRendererObj;
        if (fr != null) {
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
                RenderUtil.drawRect(0.0f, 0.0f, w, h, theme.backdrop());
            }
        } else {
            int guiMouseX = Math.round(w * 0.5f);
            int guiMouseY = Math.round(h * 0.5f);
            if (Mouse.isCreated() && Minecraft.getMinecraft() != null) {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                int dispW = Math.max(1, Minecraft.getMinecraft().displayWidth);
                int dispH = Math.max(1, Minecraft.getMinecraft().displayHeight);
                guiMouseX = Mouse.getX() * sr.getScaledWidth() / dispW;
                guiMouseY = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / dispH - 1;
            }
            if (ModernVanillaUI.isWaveStripesEnabled()) {
                AmbientBackgroundRenderer.renderBackground(w, h, guiMouseX, guiMouseY, theme);
            } else {
                RenderUtil.drawVerticalGradient(0.0f, 0.0f, w, h, theme.surface0(), theme.surface1());
            }
            int subtleGlow = RenderUtil.withAlpha(
                theme.accent(),
                theme.getMode() == ThemeMode.LIGHT ? 0.07f : 0.05f
            );
            RenderUtil.drawShadow(w * 0.5f - 140.0f, -40.0f, 280.0f, 120.0f, 60.0f, 100.0f, subtleGlow);
        }
    }

    /**
     * Renders restrained, mouse-reactive flowing wave ribbons across the background (frame-rate independent).
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
        long now = System.nanoTime();
        float dt = lastWaveNanos == 0L ? 0.016f : Math.min(0.05f, Math.max(0.0005f, (now - lastWaveNanos) / 1_000_000_000.0f));
        lastWaveNanos = now;

        boolean reduceMotion = Animation.isReduceMotion();
        if (reduceMotion) {
            smoothedMouseNormX = 0.5f;
            smoothedMouseNormY = 0.5f;
        } else {
            float targetX = Math.max(0.0f, Math.min(1.0f, mouseX / width));
            float targetY = Math.max(0.0f, Math.min(1.0f, mouseY / height));
            float factor = 1.0f - (float) Math.exp(-5.0f * dt);
            smoothedMouseNormX += (targetX - smoothedMouseNormX) * factor;
            smoothedMouseNormY += (targetY - smoothedMouseNormY) * factor;
        }

        double timeSec = reduceMotion ? 0.0D : (now / 1_000_000_000.0D);
        float mx = smoothedMouseNormX;
        float my = smoothedMouseNormY;

        RenderUtil.pushUntexturedBlendState(true);
        try {
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
                RenderUtil.withAlpha(theme.accent(), isLight ? 0.26f : 0.22f));
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
     * Backwards-compatible entry point delegating with binary hover state.
     */
    public static void renderButton(GuiButton btn, Minecraft mc, int mouseX, int mouseY, boolean hovered) {
        renderButton(btn, mc, mouseX, mouseY, hovered, (hovered && btn.enabled) ? 1.0f : 0.0f);
    }

    /**
     * Renders a clean, theme-matched rounded/frosted button (or slider) with 120ms hover interpolation.
     */
    public static void renderButton(
        GuiButton btn,
        Minecraft mc,
        int mouseX,
        int mouseY,
        boolean hovered,
        float hoverProgress
    ) {
        if (!btn.visible) {
            return;
        }
        ThemeData theme = ThemeManager.current();
        boolean isLight = theme.getMode() == ThemeMode.LIGHT;
        boolean frosted = ThemeManager.isFrostedGlass() && BackdropBlurRenderer.isBlurReady();
        float x = btn.xPosition;
        float y = btn.yPosition;
        float w = btn.width;
        float h = btn.height;
        float radius = theme.radiusControl();
        float hoverT = hoverProgress <= 0.0f ? 0.0f : (hoverProgress >= 1.0f ? 1.0f : hoverProgress);

        float srScale = mc != null ? Math.max(1.0f, (float) new ScaledResolution(mc).getScaleFactor()) : 2.0f;

        if (btn instanceof GuiOptionSlider) {
            renderSliderButton((GuiOptionSlider) btn, x, y, w, h, radius, hoverT, theme, isLight, frosted, srScale);
            return;
        }

        int textColor;
        if (!btn.enabled) {
            int disabledBg = RenderUtil.withAlpha(theme.surface1(), 0.50f);
            int disabledBorder = RenderUtil.withAlpha(theme.border(), 0.45f);
            RenderUtil.drawRoundedRect(x, y, w, h, radius, disabledBg, disabledBorder, UiTokens.BORDER_WIDTH);
            textColor = theme.textTertiary();
        } else if (frosted) {
            int hoverGlass = RenderUtil.withAlpha(theme.surface3(), isLight ? 0.76f : 0.64f);
            int glassTint = ColorAnimation.lerpArgb(theme.frostedCardTint(), hoverGlass, hoverT);
            int glassBorder = ColorAnimation.lerpArgb(theme.frostedBorder(), theme.borderStrong(), hoverT);
            float sheen = theme.frostedHighlightStrength() + 0.04f * hoverT;
            RenderUtil.drawFrostedRoundedRect(
                x, y, w, h,
                radius,
                glassTint, glassBorder, UiTokens.BORDER_WIDTH,
                sheen, 1.0f
            );
            textColor = theme.textPrimary();
        } else {
            int baseBg = RenderUtil.withAlpha(theme.surface2(), isLight ? 0.90f : 0.84f);
            int hoverBg = RenderUtil.withAlpha(theme.surface3(), isLight ? 0.96f : 0.94f);
            int bg = ColorAnimation.lerpArgb(baseBg, hoverBg, hoverT);
            int border = ColorAnimation.lerpArgb(theme.border(), theme.borderStrong(), hoverT);
            RenderUtil.drawRoundedRect(x, y, w, h, radius, bg, border, UiTokens.BORDER_WIDTH);
            textColor = theme.textPrimary();
        }

        if (btn.displayString != null && !btn.displayString.isEmpty()) {
            if (btn.packedFGColour != 0) {
                textColor = 0xFF000000 | (btn.packedFGColour & 0x00FFFFFF);
            }
            float textW = VanillaTtfFontBridge.measureFormattedUiWidth(btn.displayString, BUTTON_FONT_SIZE, srScale);
            TtfFontRenderer metricFont = FontManager.getInstance().getRenderer(
                FontStyle.of(BUTTON_FONT_SIZE, FontWeight.MEDIUM), srScale
            );
            float textX = x + (w - textW) * 0.5f;
            float textY = y + (h - metricFont.getHeight()) * 0.5f;
            VanillaTtfFontBridge.drawFormattedUiString(
                btn.displayString, textX, textY, textColor, false, BUTTON_FONT_SIZE, srScale
            );
        }
    }

    private static void renderSliderButton(
        GuiOptionSlider slider,
        float x,
        float y,
        float w,
        float h,
        float radius,
        float hoverT,
        ThemeData theme,
        boolean isLight,
        boolean frosted,
        float srScale
    ) {
        if (frosted) {
            int hoverGlass = RenderUtil.withAlpha(theme.surface3(), isLight ? 0.76f : 0.64f);
            int glassTint = ColorAnimation.lerpArgb(theme.frostedCardTint(), hoverGlass, hoverT);
            int glassBorder = ColorAnimation.lerpArgb(theme.frostedBorder(), theme.borderStrong(), hoverT);
            RenderUtil.drawFrostedRoundedRect(
                x, y, w, h,
                radius,
                glassTint, glassBorder, UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength(), 1.0f
            );
        } else {
            int baseBg = RenderUtil.withAlpha(theme.surface2(), isLight ? 0.90f : 0.84f);
            int hoverBg = RenderUtil.withAlpha(theme.surface3(), isLight ? 0.96f : 0.94f);
            int bg = ColorAnimation.lerpArgb(baseBg, hoverBg, hoverT);
            int border = ColorAnimation.lerpArgb(theme.border(), theme.borderStrong(), hoverT);
            RenderUtil.drawRoundedRect(x, y, w, h, radius, bg, border, UiTokens.BORDER_WIDTH);
        }

        float val = 0.0f;
        if (slider instanceof AccessorGuiOptionSlider) {
            val = ((AccessorGuiOptionSlider) slider).hoptimization$getSliderValue();
        }
        val = val < 0.0f ? 0.0f : (val > 1.0f ? 1.0f : val);

        float padH = UiTokens.SPACE_8;
        float trackW = Math.max(0.0f, w - padH * 2.0f);
        float trackH = 3.0f;
        float trackX = x + padH;
        float trackY = y + h - 5.5f;
        float trackR = theme.radiusPill(trackH);

        RenderUtil.drawRoundedRect(trackX, trackY, trackW, trackH, trackR, theme.surface3());
        float fillW = trackW * val;
        if (fillW > 0.5f) {
            RenderUtil.drawRoundedRect(
                trackX, trackY, Math.max(trackH, fillW), trackH, trackR, theme.accent()
            );
        }

        float thumbCenterX = trackX + fillW;
        float thumbCenterY = trackY + trackH * 0.5f;
        if (hoverT > 0.01f) {
            float haloR = 3.0f + 2.5f * hoverT;
            RenderUtil.drawCircle(
                thumbCenterX, thumbCenterY, haloR, RenderUtil.withAlpha(theme.accentMuted(), hoverT)
            );
        }
        float thumbSize = 6.0f;
        RenderUtil.drawRoundedRect(
            thumbCenterX - thumbSize * 0.5f,
            thumbCenterY - thumbSize * 0.5f,
            thumbSize,
            thumbSize,
            theme.radiusPill(thumbSize),
            theme.thumb(),
            isLight ? theme.border() : 0,
            isLight ? UiTokens.BORDER_WIDTH : 0.0f
        );

        if (slider.displayString != null && !slider.displayString.isEmpty()) {
            float textW = VanillaTtfFontBridge.measureFormattedUiWidth(slider.displayString, SLIDER_FONT_SIZE, srScale);
            TtfFontRenderer metricFont = FontManager.getInstance().getRenderer(
                FontStyle.of(SLIDER_FONT_SIZE, FontWeight.MEDIUM), srScale
            );
            float topAreaH = trackY - y;
            float textX = x + (w - textW) * 0.5f;
            float textY = y + Math.max(1.5f, (topAreaH - metricFont.getHeight()) * 0.5f);
            VanillaTtfFontBridge.drawFormattedUiString(
                slider.displayString, textX, textY, theme.textPrimary(), false, SLIDER_FONT_SIZE, srScale
            );
        }
    }
}
