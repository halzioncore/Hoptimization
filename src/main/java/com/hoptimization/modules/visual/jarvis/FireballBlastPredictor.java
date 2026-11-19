package com.hoptimization.modules.visual.jarvis;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityWitherSkull;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * Zero-allocation deterministic trajectory and two-tier blast block predictor for active fireballs.
 */
public final class FireballBlastPredictor {
    public static final String PRESET_BEDWARS = "bedwars";
    public static final String PRESET_VANILLA = "vanilla";
    public static final int MAX_FIREBALLS = 16;
    public static final int MAX_BLAST_BLOCKS = 256;
    public static final byte TIER_POSSIBLE = 1;
    public static final byte TIER_GUARANTEED = 2;

    public static final int RAY_COUNT = 728;
    public static final float[] RAY_DX = new float[RAY_COUNT];
    public static final float[] RAY_DY = new float[RAY_COUNT];
    public static final float[] RAY_DZ = new float[RAY_COUNT];

    private static final int GRID_SIZE = 12;
    private static final int MAX_SIM_TICKS = 80;
    private static final double MOTION_EPS_SQ = 1.0E-8D;
    private static final double STEP_EPS_SQ = 1.0E-12D;
    private static final double ENTITY_BOX_EXPAND = 0.3D;
    private static final double IMPACT_SURFACE_NUDGE = 0.001D;
    private static final float RAY_STEP_DIST = 0.3F;
    private static final float AIR_DECAY_PER_STEP = 0.22500001F;
    private static final float CORE_STRENGTH_FACTOR = 0.72F;
    private static final float OUTER_STRENGTH_FACTOR = 1.25F;
    private static final float IMMUNE_BLAST_RESISTANCE = 1000.0F;
    private static final int DEDUP_TABLE_SIZE = 1024;
    private static final int DEDUP_TABLE_MASK = DEDUP_TABLE_SIZE - 1;

    static {
        int index = 0;
        for (int j = 0; j < GRID_SIZE; j++) {
            for (int k = 0; k < GRID_SIZE; k++) {
                for (int l = 0; l < GRID_SIZE; l++) {
                    if (j != 0 && j != GRID_SIZE - 1
                        && k != 0 && k != GRID_SIZE - 1
                        && l != 0 && l != GRID_SIZE - 1) {
                        continue;
                    }
                    double dx = (double) ((float) j / 11.0F * 2.0F - 1.0F);
                    double dy = (double) ((float) k / 11.0F * 2.0F - 1.0F);
                    double dz = (double) ((float) l / 11.0F * 2.0F - 1.0F);
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    RAY_DX[index] = (float) (dx / len);
                    RAY_DY[index] = (float) (dy / len);
                    RAY_DZ[index] = (float) (dz / len);
                    index++;
                }
            }
        }
    }

    private final FireballSlot[] slots = new FireballSlot[MAX_FIREBALLS];
    private final BlockPos.MutableBlockPos rayPos = new BlockPos.MutableBlockPos();
    private final boolean[] dedupOccupied = new boolean[DEDUP_TABLE_SIZE];
    private final long[] dedupKeys = new long[DEDUP_TABLE_SIZE];
    private final int[] dedupIndices = new int[DEDUP_TABLE_SIZE];

    private int activeCount;
    private World lastWorld;

    /**
     * Creates a fireball predictor with pre-allocated fireball slots.
     */
    public FireballBlastPredictor() {
        for (int i = 0; i < MAX_FIREBALLS; i++) {
            this.slots[i] = new FireballSlot();
        }
    }

    /**
     * Scans active fireballs in the world, predicts impact points, and computes deterministic blast footprints.
     *
     * @param world         active client world
     * @param enabled       whether fireball prediction is enabled
     * @param bedwarsPreset true for BedWars resistance rules, false for vanilla resistance rules
     * @param power         fireball explosion power (typically {@code 1.0F})
     */
    public void update(World world, boolean enabled, boolean bedwarsPreset, float power) {
        if (!enabled || world == null) {
            clear();
            return;
        }
        if (world != this.lastWorld) {
            clear();
            this.lastWorld = world;
        }

        int prevActiveCount = this.activeCount;
        int nextCount = 0;
        List<Entity> entities = world.loadedEntityList;

        for (int i = 0, size = entities.size(); i < size && nextCount < MAX_FIREBALLS; i++) {
            Entity entity = entities.get(i);
            if (!(entity instanceof EntityFireball) || entity instanceof EntityWitherSkull || entity.isDead) {
                continue;
            }
            EntityFireball fireball = (EntityFireball) entity;
            FireballSlot slot = this.slots[nextCount++];
            updateSlot(world, fireball, slot, bedwarsPreset, power);
        }

        for (int i = nextCount; i < prevActiveCount; i++) {
            this.slots[i].reset();
        }
        this.activeCount = nextCount;
    }

