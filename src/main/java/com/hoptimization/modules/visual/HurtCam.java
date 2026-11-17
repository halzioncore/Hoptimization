package com.hoptimization.modules.visual;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

/**
 * Cancels or scales the camera tilt shake triggered when taking damage.
 */
public class HurtCam extends Module {
    public static final String ID = "hurt_cam";

    private final NumberSetting intensity;

    /**
     * Creates the Hurt Cam module and registers its settings.
     */
    public HurtCam() {
        super(ID, Category.VISUAL);
        this.intensity = addSetting(
            new NumberSetting(
                "intensity",
                "setting.hoptimization.hurt_cam.intensity.name",
                0.0D,
                0.0D,
                100.0D,
                5.0D,
                "%"
            )
        );
    }

    /**
     * Returns the hurt camera intensity percentage setting.
     *
     * @return intensity setting
     */
    public NumberSetting getIntensitySetting() {
        return this.intensity;
    }

    /**
     * Applies scaled hurt camera roll or requests cancellation of vanilla hurt camera shake.
     *
     * @param mc Minecraft client instance
     * @param partialTicks render partial ticks
     * @return true if vanilla {@code hurtCameraEffect} should be canceled
     */
    public static boolean handleHurtCameraEffect(Minecraft mc, float partialTicks) {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        HurtCam module = manager != null ? manager.getModule(HurtCam.class) : null;
        if (module == null || !module.isEnabled()) {
            return false;
        }
        float pct = module.intensity.getFloatValue();
        if (pct <= 0.0F) {
            return true;
        }
        if (pct >= 100.0F) {
            return false;
        }
        applyScaledHurtCamera(mc, partialTicks, pct / 100.0F);
        return true;
    }

    private static void applyScaledHurtCamera(Minecraft mc, float partialTicks, float scale) {
        if (mc == null || !(mc.getRenderViewEntity() instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) mc.getRenderViewEntity();
        float f = (float) entity.hurtTime - partialTicks;
        if (entity.getHealth() <= 0.0F) {
            float deathTime = (float) entity.deathTime + partialTicks;
            GlStateManager.rotate((40.0F - 8000.0F / (deathTime + 200.0F)) * scale, 0.0F, 0.0F, 1.0F);
        }
        if (f < 0.0F || entity.maxHurtTime <= 0) {
            return;
        }
        f /= (float) entity.maxHurtTime;
        f = MathHelper.sin(f * f * f * f * (float) Math.PI);
        float yaw = entity.attackedAtYaw;
        GlStateManager.rotate(-yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-f * 14.0F * scale, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
    }
}
