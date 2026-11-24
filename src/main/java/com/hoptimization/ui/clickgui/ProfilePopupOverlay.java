package com.hoptimization.ui.clickgui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.modules.visual.ModernFont;
import com.hoptimization.modules.visual.Theme;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.component.PopupHost;
import com.hoptimization.ui.component.PopupOverlay;
import com.hoptimization.ui.component.TextFieldComponent;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.UiTokens;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * 侧边栏配置档（Profile）上浮弹出层，支持切换、新建、复制当前、重命名与删除配置档。
 */
public final class ProfilePopupOverlay implements PopupOverlay {
    private static final float POPUP_WIDTH = ClickGuiSidebar.WIDTH - UiTokens.SPACE_8 * 2.0f;
    private static final float POPUP_PAD = UiTokens.SPACE_4;
    private static final float POPUP_GAP = UiTokens.SPACE_4;
    private static final float ROW_HEIGHT = 26.0f;
    private static final float ICON_SIZE = 14.0f;
    private static final int MAX_VISIBLE_PROFILES = 6;
    private static final int ACTION_COUNT = 4;
    private static final float SCROLL_PER_NOTCH = 26.0f;
    private static final double SCROLL_TIME_CONSTANT_MS = 80.0D;

    private static final int ACTION_NEW = 0;
    private static final int ACTION_DUPLICATE = 1;
    private static final int ACTION_RENAME = 2;
    private static final int ACTION_DELETE = 3;

    private static final int INPUT_MODE_NONE = 0;
    private static final int INPUT_MODE_NEW = 1;
    private static final int INPUT_MODE_DUPLICATE = 2;
    private static final int INPUT_MODE_RENAME = 3;

    private final PopupHost popupHost;
    private final Runnable onProfileChanged;
    private final List<String> profiles = new ArrayList<String>();
    private final List<Animation> profileHoverAnims = new ArrayList<Animation>();
    private final Animation[] actionHoverAnims = new Animation[ACTION_COUNT];
    private final Animation cancelHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation confirmHoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final TextFieldComponent nameField = new TextFieldComponent();

    private String labelDefault = "";
    private String labelNew = "";
    private String labelDuplicate = "";
    private String labelRename = "";
    private String labelDelete = "";
    private String labelCancel = "";
    private String labelConfirm = "";
    private String labelPlaceholder = "";

    private boolean open = false;
    private int inputMode = INPUT_MODE_NONE;
    private float anchorX = 0.0f;
    private float anchorY = 0.0f;
    private float anchorW = POPUP_WIDTH;
    private float anchorH = 32.0f;

    private float popupX = 0.0f;
    private float popupY = 0.0f;
    private float popupH = 0.0f;

    private float scrollOffset = 0.0f;
    private float targetScroll = 0.0f;
    private float maxScroll = 0.0f;
    private long lastScrollNanos = 0L;

