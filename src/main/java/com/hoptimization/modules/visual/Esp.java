package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.modules.visual.esp.BedStructureCache;
import com.hoptimization.modules.visual.esp.EspRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Cheat-gated visual module rendering restrained, structurally recognizable 3D ESP outlines
 * for players, mobs, chests, and beds.
 */
public class Esp extends Module {
    public static final String ID = "esp";
    public static final String STYLE_CORNER_BRACKET = "corner_bracket";
    public static final String STYLE_CLEAN_BOX = "clean_box";

    private final BooleanSetting players;
    private final BooleanSetting mobs;
    private final BooleanSetting beds;
    private final BooleanSetting chests;
    private final ModeSetting entityStyle;
    private final NumberSetting lineWidth;
    private final NumberSetting fillOpacity;
    private final BooleanSetting throughWalls;
    private final BooleanSetting showHealthBar;
    private final BooleanSetting ignoreTeammates;
    private final BooleanSetting filterBots;
    private final NumberSetting blockRange;
    private final BooleanSetting followTheme;

    private final BedStructureCache bedCache = new BedStructureCache();

    /**
     * Creates the ESP module (disabled by default, requires Cheat Mode) and registers its settings.
     */
    public Esp() {
        super(ID, Category.VISUAL, Keyboard.KEY_NONE, true, true);
        this.players = addSetting(
            new BooleanSetting("players", "setting.hoptimization.esp.players.name", true)
        );
        this.mobs = addSetting(
            new BooleanSetting("mobs", "setting.hoptimization.esp.mobs.name", false)
        );
        this.beds = addSetting(
            new BooleanSetting("beds", "setting.hoptimization.esp.beds.name", true)
        );
        this.chests = addSetting(
            new BooleanSetting("chests", "setting.hoptimization.esp.chests.name", true)
        );
        this.entityStyle = addSetting(
            new ModeSetting(
                "entity_style",
                "setting.hoptimization.esp.entity_style.name",
                STYLE_CORNER_BRACKET,
                STYLE_CORNER_BRACKET,
                STYLE_CLEAN_BOX
            )
        );
        this.lineWidth = addSetting(
            new NumberSetting(
                "line_width",
                "setting.hoptimization.esp.line_width.name",
                1.5D,
                1.0D,
                3.0D,
                0.25D,
                "px"
            )
        );
        this.fillOpacity = addSetting(
            new NumberSetting(
                "fill_opacity",
                "setting.hoptimization.esp.fill_opacity.name",
                0.12D,
                0.0D,
                0.5D,
                0.02D
            )
        );
        this.throughWalls = addSetting(
            new BooleanSetting("through_walls", "setting.hoptimization.esp.through_walls.name", true)
        );
        this.showHealthBar = addSetting(
            new BooleanSetting("show_health_bar", "setting.hoptimization.esp.show_health_bar.name", true)
        );
        this.ignoreTeammates = addSetting(
            new BooleanSetting("ignore_teammates", "setting.hoptimization.esp.ignore_teammates.name", false)
        );
        this.filterBots = addSetting(
            new BooleanSetting("filter_bots", "setting.hoptimization.esp.filter_bots.name", true)
        );
        this.blockRange = addSetting(
            new NumberSetting(
                "block_range",
                "setting.hoptimization.esp.block_range.name",
                64.0D,
                16.0D,
                128.0D,
                8.0D,
                "m"
            )
        );
        this.followTheme = addSetting(
            new BooleanSetting("follow_theme", "setting.hoptimization.esp.follow_theme.name", true)
        );
    }

    public BooleanSetting getPlayersSetting() {
        return this.players;
    }

    public BooleanSetting getMobsSetting() {
        return this.mobs;
    }

    public BooleanSetting getBedsSetting() {
        return this.beds;
    }

    public BooleanSetting getChestsSetting() {
        return this.chests;
    }

    public ModeSetting getEntityStyleSetting() {
        return this.entityStyle;
    }

    public NumberSetting getLineWidthSetting() {
        return this.lineWidth;
    }

    public NumberSetting getFillOpacitySetting() {
        return this.fillOpacity;
    }

