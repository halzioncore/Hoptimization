package com.hoptimization.ui.font;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义 TTF 字体渲染器：使用 Java AWT 进行 {@code (logicalSize * uiScale * 2)} 灰度抗锯齿超采样栅格化，
 * 字形按需懒加载进 {@link GlyphAtlas} 纹理图集，支持中文系统字体回退、字符串宽度 LRU 缓存、
 * 省略号截断、带 Alpha 的 ARGB 着色与 HUD 纯文本模式的 1px 柔和阴影。
 */
public final class TtfFontRenderer {

    private static final int WIDTH_CACHE_MAX_ENTRIES = 512;
    private static final int GLYPH_BORDER_PAD = 1;
    private static final int CODEPOINT_ELLIPSIS = '\u2026';
    private static final float SHADOW_OFFSET_LOGICAL = 1.0f;
    private static final float SHADOW_ALPHA_FACTOR = 0.45f;
    private static final char[] CAP_PROBE_CHARS = {'H'};

    private final FontStyle style;
    private final int rasterSizePx;
    private final float logicalPerRasterPx;
    private final float snapScale;
    private final float ascent;
    private final float descent;
    private final float lineHeight;
    private final float capHeight;

    private final Font primaryFont;
    private final Font cjkFallbackFont;
    private final Font genericFallbackFont;

    private final GlyphAtlas atlas = new GlyphAtlas();
    private final IntGlyphMap glyphMap = new IntGlyphMap(256);

