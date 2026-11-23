package com.hoptimization.ui.render;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Real-time half-resolution separable Gaussian backdrop blur and SDF frosted-glass rounded rectangle renderer.
 * Captures Minecraft's main {@link Framebuffer} once per UI pass and falls back gracefully when FBOs or GLSL 120
 * are unavailable.
 */
public final class BackdropBlurRenderer {
    private static final String BLUR_VERT_RESOURCE = "shader/blur_quad.vsh";
    private static final String BLUR_FRAG_RESOURCE = "shader/gaussian_blur.fsh";
    private static final String GLASS_VERT_RESOURCE = "shader/rounded_rect.vsh";
    private static final String GLASS_FRAG_RESOURCE = "shader/frosted_glass.fsh";
    private static final String CLASSPATH_PREFIX = "/assets/" + HoptimizationMod.MODID + "/";

    private static final IntBuffer VIEWPORT_BUFFER = BufferUtils.createIntBuffer(16);

    private static boolean initialized = false;
    private static boolean failed = false;
    private static boolean warnLogged = false;

    private static int blurProgram = 0;
    private static int blurTexLoc = -1;
    private static int blurTexelSizeLoc = -1;
    private static int blurDirectionLoc = -1;
    private static int blurRadiusLoc = -1;

    private static int glassProgram = 0;
    private static int glassBlurTexLoc = -1;
    private static int glassViewportLoc = -1;
    private static int glassHalfSizeLoc = -1;
    private static int glassRadiiLoc = -1;
    private static int glassTintColorLoc = -1;
    private static int glassBorderColorLoc = -1;
    private static int glassBorderThicknessLoc = -1;
    private static int glassHighlightLoc = -1;
    private static int glassMasterAlphaLoc = -1;

    private static Framebuffer blurFboA = null;
    private static Framebuffer blurFboB = null;
    private static int cachedFboW = 0;
    private static int cachedFboH = 0;
    private static boolean blurReady = false;

    private BackdropBlurRenderer() {
    }

    /**
     * Returns whether a valid blurred backdrop texture is ready for sampling in the current frame.
     *
     * @return {@code true} when blurred backdrop can be sampled
     */
    public static boolean isBlurReady() {
        return blurReady && blurFboB != null && blurFboB.framebufferTexture > 0 && !failed && !RoundedRectShader.isForceFallback();
    }

