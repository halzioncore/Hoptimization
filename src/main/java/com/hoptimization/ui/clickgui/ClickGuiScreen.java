package com.hoptimization.ui.clickgui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
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
 * ClickGUI 主窗口屏幕（第 3 步：窗口框架）。
 * <p>
 * 负责打开/关闭缓动动画、侧边栏导航与指示条平滑滑动、内容区头部与“全部 | 已启用”切换、
 * 品牌区窗口拖动与 {@code ui.json} 位置/分类持久化；卡片列表区按规范裁剪并留空，待第 4 步填充。
 */
public class ClickGuiScreen extends GuiScreen {
    public static final float WINDOW_WIDTH = 680.0f;
    public static final float WINDOW_HEIGHT = 420.0f;
    private static final float CONTENT_MARGIN = UiTokens.SPACE_4;
    private static final float LIST_BOTTOM_PADDING = UiTokens.SPACE_20;
    private static final float OPEN_SCALE_MIN = 0.96f;

    private final ClickGuiSidebar sidebar = new ClickGuiSidebar();
    private final ClickGuiContentHeader contentHeader = new ClickGuiContentHeader();
    private final Animation openCloseAnim = new Animation(0.0f, 1.0f, AnimDuration.SLOW, Easing.EASE_OUT_QUINT);

    private Category activeCategory = Category.COMBAT;
    private boolean settingsViewActive = false;
    private boolean filterEnabledOnly = false;
    private boolean closing = false;

    private float windowX;
    private float windowY;
    private boolean dragging = false;
    private float dragOffsetX;
    private float dragOffsetY;
    private boolean positionInitialized = false;
    private boolean positionModified = false;

    private float lastUiScale = -1.0f;
    private float lastLogicalW = -1.0f;
    private float lastLogicalH = -1.0f;

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(false);

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = configManager != null ? configManager.getUiConfig() : null;
        if (uiConfig != null) {
            boolean reduceMotion = uiConfig.isReduceMotion();
            Animation.setReduceMotion(reduceMotion);
            ThemeManager.getInstance().setReduceMotion(reduceMotion);
            UiScale.setUserMultiplier(uiConfig.getUiScaleMultiplier());
            activeCategory = Category.fromIdOrDefault(uiConfig.getLastCategory(), Category.COMBAT);
        }

        float uiScale = UiScale.getScale();
        lastUiScale = uiScale;
        FontManager.setUiScale(uiScale);

        sidebar.refreshLabels();
        contentHeader.refreshLabels();

        float logicalW = UiScale.getLogicalWidth();
        float logicalH = UiScale.getLogicalHeight();
        lastLogicalW = logicalW;
        lastLogicalH = logicalH;

        if (!positionInitialized) {
            if (uiConfig != null && uiConfig.isWindowPositionSet()) {
                windowX = uiConfig.getWindowX();
                windowY = uiConfig.getWindowY();
            } else {
                windowX = (logicalW - WINDOW_WIDTH) * 0.5f;
                windowY = (logicalH - WINDOW_HEIGHT) * 0.5f;
            }
            positionInitialized = true;
        }
        clampWindowPosition(logicalW, logicalH);

        sidebar.snapSelection(activeCategory, settingsViewActive, WINDOW_HEIGHT);
        contentHeader.snapFilter(filterEnabledOnly);

