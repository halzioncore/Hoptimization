package com.hoptimization.ui.component;

import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;

/**
 * Color swatch control (left {@code 16x16} rounded swatch, optional HEX label, top-layer
 * {@link ColorPickerPopup} on click) bound to a {@link ColorSetting} or standalone color callback.
 */
public class ColorComponent extends UiComponent {
    public static final float SWATCH_SIZE = 16.0f;
    public static final float HEIGHT_WITH_HEX = 22.0f;
    private static final float CHECKER_INSET = 1.0f;

    /**
     * Listener invoked when a standalone color component changes value.
     */
    public interface ColorChangeListener {
        void onColorChanged(int newArgb);
    }

    private final ColorSetting setting;
    private final ColorChangeListener changeListener;
    private final boolean allowAlpha;

    private int standaloneArgb;
    private String standaloneHex;
    private boolean showHexLabel = false;

    private PopupHost popupHost;
    private final ColorPickerPopup popup;
    private final Animation hoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);

    private float visualX = 0.0f;
    private float visualY = 0.0f;
    private float visualW = SWATCH_SIZE;
    private float visualH = SWATCH_SIZE;

    /**
     * Creates a color swatch bound to a {@link ColorSetting} without an attached {@link PopupHost}.
     *
     * @param setting color setting
     */
    public ColorComponent(ColorSetting setting) {
        this(setting, null);
    }

    /**
     * Creates a color swatch bound to a {@link ColorSetting} with a top-most {@link PopupHost}.
     *
     * @param setting   color setting
     * @param popupHost popup host managing top-layer popups
     */
    public ColorComponent(ColorSetting setting, PopupHost popupHost) {
        if (setting == null) {
            throw new IllegalArgumentException("ColorComponent setting must not be null");
        }
        this.setting = setting;
        this.changeListener = null;
        this.allowAlpha = setting.isAllowAlpha();
        this.standaloneArgb = setting.getArgb();
        this.standaloneHex = setting.getHexString();
        this.popupHost = popupHost;
        this.popup = new ColorPickerPopup(createColorModel(), popupHost);
    }

    /**
     * Creates a standalone color swatch for non-module color editors (such as the Theme Editor).
     *
     * @param initialArgb    initial packed {@code 0xAARRGGBB} color
     * @param allowAlpha     {@code true} to allow alpha editing
     * @param popupHost      popup host managing top-layer popups
     * @param changeListener optional listener invoked when color changes
     */
    public ColorComponent(
        int initialArgb,
        boolean allowAlpha,
        PopupHost popupHost,
        ColorChangeListener changeListener
    ) {
        this.setting = null;
        this.changeListener = changeListener;
        this.allowAlpha = allowAlpha;
        this.standaloneArgb = allowAlpha ? initialArgb : RenderUtil.replaceAlpha(initialArgb, 255);
        this.standaloneHex = ColorSetting.formatHexColor(this.standaloneArgb, allowAlpha);
        this.popupHost = popupHost;
        this.popup = new ColorPickerPopup(createColorModel(), popupHost);
    }

    private ColorPickerPopup.ColorModel createColorModel() {
        return new ColorPickerPopup.ColorModel() {
            @Override
            public int getArgb() {
                return ColorComponent.this.getArgb();
            }

            @Override
            public void setArgb(int argb) {
                ColorComponent.this.setArgb(argb);
            }

            @Override
            public boolean isAllowAlpha() {
                return ColorComponent.this.allowAlpha;
            }

            @Override
            public String getHexString() {
                return ColorComponent.this.getHexString();
            }
        };
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
        ThemeData resolved = resolveTheme(theme);
        TtfFontRenderer font = FontManager.caption();
        String hex = getHexString();

        float contentH = getHeight();
        float contentW = this.showHexLabel
            ? (SWATCH_SIZE + UiTokens.SPACE_6 + font.getWidth(hex) + UiTokens.SPACE_4)
            : SWATCH_SIZE;
        float drawX = width > contentW ? (x + width - contentW) : x;
        float drawY = y;

        this.visualX = drawX;
        this.visualY = drawY;
        this.visualW = contentW;
        this.visualH = contentH;

        boolean inside = hitTestMin20(mouseX, mouseY, drawX, drawY, contentW, contentH);
        updateBounds(x, y, Math.max(contentW, width), contentH, inside);

        if (this.popup.isOverlayOpen()) {
            this.popup.updateAnchor(drawX, drawY, contentW, contentH);
        }

        this.hoverAnim.setTarget((this.hovered || this.popup.isOverlayOpen()) ? 1.0f : 0.0f);

        float effAlpha = effectiveAlpha(alpha);
        if (effAlpha <= 0.001f) {
            return;
        }

        float hoverT = clamp01(this.hoverAnim.get());
        float swatchY = drawY + (contentH - SWATCH_SIZE) * 0.5f;
        float swatchRadius = resolved.nestedRadius(resolved.radiusControl(), UiTokens.SPACE_2);

        int currentArgb = getArgb();
        int argbAlpha = (currentArgb >>> 24) & 0xFF;
        if (this.allowAlpha && argbAlpha < 255) {
            ColorPickerPopup.drawCheckerboard(
                drawX + CHECKER_INSET,
                swatchY + CHECKER_INSET,
                SWATCH_SIZE - CHECKER_INSET * 2.0f,
                SWATCH_SIZE - CHECKER_INSET * 2.0f,
                resolved,
                effAlpha
            );
        }

        int borderBase = ColorAnimation.lerpArgb(resolved.borderStrong(), resolved.accent(), hoverT);
        RenderUtil.drawRoundedRect(
            drawX,
            swatchY,
            SWATCH_SIZE,
            SWATCH_SIZE,
            swatchRadius,
            RenderUtil.withAlpha(currentArgb, effAlpha),
            RenderUtil.withAlpha(borderBase, effAlpha),
            UiTokens.BORDER_WIDTH
        );

        if (this.showHexLabel) {
            float textX = drawX + SWATCH_SIZE + UiTokens.SPACE_6;
            float textY = drawY + (contentH - font.getHeight()) * 0.5f;
            int textColor = ColorAnimation.lerpArgb(resolved.textSecondary(), resolved.textPrimary(), hoverT);
            font.drawString(hex, textX, textY, RenderUtil.withAlpha(textColor, effAlpha));
        }

        if (this.popup.isOverlayOpen() && this.popupHost == null) {
            this.popup.renderOverlay(mouseX, mouseY, effAlpha, resolved);
        }
    }

    @Override
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled) {
            return false;
        }
        if (this.popup.isOverlayOpen() && this.popupHost == null) {
            if (this.popup.isPointInsideOverlay(mouseX, mouseY)) {
                return this.popup.handleOverlayMouseClicked(mouseX, mouseY, mouseButton);
            }
            closePopup();
            return true;
        }
        if (mouseButton != 0) {
            return false;
        }
        if (hitTestMin20(mouseX, mouseY, this.visualX, this.visualY, this.visualW, this.visualH)) {
            if (this.popup.isOverlayOpen()) {
                closePopup();
            } else {
                openPopup();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled || !this.popup.isOverlayOpen() || this.popupHost != null) {
            return false;
        }
        return this.popup.handleOverlayMouseDragged(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean mouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (!this.popup.isOverlayOpen() || this.popupHost != null) {
            return false;
        }
        return this.popup.handleOverlayMouseReleased(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!this.enabled || !this.popup.isOverlayOpen() || this.popupHost != null) {
            return false;
        }
        return this.popup.handleOverlayKeyTyped(typedChar, keyCode);
    }

    @Override
    public float getHeight() {
        return this.showHexLabel ? HEIGHT_WITH_HEX : SWATCH_SIZE;
    }

    @Override
    public float getPreferredWidth() {
        if (!this.showHexLabel) {
            return SWATCH_SIZE;
        }
        TtfFontRenderer font = FontManager.caption();
        return SWATCH_SIZE + UiTokens.SPACE_6 + font.getWidth(getHexString()) + UiTokens.SPACE_4;
    }

    /**
     * Attaches the {@link PopupHost} responsible for rendering the color picker popup in the top-most layer.
     *
     * @param popupHost popup host, or {@code null} for inline fallback
     */
    public void setPopupHost(PopupHost popupHost) {
        if (this.popup.isOverlayOpen() && this.popupHost != null && this.popupHost != popupHost) {
            this.popupHost.closePopup(this.popup);
        }
        this.popupHost = popupHost;
        this.popup.setPopupHost(popupHost);
        if (this.popup.isOverlayOpen() && popupHost != null) {
            popupHost.openPopup(this.popup);
        }
    }

    /**
     * Controls whether the HEX code label is displayed to the right of the {@code 16x16} swatch.
     *
     * @param showHexLabel {@code true} to show HEX text beside the swatch
     */
    public void setShowHexLabel(boolean showHexLabel) {
        this.showHexLabel = showHexLabel;
    }

    /**
     * Returns whether the HEX label is shown beside the swatch.
     *
     * @return {@code true} if visible
     */
    public boolean isShowHexLabel() {
        return this.showHexLabel;
    }

    /**
     * Opens the color picker popup.
     */
    public void openPopup() {
        if (!this.enabled) {
            return;
        }
        this.popup.openAt(this.visualX, this.visualY, this.visualW, this.visualH);
        if (this.popupHost != null) {
            this.popupHost.openPopup(this.popup);
        }
    }

    /**
     * Closes the color picker popup if currently open.
     */
    public void closePopup() {
        if (this.popupHost != null) {
            this.popupHost.closePopup(this.popup);
        } else {
            this.popup.closeOverlay();
        }
    }

    /**
     * Returns whether the color picker popup is currently open.
     *
     * @return {@code true} if open
     */
    public boolean isPopupOpen() {
        return this.popup.isOverlayOpen();
    }

    /**
     * Returns the underlying {@link ColorPickerPopup} overlay.
     *
     * @return popup overlay instance
     */
    public ColorPickerPopup getPopup() {
        return this.popup;
    }

    /**
     * Returns the current color in packed {@code 0xAARRGGBB} format.
     *
     * @return ARGB color integer
     */
    public int getArgb() {
        return this.setting != null ? this.setting.getArgb() : this.standaloneArgb;
    }

    /**
     * Updates the current color and notifies the bound setting or listener.
     *
     * @param argb packed {@code 0xAARRGGBB} color integer
     */
    public void setArgb(int argb) {
        int coerced = this.allowAlpha ? argb : RenderUtil.replaceAlpha(argb, 255);
        if (this.setting != null) {
            this.setting.setValue(Integer.valueOf(coerced));
            this.standaloneArgb = this.setting.getArgb();
            this.standaloneHex = this.setting.getHexString();
        } else if (this.standaloneArgb != coerced) {
            this.standaloneArgb = coerced;
            this.standaloneHex = ColorSetting.formatHexColor(coerced, this.allowAlpha);
            if (this.changeListener != null) {
                this.changeListener.onColorChanged(coerced);
            }
        }
    }

    /**
     * Returns the cached {@code #RRGGBB} or {@code #RRGGBBAA} hex string without per-frame allocations.
     *
     * @return formatted hex string
     */
    public String getHexString() {
        return this.setting != null ? this.setting.getHexString() : this.standaloneHex;
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (!enabled) {
            closePopup();
        }
    }
}
