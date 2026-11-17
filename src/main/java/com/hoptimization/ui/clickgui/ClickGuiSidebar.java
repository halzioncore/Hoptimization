package com.hoptimization.ui.clickgui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.FontStyle;
import com.hoptimization.ui.font.FontToken;
import com.hoptimization.ui.font.FontWeight;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.resources.I18n;

/**
 * ClickGUI 左侧导航栏（宽 192）：品牌拖动区、搜索框外观、分类列表与平滑滑动指示条、
 * 底部配置档入口与设置入口。
 */
public final class ClickGuiSidebar {
    public static final float WIDTH = 192.0f;
    public static final float BRAND_HEIGHT = 56.0f;
    private static final float SEARCH_HEIGHT = 28.0f;
    private static final float ITEM_HEIGHT = 32.0f;
    private static final float ITEM_GAP = UiTokens.SPACE_4;
    private static final float ITEM_MARGIN_H = UiTokens.SPACE_8;
    private static final float ITEM_PADDING_H = UiTokens.SPACE_12;
    private static final float CATEGORY_ICON_SIZE = 16.0f;
    private static final float INLINE_ICON_SIZE = 14.0f;
    private static final float INDICATOR_WIDTH = 3.0f;
    private static final float INDICATOR_HEIGHT = 16.0f;
    private static final float BOTTOM_PADDING = UiTokens.SPACE_8;

    private static final FontStyle BRAND_TITLE_STYLE = FontStyle.of(13.0f, FontWeight.SEMIBOLD);
    private static final String VERSION_TEXT = "v" + HoptimizationMod.VERSION;
    private static final String[] SMALL_COUNT_STRINGS = {
        "", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    };

    private static final Category[] CATEGORIES = Category.values();
    private static final int NAV_COUNT = CATEGORIES.length + 1;
    private static final int SETTINGS_INDEX = CATEGORIES.length;

    private final Animation[] navHoverAnims = new Animation[NAV_COUNT];
    private final Animation searchHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation profileHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation indicatorYAnim = new Animation(0.0f, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);

    private final String[] categoryLabels = new String[CATEGORIES.length];
    private String searchPlaceholder = "";
    private String searchShortcut = "";
    private String defaultProfileLabel = "";
    private String settingsLabel = "";

    private float windowHeight = ClickGuiScreen.WINDOW_HEIGHT;

    /**
     * 构造侧边栏并预初始化所有悬停与指示条动画实例。
     */
    public ClickGuiSidebar() {
        for (int i = 0; i < NAV_COUNT; i++) {
            navHoverAnims[i] = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
        }
    }

