package com.hoptimization.modules.visual.jarvis;

import com.hoptimization.modules.visual.jarvis.BedMiningMath.DefenseBlockKind;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.Arrays;

/**
 * Zero-allocation bed defense inspector and tactical target ranker.
 *
 * <p>Performs a 4-shell BFS ({@code y >= headY}, depth {@code 1..4}) from bed cells through
 * contiguous solid/water defense blocks (never expanding through air), extracts unique inner-to-outer
 * layer icons, computes minimum break time via {@link BedMiningPathfinder}, and ranks eligible beds
 * ascending by ease score.</p>
 */
public final class BedDefenseAnalyzer {
    public static final int MAX_BEDS = 16;
    public static final int MAX_LAYER_ICONS = 4;

    private static final int MAX_SHELL_DEPTH = 4;
    private static final int BFS_X = 10;
    private static final int BFS_Y = 5;
    private static final int BFS_Z = 10;
    private static final int BFS_SIZE = BFS_X * BFS_Y * BFS_Z;

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private final DefenseEntry[] entries = new DefenseEntry[MAX_BEDS];
    private final int[] rankedBedSlots = new int[MAX_BEDS];
    private int rankedCount;

    private final boolean[] visited = new boolean[BFS_SIZE];
    private final int[] bfsQueue = new int[BFS_SIZE];
    private final byte[] bfsDepth = new byte[BFS_SIZE];
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

    /**
     * Pre-allocated tactical analysis snapshot for one tracked bed slot.
     */
    public static final class DefenseEntry {
        private boolean valid;
        private int bedSlotIndex;
        private boolean nakedBed;
        private boolean blastProofGlass;
        private boolean water;
        private float minBreakSeconds;
        private float staticDefenseScore;
        private float easeScore;
        private double distance;
        private int layerCount;
        private final DefenseBlockKind[] layers = new DefenseBlockKind[MAX_LAYER_ICONS];
        private int totalDefenseBlocks;

        private DefenseEntry(int slotIndex) {
            this.bedSlotIndex = slotIndex;
            clear();
        }

        private void clear() {
            this.valid = false;
            this.nakedBed = false;
            this.blastProofGlass = false;
            this.water = false;
            this.minBreakSeconds = 0.0F;
            this.staticDefenseScore = 0.0F;
            this.easeScore = 0.0F;
            this.distance = 0.0D;
            this.layerCount = 0;
            for (int i = 0; i < MAX_LAYER_ICONS; i++) {
                this.layers[i] = DefenseBlockKind.AIR;
            }
            this.totalDefenseBlocks = 0;
        }

        private void addUniqueLayer(DefenseBlockKind kind) {
            if (kind == null || kind == DefenseBlockKind.AIR || this.layerCount >= MAX_LAYER_ICONS) {
                return;
            }
            for (int i = 0; i < this.layerCount; i++) {
                if (this.layers[i] == kind) {
                    return;
                }
            }
            this.layers[this.layerCount++] = kind;
        }

        private void updateDistanceAndEase(double newDistance) {
            this.distance = newDistance;
            this.easeScore = computeEaseScore(
                this.nakedBed,
                this.minBreakSeconds,
                this.staticDefenseScore,
                newDistance
            );
        }

        public boolean isValid() {
            return this.valid;
        }

        public int getBedSlotIndex() {
            return this.bedSlotIndex;
        }

        public boolean isNakedBed() {
            return this.nakedBed;
        }

        public boolean hasBlastProofGlass() {
            return this.blastProofGlass;
        }

        public boolean hasWater() {
            return this.water;
        }

        public float getMinBreakSeconds() {
            return this.minBreakSeconds;
        }

        public float getStaticDefenseScore() {
            return this.staticDefenseScore;
        }

        public float getEaseScore() {
            return this.easeScore;
        }

        public double getDistance() {
            return this.distance;
        }

        public int getLayerCount() {
            return this.layerCount;
        }

        public DefenseBlockKind getLayerKind(int idx) {
            if (idx < 0 || idx >= this.layerCount) {
                return DefenseBlockKind.AIR;
            }
            return this.layers[idx];
        }

        public int getTotalDefenseBlocks() {
            return this.totalDefenseBlocks;
        }
    }

    /**
     * Initializes pre-allocated bed entries and ranking buffers.
     */
    public BedDefenseAnalyzer() {
        for (int i = 0; i < MAX_BEDS; i++) {
            this.entries[i] = new DefenseEntry(i);
            this.rankedBedSlots[i] = -1;
        }
    }

