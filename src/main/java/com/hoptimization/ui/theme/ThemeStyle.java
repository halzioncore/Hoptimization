package com.hoptimization.ui.theme;

/**
 * 主题的不可变风格参数集合（圆角倍率、透明度、阴影强度、背景模糊、卡片描边）。
 */
public final class ThemeStyle {

    public static final float DEFAULT_RADIUS_SCALE = 1.0f;
    public static final float MIN_RADIUS_SCALE = 0.0f;
    public static final float MAX_RADIUS_SCALE = 1.5f;

    public static final float DEFAULT_WINDOW_OPACITY = 1.0f;
    public static final float MIN_WINDOW_OPACITY = 0.80f;
    public static final float MAX_WINDOW_OPACITY = 1.0f;

    public static final float DEFAULT_HUD_OPACITY = 0.70f;
    public static final float MIN_HUD_OPACITY = 0.0f;
    public static final float MAX_HUD_OPACITY = 1.0f;

    public static final float DEFAULT_SHADOW_STRENGTH = 1.0f;
    public static final float MIN_SHADOW_STRENGTH = 0.0f;
    public static final float MAX_SHADOW_STRENGTH = 1.5f;

    private final float radiusScale;
    private final float windowOpacity;
    private final float hudOpacity;
    private final float shadowStrength;
    private final boolean blur;
    private final boolean cardBorder;

    /**
     * 构造并校验夹紧后的风格参数实例。
     *
     * @param radiusScale 圆角缩放倍率（0–1.5）
     * @param windowOpacity 窗口整体不透明度（0.80–1.0）
     * @param hudOpacity HUD 卡片背景不透明度（0–1.0）
     * @param shadowStrength 阴影强度系数（0–1.5）
     * @param blur 打开 GUI 时是否启用背景模糊
     * @param cardBorder 是否绘制卡片 1px 描边
     */
    public ThemeStyle(float radiusScale, float windowOpacity, float hudOpacity,
                      float shadowStrength, boolean blur, boolean cardBorder) {
        this.radiusScale = clampFloat(radiusScale, MIN_RADIUS_SCALE, MAX_RADIUS_SCALE, DEFAULT_RADIUS_SCALE);
        this.windowOpacity = clampFloat(windowOpacity, MIN_WINDOW_OPACITY, MAX_WINDOW_OPACITY, DEFAULT_WINDOW_OPACITY);
        this.hudOpacity = clampFloat(hudOpacity, MIN_HUD_OPACITY, MAX_HUD_OPACITY, DEFAULT_HUD_OPACITY);
        this.shadowStrength = clampFloat(shadowStrength, MIN_SHADOW_STRENGTH, MAX_SHADOW_STRENGTH,
                DEFAULT_SHADOW_STRENGTH);
        this.blur = blur;
        this.cardBorder = cardBorder;
    }

    /**
     * 获取指定主题模式下的默认风格参数（浅色模式默认启用 {@code cardBorder}）。
     *
     * @param mode 主题模式
     * @return 默认 {@link ThemeStyle}
     */
    public static ThemeStyle defaultFor(ThemeMode mode) {
        boolean defaultCardBorder = (mode == ThemeMode.LIGHT);
        return new ThemeStyle(
                DEFAULT_RADIUS_SCALE,
                DEFAULT_WINDOW_OPACITY,
                DEFAULT_HUD_OPACITY,
                DEFAULT_SHADOW_STRENGTH,
                false,
                defaultCardBorder
        );
    }

    /**
     * 在两个风格配置之间进行线性插值，布尔项在进度过半时切换。
     *
     * @param from 起始风格
     * @param to 目标风格
     * @param t 归一化进度 [0, 1]
     * @return 插值后的 {@link ThemeStyle}
     */
    public static ThemeStyle interpolate(ThemeStyle from, ThemeStyle to, float t) {
        if (t <= 0.0f) {
            return from;
        }
        if (t >= 1.0f) {
            return to;
        }
        float inv = 1.0f - t;
        return new ThemeStyle(
                from.radiusScale * inv + to.radiusScale * t,
                from.windowOpacity * inv + to.windowOpacity * t,
                from.hudOpacity * inv + to.hudOpacity * t,
                from.shadowStrength * inv + to.shadowStrength * t,
                t >= 0.5f ? to.blur : from.blur,
                t >= 0.5f ? to.cardBorder : from.cardBorder
        );
    }

    public float getRadiusScale() {
        return radiusScale;
    }

    public float getWindowOpacity() {
        return windowOpacity;
    }

    public float getHudOpacity() {
        return hudOpacity;
    }

    public float getShadowStrength() {
        return shadowStrength;
    }

    public boolean isBlur() {
        return blur;
    }

    public boolean isCardBorder() {
        return cardBorder;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ThemeStyle)) {
            return false;
        }
        ThemeStyle other = (ThemeStyle) obj;
        return Float.compare(radiusScale, other.radiusScale) == 0
                && Float.compare(windowOpacity, other.windowOpacity) == 0
                && Float.compare(hudOpacity, other.hudOpacity) == 0
                && Float.compare(shadowStrength, other.shadowStrength) == 0
                && blur == other.blur
                && cardBorder == other.cardBorder;
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(radiusScale);
        result = 31 * result + Float.floatToIntBits(windowOpacity);
        result = 31 * result + Float.floatToIntBits(hudOpacity);
        result = 31 * result + Float.floatToIntBits(shadowStrength);
        result = 31 * result + (blur ? 1 : 0);
        result = 31 * result + (cardBorder ? 1 : 0);
        return result;
    }

    private static float clampFloat(float value, float min, float max, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * {@link ThemeStyle} 构建器。
     */
    public static final class Builder {
        private float radiusScale = DEFAULT_RADIUS_SCALE;
        private float windowOpacity = DEFAULT_WINDOW_OPACITY;
        private float hudOpacity = DEFAULT_HUD_OPACITY;
        private float shadowStrength = DEFAULT_SHADOW_STRENGTH;
        private boolean blur = false;
        private boolean cardBorder = false;

        public Builder() {
        }

        public Builder(ThemeStyle source) {
            this.radiusScale = source.radiusScale;
            this.windowOpacity = source.windowOpacity;
            this.hudOpacity = source.hudOpacity;
            this.shadowStrength = source.shadowStrength;
            this.blur = source.blur;
            this.cardBorder = source.cardBorder;
        }

        public Builder radiusScale(float radiusScale) {
            this.radiusScale = radiusScale;
            return this;
        }

        public Builder windowOpacity(float windowOpacity) {
            this.windowOpacity = windowOpacity;
            return this;
        }

        public Builder hudOpacity(float hudOpacity) {
            this.hudOpacity = hudOpacity;
            return this;
        }

        public Builder shadowStrength(float shadowStrength) {
            this.shadowStrength = shadowStrength;
            return this;
        }

        public Builder blur(boolean blur) {
            this.blur = blur;
            return this;
        }

        public Builder cardBorder(boolean cardBorder) {
            this.cardBorder = cardBorder;
            return this;
        }

        public ThemeStyle build() {
            return new ThemeStyle(radiusScale, windowOpacity, hudOpacity, shadowStrength, blur, cardBorder);
        }
    }
}
