package com.hoptimization.ui.font;

import com.hoptimization.HoptimizationMod;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 字体管理器：负责加载内置 Inter 静态 TTF 字体、按顺序解析中文系统回退字体、
 * 并按 {@code (FontStyle, rasterPx)} 缓存和复用 {@link TtfFontRenderer} 实例。
 */
public final class FontManager {

    private static final String FONT_RESOURCE_PREFIX = "/assets/hoptimization/font/";
    private static final String[] CJK_FALLBACK_ORDER = {
            "Microsoft YaHei UI",
            "PingFang SC",
            "Noto Sans CJK SC",
            "SansSerif"
    };
    private static final String[] CRISP_LATIN_ORDER = {
            "Segoe UI",
            "Microsoft YaHei UI",
            "SF Pro Text",
            "PingFang SC",
            "Noto Sans"
    };
    private static final int MAX_RENDERER_CACHE = 24;
    private static final float MIN_UI_SCALE = 0.15f;
    private static final float MAX_UI_SCALE = 5.0f;
    private static final int MAX_RASTER_PX = 96;
    private static final FontToken[] TOKENS = FontToken.values();

    private static final FontManager INSTANCE = new FontManager();

    public enum FontFamilyMode {
        CRISP_SYSTEM,
        GOOGLE_SANS,
        INTER
    }

    private final Map<FontWeight, Font> loadedInterFonts = new EnumMap<FontWeight, Font>(FontWeight.class);
    private final Map<FontWeight, Font> loadedGoogleSansFonts = new EnumMap<FontWeight, Font>(FontWeight.class);
    private final Map<FontWeight, Font> loadedSystemFonts = new EnumMap<FontWeight, Font>(FontWeight.class);
    private final TtfFontRenderer[] tokenRenderers = new TtfFontRenderer[TOKENS.length];

    // 限制同时保活的图集数量，避免连续拖拽缩放窗口时无限制创建 GL 纹理
    private final Map<RendererKey, TtfFontRenderer> rendererCache =
            new LinkedHashMap<RendererKey, TtfFontRenderer>(16, 0.75f, true);

    private String cjkFallbackFamily;
    private String crispLatinFamily;
    private volatile FontFamilyMode fontFamilyMode = FontFamilyMode.CRISP_SYSTEM;
    private boolean initialized = false;
    private float uiScale = 1.0f;

    private static final class RendererKey {
        final FontStyle style;
        final int rasterPx;

