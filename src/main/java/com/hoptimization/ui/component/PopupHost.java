package com.hoptimization.ui.component;

/**
 * Host container coordinating top-most popup rendering and single-popup exclusivity.
 * At most one {@link PopupOverlay} may be open at any time.
 */
public interface PopupHost {

    /**
     * Opens the specified popup, closing any previously active popup first.
     *
     * @param overlay popup overlay to activate
     */
    void openPopup(PopupOverlay overlay);

    /**
     * Closes the specified popup if it is currently active.
     *
     * @param overlay popup overlay to close
     */
    void closePopup(PopupOverlay overlay);

    /**
     * Closes whichever popup is currently active, if any.
     */
    void closeActivePopup();

    /**
     * Returns the currently active popup overlay, or {@code null} if none is open.
     *
     * @return active popup or {@code null}
     */
    PopupOverlay getActivePopup();

    /**
     * Returns whether a popup overlay is currently open.
     *
     * @return {@code true} if an active popup is open
     */
    boolean hasActivePopup();

    /**
     * Returns the left logical X bound of the host viewport used for popup clamping.
     *
     * @return viewport left X
     */
    float getViewportX();

    /**
     * Returns the top logical Y bound of the host viewport used for popup clamping.
     *
     * @return viewport top Y
     */
    float getViewportY();

    /**
     * Returns the logical width of the host viewport used for popup clamping.
     *
     * @return viewport width
     */
    float getViewportWidth();

    /**
     * Returns the logical height of the host viewport used for popup clamping.
     *
     * @return viewport height
     */
    float getViewportHeight();
}
