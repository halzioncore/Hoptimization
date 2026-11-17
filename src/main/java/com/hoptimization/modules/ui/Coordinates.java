package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

/**
 * HUD module displaying rounded {@code X / Y / Z} coordinates, horizontal facing direction, and current biome.
 */
public class Coordinates extends Module implements HudElement {
    private static final float MIN_CARD_WIDTH = 120.0f;
    private static final String LABEL_XYZ = "XYZ ";
    private static final String SEP_DOT = " \u00b7 ";
    private static final String COORD_SEP = " / ";

    private final BooleanSetting showFacing;
    private final BooleanSetting showBiome;
    private final ModeSetting style;

    private final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
    private final StringBuilder coordScratch = new StringBuilder(32);

    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;
    private String cachedCoordText = "0 / 64 / 0";

    private String cachedLangCode = null;
    private String labelNorth = "North (-Z)";
    private String labelSouth = "South (+Z)";
    private String labelWest = "West (-X)";
    private String labelEast = "East (+X)";
    private String labelUnknownBiome = "Plains";

    /**
     * Creates the Coordinates module, registers its settings, and attaches it to {@link HudOverlayRenderer}.
     */
    public Coordinates() {
        super("coordinates", Category.UI);
        this.showFacing = addSetting(
            new BooleanSetting("show_facing", "setting.hoptimization.coordinates.show_facing.name", true)
        );
        this.showBiome = addSetting(
            new BooleanSetting("show_biome", "setting.hoptimization.coordinates.show_biome.name", true)
        );
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.coordinates.style.name", "card", "card", "text")
        );
        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.ensureRegistered();
    }

    /**
     * Returns the facing direction display toggle setting.
     *
     * @return show facing setting
     */
    public BooleanSetting getShowFacingSetting() {
        return this.showFacing;
    }

    /**
     * Returns the current biome display toggle setting.
     *
     * @return show biome setting
     */
    public BooleanSetting getShowBiomeSetting() {
        return this.showBiome;
    }

    /**
     * Returns the HUD rendering style setting ({@code card} or {@code text}).
     *
     * @return style setting
     */
    public ModeSetting getStyleSetting() {
        return this.style;
    }

    @Override
    public String getHudId() {
        return getId();
    }

    @Override
    public String getHudDisplayName() {
        return getDisplayName();
    }

    @Override
    public boolean isHudEnabled() {
        return isEnabled();
    }

    @Override
    public float getHudWidth() {
        refreshLabelsIfNeeded();
        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();

        int[] coords = resolveCoords();
        String coordText = getOrFormatCoords(coords[0], coords[1], coords[2]);
        float line1W = bodyFont.getWidth(LABEL_XYZ) + bodyFont.getWidth(coordText);

        float line2W = 0.0f;
        boolean facingOn = this.showFacing.isEnabled();
        boolean biomeOn = this.showBiome.isEnabled();
        if (facingOn || biomeOn) {
            String facingStr = facingOn ? resolveFacingText() : "";
            String biomeStr = biomeOn ? resolveBiomeText(coords[0], coords[1], coords[2]) : "";
            if (facingOn && biomeOn) {
                line2W = captionFont.getWidth(facingStr) + captionFont.getWidth(SEP_DOT) + captionFont.getWidth(biomeStr);
            } else if (facingOn) {
                line2W = captionFont.getWidth(facingStr);
            } else {
                line2W = captionFont.getWidth(biomeStr);
            }
        }

        float contentW = Math.max(line1W, line2W);
        return Math.max(MIN_CARD_WIDTH, contentW + UiTokens.HUD_PADDING_H * 2.0f);
    }

    @Override
    public float getHudHeight() {
        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        boolean hasSecondLine = this.showFacing.isEnabled() || this.showBiome.isEnabled();
        float contentH = bodyFont.getHeight();
        if (hasSecondLine) {
            contentH += UiTokens.SPACE_2 + captionFont.getHeight();
        }
        return contentH + UiTokens.HUD_PADDING_V * 2.0f;
    }

    @Override
    public float getDefaultHudX(float screenWidth, float screenHeight) {
        return UiTokens.HUD_SCREEN_MARGIN;
    }

    @Override
    public float getDefaultHudY(float screenWidth, float screenHeight) {
        return UiTokens.HUD_SCREEN_MARGIN + 68.0f;
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        refreshLabelsIfNeeded();

        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();

        int[] coords = resolveCoords();
        String coordText = getOrFormatCoords(coords[0], coords[1], coords[2]);

        boolean facingOn = this.showFacing.isEnabled();
        boolean biomeOn = this.showBiome.isEnabled();
        boolean hasSecondLine = facingOn || biomeOn;

        String facingStr = facingOn ? resolveFacingText() : "";
        String biomeStr = biomeOn ? resolveBiomeText(coords[0], coords[1], coords[2]) : "";

        float line1W = bodyFont.getWidth(LABEL_XYZ) + bodyFont.getWidth(coordText);
        float line2W = 0.0f;
        if (facingOn && biomeOn) {
            line2W = captionFont.getWidth(facingStr) + captionFont.getWidth(SEP_DOT) + captionFont.getWidth(biomeStr);
        } else if (facingOn) {
            line2W = captionFont.getWidth(facingStr);
        } else if (biomeOn) {
            line2W = captionFont.getWidth(biomeStr);
        }

        float boxW = Math.max(MIN_CARD_WIDTH, Math.max(line1W, line2W) + UiTokens.HUD_PADDING_H * 2.0f);
        float contentH = bodyFont.getHeight() + (hasSecondLine ? UiTokens.SPACE_2 + captionFont.getHeight() : 0.0f);
        float boxH = contentH + UiTokens.HUD_PADDING_V * 2.0f;

        boolean cardMode = !this.style.is("text");
        if (cardMode) {
            HudRenderHelper.drawHudCard(x, y, boxW, boxH, theme, 1.0f);
        }

        boolean shadow = !cardMode;
        float textX = x + UiTokens.HUD_PADDING_H;
        float line1Y = y + UiTokens.HUD_PADDING_V;

        float afterPrefixX = HudRenderHelper.drawHudText(bodyFont, LABEL_XYZ, textX, line1Y, theme.accent(), shadow);
        HudRenderHelper.drawHudText(bodyFont, coordText, afterPrefixX, line1Y, theme.textPrimary(), shadow);

        if (hasSecondLine) {
            float line2Y = line1Y + bodyFont.getHeight() + UiTokens.SPACE_2;
            if (facingOn && biomeOn) {
                float cx = HudRenderHelper.drawHudText(captionFont, facingStr, textX, line2Y, theme.textSecondary(), shadow);
                cx = HudRenderHelper.drawHudText(captionFont, SEP_DOT, cx, line2Y, theme.textTertiary(), shadow);
                HudRenderHelper.drawHudText(captionFont, biomeStr, cx, line2Y, theme.textSecondary(), shadow);
            } else if (facingOn) {
                HudRenderHelper.drawHudText(captionFont, facingStr, textX, line2Y, theme.textSecondary(), shadow);
            } else {
                HudRenderHelper.drawHudText(captionFont, biomeStr, textX, line2Y, theme.textSecondary(), shadow);
            }
        }
    }

    private final int[] coordCacheArray = new int[3];

    private int[] resolveCoords() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity entity = (mc != null && mc.thePlayer != null)
            ? (mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.thePlayer)
            : null;
        if (entity == null) {
            this.coordCacheArray[0] = 0;
            this.coordCacheArray[1] = 64;
            this.coordCacheArray[2] = 0;
        } else {
            this.coordCacheArray[0] = (int) Math.round(entity.posX);
            this.coordCacheArray[1] = (int) Math.round(entity.getEntityBoundingBox().minY);
            this.coordCacheArray[2] = (int) Math.round(entity.posZ);
        }
        return this.coordCacheArray;
    }

    private String getOrFormatCoords(int x, int y, int z) {
        if (x != this.lastX || y != this.lastY || z != this.lastZ) {
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.coordScratch.setLength(0);
            this.coordScratch.append(x).append(COORD_SEP).append(y).append(COORD_SEP).append(z);
            this.cachedCoordText = this.coordScratch.toString();
        }
        return this.cachedCoordText;
    }

    private String resolveFacingText() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity entity = (mc != null && mc.thePlayer != null)
            ? (mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.thePlayer)
            : null;
        if (entity == null) {
            return this.labelNorth;
        }
        EnumFacing facing = entity.getHorizontalFacing();
        if (facing == null) {
            return this.labelNorth;
        }
        switch (facing) {
            case SOUTH:
                return this.labelSouth;
            case WEST:
                return this.labelWest;
            case EAST:
                return this.labelEast;
            case NORTH:
            default:
                return this.labelNorth;
        }
    }

    private String resolveBiomeText(int blockX, int blockY, int blockZ) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return this.labelUnknownBiome;
        }
        this.mutableBlockPos.set(blockX, MathHelper.clamp_int(blockY, 0, 255), blockZ);
        if (!mc.theWorld.isBlockLoaded(this.mutableBlockPos)) {
            return this.labelUnknownBiome;
        }
        Chunk chunk = mc.theWorld.getChunkFromBlockCoords(this.mutableBlockPos);
        if (chunk == null) {
            return this.labelUnknownBiome;
        }
        BiomeGenBase biome = chunk.getBiome(this.mutableBlockPos, mc.theWorld.getWorldChunkManager());
        return (biome != null && biome.biomeName != null) ? biome.biomeName : this.labelUnknownBiome;
    }

    private void refreshLabelsIfNeeded() {
        String lang = HudRenderHelper.getCurrentLanguageCode();
        if (lang.equals(this.cachedLangCode)) {
            return;
        }
        this.cachedLangCode = lang;
        this.labelNorth = HudRenderHelper.resolveLang("ui.hoptimization.hud.facing.north", "North (-Z)");
        this.labelSouth = HudRenderHelper.resolveLang("ui.hoptimization.hud.facing.south", "South (+Z)");
        this.labelWest = HudRenderHelper.resolveLang("ui.hoptimization.hud.facing.west", "West (-X)");
        this.labelEast = HudRenderHelper.resolveLang("ui.hoptimization.hud.facing.east", "East (+X)");
        this.labelUnknownBiome = HudRenderHelper.resolveLang("ui.hoptimization.hud.biome.unknown", "Plains");
    }
}
