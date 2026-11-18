package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTBlendFuncSeparate;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cheat-gated module rendering clean through-wall 3D billboard nametag cards
 * with distance scaling, semantic health colors, thin health bars, and optional equipment icons.
 */
public class Nametags extends Module {
    public static final String ID = "nametags";

    private static final int MAX_ENTITIES = 256;
    private static final int DIST_CACHE_LIMIT = 256;
    private static final int HP_TENTHS_CACHE_LIMIT = 400;
    private static final int CLEAN_NAME_CACHE_SIZE = 64;
    private static final int CORNER_SEGMENTS = 6;
    private static final int TOTAL_CORNER_POINTS = (CORNER_SEGMENTS + 1) * 4;

    private static final float MAX_NAME_WIDTH = 136.0F;
    private static final float CARD_PAD_X = 6.0F;
    private static final float CARD_PAD_Y = 3.0F;
    private static final float ROW_GAP = 5.0F;
    private static final float BAR_HEIGHT = 1.5F;
    private static final float BAR_TOP_GAP = 2.5F;
    private static final float BAR_BOTTOM_PAD = 2.0F;
    private static final float ITEM_ICON_SIZE = 14.0F;
    private static final float ITEM_ICON_SCALE = 14.0F / 16.0F;
    private static final float ITEM_STEP_X = 15.5F;
    private static final float ITEM_ROW_GAP = 2.5F;

    private static final int FALLBACK_SURFACE = 0xFF111318;
    private static final int FALLBACK_SURFACE_TRACK = 0x732A2E3A;
    private static final int FALLBACK_BORDER = 0x38FFFFFF;
    private static final int FALLBACK_TEXT_PRIMARY = 0xFFF2F4F8;
    private static final int FALLBACK_TEXT_SECONDARY = 0xFFA2AAB8;
    private static final int FALLBACK_SUCCESS = 0xFF3DD68C;
    private static final int FALLBACK_WARNING = 0xFFF5B544;
    private static final int FALLBACK_DANGER = 0xFFF2555A;

    private static final String[] DIST_LABELS = new String[DIST_CACHE_LIMIT + 1];
    private static final String[] HP_TENTHS_LABELS = new String[HP_TENTHS_CACHE_LIMIT + 1];

    private static final float[] CORNER_COS = new float[CORNER_SEGMENTS + 1];
    private static final float[] CORNER_SIN = new float[CORNER_SEGMENTS + 1];
    private static final float[] QUAD_COS_X = new float[] { -1.0F, 0.0F, 1.0F, 0.0F };
    private static final float[] QUAD_SIN_X = new float[] { 0.0F, 1.0F, 0.0F, -1.0F };
    private static final float[] QUAD_COS_Y = new float[] { 0.0F, -1.0F, 0.0F, 1.0F };
    private static final float[] QUAD_SIN_Y = new float[] { -1.0F, 0.0F, 1.0F, 0.0F };

