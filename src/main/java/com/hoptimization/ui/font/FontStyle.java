package com.hoptimization.ui.font;

/**
 * 不可变字体样式描述符，定义逻辑字号、字重、字距与英文全大写转换标记。
 */
public final class FontStyle {

    /** 页面标题：16 SemiBold。 */
    public static final FontStyle TITLE = new FontStyle(16.0f, FontWeight.SEMIBOLD, 0.0f, false);
    /** 模块名、按钮：13 Medium。 */
    public static final FontStyle BODY = new FontStyle(13.0f, FontWeight.MEDIUM, 0.0f, false);
    /** 设置项标签：13 Regular。 */
    public static final FontStyle BODY_REGULAR = new FontStyle(13.0f, FontWeight.REGULAR, 0.0f, false);
    /** 描述、数值单位、提示：11 Regular。 */
    public static final FontStyle CAPTION = new FontStyle(11.0f, FontWeight.REGULAR, 0.0f, false);
    /** 分组小标题：10 SemiBold，字距 +0.6，英文全大写。 */
    public static final FontStyle OVERLINE = new FontStyle(10.0f, FontWeight.SEMIBOLD, 0.6f, true);

    private final float size;
    private final FontWeight weight;
    private final float letterSpacing;
    private final boolean uppercase;

    /**
     * 构造字体样式描述符。
     *
     * @param size 逻辑像素字号（> 0）
     * @param weight 字重
     * @param letterSpacing 字符间距（逻辑像素）
     * @param uppercase 是否将拉丁字母转为全大写
     */
    public FontStyle(float size, FontWeight weight, float letterSpacing, boolean uppercase) {
        if (size <= 0.0f || Float.isNaN(size) || Float.isInfinite(size)) {
            throw new IllegalArgumentException("Font size must be positive: " + size);
        }
        this.size = size;
        this.weight = weight != null ? weight : FontWeight.REGULAR;
        this.letterSpacing = letterSpacing;
        this.uppercase = uppercase;
    }

    /**
     * 创建无额外字距与大小写转换的常规样式。
     *
     * @param size 逻辑像素字号
     * @param weight 字重
     * @return 字体样式实例
     */
    public static FontStyle of(float size, FontWeight weight) {
        return new FontStyle(size, weight, 0.0f, false);
    }

    /**
     * 创建自定义字体样式。
     *
     * @param size 逻辑像素字号
     * @param weight 字重
     * @param letterSpacing 字符间距（逻辑像素）
     * @param uppercase 是否转为全大写
     * @return 字体样式实例
     */
    public static FontStyle of(float size, FontWeight weight, float letterSpacing, boolean uppercase) {
        return new FontStyle(size, weight, letterSpacing, uppercase);
    }

    public float getSize() {
        return size;
    }

    public FontWeight getWeight() {
        return weight;
    }

    public float getLetterSpacing() {
        return letterSpacing;
    }

    public boolean isUppercase() {
        return uppercase;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof FontStyle)) {
            return false;
        }
        FontStyle other = (FontStyle) obj;
        return Float.compare(size, other.size) == 0
                && weight == other.weight
                && Float.compare(letterSpacing, other.letterSpacing) == 0
                && uppercase == other.uppercase;
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(size);
        result = 31 * result + weight.hashCode();
        result = 31 * result + Float.floatToIntBits(letterSpacing);
        result = 31 * result + (uppercase ? 1 : 0);
        return result;
    }
}
