package com.hoptimization.modules.visual.jarvis;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.Arrays;

/**
 * Zero-allocation 3D bed defense mining pathfinder on a {@code 12 x 7 x 12} local grid.
 *
 * <p>Uses a two-stage solver: Stage A floods external passable air from the outer boundaries and
 * player cell so sealed Butterfly air cavities cannot act as false entry points; Stage B runs an
 * indexed binary min-heap Dijkstra to find the fastest break sequence to the bed.</p>
 */
public final class BedMiningPathfinder {
    public static final int GRID_X = 12;
    public static final int GRID_Y = 7;
    public static final int GRID_Z = 12;
    public static final int GRID_SIZE = GRID_X * GRID_Y * GRID_Z;
    public static final int MAX_PATH = 24;

    private static final float INTERNAL_AIR_COST = 0.02F;
    private static final float SOLID_BLOCK_PENALTY = 0.25F;
    private static final float TOOL_SWAP_PENALTY = 0.08F;
    private static final float FLOATING_ENTRY_PENALTY = 0.6F;
    private static final double REACH_DISTANCE = 4.5D;
    private static final double APPROACH_OFFSET = 3.5D;

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private final boolean[] externalAir = new boolean[GRID_SIZE];
    private final boolean[] isBedCell = new boolean[GRID_SIZE];
    private final boolean[] isAirCell = new boolean[GRID_SIZE];
    private final float[] mineCost = new float[GRID_SIZE];
    private final int[] bestSlot = new int[GRID_SIZE];
    private final float[] dist = new float[GRID_SIZE];
    private final int[] parent = new int[GRID_SIZE];
    private final int[] parentSlot = new int[GRID_SIZE];
    private final boolean[] closed = new boolean[GRID_SIZE];
    private final int[] bfsQueue = new int[GRID_SIZE];

    private final int[] heapNode = new int[GRID_SIZE];
    private final float[] heapVal = new float[GRID_SIZE];
    private final int[] heapPos = new int[GRID_SIZE];
    private int heapSize;

    private final int[] seedEntryAir = new int[GRID_SIZE];
    private final int[] reversePathNodes = new int[GRID_SIZE];

    private final int[] stepX = new int[MAX_PATH];
    private final int[] stepY = new int[MAX_PATH];
    private final int[] stepZ = new int[MAX_PATH];
    private final int[] stepToolSlot = new int[MAX_PATH];
    private final float[] stepSeconds = new float[MAX_PATH];

    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
    private final int[] tmpBestSlot = new int[1];

    private int originX;
    private int originY;
    private int originZ;

    private boolean validPath;
    private boolean nakedBed;
    private int stepCount;
    private int goalBedX;
    private int goalBedY;
    private int goalBedZ;
    private double entryX;
    private double entryY;
    private double entryZ;
    private float totalMineSeconds;

    /**
     * Converts local grid coordinates {@code (lx, ly, lz)} into a flat index in {@code [0, GRID_SIZE - 1]}.
     *
     * @param lx local X in {@code [0, GRID_X - 1]}
     * @param ly local Y in {@code [0, GRID_Y - 1]}
     * @param lz local Z in {@code [0, GRID_Z - 1]}
     * @return flat cell index
     */
    public static int gridIndex(int lx, int ly, int lz) {
        return (ly * GRID_Z + lz) * GRID_X + lx;
    }

