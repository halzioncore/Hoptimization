package com.hoptimization.modules.misc;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Boosts world lightmap brightness by smoothly managing client gamma while preserving prior user settings.
 */
public class Fullbright extends Module {
    public static final String ID = "fullbright";
    private static final float MAX_BOOSTED_GAMMA = 12.0F;
    private static final float SMOOTH_FACTOR = 0.35F;
    private static final float SNAP_EPSILON = 0.05F;

    private final NumberSetting brightness;
    private float savedGamma = 1.0F;
    private boolean hasSavedGamma;

    /**
     * Creates the Fullbright module and registers its settings.
     */
    public Fullbright() {
        super(ID, Category.MISC);
        this.brightness = addSetting(
            new NumberSetting(
                "brightness",
                "setting.hoptimization.fullbright.brightness.name",
                100.0D,
                0.0D,
                100.0D,
                1.0D,
                "%"
            )
        );
    }

    /**
     * Returns the target brightness percentage setting.
     *
     * @return brightness setting
     */
    public NumberSetting getBrightnessSetting() {
        return this.brightness;
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.gameSettings != null) {
            this.savedGamma = sanitizeGamma(mc.gameSettings.gammaSetting);
            this.hasSavedGamma = true;
        }
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (this.hasSavedGamma && mc != null && mc.gameSettings != null) {
            mc.gameSettings.gammaSetting = this.savedGamma;
        }
        this.hasSavedGamma = false;
    }

    /**
     * Smoothly interpolates {@code gameSettings.gammaSetting} toward the configured brightness level.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        if (!this.hasSavedGamma) {
            this.savedGamma = sanitizeGamma(mc.gameSettings.gammaSetting);
            this.hasSavedGamma = true;
        }

        float target = computeTargetGamma();
        float current = sanitizeGamma(mc.gameSettings.gammaSetting);
        float diff = target - current;
        if (Math.abs(diff) <= SNAP_EPSILON) {
            mc.gameSettings.gammaSetting = target;
        } else {
            mc.gameSettings.gammaSetting = current + diff * SMOOTH_FACTOR;
        }
    }

    private float computeTargetGamma() {
        float ratio = this.brightness.getFloatValue() / 100.0F;
        if (ratio < 0.0F) {
            ratio = 0.0F;
        } else if (ratio > 1.0F) {
            ratio = 1.0F;
        }
        float maxTarget = Math.max(this.savedGamma, MAX_BOOSTED_GAMMA);
        return this.savedGamma + (maxTarget - this.savedGamma) * ratio;
    }

    private static float sanitizeGamma(float gamma) {
        return Float.isNaN(gamma) || Float.isInfinite(gamma) ? 1.0F : gamma;
    }
}
