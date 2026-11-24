package com.hoptimization.ui.component;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.FontToken;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeMode;
import com.hoptimization.ui.theme.UiTokens;
import org.lwjgl.input.Keyboard;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric slider control bound to a {@link NumberSetting} (or standalone numeric range).
 * Features a {@code 4px} pill track ({@code surface3}), {@code accent} fill, {@code 12x12} {@code thumb}
 * ({@code 1px border} on light themes), {@code 4px} {@code accentMuted} hover/drag halo,
 * {@code Shift} fine-precision drag ({@code step / 10}), and double-click inline numeric typing.
 */
public class SliderComponent extends UiComponent {
    public static final float TRACK_HEIGHT = 4.0f;
    public static final float THUMB_SIZE = 12.0f;
    public static final float THUMB_RADIUS = 6.0f;
    public static final float HALO_EXTRA = 4.0f;
    public static final float INLINE_HEIGHT = 24.0f;
    public static final float STACKED_HEIGHT = 40.0f;

    private static final long DOUBLE_CLICK_NANOS = 350_000_000L;
    private static final float MIN_VALUE_BOX_WIDTH = 36.0f;
    private static final float EDIT_BOX_MIN_WIDTH = 52.0f;

    /**
     * Arrangement of track, value readout, and optional label.
     */
    public enum Layout {
        /** Single row: slider track on the left, value + unit on the right (24px height). */
        INLINE,
        /** Two rows: optional label top-left, value + unit top-right, full-width track bottom (40px height). */
        STACKED
    }

    /**
     * Listener invoked when a standalone slider value changes.
     */
    public interface ChangeListener {
        void onValueChanged(double newValue);
    }

    private final NumberSetting setting;
    private final ChangeListener changeListener;
    private final double min;
    private final double max;
    private final double step;
    private final String unit;
    private final int baseScale;

    private double standaloneValue;
    private String standaloneFormattedValue;
    private String labelText = "";

    private Layout layout = Layout.INLINE;
    private boolean showLabel = false;

    private final Animation progressAnim;
    private final Animation haloAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final TextFieldComponent inlineField = new TextFieldComponent();

    private boolean dragging = false;
    private boolean editingValue = false;
    private long lastValueClickNanos = 0L;

    private float trackLeft = 0.0f;
    private float trackVisualW = 0.0f;
    private float trackCenterY = 0.0f;
    private float valueHitX = 0.0f;
    private float valueHitY = 0.0f;
    private float valueHitW = 0.0f;
    private float valueHitH = 0.0f;
    private float editBoxX = 0.0f;
    private float editBoxY = 0.0f;
    private float editBoxW = EDIT_BOX_MIN_WIDTH;

    private String reservedMeasureUnit;
    private TtfFontRenderer reservedMeasureFont;
    private float cachedValueReserveWidth = MIN_VALUE_BOX_WIDTH;

    /**
     * Creates an inline slider bound directly to a {@link NumberSetting}.
     *
     * @param setting numeric setting to control
     */
    public SliderComponent(NumberSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("SliderComponent setting must not be null");
        }
        this.setting = setting;
        this.changeListener = null;
        this.min = setting.getMin();
        this.max = setting.getMax();
        this.step = setting.getStep();
        this.unit = setting.getUnit();
        this.baseScale = computeScale(this.step);
        this.standaloneValue = setting.getDoubleValue();
        this.standaloneFormattedValue = setting.getFormattedValue();
        this.progressAnim = new Animation((float) setting.getProgress(), AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
        configureInlineField();
    }

