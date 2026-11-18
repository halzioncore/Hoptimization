package com.hoptimization.modules.misc;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.Setting;
import com.hoptimization.ui.input.ImeInputBridge;

import java.util.List;

/**
 * Enables Windows IME Chinese input and optional pre-edit composition display inside text screens
 * and the ClickGUI search bar.
 */
public class ChineseInput extends Module {
    public static final String ID = "chinese_input";

    private static volatile boolean defaultEnabled = true;
    private static volatile ChineseInput instance;

    private final BooleanSetting showCompositionBar;
    private final BooleanSetting enableInChat;
    private final BooleanSetting enableInClickGui;

    /**
     * Creates the Chinese Input module (enabled by default) and registers its settings and IME bridge.
     */
    public ChineseInput() {
        super(ID, Category.MISC);
        this.showCompositionBar = addSetting(
            new BooleanSetting(
                "show_composition_bar",
                "setting.hoptimization.chinese_input.show_composition_bar.name",
                true
            )
        );
        this.enableInChat = addSetting(
            new BooleanSetting(
                "enable_in_chat",
                "setting.hoptimization.chinese_input.enable_in_chat.name",
                true
            )
        );
        this.enableInClickGui = addSetting(
            new BooleanSetting(
                "enable_in_clickgui",
                "setting.hoptimization.chinese_input.enable_in_clickgui.name",
                true
            )
        );

        instance = this;
        ImeInputBridge.ensureRegistered();
        if (defaultEnabled) {
            // Safe during construction because ModuleManager has not bound ownerManager/changeListener yet.
            setEnabled(true);
        }
    }

    /**
     * Returns whether new or reset {@link ChineseInput} instances default to enabled.
     *
     * @return {@code true} when enabled by default
     */
    public static boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    /**
     * Configures whether {@link ChineseInput} defaults to enabled during construction and profile resets.
     *
     * @param enabled default enabled flag
     */
    public static void setDefaultEnabled(boolean enabled) {
        defaultEnabled = enabled;
        ChineseInput active = getInstance();
        if (active != null && active.getOwnerManager() == null) {
            active.setEnabled(enabled);
        }
    }

    /**
     * Returns the registered {@link ChineseInput} module instance if available.
     *
     * @return module instance, or {@code null} before creation
     */
    public static ChineseInput getInstance() {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        if (manager != null) {
            ChineseInput registered = manager.getModule(ChineseInput.class);
            if (registered != null) {
                return registered;
            }
        }
        return instance;
    }

    /**
     * Returns whether the Chinese IME bridge should currently process input.
     *
     * @return {@code true} if the module exists and is enabled
     */
    public static boolean isBridgeEnabled() {
        ChineseInput module = getInstance();
        return module != null && module.isEnabled();
    }

    /**
     * Returns whether the pre-edit composition bar overlay should be rendered.
     *
     * @return {@code true} if composition bar rendering is enabled
     */
    public static boolean isShowCompositionBar() {
        ChineseInput module = getInstance();
        return module == null || module.showCompositionBar.isEnabled();
    }

    /**
     * Returns whether IME input is active in vanilla chat and text input screens.
     *
     * @return {@code true} if enabled for vanilla text screens
     */
    public static boolean isEnableInChat() {
        ChineseInput module = getInstance();
        return module == null || module.enableInChat.isEnabled();
    }

    /**
     * Returns whether IME input is active in the ClickGUI sidebar search field.
     *
     * @return {@code true} if enabled for ClickGUI search
     */
    public static boolean isEnableInClickGui() {
        ChineseInput module = getInstance();
        return module == null || module.enableInClickGui.isEnabled();
    }

    /**
     * Returns the pre-edit composition bar visibility setting.
     *
     * @return show composition bar setting
     */
    public BooleanSetting getShowCompositionBarSetting() {
        return this.showCompositionBar;
    }

    /**
     * Returns the vanilla chat/text screen IME toggle setting.
     *
     * @return enable in chat setting
     */
    public BooleanSetting getEnableInChatSetting() {
        return this.enableInChat;
    }

    /**
     * Returns the ClickGUI search bar IME toggle setting.
     *
     * @return enable in ClickGUI setting
     */
    public BooleanSetting getEnableInClickGuiSetting() {
        return this.enableInClickGui;
    }

    @Override
    protected void onEnable() {
        ImeInputBridge.ensureRegistered();
    }

    @Override
    protected void onDisable() {
        ImeInputBridge.onBridgeDisabled();
    }

    @Override
    public void resetToDefaults() {
        List<Setting<?>> allSettings = getSettings();
        for (int i = 0; i < allSettings.size(); i++) {
            allSettings.get(i).reset();
        }
        setEnabled(defaultEnabled);
    }
}
