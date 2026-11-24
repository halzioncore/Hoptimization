package com.hoptimization.ui.theme;

import com.hoptimization.modules.misc.LanguageModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规范定义的 10 套只读内置主题与 8 个强调色预设（参见 {@code 02_美术规范.md} §2.3–§2.4）。
 */
public final class BuiltinThemes {

    /** 1. 黑曜流金 Obsidian Gold（默认深色主题）。 */
    public static final ThemeData OBSIDIAN_GOLD = new ThemeData.Builder()
            .id("obsidian_gold")
            .name("黑曜流金")
            .mode(ThemeMode.DARK)
            .backdrop(0x80090806)
            .surface0(0xFF0E0D0B)
            .surface1(0xFF151310)
            .surface2(0xFF1D1A15)
            .surface3(0xFF26221B)
            .surface4(0xFF312B22)
            .border(0x1AD4AF6A)
            .borderStrong(0x2ED4AF6A)
            .textPrimary(0xFFF4EFE6)
            .textSecondary(0xFFA89E8E)
            .textTertiary(0xFF6B6255)
            .accent(0xFFD4AF6A)
            .textOnAccent(0xFF14110B)
            .thumb(0xFFFFFBF2)
            .success(0xFF42C985)
            .warning(0xFFE8A838)
            .danger(0xFFE85454)
            .shadow(0x80000000)
            .style(new ThemeStyle(1.0f, 1.0f, 0.75f, 1.1f, false, true))
            .build();

