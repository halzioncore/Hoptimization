package com.hoptimization.ui.hud;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.Module;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.BackdropBlurRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.ThemeMode;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/**
 * Interactive HUD layout editor allowing drag-and-drop repositioning, 4 px snapping with 1 px accent
 * alignment guides ({@code 02 §11}), scroll-wheel element scaling, and reset-to-default persistence.
 */
public class HudEditScreen extends GuiScreen {
    private static final float SNAP_THRESHOLD = 4.0f;
    private static final float SCALE_STEP = 0.1f;
    private static final float TOOLBAR_HEIGHT = 28.0f;
    private static final float TOOLBAR_TOP = 12.0f;
    private static final float BUTTON_HEIGHT = 20.0f;
    private static final float ELEMENT_OUTLINE_PAD = 2.0f;

    private final GuiScreen parentScreen;
    private final float[] boundsScratch = new float[5];
    private final float[] otherBoundsScratch = new float[5];
    private final float[] snapResultScratch = new float[3];
    private final StringBuilder scaleLabelScratch = new StringBuilder(8);

    private String draggingId = null;
    private String selectedId = null;
    private float dragOffsetX = 0.0f;
    private float dragOffsetY = 0.0f;

    private boolean activeSnapX = false;
    private boolean activeSnapY = false;
    private float snapGuideX = 0.0f;
    private float snapGuideY = 0.0f;

    private String cachedLangCode = null;
    private String labelTitle = "HUD Layout";
    private String labelReset = "Reset";
    private String labelDone = "Done";
    private String labelOff = "OFF";

    private float toolbarX;
    private float toolbarY;
    private float toolbarW;
    private float resetBtnX;
    private float resetBtnW;
    private float doneBtnX;
    private float doneBtnW;

    /**
     * Creates a HUD editor returning directly to the game when closed.
     */
    public HudEditScreen() {
        this(null);
    }

    /**
     * Creates a HUD editor with an optional parent {@link GuiScreen} to return to on exit.
     *
     * @param parentScreen parent screen, or {@code null}
     */
    public HudEditScreen(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager != null) {
            UiConfig uiConfig = configManager.getUiConfig();
            UiScale.setUserMultiplier(uiConfig.getUiScaleMultiplier());
            boolean reduceMotion = uiConfig.isReduceMotion();
            Animation.setReduceMotion(reduceMotion);
            ThemeManager.getInstance().setReduceMotion(reduceMotion);
        }

