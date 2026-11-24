package com.hoptimization.ui.clickgui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.modules.ui.ClickGuiModule;
import com.hoptimization.modules.visual.Theme;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.component.ColorPickerPopup;
import com.hoptimization.ui.component.DropdownComponent;
import com.hoptimization.ui.component.KeybindComponent;
import com.hoptimization.ui.component.PopupManager;
import com.hoptimization.ui.component.SegmentedComponent;
import com.hoptimization.ui.component.SliderComponent;
import com.hoptimization.ui.component.SwitchComponent;
import com.hoptimization.ui.component.TooltipManager;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.BuiltinThemes;
import com.hoptimization.ui.theme.DisplayStyle;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ClickGUI 右侧设置页完整视图：包含主题预览网格与材质切换、全局强调色预设与自定义取色、
 * 界面缩放与减少动效、ClickGUI 缩放与打开快捷键、HUD 布局编辑入口以及配置目录快捷打开。
 */
public final class ClickGuiSettingsPanel {
    private static final float CARD_GAP = UiTokens.SPACE_12;
    private static final float CARD_PAD = UiTokens.PADDING_CARD;
    private static final float ROW_HEIGHT = 28.0f;
    private static final float ROW_GAP = UiTokens.SPACE_8;
    private static final float CONTROL_WIDTH = 156.0f;
    private static final float PILL_BTN_HEIGHT = 22.0f;
    private static final float ACTION_BTN_HEIGHT = 24.0f;

    private static final int THEME_COLS = 3;
    private static final float THEME_GRID_GAP = UiTokens.SPACE_8;
    private static final float THEME_CARD_HEIGHT = 54.0f;

    private static final float SWATCH_SIZE = 22.0f;
    private static final float SWATCH_GAP = UiTokens.SPACE_8;

    private static final float BOTTOM_SCROLL_PADDING = UiTokens.SPACE_16;
    private static final float FADE_MASK_HEIGHT = 12.0f;
    private static final float SCROLLBAR_WIDTH = 3.0f;
    private static final float SCROLLBAR_HIT_HALF_W = 6.0f;
    private static final float SCROLLBAR_MIN_THUMB_H = 20.0f;
    private static final float SCROLL_PER_NOTCH = 36.0f;
    private static final double SCROLL_TIME_CONSTANT_MS = 80.0D;
    private static final long SCROLLBAR_IDLE_NANOS = 800_000_000L;
    private static final long STATUS_DURATION_NANOS = 2_200_000_000L;

    private static final List<String> UI_SCALE_LABELS = Arrays.asList(
        "50%", "75%", "100%", "125%", "150%", "200%"
    );

    private static final class ThemeSlot {
        ThemeData theme;
        String displayName;
        boolean builtin;
        final Animation hoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
        float x;
        float y;
        float w;
        float h;
    }

    private final ClickGuiScreen parentScreen;
    private final PopupManager popupManager;
    private final List<ThemeSlot> themeSlots = new ArrayList<ThemeSlot>();
    private final Animation[] accentSwatchHoverAnims =
        new Animation[BuiltinThemes.ACCENT_PRESETS.size()];

