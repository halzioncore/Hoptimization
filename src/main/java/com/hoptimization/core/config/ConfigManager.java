package com.hoptimization.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.KeybindSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.core.setting.Setting;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Manages fault-tolerant JSON persistence for {@link UiConfig} and module profiles.
 *
 * <p>JSON snapshots are always constructed on the caller (main) thread while disk I/O runs
 * sequentially on a single daemon background thread using atomic {@code *.tmp} file replacement.</p>
 */
public class ConfigManager {
    public static final int PROFILE_SCHEMA = 1;
    public static final long DEBOUNCE_DELAY_MS = 500L;
    private static final int MAX_PROFILE_NAME_LENGTH = 64;
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final Path configDir;
    private final Path profilesDir;
    private final Path themesDir;
    private final Path uiConfigFile;
    private final ModuleManager moduleManager;
    private final UiConfig uiConfig = new UiConfig();
    private final JsonParser jsonParser = new JsonParser();

    private final ScheduledExecutorService writerExecutor;
    private final Object ioLock = new Object();

    private ScheduledFuture<?> pendingProfileTask;
    private ScheduledFuture<?> pendingUiTask;
    private String pendingProfileName;
    private String pendingProfileSnapshot;
    private String pendingUiSnapshot;
    private long profileSequence = 0L;
    private long uiSequence = 0L;
    private boolean suppressAutoSave = false;
    private volatile boolean isShutdown = false;

    /**
     * Creates a configuration manager rooted at the specified directory.
     *
     * @param configDir mod config directory (typically {@code .minecraft/config/hoptimization})
     * @param moduleManager module registry whose states and settings are persisted
     */
    public ConfigManager(File configDir, ModuleManager moduleManager) {
        this(configDir != null ? configDir.toPath() : null, moduleManager);
    }

