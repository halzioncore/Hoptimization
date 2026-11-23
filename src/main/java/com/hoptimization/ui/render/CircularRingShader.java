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
 * GLSL 120 signed-distance-field renderer for sub-pixel anti-aliased circular progress rings
 * with smooth rounded arc caps. Falls back to {@link RenderUtil} geometry when unavailable.
 */
public final class CircularRingShader {
    private static final String VERT_RESOURCE = "shader/rounded_rect.vsh";
    private static final String FRAG_RESOURCE = "shader/circular_ring.fsh";
    private static final String CLASSPATH_PREFIX = "/assets/" + HoptimizationMod.MODID + "/";

    private static boolean initialized = false;
    private static boolean failed = false;
    private static boolean warnLogged = false;

    private static int program = 0;
    private static int viewportLoc = -1;
    private static int radiusLoc = -1;
    private static int halfThicknessLoc = -1;
    private static int progressLoc = -1;
    private static int trackColorLoc = -1;
    private static int progressColorLoc = -1;

    private CircularRingShader() {
    }

    /**
     * Attempts to draw an anti-aliased circular progress ring using the GLSL 120 SDF shader.
     *
     * @return {@code true} if drawn by shader, {@code false} if caller should use fixed-function fallback
     */
    public static boolean tryDrawCircularRing(
        float centerX,
        float centerY,
        float radius,
        float thickness,
        float progress,
        int trackColor,
        int progressColor
    ) {
        if (radius <= 0.0f || thickness <= 0.0f) {
            return true;
        }
        if (((trackColor >>> 24) & 0xFF) == 0 && ((progressColor >>> 24) & 0xFF) == 0) {
            return true;
        }
        if (!ensureInitialized()) {
            return false;
        }

        float halfT = thickness * 0.5f;
        float clampedProgress = progress <= 0.0f ? 0.0f : (progress >= 1.0f ? 1.0f : progress);
        float halfBox = radius + halfT + 2.0f;

        RenderUtil.pushUntexturedBlendState(false);
        try {
            GL20.glUseProgram(program);
            GL20.glUniform2f(viewportLoc, UiScale.getDisplayWidth(), UiScale.getDisplayHeight());
            GL20.glUniform1f(radiusLoc, radius);
            GL20.glUniform1f(halfThicknessLoc, halfT);
            GL20.glUniform1f(progressLoc, clampedProgress);
            setColorUniform(trackColorLoc, trackColor);
            setColorUniform(progressColorLoc, progressColor);

            float x1 = centerX - halfBox;
            float y1 = centerY - halfBox;
            float x2 = centerX + halfBox;
            float y2 = centerY + halfBox;

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            wr.pos(x1, y2, 0.0D).tex(-halfBox, halfBox).endVertex();
            wr.pos(x2, y2, 0.0D).tex(halfBox, halfBox).endVertex();
            wr.pos(x2, y1, 0.0D).tex(halfBox, -halfBox).endVertex();
            wr.pos(x1, y1, 0.0D).tex(-halfBox, -halfBox).endVertex();
            tessellator.draw();
        } finally {
            RenderUtil.popState();
        }
        return true;
    }

    public static void destroy() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        initialized = false;
    }

    private static boolean ensureInitialized() {
        if (RoundedRectShader.isForceFallback() || failed) {
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
            markFailed("OpenGL 2.0 unavailable; circular ring shader disabled.", null);
            return false;
        }

        try {
            String vertSource = loadShaderSource(VERT_RESOURCE);
            String fragSource = loadShaderSource(FRAG_RESOURCE);
            program = compileAndLink(vertSource, fragSource);
            if (program == 0) {
                return false;
            }
            viewportLoc = GL20.glGetUniformLocation(program, "u_ViewportSize");
            radiusLoc = GL20.glGetUniformLocation(program, "u_Radius");
            halfThicknessLoc = GL20.glGetUniformLocation(program, "u_HalfThickness");
            progressLoc = GL20.glGetUniformLocation(program, "u_Progress");
            trackColorLoc = GL20.glGetUniformLocation(program, "u_TrackColor");
            progressColorLoc = GL20.glGetUniformLocation(program, "u_ProgressColor");
            initialized = true;
            return true;
        } catch (Exception e) {
            markFailed("Failed to initialize circular_ring GLSL shader; using fallback.", e);
            return false;
        }
    }

    private static int compileAndLink(String vertSource, String fragSource) {
        int vert = 0;
        int frag = 0;
        try {
            vert = compileShader(GL20.GL_VERTEX_SHADER, vertSource, "rounded_rect.vsh");
            if (vert == 0) {
                return 0;
            }
            frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSource, "circular_ring.fsh");
            if (frag == 0) {
                return 0;
            }
            int prog = GL20.glCreateProgram();
            if (prog == 0) {
                markFailed("glCreateProgram returned 0 for circular_ring.", null);
                return 0;
            }
            GL20.glAttachShader(prog, vert);
            GL20.glAttachShader(prog, frag);
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(prog, 1024);
                GL20.glDeleteProgram(prog);
                markFailed("Failed to link circular_ring shader: " + log, null);
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
            stream = CircularRingShader.class.getResourceAsStream(CLASSPATH_PREFIX + resourcePath);
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
