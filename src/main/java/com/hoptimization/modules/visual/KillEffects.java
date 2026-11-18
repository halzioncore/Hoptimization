package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.modules.visual.killeffects.KillEffectInstance;
import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import com.hoptimization.modules.visual.killeffects.KillEffectsTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Replaces vanilla entity death tilting with stylized Soul Sever or Annihilation kill effects.
 */
public class KillEffects extends Module {
    public static final String ID = "kill_effects";
    public static final String MODE_SOUL_SEVER = "soul_sever";
    public static final String MODE_ANNIHILATION = "annihilation";
    public static final String TARGET_ALL = "all";
    public static final String TARGET_PLAYERS = "players";
    public static final String TARGET_MOBS = "mobs";

    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;
    private static final double MIN_DIRECTION_DIST_SQ = 1.0E-8D;
    private static final float DEG_TO_RAD = 0.017453292F;

    private static KillEffects instance;

    private final ModeSetting mode;
    private final ModeSetting targets;
    private final BooleanSetting onlyOwnKills;
    private final NumberSetting duration;
    private final ColorSetting soulColor;
    private final NumberSetting weaponScale;
    private final BooleanSetting particles;
    private final BooleanSetting sound;

    private final KillEffectsTracker tracker = new KillEffectsTracker();

    /**
     * Creates the Kill Effects module and registers its settings in display order.
     */
    public KillEffects() {
        super(ID, Category.VISUAL);
        this.mode = addSetting(
            new ModeSetting(
                "mode",
                "setting.hoptimization.kill_effects.mode.name",
                MODE_SOUL_SEVER,
                MODE_SOUL_SEVER,
                MODE_ANNIHILATION
            )
        );
        this.targets = addSetting(
            new ModeSetting(
                "targets",
                "setting.hoptimization.kill_effects.targets.name",
                TARGET_ALL,
                TARGET_ALL,
                TARGET_PLAYERS,
                TARGET_MOBS
            )
        );
        this.onlyOwnKills = addSetting(
            new BooleanSetting("only_own_kills", "setting.hoptimization.kill_effects.only_own_kills.name", true)
        );
        this.duration = addSetting(
            new NumberSetting(
                "duration",
                "setting.hoptimization.kill_effects.duration.name",
                1.2D,
                0.5D,
                2.5D,
                0.1D,
                "s"
            )
        );
        this.soulColor = addSetting(
            new ColorSetting("soul_color", "setting.hoptimization.kill_effects.soul_color.name", 0xFFDCEEFF, false)
                .visibleWhen(() -> Boolean.valueOf(this.mode.is(MODE_SOUL_SEVER)))
        );
        this.weaponScale = addSetting(
            new NumberSetting(
                "weapon_scale",
                "setting.hoptimization.kill_effects.weapon_scale.name",
                1.8D,
                1.0D,
                3.0D,
                0.1D,
                "x"
            ).visibleWhen(() -> Boolean.valueOf(this.mode.is(MODE_SOUL_SEVER)))
        );
        this.particles = addSetting(
            new BooleanSetting("particles", "setting.hoptimization.kill_effects.particles.name", true)
        );
        this.sound = addSetting(
            new BooleanSetting("sound", "setting.hoptimization.kill_effects.sound.name", true)
        );
        instance = this;
    }

    /**
     * Returns the kill visual mode setting.
     *
     * @return mode setting
     */
    public ModeSetting getModeSetting() {
        return this.mode;
    }

    /**
     * Returns the eligible target filter setting.
     *
     * @return targets setting
     */
    public ModeSetting getTargetsSetting() {
        return this.targets;
    }

    /**
     * Returns whether effects are restricted to kills attributed to the local player.
     *
     * @return only own kills setting
     */
    public BooleanSetting getOnlyOwnKillsSetting() {
        return this.onlyOwnKills;
    }

    /**
     * Returns the effect duration setting in seconds.
     *
     * @return duration setting
     */
    public NumberSetting getDurationSetting() {
        return this.duration;
    }

    /**
     * Returns the Soul Sever tint color setting.
     *
     * @return soul color setting
     */
    public ColorSetting getSoulColorSetting() {
        return this.soulColor;
    }

    /**
     * Returns the Soul Sever phantom weapon scale setting.
     *
     * @return weapon scale setting
     */
    public NumberSetting getWeaponScaleSetting() {
        return this.weaponScale;
    }

    /**
     * Returns the particle burst toggle setting.
     *
     * @return particles setting
     */
    public BooleanSetting getParticlesSetting() {
        return this.particles;
    }

    /**
     * Returns the kill sound toggle setting.
     *
     * @return sound setting
     */
    public BooleanSetting getSoundSetting() {
        return this.sound;
    }

    /**
     * Returns the pooled effect tracker owned by this module.
     *
     * @return kill effects tracker
     */
    public KillEffectsTracker getTracker() {
        return this.tracker;
    }

    /**
     * Returns whether Kill Effects is currently initialized and enabled.
     *
     * @return true if active
     */
    public static boolean isActive() {
        KillEffects module = instance;
        return module != null && module.isEnabled();
    }

