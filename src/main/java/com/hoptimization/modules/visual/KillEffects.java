package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.modules.visual.killeffects.KillEffectInstance;
import com.hoptimization.modules.visual.killeffects.KillEffectsRenderer;
import com.hoptimization.modules.visual.killeffects.KillEffectsTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Replaces vanilla entity death tilting with stylized Soul Sever, Annihilation,
 * Phantom Swords, or Abyss Vortex kill effects.
 */
public class KillEffects extends Module {
    public static final String ID = "kill_effects";
    public static final String MODE_SOUL_SEVER = "soul_sever";
    public static final String MODE_ANNIHILATION = "annihilation";
    public static final String MODE_PHANTOM_SWORDS = "phantom_swords";
    public static final String MODE_ABYSS_VORTEX = "abyss_vortex";
    public static final String TARGET_ALL = "all";
    public static final String TARGET_PLAYERS = "players";
    public static final String TARGET_MOBS = "mobs";

    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;
    private static final double MIN_DIRECTION_DIST_SQ = 1.0E-8D;
    private static final double DESTROY_ATTRIBUTION_RANGE_SQ = 42.0D * 42.0D;
    private static final double SPECTATOR_OTHER_KILL_RANGE_SQ = 24.0D * 24.0D;
    private static final double PRE_TELEPORT_MIN_JUMP_SQ = 64.0D;
    private static final float DEG_TO_RAD = 0.017453292F;

    private static final String[] KILL_CHAT_INDICATORS = {
        "killed", "void", "shot", "knock", "slain", "final", "eliminat", "fell", "died", "blown",
        "\u51fb\u6740", "\u6740", "\u865a\u7a7a", "\u7ec8\u7ed3", "\u6dd8\u6c70", "\u63a8", "\u5c04", "\u5760"
    };
    private static final String[] PASSIVE_CHAT_MARKERS = {
        " by ", "while escaping", "while trying to escape", "to escape", "\u88ab", "\u9003\u79bb", "\u6b7b\u4e8e"
    };

    private static KillEffects instance;

    private final ModeSetting mode;
    private final ModeSetting targets;
    private final BooleanSetting onlyOwnKills;
    private final NumberSetting duration;
    private final ColorSetting soulColor;
    private final NumberSetting weaponScale;
    private final BooleanSetting particles;
    private final BooleanSetting sound;

    private final KillEffectsTracker tracker = new KillEffectsTracker();

    /**
     * Creates the Kill Effects module and registers its settings in display order.
     */
    public KillEffects() {
        super(ID, Category.VISUAL);
        this.mode = addSetting(
            new ModeSetting(
                "mode",
                "setting.hoptimization.kill_effects.mode.name",
                MODE_SOUL_SEVER,
                MODE_SOUL_SEVER,
                MODE_ANNIHILATION,
                MODE_PHANTOM_SWORDS,
                MODE_ABYSS_VORTEX
            )
        );
        this.targets = addSetting(
            new ModeSetting(
                "targets",
                "setting.hoptimization.kill_effects.targets.name",
                TARGET_ALL,
                TARGET_ALL,
                TARGET_PLAYERS,
                TARGET_MOBS
            )
        );
        this.onlyOwnKills = addSetting(
            new BooleanSetting("only_own_kills", "setting.hoptimization.kill_effects.only_own_kills.name", true)
        );
        this.duration = addSetting(
            new NumberSetting(
                "duration",
                "setting.hoptimization.kill_effects.duration.name",
                1.2D,
                0.5D,
                2.5D,
                0.1D,
                "s"
            )
        );
        this.soulColor = addSetting(
            new ColorSetting("soul_color", "setting.hoptimization.kill_effects.soul_color.name", 0xFFDCEEFF, false)
                .visibleWhen(() -> Boolean.valueOf(this.mode.is(MODE_SOUL_SEVER) || this.mode.is(MODE_PHANTOM_SWORDS)))
        );
        this.weaponScale = addSetting(
            new NumberSetting(
                "weapon_scale",
                "setting.hoptimization.kill_effects.weapon_scale.name",
                1.8D,
                1.0D,
                3.0D,
                0.1D,
                "x"
            ).visibleWhen(() -> Boolean.valueOf(this.mode.is(MODE_SOUL_SEVER) || this.mode.is(MODE_PHANTOM_SWORDS)))
        );
        this.particles = addSetting(
            new BooleanSetting("particles", "setting.hoptimization.kill_effects.particles.name", true)
        );
        this.sound = addSetting(
            new BooleanSetting("sound", "setting.hoptimization.kill_effects.sound.name", true)
        );
        instance = this;
    }

