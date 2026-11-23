package com.hoptimization.ui.component;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import java.util.List;

/**
 * Dropdown selector control (height {@code 24}, {@code surface3}, {@code 14px} rotating {@code chevron-down},
 * top-most {@code surface2} popup with popup shadow and {@code 14px} {@code check} icon) bound to a
 * {@link ModeSetting} or standalone option list.
 */
public class DropdownComponent extends UiComponent {
    public static final float HEIGHT = 24.0f;
    public static final float ITEM_HEIGHT = 24.0f;
    public static final float ICON_SIZE = 14.0f;
    public static final float POPUP_PAD = UiTokens.SPACE_4;
    public static final float POPUP_GAP = UiTokens.SPACE_4;
    private static final float MIN_PREFERRED_WIDTH = 96.0f;

    private static final ResourceLocation ICON_CHEVRON_DOWN =
        new ResourceLocation(HoptimizationMod.MODID, "texture/icon/chevron-down.png");
    private static final ResourceLocation ICON_CHECK =
        new ResourceLocation(HoptimizationMod.MODID, "texture/icon/check.png");

    /**
     * Listener invoked when a standalone dropdown selection changes.
     */
    public interface SelectionListener {
        void onSelected(int index, String label);
    }

    private final ModeSetting setting;
    private final SelectionListener selectionListener;
    private final String[] cachedLabels;
    private boolean labelsInitialized;
    private int selectedIndex;

    private PopupHost popupHost;
    private final DropdownOverlay overlay = new DropdownOverlay();

    private final Animation hoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation openAnim = new Animation(0.0f, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);

    private boolean open = false;
    private int highlightedIndex = -1;
    private float scrollOffset = 0.0f;
    private float targetScroll = 0.0f;

    private float popupX = 0.0f;
    private float popupY = 0.0f;
    private float popupW = 0.0f;
    private float popupH = 0.0f;
    private float maxScroll = 0.0f;

    private float cachedPreferredWidth = -1.0f;
    private float cachedMaxOptionTextW = 0.0f;
    private TtfFontRenderer measuredFont;

    /**
     * Creates a dropdown bound to a {@link ModeSetting} without an attached {@link PopupHost}.
     *
     * @param setting discrete mode setting
     */
    public DropdownComponent(ModeSetting setting) {
        this(setting, null);
    }

    /**
     * Creates a dropdown bound to a {@link ModeSetting} with an optional {@link PopupHost}.
     *
     * @param setting   discrete mode setting
     * @param popupHost top-most layer host managing popup overlays
     */
    public DropdownComponent(ModeSetting setting, PopupHost popupHost) {
        if (setting == null) {
            throw new IllegalArgumentException("DropdownComponent setting must not be null");
        }
        this.setting = setting;
        this.selectionListener = null;
        this.popupHost = popupHost;
        int count = setting.getModeCount();
        this.cachedLabels = new String[count];
        this.labelsInitialized = false;
        this.selectedIndex = clampIndex(setting.getIndex(), count);
    }

