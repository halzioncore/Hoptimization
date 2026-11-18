package com.hoptimization.modules.visual.esp;

import com.hoptimization.modules.visual.Esp;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

import java.util.List;

/**
 * Renders restrained, structurally recognizable 3D ESP geometry for entities, chests, and beds
 * with zero hot-path heap allocation and strict OpenGL state restoration.
 */
public final class EspRenderer {
    private static final int DEFAULT_PLAYER_COLOR = 0xFF5B8CFF;
    private static final int TEAMMATE_COLOR = 0xFF36D399;
    private static final int HOSTILE_MOB_COLOR = 0xFFF26D78;
    private static final int PASSIVE_MOB_COLOR = 0xFF56C8E8;

    private static final int CHEST_NORMAL_COLOR = 0xFFE5A93C;
    private static final int CHEST_TRAPPED_COLOR = 0xFFF97316;
    private static final int CHEST_ENDER_COLOR = 0xFFA855F7;
    private static final int CHEST_LATCH_COLOR = 0xFFF1F5F9;

    private static final int BED_FRAME_COLOR = 0xFFD64556;
    private static final int BED_MATTRESS_COLOR = 0xFFE85468;
    private static final int BED_QUILT_SEAM_COLOR = 0xFFF5EFE2;
    private static final int BED_PILLOW_COLOR = 0xFFFAF3E3;

    private static final int HEALTH_HIGH_COLOR = 0xFF36D399;
    private static final int HEALTH_MID_COLOR = 0xFFF5B544;
    private static final int HEALTH_LOW_COLOR = 0xFFF2555A;
    private static final int HEALTH_TRACK_COLOR = 0xFF101318;

    private static final int OUTLINE_ALPHA = 230;
    private static final int ACCENT_OUTLINE_ALPHA = 242;
    private static final int HEALTH_TRACK_ALPHA = 168;
    private static final int HEALTH_BAR_ALPHA = 242;

    private static final float CORNER_ARM_RATIO = 0.26F;
    private static final float HEALTH_BAR_WIDTH_PX = 1.5F;
    private static final float HEALTH_TRACK_WIDTH_PX = 2.5F;

    private static final double ENTITY_BOX_EXPAND = 0.002D;
    private static final double BLOCK_EPS = 0.0025D;
    private static final double CHEST_INSET = 1.0D / 16.0D;
    private static final double CHEST_HEIGHT = 14.0D / 16.0D;
    private static final double CHEST_LID_SEAM_Y = 9.0D / 16.0D;
    private static final double CHEST_LATCH_MIN_Y = 7.0D / 16.0D;
    private static final double CHEST_LATCH_MAX_Y = 11.0D / 16.0D;
    private static final double CHEST_LATCH_HALF_W = 1.0D / 16.0D;
    private static final double CHEST_LATCH_DEPTH = 1.0D / 16.0D;

    private static final double BED_SIDE_INSET = 0.03D;
    private static final double BED_LEG_SIZE = 0.14D;
    private static final double BED_LEG_HEIGHT = 3.0D / 16.0D;
    private static final double BED_MATTRESS_HEIGHT = 9.0D / 16.0D;
    private static final double BED_PILLOW_TOP_Y = 0.635D;
    private static final double BED_PILLOW_INSET = 0.12D;
    private static final double BED_PILLOW_DEPTH_PAIRED = 0.40D;
    private static final double BED_PILLOW_DEPTH_SINGLE = 0.30D;
    private static final double BED_QUILT_LINE_OFFSET_PAIRED = 0.66D;
    private static final double BED_QUILT_LINE_OFFSET_SINGLE = 0.56D;

    private static final int BATCH_VERTEX_FLUSH_THRESHOLD = 32768;

    private EspRenderer() {
    }

