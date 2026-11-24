package com.hoptimization.ui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTBlendFuncSeparate;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.nio.FloatBuffer;

/**
 * Unified 2D UI rendering primitives with automatic GLSL 120 SDF acceleration and fixed-function fallbacks.
 * Strictly restores all mutated OpenGL and {@link GlStateManager} states and performs zero hot-path allocations.
 */
public final class RenderUtil {
    private static final int CORNER_SEGMENTS = 16;
    private static final int RING_SEGMENTS = 48;
    private static final int MAX_RING_VERTICES = (CORNER_SEGMENTS + 1) * 4;
    private static final int STATE_STACK_CAPACITY = 8;
    private static final int SHADOW_INWARD_STEPS = 3;
    private static final int SHADOW_OUTWARD_STEPS = 8;

    private static final float[] COS_TABLE = new float[CORNER_SEGMENTS + 1];
    private static final float[] SIN_TABLE = new float[CORNER_SEGMENTS + 1];
    private static final float[] CIRCLE_COS = new float[RING_SEGMENTS + 1];
    private static final float[] CIRCLE_SIN = new float[RING_SEGMENTS + 1];
    private static final float[] QUAD_COS_X = new float[] { -1.0f, 0.0f, 1.0f, 0.0f };
    private static final float[] QUAD_SIN_X = new float[] { 0.0f, 1.0f, 0.0f, -1.0f };
    private static final float[] QUAD_COS_Y = new float[] { 0.0f, -1.0f, 0.0f, 1.0f };
    private static final float[] QUAD_SIN_Y = new float[] { -1.0f, 0.0f, 1.0f, 0.0f };

    private static final float[] RING_X_A = new float[MAX_RING_VERTICES];
    private static final float[] RING_Y_A = new float[MAX_RING_VERTICES];
    private static final float[] RING_X_B = new float[MAX_RING_VERTICES];
    private static final float[] RING_Y_B = new float[MAX_RING_VERTICES];

    private static final FloatBuffer COLOR_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final StateBackup[] STATE_STACK = new StateBackup[STATE_STACK_CAPACITY];
    private static int stateDepth = 0;
    private static int uiBatchDepth = 0;
    private static int uiBatchInnerDepth = 0;