    public BooleanSetting getThroughWallsSetting() {
        return this.throughWalls;
    }

    public BooleanSetting getShowHealthBarSetting() {
        return this.showHealthBar;
    }

    public BooleanSetting getIgnoreTeammatesSetting() {
        return this.ignoreTeammates;
    }

    public BooleanSetting getFilterBotsSetting() {
        return this.filterBots;
    }

    public NumberSetting getBlockRangeSetting() {
        return this.blockRange;
    }

    public BooleanSetting getFollowThemeSetting() {
        return this.followTheme;
    }

    public BedStructureCache getBedCache() {
        return this.bedCache;
    }

    @Override
    protected void onEnable() {
        this.bedCache.invalidate();
    }

    @Override
    protected void onDisable() {
        this.bedCache.clear();
    }

    /**
     * Incrementally refreshes cached bed blocks on client ticks while Bed ESP is active.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!isEnabled() || !isAvailable() || event == null || event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            this.bedCache.clear();
            return;
        }
        if (!this.beds.isEnabled()) {
            if (this.bedCache.getBedCount() > 0) {
                this.bedCache.clear();
            }
            return;
        }
        int scanRadius = Math.min(this.blockRange.getIntValue(), BedStructureCache.HORIZONTAL_CAP);
        this.bedCache.update(mc.theWorld, mc.thePlayer, scanRadius);
    }

    /**
     * Clears cached beds when the client world unloads.
     *
     * @param event Forge world unload event
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event == null || event.world == null || event.world.isRemote) {
            this.bedCache.clear();
        }
    }

    /**
     * Renders structural ESP overlays after world geometry.
     *
     * @param event Forge render world last event
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled() || !isAvailable() || event == null) {
            return;
        }
        EspRenderer.render(this, this.bedCache, event.partialTicks);
    }

    /**
     * Checks whether a loaded entity matches active player/mob ESP toggles and filters without allocating.
     *
     * @param entity      candidate entity
     * @param localPlayer local player entity
     * @param mc          Minecraft client instance
     * @return {@code true} if the entity should be rendered
     */
    public boolean isEntityEligible(Entity entity, EntityPlayer localPlayer, Minecraft mc) {
        if (!(entity instanceof EntityLivingBase) || entity == localPlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        if (!living.isEntityAlive() || living.isDead) {
            return false;
        }
        if (living instanceof EntityPlayer) {
            if (!this.players.isEnabled()) {
                return false;
            }
            EntityPlayer targetPlayer = (EntityPlayer) living;
            if (targetPlayer.isSpectator()) {
                return false;
            }
            if (this.filterBots.isEnabled() && isAntiCheatBot(targetPlayer, mc)) {
                return false;
            }
            if (this.ignoreTeammates.isEnabled() && localPlayer != null && localPlayer.isOnSameTeam(targetPlayer)) {
                return false;
            }
            return true;
        }
        return this.mobs.isEnabled();
    }

    /**
     * Determines whether a player entity is a teammate of the local player.
     *
     * @param targetPlayer target player
     * @param localPlayer  local player
     * @return {@code true} if on the same scoreboard team
     */
    public static boolean isTeammate(EntityPlayer targetPlayer, EntityPlayer localPlayer) {
        return targetPlayer != null && localPlayer != null && localPlayer.isOnSameTeam(targetPlayer);
    }

    private static boolean isAntiCheatBot(EntityPlayer target, Minecraft mc) {
        if (target.getGameProfile() == null || target.getUniqueID() == null) {
            return true;
        }
        String profileName = target.getGameProfile().getName();
        if (profileName == null || profileName.isEmpty()) {
            return true;
        }
        if (profileName.indexOf(' ') >= 0 || profileName.indexOf('\u00a7') >= 0) {
            return true;
        }
        if (target.isInvisible()) {
            NetHandlerPlayClient netHandler = mc != null ? mc.getNetHandler() : null;
            if (netHandler == null || netHandler.getPlayerInfo(target.getUniqueID()) == null) {
                return true;
            }
        }
        return false;
    }
}
