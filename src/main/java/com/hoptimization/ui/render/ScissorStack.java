package com.hoptimization.ui.render;

import com.hoptimization.HoptimizationMod;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Nested scissor clipping stack converting logical coordinates into OpenGL framebuffer scissor rectangles.
 * All hot-path state uses pre-allocated primitive arrays and direct NIO buffers for zero per-frame allocations.
 */
public final class ScissorStack {
    private static final int INITIAL_CAPACITY = 32;

    private static final IntBuffer INT_BUFFER = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer MODELVIEW_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION_BUFFER = BufferUtils.createFloatBuffer(16);

    private static int[] stackX = new int[INITIAL_CAPACITY];
    private static int[] stackY = new int[INITIAL_CAPACITY];
    private static int[] stackW = new int[INITIAL_CAPACITY];
    private static int[] stackH = new int[INITIAL_CAPACITY];
    private static int depth = 0;

    private static boolean savedOuterScissor = false;
    private static int savedOuterX = 0;
    private static int savedOuterY = 0;
    private static int savedOuterW = 0;
    private static int savedOuterH = 0;
    private static boolean underflowWarned = false;
    private static float projectedX = 0.0f;
    private static float projectedY = 0.0f;

    private ScissorStack() {
    }

    /**
     * Pushes a logical-pixel scissor rectangle using the active {@link UiScale#getScale()}.
     *
     * @param x      logical left X
     * @param y      logical top Y
     * @param width  logical width
     * @param height logical height
     */
    public static void push(float x, float y, float width, float height) {
        push(x, y, width, height, UiScale.getScale());
    }

    /**
     * Pushes a logical-pixel scissor rectangle converted with {@code uiScale} and {@code Display.getHeight()} Y-flip.
     *
     * @param x       logical left X
     * @param y       logical top Y
     * @param width   logical width
     * @param height  logical height
     * @param uiScale scale factor mapping logical units to physical pixels
     */
    public static void push(float x, float y, float width, float height, float uiScale) {
        float safeScale = Math.max(0.0001f, uiScale);
        float safeW = Math.max(0.0f, width);
        float safeH = Math.max(0.0f, height);

        int physLeft = Math.round(x * safeScale);
        int physRight = Math.round((x + safeW) * safeScale);
        int physTop = Math.round(y * safeScale);
        int physBottom = Math.round((y + safeH) * safeScale);

        int displayHeight = Display.isCreated() ? Math.max(1, Display.getHeight()) : UiScale.getDisplayHeight();
        int glX = physLeft;
        int glY = displayHeight - physBottom;
        int glW = Math.max(0, physRight - physLeft);
        int glH = Math.max(0, physBottom - physTop);

        pushPhysical(glX, glY, glW, glH);
    }

    /**
     * Pushes a scissor rectangle transformed by the current OpenGL modelview and projection matrices.
     * Useful when an active matrix scale/translation (such as zoom open animations or previews) is applied.
     *
     * @param x      local left X
     * @param y      local top Y
     * @param width  local width
     * @param height local height
     */
    public static void pushTransformed(float x, float y, float width, float height) {
        float safeW = Math.max(0.0f, width);
        float safeH = Math.max(0.0f, height);

        INT_BUFFER.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, INT_BUFFER);
        int vpX = INT_BUFFER.get(0);
        int vpY = INT_BUFFER.get(1);
        int vpW = Math.max(1, INT_BUFFER.get(2));
        int vpH = Math.max(1, INT_BUFFER.get(3));

