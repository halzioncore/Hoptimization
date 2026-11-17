package com.hoptimization.ui.theme;

import java.util.Locale;

/**
 * 主题明暗模式，用于决定派生颜色 token（如 {@code accentHover}、{@code accentMuted}）的计算策略。
 */
public enum ThemeMode {
    DARK("dark"),
    LIGHT("light");

    private final String id;

    ThemeMode(String id) {
        this.id = id;
    }

    /**
     * 获取序列化到 JSON 的模式标识（{@code "dark"} 或 {@code "light"}）。
     *
     * @return 模式标识字符串
     */
    public String getId() {
        return id;
    }

    /**
     * 根据字符串解析主题模式（忽略大小写），无法识别时返回 {@code null}。
     *
     * @param id 模式字符串
     * @return 对应的 {@link ThemeMode}，无效时返回 {@code null}
     */
    public static ThemeMode fromId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if ("dark".equals(normalized)) {
            return DARK;
        }
        if ("light".equals(normalized)) {
            return LIGHT;
        }
        return null;
    }

    /**
     * 根据字符串解析主题模式，无效时返回默认值。
     *
     * @param id 模式字符串
     * @param fallback 无法解析时的回退值
     * @return 解析结果或 {@code fallback}
     */
    public static ThemeMode fromIdOrDefault(String id, ThemeMode fallback) {
        ThemeMode parsed = fromId(id);
        return parsed != null ? parsed : fallback;
    }
}
