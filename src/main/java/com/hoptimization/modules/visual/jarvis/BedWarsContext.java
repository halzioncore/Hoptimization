package com.hoptimization.modules.visual.jarvis;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
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
import net.minecraft.world.WorldSettings;
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
    public static final int MATCH_START_ANCHOR_GRACE_TICKS = 60;

    public static final int SCOREBOARD_FLAG_VALID = 1 << 8;
    public static final int SCOREBOARD_FLAG_BED_ALIVE = 1 << 4;
    public static final int SCOREBOARD_FLAG_LOCAL_TEAM = 1 << 5;
    public static final int SCOREBOARD_COLOR_MASK = 0x0F;

    private static final int SWEEP_COOLDOWN_TICKS = 20;
    private static final double RESCAN_TELEPORT_DIST_SQ = 32.0D * 32.0D;
    private static final int DEFAULT_LEATHER_RGB = 0xA06540;
    private static final int MAX_UNLOADED_SKIPS_PER_TICK = 24;
    private static final int MIN_BLOCK_VOTE_SCORE = 12;
    private static final int MIN_PLAYER_ASSOC_VOTES = 2;
    private static final int ANCHOR_NONE = 0;
    private static final int ANCHOR_CHECK = 1;
    private static final int ANCHOR_HEART = 2;
    private static final int ANCHOR_CROSS = 3;
    private static final int ANCHOR_PIPE_COUNT = 4;

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
        private final int[] playerVotes = new int[TeamColor.values().length];
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
            Arrays.fill(this.playerVotes, 0);
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
    private final int[] rosterAccum = new int[4];
    private final int[] parsedLineScratch = new int[8];

    private int bedCount;
    private int tickCounter;
    private World lastWorld;
    private ScoreObjective lastSidebarObjective;

    private boolean inBedWarsMatch;
    private boolean lastEffectiveActive;
    private TeamColor localTeamColor = TeamColor.UNKNOWN;
    private int presentTeamsMask;
    private int bedAliveMask;
    private TeamColor scoreboardLocalTeam = TeamColor.UNKNOWN;
    private int matchStartTick = -1;

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
            this.matchStartTick = this.tickCounter;
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

        updateLocalTeamColor(player, mc);

        this.bedAddedThisTick = false;
        int clampedRange = clampScanRange(scanRange);
        stepIncrementalChunkScan(world, player, clampedRange);

        boolean runDedupe = this.bedAddedThisTick;
        if (this.tickCounter % REVERIFY_INTERVAL_TICKS == 0) {
            reverifyTrackedBeds(world);
            refreshOneBedBlockVotes(world);
            updateEnemyBedPlayerAssociations(world, player, mc);
            runDedupe = true;
        } else if (this.bedAddedThisTick) {
            updateEnemyBedPlayerAssociations(world, player, mc);
        }

        tryAnchorHomeBase(player, alwaysActive);
        if (runDedupe) {
            deduplicateEnemyBedColors();
        }

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
            this.matchStartTick = this.tickCounter;
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
            || containsSequence(this.lineScratch, "\u5e8a\u5df2\u88ab\u7834\u574f")
            || containsSequence(this.lineScratch, "\u5e8a\u88ab\u6467\u6bc1")
            || containsSequence(this.lineScratch, "\u5e8a\u5df2\u88ab\u6467\u6bc1")
            || containsSequence(this.lineScratch, "\u7684\u5e8a\u88ab")
            || containsSequence(this.lineScratch, "\u7684\u5e8a\u5df2\u88ab");
        boolean isOwnBedLost = containsIgnoreCase(this.lineScratch, "Your bed was destroyed")
            || containsSequence(this.lineScratch, "\u4f60\u7684\u5e8a\u88ab\u7834\u574f")
            || containsSequence(this.lineScratch, "\u4f60\u7684\u5e8a\u5df2\u88ab\u7834\u574f")
            || containsSequence(this.lineScratch, "\u4f60\u7684\u5e8a\u88ab\u6467\u6bc1")
            || containsSequence(this.lineScratch, "\u4f60\u7684\u5e8a\u5df2\u88ab\u6467\u6bc1")
            || containsSequence(this.lineScratch, "\u4f60\u4eec\u7684\u5e8a\u88ab\u7834\u574f")
            || containsSequence(this.lineScratch, "\u4f60\u4eec\u7684\u5e8a\u5df2\u88ab\u7834\u574f")
            || containsSequence(this.lineScratch, "\u4f60\u4eec\u7684\u5e8a\u88ab\u6467\u6bc1")
            || containsSequence(this.lineScratch, "\u4f60\u4eec\u7684\u5e8a\u5df2\u88ab\u6467\u6bc1");

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
        return isValidEnemyPlayer(target, local, mc, TeamColor.UNKNOWN, 0);
    }

    /**
     * Checks whether a player entity is a valid living enemy player with optional authoritative
     * match context (local team color and active scoreboard team bitmask).
     *
     * @param target           candidate player entity
     * @param local            local client player
     * @param mc               Minecraft client instance
     * @param contextLocalTeam local team color from match context, or {@link TeamColor#UNKNOWN}
     * @param presentTeamsMask bitmask of teams present on scoreboard ({@code 0} when unavailable)
     * @return {@code true} if the target is a valid enemy player
     */
    public static boolean isValidEnemyPlayer(EntityPlayer target, EntityPlayer local, Minecraft mc,
                                             TeamColor contextLocalTeam, int presentTeamsMask) {
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

        NetworkPlayerInfo targetInfo = null;
        NetworkPlayerInfo localInfo = null;
        if (mc != null) {
            NetHandlerPlayClient netHandler = mc.getNetHandler();
            if (netHandler != null) {
                targetInfo = netHandler.getPlayerInfo(uuid);
                if (targetInfo == null) {
                    return false;
                }
                if (targetInfo.getGameType() == WorldSettings.GameType.SPECTATOR) {
                    return false;
                }
                if (local != null && local.getUniqueID() != null) {
                    localInfo = netHandler.getPlayerInfo(local.getUniqueID());
                }
            }
        }

        TeamColor targetColor = resolvePlayerTeamColor(target, mc, presentTeamsMask);
        if (presentTeamsMask != 0 && targetColor != TeamColor.UNKNOWN
            && (presentTeamsMask & (1 << targetColor.ordinal())) == 0) {
            return false;
        }

        if (local != null) {
            if (local.isOnSameTeam(target)) {
                return false;
            }
            Team localTeam = local.getTeam();
            Team targetTeam = target.getTeam();
            if (localTeam != null && targetTeam != null) {
                if (localTeam == targetTeam || localTeam.isSameTeam(targetTeam)) {
                    return false;
                }
                if (localTeam instanceof ScorePlayerTeam && targetTeam instanceof ScorePlayerTeam
                    && haveEqualNonEmptyTeamPrefix((ScorePlayerTeam) localTeam, (ScorePlayerTeam) targetTeam)) {
                    return false;
                }
            }
            if (localInfo != null && targetInfo != null) {
                ScorePlayerTeam localTabTeam = localInfo.getPlayerTeam();
                ScorePlayerTeam targetTabTeam = targetInfo.getPlayerTeam();
                if (localTabTeam != null && targetTabTeam != null) {
                    if (localTabTeam == targetTabTeam
                        || localTabTeam.isSameTeam(targetTabTeam)
                        || haveEqualNonEmptyTeamPrefix(localTabTeam, targetTabTeam)) {
                        return false;
                    }
                }
            }

            int localHelmRgb = getDyedLeatherArmorRgb(local, 3);
            int targetHelmRgb = getDyedLeatherArmorRgb(target, 3);
            if (localHelmRgb != -1 && localHelmRgb == targetHelmRgb) {
                return false;
            }
            int localChestRgb = getDyedLeatherArmorRgb(local, 2);
            int targetChestRgb = getDyedLeatherArmorRgb(target, 2);
            if (localChestRgb != -1 && localChestRgb == targetChestRgb) {
                return false;
            }

            TeamColor localColor = (contextLocalTeam != null && contextLocalTeam != TeamColor.UNKNOWN)
                ? contextLocalTeam
                : resolvePlayerTeamColor(local, mc, presentTeamsMask);
            if (localColor != TeamColor.UNKNOWN && targetColor == localColor) {
                return false;
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
        return resolvePlayerTeamColor(player, null, 0);
    }

    /**
     * Resolves a player's BedWars team color using scoreboard team metadata, optional client tab
     * info ({@link NetworkPlayerInfo}), formatted display name before player name, and dyed armor.
     *
     * @param player target player
     * @param mc     optional Minecraft client instance for tab-list lookup
     * @return resolved {@link TeamColor}, or {@link TeamColor#UNKNOWN}
     */
    public static TeamColor resolvePlayerTeamColor(EntityPlayer player, Minecraft mc) {
        return resolvePlayerTeamColor(player, mc, 0);
    }

    /**
     * Resolves a player's BedWars team color with optional present-teams filtering so neutral reset
     * formatting (such as white name tags) falls through to dyed armor when outside active teams.
     *
     * @param player           target player
     * @param mc               optional Minecraft client instance for tab-list lookup
     * @param presentTeamsMask bitmask of teams present on scoreboard ({@code 0} when unavailable)
     * @return resolved {@link TeamColor}, or {@link TeamColor#UNKNOWN}
     */
    public static TeamColor resolvePlayerTeamColor(EntityPlayer player, Minecraft mc, int presentTeamsMask) {
        if (player == null) {
            return TeamColor.UNKNOWN;
        }

        String profileName = null;
        GameProfile profile = player.getGameProfile();
        if (profile != null) {
            profileName = profile.getName();
        }

        Team team = player.getTeam();
        ScorePlayerTeam scoreTeam = (team instanceof ScorePlayerTeam) ? (ScorePlayerTeam) team : null;
        NetworkPlayerInfo tabInfo = null;
        if (mc != null && mc.getNetHandler() != null && player.getUniqueID() != null) {
            tabInfo = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
            if (scoreTeam == null && tabInfo != null) {
                scoreTeam = tabInfo.getPlayerTeam();
            }
        }

        TeamColor fallback = TeamColor.UNKNOWN;

        if (scoreTeam != null) {
            TeamColor fromPrefix = extractPrefixTeamColor(scoreTeam.getColorPrefix());
            if (isColorAllowedByMask(fromPrefix, presentTeamsMask)) {
                return fromPrefix;
            }
            if (fallback == TeamColor.UNKNOWN && fromPrefix != TeamColor.UNKNOWN) {
                fallback = fromPrefix;
            }

            TeamColor fromFormat = fromChatFormatting(scoreTeam.getChatFormat());
            if (isColorAllowedByMask(fromFormat, presentTeamsMask)) {
                return fromFormat;
            }
            if (fallback == TeamColor.UNKNOWN && fromFormat != TeamColor.UNKNOWN) {
                fallback = fromFormat;
            }

            TeamColor fromRegName = resolveTeamFromRegisteredName(scoreTeam.getRegisteredName());
            if (isColorAllowedByMask(fromRegName, presentTeamsMask)) {
                return fromRegName;
            }
            if (fallback == TeamColor.UNKNOWN && fromRegName != TeamColor.UNKNOWN) {
                fallback = fromRegName;
            }
        }

        if (tabInfo != null) {
            IChatComponent tabDisplay = tabInfo.getDisplayName();
            if (tabDisplay != null) {
                String formatted = tabDisplay.getFormattedText();
                TeamColor fromTab = extractColorBeforeName(formatted, profileName);
                if (fromTab == TeamColor.UNKNOWN) {
                    fromTab = extractLastColorFromFormattedText(formatted);
                }
                if (isColorAllowedByMask(fromTab, presentTeamsMask)) {
                    return fromTab;
                }
                if (fallback == TeamColor.UNKNOWN && fromTab != TeamColor.UNKNOWN) {
                    fallback = fromTab;
                }
            }
        }

        IChatComponent displayName = player.getDisplayName();
        if (displayName != null) {
            String formatted = displayName.getFormattedText();
            TeamColor fromDisplay = extractColorBeforeName(formatted, profileName);
            if (fromDisplay == TeamColor.UNKNOWN) {
                fromDisplay = extractLastColorFromFormattedText(formatted);
            }
            if (isColorAllowedByMask(fromDisplay, presentTeamsMask)) {
                return fromDisplay;
            }
            if (fallback == TeamColor.UNKNOWN && fromDisplay != TeamColor.UNKNOWN) {
                fallback = fromDisplay;
            }
        }

        if (player.inventory != null) {
            for (int slot = 3; slot >= 0; slot--) {
                int rgb = getDyedLeatherArmorRgb(player, slot);
                if (rgb != -1) {
                    TeamColor fromArmor = TeamColor.fromArmorColor(rgb);
                    if (isColorAllowedByMask(fromArmor, presentTeamsMask)) {
                        return fromArmor;
                    }
                    if (fallback == TeamColor.UNKNOWN && fromArmor != TeamColor.UNKNOWN) {
                        fallback = fromArmor;
                    }
                }
            }
        }

        return fallback;
    }

    private static boolean isColorAllowedByMask(TeamColor color, int presentTeamsMask) {
        if (color == null || color == TeamColor.UNKNOWN) {
            return false;
        }
        return presentTeamsMask == 0 || (presentTeamsMask & (1 << color.ordinal())) != 0;
    }

    private static boolean haveEqualNonEmptyTeamPrefix(ScorePlayerTeam a, ScorePlayerTeam b) {
        if (a == null || b == null) {
            return false;
        }
        String prefixA = a.getColorPrefix();
        String prefixB = b.getColorPrefix();
        return prefixA != null
            && !prefixA.isEmpty()
            && !"\u00a7r".equals(prefixA)
            && !"\u00a7f".equals(prefixA)
            && prefixA.equals(prefixB);
    }

    private static int getDyedLeatherArmorRgb(EntityPlayer player, int slot) {
        if (player == null || player.inventory == null) {
            return -1;
        }
        ItemStack armorStack = player.getCurrentArmor(slot);
        if (armorStack == null || !(armorStack.getItem() instanceof ItemArmor)) {
            return -1;
        }
        ItemArmor armor = (ItemArmor) armorStack.getItem();
        if (armor.getArmorMaterial() != ItemArmor.ArmorMaterial.LEATHER) {
            return -1;
        }
        int rgb = armor.getColor(armorStack);
        if (rgb == -1) {
            return -1;
        }
        int stripped = rgb & 0x00FFFFFF;
        if (stripped == DEFAULT_LEATHER_RGB || stripped == 0) {
            return -1;
        }
        return stripped;
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

    public int getPresentTeamsMask() {
        return this.presentTeamsMask;
    }

    public int getBedAliveMask() {
        return this.bedAliveMask;
    }

    public boolean hasScoreboardRoster() {
        return this.presentTeamsMask != 0;
    }

    public boolean isTeamPresent(TeamColor color) {
        if (color == null || color == TeamColor.UNKNOWN) {
            return false;
        }
        return this.presentTeamsMask == 0 || (this.presentTeamsMask & (1 << color.ordinal())) != 0;
    }

    public boolean isTeamBedAliveOnScoreboard(TeamColor color) {
        if (color == null || color == TeamColor.UNKNOWN) {
            return false;
        }
        return (this.bedAliveMask & (1 << color.ordinal())) != 0;
    }

    public boolean isEligibleEnemyColor(TeamColor color) {
        if (color == null || color == TeamColor.UNKNOWN) {
            return false;
        }
        if (this.localTeamColor != TeamColor.UNKNOWN && color == this.localTeamColor) {
            return false;
        }
        return this.presentTeamsMask == 0 || (this.presentTeamsMask & (1 << color.ordinal())) != 0;
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
        this.presentTeamsMask = 0;
        this.bedAliveMask = 0;
        this.scoreboardLocalTeam = TeamColor.UNKNOWN;
        this.matchStartTick = -1;
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
        this.matchStartTick = -1;
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
            this.presentTeamsMask = 0;
            this.bedAliveMask = 0;
            this.scoreboardLocalTeam = TeamColor.UNKNOWN;
            this.lastSidebarObjective = null;
            return;
        }
        ScoreObjective sidebar = scoreboard.getObjectiveInDisplaySlot(1);
        if (sidebar == null) {
            this.inBedWarsMatch = false;
            this.presentTeamsMask = 0;
            this.bedAliveMask = 0;
            this.scoreboardLocalTeam = TeamColor.UNKNOWN;
            this.lastSidebarObjective = null;
            return;
        }

        stripFormattingInto(sidebar.getDisplayName(), this.scoreboardScratch);
        boolean bedWarsTitle = containsIgnoreCase(this.scoreboardScratch, "BED WARS")
            || containsIgnoreCase(this.scoreboardScratch, "BEDWARS")
            || containsSequence(this.scoreboardScratch, "\u8d77\u5e8a\u6218\u4e89")
            || containsSequence(this.scoreboardScratch, "\u8d77\u5e8a")
            || containsSequence(this.scoreboardScratch, "\u5e8a\u306e\u6218\u4e89")
            || containsSequence(this.scoreboardScratch, "\u5e8a\u7684\u6218\u4e89")
            || containsSequence(this.scoreboardScratch, "\u8d85\u7ea7\u5e8a")
            || containsSequence(this.scoreboardScratch, "\u5e8a\u6218");

        // Refresh sidebar team roster more often when the title indicates BedWars, while keeping
        // Scoreboard#getSortedScores off every single tick to avoid per-tick allocations.
        int refreshInterval = bedWarsTitle ? 10 : 20;
        if (this.tickCounter % refreshInterval == 0 || sidebar != this.lastSidebarObjective) {
            this.lastSidebarObjective = sidebar;
            scanSidebarRoster(scoreboard, sidebar, bedWarsTitle);
        }
    }

    private void scanSidebarRoster(Scoreboard scoreboard, ScoreObjective sidebar, boolean bedWarsTitle) {
        Collection<Score> scores = scoreboard.getSortedScores(sidebar);
        if (scores == null || scores.isEmpty()) {
            this.inBedWarsMatch = false;
            this.presentTeamsMask = 0;
            this.bedAliveMask = 0;
            this.scoreboardLocalTeam = TeamColor.UNKNOWN;
            return;
        }

        Arrays.fill(this.rosterAccum, 0);
        this.rosterAccum[3] = -1;

        if (scores instanceof List<?>) {
            List<Score> list = (List<Score>) scores;
            for (int i = 0, size = list.size(); i < size; i++) {
                inspectAndAccumulateScoreboardRow(scoreboard, list.get(i), bedWarsTitle);
            }
        } else {
            for (Score score : scores) {
                inspectAndAccumulateScoreboardRow(scoreboard, score, bedWarsTitle);
            }
        }

        int parsedRows = this.rosterAccum[0];
        if (parsedRows >= 2) {
            TeamColor[] values = TeamColor.values();
            int localOrd = this.rosterAccum[3];
            this.presentTeamsMask = this.rosterAccum[1];
            this.bedAliveMask = this.rosterAccum[2];
            this.scoreboardLocalTeam = (localOrd >= 0 && localOrd < values.length)
                ? values[localOrd]
                : TeamColor.UNKNOWN;
            this.inBedWarsMatch = true;
        } else {
            this.presentTeamsMask = 0;
            this.bedAliveMask = 0;
            this.scoreboardLocalTeam = TeamColor.UNKNOWN;
            this.inBedWarsMatch = false;
        }
    }

    private void accumulateSidebarRow(int parsed) {
        if ((parsed & SCOREBOARD_FLAG_VALID) == 0) {
            return;
        }
        int ordinal = parsed & SCOREBOARD_COLOR_MASK;
        int bit = 1 << ordinal;
        if ((this.rosterAccum[1] & bit) == 0) {
            this.rosterAccum[0]++;
        }
        this.rosterAccum[1] |= bit;
        if ((parsed & SCOREBOARD_FLAG_BED_ALIVE) != 0) {
            this.rosterAccum[2] |= bit;
        }
        if ((parsed & SCOREBOARD_FLAG_LOCAL_TEAM) != 0) {
            this.rosterAccum[3] = ordinal;
        }
    }

    private void inspectAndAccumulateScoreboardRow(Scoreboard scoreboard, Score score, boolean bedWarsTitle) {
        if (score == null) {
            return;
        }
        String entryName = score.getPlayerName();
        if (entryName == null) {
            return;
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
        if (isLocalTeamDeclarationRow(this.cleanLineScratch)) {
            TeamColor declaredLocal = parseScoreboardLocalTeamLine(this.lineScratch, this.cleanLineScratch);
            if (declaredLocal != TeamColor.UNKNOWN) {
                this.rosterAccum[3] = declaredLocal.ordinal();
            }
            return;
        }

        int teamCount = parseScoreboardLine(
            this.lineScratch,
            this.cleanLineScratch,
            bedWarsTitle,
            this.parsedLineScratch
        );
        for (int k = 0; k < teamCount; k++) {
            accumulateSidebarRow(this.parsedLineScratch[k]);
        }
    }

    private static boolean isLocalTeamDeclarationRow(CharSequence cleanText) {
        return findLocalTeamColonIndex(cleanText) > 0;
    }

    private static int findLocalTeamColonIndex(CharSequence cleanText) {
        if (cleanText == null || cleanText.length() < 3) {
            return -1;
        }
        int colonIdx = findColonIndex(cleanText);
        if (colonIdx <= 0) {
            return -1;
        }
        int keyStart = skipSpaces(cleanText, 0, colonIdx);
        int keyEnd = colonIdx;
        while (keyEnd > keyStart && isSpaceChar(cleanText.charAt(keyEnd - 1))) {
            keyEnd--;
        }
        return isLocalTeamLabel(cleanText, keyStart, keyEnd) ? colonIdx : -1;
    }

    /**
     * Parses a dedicated sidebar local-team declaration line (such as
     * {@code "队伍: ❤ 灵梦红  人数: 4"}) without counting it as a bed roster entry.
     *
     * @param rawFormatted raw formatting-retained scoreboard text (nullable)
     * @param cleanText    unformatted stripped scoreboard text
     * @return declared local {@link TeamColor}, or {@link TeamColor#UNKNOWN}
     */
    public static TeamColor parseScoreboardLocalTeamLine(CharSequence rawFormatted, CharSequence cleanText) {
        int colonIdx = findLocalTeamColonIndex(cleanText);
        if (colonIdx <= 0) {
            return TeamColor.UNKNOWN;
        }

        int valStart = colonIdx + 1;
        int valEnd = findNextLocalTeamFieldEnd(cleanText, valStart, cleanText.length());
        TeamColor named = detectTeamRowColor(cleanText, valStart, valEnd);
        if (named != TeamColor.UNKNOWN) {
            return named;
        }
        if (rawFormatted != null) {
            for (int idx = valStart; idx < valEnd; idx++) {
                char c = cleanText.charAt(idx);
                if (isSpaceChar(c) || isTeamStatusIcon(c) || isLabelPunctuation(c)) {
                    continue;
                }
                int state = findFormattingStateAtCleanIndex(rawFormatted, idx);
                TeamColor fromFmt = TeamColor.fromChatColorChar((char) (state & 0xFFFF));
                if (fromFmt != TeamColor.UNKNOWN) {
                    return fromFmt;
                }
            }
        }
        return TeamColor.UNKNOWN;
    }

    /**
     * Parses a single formatted + unformatted BedWars sidebar row into packed status bits.
     *
     * @param rawFormatted raw formatting-retained scoreboard text (nullable)
     * @param cleanText    unformatted stripped scoreboard text
     * @return {@code 0} if the line is not a BedWars team status row, otherwise a bitmask using
     * {@link #SCOREBOARD_FLAG_VALID}, {@link #SCOREBOARD_FLAG_BED_ALIVE},
     * {@link #SCOREBOARD_FLAG_LOCAL_TEAM}, and ordinal bits {@link #SCOREBOARD_COLOR_MASK}
     */
    public static int parseScoreboardTeamLine(CharSequence rawFormatted, CharSequence cleanText) {
        int[] single = new int[1];
        return parseScoreboardLine(rawFormatted, cleanText, false, single) > 0 ? single[0] : 0;
    }

    /**
     * Parses a formatted + unformatted BedWars sidebar row (supporting single or multiple teams per
     * line, checkmark/heart/cross/pipe indicators, and colon-numeric fallback) into {@code outPackedTeams}.
     *
     * @param rawFormatted   raw formatting-retained scoreboard text (nullable)
     * @param cleanText      unformatted stripped scoreboard text
     * @param bedWarsTitle   true when the sidebar title indicates BedWars
     * @param outPackedTeams destination buffer receiving packed status bitmasks
     * @return number of valid team entries written to {@code outPackedTeams}
     */
    public static int parseScoreboardLine(
        CharSequence rawFormatted,
        CharSequence cleanText,
        boolean bedWarsTitle,
        int[] outPackedTeams
    ) {
        if (cleanText == null || cleanText.length() < 3 || outPackedTeams == null || outPackedTeams.length == 0) {
            return 0;
        }

        int len = cleanText.length();
        int segStart = 0;
        int written = 0;

        for (int i = 0; i < len; i++) {
            char c = cleanText.charAt(i);
            int anchorKind = ANCHOR_NONE;
            boolean bedAliveByAnchor = false;

            if (isCheckmarkIcon(c)) {
                anchorKind = ANCHOR_CHECK;
                bedAliveByAnchor = true;
            } else if (isHeartIcon(c)) {
                anchorKind = ANCHOR_HEART;
                bedAliveByAnchor = !isDeadHeartFormatting(findFormattingStateAtCleanIndex(rawFormatted, i));
            } else if (isCrossOrDeadIcon(c)) {
                anchorKind = ANCHOR_CROSS;
                bedAliveByAnchor = false;
            } else if ((c == 'X' || c == 'x') && isStandaloneXCrossAnchor(cleanText, i, len)) {
                anchorKind = ANCHOR_CROSS;
                bedAliveByAnchor = false;
            } else if (isTeamSeparatorPipe(c) && isBarePipeCountAnchor(cleanText, segStart, i, len)) {
                anchorKind = ANCHOR_PIPE_COUNT;
                bedAliveByAnchor = false;
            }

            if (anchorKind == ANCHOR_NONE) {
                continue;
            }

            boolean hasPriorContent = hasLabelContent(cleanText, segStart, i);
            boolean iconFirst = !hasPriorContent && !hasMoreStatusIcons(cleanText, i + 1);

            int labelStart;
            int labelEnd;
            boolean hasCount = false;
            int parsedCount = 0;
            boolean isLocal;
            int suffixEnd;

            if (iconFirst) {
                labelStart = i + 1;
                labelEnd = len;
                suffixEnd = len;
                isLocal = containsAsciiWordIgnoreCase(cleanText, labelStart, labelEnd, "you")
                    || containsSequence(cleanText, labelStart, labelEnd, "\u4f60");
                int parsedPipeCount = findIconFirstTrailingCount(cleanText, labelStart, labelEnd);
                if (parsedPipeCount >= 0) {
                    hasCount = true;
                    parsedCount = parsedPipeCount;
                }
            } else {
                labelStart = segStart;
                labelEnd = i;
                int p = (anchorKind == ANCHOR_PIPE_COUNT) ? i : (i + 1);
                p = skipSpaces(cleanText, p, len);
                if (p < len && (isTeamSeparatorPipe(cleanText.charAt(p))
                    || (anchorKind != ANCHOR_PIPE_COUNT
                        && (cleanText.charAt(p) == ':' || cleanText.charAt(p) == '\uff1a')))) {
                    int afterSep = p + 1;
                    int q = skipSpaces(cleanText, afterSep, len);
                    int digitEnd = parseStandaloneIntegerEnd(cleanText, q, len);
                    if (digitEnd > q) {
                        hasCount = true;
                        parsedCount = parseNonNegativeInt(cleanText, q, digitEnd);
                        p = digitEnd;
                    } else {
                        p = afterSep;
                    }
                } else if (p < len && cleanText.charAt(p) >= '0' && cleanText.charAt(p) <= '9') {
                    int digitEnd = parseStandaloneIntegerEnd(cleanText, p, len);
                    if (digitEnd > p) {
                        hasCount = true;
                        parsedCount = parseNonNegativeInt(cleanText, p, digitEnd);
                        p = digitEnd;
                    }
                }

                int localEnd = matchLocalMarkerEnd(cleanText, p, len);
                isLocal = localEnd >= 0
                    || containsAsciiWordIgnoreCase(cleanText, labelStart, labelEnd, "you")
                    || containsSequence(cleanText, labelStart, labelEnd, "\u4f60");
                if (localEnd >= 0) {
                    p = localEnd;
                }
                suffixEnd = p;
            }

            TeamColor color = resolveLabelSegmentTeamColor(
                rawFormatted,
                cleanText,
                labelStart,
                labelEnd,
                anchorKind,
                bedWarsTitle,
                hasCount,
                parsedCount,
                segStart == 0 && !hasMoreStatusIcons(cleanText, suffixEnd)
            );

            if (color != TeamColor.UNKNOWN && color.ordinal() < TeamColor.UNKNOWN.ordinal()) {
                boolean bedAlive = bedAliveByAnchor && !(hasCount && parsedCount == 0);
                int packed = SCOREBOARD_FLAG_VALID | (color.ordinal() & SCOREBOARD_COLOR_MASK);
                if (bedAlive) {
                    packed |= SCOREBOARD_FLAG_BED_ALIVE;
                }
                if (isLocal) {
                    packed |= SCOREBOARD_FLAG_LOCAL_TEAM;
                }
                if (written < outPackedTeams.length) {
                    outPackedTeams[written++] = packed;
                }
            }

            segStart = suffixEnd;
            i = Math.max(i, suffixEnd - 1);
        }

        if (written > 0) {
            return written;
        }

        int colonIdx = findColonIndex(cleanText);
        if (colonIdx < 0 || !isAliveCountAfterColon(cleanText, colonIdx)) {
            return 0;
        }

        int searchEnd = colonIdx > 0 ? colonIdx : len;
        TeamColor color = detectTeamRowColor(cleanText, 0, searchEnd);
        if (color == TeamColor.UNKNOWN && colonIdx <= 0) {
            color = detectTeamRowColor(cleanText, 0, len);
        }
        if (color == TeamColor.UNKNOWN || color.ordinal() >= TeamColor.UNKNOWN.ordinal()) {
            return 0;
        }

        boolean isLocal = containsAsciiWordIgnoreCase(cleanText, 0, len, "you")
            || containsSequence(cleanText, "\u4f60");

        int packed = SCOREBOARD_FLAG_VALID | (color.ordinal() & SCOREBOARD_COLOR_MASK);
        if (isLocal) {
            packed |= SCOREBOARD_FLAG_LOCAL_TEAM;
        }
        outPackedTeams[0] = packed;
        return 1;
    }

    private static TeamColor resolveLabelSegmentTeamColor(
        CharSequence rawFormatted,
        CharSequence cleanText,
        int labelStart,
        int labelEnd,
        int anchorKind,
        boolean bedWarsTitle,
        boolean hasCount,
        int parsedCount,
        boolean singleTeamLine
    ) {
        int labelColon = findColonIndexRange(cleanText, labelStart, labelEnd);
        TeamColor color = TeamColor.UNKNOWN;
        if (labelColon > labelStart) {
            color = detectTeamRowColor(cleanText, labelStart, labelColon);
        }
        if (color == TeamColor.UNKNOWN) {
            color = detectTeamRowColor(cleanText, labelStart, labelEnd);
        }
        if (color != TeamColor.UNKNOWN) {
            return color;
        }

        if (anchorKind == ANCHOR_CHECK || anchorKind == ANCHOR_CROSS) {
            color = extractFirstTeamColorInCleanRange(rawFormatted, cleanText, labelStart, labelEnd);
            if (color == TeamColor.UNKNOWN && singleTeamLine && rawFormatted != null) {
                color = extractFirstColorFromFormattedText(rawFormatted);
            }
            return color;
        }

        if (anchorKind == ANCHOR_HEART
            && bedWarsTitle
            && !containsNonTeamStatKeyword(cleanText, labelStart, labelEnd)
            && (!hasCount || parsedCount <= 32)) {
            return extractFirstTeamColorInCleanRange(rawFormatted, cleanText, labelStart, labelEnd);
        }

        return TeamColor.UNKNOWN;
    }

    private void updateLocalTeamColor(EntityPlayer player, Minecraft mc) {
        if (this.scoreboardLocalTeam != TeamColor.UNKNOWN) {
            this.localTeamColor = this.scoreboardLocalTeam;
            return;
        }

        // Fast zero-alloc check against ScorePlayerTeam prefix first on every tick.
        Team team = player.getTeam();
        if (team instanceof ScorePlayerTeam) {
            ScorePlayerTeam scoreTeam = (ScorePlayerTeam) team;
            TeamColor fromPrefix = extractPrefixTeamColor(scoreTeam.getColorPrefix());
            if (isColorAllowedByMask(fromPrefix, this.presentTeamsMask)) {
                this.localTeamColor = fromPrefix;
                return;
            }
            TeamColor fromFormat = fromChatFormatting(scoreTeam.getChatFormat());
            if (isColorAllowedByMask(fromFormat, this.presentTeamsMask)) {
                this.localTeamColor = fromFormat;
                return;
            }
            TeamColor fromReg = resolveTeamFromRegisteredName(scoreTeam.getRegisteredName());
            if (isColorAllowedByMask(fromReg, this.presentTeamsMask)) {
                this.localTeamColor = fromReg;
                return;
            }
        }

        // Fall back to TabList + displayName + leather armor on initial/periodic ticks.
        if (this.localTeamColor == TeamColor.UNKNOWN || this.tickCounter % 10 == 0) {
            TeamColor resolved = resolvePlayerTeamColor(player, mc, this.presentTeamsMask);
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
        boolean rosterKnown = hasScoreboardRoster();
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
                if (rosterKnown && bed.teamColor != TeamColor.UNKNOWN
                    && (this.presentTeamsMask & (1 << bed.teamColor.ordinal())) != 0
                    && !isTeamBedAliveOnScoreboard(bed.teamColor)) {
                    bed.status = TrackedBed.STATUS_DESTROYED;
                }
                recomputeBedTeamColor(bed);
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
            recomputeBedTeamColor(bed);
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
        } else if (!isEligibleEnemyColor(bed.votedColor)) {
            bed.votedColor = TeamColor.UNKNOWN;
        }
        recomputeBedTeamColor(bed);
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
            int dxAbs = Math.abs(x - hx);
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
                int dzAbs = Math.abs(z - hz);
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
                                int weight = computeBlockVoteWeight(block, color, dxAbs, Math.abs(y - hy), dzAbs);
                                if (weight > 0) {
                                    this.teamVoteCounts[color.ordinal()] += weight;
                                }
                            }
                        }
                    }
                }
            }
        }

        return selectVotedColorFromCounts(this.teamVoteCounts);
    }

    /**
     * Computes distance- and material-weighted team vote score for a dyed block near a bed.
     *
     * @param block dyed block instance
     * @param color dye team color
     * @param dxAbs X distance from bed head
     * @param dyAbs Y distance from bed head
     * @param dzAbs Z distance from bed head
     * @return non-negative vote weight
     */
    public static int computeBlockVoteWeight(Block block, TeamColor color, int dxAbs, int dyAbs, int dzAbs) {
        if (block == null || color == null || color == TeamColor.UNKNOWN) {
            return 0;
        }
        boolean nearBed = dxAbs <= 5 && dyAbs <= 4 && dzAbs <= 5;
        if (block == Blocks.wool) {
            return nearBed ? 6 : 3;
        }
        if (block == Blocks.stained_hardened_clay) {
            if (color == TeamColor.GRAY || color == TeamColor.WHITE) {
                return 1;
            }
            return nearBed ? 2 : 1;
        }
        if (block == Blocks.stained_glass) {
            return 1;
        }
        return 0;
    }

    /**
     * Selects the highest-scoring eligible enemy team color meeting {@link #MIN_BLOCK_VOTE_SCORE}.
     *
     * @param counts per-{@link TeamColor} ordinal score array
     * @return winning eligible enemy team color, or {@link TeamColor#UNKNOWN}
     */
    public TeamColor selectVotedColorFromCounts(int[] counts) {
        if (counts == null) {
            return TeamColor.UNKNOWN;
        }
        TeamColor bestColor = TeamColor.UNKNOWN;
        int bestCount = 0;
        TeamColor[] values = TeamColor.values();
        int limit = Math.min(counts.length, values.length);
        for (int i = 0; i < limit; i++) {
            TeamColor candidate = values[i];
            if (!isEligibleEnemyColor(candidate)) {
                continue;
            }
            int count = counts[i];
            if (count > bestCount) {
                bestCount = count;
                bestColor = candidate;
            }
        }
        return bestCount >= MIN_BLOCK_VOTE_SCORE ? bestColor : TeamColor.UNKNOWN;
    }

    private void updateEnemyBedPlayerAssociations(World world, EntityPlayer localPlayer, Minecraft mc) {
        List<EntityPlayer> players = world.playerEntities;
        if (players == null || players.isEmpty()) {
            return;
        }
        double maxDistSq = ENEMY_PLAYER_ASSOC_RANGE * ENEMY_PLAYER_ASSOC_RANGE;
        double closeSpawnDistSq = 7.0D * 7.0D;
        TeamColor[] values = TeamColor.values();

        for (int b = 0; b < this.bedCount; b++) {
            TrackedBed bed = this.beds[b];
            if (!bed.active || bed.ownBed || bed.status == TrackedBed.STATUS_DESTROYED) {
                continue;
            }
            if (bed.playerColor != TeamColor.UNKNOWN && !isEligibleEnemyColor(bed.playerColor)) {
                bed.playerVotes[bed.playerColor.ordinal()] = 0;
                bed.playerColor = TeamColor.UNKNOWN;
            }

            TeamColor nearestColor = TeamColor.UNKNOWN;
            double bestDistSq = maxDistSq;
            boolean foundVoteMatch = false;

            for (int p = 0, pCount = players.size(); p < pCount; p++) {
                EntityPlayer candidate = players.get(p);
                if (!isValidEnemyPlayer(candidate, localPlayer, mc, this.localTeamColor, this.presentTeamsMask)) {
                    continue;
                }
                TeamColor enemyColor = resolvePlayerTeamColor(candidate, mc, this.presentTeamsMask);
                if (!isEligibleEnemyColor(enemyColor)) {
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
                    nearestColor = enemyColor;
                } else if (matchesVote == foundVoteMatch && dSq <= bestDistSq) {
                    bestDistSq = dSq;
                    nearestColor = enemyColor;
                }
            }

            if (nearestColor != TeamColor.UNKNOWN) {
                int ord = nearestColor.ordinal();
                int weight = (nearestColor == bed.votedColor || bestDistSq <= closeSpawnDistSq) ? 2 : 1;
                int updated = bed.playerVotes[ord] + weight;
                bed.playerVotes[ord] = updated > 100 ? 100 : updated;
            }

            TeamColor topColor = TeamColor.UNKNOWN;
            int topVotes = 0;
            int secondVotes = 0;
            for (int c = 0; c < values.length; c++) {
                TeamColor candidate = values[c];
                int v = bed.playerVotes[c];
                if (v <= 0) {
                    continue;
                }
                if (!isEligibleEnemyColor(candidate)) {
                    bed.playerVotes[c] = 0;
                    continue;
                }
                if (v > topVotes) {
                    secondVotes = topVotes;
                    topVotes = v;
                    topColor = candidate;
                } else if (v > secondVotes) {
                    secondVotes = v;
                }
            }

            if (topColor != TeamColor.UNKNOWN && topVotes >= MIN_PLAYER_ASSOC_VOTES) {
                boolean eligibleVote = isEligibleEnemyColor(bed.votedColor);
                if (!eligibleVote || topColor == bed.votedColor || topVotes >= secondVotes + 3) {
                    bed.playerColor = topColor;
                }
            }
            recomputeBedTeamColor(bed);
        }
    }

    private void recomputeBedTeamColor(TrackedBed bed) {
        if (bed.ownBed) {
            bed.teamColor = this.localTeamColor;
            return;
        }
        if (isEligibleEnemyColor(bed.playerColor)) {
            bed.teamColor = bed.playerColor;
        } else if (isEligibleEnemyColor(bed.votedColor)) {
            bed.teamColor = bed.votedColor;
        } else {
            bed.teamColor = TeamColor.UNKNOWN;
        }
    }

    private void deduplicateEnemyBedColors() {
        for (int i = 0; i < this.bedCount; i++) {
            TrackedBed a = this.beds[i];
            if (!a.active || a.ownBed || a.status == TrackedBed.STATUS_DESTROYED
                || a.teamColor == TeamColor.UNKNOWN) {
                continue;
            }
            for (int j = i + 1; j < this.bedCount; j++) {
                TrackedBed b = this.beds[j];
                if (!b.active || b.ownBed || b.status == TrackedBed.STATUS_DESTROYED
                    || b.teamColor != a.teamColor) {
                    continue;
                }
                TeamColor shared = a.teamColor;
                int scoreA = scoreBedColorConfidence(a, shared);
                int scoreB = scoreBedColorConfidence(b, shared);
                TrackedBed loser = scoreA >= scoreB ? b : a;
                int ord = shared.ordinal();
                loser.playerVotes[ord] = 0;
                if (loser.playerColor == shared) {
                    loser.playerColor = TeamColor.UNKNOWN;
                }
                if (loser.votedColor == shared) {
                    loser.votedColor = TeamColor.UNKNOWN;
                }
                recomputeBedTeamColor(loser);
                if (loser == a) {
                    break;
                }
            }
        }
    }

    private static int scoreBedColorConfidence(TrackedBed bed, TeamColor color) {
        int score = 0;
        if (bed.pairedFoot) {
            score += 8;
        }
        if (bed.status == TrackedBed.STATUS_LIVE) {
            score += 4;
        }
        if (bed.votedColor == color) {
            score += 4;
        }
        if (bed.playerColor == color) {
            score += 6;
        }
        int votes = bed.playerVotes[color.ordinal()];
        return score + (votes > 20 ? 20 : votes);
    }

    private void tryAnchorHomeBase(EntityPlayer player, boolean alwaysActive) {
        if (this.homeAnchored && !this.anchorPending) {
            TrackedBed own = findOwnBed();
            if (own != null && own.active) {
                if (this.localTeamColor != TeamColor.UNKNOWN && own.teamColor != this.localTeamColor) {
                    own.teamColor = this.localTeamColor;
                }
                return;
            }
            this.homeAnchored = false;
            this.anchorPending = true;
        }
        anchorHomeAt(player.posX, player.posY, player.posZ, alwaysActive);
    }

    void anchorHomeAt(double playerX, double playerY, double playerZ, boolean alwaysActive) {
        if (this.bedCount == 0) {
            return;
        }

        double maxSpawnDistSq = HOME_SPAWN_ANCHOR_RANGE * HOME_SPAWN_ANCHOR_RANGE;
        boolean inAnchorGrace = this.matchStartTick >= 0
            && (this.tickCounter - this.matchStartTick) <= MATCH_START_ANCHOR_GRACE_TICKS;
        TrackedBed matchingSpawnBed = null;
        double matchingSpawnDistSq = maxSpawnDistSq;

        TrackedBed unconfirmedSpawnBed = null;
        double unconfirmedSpawnDistSq = maxSpawnDistSq;

        TrackedBed teamFallbackBed = null;
        double teamFallbackDistSq = Double.MAX_VALUE;

        TrackedBed nearestBed = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (int i = 0; i < this.bedCount; i++) {
            TrackedBed bed = this.beds[i];
            if (!bed.active || bed.status == TrackedBed.STATUS_DESTROYED) {
                continue;
            }
            double dSq = bed.distSqTo(playerX, playerY, playerZ);
            boolean matchesLocal = this.localTeamColor != TeamColor.UNKNOWN
                && bed.teamColor == this.localTeamColor;

            if (dSq <= maxSpawnDistSq) {
                boolean noisyOrUnknownVote = bed.votedColor == TeamColor.UNKNOWN
                    || bed.votedColor == TeamColor.GRAY
                    || bed.votedColor == TeamColor.WHITE
                    || bed.votedColor == this.localTeamColor;
                if (matchesLocal && dSq <= matchingSpawnDistSq) {
                    matchingSpawnDistSq = dSq;
                    matchingSpawnBed = bed;
                } else if ((inAnchorGrace || noisyOrUnknownVote)
                    && (bed.playerColor == TeamColor.UNKNOWN || bed.playerColor == this.localTeamColor)) {
                    // Decorative clay/glass near spawn (e.g. gray island trim) must never block anchoring
                    // the home bed inside HOME_SPAWN_ANCHOR_RANGE when player color matches or is unconfirmed.
                    if (dSq <= unconfirmedSpawnDistSq) {
                        unconfirmedSpawnDistSq = dSq;
                        unconfirmedSpawnBed = bed;
                    }
                }
            }

            if (matchesLocal && dSq < teamFallbackDistSq) {
                teamFallbackDistSq = dSq;
                teamFallbackBed = bed;
            }

            if (dSq < nearestDistSq) {
                nearestDistSq = dSq;
                nearestBed = bed;
            }
        }

        TrackedBed chosen = matchingSpawnBed;
        if (chosen == null && unconfirmedSpawnBed != null && (teamFallbackBed == null || inAnchorGrace)) {
            chosen = unconfirmedSpawnBed;
        }
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
        chosen.votedColor = TeamColor.UNKNOWN;
        chosen.playerColor = TeamColor.UNKNOWN;
        Arrays.fill(chosen.playerVotes, 0);
        chosen.teamColor = this.localTeamColor;
        this.ownBedIndex = chosen.index;
        this.homeX = chosen.getCenterX();
        this.homeY = chosen.getCenterY();
        this.homeZ = chosen.getCenterZ();
        this.homeAnchored = true;
        this.anchorPending = false;
    }

    void setTestRosterState(int presentMask, int aliveMask, TeamColor localTeam, boolean inMatch) {
        this.presentTeamsMask = presentMask;
        this.bedAliveMask = aliveMask;
        this.scoreboardLocalTeam = localTeam != null ? localTeam : TeamColor.UNKNOWN;
        if (this.scoreboardLocalTeam != TeamColor.UNKNOWN) {
            this.localTeamColor = this.scoreboardLocalTeam;
        }
        this.inBedWarsMatch = inMatch;
    }

    int registerTestBed(int hx, int hy, int hz, TeamColor votedColor, TeamColor playerColor, byte status) {
        TrackedBed slot = allocateBedSlot();
        if (slot == null) {
            return -1;
        }
        slot.resetSlot();
        slot.active = true;
        slot.headX = hx;
        slot.headY = hy;
        slot.headZ = hz;
        slot.pairedFoot = true;
        slot.status = status;
        slot.votedColor = votedColor != null ? votedColor : TeamColor.UNKNOWN;
        slot.playerColor = playerColor != null ? playerColor : TeamColor.UNKNOWN;
        recomputeBedTeamColor(slot);
        return slot.index;
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

    /**
     * Extracts the last BedWars {@link TeamColor} code in a formatted prefix string (handling
     * Hypixel prefixes such as {@code "§7[123✫] §c§lR §c"}).
     *
     * @param text formatted prefix text
     * @return last matched color, or {@link TeamColor#UNKNOWN}
     */
    public static TeamColor extractLastColorFromFormattedText(String text) {
        if (text == null || text.isEmpty()) {
            return TeamColor.UNKNOWN;
        }
        TeamColor last = TeamColor.UNKNOWN;
        int len = text.length();
        for (int i = 0; i < len - 1; i++) {
            if (text.charAt(i) == '\u00a7') {
                TeamColor color = TeamColor.fromChatColorChar(text.charAt(i + 1));
                if (color != TeamColor.UNKNOWN) {
                    last = color;
                }
                i++;
            }
        }
        return last;
    }

    /**
     * Extracts a player's team color from a scoreboard team prefix, resolving trailing reset-to-white
     * Chinese prefixes such as {@code "§e[雾雨黄] §f"} while preserving Hypixel {@code "§f§lW §f"}.
     *
     * @param prefix formatted team prefix text
     * @return resolved {@link TeamColor}, or {@link TeamColor#UNKNOWN}
     */
    public static TeamColor extractPrefixTeamColor(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return TeamColor.UNKNOWN;
        }
        TeamColor lastColor = TeamColor.UNKNOWN;
        TeamColor activeColor = TeamColor.UNKNOWN;
        TeamColor lastVisibleColor = TeamColor.UNKNOWN;
        boolean hasVisibleAfterLastColor = false;
        int len = prefix.length();
        for (int i = 0; i < len; i++) {
            char c = prefix.charAt(i);
            if (c == '\u00a7') {
                if (i + 1 < len) {
                    char code = toAsciiLower(prefix.charAt(i + 1));
                    if (code == 'r') {
                        activeColor = TeamColor.UNKNOWN;
                    } else {
                        TeamColor parsed = TeamColor.fromChatColorChar(code);
                        if (parsed != TeamColor.UNKNOWN) {
                            lastColor = parsed;
                            activeColor = parsed;
                            hasVisibleAfterLastColor = false;
                        }
                    }
                    i++;
                }
                continue;
            }
            if (!isSpaceChar(c)) {
                hasVisibleAfterLastColor = true;
                if (activeColor != TeamColor.UNKNOWN) {
                    lastVisibleColor = activeColor;
                }
            }
        }
        if (lastColor == TeamColor.WHITE
            && !hasVisibleAfterLastColor
            && isChromaticTeamColor(lastVisibleColor)) {
            return lastVisibleColor;
        }
        return lastColor;
    }

    private static boolean isChromaticTeamColor(TeamColor color) {
        return color == TeamColor.RED
            || color == TeamColor.BLUE
            || color == TeamColor.GREEN
            || color == TeamColor.YELLOW
            || color == TeamColor.AQUA
            || color == TeamColor.PINK;
    }

    private static TeamColor extractFirstColorFromFormattedText(CharSequence text) {
        if (text == null || text.length() < 2) {
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

    /**
     * Extracts the active formatting {@link TeamColor} directly preceding {@code playerName},
     * ignoring leading rank brackets and trailing guild tags.
     *
     * @param formattedText formatted display name
     * @param playerName    plain player name to locate
     * @return active team color before {@code playerName}, or {@link TeamColor#UNKNOWN}
     */
    public static TeamColor extractColorBeforeName(String formattedText, String playerName) {
        if (formattedText == null || formattedText.isEmpty() || playerName == null || playerName.isEmpty()) {
            return TeamColor.UNKNOWN;
        }
        int len = formattedText.length();
        int nameLen = playerName.length();
        TeamColor activeColor = TeamColor.UNKNOWN;
        char prevVisible = 0;

        for (int i = 0; i < len; i++) {
            char c = formattedText.charAt(i);
            if (c == '\u00a7') {
                if (i + 1 < len) {
                    char code = formattedText.charAt(i + 1);
                    char lower = toAsciiLower(code);
                    if (lower == 'r') {
                        activeColor = TeamColor.UNKNOWN;
                    } else {
                        TeamColor parsed = TeamColor.fromChatColorChar(lower);
                        if (parsed != TeamColor.UNKNOWN) {
                            activeColor = parsed;
                        }
                    }
                    i++;
                }
                continue;
            }

            if (c == playerName.charAt(0) && !isIdentifierChar(prevVisible)) {
                int matchIdx = matchNameIgnoringFormatting(formattedText, i, playerName, nameLen);
                if (matchIdx >= 0) {
                    return activeColor;
                }
            }
            prevVisible = c;
        }
        return TeamColor.UNKNOWN;
    }

    private static int matchNameIgnoringFormatting(String formatted, int start, String name, int nameLen) {
        int len = formatted.length();
        int fi = start;
        int ni = 0;
        while (fi < len && ni < nameLen) {
            char c = formatted.charAt(fi);
            if (c == '\u00a7') {
                if (fi + 1 >= len) {
                    return -1;
                }
                fi += 2;
                continue;
            }
            if (c != name.charAt(ni)) {
                return -1;
            }
            fi++;
            ni++;
        }
        if (ni < nameLen) {
            return -1;
        }
        while (fi < len && formatted.charAt(fi) == '\u00a7' && fi + 1 < len) {
            fi += 2;
        }
        if (fi < len && isIdentifierChar(formatted.charAt(fi))) {
            return -1;
        }
        return fi;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '_';
    }

    private static TeamColor resolveTeamFromRegisteredName(String regName) {
        if (regName == null || regName.isEmpty()) {
            return TeamColor.UNKNOWN;
        }
        int len = regName.length();
        if (containsCamelOrDelimitedWord(regName, len, "red")) {
            return TeamColor.RED;
        }
        if (containsCamelOrDelimitedWord(regName, len, "blue")) {
            return TeamColor.BLUE;
        }
        if (containsCamelOrDelimitedWord(regName, len, "green")) {
            return TeamColor.GREEN;
        }
        if (containsCamelOrDelimitedWord(regName, len, "yellow")) {
            return TeamColor.YELLOW;
        }
        if (containsCamelOrDelimitedWord(regName, len, "aqua")
            || containsCamelOrDelimitedWord(regName, len, "cyan")) {
            return TeamColor.AQUA;
        }
        if (containsCamelOrDelimitedWord(regName, len, "white")) {
            return TeamColor.WHITE;
        }
        if (containsCamelOrDelimitedWord(regName, len, "pink")) {
            return TeamColor.PINK;
        }
        if (containsCamelOrDelimitedWord(regName, len, "gray")
            || containsCamelOrDelimitedWord(regName, len, "grey")) {
            return TeamColor.GRAY;
        }
        return detectTeamRowColor(regName, 0, len);
    }

    private static boolean containsCamelOrDelimitedWord(String text, int hLen, String needle) {
        int nLen = needle.length();
        if (hLen < nLen) {
            return false;
        }
        int limit = hLen - nLen;
        for (int i = 0; i <= limit; i++) {
            if (!regionMatchesIgnoreCase(text, i, needle, nLen)) {
                continue;
            }
            boolean leftOk = i == 0
                || !isAsciiLetter(text.charAt(i - 1))
                || (isAsciiLower(text.charAt(i - 1)) && isAsciiUpper(text.charAt(i)));
            int end = i + nLen;
            boolean rightOk = end == hLen
                || !isAsciiLetter(text.charAt(end))
                || isAsciiUpper(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
        }
        return false;
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

    private static int findColonIndex(CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == ':' || c == '\uff1a') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAliveCountAfterColon(CharSequence text, int colonIdx) {
        int len = text.length();
        int i = colonIdx + 1;
        while (i < len && text.charAt(i) == ' ') {
            i++;
        }
        if (i >= len) {
            return false;
        }
        char first = text.charAt(i);
        if (first < '0' || first > '9') {
            return false;
        }
        while (i < len && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        while (i < len && text.charAt(i) == ' ') {
            i++;
        }
        if (i == len) {
            return true;
        }
        // Only treat trailing YOU/你 markers after digits as an eliminated-bed alive count;
        // lobby stat rows such as "Kills: 15 Today" or "Coins: 5,000" return false.
        int remLen = len - i;
        return containsAsciiWordIgnoreCase(text, i, len, "you")
            || (remLen >= 1 && containsSequence(text, "\u4f60") && i <= findCharIndex(text, '\u4f60'));
    }

    private static int findCharIndex(CharSequence text, char target) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static TeamColor detectTeamRowColor(CharSequence text, int endIdx) {
        return detectTeamRowColor(text, 0, endIdx);
    }

    private static TeamColor detectTeamRowColor(CharSequence text, int startIdx, int endIdx) {
        if (text == null || endIdx <= startIdx) {
            return TeamColor.UNKNOWN;
        }
        int safeStart = startIdx < 0 ? 0 : startIdx;
        int safeEnd = endIdx > text.length() ? text.length() : endIdx;
        if (safeEnd <= safeStart) {
            return TeamColor.UNKNOWN;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "red")
            || containsSequence(text, safeStart, safeEnd, "\u7ea2\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u7ea2")) {
            return TeamColor.RED;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "blue")
            || containsSequence(text, safeStart, safeEnd, "\u84dd\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u84dd")) {
            return TeamColor.BLUE;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "green")
            || containsSequence(text, safeStart, safeEnd, "\u7eff\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u7eff")) {
            return TeamColor.GREEN;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "yellow")
            || containsSequence(text, safeStart, safeEnd, "\u9ec4\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u9ec4")) {
            return TeamColor.YELLOW;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "aqua")
            || containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "cyan")
            || containsSequence(text, safeStart, safeEnd, "\u9752\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u9752")) {
            return TeamColor.AQUA;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "white")
            || containsSequence(text, safeStart, safeEnd, "\u767d\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u767d")) {
            return TeamColor.WHITE;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "pink")
            || containsSequence(text, safeStart, safeEnd, "\u7c89\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u7c89")) {
            return TeamColor.PINK;
        }
        if (containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "gray")
            || containsAsciiWordIgnoreCase(text, safeStart, safeEnd, "grey")
            || containsSequence(text, safeStart, safeEnd, "\u7070\u961f")
            || containsSequence(text, safeStart, safeEnd, "\u7070")) {
            return TeamColor.GRAY;
        }
        return TeamColor.UNKNOWN;
    }

    private static int findColonIndexRange(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == ':' || c == '\uff1a') {
                return i;
            }
        }
        return -1;
    }

    private static int skipSpaces(CharSequence text, int start, int end) {
        int i = start;
        while (i < end && isSpaceChar(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isSpaceChar(char c) {
        return c == ' ' || c == '\t' || c == '\u00a0';
    }

    private static boolean isLocalTeamLabel(CharSequence text, int start, int end) {
        return equalsRangeIgnoreCase(text, start, end, "\u961f\u4f0d")
            || equalsRangeIgnoreCase(text, start, end, "\u4f60\u7684\u961f\u4f0d")
            || equalsRangeIgnoreCase(text, start, end, "\u6211\u7684\u961f\u4f0d")
            || equalsRangeIgnoreCase(text, start, end, "\u5f53\u524d\u961f\u4f0d")
            || equalsRangeIgnoreCase(text, start, end, "\u6240\u5c5e\u961f\u4f0d")
            || equalsRangeIgnoreCase(text, start, end, "\u6240\u5728\u961f\u4f0d")
            || equalsRangeIgnoreCase(text, start, end, "\u9635\u8425")
            || equalsRangeIgnoreCase(text, start, end, "team")
            || equalsRangeIgnoreCase(text, start, end, "your team");
    }

    private static boolean equalsRangeIgnoreCase(CharSequence text, int start, int end, String target) {
        int tLen = target.length();
        if (end - start != tLen) {
            return false;
        }
        return regionMatchesIgnoreCase(text, start, target, tLen);
    }

    private static int findNextLocalTeamFieldEnd(CharSequence text, int start, int len) {
        int best = len;
        for (int i = start; i < best; i++) {
            char c = text.charAt(i);
            if (c == ':' || c == '\uff1a') {
                best = i;
                break;
            }
        }
        best = minPositiveIndex(best, indexOfSequence(text, start, best, "\u4eba\u6570"));
        best = minPositiveIndex(best, indexOfSequence(text, start, best, "\u5b58\u6d3b"));
        best = minPositiveIndex(best, indexOfSequence(text, start, best, "\u6740\u654c"));
        best = minPositiveIndex(best, indexOfSequence(text, start, best, "\u51fb\u6740"));
        best = minPositiveIndex(best, indexOfAsciiWordIgnoreCase(text, start, best, "players"));
        return best;
    }

    private static int minPositiveIndex(int currentBest, int candidate) {
        if (candidate >= 0 && candidate < currentBest) {
            return candidate;
        }
        return currentBest;
    }

    private static boolean isTeamStatusIcon(char c) {
        return isCheckmarkIcon(c) || isHeartIcon(c) || isCrossOrDeadIcon(c);
    }

    private static boolean isCheckmarkIcon(char c) {
        return c == '\u2714' || c == '\u2713' || c == '\u221a' || c == '\u2611';
    }

    private static boolean isHeartIcon(char c) {
        return c == '\u2764' || c == '\u2665' || c == '\u2765' || c == '\u2763';
    }

    private static boolean isCrossOrDeadIcon(char c) {
        // Explicitly exclude multiplication sign '\u00d7' (used in e.g. "资源×2").
        return c == '\u2717'
            || c == '\u2718'
            || c == '\u2716'
            || c == '\u2715'
            || c == '\u2612'
            || c == '\u2661';
    }

    private static boolean isTeamSeparatorPipe(char c) {
        return c == '|' || c == '\u00a6' || c == '\u2502' || c == '\u258f' || c == '\uff5c';
    }

    private static boolean isLabelPunctuation(char c) {
        return c == '['
            || c == ']'
            || c == '('
            || c == ')'
            || c == '\u3010'
            || c == '\u3011'
            || c == '\uff08'
            || c == '\uff09'
            || isTeamSeparatorPipe(c)
            || c == '-'
            || c == '/'
            || c == ':'
            || c == '\uff1a';
    }

    private static int findFormattingStateAtCleanIndex(CharSequence rawFormatted, int targetCleanIdx) {
        if (rawFormatted == null || targetCleanIdx < 0) {
            return 0;
        }
        int activeColor = 0;
        boolean strikethrough = false;
        int cleanPos = 0;
        int rawLen = rawFormatted.length();
        for (int i = 0; i < rawLen; i++) {
            char c = rawFormatted.charAt(i);
            if (c == '\u00a7') {
                if (i + 1 < rawLen) {
                    char code = toAsciiLower(rawFormatted.charAt(i + 1));
                    if (code == 'r') {
                        activeColor = 0;
                        strikethrough = false;
                    } else if (code == 'm') {
                        strikethrough = true;
                    } else if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                        activeColor = code;
                        strikethrough = false;
                    }
                    i++;
                }
                continue;
            }
            if (cleanPos == targetCleanIdx) {
                return activeColor | (strikethrough ? (1 << 16) : 0);
            }
            cleanPos++;
        }
        return 0;
    }

    private static boolean isDeadHeartFormatting(int formatState) {
        if ((formatState & (1 << 16)) != 0) {
            return true;
        }
        char color = (char) (formatState & 0xFFFF);
        return color == '7' || color == '8' || color == '0';
    }

    private static boolean isStandaloneXCrossAnchor(CharSequence cleanText, int idx, int len) {
        if (idx > 0 && isAsciiAlphaNum(cleanText.charAt(idx - 1))) {
            return false;
        }
        int p = skipSpaces(cleanText, idx + 1, len);
        if (p >= len || !isTeamSeparatorPipe(cleanText.charAt(p))) {
            return false;
        }
        int q = skipSpaces(cleanText, p + 1, len);
        return parseStandaloneIntegerEnd(cleanText, q, len) > q;
    }

    private static boolean isBarePipeCountAnchor(CharSequence cleanText, int segStart, int pipeIdx, int len) {
        int q = skipSpaces(cleanText, pipeIdx + 1, len);
        if (parseStandaloneIntegerEnd(cleanText, q, len) <= q) {
            return false;
        }
        return detectTeamRowColor(cleanText, segStart, pipeIdx) != TeamColor.UNKNOWN;
    }

    private static boolean hasLabelContent(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (!isSpaceChar(c) && !isLabelPunctuation(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMoreStatusIcons(CharSequence text, int start) {
        int len = text.length();
        for (int i = start; i < len; i++) {
            if (isTeamStatusIcon(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int findIconFirstTrailingCount(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (isTeamSeparatorPipe(c) || c == ':' || c == '\uff1a') {
                int q = skipSpaces(text, i + 1, end);
                int digitEnd = parseStandaloneIntegerEnd(text, q, end);
                if (digitEnd > q) {
                    return parseNonNegativeInt(text, q, digitEnd);
                }
            }
        }
        return -1;
    }

    private static int parseStandaloneIntegerEnd(CharSequence text, int start, int len) {
        if (start < 0 || start >= len) {
            return -1;
        }
        int i = start;
        while (i < len && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        if (i == start) {
            return -1;
        }
        if (i < len) {
            char next = text.charAt(i);
            if (next == ':' || next == '\uff1a' || next == '/' || next == '.' || isAsciiLetter(next)) {
                return -1;
            }
        }
        return i;
    }

    private static int parseNonNegativeInt(CharSequence text, int start, int end) {
        int val = 0;
        for (int i = start; i < end; i++) {
            val = val * 10 + (text.charAt(i) - '0');
            if (val > 100000) {
                return 100000;
            }
        }
        return val;
    }

    private static int matchLocalMarkerEnd(CharSequence text, int start, int len) {
        int m = skipSpaces(text, start, len);
        if (m >= len) {
            return -1;
        }
        int matched = matchWrappedMarker(text, m, len, "(", ")");
        if (matched >= 0) {
            return matched;
        }
        matched = matchWrappedMarker(text, m, len, "[", "]");
        if (matched >= 0) {
            return matched;
        }
        matched = matchWrappedMarker(text, m, len, "\uff08", "\uff09");
        if (matched >= 0) {
            return matched;
        }
        matched = matchWrappedMarker(text, m, len, "\u3010", "\u3011");
        if (matched >= 0) {
            return matched;
        }
        if (m + 3 <= len
            && regionMatchesIgnoreCase(text, m, "you", 3)
            && (m + 3 == len || !isAsciiLetter(text.charAt(m + 3)))) {
            return m + 3;
        }
        if (text.charAt(m) == '\u4f60') {
            return m + 1;
        }
        return -1;
    }

    private static int matchWrappedMarker(CharSequence text, int start, int len, String open, String close) {
        int oLen = open.length();
        int cLen = close.length();
        int inner = start + oLen;
        if (inner + 3 + cLen <= len
            && regionMatchesIgnoreCase(text, start, open, oLen)
            && regionMatchesIgnoreCase(text, inner, "you", 3)
            && regionMatchesIgnoreCase(text, inner + 3, close, cLen)) {
            return inner + 3 + cLen;
        }
        if (inner + 1 + cLen <= len
            && regionMatchesIgnoreCase(text, start, open, oLen)
            && text.charAt(inner) == '\u4f60'
            && regionMatchesIgnoreCase(text, inner + 1, close, cLen)) {
            return inner + 1 + cLen;
        }
        return -1;
    }

    private static TeamColor extractFirstTeamColorInCleanRange(
        CharSequence rawFormatted,
        CharSequence cleanText,
        int start,
        int end
    ) {
        if (rawFormatted == null || cleanText == null) {
            return TeamColor.UNKNOWN;
        }
        for (int idx = start; idx < end; idx++) {
            char c = cleanText.charAt(idx);
            if (isSpaceChar(c) || isTeamStatusIcon(c) || isLabelPunctuation(c)) {
                continue;
            }
            int state = findFormattingStateAtCleanIndex(rawFormatted, idx);
            TeamColor color = TeamColor.fromChatColorChar((char) (state & 0xFFFF));
            if (color != TeamColor.UNKNOWN) {
                return color;
            }
        }
        return TeamColor.UNKNOWN;
    }

    private static boolean containsNonTeamStatKeyword(CharSequence text, int start, int end) {
        return containsSequence(text, start, end, "\u8840\u91cf")
            || containsSequence(text, start, end, "\u751f\u547d")
            || containsSequence(text, start, end, "\u9965\u997f")
            || containsSequence(text, start, end, "\u5fc3")
            || containsSequence(text, start, end, "\u4e8b\u4ef6")
            || containsSequence(text, start, end, "\u65f6\u95f4")
            || containsSequence(text, start, end, "\u4eba\u6570")
            || containsSequence(text, start, end, "\u5730\u56fe")
            || containsSequence(text, start, end, "\u6740\u654c")
            || containsSequence(text, start, end, "\u51fb\u6740")
            || containsAsciiWordIgnoreCase(text, start, end, "health")
            || containsAsciiWordIgnoreCase(text, start, end, "hp")
            || containsAsciiWordIgnoreCase(text, start, end, "food")
            || containsAsciiWordIgnoreCase(text, start, end, "hunger")
            || containsAsciiWordIgnoreCase(text, start, end, "kills")
            || containsAsciiWordIgnoreCase(text, start, end, "wins");
    }

    private static boolean containsAsciiWordIgnoreCase(CharSequence haystack, int start, int end, String needle) {
        return indexOfAsciiWordIgnoreCase(haystack, start, end, needle) >= 0;
    }

    private static int indexOfAsciiWordIgnoreCase(CharSequence haystack, int start, int end, String needle) {
        int nLen = needle.length();
        if (end - start < nLen) {
            return -1;
        }
        int limit = end - nLen;
        for (int i = start; i <= limit; i++) {
            if (!regionMatchesIgnoreCase(haystack, i, needle, nLen)) {
                continue;
            }
            boolean leftOk = i == start || !isAsciiLetter(haystack.charAt(i - 1));
            int after = i + nLen;
            boolean rightOk = after == end || !isAsciiLetter(haystack.charAt(after));
            if (leftOk && rightOk) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsSequence(CharSequence haystack, int start, int end, String needle) {
        return indexOfSequence(haystack, start, end, needle) >= 0;
    }

    private static int indexOfSequence(CharSequence haystack, int start, int end, String needle) {
        int nLen = needle.length();
        if (nLen == 0) {
            return start;
        }
        if (end - start < nLen) {
            return -1;
        }
        int limit = end - nLen;
        for (int i = start; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < nLen; j++) {
                if (haystack.charAt(i + j) != needle.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAsciiAlphaNum(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiLower(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean isAsciiUpper(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static TeamColor detectTeamMention(CharSequence text) {
        TeamColor beforeBed = detectChineseTeamBeforeBedKeyword(text);
        if (beforeBed != TeamColor.UNKNOWN) {
            return beforeBed;
        }
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

    private static TeamColor detectChineseTeamBeforeBedKeyword(CharSequence text) {
        if (text == null) {
            return TeamColor.UNKNOWN;
        }
        int len = text.length();
        int earliest = len;
        earliest = minPositiveIndex(earliest, indexOfSequence(text, 0, earliest, "\u7684\u5e8a"));
        earliest = minPositiveIndex(earliest, indexOfSequence(text, 0, earliest, "\u5e8a\u88ab"));
        earliest = minPositiveIndex(earliest, indexOfSequence(text, 0, earliest, "\u5e8a\u5df2"));
        if (earliest >= len) {
            return TeamColor.UNKNOWN;
        }

        int pos = earliest - 1;
        while (pos >= 0) {
            char c = text.charAt(pos);
            if (isSpaceChar(c)
                || c == ']'
                || c == '\u3011'
                || c == ')'
                || c == '\uff09'
                || c == '\u961f') {
                pos--;
                continue;
            }
            return fromChineseColorChar(c);
        }
        return TeamColor.UNKNOWN;
    }

    private static TeamColor fromChineseColorChar(char c) {
        switch (c) {
            case '\u7ea2':
                return TeamColor.RED;
            case '\u84dd':
                return TeamColor.BLUE;
            case '\u7eff':
                return TeamColor.GREEN;
            case '\u9ec4':
                return TeamColor.YELLOW;
            case '\u9752':
                return TeamColor.AQUA;
            case '\u767d':
                return TeamColor.WHITE;
            case '\u7c89':
                return TeamColor.PINK;
            case '\u7070':
                return TeamColor.GRAY;
            default:
                return TeamColor.UNKNOWN;
        }
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
        return haystack != null && containsSequence(haystack, 0, haystack.length(), needle);
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
