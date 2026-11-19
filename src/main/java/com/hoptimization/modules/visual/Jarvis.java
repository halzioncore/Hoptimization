package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.modules.visual.jarvis.BaseIntrusionMonitor;
import com.hoptimization.modules.visual.jarvis.BedDefenseAnalyzer;
import com.hoptimization.modules.visual.jarvis.BedDefenseAnalyzer.DefenseEntry;
import com.hoptimization.modules.visual.jarvis.BedMiningPathfinder;
import com.hoptimization.modules.visual.jarvis.BedWarsContext;
import com.hoptimization.modules.visual.jarvis.BedWarsContext.TrackedBed;
import com.hoptimization.modules.visual.jarvis.FireballBlastPredictor;
import com.hoptimization.modules.visual.jarvis.JarvisHudRenderer;
import com.hoptimization.modules.visual.jarvis.JarvisWorldRenderer;
import com.hoptimization.ui.hud.HudEditScreen;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.theme.BuiltinThemes;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * BedWars Jarvis tactical suite: base intrusion alert banner & sound/chat warnings,
 * bed defense ease ranking radar, fireball trajectory/blast prediction, and 3D mining pathfinder.
 */
public class Jarvis extends Module implements HudElement {
    public static final String ID = "jarvis";
    public static final String HUD_ID_RADAR = "jarvis_radar";
    public static final String HUD_ID_ALERT = "jarvis_alert";

    public static final String WORK_BEDWARS = "bedwars";
    public static final String WORK_ALWAYS = "always";
    public static final String HUD_STYLE_CARD = "card";
    public static final String HUD_STYLE_TEXT = "text";

    private static final int ANALYZE_INTERVAL_TICKS = 10;
    private static final int PATH_RECOMPUTE_INTERVAL_TICKS = 5;
    private static final double PATH_MOVE_THRESHOLD_SQ = 1.0D;

    private final ModeSetting workMode;
    private final ModeSetting hudStyle;
    private final BooleanSetting followTheme;

    private final BooleanSetting baseAlert;
    private final NumberSetting baseRange;
    private final BooleanSetting alertSound;
    private final BooleanSetting alertChat;
    private final BooleanSetting alertInvisible;

    private final BooleanSetting defenseRadar;
    private final NumberSetting scanRange;
    private final BooleanSetting showDefenseHud;

    private final BooleanSetting fireballPredict;
    private final ModeSetting fireballPreset;
    private final NumberSetting fireballPower;
    private final BooleanSetting fireballTrajectory;

    private final BooleanSetting miningPath;
    private final NumberSetting invadeRange;
    private final BooleanSetting pathThroughWalls;

    private final BedWarsContext context = new BedWarsContext();
    private final BaseIntrusionMonitor intrusionMonitor = new BaseIntrusionMonitor();
    private final BedDefenseAnalyzer defenseAnalyzer = new BedDefenseAnalyzer();
    private final BedMiningPathfinder quickAnalyzerSolver = new BedMiningPathfinder();
    private final BedMiningPathfinder miningPathfinder = new BedMiningPathfinder();
    private final FireballBlastPredictor fireballPredictor = new FireballBlastPredictor();
    private final JarvisHudRenderer hudRenderer = new JarvisHudRenderer();
    private final AlertHudElement alertHudElement = new AlertHudElement();

    private final boolean[] analyzerEligibleFlags = new boolean[BedWarsContext.MAX_BEDS];
    private final double[] analyzerDistances = new double[BedWarsContext.MAX_BEDS];

    private World lastWorld;
    private int tickCounter;
    private int lastAnalyzerToolHash;
    private boolean hasAnalyzerToolHash;

    private int lastPathBedIndex = -1;
    private int lastPathBedX;
    private int lastPathBedY;
    private int lastPathBedZ;
    private double lastPathPlayerX;
    private double lastPathPlayerY;
    private double lastPathPlayerZ;
    private int lastPathToolPotionHash;
    private int lastPathSolveTick;