        closing = false;
        if (Animation.isReduceMotion()) {
            openCloseAnim.snapTo(1.0f);
        } else {
            openCloseAnim.reset(0.0f, 1.0f);
        }
    }

    @Override
    public void onGuiClosed() {
        ScissorStack.clear();
        dragging = false;
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager != null) {
            UiConfig uiConfig = configManager.getUiConfig();
            if (positionModified || uiConfig.isWindowPositionSet()) {
                uiConfig.setWindowPosition(windowX, windowY);
            }
            uiConfig.setLastCategory(activeCategory);
            configManager.saveUiConfigAsync();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Minecraft mc = this.mc;
        if (mc == null) {
            return;
        }
        if (closing && openCloseAnim.isFinished()) {
            mc.displayGuiScreen(null);
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        float uiScale = UiScale.getScale();
        if (Math.abs(uiScale - lastUiScale) > 0.0001f) {
            lastUiScale = uiScale;
            FontManager.setUiScale(uiScale);
            sidebar.refreshLabels();
            contentHeader.refreshLabels();
        }

        float logicalW = UiScale.getLogicalWidth();
        float logicalH = UiScale.getLogicalHeight();
        if (Math.abs(logicalW - lastLogicalW) > 0.5f || Math.abs(logicalH - lastLogicalH) > 0.5f) {
            lastLogicalW = logicalW;
            lastLogicalH = logicalH;
            if (!dragging) {
                if (positionModified || hasSavedWindowPosition()) {
                    clampWindowPosition(logicalW, logicalH);
                } else {
                    windowX = (logicalW - WINDOW_WIDTH) * 0.5f;
                    windowY = (logicalH - WINDOW_HEIGHT) * 0.5f;
                    clampWindowPosition(logicalW, logicalH);
                }
            }
        }

        float lMouseX = resolveLogicalMouseX(mouseX, sr, uiScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, uiScale);

        if (dragging) {
            if (!Mouse.isButtonDown(0)) {
                finishDragging();
            } else {
                windowX = lMouseX - dragOffsetX;
                windowY = lMouseY - dragOffsetY;
                clampWindowPosition(logicalW, logicalH);
                positionModified = true;
            }
        }

        float progress = openCloseAnim.get();
        if (progress < 0.0f) {
            progress = 0.0f;
        } else if (progress > 1.0f) {
            progress = 1.0f;
        }

        ThemeData theme = ThemeManager.current();
        float windowAlpha = progress * theme.style().getWindowOpacity();
        float animScale = OPEN_SCALE_MIN + (1.0f - OPEN_SCALE_MIN) * progress;
        float scaleRatio = uiScale / (float) sr.getScaleFactor();

        GlStateManager.pushMatrix();
        GlStateManager.scale(scaleRatio, scaleRatio, 1.0f);
        try {
            // 背后游戏画面遮罩 backdrop（随打开/关闭进度淡入淡出）
            RenderUtil.drawRect(0.0f, 0.0f, logicalW, logicalH, RenderUtil.withAlpha(theme.backdrop(), progress));

            float centerX = windowX + WINDOW_WIDTH * 0.5f;
            float centerY = windowY + WINDOW_HEIGHT * 0.5f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(centerX, centerY, 0.0f);
            GlStateManager.scale(animScale, animScale, 1.0f);
            GlStateManager.translate(-centerX, -centerY, 0.0f);
            try {
                renderWindowContents(lMouseX, lMouseY, windowAlpha, theme);
            } finally {
                ScissorStack.clear();
                GlStateManager.popMatrix();
            }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == getOpenKeybind()) {
            beginClose();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0 || closing || this.mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        float uiScale = UiScale.getScale();
        float lMouseX = resolveLogicalMouseX(mouseX, sr, uiScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, uiScale);

        if (!isInside(lMouseX, lMouseY, windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT)) {
            return;
        }

        float localX = lMouseX - windowX;
        float localY = lMouseY - windowY;
        if (sidebar.isInBrandDragArea(localX, localY)) {
            dragging = true;
            dragOffsetX = localX;
            dragOffsetY = localY;
            return;
        }

        ClickGuiSidebar.ClickResult sidebarClick = sidebar.handleClick(windowX, windowY, WINDOW_HEIGHT, lMouseX, lMouseY);
        if (sidebarClick.getKind() == ClickGuiSidebar.ClickResult.Kind.CATEGORY) {
            selectCategory(sidebarClick.getCategory());
            return;
        }
        if (sidebarClick.getKind() == ClickGuiSidebar.ClickResult.Kind.SETTINGS) {
            selectSettings();
            return;
        }
        if (sidebarClick.getKind() == ClickGuiSidebar.ClickResult.Kind.PROFILE_TRIGGERED) {
            HoptimizationMod.LOGGER.debug("Profile switcher clicked (dropdown scheduled for Step 5).");
            return;
        }

        float contentX = windowX + ClickGuiSidebar.WIDTH;
        float contentY = windowY + CONTENT_MARGIN;
        float contentW = WINDOW_WIDTH - ClickGuiSidebar.WIDTH - CONTENT_MARGIN;
        int segmentHit = contentHeader.handleClick(contentX, contentY, contentW, lMouseX, lMouseY, settingsViewActive);
        if (segmentHit == 0) {
            filterEnabledOnly = false;
        } else if (segmentHit == 1) {
            filterEnabledOnly = true;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (!dragging || clickedMouseButton != 0 || this.mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        float uiScale = UiScale.getScale();
        float lMouseX = resolveLogicalMouseX(mouseX, sr, uiScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, uiScale);
        windowX = lMouseX - dragOffsetX;
        windowY = lMouseY - dragOffsetY;
        clampWindowPosition(UiScale.getLogicalWidth(), UiScale.getLogicalHeight());
        positionModified = true;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && dragging) {
            finishDragging();
        }
    }

    /**
     * 触发平滑关闭动画（时长为打开的 0.75×），动画结束后关闭屏幕。
     */
    public void beginClose() {
        if (closing) {
            return;
        }
        closing = true;
        dragging = false;
        if (Animation.isReduceMotion() && this.mc != null) {
            this.mc.displayGuiScreen(null);
            return;
        }
        long closeMillis = Math.round(UiTokens.DURATION_SLOW_MS * UiTokens.CLOSE_DURATION_MULTIPLIER);
        openCloseAnim.setTarget(0.0f, closeMillis);
    }

    private void renderWindowContents(float mouseX, float mouseY, float windowAlpha, ThemeData theme) {
        // 主窗口阴影（offsetY=16, blur=48）+ 窗口底板 surface0 + 外沿 1px border
        RenderUtil.drawRoundedRectWithShadow(
            windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT,
            theme.radiusWindow(),
            RenderUtil.withAlpha(theme.surface0(), windowAlpha),
            RenderUtil.withAlpha(theme.border(), windowAlpha),
            UiTokens.BORDER_WIDTH,
            0.0f,
            UiTokens.SHADOW_WINDOW_OFFSET_Y,
            UiTokens.SHADOW_WINDOW_BLUR,
            RenderUtil.withAlpha(theme.windowShadowColor(), windowAlpha)
        );

        // 右侧嵌入式内容面板（距窗口上/右/下各 4，radius 8，surface1）
        float contentX = windowX + ClickGuiSidebar.WIDTH;
        float contentY = windowY + CONTENT_MARGIN;
        float contentW = WINDOW_WIDTH - ClickGuiSidebar.WIDTH - CONTENT_MARGIN;
        float contentH = WINDOW_HEIGHT - CONTENT_MARGIN * 2.0f;
        float contentRadius = theme.nestedRadius(theme.radiusWindow(), CONTENT_MARGIN);
        int contentBorder = theme.style().isCardBorder() ? RenderUtil.withAlpha(theme.border(), windowAlpha) : 0;

        RenderUtil.drawRoundedRect(
            contentX, contentY, contentW, contentH,
            contentRadius,
            RenderUtil.withAlpha(theme.surface1(), windowAlpha),
            contentBorder,
            contentBorder != 0 ? UiTokens.BORDER_WIDTH : 0.0f
        );

        ModuleManager moduleManager = HoptimizationMod.getModuleManager();
        ConfigManager configManager = HoptimizationMod.getConfigManager();

        sidebar.render(
            windowX, windowY, WINDOW_HEIGHT,
            mouseX, mouseY,
            windowAlpha, theme,
            activeCategory, settingsViewActive,
            moduleManager, configManager
        );

        int headerCount = countHeaderModules(moduleManager);
        contentHeader.render(
            contentX, contentY, contentW,
            mouseX, mouseY,
            windowAlpha, theme,
            activeCategory, settingsViewActive,
            headerCount, filterEnabledOnly
        );

        // 卡片列表区先留空，建立按变换矩阵对齐的裁剪视口供第 4 步填充模块卡片
        float listY = contentY + ClickGuiContentHeader.HEIGHT;
        float listH = Math.max(0.0f, contentH - ClickGuiContentHeader.HEIGHT - LIST_BOTTOM_PADDING);
        if (listH > 0.0f) {
            ScissorStack.pushTransformed(contentX, listY, contentW, listH);
            ScissorStack.pop();
        }
    }

    private int countHeaderModules(ModuleManager moduleManager) {
        if (moduleManager == null || settingsViewActive) {
            return 0;
        }
        List<Module> list = moduleManager.getModules(activeCategory);
        if (!filterEnabledOnly) {
            return list.size();
        }
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isEnabled()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 切换到指定模块分类并播放侧边栏指示条滑动动画。
     *
     * @param category 目标分类
     */
    public void selectCategory(Category category) {
        if (category == null) {
            return;
        }
        boolean changed = settingsViewActive || activeCategory != category;
        settingsViewActive = false;
        activeCategory = category;
        if (changed) {
            sidebar.animateSelectionTo(activeCategory, false, WINDOW_HEIGHT);
            ConfigManager configManager = HoptimizationMod.getConfigManager();
            if (configManager != null) {
                configManager.getUiConfig().setLastCategory(category);
                configManager.saveUiConfigDebounced();
            }
        }
    }

    /**
     * 切换到设置页并播放侧边栏指示条滑动动画。
     */
    public void selectSettings() {
        if (!settingsViewActive) {
            settingsViewActive = true;
            sidebar.animateSelectionTo(activeCategory, true, WINDOW_HEIGHT);
        }
    }

    /**
     * 切换内容头部“全部 | 已启用”筛选状态。
     *
     * @param enabledOnly {@code true} 仅显示已启用模块
     */
    public void setFilterEnabledOnly(boolean enabledOnly) {
        this.filterEnabledOnly = enabledOnly;
    }

    private void finishDragging() {
        dragging = false;
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager != null && positionModified) {
            configManager.getUiConfig().setWindowPosition(windowX, windowY);
            configManager.saveUiConfigDebounced();
        }
    }

    private void clampWindowPosition(float logicalW, float logicalH) {
        float margin = logicalW > WINDOW_WIDTH + UiTokens.SPACE_16 ? UiTokens.SPACE_8 : 0.0f;
        float maxX = Math.max(margin, logicalW - WINDOW_WIDTH - margin);
        float maxY = Math.max(margin, logicalH - WINDOW_HEIGHT - margin);
        if (windowX < margin) {
            windowX = margin;
        } else if (windowX > maxX) {
            windowX = maxX;
        }
        if (windowY < margin) {
            windowY = margin;
        } else if (windowY > maxY) {
            windowY = maxY;
        }
        windowX = Math.round(windowX);
        windowY = Math.round(windowY);
    }

    private static boolean hasSavedWindowPosition() {
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        return configManager != null && configManager.getUiConfig().isWindowPositionSet();
    }

    private static int getOpenKeybind() {
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager != null) {
            return configManager.getUiConfig().getClickGuiKeybind();
        }
        return Keyboard.KEY_RSHIFT;
    }

    private static float resolveLogicalMouseX(int mcMouseX, ScaledResolution sr, float uiScale) {
        if (Mouse.isCreated()) {
            return Mouse.getX() / uiScale;
        }
        return UiScale.mcScaledToLogicalX(mcMouseX, sr.getScaledWidth_double());
    }

    private static float resolveLogicalMouseY(int mcMouseY, ScaledResolution sr, float uiScale) {
        if (Mouse.isCreated()) {
            return UiScale.physicalMouseYToLogical(Mouse.getY());
        }
        return UiScale.mcScaledToLogicalY(mcMouseY, sr.getScaledHeight_double());
    }

    private static boolean isInside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
}
