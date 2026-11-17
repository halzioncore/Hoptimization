package com.hoptimization.core.module;

import com.hoptimization.modules.combat.AutoClicker;
import com.hoptimization.modules.combat.ComboCounter;
import com.hoptimization.modules.combat.HitColor;
import com.hoptimization.modules.combat.HitDelayFix;
import com.hoptimization.modules.combat.OldAnimations;
import com.hoptimization.modules.combat.WTap;
import com.hoptimization.modules.misc.Fullbright;
import com.hoptimization.modules.movement.NoJumpDelay;
import com.hoptimization.modules.movement.ToggleSprint;
import com.hoptimization.modules.performance.MouseDelayFix;
import com.hoptimization.modules.performance.ParticleLimit;
import com.hoptimization.modules.ui.CheatMode;
import com.hoptimization.modules.ui.ClickGuiModule;
import com.hoptimization.modules.ui.Coordinates;
import com.hoptimization.modules.ui.CpsDisplay;
import com.hoptimization.modules.ui.FPSDisplay;
import com.hoptimization.modules.ui.Keystrokes;
import com.hoptimization.modules.ui.TargetHud;
import com.hoptimization.modules.visual.BlockOutline;
import com.hoptimization.modules.visual.HurtCam;
import com.hoptimization.modules.visual.LowFire;
import com.hoptimization.modules.visual.TimeChanger;
import com.hoptimization.modules.visual.Zoom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry and lifecycle coordinator for all client modules.
 */
public class ModuleManager {
    private final List<Module> modules = new ArrayList<Module>();
    private final List<Module> modulesView = Collections.unmodifiableList(this.modules);
    private final Map<String, Module> byId = new LinkedHashMap<String, Module>();
    private final Map<Class<? extends Module>, Module> byClass = new HashMap<Class<? extends Module>, Module>();
    private final Map<Category, List<Module>> byCategory = new EnumMap<Category, List<Module>>(Category.class);
    private final Map<Category, List<Module>> byCategoryView = new EnumMap<Category, List<Module>>(Category.class);
    private Runnable changeListener;