    /**
     * 构造配置档弹出层。
     *
     * @param popupHost        顶层弹出宿主
     * @param onProfileChanged 配置档切换或变更后的回调
     */
    public ProfilePopupOverlay(PopupHost popupHost, Runnable onProfileChanged) {
        this.popupHost = popupHost;
        this.onProfileChanged = onProfileChanged;
        for (int i = 0; i < ACTION_COUNT; i++) {
            this.actionHoverAnims[i] = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
        }
        this.nameField.setHeight(26.0f);
        this.nameField.setMaxLength(32);
        this.nameField.setCharFilter(new TextFieldComponent.CharFilter() {
            @Override
            public boolean accept(char c, String currentText, int insertIndex) {
                if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                    return false;
                }
                return !(c == '.' && insertIndex == 0);
            }
        });
        this.nameField.setSubmitListener(new TextFieldComponent.SubmitListener() {
            @Override
            public void onSubmit(String text) {
                submitInputAction();
            }
        });
    }

    /**
     * 刷新本地化文案缓存。
     */
    public void refreshLabels() {
        this.labelDefault = LanguageModule.format("ui.hoptimization.profile.default");
        this.labelNew = LanguageModule.format("ui.hoptimization.profile.new");
        this.labelDuplicate = LanguageModule.format("ui.hoptimization.profile.duplicate");
        this.labelRename = LanguageModule.format("ui.hoptimization.profile.rename");
        this.labelDelete = LanguageModule.format("ui.hoptimization.profile.delete");
        this.labelCancel = LanguageModule.format("ui.hoptimization.common.cancel");
        this.labelConfirm = LanguageModule.format("ui.hoptimization.common.confirm");
        this.labelPlaceholder = LanguageModule.format("ui.hoptimization.profile.input_placeholder");
        this.nameField.setPlaceholder(this.labelPlaceholder);
    }

    /**
     * 在配置档入口上方打开或关闭切换弹窗。
     *
     * @param triggerX 入口逻辑 X
     * @param triggerY 入口逻辑 Y
     * @param triggerW 入口逻辑宽度
     * @param triggerH 入口逻辑高度
     */
    public void toggleAt(float triggerX, float triggerY, float triggerW, float triggerH) {
        if (this.open) {
            if (this.popupHost != null) {
                this.popupHost.closePopup(this);
            } else {
                closeOverlay();
            }
            return;
        }
        openAt(triggerX, triggerY, triggerW, triggerH);
    }

    /**
     * 在配置档入口上方打开弹窗。
     *
     * @param triggerX 入口逻辑 X
     * @param triggerY 入口逻辑 Y
     * @param triggerW 入口逻辑宽度
     * @param triggerH 入口逻辑高度
     */
    public void openAt(float triggerX, float triggerY, float triggerW, float triggerH) {
        if (this.labelDefault.isEmpty()) {
            refreshLabels();
        }
        this.anchorX = triggerX;
        this.anchorY = triggerY;
        this.anchorW = triggerW;
        this.anchorH = triggerH;
        this.inputMode = INPUT_MODE_NONE;
        this.nameField.setFocused(false);
        this.scrollOffset = 0.0f;
        this.targetScroll = 0.0f;
        this.lastScrollNanos = System.nanoTime();
        reloadProfiles();
        this.open = true;
        computePopupBounds();
        if (this.popupHost != null) {
            this.popupHost.openPopup(this);
        }
    }

    /**
     * 跟随主窗口拖动实时更新锚点位置。
     *
     * @param triggerX 入口逻辑 X
     * @param triggerY 入口逻辑 Y
     * @param triggerW 入口逻辑宽度
     * @param triggerH 入口逻辑高度
     */
    public void updateAnchor(float triggerX, float triggerY, float triggerW, float triggerH) {
        this.anchorX = triggerX;
        this.anchorY = triggerY;
        this.anchorW = triggerW;
        this.anchorH = triggerH;
    }

    private void reloadProfiles() {
        this.profiles.clear();
        ConfigManager cm = HoptimizationMod.getConfigManager();
        if (cm != null) {
            List<String> loaded = cm.listProfiles();
            for (int i = 0; i < loaded.size(); i++) {
                this.profiles.add(loaded.get(i));
            }
        }
        if (this.profiles.isEmpty()) {
            this.profiles.add(UiConfig.DEFAULT_PROFILE);
        }
        while (this.profileHoverAnims.size() < this.profiles.size()) {
            this.profileHoverAnims.add(new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC));
        }
    }

    private void computePopupBounds() {
        if (this.inputMode != INPUT_MODE_NONE) {
            this.popupH = UiTokens.SPACE_8 * 2.0f + FontManager.caption().getHeight()
                + UiTokens.SPACE_6 + this.nameField.getHeight() + UiTokens.SPACE_8 + 24.0f;
            this.maxScroll = 0.0f;
        } else {
            int visibleCount = Math.min(MAX_VISIBLE_PROFILES, Math.max(1, this.profiles.size()));
            float listH = visibleCount * ROW_HEIGHT;
            float totalListH = this.profiles.size() * ROW_HEIGHT;
            this.maxScroll = Math.max(0.0f, totalListH - listH);
            this.targetScroll = clamp(this.targetScroll, 0.0f, this.maxScroll);
            float dividerSpace = UiTokens.SPACE_4 * 2.0f + UiTokens.BORDER_WIDTH;
            this.popupH = POPUP_PAD * 2.0f + listH + dividerSpace + ACTION_COUNT * ROW_HEIGHT;
        }

        float desiredX = this.anchorX;
        float desiredY = this.anchorY - POPUP_GAP - this.popupH;
        if (this.popupHost != null) {
            float vpX = this.popupHost.getViewportX();
            float vpY = this.popupHost.getViewportY();
            float vpW = this.popupHost.getViewportWidth();
            float vpH = this.popupHost.getViewportHeight();
            if (desiredY < vpY + UiTokens.SPACE_4) {
                desiredY = Math.min(vpY + vpH - this.popupH - UiTokens.SPACE_4,
                    this.anchorY + this.anchorH + POPUP_GAP);
            }
            desiredX = clamp(desiredX, vpX + UiTokens.SPACE_4,
                Math.max(vpX + UiTokens.SPACE_4, vpX + vpW - POPUP_WIDTH - UiTokens.SPACE_4));
        }
        this.popupX = desiredX;
        this.popupY = Math.max(UiTokens.SPACE_4, desiredY);
    }

    @Override
    public void renderOverlay(float mouseX, float mouseY, float alpha, ThemeData theme) {
        if (!this.open) {
            return;
        }
        computePopupBounds();
        float effAlpha = clamp01(alpha);
        if (effAlpha <= 0.001f) {
            return;
        }

        if (ThemeManager.isFrostedGlass()) {
            RenderUtil.drawFrostedRoundedRectWithShadow(
                this.popupX, this.popupY, POPUP_WIDTH, this.popupH,
                theme.radiusCard(),
                theme.frostedPopupTint(),
                theme.frostedBorder(),
                UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength(),
                effAlpha,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                theme.popupShadowColor()
            );
        } else {
            RenderUtil.drawRoundedRectWithShadow(
                this.popupX, this.popupY, POPUP_WIDTH, this.popupH,
                theme.radiusCard(),
                RenderUtil.withAlpha(theme.surface2(), effAlpha),
                RenderUtil.withAlpha(theme.border(), effAlpha),
                UiTokens.BORDER_WIDTH,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                RenderUtil.withAlpha(theme.popupShadowColor(), effAlpha)
            );
        }

        if (this.inputMode != INPUT_MODE_NONE) {
            renderInputView(mouseX, mouseY, effAlpha, theme);
        } else {
            renderMenuView(mouseX, mouseY, effAlpha, theme);
        }
    }

    private void renderMenuView(float mouseX, float mouseY, float effAlpha, ThemeData theme) {
        long now = System.nanoTime();
        float dtMs = this.lastScrollNanos > 0L
            ? Math.min(100.0f, Math.max(0.0f, (now - this.lastScrollNanos) / 1_000_000.0f))
            : 16.67f;
        this.lastScrollNanos = now;

        if (Animation.isReduceMotion()) {
            this.scrollOffset = this.targetScroll;
        } else {
            float factor = 1.0f - (float) Math.exp(-dtMs / SCROLL_TIME_CONSTANT_MS);
            this.scrollOffset += (this.targetScroll - this.scrollOffset) * factor;
            if (Math.abs(this.targetScroll - this.scrollOffset) < 0.2f) {
                this.scrollOffset = this.targetScroll;
            }
        }
        this.scrollOffset = clamp(this.scrollOffset, 0.0f, this.maxScroll);

        ConfigManager cm = HoptimizationMod.getConfigManager();
        String activeProfile = cm != null ? cm.getActiveProfileName() : UiConfig.DEFAULT_PROFILE;
        boolean isDefaultActive = UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(activeProfile);

        float rowX = this.popupX + POPUP_PAD;
        float rowW = POPUP_WIDTH - POPUP_PAD * 2.0f;
        float rowRadius = theme.nestedRadius(theme.radiusCard(), POPUP_PAD);
        int visibleCount = Math.min(MAX_VISIBLE_PROFILES, Math.max(1, this.profiles.size()));
        float listTop = this.popupY + POPUP_PAD;
        float listH = visibleCount * ROW_HEIGHT;
        boolean inList = isInside(mouseX, mouseY, rowX, listTop, rowW, listH);

        TtfFontRenderer bodyFont = FontManager.bodyRegular();

        boolean pushedScissor = false;
        if (Display.isCreated()) {
            ScissorStack.pushTransformed(rowX, listTop, rowW, listH);
            pushedScissor = true;
        }
        try {
            for (int i = 0; i < this.profiles.size(); i++) {
                float itemY = listTop + i * ROW_HEIGHT - this.scrollOffset;
                if (itemY + ROW_HEIGHT < listTop || itemY > listTop + listH) {
                    continue;
                }
                String rawName = this.profiles.get(i);
                boolean active = rawName.equalsIgnoreCase(activeProfile);
                boolean hovered = inList && isInside(mouseX, mouseY, rowX, itemY, rowW, ROW_HEIGHT);
                Animation hoverAnim = this.profileHoverAnims.get(i);
                hoverAnim.setTarget(hovered ? 1.0f : 0.0f);
                float hoverT = clamp01(hoverAnim.get());

                if (hoverT > 0.005f) {
                    RenderUtil.drawRoundedRect(
                        rowX, itemY, rowW, ROW_HEIGHT, rowRadius,
                        RenderUtil.withAlpha(theme.surface3(), hoverT * effAlpha)
                    );
                }

                String display = UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(rawName) ? this.labelDefault : rawName;
                int fg = active
                    ? theme.textPrimary()
                    : ColorAnimation.lerpArgb(theme.textSecondary(), theme.textPrimary(), hoverT);
                float maxTextW = rowW - UiTokens.SPACE_8 * 2.0f - (active ? (ICON_SIZE + UiTokens.SPACE_6) : 0.0f);
                float textY = itemY + (ROW_HEIGHT - bodyFont.getHeight()) * 0.5f;
                bodyFont.drawStringWithEllipsis(
                    display, rowX + UiTokens.SPACE_8, textY, Math.max(0.0f, maxTextW),
                    RenderUtil.withAlpha(fg, effAlpha)
                );

                if (active) {
                    float checkX = rowX + rowW - UiTokens.SPACE_8 - ICON_SIZE;
                    float checkY = itemY + (ROW_HEIGHT - ICON_SIZE) * 0.5f;
                    RenderUtil.drawTintedIcon(
                        ClickGuiIcons.CHECK, checkX, checkY, ICON_SIZE,
                        RenderUtil.withAlpha(theme.accent(), effAlpha)
                    );
                }
            }
        } finally {
            if (pushedScissor) {
                ScissorStack.pop();
            }
        }

        float dividerY = listTop + listH + UiTokens.SPACE_4;
        RenderUtil.drawRect(
            this.popupX + UiTokens.SPACE_8,
            dividerY,
            POPUP_WIDTH - UiTokens.SPACE_8 * 2.0f,
            UiTokens.BORDER_WIDTH,
            RenderUtil.withAlpha(theme.border(), effAlpha)
        );

        float actionsTop = dividerY + UiTokens.BORDER_WIDTH + UiTokens.SPACE_4;
        for (int a = 0; a < ACTION_COUNT; a++) {
            float ay = actionsTop + a * ROW_HEIGHT;
            boolean enabled = (a != ACTION_RENAME && a != ACTION_DELETE) || !isDefaultActive;
            boolean hovered = enabled && isInside(mouseX, mouseY, rowX, ay, rowW, ROW_HEIGHT);
            this.actionHoverAnims[a].setTarget(hovered ? 1.0f : 0.0f);
            float hoverT = clamp01(this.actionHoverAnims[a].get());

            if (enabled && hoverT > 0.005f) {
                int hoverFill = a == ACTION_DELETE
                    ? RenderUtil.withAlpha(theme.danger(), 0.14f * hoverT * effAlpha)
                    : RenderUtil.withAlpha(theme.surface3(), hoverT * effAlpha);
                RenderUtil.drawRoundedRect(rowX, ay, rowW, ROW_HEIGHT, rowRadius, hoverFill);
            }

            String actionLabel = resolveActionLabel(a);
            int baseColor;
            if (!enabled) {
                baseColor = RenderUtil.withAlpha(theme.textTertiary(), 0.45f * effAlpha);
            } else if (a == ACTION_DELETE) {
                baseColor = RenderUtil.withAlpha(theme.danger(), effAlpha);
            } else {
                int lerped = ColorAnimation.lerpArgb(theme.textSecondary(), theme.textPrimary(), hoverT);
                baseColor = RenderUtil.withAlpha(lerped, effAlpha);
            }
            float textY = ay + (ROW_HEIGHT - bodyFont.getHeight()) * 0.5f;
            bodyFont.drawStringWithEllipsis(
                actionLabel, rowX + UiTokens.SPACE_8, textY, rowW - UiTokens.SPACE_16, baseColor
            );
        }
    }

    private void renderInputView(float mouseX, float mouseY, float effAlpha, ThemeData theme) {
        float pad = UiTokens.SPACE_8;
        float contentX = this.popupX + pad;
        float contentW = POPUP_WIDTH - pad * 2.0f;
        float curY = this.popupY + pad;

        TtfFontRenderer captionFont = FontManager.caption();
        String modeTitle = this.inputMode == INPUT_MODE_NEW
            ? this.labelNew
            : (this.inputMode == INPUT_MODE_DUPLICATE ? this.labelDuplicate : this.labelRename);
        captionFont.drawString(modeTitle, contentX, curY, RenderUtil.withAlpha(theme.textSecondary(), effAlpha));
        curY += captionFont.getHeight() + UiTokens.SPACE_6;

        this.nameField.render(contentX, curY, contentW, mouseX, mouseY, effAlpha, theme);
        curY += this.nameField.getHeight() + UiTokens.SPACE_8;

        float btnGap = UiTokens.SPACE_6;
        float btnW = (contentW - btnGap) * 0.5f;
        float btnH = 24.0f;
        float btnRadius = theme.radiusControl();

        boolean cancelHover = isInside(mouseX, mouseY, contentX, curY, btnW, btnH);
        this.cancelHoverAnim.setTarget(cancelHover ? 1.0f : 0.0f);
        int cancelBg = ColorAnimation.lerpArgb(theme.surface3(), theme.surface4(), clamp01(this.cancelHoverAnim.get()));
        RenderUtil.drawRoundedRect(contentX, curY, btnW, btnH, btnRadius, RenderUtil.withAlpha(cancelBg, effAlpha));
        captionFont.drawStringCentered(
            this.labelCancel,
            contentX + btnW * 0.5f,
            curY + (btnH - captionFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(theme.textPrimary(), effAlpha)
        );

        float confirmX = contentX + btnW + btnGap;
        boolean valid = canSubmitCurrentInput();
        boolean confirmHover = valid && isInside(mouseX, mouseY, confirmX, curY, btnW, btnH);
        this.confirmHoverAnim.setTarget(confirmHover ? 1.0f : 0.0f);
        int confirmBg = valid
            ? ColorAnimation.lerpArgb(theme.accent(), theme.accentHover(), clamp01(this.confirmHoverAnim.get()))
            : theme.surface3();
        int confirmFg = valid ? theme.textOnAccent() : RenderUtil.withAlpha(theme.textTertiary(), 0.5f);
        RenderUtil.drawRoundedRect(confirmX, curY, btnW, btnH, btnRadius, RenderUtil.withAlpha(confirmBg, effAlpha));
        captionFont.drawStringCentered(
            this.labelConfirm,
            confirmX + btnW * 0.5f,
            curY + (btnH - captionFont.getHeight()) * 0.5f,
            RenderUtil.withAlpha(confirmFg, effAlpha)
        );
    }

    @Override
    public boolean handleOverlayMouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.open || mouseButton != 0) {
            return false;
        }
        if (this.inputMode != INPUT_MODE_NONE) {
            if (this.nameField.mouseClicked(mouseX, mouseY, mouseButton)) {
                return true;
            }
            float pad = UiTokens.SPACE_8;
            float contentX = this.popupX + pad;
            float contentW = POPUP_WIDTH - pad * 2.0f;
            float btnY = this.popupY + pad + FontManager.caption().getHeight()
                + UiTokens.SPACE_6 + this.nameField.getHeight() + UiTokens.SPACE_8;
            float btnGap = UiTokens.SPACE_6;
            float btnW = (contentW - btnGap) * 0.5f;
            float btnH = 24.0f;
            if (isInside(mouseX, mouseY, contentX, btnY, btnW, btnH)) {
                this.inputMode = INPUT_MODE_NONE;
                this.nameField.setFocused(false);
                computePopupBounds();
                return true;
            }
            if (isInside(mouseX, mouseY, contentX + btnW + btnGap, btnY, btnW, btnH) && canSubmitCurrentInput()) {
                submitInputAction();
                return true;
            }
            return true;
        }

        float rowX = this.popupX + POPUP_PAD;
        float rowW = POPUP_WIDTH - POPUP_PAD * 2.0f;
        int visibleCount = Math.min(MAX_VISIBLE_PROFILES, Math.max(1, this.profiles.size()));
        float listTop = this.popupY + POPUP_PAD;
        float listH = visibleCount * ROW_HEIGHT;

        if (isInside(mouseX, mouseY, rowX, listTop, rowW, listH)) {
            int idx = (int) Math.floor((mouseY - listTop + this.scrollOffset) / ROW_HEIGHT);
            if (idx >= 0 && idx < this.profiles.size()) {
                String targetProfile = this.profiles.get(idx);
                ConfigManager cm = HoptimizationMod.getConfigManager();
                if (cm != null && cm.switchProfile(targetProfile)) {
                    syncAfterProfileChange(cm);
                }
                closeSelf();
                return true;
            }
        }

        ConfigManager cm = HoptimizationMod.getConfigManager();
        String activeProfile = cm != null ? cm.getActiveProfileName() : UiConfig.DEFAULT_PROFILE;
        boolean isDefaultActive = UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(activeProfile);
        float actionsTop = listTop + listH + UiTokens.SPACE_4 * 2.0f + UiTokens.BORDER_WIDTH;

        for (int a = 0; a < ACTION_COUNT; a++) {
            float ay = actionsTop + a * ROW_HEIGHT;
            if (isInside(mouseX, mouseY, rowX, ay, rowW, ROW_HEIGHT)) {
                boolean enabled = (a != ACTION_RENAME && a != ACTION_DELETE) || !isDefaultActive;
                if (enabled) {
                    triggerAction(a, activeProfile, cm);
                }
                return true;
            }
        }
        return true;
    }

    private void triggerAction(int action, String activeProfile, ConfigManager cm) {
        if (action == ACTION_NEW) {
            this.inputMode = INPUT_MODE_NEW;
            this.nameField.setText("");
            this.nameField.setFocused(true);
            computePopupBounds();
            return;
        }
        if (action == ACTION_DUPLICATE) {
            this.inputMode = INPUT_MODE_DUPLICATE;
            this.nameField.setText(activeProfile + "_copy");
            this.nameField.setFocused(true);
            this.nameField.selectAll();
            computePopupBounds();
            return;
        }
        if (action == ACTION_RENAME) {
            this.inputMode = INPUT_MODE_RENAME;
            this.nameField.setText(activeProfile);
            this.nameField.setFocused(true);
            this.nameField.selectAll();
            computePopupBounds();
            return;
        }
        if (action == ACTION_DELETE && cm != null) {
            if (cm.deleteProfile(activeProfile)) {
                syncAfterProfileChange(cm);
                reloadProfiles();
                computePopupBounds();
            }
        }
    }

    private boolean canSubmitCurrentInput() {
        String candidate = this.nameField.getText().trim();
        if (!ConfigManager.isValidProfileName(candidate)) {
            return false;
        }
        if (this.inputMode == INPUT_MODE_RENAME && UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(candidate)) {
            return false;
        }
        for (int i = 0; i < this.profiles.size(); i++) {
            if (this.profiles.get(i).equalsIgnoreCase(candidate)) {
                return false;
            }
        }
        return true;
    }

    private void submitInputAction() {
        if (!canSubmitCurrentInput()) {
            return;
        }
        ConfigManager cm = HoptimizationMod.getConfigManager();
        if (cm == null) {
            return;
        }
        String targetName = this.nameField.getText().trim();
        String activeProfile = cm.getActiveProfileName();
        boolean ok = false;
        if (this.inputMode == INPUT_MODE_NEW) {
            ok = cm.createProfile(targetName);
        } else if (this.inputMode == INPUT_MODE_DUPLICATE) {
            ok = cm.duplicateCurrentProfile(targetName);
        } else if (this.inputMode == INPUT_MODE_RENAME) {
            ok = cm.renameProfile(activeProfile, targetName);
        }
        if (ok) {
            syncAfterProfileChange(cm);
            closeSelf();
        }
    }

    private void syncAfterProfileChange(ConfigManager cm) {
        Theme.syncOnStartup(cm.getUiConfig());
        ModernFont.syncOnStartup();
        cm.syncClickGuiFromUiConfig();
        if (this.onProfileChanged != null) {
            this.onProfileChanged.run();
        }
    }

    private void closeSelf() {
        if (this.popupHost != null) {
            this.popupHost.closePopup(this);
        } else {
            closeOverlay();
        }
    }

    private String resolveActionLabel(int action) {
        switch (action) {
            case ACTION_NEW:
                return this.labelNew;
            case ACTION_DUPLICATE:
                return this.labelDuplicate;
            case ACTION_RENAME:
                return this.labelRename;
            case ACTION_DELETE:
            default:
                return this.labelDelete;
        }
    }

    @Override
    public boolean handleOverlayMouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (this.inputMode != INPUT_MODE_NONE) {
            return this.nameField.mouseReleased(mouseX, mouseY, mouseButton);
        }
        return false;
    }

    @Override
    public boolean handleOverlayMouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (this.inputMode != INPUT_MODE_NONE) {
            return this.nameField.mouseDragged(mouseX, mouseY, mouseButton);
        }
        return false;
    }

    @Override
    public boolean handleOverlayMouseScrolled(float mouseX, float mouseY, int wheelDelta) {
        if (!this.open || this.inputMode != INPUT_MODE_NONE || this.maxScroll <= 0.0f) {
            return true;
        }
        float delta = -(wheelDelta / 120.0f) * SCROLL_PER_NOTCH;
        this.targetScroll = clamp(this.targetScroll + delta, 0.0f, this.maxScroll);
        return true;
    }

    @Override
    public boolean handleOverlayKeyTyped(char typedChar, int keyCode) {
        if (!this.open) {
            return false;
        }
        if (this.inputMode != INPUT_MODE_NONE) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                submitInputAction();
                return true;
            }
            return this.nameField.keyTyped(typedChar, keyCode);
        }
        return false;
    }

    @Override
    public boolean isOverlayOpen() {
        return this.open;
    }

    @Override
    public void closeOverlay() {
        this.open = false;
        this.inputMode = INPUT_MODE_NONE;
        this.nameField.setFocused(false);
    }

    @Override
    public boolean isPointInsideOverlay(float mouseX, float mouseY) {
        return this.open && isInside(mouseX, mouseY, this.popupX, this.popupY, POPUP_WIDTH, this.popupH);
    }

    private static boolean isInside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
