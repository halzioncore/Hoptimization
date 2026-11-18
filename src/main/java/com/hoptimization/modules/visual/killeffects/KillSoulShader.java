package com.hoptimization.modules.visual.killeffects;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Manages the GLSL 120 soul/annihilation shader program with automatic fixed-function
 * multitexture combiner fallback for environments without OpenGL 2.0 or with active shaderpacks.
 */
public final class KillSoulShader {
    private static final String VERT_RESOURCE = "shader/kill_soul.vsh";
    private static final String FRAG_RESOURCE = "shader/kill_soul.fsh";
    private static final String CLASSPATH_PREFIX = "/assets/" + HoptimizationMod.MODID + "/";

    private static final FloatBuffer ENV_COLOR_WRITE_BUFFER = BufferUtils.createFloatBuffer(4);
    private static final TexUnitBackup TU0_BACKUP = new TexUnitBackup();
    private static final TexUnitBackup TU1_BACKUP = new TexUnitBackup();
    private static final TexUnitBackup TU2_BACKUP = new TexUnitBackup();

    private static volatile boolean forceFallback = false;
    private static boolean initialized = false;
    private static boolean shaderFailed = false;
    private static boolean fallbackWarnLogged = false;

    private static int program = 0;
    private static int uTextureLoc = -1;
    private static int uLightmapLoc = -1;
    private static int uUseLightmapLoc = -1;
    private static int uSoulColorLoc = -1;
    private static int uSoulMixLoc = -1;
    private static int uAlphaLoc = -1;
    private static int uFlashLoc = -1;

    private static int whiteTextureId = 0;

    private static boolean passActive = false;
    private static boolean passUsedShader = false;
    private static boolean passModifiedFixedPipeline = false;
    private static boolean passModifiedColor = false;
    private static boolean passHasOpenGl20 = false;
    private static int prevProgram = 0;
    private static int prevActiveTex = 0;

    private KillSoulShader() {
    }

    /**
     * Enables or disables forced fallback to the fixed-function texture combiner path.
     *
     * @param force {@code true} to bypass GLSL shaders
     */
    public static void setForceFallback(boolean force) {
        forceFallback = force;
        if (force && !fallbackWarnLogged) {
            fallbackWarnLogged = true;
            HoptimizationMod.LOGGER.warn("KillSoulShader forced to fixed-function fallback mode.");
        }
    }

    /**
     * Returns whether forced fixed-function fallback mode is active.
     *
     * @return {@code true} when forced fallback is enabled
     */
    public static boolean isForceFallback() {
        return forceFallback;
    }

    /**
     * Returns whether the GLSL 120 shader program is initialized and ready for use.
     *
     * @return {@code true} when the shader program is available
     */
    public static boolean isShaderAvailable() {
        return ensureInitialized();
    }

