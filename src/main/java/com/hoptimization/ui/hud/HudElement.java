package com.hoptimization.ui.hud;

import com.hoptimization.ui.theme.ThemeData;

/**
 * Contract for renderable, draggable HUD elements managed by {@link HudOverlayRenderer}
 * and positioned in {@link HudEditScreen}.
 */
public interface HudElement {

    /**
     * Returns the stable persistence identifier matching {@code UiConfig.getHudLayout(id)}.
     *
     * @return HUD element identifier
     */
    String getHudId();

    /**
     * Returns the localized display name shown in {@link HudEditScreen} overlays.
     *
     * @return localized element name
     */
    String getHudDisplayName();

    /**
     * Returns whether this HUD element is currently enabled and eligible for in-game rendering.
     *
     * @return {@code true} if enabled
     */
    boolean isHudEnabled();

    /**
     * Returns the current logical width of this HUD element at scale {@code 1.0f}.
     *
     * @return logical width in UI pixels
     */
    float getHudWidth();

    /**
     * Returns the current logical height of this HUD element at scale {@code 1.0f}.
     *
     * @return logical height in UI pixels
     */
    float getHudHeight();

    /**
     * Computes the default logical X coordinate when no saved layout exists in {@code UiConfig}.
     *
     * @param screenWidth  logical viewport width
     * @param screenHeight logical viewport height
     * @return default logical X coordinate
     */
    float getDefaultHudX(float screenWidth, float screenHeight);

    /**
     * Computes the default logical Y coordinate when no saved layout exists in {@code UiConfig}.
     *
     * @param screenWidth  logical viewport width
     * @param screenHeight logical viewport height
     * @return default logical Y coordinate
     */
    float getDefaultHudY(float screenWidth, float screenHeight);

    /**
     * Renders this HUD element at the resolved logical coordinates.
     *
     * @param x            logical left X
     * @param y            logical top Y
     * @param partialTicks partial render tick fraction
     * @param theme        active theme snapshot
     * @param editMode     {@code true} when rendered inside {@link HudEditScreen} for layout preview
     */
    void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode);
}
