package com.hoptimization.ui.hud;

import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTBlendFuncSeparate;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.nio.FloatBuffer;

/**
 * Shared zero-allocation HUD rendering, string formatting, localization, and OpenGL state restoration helpers.
 */
public final class HudRenderHelper {
    private static final int SMALL_INT_LIMIT = 512;
    private static final int CPS_CACHE_LIMIT = 128;
    private static final String[] SMALL_INTS = new String[SMALL_INT_LIMIT + 1];
    private static final String[] CPS_LABELS = new String[CPS_CACHE_LIMIT + 1];

    private static final FloatBuffer COLOR_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final int SNAPSHOT_STACK_CAPACITY = 8;
    private static final GlStateSnapshot[] SNAPSHOT_STACK = new GlStateSnapshot[SNAPSHOT_STACK_CAPACITY];
    private static int snapshotDepth = 0;

    static {
        for (int i = 0; i <= SMALL_INT_LIMIT; i++) {
            SMALL_INTS[i] = Integer.toString(i);
        }
        for (int i = 0; i <= CPS_CACHE_LIMIT; i++) {
            CPS_LABELS[i] = SMALL_INTS[i] + " CPS";
        }
        for (int i = 0; i < SNAPSHOT_STACK_CAPACITY; i++) {
            SNAPSHOT_STACK[i] = new GlStateSnapshot();
        }
    }

    private HudRenderHelper() {
    }

    /**
     * Acquires a pre-allocated {@link GlStateSnapshot} from the thread-local/client stack without allocating.
     * Must be paired with {@link #releaseSnapshot()}.
     *
     * @return reusable snapshot instance
     */
    public static GlStateSnapshot acquireSnapshot() {
        if (snapshotDepth < SNAPSHOT_STACK_CAPACITY) {
            return SNAPSHOT_STACK[snapshotDepth++];
        }
        snapshotDepth++;
        return new GlStateSnapshot();
    }

    /**
     * Releases the top {@link GlStateSnapshot} acquired via {@link #acquireSnapshot()}.
     */
    public static void releaseSnapshot() {
        if (snapshotDepth > 0) {
            snapshotDepth--;
        }
    }

    /**
     * Draws a standard HUD card container using active theme tokens (no shadow per {@code 02 §11}).
     *
     * @param x      logical left X
     * @param y      logical top Y
     * @param width  logical width
     * @param height logical height
     * @param theme  active theme data
     * @param alpha  opacity multiplier in [0, 1]
     */
    public static void drawHudCard(float x, float y, float width, float height, ThemeData theme, float alpha) {
        if (width <= 0.0f || height <= 0.0f || alpha <= 0.0f) {
            return;
        }
        if (ThemeManager.isFrostedGlass()) {
            RenderUtil.drawFrostedRoundedRect(
                x, y, width, height,
                theme.radiusCard(),
                theme.frostedHudTint(),
                theme.frostedBorder(),
                UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength(),
                alpha
            );
            return;
        }
        boolean hasBorder = theme.style().isCardBorder();
        int bgColor = RenderUtil.withAlpha(theme.hudBackground(), alpha);
        int borderColor = hasBorder ? RenderUtil.withAlpha(theme.border(), alpha) : 0;
        float borderWidth = hasBorder ? UiTokens.BORDER_WIDTH : 0.0f;
        RenderUtil.drawRoundedRect(
            x, y, width, height,
            theme.radiusCard(),
            bgColor,
            borderColor,
            borderWidth
        );
    }

    /**
     * Draws text with optional 1px readable drop shadow for HUD Text style.
     *
     * @param font   target TTF font renderer
     * @param text   string to draw
     * @param x      logical left X
     * @param y      logical top Y
     * @param color  ARGB foreground color
     * @param shadow whether to draw 1px shadow
     * @return right X coordinate after drawing
     */
    public static float drawHudText(TtfFontRenderer font, String text, float x, float y, int color, boolean shadow) {
        if (font == null || text == null || text.isEmpty()) {
            return x;
        }
        return shadow ? font.drawStringWithShadow(text, x, y, color) : font.drawString(text, x, y, color);
    }

    /**
     * Draws right-aligned text with optional 1px readable drop shadow.
     *
     * @param font   target TTF font renderer
     * @param text   string to draw
     * @param rightX logical right edge X
     * @param y      logical top Y
     * @param color  ARGB foreground color
     * @param shadow whether to draw 1px shadow
     * @return right X coordinate after drawing
     */
    public static float drawHudTextRight(
        TtfFontRenderer font,
        String text,
        float rightX,
        float y,
        int color,
        boolean shadow
    ) {
        if (font == null || text == null || text.isEmpty()) {
            return rightX;
        }
        float width = font.getWidth(text);
        return drawHudText(font, text, rightX - width, y, color, shadow);
    }

