package com.hoptimization.ui.theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规范定义的 6 套只读内置主题与 8 个强调色预设（参见 {@code 02_美术规范.md} §2.3–§2.4）。
 */
public final class BuiltinThemes {

    /** 1. 石墨 Graphite（默认深色主题）。 */
    public static final ThemeData GRAPHITE = new ThemeData.Builder()
            .id("graphite")
            .name("石墨")
            .mode(ThemeMode.DARK)
            .backdrop(0x73000000)
            .surface0(0xFF0F1012)
            .surface1(0xFF16171A)
            .surface2(0xFF1D1F23)
            .surface3(0xFF25272C)
            .surface4(0xFF2E3137)
            .border(0x0FFFFFFF)
            .borderStrong(0x1AFFFFFF)
            .textPrimary(0xFFECEDEE)
            .textSecondary(0xFF9BA1A6)
            .textTertiary(0xFF5E636A)
            .accent(0xFF5B8CFF)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFF5B544)
            .danger(0xFFF2555A)
            .shadow(0x73000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 2. 午夜 Midnight（深蓝冷色主题）。 */
    public static final ThemeData MIDNIGHT = new ThemeData.Builder()
            .id("midnight")
            .name("午夜")
            .mode(ThemeMode.DARK)
            .backdrop(0x8005070D)
            .surface0(0xFF0B0E14)
            .surface1(0xFF111520)
            .surface2(0xFF171C29)
            .surface3(0xFF1F2533)
            .surface4(0xFF283040)
            .border(0x12A0B4FF)
            .borderStrong(0x1FA0B4FF)
            .textPrimary(0xFFE6EAF2)
            .textSecondary(0xFF8F99AD)
            .textTertiary(0xFF566075)
            .accent(0xFF7C8CFF)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFF5B544)
            .danger(0xFFF2555A)
            .shadow(0x80000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 3. 纯黑 OLED（高对比纯黑主题，阴影为 0）。 */
    public static final ThemeData OLED = new ThemeData.Builder()
            .id("oled")
            .name("纯黑 OLED")
            .mode(ThemeMode.DARK)
            .backdrop(0x99000000)
            .surface0(0xFF000000)
            .surface1(0xFF0A0A0A)
            .surface2(0xFF121212)
            .surface3(0xFF1A1A1A)
            .surface4(0xFF242424)
            .border(0x14FFFFFF)
            .borderStrong(0x24FFFFFF)
            .textPrimary(0xFFF2F2F2)
            .textSecondary(0xFFA0A0A0)
            .textTertiary(0xFF5C5C5C)
            .accent(0xFF2EC4A6)
            .textOnAccent(0xFF00140F)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFF5B544)
            .danger(0xFFF2555A)
            .shadow(0x00000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 4. 松林 Forest（深绿自然主题）。 */
    public static final ThemeData FOREST = new ThemeData.Builder()
            .id("forest")
            .name("松林")
            .mode(ThemeMode.DARK)
            .backdrop(0x73000000)
            .surface0(0xFF0D1110)
            .surface1(0xFF131816)
            .surface2(0xFF19201D)
            .surface3(0xFF212924)
            .surface4(0xFF2A332D)
            .border(0x0FC8FFE0)
            .borderStrong(0x1AC8FFE0)
            .textPrimary(0xFFE8EEEA)
            .textSecondary(0xFF94A39A)
            .textTertiary(0xFF5A6760)
            .accent(0xFF3DD68C)
            .textOnAccent(0xFF04140C)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFF5B544)
            .danger(0xFFF2555A)
            .shadow(0x73000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 5. 雾白 Mist（浅色主题，默认开启卡片 1px 描边）。 */
    public static final ThemeData MIST = new ThemeData.Builder()
            .id("mist")
            .name("雾白")
            .mode(ThemeMode.LIGHT)
            .backdrop(0x4D0B0D10)
            .surface0(0xFFF1F2F4)
            .surface1(0xFFFFFFFF)
            .surface2(0xFFF7F8F9)
            .surface3(0xFFEEF0F2)
            .surface4(0xFFE3E6EA)
            .border(0x14000000)
            .borderStrong(0x24000000)
            .textPrimary(0xFF16181B)
            .textSecondary(0xFF5F656D)
            .textTertiary(0xFF9BA1A8)
            .accent(0xFF3B6FF0)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF1FA86A)
            .warning(0xFFD9961A)
            .danger(0xFFE0434A)
            .shadow(0x24101828)
            .style(ThemeStyle.defaultFor(ThemeMode.LIGHT))
            .build();

    /** 6. 暖沙 Sand（暖色调浅色主题，默认开启卡片 1px 描边）。 */
    public static final ThemeData SAND = new ThemeData.Builder()
            .id("sand")
            .name("暖沙")
            .mode(ThemeMode.LIGHT)
            .backdrop(0x471E160C)
            .surface0(0xFFEFEBE4)
            .surface1(0xFFFAF8F4)
            .surface2(0xFFF3F0EA)
            .surface3(0xFFEAE5DC)
            .surface4(0xFFDFD8CC)
            .border(0x1A3C2A14)
            .borderStrong(0x293C2A14)
            .textPrimary(0xFF2A241C)
            .textSecondary(0xFF6E6456)
            .textTertiary(0xFFA39886)
            .accent(0xFFD9822B)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF1FA86A)
            .warning(0xFFD9961A)
            .danger(0xFFE0434A)
            .shadow(0x243C2A14)
            .style(ThemeStyle.defaultFor(ThemeMode.LIGHT))
            .build();

    /** 按规范顺序排列的全部 6 套内置主题。 */
    public static final List<ThemeData> ALL;
    private static final Map<String, ThemeData> BY_ID;

    /** 8 个预设强调色（参见 {@code 02 §2.4}）。 */
    public static final List<AccentPreset> ACCENT_PRESETS;

    static {
        List<ThemeData> list = new ArrayList<ThemeData>(6);
        list.add(GRAPHITE);
        list.add(MIDNIGHT);
        list.add(OLED);
        list.add(FOREST);
        list.add(MIST);
        list.add(SAND);
        ALL = Collections.unmodifiableList(list);

        Map<String, ThemeData> map = new LinkedHashMap<String, ThemeData>(8);
        for (ThemeData theme : list) {
            map.put(theme.getId(), theme);
        }
        BY_ID = Collections.unmodifiableMap(map);

        List<AccentPreset> presets = new ArrayList<AccentPreset>(8);
        presets.add(new AccentPreset("blue", "蓝", 0xFF5B8CFF));
        presets.add(new AccentPreset("indigo", "靛", 0xFF7C8CFF));
        presets.add(new AccentPreset("purple", "紫", 0xFF8B7CFF));
        presets.add(new AccentPreset("cyan", "青", 0xFF2EC4A6));
        presets.add(new AccentPreset("green", "绿", 0xFF3DD68C));
        presets.add(new AccentPreset("amber", "琥珀", 0xFFF2A93B));
        presets.add(new AccentPreset("rose", "玫红", 0xFFF2637E));
        presets.add(new AccentPreset("silver", "素白", 0xFFE6E8EB));
        ACCENT_PRESETS = Collections.unmodifiableList(presets);
    }

    private BuiltinThemes() {
    }

    /**
     * 按 ID 查找内置主题（忽略大小写），不存在则返回 {@code null}。
     *
     * @param id 主题 ID
     * @return 内置 {@link ThemeData} 或 {@code null}
     */
    public static ThemeData findById(String id) {
        if (id == null) {
            return null;
        }
        return BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 判断给定 ID 是否属于内置只读主题。
     *
     * @param id 主题 ID
     * @return 是否为内置主题
     */
    public static boolean isBuiltin(String id) {
        return findById(id) != null;
    }

    /**
     * 预设强调色条目，包含自动按亮度推导的 {@code textOnAccent}。
     */
    public static final class AccentPreset {
        private final String id;
        private final String name;
        private final int argb;
        private final int textOnAccent;

        public AccentPreset(String id, String name, int argb) {
            this.id = id;
            this.name = name;
            this.argb = argb;
            this.textOnAccent = ThemeData.computeTextOnAccent(argb);
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getArgb() {
            return argb;
        }

        public int getTextOnAccent() {
            return textOnAccent;
        }
    }
}