    /**
     * Returns the kill visual mode setting.
     *
     * @return mode setting
     */
    public ModeSetting getModeSetting() {
        return this.mode;
    }

    /**
     * Returns the eligible target filter setting.
     *
     * @return targets setting
     */
    public ModeSetting getTargetsSetting() {
        return this.targets;
    }

    /**
     * Returns whether effects are restricted to kills attributed to the local player.
     *
     * @return only own kills setting
     */
    public BooleanSetting getOnlyOwnKillsSetting() {
        return this.onlyOwnKills;
    }

    /**
     * Returns the effect duration setting in seconds.
     *
     * @return duration setting
     */
    public NumberSetting getDurationSetting() {
        return this.duration;
    }

    /**
     * Returns the Soul Sever tint color setting.
     *
     * @return soul color setting
     */
    public ColorSetting getSoulColorSetting() {
        return this.soulColor;
    }

    /**
     * Returns the Soul Sever phantom weapon scale setting.
     *
     * @return weapon scale setting
     */
    public NumberSetting getWeaponScaleSetting() {
        return this.weaponScale;
    }

    /**
     * Returns the particle burst toggle setting.
     *
     * @return particles setting
     */
    public BooleanSetting getParticlesSetting() {
        return this.particles;
    }

    /**
     * Returns the kill sound toggle setting.
     *
     * @return sound setting
     */
    public BooleanSetting getSoundSetting() {
        return this.sound;
    }

    /**
     * Returns the pooled effect tracker owned by this module.
     *
     * @return kill effects tracker
     */
    public KillEffectsTracker getTracker() {
        return this.tracker;
    }

    /**
     * Returns whether Kill Effects is currently initialized and enabled.
     *
     * @return true if active
     */
    public static boolean isActive() {
        KillEffects module = instance;
        return module != null && module.isEnabled();
    }

