package com.hoptimization;

import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.modules.visual.Theme;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.clickgui.ClickGuiScreen;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.io.File;

@Mod(
    modid = HoptimizationMod.MODID,
    name = HoptimizationMod.NAME,
    version = HoptimizationMod.VERSION,
    clientSideOnly = true,
    useMetadata = true
)
public class HoptimizationMod {
    public static final String MODID = "hoptimization";
    public static final String NAME = "Hoptimization";
    public static final String VERSION = "0.11.0";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    private static ModuleManager moduleManager;
    private static ConfigManager configManager;
    private static boolean shutdownHookRegistered = false;

    /**
     * Returns the initialized module registry.
     *
     * @return module manager instance, or null before pre-initialization
     */
    public static ModuleManager getModuleManager() {
        return moduleManager;
    }

    /**
     * Returns the initialized configuration manager.
     *
     * @return config manager instance, or null before pre-initialization
     */
    public static ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Returns whether Cheat Mode is currently enabled.
     *
     * @return true if Cheat Mode is active
     */
    public static boolean isCheatModeEnabled() {
        return moduleManager != null && moduleManager.isCheatModeEnabled();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Pre-initializing {} {}", NAME, VERSION);

        moduleManager = new ModuleManager();
        moduleManager.registerDefaultModules();

        File configRoot = new File(event.getModConfigurationDirectory(), MODID);
        configManager = new ConfigManager(configRoot, moduleManager);
        configManager.loadAll();

        initializeUiState(configManager.getUiConfig());

        moduleManager.setChangeListener(new Runnable() {
            @Override
            public void run() {
                if (configManager != null) {
                    configManager.saveActiveProfileDebounced();
                }
            }
        });

        registerShutdownHook();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        LanguageModule.applyConfiguredLanguageOnInit();
        LOGGER.info("{} initialized ({} modules).", NAME, Integer.valueOf(moduleManager.getModules().size()));
    }

    /**
     * Dispatches in-game key presses to bound modules while ignoring GUI typing and key repeats.
     *
     * @param event Forge key input event
     */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (moduleManager == null) {
            return;
        }
        if (!Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) {
            return;
        }
        int keyCode = Keyboard.getEventKey();
        if (keyCode <= Keyboard.KEY_NONE) {
            return;
        }
        if (configManager != null && keyCode == configManager.getUiConfig().getClickGuiKeybind()) {
            mc.displayGuiScreen(new ClickGuiScreen());
            return;
        }
        moduleManager.handleKeyPress(keyCode);
    }

    private static void initializeUiState(UiConfig uiConfig) {
        if (configManager == null || uiConfig == null) {
            return;
        }
        ThemeManager themeManager = ThemeManager.getInstance();
        themeManager.setThemesDirectory(configManager.getThemesDir().toFile());
        Theme.syncOnStartup(uiConfig);
        themeManager.setReduceMotion(uiConfig.isReduceMotion());
        Animation.setReduceMotion(uiConfig.isReduceMotion());

        UiScale.setUserMultiplier(uiConfig.getUiScaleMultiplier());
        FontManager.setUiScale(UiScale.getScale());
    }

    private static void registerShutdownHook() {
        if (shutdownHookRegistered || configManager == null) {
            return;
        }
        final ConfigManager managerToFlush = configManager;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    managerToFlush.shutdown();
                }
            }, "Hoptimization-Config-Shutdown"));
            shutdownHookRegistered = true;
        } catch (IllegalStateException e) {
            LOGGER.warn("Failed to register config shutdown hook", e);
        }
    }
}
