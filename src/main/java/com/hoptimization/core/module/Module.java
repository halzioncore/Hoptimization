package com.hoptimization.core.module;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.setting.KeybindSetting;
import com.hoptimization.core.setting.Setting;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for all toggleable client feature modules.
 *
 * <p>Modules are disabled by default and register onto {@link MinecraftForge#EVENT_BUS} only while
 * enabled and only when they declare {@link SubscribeEvent} handler methods.</p>
 */
public abstract class Module {
    private static final String COMMON_KEYBIND_NAME_KEY = "setting.hoptimization.common.keybind.name";
    private static final String COMMON_KEYBIND_DESC_KEY = "setting.hoptimization.common.keybind.desc";

    private final String id;
    private final String nameKey;
    private final String descKey;
    private final Category category;
    private boolean enabled;
    private int keybind;
    private final KeybindSetting keybindSetting;
    private final List<Setting<?>> settings;
    private final List<Setting<?>> settingsView;
    private final boolean hasEventHandlers;
    private boolean registeredOnEventBus;
    private Runnable changeListener;

    /**
     * Creates a disabled module with no default keybind.
     *
     * @param id stable lowercase snake_case identifier used in config profiles
     * @param category module category
     */
    protected Module(String id, Category category) {
        this(id, category, Keyboard.KEY_NONE);
    }

    /**
     * Creates a disabled module with an optional default keybind.
     *
     * @param id stable lowercase snake_case identifier used in config profiles
     * @param category module category
     * @param defaultKeybind default LWJGL key code ({@link Keyboard#KEY_NONE} for unbound)
     */
    protected Module(String id, Category category, int defaultKeybind) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Module id must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("Module category must not be null");
        }
        this.id = id;
        this.nameKey = "module.hoptimization." + id + ".name";
        this.descKey = "module.hoptimization." + id + ".desc";
        this.category = category;
        this.enabled = false;
        this.keybind = KeybindSetting.sanitizeKeyCode(defaultKeybind);
        this.keybindSetting = new KeybindSetting(
            "keybind",
            COMMON_KEYBIND_NAME_KEY,
            COMMON_KEYBIND_DESC_KEY,
            this.keybind
        );
        this.settings = new ArrayList<Setting<?>>(4);
        this.settings.add(this.keybindSetting);
        this.settingsView = Collections.unmodifiableList(this.settings);
        this.hasEventHandlers = detectSubscribeEventHandlers(getClass());

        this.keybindSetting.addChangeListener(new Runnable() {
            @Override
            public void run() {
                keybind = keybindSetting.getKeyCode();
                notifyChanged();
            }
        });
    }

    /**
     * Returns the stable configuration key of this module.
     *
     * @return module id
     */
    public String getId() {
        return this.id;
    }

    /**
     * Returns the localization key for the module display name.
     *
     * @return name localization key
     */
    public String getNameKey() {
        return this.nameKey;
    }

    /**
     * Returns the localization key for the module one-line description.
     *
     * @return description localization key
     */
    public String getDescKey() {
        return this.descKey;
    }

    /**
     * Returns the localized module name.
     *
     * @return localized display name
     */
    public String getDisplayName() {
        return I18n.format(this.nameKey);
    }

    /**
     * Returns the localized one-line description.
     *
     * @return localized description
     */
    public String getDisplayDescription() {
        return I18n.format(this.descKey);
    }

    /**
     * Returns the category to which this module belongs.
     *
     * @return module category
     */
    public Category getCategory() {
        return this.category;
    }

    /**
     * Returns whether this module is currently enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Toggles the enabled state of this module.
     */
    public void toggle() {
        setEnabled(!this.enabled);
    }

    /**
     * Enables or disables this module, synchronizing Forge event bus registration and lifecycle hooks.
     *
     * @param enabled target enabled state
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        if (enabled) {
            this.enabled = true;
            registerEventBus();
            try {
                onEnable();
            } catch (RuntimeException e) {
                HoptimizationMod.LOGGER.error("Error in onEnable() for module {}", this.id, e);
                this.enabled = false;
                unregisterEventBus();
                return;
            }
        } else {
            this.enabled = false;
            unregisterEventBus();
            try {
                onDisable();
            } catch (RuntimeException e) {
                HoptimizationMod.LOGGER.error("Error in onDisable() for module {}", this.id, e);
            }
        }
        notifyChanged();
    }

    /**
     * Returns the bound LWJGL key code, or {@code 0} if unbound.
     *
     * @return keybind code
     */
    public int getKeybind() {
        return this.keybind;
    }

    /**
     * Updates the bound LWJGL key code and keeps the exposed {@link KeybindSetting} synchronized.
     *
     * @param keybind LWJGL key code ({@link Keyboard#KEY_NONE} to unbind)
     */
    public void setKeybind(int keybind) {
        this.keybindSetting.setValue(Integer.valueOf(keybind));
    }

    /**
     * Returns the trailing {@link KeybindSetting} exposed at the end of {@link #getSettings()}.
     *
     * @return module keybind setting
     */
    public KeybindSetting getKeybindSetting() {
        return this.keybindSetting;
    }

    /**
     * Returns an unmodifiable view of all module settings in display order, ending with the keybind setting.
     *
     * @return unmodifiable list of settings
     */
    public List<Setting<?>> getSettings() {
        return this.settingsView;
    }

    /**
     * Returns whether this module declares custom settings in addition to the trailing keybind row.
     *
     * @return true if at least one non-keybind setting exists
     */
    public boolean hasCustomSettings() {
        return this.settings.size() > 1;
    }

    /**
     * Finds a setting on this module by its persistence id.
     *
     * @param settingId setting identifier
     * @return matching setting, or null if not found
     */
    public Setting<?> getSettingById(String settingId) {
        if (settingId == null) {
            return null;
        }
        for (int i = 0; i < this.settings.size(); i++) {
            Setting<?> setting = this.settings.get(i);
            if (setting.getId().equals(settingId)) {
                return setting;
            }
        }
        return null;
    }

    /**
     * Resets enabled state, keybind, and all settings back to their defaults.
     */
    public void resetToDefaults() {
        setEnabled(false);
        for (int i = 0; i < this.settings.size(); i++) {
            this.settings.get(i).reset();
        }
    }

    /**
     * Registers a setting before the trailing keybind setting.
     *
     * @param setting setting instance to register
     * @param <S> concrete setting type
     * @return the registered setting for fluent assignment
     */
    protected <S extends Setting<?>> S addSetting(S setting) {
        if (setting == null) {
            throw new IllegalArgumentException("Setting must not be null");
        }
        if (setting == this.keybindSetting) {
            throw new IllegalArgumentException("KeybindSetting is managed automatically by Module");
        }
        for (int i = 0; i < this.settings.size(); i++) {
            if (this.settings.get(i).getId().equals(setting.getId())) {
                throw new IllegalArgumentException(
                    "Duplicate setting id '" + setting.getId() + "' in module '" + this.id + "'"
                );
            }
        }
        // Keep the built-in KeybindSetting pinned at the end of settings per 03 §3.
        this.settings.add(this.settings.size() - 1, setting);
        setting.addChangeListener(new Runnable() {
            @Override
            public void run() {
                notifyChanged();
            }
        });
        return setting;
    }

    /**
     * Called when the module transitions from disabled to enabled.
     */
    protected void onEnable() {
    }

    /**
     * Called when the module transitions from enabled to disabled.
     */
    protected void onDisable() {
    }

    void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void notifyChanged() {
        if (this.changeListener != null) {
            this.changeListener.run();
        }
    }

    private void registerEventBus() {
        // Avoid calling EventBus.register when a module has no @SubscribeEvent methods so
        // toggling placeholder or mixin-only modules does not trigger unnecessary reflection.
        if (!this.hasEventHandlers || this.registeredOnEventBus) {
            return;
        }
        try {
            MinecraftForge.EVENT_BUS.register(this);
            this.registeredOnEventBus = true;
        } catch (RuntimeException e) {
            HoptimizationMod.LOGGER.error("Failed to register module {} on Forge EVENT_BUS", this.id, e);
        }
    }

    private void unregisterEventBus() {
        if (!this.registeredOnEventBus) {
            return;
        }
        try {
            MinecraftForge.EVENT_BUS.unregister(this);
        } catch (RuntimeException e) {
            HoptimizationMod.LOGGER.error("Failed to unregister module {} from Forge EVENT_BUS", this.id, e);
        } finally {
            this.registeredOnEventBus = false;
        }
    }

    private static boolean detectSubscribeEventHandlers(Class<?> moduleClass) {
        Class<?> current = moduleClass;
        while (current != null && current != Object.class) {
            Method[] declared = current.getDeclaredMethods();
            for (int i = 0; i < declared.length; i++) {
                if (declared[i].isAnnotationPresent(SubscribeEvent.class)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
