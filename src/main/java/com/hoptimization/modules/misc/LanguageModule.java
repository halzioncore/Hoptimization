package com.hoptimization.modules.misc;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.LanguageManager;
import org.lwjgl.input.Keyboard;

import java.util.Collection;

/**
 * Non-toggleable configuration module for switching Minecraft and mod language live in ClickGUI.
 */
public class LanguageModule extends Module {
    public static final String ID = "language";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_ZH_CN = "zh_cn";
    public static final String MODE_EN_US = "en_us";

    private static final String MC_CODE_ZH_CN = "zh_CN";
    private static final String MC_CODE_EN_US = "en_US";

    private static boolean initialized = false;
    private static String pendingMode = null;
    private static String vanillaLanguageCode = null;
    private static int languageRevision = 0;

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
     * Returns the monotonic counter incremented whenever the active language changes.
     *
     * @return current language revision number
     */
    public static int getLanguageRevision() {
        return languageRevision;
    }

    /**
     * Applies any persisted non-auto language override once Minecraft's font renderer and
     * {@link LanguageManager} are populated during {@code FMLInitializationEvent}.
     */
    public static void applyConfiguredLanguageOnInit() {
        initialized = true;
        String targetMode = pendingMode;
        pendingMode = null;
        if (targetMode == null) {
            ModuleManager manager = HoptimizationMod.getModuleManager();
            LanguageModule module = manager != null ? manager.getModule(LanguageModule.class) : null;
            if (module != null) {
                targetMode = module.getLanguageSetting().getValue();
            }
        }
        if (targetMode == null || MODE_AUTO.equalsIgnoreCase(targetMode)) {
            return;
        }
        applyMode(targetMode, false);
    }

    private void onLanguageSettingChanged() {
        String selectedMode = this.language.getValue();
        if (!initialized) {
            pendingMode = selectedMode;
            return;
        }
        applyMode(selectedMode, true);
    }

    private static void applyMode(String mode, boolean interactive) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        LanguageManager langManager = mc.getLanguageManager();
        if (langManager == null) {
            return;
        }
        Collection<Language> languages = langManager.getLanguages();
        if (languages == null || languages.isEmpty()) {
            return;
        }

        String targetCode;
        if (MODE_AUTO.equalsIgnoreCase(mode)) {
            if (vanillaLanguageCode == null) {
                return;
            }
            targetCode = vanillaLanguageCode;
            vanillaLanguageCode = null;
        } else if (MODE_ZH_CN.equalsIgnoreCase(mode)) {
            if (vanillaLanguageCode == null) {
                vanillaLanguageCode = resolveCurrentLanguageCode(mc, langManager);
            }
            targetCode = MC_CODE_ZH_CN;
        } else if (MODE_EN_US.equalsIgnoreCase(mode)) {
            if (vanillaLanguageCode == null) {
                vanillaLanguageCode = resolveCurrentLanguageCode(mc, langManager);
            }
            targetCode = MC_CODE_EN_US;
        } else {
            HoptimizationMod.LOGGER.warn("Unknown language mode '{}'", mode);
            return;
        }

        Language targetLang = findLanguage(languages, targetCode);
        if (targetLang == null) {
            HoptimizationMod.LOGGER.warn("Minecraft language '{}' not found in LanguageManager", targetCode);
            return;
        }

        applyLanguage(mc, langManager, targetLang, interactive);
    }

    private static void applyLanguage(
        Minecraft mc,
        LanguageManager langManager,
        Language targetLang,
        boolean interactive
    ) {
        langManager.setCurrentLanguage(targetLang);
        if (mc.gameSettings != null) {
            mc.gameSettings.language = targetLang.getLanguageCode();
        }
        if (mc.getResourceManager() != null) {
            try {
                langManager.onResourceManagerReload(mc.getResourceManager());
            } catch (RuntimeException e) {
                HoptimizationMod.LOGGER.error(
                    "Failed to reload locale data for language '{}'",
                    targetLang.getLanguageCode(),
                    e
                );
            }
        }
        if (mc.fontRendererObj != null) {
            boolean forceUnicode = mc.gameSettings != null && mc.gameSettings.forceUnicodeFont;
            mc.fontRendererObj.setUnicodeFlag(langManager.isCurrentLocaleUnicode() || forceUnicode);
            mc.fontRendererObj.setBidiFlag(langManager.isCurrentLanguageBidirectional());
        }
        if (interactive && mc.gameSettings != null) {
            mc.gameSettings.saveOptions();
        }
        languageRevision++;
    }

    private static String resolveCurrentLanguageCode(Minecraft mc, LanguageManager langManager) {
        Language current = langManager.getCurrentLanguage();
        if (current != null && current.getLanguageCode() != null && !current.getLanguageCode().isEmpty()) {
            return current.getLanguageCode();
        }
        if (mc.gameSettings != null && mc.gameSettings.language != null && !mc.gameSettings.language.isEmpty()) {
            return mc.gameSettings.language;
        }
        return MC_CODE_EN_US;
    }

    private static Language findLanguage(Collection<Language> languages, String targetCode) {
        for (Language language : languages) {
            if (language != null && targetCode.equalsIgnoreCase(language.getLanguageCode())) {
                return language;
            }
        }
        return null;
    }
}