    /** 2. 霓虹脉冲 Cyber Pulse（粉紫未来深色主题）。 */
    public static final ThemeData CYBER_PULSE = new ThemeData.Builder()
            .id("cyber_pulse")
            .name("霓虹脉冲")
            .mode(ThemeMode.DARK)
            .backdrop(0x85070510)
            .surface0(0xFF0D0A18)
            .surface1(0xFF130F24)
            .surface2(0xFF1B1531)
            .surface3(0xFF241C40)
            .surface4(0xFF2F2552)
            .border(0x24E066FF)
            .borderStrong(0x3DFF4DB8)
            .textPrimary(0xFFF6EBFF)
            .textSecondary(0xFFB79EE0)
            .textTertiary(0xFF735C9C)
            .accent(0xFFFF4DB8)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF2EE6A6)
            .warning(0xFFFFB84D)
            .danger(0xFFFF4D6D)
            .shadow(0x8805020D)
            .style(new ThemeStyle(0.85f, 0.96f, 0.75f, 1.2f, false, true))
            .build();

    /** 3. 桃夭新茗 Spring Blossom（粉绿清新浅色主题）。 */
    public static final ThemeData SPRING_BLOSSOM = new ThemeData.Builder()
            .id("spring_blossom")
            .name("桃夭新茗")
            .mode(ThemeMode.LIGHT)
            .backdrop(0x4214261D)
            .surface0(0xFFEBF5EE)
            .surface1(0xFFFDF8FA)
            .surface2(0xFFF2F9F4)
            .surface3(0xFFE4F2E9)
            .surface4(0xFFD4E9DC)
            .border(0x1F2E6B4E)
            .borderStrong(0x33E85D88)
            .textPrimary(0xFF1A2E23)
            .textSecondary(0xFF4E6E5D)
            .textTertiary(0xFF82A390)
            .accent(0xFFE85D88)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF22A86B)
            .warning(0xFFD9941A)
            .danger(0xFFE0485A)
            .shadow(0x241A3326)
            .style(new ThemeStyle(1.15f, 1.0f, 0.82f, 1.0f, false, true))
            .build();

    /** 4. 深海极光 Abyssal Cyan（深海青蓝主题）。 */
    public static final ThemeData ABYSSAL_CYAN = new ThemeData.Builder()
            .id("abyssal_cyan")
            .name("深海极光")
            .mode(ThemeMode.DARK)
            .backdrop(0x8004080D)
            .surface0(0xFF081018)
            .surface1(0xFF0C1722)
            .surface2(0xFF12202E)
            .surface3(0xFF192B3C)
            .surface4(0xFF22384D)
            .border(0x1A00E5CC)
            .borderStrong(0x2E00E5CC)
            .textPrimary(0xFFE6F7FA)
            .textSecondary(0xFF88AEB8)
            .textTertiary(0xFF4F6F7A)
            .accent(0xFF00E5CC)
            .textOnAccent(0xFF041A17)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFF5B544)
            .danger(0xFFF2555A)
            .shadow(0x80000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 5. 绯红月蚀 Crimson Eclipse（暗红炽烈主题）。 */
    public static final ThemeData CRIMSON_ECLIPSE = new ThemeData.Builder()
            .id("crimson_eclipse")
            .name("绯红月蚀")
            .mode(ThemeMode.DARK)
            .backdrop(0x800A0406)
            .surface0(0xFF12090C)
            .surface1(0xFF1A0D11)
            .surface2(0xFF241318)
            .surface3(0xFF301A21)
            .surface4(0xFF3D222B)
            .border(0x1AFF4D6D)
            .borderStrong(0x2EFF4D6D)
            .textPrimary(0xFFF7E8EC)
            .textSecondary(0xFFB88F9A)
            .textTertiary(0xFF75525C)
            .accent(0xFFF23F5D)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFF5B544)
            .danger(0xFFFF4D6D)
            .shadow(0x80000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 6. 幽林萤火 Emerald Grove（深翠森林主题）。 */
    public static final ThemeData EMERALD_GROVE = new ThemeData.Builder()
            .id("emerald_grove")
            .name("幽林萤火")
            .mode(ThemeMode.DARK)
            .backdrop(0x78040A07)
            .surface0(0xFF09120E)
            .surface1(0xFF0E1A14)
            .surface2(0xFF14241C)
            .surface3(0xFF1C3026)
            .surface4(0xFF253E31)
            .border(0x176BFFB8)
            .borderStrong(0x296BFFB8)
            .textPrimary(0xFFE6F4EC)
            .textSecondary(0xFF8EB09C)
            .textTertiary(0xFF526E5E)
            .accent(0xFF34E090)
            .textOnAccent(0xFF051A10)
            .thumb(0xFFFFFFFF)
            .success(0xFF34E090)
            .warning(0xFFF5B544)
            .danger(0xFFF2555A)
            .shadow(0x78000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 7. 落日琥珀 Sunset Amber（暖棕落日主题）。 */
    public static final ThemeData SUNSET_AMBER = new ThemeData.Builder()
            .id("sunset_amber")
            .name("落日琥珀")
            .mode(ThemeMode.DARK)
            .backdrop(0x780C0604)
            .surface0(0xFF140D0B)
            .surface1(0xFF1D1310)
            .surface2(0xFF271A16)
            .surface3(0xFF33221D)
            .surface4(0xFF402C25)
            .border(0x1AFF9E66)
            .borderStrong(0x2EFF9E66)
            .textPrimary(0xFFF9ECE6)
            .textSecondary(0xFFBA9A8C)
            .textTertiary(0xFF785D52)
            .accent(0xFFFF7A3D)
            .textOnAccent(0xFF1A0900)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFFFB03B)
            .danger(0xFFF2555A)
            .shadow(0x78000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 8. 星穹靛紫 Starlight Indigo（深邃星夜主题）。 */
    public static final ThemeData STARLIGHT_INDIGO = new ThemeData.Builder()
            .id("starlight_indigo")
            .name("星穹靛紫")
            .mode(ThemeMode.DARK)
            .backdrop(0x8005070D)
            .surface0(0xFF0A0D18)
            .surface1(0xFF101424)
            .surface2(0xFF161C31)
            .surface3(0xFF1E2640)
            .surface4(0xFF283252)
            .border(0x178FA2FF)
            .borderStrong(0x298FA2FF)
            .textPrimary(0xFFE8ECFA)
            .textSecondary(0xFF919CC0)
            .textTertiary(0xFF566082)
            .accent(0xFF6E7BFF)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF3DD68C)
            .warning(0xFFF5B544)
            .danger(0xFFF2555A)
            .shadow(0x80000000)
            .style(ThemeStyle.defaultFor(ThemeMode.DARK))
            .build();

    /** 9. 极地冰川 Arctic Frost（冷调浅色主题，默认开启卡片 1px 描边）。 */
    public static final ThemeData ARCTIC_FROST = new ThemeData.Builder()
            .id("arctic_frost")
            .name("极地冰川")
            .mode(ThemeMode.LIGHT)
            .backdrop(0x470B1528)
            .surface0(0xFFEBF1F7)
            .surface1(0xFFF9FCFF)
            .surface2(0xFFF1F6FB)
            .surface3(0xFFE4EDF6)
            .surface4(0xFFD5E2F0)
            .border(0x171E3A5F)
            .borderStrong(0x291E3A5F)
            .textPrimary(0xFF0F1D33)
            .textSecondary(0xFF4A607D)
            .textTertiary(0xFF8498B3)
            .accent(0xFF2563EB)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF1FA86A)
            .warning(0xFFD9961A)
            .danger(0xFFE0434A)
            .shadow(0x240F1D33)
            .style(ThemeStyle.defaultFor(ThemeMode.LIGHT))
            .build();

    /** 10. 晴空薰衣草 Lavender Mist（柔紫浅色主题，默认开启卡片 1px 描边）。 */
    public static final ThemeData LAVENDER_MIST = new ThemeData.Builder()
            .id("lavender_mist")
            .name("晴空薰衣草")
            .mode(ThemeMode.LIGHT)
            .backdrop(0x471A1029)
            .surface0(0xFFF0ECF7)
            .surface1(0xFFFBF9FE)
            .surface2(0xFFF5F1FB)
            .surface3(0xFFEAE3F5)
            .surface4(0xFFDDD3ED)
            .border(0x173B235E)
            .borderStrong(0x293B235E)
            .textPrimary(0xFF1E162B)
            .textSecondary(0xFF5E5073)
            .textTertiary(0xFF9688AD)
            .accent(0xFF7C4DFF)
            .textOnAccent(0xFFFFFFFF)
            .thumb(0xFFFFFFFF)
            .success(0xFF1FA86A)
            .warning(0xFFD9961A)
            .danger(0xFFE0434A)
            .shadow(0x24261642)
            .style(ThemeStyle.defaultFor(ThemeMode.LIGHT))
            .build();

    /** 向后兼容旧常量别名：石墨 -> 黑曜流金。 */
    public static final ThemeData GRAPHITE = OBSIDIAN_GOLD;
    /** 向后兼容旧常量别名：午夜 -> 星穹靛紫。 */
    public static final ThemeData MIDNIGHT = STARLIGHT_INDIGO;
    /** 向后兼容旧常量别名：纯黑 OLED -> 黑曜流金。 */
    public static final ThemeData OLED = OBSIDIAN_GOLD;
    /** 向后兼容旧常量别名：松林 -> 幽林萤火。 */
    public static final ThemeData FOREST = EMERALD_GROVE;
    /** 向后兼容旧常量别名：雾白 -> 极地冰川。 */
    public static final ThemeData MIST = ARCTIC_FROST;
    /** 向后兼容旧常量别名：暖沙 -> 晴空薰衣草。 */
    public static final ThemeData SAND = LAVENDER_MIST;

    /** 按规范顺序排列的全部 10 套内置主题。 */
    public static final List<ThemeData> ALL;
    private static final Map<String, ThemeData> BY_ID;

    /** 8 个预设强调色（参见 {@code 02 §2.4}）。 */
    public static final List<AccentPreset> ACCENT_PRESETS;

    static {
        List<ThemeData> list = new ArrayList<ThemeData>(10);
        list.add(OBSIDIAN_GOLD);
        list.add(CYBER_PULSE);
        list.add(SPRING_BLOSSOM);
        list.add(ABYSSAL_CYAN);
        list.add(CRIMSON_ECLIPSE);
        list.add(EMERALD_GROVE);
        list.add(SUNSET_AMBER);
        list.add(STARLIGHT_INDIGO);
        list.add(ARCTIC_FROST);
        list.add(LAVENDER_MIST);
        ALL = Collections.unmodifiableList(list);

        Map<String, ThemeData> map = new LinkedHashMap<String, ThemeData>(24);
        for (ThemeData theme : list) {
            map.put(theme.getId(), theme);
        }
        // 保留旧版本主题 ID 向后兼容映射，不加入 ALL 列表
        map.put("graphite", GRAPHITE);
        map.put("midnight", MIDNIGHT);
        map.put("oled", OLED);
        map.put("forest", FOREST);
        map.put("mist", MIST);
        map.put("sand", SAND);
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

        public String getNameKey() {
            return "ui.hoptimization.accent." + id;
        }

        public String getLocalizedName() {
            String key = getNameKey();
            try {
                String formatted = LanguageModule.format(key);
                if (formatted != null && !formatted.isEmpty() && !formatted.equals(key)) {
                    return formatted;
                }
            } catch (RuntimeException ignored) {
                // 未初始化时回退默认显示名
            }
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