    /**
     * Handles client death status packet updates ({@code S19PacketEntityStatus} opcode 3).
     *
     * @param entity living entity receiving death status
     */
    public static void onClientEntityDeathStatus(EntityLivingBase entity) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return;
        }
        if (!module.isTargetEligible(entity, mc)) {
            return;
        }
        long nowNanos = System.nanoTime();
        int entityId = entity.getEntityId();
        int identityHash = System.identityHashCode(entity);
        if (module.tracker.isRecentlyDead(entityId, identityHash, nowNanos)) {
            return;
        }
        if (!module.hasOwnKillAttribution(entity, mc, nowNanos)) {
            return;
        }
        module.captureAndRemoveCorpse(entity, mc, nowNanos);
    }

    /**
     * Handles per-tick death updates on client living entities and cancels vanilla death animation when handled.
     *
     * @param entity dying client entity
     * @return true if vanilla {@code onDeathUpdate} should be canceled
     */
    public static boolean onClientEntityDeathTick(EntityLivingBase entity) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return false;
        }
        if (!module.isTargetEligible(entity, mc)) {
            return false;
        }
        long nowNanos = System.nanoTime();
        if (module.tracker.shouldSuppressRender(entity, nowNanos)) {
            return true;
        }
        if (!module.hasOwnKillAttribution(entity, mc, nowNanos)) {
            return false;
        }
        module.captureAndRemoveCorpse(entity, mc, nowNanos);
        return true;
    }

    /**
     * Handles entity destruction packets ({@code S13PacketDestroyEntities}) for instant-despawn kills.
     *
     * @param entityId destroyed entity network id
     */
    public static void onClientEntityDestroy(int entityId) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Entity raw = mc.theWorld.getEntityByID(entityId);
        if (!(raw instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) raw;
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return;
        }
        if (!module.isTargetEligible(entity, mc)) {
            return;
        }
        long nowNanos = System.nanoTime();
        int identityHash = System.identityHashCode(entity);
        if (module.tracker.isRecentlyDead(entityId, identityHash, nowNanos)) {
            return;
        }
        boolean attackedRecently1_5s = module.tracker.wasRecentlyAttacked(
            entityId,
            nowNanos,
            KillEffectsTracker.DESTROY_HURT_WINDOW_NANOS
        ) || module.tracker.isRecentPlayerProjectileNear(entity, mc.theWorld, mc.thePlayer, nowNanos);
        boolean lethalDestroy = entity.getHealth() <= 0.0F
            || entity.deathTime > 0
            || (attackedRecently1_5s && entity.hurtTime > 0);
        if (!lethalDestroy) {
            return;
        }
        if (module.onlyOwnKills.isEnabled()
            && !attackedRecently1_5s
            && !module.tracker.wasRecentlyAttacked(entityId, nowNanos, KillEffectsTracker.ATTACK_WINDOW_NANOS)) {
            return;
        }
        module.captureAndRemoveCorpse(entity, mc, nowNanos);
    }

    /**
     * Returns whether vanilla entity rendering should be suppressed for a captured corpse.
     *
     * @param entity entity scheduled for rendering
     * @return true if original rendering should be skipped
     */
    public static boolean shouldSuppressOriginalRender(Entity entity) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || !(entity instanceof EntityLivingBase)) {
            return false;
        }
        return module.tracker.shouldSuppressRender((EntityLivingBase) entity, System.nanoTime());
    }

    @Override
    protected void onEnable() {
        this.tracker.clear();
    }

    @Override
    protected void onDisable() {
        this.tracker.clear();
        KillEffectsRenderer.clearState();
    }

    /**
     * Records local player melee hits for kill attribution.
     *
     * @param event Forge attack entity event
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled() || event == null) {
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
        if (event.target instanceof EntityLivingBase
            && event.target != mc.thePlayer
            && !(event.target instanceof EntityArmorStand)) {
            this.tracker.recordAttack(event.target.getEntityId(), System.nanoTime());
        }
    }

    /**
     * Tracks player-owned projectiles and attributes fresh hit ticks on nearby living targets.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!isEnabled() || event == null || event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            this.tracker.clear();
            KillEffectsRenderer.clearState();
            return;
        }
        if (mc.isGamePaused()) {
            return;
        }
        this.tracker.tickProjectiles(mc.theWorld, mc.thePlayer, System.nanoTime());
    }

    /**
     * Renders active kill effects and particle pools in world space.
     *
     * @param event Forge render world last event
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }
        KillEffectsRenderer.render(this.tracker, event.partialTicks);
    }

    /**
     * Clears tracked effects and renderer state when the client world unloads.
     *
     * @param event Forge world unload event
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event != null && event.world != null && event.world.isRemote) {
            this.tracker.clear();
            KillEffectsRenderer.clearState();
        }
    }

    private boolean isTargetEligible(EntityLivingBase entity, Minecraft mc) {
        if (entity == mc.thePlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        if (this.targets.is(TARGET_PLAYERS)) {
            return entity instanceof EntityPlayer;
        }
        if (this.targets.is(TARGET_MOBS)) {
            return !(entity instanceof EntityPlayer);
        }
        return true;
    }

    private boolean hasOwnKillAttribution(EntityLivingBase entity, Minecraft mc, long nowNanos) {
        if (!this.onlyOwnKills.isEnabled()) {
            return true;
        }
        if (this.tracker.wasRecentlyAttacked(entity.getEntityId(), nowNanos, KillEffectsTracker.ATTACK_WINDOW_NANOS)) {
            return true;
        }
        return this.tracker.isRecentPlayerProjectileNear(entity, mc.theWorld, mc.thePlayer, nowNanos);
    }

    private void captureAndRemoveCorpse(EntityLivingBase entity, Minecraft mc, long nowNanos) {
        int entityId = entity.getEntityId();
        int identityHash = System.identityHashCode(entity);
        this.tracker.markDead(entityId, identityHash, nowNanos);

        KillEffectInstance slot = this.tracker.acquireSlot(nowNanos);
        int effectMode = this.mode.is(MODE_ANNIHILATION)
            ? KillEffectInstance.MODE_ANNIHILATION
            : KillEffectInstance.MODE_SOUL_SEVER;

        double dx = entity.posX - mc.thePlayer.posX;
        double dz = entity.posZ - mc.thePlayer.posZ;
        double distSq = dx * dx + dz * dz;
        float behindDirX;
        float behindDirZ;
        if (distSq >= MIN_DIRECTION_DIST_SQ) {
            double invDist = 1.0D / Math.sqrt(distSq);
            behindDirX = (float) (dx * invDist);
            behindDirZ = (float) (dz * invDist);
        } else {
            Vec3 look = entity.getLookVec();
            double lx = look != null ? look.xCoord : 0.0D;
            double lz = look != null ? look.zCoord : 1.0D;
            double lookDistSq = lx * lx + lz * lz;
            if (lookDistSq >= MIN_DIRECTION_DIST_SQ) {
                double invLook = 1.0D / Math.sqrt(lookDistSq);
                behindDirX = (float) (lx * invLook);
                behindDirZ = (float) (lz * invLook);
            } else {
                float yawRad = entity.rotationYaw * DEG_TO_RAD;
                behindDirX = -MathHelper.sin(yawRad);
                behindDirZ = MathHelper.cos(yawRad);
            }
        }

        ItemStack rawHeld = mc.thePlayer.getHeldItem();
        slot.mode = effectMode;
        slot.entityId = entityId;
        slot.entity = entity;
        slot.startNanos = nowNanos;
        slot.durationNanos = Math.max(1L, (long) (this.duration.getDoubleValue() * NANOS_PER_SECOND));
        slot.x = entity.posX;
        slot.y = entity.posY;
        slot.z = entity.posZ;
        slot.entityWidth = entity.width;
        slot.entityHeight = entity.height;
        slot.yaw = entity.rotationYaw;
        slot.behindDirX = behindDirX;
        slot.behindDirZ = behindDirZ;
        slot.heldItem = rawHeld != null ? rawHeld.copy() : null;
        slot.weaponScale = this.weaponScale.getFloatValue();
        slot.soulArgb = this.soulColor.getArgb();
        slot.particlesEnabled = this.particles.isEnabled();
        slot.soundEnabled = this.sound.isEnabled();
        slot.cleaveTriggered = false;
        slot.burstSpawned = false;
        slot.savedDeathTime = entity.deathTime;
        slot.savedHurtTime = entity.hurtTime;

        // Freeze interpolation and hurt/death timers so the rendered ghost stands upright in its lethal pose.
        entity.prevRenderYawOffset = entity.renderYawOffset;
        entity.prevRotationYawHead = entity.rotationYawHead;
        entity.prevRotationYaw = entity.rotationYaw;
        entity.prevRotationPitch = entity.rotationPitch;
        entity.prevLimbSwingAmount = entity.limbSwingAmount;
        entity.prevSwingProgress = entity.swingProgress;
        entity.lastTickPosX = entity.posX;
        entity.lastTickPosY = entity.posY;
        entity.lastTickPosZ = entity.posZ;
        entity.prevPosX = entity.posX;
        entity.prevPosY = entity.posY;
        entity.prevPosZ = entity.posZ;
        entity.deathTime = 0;
        entity.hurtTime = 0;

        if (entity.worldObj instanceof WorldClient) {
            ((WorldClient) entity.worldObj).removeEntityFromWorld(entityId);
        }
        entity.setDead();

        if (slot.soundEnabled && mc.getRenderViewEntity() != null) {
            double soundY = slot.y + (double) slot.entityHeight * 0.5D;
            if (effectMode == KillEffectInstance.MODE_ANNIHILATION) {
                mc.theWorld.playSound(slot.x, soundY, slot.z, "random.fizz", 0.25F, 1.5F, false);
            } else {
                mc.theWorld.playSound(slot.x, soundY, slot.z, "mob.endermen.portal", 0.4F, 1.45F, false);
            }
        }
    }
}
