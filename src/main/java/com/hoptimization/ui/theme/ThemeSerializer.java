package com.hoptimization.ui.theme;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.hoptimization.HoptimizationMod;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 主题 JSON 序列化与反序列化工具（参见 {@code 02_美术规范.md} §2.6）。
 * <p>
 * 颜色统一使用 {@code #RRGGBB} 或 {@code #RRGGBBAA}（Alpha 在末尾）；缺失 token 自动用石墨主题补齐，
 * 读取到更高 {@code schema} 版本时打印警告并尽力加载，非法 JSON 不会导致崩溃。
 */
public final class ThemeSerializer {

    public static final int CURRENT_SCHEMA = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final JsonParser JSON_PARSER = new JsonParser();

    private ThemeSerializer() {
    }

    /**
     * 将主题序列化为美化排版的 JSON 字符串。
     *
     * @param theme 待导出的主题
     * @return JSON 字符串
     */
    public static String toJson(ThemeData theme) {
        if (theme == null) {
            throw new IllegalArgumentException("theme must not be null");
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", Integer.valueOf(CURRENT_SCHEMA));
        root.addProperty("id", theme.getId());
        root.addProperty("name", theme.getName());
        root.addProperty("mode", theme.getMode().getId());

        JsonObject colors = new JsonObject();
        colors.addProperty("backdrop", formatHex(theme.backdrop()));
        colors.addProperty("surface0", formatHex(theme.surface0()));
        colors.addProperty("surface1", formatHex(theme.surface1()));
        colors.addProperty("surface2", formatHex(theme.surface2()));
        colors.addProperty("surface3", formatHex(theme.surface3()));
        colors.addProperty("surface4", formatHex(theme.surface4()));
        colors.addProperty("border", formatHex(theme.border()));
        colors.addProperty("borderStrong", formatHex(theme.borderStrong()));
        colors.addProperty("textPrimary", formatHex(theme.textPrimary()));
        colors.addProperty("textSecondary", formatHex(theme.textSecondary()));
        colors.addProperty("textTertiary", formatHex(theme.textTertiary()));
        colors.addProperty("accent", formatHex(theme.accent()));
        colors.addProperty("textOnAccent", formatHex(theme.textOnAccent()));
        colors.addProperty("thumb", formatHex(theme.thumb()));
        colors.addProperty("success", formatHex(theme.success()));
        colors.addProperty("warning", formatHex(theme.warning()));
        colors.addProperty("danger", formatHex(theme.danger()));
        colors.addProperty("shadow", formatHex(theme.shadow()));

        // 派生 token 仅在存在手动覆盖时才写入，未覆盖项保持自动推导
        if (theme.getAccentHoverOverride() != null) {
            colors.addProperty("accentHover", formatHex(theme.getAccentHoverOverride().intValue()));
        }
        if (theme.getAccentMutedOverride() != null) {
            colors.addProperty("accentMuted", formatHex(theme.getAccentMutedOverride().intValue()));
        }
        if (theme.getHudBackgroundOverride() != null) {
            colors.addProperty("hudBackground", formatHex(theme.getHudBackgroundOverride().intValue()));
        }
        if (theme.getScrollbarOverride() != null) {
            colors.addProperty("scrollbar", formatHex(theme.getScrollbarOverride().intValue()));
        }
        root.add("colors", colors);

        ThemeStyle style = theme.style();
        JsonObject styleObj = new JsonObject();
        styleObj.addProperty("radiusScale", Float.valueOf(style.getRadiusScale()));
        styleObj.addProperty("windowOpacity", Float.valueOf(style.getWindowOpacity()));
        styleObj.addProperty("hudOpacity", Float.valueOf(style.getHudOpacity()));
        styleObj.addProperty("shadowStrength", Float.valueOf(style.getShadowStrength()));
        styleObj.addProperty("blur", Boolean.valueOf(style.isBlur()));
        styleObj.addProperty("cardBorder", Boolean.valueOf(style.isCardBorder()));
        root.add("style", styleObj);

        return GSON.toJson(root);
    }

    /**
     * 从 JSON 字符串解析主题；若 JSON 非法或结构无效则返回 {@code null} 并记录警告日志。
     *
     * @param json 主题 JSON 文本
     * @param fallbackId 当 JSON 中缺少 id 时的回退标识
     * @return 解析成功的 {@link ThemeData}，失败返回 {@code null}
     */
    public static ThemeData fromJson(String json, String fallbackId) {
        if (json == null || json.trim().isEmpty()) {
            HoptimizationMod.LOGGER.warn("Failed to parse theme JSON ({}): input is empty.", fallbackId);
            return null;
        }
        JsonElement parsed;
        try {
            parsed = JSON_PARSER.parse(json);
        } catch (JsonSyntaxException e) {
            HoptimizationMod.LOGGER.warn("Invalid theme JSON syntax ({}): {}", fallbackId, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            HoptimizationMod.LOGGER.warn("Failed to parse theme JSON ({})", fallbackId, e);
            return null;
        }

        if (parsed == null || !parsed.isJsonObject()) {
            HoptimizationMod.LOGGER.warn("Invalid theme JSON ({}): root must be a JSON object.", fallbackId);
            return null;
        }

        JsonObject root = parsed.getAsJsonObject();
        ThemeData fallback = BuiltinThemes.GRAPHITE;

        String rawId = readString(root, "id");
        String resolvedId = (rawId != null && !rawId.trim().isEmpty())
                ? rawId.trim()
                : ((fallbackId != null && !fallbackId.trim().isEmpty()) ? fallbackId.trim() : "custom");

        int schema = readInt(root, "schema", CURRENT_SCHEMA, resolvedId);
        if (schema > CURRENT_SCHEMA) {
            HoptimizationMod.LOGGER.warn(
                    "Theme '{}' uses schema {} which is newer than supported schema {}; loading best-effort.",
                    resolvedId, Integer.valueOf(schema), Integer.valueOf(CURRENT_SCHEMA));
        }

        String rawName = readString(root, "name");
        String resolvedName = (rawName != null && !rawName.trim().isEmpty()) ? rawName.trim() : resolvedId;

        String rawMode = readString(root, "mode");
        ThemeMode mode = ThemeMode.fromId(rawMode);
        if (mode == null) {
            if (rawMode != null) {
                HoptimizationMod.LOGGER.warn("Theme '{}' has unknown mode '{}', falling back to '{}'.",
                        resolvedId, rawMode, fallback.getMode().getId());
            }
            mode = fallback.getMode();
        }

        JsonObject colors = readObject(root, "colors", resolvedId);
        ThemeData.Builder builder = new ThemeData.Builder()
                .id(resolvedId)
                .name(resolvedName)
                .mode(mode)
                .backdrop(readColor(colors, "backdrop", fallback.backdrop(), resolvedId))
                .surface0(readColor(colors, "surface0", fallback.surface0(), resolvedId))
                .surface1(readColor(colors, "surface1", fallback.surface1(), resolvedId))
                .surface2(readColor(colors, "surface2", fallback.surface2(), resolvedId))
                .surface3(readColor(colors, "surface3", fallback.surface3(), resolvedId))
                .surface4(readColor(colors, "surface4", fallback.surface4(), resolvedId))
                .border(readColor(colors, "border", fallback.border(), resolvedId))
                .borderStrong(readColor(colors, "borderStrong", fallback.borderStrong(), resolvedId))
                .textPrimary(readColor(colors, "textPrimary", fallback.textPrimary(), resolvedId))
                .textSecondary(readColor(colors, "textSecondary", fallback.textSecondary(), resolvedId))
                .textTertiary(readColor(colors, "textTertiary", fallback.textTertiary(), resolvedId))
                .accent(readColor(colors, "accent", fallback.accent(), resolvedId))
                .textOnAccent(readColor(colors, "textOnAccent", fallback.textOnAccent(), resolvedId))
                .thumb(readColor(colors, "thumb", fallback.thumb(), resolvedId))
                .success(readColor(colors, "success", fallback.success(), resolvedId))
                .warning(readColor(colors, "warning", fallback.warning(), resolvedId))
                .danger(readColor(colors, "danger", fallback.danger(), resolvedId))
                .shadow(readColor(colors, "shadow", fallback.shadow(), resolvedId))
                .accentHoverOverride(readOptionalColor(colors, "accentHover", resolvedId))
                .accentMutedOverride(readOptionalColor(colors, "accentMuted", resolvedId))
                .hudBackgroundOverride(readOptionalColor(colors, "hudBackground", resolvedId))
                .scrollbarOverride(readOptionalColor(colors, "scrollbar", resolvedId));

        JsonObject styleObj = readObject(root, "style", resolvedId);
        ThemeStyle fallbackStyle = fallback.style();
        ThemeStyle style = new ThemeStyle(
                readFloat(styleObj, "radiusScale", fallbackStyle.getRadiusScale(), resolvedId),
                readFloat(styleObj, "windowOpacity", fallbackStyle.getWindowOpacity(), resolvedId),
                readFloat(styleObj, "hudOpacity", fallbackStyle.getHudOpacity(), resolvedId),
                readFloat(styleObj, "shadowStrength", fallbackStyle.getShadowStrength(), resolvedId),
                readBoolean(styleObj, "blur", fallbackStyle.isBlur(), resolvedId),
                readBoolean(styleObj, "cardBorder", fallbackStyle.isCardBorder(), resolvedId)
        );
        builder.style(style);
        return builder.build();
    }

    /**
     * 从磁盘文件读取并反序列化主题，失败时记录错误并返回 {@code null}。
     *
     * @param file JSON 主题文件
     * @return 解析成功的 {@link ThemeData}，失败返回 {@code null}
     */
    public static ThemeData loadFromFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        String fileName = file.getName();
        String stem = fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - 5)
                : fileName;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            return fromJson(content, stem);
        } catch (IOException e) {
            HoptimizationMod.LOGGER.error("Failed to read theme file '{}'", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 使用临时文件 + 原子替换将主题写入磁盘，避免写盘中途崩溃损坏配置。
     *
     * @param theme 待保存的主题
     * @param targetFile 目标 JSON 文件
     * @return 是否保存成功
     */
    public static boolean saveToFile(ThemeData theme, File targetFile) {
        if (theme == null || targetFile == null) {
            return false;
        }
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            HoptimizationMod.LOGGER.error("Failed to create theme directory '{}'", parent.getAbsolutePath());
            return false;
        }
        File tempFile = new File(parent, targetFile.getName() + ".tmp");
        try {
            byte[] bytes = toJson(theme).getBytes(StandardCharsets.UTF_8);
            Files.write(tempFile.toPath(), bytes);
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 部分文件系统/虚拟磁盘不支持跨目录原子标志，降级为普通替换移动
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            HoptimizationMod.LOGGER.error("Failed to save theme '{}' to '{}'",
                    theme.getId(), targetFile.getAbsolutePath(), e);
            if (tempFile.exists() && !tempFile.delete()) {
                HoptimizationMod.LOGGER.warn("Failed to clean up temp theme file '{}'", tempFile.getAbsolutePath());
            }
            return false;
        }
    }

    /**
     * 将 ARGB 颜色格式化为规范字符串：{@code #RRGGBB}（不透明）或 {@code #RRGGBBAA}（含透明通道，AA 在末尾）。
     *
     * @param argb ARGB 颜色
     * @return HEX 字符串
     */
    public static String formatHex(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (a == 0xFF) {
            return String.format(Locale.ROOT, "#%02X%02X%02X",
                    Integer.valueOf(r), Integer.valueOf(g), Integer.valueOf(b));
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X",
                Integer.valueOf(r), Integer.valueOf(g), Integer.valueOf(b), Integer.valueOf(a));
    }

    /**
     * 解析 {@code #RRGGBB} 或 {@code #RRGGBBAA}（AA 在末尾）为 Java ARGB 整数，非法格式返回 {@code null}。
     *
     * @param hex HEX 颜色字符串
     * @return 解析后的 ARGB 整数，或 {@code null}
     */
    public static Integer parseHex(String hex) {
        if (hex == null) {
            return null;
        }
        String trimmed = hex.trim();
        if (!trimmed.startsWith("#")) {
            return null;
        }
        String digits = trimmed.substring(1);
        if (digits.length() != 6 && digits.length() != 8) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            boolean isHex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!isHex) {
                return null;
            }
        }
        try {
            int rgb = (int) Long.parseLong(digits.substring(0, 6), 16);
            int alpha = (digits.length() == 8)
                    ? (int) Long.parseLong(digits.substring(6, 8), 16)
                    : 0xFF;
            return Integer.valueOf((alpha << 24) | (rgb & 0x00FFFFFF));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonObject readObject(JsonObject parent, String key, String themeId) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement el = parent.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonObject()) {
            HoptimizationMod.LOGGER.warn("Theme '{}' field '{}' is not a JSON object, ignoring.", themeId, key);
            return null;
        }
        return el.getAsJsonObject();
    }

    private static String readString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive prim = el.getAsJsonPrimitive();
        return prim.isString() ? prim.getAsString() : null;
    }

    private static int readInt(JsonObject obj, String key, int fallback, String themeId) {
        if (obj == null || !obj.has(key)) {
            return fallback;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        HoptimizationMod.LOGGER.warn("Theme '{}' field '{}' is not an integer, using default {}.",
                themeId, key, Integer.valueOf(fallback));
        return fallback;
    }

    private static float readFloat(JsonObject obj, String key, float fallback, String themeId) {
        if (obj == null || !obj.has(key)) {
            return fallback;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsFloat();
        }
        HoptimizationMod.LOGGER.warn("Theme '{}' style '{}' is not a number, using default {}.",
                themeId, key, Float.valueOf(fallback));
        return fallback;
    }

    private static boolean readBoolean(JsonObject obj, String key, boolean fallback, String themeId) {
        if (obj == null || !obj.has(key)) {
            return fallback;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
            return el.getAsBoolean();
        }
        HoptimizationMod.LOGGER.warn("Theme '{}' style '{}' is not a boolean, using default {}.",
                themeId, key, Boolean.valueOf(fallback));
        return fallback;
    }

    private static int readColor(JsonObject colors, String tokenName, int fallback, String themeId) {
        if (colors == null || !colors.has(tokenName)) {
            return fallback;
        }
        JsonElement el = colors.get(tokenName);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            HoptimizationMod.LOGGER.warn("Theme '{}' color '{}' is not a hex string, falling back to graphite.",
                    themeId, tokenName);
            return fallback;
        }
        Integer parsed = parseHex(el.getAsString());
        if (parsed == null) {
            HoptimizationMod.LOGGER.warn("Theme '{}' color '{}' has invalid hex '{}', falling back to graphite.",
                    themeId, tokenName, el.getAsString());
            return fallback;
        }
        return parsed.intValue();
    }

    private static Integer readOptionalColor(JsonObject colors, String tokenName, String themeId) {
        if (colors == null || !colors.has(tokenName)) {
            return null;
        }
        JsonElement el = colors.get(tokenName);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            HoptimizationMod.LOGGER.warn("Theme '{}' derived color '{}' is not a hex string, using auto.",
                    themeId, tokenName);
            return null;
        }
        Integer parsed = parseHex(el.getAsString());
        if (parsed == null) {
            HoptimizationMod.LOGGER.warn("Theme '{}' derived color '{}' has invalid hex '{}', using auto.",
                    themeId, tokenName, el.getAsString());
            return null;
        }
        return parsed;
    }
}
