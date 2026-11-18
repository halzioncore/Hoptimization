package com.hoptimization.modules.visual.esp;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Zero-allocation incremental bed scanner cache.
 *
 * <p>Scans loaded chunks around the local player every 15 ticks (or upon movement &gt; 8 blocks,
 * world change, range change, or module enable) using a single reusable
 * {@link BlockPos.MutableBlockPos}, merging head and foot blocks into unified bed entries.</p>
 */
public final class BedStructureCache {
    public static final int MAX_BEDS = 128;
    public static final int HORIZONTAL_CAP = 64;
    public static final int VERTICAL_RADIUS = 24;
    public static final int SCAN_INTERVAL_TICKS = 15;
    public static final double RESCAN_MOVE_DIST_SQ = 64.0D;

    /**
     * Horizontal facing indices matching MC 1.8.9 {@code EnumFacing.getHorizontalIndex()}
     * and {@code BlockBed} metadata bits {@code 0..1} (foot -&gt; head direction).
     */
    public static final byte DIR_SOUTH = 0;
    public static final byte DIR_WEST = 1;
    public static final byte DIR_NORTH = 2;
    public static final byte DIR_EAST = 3;

    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
    private final int[] headX = new int[MAX_BEDS];
    private final int[] headY = new int[MAX_BEDS];
    private final int[] headZ = new int[MAX_BEDS];
    private final byte[] facingDir = new byte[MAX_BEDS];
    private final boolean[] pairedFoot = new boolean[MAX_BEDS];

    private int bedCount;
    private int ticksSinceScan;
    private boolean dirty = true;
    private boolean hasScanned;
    private World lastWorld;
    private double lastScanX;
    private double lastScanY;
    private double lastScanZ;
    private int lastScanRadius = -1;

    /**
     * Marks the cache dirty so the next client tick performs an immediate rescan.
     */
    public void invalidate() {
        this.dirty = true;
    }

    /**
     * Clears all cached bed entries and resets scan tracking state.
     */
    public void clear() {
        this.bedCount = 0;
        this.ticksSinceScan = 0;
        this.dirty = true;
        this.hasScanned = false;
        this.lastWorld = null;
        this.lastScanRadius = -1;
    }

    /**
     * Incrementally refreshes cached beds around the player when due.
     *
     * @param world            active client world
     * @param player           local player
     * @param horizontalRadius configured horizontal scan radius (clamped to {@code 1..64})
     */
    public void update(World world, EntityPlayer player, int horizontalRadius) {
        if (world == null || player == null) {
            clear();
            return;
        }
        int radius = clampRadius(horizontalRadius);
        double dx = player.posX - this.lastScanX;
        double dy = player.posY - this.lastScanY;
        double dz = player.posZ - this.lastScanZ;
        double movedDistSq = dx * dx + dy * dy + dz * dz;

        boolean shouldScan = this.dirty
            || !this.hasScanned
            || world != this.lastWorld
            || radius != this.lastScanRadius
            || this.ticksSinceScan >= SCAN_INTERVAL_TICKS
            || movedDistSq > RESCAN_MOVE_DIST_SQ;

        if (!shouldScan) {
            this.ticksSinceScan++;
            return;
        }

        rescan(world, player, radius);
    }

    public int getBedCount() {
        return this.bedCount;
    }

    public int getHeadX(int index) {
        return this.headX[index];
    }

    public int getHeadY(int index) {
        return this.headY[index];
    }

    public int getHeadZ(int index) {
        return this.headZ[index];
    }

    public byte getFacingDir(int index) {
        return this.facingDir[index];
    }

    public boolean isPairedFoot(int index) {
        return this.pairedFoot[index];
    }

    /**
     * Returns the X step from foot block to head block for the given horizontal facing index.
     *
     * @param dir {@code 0=SOUTH, 1=WEST, 2=NORTH, 3=EAST}
     * @return {@code -1}, {@code 0}, or {@code +1}
     */
    public static int getFacingOffsetX(int dir) {
        if (dir == DIR_WEST) {
            return -1;
        }
        if (dir == DIR_EAST) {
            return 1;
        }
        return 0;
    }

