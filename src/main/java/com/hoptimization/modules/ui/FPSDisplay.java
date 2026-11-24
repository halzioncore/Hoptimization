package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;

/**
 * Lightweight FPS & Ping counter HUD module displaying {@link Minecraft#getDebugFPS()} and server latency.
 */
public class FPSDisplay extends Module implements HudElement {
    private static final String LABEL_FPS = " FPS";
    private static final String LABEL_SEP = " | ";
    private static final String LABEL_MS = " ms";
    private static final String BASELINE_FPS = "000 FPS";
    private static final String BASELINE_FPS_PING = "000 FPS | 00 ms";

    private final ModeSetting style;
    private final BooleanSetting showPing;
    private final StringBuilder fpsScratch = new StringBuilder(8);
    private final StringBuilder pingScratch = new StringBuilder(8);
    private int lastFps = -1;
    private String cachedFpsText = "0";
    private int lastPing = -1;
    private String cachedPingText = "0";

    /**
     * Creates the FPS Display module, registers its style setting, and attaches it to {@link HudOverlayRenderer}.
     */
    public FPSDisplay() {
        super("fps_display", Category.UI);
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.fps_display.style.name", "card", "card", "text")
        );
        this.showPing = addSetting(
            new BooleanSetting("show_ping", "setting.hoptimization.fps_display.show_ping.name", true)
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
        boolean pingEnabled = this.showPing.isEnabled();
        float minTextW = font.getWidth(pingEnabled ? BASELINE_FPS_PING : BASELINE_FPS);
        float liveTextW = font.getWidth(fpsStr) + font.getWidth(LABEL_FPS);
        if (pingEnabled) {
            String pingStr = getCachedPingString(false);
            liveTextW += font.getWidth(LABEL_SEP) + font.getWidth(pingStr) + font.getWidth(LABEL_MS);
        }
        return Math.max(Keystrokes.getAlignedWidthFor(getHudId()), Math.max(minTextW, liveTextW) + UiTokens.HUD_PADDING_H * 2.0f);
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
        boolean pingEnabled = this.showPing.isEnabled();
        String pingStr = pingEnabled ? getCachedPingString(editMode) : null;

        float contentW = font.getWidth(fpsStr) + font.getWidth(LABEL_FPS);
        if (pingEnabled) {
            contentW += font.getWidth(LABEL_SEP) + font.getWidth(pingStr) + font.getWidth(LABEL_MS);
        }
        float minTextW = font.getWidth(pingEnabled ? BASELINE_FPS_PING : BASELINE_FPS);

        float boxW = Math.max(Keystrokes.getAlignedWidthFor(getHudId()), Math.max(minTextW, contentW) + UiTokens.HUD_PADDING_H * 2.0f);
        float boxH = font.getHeight() + UiTokens.HUD_PADDING_V * 2.0f;

        boolean cardMode = !this.style.is("text");
        if (cardMode) {
            HudRenderHelper.drawHudCard(x, y, boxW, boxH, theme, 1.0f);
        }

        boolean shadow = !cardMode;
        float cursorX = x + (boxW - contentW) * 0.5f;
        float textY = y + (boxH - font.getHeight()) * 0.5f;

        cursorX = HudRenderHelper.drawHudText(font, fpsStr, cursorX, textY, theme.accent(), shadow);
        cursorX = HudRenderHelper.drawHudText(font, LABEL_FPS, cursorX, textY, theme.textSecondary(), shadow);
        if (pingEnabled) {
            cursorX = HudRenderHelper.drawHudText(font, LABEL_SEP, cursorX, textY, theme.textTertiary(), shadow);
            cursorX = HudRenderHelper.drawHudText(font, pingStr, cursorX, textY, theme.textPrimary(), shadow);
            HudRenderHelper.drawHudText(font, LABEL_MS, cursorX, textY, theme.textSecondary(), shadow);
        }
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
            this.cachedFpsText = HudRenderHelper.formatInt(fps, this.fpsScratch);
        }
        return this.cachedFpsText;
    }

    private String getCachedPingString(boolean editMode) {
        int ping = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            NetHandlerPlayClient nh = mc.getNetHandler();
            if (nh != null) {
                NetworkPlayerInfo info = nh.getPlayerInfo(mc.thePlayer.getUniqueID());
                if (info != null) {
                    ping = Math.max(0, info.getResponseTime());
                }
            }
        }
        if (editMode && ping <= 0) {
            ping = 32;
        }
        if (ping != this.lastPing) {
            this.lastPing = ping;
            this.cachedPingText = HudRenderHelper.formatInt(ping, this.pingScratch);
        }
        return this.cachedPingText;
    }
}
