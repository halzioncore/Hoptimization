package com.hoptimization.ui.clickgui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.modules.ui.ClickGuiModule;
import com.hoptimization.modules.visual.Theme;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.component.PopupManager;
import com.hoptimization.ui.component.TextFieldComponent;
import com.hoptimization.ui.component.TooltipManager;
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
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ClickGUI 主窗口屏幕（第 4 步：模块卡片列表、全套设置控件、平滑展开、搜索与滚动裁剪）。
 * <p>
 * 负责打开/关闭缓动动画、侧边栏导航与搜索框、内容区头部与“全部 | 已启用”筛选、
 * 品牌区窗口拖动、80ms 指数插值滚动与 12px 边缘渐隐遮罩、3px 滚动条、顶层弹出层/提示渲染
 * 以及分级 Esc 交互；会话内记忆各分类滚动位置与已展开卡片。
 */
public class ClickGuiScreen extends GuiScreen {
    public static final float WINDOW_WIDTH = 720.0f;
    public static final float WINDOW_HEIGHT = 440.0f;

    private static final float CONTENT_MARGIN = UiTokens.SPACE_4;
    private static final float LIST_VIEWPORT_BOTTOM_INSET = UiTokens.SPACE_4;
    private static final float LIST_BOTTOM_SCROLL_PADDING = UiTokens.SPACE_16;
    private static final float FADE_MASK_HEIGHT = 12.0f;
    private static final float SCROLLBAR_WIDTH = 3.0f;
    private static final float SCROLLBAR_HIT_HALF_W = 6.0f;
    private static final float SCROLLBAR_MIN_THUMB_H = 20.0f;
    private static final float SCROLL_PER_NOTCH = 36.0f;
    private static final double SCROLL_TIME_CONSTANT_MS = 80.0D;
    private static final long SCROLLBAR_IDLE_NANOS = 800_000_000L;
    private static final float EMPTY_ICON_SIZE = 32.0f;
    private static final float OPEN_SCALE_MIN = 0.96f;

    private static final Category[] CATEGORIES = Category.values();
    private static final float[] SESSION_CATEGORY_SCROLL = new float[CATEGORIES.length];
    private static final Set<String> SESSION_EXPANDED_IDS = new HashSet<String>();

    static {
        SESSION_EXPANDED_IDS.add(Theme.ID);
    }

    private final ClickGuiSidebar sidebar = new ClickGuiSidebar();
    private final ClickGuiContentHeader contentHeader = new ClickGuiContentHeader();
    private final PopupManager popupManager = new PopupManager();
    private final TooltipManager tooltipManager = new TooltipManager();
    private final ClickGuiSettingsPanel settingsPanel;
    private final ProfilePopupOverlay profilePopup;