    private final Animation importHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation exportHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation themeFolderHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation followAccentHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation customAccentHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation editHudHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation openConfigHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation scrollbarAlphaAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);

    private SegmentedComponent displayStyleSegment;
    private SliderComponent blurStrengthSlider;
    private DropdownComponent uiScaleDropdown;
    private SwitchComponent reduceMotionSwitch;
    private SliderComponent clickGuiScaleSlider;
    private KeybindComponent openKeybindControl;
    private ColorPickerPopup customAccentPopup;

    private boolean controlsInitialized = false;
    private boolean syncingControls = false;
    private double pendingClickGuiScale = -1.0D;
    private boolean draggingClickGuiSlider = false;

    private float targetScroll = 0.0f;
    private float currentScroll = 0.0f;
    private float maxScroll = 0.0f;
    private long lastFrameNanos = 0L;
    private long lastScrollActivityNanos = 0L;

    private boolean draggingScrollbar = false;
    private float scrollbarDragGrabOffset = 0.0f;
    private float cachedSbX = 0.0f;
    private float cachedSbTrackY = 0.0f;
    private float cachedSbTrackH = 0.0f;
    private float cachedSbThumbY = 0.0f;
    private float cachedSbThumbH = 0.0f;

    // Cached interactive hit boxes in logical coordinates
    private float btnImportX, btnImportY, btnImportW;
    private float btnExportX, btnExportY, btnExportW;
    private float btnThemeFolderX, btnThemeFolderY, btnThemeFolderW;
    private float btnFollowAccentX, btnFollowAccentY, btnFollowAccentW;
    private float btnCustomAccentX, btnCustomAccentY, btnCustomAccentW;
    private float swatchesStartX, swatchesY;
    private float btnEditHudX, btnEditHudY, btnEditHudW;
    private float btnOpenConfigX, btnOpenConfigY, btnOpenConfigW;

    // Cached localized strings
    private String titleTheme = "";
    private String btnImportText = "";
    private String btnExportText = "";
    private String btnThemeFolderText = "";
    private String labelDisplayStyle = "";
    private String labelBlurStrength = "";
    private String titleAccent = "";
    private String btnFollowThemeText = "";
    private String btnCustomAccentText = "";
    private final String[] accentPresetNames = new String[BuiltinThemes.ACCENT_PRESETS.size()];
    private String titleAppearance = "";
    private String labelUiScale = "";
    private String labelReduceMotion = "";
    private String titleClickGui = "";
    private String labelClickGuiScale = "";
    private String labelOpenKey = "";
    private String labelHud = "";
    private String btnEditHudText = "";
    private String titleAbout = "";
    private String versionLineText = "";
    private String btnOpenConfigText = "";
    private String msgImported = "";
    private String msgExported = "";
    private String msgImportFailed = "";

    private String statusMessage = "";
    private boolean statusError = false;
    private long statusExpireNanos = 0L;

    /**
     * 构造 ClickGUI 设置页视图。
     *
     * @param parentScreen 所属 ClickGUI 主屏幕
     * @param popupManager 顶层弹出层管理器
     */
    public ClickGuiSettingsPanel(ClickGuiScreen parentScreen, PopupManager popupManager) {
        this.parentScreen = parentScreen;
        this.popupManager = popupManager;
        for (int i = 0; i < this.accentSwatchHoverAnims.length; i++) {
            this.accentSwatchHoverAnims[i] = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
        }
    }

    private void ensureControlsInitialized() {
        if (this.controlsInitialized) {
            return;
        }
        this.controlsInitialized = true;

        ModuleManager mm = HoptimizationMod.getModuleManager();
        Theme themeModule = mm != null ? mm.getModule(Theme.class) : null;
        ConfigManager cm = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = cm != null ? cm.getUiConfig() : null;

        if (themeModule != null) {
            this.displayStyleSegment = new SegmentedComponent(themeModule.getDisplayStyleSetting());
            this.blurStrengthSlider = new SliderComponent(themeModule.getBlurStrengthSetting());
        } else {
            String[] fallbackStyles = new String[] { "Standard", "Frosted" };
            int idx = ThemeManager.isFrostedGlass() ? 1 : 0;
            this.displayStyleSegment = new SegmentedComponent(fallbackStyles, idx,
                new SegmentedComponent.SelectionListener() {
                    @Override
                    public void onSelected(int index, String label) {
                        DisplayStyle ds = index == 1 ? DisplayStyle.FROSTED_GLASS : DisplayStyle.STANDARD;
                        ThemeManager.getInstance().setDisplayStyle(ds);
                    }
                });
            this.blurStrengthSlider = new SliderComponent(
                ThemeManager.getBlurStrength(), 0.0D, 24.0D, 1.0D, "px",
                new SliderComponent.ChangeListener() {
                    @Override
                    public void onValueChanged(double newValue) {
                        ThemeManager.getInstance().setBlurStrength((float) newValue);
                    }
                });
        }

        int initialScaleIdx = findNearestUiScalePresetIndex(UiScale.getUserMultiplier());
        this.uiScaleDropdown = new DropdownComponent(
            UI_SCALE_LABELS, initialScaleIdx, this.popupManager,
            new DropdownComponent.SelectionListener() {
                @Override
                public void onSelected(int index, String label) {
                    applyGlobalUiScalePreset(index);
                }
            }
        );

        boolean initialReduce = uiConfig != null ? uiConfig.isReduceMotion() : Animation.isReduceMotion();
        this.reduceMotionSwitch = new SwitchComponent(initialReduce, new SwitchComponent.ChangeListener() {
            @Override
            public void onChanged(boolean checked) {
                applyReduceMotion(checked);
            }
        });

        double initialCgScale = uiConfig != null
            ? clampDouble(uiConfig.getClickGuiScale(), 0.50D, 2.00D)
            : 1.0D;
        this.clickGuiScaleSlider = new SliderComponent(
            initialCgScale, 0.50D, 2.00D, ClickGuiModule.SCALE_STEP, "x",
            new SliderComponent.ChangeListener() {
                @Override
                public void onValueChanged(double newValue) {
                    if (syncingControls) {
                        return;
                    }
                    if (draggingClickGuiSlider) {
                        pendingClickGuiScale = newValue;
                    } else {
                        parentScreen.applyClickGuiScaleMultiplier((float) newValue);
                    }
                }
            }
        );

        int initialKey = uiConfig != null ? uiConfig.getClickGuiKeybind() : 0;
        this.openKeybindControl = new KeybindComponent(initialKey, new KeybindComponent.KeybindChangeListener() {
            @Override
            public void onKeybindChanged(int newKeyCode) {
                applyOpenKeybind(newKeyCode);
            }
        });

        this.customAccentPopup = new ColorPickerPopup(new ColorPickerPopup.ColorModel() {
            @Override
            public int getArgb() {
                Integer override = ThemeManager.getInstance().getAccentOverride();
                return override != null ? override.intValue() : ThemeManager.current().accent();
            }

            @Override
            public void setArgb(int argb) {
                applyAccentOverride(Integer.valueOf(argb));
            }

            @Override
            public boolean isAllowAlpha() {
                return false;
            }

            @Override
            public String getHexString() {
                return ColorSetting.formatHexColor(getArgb(), false);
            }
        }, this.popupManager);

        rebuildThemeSlots();
    }

    /**
     * 刷新本地化标签、主题卡片列表以及外部配置同步状态。
     */
    public void refreshLabelsAndState() {
        ensureControlsInitialized();

        this.titleTheme = LanguageModule.format("ui.hoptimization.settings.theme");
        this.btnImportText = LanguageModule.format("ui.hoptimization.settings.theme.import_clipboard");
        this.btnExportText = LanguageModule.format("ui.hoptimization.settings.theme.export");
        this.btnThemeFolderText = LanguageModule.format("ui.hoptimization.settings.theme.open_folder");
        this.labelDisplayStyle = LanguageModule.format("setting.hoptimization.theme.display_style.name");
        this.labelBlurStrength = LanguageModule.format("setting.hoptimization.theme.blur_strength.name");

        this.titleAccent = LanguageModule.format("ui.hoptimization.settings.accent");
        this.btnFollowThemeText = LanguageModule.format("ui.hoptimization.settings.accent.follow_theme");
        this.btnCustomAccentText = LanguageModule.format("ui.hoptimization.settings.accent.custom");
        for (int i = 0; i < BuiltinThemes.ACCENT_PRESETS.size(); i++) {
            this.accentPresetNames[i] = BuiltinThemes.ACCENT_PRESETS.get(i).getLocalizedName();
        }

        this.titleAppearance = LanguageModule.format("ui.hoptimization.settings.appearance");
        this.labelUiScale = LanguageModule.format("ui.hoptimization.settings.ui_scale");
        this.labelReduceMotion = LanguageModule.format("ui.hoptimization.settings.reduce_motion");

        this.titleClickGui = LanguageModule.format("ui.hoptimization.settings.clickgui");
        this.labelClickGuiScale = LanguageModule.format("setting.hoptimization.clickgui.scale.name");
        this.labelOpenKey = LanguageModule.format("ui.hoptimization.settings.clickgui.open_key");
        this.labelHud = LanguageModule.format("ui.hoptimization.settings.hud");
        this.btnEditHudText = LanguageModule.format("ui.hoptimization.settings.hud.edit_layout");

        this.titleAbout = LanguageModule.format("ui.hoptimization.settings.about");
        this.versionLineText = LanguageModule.format("ui.hoptimization.settings.about.version")
            + " v" + HoptimizationMod.VERSION;
        this.btnOpenConfigText = LanguageModule.format("ui.hoptimization.settings.about.open_config_folder");

        this.msgImported = LanguageModule.format("ui.hoptimization.settings.theme.imported");
        this.msgExported = LanguageModule.format("ui.hoptimization.settings.theme.exported");
        this.msgImportFailed = LanguageModule.format("ui.hoptimization.settings.theme.import_failed");

        this.displayStyleSegment.refreshLabels();
        this.blurStrengthSlider.refreshLabels();
        this.uiScaleDropdown.refreshLabels();
        this.openKeybindControl.refreshLabels();

        syncControlsFromConfig();
        rebuildThemeSlots();
    }

    /**
     * 从当前生效的 {@link UiConfig} 同步独立控件状态（例如切换配置档或 Ctrl+滚轮缩放后）。
     */
    public void syncControlsFromConfig() {
        if (!this.controlsInitialized) {
            return;
        }
        ConfigManager cm = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = cm != null ? cm.getUiConfig() : null;
        if (uiConfig != null) {
            this.syncingControls = true;
            try {
                this.uiScaleDropdown.setSelectedIndex(findNearestUiScalePresetIndex(uiConfig.getUiScaleMultiplier()));
                this.reduceMotionSwitch.setChecked(uiConfig.isReduceMotion());
                if (!this.draggingClickGuiSlider && !this.clickGuiScaleSlider.isEditingValue()) {
                    this.clickGuiScaleSlider.setExactValue(
                        clampDouble(uiConfig.getClickGuiScale(), 0.50D, 2.00D)
                    );
                }
                if (!this.openKeybindControl.isListening()) {
                    this.openKeybindControl.setKeyCode(uiConfig.getClickGuiKeybind());
                }
            } finally {
                this.syncingControls = false;
            }
        }
        this.displayStyleSegment.snapToCurrent();
    }

    private void rebuildThemeSlots() {
        List<ThemeData> all = ThemeManager.getInstance().getAllThemes();
        while (this.themeSlots.size() > all.size()) {
            this.themeSlots.remove(this.themeSlots.size() - 1);
        }
        while (this.themeSlots.size() < all.size()) {
            this.themeSlots.add(new ThemeSlot());
        }
        for (int i = 0; i < all.size(); i++) {
            ThemeData td = all.get(i);
            ThemeSlot slot = this.themeSlots.get(i);
            slot.theme = td;
            slot.displayName = td.getLocalizedName();
            slot.builtin = ThemeManager.getInstance().isBuiltin(td.getId());
        }
    }

    /**
     * 渲染右侧可滚动设置页。
     */
    public void render(
        float listX,
        float listY,
        float listW,
        float listH,
        float mouseX,
        float mouseY,
        float windowAlpha,
        ThemeData theme,
        TooltipManager tooltipManager
    ) {
        ensureControlsInitialized();
        if (this.titleTheme.isEmpty()) {
            refreshLabelsAndState();
        }

        if (this.draggingScrollbar && !Mouse.isButtonDown(0)) {
            this.draggingScrollbar = false;
        } else if (this.draggingScrollbar) {
            updateScrollFromScrollbarMouseY(mouseY);
        }

        float cardX = listX + UiTokens.SPACE_20;
        float cardW = Math.max(0.0f, listW - UiTokens.SPACE_20 * 2.0f);
        float totalContentH = computeTotalHeight(cardW);
        updateSmoothScroll(listH, totalContentH);

        boolean inViewport = isInside(mouseX, mouseY, listX, listY, listW, listH);
        float effMouseX = inViewport ? mouseX : -99999.0f;
        float effMouseY = inViewport ? mouseY : -99999.0f;

        boolean pushedScissor = false;
        if (Display.isCreated()) {
            ScissorStack.pushTransformed(listX, listY, listW, listH);
            pushedScissor = true;
        }
        try {
            float curY = listY - this.currentScroll;
            curY += renderThemeCard(cardX, curY, cardW, effMouseX, effMouseY, windowAlpha, theme) + CARD_GAP;
            curY += renderAccentCard(cardX, curY, cardW, effMouseX, effMouseY, windowAlpha, theme, tooltipManager)
                + CARD_GAP;
            curY += renderAppearanceCard(cardX, curY, cardW, effMouseX, effMouseY, windowAlpha, theme) + CARD_GAP;
            curY += renderClickGuiHudCard(cardX, curY, cardW, effMouseX, effMouseY, windowAlpha, theme) + CARD_GAP;
            renderAboutCard(cardX, curY, cardW, effMouseX, effMouseY, windowAlpha, theme);

            if (!ThemeManager.isFrostedGlass()) {
                int maskSurface = RenderUtil.withAlpha(theme.surface1(), windowAlpha);
                if (this.currentScroll > 0.5f) {
                    RenderUtil.drawScrollFadeMask(cardX, listY, cardW, FADE_MASK_HEIGHT, maskSurface, true);
                }
                if (this.maxScroll > 0.5f && this.currentScroll < this.maxScroll - 0.5f) {
                    RenderUtil.drawScrollFadeMask(
                        cardX, listY + listH - FADE_MASK_HEIGHT, cardW, FADE_MASK_HEIGHT, maskSurface, false
                    );
                }
            }

            renderScrollbar(listX, listY, listW, listH, totalContentH, inViewport, windowAlpha, theme);
        } finally {
            if (pushedScissor) {
                ScissorStack.pop();
            }
        }
    }

    private float computeTotalHeight(float cardW) {
        int themeRows = Math.max(1, (this.themeSlots.size() + THEME_COLS - 1) / THEME_COLS);
        float themeGridH = themeRows * THEME_CARD_HEIGHT + (themeRows - 1) * THEME_GRID_GAP;
        float themeCardH = CARD_PAD * 2.0f + ROW_HEIGHT + UiTokens.SPACE_8 + themeGridH
            + UiTokens.SPACE_12 + UiTokens.BORDER_WIDTH + UiTokens.SPACE_8
            + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT;

        float accentCardH = CARD_PAD * 2.0f + ROW_HEIGHT + UiTokens.SPACE_8 + SWATCH_SIZE + UiTokens.SPACE_2;
        float appearanceCardH = CARD_PAD * 2.0f + 20.0f + UiTokens.SPACE_8 + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT;
        float clickGuiCardH = CARD_PAD * 2.0f + 20.0f + UiTokens.SPACE_8
            + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT;
        float aboutCardH = CARD_PAD * 2.0f + 32.0f;

        return themeCardH + CARD_GAP + accentCardH + CARD_GAP + appearanceCardH
            + CARD_GAP + clickGuiCardH + CARD_GAP + aboutCardH + BOTTOM_SCROLL_PADDING;
    }

    private float renderThemeCard(
        float x,
        float y,
        float w,
        float mouseX,
        float mouseY,
        float alpha,
        ThemeData theme
    ) {
        int rows = Math.max(1, (this.themeSlots.size() + THEME_COLS - 1) / THEME_COLS);
        float gridH = rows * THEME_CARD_HEIGHT + (rows - 1) * THEME_GRID_GAP;
        float cardH = CARD_PAD * 2.0f + ROW_HEIGHT + UiTokens.SPACE_8 + gridH
            + UiTokens.SPACE_12 + UiTokens.BORDER_WIDTH + UiTokens.SPACE_8
            + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT;

        drawSectionCard(x, y, w, cardH, alpha, theme);

        float innerX = x + CARD_PAD;
        float innerW = Math.max(0.0f, w - CARD_PAD * 2.0f);
        float curY = y + CARD_PAD;

        TtfFontRenderer titleFont = FontManager.body();
        TtfFontRenderer labelFont = FontManager.bodyRegular();
        TtfFontRenderer captionFont = FontManager.caption();

        float titleY = curY + (ROW_HEIGHT - titleFont.getHeight()) * 0.5f;
        titleFont.drawString(this.titleTheme, innerX, titleY, RenderUtil.withAlpha(theme.textPrimary(), alpha));

        if (!this.statusMessage.isEmpty() && System.nanoTime() < this.statusExpireNanos) {
            int statusCol = this.statusError ? theme.danger() : theme.success();
            float sx = innerX + titleFont.getWidth(this.titleTheme) + UiTokens.SPACE_8;
            captionFont.drawString(
                this.statusMessage,
                sx,
                curY + (ROW_HEIGHT - captionFont.getHeight()) * 0.5f,
                RenderUtil.withAlpha(statusCol, alpha)
            );
        }

        float btnY = curY + (ROW_HEIGHT - PILL_BTN_HEIGHT) * 0.5f;
        float rightEdge = innerX + innerW;
        this.btnThemeFolderW = captionFont.getWidth(this.btnThemeFolderText) + UiTokens.SPACE_8 * 2.0f;
        this.btnThemeFolderX = rightEdge - this.btnThemeFolderW;
        this.btnThemeFolderY = btnY;
        drawPillButton(
            this.btnThemeFolderX, btnY, this.btnThemeFolderW, PILL_BTN_HEIGHT,
            this.btnThemeFolderText, false, mouseX, mouseY, this.themeFolderHoverAnim, alpha, theme
        );

        this.btnExportW = captionFont.getWidth(this.btnExportText) + UiTokens.SPACE_8 * 2.0f;
        this.btnExportX = this.btnThemeFolderX - UiTokens.SPACE_6 - this.btnExportW;
        this.btnExportY = btnY;
        drawPillButton(
            this.btnExportX, btnY, this.btnExportW, PILL_BTN_HEIGHT,
            this.btnExportText, false, mouseX, mouseY, this.exportHoverAnim, alpha, theme
        );

        this.btnImportW = captionFont.getWidth(this.btnImportText) + UiTokens.SPACE_8 * 2.0f;
        this.btnImportX = this.btnExportX - UiTokens.SPACE_6 - this.btnImportW;
        this.btnImportY = btnY;
        drawPillButton(
            this.btnImportX, btnY, this.btnImportW, PILL_BTN_HEIGHT,
            this.btnImportText, false, mouseX, mouseY, this.importHoverAnim, alpha, theme
        );

        curY += ROW_HEIGHT + UiTokens.SPACE_8;

        String activeThemeId = ThemeManager.getInstance().getActiveThemeId();
        float slotW = (innerW - THEME_GRID_GAP * (THEME_COLS - 1)) / THEME_COLS;
        float slotRadius = theme.radiusControl();

        for (int i = 0; i < this.themeSlots.size(); i++) {
            ThemeSlot slot = this.themeSlots.get(i);
            int col = i % THEME_COLS;
            int row = i / THEME_COLS;
            float sx = innerX + col * (slotW + THEME_GRID_GAP);
            float sy = curY + row * (THEME_CARD_HEIGHT + THEME_GRID_GAP);
            slot.x = sx;
            slot.y = sy;
            slot.w = slotW;
            slot.h = THEME_CARD_HEIGHT;

            boolean selected = slot.theme.getId().equalsIgnoreCase(activeThemeId);
            boolean hovered = isInside(mouseX, mouseY, sx, sy, slotW, THEME_CARD_HEIGHT);
            slot.hoverAnim.setTarget(hovered ? 1.0f : 0.0f);
            float hoverT = clamp01(slot.hoverAnim.get());

            ThemeData st = slot.theme;
            if (selected) {
                RenderUtil.drawRoundedRect(
                    sx - 1.5f, sy - 1.5f, slotW + 3.0f, THEME_CARD_HEIGHT + 3.0f,
                    slotRadius + 1.5f,
                    RenderUtil.withAlpha(theme.accentMuted(), alpha)
                );
            }

            int borderCol = selected
                ? theme.accent()
                : ColorAnimation.lerpArgb(st.border(), st.borderStrong(), hoverT);
            RenderUtil.drawRoundedRect(
                sx, sy, slotW, THEME_CARD_HEIGHT, slotRadius,
                RenderUtil.withAlpha(st.surface0(), alpha),
                RenderUtil.withAlpha(borderCol, alpha),
                UiTokens.BORDER_WIDTH
            );

            // Mini window layout preview inside top 30px
            float previewPad = UiTokens.SPACE_4;
            float px = sx + previewPad;
            float py = sy + previewPad;
            float pw = Math.max(0.0f, slotW - previewPad * 2.0f);
            float ph = 26.0f;
            float innerR = Math.max(2.0f, slotRadius - 2.0f);
            float sideW = Math.min(28.0f, pw * 0.28f);

            RenderUtil.drawRoundedRect(
                px, py, sideW, ph, innerR, RenderUtil.withAlpha(st.surface1(), alpha)
            );
            float mainX = px + sideW + 3.0f;
            float mainW = Math.max(0.0f, pw - sideW - 3.0f);
            RenderUtil.drawRoundedRect(
                mainX, py, mainW, ph, innerR, RenderUtil.withAlpha(st.surface2(), alpha)
            );
            // Mini UI bars + accent pill inside preview
            RenderUtil.drawRoundedRect(
                mainX + 4.0f, py + 5.0f, Math.min(36.0f, mainW * 0.45f), 4.0f, 2.0f,
                RenderUtil.withAlpha(st.surface4(), alpha)
            );
            RenderUtil.drawRoundedRect(
                mainX + 4.0f, py + 12.0f, Math.min(52.0f, mainW * 0.65f), 4.0f, 2.0f,
                RenderUtil.withAlpha(st.surface3(), alpha)
            );
            float dotSize = 8.0f;
            RenderUtil.drawRoundedRect(
                mainX + mainW - dotSize - 5.0f, py + 5.0f, dotSize, dotSize, dotSize * 0.5f,
                RenderUtil.withAlpha(st.accent(), alpha)
            );

            // Theme localized name in bottom 20px using slot's own high-contrast textPrimary
            float labelY = sy + 33.0f + (19.0f - captionFont.getHeight()) * 0.5f;
            float maxLabelW = slotW - UiTokens.SPACE_12 - (selected ? 14.0f : 0.0f);
            captionFont.drawStringWithEllipsis(
                slot.displayName,
                sx + UiTokens.SPACE_6,
                labelY,
                Math.max(0.0f, maxLabelW),
                RenderUtil.withAlpha(st.textPrimary(), alpha)
            );

            if (selected) {
                RenderUtil.drawTintedIcon(
                    ClickGuiIcons.CHECK,
                    sx + slotW - UiTokens.SPACE_6 - 12.0f,
                    sy + 33.0f + (19.0f - 12.0f) * 0.5f,
                    12.0f,
                    RenderUtil.withAlpha(st.accent(), alpha)
                );
            }
        }

        curY += gridH + UiTokens.SPACE_12;
        RenderUtil.drawRect(innerX, curY, innerW, UiTokens.BORDER_WIDTH, RenderUtil.withAlpha(theme.border(), alpha));
        curY += UiTokens.BORDER_WIDTH + UiTokens.SPACE_8;

        // Display Style row
        labelFont.drawString(
            this.labelDisplayStyle,
            innerX,
            curY + (ROW_HEIGHT - labelFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );
        float segW = Math.min(CONTROL_WIDTH, Math.max(120.0f, this.displayStyleSegment.getPreferredWidth()));
        this.displayStyleSegment.render(
            innerX + innerW - segW,
            curY + (ROW_HEIGHT - SegmentedComponent.HEIGHT) * 0.5f,
            segW,
            mouseX, mouseY, alpha, theme
        );
        curY += ROW_HEIGHT + ROW_GAP;

        // Blur Strength row
        boolean blurActive = ThemeManager.isFrostedGlass();
        this.blurStrengthSlider.setEnabled(blurActive);
        int blurLabelColor = blurActive ? theme.textPrimary() : RenderUtil.withAlpha(theme.textTertiary(), 0.5f);
        labelFont.drawString(
            this.labelBlurStrength,
            innerX,
            curY + (ROW_HEIGHT - labelFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(blurLabelColor, alpha)
        );
        this.blurStrengthSlider.render(
            innerX + innerW - CONTROL_WIDTH,
            curY + (ROW_HEIGHT - SliderComponent.INLINE_HEIGHT) * 0.5f,
            CONTROL_WIDTH,
            mouseX, mouseY, alpha, theme
        );

        return cardH;
    }

    private float renderAccentCard(
        float x,
        float y,
        float w,
        float mouseX,
        float mouseY,
        float alpha,
        ThemeData theme,
        TooltipManager tooltipManager
    ) {
        float cardH = CARD_PAD * 2.0f + ROW_HEIGHT + UiTokens.SPACE_8 + SWATCH_SIZE + UiTokens.SPACE_2;
        drawSectionCard(x, y, w, cardH, alpha, theme);

        float innerX = x + CARD_PAD;
        float innerW = Math.max(0.0f, w - CARD_PAD * 2.0f);
        float curY = y + CARD_PAD;

        TtfFontRenderer titleFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();

        titleFont.drawString(
            this.titleAccent,
            innerX,
            curY + (ROW_HEIGHT - titleFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );

        Integer accentOverride = ThemeManager.getInstance().getAccentOverride();
        boolean followingTheme = accentOverride == null;
        this.btnFollowAccentW = captionFont.getWidth(this.btnFollowThemeText) + UiTokens.SPACE_12 * 2.0f;
        this.btnFollowAccentX = innerX + innerW - this.btnFollowAccentW;
        this.btnFollowAccentY = curY + (ROW_HEIGHT - PILL_BTN_HEIGHT) * 0.5f;
        drawPillButton(
            this.btnFollowAccentX, this.btnFollowAccentY, this.btnFollowAccentW, PILL_BTN_HEIGHT,
            this.btnFollowThemeText, followingTheme, mouseX, mouseY, this.followAccentHoverAnim, alpha, theme
        );

        curY += ROW_HEIGHT + UiTokens.SPACE_8;
        this.swatchesStartX = innerX;
        this.swatchesY = curY;

        boolean matchedPreset = false;
        for (int i = 0; i < BuiltinThemes.ACCENT_PRESETS.size(); i++) {
            BuiltinThemes.AccentPreset preset = BuiltinThemes.ACCENT_PRESETS.get(i);
            float sx = innerX + i * (SWATCH_SIZE + SWATCH_GAP);
            float sy = curY;
            boolean hovered = isInside(mouseX, mouseY, sx, sy, SWATCH_SIZE, SWATCH_SIZE);
            this.accentSwatchHoverAnims[i].setTarget(hovered ? 1.0f : 0.0f);
            float hoverT = clamp01(this.accentSwatchHoverAnims[i].get());

            boolean selected = accentOverride != null && sameRgb(accentOverride.intValue(), preset.getArgb());
            if (selected) {
                matchedPreset = true;
            }

            if (selected || hoverT > 0.01f) {
                float haloAlpha = selected ? 1.0f : (hoverT * 0.55f);
                RenderUtil.drawRoundedRect(
                    sx - 2.0f, sy - 2.0f, SWATCH_SIZE + 4.0f, SWATCH_SIZE + 4.0f,
                    (SWATCH_SIZE + 4.0f) * 0.5f,
                    0,
                    RenderUtil.withAlpha(preset.getArgb(), haloAlpha * alpha),
                    UiTokens.BORDER_WIDTH
                );
            }

            RenderUtil.drawRoundedRect(
                sx, sy, SWATCH_SIZE, SWATCH_SIZE, SWATCH_SIZE * 0.5f,
                RenderUtil.withAlpha(preset.getArgb(), alpha),
                RenderUtil.withAlpha(theme.border(), alpha),
                UiTokens.BORDER_WIDTH
            );

            if (selected) {
                RenderUtil.drawTintedIcon(
                    ClickGuiIcons.CHECK,
                    sx + (SWATCH_SIZE - 12.0f) * 0.5f,
                    sy + (SWATCH_SIZE - 12.0f) * 0.5f,
                    12.0f,
                    RenderUtil.withAlpha(preset.getTextOnAccent(), alpha)
                );
            }

            if (hovered && tooltipManager != null) {
                tooltipManager.request(preset, this.accentPresetNames[i]);
            }
        }

        // Custom Accent pill button on the right
        boolean customSelected = accentOverride != null && !matchedPreset;
        this.btnCustomAccentW = 12.0f + UiTokens.SPACE_6 + captionFont.getWidth(this.btnCustomAccentText)
            + UiTokens.SPACE_12 * 2.0f;
        this.btnCustomAccentX = innerX + innerW - this.btnCustomAccentW;
        this.btnCustomAccentY = curY + (SWATCH_SIZE - PILL_BTN_HEIGHT) * 0.5f;
        this.customAccentPopup.updateAnchor(
            this.btnCustomAccentX, this.btnCustomAccentY, this.btnCustomAccentW, PILL_BTN_HEIGHT
        );

        boolean customHovered = isInside(
            mouseX, mouseY, this.btnCustomAccentX, this.btnCustomAccentY, this.btnCustomAccentW, PILL_BTN_HEIGHT
        );
        this.customAccentHoverAnim.setTarget((customHovered || this.customAccentPopup.isOverlayOpen()) ? 1.0f : 0.0f);
        float chT = clamp01(this.customAccentHoverAnim.get());
        int customBg = customSelected
            ? theme.accentMuted()
            : ColorAnimation.lerpArgb(theme.surface3(), theme.surface4(), chT);
        int customBorder = customSelected ? RenderUtil.withAlpha(theme.accent(), alpha) : 0;
        RenderUtil.drawRoundedRect(
            this.btnCustomAccentX, this.btnCustomAccentY, this.btnCustomAccentW, PILL_BTN_HEIGHT,
            theme.radiusPill(PILL_BTN_HEIGHT),
            RenderUtil.withAlpha(customBg, alpha),
            customBorder,
            customBorder != 0 ? UiTokens.BORDER_WIDTH : 0.0f
        );

        float dotX = this.btnCustomAccentX + UiTokens.SPACE_8;
        float dotY = this.btnCustomAccentY + (PILL_BTN_HEIGHT - 12.0f) * 0.5f;
        int previewAccent = accentOverride != null ? accentOverride.intValue() : theme.accent();
        RenderUtil.drawRoundedRect(
            dotX, dotY, 12.0f, 12.0f, 6.0f,
            RenderUtil.withAlpha(previewAccent, alpha),
            RenderUtil.withAlpha(theme.border(), alpha),
            UiTokens.BORDER_WIDTH
        );
        captionFont.drawString(
            this.btnCustomAccentText,
            dotX + 12.0f + UiTokens.SPACE_6,
            this.btnCustomAccentY + (PILL_BTN_HEIGHT - captionFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(customSelected ? theme.accent() : theme.textPrimary(), alpha)
        );

        return cardH;
    }

    private float renderAppearanceCard(
        float x,
        float y,
        float w,
        float mouseX,
        float mouseY,
        float alpha,
        ThemeData theme
    ) {
        float cardH = CARD_PAD * 2.0f + 20.0f + UiTokens.SPACE_8 + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT;
        drawSectionCard(x, y, w, cardH, alpha, theme);

        float innerX = x + CARD_PAD;
        float innerW = Math.max(0.0f, w - CARD_PAD * 2.0f);
        float curY = y + CARD_PAD;

        TtfFontRenderer titleFont = FontManager.body();
        TtfFontRenderer labelFont = FontManager.bodyRegular();

        titleFont.drawString(this.titleAppearance, innerX, curY, RenderUtil.withAlpha(theme.textPrimary(), alpha));
        curY += 20.0f + UiTokens.SPACE_8;

        // Global UI Scale dropdown
        labelFont.drawString(
            this.labelUiScale,
            innerX,
            curY + (ROW_HEIGHT - labelFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );
        this.uiScaleDropdown.render(
            innerX + innerW - 120.0f,
            curY + (ROW_HEIGHT - DropdownComponent.HEIGHT) * 0.5f,
            120.0f,
            mouseX, mouseY, alpha, theme
        );
        curY += ROW_HEIGHT + ROW_GAP;

        // Reduce Motion switch
        labelFont.drawString(
            this.labelReduceMotion,
            innerX,
            curY + (ROW_HEIGHT - labelFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );
        this.reduceMotionSwitch.render(
            innerX + innerW - SwitchComponent.WIDTH,
            curY + (ROW_HEIGHT - SwitchComponent.HEIGHT) * 0.5f,
            SwitchComponent.WIDTH,
            mouseX, mouseY, alpha, theme
        );

        return cardH;
    }

    private float renderClickGuiHudCard(
        float x,
        float y,
        float w,
        float mouseX,
        float mouseY,
        float alpha,
        ThemeData theme
    ) {
        float cardH = CARD_PAD * 2.0f + 20.0f + UiTokens.SPACE_8
            + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT;
        drawSectionCard(x, y, w, cardH, alpha, theme);

        float innerX = x + CARD_PAD;
        float innerW = Math.max(0.0f, w - CARD_PAD * 2.0f);
        float curY = y + CARD_PAD;

        TtfFontRenderer titleFont = FontManager.body();
        TtfFontRenderer labelFont = FontManager.bodyRegular();
        TtfFontRenderer captionFont = FontManager.caption();

        titleFont.drawString(this.titleClickGui, innerX, curY, RenderUtil.withAlpha(theme.textPrimary(), alpha));
        curY += 20.0f + UiTokens.SPACE_8;

        // ClickGUI Scale slider
        labelFont.drawString(
            this.labelClickGuiScale,
            innerX,
            curY + (ROW_HEIGHT - labelFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );
        this.clickGuiScaleSlider.render(
            innerX + innerW - CONTROL_WIDTH,
            curY + (ROW_HEIGHT - SliderComponent.INLINE_HEIGHT) * 0.5f,
            CONTROL_WIDTH,
            mouseX, mouseY, alpha, theme
        );
        curY += ROW_HEIGHT + ROW_GAP;

        // Open Keybind
        labelFont.drawString(
            this.labelOpenKey,
            innerX,
            curY + (ROW_HEIGHT - labelFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );
        float kbW = Math.max(KeybindComponent.MIN_WIDTH, this.openKeybindControl.getPreferredWidth());
        this.openKeybindControl.render(
            innerX + innerW - kbW,
            curY + (ROW_HEIGHT - KeybindComponent.HEIGHT) * 0.5f,
            kbW,
            mouseX, mouseY, alpha, theme
        );
        curY += ROW_HEIGHT + ROW_GAP;

        // HUD Layout Editor button
        labelFont.drawString(
            this.labelHud,
            innerX,
            curY + (ROW_HEIGHT - labelFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );
        this.btnEditHudW = Math.max(104.0f, 14.0f + UiTokens.SPACE_6
            + captionFont.getWidth(this.btnEditHudText) + UiTokens.SPACE_12 * 2.0f);
        this.btnEditHudX = innerX + innerW - this.btnEditHudW;
        this.btnEditHudY = curY + (ROW_HEIGHT - ACTION_BTN_HEIGHT) * 0.5f;

        boolean hudHovered = isInside(
            mouseX, mouseY, this.btnEditHudX, this.btnEditHudY, this.btnEditHudW, ACTION_BTN_HEIGHT
        );
        this.editHudHoverAnim.setTarget(hudHovered ? 1.0f : 0.0f);
        int hudBtnBg = ColorAnimation.lerpArgb(
            theme.accent(), theme.accentHover(), clamp01(this.editHudHoverAnim.get())
        );
        RenderUtil.drawRoundedRect(
            this.btnEditHudX, this.btnEditHudY, this.btnEditHudW, ACTION_BTN_HEIGHT,
            theme.radiusControl(),
            RenderUtil.withAlpha(hudBtnBg, alpha)
        );
        float contentW = 14.0f + UiTokens.SPACE_6 + captionFont.getWidth(this.btnEditHudText);
        float startX = this.btnEditHudX + (this.btnEditHudW - contentW) * 0.5f;
        RenderUtil.drawTintedIcon(
            ClickGuiIcons.MOVE,
            startX,
            this.btnEditHudY + (ACTION_BTN_HEIGHT - 14.0f) * 0.5f,
            14.0f,
            RenderUtil.withAlpha(theme.textOnAccent(), alpha)
        );
        captionFont.drawString(
            this.btnEditHudText,
            startX + 14.0f + UiTokens.SPACE_6,
            this.btnEditHudY + (ACTION_BTN_HEIGHT - captionFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textOnAccent(), alpha)
        );

        return cardH;
    }

    private float renderAboutCard(
        float x,
        float y,
        float w,
        float mouseX,
        float mouseY,
        float alpha,
        ThemeData theme
    ) {
        float cardH = CARD_PAD * 2.0f + 32.0f;
        drawSectionCard(x, y, w, cardH, alpha, theme);

        float innerX = x + CARD_PAD;
        float innerW = Math.max(0.0f, w - CARD_PAD * 2.0f);
        float curY = y + CARD_PAD;

        TtfFontRenderer titleFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();

        titleFont.drawString(
            this.titleAbout + " · " + HoptimizationMod.NAME,
            innerX,
            curY + 1.0f,
            RenderUtil.withAlpha(theme.textPrimary(), alpha)
        );
        captionFont.drawString(
            this.versionLineText,
            innerX,
            curY + titleFont.getHeight() + UiTokens.SPACE_2,
            RenderUtil.withAlpha(theme.textSecondary(), alpha)
        );

        this.btnOpenConfigW = captionFont.getWidth(this.btnOpenConfigText) + UiTokens.SPACE_12 * 2.0f;
        this.btnOpenConfigX = innerX + innerW - this.btnOpenConfigW;
        this.btnOpenConfigY = curY + (32.0f - ACTION_BTN_HEIGHT) * 0.5f;
        drawPillButton(
            this.btnOpenConfigX, this.btnOpenConfigY, this.btnOpenConfigW, ACTION_BTN_HEIGHT,
            this.btnOpenConfigText, false, mouseX, mouseY, this.openConfigHoverAnim, alpha, theme
        );

        return cardH;
    }

    private static void drawSectionCard(float x, float y, float w, float h, float alpha, ThemeData theme) {
        if (ThemeManager.isFrostedGlass()) {
            RenderUtil.drawFrostedRoundedRect(
                x, y, w, h,
                theme.radiusCard(),
                theme.frostedCardTint(),
                theme.frostedBorder(),
                UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength() * 0.85f,
                alpha
            );
            return;
        }
        boolean hasBorder = theme.style().isCardBorder();
        RenderUtil.drawRoundedRect(
            x, y, w, h,
            theme.radiusCard(),
            RenderUtil.withAlpha(theme.surface2(), alpha),
            hasBorder ? RenderUtil.withAlpha(theme.border(), alpha) : 0,
            hasBorder ? UiTokens.BORDER_WIDTH : 0.0f
        );
    }

    private static void drawPillButton(
        float x,
        float y,
        float w,
        float h,
        String text,
        boolean active,
        float mouseX,
        float mouseY,
        Animation hoverAnim,
        float alpha,
        ThemeData theme
    ) {
        boolean hovered = isInside(mouseX, mouseY, x, y, w, h);
        hoverAnim.setTarget(hovered ? 1.0f : 0.0f);
        float hoverT = clamp01(hoverAnim.get());

        int bg = active
            ? theme.accentMuted()
            : ColorAnimation.lerpArgb(theme.surface3(), theme.surface4(), hoverT);
        int border = active ? RenderUtil.withAlpha(theme.accent(), alpha) : 0;
        RenderUtil.drawRoundedRect(
            x, y, w, h,
            theme.radiusControl(),
            RenderUtil.withAlpha(bg, alpha),
            border,
            border != 0 ? UiTokens.BORDER_WIDTH : 0.0f
        );

        TtfFontRenderer font = FontManager.caption();
        int fg = active ? theme.accent() : theme.textPrimary();
        font.drawStringCentered(
            text,
            x + w * 0.5f,
            y + (h - font.getHeight()) * 0.5f,
            RenderUtil.withAlpha(fg, alpha)
        );
    }

    /**
     * 处理设置页点击交互。
     */
    public boolean mouseClicked(
        float mouseX,
        float mouseY,
        int mouseButton,
        float listX,
        float listY,
        float listW,
        float listH
    ) {
        ensureControlsInitialized();
        if (mouseButton == 0 && this.maxScroll > 0.5f && this.cachedSbTrackH > 0.0f
            && isInside(mouseX, mouseY,
                this.cachedSbX - SCROLLBAR_HIT_HALF_W, this.cachedSbTrackY,
                SCROLLBAR_WIDTH + SCROLLBAR_HIT_HALF_W * 2.0f, this.cachedSbTrackH)) {
            cancelCaptures();
            this.draggingScrollbar = true;
            this.lastScrollActivityNanos = System.nanoTime();
            if (mouseY >= this.cachedSbThumbY && mouseY <= this.cachedSbThumbY + this.cachedSbThumbH) {
                this.scrollbarDragGrabOffset = mouseY - this.cachedSbThumbY;
            } else {
                this.scrollbarDragGrabOffset = this.cachedSbThumbH * 0.5f;
                updateScrollFromScrollbarMouseY(mouseY);
            }
            return true;
        }

        if (!isInside(mouseX, mouseY, listX, listY, listW, listH)) {
            cancelCaptures();
            return false;
        }

        // Delegate to embedded controls first
        if (this.openKeybindControl.mouseClicked(mouseX, mouseY, mouseButton)) {
            cancelInlineSliderEdits();
            return true;
        }
        if (this.blurStrengthSlider.mouseClicked(mouseX, mouseY, mouseButton)) {
            this.clickGuiScaleSlider.cancelInlineEdit();
            return true;
        }
        if (this.clickGuiScaleSlider.mouseClicked(mouseX, mouseY, mouseButton)) {
            this.blurStrengthSlider.cancelInlineEdit();
            this.draggingClickGuiSlider = this.clickGuiScaleSlider.isDragging();
            return true;
        }
        cancelInlineSliderEdits();

        if (this.displayStyleSegment.mouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }
        if (this.uiScaleDropdown.mouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }
        if (this.reduceMotionSwitch.mouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }

        if (mouseButton != 0) {
            return false;
        }

        // Theme action buttons
        if (isInside(mouseX, mouseY, this.btnImportX, this.btnImportY, this.btnImportW, PILL_BTN_HEIGHT)) {
            handleImportTheme();
            return true;
        }
        if (isInside(mouseX, mouseY, this.btnExportX, this.btnExportY, this.btnExportW, PILL_BTN_HEIGHT)) {
            handleExportTheme();
            return true;
        }
        if (isInside(
            mouseX, mouseY, this.btnThemeFolderX, this.btnThemeFolderY, this.btnThemeFolderW, PILL_BTN_HEIGHT
        )) {
            openDirectoryAsync(ThemeManager.getInstance().getThemesDirectory());
            return true;
        }

        // Theme preview slots
        for (int i = 0; i < this.themeSlots.size(); i++) {
            ThemeSlot slot = this.themeSlots.get(i);
            if (isInside(mouseX, mouseY, slot.x, slot.y, slot.w, slot.h)) {
                selectThemeById(slot.theme.getId());
                return true;
            }
        }

        // Accent buttons & swatches
        if (isInside(
            mouseX, mouseY, this.btnFollowAccentX, this.btnFollowAccentY, this.btnFollowAccentW, PILL_BTN_HEIGHT
        )) {
            applyAccentOverride(null);
            return true;
        }
        for (int i = 0; i < BuiltinThemes.ACCENT_PRESETS.size(); i++) {
            float sx = this.swatchesStartX + i * (SWATCH_SIZE + SWATCH_GAP);
            if (isInside(mouseX, mouseY, sx, this.swatchesY, SWATCH_SIZE, SWATCH_SIZE)) {
                applyAccentOverride(Integer.valueOf(BuiltinThemes.ACCENT_PRESETS.get(i).getArgb()));
                return true;
            }
        }
        if (isInside(
            mouseX, mouseY, this.btnCustomAccentX, this.btnCustomAccentY, this.btnCustomAccentW, PILL_BTN_HEIGHT
        )) {
            this.customAccentPopup.openAt(
                this.btnCustomAccentX, this.btnCustomAccentY, this.btnCustomAccentW, PILL_BTN_HEIGHT
            );
            this.popupManager.openPopup(this.customAccentPopup);
            return true;
        }

        // Edit HUD Layout & Open Config Folder
        if (isInside(mouseX, mouseY, this.btnEditHudX, this.btnEditHudY, this.btnEditHudW, ACTION_BTN_HEIGHT)) {
            HudOverlayRenderer.openEditor(this.parentScreen);
            return true;
        }
        if (isInside(
            mouseX, mouseY, this.btnOpenConfigX, this.btnOpenConfigY, this.btnOpenConfigW, ACTION_BTN_HEIGHT
        )) {
            ConfigManager cm = HoptimizationMod.getConfigManager();
            if (cm != null) {
                openDirectoryAsync(cm.getConfigDir().toFile());
            }
            return true;
        }
        return false;
    }

    /**
     * 处理鼠标拖动。
     */
    public void mouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (!this.controlsInitialized) {
            return;
        }
        if (this.draggingScrollbar && mouseButton == 0) {
            updateScrollFromScrollbarMouseY(mouseY);
            return;
        }
        this.blurStrengthSlider.mouseDragged(mouseX, mouseY, mouseButton);
        this.clickGuiScaleSlider.mouseDragged(mouseX, mouseY, mouseButton);
    }

    /**
     * 处理鼠标释放。
     */
    public void mouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (!this.controlsInitialized) {
            return;
        }
        if (mouseButton == 0) {
            this.draggingScrollbar = false;
        }
        this.blurStrengthSlider.mouseReleased(mouseX, mouseY, mouseButton);
        this.clickGuiScaleSlider.mouseReleased(mouseX, mouseY, mouseButton);
        this.reduceMotionSwitch.mouseReleased(mouseX, mouseY, mouseButton);

        if (mouseButton == 0 && this.draggingClickGuiSlider) {
            this.draggingClickGuiSlider = false;
            if (this.pendingClickGuiScale > 0.0D) {
                double commitVal = this.pendingClickGuiScale;
                this.pendingClickGuiScale = -1.0D;
                this.parentScreen.applyClickGuiScaleMultiplier((float) commitVal);
            }
        }
    }

    /**
     * 处理滚轮滚动。
     */
    public void mouseScrolled(int wheelDelta) {
        if (this.maxScroll <= 0.0f) {
            return;
        }
        float delta = -(wheelDelta / 120.0f) * SCROLL_PER_NOTCH;
        this.targetScroll = clamp(this.targetScroll + delta, 0.0f, this.maxScroll);
        this.lastScrollActivityNanos = System.nanoTime();
    }

    /**
     * 是否正在捕获快捷键。
     */
    public boolean isListeningKeybind() {
        return this.controlsInitialized && this.openKeybindControl.isListening();
    }

    /**
     * 是否正在编辑滑块内联数值。
     */
    public boolean isEditingInlineNumber() {
        return this.controlsInitialized
            && (this.blurStrengthSlider.isEditingValue() || this.clickGuiScaleSlider.isEditingValue());
    }

    /**
     * 取消所有快捷键监听与内联数值编辑。
     */
    public void cancelCaptures() {
        if (!this.controlsInitialized) {
            return;
        }
        this.openKeybindControl.cancelListening();
        cancelInlineSliderEdits();
    }

    private void cancelInlineSliderEdits() {
        this.blurStrengthSlider.cancelInlineEdit();
        this.clickGuiScaleSlider.cancelInlineEdit();
    }

    /**
     * 转发按键事件给正在捕获或编辑的设置项。
     */
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!this.controlsInitialized) {
            return false;
        }
        if (this.openKeybindControl.isListening()) {
            return this.openKeybindControl.keyTyped(typedChar, keyCode);
        }
        if (this.blurStrengthSlider.isEditingValue()) {
            return this.blurStrengthSlider.keyTyped(typedChar, keyCode);
        }
        if (this.clickGuiScaleSlider.isEditingValue()) {
            return this.clickGuiScaleSlider.keyTyped(typedChar, keyCode);
        }
        return false;
    }

    private void selectThemeById(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        ModuleManager mm = HoptimizationMod.getModuleManager();
        Theme themeModule = mm != null ? mm.getModule(Theme.class) : null;
        if (themeModule != null && ThemeManager.getInstance().isBuiltin(id)) {
            themeModule.getThemeSetting().setValue(id);
        } else {
            ThemeManager.getInstance().setTheme(id);
            ConfigManager cm = HoptimizationMod.getConfigManager();
            if (cm != null) {
                cm.getUiConfig().setThemeId(id);
                cm.saveUiConfigDebounced();
            }
        }
    }

    private void applyAccentOverride(Integer argb) {
        ThemeManager.getInstance().setAccentOverride(argb);
        ConfigManager cm = HoptimizationMod.getConfigManager();
        if (cm != null) {
            String hex = argb != null ? ColorSetting.formatHexColor(argb.intValue(), false) : "";
            cm.getUiConfig().setAccentOverride(hex);
            cm.saveUiConfigDebounced();
        }
    }

    private static void applyGlobalUiScalePreset(int presetIndex) {
        if (presetIndex < 0 || presetIndex >= UiScale.getPresetCount()) {
            return;
        }
        float multiplier = UiScale.getPreset(presetIndex);
        UiScale.setUserMultiplier(multiplier);
        ConfigManager cm = HoptimizationMod.getConfigManager();
        if (cm != null) {
            cm.getUiConfig().setUiScaleMultiplier(multiplier);
            cm.saveUiConfigDebounced();
        }
    }

    private static void applyReduceMotion(boolean reduceMotion) {
        Animation.setReduceMotion(reduceMotion);
        ThemeManager.getInstance().setReduceMotion(reduceMotion);
        ConfigManager cm = HoptimizationMod.getConfigManager();
        if (cm != null) {
            cm.getUiConfig().setReduceMotion(reduceMotion);
            cm.saveUiConfigDebounced();
        }
    }

    private static void applyOpenKeybind(int newKeyCode) {
        ModuleManager mm = HoptimizationMod.getModuleManager();
        ClickGuiModule cgm = mm != null ? mm.getModule(ClickGuiModule.class) : null;
        if (cgm != null) {
            cgm.setKeybind(newKeyCode);
        }
        ConfigManager cm = HoptimizationMod.getConfigManager();
        if (cm != null) {
            cm.getUiConfig().setClickGuiKeybind(newKeyCode);
            cm.saveUiConfigDebounced();
        }
    }

    private void handleImportTheme() {
        String clip = GuiScreen.getClipboardString();
        if (clip == null || clip.trim().isEmpty()) {
            showStatus(this.msgImportFailed, true);
            return;
        }
        ThemeData imported = ThemeManager.getInstance().importThemeFromJson(clip);
        if (imported == null) {
            showStatus(this.msgImportFailed, true);
            return;
        }
        rebuildThemeSlots();
        selectThemeById(imported.getId());
        showStatus(this.msgImported, false);
    }

    private void handleExportTheme() {
        String id = ThemeManager.getInstance().getActiveThemeId();
        String json = ThemeManager.getInstance().exportThemeToJson(id);
        if (json != null && !json.isEmpty()) {
            GuiScreen.setClipboardString(json);
            showStatus(this.msgExported, false);
        }
    }

    private void showStatus(String msg, boolean isError) {
        this.statusMessage = msg != null ? msg : "";
        this.statusError = isError;
        this.statusExpireNanos = System.nanoTime() + STATUS_DURATION_NANOS;
    }

    private static void openDirectoryAsync(final File dir) {
        if (dir == null) {
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!dir.exists() && !dir.mkdirs()) {
                        HoptimizationMod.LOGGER.warn("Could not create directory {}", dir.getAbsolutePath());
                    }
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(dir);
                    } else {
                        HoptimizationMod.LOGGER.warn("Desktop API not supported on this platform: {}", dir);
                    }
                } catch (Exception e) {
                    HoptimizationMod.LOGGER.warn("Failed to open directory {}", dir.getAbsolutePath(), e);
                }
            }
        }, "Hoptimization-Open-Folder");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateSmoothScroll(float listH, float totalContentH) {
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
            sbX, thumbY, SCROLLBAR_WIDTH, thumbH,
            theme.radiusPill(SCROLLBAR_WIDTH),
            RenderUtil.withAlpha(theme.scrollbar(), sbAlpha)
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
    }

    private static int findNearestUiScalePresetIndex(float multiplier) {
        int bestIdx = 2;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < UiScale.getPresetCount(); i++) {
            float diff = Math.abs(UiScale.getPreset(i) - multiplier);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static boolean sameRgb(int a, int b) {
        return (a << 8) == (b << 8);
    }

    private static boolean isInside(float px, float py, float x, float y, float w, float h) {
        return w > 0.0f && h > 0.0f && px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private static double clampDouble(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
