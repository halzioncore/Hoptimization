package com.hoptimization.ui.component;

import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import org.lwjgl.input.Keyboard;

/**
 * Top-most layer HSV + Alpha color picker popup ({@code 160x120} SV square + Hue bar
 * + optional Alpha bar when {@code allowAlpha} + integrated HEX {@link TextFieldComponent}).
 */
public class ColorPickerPopup implements PopupOverlay {
    public static final float SV_WIDTH = 160.0f;
    public static final float SV_HEIGHT = 120.0f;
    public static final float BAR_HEIGHT = 12.0f;
    public static final float PADDING = UiTokens.PADDING_CARD;
    public static final float GAP = UiTokens.SPACE_8;
    public static final float POPUP_GAP = UiTokens.SPACE_4;
    public static final float POPUP_WIDTH = PADDING * 2.0f + SV_WIDTH;

    private static final int DRAG_NONE = 0;
    private static final int DRAG_SV = 1;
    private static final int DRAG_HUE = 2;
    private static final int DRAG_ALPHA = 3;

    private static final int CHECKER_CELL = 6;
    private static final float INDICATOR_RADIUS = 4.0f;
    private static final int[] HUE_STOPS = new int[] {
        hsvToArgb(0.0f / 6.0f, 1.0f, 1.0f, 255),
        hsvToArgb(1.0f / 6.0f, 1.0f, 1.0f, 255),
        hsvToArgb(2.0f / 6.0f, 1.0f, 1.0f, 255),
        hsvToArgb(3.0f / 6.0f, 1.0f, 1.0f, 255),
        hsvToArgb(4.0f / 6.0f, 1.0f, 1.0f, 255),
        hsvToArgb(5.0f / 6.0f, 1.0f, 1.0f, 255),
        hsvToArgb(0.0f / 6.0f, 1.0f, 1.0f, 255)
    };
    private static final int PURE_WHITE = hsvToArgb(0.0f, 0.0f, 1.0f, 255);
    private static final int TRANSPARENT_BLACK = hsvToArgb(0.0f, 0.0f, 0.0f, 0);
    private static final int OPAQUE_BLACK = hsvToArgb(0.0f, 0.0f, 0.0f, 255);

    /**
     * Bridge connecting the popup to its owning {@link ColorComponent} or custom color source.
     */
    public interface ColorModel {
        int getArgb();

        void setArgb(int argb);

        boolean isAllowAlpha();

        String getHexString();
    }

    private final ColorModel model;
    private PopupHost popupHost;
    private final TextFieldComponent hexField = new TextFieldComponent();
    private final float[] hsvScratch = new float[3];

    private boolean open = false;
    private int dragTarget = DRAG_NONE;
    private boolean suppressHexCallback = false;

    private float hue = 0.0f;
    private float saturation = 1.0f;
    private float brightness = 1.0f;
    private float alphaNorm = 1.0f;
    private int lastSyncedArgb = 0;
    private boolean hasSyncedOnce = false;

    private float anchorX = 0.0f;
    private float anchorY = 0.0f;
    private float anchorW = 0.0f;
    private float anchorH = 0.0f;

    private float popupX = 0.0f;
    private float popupY = 0.0f;
    private float popupH = 0.0f;
    private float svX = 0.0f;
    private float svY = 0.0f;
    private float hueX = 0.0f;
    private float hueY = 0.0f;
    private float alphaX = 0.0f;
    private float alphaY = 0.0f;
    private float hexX = 0.0f;
    private float hexY = 0.0f;

    /**
     * Creates a color picker popup bound to the provided {@link ColorModel} and optional {@link PopupHost}.
     *
     * @param model     backing color model
     * @param popupHost popup host, or {@code null} for standalone usage
     */
    public ColorPickerPopup(ColorModel model, PopupHost popupHost) {
        if (model == null) {
            throw new IllegalArgumentException("ColorPickerPopup model must not be null");
        }
        this.model = model;
        this.popupHost = popupHost;
        configureHexField();
    }