    /**
     * Creates a standalone slider for non-module numeric settings (such as theme style parameters).
     *
     * @param initialValue   initial numeric value
     * @param min            inclusive minimum
     * @param max            inclusive maximum
     * @param step           standard increment step
     * @param unit           optional display unit suffix
     * @param changeListener optional listener invoked when value changes
     */
    public SliderComponent(
        double initialValue,
        double min,
        double max,
        double step,
        String unit,
        ChangeListener changeListener
    ) {
        if (min > max || step <= 0.0D) {
            throw new IllegalArgumentException("Invalid slider range/step");
        }
        this.setting = null;
        this.changeListener = changeListener;
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit == null ? "" : unit;
        this.baseScale = computeScale(step);
        this.standaloneValue = clampDouble(initialValue, min, max);
        this.standaloneFormattedValue = formatStandaloneNumber(this.standaloneValue, this.baseScale);
        this.progressAnim = new Animation((float) getProgress(), AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
        configureInlineField();
    }

    private void configureInlineField() {
        this.inlineField.setFontToken(FontToken.CAPTION);
        this.inlineField.setHeight(UiTokens.SPACE_20);
        this.inlineField.setMaxLength(24);
        this.inlineField.setCharFilter(new TextFieldComponent.CharFilter() {
            @Override
            public boolean accept(char candidate, String currentText, int insertIndex) {
                if (candidate >= '0' && candidate <= '9') {
                    return true;
                }
                if (candidate == '.') {
                    return currentText.indexOf('.') < 0;
                }
                if (candidate == '-') {
                    return insertIndex == 0 && currentText.indexOf('-') < 0;
                }
                return false;
            }
        });
        this.inlineField.setSubmitListener(new TextFieldComponent.SubmitListener() {
            @Override
            public void onSubmit(String text) {
                commitInlineEdit();
            }
        });
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
        float totalW = Math.max(0.0f, width);
        float totalH = getHeight();

        double targetProgress = getProgress();
        if (this.dragging) {
            this.progressAnim.snapTo((float) targetProgress);
        } else {
            this.progressAnim.setTarget((float) targetProgress);
        }

        computeGeometry(x, y, totalW);

        boolean insideTrack = hitTestMin20(
            mouseX,
            mouseY,
            this.trackLeft - THUMB_RADIUS,
            this.trackCenterY - THUMB_RADIUS,
            this.trackVisualW + THUMB_SIZE,
            THUMB_SIZE
        );
        boolean insideComponent = hitTest(mouseX, mouseY, x, y, totalW, totalH);
        updateBounds(x, y, totalW, totalH, insideComponent);

        boolean trackHovered = this.enabled && (insideTrack || this.dragging);
        this.haloAnim.setTarget(trackHovered ? 1.0f : 0.0f);

        float effAlpha = effectiveAlpha(alpha);
        if (effAlpha <= 0.001f || totalW <= 0.0f) {
            return;
        }

        if (this.layout == Layout.STACKED && this.showLabel) {
            String label = getResolvedLabel();
            if (!label.isEmpty()) {
                TtfFontRenderer labelFont = FontManager.bodyRegular();
                float labelMaxW = Math.max(0.0f, totalW - this.valueHitW - UiTokens.SPACE_8);
                labelFont.drawStringWithEllipsis(
                    label,
                    x,
                    y + UiTokens.SPACE_2,
                    labelMaxW,
                    RenderUtil.withAlpha(resolved.textSecondary(), effAlpha)
                );
            }
        }

        if (this.editingValue) {
            this.inlineField.render(
                this.editBoxX,
                this.editBoxY,
                this.editBoxW,
                mouseX,
                mouseY,
                effAlpha,
                resolved
            );
            if (!this.inlineField.isFocused()) {
                commitInlineEdit();
            }
        } else {
            renderValueReadout(resolved, effAlpha);
        }

        renderTrackAndThumb(resolved, effAlpha);
    }

    @Override
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled) {
            return false;
        }

        if (this.editingValue) {
            if (hitTest(mouseX, mouseY, this.editBoxX, this.editBoxY, this.editBoxW, this.inlineField.getHeight())) {
                return this.inlineField.mouseClicked(mouseX, mouseY, mouseButton);
            }
            commitInlineEdit();
            return true;
        }

        if (mouseButton != 0) {
            return false;
        }

        if (hitTestMin20(mouseX, mouseY, this.valueHitX, this.valueHitY, this.valueHitW, this.valueHitH)) {
            long now = System.nanoTime();
            if (now - this.lastValueClickNanos <= DOUBLE_CLICK_NANOS) {
                this.lastValueClickNanos = 0L;
                beginInlineEdit();
            } else {
                this.lastValueClickNanos = now;
            }
            return true;
        }

        boolean insideTrack = hitTestMin20(
            mouseX,
            mouseY,
            this.trackLeft - THUMB_RADIUS,
            this.trackCenterY - THUMB_RADIUS,
            this.trackVisualW + THUMB_SIZE,
            THUMB_SIZE
        );
        if (insideTrack && this.trackVisualW > 0.0f) {
            this.dragging = true;
            this.lastValueClickNanos = 0L;
            updateFromMouseX(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled) {
            return false;
        }
        if (this.editingValue) {
            return this.inlineField.mouseDragged(mouseX, mouseY, mouseButton);
        }
        if (!this.dragging || mouseButton != 0) {
            return false;
        }
        updateFromMouseX(mouseX);
        return true;
    }

