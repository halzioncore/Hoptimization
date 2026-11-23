package com.hoptimization.ui.font;

import com.hoptimization.modules.visual.ModernFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/**
 * High-resolution TTF bridge backing {@link com.hoptimization.mixin.render.MixinFontRenderer}.
 * Supports full Minecraft {@code §} formatting codes ({@code §0-§f}, {@code §l}, {@code §o}, {@code §n},
 * {@code §m}, {@code §k}, {@code §r}), CJK fallback glyphs, exact metrics/wrapping, and 3D depth-safe rendering.
 */
public final class VanillaTtfFontBridge {
    private static final float BASE_LOGICAL_SIZE = 8.8f;
    private static final float VANILLA_LINE_HEIGHT = 9.0f;
    private static final float ITALIC_SHEAR_RATIO = 0.22f;
    private static final char SECTION_SIGN = '\u00A7';
    private static final String FORMAT_CHARS = "0123456789abcdefklmnor";
    private static final int[] MC_COLORS = new int[32];

    static {
        for (int i = 0; i < 32; i++) {
            int j = (i >> 3 & 1) * 85;
            int r = (i >> 2 & 1) * 170 + j;
            int g = (i >> 1 & 1) * 170 + j;
            int b = (i & 1) * 170 + j;
            if (i == 6) {
                r += 85;
            }
            if (i >= 16) {
                r /= 4;
                g /= 4;
                b /= 4;
            }
            MC_COLORS[i] = (r & 255) << 16 | (g & 255) << 8 | (b & 255);
        }
    }

    private static int obfuscateSeed = 0x1337BEEF;

    private VanillaTtfFontBridge() {
    }

    /**
     * Renders a Minecraft-formatted string at {@code (x, y)} with high-resolution supersampled TTF glyphs.
     * Preserves {@code GL_DEPTH_TEST} and {@code GL_CULL_FACE} so 3D signs and nametags render accurately.
     *
     * @param text       formatted string
     * @param x          left X coordinate in active GL space
     * @param y          top Y coordinate in active GL space
     * @param color      RGB or ARGB color (applies vanilla opaque fallback when alpha bits are zero)
     * @param dropShadow whether vanilla requested a drop shadow
     * @return integer end X coordinate matching {@code FontRenderer.drawString}
     */
    public static int drawString(String text, float x, float y, int color, boolean dropShadow) {
        if (text == null || text.isEmpty()) {
            return (int) x;
        }
        int resolvedColor = color;
        if ((resolvedColor & 0xFC000000) == 0) {
            resolvedColor |= 0xFF000000;
        }
        boolean forceContainerShadow = false;
        if (com.hoptimization.ui.container.ModernContainerRenderer.isRenderingForeground()
            && (resolvedColor & 0x00FFFFFF) == 0x00404040) {
            resolvedColor = com.hoptimization.ui.theme.ThemeManager.current().accent();
            forceContainerShadow = true;
        }
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);

        // Vanilla callers like GuiIngame.renderScoreboard draw with GL_BLEND disabled and pass
        // dummy alpha values (e.g. 553648127 == 0x20FFFFFF), expecting fully opaque text.
        if (!wasBlend) {
            resolvedColor |= 0xFF000000;
        }
        int baseAlpha = (resolvedColor >>> 24) & 0xFF;
        if (baseAlpha <= 3) {
            return (int) Math.ceil(x + getStringWidthFloat(text));
        }

        TtfFontRenderer normalFont = resolveRenderer(false);
        TtfFontRenderer boldFont = resolveRenderer(true);
        boolean simulateExtraBold = (ModernFont.getConfiguredWeight() == FontWeight.SEMIBOLD);

        float verticalOffset = (VANILLA_LINE_HEIGHT - normalFont.getHeight()) * 0.5f - 0.35f;
        float drawY = y + verticalOffset;