    /**
     * Returns the number of active tracked fireball slots.
     *
     * @return active slot count ({@code 0..MAX_FIREBALLS})
     */
    public int getActiveCount() {
        return this.activeCount;
    }

    /**
     * Returns the fireball prediction slot at the given index.
     *
     * @param index slot index ({@code 0..MAX_FIREBALLS - 1})
     * @return fireball slot
     */
    public FireballSlot getSlot(int index) {
        return this.slots[index];
    }

    /**
     * Returns whether any active fireball currently has a predicted impact within the simulation horizon.
     *
     * @return true if at least one active slot has {@link FireballSlot#hasImpact()}
     */
    public boolean hasActiveImpacts() {
        for (int i = 0; i < this.activeCount; i++) {
            if (this.slots[i].hasImpact()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clears all active fireball slots and resets world tracking state.
     */
    public void clear() {
        for (int i = 0; i < MAX_FIREBALLS; i++) {
            this.slots[i].reset();
        }
        this.activeCount = 0;
        this.lastWorld = null;
    }

    /**
     * Returns whether the given block is immune to fireballs under BedWars rules.
     *
     * <p>BedWars-destructible blocks (wool, planks, ladder, sponge, web, wooden fences/gates/slabs/stairs,
     * hay bales, leaves) plus air/fire/TNT return {@code false}; Blast-Proof Glass, beds, obsidian,
     * end stone, clay, chests, bedrock, liquids, and protected map blocks return {@code true}.</p>
     *
     * @param block block to classify
     * @return true if immune to BedWars fireball destruction
     */
    public static boolean isBedWarsFireballImmune(Block block) {
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return false;
        }
        if (block == Blocks.fire || block == Blocks.tnt) {
            return false;
        }
        return !isBedWarsDestructible(block);
    }

    /**
     * Returns the effective blast resistance used for explosion ray attenuation.
     *
     * @param block         target block
     * @param bedwarsPreset true for BedWars rules, false for vanilla rules
     * @return effective blast resistance (&gt;= 1000.0F for BedWars-immune blocks)
     */
    public static float getEffectiveBlastResistance(Block block, boolean bedwarsPreset) {
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return 0.0F;
        }
        float rawResistance = block.getExplosionResistance(null);
        if (Float.isNaN(rawResistance) || rawResistance < 0.0F) {
            rawResistance = 0.0F;
        }
        if (!bedwarsPreset) {
            return rawResistance;
        }
        if (isBedWarsFireballImmune(block)) {
            return rawResistance >= IMMUNE_BLAST_RESISTANCE ? rawResistance : IMMUNE_BLAST_RESISTANCE;
        }
        return rawResistance;
    }

    private void updateSlot(
        World world,
        EntityFireball fireball,
        FireballSlot slot,
        boolean bedwarsPreset,
        float power
    ) {
        int entityId = fireball.getEntityId();
        boolean sameEntity = slot.active && slot.entityId == entityId;
        if (!sameEntity) {
            slot.cacheValid = false;
        }

        slot.active = true;
        slot.entityId = entityId;
        slot.curX = fireball.posX;
        slot.curY = fireball.posY;
        slot.curZ = fireball.posZ;
        slot.prevX = fireball.prevPosX;
        slot.prevY = fireball.prevPosY;
        slot.prevZ = fireball.prevPosZ;

        simulateTrajectory(world, fireball, slot);

        if (!slot.hasImpact) {
            slot.blockCount = 0;
            slot.cacheValid = false;
            return;
        }

        boolean cacheHit = slot.cacheValid
            && slot.lastEntityId == entityId
            && slot.lastHitBx == slot.hitBlockX
            && slot.lastHitBy == slot.hitBlockY
            && slot.lastHitBz == slot.hitBlockZ
            && Float.compare(slot.lastPower, power) == 0
            && slot.lastBedwarsPreset == bedwarsPreset;

        if (!cacheHit) {
            computeBlastFootprint(world, slot, bedwarsPreset, power);
            slot.lastEntityId = entityId;
            slot.lastHitBx = slot.hitBlockX;
            slot.lastHitBy = slot.hitBlockY;
            slot.lastHitBz = slot.hitBlockZ;
            slot.lastPower = power;
            slot.lastBedwarsPreset = bedwarsPreset;
            slot.cacheValid = true;
        }
    }

    private void simulateTrajectory(World world, EntityFireball fireball, FireballSlot slot) {
        double px = fireball.posX;
        double py = fireball.posY;
        double pz = fireball.posZ;

        double vx = fireball.motionX;
        double vy = fireball.motionY;
        double vz = fireball.motionZ;
        if (vx * vx + vy * vy + vz * vz < MOTION_EPS_SQ) {
            double fx = fireball.posX - fireball.prevPosX;
            double fy = fireball.posY - fireball.prevPosY;
            double fz = fireball.posZ - fireball.prevPosZ;
            double fallbackSq = fx * fx + fy * fy + fz * fz;
            if (fallbackSq < 100.0D) {
                vx = fx;
                vy = fy;
                vz = fz;
            }
        }

        double ax = fireball.accelerationX;
        double ay = fireball.accelerationY;
        double az = fireball.accelerationZ;
        boolean hasAccel = (ax * ax + ay * ay + az * az) > MOTION_EPS_SQ;

        if (!hasAccel && (vx * vx + vy * vy + vz * vz) < STEP_EPS_SQ) {
            slot.hasImpact = false;
            slot.etaSeconds = 0.0F;
            return;
        }

        List<Entity> entities = world.loadedEntityList;
        for (int tick = 0; tick < MAX_SIM_TICKS; tick++) {
            double nx = px + vx;
            double ny = py + vy;
            double nz = pz + vz;
            double stepDx = nx - px;
            double stepDy = ny - py;
            double stepDz = nz - pz;
            double stepLenSq = stepDx * stepDx + stepDy * stepDy + stepDz * stepDz;

            if (stepLenSq > STEP_EPS_SQ) {
                // Vec3 is immutable in MC 1.8.9; world.rayTraceBlocks requires Vec3 endpoints per step.
                MovingObjectPosition blockHit = world.rayTraceBlocks(
                    new Vec3(px, py, pz),
                    new Vec3(nx, ny, nz),
                    false,
                    true,
                    false
                );

                double bestX = nx;
                double bestY = ny;
                double bestZ = nz;
                double stepScale = 1.0D;
                boolean hitIsBlock = false;
                int hitBx = 0;
                int hitBy = 0;
                int hitBz = 0;
                EnumFacing hitSide = null;

                if (blockHit != null
                    && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && blockHit.hitVec != null) {
                    bestX = blockHit.hitVec.xCoord;
                    bestY = blockHit.hitVec.yCoord;
                    bestZ = blockHit.hitVec.zCoord;
                    double bdx = bestX - px;
                    double bdy = bestY - py;
                    double bdz = bestZ - pz;
                    stepScale = Math.sqrt((bdx * bdx + bdy * bdy + bdz * bdz) / stepLenSq);
                    if (stepScale > 1.0D) {
                        stepScale = 1.0D;
                    }
                    hitIsBlock = true;
                    BlockPos hitPos = blockHit.getBlockPos();
                    if (hitPos != null) {
                        hitBx = hitPos.getX();
                        hitBy = hitPos.getY();
                        hitBz = hitPos.getZ();
                    } else {
                        hitBx = MathHelper.floor_double(bestX);
                        hitBy = MathHelper.floor_double(bestY);
                        hitBz = MathHelper.floor_double(bestZ);
                    }
                    hitSide = blockHit.sideHit;
                }

                double segDx = bestX - px;
                double segDy = bestY - py;
                double segDz = bestZ - pz;
                double segMinX = Math.min(px, bestX) - ENTITY_BOX_EXPAND;
                double segMaxX = Math.max(px, bestX) + ENTITY_BOX_EXPAND;
                double segMinY = Math.min(py, bestY) - ENTITY_BOX_EXPAND;
                double segMaxY = Math.max(py, bestY) + ENTITY_BOX_EXPAND;
                double segMinZ = Math.min(pz, bestZ) - ENTITY_BOX_EXPAND;
                double segMaxZ = Math.max(pz, bestZ) + ENTITY_BOX_EXPAND;

                double bestEntityT = 2.0D;
                boolean hitEntity = false;

                for (int i = 0, size = entities.size(); i < size; i++) {
                    Entity candidate = entities.get(i);
                    if (candidate == null || candidate == fireball || candidate.isDead) {
                        continue;
                    }
                    if (fireball.shootingEntity != null && candidate.isEntityEqual(fireball.shootingEntity)) {
                        continue;
                    }
                    if (candidate instanceof EntityArmorStand || !candidate.canBeCollidedWith()) {
                        continue;
                    }
                    if (candidate instanceof EntityPlayer && ((EntityPlayer) candidate).isSpectator()) {
                        continue;
                    }
                    AxisAlignedBB bb = candidate.getEntityBoundingBox();
                    if (bb == null) {
                        continue;
                    }
                    if (bb.maxX < segMinX || bb.minX > segMaxX
                        || bb.maxY < segMinY || bb.minY > segMaxY
                        || bb.maxZ < segMinZ || bb.minZ > segMaxZ) {
                        continue;
                    }
                    double t = intersectRayAabb(
                        px, py, pz, segDx, segDy, segDz,
                        bb.minX - ENTITY_BOX_EXPAND,
                        bb.minY - ENTITY_BOX_EXPAND,
                        bb.minZ - ENTITY_BOX_EXPAND,
                        bb.maxX + ENTITY_BOX_EXPAND,
                        bb.maxY + ENTITY_BOX_EXPAND,
                        bb.maxZ + ENTITY_BOX_EXPAND
                    );
                    if (t >= 0.0D && t < bestEntityT) {
                        bestEntityT = t;
                        hitEntity = true;
                    }
                }

                if (hitEntity) {
                    double hx = px + segDx * bestEntityT;
                    double hy = py + segDy * bestEntityT;
                    double hz = pz + segDz * bestEntityT;
                    slot.hasImpact = true;
                    slot.hitX = hx;
                    slot.hitY = hy;
                    slot.hitZ = hz;
                    slot.hitBlockX = MathHelper.floor_double(hx);
                    slot.hitBlockY = MathHelper.floor_double(hy);
                    slot.hitBlockZ = MathHelper.floor_double(hz);
                    slot.etaSeconds = (float) (((double) tick + bestEntityT * stepScale) * 0.05D);
                    return;
                }

                if (hitIsBlock) {
                    double hx = bestX;
                    double hy = bestY;
                    double hz = bestZ;
                    // Step 1mm off the hit face so exact block boundaries do not start inside immune blocks.
                    if (hitSide != null) {
                        hx += (double) hitSide.getFrontOffsetX() * IMPACT_SURFACE_NUDGE;
                        hy += (double) hitSide.getFrontOffsetY() * IMPACT_SURFACE_NUDGE;
                        hz += (double) hitSide.getFrontOffsetZ() * IMPACT_SURFACE_NUDGE;
                    }
                    slot.hasImpact = true;
                    slot.hitX = hx;
                    slot.hitY = hy;
                    slot.hitZ = hz;
                    slot.hitBlockX = hitBx;
                    slot.hitBlockY = hitBy;
                    slot.hitBlockZ = hitBz;
                    slot.etaSeconds = (float) (((double) tick + stepScale) * 0.05D);
                    return;
                }
            }

            px = nx;
            py = ny;
            pz = nz;
            if (hasAccel) {
                vx = (vx + ax) * 0.95D;
                vy = (vy + ay) * 0.95D;
                vz = (vz + az) * 0.95D;
            }
        }

        slot.hasImpact = false;
        slot.etaSeconds = 0.0F;
    }

    private void computeBlastFootprint(World world, FireballSlot slot, boolean bedwarsPreset, float power) {
        slot.blockCount = 0;
        if (Float.isNaN(power) || power <= 0.0F) {
            return;
        }
        Arrays.fill(this.dedupOccupied, false);

        // Run guaranteed core pass first so inner blocks never lose capacity to outer possible blocks.
        traceBlastRays(world, slot, bedwarsPreset, power * CORE_STRENGTH_FACTOR, TIER_GUARANTEED);
        traceBlastRays(world, slot, bedwarsPreset, power * OUTER_STRENGTH_FACTOR, TIER_POSSIBLE);
    }

    private void traceBlastRays(
        World world,
        FireballSlot slot,
        boolean bedwarsPreset,
        float initialStrength,
        byte tierValue
    ) {
        if (initialStrength <= 0.0F) {
            return;
        }
        double originX = slot.hitX;
        double originY = slot.hitY;
        double originZ = slot.hitZ;
        double stepDist = (double) RAY_STEP_DIST;

        for (int r = 0; r < RAY_COUNT; r++) {
            double rx = originX;
            double ry = originY;
            double rz = originZ;
            double stepX = (double) RAY_DX[r] * stepDist;
            double stepY = (double) RAY_DY[r] * stepDist;
            double stepZ = (double) RAY_DZ[r] * stepDist;

            for (float f = initialStrength; f > 0.0F; f -= AIR_DECAY_PER_STEP) {
                int bx = MathHelper.floor_double(rx);
                int by = MathHelper.floor_double(ry);
                int bz = MathHelper.floor_double(rz);
                if (by >= 0 && by < 256) {
                    this.rayPos.set(bx, by, bz);
                    IBlockState state = world.getBlockState(this.rayPos);
                    Block block = state.getBlock();
                    if (block.getMaterial() != Material.air) {
                        float resist = getEffectiveBlastResistance(block, bedwarsPreset);
                        f -= (resist + RAY_STEP_DIST) * RAY_STEP_DIST;
                    }
                    if (f > 0.0F && isDestroyable(world, this.rayPos, block, bedwarsPreset)) {
                        recordBlastBlock(slot, bx, by, bz, tierValue);
                    }
                }
                rx += stepX;
                ry += stepY;
                rz += stepZ;
            }
        }
    }

    private boolean isDestroyable(World world, BlockPos pos, Block block, boolean bedwarsPreset) {
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return false;
        }
        if (block.getMaterial().isLiquid()) {
            return false;
        }
        if (bedwarsPreset) {
            return !isBedWarsFireballImmune(block);
        }
        return block.getBlockHardness(world, pos) >= 0.0F;
    }

