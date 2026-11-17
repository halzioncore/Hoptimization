package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Tracks consecutive confirmed melee hits against the current target until timeout or player damage.
 */
public class ComboCounter extends Module {
    public static final String ID = "combo_counter";
    private static final long PENDING_HIT_WINDOW_NANOS = 500_000_000L;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;

    private final NumberSetting timeout;

    private int trackedTargetId = -1;
    private int pendingTargetId = -1;
    private long pendingExpireNanos;
    private int lastTargetHurtTime;
    private int lastPlayerHurtTime;
    private int ticksSinceLastCount;
    private int currentCombo;
    private int maxCombo;
    private long lastHitNanos;

    /**
     * Creates the Combo Counter module and registers its timeout setting.
     */
    public ComboCounter() {
        super(ID, Category.COMBAT);
        this.timeout = addSetting(
            new NumberSetting(
                "timeout",
                "setting.hoptimization.combo_counter.timeout.name",
                3.0D,
                1.0D,
                5.0D,
                0.5D,
                "s"
            )
        );
    }

    /**
     * Returns the combo reset timeout setting in seconds.
     *
     * @return timeout setting
     */
    public NumberSetting getTimeoutSetting() {
        return this.timeout;
    }

    /**
     * Returns the active consecutive hit count without allocating.
     *
     * @return current combo count, or 0 if disabled
     */
    public int getCurrentCombo() {
        return isEnabled() ? this.currentCombo : 0;
    }

    /**
     * Returns the highest combo achieved since the module was enabled.
     *
     * @return maximum combo count
     */
    public int getMaxCombo() {
        return isEnabled() ? this.maxCombo : 0;
    }

    /**
     * Returns the entity id currently being tracked, or {@code -1} if none.
     *
     * @return tracked entity id
     */
    public int getTrackedTargetId() {
        return this.trackedTargetId;
    }

    /**
     * Clears the active combo streak and pending target state.
     */
    public void resetCombo() {
        this.trackedTargetId = -1;
        this.pendingTargetId = -1;
        this.pendingExpireNanos = 0L;
        this.lastTargetHurtTime = 0;
        this.ticksSinceLastCount = 0;
        this.currentCombo = 0;
        this.lastHitNanos = 0L;
    }

    @Override
    protected void onEnable() {
        resetCombo();
        this.maxCombo = 0;
        this.lastPlayerHurtTime = 0;
    }

    @Override
    protected void onDisable() {
        resetCombo();
        this.maxCombo = 0;
        this.lastPlayerHurtTime = 0;
    }

    /**
     * Records a pending melee attack on a living target for hurt-confirmation on subsequent ticks.
     *
     * @param event Forge attack entity event
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        // Filter out integrated-server EntityPlayerMP attack events in singleplayer.
        if (event.entityPlayer != mc.thePlayer) {
            return;
        }
        if (!(event.target instanceof EntityLivingBase) || !event.target.isEntityAlive()) {
            return;
        }

        EntityLivingBase target = (EntityLivingBase) event.target;
        int targetId = target.getEntityId();
        if (targetId != this.trackedTargetId) {
            this.currentCombo = 0;
            this.trackedTargetId = targetId;
            this.lastTargetHurtTime = target.hurtTime;
            this.ticksSinceLastCount = 1;
        }

        this.pendingTargetId = targetId;
        this.pendingExpireNanos = System.nanoTime() + PENDING_HIT_WINDOW_NANOS;
    }

    /**
     * Confirms target hits via hurtTime updates and resets combos on player damage or timeout.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            resetCombo();
            this.lastPlayerHurtTime = 0;
            return;
        }
        if (mc.isGamePaused()) {
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        int playerHurt = player.hurtTime;
        if (playerHurt > this.lastPlayerHurtTime) {
            // Taking damage breaks the active combo streak.
            this.currentCombo = 0;
            this.pendingTargetId = -1;
        }
        this.lastPlayerHurtTime = playerHurt;

        long now = System.nanoTime();
        if (this.currentCombo > 0) {
            long timeoutNanos = (long) (this.timeout.getDoubleValue() * NANOS_PER_SECOND);
            if (now - this.lastHitNanos >= timeoutNanos) {
                this.currentCombo = 0;
            }
        }

        if (this.pendingTargetId != -1 && now > this.pendingExpireNanos) {
            this.pendingTargetId = -1;
        }

        this.ticksSinceLastCount++;
        if (this.trackedTargetId == -1) {
            return;
        }

        Entity entity = mc.theWorld.getEntityByID(this.trackedTargetId);
        if (!(entity instanceof EntityLivingBase) || !entity.isEntityAlive()) {
            this.trackedTargetId = -1;
            this.pendingTargetId = -1;
            this.lastTargetHurtTime = 0;
            return;
        }

        EntityLivingBase target = (EntityLivingBase) entity;
        int hurtTime = target.hurtTime;
        if (this.pendingTargetId == this.trackedTargetId && now <= this.pendingExpireNanos) {
            boolean hitConfirmed = hurtTime > this.lastTargetHurtTime
                || (hurtTime >= 10 && this.ticksSinceLastCount > 0);
            if (hitConfirmed) {
                this.currentCombo++;
                if (this.currentCombo > this.maxCombo) {
                    this.maxCombo = this.currentCombo;
                }
                this.lastHitNanos = now;
                this.pendingTargetId = -1;
                this.ticksSinceLastCount = 0;
            }
        }
        this.lastTargetHurtTime = hurtTime;
    }
}
