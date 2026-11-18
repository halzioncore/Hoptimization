package com.hoptimization.modules.visual.killeffects;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;

import java.util.List;

/**
 * Manages the fixed-capacity pool of active {@link KillEffectInstance} slots, recent player attack
 * attribution (melee, projectiles, fireballs, TNT), global hurt cache, recent target snapshots,
 * pre-teleport cache, and dead-entity deduplication without hot-path allocations.
 */
public final class KillEffectsTracker {
    public static final int MAX_EFFECTS = 16;
    public static final long ATTACK_WINDOW_NANOS = 8_000_000_000L;
    public static final long DESTROY_HURT_WINDOW_NANOS = 1_500_000_000L;
    public static final long GLOBAL_HURT_WINDOW_NANOS = 3_000_000_000L;
    public static final long SNAPSHOT_TTL_NANOS = 12_000_000_000L;
    public static final long PRE_TELEPORT_TTL_NANOS = 1_500_000_000L;

    private static final int ATTACK_CAPACITY = 32;
    private static final int GLOBAL_HURT_CAPACITY = 32;
    private static final int SNAPSHOT_CAPACITY = 32;
    private static final int PRE_TELEPORT_CAPACITY = 16;
    private static final int PROJECTILE_CAPACITY = 32;
    private static final int DEAD_DEDUP_CAPACITY = 64;
    private static final int DEAD_DEDUP_MASK = DEAD_DEDUP_CAPACITY - 1;

    private static final byte PROJ_KIND_STANDARD = 0;
    private static final byte PROJ_KIND_FIREBALL = 1;
    private static final byte PROJ_KIND_TNT = 2;

    private static final long OWNED_PROJECTILE_TTL_NANOS = 10_000_000_000L;
    private static final long ACTIVE_PROJECTILE_POS_TTL_NANOS = 750_000_000L;
    private static final long DEAD_DEDUP_TTL_NANOS = 10_000_000_000L;
    private static final long RESPAWN_REATTACK_MIN_AGE_NANOS = 2_000_000_000L;
    private static final long DOUBLE_TELEPORT_KEEP_ORIGIN_NANOS = 300_000_000L;
    private static final double PLAYER_THROW_RADIUS_SQ = 9.0D;
    private static final double EXPLOSION_OWNERSHIP_RADIUS_SQ = 28.0D;
    private static final double HIT_ATTRIBUTION_RADIUS_SQ = 2.2D * 2.2D;
    private static final double FIREBALL_ATTRIBUTION_RADIUS_SQ = 5.0D * 5.0D;
    private static final double TNT_ATTRIBUTION_RADIUS_SQ = 6.0D * 6.0D;
    private static final double FAR_RESPAWN_JUMP_SQ = 64.0D;
    private static final float DEG_TO_RAD = 0.017453292F;

    private final KillEffectInstance[] instances = new KillEffectInstance[MAX_EFFECTS];
    private final boolean[] instanceSuppressRender = new boolean[MAX_EFFECTS];
    private int activeCount;

    private final boolean[] attackSlotUsed = new boolean[ATTACK_CAPACITY];
    private final int[] attackedEntityIds = new int[ATTACK_CAPACITY];
    private final long[] attackedTimestamps = new long[ATTACK_CAPACITY];

    private final boolean[] globalHurtUsed = new boolean[GLOBAL_HURT_CAPACITY];
    private final int[] globalHurtEntityIds = new int[GLOBAL_HURT_CAPACITY];
    private final long[] globalHurtTimestamps = new long[GLOBAL_HURT_CAPACITY];

    private final boolean[] snapUsed = new boolean[SNAPSHOT_CAPACITY];
    private final int[] snapEntityIds = new int[SNAPSHOT_CAPACITY];
    private final String[] snapNames = new String[SNAPSHOT_CAPACITY];
    private final EntityLivingBase[] snapEntities = new EntityLivingBase[SNAPSHOT_CAPACITY];
    private final double[] snapX = new double[SNAPSHOT_CAPACITY];
    private final double[] snapY = new double[SNAPSHOT_CAPACITY];
    private final double[] snapZ = new double[SNAPSHOT_CAPACITY];
    private final float[] snapYaw = new float[SNAPSHOT_CAPACITY];
    private final float[] snapWidth = new float[SNAPSHOT_CAPACITY];
    private final float[] snapHeight = new float[SNAPSHOT_CAPACITY];
    private final long[] snapTimestamps = new long[SNAPSHOT_CAPACITY];

    private final boolean[] preTpUsed = new boolean[PRE_TELEPORT_CAPACITY];
    private final int[] preTpEntityIds = new int[PRE_TELEPORT_CAPACITY];
    private final double[] preTpX = new double[PRE_TELEPORT_CAPACITY];
    private final double[] preTpY = new double[PRE_TELEPORT_CAPACITY];
    private final double[] preTpZ = new double[PRE_TELEPORT_CAPACITY];
    private final float[] preTpYaw = new float[PRE_TELEPORT_CAPACITY];
    private final long[] preTpTimestamps = new long[PRE_TELEPORT_CAPACITY];

    private final boolean[] ownedProjUsed = new boolean[PROJECTILE_CAPACITY];
    private final int[] ownedProjIds = new int[PROJECTILE_CAPACITY];
    private final long[] ownedProjLastSeenNanos = new long[PROJECTILE_CAPACITY];

    private final boolean[] projPosUsed = new boolean[PROJECTILE_CAPACITY];
    private final int[] projPosEntityIds = new int[PROJECTILE_CAPACITY];
    private final byte[] projPosKind = new byte[PROJECTILE_CAPACITY];
    private final double[] projPosX = new double[PROJECTILE_CAPACITY];
    private final double[] projPosY = new double[PROJECTILE_CAPACITY];
    private final double[] projPosZ = new double[PROJECTILE_CAPACITY];
    private final double[] projPosRadiusSq = new double[PROJECTILE_CAPACITY];
    private final long[] projPosUpdatedNanos = new long[PROJECTILE_CAPACITY];

