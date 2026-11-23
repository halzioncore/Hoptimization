package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.FontWeight;

/**
 * Replaces Minecraft's global {@code FontRenderer} with a crisp, pixel-hinted modern TTF font
 * (Windows Segoe UI + Microsoft YaHei UI or bundled Inter) supporting all vanilla {@code §} formatting codes.
 */
public class ModernFont extends Module {
    public static final String ID = "modern_font";

    public static final String FAMILY_SYSTEM = "crisp_system";
    public static final String FAMILY_GOOGLE_SANS = "google_sans";
    public static final String FAMILY_INTER = "inter";

    public static final String WEIGHT_REGULAR = "regular";
    public static final String WEIGHT_MEDIUM = "medium";
    public static final String WEIGHT_SEMIBOLD = "semibold";

    public static final String SHADOW_MODERN = "modern";
    public static final String SHADOW_VANILLA = "vanilla";
    public static final String SHADOW_OFF = "off";

    private static volatile ModernFont instance;

    private final ModeSetting family;
    private final ModeSetting weight;
    private final NumberSetting scale;
    private final ModeSetting shadow;

    /**
     * Creates the Modern Font module (enabled by default).
     */
    public ModernFont() {
        super(ID, Category.VISUAL);
        this.family = addSetting(
            new ModeSetting(
                "family",
                "setting.hoptimization.modern_font.family.name",
                FAMILY_SYSTEM,
                FAMILY_SYSTEM,
                FAMILY_GOOGLE_SANS,
                FAMILY_INTER
            )
        );
        this.family.addChangeListener(new Runnable() {
            @Override
            public void run() {
                String val = family.getValue();
                if (FAMILY_GOOGLE_SANS.equals(val)) {
                    FontManager.setFontFamilyMode(FontManager.FontFamilyMode.GOOGLE_SANS);
                } else if (FAMILY_INTER.equals(val)) {
                    FontManager.setFontFamilyMode(FontManager.FontFamilyMode.INTER);
                } else {
                    FontManager.setFontFamilyMode(FontManager.FontFamilyMode.CRISP_SYSTEM);
                }
            }
        });
        this.weight = addSetting(
            new ModeSetting(
                "weight",
                "setting.hoptimization.modern_font.weight.name",
                WEIGHT_MEDIUM,
                WEIGHT_REGULAR,
                WEIGHT_MEDIUM,
                WEIGHT_SEMIBOLD
            )
        );
        this.scale = addSetting(
            new NumberSetting(
                "scale",
                "setting.hoptimization.modern_font.scale.name",
                1.00D,
                0.85D,
                1.20D,
                0.05D,
                "x"
            )
        );
        this.shadow = addSetting(
            new ModeSetting(
                "shadow",
                "setting.hoptimization.modern_font.shadow.name",
                SHADOW_MODERN,
                SHADOW_MODERN,
                SHADOW_VANILLA,
                SHADOW_OFF
            )
        );
        instance = this;
        setEnabled(true);
    }

    @Override
    public void resetToDefaults() {
        super.resetToDefaults();
        setEnabled(true);
    }

    /**
     * Returns whether the global modern font replacement is currently active.
     *
     * @return {@code true} if enabled
     */
    public static boolean isActive() {
        ModernFont inst = instance;
        return inst != null && inst.isEnabled();
    }

    /**
     * Returns the configured base {@link FontWeight} for normal (non-bold) game text.
     *
     * @return active base font weight
     */
    public static FontWeight getConfiguredWeight() {
        ModernFont inst = instance;
        if (inst == null) {
            return FontWeight.MEDIUM;
        }
        String val = inst.weight.getValue();
        if (WEIGHT_REGULAR.equals(val)) {
            return FontWeight.REGULAR;
        }
        if (WEIGHT_SEMIBOLD.equals(val)) {
            return FontWeight.SEMIBOLD;
        }
        return FontWeight.MEDIUM;
    }

    /**
     * Returns the configured relative font scale multiplier in {@code [0.85, 1.20]}.
     *
     * @return font scale multiplier
     */
    public static float getConfiguredScale() {
        ModernFont inst = instance;
        return inst != null ? inst.scale.getFloatValue() : 1.0f;
    }

    /**
     * Returns the configured shadow mode ({@code "modern"}, {@code "vanilla"}, or {@code "off"}).
     *
     * @return shadow mode id
     */
    public static String getConfiguredShadowMode() {
        ModernFont inst = instance;
        return inst != null ? inst.shadow.getValue() : SHADOW_MODERN;
    }

    public ModeSetting getWeightSetting() {
        return this.weight;
    }

    public NumberSetting getScaleSetting() {
        return this.scale;
    }

    public ModeSetting getShadowSetting() {
        return this.shadow;
    }
}