    /**
     * Creates the Jarvis module (disabled by default, requires Cheat Mode), registers all settings
     * in display order, and registers both {@code jarvis_radar} and {@code jarvis_alert} HUD elements.
     */
    public Jarvis() {
        super(ID, Category.VISUAL, Keyboard.KEY_NONE, true, true);

        this.workMode = addSetting(
            new ModeSetting(
                "work_mode",
                "setting.hoptimization.jarvis.work_mode.name",
                WORK_BEDWARS,
                WORK_BEDWARS,
                WORK_ALWAYS
            )
        );
        this.hudStyle = addSetting(
            new ModeSetting(
                "hud_style",
                "setting.hoptimization.jarvis.hud_style.name",
                HUD_STYLE_CARD,
                HUD_STYLE_CARD,
                HUD_STYLE_TEXT
            )
        );
        this.followTheme = addSetting(
            new BooleanSetting("follow_theme", "setting.hoptimization.jarvis.follow_theme.name", true)
        );

        this.baseAlert = addSetting(
            new BooleanSetting("base_alert", "setting.hoptimization.jarvis.base_alert.name", true)
        );
        this.baseRange = addSetting(
            new NumberSetting(
                "base_range",
                "setting.hoptimization.jarvis.base_range.name",
                24.0D,
                12.0D,
                48.0D,
                2.0D,
                "m"
            ).visibleWhen(() -> Boolean.valueOf(this.baseAlert.isEnabled()))
        );
        this.alertSound = addSetting(
            new BooleanSetting("alert_sound", "setting.hoptimization.jarvis.alert_sound.name", true)
                .visibleWhen(() -> Boolean.valueOf(this.baseAlert.isEnabled()))
        );
        this.alertChat = addSetting(
            new BooleanSetting("alert_chat", "setting.hoptimization.jarvis.alert_chat.name", false)
                .visibleWhen(() -> Boolean.valueOf(this.baseAlert.isEnabled()))
        );
        this.alertInvisible = addSetting(
            new BooleanSetting("alert_invisible", "setting.hoptimization.jarvis.alert_invisible.name", true)
                .visibleWhen(() -> Boolean.valueOf(this.baseAlert.isEnabled()))
        );

        this.defenseRadar = addSetting(
            new BooleanSetting("defense_radar", "setting.hoptimization.jarvis.defense_radar.name", true)
        );
        this.scanRange = addSetting(
            new NumberSetting(
                "scan_range",
                "setting.hoptimization.jarvis.scan_range.name",
                112.0D,
                48.0D,
                160.0D,
                16.0D,
                "m"
            ).visibleWhen(() -> Boolean.valueOf(this.defenseRadar.isEnabled()))
        );
        this.showDefenseHud = addSetting(
            new BooleanSetting("show_defense_hud", "setting.hoptimization.jarvis.show_defense_hud.name", true)
                .visibleWhen(() -> Boolean.valueOf(this.defenseRadar.isEnabled()))
        );

        this.fireballPredict = addSetting(
            new BooleanSetting("fireball_predict", "setting.hoptimization.jarvis.fireball_predict.name", true)
        );
        this.fireballPreset = addSetting(
            new ModeSetting(
                "fireball_preset",
                "setting.hoptimization.jarvis.fireball_preset.name",
                FireballBlastPredictor.PRESET_BEDWARS,
                FireballBlastPredictor.PRESET_BEDWARS,
                FireballBlastPredictor.PRESET_VANILLA
            ).visibleWhen(() -> Boolean.valueOf(this.fireballPredict.isEnabled()))
        );
        this.fireballPower = addSetting(
            new NumberSetting(
                "fireball_power",
                "setting.hoptimization.jarvis.fireball_power.name",
                2.2D,
                1.0D,
                4.0D,
                0.2D
            ).visibleWhen(() -> Boolean.valueOf(this.fireballPredict.isEnabled()))
        );
        this.fireballTrajectory = addSetting(
            new BooleanSetting("fireball_trajectory", "setting.hoptimization.jarvis.fireball_trajectory.name", true)
                .visibleWhen(() -> Boolean.valueOf(this.fireballPredict.isEnabled()))
        );

        this.miningPath = addSetting(
            new BooleanSetting("mining_path", "setting.hoptimization.jarvis.mining_path.name", true)
        );
        this.invadeRange = addSetting(
            new NumberSetting(
                "invade_range",
                "setting.hoptimization.jarvis.invade_range.name",
                24.0D,
                8.0D,
                48.0D,
                2.0D,
                "m"
            ).visibleWhen(() -> Boolean.valueOf(this.miningPath.isEnabled()))
        );
        this.pathThroughWalls = addSetting(
            new BooleanSetting("path_through_walls", "setting.hoptimization.jarvis.path_through_walls.name", true)
                .visibleWhen(() -> Boolean.valueOf(this.miningPath.isEnabled()))
        );

        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.registerElement(this.alertHudElement);
        HudOverlayRenderer.ensureRegistered();
    }

