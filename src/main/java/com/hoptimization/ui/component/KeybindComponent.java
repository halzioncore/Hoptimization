package com.hoptimization.ui.component;

import com.hoptimization.core.setting.KeybindSetting;
import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import org.lwjgl.input.Keyboard;

/**
 * Keyboard shortcut capture pill (height {@code 22}, minimum width {@code 48}, {@code surface3},
 * {@code 1px accent} border when listening, {@code Esc} cancels, {@code Backspace/Delete} clears to {@code 0})
 * bound to a {@link KeybindSetting} or standalone keycode callback.
 */
public class KeybindComponent extends UiComponent {
    public static final float HEIGHT = 22.0f;
    public static final float MIN_WIDTH = 48.0f;
    private static final float PADDING_H = UiTokens.SPACE_8;

    private static final String KEY_NONE_LANG = "setting.hoptimization.common.keybind.none";
    private static final String KEY_LISTENING_LANG = "setting.hoptimization.common.keybind.listening";

    /**
     * Listener invoked when a standalone keybind changes.
     */
    public interface KeybindChangeListener {
        void onKeybindChanged(int newKeyCode);
    }

    private final KeybindSetting setting;
    private final KeybindChangeListener changeListener;
    private int keyCode;
    private String cachedStandaloneKeyName;

    private String noneLabel;
    private String listeningLabel;
    private boolean listening = false;
    private float visualX = 0.0f;
    private float visualW = MIN_WIDTH;