        String shadowMode = ModernFont.getConfiguredShadowMode();
        boolean drawShadow = (dropShadow || forceContainerShadow) && !ModernFont.SHADOW_OFF.equals(shadowMode);
        boolean modernShadow = ModernFont.SHADOW_MODERN.equals(shadowMode);

        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        int prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            );
            GlStateManager.disableAlpha();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            if (drawShadow) {
                float shadowOffset = modernShadow ? 0.65f : 1.0f;
                int shadowBaseColor = computeShadowColor(resolvedColor, modernShadow);
                renderFormattedPass(
                    text,
                    x + shadowOffset,
                    drawY + shadowOffset,
                    shadowBaseColor,
                    true,
                    modernShadow,
                    normalFont,
                    boldFont,
                    simulateExtraBold
                );
            }

            float endX = renderFormattedPass(
                text,
                x,
                drawY,
                resolvedColor,
                false,
                modernShadow,
                normalFont,
                boldFont,
                simulateExtraBold
            );
            return (int) Math.ceil(endX);
        } finally {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(prevTex2D);
            GlStateManager.setActiveTexture(prevActiveTex);
            GlStateManager.tryBlendFuncSeparate(prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha);
            if (wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (wasAlphaTest) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    /**
     * Measures the pixel width of a formatted string (ignoring {@code §} format codes).
     *
     * @param text string to measure
     * @return width in vanilla GUI pixels
     */
    public static int getStringWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(getStringWidthFloat(text));
    }

    /**
     * Measures the pixel width of a single character in the active normal TTF font.
     *
     * @param character character to measure
     * @return width in vanilla GUI pixels
     */
    public static int getCharWidth(char character) {
        if (character == SECTION_SIGN) {
            return -1;
        }
        if (character == '\n') {
            return 0;
        }
        TtfFontRenderer font = resolveRenderer(false);
        TtfFontRenderer.GlyphEntry glyph = font.getOrMeasureGlyph(character);
        return (int) Math.ceil(glyph.advance);
    }

    /**
     * Trims a formatted string to fit within {@code maxWidth} pixels, supporting both forward and reverse modes.
     *
     * @param text     source string
     * @param maxWidth maximum allowed width in vanilla GUI pixels
     * @param reverse  {@code true} to trim from the end backwards
     * @return trimmed substring
     */
    public static String trimStringToWidth(String text, int maxWidth, boolean reverse) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        TtfFontRenderer normalFont = resolveRenderer(false);
        TtfFontRenderer boldFont = resolveRenderer(true);
        boolean simulateExtraBold = (ModernFont.getConfiguredWeight() == FontWeight.SEMIBOLD);

        StringBuilder out = new StringBuilder();
        float accWidth = 0.0f;
        int start = reverse ? text.length() - 1 : 0;
        int step = reverse ? -1 : 1;
        boolean bold = false;
        boolean skipNext = false;

        for (int i = start; i >= 0 && i < text.length() && accWidth < maxWidth; i += step) {
            char c = text.charAt(i);
            if (skipNext) {
                skipNext = false;
                char lower = Character.toLowerCase(c);
                if (lower == 'l') {
                    bold = true;
                } else if (lower == 'r' || (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')) {
                    bold = false;
                }
            } else if (c == SECTION_SIGN) {
                skipNext = true;
            } else {
                TtfFontRenderer activeFont = bold ? boldFont : normalFont;
                float charW = activeFont.getOrMeasureGlyph(c).advance + (bold && simulateExtraBold ? 0.45f : 0.0f);
                if (accWidth + charW > maxWidth) {
                    break;
                }
                accWidth += charW;
            }

            if (reverse) {
                out.insert(0, c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Measures a Minecraft {@code §}-formatted string at an explicit logical size and UI scale.
     *
     * @param text        formatted text
     * @param logicalSize logical font size in HUD units
     * @param uiScale     active UI scale factor for high-DPI rasterization
     * @return logical width
     */
    public static float measureFormattedUiWidth(String text, float logicalSize, float uiScale) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        float rasterScale = Math.max(1.0f, uiScale);
        TtfFontRenderer normalFont = FontManager.getInstance().getRenderer(FontStyle.of(logicalSize, FontWeight.MEDIUM), rasterScale);
        TtfFontRenderer boldFont = FontManager.getInstance().getRenderer(FontStyle.of(logicalSize, FontWeight.SEMIBOLD), rasterScale);
        return measureFormattedWidthWithFonts(text, normalFont, boldFont, false);
    }

    /**
     * Renders a Minecraft {@code §}-formatted string in HUD logical coordinates at an explicit logical size and UI scale.
     *
     * @param text         formatted text
     * @param x            logical left X
     * @param y            logical top Y
     * @param defaultColor default ARGB color before any {@code §} color code
     * @param shadow       whether to draw a soft drop shadow
     * @param logicalSize  logical font size in HUD units
     * @param uiScale      active UI scale factor
     * @return logical right X after drawing
     */
    public static float drawFormattedUiString(
        String text,
        float x,
        float y,
        int defaultColor,
        boolean shadow,
        float logicalSize,
        float uiScale
    ) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        int alpha = (defaultColor >>> 24) & 0xFF;
        if (alpha == 0) {
            return x + measureFormattedUiWidth(text, logicalSize, uiScale);
        }
        float rasterScale = Math.max(1.0f, uiScale);
        TtfFontRenderer normalFont = FontManager.getInstance().getRenderer(FontStyle.of(logicalSize, FontWeight.MEDIUM), rasterScale);
        TtfFontRenderer boldFont = FontManager.getInstance().getRenderer(FontStyle.of(logicalSize, FontWeight.SEMIBOLD), rasterScale);

        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        int prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            );
            GlStateManager.disableAlpha();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            if (shadow) {
                int shadowColor = computeShadowColor(defaultColor, true);
                renderFormattedPass(
                    text,
                    x + 0.65f,
                    y + 0.65f,
                    shadowColor,
                    true,
                    true,
                    normalFont,
                    boldFont,
                    false
                );
            }
            return renderFormattedPass(
                text,
                x,
                y,
                defaultColor,
                false,
                true,
                normalFont,
                boldFont,
                false
            );
        } finally {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(prevTex2D);
            GlStateManager.setActiveTexture(prevActiveTex);
            GlStateManager.tryBlendFuncSeparate(prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha);
            if (wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (wasAlphaTest) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static float getStringWidthFloat(String text) {
        TtfFontRenderer normalFont = resolveRenderer(false);
        TtfFontRenderer boldFont = resolveRenderer(true);
        boolean simulateExtraBold = (ModernFont.getConfiguredWeight() == FontWeight.SEMIBOLD);
        return measureFormattedWidthWithFonts(text, normalFont, boldFont, simulateExtraBold);
    }

    private static float measureFormattedWidthWithFonts(
        String text,
        TtfFontRenderer normalFont,
        TtfFontRenderer boldFont,
        boolean simulateExtraBold
    ) {

        float width = 0.0f;
        boolean bold = false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < len) {
                int fmtIdx = FORMAT_CHARS.indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (fmtIdx >= 0) {
                    if (fmtIdx < 16 || fmtIdx == 21) {
                        bold = false;
                    } else if (fmtIdx == 17) {
                        bold = true;
                    }
                    i++;
                    continue;
                }
            }
            int cp = c;
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
                cp = Character.toCodePoint(c, text.charAt(i + 1));
                i++;
            }
            if (cp < 0x20 || cp == 0x7F) {
                continue;
            }
            TtfFontRenderer activeFont = bold ? boldFont : normalFont;
            width += activeFont.getOrMeasureGlyph(cp).advance + (bold && simulateExtraBold ? 0.45f : 0.0f);
        }
        return width;
    }

    private static float renderFormattedPass(
        String text,
        float startX,
        float y,
        int baseColor,
        boolean shadowPass,
        boolean modernShadow,
        TtfFontRenderer normalFont,
        TtfFontRenderer boldFont,
        boolean simulateExtraBold
    ) {
        int baseAlpha = (baseColor >>> 24) & 0xFF;
        int curColor = baseColor;
        int r = (curColor >>> 16) & 0xFF;
        int g = (curColor >>> 8) & 0xFF;
        int b = curColor & 0xFF;
        int a = baseAlpha;

        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strikethrough = false;
        boolean obfuscated = false;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        boolean batchOpen = false;
        int boundTexId = 0;

        float penX = normalFont.snapToPhysicalPixel(startX);
        float baselineY = normalFont.snapToPhysicalPixel(y + normalFont.getAscent());
        float italicShear = normalFont.getCapHeight() * ITALIC_SHEAR_RATIO;
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < len) {
                int fmtIdx = FORMAT_CHARS.indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (fmtIdx >= 0) {
                    if (fmtIdx < 16) {
                        bold = false;
                        italic = false;
                        underline = false;
                        strikethrough = false;
                        obfuscated = false;
                        int rgb = MC_COLORS[shadowPass && !modernShadow ? (fmtIdx + 16) : fmtIdx];
                        if (shadowPass && modernShadow) {
                            curColor = (a << 24);
                        } else {
                            curColor = (a << 24) | (rgb & 0x00FFFFFF);
                        }
                        r = (curColor >>> 16) & 0xFF;
                        g = (curColor >>> 8) & 0xFF;
                        b = curColor & 0xFF;
                    } else if (fmtIdx == 16) {
                        obfuscated = true;
                    } else if (fmtIdx == 17) {
                        bold = true;
                    } else if (fmtIdx == 18) {
                        strikethrough = true;
                    } else if (fmtIdx == 19) {
                        underline = true;
                    } else if (fmtIdx == 20) {
                        italic = true;
                    } else {
                        bold = false;
                        italic = false;
                        underline = false;
                        strikethrough = false;
                        obfuscated = false;
                        curColor = baseColor;
                        r = (curColor >>> 16) & 0xFF;
                        g = (curColor >>> 8) & 0xFF;
                        b = curColor & 0xFF;
                    }
                    i++;
                    continue;
                }
            }

            int cp = c;
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
                cp = Character.toCodePoint(c, text.charAt(i + 1));
                i++;
            }
            if (cp < 0x20 || cp == 0x7F) {
                continue;
            }

            TtfFontRenderer activeFont = bold ? boldFont : normalFont;
            int drawCp = cp;
            if (obfuscated && cp > 0x20) {
                obfuscateSeed = obfuscateSeed * 1664525 + 1013904223;
                drawCp = 33 + ((obfuscateSeed >>> 16) % 93);
            }

            TtfFontRenderer.GlyphEntry glyph = activeFont.getOrMeasureGlyph(drawCp);
            float advance = glyph.advance + (bold && simulateExtraBold ? 0.45f : 0.0f);

            if (glyph.visible) {
                activeFont.ensureUploaded(glyph);
                if (glyph.textureId != 0) {
                    if (glyph.textureId != boundTexId) {
                        if (batchOpen) {
                            tessellator.draw();
                            batchOpen = false;
                        }
                        GlStateManager.bindTexture(glyph.textureId);
                        boundTexId = glyph.textureId;
                    }
                    if (!batchOpen) {
                        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                        batchOpen = true;
                    }
                    float shear = italic ? italicShear : 0.0f;
                    emitGlyphQuad(wr, glyph, penX, baselineY, shear, r, g, b, a);
                    if (bold && simulateExtraBold) {
                        emitGlyphQuad(wr, glyph, penX + 0.45f, baselineY, shear, r, g, b, a);
                    }
                }
            }

            if (strikethrough || underline) {
                if (batchOpen) {
                    tessellator.draw();
                    batchOpen = false;
                }
                GlStateManager.disableTexture2D();
                if (strikethrough) {
                    float strikeY = baselineY - normalFont.getCapHeight() * 0.42f;
                    drawDecorationBar(tessellator, wr, penX, strikeY, advance, 0.75f, r, g, b, a);
                }
                if (underline) {
                    float underY = baselineY + 1.0f;
                    drawDecorationBar(tessellator, wr, penX, underY, advance, 0.75f, r, g, b, a);
                }
                GlStateManager.enableTexture2D();
            }

            penX += advance;
        }

        if (batchOpen) {
            tessellator.draw();
        }
        return penX;
    }

    private static void emitGlyphQuad(
        WorldRenderer wr,
        TtfFontRenderer.GlyphEntry glyph,
        float penX,
        float baselineY,
        float italicShear,
        int r,
        int g,
        int b,
        int a
    ) {
        float x0 = penX + glyph.offsetX;
        float y0 = baselineY + glyph.offsetY;
        float x1 = x0 + glyph.width;
        float y1 = y0 + glyph.height;

        wr.pos(x0 + italicShear, y0, 0.0D).tex(glyph.u0, glyph.v0).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, 0.0D).tex(glyph.u0, glyph.v1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, 0.0D).tex(glyph.u1, glyph.v1).color(r, g, b, a).endVertex();
        wr.pos(x1 + italicShear, y0, 0.0D).tex(glyph.u1, glyph.v0).color(r, g, b, a).endVertex();
    }

    private static void drawDecorationBar(
        Tessellator tessellator,
        WorldRenderer wr,
        float x,
        float y,
        float width,
        float height,
        int r,
        int g,
        int b,
        int a
    ) {
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x, y + height, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x + width, y + height, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x + width, y, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x, y, 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    private static int computeShadowColor(int argb, boolean modernShadow) {
        int a = (argb >>> 24) & 0xFF;
        if (modernShadow) {
            int shadowA = Math.round(a * 0.42f);
            return (shadowA << 24);
        }
        int r = ((argb >>> 16) & 0xFF) / 4;
        int g = ((argb >>> 8) & 0xFF) / 4;
        int b = (argb & 0xFF) / 4;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static TtfFontRenderer resolveRenderer(boolean bold) {
        FontWeight baseWeight = ModernFont.getConfiguredWeight();
        FontWeight targetWeight = bold ? FontWeight.SEMIBOLD : baseWeight;
        float logicalSize = BASE_LOGICAL_SIZE * ModernFont.getConfiguredScale();
        FontStyle style = FontStyle.of(logicalSize, targetWeight);
        float rasterScale = resolvePhysicalScale();
        return FontManager.getInstance().getRenderer(style, rasterScale);
    }

    private static float resolvePhysicalScale() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && Display.isCreated()) {
            ScaledResolution sr = new ScaledResolution(mc);
            // Match exact physical pixel scale of Minecraft's active GUI scale for 1:1 hinted sharpness.
            return Math.max(1.0f, (float) sr.getScaleFactor());
        }
        return 2.0f;
    }
}