    private void recordBlastBlock(FireballSlot slot, int bx, int by, int bz, byte tierValue) {
        long key = packBlockPos(bx, by, bz);
        int hash = mix64to32(key) & DEDUP_TABLE_MASK;

        while (this.dedupOccupied[hash]) {
            if (this.dedupKeys[hash] == key) {
                int existingIdx = this.dedupIndices[hash];
                if (tierValue > slot.tier[existingIdx]) {
                    slot.tier[existingIdx] = tierValue;
                }
                return;
            }
            hash = (hash + 1) & DEDUP_TABLE_MASK;
        }

        if (slot.blockCount >= MAX_BLAST_BLOCKS) {
            return;
        }
        int idx = slot.blockCount++;
        slot.bx[idx] = bx;
        slot.by[idx] = by;
        slot.bz[idx] = bz;
        slot.tier[idx] = tierValue;

        this.dedupOccupied[hash] = true;
        this.dedupKeys[hash] = key;
        this.dedupIndices[hash] = idx;
    }

    private static boolean isBedWarsDestructible(Block block) {
        return block == Blocks.wool
            || block == Blocks.planks
            || block == Blocks.ladder
            || block == Blocks.sponge
            || block == Blocks.web
            || block == Blocks.wooden_slab
            || block == Blocks.double_wooden_slab
            || block == Blocks.oak_stairs
            || block == Blocks.spruce_stairs
            || block == Blocks.birch_stairs
            || block == Blocks.jungle_stairs
            || block == Blocks.acacia_stairs
            || block == Blocks.dark_oak_stairs
            || block == Blocks.oak_fence
            || block == Blocks.spruce_fence
            || block == Blocks.birch_fence
            || block == Blocks.jungle_fence
            || block == Blocks.dark_oak_fence
            || block == Blocks.acacia_fence
            || block == Blocks.oak_fence_gate
            || block == Blocks.spruce_fence_gate
            || block == Blocks.birch_fence_gate
            || block == Blocks.jungle_fence_gate
            || block == Blocks.dark_oak_fence_gate
            || block == Blocks.acacia_fence_gate
            || block == Blocks.hay_block
            || block == Blocks.leaves
            || block == Blocks.leaves2;
    }