    /**
     * Returns the Z step from foot block to head block for the given horizontal facing index.
     *
     * @param dir {@code 0=SOUTH, 1=WEST, 2=NORTH, 3=EAST}
     * @return {@code -1}, {@code 0}, or {@code +1}
     */
    public static int getFacingOffsetZ(int dir) {
        if (dir == DIR_SOUTH) {
            return 1;
        }
        if (dir == DIR_NORTH) {
            return -1;
        }
        return 0;
    }

    private void rescan(World world, EntityPlayer player, int radius) {
        this.bedCount = 0;
        this.ticksSinceScan = 0;
        this.dirty = false;
        this.hasScanned = true;
        this.lastWorld = world;
        this.lastScanX = player.posX;
        this.lastScanY = player.posY;
        this.lastScanZ = player.posZ;
        this.lastScanRadius = radius;

        int px = MathHelper.floor_double(player.posX);
        int py = MathHelper.floor_double(player.posY);
        int pz = MathHelper.floor_double(player.posZ);

        int minX = px - radius;
        int maxX = px + radius;
        int minY = Math.max(0, py - VERTICAL_RADIUS);
        int maxY = Math.min(255, py + VERTICAL_RADIUS);
        int minZ = pz - radius;
        int maxZ = pz + radius;
        if (minY > maxY) {
            return;
        }

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        int centerChunkX = px >> 4;
        int centerChunkZ = pz >> 4;
        int maxRing = Math.max(
            Math.max(centerChunkX - minChunkX, maxChunkX - centerChunkX),
            Math.max(centerChunkZ - minChunkZ, maxChunkZ - centerChunkZ)
        );

        // Iterate chunk rings outward from player so nearby beds are always prioritized within MAX_BEDS.
        for (int ring = 0; ring <= maxRing && this.bedCount < MAX_BEDS; ring++) {
            if (ring == 0) {
                scanChunk(world, centerChunkX, centerChunkZ, minX, maxX, minY, maxY, minZ, maxZ);
                continue;
            }
            int northCz = centerChunkZ - ring;
            int southCz = centerChunkZ + ring;
            int westCx = centerChunkX - ring;
            int eastCx = centerChunkX + ring;

            if (northCz >= minChunkZ && northCz <= maxChunkZ) {
                int cxStart = Math.max(minChunkX, westCx);
                int cxEnd = Math.min(maxChunkX, eastCx);
                for (int cx = cxStart; cx <= cxEnd && this.bedCount < MAX_BEDS; cx++) {
                    scanChunk(world, cx, northCz, minX, maxX, minY, maxY, minZ, maxZ);
                }
            }
            if (southCz >= minChunkZ && southCz <= maxChunkZ) {
                int cxStart = Math.max(minChunkX, westCx);
                int cxEnd = Math.min(maxChunkX, eastCx);
                for (int cx = cxStart; cx <= cxEnd && this.bedCount < MAX_BEDS; cx++) {
                    scanChunk(world, cx, southCz, minX, maxX, minY, maxY, minZ, maxZ);
                }
            }
            int czInnerStart = Math.max(minChunkZ, northCz + 1);
            int czInnerEnd = Math.min(maxChunkZ, southCz - 1);
            if (westCx >= minChunkX && westCx <= maxChunkX) {
                for (int cz = czInnerStart; cz <= czInnerEnd && this.bedCount < MAX_BEDS; cz++) {
                    scanChunk(world, westCx, cz, minX, maxX, minY, maxY, minZ, maxZ);
                }
            }
            if (eastCx >= minChunkX && eastCx <= maxChunkX) {
                for (int cz = czInnerStart; cz <= czInnerEnd && this.bedCount < MAX_BEDS; cz++) {
                    scanChunk(world, eastCx, cz, minX, maxX, minY, maxY, minZ, maxZ);
                }
            }
        }
    }