    /**
     * Solves the optimal 3D mining sequence from reachable external air to the target bed.
     *
     * @param world           active client world
     * @param player          local player (used for tools, potions, and approach timing; may be {@code null})
     * @param headX           bed head X coordinate
     * @param headY           bed head Y coordinate
     * @param headZ           bed head Z coordinate
     * @param facingDir       bed facing index ({@code 0=SOUTH, 1=WEST, 2=NORTH, 3=EAST}, foot -&gt; head)
     * @param pairedFoot      whether the bed has a paired foot block
     * @param includeApproach whether to add approach travel and floating penalties to entry candidates
     * @return {@code true} if a valid mining path (or exposed naked bed) was found
     */
    public boolean solve(
        World world,
        EntityPlayer player,
        int headX,
        int headY,
        int headZ,
        byte facingDir,
        boolean pairedFoot,
        boolean includeApproach
    ) {
        if (world == null) {
            clear();
            return false;
        }

        int fx = getFacingOffsetX(facingDir);
        int fz = getFacingOffsetZ(facingDir);
        int footX = pairedFoot ? headX - fx : headX;
        int footZ = pairedFoot ? headZ - fz : headZ;

        this.originX = Math.min(headX, footX) - 5;
        this.originY = headY;
        this.originZ = Math.min(headZ, footZ) - 5;

        int headLx = headX - this.originX;
        int headLz = headZ - this.originZ;
        int footLx = footX - this.originX;
        int footLz = footZ - this.originZ;
        int headIdx = gridIndex(headLx, 0, headLz);
        int footIdx = pairedFoot ? gridIndex(footLx, 0, footLz) : headIdx;

        for (int ly = 0; ly < GRID_Y; ly++) {
            int wy = this.originY + ly;
            for (int lz = 0; lz < GRID_Z; lz++) {
                int wz = this.originZ + lz;
                for (int lx = 0; lx < GRID_X; lx++) {
                    int idx = gridIndex(lx, ly, lz);
                    if (idx == headIdx || idx == footIdx) {
                        this.isBedCell[idx] = true;
                        this.isAirCell[idx] = false;
                        this.mineCost[idx] = 0.0F;
                        this.bestSlot[idx] = -1;
                        continue;
                    }
                    this.isBedCell[idx] = false;

                    if (wy > 255) {
                        this.isAirCell[idx] = true;
                        this.mineCost[idx] = 0.0F;
                        this.bestSlot[idx] = -1;
                        continue;
                    }
                    if (wy < 0) {
                        this.isAirCell[idx] = false;
                        this.mineCost[idx] = Float.POSITIVE_INFINITY;
                        this.bestSlot[idx] = -1;
                        continue;
                    }

                    this.scanPos.set(this.originX + lx, wy, wz);
                    if (!world.isBlockLoaded(this.scanPos)) {
                        this.isAirCell[idx] = false;
                        this.mineCost[idx] = Float.POSITIVE_INFINITY;
                        this.bestSlot[idx] = -1;
                        continue;
                    }

                    IBlockState state = world.getBlockState(this.scanPos);
                    Block block = state.getBlock();
                    if (BedMiningMath.isPassableAir(block)) {
                        this.isAirCell[idx] = true;
                        this.mineCost[idx] = 0.0F;
                        this.bestSlot[idx] = -1;
                    } else {
                        this.isAirCell[idx] = false;
                        this.mineCost[idx] = BedMiningMath.computeBestBreakSeconds(
                            world,
                            this.scanPos,
                            state,
                            player,
                            this.tmpBestSlot
                        );
                        this.bestSlot[idx] = this.tmpBestSlot[0];
                    }
                }
            }
        }

        int playerLx = player != null ? MathHelper.floor_double(player.posX) - this.originX : -1;
        int playerLy = player != null ? MathHelper.floor_double(player.posY) - this.originY : -1;
        int playerLz = player != null ? MathHelper.floor_double(player.posZ) - this.originZ : -1;
        double eyeX = player != null ? player.posX : headX + 0.5D;
        double eyeY = player != null ? player.posY + player.getEyeHeight() : headY + 1.62D;
        double eyeZ = player != null ? player.posZ : headZ + 0.5D;
        float moveSpeed = BedMiningMath.computePlayerMoveSpeed(player);

        return solveInternal(
            world,
            playerLx,
            playerLy,
            playerLz,
            eyeX,
            eyeY,
            eyeZ,
            moveSpeed,
            includeApproach && player != null
        );
    }

