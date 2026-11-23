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
 * Renders dynamic, randomly appearing glowing 3D rift polylines on living entities,
 * with an optional soft outer silhouette aura and Cheat Mode through-walls visibility.
 */
public class Chams extends Module {
    public static final String ID = "chams";

    private static volatile Chams instance;

    private final BooleanSetting players;
    private final BooleanSetting mobs;
    private final BooleanSetting self;
    private final BooleanSetting throughWalls;
    private final NumberSetting riftCount;
    private final NumberSetting glowStrength;
    private final BooleanSetting outerAura;
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
        this.riftCount = addSetting(
                new NumberSetting("rift_count", "setting.hoptimization.chams.rift_count.name", 5.0, 2.0, 12.0, 1.0));
        this.glowStrength = addSetting(
                new NumberSetting("glow_strength", "setting.hoptimization.chams.glow_strength.name", 1.0, 0.3, 1.6, 0.05));
        this.outerAura = addSetting(
                new BooleanSetting("outer_aura", "setting.hoptimization.chams.outer_aura.name", true));
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

    public int getRiftCount() {
        return Math.max(2, Math.min(12, this.riftCount.getValue().intValue()));
    }

    public float getGlowStrength() {
        return this.glowStrength.getValue().floatValue();
    }

    public boolean isOuterAuraEnabled() {
        return this.outerAura.isEnabled();
    }

    public int getEffectColor() {
        if (this.followTheme.isEnabled()) {
            return ThemeManager.current().accent();
        }
        return this.color.getArgb();
    }
}
