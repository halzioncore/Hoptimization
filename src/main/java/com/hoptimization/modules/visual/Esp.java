package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.modules.visual.esp.EspRenderer;
import com.hoptimization.modules.visual.esp.EspTargetPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Cheat-gated visual module rendering players as restrained monochrome tonal blocks and 1px sketch lines.
 */
public class Esp extends Module implements EspTargetPolicy {
    public static final String ID = "esp";

    private static final int DEFAULT_COLOR_ARGB = 0xFF8B7CFF;

    private static Esp instance;

    private final ColorSetting color;
    private final BooleanSetting throughWalls;
    private final NumberSetting fillOpacity;
    private final BooleanSetting lineArt;
    private final NumberSetting lineWidth;
    private final BooleanSetting posterize;
    private final BooleanSetting ignoreTeammates;
    private final BooleanSetting filterBots;
    private final BooleanSetting renderSelf;

    /**
     * Creates the ESP module (disabled by default, requires Cheat Mode) and registers its settings.
     */
    public Esp() {
        super(ID, Category.VISUAL, Keyboard.KEY_NONE, true, true);
        this.color = addSetting(
            new ColorSetting("color", "setting.hoptimization.esp.color.name", DEFAULT_COLOR_ARGB, false)
        );
        this.throughWalls = addSetting(
            new BooleanSetting("through_walls", "setting.hoptimization.esp.through_walls.name", true)
        );
        this.fillOpacity = addSetting(
            new NumberSetting(
                "fill_opacity",
                "setting.hoptimization.esp.fill_opacity.name",
                90.0D,
                0.0D,
                100.0D,
                5.0D,
                "%"
            )
        );
        this.lineArt = addSetting(
            new BooleanSetting("line_art", "setting.hoptimization.esp.line_art.name", true)
        );
        this.lineWidth = addSetting(
            new NumberSetting(
                "line_width",
                "setting.hoptimization.esp.line_width.name",
                1.0D,
                0.5D,
                2.5D,
                0.25D,
                "px"
            ).visibleWhen(() -> Boolean.valueOf(this.lineArt.isEnabled()))
        );
        this.posterize = addSetting(
            new BooleanSetting("posterize", "setting.hoptimization.esp.posterize.name", true)
        );
        this.ignoreTeammates = addSetting(
            new BooleanSetting("ignore_teammates", "setting.hoptimization.esp.ignore_teammates.name", false)
        );
        this.filterBots = addSetting(
            new BooleanSetting("filter_bots", "setting.hoptimization.esp.filter_bots.name", true)
        );
        this.renderSelf = addSetting(
            new BooleanSetting("render_self", "setting.hoptimization.esp.render_self.name", false)
        );
        instance = this;
    }

    /**
     * Returns whether ESP is initialized, enabled, and unlocked under Cheat Mode.
     *
     * @return {@code true} if ESP is active
     */
    public static boolean isActive() {
        Esp module = instance;
        return module != null && module.isEnabled() && module.isAvailable();
    }

    /**
     * Returns whether vanilla player model and layer rendering should be suppressed in the world pass,
     * preserving overhead nametags, ground shadows, inventory paper-dolls, and Kill Effects corpses.
     *
     * @param entity candidate entity being rendered
     * @return {@code true} if original model and layer rendering should be skipped
     */
    public static boolean shouldSuppressOriginalRender(Entity entity) {
        Esp module = instance;
        if (module == null || !module.isEnabled() || !module.isAvailable()) {
            return false;
        }
        if (!(entity instanceof EntityPlayer) || KillEffects.shouldSuppressOriginalRender(entity)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        RenderManager renderManager = mc.getRenderManager();
        // Paper-doll inventory previews and internal effect passes disable renderShadow.
        if (renderManager == null || !renderManager.isRenderShadow()) {
            return false;
        }
        return module.isTargetEligible((EntityPlayer) entity, mc);
    }

    /**
     * Returns the monochrome base tint color setting.
     *
     * @return color setting
     */
    public ColorSetting getColorSetting() {
        return this.color;
    }

    /**
     * Returns the through-walls rendering setting.
     *
     * @return through walls setting
     */
    public BooleanSetting getThroughWallsSetting() {
        return this.throughWalls;
    }

    /**
     * Returns the monochrome fill opacity setting.
     *
     * @return fill opacity setting
     */
    public NumberSetting getFillOpacitySetting() {
        return this.fillOpacity;
    }

    /**
     * Returns the 1px cuboid sketch line art toggle setting.
     *
     * @return line art setting
     */
    public BooleanSetting getLineArtSetting() {
        return this.lineArt;
    }

    /**
     * Returns the sketch line art stroke width setting.
     *
     * @return line width setting
     */
    public NumberSetting getLineWidthSetting() {
        return this.lineWidth;
    }

    /**
     * Returns the tonal posterization toggle setting.
     *
     * @return posterize setting
     */
    public BooleanSetting getPosterizeSetting() {
        return this.posterize;
    }

    /**
     * Returns the teammate exclusion setting.
     *
     * @return ignore teammates setting
     */
    public BooleanSetting getIgnoreTeammatesSetting() {
        return this.ignoreTeammates;
    }

    /**
     * Returns the anti-cheat bot filter setting.
     *
     * @return filter bots setting
     */
    public BooleanSetting getFilterBotsSetting() {
        return this.filterBots;
    }

    /**
     * Returns the third-person self-render setting.
     *
     * @return render self setting
     */
    public BooleanSetting getRenderSelfSetting() {
        return this.renderSelf;
    }

    @Override
    protected void onDisable() {
        EspRenderer.resetState();
    }

    /**
     * Renders eligible players via {@link EspRenderer} after world geometry.
     *
     * @param event Forge render world last event
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled() || !isAvailable() || event == null) {
            return;
        }
        EspRenderer.render(
            this,
            this.color.getArgb(),
            this.throughWalls.isEnabled(),
            this.fillOpacity.getFloatValue() * 0.01F,
            this.lineArt.isEnabled(),
            this.lineWidth.getFloatValue(),
            this.posterize.isEnabled(),
            event.partialTicks
        );
    }

    /**
     * Resets cached renderer resources when the client world unloads.
     *
     * @param event Forge world unload event
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event == null || event.world == null || event.world.isRemote) {
            EspRenderer.resetState();
        }
    }

    @Override
    public boolean isTargetEligible(EntityPlayer target, Minecraft mc) {
        if (target == null || mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }
        if (!target.isEntityAlive() || target.isDead || target.isSpectator()) {
            return false;
        }
        if (KillEffects.shouldSuppressOriginalRender(target)) {
            return false;
        }
        if (target == mc.thePlayer) {
            if (!this.renderSelf.isEnabled()
                || mc.gameSettings == null
                || mc.gameSettings.thirdPersonView == 0) {
                return false;
            }
        } else {
            if (this.filterBots.isEnabled()) {
                if (target.getGameProfile() == null || target.getUniqueID() == null) {
                    return false;
                }
                String profileName = target.getGameProfile().getName();
                if (profileName == null || profileName.isEmpty()) {
                    return false;
                }
                if (target.isInvisible()) {
                    NetHandlerPlayClient netHandler = mc.getNetHandler();
                    if (netHandler == null || netHandler.getPlayerInfo(target.getUniqueID()) == null) {
                        return false;
                    }
                }
            }
            if (this.ignoreTeammates.isEnabled() && mc.thePlayer.isOnSameTeam(target)) {
                return false;
            }
        }
        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null) {
            viewEntity = mc.thePlayer;
        }
        return target.getDistanceSqToEntity(viewEntity) <= EspRenderer.MAX_RANGE_SQ;
    }
}
