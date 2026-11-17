package com.hoptimization.ui.component;

import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;

/**
 * Base class for reusable logical-pixel UI components.
 * Supports consistent bounds tracking, hover detection, disabled 40% alpha state, and input routing.
 */
public abstract class UiComponent {
    public static final float DISABLED_ALPHA = 0.40f;
    public static final float MIN_HIT_SIZE = 20.0f;

    protected float x;
    protected float y;
    protected float width;
    protected float height;
    protected boolean hovered;
    protected boolean mouseInside;
    protected boolean enabled = true;

    /**
     * Renders this component using the current active theme.
     *
     * @param x      logical left X
     * @param y      logical top Y
     * @param width  available logical width
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @param alpha  parent opacity multiplier in {@code [0, 1]}
     */
    public final void render(float x, float y, float width, float mouseX, float mouseY, float alpha) {
        render(x, y, width, mouseX, mouseY, alpha, ThemeManager.current());
    }

    /**
     * Renders this component within the specified logical bounds and theme.
     *
     * @param x      logical left X
     * @param y      logical top Y
     * @param width  available logical width
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @param alpha  parent opacity multiplier in {@code [0, 1]}
     * @param theme  theme data providing color and radius tokens
     */
    public abstract void render(
        float x,
        float y,
        float width,
        float mouseX,
        float mouseY,
        float alpha,
        ThemeData theme
    );

