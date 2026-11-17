package com.hoptimization.ui.component;

import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import org.lwjgl.input.Keyboard;

/**
 * Reusable {@link PopupHost} implementation enforcing a single active {@link PopupOverlay},
 * top-most rendering, click-outside dismissal, and Escape dismissal.
 */
public class PopupManager implements PopupHost {
    private PopupOverlay activePopup;
    private boolean customViewport = false;
    private float viewportX;
    private float viewportY;
    private float viewportWidth;
    private float viewportHeight;

    /**
     * Configures an explicit logical viewport used by popups to avoid overflowing bounds.
     *
     * @param x      viewport left X
     * @param y      viewport top Y
     * @param width  viewport width
     * @param height viewport height
     */
    public void setViewport(float x, float y, float width, float height) {
        this.customViewport = true;
        this.viewportX = x;
        this.viewportY = y;
        this.viewportWidth = Math.max(0.0f, width);
        this.viewportHeight = Math.max(0.0f, height);
    }

    /**
     * Reverts viewport bounds back to the full logical display size from {@link UiScale}.
     */
    public void clearCustomViewport() {
        this.customViewport = false;
    }

    @Override
    public void openPopup(PopupOverlay overlay) {
        if (overlay == null) {
            return;
        }
        if (this.activePopup != null && this.activePopup != overlay) {
            this.activePopup.closeOverlay();
        }
        this.activePopup = overlay;
    }

    @Override
    public void closePopup(PopupOverlay overlay) {
        if (overlay == null || this.activePopup != overlay) {
            return;
        }
        this.activePopup = null;
        if (overlay.isOverlayOpen()) {
            overlay.closeOverlay();
        }
    }

    @Override
    public void closeActivePopup() {
        PopupOverlay current = this.activePopup;
        if (current == null) {
            return;
        }
        this.activePopup = null;
        if (current.isOverlayOpen()) {
            current.closeOverlay();
        }
    }

    @Override
    public PopupOverlay getActivePopup() {
        if (this.activePopup != null && !this.activePopup.isOverlayOpen()) {
            this.activePopup = null;
        }
        return this.activePopup;
    }

    @Override
    public boolean hasActivePopup() {
        return getActivePopup() != null;
    }

    @Override
    public float getViewportX() {
        return this.customViewport ? this.viewportX : 0.0f;
    }

    @Override
    public float getViewportY() {
        return this.customViewport ? this.viewportY : 0.0f;
    }

    @Override
    public float getViewportWidth() {
        return this.customViewport ? this.viewportWidth : UiScale.getLogicalWidth();
    }

    @Override
    public float getViewportHeight() {
        return this.customViewport ? this.viewportHeight : UiScale.getLogicalHeight();
    }

    /**
     * Renders the currently open popup overlay in the top-most layer.
     *
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @param alpha  window opacity multiplier in {@code [0, 1]}
     */
    public void render(float mouseX, float mouseY, float alpha) {
        render(mouseX, mouseY, alpha, ThemeManager.current());
    }

    /**
     * Renders the currently open popup overlay in the top-most layer using the supplied theme.
     *
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @param alpha  window opacity multiplier in {@code [0, 1]}
     * @param theme  active theme data
     */
    public void render(float mouseX, float mouseY, float alpha, ThemeData theme) {
        PopupOverlay overlay = getActivePopup();
        if (overlay == null) {
            return;
        }
        ThemeData resolved = theme != null ? theme : ThemeManager.current();
        overlay.renderOverlay(mouseX, mouseY, alpha, resolved);
        if (!overlay.isOverlayOpen()) {
            this.activePopup = null;
        }
    }

    /**
     * Routes mouse clicks to the active popup, or closes it when clicking outside.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton LWJGL mouse button index
     * @return {@code true} if an active popup handled or was dismissed by this click
     */
    public boolean mouseClicked(float mouseX, float mouseY, int mouseButton) {
        PopupOverlay overlay = getActivePopup();
        if (overlay == null) {
            return false;
        }
        if (overlay.isPointInsideOverlay(mouseX, mouseY)) {
            overlay.handleOverlayMouseClicked(mouseX, mouseY, mouseButton);
            if (!overlay.isOverlayOpen()) {
                this.activePopup = null;
            }
            return true;
        }
        closeActivePopup();
        return true;
    }

    /**
     * Routes mouse releases to the active popup regardless of cursor position so drags finish cleanly.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton LWJGL mouse button index
     * @return {@code true} if an active popup was present
     */
    public boolean mouseReleased(float mouseX, float mouseY, int mouseButton) {
        PopupOverlay overlay = getActivePopup();
        if (overlay == null) {
            return false;
        }
        overlay.handleOverlayMouseReleased(mouseX, mouseY, mouseButton);
        if (!overlay.isOverlayOpen()) {
            this.activePopup = null;
        }
        return true;
    }

    /**
     * Routes mouse drags to the active popup regardless of cursor position.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton active LWJGL mouse button index
     * @return {@code true} if an active popup was present
     */
    public boolean mouseDragged(float mouseX, float mouseY, int mouseButton) {
        PopupOverlay overlay = getActivePopup();
        if (overlay == null) {
            return false;
        }
        overlay.handleOverlayMouseDragged(mouseX, mouseY, mouseButton);
        if (!overlay.isOverlayOpen()) {
            this.activePopup = null;
        }
        return true;
    }

    /**
     * Routes scroll events to the active popup overlay.
     *
     * @param mouseX     logical mouse X
     * @param mouseY     logical mouse Y
     * @param wheelDelta signed scroll delta
     * @return {@code true} if an active popup consumed or blocked the scroll
     */
    public boolean mouseScrolled(float mouseX, float mouseY, int wheelDelta) {
        PopupOverlay overlay = getActivePopup();
        if (overlay == null) {
            return false;
        }
        overlay.handleOverlayMouseScrolled(mouseX, mouseY, wheelDelta);
        return true;
    }

    /**
     * Routes key events to the active popup, closing it on Escape if not handled internally.
     *
     * @param typedChar typed character
     * @param keyCode   LWJGL key code
     * @return {@code true} if consumed by the active popup
     */
    public boolean keyTyped(char typedChar, int keyCode) {
        PopupOverlay overlay = getActivePopup();
        if (overlay == null) {
            return false;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeActivePopup();
            return true;
        }
        boolean handled = overlay.handleOverlayKeyTyped(typedChar, keyCode);
        if (!overlay.isOverlayOpen()) {
            this.activePopup = null;
        }
        return handled;
    }
}
