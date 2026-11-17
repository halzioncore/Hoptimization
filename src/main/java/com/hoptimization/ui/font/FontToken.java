package com.hoptimization.ui.font;

/**
 * 规范定义的五级字体阶梯 token（参见 {@code 02_美术规范.md} §3）。
 */
public enum FontToken {

    /** 内容区页面标题：16 SemiBold。 */
    TITLE(FontStyle.TITLE),
    /** 模块名、按钮：13 Medium。 */
    BODY(FontStyle.BODY),
    /** 设置项标签：13 Regular。 */
    BODY_REGULAR(FontStyle.BODY_REGULAR),
    /** 描述、数值单位、提示：11 Regular。 */
    CAPTION(FontStyle.CAPTION),
    /** 分组小标题：10 SemiBold，字距 +0.6，英文全大写。 */
    OVERLINE(FontStyle.OVERLINE);

    private final FontStyle style;

    FontToken(FontStyle style) {
        this.style = style;
    }

    public FontStyle getStyle() {
        return style;
    }

    public float getSize() {
        return style.getSize();
    }

    public FontWeight getWeight() {
        return style.getWeight();
    }

    public float getLetterSpacing() {
        return style.getLetterSpacing();
    }

    public boolean isUppercase() {
        return style.isUppercase();
    }
}
