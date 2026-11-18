package com.hoptimization.ui.clickgui;

import com.hoptimization.core.module.Category;
import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;

/**
 * ClickGUI 右侧内容面板头部（高 56，内边距 20）：
 * 显示当前分类标题与模块数量副标题，以及右上角“全部 | 已启用”平滑滑动分段控件。
 */
public final class ClickGuiContentHeader {
    public static final float HEIGHT = 56.0f;
    private static final float PADDING_H = UiTokens.SPACE_20;
    private static final float SEGMENTED_HEIGHT = 24.0f;
    private static final float SEGMENTED_INNER_PAD = UiTokens.SPACE_2;

    private final Animation filterSlideAnim = new Animation(0.0f, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);

    private String filterAllLabel = "";
    private String filterEnabledLabel = "";
    private String settingsTitle = "";
    private String settingsSubtitle = "";
    private String searchResultsTitle = "";
    private String cachedTitle = "";
    private String cachedSubtitle = "";

    private Category lastCategory;
    private boolean lastSettingsActive = false;
    private boolean lastSearchActive = false;
    private int lastModuleCount = -1;
    private float segmentWidth = 48.0f;

    /**
     * 刷新本地化静态文案与分段控件宽度，避免在渲染热路径产生分配。
     */
    public void refreshLabels() {
        filterAllLabel = LanguageModule.format("ui.hoptimization.filter.all");
        filterEnabledLabel = LanguageModule.format("ui.hoptimization.filter.enabled");
        settingsTitle = LanguageModule.format("ui.hoptimization.settings.title");
        settingsSubtitle = LanguageModule.format("ui.hoptimization.settings.subtitle");
        searchResultsTitle = LanguageModule.format("ui.hoptimization.search.results");

        TtfFontRenderer bodyFont = FontManager.body();
        float maxLabelW = Math.max(bodyFont.getWidth(filterAllLabel), bodyFont.getWidth(filterEnabledLabel));
        // 按 4px 网格取整分段单格宽度，保持视觉均衡
        float rawSegW = maxLabelW + UiTokens.SPACE_16;
        segmentWidth = Math.max(48.0f, (float) (Math.ceil(rawSegW / 4.0) * 4.0));

        // 强制下次渲染同步刷新标题与计数文本
        lastModuleCount = -1;
    }

    /**
     * 立即同步分段控件选中态位置。
     *
     * @param enabledOnly {@code true} 选中“已启用”，{@code false} 选中“全部”
     */
    public void snapFilter(boolean enabledOnly) {
        filterSlideAnim.snapTo(enabledOnly ? 1.0f : 0.0f);
    }

    /**
     * 更新头部标题、模块数副标题并渲染内容头部。
     *
     * @param contentX       内容面板逻辑 X
     * @param contentY       内容面板逻辑 Y
     * @param contentW       内容面板逻辑宽度
     * @param mouseX         逻辑鼠标 X
     * @param mouseY         逻辑鼠标 Y
     * @param windowAlpha    窗口不透明度 [0, 1]
     * @param theme          当前主题
     * @param activeCategory 当前分类
     * @param settingsActive 是否处于设置页
     * @param moduleCount    当前展示模块数
     * @param enabledOnly    当前是否仅筛选已启用模块
     */
    public void render(
        float contentX,
        float contentY,
        float contentW,
        float mouseX,
        float mouseY,
        float windowAlpha,
        ThemeData theme,
        Category activeCategory,
        boolean settingsActive,
        int moduleCount,
        boolean enabledOnly
    ) {
        render(
            contentX, contentY, contentW,
            mouseX, mouseY, windowAlpha, theme,
            activeCategory, settingsActive, false,
            moduleCount, enabledOnly
        );
    }

    /**
     * 更新头部标题、模块数/匹配数副标题并渲染内容头部。
     *
     * @param contentX       内容面板逻辑 X
     * @param contentY       内容面板逻辑 Y
     * @param contentW       内容面板逻辑宽度
     * @param mouseX         逻辑鼠标 X
     * @param mouseY         逻辑鼠标 Y
     * @param windowAlpha    窗口不透明度 [0, 1]
     * @param theme          当前主题
     * @param activeCategory 当前分类
     * @param settingsActive 是否处于设置页
     * @param searchActive   是否处于全局搜索结果视图
     * @param moduleCount    当前展示模块数（或匹配数）
     * @param enabledOnly    当前是否仅筛选已启用模块
     */
    public void render(
        float contentX,
        float contentY,
        float contentW,
        float mouseX,
        float mouseY,
        float windowAlpha,
        ThemeData theme,
        Category activeCategory,
        boolean settingsActive,
        boolean searchActive,
        int moduleCount,
        boolean enabledOnly
    ) {
        updateTitleCache(activeCategory, settingsActive, searchActive, moduleCount);

        TtfFontRenderer titleFont = FontManager.title();
        TtfFontRenderer captionFont = FontManager.caption();

        float textX = contentX + PADDING_H;
        float titleY = contentY + UiTokens.SPACE_12;
        titleFont.drawString(
            cachedTitle,
            textX,
            titleY,
            RenderUtil.withAlpha(theme.textPrimary(), windowAlpha)
        );
        captionFont.drawString(
            cachedSubtitle,
            textX,
            titleY + titleFont.getHeight() + UiTokens.SPACE_2,
            RenderUtil.withAlpha(theme.textTertiary(), windowAlpha)
        );

        if (searchActive || !settingsActive) {
            filterSlideAnim.setTarget(enabledOnly ? 1.0f : 0.0f);
            renderSegmentedControl(contentX, contentY, contentW, windowAlpha, theme);
        }
    }