    /**
     * Draws horizontally centered text with optional 1px readable drop shadow.
     *
     * @param font    target TTF font renderer
     * @param text    string to draw
     * @param centerX logical horizontal center X
     * @param y       logical top Y
     * @param color   ARGB foreground color
     * @param shadow  whether to draw 1px shadow
     * @return right X coordinate after drawing
     */
    public static float drawHudTextCentered(
        TtfFontRenderer font,
        String text,
        float centerX,
        float y,
        int color,
        boolean shadow
    ) {
        if (font == null || text == null || text.isEmpty()) {
            return centerX;
        }
        float width = font.getWidth(text);
        return drawHudText(font, text, centerX - width * 0.5f, y, color, shadow);
    }

    /**
     * Draws a crisp 64x64 Minecraft player skin face + hat layer using nearest-neighbor filtering.
     *
     * @param skin  skin texture resource location
     * @param x     logical left X
     * @param y     logical top Y
     * @param size  logical width and height
     * @param alpha opacity in [0, 1]
     */
    public static void drawPlayerHead(ResourceLocation skin, float x, float y, float size, float alpha) {
        if (skin == null || size <= 0.0f || alpha <= 0.005f) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        TextureManager texManager = mc.getTextureManager();
        if (texManager == null) {
            return;
        }

        float clampedAlpha = alpha >= 1.0f ? 1.0f : alpha;
        GlStateSnapshot snapshot = acquireSnapshot();
        snapshot.capture();
        try {
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            );
            GlStateManager.disableAlpha();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.disableCull();
            GlStateManager.color(1.0f, 1.0f, 1.0f, clampedAlpha);

            texManager.bindTexture(skin);
            int prevMinFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
            int prevMagFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            try {
                // Base face (8,8 -> 16,16 on 64x64)
                drawTexturedRegion(
                    x, y, size, size,
                    8.0f / 64.0f, 8.0f / 64.0f, 16.0f / 64.0f, 16.0f / 64.0f, clampedAlpha
                );
                // Hat overlay (40,8 -> 48,16 on 64x64)
                drawTexturedRegion(
                    x, y, size, size,
                    40.0f / 64.0f, 8.0f / 64.0f, 48.0f / 64.0f, 16.0f / 64.0f, clampedAlpha
                );
            } finally {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, prevMinFilter);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, prevMagFilter);
            }
        } finally {
            snapshot.restore();
            releaseSnapshot();
        }
    }

    /**
     * Safely renders a Minecraft {@link ItemStack} icon inside HUD overlays without leaking GL/lighting states.
     *
     * @param stack item stack to render
     * @param x     logical left X
     * @param y     logical top Y
     * @param scale icon scale relative to 16x16 standard item size
     */
    public static void drawItemStack(ItemStack stack, float x, float y, float scale) {
        if (stack == null || stack.getItem() == null || scale <= 0.0f) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        RenderItem renderItem = mc.getRenderItem();
        if (renderItem == null) {
            return;
        }

        GlStateSnapshot snapshot = acquireSnapshot();
        snapshot.capture();
        boolean wasRescaleNormal = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
        float prevZLevel = renderItem.zLevel;

        try {
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0.0f);
            GlStateManager.scale(scale, scale, 1.0f);

            GlStateManager.enableRescaleNormal();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            );
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            RenderHelper.enableGUIStandardItemLighting();

            renderItem.zLevel = 100.0f;
            renderItem.renderItemAndEffectIntoGUI(stack, 0, 0);
        } finally {
            renderItem.zLevel = prevZLevel;
            RenderHelper.disableStandardItemLighting();
            if (wasRescaleNormal) {
                GlStateManager.enableRescaleNormal();
            } else {
                GlStateManager.disableRescaleNormal();
            }
            GlStateManager.popMatrix();
            snapshot.restore();
            releaseSnapshot();
        }
    }

    /**
     * Formats an integer with zero heap allocations for values in {@code [0, 512]}.
     *
     * @param value   integer value
     * @param scratch reusable {@link StringBuilder} for values outside cache
     * @return formatted integer string
     */
    public static String formatInt(int value, StringBuilder scratch) {
        if (value >= 0 && value <= SMALL_INT_LIMIT) {
            return SMALL_INTS[value];
        }
        scratch.setLength(0);
        scratch.append(value);
        return scratch.toString();
    }

    /**
     * Returns a pre-allocated {@code "<n> CPS"} string for {@code [0, 128]}.
     *
     * @param cps     clicks per second
     * @param scratch reusable {@link StringBuilder} for values outside cache
     * @return formatted CPS sub-label
     */
    public static String formatCpsLabel(int cps, StringBuilder scratch) {
        if (cps >= 0 && cps <= CPS_CACHE_LIMIT) {
            return CPS_LABELS[cps];
        }
        scratch.setLength(0);
        scratch.append(Math.max(0, cps)).append(" CPS");
        return scratch.toString();
    }

    /**
     * Formats a non-negative tenth-scaled integer (e.g., {@code 155 -> "15.5"}) using {@code scratch}.
     *
     * @param tenths  value multiplied by 10
     * @param scratch reusable {@link StringBuilder}
     * @return formatted decimal string with one fractional digit
     */
    public static String formatTenths(int tenths, StringBuilder scratch) {
        int safe = Math.max(0, tenths);
        scratch.setLength(0);
        scratch.append(safe / 10).append('.').append(safe % 10);
        return scratch.toString();
    }

    /**
     * Resolves a localized string via {@link LanguageModule#format(String)} with a clean fallback
     * if the key has not been registered yet.
     *
     * @param key      localization key
     * @param fallback fallback text when the key is missing
     * @return localized string or fallback
     */
    public static String resolveLang(String key, String fallback) {
        if (key == null) {
            return fallback;
        }
        try {
            String formatted = LanguageModule.format(key);
            if (formatted != null && !formatted.isEmpty() && !formatted.equals(key)) {
                return formatted;
            }
        } catch (RuntimeException ignored) {
            // Fall back cleanly when called outside initialized context (e.g. unit checks).
        }
        return fallback;
    }

    /**
     * Returns the effective mod display language code without heap allocation.
     *
     * @return {@code "zh_CN"} or {@code "en_US"}
     */
    public static String getCurrentLanguageCode() {
        return LanguageModule.getEffectiveLanguageCode();
    }

    private static void drawTexturedRegion(
        float x,
        float y,
        float width,
        float height,
        float u0,
        float v0,
        float u1,
        float v1,
        float alpha
    ) {
        float x2 = x + width;
        float y2 = y + height;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        wr.pos(x, y2, 0.0D).tex(u0, v1).color(1.0f, 1.0f, 1.0f, alpha).endVertex();
        wr.pos(x2, y2, 0.0D).tex(u1, v1).color(1.0f, 1.0f, 1.0f, alpha).endVertex();
        wr.pos(x2, y, 0.0D).tex(u1, v0).color(1.0f, 1.0f, 1.0f, alpha).endVertex();
        wr.pos(x, y, 0.0D).tex(u0, v0).color(1.0f, 1.0f, 1.0f, alpha).endVertex();
        tessellator.draw();
    }

    /**
     * Reusable pre-allocated OpenGL state backup for HUD overlay rendering passes.
     */
    public static final class GlStateSnapshot {
        private boolean hasGl20;
        private int prevProgram;
        private int prevActiveTex;
        private int prevTex2D;
        private boolean wasTexture2D;
        private boolean wasBlend;
        private boolean wasAlpha;
        private boolean wasLighting;
        private boolean wasDepth;
        private boolean wasCull;
        private int prevShadeModel;
        private int prevBlendSrc;
        private int prevBlendDst;
        private int prevBlendSrcAlpha;
        private int prevBlendDstAlpha;
        private float prevR;
        private float prevG;
        private float prevB;
        private float prevA;

        /**
         * Captures active OpenGL and {@link GlStateManager} states without heap allocations.
         */
        public void capture() {
            ContextCapabilities caps = Display.isCreated() ? GLContext.getCapabilities() : null;
            this.hasGl20 = caps != null && caps.OpenGL20;
            this.prevProgram = this.hasGl20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
            this.prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            this.prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            this.wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            this.wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            this.wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            this.wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            this.wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            this.wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            this.prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);

            this.prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
            this.prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
            if (caps != null && caps.OpenGL14) {
                this.prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
                this.prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            } else if (caps != null && caps.GL_EXT_blend_func_separate) {
                this.prevBlendSrcAlpha = GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_SRC_ALPHA_EXT);
                this.prevBlendDstAlpha = GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_DST_ALPHA_EXT);
            } else {
                this.prevBlendSrcAlpha = this.prevBlendSrc;
                this.prevBlendDstAlpha = this.prevBlendDst;
            }

            COLOR_BUFFER.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR_BUFFER);
            this.prevR = COLOR_BUFFER.get(0);
            this.prevG = COLOR_BUFFER.get(1);
            this.prevB = COLOR_BUFFER.get(2);
            this.prevA = COLOR_BUFFER.get(3);
        }

        /**
         * Restores previously captured OpenGL and {@link GlStateManager} states.
         */
        public void restore() {
            if (this.hasGl20 && GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != this.prevProgram) {
                GL20.glUseProgram(this.prevProgram);
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(this.prevTex2D);
            GlStateManager.setActiveTexture(this.prevActiveTex);

            GlStateManager.shadeModel(this.prevShadeModel);
            GlStateManager.tryBlendFuncSeparate(
                this.prevBlendSrc, this.prevBlendDst,
                this.prevBlendSrcAlpha, this.prevBlendDstAlpha
            );

            if (this.wasCull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (this.wasDepth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            if (this.wasLighting) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
            if (this.wasAlpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            if (this.wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (this.wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }

            GlStateManager.resetColor();
            GlStateManager.color(this.prevR, this.prevG, this.prevB, this.prevA);
        }
    }
}
