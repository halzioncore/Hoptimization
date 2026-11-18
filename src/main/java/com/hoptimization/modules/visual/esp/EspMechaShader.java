package com.hoptimization.modules.visual.esp;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.Minecraft;
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
 * Manages the GLSL 120 mech-cockpit ESP shader program with lazy initialization,
 * single-warning fallback, and automatic bypass when external shaderpacks are bound.
 */
public final class EspMechaShader {
    private static final String VERT_RESOURCE = "shader/esp_mecha.vsh";
    private static final String FRAG_RESOURCE = "shader/esp_mecha.fsh";
    private static final String CLASSPATH_PREFIX = "/assets/" + HoptimizationMod.MODID + "/";

    private static volatile boolean forceFallback = false;
    private static boolean initialized = false;
    private static boolean shaderFailed = false;
    private static boolean fallbackWarnLogged = false;

    private static int program = 0;
    private static int uTimeLoc = -1;
    private static int uScanlineStrengthLoc = -1;
    private static int uFresnelStrengthLoc = -1;
    private static int uShadeContrastLoc = -1;

    private static boolean passActive = false;
    private static boolean passUsedShader = false;
    private static boolean passHasOpenGl20 = false;
    private static int prevProgram = 0;

    private EspMechaShader() {
    }

    /**
     * Enables or disables forced fixed-function fallback mode.
     *
     * @param force {@code true} to bypass the GLSL shader
     */
    public static void setForceFallback(boolean force) {
        forceFallback = force;
        if (force && !fallbackWarnLogged) {
            fallbackWarnLogged = true;
            HoptimizationMod.LOGGER.warn("EspMechaShader forced to fixed-function fallback mode.");
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
     * Begins a mech-cockpit shading pass. Automatically bypasses when OpenGL 2.0 is unsupported,
     * shader compilation failed, or another shader program (e.g., OptiFine Shaders) is active.
     *
     * @param timeSeconds      animation time in seconds
     * @param scanlineStrength scanline intensity in {@code [0, 1]}
     * @param fresnelStrength  Fresnel rim intensity in {@code [0, 1]}
     * @param shadeContrast    two-tone facet shade contrast in {@code [0, 0.45]}
     * @return {@code true} if the custom GLSL program is bound, {@code false} for fixed-pipeline fallback
     */
    public static boolean begin(
        float timeSeconds,
        float scanlineStrength,
        float fresnelStrength,
        float shadeContrast
    ) {
        if (passActive) {
            end();
        }

        ContextCapabilities caps = getCapabilities();
        passHasOpenGl20 = caps != null && caps.OpenGL20;
        prevProgram = passHasOpenGl20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        passActive = true;
        passUsedShader = false;

        // Bypass custom GLSL program when an external shaderpack (e.g., OptiFine) is active.
        if (prevProgram == 0 && ensureInitialized()) {
            GL20.glUseProgram(program);
            if (uTimeLoc >= 0) {
                GL20.glUniform1f(uTimeLoc, timeSeconds);
            }
            if (uScanlineStrengthLoc >= 0) {
                GL20.glUniform1f(uScanlineStrengthLoc, scanlineStrength);
            }
            if (uFresnelStrengthLoc >= 0) {
                GL20.glUniform1f(uFresnelStrengthLoc, fresnelStrength);
            }
            if (uShadeContrastLoc >= 0) {
                GL20.glUniform1f(uShadeContrastLoc, shadeContrast);
            }
            passUsedShader = true;
            return true;
        }

        return false;
    }

    /**
     * Ends the active shading pass and restores {@code GL_CURRENT_PROGRAM} if modified.
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
        } finally {
            passActive = false;
            passUsedShader = false;
        }
    }

    /**
     * Releases any compiled shader program resources.
     */
    public static void destroy() {
        if (passActive) {
            end();
        }
        destroyProgram();
        initialized = false;
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
            markFailed("OpenGL 2.0 is not supported; falling back to fixed-function ESP facet shading.", null);
            return false;
        }

        try {
            String vertSource = loadShaderSource(VERT_RESOURCE);
            String fragSource = loadShaderSource(FRAG_RESOURCE);

            program = compileAndLink(vertSource, fragSource, "esp_mecha");
            if (program == 0) {
                return false;
            }

            uTimeLoc = GL20.glGetUniformLocation(program, "u_Time");
            uScanlineStrengthLoc = GL20.glGetUniformLocation(program, "u_ScanlineStrength");
            uFresnelStrengthLoc = GL20.glGetUniformLocation(program, "u_FresnelStrength");
            uShadeContrastLoc = GL20.glGetUniformLocation(program, "u_ShadeContrast");

            initialized = true;
            return true;
        } catch (Exception e) {
            markFailed("Failed to initialize GLSL 120 esp_mecha shader; using fallback renderer.", e);
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
            stream = EspMechaShader.class.getResourceAsStream(CLASSPATH_PREFIX + resourcePath);
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
}
