package com.hoptimization.modules.visual.esp;

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
 * Manages the GLSL 120 monochrome posterized sketch ESP shader program with lazy loading,
 * single-warning fallback, external shaderpack bypass, and fixed-function combiner fallback.
 */
public final class EspSketchShader {
    private static final String VERT_RESOURCE = "shader/esp_sketch.vsh";
    private static final String FRAG_RESOURCE = "shader/esp_sketch.fsh";
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
    private static int uBaseColorLoc = -1;
    private static int uOpacityLoc = -1;
    private static int uPosterizeLoc = -1;
    private static int uIsOccludedLoc = -1;
    private static int uLightMixLoc = -1;

    private static int whiteTextureId = 0;

    private static boolean passActive = false;
    private static boolean passUsedShader = false;
    private static boolean passModifiedFixedPipeline = false;
    private static boolean passModifiedColor = false;
    private static boolean passHasOpenGl20 = false;
    private static int prevProgram = 0;
    private static int prevActiveTex = 0;

    private EspSketchShader() {
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
            HoptimizationMod.LOGGER.warn("EspSketchShader forced to fixed-function fallback mode.");
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
     * Begins a monochrome sketch ESP shading pass, automatically falling back to fixed-function
     * texture combiners when OpenGL 2.0 is unavailable, compilation failed, or another shader
     * program (such as OptiFine Shaders) is already bound.
     *
     * @param baseArgb   monochrome base tint color in ARGB format
     * @param opacity    overall opacity multiplier in {@code [0, 1]}
     * @param posterize  {@code true} to quantize luminance into 4 calm tonal blocks
     * @param isOccluded {@code true} when rendering the wall-occluded pass
     * @param lightMix   studio face-shade blend weight in {@code [0, 0.5]}
     * @return {@code true} if the GLSL shader program was bound, {@code false} if fallback is used
     */
    public static boolean begin(
        int baseArgb,
        float opacity,
        boolean posterize,
        boolean isOccluded,
        float lightMix
    ) {
        if (passActive) {
            end();
        }

        float baseR = ((baseArgb >>> 16) & 0xFF) / 255.0F;
        float baseG = ((baseArgb >>> 8) & 0xFF) / 255.0F;
        float baseB = (baseArgb & 0xFF) / 255.0F;
        float clampedOpacity = clamp01(opacity);
        float clampedLightMix = clamp(lightMix, 0.0F, 0.5F);

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
            if (uBaseColorLoc >= 0) {
                GL20.glUniform3f(uBaseColorLoc, baseR, baseG, baseB);
            }
            if (uOpacityLoc >= 0) {
                GL20.glUniform1f(uOpacityLoc, clampedOpacity);
            }
            if (uPosterizeLoc >= 0) {
                GL20.glUniform1f(uPosterizeLoc, posterize ? 1.0F : 0.0F);
            }
            if (uIsOccludedLoc >= 0) {
                GL20.glUniform1f(uIsOccludedLoc, isOccluded ? 1.0F : 0.0F);
            }
            if (uLightMixLoc >= 0) {
                GL20.glUniform1f(uLightMixLoc, clampedLightMix);
            }
            passUsedShader = true;
            return true;
        }

        applyFixedPipelineFallback(caps, baseR, baseG, baseB, clampedOpacity, isOccluded);
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
        float baseR,
        float baseG,
        float baseB,
        float opacity,
        boolean isOccluded
    ) {
        float tintR = clamp01(baseR * 0.86F + 0.08F);
        float tintG = clamp01(baseG * 0.86F + 0.08F);
        float tintB = clamp01(baseB * 0.86F + 0.08F);
        if (isOccluded) {
            float occR = clamp01(baseR * 0.35F + 0.06F);
            float occG = clamp01(baseG * 0.35F + 0.06F);
            float occB = clamp01(baseB * 0.35F + 0.06F);
            tintR = tintR + (occR - tintR) * 0.45F;
            tintG = tintG + (occG - tintG) * 0.45F;
            tintB = tintB + (occB - tintB) * 0.45F;
        }

        boolean hasMultitex = caps != null && (caps.OpenGL13 || caps.GL_ARB_multitexture)
            && OpenGlHelper.GL_COMBINE != 0 && OpenGlHelper.GL_TEXTURE2 != 0;
        int maxTexUnits = hasMultitex ? GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS) : 1;

        if (!hasMultitex || maxTexUnits < 3) {
            GlStateManager.color(tintR, tintG, tintB, opacity);
            passModifiedColor = true;
            return;
        }

        prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        passModifiedFixedPipeline = true;

        captureUnit(OpenGlHelper.defaultTexUnit, TU0_BACKUP);
        captureUnit(OpenGlHelper.lightmapTexUnit, TU1_BACKUP);
        captureUnit(OpenGlHelper.GL_TEXTURE2, TU2_BACKUP);

        int whiteTex = ensureWhiteTexture();

        // Tint toward monochrome on TU1 and modulate alpha on TU2 so armor and capes stay consistent.
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
        putEnvColor(tintR, tintG, tintB, isOccluded ? 0.80F : 0.68F);

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
        putEnvColor(1.0F, 1.0F, 1.0F, opacity);

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

        if (prevProgram != 0) {
            GlStateManager.color(tintR, tintG, tintB, opacity);
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
            markFailed("OpenGL 2.0 is not supported; falling back to fixed-function ESP shading.", null);
            return false;
        }

        try {
            String vertSource = loadShaderSource(VERT_RESOURCE);
            String fragSource = loadShaderSource(FRAG_RESOURCE);

            program = compileAndLink(vertSource, fragSource, "esp_sketch");
            if (program == 0) {
                return false;
            }

            uTextureLoc = GL20.glGetUniformLocation(program, "u_Texture");
            uBaseColorLoc = GL20.glGetUniformLocation(program, "u_BaseColor");
            uOpacityLoc = GL20.glGetUniformLocation(program, "u_Opacity");
            uPosterizeLoc = GL20.glGetUniformLocation(program, "u_Posterize");
            uIsOccludedLoc = GL20.glGetUniformLocation(program, "u_IsOccluded");
            uLightMixLoc = GL20.glGetUniformLocation(program, "u_LightMix");

            initialized = true;
            return true;
        } catch (Exception e) {
            markFailed("Failed to initialize GLSL 120 esp_sketch shader; using fallback renderer.", e);
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
            stream = EspSketchShader.class.getResourceAsStream(CLASSPATH_PREFIX + resourcePath);
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
        if (value <= 0.0F || Float.isNaN(value)) {
            return 0.0F;
        }
        if (value >= 1.0F) {
            return 1.0F;
        }
        return value;
    }

    private static float clamp(float value, float min, float max) {
        if (value <= min || Float.isNaN(value)) {
            return min;
        }
        if (value >= max) {
            return max;
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