    public ModeSetting getWorkModeSetting() {
        return this.workMode;
    }

    public ModeSetting getHudStyleSetting() {
        return this.hudStyle;
    }

    public BooleanSetting getFollowThemeSetting() {
        return this.followTheme;
    }

    public BooleanSetting getBaseAlertSetting() {
        return this.baseAlert;
    }

    public NumberSetting getBaseRangeSetting() {
        return this.baseRange;
    }

    public BooleanSetting getAlertSoundSetting() {
        return this.alertSound;
    }

    public BooleanSetting getAlertChatSetting() {
        return this.alertChat;
    }

    public BooleanSetting getAlertInvisibleSetting() {
        return this.alertInvisible;
    }

    public BooleanSetting getDefenseRadarSetting() {
        return this.defenseRadar;
    }

    public NumberSetting getScanRangeSetting() {
        return this.scanRange;
    }

    public BooleanSetting getShowDefenseHudSetting() {
        return this.showDefenseHud;
    }

    public BooleanSetting getFireballPredictSetting() {
        return this.fireballPredict;
    }

    public ModeSetting getFireballPresetSetting() {
        return this.fireballPreset;
    }

    public NumberSetting getFireballPowerSetting() {
        return this.fireballPower;
    }

    public BooleanSetting getFireballTrajectorySetting() {
        return this.fireballTrajectory;
    }

    public BooleanSetting getMiningPathSetting() {
        return this.miningPath;
    }

    public NumberSetting getInvadeRangeSetting() {
        return this.invadeRange;
    }

    public BooleanSetting getPathThroughWallsSetting() {
        return this.pathThroughWalls;
    }

    public BedWarsContext getContext() {
        return this.context;
    }

    public BaseIntrusionMonitor getIntrusionMonitor() {
        return this.intrusionMonitor;
    }

    public BedDefenseAnalyzer getDefenseAnalyzer() {
        return this.defenseAnalyzer;
    }

    public BedMiningPathfinder getMiningPathfinder() {
        return this.miningPathfinder;
    }

    public FireballBlastPredictor getFireballPredictor() {
        return this.fireballPredictor;
    }

    public JarvisHudRenderer getHudRenderer() {
        return this.hudRenderer;
    }

    public HudElement getAlertHudElement() {
        return this.alertHudElement;
    }

    @Override
    protected void onEnable() {
        resetSubsystemTracking();
    }

    @Override
    protected void onDisable() {
        clearAllSubsystems();
    }

