package com.hoptimization.ui.component;

import com.hoptimization.core.setting.ModeSetting;
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
 * Horizontal segmented option selector (height {@code 24}, {@code surface3} track,
 * {@code radiusControl}, smoothly sliding {@code surface4} active block in {@code 180ms})
 * designed for {@link ModeSetting} with up to 4 options or standalone mode selectors.
 */
public class SegmentedComponent extends UiComponent {
    public static final float HEIGHT = 24.0f;
    public static final float INNER_PAD = UiTokens.SPACE_2;
    private static final float MIN_SEGMENT_WIDTH = 40.0f;

    /**
     * Listener invoked when the active option index changes in standalone mode.
     */
    public interface SelectionListener {
        void onSelected(int index, String label);
    }

    private final ModeSetting setting;
    private final SelectionListener selectionListener;
    private final String[] cachedLabels;
    private boolean labelsInitialized;
    private int selectedIndex;

    private final Animation slideAnim;
    private final Animation[] hoverAnims;
    private float cachedPreferredWidth = -1.0f;
    private TtfFontRenderer preferredMeasureFont;

    /**
     * Creates a segmented control bound to a {@link ModeSetting}.
     *
     * @param setting discrete mode setting (recommended 2 to 4 options)
     */
    public SegmentedComponent(ModeSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("SegmentedComponent setting must not be null");
        }
        this.setting = setting;
        this.selectionListener = null;
        int count = setting.getModeCount();
        this.cachedLabels = new String[count];
        this.labelsInitialized = false;
        this.selectedIndex = clampIndex(setting.getIndex(), count);
        this.slideAnim = new Animation(this.selectedIndex, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);
        this.hoverAnims = createHoverAnims(count);
    }

    /**
     * Creates a standalone segmented control for non-module selectors (for example Dark | Light).
     *
     * @param labels            ordered option labels
     * @param initialIndex      initial selected index
     * @param selectionListener optional callback on selection change
     */
    public SegmentedComponent(String[] labels, int initialIndex, SelectionListener selectionListener) {
        if (labels == null || labels.length == 0) {
            throw new IllegalArgumentException("SegmentedComponent requires at least one option");
        }
        this.setting = null;
        this.selectionListener = selectionListener;
        this.cachedLabels = new String[labels.length];
        for (int i = 0; i < labels.length; i++) {
            this.cachedLabels[i] = labels[i] != null ? labels[i] : "";
        }
        this.labelsInitialized = true;
        this.selectedIndex = clampIndex(initialIndex, labels.length);
        this.slideAnim = new Animation(this.selectedIndex, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);
        this.hoverAnims = createHoverAnims(labels.length);
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
        float totalW = Math.max(0.0f, width);
        boolean inside = hitTest(mouseX, mouseY, x, y, totalW, HEIGHT);
        updateBounds(x, y, totalW, HEIGHT, inside);

        int count = this.cachedLabels.length;
        int activeIdx = getSelectedIndex();
        this.slideAnim.setTarget(activeIdx);

        float innerW = Math.max(0.0f, totalW - INNER_PAD * 2.0f);
        float segW = count > 0 ? (innerW / (float) count) : 0.0f;

        for (int i = 0; i < count; i++) {
            float segX = x + INNER_PAD + i * segW;
            boolean segHovered = this.hovered && mouseX >= segX && mouseX <= segX + segW;
            this.hoverAnims[i].setTarget(segHovered ? 1.0f : 0.0f);
        }

        float effAlpha = effectiveAlpha(alpha);
        if (effAlpha <= 0.001f || totalW <= 0.0f || count == 0) {
            return;
        }

        float outerRadius = resolved.radiusControl();
        RenderUtil.drawRoundedRect(
            x,
            y,
            totalW,
            HEIGHT,
            outerRadius,
            RenderUtil.withAlpha(resolved.surface3(), effAlpha)
        );

        float slidePos = clamp(this.slideAnim.get(), 0.0f, Math.max(0.0f, count - 1));
        float knobX = x + INNER_PAD + slidePos * segW;
        float knobY = y + INNER_PAD;
        float knobH = HEIGHT - INNER_PAD * 2.0f;
        float knobRadius = resolved.nestedRadius(outerRadius, INNER_PAD);

        if (segW > 0.0f && knobH > 0.0f) {
            RenderUtil.drawRoundedRect(
                knobX,
                knobY,
                segW,
                knobH,
                knobRadius,
                RenderUtil.withAlpha(resolved.surface4(), effAlpha)
            );
        }

        TtfFontRenderer font = FontManager.body();
        float textY = y + (HEIGHT - font.getHeight()) * 0.5f;
        float maxTextW = Math.max(0.0f, segW - UiTokens.SPACE_8);

        for (int i = 0; i < count; i++) {
            String label = this.cachedLabels[i];
            if (label.isEmpty()) {
                continue;
            }
            float activeWeight = clamp01(1.0f - Math.abs(slidePos - (float) i));
            float hoverWeight = clamp01(this.hoverAnims[i].get()) * 0.45f;
            float colorT = Math.max(activeWeight, hoverWeight);
            int textColor = ColorAnimation.lerpArgb(resolved.textSecondary(), resolved.textPrimary(), colorT);
            int finalTextColor = RenderUtil.withAlpha(textColor, effAlpha);

            float segLeft = x + INNER_PAD + i * segW;
            float labelW = font.getWidth(label);
            if (labelW <= maxTextW) {
                font.drawStringCentered(label, segLeft + segW * 0.5f, textY, finalTextColor);
            } else if (maxTextW > 0.0f) {
                font.drawStringWithEllipsis(label, segLeft + UiTokens.SPACE_4, textY, maxTextW, finalTextColor);
            }
        }
    }

    @Override
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled || mouseButton != 0) {
            return false;
        }
        if (!hitTest(mouseX, mouseY, this.x, this.y, this.width, HEIGHT)) {
            return false;
        }
        int count = this.cachedLabels.length;
        float innerW = Math.max(0.0f, this.width - INNER_PAD * 2.0f);
        if (count <= 0 || innerW <= 0.0f) {
            return false;
        }
        float segW = innerW / (float) count;
        int clicked = (int) Math.floor((mouseX - (this.x + INNER_PAD)) / segW);
        int targetIndex = clampIndex(clicked, count);
        setSelectedIndex(targetIndex);
        return true;
    }

    @Override
    public float getHeight() {
        return HEIGHT;
    }

    @Override
    public float getPreferredWidth() {
        ensureLabels();
        TtfFontRenderer font = FontManager.body();
        if (this.cachedPreferredWidth < 0.0f || this.preferredMeasureFont != font) {
            this.preferredMeasureFont = font;
            float maxLabelW = 0.0f;
            for (int i = 0; i < this.cachedLabels.length; i++) {
                float w = font.getWidth(this.cachedLabels[i]);
                if (w > maxLabelW) {
                    maxLabelW = w;
                }
            }
            float rawSegW = Math.max(MIN_SEGMENT_WIDTH, maxLabelW + UiTokens.SPACE_16);
            float snappedSegW = (float) (Math.ceil(rawSegW / 4.0D) * 4.0D);
            this.cachedPreferredWidth = snappedSegW * this.cachedLabels.length + INNER_PAD * 2.0f;
        }
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
     * @param index target option index
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
        this.slideAnim.setTarget(this.selectedIndex);
    }

    /**
     * Immediately snaps the sliding indicator to the currently active option without animation.
     */
    public void snapToCurrent() {
        this.slideAnim.snapTo(getSelectedIndex());
    }

    private void ensureLabels() {
        if (!this.labelsInitialized) {
            refreshLabels();
        }
    }

    private static Animation[] createHoverAnims(int count) {
        Animation[] anims = new Animation[count];
        for (int i = 0; i < count; i++) {
            anims[i] = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
        }
        return anims;
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
}
