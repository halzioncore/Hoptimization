package com.hoptimization.modules.visual.jarvis;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * Zero-allocation BedWars road/bridge void pit detector.
 *
 * <p>Identifies 1- to 3-block road/platform gaps (and 1-block opposite-diagonal bridge gaps)
 * that open all the way down to {@code y = 0}, while ignoring open side void beside bridges
 * and shallow non-void steps. Exposes contiguous pit perimeter masks for crisp rim & glow rendering.</p>
 */
public final class VoidPitDetector {
    public static final int MIN_PIT_RANGE = 6;
    public static final int MAX_PIT_RANGE = 24;
    public static final int MAX_GAP_SPAN = 3;
    public static final int MAX_PITS = 96;

    public static final byte EDGE_NORTH = 1;
    public static final byte EDGE_SOUTH = 2;
    public static final byte EDGE_WEST = 4;
    public static final byte EDGE_EAST = 8;

    private static final int MAX_GRID_SIDE = (MAX_PIT_RANGE + MAX_GAP_SPAN) * 2 + 1;
    private static final int MAX_GRID_CELLS = MAX_GRID_SIDE * MAX_GRID_SIDE;
    private static final int REFRESH_INTERVAL_TICKS = 3;

    private final short[] surfaceYGrid = new short[MAX_GRID_CELLS];
    private final boolean[] voidColumnGrid = new boolean[MAX_GRID_CELLS];
    private final short[] detectedPitYGrid = new short[MAX_GRID_CELLS];

    private final int[] pitX = new int[MAX_PITS];
    private final int[] pitY = new int[MAX_PITS];
    private final int[] pitZ = new int[MAX_PITS];
    private final byte[] pitEdgeMask = new byte[MAX_PITS];
    private final double[] pitDistSq = new double[MAX_PITS];

    private int pitCount;
    private World lastWorld;
    private int lastPlayerBlockX;
    private int lastPlayerBlockY;
    private int lastPlayerBlockZ;
    private int lastRange = -1;
    private int tickCounter;
    private boolean hasSnapshot;

    /**
     * Refreshes detected void pit gaps around the player when enabled.
     *
     * @param world   client world
     * @param player  local client player
     * @param enabled whether pit detection is active
     * @param range   configured horizontal detection radius in blocks ({@code 6..24})
     */
    public void update(World world, EntityPlayer player, boolean enabled, int range) {
        if (!enabled || world == null || player == null) {
            clear();
            return;
        }
        if (world != this.lastWorld) {
            clear();
            this.lastWorld = world;
        }

        int clampedRange = clampRange(range);
        int px = MathHelper.floor_double(player.posX);
        int py = MathHelper.floor_double(player.posY);
        int pz = MathHelper.floor_double(player.posZ);

        boolean shouldRefresh = !this.hasSnapshot
            || px != this.lastPlayerBlockX
            || py != this.lastPlayerBlockY
            || pz != this.lastPlayerBlockZ
            || clampedRange != this.lastRange
            || (this.tickCounter % REFRESH_INTERVAL_TICKS) == 0;

        this.tickCounter++;
        if (!shouldRefresh) {
            return;
        }

        this.lastPlayerBlockX = px;
        this.lastPlayerBlockY = py;
        this.lastPlayerBlockZ = pz;
        this.lastRange = clampedRange;
        this.hasSnapshot = true;

        int pad = MAX_GAP_SPAN;
        int half = clampedRange + pad;
        int side = half * 2 + 1;
        int originX = px - half;
        int originZ = pz - half;

        populateWorldGrid(world, px, py - 1, pz, originX, originZ, side);
        detectFromPreparedGrid(side, side, originX, originZ, px, pz, clampedRange);
    }

    /**
     * Clears detected pit results and snapshot tracking state.
     */
    public void clear() {
        this.pitCount = 0;
        this.hasSnapshot = false;
        this.lastRange = -1;
        this.tickCounter = 0;
        this.lastWorld = null;
    }

    public boolean hasPits() {
        return this.pitCount > 0;
    }

    public int getPitCount() {
        return this.pitCount;
    }

    public int[] getPitXArray() {
        return this.pitX;
    }

