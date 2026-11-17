package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.Random;

/**
 * Cheat-gated module that resets sprint briefly on melee hits to maximize knockback dealt.
 */
public class WTap extends Module {
    public static final String ID = "w_tap";
    private static WTap instance;

    private final NumberSetting holdTicks;
    private final NumberSetting chance;
    private final BooleanSetting onlyMoving;
    private final Random random = new Random();
    private int resetTicksRemaining;

    /**
     * Creates the W-Tap module (disabled by default, requires Cheat Mode).
     */
    public WTap() {
        super(ID, Category.COMBAT, Keyboard.KEY_NONE, true, true);
        this.holdTicks = addSetting(
            new NumberSetting("hold_ticks", "setting.hoptimization.w_tap.hold_ticks.name", 2.0D, 1.0D, 4.0D, 1.0D, "t")
        );
        this.chance = addSetting(
            new NumberSetting("chance", "setting.hoptimization.w_tap.chance.name", 100.0D, 10.0D, 100.0D, 5.0D, "%")
        );
        this.onlyMoving = addSetting(
            new BooleanSetting("only_moving", "setting.hoptimization.w_tap.only_moving.name", true)
        );
        instance = this;
    }

    /**
     * Returns the sprint release hold duration setting in client ticks.
     *
     * @return hold-ticks setting
     */
    public NumberSetting getHoldTicksSetting() {
        return this.holdTicks;
    }

    /**
     * Returns the W-Tap activation chance percentage setting.
     *
     * @return chance setting
     */
    public NumberSetting getChanceSetting() {
        return this.chance;
    }

    /**
     * Returns the only-when-moving-forward toggle setting.
     *
     * @return only-moving setting
     */
    public BooleanSetting getOnlyMovingSetting() {
        return this.onlyMoving;
    }

    /**
     * Queried by {@link com.hoptimization.mixin.combat.MixinEntityPlayerSPCombat} to prevent
     * held sprint keys from immediately re-enabling sprint before the stop-sprint packet sends.
     *
     * @return true while an active sprint reset window is running
     */
    public static boolean shouldSuppressSprint() {
        WTap module = instance;
        return module != null && module.isEnabled() && module.isAvailable() && module.resetTicksRemaining > 0;
    }

    @Override
    protected void onEnable() {
        this.resetTicksRemaining = 0;
    }

    @Override
    protected void onDisable() {
        this.resetTicksRemaining = 0;
    }

    /**
     * Triggers a sprint reset when the local client player attacks a living target.
     *
     * @param event Forge attack entity event
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled() || !isAvailable()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        // Ignore integrated-server AttackEntityEvent firing for EntityPlayerMP on singleplayer.
        if (event.entityPlayer != mc.thePlayer) {
            return;
        }
        if (!(event.target instanceof EntityLivingBase) || !event.target.isEntityAlive()) {
            return;
        }
        // Do not restart an ongoing reset window so high CPS clicking still completes sprint re-engagement.
        if (this.resetTicksRemaining > 0) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (!player.isSprinting()) {
            return;
        }
        if (this.onlyMoving.isEnabled() && !isMovingForward(mc, player)) {
            return;
        }
        double chancePct = this.chance.getDoubleValue();
        if (chancePct < 100.0D && this.random.nextDouble() * 100.0D >= chancePct) {
            return;
        }

        int ticks = this.holdTicks.getIntValue();
        if (ticks < 1) {
            ticks = 1;
        }
        this.resetTicksRemaining = ticks;
        player.setSprinting(false);
    }

    /**
     * Counts down the hold window and cleanly resumes sprinting once {@code hold_ticks} elapse.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!isEnabled() || !isAvailable()) {
            this.resetTicksRemaining = 0;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            this.resetTicksRemaining = 0;
            return;
        }
        if (mc.isGamePaused() || this.resetTicksRemaining <= 0) {
            return;
        }

        this.resetTicksRemaining--;
        if (this.resetTicksRemaining <= 0) {
            this.resetTicksRemaining = 0;
            if (canResumeSprint(mc, mc.thePlayer)) {
                mc.thePlayer.setSprinting(true);
            }
        }
    }

    private static boolean isMovingForward(Minecraft mc, EntityPlayerSP player) {
        return mc.gameSettings.keyBindForward.isKeyDown() || player.moveForward > 0.0F;
    }

    private static boolean canResumeSprint(Minecraft mc, EntityPlayerSP player) {
        if (player.isDead || player.getHealth() <= 0.0F) {
            return false;
        }
        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return false;
        }
        if (player.isSneaking() || player.isUsingItem() || player.isCollidedHorizontally) {
            return false;
        }
        if (player.isPotionActive(Potion.blindness)) {
            return false;
        }
        return (float) player.getFoodStats().getFoodLevel() > 6.0F || player.capabilities.allowFlying;
    }
}