    /**
     * Analyzes the 4-shell defense around a bed and computes its minimum break time and ease score.
     *
     * @param slotIndex   bed slot index in {@code [0, MAX_BEDS - 1]}
     * @param world       active client world
     * @param player      local player
     * @param headX       bed head X
     * @param headY       bed head Y
     * @param headZ       bed head Z
     * @param facingDir   bed facing index ({@code 0=SOUTH, 1=WEST, 2=NORTH, 3=EAST}, foot -&gt; head)
     * @param pairedFoot  whether the bed has a paired foot block
     * @param distance    current player-to-bed distance
     * @param quickSolver reusable {@link BedMiningPathfinder} instance for break-time solving
     */
    public void analyzeBed(
        int slotIndex,
        World world,
        EntityPlayer player,
        int headX,
        int headY,
        int headZ,
        byte facingDir,
        boolean pairedFoot,
        double distance,
        BedMiningPathfinder quickSolver
    ) {
        if (slotIndex < 0 || slotIndex >= MAX_BEDS) {
            return;
        }

        DefenseEntry entry = this.entries[slotIndex];
        entry.clear();
        entry.bedSlotIndex = slotIndex;
        entry.distance = distance;
        if (world == null) {
            return;
        }

        int fx = getFacingOffsetX(facingDir);
        int fz = getFacingOffsetZ(facingDir);
        int footX = pairedFoot ? headX - fx : headX;
        int footZ = pairedFoot ? headZ - fz : headZ;

        int originX = Math.min(headX, footX) - MAX_SHELL_DEPTH;
        int originY = headY;
        int originZ = Math.min(headZ, footZ) - MAX_SHELL_DEPTH;

        Arrays.fill(this.visited, false);
        int qHead = 0;
        int qTail = 0;

        int headIdx = bfsIndex(headX - originX, 0, headZ - originZ);
        this.visited[headIdx] = true;
        this.bfsDepth[headIdx] = 0;
        this.bfsQueue[qTail++] = headIdx;

        if (pairedFoot) {
            int footIdx = bfsIndex(footX - originX, 0, footZ - originZ);
            if (!this.visited[footIdx]) {
                this.visited[footIdx] = true;
                this.bfsDepth[footIdx] = 0;
                this.bfsQueue[qTail++] = footIdx;
            }
        }

        boolean hasGlass = false;
        boolean hasWater = false;
        float staticScore = 0.0F;
        int blockCount = 0;

        while (qHead < qTail) {
            int u = this.bfsQueue[qHead++];
            int depth = this.bfsDepth[u];
            if (depth >= MAX_SHELL_DEPTH) {
                continue;
            }

            int ux = indexToLx(u);
            int uy = indexToLy(u);
            int uz = indexToLz(u);
            int nextDepth = depth + 1;

            for (int dir = 0; dir < 6; dir++) {
                int nx = ux + DX[dir];
                int ny = uy + DY[dir];
                int nz = uz + DZ[dir];
                if (nx < 0 || nx >= BFS_X || ny < 0 || ny >= BFS_Y || nz < 0 || nz >= BFS_Z) {
                    continue;
                }
                int v = bfsIndex(nx, ny, nz);
                if (this.visited[v]) {
                    continue;
                }
                this.visited[v] = true;

                int wy = originY + ny;
                if (wy < 0 || wy > 255) {
                    continue;
                }
                this.scanPos.set(originX + nx, wy, originZ + nz);
                if (!world.isBlockLoaded(this.scanPos)) {
                    continue;
                }

                Block block = world.getBlockState(this.scanPos).getBlock();
                if (block == Blocks.bed) {
                    continue;
                }
                DefenseBlockKind kind = BedMiningMath.classifyBlock(block);
                // Never expand through air so open-air map structures never pollute defense shells.
                if (kind == DefenseBlockKind.AIR) {
                    continue;
                }

                blockCount++;
                if (kind == DefenseBlockKind.GLASS) {
                    hasGlass = true;
                } else if (kind == DefenseBlockKind.WATER) {
                    hasWater = true;
                }
                // Discount outer shells by depth so stray distant blocks do not outweigh inner layers.
                staticScore += kind.getWeight() / (float) nextDepth;
                entry.addUniqueLayer(kind);

                this.bfsDepth[v] = (byte) nextDepth;
                this.bfsQueue[qTail++] = v;
            }
        }

        float minBreak;
        boolean naked;
        if (quickSolver != null) {
            minBreak = quickSolver.solveMinBreakSecondsOnly(
                world,
                player,
                headX,
                headY,
                headZ,
                facingDir,
                pairedFoot
            );
            naked = quickSolver.hasValidPath() && quickSolver.isNakedBed();
        } else {
            naked = blockCount == 0;
            minBreak = naked ? 0.0F : staticScore;
        }

        entry.nakedBed = naked;
        entry.blastProofGlass = hasGlass;
        entry.water = hasWater;
        entry.minBreakSeconds = minBreak;
        entry.staticDefenseScore = staticScore;
        entry.totalDefenseBlocks = blockCount;
        entry.easeScore = computeEaseScore(naked, minBreak, staticScore, distance);
        entry.valid = true;
    }

