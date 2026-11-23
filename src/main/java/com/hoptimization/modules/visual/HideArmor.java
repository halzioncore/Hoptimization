package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Hides rendered armor layers on enemy players (and optionally teammates, mobs, or self).
 */
public class HideArmor extends Module {
    public static final String ID = "hide_armor";

    private static volatile HideArmor instance;

    private final BooleanSetting ignoreTeammates;
    private final BooleanSetting hideMobs;
    private final BooleanSetting hideSelf;

    public HideArmor() {
        super(ID, Category.VISUAL);
        this.ignoreTeammates = addSetting(
                new BooleanSetting("ignore_teammates", "setting.hoptimization.hide_armor.ignore_teammates.name", true));
        this.hideMobs = addSetting(
                new BooleanSetting("hide_mobs", "setting.hoptimization.hide_armor.hide_mobs.name", false));
        this.hideSelf = addSetting(
                new BooleanSetting("hide_self", "setting.hoptimization.hide_armor.hide_self.name", false));
        instance = this;
        setEnabled(true);
    }

    public static boolean shouldHideArmor(EntityLivingBase entity) {
        HideArmor mod = instance;
        if (mod == null || !mod.isEnabled() || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && entity == mc.thePlayer) {
            return mod.hideSelf.isEnabled();
        }
        if (entity instanceof EntityPlayer) {
            if (mod.ignoreTeammates.isEnabled() && mc != null && mc.thePlayer != null
                    && entity.isOnSameTeam(mc.thePlayer)) {
                return false;
            }
            return true;
        }
        return mod.hideMobs.isEnabled();
    }
}