        RendererKey(FontStyle style, int rasterPx) {
            this.style = style;
            this.rasterPx = rasterPx;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RendererKey)) {
                return false;
            }
            RendererKey other = (RendererKey) obj;
            return rasterPx == other.rasterPx && style.equals(other.style);
        }

        @Override
        public int hashCode() {
            return 31 * style.hashCode() + rasterPx;
        }
    }

    private FontManager() {
    }

    /**
     * 获取全局单例。
     *
     * @return {@link FontManager} 实例
     */
    public static FontManager getInstance() {
        return INSTANCE;
    }

    /**
     * 按规范 token 获取当前 UI 缩放对应的字体渲染器（O(1) 数组读取，零分配）。
     *
     * @param token 字体阶梯 token
     * @return 对应的 {@link TtfFontRenderer}
     */
    public static TtfFontRenderer get(FontToken token) {
        return INSTANCE.getRenderer(token);
    }

    /**
     * 按自定义样式获取当前 UI 缩放对应的字体渲染器。
     *
     * @param style 字体样式
     * @return 对应的 {@link TtfFontRenderer}
     */
    public static TtfFontRenderer get(FontStyle style) {
        return INSTANCE.getRenderer(style);
    }

    /**
     * 更新全局 UI 缩放倍率（用于 {@code logicalSize * uiScale * 2} 超采样栅格化）。
     *
     * @param uiScale 当前 UI 缩放系数
     */
    public static void setUiScale(float uiScale) {
        INSTANCE.updateUiScale(uiScale);
    }

    /**
     * 获取当前配置的 UI 缩放倍率。
     *
     * @return UI 缩放倍率
     */
    public static float getUiScale() {
        return INSTANCE.uiScale;
    }

    /**
     * Switches between native hinted crisp system fonts (e.g. Segoe UI + Microsoft YaHei UI) and bundled Inter.
     *
     * @param preferSystem {@code true} for crisp hinted system font, {@code false} for bundled Inter
     */
    public static void setPreferCrispSystemFont(boolean preferSystem) {
        setFontFamilyMode(preferSystem ? FontFamilyMode.CRISP_SYSTEM : FontFamilyMode.INTER);
    }

    public static boolean isPreferCrispSystemFont() {
        return INSTANCE.fontFamilyMode == FontFamilyMode.CRISP_SYSTEM;
    }

    public static void setFontFamilyMode(FontFamilyMode mode) {
        INSTANCE.updateFontFamilyMode(mode);
    }

    public static FontFamilyMode getFontFamilyMode() {
        return INSTANCE.fontFamilyMode;
    }

    public void updateFontFamilyMode(FontFamilyMode mode) {
        FontFamilyMode resolved = mode != null ? mode : FontFamilyMode.CRISP_SYSTEM;
        if (this.fontFamilyMode == resolved) {
            return;
        }
        this.fontFamilyMode = resolved;
        if (initialized) {
            disposeAll();
            refreshTokenRenderers();
        }
    }

    /**
     * 释放所有字体图集纹理与缓存（应在关闭客户端或重载资源时于渲染线程调用）。
     */
    public static void dispose() {
        INSTANCE.disposeAll();
    }

    public static TtfFontRenderer title() {
        return get(FontToken.TITLE);
    }

    public static TtfFontRenderer body() {
        return get(FontToken.BODY);
    }

    public static TtfFontRenderer bodyRegular() {
        return get(FontToken.BODY_REGULAR);
    }

    public static TtfFontRenderer caption() {
        return get(FontToken.CAPTION);
    }

    public static TtfFontRenderer overline() {
        return get(FontToken.OVERLINE);
    }

    /**
     * 获取指定 token 在当前 UI 缩放下的渲染器。
     *
     * @param token 字体阶梯 token
     * @return {@link TtfFontRenderer}
     */
    public TtfFontRenderer getRenderer(FontToken token) {
        ensureInitialized();
        FontToken resolved = token != null ? token : FontToken.BODY;
        TtfFontRenderer cached = tokenRenderers[resolved.ordinal()];
        if (cached == null) {
            cached = getRenderer(resolved.getStyle(), uiScale);
            tokenRenderers[resolved.ordinal()] = cached;
        }
        return cached;
    }

    /**
     * 获取指定样式在当前 UI 缩放下的渲染器（与默认 token 样式一致时走零分配快速路径）。
     *
     * @param style 字体样式
     * @return {@link TtfFontRenderer}
     */
    public TtfFontRenderer getRenderer(FontStyle style) {
        if (style != null) {
            for (int i = 0; i < TOKENS.length; i++) {
                if (TOKENS[i].getStyle().equals(style)) {
                    return getRenderer(TOKENS[i]);
                }
            }
        }
        return getRenderer(style, uiScale);
    }

    /**
     * 获取指定样式在指定 UI 缩放倍率下的渲染器。
     *
     * @param style 字体样式
     * @param targetUiScale UI 缩放倍率
     * @return {@link TtfFontRenderer}
     */
    public TtfFontRenderer getRenderer(FontStyle style, float targetUiScale) {
        ensureInitialized();
        FontStyle resolvedStyle = style != null ? style : FontStyle.BODY;
        float clampedScale = clampScale(targetUiScale);
        // Exact 1:1 physical-pixel rasterization preserves crisp TrueType hinting on 1080p monitors.
        int rasterPx = Math.min(MAX_RASTER_PX, Math.max(8, Math.round(resolvedStyle.getSize() * clampedScale)));
        RendererKey key = new RendererKey(resolvedStyle, rasterPx);
        TtfFontRenderer cached = rendererCache.get(key);
        if (cached != null) {
            return cached;
        }
        Font baseFont;
        if (fontFamilyMode == FontFamilyMode.GOOGLE_SANS) {
            baseFont = loadedGoogleSansFonts.get(resolvedStyle.getWeight());
        } else if (fontFamilyMode == FontFamilyMode.CRISP_SYSTEM) {
            baseFont = loadedSystemFonts.get(resolvedStyle.getWeight());
        } else {
            baseFont = loadedInterFonts.get(resolvedStyle.getWeight());
        }
        if (baseFont == null) {
            baseFont = loadedInterFonts.get(resolvedStyle.getWeight());
        }
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, resolvedStyle.getWeight().getAwtFallbackStyle(), 16);
        }
        TtfFontRenderer created = new TtfFontRenderer(resolvedStyle, rasterPx, clampedScale,
                baseFont, cjkFallbackFamily);
        rendererCache.put(key, created);
        evictOldRenderersIfNeeded();
        return created;
    }

    private void evictOldRenderersIfNeeded() {
        if (rendererCache.size() <= MAX_RENDERER_CACHE) {
            return;
        }
        Iterator<Map.Entry<RendererKey, TtfFontRenderer>> it = rendererCache.entrySet().iterator();
        while (it.hasNext() && rendererCache.size() > MAX_RENDERER_CACHE) {
            Map.Entry<RendererKey, TtfFontRenderer> entry = it.next();
            TtfFontRenderer candidate = entry.getValue();
            if (candidate != null && !isActiveTokenRenderer(candidate)) {
                candidate.dispose();
                it.remove();
            }
        }
    }

    /**
     * 更新当前 UI 缩放倍率，并刷新五个内置字体 token 的快速索引槽位。
     *
     * @param newScale 新的 UI 缩放系数
     */
    public void updateUiScale(float newScale) {
        float clamped = clampScale(newScale);
        if (Math.abs(clamped - this.uiScale) <= 0.002f && initialized) {
            return;
        }
        this.uiScale = clamped;
        ensureInitialized();
        refreshTokenRenderers();
    }

    /**
     * 预热 5 个默认字号阶梯的 ASCII 可见字符。
     */
    public void preloadAllAscii() {
        ensureInitialized();
        for (FontToken token : FontToken.values()) {
            getRenderer(token).preloadAscii();
        }
    }

    /**
     * 释放所有缓存的渲染器及其 OpenGL 纹理。
     */
    public void disposeAll() {
        for (TtfFontRenderer renderer : rendererCache.values()) {
            renderer.dispose();
        }
        rendererCache.clear();
        for (int i = 0; i < tokenRenderers.length; i++) {
            tokenRenderers[i] = null;
        }
    }

    /**
     * 获取当前环境解析到的中文回退字体族名。
     *
     * @return 字体族名
     */
    public String getCjkFallbackFamily() {
        ensureInitialized();
        return cjkFallbackFamily;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        cjkFallbackFamily = resolveCjkFallbackFamily();
        crispLatinFamily = resolveCrispLatinFamily();
        for (FontWeight weight : FontWeight.values()) {
            loadedInterFonts.put(weight, loadBundledFont(weight.getResourceName(), weight));
            loadedGoogleSansFonts.put(weight, loadBundledFont("GoogleSans-" + weightToSuffix(weight) + ".ttf", weight));
            loadedSystemFonts.put(weight, buildCrispSystemFont(crispLatinFamily, weight));
        }
        refreshTokenRenderers();
    }

    private void refreshTokenRenderers() {
        for (int i = 0; i < TOKENS.length; i++) {
            tokenRenderers[i] = getRenderer(TOKENS[i].getStyle(), uiScale);
        }
    }

    private boolean isActiveTokenRenderer(TtfFontRenderer candidate) {
        for (int i = 0; i < tokenRenderers.length; i++) {
            if (tokenRenderers[i] == candidate) {
                return true;
            }
        }
        return false;
    }

    private static String weightToSuffix(FontWeight weight) {
        if (weight == FontWeight.SEMIBOLD) {
            return "SemiBold";
        }
        if (weight == FontWeight.MEDIUM) {
            return "Medium";
        }
        return "Regular";
    }

    private static Font loadBundledFont(String fileName, FontWeight weight) {
        String resourcePath = FONT_RESOURCE_PREFIX + fileName;
        InputStream stream = FontManager.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            HoptimizationMod.LOGGER.error("Missing bundled Inter font resource '{}', falling back to SansSerif.",
                    resourcePath);
            return new Font(Font.SANS_SERIF, weight.getAwtFallbackStyle(), 16);
        }
        try {
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (FontFormatException e) {
            HoptimizationMod.LOGGER.error("Invalid TTF font format in '{}', falling back to SansSerif.",
                    resourcePath, e);
            return new Font(Font.SANS_SERIF, weight.getAwtFallbackStyle(), 16);
        } catch (IOException e) {
            HoptimizationMod.LOGGER.error("Failed to read bundled font '{}', falling back to SansSerif.",
                    resourcePath, e);
            return new Font(Font.SANS_SERIF, weight.getAwtFallbackStyle(), 16);
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                HoptimizationMod.LOGGER.warn("Failed to close font input stream for '{}'", resourcePath, e);
            }
        }
    }

    private static String resolveCjkFallbackFamily() {
        // 先枚举系统已安装字体家族，防止 AWT 对未安装字体静默返回 Dialog 导致跳过后续候选字体
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        Map<String, String> normalizedMap = new HashMap<String, String>(available.length * 2);
        for (String name : available) {
            if (name != null) {
                normalizedMap.put(name.trim().toLowerCase(Locale.ROOT), name);
            }
        }
        for (String candidate : CJK_FALLBACK_ORDER) {
            String exact = normalizedMap.get(candidate.toLowerCase(Locale.ROOT));
            if (exact != null) {
                HoptimizationMod.LOGGER.info("Resolved CJK fallback font family: {}", exact);
                return exact;
            }
        }
        HoptimizationMod.LOGGER.info("No preferred CJK font found, falling back to SansSerif.");
        return Font.SANS_SERIF;
    }

    private static String resolveCrispLatinFamily() {
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        Map<String, String> normalizedMap = new HashMap<String, String>(available.length * 2);
        for (String name : available) {
            if (name != null) {
                normalizedMap.put(name.trim().toLowerCase(Locale.ROOT), name);
            }
        }
        for (String candidate : CRISP_LATIN_ORDER) {
            String exact = normalizedMap.get(candidate.toLowerCase(Locale.ROOT));
            if (exact != null) {
                HoptimizationMod.LOGGER.info("Resolved crisp system font family: {}", exact);
                return exact;
            }
        }
        return null;
    }

    private static Font buildCrispSystemFont(String family, FontWeight weight) {
        if (family == null) {
            return null;
        }
        // Windows 下 Segoe UI 提供原生 Regular / Semibold / Bold 三个独立 TTF，可精确形成三阶字重层级。
        if ("Segoe UI".equalsIgnoreCase(family)) {
            if (weight == FontWeight.MEDIUM) {
                Font semi = new Font("Segoe UI Semibold", Font.PLAIN, 16);
                if (!Font.DIALOG.equalsIgnoreCase(semi.getFamily())) {
                    return semi;
                }
            } else if (weight == FontWeight.SEMIBOLD) {
                return new Font("Segoe UI", Font.BOLD, 16);
            }
            return new Font("Segoe UI", Font.PLAIN, 16);
        }
        Font base = new Font(family, weight.getAwtFallbackStyle(), 16);
        if (weight == FontWeight.MEDIUM) {
            return base.deriveFont(Collections.singletonMap(TextAttribute.WEIGHT, TextAttribute.WEIGHT_MEDIUM));
        }
        return base;
    }

    private static float clampScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) {
            return 1.0f;
        }
        if (scale < MIN_UI_SCALE) {
            return MIN_UI_SCALE;
        }
        if (scale > MAX_UI_SCALE) {
            return MAX_UI_SCALE;
        }
        return scale;
    }
}