        FontManager.setUiScale(UiScale.getScale());
        refreshLabels(true);
        this.draggingId = null;
        this.activeSnapX = false;
        this.activeSnapY = false;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        ScissorStack.clear();
        this.draggingId = null;
        this.activeSnapX = false;
        this.activeSnapY = false;

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager != null) {
            configManager.saveUiConfigAsync();
        }
        FontManager.setUiScale(UiScale.getScale());
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Minecraft mc = this.mc;
        if (mc == null) {
            return;
        }

        refreshLabels(false);

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = configManager != null ? configManager.getUiConfig() : null;
        if (uiConfig != null) {
            UiScale.setUserMultiplier(uiConfig.getUiScaleMultiplier());
        }

        float hudScale = UiScale.getScale();
        FontManager.setUiScale(hudScale);

        ScaledResolution sr = new ScaledResolution(mc);
        int srFactor = Math.max(1, sr.getScaleFactor());
        float scaleRatio = hudScale / (float) srFactor;
        float logicalW = UiScale.getLogicalWidth(hudScale);
        float logicalH = UiScale.getLogicalHeight(hudScale);

        float lMouseX = resolveLogicalMouseX(mouseX, sr, hudScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, hudScale);

        List<HudElement> elements = HudOverlayRenderer.getRegisteredElements();
        if (this.draggingId != null) {
            if (!Mouse.isButtonDown(0)) {
                finishDragging();
            } else {
                updateDragPosition(lMouseX, lMouseY, logicalW, logicalH, uiConfig, elements);
            }
        }

        ThemeData theme = ThemeManager.current();

        HudRenderHelper.GlStateSnapshot glSnapshot = HudRenderHelper.acquireSnapshot();
        glSnapshot.capture();
        if (ThemeManager.isFrostedGlass()) {
            BackdropBlurRenderer.captureAndBlur(ThemeManager.getBlurStrength());
        }
        GlStateManager.pushMatrix();
        try {
            GlStateManager.scale(scaleRatio, scaleRatio, 1.0f);

            // Soft backdrop dims the game slightly while leaving world context clearly visible.
            RenderUtil.drawRect(0.0f, 0.0f, logicalW, logicalH, RenderUtil.withAlpha(theme.backdrop(), 0.55f));

            // Faint viewport center guides when dragging to aid visual symmetry.
            if (this.draggingId != null) {
                int subtleGuide = theme.border();
                RenderUtil.drawRect(logicalW * 0.5f - 0.5f, 0.0f, 1.0f, logicalH, subtleGuide);
                RenderUtil.drawRect(0.0f, logicalH * 0.5f - 0.5f, logicalW, 1.0f, subtleGuide);
            }

            HudElement hoveredElement = findElementAt(lMouseX, lMouseY, logicalW, logicalH, uiConfig, elements);

            for (int i = 0; i < elements.size(); i++) {
                HudElement element = elements.get(i);
                HudOverlayRenderer.resolveElementBounds(element, uiConfig, logicalW, logicalH, this.boundsScratch);
                float ex = this.boundsScratch[0];
                float ey = this.boundsScratch[1];
                float ew = this.boundsScratch[2];
                float eh = this.boundsScratch[3];
                float elemScale = this.boundsScratch[4];

                boolean isDragging = element.getHudId().equals(this.draggingId);
                boolean isSelected = element.getHudId().equals(this.selectedId);
                boolean isHovered = element == hoveredElement && !isMouseOverToolbar(lMouseX, lMouseY);

                renderElementPreview(element, ex, ey, elemScale, hudScale, partialTicks, theme);

                int outlineColor;
                int tintColor = 0;
                if (isDragging || isSelected) {
                    outlineColor = theme.accent();
                    tintColor = RenderUtil.withAlpha(theme.accentMuted(), 0.45f);
                } else if (isHovered) {
                    outlineColor = theme.borderStrong();
                    tintColor = RenderUtil.withAlpha(theme.surface2(), 0.25f);
                } else {
                    outlineColor = theme.border();
                }

                float pad = ELEMENT_OUTLINE_PAD;
                float outlineRadius = Math.max(2.0f, theme.radiusCard() + (theme.radiusCard() > 0.0f ? 2.0f : 0.0f));
                RenderUtil.drawRoundedRect(
                    ex - pad, ey - pad, ew + pad * 2.0f, eh + pad * 2.0f,
                    outlineRadius,
                    tintColor,
                    outlineColor,
                    UiTokens.BORDER_WIDTH
                );

                if (isHovered || isDragging || !element.isHudEnabled()) {
                    renderElementTag(element, ex, ey, ew, eh, elemScale, logicalH, theme, isHovered || isDragging);
                }
            }

            // Crisp 1px accent snap guide lines per 02 §11.
            if (this.draggingId != null) {
                int snapLineColor = RenderUtil.withAlpha(theme.accent(), 0.90f);
                if (this.activeSnapX) {
                    RenderUtil.drawRect(this.snapGuideX - 0.5f, 0.0f, 1.0f, logicalH, snapLineColor);
                }
                if (this.activeSnapY) {
                    RenderUtil.drawRect(0.0f, this.snapGuideY - 0.5f, logicalW, 1.0f, snapLineColor);
                }
            }

            renderToolbar(logicalW, lMouseX, lMouseY, theme);
        } finally {
            ScissorStack.clear();
            GlStateManager.popMatrix();
            FontManager.setUiScale(hudScale);
            glSnapshot.restore();
            HudRenderHelper.releaseSnapshot();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (this.mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        float hudScale = UiScale.getScale();
        float logicalW = UiScale.getLogicalWidth(hudScale);
        float logicalH = UiScale.getLogicalHeight(hudScale);
        float lMouseX = resolveLogicalMouseX(mouseX, sr, hudScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, hudScale);

        computeToolbarMetrics(logicalW);
        if (mouseButton == 0 && isMouseOverToolbar(lMouseX, lMouseY)) {
            if (isInside(lMouseX, lMouseY, this.resetBtnX, this.toolbarY + 4.0f, this.resetBtnW, BUTTON_HEIGHT)) {
                resetAllLayouts();
                return;
            }
            if (isInside(lMouseX, lMouseY, this.doneBtnX, this.toolbarY + 4.0f, this.doneBtnW, BUTTON_HEIGHT)) {
                closeEditor();
                return;
            }
            return;
        }

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = configManager != null ? configManager.getUiConfig() : null;
        List<HudElement> elements = HudOverlayRenderer.getRegisteredElements();
        HudElement hit = findElementAt(lMouseX, lMouseY, logicalW, logicalH, uiConfig, elements);
        if (hit == null) {
            this.selectedId = null;
            return;
        }

        this.selectedId = hit.getHudId();

        if (mouseButton == 0) {
            HudOverlayRenderer.resolveElementBounds(hit, uiConfig, logicalW, logicalH, this.boundsScratch);
            this.draggingId = hit.getHudId();
            this.dragOffsetX = lMouseX - this.boundsScratch[0];
            this.dragOffsetY = lMouseY - this.boundsScratch[1];
            this.activeSnapX = false;
            this.activeSnapY = false;
        } else if (mouseButton == 1) {
            if (isShiftKeyDown()) {
                resetElementLayout(hit.getHudId());
            } else if (hit instanceof Module) {
                Module mod = (Module) hit;
                if (mod.isToggleable() && mod.isAvailable()) {
                    mod.toggle();
                }
            }
        } else if (mouseButton == 2) {
            resetElementLayout(hit.getHudId());
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton != 0 || this.draggingId == null || this.mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        float hudScale = UiScale.getScale();
        float logicalW = UiScale.getLogicalWidth(hudScale);
        float logicalH = UiScale.getLogicalHeight(hudScale);
        float lMouseX = resolveLogicalMouseX(mouseX, sr, hudScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, hudScale);

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = configManager != null ? configManager.getUiConfig() : null;
        updateDragPosition(lMouseX, lMouseY, logicalW, logicalH, uiConfig, HudOverlayRenderer.getRegisteredElements());
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && this.draggingId != null) {
            finishDragging();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || this.mc == null) {
            return;
        }

        float hudScale = UiScale.getScale();
        float logicalW = UiScale.getLogicalWidth(hudScale);
        float logicalH = UiScale.getLogicalHeight(hudScale);
        float lMouseX = Mouse.isCreated() ? (Mouse.getX() / hudScale) : 0.0f;
        float lMouseY = Mouse.isCreated() ? UiScale.physicalMouseYToLogical(Mouse.getY(), hudScale) : 0.0f;

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = configManager != null ? configManager.getUiConfig() : null;
        if (uiConfig == null) {
            return;
        }

        List<HudElement> elements = HudOverlayRenderer.getRegisteredElements();
        HudElement target = findElementAt(lMouseX, lMouseY, logicalW, logicalH, uiConfig, elements);
        if (target == null && this.selectedId != null) {
            target = findElementById(this.selectedId, elements);
        }
        if (target == null) {
            return;
        }

        this.selectedId = target.getHudId();
        HudOverlayRenderer.resolveElementBounds(target, uiConfig, logicalW, logicalH, this.boundsScratch);
        float curX = this.boundsScratch[0];
        float curY = this.boundsScratch[1];
        float curScale = this.boundsScratch[4];

        float delta = wheel > 0 ? SCALE_STEP : -SCALE_STEP;
        float nextScale = Math.round((curScale + delta) * 10.0f) / 10.0f;
        nextScale = HudOverlayRenderer.clampElementScale(nextScale);

        float nextW = Math.max(8.0f, target.getHudWidth()) * nextScale;
        float nextH = Math.max(8.0f, target.getHudHeight()) * nextScale;
        float clampedX = clampCoord(curX, logicalW - nextW);
        float clampedY = clampCoord(curY, logicalH - nextH);

        uiConfig.setHudLayout(target.getHudId(), new UiConfig.HudLayout(clampedX, clampedY, nextScale));
        configManager.saveUiConfigDebounced();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeEditor();
            return;
        }
        if (this.selectedId == null) {
            return;
        }
        int dx = 0;
        int dy = 0;
        if (keyCode == Keyboard.KEY_LEFT) {
            dx = -1;
        } else if (keyCode == Keyboard.KEY_RIGHT) {
            dx = 1;
        } else if (keyCode == Keyboard.KEY_UP) {
            dy = -1;
        } else if (keyCode == Keyboard.KEY_DOWN) {
            dy = 1;
        } else {
            return;
        }

        int step = isShiftKeyDown() ? 8 : 1;
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = configManager != null ? configManager.getUiConfig() : null;
        if (uiConfig == null) {
            return;
        }
        HudElement target = findElementById(this.selectedId, HudOverlayRenderer.getRegisteredElements());
        if (target == null) {
            return;
        }

        float hudScale = UiScale.getScale();
        float logicalW = UiScale.getLogicalWidth(hudScale);
        float logicalH = UiScale.getLogicalHeight(hudScale);
        HudOverlayRenderer.resolveElementBounds(target, uiConfig, logicalW, logicalH, this.boundsScratch);

        float nextX = clampCoord(this.boundsScratch[0] + dx * step, logicalW - this.boundsScratch[2]);
        float nextY = clampCoord(this.boundsScratch[1] + dy * step, logicalH - this.boundsScratch[3]);
        uiConfig.setHudLayout(target.getHudId(), new UiConfig.HudLayout(nextX, nextY, this.boundsScratch[4]));
        configManager.saveUiConfigDebounced();
    }

    /**
     * Resets all registered HUD elements back to their default layouts and schedules a debounced config save.
     */
    public void resetAllLayouts() {
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager == null) {
            return;
        }
        UiConfig uiConfig = configManager.getUiConfig();
        List<HudElement> elements = HudOverlayRenderer.getRegisteredElements();
        for (int i = 0; i < elements.size(); i++) {
            uiConfig.setHudLayout(elements.get(i).getHudId(), null);
        }
        this.draggingId = null;
        this.activeSnapX = false;
        this.activeSnapY = false;
        configManager.saveUiConfigDebounced();
    }

    private void resetElementLayout(String id) {
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager == null || id == null) {
            return;
        }
        configManager.getUiConfig().setHudLayout(id, null);
        configManager.saveUiConfigDebounced();
    }

    private void closeEditor() {
        if (this.mc == null) {
            return;
        }
        finishDragging();
        this.mc.displayGuiScreen(this.parentScreen);
    }

    private void finishDragging() {
        if (this.draggingId == null) {
            return;
        }
        this.draggingId = null;
        this.activeSnapX = false;
        this.activeSnapY = false;
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager != null) {
            configManager.saveUiConfigDebounced();
        }
    }

    private void updateDragPosition(
        float lMouseX,
        float lMouseY,
        float logicalW,
        float logicalH,
        UiConfig uiConfig,
        List<HudElement> elements
    ) {
        if (uiConfig == null || this.draggingId == null) {
            return;
        }
        HudElement dragged = findElementById(this.draggingId, elements);
        if (dragged == null) {
            this.draggingId = null;
            return;
        }

        HudOverlayRenderer.resolveElementBounds(dragged, uiConfig, logicalW, logicalH, this.boundsScratch);
        float w = this.boundsScratch[2];
        float h = this.boundsScratch[3];
        float elemScale = this.boundsScratch[4];

        float rawX = lMouseX - this.dragOffsetX;
        float rawY = lMouseY - this.dragOffsetY;
        float margin = UiTokens.HUD_SCREEN_MARGIN;

        // Evaluate X snap (snapResultScratch: [bestAbsDelta, bestDelta, bestGuide])
        this.snapResultScratch[0] = SNAP_THRESHOLD + 1.0f;
        this.snapResultScratch[1] = 0.0f;
        this.snapResultScratch[2] = 0.0f;
        testSnapTarget(rawX, w, margin, this.snapResultScratch);
        testSnapTarget(rawX, w, 0.0f, this.snapResultScratch);
        testSnapTarget(rawX, w, logicalW * 0.5f, this.snapResultScratch);
        testSnapTarget(rawX, w, logicalW - margin, this.snapResultScratch);
        testSnapTarget(rawX, w, logicalW, this.snapResultScratch);

        for (int i = 0; i < elements.size(); i++) {
            HudElement other = elements.get(i);
            if (other == dragged) {
                continue;
            }
            HudOverlayRenderer.resolveElementBounds(other, uiConfig, logicalW, logicalH, this.otherBoundsScratch);
            float ox = this.otherBoundsScratch[0];
            float ow = this.otherBoundsScratch[2];
            testSnapTarget(rawX, w, ox, this.snapResultScratch);
            testSnapTarget(rawX, w, ox + ow, this.snapResultScratch);
            testSnapTarget(rawX, w, ox + ow * 0.5f, this.snapResultScratch);
        }

        boolean snappedX = this.snapResultScratch[0] <= SNAP_THRESHOLD;
        float bestDx = this.snapResultScratch[1];
        float bestGuideX = this.snapResultScratch[2];

        // Evaluate Y snap
        this.snapResultScratch[0] = SNAP_THRESHOLD + 1.0f;
        this.snapResultScratch[1] = 0.0f;
        this.snapResultScratch[2] = 0.0f;
        testSnapTarget(rawY, h, margin, this.snapResultScratch);
        testSnapTarget(rawY, h, 0.0f, this.snapResultScratch);
        testSnapTarget(rawY, h, logicalH * 0.5f, this.snapResultScratch);
        testSnapTarget(rawY, h, logicalH - margin, this.snapResultScratch);
        testSnapTarget(rawY, h, logicalH, this.snapResultScratch);

        for (int i = 0; i < elements.size(); i++) {
            HudElement other = elements.get(i);
            if (other == dragged) {
                continue;
            }
            HudOverlayRenderer.resolveElementBounds(other, uiConfig, logicalW, logicalH, this.otherBoundsScratch);
            float oy = this.otherBoundsScratch[1];
            float oh = this.otherBoundsScratch[3];
            testSnapTarget(rawY, h, oy, this.snapResultScratch);
            testSnapTarget(rawY, h, oy + oh, this.snapResultScratch);
            testSnapTarget(rawY, h, oy + oh * 0.5f, this.snapResultScratch);
        }

        boolean snappedY = this.snapResultScratch[0] <= SNAP_THRESHOLD;
        float bestDy = this.snapResultScratch[1];
        float bestGuideY = this.snapResultScratch[2];

        float finalX = snappedX ? (rawX + bestDx) : rawX;
        float finalY = snappedY ? (rawY + bestDy) : rawY;

        finalX = Math.round(clampCoord(finalX, logicalW - w));
        finalY = Math.round(clampCoord(finalY, logicalH - h));

        this.activeSnapX = snappedX;
        this.snapGuideX = bestGuideX;
        this.activeSnapY = snappedY;
        this.snapGuideY = bestGuideY;

        uiConfig.setHudLayout(this.draggingId, new UiConfig.HudLayout(finalX, finalY, elemScale));
    }

    private static void testSnapTarget(float rawStart, float span, float target, float[] snapState) {
        testSnapCandidate(target - rawStart, target, snapState);
        testSnapCandidate(target - (rawStart + span), target, snapState);
        testSnapCandidate(target - (rawStart + span * 0.5f), target, snapState);
    }

    private static void testSnapCandidate(float delta, float target, float[] snapState) {
        float absDelta = Math.abs(delta);
        if (absDelta <= SNAP_THRESHOLD && absDelta < snapState[0]) {
            snapState[0] = absDelta;
            snapState[1] = delta;
            snapState[2] = target;
        }
    }

    private void renderElementPreview(
        HudElement element,
        float x,
        float y,
        float elemScale,
        float hudScale,
        float partialTicks,
        ThemeData theme
    ) {
        if (Math.abs(elemScale - 1.0f) > 0.001f) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0.0f);
            GlStateManager.scale(elemScale, elemScale, 1.0f);
            FontManager.setUiScale(hudScale * elemScale);
            try {
                element.renderHud(0.0f, 0.0f, partialTicks, theme, true);
            } finally {
                GlStateManager.popMatrix();
                FontManager.setUiScale(hudScale);
            }
        } else {
            element.renderHud(x, y, partialTicks, theme, true);
        }
    }

    private void renderElementTag(
        HudElement element,
        float ex,
        float ey,
        float ew,
        float eh,
        float elemScale,
        float logicalH,
        ThemeData theme,
        boolean showName
    ) {
        TtfFontRenderer font = FontManager.caption();
        String name = element.getHudDisplayName();
        boolean disabled = !element.isHudEnabled();

        String badgeText;
        if (!showName && disabled) {
            badgeText = this.labelOff;
        } else if (Math.abs(elemScale - 1.0f) > 0.05f) {
            this.scaleLabelScratch.setLength(0);
            int tenths = Math.round(elemScale * 10.0f);
            this.scaleLabelScratch.append(name).append(' ');
            this.scaleLabelScratch.append(tenths / 10).append('.').append(tenths % 10).append('x');
            if (disabled) {
                this.scaleLabelScratch.append(" · ").append(this.labelOff);
            }
            badgeText = this.scaleLabelScratch.toString();
        } else if (disabled) {
            this.scaleLabelScratch.setLength(0);
            this.scaleLabelScratch.append(name).append(" · ").append(this.labelOff);
            badgeText = this.scaleLabelScratch.toString();
        } else {
            badgeText = name;
        }

        float padH = UiTokens.SPACE_6;
        float tagW = font.getWidth(badgeText) + padH * 2.0f;
        float tagH = 16.0f;
        float tagX = ex;
        float tagY = (ey - tagH - UiTokens.SPACE_4 >= 0.0f)
            ? (ey - tagH - UiTokens.SPACE_4)
            : Math.min(logicalH - tagH, ey + eh + UiTokens.SPACE_4);

        RenderUtil.drawRoundedRect(
            tagX, tagY, tagW, tagH,
            theme.nestedRadius(theme.radiusControl(), 2.0f),
            RenderUtil.withAlpha(theme.surface0(), 0.90f),
            theme.border(),
            UiTokens.BORDER_WIDTH
        );
        int textColor = disabled ? theme.textTertiary() : theme.textPrimary();
        font.drawString(badgeText, tagX + padH, tagY + (tagH - font.getHeight()) * 0.5f, textColor);
    }

    private void renderToolbar(float logicalW, float mouseX, float mouseY, ThemeData theme) {
        computeToolbarMetrics(logicalW);
        if (ThemeManager.isFrostedGlass()) {
            RenderUtil.drawFrostedRoundedRectWithShadow(
                this.toolbarX, this.toolbarY, this.toolbarW, TOOLBAR_HEIGHT,
                theme.radiusCard(),
                theme.frostedWindowTint(),
                theme.frostedBorder(),
                UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength(),
                1.0f,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                theme.popupShadowColor()
            );
        } else {
            RenderUtil.drawRoundedRectWithShadow(
                this.toolbarX, this.toolbarY, this.toolbarW, TOOLBAR_HEIGHT,
                theme.radiusCard(),
                RenderUtil.withAlpha(theme.surface0(), 0.92f),
                theme.border(),
                UiTokens.BORDER_WIDTH,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                theme.popupShadowColor()
            );
        }

        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        float titleX = this.toolbarX + UiTokens.SPACE_12;
        float titleY = this.toolbarY + (TOOLBAR_HEIGHT - bodyFont.getHeight()) * 0.5f;
        bodyFont.drawString(this.labelTitle, titleX, titleY, theme.textPrimary());

        float btnY = this.toolbarY + 4.0f;
        boolean resetHover = isInside(mouseX, mouseY, this.resetBtnX, btnY, this.resetBtnW, BUTTON_HEIGHT);
        RenderUtil.drawRoundedRect(
            this.resetBtnX, btnY, this.resetBtnW, BUTTON_HEIGHT,
            theme.radiusControl(),
            resetHover ? theme.surface3() : theme.surface2(),
            theme.border(),
            UiTokens.BORDER_WIDTH
        );
        captionFont.drawStringCentered(
            this.labelReset,
            this.resetBtnX + this.resetBtnW * 0.5f,
            btnY + (BUTTON_HEIGHT - captionFont.getHeight()) * 0.5f,
            theme.textPrimary()
        );

        boolean doneHover = isInside(mouseX, mouseY, this.doneBtnX, btnY, this.doneBtnW, BUTTON_HEIGHT);
        RenderUtil.drawRoundedRect(
            this.doneBtnX, btnY, this.doneBtnW, BUTTON_HEIGHT,
            theme.radiusControl(),
            doneHover ? theme.accentHover() : theme.accent()
        );
        captionFont.drawStringCentered(
            this.labelDone,
            this.doneBtnX + this.doneBtnW * 0.5f,
            btnY + (BUTTON_HEIGHT - captionFont.getHeight()) * 0.5f,
            theme.textOnAccent()
        );
    }

    private void computeToolbarMetrics(float logicalW) {
        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        float titleW = bodyFont.getWidth(this.labelTitle);
        this.resetBtnW = Math.max(48.0f, captionFont.getWidth(this.labelReset) + UiTokens.SPACE_16);
        this.doneBtnW = Math.max(48.0f, captionFont.getWidth(this.labelDone) + UiTokens.SPACE_16);
        this.toolbarW = UiTokens.SPACE_12 + titleW + UiTokens.SPACE_12
            + this.resetBtnW + UiTokens.SPACE_6 + this.doneBtnW + UiTokens.SPACE_8;
        this.toolbarX = Math.round((logicalW - this.toolbarW) * 0.5f);
        this.toolbarY = TOOLBAR_TOP;
        this.resetBtnX = this.toolbarX + UiTokens.SPACE_12 + titleW + UiTokens.SPACE_12;
        this.doneBtnX = this.resetBtnX + this.resetBtnW + UiTokens.SPACE_6;
    }

    private void refreshLabels(boolean force) {
        String lang = HudRenderHelper.getCurrentLanguageCode();
        if (!force && lang.equals(this.cachedLangCode)) {
            return;
        }
        this.cachedLangCode = lang;
        this.labelTitle = HudRenderHelper.resolveLang("ui.hoptimization.hud_editor.title", "HUD Layout");
        this.labelReset = HudRenderHelper.resolveLang("ui.hoptimization.hud_editor.reset", "Reset");
        this.labelDone = HudRenderHelper.resolveLang("ui.hoptimization.hud_editor.done", "Done");
        this.labelOff = HudRenderHelper.resolveLang("ui.hoptimization.hud_editor.off", "OFF");
    }

    private boolean isMouseOverToolbar(float mouseX, float mouseY) {
        return isInside(mouseX, mouseY, this.toolbarX, this.toolbarY, this.toolbarW, TOOLBAR_HEIGHT);
    }

    private HudElement findElementAt(
        float mouseX,
        float mouseY,
        float logicalW,
        float logicalH,
        UiConfig uiConfig,
        List<HudElement> elements
    ) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            HudOverlayRenderer.resolveElementBounds(element, uiConfig, logicalW, logicalH, this.boundsScratch);
            if (isInside(
                mouseX, mouseY,
                this.boundsScratch[0] - ELEMENT_OUTLINE_PAD,
                this.boundsScratch[1] - ELEMENT_OUTLINE_PAD,
                this.boundsScratch[2] + ELEMENT_OUTLINE_PAD * 2.0f,
                this.boundsScratch[3] + ELEMENT_OUTLINE_PAD * 2.0f
            )) {
                return element;
            }
        }
        return null;
    }

    private static HudElement findElementById(String id, List<HudElement> elements) {
        if (id == null) {
            return null;
        }
        for (int i = 0; i < elements.size(); i++) {
            HudElement element = elements.get(i);
            if (id.equals(element.getHudId())) {
                return element;
            }
        }
        return null;
    }

    private static float clampCoord(float value, float maxAllowed) {
        float safeMax = Math.max(0.0f, maxAllowed);
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > safeMax) {
            return safeMax;
        }
        return value;
    }

    private static float resolveLogicalMouseX(int mcMouseX, ScaledResolution sr, float hudScale) {
        if (Mouse.isCreated()) {
            return Mouse.getX() / hudScale;
        }
        return UiScale.mcScaledToLogicalX(mcMouseX, sr.getScaledWidth_double(), hudScale);
    }

    private static float resolveLogicalMouseY(int mcMouseY, ScaledResolution sr, float hudScale) {
        if (Mouse.isCreated()) {
            return UiScale.physicalMouseYToLogical(Mouse.getY(), hudScale);
        }
        return UiScale.mcScaledToLogicalY(mcMouseY, sr.getScaledHeight_double(), hudScale);
    }

    private static boolean isInside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
}
