package com.hoptimization.ui.theme;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主题管理器：负责内置只读主题、自定义主题目录加载/保存/导入导出，以及基于真实时间的主题/强调色平滑插值切换。
 * <p>
 * 静止状态下调用 {@link #current()} 为零对象分配；过渡期间复用起止快照并按帧缓存插值结果。
 */
public final class ThemeManager {

    private static final long TRANSITION_DURATION_NANOS = UiTokens.DURATION_NORMAL_MS * 1_000_000L;
    private static final ThemeManager INSTANCE = new ThemeManager();

    private final Map<String, ThemeData> builtinThemes = new LinkedHashMap<String, ThemeData>(16);
    private final Map<String, ThemeData> customThemes = new LinkedHashMap<String, ThemeData>();

    private File themesDirectory;
    private boolean customLoaded = false;

    private ThemeData activeBaseTheme;
    private Integer accentOverride;
    private boolean reduceMotion;

    private ThemeData fromTheme;
    private ThemeData targetTheme;
    private ThemeData cachedInterpolated;
    private float lastCachedEased = -1.0f;
    private long transitionStartNanos;
    private boolean transitioning = false;

    private ThemeManager() {
        for (ThemeData builtin : BuiltinThemes.ALL) {
            builtinThemes.put(builtin.getId(), builtin);
        }
        this.activeBaseTheme = BuiltinThemes.OBSIDIAN_GOLD;
        this.targetTheme = BuiltinThemes.OBSIDIAN_GOLD;
        this.fromTheme = BuiltinThemes.OBSIDIAN_GOLD;
    }

    /**
     * 获取全局单例实例。
     *
     * @return {@link ThemeManager} 实例
     */
    public static ThemeManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取当前帧生效的主题 token 集合（过渡期间返回平滑插值后的结果，静止时零分配）。
     *
     * @return 当前主题 {@link ThemeData}
     */
    public static ThemeData current() {
        return INSTANCE.getCurrentTheme();
    }

    /**
     * 获取当前生效的 {@link ThemeData}（过渡期间基于 {@link System#nanoTime()} 插值）。
     *
     * @return 当前主题
     */
    public ThemeData getCurrentTheme() {
        if (!transitioning) {
            return targetTheme;
        }
        if (reduceMotion) {
            finishTransition();
            return targetTheme;
        }
        long elapsed = System.nanoTime() - transitionStartNanos;
        if (elapsed >= TRANSITION_DURATION_NANOS) {
            finishTransition();
            return targetTheme;
        }
        if (elapsed <= 0L) {
            return fromTheme;
        }
        float raw = elapsed / (float) TRANSITION_DURATION_NANOS;
        float eased = easeOutCubic(raw);
        // 同一帧内多控件多次调用 current() 时直接复用本次插值快照
        if (cachedInterpolated != null && Math.abs(eased - lastCachedEased) < 0.0002f) {
            return cachedInterpolated;
        }
        cachedInterpolated = ThemeData.interpolate(fromTheme, targetTheme, eased);
        lastCachedEased = eased;
        return cachedInterpolated;
    }

    /**
     * 配置自定义主题目录并重新扫描加载（可用于初始化或测试隔离目录）。
     *
     * @param directory 自定义主题目录（{@code config/hoptimization/themes/}）
     */
    public void setThemesDirectory(File directory) {
        this.themesDirectory = directory;
        this.customLoaded = false;
        reloadCustomThemes();
    }

    /**
     * 获取自定义主题存放目录（必要时自动创建）。
     *
     * @return 主题目录 {@link File}
     */
    public File getThemesDirectory() {
        if (themesDirectory == null) {
            themesDirectory = resolveDefaultThemesDirectory();
        }
        if (!themesDirectory.exists() && !themesDirectory.mkdirs()) {
            HoptimizationMod.LOGGER.warn("Unable to create themes directory '{}'",
                    themesDirectory.getAbsolutePath());
        }
        return themesDirectory;
    }

    /**
     * 从自定义主题目录重新加载所有 {@code *.json} 主题文件。
     */
    public void reloadCustomThemes() {
        customThemes.clear();
        customLoaded = true;
        File dir = getThemesDirectory();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                continue;
            }
            ThemeData loaded = ThemeSerializer.loadFromFile(file);
            if (loaded == null) {
                continue;
            }
            String safeId = sanitizeId(loaded.getId());
            if (isBuiltin(safeId) || customThemes.containsKey(safeId)) {
                String uniqueId = ensureUniqueCustomId(safeId);
                HoptimizationMod.LOGGER.warn(
                        "Custom theme id '{}' from '{}' conflicts with existing theme; registered as '{}'.",
                        loaded.getId(), file.getName(), uniqueId);
                loaded = loaded.toBuilder().id(uniqueId).build();
            } else if (!safeId.equals(loaded.getId())) {
                loaded = loaded.toBuilder().id(safeId).build();
            }
            customThemes.put(loaded.getId(), loaded);
        }
    }

    private void ensureCustomLoaded() {
        if (!customLoaded) {
            reloadCustomThemes();
        }
    }

    /**
     * 获取全部主题列表（10 套内置主题在前，自定义主题在后）。
     *
     * @return 主题列表快照
     */
    public List<ThemeData> getAllThemes() {
        ensureCustomLoaded();
        List<ThemeData> result = new ArrayList<ThemeData>(builtinThemes.size() + customThemes.size());
        result.addAll(builtinThemes.values());
        result.addAll(customThemes.values());
        return result;
    }

    /**
     * 获取只读内置主题列表。
     *
     * @return 内置主题列表
     */
    public List<ThemeData> getBuiltinThemes() {
        return BuiltinThemes.ALL;
    }

    /**
     * 获取自定义主题列表快照。
     *
     * @return 自定义主题列表
     */
    public List<ThemeData> getCustomThemes() {
        ensureCustomLoaded();
        return Collections.unmodifiableList(new ArrayList<ThemeData>(customThemes.values()));
    }

    /**
     * 按 ID 查找主题（内置优先，支持旧版本内置 ID 别名查找，其次自定义）。
     *
     * @param id 主题 ID
     * @return 找到的 {@link ThemeData}，不存在则返回 {@code null}
     */
    public ThemeData getTheme(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        ThemeData builtin = builtinThemes.get(normalized);
        if (builtin == null) {
            builtin = BuiltinThemes.findById(normalized);
        }
        if (builtin != null) {
            return builtin;
        }
        ensureCustomLoaded();
        ThemeData exact = customThemes.get(id.trim());
        return exact != null ? exact : customThemes.get(normalized);
    }

    /**
     * 判断给定主题 ID 是否为只读内置主题。
     *
     * @param id 主题 ID
     * @return 是否内置
     */
    public boolean isBuiltin(String id) {
        return BuiltinThemes.isBuiltin(id);
    }

    /**
     * 获取当前激活的基础主题 ID（不含全局强调色覆盖修饰）。
     *
     * @return 基础主题 ID
     */
    public String getActiveThemeId() {
        return activeBaseTheme.getId();
    }

    /**
     * 获取当前激活的基础主题。
     *
     * @return 基础主题
     */
    public ThemeData getActiveBaseTheme() {
        return activeBaseTheme;
    }

    /**
     * 获取当前全局强调色覆盖值（{@code null} 表示跟随主题自带强调色）。
     *
     * @return ARGB 强调色或 {@code null}
     */
    public Integer getAccentOverride() {
        return accentOverride;
    }

    /**
     * 是否开启了“减少动效”。
     *
     * @return 减少动效状态
     */
    public boolean isReduceMotion() {
        return reduceMotion;
    }

    /**
     * 设置“减少动效”开关；开启时进行中的主题过渡将立即完成。
     *
     * @param reduceMotion 是否减少动效
     */
    public void setReduceMotion(boolean reduceMotion) {
        this.reduceMotion = reduceMotion;
        if (reduceMotion && transitioning) {
            finishTransition();
        }
    }

    /**
     * 是否正在进行主题/强调色平滑过渡。
     *
     * @return 是否过渡中
     */
    public boolean isTransitioning() {
        if (!transitioning) {
            return false;
        }
        if (reduceMotion || (System.nanoTime() - transitionStartNanos) >= TRANSITION_DURATION_NANOS) {
            finishTransition();
            return false;
        }
        return true;
    }

    /**
     * 按 ID 平滑切换主题（180ms easeOutCubic）。
     *
     * @param id 目标主题 ID
     * @return 若目标主题存在并已切换/开始过渡则返回 {@code true}
     */
    public boolean setTheme(String id) {
        ThemeData found = getTheme(id);
        if (found == null) {
            HoptimizationMod.LOGGER.warn("Attempted to switch to unknown theme '{}'.", id);
            return false;
        }
        setTheme(found);
        return true;
    }

    /**
     * 平滑切换到指定主题实例（保留当前全局强调色覆盖）。
     *
     * @param theme 目标主题
     */
    public void setTheme(ThemeData theme) {
        if (theme == null) {
            return;
        }
        this.activeBaseTheme = theme;
        beginTransitionTo(applyAccentOverride(theme, this.accentOverride));
    }

    /**
     * 平滑切换全局强调色覆盖（传 {@code null} 恢复为“跟随主题”）。
     *
     * @param accentArgb 新的强调色（ARGB）或 {@code null}
     */
    public void setAccentOverride(Integer accentArgb) {
        this.accentOverride = accentArgb;
        beginTransitionTo(applyAccentOverride(this.activeBaseTheme, accentArgb));
    }

    /**
     * 启动期无动画直接应用主题与强调色（未知主题时回退黑曜流金主题）。
     *
     * @param themeId 主题 ID
     * @param accentArgb 全局强调色覆盖或 {@code null}
     */
    public void applyImmediate(String themeId, Integer accentArgb) {
        ThemeData resolved = getTheme(themeId);
        if (resolved == null) {
            if (themeId != null && !themeId.trim().isEmpty()) {
                HoptimizationMod.LOGGER.warn("Configured theme '{}' not found, falling back to 'obsidian_gold'.",
                        themeId);
            }
            resolved = BuiltinThemes.OBSIDIAN_GOLD;
        }
        this.activeBaseTheme = resolved;
        this.accentOverride = accentArgb;
        this.targetTheme = applyAccentOverride(resolved, accentArgb);
        this.fromTheme = this.targetTheme;
        this.cachedInterpolated = null;
        this.transitioning = false;
    }

    /**
     * 基于现有主题创建一个新的自定义副本并保存至磁盘。
     *
     * @param sourceId 源主题 ID
     * @param newName 新主题名称（传 null/空时使用“&lt;源主题名&gt; 副本”）
     * @return 创建成功的自定义 {@link ThemeData}，源主题不存在或写盘失败时返回 {@code null}
     */
    public ThemeData duplicateTheme(String sourceId, String newName) {
        ensureCustomLoaded();
        ThemeData source = getTheme(sourceId);
        if (source == null) {
            return null;
        }
        String resolvedName = (newName == null || newName.trim().isEmpty())
                ? source.getName() + " 副本"
                : newName.trim();
        String newId = ensureUniqueCustomId(sanitizeId(source.getId() + "_copy"));
        ThemeData copy = source.toBuilder()
                .id(newId)
                .name(resolvedName)
                .build();
        if (!saveCustomTheme(copy)) {
            return null;
        }
        return copy;
    }

    /**
     * 保存自定义主题到磁盘并更新注册表；禁止覆写内置只读主题。
     *
     * @param theme 待保存的自定义主题
     * @return 是否保存成功
     */
    public boolean saveCustomTheme(ThemeData theme) {
        if (theme == null) {
            return false;
        }
        ensureCustomLoaded();
        String id = sanitizeId(theme.getId());
        if (isBuiltin(id)) {
            HoptimizationMod.LOGGER.warn("Refused to overwrite read-only builtin theme '{}'.", id);
            return false;
        }
        ThemeData normalized = id.equals(theme.getId()) ? theme : theme.toBuilder().id(id).build();
        File target = new File(getThemesDirectory(), id + ".json");
        if (!ThemeSerializer.saveToFile(normalized, target)) {
            return false;
        }
        customThemes.put(id, normalized);
        if (activeBaseTheme.getId().equals(id)) {
            setTheme(normalized);
        }
        return true;
    }

    /**
     * 重命名已有自定义主题的显示名称并写盘。
     *
     * @param id 自定义主题 ID
     * @param newName 新的显示名称
     * @return 是否重命名成功
     */
    public boolean renameCustomTheme(String id, String newName) {
        if (id == null || newName == null || newName.trim().isEmpty() || isBuiltin(id)) {
            return false;
        }
        ensureCustomLoaded();
        ThemeData existing = customThemes.get(id);
        if (existing == null) {
            return false;
        }
        ThemeData renamed = existing.toBuilder().name(newName.trim()).build();
        return saveCustomTheme(renamed);
    }

    /**
     * 删除自定义主题及对应 JSON 文件；若当前正使用该主题则平滑切回黑曜流金主题。
     *
     * @param id 自定义主题 ID
     * @return 是否删除成功
     */
    public boolean deleteCustomTheme(String id) {
        if (id == null || isBuiltin(id)) {
            return false;
        }
        ensureCustomLoaded();
        ThemeData removed = customThemes.remove(id);
        if (removed == null) {
            return false;
        }
        File file = new File(getThemesDirectory(), id + ".json");
        if (file.exists() && !file.delete()) {
            HoptimizationMod.LOGGER.warn("Failed to delete custom theme file '{}'", file.getAbsolutePath());
        }
        if (activeBaseTheme.getId().equals(id)) {
            setTheme(BuiltinThemes.OBSIDIAN_GOLD);
        }
        return true;
    }

    /**
     * 从 JSON 文本导入自定义主题并保存到磁盘（ID 冲突时自动生成唯一后缀）。
     *
     * @param json 主题 JSON 文本
     * @return 导入成功的 {@link ThemeData}，JSON 非法或保存失败时返回 {@code null}
     */
    public ThemeData importThemeFromJson(String json) {
        ensureCustomLoaded();
        ThemeData parsed = ThemeSerializer.fromJson(json, "imported");
        if (parsed == null) {
            return null;
        }
        String baseId = sanitizeId(parsed.getId());
        String uniqueId = ensureUniqueCustomId(baseId);
        ThemeData ready = parsed.toBuilder().id(uniqueId).build();
        if (!saveCustomTheme(ready)) {
            return null;
        }
        return ready;
    }

    /**
     * 导出指定主题为格式化 JSON 字符串。
     *
     * @param id 主题 ID
     * @return JSON 字符串，主题不存在则返回 {@code null}
     */
    public String exportThemeToJson(String id) {
        ThemeData theme = getTheme(id);
        if (theme == null) {
            return null;
        }
        return ThemeSerializer.toJson(theme);
    }

    private void beginTransitionTo(ThemeData newTarget) {
        ThemeData currentVisual = getCurrentTheme();
        this.targetTheme = newTarget;
        if (reduceMotion) {
            this.fromTheme = newTarget;
            this.cachedInterpolated = null;
            this.transitioning = false;
            return;
        }
        this.fromTheme = currentVisual;
        this.cachedInterpolated = null;
        this.lastCachedEased = -1.0f;
        this.transitionStartNanos = System.nanoTime();
        this.transitioning = true;
    }

    private void finishTransition() {
        this.fromTheme = this.targetTheme;
        this.cachedInterpolated = null;
        this.lastCachedEased = 1.0f;
        this.transitioning = false;
    }

    private String ensureUniqueCustomId(String baseId) {
        String candidate = sanitizeId(baseId);
        if (!isBuiltin(candidate) && !customThemes.containsKey(candidate)) {
            return candidate;
        }
        int suffix = 1;
        while (true) {
            String next = candidate + "_" + suffix;
            if (!isBuiltin(next) && !customThemes.containsKey(next)) {
                return next;
            }
            suffix++;
        }
    }

    private static ThemeData applyAccentOverride(ThemeData base, Integer accentArgb) {
        if (accentArgb == null) {
            return base;
        }
        return base.withAccent(accentArgb.intValue());
    }

    private static float easeOutCubic(float t) {
        if (t <= 0.0f) {
            return 0.0f;
        }
        if (t >= 1.0f) {
            return 1.0f;
        }
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv;
    }

    private static String sanitizeId(String raw) {
        if (raw == null) {
            return "custom";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ' || c == '.') {
                sb.append('_');
            }
        }
        String sanitized = sb.toString();
        if (sanitized.isEmpty()) {
            return "custom";
        }
        return sanitized;
    }

    private static File resolveDefaultThemesDirectory() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.mcDataDir != null) {
                return new File(mc.mcDataDir, "config/hoptimization/themes");
            }
        } catch (Throwable t) {
            // 纯单元测试环境下 Minecraft 单例可能尚未启动，回退相对配置路径
            HoptimizationMod.LOGGER.warn("Minecraft instance unavailable while resolving themes directory, using local path.", t);
        }
        return new File("config/hoptimization/themes");
    }
}