    /**
     * Captures the current main Minecraft framebuffer and executes a 2-iteration separable Gaussian blur
     * into half-resolution ping-pong FBOs. Safe to call once per frame before drawing frosted panels.
     *
     * @param blurStrength blur strength in logical pixels (typically 4.0 to 24.0)
     * @return {@code true} if the blurred texture is ready
     */
    public static boolean captureAndBlur(float blurStrength) {
        blurReady = false;
        if (RoundedRectShader.isForceFallback() || !OpenGlHelper.isFramebufferEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        Framebuffer mainFbo = mc.getFramebuffer();
        if (mainFbo == null || mainFbo.framebufferTexture <= 0
            || mainFbo.framebufferWidth <= 0 || mainFbo.framebufferHeight <= 0) {
            return false;
        }
        if (!ensureInitialized()) {
            return false;
        }

        int targetW = Math.max(1, mainFbo.framebufferWidth / 2);
        int targetH = Math.max(1, mainFbo.framebufferHeight / 2);
        ensureFramebuffers(targetW, targetH);
        if (blurFboA == null || blurFboB == null
            || blurFboA.framebufferTexture <= 0 || blurFboB.framebufferTexture <= 0) {
            return false;
        }

        float clampedStrength = blurStrength < 2.0f ? 2.0f : (blurStrength > 32.0f ? 32.0f : blurStrength);
        // Scale down to half-res texel space and distribute across 2 ping-pong cycles for smooth results.
        float pass1Radius = clampedStrength * 0.22f;
        float pass2Radius = clampedStrength * 0.36f;

        boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasTexture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        VIEWPORT_BUFFER.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT_BUFFER);
        int prevVpX = VIEWPORT_BUFFER.get(0);
        int prevVpY = VIEWPORT_BUFFER.get(1);
        int prevVpW = VIEWPORT_BUFFER.get(2);
        int prevVpH = VIEWPORT_BUFFER.get(3);

        try {
            if (wasScissor) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GlStateManager.disableBlend();
            GlStateManager.disableDepth();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            GL20.glUseProgram(blurProgram);
            GL20.glUniform1i(blurTexLoc, 0);

            float invMainW = 1.0f / (float) mainFbo.framebufferWidth;
            float invMainH = 1.0f / (float) mainFbo.framebufferHeight;
            float invBlurW = 1.0f / (float) targetW;
            float invBlurH = 1.0f / (float) targetH;

            // Pass 1: Main FBO -> blurFboA (Horizontal)
            runBlurPass(blurFboA, mainFbo.framebufferTexture, invMainW, invMainH, 1.0f, 0.0f, pass1Radius);
            // Pass 2: blurFboA -> blurFboB (Vertical)
            runBlurPass(blurFboB, blurFboA.framebufferTexture, invBlurW, invBlurH, 0.0f, 1.0f, pass1Radius);
            // Pass 3: blurFboB -> blurFboA (Horizontal wide)
            runBlurPass(blurFboA, blurFboB.framebufferTexture, invBlurW, invBlurH, 1.0f, 0.0f, pass2Radius);
            // Pass 4: blurFboA -> blurFboB (Vertical wide)
            runBlurPass(blurFboB, blurFboA.framebufferTexture, invBlurW, invBlurH, 0.0f, 1.0f, pass2Radius);

            blurReady = true;
            return true;
        } catch (Throwable t) {
            markFailed("BackdropBlurRenderer encountered an error during blur pass; disabling frosted blur.", t);
            return false;
        } finally {
            GL20.glUseProgram(prevProgram);
            mainFbo.bindFramebuffer(false);
            GL11.glViewport(prevVpX, prevVpY, prevVpW, prevVpH);

            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(prevTex2D);
            GlStateManager.setActiveTexture(prevActiveTex);

            if (wasTexture) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (wasCull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (wasAlpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            if (wasDepth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            if (wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (wasScissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    /**
     * Draws a frosted glass rounded rectangle sampling the blurred backdrop texture.
     * Returns {@code false} if the blur shader/FBO is unavailable so the caller can fall back cleanly.
     */
    public static boolean tryDrawFrostedRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float topLeft,
        float topRight,
        float bottomRight,
        float bottomLeft,
        int tintColor,
        int borderColor,
        float borderThickness,
        float highlightStrength,
        float masterAlpha
    ) {
        if (width <= 0.0f || height <= 0.0f || masterAlpha <= 0.001f) {
            return true;
        }
        if (!isBlurReady() || !ensureInitialized()) {
            return false;
        }

        float maxRadius = Math.min(width, height) * 0.5f;
        float rTL = clampRadius(topLeft, maxRadius);
        float rTR = clampRadius(topRight, maxRadius);
        float rBR = clampRadius(bottomRight, maxRadius);
        float rBL = clampRadius(bottomLeft, maxRadius);

        float halfW = width * 0.5f;
        float halfH = height * 0.5f;
        float expand = 2.0f;

        RenderUtil.pushTexturedBlendState();
        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(blurFboB.framebufferTexture);
            applyLinearClamp();

            GL20.glUseProgram(glassProgram);
            GL20.glUniform1i(glassBlurTexLoc, 0);
            GL20.glUniform2f(glassViewportLoc, UiScale.getDisplayWidth(), UiScale.getDisplayHeight());
            GL20.glUniform2f(glassHalfSizeLoc, halfW, halfH);
            GL20.glUniform4f(glassRadiiLoc, rTL, rTR, rBR, rBL);
            setColorUniform(glassTintColorLoc, tintColor);
            setColorUniform(glassBorderColorLoc, borderColor);
            GL20.glUniform1f(glassBorderThicknessLoc, Math.max(0.0f, borderThickness));
            GL20.glUniform1f(glassHighlightLoc, Math.max(0.0f, highlightStrength));
            GL20.glUniform1f(glassMasterAlphaLoc, masterAlpha >= 1.0f ? 1.0f : masterAlpha);

            drawLocalCoordQuad(x, y, width, height, halfW, halfH, expand);
        } finally {
            RenderUtil.popState();
        }
        return true;
    }

    /**
     * Releases compiled blur/glass shaders and offscreen framebuffers.
     */
    public static void destroy() {
        destroyPrograms();
        deleteFramebuffers();
        initialized = false;
        blurReady = false;
    }

    private static void runBlurPass(
        Framebuffer targetFbo,
        int sourceTexId,
        float texelX,
        float texelY,
        float dirX,
        float dirY,
        float radius
    ) {
        targetFbo.bindFramebuffer(true);
        GlStateManager.bindTexture(sourceTexId);
        applyLinearClamp();

        GL20.glUniform2f(blurTexelSizeLoc, texelX, texelY);
        GL20.glUniform2f(blurDirectionLoc, dirX, dirY);
        GL20.glUniform1f(blurRadiusLoc, radius);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(-1.0D, -1.0D, 0.0D).tex(0.0D, 0.0D).endVertex();
        wr.pos(1.0D, -1.0D, 0.0D).tex(1.0D, 0.0D).endVertex();
        wr.pos(1.0D, 1.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
        wr.pos(-1.0D, 1.0D, 0.0D).tex(0.0D, 1.0D).endVertex();
        tess.draw();
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

    private static void applyLinearClamp() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        ContextCapabilities caps = Display.isCreated() ? GLContext.getCapabilities() : null;
        if (caps != null && caps.OpenGL12) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
    }

    private static void ensureFramebuffers(int width, int height) {
        if (blurFboA != null && blurFboB != null && cachedFboW == width && cachedFboH == height) {
            return;
        }
        deleteFramebuffers();
        blurFboA = new Framebuffer(width, height, false);
        blurFboA.setFramebufferFilter(GL11.GL_LINEAR);
        blurFboB = new Framebuffer(width, height, false);
        blurFboB.setFramebufferFilter(GL11.GL_LINEAR);
        cachedFboW = width;
        cachedFboH = height;
    }

    private static void deleteFramebuffers() {
        if (blurFboA != null) {
            blurFboA.deleteFramebuffer();
            blurFboA = null;
        }
        if (blurFboB != null) {
            blurFboB.deleteFramebuffer();
            blurFboB = null;
        }
        cachedFboW = 0;
        cachedFboH = 0;
    }

    private static boolean ensureInitialized() {
        if (failed || RoundedRectShader.isForceFallback()) {
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
            markFailed("OpenGL 2.0 is not supported; frosted glass blur disabled.", null);
            return false;
        }

        try {
            String blurVert = loadShaderSource(BLUR_VERT_RESOURCE);
            String blurFrag = loadShaderSource(BLUR_FRAG_RESOURCE);
            String glassVert = loadShaderSource(GLASS_VERT_RESOURCE);
            String glassFrag = loadShaderSource(GLASS_FRAG_RESOURCE);

            blurProgram = compileAndLink(blurVert, blurFrag, "gaussian_blur");
            if (blurProgram == 0) {
                return false;
            }
            glassProgram = compileAndLink(glassVert, glassFrag, "frosted_glass");
            if (glassProgram == 0) {
                return false;
            }

            blurTexLoc = GL20.glGetUniformLocation(blurProgram, "u_Texture");
            blurTexelSizeLoc = GL20.glGetUniformLocation(blurProgram, "u_TexelSize");
            blurDirectionLoc = GL20.glGetUniformLocation(blurProgram, "u_Direction");
            blurRadiusLoc = GL20.glGetUniformLocation(blurProgram, "u_Radius");

            glassBlurTexLoc = GL20.glGetUniformLocation(glassProgram, "u_BlurTex");
            glassViewportLoc = GL20.glGetUniformLocation(glassProgram, "u_ViewportSize");
            glassHalfSizeLoc = GL20.glGetUniformLocation(glassProgram, "u_HalfSize");
            glassRadiiLoc = GL20.glGetUniformLocation(glassProgram, "u_Radii");
            glassTintColorLoc = GL20.glGetUniformLocation(glassProgram, "u_TintColor");
            glassBorderColorLoc = GL20.glGetUniformLocation(glassProgram, "u_BorderColor");
            glassBorderThicknessLoc = GL20.glGetUniformLocation(glassProgram, "u_BorderThickness");
            glassHighlightLoc = GL20.glGetUniformLocation(glassProgram, "u_HighlightStrength");
            glassMasterAlphaLoc = GL20.glGetUniformLocation(glassProgram, "u_MasterAlpha");

            initialized = true;
            return true;
        } catch (Exception e) {
            markFailed("Failed to initialize frosted glass GLSL shaders; falling back to translucent tint.", e);
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
                markFailed("glCreateProgram returned 0 for " + debugName, null);
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
            markFailed("glCreateShader returned 0 for " + debugName, null);
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
            } catch (IOException ignored) {
                // Fall back to classpath stream below.
            }
        }
        if (stream == null) {
            stream = BackdropBlurRenderer.class.getResourceAsStream(CLASSPATH_PREFIX + resourcePath);
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

    private static void markFailed(String message, Throwable cause) {
        failed = true;
        blurReady = false;
        destroyPrograms();
        deleteFramebuffers();
        if (!warnLogged) {
            warnLogged = true;
            if (cause != null) {
                HoptimizationMod.LOGGER.warn(message, cause);
            } else {
                HoptimizationMod.LOGGER.warn(message);
            }
        }
    }

    private static void destroyPrograms() {
        if (blurProgram != 0) {
            GL20.glDeleteProgram(blurProgram);
            blurProgram = 0;
        }
        if (glassProgram != 0) {
            GL20.glDeleteProgram(glassProgram);
            glassProgram = 0;
        }
    }
}