    /**
     * Creates a configuration manager rooted at the specified path.
     *
     * @param configDir mod config path (typically {@code .minecraft/config/hoptimization})
     * @param moduleManager module registry whose states and settings are persisted
     */
    public ConfigManager(Path configDir, ModuleManager moduleManager) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir must not be null");
        }
        if (moduleManager == null) {
            throw new IllegalArgumentException("moduleManager must not be null");
        }
        this.configDir = configDir;
        this.profilesDir = configDir.resolve("profiles");
        this.themesDir = configDir.resolve("themes");
        this.uiConfigFile = configDir.resolve("ui.json");
        this.moduleManager = moduleManager;
        this.writerExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "Hoptimization-Config-Writer");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Returns the root configuration directory path.
     *
     * @return config directory path
     */
    public Path getConfigDir() {
        return this.configDir;
    }

    /**
     * Returns the profiles directory path.
     *
     * @return profiles directory path
     */
    public Path getProfilesDir() {
        return this.profilesDir;
    }

    /**
     * Returns the custom themes directory path.
     *
     * @return themes directory path
     */
    public Path getThemesDir() {
        return this.themesDir;
    }

    /**
     * Returns the {@code ui.json} file path.
     *
     * @return ui config file path
     */
    public Path getUiConfigFile() {
        return this.uiConfigFile;
    }

    /**
     * Returns the live {@link UiConfig} instance.
     *
     * @return UI configuration model
     */
    public UiConfig getUiConfig() {
        return this.uiConfig;
    }

    /**
     * Returns the currently active profile name.
     *
     * @return active profile name
     */
    public String getActiveProfileName() {
        return this.uiConfig.getActiveProfile();
    }

    /**
     * Loads {@code ui.json} and the active profile from disk, creating defaults when missing.
     */
    public void loadAll() {
        ensureDirectories();
        boolean uiExisted = Files.exists(this.uiConfigFile);
        loadUiConfig();
        if (!uiExisted) {
            saveUiConfigAsync();
        }

        String requestedProfile = this.uiConfig.getActiveProfile();
        if (!isValidProfileName(requestedProfile)) {
            HoptimizationMod.LOGGER.warn(
                "Invalid activeProfile '{}' in ui.json, falling back to '{}'",
                requestedProfile,
                UiConfig.DEFAULT_PROFILE
            );
            requestedProfile = UiConfig.DEFAULT_PROFILE;
            this.uiConfig.setActiveProfile(requestedProfile);
            saveUiConfigAsync();
        }

        Path profilePath = getProfilePath(requestedProfile);
        if (!Files.exists(profilePath)) {
            if (!UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(requestedProfile)) {
                HoptimizationMod.LOGGER.warn(
                    "Active profile '{}' not found at {}, falling back to '{}'",
                    requestedProfile,
                    profilePath,
                    UiConfig.DEFAULT_PROFILE
                );
                this.uiConfig.setActiveProfile(UiConfig.DEFAULT_PROFILE);
                saveUiConfigAsync();
                profilePath = getProfilePath(UiConfig.DEFAULT_PROFILE);
            }
            if (!Files.exists(profilePath)) {
                resetModulesToDefaultsQuietly();
                saveActiveProfileAsync();
                return;
            }
        }

        loadProfileIntoModules(this.uiConfig.getActiveProfile());
    }

    /**
     * Lists available profile names sorted with {@code "default"} first.
     *
     * @return sorted list of profile names (without {@code .json} extension)
     */
    public List<String> listProfiles() {
        List<String> names = new ArrayList<String>();
        if (!Files.isDirectory(this.profilesDir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.profilesDir, "*.json")) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String fileName = entry.getFileName().toString();
                if (fileName.endsWith(".json") && fileName.length() > 5) {
                    names.add(fileName.substring(0, fileName.length() - 5));
                }
            }
        } catch (IOException e) {
            HoptimizationMod.LOGGER.error("Failed to list profiles in {}", this.profilesDir, e);
        }

        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                boolean aDefault = UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(a);
                boolean bDefault = UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(b);
                if (aDefault && !bDefault) {
                    return -1;
                }
                if (!aDefault && bDefault) {
                    return 1;
                }
                return a.compareToIgnoreCase(b);
            }
        });
        return names;
    }

    /**
     * Switches the active profile to an existing profile and loads its module states.
     *
     * @param name target profile name
     * @return true if switched and loaded, false if invalid or missing
     */
    public boolean switchProfile(String name) {
        String normalized = normalizeProfileName(name);
        if (normalized == null) {
            HoptimizationMod.LOGGER.warn("Cannot switch to invalid profile name '{}'", name);
            return false;
        }
        String existingName = findExistingProfileName(normalized);
        if (existingName == null) {
            HoptimizationMod.LOGGER.warn("Profile '{}' does not exist", normalized);
            return false;
        }
        if (existingName.equals(this.uiConfig.getActiveProfile())) {
            return true;
        }
        flushPendingProfileSave();
        this.uiConfig.setActiveProfile(existingName);
        loadProfileIntoModules(existingName);
        saveUiConfigAsync();
        return true;
    }

    /**
     * Creates a new profile initialized with default module values and switches to it.
     *
     * @param name new profile name
     * @return true if created, false if invalid or already exists
     */
    public boolean createProfile(String name) {
        String normalized = normalizeProfileName(name);
        if (normalized == null) {
            HoptimizationMod.LOGGER.warn("Cannot create profile with invalid name '{}'", name);
            return false;
        }
        if (findExistingProfileName(normalized) != null) {
            HoptimizationMod.LOGGER.warn("Profile '{}' already exists", normalized);
            return false;
        }
        flushPendingProfileSave();
        resetModulesToDefaultsQuietly();
        this.uiConfig.setActiveProfile(normalized);
        String snapshot = serializeActiveProfile();
        writeSyncThroughExecutor(getProfilePath(normalized), snapshot);
        saveUiConfigAsync();
        return true;
    }

    /**
     * Duplicates the currently active profile state into a new profile and switches to it.
     *
     * @param newName destination profile name
     * @return true if duplicated, false if invalid or already exists
     */
    public boolean duplicateCurrentProfile(String newName) {
        String normalized = normalizeProfileName(newName);
        if (normalized == null) {
            HoptimizationMod.LOGGER.warn("Cannot duplicate profile to invalid name '{}'", newName);
            return false;
        }
        if (findExistingProfileName(normalized) != null) {
            HoptimizationMod.LOGGER.warn("Cannot duplicate: profile '{}' already exists", normalized);
            return false;
        }
        flushPendingProfileSave();
        this.uiConfig.setActiveProfile(normalized);
        String snapshot = serializeActiveProfile();
        writeSyncThroughExecutor(getProfilePath(normalized), snapshot);
        saveUiConfigAsync();
        return true;
    }

    /**
     * Renames an existing non-default profile.
     *
     * @param oldName current profile name
     * @param newName desired new profile name
     * @return true if renamed, false if invalid, protected, missing, or target exists
     */
    public boolean renameProfile(String oldName, String newName) {
        String normOld = normalizeProfileName(oldName);
        String normNew = normalizeProfileName(newName);
        if (normOld == null || normNew == null) {
            HoptimizationMod.LOGGER.warn("Invalid profile rename '{}' -> '{}'", oldName, newName);
            return false;
        }
        String existingOld = findExistingProfileName(normOld);
        if (existingOld == null) {
            HoptimizationMod.LOGGER.warn("Cannot rename missing profile '{}'", normOld);
            return false;
        }
        if (UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(existingOld)) {
            HoptimizationMod.LOGGER.warn("Cannot rename protected default profile");
            return false;
        }
        if (existingOld.equalsIgnoreCase(normNew)) {
            return false;
        }
        if (findExistingProfileName(normNew) != null) {
            HoptimizationMod.LOGGER.warn("Cannot rename: target profile '{}' already exists", normNew);
            return false;
        }

        flushPendingProfileSave();
        final Path source = getProfilePath(existingOld);
        final Path target = getProfilePath(normNew);
        final boolean[] success = new boolean[]{false};
        awaitExecutorTask(new Runnable() {
            @Override
            public void run() {
                try {
                    moveFileSafely(source, target);
                    success[0] = true;
                } catch (IOException e) {
                    HoptimizationMod.LOGGER.error("Failed to rename profile {} to {}", source, target, e);
                }
            }
        });

        if (success[0] && existingOld.equals(this.uiConfig.getActiveProfile())) {
            this.uiConfig.setActiveProfile(normNew);
            saveUiConfigAsync();
        }
        return success[0];
    }

    /**
     * Deletes a non-default profile, switching back to {@code "default"} if the active profile was deleted.
     *
     * @param name profile name to delete
     * @return true if deleted, false if protected, invalid, or missing
     */
    public boolean deleteProfile(String name) {
        String normalized = normalizeProfileName(name);
        if (normalized == null) {
            HoptimizationMod.LOGGER.warn("Cannot delete invalid profile name '{}'", name);
            return false;
        }
        String existing = findExistingProfileName(normalized);
        if (existing == null) {
            HoptimizationMod.LOGGER.warn("Cannot delete missing profile '{}'", normalized);
            return false;
        }
        if (UiConfig.DEFAULT_PROFILE.equalsIgnoreCase(existing)) {
            HoptimizationMod.LOGGER.warn("Cannot delete protected default profile");
            return false;
        }

        boolean deletingActive = existing.equals(this.uiConfig.getActiveProfile());
        if (deletingActive) {
            cancelPendingProfileSave();
        } else {
            flushPendingProfileSave();
        }

        final Path target = getProfilePath(existing);
        final boolean[] deleted = new boolean[]{false};
        awaitExecutorTask(new Runnable() {
            @Override
            public void run() {
                try {
                    deleted[0] = Files.deleteIfExists(target);
                } catch (IOException e) {
                    HoptimizationMod.LOGGER.error("Failed to delete profile {}", target, e);
                }
            }
        });

        if (deleted[0] && deletingActive) {
            this.uiConfig.setActiveProfile(UiConfig.DEFAULT_PROFILE);
            Path defaultPath = getProfilePath(UiConfig.DEFAULT_PROFILE);
            if (!Files.exists(defaultPath)) {
                resetModulesToDefaultsQuietly();
                saveActiveProfileAsync();
            } else {
                loadProfileIntoModules(UiConfig.DEFAULT_PROFILE);
            }
            saveUiConfigAsync();
        }
        return deleted[0];
    }

    /**
     * Checks whether a candidate profile name is safe and valid on Windows/Unix filesystems.
     *
     * @param name candidate profile name
     * @return true if valid
     */
    public static boolean isValidProfileName(String name) {
        return normalizeProfileName(name) != null;
    }

    /**
     * Schedules an active profile save after {@link #DEBOUNCE_DELAY_MS} ms using a main-thread JSON snapshot.
     */
    public void saveActiveProfileDebounced() {
        if (this.suppressAutoSave || this.isShutdown) {
            return;
        }
        final String profileName = this.uiConfig.getActiveProfile();
        final String snapshot = serializeActiveProfile();

        synchronized (this.ioLock) {
            if (this.isShutdown) {
                return;
            }
            if (this.pendingProfileTask != null) {
                this.pendingProfileTask.cancel(false);
            }
            final long seq = ++this.profileSequence;
            this.pendingProfileName = profileName;
            this.pendingProfileSnapshot = snapshot;
            this.pendingProfileTask = this.writerExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    String dataToWrite = null;
                    String targetName = null;
                    synchronized (ioLock) {
                        if (profileSequence != seq || pendingProfileSnapshot == null) {
                            return;
                        }
                        dataToWrite = pendingProfileSnapshot;
                        targetName = pendingProfileName;
                        pendingProfileSnapshot = null;
                        pendingProfileName = null;
                        pendingProfileTask = null;
                    }
                    if (targetName != null) {
                        writeAtomically(getProfilePath(targetName), dataToWrite);
                    }
                }
            }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Schedules a {@code ui.json} save after {@link #DEBOUNCE_DELAY_MS} ms using a main-thread JSON snapshot.
     */
    public void saveUiConfigDebounced() {
        if (this.isShutdown) {
            return;
        }
        final String snapshot = serializeUiConfig();

        synchronized (this.ioLock) {
            if (this.isShutdown) {
                return;
            }
            if (this.pendingUiTask != null) {
                this.pendingUiTask.cancel(false);
            }
            final long seq = ++this.uiSequence;
            this.pendingUiSnapshot = snapshot;
            this.pendingUiTask = this.writerExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    String dataToWrite = null;
                    synchronized (ioLock) {
                        if (uiSequence != seq || pendingUiSnapshot == null) {
                            return;
                        }
                        dataToWrite = pendingUiSnapshot;
                        pendingUiSnapshot = null;
                        pendingUiTask = null;
                    }
                    writeAtomically(uiConfigFile, dataToWrite);
                }
            }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Snapshots the active profile on the caller thread and writes it asynchronously without debounce.
     */
    public void saveActiveProfileAsync() {
        if (this.isShutdown) {
            return;
        }
        final String profileName = this.uiConfig.getActiveProfile();
        final String snapshot = serializeActiveProfile();

        synchronized (this.ioLock) {
            if (this.isShutdown) {
                return;
            }
            if (this.pendingProfileTask != null) {
                this.pendingProfileTask.cancel(false);
                this.pendingProfileTask = null;
            }
            this.profileSequence++;
            this.pendingProfileName = null;
            this.pendingProfileSnapshot = null;
            this.writerExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    writeAtomically(getProfilePath(profileName), snapshot);
                }
            });
        }
    }

    /**
     * Snapshots {@link UiConfig} on the caller thread and writes {@code ui.json} asynchronously without debounce.
     */
    public void saveUiConfigAsync() {
        if (this.isShutdown) {
            return;
        }
        final String snapshot = serializeUiConfig();

        synchronized (this.ioLock) {
            if (this.isShutdown) {
                return;
            }
            if (this.pendingUiTask != null) {
                this.pendingUiTask.cancel(false);
                this.pendingUiTask = null;
            }
            this.uiSequence++;
            this.pendingUiSnapshot = null;
            this.writerExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    writeAtomically(uiConfigFile, snapshot);
                }
            });
        }
    }

    /**
     * Snapshots both {@code ui.json} and the active profile immediately and saves them asynchronously.
     */
    public void saveAllAsync() {
        saveUiConfigAsync();
        saveActiveProfileAsync();
    }

    /**
     * Flushes any pending debounced writes and waits until queued background writes finish.
     */
    public void flush() {
        if (this.isShutdown) {
            return;
        }
        flushPendingUiSave();
        flushPendingProfileSave();
        awaitExecutorDrain();
    }

    /**
     * Flushes all pending configuration writes and shuts down the background writer executor cleanly.
     */
    public void shutdown() {
        if (this.isShutdown) {
            return;
        }
        flushPendingUiSave();
        flushPendingProfileSave();
        this.isShutdown = true;
        this.writerExecutor.shutdown();
        try {
            if (!this.writerExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                HoptimizationMod.LOGGER.warn("Config writer executor did not terminate within 5 seconds");
                this.writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HoptimizationMod.LOGGER.warn("Interrupted while waiting for config writer shutdown", e);
        }
    }

    private void loadUiConfig() {
        this.uiConfig.resetToDefaults();
        if (!Files.exists(this.uiConfigFile)) {
            return;
        }
        JsonObject root = readJsonObject(this.uiConfigFile, "ui.json");
        if (root == null) {
            return;
        }

        Integer schema = readInt(root, "schema", "ui.json");
        if (schema != null) {
            if (schema.intValue() > UiConfig.CURRENT_SCHEMA) {
                HoptimizationMod.LOGGER.warn(
                    "ui.json schema {} is newer than supported schema {}, attempting best-effort load",
                    schema,
                    Integer.valueOf(UiConfig.CURRENT_SCHEMA)
                );
            }
            this.uiConfig.setSchema(schema.intValue());
        }

        String activeProfile = readString(root, "activeProfile", "ui.json");
        if (activeProfile != null) {
            this.uiConfig.setActiveProfile(activeProfile);
        }

        String themeId = readString(root, "themeId", "ui.json");
        if (themeId != null) {
            this.uiConfig.setThemeId(themeId);
        }

        String accentOverride = readString(root, "accentOverride", "ui.json");
        if (accentOverride != null) {
            if (!accentOverride.trim().isEmpty() && ColorSetting.parseHexColor(accentOverride, true) == null) {
                HoptimizationMod.LOGGER.warn("Invalid accentOverride '{}' in ui.json, using default", accentOverride);
            } else {
                this.uiConfig.setAccentOverride(accentOverride);
            }
        }

        Double uiScale = readNumber(root, "uiScaleMultiplier", "ui.json");
        if (uiScale != null) {
            this.uiConfig.setUiScaleMultiplier(uiScale.floatValue());
        }

        Boolean reduceMotion = readBoolean(root, "reduceMotion", "ui.json");
        if (reduceMotion != null) {
            this.uiConfig.setReduceMotion(reduceMotion.booleanValue());
        }

        Integer clickGuiKeybind = readInt(root, "clickGuiKeybind", "ui.json");
        if (clickGuiKeybind != null) {
            this.uiConfig.setClickGuiKeybind(clickGuiKeybind.intValue());
        }

        String lastCategory = readString(root, "lastCategory", "ui.json");
        if (lastCategory != null) {
            if (Category.fromId(lastCategory) == null) {
                HoptimizationMod.LOGGER.warn("Unknown lastCategory '{}' in ui.json, keeping default", lastCategory);
            } else {
                this.uiConfig.setLastCategory(lastCategory);
            }
        }

        Boolean posSet = readBoolean(root, "windowPositionSet", "ui.json");
        if (Boolean.TRUE.equals(posSet)) {
            Double winX = readNumber(root, "windowX", "ui.json");
            Double winY = readNumber(root, "windowY", "ui.json");
            if (winX != null && winY != null) {
                this.uiConfig.setWindowPosition(winX.floatValue(), winY.floatValue());
            } else {
                HoptimizationMod.LOGGER.warn("Incomplete window position in ui.json, centering window");
            }
        }

        if (root.has("hudLayouts")) {
            JsonElement hudElem = root.get("hudLayouts");
            if (!hudElem.isJsonObject()) {
                HoptimizationMod.LOGGER.warn("Expected object for 'hudLayouts' in ui.json, ignoring");
            } else {
                JsonObject layoutsObj = hudElem.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : layoutsObj.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        HoptimizationMod.LOGGER.warn(
                            "Invalid hudLayout entry '{}' in ui.json, ignoring",
                            entry.getKey()
                        );
                        continue;
                    }
                    JsonObject layoutObj = entry.getValue().getAsJsonObject();
                    Double x = readNumber(layoutObj, "x", "ui.json");
                    Double y = readNumber(layoutObj, "y", "ui.json");
                    Double scale = readNumber(layoutObj, "scale", "ui.json");
                    if (x != null && y != null) {
                        float resolvedScale = scale != null ? scale.floatValue() : 1.0f;
                        this.uiConfig.setHudLayout(
                            entry.getKey(),
                            new UiConfig.HudLayout(x.floatValue(), y.floatValue(), resolvedScale)
                        );
                    }
                }
            }
        }
    }

    private void loadProfileIntoModules(String profileName) {
        resetModulesToDefaultsQuietly();

        Path profilePath = getProfilePath(profileName);
        if (!Files.exists(profilePath)) {
            return;
        }
        String context = "profile '" + profileName + "'";
        JsonObject root = readJsonObject(profilePath, context);
        if (root == null) {
            return;
        }

        Integer schema = readInt(root, "schema", context);
        if (schema != null && schema.intValue() > PROFILE_SCHEMA) {
            HoptimizationMod.LOGGER.warn(
                "Profile '{}' schema {} is newer than supported schema {}, attempting best-effort load",
                profileName,
                schema,
                Integer.valueOf(PROFILE_SCHEMA)
            );
        }

        if (!root.has("modules")) {
            return;
        }
        JsonElement modulesElem = root.get("modules");
        if (!modulesElem.isJsonObject()) {
            HoptimizationMod.LOGGER.warn("Expected object for 'modules' in {}, using defaults", context);
            return;
        }

        this.suppressAutoSave = true;
        try {
            JsonObject modulesObj = modulesElem.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : modulesObj.entrySet()) {
                Module module = this.moduleManager.getModuleById(entry.getKey());
                if (module == null) {
                    // Ignore unknown module keys per 01 §7 so stale config entries never fail.
                    continue;
                }
                if (!entry.getValue().isJsonObject()) {
                    HoptimizationMod.LOGGER.warn(
                        "Expected object for module '{}' in {}, keeping defaults",
                        module.getId(),
                        context
                    );
                    continue;
                }
                applyModuleJson(module, entry.getValue().getAsJsonObject(), context);
            }
        } finally {
            this.suppressAutoSave = false;
        }
    }

    private void applyModuleJson(Module module, JsonObject modObj, String context) {
        boolean hasExplicitModuleKeybind = false;
        if (modObj.has("keybind")) {
            Integer keybind = readInt(modObj, "keybind", context + " (" + module.getId() + ")");
            if (keybind != null) {
                module.setKeybind(keybind.intValue());
                hasExplicitModuleKeybind = true;
            }
        }

        if (modObj.has("settings")) {
            JsonElement settingsElem = modObj.get("settings");
            if (!settingsElem.isJsonObject()) {
                HoptimizationMod.LOGGER.warn(
                    "Expected object for settings of module '{}' in {}, keeping default settings",
                    module.getId(),
                    context
                );
            } else {
                JsonObject settingsObj = settingsElem.getAsJsonObject();
                for (Map.Entry<String, JsonElement> settingEntry : settingsObj.entrySet()) {
                    Setting<?> setting = module.getSettingById(settingEntry.getKey());
                    if (setting == null) {
                        continue;
                    }
                    if (setting == module.getKeybindSetting() && hasExplicitModuleKeybind) {
                        continue;
                    }
                    applySettingJson(module, setting, settingEntry.getValue(), context);
                }
            }
        }

        // Apply enabled state after settings and keybind so onEnable() sees loaded configuration values.
        if (modObj.has("enabled")) {
            Boolean enabled = readBoolean(modObj, "enabled", context + " (" + module.getId() + ")");
            if (enabled != null) {
                module.setEnabled(enabled.booleanValue());
            }
        }
    }

    private void applySettingJson(Module module, Setting<?> setting, JsonElement rawElement, String context) {
        if (rawElement == null || !rawElement.isJsonPrimitive()) {
            HoptimizationMod.LOGGER.warn(
                "Invalid value for setting '{}.{}' in {}, keeping default",
                module.getId(),
                setting.getId(),
                context
            );
            return;
        }
        JsonPrimitive prim = rawElement.getAsJsonPrimitive();
        if (setting instanceof BooleanSetting) {
            if (!prim.isBoolean()) {
                warnTypeMismatch(module, setting, "boolean", context);
                return;
            }
            ((BooleanSetting) setting).setValue(Boolean.valueOf(prim.getAsBoolean()));
        } else if (setting instanceof NumberSetting) {
            if (!prim.isNumber()) {
                warnTypeMismatch(module, setting, "number", context);
                return;
            }
            double value = prim.getAsDouble();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                warnTypeMismatch(module, setting, "finite number", context);
                return;
            }
            ((NumberSetting) setting).setValue(Double.valueOf(value));
        } else if (setting instanceof ModeSetting) {
            if (!prim.isString()) {
                warnTypeMismatch(module, setting, "string", context);
                return;
            }
            ((ModeSetting) setting).setValue(prim.getAsString());
        } else if (setting instanceof ColorSetting) {
            ColorSetting colorSetting = (ColorSetting) setting;
            if (prim.isString()) {
                if (!colorSetting.setFromHex(prim.getAsString())) {
                    HoptimizationMod.LOGGER.warn(
                        "Invalid hex color '{}' for '{}.{}' in {}, keeping default",
                        prim.getAsString(),
                        module.getId(),
                        setting.getId(),
                        context
                    );
                }
            } else if (prim.isNumber()) {
                colorSetting.setValue(Integer.valueOf(prim.getAsInt()));
            } else {
                warnTypeMismatch(module, setting, "hex string or ARGB integer", context);
            }
        } else if (setting instanceof KeybindSetting) {
            if (!prim.isNumber()) {
                warnTypeMismatch(module, setting, "key code integer", context);
                return;
            }
            ((KeybindSetting) setting).setValue(Integer.valueOf(prim.getAsInt()));
        }
    }

    private void warnTypeMismatch(Module module, Setting<?> setting, String expectedType, String context) {
        HoptimizationMod.LOGGER.warn(
            "Expected {} for setting '{}.{}' in {}, keeping default",
            expectedType,
            module.getId(),
            setting.getId(),
            context
        );
    }

    private void resetModulesToDefaultsQuietly() {
        this.suppressAutoSave = true;
        try {
            this.moduleManager.resetAllToDefaults();
        } finally {
            this.suppressAutoSave = false;
        }
    }

    private String serializeActiveProfile() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", Integer.valueOf(PROFILE_SCHEMA));

        JsonObject modulesObj = new JsonObject();
        List<Module> modules = this.moduleManager.getModules();
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            JsonObject modObj = new JsonObject();
            modObj.addProperty("enabled", Boolean.valueOf(module.isEnabled()));
            modObj.addProperty("keybind", Integer.valueOf(module.getKeybind()));

            JsonObject settingsObj = new JsonObject();
            List<Setting<?>> settings = module.getSettings();
            for (int j = 0; j < settings.size(); j++) {
                Setting<?> setting = settings.get(j);
                if (setting == module.getKeybindSetting()) {
                    // Already persisted at module top-level as "keybind".
                    continue;
                }
                writeSettingProperty(settingsObj, setting);
            }
            modObj.add("settings", settingsObj);
            modulesObj.add(module.getId(), modObj);
        }

        root.add("modules", modulesObj);
        return GSON.toJson(root);
    }

    private void writeSettingProperty(JsonObject settingsObj, Setting<?> setting) {
        String id = setting.getId();
        if (setting instanceof BooleanSetting) {
            settingsObj.addProperty(id, Boolean.valueOf(((BooleanSetting) setting).isEnabled()));
        } else if (setting instanceof NumberSetting) {
            settingsObj.addProperty(id, ((NumberSetting) setting).getValue());
        } else if (setting instanceof ModeSetting) {
            settingsObj.addProperty(id, ((ModeSetting) setting).getValue());
        } else if (setting instanceof ColorSetting) {
            settingsObj.addProperty(id, ((ColorSetting) setting).getHexString());
        } else if (setting instanceof KeybindSetting) {
            settingsObj.addProperty(id, Integer.valueOf(((KeybindSetting) setting).getKeyCode()));
        }
    }

    private String serializeUiConfig() {
        UiConfig snapshot = this.uiConfig.copy();
        JsonObject root = new JsonObject();
        root.addProperty("schema", Integer.valueOf(snapshot.getSchema()));
        root.addProperty("activeProfile", snapshot.getActiveProfile());
        root.addProperty("themeId", snapshot.getThemeId());
        root.addProperty("accentOverride", snapshot.getAccentOverride());
        root.addProperty("uiScaleMultiplier", Float.valueOf(snapshot.getUiScaleMultiplier()));
        root.addProperty("reduceMotion", Boolean.valueOf(snapshot.isReduceMotion()));
        root.addProperty("clickGuiKeybind", Integer.valueOf(snapshot.getClickGuiKeybind()));
        root.addProperty("lastCategory", snapshot.getLastCategory());
        root.addProperty("windowPositionSet", Boolean.valueOf(snapshot.isWindowPositionSet()));
        root.addProperty("windowX", Float.valueOf(snapshot.getWindowX()));
        root.addProperty("windowY", Float.valueOf(snapshot.getWindowY()));

        JsonObject layoutsObj = new JsonObject();
        for (Map.Entry<String, UiConfig.HudLayout> entry : snapshot.getHudLayouts().entrySet()) {
            UiConfig.HudLayout layout = entry.getValue();
            if (layout == null) {
                continue;
            }
            JsonObject layoutObj = new JsonObject();
            layoutObj.addProperty("x", Float.valueOf(layout.getX()));
            layoutObj.addProperty("y", Float.valueOf(layout.getY()));
            layoutObj.addProperty("scale", Float.valueOf(layout.getScale()));
            layoutsObj.add(entry.getKey(), layoutObj);
        }
        root.add("hudLayouts", layoutsObj);
        return GSON.toJson(root);
    }

    private JsonObject readJsonObject(Path file, String context) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = this.jsonParser.parse(reader);
            if (parsed == null || parsed.isJsonNull() || !parsed.isJsonObject()) {
                HoptimizationMod.LOGGER.warn("Config {} is not a JSON object, falling back to defaults", context);
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (Exception e) {
            HoptimizationMod.LOGGER.warn("Failed to parse {} at {}, falling back to defaults", context, file, e);
            return null;
        }
    }

    private Boolean readBoolean(JsonObject obj, String key, String context) {
        if (!obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isBoolean()) {
            HoptimizationMod.LOGGER.warn("Expected boolean for '{}' in {}, using default", key, context);
            return null;
        }
        return Boolean.valueOf(elem.getAsBoolean());
    }

    private Integer readInt(JsonObject obj, String key, String context) {
        if (!obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isNumber()) {
            HoptimizationMod.LOGGER.warn("Expected integer for '{}' in {}, using default", key, context);
            return null;
        }
        return Integer.valueOf(elem.getAsInt());
    }

    private Double readNumber(JsonObject obj, String key, String context) {
        if (!obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isNumber()) {
            HoptimizationMod.LOGGER.warn("Expected number for '{}' in {}, using default", key, context);
            return null;
        }
        double value = elem.getAsDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            HoptimizationMod.LOGGER.warn("Non-finite number for '{}' in {}, using default", key, context);
            return null;
        }
        return Double.valueOf(value);
    }

    private String readString(JsonObject obj, String key, String context) {
        if (!obj.has(key)) {
            return null;
        }
        JsonElement elem = obj.get(key);
        if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isString()) {
            HoptimizationMod.LOGGER.warn("Expected string for '{}' in {}, using default", key, context);
            return null;
        }
        return elem.getAsString();
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(this.configDir);
            Files.createDirectories(this.profilesDir);
            Files.createDirectories(this.themesDir);
        } catch (IOException e) {
            HoptimizationMod.LOGGER.error("Failed to create config directories under {}", this.configDir, e);
        }
    }

    private Path getProfilePath(String name) {
        return this.profilesDir.resolve(name + ".json");
    }

    private String findExistingProfileName(String candidate) {
        List<String> profiles = listProfiles();
        for (int i = 0; i < profiles.size(); i++) {
            String existing = profiles.get(i);
            if (existing.equalsIgnoreCase(candidate)) {
                return existing;
            }
        }
        return null;
    }

    private void cancelPendingProfileSave() {
        synchronized (this.ioLock) {
            if (this.pendingProfileTask != null) {
                this.pendingProfileTask.cancel(false);
                this.pendingProfileTask = null;
            }
            this.profileSequence++;
            this.pendingProfileSnapshot = null;
            this.pendingProfileName = null;
        }
    }

    private void flushPendingProfileSave() {
        Future<?> future = null;
        synchronized (this.ioLock) {
            if (this.pendingProfileTask != null) {
                this.pendingProfileTask.cancel(false);
                this.pendingProfileTask = null;
            }
            this.profileSequence++;
            if (this.pendingProfileSnapshot != null && this.pendingProfileName != null) {
                final Path target = getProfilePath(this.pendingProfileName);
                final String data = this.pendingProfileSnapshot;
                this.pendingProfileSnapshot = null;
                this.pendingProfileName = null;
                future = this.writerExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        writeAtomically(target, data);
                    }
                });
            }
        }
        awaitFuture(future);
    }

    private void flushPendingUiSave() {
        Future<?> future = null;
        synchronized (this.ioLock) {
            if (this.pendingUiTask != null) {
                this.pendingUiTask.cancel(false);
                this.pendingUiTask = null;
            }
            this.uiSequence++;
            if (this.pendingUiSnapshot != null) {
                final String data = this.pendingUiSnapshot;
                this.pendingUiSnapshot = null;
                future = this.writerExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        writeAtomically(uiConfigFile, data);
                    }
                });
            }
        }
        awaitFuture(future);
    }

    private void writeSyncThroughExecutor(final Path target, final String content) {
        awaitExecutorTask(new Runnable() {
            @Override
            public void run() {
                writeAtomically(target, content);
            }
        });
    }

    private void awaitExecutorDrain() {
        awaitExecutorTask(new Runnable() {
            @Override
            public void run() {
                // Barrier task ensures all previously submitted async writes have completed.
            }
        });
    }

    private void awaitExecutorTask(Runnable task) {
        if (this.isShutdown) {
            return;
        }
        Future<?> future = this.writerExecutor.submit(task);
        awaitFuture(future);
    }

    private void awaitFuture(Future<?> future) {
        if (future == null) {
            return;
        }
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HoptimizationMod.LOGGER.warn("Interrupted waiting for config write", e);
        } catch (ExecutionException e) {
            HoptimizationMod.LOGGER.error("Config write task failed", e.getCause());
        }
    }

    private static void writeAtomically(Path target, String jsonContent) {
        Path parent = target.getParent();
        Path tempFile = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                writer.write(jsonContent);
            }
            moveFileSafely(tempFile, target);
        } catch (IOException e) {
            HoptimizationMod.LOGGER.error("Failed to write config file {}", target, e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                HoptimizationMod.LOGGER.warn("Failed to clean up temporary file {}", tempFile, e);
            }
        }
    }

    private static void moveFileSafely(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Fall back to standard replacement when filesystem/OS rejects atomic move across volumes.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeProfileName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_PROFILE_NAME_LENGTH) {
            return null;
        }
        if (".".equals(trimmed) || "..".equals(trimmed) || trimmed.contains("..")) {
            return null;
        }
        if (trimmed.endsWith(".")) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*'
                || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                return null;
            }
        }
        if (isWindowsReservedDeviceName(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static boolean isWindowsReservedDeviceName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        String base = dot >= 0 ? upper.substring(0, dot) : upper;
        if ("CON".equals(base) || "PRN".equals(base) || "AUX".equals(base) || "NUL".equals(base)) {
            return true;
        }
        if (base.length() == 4 && (base.startsWith("COM") || base.startsWith("LPT"))) {
            char digit = base.charAt(3);
            return digit >= '1' && digit <= '9';
        }
        return false;
    }
}
