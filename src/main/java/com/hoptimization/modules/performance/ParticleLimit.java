package com.hoptimization.modules.performance;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.client.particle.EntityAuraFX;
import net.minecraft.client.particle.EntityBubbleFX;
import net.minecraft.client.particle.EntityCrit2FX;
import net.minecraft.client.particle.EntityDiggingFX;
import net.minecraft.client.particle.EntityDropParticleFX;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.particle.EntityPortalFX;
import net.minecraft.client.particle.EntityRainFX;
import net.minecraft.client.particle.EntitySmokeFX;
import net.minecraft.client.particle.EntitySplashFX;
import net.minecraft.client.particle.EntitySuspendFX;

import java.util.List;

/**
 * Limits and filters active client particles to keep frame times steady in particle-heavy scenes.
 */
public class ParticleLimit extends Module {
    public static final String ID = "particle_limit";
    public static final String MODE_ALL = "all";
    public static final String MODE_DECREASED = "decreased";
    public static final String MODE_MINIMAL = "minimal";
    public static final String MODE_OFF = "off";
    public static final String MODE_SMART = "smart";

    private static final int SMART_CRIT_HEADROOM = 64;

    private final NumberSetting limit;
    private final ModeSetting mode;
    private int sampleCounter;

    /**
     * Creates the Particle Limit module and registers its settings.
     */
    public ParticleLimit() {
        super(ID, Category.PERFORMANCE);
        this.limit = addSetting(
            new NumberSetting(
                "limit",
                "setting.hoptimization.particle_limit.limit.name",
                1000.0D,
                0.0D,
                4000.0D,
                100.0D
            )
        );
        this.mode = addSetting(
            new ModeSetting(
                "mode",
                "setting.hoptimization.particle_limit.mode.name",
                MODE_SMART,
                MODE_ALL,
                MODE_DECREASED,
                MODE_MINIMAL,
                MODE_OFF,
                MODE_SMART
            )
        );
    }

    /**
     * Returns the maximum particle count setting.
     *
     * @return particle limit setting
     */
    public NumberSetting getLimitSetting() {
        return this.limit;
    }

    /**
     * Returns the particle filtering mode setting.
     *
     * @return mode setting
     */
    public ModeSetting getModeSetting() {
        return this.mode;
    }

    @Override
    protected void onEnable() {
        this.sampleCounter = 0;
    }

    /**
     * Evaluates whether a newly spawned particle should be added to {@code EffectRenderer}.
     * Zero-allocation hot path helper called from {@code MixinEffectRendererPerf}.
     *
     * @param effect candidate particle
     * @param fxLayers vanilla particle layers from {@code EffectRenderer}
     * @return true to allow spawning, false to drop the particle
     */
    public static boolean shouldSpawnEffect(EntityFX effect, List<EntityFX>[][] fxLayers) {
        if (effect == null) {
            return false;
        }
        ModuleManager manager = HoptimizationMod.getModuleManager();
        ParticleLimit module = manager != null ? manager.getModule(ParticleLimit.class) : null;
        if (module == null || !module.isEnabled()) {
            return true;
        }

        String activeMode = module.mode.getValue();
        if (MODE_OFF.equalsIgnoreCase(activeMode)) {
            return false;
        }

        int maxLimit = module.limit.getIntValue();
        if (maxLimit <= 0) {
            return false;
        }

        int activeCount = countActiveParticles(fxLayers);
        boolean isCombatCrit = effect instanceof EntityCrit2FX;

        if (activeCount >= maxLimit) {
            // Allow critical hit indicators a small buffer in smart mode so combat feedback remains visible.
            return MODE_SMART.equalsIgnoreCase(activeMode)
                && isCombatCrit
                && activeCount < maxLimit + SMART_CRIT_HEADROOM;
        }

        if (MODE_ALL.equalsIgnoreCase(activeMode)) {
            return true;
        }
        if (MODE_DECREASED.equalsIgnoreCase(activeMode)) {
            return isCombatCrit || (module.sampleCounter++ & 1) == 0;
        }
        if (MODE_MINIMAL.equalsIgnoreCase(activeMode)) {
            return isCombatCrit || (module.sampleCounter++ & 7) == 0;
        }
        if (MODE_SMART.equalsIgnoreCase(activeMode)) {
            if (isCombatCrit) {
                return true;
            }
            if (isHighVolumeAmbient(effect)) {
                if (activeCount >= (maxLimit * 3) / 4) {
                    return false;
                }
                if (activeCount >= maxLimit / 2) {
                    return (module.sampleCounter++ & 1) == 0;
                }
            }
            return true;
        }
        return true;
    }

    private static int countActiveParticles(List<EntityFX>[][] fxLayers) {
        if (fxLayers == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < fxLayers.length; i++) {
            List<EntityFX>[] alphaLayers = fxLayers[i];
            if (alphaLayers == null) {
                continue;
            }
            for (int j = 0; j < alphaLayers.length; j++) {
                List<EntityFX> bucket = alphaLayers[j];
                if (bucket != null) {
                    total += bucket.size();
                }
            }
        }
        return total;
    }

    private static boolean isHighVolumeAmbient(EntityFX effect) {
        return effect instanceof EntityDiggingFX
            || effect instanceof EntityRainFX
            || effect instanceof EntitySmokeFX
            || effect instanceof EntityBubbleFX
            || effect instanceof EntitySplashFX
            || effect instanceof EntitySuspendFX
            || effect instanceof EntityAuraFX
            || effect instanceof EntityPortalFX
            || effect instanceof EntityDropParticleFX;
    }
}
