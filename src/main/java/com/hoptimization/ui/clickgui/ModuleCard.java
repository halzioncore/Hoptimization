package com.hoptimization.ui.clickgui;

import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.KeybindSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.core.setting.Setting;
import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.component.ColorComponent;
import com.hoptimization.ui.component.DropdownComponent;
import com.hoptimization.ui.component.KeybindComponent;
import com.hoptimization.ui.component.PopupHost;
import com.hoptimization.ui.component.SegmentedComponent;
import com.hoptimization.ui.component.SliderComponent;
import com.hoptimization.ui.component.SwitchComponent;
import com.hoptimization.ui.component.TooltipManager;
import com.hoptimization.ui.component.UiComponent;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模块卡片组件：支持 52px 收起态展示、开关与按键胶囊、平滑展开/收起（180ms）、
 * 定义顺序设置控件（含 {@code visibleWhen} 高度动画）、中键快捷按键绑定、
 * 不可开关模块（{@code toggleable = false}）与作弊模式 40% 置灰锁定及提示。
 */
public final class ModuleCard {
    public static final float COLLAPSED_HEIGHT = 52.0f;
    private static final float PADDING_H = UiTokens.PADDING_CARD;
    private static final float CHEVRON_SIZE = 14.0f;
    private static final float CHEVRON_ROTATION_DEG = 180.0f;
    private static final float KEYBIND_PILL_HEIGHT = 18.0f;
    private static final float KEYBIND_PILL_MIN_W = 20.0f;
    private static final float KEYBIND_PILL_PAD_H = UiTokens.SPACE_6;
    private static final float ROW_HEIGHT_DEFAULT = 32.0f;
    private static final float ROW_HEIGHT_SLIDER = 40.0f;
    private static final float BODY_TOP_PAD = UiTokens.SPACE_4;
    private static final float BODY_BOTTOM_PAD = UiTokens.SPACE_4;
    private static final float LOCKED_ALPHA = UiComponent.DISABLED_ALPHA;
    private static final float MIN_HIT_SIZE = UiComponent.MIN_HIT_SIZE;

    private final Module module;
    private final PopupHost popupHost;
    private final SwitchComponent headerSwitch;
    private final List<SettingRow> settingRows;