    /**
     * 刷新本地化文本缓存，避免每帧调用 {@link I18n#format} 产生临时对象。
     */
    public void refreshLabels() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            categoryLabels[i] = I18n.format(CATEGORIES[i].getNameKey());
        }
        searchPlaceholder = I18n.format("ui.hoptimization.search.placeholder");
        searchShortcut = I18n.format("ui.hoptimization.search.shortcut");
        defaultProfileLabel = I18n.format("ui.hoptimization.profile.default");
        settingsLabel = I18n.format("ui.hoptimization.sidebar.settings");
    }

    /**
     * 立即将选中指示条定位到当前选中的分类或设置项（用于窗口初始化无跳变定位）。
     *
     * @param activeCategory 当前分类
     * @param settingsActive 是否选中设置页
     * @param windowHeight   窗口逻辑高度
     */
    public void snapSelection(Category activeCategory, boolean settingsActive, float windowHeight) {
        this.windowHeight = windowHeight;
        indicatorYAnim.snapTo(getItemLocalY(resolveNavIndex(activeCategory, settingsActive)));
    }

    /**
     * 平滑滑动选中指示条到目标分类或设置项（180ms easeOutCubic）。
     *
     * @param activeCategory 目标分类
     * @param settingsActive 是否选中设置页
     * @param windowHeight   窗口逻辑高度
     */
    public void animateSelectionTo(Category activeCategory, boolean settingsActive, float windowHeight) {
        this.windowHeight = windowHeight;
        indicatorYAnim.setTarget(getItemLocalY(resolveNavIndex(activeCategory, settingsActive)));
    }

    /**
     * 判断坐标是否处于顶部品牌可拖动区域。
     *
     * @param localMouseX 相对窗口左上角的逻辑 X
     * @param localMouseY 相对窗口左上角的逻辑 Y
     * @return 是否命中拖动区
     */
    public boolean isInBrandDragArea(float localMouseX, float localMouseY) {
        return localMouseX >= 0.0f && localMouseX <= WIDTH
            && localMouseY >= 0.0f && localMouseY <= BRAND_HEIGHT;
    }

    /**
     * 渲染侧边栏所有元素（纯主题 token + 窗口透明乘子，零热路径分配）。
     *
     * @param winX           窗口逻辑 X
     * @param winY           窗口逻辑 Y
     * @param winH           窗口逻辑高度
     * @param mouseX         逻辑鼠标 X
     * @param mouseY         逻辑鼠标 Y
     * @param windowAlpha    窗口整体不透明度 [0, 1]
     * @param theme          当前主题
     * @param activeCategory 当前激活分类
     * @param settingsActive 是否激活设置页
     * @param moduleManager  模块管理器（用于显示各分类已启用计数）
     * @param configManager  配置管理器（用于读取当前配置档名称）
     */
    public void render(
        float winX,
        float winY,
        float winH,
        float mouseX,
        float mouseY,
        float windowAlpha,
        ThemeData theme,
        Category activeCategory,
        boolean settingsActive,
        ModuleManager moduleManager,
        ConfigManager configManager
    ) {
        this.windowHeight = winH;
        renderBrandHeader(winX, winY, windowAlpha, theme);
        renderSearchAppearance(winX, winY, mouseX, mouseY, windowAlpha, theme);

        int selectedIdx = resolveNavIndex(activeCategory, settingsActive);
        float targetY = getItemLocalY(selectedIdx);
        // 若动画被窗口高度变化等外部因素偏离目标，保持平滑追随
        if (Float.compare(indicatorYAnim.getTarget(), targetY) != 0) {
            indicatorYAnim.setTarget(targetY);
        }
        float currentIndicatorY = winY + indicatorYAnim.get();
        float itemX = winX + ITEM_MARGIN_H;
        float itemW = WIDTH - ITEM_MARGIN_H * 2.0f;
        float radiusControl = theme.radiusControl();

        // 先绘制滑动中的选中底色与左侧 3x16 强调色指示条，再绘制各条目文字与图标
        int selectionBg = RenderUtil.withAlpha(theme.accentMuted(), windowAlpha);
        RenderUtil.drawRoundedRect(itemX, currentIndicatorY, itemW, ITEM_HEIGHT, radiusControl, selectionBg);

        float pillY = currentIndicatorY + (ITEM_HEIGHT - INDICATOR_HEIGHT) * 0.5f;
        int pillColor = RenderUtil.withAlpha(theme.accent(), windowAlpha);
        RenderUtil.drawRoundedRect(
            itemX, pillY, INDICATOR_WIDTH, INDICATOR_HEIGHT,
            theme.radiusPill(INDICATOR_WIDTH), pillColor
        );

        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();

        for (int i = 0; i < CATEGORIES.length; i++) {
            float itemY = winY + getItemLocalY(i);
            boolean selected = (i == selectedIdx);
            boolean hovered = !selected && isInside(mouseX, mouseY, itemX, itemY, itemW, ITEM_HEIGHT);
            navHoverAnims[i].setTarget(hovered ? 1.0f : 0.0f);
            float hoverT = navHoverAnims[i].get();

            if (!selected && hoverT > 0.005f) {
                int hoverBg = RenderUtil.withAlpha(theme.surface2(), hoverT * windowAlpha);
                RenderUtil.drawRoundedRect(itemX, itemY, itemW, ITEM_HEIGHT, radiusControl, hoverBg);
            }

            float proximity = computeSelectionProximity(itemY, currentIndicatorY);
            int iconColor = ThemeData.lerpColor(theme.textSecondary(), theme.accent(), proximity);
            int textColor = ThemeData.lerpColor(
                theme.textSecondary(),
                theme.textPrimary(),
                Math.max(proximity, hoverT)
            );

            float iconX = itemX + ITEM_PADDING_H;
            float iconY = itemY + (ITEM_HEIGHT - CATEGORY_ICON_SIZE) * 0.5f;
            RenderUtil.drawTintedIcon(
                ClickGuiIcons.forCategory(CATEGORIES[i]),
                iconX, iconY, CATEGORY_ICON_SIZE,
                RenderUtil.withAlpha(iconColor, windowAlpha)
            );

            float textX = iconX + CATEGORY_ICON_SIZE + UiTokens.SPACE_8;
            float textY = itemY + (ITEM_HEIGHT - bodyFont.getHeight()) * 0.5f;
            bodyFont.drawString(categoryLabels[i], textX, textY, RenderUtil.withAlpha(textColor, windowAlpha));

            int enabledCount = moduleManager != null ? moduleManager.getEnabledCount(CATEGORIES[i]) : 0;
            if (enabledCount > 0) {
                String countText = formatSmallCount(enabledCount);
                float countY = itemY + (ITEM_HEIGHT - captionFont.getHeight()) * 0.5f;
                captionFont.drawStringRight(
                    countText,
                    itemX + itemW - ITEM_PADDING_H,
                    countY,
                    RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
                );
            }
        }

        renderBottomSection(winX, winY, winH, mouseX, mouseY, windowAlpha, theme,
            selectedIdx == SETTINGS_INDEX, currentIndicatorY, configManager);
    }

    /**
     * 处理侧边栏点击；命中分类或设置项时返回处理结果。
     *
     * @param winX   窗口逻辑 X
     * @param winY   窗口逻辑 Y
     * @param winH   窗口逻辑高度
     * @param mouseX 逻辑鼠标 X
     * @param mouseY 逻辑鼠标 Y
     * @return 点击交互结果（可能为 {@link ClickResult#NONE}）
     */
    public ClickResult handleClick(float winX, float winY, float winH, float mouseX, float mouseY) {
        this.windowHeight = winH;
        float itemX = winX + ITEM_MARGIN_H;
        float itemW = WIDTH - ITEM_MARGIN_H * 2.0f;

        for (int i = 0; i < CATEGORIES.length; i++) {
            float itemY = winY + getItemLocalY(i);
            if (isInside(mouseX, mouseY, itemX, itemY, itemW, ITEM_HEIGHT)) {
                return ClickResult.category(CATEGORIES[i]);
            }
        }

        float profileY = winY + getProfileLocalY();
        if (isInside(mouseX, mouseY, itemX, profileY, itemW, ITEM_HEIGHT)) {
            return ClickResult.PROFILE_TRIGGERED;
        }

        float settingsY = winY + getItemLocalY(SETTINGS_INDEX);
        if (isInside(mouseX, mouseY, itemX, settingsY, itemW, ITEM_HEIGHT)) {
            return ClickResult.SETTINGS;
        }
        return ClickResult.NONE;
    }

    private void renderBrandHeader(float winX, float winY, float windowAlpha, ThemeData theme) {
        TtfFontRenderer brandFont = FontManager.get(BRAND_TITLE_STYLE);
        TtfFontRenderer captionFont = FontManager.caption();

        float textX = winX + UiTokens.SPACE_16;
        float titleY = winY + UiTokens.SPACE_12;
        brandFont.drawString(
            HoptimizationMod.NAME,
            textX,
            titleY,
            RenderUtil.withAlpha(theme.textPrimary(), windowAlpha)
        );
        captionFont.drawString(
            VERSION_TEXT,
            textX,
            titleY + brandFont.getHeight() + UiTokens.SPACE_2,
            RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
        );
    }

    private void renderSearchAppearance(
        float winX,
        float winY,
        float mouseX,
        float mouseY,
        float windowAlpha,
        ThemeData theme
    ) {
        float boxX = winX + UiTokens.SPACE_12;
        float boxY = winY + BRAND_HEIGHT;
        float boxW = WIDTH - UiTokens.SPACE_12 * 2.0f;

        boolean hovered = isInside(mouseX, mouseY, boxX, boxY, boxW, SEARCH_HEIGHT);
        searchHoverAnim.setTarget(hovered ? 1.0f : 0.0f);
        float hoverT = searchHoverAnim.get();

        int bg = ThemeData.lerpColor(theme.surface3(), theme.surface4(), hoverT * 0.6f);
        int border = theme.borderStrong();
        RenderUtil.drawRoundedRect(
            boxX, boxY, boxW, SEARCH_HEIGHT,
            theme.radiusControl(),
            RenderUtil.withAlpha(bg, windowAlpha),
            RenderUtil.withAlpha(border, windowAlpha),
            UiTokens.BORDER_WIDTH
        );

        float iconX = boxX + UiTokens.SPACE_8;
        float iconY = boxY + (SEARCH_HEIGHT - INLINE_ICON_SIZE) * 0.5f;
        RenderUtil.drawTintedIcon(
            ClickGuiIcons.SEARCH,
            iconX, iconY, INLINE_ICON_SIZE,
            RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
        );

        TtfFontRenderer captionFont = FontManager.caption();
        float textY = boxY + (SEARCH_HEIGHT - captionFont.getHeight()) * 0.5f;
        float shortcutW = captionFont.getWidth(searchShortcut);
        float placeholderX = iconX + INLINE_ICON_SIZE + UiTokens.SPACE_6;
        float maxPlaceholderW = Math.max(0.0f, boxX + boxW - UiTokens.SPACE_8 - shortcutW - UiTokens.SPACE_6 - placeholderX);

        captionFont.drawStringWithEllipsis(
            searchPlaceholder,
            placeholderX,
            textY,
            maxPlaceholderW,
            RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
        );
        captionFont.drawStringRight(
            searchShortcut,
            boxX + boxW - UiTokens.SPACE_8,
            textY,
            RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
        );
    }

    private void renderBottomSection(
        float winX,
        float winY,
        float winH,
        float mouseX,
        float mouseY,
        float windowAlpha,
        ThemeData theme,
        boolean settingsSelected,
        float currentIndicatorY,
        ConfigManager configManager
    ) {
        float dividerY = winY + getProfileLocalY() - UiTokens.SPACE_8 - UiTokens.BORDER_WIDTH;
        RenderUtil.drawRect(
            winX + UiTokens.SPACE_12,
            dividerY,
            WIDTH - UiTokens.SPACE_12 * 2.0f,
            UiTokens.BORDER_WIDTH,
            RenderUtil.withAlpha(theme.border(), windowAlpha)
        );

        float itemX = winX + ITEM_MARGIN_H;
        float itemW = WIDTH - ITEM_MARGIN_H * 2.0f;
        float radiusControl = theme.radiusControl();
        TtfFontRenderer bodyFont = FontManager.body();

        // 配置档切换外观入口（第 5 步接入完整配置档下拉 CRUD）
        float profileY = winY + getProfileLocalY();
        boolean profileHovered = isInside(mouseX, mouseY, itemX, profileY, itemW, ITEM_HEIGHT);
        profileHoverAnim.setTarget(profileHovered ? 1.0f : 0.0f);
        float profileHoverT = profileHoverAnim.get();

        if (profileHoverT > 0.005f) {
            RenderUtil.drawRoundedRect(
                itemX, profileY, itemW, ITEM_HEIGHT,
                radiusControl,
                RenderUtil.withAlpha(theme.surface2(), profileHoverT * windowAlpha)
            );
        }

        String rawProfile = configManager != null ? configManager.getActiveProfileName() : UiConfig.DEFAULT_PROFILE;
        String profileLabel = UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(rawProfile) ? defaultProfileLabel : rawProfile;
        int profileTextColor = ThemeData.lerpColor(theme.textSecondary(), theme.textPrimary(), profileHoverT);

        float chevronX = itemX + itemW - ITEM_PADDING_H - INLINE_ICON_SIZE;
        float chevronY = profileY + (ITEM_HEIGHT - INLINE_ICON_SIZE) * 0.5f;
        float profileTextX = itemX + ITEM_PADDING_H;
        float profileMaxW = Math.max(0.0f, chevronX - UiTokens.SPACE_8 - profileTextX);
        float profileTextY = profileY + (ITEM_HEIGHT - bodyFont.getHeight()) * 0.5f;
        bodyFont.drawStringWithEllipsis(
            profileLabel,
            profileTextX,
            profileTextY,
            profileMaxW,
            RenderUtil.withAlpha(profileTextColor, windowAlpha)
        );
        RenderUtil.drawTintedIcon(
            ClickGuiIcons.CHEVRON_DOWN,
            chevronX, chevronY, INLINE_ICON_SIZE,
            RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
        );

        // 底部"设置"入口（样式同分类项，参与滑动指示条）
        float settingsY = winY + getItemLocalY(SETTINGS_INDEX);
        boolean settingsHovered = !settingsSelected && isInside(mouseX, mouseY, itemX, settingsY, itemW, ITEM_HEIGHT);
        navHoverAnims[SETTINGS_INDEX].setTarget(settingsHovered ? 1.0f : 0.0f);
        float settingsHoverT = navHoverAnims[SETTINGS_INDEX].get();

        if (!settingsSelected && settingsHoverT > 0.005f) {
            RenderUtil.drawRoundedRect(
                itemX, settingsY, itemW, ITEM_HEIGHT,
                radiusControl,
                RenderUtil.withAlpha(theme.surface2(), settingsHoverT * windowAlpha)
            );
        }

        float proximity = computeSelectionProximity(settingsY, currentIndicatorY);
        int iconColor = ThemeData.lerpColor(theme.textSecondary(), theme.accent(), proximity);
        int textColor = ThemeData.lerpColor(
            theme.textSecondary(),
            theme.textPrimary(),
            Math.max(proximity, settingsHoverT)
        );

        float iconX = itemX + ITEM_PADDING_H;
        float iconY = settingsY + (ITEM_HEIGHT - CATEGORY_ICON_SIZE) * 0.5f;
        RenderUtil.drawTintedIcon(
            ClickGuiIcons.SETTINGS,
            iconX, iconY, CATEGORY_ICON_SIZE,
            RenderUtil.withAlpha(iconColor, windowAlpha)
        );
        float settingsTextY = settingsY + (ITEM_HEIGHT - bodyFont.getHeight()) * 0.5f;
        bodyFont.drawString(
            settingsLabel,
            iconX + CATEGORY_ICON_SIZE + UiTokens.SPACE_8,
            settingsTextY,
            RenderUtil.withAlpha(textColor, windowAlpha)
        );
    }

    private float getItemLocalY(int navIndex) {
        if (navIndex == SETTINGS_INDEX) {
            return windowHeight - BOTTOM_PADDING - ITEM_HEIGHT;
        }
        float listStartY = BRAND_HEIGHT + SEARCH_HEIGHT + UiTokens.SPACE_12;
        return listStartY + navIndex * (ITEM_HEIGHT + ITEM_GAP);
    }

    private float getProfileLocalY() {
        return getItemLocalY(SETTINGS_INDEX) - ITEM_GAP - ITEM_HEIGHT;
    }

    private static int resolveNavIndex(Category activeCategory, boolean settingsActive) {
        if (settingsActive) {
            return SETTINGS_INDEX;
        }
        if (activeCategory != null) {
            return activeCategory.ordinal();
        }
        return 0;
    }

    private static float computeSelectionProximity(float itemY, float indicatorY) {
        float dist = Math.abs(itemY - indicatorY);
        if (dist >= ITEM_HEIGHT) {
            return 0.0f;
        }
        return 1.0f - (dist / ITEM_HEIGHT);
    }

    private static String formatSmallCount(int count) {
        if (count >= 0 && count < SMALL_COUNT_STRINGS.length) {
            return SMALL_COUNT_STRINGS[count];
        }
        return Integer.toString(count);
    }

    private static boolean isInside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /**
     * 侧边栏点击交互结果。
     */
    public static final class ClickResult {
        public static final ClickResult NONE = new ClickResult(Kind.NONE, null);
        public static final ClickResult SETTINGS = new ClickResult(Kind.SETTINGS, null);
        public static final ClickResult PROFILE_TRIGGERED = new ClickResult(Kind.PROFILE_TRIGGERED, null);

        private static final ClickResult[] CATEGORY_RESULTS = new ClickResult[CATEGORIES.length];

        static {
            for (int i = 0; i < CATEGORIES.length; i++) {
                CATEGORY_RESULTS[i] = new ClickResult(Kind.CATEGORY, CATEGORIES[i]);
            }
        }

        public enum Kind {
            NONE,
            CATEGORY,
            SETTINGS,
            PROFILE_TRIGGERED
        }

        private final Kind kind;
        private final Category category;

        private ClickResult(Kind kind, Category category) {
            this.kind = kind;
            this.category = category;
        }

        static ClickResult category(Category category) {
            return category != null ? CATEGORY_RESULTS[category.ordinal()] : NONE;
        }

        public Kind getKind() {
            return kind;
        }

        public Category getCategory() {
            return category;
        }
    }
}
