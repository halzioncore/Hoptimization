package com.hoptimization.modules.visual.jarvis;

import com.hoptimization.modules.misc.LanguageModule;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import java.util.List;

/**
 * Cylindrical home-base intrusion monitor with held-item threat classification,
 * priority sorting, per-entity hysteresis, and alert cooldown tracking.
 */
public final class BaseIntrusionMonitor {
    public static final int MAX_INTRUDERS = 8;
    public static final double HYSTERESIS_BLOCKS = 4.0D;
    public static final long ALERT_COOLDOWN_NANOS = 8_000_000_000L;
    public static final int MAX_TRACKED = 32;

    private static final double MIN_DELTA_Y = -10.0D;
    private static final double MAX_DELTA_Y = 40.0D;
    private static final double HIGH_GROUND_DELTA_Y = 5.0D;

    private static final String KEY_ALERT_CHAT = "ui.hoptimization.jarvis.alert.chat";
    private static final String KEY_THREAT_TNT = "ui.hoptimization.jarvis.threat.tnt";
    private static final String KEY_THREAT_FIREBALL = "ui.hoptimization.jarvis.threat.fireball";
    private static final String KEY_THREAT_PICKAXE = "ui.hoptimization.jarvis.threat.pickaxe";
    private static final String KEY_THREAT_AXE = "ui.hoptimization.jarvis.threat.axe";
    private static final String KEY_THREAT_SHEARS = "ui.hoptimization.jarvis.threat.shears";
    private static final String KEY_THREAT_INVIS = "ui.hoptimization.jarvis.threat.invisible";
    private static final String KEY_THREAT_HIGH_GROUND = "ui.hoptimization.jarvis.threat.high_ground";

    /**
     * Pre-allocated snapshot of a single active enemy intruder inside the base cylinder.
     */
    public static final class IntruderEntry {
        public static final byte THREAT_NONE = 0;
        public static final byte THREAT_TNT = 1;
        public static final byte THREAT_FIREBALL = 2;
        public static final byte THREAT_PICKAXE = 3;
        public static final byte THREAT_AXE = 4;
        public static final byte THREAT_SHEARS = 5;

        private int entityId;
        private String playerName = "";
        private BedWarsContext.TeamColor teamColor = BedWarsContext.TeamColor.UNKNOWN;
        private double distXZ;
        private double deltaY;
        private boolean invisible;
        private byte threatKind;

        private void set(
            int entityId,
            String playerName,
            BedWarsContext.TeamColor teamColor,
            double distXZ,
            double deltaY,
            boolean invisible,
            byte threatKind
        ) {
            this.entityId = entityId;
            this.playerName = playerName != null ? playerName : "";
            this.teamColor = teamColor != null ? teamColor : BedWarsContext.TeamColor.UNKNOWN;
            this.distXZ = distXZ;
            this.deltaY = deltaY;
            this.invisible = invisible;
            this.threatKind = threatKind;
        }

        private void reset() {
            this.entityId = 0;
            this.playerName = "";
            this.teamColor = BedWarsContext.TeamColor.UNKNOWN;
            this.distXZ = 0.0D;
            this.deltaY = 0.0D;
            this.invisible = false;
            this.threatKind = THREAT_NONE;
        }

        public int getEntityId() {
            return this.entityId;
        }

        public String getPlayerName() {
            return this.playerName;
        }

        public BedWarsContext.TeamColor getTeamColor() {
            return this.teamColor;
        }

        public double getDistXZ() {
            return this.distXZ;
        }

        public double getDeltaY() {
            return this.deltaY;
        }

        public boolean isHighGround() {
            return this.deltaY >= HIGH_GROUND_DELTA_Y;
        }

        public boolean isInvisible() {
            return this.invisible;
        }

        public byte getThreatKind() {
            return this.threatKind;
        }
    }

    private final IntruderEntry[] intruders = new IntruderEntry[MAX_TRACKED];
    private final int[] trackedIds = new int[MAX_TRACKED];
    private final boolean[] insideZone = new boolean[MAX_TRACKED];
    private final long[] lastNotifyNanos = new long[MAX_TRACKED];
    private final boolean[] slotActive = new boolean[MAX_TRACKED];
    private final boolean[] hasNotified = new boolean[MAX_TRACKED];
    private final int[] lastSeenSweep = new int[MAX_TRACKED];

    private final StringBuilder chatScratch = new StringBuilder(96);
    private final StringBuilder tagScratch = new StringBuilder(48);