    static {
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            double angle = (Math.PI * 0.5D * i) / CORNER_SEGMENTS;
            COS_TABLE[i] = (float) Math.cos(angle);
            SIN_TABLE[i] = (float) Math.sin(angle);
        }
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = -Math.PI * 0.5D + (Math.PI * 2.0D * i) / RING_SEGMENTS;
            CIRCLE_COS[i] = (float) Math.cos(angle);
            CIRCLE_SIN[i] = (float) Math.sin(angle);
        }
        for (int i = 0; i < STATE_STACK_CAPACITY; i++) {
            STATE_STACK[i] = new StateBackup();
        }
    }

    private RenderUtil() {
    }

    /**
     * Draws a filled rounded rectangle with a uniform corner radius.
     *
     * @param x         logical left X
     * @param y         logical top Y
     * @param width     logical width
     * @param height    logical height
     * @param radius    uniform corner radius
     * @param fillColor fill color in ARGB format
     */
    public static void drawRoundedRect(float x, float y, float width, float height, float radius, int fillColor) {
        drawRoundedRect(x, y, width, height, radius, radius, radius, radius, fillColor, 0, 0.0f);
    }

    /**
     * Draws a filled rounded rectangle with four independent corner radii.
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param width       logical width
     * @param height      logical height
     * @param topLeft     top-left corner radius
     * @param topRight    top-right corner radius
     * @param bottomRight bottom-right corner radius
     * @param bottomLeft  bottom-left corner radius
     * @param fillColor   fill color in ARGB format
     */
    public static void drawRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        int fillColor
    ) {
        drawRoundedRect(x, y, width, height, topLeft, topRight, bottomRight, bottomLeft, fillColor, 0, 0.0f);
    }

    /**
     * Draws a rounded rectangle with uniform corner radius, fill color, and inner border.
     *
     * @param x               logical left X
     * @param y               logical top Y
     * @param width           logical width
     * @param height          logical height
     * @param radius          uniform corner radius
     * @param fillColor       fill color in ARGB format
     * @param borderColor     border color in ARGB format
     * @param borderThickness inner border thickness in logical pixels
     */
    public static void drawRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int fillColor,
        int borderColor,
        float borderThickness
    ) {
        drawRoundedRect(
            x, y, width, height,
            radius, radius, radius, radius,
            fillColor, borderColor, borderThickness
        );
    }

    /**
     * Draws a rounded rectangle with four corner radii, fill color, and inner border.
     *
     * @param x               logical left X
     * @param y               logical top Y
     * @param width           logical width
     * @param height          logical height
     * @param topLeft         top-left corner radius
     * @param topRight        top-right corner radius
     * @param bottomRight     bottom-right corner radius
     * @param bottomLeft      bottom-left corner radius
     * @param fillColor       fill color in ARGB format
     * @param borderColor     border color in ARGB format
     * @param borderThickness inner border thickness in logical pixels
     */
    public static void drawRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        int fillColor,
        int borderColor,
        float borderThickness
    ) {
        RoundedRectShader.drawRoundedRect(
            x, y, width, height,
            topLeft, topRight, bottomRight, bottomLeft,
            fillColor, borderColor, borderThickness
        );
    }

    /**
     * Draws a rounded rectangle with a soft drop shadow using a uniform corner radius.
     *
     * @param x               logical left X
     * @param y               logical top Y
     * @param width           logical width
     * @param height          logical height
     * @param radius          uniform corner radius
     * @param fillColor       fill color in ARGB format
     * @param borderColor     border color in ARGB format
     * @param borderThickness inner border thickness in logical pixels
     * @param shadowOffsetX   horizontal shadow offset in logical pixels
     * @param shadowOffsetY   vertical shadow offset in logical pixels
     * @param shadowBlur      shadow blur radius in logical pixels
     * @param shadowColor     shadow color in ARGB format
     */
    public static void drawRoundedRectWithShadow(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int fillColor,
        int borderColor,
        float borderThickness,
        float shadowOffsetX,
        float shadowOffsetY,
        float shadowBlur,
        int shadowColor
    ) {
        drawRoundedRectWithShadow(
            x, y, width, height,
            radius, radius, radius, radius,
            fillColor, borderColor, borderThickness,
            shadowOffsetX, shadowOffsetY, shadowBlur, shadowColor
        );
    }

    /**
     * Draws a rounded rectangle with four corner radii and an optional soft drop shadow.
     *
     * @param x               logical left X
     * @param y               logical top Y
     * @param width           logical width
     * @param height          logical height
     * @param topLeft         top-left corner radius
     * @param topRight        top-right corner radius
     * @param bottomRight     bottom-right corner radius
     * @param bottomLeft      bottom-left corner radius
     * @param fillColor       fill color in ARGB format
     * @param borderColor     border color in ARGB format
     * @param borderThickness inner border thickness in logical pixels
     * @param shadowOffsetX   horizontal shadow offset in logical pixels
     * @param shadowOffsetY   vertical shadow offset in logical pixels
     * @param shadowBlur      shadow blur radius in logical pixels
     * @param shadowColor     shadow color in ARGB format
     */
    public static void drawRoundedRectWithShadow(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        int fillColor,
        int borderColor,
        float borderThickness,
        float shadowOffsetX,
        float shadowOffsetY,
        float shadowBlur,
        int shadowColor
    ) {
        RoundedRectShader.drawRoundedRect(
            x, y, width, height,
            topLeft, topRight, bottomRight, bottomLeft,
            fillColor, borderColor, borderThickness,
            shadowOffsetX, shadowOffsetY, shadowBlur, shadowColor
        );
    }

    /**
     * Draws a frosted-glass rounded rectangle sampling the blurred backdrop texture, with automatic fallback
     * to a translucent rounded rectangle when blur shaders or FBOs are unavailable.
     */
    public static void drawFrostedRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int tintColor,
        int borderColor,
        float borderThickness,
        float highlightStrength,
        float masterAlpha
    ) {
        if (!BackdropBlurRenderer.tryDrawFrostedRoundedRect(
            x, y, width, height,
            radius, radius, radius, radius,
            tintColor, borderColor, borderThickness,
            highlightStrength, masterAlpha
        )) {
            drawRoundedRect(
                x, y, width, height,
                radius,
                withAlpha(tintColor, masterAlpha),
                withAlpha(borderColor, masterAlpha),
                borderThickness
            );
        }
    }

    /**
     * Draws a frosted-glass rounded rectangle with a soft drop shadow.
     */
    public static void drawFrostedRoundedRectWithShadow(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int tintColor,
        int borderColor,
        float borderThickness,
        float highlightStrength,
        float masterAlpha,
        float shadowOffsetX,
        float shadowOffsetY,
        float shadowBlur,
        int shadowColor
    ) {
        if (shadowBlur > 0.0f && (shadowColor >>> 24) != 0) {
            drawShadow(
                x, y, width, height,
                radius, radius, radius, radius,
                shadowOffsetX, shadowOffsetY, shadowBlur,
                withAlpha(shadowColor, masterAlpha)
            );
        }
        drawFrostedRoundedRect(
            x, y, width, height,
            radius, tintColor, borderColor, borderThickness,
            highlightStrength, masterAlpha
        );
    }

    /**
     * Draws only the inner border of a rounded rectangle with uniform corner radius.
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param width       logical width
     * @param height      logical height
     * @param radius      uniform corner radius
     * @param thickness   border thickness in logical pixels
     * @param borderColor border color in ARGB format
     */
    public static void drawRoundedBorder(
        float x,
        float y,
        float width,
        float height,
        float radius,
        float thickness,
        int borderColor
    ) {
        drawRoundedBorder(x, y, width, height, radius, radius, radius, radius, thickness, borderColor);
    }

    /**
     * Draws only the inner border of a rounded rectangle with four independent corner radii.
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param width       logical width
     * @param height      logical height
     * @param topLeft     top-left corner radius
     * @param topRight    top-right corner radius
     * @param bottomRight bottom-right corner radius
     * @param bottomLeft  bottom-left corner radius
     * @param thickness   border thickness in logical pixels
     * @param borderColor border color in ARGB format
     */
    public static void drawRoundedBorder(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        float thickness,
        int borderColor
    ) {
        drawRoundedRect(x, y, width, height, topLeft, topRight, bottomRight, bottomLeft, 0, borderColor, thickness);
    }

    /**
     * Draws a centered soft shadow for a rounded rectangle.
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param width       logical width
     * @param height      logical height
     * @param radius      uniform corner radius
     * @param blurRadius  blur radius in logical pixels
     * @param shadowColor shadow color in ARGB format
     */
    public static void drawShadow(
        float x,
        float y,
        float width,
        float height,
        float radius,
        float blurRadius,
        int shadowColor
    ) {
        RoundedRectShader.drawShadow(x, y, width, height, radius, blurRadius, shadowColor);
    }

    /**
     * Draws an offset soft drop shadow for a rounded rectangle with four independent corner radii.
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param width       logical width
     * @param height      logical height
     * @param topLeft     top-left corner radius
     * @param topRight    top-right corner radius
     * @param bottomRight bottom-right corner radius
     * @param bottomLeft  bottom-left corner radius
     * @param offsetX     horizontal offset in logical pixels
     * @param offsetY     vertical offset in logical pixels
     * @param blurRadius  blur radius in logical pixels
     * @param shadowColor shadow color in ARGB format
     */
    public static void drawShadow(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        float offsetX,
        float offsetY,
        float blurRadius,
        int shadowColor
    ) {
        RoundedRectShader.drawShadow(
            x, y, width, height,
            topLeft, topRight, bottomRight, bottomLeft,
            offsetX, offsetY, blurRadius, shadowColor
        );
    }

    /**
     * Draws a capsule pill shape (corner radius = {@code min(width, height) / 2}).
     *
     * @param x         logical left X
     * @param y         logical top Y
     * @param width     logical width
     * @param height    logical height
     * @param fillColor fill color in ARGB format
     */
    public static void drawPill(float x, float y, float width, float height, int fillColor) {
        float radius = Math.min(width, height) * 0.5f;
        drawRoundedRect(x, y, width, height, radius, fillColor);
    }

    /**
     * Draws a capsule pill shape with an inner border.
     *
     * @param x               logical left X
     * @param y               logical top Y
     * @param width           logical width
     * @param height          logical height
     * @param fillColor       fill color in ARGB format
     * @param borderColor     border color in ARGB format
     * @param borderThickness inner border thickness in logical pixels
     */
    public static void drawPill(
        float x,
        float y,
        float width,
        float height,
        int fillColor,
        int borderColor,
        float borderThickness
    ) {
        float radius = Math.min(width, height) * 0.5f;
        drawRoundedRect(x, y, width, height, radius, fillColor, borderColor, borderThickness);
    }

    /**
     * Draws a filled anti-aliased circle centered at {@code (centerX, centerY)}.
     *
     * @param centerX   logical center X
     * @param centerY   logical center Y
     * @param radius    circle radius in logical pixels
     * @param fillColor fill color in ARGB format
     */
    public static void drawCircle(float centerX, float centerY, float radius, int fillColor) {
        if (radius <= 0.0f) {
            return;
        }
        float diameter = radius * 2.0f;
        drawRoundedRect(centerX - radius, centerY - radius, diameter, diameter, radius, fillColor);
    }

    /**
     * Draws an anti-aliased circle with an inner border centered at {@code (centerX, centerY)}.
     *
     * @param centerX         logical center X
     * @param centerY         logical center Y
     * @param radius          circle radius in logical pixels
     * @param fillColor       fill color in ARGB format
     * @param borderColor     border color in ARGB format
     * @param borderThickness inner border thickness in logical pixels
     */
    public static void drawCircle(
        float centerX,
        float centerY,
        float radius,
        int fillColor,
        int borderColor,
        float borderThickness
    ) {
        if (radius <= 0.0f) {
            return;
        }
        float diameter = radius * 2.0f;
        drawRoundedRect(
            centerX - radius, centerY - radius, diameter, diameter,
            radius, fillColor, borderColor, borderThickness
        );
    }

    /**
     * Draws a smooth circular progress ring starting at 12 o'clock and sweeping clockwise by {@code progress} in [0, 1].
     *
     * @param centerX       logical center X
     * @param centerY       logical center Y
     * @param radius        mid-line ring radius in logical pixels
     * @param thickness     ring stroke width in logical pixels
     * @param progress      fraction in {@code [0, 1]}
     * @param trackColor    background track ARGB color
     * @param progressColor active progress arc ARGB color
     */
    public static void drawCircularProgressRing(
        float centerX,
        float centerY,
        float radius,
        float thickness,
        float progress,
        int trackColor,
        int progressColor
    ) {
        if (radius <= 0.0f || thickness <= 0.0f) {
            return;
        }
        if (CircularRingShader.tryDrawCircularRing(
            centerX, centerY, radius, thickness, progress, trackColor, progressColor
        )) {
            return;
        }
        float clampedProgress = progress <= 0.0f ? 0.0f : (progress >= 1.0f ? 1.0f : progress);
        float halfT = thickness * 0.5f;
        float innerR = Math.max(0.0f, radius - halfT);
        float outerR = radius + halfT;

        pushUntexturedBlendState(false);
        try {
            if (((trackColor >>> 24) & 0xFF) != 0) {
                drawArcStripImmediate(centerX, centerY, innerR, outerR, 1.0f, trackColor);
            }
            if (clampedProgress > 0.005f && ((progressColor >>> 24) & 0xFF) != 0) {
                drawArcStripImmediate(centerX, centerY, innerR, outerR, clampedProgress, progressColor);
            }
        } finally {
            popState();
        }

        // Rounded caps at start and end of partial arcs for clean, modern Apple-Watch-like ring ends.
        if (clampedProgress > 0.01f && clampedProgress < 0.995f && ((progressColor >>> 24) & 0xFF) != 0) {
            float capRadius = halfT;
            drawCircle(centerX, centerY - radius, capRadius, progressColor);
            double endAngle = -Math.PI * 0.5D + Math.PI * 2.0D * clampedProgress;
            float endX = centerX + (float) Math.cos(endAngle) * radius;
            float endY = centerY + (float) Math.sin(endAngle) * radius;
            drawCircle(endX, endY, capRadius, progressColor);
        }
    }

    private static void drawArcStripImmediate(
        float centerX,
        float centerY,
        float innerR,
        float outerR,
        float fraction,
        int argb
    ) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;

        float totalSteps = RING_SEGMENTS * fraction;
        int fullSteps = (int) Math.floor(totalSteps);
        boolean hasPartial = (totalSteps - fullSteps) > 0.001f;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= fullSteps; i++) {
            int idx = i <= RING_SEGMENTS ? i : RING_SEGMENTS;
            float c = CIRCLE_COS[idx];
            float s = CIRCLE_SIN[idx];
            wr.pos(centerX + c * outerR, centerY + s * outerR, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(centerX + c * innerR, centerY + s * innerR, 0.0D).color(r, g, b, a).endVertex();
        }
        if (hasPartial) {
            double endAngle = -Math.PI * 0.5D + Math.PI * 2.0D * fraction;
            float c = (float) Math.cos(endAngle);
            float s = (float) Math.sin(endAngle);
            wr.pos(centerX + c * outerR, centerY + s * outerR, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(centerX + c * innerR, centerY + s * innerR, 0.0D).color(r, g, b, a).endVertex();
        }
        tessellator.draw();
    }

    /**
     * Draws a sharp axis-aligned rectangle without touching vanilla {@code Gui.drawRect}.
     *
     * @param x      logical left X
     * @param y      logical top Y
     * @param width  logical width
     * @param height logical height
     * @param color  fill color in ARGB format
     */
    public static void drawRect(float x, float y, float width, float height, int color) {
        if (width <= 0.0f || height <= 0.0f || ((color >>> 24) & 0xFF) == 0) {
            return;
        }
        pushUntexturedBlendState(false);
        try {
            drawQuadImmediate(x, y, width, height, color);
        } finally {
            popState();
        }
    }

    /**
     * Draws a smooth 4-corner gradient rectangle.
     *
     * @param x                logical left X
     * @param y                logical top Y
     * @param width            logical width
     * @param height           logical height
     * @param topLeftColor     top-left ARGB color
     * @param topRightColor    top-right ARGB color
     * @param bottomRightColor bottom-right ARGB color
     * @param bottomLeftColor  bottom-left ARGB color
     */
    public static void drawGradientRect(
        float x,
        float y,
        float width,
        float height,
        int topLeftColor,
        int topRightColor,
        int bottomRightColor,
        int bottomLeftColor
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        if (((topLeftColor | topRightColor | bottomRightColor | bottomLeftColor) >>> 24) == 0) {
            return;
        }

        pushUntexturedBlendState(true);
        try {
            float x2 = x + width;
            float y2 = y + height;

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer worldRenderer = tessellator.getWorldRenderer();
            worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            putColoredVertex(worldRenderer, x, y2, bottomLeftColor);
            putColoredVertex(worldRenderer, x2, y2, bottomRightColor);
            putColoredVertex(worldRenderer, x2, y, topRightColor);
            putColoredVertex(worldRenderer, x, y, topLeftColor);
            tessellator.draw();
        } finally {
            popState();
        }
    }

    /**
     * Draws a vertical gradient rectangle (top to bottom).
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param width       logical width
     * @param height      logical height
     * @param topColor    top ARGB color
     * @param bottomColor bottom ARGB color
     */
    public static void drawVerticalGradient(
        float x,
        float y,
        float width,
        float height,
        int topColor,
        int bottomColor
    ) {
        drawGradientRect(x, y, width, height, topColor, topColor, bottomColor, bottomColor);
    }

    /**
     * Draws a horizontal gradient rectangle (left to right).
     *
     * @param x          logical left X
     * @param y          logical top Y
     * @param width      logical width
     * @param height     logical height
     * @param leftColor  left ARGB color
     * @param rightColor right ARGB color
     */
    public static void drawHorizontalGradient(
        float x,
        float y,
        float width,
        float height,
        int leftColor,
        int rightColor
    ) {
        drawGradientRect(x, y, width, height, leftColor, rightColor, rightColor, leftColor);
    }

    /**
     * Draws a scroll-edge fade mask between {@code surfaceColor} and transparent.
     * The transparent end preserves {@code surfaceColor}'s RGB channels so light themes do not develop dark fringes.
     *
     * @param x            logical left X
     * @param y            logical top Y
     * @param width        logical width
     * @param height       logical height
     * @param surfaceColor opaque background surface ARGB color to fade into
     * @param fadeDown     {@code true} for top edge (opaque at top fading down); {@code false} for bottom edge
     */
    public static void drawScrollFadeMask(
        float x,
        float y,
        float width,
        float height,
        int surfaceColor,
        boolean fadeDown
    ) {
        int transparentSameRgb = surfaceColor & 0x00FFFFFF;
        if (fadeDown) {
            drawVerticalGradient(x, y, width, height, surfaceColor, transparentSameRgb);
        } else {
            drawVerticalGradient(x, y, width, height, transparentSameRgb, surfaceColor);
        }
    }

    /**
     * Draws a square tinted monochrome icon quad from {@code icon}.
     *
     * @param icon resource location of a white monochrome PNG
     * @param x    logical left X
     * @param y    logical top Y
     * @param size logical width and height
     * @param argb tint color in ARGB format
     */
    public static void drawTintedIcon(ResourceLocation icon, float x, float y, float size, int argb) {
        drawTintedIcon(icon, x, y, size, size, 0.0f, 0.0f, 1.0f, 1.0f, argb);
    }

    /**
     * Draws a tinted monochrome icon quad stretched to {@code width x height}.
     *
     * @param icon   resource location of a white monochrome PNG
     * @param x      logical left X
     * @param y      logical top Y
     * @param width  logical width
     * @param height logical height
     * @param argb   tint color in ARGB format
     */
    public static void drawTintedIcon(ResourceLocation icon, float x, float y, float width, float height, int argb) {
        drawTintedIcon(icon, x, y, width, height, 0.0f, 0.0f, 1.0f, 1.0f, argb);
    }

    /**
     * Draws a tinted monochrome icon quad with explicit UV coordinates.
     *
     * @param icon   resource location of a white monochrome PNG
     * @param x      logical left X
     * @param y      logical top Y
     * @param width  logical width
     * @param height logical height
     * @param u0     left UV [0..1]
     * @param v0     top UV [0..1]
     * @param u1     right UV [0..1]
     * @param v1     bottom UV [0..1]
     * @param argb   tint color in ARGB format
     */
    public static void drawTintedIcon(
        ResourceLocation icon,
        float x,
        float y,
        float width,
        float height,
        float u0,
        float v0,
        float u1,
        float v1,
        int argb
    ) {
        if (icon == null || width <= 0.0f || height <= 0.0f || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        TextureManager textureManager = mc.getTextureManager();
        if (textureManager == null) {
            return;
        }

        pushTexturedBlendState();
        try {
            textureManager.bindTexture(icon);
            int boundTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager.bindTexture(boundTex);

            // Force smooth linear filtering so 64x64 monochrome icons stay crisp at 14px/16px UI sizes.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            ContextCapabilities caps = getCapabilities();
            if (caps != null && caps.OpenGL12) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }

            drawTexturedColoredQuad(x, y, width, height, u0, v0, u1, v1, argb);
        } finally {
            popState();
        }
    }

    /**
     * Draws a tinted textured quad for an already-uploaded OpenGL texture ID without modifying its filter parameters.
     *
     * @param textureId OpenGL 2D texture ID
     * @param x         logical left X
     * @param y         logical top Y
     * @param width     logical width
     * @param height    logical height
     * @param u0        left UV [0..1]
     * @param v0        top UV [0..1]
     * @param u1        right UV [0..1]
     * @param v1        bottom UV [0..1]
     * @param argb      tint color in ARGB format
     */
    public static void drawTintedTexture(
        int textureId,
        float x,
        float y,
        float width,
        float height,
        float u0,
        float v0,
        float u1,
        float v1,
        int argb
    ) {
        if (textureId <= 0 || width <= 0.0f || height <= 0.0f || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        pushTexturedBlendState();
        try {
            GlStateManager.bindTexture(textureId);
            drawTexturedColoredQuad(x, y, width, height, u0, v0, u1, v1, argb);
        } finally {
            popState();
        }
    }

    /**
     * Multiplies the alpha channel of an ARGB color by {@code alphaMultiplier} in [0, 1] while preserving RGB.
     *
     * @param argb            source ARGB color
     * @param alphaMultiplier multiplier clamped to [0, 1]
     * @return adjusted ARGB color
     */
    public static int withAlpha(int argb, float alphaMultiplier) {
        float factor = alphaMultiplier <= 0.0f ? 0.0f : (alphaMultiplier >= 1.0f ? 1.0f : alphaMultiplier);
        int a = (argb >>> 24) & 0xFF;
        int scaledA = Math.round(a * factor);
        if (scaledA < 0) {
            scaledA = 0;
        } else if (scaledA > 255) {
            scaledA = 255;
        }
        return (scaledA << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Replaces the alpha channel of {@code argb} with {@code alphaByte} clamped to [0, 255].
     *
     * @param argb      source ARGB color
     * @param alphaByte new alpha channel [0..255]
     * @return adjusted ARGB color
     */
    public static int replaceAlpha(int argb, int alphaByte) {
        int clamped = alphaByte <= 0 ? 0 : (alphaByte >= 255 ? 255 : alphaByte);
        return (clamped << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Fixed-function fallback for rounded rectangles with 1px anti-aliased edge feathering and optional inner border.
     *
     * @param x               logical left X
     * @param y               logical top Y
     * @param width           logical width
     * @param height          logical height
     * @param topLeft         top-left corner radius
     * @param topRight        top-right corner radius
     * @param bottomRight     bottom-right corner radius
     * @param bottomLeft      bottom-left corner radius
     * @param fillColor       fill color in ARGB format
     * @param borderColor     border color in ARGB format
     * @param borderThickness inner border thickness in logical pixels
     */
    public static void drawRoundedRectFallback(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        int fillColor,
        int borderColor,
        float borderThickness
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        int fillAlpha = (fillColor >>> 24) & 0xFF;
        int borderAlpha = (borderColor >>> 24) & 0xFF;
        float safeBorder = Math.max(0.0f, borderThickness);
        if (fillAlpha == 0 && (borderAlpha == 0 || safeBorder <= 0.0f)) {
            return;
        }

        float maxRadius = Math.min(width, height) * 0.5f;
        float rTL = clampRadius(topLeft, maxRadius);
        float rTR = clampRadius(topRight, maxRadius);
        float rBR = clampRadius(bottomRight, maxRadius);
        float rBL = clampRadius(bottomLeft, maxRadius);

        pushUntexturedBlendState(true);
        try {
            if (fillAlpha != 0) {
                drawRoundedFillFallbackInternal(x, y, width, height, rTL, rTR, rBR, rBL, fillColor);
            }
            if (borderAlpha != 0 && safeBorder > 0.0f) {
                drawRoundedBorderFallbackInternal(
                    x, y, width, height,
                    rTL, rTR, rBR, rBL,
                    safeBorder, borderColor
                );
            }
        } finally {
            popState();
        }
    }

    /**
     * Fixed-function fallback for soft drop shadows using multi-band Gaussian alpha falloff.
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param width       logical width
     * @param height      logical height
     * @param topLeft     top-left corner radius
     * @param topRight    top-right corner radius
     * @param bottomRight bottom-right corner radius
     * @param bottomLeft  bottom-left corner radius
     * @param offsetX     horizontal offset in logical pixels
     * @param offsetY     vertical offset in logical pixels
     * @param blurRadius  blur radius in logical pixels
     * @param shadowColor shadow color in ARGB format
     */
    public static void drawShadowFallback(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        float offsetX,
        float offsetY,
        float blurRadius,
        int shadowColor
    ) {
        int shadowAlphaByte = (shadowColor >>> 24) & 0xFF;
        if (width <= 0.0f || height <= 0.0f || blurRadius <= 0.0f || shadowAlphaByte == 0) {
            return;
        }

        float maxRadius = Math.min(width, height) * 0.5f;
        float rTL = clampRadius(topLeft, maxRadius);
        float rTR = clampRadius(topRight, maxRadius);
        float rBR = clampRadius(bottomRight, maxRadius);
        float rBL = clampRadius(bottomLeft, maxRadius);

        float sx = x + offsetX;
        float sy = y + offsetY;
        float r = ((shadowColor >>> 16) & 0xFF) / 255.0f;
        float g = ((shadowColor >>> 8) & 0xFF) / 255.0f;
        float b = (shadowColor & 0xFF) / 255.0f;
        float baseA = shadowAlphaByte / 255.0f;

        float maxRange = blurRadius * 1.4f;
        float innerOffset = -Math.min(blurRadius * 0.5f, Math.min(width, height) * 0.45f);

        pushUntexturedBlendState(true);
        try {
            float[] prevX = RING_X_A;
            float[] prevY = RING_Y_A;
            float[] currX = RING_X_B;
            float[] currY = RING_Y_B;

            int count = emitRoundedRing(
                sx, sy, width, height,
                rTL, rTR, rBR, rBL,
                innerOffset, true,
                prevX, prevY
            );
            float prevAlpha = baseA * gaussianShadowFalloff(innerOffset, blurRadius, maxRange);
            if (prevAlpha > 0.001f) {
                drawTriangleFan(prevX, prevY, count, sx + width * 0.5f, sy + height * 0.5f, r, g, b, prevAlpha);
            }

            int totalBands = SHADOW_INWARD_STEPS + SHADOW_OUTWARD_STEPS;
            for (int step = 1; step <= totalBands; step++) {
                float dist;
                if (step <= SHADOW_INWARD_STEPS) {
                    float t = (float) step / (float) SHADOW_INWARD_STEPS;
                    dist = innerOffset * (1.0f - t);
                } else {
                    float t = (float) (step - SHADOW_INWARD_STEPS) / (float) SHADOW_OUTWARD_STEPS;
                    dist = (float) Math.pow(t, 1.35D) * maxRange;
                }

                emitRoundedRing(sx, sy, width, height, rTL, rTR, rBR, rBL, dist, true, currX, currY);
                float currAlpha = (step == totalBands)
                    ? 0.0f
                    : baseA * gaussianShadowFalloff(dist, blurRadius, maxRange);

                drawQuadStripLoop(
                    prevX, prevY, r, g, b, prevAlpha,
                    currX, currY, r, g, b, currAlpha,
                    count
                );

                float[] tmpX = prevX;
                float[] tmpY = prevY;
                prevX = currX;
                prevY = currY;
                currX = tmpX;
                currY = tmpY;
                prevAlpha = currAlpha;
            }
        } finally {
            popState();
        }
    }

    /**
     * Begins a batched UI rendering pass, capturing outer OpenGL state once and avoiding
     * per-widget synchronous {@code glGet*} driver stalls until {@link #endUiBatch()}.
     */
    public static void beginUiBatch() {
        if (uiBatchDepth == 0) {
            pushUntexturedBlendState(true);
        }
        uiBatchDepth++;
    }

    /**
     * Ends a batched UI rendering pass started with {@link #beginUiBatch()} and restores outer OpenGL state.
     */
    public static void endUiBatch() {
        if (uiBatchDepth <= 0) {
            return;
        }
        uiBatchDepth--;
        if (uiBatchDepth == 0) {
            uiBatchInnerDepth = 0;
            popState();
        }
    }

    public static void pushUntexturedBlendState(boolean smoothShade) {
        if (uiBatchDepth > 0) {
            uiBatchInnerDepth++;
            GlStateManager.disableTexture2D();
            GlStateManager.shadeModel(smoothShade ? GL11.GL_SMOOTH : GL11.GL_FLAT);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }
        StateBackup state = acquireStateBackup();
        captureCommonState(state);
        state.hasTextureBackup = false;

        syncBlend(true);
        syncTexture2D(false);
        syncAlphaTest(false);
        syncCullFace(false);
        syncDepthTest(false);
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
        );
        GlStateManager.shadeModel(smoothShade ? GL11.GL_SMOOTH : GL11.GL_FLAT);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    static void pushTexturedBlendState() {
        if (uiBatchDepth > 0) {
            uiBatchInnerDepth++;
            GlStateManager.enableTexture2D();
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }
        StateBackup state = acquireStateBackup();
        captureCommonState(state);
        state.hasTextureBackup = true;
        state.prevTexture2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        if (state.hasProgramBackup && state.prevProgram != 0) {
            GL20.glUseProgram(0);
        }
        syncBlend(true);
        syncTexture2D(true);
        syncAlphaTest(false);
        syncCullFace(false);
        syncDepthTest(false);
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
        );
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void popState() {
        if (uiBatchDepth > 0 && uiBatchInnerDepth > 0) {
            uiBatchInnerDepth--;
            ContextCapabilities caps = getCapabilities();
            if (caps != null && caps.OpenGL20) {
                GL20.glUseProgram(0);
            }
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }
        if (stateDepth <= 0) {
            return;
        }
        StateBackup state = STATE_STACK[--stateDepth];

        if (state.hasProgramBackup && GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != state.prevProgram) {
            GL20.glUseProgram(state.prevProgram);
        }
        if (state.hasTextureBackup) {
            GlStateManager.bindTexture(state.prevTexture2D);
            if (GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != state.prevTexture2D) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, state.prevTexture2D);
            }
        }

        GlStateManager.shadeModel(state.prevShadeModel);
        GlStateManager.tryBlendFuncSeparate(
            state.prevBlendSrc, state.prevBlendDst,
            state.prevBlendSrcAlpha, state.prevBlendDstAlpha
        );
        syncCullFace(state.wasCull);
        syncDepthTest(state.wasDepth);
        syncAlphaTest(state.wasAlpha);
        syncTexture2D(state.wasTexture2D);
        syncBlend(state.wasBlend);

        GlStateManager.resetColor();
        GlStateManager.color(state.prevR, state.prevG, state.prevB, state.prevA);
    }

    private static StateBackup acquireStateBackup() {
        if (stateDepth < STATE_STACK_CAPACITY) {
            return STATE_STACK[stateDepth++];
        }
        // Fallback only if nested >8 deep; normal rendering stays strictly zero-allocation.
        stateDepth++;
        return new StateBackup();
    }

    private static void captureCommonState(StateBackup state) {
        ContextCapabilities caps = getCapabilities();
        state.hasProgramBackup = caps != null && caps.OpenGL20;
        state.prevProgram = state.hasProgramBackup ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        state.wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        state.wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        state.wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        state.wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        state.wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        state.prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        state.prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        state.prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        if (caps != null && caps.OpenGL14) {
            state.prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            state.prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        } else if (caps != null && caps.GL_EXT_blend_func_separate) {
            state.prevBlendSrcAlpha = GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_SRC_ALPHA_EXT);
            state.prevBlendDstAlpha = GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_DST_ALPHA_EXT);
        } else {
            state.prevBlendSrcAlpha = state.prevBlendSrc;
            state.prevBlendDstAlpha = state.prevBlendDst;
        }

        COLOR_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR_BUFFER);
        state.prevR = COLOR_BUFFER.get(0);
        state.prevG = COLOR_BUFFER.get(1);
        state.prevB = COLOR_BUFFER.get(2);
        state.prevA = COLOR_BUFFER.get(3);
    }

    private static void syncBlend(boolean enable) {
        if (enable) {
            GlStateManager.enableBlend();
            if (!GL11.glIsEnabled(GL11.GL_BLEND)) {
                GlStateManager.disableBlend();
                GlStateManager.enableBlend();
            }
        } else {
            GlStateManager.disableBlend();
            if (GL11.glIsEnabled(GL11.GL_BLEND)) {
                GlStateManager.enableBlend();
                GlStateManager.disableBlend();
            }
        }
    }

    private static void syncTexture2D(boolean enable) {
        if (enable) {
            GlStateManager.enableTexture2D();
            if (!GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) {
                GlStateManager.disableTexture2D();
                GlStateManager.enableTexture2D();
            }
        } else {
            GlStateManager.disableTexture2D();
            if (GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) {
                GlStateManager.enableTexture2D();
                GlStateManager.disableTexture2D();
            }
        }
    }

    private static void syncAlphaTest(boolean enable) {
        if (enable) {
            GlStateManager.enableAlpha();
            if (!GL11.glIsEnabled(GL11.GL_ALPHA_TEST)) {
                GlStateManager.disableAlpha();
                GlStateManager.enableAlpha();
            }
        } else {
            GlStateManager.disableAlpha();
            if (GL11.glIsEnabled(GL11.GL_ALPHA_TEST)) {
                GlStateManager.enableAlpha();
                GlStateManager.disableAlpha();
            }
        }
    }

    private static void syncCullFace(boolean enable) {
        if (enable) {
            GlStateManager.enableCull();
            if (!GL11.glIsEnabled(GL11.GL_CULL_FACE)) {
                GlStateManager.disableCull();
                GlStateManager.enableCull();
            }
        } else {
            GlStateManager.disableCull();
            if (GL11.glIsEnabled(GL11.GL_CULL_FACE)) {
                GlStateManager.enableCull();
                GlStateManager.disableCull();
            }
        }
    }

    private static void syncDepthTest(boolean enable) {
        if (enable) {
            GlStateManager.enableDepth();
            if (!GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) {
                GlStateManager.disableDepth();
                GlStateManager.enableDepth();
            }
        } else {
            GlStateManager.disableDepth();
            if (GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) {
                GlStateManager.enableDepth();
                GlStateManager.disableDepth();
            }
        }
    }

    private static void drawRoundedFillFallbackInternal(
        float x,
        float y,
        float width,
        float height,
        float rTL,
        float rTR,
        float rBR,
        float rBL,
        int fillColor
    ) {
        if (rTL <= 0.0f && rTR <= 0.0f && rBR <= 0.0f && rBL <= 0.0f && (width < 2.0f || height < 2.0f)) {
            drawQuadImmediate(x, y, width, height, fillColor);
            return;
        }

        float a = ((fillColor >>> 24) & 0xFF) / 255.0f;
        float r = ((fillColor >>> 16) & 0xFF) / 255.0f;
        float g = ((fillColor >>> 8) & 0xFF) / 255.0f;
        float b = (fillColor & 0xFF) / 255.0f;

        float inset = Math.min(0.5f, Math.min(width, height) * 0.25f);
        int count = emitRoundedRing(x, y, width, height, rTL, rTR, rBR, rBL, -inset, false, RING_X_A, RING_Y_A);
        emitRoundedRing(x, y, width, height, rTL, rTR, rBR, rBL, 0.5f, false, RING_X_B, RING_Y_B);

        drawTriangleFan(RING_X_A, RING_Y_A, count, x + width * 0.5f, y + height * 0.5f, r, g, b, a);
        drawQuadStripLoop(RING_X_A, RING_Y_A, r, g, b, a, RING_X_B, RING_Y_B, r, g, b, 0.0f, count);
    }

    private static void drawRoundedBorderFallbackInternal(
        float x,
        float y,
        float width,
        float height,
        float rTL,
        float rTR,
        float rBR,
        float rBL,
        float borderThickness,
        int borderColor
    ) {
        float a = ((borderColor >>> 24) & 0xFF) / 255.0f;
        float r = ((borderColor >>> 16) & 0xFF) / 255.0f;
        float g = ((borderColor >>> 8) & 0xFF) / 255.0f;
        float b = (borderColor & 0xFF) / 255.0f;

        float t = Math.min(borderThickness, Math.min(width, height) * 0.5f);
        float halfAA = Math.min(0.45f, t * 0.4f);

        if (t > 2.0f * halfAA + 0.05f) {
            float innerSolid = -(t - halfAA * 0.5f);
            float outerSolid = -(halfAA * 0.5f);
            float innerAA = -(t + 0.5f);

            int count = emitRoundedRing(
                x, y, width, height, rTL, rTR, rBR, rBL, innerAA, false, RING_X_A, RING_Y_A
            );
            emitRoundedRing(x, y, width, height, rTL, rTR, rBR, rBL, innerSolid, false, RING_X_B, RING_Y_B);
            drawQuadStripLoop(RING_X_A, RING_Y_A, r, g, b, 0.0f, RING_X_B, RING_Y_B, r, g, b, a, count);

            emitRoundedRing(x, y, width, height, rTL, rTR, rBR, rBL, outerSolid, false, RING_X_A, RING_Y_A);
            drawQuadStripLoop(RING_X_B, RING_Y_B, r, g, b, a, RING_X_A, RING_Y_A, r, g, b, a, count);

            emitRoundedRing(x, y, width, height, rTL, rTR, rBR, rBL, 0.5f, false, RING_X_B, RING_Y_B);
            drawQuadStripLoop(RING_X_A, RING_Y_A, r, g, b, a, RING_X_B, RING_Y_B, r, g, b, 0.0f, count);
        } else {
            float mid = -(t * 0.5f);
            float innerAA = -(t + 0.5f);
            int count = emitRoundedRing(
                x, y, width, height, rTL, rTR, rBR, rBL, innerAA, false, RING_X_A, RING_Y_A
            );
            emitRoundedRing(x, y, width, height, rTL, rTR, rBR, rBL, mid, false, RING_X_B, RING_Y_B);
            drawQuadStripLoop(RING_X_A, RING_Y_A, r, g, b, 0.0f, RING_X_B, RING_Y_B, r, g, b, a, count);

            emitRoundedRing(x, y, width, height, rTL, rTR, rBR, rBL, 0.5f, false, RING_X_A, RING_Y_A);
            drawQuadStripLoop(RING_X_B, RING_Y_B, r, g, b, a, RING_X_A, RING_Y_A, r, g, b, 0.0f, count);
        }
    }

    private static int emitRoundedRing(
        float x,
        float y,
        float width,
        float height,
        float baseTL,
        float baseTR,
        float baseBR,
        float baseBL,
        float offset,
        boolean roundSharpCorners,
        float[] outX,
        float[] outY
    ) {
        float rw = Math.max(0.0f, width + 2.0f * offset);
        float rh = Math.max(0.0f, height + 2.0f * offset);
        float rx = x + (width - rw) * 0.5f;
        float ry = y + (height - rh) * 0.5f;
        float maxR = Math.min(rw, rh) * 0.5f;

        float rTL = effectiveRadius(baseTL, offset, maxR, roundSharpCorners);
        float rTR = effectiveRadius(baseTR, offset, maxR, roundSharpCorners);
        float rBR = effectiveRadius(baseBR, offset, maxR, roundSharpCorners);
        float rBL = effectiveRadius(baseBL, offset, maxR, roundSharpCorners);

        int idx = 0;
        idx = emitCorner(rx + rTL, ry + rTL, rTL, baseTL > 0.0f || roundSharpCorners, 0, outX, outY, idx);
        idx = emitCorner(rx + rw - rTR, ry + rTR, rTR, baseTR > 0.0f || roundSharpCorners, 1, outX, outY, idx);
        idx = emitCorner(rx + rw - rBR, ry + rh - rBR, rBR, baseBR > 0.0f || roundSharpCorners, 2, outX, outY, idx);
        idx = emitCorner(rx + rBL, ry + rh - rBL, rBL, baseBL > 0.0f || roundSharpCorners, 3, outX, outY, idx);
        return idx;
    }

    private static float effectiveRadius(float baseRadius, float offset, float maxR, boolean roundSharpCorners) {
        float raw;
        if (baseRadius > 0.0f || roundSharpCorners) {
            raw = baseRadius + offset;
        } else {
            raw = 0.0f;
        }
        return clampRadius(raw, maxR);
    }

    private static int emitCorner(
        float cx,
        float cy,
        float r,
        boolean useArcCount,
        int quadrant,
        float[] outX,
        float[] outY,
        int startIdx
    ) {
        if (!useArcCount) {
            outX[startIdx] = cx;
            outY[startIdx] = cy;
            return startIdx + 1;
        }
        float cosX = QUAD_COS_X[quadrant];
        float sinX = QUAD_SIN_X[quadrant];
        float cosY = QUAD_COS_Y[quadrant];
        float sinY = QUAD_SIN_Y[quadrant];
        int idx = startIdx;
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            float c = COS_TABLE[i];
            float s = SIN_TABLE[i];
            outX[idx] = cx + r * (cosX * c + sinX * s);
            outY[idx] = cy + r * (cosY * c + sinY * s);
            idx++;
        }
        return idx;
    }

    private static void drawTriangleFan(
        float[] ringX,
        float[] ringY,
        int count,
        float centerX,
        float centerY,
        float r,
        float g,
        float b,
        float a
    ) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        worldRenderer.pos(centerX, centerY, 0.0D).color(r, g, b, a).endVertex();
        // Reverse order (CCW in Y-down screen space) keeps front-face winding consistent.
        for (int i = count - 1; i >= 0; i--) {
            worldRenderer.pos(ringX[i], ringY[i], 0.0D).color(r, g, b, a).endVertex();
        }
        worldRenderer.pos(ringX[count - 1], ringY[count - 1], 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    private static void drawQuadStripLoop(
        float[] innerX,
        float[] innerY,
        float rIn,
        float gIn,
        float bIn,
        float aIn,
        float[] outerX,
        float[] outerY,
        float rOut,
        float gOut,
        float bOut,
        float aOut,
        int count
    ) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            worldRenderer.pos(outerX[i], outerY[i], 0.0D).color(rOut, gOut, bOut, aOut).endVertex();
            worldRenderer.pos(innerX[i], innerY[i], 0.0D).color(rIn, gIn, bIn, aIn).endVertex();
        }
        worldRenderer.pos(outerX[0], outerY[0], 0.0D).color(rOut, gOut, bOut, aOut).endVertex();
        worldRenderer.pos(innerX[0], innerY[0], 0.0D).color(rIn, gIn, bIn, aIn).endVertex();
        tessellator.draw();
    }

    private static void drawQuadImmediate(float x, float y, float width, float height, int argb) {
        float x2 = x + width;
        float y2 = y + height;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        putColoredVertex(worldRenderer, x, y2, argb);
        putColoredVertex(worldRenderer, x2, y2, argb);
        putColoredVertex(worldRenderer, x2, y, argb);
        putColoredVertex(worldRenderer, x, y, argb);
        tessellator.draw();
    }

    private static void drawTexturedColoredQuad(
        float x,
        float y,
        float width,
        float height,
        float u0,
        float v0,
        float u1,
        float v1,
        int argb
    ) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        float x2 = x + width;
        float y2 = y + height;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        worldRenderer.pos(x, y2, 0.0D).tex(u0, v1).color(r, g, b, a).endVertex();
        worldRenderer.pos(x2, y2, 0.0D).tex(u1, v1).color(r, g, b, a).endVertex();
        worldRenderer.pos(x2, y, 0.0D).tex(u1, v0).color(r, g, b, a).endVertex();
        worldRenderer.pos(x, y, 0.0D).tex(u0, v0).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    private static void putColoredVertex(WorldRenderer worldRenderer, float x, float y, int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        worldRenderer.pos(x, y, 0.0D).color(r, g, b, a).endVertex();
    }

    private static float gaussianShadowFalloff(float dist, float blurRadius, float maxRange) {
        float normDist = (dist * 1.41421356f) / Math.max(blurRadius, 0.5f);
        float shadow = 0.5f * (1.0f - erfApprox(normDist));
        float edgeStart = maxRange * 0.75f;
        if (dist > edgeStart) {
            float t = (dist - edgeStart) / Math.max(0.0001f, maxRange - edgeStart);
            if (t >= 1.0f) {
                return 0.0f;
            }
            float smooth = t * t * (3.0f - 2.0f * t);
            shadow *= (1.0f - smooth);
        }
        return shadow;
    }

    private static float erfApprox(float x) {
        float s = x < 0.0f ? -1.0f : (x > 0.0f ? 1.0f : 0.0f);
        float a = Math.abs(x);
        float t = 1.0f + (0.278393f + (0.230389f + 0.078108f * (a * a)) * a) * a;
        t *= t;
        return s - s / (t * t);
    }

    private static float clampRadius(float radius, float maxRadius) {
        if (radius <= 0.0f) {
            return 0.0f;
        }
        if (radius >= maxRadius) {
            return maxRadius;
        }
        return radius;
    }

    private static ContextCapabilities getCapabilities() {
        if (!Display.isCreated()) {
            return null;
        }
        return GLContext.getCapabilities();
    }

    private static final class StateBackup {
        boolean hasProgramBackup;
        int prevProgram;
        boolean hasTextureBackup;
        int prevTexture2D;
        boolean wasBlend;
        boolean wasTexture2D;
        boolean wasAlpha;
        boolean wasCull;
        boolean wasDepth;
        int prevShadeModel;
        int prevBlendSrc;
        int prevBlendDst;
        int prevBlendSrcAlpha;
        int prevBlendDstAlpha;
        float prevR;
        float prevG;
        float prevB;
        float prevA;
    }
}
