package com.hoptimization.ui.component;

import com.hoptimization.ui.theme.ThemeData;

/**
 * Contract for top-most layer popups (such as dropdown menus and color pickers)
 * managed by a {@link PopupHost}.
 */
public interface PopupOverlay {

    /**
     * Renders the popup in the top-most UI layer.
     *
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @param alpha  parent window opacity multiplier in {@code [0, 1]}
     * @param theme  active theme data
     */
    void renderOverlay(float mouseX, float mouseY, float alpha, ThemeData theme);

    /**
     * Handles a mouse click inside the popup overlay.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton LWJGL mouse button index
     * @return {@code true} if consumed
     */
    boolean handleOverlayMouseClicked(float mouseX, float mouseY, int mouseButton);

    /**
     * Handles a mouse release event while this overlay is active.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton LWJGL mouse button index
     * @return {@code true} if consumed
     */
    boolean handleOverlayMouseReleased(float mouseX, float mouseY, int mouseButton);

    /**
     * Handles mouse drag movement while this overlay is active.
     *
     * @param mouseX      logical mouse X
     * @param mouseY      logical mouse Y
     * @param mouseButton active LWJGL mouse button index
     * @return {@code true} if consumed
     */
    boolean handleOverlayMouseDragged(float mouseX, float mouseY, int mouseButton);

    /**
     * Handles mouse wheel scrolling while this overlay is active.
     *
     * @param mouseX     logical mouse X
     * @param mouseY     logical mouse Y
     * @param wheelDelta signed scroll delta
     * @return {@code true} if consumed
     */
    boolean handleOverlayMouseScrolled(float mouseX, float mouseY, int wheelDelta);

    /**
     * Handles keyboard input while this overlay is active.
     *
     * @param typedChar typed character
     * @param keyCode   LWJGL key code
     * @return {@code true} if consumed
     */
    boolean handleOverlayKeyTyped(char typedChar, int keyCode);

    /**
     * Returns whether this overlay is currently open.
     *
     * @return {@code true} if open
     */
    boolean isOverlayOpen();

    /**
     * Closes this overlay and cleans up any active interaction state.
     */
    void closeOverlay();

    /**
     * Checks whether the given logical coordinates fall within the popup's visual rectangle.
     *
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @return {@code true} if inside the popup
     */
    boolean isPointInsideOverlay(float mouseX, float mouseY);
}