    private int activeCount;
    private long lastActiveNanos;
    private int sweepSeq;
    private World lastWorld;

    /**
     * Constructs a base intrusion monitor with pre-allocated intruder and cooldown tables.
     */
    public BaseIntrusionMonitor() {
        for (int i = 0; i < MAX_TRACKED; i++) {
            this.intruders[i] = new IntruderEntry();
        }
    }

    /**
     * Updates active base intruders, priority ordering, hysteresis state, and sound/chat alerts.
     *
     * @param mc             Minecraft client instance
     * @param ctx            current BedWars context
     * @param enabled        whether base intrusion monitoring is enabled
     * @param baseRange      horizontal cylindrical alert radius in blocks
     * @param alertInvisible true to include invisible enemies
     * @param alertSound     true to play a client-only sound on new alert / cooldown expiry
     * @param alertChat      true to post a client-only chat notification on new alert / cooldown expiry
     */
    public void update(
        Minecraft mc,
        BedWarsContext ctx,
        boolean enabled,
        double baseRange,
        boolean alertInvisible,
        boolean alertSound,
        boolean alertChat
    ) {
        if (!enabled || mc == null || mc.theWorld == null || mc.thePlayer == null
            || ctx == null || !ctx.isEffectiveActive() || !ctx.hasHomeAnchor()) {
            if (mc != null && mc.theWorld != this.lastWorld) {
                clear();
                this.lastWorld = mc.theWorld;
            }
            this.activeCount = 0;
            return;
        }

        World world = mc.theWorld;
        if (world != this.lastWorld) {
            clear();
            this.lastWorld = world;
        }

        double effectiveRange = baseRange > 0.0D ? baseRange : 0.0D;
        double exitRange = effectiveRange + HYSTERESIS_BLOCKS;
        double homeX = ctx.getHomeX();
        double homeY = ctx.getHomeY();
        double homeZ = ctx.getHomeZ();
        long nowNanos = System.nanoTime();

        this.sweepSeq++;
        int candidateCount = 0;
        boolean playedSoundThisTick = false;

        List<EntityPlayer> players = world.playerEntities;
        if (players != null) {
            for (int i = 0, size = players.size(); i < size; i++) {
                EntityPlayer target = players.get(i);
                if (!BedWarsContext.isValidEnemyPlayer(target, mc.thePlayer, mc)) {
                    continue;
                }
                boolean invisible = target.isInvisible();
                if (invisible && !alertInvisible) {
                    continue;
                }

                double dx = target.posX - homeX;
                double dz = target.posZ - homeZ;
                double distXZ = Math.sqrt(dx * dx + dz * dz);
                double deltaY = target.posY - homeY;
                int entityId = target.getEntityId();

                boolean inCylinder = distXZ <= effectiveRange && deltaY >= MIN_DELTA_Y && deltaY <= MAX_DELTA_Y;
                if (!inCylinder) {
                    int existingSlot = findTrackingSlot(entityId);
                    if (existingSlot >= 0) {
                        this.lastSeenSweep[existingSlot] = this.sweepSeq;
                        if (distXZ > exitRange) {
                            this.insideZone[existingSlot] = false;
                        }
                    }
                    continue;
                }

                byte threatKind = detectHeldThreat(target);
                BedWarsContext.TeamColor teamColor = BedWarsContext.resolvePlayerTeamColor(target);
                String playerName = target.getName();

                if (candidateCount < MAX_TRACKED) {
                    this.intruders[candidateCount++].set(
                        entityId, playerName, teamColor, distXZ, deltaY, invisible, threatKind
                    );
                }

                int slot = findOrAllocateTrackingSlot(entityId, nowNanos);
                this.lastSeenSweep[slot] = this.sweepSeq;
                boolean wasInside = this.insideZone[slot];
                boolean cooldownElapsed = !this.hasNotified[slot]
                    || (nowNanos - this.lastNotifyNanos[slot]) >= ALERT_COOLDOWN_NANOS;

                this.insideZone[slot] = true;
                if (!wasInside || cooldownElapsed) {
                    this.hasNotified[slot] = true;
                    this.lastNotifyNanos[slot] = nowNanos;
                    if (alertSound && !playedSoundThisTick) {
                        mc.thePlayer.playSound("note.pling", 0.75F, 1.55F);
                        playedSoundThisTick = true;
                    }
                    if (alertChat) {
                        String msg = formatChatAlert(playerName, teamColor, distXZ, deltaY, invisible, threatKind);
                        mc.thePlayer.addChatMessage(new ChatComponentText(msg));
                    }
                }
            }
        }

        // Clear tracked entries for entities that left the world or became ineligible.
        for (int s = 0; s < MAX_TRACKED; s++) {
            if (this.slotActive[s] && this.lastSeenSweep[s] != this.sweepSeq) {
                this.slotActive[s] = false;
                this.trackedIds[s] = 0;
                this.insideZone[s] = false;
                this.hasNotified[s] = false;
                this.lastNotifyNanos[s] = 0L;
            }
        }

        // Insertion sort candidates in-place by threat priority then nearest horizontal distance.
        for (int i = 1; i < candidateCount; i++) {
            IntruderEntry key = this.intruders[i];
            int j = i - 1;
            while (j >= 0 && comparePriority(this.intruders[j], key) > 0) {
                this.intruders[j + 1] = this.intruders[j];
                j--;
            }
            this.intruders[j + 1] = key;
        }

        this.activeCount = candidateCount > MAX_INTRUDERS ? MAX_INTRUDERS : candidateCount;
        if (this.activeCount > 0) {
            this.lastActiveNanos = nowNanos;
        }
    }

