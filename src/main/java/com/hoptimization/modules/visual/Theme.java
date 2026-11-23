package com.hoptimization.modules.visual;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.ui.theme.DisplayStyle;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.ThemeSerializer;
import org.lwjgl.input.Keyboard;

import java.util.function.Supplier;

/**
 * Non-toggleable configuration module for switching the global visual theme palette in ClickGUI and via keybind.
 */
public class Theme extends Module {
    public static final String ID = "theme";

    public static final String THEME_OBSIDIAN_GOLD = "obsidian_gold";
    public static final String THEME_CYBER_PULSE = "cyber_pulse";
    public static final String THEME_SPRING_BLOSSOM = "spring_blossom";
    public static final String THEME_ABYSSAL_CYAN = "abyssal_cyan";
    public static final String THEME_CRIMSON_ECLIPSE = "crimson_eclipse";
    public static final String THEME_EMERALD_GROVE = "emerald_grove";
    public static final String THEME_SUNSET_AMBER = "sunset_amber";
    public static final String THEME_STARLIGHT_INDIGO = "starlight_indigo";
    public static final String THEME_ARCTIC_FROST = "arctic_frost";
    public static final String THEME_LAVENDER_MIST = "lavender_mist";
    public static final String DEFAULT_THEME_ID = THEME_OBSIDIAN_GOLD;
    public static final String STYLE_STANDARD = "standard";
    public static final String STYLE_FROSTED_GLASS = "frosted_glass";

    private static boolean initialized = false;
    private static boolean suppressSync = false;

    private final ModeSetting theme;
    private final ModeSetting displayStyle;
    private final NumberSetting blurStrength;

    /**
     * Creates the Theme selector module (non-toggleable, defaulting to {@code obsidian_gold}).
     */
    public Theme() {
        super(ID, Category.VISUAL, Keyboard.KEY_NONE, false, false);
        this.theme = addSetting(
            new ModeSetting(
                "theme",
                "setting.hoptimization.theme.theme.name",
                DEFAULT_THEME_ID,
                THEME_OBSIDIAN_GOLD,
                THEME_CYBER_PULSE,
                THEME_SPRING_BLOSSOM,
                THEME_ABYSSAL_CYAN,
                THEME_CRIMSON_ECLIPSE,
                THEME_EMERALD_GROVE,
                THEME_SUNSET_AMBER,
                THEME_STARLIGHT_INDIGO,
                THEME_ARCTIC_FROST,
                THEME_LAVENDER_MIST
            )
        );
        this.displayStyle = addSetting(
            new ModeSetting(
                "display_style",
                "setting.hoptimization.theme.display_style.name",
                STYLE_STANDARD,
                STYLE_STANDARD,
                STYLE_FROSTED_GLASS
            )
        );
        this.blurStrength = addSetting(
            new NumberSetting(
                "blur_strength",
                "setting.hoptimization.theme.blur_strength.name",
                UiConfig.DEFAULT_BLUR_STRENGTH,
                UiConfig.MIN_BLUR_STRENGTH,
                UiConfig.MAX_BLUR_STRENGTH,
                1.0D,
                "px"
            ).visibleWhen(new Supplier<Boolean>() {
                @Override
                public Boolean get() {
                    return displayStyle.is(STYLE_FROSTED_GLASS);
                }
            })
        );
        this.theme.addChangeListener(new Runnable() {
            @Override
            public void run() {
                onThemeSettingChanged();
            }
        });
        this.displayStyle.addChangeListener(new Runnable() {
            @Override
            public void run() {
                onDisplayStyleChanged();
            }
        });
        this.blurStrength.addChangeListener(new Runnable() {
            @Override
            public void run() {
                onBlurStrengthChanged();
            }
        });
    }

    /**
     * Returns the theme selection mode setting.
     *
     * @return theme mode setting
     */
    public ModeSetting getThemeSetting() {
        return this.theme;
    }

    /**
     * Returns the global display style setting ({@code standard} or {@code frosted_glass}).
     *
     * @return display style mode setting
     */
    public ModeSetting getDisplayStyleSetting() {
        return this.displayStyle;
    }

    /**
     * Returns the frosted glass blur strength setting in pixels.
     *
     * @return blur strength number setting
     */
    public NumberSetting getBlurStrengthSetting() {
        return this.blurStrength;
    }

