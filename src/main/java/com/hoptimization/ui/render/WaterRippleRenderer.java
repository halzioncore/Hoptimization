package com.hoptimization.ui.render;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeMode;
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
 * Real-time top-down 2D water ripple physics simulator using ping-pong FBOs and GLSL 120.
 * Mouse cursor movement disturbs the water surface like a hand creating realistic propagating wakes and ripples.
 */
public final class WaterRippleRenderer {
    private static final String VERT_RESOURCE = "shader/blur_quad.vsh";
    private static final String SIM_FRAG_RESOURCE = "shader/water_sim.fsh";
    private static final String RENDER_FRAG_RESOURCE = "shader/water_render.fsh";
    private static final String CLASSPATH_PREFIX = "/assets/" + HoptimizationMod.MODID + "/";
    private static final int SIM_SHORT_SIDE = 192;

    private static final IntBuffer VIEWPORT_BUF = BufferUtils.createIntBuffer(16);

    private static boolean initialized = false;
    private static boolean failed = false;
    private static boolean warnLogged = false;

    private static int simProgram = 0;
    private static int simStateTexLoc = -1;
    private static int simTexelSizeLoc = -1;
    private static int simAspectLoc = -1;
    private static int simMousePosLoc = -1;
    private static int simPrevMousePosLoc = -1;
    private static int simMouseMovedLoc = -1;
    private static int simBrushRadiusLoc = -1;

    private static int renderProgram = 0;
    private static int renderStateTexLoc = -1;
    private static int renderTexelSizeLoc = -1;
    private static int renderTopColorLoc = -1;
    private static int renderBottomColorLoc = -1;
    private static int renderAccentColorLoc = -1;
    private static int renderIsLightLoc = -1;

    private static Framebuffer fboA = null;
    private static Framebuffer fboB = null;
    private static int simW = 0;
    private static int simH = 0;
    private static boolean readFromA = true;

    private static float prevMouseU = -1.0f;
    private static float prevMouseV = -1.0f;
    private static long lastStepNanos = 0L;

    private WaterRippleRenderer() {
    }

    /**
     * Steps the 2D water simulation and renders the top-down ripple surface across {@code [0..width, 0..height]}.
     *
     * @return {@code true} if rendered via water shader, {@code false} if caller should fall back to flat gradient
     */
    public static boolean renderWaterBackground(
        float width,
        float height,
        int mouseX,
        int mouseY,
        ThemeData theme
    ) {
        if (width <= 0.0f || height <= 0.0f || RoundedRectShader.isForceFallback() || !OpenGlHelper.isFramebufferEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getFramebuffer() == null || !ensureInitialized()) {
            return false;
        }

        int targetW = width >= height
            ? Math.max(64, Math.round(SIM_SHORT_SIDE * (width / height)))
            : SIM_SHORT_SIDE;
        int targetH = width >= height
            ? SIM_SHORT_SIDE
            : Math.max(64, Math.round(SIM_SHORT_SIDE * (height / width)));
        targetW = Math.min(384, targetW);
        targetH = Math.min(384, targetH);
        ensureFramebuffers(targetW, targetH);
        if (fboA == null || fboB == null || fboA.framebufferTexture <= 0 || fboB.framebufferTexture <= 0) {
            return false;
        }

        float mouseU = clamp01(mouseX / width);
        float mouseV = clamp01(1.0f - (mouseY / height));
        if (prevMouseU < 0.0f) {
            prevMouseU = mouseU;
            prevMouseV = mouseV;
        }
        float deltaSq = (mouseU - prevMouseU) * (mouseU - prevMouseU) + (mouseV - prevMouseV) * (mouseV - prevMouseV);
        boolean moved = deltaSq > 1e-6f;

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

        VIEWPORT_BUF.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT_BUF);
        int prevVpX = VIEWPORT_BUF.get(0);
        int prevVpY = VIEWPORT_BUF.get(1);
        int prevVpW = VIEWPORT_BUF.get(2);
        int prevVpH = VIEWPORT_BUF.get(3);

