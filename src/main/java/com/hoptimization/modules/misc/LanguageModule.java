package com.hoptimization.modules.misc;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.LanguageManager;
import org.lwjgl.input.Keyboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Non-toggleable configuration module for switching mod ClickGUI and HUD language
 * without altering Minecraft's vanilla language or font settings.
 */
public class LanguageModule extends Module {
    public static final String ID = "language";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_ZH_CN = "zh_cn";
    public static final String MODE_EN_US = "en_us";

    private static final String MC_CODE_ZH_CN = "zh_CN";
    private static final String MC_CODE_EN_US = "en_US";
    private static final String LANG_PATH_ZH_CN = "/assets/hoptimization/lang/zh_CN.lang";
    private static final String LANG_PATH_EN_US = "/assets/hoptimization/lang/en_US.lang";

    private static final Map<String, String> ZH_CN_TABLE = loadLangTable(LANG_PATH_ZH_CN);
    private static final Map<String, String> EN_US_TABLE = loadLangTable(LANG_PATH_EN_US);

    private static volatile String activeMode = MODE_AUTO;
    private static volatile int languageRevision = 0;
    private static boolean loggedVanillaReadFailure = false;

    private final ModeSetting language;

    /**
     * Creates the Language switcher module (non-toggleable, defaulting to {@code auto}).
     */
    public LanguageModule() {
        super(ID, Category.MISC, Keyboard.KEY_NONE, false, false);
        this.language = addSetting(
            new ModeSetting(
                "language",
                "setting.hoptimization.language.language.name",
                MODE_AUTO,
                MODE_AUTO,
                MODE_ZH_CN,
                MODE_EN_US
            )
        );
        activeMode = normalizeMode(this.language.getValue());
        this.language.addChangeListener(new Runnable() {
            @Override
            public void run() {
                onLanguageSettingChanged();
            }
        });
    }

    /**
     * Returns the language selection mode setting.
     *
     * @return language mode setting
     */
    public ModeSetting getLanguageSetting() {
        return this.language;
    }

    /**
     * Returns the monotonic counter incremented whenever the active mod language mode changes.
     *
     * @return current language revision number
     */
    public static int getLanguageRevision() {
        return languageRevision;
    }

    /**
     * Synchronizes {@link #activeMode} with the loaded {@link LanguageModule} configuration
     * during {@code FMLInitializationEvent} without touching vanilla {@link LanguageManager}.
     */
    public static void applyConfiguredLanguageOnInit() {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        LanguageModule module = manager != null ? manager.getModule(LanguageModule.class) : null;
        if (module != null) {
            activeMode = normalizeMode(module.getLanguageSetting().getValue());
        }
        languageRevision++;
    }

    /**
     * Resolves the effective mod display language code ({@code "zh_CN"} or {@code "en_US"})
     * without heap allocations on the hot path.
     *
     * @return {@code "zh_CN"} or {@code "en_US"}
     */
    public static String getEffectiveLanguageCode() {
        String mode = activeMode;
        if (MODE_ZH_CN.equalsIgnoreCase(mode)) {
            return MC_CODE_ZH_CN;
        }
        if (MODE_EN_US.equalsIgnoreCase(mode)) {
            return MC_CODE_EN_US;
        }
        String vanillaCode = readVanillaLanguageCode();
        if (startsWithZhIgnoreCase(vanillaCode)) {
            return MC_CODE_ZH_CN;
        }
        return MC_CODE_EN_US;
    }