    /**
     * Computes only the minimum total block-break seconds required to reach the bed (no approach cost).
     *
     * @param world      active client world
     * @param player     local player
     * @param headX      bed head X
     * @param headY      bed head Y
     * @param headZ      bed head Z
     * @param facingDir  bed facing index
     * @param pairedFoot whether the bed has a paired foot block
     * @return minimum break seconds ({@code 0.0F} if naked bed), or {@link Float#POSITIVE_INFINITY} if unreachable
     */
    public float solveMinBreakSecondsOnly(
        World world,
        EntityPlayer player,
        int headX,
        int headY,
        int headZ,
        byte facingDir,
        boolean pairedFoot
    ) {
        if (!solve(world, player, headX, headY, headZ, facingDir, pairedFoot, false)) {
            return Float.POSITIVE_INFINITY;
        }
        return this.totalMineSeconds;
    }

    /**
     * Pure Java 8 grid entry point for unit-testing external-air BFS and Butterfly cavity handling without MC World.
     *
     * @param customIsAir     grid air flags of length {@link #GRID_SIZE}
     * @param customMineCost  grid break durations of length {@link #GRID_SIZE}
     * @param customIsBed     grid bed flags of length {@link #GRID_SIZE}
     * @param playerLx        player local X coordinate (may be out of grid bounds)
     * @param playerLy        player local Y coordinate (may be out of grid bounds)
     * @param playerLz        player local Z coordinate (may be out of grid bounds)
     * @param includeApproach whether to include approach distance and floating penalties
     * @return {@code true} if a valid path or naked bed was found
     */
    public boolean solveGridForTest(
        boolean[] customIsAir,
        float[] customMineCost,
        boolean[] customIsBed,
        int playerLx,
        int playerLy,
        int playerLz,
        boolean includeApproach
    ) {
        if (customIsAir == null
            || customMineCost == null
            || customIsBed == null
            || customIsAir.length < GRID_SIZE
            || customMineCost.length < GRID_SIZE
            || customIsBed.length < GRID_SIZE) {
            clear();
            return false;
        }

        this.originX = 0;
        this.originY = 0;
        this.originZ = 0;
        for (int i = 0; i < GRID_SIZE; i++) {
            this.isBedCell[i] = customIsBed[i];
            this.isAirCell[i] = !customIsBed[i] && customIsAir[i];
            this.mineCost[i] = customIsBed[i] ? 0.0F : customMineCost[i];
            this.bestSlot[i] = -1;
        }

        double eyeX = playerLx + 0.5D;
        double eyeY = playerLy + 1.62D;
        double eyeZ = playerLz + 0.5D;
        return solveInternal(null, playerLx, playerLy, playerLz, eyeX, eyeY, eyeZ, 5.61F, includeApproach);
    }