    // LRU 宽度缓存：命中时不产生任何对象分配
    private final Map<String, Float> widthCache = new LinkedHashMap<String, Float>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Float> eldest) {
            return size() > WIDTH_CACHE_MAX_ENTRIES;
        }
    };

    private final FontRenderContext metricsFrc;
    private final char[] oneCharBuf = new char[1];
    private final char[] twoCharBuf = new char[2];

    // 复用离屏画布与像素缓冲区，避免按需栅格化单字形时重复分配大数组
    private BufferedImage rasterScratchImage;
    private Graphics2D rasterScratchGraphics;
    private int[] rasterScratchPixels = new int[64 * 64];

    private static final class GlyphEntry {
        final int codePoint;
        final Font chosenFont;
        final float advance;
        final float width;
        final float height;
        final float offsetX;
        final float offsetY;
        final boolean visible;
        final int boundsX;
        final int boundsY;
        final int cellWidth;
        final int cellHeight;

        boolean uploaded;
        int textureId;
        float u0;
        float v0;
        float u1;
        float v1;

        GlyphEntry(int codePoint, Font chosenFont, float advance,
                   float width, float height, float offsetX, float offsetY,
                   boolean visible, int boundsX, int boundsY, int cellWidth, int cellHeight) {
            this.codePoint = codePoint;
            this.chosenFont = chosenFont;
            this.advance = advance;
            this.width = width;
            this.height = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.visible = visible;
            this.boundsX = boundsX;
            this.boundsY = boundsY;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
        }
    }

    /**
     * 无装箱的开放寻址 {@code int -> GlyphEntry} 哈希表，杜绝中文/Unicode 字符在绘制热路径产生装箱分配。
     */
    private static final class IntGlyphMap {
        private static final int EMPTY = Integer.MIN_VALUE;

        private int[] keys;
        private GlyphEntry[] values;
        private int mask;
        private int size;
        private int resizeThreshold;

        IntGlyphMap(int initialCapacity) {
            int cap = 16;
            while (cap < initialCapacity) {
                cap <<= 1;
            }
            this.keys = new int[cap];
            Arrays.fill(this.keys, EMPTY);
            this.values = new GlyphEntry[cap];
            this.mask = cap - 1;
            this.resizeThreshold = (int) (cap * 0.65f);
        }

        GlyphEntry get(int key) {
            int idx = mix(key) & mask;
            while (true) {
                int k = keys[idx];
                if (k == key) {
                    return values[idx];
                }
                if (k == EMPTY) {
                    return null;
                }
                idx = (idx + 1) & mask;
            }
        }

        void put(int key, GlyphEntry value) {
            if (size >= resizeThreshold) {
                rehash();
            }
            int idx = mix(key) & mask;
            while (true) {
                int k = keys[idx];
                if (k == EMPTY || k == key) {
                    if (k == EMPTY) {
                        size++;
                    }
                    keys[idx] = key;
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) & mask;
            }
        }

        void clear() {
            Arrays.fill(keys, EMPTY);
            Arrays.fill(values, null);
            size = 0;
        }

        private void rehash() {
            int[] oldKeys = keys;
            GlyphEntry[] oldValues = values;
            int newCap = oldKeys.length << 1;
            keys = new int[newCap];
            Arrays.fill(keys, EMPTY);
            values = new GlyphEntry[newCap];
            mask = newCap - 1;
            resizeThreshold = (int) (newCap * 0.65f);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }

        private static int mix(int x) {
            int h = x * 0x9E3779B9;
            return h ^ (h >>> 16);
        }
    }

    /**
     * 构造特定样式与栅格化像素尺寸的字体渲染器。
     *
     * @param style 字体样式（逻辑字号、字重、字距、全大写）
     * @param rasterSizePx 超采样栅格化像素大小（{@code round(logicalSize * uiScale * 2)}）
     * @param primaryBaseFont 主拉丁字体（Inter）基础 Font
     * @param cjkFallbackFamily 已解析的 CJK 回退字体族名
     */
    TtfFontRenderer(FontStyle style, int rasterSizePx, Font primaryBaseFont, String cjkFallbackFamily) {
        this(style, rasterSizePx, rasterSizePx / (2.0f * Math.max(1.0f, style.getSize())),
                primaryBaseFont, cjkFallbackFamily);
    }

    /**
     * 构造特定样式、栅格化像素尺寸与物理像素对齐倍率的字体渲染器。
     */
    TtfFontRenderer(FontStyle style, int rasterSizePx, float uiScale,
                    Font primaryBaseFont, String cjkFallbackFamily) {
        this.style = style;
        this.rasterSizePx = Math.max(8, rasterSizePx);
        this.logicalPerRasterPx = style.getSize() / (float) this.rasterSizePx;
        this.snapScale = Math.max(0.5f, uiScale);

        this.primaryFont = primaryBaseFont.deriveFont((float) this.rasterSizePx);
        this.cjkFallbackFont = new Font(cjkFallbackFamily, style.getWeight().getAwtFallbackStyle(), this.rasterSizePx);
        this.genericFallbackFont = new Font(Font.SANS_SERIF, style.getWeight().getAwtFallbackStyle(), this.rasterSizePx);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        configureGraphics(g);
        this.metricsFrc = g.getFontRenderContext();
        FontMetrics fm = g.getFontMetrics(this.primaryFont);
        this.ascent = fm.getAscent() * this.logicalPerRasterPx;
        this.descent = fm.getDescent() * this.logicalPerRasterPx;
        this.lineHeight = (fm.getAscent() + fm.getDescent()) * this.logicalPerRasterPx;

        GlyphVector capGv = this.primaryFont.createGlyphVector(this.metricsFrc, CAP_PROBE_CHARS);
        Rectangle capBounds = capGv.getGlyphPixelBounds(0, this.metricsFrc, 0.0f, 0.0f);
        this.capHeight = capBounds.height > 0 ? capBounds.height * this.logicalPerRasterPx : this.ascent * 0.72f;
        g.dispose();

        ensureRasterScratch(64, 64);
    }

    /**
     * 绘制文本（坐标为逻辑像素，左上角对齐行框，颜色为 ARGB）。
     *
     * @param text 待绘制文本
     * @param x 起始 X（逻辑像素）
     * @param y 起始 Y（行框顶部，逻辑像素）
     * @param color ARGB 颜色（支持透明通道 0–255）
     * @return 绘制完成后的右侧 X 坐标（逻辑像素）
     */
    public float drawString(String text, float x, float y, int color) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            return x + getWidth(text);
        }
        return drawRange(text, 0, text.length(), x, y, color, false, 0, null);
    }

    /**
     * 使用默认 1px 柔和阴影绘制文本（适用于 HUD 纯文本模式）。
     *
     * @param text 待绘制文本
     * @param x 起始 X（逻辑像素）
     * @param y 起始 Y（逻辑像素）
     * @param color ARGB 前景颜色
     * @return 绘制完成后的右侧 X 坐标
     */
    public float drawStringWithShadow(String text, float x, float y, int color) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            return x + getWidth(text);
        }
        int shadowAlpha = Math.round(alpha * SHADOW_ALPHA_FACTOR);
        int shadowColor = shadowAlpha << 24;
        return drawRange(text, 0, text.length(), x, y, color, true, shadowColor, null);
    }

    /**
     * 使用自定义阴影颜色和 1px 偏移绘制文本。
     *
     * @param text 待绘制文本
     * @param x 起始 X（逻辑像素）
     * @param y 起始 Y（逻辑像素）
     * @param color ARGB 前景颜色
     * @param shadowColor ARGB 阴影颜色
     * @return 绘制完成后的右侧 X 坐标
     */
    public float drawStringWithShadow(String text, float x, float y, int color, int shadowColor) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            return x + getWidth(text);
        }
        boolean drawShadow = ((shadowColor >>> 24) & 0xFF) > 0;
        return drawRange(text, 0, text.length(), x, y, color, drawShadow, shadowColor, null);
    }

    /**
     * 绘制居中对齐的文本。
     */
    public float drawStringCentered(String text, float centerX, float y, int color) {
        if (text == null || text.isEmpty()) {
            return centerX;
        }
        float width = getWidth(text);
        return drawString(text, centerX - width * 0.5f, y, color);
    }

    /**
     * 绘制右对齐的文本（适用于非等宽数字场景的数值右对齐）。
     */
    public float drawStringRight(String text, float rightX, float y, int color) {
        if (text == null || text.isEmpty()) {
            return rightX;
        }
        float width = getWidth(text);
        return drawString(text, rightX - width, y, color);
    }

    /**
     * 超出最大宽度时自动以省略号（{@code …}）截断并绘制，热路径零字符串拼接分配。
     *
     * @param text 待绘制文本
     * @param x 起始 X（逻辑像素）
     * @param y 起始 Y（逻辑像素）
     * @param maxWidth 最大允许宽度（逻辑像素）
     * @param color ARGB 颜色
     * @return 实际绘制结束 X 坐标
     */
    public float drawStringWithEllipsis(String text, float x, float y, float maxWidth, int color) {
        return drawStringWithEllipsis(text, x, y, maxWidth, color, false);
    }

    /**
     * 超出最大宽度时自动以省略号截断并绘制，可选是否带 1px 柔和阴影。
     */
    public float drawStringWithEllipsis(String text, float x, float y, float maxWidth, int color, boolean shadow) {
        if (text == null || text.isEmpty() || maxWidth <= 0.0f) {
            return x;
        }
        float fullWidth = getWidth(text);
        if (fullWidth <= maxWidth) {
            return shadow ? drawStringWithShadow(text, x, y, color) : drawString(text, x, y, color);
        }
        GlyphEntry ellipsis = getOrMeasureGlyph(CODEPOINT_ELLIPSIS);
        if (ellipsis.advance > maxWidth) {
            return x;
        }
        int prefixEnd = findPrefixEndForEllipsis(text, maxWidth, ellipsis.advance);
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            return x + Math.min(fullWidth, maxWidth);
        }
        int shadowColor = shadow ? (Math.round(alpha * SHADOW_ALPHA_FACTOR) << 24) : 0;
        return drawRange(text, 0, prefixEnd, x, y, color, shadow, shadowColor, ellipsis);
    }

    /**
     * 若文本宽度超过 {@code maxWidth} 则截断并追加省略号（{@code …}），否则原样返回。
     *
     * @param text 输入文本
     * @param maxWidth 最大逻辑像素宽度
     * @return 原字符串或带省略号的截断结果
     */
    public String trimToWidth(String text, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0.0f) {
            return "";
        }
        if (getWidth(text) <= maxWidth) {
            return text;
        }
        GlyphEntry ellipsis = getOrMeasureGlyph(CODEPOINT_ELLIPSIS);
        if (ellipsis.advance > maxWidth) {
            return "";
        }
        int prefixEnd = findPrefixEndForEllipsis(text, maxWidth, ellipsis.advance);
        if (prefixEnd <= 0) {
            return "\u2026";
        }
        return text.substring(0, prefixEnd) + "\u2026";
    }

    /**
     * 计算文本渲染后的逻辑像素宽度（经 LRU 缓存，缓存命中零分配）。
     *
     * @param text 待测量文本
     * @return 宽度（逻辑像素）
     */
    public float getWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        Float cached = widthCache.get(text);
        if (cached != null) {
            return cached.floatValue();
        }
        float computed = measureWidthUncached(text, 0, text.length());
        widthCache.put(text, Float.valueOf(computed));
        return computed;
    }

    public FontStyle getStyle() {
        return style;
    }

    public float getHeight() {
        return lineHeight;
    }

    public float getAscent() {
        return ascent;
    }

    public float getDescent() {
        return descent;
    }

    public float getCapHeight() {
        return capHeight;
    }

    public int getRasterSizePx() {
        return rasterSizePx;
    }

    /**
     * 预热常用 ASCII 可见字符（32–126）的字形测量与图集上传（必须在渲染线程调用）。
     */
    public void preloadAscii() {
        for (int cp = 32; cp <= 126; cp++) {
            GlyphEntry entry = getOrMeasureGlyph(cp);
            if (entry.visible) {
                ensureUploaded(entry);
            }
        }
    }

    /**
     * 释放本渲染器占用的 OpenGL 图集纹理与内存缓存。
     */
    public void dispose() {
        atlas.dispose();
        glyphMap.clear();
        widthCache.clear();
        if (rasterScratchGraphics != null) {
            rasterScratchGraphics.dispose();
            rasterScratchGraphics = null;
        }
        rasterScratchImage = null;
    }

    private float measureWidthUncached(String text, int start, int end) {
        float width = 0.0f;
        float letterSpacing = style.getLetterSpacing();
        boolean uppercase = style.isUppercase();
        boolean hasPrintable = false;
        int i = start;
        while (i < end) {
            char c = text.charAt(i);
            int step = 1;
            int cp = c;
            if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text.charAt(i + 1))) {
                cp = Character.toCodePoint(c, text.charAt(i + 1));
                step = 2;
            }
            if (isPrintableCodePoint(cp)) {
                if (uppercase) {
                    cp = Character.toUpperCase(cp);
                }
                GlyphEntry glyph = getOrMeasureGlyph(cp);
                if (hasPrintable) {
                    width += letterSpacing;
                }
                width += glyph.advance;
                hasPrintable = true;
            }
            i += step;
        }
        return width;
    }

    private int findPrefixEndForEllipsis(String text, float maxWidth, float ellipsisWidth) {
        float width = 0.0f;
        float letterSpacing = style.getLetterSpacing();
        boolean uppercase = style.isUppercase();
        boolean hasPrintable = false;
        int len = text.length();
        int bestEnd = 0;
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            int step = 1;
            int cp = c;
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
                cp = Character.toCodePoint(c, text.charAt(i + 1));
                step = 2;
            }
            if (isPrintableCodePoint(cp)) {
                if (uppercase) {
                    cp = Character.toUpperCase(cp);
                }
                GlyphEntry glyph = getOrMeasureGlyph(cp);
                float nextWidth = width + (hasPrintable ? letterSpacing : 0.0f) + glyph.advance;
                if (nextWidth + letterSpacing + ellipsisWidth <= maxWidth) {
                    width = nextWidth;
                    hasPrintable = true;
                    bestEnd = i + step;
                } else {
                    break;
                }
            } else {
                bestEnd = i + step;
            }
            i += step;
        }
        return bestEnd;
    }

    private float drawRange(String text, int start, int end, float x, float y,
                            int color, boolean withShadow, int shadowColor, GlyphEntry trailingGlyph) {
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
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
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.disableAlpha();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.disableCull();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            if (withShadow) {
                drawTextPass(text, start, end, x + SHADOW_OFFSET_LOGICAL, y + SHADOW_OFFSET_LOGICAL,
                        shadowColor, trailingGlyph);
            }
            return drawTextPass(text, start, end, x, y, color, trailingGlyph);
        } finally {
            restoreGlState(wasTexture2D, wasBlend, wasAlphaTest, wasLighting, wasDepthTest, wasCull,
                    prevActiveTex, prevTex2D, prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha);
        }
    }

    private float drawTextPass(String text, int start, int end, float x, float y,
                               int color, GlyphEntry trailingGlyph) {
        int a = (color >>> 24) & 0xFF;
        if (a == 0) {
            return x + measureWidthUncached(text, start, end);
        }
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        boolean batchOpen = false;
        int boundTexId = 0;

        float penX = snapToPhysicalPixel(x);
        float baselineY = snapToPhysicalPixel(y + ascent);
        float letterSpacing = style.getLetterSpacing();
        boolean uppercase = style.isUppercase();
        boolean hasPrintable = false;

        int i = start;
        while (i < end) {
            char c = text.charAt(i);
            int step = 1;
            int cp = c;
            if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text.charAt(i + 1))) {
                cp = Character.toCodePoint(c, text.charAt(i + 1));
                step = 2;
            }
            if (isPrintableCodePoint(cp)) {
                if (uppercase) {
                    cp = Character.toUpperCase(cp);
                }
                GlyphEntry glyph = getOrMeasureGlyph(cp);
                if (hasPrintable) {
                    penX += letterSpacing;
                }
                if (glyph.visible) {
                    ensureUploaded(glyph);
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
                        emitGlyphQuad(wr, glyph, penX, baselineY, r, g, b, a);
                    }
                }
                penX += glyph.advance;
                hasPrintable = true;
            }
            i += step;
        }
        if (trailingGlyph != null) {
            if (hasPrintable) {
                penX += letterSpacing;
            }
            if (trailingGlyph.visible) {
                ensureUploaded(trailingGlyph);
                if (trailingGlyph.textureId != 0) {
                    if (trailingGlyph.textureId != boundTexId) {
                        if (batchOpen) {
                            tessellator.draw();
                            batchOpen = false;
                        }
                        GlStateManager.bindTexture(trailingGlyph.textureId);
                    }
                    if (!batchOpen) {
                        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                        batchOpen = true;
                    }
                    emitGlyphQuad(wr, trailingGlyph, penX, baselineY, r, g, b, a);
                }
            }
            penX += trailingGlyph.advance;
        }
        if (batchOpen) {
            tessellator.draw();
        }
        return penX;
    }

    private static void emitGlyphQuad(WorldRenderer wr, GlyphEntry glyph, float penX, float baselineY,
                                      int r, int g, int b, int a) {
        float x0 = penX + glyph.offsetX;
        float y0 = baselineY + glyph.offsetY;
        float x1 = x0 + glyph.width;
        float y1 = y0 + glyph.height;

        wr.pos(x0, y0, 0.0).tex(glyph.u0, glyph.v0).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, 0.0).tex(glyph.u0, glyph.v1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, 0.0).tex(glyph.u1, glyph.v1).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, 0.0).tex(glyph.u1, glyph.v0).color(r, g, b, a).endVertex();
    }

    private static void restoreGlState(boolean wasTexture2D, boolean wasBlend, boolean wasAlphaTest,
                                       boolean wasLighting, boolean wasDepthTest, boolean wasCull,
                                       int prevActiveTex, int prevTex2D,
                                       int prevBlendSrc, int prevBlendDst,
                                       int prevBlendSrcAlpha, int prevBlendDstAlpha) {
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
        if (wasLighting) {
            GlStateManager.enableLighting();
        } else {
            GlStateManager.disableLighting();
        }
        if (wasDepthTest) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
        if (wasCull) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
        GlStateManager.resetColor();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private GlyphEntry getOrMeasureGlyph(int codePoint) {
        GlyphEntry cached = glyphMap.get(codePoint);
        if (cached != null) {
            return cached;
        }
        Font chosenFont = selectFontForCodePoint(codePoint);
        int charCount = Character.toChars(codePoint, twoCharBuf, 0);
        char[] chars;
        if (charCount == 1) {
            oneCharBuf[0] = twoCharBuf[0];
            chars = oneCharBuf;
        } else {
            chars = twoCharBuf;
        }

        GlyphVector gv = chosenFont.createGlyphVector(metricsFrc, chars);
        float adv = gv.getGlyphMetrics(0).getAdvanceX() * logicalPerRasterPx;
        Rectangle pb = gv.getGlyphPixelBounds(0, metricsFrc, 0.0f, 0.0f);

        GlyphEntry entry;
        if (pb.width <= 0 || pb.height <= 0) {
            entry = new GlyphEntry(codePoint, chosenFont, adv,
                    0.0f, 0.0f, 0.0f, 0.0f, false, 0, 0, 0, 0);
        } else {
            int cellW = pb.width + GLYPH_BORDER_PAD * 2;
            int cellH = pb.height + GLYPH_BORDER_PAD * 2;
            entry = new GlyphEntry(
                    codePoint,
                    chosenFont,
                    adv,
                    cellW * logicalPerRasterPx,
                    cellH * logicalPerRasterPx,
                    (pb.x - GLYPH_BORDER_PAD) * logicalPerRasterPx,
                    (pb.y - GLYPH_BORDER_PAD) * logicalPerRasterPx,
                    true,
                    pb.x,
                    pb.y,
                    cellW,
                    cellH
            );
        }
        glyphMap.put(codePoint, entry);
        return entry;
    }

    private void ensureUploaded(GlyphEntry entry) {
        if (entry.uploaded || !entry.visible) {
            return;
        }
        entry.uploaded = true;
        int w = entry.cellWidth;
        int h = entry.cellHeight;
        ensureRasterScratch(w, h);

        rasterScratchGraphics.setComposite(AlphaComposite.Clear);
        rasterScratchGraphics.fillRect(0, 0, w, h);
        rasterScratchGraphics.setComposite(AlphaComposite.SrcOver);
        rasterScratchGraphics.setColor(Color.WHITE);
        rasterScratchGraphics.setFont(entry.chosenFont);

        int charCount = Character.toChars(entry.codePoint, twoCharBuf, 0);
        rasterScratchGraphics.drawChars(twoCharBuf, 0, charCount,
                GLYPH_BORDER_PAD - entry.boundsX, GLYPH_BORDER_PAD - entry.boundsY);

        int requiredPixels = w * h;
        if (rasterScratchPixels.length < requiredPixels) {
            rasterScratchPixels = new int[Math.max(requiredPixels, rasterScratchPixels.length * 2)];
        }
        rasterScratchImage.getRGB(0, 0, w, h, rasterScratchPixels, 0, w);
        // 统一将所有像素 RGB 置白、仅保留灰度抗锯齿 Alpha，消除线性滤波黑边
        for (int i = 0; i < requiredPixels; i++) {
            int alpha = (rasterScratchPixels[i] >>> 24) & 0xFF;
            rasterScratchPixels[i] = (alpha << 24) | 0x00FFFFFF;
        }

        GlyphAtlas.Region region = atlas.upload(rasterScratchPixels, w, h);
        if (region != null) {
            entry.textureId = region.textureId;
            entry.u0 = region.u0;
            entry.v0 = region.v0;
            entry.u1 = region.u1;
            entry.v1 = region.v1;
        }
    }

    private void ensureRasterScratch(int minWidth, int minHeight) {
        if (rasterScratchImage != null
                && rasterScratchImage.getWidth() >= minWidth
                && rasterScratchImage.getHeight() >= minHeight) {
            return;
        }
        int newW = rasterScratchImage == null ? Math.max(64, minWidth) : Math.max(rasterScratchImage.getWidth() * 2, minWidth);
        int newH = rasterScratchImage == null ? Math.max(64, minHeight) : Math.max(rasterScratchImage.getHeight() * 2, minHeight);
        if (rasterScratchGraphics != null) {
            rasterScratchGraphics.dispose();
        }
        rasterScratchImage = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        rasterScratchGraphics = rasterScratchImage.createGraphics();
        configureGraphics(rasterScratchGraphics);
    }

    private Font selectFontForCodePoint(int codePoint) {
        if (primaryFont.canDisplay(codePoint)) {
            return primaryFont;
        }
        if (cjkFallbackFont.canDisplay(codePoint)) {
            return cjkFallbackFont;
        }
        if (genericFallbackFont.canDisplay(codePoint)) {
            return genericFallbackFont;
        }
        return primaryFont;
    }

    private float snapToPhysicalPixel(float logicalCoord) {
        return Math.round(logicalCoord * snapScale) / snapScale;
    }

    private static boolean isPrintableCodePoint(int codePoint) {
        return codePoint >= 0x20 && codePoint != 0x7F;
    }

    private static void configureGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