    /**
     * Computes tactical ease score (lower means easier/faster bed to rush).
     *
     * @param nakedBed           whether the bed is exposed to reachable external air
     * @param minBreakSeconds    fastest mining duration to breach the bed
     * @param staticDefenseScore weighted 4-shell defense score
     * @param distance           distance to bed
     * @return ascending ease score
     */
    public static float computeEaseScore(
        boolean nakedBed,
        float minBreakSeconds,
        float staticDefenseScore,
        double distance
    ) {
        if (nakedBed) {
            return (float) (distance * 0.05D);
        }
        if (Float.isInfinite(minBreakSeconds) || Float.isNaN(minBreakSeconds)) {
            return Float.POSITIVE_INFINITY;
        }
        return minBreakSeconds * 10.0F + staticDefenseScore + (float) (distance * 0.08D);
    }

    /**
     * Refreshes distances and sorts eligible bed slot indices ascending by {@link DefenseEntry#getEaseScore()}.
     *
     * @param eligibleFlags per-slot eligibility flags (may be {@code null} to include all valid entries)
     * @param count         number of active bed slots to inspect
     * @param distances     per-slot updated distances (may be {@code null} to keep existing distances)
     */
    public void rebuildRanking(boolean[] eligibleFlags, int count, double[] distances) {
        this.rankedCount = 0;
        int limit = Math.min(count, MAX_BEDS);
        for (int i = 0; i < limit; i++) {
            if (eligibleFlags != null && (i >= eligibleFlags.length || !eligibleFlags[i])) {
                continue;
            }
            DefenseEntry entry = this.entries[i];
            if (!entry.isValid()) {
                continue;
            }
            if (distances != null && i < distances.length) {
                entry.updateDistanceAndEase(distances[i]);
            }
            this.rankedBedSlots[this.rankedCount++] = i;
        }

        // Zero-allocation insertion sort (MAX_BEDS <= 16).
        for (int i = 1; i < this.rankedCount; i++) {
            int keySlot = this.rankedBedSlots[i];
            DefenseEntry keyEntry = this.entries[keySlot];
            int j = i - 1;
            while (j >= 0 && compareEntries(this.entries[this.rankedBedSlots[j]], keyEntry) > 0) {
                this.rankedBedSlots[j + 1] = this.rankedBedSlots[j];
                j--;
            }
            this.rankedBedSlots[j + 1] = keySlot;
        }
    }

    private static int compareEntries(DefenseEntry a, DefenseEntry b) {
        int scoreCmp = Float.compare(a.easeScore, b.easeScore);
        if (scoreCmp != 0) {
            return scoreCmp;
        }
        int distCmp = Double.compare(a.distance, b.distance);
        if (distCmp != 0) {
            return distCmp;
        }
        return Integer.compare(a.bedSlotIndex, b.bedSlotIndex);
    }

    public DefenseEntry getEntry(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= MAX_BEDS) {
            return null;
        }
        return this.entries[slotIndex];
    }

    public int getRankedCount() {
        return this.rankedCount;
    }

    public int getRankedBedSlot(int rank) {
        if (rank < 0 || rank >= this.rankedCount) {
            return -1;
        }
        return this.rankedBedSlots[rank];
    }

    /**
     * Resets all cached defense entries and rankings.
     */
    public void clear() {
        for (int i = 0; i < MAX_BEDS; i++) {
            this.entries[i].clear();
            this.rankedBedSlots[i] = -1;
        }
        this.rankedCount = 0;
    }

    private static int bfsIndex(int lx, int ly, int lz) {
        return (ly * BFS_Z + lz) * BFS_X + lx;
    }

    private static int indexToLx(int idx) {
        return idx % BFS_X;
    }

    private static int indexToLy(int idx) {
        return idx / (BFS_X * BFS_Z);
    }

    private static int indexToLz(int idx) {
        return (idx / BFS_X) % BFS_Z;
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
}