    private final boolean[] deadSlotUsed = new boolean[DEAD_DEDUP_CAPACITY];
    private final int[] deadEntityIds = new int[DEAD_DEDUP_CAPACITY];
    private final int[] deadIdentityHashes = new int[DEAD_DEDUP_CAPACITY];
    private final boolean[] deadSuppressRender = new boolean[DEAD_DEDUP_CAPACITY];
    private final long[] deadTimestamps = new long[DEAD_DEDUP_CAPACITY];
    private int deadCursor;

    /**
     * Initializes pre-allocated effect instances and primitive tracking arrays.
     */
    public KillEffectsTracker() {
        for (int i = 0; i < MAX_EFFECTS; i++) {
            this.instances[i] = new KillEffectInstance();
        }
    }

    /**
     * Returns the fixed-size pool array of {@link KillEffectInstance} entries.
     *
     * @return pre-allocated instance array of length {@link #MAX_EFFECTS}
     */
    public KillEffectInstance[] getInstances() {
        return this.instances;
    }

    /**
     * Returns whether at least one kill effect instance is currently active.
     *
     * @return true if any slot is active
     */
    public boolean hasActiveEffects() {
        return this.activeCount > 0;
    }

    /**
     * Expires completed effect instances against the current monotonic timestamp.
     *
     * @param nowNanos current timestamp from {@link System#nanoTime()}
     */
    public void updateActive(long nowNanos) {
        int count = 0;
        for (int i = 0; i < MAX_EFFECTS; i++) {
            KillEffectInstance inst = this.instances[i];
            if (!inst.active) {
                continue;
            }
            if (inst.durationNanos <= 0L || (nowNanos - inst.startNanos) >= inst.durationNanos) {
                inst.clear();
                this.instanceSuppressRender[i] = false;
            } else {
                count++;
            }
        }
        this.activeCount = count;
    }

    /**
     * Clears all active effect instances, attack history, global hurt cache, snapshots,
     * pre-teleport cache, projectile caches, and death deduplication state.
     */
    public void clear() {
        for (int i = 0; i < MAX_EFFECTS; i++) {
            this.instances[i].clear();
            this.instanceSuppressRender[i] = false;
        }
        this.activeCount = 0;
        for (int i = 0; i < ATTACK_CAPACITY; i++) {
            this.attackSlotUsed[i] = false;
            this.attackedEntityIds[i] = 0;
            this.attackedTimestamps[i] = 0L;
        }
        for (int i = 0; i < GLOBAL_HURT_CAPACITY; i++) {
            this.globalHurtUsed[i] = false;
            this.globalHurtEntityIds[i] = 0;
            this.globalHurtTimestamps[i] = 0L;
        }
        for (int i = 0; i < SNAPSHOT_CAPACITY; i++) {
            this.snapUsed[i] = false;
            this.snapEntityIds[i] = 0;
            this.snapNames[i] = null;
            this.snapEntities[i] = null;
            this.snapX[i] = 0.0D;
            this.snapY[i] = 0.0D;
            this.snapZ[i] = 0.0D;
            this.snapYaw[i] = 0.0F;
            this.snapWidth[i] = 0.0F;
            this.snapHeight[i] = 0.0F;
            this.snapTimestamps[i] = 0L;
        }
        for (int i = 0; i < PRE_TELEPORT_CAPACITY; i++) {
            this.preTpUsed[i] = false;
            this.preTpEntityIds[i] = 0;
            this.preTpX[i] = 0.0D;
            this.preTpY[i] = 0.0D;
            this.preTpZ[i] = 0.0D;
            this.preTpYaw[i] = 0.0F;
            this.preTpTimestamps[i] = 0L;
        }
        for (int i = 0; i < PROJECTILE_CAPACITY; i++) {
            this.ownedProjUsed[i] = false;
            this.ownedProjIds[i] = 0;
            this.ownedProjLastSeenNanos[i] = 0L;
            this.projPosUsed[i] = false;
            this.projPosEntityIds[i] = 0;
            this.projPosKind[i] = PROJ_KIND_STANDARD;
            this.projPosX[i] = 0.0D;
            this.projPosY[i] = 0.0D;
            this.projPosZ[i] = 0.0D;
            this.projPosRadiusSq[i] = 0.0D;
            this.projPosUpdatedNanos[i] = 0L;
        }
        for (int i = 0; i < DEAD_DEDUP_CAPACITY; i++) {
            this.deadSlotUsed[i] = false;
            this.deadEntityIds[i] = 0;
            this.deadIdentityHashes[i] = 0;
            this.deadSuppressRender[i] = false;
            this.deadTimestamps[i] = 0L;
        }
        this.deadCursor = 0;
    }

    /**
     * Acquires a cleared slot from the pool, evicting the oldest active slot when full.
     *
     * @param nowNanos current monotonic timestamp
     * @return cleared slot with {@code active} initialized to {@code true}
     */
    public KillEffectInstance acquireSlot(long nowNanos) {
        return acquireSlot(nowNanos, true);
    }

    /**
     * Acquires a cleared slot from the pool with configurable vanilla corpse suppression.
     *
     * @param nowNanos current monotonic timestamp
     * @param suppressOriginalRender whether vanilla entity rendering should be suppressed for this instance
     * @return cleared slot with {@code active} initialized to {@code true}
     */
    public KillEffectInstance acquireSlot(long nowNanos, boolean suppressOriginalRender) {
        updateActive(nowNanos);

        KillEffectInstance chosen = null;
        int chosenIndex = -1;
        int oldestIndex = 0;
        long oldestStartNanos = Long.MAX_VALUE;
        for (int i = 0; i < MAX_EFFECTS; i++) {
            KillEffectInstance candidate = this.instances[i];
            if (!candidate.active) {
                chosen = candidate;
                chosenIndex = i;
                break;
            }
            if (i == 0 || (candidate.startNanos - oldestStartNanos) < 0L) {
                oldestStartNanos = candidate.startNanos;
                oldestIndex = i;
            }
        }

        boolean reusedInactive = chosen != null;
        if (!reusedInactive) {
            chosenIndex = oldestIndex;
            chosen = this.instances[chosenIndex];
        }
        chosen.clear();
        chosen.active = true;
        this.instanceSuppressRender[chosenIndex] = suppressOriginalRender;
        if (reusedInactive) {
            this.activeCount++;
        }
        return chosen;
    }