    private void configureHexField() {
        this.hexField.setMaxLength(this.model.isAllowAlpha() ? 9 : 7);
        this.hexField.setPlaceholder(this.model.isAllowAlpha() ? "#RRGGBBAA" : "#RRGGBB");
        this.hexField.setCharFilter(new TextFieldComponent.CharFilter() {
            @Override
            public boolean accept(char candidate, String currentText, int insertIndex) {
                if (candidate == '#') {
                    return insertIndex == 0 && currentText.indexOf('#') < 0;
                }
                return isHexDigit(candidate);
            }
        });
        this.hexField.setTextChangeListener(new TextFieldComponent.TextChangeListener() {
            @Override
            public void onTextChanged(String newText) {
                if (ColorPickerPopup.this.suppressHexCallback) {
                    return;
                }
                Integer parsed = ColorSetting.parseHexColor(newText, ColorPickerPopup.this.model.isAllowAlpha());
                if (parsed != null) {
                    applyExternalArgb(parsed.intValue(), false);
                }
            }
        });
        this.hexField.setSubmitListener(new TextFieldComponent.SubmitListener() {
            @Override
            public void onSubmit(String text) {
                commitHexText();
                ColorPickerPopup.this.hexField.setFocused(false);
            }
        });
    }

    /**
     * Updates the popup host reference used for viewport clamping.
     *
     * @param popupHost popup host, or {@code null} for full-screen fallback
     */
    public void setPopupHost(PopupHost popupHost) {
        this.popupHost = popupHost;
    }

    /**
     * Opens the color picker anchored near the specified trigger bounds.
     *
     * @param triggerX trigger logical X
     * @param triggerY trigger logical Y
     * @param triggerW trigger logical width
     * @param triggerH trigger logical height
     */
    public void openAt(float triggerX, float triggerY, float triggerW, float triggerH) {
        this.anchorX = triggerX;
        this.anchorY = triggerY;
        this.anchorW = triggerW;
        this.anchorH = triggerH;
        this.open = true;
        this.dragTarget = DRAG_NONE;
        this.hasSyncedOnce = false;
        syncFromModelIfChanged();
        computeLayout();
    }

    /**
     * Updates the anchor bounds when the parent swatch moves during scrolling or window drag.
     *
     * @param triggerX trigger logical X
     * @param triggerY trigger logical Y
     * @param triggerW trigger logical width
     * @param triggerH trigger logical height
     */
    public void updateAnchor(float triggerX, float triggerY, float triggerW, float triggerH) {
        this.anchorX = triggerX;
        this.anchorY = triggerY;
        this.anchorW = triggerW;
        this.anchorH = triggerH;
    }

    /**
     * Returns the computed logical height of this popup.
     *
     * @return popup height in logical pixels
     */
    public float getPopupHeight() {
        float extraAlpha = this.model.isAllowAlpha() ? (GAP + BAR_HEIGHT) : 0.0f;
        return PADDING * 2.0f + SV_HEIGHT + GAP + BAR_HEIGHT + extraAlpha + GAP + this.hexField.getHeight();
    }

    @Override
    public void renderOverlay(float mouseX, float mouseY, float alpha, ThemeData theme) {
        if (!this.open) {
            return;
        }
        ThemeData resolved = theme != null ? theme : com.hoptimization.ui.theme.ThemeManager.current();
        syncFromModelIfChanged();
        computeLayout();

        float effAlpha = clamp01(alpha);
        if (effAlpha <= 0.001f) {
            return;
        }

        if (com.hoptimization.ui.theme.ThemeManager.isFrostedGlass()) {
            boolean isLight = resolved.getMode() == com.hoptimization.ui.theme.ThemeMode.LIGHT;
            RenderUtil.drawFrostedRoundedRectWithShadow(
                this.popupX,
                this.popupY,
                POPUP_WIDTH,
                this.popupH,
                resolved.radiusCard(),
                RenderUtil.withAlpha(resolved.surface2(), isLight ? 0.74f : 0.66f),
                isLight ? resolved.borderStrong() : 0x38FFFFFF,
                UiTokens.BORDER_WIDTH,
                isLight ? 0.14f : 0.09f,
                effAlpha,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                resolved.popupShadowColor()
            );
        } else {
            RenderUtil.drawRoundedRectWithShadow(
                this.popupX,
                this.popupY,
                POPUP_WIDTH,
                this.popupH,
                resolved.radiusCard(),
                RenderUtil.withAlpha(resolved.surface2(), effAlpha),
                RenderUtil.withAlpha(resolved.border(), effAlpha),
                UiTokens.BORDER_WIDTH,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                RenderUtil.withAlpha(resolved.popupShadowColor(), effAlpha)
            );
        }

        renderSvSquare(resolved, effAlpha);
        renderHueBar(resolved, effAlpha);
        if (this.model.isAllowAlpha()) {
            renderAlphaBar(resolved, effAlpha);
        }
        this.hexField.render(this.hexX, this.hexY, SV_WIDTH, mouseX, mouseY, effAlpha, resolved);
    }

