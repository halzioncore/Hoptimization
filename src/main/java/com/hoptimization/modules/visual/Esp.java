package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.modules.visual.esp.EspRenderer;
import com.hoptimization.modules.visual.esp.EspTargetPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Cheat-gated visual module rendering players as futuristic cockpit mecha silhouettes or outlines.
 */
public class Esp extends Module implements EspTargetPolicy {
    public static final String ID = "esp";

    public static final String STYLE_COCKPIT = "cockpit";
    public static final String STYLE_OVERLAY = "overlay";
    public static final String STYLE_WIREFRAME = "wireframe";

    public static final String COLOR_CUSTOM = "custom";
    public static final String COLOR_TEAM = "team";
    public static final String COLOR_HEALTH = "health";

    private static final int DEFAULT_TEAM_COLOR = 0xFF38E8FF;
    private static final int HEALTH_HIGH_COLOR = 0xFF3DF58C;
    private static final int HEALTH_MID_COLOR = 0xFFFFC947;
    private static final int HEALTH_LOW_COLOR = 0xFFFF4A60;
    private static final int HEALTH_OCCLUDED_WARM_MIX = 0xFF8C6448;
    private static final float HEALTH_OCCLUDED_BRIGHTNESS = 0.68F;
    private static final float HEALTH_OCCLUDED_MIX_WEIGHT = 0.25F;
    private static final float TEAM_OCCLUDED_BRIGHTNESS = 0.62F;

    private static final int[] VANILLA_COLOR_PALETTE = {
        0xFF000000,
        0xFF0000AA,
        0xFF00AA00,
        0xFF00AAAA,
        0xFFAA0000,
        0xFFAA00AA,
        0xFFFFAA00,
        0xFFAAAAAA,
        0xFF555555,
        0xFF5555FF,
        0xFF55FF55,
        0xFF55FFFF,
        0xFFFF5555,
        0xFFFF55FF,
        0xFFFFFF55,
        0xFFFFFFFF
    };

    private final ModeSetting style;
    private final BooleanSetting throughWalls;
    private final ModeSetting colorMode;
    private final ColorSetting visibleColor;
    private final ColorSetting hiddenColor;
    private final NumberSetting fillOpacity;
    private final NumberSetting lineWidth;
    private final BooleanSetting scanlines;
    private final BooleanSetting groundRing;
    private final BooleanSetting ignoreTeammates;
    private final BooleanSetting filterBots;
    private final BooleanSetting renderSelf;

