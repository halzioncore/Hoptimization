package com.hoptimization.modules.visual;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Renders 3D topographic contour lines and a soft Keystrokes-style outer glow
 * on living entities, with optional through-walls visibility when Cheat Mode is active.
 */
public class Chams extends Module {
    public static final String ID = "chams";

    private static volatile Chams instance;

    private final BooleanSetting players;
    private final BooleanSetting mobs;
    private final BooleanSetting self;
    private final BooleanSetting throughWalls;
    private final NumberSetting contourDensity;
    private final BooleanSetting contourFlow;
    private final NumberSetting glowStrength;
    private final NumberSetting fillOpacity;
    private final BooleanSetting followTheme;
    private final ColorSetting color;

    public Chams() {
        super(ID, Category.VISUAL);
        this.players = addSetting(new BooleanSetting("players", "setting.hoptimization.chams.players.name", true));
        this.mobs = addSetting(new BooleanSetting("mobs", "setting.hoptimization.chams.mobs.name", false));
        this.self = addSetting(new BooleanSetting("self", "setting.hoptimization.chams.self.name", false));
        this.throughWalls = addSetting(
                new BooleanSetting("through_walls", "setting.hoptimization.chams.through_walls.name", false)
                        .visibleWhen(HoptimizationMod::isCheatModeEnabled));
        this.contourDensity = addSetting(
                new NumberSetting("contour_density", "setting.hoptimization.chams.contour_density.name", 2.4, 0.8, 5.0, 0.2));
        this.contourFlow = addSetting(
                new BooleanSetting("contour_flow", "setting.hoptimization.chams.contour_flow.name", true));
        this.glowStrength = addSetting(
                new NumberSetting("glow_strength", "setting.hoptimization.chams.glow_strength.name", 0.85, 0.2, 1.5, 0.05));
        this.fillOpacity = addSetting(
                new NumberSetting("fill_opacity", "setting.hoptimization.chams.fill_opacity.name", 0.14, 0.0, 0.45, 0.02));
        this.followTheme = addSetting(
                new BooleanSetting("follow_theme", "setting.hoptimization.chams.follow_theme.name", true));
        this.color = addSetting(
                new ColorSetting("color", "setting.hoptimization.chams.color.name", 0xFF5B8CFF, false)
                        .visibleWhen(() -> !this.followTheme.isEnabled()));

        instance = this;
        setEnabled(true);
    }

    public static Chams get() {
        return instance;
    }

    public static boolean shouldRenderEntity(EntityLivingBase entity) {
        if (entity == null || entity instanceof EntityArmorStand || entity.isDead) {
            return false;
        }
        Chams chams = get();
        if (chams == null || !chams.isEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && entity == mc.thePlayer) {
            return chams.self.isEnabled();
        }
        if (entity instanceof EntityPlayer) {
            return chams.players.isEnabled();
        }
        return chams.mobs.isEnabled();
    }

    public boolean isThroughWalls() {
        return HoptimizationMod.isCheatModeEnabled() && this.throughWalls.isEnabled();
    }

    public float getContourDensity() {
        return this.contourDensity.getValue().floatValue();
    }

    public boolean isContourFlow() {
        return this.contourFlow.isEnabled();
    }

    public float getGlowStrength() {
        return this.glowStrength.getValue().floatValue();
    }

    public float getFillOpacity() {
        return this.fillOpacity.getValue().floatValue();
    }

    public int getEffectColor() {
        if (this.followTheme.isEnabled()) {
            return ThemeManager.current().accent();
        }
        return this.color.getArgb();
    }
}