    /**
     * Returns whether vanilla entity rendering should be suppressed for the given entity.
     *
     * @param entity candidate entity being rendered
     * @param nowNanos current monotonic timestamp
     * @return true if this exact entity instance is a suppressed captured corpse
     */
    public boolean shouldSuppressRender(EntityLivingBase entity, long nowNanos) {
        if (entity == null) {
            return false;
        }
        int entityId = entity.getEntityId();
        int identityHash = System.identityHashCode(entity);
        for (int i = 0; i < DEAD_DEDUP_CAPACITY; i++) {
            if (!this.deadSlotUsed[i]) {
                continue;
            }
            long age = nowNanos - this.deadTimestamps[i];
            if (age < 0L || age > DEAD_DEDUP_TTL_NANOS) {
                this.deadSlotUsed[i] = false;
                continue;
            }
            if (this.deadEntityIds[i] == entityId && this.deadIdentityHashes[i] == identityHash) {
                return this.deadSuppressRender[i];
            }
        }
        if (this.activeCount > 0) {
            for (int i = 0; i < MAX_EFFECTS; i++) {
                KillEffectInstance inst = this.instances[i];
                if (inst.active && inst.entity == entity && this.instanceSuppressRender[i]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Records a client-side hit against the specified entity id.
     *
     * @param entityId victim entity id
     * @param nowNanos current monotonic timestamp
     */
    public void recordAttack(int entityId, long nowNanos) {
        int slot = findUpsertSlot(
            this.attackSlotUsed,
            this.attackedEntityIds,
            this.attackedTimestamps,
            entityId,
            nowNanos,
            ATTACK_WINDOW_NANOS
        );
        this.attackSlotUsed[slot] = true;
        this.attackedEntityIds[slot] = entityId;
        this.attackedTimestamps[slot] = nowNanos;
    }

    /**
     * Checks whether the specified entity id was attacked by the local player within {@code windowNanos}.
     *
     * @param entityId candidate entity id
     * @param nowNanos current monotonic timestamp
     * @param windowNanos maximum accepted age in nanoseconds
     * @return true if a hit timestamp exists within the window
     */
    public boolean wasRecentlyAttacked(int entityId, long nowNanos, long windowNanos) {
        return containsValidId(
            this.attackSlotUsed,
            this.attackedEntityIds,
            this.attackedTimestamps,
            entityId,
            nowNanos,
            windowNanos,
            ATTACK_WINDOW_NANOS
        );
    }

    /**
     * Records a fresh hurt tick on any eligible living entity for non-own-kill attribution.
     *
     * @param entityId hurt entity id
     * @param nowNanos current monotonic timestamp
     */
    public void recordGlobalHurt(int entityId, long nowNanos) {
        int slot = findUpsertSlot(
            this.globalHurtUsed,
            this.globalHurtEntityIds,
            this.globalHurtTimestamps,
            entityId,
            nowNanos,
            GLOBAL_HURT_WINDOW_NANOS
        );
        this.globalHurtUsed[slot] = true;
        this.globalHurtEntityIds[slot] = entityId;
        this.globalHurtTimestamps[slot] = nowNanos;
    }

    /**
     * Checks whether any recent hurt tick was observed for the specified entity id within {@code windowNanos}.
     *
     * @param entityId candidate entity id
     * @param nowNanos current monotonic timestamp
     * @param windowNanos maximum accepted age in nanoseconds
     * @return true if a recent hurt timestamp exists within the window
     */
    public boolean wasRecentlyHurtGlobally(int entityId, long nowNanos, long windowNanos) {
        return containsValidId(
            this.globalHurtUsed,
            this.globalHurtEntityIds,
            this.globalHurtTimestamps,
            entityId,
            nowNanos,
            windowNanos,
            GLOBAL_HURT_WINDOW_NANOS
        );
    }

    /**
     * Records or refreshes a recent-target snapshot using the current monotonic timestamp.
     *
     * @param entity target player entity
     */
    public void trackRecentPlayerSnapshot(EntityLivingBase entity) {
        trackRecentPlayerSnapshot(entity, System.nanoTime(), true);
    }

    /**
     * Records or updates a recent-target snapshot for chat-based final kill fallback.
     *
     * @param entity target entity
     * @param nowNanos current monotonic timestamp
     * @param refreshAttributionTime true on direct combat events; false for live position tracking
     */
    public void trackRecentPlayerSnapshot(
        EntityLivingBase entity,
        long nowNanos,
        boolean refreshAttributionTime
    ) {
        if (!(entity instanceof EntityPlayer) || !entity.isEntityAlive() || entity.getHealth() <= 0.0F) {
            return;
        }
        String rawName = entity.getName();
        if (rawName == null || rawName.isEmpty()) {
            return;
        }
        String cleanName = rawName.indexOf('\u00a7') >= 0
            ? EnumChatFormatting.getTextWithoutFormattingCodes(rawName)
            : rawName;
        if (cleanName == null || cleanName.isEmpty()) {
            return;
        }

        int entityId = entity.getEntityId();
        if (!refreshAttributionTime && hasPreTeleportPos(entityId, nowNanos)) {
            // Do not overwrite combat coordinates with a post-death spawn teleport.
            return;
        }

        int matchSlot = -1;
        int freeSlot = -1;
        int oldestSlot = 0;
        long maxAge = -1L;
        for (int i = 0; i < SNAPSHOT_CAPACITY; i++) {
            if (!this.snapUsed[i]) {
                if (freeSlot == -1) {
                    freeSlot = i;
                }
                continue;
            }
            long age = nowNanos - this.snapTimestamps[i];
            if (age < 0L || age > SNAPSHOT_TTL_NANOS) {
                clearSnapshotSlot(i);
                if (freeSlot == -1) {
                    freeSlot = i;
                }
                continue;
            }
            if (this.snapEntityIds[i] == entityId || cleanName.equals(this.snapNames[i])) {
                matchSlot = i;
                break;
            }
            if (age > maxAge) {
                maxAge = age;
                oldestSlot = i;
            }
        }

        if (!refreshAttributionTime) {
            if (matchSlot == -1) {
                return;
            }
            if (entity.ticksExisted <= 3) {
                double dx = entity.posX - this.snapX[matchSlot];
                double dy = entity.posY - this.snapY[matchSlot];
                double dz = entity.posZ - this.snapZ[matchSlot];
                if ((dx * dx + dy * dy + dz * dz) >= FAR_RESPAWN_JUMP_SQ) {
                    // Preserve the pre-respawn combat position when S0C spawns a fresh entity far away.
                    return;
                }
            }
        }

        int slot = matchSlot != -1 ? matchSlot : (freeSlot != -1 ? freeSlot : oldestSlot);
        this.snapUsed[slot] = true;
        this.snapEntityIds[slot] = entityId;
        this.snapNames[slot] = cleanName;
        this.snapEntities[slot] = entity;
        this.snapX[slot] = entity.posX;
        this.snapY[slot] = entity.posY;
        this.snapZ[slot] = entity.posZ;
        this.snapYaw[slot] = entity.rotationYaw;
        this.snapWidth[slot] = entity.width;
        this.snapHeight[slot] = entity.height;
        if (refreshAttributionTime || matchSlot == -1) {
            this.snapTimestamps[slot] = nowNanos;
        }
    }

    /**
     * Returns the fixed capacity of the recent-target snapshot table.
     *
     * @return snapshot capacity (32)
     */
    public int getSnapshotCapacity() {
        return SNAPSHOT_CAPACITY;
    }

    /**
     * Checks whether the given snapshot slot is populated and unexpired, clearing expired entity refs.
     *
     * @param slot snapshot slot index
     * @param nowNanos current monotonic timestamp
     * @return true if the snapshot entry is valid
     */
    public boolean isSnapshotValid(int slot, long nowNanos) {
        if (slot < 0 || slot >= SNAPSHOT_CAPACITY || !this.snapUsed[slot]) {
            return false;
        }
        long age = nowNanos - this.snapTimestamps[slot];
        if (age < 0L || age > SNAPSHOT_TTL_NANOS) {
            clearSnapshotSlot(slot);
            return false;
        }
        return true;
    }

    /**
     * Returns the clean player name stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return clean player name or null
     */
    public String getSnapshotName(int slot) {
        return this.snapNames[slot];
    }

    /**
     * Returns the entity network id stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return entity network id
     */
    public int getSnapshotEntityId(int slot) {
        return this.snapEntityIds[slot];
    }

    /**
     * Returns the cached entity reference stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return entity reference or null
     */
    public EntityLivingBase getSnapshotEntity(int slot) {
        return this.snapEntities[slot];
    }

    /**
     * Returns the cached X coordinate stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return X coordinate
     */
    public double getSnapshotX(int slot) {
        return this.snapX[slot];
    }

    /**
     * Returns the cached Y coordinate stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return Y coordinate
     */
    public double getSnapshotY(int slot) {
        return this.snapY[slot];
    }

    /**
     * Returns the cached Z coordinate stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return Z coordinate
     */
    public double getSnapshotZ(int slot) {
        return this.snapZ[slot];
    }

    /**
     * Returns the cached yaw stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return yaw angle in degrees
     */
    public float getSnapshotYaw(int slot) {
        return this.snapYaw[slot];
    }

    /**
     * Returns the cached bounding width stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return entity width
     */
    public float getSnapshotWidth(int slot) {
        return this.snapWidth[slot];
    }

    /**
     * Returns the cached bounding height stored in the given snapshot slot.
     *
     * @param slot snapshot slot index
     * @return entity height
     */
    public float getSnapshotHeight(int slot) {
        return this.snapHeight[slot];
    }

    /**
     * Records an entity's pre-teleport combat coordinates before a large server teleport.
     *
     * @param entityId teleported entity id
     * @param x pre-teleport X coordinate
     * @param y pre-teleport Y coordinate
     * @param z pre-teleport Z coordinate
     * @param yaw pre-teleport yaw angle
     * @param nowNanos current monotonic timestamp
     */
    public void recordPreTeleport(
        int entityId,
        double x,
        double y,
        double z,
        float yaw,
        long nowNanos
    ) {
        int slot = findUpsertSlot(
            this.preTpUsed,
            this.preTpEntityIds,
            this.preTpTimestamps,
            entityId,
            nowNanos,
            PRE_TELEPORT_TTL_NANOS
        );
        if (this.preTpUsed[slot] && this.preTpEntityIds[slot] == entityId) {
            long age = nowNanos - this.preTpTimestamps[slot];
            if (age >= 0L && age <= DOUBLE_TELEPORT_KEEP_ORIGIN_NANOS) {
                // Keep the original combat coordinates across rapid multi-step lobby teleports.
                return;
            }
        }
        this.preTpUsed[slot] = true;
        this.preTpEntityIds[slot] = entityId;
        this.preTpX[slot] = x;
        this.preTpY[slot] = y;
        this.preTpZ[slot] = z;
        this.preTpYaw[slot] = yaw;
        this.preTpTimestamps[slot] = nowNanos;
    }

    /**
     * Checks whether valid pre-teleport coordinates are cached for the specified entity id.
     *
     * @param entityId entity network id
     * @param nowNanos current monotonic timestamp
     * @return true if an unexpired pre-teleport entry exists
     */
    public boolean hasPreTeleportPos(int entityId, long nowNanos) {
        return findPreTeleportSlot(entityId, nowNanos) != -1;
    }

    /**
     * Finds the active pre-teleport cache slot for the specified entity id.
     *
     * @param entityId entity network id
     * @param nowNanos current monotonic timestamp
     * @return slot index in {@code [0, 15]}, or {@code -1} if absent/expired
     */
    public int findPreTeleportSlot(int entityId, long nowNanos) {
        for (int i = 0; i < PRE_TELEPORT_CAPACITY; i++) {
            if (!this.preTpUsed[i]) {
                continue;
            }
            long age = nowNanos - this.preTpTimestamps[i];
            if (age < 0L || age > PRE_TELEPORT_TTL_NANOS) {
                this.preTpUsed[i] = false;
                continue;
            }
            if (this.preTpEntityIds[i] == entityId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the pre-teleport X coordinate at {@code slot}.
     *
     * @param slot pre-teleport slot index
     * @return X coordinate
     */
    public double getPreTeleportX(int slot) {
        return this.preTpX[slot];
    }

    /**
     * Returns the pre-teleport Y coordinate at {@code slot}.
     *
     * @param slot pre-teleport slot index
     * @return Y coordinate
     */
    public double getPreTeleportY(int slot) {
        return this.preTpY[slot];
    }

    /**
     * Returns the pre-teleport Z coordinate at {@code slot}.
     *
     * @param slot pre-teleport slot index
     * @return Z coordinate
     */
    public double getPreTeleportZ(int slot) {
        return this.preTpZ[slot];
    }

    /**
     * Returns the pre-teleport yaw angle at {@code slot}.
     *
     * @param slot pre-teleport slot index
     * @return yaw angle in degrees
     */
    public float getPreTeleportYaw(int slot) {
        return this.preTpYaw[slot];
    }

    /**
     * Consumes and clears the pre-teleport cache slot after spawning a kill effect.
     *
     * @param slot pre-teleport slot index
     */
    public void consumePreTeleportSlot(int slot) {
        if (slot >= 0 && slot < PRE_TELEPORT_CAPACITY) {
            this.preTpUsed[slot] = false;
        }
    }

    /**
     * Scans player-owned projectiles, fireballs, and TNT in the client world, records global hurt ticks,
     * and updates live target snapshots.
     *
     * @param world active client world
     * @param player local client player
     * @param nowNanos current monotonic timestamp
     */
    public void tickProjectiles(WorldClient world, EntityPlayerSP player, long nowNanos) {
        List<Entity> entities = world.loadedEntityList;
        int entityCount = entities.size();
        boolean hasRecentProjectile = false;

        for (int i = 0; i < PROJECTILE_CAPACITY; i++) {
            if (!this.projPosUsed[i]) {
                continue;
            }
            long age = nowNanos - this.projPosUpdatedNanos[i];
            if (age < 0L || age > ACTIVE_PROJECTILE_POS_TTL_NANOS) {
                this.projPosUsed[i] = false;
            } else {
                hasRecentProjectile = true;
            }
        }

        double eyeX = player.posX;
        double eyeY = player.posY + (double) player.getEyeHeight();
        double eyeZ = player.posZ;

        // First pass: register player-owned flying projectiles, fireballs, TNT, and direct rod catches.
        for (int i = 0; i < entityCount; i++) {
            Entity entity = entities.get(i);
            if (entity == null || entity.isDead) {
                continue;
            }
            if (entity instanceof EntityArrow) {
                EntityArrow arrow = (EntityArrow) entity;
                if (arrow.shootingEntity == player && isArrowActive(arrow)) {
                    recordProjectilePos(
                        arrow.getEntityId(),
                        PROJ_KIND_STANDARD,
                        arrow.posX,
                        arrow.posY,
                        arrow.posZ,
                        HIT_ATTRIBUTION_RADIUS_SQ,
                        nowNanos
                    );
                    hasRecentProjectile = true;
                }
            } else if (entity instanceof EntityFishHook) {
                EntityFishHook hook = (EntityFishHook) entity;
                if (hook.angler == player || hook == player.fishEntity) {
                    if (hook.caughtEntity instanceof EntityLivingBase) {
                        EntityLivingBase caught = (EntityLivingBase) hook.caughtEntity;
                        if (caught != player && !(caught instanceof EntityArmorStand)) {
                            recordAttack(caught.getEntityId(), nowNanos);
                            trackRecentPlayerSnapshot(caught, nowNanos, true);
                        }
                    }
                    if (hook.caughtEntity != null || hook.ticksExisted <= 5 || isEntityMoving(hook)) {
                        recordProjectilePos(
                            hook.getEntityId(),
                            PROJ_KIND_STANDARD,
                            hook.posX,
                            hook.posY,
                            hook.posZ,
                            HIT_ATTRIBUTION_RADIUS_SQ,
                            nowNanos
                        );
                        hasRecentProjectile = true;
                    }
                }
            } else if (entity instanceof EntityThrowable) {
                EntityThrowable throwable = (EntityThrowable) entity;
                if (isPlayerOwnedThrowable(throwable, player, eyeX, eyeY, eyeZ, nowNanos)) {
                    markOwnedProjectile(throwable.getEntityId(), nowNanos);
                    recordProjectilePos(
                        throwable.getEntityId(),
                        PROJ_KIND_STANDARD,
                        throwable.posX,
                        throwable.posY,
                        throwable.posZ,
                        HIT_ATTRIBUTION_RADIUS_SQ,
                        nowNanos
                    );
                    hasRecentProjectile = true;
                }
            } else if (entity instanceof EntityFireball) {
                EntityFireball fireball = (EntityFireball) entity;
                if (isPlayerOwnedFireball(fireball, player, eyeX, eyeY, eyeZ, nowNanos)) {
                    markOwnedProjectile(fireball.getEntityId(), nowNanos);
                    recordProjectilePos(
                        fireball.getEntityId(),
                        PROJ_KIND_FIREBALL,
                        fireball.posX,
                        fireball.posY,
                        fireball.posZ,
                        FIREBALL_ATTRIBUTION_RADIUS_SQ,
                        nowNanos
                    );
                    hasRecentProjectile = true;
                }
            } else if (entity instanceof EntityTNTPrimed) {
                EntityTNTPrimed tnt = (EntityTNTPrimed) entity;
                if (isPlayerOwnedTnt(tnt, player, nowNanos)) {
                    markOwnedProjectile(tnt.getEntityId(), nowNanos);
                    recordProjectilePos(
                        tnt.getEntityId(),
                        PROJ_KIND_TNT,
                        tnt.posX,
                        tnt.posY + 0.5D,
                        tnt.posZ,
                        TNT_ATTRIBUTION_RADIUS_SQ,
                        nowNanos
                    );
                    hasRecentProjectile = true;
                }
            }
        }

        // Second pass: record global hurt ticks, attribute projectile/explosion hits, and refresh snapshots.
        for (int i = 0; i < entityCount; i++) {
            Entity entity = entities.get(i);
            if (!(entity instanceof EntityLivingBase) || entity == player || entity instanceof EntityArmorStand) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            int livingId = living.getEntityId();
            if (living.hurtTime >= 8) {
                recordGlobalHurt(livingId, nowNanos);
            }
            if (hasRecentProjectile && living.hurtTime >= 8 && isNearCachedProjectile(living, world, nowNanos)) {
                recordAttack(livingId, nowNanos);
                trackRecentPlayerSnapshot(living, nowNanos, true);
            } else if (living instanceof EntityPlayer && hasActiveSnapshotForEntity(livingId, nowNanos)) {
                trackRecentPlayerSnapshot(living, nowNanos, false);
            }
        }
    }

    /**
     * Checks cached and active player-owned projectiles, fireballs, and TNT near a dying entity.
     *
     * @param victim dying entity
     * @param world active client world
     * @param player local client player
     * @param nowNanos current monotonic timestamp
     * @return true if a player-owned projectile or explosive is within attribution range
     */
    public boolean isRecentPlayerProjectileNear(
        EntityLivingBase victim,
        WorldClient world,
        EntityPlayerSP player,
        long nowNanos
    ) {
        if (victim == null) {
            return false;
        }
        if (isNearCachedProjectile(victim, world, nowNanos)) {
            return true;
        }
        if (world == null || player == null) {
            return false;
        }

        double eyeX = player.posX;
        double eyeY = player.posY + (double) player.getEyeHeight();
        double eyeZ = player.posZ;
        List<Entity> entities = world.loadedEntityList;
        for (int i = 0, size = entities.size(); i < size; i++) {
            Entity entity = entities.get(i);
            if (entity == null) {
                continue;
            }
            if (entity instanceof EntityArrow) {
                EntityArrow arrow = (EntityArrow) entity;
                if (arrow.shootingEntity == player && isArrowActive(arrow)
                    && isPointNearEntity(arrow.posX, arrow.posY, arrow.posZ, victim, HIT_ATTRIBUTION_RADIUS_SQ)) {
                    return true;
                }
            } else if (entity instanceof EntityFishHook) {
                EntityFishHook hook = (EntityFishHook) entity;
                if ((hook.angler == player || hook == player.fishEntity)
                    && (hook.caughtEntity == victim
                    || isPointNearEntity(hook.posX, hook.posY, hook.posZ, victim, HIT_ATTRIBUTION_RADIUS_SQ))) {
                    return true;
                }
            } else if (entity instanceof EntityThrowable) {
                EntityThrowable throwable = (EntityThrowable) entity;
                if (isPlayerOwnedThrowable(throwable, player, eyeX, eyeY, eyeZ, nowNanos)
                    && isPointNearEntity(
                        throwable.posX,
                        throwable.posY,
                        throwable.posZ,
                        victim,
                        HIT_ATTRIBUTION_RADIUS_SQ
                    )) {
                    return true;
                }
            } else if (entity instanceof EntityFireball) {
                EntityFireball fireball = (EntityFireball) entity;
                if (isPlayerOwnedFireball(fireball, player, eyeX, eyeY, eyeZ, nowNanos)
                    && isPointNearEntity(
                        fireball.posX,
                        fireball.posY,
                        fireball.posZ,
                        victim,
                        FIREBALL_ATTRIBUTION_RADIUS_SQ
                    )) {
                    return true;
                }
            } else if (entity instanceof EntityTNTPrimed) {
                EntityTNTPrimed tnt = (EntityTNTPrimed) entity;
                if (isPlayerOwnedTnt(tnt, player, nowNanos)
                    && (tnt.isDead || tnt.fuse <= 5
                        || victim.hurtTime > 0 || victim.getHealth() <= 0.0F || victim.deathTime > 0)
                    && isPointNearEntity(
                        tnt.posX,
                        tnt.posY + 0.5D,
                        tnt.posZ,
                        victim,
                        TNT_ATTRIBUTION_RADIUS_SQ
                    )) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Records a dead entity in the 64-entry deduplication ring buffer and marks its vanilla render suppressed.
     *
     * @param entityId entity network id
     * @param identityHash {@link System#identityHashCode(Object)} of the captured entity instance
     * @param nowNanos current monotonic timestamp
     */
    public void markDead(int entityId, int identityHash, long nowNanos) {
        markDead(entityId, identityHash, nowNanos, true);
    }

    /**
     * Records a dead entity in the 64-entry deduplication ring buffer with explicit render-suppression control.
     *
     * @param entityId entity network id
     * @param identityHash {@link System#identityHashCode(Object)} of the captured entity instance
     * @param nowNanos current monotonic timestamp
     * @param suppressRender true to suppress vanilla rendering for the captured corpse instance
     */
    public void markDead(int entityId, int identityHash, long nowNanos, boolean suppressRender) {
        for (int i = 0; i < DEAD_DEDUP_CAPACITY; i++) {
            if (this.deadSlotUsed[i]
                && this.deadEntityIds[i] == entityId
                && this.deadIdentityHashes[i] == identityHash) {
                this.deadTimestamps[i] = nowNanos;
                this.deadSuppressRender[i] = this.deadSuppressRender[i] || suppressRender;
                return;
            }
        }
        int slot = this.deadCursor;
        this.deadSlotUsed[slot] = true;
        this.deadEntityIds[slot] = entityId;
        this.deadIdentityHashes[slot] = identityHash;
        this.deadSuppressRender[slot] = suppressRender;
        this.deadTimestamps[slot] = nowNanos;
        this.deadCursor = (slot + 1) & DEAD_DEDUP_MASK;
    }

    /**
     * Clears a previous death dedup entry when a player entity respawns alive and is re-attacked later.
     *
     * @param entityId entity network id
     * @param identityHash {@link System#identityHashCode(Object)} of the entity instance
     * @param nowNanos current monotonic timestamp
     */
    public void clearExpiredRespawnDead(int entityId, int identityHash, long nowNanos) {
        for (int i = 0; i < DEAD_DEDUP_CAPACITY; i++) {
            if (!this.deadSlotUsed[i]) {
                continue;
            }
            if (this.deadEntityIds[i] == entityId && this.deadIdentityHashes[i] == identityHash) {
                long age = nowNanos - this.deadTimestamps[i];
                if (age < 0L || age >= RESPAWN_REATTACK_MIN_AGE_NANOS) {
                    this.deadSlotUsed[i] = false;
                    this.deadSuppressRender[i] = false;
                }
                return;
            }
        }
    }

    /**
     * Checks whether the entity instance was already handled recently in the 64-entry deduplication buffer.
     *
     * @param entityId entity network id
     * @param identityHash {@link System#identityHashCode(Object)} of the entity instance
     * @param nowNanos current monotonic timestamp
     * @return true if already captured within the deduplication window
     */
    public boolean isRecentlyDead(int entityId, int identityHash, long nowNanos) {
        for (int i = 0; i < DEAD_DEDUP_CAPACITY; i++) {
            if (!this.deadSlotUsed[i]) {
                continue;
            }
            long age = nowNanos - this.deadTimestamps[i];
            if (age < 0L || age > DEAD_DEDUP_TTL_NANOS) {
                this.deadSlotUsed[i] = false;
                this.deadSuppressRender[i] = false;
                continue;
            }
            if (this.deadEntityIds[i] == entityId && this.deadIdentityHashes[i] == identityHash) {
                return true;
            }
        }
        return false;
    }

    private void clearSnapshotSlot(int slot) {
        this.snapUsed[slot] = false;
        this.snapEntityIds[slot] = 0;
        this.snapNames[slot] = null;
        this.snapEntities[slot] = null;
        this.snapTimestamps[slot] = 0L;
    }

    private boolean hasActiveSnapshotForEntity(int entityId, long nowNanos) {
        for (int i = 0; i < SNAPSHOT_CAPACITY; i++) {
            if (!this.snapUsed[i]) {
                continue;
            }
            long age = nowNanos - this.snapTimestamps[i];
            if (age < 0L || age > SNAPSHOT_TTL_NANOS) {
                clearSnapshotSlot(i);
                continue;
            }
            if (this.snapEntityIds[i] == entityId) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerOwnedThrowable(
        EntityThrowable throwable,
        EntityPlayerSP player,
        double eyeX,
        double eyeY,
        double eyeZ,
        long nowNanos
    ) {
        EntityLivingBase thrower = throwable.getThrower();
        if (thrower != null) {
            return thrower == player;
        }
        if (isOwnedProjectile(throwable.getEntityId(), nowNanos)) {
            return true;
        }
        // Multiplayer S0EPacketSpawnObject leaves thrower null; claim throwables spawning right at the player.
        if (throwable.ticksExisted <= 3) {
            double dx = throwable.posX - eyeX;
            double dy = throwable.posY - eyeY;
            double dz = throwable.posZ - eyeZ;
            return (dx * dx + dy * dy + dz * dz) <= PLAYER_THROW_RADIUS_SQ;
        }
        return false;
    }

    private boolean isPlayerOwnedFireball(
        EntityFireball fireball,
        EntityPlayerSP player,
        double eyeX,
        double eyeY,
        double eyeZ,
        long nowNanos
    ) {
        if (fireball.shootingEntity != null) {
            return fireball.shootingEntity == player;
        }
        if (isOwnedProjectile(fireball.getEntityId(), nowNanos)) {
            return true;
        }
        double dx = fireball.posX - eyeX;
        double dy = fireball.posY - eyeY;
        double dz = fireball.posZ - eyeZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > EXPLOSION_OWNERSHIP_RADIUS_SQ) {
            return false;
        }
        if (fireball.ticksExisted <= 4) {
            return true;
        }
        if (player.isSwingInProgress) {
            double vx = fireball.posX - fireball.prevPosX;
            double vy = fireball.posY - fireball.prevPosY;
            double vz = fireball.posZ - fireball.prevPosZ;
            if ((vx * vx + vy * vy + vz * vz) < 1.0E-6D) {
                vx = fireball.motionX;
                vy = fireball.motionY;
                vz = fireball.motionZ;
            }
            // Claim an existing fireball only when swung at and moving away in front of the player.
            if ((dx * vx + dy * vy + dz * vz) > 0.0D) {
                float yawRad = -player.rotationYaw * DEG_TO_RAD - (float) Math.PI;
                float pitchRad = -player.rotationPitch * DEG_TO_RAD;
                float cosYaw = MathHelper.cos(yawRad);
                float sinYaw = MathHelper.sin(yawRad);
                float negCosPitch = -MathHelper.cos(pitchRad);
                float sinPitch = MathHelper.sin(pitchRad);
                double lookX = (double) (sinYaw * negCosPitch);
                double lookY = (double) sinPitch;
                double lookZ = (double) (cosYaw * negCosPitch);
                if ((lookX * vx + lookY * vy + lookZ * vz) > 0.0D
                    && (lookX * dx + lookY * dy + lookZ * dz) > 0.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPlayerOwnedTnt(EntityTNTPrimed tnt, EntityPlayerSP player, long nowNanos) {
        EntityLivingBase placer = tnt.getTntPlacedBy();
        if (placer != null) {
            return placer == player;
        }
        if (isOwnedProjectile(tnt.getEntityId(), nowNanos)) {
            return true;
        }
        if (tnt.ticksExisted <= 4) {
            double centerY = player.posY + (double) player.getEyeHeight() * 0.5D;
            double dx = tnt.posX - player.posX;
            double dy = (tnt.posY + 0.5D) - centerY;
            double dz = tnt.posZ - player.posZ;
            return (dx * dx + dy * dy + dz * dz) <= EXPLOSION_OWNERSHIP_RADIUS_SQ;
        }
        return false;
    }

    private void markOwnedProjectile(int entityId, long nowNanos) {
        int slot = findUpsertSlot(
            this.ownedProjUsed,
            this.ownedProjIds,
            this.ownedProjLastSeenNanos,
            entityId,
            nowNanos,
            OWNED_PROJECTILE_TTL_NANOS
        );
        this.ownedProjUsed[slot] = true;
        this.ownedProjIds[slot] = entityId;
        this.ownedProjLastSeenNanos[slot] = nowNanos;
    }

    private boolean isOwnedProjectile(int entityId, long nowNanos) {
        return containsValidId(
            this.ownedProjUsed,
            this.ownedProjIds,
            this.ownedProjLastSeenNanos,
            entityId,
            nowNanos,
            OWNED_PROJECTILE_TTL_NANOS,
            OWNED_PROJECTILE_TTL_NANOS
        );
    }

    private void recordProjectilePos(
        int entityId,
        byte kind,
        double x,
        double y,
        double z,
        double radiusSq,
        long nowNanos
    ) {
        int slot = findUpsertSlot(
            this.projPosUsed,
            this.projPosEntityIds,
            this.projPosUpdatedNanos,
            entityId,
            nowNanos,
            ACTIVE_PROJECTILE_POS_TTL_NANOS
        );
        this.projPosUsed[slot] = true;
        this.projPosEntityIds[slot] = entityId;
        this.projPosKind[slot] = kind;
        this.projPosX[slot] = x;
        this.projPosY[slot] = y;
        this.projPosZ[slot] = z;
        this.projPosRadiusSq[slot] = radiusSq;
        this.projPosUpdatedNanos[slot] = nowNanos;
    }

    private boolean isNearCachedProjectile(EntityLivingBase victim, WorldClient world, long nowNanos) {
        for (int i = 0; i < PROJECTILE_CAPACITY; i++) {
            if (!this.projPosUsed[i]) {
                continue;
            }
            long age = nowNanos - this.projPosUpdatedNanos[i];
            if (age < 0L || age > ACTIVE_PROJECTILE_POS_TTL_NANOS) {
                this.projPosUsed[i] = false;
                continue;
            }
            if (this.projPosKind[i] == PROJ_KIND_TNT && world != null) {
                Entity raw = world.getEntityByID(this.projPosEntityIds[i]);
                if (raw instanceof EntityTNTPrimed
                    && !raw.isDead
                    && ((EntityTNTPrimed) raw).fuse > 5
                    && victim.hurtTime == 0
                    && victim.getHealth() > 0.0F
                    && victim.deathTime <= 0) {
                    continue;
                }
            }
            if (isPointNearEntity(
                this.projPosX[i],
                this.projPosY[i],
                this.projPosZ[i],
                victim,
                this.projPosRadiusSq[i]
            )) {
                return true;
            }
        }
        return false;
    }

    private static int findUpsertSlot(
        boolean[] used,
        int[] ids,
        long[] timestamps,
        int entityId,
        long nowNanos,
        long ttlNanos
    ) {
        int freeIndex = -1;
        int oldestIndex = 0;
        long maxAge = -1L;

        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                if (freeIndex == -1) {
                    freeIndex = i;
                }
                continue;
            }
            if (ids[i] == entityId) {
                return i;
            }
            long age = nowNanos - timestamps[i];
            if (age < 0L || age > ttlNanos) {
                used[i] = false;
                if (freeIndex == -1) {
                    freeIndex = i;
                }
            } else if (age > maxAge) {
                maxAge = age;
                oldestIndex = i;
            }
        }

        return freeIndex != -1 ? freeIndex : oldestIndex;
    }

    private static boolean containsValidId(
        boolean[] used,
        int[] ids,
        long[] timestamps,
        int entityId,
        long nowNanos,
        long windowNanos,
        long maxTtlNanos
    ) {
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                continue;
            }
            long age = nowNanos - timestamps[i];
            if (age < 0L || age > maxTtlNanos) {
                used[i] = false;
                continue;
            }
            if (ids[i] == entityId && age <= windowNanos) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArrowActive(EntityArrow arrow) {
        return arrow.ticksExisted <= 3 || isEntityMoving(arrow);
    }

    private static boolean isEntityMoving(Entity entity) {
        double dx = entity.posX - entity.prevPosX;
        double dy = entity.posY - entity.prevPosY;
        double dz = entity.posZ - entity.prevPosZ;
        double moveSq = dx * dx + dy * dy + dz * dz;
        double velSq = entity.motionX * entity.motionX
            + entity.motionY * entity.motionY
            + entity.motionZ * entity.motionZ;
        return moveSq > 1.0E-6D || velSq > 1.0E-4D;
    }

    private static boolean isPointNearEntity(
        double px,
        double py,
        double pz,
        EntityLivingBase entity,
        double maxDistSq
    ) {
        double halfW = (double) entity.width * 0.5D;
        double minX = entity.posX - halfW;
        double maxX = entity.posX + halfW;
        double minY = entity.posY;
        double maxY = entity.posY + (double) entity.height;
        double minZ = entity.posZ - halfW;
        double maxZ = entity.posZ + halfW;

        double cx = px < minX ? minX : (px > maxX ? maxX : px);
        double cy = py < minY ? minY : (py > maxY ? maxY : py);
        double cz = pz < minZ ? minZ : (pz > maxZ ? maxZ : pz);

        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        return (dx * dx + dy * dy + dz * dz) <= maxDistSq;
    }
}
