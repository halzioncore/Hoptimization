package com.hoptimization.ui.theme;

import com.hoptimization.modules.misc.LanguageModule;

/**
 * 不可变主题 token 容器，包含全部 18 个基础色板 token、4 个派生 token（支持覆盖）、毛玻璃派生色与风格参数，
 * 以及 WCAG 相对亮度/对比度辅助计算方法。
 */
public final class ThemeData {

    /** 当强调色相对亮度高于 0.5 时自动采用的深色前景色（与石墨主题一致的深炭色，比纯黑更柔和）。 */
    public static final int DARK_TEXT_ON_ACCENT = 0xFF0F1012;
    /** 亮色/常规强调色上采用的纯白前景色。 */
    public static final int LIGHT_TEXT_ON_ACCENT = 0xFFFFFFFF;
    /** 决定 textOnAccent 切换深色文字的相对亮度阈值（参见 02 §2.4）。 */
    public static final double AUTO_TEXT_LUMINANCE_THRESHOLD = 0.5;

    public static final double WCAG_MIN_PRIMARY_TEXT = 4.5;
    public static final double WCAG_MIN_SECONDARY_TEXT = 3.0;

    private final String id;
    private final String name;
    private final ThemeMode mode;

    // 18 个基础 token（ARGB）
    private final int backdrop;
    private final int surface0;
    private final int surface1;
    private final int surface2;
    private final int surface3;
    private final int surface4;
    private final int border;
    private final int borderStrong;
    private final int textPrimary;
    private final int textSecondary;
    private final int textTertiary;
    private final int accent;
    private final int textOnAccent;
    private final int thumb;
    private final int success;
    private final int warning;
    private final int danger;
    private final int shadow;

    // 4 个派生 token 的显式覆盖值（null 表示自动计算）
    private final Integer accentHoverOverride;
    private final Integer accentMutedOverride;
    private final Integer hudBackgroundOverride;
    private final Integer scrollbarOverride;

    // 4 个预计算后的有效派生 token（读取路径零分配）
    private final int accentHover;
    private final int accentMuted;
    private final int hudBackground;
    private final int scrollbar;
    private final int frostedBorder;

    private final ThemeStyle style;

    private ThemeData(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.mode = builder.mode;
        this.backdrop = builder.backdrop;
        this.surface0 = builder.surface0;
        this.surface1 = builder.surface1;
        this.surface2 = builder.surface2;
        this.surface3 = builder.surface3;
        this.surface4 = builder.surface4;
        this.border = builder.border;
        this.borderStrong = builder.borderStrong;
        this.textPrimary = builder.textPrimary;
        this.textSecondary = builder.textSecondary;
        this.textTertiary = builder.textTertiary;
        this.accent = builder.accent;
        this.textOnAccent = builder.textOnAccent;
        this.thumb = builder.thumb;
        this.success = builder.success;
        this.warning = builder.warning;
        this.danger = builder.danger;
        this.shadow = builder.shadow;
        this.accentHoverOverride = builder.accentHoverOverride;
        this.accentMutedOverride = builder.accentMutedOverride;
        this.hudBackgroundOverride = builder.hudBackgroundOverride;
        this.scrollbarOverride = builder.scrollbarOverride;
        this.style = builder.style;

        this.accentHover = this.accentHoverOverride != null
                ? this.accentHoverOverride.intValue()
                : computeAccentHover(this.accent, this.mode);
        this.accentMuted = this.accentMutedOverride != null
                ? this.accentMutedOverride.intValue()
                : computeAccentMuted(this.accent, this.mode);
        this.hudBackground = this.hudBackgroundOverride != null
                ? this.hudBackgroundOverride.intValue()
                : computeHudBackground(this.surface0, this.style.getHudOpacity());
        this.scrollbar = this.scrollbarOverride != null
                ? this.scrollbarOverride.intValue()
                : computeScrollbar(this.textTertiary);
        this.frostedBorder = computeFrostedBorder(this.borderStrong, this.thumb, this.mode);
    }