    private boolean solveInternal(
        World world,
        int playerLx,
        int playerLy,
        int playerLz,
        double eyeX,
        double eyeY,
        double eyeZ,
        float moveSpeed,
        boolean includeApproach
    ) {
        runExternalAirBfs(playerLx, playerLy, playerLz);

        if (tryResolveNakedBed(world, eyeX, eyeY, eyeZ, includeApproach)) {
            return true;
        }

        Arrays.fill(this.dist, Float.POSITIVE_INFINITY);
        Arrays.fill(this.parent, -1);
        Arrays.fill(this.parentSlot, -1);
        Arrays.fill(this.seedEntryAir, -1);
        Arrays.fill(this.closed, false);
        Arrays.fill(this.heapPos, -1);
        this.heapSize = 0;

        for (int ly = 0; ly < GRID_Y; ly++) {
            for (int lz = 0; lz < GRID_Z; lz++) {
                for (int lx = 0; lx < GRID_X; lx++) {
                    int s = gridIndex(lx, ly, lz);
                    if (this.isAirCell[s] || this.isBedCell[s] || !isBreakable(this.mineCost[s])) {
                        continue;
                    }

                    int bestAir = -1;
                    float bestApproach = Float.POSITIVE_INFINITY;
                    boolean bestAirSupported = false;

                    for (int dir = 0; dir < 6; dir++) {
                        int ax = lx + DX[dir];
                        int ay = ly + DY[dir];
                        int az = lz + DZ[dir];
                        if (!inBounds(ax, ay, az)) {
                            continue;
                        }
                        int a = gridIndex(ax, ay, az);
                        if (!this.externalAir[a]) {
                            continue;
                        }

                        boolean supported = hasSolidSupportWithin2Blocks(world, ax, ay, az);
                        if (includeApproach) {
                            double d = distanceToBlockCenter(eyeX, eyeY, eyeZ, lx, ly, lz);
                            float approach = d <= REACH_DISTANCE
                                ? 0.0F
                                : (float) ((d - APPROACH_OFFSET) / moveSpeed);
                            if (!supported) {
                                approach += FLOATING_ENTRY_PENALTY;
                            }
                            if (bestAir == -1 || approach < bestApproach) {
                                bestAir = a;
                                bestApproach = approach;
                                bestAirSupported = supported;
                            }
                        } else if (bestAir == -1 || (supported && !bestAirSupported)) {
                            bestAir = a;
                            bestApproach = 0.0F;
                            bestAirSupported = supported;
                        }
                    }

                    if (bestAir != -1) {
                        float seedDist = this.mineCost[s] + SOLID_BLOCK_PENALTY + bestApproach;
                        this.dist[s] = seedDist;
                        this.parent[s] = -1;
                        this.parentSlot[s] = this.bestSlot[s];
                        this.seedEntryAir[s] = bestAir;
                        heapPushOrDecrease(s, seedDist);
                    }
                }
            }
        }

        int goalNode = -1;
        while (this.heapSize > 0) {
            int u = heapPop();
            if (this.closed[u]) {
                continue;
            }
            this.closed[u] = true;
            if (this.isBedCell[u]) {
                goalNode = u;
                break;
            }

            int ux = indexToLx(u);
            int uy = indexToLy(u);
            int uz = indexToLz(u);
            float uDist = this.dist[u];
            int uSlot = this.parentSlot[u];

            for (int dir = 0; dir < 6; dir++) {
                int vx = ux + DX[dir];
                int vy = uy + DY[dir];
                int vz = uz + DZ[dir];
                if (!inBounds(vx, vy, vz)) {
                    continue;
                }
                int v = gridIndex(vx, vy, vz);
                // Skip external air so paths stay inside the defense once breached.
                if (this.closed[v] || this.externalAir[v]) {
                    continue;
                }

                if (this.isBedCell[v]) {
                    if (uDist < this.dist[v]) {
                        this.dist[v] = uDist;
                        this.parent[v] = u;
                        this.parentSlot[v] = uSlot;
                        heapPushOrDecrease(v, uDist);
                    }
                } else if (this.isAirCell[v]) {
                    float cand = uDist + INTERNAL_AIR_COST;
                    if (cand < this.dist[v]) {
                        this.dist[v] = cand;
                        this.parent[v] = u;
                        this.parentSlot[v] = uSlot;
                        heapPushOrDecrease(v, cand);
                    }
                } else if (isBreakable(this.mineCost[v])) {
                    int nextSlot = this.bestSlot[v];
                    boolean slotChanged = uSlot != -1 && nextSlot != -1 && uSlot != nextSlot;
                    int propagatedSlot = nextSlot != -1 ? nextSlot : uSlot;
                    float cand = uDist
                        + this.mineCost[v]
                        + SOLID_BLOCK_PENALTY
                        + (slotChanged ? TOOL_SWAP_PENALTY : 0.0F);
                    if (cand < this.dist[v]) {
                        this.dist[v] = cand;
                        this.parent[v] = u;
                        this.parentSlot[v] = propagatedSlot;
                        heapPushOrDecrease(v, cand);
                    }
                }
            }
        }

        if (goalNode == -1) {
            clear();
            return false;
        }

        int solidCount = 0;
        int firstSeedNode = -1;
        int cur = this.parent[goalNode];
        while (cur != -1) {
            firstSeedNode = cur;
            if (!this.isAirCell[cur] && !this.isBedCell[cur]) {
                this.reversePathNodes[solidCount++] = cur;
            }
            cur = this.parent[cur];
        }

        this.nakedBed = false;
        this.validPath = true;
        this.stepCount = 0;
        this.totalMineSeconds = 0.0F;

        for (int i = solidCount - 1; i >= 0; i--) {
            int node = this.reversePathNodes[i];
            float cost = this.mineCost[node];
            this.totalMineSeconds += cost;
            if (this.stepCount < MAX_PATH) {
                int outIdx = this.stepCount++;
                this.stepX[outIdx] = this.originX + indexToLx(node);
                this.stepY[outIdx] = this.originY + indexToLy(node);
                this.stepZ[outIdx] = this.originZ + indexToLz(node);
                this.stepToolSlot[outIdx] = this.bestSlot[node];
                this.stepSeconds[outIdx] = cost;
            }
        }

        this.goalBedX = this.originX + indexToLx(goalNode);
        this.goalBedY = this.originY + indexToLy(goalNode);
        this.goalBedZ = this.originZ + indexToLz(goalNode);

        int entryAir = firstSeedNode != -1 ? this.seedEntryAir[firstSeedNode] : -1;
        if (entryAir != -1) {
            this.entryX = this.originX + indexToLx(entryAir) + 0.5D;
            this.entryY = this.originY + indexToLy(entryAir) + 0.5D;
            this.entryZ = this.originZ + indexToLz(entryAir) + 0.5D;
        } else {
            this.entryX = this.goalBedX + 0.5D;
            this.entryY = this.goalBedY + 0.5D;
            this.entryZ = this.goalBedZ + 0.5D;
        }
        return true;
    }