    /**
     * Begins a soul or annihilation shading pass, automatically falling back to fixed-function
     * texture combiners when OpenGL 2.0 is unavailable, compilation failed, or another shader
     * program (such as OptiFine Shaders) is already bound.
     *
     * @param soulArgb    soul tint color in ARGB format
     * @param soulMix     soul tint blend factor in {@code [0, 1]} ({@code 0} for Annihilation)
     * @param alpha       overall opacity multiplier in {@code [0, 1]}
     * @param flash       cleave impact flash intensity in {@code [0, 1]}
     * @param useLightmap {@code true} to sample lightmap texture unit 1
     * @return {@code true} if the GLSL shader program was bound, {@code false} if fixed-pipeline fallback is used
     */
    public static boolean begin(int soulArgb, float soulMix, float alpha, float flash, boolean useLightmap) {
        if (passActive) {
            end();
        }

        float clampedMix = clamp01(soulMix);
        float clampedAlpha = clamp01(alpha);
        float clampedFlash = clamp01(flash);
        float soulR = ((soulArgb >>> 16) & 0xFF) / 255.0F;
        float soulG = ((soulArgb >>> 8) & 0xFF) / 255.0F;
        float soulB = (soulArgb & 0xFF) / 255.0F;

        ContextCapabilities caps = getCapabilities();
        passHasOpenGl20 = caps != null && caps.OpenGL20;
        prevProgram = passHasOpenGl20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        passActive = true;
        passUsedShader = false;
        passModifiedFixedPipeline = false;
        passModifiedColor = false;

        // Bypass custom GLSL program when an external shaderpack (e.g., OptiFine) is active.
        if (prevProgram == 0 && ensureInitialized()) {
            GL20.glUseProgram(program);
            if (uTextureLoc >= 0) {
                GL20.glUniform1i(uTextureLoc, 0);
            }
            if (uLightmapLoc >= 0) {
                GL20.glUniform1i(uLightmapLoc, 1);
            }
            if (uUseLightmapLoc >= 0) {
                GL20.glUniform1i(uUseLightmapLoc, useLightmap ? 1 : 0);
            }
            if (uSoulColorLoc >= 0) {
                GL20.glUniform4f(uSoulColorLoc, soulR, soulG, soulB, 1.0F);
            }
            if (uSoulMixLoc >= 0) {
                GL20.glUniform1f(uSoulMixLoc, clampedMix);
            }
            if (uAlphaLoc >= 0) {
                GL20.glUniform1f(uAlphaLoc, clampedAlpha);
            }
            if (uFlashLoc >= 0) {
                GL20.glUniform1f(uFlashLoc, clampedFlash);
            }
            passUsedShader = true;
            return true;
        }

        applyFixedPipelineFallback(caps, soulR, soulG, soulB, clampedMix, clampedAlpha, clampedFlash);
        return false;
    }

    /**
     * Ends the active shading pass and restores all mutated OpenGL shader and texture unit states.
     */
    public static void end() {
        if (!passActive) {
            return;
        }
        try {
            if (passUsedShader && passHasOpenGl20) {
                if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != prevProgram) {
                    GL20.glUseProgram(prevProgram);
                }
            }
            if (passModifiedFixedPipeline) {
                restoreUnit(OpenGlHelper.GL_TEXTURE2, TU2_BACKUP);
                restoreUnit(OpenGlHelper.lightmapTexUnit, TU1_BACKUP);
                restoreUnit(OpenGlHelper.defaultTexUnit, TU0_BACKUP);
                GlStateManager.setActiveTexture(prevActiveTex);
            }
            if (passModifiedColor) {
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
        } finally {
            passActive = false;
            passUsedShader = false;
            passModifiedFixedPipeline = false;
            passModifiedColor = false;
        }
    }

    /**
     * Releases any compiled shader program and helper OpenGL textures.
     */
    public static void destroy() {
        if (passActive) {
            end();
        }
        destroyProgram();
        if (whiteTextureId != 0) {
            GlStateManager.deleteTexture(whiteTextureId);
            whiteTextureId = 0;
        }
        initialized = false;
    }

    private static void applyFixedPipelineFallback(
        ContextCapabilities caps,
        float soulR,
        float soulG,
        float soulB,
        float soulMix,
        float alpha,
        float flash
    ) {
        boolean hasMultitex = caps != null && (caps.OpenGL13 || caps.GL_ARB_multitexture)
            && OpenGlHelper.GL_COMBINE != 0 && OpenGlHelper.GL_TEXTURE2 != 0;
        int maxTexUnits = hasMultitex ? GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS) : 1;

        if (!hasMultitex || maxTexUnits < 3) {
            float tintR = 1.0F + (soulR - 1.0F) * soulMix;
            float tintG = 1.0F + (soulG - 1.0F) * soulMix;
            float tintB = 1.0F + (soulB - 1.0F) * soulMix;
            GlStateManager.color(tintR, tintG, tintB, alpha);
            passModifiedColor = true;
            return;
        }

        prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        passModifiedFixedPipeline = true;

        captureUnit(OpenGlHelper.defaultTexUnit, TU0_BACKUP);
        captureUnit(OpenGlHelper.lightmapTexUnit, TU1_BACKUP);
        captureUnit(OpenGlHelper.GL_TEXTURE2, TU2_BACKUP);

        int whiteTex = ensureWhiteTexture();