    /**
     * 专供帧内过渡插值使用的包内直构构造器，避免过渡期间创建 Builder 对象。
     */
    ThemeData(String id, String name, ThemeMode mode,
              int backdrop, int surface0, int surface1, int surface2, int surface3, int surface4,
              int border, int borderStrong,
              int textPrimary, int textSecondary, int textTertiary,
              int accent, int textOnAccent, int thumb,
              int success, int warning, int danger, int shadow,
              int accentHover, int accentMuted, int hudBackground, int scrollbar, int frostedBorder,
              ThemeStyle style) {
        this.id = id;
        this.name = name;
        this.mode = mode;
        this.backdrop = backdrop;
        this.surface0 = surface0;
        this.surface1 = surface1;
        this.surface2 = surface2;
        this.surface3 = surface3;
        this.surface4 = surface4;
        this.border = border;
        this.borderStrong = borderStrong;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.textTertiary = textTertiary;
        this.accent = accent;
        this.textOnAccent = textOnAccent;
        this.thumb = thumb;
        this.success = success;
        this.warning = warning;
        this.danger = danger;
        this.shadow = shadow;
        this.accentHoverOverride = null;
        this.accentMutedOverride = null;
        this.hudBackgroundOverride = null;
        this.scrollbarOverride = null;
        this.accentHover = accentHover;
        this.accentMuted = accentMuted;
        this.hudBackground = hudBackground;
        this.scrollbar = scrollbar;
        this.frostedBorder = frostedBorder;
        this.style = style;
    }

