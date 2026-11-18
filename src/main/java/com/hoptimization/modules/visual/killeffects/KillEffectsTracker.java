package com.hoptimization.modules.visual.killeffects;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityThrowable;

import java.util.List;

/**
 * Manages the fixed-capacity pool of active {@link KillEffectInstance} slots, recent player attack
 * attribution (melee and projectiles), and dead-entity deduplication without hot-path allocations.
 */
public final class KillEffectsTracker {
    public static final int MAX_EFFECTS = 16;
    public static final long ATTACK_WINDOW_NANOS = 5_000_000_000L;
    public static final long DESTROY_HURT_WINDOW_NANOS = 1_500_000_000L;

    private static final int ATTACK_CAPACITY = 32;
    private static final int PROJECTILE_CAPACITY = 32;
    private static final int DEAD_DEDUP_CAPACITY = 64;
    private static final int DEAD_DEDUP_MASK = DEAD_DEDUP_CAPACITY - 1;

    private static final long OWNED_PROJECTILE_TTL_NANOS = 10_000_000_000L;
    private static final long ACTIVE_PROJECTILE_POS_TTL_NANOS = 500_000_000L;
    private static final long DEAD_DEDUP_TTL_NANOS = 10_000_000_000L;
    private static final double PLAYER_THROW_RADIUS_SQ = 9.0D;
    private static final double HIT_ATTRIBUTION_RADIUS_SQ = 2.2D * 2.2D;

    private final KillEffectInstance[] instances = new KillEffectInstance[MAX_EFFECTS];
    private int activeCount;

    private final boolean[] attackSlotUsed = new boolean[ATTACK_CAPACITY];
    private final int[] attackedEntityIds = new int[ATTACK_CAPACITY];
    private final long[] attackedTimestamps = new long[ATTACK_CAPACITY];

    private final boolean[] ownedProjUsed = new boolean[PROJECTILE_CAPACITY];
    private final int[] ownedProjIds = new int[PROJECTILE_CAPACITY];
    private final long[] ownedProjLastSeenNanos = new long[PROJECTILE_CAPACITY];

    private final boolean[] projPosUsed = new boolean[PROJECTILE_CAPACITY];
    private final int[] projPosEntityIds = new int[PROJECTILE_CAPACITY];
    private final double[] projPosX = new double[PROJECTILE_CAPACITY];
    private final double[] projPosY = new double[PROJECTILE_CAPACITY];
    private final double[] projPosZ = new double[PROJECTILE_CAPACITY];
    private final long[] projPosUpdatedNanos = new long[PROJECTILE_CAPACITY];