    @Override
    public boolean mouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (this.editingValue) {
            return this.inlineField.mouseReleased(mouseX, mouseY, mouseButton);
        }
        if (mouseButton == 0 && this.dragging) {
            this.dragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!this.enabled || !this.editingValue) {
            return false;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            cancelInlineEdit();
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitInlineEdit();
            return true;
        }
        return this.inlineField.keyTyped(typedChar, keyCode);
    }

    @Override
    public float getHeight() {
        return this.layout == Layout.STACKED ? STACKED_HEIGHT : INLINE_HEIGHT;
    }

    @Override
    public void refreshLabels() {
        if (this.setting != null) {
            this.labelText = this.setting.getDisplayName();
        }
    }

    /**
     * Sets the slider layout mode ({@link Layout#INLINE} 24px or {@link Layout#STACKED} 40px).
     *
     * @param layout target layout mode
     */
    public void setLayout(Layout layout) {
        if (layout != null) {
            this.layout = layout;
        }
    }

    /**
     * Returns the active layout mode.
     *
     * @return current layout
     */
    public Layout getLayout() {
        return this.layout;
    }

    /**
     * Controls whether {@link Layout#STACKED} renders the setting display name on the top-left.
     *
     * @param showLabel {@code true} to render the setting name in stacked mode
     */
    public void setShowLabel(boolean showLabel) {
        this.showLabel = showLabel;
    }

    /**
     * Returns whether inline numeric editing mode is currently active.
     *
     * @return {@code true} if editing inline text
     */
    public boolean isEditingValue() {
        return this.editingValue;
    }

    /**
     * Returns whether the slider thumb is currently being dragged.
     *
     * @return {@code true} if dragging
     */
    public boolean isDragging() {
        return this.dragging;
    }

    /**
     * Enters inline numeric editing mode (normally triggered by double-clicking the value text).
     */
    public void beginInlineEdit() {
        if (!this.enabled) {
            return;
        }
        this.dragging = false;
        this.editingValue = true;
        this.inlineField.setTextSilent(getFormattedValue());
        this.inlineField.setFocused(true);
        this.inlineField.selectAll();
    }

    /**
     * Commits the typed inline number (clamped to {@code [min, max]}) and exits editing mode.
     * Invalid input restores the previous value without error.
     */
    public void commitInlineEdit() {
        if (!this.editingValue) {
            return;
        }
        String raw = this.inlineField.getText();
        this.editingValue = false;
        this.inlineField.setFocused(false);
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (!isParsableNumber(trimmed)) {
            return;
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            if (!Double.isNaN(parsed) && !Double.isInfinite(parsed)) {
                setExactValue(parsed);
            }
        } catch (NumberFormatException e) {
            HoptimizationMod.LOGGER.warn("Failed to parse inline slider number '{}'", trimmed, e);
        }
    }

    private static boolean isParsableNumber(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasDot = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c == '.' && !hasDot) {
                hasDot = true;
            } else if (c == '-' && i == 0) {
                continue;
            } else {
                return false;
            }
        }
        return hasDigit;
    }

    /**
     * Cancels inline numeric editing without modifying the current value.
     */
    public void cancelInlineEdit() {
        if (!this.editingValue) {
            return;
        }
        this.editingValue = false;
        this.inlineField.setFocused(false);
    }

    /**
     * Returns the current numeric value.
     *
     * @return current value
     */
    public double getValue() {
        return this.setting != null ? this.setting.getDoubleValue() : this.standaloneValue;
    }

    /**
     * Updates the value snapped to either normal {@code step} or fine {@code step / 10}.
     *
     * @param progress normalized slider progress in {@code [0, 1]}
     * @param fine     {@code true} for Shift fine-precision snapping
     */
    public void setFromProgress(double progress, boolean fine) {
        if (this.setting != null) {
            this.setting.setFromProgress(progress, fine);
            this.standaloneValue = this.setting.getDoubleValue();
            this.standaloneFormattedValue = this.setting.getFormattedValue();
        } else {
            double clampedProgress = clampDouble(progress, 0.0D, 1.0D);
            double mapped = this.min + clampedProgress * (this.max - this.min);
            double activeStep = fine ? (this.step * 0.1D) : this.step;
            double snapped = this.min + Math.round((mapped - this.min) / activeStep) * activeStep;
            int snapScale = Math.min(4, this.baseScale + (fine ? 1 : 0));
            double clean = BigDecimal.valueOf(snapped)
                .setScale(snapScale, RoundingMode.HALF_UP)
                .doubleValue();
            setExactValue(clean);
        }
    }

    /**
     * Sets an exact value clamped to {@code [min, max]} (used by inline numeric typing).
     *
     * @param value candidate numeric value
     */
    public void setExactValue(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        if (this.setting != null) {
            this.setting.setValue(Double.valueOf(value));
            this.standaloneValue = this.setting.getDoubleValue();
            this.standaloneFormattedValue = this.setting.getFormattedValue();
        } else {
            double clamped = clampDouble(value, this.min, this.max);
            if (Double.compare(this.standaloneValue, clamped) != 0) {
                this.standaloneValue = clamped;
                this.standaloneFormattedValue = formatStandaloneNumber(clamped, this.baseScale);
                if (this.changeListener != null) {
                    this.changeListener.onValueChanged(clamped);
                }
            }
        }
    }

    /**
     * Returns normalized progress in {@code [0, 1]}.
     *
     * @return progress ratio
     */
    public double getProgress() {
        if (this.setting != null) {
            return this.setting.getProgress();
        }
        double range = this.max - this.min;
        if (range <= 0.0D) {
            return 0.0D;
        }
        return clampDouble((this.standaloneValue - this.min) / range, 0.0D, 1.0D);
    }

    /**
     * Returns the formatted numeric value string without unit.
     *
     * @return formatted number string
     */
    public String getFormattedValue() {
        return this.setting != null ? this.setting.getFormattedValue() : this.standaloneFormattedValue;
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (!enabled) {
            this.dragging = false;
            cancelInlineEdit();
        }
    }

    private void renderValueReadout(ThemeData theme, float effAlpha) {
        TtfFontRenderer valueFont = FontManager.bodyRegular();
        TtfFontRenderer unitFont = FontManager.caption();
        String valueStr = getFormattedValue();
        float rightEdge = this.x + this.width;
        float valueY = this.valueHitY + (this.valueHitH - valueFont.getHeight()) * 0.5f;

        if (!this.unit.isEmpty()) {
            float unitW = unitFont.getWidth(this.unit);
            float unitGap = "%".equals(this.unit) ? 0.0f : UiTokens.SPACE_2;
            float unitY = valueY + (valueFont.getAscent() - unitFont.getAscent());
            unitFont.drawString(
                this.unit,
                rightEdge - unitW,
                unitY,
                RenderUtil.withAlpha(theme.textSecondary(), effAlpha)
            );
            float valW = valueFont.getWidth(valueStr);
            valueFont.drawString(
                valueStr,
                rightEdge - unitW - unitGap - valW,
                valueY,
                RenderUtil.withAlpha(theme.textPrimary(), effAlpha)
            );
        } else {
            valueFont.drawStringRight(
                valueStr,
                rightEdge,
                valueY,
                RenderUtil.withAlpha(theme.textPrimary(), effAlpha)
            );
        }
    }

    private void renderTrackAndThumb(ThemeData theme, float effAlpha) {
        if (this.trackVisualW <= 0.0f) {
            return;
        }
        float trackY = this.trackCenterY - TRACK_HEIGHT * 0.5f;
        float trackRadius = theme.radiusPill(TRACK_HEIGHT);

        RenderUtil.drawRoundedRect(
            this.trackLeft,
            trackY,
            this.trackVisualW,
            TRACK_HEIGHT,
            trackRadius,
            RenderUtil.withAlpha(theme.surface3(), effAlpha)
        );

        float t = clamp01(this.progressAnim.get());
        float thumbCenterX = this.trackLeft + this.trackVisualW * t;
        float filledW = thumbCenterX - this.trackLeft;
        if (filledW > 0.5f) {
            float drawFillW = Math.min(this.trackVisualW, Math.max(TRACK_HEIGHT, filledW));
            RenderUtil.drawRoundedRect(
                this.trackLeft,
                trackY,
                drawFillW,
                TRACK_HEIGHT,
                trackRadius,
                RenderUtil.withAlpha(theme.accent(), effAlpha)
            );
        }

        float haloT = clamp01(this.haloAnim.get());
        if (haloT > 0.01f) {
            float haloRadius = THUMB_RADIUS + HALO_EXTRA * haloT;
            float haloDiameter = haloRadius * 2.0f;
            RenderUtil.drawRoundedRect(
                thumbCenterX - haloRadius,
                this.trackCenterY - haloRadius,
                haloDiameter,
                haloDiameter,
                theme.radiusPill(haloDiameter),
                RenderUtil.withAlpha(theme.accentMuted(), haloT * effAlpha)
            );
        }

        float thumbX = thumbCenterX - THUMB_RADIUS;
        float thumbY = this.trackCenterY - THUMB_RADIUS;
        float thumbRadius = theme.radiusPill(THUMB_SIZE);
        boolean isLight = theme.getMode() == ThemeMode.LIGHT;
        int thumbBorder = isLight ? RenderUtil.withAlpha(theme.border(), effAlpha) : 0;
        float borderWidth = isLight ? UiTokens.BORDER_WIDTH : 0.0f;

        RenderUtil.drawRoundedRect(
            thumbX,
            thumbY,
            THUMB_SIZE,
            THUMB_SIZE,
            thumbRadius,
            RenderUtil.withAlpha(theme.thumb(), effAlpha),
            thumbBorder,
            borderWidth
        );
    }

    private void computeGeometry(float x, float y, float width) {
        TtfFontRenderer valueFont = FontManager.bodyRegular();
        TtfFontRenderer unitFont = FontManager.caption();
        ensureReservedValueWidth(valueFont, unitFont);

        float edgePad = THUMB_RADIUS + HALO_EXTRA * 0.5f;
        if (this.layout == Layout.STACKED) {
            this.valueHitH = UiTokens.SPACE_20;
            this.valueHitY = y;
            this.valueHitW = Math.min(width, Math.max(MIN_VALUE_BOX_WIDTH, this.cachedValueReserveWidth));
            this.valueHitX = x + width - this.valueHitW;

            this.trackLeft = x + edgePad;
            this.trackVisualW = Math.max(0.0f, width - edgePad * 2.0f);
            this.trackCenterY = y + STACKED_HEIGHT - (THUMB_RADIUS + HALO_EXTRA);

            this.editBoxW = Math.min(width, Math.max(EDIT_BOX_MIN_WIDTH, this.valueHitW + UiTokens.SPACE_8));
            this.editBoxX = x + width - this.editBoxW;
            this.editBoxY = y;
        } else {
            float reserveW = Math.min(width * 0.45f, this.cachedValueReserveWidth);
            this.valueHitW = Math.max(MIN_VALUE_BOX_WIDTH, reserveW);
            this.valueHitX = x + width - this.valueHitW;
            this.valueHitY = y + (INLINE_HEIGHT - UiTokens.SPACE_20) * 0.5f;
            this.valueHitH = UiTokens.SPACE_20;

            float trackAreaW = Math.max(0.0f, width - this.valueHitW - UiTokens.SPACE_8);
            this.trackLeft = x + edgePad;
            this.trackVisualW = Math.max(0.0f, trackAreaW - edgePad * 2.0f);
            this.trackCenterY = y + INLINE_HEIGHT * 0.5f;

            this.editBoxW = Math.min(width, Math.max(EDIT_BOX_MIN_WIDTH, this.valueHitW + UiTokens.SPACE_4));
            this.editBoxX = x + width - this.editBoxW;
            this.editBoxY = y + (INLINE_HEIGHT - this.inlineField.getHeight()) * 0.5f;
        }
    }

    private void ensureReservedValueWidth(TtfFontRenderer valueFont, TtfFontRenderer unitFont) {
        if (this.reservedMeasureFont == valueFont && this.unit.equals(this.reservedMeasureUnit)) {
            return;
        }
        this.reservedMeasureFont = valueFont;
        this.reservedMeasureUnit = this.unit;

        int maxScale = Math.min(4, this.baseScale + 1);
        String minSample = formatStandaloneNumber(this.min, maxScale);
        String maxSample = formatStandaloneNumber(this.max, maxScale);
        float numW = Math.max(valueFont.getWidth(minSample), valueFont.getWidth(maxSample));
        float unitW = this.unit.isEmpty() ? 0.0f : unitFont.getWidth(this.unit);
        float unitGap = this.unit.isEmpty() || "%".equals(this.unit) ? 0.0f : UiTokens.SPACE_2;
        this.cachedValueReserveWidth = Math.max(MIN_VALUE_BOX_WIDTH, numW + unitGap + unitW + UiTokens.SPACE_4);
    }

    private void updateFromMouseX(float mouseX) {
        if (this.trackVisualW <= 0.0f) {
            return;
        }
        double progress = (mouseX - this.trackLeft) / (double) this.trackVisualW;
        boolean fine = isFineShift();
        setFromProgress(progress, fine);
        this.progressAnim.snapTo((float) getProgress());
    }

    private String getResolvedLabel() {
        if (this.labelText.isEmpty() && this.setting != null) {
            this.labelText = this.setting.getDisplayName();
        }
        return this.labelText;
    }

    private static boolean isFineShift() {
        if (!Keyboard.isCreated()) {
            return false;
        }
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    private static int computeScale(double step) {
        BigDecimal bd = BigDecimal.valueOf(step).stripTrailingZeros();
        return Math.max(0, Math.min(4, bd.scale()));
    }

    private static String formatStandaloneNumber(double value, int baseScale) {
        int displayScale = Math.min(4, baseScale + 2);
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(displayScale, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    private static double clampDouble(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