    /**
     * 在两个主题之间按进度 {@code t} 进行全 token 逐通道平滑插值。
     * 直接对有效派生颜色插值，可确保深/浅主题切换时派生色连续过渡不跳变。
     *
     * @param from 起始主题
     * @param to 目标主题
     * @param t 归一化进度 [0, 1]
     * @return 插值后的主题快照
     */
    public static ThemeData interpolate(ThemeData from, ThemeData to, float t) {
        if (t <= 0.0f) {
            return from;
        }
        if (t >= 1.0f) {
            return to;
        }
        return new ThemeData(
                to.id,
                to.name,
                t >= 0.5f ? to.mode : from.mode,
                lerpColor(from.backdrop, to.backdrop, t),
                lerpColor(from.surface0, to.surface0, t),
                lerpColor(from.surface1, to.surface1, t),
                lerpColor(from.surface2, to.surface2, t),
                lerpColor(from.surface3, to.surface3, t),
                lerpColor(from.surface4, to.surface4, t),
                lerpColor(from.border, to.border, t),
                lerpColor(from.borderStrong, to.borderStrong, t),
                lerpColor(from.textPrimary, to.textPrimary, t),
                lerpColor(from.textSecondary, to.textSecondary, t),
                lerpColor(from.textTertiary, to.textTertiary, t),
                lerpColor(from.accent, to.accent, t),
                lerpColor(from.textOnAccent, to.textOnAccent, t),
                lerpColor(from.thumb, to.thumb, t),
                lerpColor(from.success, to.success, t),
                lerpColor(from.warning, to.warning, t),
                lerpColor(from.danger, to.danger, t),
                lerpColor(from.shadow, to.shadow, t),
                lerpColor(from.accentHover, to.accentHover, t),
                lerpColor(from.accentMuted, to.accentMuted, t),
                lerpColor(from.hudBackground, to.hudBackground, t),
                lerpColor(from.scrollbar, to.scrollbar, t),
                lerpColor(from.frostedBorder, to.frostedBorder, t),
                ThemeStyle.interpolate(from.style, to.style, t)
        );
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * 获取当前语言下的主题显示名称：内置主题优先读取 {@code ui.hoptimization.theme.<id>}，自定义主题返回其配置名称。
     *
     * @return 本地化后的主题名称
     */
    public String getLocalizedName() {
        if (BuiltinThemes.isBuiltin(id)) {
            String key = "ui.hoptimization.theme." + id;
            try {
                String formatted = LanguageModule.format(key);
                if (formatted != null && !formatted.isEmpty() && !formatted.equals(key)) {
                    return formatted;
                }
            } catch (RuntimeException ignored) {
                // 单元测试或未初始化语言模块时安全回退内置默认名
            }
        }
        return name;
    }

    public ThemeMode getMode() {
        return mode;
    }

    public int backdrop() {
        return backdrop;
    }

    public int surface0() {
        return surface0;
    }

    public int surface1() {
        return surface1;
    }

    public int surface2() {
        return surface2;
    }

    public int surface3() {
        return surface3;
    }

    public int surface4() {
        return surface4;
    }

    public int border() {
        return border;
    }

    public int borderStrong() {
        return borderStrong;
    }

    public int textPrimary() {
        return textPrimary;
    }

    public int textSecondary() {
        return textSecondary;
    }

    public int textTertiary() {
        return textTertiary;
    }

    public int accent() {
        return accent;
    }

    public int textOnAccent() {
        return textOnAccent;
    }

    public int thumb() {
        return thumb;
    }

    public int success() {
        return success;
    }

    public int warning() {
        return warning;
    }

    public int danger() {
        return danger;
    }

    public int shadow() {
        return shadow;
    }

    public int accentHover() {
        return accentHover;
    }

    public int accentMuted() {
        return accentMuted;
    }

    public int hudBackground() {
        return hudBackground;
    }

    public int scrollbar() {
        return scrollbar;
    }

    /**
     * 获取毛玻璃模式下的微光折射边框色（浅色基于 {@code borderStrong}，深色融合 {@code borderStrong} 与 {@code thumb}）。
     *
     * @return 毛玻璃边框 ARGB 颜色
     */
    public int frostedBorder() {
        return frostedBorder;
    }

    /**
     * 获取毛玻璃材质顶部高光光泽强度。
     *
     * @return 高光强度系数
     */
    public float frostedHighlightStrength() {
        return mode == ThemeMode.LIGHT ? 0.14f : 0.09f;
    }

    /**
     * 获取毛玻璃主窗口底板着色（基于 {@code surface0}）。
     *
     * @return 磨砂窗口着色 ARGB
     */
    public int frostedWindowTint() {
        return scaleAlpha(surface0, mode == ThemeMode.LIGHT ? 0.68f : 0.56f);
    }

    /**
     * 获取毛玻璃二级内容面板着色（基于 {@code surface1}）。
     *
     * @return 磨砂面板着色 ARGB
     */
    public int frostedPanelTint() {
        return scaleAlpha(surface1, mode == ThemeMode.LIGHT ? 0.54f : 0.44f);
    }

    /**
     * 获取毛玻璃卡片着色（基于 {@code surface2}）。
     *
     * @return 磨砂卡片着色 ARGB
     */
    public int frostedCardTint() {
        return scaleAlpha(surface2, mode == ThemeMode.LIGHT ? 0.66f : 0.54f);
    }

    /**
     * 获取毛玻璃顶层弹出菜单/取色器着色（基于 {@code surface2}）。
     *
     * @return 磨砂弹出层着色 ARGB
     */
    public int frostedPopupTint() {
        return scaleAlpha(surface2, mode == ThemeMode.LIGHT ? 0.76f : 0.68f);
    }

    /**
     * 获取毛玻璃悬停提示框着色（基于 {@code surface4}）。
     *
     * @return 磨砂提示框着色 ARGB
     */
    public int frostedTooltipTint() {
        return scaleAlpha(surface4, mode == ThemeMode.LIGHT ? 0.78f : 0.70f);
    }

    /**
     * 获取融合了主题 {@code hudOpacity} 的毛玻璃 HUD 卡片着色（基于 {@code surface0}）。
     *
     * @return 磨砂 HUD 卡片着色 ARGB
     */
    public int frostedHudTint() {
        float base = mode == ThemeMode.LIGHT ? 0.78f : 0.66f;
        float factor = Math.min(0.92f, Math.max(0.0f, style.getHudOpacity() * base));
        return scaleAlpha(surface0, factor);
    }

    public Integer getAccentHoverOverride() {
        return accentHoverOverride;
    }

    public Integer getAccentMutedOverride() {
        return accentMutedOverride;
    }

    public Integer getHudBackgroundOverride() {
        return hudBackgroundOverride;
    }

    public Integer getScrollbarOverride() {
        return scrollbarOverride;
    }

    public ThemeStyle style() {
        return style;
    }

    /**
     * 获取应用了 {@code radiusScale} 的主窗口圆角半径（逻辑像素）。
     *
     * @return 主窗口圆角半径
     */
    public float radiusWindow() {
        return UiTokens.RADIUS_WINDOW * style.getRadiusScale();
    }

    /**
     * 获取应用了 {@code radiusScale} 的卡片/弹出层圆角半径（逻辑像素）。
     *
     * @return 卡片圆角半径
     */
    public float radiusCard() {
        return UiTokens.RADIUS_CARD * style.getRadiusScale();
    }

    /**
     * 获取应用了 {@code radiusScale} 的控件圆角半径（逻辑像素）。
     *
     * @return 控件圆角半径
     */
    public float radiusControl() {
        return UiTokens.RADIUS_CONTROL * style.getRadiusScale();
    }

    /**
     * 获取受主题 {@code radiusScale} 约束的胶囊圆角半径（{@code radiusScale == 0} 时返回 0）。
     *
     * @param height 控件高度（逻辑像素）
     * @return 胶囊圆角半径
     */
    public float radiusPill(float height) {
        return UiTokens.pillRadius(height, style.getRadiusScale());
    }

    /**
     * 按规范嵌套规则计算内层圆角半径。
     *
     * @param outerScaledRadius 外层缩放后的圆角半径
     * @param spacing 外层与内层之间的间距
     * @return 内层圆角半径
     */
    public float nestedRadius(float outerScaledRadius, float spacing) {
        return UiTokens.nestedRadius(outerScaledRadius, spacing);
    }

    /**
     * 计算应用了 {@code shadowStrength} 的主窗口阴影颜色（ARGB）。
     *
     * @return 主窗口阴影颜色
     */
    public int windowShadowColor() {
        return scaleAlpha(shadow, style.getShadowStrength() * UiTokens.SHADOW_WINDOW_ALPHA_FACTOR);
    }

    /**
     * 计算应用了 {@code shadowStrength} 与弹出层系数（0.9）的弹出层阴影颜色（ARGB）。
     *
     * @return 弹出层阴影颜色
     */
    public int popupShadowColor() {
        return scaleAlpha(shadow, style.getShadowStrength() * UiTokens.SHADOW_POPUP_ALPHA_FACTOR);
    }

    /**
     * 基于当前主题生成一个替换强调色的新主题（自动计算 {@code textOnAccent}、{@code accentHover} 与 {@code accentMuted}）。
     *
     * @param newAccentArgb 新的强调色（ARGB）
     * @return 替换强调色后的不可变 {@link ThemeData}
     */
    public ThemeData withAccent(int newAccentArgb) {
        return toBuilder()
                .accent(newAccentArgb)
                .textOnAccent(computeTextOnAccent(newAccentArgb))
                .accentHoverOverride(null)
                .accentMutedOverride(null)
                .build();
    }

    /**
     * 检查 {@code textPrimary} 在 {@code surface1} 与 {@code surface2} 上是否同时满足 WCAG 4.5 对比度。
     *
     * @return 是否达标
     */
    public boolean meetsPrimaryTextContrast() {
        return contrastRatio(textPrimary, surface1) >= WCAG_MIN_PRIMARY_TEXT
                && contrastRatio(textPrimary, surface2) >= WCAG_MIN_PRIMARY_TEXT;
    }

    /**
     * 检查 {@code textSecondary} 在 {@code surface1} 与 {@code surface2} 上是否同时满足 WCAG 3.0 对比度。
     *
     * @return 是否达标
     */
    public boolean meetsSecondaryTextContrast() {
        return contrastRatio(textSecondary, surface1) >= WCAG_MIN_SECONDARY_TEXT
                && contrastRatio(textSecondary, surface2) >= WCAG_MIN_SECONDARY_TEXT;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * 按规范计算默认 {@code accentHover}：深色模式混合 15% 白，浅色模式混合 10% 黑。
     */
    public static int computeAccentHover(int accentArgb, ThemeMode mode) {
        int target = (mode == ThemeMode.LIGHT) ? 0xFF000000 : 0xFFFFFFFF;
        float ratio = (mode == ThemeMode.LIGHT) ? 0.10f : 0.15f;
        int alpha = (accentArgb >>> 24) & 0xFF;
        int mixed = lerpColor(accentArgb, target, ratio);
        return (alpha << 24) | (mixed & 0x00FFFFFF);
    }

    /**
     * 按规范计算默认 {@code accentMuted}：深色模式 14% 透明，浅色模式 10% 透明。
     */
    public static int computeAccentMuted(int accentArgb, ThemeMode mode) {
        float factor = (mode == ThemeMode.LIGHT) ? 0.10f : 0.14f;
        return scaleAlpha(accentArgb, factor);
    }

    /**
     * 按规范计算默认 {@code hudBackground}：{@code surface0} 乘以 {@code hudOpacity}。
     */
    public static int computeHudBackground(int surface0Argb, float hudOpacity) {
        return scaleAlpha(surface0Argb, hudOpacity);
    }

    /**
     * 按规范计算默认 {@code scrollbar}：{@code textTertiary} 的 50% 不透明度。
     */
    public static int computeScrollbar(int textTertiaryArgb) {
        return scaleAlpha(textTertiaryArgb, 0.50f);
    }

    /**
     * 计算与主题色调协调的毛玻璃微光边缘色，消除组件内硬编码白色描边。
     */
    public static int computeFrostedBorder(int borderStrongArgb, int thumbArgb, ThemeMode mode) {
        if (mode == ThemeMode.LIGHT) {
            int baseA = (borderStrongArgb >>> 24) & 0xFF;
            int boostedA = Math.max(0x32, Math.min(0x54, baseA + 0x10));
            return withAlpha(borderStrongArgb, boostedA);
        }
        int strongOpaque = 0xFF000000 | (borderStrongArgb & 0x00FFFFFF);
        int thumbOpaque = 0xFF000000 | (thumbArgb & 0x00FFFFFF);
        int mixedRgb = lerpColor(strongOpaque, thumbOpaque, 0.55f);
        return withAlpha(mixedRgb, 0x36);
    }

    /**
     * 根据强调色相对亮度自动选择 {@code textOnAccent}（> 0.5 用深色文字，否则白色）。
     *
     * @param accentArgb 强调色（ARGB）
     * @return 深色或白色前景色
     */
    public static int computeTextOnAccent(int accentArgb) {
        return relativeLuminance(accentArgb) > AUTO_TEXT_LUMINANCE_THRESHOLD
                ? DARK_TEXT_ON_ACCENT
                : LIGHT_TEXT_ON_ACCENT;
    }

    /**
     * 计算 ARGB 颜色的 WCAG 相对亮度（[0.0, 1.0]，忽略 Alpha 通道）。
     *
     * @param argb ARGB 颜色
     * @return 相对亮度
     */
    public static double relativeLuminance(int argb) {
        double r = linearizeChannel(((argb >>> 16) & 0xFF) / 255.0);
        double g = linearizeChannel(((argb >>> 8) & 0xFF) / 255.0);
        double b = linearizeChannel((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /**
     * 计算前景色在背景色上的 WCAG 对比度（[1.0, 21.0]），若前景色含半透明则先与背景做 Alpha 合成。
     *
     * @param fgArgb 前景色（ARGB）
     * @param bgArgb 背景色（ARGB）
     * @return 对比度比值
     */
    public static double contrastRatio(int fgArgb, int bgArgb) {
        int effectiveFg = fgArgb;
        int fgAlpha = (fgArgb >>> 24) & 0xFF;
        if (fgAlpha < 255) {
            effectiveFg = alphaCompositeOver(fgArgb, bgArgb);
        }
        double l1 = relativeLuminance(effectiveFg);
        double l2 = relativeLuminance(bgArgb);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /**
     * 检查前景色与背景色对比度是否达到指定阈值。
     */
    public static boolean meetsContrast(int fgArgb, int bgArgb, double minRatio) {
        return contrastRatio(fgArgb, bgArgb) >= minRatio;
    }

    /**
     * 逐通道线性插值两个 ARGB 颜色。
     */
    public static int lerpColor(int fromArgb, int toArgb, float t) {
        if (t <= 0.0f) {
            return fromArgb;
        }
        if (t >= 1.0f) {
            return toArgb;
        }
        int a1 = (fromArgb >>> 24) & 0xFF;
        int r1 = (fromArgb >>> 16) & 0xFF;
        int g1 = (fromArgb >>> 8) & 0xFF;
        int b1 = fromArgb & 0xFF;

        int a2 = (toArgb >>> 24) & 0xFF;
        int r2 = (toArgb >>> 16) & 0xFF;
        int g2 = (toArgb >>> 8) & 0xFF;
        int b2 = toArgb & 0xFF;

        int a = Math.round(a1 + (a2 - a1) * t);
        int r = Math.round(r1 + (r2 - r1) * t);
        int g = Math.round(g1 + (g2 - g1) * t);
        int b = Math.round(b1 + (b2 - b1) * t);
        return (clampByte(a) << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    /**
     * 替换颜色的 Alpha 通道（0–255）。
     */
    public static int withAlpha(int argb, int alpha) {
        return (clampByte(alpha) << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 将颜色现有 Alpha 乘以倍率并夹紧到 [0, 255]。
     */
    public static int scaleAlpha(int argb, float factor) {
        if (factor <= 0.0f) {
            return argb & 0x00FFFFFF;
        }
        int alpha = (argb >>> 24) & 0xFF;
        int scaled = clampByte(Math.round(alpha * factor));
        return (scaled << 24) | (argb & 0x00FFFFFF);
    }

    private static int alphaCompositeOver(int fgArgb, int bgArgb) {
        double a = ((fgArgb >>> 24) & 0xFF) / 255.0;
        double invA = 1.0 - a;
        int r = clampByte((int) Math.round(((fgArgb >>> 16) & 0xFF) * a + ((bgArgb >>> 16) & 0xFF) * invA));
        int g = clampByte((int) Math.round(((fgArgb >>> 8) & 0xFF) * a + ((bgArgb >>> 8) & 0xFF) * invA));
        int b = clampByte((int) Math.round((fgArgb & 0xFF) * a + (bgArgb & 0xFF) * invA));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double linearizeChannel(double c) {
        if (c <= 0.03928) {
            return c / 12.92;
        }
        return Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int clampByte(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

    /**
     * {@link ThemeData} 构建器。
     */
    public static final class Builder {
        private String id = "custom";
        private String name = "Custom";
        private ThemeMode mode = ThemeMode.DARK;

        private int backdrop = 0x73000000;
        private int surface0 = 0xFF0F1012;
        private int surface1 = 0xFF16171A;
        private int surface2 = 0xFF1D1F23;
        private int surface3 = 0xFF25272C;
        private int surface4 = 0xFF2E3137;
        private int border = 0x0FFFFFFF;
        private int borderStrong = 0x1AFFFFFF;
        private int textPrimary = 0xFFECEDEE;
        private int textSecondary = 0xFF9BA1A6;
        private int textTertiary = 0xFF5E636A;
        private int accent = 0xFF5B8CFF;
        private int textOnAccent = 0xFFFFFFFF;
        private int thumb = 0xFFFFFFFF;
        private int success = 0xFF3DD68C;
        private int warning = 0xFFF5B544;
        private int danger = 0xFFF2555A;
        private int shadow = 0x73000000;

        private Integer accentHoverOverride;
        private Integer accentMutedOverride;
        private Integer hudBackgroundOverride;
        private Integer scrollbarOverride;

        private ThemeStyle style = ThemeStyle.defaultFor(ThemeMode.DARK);

        public Builder() {
        }

        public Builder(ThemeData source) {
            this.id = source.id;
            this.name = source.name;
            this.mode = source.mode;
            this.backdrop = source.backdrop;
            this.surface0 = source.surface0;
            this.surface1 = source.surface1;
            this.surface2 = source.surface2;
            this.surface3 = source.surface3;
            this.surface4 = source.surface4;
            this.border = source.border;
            this.borderStrong = source.borderStrong;
            this.textPrimary = source.textPrimary;
            this.textSecondary = source.textSecondary;
            this.textTertiary = source.textTertiary;
            this.accent = source.accent;
            this.textOnAccent = source.textOnAccent;
            this.thumb = source.thumb;
            this.success = source.success;
            this.warning = source.warning;
            this.danger = source.danger;
            this.shadow = source.shadow;
            this.accentHoverOverride = source.accentHoverOverride;
            this.accentMutedOverride = source.accentMutedOverride;
            this.hudBackgroundOverride = source.hudBackgroundOverride;
            this.scrollbarOverride = source.scrollbarOverride;
            this.style = source.style;
        }

        public Builder id(String id) {
            this.id = (id == null || id.trim().isEmpty()) ? "custom" : id.trim();
            return this;
        }

        public Builder name(String name) {
            this.name = (name == null || name.trim().isEmpty()) ? this.id : name.trim();
            return this;
        }

        public Builder mode(ThemeMode mode) {
            this.mode = mode == null ? ThemeMode.DARK : mode;
            return this;
        }

        public Builder backdrop(int backdrop) {
            this.backdrop = backdrop;
            return this;
        }

        public Builder surface0(int surface0) {
            this.surface0 = surface0;
            return this;
        }

        public Builder surface1(int surface1) {
            this.surface1 = surface1;
            return this;
        }

        public Builder surface2(int surface2) {
            this.surface2 = surface2;
            return this;
        }

        public Builder surface3(int surface3) {
            this.surface3 = surface3;
            return this;
        }

        public Builder surface4(int surface4) {
            this.surface4 = surface4;
            return this;
        }

        public Builder border(int border) {
            this.border = border;
            return this;
        }

        public Builder borderStrong(int borderStrong) {
            this.borderStrong = borderStrong;
            return this;
        }

        public Builder textPrimary(int textPrimary) {
            this.textPrimary = textPrimary;
            return this;
        }

        public Builder textSecondary(int textSecondary) {
            this.textSecondary = textSecondary;
            return this;
        }

        public Builder textTertiary(int textTertiary) {
            this.textTertiary = textTertiary;
            return this;
        }

        public Builder accent(int accent) {
            this.accent = accent;
            return this;
        }

        public Builder textOnAccent(int textOnAccent) {
            this.textOnAccent = textOnAccent;
            return this;
        }

        public Builder thumb(int thumb) {
            this.thumb = thumb;
            return this;
        }

        public Builder success(int success) {
            this.success = success;
            return this;
        }

        public Builder warning(int warning) {
            this.warning = warning;
            return this;
        }

        public Builder danger(int danger) {
            this.danger = danger;
            return this;
        }

        public Builder shadow(int shadow) {
            this.shadow = shadow;
            return this;
        }

        public Builder accentHoverOverride(Integer accentHoverOverride) {
            this.accentHoverOverride = accentHoverOverride;
            return this;
        }

        public Builder accentMutedOverride(Integer accentMutedOverride) {
            this.accentMutedOverride = accentMutedOverride;
            return this;
        }

        public Builder hudBackgroundOverride(Integer hudBackgroundOverride) {
            this.hudBackgroundOverride = hudBackgroundOverride;
            return this;
        }

        public Builder scrollbarOverride(Integer scrollbarOverride) {
            this.scrollbarOverride = scrollbarOverride;
            return this;
        }

        public Builder style(ThemeStyle style) {
            this.style = style == null ? ThemeStyle.defaultFor(this.mode) : style;
            return this;
        }

        public ThemeData build() {
            return new ThemeData(this);
        }
    }
}