    /**
     * Creates the ESP module (disabled by default, requires Cheat Mode) and registers its settings.
     */
    public Esp() {
        super(ID, Category.VISUAL, Keyboard.KEY_NONE, true, true);
        this.style = addSetting(
            new ModeSetting(
                "style",
                "setting.hoptimization.esp.style.name",
                STYLE_COCKPIT,
                STYLE_COCKPIT,
                STYLE_OVERLAY,
                STYLE_WIREFRAME
            )
        );
        this.throughWalls = addSetting(
            new BooleanSetting("through_walls", "setting.hoptimization.esp.through_walls.name", true)
        );
        this.colorMode = addSetting(
            new ModeSetting(
                "color_mode",
                "setting.hoptimization.esp.color_mode.name",
                COLOR_CUSTOM,
                COLOR_CUSTOM,
                COLOR_TEAM,
                COLOR_HEALTH
            )
        );
        this.visibleColor = addSetting(
            new ColorSetting("visible_color", "setting.hoptimization.esp.visible_color.name", 0xFF38E8FF, false)
                .visibleWhen(() -> Boolean.valueOf(this.colorMode.is(COLOR_CUSTOM)))
        );
        this.hiddenColor = addSetting(
            new ColorSetting("hidden_color", "setting.hoptimization.esp.hidden_color.name", 0xFFFF8A3D, false)
                .visibleWhen(() -> Boolean.valueOf(this.throughWalls.isEnabled() && this.colorMode.is(COLOR_CUSTOM)))
        );
        this.fillOpacity = addSetting(
            new NumberSetting(
                "fill_opacity",
                "setting.hoptimization.esp.fill_opacity.name",
                80.0D,
                0.0D,
                100.0D,
                5.0D,
                "%"
            ).visibleWhen(() -> Boolean.valueOf(!this.style.is(STYLE_WIREFRAME)))
        );
        this.lineWidth = addSetting(
            new NumberSetting(
                "line_width",
                "setting.hoptimization.esp.line_width.name",
                1.75D,
                1.0D,
                3.5D,
                0.25D,
                "px"
            )
        );
        this.scanlines = addSetting(
            new BooleanSetting("scanlines", "setting.hoptimization.esp.scanlines.name", true)
        );
        this.groundRing = addSetting(
            new BooleanSetting("ground_ring", "setting.hoptimization.esp.ground_ring.name", true)
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
    }

    /**
     * Returns the render style setting.
     *
     * @return style setting
     */
    public ModeSetting getStyleSetting() {
        return this.style;
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
     * Returns the target color mode setting.
     *
     * @return color mode setting
     */
    public ModeSetting getColorModeSetting() {
        return this.colorMode;
    }

    /**
     * Returns the visible target color setting.
     *
     * @return visible color setting
     */
    public ColorSetting getVisibleColorSetting() {
        return this.visibleColor;
    }

    /**
     * Returns the occluded target color setting.
     *
     * @return hidden color setting
     */
    public ColorSetting getHiddenColorSetting() {
        return this.hiddenColor;
    }

    /**
     * Returns the facet fill opacity setting.
     *
     * @return fill opacity setting
     */
    public NumberSetting getFillOpacitySetting() {
        return this.fillOpacity;
    }

    /**
     * Returns the wireframe stroke width setting.
     *
     * @return line width setting
     */
    public NumberSetting getLineWidthSetting() {
        return this.lineWidth;
    }

    /**
     * Returns the cockpit scanline effect setting.
     *
     * @return scanlines setting
     */
    public BooleanSetting getScanlinesSetting() {
        return this.scanlines;
    }

    /**
     * Returns the ground indicator ring setting.
     *
     * @return ground ring setting
     */
    public BooleanSetting getGroundRingSetting() {
        return this.groundRing;
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
     * Suppresses vanilla player rendering in Cockpit mode while preserving paper-doll and internal passes.
     *
     * @param event Forge pre-render player event
     */
    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!isEnabled() || !isAvailable() || !this.style.is(STYLE_COCKPIT)) {
            return;
        }
        if (event == null || event.entityPlayer == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        RenderManager renderManager = mc.getRenderManager();
        // Paper-doll inventory previews and internal effect passes disable renderShadow.
        if (renderManager == null || !renderManager.isRenderShadow()) {
            return;
        }
        if (isTargetEligible(event.entityPlayer, mc)) {
            event.setCanceled(true);
        }
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
            resolveStyleIndex(),
            this.throughWalls.isEnabled(),
            this.fillOpacity.getFloatValue() * 0.01F,
            this.lineWidth.getFloatValue(),
            this.scanlines.isEnabled(),
            this.groundRing.isEnabled(),
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

    @Override
    public int resolveTargetColorArgb(EntityPlayer target, boolean occluded) {
        if (this.colorMode.is(COLOR_HEALTH)) {
            return resolveHealthColorArgb(target, occluded);
        }
        if (this.colorMode.is(COLOR_TEAM)) {
            return resolveTeamColorArgb(target, occluded);
        }
        return (occluded && this.throughWalls.isEnabled())
            ? this.hiddenColor.getArgb()
            : this.visibleColor.getArgb();
    }

    private int resolveStyleIndex() {
        if (this.style.is(STYLE_OVERLAY)) {
            return EspRenderer.STYLE_OVERLAY;
        }
        if (this.style.is(STYLE_WIREFRAME)) {
            return EspRenderer.STYLE_WIREFRAME;
        }
        return EspRenderer.STYLE_COCKPIT;
    }

    private static int resolveHealthColorArgb(EntityPlayer target, boolean occluded) {
        if (target == null) {
            return occluded
                ? lerpArgb(scaleBrightness(HEALTH_HIGH_COLOR, HEALTH_OCCLUDED_BRIGHTNESS),
                    HEALTH_OCCLUDED_WARM_MIX, HEALTH_OCCLUDED_MIX_WEIGHT)
                : HEALTH_HIGH_COLOR;
        }
        float maxHealth = target.getMaxHealth();
        if (maxHealth < 1.0F) {
            maxHealth = 1.0F;
        }
        float ratio = clamp01(target.getHealth() / maxHealth);
        int baseColor;
        if (ratio >= 0.5F) {
            baseColor = lerpArgb(HEALTH_MID_COLOR, HEALTH_HIGH_COLOR, (ratio - 0.5F) * 2.0F);
        } else {
            baseColor = lerpArgb(HEALTH_LOW_COLOR, HEALTH_MID_COLOR, ratio * 2.0F);
        }
        if (!occluded) {
            return baseColor;
        }
        int dimmed = scaleBrightness(baseColor, HEALTH_OCCLUDED_BRIGHTNESS);
        return lerpArgb(dimmed, HEALTH_OCCLUDED_WARM_MIX, HEALTH_OCCLUDED_MIX_WEIGHT);
    }

    private static int resolveTeamColorArgb(EntityPlayer target, boolean occluded) {
        if (target == null) {
            return occluded ? scaleBrightness(DEFAULT_TEAM_COLOR, TEAM_OCCLUDED_BRIGHTNESS) : DEFAULT_TEAM_COLOR;
        }
        int colorIndex = -1;
        Team rawTeam = target.getTeam();
        if (rawTeam instanceof ScorePlayerTeam) {
            ScorePlayerTeam team = (ScorePlayerTeam) rawTeam;
            colorIndex = findLastColorIndex(team.getColorPrefix());
            if (colorIndex < 0) {
                EnumChatFormatting chatFormat = team.getChatFormat();
                if (chatFormat != null && chatFormat.isColor()) {
                    colorIndex = chatFormat.getColorIndex();
                }
            }
        }
        if (colorIndex < 0) {
            colorIndex = findLastColorIndex(target.getDisplayNameString());
        }
        int baseColor = (colorIndex >= 0 && colorIndex < VANILLA_COLOR_PALETTE.length)
            ? VANILLA_COLOR_PALETTE[colorIndex]
            : DEFAULT_TEAM_COLOR;
        if (!occluded) {
            return baseColor;
        }
        return scaleBrightness(baseColor, TEAM_OCCLUDED_BRIGHTNESS);
    }

    private static int findLastColorIndex(String text) {
        if (text == null) {
            return -1;
        }
        int len = text.length();
        int lastColor = -1;
        for (int i = 0; i < len - 1; i++) {
            if (text.charAt(i) == '\u00a7') {
                int colorIdx = colorCodeToIndex(text.charAt(i + 1));
                if (colorIdx >= 0) {
                    lastColor = colorIdx;
                }
                i++;
            }
        }
        return lastColor;
    }

    private static int colorCodeToIndex(char code) {
        if (code >= '0' && code <= '9') {
            return code - '0';
        }
        if (code >= 'a' && code <= 'f') {
            return 10 + (code - 'a');
        }
        if (code >= 'A' && code <= 'F') {
            return 10 + (code - 'A');
        }
        return -1;
    }

    private static float clamp01(float value) {
        if (value <= 0.0F || Float.isNaN(value)) {
            return 0.0F;
        }
        if (value >= 1.0F) {
            return 1.0F;
        }
        return value;
    }

    private static int lerpArgb(int startArgb, int endArgb, float t) {
        float clamped = clamp01(t);
        int sa = (startArgb >>> 24) & 0xFF;
        int sr = (startArgb >>> 16) & 0xFF;
        int sg = (startArgb >>> 8) & 0xFF;
        int sb = startArgb & 0xFF;

        int ea = (endArgb >>> 24) & 0xFF;
        int er = (endArgb >>> 16) & 0xFF;
        int eg = (endArgb >>> 8) & 0xFF;
        int eb = endArgb & 0xFF;

        int a = Math.round(sa + (ea - sa) * clamped);
        int r = Math.round(sr + (er - sr) * clamped);
        int g = Math.round(sg + (eg - sg) * clamped);
        int b = Math.round(sb + (eb - sb) * clamped);
        return (clampByte(a) << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    private static int scaleBrightness(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = clampByte(Math.round(((argb >>> 16) & 0xFF) * factor));
        int g = clampByte(Math.round(((argb >>> 8) & 0xFF) * factor));
        int b = clampByte(Math.round((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clampByte(int channel) {
        if (channel < 0) {
            return 0;
        }
        if (channel > 255) {
            return 255;
        }
        return channel;
    }
}