        Framebuffer mainFbo = mc.getFramebuffer();
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

            long now = System.nanoTime();
            if (lastStepNanos == 0L || (now - lastStepNanos) >= 14_000_000L) {
                lastStepNanos = now;
                Framebuffer src = readFromA ? fboA : fboB;
                Framebuffer dst = readFromA ? fboB : fboA;

                dst.bindFramebuffer(true);
                GlStateManager.bindTexture(src.framebufferTexture);
                applyLinearClamp();

                GL20.glUseProgram(simProgram);
                GL20.glUniform1i(simStateTexLoc, 0);
                GL20.glUniform2f(simTexelSizeLoc, 1.0f / targetW, 1.0f / targetH);
                float aspectX = width >= height ? (width / height) : 1.0f;
                float aspectY = width >= height ? 1.0f : (height / width);
                GL20.glUniform2f(simAspectLoc, aspectX, aspectY);
                GL20.glUniform2f(simMousePosLoc, mouseU, mouseV);
                GL20.glUniform2f(simPrevMousePosLoc, prevMouseU, prevMouseV);
                GL20.glUniform1f(simMouseMovedLoc, moved ? 1.0f : 0.0f);
                GL20.glUniform1f(simBrushRadiusLoc, 0.045f);

                drawNdcQuad();
                readFromA = !readFromA;
                prevMouseU = mouseU;
                prevMouseV = mouseV;
            }

            mainFbo.bindFramebuffer(false);
            GL11.glViewport(prevVpX, prevVpY, prevVpW, prevVpH);

            Framebuffer activeState = readFromA ? fboA : fboB;
            GlStateManager.bindTexture(activeState.framebufferTexture);
            applyLinearClamp();

            GL20.glUseProgram(renderProgram);
            GL20.glUniform1i(renderStateTexLoc, 0);
            GL20.glUniform2f(renderTexelSizeLoc, 1.0f / targetW, 1.0f / targetH);
            setColorUniform(renderTopColorLoc, theme.surface0());
            setColorUniform(renderBottomColorLoc, theme.surface1());
            setColorUniform(renderAccentColorLoc, theme.accent());
            GL20.glUniform1f(renderIsLightLoc, theme.getMode() == ThemeMode.LIGHT ? 1.0f : 0.0f);