    /**
     * Renders all enabled ESP targets for the current world render frame.
     *
     * @param module       active ESP module configuration
     * @param bedCache     cached bed positions and orientations
     * @param partialTicks current render partial ticks
     */
    public static void render(Esp module, BedStructureCache bedCache, float partialTicks) {
        if (module == null) {
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

        boolean drawPlayers = module.getPlayersSetting().isEnabled();
        boolean drawMobs = module.getMobsSetting().isEnabled();
        boolean drawBeds = module.getBedsSetting().isEnabled() && bedCache != null && bedCache.getBedCount() > 0;
        boolean drawChests = module.getChestsSetting().isEnabled();
        if (!drawPlayers && !drawMobs && !drawBeds && !drawChests) {
            return;
        }

        boolean cornerBrackets = module.getEntityStyleSetting().is(Esp.STYLE_CORNER_BRACKET);
        float lineWidth = module.getLineWidthSetting().getFloatValue();
        float fillOpacity = clamp01(module.getFillOpacitySetting().getFloatValue());
        boolean throughWalls = module.getThroughWallsSetting().isEnabled();
        boolean showHealthBar = module.getShowHealthBarSetting().isEnabled();
        double blockRange = module.getBlockRangeSetting().getDoubleValue();
        double blockRangeSq = blockRange * blockRange;
        boolean followTheme = module.getFollowThemeSetting().isEnabled();
        int playerAccentArgb = followTheme ? ThemeManager.current().accent() : DEFAULT_PLAYER_COLOR;

        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null) {
            viewEntity = mc.thePlayer;
        }
        double camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * (double) partialTicks;
        double camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * (double) partialTicks;
        double camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * (double) partialTicks;

        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasLineSmooth = GL11.glIsEnabled(GL11.GL_LINE_SMOOTH);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        float prevLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        int prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        ContextCapabilities caps = GLContext.getCapabilities();
        int prevBlendSrcAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
            : prevBlendSrc;
        int prevBlendDstAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
            : prevBlendDst;

        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            syncTexture2D(false);
            syncLighting(false);
            syncAlphaTest(false);
            syncBlend(true);
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.depthMask(false);
            syncCullFace(true);
            if (throughWalls) {
                syncDepthTest(false);
            } else {
                syncDepthTest(true);
                GlStateManager.depthFunc(GL11.GL_LEQUAL);
            }
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
            GlStateManager.resetColor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            World world = mc.theWorld;
            EntityPlayer localPlayer = mc.thePlayer;

            if (fillOpacity > 0.002F) {
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                if (drawPlayers || drawMobs) {
                    emitEntityPass(
                        tessellator, wr, world, localPlayer, mc, module,
                        playerAccentArgb, cornerBrackets, fillOpacity, partialTicks,
                        camX, camY, camZ, true
                    );
                }
                if (drawChests) {
                    emitChestPass(
                        tessellator, wr, world, fillOpacity, blockRangeSq,
                        camX, camY, camZ, true
                    );
                }
                if (drawBeds) {
                    emitBedPass(
                        tessellator, wr, bedCache, fillOpacity, blockRangeSq,
                        camX, camY, camZ, true
                    );
                }
                tessellator.draw();
            }

            GL11.glLineWidth(lineWidth);
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            if (drawPlayers || drawMobs) {
                emitEntityPass(
                    tessellator, wr, world, localPlayer, mc, module,
                    playerAccentArgb, cornerBrackets, fillOpacity, partialTicks,
                    camX, camY, camZ, false
                );
            }
            if (drawChests) {
                emitChestPass(
                    tessellator, wr, world, fillOpacity, blockRangeSq,
                    camX, camY, camZ, false
                );
            }
            if (drawBeds) {
                emitBedPass(
                    tessellator, wr, bedCache, fillOpacity, blockRangeSq,
                    camX, camY, camZ, false
                );
            }
            tessellator.draw();

            if (showHealthBar && (drawPlayers || drawMobs)) {
                renderEntityHealthBars(
                    tessellator, wr, world, localPlayer, mc, module, renderManager,
                    partialTicks, camX, camY, camZ
                );
            }
        } finally {
            GL11.glLineWidth(prevLineWidth);
            if (wasLineSmooth) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
            } else {
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
            }
            GlStateManager.depthMask(prevDepthMask);
            GlStateManager.depthFunc(prevDepthFunc);
            syncDepthTest(wasDepth);
            syncCullFace(wasCull);
            syncLighting(wasLighting);
            syncAlphaTest(wasAlpha);
            syncTexture2D(wasTexture2D);
            GlStateManager.tryBlendFuncSeparate(prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha);
            syncBlend(wasBlend);
            GlStateManager.resetColor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void emitEntityPass(
        Tessellator tessellator,
        WorldRenderer wr,
        World world,
        EntityPlayer localPlayer,
        Minecraft mc,
        Esp module,
        int playerAccentArgb,
        boolean cornerBrackets,
        float fillOpacity,
        float partialTicks,
        double camX,
        double camY,
        double camZ,
        boolean fillPass
    ) {
        int fillAlpha = fillPass ? clampAlpha(fillOpacity) : OUTLINE_ALPHA;
        if (fillPass && fillAlpha <= 0) {
            return;
        }
        List<Entity> entities = world.loadedEntityList;
        for (int i = 0, size = entities.size(); i < size; i++) {
            Entity entity = entities.get(i);
            if (!module.isEntityEligible(entity, localPlayer, mc)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            int color = resolveEntityColor(living, localPlayer, playerAccentArgb);
            AxisAlignedBB bb = living.getEntityBoundingBox();
            double ix = living.lastTickPosX + (living.posX - living.lastTickPosX) * (double) partialTicks;
            double iy = living.lastTickPosY + (living.posY - living.lastTickPosY) * (double) partialTicks;
            double iz = living.lastTickPosZ + (living.posZ - living.lastTickPosZ) * (double) partialTicks;
            double minX;
            double minY;
            double minZ;
            double maxX;
            double maxY;
            double maxZ;
            if (bb != null) {
                double dx = ix - living.posX - camX;
                double dy = iy - living.posY - camY;
                double dz = iz - living.posZ - camZ;
                minX = bb.minX + dx - ENTITY_BOX_EXPAND;
                minY = bb.minY + dy - ENTITY_BOX_EXPAND;
                minZ = bb.minZ + dz - ENTITY_BOX_EXPAND;
                maxX = bb.maxX + dx + ENTITY_BOX_EXPAND;
                maxY = bb.maxY + dy + ENTITY_BOX_EXPAND;
                maxZ = bb.maxZ + dz + ENTITY_BOX_EXPAND;
            } else {
                double halfW = (double) living.width * 0.5D + ENTITY_BOX_EXPAND;
                minX = ix - camX - halfW;
                minY = iy - camY - ENTITY_BOX_EXPAND;
                minZ = iz - camZ - halfW;
                maxX = ix - camX + halfW;
                maxY = iy - camY + (double) living.height + ENTITY_BOX_EXPAND;
                maxZ = iz - camZ + halfW;
            }
            if (fillPass) {
                emitFilledBox(wr, minX, minY, minZ, maxX, maxY, maxZ, color, fillAlpha);
                flushQuadsIfNeeded(tessellator, wr);
            } else {
                if (cornerBrackets) {
                    emitCornerBracketBox(wr, minX, minY, minZ, maxX, maxY, maxZ, color, OUTLINE_ALPHA);
                } else {
                    emitOutlinedBox(wr, minX, minY, minZ, maxX, maxY, maxZ, color, OUTLINE_ALPHA);
                }
                flushLinesIfNeeded(tessellator, wr);
            }
        }
    }

    private static void renderEntityHealthBars(
        Tessellator tessellator,
        WorldRenderer wr,
        World world,
        EntityPlayer localPlayer,
        Minecraft mc,
        Esp module,
        RenderManager renderManager,
        float partialTicks,
        double camX,
        double camY,
        double camZ
    ) {
        List<Entity> entities = world.loadedEntityList;
        double fallbackRad = Math.toRadians(renderManager.playerViewY);
        double fallbackLeftX = -Math.cos(fallbackRad);
        double fallbackLeftZ = -Math.sin(fallbackRad);

        // Pass 1: subtle dark backdrop track for legibility across bright and dark scenes.
        GL11.glLineWidth(HEALTH_TRACK_WIDTH_PX);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        emitHealthBarSegments(
            tessellator, wr, entities, localPlayer, mc, module, partialTicks,
            camX, camY, camZ, fallbackLeftX, fallbackLeftZ, true
        );
        tessellator.draw();

        // Pass 2: crisp 1.5px semantic health indicator.
        GL11.glLineWidth(HEALTH_BAR_WIDTH_PX);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        emitHealthBarSegments(
            tessellator, wr, entities, localPlayer, mc, module, partialTicks,
            camX, camY, camZ, fallbackLeftX, fallbackLeftZ, false
        );
        tessellator.draw();
    }

    private static void emitHealthBarSegments(
        Tessellator tessellator,
        WorldRenderer wr,
        List<Entity> entities,
        EntityPlayer localPlayer,
        Minecraft mc,
        Esp module,
        float partialTicks,
        double camX,
        double camY,
        double camZ,
        double fallbackLeftX,
        double fallbackLeftZ,
        boolean trackPass
    ) {
        for (int i = 0, size = entities.size(); i < size; i++) {
            Entity entity = entities.get(i);
            if (!module.isEntityEligible(entity, localPlayer, mc)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            AxisAlignedBB bb = living.getEntityBoundingBox();
            double ix = living.lastTickPosX + (living.posX - living.lastTickPosX) * (double) partialTicks;
            double iy = living.lastTickPosY + (living.posY - living.lastTickPosY) * (double) partialTicks;
            double iz = living.lastTickPosZ + (living.posZ - living.lastTickPosZ) * (double) partialTicks;

            double minY;
            double maxY;
            double centerX;
            double centerZ;
            double halfW;
            double halfD;
            if (bb != null) {
                double dx = ix - living.posX - camX;
                double dy = iy - living.posY - camY;
                double dz = iz - living.posZ - camZ;
                double minX = bb.minX + dx;
                double maxX = bb.maxX + dx;
                double minZ = bb.minZ + dz;
                double maxZ = bb.maxZ + dz;
                minY = bb.minY + dy;
                maxY = bb.maxY + dy;
                centerX = (minX + maxX) * 0.5D;
                centerZ = (minZ + maxZ) * 0.5D;
                halfW = (maxX - minX) * 0.5D;
                halfD = (maxZ - minZ) * 0.5D;
            } else {
                centerX = ix - camX;
                centerZ = iz - camZ;
                minY = iy - camY;
                maxY = minY + (double) living.height;
                halfW = (double) living.width * 0.5D;
                halfD = halfW;
            }

            double horizSq = centerX * centerX + centerZ * centerZ;
            double leftX;
            double leftZ;
            if (horizSq > 1.0E-6D) {
                double invLen = 1.0D / Math.sqrt(horizSq);
                double fwdX = centerX * invLen;
                double fwdZ = centerZ * invLen;
                // Screen-left = -(forward x up) = (fwdZ, 0, -fwdX)
                leftX = fwdZ;
                leftZ = -fwdX;
            } else {
                leftX = fallbackLeftX;
                leftZ = fallbackLeftZ;
            }

            double offset = Math.abs(leftX) * halfW + Math.abs(leftZ) * halfD + 0.10D;
            double barX = centerX + leftX * offset;
            double barZ = centerZ + leftZ * offset;

            if (trackPass) {
                emitLine(wr, barX, minY, barZ, barX, maxY, barZ, HEALTH_TRACK_COLOR, HEALTH_TRACK_ALPHA);
            } else {
                float maxHp = living.getMaxHealth();
                if (maxHp < 1.0F) {
                    maxHp = 1.0F;
                }
                float ratio = clamp01(living.getHealth() / maxHp);
                if (ratio > 0.005F) {
                    double barTopY = minY + (maxY - minY) * (double) ratio;
                    int hpColor = resolveHealthColor(ratio);
                    emitLine(wr, barX, minY, barZ, barX, barTopY, barZ, hpColor, HEALTH_BAR_ALPHA);
                }
            }
            flushLinesIfNeeded(tessellator, wr);
        }
    }

    private static void emitChestPass(
        Tessellator tessellator,
        WorldRenderer wr,
        World world,
        float fillOpacity,
        double blockRangeSq,
        double camX,
        double camY,
        double camZ,
        boolean fillPass
    ) {
        int bodyAlpha = fillPass ? clampAlpha(fillOpacity) : OUTLINE_ALPHA;
        int latchAlpha = fillPass ? clampAlpha(Math.min(0.45F, fillOpacity * 1.8F)) : ACCENT_OUTLINE_ALPHA;
        if (fillPass && bodyAlpha <= 0 && latchAlpha <= 0) {
            return;
        }
        List<TileEntity> tileEntities = world.loadedTileEntityList;
        for (int i = 0, size = tileEntities.size(); i < size; i++) {
            TileEntity te = tileEntities.get(i);
            if (te == null || te.isInvalid()) {
                continue;
            }

            double bx;
            double by;
            double bz;
            boolean extX = false;
            boolean extZ = false;
            int chestColor;
            int meta;

            if (te instanceof TileEntityChest) {
                TileEntityChest chest = (TileEntityChest) te;
                if (!chest.hasWorldObj() || !(chest.getBlockType() instanceof BlockChest)) {
                    continue;
                }
                chest.checkForAdjacentChests();
                // Skip secondary half of double chests; primary XNeg/ZNeg-free half renders full 2x1 box.
                if (chest.adjacentChestXNeg != null || chest.adjacentChestZNeg != null) {
                    continue;
                }
                BlockPos pos = chest.getPos();
                bx = pos.getX();
                by = pos.getY();
                bz = pos.getZ();
                extX = chest.adjacentChestXPos != null;
                extZ = !extX && chest.adjacentChestZPos != null;
                chestColor = chest.getChestType() == 1 ? CHEST_TRAPPED_COLOR : CHEST_NORMAL_COLOR;
                meta = chest.getBlockMetadata();
            } else if (te instanceof TileEntityEnderChest) {
                TileEntityEnderChest ender = (TileEntityEnderChest) te;
                if (!ender.hasWorldObj()) {
                    continue;
                }
                BlockPos pos = ender.getPos();
                bx = pos.getX();
                by = pos.getY();
                bz = pos.getZ();
                chestColor = CHEST_ENDER_COLOR;
                meta = ender.getBlockMetadata();
            } else {
                continue;
            }

            double cx = bx + (extX ? 1.0D : 0.5D);
            double cy = by + 0.4375D;
            double cz = bz + (extZ ? 1.0D : 0.5D);
            if (distSq(cx - camX, cy - camY, cz - camZ) > blockRangeSq) {
                continue;
            }

            double minX = bx + CHEST_INSET - BLOCK_EPS - camX;
            double minY = by - camY;
            double minZ = bz + CHEST_INSET - BLOCK_EPS - camZ;
            double maxX = bx + (extX ? 2.0D : 1.0D) - CHEST_INSET + BLOCK_EPS - camX;
            double maxY = by + CHEST_HEIGHT + BLOCK_EPS - camY;
            double maxZ = bz + (extZ ? 2.0D : 1.0D) - CHEST_INSET + BLOCK_EPS - camZ;

            if (fillPass) {
                if (bodyAlpha > 0) {
                    emitFilledBox(wr, minX, minY, minZ, maxX, maxY, maxZ, chestColor, bodyAlpha);
                }
                if (latchAlpha > 0) {
                    emitChestLatch(wr, minX, minZ, maxX, maxZ, minY, meta, latchAlpha, true);
                }
                flushQuadsIfNeeded(tessellator, wr);
            } else {
                emitOutlinedBox(wr, minX, minY, minZ, maxX, maxY, maxZ, chestColor, OUTLINE_ALPHA);
                // Feature A: horizontal lid seam loop at y = 9/16 separating lid from lower chest body.
                emitHorizontalLoop(wr, minX, minY + CHEST_LID_SEAM_Y, minZ, maxX, maxZ, chestColor, ACCENT_OUTLINE_ALPHA);
                // Feature B: protruding 3D silver clasp centered on the front face across the lid seam.
                emitChestLatch(wr, minX, minZ, maxX, maxZ, minY, meta, ACCENT_OUTLINE_ALPHA, false);
                flushLinesIfNeeded(tessellator, wr);
            }
        }
    }

    private static void emitChestLatch(
        WorldRenderer wr,
        double chestMinX,
        double chestMinZ,
        double chestMaxX,
        double chestMaxZ,
        double baseY,
        int meta,
        int alpha,
        boolean fillPass
    ) {
        double midX = (chestMinX + chestMaxX) * 0.5D;
        double midZ = (chestMinZ + chestMaxZ) * 0.5D;
        double ly0 = baseY + CHEST_LATCH_MIN_Y;
        double ly1 = baseY + CHEST_LATCH_MAX_Y;
        double lx0;
        double lx1;
        double lz0;
        double lz1;

        if (meta == 2) {
            lx0 = midX - CHEST_LATCH_HALF_W;
            lx1 = midX + CHEST_LATCH_HALF_W;
            lz0 = chestMinZ - CHEST_LATCH_DEPTH;
            lz1 = chestMinZ;
        } else if (meta == 4) {
            lx0 = chestMinX - CHEST_LATCH_DEPTH;
            lx1 = chestMinX;
            lz0 = midZ - CHEST_LATCH_HALF_W;
            lz1 = midZ + CHEST_LATCH_HALF_W;
        } else if (meta == 5) {
            lx0 = chestMaxX;
            lx1 = chestMaxX + CHEST_LATCH_DEPTH;
            lz0 = midZ - CHEST_LATCH_HALF_W;
            lz1 = midZ + CHEST_LATCH_HALF_W;
        } else {
            // Default to SOUTH (+Z) when metadata is 3 or uninitialized (0).
            lx0 = midX - CHEST_LATCH_HALF_W;
            lx1 = midX + CHEST_LATCH_HALF_W;
            lz0 = chestMaxZ;
            lz1 = chestMaxZ + CHEST_LATCH_DEPTH;
        }
        if (fillPass) {
            emitFilledBox(wr, lx0, ly0, lz0, lx1, ly1, lz1, CHEST_LATCH_COLOR, alpha);
        } else {
            emitOutlinedBox(wr, lx0, ly0, lz0, lx1, ly1, lz1, CHEST_LATCH_COLOR, alpha);
        }
    }

    private static void emitBedPass(
        Tessellator tessellator,
        WorldRenderer wr,
        BedStructureCache bedCache,
        float fillOpacity,
        double blockRangeSq,
        double camX,
        double camY,
        double camZ,
        boolean fillPass
    ) {
        int mattressAlpha = fillPass ? clampAlpha(fillOpacity) : OUTLINE_ALPHA;
        int legAlpha = fillPass ? clampAlpha(fillOpacity * 0.85F) : OUTLINE_ALPHA;
        int pillowAlpha = fillPass ? clampAlpha(Math.min(0.45F, fillOpacity * 1.8F)) : ACCENT_OUTLINE_ALPHA;
        if (fillPass && mattressAlpha <= 0 && legAlpha <= 0 && pillowAlpha <= 0) {
            return;
        }

        int count = bedCache.getBedCount();
        for (int i = 0; i < count; i++) {
            int hx = bedCache.getHeadX(i);
            int hy = bedCache.getHeadY(i);
            int hz = bedCache.getHeadZ(i);
            byte dir = bedCache.getFacingDir(i);
            boolean paired = bedCache.isPairedFoot(i);

            int fx = BedStructureCache.getFacingOffsetX(dir);
            int fz = BedStructureCache.getFacingOffsetZ(dir);

            double centerX = hx + 0.5D - (paired ? fx * 0.5D : 0.0D);
            double centerY = hy + 0.3D;
            double centerZ = hz + 0.5D - (paired ? fz * 0.5D : 0.0D);
            if (distSq(centerX - camX, centerY - camY, centerZ - camZ) > blockRangeSq) {
                continue;
            }

            double minX = computeBedMinX(hx, dir, paired) - camX;
            double maxX = computeBedMaxX(hx, dir, paired) - camX;
            double minZ = computeBedMinZ(hz, dir, paired) - camZ;
            double maxZ = computeBedMaxZ(hz, dir, paired) - camZ;

            double baseY = (double) hy - camY;
            double legMinY = baseY + 0.002D;
            double legMaxY = baseY + BED_LEG_HEIGHT;
            double matMaxY = baseY + BED_MATTRESS_HEIGHT + BLOCK_EPS;

            double pMinX = computePillowMinX(hx, dir, paired) - camX;
            double pMaxX = computePillowMaxX(hx, dir, paired) - camX;
            double pMinZ = computePillowMinZ(hz, dir, paired) - camZ;
            double pMaxZ = computePillowMaxZ(hz, dir, paired) - camZ;

            if (fillPass) {
                if (legAlpha > 0) {
                    emitFilledBox(wr, minX, legMinY, minZ, minX + BED_LEG_SIZE, legMaxY, minZ + BED_LEG_SIZE, BED_FRAME_COLOR, legAlpha);
                    emitFilledBox(wr, maxX - BED_LEG_SIZE, legMinY, minZ, maxX, legMaxY, minZ + BED_LEG_SIZE, BED_FRAME_COLOR, legAlpha);
                    emitFilledBox(wr, minX, legMinY, maxZ - BED_LEG_SIZE, minX + BED_LEG_SIZE, legMaxY, maxZ, BED_FRAME_COLOR, legAlpha);
                    emitFilledBox(wr, maxX - BED_LEG_SIZE, legMinY, maxZ - BED_LEG_SIZE, maxX, legMaxY, maxZ, BED_FRAME_COLOR, legAlpha);
                }
                if (mattressAlpha > 0) {
                    emitFilledBox(wr, minX, legMaxY, minZ, maxX, matMaxY, maxZ, BED_MATTRESS_COLOR, mattressAlpha);
                }
                if (pillowAlpha > 0) {
                    emitFilledBox(wr, pMinX, matMaxY, pMinZ, pMaxX, baseY + BED_PILLOW_TOP_Y, pMaxZ, BED_PILLOW_COLOR, pillowAlpha);
                }
                flushQuadsIfNeeded(tessellator, wr);
            } else {
                // Layer 1: 4 corner bed leg posts.
                emitOutlinedBox(wr, minX, legMinY, minZ, minX + BED_LEG_SIZE, legMaxY, minZ + BED_LEG_SIZE, BED_FRAME_COLOR, OUTLINE_ALPHA);
                emitOutlinedBox(wr, maxX - BED_LEG_SIZE, legMinY, minZ, maxX, legMaxY, minZ + BED_LEG_SIZE, BED_FRAME_COLOR, OUTLINE_ALPHA);
                emitOutlinedBox(wr, minX, legMinY, maxZ - BED_LEG_SIZE, minX + BED_LEG_SIZE, legMaxY, maxZ, BED_FRAME_COLOR, OUTLINE_ALPHA);
                emitOutlinedBox(wr, maxX - BED_LEG_SIZE, legMinY, maxZ - BED_LEG_SIZE, maxX, legMaxY, maxZ, BED_FRAME_COLOR, OUTLINE_ALPHA);

                // Layer 2: low flat mattress body.
                emitOutlinedBox(wr, minX, legMaxY, minZ, maxX, matMaxY, maxZ, BED_MATTRESS_COLOR, OUTLINE_ALPHA);

                // Layer 3: transverse quilt/blanket fold seam across mattress top between head area and blanket.
                double seamY = matMaxY + 0.001D;
                double quiltDropY = baseY + 0.43D;
                if (dir == BedStructureCache.DIR_SOUTH || dir == BedStructureCache.DIR_NORTH) {
                    double seamZ = computeBedQuiltSeamCoord(hz, dir, paired) - camZ;
                    emitLine(wr, minX, seamY, seamZ, maxX, seamY, seamZ, BED_QUILT_SEAM_COLOR, ACCENT_OUTLINE_ALPHA);
                    emitLine(wr, minX, seamY, seamZ, minX, quiltDropY, seamZ, BED_QUILT_SEAM_COLOR, OUTLINE_ALPHA);
                    emitLine(wr, maxX, seamY, seamZ, maxX, quiltDropY, seamZ, BED_QUILT_SEAM_COLOR, OUTLINE_ALPHA);
                } else {
                    double seamX = computeBedQuiltSeamCoord(hx, dir, paired) - camX;
                    emitLine(wr, seamX, seamY, minZ, seamX, seamY, maxZ, BED_QUILT_SEAM_COLOR, ACCENT_OUTLINE_ALPHA);
                    emitLine(wr, seamX, seamY, minZ, seamX, quiltDropY, minZ, BED_QUILT_SEAM_COLOR, OUTLINE_ALPHA);
                    emitLine(wr, seamX, seamY, maxZ, seamX, quiltDropY, maxZ, BED_QUILT_SEAM_COLOR, OUTLINE_ALPHA);
                }

                // Layer 4: raised 3D ivory pillow box at the head end.
                emitOutlinedBox(wr, pMinX, matMaxY, pMinZ, pMaxX, baseY + BED_PILLOW_TOP_Y, pMaxZ, BED_PILLOW_COLOR, ACCENT_OUTLINE_ALPHA);
                flushLinesIfNeeded(tessellator, wr);
            }
        }
    }

    private static double computeBedMinX(int hx, byte dir, boolean paired) {
        if (dir == BedStructureCache.DIR_SOUTH || dir == BedStructureCache.DIR_NORTH) {
            return (double) hx + BED_SIDE_INSET;
        }
        if (dir == BedStructureCache.DIR_EAST && paired) {
            return (double) hx - 1.0D;
        }
        return (double) hx;
    }

    private static double computeBedMaxX(int hx, byte dir, boolean paired) {
        if (dir == BedStructureCache.DIR_SOUTH || dir == BedStructureCache.DIR_NORTH) {
            return (double) hx + 1.0D - BED_SIDE_INSET;
        }
        if (dir == BedStructureCache.DIR_WEST && paired) {
            return (double) hx + 2.0D;
        }
        return (double) hx + 1.0D;
    }

    private static double computeBedMinZ(int hz, byte dir, boolean paired) {
        if (dir == BedStructureCache.DIR_WEST || dir == BedStructureCache.DIR_EAST) {
            return (double) hz + BED_SIDE_INSET;
        }
        if (dir == BedStructureCache.DIR_SOUTH && paired) {
            return (double) hz - 1.0D;
        }
        return (double) hz;
    }

    private static double computeBedMaxZ(int hz, byte dir, boolean paired) {
        if (dir == BedStructureCache.DIR_WEST || dir == BedStructureCache.DIR_EAST) {
            return (double) hz + 1.0D - BED_SIDE_INSET;
        }
        if (dir == BedStructureCache.DIR_NORTH && paired) {
            return (double) hz + 2.0D;
        }
        return (double) hz + 1.0D;
    }

    private static double computeBedQuiltSeamCoord(int headCoord, byte dir, boolean paired) {
        double offset = paired ? BED_QUILT_LINE_OFFSET_PAIRED : BED_QUILT_LINE_OFFSET_SINGLE;
        if (dir == BedStructureCache.DIR_SOUTH || dir == BedStructureCache.DIR_EAST) {
            return (double) headCoord + 1.0D - offset;
        }
        return (double) headCoord + offset;
    }

    private static double computePillowMinX(int hx, byte dir, boolean paired) {
        double depth = paired ? BED_PILLOW_DEPTH_PAIRED : BED_PILLOW_DEPTH_SINGLE;
        if (dir == BedStructureCache.DIR_EAST) {
            return (double) hx + 1.0D - BED_PILLOW_INSET - depth;
        }
        return (double) hx + BED_PILLOW_INSET;
    }

    private static double computePillowMaxX(int hx, byte dir, boolean paired) {
        double depth = paired ? BED_PILLOW_DEPTH_PAIRED : BED_PILLOW_DEPTH_SINGLE;
        if (dir == BedStructureCache.DIR_WEST) {
            return (double) hx + BED_PILLOW_INSET + depth;
        }
        return (double) hx + 1.0D - BED_PILLOW_INSET;
    }

    private static double computePillowMinZ(int hz, byte dir, boolean paired) {
        double depth = paired ? BED_PILLOW_DEPTH_PAIRED : BED_PILLOW_DEPTH_SINGLE;
        if (dir == BedStructureCache.DIR_SOUTH) {
            return (double) hz + 1.0D - BED_PILLOW_INSET - depth;
        }
        return (double) hz + BED_PILLOW_INSET;
    }

    private static double computePillowMaxZ(int hz, byte dir, boolean paired) {
        double depth = paired ? BED_PILLOW_DEPTH_PAIRED : BED_PILLOW_DEPTH_SINGLE;
        if (dir == BedStructureCache.DIR_NORTH) {
            return (double) hz + BED_PILLOW_INSET + depth;
        }
        return (double) hz + 1.0D - BED_PILLOW_INSET;
    }

    private static int resolveEntityColor(EntityLivingBase living, EntityPlayer localPlayer, int playerAccentArgb) {
        if (living instanceof EntityPlayer) {
            if (Esp.isTeammate((EntityPlayer) living, localPlayer)) {
                return TEAMMATE_COLOR;
            }
            return playerAccentArgb;
        }
        if (isHostileMob(living)) {
            return HOSTILE_MOB_COLOR;
        }
        return PASSIVE_MOB_COLOR;
    }

    private static boolean isHostileMob(EntityLivingBase living) {
        if (living instanceof IMob || living instanceof EntityDragon) {
            return true;
        }
        if (living instanceof EntityWolf) {
            return ((EntityWolf) living).isAngry();
        }
        return false;
    }

    private static int resolveHealthColor(float ratio) {
        if (ratio >= 0.6F) {
            return HEALTH_HIGH_COLOR;
        }
        if (ratio >= 0.3F) {
            return HEALTH_MID_COLOR;
        }
        return HEALTH_LOW_COLOR;
    }

    private static void emitFilledBox(
        WorldRenderer wr,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int argb,
        int alpha
    ) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;

        // Bottom (-Y)
        wr.pos(minX, minY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, minY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, minY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, minY, maxZ).color(r, g, b, alpha).endVertex();

        // Top (+Y)
        wr.pos(minX, maxY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, maxY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, maxY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, maxY, minZ).color(r, g, b, alpha).endVertex();

        // North (-Z)
        wr.pos(minX, minY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, maxY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, maxY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, minY, minZ).color(r, g, b, alpha).endVertex();

        // South (+Z)
        wr.pos(minX, minY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, minY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, maxY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, maxY, maxZ).color(r, g, b, alpha).endVertex();

        // West (-X)
        wr.pos(minX, minY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, maxY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, maxY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, minY, minZ).color(r, g, b, alpha).endVertex();

        // East (+X)
        wr.pos(maxX, minY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, maxY, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, maxY, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, minY, maxZ).color(r, g, b, alpha).endVertex();
    }

    private static void emitOutlinedBox(
        WorldRenderer wr,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int argb,
        int alpha
    ) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;

        // Bottom rectangle
        emitLineRgb(wr, minX, minY, minZ, maxX, minY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, minX, minY, maxZ, minX, minY, minZ, r, g, b, alpha);

        // Top rectangle
        emitLineRgb(wr, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, alpha);

        // Vertical edges
        emitLineRgb(wr, minX, minY, minZ, minX, maxY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
    }

    private static void emitCornerBracketBox(
        WorldRenderer wr,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int argb,
        int alpha
    ) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;

        double ax = (maxX - minX) * CORNER_ARM_RATIO;
        double ay = (maxY - minY) * CORNER_ARM_RATIO;
        double az = (maxZ - minZ) * CORNER_ARM_RATIO;

        // Bottom 4 corners
        emitCornerArms(wr, minX, minY, minZ, ax, ay, az, r, g, b, alpha);
        emitCornerArms(wr, maxX, minY, minZ, -ax, ay, az, r, g, b, alpha);
        emitCornerArms(wr, minX, minY, maxZ, ax, ay, -az, r, g, b, alpha);
        emitCornerArms(wr, maxX, minY, maxZ, -ax, ay, -az, r, g, b, alpha);

        // Top 4 corners
        emitCornerArms(wr, minX, maxY, minZ, ax, -ay, az, r, g, b, alpha);
        emitCornerArms(wr, maxX, maxY, minZ, -ax, -ay, az, r, g, b, alpha);
        emitCornerArms(wr, minX, maxY, maxZ, ax, -ay, -az, r, g, b, alpha);
        emitCornerArms(wr, maxX, maxY, maxZ, -ax, -ay, -az, r, g, b, alpha);
    }