        MODELVIEW_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW_BUFFER);
        PROJECTION_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION_BUFFER);

        projectPoint(x, y, vpX, vpY, vpW, vpH);
        float ax = projectedX;
        float ay = projectedY;
        projectPoint(x + safeW, y + safeH, vpX, vpY, vpW, vpH);
        float bx = projectedX;
        float by = projectedY;

        int left = Math.round(Math.min(ax, bx));
        int right = Math.round(Math.max(ax, bx));
        int bottom = Math.round(Math.min(ay, by));
        int top = Math.round(Math.max(ay, by));

        pushPhysical(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
    }

    /**
     * Pushes a raw OpenGL physical-pixel scissor rectangle (bottom-left origin) and intersects it with the active box.
     *
     * @param glX      physical X from left edge
     * @param glY      physical Y from bottom edge
     * @param glWidth  physical width
     * @param glHeight physical height
     */
    public static void pushPhysical(int glX, int glY, int glWidth, int glHeight) {
        int clampedW = Math.max(0, glWidth);
        int clampedH = Math.max(0, glHeight);

        int ix = glX;
        int iy = glY;
        int iw = clampedW;
        int ih = clampedH;

        if (depth == 0) {
            savedOuterScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (savedOuterScissor) {
                INT_BUFFER.clear();
                GL11.glGetInteger(GL11.GL_SCISSOR_BOX, INT_BUFFER);
                savedOuterX = INT_BUFFER.get(0);
                savedOuterY = INT_BUFFER.get(1);
                savedOuterW = Math.max(0, INT_BUFFER.get(2));
                savedOuterH = Math.max(0, INT_BUFFER.get(3));

                int x1 = Math.max(ix, savedOuterX);
                int y1 = Math.max(iy, savedOuterY);
                int x2 = Math.min(ix + iw, savedOuterX + savedOuterW);
                int y2 = Math.min(iy + ih, savedOuterY + savedOuterH);
                ix = x1;
                iy = y1;
                iw = Math.max(0, x2 - x1);
                ih = Math.max(0, y2 - y1);
            }
        } else {
            int px = stackX[depth - 1];
            int py = stackY[depth - 1];
            int pw = stackW[depth - 1];
            int ph = stackH[depth - 1];

            int x1 = Math.max(ix, px);
            int y1 = Math.max(iy, py);
            int x2 = Math.min(ix + iw, px + pw);
            int y2 = Math.min(iy + ih, py + ph);
            ix = x1;
            iy = y1;
            iw = Math.max(0, x2 - x1);
            ih = Math.max(0, y2 - y1);
        }

        ensureCapacity(depth + 1);
        stackX[depth] = ix;
        stackY[depth] = iy;
        stackW[depth] = iw;
        stackH[depth] = ih;
        depth++;

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(ix, iy, iw, ih);
    }

    /**
     * Pops the current scissor rectangle and restores the enclosing scissor box or pre-stack GL state.
     */
    public static void pop() {
        if (depth <= 0) {
            if (!underflowWarned) {
                underflowWarned = true;
                HoptimizationMod.LOGGER.warn("ScissorStack.pop() called on an empty stack.");
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        depth--;
        if (depth == 0) {
            restoreOuterScissor();
        } else {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(stackX[depth - 1], stackY[depth - 1], stackW[depth - 1], stackH[depth - 1]);
        }
    }

    /**
     * Clears any remaining entries on the stack and restores the pre-stack scissor state.
     */
    public static void clear() {
        if (depth > 0) {
            depth = 0;
            restoreOuterScissor();
        }
    }

    /**
     * Returns the current nesting depth of the scissor stack.
     *
     * @return current stack depth
     */
    public static int getDepth() {
        return depth;
    }

    /**
     * Returns the active intersected physical scissor X, or 0 if empty.
     *
     * @return physical scissor X
     */
    public static int getTopX() {
        return depth > 0 ? stackX[depth - 1] : 0;
    }

    /**
     * Returns the active intersected physical scissor Y (bottom-up), or 0 if empty.
     *
     * @return physical scissor Y
     */
    public static int getTopY() {
        return depth > 0 ? stackY[depth - 1] : 0;
    }

    /**
     * Returns the active intersected physical scissor width, or 0 if empty.
     *
     * @return physical scissor width
     */
    public static int getTopWidth() {
        return depth > 0 ? stackW[depth - 1] : 0;
    }

    /**
     * Returns the active intersected physical scissor height, or 0 if empty.
     *
     * @return physical scissor height
     */
    public static int getTopHeight() {
        return depth > 0 ? stackH[depth - 1] : 0;
    }

    private static void restoreOuterScissor() {
        if (savedOuterScissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(savedOuterX, savedOuterY, savedOuterW, savedOuterH);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        savedOuterScissor = false;
    }

    private static void ensureCapacity(int required) {
        if (required <= stackX.length) {
            return;
        }
        int newCapacity = stackX.length * 2;
        stackX = Arrays.copyOf(stackX, newCapacity);
        stackY = Arrays.copyOf(stackY, newCapacity);
        stackW = Arrays.copyOf(stackW, newCapacity);
        stackH = Arrays.copyOf(stackH, newCapacity);
    }

    private static void projectPoint(float x, float y, int vpX, int vpY, int vpW, int vpH) {
        float mvX = MODELVIEW_BUFFER.get(0) * x + MODELVIEW_BUFFER.get(4) * y + MODELVIEW_BUFFER.get(12);
        float mvY = MODELVIEW_BUFFER.get(1) * x + MODELVIEW_BUFFER.get(5) * y + MODELVIEW_BUFFER.get(13);
        float mvZ = MODELVIEW_BUFFER.get(2) * x + MODELVIEW_BUFFER.get(6) * y + MODELVIEW_BUFFER.get(14);
        float mvW = MODELVIEW_BUFFER.get(3) * x + MODELVIEW_BUFFER.get(7) * y + MODELVIEW_BUFFER.get(15);

        float clipX = PROJECTION_BUFFER.get(0) * mvX + PROJECTION_BUFFER.get(4) * mvY
            + PROJECTION_BUFFER.get(8) * mvZ + PROJECTION_BUFFER.get(12) * mvW;
        float clipY = PROJECTION_BUFFER.get(1) * mvX + PROJECTION_BUFFER.get(5) * mvY
            + PROJECTION_BUFFER.get(9) * mvZ + PROJECTION_BUFFER.get(13) * mvW;
        float clipW = PROJECTION_BUFFER.get(3) * mvX + PROJECTION_BUFFER.get(7) * mvY
            + PROJECTION_BUFFER.get(11) * mvZ + PROJECTION_BUFFER.get(15) * mvW;
        float invW = Math.abs(clipW) > 1e-6f ? (1.0f / clipW) : 1.0f;
        projectedX = vpX + ((clipX * invW) * 0.5f + 0.5f) * vpW;
        projectedY = vpY + ((clipY * invW) * 0.5f + 0.5f) * vpH;
    }
}