    private void runExternalAirBfs(int playerLx, int playerLy, int playerLz) {
        Arrays.fill(this.externalAir, false);
        int qHead = 0;
        int qTail = 0;

        for (int ly = 0; ly < GRID_Y; ly++) {
            for (int lz = 0; lz < GRID_Z; lz++) {
                for (int lx = 0; lx < GRID_X; lx++) {
                    if (lx == 0 || lx == GRID_X - 1 || lz == 0 || lz == GRID_Z - 1 || ly == GRID_Y - 1) {
                        int idx = gridIndex(lx, ly, lz);
                        if (this.isAirCell[idx] && !this.externalAir[idx]) {
                            this.externalAir[idx] = true;
                            this.bfsQueue[qTail++] = idx;
                        }
                    }
                }
            }
        }

        if (inBounds(playerLx, playerLy, playerLz)) {
            int pIdx = gridIndex(playerLx, playerLy, playerLz);
            if (this.isAirCell[pIdx] && !this.externalAir[pIdx]) {
                this.externalAir[pIdx] = true;
                this.bfsQueue[qTail++] = pIdx;
            }
        }

        while (qHead < qTail) {
            int u = this.bfsQueue[qHead++];
            int ux = indexToLx(u);
            int uy = indexToLy(u);
            int uz = indexToLz(u);
            for (int dir = 0; dir < 6; dir++) {
                int vx = ux + DX[dir];
                int vy = uy + DY[dir];
                int vz = uz + DZ[dir];
                if (!inBounds(vx, vy, vz)) {
                    continue;
                }
                int v = gridIndex(vx, vy, vz);
                if (this.isAirCell[v] && !this.externalAir[v]) {
                    this.externalAir[v] = true;
                    this.bfsQueue[qTail++] = v;
                }
            }
        }
    }

