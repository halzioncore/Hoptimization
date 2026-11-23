package com.hoptimization.ui.render;

import com.hoptimization.modules.visual.Chams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Renders 3D topographic contour lines and a soft Keystrokes-style outer glow
 * around living entities.
 */
public final class ChamsRenderer {
    private static final ResourceLocation VERTEX_SHADER =
            new ResourceLocation("hoptimization", "shader/chams_contour.vsh");
    private static final ResourceLocation FRAGMENT_SHADER =
            new ResourceLocation("hoptimization", "shader/chams_contour.fsh");

    private static boolean renderingChams;
    private static boolean shaderInitialized;
    private static int programId;
    private static int uColorLoc = -1;
    private static int uDensityLoc = -1;
    private static int uFlowPhaseLoc = -1;
    private static int uGlowStrengthLoc = -1;
    private static int uFillAlphaLoc = -1;

    private ChamsRenderer() {
    }

    public interface ModelDrawCallback {
        void draw();
    }

    public static boolean isRenderingChams() {
        return renderingChams;
    }

    public static void renderOverlay(EntityLivingBase entity, ModelDrawCallback drawCallback) {
        if (renderingChams || !Chams.shouldRenderEntity(entity)) {
            return;
        }
        Chams chams = Chams.get();
        if (chams == null) {
            return;
        }

        ensureShader();
        int argb = chams.getEffectColor();
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        float density = chams.getContourDensity();
        float glow = chams.getGlowStrength();
        float fill = chams.getFillOpacity();
        float flowPhase = chams.isContourFlow()
                ? (System.currentTimeMillis() % 60000L) / 1000.0F * 0.85F
                : 0.0F;

        renderingChams = true;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.depthMask(false);
            GlStateManager.disableAlpha();

            if (chams.isThroughWalls()) {
                GL11.glDepthFunc(GL11.GL_GREATER);
                renderPass(drawCallback, r, g, b, density, flowPhase, glow * 0.65F, fill * 0.75F, true);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
            }

            renderPass(drawCallback, r, g, b, density, flowPhase, glow, fill, false);
        } finally {
            GL20.glUseProgram(0);
            GL11.glPopAttrib();
            GlStateManager.depthMask(true);
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.enableLighting();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            renderingChams = false;
        }
    }

    private static void renderPass(
            ModelDrawCallback drawCallback,
            float r,
            float g,
            float b,
            float density,
            float flowPhase,
            float glow,
            float fill,
            boolean occludedPass) {
        // 1. Soft outer silhouette bloom (front-face culled expanded passes)
        if (glow > 0.05F) {
            GL20.glUseProgram(0);
            GlStateManager.enableCull();
            GL11.glCullFace(GL11.GL_FRONT);
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

            float outerAlpha1 = (occludedPass ? 0.12F : 0.16F) * glow;
            float outerAlpha2 = (occludedPass ? 0.06F : 0.08F) * glow;

            drawScaledSilhouette(drawCallback, 1.032F, 1.016F, 1.032F, r, g, b, outerAlpha1);
            drawScaledSilhouette(drawCallback, 1.068F, 1.034F, 1.068F, r, g, b, outerAlpha2);

            GL11.glCullFace(GL11.GL_BACK);
        }

        // 2. 3D topographic contour lines + Fresnel rim + soft translucent fill
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1.0F, -1.0F);

        if (programId != 0) {
            GL20.glUseProgram(programId);
            GL20.glUniform3f(uColorLoc, r, g, b);
            GL20.glUniform1f(uDensityLoc, density);
            GL20.glUniform1f(uFlowPhaseLoc, flowPhase);
            GL20.glUniform1f(uGlowStrengthLoc, glow);
            GL20.glUniform1f(uFillAlphaLoc, fill);
            drawCallback.draw();
            GL20.glUseProgram(0);
        } else {
            GlStateManager.color(r, g, b, Math.min(0.45F, fill + 0.18F));
            drawCallback.draw();
        }

        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    private static void drawScaledSilhouette(
            ModelDrawCallback drawCallback,
            float sx,
            float sy,
            float sz,
            float r,
            float g,
            float b,
            float alpha) {
        if (alpha <= 0.002F) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, -1.501F * (sy - 1.0F) * 0.5F, 0.0F);
        GlStateManager.scale(sx, sy, sz);
        GlStateManager.color(r, g, b, alpha);
        drawCallback.draw();
        GlStateManager.popMatrix();
    }

    private static void ensureShader() {
        if (shaderInitialized) {
            return;
        }
        shaderInitialized = true;
        try {
            String vertSrc = readResource(VERTEX_SHADER);
            String fragSrc = readResource(FRAGMENT_SHADER);
            if (vertSrc == null || fragSrc == null) {
                return;
            }
            int vert = compileShader(GL20.GL_VERTEX_SHADER, vertSrc);
            int frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc);
            if (vert == 0 || frag == 0) {
                if (vert != 0) {
                    GL20.glDeleteShader(vert);
                }
                if (frag != 0) {
                    GL20.glDeleteShader(frag);
                }
                return;
            }
            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vert);
            GL20.glAttachShader(prog, frag);
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(prog);
                return;
            }
            programId = prog;
            uColorLoc = GL20.glGetUniformLocation(prog, "u_Color");
            uDensityLoc = GL20.glGetUniformLocation(prog, "u_Density");
            uFlowPhaseLoc = GL20.glGetUniformLocation(prog, "u_FlowPhase");
            uGlowStrengthLoc = GL20.glGetUniformLocation(prog, "u_GlowStrength");
            uFillAlphaLoc = GL20.glGetUniformLocation(prog, "u_FillAlpha");
        } catch (Throwable ignored) {
            programId = 0;
        }
    }

    private static int compileShader(int type, String src) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static String readResource(ResourceLocation location) {
        try (InputStream stream = Minecraft.getMinecraft().getResourceManager().getResource(location).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
