package com.hoptimization.core.module;

import net.minecraft.client.resources.I18n;

import java.util.Locale;

/**
 * Fixed module categories shown in the ClickGUI sidebar.
 */
public enum Category {
    COMBAT("combat", "category.hoptimization.combat", "sword"),
    MOVEMENT("movement", "category.hoptimization.movement", "wind"),
    VISUAL("visual", "category.hoptimization.visual", "eye"),
    UI("ui", "category.hoptimization.ui", "layout"),
    PERFORMANCE("performance", "category.hoptimization.performance", "gauge"),
    MISC("misc", "category.hoptimization.misc", "sliders");

    private final String id;
    private final String nameKey;
    private final String iconName;

    Category(String id, String nameKey, String iconName) {
        this.id = id;
        this.nameKey = nameKey;
        this.iconName = iconName;
    }

    /**
     * Returns the stable lowercase identifier used in configuration files.
     *
     * @return category id
     */
    public String getId() {
        return this.id;
    }

    /**
     * Returns the localization key for this category name.
     *
     * @return localization key
     */
    public String getNameKey() {
        return this.nameKey;
    }

    /**
     * Returns the icon asset base name under {@code assets/hoptimization/texture/icon/}.
     *
     * @return icon name
     */
    public String getIconName() {
        return this.iconName;
    }

    /**
     * Returns the localized display name for this category.
     *
     * @return localized category name
     */
    public String getDisplayName() {
        return I18n.format(this.nameKey);
    }

    /**
     * Finds a category matching the given identifier case-insensitively.
     * Legacy {@code "hud"} identifiers are mapped to {@link #UI}.
     *
     * @param id candidate category id
     * @return matching category, or null if unknown
     */
    public static Category fromId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if ("hud".equals(normalized)) {
            return UI;
        }
        Category[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].id.equals(normalized)) {
                return values[i];
            }
        }
        return null;
    }

    /**
     * Finds a category matching the given identifier, or returns the provided fallback.
     *
     * @param id candidate category id
     * @param fallback fallback category when {@code id} is null or unknown
     * @return matching category or fallback
     */
    public static Category fromIdOrDefault(String id, Category fallback) {
        Category resolved = fromId(id);
        return resolved != null ? resolved : fallback;
    }
}