        if (soulMix > 0.0F) {
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.enableTexture2D();
            if (TU1_BACKUP.prevTexId <= 0) {
                forceBindTexture(whiteTex);
            }
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, OpenGlHelper.GL_INTERPOLATE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.GL_CONSTANT);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PREVIOUS);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE2_RGB, OpenGlHelper.GL_CONSTANT);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11.GL_SRC_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND2_RGB, GL11.GL_SRC_ALPHA);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.GL_PREVIOUS);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);

            float flashLift = flash * 0.65F;
            float envR = soulR + (1.0F - soulR) * flashLift;
            float envG = soulG + (1.0F - soulG) * flashLift;
            float envB = soulB + (1.0F - soulB) * flashLift;
            float mixWeight = clamp01(soulMix * 0.80F + flash * 0.20F);
            putEnvColor(envR, envG, envB, mixWeight);
        }

        // Modulate alpha on TU2 with a 1x1 white texture so armor and held-item layers also fade cleanly.
        GlStateManager.setActiveTexture(OpenGlHelper.GL_TEXTURE2);
        GlStateManager.enableTexture2D();
        forceBindTexture(whiteTex);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.GL_PREVIOUS);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, GL11.GL_MODULATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.GL_PREVIOUS);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_ALPHA, OpenGlHelper.GL_CONSTANT);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_ALPHA, GL11.GL_SRC_ALPHA);
        putEnvColor(1.0F, 1.0F, 1.0F, alpha);

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

        if (prevProgram != 0) {
            float tintR = 1.0F + (soulR - 1.0F) * soulMix;
            float tintG = 1.0F + (soulG - 1.0F) * soulMix;
            float tintB = 1.0F + (soulB - 1.0F) * soulMix;
            GlStateManager.color(tintR, tintG, tintB, alpha);
            passModifiedColor = true;
        }
    }

    private static void captureUnit(int texUnit, TexUnitBackup backup) {
        GlStateManager.setActiveTexture(texUnit);
        backup.wasEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        backup.prevTexId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        backup.envMode = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE);
        backup.combineRgb = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB);
        backup.src0Rgb = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB);
        backup.src1Rgb = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB);
        backup.src2Rgb = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE2_RGB);
        backup.op0Rgb = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB);
        backup.op1Rgb = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB);
        backup.op2Rgb = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND2_RGB);
        backup.combineAlpha = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA);
        backup.src0Alpha = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA);
        backup.src1Alpha = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_ALPHA);
        backup.src2Alpha = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE2_ALPHA);
        backup.op0Alpha = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA);
        backup.op1Alpha = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_ALPHA);
        backup.op2Alpha = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND2_ALPHA);

        backup.envColorQuery.clear();
        GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, backup.envColorQuery);
        backup.envColorR = backup.envColorQuery.get(0);
        backup.envColorG = backup.envColorQuery.get(1);
        backup.envColorB = backup.envColorQuery.get(2);
        backup.envColorA = backup.envColorQuery.get(3);
    }

    private static void restoreUnit(int texUnit, TexUnitBackup backup) {
        GlStateManager.setActiveTexture(texUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, backup.combineRgb);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, backup.src0Rgb);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, backup.src1Rgb);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE2_RGB, backup.src2Rgb);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, backup.op0Rgb);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, backup.op1Rgb);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND2_RGB, backup.op2Rgb);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, backup.combineAlpha);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, backup.src0Alpha);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_ALPHA, backup.src1Alpha);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE2_ALPHA, backup.src2Alpha);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, backup.op0Alpha);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_ALPHA, backup.op1Alpha);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND2_ALPHA, backup.op2Alpha);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, backup.envMode);
        putEnvColor(backup.envColorR, backup.envColorG, backup.envColorB, backup.envColorA);
        forceBindTexture(backup.prevTexId);
        syncTexture2D(backup.wasEnabled);
    }

    private static void forceBindTexture(int textureId) {
        GlStateManager.bindTexture(textureId);
        if (GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != textureId) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
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

    private static void putEnvColor(float r, float g, float b, float a) {
        ENV_COLOR_WRITE_BUFFER.clear();
        ENV_COLOR_WRITE_BUFFER.put(r).put(g).put(b).put(a).flip();
        GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ENV_COLOR_WRITE_BUFFER);
    }

    private static int ensureWhiteTexture() {
        if (whiteTextureId != 0) {
            return whiteTextureId;
        }
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        pixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();

        int texId = GlStateManager.generateTexture();
        int savedActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int savedBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        forceBindTexture(texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
            1, 1, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel
        );

        forceBindTexture(savedBinding);
        GlStateManager.setActiveTexture(savedActive);
        whiteTextureId = texId;
        return texId;
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
            markFailed("OpenGL 2.0 is not supported; falling back to fixed-function kill effect rendering.", null);
            return false;
        }

        try {
            String vertSource = loadShaderSource(VERT_RESOURCE);
            String fragSource = loadShaderSource(FRAG_RESOURCE);

            program = compileAndLink(vertSource, fragSource, "kill_soul");
            if (program == 0) {
                return false;
            }

            uTextureLoc = GL20.glGetUniformLocation(program, "u_Texture");
            uLightmapLoc = GL20.glGetUniformLocation(program, "u_Lightmap");
            uUseLightmapLoc = GL20.glGetUniformLocation(program, "u_UseLightmap");
            uSoulColorLoc = GL20.glGetUniformLocation(program, "u_SoulColor");
            uSoulMixLoc = GL20.glGetUniformLocation(program, "u_SoulMix");
            uAlphaLoc = GL20.glGetUniformLocation(program, "u_Alpha");
            uFlashLoc = GL20.glGetUniformLocation(program, "u_Flash");

            initialized = true;
            return true;
        } catch (Exception e) {
            markFailed("Failed to initialize GLSL 120 kill_soul shader; using fallback renderer.", e);
            return false;
        }
    }

    private static int compileAndLink(String vertSource, String fragSource, String debugName) {
        int vertShader = 0;
        int fragShader = 0;
        int linkedProgram = 0;
        try {
            vertShader = compileShader(GL20.GL_VERTEX_SHADER, vertSource, debugName + ".vsh");
            if (vertShader == 0) {
                return 0;
            }
            fragShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragSource, debugName + ".fsh");
            if (fragShader == 0) {
                return 0;
            }

            linkedProgram = GL20.glCreateProgram();
            if (linkedProgram == 0) {
                markFailed("glCreateProgram returned 0 for " + debugName + "; using fallback renderer.", null);
                return 0;
            }

            GL20.glAttachShader(linkedProgram, vertShader);
            GL20.glAttachShader(linkedProgram, fragShader);
            GL20.glLinkProgram(linkedProgram);

            if (GL20.glGetProgrami(linkedProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(linkedProgram, 1024);
                GL20.glDeleteProgram(linkedProgram);
                markFailed("Failed to link GLSL 120 program '" + debugName + "': " + log, null);
                return 0;
            }
            return linkedProgram;
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
            stream = KillSoulShader.class.getResourceAsStream(CLASSPATH_PREFIX + resourcePath);
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
        destroyProgram();
        if (!fallbackWarnLogged) {
            fallbackWarnLogged = true;
            if (cause != null) {
                HoptimizationMod.LOGGER.warn(message, cause);
            } else {
                HoptimizationMod.LOGGER.warn(message);
            }
        }
    }

    private static void destroyProgram() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
    }

    private static ContextCapabilities getCapabilities() {
        if (!Display.isCreated()) {
            return null;
        }
        return GLContext.getCapabilities();
    }

    private static float clamp01(float value) {
        if (value <= 0.0F) {
            return 0.0F;
        }
        if (value >= 1.0F) {
            return 1.0F;
        }
        return value;
    }

    private static final class TexUnitBackup {
        boolean wasEnabled;
        int prevTexId;
        int envMode;
        int combineRgb;
        int src0Rgb;
        int src1Rgb;
        int src2Rgb;
        int op0Rgb;
        int op1Rgb;
        int op2Rgb;
        int combineAlpha;
        int src0Alpha;
        int src1Alpha;
        int src2Alpha;
        int op0Alpha;
        int op1Alpha;
        int op2Alpha;
        final FloatBuffer envColorQuery = BufferUtils.createFloatBuffer(16);
        float envColorR;
        float envColorG;
        float envColorB;
        float envColorA;
    }
}