    /**
     * Handles client death status packet updates ({@code S19PacketEntityStatus} opcode 3).
     *
     * @param entity living entity receiving death status
     */
    public static void onClientEntityDeathStatus(EntityLivingBase entity) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return;
        }
        if (!module.isTargetEligible(entity, mc)) {
            return;
        }
        long nowNanos = System.nanoTime();
        int entityId = entity.getEntityId();
        int identityHash = System.identityHashCode(entity);
        if (module.tracker.isRecentlyDead(entityId, identityHash, nowNanos)) {
            return;
        }
        if (!module.hasOwnKillAttribution(entity, mc, nowNanos)) {
            return;
        }
        module.captureAndRemoveCorpse(entity, mc, nowNanos);
    }

    /**
     * Handles per-tick death updates on client living entities and cancels vanilla death animation when handled.
     *
     * @param entity dying client entity
     * @return true if vanilla {@code onDeathUpdate} should be canceled
     */
    public static boolean onClientEntityDeathTick(EntityLivingBase entity) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return false;
        }
        if (!module.isTargetEligible(entity, mc)) {
            return false;
        }
        long nowNanos = System.nanoTime();
        if (module.tracker.shouldSuppressRender(entity, nowNanos)) {
            return true;
        }
        if (!module.hasOwnKillAttribution(entity, mc, nowNanos)) {
            return false;
        }
        module.captureAndRemoveCorpse(entity, mc, nowNanos);
        return true;
    }

    /**
     * Handles entity metadata updates ({@code S1CPacketEntityMetadata}) for lethal health changes.
     *
     * @param entityId updated entity network id
     */
    public static void onClientEntityMetadata(int entityId) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Entity raw = mc.theWorld.getEntityByID(entityId);
        if (!(raw instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) raw;
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return;
        }
        if (entity.getHealth() > 0.0F || !module.isTargetEligible(entity, mc)) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (module.tracker.isRecentlyDead(entityId, System.identityHashCode(entity), nowNanos)) {
            return;
        }
        if (!module.hasOwnKillAttribution(entity, mc, nowNanos)) {
            return;
        }
        module.captureAndRemoveCorpse(entity, mc, nowNanos);
    }

    /**
     * Captures pre-teleport combat coordinates before large {@code S18PacketEntityTeleport} jumps.
     *
     * @param packetIn incoming entity teleport packet
     */
    public static void onClientEntityPreTeleport(S18PacketEntityTeleport packetIn) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || packetIn == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        int entityId = packetIn.getEntityId();
        Entity raw = mc.theWorld.getEntityByID(entityId);
        if (!(raw instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) raw;
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return;
        }
        if (!module.isTargetEligible(entity, mc) || isAntiCheatBot(entity, mc)) {
            return;
        }
        long nowNanos = System.nanoTime();
        boolean trackedCombat = module.tracker.wasRecentlyAttacked(
            entityId,
            nowNanos,
            KillEffectsTracker.ATTACK_WINDOW_NANOS
        ) || module.tracker.wasRecentlyHurtGlobally(
            entityId,
            nowNanos,
            KillEffectsTracker.GLOBAL_HURT_WINDOW_NANOS
        ) || (!module.onlyOwnKills.isEnabled() && entity.hurtTime > 0)
            || module.tracker.isRecentPlayerProjectileNear(entity, mc.theWorld, mc.thePlayer, nowNanos);
        if (!trackedCombat) {
            return;
        }
        double targetX = (double) packetIn.getX() / 32.0D;
        double targetY = (double) packetIn.getY() / 32.0D;
        double targetZ = (double) packetIn.getZ() / 32.0D;
        double dx = targetX - entity.posX;
        double dy = targetY - entity.posY;
        double dz = targetZ - entity.posZ;
        if ((dx * dx + dy * dy + dz * dz) >= PRE_TELEPORT_MIN_JUMP_SQ) {
            module.tracker.recordPreTeleport(
                entityId,
                entity.posX,
                entity.posY,
                entity.posZ,
                entity.rotationYaw,
                nowNanos
            );
        }
    }

    /**
     * Handles entity destruction packets ({@code S13PacketDestroyEntities}) for instant-despawn kills.
     *
     * @param entityId destroyed entity network id
     */
    public static void onClientEntityDestroy(int entityId) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Entity raw = mc.theWorld.getEntityByID(entityId);
        if (!(raw instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) raw;
        if (entity.worldObj == null || !entity.worldObj.isRemote || entity.worldObj != mc.theWorld) {
            return;
        }
        if (!module.isTargetEligible(entity, mc) || isAntiCheatBot(entity, mc)) {
            return;
        }
        long nowNanos = System.nanoTime();
        int identityHash = System.identityHashCode(entity);
        if (module.tracker.isRecentlyDead(entityId, identityHash, nowNanos)) {
            return;
        }
        boolean hasPreTeleport = module.tracker.hasPreTeleportPos(entityId, nowNanos);
        boolean lethalDestroy;
        if (module.onlyOwnKills.isEnabled()) {
            boolean ownAttributed = module.tracker.wasRecentlyAttacked(
                entityId,
                nowNanos,
                KillEffectsTracker.ATTACK_WINDOW_NANOS
            ) || module.tracker.isRecentPlayerProjectileNear(entity, mc.theWorld, mc.thePlayer, nowNanos);
            if (!ownAttributed) {
                return;
            }
            double dx = entity.posX - mc.thePlayer.posX;
            double dy = entity.posY - mc.thePlayer.posY;
            double dz = entity.posZ - mc.thePlayer.posZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            lethalDestroy = entity.getHealth() <= 0.0F
                || entity.deathTime > 0
                || entity.hurtTime > 0
                || distSq <= DESTROY_ATTRIBUTION_RANGE_SQ
                || entity.posY < 2.0D
                || hasPreTeleport;
        } else {
            lethalDestroy = entity.getHealth() <= 0.0F
                || entity.deathTime > 0
                || entity.hurtTime > 0
                || module.tracker.wasRecentlyHurtGlobally(
                    entityId,
                    nowNanos,
                    KillEffectsTracker.GLOBAL_HURT_WINDOW_NANOS
                )
                || hasPreTeleport;
        }
        if (!lethalDestroy) {
            return;
        }
        module.captureAndRemoveCorpse(entity, mc, nowNanos);
    }

    /**
     * Handles tablist updates ({@code S38PacketPlayerListItem}) for spectator transitions and player removals.
     *
     * @param packetIn incoming player list item packet
     */
    public static void onClientPlayerListItem(S38PacketPlayerListItem packetIn) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || packetIn == null) {
            return;
        }
        if (module.targets.is(TARGET_MOBS)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        S38PacketPlayerListItem.Action action = packetIn.getAction();
        boolean isSpectatorUpdate = action == S38PacketPlayerListItem.Action.UPDATE_GAME_MODE;
        boolean isRemovePlayer = action == S38PacketPlayerListItem.Action.REMOVE_PLAYER;
        if (!isSpectatorUpdate && !isRemovePlayer) {
            return;
        }
        List<S38PacketPlayerListItem.AddPlayerData> entries = packetIn.getEntries();
        if (entries == null || entries.isEmpty()) {
            return;
        }

        NetHandlerPlayClient netHandler = mc.getNetHandler();
        long nowNanos = System.nanoTime();
        UUID localUuid = mc.thePlayer.getUniqueID();

        for (int i = 0, size = entries.size(); i < size; i++) {
            S38PacketPlayerListItem.AddPlayerData entry = entries.get(i);
            if (entry == null || entry.getProfile() == null) {
                continue;
            }
            UUID uuid = entry.getProfile().getId();
            if (uuid == null || uuid.equals(localUuid)) {
                continue;
            }
            if (isSpectatorUpdate) {
                if (entry.getGameMode() != WorldSettings.GameType.SPECTATOR || netHandler == null) {
                    continue;
                }
                NetworkPlayerInfo existing = netHandler.getPlayerInfo(uuid);
                if (existing == null || existing.getGameType() == WorldSettings.GameType.SPECTATOR) {
                    continue;
                }
            }

            EntityPlayer target = findWorldPlayerByUuid(mc.theWorld, mc.thePlayer, uuid);
            if (target == null || !module.isTargetEligible(target, mc)) {
                continue;
            }
            int targetId = target.getEntityId();
            int identityHash = System.identityHashCode(target);
            if (module.tracker.isRecentlyDead(targetId, identityHash, nowNanos)) {
                continue;
            }
            if (isRemovePlayer
                && target.isInvisible()
                && target.hurtTime == 0
                && target.getHealth() > 0.0F
                && target.deathTime <= 0
                && target.ticksExisted <= 10) {
                continue;
            }

            boolean attributed;
            if (module.onlyOwnKills.isEnabled()) {
                attributed = module.hasOwnKillAttribution(target, mc, nowNanos);
            } else {
                double dx = target.posX - mc.thePlayer.posX;
                double dy = target.posY - mc.thePlayer.posY;
                double dz = target.posZ - mc.thePlayer.posZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                boolean closeVisibleKill = distSq <= SPECTATOR_OTHER_KILL_RANGE_SQ
                    && (!isRemovePlayer || (!target.isInvisible() && target.ticksExisted > 10));
                attributed = module.tracker.wasRecentlyHurtGlobally(
                        targetId,
                        nowNanos,
                        KillEffectsTracker.GLOBAL_HURT_WINDOW_NANOS
                    )
                    || target.hurtTime > 0
                    || target.getHealth() <= 0.0F
                    || target.deathTime > 0
                    || module.tracker.hasPreTeleportPos(targetId, nowNanos)
                    || closeVisibleKill;
            }
            if (!attributed) {
                continue;
            }

            if (isSpectatorUpdate) {
                module.captureAndRemoveCorpse(target, mc, nowNanos);
            } else {
                boolean removeNow = target.isDead
                    || target.getHealth() <= 0.0F
                    || target.deathTime > 0
                    || target.isInvisible();
                if (removeNow) {
                    module.captureAndRemoveCorpse(target, mc, nowNanos);
                } else {
                    module.captureAtEntityWithPreTeleport(target, mc, nowNanos, false, false);
                }
            }
        }
    }

    /**
     * Returns whether vanilla entity rendering should be suppressed for a captured corpse.
     *
     * @param entity entity scheduled for rendering
     * @return true if original rendering should be skipped
     */
    public static boolean shouldSuppressOriginalRender(Entity entity) {
        KillEffects module = instance;
        if (module == null || !module.isEnabled() || !(entity instanceof EntityLivingBase)) {
            return false;
        }
        return module.tracker.shouldSuppressRender((EntityLivingBase) entity, System.nanoTime());
    }

    @Override
    protected void onEnable() {
        this.tracker.clear();
    }

    @Override
    protected void onDisable() {
        this.tracker.clear();
        KillEffectsRenderer.clearState();
    }

    /**
     * Records local player melee hits for kill attribution.
     *
     * @param event Forge attack entity event
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        // Filter out integrated-server EntityPlayerMP attack events in singleplayer.
        if (event.entityPlayer != mc.thePlayer) {
            return;
        }
        if (event.target instanceof EntityLivingBase
            && event.target != mc.thePlayer
            && !(event.target instanceof EntityArmorStand)) {
            EntityLivingBase target = (EntityLivingBase) event.target;
            if (isAntiCheatBot(target, mc)) {
                return;
            }
            long nowNanos = System.nanoTime();
            int targetId = target.getEntityId();
            if (target.isEntityAlive() && target.getHealth() > 0.0F) {
                this.tracker.clearExpiredRespawnDead(targetId, System.identityHashCode(target), nowNanos);
            }
            this.tracker.recordAttack(targetId, nowNanos);
            this.tracker.trackRecentPlayerSnapshot(target, nowNanos, true);
        }
    }

    /**
     * Falls back to server kill chat broadcasts for final kills and instant-respawn plugins.
     *
     * @param event Forge client chat event
     */
    @SubscribeEvent
    public void onClientChat(ClientChatReceivedEvent event) {
        if (!isEnabled() || event == null || event.type != 0 || event.message == null) {
            return;
        }
        if (this.targets.is(TARGET_MOBS)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!mc.thePlayer.isEntityAlive() || mc.thePlayer.getHealth() <= 0.0F) {
            return;
        }
        String rawText = event.message.getUnformattedText();
        if (rawText == null || rawText.isEmpty()) {
            return;
        }
        String cleanText = EnumChatFormatting.getTextWithoutFormattingCodes(rawText);
        if (cleanText == null || cleanText.isEmpty()) {
            return;
        }
        String localName = stripFormattingIfNeeded(mc.thePlayer.getName());
        if (localName == null || localName.isEmpty()) {
            return;
        }
        if (!containsKillIndicator(cleanText)) {
            return;
        }
        int killerIdx = findWordIndex(cleanText, localName, 0);
        if (killerIdx < 0) {
            return;
        }
        boolean isPassive = containsPassiveMarker(cleanText);
        long nowNanos = System.nanoTime();
        int cap = this.tracker.getSnapshotCapacity();

        for (int i = 0; i < cap; i++) {
            if (!this.tracker.isSnapshotValid(i, nowNanos)) {
                continue;
            }
            String victimName = this.tracker.getSnapshotName(i);
            if (victimName == null || victimName.isEmpty() || victimName.equals(localName)) {
                continue;
            }
            int victimIdx = findWordIndex(cleanText, victimName, 0);
            if (victimIdx < 0) {
                continue;
            }
            boolean validDirection = isPassive ? (victimIdx < killerIdx) : (killerIdx < victimIdx);
            if (!validDirection) {
                continue;
            }

            EntityLivingBase snapEntity = this.tracker.getSnapshotEntity(i);
            int snapEntityId = this.tracker.getSnapshotEntityId(i);
            int identityHash = snapEntity != null ? System.identityHashCode(snapEntity) : 0;
            if (this.tracker.isRecentlyDead(snapEntityId, identityHash, nowNanos)) {
                return;
            }

            double spawnX = this.tracker.getSnapshotX(i);
            double spawnY = this.tracker.getSnapshotY(i);
            double spawnZ = this.tracker.getSnapshotZ(i);
            float spawnYaw = this.tracker.getSnapshotYaw(i);
            int preTpSlot = this.tracker.findPreTeleportSlot(snapEntityId, nowNanos);
            if (preTpSlot >= 0) {
                spawnX = this.tracker.getPreTeleportX(preTpSlot);
                spawnY = this.tracker.getPreTeleportY(preTpSlot);
                spawnZ = this.tracker.getPreTeleportZ(preTpSlot);
                spawnYaw = this.tracker.getPreTeleportYaw(preTpSlot);
                this.tracker.consumePreTeleportSlot(preTpSlot);
            }

            float width = snapEntity != null ? snapEntity.width : this.tracker.getSnapshotWidth(i);
            float height = snapEntity != null ? snapEntity.height : this.tracker.getSnapshotHeight(i);
            boolean stillInWorld = snapEntity != null && mc.theWorld.getEntityByID(snapEntityId) == snapEntity;
            boolean deadCorpseInWorld = stillInWorld
                && (snapEntity.isDead || snapEntity.getHealth() <= 0.0F || snapEntity.deathTime > 0);
            captureKillEffect(
                snapEntity,
                snapEntityId,
                spawnX,
                spawnY,
                spawnZ,
                width,
                height,
                spawnYaw,
                deadCorpseInWorld,
                deadCorpseInWorld,
                mc,
                nowNanos
            );
            return;
        }
    }

    /**
     * Tracks player-owned projectiles and attributes fresh hit ticks on nearby living targets.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!isEnabled() || event == null || event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            this.tracker.clear();
            KillEffectsRenderer.clearState();
            return;
        }
        if (mc.isGamePaused()) {
            return;
        }
        this.tracker.tickProjectiles(mc.theWorld, mc.thePlayer, System.nanoTime());
    }

    /**
     * Renders active kill effects and particle pools in world space.
     *
     * @param event Forge render world last event
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }
        KillEffectsRenderer.render(this.tracker, event.partialTicks);
    }

    /**
     * Clears tracked effects and renderer state when the client world unloads.
     *
     * @param event Forge world unload event
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event != null && event.world != null && event.world.isRemote) {
            this.tracker.clear();
            KillEffectsRenderer.clearState();
        }
    }

    private boolean isTargetEligible(EntityLivingBase entity, Minecraft mc) {
        if (entity == mc.thePlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        if (this.targets.is(TARGET_PLAYERS)) {
            return entity instanceof EntityPlayer;
        }
        if (this.targets.is(TARGET_MOBS)) {
            return !(entity instanceof EntityPlayer);
        }
        return true;
    }

    private boolean hasOwnKillAttribution(EntityLivingBase entity, Minecraft mc, long nowNanos) {
        if (!this.onlyOwnKills.isEnabled()) {
            return true;
        }
        if (this.tracker.wasRecentlyAttacked(entity.getEntityId(), nowNanos, KillEffectsTracker.ATTACK_WINDOW_NANOS)) {
            return true;
        }
        return this.tracker.isRecentPlayerProjectileNear(entity, mc.theWorld, mc.thePlayer, nowNanos);
    }

    private void captureAndRemoveCorpse(EntityLivingBase entity, Minecraft mc, long nowNanos) {
        captureAtEntityWithPreTeleport(entity, mc, nowNanos, true, true);
    }

    private void captureAtEntityWithPreTeleport(
        EntityLivingBase entity,
        Minecraft mc,
        long nowNanos,
        boolean suppressOriginalRender,
        boolean removeFromWorld
    ) {
        int entityId = entity.getEntityId();
        double spawnX = entity.posX;
        double spawnY = entity.posY;
        double spawnZ = entity.posZ;
        float spawnYaw = entity.rotationYaw;

        int preTpSlot = this.tracker.findPreTeleportSlot(entityId, nowNanos);
        if (preTpSlot >= 0) {
            spawnX = this.tracker.getPreTeleportX(preTpSlot);
            spawnY = this.tracker.getPreTeleportY(preTpSlot);
            spawnZ = this.tracker.getPreTeleportZ(preTpSlot);
            spawnYaw = this.tracker.getPreTeleportYaw(preTpSlot);
            this.tracker.consumePreTeleportSlot(preTpSlot);
        }

        captureKillEffect(
            entity,
            entityId,
            spawnX,
            spawnY,
            spawnZ,
            entity.width,
            entity.height,
            spawnYaw,
            suppressOriginalRender,
            removeFromWorld,
            mc,
            nowNanos
        );
    }

    private void captureKillEffect(
        EntityLivingBase entity,
        int entityId,
        double spawnX,
        double spawnY,
        double spawnZ,
        float width,
        float height,
        float yaw,
        boolean suppressOriginalRender,
        boolean removeFromWorld,
        Minecraft mc,
        long nowNanos
    ) {
        int identityHash = entity != null ? System.identityHashCode(entity) : 0;
        this.tracker.markDead(entityId, identityHash, nowNanos, suppressOriginalRender);

        KillEffectInstance slot = this.tracker.acquireSlot(nowNanos, suppressOriginalRender);
        int effectMode;
        if (this.mode.is(MODE_ANNIHILATION)) {
            effectMode = KillEffectInstance.MODE_ANNIHILATION;
        } else if (this.mode.is(MODE_PHANTOM_SWORDS)) {
            effectMode = KillEffectInstance.MODE_PHANTOM_SWORDS;
        } else if (this.mode.is(MODE_ABYSS_VORTEX)) {
            effectMode = KillEffectInstance.MODE_ABYSS_VORTEX;
        } else {
            effectMode = KillEffectInstance.MODE_SOUL_SEVER;
        }

        double dx = spawnX - mc.thePlayer.posX;
        double dz = spawnZ - mc.thePlayer.posZ;
        double distSq = dx * dx + dz * dz;
        float behindDirX;
        float behindDirZ;
        if (distSq >= MIN_DIRECTION_DIST_SQ) {
            double invDist = 1.0D / Math.sqrt(distSq);
            behindDirX = (float) (dx * invDist);
            behindDirZ = (float) (dz * invDist);
        } else {
            Vec3 look = entity != null ? entity.getLookVec() : null;
            double lx = look != null ? look.xCoord : 0.0D;
            double lz = look != null ? look.zCoord : 1.0D;
            double lookDistSq = lx * lx + lz * lz;
            if (lookDistSq >= MIN_DIRECTION_DIST_SQ) {
                double invLook = 1.0D / Math.sqrt(lookDistSq);
                behindDirX = (float) (lx * invLook);
                behindDirZ = (float) (lz * invLook);
            } else {
                float yawRad = yaw * DEG_TO_RAD;
                behindDirX = -MathHelper.sin(yawRad);
                behindDirZ = MathHelper.cos(yawRad);
            }
        }

        ItemStack rawHeld = mc.thePlayer.getHeldItem();
        slot.mode = effectMode;
        slot.entityId = entityId;
        slot.entity = entity;
        slot.startNanos = nowNanos;
        slot.durationNanos = Math.max(1L, (long) (this.duration.getDoubleValue() * NANOS_PER_SECOND));
        slot.x = spawnX;
        slot.y = spawnY;
        slot.z = spawnZ;
        slot.entityWidth = width > 0.0F ? width : 0.6F;
        slot.entityHeight = height > 0.0F ? height : 1.8F;
        slot.yaw = yaw;
        slot.behindDirX = behindDirX;
        slot.behindDirZ = behindDirZ;
        slot.heldItem = rawHeld != null ? rawHeld.copy() : null;
        slot.weaponScale = this.weaponScale.getFloatValue();
        slot.soulArgb = this.soulColor.getArgb();
        slot.particlesEnabled = this.particles.isEnabled();
        slot.soundEnabled = this.sound.isEnabled();
        slot.cleaveTriggered = false;
        slot.burstSpawned = false;
        slot.savedDeathTime = entity != null ? entity.deathTime : 0;
        slot.savedHurtTime = entity != null ? entity.hurtTime : 0;

        if (removeFromWorld && entity != null) {
            // Freeze interpolation and hurt/death timers so the rendered ghost stands upright in its lethal pose.
            entity.posX = spawnX;
            entity.posY = spawnY;
            entity.posZ = spawnZ;
            entity.rotationYaw = yaw;
            entity.prevRenderYawOffset = entity.renderYawOffset;
            entity.prevRotationYawHead = entity.rotationYawHead;
            entity.prevRotationYaw = yaw;
            entity.prevRotationPitch = entity.rotationPitch;
            entity.prevLimbSwingAmount = entity.limbSwingAmount;
            entity.prevSwingProgress = entity.swingProgress;
            entity.lastTickPosX = spawnX;
            entity.lastTickPosY = spawnY;
            entity.lastTickPosZ = spawnZ;
            entity.prevPosX = spawnX;
            entity.prevPosY = spawnY;
            entity.prevPosZ = spawnZ;
            entity.deathTime = 0;
            entity.hurtTime = 0;

            if (entity.worldObj instanceof WorldClient) {
                ((WorldClient) entity.worldObj).removeEntityFromWorld(entityId);
            }
            entity.setDead();
        }

        if (slot.soundEnabled && mc.getRenderViewEntity() != null) {
            double soundY = slot.y + (double) slot.entityHeight * 0.5D;
            if (effectMode == KillEffectInstance.MODE_ANNIHILATION) {
                mc.theWorld.playSound(slot.x, soundY, slot.z, "random.fizz", 0.25F, 1.5F, false);
            } else if (effectMode == KillEffectInstance.MODE_PHANTOM_SWORDS) {
                mc.theWorld.playSound(slot.x, soundY, slot.z, "mob.endermen.portal", 0.38F, 1.65F, false);
            } else if (effectMode == KillEffectInstance.MODE_ABYSS_VORTEX) {
                mc.theWorld.playSound(slot.x, soundY, slot.z, "mob.endermen.portal", 0.45F, 0.55F, false);
            } else {
                mc.theWorld.playSound(slot.x, soundY, slot.z, "mob.endermen.portal", 0.4F, 1.45F, false);
            }
        }
    }

    private static boolean isAntiCheatBot(EntityLivingBase entity, Minecraft mc) {
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        if (!entity.isInvisible() || entity.getHealth() <= 0.0F || entity.deathTime > 0) {
            return false;
        }
        NetHandlerPlayClient netHandler = mc.getNetHandler();
        return netHandler != null && netHandler.getPlayerInfo(entity.getUniqueID()) == null;
    }

    private static EntityPlayer findWorldPlayerByUuid(
        WorldClient world,
        EntityPlayer localPlayer,
        UUID targetUuid
    ) {
        List<EntityPlayer> players = world.playerEntities;
        for (int i = 0, size = players.size(); i < size; i++) {
            EntityPlayer candidate = players.get(i);
            if (candidate != null && candidate != localPlayer && targetUuid.equals(candidate.getUniqueID())) {
                return candidate;
            }
        }
        return null;
    }

    private static String stripFormattingIfNeeded(String text) {
        if (text == null || text.indexOf('\u00a7') < 0) {
            return text;
        }
        return EnumChatFormatting.getTextWithoutFormattingCodes(text);
    }

    private static boolean containsKillIndicator(String cleanText) {
        String lower = cleanText.toLowerCase(Locale.ROOT);
        for (int i = 0; i < KILL_CHAT_INDICATORS.length; i++) {
            if (lower.contains(KILL_CHAT_INDICATORS[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPassiveMarker(String cleanText) {
        String lower = cleanText.toLowerCase(Locale.ROOT);
        for (int i = 0; i < PASSIVE_CHAT_MARKERS.length; i++) {
            if (lower.contains(PASSIVE_CHAT_MARKERS[i])) {
                return true;
            }
        }
        return false;
    }

    private static int findWordIndex(String text, String word, int fromIndex) {
        int textLen = text.length();
        int wordLen = word.length();
        if (wordLen == 0 || textLen < wordLen) {
            return -1;
        }
        int idx = text.indexOf(word, fromIndex);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !isUsernameChar(text.charAt(idx - 1));
            int after = idx + wordLen;
            boolean rightOk = after >= textLen || !isUsernameChar(text.charAt(after));
            if (leftOk && rightOk) {
                return idx;
            }
            idx = text.indexOf(word, idx + 1);
        }
        return -1;
    }

    private static boolean isUsernameChar(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '_';
    }
}
