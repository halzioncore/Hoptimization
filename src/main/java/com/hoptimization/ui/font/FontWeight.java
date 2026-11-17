package com.hoptimization.ui.font;

import java.awt.Font;

/**
 * 字体字重枚举，映射到内置 Inter 静态 TTF 资源与系统 CJK 回退字体的 AWT 粗细样式。
 */
public enum FontWeight {

    REGULAR("Inter-Regular.ttf", Font.PLAIN),
    MEDIUM("Inter-Medium.ttf", Font.PLAIN),
    SEMIBOLD("Inter-SemiBold.ttf", Font.BOLD);

    private final String resourceName;
    private final int awtFallbackStyle;

    FontWeight(String resourceName, int awtFallbackStyle) {
        this.resourceName = resourceName;
        this.awtFallbackStyle = awtFallbackStyle;
    }

    /**
     * 获取内置 Inter 静态 TTF 文件名（位于 {@code assets/hoptimization/font/}）。
     *
     * @return 字体文件名
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * 获取回退系统字体（如微软雅黑 UI）对应的 AWT 样式标记。
     *
     * @return {@link Font#PLAIN} 或 {@link Font#BOLD}
     */
    public int getAwtFallbackStyle() {
        return awtFallbackStyle;
    }
}
