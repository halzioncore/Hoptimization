package com.hoptimization.modules.visual.jarvis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

/**
 * Zero-allocation batched 3D world overlay renderer for Jarvis fireball blast predictions
 * and bed-defense mining paths, with strict OpenGL state restoration.
 */
public final class JarvisWorldRenderer {
    private static final int TIER_GUARANTEED_COLOR = 0xFFF2555A;
    private static final int TIER_POSSIBLE_COLOR = 0xFFF5B544;
    private static final int BED_GOAL_COLOR = 0xFF3DD68C;

    private static final int TIER_GUARANTEED_FILL_ALPHA = 71;
    private static final int TIER_POSSIBLE_FILL_ALPHA = 31;
    private static final int IMPACT_MARKER_FILL_ALPHA = 64;
    private static final int TIER_GUARANTEED_OUTLINE_ALPHA = 235;
    private static final int TIER_POSSIBLE_OUTLINE_ALPHA = 205;
    private static final int IMPACT_MARKER_OUTLINE_ALPHA = 242;
    private static final int TRAJECTORY_LINE_ALPHA = 230;

    private static final float TIER_GUARANTEED_LINE_WIDTH = 2.2F;
    private static final float TIER_POSSIBLE_LINE_WIDTH = 1.4F;

    private static final int STEP_ZERO_FILL_ALPHA = 71;
    private static final int STEP_ZERO_OUTLINE_ALPHA = 245;
    private static final float STEP_ZERO_LINE_WIDTH = 2.4F;
    private static final float STEP_TAIL_LINE_WIDTH = 1.5F;
    private static final float PATH_GUIDE_LINE_WIDTH = 2.0F;
    private static final int BED_GOAL_FILL_ALPHA = 64;
    private static final int BED_GOAL_OUTLINE_ALPHA = 242;

    private static final double BLOCK_EXPAND = 0.003D;
    private static final double IMPACT_MARKER_HALF = 0.18D;
    private static final double BED_MATTRESS_HEIGHT = 0.5625D;
    private static final int BATCH_VERTEX_FLUSH_THRESHOLD = 32768;

    private JarvisWorldRenderer() {
    }