    private void scanChunk(
        World world,
        int cx,
        int cz,
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ
    ) {
        if (this.bedCount >= MAX_BEDS) {
            return;
        }
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        if (sections == null) {
            return;
        }

        int cMinX = Math.max(minX, cx << 4);
        int cMaxX = Math.min(maxX, (cx << 4) + 15);
        int cMinZ = Math.max(minZ, cz << 4);
        int cMaxZ = Math.min(maxZ, (cz << 4) + 15);
        int minSec = minY >> 4;
        int maxSec = Math.min(sections.length - 1, maxY >> 4);
        Block bedBlock = Blocks.bed;

        for (int sec = minSec; sec <= maxSec && this.bedCount < MAX_BEDS; sec++) {
            ExtendedBlockStorage storage = sections[sec];
            if (storage == null || storage.isEmpty()) {
                continue;
            }
            int secMinY = Math.max(minY, sec << 4);
            int secMaxY = Math.min(maxY, (sec << 4) + 15);

            for (int y = secMinY; y <= secMaxY && this.bedCount < MAX_BEDS; y++) {
                int localY = y & 15;
                for (int z = cMinZ; z <= cMaxZ && this.bedCount < MAX_BEDS; z++) {
                    int localZ = z & 15;
                    for (int x = cMinX; x <= cMaxX && this.bedCount < MAX_BEDS; x++) {
                        this.scanPos.set(x, y, z);
                        IBlockState state = storage.get(this.scanPos.getX() & 15, localY, localZ);
                        if (state.getBlock() != bedBlock) {
                            continue;
                        }
                        handleBedBlock(world, bedBlock, state, x, y, z, minX, maxX, minZ, maxZ);
                    }
                }
            }
        }
    }

    private void handleBedBlock(
        World world,
        Block bedBlock,
        IBlockState state,
        int x,
        int y,
        int z,
        int minX,
        int maxX,
        int minZ,
        int maxZ
    ) {
        int meta = bedBlock.getMetaFromState(state);
        boolean isHead = (meta & 8) != 0;
        byte dir = (byte) (meta & 3);
        int fx = getFacingOffsetX(dir);
        int fz = getFacingOffsetZ(dir);

        if (isHead) {
            // In MC 1.8.9 BlockBed, facing points from FOOT to HEAD, so foot is at head.offset(facing.getOpposite()).
            int footX = x - fx;
            int footZ = z - fz;
            boolean hasFoot = isLoadedBedPart(world, bedBlock, footX, y, footZ, false);
            addBedIfAbsent(x, y, z, dir, hasFoot);
            return;
        }

        // Foot block: normally skipped when its partner head lies inside the scan bounds.
        // If the head lies outside scan bounds or is missing/unloaded, handle safely without missing or duplicating.
        int partnerHeadX = x + fx;
        int partnerHeadZ = z + fz;
        boolean headInBounds = partnerHeadX >= minX && partnerHeadX <= maxX
            && partnerHeadZ >= minZ && partnerHeadZ <= maxZ;

        Chunk headChunk = world.getChunkFromChunkCoords(partnerHeadX >> 4, partnerHeadZ >> 4);
        if (headChunk != null && !headChunk.isEmpty()) {
            this.scanPos.set(partnerHeadX, y, partnerHeadZ);
            IBlockState headState = headChunk.getBlockState(this.scanPos);
            if (headState.getBlock() == bedBlock) {
                int headMeta = bedBlock.getMetaFromState(headState);
                if ((headMeta & 8) != 0) {
                    if (!headInBounds) {
                        addBedIfAbsent(partnerHeadX, y, partnerHeadZ, (byte) (headMeta & 3), true);
                    }
                    return;
                }
            }
        }

        // Orphan foot block or edge-of-loaded-world half-bed fallback.
        addBedIfAbsent(x, y, z, dir, false);
    }

    private boolean isLoadedBedPart(World world, Block bedBlock, int x, int y, int z, boolean expectHead) {
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        this.scanPos.set(x, y, z);
        IBlockState state = chunk.getBlockState(this.scanPos);
        if (state.getBlock() != bedBlock) {
            return false;
        }
        boolean isHead = (bedBlock.getMetaFromState(state) & 8) != 0;
        return isHead == expectHead;
    }

    private void addBedIfAbsent(int hx, int hy, int hz, byte dir, boolean hasFoot) {
        if (this.bedCount >= MAX_BEDS) {
            return;
        }
        for (int i = 0; i < this.bedCount; i++) {
            if (this.headX[i] == hx && this.headY[i] == hy && this.headZ[i] == hz) {
                return;
            }
        }
        int idx = this.bedCount++;
        this.headX[idx] = hx;
        this.headY[idx] = hy;
        this.headZ[idx] = hz;
        this.facingDir[idx] = dir;
        this.pairedFoot[idx] = hasFoot;
    }

    private static int clampRadius(int radius) {
        if (radius < 1) {
            return 1;
        }
        if (radius > HORIZONTAL_CAP) {
            return HORIZONTAL_CAP;
        }
        return radius;
    }
}