    static {
        for (int i = 0; i <= DIST_CACHE_LIMIT; i++) {
            DIST_LABELS[i] = Integer.toString(i) + "m";
        }
        for (int i = 0; i <= HP_TENTHS_CACHE_LIMIT; i++) {
            HP_TENTHS_LABELS[i] = Integer.toString(i / 10) + "." + Integer.toString(i % 10);
        }
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            double angle = (Math.PI * 0.5D * i) / CORNER_SEGMENTS;
            CORNER_COS[i] = (float) Math.cos(angle);
            CORNER_SIN[i] = (float) Math.sin(angle);
        }
    }

    private static Nametags instance;

    private final BooleanSetting players;
    private final BooleanSetting mobs;
    private final NumberSetting scale;
    private final BooleanSetting showHealth;
    private final BooleanSetting showDistance;
    private final BooleanSetting showArmor;
    private final BooleanSetting showInvisibles;
    private final BooleanSetting filterBots;
    private final NumberSetting backgroundOpacity;
    private final BooleanSetting followTheme;

    private final EntityLivingBase[] entityBuffer = new EntityLivingBase[MAX_ENTITIES];
    private final double[] distSqBuffer = new double[MAX_ENTITIES];
    private final ItemStack[] itemScratch = new ItemStack[5];
    private final StringBuilder textScratch = new StringBuilder(64);

    private final float[] outerRingX = new float[TOTAL_CORNER_POINTS];
    private final float[] outerRingY = new float[TOTAL_CORNER_POINTS];
    private final float[] innerRingX = new float[TOTAL_CORNER_POINTS];
    private final float[] innerRingY = new float[TOTAL_CORNER_POINTS];

    private final String[] rawNameCache = new String[CLEAN_NAME_CACHE_SIZE];
    private final String[] cleanNameCache = new String[CLEAN_NAME_CACHE_SIZE];
    private final Map<Class<?>, String> mobNameCache = new HashMap<Class<?>, String>(32);
    private int cleanNameCursor = 0;

    /**
     * Creates the Nametags module (disabled by default, requires Cheat Mode).
     */
    public Nametags() {
        super(ID, Category.VISUAL, Keyboard.KEY_NONE, true, true);
        this.players = addSetting(
            new BooleanSetting("players", "setting.hoptimization.nametags.players.name", true)
        );
        this.mobs = addSetting(
            new BooleanSetting("mobs", "setting.hoptimization.nametags.mobs.name", false)
        );
        this.scale = addSetting(
            new NumberSetting(
                "scale",
                "setting.hoptimization.nametags.scale.name",
                1.0D,
                0.5D,
                2.0D,
                0.05D,
                "x"
            )
        );
        this.showHealth = addSetting(
            new BooleanSetting("show_health", "setting.hoptimization.nametags.show_health.name", true)
        );
        this.showDistance = addSetting(
            new BooleanSetting("show_distance", "setting.hoptimization.nametags.show_distance.name", true)
        );
        this.showArmor = addSetting(
            new BooleanSetting("show_armor", "setting.hoptimization.nametags.show_armor.name", true)
        );
        this.showInvisibles = addSetting(
            new BooleanSetting("show_invisibles", "setting.hoptimization.nametags.show_invisibles.name", true)
        );
        this.filterBots = addSetting(
            new BooleanSetting("filter_bots", "setting.hoptimization.nametags.filter_bots.name", true)
        );
        this.backgroundOpacity = addSetting(
            new NumberSetting(
                "background_opacity",
                "setting.hoptimization.nametags.background_opacity.name",
                0.75D,
                0.0D,
                1.0D,
                0.05D
            )
        );
        this.followTheme = addSetting(
            new BooleanSetting("follow_theme", "setting.hoptimization.nametags.follow_theme.name", true)
        );
        instance = this;
    }

    /**
     * Returns the shared {@link Nametags} instance if initialized.
     *
     * @return module instance or null
     */
    public static Nametags getInstance() {
        return instance;
    }

    public BooleanSetting getPlayersSetting() {
        return this.players;
    }

    public BooleanSetting getMobsSetting() {
        return this.mobs;
    }

    public NumberSetting getScaleSetting() {
        return this.scale;
    }

    public BooleanSetting getShowHealthSetting() {
        return this.showHealth;
    }

    public BooleanSetting getShowDistanceSetting() {
        return this.showDistance;
    }

    public BooleanSetting getShowArmorSetting() {
        return this.showArmor;
    }

    public BooleanSetting getShowInvisiblesSetting() {
        return this.showInvisibles;
    }

    public BooleanSetting getFilterBotsSetting() {
        return this.filterBots;
    }

    public NumberSetting getBackgroundOpacitySetting() {
        return this.backgroundOpacity;
    }

    public BooleanSetting getFollowThemeSetting() {
        return this.followTheme;
    }

    /**
     * Returns whether vanilla nametag rendering should be suppressed for {@code entity}
     * to prevent duplicate overlapping labels while {@link Nametags} is active.
     *
     * @param entity rendered living entity
     * @return {@code true} if {@link Nametags} handles rendering this entity's tag
     */
    public static boolean shouldSuppressVanillaNametag(EntityLivingBase entity) {
        Nametags module = instance;
        if (module == null || !module.isEnabled() || !module.isAvailable() || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return module.isEligibleEntity(entity, mc);
    }

    @Override
    protected void onDisable() {
        clearEntityBuffer();
    }

    /**
     * Releases buffered entity references when the client world unloads.
     *
     * @param event Forge world unload event
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event != null && event.world != null && event.world.isRemote) {
            clearEntityBuffer();
        }
    }

    /**
     * Renders 3D through-wall billboard nametag cards for eligible entities sorted far-to-near.
     *
     * @param event Forge render world last event
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled() || !isAvailable() || event == null) {
            return;
        }
        if (!this.players.isEnabled() && !this.mobs.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        RenderManager renderManager = mc.getRenderManager();
        if (renderManager == null) {
            return;
        }

        Entity viewEntity = mc.getRenderViewEntity();
        double camX;
        double camY;
        double camZ;
        if (viewEntity != null) {
            camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * (double) event.partialTicks;
            camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * (double) event.partialTicks;
            camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * (double) event.partialTicks;
        } else {
            camX = renderManager.viewerPosX;
            camY = renderManager.viewerPosY;
            camZ = renderManager.viewerPosZ;
        }

        Vec3 camOffset = ActiveRenderInfo.getPosition();
        double sortOriginX = camX + (camOffset != null ? camOffset.xCoord : 0.0D);
        double sortOriginY = camY + (camOffset != null ? camOffset.yCoord : 0.0D);
        double sortOriginZ = camZ + (camOffset != null ? camOffset.zCoord : 0.0D);

        int count = collectAndSortEntities(mc, sortOriginX, sortOriginY, sortOriginZ, event.partialTicks);
        if (count <= 0) {
            return;
        }

        ThemeData theme = this.followTheme.isEnabled() ? ThemeManager.current() : null;
        TtfFontRenderer nameFont = FontManager.body();
        TtfFontRenderer metaFont = FontManager.caption();

        float userScale = this.scale.getFloatValue();
        float bgOpacity = clamp01(this.backgroundOpacity.getFloatValue());
        float viewY = renderManager.playerViewY;
        float viewX = (mc.gameSettings != null && mc.gameSettings.thirdPersonView == 2)
            ? -renderManager.playerViewX
            : renderManager.playerViewX;

        ContextCapabilities caps = Display.isCreated() ? GLContext.getCapabilities() : null;
        boolean hasGl14 = caps != null && caps.OpenGL14;
        boolean hasExtBlendSep = caps != null && caps.GL_EXT_blend_func_separate;

        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        int prevMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int prevAlphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float prevAlphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        float prevBrightX = OpenGlHelper.lastBrightnessX;
        float prevBrightY = OpenGlHelper.lastBrightnessY;
        int prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        int prevBlendSrcAlpha = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
            : (hasExtBlendSep ? GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_SRC_ALPHA_EXT) : prevBlendSrc);
        int prevBlendDstAlpha = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
            : (hasExtBlendSep ? GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_DST_ALPHA_EXT) : prevBlendDst);

        try {
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.shadeModel(GL11.GL_SMOOTH);

            for (int i = 0; i < count; i++) {
                EntityLivingBase entity = this.entityBuffer[i];
                double camDistSq = this.distSqBuffer[i];
                this.entityBuffer[i] = null;
                if (entity == null) {
                    continue;
                }
                renderNametagCard(
                    mc,
                    entity,
                    camDistSq,
                    camX, camY, camZ,
                    viewX, viewY,
                    event.partialTicks,
                    userScale,
                    bgOpacity,
                    theme,
                    nameFont,
                    metaFont,
                    prevTex2D
                );
            }
        } finally {
            clearEntityBuffer();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevBrightX, prevBrightY);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            if (prevMatrixMode != GL11.GL_MODELVIEW) {
                GlStateManager.matrixMode(prevMatrixMode);
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(prevTex2D);
            GlStateManager.setActiveTexture(prevActiveTex);

            GlStateManager.shadeModel(prevShadeModel);
            GlStateManager.depthFunc(prevDepthFunc);
            GlStateManager.depthMask(prevDepthMask);
            GlStateManager.alphaFunc(prevAlphaFunc, prevAlphaRef);
            GlStateManager.tryBlendFuncSeparate(
                prevBlendSrc, prevBlendDst,
                prevBlendSrcAlpha, prevBlendDstAlpha
            );
            if (wasDepth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            if (wasCull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (wasAlpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            if (wasLighting) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
            if (wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            GlStateManager.resetColor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private int collectAndSortEntities(
        Minecraft mc,
        double sortOriginX,
        double sortOriginY,
        double sortOriginZ,
        float partialTicks
    ) {
        List<Entity> loaded = mc.theWorld.loadedEntityList;
        if (loaded == null || loaded.isEmpty()) {
            return 0;
        }
        int count = 0;
        int listSize = loaded.size();
        for (int i = 0; i < listSize; i++) {
            Entity raw = loaded.get(i);
            if (!(raw instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase entity = (EntityLivingBase) raw;
            if (!isEligibleEntity(entity, mc)) {
                continue;
            }
            double ex = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * (double) partialTicks;
            double ey = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * (double) partialTicks;
            double ez = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * (double) partialTicks;
            double dx = ex - sortOriginX;
            double dy = (ey + (double) entity.height) - sortOriginY;
            double dz = ez - sortOriginZ;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (count < MAX_ENTITIES) {
                this.entityBuffer[count] = entity;
                this.distSqBuffer[count] = distSq;
                count++;
            } else {
                int furthestIdx = 0;
                double furthestDistSq = this.distSqBuffer[0];
                for (int j = 1; j < MAX_ENTITIES; j++) {
                    if (this.distSqBuffer[j] > furthestDistSq) {
                        furthestDistSq = this.distSqBuffer[j];
                        furthestIdx = j;
                    }
                }
                if (distSq < furthestDistSq) {
                    this.entityBuffer[furthestIdx] = entity;
                    this.distSqBuffer[furthestIdx] = distSq;
                }
            }
        }

        // Far-to-near in-place insertion sort so closer cards draw cleanly on top with zero allocations.
        for (int i = 1; i < count; i++) {
            EntityLivingBase keyEntity = this.entityBuffer[i];
            double keyDist = this.distSqBuffer[i];
            int j = i - 1;
            while (j >= 0 && this.distSqBuffer[j] < keyDist) {
                this.entityBuffer[j + 1] = this.entityBuffer[j];
                this.distSqBuffer[j + 1] = this.distSqBuffer[j];
                j--;
            }
            this.entityBuffer[j + 1] = keyEntity;
            this.distSqBuffer[j + 1] = keyDist;
        }
        return count;
    }

    private void renderNametagCard(
        Minecraft mc,
        EntityLivingBase entity,
        double camDistSq,
        double camX,
        double camY,
        double camZ,
        float viewX,
        float viewY,
        float partialTicks,
        float userScale,
        float bgOpacity,
        ThemeData theme,
        TtfFontRenderer nameFont,
        TtfFontRenderer metaFont,
        int prevTex2D
    ) {
        double ex = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * (double) partialTicks;
        double ey = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * (double) partialTicks;
        double ez = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * (double) partialTicks;

        float entityHeight = entity.height > 0.2F ? entity.height : 1.8F;
        double anchorY = ey + (double) entityHeight
            + (entity.isSneaking() ? 0.25D : 0.40D)
            - (entity.isChild() ? (double) (entityHeight * 0.35F) : 0.0D);

        float camDist = (float) Math.sqrt(Math.max(0.0D, camDistSq));
        float distFactor = camDist <= 5.5F ? 5.5F : (5.5F + (camDist - 5.5F) * 0.85F);
        float billboardScale = distFactor * 0.0019F * userScale;

        int playerDistMeters = Math.round(mc.thePlayer.getDistanceToEntity(entity));
        String distStr = this.showDistance.isEnabled() ? formatDistance(playerDistMeters) : null;
        String nameStr = getCleanEntityName(entity);

        float hp = Math.max(0.0F, entity.getHealth());
        float abs = Math.max(0.0F, entity.getAbsorptionAmount());
        float totalHp = hp + abs;
        float maxHp = entity.getMaxHealth() > 0.0F ? entity.getMaxHealth() : 20.0F;
        float healthRatio = clamp01(hp / maxHp);
        String hpStr = this.showHealth.isEnabled() ? formatHealth(totalHp) : null;

        float distW = distStr != null ? metaFont.getWidth(distStr) : 0.0F;
        float nameW = Math.min(MAX_NAME_WIDTH, nameFont.getWidth(nameStr));
        float hpW = hpStr != null ? nameFont.getWidth(hpStr) : 0.0F;
        float contentW = nameW
            + (distStr != null ? distW + ROW_GAP : 0.0F)
            + (hpStr != null ? ROW_GAP + hpW : 0.0F);
        float cardW = Math.max(24.0F, contentW + CARD_PAD_X * 2.0F);
        float textH = nameFont.getHeight();
        float cardH = CARD_PAD_Y + textH + BAR_TOP_GAP + BAR_HEIGHT + BAR_BOTTOM_PAD;
        float cardX = -cardW * 0.5F;
        float cardY = -cardH;

        int surfaceBase = theme != null ? theme.surface0() : FALLBACK_SURFACE;
        int bgArgb = RenderUtil.replaceAlpha(surfaceBase, Math.round(bgOpacity * 255.0F));
        int borderBase = theme != null ? theme.borderStrong() : FALLBACK_BORDER;
        int borderArgb = bgOpacity <= 0.01F
            ? 0
            : RenderUtil.withAlpha(borderBase, Math.min(1.0F, bgOpacity * 1.35F));
        int trackArgb = theme != null
            ? RenderUtil.withAlpha(theme.surface3(), Math.max(0.45F, bgOpacity))
            : FALLBACK_SURFACE_TRACK;
        int textPrimary = theme != null ? theme.textPrimary() : FALLBACK_TEXT_PRIMARY;
        int textSecondary = theme != null ? theme.textSecondary() : FALLBACK_TEXT_SECONDARY;
        int healthColor = resolveSemanticHealthColor(healthRatio, theme);
        int absorbColor = theme != null ? theme.warning() : FALLBACK_WARNING;
        float cornerRadius = theme != null ? Math.min(4.0F, theme.radiusControl() * 0.65F) : 3.5F;
        boolean textShadow = bgOpacity < 0.28F;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(ex - camX, anchorY - camY, ez - camZ);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-viewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(viewX, 1.0F, 0.0F, 0.0F);
            GlStateManager.scale(-billboardScale, -billboardScale, billboardScale);

            if (this.showArmor.isEnabled()) {
                int itemCount = collectEntityEquipment(entity);
                if (itemCount > 0) {
                    renderEquipmentRow(mc, itemCount, cardY, prevTex2D);
                }
            }

            if (bgOpacity > 0.01F) {
                drawRoundedCard3D(cardX, cardY, cardW, cardH, cornerRadius, bgArgb, borderArgb, 1.0F);
            }

            // 1.5px thin bottom health bar with optional absorption highlight.
            float barX = cardX + CARD_PAD_X;
            float barW = Math.max(8.0F, cardW - CARD_PAD_X * 2.0F);
            float barY = cardY + cardH - BAR_BOTTOM_PAD - BAR_HEIGHT;
            drawRect3D(barX, barY, barW, BAR_HEIGHT, trackArgb);
            float healthFillW = barW * healthRatio;
            if (healthFillW > 0.25F) {
                drawRect3D(barX, barY, healthFillW, BAR_HEIGHT, healthColor);
            }
            if (abs > 0.05F) {
                float absRatio = clamp01(abs / maxHp);
                float absW = barW * absRatio;
                if (healthFillW + absW <= barW) {
                    drawRect3D(barX + healthFillW, barY, absW, BAR_HEIGHT, absorbColor);
                } else {
                    drawRect3D(barX + barW - absW, barY, absW, BAR_HEIGHT, absorbColor);
                }
            }

            // Text row: distance (secondary) + name (primary) + HP (semantic health color).
            float cursorX = cardX + CARD_PAD_X;
            float nameY = cardY + CARD_PAD_Y;
            if (distStr != null) {
                float distY = nameY + (textH - metaFont.getHeight()) * 0.5F;
                if (textShadow) {
                    metaFont.drawStringWithShadow(distStr, cursorX, distY, textSecondary);
                } else {
                    metaFont.drawString(distStr, cursorX, distY, textSecondary);
                }
                cursorX += distW + ROW_GAP;
            }

            nameFont.drawStringWithEllipsis(nameStr, cursorX, nameY, MAX_NAME_WIDTH, textPrimary, textShadow);
            cursorX += nameW + ROW_GAP;

            if (hpStr != null) {
                if (textShadow) {
                    nameFont.drawStringWithShadow(hpStr, cursorX, nameY, healthColor);
                } else {
                    nameFont.drawString(hpStr, cursorX, nameY, healthColor);
                }
            }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private int collectEntityEquipment(EntityLivingBase entity) {
        int count = 0;
        ItemStack held = entity.getHeldItem();
        if (held != null && held.getItem() != null) {
            this.itemScratch[count++] = held;
        }
        // Slots 4..1 = helmet, chestplate, leggings, boots.
        for (int slot = 4; slot >= 1; slot--) {
            ItemStack armor = entity.getEquipmentInSlot(slot);
            if (armor != null && armor.getItem() != null) {
                this.itemScratch[count++] = armor;
            }
        }
        return count;
    }

    private void renderEquipmentRow(Minecraft mc, int itemCount, float cardY, int prevTex2D) {
        RenderItem renderItem = mc.getRenderItem();
        if (renderItem == null) {
            return;
        }
        float rowW = itemCount * ITEM_ICON_SIZE + (itemCount - 1) * (ITEM_STEP_X - ITEM_ICON_SIZE);
        float startX = -rowW * 0.5F;
        float rowY = cardY - ITEM_ROW_GAP - ITEM_ICON_SIZE;

        boolean wasRescaleNormal = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
        float prevZLevel = renderItem.zLevel;

        GlStateManager.matrixMode(GL11.GL_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        try {
            GlStateManager.enableTexture2D();
            GlStateManager.enableCull();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
            RenderHelper.enableGUIStandardItemLighting();

            // Cancel RenderItem's +100 (setupGuiTransform) and +50 (renderItemAndEffectIntoGUI) Z offset in 3D space.
            renderItem.zLevel = -150.0F;

            for (int i = 0; i < itemCount; i++) {
                ItemStack stack = this.itemScratch[i];
                this.itemScratch[i] = null;
                if (stack == null || stack.getItem() == null) {
                    continue;
                }
                // Re-assert texture/cull/depth flags in case a prior enchanted glint or custom renderer altered them.
                GlStateManager.enableTexture2D();
                GlStateManager.enableCull();
                GlStateManager.disableDepth();
                GlStateManager.depthMask(false);

                float itemX = startX + i * ITEM_STEP_X;
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translate(itemX, rowY, 0.0F);
                    GlStateManager.scale(ITEM_ICON_SCALE, ITEM_ICON_SCALE, ITEM_ICON_SCALE);
                    renderItem.renderItemAndEffectIntoGUI(stack, 0, 0);
                } finally {
                    GlStateManager.popMatrix();
                }
            }
        } finally {
            for (int i = 0; i < itemCount; i++) {
                this.itemScratch[i] = null;
            }
            renderItem.zLevel = prevZLevel;
            RenderHelper.disableStandardItemLighting();

            GlStateManager.matrixMode(GL11.GL_TEXTURE);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);

            if (wasRescaleNormal) {
                GlStateManager.enableRescaleNormal();
            } else {
                GlStateManager.disableRescaleNormal();
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(prevTex2D);
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private void drawRoundedCard3D(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int fillArgb,
        int borderArgb,
        float borderThickness
    ) {
        if (width <= 0.0F || height <= 0.0F) {
            return;
        }
        float maxR = Math.min(width, height) * 0.5F;
        float r = radius <= 0.0F ? 0.0F : (radius >= maxR ? maxR : radius);

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );

        int fillAlpha = (fillArgb >>> 24) & 0xFF;
        if (fillAlpha > 0) {
            int count = emitRoundedRing(x, y, width, height, r, 0.0F, this.outerRingX, this.outerRingY);
            drawTriangleFan3D(
                this.outerRingX,
                this.outerRingY,
                count,
                x + width * 0.5F,
                y + height * 0.5F,
                fillArgb
            );
        }

        int borderAlpha = (borderArgb >>> 24) & 0xFF;
        if (borderAlpha > 0 && borderThickness > 0.0F) {
            float safeBorder = Math.min(borderThickness, maxR);
            int outerCount = emitRoundedRing(x, y, width, height, r, 0.0F, this.outerRingX, this.outerRingY);
            emitRoundedRing(x, y, width, height, r, -safeBorder, this.innerRingX, this.innerRingY);
            drawQuadStripLoop3D(
                this.outerRingX,
                this.outerRingY,
                this.innerRingX,
                this.innerRingY,
                outerCount,
                borderArgb
            );
        }
    }

    private static int emitRoundedRing(
        float x,
        float y,
        float width,
        float height,
        float baseRadius,
        float offset,
        float[] outX,
        float[] outY
    ) {
        float rw = Math.max(0.0F, width + 2.0F * offset);
        float rh = Math.max(0.0F, height + 2.0F * offset);
        float rx = x + (width - rw) * 0.5F;
        float ry = y + (height - rh) * 0.5F;
        float maxR = Math.min(rw, rh) * 0.5F;
        float r = Math.max(0.0F, Math.min(maxR, baseRadius + offset));

        int idx = 0;
        idx = emitCorner(rx + r, ry + r, r, 0, outX, outY, idx);
        idx = emitCorner(rx + rw - r, ry + r, r, 1, outX, outY, idx);
        idx = emitCorner(rx + rw - r, ry + rh - r, r, 2, outX, outY, idx);
        idx = emitCorner(rx + r, ry + rh - r, r, 3, outX, outY, idx);
        return idx;
    }

    private static int emitCorner(
        float cx,
        float cy,
        float r,
        int quadrant,
        float[] outX,
        float[] outY,
        int startIdx
    ) {
        float cosX = QUAD_COS_X[quadrant];
        float sinX = QUAD_SIN_X[quadrant];
        float cosY = QUAD_COS_Y[quadrant];
        float sinY = QUAD_SIN_Y[quadrant];
        int idx = startIdx;
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            float c = CORNER_COS[i];
            float s = CORNER_SIN[i];
            outX[idx] = cx + r * (cosX * c + sinX * s);
            outY[idx] = cy + r * (cosY * c + sinY * s);
            idx++;
        }
        return idx;
    }

    private static void drawTriangleFan3D(
        float[] ringX,
        float[] ringY,
        int count,
        float centerX,
        float centerY,
        int argb
    ) {
        float a = ((argb >>> 24) & 0xFF) / 255.0F;
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(centerX, centerY, 0.0D).color(r, g, b, a).endVertex();
        for (int i = count - 1; i >= 0; i--) {
            wr.pos(ringX[i], ringY[i], 0.0D).color(r, g, b, a).endVertex();
        }
        wr.pos(ringX[count - 1], ringY[count - 1], 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    private static void drawQuadStripLoop3D(
        float[] outerX,
        float[] outerY,
        float[] innerX,
        float[] innerY,
        int count,
        int argb
    ) {
        float a = ((argb >>> 24) & 0xFF) / 255.0F;
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            wr.pos(outerX[i], outerY[i], 0.0D).color(r, g, b, a).endVertex();
            wr.pos(innerX[i], innerY[i], 0.0D).color(r, g, b, a).endVertex();
        }
        wr.pos(outerX[0], outerY[0], 0.0D).color(r, g, b, a).endVertex();
        wr.pos(innerX[0], innerY[0], 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    private static void drawRect3D(float x, float y, float width, float height, int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0 || width <= 0.0F || height <= 0.0F) {
            return;
        }
        float a = alpha / 255.0F;
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float x2 = x + width;
        float y2 = y + height;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x, y2, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x, y, 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    private boolean isEligibleEntity(EntityLivingBase entity, Minecraft mc) {
        if (entity == null || mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (entity == mc.thePlayer || entity == mc.getRenderViewEntity() || entity instanceof EntityArmorStand) {
            return false;
        }
        if (entity.isDead || !entity.isEntityAlive() || entity.getHealth() <= 0.0F || entity.deathTime > 0) {
            return false;
        }
        if (entity instanceof EntityPlayer) {
            if (!this.players.isEnabled()) {
                return false;
            }
        } else {
            if (!this.mobs.isEnabled()) {
                return false;
            }
        }
        if (!this.showInvisibles.isEnabled() && entity.isInvisible()) {
            return false;
        }
        if (this.filterBots.isEnabled() && isAntiCheatBot(entity, mc)) {
            return false;
        }
        return true;
    }

    private static boolean isAntiCheatBot(EntityLivingBase entity, Minecraft mc) {
        if (entity instanceof EntityArmorStand || entity.width <= 0.05F || entity.height <= 0.05F) {
            return true;
        }
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        if (entity.getUniqueID() == null) {
            return true;
        }
        String name = entity.getName();
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        if (!entity.isInvisible()) {
            return false;
        }
        NetHandlerPlayClient netHandler = mc.getNetHandler();
        return netHandler == null || netHandler.getPlayerInfo(entity.getUniqueID()) == null;
    }

    private String getCleanEntityName(EntityLivingBase entity) {
        String raw;
        if (entity instanceof EntityPlayer || entity.hasCustomName()) {
            raw = entity.getName();
        } else {
            Class<?> clazz = entity.getClass();
            raw = this.mobNameCache.get(clazz);
            if (raw == null) {
                raw = entity.getName();
                if (raw != null && !raw.isEmpty()) {
                    this.mobNameCache.put(clazz, raw);
                }
            }
        }
        if (raw == null || raw.isEmpty()) {
            return "Entity";
        }
        if (raw.indexOf('\u00a7') < 0) {
            return raw;
        }
        for (int i = 0; i < CLEAN_NAME_CACHE_SIZE; i++) {
            if (raw.equals(this.rawNameCache[i]) && this.cleanNameCache[i] != null) {
                return this.cleanNameCache[i];
            }
        }
        this.textScratch.setLength(0);
        int len = raw.length();
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7' && i + 1 < len) {
                i++;
                continue;
            }
            this.textScratch.append(c);
        }
        String clean = this.textScratch.length() > 0 ? this.textScratch.toString() : raw;
        int slot = this.cleanNameCursor;
        this.cleanNameCursor = (this.cleanNameCursor + 1) % CLEAN_NAME_CACHE_SIZE;
        this.rawNameCache[slot] = raw;
        this.cleanNameCache[slot] = clean;
        return clean;
    }

    private String formatDistance(int meters) {
        int clamped = Math.max(0, meters);
        if (clamped <= DIST_CACHE_LIMIT) {
            return DIST_LABELS[clamped];
        }
        this.textScratch.setLength(0);
        this.textScratch.append(clamped).append('m');
        return this.textScratch.toString();
    }

    private String formatHealth(float totalHp) {
        int tenths = Math.max(0, Math.round(totalHp * 10.0F));
        if (tenths <= HP_TENTHS_CACHE_LIMIT) {
            return HP_TENTHS_LABELS[tenths];
        }
        this.textScratch.setLength(0);
        this.textScratch.append(tenths / 10).append('.').append(tenths % 10);
        return this.textScratch.toString();
    }

    private static int resolveSemanticHealthColor(float ratio, ThemeData theme) {
        if (ratio > 0.55F) {
            return theme != null ? theme.success() : FALLBACK_SUCCESS;
        }
        if (ratio > 0.25F) {
            return theme != null ? theme.warning() : FALLBACK_WARNING;
        }
        return theme != null ? theme.danger() : FALLBACK_DANGER;
    }

    private void clearEntityBuffer() {
        for (int i = 0; i < MAX_ENTITIES; i++) {
            this.entityBuffer[i] = null;
        }
    }

    private static float clamp01(float value) {
        if (value <= 0.0F) {
            return 0.0F;
        }
        if (value >= 1.0F) {
            return 1.0F;
        }
        return value;
    }
}