    private final Animation expandAnim = new Animation(0.0f, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);
    private final Animation headerHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation enabledColorAnim;
    private final Animation availabilityAlphaAnim;
    private final Animation pillListenAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);

    private boolean expanded = false;
    private boolean listeningInlineKeybind = false;
    private boolean syncingSwitch = false;
    private boolean pressedHeaderSwitch = false;
    private SettingRow activeDragRow = null;

    private float layoutX = 0.0f;
    private float layoutY = 0.0f;
    private float layoutW = 0.0f;
    private float layoutH = COLLAPSED_HEIGHT;
    private float currentBodyH = 0.0f;

    private float lastRenderX = 0.0f;
    private float lastRenderY = 0.0f;
    private float lastRenderW = 0.0f;
    private float lastRenderH = COLLAPSED_HEIGHT;
    private float lastPillX = 0.0f;
    private float lastPillY = 0.0f;
    private float lastPillW = 0.0f;
    private float lastPillH = KEYBIND_PILL_HEIGHT;
    private boolean lastPillVisible = false;
    private float lastSwitchX = 0.0f;
    private float lastSwitchY = 0.0f;

    private String cachedName = "";
    private String cachedDesc = "";
    private String cachedIdSearch = "";
    private String cachedListeningLabel = "";
    private String cachedCheatLockedLabel = "";

    private String activeNormalizedQuery = "";
    private boolean matchesSearch = true;
    private boolean hasNameHighlight = false;
    private String namePrefix = "";
    private String nameMatch = "";
    private String nameSuffix = "";
    private boolean hasDescHighlight = false;
    private String descPrefix = "";
    private String descMatch = "";
    private String descSuffix = "";

    /**
     * 为指定 {@link Module} 创建交互卡片实例并预构建所有设置项控件。
     *
     * @param module    所属模块
     * @param popupHost 顶层弹出层宿主（用于下拉框与取色器）
     */
    public ModuleCard(final Module module, PopupHost popupHost) {
        if (module == null) {
            throw new IllegalArgumentException("ModuleCard module must not be null");
        }
        this.module = module;
        this.popupHost = popupHost;

        if (module.isToggleable()) {
            this.headerSwitch = new SwitchComponent(module.isEnabled(), new SwitchComponent.ChangeListener() {
                @Override
                public void onChanged(boolean checked) {
                    if (syncingSwitch) {
                        return;
                    }
                    if (!module.isAvailable()) {
                        syncHeaderSwitchState();
                        return;
                    }
                    module.setEnabled(checked);
                    syncHeaderSwitchState();
                }
            });
        } else {
            this.headerSwitch = null;
        }

        this.enabledColorAnim = new Animation(
            module.isEnabled() ? 1.0f : 0.0f,
            AnimDuration.FAST,
            Easing.EASE_OUT_CUBIC
        );
        this.availabilityAlphaAnim = new Animation(
            module.isAvailable() ? 1.0f : LOCKED_ALPHA,
            AnimDuration.FAST,
            Easing.EASE_OUT_CUBIC
        );

        List<Setting<?>> settings = module.getSettings();
        this.settingRows = new ArrayList<SettingRow>(settings.size());
        for (int i = 0; i < settings.size(); i++) {
            Setting<?> setting = settings.get(i);
            UiComponent control = createControlForSetting(setting, popupHost);
            if (control != null) {
                this.settingRows.add(new SettingRow(setting, control));
            }
        }
    }

    /**
     * 返回此卡片关联的模块实例。
     *
     * @return 模块实例
     */
    public Module getModule() {
        return this.module;
    }

    /**
     * 刷新模块名称、描述、设置标签与通用提示文案缓存（避免在绘制热路径产生分配）。
     */
    public void refreshLabels() {
        this.cachedName = safeString(this.module.getDisplayName());
        this.cachedDesc = safeString(this.module.getDisplayDescription());
        this.cachedIdSearch = this.module.getId().replace('_', ' ').toLowerCase(Locale.ROOT);
        this.cachedListeningLabel = LanguageModule.format("setting.hoptimization.common.keybind.listening");
        this.cachedCheatLockedLabel = LanguageModule.format("ui.hoptimization.cheat_locked");

        for (int i = 0; i < this.settingRows.size(); i++) {
            SettingRow row = this.settingRows.get(i);
            row.cachedLabel = safeString(row.setting.getDisplayName());
            row.cachedDesc = row.setting.hasDescription() ? safeString(row.setting.getDisplayDescription()) : "";
            row.cachedIdSearch = row.setting.getId().replace('_', ' ').toLowerCase(Locale.ROOT);
            row.component.refreshLabels();
        }
        updateSearchQuery(this.activeNormalizedQuery);
    }

    /**
     * 更新搜索关键字并预计算匹配标记与高亮片段（仅在搜索文本或文案变化时执行）。
     *
     * @param rawQuery 搜索关键字
     */
    public void updateSearchQuery(String rawQuery) {
        String normalized = rawQuery == null ? "" : rawQuery.trim();
        this.activeNormalizedQuery = normalized;

        if (normalized.isEmpty()) {
            this.matchesSearch = true;
            this.hasNameHighlight = false;
            this.hasDescHighlight = false;
            for (int i = 0; i < this.settingRows.size(); i++) {
                this.settingRows.get(i).clearHighlight();
            }
            return;
        }

        String lowerQuery = normalized.toLowerCase(Locale.ROOT);
        boolean matched = false;

        int nameIdx = indexOfIgnoreCase(this.cachedName, normalized);
        if (nameIdx >= 0) {
            this.hasNameHighlight = true;
            this.namePrefix = this.cachedName.substring(0, nameIdx);
            this.nameMatch = this.cachedName.substring(nameIdx, nameIdx + normalized.length());
            this.nameSuffix = this.cachedName.substring(nameIdx + normalized.length());
            matched = true;
        } else {
            this.hasNameHighlight = false;
        }

        int descIdx = indexOfIgnoreCase(this.cachedDesc, normalized);
        if (descIdx >= 0) {
            this.hasDescHighlight = true;
            this.descPrefix = this.cachedDesc.substring(0, descIdx);
            this.descMatch = this.cachedDesc.substring(descIdx, descIdx + normalized.length());
            this.descSuffix = this.cachedDesc.substring(descIdx + normalized.length());
            matched = true;
        } else {
            this.hasDescHighlight = false;
        }

        if (this.cachedIdSearch.contains(lowerQuery)) {
            matched = true;
        }

        for (int i = 0; i < this.settingRows.size(); i++) {
            SettingRow row = this.settingRows.get(i);
            if (row.updateHighlight(normalized, lowerQuery)) {
                matched = true;
            }
        }
        this.matchesSearch = matched;
    }

    /**
     * 返回当前卡片是否命中最近一次 {@link #updateSearchQuery(String)} 匹配。
     *
     * @return 是否命中搜索
     */
    public boolean matchesSearch() {
        return this.matchesSearch;
    }

    /**
     * 返回当前卡片是否处于展开状态。
     *
     * @return 是否展开
     */
    public boolean isExpanded() {
        return this.expanded;
    }

    /**
     * 设置展开/收起状态；立即对齐可选，正常交互采用 180ms 平滑展开。
     *
     * @param expanded  目标展开状态
     * @param immediate {@code true} 立即跳至目标高度，{@code false} 播放动画
     */
    public void setExpanded(boolean expanded, boolean immediate) {
        if (this.module.getSettings().isEmpty()) {
            expanded = false;
        }
        if (this.expanded == expanded && !immediate) {
            return;
        }
        this.expanded = expanded;
        if (!expanded) {
            cancelSettingInputCaptures();
            closeOwnedPopups();
        }
        if (immediate) {
            this.expandAnim.snapTo(expanded ? 1.0f : 0.0f);
            for (int i = 0; i < this.settingRows.size(); i++) {
                SettingRow row = this.settingRows.get(i);
                boolean vis = row.setting.isVisible();
                row.lastVisible = vis;
                row.visibilityAnim.snapTo(vis ? 1.0f : 0.0f);
            }
        } else {
            this.expandAnim.setTarget(expanded ? 1.0f : 0.0f);
        }
    }

    /**
     * 切换卡片展开/收起状态。
     */
    public void toggleExpanded() {
        setExpanded(!this.expanded, false);
    }

    /**
     * 第一阶段布局计算（零分配）：推进高度与显隐动画并确定卡片逻辑边界，供滚动裁剪与视口剔除使用。
     *
     * @param x     逻辑左 X
     * @param y     未滚动前的逻辑上 Y
     * @param width 卡片逻辑宽度
     * @return 本帧卡片总逻辑高度
     */
    public float updateLayout(float x, float y, float width) {
        boolean available = this.module.isAvailable();
        if (!available) {
            if (this.listeningInlineKeybind) {
                this.listeningInlineKeybind = false;
            }
            cancelSettingInputCaptures();
            closeOwnedPopups();
        }
        this.availabilityAlphaAnim.setTarget(available ? 1.0f : LOCKED_ALPHA);

        syncHeaderSwitchState();
        this.enabledColorAnim.setTarget(this.module.isEnabled() ? 1.0f : 0.0f);
        this.expandAnim.setTarget(this.expanded ? 1.0f : 0.0f);
        float expandT = clamp01(this.expandAnim.get());

        float totalRowsH = 0.0f;
        float rowLocalY = COLLAPSED_HEIGHT + UiTokens.BORDER_WIDTH + BODY_TOP_PAD;
        boolean settledCollapsed = !this.expanded && expandT <= 0.001f;

        for (int i = 0; i < this.settingRows.size(); i++) {
            SettingRow row = this.settingRows.get(i);
            boolean visNow = row.setting.isVisible();
            if (row.lastVisible && !visNow) {
                cancelRowInputCapture(row);
                closeRowPopupIfOpen(row);
            }
            row.lastVisible = visNow;

            if (settledCollapsed) {
                row.visibilityAnim.snapTo(visNow ? 1.0f : 0.0f);
            } else {
                row.visibilityAnim.setTarget(visNow ? 1.0f : 0.0f);
            }

            float visT = clamp01(row.visibilityAnim.get());
            row.rowH = row.baseHeight * visT;
            row.localYFromTop = rowLocalY;
            rowLocalY += row.rowH;
            totalRowsH += row.rowH;
        }

        if (totalRowsH > 0.5f && expandT > 0.001f) {
            float fullBodyH = UiTokens.BORDER_WIDTH + BODY_TOP_PAD + totalRowsH + BODY_BOTTOM_PAD;
            this.currentBodyH = fullBodyH * expandT;
        } else {
            this.currentBodyH = 0.0f;
        }

        this.layoutX = x;
        this.layoutY = y;
        this.layoutW = width;
        this.layoutH = COLLAPSED_HEIGHT + this.currentBodyH;
        return this.layoutH;
    }

    /**
     * 返回最近一次 {@link #updateLayout(float, float, float)} 计算的未滚动逻辑上 Y。
     *
     * @return 逻辑上 Y
     */
    public float getLayoutY() {
        return this.layoutY;
    }

    /**
     * 返回最近一次 {@link #updateLayout(float, float, float)} 计算的卡片逻辑高度。
     *
     * @return 逻辑高度
     */
    public float getHeight() {
        return this.layoutH;
    }

    /**
     * 渲染可见区域内的模块卡片及其展开设置项。
     *
     * @param x              逻辑左 X
     * @param y              滚动偏移后的逻辑上 Y
     * @param width          卡片逻辑宽度
     * @param mouseX         逻辑鼠标 X（若被弹出层遮挡则为屏外哨兵值）
     * @param mouseY         逻辑鼠标 Y
     * @param windowAlpha    父窗口不透明度 [0, 1]
     * @param theme          当前主题快照
     * @param tooltipManager 提示管理器（用于作弊模式锁定提示）
     */
    public void render(
        float x,
        float y,
        float width,
        float mouseX,
        float mouseY,
        float windowAlpha,
        ThemeData theme,
        TooltipManager tooltipManager
    ) {
        this.lastRenderX = x;
        this.lastRenderY = y;
        this.lastRenderW = width;
        this.lastRenderH = this.layoutH;

        float availAlpha = clamp01(this.availabilityAlphaAnim.get());
        float cardAlpha = clamp01(windowAlpha) * availAlpha;
        if (cardAlpha <= 0.001f || width <= 0.0f || this.layoutH <= 0.0f) {
            return;
        }

        boolean available = this.module.isAvailable();
        boolean insideCard = isInside(mouseX, mouseY, x, y, width, this.layoutH);
        boolean insideHeader = isInside(mouseX, mouseY, x, y, width, COLLAPSED_HEIGHT);

        if (!available && insideCard && tooltipManager != null && !this.cachedCheatLockedLabel.isEmpty()) {
            tooltipManager.request(this.module, this.cachedCheatLockedLabel);
        }

        this.headerHoverAnim.setTarget((available && insideHeader) ? 1.0f : 0.0f);
        float hoverT = clamp01(this.headerHoverAnim.get());
        float effMouseX = available ? mouseX : -99999.0f;
        float effMouseY = available ? mouseY : -99999.0f;

        float cardRadius = theme.radiusCard();
        boolean frosted = com.hoptimization.ui.theme.ThemeManager.isFrostedGlass();
        float surfaceAlpha = frosted
            ? (cardAlpha * (theme.getMode() == com.hoptimization.ui.theme.ThemeMode.LIGHT ? 0.68f : 0.58f))
            : cardAlpha;
        if (this.currentBodyH < 1.0f) {
            int bg = ColorAnimation.lerpArgb(theme.surface2(), theme.surface3(), hoverT);
            RenderUtil.drawRoundedRect(x, y, width, this.layoutH, cardRadius, RenderUtil.withAlpha(bg, surfaceAlpha));
        } else {
            RenderUtil.drawRoundedRect(
                x, y, width, this.layoutH,
                cardRadius,
                RenderUtil.withAlpha(theme.surface2(), surfaceAlpha)
            );
            if (hoverT > 0.005f) {
                int headerBg = ColorAnimation.lerpArgb(theme.surface2(), theme.surface3(), hoverT);
                RenderUtil.drawRoundedRect(
                    x, y, width, Math.min(this.layoutH, COLLAPSED_HEIGHT),
                    cardRadius, cardRadius, 0.0f, 0.0f,
                    RenderUtil.withAlpha(headerBg, surfaceAlpha)
                );
            }
        }

        renderHeader(x, y, width, effMouseX, effMouseY, cardAlpha, theme);

        float expandT = clamp01(this.expandAnim.get());
        if (expandT > 0.002f && this.currentBodyH > UiTokens.BORDER_WIDTH + 0.5f) {
            renderExpandedBody(x, y, width, effMouseX, effMouseY, cardAlpha, expandT, theme, tooltipManager);
        }

        if (frosted || theme.style().isCardBorder()) {
            int borderColor = frosted
                ? RenderUtil.withAlpha(theme.borderStrong(), cardAlpha * 0.85f)
                : RenderUtil.withAlpha(theme.border(), cardAlpha);
            RenderUtil.drawRoundedBorder(
                x, y, width, this.layoutH,
                cardRadius, UiTokens.BORDER_WIDTH,
                borderColor
            );
        }
    }

    /**
     * 处理卡片范围内的鼠标点击（0=左键，1=右键，2=中键）。
     *
     * @param mouseX      逻辑鼠标 X
     * @param mouseY      逻辑鼠标 Y
     * @param mouseButton LWJGL 鼠标按键索引
     * @return 是否消费该点击事件
     */
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!isInside(mouseX, mouseY, this.lastRenderX, this.lastRenderY, this.lastRenderW, this.lastRenderH)) {
            return false;
        }

        boolean available = this.module.isAvailable();
        if (mouseY <= this.lastRenderY + COLLAPSED_HEIGHT) {
            if (this.listeningInlineKeybind) {
                if (mouseButton == 0 && this.lastPillVisible
                    && hitTestMin20(mouseX, mouseY, this.lastPillX, this.lastPillY, this.lastPillW, this.lastPillH)) {
                    this.listeningInlineKeybind = false;
                    return true;
                }
                if (mouseButton == 2 && available) {
                    return true;
                }
                this.listeningInlineKeybind = false;
            }

            if (mouseButton == 2) {
                if (available) {
                    cancelSettingInputCaptures();
                    this.listeningInlineKeybind = true;
                }
                return true;
            }

            if (mouseButton == 1) {
                if (!this.module.getSettings().isEmpty()) {
                    toggleExpanded();
                }
                return true;
            }

            if (mouseButton == 0) {
                if (this.headerSwitch != null) {
                    boolean inSwitch = hitTestMin20(
                        mouseX, mouseY,
                        this.lastSwitchX, this.lastSwitchY,
                        SwitchComponent.WIDTH, SwitchComponent.HEIGHT
                    );
                    if (inSwitch) {
                        if (available && this.headerSwitch.mouseClicked(mouseX, mouseY, mouseButton)) {
                            this.pressedHeaderSwitch = true;
                        }
                        return true;
                    }
                }

                if (this.module.hasCustomSettings()) {
                    toggleExpanded();
                } else if (this.module.isToggleable() && available) {
                    this.module.toggle();
                    syncHeaderSwitchState();
                }
                return true;
            }
            return false;
        }

        if (this.listeningInlineKeybind) {
            this.listeningInlineKeybind = false;
        }

        if (!available) {
            if (mouseButton == 1 && !this.module.getSettings().isEmpty()) {
                toggleExpanded();
            }
            return true;
        }

        if (!this.expanded || this.expandAnim.get() < 0.25f) {
            return true;
        }

        SettingRow consumedRow = null;
        for (int i = 0; i < this.settingRows.size(); i++) {
            SettingRow row = this.settingRows.get(i);
            if (!row.setting.isVisible() || row.visibilityAnim.get() < 0.25f) {
                continue;
            }
            if (row.component.mouseClicked(mouseX, mouseY, mouseButton)) {
                consumedRow = row;
                this.activeDragRow = row;
                break;
            }
        }

        if (consumedRow != null) {
            for (int i = 0; i < this.settingRows.size(); i++) {
                SettingRow row = this.settingRows.get(i);
                if (row != consumedRow) {
                    cancelRowInputCapture(row);
                }
            }
            return true;
        }

        if (mouseButton == 1) {
            toggleExpanded();
            return true;
        }
        if (mouseButton == 2) {
            cancelSettingInputCaptures();
            this.listeningInlineKeybind = true;
            return true;
        }
        return true;
    }

    /**
     * 处理鼠标拖动事件（主要转发给正在拖动的滑条或行内输入框）。
     *
     * @param mouseX      逻辑鼠标 X
     * @param mouseY      逻辑鼠标 Y
     * @param mouseButton 鼠标按键索引
     * @return 是否消费
     */
    public boolean mouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (!this.module.isAvailable()) {
            this.activeDragRow = null;
            return false;
        }
        if (this.activeDragRow != null) {
            return this.activeDragRow.component.mouseDragged(mouseX, mouseY, mouseButton);
        }
        return false;
    }

    /**
     * 处理鼠标释放事件（完成卡片顶部开关点击或设置滑条拖动）。
     *
     * @param mouseX      逻辑鼠标 X
     * @param mouseY      逻辑鼠标 Y
     * @param mouseButton 鼠标按键索引
     * @return 是否消费
     */
    public boolean mouseReleased(float mouseX, float mouseY, int mouseButton) {
        boolean handled = false;
        if (this.pressedHeaderSwitch && mouseButton == 0) {
            this.pressedHeaderSwitch = false;
            if (this.headerSwitch != null && this.module.isAvailable()) {
                handled = this.headerSwitch.mouseReleased(mouseX, mouseY, mouseButton);
                syncHeaderSwitchState();
            } else {
                handled = true;
            }
        }
        if (this.activeDragRow != null) {
            SettingRow row = this.activeDragRow;
            if (mouseButton == 0) {
                this.activeDragRow = null;
            }
            if (this.module.isAvailable()) {
                handled = row.component.mouseReleased(mouseX, mouseY, mouseButton) || handled;
            }
        }
        return handled;
    }

    /**
     * 处理键盘输入事件（优先内联中键按键绑定，其次设置区 {@link KeybindComponent} 或滑条行内数值编辑）。
     *
     * @param typedChar 输入字符
     * @param keyCode   LWJGL 按键码
     * @return 是否消费
     */
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!this.module.isAvailable()) {
            cancelAllInputCaptures();
            return false;
        }

        if (this.listeningInlineKeybind) {
            if (Keyboard.isCreated() && Keyboard.isRepeatEvent()) {
                return true;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.listeningInlineKeybind = false;
                return true;
            }
            if (keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) {
                this.module.setKeybind(Keyboard.KEY_NONE);
                this.listeningInlineKeybind = false;
                return true;
            }
            int sanitized = KeybindSetting.sanitizeKeyCode(keyCode);
            if (sanitized != Keyboard.KEY_NONE) {
                this.module.setKeybind(sanitized);
                this.listeningInlineKeybind = false;
            }
            return true;
        }

        for (int i = 0; i < this.settingRows.size(); i++) {
            SettingRow row = this.settingRows.get(i);
            if (!row.setting.isVisible()) {
                continue;
            }
            if (row.component.keyTyped(typedChar, keyCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回当前是否处于中键快捷绑定或设置区 {@link KeybindComponent} 等待按键状态。
     *
     * @return 是否正在等待按键绑定
     */
    public boolean isListeningKeybind() {
        if (this.listeningInlineKeybind) {
            return true;
        }
        for (int i = 0; i < this.settingRows.size(); i++) {
            UiComponent component = this.settingRows.get(i).component;
            if (component instanceof KeybindComponent && ((KeybindComponent) component).isListening()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取消本卡片的内联按键等待态与设置区 {@link KeybindComponent} 等待态。
     */
    public void cancelKeybindListening() {
        this.listeningInlineKeybind = false;
        for (int i = 0; i < this.settingRows.size(); i++) {
            UiComponent component = this.settingRows.get(i).component;
            if (component instanceof KeybindComponent) {
                ((KeybindComponent) component).cancelListening();
            }
        }
    }

    /**
     * 返回本卡片内是否存在正在行内输入数值的滑条控件。
     *
     * @return 是否存在滑条行内编辑
     */
    public boolean isEditingInlineNumber() {
        for (int i = 0; i < this.settingRows.size(); i++) {
            UiComponent component = this.settingRows.get(i).component;
            if (component instanceof SliderComponent && ((SliderComponent) component).isEditingValue()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取消本卡片内所有滑条行内数值编辑。
     */
    public void cancelInlineNumberEdit() {
        for (int i = 0; i < this.settingRows.size(); i++) {
            UiComponent component = this.settingRows.get(i).component;
            if (component instanceof SliderComponent) {
                ((SliderComponent) component).cancelInlineEdit();
            }
        }
    }

    /**
     * 取消本卡片内所有按键绑定等待态与滑条行内编辑态。
     */
    public void cancelAllInputCaptures() {
        cancelKeybindListening();
        cancelInlineNumberEdit();
    }

    /**
     * 关闭属于本卡片控件的任何打开的顶层弹出层。
     */
    public void closeOwnedPopups() {
        for (int i = 0; i < this.settingRows.size(); i++) {
            closeRowPopupIfOpen(this.settingRows.get(i));
        }
    }

    private void renderHeader(
        float x,
        float y,
        float width,
        float effMouseX,
        float effMouseY,
        float cardAlpha,
        ThemeData theme
    ) {
        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();

        float textStackH = bodyFont.getHeight() + UiTokens.SPACE_2 + captionFont.getHeight();
        float nameY = y + (COLLAPSED_HEIGHT - textStackH) * 0.5f;
        float descY = nameY + bodyFont.getHeight() + UiTokens.SPACE_2;
        float leftX = x + PADDING_H;
        float rightEdge = x + width - PADDING_H;

        float row1CenterY = nameY + bodyFont.getHeight() * 0.5f;
        float nextRightX = rightEdge;

        if (this.headerSwitch != null) {
            float switchX = rightEdge - SwitchComponent.WIDTH;
            float switchY = row1CenterY - SwitchComponent.HEIGHT * 0.5f;
            this.lastSwitchX = switchX;
            this.lastSwitchY = switchY;
            this.headerSwitch.render(
                switchX, switchY, SwitchComponent.WIDTH,
                effMouseX, effMouseY, cardAlpha, theme
            );
            nextRightX = switchX - UiTokens.SPACE_8;
        }

        boolean showPill = this.listeningInlineKeybind || this.module.getKeybind() != Keyboard.KEY_NONE;
        this.pillListenAnim.setTarget(this.listeningInlineKeybind ? 1.0f : 0.0f);
        float listenT = clamp01(this.pillListenAnim.get());

        if (showPill) {
            String pillText = this.listeningInlineKeybind
                ? this.cachedListeningLabel
                : this.module.getKeybindSetting().getKeyName();
            float pillTextW = captionFont.getWidth(pillText);
            float pillW = Math.max(KEYBIND_PILL_MIN_W, pillTextW + KEYBIND_PILL_PAD_H * 2.0f);
            float pillX = nextRightX - pillW;
            float pillY = row1CenterY - KEYBIND_PILL_HEIGHT * 0.5f;

            int pillBg = ColorAnimation.lerpArgb(theme.surface3(), theme.surface4(), listenT * 0.5f);
            int pillBorder = listenT > 0.01f ? RenderUtil.withAlpha(theme.accent(), listenT * cardAlpha) : 0;
            float pillBorderW = listenT > 0.01f ? UiTokens.BORDER_WIDTH : 0.0f;

            RenderUtil.drawRoundedRect(
                pillX, pillY, pillW, KEYBIND_PILL_HEIGHT,
                theme.radiusPill(KEYBIND_PILL_HEIGHT),
                RenderUtil.withAlpha(pillBg, cardAlpha),
                pillBorder,
                pillBorderW
            );

            int pillTextBase = ColorAnimation.lerpArgb(theme.textSecondary(), theme.accent(), listenT);
            float pillTextY = pillY + (KEYBIND_PILL_HEIGHT - captionFont.getHeight()) * 0.5f;
            captionFont.drawStringCentered(
                pillText,
                pillX + pillW * 0.5f,
                pillTextY,
                RenderUtil.withAlpha(pillTextBase, cardAlpha)
            );

            this.lastPillX = pillX;
            this.lastPillY = pillY;
            this.lastPillW = pillW;
            this.lastPillH = KEYBIND_PILL_HEIGHT;
            this.lastPillVisible = true;
            nextRightX = pillX - UiTokens.SPACE_8;
        } else {
            this.lastPillVisible = false;
        }

        float maxNameW = Math.max(0.0f, nextRightX - leftX);
        float enabledT = clamp01(this.enabledColorAnim.get());
        int nameBase = ColorAnimation.lerpArgb(theme.textSecondary(), theme.textPrimary(), enabledT);
        int nameColor = RenderUtil.withAlpha(nameBase, cardAlpha);
        int accentColor = RenderUtil.withAlpha(theme.accent(), cardAlpha);

        if (this.hasNameHighlight) {
            drawHighlightedText(
                bodyFont,
                this.cachedName, this.namePrefix, this.nameMatch, this.nameSuffix,
                leftX, nameY, maxNameW, nameColor, accentColor
            );
        } else {
            bodyFont.drawStringWithEllipsis(this.cachedName, leftX, nameY, maxNameW, nameColor);
        }

        float descRightLimit = rightEdge;
        if (this.module.hasCustomSettings()) {
            float row2CenterY = descY + captionFont.getHeight() * 0.5f;
            float chevX = rightEdge - CHEVRON_SIZE;
            float chevY = row2CenterY - CHEVRON_SIZE * 0.5f;
            int chevColor = RenderUtil.withAlpha(theme.textTertiary(), cardAlpha);
            float expandT = clamp01(this.expandAnim.get());

            if (expandT > 0.005f) {
                float cx = chevX + CHEVRON_SIZE * 0.5f;
                float cy = chevY + CHEVRON_SIZE * 0.5f;
                GlStateManager.pushMatrix();
                GlStateManager.translate(cx, cy, 0.0f);
                GlStateManager.rotate(CHEVRON_ROTATION_DEG * expandT, 0.0f, 0.0f, 1.0f);
                GlStateManager.translate(-cx, -cy, 0.0f);
                try {
                    RenderUtil.drawTintedIcon(ClickGuiIcons.CHEVRON_DOWN, chevX, chevY, CHEVRON_SIZE, chevColor);
                } finally {
                    GlStateManager.popMatrix();
                }
            } else {
                RenderUtil.drawTintedIcon(ClickGuiIcons.CHEVRON_DOWN, chevX, chevY, CHEVRON_SIZE, chevColor);
            }
            descRightLimit = chevX - UiTokens.SPACE_8;
        }

        float maxDescW = Math.max(0.0f, descRightLimit - leftX);
        int descColor = RenderUtil.withAlpha(theme.textSecondary(), cardAlpha);
        if (this.hasDescHighlight) {
            drawHighlightedText(
                captionFont,
                this.cachedDesc, this.descPrefix, this.descMatch, this.descSuffix,
                leftX, descY, maxDescW, descColor, accentColor
            );
        } else {
            captionFont.drawStringWithEllipsis(this.cachedDesc, leftX, descY, maxDescW, descColor);
        }
    }

    private void renderExpandedBody(
        float cardX,
        float cardY,
        float cardW,
        float effMouseX,
        float effMouseY,
        float cardAlpha,
        float expandT,
        ThemeData theme,
        TooltipManager tooltipManager
    ) {
        float bodyAlpha = cardAlpha * expandT;
        float dividerY = cardY + COLLAPSED_HEIGHT;
        float innerW = Math.max(0.0f, cardW - PADDING_H * 2.0f);
        RenderUtil.drawRect(
            cardX + PADDING_H,
            dividerY,
            innerW,
            UiTokens.BORDER_WIDTH,
            RenderUtil.withAlpha(theme.border(), bodyAlpha)
        );

        float clipY = dividerY + UiTokens.BORDER_WIDTH;
        float clipH = Math.max(0.0f, this.layoutH - COLLAPSED_HEIGHT - UiTokens.BORDER_WIDTH);
        if (clipH <= 0.5f || innerW <= 0.0f) {
            return;
        }

        TtfFontRenderer labelFont = FontManager.bodyRegular();
        int labelBaseColor = theme.textSecondary();
        int accentBaseColor = theme.accent();

        boolean pushedBodyScissor = false;
        if (Display.isCreated()) {
            ScissorStack.pushTransformed(cardX, clipY, cardW, clipH);
            pushedBodyScissor = true;
        }
        try {
            float rowX = cardX + PADDING_H;
            for (int i = 0; i < this.settingRows.size(); i++) {
                SettingRow row = this.settingRows.get(i);
                float visT = clamp01(row.visibilityAnim.get());
                if (visT <= 0.002f || row.rowH <= 0.5f) {
                    continue;
                }
                float rowY = cardY + row.localYFromTop;
                if (rowY > clipY + clipH) {
                    break;
                }
                float rowAlpha = bodyAlpha * visT;
                boolean pushedRowScissor = false;
                if (visT < 0.995f && Display.isCreated()) {
                    ScissorStack.pushTransformed(cardX, rowY, cardW, row.rowH);
                    pushedRowScissor = true;
                }
                try {
                    int labelColor = RenderUtil.withAlpha(labelBaseColor, rowAlpha);
                    int accentColor = RenderUtil.withAlpha(accentBaseColor, rowAlpha);

                    if (row.component instanceof SliderComponent) {
                        row.component.render(rowX, rowY, innerW, effMouseX, effMouseY, rowAlpha, theme);
                        if (row.hasLabelHighlight) {
                            float maxSliderLabelW = Math.max(0.0f, innerW - 72.0f);
                            drawHighlightedText(
                                labelFont,
                                row.cachedLabel, row.labelPrefix, row.labelMatch, row.labelSuffix,
                                rowX, rowY + UiTokens.SPACE_2, maxSliderLabelW, labelColor, accentColor
                            );
                        }
                        if (tooltipManager != null && !row.cachedDesc.isEmpty()
                            && isInside(effMouseX, effMouseY, rowX, rowY, Math.max(0.0f, innerW - 72.0f), 18.0f)) {
                            tooltipManager.request(row.setting, row.cachedDesc);
                        }
                    } else {
                        float prefW = row.component.getPreferredWidth();
                        float ctrlW;
                        if (row.component instanceof SegmentedComponent || row.component instanceof DropdownComponent) {
                            float minLabelSpace = Math.min(
                                labelFont.getWidth(row.cachedLabel) + UiTokens.SPACE_12,
                                innerW * 0.45f
                            );
                            float maxCtrlW = Math.max(80.0f, innerW - minLabelSpace);
                            ctrlW = Math.min(prefW, maxCtrlW);
                        } else if (row.component instanceof KeybindComponent) {
                            ctrlW = Math.min(prefW, innerW * 0.5f);
                        } else {
                            ctrlW = prefW;
                        }

                        float ctrlX = rowX + innerW - ctrlW;
                        float ctrlH = row.component.getHeight();
                        float ctrlY = rowY + (row.baseHeight - ctrlH) * 0.5f;
                        float maxLabelW = Math.max(0.0f, ctrlX - UiTokens.SPACE_12 - rowX);
                        float labelY = rowY + (row.baseHeight - labelFont.getHeight()) * 0.5f;

                        if (row.hasLabelHighlight) {
                            drawHighlightedText(
                                labelFont,
                                row.cachedLabel, row.labelPrefix, row.labelMatch, row.labelSuffix,
                                rowX, labelY, maxLabelW, labelColor, accentColor
                            );
                        } else {
                            labelFont.drawStringWithEllipsis(row.cachedLabel, rowX, labelY, maxLabelW, labelColor);
                        }

                        row.component.render(ctrlX, ctrlY, ctrlW, effMouseX, effMouseY, rowAlpha, theme);

                        if (tooltipManager != null && !row.cachedDesc.isEmpty()
                            && isInside(effMouseX, effMouseY, rowX, rowY, maxLabelW, row.baseHeight)) {
                            tooltipManager.request(row.setting, row.cachedDesc);
                        }
                    }
                } finally {
                    if (pushedRowScissor) {
                        ScissorStack.pop();
                    }
                }
            }
        } finally {
            if (pushedBodyScissor) {
                ScissorStack.pop();
            }
        }
    }

    private void syncHeaderSwitchState() {
        if (this.headerSwitch == null) {
            return;
        }
        boolean actual = this.module.isEnabled();
        if (this.headerSwitch.isChecked() != actual) {
            this.syncingSwitch = true;
            try {
                this.headerSwitch.setChecked(actual);
            } finally {
                this.syncingSwitch = false;
            }
        }
    }

    private void cancelSettingInputCaptures() {
        for (int i = 0; i < this.settingRows.size(); i++) {
            cancelRowInputCapture(this.settingRows.get(i));
        }
    }

    private static void cancelRowInputCapture(SettingRow row) {
        if (row.component instanceof KeybindComponent) {
            ((KeybindComponent) row.component).cancelListening();
        } else if (row.component instanceof SliderComponent) {
            ((SliderComponent) row.component).cancelInlineEdit();
        }
    }

    private static void closeRowPopupIfOpen(SettingRow row) {
        if (row.component instanceof DropdownComponent) {
            DropdownComponent dropdown = (DropdownComponent) row.component;
            if (dropdown.isOpen()) {
                dropdown.close();
            }
        } else if (row.component instanceof ColorComponent) {
            ColorComponent color = (ColorComponent) row.component;
            if (color.isPopupOpen()) {
                color.closePopup();
            }
        }
    }

    private static UiComponent createControlForSetting(Setting<?> setting, PopupHost popupHost) {
        if (setting instanceof BooleanSetting) {
            return new SwitchComponent((BooleanSetting) setting);
        }
        if (setting instanceof NumberSetting) {
            SliderComponent slider = new SliderComponent((NumberSetting) setting);
            slider.setLayout(SliderComponent.Layout.STACKED);
            slider.setShowLabel(true);
            return slider;
        }
        if (setting instanceof ModeSetting) {
            ModeSetting mode = (ModeSetting) setting;
            if (mode.getModeCount() <= 4) {
                return new SegmentedComponent(mode);
            }
            return new DropdownComponent(mode, popupHost);
        }
        if (setting instanceof ColorSetting) {
            return new ColorComponent((ColorSetting) setting, popupHost);
        }
        if (setting instanceof KeybindSetting) {
            return new KeybindComponent((KeybindSetting) setting);
        }
        return null;
    }

    private static void drawHighlightedText(
        TtfFontRenderer font,
        String fullText,
        String prefix,
        String match,
        String suffix,
        float x,
        float y,
        float maxWidth,
        int normalColor,
        int highlightColor
    ) {
        if (maxWidth <= 0.0f || fullText.isEmpty()) {
            return;
        }
        if (prefix.isEmpty()) {
            float matchW = font.getWidth(match);
            if (matchW <= maxWidth) {
                float nextX = font.drawString(match, x, y, highlightColor);
                if (!suffix.isEmpty()) {
                    float remW = Math.max(0.0f, x + maxWidth - nextX);
                    font.drawStringWithEllipsis(suffix, nextX, y, remW, normalColor);
                }
            } else {
                font.drawStringWithEllipsis(match, x, y, maxWidth, highlightColor);
            }
            return;
        }

        float prefixW = font.getWidth(prefix);
        if (prefixW >= maxWidth) {
            font.drawStringWithEllipsis(fullText, x, y, maxWidth, normalColor);
            return;
        }

        float curX = font.drawString(prefix, x, y, normalColor);
        float remAfterPrefix = Math.max(0.0f, x + maxWidth - curX);
        float matchW = font.getWidth(match);
        if (matchW <= remAfterPrefix) {
            curX = font.drawString(match, curX, y, highlightColor);
            if (!suffix.isEmpty()) {
                float remAfterMatch = Math.max(0.0f, x + maxWidth - curX);
                font.drawStringWithEllipsis(suffix, curX, y, remAfterMatch, normalColor);
            }
        } else {
            font.drawStringWithEllipsis(match, curX, y, remAfterPrefix, highlightColor);
        }
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return -1;
        }
        int hayLen = haystack.length();
        int needleLen = needle.length();
        if (needleLen > hayLen) {
            return -1;
        }
        for (int i = 0; i <= hayLen - needleLen; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needleLen)) {
                return i;
            }
        }
        return -1;
    }

    private static String safeString(String value) {
        return value != null ? value : "";
    }

    private static boolean isInside(float px, float py, float x, float y, float w, float h) {
        return w > 0.0f && h > 0.0f && px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static boolean hitTestMin20(float px, float py, float x, float y, float w, float h) {
        float hitW = Math.max(MIN_HIT_SIZE, w);
        float hitH = Math.max(MIN_HIT_SIZE, h);
        float hitX = x - (hitW - w) * 0.5f;
        float hitY = y - (hitH - h) * 0.5f;
        return isInside(px, py, hitX, hitY, hitW, hitH);
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

    private static final class SettingRow {
        final Setting<?> setting;
        final UiComponent component;
        final float baseHeight;
        final Animation visibilityAnim;
        boolean lastVisible;

        String cachedLabel = "";
        String cachedDesc = "";
        String cachedIdSearch = "";

        boolean hasLabelHighlight = false;
        String labelPrefix = "";
        String labelMatch = "";
        String labelSuffix = "";

        float localYFromTop = 0.0f;
        float rowH = 0.0f;

        SettingRow(Setting<?> setting, UiComponent component) {
            this.setting = setting;
            this.component = component;
            this.baseHeight = (setting instanceof NumberSetting) ? ROW_HEIGHT_SLIDER : ROW_HEIGHT_DEFAULT;
            this.lastVisible = setting.isVisible();
            this.visibilityAnim = new Animation(
                this.lastVisible ? 1.0f : 0.0f,
                AnimDuration.NORMAL,
                Easing.EASE_OUT_CUBIC
            );
        }

        void clearHighlight() {
            this.hasLabelHighlight = false;
            if (this.component instanceof SliderComponent) {
                ((SliderComponent) this.component).setShowLabel(true);
            }
        }

        boolean updateHighlight(String query, String lowerQuery) {
            int idx = indexOfIgnoreCase(this.cachedLabel, query);
            if (idx >= 0) {
                this.hasLabelHighlight = true;
                this.labelPrefix = this.cachedLabel.substring(0, idx);
                this.labelMatch = this.cachedLabel.substring(idx, idx + query.length());
                this.labelSuffix = this.cachedLabel.substring(idx + query.length());
                if (this.component instanceof SliderComponent) {
                    ((SliderComponent) this.component).setShowLabel(false);
                }
                return true;
            }
            this.hasLabelHighlight = false;
            if (this.component instanceof SliderComponent) {
                ((SliderComponent) this.component).setShowLabel(true);
            }
            return this.cachedIdSearch.contains(lowerQuery);
        }
    }
}
