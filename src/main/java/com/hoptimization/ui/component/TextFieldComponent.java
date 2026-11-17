package com.hoptimization.ui.component;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.FontToken;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

/**
 * Reusable single-line text input control ({@code 28px} default height, {@code surface3},
 * {@code radiusControl}, {@code borderStrong -> 1px accent} focus border, {@code 530ms} cursor blink,
 * selection, horizontal scrolling, and clipboard shortcuts).
 */
public class TextFieldComponent extends UiComponent {
    public static final float DEFAULT_HEIGHT = 28.0f;
    public static final long CURSOR_BLINK_PERIOD_MS = 530L;
    private static final long MULTI_CLICK_THRESHOLD_NANOS = 350_000_000L;
    private static final float SELECTION_ALPHA = 0.28f;

    /**
     * Listener invoked when the text value changes due to editing or {@link #setText(String)}.
     */
    public interface TextChangeListener {
        void onTextChanged(String newText);
    }

    /**
     * Listener invoked when the user presses Enter inside the focused text field.
     */
    public interface SubmitListener {
        void onSubmit(String text);
    }

    /**
     * Filter controlling whether a candidate character may be inserted into this field.
     */
    public interface CharFilter {
        boolean accept(char candidate, String currentText, int insertIndex);
    }

    private final Animation focusAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation hoverAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);

    private String text = "";
    private String placeholder = "";
    private String trailingHint = "";
    private ResourceLocation leadingIcon;
    private float leadingIconSize = 14.0f;

    private FontToken fontToken = FontToken.BODY_REGULAR;
    private float preferredHeight = DEFAULT_HEIGHT;
    private int maxLength = 256;

    private boolean focused = false;
    private int cursorIndex = 0;
    private int selectionAnchor = 0;
    private float scrollOffsetX = 0.0f;
    private long blinkAnchorMs = System.currentTimeMillis();

    private boolean selectingWithMouse = false;
    private long lastClickNanos = 0L;
    private int clickStreak = 0;

    private float lastTextAreaX = 0.0f;
    private TtfFontRenderer lastFont;

    private TtfFontRenderer metricsFont;
    private String metricsText;
    private int metricsCursor = -1;
    private int metricsAnchor = -1;
    private float cachedCursorPx = 0.0f;
    private float cachedAnchorPx = 0.0f;
    private float cachedTextAreaW = -1.0f;

    private TextChangeListener textChangeListener;
    private SubmitListener submitListener;
    private CharFilter charFilter;

    /**
     * Creates an empty text field without placeholder text.
     */
    public TextFieldComponent() {
        this("");
    }

    /**
     * Creates an empty text field with the specified placeholder text.
     *
     * @param placeholder localized placeholder shown when text is empty
     */
    public TextFieldComponent(String placeholder) {
        this.placeholder = placeholder != null ? placeholder : "";
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
        float boxW = Math.max(0.0f, width);
        float boxH = this.preferredHeight;
        boolean inside = hitTest(mouseX, mouseY, x, y, boxW, boxH);
        updateBounds(x, y, boxW, boxH, inside);

        this.hoverAnim.setTarget(this.hovered && !this.focused ? 1.0f : 0.0f);
        this.focusAnim.setTarget(this.focused && this.enabled ? 1.0f : 0.0f);

        float effAlpha = effectiveAlpha(alpha);
        if (effAlpha <= 0.001f || boxW <= 0.0f || boxH <= 0.0f) {
            return;
        }

        float hoverT = this.hoverAnim.get();
        float focusT = this.focusAnim.get();
        float radius = resolved.style().getRadiusScale() <= 0.0f
            ? 0.0f
            : Math.min(resolved.radiusControl(), boxH * 0.5f);

        int bgBase = ColorAnimation.lerpArgb(resolved.surface3(), resolved.surface4(), hoverT * 0.35f);
        int borderBase = ColorAnimation.lerpArgb(resolved.borderStrong(), resolved.accent(), focusT);

        RenderUtil.drawRoundedRect(
            x, y, boxW, boxH,
            radius,
            RenderUtil.withAlpha(bgBase, effAlpha),
            RenderUtil.withAlpha(borderBase, effAlpha),
            UiTokens.BORDER_WIDTH
        );

        float padH = UiTokens.PADDING_CONTROL;
        float contentLeft = x + padH;
        float contentRight = x + boxW - padH;

        if (this.leadingIcon != null) {
            float iconSize = Math.min(this.leadingIconSize, Math.max(8.0f, boxH - UiTokens.SPACE_8));
            float iconY = y + (boxH - iconSize) * 0.5f;
            int iconColor = this.focused ? resolved.textPrimary() : resolved.textSecondary();
            RenderUtil.drawTintedIcon(
                this.leadingIcon,
                contentLeft,
                iconY,
                iconSize,
                RenderUtil.withAlpha(iconColor, effAlpha)
            );
            contentLeft += iconSize + UiTokens.SPACE_6;
        }

        TtfFontRenderer font = FontManager.get(this.fontToken);
        if (this.text.isEmpty() && !this.trailingHint.isEmpty()) {
            TtfFontRenderer hintFont = FontManager.caption();
            float hintW = hintFont.getWidth(this.trailingHint);
            if (contentRight - contentLeft > hintW + UiTokens.SPACE_16) {
                float hintY = y + (boxH - hintFont.getHeight()) * 0.5f;
                hintFont.drawString(
                    this.trailingHint,
                    contentRight - hintW,
                    hintY,
                    RenderUtil.withAlpha(resolved.textTertiary(), effAlpha)
                );
                contentRight -= hintW + UiTokens.SPACE_6;
            }
        }

        float textAreaW = Math.max(0.0f, contentRight - contentLeft);
        this.lastTextAreaX = contentLeft;
        this.lastFont = font;

        if (textAreaW <= 1.0f) {
            return;
        }

        ensureCaretMetrics(font, textAreaW);
        float textY = y + (boxH - font.getHeight()) * 0.5f;

        boolean pushedScissor = false;
        if (Display.isCreated()) {
            ScissorStack.pushTransformed(contentLeft, y + 1.0f, textAreaW, Math.max(0.0f, boxH - 2.0f));
            pushedScissor = true;
        }
        try {
            if (this.text.isEmpty()) {
                if (!this.placeholder.isEmpty()) {
                    font.drawStringWithEllipsis(
                        this.placeholder,
                        contentLeft,
                        textY,
                        textAreaW,
                        RenderUtil.withAlpha(resolved.textTertiary(), effAlpha)
                    );
                }
            } else {
                if (hasSelection()) {
                    float selLeftPx = Math.min(this.cachedCursorPx, this.cachedAnchorPx);
                    float selRightPx = Math.max(this.cachedCursorPx, this.cachedAnchorPx);
                    float selX = contentLeft + selLeftPx - this.scrollOffsetX;
                    float selW = Math.max(1.0f, selRightPx - selLeftPx);
                    float selY = y + UiTokens.SPACE_4;
                    float selH = Math.max(font.getHeight(), boxH - UiTokens.SPACE_8);
                    RenderUtil.drawRect(
                        selX,
                        selY,
                        selW,
                        selH,
                        RenderUtil.withAlpha(resolved.accent(), SELECTION_ALPHA * effAlpha)
                    );
                }

                font.drawString(
                    this.text,
                    contentLeft - this.scrollOffsetX,
                    textY,
                    RenderUtil.withAlpha(resolved.textPrimary(), effAlpha)
                );
            }

            if (this.focused && this.enabled && isCursorVisibleNow()) {
                float cursorDrawX = contentLeft + this.cachedCursorPx - this.scrollOffsetX;
                if (cursorDrawX >= contentLeft - 1.0f && cursorDrawX <= contentLeft + textAreaW) {
                    float clampedCursorX = clamp(cursorDrawX, contentLeft, contentLeft + textAreaW - 1.0f);
                    RenderUtil.drawRect(
                        clampedCursorX,
                        textY,
                        1.0f,
                        font.getHeight(),
                        RenderUtil.withAlpha(resolved.textPrimary(), effAlpha)
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
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled) {
            return false;
        }
        boolean inside = hitTest(mouseX, mouseY, this.x, this.y, this.width, this.preferredHeight);
        if (mouseButton != 0) {
            if (!inside && this.focused) {
                setFocused(false);
            }
            return false;
        }

        if (!inside) {
            if (this.focused) {
                setFocused(false);
            }
            return false;
        }

        setFocused(true);
        long now = System.nanoTime();
        if (now - this.lastClickNanos <= MULTI_CLICK_THRESHOLD_NANOS) {
            this.clickStreak++;
        } else {
            this.clickStreak = 1;
        }
        this.lastClickNanos = now;

        int clickedIndex = resolveIndexAtMouse(mouseX);
        if (this.clickStreak >= 3) {
            selectAll();
            this.selectingWithMouse = false;
        } else if (this.clickStreak == 2) {
            selectWordAt(clickedIndex);
            this.selectingWithMouse = false;
        } else {
            boolean shiftHeld = isShiftDown();
            if (shiftHeld) {
                this.cursorIndex = clickedIndex;
            } else {
                this.cursorIndex = clickedIndex;
                this.selectionAnchor = clickedIndex;
            }
            this.selectingWithMouse = true;
        }
        resetBlink();
        invalidateMetrics();
        return true;
    }

    @Override
    public boolean mouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (!this.enabled || !this.focused || !this.selectingWithMouse || mouseButton != 0) {
            return false;
        }
        int nextIndex = resolveIndexAtMouse(mouseX);
        if (nextIndex != this.cursorIndex) {
            this.cursorIndex = nextIndex;
            resetBlink();
            invalidateMetrics();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (mouseButton == 0 && this.selectingWithMouse) {
            this.selectingWithMouse = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!this.enabled || !this.focused) {
            return false;
        }
        boolean ctrl = isCtrlDown();
        boolean shift = isShiftDown();

        if (keyCode == Keyboard.KEY_ESCAPE) {
            setFocused(false);
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (this.submitListener != null) {
                this.submitListener.onSubmit(this.text);
            }
            return true;
        }

        if (ctrl && keyCode == Keyboard.KEY_A) {
            selectAll();
            resetBlink();
            return true;
        }
        if (ctrl && keyCode == Keyboard.KEY_C) {
            copySelectionToClipboard();
            return true;
        }
        if (ctrl && keyCode == Keyboard.KEY_X) {
            if (hasSelection()) {
                copySelectionToClipboard();
                replaceSelection("");
            }
            resetBlink();
            return true;
        }
        if (ctrl && keyCode == Keyboard.KEY_V) {
            pasteFromClipboard();
            resetBlink();
            return true;
        }

        if (keyCode == Keyboard.KEY_BACK) {
            handleBackspace(ctrl);
            resetBlink();
            return true;
        }
        if (keyCode == Keyboard.KEY_DELETE) {
            handleDelete(ctrl);
            resetBlink();
            return true;
        }
        if (keyCode == Keyboard.KEY_LEFT) {
            moveCursorLeft(ctrl, shift);
            resetBlink();
            return true;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            moveCursorRight(ctrl, shift);
            resetBlink();
            return true;
        }
        if (keyCode == Keyboard.KEY_HOME) {
            moveCursorTo(0, shift);
            resetBlink();
            return true;
        }
        if (keyCode == Keyboard.KEY_END) {
            moveCursorTo(this.text.length(), shift);
            resetBlink();
            return true;
        }

        if (!ctrl && isAllowedInputChar(typedChar)) {
            insertCharacter(typedChar);
            resetBlink();
            return true;
        }
        return true;
    }

    @Override
    public float getHeight() {
        return this.preferredHeight;
    }

    /**
     * Sets the logical height of this text field (defaults to {@link #DEFAULT_HEIGHT}).
     *
     * @param height desired logical height
     */
    public void setHeight(float height) {
        if (height > 0.0f) {
            this.preferredHeight = height;
        }
    }

    /**
     * Sets the font token used for rendering text and placeholder (defaults to {@link FontToken#BODY_REGULAR}).
     *
     * @param fontToken font token
     */
    public void setFontToken(FontToken fontToken) {
        if (fontToken != null && this.fontToken != fontToken) {
            this.fontToken = fontToken;
            invalidateMetrics();
        }
    }

    /**
     * Returns the current text content.
     *
     * @return current text
     */
    public String getText() {
        return this.text;
    }

    /**
     * Sets the text content and notifies the {@link TextChangeListener} if changed.
     *
     * @param newText new text value
     */
    public void setText(String newText) {
        String clamped = sanitizeAndClamp(newText);
        if (this.text.equals(clamped)) {
            return;
        }
        this.text = clamped;
        this.cursorIndex = clamped.length();
        this.selectionAnchor = this.cursorIndex;
        this.scrollOffsetX = 0.0f;
        invalidateMetrics();
        if (this.textChangeListener != null) {
            this.textChangeListener.onTextChanged(this.text);
        }
    }

    /**
     * Sets the text content without firing the {@link TextChangeListener}.
     *
     * @param newText new text value
     */
    public void setTextSilent(String newText) {
        String clamped = sanitizeAndClamp(newText);
        this.text = clamped;
        this.cursorIndex = clampIndex(this.cursorIndex, clamped.length());
        this.selectionAnchor = clampIndex(this.selectionAnchor, clamped.length());
        if (clamped.isEmpty()) {
            this.scrollOffsetX = 0.0f;
        }
        invalidateMetrics();
    }

    /**
     * Returns the placeholder string displayed when text is empty.
     *
     * @return placeholder text
     */
    public String getPlaceholder() {
        return this.placeholder;
    }

    /**
     * Updates the placeholder text displayed in {@code textTertiary} when empty.
     *
     * @param placeholder placeholder string
     */
    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder != null ? placeholder : "";
    }

    /**
     * Configures an optional trailing shortcut hint (for example {@code "Ctrl F"}) drawn when empty.
     *
     * @param trailingHint trailing hint text
     */
    public void setTrailingHint(String trailingHint) {
        this.trailingHint = trailingHint != null ? trailingHint : "";
    }

    /**
     * Configures an optional leading monochrome icon texture.
     *
     * @param icon     icon texture resource location, or {@code null} for none
     * @param iconSize icon size in logical pixels
     */
    public void setLeadingIcon(ResourceLocation icon, float iconSize) {
        this.leadingIcon = icon;
        this.leadingIconSize = iconSize > 0.0f ? iconSize : 14.0f;
    }

    /**
     * Sets the maximum allowed character length.
     *
     * @param maxLength positive max length
     */
    public void setMaxLength(int maxLength) {
        this.maxLength = Math.max(1, maxLength);
        if (this.text.length() > this.maxLength) {
            setText(this.text.substring(0, this.maxLength));
        }
    }

    /**
     * Returns whether this text field currently has keyboard focus.
     *
     * @return {@code true} if focused
     */
    public boolean isFocused() {
        return this.focused;
    }

    /**
     * Sets keyboard focus state for this text field and toggles LWJGL repeat events when active.
     *
     * @param focused {@code true} to focus
     */
    public void setFocused(boolean focused) {
        if (!this.enabled && focused) {
            return;
        }
        if (this.focused == focused) {
            return;
        }
        this.focused = focused;
        this.selectingWithMouse = false;
        if (focused) {
            resetBlink();
        }
        if (Keyboard.isCreated()) {
            Keyboard.enableRepeatEvents(focused);
        }
    }

    /**
     * Returns whether a non-empty text selection currently exists.
     *
     * @return {@code true} if selection length > 0
     */
    public boolean hasSelection() {
        return this.cursorIndex != this.selectionAnchor;
    }

    /**
     * Returns the currently selected substring, or an empty string if no selection exists.
     *
     * @return selected text
     */
    public String getSelectedText() {
        if (!hasSelection()) {
            return "";
        }
        int start = Math.min(this.cursorIndex, this.selectionAnchor);
        int end = Math.max(this.cursorIndex, this.selectionAnchor);
        return this.text.substring(start, end);
    }

    /**
     * Selects the entire text content.
     */
    public void selectAll() {
        this.selectionAnchor = 0;
        this.cursorIndex = this.text.length();
        invalidateMetrics();
    }

    /**
     * Clears any active selection while keeping the cursor at its current position.
     */
    public void clearSelection() {
        this.selectionAnchor = this.cursorIndex;
        invalidateMetrics();
    }

    /**
     * Returns the current cursor character index.
     *
     * @return cursor index in {@code [0, text.length()]}
     */
    public int getCursorIndex() {
        return this.cursorIndex;
    }

    /**
     * Sets the listener notified whenever text changes via user input or {@link #setText(String)}.
     *
     * @param textChangeListener listener instance
     */
    public void setTextChangeListener(TextChangeListener textChangeListener) {
        this.textChangeListener = textChangeListener;
    }

    /**
     * Sets the listener notified when Enter is pressed while focused.
     *
     * @param submitListener listener instance
     */
    public void setSubmitListener(SubmitListener submitListener) {
        this.submitListener = submitListener;
    }

    /**
     * Sets an optional filter predicate restricting typed/pasted characters.
     *
     * @param charFilter character filter
     */
    public void setCharFilter(CharFilter charFilter) {
        this.charFilter = charFilter;
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (!enabled) {
            setFocused(false);
            this.selectingWithMouse = false;
        }
    }

    private void insertCharacter(char c) {
        int selStart = Math.min(this.cursorIndex, this.selectionAnchor);
        if (this.charFilter != null && !this.charFilter.accept(c, this.text, selStart)) {
            return;
        }
        replaceSelection(String.valueOf(c));
    }

    private void replaceSelection(String replacement) {
        int selStart = Math.min(this.cursorIndex, this.selectionAnchor);
        int selEnd = Math.max(this.cursorIndex, this.selectionAnchor);
        String prefix = this.text.substring(0, selStart);
        String suffix = this.text.substring(selEnd);

        int remaining = this.maxLength - (prefix.length() + suffix.length());
        if (remaining <= 0) {
            if (selStart == selEnd) {
                return;
            }
            replacement = "";
        } else if (replacement.length() > remaining) {
            replacement = replacement.substring(0, remaining);
        }

        String updated = prefix + replacement + suffix;
        boolean changed = !updated.equals(this.text);
        this.text = updated;
        this.cursorIndex = selStart + replacement.length();
        this.selectionAnchor = this.cursorIndex;
        invalidateMetrics();

        if (changed && this.textChangeListener != null) {
            this.textChangeListener.onTextChanged(this.text);
        }
    }

    private void handleBackspace(boolean byWord) {
        if (hasSelection()) {
            replaceSelection("");
            return;
        }
        if (this.cursorIndex <= 0) {
            return;
        }
        int deleteFrom = byWord
            ? findPreviousWordBoundary(this.cursorIndex)
            : Character.offsetByCodePoints(this.text, this.cursorIndex, -1);
        this.selectionAnchor = deleteFrom;
        replaceSelection("");
    }

    private void handleDelete(boolean byWord) {
        if (hasSelection()) {
            replaceSelection("");
            return;
        }
        if (this.cursorIndex >= this.text.length()) {
            return;
        }
        int deleteTo = byWord
            ? findNextWordBoundary(this.cursorIndex)
            : Character.offsetByCodePoints(this.text, this.cursorIndex, 1);
        this.selectionAnchor = this.cursorIndex;
        this.cursorIndex = deleteTo;
        replaceSelection("");
    }

    private void moveCursorLeft(boolean byWord, boolean extendSelection) {
        if (!extendSelection && hasSelection()) {
            int start = Math.min(this.cursorIndex, this.selectionAnchor);
            moveCursorTo(start, false);
            return;
        }
        int next = byWord
            ? findPreviousWordBoundary(this.cursorIndex)
            : (this.cursorIndex > 0 ? Character.offsetByCodePoints(this.text, this.cursorIndex, -1) : 0);
        moveCursorTo(next, extendSelection);
    }

    private void moveCursorRight(boolean byWord, boolean extendSelection) {
        if (!extendSelection && hasSelection()) {
            int end = Math.max(this.cursorIndex, this.selectionAnchor);
            moveCursorTo(end, false);
            return;
        }
        int next = byWord
            ? findNextWordBoundary(this.cursorIndex)
            : (this.cursorIndex < this.text.length()
                ? Character.offsetByCodePoints(this.text, this.cursorIndex, 1)
                : this.text.length());
        moveCursorTo(next, extendSelection);
    }

    private void moveCursorTo(int index, boolean extendSelection) {
        this.cursorIndex = clampIndex(index, this.text.length());
        if (!extendSelection) {
            this.selectionAnchor = this.cursorIndex;
        }
        invalidateMetrics();
    }

    private void selectWordAt(int index) {
        if (this.text.isEmpty()) {
            this.cursorIndex = 0;
            this.selectionAnchor = 0;
            return;
        }
        int idx = clampIndex(index, this.text.length());
        if (idx == this.text.length() && idx > 0) {
            idx--;
        }
        boolean targetWordChar = isWordChar(this.text.charAt(idx));
        int start = idx;
        while (start > 0 && isWordChar(this.text.charAt(start - 1)) == targetWordChar) {
            start--;
        }
        int end = idx + 1;
        while (end < this.text.length() && isWordChar(this.text.charAt(end)) == targetWordChar) {
            end++;
        }
        this.selectionAnchor = start;
        this.cursorIndex = end;
    }

    private int findPreviousWordBoundary(int from) {
        int idx = clampIndex(from, this.text.length());
        while (idx > 0 && Character.isWhitespace(this.text.charAt(idx - 1))) {
            idx--;
        }
        while (idx > 0 && !Character.isWhitespace(this.text.charAt(idx - 1))) {
            idx--;
        }
        return idx;
    }

    private int findNextWordBoundary(int from) {
        int len = this.text.length();
        int idx = clampIndex(from, len);
        while (idx < len && !Character.isWhitespace(this.text.charAt(idx))) {
            idx++;
        }
        while (idx < len && Character.isWhitespace(this.text.charAt(idx))) {
            idx++;
        }
        return idx;
    }

    private void copySelectionToClipboard() {
        if (!hasSelection()) {
            return;
        }
        try {
            GuiScreen.setClipboardString(getSelectedText());
        } catch (Exception e) {
            HoptimizationMod.LOGGER.warn("Failed to copy text to clipboard", e);
        }
    }

    private void pasteFromClipboard() {
        String raw;
        try {
            raw = GuiScreen.getClipboardString();
        } catch (Exception e) {
            HoptimizationMod.LOGGER.warn("Failed to read clipboard text", e);
            return;
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        StringBuilder filtered = new StringBuilder(raw.length());
        int insertAt = Math.min(this.cursorIndex, this.selectionAnchor);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!isAllowedInputChar(c)) {
                continue;
            }
            if (this.charFilter != null && !this.charFilter.accept(c, this.text, insertAt)) {
                continue;
            }
            filtered.append(c);
        }
        if (filtered.length() > 0 || hasSelection()) {
            replaceSelection(filtered.toString());
        }
    }

    private int resolveIndexAtMouse(float mouseX) {
        if (this.text.isEmpty() || this.lastFont == null) {
            return 0;
        }
        float targetX = Math.max(0.0f, mouseX - this.lastTextAreaX + this.scrollOffsetX);
        int len = this.text.length();
        if (targetX <= 0.0f) {
            return 0;
        }
        float totalW = this.lastFont.getWidth(this.text);
        if (targetX >= totalW) {
            return len;
        }
        int bestIdx = 0;
        float bestDist = targetX;
        float prevW = 0.0f;
        int i = 0;
        while (i < len) {
            int nextIdx = Character.offsetByCodePoints(this.text, i, 1);
            float w = this.lastFont.getWidth(this.text.substring(0, nextIdx));
            float dist = Math.abs(w - targetX);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = nextIdx;
            }
            if (w > targetX && (w - targetX) > (targetX - prevW)) {
                break;
            }
            prevW = w;
            i = nextIdx;
        }
        return bestIdx;
    }

    private void ensureCaretMetrics(TtfFontRenderer font, float textAreaW) {
        if (this.metricsFont == font
            && this.metricsText == this.text
            && this.metricsCursor == this.cursorIndex
            && this.metricsAnchor == this.selectionAnchor
            && Math.abs(this.cachedTextAreaW - textAreaW) < 0.01f) {
            return;
        }
        this.metricsFont = font;
        this.metricsText = this.text;
        this.metricsCursor = this.cursorIndex;
        this.metricsAnchor = this.selectionAnchor;
        this.cachedTextAreaW = textAreaW;

        int len = this.text.length();
        int safeCursor = clampIndex(this.cursorIndex, len);
        int safeAnchor = clampIndex(this.selectionAnchor, len);
        this.cachedCursorPx = safeCursor == 0 ? 0.0f : font.getWidth(this.text.substring(0, safeCursor));
        if (safeAnchor == safeCursor) {
            this.cachedAnchorPx = this.cachedCursorPx;
        } else {
            this.cachedAnchorPx = safeAnchor == 0 ? 0.0f : font.getWidth(this.text.substring(0, safeAnchor));
        }

        float fullWidth = font.getWidth(this.text);
        float maxScroll = Math.max(0.0f, fullWidth - textAreaW + 2.0f);
        if (this.scrollOffsetX > maxScroll) {
            this.scrollOffsetX = maxScroll;
        }
        if (this.cachedCursorPx - this.scrollOffsetX > textAreaW - 2.0f) {
            this.scrollOffsetX = Math.max(0.0f, this.cachedCursorPx - (textAreaW - 2.0f));
        } else if (this.cachedCursorPx - this.scrollOffsetX < 0.0f) {
            this.scrollOffsetX = Math.max(0.0f, this.cachedCursorPx);
        }
    }

    private void invalidateMetrics() {
        this.metricsCursor = -1;
        this.metricsAnchor = -1;
    }

    private void resetBlink() {
        this.blinkAnchorMs = System.currentTimeMillis();
    }

    private boolean isCursorVisibleNow() {
        long elapsed = System.currentTimeMillis() - this.blinkAnchorMs;
        return ((elapsed / CURSOR_BLINK_PERIOD_MS) & 1L) == 0L;
    }

    private String sanitizeAndClamp(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), this.maxLength));
        for (int i = 0; i < raw.length() && sb.length() < this.maxLength; i++) {
            char c = raw.charAt(i);
            if (isAllowedInputChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isAllowedInputChar(char c) {
        return ChatAllowedCharacters.isAllowedCharacter(c);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int clampIndex(int index, int max) {
        if (index <= 0) {
            return 0;
        }
        if (index >= max) {
            return max;
        }
        return index;
    }

    private static boolean isCtrlDown() {
        if (!Keyboard.isCreated()) {
            return false;
        }
        return GuiScreen.isCtrlKeyDown();
    }

    private static boolean isShiftDown() {
        if (!Keyboard.isCreated()) {
            return false;
        }
        return GuiScreen.isShiftKeyDown();
    }
}