    public int[] getPitYArray() {
        return this.pitY;
    }

    public int[] getPitZArray() {
        return this.pitZ;
    }

    public byte[] getPitEdgeMaskArray() {
        return this.pitEdgeMask;
    }

    public double[] getPitDistSqArray() {
        return this.pitDistSq;
    }

    /**
     * Returns whether a block offers zero fall-arrest resistance and opens straight into a void drop.
     *
     * @param block target block
     * @return {@code true} if entities fall directly through this block
     */
    public static boolean isFallThrough(Block block) {
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return true;
        }
        return block == Blocks.tallgrass
            || block == Blocks.deadbush
            || block == Blocks.vine
            || block == Blocks.torch
            || block == Blocks.redstone_torch
            || block == Blocks.unlit_redstone_torch
            || block == Blocks.redstone_wire
            || block == Blocks.standing_sign
            || block == Blocks.wall_sign
            || block == Blocks.stone_button
            || block == Blocks.wooden_button
            || block == Blocks.stone_pressure_plate
            || block == Blocks.wooden_pressure_plate
            || block == Blocks.light_weighted_pressure_plate
            || block == Blocks.heavy_weighted_pressure_plate
            || block == Blocks.lever
            || block == Blocks.fire;
    }

    /**
     * Returns whether a block supports player footing as a solid road or bridge block.
     *
     * @param block target block
     * @return {@code true} if walkable solid footing
     */
    public static boolean isWalkableFloor(Block block) {
        return !isFallThrough(block)
            && block != Blocks.water
            && block != Blocks.flowing_water
            && block != Blocks.lava
            && block != Blocks.flowing_lava;
    }

    /**
     * Test hook for deterministic grid-based pit detection without a loaded Minecraft world.
     *
     * @param width     grid width ({@code >= 7}, {@code <= MAX_GRID_SIDE})
     * @param depth     grid depth ({@code >= 7}, {@code <= MAX_GRID_SIDE})
     * @param originX   world X coordinate of grid cell {@code (0, 0)}
     * @param originZ   world Z coordinate of grid cell {@code (0, 0)}
     * @param surfaceY  flattened {@code width * depth} walkable lip Y values ({@code -1} if none)
     * @param voidCols  flattened {@code width * depth} void-column flags
     * @param playerX   reference player block X
     * @param playerZ   reference player block Z
     * @param range     detection radius in blocks
     */
    public void detectFromGridForTest(
        int width,
        int depth,
        int originX,
        int originZ,
        short[] surfaceY,
        boolean[] voidCols,
        int playerX,
        int playerZ,
        int range
    ) {
        this.pitCount = 0;
        if (width < MAX_GAP_SPAN * 2 + 1
            || depth < MAX_GAP_SPAN * 2 + 1
            || width > MAX_GRID_SIDE
            || depth > MAX_GRID_SIDE
            || surfaceY == null
            || voidCols == null) {
            return;
        }
        int total = width * depth;
        if (surfaceY.length < total || voidCols.length < total) {
            return;
        }
        System.arraycopy(surfaceY, 0, this.surfaceYGrid, 0, total);
        System.arraycopy(voidCols, 0, this.voidColumnGrid, 0, total);
        detectFromPreparedGrid(width, depth, originX, originZ, playerX, playerZ, Math.max(1, range));
    }

    private void populateWorldGrid(
        World world,
        int px,
        int floorY,
        int pz,
        int originX,
        int originZ,
        int side
    ) {
        int bandMinY = Math.max(1, floorY - 2);
        int bandMaxY = Math.min(254, floorY + 2);
        int voidTopY = Math.min(255, floorY + 2);
        int maxSec = voidTopY >> 4;

        for (int gz = 0; gz < side; gz++) {
            int wz = originZ + gz;
            int cz = wz >> 4;
            int localZ = wz & 15;
            int rowBase = gz * side;

            int cachedCx = Integer.MIN_VALUE;
            Chunk cachedChunk = null;

            for (int gx = 0; gx < side; gx++) {
                int wx = originX + gx;
                int cx = wx >> 4;
                if (cx != cachedCx) {
                    cachedCx = cx;
                    cachedChunk = world.getChunkFromChunkCoords(cx, cz);
                }

                int idx = rowBase + gx;
                if (cachedChunk == null || cachedChunk.isEmpty()) {
                    this.surfaceYGrid[idx] = -1;
                    this.voidColumnGrid[idx] = false;
                    continue;
                }

                ExtendedBlockStorage[] sections = cachedChunk.getBlockStorageArray();
                if (sections == null) {
                    this.surfaceYGrid[idx] = -1;
                    this.voidColumnGrid[idx] = false;
                    continue;
                }

                int localX = wx & 15;
                short foundSurface = -1;
                for (int y = bandMaxY; y >= bandMinY; y--) {
                    Block blockAtY = readBlockFromSections(sections, localX, y, localZ);
                    if (isWalkableFloor(blockAtY)) {
                        Block blockAbove = readBlockFromSections(sections, localX, y + 1, localZ);
                        if (isFallThrough(blockAbove)) {
                            foundSurface = (short) y;
                            break;
                        }
                    }
                }

                this.surfaceYGrid[idx] = foundSurface;
                if (foundSurface >= 0) {
                    this.voidColumnGrid[idx] = false;
                    continue;
                }

                boolean isVoid = true;
                int clampedMaxSec = Math.min(sections.length - 1, maxSec);
                for (int sec = clampedMaxSec; sec >= 0; sec--) {
                    ExtendedBlockStorage storage = sections[sec];
                    if (storage == null || storage.isEmpty()) {
                        continue;
                    }
                    int secTop = Math.min(voidTopY, (sec << 4) + 15);
                    for (int y = secTop; y >= (sec << 4); y--) {
                        IBlockState state = storage.get(localX, y & 15, localZ);
                        if (!isFallThrough(state.getBlock())) {
                            isVoid = false;
                            break;
                        }
                    }
                    if (!isVoid) {
                        break;
                    }
                }
                this.voidColumnGrid[idx] = isVoid;
            }
        }
    }

    private static Block readBlockFromSections(ExtendedBlockStorage[] sections, int localX, int y, int localZ) {
        if (y < 0 || y > 255) {
            return Blocks.air;
        }
        int sec = y >> 4;
        if (sec >= sections.length) {
            return Blocks.air;
        }
        ExtendedBlockStorage storage = sections[sec];
        if (storage == null || storage.isEmpty()) {
            return Blocks.air;
        }
        return storage.get(localX, y & 15, localZ).getBlock();
    }

    private void detectFromPreparedGrid(
        int width,
        int depth,
        int originX,
        int originZ,
        int playerX,
        int playerZ,
        int range
    ) {
        int total = width * depth;
        Arrays.fill(this.detectedPitYGrid, 0, total, (short) -1);
        this.pitCount = 0;

        int pad = MAX_GAP_SPAN;
        double maxRangeSq = (double) range * (double) range;

        for (int gz = pad; gz < depth - pad; gz++) {
            int rowBase = gz * width;
            int wz = originZ + gz;
            double dz = (double) (wz - playerZ);
            double dzSq = dz * dz;
            for (int gx = pad; gx < width - pad; gx++) {
                int idx = rowBase + gx;
                if (!this.voidColumnGrid[idx]) {
                    continue;
                }
                int wx = originX + gx;
                double dx = (double) (wx - playerX);
                if (dx * dx + dzSq > maxRangeSq) {
                    continue;
                }

                short pitY = findBracketedPitY(gx, gz, width);
                if (pitY >= 0) {
                    this.detectedPitYGrid[idx] = pitY;
                }
            }
        }

        for (int gz = pad; gz < depth - pad; gz++) {
            int rowBase = gz * width;
            int wz = originZ + gz;
            double dz = (double) (wz - playerZ);
            for (int gx = pad; gx < width - pad; gx++) {
                int idx = rowBase + gx;
                short y = this.detectedPitYGrid[idx];
                if (y < 0) {
                    continue;
                }

                byte mask = 0;
                if (this.detectedPitYGrid[idx - width] != y) {
                    mask |= EDGE_NORTH;
                }
                if (this.detectedPitYGrid[idx + width] != y) {
                    mask |= EDGE_SOUTH;
                }
                if (this.detectedPitYGrid[idx - 1] != y) {
                    mask |= EDGE_WEST;
                }
                if (this.detectedPitYGrid[idx + 1] != y) {
                    mask |= EDGE_EAST;
                }

                int wx = originX + gx;
                double dx = (double) (wx - playerX);
                insertPitSorted(wx, y, wz, mask, dx * dx + dz * dz);
            }
        }
    }

    private short findBracketedPitY(int gx, int gz, int width) {
        short xY = findAxisBracketY(gx, gz, 1, 0, width);
        if (xY >= 0) {
            return xY;
        }
        short zY = findAxisBracketY(gx, gz, 0, 1, width);
        if (zY >= 0) {
            return zY;
        }

        // Handle 1-block diagonal staircase/bridge gaps across opposite corners.
        short yNW = this.surfaceYGrid[(gz - 1) * width + (gx - 1)];
        short ySE = this.surfaceYGrid[(gz + 1) * width + (gx + 1)];
        if (yNW >= 0 && ySE >= 0 && Math.abs(yNW - ySE) <= 1) {
            return yNW < ySE ? yNW : ySE;
        }
        short yNE = this.surfaceYGrid[(gz - 1) * width + (gx + 1)];
        short ySW = this.surfaceYGrid[(gz + 1) * width + (gx - 1)];
        if (yNE >= 0 && ySW >= 0 && Math.abs(yNE - ySW) <= 1) {
            return yNE < ySW ? yNE : ySW;
        }
        return -1;
    }

    private short findAxisBracketY(int gx, int gz, int stepX, int stepZ, int width) {
        int negDist = 0;
        short negY = -1;
        for (int d = 1; d <= MAX_GAP_SPAN; d++) {
            int cx = gx - stepX * d;
            int cz = gz - stepZ * d;
            int idx = cz * width + cx;
            short sy = this.surfaceYGrid[idx];
            if (sy >= 0) {
                negDist = d;
                negY = sy;
                break;
            }
            if (!this.voidColumnGrid[idx]) {
                return -1;
            }
        }
        if (negDist == 0) {
            return -1;
        }

        int posDist = 0;
        short posY = -1;
        for (int d = 1; d <= MAX_GAP_SPAN; d++) {
            int cx = gx + stepX * d;
            int cz = gz + stepZ * d;
            int idx = cz * width + cx;
            short sy = this.surfaceYGrid[idx];
            if (sy >= 0) {
                posDist = d;
                posY = sy;
                break;
            }
            if (!this.voidColumnGrid[idx]) {
                return -1;
            }
        }
        if (posDist == 0) {
            return -1;
        }

        int totalSpan = negDist + posDist - 1;
        if (totalSpan > MAX_GAP_SPAN || Math.abs(negY - posY) > 2) {
            return -1;
        }
        return negY < posY ? negY : posY;
    }

    private void insertPitSorted(int wx, int wy, int wz, byte edgeMask, double distSq) {
        if (this.pitCount < MAX_PITS) {
            int slot = this.pitCount++;
            this.pitX[slot] = wx;
            this.pitY[slot] = wy;
            this.pitZ[slot] = wz;
            this.pitEdgeMask[slot] = edgeMask;
            this.pitDistSq[slot] = distSq;
            return;
        }

        int farthestIdx = -1;
        double farthestDistSq = distSq;
        for (int i = 0; i < MAX_PITS; i++) {
            if (this.pitDistSq[i] > farthestDistSq) {
                farthestDistSq = this.pitDistSq[i];
                farthestIdx = i;
            }
        }
        if (farthestIdx >= 0) {
            this.pitX[farthestIdx] = wx;
            this.pitY[farthestIdx] = wy;
            this.pitZ[farthestIdx] = wz;
            this.pitEdgeMask[farthestIdx] = edgeMask;
            this.pitDistSq[farthestIdx] = distSq;
        }
    }

    private static int clampRange(int range) {
        if (range < MIN_PIT_RANGE) {
            return MIN_PIT_RANGE;
        }
        if (range > MAX_PIT_RANGE) {
            return MAX_PIT_RANGE;
        }
        return range;
    }
}
