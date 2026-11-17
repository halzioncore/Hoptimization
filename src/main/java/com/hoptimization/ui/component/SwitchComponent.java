package com.hoptimization.ui.component;

import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;

/**
 * Pill toggle switch control ({@code 28x16}, {@code 12x12} thumb, {@code 2px} inset,
 * {@code fast} 120ms displacement and color transition) bound to a {@link BooleanSetting}
 * or custom boolean callback.
 */
public class SwitchComponent extends UiComponent {
    public static final float WIDTH = 28.0f;
    public static final float HEIGHT = 16.0f;
    public static final float THUMB_SIZE = 12.0f;
    public static final float THUMB_PAD = UiTokens.SPACE_2;

    /**
     * Listener invoked when a standalone switch toggles.
     */
    public interface ChangeListener {
        void onChanged(boolean checked);
    }

    private final BooleanSetting setting;
    private final ChangeListener changeListener;
    private boolean checked;

    private final Animation toggleAnim;
    private final Animation hoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private boolean pressed = false;
    private float visualX = 0.0f;

    /**
     * Creates a switch bound directly to a {@link BooleanSetting}.
     *
     * @param setting boolean setting to read and toggle
     */
    public SwitchComponent(BooleanSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("SwitchComponent setting must not be null");
        }
        this.setting = setting;
        this.changeListener = null;
        this.checked = setting.isEnabled();
        this.toggleAnim = new Animation(this.checked ? 1.0f : 0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    }

    /**
     * Creates a standalone switch for non-module toggles (such as UI settings or style options).
     *
     * @param initialChecked initial checked state
     * @param changeListener optional listener invoked when toggled
     */
    public SwitchComponent(boolean initialChecked, ChangeListener changeListener) {
        this.setting = null;
        this.changeListener = changeListener;
        this.checked = initialChecked;
        this.toggleAnim = new Animation(initialChecked ? 1.0f : 0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
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
        boolean current = isChecked();
        this.toggleAnim.setTarget(current ? 1.0f : 0.0f);

        float drawX = width > WIDTH ? (x + width - WIDTH) : x;
        this.visualX = drawX;
        boolean inside = hitTestMin20(mouseX, mouseY, drawX, y, WIDTH, HEIGHT);
        updateBounds(x, y, Math.max(WIDTH, width), HEIGHT, inside);

        this.hoverAnim.setTarget(this.hovered ? 1.0f : 0.0f);

        float effAlpha = effectiveAlpha(alpha);
        if (effAlpha <= 0.001f) {
            return;
        }

        float t = clamp01(this.toggleAnim.get());
        float hoverT = clamp01(this.hoverAnim.get());

        int offTrack = ColorAnimation.lerpArgb(resolved.surface4(), resolved.textTertiary(), hoverT * 0.16f);
        int onTrack = ColorAnimation.lerpArgb(resolved.accent(), resolved.accentHover(), hoverT);
        int trackColor = ColorAnimation.lerpArgb(offTrack, onTrack, t);
        if (this.pressed && this.hovered) {
            trackColor = ColorAnimation.lerpArgb(trackColor, resolved.surface0(), 0.04f);
        }

        int thumbColor = ColorAnimation.lerpArgb(resolved.textSecondary(), resolved.textOnAccent(), t);

        float trackRadius = resolved.radiusPill(HEIGHT);
        RenderUtil.drawRoundedRect(
            drawX,
            y,
            WIDTH,
            HEIGHT,
            trackRadius,
            RenderUtil.withAlpha(trackColor, effAlpha)
        );

        float travel = WIDTH - THUMB_PAD * 2.0f - THUMB_SIZE;
        float thumbX = drawX + THUMB_PAD + travel * t;
        float thumbY = y + THUMB_PAD;
        float thumbRadius = resolved.radiusPill(THUMB_SIZE);

        RenderUtil.drawRoundedRect(
            thumbX,
            thumbY,
            THUMB_SIZE,
            THUMB_SIZE,
            thumbRadius,
            RenderUtil.withAlpha(thumbColor, effAlpha)
        );
    }

    @Override
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled || mouseButton != 0) {
            return false;
        }
        float hitX = this.width > WIDTH ? this.visualX : this.x;
        if (hitTestMin20(mouseX, mouseY, hitX, this.y, WIDTH, HEIGHT)) {
            this.pressed = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (mouseButton != 0 || !this.pressed) {
            return false;
        }
        this.pressed = false;
        if (!this.enabled) {
            return false;
        }
        float hitX = this.width > WIDTH ? this.visualX : this.x;
        if (hitTestMin20(mouseX, mouseY, hitX, this.y, WIDTH, HEIGHT)) {
            toggle();
        }
        return true;
    }

    @Override
    public float getHeight() {
        return HEIGHT;
    }

    @Override
    public float getPreferredWidth() {
        return WIDTH;
    }

    /**
     * Returns whether this switch is currently ON.
     *
     * @return {@code true} if checked
     */
    public boolean isChecked() {
        return this.setting != null ? this.setting.isEnabled() : this.checked;
    }

    /**
     * Updates the switch state and notifies the bound setting or change listener.
     *
     * @param checked new checked state
     */
    public void setChecked(boolean checked) {
        if (this.setting != null) {
            this.setting.setValue(Boolean.valueOf(checked));
            this.checked = this.setting.isEnabled();
        } else if (this.checked != checked) {
            this.checked = checked;
            if (this.changeListener != null) {
                this.changeListener.onChanged(checked);
            }
        }
        this.toggleAnim.setTarget(this.checked ? 1.0f : 0.0f);
    }

    /**
     * Toggles the switch state.
     */
    public void toggle() {
        setChecked(!isChecked());
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (!enabled) {
            this.pressed = false;
        }
    }
}