    private static void emitCornerArms(
        WorldRenderer wr,
        double cx,
        double cy,
        double cz,
        double dx,
        double dy,
        double dz,
        int r,
        int g,
        int b,
        int alpha
    ) {
        emitLineRgb(wr, cx, cy, cz, cx + dx, cy, cz, r, g, b, alpha);
        emitLineRgb(wr, cx, cy, cz, cx, cy + dy, cz, r, g, b, alpha);
        emitLineRgb(wr, cx, cy, cz, cx, cy, cz + dz, r, g, b, alpha);
    }

    private static void emitHorizontalLoop(
        WorldRenderer wr,
        double minX,
        double y,
        double minZ,
        double maxX,
        double maxZ,
        int argb,
        int alpha
    ) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        emitLineRgb(wr, minX, y, minZ, maxX, y, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, y, minZ, maxX, y, maxZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, y, maxZ, minX, y, maxZ, r, g, b, alpha);
        emitLineRgb(wr, minX, y, maxZ, minX, y, minZ, r, g, b, alpha);
    }

    private static void emitLine(
        WorldRenderer wr,
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2,
        int argb,
        int alpha
    ) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        emitLineRgb(wr, x1, y1, z1, x2, y2, z2, r, g, b, alpha);
    }

    private static void emitLineRgb(
        WorldRenderer wr,
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2,
        int r,
        int g,
        int b,
        int alpha
    ) {
        wr.pos(x1, y1, z1).color(r, g, b, alpha).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, alpha).endVertex();
    }

    private static void flushQuadsIfNeeded(Tessellator tessellator, WorldRenderer wr) {
        if (wr.getVertexCount() >= BATCH_VERTEX_FLUSH_THRESHOLD) {
            tessellator.draw();
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        }
    }

    private static void flushLinesIfNeeded(Tessellator tessellator, WorldRenderer wr) {
        if (wr.getVertexCount() >= BATCH_VERTEX_FLUSH_THRESHOLD) {
            tessellator.draw();
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        }
    }

    private static void syncDepthTest(boolean enable) {
        if (enable) {
            GlStateManager.enableDepth();
            if (!GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) {
                GlStateManager.disableDepth();
                GlStateManager.enableDepth();
            }
        } else {
            GlStateManager.disableDepth();
            if (GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) {
                GlStateManager.enableDepth();
                GlStateManager.disableDepth();
            }
        }
    }

    private static void syncBlend(boolean enable) {
        if (enable) {
            GlStateManager.enableBlend();
            if (!GL11.glIsEnabled(GL11.GL_BLEND)) {
                GlStateManager.disableBlend();
                GlStateManager.enableBlend();
            }
        } else {
            GlStateManager.disableBlend();
            if (GL11.glIsEnabled(GL11.GL_BLEND)) {
                GlStateManager.enableBlend();
                GlStateManager.disableBlend();
            }
        }
    }

    private static void syncTexture2D(boolean enable) {
        if (enable) {
            GlStateManager.enableTexture2D();
            if (!GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) {
                GlStateManager.disableTexture2D();
                GlStateManager.enableTexture2D();
            }
        } else {
            GlStateManager.disableTexture2D();
            if (GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) {
                GlStateManager.enableTexture2D();
                GlStateManager.disableTexture2D();
            }
        }
    }

    private static void syncCullFace(boolean enable) {
        if (enable) {
            GlStateManager.enableCull();
            if (!GL11.glIsEnabled(GL11.GL_CULL_FACE)) {
                GlStateManager.disableCull();
                GlStateManager.enableCull();
            }
        } else {
            GlStateManager.disableCull();
            if (GL11.glIsEnabled(GL11.GL_CULL_FACE)) {
                GlStateManager.enableCull();
                GlStateManager.disableCull();
            }
        }
    }

    private static void syncLighting(boolean enable) {
        if (enable) {
            GlStateManager.enableLighting();
            if (!GL11.glIsEnabled(GL11.GL_LIGHTING)) {
                GlStateManager.disableLighting();
                GlStateManager.enableLighting();
            }
        } else {
            GlStateManager.disableLighting();
            if (GL11.glIsEnabled(GL11.GL_LIGHTING)) {
                GlStateManager.enableLighting();
                GlStateManager.disableLighting();
            }
        }
    }

    private static void syncAlphaTest(boolean enable) {
        if (enable) {
            GlStateManager.enableAlpha();
            if (!GL11.glIsEnabled(GL11.GL_ALPHA_TEST)) {
                GlStateManager.disableAlpha();
                GlStateManager.enableAlpha();
            }
        } else {
            GlStateManager.disableAlpha();
            if (GL11.glIsEnabled(GL11.GL_ALPHA_TEST)) {
                GlStateManager.enableAlpha();
                GlStateManager.disableAlpha();
            }
        }
    }

    private static double distSq(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
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

    private static int clampAlpha(float normalizedAlpha) {
        int a = Math.round(clamp01(normalizedAlpha) * 255.0F);
        if (a < 0) {
            return 0;
        }
        if (a > 255) {
            return 255;
        }
        return a;
    }
}
