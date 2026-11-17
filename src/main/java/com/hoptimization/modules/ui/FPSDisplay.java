package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;

/**
 * Lightweight FPS counter HUD module displaying {@link Minecraft#getDebugFPS()} with zero per-frame allocations.
 */
public class FPSDisplay extends Module implements HudElement {
    private static final String LABEL_FPS = " FPS";
    private static final String BASELINE_FPS = "000 FPS";

    private final ModeSetting style;
    private final StringBuilder formatScratch = new StringBuilder(8);
    private int lastFps = -1;
    private String cachedFpsText = "0";

    /**
     * Creates the FPS Display module, registers its style setting, and attaches it to {@link HudOverlayRenderer}.
     */
    public FPSDisplay() {
        super("fps_display", Category.UI);
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.fps_display.style.name", "card", "card", "text")
        );
        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.ensureRegistered();
    }

    /**
     * Returns the HUD rendering style setting ({@code card} or {@code text}).
     *
     * @return style setting
     */
    public ModeSetting getStyleSetting() {
        return this.style;
    }

    @Override
    public String getHudId() {
        return getId();
    }

    @Override
    public String getHudDisplayName() {
        return getDisplayName();
    }

    @Override
    public boolean isHudEnabled() {
        return isEnabled();
    }

    @Override
    public float getHudWidth() {
        TtfFontRenderer font = FontManager.body();
        String fpsStr = getCachedFpsString(false);
        float minTextW = font.getWidth(BASELINE_FPS);
        float liveTextW = font.getWidth(fpsStr) + font.getWidth(LABEL_FPS);
        return Math.max(minTextW, liveTextW) + UiTokens.HUD_PADDING_H * 2.0f;
    }

    @Override
    public float getHudHeight() {
        return FontManager.body().getHeight() + UiTokens.HUD_PADDING_V * 2.0f;
    }

    @Override
    public float getDefaultHudX(float screenWidth, float screenHeight) {
        return UiTokens.HUD_SCREEN_MARGIN;
    }

    @Override
    public float getDefaultHudY(float screenWidth, float screenHeight) {
        return UiTokens.HUD_SCREEN_MARGIN;
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        TtfFontRenderer font = FontManager.body();
        String fpsStr = getCachedFpsString(editMode);

        float numW = font.getWidth(fpsStr);
        float labelW = font.getWidth(LABEL_FPS);
        float contentW = numW + labelW;
        float minTextW = font.getWidth(BASELINE_FPS);

        float boxW = Math.max(minTextW, contentW) + UiTokens.HUD_PADDING_H * 2.0f;
        float boxH = font.getHeight() + UiTokens.HUD_PADDING_V * 2.0f;

        boolean cardMode = !this.style.is("text");
        if (cardMode) {
            HudRenderHelper.drawHudCard(x, y, boxW, boxH, theme, 1.0f);
        }

        boolean shadow = !cardMode;
        float cursorX = x + (boxW - contentW) * 0.5f;
        float textY = y + (boxH - font.getHeight()) * 0.5f;

        cursorX = HudRenderHelper.drawHudText(font, fpsStr, cursorX, textY, theme.accent(), shadow);
        HudRenderHelper.drawHudText(font, LABEL_FPS, cursorX, textY, theme.textSecondary(), shadow);
    }

    private String getCachedFpsString(boolean editMode) {
        int fps = Minecraft.getDebugFPS();
        if (editMode && fps <= 0) {
            fps = 144;
        } else if (fps < 0) {
            fps = 0;
        }
        if (fps != this.lastFps) {
            this.lastFps = fps;
            this.cachedFpsText = HudRenderHelper.formatInt(fps, this.formatScratch);
        }
        return this.cachedFpsText;
    }
}
