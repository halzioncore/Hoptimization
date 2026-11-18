package com.hoptimization.modules.visual.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

import java.util.List;

/**
 * World-space minimalist monochrome posterized photo and 1px cuboid line-art ESP renderer
 * with two-pass depth testing, Mixin-safe layer state guards, and zero hot-path allocations.
 */
public final class EspRenderer {
    public static final double MAX_RANGE_SQ = 128.0D * 128.0D;

    private static final float MODEL_SCALE = 0.0625F;
    private static final float PLAYER_PRE_SCALE = 0.9375F;
    private static final float PLAYER_BASE_OFFSET_Y = -1.5078125F;
    private static final float RAD_TO_DEG = 57.295776F;
    private static final float CUBOID_PAD = 0.04F;
    private static final float VISIBLE_LIGHT_MIX = 0.24F;
    private static final float OCCLUDED_LIGHT_MIX = 0.30F;
    private static final float VISIBLE_LINE_ALPHA = 0.86F;
    private static final float OCCLUDED_LINE_ALPHA = 0.42F;

    private static final int PART_HEAD = 0;
    private static final int PART_TORSO = 1;
    private static final int PART_RIGHT_ARM = 2;
    private static final int PART_LEFT_ARM = 3;
    private static final int PART_RIGHT_LEG = 4;
    private static final int PART_LEFT_LEG = 5;
    private static final int PART_COUNT = 6;

    private static final int MAX_TARGETS = 256;

    // Static pre-allocated ClippingHelperImpl avoids GL calls during <clinit>; init() runs inside render().
    private static final ClippingHelperImpl CLIPPING_HELPER = new ClippingHelperImpl();
    private static final Frustum FRUSTUM = new Frustum(CLIPPING_HELPER);
    private static final ModelBiped POSE_MODEL = new ModelBiped(0.0F);
    private static final TargetEntry[] TARGETS = new TargetEntry[MAX_TARGETS];

    private static boolean internalRenderPass = false;
    private static boolean activeColorPass = false;
    private static boolean activeDepthMask = true;
    private static int activeDepthFunc = GL11.GL_LEQUAL;

    static {
        for (int i = 0; i < MAX_TARGETS; i++) {
            TARGETS[i] = new TargetEntry();
        }
    }

    private EspRenderer() {
    }

    /**
     * Returns whether the renderer is currently inside an internal ESP entity pass.
     *
     * @return {@code true} while rendering an ESP target entity pass
     */
    public static boolean isInternalRenderPass() {
        return internalRenderPass;
    }

    /**
     * Re-applies active depth, blend, and alpha state right before entity layers render so
     * {@code RendererLivingEntity.doRender}'s unconditional {@code depthMask(true)} cannot
     * overwrite world wall depth during the occluded pass.
     */
    public static void onBeforeRenderLayers() {
        if (!internalRenderPass) {
            return;
        }
        restorePassGlState();
    }

    /**
     * Restores active blend, depth mask, and depth func after {@code RendererLivingEntity.renderLayers}
     * returns so layers such as {@code LayerArrow} or {@code LayerCape} cannot break pass state.
     */
    public static void onAfterRenderLayers() {
        if (!internalRenderPass) {
            return;
        }
        restorePassGlState();
    }

    /**
     * Renders all eligible players as restrained monochrome photo passes and optional 1px cuboid line-art.
     *
     * @param policy        module target eligibility policy
     * @param baseColorArgb monochrome base tint in ARGB format
     * @param throughWalls  {@code true} to render the muted occluded pass behind terrain/walls
     * @param fillOpacity   monochrome photo fill opacity in {@code [0, 1]}
     * @param lineArt       {@code true} to draw 1px cuboid technical line-art around targets
     * @param lineWidth     line-art stroke width in pixels
     * @param posterize     {@code true} to quantize luminance into 4 calm tonal blocks
     * @param partialTicks  current render partial ticks
     */
    public static void render(
        EspTargetPolicy policy,
        int baseColorArgb,
        boolean throughWalls,
        float fillOpacity,
        boolean lineArt,
        float lineWidth,
        boolean posterize,
        float partialTicks
    ) {
        if (policy == null) {
            return;
        }
        float clampedFill = clamp01(fillOpacity);
        boolean drawFill = clampedFill > 0.01F;
        if (!drawFill && !lineArt) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.entityRenderer == null) {
            return;
        }
        RenderManager renderManager = mc.getRenderManager();
        if (renderManager == null) {
            return;
        }
        List<EntityPlayer> players = mc.theWorld.playerEntities;
        if (players == null || players.isEmpty()) {
            return;
        }