    /**
     * Handles a mouse press event at logical coordinates.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton LWJGL mouse button index ({@code 0} = left, {@code 1} = right, {@code 2} = middle)
     * @return {@code true} if the event was consumed
     */
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        return false;
    }

    /**
     * Handles a mouse release event at logical coordinates.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton LWJGL mouse button index
     * @return {@code true} if the event was consumed
     */
    public boolean mouseReleased(float mouseX, float mouseY, int mouseButton) {
        return false;
    }

    /**
     * Handles mouse drag movement while a mouse button is held.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton active LWJGL mouse button index
     * @return {@code true} if the drag was consumed
     */
    public boolean mouseDragged(float mouseX, float mouseY, int mouseButton) {
        return false;
    }

    /**
     * Alias for {@link #mouseDragged(float, float, int)} matching {@code GuiScreen.mouseClickMove}.
     *
     * @param mouseX             logical mouse X
     * @param mouseY             logical mouse Y
     * @param clickedMouseButton active LWJGL mouse button index
     * @param timeSinceLastClick elapsed milliseconds since mouse press
     * @return {@code true} if the drag was consumed
     */
    public boolean mouseClickMove(
        float mouseX,
        float mouseY,
        int clickedMouseButton,
        long timeSinceLastClick
    ) {
        return mouseDragged(mouseX, mouseY, clickedMouseButton);
    }

    /**
     * Handles a mouse wheel scroll event at logical coordinates.
     *
     * @param mouseX     logical mouse X
     * @param mouseY     logical mouse Y
     * @param wheelDelta signed scroll delta
     * @return {@code true} if the scroll was consumed
     */
    public boolean mouseScrolled(float mouseX, float mouseY, int wheelDelta) {
        return false;
    }

    /**
     * Handles a keyboard input event.
     *
     * @param typedChar typed Unicode character, or {@code '\0'} if non-printable
     * @param keyCode   LWJGL key code
     * @return {@code true} if the key event was consumed
     */
    public boolean keyTyped(char typedChar, int keyCode) {
        return false;
    }

    /**
     * Returns the logical height of this component.
     *
     * @return logical height
     */
    public abstract float getHeight();

    /**
     * Returns the preferred logical width of this component.
     *
     * @return preferred logical width
     */
    public float getPreferredWidth() {
        return this.width;
    }

    /**
     * Returns the last rendered logical X coordinate.
     *
     * @return logical X
     */
    public float getX() {
        return this.x;
    }

    /**
     * Returns the last rendered logical Y coordinate.
     *
     * @return logical Y
     */
    public float getY() {
        return this.y;
    }

    /**
     * Returns the last rendered logical width.
     *
     * @return logical width
     */
    public float getWidth() {
        return this.width;
    }

    /**
     * Returns whether the cursor is currently hovering this enabled component.
     *
     * @return {@code true} if hovered and enabled
     */
    public boolean isHovered() {
        return this.hovered && this.enabled;
    }

    /**
     * Returns whether the cursor was inside the component hitbox on the last render, regardless of enabled state.
     *
     * @return {@code true} if the cursor lies inside the hitbox
     */
    public boolean isMouseInside() {
        return this.mouseInside;
    }

    /**
     * Returns whether this component accepts user interaction.
     *
     * @return {@code true} if enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Enables or disables interaction for this component.
     * Disabled components render at 40% opacity and ignore input.
     *
     * @param enabled {@code true} to enable interaction
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            this.hovered = false;
        }
        onEnabledChanged(enabled);
    }

    /**
     * Refreshes any cached localized display strings (for example on GUI init or language change).
     */
    public void refreshLabels() {
    }

    /**
     * Hook invoked when {@link #setEnabled(boolean)} changes state so subclasses can cancel active drags or focus.
     *
     * @param enabled new enabled state
     */
    protected void onEnabledChanged(boolean enabled) {
    }

    /**
     * Updates the tracked geometry and hover state from the latest render pass.
     *
     * @param x         logical left X
     * @param y         logical top Y
     * @param width     logical width
     * @param height    logical height
     * @param insideHit whether the mouse is currently inside the component hitbox
     */
    protected void updateBounds(float x, float y, float width, float height, boolean insideHit) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mouseInside = insideHit;
        this.hovered = insideHit && this.enabled;
    }

    /**
     * Computes the effective render alpha after clamping and applying the 40% disabled state multiplier.
     *
     * @param alpha parent opacity multiplier
     * @return effective opacity in {@code [0, 1]}
     */
    protected float effectiveAlpha(float alpha) {
        float clamped = clamp01(alpha);
        return this.enabled ? clamped : clamped * DISABLED_ALPHA;
    }

    /**
     * Resolves a non-null {@link ThemeData} instance, falling back to {@link ThemeManager#current()}.
     *
     * @param theme candidate theme
     * @return resolved theme
     */
    protected static ThemeData resolveTheme(ThemeData theme) {
        return theme != null ? theme : ThemeManager.current();
    }

    /**
     * Tests whether {@code (px, py)} lies inside the rectangle {@code (x, y, w, h)}.
     *
     * @return {@code true} if inside
     */
    protected static boolean hitTest(float px, float py, float x, float y, float w, float h) {
        return w > 0.0f && h > 0.0f && px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /**
     * Tests whether {@code (px, py)} lies inside the visual rect expanded symmetrically to at least 20x20.
     * Satisfies the minimum pointer hitbox rule in {@code 02_美术规范.md §10}.
     *
     * @return {@code true} if inside the expanded hitbox
     */
    protected static boolean hitTestMin20(float px, float py, float x, float y, float w, float h) {
        float hitW = Math.max(MIN_HIT_SIZE, w);
        float hitH = Math.max(MIN_HIT_SIZE, h);
        float hitX = x - (hitW - w) * 0.5f;
        float hitY = y - (hitH - h) * 0.5f;
        return hitTest(px, py, hitX, hitY, hitW, hitH);
    }

    /**
     * Clamps {@code value} to {@code [0, 1]}.
     *
     * @param value input float
     * @return clamped value
     */
    protected static float clamp01(float value) {
        if (value <= 0.0f || Float.isNaN(value)) {
            return 0.0f;
        }
        if (value >= 1.0f) {
            return 1.0f;
        }
        return value;
    }

    /**
     * Clamps {@code value} to {@code [min, max]}.
     *
     * @param value input float
     * @param min   inclusive minimum
     * @param max   inclusive maximum
     * @return clamped value
     */
    protected static float clamp(float value, float min, float max) {
        if (value <= min) {
            return min;
        }
        if (value >= max) {
            return max;
        }
        return value;
    }
}