    public int getActiveCount() {
        return this.activeCount;
    }

    public IntruderEntry getIntruder(int index) {
        if (index < 0 || index >= this.activeCount) {
            return null;
        }
        return this.intruders[index];
    }

    public boolean hasActiveAlert() {
        return this.activeCount > 0;
    }

    public long getLastActiveNanos() {
        return this.lastActiveNanos;
    }

    /**
     * Clears active intruder entries and resets per-entity hysteresis and cooldown state.
     */
    public void clear() {
        this.activeCount = 0;
        this.lastActiveNanos = 0L;
        for (int i = 0; i < MAX_TRACKED; i++) {
            this.intruders[i].reset();
            this.trackedIds[i] = 0;
            this.insideZone[i] = false;
            this.lastNotifyNanos[i] = 0L;
            this.slotActive[i] = false;
            this.hasNotified[i] = false;
            this.lastSeenSweep[i] = 0;
        }
    }

    private int findTrackingSlot(int entityId) {
        for (int i = 0; i < MAX_TRACKED; i++) {
            if (this.slotActive[i] && this.trackedIds[i] == entityId) {
                return i;
            }
        }
        return -1;
    }

    private int findOrAllocateTrackingSlot(int entityId, long nowNanos) {
        int existing = findTrackingSlot(entityId);
        if (existing >= 0) {
            return existing;
        }

        for (int i = 0; i < MAX_TRACKED; i++) {
            if (!this.slotActive[i]) {
                initTrackingSlot(i, entityId);
                return i;
            }
        }

        // Evict the oldest slot outside the zone first, or the stalest notified slot if all are full.
        int evictIdx = 0;
        long oldestAge = -1L;
        boolean foundOutside = false;
        for (int i = 0; i < MAX_TRACKED; i++) {
            boolean outside = !this.insideZone[i];
            long age = nowNanos - this.lastNotifyNanos[i];
            if (outside && !foundOutside) {
                foundOutside = true;
                oldestAge = age;
                evictIdx = i;
            } else if (outside == foundOutside && age > oldestAge) {
                oldestAge = age;
                evictIdx = i;
            }
        }
        initTrackingSlot(evictIdx, entityId);
        return evictIdx;
    }

    private void initTrackingSlot(int slot, int entityId) {
        this.slotActive[slot] = true;
        this.trackedIds[slot] = entityId;
        this.insideZone[slot] = false;
        this.lastNotifyNanos[slot] = 0L;
        this.hasNotified[slot] = false;
        this.lastSeenSweep[slot] = this.sweepSeq;
    }

    private static byte detectHeldThreat(EntityPlayer target) {
        ItemStack held = target.getHeldItem();
        if (held == null || held.getItem() == null) {
            return IntruderEntry.THREAT_NONE;
        }
        Item item = held.getItem();
        if (item instanceof ItemBlock && ((ItemBlock) item).getBlock() == Blocks.tnt) {
            return IntruderEntry.THREAT_TNT;
        }
        if (item == Items.fire_charge) {
            return IntruderEntry.THREAT_FIREBALL;
        }
        if (item instanceof ItemPickaxe) {
            return IntruderEntry.THREAT_PICKAXE;
        }
        if (item instanceof ItemAxe) {
            return IntruderEntry.THREAT_AXE;
        }
        if (item instanceof ItemShears) {
            return IntruderEntry.THREAT_SHEARS;
        }
        return IntruderEntry.THREAT_NONE;
    }