    /**
     * Creates a standalone dropdown for non-module selectors (such as UI scale or profiles).
     *
     * @param options           ordered display labels
     * @param initialIndex      initial selected index
     * @param popupHost         top-most layer host managing popup overlays
     * @param selectionListener optional callback when selection changes
     */
    public DropdownComponent(
        List<String> options,
        int initialIndex,
        PopupHost popupHost,
        SelectionListener selectionListener
    ) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("DropdownComponent requires at least one option");
        }
        this.setting = null;
        this.selectionListener = selectionListener;
        this.popupHost = popupHost;
        this.cachedLabels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            String opt = options.get(i);
            this.cachedLabels[i] = opt != null ? opt : "";
        }
        this.labelsInitialized = true;
        this.selectedIndex = clampIndex(initialIndex, options.size());
    }

    @Override
    public void render(
        float x,
        float y,
        float width,
        float mouseX,
        float mouseY,
        float alpha,
        ThemeData theme
    ) {
        ensureLabels();
        ThemeData resolved = resolveTheme(theme);
        float boxW = Math.max(0.0f, width);
        boolean inside = hitTest(mouseX, mouseY, x, y, boxW, HEIGHT);
        updateBounds(x, y, boxW, HEIGHT, inside);

        this.hoverAnim.setTarget((this.hovered || this.open) ? 1.0f : 0.0f);
        this.openAnim.setTarget(this.open ? 1.0f : 0.0f);

        float effAlpha = effectiveAlpha(alpha);
        if (effAlpha <= 0.001f || boxW <= 0.0f) {
            return;
        }

        float hoverT = clamp01(this.hoverAnim.get());
        int bgColor = ColorAnimation.lerpArgb(resolved.surface3(), resolved.surface4(), hoverT);
        RenderUtil.drawRoundedRect(
            x,
            y,
            boxW,
            HEIGHT,
            resolved.radiusControl(),
            RenderUtil.withAlpha(bgColor, effAlpha)
        );

        float iconX = x + boxW - UiTokens.SPACE_6 - ICON_SIZE;
        float iconY = y + (HEIGHT - ICON_SIZE) * 0.5f;
        int iconBase = ColorAnimation.lerpArgb(resolved.textSecondary(), resolved.textPrimary(), hoverT);
        int iconColor = RenderUtil.withAlpha(iconBase, effAlpha);

        float openT = clamp01(this.openAnim.get());
        if (openT > 0.005f) {
            float cx = iconX + ICON_SIZE * 0.5f;
            float cy = iconY + ICON_SIZE * 0.5f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(cx, cy, 0.0f);
            GlStateManager.rotate(180.0f * openT, 0.0f, 0.0f, 1.0f);
            GlStateManager.translate(-cx, -cy, 0.0f);
            try {
                RenderUtil.drawTintedIcon(ICON_CHEVRON_DOWN, iconX, iconY, ICON_SIZE, iconColor);
            } finally {
                GlStateManager.popMatrix();
            }
        } else {
            RenderUtil.drawTintedIcon(ICON_CHEVRON_DOWN, iconX, iconY, ICON_SIZE, iconColor);
        }

        TtfFontRenderer font = FontManager.bodyRegular();
        int activeIdx = getSelectedIndex();
        String currentLabel = this.cachedLabels.length > 0 ? this.cachedLabels[activeIdx] : "";
        float textX = x + UiTokens.PADDING_CONTROL;
        float textY = y + (HEIGHT - font.getHeight()) * 0.5f;
        float maxTextW = Math.max(0.0f, iconX - UiTokens.SPACE_4 - textX);
        if (!currentLabel.isEmpty() && maxTextW > 0.0f) {
            font.drawStringWithEllipsis(
                currentLabel,
                textX,
                textY,
                maxTextW,
                RenderUtil.withAlpha(resolved.textPrimary(), effAlpha)
            );
        }

        if (this.open && this.popupHost == null) {
            this.overlay.renderOverlay(mouseX, mouseY, effAlpha, resolved);
        }
    }

    @Override
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled) {
            return false;
        }
        if (this.open && this.popupHost == null) {
            if (this.overlay.isPointInsideOverlay(mouseX, mouseY)) {
                return this.overlay.handleOverlayMouseClicked(mouseX, mouseY, mouseButton);
            }
            close();
            return true;
        }
        if (mouseButton != 0) {
            return false;
        }
        if (hitTest(mouseX, mouseY, this.x, this.y, this.width, HEIGHT)) {
            if (this.open) {
                close();
            } else {
                open();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(float mouseX, float mouseY, int wheelDelta) {
        if (!this.enabled || !this.open || this.popupHost != null) {
            return false;
        }
        return this.overlay.handleOverlayMouseScrolled(mouseX, mouseY, wheelDelta);
    }

    @Override
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!this.enabled || !this.open || this.popupHost != null) {
            return false;
        }
        return this.overlay.handleOverlayKeyTyped(typedChar, keyCode);
    }

    @Override
    public float getHeight() {
        return HEIGHT;
    }

    @Override
    public float getPreferredWidth() {
        ensureLabels();
        ensureMeasuredWidths(FontManager.bodyRegular());
        return this.cachedPreferredWidth;
    }

    @Override
    public void refreshLabels() {
        if (this.setting != null) {
            for (int i = 0; i < this.cachedLabels.length; i++) {
                this.cachedLabels[i] = this.setting.getOptionDisplayName(i);
            }
            this.labelsInitialized = true;
            this.cachedPreferredWidth = -1.0f;
            this.measuredFont = null;
        }
    }

    /**
     * Attaches the {@link PopupHost} responsible for rendering this dropdown's menu in the top-most layer.
     *
     * @param popupHost popup host, or {@code null} for inline fallback
     */
    public void setPopupHost(PopupHost popupHost) {
        if (this.open && this.popupHost != null && this.popupHost != popupHost) {
            this.popupHost.closePopup(this.overlay);
        }
        this.popupHost = popupHost;
        if (this.open && popupHost != null) {
            popupHost.openPopup(this.overlay);
        }
    }

    /**
     * Returns whether the dropdown popup list is currently open.
     *
     * @return {@code true} if open
     */
    public boolean isOpen() {
        return this.open;
    }

    /**
     * Opens the dropdown popup list, closing any other popup managed by the {@link PopupHost}.
     */
    public void open() {
        if (!this.enabled || this.cachedLabels.length == 0) {
            return;
        }
        ensureLabels();
        this.open = true;
        this.highlightedIndex = getSelectedIndex();
        this.scrollOffset = 0.0f;
        this.targetScroll = 0.0f;
        this.openAnim.setTarget(1.0f);
        if (this.popupHost != null) {
            this.popupHost.openPopup(this.overlay);
        }
    }

    /**
     * Closes the dropdown popup list.
     */
    public void close() {
        if (!this.open) {
            return;
        }
        this.open = false;
        this.openAnim.setTarget(0.0f);
        if (this.popupHost != null) {
            this.popupHost.closePopup(this.overlay);
        }
    }

    /**
     * Returns the zero-based index of the currently selected option.
     *
     * @return selected option index
     */
    public int getSelectedIndex() {
        return this.setting != null ? this.setting.getIndex() : this.selectedIndex;
    }

    /**
     * Selects the option at {@code index} and notifies the bound setting or listener.
     *
     * @param index option index
     */
    public void setSelectedIndex(int index) {
        int safeIndex = clampIndex(index, this.cachedLabels.length);
        if (this.setting != null) {
            this.setting.setIndex(safeIndex);
            this.selectedIndex = this.setting.getIndex();
        } else if (this.selectedIndex != safeIndex) {
            this.selectedIndex = safeIndex;
            if (this.selectionListener != null) {
                ensureLabels();
                this.selectionListener.onSelected(safeIndex, this.cachedLabels[safeIndex]);
            }
        }
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (!enabled) {
            close();
        }
    }

    private void ensureLabels() {
        if (!this.labelsInitialized) {
            refreshLabels();
        }
    }

    private void ensureMeasuredWidths(TtfFontRenderer font) {
        if (this.cachedPreferredWidth >= 0.0f && this.measuredFont == font) {
            return;
        }
        this.measuredFont = font;
        float maxW = 0.0f;
        for (int i = 0; i < this.cachedLabels.length; i++) {
            float w = font.getWidth(this.cachedLabels[i]);
            if (w > maxW) {
                maxW = w;
            }
        }
        this.cachedMaxOptionTextW = maxW;
        float raw = maxW + UiTokens.PADDING_CONTROL + UiTokens.SPACE_6 + ICON_SIZE + UiTokens.SPACE_6;
        this.cachedPreferredWidth = Math.max(MIN_PREFERRED_WIDTH, (float) (Math.ceil(raw / 4.0D) * 4.0D));
    }

    private void computePopupLayout(TtfFontRenderer font) {
        ensureMeasuredWidths(font);
        float vpX = this.popupHost != null ? this.popupHost.getViewportX() : 0.0f;
        float vpY = this.popupHost != null ? this.popupHost.getViewportY() : 0.0f;
        float vpW = this.popupHost != null ? this.popupHost.getViewportWidth() : UiScale.getLogicalWidth();
        float vpH = this.popupHost != null ? this.popupHost.getViewportHeight() : UiScale.getLogicalHeight();

        float margin = UiTokens.SPACE_4;
        float maxVpW = Math.max(64.0f, vpW - margin * 2.0f);
        float minMenuW = this.cachedMaxOptionTextW + UiTokens.SPACE_6 + ICON_SIZE + UiTokens.SPACE_6
            + UiTokens.SPACE_8 + POPUP_PAD * 2.0f;
        this.popupW = Math.min(maxVpW, Math.max(this.width, minMenuW));

        float px = this.x;
        if (px + this.popupW > vpX + vpW - margin) {
            px = vpX + vpW - margin - this.popupW;
        }
        if (px < vpX + margin) {
            px = vpX + margin;
        }
        this.popupX = px;

        float contentH = this.cachedLabels.length * ITEM_HEIGHT + POPUP_PAD * 2.0f;
        float spaceBelow = (vpY + vpH - margin) - (this.y + HEIGHT + POPUP_GAP);
        float spaceAbove = (this.y - POPUP_GAP) - (vpY + margin);

        boolean placeBelow = spaceBelow >= contentH || spaceBelow >= spaceAbove;
        float availableH = Math.max(ITEM_HEIGHT + POPUP_PAD * 2.0f, placeBelow ? spaceBelow : spaceAbove);
        this.popupH = Math.min(contentH, availableH);
        this.maxScroll = Math.max(0.0f, contentH - this.popupH);
        this.targetScroll = clamp(this.targetScroll, 0.0f, this.maxScroll);
        this.scrollOffset = clamp(this.scrollOffset, 0.0f, this.maxScroll);

        if (placeBelow) {
            this.popupY = this.y + HEIGHT + POPUP_GAP;
        } else {
            this.popupY = this.y - POPUP_GAP - this.popupH;
        }
        if (this.popupY < vpY + margin) {
            this.popupY = vpY + margin;
        }
    }

    private void ensureItemVisible(int index) {
        if (index < 0 || index >= this.cachedLabels.length || this.maxScroll <= 0.0f) {
            return;
        }
        float listH = Math.max(0.0f, this.popupH - POPUP_PAD * 2.0f);
        float itemTop = index * ITEM_HEIGHT;
        float itemBottom = itemTop + ITEM_HEIGHT;
        if (itemTop < this.targetScroll) {
            this.targetScroll = itemTop;
        } else if (itemBottom > this.targetScroll + listH) {
            this.targetScroll = itemBottom - listH;
        }
        this.targetScroll = clamp(this.targetScroll, 0.0f, this.maxScroll);
    }

    private static int clampIndex(int index, int count) {
        if (count <= 0 || index <= 0) {
            return 0;
        }
        if (index >= count) {
            return count - 1;
        }
        return index;
    }

    private final class DropdownOverlay implements PopupOverlay {

        @Override
        public void renderOverlay(float mouseX, float mouseY, float alpha, ThemeData theme) {
            if (!DropdownComponent.this.open) {
                return;
            }
            ensureLabels();
            TtfFontRenderer font = FontManager.bodyRegular();
            computePopupLayout(font);

            float effAlpha = effectiveAlpha(alpha);
            if (effAlpha <= 0.001f) {
                return;
            }

            // Smooth exponential scroll convergence toward targetScroll.
            DropdownComponent.this.scrollOffset +=
                (DropdownComponent.this.targetScroll - DropdownComponent.this.scrollOffset) * 0.35f;
            if (Math.abs(DropdownComponent.this.targetScroll - DropdownComponent.this.scrollOffset) < 0.25f) {
                DropdownComponent.this.scrollOffset = DropdownComponent.this.targetScroll;
            }

            float radius = theme.radiusCard();
            if (com.hoptimization.ui.theme.ThemeManager.isFrostedGlass()) {
                boolean isLight = theme.getMode() == com.hoptimization.ui.theme.ThemeMode.LIGHT;
                RenderUtil.drawFrostedRoundedRectWithShadow(
                    DropdownComponent.this.popupX,
                    DropdownComponent.this.popupY,
                    DropdownComponent.this.popupW,
                    DropdownComponent.this.popupH,
                    radius,
                    RenderUtil.withAlpha(theme.surface2(), isLight ? 0.74f : 0.66f),
                    isLight ? theme.borderStrong() : 0x38FFFFFF,
                    UiTokens.BORDER_WIDTH,
                    isLight ? 0.14f : 0.09f,
                    effAlpha,
                    0.0f,
                    UiTokens.SHADOW_POPUP_OFFSET_Y,
                    UiTokens.SHADOW_POPUP_BLUR,
                    theme.popupShadowColor()
                );
            } else {
                RenderUtil.drawRoundedRectWithShadow(
                    DropdownComponent.this.popupX,
                    DropdownComponent.this.popupY,
                    DropdownComponent.this.popupW,
                    DropdownComponent.this.popupH,
                    radius,
                    RenderUtil.withAlpha(theme.surface2(), effAlpha),
                    RenderUtil.withAlpha(theme.border(), effAlpha),
                    UiTokens.BORDER_WIDTH,
                    0.0f,
                    UiTokens.SHADOW_POPUP_OFFSET_Y,
                    UiTokens.SHADOW_POPUP_BLUR,
                    RenderUtil.withAlpha(theme.popupShadowColor(), effAlpha)
                );
            }

            float listX = DropdownComponent.this.popupX + POPUP_PAD;
            float listY = DropdownComponent.this.popupY + POPUP_PAD;
            float listW = Math.max(0.0f, DropdownComponent.this.popupW - POPUP_PAD * 2.0f);
            float listH = Math.max(0.0f, DropdownComponent.this.popupH - POPUP_PAD * 2.0f);
            if (listW <= 0.0f || listH <= 0.0f) {
                return;
            }

            float itemRadius = theme.nestedRadius(radius, POPUP_PAD);
            int activeIdx = getSelectedIndex();
            boolean mouseInList = hitTest(mouseX, mouseY, listX, listY, listW, listH);

            boolean pushedScissor = false;
            if (Display.isCreated()) {
                ScissorStack.pushTransformed(listX, listY, listW, listH);
                pushedScissor = true;
            }
            try {
                float textOffsetX = UiTokens.SPACE_6 + ICON_SIZE + UiTokens.SPACE_6;
                for (int i = 0; i < DropdownComponent.this.cachedLabels.length; i++) {
                    float itemY = listY + i * ITEM_HEIGHT - DropdownComponent.this.scrollOffset;
                    if (itemY + ITEM_HEIGHT < listY || itemY > listY + listH) {
                        continue;
                    }
                    boolean itemHovered = mouseInList && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
                    if (itemHovered) {
                        DropdownComponent.this.highlightedIndex = i;
                    }
                    boolean highlighted = itemHovered || DropdownComponent.this.highlightedIndex == i;
                    boolean selected = i == activeIdx;

                    if (highlighted) {
                        RenderUtil.drawRoundedRect(
                            listX,
                            itemY,
                            listW,
                            ITEM_HEIGHT,
                            itemRadius,
                            RenderUtil.withAlpha(theme.surface3(), effAlpha)
                        );
                    }

                    if (selected) {
                        float checkX = listX + UiTokens.SPACE_6;
                        float checkY = itemY + (ITEM_HEIGHT - ICON_SIZE) * 0.5f;
                        RenderUtil.drawTintedIcon(
                            ICON_CHECK,
                            checkX,
                            checkY,
                            ICON_SIZE,
                            RenderUtil.withAlpha(theme.accent(), effAlpha)
                        );
                    }

                    int textColor = (selected || highlighted) ? theme.textPrimary() : theme.textSecondary();
                    float textX = listX + textOffsetX;
                    float textY = itemY + (ITEM_HEIGHT - font.getHeight()) * 0.5f;
                    float maxTextW = Math.max(0.0f, listX + listW - UiTokens.SPACE_6 - textX);
                    if (maxTextW > 0.0f) {
                        font.drawStringWithEllipsis(
                            DropdownComponent.this.cachedLabels[i],
                            textX,
                            textY,
                            maxTextW,
                            RenderUtil.withAlpha(textColor, effAlpha)
                        );
                    }
                }
            } finally {
                if (pushedScissor) {
                    ScissorStack.pop();
                }
            }
        }

        @Override
        public boolean handleOverlayMouseClicked(float mouseX, float mouseY, int mouseButton) {
            if (mouseButton != 0 || !DropdownComponent.this.open) {
                return false;
            }
            float listX = DropdownComponent.this.popupX + POPUP_PAD;
            float listY = DropdownComponent.this.popupY + POPUP_PAD;
            float listW = Math.max(0.0f, DropdownComponent.this.popupW - POPUP_PAD * 2.0f);
            float listH = Math.max(0.0f, DropdownComponent.this.popupH - POPUP_PAD * 2.0f);
            if (!hitTest(mouseX, mouseY, listX, listY, listW, listH)) {
                return true;
            }
            float relY = mouseY - listY + DropdownComponent.this.scrollOffset;
            int idx = (int) Math.floor(relY / ITEM_HEIGHT);
            if (idx >= 0 && idx < DropdownComponent.this.cachedLabels.length) {
                setSelectedIndex(idx);
                close();
            }
            return true;
        }

        @Override
        public boolean handleOverlayMouseReleased(float mouseX, float mouseY, int mouseButton) {
            return DropdownComponent.this.open;
        }

        @Override
        public boolean handleOverlayMouseDragged(float mouseX, float mouseY, int mouseButton) {
            return DropdownComponent.this.open;
        }

        @Override
        public boolean handleOverlayMouseScrolled(float mouseX, float mouseY, int wheelDelta) {
            if (!DropdownComponent.this.open || DropdownComponent.this.maxScroll <= 0.0f) {
                return DropdownComponent.this.open;
            }
            float step = wheelDelta > 0 ? -ITEM_HEIGHT : (wheelDelta < 0 ? ITEM_HEIGHT : 0.0f);
            DropdownComponent.this.targetScroll = clamp(
                DropdownComponent.this.targetScroll + step,
                0.0f,
                DropdownComponent.this.maxScroll
            );
            return true;
        }

        @Override
        public boolean handleOverlayKeyTyped(char typedChar, int keyCode) {
            if (!DropdownComponent.this.open) {
                return false;
            }
            int count = DropdownComponent.this.cachedLabels.length;
            if (keyCode == Keyboard.KEY_ESCAPE) {
                close();
                return true;
            }
            if (keyCode == Keyboard.KEY_UP && count > 0) {
                int next = DropdownComponent.this.highlightedIndex <= 0
                    ? count - 1
                    : DropdownComponent.this.highlightedIndex - 1;
                DropdownComponent.this.highlightedIndex = next;
                ensureItemVisible(next);
                return true;
            }
            if (keyCode == Keyboard.KEY_DOWN && count > 0) {
                int next = (DropdownComponent.this.highlightedIndex + 1) % count;
                DropdownComponent.this.highlightedIndex = next;
                ensureItemVisible(next);
                return true;
            }
            if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && count > 0) {
                int idx = DropdownComponent.this.highlightedIndex >= 0
                    ? DropdownComponent.this.highlightedIndex
                    : getSelectedIndex();
                setSelectedIndex(idx);
                close();
                return true;
            }
            return false;
        }

        @Override
        public boolean isOverlayOpen() {
            return DropdownComponent.this.open;
        }

        @Override
        public void closeOverlay() {
            DropdownComponent.this.open = false;
            DropdownComponent.this.openAnim.setTarget(0.0f);
        }

        @Override
        public boolean isPointInsideOverlay(float mouseX, float mouseY) {
            return DropdownComponent.this.open && hitTest(
                mouseX,
                mouseY,
                DropdownComponent.this.popupX,
                DropdownComponent.this.popupY,
                DropdownComponent.this.popupW,
                DropdownComponent.this.popupH
            );
        }
    }
}