            drawNdcQuad();
            return true;
        } catch (Throwable t) {
            markFailed("WaterRippleRenderer encountered an error; falling back to flat gradient.", t);
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
            }
            if (wasAlpha) {
                GlStateManager.enableAlpha();
            }
            if (wasDepth) {
                GlStateManager.enableDepth();
            }
            if (wasBlend) {
                GlStateManager.enableBlend();
            }
            if (wasScissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    public static void destroy() {
        if (simProgram != 0) {
            GL20.glDeleteProgram(simProgram);
            simProgram = 0;
        }
        if (renderProgram != 0) {
            GL20.glDeleteProgram(renderProgram);
            renderProgram = 0;
        }
        deleteFramebuffers();
        initialized = false;
    }

    private static void ensureFramebuffers(int width, int height) {
        if (fboA != null && fboB != null && simW == width && simH == height) {
            return;
        }
        deleteFramebuffers();
        fboA = new Framebuffer(width, height, false);
        fboA.setFramebufferColor(0.5f, 0.5f, 0.0f, 1.0f);
        fboA.framebufferClear();
        fboA.setFramebufferFilter(GL11.GL_LINEAR);

        fboB = new Framebuffer(width, height, false);
        fboB.setFramebufferColor(0.5f, 0.5f, 0.0f, 1.0f);
        fboB.framebufferClear();
        fboB.setFramebufferFilter(GL11.GL_LINEAR);

        simW = width;
        simH = height;
        readFromA = true;
    }

    private static void deleteFramebuffers() {
        if (fboA != null) {
            fboA.deleteFramebuffer();
            fboA = null;
        }
        if (fboB != null) {
            fboB.deleteFramebuffer();
            fboB = null;
        }
        simW = 0;
        simH = 0;
    }

    private static void drawNdcQuad() {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(-1.0D, -1.0D, 0.0D).tex(0.0D, 0.0D).endVertex();
        wr.pos(1.0D, -1.0D, 0.0D).tex(1.0D, 0.0D).endVertex();
        wr.pos(1.0D, 1.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
        wr.pos(-1.0D, 1.0D, 0.0D).tex(0.0D, 1.0D).endVertex();
        tess.draw();
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
            markFailed("OpenGL 2.0 unavailable; water ripple shader disabled.", null);
            return false;
        }
        try {
            String vert = loadShaderSource(VERT_RESOURCE);
            String simFrag = loadShaderSource(SIM_FRAG_RESOURCE);
            String renderFrag = loadShaderSource(RENDER_FRAG_RESOURCE);

            simProgram = compileAndLink(vert, simFrag, "water_sim");
            if (simProgram == 0) {
                return false;
            }
            renderProgram = compileAndLink(vert, renderFrag, "water_render");
            if (renderProgram == 0) {
                return false;
            }

            simStateTexLoc = GL20.glGetUniformLocation(simProgram, "u_StateTex");
            simTexelSizeLoc = GL20.glGetUniformLocation(simProgram, "u_TexelSize");
            simAspectLoc = GL20.glGetUniformLocation(simProgram, "u_Aspect");
            simMousePosLoc = GL20.glGetUniformLocation(simProgram, "u_MousePos");
            simPrevMousePosLoc = GL20.glGetUniformLocation(simProgram, "u_PrevMousePos");
            simMouseMovedLoc = GL20.glGetUniformLocation(simProgram, "u_MouseMoved");
            simBrushRadiusLoc = GL20.glGetUniformLocation(simProgram, "u_BrushRadius");

            renderStateTexLoc = GL20.glGetUniformLocation(renderProgram, "u_StateTex");
            renderTexelSizeLoc = GL20.glGetUniformLocation(renderProgram, "u_TexelSize");
            renderTopColorLoc = GL20.glGetUniformLocation(renderProgram, "u_TopColor");
            renderBottomColorLoc = GL20.glGetUniformLocation(renderProgram, "u_BottomColor");
            renderAccentColorLoc = GL20.glGetUniformLocation(renderProgram, "u_AccentColor");
            renderIsLightLoc = GL20.glGetUniformLocation(renderProgram, "u_IsLight");

            initialized = true;
            return true;
        } catch (Exception e) {
            markFailed("Failed to initialize water ripple shaders; using fallback.", e);
            return false;
        }
    }

    private static int compileAndLink(String vertSource, String fragSource, String debugName) {
        int vert = 0;
        int frag = 0;
        try {
            vert = compileShader(GL20.GL_VERTEX_SHADER, vertSource, debugName + ".vsh");
            if (vert == 0) {
                return 0;
            }
            frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSource, debugName + ".fsh");
            if (frag == 0) {
                return 0;
            }
            int prog = GL20.glCreateProgram();
            if (prog == 0) {
                markFailed("glCreateProgram returned 0 for " + debugName, null);
                return 0;
            }
            GL20.glAttachShader(prog, vert);
            GL20.glAttachShader(prog, frag);
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(prog, 1024);
                GL20.glDeleteProgram(prog);
                markFailed("Failed to link " + debugName + ": " + log, null);
                return 0;
            }
            return prog;
        } finally {
            if (vert != 0) {
                GL20.glDeleteShader(vert);
            }
            if (frag != 0) {
                GL20.glDeleteShader(frag);
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
            markFailed("Failed to compile " + debugName + ": " + log, null);
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
            stream = WaterRippleRenderer.class.getResourceAsStream(CLASSPATH_PREFIX + resourcePath);
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

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private static void markFailed(String message, Throwable cause) {
        failed = true;
        destroy();
        if (!warnLogged) {
            warnLogged = true;
            if (cause != null) {
                HoptimizationMod.LOGGER.warn(message, cause);
            } else {
                HoptimizationMod.LOGGER.warn(message);
            }
        }
    }
}