    @Override
    public boolean handleOverlayMouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.open) {
            return false;
        }
        computeLayout();
        if (hitRect(mouseX, mouseY, this.hexX, this.hexY, SV_WIDTH, this.hexField.getHeight())) {
            this.dragTarget = DRAG_NONE;
            return this.hexField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (this.hexField.isFocused()) {
            commitHexText();
            this.hexField.setFocused(false);
        }

        if (mouseButton != 0) {
            return isPointInsideOverlay(mouseX, mouseY);
        }

        if (hitRect(mouseX, mouseY, this.svX - 2.0f, this.svY - 2.0f, SV_WIDTH + 4.0f, SV_HEIGHT + 4.0f)) {
            this.dragTarget = DRAG_SV;
            updateSvFromMouse(mouseX, mouseY);
            return true;
        }
        if (hitRect(mouseX, mouseY, this.hueX - 2.0f, this.hueY - 3.0f, SV_WIDTH + 4.0f, BAR_HEIGHT + 6.0f)) {
            this.dragTarget = DRAG_HUE;
            updateHueFromMouse(mouseX);
            return true;
        }
        if (this.model.isAllowAlpha()
            && hitRect(mouseX, mouseY, this.alphaX - 2.0f, this.alphaY - 3.0f, SV_WIDTH + 4.0f, BAR_HEIGHT + 6.0f)) {
            this.dragTarget = DRAG_ALPHA;
            updateAlphaFromMouse(mouseX);
            return true;
        }
        return isPointInsideOverlay(mouseX, mouseY);
    }

    @Override
    public boolean handleOverlayMouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (!this.open) {
            return false;
        }
        if (this.hexField.isFocused()) {
            this.hexField.mouseReleased(mouseX, mouseY, mouseButton);
        }
        if (mouseButton == 0 && this.dragTarget != DRAG_NONE) {
            this.dragTarget = DRAG_NONE;
            return true;
        }
        return false;
    }

    @Override
    public boolean handleOverlayMouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (!this.open || mouseButton != 0) {
            return false;
        }
        if (this.dragTarget == DRAG_SV) {
            updateSvFromMouse(mouseX, mouseY);
            return true;
        }
        if (this.dragTarget == DRAG_HUE) {
            updateHueFromMouse(mouseX);
            return true;
        }
        if (this.dragTarget == DRAG_ALPHA) {
            updateAlphaFromMouse(mouseX);
            return true;
        }
        if (this.hexField.isFocused()) {
            return this.hexField.mouseDragged(mouseX, mouseY, mouseButton);
        }
        return false;
    }

    @Override
    public boolean handleOverlayMouseScrolled(float mouseX, float mouseY, int wheelDelta) {
        return this.open && isPointInsideOverlay(mouseX, mouseY);
    }

    @Override
    public boolean handleOverlayKeyTyped(char typedChar, int keyCode) {
        if (!this.open) {
            return false;
        }
        if (this.hexField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                commitHexText();
                this.hexField.setFocused(false);
                closeOverlay();
                return true;
            }
            return this.hexField.keyTyped(typedChar, keyCode);
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeOverlay();
            return true;
        }
        return false;
    }

    @Override
    public boolean isOverlayOpen() {
        return this.open;
    }

    @Override
    public void closeOverlay() {
        if (this.hexField.isFocused()) {
            commitHexText();
            this.hexField.setFocused(false);
        }
        this.open = false;
        this.dragTarget = DRAG_NONE;
    }

    @Override
    public boolean isPointInsideOverlay(float mouseX, float mouseY) {
        if (!this.open) {
            return false;
        }
        computeLayout();
        return hitRect(mouseX, mouseY, this.popupX, this.popupY, POPUP_WIDTH, this.popupH);
    }

    /**
     * Converts packed {@code 0xAARRGGBB} RGB channels into HSV {@code [0..1]} stored in {@code outHsv}.
     * Zero-allocation helper usable outside GL contexts.
     *
     * @param argb   packed ARGB color
     * @param outHsv destination array of length >= 3 ({@code [hue, saturation, value]})
     */
    public static void rgbToHsv(int argb, float[] outHsv) {
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float h = 0.0f;
        if (delta > 1e-6f) {
            if (max == r) {
                h = ((g - b) / delta) % 6.0f;
            } else if (max == g) {
                h = ((b - r) / delta) + 2.0f;
            } else {
                h = ((r - g) / delta) + 4.0f;
            }
            h /= 6.0f;
            if (h < 0.0f) {
                h += 1.0f;
            }
        }

        float s = max > 1e-6f ? (delta / max) : 0.0f;
        outHsv[0] = h;
        outHsv[1] = s;
        outHsv[2] = max;
    }

    /**
     * Converts normalized HSV components and alpha byte {@code [0..255]} into packed ARGB.
     *
     * @param hue        hue in {@code [0, 1]}
     * @param saturation saturation in {@code [0, 1]}
     * @param brightness value/brightness in {@code [0, 1]}
     * @param alphaByte  alpha channel in {@code [0, 255]}
     * @return packed {@code 0xAARRGGBB} integer
     */
    public static int hsvToArgb(float hue, float saturation, float brightness, int alphaByte) {
        float h = hue - (float) Math.floor(hue);
        float s = clamp01(saturation);
        float v = clamp01(brightness);
        int a = clampByte(alphaByte);

        float sector = h * 6.0f;
        int idx = (int) Math.floor(sector) % 6;
        float f = sector - (float) Math.floor(sector);
        float p = v * (1.0f - s);
        float q = v * (1.0f - f * s);
        float t = v * (1.0f - (1.0f - f) * s);

        float r;
        float g;
        float b;
        switch (idx) {
            case 0:
                r = v;
                g = t;
                b = p;
                break;
            case 1:
                r = q;
                g = v;
                b = p;
                break;
            case 2:
                r = p;
                g = v;
                b = t;
                break;
            case 3:
                r = p;
                g = q;
                b = v;
                break;
            case 4:
                r = t;
                g = p;
                b = v;
                break;
            default:
                r = v;
                g = p;
                b = q;
                break;
        }
        return (a << 24)
            | (clampByte(Math.round(r * 255.0f)) << 16)
            | (clampByte(Math.round(g * 255.0f)) << 8)
            | clampByte(Math.round(b * 255.0f));
    }

    private void renderSvSquare(ThemeData theme, float effAlpha) {
        int pureHue = hsvToArgb(this.hue, 1.0f, 1.0f, 255);
        RenderUtil.drawHorizontalGradient(
            this.svX,
            this.svY,
            SV_WIDTH,
            SV_HEIGHT,
            RenderUtil.withAlpha(PURE_WHITE, effAlpha),
            RenderUtil.withAlpha(pureHue, effAlpha)
        );
        RenderUtil.drawVerticalGradient(
            this.svX,
            this.svY,
            SV_WIDTH,
            SV_HEIGHT,
            TRANSPARENT_BLACK,
            RenderUtil.withAlpha(OPAQUE_BLACK, effAlpha)
        );
        RenderUtil.drawRoundedBorder(
            this.svX,
            this.svY,
            SV_WIDTH,
            SV_HEIGHT,
            0.0f,
            UiTokens.BORDER_WIDTH,
            RenderUtil.withAlpha(theme.border(), effAlpha)
        );

        float cx = this.svX + this.saturation * SV_WIDTH;
        float cy = this.svY + (1.0f - this.brightness) * SV_HEIGHT;
        float ringD = INDICATOR_RADIUS * 2.0f;
        int currentOpaque = hsvToArgb(this.hue, this.saturation, this.brightness, 255);
        RenderUtil.drawRoundedRect(
            cx - INDICATOR_RADIUS,
            cy - INDICATOR_RADIUS,
            ringD,
            ringD,
            theme.radiusPill(ringD),
            RenderUtil.withAlpha(currentOpaque, effAlpha),
            RenderUtil.withAlpha(theme.thumb(), effAlpha),
            1.5f
        );
    }

    private void renderHueBar(ThemeData theme, float effAlpha) {
        float segW = SV_WIDTH / 6.0f;
        for (int i = 0; i < 6; i++) {
            float sx = this.hueX + i * segW;
            float sw = (i == 5) ? (this.hueX + SV_WIDTH - sx) : segW;
            RenderUtil.drawHorizontalGradient(
                sx,
                this.hueY,
                sw,
                BAR_HEIGHT,
                RenderUtil.withAlpha(HUE_STOPS[i], effAlpha),
                RenderUtil.withAlpha(HUE_STOPS[i + 1], effAlpha)
            );
        }
        RenderUtil.drawRoundedBorder(
            this.hueX,
            this.hueY,
            SV_WIDTH,
            BAR_HEIGHT,
            0.0f,
            UiTokens.BORDER_WIDTH,
            RenderUtil.withAlpha(theme.border(), effAlpha)
        );

        float thumbX = this.hueX + this.hue * SV_WIDTH;
        float thumbY = this.hueY + BAR_HEIGHT * 0.5f;
        int hueColor = hsvToArgb(this.hue, 1.0f, 1.0f, 255);
        RenderUtil.drawRoundedRect(
            thumbX - BAR_HEIGHT * 0.5f,
            thumbY - BAR_HEIGHT * 0.5f,
            BAR_HEIGHT,
            BAR_HEIGHT,
            theme.radiusPill(BAR_HEIGHT),
            RenderUtil.withAlpha(hueColor, effAlpha),
            RenderUtil.withAlpha(theme.thumb(), effAlpha),
            1.5f
        );
    }

    private void renderAlphaBar(ThemeData theme, float effAlpha) {
        drawCheckerboard(this.alphaX, this.alphaY, SV_WIDTH, BAR_HEIGHT, theme, effAlpha);
        int opaqueColor = hsvToArgb(this.hue, this.saturation, this.brightness, 255);
        int transparentColor = RenderUtil.replaceAlpha(opaqueColor, 0);
        RenderUtil.drawHorizontalGradient(
            this.alphaX,
            this.alphaY,
            SV_WIDTH,
            BAR_HEIGHT,
            transparentColor,
            RenderUtil.withAlpha(opaqueColor, effAlpha)
        );
        RenderUtil.drawRoundedBorder(
            this.alphaX,
            this.alphaY,
            SV_WIDTH,
            BAR_HEIGHT,
            0.0f,
            UiTokens.BORDER_WIDTH,
            RenderUtil.withAlpha(theme.border(), effAlpha)
        );

        float thumbX = this.alphaX + this.alphaNorm * SV_WIDTH;
        float thumbY = this.alphaY + BAR_HEIGHT * 0.5f;
        RenderUtil.drawRoundedRect(
            thumbX - BAR_HEIGHT * 0.5f,
            thumbY - BAR_HEIGHT * 0.5f,
            BAR_HEIGHT,
            BAR_HEIGHT,
            theme.radiusPill(BAR_HEIGHT),
            RenderUtil.withAlpha(theme.thumb(), effAlpha),
            RenderUtil.withAlpha(theme.borderStrong(), effAlpha),
            UiTokens.BORDER_WIDTH
        );
    }

    /**
     * Draws a theme-token checkerboard inside {@code (x, y, w, h)} using zero allocations.
     */
    static void drawCheckerboard(
        float x,
        float y,
        float w,
        float h,
        ThemeData theme,
        float effAlpha
    ) {
        int lightCell = RenderUtil.withAlpha(theme.surface4(), effAlpha);
        int darkCell = RenderUtil.withAlpha(theme.surface2(), effAlpha);
        RenderUtil.drawRect(x, y, w, h, darkCell);

        int cols = (int) Math.ceil(w / (double) CHECKER_CELL);
        int rows = (int) Math.ceil(h / (double) CHECKER_CELL);
        for (int r = 0; r < rows; r++) {
            float cy = y + r * CHECKER_CELL;
            float ch = Math.min(CHECKER_CELL, y + h - cy);
            for (int c = 0; c < cols; c++) {
                if (((r + c) & 1) == 0) {
                    float cx = x + c * CHECKER_CELL;
                    float cw = Math.min(CHECKER_CELL, x + w - cx);
                    RenderUtil.drawRect(cx, cy, cw, ch, lightCell);
                }
            }
        }
    }

    private void computeLayout() {
        this.popupH = getPopupHeight();
        float vpX = this.popupHost != null ? this.popupHost.getViewportX() : 0.0f;
        float vpY = this.popupHost != null ? this.popupHost.getViewportY() : 0.0f;
        float vpW = this.popupHost != null ? this.popupHost.getViewportWidth() : UiScale.getLogicalWidth();
        float vpH = this.popupHost != null ? this.popupHost.getViewportHeight() : UiScale.getLogicalHeight();
        float margin = UiTokens.SPACE_4;

        float px = this.anchorX + this.anchorW - POPUP_WIDTH;
        if (px < vpX + margin) {
            px = this.anchorX;
        }
        if (px + POPUP_WIDTH > vpX + vpW - margin) {
            px = vpX + vpW - margin - POPUP_WIDTH;
        }
        if (px < vpX + margin) {
            px = vpX + margin;
        }
        this.popupX = px;

        float belowY = this.anchorY + this.anchorH + POPUP_GAP;
        float aboveY = this.anchorY - POPUP_GAP - this.popupH;
        float py;
        if (belowY + this.popupH <= vpY + vpH - margin) {
            py = belowY;
        } else if (aboveY >= vpY + margin) {
            py = aboveY;
        } else {
            py = clamp(belowY, vpY + margin, Math.max(vpY + margin, vpY + vpH - margin - this.popupH));
        }
        this.popupY = py;

        this.svX = this.popupX + PADDING;
        this.svY = this.popupY + PADDING;
        this.hueX = this.svX;
        this.hueY = this.svY + SV_HEIGHT + GAP;

        float nextY = this.hueY + BAR_HEIGHT + GAP;
        if (this.model.isAllowAlpha()) {
            this.alphaX = this.svX;
            this.alphaY = nextY;
            nextY += BAR_HEIGHT + GAP;
        }
        this.hexX = this.svX;
        this.hexY = nextY;
    }

    private void updateSvFromMouse(float mouseX, float mouseY) {
        this.saturation = clamp01((mouseX - this.svX) / SV_WIDTH);
        this.brightness = 1.0f - clamp01((mouseY - this.svY) / SV_HEIGHT);
        emitCurrentHsvToModel();
    }

    private void updateHueFromMouse(float mouseX) {
        this.hue = clamp01((mouseX - this.hueX) / SV_WIDTH);
        emitCurrentHsvToModel();
    }

    private void updateAlphaFromMouse(float mouseX) {
        this.alphaNorm = clamp01((mouseX - this.alphaX) / SV_WIDTH);
        emitCurrentHsvToModel();
    }

    private void emitCurrentHsvToModel() {
        int alphaByte = this.model.isAllowAlpha() ? clampByte(Math.round(this.alphaNorm * 255.0f)) : 255;
        int packed = hsvToArgb(this.hue, this.saturation, this.brightness, alphaByte);
        this.lastSyncedArgb = packed;
        this.hasSyncedOnce = true;
        this.model.setArgb(packed);
        syncHexFieldFromModel();
    }

    private void syncFromModelIfChanged() {
        int current = this.model.getArgb();
        if (!this.hasSyncedOnce || current != this.lastSyncedArgb) {
            applyExternalArgb(current, !this.hexField.isFocused());
        }
    }

    private void applyExternalArgb(int argb, boolean updateHexText) {
        int alphaByte = this.model.isAllowAlpha() ? ((argb >>> 24) & 0xFF) : 255;
        int normalizedArgb = RenderUtil.replaceAlpha(argb, alphaByte);

        rgbToHsv(normalizedArgb, this.hsvScratch);
        // Preserve hue when saturation/brightness drops to zero so dragging corners does not snap hue to red.
        if (this.hsvScratch[1] > 1e-4f && this.hsvScratch[2] > 1e-4f) {
            this.hue = this.hsvScratch[0];
        }
        if (this.hsvScratch[2] > 1e-4f) {
            this.saturation = this.hsvScratch[1];
        }
        this.brightness = this.hsvScratch[2];
        this.alphaNorm = alphaByte / 255.0f;

        this.lastSyncedArgb = normalizedArgb;
        this.hasSyncedOnce = true;
        this.model.setArgb(normalizedArgb);
        if (updateHexText) {
            syncHexFieldFromModel();
        }
    }

    private void commitHexText() {
        Integer parsed = ColorSetting.parseHexColor(this.hexField.getText(), this.model.isAllowAlpha());
        if (parsed != null) {
            applyExternalArgb(parsed.intValue(), true);
        } else {
            syncHexFieldFromModel();
        }
    }

    private void syncHexFieldFromModel() {
        this.suppressHexCallback = true;
        try {
            this.hexField.setTextSilent(this.model.getHexString());
        } finally {
            this.suppressHexCallback = false;
        }
    }

    private static boolean hitRect(float px, float py, float x, float y, float w, float h) {
        return w > 0.0f && h > 0.0f && px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
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

    private static float clamp(float value, float min, float max) {
        if (value <= min) {
            return min;
        }
        if (value >= max) {
            return max;
        }
        return value;
    }

    private static int clampByte(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= 255) {
            return 255;
        }
        return value;
    }
}