    private boolean tryResolveNakedBed(
        World world,
        double eyeX,
        double eyeY,
        double eyeZ,
        boolean includeApproach
    ) {
        int bestAir = -1;
        int bestBed = -1;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int b = 0; b < GRID_SIZE; b++) {
            if (!this.isBedCell[b]) {
                continue;
            }
            int bx = indexToLx(b);
            int by = indexToLy(b);
            int bz = indexToLz(b);
            for (int dir = 0; dir < 6; dir++) {
                int ax = bx + DX[dir];
                int ay = by + DY[dir];
                int az = bz + DZ[dir];
                if (!inBounds(ax, ay, az)) {
                    continue;
                }
                int a = gridIndex(ax, ay, az);
                if (!this.externalAir[a]) {
                    continue;
                }
                double dx = (this.originX + ax + 0.5D) - eyeX;
                double dy = (this.originY + ay + 0.5D) - eyeY;
                double dz = (this.originZ + az + 0.5D) - eyeZ;
                double score = dx * dx + dy * dy + dz * dz;
                if (includeApproach && !hasSolidSupportWithin2Blocks(world, ax, ay, az)) {
                    score += 25.0D;
                }
                if (bestAir == -1 || score < bestScore) {
                    bestScore = score;
                    bestAir = a;
                    bestBed = b;
                }
            }
        }

        if (bestAir == -1) {
            return false;
        }