    private final Animation openCloseAnim = new Animation(0.0f, 1.0f, AnimDuration.SLOW, Easing.EASE_OUT_QUINT);
    private final Animation scrollbarAlphaAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);

    private final List<ModuleCard> allCards = new ArrayList<ModuleCard>();
    private final Map<Module, ModuleCard> cardByModule = new HashMap<Module, ModuleCard>();
    @SuppressWarnings("unchecked")
    private final List<ModuleCard>[] cardsByCategory = new List[CATEGORIES.length];
    private final GroupHeaderSlot[] groupSlots = new GroupHeaderSlot[CATEGORIES.length];
    private final List<ModuleCard> activeRenderCards = new ArrayList<ModuleCard>();

    private Category activeCategory = Category.COMBAT;
    private boolean settingsViewActive = false;
    private boolean filterEnabledOnly = false;
    private boolean closing = false;
    private boolean displayListDirty = true;
    private long lastEnabledMask = 0L;

    private float targetScroll = 0.0f;
    private float currentScroll = 0.0f;
    private float maxScroll = 0.0f;
    private float searchScrollTarget = 0.0f;
    private long lastFrameNanos = 0L;
    private long lastScrollActivityNanos = 0L;

    private boolean draggingScrollbar = false;
    private float scrollbarDragGrabOffset = 0.0f;
    private float cachedSbTrackY = 0.0f;
    private float cachedSbTrackH = 0.0f;
    private float cachedSbThumbH = 0.0f;
    private float cachedSbThumbY = 0.0f;
    private float cachedSbX = 0.0f;

    private ModuleCard activeMouseCard = null;
    private String emptySearchLabel = "";

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
    private int lastLanguageRevision = LanguageModule.getLanguageRevision();
    private String lastEffectiveLanguageCode = LanguageModule.getEffectiveLanguageCode();

    /**
     * 构造 ClickGUI 屏幕并预分配分类容器与搜索监听器。
     */
    public ClickGuiScreen() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            this.cardsByCategory[i] = new ArrayList<ModuleCard>();
            this.groupSlots[i] = new GroupHeaderSlot(CATEGORIES[i]);
        }
        this.settingsPanel = new ClickGuiSettingsPanel(this, this.popupManager);
        this.profilePopup = new ProfilePopupOverlay(this.popupManager, new Runnable() {
            @Override
            public void run() {
                onProfileChanged();
            }
        });
        this.sidebar.setSearchChangeListener(new TextFieldComponent.TextChangeListener() {
            @Override
            public void onTextChanged(String newText) {
                onSearchQueryChanged(newText);
            }
        });
    }

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

        float uiScale = resolveClickGuiScale();
        lastUiScale = uiScale;
        FontManager.setUiScale(uiScale);

        ensureCardsInitialized();
        refreshAllLabels();
        lastLanguageRevision = LanguageModule.getLanguageRevision();
        lastEffectiveLanguageCode = LanguageModule.getEffectiveLanguageCode();

        float logicalW = UiScale.getLogicalWidth(uiScale);
        float logicalH = UiScale.getLogicalHeight(uiScale);
        lastLogicalW = logicalW;
        lastLogicalH = logicalH;
        popupManager.setViewport(0.0f, 0.0f, logicalW, logicalH);
        tooltipManager.setViewport(0.0f, 0.0f, logicalW, logicalH);

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

        float restoredScroll = SESSION_CATEGORY_SCROLL[activeCategory.ordinal()];
        targetScroll = Math.max(0.0f, restoredScroll);
        currentScroll = targetScroll;
        lastFrameNanos = System.nanoTime();
        lastScrollActivityNanos = 0L;
        draggingScrollbar = false;
        activeMouseCard = null;
        displayListDirty = true;

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
        draggingScrollbar = false;
        activeMouseCard = null;

        sidebar.unfocusSearch();
        Keyboard.enableRepeatEvents(false);
        cancelAllKeybindCaptures();
        cancelAllInlineNumberEdits();
        popupManager.closeActivePopup();
        tooltipManager.clear();

        saveCurrentScrollToSession();
        saveExpandedStateToSession();

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        if (configManager != null) {
            UiConfig uiConfig = configManager.getUiConfig();
            if (positionModified || uiConfig.isWindowPositionSet()) {
                uiConfig.setWindowPosition(windowX, windowY);
            }
            uiConfig.setLastCategory(activeCategory);
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
        if (closing && openCloseAnim.isFinished()) {
            mc.displayGuiScreen(null);
            return;
        }

        int languageRevision = LanguageModule.getLanguageRevision();
        String effectiveLanguageCode = LanguageModule.getEffectiveLanguageCode();
        boolean needsLabelRefresh = false;
        if (languageRevision != this.lastLanguageRevision) {
            this.lastLanguageRevision = languageRevision;
            needsLabelRefresh = true;
        }
        if (!effectiveLanguageCode.equals(this.lastEffectiveLanguageCode)) {
            this.lastEffectiveLanguageCode = effectiveLanguageCode;
            needsLabelRefresh = true;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        float uiScale = resolveClickGuiScale();
        if (Math.abs(uiScale - lastUiScale) > 0.0001f) {
            lastUiScale = uiScale;
            FontManager.setUiScale(uiScale);
            needsLabelRefresh = true;
        }
        if (needsLabelRefresh) {
            refreshAllLabels();
        }

        float logicalW = UiScale.getLogicalWidth(uiScale);
        float logicalH = UiScale.getLogicalHeight(uiScale);
        if (Math.abs(logicalW - lastLogicalW) > 0.5f || Math.abs(logicalH - lastLogicalH) > 0.5f) {
            lastLogicalW = logicalW;
            lastLogicalH = logicalH;
            popupManager.setViewport(0.0f, 0.0f, logicalW, logicalH);
            tooltipManager.setViewport(0.0f, 0.0f, logicalW, logicalH);
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

        if (draggingScrollbar) {
            if (!Mouse.isButtonDown(0)) {
                draggingScrollbar = false;
            } else {
                updateScrollFromScrollbarMouseY(lMouseY);
            }
        }

        float progress = openCloseAnim.get();
        if (progress < 0.0f) {
            progress = 0.0f;
        } else if (progress > 1.0f) {
            progress = 1.0f;
        }

        ThemeData theme = ThemeManager.current();
        boolean frosted = ThemeManager.isFrostedGlass();
        if (frosted) {
            BackdropBlurRenderer.captureAndBlur(ThemeManager.getBlurStrength());
        }
        float windowAlpha = progress * theme.style().getWindowOpacity();
        float animScale = OPEN_SCALE_MIN + (1.0f - OPEN_SCALE_MIN) * progress;
        float scaleRatio = uiScale / (float) sr.getScaleFactor();

        GlStateManager.pushMatrix();
        GlStateManager.scale(scaleRatio, scaleRatio, 1.0f);
        try {
            // 背后游戏画面遮罩 backdrop（毛玻璃模式下减轻压暗，让模糊背景透出层次）
            float backdropAlpha = frosted ? (progress * 0.45f) : progress;
            RenderUtil.drawRect(0.0f, 0.0f, logicalW, logicalH, RenderUtil.withAlpha(theme.backdrop(), backdropAlpha));

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
        if (closing) {
            return;
        }
        boolean ctrl = isCtrlKeyDown();

        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (sidebar.isSearchFocused()) {
                if (sidebar.hasSearchQuery()) {
                    sidebar.clearSearch();
                }
                sidebar.unfocusSearch();
                return;
            }
            if (hasActiveKeybindCapture()) {
                cancelAllKeybindCaptures();
                return;
            }
            if (popupManager.hasActivePopup()) {
                popupManager.closeActivePopup();
                return;
            }
            if (hasActiveInlineNumberEdit()) {
                cancelAllInlineNumberEdits();
                return;
            }
            if (sidebar.hasSearchQuery()) {
                sidebar.clearSearch();
                return;
            }
            beginClose();
            return;
        }

        if (hasActiveKeybindCapture()) {
            dispatchKeyToCapturingCard(typedChar, keyCode);
            return;
        }

        if (popupManager.hasActivePopup() && popupManager.keyTyped(typedChar, keyCode)) {
            return;
        }

        if (hasActiveInlineNumberEdit() && dispatchKeyToEditingCard(typedChar, keyCode)) {
            return;
        }

        if (!popupManager.hasActivePopup() && ctrl && keyCode == Keyboard.KEY_F) {
            sidebar.focusSearch(true);
            return;
        }

        if (sidebar.isSearchFocused()) {
            sidebar.handleSearchKeyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == getOpenKeybind()) {
            beginClose();
            return;
        }

        if (!popupManager.hasActivePopup() && !ctrl && ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
            sidebar.focusSearch(false);
            sidebar.handleSearchKeyTyped(typedChar, keyCode);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || closing || this.mc == null) {
            return;
        }
        if (isCtrlKeyDown()) {
            adjustClickGuiScale(wheel > 0 ? 1 : -1);
            return;
        }

        ScaledResolution sr = new ScaledResolution(this.mc);
        float uiScale = resolveClickGuiScale();
        float lMouseX = resolveLogicalMouseX(Mouse.getEventX(), sr, uiScale);
        float lMouseY = resolveLogicalMouseY(Mouse.getEventY(), sr, uiScale);

        if (popupManager.hasActivePopup()) {
            if (popupManager.getActivePopup().isPointInsideOverlay(lMouseX, lMouseY)) {
                popupManager.mouseScrolled(lMouseX, lMouseY, wheel);
                return;
            }
            popupManager.closeActivePopup();
        }

        float contentX = windowX + ClickGuiSidebar.WIDTH;
        float contentY = windowY + CONTENT_MARGIN;
        float contentW = WINDOW_WIDTH - ClickGuiSidebar.WIDTH - CONTENT_MARGIN;
        float contentH = WINDOW_HEIGHT - CONTENT_MARGIN * 2.0f;
        float listY = contentY + ClickGuiContentHeader.HEIGHT;
        float listH = Math.max(0.0f, contentH - ClickGuiContentHeader.HEIGHT - LIST_VIEWPORT_BOTTOM_INSET);

        if (isSearchActive() || !settingsViewActive) {
            if (maxScroll > 0.0f && isInside(lMouseX, lMouseY, contentX, listY, contentW, listH)) {
                float delta = -(wheel / 120.0f) * SCROLL_PER_NOTCH;
                targetScroll = clamp(targetScroll + delta, 0.0f, maxScroll);
                lastScrollActivityNanos = System.nanoTime();
                saveCurrentScrollToSession();
            }
        } else if (isInside(lMouseX, lMouseY, contentX, listY, contentW, listH)) {
            settingsPanel.mouseScrolled(wheel);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (closing || this.mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        float uiScale = resolveClickGuiScale();
        float lMouseX = resolveLogicalMouseX(mouseX, sr, uiScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, uiScale);

        if (popupManager.hasActivePopup()) {
            popupManager.mouseClicked(lMouseX, lMouseY, mouseButton);
            sidebar.unfocusSearch();
            cancelAllKeybindCaptures();
            return;
        }

        boolean searchHit = sidebar.handleSearchMouseClicked(lMouseX, lMouseY, mouseButton);
        if (searchHit) {
            cancelAllKeybindCaptures();
            cancelAllInlineNumberEdits();
            return;
        }

        if (!isInside(lMouseX, lMouseY, windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT)) {
            cancelAllKeybindCaptures();
            cancelAllInlineNumberEdits();
            return;
        }

        float localX = lMouseX - windowX;
        float localY = lMouseY - windowY;
        if (mouseButton == 0 && sidebar.isInBrandDragArea(localX, localY)) {
            cancelAllKeybindCaptures();
            cancelAllInlineNumberEdits();
            dragging = true;
            dragOffsetX = localX;
            dragOffsetY = localY;
            return;
        }

        if (mouseButton == 0) {
            ClickGuiSidebar.ClickResult sidebarClick =
                sidebar.handleClick(windowX, windowY, WINDOW_HEIGHT, lMouseX, lMouseY);
            if (sidebarClick.getKind() == ClickGuiSidebar.ClickResult.Kind.CATEGORY) {
                cancelAllKeybindCaptures();
                cancelAllInlineNumberEdits();
                if (sidebar.hasSearchQuery()) {
                    sidebar.clearSearch();
                }
                selectCategory(sidebarClick.getCategory());
                return;
            }
            if (sidebarClick.getKind() == ClickGuiSidebar.ClickResult.Kind.SETTINGS) {
                cancelAllKeybindCaptures();
                cancelAllInlineNumberEdits();
                if (sidebar.hasSearchQuery()) {
                    sidebar.clearSearch();
                }
                selectSettings();
                return;
            }
            if (sidebarClick.getKind() == ClickGuiSidebar.ClickResult.Kind.PROFILE_TRIGGERED) {
                cancelAllKeybindCaptures();
                cancelAllInlineNumberEdits();
                float triggerX = windowX + UiTokens.SPACE_8;
                float triggerY = windowY + sidebar.getProfileLocalY();
                float triggerW = ClickGuiSidebar.WIDTH - UiTokens.SPACE_8 * 2.0f;
                profilePopup.toggleAt(triggerX, triggerY, triggerW, 32.0f);
                return;
            }
        }

        float contentX = windowX + ClickGuiSidebar.WIDTH;
        float contentY = windowY + CONTENT_MARGIN;
        float contentW = WINDOW_WIDTH - ClickGuiSidebar.WIDTH - CONTENT_MARGIN;
        float contentH = WINDOW_HEIGHT - CONTENT_MARGIN * 2.0f;

        boolean searchActive = isSearchActive();
        if (mouseButton == 0) {
            int segmentHit = contentHeader.handleClick(
                contentX, contentY, contentW,
                lMouseX, lMouseY,
                settingsViewActive && !searchActive
            );
            if (segmentHit == 0 || segmentHit == 1) {
                cancelAllKeybindCaptures();
                cancelAllInlineNumberEdits();
                setFilterEnabledOnly(segmentHit == 1);
                return;
            }
        }

        if (!searchActive && settingsViewActive) {
            float listY = contentY + ClickGuiContentHeader.HEIGHT;
            float listH = Math.max(0.0f, contentH - ClickGuiContentHeader.HEIGHT - LIST_VIEWPORT_BOTTOM_INSET);
            settingsPanel.mouseClicked(lMouseX, lMouseY, mouseButton, contentX, listY, contentW, listH);
            return;
        }

        float listY = contentY + ClickGuiContentHeader.HEIGHT;
        float listH = Math.max(0.0f, contentH - ClickGuiContentHeader.HEIGHT - LIST_VIEWPORT_BOTTOM_INSET);

        if (mouseButton == 0 && maxScroll > 0.5f && cachedSbTrackH > 0.0f
            && isInside(lMouseX, lMouseY,
                cachedSbX - SCROLLBAR_HIT_HALF_W, cachedSbTrackY,
                SCROLLBAR_WIDTH + SCROLLBAR_HIT_HALF_W * 2.0f, cachedSbTrackH)) {
            cancelAllKeybindCaptures();
            cancelAllInlineNumberEdits();
            draggingScrollbar = true;
            lastScrollActivityNanos = System.nanoTime();
            if (lMouseY >= cachedSbThumbY && lMouseY <= cachedSbThumbY + cachedSbThumbH) {
                scrollbarDragGrabOffset = lMouseY - cachedSbThumbY;
            } else {
                scrollbarDragGrabOffset = cachedSbThumbH * 0.5f;
                updateScrollFromScrollbarMouseY(lMouseY);
            }
            return;
        }

        if (!isInside(lMouseX, lMouseY, contentX, listY, contentW, listH)) {
            cancelAllKeybindCaptures();
            cancelAllInlineNumberEdits();
            return;
        }

        ModuleCard hitCard = null;
        for (int i = 0; i < activeRenderCards.size(); i++) {
            ModuleCard card = activeRenderCards.get(i);
            float cardTop = card.getLayoutY() - currentScroll;
            float cardH = card.getHeight();
            if (cardTop + cardH < listY || cardTop > listY + listH) {
                continue;
            }
            if (card.mouseClicked(lMouseX, lMouseY, mouseButton)) {
                hitCard = card;
                break;
            }
        }

        this.activeMouseCard = hitCard;
        for (int i = 0; i < this.allCards.size(); i++) {
            ModuleCard card = this.allCards.get(i);
            if (card != hitCard) {
                card.cancelAllInputCaptures();
            }
        }
        if (hitCard != null) {
            saveExpandedStateToSession();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (closing || this.mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        float uiScale = resolveClickGuiScale();
        float lMouseX = resolveLogicalMouseX(mouseX, sr, uiScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, uiScale);

        if (dragging && clickedMouseButton == 0) {
            windowX = lMouseX - dragOffsetX;
            windowY = lMouseY - dragOffsetY;
            clampWindowPosition(UiScale.getLogicalWidth(uiScale), UiScale.getLogicalHeight(uiScale));
            positionModified = true;
            return;
        }

        if (popupManager.hasActivePopup()) {
            popupManager.mouseDragged(lMouseX, lMouseY, clickedMouseButton);
            return;
        }

        if (draggingScrollbar && clickedMouseButton == 0) {
            updateScrollFromScrollbarMouseY(lMouseY);
            return;
        }

        if (sidebar.handleSearchMouseDragged(lMouseX, lMouseY, clickedMouseButton)) {
            return;
        }

        if (!isSearchActive() && settingsViewActive) {
            settingsPanel.mouseDragged(lMouseX, lMouseY, clickedMouseButton);
            return;
        }

        if (activeMouseCard != null) {
            activeMouseCard.mouseDragged(lMouseX, lMouseY, clickedMouseButton);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && dragging) {
            finishDragging();
        }
        if (state == 0 && draggingScrollbar) {
            draggingScrollbar = false;
        }
        if (this.mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        float uiScale = resolveClickGuiScale();
        float lMouseX = resolveLogicalMouseX(mouseX, sr, uiScale);
        float lMouseY = resolveLogicalMouseY(mouseY, sr, uiScale);

        if (popupManager.hasActivePopup()) {
            popupManager.mouseReleased(lMouseX, lMouseY, state);
        }

        sidebar.handleSearchMouseReleased(lMouseX, lMouseY, state);
        settingsPanel.mouseReleased(lMouseX, lMouseY, state);

        if (activeMouseCard != null) {
            activeMouseCard.mouseReleased(lMouseX, lMouseY, state);
            if (state == 0) {
                activeMouseCard = null;
            }
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
        draggingScrollbar = false;
        activeMouseCard = null;
        sidebar.unfocusSearch();
        cancelAllKeybindCaptures();
        cancelAllInlineNumberEdits();
        popupManager.closeActivePopup();
        tooltipManager.clear();

        if (Animation.isReduceMotion() && this.mc != null) {
            this.mc.displayGuiScreen(null);
            return;
        }
        long closeMillis = Math.round(UiTokens.DURATION_SLOW_MS * UiTokens.CLOSE_DURATION_MULTIPLIER);
        openCloseAnim.setTarget(0.0f, closeMillis);
    }

    private void renderWindowContents(float mouseX, float mouseY, float windowAlpha, ThemeData theme) {
        tooltipManager.beginFrame();

        boolean frosted = ThemeManager.isFrostedGlass();

        // 主窗口阴影（offsetY=16, blur=48）+ 窗口底板 surface0 + 外沿 1px border
        if (frosted) {
            RenderUtil.drawFrostedRoundedRectWithShadow(
                windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT,
                theme.radiusWindow(),
                theme.frostedWindowTint(),
                theme.frostedBorder(),
                UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength(),
                windowAlpha,
                0.0f,
                UiTokens.SHADOW_WINDOW_OFFSET_Y,
                UiTokens.SHADOW_WINDOW_BLUR,
                theme.windowShadowColor()
            );
        } else {
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
        }

        // 右侧嵌入式内容面板：标准模式使用 surface1 + 边框；毛玻璃模式使用轻透叠加与左侧竖向发丝分隔线保持通透一体感
        float contentX = windowX + ClickGuiSidebar.WIDTH;
        float contentY = windowY + CONTENT_MARGIN;
        float contentW = WINDOW_WIDTH - ClickGuiSidebar.WIDTH - CONTENT_MARGIN;
        float contentH = WINDOW_HEIGHT - CONTENT_MARGIN * 2.0f;
        float contentRadius = theme.nestedRadius(theme.radiusWindow(), CONTENT_MARGIN);
        if (frosted) {
            float panelAlpha = (theme.getMode() == ThemeMode.LIGHT ? 0.16f : 0.14f) * windowAlpha;
            RenderUtil.drawRoundedRect(
                contentX, contentY, contentW, contentH,
                contentRadius,
                RenderUtil.withAlpha(theme.surface1(), panelAlpha),
                0,
                0.0f
            );
            RenderUtil.drawRect(
                contentX,
                windowY + UiTokens.SPACE_12,
                UiTokens.BORDER_WIDTH,
                WINDOW_HEIGHT - UiTokens.SPACE_12 * 2.0f,
                RenderUtil.withAlpha(theme.frostedBorder(), 0.55f * windowAlpha)
            );
        } else {
            int contentBorder = theme.style().isCardBorder()
                ? RenderUtil.withAlpha(theme.border(), windowAlpha)
                : 0;
            RenderUtil.drawRoundedRect(
                contentX, contentY, contentW, contentH,
                contentRadius,
                RenderUtil.withAlpha(theme.surface1(), windowAlpha),
                contentBorder,
                contentBorder != 0 ? UiTokens.BORDER_WIDTH : 0.0f
            );
        }

        profilePopup.updateAnchor(
            windowX + UiTokens.SPACE_8,
            windowY + sidebar.getProfileLocalY(),
            ClickGuiSidebar.WIDTH - UiTokens.SPACE_8 * 2.0f,
            32.0f
        );

        ModuleManager moduleManager = HoptimizationMod.getModuleManager();
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        boolean popupOpen = popupManager.hasActivePopup();
        float bgMouseX = popupOpen ? -99999.0f : mouseX;
        float bgMouseY = popupOpen ? -99999.0f : mouseY;

        sidebar.render(
            windowX, windowY, WINDOW_HEIGHT,
            bgMouseX, bgMouseY,
            windowAlpha, theme,
            activeCategory, settingsViewActive,
            moduleManager, configManager
        );

        boolean searchActive = isSearchActive();
        rebuildDisplayListIfNeeded(searchActive);

        int headerCount = (searchActive || !settingsViewActive) ? activeRenderCards.size() : 0;
        contentHeader.render(
            contentX, contentY, contentW,
            bgMouseX, bgMouseY,
            windowAlpha, theme,
            activeCategory, settingsViewActive, searchActive,
            headerCount, filterEnabledOnly
        );

        float listX = contentX;
        float listY = contentY + ClickGuiContentHeader.HEIGHT;
        float listW = contentW;
        float listH = Math.max(0.0f, contentH - ClickGuiContentHeader.HEIGHT - LIST_VIEWPORT_BOTTOM_INSET);

        if ((searchActive || !settingsViewActive) && listH > 0.0f) {
            renderModuleListViewport(
                listX, listY, listW, listH,
                bgMouseX, bgMouseY, windowAlpha, theme, searchActive
            );
        } else if (settingsViewActive && listH > 0.0f) {
            settingsPanel.render(
                listX, listY, listW, listH,
                bgMouseX, bgMouseY, windowAlpha, theme, tooltipManager
            );
        }

        // 最顶层渲染下拉菜单 / 取色器与延迟悬停提示
        popupManager.render(mouseX, mouseY, windowAlpha, theme);
        tooltipManager.render(mouseX, mouseY, windowAlpha, theme);
    }

    private void renderModuleListViewport(
        float listX,
        float listY,
        float listW,
        float listH,
        float bgMouseX,
        float bgMouseY,
        float windowAlpha,
        ThemeData theme,
        boolean searchActive
    ) {
        float cardX = listX + UiTokens.SPACE_20;
        float cardW = Math.max(0.0f, listW - UiTokens.SPACE_20 * 2.0f);

        float totalContentH = updateListLayoutAndScroll(cardX, listY, cardW, listH, searchActive);

        boolean inListViewport = isInside(bgMouseX, bgMouseY, listX, listY, listW, listH);
        float listMouseX = inListViewport ? bgMouseX : -99999.0f;
        float listMouseY = inListViewport ? bgMouseY : -99999.0f;

        boolean pushedScissor = false;
        if (Display.isCreated()) {
            ScissorStack.pushTransformed(listX, listY, listW, listH);
            pushedScissor = true;
        }
        try {
            if (searchActive && activeRenderCards.isEmpty()) {
                renderSearchEmptyState(listX, listY, listW, listH, windowAlpha, theme);
                return;
            }

            if (searchActive) {
                TtfFontRenderer overlineFont = FontManager.overline();
                int groupHeaderColor = RenderUtil.withAlpha(theme.textTertiary(), windowAlpha);
                float overlineH = overlineFont.getHeight();
                for (int c = 0; c < groupSlots.length; c++) {
                    GroupHeaderSlot slot = groupSlots[c];
                    if (!slot.active) {
                        continue;
                    }
                    float headerY = slot.layoutY - currentScroll;
                    if (headerY + overlineH >= listY && headerY <= listY + listH) {
                        overlineFont.drawString(slot.label, cardX, headerY, groupHeaderColor);
                    }
                }
            }

            for (int i = 0; i < activeRenderCards.size(); i++) {
                ModuleCard card = activeRenderCards.get(i);
                float drawY = card.getLayoutY() - currentScroll;
                float cardH = card.getHeight();
                if (drawY + cardH < listY || drawY > listY + listH) {
                    continue;
                }
                card.render(cardX, drawY, cardW, listMouseX, listMouseY, windowAlpha, theme, tooltipManager);
            }

            // 12px 列表上下边缘渐隐遮罩（标准模式绘制，毛玻璃模式跳过避免出现不透明色条）
            if (!ThemeManager.isFrostedGlass()) {
                int maskSurface = RenderUtil.withAlpha(theme.surface1(), windowAlpha);
                if (currentScroll > 0.5f) {
                    RenderUtil.drawScrollFadeMask(cardX, listY, cardW, FADE_MASK_HEIGHT, maskSurface, true);
                }
                if (maxScroll > 0.5f && currentScroll < maxScroll - 0.5f) {
                    RenderUtil.drawScrollFadeMask(
                        cardX, listY + listH - FADE_MASK_HEIGHT, cardW, FADE_MASK_HEIGHT, maskSurface, false
                    );
                }
            }

            renderScrollbar(listX, listY, listW, listH, totalContentH, inListViewport, windowAlpha, theme);
        } finally {
            if (pushedScissor) {
                ScissorStack.pop();
            }
        }
    }

    private float updateListLayoutAndScroll(
        float cardX,
        float listY,
        float cardW,
        float listH,
        boolean searchActive
    ) {
        float curY = listY;
        if (searchActive) {
            boolean firstGroup = true;
            float groupHeaderH = FontManager.overline().getHeight() + UiTokens.SPACE_6;
            for (int c = 0; c < groupSlots.length; c++) {
                GroupHeaderSlot slot = groupSlots[c];
                if (!slot.active) {
                    continue;
                }
                if (!firstGroup) {
                    curY += UiTokens.GAP_GROUP;
                }
                firstGroup = false;
                slot.layoutY = curY;
                curY += groupHeaderH;

                for (int i = slot.startCardIndex; i < slot.endCardIndex; i++) {
                    ModuleCard card = activeRenderCards.get(i);
                    curY += card.updateLayout(cardX, curY, cardW);
                    if (i + 1 < slot.endCardIndex) {
                        curY += UiTokens.GAP_LIST_ITEM;
                    }
                }
            }
        } else {
            int count = activeRenderCards.size();
            for (int i = 0; i < count; i++) {
                ModuleCard card = activeRenderCards.get(i);
                curY += card.updateLayout(cardX, curY, cardW);
                if (i + 1 < count) {
                    curY += UiTokens.GAP_LIST_ITEM;
                }
            }
        }

        float totalContentH = activeRenderCards.isEmpty() ? 0.0f : ((curY - listY) + LIST_BOTTOM_SCROLL_PADDING);
        this.maxScroll = Math.max(0.0f, totalContentH - listH);
        this.targetScroll = clamp(this.targetScroll, 0.0f, this.maxScroll);

        long nowNanos = System.nanoTime();
        float dtMs = this.lastFrameNanos > 0L
            ? Math.min(100.0f, Math.max(0.0f, (nowNanos - this.lastFrameNanos) / 1_000_000.0f))
            : 16.67f;
        this.lastFrameNanos = nowNanos;

        if (Animation.isReduceMotion()) {
            this.currentScroll = this.targetScroll;
        } else {
            float factor = 1.0f - (float) Math.exp(-dtMs / SCROLL_TIME_CONSTANT_MS);
            this.currentScroll += (this.targetScroll - this.currentScroll) * factor;
            if (Math.abs(this.targetScroll - this.currentScroll) < 0.25f) {
                this.currentScroll = this.targetScroll;
            }
        }
        this.currentScroll = clamp(this.currentScroll, 0.0f, this.maxScroll);

        if (searchActive) {
            this.searchScrollTarget = this.targetScroll;
        } else if (!settingsViewActive && activeCategory != null) {
            SESSION_CATEGORY_SCROLL[activeCategory.ordinal()] = this.targetScroll;
        }
        return totalContentH;
    }

    private void renderScrollbar(
        float listX,
        float listY,
        float listW,
        float listH,
        float totalContentH,
        boolean listHovered,
        float windowAlpha,
        ThemeData theme
    ) {
        if (this.maxScroll <= 0.5f || totalContentH <= listH) {
            this.scrollbarAlphaAnim.snapTo(0.0f);
            this.cachedSbTrackH = 0.0f;
            return;
        }

        long nowNanos = System.nanoTime();
        boolean recentlyScrolled = (nowNanos - this.lastScrollActivityNanos) < SCROLLBAR_IDLE_NANOS;
        this.scrollbarAlphaAnim.setTarget((listHovered || recentlyScrolled || this.draggingScrollbar) ? 1.0f : 0.0f);
        float sbAlpha = clamp01(this.scrollbarAlphaAnim.get()) * windowAlpha;

        float sbX = listX + listW - UiTokens.SPACE_6 - SCROLLBAR_WIDTH;
        float sbTrackY = listY + UiTokens.SPACE_4;
        float sbTrackH = Math.max(0.0f, listH - UiTokens.SPACE_8);
        float thumbH = Math.min(sbTrackH, Math.max(SCROLLBAR_MIN_THUMB_H, sbTrackH * (listH / totalContentH)));
        float scrollRatio = this.maxScroll > 0.0f ? (this.currentScroll / this.maxScroll) : 0.0f;
        float thumbY = sbTrackY + (sbTrackH - thumbH) * clamp01(scrollRatio);

        this.cachedSbX = sbX;
        this.cachedSbTrackY = sbTrackY;
        this.cachedSbTrackH = sbTrackH;
        this.cachedSbThumbH = thumbH;
        this.cachedSbThumbY = thumbY;

        if (sbAlpha <= 0.005f) {
            return;
        }
        RenderUtil.drawRoundedRect(
            sbX,
            thumbY,
            SCROLLBAR_WIDTH,
            thumbH,
            theme.radiusPill(SCROLLBAR_WIDTH),
            RenderUtil.withAlpha(theme.scrollbar(), sbAlpha)
        );
    }

    private void renderSearchEmptyState(
        float listX,
        float listY,
        float listW,
        float listH,
        float windowAlpha,
        ThemeData theme
    ) {
        TtfFontRenderer bodyFont = FontManager.body();
        float gap = UiTokens.SPACE_12;
        float blockH = EMPTY_ICON_SIZE + gap + bodyFont.getHeight();
        float startY = listY + (listH - blockH) * 0.5f;
        float iconX = listX + (listW - EMPTY_ICON_SIZE) * 0.5f;

        RenderUtil.drawTintedIcon(
            ClickGuiIcons.SEARCH,
            iconX,
            startY,
            EMPTY_ICON_SIZE,
            RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
        );
        bodyFont.drawStringCentered(
            this.emptySearchLabel,
            listX + listW * 0.5f,
            startY + EMPTY_ICON_SIZE + gap,
            RenderUtil.withAlpha(theme.textSecondary(), windowAlpha)
        );
    }

    private void updateScrollFromScrollbarMouseY(float mouseY) {
        float travel = this.cachedSbTrackH - this.cachedSbThumbH;
        if (travel <= 0.0f || this.maxScroll <= 0.0f) {
            return;
        }
        float desiredThumbTop = mouseY - this.scrollbarDragGrabOffset;
        float ratio = clamp01((desiredThumbTop - this.cachedSbTrackY) / travel);
        this.targetScroll = ratio * this.maxScroll;
        this.currentScroll = this.targetScroll;
        this.lastScrollActivityNanos = System.nanoTime();
        saveCurrentScrollToSession();
    }

    private void ensureCardsInitialized() {
        ModuleManager moduleManager = HoptimizationMod.getModuleManager();
        if (moduleManager == null) {
            return;
        }
        List<Module> modules = moduleManager.getModules();
        if (this.allCards.size() == modules.size() && !this.allCards.isEmpty()) {
            return;
        }
        this.allCards.clear();
        this.cardByModule.clear();
        for (int c = 0; c < this.cardsByCategory.length; c++) {
            this.cardsByCategory[c].clear();
        }
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            ModuleCard card = new ModuleCard(module, this.popupManager);
            if (SESSION_EXPANDED_IDS.contains(module.getId())) {
                card.setExpanded(true, true);
            }
            this.allCards.add(card);
            this.cardByModule.put(module, card);
            this.cardsByCategory[module.getCategory().ordinal()].add(card);
        }
        this.displayListDirty = true;
    }

    /**
     * 刷新 ClickGUI 内所有缓存的本地化文案与控件尺寸。
     */
    public void refreshAllLabels() {
        this.sidebar.refreshLabels();
        this.contentHeader.refreshLabels();
        this.profilePopup.refreshLabels();
        this.settingsPanel.refreshLabelsAndState();
        this.emptySearchLabel = LanguageModule.format("ui.hoptimization.search.empty");
        for (int c = 0; c < CATEGORIES.length; c++) {
            this.groupSlots[c].label = LanguageModule.format(CATEGORIES[c].getNameKey());
        }
        for (int i = 0; i < this.allCards.size(); i++) {
            this.allCards.get(i).refreshLabels();
        }
        this.displayListDirty = true;
    }

    private void onSearchQueryChanged(String newText) {
        String normalized = newText == null ? "" : newText.trim();
        for (int i = 0; i < this.allCards.size(); i++) {
            this.allCards.get(i).updateSearchQuery(normalized);
        }
        if (normalized.isEmpty()) {
            float restored = this.settingsViewActive
                ? 0.0f
                : Math.max(0.0f, SESSION_CATEGORY_SCROLL[this.activeCategory.ordinal()]);
            this.targetScroll = restored;
            this.currentScroll = restored;
        } else {
            this.searchScrollTarget = 0.0f;
            this.targetScroll = 0.0f;
            this.currentScroll = 0.0f;
        }
        this.popupManager.closeActivePopup();
        cancelAllKeybindCaptures();
        cancelAllInlineNumberEdits();
        this.displayListDirty = true;
    }

    private void rebuildDisplayListIfNeeded(boolean searchActive) {
        long enabledSig = computeEnabledMask();
        if (this.filterEnabledOnly && enabledSig != this.lastEnabledMask) {
            this.displayListDirty = true;
        }
        if (!this.displayListDirty) {
            return;
        }
        this.displayListDirty = false;
        this.lastEnabledMask = enabledSig;
        this.activeRenderCards.clear();
        for (int c = 0; c < this.groupSlots.length; c++) {
            this.groupSlots[c].active = false;
        }

        if (searchActive) {
            for (int c = 0; c < CATEGORIES.length; c++) {
                List<ModuleCard> catCards = this.cardsByCategory[c];
                int startIdx = this.activeRenderCards.size();
                for (int i = 0; i < catCards.size(); i++) {
                    ModuleCard card = catCards.get(i);
                    if (card.matchesSearch() && (!this.filterEnabledOnly || card.getModule().isEnabled())) {
                        this.activeRenderCards.add(card);
                    }
                }
                int endIdx = this.activeRenderCards.size();
                if (endIdx > startIdx) {
                    this.groupSlots[c].active = true;
                    this.groupSlots[c].startCardIndex = startIdx;
                    this.groupSlots[c].endCardIndex = endIdx;
                }
            }
            return;
        }

        if (!this.settingsViewActive && this.activeCategory != null) {
            List<ModuleCard> catCards = this.cardsByCategory[this.activeCategory.ordinal()];
            for (int i = 0; i < catCards.size(); i++) {
                ModuleCard card = catCards.get(i);
                if (!this.filterEnabledOnly || card.getModule().isEnabled()) {
                    this.activeRenderCards.add(card);
                }
            }
        }
    }

    private long computeEnabledMask() {
        long sig = 17L;
        for (int i = 0; i < this.allCards.size(); i++) {
            if (this.allCards.get(i).getModule().isEnabled()) {
                sig = sig * 31L + (i + 1L);
            }
        }
        return sig;
    }

    private boolean isSearchActive() {
        return this.sidebar.hasSearchQuery();
    }

    /**
     * Returns whether the sidebar search box is actively focused for text or IME input.
     *
     * @return {@code true} when search is focused and not blocked by closing, popups, or capture states
     */
    public boolean isSearchFocused() {
        return !this.closing
            && this.sidebar.isSearchFocused()
            && !this.popupManager.hasActivePopup()
            && !hasActiveKeybindCapture()
            && !hasActiveInlineNumberEdit();
    }

    /**
     * Commits IME-composed text into the sidebar search box while focused.
     *
     * @param text committed text string
     * @return {@code true} if inserted into the focused search field
     */
    public boolean commitSearchText(String text) {
        if (!isSearchFocused()) {
            return false;
        }
        return this.sidebar.commitSearchText(text);
    }

    /**
     * Alias for {@link #commitSearchText(String)} exposed for IME input bridges.
     *
     * @param text committed text string
     * @return {@code true} if inserted into the focused search field
     */
    public boolean commitImeText(String text) {
        return commitSearchText(text);
    }

    /**
     * Dispatches a bridged special/shortcut key (such as Escape, Backspace, or Ctrl+A/C/V/X) to the search box.
     *
     * @param typedChar character value (or {@code '\0'})
     * @param keyCode   LWJGL key code
     * @param ctrlDown  whether Ctrl (or Cmd on macOS) is held
     * @param shiftDown whether Shift is held
     * @return {@code true} if handled
     */
    public boolean handleImeKeyTyped(char typedChar, int keyCode, boolean ctrlDown, boolean shiftDown) {
        if (!isSearchFocused()) {
            return false;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (this.sidebar.hasSearchQuery()) {
                this.sidebar.clearSearch();
            }
            this.sidebar.unfocusSearch();
            return true;
        }
        return this.sidebar.handleSearchKeyTyped(typedChar, keyCode, ctrlDown, shiftDown);
    }

    /**
     * Populates {@code outBounds} with {@code [boxX, boxY, boxW, boxH, caretX, clickGuiUiScale]} in
     * ClickGUI logical pixels without allocating heap objects.
     *
     * @param outBounds pre-allocated array of length at least 6
     * @return {@code true} if bounds were written
     */
    public boolean getSearchBoxLogicalBounds(float[] outBounds) {
        if (outBounds == null || outBounds.length < 6) {
            return false;
        }
        float uiScale = this.lastUiScale > 0.0f ? this.lastUiScale : resolveClickGuiScale();
        float boxW = this.sidebar.getSearchBoxWidth();
        float boxH = this.sidebar.getSearchBoxHeight();
        float boxX = boxW > 0.0f ? this.sidebar.getSearchBoxX() : (this.windowX + UiTokens.SPACE_12);
        float boxY = boxH > 0.0f ? this.sidebar.getSearchBoxY() : (this.windowY + ClickGuiSidebar.BRAND_HEIGHT);
        if (boxW <= 0.0f) {
            boxW = ClickGuiSidebar.WIDTH - UiTokens.SPACE_12 * 2.0f;
        }
        if (boxH <= 0.0f) {
            boxH = ClickGuiSidebar.SEARCH_HEIGHT;
        }
        float caretX = this.sidebar.getSearchBoxWidth() > 0.0f
            ? this.sidebar.getSearchCaretLogicalX()
            : (boxX + UiTokens.PADDING_CONTROL);
        outBounds[0] = boxX;
        outBounds[1] = boxY;
        outBounds[2] = boxW;
        outBounds[3] = boxH;
        outBounds[4] = caretX;
        outBounds[5] = uiScale;
        return true;
    }

    private boolean hasActiveKeybindCapture() {
        if (this.settingsPanel.isListeningKeybind()) {
            return true;
        }
        for (int i = 0; i < this.allCards.size(); i++) {
            if (this.allCards.get(i).isListeningKeybind()) {
                return true;
            }
        }
        return false;
    }

    private void cancelAllKeybindCaptures() {
        this.settingsPanel.cancelCaptures();
        for (int i = 0; i < this.allCards.size(); i++) {
            this.allCards.get(i).cancelKeybindListening();
        }
    }

    private void dispatchKeyToCapturingCard(char typedChar, int keyCode) {
        if (this.settingsPanel.isListeningKeybind()) {
            this.settingsPanel.keyTyped(typedChar, keyCode);
            return;
        }
        for (int i = 0; i < this.allCards.size(); i++) {
            ModuleCard card = this.allCards.get(i);
            if (card.isListeningKeybind()) {
                card.keyTyped(typedChar, keyCode);
                return;
            }
        }
    }

    private boolean hasActiveInlineNumberEdit() {
        if (this.settingsPanel.isEditingInlineNumber()) {
            return true;
        }
        for (int i = 0; i < this.allCards.size(); i++) {
            if (this.allCards.get(i).isEditingInlineNumber()) {
                return true;
            }
        }
        return false;
    }

    private void cancelAllInlineNumberEdits() {
        this.settingsPanel.cancelCaptures();
        for (int i = 0; i < this.allCards.size(); i++) {
            this.allCards.get(i).cancelInlineNumberEdit();
        }
    }

    private boolean dispatchKeyToEditingCard(char typedChar, int keyCode) {
        if (this.settingsPanel.isEditingInlineNumber()) {
            return this.settingsPanel.keyTyped(typedChar, keyCode);
        }
        for (int i = 0; i < this.allCards.size(); i++) {
            ModuleCard card = this.allCards.get(i);
            if (card.isEditingInlineNumber()) {
                return card.keyTyped(typedChar, keyCode);
            }
        }
        return false;
    }

    private void saveCurrentScrollToSession() {
        if (isSearchActive()) {
            this.searchScrollTarget = this.targetScroll;
        } else if (!this.settingsViewActive && this.activeCategory != null) {
            SESSION_CATEGORY_SCROLL[this.activeCategory.ordinal()] = this.targetScroll;
        }
    }

    private void saveExpandedStateToSession() {
        SESSION_EXPANDED_IDS.clear();
        for (int i = 0; i < this.allCards.size(); i++) {
            ModuleCard card = this.allCards.get(i);
            if (card.isExpanded()) {
                SESSION_EXPANDED_IDS.add(card.getModule().getId());
            }
        }
    }

    /**
     * 切换到指定模块分类并播放侧边栏指示条滑动动画，同时记忆并恢复各分类滚动偏移。
     *
     * @param category 目标分类
     */
    public void selectCategory(Category category) {
        if (category == null) {
            return;
        }
        saveCurrentScrollToSession();
        boolean changed = settingsViewActive || activeCategory != category;
        settingsViewActive = false;
        activeCategory = category;
        popupManager.closeActivePopup();

        targetScroll = Math.max(0.0f, SESSION_CATEGORY_SCROLL[category.ordinal()]);
        currentScroll = targetScroll;
        displayListDirty = true;

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
        saveCurrentScrollToSession();
        popupManager.closeActivePopup();
        settingsPanel.syncControlsFromConfig();
        if (!settingsViewActive) {
            settingsViewActive = true;
            displayListDirty = true;
            sidebar.animateSelectionTo(activeCategory, true, WINDOW_HEIGHT);
        }
    }

    /**
     * 切换内容头部“全部 | 已启用”筛选状态。
     *
     * @param enabledOnly {@code true} 仅显示已启用模块
     */
    public void setFilterEnabledOnly(boolean enabledOnly) {
        if (this.filterEnabledOnly != enabledOnly) {
            this.filterEnabledOnly = enabledOnly;
            this.popupManager.closeActivePopup();
            this.displayListDirty = true;
        }
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

    private void adjustClickGuiScale(int direction) {
        ModuleManager moduleManager = HoptimizationMod.getModuleManager();
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        ClickGuiModule clickGuiModule = moduleManager != null ? moduleManager.getModule(ClickGuiModule.class) : null;

        double baseScale;
        if (clickGuiModule != null) {
            baseScale = clickGuiModule.getScaleSetting().getDoubleValue();
        } else if (configManager != null) {
            baseScale = configManager.getUiConfig().getClickGuiScale();
        } else {
            baseScale = UiConfig.DEFAULT_CLICK_GUI_SCALE;
        }

        double candidate = baseScale + direction * ClickGuiModule.SCALE_STEP;
        applyClickGuiScaleMultiplier((float) candidate);
    }

    void applyClickGuiScaleMultiplier(float candidateMultiplier) {
        ModuleManager moduleManager = HoptimizationMod.getModuleManager();
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        ClickGuiModule clickGuiModule = moduleManager != null ? moduleManager.getModule(ClickGuiModule.class) : null;

        float nextMultiplier;
        if (clickGuiModule != null) {
            clickGuiModule.getScaleSetting().setSteppedValue(candidateMultiplier, false);
            nextMultiplier = clickGuiModule.getScaleSetting().getFloatValue();
        } else {
            double snapped = Math.round(candidateMultiplier / ClickGuiModule.SCALE_STEP) * ClickGuiModule.SCALE_STEP;
            if (snapped < UiConfig.MIN_CLICK_GUI_SCALE) {
                snapped = UiConfig.MIN_CLICK_GUI_SCALE;
            } else if (snapped > UiConfig.MAX_CLICK_GUI_SCALE) {
                snapped = UiConfig.MAX_CLICK_GUI_SCALE;
            }
            nextMultiplier = (float) snapped;
        }

        if (configManager != null) {
            configManager.getUiConfig().setClickGuiScale(nextMultiplier);
            configManager.saveUiConfigDebounced();
        }

        float resolvedUiScale = UiScale.getClickGuiScale(nextMultiplier);
        lastUiScale = resolvedUiScale;
        FontManager.setUiScale(resolvedUiScale);
        refreshAllLabels();

        float logicalW = UiScale.getLogicalWidth(resolvedUiScale);
        float logicalH = UiScale.getLogicalHeight(resolvedUiScale);
        lastLogicalW = logicalW;
        lastLogicalH = logicalH;
        popupManager.setViewport(0.0f, 0.0f, logicalW, logicalH);
        tooltipManager.setViewport(0.0f, 0.0f, logicalW, logicalH);
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

    private void onProfileChanged() {
        float resolvedUiScale = resolveClickGuiScale();
        lastUiScale = resolvedUiScale;
        FontManager.setUiScale(resolvedUiScale);
        float logicalW = UiScale.getLogicalWidth(resolvedUiScale);
        float logicalH = UiScale.getLogicalHeight(resolvedUiScale);
        lastLogicalW = logicalW;
        lastLogicalH = logicalH;
        popupManager.setViewport(0.0f, 0.0f, logicalW, logicalH);
        tooltipManager.setViewport(0.0f, 0.0f, logicalW, logicalH);
        clampWindowPosition(logicalW, logicalH);
        refreshAllLabels();
    }

    private static float resolveClickGuiScale() {
        ConfigManager configManager = HoptimizationMod.getConfigManager();
        float multiplier = configManager != null
            ? configManager.getUiConfig().getClickGuiScale()
            : UiConfig.DEFAULT_CLICK_GUI_SCALE;
        return UiScale.getClickGuiScale(multiplier);
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
        return UiScale.mcScaledToLogicalX(mcMouseX, sr.getScaledWidth_double(), uiScale);
    }

    private static float resolveLogicalMouseY(int mcMouseY, ScaledResolution sr, float uiScale) {
        if (Mouse.isCreated()) {
            return UiScale.physicalMouseYToLogical(Mouse.getY(), uiScale);
        }
        return UiScale.mcScaledToLogicalY(mcMouseY, sr.getScaledHeight_double(), uiScale);
    }

    private static boolean isInside(float px, float py, float x, float y, float w, float h) {
        return w > 0.0f && h > 0.0f && px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static float clamp(float value, float min, float max) {
        if (value <= min) {
            return min;
        }
        if (value >= max) {
            return max;
        }
        return value;
    }

    private static float clamp01(float value) {
        if (value <= 0.0f || Float.isNaN(value)) {
            return 0.0f;
        }
        if (value >= 1.0f) {
            return 1.0f;
        }
        return value;
    }

    private static final class GroupHeaderSlot {
        final Category category;
        String label = "";
        boolean active = false;
        float layoutY = 0.0f;
        int startCardIndex = 0;
        int endCardIndex = 0;

        GroupHeaderSlot(Category category) {
            this.category = category;
        }
    }
}