    private static double intersectRayAabb(
        double ox,
        double oy,
        double oz,
        double dx,
        double dy,
        double dz,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
    ) {
        double tMin = 0.0D;
        double tMax = 1.0D;

        if (Math.abs(dx) < 1.0E-9D) {
            if (ox < minX || ox > maxX) {
                return -1.0D;
            }
        } else {
            double invX = 1.0D / dx;
            double t1 = (minX - ox) * invX;
            double t2 = (maxX - ox) * invX;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
            }
            if (t2 < tMax) {
                tMax = t2;
            }
            if (tMin > tMax) {
                return -1.0D;
            }
        }

        if (Math.abs(dy) < 1.0E-9D) {
            if (oy < minY || oy > maxY) {
                return -1.0D;
            }
        } else {
            double invY = 1.0D / dy;
            double t1 = (minY - oy) * invY;
            double t2 = (maxY - oy) * invY;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
            }
            if (t2 < tMax) {
                tMax = t2;
            }
            if (tMin > tMax) {
                return -1.0D;
            }
        }

        if (Math.abs(dz) < 1.0E-9D) {
            if (oz < minZ || oz > maxZ) {
                return -1.0D;
            }
        } else {
            double invZ = 1.0D / dz;
            double t1 = (minZ - oz) * invZ;
            double t2 = (maxZ - oz) * invZ;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
            }
            if (t2 < tMax) {
                tMax = t2;
            }
            if (tMin > tMax) {
                return -1.0D;
            }
        }

        return tMin;
    }

    private static long packBlockPos(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) y & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL);
    }

    private static int mix64to32(long key) {
        long z = key;
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return (int) (z ^ (z >>> 33));
    }

    /**
     * Pre-allocated prediction snapshot and cached blast block array for a single fireball.
     */
    public static final class FireballSlot {
        private boolean active;
        private int entityId;
        private double curX;
        private double curY;
        private double curZ;
        private double prevX;
        private double prevY;
        private double prevZ;
        private double hitX;
        private double hitY;
        private double hitZ;
        private int hitBlockX;
        private int hitBlockY;
        private int hitBlockZ;
        private boolean hasImpact;
        private float etaSeconds;
        private int blockCount;
        private final int[] bx = new int[MAX_BLAST_BLOCKS];
        private final int[] by = new int[MAX_BLAST_BLOCKS];
        private final int[] bz = new int[MAX_BLAST_BLOCKS];
        private final byte[] tier = new byte[MAX_BLAST_BLOCKS];

        private boolean cacheValid;
        private int lastEntityId;
        private int lastHitBx;
        private int lastHitBy;
        private int lastHitBz;
        private float lastPower;
        private boolean lastBedwarsPreset;

        private void reset() {
            this.active = false;
            this.entityId = 0;
            this.curX = 0.0D;
            this.curY = 0.0D;
            this.curZ = 0.0D;
            this.prevX = 0.0D;
            this.prevY = 0.0D;
            this.prevZ = 0.0D;
            this.hitX = 0.0D;
            this.hitY = 0.0D;
            this.hitZ = 0.0D;
            this.hitBlockX = 0;
            this.hitBlockY = 0;
            this.hitBlockZ = 0;
            this.hasImpact = false;
            this.etaSeconds = 0.0F;
            this.blockCount = 0;
            this.cacheValid = false;
            this.lastEntityId = 0;
            this.lastHitBx = 0;
            this.lastHitBy = 0;
            this.lastHitBz = 0;
            this.lastPower = -1.0F;
            this.lastBedwarsPreset = false;
        }

        public boolean isActive() {
            return this.active;
        }

        public int getEntityId() {
            return this.entityId;
        }

        public double getCurX() {
            return this.curX;
        }

        public double getCurY() {
            return this.curY;
        }

        public double getCurZ() {
            return this.curZ;
        }

        public double getPrevX() {
            return this.prevX;
        }

        public double getPrevY() {
            return this.prevY;
        }

        public double getPrevZ() {
            return this.prevZ;
        }

        public double getHitX() {
            return this.hitX;
        }

        public double getHitY() {
            return this.hitY;
        }

        public double getHitZ() {
            return this.hitZ;
        }

        public int getHitBlockX() {
            return this.hitBlockX;
        }

        public int getHitBlockY() {
            return this.hitBlockY;
        }

        public int getHitBlockZ() {
            return this.hitBlockZ;
        }

        public boolean hasImpact() {
            return this.hasImpact;
        }

        public float getEtaSeconds() {
            return this.etaSeconds;
        }

        public int getBlockCount() {
            return this.blockCount;
        }

        public int getBlockX(int index) {
            return this.bx[index];
        }

        public int getBlockY(int index) {
            return this.by[index];
        }

        public int getBlockZ(int index) {
            return this.bz[index];
        }

        public byte getTier(int index) {
            return this.tier[index];
        }

        public int getLastHitBx() {
            return this.lastHitBx;
        }

        public int getLastHitBy() {
            return this.lastHitBy;
        }

        public int getLastHitBz() {
            return this.lastHitBz;
        }

        public float getLastPower() {
            return this.lastPower;
        }

        public boolean isLastBedwarsPreset() {
            return this.lastBedwarsPreset;
        }
    }
}