    /**
     * Renders predicted fireball blast footprints, trajectories, and impact markers in 3D world space.
     *
     * @param predictor      populated fireball blast predictor
     * @param showTrajectory whether to draw trajectory line and 3D impact marker box
     * @param throughWalls   whether geometry ignores world depth occlusion
     * @param partialTicks   current frame partial ticks
     */
    public static void renderFireballPredictions(
        FireballBlastPredictor predictor,
        boolean showTrajectory,
        boolean throughWalls,
        float partialTicks
    ) {
        if (predictor == null) {
            return;
        }
        int activeCount = predictor.getActiveCount();
        if (activeCount <= 0 || !predictor.hasActiveImpacts()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null) {
            viewEntity = mc.thePlayer;
        }
        double camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * (double) partialTicks;
        double camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * (double) partialTicks;
        double camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * (double) partialTicks;

        ContextCapabilities caps = GLContext.getCapabilities();
        int prevActiveTex = (caps != null && caps.OpenGL13)
            ? GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
            : OpenGlHelper.defaultTexUnit;
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
        int prevBlendSrcAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
            : prevBlendSrc;
        int prevBlendDstAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
            : prevBlendDst;

        try {
            applyOverlayRenderState(throughWalls);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            boolean hasTierPossible = false;
            boolean hasTierGuaranteed = false;

            // Pass 1: batched translucent fills for blast blocks and impact markers.
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int s = 0; s < activeCount; s++) {
                FireballBlastPredictor.FireballSlot slot = predictor.getSlot(s);
                if (!slot.isActive() || !slot.hasImpact()) {
                    continue;
                }
                int blockCount = slot.getBlockCount();
                boolean slotHasGuaranteed = false;
                for (int i = 0; i < blockCount; i++) {
                    byte tier = slot.getTier(i);
                    boolean guaranteed = tier == FireballBlastPredictor.TIER_GUARANTEED;
                    if (guaranteed) {
                        hasTierGuaranteed = true;
                        slotHasGuaranteed = true;
                    } else {
                        hasTierPossible = true;
                    }
                    int color = guaranteed ? TIER_GUARANTEED_COLOR : TIER_POSSIBLE_COLOR;
                    int alpha = guaranteed ? TIER_GUARANTEED_FILL_ALPHA : TIER_POSSIBLE_FILL_ALPHA;
                    double minX = (double) slot.getBlockX(i) - BLOCK_EXPAND - camX;
                    double minY = (double) slot.getBlockY(i) - BLOCK_EXPAND - camY;
                    double minZ = (double) slot.getBlockZ(i) - BLOCK_EXPAND - camZ;
                    emitFilledBox(
                        wr,
                        minX, minY, minZ,
                        minX + 1.0D + BLOCK_EXPAND * 2.0D,
                        minY + 1.0D + BLOCK_EXPAND * 2.0D,
                        minZ + 1.0D + BLOCK_EXPAND * 2.0D,
                        color,
                        alpha
                    );
                    flushQuadsIfNeeded(tessellator, wr);
                }
                if (showTrajectory) {
                    double ix = slot.getHitX() - camX;
                    double iy = slot.getHitY() - camY;
                    double iz = slot.getHitZ() - camZ;
                    int markerColor = slotHasGuaranteed ? TIER_GUARANTEED_COLOR : TIER_POSSIBLE_COLOR;
                    emitFilledBox(
                        wr,
                        ix - IMPACT_MARKER_HALF, iy - IMPACT_MARKER_HALF, iz - IMPACT_MARKER_HALF,
                        ix + IMPACT_MARKER_HALF, iy + IMPACT_MARKER_HALF, iz + IMPACT_MARKER_HALF,
                        markerColor,
                        IMPACT_MARKER_FILL_ALPHA
                    );
                    flushQuadsIfNeeded(tessellator, wr);
                }
            }
            tessellator.draw();

            // Pass 2: Tier 1 (possible) block outlines at 1.4px.
            if (hasTierPossible) {
                GL11.glLineWidth(TIER_POSSIBLE_LINE_WIDTH);
                wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                for (int s = 0; s < activeCount; s++) {
                    FireballBlastPredictor.FireballSlot slot = predictor.getSlot(s);
                    if (!slot.isActive() || !slot.hasImpact()) {
                        continue;
                    }
                    int blockCount = slot.getBlockCount();
                    for (int i = 0; i < blockCount; i++) {
                        if (slot.getTier(i) == FireballBlastPredictor.TIER_GUARANTEED) {
                            continue;
                        }
                        double minX = (double) slot.getBlockX(i) - BLOCK_EXPAND - camX;
                        double minY = (double) slot.getBlockY(i) - BLOCK_EXPAND - camY;
                        double minZ = (double) slot.getBlockZ(i) - BLOCK_EXPAND - camZ;
                        emitOutlinedBox(
                            wr,
                            minX, minY, minZ,
                            minX + 1.0D + BLOCK_EXPAND * 2.0D,
                            minY + 1.0D + BLOCK_EXPAND * 2.0D,
                            minZ + 1.0D + BLOCK_EXPAND * 2.0D,
                            TIER_POSSIBLE_COLOR,
                            TIER_POSSIBLE_OUTLINE_ALPHA
                        );
                        flushLinesIfNeeded(tessellator, wr);
                    }
                }
                tessellator.draw();
            }

            // Pass 3: Tier 2 (guaranteed) block outlines + trajectory lines & impact marker boxes at 2.2px.
            if (hasTierGuaranteed || showTrajectory) {
                GL11.glLineWidth(TIER_GUARANTEED_LINE_WIDTH);
                wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                for (int s = 0; s < activeCount; s++) {
                    FireballBlastPredictor.FireballSlot slot = predictor.getSlot(s);
                    if (!slot.isActive() || !slot.hasImpact()) {
                        continue;
                    }
                    int blockCount = slot.getBlockCount();
                    boolean slotHasGuaranteed = false;
                    for (int i = 0; i < blockCount; i++) {
                        if (slot.getTier(i) != FireballBlastPredictor.TIER_GUARANTEED) {
                            continue;
                        }
                        slotHasGuaranteed = true;
                        double minX = (double) slot.getBlockX(i) - BLOCK_EXPAND - camX;
                        double minY = (double) slot.getBlockY(i) - BLOCK_EXPAND - camY;
                        double minZ = (double) slot.getBlockZ(i) - BLOCK_EXPAND - camZ;
                        emitOutlinedBox(
                            wr,
                            minX, minY, minZ,
                            minX + 1.0D + BLOCK_EXPAND * 2.0D,
                            minY + 1.0D + BLOCK_EXPAND * 2.0D,
                            minZ + 1.0D + BLOCK_EXPAND * 2.0D,
                            TIER_GUARANTEED_COLOR,
                            TIER_GUARANTEED_OUTLINE_ALPHA
                        );
                        flushLinesIfNeeded(tessellator, wr);
                    }
                    if (showTrajectory) {
                        double fx = slot.getPrevX()
                            + (slot.getCurX() - slot.getPrevX()) * (double) partialTicks - camX;
                        double fy = slot.getPrevY()
                            + (slot.getCurY() - slot.getPrevY()) * (double) partialTicks - camY;
                        double fz = slot.getPrevZ()
                            + (slot.getCurZ() - slot.getPrevZ()) * (double) partialTicks - camZ;
                        double hx = slot.getHitX() - camX;
                        double hy = slot.getHitY() - camY;
                        double hz = slot.getHitZ() - camZ;
                        int lineColor = slotHasGuaranteed ? TIER_GUARANTEED_COLOR : TIER_POSSIBLE_COLOR;
                        emitLine(wr, fx, fy, fz, hx, hy, hz, lineColor, TRAJECTORY_LINE_ALPHA);
                        emitOutlinedBox(
                            wr,
                            hx - IMPACT_MARKER_HALF, hy - IMPACT_MARKER_HALF, hz - IMPACT_MARKER_HALF,
                            hx + IMPACT_MARKER_HALF, hy + IMPACT_MARKER_HALF, hz + IMPACT_MARKER_HALF,
                            lineColor,
                            IMPACT_MARKER_OUTLINE_ALPHA
                        );
                        flushLinesIfNeeded(tessellator, wr);
                    }
                }
                tessellator.draw();
            }
        } finally {
            restoreOverlayRenderState(
                prevActiveTex, wasDepth, wasBlend, wasTexture2D, wasCull,
                wasLighting, wasAlpha, wasLineSmooth, prevDepthMask, prevDepthFunc,
                prevLineWidth, prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha
            );
        }
    }

    /**
     * Renders the ordered bed-defense mining steps, goal bed mattress box, and 3D guiding polyline.
     *
     * @param stepCount    number of mining steps before the bed
     * @param stepX        step X block coordinates
     * @param stepY        step Y block coordinates
     * @param stepZ        step Z block coordinates
     * @param goalBedX     goal bed X coordinate
     * @param goalBedY     goal bed Y coordinate
     * @param goalBedZ     goal bed Z coordinate
     * @param nakedBed     true when the bed can be mined directly without breaking defense blocks
     * @param entryX       external entry world X coordinate
     * @param entryY       external entry world Y coordinate
     * @param entryZ       external entry world Z coordinate
     * @param throughWalls whether geometry ignores world depth occlusion
     * @param accentArgb   accent ARGB color for step highlights and guide path
     * @param partialTicks current frame partial ticks
     */
    public static void renderMiningPath(
        int stepCount,
        int[] stepX,
        int[] stepY,
        int[] stepZ,
        int goalBedX,
        int goalBedY,
        int goalBedZ,
        boolean nakedBed,
        double entryX,
        double entryY,
        double entryZ,
        boolean throughWalls,
        int accentArgb,
        float partialTicks
    ) {
        int effectiveSteps = 0;
        if (!nakedBed && stepCount > 0) {
            if (stepX == null || stepY == null || stepZ == null) {
                return;
            }
            effectiveSteps = Math.min(stepCount, Math.min(stepX.length, Math.min(stepY.length, stepZ.length)));
        }
        if (!nakedBed && effectiveSteps <= 0) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null) {
            viewEntity = mc.thePlayer;
        }
        double camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * (double) partialTicks;
        double camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * (double) partialTicks;
        double camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * (double) partialTicks;

        ContextCapabilities caps = GLContext.getCapabilities();
        int prevActiveTex = (caps != null && caps.OpenGL13)
            ? GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
            : OpenGlHelper.defaultTexUnit;
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
        int prevBlendSrcAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
            : prevBlendSrc;
        int prevBlendDstAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
            : prevBlendDst;

        try {
            applyOverlayRenderState(throughWalls);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            double bedMinX = (double) goalBedX - BLOCK_EXPAND - camX;
            double bedMinY = (double) goalBedY - BLOCK_EXPAND - camY;
            double bedMinZ = (double) goalBedZ - BLOCK_EXPAND - camZ;
            double bedMaxX = (double) goalBedX + 1.0D + BLOCK_EXPAND - camX;
            double bedMaxY = (double) goalBedY + BED_MATTRESS_HEIGHT + BLOCK_EXPAND - camY;
            double bedMaxZ = (double) goalBedZ + 1.0D + BLOCK_EXPAND - camZ;

            // Pass 1: translucent block fills for steps and goal bed mattress.
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < effectiveSteps; i++) {
                int fillAlpha;
                if (i == 0) {
                    fillAlpha = STEP_ZERO_FILL_ALPHA;
                } else {
                    float decay = Math.max(0.22F, 1.0F - 0.14F * (float) i);
                    fillAlpha = Math.max(10, Math.round(46.0F * decay));
                }
                double minX = (double) stepX[i] - BLOCK_EXPAND - camX;
                double minY = (double) stepY[i] - BLOCK_EXPAND - camY;
                double minZ = (double) stepZ[i] - BLOCK_EXPAND - camZ;
                emitFilledBox(
                    wr,
                    minX, minY, minZ,
                    minX + 1.0D + BLOCK_EXPAND * 2.0D,
                    minY + 1.0D + BLOCK_EXPAND * 2.0D,
                    minZ + 1.0D + BLOCK_EXPAND * 2.0D,
                    accentArgb,
                    fillAlpha
                );
                flushQuadsIfNeeded(tessellator, wr);
            }
            emitFilledBox(
                wr,
                bedMinX, bedMinY, bedMinZ,
                bedMaxX, bedMaxY, bedMaxZ,
                BED_GOAL_COLOR,
                BED_GOAL_FILL_ALPHA
            );
            tessellator.draw();

            // Pass 2: secondary step outlines (step 1 .. K-1) with progressive alpha falloff.
            if (effectiveSteps > 1) {
                GL11.glLineWidth(STEP_TAIL_LINE_WIDTH);
                wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                for (int i = 1; i < effectiveSteps; i++) {
                    float decay = Math.max(0.28F, 1.0F - 0.13F * (float) i);
                    int outlineAlpha = Math.max(64, Math.round(215.0F * decay));
                    double minX = (double) stepX[i] - BLOCK_EXPAND - camX;
                    double minY = (double) stepY[i] - BLOCK_EXPAND - camY;
                    double minZ = (double) stepZ[i] - BLOCK_EXPAND - camZ;
                    emitOutlinedBox(
                        wr,
                        minX, minY, minZ,
                        minX + 1.0D + BLOCK_EXPAND * 2.0D,
                        minY + 1.0D + BLOCK_EXPAND * 2.0D,
                        minZ + 1.0D + BLOCK_EXPAND * 2.0D,
                        accentArgb,
                        outlineAlpha
                    );
                    flushLinesIfNeeded(tessellator, wr);
                }
                tessellator.draw();
            }

            // Pass 3: primary target (step 0) and goal bed mattress outlines at 2.4px.
            GL11.glLineWidth(STEP_ZERO_LINE_WIDTH);
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            if (effectiveSteps > 0) {
                double s0MinX = (double) stepX[0] - BLOCK_EXPAND - camX;
                double s0MinY = (double) stepY[0] - BLOCK_EXPAND - camY;
                double s0MinZ = (double) stepZ[0] - BLOCK_EXPAND - camZ;
                emitOutlinedBox(
                    wr,
                    s0MinX, s0MinY, s0MinZ,
                    s0MinX + 1.0D + BLOCK_EXPAND * 2.0D,
                    s0MinY + 1.0D + BLOCK_EXPAND * 2.0D,
                    s0MinZ + 1.0D + BLOCK_EXPAND * 2.0D,
                    accentArgb,
                    STEP_ZERO_OUTLINE_ALPHA
                );
            }
            emitOutlinedBox(
                wr,
                bedMinX, bedMinY, bedMinZ,
                bedMaxX, bedMaxY, bedMaxZ,
                BED_GOAL_COLOR,
                BED_GOAL_OUTLINE_ALPHA
            );
            tessellator.draw();

            // Pass 4: 3D guiding polyline connecting entry -> step centers -> goal bed center.
            boolean hasEntry = !Double.isNaN(entryX) && !Double.isNaN(entryY) && !Double.isNaN(entryZ);
            if (hasEntry || effectiveSteps > 0) {
                double goalCenterX = (double) goalBedX + 0.5D - camX;
                double goalCenterY = (double) goalBedY + BED_MATTRESS_HEIGHT * 0.5D - camY;
                double goalCenterZ = (double) goalBedZ + 0.5D - camZ;

                GL11.glLineWidth(PATH_GUIDE_LINE_WIDTH);
                wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                if (effectiveSteps == 0) {
                    if (hasEntry) {
                        emitLine(
                            wr,
                            entryX - camX, entryY - camY, entryZ - camZ,
                            goalCenterX, goalCenterY, goalCenterZ,
                            BED_GOAL_COLOR,
                            230
                        );
                    }
                } else {
                    if (hasEntry) {
                        emitLine(
                            wr,
                            entryX - camX, entryY - camY, entryZ - camZ,
                            (double) stepX[0] + 0.5D - camX,
                            (double) stepY[0] + 0.5D - camY,
                            (double) stepZ[0] + 0.5D - camZ,
                            accentArgb,
                            235
                        );
                    }
                    for (int i = 0; i < effectiveSteps - 1; i++) {
                        emitLine(
                            wr,
                            (double) stepX[i] + 0.5D - camX,
                            (double) stepY[i] + 0.5D - camY,
                            (double) stepZ[i] + 0.5D - camZ,
                            (double) stepX[i + 1] + 0.5D - camX,
                            (double) stepY[i + 1] + 0.5D - camY,
                            (double) stepZ[i + 1] + 0.5D - camZ,
                            accentArgb,
                            215
                        );
                        flushLinesIfNeeded(tessellator, wr);
                    }
                    emitLine(
                        wr,
                        (double) stepX[effectiveSteps - 1] + 0.5D - camX,
                        (double) stepY[effectiveSteps - 1] + 0.5D - camY,
                        (double) stepZ[effectiveSteps - 1] + 0.5D - camZ,
                        goalCenterX, goalCenterY, goalCenterZ,
                        BED_GOAL_COLOR,
                        230
                    );
                }
                tessellator.draw();
            }
        } finally {
            restoreOverlayRenderState(
                prevActiveTex, wasDepth, wasBlend, wasTexture2D, wasCull,
                wasLighting, wasAlpha, wasLineSmooth, prevDepthMask, prevDepthFunc,
                prevLineWidth, prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha
            );
        }
    }

    /**
     * Renders restrained depth-tested perimeter rim lines and subtle downward void-pit veils
     * around detected 1- to 3-block road gaps.
     *
     * @param detector     active void pit detector
     * @param maxRange     configured detection radius for distance fade
     * @param glowArgb     theme warning color tint for subtle surface/veil glow
     * @param rimArgb      theme danger color tint for crisp outer rim lines
     * @param partialTicks partial render tick
     */
    public static void renderVoidPits(
        VoidPitDetector detector,
        double maxRange,
        int glowArgb,
        int rimArgb,
        float partialTicks
    ) {
        if (detector == null || !detector.hasPits()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.getRenderManager() == null) {
            return;
        }
        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null) {
            viewEntity = mc.thePlayer;
        }
        double camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * (double) partialTicks;
        double camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * (double) partialTicks;
        double camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * (double) partialTicks;

        int pitCount = detector.getPitCount();
        int[] pitX = detector.getPitXArray();
        int[] pitY = detector.getPitYArray();
        int[] pitZ = detector.getPitZArray();
        byte[] pitEdgeMask = detector.getPitEdgeMaskArray();
        double[] pitDistSq = detector.getPitDistSqArray();

        double effectiveMax = maxRange > 1.0D ? maxRange : 6.0D;
        double fadeStart = effectiveMax * 0.60D;
        double fadeSpan = Math.max(0.5D, effectiveMax - fadeStart);

        ContextCapabilities caps = GLContext.getCapabilities();
        int prevActiveTex = (caps != null && caps.OpenGL13)
            ? GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
            : OpenGlHelper.defaultTexUnit;
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
        int prevBlendSrcAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
            : prevBlendSrc;
        int prevBlendDstAlpha = (caps != null && caps.OpenGL14)
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
            : prevBlendDst;

        try {
            applyOverlayRenderState(false);
            syncCullFace(false);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            // Pass 1: subtle horizontal surface sheen + downward gradient edge veil.
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < pitCount; i++) {
                double dist = Math.sqrt(pitDistSq[i]);
                float fade = computePitDistanceFade(dist, fadeStart, fadeSpan);
                if (fade <= 0.02F) {
                    continue;
                }

                double topY = (double) pitY[i] + 1.005D - camY;
                double veilBottomY = topY - 0.55D;
                double minX = (double) pitX[i] + 0.02D - camX;
                double maxX = (double) pitX[i] + 0.98D - camX;
                double minZ = (double) pitZ[i] + 0.02D - camZ;
                double maxZ = (double) pitZ[i] + 0.98D - camZ;

                int planeAlpha = Math.round(28.0F * fade);
                if (planeAlpha > 0) {
                    emitHorizontalPlane(wr, minX, topY, minZ, maxX, maxZ, glowArgb, planeAlpha);
                }

                byte mask = pitEdgeMask[i];
                int skirtColor = dist < 5.0D ? rimArgb : glowArgb;
                int skirtAlpha = Math.round(58.0F * fade);
                if (skirtAlpha > 0 && mask != 0) {
                    if ((mask & VoidPitDetector.EDGE_NORTH) != 0) {
                        emitVerticalFadeQuad(wr, minX, minZ, maxX, minZ, topY, veilBottomY, skirtColor, skirtAlpha);
                    }
                    if ((mask & VoidPitDetector.EDGE_SOUTH) != 0) {
                        emitVerticalFadeQuad(wr, minX, maxZ, maxX, maxZ, topY, veilBottomY, skirtColor, skirtAlpha);
                    }
                    if ((mask & VoidPitDetector.EDGE_WEST) != 0) {
                        emitVerticalFadeQuad(wr, minX, minZ, minX, maxZ, topY, veilBottomY, skirtColor, skirtAlpha);
                    }
                    if ((mask & VoidPitDetector.EDGE_EAST) != 0) {
                        emitVerticalFadeQuad(wr, maxX, minZ, maxX, maxZ, topY, veilBottomY, skirtColor, skirtAlpha);
                    }
                }
                flushQuadsIfNeeded(tessellator, wr);
            }
            tessellator.draw();

            // Pass 2: crisp outer perimeter rim lines.
            GL11.glLineWidth(1.6F);
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < pitCount; i++) {
                byte mask = pitEdgeMask[i];
                if (mask == 0) {
                    continue;
                }
                double dist = Math.sqrt(pitDistSq[i]);
                float fade = computePitDistanceFade(dist, fadeStart, fadeSpan);
                if (fade <= 0.02F) {
                    continue;
                }

                double rimY = (double) pitY[i] + 1.007D - camY;
                double minX = (double) pitX[i] + 0.02D - camX;
                double maxX = (double) pitX[i] + 0.98D - camX;
                double minZ = (double) pitZ[i] + 0.02D - camZ;
                double maxZ = (double) pitZ[i] + 0.98D - camZ;

                int rimAlpha = Math.round(190.0F * fade);
                if ((mask & VoidPitDetector.EDGE_NORTH) != 0) {
                    emitLine(wr, minX, rimY, minZ, maxX, rimY, minZ, rimArgb, rimAlpha);
                }
                if ((mask & VoidPitDetector.EDGE_SOUTH) != 0) {
                    emitLine(wr, minX, rimY, maxZ, maxX, rimY, maxZ, rimArgb, rimAlpha);
                }
                if ((mask & VoidPitDetector.EDGE_WEST) != 0) {
                    emitLine(wr, minX, rimY, minZ, minX, rimY, maxZ, rimArgb, rimAlpha);
                }
                if ((mask & VoidPitDetector.EDGE_EAST) != 0) {
                    emitLine(wr, maxX, rimY, minZ, maxX, rimY, maxZ, rimArgb, rimAlpha);
                }
                flushLinesIfNeeded(tessellator, wr);
            }
            tessellator.draw();

            // Pass 3: subtle inset diagonal caution X inside each void pit cell.
            GL11.glLineWidth(1.0F);
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < pitCount; i++) {
                double dist = Math.sqrt(pitDistSq[i]);
                float fade = computePitDistanceFade(dist, fadeStart, fadeSpan);
                if (fade <= 0.02F) {
                    continue;
                }

                double rimY = (double) pitY[i] + 1.007D - camY;
                double x0 = (double) pitX[i] + 0.18D - camX;
                double x1 = (double) pitX[i] + 0.82D - camX;
                double z0 = (double) pitZ[i] + 0.18D - camZ;
                double z1 = (double) pitZ[i] + 0.82D - camZ;

                int crossAlpha = Math.round(70.0F * fade);
                emitLine(wr, x0, rimY, z0, x1, rimY, z1, glowArgb, crossAlpha);
                emitLine(wr, x1, rimY, z0, x0, rimY, z1, glowArgb, crossAlpha);
                flushLinesIfNeeded(tessellator, wr);
            }
            tessellator.draw();
        } finally {
            restoreOverlayRenderState(
                prevActiveTex, wasDepth, wasBlend, wasTexture2D, wasCull,
                wasLighting, wasAlpha, wasLineSmooth, prevDepthMask, prevDepthFunc,
                prevLineWidth, prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha
            );
        }
    }

    private static float computePitDistanceFade(double dist, double fadeStart, double fadeSpan) {
        if (dist <= fadeStart) {
            return 1.0F;
        }
        double t = 1.0D - (dist - fadeStart) / fadeSpan;
        if (t <= 0.0D) {
            return 0.0F;
        }
        return t >= 1.0D ? 1.0F : (float) t;
    }

    private static void emitHorizontalPlane(
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
        wr.pos(minX, y, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, y, maxZ).color(r, g, b, alpha).endVertex();
        wr.pos(maxX, y, minZ).color(r, g, b, alpha).endVertex();
        wr.pos(minX, y, minZ).color(r, g, b, alpha).endVertex();
    }

    private static void emitVerticalFadeQuad(
        WorldRenderer wr,
        double x1,
        double z1,
        double x2,
        double z2,
        double topY,
        double bottomY,
        int argb,
        int topAlpha
    ) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        wr.pos(x1, topY, z1).color(r, g, b, topAlpha).endVertex();
        wr.pos(x2, topY, z2).color(r, g, b, topAlpha).endVertex();
        wr.pos(x2, bottomY, z2).color(r, g, b, 0).endVertex();
        wr.pos(x1, bottomY, z1).color(r, g, b, 0).endVertex();
    }

    private static void applyOverlayRenderState(boolean throughWalls) {
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
    }

    private static void restoreOverlayRenderState(
        int prevActiveTex,
        boolean wasDepth,
        boolean wasBlend,
        boolean wasTexture2D,
        boolean wasCull,
        boolean wasLighting,
        boolean wasAlpha,
        boolean wasLineSmooth,
        boolean prevDepthMask,
        int prevDepthFunc,
        float prevLineWidth,
        int prevBlendSrc,
        int prevBlendDst,
        int prevBlendSrcAlpha,
        int prevBlendDstAlpha
    ) {
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
        if (prevActiveTex >= OpenGlHelper.defaultTexUnit && prevActiveTex < OpenGlHelper.defaultTexUnit + 8) {
            GlStateManager.setActiveTexture(prevActiveTex);
        } else {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
        GlStateManager.tryBlendFuncSeparate(prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha);
        syncBlend(wasBlend);
        GlStateManager.resetColor();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
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

        emitLineRgb(wr, minX, minY, minZ, maxX, minY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, minX, minY, maxZ, minX, minY, minZ, r, g, b, alpha);

        emitLineRgb(wr, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, alpha);

        emitLineRgb(wr, minX, minY, minZ, minX, maxY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        emitLineRgb(wr, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, alpha);
        emitLineRgb(wr, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
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
}
