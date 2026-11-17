package com.hoptimization.ui.theme;

/**
 * 规范定义的 UI 尺寸、间距、圆角、阴影与动效时长常量（参见 {@code 02_美术规范.md} §4–§7）。
 */
public final class UiTokens {

    /** 基础网格单位（4px）。 */
    public static final int GRID_UNIT = 4;

    /** 允许的规范间距阶梯（逻辑像素）。 */
    public static final int SPACE_2 = 2;
    public static final int SPACE_4 = 4;
    public static final int SPACE_6 = 6;
    public static final int SPACE_8 = 8;
    public static final int SPACE_12 = 12;
    public static final int SPACE_16 = 16;
    public static final int SPACE_20 = 20;
    public static final int SPACE_24 = 24;
    public static final int SPACE_32 = 32;

    /** 语义化容器内边距与间距（逻辑像素）。 */
    public static final int PADDING_WINDOW = SPACE_16;
    public static final int PADDING_CARD = SPACE_12;
    public static final int PADDING_CONTROL = SPACE_8;
    public static final int GAP_LIST_ITEM = SPACE_6;
    public static final int GAP_GROUP = SPACE_20;

    /** HUD 卡片默认内边距与贴边距（逻辑像素）。 */
    public static final int HUD_PADDING_V = SPACE_6;
    public static final int HUD_PADDING_H = SPACE_8;
    public static final int HUD_SCREEN_MARGIN = SPACE_8;

    /** 基础圆角半径（逻辑像素，需乘以主题的 radiusScale）。 */
    public static final float RADIUS_WINDOW = 12.0f;
    public static final float RADIUS_CARD = 8.0f;
    public static final float RADIUS_CONTROL = 6.0f;
    /** 嵌套规则最小内圆角（在非直角模式下不得小于 4）。 */
    public static final float RADIUS_MIN_NESTED = 4.0f;

    /** 唯一允许的描边宽度（逻辑像素）。 */
    public static final float BORDER_WIDTH = 1.0f;

    /** 主窗口阴影参数（逻辑像素）。 */
    public static final float SHADOW_WINDOW_OFFSET_Y = 16.0f;
    public static final float SHADOW_WINDOW_BLUR = 48.0f;
    public static final float SHADOW_WINDOW_ALPHA_FACTOR = 1.0f;

    /** 弹出层（下拉、取色器、提示）阴影参数（逻辑像素）。 */
    public static final float SHADOW_POPUP_OFFSET_Y = 8.0f;
    public static final float SHADOW_POPUP_BLUR = 24.0f;
    public static final float SHADOW_POPUP_ALPHA_FACTOR = 0.9f;

    /** 动效时长常量（毫秒）。 */
    public static final long DURATION_FAST_MS = 120L;
    public static final long DURATION_NORMAL_MS = 180L;
    public static final long DURATION_SLOW_MS = 240L;
    public static final float CLOSE_DURATION_MULTIPLIER = 0.75f;

    private UiTokens() {
    }

    /**
     * 计算胶囊圆角半径（高度的一半）。
     *
     * @param height 控件高度（逻辑像素）
     * @return 胶囊圆角半径
     */
    public static float pillRadius(float height) {
        return Math.max(0.0f, height * 0.5f);
    }

    /**
     * 计算受主题 {@code radiusScale} 约束的胶囊圆角半径：常规情况恒为高度/2，{@code radiusScale <= 0} 时变为直角 0。
     *
     * @param height 控件高度（逻辑像素）
     * @param radiusScale 主题圆角倍率
     * @return 胶囊圆角半径或 0
     */
    public static float pillRadius(float height, float radiusScale) {
        if (radiusScale <= 0.0f || height <= 0.0f) {
            return 0.0f;
        }
        return height * 0.5f;
    }

    /**
     * 按规范嵌套规则计算内层圆角：{@code max(min(4, outer), outer - spacing)}；若外层已是直角则返回 0。
     *
     * @param outerScaledRadius 外层缩放后的圆角半径
     * @param spacing 外层与内层之间的间距
     * @return 内层圆角半径
     */
    public static float nestedRadius(float outerScaledRadius, float spacing) {
        if (outerScaledRadius <= 0.0f) {
            return 0.0f;
        }
        float minFloor = Math.min(RADIUS_MIN_NESTED, outerScaledRadius);
        return Math.max(minFloor, outerScaledRadius - Math.max(0.0f, spacing));
    }
}