        Entity viewEntity = mc.getRenderViewEntity();
        double camX;
        double camY;
        double camZ;
        if (viewEntity != null) {
            camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * (double) partialTicks;
            camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * (double) partialTicks;
            camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * (double) partialTicks;
        } else {
            camX = renderManager.viewerPosX;
            camY = renderManager.viewerPosY;
            camZ = renderManager.viewerPosZ;
        }

        CLIPPING_HELPER.init();
        FRUSTUM.setPosition(camX, camY, camZ);

        int targetCount = collectTargets(policy, mc, players, lineArt, partialTicks, camX, camY, camZ);
        if (targetCount == 0) {
            return;
        }

        float safeLineWidth = sanitizeLineWidth(lineWidth);

        ContextCapabilities caps = GLContext.getCapabilities();
        boolean hasGl14 = caps != null && caps.OpenGL14;
        boolean hasGl13 = caps != null && (caps.OpenGL13 || caps.GL_ARB_multitexture);
        boolean prevRenderShadow = renderManager.isRenderShadow();
        float prevBrightX = OpenGlHelper.lastBrightnessX;
        float prevBrightY = OpenGlHelper.lastBrightnessY;

        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int prevBlendSrcRgb = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
            : GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDstRgb = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
            : GL11.glGetInteger(GL11.GL_BLEND_DST);
        int prevBlendSrcAlpha = hasGl14 ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA) : prevBlendSrcRgb;
        int prevBlendDstAlpha = hasGl14 ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA) : prevBlendDstRgb;

        int prevActiveTex = hasGl13 ? GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) : OpenGlHelper.defaultTexUnit;
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int prevCullMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        boolean wasFog = GL11.glIsEnabled(GL11.GL_FOG);
        boolean wasLineSmooth = GL11.glIsEnabled(GL11.GL_LINE_SMOOTH);
        int prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        float prevLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);

        try {
            renderManager.setRenderShadow(false);
            internalRenderPass = true;

            // Pass 1 (occluded, GL_GREATER) must run before Pass 2 depth writes so wall depth stays intact.
            if (throughWalls) {
                if (drawFill) {
                    float occludedAlpha = Math.min(clampedFill * 0.40F, 0.36F);
                    renderEntityFillPass(
                        mc, renderManager, targetCount, true, true,
                        baseColorArgb, occludedAlpha, posterize, true, OCCLUDED_LIGHT_MIX,
                        GL11.GL_GREATER, partialTicks
                    );
                }
                if (lineArt) {
                    renderCuboidLinePass(mc, targetCount, baseColorArgb, true, safeLineWidth, GL11.GL_GREATER);
                }
            }

            // Pass 2 (visible, GL_LEQUAL / GL_EQUAL).
            if (drawFill) {
                boolean hasOpaque = false;
                boolean hasInvisible = false;
                for (int i = 0; i < targetCount; i++) {
                    if (TARGETS[i].invisible) {
                        hasInvisible = true;
                    } else {
                        hasOpaque = true;
                    }
                }

                if (hasOpaque) {
                    renderDepthPrepass(mc, renderManager, targetCount, partialTicks);
                    renderEntityFillPass(
                        mc, renderManager, targetCount, false, false,
                        baseColorArgb, clampedFill, posterize, false, VISIBLE_LIGHT_MIX,
                        GL11.GL_EQUAL, partialTicks
                    );
                }
                if (hasInvisible) {
                    renderEntityFillPass(
                        mc, renderManager, targetCount, false, true,
                        baseColorArgb, clampedFill, posterize, false, VISIBLE_LIGHT_MIX,
                        GL11.GL_LEQUAL, partialTicks
                    );
                }
            }

            if (lineArt) {
                renderCuboidLinePass(mc, targetCount, baseColorArgb, false, safeLineWidth, GL11.GL_LEQUAL);
            }
        } finally {
            for (int i = 0; i < targetCount; i++) {
                TARGETS[i].player = null;
            }
            internalRenderPass = false;
            activeColorPass = false;
            activeDepthMask = true;
            activeDepthFunc = GL11.GL_LEQUAL;
            EspSketchShader.end();
            renderManager.setRenderShadow(prevRenderShadow);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevBrightX, prevBrightY);
            RenderHelper.disableStandardItemLighting();
            mc.entityRenderer.disableLightmap();
            GL11.glLineWidth(prevLineWidth);
            if (wasLineSmooth) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
            } else {
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
            }
            GlStateManager.shadeModel(prevShadeModel);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.cullFace(prevCullMode);
            if (wasCull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (wasDepth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            GlStateManager.tryBlendFuncSeparate(
                prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha
            );
            if (wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (wasLighting) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
            if (wasAlpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            if (wasFog) {
                GlStateManager.enableFog();
            } else {
                GlStateManager.disableFog();
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if (wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (hasGl13) {
                GlStateManager.setActiveTexture(prevActiveTex);
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * Resets transient renderer and shader pass state on module disable or world unload.
     */
    public static void resetState() {
        internalRenderPass = false;
        activeColorPass = false;
        activeDepthMask = true;
        activeDepthFunc = GL11.GL_LEQUAL;
        for (int i = 0; i < MAX_TARGETS; i++) {
            TARGETS[i].player = null;
        }
        EspSketchShader.end();
    }

    private static void restorePassGlState() {
        if (activeColorPass) {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003F);
        } else {
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        }
        GlStateManager.depthMask(activeDepthMask);
        GlStateManager.depthFunc(activeDepthFunc);
    }

    private static void renderDepthPrepass(
        Minecraft mc,
        RenderManager renderManager,
        int targetCount,
        float partialTicks
    ) {
        activeColorPass = false;
        activeDepthMask = true;
        activeDepthFunc = GL11.GL_LEQUAL;

        mc.entityRenderer.enableLightmap();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        for (int i = 0; i < targetCount; i++) {
            TargetEntry entry = TARGETS[i];
            if (entry.invisible || entry.player == null) {
                continue;
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            GlStateManager.colorMask(false, false, false, false);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.disableLighting();
            GlStateManager.disableFog();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            renderManager.doRenderEntity(
                entry.player, entry.dx, entry.dy, entry.dz, entry.yaw, partialTicks, true
            );
        }
    }

    private static void renderEntityFillPass(
        Minecraft mc,
        RenderManager renderManager,
        int targetCount,
        boolean includeOpaque,
        boolean includeInvisible,
        int baseColorArgb,
        float opacity,
        boolean posterize,
        boolean isOccluded,
        float lightMix,
        int depthFunc,
        float partialTicks
    ) {
        activeColorPass = true;
        activeDepthMask = false;
        activeDepthFunc = depthFunc;

        mc.entityRenderer.enableLightmap();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        for (int i = 0; i < targetCount; i++) {
            TargetEntry entry = TARGETS[i];
            if (entry.player == null) {
                continue;
            }
            if (entry.invisible && !includeInvisible) {
                continue;
            }
            if (!entry.invisible && !includeOpaque) {
                continue;
            }

            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.depthFunc(depthFunc);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003F);
            GlStateManager.disableLighting();
            GlStateManager.disableFog();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            EspSketchShader.begin(baseColorArgb, opacity, posterize, isOccluded, lightMix);
            try {
                renderManager.doRenderEntity(
                    entry.player, entry.dx, entry.dy, entry.dz, entry.yaw, partialTicks, true
                );
            } finally {
                EspSketchShader.end();
            }
        }
    }

    private static void renderCuboidLinePass(
        Minecraft mc,
        int targetCount,
        int baseColorArgb,
        boolean occluded,
        float lineWidth,
        int depthFunc
    ) {
        mc.entityRenderer.disableLightmap();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableFog();
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.depthFunc(depthFunc);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
        GL11.glLineWidth(lineWidth);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        float baseR = ((baseColorArgb >>> 16) & 0xFF) / 255.0F;
        float baseG = ((baseColorArgb >>> 8) & 0xFF) / 255.0F;
        float baseB = (baseColorArgb & 0xFF) / 255.0F;
        float baseLuma = 0.299F * baseR + 0.587F * baseG + 0.114F * baseB;

        // Keep white/bright tints readable with #25272C dark graphite ink; lift darker tints toward #ECEDEE.
        float lineR;
        float lineG;
        float lineB;
        if (baseLuma > 0.72F) {
            lineR = baseR + (0.1451F - baseR) * 0.38F;
            lineG = baseG + (0.1529F - baseG) * 0.38F;
            lineB = baseB + (0.1725F - baseB) * 0.38F;
        } else {
            lineR = baseR + (0.9255F - baseR) * 0.44F;
            lineG = baseG + (0.9294F - baseG) * 0.44F;
            lineB = baseB + (0.9333F - baseB) * 0.44F;
        }

        if (occluded) {
            float mutedR = baseR * 0.45F + 0.08F;
            float mutedG = baseG * 0.45F + 0.08F;
            float mutedB = baseB * 0.45F + 0.08F;
            lineR = lineR + (mutedR - lineR) * 0.38F;
            lineG = lineG + (mutedG - lineG) * 0.38F;
            lineB = lineB + (mutedB - lineB) * 0.38F;
        }

        float lineAlpha = occluded ? OCCLUDED_LINE_ALPHA : VISIBLE_LINE_ALPHA;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();

        for (int i = 0; i < targetCount; i++) {
            TargetEntry entry = TARGETS[i];
            if (entry.sleeping || entry.player == null) {
                continue;
            }
            drawTargetCuboids(tess, wr, entry, lineR, lineG, lineB, lineAlpha);
        }
    }

    private static void drawTargetCuboids(
        Tessellator tess,
        WorldRenderer wr,
        TargetEntry entry,
        float r,
        float g,
        float b,
        float alpha
    ) {
        pushPlayerBaseTransform(entry);
        for (int p = 0; p < PART_COUNT; p++) {
            pushPartTransform(entry, p);
            float inf = entry.partInflate[p];
            float minX;
            float minY;
            float minZ;
            float maxX;
            float maxY;
            float maxZ;
            switch (p) {
                case PART_HEAD:
                    minX = -4.0F - inf;
                    minY = -8.0F - inf;
                    minZ = -4.0F - inf;
                    maxX = 4.0F + inf;
                    maxY = 0.0F + inf;
                    maxZ = 4.0F + inf;
                    break;
                case PART_TORSO:
                    minX = -4.0F - inf;
                    minY = 0.0F - inf;
                    minZ = -2.0F - inf;
                    maxX = 4.0F + inf;
                    maxY = 12.0F + inf;
                    maxZ = 2.0F + inf;
                    break;
                case PART_RIGHT_ARM:
                    minX = (entry.slimArms ? -2.0F : -3.0F) - inf;
                    minY = -2.0F - inf;
                    minZ = -2.0F - inf;
                    maxX = 1.0F + inf;
                    maxY = 10.0F + inf;
                    maxZ = 2.0F + inf;
                    break;
                case PART_LEFT_ARM:
                    minX = -1.0F - inf;
                    minY = -2.0F - inf;
                    minZ = -2.0F - inf;
                    maxX = (entry.slimArms ? 2.0F : 3.0F) + inf;
                    maxY = 10.0F + inf;
                    maxZ = 2.0F + inf;
                    break;
                case PART_RIGHT_LEG:
                case PART_LEFT_LEG:
                default:
                    minX = -2.0F - inf;
                    minY = 0.0F - inf;
                    minZ = -2.0F - inf;
                    maxX = 2.0F + inf;
                    maxY = 12.0F + inf;
                    maxZ = 2.0F + inf;
                    break;
            }
            drawCuboidWireframe(tess, wr, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, alpha);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    private static void drawCuboidWireframe(
        Tessellator tess,
        WorldRenderer wr,
        float x0,
        float y0,
        float z0,
        float x1,
        float y1,
        float z1,
        float r,
        float g,
        float b,
        float a
    ) {
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        // Top rectangle (y0)
        wr.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, z0).color(r, g, b, a).endVertex();

        // Bottom rectangle (y1)
        wr.pos(x0, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z0).color(r, g, b, a).endVertex();

        // Vertical edges
        wr.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z1).color(r, g, b, a).endVertex();
        tess.draw();
    }

    private static void pushPlayerBaseTransform(TargetEntry entry) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(entry.dx, entry.modelDy, entry.dz);
        GlStateManager.rotate(180.0F - entry.bodyYaw, 0.0F, 1.0F, 0.0F);
        if (entry.deathTiltDeg > 0.0F) {
            GlStateManager.rotate(entry.deathTiltDeg, 0.0F, 0.0F, 1.0F);
        } else if (entry.flipped) {
            GlStateManager.translate(0.0F, entry.entityHeight + 0.1F, 0.0F);
            GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        }
        GlStateManager.scale(-1.0F, -1.0F, 1.0F);
        GlStateManager.scale(PLAYER_PRE_SCALE, PLAYER_PRE_SCALE, PLAYER_PRE_SCALE);
        GlStateManager.translate(0.0F, PLAYER_BASE_OFFSET_Y, 0.0F);
        if (entry.sneak) {
            GlStateManager.translate(0.0F, 0.2F, 0.0F);
        }
    }

    private static void pushPartTransform(TargetEntry entry, int partIdx) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(
            entry.rotPtX[partIdx] * MODEL_SCALE,
            entry.rotPtY[partIdx] * MODEL_SCALE,
            entry.rotPtZ[partIdx] * MODEL_SCALE
        );
        float rz = entry.rotZ[partIdx];
        if (rz != 0.0F) {
            GlStateManager.rotate(rz * RAD_TO_DEG, 0.0F, 0.0F, 1.0F);
        }
        float ry = entry.rotY[partIdx];
        if (ry != 0.0F) {
            GlStateManager.rotate(ry * RAD_TO_DEG, 0.0F, 1.0F, 0.0F);
        }
        float rx = entry.rotX[partIdx];
        if (rx != 0.0F) {
            GlStateManager.rotate(rx * RAD_TO_DEG, 1.0F, 0.0F, 0.0F);
        }
        GlStateManager.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
    }

    private static int collectTargets(
        EspTargetPolicy policy,
        Minecraft mc,
        List<EntityPlayer> players,
        boolean solvePose,
        float partialTicks,
        double camX,
        double camY,
        double camZ
    ) {
        int count = 0;
        int listSize = players.size();
        for (int i = 0; i < listSize && count < MAX_TARGETS; i++) {
            EntityPlayer player = players.get(i);
            if (player == null || !policy.isTargetEligible(player, mc)) {
                continue;
            }

            double lx = player.ticksExisted == 0 ? player.posX : player.lastTickPosX;
            double ly = player.ticksExisted == 0 ? player.posY : player.lastTickPosY;
            double lz = player.ticksExisted == 0 ? player.posZ : player.lastTickPosZ;
            double interpX = lx + (player.posX - lx) * (double) partialTicks;
            double interpY = ly + (player.posY - ly) * (double) partialTicks;
            double interpZ = lz + (player.posZ - lz) * (double) partialTicks;
            double dx = interpX - camX;
            double dy = interpY - camY;
            double dz = interpZ - camZ;
            if (dx * dx + dy * dy + dz * dz > MAX_RANGE_SQ) {
                continue;
            }

            if (!player.ignoreFrustumCheck && !FRUSTUM.isBoxInFrustum(
                interpX - 0.65D, interpY - 0.15D, interpZ - 0.65D,
                interpX + 0.65D, interpY + 2.15D, interpZ + 0.65D
            )) {
                continue;
            }

            TargetEntry entry = TARGETS[count];
            entry.player = player;
            entry.dx = dx;
            entry.dy = dy;
            entry.dz = dz;
            entry.yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
            entry.invisible = player.isInvisible();
            entry.sleeping = player.isEntityAlive() && player.isPlayerSleeping();
            if (solvePose && !entry.sleeping) {
                populateCuboidPose(entry, mc, player, partialTicks, dy);
            }
            count++;
        }
        return count;
    }

    private static void populateCuboidPose(
        TargetEntry entry,
        Minecraft mc,
        EntityPlayer player,
        float partialTicks,
        double dy
    ) {
        double modelDy = dy;
        if (player.isSneaking() && !(player instanceof EntityPlayerSP)) {
            modelDy -= 0.125D;
        }
        entry.modelDy = modelDy;

        float bodyYaw = interpolateRotation(player.prevRenderYawOffset, player.renderYawOffset, partialTicks);
        float headYaw = interpolateRotation(player.prevRotationYawHead, player.rotationYawHead, partialTicks);
        float netHeadYaw = headYaw - bodyYaw;
        boolean riding = player.isRiding() && player.ridingEntity != null && player.ridingEntity.shouldRiderSit();
        if (riding && player.ridingEntity instanceof EntityLivingBase) {
            EntityLivingBase mount = (EntityLivingBase) player.ridingEntity;
            bodyYaw = interpolateRotation(mount.prevRenderYawOffset, mount.renderYawOffset, partialTicks);
            netHeadYaw = headYaw - bodyYaw;
            float wrapped = MathHelper.wrapAngleTo180_float(netHeadYaw);
            if (wrapped < -85.0F) {
                wrapped = -85.0F;
            }
            if (wrapped >= 85.0F) {
                wrapped = 85.0F;
            }
            bodyYaw = headYaw - wrapped;
            if (wrapped * wrapped > 2500.0F) {
                bodyYaw += wrapped * 0.2F;
            }
        }

        float headPitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float ageInTicks = (float) player.ticksExisted + partialTicks;
        float limbSwingAmount = player.prevLimbSwingAmount
            + (player.limbSwingAmount - player.prevLimbSwingAmount) * partialTicks;
        float limbSwing = player.limbSwing - player.limbSwingAmount * (1.0F - partialTicks);
        boolean isChild = player.isChild();
        if (isChild) {
            limbSwing *= 3.0F;
        }
        if (limbSwingAmount > 1.0F) {
            limbSwingAmount = 1.0F;
        }

        entry.bodyYaw = bodyYaw;
        entry.deathTiltDeg = 0.0F;
        entry.flipped = false;
        if (player.deathTime > 0) {
            float f = ((float) player.deathTime + partialTicks - 1.0F) / 20.0F * 1.6F;
            f = MathHelper.sqrt_float(f);
            if (f > 1.0F) {
                f = 1.0F;
            }
            entry.deathTiltDeg = f * 90.0F;
        } else if (isDinnerboneOrGrumm(player.getName()) && player.isWearing(EnumPlayerModelParts.CAPE)) {
            entry.flipped = true;
        }
        entry.entityHeight = player.height;

        boolean slimArms = false;
        if (player instanceof AbstractClientPlayer) {
            if (mc.getNetHandler() != null) {
                slimArms = "slim".equals(((AbstractClientPlayer) player).getSkinType());
            } else {
                slimArms = "slim".equals(DefaultPlayerSkin.getSkinType(player.getUniqueID()));
            }
        }
        entry.slimArms = slimArms;
        entry.sneak = player.isSneaking();

        POSE_MODEL.swingProgress = player.getSwingProgress(partialTicks);
        POSE_MODEL.isRiding = riding;
        POSE_MODEL.isChild = isChild;
        POSE_MODEL.isSneak = entry.sneak;
        POSE_MODEL.heldItemLeft = 0;
        POSE_MODEL.aimedBow = false;
        ItemStack held = player.inventory != null ? player.inventory.getCurrentItem() : null;
        if (held == null) {
            POSE_MODEL.heldItemRight = 0;
        } else {
            POSE_MODEL.heldItemRight = 1;
            if (player.getItemInUseCount() > 0) {
                EnumAction action = held.getItemUseAction();
                if (action == EnumAction.BLOCK) {
                    POSE_MODEL.heldItemRight = 3;
                } else if (action == EnumAction.BOW) {
                    POSE_MODEL.aimedBow = true;
                }
            }
        }

        float armPointY = slimArms ? 2.5F : 2.0F;
        POSE_MODEL.bipedRightArm.rotationPointY = armPointY;
        POSE_MODEL.bipedLeftArm.rotationPointY = armPointY;
        POSE_MODEL.setLivingAnimations(player, limbSwing, limbSwingAmount, partialTicks);
        POSE_MODEL.setRotationAngles(
            limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, MODEL_SCALE, player
        );

        entry.capturePart(PART_HEAD, POSE_MODEL.bipedHead);
        entry.capturePart(PART_TORSO, POSE_MODEL.bipedBody);
        entry.capturePart(PART_RIGHT_ARM, POSE_MODEL.bipedRightArm);
        entry.capturePart(PART_LEFT_ARM, POSE_MODEL.bipedLeftArm);
        entry.capturePart(PART_RIGHT_LEG, POSE_MODEL.bipedRightLeg);
        entry.capturePart(PART_LEFT_LEG, POSE_MODEL.bipedLeftLeg);

        boolean hasHelmet = hasArmorItem(player, 3);
        boolean hasChestplate = hasArmorItem(player, 2);
        boolean hasLeggings = hasArmorItem(player, 1);

        entry.partInflate[PART_HEAD] = (hasHelmet
            ? 1.0F
            : (player.isWearing(EnumPlayerModelParts.HAT) ? 0.5F : 0.0F)) + CUBOID_PAD;
        entry.partInflate[PART_TORSO] = (hasChestplate
            ? 1.0F
            : (player.isWearing(EnumPlayerModelParts.JACKET) ? 0.25F : 0.0F)) + CUBOID_PAD;
        entry.partInflate[PART_RIGHT_ARM] = (player.isWearing(EnumPlayerModelParts.RIGHT_SLEEVE)
            ? 0.25F : 0.0F) + CUBOID_PAD;
        entry.partInflate[PART_LEFT_ARM] = (player.isWearing(EnumPlayerModelParts.LEFT_SLEEVE)
            ? 0.25F : 0.0F) + CUBOID_PAD;
        entry.partInflate[PART_RIGHT_LEG] = (hasLeggings
            ? 0.5F
            : (player.isWearing(EnumPlayerModelParts.RIGHT_PANTS_LEG) ? 0.25F : 0.0F)) + CUBOID_PAD;
        entry.partInflate[PART_LEFT_LEG] = (hasLeggings
            ? 0.5F
            : (player.isWearing(EnumPlayerModelParts.LEFT_PANTS_LEG) ? 0.25F : 0.0F)) + CUBOID_PAD;
    }

    private static boolean hasArmorItem(EntityPlayer player, int armorSlot) {
        ItemStack stack = player.getCurrentArmor(armorSlot);
        return stack != null && stack.getItem() instanceof ItemArmor;
    }

    private static boolean isDinnerboneOrGrumm(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return false;
        }
        return matchesUnformatted(rawName, "Dinnerbone") || matchesUnformatted(rawName, "Grumm");
    }

    private static boolean matchesUnformatted(String raw, String target) {
        int rLen = raw.length();
        int tLen = target.length();
        int ti = 0;
        for (int ri = 0; ri < rLen; ri++) {
            char c = raw.charAt(ri);
            if (c == '\u00a7') {
                ri++;
                continue;
            }
            if (ti >= tLen || c != target.charAt(ti)) {
                return false;
            }
            ti++;
        }
        return ti == tLen;
    }

    private static float interpolateRotation(float prevAngle, float nextAngle, float partialTicks) {
        float diff = nextAngle - prevAngle;
        while (diff < -180.0F) {
            diff += 360.0F;
        }
        while (diff >= 180.0F) {
            diff -= 360.0F;
        }
        return prevAngle + partialTicks * diff;
    }

    private static float sanitizeLineWidth(float lineWidth) {
        if (Float.isNaN(lineWidth) || Float.isInfinite(lineWidth) || lineWidth < 0.5F) {
            return 1.0F;
        }
        if (lineWidth > 8.0F) {
            return 8.0F;
        }
        return lineWidth;
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

    /**
     * Pre-allocated per-frame target transform and solved limb pose cache.
     */
    private static final class TargetEntry {
        EntityPlayer player;
        double dx;
        double dy;
        double dz;
        double modelDy;
        float yaw;
        float bodyYaw;
        float deathTiltDeg;
        float entityHeight;
        boolean invisible;
        boolean sleeping;
        boolean flipped;
        boolean slimArms;
        boolean sneak;

        final float[] rotPtX = new float[PART_COUNT];
        final float[] rotPtY = new float[PART_COUNT];
        final float[] rotPtZ = new float[PART_COUNT];
        final float[] rotX = new float[PART_COUNT];
        final float[] rotY = new float[PART_COUNT];
        final float[] rotZ = new float[PART_COUNT];
        final float[] partInflate = new float[PART_COUNT];

        void capturePart(int index, ModelRenderer part) {
            this.rotPtX[index] = part.rotationPointX;
            this.rotPtY[index] = part.rotationPointY;
            this.rotPtZ[index] = part.rotationPointZ;
            this.rotX[index] = part.rotateAngleX;
            this.rotY[index] = part.rotateAngleY;
            this.rotZ[index] = part.rotateAngleZ;
        }
    }
}