    private final Animation hoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation listenAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);

    /**
     * Creates a keybind control bound directly to a {@link KeybindSetting}.
     *
     * @param setting keybind setting
     */
    public KeybindComponent(KeybindSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("KeybindComponent setting must not be null");
        }
        this.setting = setting;
        this.changeListener = null;
        this.keyCode = setting.getKeyCode();
        this.cachedStandaloneKeyName = setting.getKeyName();
    }

    /**
     * Creates a standalone keybind control (for example the ClickGUI open hotkey in Settings).
     *
     * @param initialKeyCode initial LWJGL key code ({@code 0} for unbound)
     * @param changeListener optional callback invoked when the keybind changes
     */
    public KeybindComponent(int initialKeyCode, KeybindChangeListener changeListener) {
        this.setting = null;
        this.changeListener = changeListener;
        this.keyCode = KeybindSetting.sanitizeKeyCode(initialKeyCode);
        this.cachedStandaloneKeyName = KeybindSetting.formatKeyName(this.keyCode);
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
        TtfFontRenderer font = FontManager.caption();
        String label = resolveDisplayText();
        float naturalW = Math.max(MIN_WIDTH, font.getWidth(label) + PADDING_H * 2.0f);
        float drawW = width > 0.0f && naturalW > width ? Math.max(MIN_WIDTH, width) : naturalW;
        float drawX = width > drawW ? (x + width - drawW) : x;

        this.visualX = drawX;
        this.visualW = drawW;
        boolean inside = hitTestMin20(mouseX, mouseY, drawX, y, drawW, HEIGHT);
        updateBounds(x, y, Math.max(drawW, width), HEIGHT, inside);

        this.hoverAnim.setTarget(this.hovered && !this.listening ? 1.0f : 0.0f);
        this.listenAnim.setTarget(this.listening && this.enabled ? 1.0f : 0.0f);

        float effAlpha = effectiveAlpha(alpha);
        if (effAlpha <= 0.001f) {
            return;
        }

        float hoverT = clamp01(this.hoverAnim.get());
        float listenT = clamp01(this.listenAnim.get());
        int bgBase = ColorAnimation.lerpArgb(resolved.surface3(), resolved.surface4(), hoverT);
        int borderColor = listenT > 0.01f ? RenderUtil.withAlpha(resolved.accent(), listenT * effAlpha) : 0;
        float borderWidth = listenT > 0.01f ? UiTokens.BORDER_WIDTH : 0.0f;

        RenderUtil.drawRoundedRect(
            drawX,
            y,
            drawW,
            HEIGHT,
            resolved.radiusControl(),
            RenderUtil.withAlpha(bgBase, effAlpha),
            borderColor,
            borderWidth
        );

        int textBase;
        if (this.listening) {
            textBase = resolved.accent();
        } else if (isBound()) {
            textBase = resolved.textPrimary();
        } else {
            textBase = resolved.textTertiary();
        }

        float textY = y + (HEIGHT - font.getHeight()) * 0.5f;
        float maxTextW = Math.max(0.0f, drawW - PADDING_H * 2.0f);
        float textW = font.getWidth(label);
        if (textW <= maxTextW) {
            font.drawStringCentered(label, drawX + drawW * 0.5f, textY, RenderUtil.withAlpha(textBase, effAlpha));
        } else if (maxTextW > 0.0f) {
            font.drawStringWithEllipsis(
                label,
                drawX + PADDING_H,
                textY,
                maxTextW,
                RenderUtil.withAlpha(textBase, effAlpha)
            );
        }
    }

    @Override
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled) {
            return false;
        }
        boolean inside = hitTestMin20(mouseX, mouseY, this.visualX, this.y, this.visualW, HEIGHT);
        if (mouseButton != 0) {
            if (!inside && this.listening) {
                cancelListening();
            }
            return false;
        }
        if (inside) {
            if (this.listening) {
                cancelListening();
            } else {
                startListening();
            }
            return true;
        }
        if (this.listening) {
            cancelListening();
        }
        return false;
    }

    @Override
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!this.enabled || !this.listening) {
            return false;
        }
        if (Keyboard.isCreated() && Keyboard.isRepeatEvent()) {
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            cancelListening();
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) {
            setKeyCode(Keyboard.KEY_NONE);
            this.listening = false;
            return true;
        }
        int sanitized = KeybindSetting.sanitizeKeyCode(keyCode);
        if (sanitized != Keyboard.KEY_NONE) {
            setKeyCode(sanitized);
            this.listening = false;
        }
        return true;
    }

    @Override
    public float getHeight() {
        return HEIGHT;
    }

    @Override
    public float getPreferredWidth() {
        ensureLabels();
        TtfFontRenderer font = FontManager.caption();
        return Math.max(MIN_WIDTH, font.getWidth(resolveDisplayText()) + PADDING_H * 2.0f);
    }

    @Override
    public void refreshLabels() {
        this.noneLabel = LanguageModule.format(KEY_NONE_LANG);
        this.listeningLabel = LanguageModule.format(KEY_LISTENING_LANG);
    }

    /**
     * Enters listening mode waiting for the next keypress.
     */
    public void startListening() {
        if (!this.enabled) {
            return;
        }
        this.listening = true;
    }

    /**
     * Exits listening mode without changing the currently bound key.
     */
    public void cancelListening() {
        this.listening = false;
    }

    /**
     * Returns whether this keybind box is currently waiting for a keypress.
     *
     * @return {@code true} if listening
     */
    public boolean isListening() {
        return this.listening;
    }

    /**
     * Returns the current LWJGL key code ({@code 0} when unbound).
     *
     * @return LWJGL key code
     */
    public int getKeyCode() {
        return this.setting != null ? this.setting.getKeyCode() : this.keyCode;
    }

    /**
     * Returns whether a non-zero key is currently bound.
     *
     * @return {@code true} if bound
     */
    public boolean isBound() {
        return getKeyCode() != Keyboard.KEY_NONE;
    }

    /**
     * Updates the bound key code and notifies the bound setting or listener.
     *
     * @param newKeyCode candidate LWJGL key code ({@code 0} clears)
     */
    public void setKeyCode(int newKeyCode) {
        int sanitized = KeybindSetting.sanitizeKeyCode(newKeyCode);
        if (this.setting != null) {
            this.setting.setValue(Integer.valueOf(sanitized));
            this.keyCode = this.setting.getKeyCode();
            this.cachedStandaloneKeyName = this.setting.getKeyName();
        } else if (this.keyCode != sanitized) {
            this.keyCode = sanitized;
            this.cachedStandaloneKeyName = KeybindSetting.formatKeyName(sanitized);
            if (this.changeListener != null) {
                this.changeListener.onKeybindChanged(sanitized);
            }
        }
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (!enabled) {
            cancelListening();
        }
    }

    private void ensureLabels() {
        if (this.noneLabel == null || this.listeningLabel == null) {
            refreshLabels();
        }
    }

    private String resolveDisplayText() {
        if (this.listening) {
            return this.listeningLabel;
        }
        if (!isBound()) {
            return this.noneLabel;
        }
        return this.setting != null ? this.setting.getKeyName() : this.cachedStandaloneKeyName;
    }
}