    /**
     * Applies the active theme immediately during startup and enables animated runtime synchronization.
     *
     * @param uiConfig loaded UI configuration for fallback theme id and accent override
     */
    public static void syncOnStartup(UiConfig uiConfig) {
        initialized = true;

        ModuleManager manager = HoptimizationMod.getModuleManager();
        Theme themeModule = manager != null ? manager.getModule(Theme.class) : null;

        String selectedId = themeModule != null ? themeModule.getThemeSetting().getValue() : null;
        if ((selectedId == null || selectedId.trim().isEmpty()) && uiConfig != null) {
            selectedId = uiConfig.getThemeId();
        }
        if (selectedId == null || selectedId.trim().isEmpty()) {
            selectedId = DEFAULT_THEME_ID;
        }

        Integer accentOverride = null;
        if (uiConfig != null) {
            String rawAccent = uiConfig.getAccentOverride();
            if (rawAccent != null && !rawAccent.trim().isEmpty()) {
                accentOverride = ThemeSerializer.parseHex(rawAccent);
                if (accentOverride == null) {
                    HoptimizationMod.LOGGER.warn("Ignoring invalid accent override '{}' in ui.json", rawAccent);
                }
            }
        }

        suppressSync = true;
        try {
            ThemeManager.getInstance().applyImmediate(selectedId, accentOverride);
            String resolvedStyle = uiConfig != null ? uiConfig.getDisplayStyle() : STYLE_STANDARD;
            if (themeModule != null && STYLE_FROSTED_GLASS.equalsIgnoreCase(themeModule.getDisplayStyleSetting().getValue())) {
                resolvedStyle = STYLE_FROSTED_GLASS;
            }
            DisplayStyle styleEnum = DisplayStyle.fromIdOrDefault(resolvedStyle, DisplayStyle.STANDARD);
            float resolvedBlur = themeModule != null
                ? themeModule.getBlurStrengthSetting().getFloatValue()
                : (uiConfig != null ? uiConfig.getBlurStrength() : UiConfig.DEFAULT_BLUR_STRENGTH);
            ThemeManager.getInstance().setDisplayStyle(styleEnum);
            ThemeManager.getInstance().setBlurStrength(resolvedBlur);
            if (themeModule != null) {
                themeModule.getDisplayStyleSetting().setValue(styleEnum.getId());
                themeModule.getBlurStrengthSetting().setValue(Double.valueOf(resolvedBlur));
            }
            if (uiConfig != null) {
                uiConfig.setThemeId(selectedId);
                uiConfig.setDisplayStyle(styleEnum.getId());
                uiConfig.setBlurStrength(resolvedBlur);
            }
        } finally {
            suppressSync = false;
        }
    }

    private void onThemeSettingChanged() {
        // Skip startup profile loading so ThemeManager does not trigger premature transitions or disk writes.
        if (!initialized || suppressSync) {
            return;
        }
        String selectedId = this.theme.getValue();
        if (selectedId == null || selectedId.trim().isEmpty()) {
            return;
        }
        suppressSync = true;
        try {
            ThemeManager.getInstance().setTheme(selectedId);
            ConfigManager configManager = HoptimizationMod.getConfigManager();
            if (configManager != null) {
                configManager.getUiConfig().setThemeId(selectedId);
                configManager.saveUiConfigDebounced();
            }
        } finally {
            suppressSync = false;
        }
    }

    private void onDisplayStyleChanged() {
        if (!initialized || suppressSync) {
            return;
        }
        DisplayStyle styleEnum = DisplayStyle.fromIdOrDefault(this.displayStyle.getValue(), DisplayStyle.STANDARD);
        suppressSync = true;
        try {
            ThemeManager.getInstance().setDisplayStyle(styleEnum);
            ConfigManager configManager = HoptimizationMod.getConfigManager();
            if (configManager != null) {
                configManager.getUiConfig().setDisplayStyle(styleEnum.getId());
                configManager.saveUiConfigDebounced();
            }
        } finally {
            suppressSync = false;
        }
    }

    private void onBlurStrengthChanged() {
        if (!initialized || suppressSync) {
            return;
        }
        float strength = this.blurStrength.getFloatValue();
        suppressSync = true;
        try {
            ThemeManager.getInstance().setBlurStrength(strength);
            ConfigManager configManager = HoptimizationMod.getConfigManager();
            if (configManager != null) {
                configManager.getUiConfig().setBlurStrength(strength);
                configManager.saveUiConfigDebounced();
            }
        } finally {
            suppressSync = false;
        }
    }
}
