package com.hoptimization.modules.visual.jarvis;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Zero-allocation BedWars match detector, incremental bed scanner, and team-color resolver.
 *
 * <p>Tracks up to {@link #MAX_BEDS} beds via time-sliced ring chunk scanning, re-verifies loaded beds
 * periodically while preserving unloaded bed snapshots as {@link TrackedBed#STATUS_CACHED},
 * anchors the local team's home base, and associates enemy beds by player proximity and surrounding
 * dyed block voting.</p>
 */
public final class BedWarsContext {
    public static final int MAX_BEDS = 16;
    public static final int CHUNKS_PER_TICK = 6;

    public static final int MIN_SCAN_RANGE = 48;
    public static final int MAX_SCAN_RANGE = 160;
    public static final int VERTICAL_SCAN_RADIUS = 32;
    public static final int REVERIFY_INTERVAL_TICKS = 10;
    public static final double HOME_SPAWN_ANCHOR_RANGE = 28.0D;
    public static final double ENEMY_PLAYER_ASSOC_RANGE = 14.0D;
    public static final int TEAM_BLOCK_VOTE_RADIUS = 10;

    private static final int SWEEP_COOLDOWN_TICKS = 20;
    private static final double RESCAN_TELEPORT_DIST_SQ = 32.0D * 32.0D;
    private static final int DEFAULT_LEATHER_RGB = 0xA06540;
    private static final int MAX_UNLOADED_SKIPS_PER_TICK = 24;

    private static final String[] TEAM_KEYWORDS = {
        "red", "blue", "green", "yellow", "aqua", "white", "pink", "gray", "grey",
        "\u7ea2", "\u84dd", "\u7eff", "\u9ec4", "\u9752", "\u767d", "\u7c89", "\u7070"
    };

    /**
     * Canonical BedWars team colors with ARGB display tint, formatting code, and dye metadata.
     */
    public enum TeamColor {
        RED(0xFFEF4444, 'c', 14, "red"),
        BLUE(0xFF3B82F6, '9', 11, "blue"),
        GREEN(0xFF22C55E, 'a', 5, "green"),
        YELLOW(0xFFEAB308, 'e', 4, "yellow"),
        AQUA(0xFF06B6D4, 'b', 3, "aqua"),
        WHITE(0xFFF8FAFC, 'f', 0, "white"),
        PINK(0xFFEC4899, 'd', 6, "pink"),
        GRAY(0xFF94A3B8, '7', 7, "gray"),
        UNKNOWN(0xFFCBD5E1, 'r', -1, "unknown");

        private final int argb;
        private final char chatCode;
        private final int dyeMeta;
        private final String id;
        private final String langKey;

        TeamColor(int argb, char chatCode, int dyeMeta, String id) {
            this.argb = argb;
            this.chatCode = chatCode;
            this.dyeMeta = dyeMeta;
            this.id = id;
            this.langKey = "ui.hoptimization.jarvis.team." + id;
        }

        public int getArgb() {
            return this.argb;
        }

        public char getChatCode() {
            return this.chatCode;
        }

        public int getDyeMeta() {
            return this.dyeMeta;
        }

        public String getId() {
            return this.id;
        }

        public String getLangKey() {
            return this.langKey;
        }

        /**
         * Resolves a Minecraft chat color character (case-insensitive) into a {@link TeamColor}.
         *
         * @param code chat formatting character following section sign
         * @return matched team color, or {@link #UNKNOWN}
         */
        public static TeamColor fromChatColorChar(char code) {
            char lower = (code >= 'A' && code <= 'Z') ? (char) (code + ('a' - 'A')) : code;
            switch (lower) {
                case 'c':
                case '4':
                    return RED;
                case '9':
                case '1':
                    return BLUE;
                case 'a':
                case '2':
                    return GREEN;
                case 'e':
                    return YELLOW;
                case 'b':
                case '3':
                    return AQUA;
                case 'f':
                    return WHITE;
                case 'd':
                    return PINK;
                case '7':
                case '8':
                    return GRAY;
                default:
                    return UNKNOWN;
            }
        }

        /**
         * Resolves a 1.8.9 wool/clay/glass dye metadata value into a {@link TeamColor}.
         *
         * @param meta block dye metadata ({@code 0..15})
         * @return matched team color, or {@link #UNKNOWN}
         */
        public static TeamColor fromDyeMeta(int meta) {
            switch (meta) {
                case 14:
                    return RED;
                case 11:
                    return BLUE;
                case 5:
                    return GREEN;
                case 4:
                    return YELLOW;
                case 3:
                    return AQUA;
                case 0:
                    return WHITE;
                case 6:
                    return PINK;
                case 7:
                    return GRAY;
                default:
                    return UNKNOWN;
            }
        }

        /**
         * Resolves a leather armor RGB/ARGB color into a {@link TeamColor}, ignoring default undyed leather.
         *
         * @param rgb color value from {@link ItemArmor#getColor(ItemStack)} or ARGB tint
         * @return matched team color, or {@link #UNKNOWN}
         */
        public static TeamColor fromArmorColor(int rgb) {
            if (rgb == -1) {
                return UNKNOWN;
            }
            int c = rgb & 0x00FFFFFF;
            if (c == DEFAULT_LEATHER_RGB || c == 0) {
                return UNKNOWN;
            }

            // Fast exact match against enum RGBs and common Bukkit/MC team leather dye values.
            if (c == (RED.argb & 0x00FFFFFF) || c == 0xFF0000 || c == 0x993333 || c == 0xCC4C4C || c == 0xFF5555) {
                return RED;
            }
            if (c == (BLUE.argb & 0x00FFFFFF) || c == 0x0000FF || c == 0x334CB2 || c == 0x5555FF) {
                return BLUE;
            }
            if (c == (GREEN.argb & 0x00FFFFFF) || c == 0x00FF00 || c == 0x7FCC19 || c == 0x55FF55 || c == 0x667F33) {
                return GREEN;
            }
            if (c == (YELLOW.argb & 0x00FFFFFF) || c == 0xFFFF00 || c == 0xE5E533 || c == 0xFFFF55) {
                return YELLOW;
            }
            if (c == (AQUA.argb & 0x00FFFFFF) || c == 0x00FFFF || c == 0x6699D8 || c == 0x55FFFF || c == 0x4C7F99) {
                return AQUA;
            }
            if (c == (WHITE.argb & 0x00FFFFFF) || c == 0xFFFFFF) {
                return WHITE;
            }
            if (c == (PINK.argb & 0x00FFFFFF) || c == 0xFF00FF || c == 0xF27FA5 || c == 0xFF55FF || c == 0xFF69B4) {
                return PINK;
            }
            if (c == (GRAY.argb & 0x00FFFFFF) || c == 0x808080 || c == 0x4C4C4C || c == 0x999999 || c == 0x555555) {
                return GRAY;
            }

            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;

            int max = r > g ? (r > b ? r : b) : (g > b ? g : b);
            int min = r < g ? (r < b ? r : b) : (g < b ? g : b);
            int delta = max - min;

            if (delta <= 42) {
                if (min >= 200) {
                    return WHITE;
                }
                if (max >= 40) {
                    return GRAY;
                }
                return UNKNOWN;
            }

            float hue;
            if (max == r) {
                hue = 60.0F * ((float) (g - b) / (float) delta);
                if (hue < 0.0F) {
                    hue += 360.0F;
                }
            } else if (max == g) {
                hue = 60.0F * (((float) (b - r) / (float) delta) + 2.0F);
            } else {
                hue = 60.0F * (((float) (r - g) / (float) delta) + 4.0F);
            }

            if (hue >= 348.0F || hue < 18.0F) {
                return RED;
            }
            if (hue >= 35.0F && hue < 75.0F) {
                return YELLOW;
            }
            if (hue >= 75.0F && hue < 165.0F) {
                return GREEN;
            }
            if (hue >= 165.0F && hue < 215.5F) {
                return AQUA;
            }
            if (hue >= 215.5F && hue < 265.0F) {
                return BLUE;
            }
            if (hue >= 280.0F && hue < 348.0F) {
                return PINK;
            }
            return UNKNOWN;
        }
    }

    /**
     * Mutable pre-allocated snapshot of a tracked bed structure.
     */
    public static final class TrackedBed {
        public static final byte STATUS_LIVE = 0;
        public static final byte STATUS_CACHED = 1;
        public static final byte STATUS_DESTROYED = 2;

        private final int index;
        private int headX;
        private int headY;
        private int headZ;
        private byte facingDir;
        private boolean pairedFoot;
        private TeamColor teamColor = TeamColor.UNKNOWN;
        private TeamColor votedColor = TeamColor.UNKNOWN;
        private TeamColor playerColor = TeamColor.UNKNOWN;
        private boolean ownBed;
        private byte status = STATUS_LIVE;
        private int lastSeenTick;
        private boolean active;

        private TrackedBed(int index) {
            this.index = index;
        }

        private void resetSlot() {
            this.headX = 0;
            this.headY = 0;
            this.headZ = 0;
            this.facingDir = 0;
            this.pairedFoot = false;
            this.teamColor = TeamColor.UNKNOWN;
            this.votedColor = TeamColor.UNKNOWN;
            this.playerColor = TeamColor.UNKNOWN;
            this.ownBed = false;
            this.status = STATUS_LIVE;
            this.lastSeenTick = 0;
            this.active = false;
        }

        public int getIndex() {
            return this.index;
        }

        public int getHeadX() {
            return this.headX;
        }

        public int getHeadY() {
            return this.headY;
        }

        public int getHeadZ() {
            return this.headZ;
        }

        public byte getFacingDir() {
            return this.facingDir;
        }

        public boolean isPairedFoot() {
            return this.pairedFoot;
        }

        public TeamColor getTeamColor() {
            return this.teamColor;
        }

        public boolean isOwnBed() {
            return this.ownBed;
        }

        public byte getStatus() {
            return this.status;
        }

        public int getLastSeenTick() {
            return this.lastSeenTick;
        }

        public boolean isActive() {
            return this.active;
        }

        public int getFootX() {
            return this.pairedFoot ? this.headX - getFacingOffsetX(this.facingDir) : this.headX;
        }

        public int getFootZ() {
            return this.pairedFoot ? this.headZ - getFacingOffsetZ(this.facingDir) : this.headZ;
        }

        public double getCenterX() {
            return (double) this.headX + 0.5D
                - (this.pairedFoot ? (double) getFacingOffsetX(this.facingDir) * 0.5D : 0.0D);
        }

        public double getCenterY() {
            return (double) this.headY + 0.3D;
        }

        public double getCenterZ() {
            return (double) this.headZ + 0.5D
                - (this.pairedFoot ? (double) getFacingOffsetZ(this.facingDir) * 0.5D : 0.0D);
        }

        public double distSqTo(double x, double y, double z) {
            double dx = getCenterX() - x;
            double dy = getCenterY() - y;
            double dz = getCenterZ() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final TrackedBed[] beds = new TrackedBed[MAX_BEDS];
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
    private final StringBuilder scoreboardScratch = new StringBuilder(64);
    private final StringBuilder lineScratch = new StringBuilder(64);
    private final StringBuilder cleanLineScratch = new StringBuilder(64);
    private final int[] teamVoteCounts = new int[TeamColor.values().length];

    private int bedCount;
    private int tickCounter;
    private World lastWorld;
    private ScoreObjective lastSidebarObjective;

    private boolean inBedWarsMatch;
    private boolean lastEffectiveActive;
    private TeamColor localTeamColor = TeamColor.UNKNOWN;

    private boolean homeAnchored;
    private double homeX;
    private double homeY;
    private double homeZ;
    private int ownBedIndex = -1;
    private boolean anchorPending = true;

    // Incremental ring sweep state
    private boolean scanInProgress;
    private boolean forceImmediateSweep = true;
    private int lastScanRange = -1;
    private double lastSweepPlayerX;
    private double lastSweepPlayerY;
    private double lastSweepPlayerZ;
    private int sweepCenterCx;
    private int sweepCenterCz;
    private int sweepMinX;
    private int sweepMaxX;
    private int sweepMinY;
    private int sweepMaxY;
    private int sweepMinZ;
    private int sweepMaxZ;
    private int sweepMinCx;
    private int sweepMaxCx;
    private int sweepMinCz;
    private int sweepMaxCz;
    private int sweepMaxRing;
    private int currentRing;
    private int ringEdge;
    private int ringStep;
    private int cursorCx;
    private int cursorCz;
    private int ticksSinceSweepComplete;
    private int voteRefreshBedCursor;
    private boolean bedAddedThisTick;

    /**
     * Constructs a reusable BedWars context with pre-allocated bed slots.
     */
    public BedWarsContext() {
        for (int i = 0; i < MAX_BEDS; i++) {
            this.beds[i] = new TrackedBed(i);
        }
    }

    /**
     * Advances match detection, local team resolution, incremental bed scanning, bed verification,
     * home anchoring, and enemy bed team association for the current client tick.
     *
     * @param mc           Minecraft client instance
     * @param alwaysActive true to treat any world as active (singleplayer / sandbox testing)
     * @param scanRange    configured horizontal scan range in blocks (clamped to {@code 48..160})
     */
    public void update(Minecraft mc, boolean alwaysActive, int scanRange) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            reset();
            return;
        }

        World world = mc.theWorld;
        EntityPlayer player = mc.thePlayer;
        if (world != this.lastWorld) {
            reset();
            this.lastWorld = world;
        }

        boolean wasMatch = this.inBedWarsMatch;
        updateMatchDetection(world);
        if (!wasMatch && this.inBedWarsMatch) {
            // Entering a BedWars match should anchor on spawn near our home bed.
            this.anchorPending = true;
            this.homeAnchored = false;
            this.ownBedIndex = -1;
            clearOwnBedFlags();
            this.forceImmediateSweep = true;
        }

        boolean effectiveActive = alwaysActive || this.inBedWarsMatch;
        if (!effectiveActive) {
            if (this.lastEffectiveActive) {
                clearMatchStatePreserveMatchFlag();
            }
            this.lastEffectiveActive = false;
            this.tickCounter++;
            return;
        }
        this.lastEffectiveActive = true;

        updateLocalTeamColor(player);

        this.bedAddedThisTick = false;
        int clampedRange = clampScanRange(scanRange);
        stepIncrementalChunkScan(world, player, clampedRange);

        if (this.tickCounter % REVERIFY_INTERVAL_TICKS == 0) {
            reverifyTrackedBeds(world);
            refreshOneBedBlockVotes(world);
            updateEnemyBedPlayerAssociations(world, player, mc);
        } else if (this.bedAddedThisTick) {
            updateEnemyBedPlayerAssociations(world, player, mc);
        }

        tryAnchorHomeBase(player, alwaysActive);

        this.tickCounter++;
    }

    /**
     * Inspects incoming unformatted or formatted chat lines for BedWars match start and bed destruction cues.
     *
     * @param unformattedText chat message text
     */
    public void onChatMessage(String unformattedText) {
        if (unformattedText == null || unformattedText.isEmpty()) {
            return;
        }
        stripFormattingInto(unformattedText, this.lineScratch);
        if (this.lineScratch.length() == 0) {
            return;
        }

        if (containsIgnoreCase(this.lineScratch, "Protect your bed")
            || containsSequence(this.lineScratch, "\u4fdd\u62a4\u4f60\u7684\u5e8a")
            || containsSequence(this.lineScratch, "\u6e38\u620f\u5f00\u59cb")) {
            this.anchorPending = true;
            this.homeAnchored = false;
            this.ownBedIndex = -1;
            clearOwnBedFlags();
            this.scanInProgress = false;
            this.forceImmediateSweep = true;
            return;
        }

        // Ignore player-typed chat messages containing ": " before system destruction keywords.
        boolean isBedDestruction = containsIgnoreCase(this.lineScratch, "BED DESTRUCTION")
            || containsSequence(this.lineScratch, "\u5e8a\u88ab\u7834\u574f")
            || containsSequence(this.lineScratch, "\u5e8a\u5df2\u88ab\u6467\u6bc1");
        boolean isOwnBedLost = containsIgnoreCase(this.lineScratch, "Your bed was destroyed")
            || containsSequence(this.lineScratch, "\u4f60\u7684\u5e8a\u88ab\u7834\u574f")
            || containsSequence(this.lineScratch, "\u4f60\u7684\u5e8a\u5df2\u88ab\u6467\u6bc1");

        if (!isBedDestruction && !isOwnBedLost) {
            return;
        }
        if (indexOfColonSpace(this.lineScratch) >= 0) {
            return;
        }

        if (isOwnBedLost) {
            TrackedBed own = findOwnBed();
            if (own != null) {
                own.status = TrackedBed.STATUS_DESTROYED;
            }
            return;
        }

        TeamColor destroyedTeam = detectTeamMention(this.lineScratch);
        if (destroyedTeam != TeamColor.UNKNOWN) {
            for (int i = 0; i < this.bedCount; i++) {
                TrackedBed bed = this.beds[i];
                if (bed.active && bed.teamColor == destroyedTeam) {
                    bed.status = TrackedBed.STATUS_DESTROYED;
                }
            }
        }
    }

    /**
     * Checks whether a player entity is a valid living enemy player (filtering local player,
     * spectators, teammates, NPCs/shopkeepers, malformed profiles, and tab-missing bots).
     *
     * @param target candidate player entity
     * @param local  local client player
     * @param mc     Minecraft client instance
     * @return {@code true} if the target is a valid enemy player
     */
    public static boolean isValidEnemyPlayer(EntityPlayer target, EntityPlayer local, Minecraft mc) {
        if (target == null || target == local) {
            return false;
        }
        if (!target.isEntityAlive() || target.isDead || target.getHealth() <= 0.0F) {
            return false;
        }
        if (target.isSpectator()) {
            return false;
        }

        UUID uuid = target.getUniqueID();
        if (uuid == null || uuid.version() == 2) {
            return false;
        }

        GameProfile profile = target.getGameProfile();
        if (profile == null) {
            return false;
        }
        String profileName = profile.getName();
        if (profileName == null || profileName.isEmpty()
            || profileName.indexOf(' ') >= 0 || profileName.indexOf('\u00a7') >= 0) {
            return false;
        }

        if (mc != null) {
            NetHandlerPlayClient netHandler = mc.getNetHandler();
            if (netHandler != null && netHandler.getPlayerInfo(uuid) == null) {
                return false;
            }
        }

        if (local != null) {
            if (local.isOnSameTeam(target)) {
                return false;
            }
            TeamColor localColor = resolvePlayerTeamColor(local);
            if (localColor != TeamColor.UNKNOWN) {
                TeamColor targetColor = resolvePlayerTeamColor(target);
                if (targetColor == localColor) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Resolves a player's BedWars team color from scoreboard team prefix, formatted display name,
     * or equipped leather armor dye color.
     *
     * @param player target player
     * @return resolved {@link TeamColor}, or {@link TeamColor#UNKNOWN}
     */
    public static TeamColor resolvePlayerTeamColor(EntityPlayer player) {
        if (player == null) {
            return TeamColor.UNKNOWN;
        }

        Team team = player.getTeam();
        if (team instanceof ScorePlayerTeam) {
            ScorePlayerTeam scoreTeam = (ScorePlayerTeam) team;
            TeamColor fromPrefix = extractColorFromFormattedText(scoreTeam.getColorPrefix());
            if (fromPrefix != TeamColor.UNKNOWN) {
                return fromPrefix;
            }
            TeamColor fromFormat = fromChatFormatting(scoreTeam.getChatFormat());
            if (fromFormat != TeamColor.UNKNOWN) {
                return fromFormat;
            }
        }

        IChatComponent displayName = player.getDisplayName();
        if (displayName != null) {
            TeamColor fromDisplay = extractColorFromFormattedText(displayName.getFormattedText());
            if (fromDisplay != TeamColor.UNKNOWN) {
                return fromDisplay;
            }
        }

        if (player.inventory != null) {
            for (int slot = 3; slot >= 0; slot--) {
                ItemStack armorStack = player.getCurrentArmor(slot);
                if (armorStack != null && armorStack.getItem() instanceof ItemArmor) {
                    ItemArmor armor = (ItemArmor) armorStack.getItem();
                    TeamColor fromArmor = TeamColor.fromArmorColor(armor.getColor(armorStack));
                    if (fromArmor != TeamColor.UNKNOWN) {
                        return fromArmor;
                    }
                }
            }
        }

        return TeamColor.UNKNOWN;
    }

    public boolean isInBedWarsMatch() {
        return this.inBedWarsMatch;
    }

    public boolean isEffectiveActive() {
        return this.lastEffectiveActive;
    }

    public TeamColor getLocalTeamColor() {
        return this.localTeamColor;
    }

    public boolean hasHomeAnchor() {
        return this.homeAnchored;
    }

    public boolean hasOwnBed() {
        TrackedBed own = findOwnBed();
        return own != null && own.status != TrackedBed.STATUS_DESTROYED;
    }

    public double getHomeX() {
        return this.homeX;
    }

    public double getHomeY() {
        return this.homeY;
    }

    public double getHomeZ() {
        return this.homeZ;
    }

    public int getBedCount() {
        return this.bedCount;
    }

    public TrackedBed getBed(int index) {
        if (index < 0 || index >= this.bedCount) {
            return null;
        }
        return this.beds[index];
    }

    public TrackedBed findOwnBed() {
        if (this.ownBedIndex >= 0 && this.ownBedIndex < this.bedCount) {
            TrackedBed candidate = this.beds[this.ownBedIndex];
            if (candidate.active && candidate.ownBed) {
                return candidate;
            }
        }
        for (int i = 0; i < this.bedCount; i++) {
            TrackedBed bed = this.beds[i];
            if (bed.active && bed.ownBed) {
                this.ownBedIndex = i;
                return bed;
            }
        }
        return null;
    }

    /**
     * Clears tracked beds, home anchor, team state, and scan progress while preserving world reference.
     */
    public void clear() {
        clearMatchStatePreserveMatchFlag();
        this.inBedWarsMatch = false;
        this.lastEffectiveActive = false;
        this.lastSidebarObjective = null;
    }

    /**
     * Performs a full context reset, including world and tick tracking.
     */
    public void reset() {
        clear();
        this.lastWorld = null;
        this.tickCounter = 0;
    }

    /**
     * Returns the X step from foot block to head block for the given horizontal facing index.
     *
     * @param dir {@code 0=SOUTH, 1=WEST, 2=NORTH, 3=EAST}
     * @return {@code -1}, {@code 0}, or {@code +1}
     */
    public static int getFacingOffsetX(int dir) {
        if (dir == 1) {
            return -1;
        }
        if (dir == 3) {
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
        if (dir == 0) {
            return 1;
        }
        if (dir == 2) {
            return -1;
        }
        return 0;
    }

    private void clearMatchStatePreserveMatchFlag() {
        for (int i = 0; i < MAX_BEDS; i++) {
            this.beds[i].resetSlot();
        }
        this.bedCount = 0;
        this.localTeamColor = TeamColor.UNKNOWN;
        this.homeAnchored = false;
        this.homeX = 0.0D;
        this.homeY = 0.0D;
        this.homeZ = 0.0D;
        this.ownBedIndex = -1;
        this.anchorPending = true;
        this.scanInProgress = false;
        this.forceImmediateSweep = true;
        this.lastScanRange = -1;
        this.ticksSinceSweepComplete = 0;
        this.voteRefreshBedCursor = 0;
    }

    private void clearOwnBedFlags() {
        for (int i = 0; i < this.bedCount; i++) {
            this.beds[i].ownBed = false;
        }
    }

    private void updateMatchDetection(World world) {
        Scoreboard scoreboard = world.getScoreboard();
        if (scoreboard == null) {
            this.inBedWarsMatch = false;
            this.lastSidebarObjective = null;
            return;
        }
        ScoreObjective sidebar = scoreboard.getObjectiveInDisplaySlot(1);
        if (sidebar == null) {
            this.inBedWarsMatch = false;
            this.lastSidebarObjective = null;
            return;
        }

        stripFormattingInto(sidebar.getDisplayName(), this.scoreboardScratch);
        if (containsIgnoreCase(this.scoreboardScratch, "BED WARS")
            || containsIgnoreCase(this.scoreboardScratch, "BEDWARS")
            || containsSequence(this.scoreboardScratch, "\u8d77\u5e8a\u6218\u4e89")
            || containsSequence(this.scoreboardScratch, "\u8d77\u5e8a")) {
            this.inBedWarsMatch = true;
            this.lastSidebarObjective = sidebar;
            return;
        }

        // Only inspect individual scoreboard rows periodically (or on objective switch) because
        // Scoreboard#getSortedScores allocates a temporary list inside MC 1.8.9.
        if (this.tickCounter % 20 == 0 || sidebar != this.lastSidebarObjective) {
            this.lastSidebarObjective = sidebar;
            this.inBedWarsMatch = hasBedWarsTeamStatusLine(scoreboard, sidebar);
        }
    }

    private boolean hasBedWarsTeamStatusLine(Scoreboard scoreboard, ScoreObjective sidebar) {
        Collection<Score> scores = scoreboard.getSortedScores(sidebar);
        if (scores == null || scores.isEmpty()) {
            return false;
        }
        if (scores instanceof List<?>) {
            List<Score> list = (List<Score>) scores;
            for (int i = 0, size = list.size(); i < size; i++) {
                if (isTeamStatusScore(scoreboard, list.get(i))) {
                    return true;
                }
            }
            return false;
        }
        for (Score score : scores) {
            if (isTeamStatusScore(scoreboard, score)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTeamStatusScore(Scoreboard scoreboard, Score score) {
        if (score == null) {
            return false;
        }
        String entryName = score.getPlayerName();
        if (entryName == null) {
            return false;
        }
        this.lineScratch.setLength(0);
        ScorePlayerTeam team = scoreboard.getPlayersTeam(entryName);
        if (team != null) {
            String prefix = team.getColorPrefix();
            if (prefix != null) {
                this.lineScratch.append(prefix);
            }
            this.lineScratch.append(entryName);
            String suffix = team.getColorSuffix();
            if (suffix != null) {
                this.lineScratch.append(suffix);
            }
        } else {
            this.lineScratch.append(entryName);
        }

        stripFormattingInto(this.lineScratch, this.cleanLineScratch);
        return hasTeamStatusMarker(this.cleanLineScratch) && hasTeamKeyword(this.cleanLineScratch);
    }

    private void updateLocalTeamColor(EntityPlayer player) {
        // Fast zero-alloc check against ScorePlayerTeam prefix first on every tick.
        Team team = player.getTeam();
        if (team instanceof ScorePlayerTeam) {
            ScorePlayerTeam scoreTeam = (ScorePlayerTeam) team;
            TeamColor fromPrefix = extractColorFromFormattedText(scoreTeam.getColorPrefix());
            if (fromPrefix != TeamColor.UNKNOWN) {
                this.localTeamColor = fromPrefix;
                return;
            }
            TeamColor fromFormat = fromChatFormatting(scoreTeam.getChatFormat());
            if (fromFormat != TeamColor.UNKNOWN) {
                this.localTeamColor = fromFormat;
                return;
            }
        }

        // Fall back to displayName + leather armor on initial/periodic ticks to avoid allocating every tick.
        if (this.localTeamColor == TeamColor.UNKNOWN || this.tickCounter % 10 == 0) {
            TeamColor resolved = resolvePlayerTeamColor(player);
            if (resolved != TeamColor.UNKNOWN) {
                this.localTeamColor = resolved;
            }
        }
    }

    private void stepIncrementalChunkScan(World world, EntityPlayer player, int radius) {
        double dx = player.posX - this.lastSweepPlayerX;
        double dy = player.posY - this.lastSweepPlayerY;
        double dz = player.posZ - this.lastSweepPlayerZ;
        double movedDistSq = dx * dx + dy * dy + dz * dz;

        boolean shouldStartSweep = this.forceImmediateSweep
            || radius != this.lastScanRange
            || movedDistSq > RESCAN_TELEPORT_DIST_SQ
            || (!this.scanInProgress && this.ticksSinceSweepComplete >= SWEEP_COOLDOWN_TICKS);

        if (shouldStartSweep) {
            beginSweep(player, radius);
        }

        if (!this.scanInProgress) {
            this.ticksSinceSweepComplete++;
            return;
        }

        int chunksScanned = 0;
        int emptySkips = 0;
        while (chunksScanned < CHUNKS_PER_TICK && emptySkips < MAX_UNLOADED_SKIPS_PER_TICK && nextChunkInSweep()) {
            if (scanChunk(world, this.cursorCx, this.cursorCz)) {
                chunksScanned++;
            } else {
                emptySkips++;
            }
        }

        if (!this.scanInProgress) {
            this.ticksSinceSweepComplete = 0;
        }
    }

    private void beginSweep(EntityPlayer player, int radius) {
        int px = MathHelper.floor_double(player.posX);
        int py = MathHelper.floor_double(player.posY);
        int pz = MathHelper.floor_double(player.posZ);

        this.sweepMinX = px - radius;
        this.sweepMaxX = px + radius;
        this.sweepMinY = Math.max(0, py - VERTICAL_SCAN_RADIUS);
        this.sweepMaxY = Math.min(255, py + VERTICAL_SCAN_RADIUS);
        this.sweepMinZ = pz - radius;
        this.sweepMaxZ = pz + radius;

        this.sweepCenterCx = px >> 4;
        this.sweepCenterCz = pz >> 4;
        this.sweepMinCx = this.sweepMinX >> 4;
        this.sweepMaxCx = this.sweepMaxX >> 4;
        this.sweepMinCz = this.sweepMinZ >> 4;
        this.sweepMaxCz = this.sweepMaxZ >> 4;
        this.sweepMaxRing = Math.max(
            Math.max(this.sweepCenterCx - this.sweepMinCx, this.sweepMaxCx - this.sweepCenterCx),
            Math.max(this.sweepCenterCz - this.sweepMinCz, this.sweepMaxCz - this.sweepCenterCz)
        );

        this.currentRing = 0;
        this.ringEdge = 0;
        this.ringStep = this.sweepCenterCx;
        this.scanInProgress = this.sweepMinY <= this.sweepMaxY;
        this.forceImmediateSweep = false;
        this.lastScanRange = radius;
        this.lastSweepPlayerX = player.posX;
        this.lastSweepPlayerY = player.posY;
        this.lastSweepPlayerZ = player.posZ;
        this.ticksSinceSweepComplete = 0;
    }

    private boolean nextChunkInSweep() {
        while (this.currentRing <= this.sweepMaxRing) {
            int r = this.currentRing;
            if (r == 0) {
                this.currentRing = 1;
                this.ringEdge = 0;
                this.ringStep = Math.max(this.sweepMinCx, this.sweepCenterCx - 1);
                if (this.sweepCenterCx >= this.sweepMinCx && this.sweepCenterCx <= this.sweepMaxCx
                    && this.sweepCenterCz >= this.sweepMinCz && this.sweepCenterCz <= this.sweepMaxCz) {
                    this.cursorCx = this.sweepCenterCx;
                    this.cursorCz = this.sweepCenterCz;
                    return true;
                }
                continue;
            }

            int northCz = this.sweepCenterCz - r;
            int southCz = this.sweepCenterCz + r;
            int westCx = this.sweepCenterCx - r;
            int eastCx = this.sweepCenterCx + r;

            if (this.ringEdge == 0) {
                int cxEnd = Math.min(this.sweepMaxCx, eastCx);
                if (northCz >= this.sweepMinCz && northCz <= this.sweepMaxCz && this.ringStep <= cxEnd) {
                    this.cursorCx = this.ringStep++;
                    this.cursorCz = northCz;
                    return true;
                }
                this.ringEdge = 1;
                this.ringStep = Math.max(this.sweepMinCx, westCx);
            }
            if (this.ringEdge == 1) {
                int cxEnd = Math.min(this.sweepMaxCx, eastCx);
                if (southCz >= this.sweepMinCz && southCz <= this.sweepMaxCz && this.ringStep <= cxEnd) {
                    this.cursorCx = this.ringStep++;
                    this.cursorCz = southCz;
                    return true;
                }
                this.ringEdge = 2;
                this.ringStep = Math.max(this.sweepMinCz, northCz + 1);
            }
            if (this.ringEdge == 2) {
                int czEnd = Math.min(this.sweepMaxCz, southCz - 1);
                if (westCx >= this.sweepMinCx && westCx <= this.sweepMaxCx && this.ringStep <= czEnd) {
                    this.cursorCx = westCx;
                    this.cursorCz = this.ringStep++;
                    return true;
                }
                this.ringEdge = 3;
                this.ringStep = Math.max(this.sweepMinCz, northCz + 1);
            }
            if (this.ringEdge == 3) {
                int czEnd = Math.min(this.sweepMaxCz, southCz - 1);
                if (eastCx >= this.sweepMinCx && eastCx <= this.sweepMaxCx && this.ringStep <= czEnd) {
                    this.cursorCx = eastCx;
                    this.cursorCz = this.ringStep++;
                    return true;
                }
            }

            this.currentRing++;
            this.ringEdge = 0;
            this.ringStep = Math.max(this.sweepMinCx, this.sweepCenterCx - this.currentRing);
        }

        this.scanInProgress = false;
        return false;
    }

    private boolean scanChunk(World world, int cx, int cz) {
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        if (sections == null) {
            return false;
        }

        int cMinX = Math.max(this.sweepMinX, cx << 4);
        int cMaxX = Math.min(this.sweepMaxX, (cx << 4) + 15);
        int cMinZ = Math.max(this.sweepMinZ, cz << 4);
        int cMaxZ = Math.min(this.sweepMaxZ, (cz << 4) + 15);
        int minSec = this.sweepMinY >> 4;
        int maxSec = Math.min(sections.length - 1, this.sweepMaxY >> 4);
        Block bedBlock = Blocks.bed;

        for (int sec = minSec; sec <= maxSec; sec++) {
            ExtendedBlockStorage storage = sections[sec];
            if (storage == null || storage.isEmpty()) {
                continue;
            }
            int secMinY = Math.max(this.sweepMinY, sec << 4);
            int secMaxY = Math.min(this.sweepMaxY, (sec << 4) + 15);

            for (int y = secMinY; y <= secMaxY; y++) {
                int localY = y & 15;
                for (int z = cMinZ; z <= cMaxZ; z++) {
                    int localZ = z & 15;
                    for (int x = cMinX; x <= cMaxX; x++) {
                        this.scanPos.set(x, y, z);
                        IBlockState state = storage.get(this.scanPos.getX() & 15, localY, localZ);
                        if (state.getBlock() != bedBlock) {
                            continue;
                        }
                        handleBedBlock(world, bedBlock, state, x, y, z);
                    }
                }
            }
        }
        return true;
    }

    private void handleBedBlock(World world, Block bedBlock, IBlockState state, int x, int y, int z) {
        int meta = bedBlock.getMetaFromState(state);
        boolean isHead = (meta & 8) != 0;
        byte dir = (byte) (meta & 3);
        int fx = getFacingOffsetX(dir);
        int fz = getFacingOffsetZ(dir);

        if (isHead) {
            int footX = x - fx;
            int footZ = z - fz;
            boolean hasFoot = isLoadedBedPart(world, bedBlock, footX, y, footZ, false);
            addOrUpdateBed(world, x, y, z, dir, hasFoot);
            return;
        }

        int partnerHeadX = x + fx;
        int partnerHeadZ = z + fz;
        Chunk headChunk = world.getChunkFromChunkCoords(partnerHeadX >> 4, partnerHeadZ >> 4);
        if (headChunk != null && !headChunk.isEmpty()) {
            this.scanPos.set(partnerHeadX, y, partnerHeadZ);
            IBlockState headState = headChunk.getBlockState(this.scanPos);
            if (headState.getBlock() == bedBlock) {
                int headMeta = bedBlock.getMetaFromState(headState);
                if ((headMeta & 8) != 0) {
                    addOrUpdateBed(world, partnerHeadX, y, partnerHeadZ, (byte) (headMeta & 3), true);
                    return;
                }
            }
        }

        addOrUpdateBed(world, x, y, z, dir, false);
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

    private void addOrUpdateBed(World world, int hx, int hy, int hz, byte dir, boolean hasFoot) {
        // Match existing head coordinates, or upgrade an earlier orphan-foot entry when its head loads.
        int fx = getFacingOffsetX(dir);
        int fz = getFacingOffsetZ(dir);
        int footX = hx - fx;
        int footZ = hz - fz;

        for (int i = 0; i < this.bedCount; i++) {
            TrackedBed existing = this.beds[i];
            if (!existing.active) {
                continue;
            }
            if (existing.headX == hx && existing.headY == hy && existing.headZ == hz) {
                existing.facingDir = dir;
                existing.pairedFoot = hasFoot;
                existing.status = TrackedBed.STATUS_LIVE;
                existing.lastSeenTick = this.tickCounter;
                if (existing.votedColor == TeamColor.UNKNOWN) {
                    existing.votedColor = voteBedTeamColorFromBlocks(world, hx, hy, hz);
                    recomputeBedTeamColor(existing);
                }
                return;
            }
            if (hasFoot && !existing.pairedFoot
                && existing.headX == footX && existing.headY == hy && existing.headZ == footZ) {
                existing.headX = hx;
                existing.headZ = hz;
                existing.facingDir = dir;
                existing.pairedFoot = true;
                existing.status = TrackedBed.STATUS_LIVE;
                existing.lastSeenTick = this.tickCounter;
                if (existing.votedColor == TeamColor.UNKNOWN) {
                    existing.votedColor = voteBedTeamColorFromBlocks(world, hx, hy, hz);
                    recomputeBedTeamColor(existing);
                }
                return;
            }
        }

        TrackedBed slot = allocateBedSlot();
        if (slot == null) {
            return;
        }
        slot.resetSlot();
        slot.active = true;
        slot.headX = hx;
        slot.headY = hy;
        slot.headZ = hz;
        slot.facingDir = dir;
        slot.pairedFoot = hasFoot;
        slot.status = TrackedBed.STATUS_LIVE;
        slot.lastSeenTick = this.tickCounter;
        slot.votedColor = voteBedTeamColorFromBlocks(world, hx, hy, hz);
        recomputeBedTeamColor(slot);
        this.bedAddedThisTick = true;
    }

    private TrackedBed allocateBedSlot() {
        if (this.bedCount < MAX_BEDS) {
            return this.beds[this.bedCount++];
        }
        for (int i = 0; i < MAX_BEDS; i++) {
            TrackedBed candidate = this.beds[i];
            if (!candidate.active) {
                return candidate;
            }
        }
        for (int i = 0; i < MAX_BEDS; i++) {
            TrackedBed candidate = this.beds[i];
            if (!candidate.ownBed && candidate.status == TrackedBed.STATUS_DESTROYED) {
                return candidate;
            }
        }
        return null;
    }

    private void reverifyTrackedBeds(World world) {
        Block bedBlock = Blocks.bed;
        for (int i = 0; i < this.bedCount; i++) {
            TrackedBed bed = this.beds[i];
            if (!bed.active) {
                continue;
            }
            this.scanPos.set(bed.headX, bed.headY, bed.headZ);
            if (!world.isBlockLoaded(this.scanPos)) {
                if (bed.status == TrackedBed.STATUS_LIVE) {
                    bed.status = TrackedBed.STATUS_CACHED;
                }
                continue;
            }
            IBlockState state = world.getBlockState(this.scanPos);
            if (state.getBlock() != bedBlock) {
                bed.status = TrackedBed.STATUS_DESTROYED;
            } else {
                bed.status = TrackedBed.STATUS_LIVE;
                bed.lastSeenTick = this.tickCounter;
                int meta = bedBlock.getMetaFromState(state);
                if ((meta & 8) != 0) {
                    byte dir = (byte) (meta & 3);
                    bed.facingDir = dir;
                    int footX = bed.headX - getFacingOffsetX(dir);
                    int footZ = bed.headZ - getFacingOffsetZ(dir);
                    bed.pairedFoot = isLoadedBedPart(world, bedBlock, footX, bed.headY, footZ, false);
                }
            }
        }
    }

    private void refreshOneBedBlockVotes(World world) {
        if (this.bedCount == 0) {
            return;
        }
        if (this.voteRefreshBedCursor >= this.bedCount) {
            this.voteRefreshBedCursor = 0;
        }
        TrackedBed bed = this.beds[this.voteRefreshBedCursor++];
        if (!bed.active || bed.ownBed || bed.status != TrackedBed.STATUS_LIVE) {
            return;
        }
        TeamColor voted = voteBedTeamColorFromBlocks(world, bed.headX, bed.headY, bed.headZ);
        if (voted != TeamColor.UNKNOWN) {
            bed.votedColor = voted;
            recomputeBedTeamColor(bed);
        }
    }

    private TeamColor voteBedTeamColorFromBlocks(World world, int hx, int hy, int hz) {
        Arrays.fill(this.teamVoteCounts, 0);

        int minX = hx - TEAM_BLOCK_VOTE_RADIUS;
        int maxX = hx + TEAM_BLOCK_VOTE_RADIUS;
        int minY = Math.max(0, hy - TEAM_BLOCK_VOTE_RADIUS);
        int maxY = Math.min(255, hy + TEAM_BLOCK_VOTE_RADIUS);
        int minZ = hz - TEAM_BLOCK_VOTE_RADIUS;
        int maxZ = hz + TEAM_BLOCK_VOTE_RADIUS;

        int minSec = minY >> 4;
        int maxSec = maxY >> 4;

        for (int x = minX; x <= maxX; x++) {
            int cx = x >> 4;
            int localX = x & 15;
            for (int z = minZ; z <= maxZ; z++) {
                Chunk chunk = world.getChunkFromChunkCoords(cx, z >> 4);
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
                if (sections == null) {
                    continue;
                }
                int localZ = z & 15;
                int clampedMaxSec = Math.min(sections.length - 1, maxSec);
                for (int sec = minSec; sec <= clampedMaxSec; sec++) {
                    ExtendedBlockStorage storage = sections[sec];
                    if (storage == null || storage.isEmpty()) {
                        continue;
                    }
                    int secMinY = Math.max(minY, sec << 4);
                    int secMaxY = Math.min(maxY, (sec << 4) + 15);
                    for (int y = secMinY; y <= secMaxY; y++) {
                        IBlockState state = storage.get(localX, y & 15, localZ);
                        Block block = state.getBlock();
                        if (block == Blocks.wool
                            || block == Blocks.stained_hardened_clay
                            || block == Blocks.stained_glass) {
                            TeamColor color = TeamColor.fromDyeMeta(block.getMetaFromState(state));
                            if (color != TeamColor.UNKNOWN) {
                                this.teamVoteCounts[color.ordinal()]++;
                            }
                        }
                    }
                }
            }
        }

        TeamColor bestColor = TeamColor.UNKNOWN;
        int bestCount = 0;
        TeamColor[] values = TeamColor.values();
        for (int i = 0; i < values.length; i++) {
            TeamColor candidate = values[i];
            if (candidate == TeamColor.UNKNOWN) {
                continue;
            }
            int count = this.teamVoteCounts[i];
            if (count > bestCount) {
                bestCount = count;
                bestColor = candidate;
            }
        }
        return bestColor;
    }

    private void updateEnemyBedPlayerAssociations(World world, EntityPlayer localPlayer, Minecraft mc) {
        List<EntityPlayer> players = world.playerEntities;
        if (players == null || players.isEmpty()) {
            return;
        }
        double maxDistSq = ENEMY_PLAYER_ASSOC_RANGE * ENEMY_PLAYER_ASSOC_RANGE;

        for (int b = 0; b < this.bedCount; b++) {
            TrackedBed bed = this.beds[b];
            if (!bed.active || bed.ownBed || bed.status == TrackedBed.STATUS_DESTROYED) {
                continue;
            }
            // Keep player association sticky once confirmed against surrounding island blocks.
            if (bed.votedColor != TeamColor.UNKNOWN && bed.playerColor == bed.votedColor) {
                recomputeBedTeamColor(bed);
                continue;
            }

            TeamColor bestEnemyColor = TeamColor.UNKNOWN;
            double bestDistSq = maxDistSq;
            boolean foundVoteMatch = false;

            for (int p = 0, pCount = players.size(); p < pCount; p++) {
                EntityPlayer candidate = players.get(p);
                if (!isValidEnemyPlayer(candidate, localPlayer, mc)) {
                    continue;
                }
                TeamColor enemyColor = resolvePlayerTeamColor(candidate);
                if (enemyColor == TeamColor.UNKNOWN || enemyColor == this.localTeamColor) {
                    continue;
                }
                double dSq = bed.distSqTo(candidate.posX, candidate.posY, candidate.posZ);
                if (dSq > maxDistSq) {
                    continue;
                }
                boolean matchesVote = bed.votedColor != TeamColor.UNKNOWN && enemyColor == bed.votedColor;
                if (matchesVote && !foundVoteMatch) {
                    foundVoteMatch = true;
                    bestDistSq = dSq;
                    bestEnemyColor = enemyColor;
                } else if (matchesVote == foundVoteMatch && dSq <= bestDistSq) {
                    bestDistSq = dSq;
                    bestEnemyColor = enemyColor;
                }
            }

            if (bestEnemyColor != TeamColor.UNKNOWN) {
                bed.playerColor = bestEnemyColor;
            }
            recomputeBedTeamColor(bed);
        }
    }

    private void recomputeBedTeamColor(TrackedBed bed) {
        if (bed.ownBed) {
            if (this.localTeamColor != TeamColor.UNKNOWN) {
                bed.teamColor = this.localTeamColor;
            }
            return;
        }
        if (bed.playerColor != TeamColor.UNKNOWN) {
            bed.teamColor = bed.playerColor;
        } else {
            bed.teamColor = bed.votedColor;
        }
    }

    private void tryAnchorHomeBase(EntityPlayer player, boolean alwaysActive) {
        if (this.homeAnchored && !this.anchorPending) {
            TrackedBed own = findOwnBed();
            if (own != null && this.localTeamColor != TeamColor.UNKNOWN && own.teamColor != this.localTeamColor) {
                own.teamColor = this.localTeamColor;
            }
            return;
        }
        if (this.bedCount == 0) {
            return;
        }

        double maxSpawnDistSq = HOME_SPAWN_ANCHOR_RANGE * HOME_SPAWN_ANCHOR_RANGE;
        TrackedBed bestSpawnBed = null;
        double bestSpawnDistSq = maxSpawnDistSq;
        boolean spawnMatchedLocalColor = false;

        TrackedBed teamFallbackBed = null;
        double teamFallbackDistSq = Double.MAX_VALUE;

        TrackedBed nearestBed = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (int i = 0; i < this.bedCount; i++) {
            TrackedBed bed = this.beds[i];
            if (!bed.active || bed.status == TrackedBed.STATUS_DESTROYED) {
                continue;
            }
            double dSq = bed.distSqTo(player.posX, player.posY, player.posZ);

            if (dSq <= maxSpawnDistSq) {
                boolean matchesLocal = this.localTeamColor != TeamColor.UNKNOWN
                    && bed.teamColor == this.localTeamColor;
                boolean conflictsLocal = this.localTeamColor != TeamColor.UNKNOWN
                    && bed.teamColor != TeamColor.UNKNOWN
                    && bed.teamColor != this.localTeamColor;
                if (!conflictsLocal) {
                    if (matchesLocal && !spawnMatchedLocalColor) {
                        spawnMatchedLocalColor = true;
                        bestSpawnDistSq = dSq;
                        bestSpawnBed = bed;
                    } else if (matchesLocal == spawnMatchedLocalColor && dSq <= bestSpawnDistSq) {
                        bestSpawnDistSq = dSq;
                        bestSpawnBed = bed;
                    }
                }
            }

            if (this.localTeamColor != TeamColor.UNKNOWN && bed.teamColor == this.localTeamColor) {
                if (dSq < teamFallbackDistSq) {
                    teamFallbackDistSq = dSq;
                    teamFallbackBed = bed;
                }
            }

            if (dSq < nearestDistSq) {
                nearestDistSq = dSq;
                nearestBed = bed;
            }
        }

        TrackedBed chosen = bestSpawnBed;
        if (chosen == null) {
            chosen = teamFallbackBed;
        }
        if (chosen == null && alwaysActive && !this.inBedWarsMatch) {
            chosen = nearestBed;
        }
        if (chosen == null) {
            return;
        }

        clearOwnBedFlags();
        chosen.ownBed = true;
        if (this.localTeamColor != TeamColor.UNKNOWN) {
            chosen.teamColor = this.localTeamColor;
        }
        this.ownBedIndex = chosen.index;
        this.homeX = chosen.getCenterX();
        this.homeY = chosen.getCenterY();
        this.homeZ = chosen.getCenterZ();
        this.homeAnchored = true;
        this.anchorPending = false;
    }

    private static int clampScanRange(int scanRange) {
        if (scanRange < MIN_SCAN_RANGE) {
            return MIN_SCAN_RANGE;
        }
        if (scanRange > MAX_SCAN_RANGE) {
            return MAX_SCAN_RANGE;
        }
        return scanRange;
    }

    private static TeamColor extractColorFromFormattedText(String text) {
        if (text == null || text.isEmpty()) {
            return TeamColor.UNKNOWN;
        }
        int len = text.length();
        for (int i = 0; i < len - 1; i++) {
            if (text.charAt(i) == '\u00a7') {
                TeamColor color = TeamColor.fromChatColorChar(text.charAt(i + 1));
                if (color != TeamColor.UNKNOWN) {
                    return color;
                }
                i++;
            }
        }
        return TeamColor.UNKNOWN;
    }

    private static TeamColor fromChatFormatting(EnumChatFormatting formatting) {
        if (formatting == null || !formatting.isColor()) {
            return TeamColor.UNKNOWN;
        }
        switch (formatting) {
            case RED:
            case DARK_RED:
                return TeamColor.RED;
            case BLUE:
            case DARK_BLUE:
                return TeamColor.BLUE;
            case GREEN:
            case DARK_GREEN:
                return TeamColor.GREEN;
            case YELLOW:
                return TeamColor.YELLOW;
            case AQUA:
            case DARK_AQUA:
                return TeamColor.AQUA;
            case WHITE:
                return TeamColor.WHITE;
            case LIGHT_PURPLE:
                return TeamColor.PINK;
            case GRAY:
            case DARK_GRAY:
                return TeamColor.GRAY;
            default:
                return TeamColor.UNKNOWN;
        }
    }

    private static void stripFormattingInto(CharSequence src, StringBuilder dst) {
        dst.setLength(0);
        if (src == null) {
            return;
        }
        int len = src.length();
        for (int i = 0; i < len; i++) {
            char c = src.charAt(i);
            if (c == '\u00a7') {
                if (i + 1 < len) {
                    i++;
                }
                continue;
            }
            dst.append(c);
        }
    }

    private static boolean hasTeamStatusMarker(CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\u2714' || c == '\u2713' || c == '\u2717' || c == '\u2718') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTeamKeyword(CharSequence text) {
        for (int i = 0; i < TEAM_KEYWORDS.length; i++) {
            if (containsIgnoreCase(text, TEAM_KEYWORDS[i])) {
                return true;
            }
        }
        return false;
    }

    private static TeamColor detectTeamMention(CharSequence text) {
        if (containsIgnoreCase(text, "red") || containsSequence(text, "\u7ea2\u961f")) {
            return TeamColor.RED;
        }
        if (containsIgnoreCase(text, "blue") || containsSequence(text, "\u84dd\u961f")) {
            return TeamColor.BLUE;
        }
        if (containsIgnoreCase(text, "green") || containsSequence(text, "\u7eff\u961f")) {
            return TeamColor.GREEN;
        }
        if (containsIgnoreCase(text, "yellow") || containsSequence(text, "\u9ec4\u961f")) {
            return TeamColor.YELLOW;
        }
        if (containsIgnoreCase(text, "aqua") || containsSequence(text, "\u9752\u961f")) {
            return TeamColor.AQUA;
        }
        if (containsIgnoreCase(text, "white") || containsSequence(text, "\u767d\u961f")) {
            return TeamColor.WHITE;
        }
        if (containsIgnoreCase(text, "pink") || containsSequence(text, "\u7c89\u961f")) {
            return TeamColor.PINK;
        }
        if (containsIgnoreCase(text, "gray") || containsIgnoreCase(text, "grey")
            || containsSequence(text, "\u7070\u961f")) {
            return TeamColor.GRAY;
        }
        return TeamColor.UNKNOWN;
    }

    private static boolean containsIgnoreCase(CharSequence haystack, String needle) {
        int nLen = needle.length();
        int hLen = haystack.length();
        if (nLen == 0) {
            return true;
        }
        if (hLen < nLen) {
            return false;
        }
        int limit = hLen - nLen;
        for (int i = 0; i <= limit; i++) {
            if (regionMatchesIgnoreCase(haystack, i, needle, nLen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionMatchesIgnoreCase(CharSequence haystack, int offset, String needle, int len) {
        for (int i = 0; i < len; i++) {
            char a = haystack.charAt(offset + i);
            char b = needle.charAt(i);
            if (a == b) {
                continue;
            }
            if (toAsciiLower(a) != toAsciiLower(b)) {
                return false;
            }
        }
        return true;
    }

    private static char toAsciiLower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + ('a' - 'A')) : c;
    }

    private static boolean containsSequence(CharSequence haystack, String needle) {
        int nLen = needle.length();
        int hLen = haystack.length();
        if (nLen == 0) {
            return true;
        }
        if (hLen < nLen) {
            return false;
        }
        int limit = hLen - nLen;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < nLen; j++) {
                if (haystack.charAt(i + j) != needle.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfColonSpace(CharSequence text) {
        int len = text.length() - 1;
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == ':' && text.charAt(i + 1) == ' ') {
                return i;
            }
        }
        return -1;
    }
}