    /**
     * Creates an empty module manager with pre-initialized category buckets.
     */
    public ModuleManager() {
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            Category category = categories[i];
            List<Module> list = new ArrayList<Module>();
            this.byCategory.put(category, list);
            this.byCategoryView.put(category, Collections.unmodifiableList(list));
        }
    }

    /**
     * Registers all built-in placeholder and feature modules.
     */
    public void registerDefaultModules() {
        register(new AutoClicker());
        register(new HitDelayFix());
        register(new WTap());
        register(new OldAnimations());
        register(new HitColor());
        register(new ComboCounter());

        register(new ToggleSprint());
        register(new NoJumpDelay());

        register(new LowFire());
        register(new BlockOutline());
        register(new HurtCam());
        register(new Zoom());
        register(new TimeChanger());

        register(new ClickGuiModule());
        register(new CheatMode());
        register(new CpsDisplay());
        register(new TargetHud());
        register(new FPSDisplay());
        register(new Coordinates());
        register(new Keystrokes());

        register(new ParticleLimit());
        register(new MouseDelayFix());

        register(new Fullbright());
    }

    /**
     * Registers a module instance, rejecting duplicate ids or duplicate module classes.
     *
     * @param module module instance to register
     * @param <T> concrete module type
     * @return registered module instance
     */
    public <T extends Module> T register(T module) {
        if (module == null) {
            throw new IllegalArgumentException("Module must not be null");
        }
        String key = module.getId().toLowerCase(Locale.ROOT);
        if (this.byId.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate module id: " + module.getId());
        }
        if (this.byClass.containsKey(module.getClass())) {
            throw new IllegalArgumentException("Duplicate module class: " + module.getClass().getName());
        }
        this.modules.add(module);
        this.byId.put(key, module);
        this.byClass.put(module.getClass(), module);
        this.byCategory.get(module.getCategory()).add(module);
        module.setOwnerManager(this);
        module.setChangeListener(new Runnable() {
            @Override
            public void run() {
                notifyModulesChanged();
            }
        });
        return module;
    }

    /**
     * Returns an unmodifiable view of all registered modules in registration order.
     *
     * @return all registered modules
     */
    public List<Module> getModules() {
        return this.modulesView;
    }

    /**
     * Returns an unmodifiable view of modules belonging to the given category without allocation.
     *
     * @param category target category
     * @return unmodifiable list of modules in that category
     */
    public List<Module> getModules(Category category) {
        if (category == null) {
            return Collections.emptyList();
        }
        return this.byCategoryView.get(category);
    }

    /**
     * Looks up a module by its stable string identifier (case-insensitive).
     *
     * @param id module id
     * @return matching module, or null if not found
     */
    public Module getModuleById(String id) {
        if (id == null) {
            return null;
        }
        return this.byId.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Looks up a registered module by its concrete class.
     *
     * @param moduleClass concrete module class
     * @param <T> module subtype
     * @return module instance, or null if not registered
     */
    public <T extends Module> T getModule(Class<T> moduleClass) {
        if (moduleClass == null) {
            return null;
        }
        return moduleClass.cast(this.byClass.get(moduleClass));
    }

    /**
     * Counts enabled modules in the given category without hot-path allocations.
     *
     * @param category target category
     * @return number of enabled modules in that category
     */
    public int getEnabledCount(Category category) {
        if (category == null) {
            return 0;
        }
        List<Module> list = this.byCategory.get(category);
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isEnabled()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts all currently enabled modules across every category without hot-path allocations.
     *
     * @return total enabled module count
     */
    public int getTotalEnabledCount() {
        int count = 0;
        for (int i = 0; i < this.modules.size(); i++) {
            if (this.modules.get(i).isEnabled()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Toggles available, toggleable modules bound to the given LWJGL key code.
     *
     * @param keyCode pressed LWJGL key code
     * @return true if at least one module was toggled
     */
    public boolean handleKeyPress(int keyCode) {
        if (keyCode <= 0) {
            return false;
        }
        boolean toggled = false;
        for (int i = 0; i < this.modules.size(); i++) {
            Module module = this.modules.get(i);
            if (module.getKeybind() == keyCode) {
                if (!module.isToggleable() || !module.isAvailable()) {
                    continue;
                }
                module.toggle();
                toggled = true;
            }
        }
        return toggled;
    }

    /**
     * Returns whether Cheat Mode is currently enabled in this manager.
     *
     * @return true if {@link CheatMode} is registered and enabled
     */
    public boolean isCheatModeEnabled() {
        CheatMode cheatMode = getModule(CheatMode.class);
        if (cheatMode != null) {
            return cheatMode.isEnabled();
        }
        Module fallback = getModuleById(CheatMode.ID);
        return fallback != null && fallback.isEnabled();
    }

    /**
     * Disables all enabled modules that require Cheat Mode.
     */
    public void disableAllCheatModules() {
        for (int i = 0; i < this.modules.size(); i++) {
            Module module = this.modules.get(i);
            if (module.isRequiresCheatMode() && module.isEnabled()) {
                module.setEnabled(false);
            }
        }
    }

    /**
     * Disables all currently enabled modules.
     */
    public void disableAll() {
        for (int i = 0; i < this.modules.size(); i++) {
            this.modules.get(i).setEnabled(false);
        }
    }

    /**
     * Resets all registered modules (enabled state, keybind, and settings) to defaults.
     */
    public void resetAllToDefaults() {
        for (int i = 0; i < this.modules.size(); i++) {
            this.modules.get(i).resetToDefaults();
        }
    }

    /**
     * Registers a listener notified whenever any module state, keybind, or setting changes.
     *
     * @param changeListener callback invoked on changes, or null to clear
     */
    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void notifyModulesChanged() {
        if (this.changeListener != null) {
            this.changeListener.run();
        }
    }
}