    /**
     * Resolves a localization key against the mod's active UTF-8 language table with zero
     * allocations on the hot path, falling back to the secondary table and vanilla {@link I18n}.
     *
     * @param key localization key
     * @return localized string, or {@code ""} if {@code key} is null, or {@code key} if missing
     */
    public static String format(String key) {
        if (key == null) {
            return "";
        }
        boolean useZh = MC_CODE_ZH_CN.equals(getEffectiveLanguageCode());
        Map<String, String> primary = useZh ? ZH_CN_TABLE : EN_US_TABLE;
        String value = primary.get(key);
        if (value != null) {
            return value;
        }
        Map<String, String> secondary = useZh ? EN_US_TABLE : ZH_CN_TABLE;
        value = secondary.get(key);
        if (value != null) {
            return value;
        }
        try {
            String vanilla = I18n.format(key);
            if (vanilla != null && !vanilla.isEmpty() && !vanilla.equals(key)) {
                return vanilla;
            }
        } catch (RuntimeException ignored) {
            // Fall back cleanly when called outside initialized MC resource context (e.g. unit tests).
        } catch (LinkageError ignored) {
            // Fall back cleanly in pure unit-test environments without MC/LWJGL runtime classes.
        }
        return key;
    }

    /**
     * Resolves a localization key and formats positional arguments using {@link Locale#ROOT}.
     *
     * @param key  localization key
     * @param args optional format arguments
     * @return formatted localized string
     */
    public static String format(String key, Object... args) {
        if (args == null || args.length == 0) {
            return format(key);
        }
        String template = format(key);
        try {
            return String.format(Locale.ROOT, template, args);
        } catch (RuntimeException e) {
            HoptimizationMod.LOGGER.warn("Failed to format localization template '{}' for key '{}'", template, key, e);
            return template;
        }
    }

    private void onLanguageSettingChanged() {
        activeMode = normalizeMode(this.language.getValue());
        languageRevision++;
    }

    private static String readVanillaLanguageCode() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return null;
            }
            LanguageManager langManager = mc.getLanguageManager();
            if (langManager != null) {
                Language current = langManager.getCurrentLanguage();
                if (current != null) {
                    String code = current.getLanguageCode();
                    if (code != null && !code.isEmpty()) {
                        return code;
                    }
                }
            }
            if (mc.gameSettings != null && mc.gameSettings.language != null && !mc.gameSettings.language.isEmpty()) {
                return mc.gameSettings.language;
            }
        } catch (RuntimeException e) {
            if (!loggedVanillaReadFailure) {
                loggedVanillaReadFailure = true;
                HoptimizationMod.LOGGER.warn("Unable to read vanilla Minecraft language; defaulting to en_US", e);
            }
        } catch (LinkageError e) {
            if (!loggedVanillaReadFailure) {
                loggedVanillaReadFailure = true;
                HoptimizationMod.LOGGER.warn("Unable to read vanilla Minecraft language; defaulting to en_US", e);
            }
        }
        return null;
    }

    private static boolean startsWithZhIgnoreCase(String code) {
        if (code == null || code.length() < 2) {
            return false;
        }
        char c0 = code.charAt(0);
        char c1 = code.charAt(1);
        return (c0 == 'z' || c0 == 'Z') && (c1 == 'h' || c1 == 'H');
    }

    private static String normalizeMode(String mode) {
        if (MODE_ZH_CN.equalsIgnoreCase(mode)) {
            return MODE_ZH_CN;
        }
        if (MODE_EN_US.equalsIgnoreCase(mode)) {
            return MODE_EN_US;
        }
        return MODE_AUTO;
    }

    private static Map<String, String> loadLangTable(String resourcePath) {
        InputStream stream = LanguageModule.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            HoptimizationMod.LOGGER.warn("Mod language resource '{}' not found on classpath", resourcePath);
            return Collections.emptyMap();
        }
        Map<String, String> table = new HashMap<String, String>(256);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                int eqIndex = line.indexOf('=');
                if (eqIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, eqIndex).trim();
                if (key.isEmpty()) {
                    continue;
                }
                table.put(key, line.substring(eqIndex + 1));
            }
        } catch (IOException e) {
            HoptimizationMod.LOGGER.error("Failed to load mod language resource '{}'", resourcePath, e);
        }
        return Collections.unmodifiableMap(table);
    }
}