    /**
     * 处理内容头部右上角“全部 | 已启用”分段控件点击。
     *
     * @param contentX       内容面板逻辑 X
     * @param contentY       内容面板逻辑 Y
     * @param contentW       内容面板逻辑宽度
     * @param mouseX         逻辑鼠标 X
     * @param mouseY         逻辑鼠标 Y
     * @param settingsActive 是否处于设置页
     * @return {@code 0} 点击“全部”，{@code 1} 点击“已启用”，{@code -1} 未命中
     */
    public int handleClick(
        float contentX,
        float contentY,
        float contentW,
        float mouseX,
        float mouseY,
        boolean settingsActive
    ) {
        if (settingsActive) {
            return -1;
        }
        float totalW = segmentWidth * 2.0f + SEGMENTED_INNER_PAD * 2.0f;
        float segX = contentX + contentW - PADDING_H - totalW;
        float segY = contentY + (HEIGHT - SEGMENTED_HEIGHT) * 0.5f;
        if (mouseX < segX || mouseX > segX + totalW || mouseY < segY || mouseY > segY + SEGMENTED_HEIGHT) {
            return -1;
        }
        return mouseX < (segX + totalW * 0.5f) ? 0 : 1;
    }

    private void renderSegmentedControl(
        float contentX,
        float contentY,
        float contentW,
        float windowAlpha,
        ThemeData theme
    ) {
        float totalW = segmentWidth * 2.0f + SEGMENTED_INNER_PAD * 2.0f;
        float segX = contentX + contentW - PADDING_H - totalW;
        float segY = contentY + (HEIGHT - SEGMENTED_HEIGHT) * 0.5f;
        float outerRadius = theme.radiusControl();

        RenderUtil.drawRoundedRect(
            segX, segY, totalW, SEGMENTED_HEIGHT,
            outerRadius,
            RenderUtil.withAlpha(theme.surface3(), windowAlpha)
        );

        float slideT = filterSlideAnim.get();
        float knobX = segX + SEGMENTED_INNER_PAD + slideT * segmentWidth;
        float knobY = segY + SEGMENTED_INNER_PAD;
        float knobH = SEGMENTED_HEIGHT - SEGMENTED_INNER_PAD * 2.0f;
        float knobRadius = theme.nestedRadius(outerRadius, SEGMENTED_INNER_PAD);

        RenderUtil.drawRoundedRect(
            knobX, knobY, segmentWidth, knobH,
            knobRadius,
            RenderUtil.withAlpha(theme.surface4(), windowAlpha)
        );

        TtfFontRenderer bodyFont = FontManager.body();
        float textY = segY + (SEGMENTED_HEIGHT - bodyFont.getHeight()) * 0.5f;

        int allColor = ThemeData.lerpColor(theme.textPrimary(), theme.textSecondary(), slideT);
        int enabledColor = ThemeData.lerpColor(theme.textSecondary(), theme.textPrimary(), slideT);

        float firstCenterX = segX + SEGMENTED_INNER_PAD + segmentWidth * 0.5f;
        float secondCenterX = firstCenterX + segmentWidth;

        bodyFont.drawStringCentered(filterAllLabel, firstCenterX, textY, RenderUtil.withAlpha(allColor, windowAlpha));
        bodyFont.drawStringCentered(
            filterEnabledLabel,
            secondCenterX,
            textY,
            RenderUtil.withAlpha(enabledColor, windowAlpha)
        );
    }

    private void updateTitleCache(Category activeCategory, boolean settingsActive, boolean searchActive, int moduleCount) {
        if (searchActive) {
            if (!lastSearchActive || lastModuleCount != moduleCount || cachedTitle.isEmpty()) {
                lastSearchActive = true;
                lastSettingsActive = false;
                lastCategory = null;
                lastModuleCount = moduleCount;
                cachedTitle = searchResultsTitle;
                cachedSubtitle = LanguageModule.format("ui.hoptimization.search.matches", Integer.valueOf(moduleCount));
            }
            return;
        }
        if (settingsActive) {
            if (lastSearchActive || !lastSettingsActive || cachedTitle.isEmpty()) {
                lastSearchActive = false;
                lastSettingsActive = true;
                lastCategory = null;
                lastModuleCount = -1;
                cachedTitle = settingsTitle;
                cachedSubtitle = settingsSubtitle;
            }
            return;
        }
        Category resolved = activeCategory != null ? activeCategory : Category.COMBAT;
        if (lastSearchActive || lastSettingsActive || lastCategory != resolved || lastModuleCount != moduleCount) {
            lastSearchActive = false;
            lastSettingsActive = false;
            lastCategory = resolved;
            lastModuleCount = moduleCount;
            cachedTitle = LanguageModule.format(resolved.getNameKey());
            cachedSubtitle = LanguageModule.format("ui.hoptimization.module.count", Integer.valueOf(moduleCount));
        }
    }
}