    private static int comparePriority(IntruderEntry a, IntruderEntry b) {
        int scoreA = computeThreatScore(a);
        int scoreB = computeThreatScore(b);
        if (scoreA != scoreB) {
            return scoreB - scoreA;
        }
        return Double.compare(a.distXZ, b.distXZ);
    }

    private static int computeThreatScore(IntruderEntry entry) {
        int score = 0;
        if (entry.invisible) {
            score += 160;
        }
        switch (entry.threatKind) {
            case IntruderEntry.THREAT_TNT:
                score += 120;
                break;
            case IntruderEntry.THREAT_FIREBALL:
                score += 100;
                break;
            case IntruderEntry.THREAT_PICKAXE:
                score += 30;
                break;
            case IntruderEntry.THREAT_AXE:
                score += 20;
                break;
            case IntruderEntry.THREAT_SHEARS:
                score += 10;
                break;
            default:
                break;
        }
        if (entry.isHighGround()) {
            score += 60;
        }
        return score;
    }

    private String formatChatAlert(
        String playerName,
        BedWarsContext.TeamColor teamColor,
        double distXZ,
        double deltaY,
        boolean invisible,
        byte threatKind
    ) {
        boolean zh = "zh_CN".equals(LanguageModule.getEffectiveLanguageCode());
        int distMeters = (int) Math.round(distXZ);
        String safeName = (playerName != null && !playerName.isEmpty()) ? playerName : "Enemy";
        BedWarsContext.TeamColor color = teamColor != null ? teamColor : BedWarsContext.TeamColor.UNKNOWN;

        this.tagScratch.setLength(0);
        if (invisible) {
            appendTag(this.tagScratch, formatWithFallback(KEY_THREAT_INVIS, zh ? "\u9690\u8eab" : "Invis"));
        }
        if (threatKind == IntruderEntry.THREAT_TNT) {
            appendTag(this.tagScratch, formatWithFallback(KEY_THREAT_TNT, "TNT"));
        } else if (threatKind == IntruderEntry.THREAT_FIREBALL) {
            appendTag(this.tagScratch, formatWithFallback(KEY_THREAT_FIREBALL, zh ? "\u706b\u7403" : "Fireball"));
        } else if (threatKind == IntruderEntry.THREAT_PICKAXE) {
            appendTag(this.tagScratch, formatWithFallback(KEY_THREAT_PICKAXE, zh ? "\u9550" : "Pickaxe"));
        } else if (threatKind == IntruderEntry.THREAT_AXE) {
            appendTag(this.tagScratch, formatWithFallback(KEY_THREAT_AXE, zh ? "\u65a7" : "Axe"));
        } else if (threatKind == IntruderEntry.THREAT_SHEARS) {
            appendTag(this.tagScratch, formatWithFallback(KEY_THREAT_SHEARS, zh ? "\u526a\u5200" : "Shears"));
        }
        if (deltaY >= HIGH_GROUND_DELTA_Y) {
            appendTag(this.tagScratch, formatWithFallback(KEY_THREAT_HIGH_GROUND, zh ? "\u9ad8\u70b9" : "High"));
        }
        String tags = this.tagScratch.toString();

        this.chatScratch.setLength(0);
        this.chatScratch.append('\u00a7').append(color.getChatCode()).append(safeName);
        String coloredPlayer = this.chatScratch.toString();

        String template = LanguageModule.format(KEY_ALERT_CHAT);
        if (template != null && !template.equals(KEY_ALERT_CHAT)) {
            return LanguageModule.format(KEY_ALERT_CHAT, coloredPlayer, Integer.valueOf(distMeters), tags);
        }

        this.chatScratch.setLength(0);
        this.chatScratch.append("\u00a78[\u00a7bJarvis\u00a78] \u00a7c");
        if (zh) {
            this.chatScratch.append("\u57fa\u5730\u5165\u4fb5: ");
        } else {
            this.chatScratch.append("Base Intrusion: ");
        }
        this.chatScratch.append(coloredPlayer)
            .append(" \u00a77(").append(distMeters).append("m)");
        if (!tags.isEmpty()) {
            this.chatScratch.append(" \u00a7e").append(tags);
        }
        return this.chatScratch.toString();
    }

    private static void appendTag(StringBuilder sb, String tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append('[').append(tag).append(']');
    }

    private static String formatWithFallback(String key, String fallback) {
        String value = LanguageModule.format(key);
        if (value == null || value.equals(key)) {
            return fallback;
        }
        return value;
    }
}
