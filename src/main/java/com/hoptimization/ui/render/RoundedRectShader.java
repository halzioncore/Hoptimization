package com.hoptimization.ui.render;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * GLSL 120 signed-distance-field renderer for anti-aliased rounded rectangles, borders, and soft shadows.
 * Automatically falls back to {@link RenderUtil} fixed-function geometry when OpenGL 2.0/GLSL 120 is unavailable,
 * compilation fails, or {@link #setForceFallback(boolean)} is enabled, logging a warning at most once.
 */
public final class RoundedRectShader {
    private static final String VERT_RESOURCE = "shader/rounded_rect.vsh";
    private static final String RECT_FRAG_RESOURCE = "shader/rounded_rect.fsh";
    private static final String SHADOW_FRAG_RESOURCE = "shader/rounded_shadow.fsh";
    private static final String CLASSPATH_PREFIX = "/assets/" + HoptimizationMod.MODID + "/";

    private static volatile boolean forceFallback = false;
    private static boolean initialized = false;
    private static boolean shaderFailed = false;
    private static boolean fallbackWarnLogged = false;

    private static int rectProgram = 0;
    private static int rectViewportLoc = -1;
    private static int rectHalfSizeLoc = -1;
    private static int rectRadiiLoc = -1;
    private static int rectFillColorLoc = -1;
    private static int rectBorderColorLoc = -1;
    private static int rectBorderThicknessLoc = -1;

    private static int shadowProgram = 0;
    private static int shadowViewportLoc = -1;
    private static int shadowHalfSizeLoc = -1;
    private static int shadowRadiiLoc = -1;
    private static int shadowColorLoc = -1;
    private static int shadowBlurLoc = -1;

    private RoundedRectShader() {
    }

    /**
     * Enables or disables forced fallback to the fixed-function renderer (primarily for testing and diagnostics).
     *
     * @param force {@code true} to bypass the shader path
     */
    public static void setForceFallback(boolean force) {
        forceFallback = force;
        if (force && !fallbackWarnLogged) {
            fallbackWarnLogged = true;
            HoptimizationMod.LOGGER.warn("RoundedRectShader forced to fixed-function fallback mode.");
        }
    }

    /**
     * Returns whether forced fallback mode is active.
     *
     * @return {@code true} when forced fallback is enabled
     */
    public static boolean isForceFallback() {
        return forceFallback;
    }

    /**
     * Returns whether the GLSL 120 shader path is currently active (not forced off and not failed).
     *
     * @return {@code true} if shaders are active and usable
     */
    public static boolean isActive() {
        return ensureInitialized();
    }

    /**
     * Draws a filled rounded rectangle with uniform corner radius, using fallback if shaders are unavailable.
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
     * Draws a rounded rectangle with four independent corner radii, fill color, and inner border.
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
        if (!tryDrawRoundedRect(
            x, y, width, height,
            topLeft, topRight, bottomRight, bottomLeft,
            fillColor, borderColor, borderThickness
        )) {
            RenderUtil.drawRoundedRectFallback(
                x, y, width, height,
                topLeft, topRight, bottomRight, bottomLeft,
                fillColor, borderColor, borderThickness
            );
        }
    }

    /**
     * Draws a rounded rectangle along with an optional soft drop shadow.
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
        float borderThickness,
        float shadowOffsetX,
        float shadowOffsetY,
        float shadowBlur,
        int shadowColor
    ) {
        if (shadowBlur > 0.0f && (shadowColor >>> 24) != 0) {
            drawShadow(
                x, y, width, height,
                topLeft, topRight, bottomRight, bottomLeft,
                shadowOffsetX, shadowOffsetY, shadowBlur, shadowColor
            );
        }
        drawRoundedRect(
            x, y, width, height,
            topLeft, topRight, bottomRight, bottomLeft,
            fillColor, borderColor, borderThickness
        );
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
        drawShadow(x, y, width, height, radius, radius, radius, radius, 0.0f, 0.0f, blurRadius, shadowColor);
    }

    /**
     * Draws a soft drop shadow for a rounded rectangle with four corner radii and pixel offset.
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
        if (!tryDrawShadow(
            x, y, width, height,
            topLeft, topRight, bottomRight, bottomLeft,
            offsetX, offsetY, blurRadius, shadowColor
        )) {
            RenderUtil.drawShadowFallback(
                x, y, width, height,
                topLeft, topRight, bottomRight, bottomLeft,
                offsetX, offsetY, blurRadius, shadowColor
            );
        }
    }

    /**
     * Releases any compiled OpenGL shader programs.
     */
    public static void destroy() {
        destroyPrograms();
        initialized = false;
    }

    static boolean tryDrawRoundedRect(
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
            return true;
        }
        int fillAlpha = (fillColor >>> 24) & 0xFF;
        int borderAlpha = (borderColor >>> 24) & 0xFF;
        float safeBorder = Math.max(0.0f, borderThickness);
        if (fillAlpha == 0 && (borderAlpha == 0 || safeBorder <= 0.0f)) {
            return true;
        }
        if (!ensureInitialized()) {
            return false;
        }

        float maxRadius = Math.min(width, height) * 0.5f;
        float rTL = clampRadius(topLeft, maxRadius);
        float rTR = clampRadius(topRight, maxRadius);
        float rBR = clampRadius(bottomRight, maxRadius);
        float rBL = clampRadius(bottomLeft, maxRadius);

        float halfW = width * 0.5f;
        float halfH = height * 0.5f;
        // Expand by 2 logical units so the 0.5px AA fringe is never clipped by the quad boundary.
        float expand = 2.0f;

        RenderUtil.pushUntexturedBlendState(false);
        try {
            GL20.glUseProgram(rectProgram);

            GL20.glUniform2f(rectViewportLoc, UiScale.getDisplayWidth(), UiScale.getDisplayHeight());
            GL20.glUniform2f(rectHalfSizeLoc, halfW, halfH);
            GL20.glUniform4f(rectRadiiLoc, rTL, rTR, rBR, rBL);
            setColorUniform(rectFillColorLoc, fillColor);
            setColorUniform(rectBorderColorLoc, borderColor);
            GL20.glUniform1f(rectBorderThicknessLoc, safeBorder);

            drawLocalCoordQuad(x, y, width, height, halfW, halfH, expand);
        } finally {
            RenderUtil.popState();
        }
        return true;
    }

    static boolean tryDrawShadow(
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
        if (width <= 0.0f || height <= 0.0f || blurRadius <= 0.0f || ((shadowColor >>> 24) & 0xFF) == 0) {
            return true;
        }
        if (!ensureInitialized()) {
            return false;
        }

        float maxRadius = Math.min(width, height) * 0.5f;
        float rTL = clampRadius(topLeft, maxRadius);
        float rTR = clampRadius(topRight, maxRadius);
        float rBR = clampRadius(bottomRight, maxRadius);
        float rBL = clampRadius(bottomLeft, maxRadius);

        float halfW = width * 0.5f;
        float halfH = height * 0.5f;
        float expand = blurRadius * 1.5f + 2.0f;

        RenderUtil.pushUntexturedBlendState(false);
        try {
            GL20.glUseProgram(shadowProgram);

            GL20.glUniform2f(shadowViewportLoc, UiScale.getDisplayWidth(), UiScale.getDisplayHeight());
            GL20.glUniform2f(shadowHalfSizeLoc, halfW, halfH);
            GL20.glUniform4f(shadowRadiiLoc, rTL, rTR, rBR, rBL);
            setColorUniform(shadowColorLoc, shadowColor);
            GL20.glUniform1f(shadowBlurLoc, blurRadius);

            drawLocalCoordQuad(x + offsetX, y + offsetY, width, height, halfW, halfH, expand);
        } finally {
            RenderUtil.popState();
        }
        return true;
    }

    private static void drawLocalCoordQuad(
        float x,
        float y,
        float width,
        float height,
        float halfW,
        float halfH,
        float expand
    ) {
        float x1 = x - expand;
        float y1 = y - expand;
        float x2 = x + width + expand;
        float y2 = y + height + expand;

        float u1 = -halfW - expand;
        float v1 = -halfH - expand;
        float u2 = halfW + expand;
        float v2 = halfH + expand;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        worldRenderer.pos(x1, y2, 0.0D).tex(u1, v2).endVertex();
        worldRenderer.pos(x2, y2, 0.0D).tex(u2, v2).endVertex();
        worldRenderer.pos(x2, y1, 0.0D).tex(u2, v1).endVertex();
        worldRenderer.pos(x1, y1, 0.0D).tex(u1, v1).endVertex();
        tessellator.draw();
    }

    private static void setColorUniform(int location, int argb) {
        if (location < 0) {
            return;
        }
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL20.glUniform4f(location, r, g, b, a);
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

    private static boolean ensureInitialized() {
        if (forceFallback || shaderFailed) {
            return false;
        }
        if (initialized) {
            return true;
        }
        if (!Display.isCreated()) {
            return false;
        }

        ContextCapabilities caps = GLContext.getCapabilities();
        if (caps == null || !caps.OpenGL20) {
            markFailed("OpenGL 2.0 is not supported; falling back to fixed-function rounded rect rendering.", null);
            return false;
        }

        try {
            String vertSource = loadShaderSource(VERT_RESOURCE);
            String rectFragSource = loadShaderSource(RECT_FRAG_RESOURCE);
            String shadowFragSource = loadShaderSource(SHADOW_FRAG_RESOURCE);

            rectProgram = compileAndLink(vertSource, rectFragSource, "rounded_rect");
            if (rectProgram == 0) {
                return false;
            }
            shadowProgram = compileAndLink(vertSource, shadowFragSource, "rounded_shadow");
            if (shadowProgram == 0) {
                return false;
            }

            rectViewportLoc = GL20.glGetUniformLocation(rectProgram, "u_ViewportSize");
            rectHalfSizeLoc = GL20.glGetUniformLocation(rectProgram, "u_HalfSize");
            rectRadiiLoc = GL20.glGetUniformLocation(rectProgram, "u_Radii");
            rectFillColorLoc = GL20.glGetUniformLocation(rectProgram, "u_FillColor");
            rectBorderColorLoc = GL20.glGetUniformLocation(rectProgram, "u_BorderColor");
            rectBorderThicknessLoc = GL20.glGetUniformLocation(rectProgram, "u_BorderThickness");

            shadowViewportLoc = GL20.glGetUniformLocation(shadowProgram, "u_ViewportSize");
            shadowHalfSizeLoc = GL20.glGetUniformLocation(shadowProgram, "u_HalfSize");
            shadowRadiiLoc = GL20.glGetUniformLocation(shadowProgram, "u_Radii");
            shadowColorLoc = GL20.glGetUniformLocation(shadowProgram, "u_ShadowColor");
            shadowBlurLoc = GL20.glGetUniformLocation(shadowProgram, "u_BlurRadius");

            initialized = true;
            return true;
        } catch (Exception e) {
            markFailed("Failed to initialize GLSL 120 rounded rect shaders; using fallback renderer.", e);
            return false;
        }
    }

    private static int compileAndLink(String vertSource, String fragSource, String debugName) {
        int vertShader = 0;
        int fragShader = 0;
        int program = 0;
        try {
            vertShader = compileShader(GL20.GL_VERTEX_SHADER, vertSource, debugName + ".vsh");
            if (vertShader == 0) {
                return 0;
            }
            fragShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragSource, debugName + ".fsh");
            if (fragShader == 0) {
                return 0;
            }

            program = GL20.glCreateProgram();
            if (program == 0) {
                markFailed("glCreateProgram returned 0 for " + debugName + "; using fallback renderer.", null);
                return 0;
            }

            GL20.glAttachShader(program, vertShader);
            GL20.glAttachShader(program, fragShader);
            GL20.glLinkProgram(program);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program, 1024);
                GL20.glDeleteProgram(program);
                markFailed("Failed to link GLSL 120 program '" + debugName + "': " + log, null);
                return 0;
            }
            return program;
        } finally {
            if (vertShader != 0) {
                GL20.glDeleteShader(vertShader);
            }
            if (fragShader != 0) {
                GL20.glDeleteShader(fragShader);
            }
        }
    }

    private static int compileShader(int type, String source, String debugName) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) {
            markFailed("glCreateShader returned 0 for " + debugName + "; using fallback renderer.", null);
            return 0;
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 1024);
            GL20.glDeleteShader(shader);
            markFailed("Failed to compile GLSL 120 shader '" + debugName + "': " + log, null);
            return 0;
        }
        return shader;
    }

    private static String loadShaderSource(String resourcePath) throws IOException {
        InputStream stream = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getResourceManager() != null) {
            IResourceManager manager = mc.getResourceManager();
            try {
                IResource resource = manager.getResource(new ResourceLocation(HoptimizationMod.MODID, resourcePath));
                if (resource != null) {
                    stream = resource.getInputStream();
                }
            } catch (IOException resourceException) {
                HoptimizationMod.LOGGER.debug(
                    "ResourceManager could not resolve {}, falling back to classpath resource.",
                    resourcePath,
                    resourceException
                );
            }
        }
        if (stream == null) {
            stream = RoundedRectShader.class.getResourceAsStream(CLASSPATH_PREFIX + resourcePath);
        }
        if (stream == null) {
            throw new IOException("Shader resource not found: " + resourcePath);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(1024);
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static void markFailed(String message, Throwable cause) {
        shaderFailed = true;
        destroyPrograms();
        if (!fallbackWarnLogged) {
            fallbackWarnLogged = true;
            if (cause != null) {
                HoptimizationMod.LOGGER.warn(message, cause);
            } else {
                HoptimizationMod.LOGGER.warn(message);
            }
        }
    }

    private static void destroyPrograms() {
        if (rectProgram != 0) {
            GL20.glDeleteProgram(rectProgram);
            rectProgram = 0;
        }
        if (shadowProgram != 0) {
            GL20.glDeleteProgram(shadowProgram);
            shadowProgram = 0;
        }
    }
}