    /**
     * Advances BedWars context detection, base intrusion alerts, defense radar ranking,
     * fireball prediction, and local mining path calculation on client tick end.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!isEnabled() || !isAvailable() || event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            clearAllSubsystems();
            return;
        }

        World world = mc.theWorld;
        EntityPlayer player = mc.thePlayer;
        if (world != this.lastWorld) {
            clearAllSubsystems();
            this.lastWorld = world;
        }

        boolean alwaysActive = this.workMode.is(WORK_ALWAYS);
        this.context.update(mc, alwaysActive, this.scanRange.getIntValue());

        boolean alertEnabled = this.baseAlert.isEnabled();
        this.intrusionMonitor.update(
            mc,
            this.context,
            alertEnabled,
            this.baseRange.getDoubleValue(),
            this.alertInvisible.isEnabled(),
            this.alertSound.isEnabled(),
            this.alertChat.isEnabled()
        );
        if (!alertEnabled) {
            this.hudRenderer.resetAnimations();
        }

        this.fireballPredictor.update(
            world,
            this.fireballPredict.isEnabled(),
            this.fireballPreset.is(FireballBlastPredictor.PRESET_BEDWARS),
            this.fireballPower.getFloatValue()
        );

        int toolPotionHash = computePlayerToolPotionHash(player);
        updateDefenseAnalyzer(world, player, toolPotionHash);
        updateMiningPathfinder(world, player, toolPotionHash);

        this.tickCounter++;
    }

    /**
     * Renders predicted fireball blast footprints and active mining path overlays in 3D world space.
     *
     * @param event Forge render world last event
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled() || !isAvailable() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        if (this.fireballPredict.isEnabled() && this.fireballPredictor.hasActiveImpacts()) {
            JarvisWorldRenderer.renderFireballPredictions(
                this.fireballPredictor,
                this.fireballTrajectory.isEnabled(),
                true,
                event.partialTicks
            );
        }

        if (this.miningPath.isEnabled()
            && this.context.isEffectiveActive()
            && this.miningPathfinder.hasValidPath()) {
            int accentArgb = this.followTheme.isEnabled()
                ? ThemeManager.current().accent()
                : BuiltinThemes.OBSIDIAN_GOLD.accent();
            JarvisWorldRenderer.renderMiningPath(
                this.miningPathfinder.getStepCount(),
                this.miningPathfinder.getStepXArray(),
                this.miningPathfinder.getStepYArray(),
                this.miningPathfinder.getStepZArray(),
                this.miningPathfinder.getGoalBedX(),
                this.miningPathfinder.getGoalBedY(),
                this.miningPathfinder.getGoalBedZ(),
                this.miningPathfinder.isNakedBed(),
                this.miningPathfinder.getEntryX(),
                this.miningPathfinder.getEntryY(),
                this.miningPathfinder.getEntryZ(),
                this.pathThroughWalls.isEnabled(),
                accentArgb,
                event.partialTicks
            );
        }
    }

    /**
     * Forwards incoming client chat messages to {@link BedWarsContext} for match start and bed loss cues.
     *
     * @param event Forge client chat received event
     */
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!isEnabled() || !isAvailable() || event == null || event.type == 2 || event.message == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread()) {
            return;
        }
        this.context.onChatMessage(event.message.getUnformattedText());
    }

    /**
     * Clears all Jarvis subsystem state when the client world unloads.
     *
     * @param event Forge world unload event
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event == null || event.world == null || event.world.isRemote) {
            clearAllSubsystems();
        }
    }

    @Override
    public String getHudId() {
        return HUD_ID_RADAR;
    }

    @Override
    public String getHudDisplayName() {
        return HudRenderHelper.resolveLang("ui.hoptimization.jarvis.hud.radar.name", "Jarvis Radar");
    }

    @Override
    public boolean isHudEnabled() {
        return isEnabled()
            && isAvailable()
            && this.defenseRadar.isEnabled()
            && this.showDefenseHud.isEnabled();
    }

    @Override
    public float getHudWidth() {
        return this.hudRenderer.getRadarWidth();
    }

    @Override
    public float getHudHeight() {
        return this.hudRenderer.getRadarHeight(
            this.context,
            this.defenseAnalyzer,
            this.miningPathfinder,
            this.miningPath.isEnabled(),
            isInHudEditScreen()
        );
    }

    @Override
    public float getDefaultHudX(float screenWidth, float screenHeight) {
        return Math.max(
            (float) UiTokens.HUD_SCREEN_MARGIN,
            screenWidth - getHudWidth() - UiTokens.HUD_SCREEN_MARGIN
        );
    }

    @Override
    public float getDefaultHudY(float screenWidth, float screenHeight) {
        return (float) UiTokens.SPACE_32;
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        boolean cardMode = !this.hudStyle.is(HUD_STYLE_TEXT);
        boolean alwaysMode = this.workMode.is(WORK_ALWAYS);
        this.hudRenderer.renderRadar(
            x,
            y,
            theme,
            this.followTheme.isEnabled(),
            cardMode,
            alwaysMode,
            this.miningPath.isEnabled(),
            editMode,
            this.context,
            this.defenseAnalyzer,
            this.miningPathfinder
        );
    }

    private void updateDefenseAnalyzer(World world, EntityPlayer player, int toolPotionHash) {
        if (!this.defenseRadar.isEnabled() || !this.context.isEffectiveActive()) {
            this.defenseAnalyzer.clear();
            this.quickAnalyzerSolver.clear();
            this.hasAnalyzerToolHash = false;
            return;
        }

        int bedCount = this.context.getBedCount();
        boolean periodicRefresh = (this.tickCounter % ANALYZE_INTERVAL_TICKS) == 0;
        boolean toolStateChanged = !this.hasAnalyzerToolHash || toolPotionHash != this.lastAnalyzerToolHash;
        this.lastAnalyzerToolHash = toolPotionHash;
        this.hasAnalyzerToolHash = true;

        for (int i = 0; i < bedCount; i++) {
            TrackedBed bed = this.context.getBed(i);
            if (bed == null
                || !bed.isActive()
                || bed.isOwnBed()
                || bed.getStatus() == TrackedBed.STATUS_DESTROYED) {
                this.analyzerEligibleFlags[i] = false;
                continue;
            }

            double dist = Math.sqrt(bed.distSqTo(player.posX, player.posY, player.posZ));
            this.analyzerDistances[i] = dist;

            DefenseEntry entry = this.defenseAnalyzer.getEntry(i);
            boolean needAnalyze = !entry.isValid() || periodicRefresh || toolStateChanged;
            if (bed.getStatus() == TrackedBed.STATUS_LIVE && needAnalyze) {
                this.defenseAnalyzer.analyzeBed(
                    i,
                    world,
                    player,
                    bed.getHeadX(),
                    bed.getHeadY(),
                    bed.getHeadZ(),
                    bed.getFacingDir(),
                    bed.isPairedFoot(),
                    dist,
                    this.quickAnalyzerSolver
                );
            }

            this.analyzerEligibleFlags[i] = entry.isValid();
        }

        this.defenseAnalyzer.rebuildRanking(this.analyzerEligibleFlags, bedCount, this.analyzerDistances);
    }

    private void updateMiningPathfinder(World world, EntityPlayer player, int toolPotionHash) {
        if (!this.miningPath.isEnabled() || !this.context.isEffectiveActive()) {
            resetMiningPathState();
            return;
        }

        double maxRange = this.invadeRange.getDoubleValue();
        double bestDistSq = maxRange * maxRange;
        TrackedBed closestEnemyBed = null;

        int bedCount = this.context.getBedCount();
        for (int i = 0; i < bedCount; i++) {
            TrackedBed bed = this.context.getBed(i);
            if (bed == null
                || !bed.isActive()
                || bed.isOwnBed()
                || bed.getStatus() == TrackedBed.STATUS_DESTROYED) {
                continue;
            }
            double distSq = bed.distSqTo(player.posX, player.posY, player.posZ);
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                closestEnemyBed = bed;
            }
        }

        if (closestEnemyBed == null) {
            resetMiningPathState();
            return;
        }

        int bedIdx = closestEnemyBed.getIndex();
        int hx = closestEnemyBed.getHeadX();
        int hy = closestEnemyBed.getHeadY();
        int hz = closestEnemyBed.getHeadZ();

        double dx = player.posX - this.lastPathPlayerX;
        double dy = player.posY - this.lastPathPlayerY;
        double dz = player.posZ - this.lastPathPlayerZ;
        double movedSq = dx * dx + dy * dy + dz * dz;

        boolean bedChanged = bedIdx != this.lastPathBedIndex
            || hx != this.lastPathBedX
            || hy != this.lastPathBedY
            || hz != this.lastPathBedZ;
        boolean stateChanged = toolPotionHash != this.lastPathToolPotionHash;
        boolean intervalElapsed = (this.tickCounter - this.lastPathSolveTick) >= PATH_RECOMPUTE_INTERVAL_TICKS;

        if (bedChanged
            || movedSq > PATH_MOVE_THRESHOLD_SQ
            || stateChanged
            || intervalElapsed) {
            this.miningPathfinder.solve(
                world,
                player,
                hx,
                hy,
                hz,
                closestEnemyBed.getFacingDir(),
                closestEnemyBed.isPairedFoot(),
                true
            );
            this.lastPathBedIndex = bedIdx;
            this.lastPathBedX = hx;
            this.lastPathBedY = hy;
            this.lastPathBedZ = hz;
            this.lastPathPlayerX = player.posX;
            this.lastPathPlayerY = player.posY;
            this.lastPathPlayerZ = player.posZ;
            this.lastPathToolPotionHash = toolPotionHash;
            this.lastPathSolveTick = this.tickCounter;
        }
    }

    private void resetMiningPathState() {
        this.miningPathfinder.clear();
        this.lastPathBedIndex = -1;
        this.lastPathBedX = 0;
        this.lastPathBedY = 0;
        this.lastPathBedZ = 0;
    }

    private void resetSubsystemTracking() {
        this.tickCounter = 0;
        this.hasAnalyzerToolHash = false;
        this.lastPathBedIndex = -1;
        this.lastPathSolveTick = 0;
    }

    private void clearAllSubsystems() {
        this.context.reset();
        this.intrusionMonitor.clear();
        this.defenseAnalyzer.clear();
        this.quickAnalyzerSolver.clear();
        this.miningPathfinder.clear();
        this.fireballPredictor.clear();
        this.hudRenderer.resetAnimations();
        this.lastWorld = null;
        resetSubsystemTracking();
    }

    private static int computePlayerToolPotionHash(EntityPlayer player) {
        int hash = 17;
        if (player != null && player.inventory != null) {
            int effId = Enchantment.efficiency.effectId;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = player.inventory.getStackInSlot(slot);
                if (stack != null && stack.getItem() != null) {
                    hash = 31 * hash + Item.getIdFromItem(stack.getItem());
                    hash = 31 * hash + stack.getMetadata();
                    hash = 31 * hash + EnchantmentHelper.getEnchantmentLevel(effId, stack);
                } else {
                    hash = 31 * hash;
                }
            }
        }
        if (player != null) {
            PotionEffect haste = player.getActivePotionEffect(Potion.digSpeed);
            hash = 31 * hash + (haste != null ? (haste.getAmplifier() + 1) : 0);
            PotionEffect fatigue = player.getActivePotionEffect(Potion.digSlowdown);
            hash = 31 * hash + (fatigue != null ? (fatigue.getAmplifier() + 1) : 0);
            PotionEffect speed = player.getActivePotionEffect(Potion.moveSpeed);
            hash = 31 * hash + (speed != null ? (speed.getAmplifier() + 1) : 0);
        }
        return hash;
    }

    private static boolean isInHudEditScreen() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.currentScreen instanceof HudEditScreen;
    }

    /**
     * Secondary {@link HudElement} representing the top-center Base Intrusion Alert Banner
     * ({@code jarvis_alert}) independently movable and scalable in {@link HudEditScreen}.
     */
    private final class AlertHudElement implements HudElement {
        @Override
        public String getHudId() {
            return HUD_ID_ALERT;
        }

        @Override
        public String getHudDisplayName() {
            return HudRenderHelper.resolveLang("ui.hoptimization.jarvis.hud.alert.name", "Jarvis Base Alert");
        }

        @Override
        public boolean isHudEnabled() {
            return isEnabled() && isAvailable() && baseAlert.isEnabled();
        }

        @Override
        public float getHudWidth() {
            return hudRenderer.getAlertWidth();
        }

        @Override
        public float getHudHeight() {
            return hudRenderer.getAlertHeight();
        }

        @Override
        public float getDefaultHudX(float screenWidth, float screenHeight) {
            return Math.max((float) UiTokens.HUD_SCREEN_MARGIN, (screenWidth - getHudWidth()) * 0.5F);
        }

        @Override
        public float getDefaultHudY(float screenWidth, float screenHeight) {
            return (float) UiTokens.SPACE_12;
        }

        @Override
        public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
            boolean cardMode = !hudStyle.is(HUD_STYLE_TEXT);
            hudRenderer.renderAlertBanner(
                x,
                y,
                theme,
                followTheme.isEnabled(),
                cardMode,
                baseAlert.isEnabled(),
                editMode,
                context,
                intrusionMonitor
            );
        }
    }
}