    private final boolean[] deadSlotUsed = new boolean[DEAD_DEDUP_CAPACITY];
    private final int[] deadEntityIds = new int[DEAD_DEDUP_CAPACITY];
    private final int[] deadIdentityHashes = new int[DEAD_DEDUP_CAPACITY];
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
            } else {
                count++;
            }
        }
        this.activeCount = count;
    }

    /**
     * Clears all active effect instances, attack history, projectile caches, and death deduplication state.
     */
    public void clear() {
        for (int i = 0; i < MAX_EFFECTS; i++) {
            this.instances[i].clear();
        }
        this.activeCount = 0;
        for (int i = 0; i < ATTACK_CAPACITY; i++) {
            this.attackSlotUsed[i] = false;
            this.attackedEntityIds[i] = 0;
            this.attackedTimestamps[i] = 0L;
        }
        for (int i = 0; i < PROJECTILE_CAPACITY; i++) {
            this.ownedProjUsed[i] = false;
            this.ownedProjIds[i] = 0;
            this.ownedProjLastSeenNanos[i] = 0L;
            this.projPosUsed[i] = false;
            this.projPosEntityIds[i] = 0;
            this.projPosX[i] = 0.0D;
            this.projPosY[i] = 0.0D;
            this.projPosZ[i] = 0.0D;
            this.projPosUpdatedNanos[i] = 0L;
        }
        for (int i = 0; i < DEAD_DEDUP_CAPACITY; i++) {
            this.deadSlotUsed[i] = false;
            this.deadEntityIds[i] = 0;
            this.deadIdentityHashes[i] = 0;
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
        updateActive(nowNanos);

        KillEffectInstance chosen = null;
        int oldestIndex = 0;
        long oldestStartNanos = Long.MAX_VALUE;
        for (int i = 0; i < MAX_EFFECTS; i++) {
            KillEffectInstance candidate = this.instances[i];
            if (!candidate.active) {
                chosen = candidate;
                break;
            }
            if (i == 0 || (candidate.startNanos - oldestStartNanos) < 0L) {
                oldestStartNanos = candidate.startNanos;
                oldestIndex = i;
            }
        }

        boolean reusedInactive = chosen != null;
        if (!reusedInactive) {
            chosen = this.instances[oldestIndex];
        }
        chosen.clear();
        chosen.active = true;
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
     * @return true if this exact entity instance is owned by an active or recently captured effect
     */
    public boolean shouldSuppressRender(EntityLivingBase entity, long nowNanos) {
        if (entity == null) {
            return false;
        }
        if (this.activeCount > 0) {
            for (int i = 0; i < MAX_EFFECTS; i++) {
                KillEffectInstance inst = this.instances[i];
                if (inst.active && inst.entity == entity) {
                    return true;
                }
            }
        }
        return isRecentlyDead(entity.getEntityId(), System.identityHashCode(entity), nowNanos);
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
     * Scans player-owned projectiles in the client world and attributes newly hurt living entities.
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

        // First pass: register player-owned flying projectiles and direct rod catches.
        for (int i = 0; i < entityCount; i++) {
            Entity entity = entities.get(i);
            if (entity == null || entity.isDead) {
                continue;
            }
            if (entity instanceof EntityArrow) {
                EntityArrow arrow = (EntityArrow) entity;
                if (arrow.shootingEntity == player && isArrowActive(arrow)) {
                    recordProjectilePos(arrow.getEntityId(), arrow.posX, arrow.posY, arrow.posZ, nowNanos);
                    hasRecentProjectile = true;
                }
            } else if (entity instanceof EntityFishHook) {
                EntityFishHook hook = (EntityFishHook) entity;
                if (hook.angler == player || hook == player.fishEntity) {
                    if (hook.caughtEntity instanceof EntityLivingBase) {
                        EntityLivingBase caught = (EntityLivingBase) hook.caughtEntity;
                        if (caught != player && !(caught instanceof EntityArmorStand)) {
                            recordAttack(caught.getEntityId(), nowNanos);
                        }
                    }
                    if (hook.caughtEntity != null || hook.ticksExisted <= 5 || isEntityMoving(hook)) {
                        recordProjectilePos(hook.getEntityId(), hook.posX, hook.posY, hook.posZ, nowNanos);
                        hasRecentProjectile = true;
                    }
                }
            } else if (entity instanceof EntityThrowable) {
                EntityThrowable throwable = (EntityThrowable) entity;
                if (isPlayerOwnedThrowable(throwable, player, eyeX, eyeY, eyeZ, nowNanos)) {
                    markOwnedProjectile(throwable.getEntityId(), nowNanos);
                    recordProjectilePos(
                        throwable.getEntityId(),
                        throwable.posX,
                        throwable.posY,
                        throwable.posZ,
                        nowNanos
                    );
                    hasRecentProjectile = true;
                }
            }
        }

        if (!hasRecentProjectile) {
            return;
        }

        // Second pass: attribute living entities taking fresh hit ticks near tracked player projectiles.
        for (int i = 0; i < entityCount; i++) {
            Entity entity = entities.get(i);
            if (!(entity instanceof EntityLivingBase) || entity == player || entity instanceof EntityArmorStand) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            if (living.hurtTime >= 9 && isNearCachedProjectile(living, nowNanos)) {
                recordAttack(living.getEntityId(), nowNanos);
            }
        }
    }

    /**
     * Checks cached and active player-owned projectiles near a dying entity to catch same-tick lethal hits.
     *
     * @param victim dying entity
     * @param world active client world
     * @param player local client player
     * @param nowNanos current monotonic timestamp
     * @return true if a player-owned projectile is within attribution range
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
        if (isNearCachedProjectile(victim, nowNanos)) {
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
            }
        }
        return false;
    }

    /**
     * Records a dead entity in the 64-entry deduplication ring buffer.
     *
     * @param entityId entity network id
     * @param identityHash {@link System#identityHashCode(Object)} of the captured entity instance
     * @param nowNanos current monotonic timestamp
     */
    public void markDead(int entityId, int identityHash, long nowNanos) {
        for (int i = 0; i < DEAD_DEDUP_CAPACITY; i++) {
            if (this.deadSlotUsed[i]
                && this.deadEntityIds[i] == entityId
                && this.deadIdentityHashes[i] == identityHash) {
                this.deadTimestamps[i] = nowNanos;
                return;
            }
        }
        int slot = this.deadCursor;
        this.deadSlotUsed[slot] = true;
        this.deadEntityIds[slot] = entityId;
        this.deadIdentityHashes[slot] = identityHash;
        this.deadTimestamps[slot] = nowNanos;
        this.deadCursor = (slot + 1) & DEAD_DEDUP_MASK;
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
                continue;
            }
            if (this.deadEntityIds[i] == entityId && this.deadIdentityHashes[i] == identityHash) {
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

    private void recordProjectilePos(int entityId, double x, double y, double z, long nowNanos) {
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
        this.projPosX[slot] = x;
        this.projPosY[slot] = y;
        this.projPosZ[slot] = z;
        this.projPosUpdatedNanos[slot] = nowNanos;
    }

    private boolean isNearCachedProjectile(EntityLivingBase victim, long nowNanos) {
        for (int i = 0; i < PROJECTILE_CAPACITY; i++) {
            if (!this.projPosUsed[i]) {
                continue;
            }
            long age = nowNanos - this.projPosUpdatedNanos[i];
            if (age < 0L || age > ACTIVE_PROJECTILE_POS_TTL_NANOS) {
                this.projPosUsed[i] = false;
                continue;
            }
            if (isPointNearEntity(
                this.projPosX[i],
                this.projPosY[i],
                this.projPosZ[i],
                victim,
                HIT_ATTRIBUTION_RADIUS_SQ
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
