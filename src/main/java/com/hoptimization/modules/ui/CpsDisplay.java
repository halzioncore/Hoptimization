package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.ClickCpsTracker;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;

/**
 * Real-time clicks-per-second (LMB/RMB) HUD module backed by a zero-allocation 1000 ms sliding window.
 */
public class CpsDisplay extends Module implements HudElement {
    private static final String SEP_PIPE = " | ";
    private static final String LABEL_CPS = " CPS";
    private static final String BASELINE_SINGLE = "00 CPS";
    private static final String BASELINE_DUAL = "00 | 00 CPS";

    private final BooleanSetting showRightClick;
    private final ModeSetting style;
    private final StringBuilder formatScratch = new StringBuilder(8);

    /**
     * Creates the CPS Display module, registers its settings, and attaches it to {@link HudOverlayRenderer}.
     */
    public CpsDisplay() {
        super("cps_display", Category.UI);
        this.showRightClick = addSetting(
            new BooleanSetting("show_right_click", "setting.hoptimization.cps_display.show_right_click.name", true)
        );
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.cps_display.style.name", "card", "card", "text")
        );
        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.ensureRegistered();
        ClickCpsTracker.ensureRegistered();
    }

    /**
     * Returns the right-click CPS visibility toggle setting.
     *
     * @return show right-click setting
     */
    public BooleanSetting getShowRightClickSetting() {
        return this.showRightClick;
    }

    /**
     * Returns the HUD rendering style setting.
     *
     * @return style setting
     */
    public ModeSetting getStyleSetting() {
        return this.style;
    }

    /**
     * Returns the current left-click CPS over the trailing 1000 ms window.
     *
     * @return left clicks per second
     */
    public int getLeftCps() {
        return ClickCpsTracker.getInstance().getLeftCps();
    }

    /**
     * Returns the current right-click CPS over the trailing 1000 ms window.
     *
     * @return right clicks per second
     */
    public int getRightCps() {
        return ClickCpsTracker.getInstance().getRightCps();
    }

    @Override
    protected void onEnable() {
        ClickCpsTracker.getInstance().acquire();
    }

    @Override
    protected void onDisable() {
        ClickCpsTracker.getInstance().release();
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
        boolean dual = this.showRightClick.isEnabled();
        float minTextW = font.getWidth(dual ? BASELINE_DUAL : BASELINE_SINGLE);
        float liveTextW = measureLiveWidth(font, dual, getLeftCps(), getRightCps());
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
        return UiTokens.HUD_SCREEN_MARGIN + 32.0f;
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        boolean dual = this.showRightClick.isEnabled();
        int left = getLeftCps();
        int right = getRightCps();

        Minecraft mc = Minecraft.getMinecraft();
        if (editMode && left == 0 && right == 0 && (mc == null || mc.thePlayer == null)) {
            left = 8;
            right = 4;
        }

        TtfFontRenderer font = FontManager.body();
        String leftStr = HudRenderHelper.formatInt(left, this.formatScratch);
        String rightStr = dual ? HudRenderHelper.formatInt(right, this.formatScratch) : "";

        float leftW = font.getWidth(leftStr);
        float pipeW = dual ? font.getWidth(SEP_PIPE) : 0.0f;
        float rightW = dual ? font.getWidth(rightStr) : 0.0f;
        float labelW = font.getWidth(LABEL_CPS);
        float contentW = leftW + pipeW + rightW + labelW;
        float minTextW = font.getWidth(dual ? BASELINE_DUAL : BASELINE_SINGLE);

        float boxW = Math.max(Keystrokes.getAlignedWidthFor(getHudId()), Math.max(minTextW, contentW) + UiTokens.HUD_PADDING_H * 2.0f);
        float boxH = font.getHeight() + UiTokens.HUD_PADDING_V * 2.0f;

        boolean cardMode = !this.style.is("text");
        if (cardMode) {
            HudRenderHelper.drawHudCard(x, y, boxW, boxH, theme, 1.0f);
        }

        boolean shadow = !cardMode;
        float cursorX = x + (boxW - contentW) * 0.5f;
        float textY = y + (boxH - font.getHeight()) * 0.5f;

        cursorX = HudRenderHelper.drawHudText(font, leftStr, cursorX, textY, theme.accent(), shadow);
        if (dual) {
            cursorX = HudRenderHelper.drawHudText(font, SEP_PIPE, cursorX, textY, theme.textTertiary(), shadow);
            cursorX = HudRenderHelper.drawHudText(font, rightStr, cursorX, textY, theme.textPrimary(), shadow);
        }
        HudRenderHelper.drawHudText(font, LABEL_CPS, cursorX, textY, theme.textSecondary(), shadow);
    }

    private float measureLiveWidth(TtfFontRenderer font, boolean dual, int left, int right) {
        String leftStr = HudRenderHelper.formatInt(left, this.formatScratch);
        float w = font.getWidth(leftStr) + font.getWidth(LABEL_CPS);
        if (dual) {
            String rightStr = HudRenderHelper.formatInt(right, this.formatScratch);
            w += font.getWidth(SEP_PIPE) + font.getWidth(rightStr);
        }
        return w;
    }
}