        this.nakedBed = true;
        this.validPath = true;
        this.stepCount = 0;
        this.totalMineSeconds = 0.0F;
        this.goalBedX = this.originX + indexToLx(bestBed);
        this.goalBedY = this.originY + indexToLy(bestBed);
        this.goalBedZ = this.originZ + indexToLz(bestBed);
        this.entryX = this.originX + indexToLx(bestAir) + 0.5D;
        this.entryY = this.originY + indexToLy(bestAir) + 0.5D;
        this.entryZ = this.originZ + indexToLz(bestAir) + 0.5D;
        return true;
    }

    private boolean hasSolidSupportWithin2Blocks(World world, int ax, int ay, int az) {
        for (int dy = 1; dy <= 2; dy++) {
            int checkLy = ay - dy;
            if (checkLy >= 0) {
                if (!this.isAirCell[gridIndex(ax, checkLy, az)]) {
                    return true;
                }
            } else if (world == null) {
                // Pure grid tests assume solid island floor beneath ly == 0.
                return true;
            } else {
                int wy = this.originY + checkLy;
                if (wy < 0 || wy > 255) {
                    continue;
                }
                this.scanPos.set(this.originX + ax, wy, this.originZ + az);
                if (world.isBlockLoaded(this.scanPos)
                    && !BedMiningMath.isPassableAir(world.getBlockState(this.scanPos).getBlock())) {
                    return true;
                }
            }
        }
        return false;
    }

    private double distanceToBlockCenter(double eyeX, double eyeY, double eyeZ, int lx, int ly, int lz) {
        double dx = (this.originX + lx + 0.5D) - eyeX;
        double dy = (this.originY + ly + 0.5D) - eyeY;
        double dz = (this.originZ + lz + 0.5D) - eyeZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void heapPushOrDecrease(int node, float val) {
        int pos = this.heapPos[node];
        if (pos == -1) {
            pos = this.heapSize++;
            this.heapNode[pos] = node;
            this.heapVal[pos] = val;
            this.heapPos[node] = pos;
            siftUp(pos);
        } else if (val < this.heapVal[pos]) {
            this.heapVal[pos] = val;
            siftUp(pos);
        }
    }

    private int heapPop() {
        int topNode = this.heapNode[0];
        this.heapPos[topNode] = -1;
        int newSize = --this.heapSize;
        if (newSize > 0) {
            int movedNode = this.heapNode[newSize];
            float movedVal = this.heapVal[newSize];
            this.heapNode[0] = movedNode;
            this.heapVal[0] = movedVal;
            this.heapPos[movedNode] = 0;
            siftDown(0);
        }
        return topNode;
    }

    private void siftUp(int pos) {
        int node = this.heapNode[pos];
        float val = this.heapVal[pos];
        while (pos > 0) {
            int p = (pos - 1) >>> 1;
            float pVal = this.heapVal[p];
            if (val >= pVal) {
                break;
            }
            int pNode = this.heapNode[p];
            this.heapNode[pos] = pNode;
            this.heapVal[pos] = pVal;
            this.heapPos[pNode] = pos;
            pos = p;
        }
        this.heapNode[pos] = node;
        this.heapVal[pos] = val;
        this.heapPos[node] = pos;
    }

    private void siftDown(int pos) {
        int node = this.heapNode[pos];
        float val = this.heapVal[pos];
        int half = this.heapSize >>> 1;
        while (pos < half) {
            int child = (pos << 1) + 1;
            float cVal = this.heapVal[child];
            int right = child + 1;
            if (right < this.heapSize && this.heapVal[right] < cVal) {
                child = right;
                cVal = this.heapVal[right];
            }
            if (val <= cVal) {
                break;
            }
            int cNode = this.heapNode[child];
            this.heapNode[pos] = cNode;
            this.heapVal[pos] = cVal;
            this.heapPos[cNode] = pos;
            pos = child;
        }
        this.heapNode[pos] = node;
        this.heapVal[pos] = val;
        this.heapPos[node] = pos;
    }

    private static boolean isBreakable(float cost) {
        return !Float.isNaN(cost) && !Float.isInfinite(cost) && cost >= 0.0F;
    }

    private static boolean inBounds(int lx, int ly, int lz) {
        return lx >= 0 && lx < GRID_X && ly >= 0 && ly < GRID_Y && lz >= 0 && lz < GRID_Z;
    }

    private static int indexToLx(int idx) {
        return idx % GRID_X;
    }

    private static int indexToLy(int idx) {
        return idx / (GRID_X * GRID_Z);
    }

    private static int indexToLz(int idx) {
        return (idx / GRID_X) % GRID_Z;
    }

    private static int getFacingOffsetX(byte dir) {
        if (dir == 1) {
            return -1;
        }
        if (dir == 3) {
            return 1;
        }
        return 0;
    }

    private static int getFacingOffsetZ(byte dir) {
        if (dir == 0) {
            return 1;
        }
        if (dir == 2) {
            return -1;
        }
        return 0;
    }

    /**
     * Resets all cached solver outputs.
     */
    public void clear() {
        this.validPath = false;
        this.nakedBed = false;
        this.stepCount = 0;
        this.goalBedX = 0;
        this.goalBedY = 0;
        this.goalBedZ = 0;
        this.entryX = 0.0D;
        this.entryY = 0.0D;
        this.entryZ = 0.0D;
        this.totalMineSeconds = 0.0F;
    }

    public boolean hasValidPath() {
        return this.validPath;
    }

    public boolean isNakedBed() {
        return this.nakedBed;
    }

    public int getStepCount() {
        return this.stepCount;
    }

    public int getStepX(int index) {
        return this.stepX[index];
    }

    public int getStepY(int index) {
        return this.stepY[index];
    }

    public int getStepZ(int index) {
        return this.stepZ[index];
    }

    public int getStepToolSlot(int index) {
        return this.stepToolSlot[index];
    }

    public float getStepSeconds(int index) {
        return this.stepSeconds[index];
    }

    public int[] getStepXArray() {
        return this.stepX;
    }

    public int[] getStepYArray() {
        return this.stepY;
    }

    public int[] getStepZArray() {
        return this.stepZ;
    }

    public int getGoalBedX() {
        return this.goalBedX;
    }

    public int getGoalBedY() {
        return this.goalBedY;
    }

    public int getGoalBedZ() {
        return this.goalBedZ;
    }

    public double getEntryX() {
        return this.entryX;
    }

    public double getEntryY() {
        return this.entryY;
    }

    public double getEntryZ() {
        return this.entryZ;
    }

    public float getTotalSeconds() {
        return this.totalMineSeconds;
    }

    public boolean isExternalAirCell(int lx, int ly, int lz) {
        return inBounds(lx, ly, lz) && this.externalAir[gridIndex(lx, ly, lz)];
    }
}
