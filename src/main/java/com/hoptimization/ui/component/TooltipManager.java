package com.hoptimization.ui.component;

import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates delayed hover tooltips ({@code 500ms} delay, {@code fast} fade-in, cached CJK+ASCII
 * wrapped lines for zero per-frame allocation while displayed).
 */
public class TooltipManager {
    public static final long HOVER_DELAY_NANOS = 500_000_000L;

    private final Animation fadeAnim = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final List<String> cachedLines = new ArrayList<String>(8);

    private Object activeOwner;
    private String activeText;
    private long hoverStartNanos = 0L;

    private boolean requestedThisFrame = false;
    private Object pendingOwner;
    private String pendingText;
    private boolean hasExplicitAnchor = false;
    private float pendingAnchorX = 0.0f;
    private float pendingAnchorY = 0.0f;

    private String wrappedForText;
    private TtfFontRenderer wrappedForFont;
    private float cachedWidth = 0.0f;
    private float cachedHeight = 0.0f;

    private boolean customViewport = false;
    private float viewportX;
    private float viewportY;
    private float viewportWidth;
    private float viewportHeight;

    /**
     * Clears the current frame's pending tooltip request.
     * Calling this explicitly is optional because {@link #render(float, float, float, ThemeData)} also clears it.
     */
    public void beginFrame() {
        this.requestedThisFrame = false;
        this.pendingOwner = null;
        this.pendingText = null;
        this.hasExplicitAnchor = false;
    }

    /**
     * Sets an explicit viewport rectangle used for edge clamping/flipping.
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
     * Reverts viewport bounds back to the full logical display size.
     */
    public void clearCustomViewport() {
        this.customViewport = false;
    }

    /**
     * Requests a cursor-anchored tooltip for the current frame.
     *
     * @param text tooltip text (empty or null is ignored)
     */
    public void request(String text) {
        request(text, text);
    }

    /**
     * Requests a cursor-anchored tooltip keyed by {@code ownerKey} for the current frame.
     * Switching between different owners resets the {@code 500ms} hover delay even if text matches.
     *
     * @param ownerKey logical owner key identifying the hovered control
     * @param text     tooltip text
     */
    public void request(Object ownerKey, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        this.requestedThisFrame = true;
        this.pendingOwner = ownerKey != null ? ownerKey : text;
        this.pendingText = text;
        this.hasExplicitAnchor = false;
    }

    /**
     * Requests a tooltip positioned near a specific logical anchor coordinate.
     *
     * @param ownerKey logical owner key
     * @param text     tooltip text
     * @param anchorX  preferred left X
     * @param anchorY  preferred top Y
     */
    public void requestAt(Object ownerKey, String text, float anchorX, float anchorY) {
        if (text == null || text.isEmpty()) {
            return;
        }
        this.requestedThisFrame = true;
        this.pendingOwner = ownerKey != null ? ownerKey : text;
        this.pendingText = text;
        this.hasExplicitAnchor = true;
        this.pendingAnchorX = anchorX;
        this.pendingAnchorY = anchorY;
    }

    /**
     * Resets active tooltip state immediately (for example on GUI close).
     */
    public void clear() {
        this.activeOwner = null;
        this.activeText = null;
        this.hoverStartNanos = 0L;
        this.requestedThisFrame = false;
        this.pendingOwner = null;
        this.pendingText = null;
        this.fadeAnim.snapTo(0.0f);
    }

    /**
     * Evaluates hover timing at {@link System#nanoTime()} and renders the tooltip if ready.
     *
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @param alpha  parent opacity multiplier in {@code [0, 1]}
     */
    public void render(float mouseX, float mouseY, float alpha) {
        render(mouseX, mouseY, alpha, ThemeManager.current());
    }

    /**
     * Evaluates hover timing at {@link System#nanoTime()} and renders the tooltip if ready.
     *
     * @param mouseX logical mouse X
     * @param mouseY logical mouse Y
     * @param alpha  parent opacity multiplier in {@code [0, 1]}
     * @param theme  active theme data
     */
    public void render(float mouseX, float mouseY, float alpha, ThemeData theme) {
        long now = System.nanoTime();
        boolean visible = updateTiming(now);
        float fade = this.fadeAnim.get(now);
        float effAlpha = Math.max(0.0f, Math.min(1.0f, alpha)) * fade;

        if (visible && effAlpha > 0.001f && this.activeText != null) {
            TtfFontRenderer font = TooltipRenderer.getTooltipFont();
            ensureWrappedCache(this.activeText, font);
            float anchorX = this.hasExplicitAnchor ? this.pendingAnchorX : (mouseX + TooltipRenderer.CURSOR_OFFSET_X);
            float anchorY = this.hasExplicitAnchor ? this.pendingAnchorY : (mouseY + TooltipRenderer.CURSOR_OFFSET_Y);
            float vpX = this.customViewport ? this.viewportX : 0.0f;
            float vpY = this.customViewport ? this.viewportY : 0.0f;
            float vpW = this.customViewport ? this.viewportWidth : UiScale.getLogicalWidth();
            float vpH = this.customViewport ? this.viewportHeight : UiScale.getLogicalHeight();

            TooltipRenderer.renderWrapped(
                this.cachedLines,
                this.cachedWidth,
                this.cachedHeight,
                anchorX,
                anchorY,
                vpX,
                vpY,
                vpW,
                vpH,
                effAlpha,
                theme != null ? theme : ThemeManager.current(),
                font
            );
        }

        this.requestedThisFrame = false;
        this.pendingOwner = null;
        this.pendingText = null;
    }

    /**
     * Package-accessible timing step for deterministic hover-delay verification.
     */
    boolean updateTiming(long nowNanos) {
        if (!this.requestedThisFrame || this.pendingText == null) {
            this.activeOwner = null;
            this.activeText = null;
            this.hoverStartNanos = 0L;
            this.fadeAnim.snapTo(0.0f);
            return false;
        }

        boolean sameTarget = this.activeOwner != null
            && this.activeOwner.equals(this.pendingOwner)
            && this.pendingText.equals(this.activeText);
        if (!sameTarget) {
            this.activeOwner = this.pendingOwner;
            this.activeText = this.pendingText;
            this.hoverStartNanos = nowNanos;
            this.fadeAnim.snapTo(0.0f);
            return false;
        }

        if (nowNanos - this.hoverStartNanos >= HOVER_DELAY_NANOS) {
            this.fadeAnim.setTarget(1.0f);
            return true;
        }
        return false;
    }

    private void ensureWrappedCache(String text, TtfFontRenderer font) {
        if (font == this.wrappedForFont && text.equals(this.wrappedForText)) {
            return;
        }
        this.wrappedForText = text;
        this.wrappedForFont = font;
        float maxTextWidth = TooltipRenderer.MAX_WIDTH - TooltipRenderer.PADDING_H * 2.0f;
        TooltipRenderer.wrapText(font, text, maxTextWidth, this.cachedLines);
        float maxLineW = TooltipRenderer.measureMaxLineWidth(font, this.cachedLines);
        this.cachedWidth = Math.min(TooltipRenderer.MAX_WIDTH, maxLineW + TooltipRenderer.PADDING_H * 2.0f);
        this.cachedHeight = TooltipRenderer.computeBoxHeight(font, this.cachedLines.size());
    }
}
