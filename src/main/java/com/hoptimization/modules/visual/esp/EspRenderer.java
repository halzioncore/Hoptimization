package com.hoptimization.modules.visual.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

import java.util.List;

/**
 * World-space futuristic mech-cockpit ESP renderer with two-layer depth passes,
 * GLSL 120 facet shading with fixed-function fallback, and zero hot-path allocations.
 */
public final class EspRenderer {
    public static final int STYLE_COCKPIT = 0;
    public static final int STYLE_OVERLAY = 1;
    public static final int STYLE_WIREFRAME = 2;
    public static final double MAX_RANGE_SQ = 128.0D * 128.0D;

    private static final float MODEL_SCALE = 0.0625F;
    private static final float PLAYER_PRE_SCALE = 0.9375F;
    private static final float PLAYER_BASE_OFFSET_Y = -1.5078125F;
    private static final float OVERLAY_EXPAND = 1.04F;
    private static final float RAD_TO_DEG = 57.295776F;
    private static final float GROUND_RING_RADIUS = 0.58F;
    private static final float GROUND_RING_INNER_SCALE = 0.80F;

    private static final float LIGHT_MODEL_X = -0.3196F;
    private static final float LIGHT_MODEL_Y = -0.7791F;
    private static final float LIGHT_MODEL_Z = -0.5394F;

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

    private static final VertexFormat POSITION_COLOR_NORMAL = new VertexFormat()
        .addElement(DefaultVertexFormats.POSITION_3F)
        .addElement(DefaultVertexFormats.COLOR_4UB)
        .addElement(DefaultVertexFormats.NORMAL_3B)
        .addElement(DefaultVertexFormats.PADDING_1B);

    private static final TargetRenderState[] TARGET_STATES = new TargetRenderState[MAX_TARGETS];
    private static final long START_NANOS = System.nanoTime();

    static {
        for (int i = 0; i < MAX_TARGETS; i++) {
            TARGET_STATES[i] = new TargetRenderState();
        }
    }

    private EspRenderer() {
    }

    /**
     * Renders ESP geometry for all eligible players in world space.
     *
     * @param policy       module target eligibility and color policy
     * @param styleMode    {@link #STYLE_COCKPIT}, {@link #STYLE_OVERLAY}, or {@link #STYLE_WIREFRAME}
     * @param throughWalls {@code true} to render occluded pass behind terrain/walls
     * @param fillOpacity  hull fill opacity in {@code [0, 1]}
     * @param lineWidth    wireframe stroke width in pixels
     * @param scanlines    {@code true} to enable animated holographic scanlines in the shader
     * @param groundRing   {@code true} to draw octagonal tactical ground rings under targets
     * @param partialTicks current render partial ticks
     */
    public static void render(
        EspTargetPolicy policy,
        int styleMode,
        boolean throughWalls,
        float fillOpacity,
        float lineWidth,
        boolean scanlines,
        boolean groundRing,
        float partialTicks
    ) {
        if (policy == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
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

        int targetCount = collectVisibleTargets(
            policy, mc, players, throughWalls, partialTicks, camX, camY, camZ
        );
        if (targetCount == 0) {
            return;
        }

        int resolvedStyle = normalizeStyle(styleMode);
        float clampedFill = clamp01(fillOpacity);
        float safeLineWidth = sanitizeLineWidth(lineWidth);
        float timeSeconds = (float) (((System.nanoTime() - START_NANOS) % 3_600_000_000_000L) * 1.0E-9D);

        ContextCapabilities caps = GLContext.getCapabilities();
        boolean hasGl14 = caps != null && caps.OpenGL14;
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int prevBlendSrcRgb = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
            : GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDstRgb = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
            : GL11.glGetInteger(GL11.GL_BLEND_DST);
        int prevBlendSrcAlpha = hasGl14 ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA) : prevBlendSrcRgb;
        int prevBlendDstAlpha = hasGl14 ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA) : prevBlendDstRgb;

        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int prevCullMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        boolean wasPolygonOffsetFill = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        float prevPolyFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        float prevPolyUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        int prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        float prevLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        boolean wasFog = GL11.glIsEnabled(GL11.GL_FOG);
        boolean wasLineSmooth = GL11.glIsEnabled(GL11.GL_LINE_SMOOTH);

        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableFog();
            GlStateManager.enableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.enableCull();
            GlStateManager.cullFace(GL11.GL_BACK);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);

            // Pass 1 (occluded) MUST execute before Pass 2 depth writes so GL_GREATER stays intact.
            if (throughWalls) {
                renderOccludedPass(
                    targetCount, resolvedStyle, clampedFill, safeLineWidth,
                    scanlines, groundRing, timeSeconds
                );
            }

            renderVisiblePass(
                targetCount, resolvedStyle, clampedFill, safeLineWidth,
                scanlines, groundRing, timeSeconds
            );
        } finally {
            EspMechaShader.end();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.doPolygonOffset(prevPolyFactor, prevPolyUnits);
            if (wasPolygonOffsetFill) {
                GlStateManager.enablePolygonOffset();
            } else {
                GlStateManager.disablePolygonOffset();
            }
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
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if (wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
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
            if (wasLineSmooth) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
            } else {
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
            }
            GlStateManager.shadeModel(prevShadeModel);
            GL11.glLineWidth(prevLineWidth);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * Resets transient renderer and shader pass state on module disable or world unload.
     */
    public static void resetState() {
        EspMechaShader.end();
    }

    private static int collectVisibleTargets(
        EspTargetPolicy policy,
        Minecraft mc,
        List<EntityPlayer> players,
        boolean throughWalls,
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

            TargetRenderState state = TARGET_STATES[count];
            populateTargetState(state, policy, mc, player, throughWalls, partialTicks, dx, dy, dz);
            count++;
        }
        return count;
    }

    private static void populateTargetState(
        TargetRenderState state,
        EspTargetPolicy policy,
        Minecraft mc,
        EntityPlayer player,
        boolean throughWalls,
        float partialTicks,
        double dx,
        double dy,
        double dz
    ) {
        double modelDy = dy;
        if (player.isSneaking() && !(player instanceof EntityPlayerSP)) {
            modelDy -= 0.125D;
        }
        boolean sleeping = player.isEntityAlive() && player.isPlayerSleeping();
        if (sleeping) {
            dx += player.renderOffsetX;
            modelDy += player.renderOffsetY;
            dz += player.renderOffsetZ;
        }

        state.dx = dx;
        state.dy = modelDy;
        state.dz = dz;
        state.ringDy = dy + 0.02D;

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

        state.sleeping = sleeping;
        state.bedOrientationDeg = sleeping ? player.getBedOrientationInDegrees() : 0.0F;
        state.bodyYaw = bodyYaw;
        state.deathTiltDeg = 0.0F;
        state.flipped = false;
        if (!sleeping) {
            if (player.deathTime > 0) {
                float f = ((float) player.deathTime + partialTicks - 1.0F) / 20.0F * 1.6F;
                f = MathHelper.sqrt_float(f);
                if (f > 1.0F) {
                    f = 1.0F;
                }
                state.deathTiltDeg = f * 90.0F;
            } else if (isDinnerboneOrGrumm(player.getName()) && player.isWearing(EnumPlayerModelParts.CAPE)) {
                state.flipped = true;
            }
        }
        state.entityHeight = player.height;

        boolean slimArms = false;
        if (player instanceof AbstractClientPlayer) {
            if (mc.getNetHandler() != null) {
                slimArms = "slim".equals(((AbstractClientPlayer) player).getSkinType());
            } else {
                slimArms = "slim".equals(DefaultPlayerSkin.getSkinType(player.getUniqueID()));
            }
        }
        state.slimArms = slimArms;
        state.sneak = player.isSneaking();

        POSE_MODEL.swingProgress = player.getSwingProgress(partialTicks);
        POSE_MODEL.isRiding = riding;
        POSE_MODEL.isChild = isChild;
        POSE_MODEL.isSneak = state.sneak;
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

        state.capturePart(PART_HEAD, POSE_MODEL.bipedHead);
        state.capturePart(PART_TORSO, POSE_MODEL.bipedBody);
        state.capturePart(PART_RIGHT_ARM, POSE_MODEL.bipedRightArm);
        state.capturePart(PART_LEFT_ARM, POSE_MODEL.bipedLeftArm);
        state.capturePart(PART_RIGHT_LEG, POSE_MODEL.bipedRightLeg);
        state.capturePart(PART_LEFT_LEG, POSE_MODEL.bipedLeftLeg);

        state.visibleArgb = policy.resolveTargetColorArgb(player, false);
        state.occludedArgb = throughWalls ? policy.resolveTargetColorArgb(player, true) : state.visibleArgb;
    }

    private static void renderOccludedPass(
        int targetCount,
        int styleMode,
        float fillOpacity,
        float lineWidth,
        boolean scanlines,
        boolean groundRing,
        float timeSeconds
    ) {
        float partExpand = (styleMode == STYLE_OVERLAY) ? OVERLAY_EXPAND : 1.0F;
        float fillAlpha;
        if (styleMode == STYLE_WIREFRAME) {
            fillAlpha = 0.06F;
        } else {
            fillAlpha = (fillOpacity > 0.02F) ? Math.min(0.28F, fillOpacity * 0.35F) : 0.0F;
        }

        GlStateManager.depthFunc(GL11.GL_GREATER);
        GlStateManager.depthMask(false);
        GlStateManager.enableCull();

        if (fillAlpha > 0.005F) {
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -1.0F);
            boolean usingShader = EspMechaShader.begin(
                timeSeconds,
                scanlines ? 0.35F : 0.0F,
                0.45F,
                0.16F
            );
            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawTargetFilledHulls(state, state.occludedArgb, fillAlpha, partExpand, usingShader, 0.16F);
            }
            EspMechaShader.end();
        }

        GlStateManager.disablePolygonOffset();
        GL11.glLineWidth(lineWidth);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        float skeletonAlpha = (styleMode == STYLE_WIREFRAME) ? 0.65F : 0.42F;
        for (int i = 0; i < targetCount; i++) {
            TargetRenderState state = TARGET_STATES[i];
            drawTargetLines(state, state.occludedArgb, 0.82F, 0.85F, skeletonAlpha, partExpand);
        }

        if (groundRing) {
            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawGroundRing(state, state.occludedArgb, 0.48F);
            }
        }
    }

    private static void renderVisiblePass(
        int targetCount,
        int styleMode,
        float fillOpacity,
        float lineWidth,
        boolean scanlines,
        boolean groundRing,
        float timeSeconds
    ) {
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.enableCull();

        if (styleMode == STYLE_COCKPIT) {
            if (fillOpacity > 0.02F) {
                // Depth-only prepass makes opaque/translucent armor limbs self-occlude with zero overlap artifacts.
                GlStateManager.colorMask(false, false, false, false);
                GlStateManager.depthMask(true);
                GlStateManager.disableBlend();
                GlStateManager.enablePolygonOffset();
                GlStateManager.doPolygonOffset(1.0F, 1.0F);
                for (int i = 0; i < targetCount; i++) {
                    drawTargetDepthOnly(TARGET_STATES[i], 1.0F);
                }

                GlStateManager.colorMask(true, true, true, true);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO
                );
                GlStateManager.depthMask(false);
                GlStateManager.depthFunc(GL11.GL_LEQUAL);

                boolean usingShader = EspMechaShader.begin(
                    timeSeconds,
                    scanlines ? 0.55F : 0.0F,
                    0.72F,
                    0.26F
                );
                for (int i = 0; i < targetCount; i++) {
                    TargetRenderState state = TARGET_STATES[i];
                    drawTargetFilledHulls(state, state.visibleArgb, fillOpacity, 1.0F, usingShader, 0.26F);
                }
                EspMechaShader.end();
                GlStateManager.disablePolygonOffset();

                float accentFillAlpha = Math.max(0.88F, fillOpacity);
                for (int i = 0; i < targetCount; i++) {
                    TargetRenderState state = TARGET_STATES[i];
                    drawTargetAccentFills(state, state.visibleArgb, accentFillAlpha, 1.0F);
                }
            }

            GlStateManager.disablePolygonOffset();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GL11.glLineWidth(lineWidth);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);

            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawTargetLines(state, state.visibleArgb, 0.94F, 0.98F, 0.48F, 1.0F);
            }
        } else if (styleMode == STYLE_OVERLAY) {
            float overlayAlpha = fillOpacity * 0.48F;
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();

            if (overlayAlpha > 0.01F) {
                GlStateManager.enablePolygonOffset();
                GlStateManager.doPolygonOffset(-1.0F, -1.0F);
                boolean usingShader = EspMechaShader.begin(
                    timeSeconds,
                    scanlines ? 0.50F : 0.0F,
                    0.78F,
                    0.22F
                );
                for (int i = 0; i < targetCount; i++) {
                    TargetRenderState state = TARGET_STATES[i];
                    drawTargetFilledHulls(state, state.visibleArgb, overlayAlpha, OVERLAY_EXPAND, usingShader, 0.22F);
                }
                EspMechaShader.end();

                for (int i = 0; i < targetCount; i++) {
                    TargetRenderState state = TARGET_STATES[i];
                    drawTargetAccentFills(state, state.visibleArgb, 0.85F, OVERLAY_EXPAND);
                }
            }

            GlStateManager.disablePolygonOffset();
            GL11.glLineWidth(lineWidth);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);

            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawTargetLines(state, state.visibleArgb, 0.95F, 0.98F, 0.40F, OVERLAY_EXPAND);
            }
        } else {
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(1.0F, 1.0F);

            boolean usingShader = EspMechaShader.begin(
                timeSeconds,
                scanlines ? 0.35F : 0.0F,
                0.65F,
                0.18F
            );
            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawTargetFilledHulls(state, state.visibleArgb, 0.08F, 1.0F, usingShader, 0.18F);
            }
            EspMechaShader.end();

            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawTargetAccentFills(state, state.visibleArgb, 0.65F, 1.0F);
            }

            GlStateManager.disablePolygonOffset();
            GL11.glLineWidth(lineWidth);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);

            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawTargetLines(state, state.visibleArgb, 0.98F, 1.00F, 0.74F, 1.0F);
            }
        }

        if (groundRing) {
            for (int i = 0; i < targetCount; i++) {
                TargetRenderState state = TARGET_STATES[i];
                drawGroundRing(state, state.visibleArgb, 0.86F);
            }
        }
    }

    private static void pushPlayerBaseTransform(TargetRenderState state) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(state.dx, state.dy, state.dz);
        if (state.sleeping) {
            GlStateManager.rotate(state.bedOrientationDeg, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);
            GlStateManager.rotate(270.0F, 0.0F, 1.0F, 0.0F);
        } else {
            GlStateManager.rotate(180.0F - state.bodyYaw, 0.0F, 1.0F, 0.0F);
            if (state.deathTiltDeg > 0.0F) {
                GlStateManager.rotate(state.deathTiltDeg, 0.0F, 0.0F, 1.0F);
            } else if (state.flipped) {
                GlStateManager.translate(0.0F, state.entityHeight + 0.1F, 0.0F);
                GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            }
        }
        GlStateManager.scale(-1.0F, -1.0F, 1.0F);
        GlStateManager.scale(PLAYER_PRE_SCALE, PLAYER_PRE_SCALE, PLAYER_PRE_SCALE);
        GlStateManager.translate(0.0F, PLAYER_BASE_OFFSET_Y, 0.0F);
        if (state.sneak) {
            GlStateManager.translate(0.0F, 0.2F, 0.0F);
        }
    }

    private static void pushPartTransform(
        TargetRenderState state,
        int partIdx,
        EspGeometry.PartMesh mesh,
        float expand
    ) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(
            state.rotPtX[partIdx] * MODEL_SCALE,
            state.rotPtY[partIdx] * MODEL_SCALE,
            state.rotPtZ[partIdx] * MODEL_SCALE
        );
        float rz = state.rotZ[partIdx];
        if (rz != 0.0F) {
            GlStateManager.rotate(rz * RAD_TO_DEG, 0.0F, 0.0F, 1.0F);
        }
        float ry = state.rotY[partIdx];
        if (ry != 0.0F) {
            GlStateManager.rotate(ry * RAD_TO_DEG, 0.0F, 1.0F, 0.0F);
        }
        float rx = state.rotX[partIdx];
        if (rx != 0.0F) {
            GlStateManager.rotate(rx * RAD_TO_DEG, 1.0F, 0.0F, 0.0F);
        }
        GlStateManager.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        if (expand != 1.0F) {
            GlStateManager.translate(mesh.centerX, mesh.centerY, mesh.centerZ);
            GlStateManager.scale(expand, expand, expand);
            GlStateManager.translate(-mesh.centerX, -mesh.centerY, -mesh.centerZ);
        }
    }

    private static void drawTargetDepthOnly(TargetRenderState state, float expand) {
        pushPlayerBaseTransform(state);
        for (int p = 0; p < PART_COUNT; p++) {
            EspGeometry.PartMesh mesh = selectPartMesh(p, state.slimArms);
            pushPartTransform(state, p, mesh, expand);
            drawMeshDepthOnly(mesh);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    private static void drawTargetFilledHulls(
        TargetRenderState state,
        int argb,
        float alpha,
        float expand,
        boolean usingShader,
        float shadeContrast
    ) {
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        pushPlayerBaseTransform(state);
        for (int p = 0; p < PART_COUNT; p++) {
            EspGeometry.PartMesh mesh = selectPartMesh(p, state.slimArms);
            pushPartTransform(state, p, mesh, expand);
            drawMeshTriangles(mesh, r, g, b, alpha, usingShader, shadeContrast);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    private static void drawTargetAccentFills(TargetRenderState state, int argb, float alpha, float expand) {
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float glowR = r + (1.0F - r) * 0.72F;
        float glowG = g + (1.0F - g) * 0.72F;
        float glowB = b + (1.0F - b) * 0.72F;

        pushPlayerBaseTransform(state);
        for (int p = 0; p < PART_COUNT; p++) {
            EspGeometry.PartMesh mesh = selectPartMesh(p, state.slimArms);
            if (mesh.accentTriCount <= 0) {
                continue;
            }
            pushPartTransform(state, p, mesh, expand);
            drawAccentTriangles(mesh, glowR, glowG, glowB, alpha);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    private static void drawTargetLines(
        TargetRenderState state,
        int argb,
        float contourAlpha,
        float accentAlpha,
        float skeletonAlpha,
        float expand
    ) {
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        float contourR = r + (1.0F - r) * 0.32F;
        float contourG = g + (1.0F - g) * 0.32F;
        float contourB = b + (1.0F - b) * 0.32F;

        float accentR = r + (1.0F - r) * 0.75F;
        float accentG = g + (1.0F - g) * 0.75F;
        float accentB = b + (1.0F - b) * 0.75F;

        float skelR = r + (1.0F - r) * 0.48F;
        float skelG = g + (1.0F - g) * 0.48F;
        float skelB = b + (1.0F - b) * 0.48F;

        pushPlayerBaseTransform(state);
        for (int p = 0; p < PART_COUNT; p++) {
            EspGeometry.PartMesh mesh = selectPartMesh(p, state.slimArms);
            pushPartTransform(state, p, mesh, expand);
            drawMeshLineBatch(
                mesh,
                contourR, contourG, contourB, contourAlpha,
                accentR, accentG, accentB, accentAlpha,
                skelR, skelG, skelB, skeletonAlpha
            );
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    private static void drawMeshDepthOnly(EspGeometry.PartMesh mesh) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION);
        float[] verts = mesh.triVerts;
        int triCount = mesh.triCount;
        for (int t = 0; t < triCount; t++) {
            int v = t * 9;
            wr.pos(verts[v], verts[v + 1], verts[v + 2]).endVertex();
            wr.pos(verts[v + 3], verts[v + 4], verts[v + 5]).endVertex();
            wr.pos(verts[v + 6], verts[v + 7], verts[v + 8]).endVertex();
        }
        tess.draw();
    }

    private static void drawMeshTriangles(
        EspGeometry.PartMesh mesh,
        float r,
        float g,
        float b,
        float alpha,
        boolean usingShader,
        float shadeContrast
    ) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLES, POSITION_COLOR_NORMAL);

        float[] verts = mesh.triVerts;
        float[] normals = mesh.triNormals;
        int triCount = mesh.triCount;
        float floorShade = clamp(1.0F - clamp(shadeContrast, 0.0F, 0.45F), 0.55F, 1.0F);

        for (int t = 0; t < triCount; t++) {
            int n = t * 3;
            float nx = normals[n];
            float ny = normals[n + 1];
            float nz = normals[n + 2];

            float outR = r;
            float outG = g;
            float outB = b;
            if (!usingShader) {
                float shade = evaluateFallbackFacetShade(nx, ny, nz, floorShade);
                outR = clamp01(r * shade);
                outG = clamp01(g * shade);
                outB = clamp01(b * shade);
            }

            int v = t * 9;
            wr.pos(verts[v], verts[v + 1], verts[v + 2])
                .color(outR, outG, outB, alpha)
                .normal(nx, ny, nz)
                .endVertex();
            wr.pos(verts[v + 3], verts[v + 4], verts[v + 5])
                .color(outR, outG, outB, alpha)
                .normal(nx, ny, nz)
                .endVertex();
            wr.pos(verts[v + 6], verts[v + 7], verts[v + 8])
                .color(outR, outG, outB, alpha)
                .normal(nx, ny, nz)
                .endVertex();
        }
        tess.draw();
    }

    private static void drawAccentTriangles(
        EspGeometry.PartMesh mesh,
        float r,
        float g,
        float b,
        float alpha
    ) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        float[] verts = mesh.accentTriVerts;
        int triCount = mesh.accentTriCount;
        for (int t = 0; t < triCount; t++) {
            int v = t * 9;
            wr.pos(verts[v], verts[v + 1], verts[v + 2]).color(r, g, b, alpha).endVertex();
            wr.pos(verts[v + 3], verts[v + 4], verts[v + 5]).color(r, g, b, alpha).endVertex();
            wr.pos(verts[v + 6], verts[v + 7], verts[v + 8]).color(r, g, b, alpha).endVertex();
        }
        tess.draw();
    }

    private static void drawMeshLineBatch(
        EspGeometry.PartMesh mesh,
        float contourR, float contourG, float contourB, float contourA,
        float accentR, float accentG, float accentB, float accentA,
        float skelR, float skelG, float skelB, float skelA
    ) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        emitLineSegments(wr, mesh.edgeLines, mesh.edgeLineCount, contourR, contourG, contourB, contourA);
        emitLineSegments(wr, mesh.accentLines, mesh.accentLineCount, accentR, accentG, accentB, accentA);
        emitLineSegments(wr, mesh.skeletonLines, mesh.skeletonLineCount, skelR, skelG, skelB, skelA);

        tess.draw();
    }

    private static void emitLineSegments(
        WorldRenderer wr,
        float[] lines,
        int segmentCount,
        float r,
        float g,
        float b,
        float a
    ) {
        for (int i = 0; i < segmentCount; i++) {
            int idx = i * 6;
            wr.pos(lines[idx], lines[idx + 1], lines[idx + 2]).color(r, g, b, a).endVertex();
            wr.pos(lines[idx + 3], lines[idx + 4], lines[idx + 5]).color(r, g, b, a).endVertex();
        }
    }

    private static void drawGroundRing(TargetRenderState state, int argb, float alpha) {
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float ringR = r + (1.0F - r) * 0.35F;
        float ringG = g + (1.0F - g) * 0.35F;
        float ringB = b + (1.0F - b) * 0.35F;

        double cx = state.dx;
        double cy = state.ringDy;
        double cz = state.dz;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        float[] cos = EspGeometry.GROUND_RING_COS;
        float[] sin = EspGeometry.GROUND_RING_SIN;
        for (int i = 0; i < EspGeometry.GROUND_RING_SEGMENTS; i++) {
            double x0 = cx + cos[i] * GROUND_RING_RADIUS;
            double z0 = cz + sin[i] * GROUND_RING_RADIUS;
            double x1 = cx + cos[i + 1] * GROUND_RING_RADIUS;
            double z1 = cz + sin[i + 1] * GROUND_RING_RADIUS;
            wr.pos(x0, cy, z0).color(ringR, ringG, ringB, alpha).endVertex();
            wr.pos(x1, cy, z1).color(ringR, ringG, ringB, alpha).endVertex();

            double ix0 = cx + cos[i] * (GROUND_RING_RADIUS * GROUND_RING_INNER_SCALE);
            double iz0 = cz + sin[i] * (GROUND_RING_RADIUS * GROUND_RING_INNER_SCALE);
            double ix1 = cx + cos[i + 1] * (GROUND_RING_RADIUS * GROUND_RING_INNER_SCALE);
            double iz1 = cz + sin[i + 1] * (GROUND_RING_RADIUS * GROUND_RING_INNER_SCALE);
            wr.pos(ix0, cy, iz0).color(ringR, ringG, ringB, alpha * 0.42F).endVertex();
            wr.pos(ix1, cy, iz1).color(ringR, ringG, ringB, alpha * 0.42F).endVertex();
        }

        float[] tickX = EspGeometry.GROUND_TICK_X;
        float[] tickZ = EspGeometry.GROUND_TICK_Z;
        for (int i = 0; i < 4; i++) {
            double x0 = cx + tickX[i * 2] * GROUND_RING_RADIUS;
            double z0 = cz + tickZ[i * 2] * GROUND_RING_RADIUS;
            double x1 = cx + tickX[i * 2 + 1] * GROUND_RING_RADIUS;
            double z1 = cz + tickZ[i * 2 + 1] * GROUND_RING_RADIUS;
            wr.pos(x0, cy, z0).color(ringR, ringG, ringB, alpha).endVertex();
            wr.pos(x1, cy, z1).color(ringR, ringG, ringB, alpha).endVertex();
        }

        tess.draw();
    }

    private static EspGeometry.PartMesh selectPartMesh(int partIndex, boolean slimArms) {
        switch (partIndex) {
            case PART_HEAD:
                return EspGeometry.HEAD_MESH;
            case PART_TORSO:
                return EspGeometry.TORSO_MESH;
            case PART_RIGHT_ARM:
                return slimArms ? EspGeometry.RIGHT_ARM_SLIM_MESH : EspGeometry.RIGHT_ARM_DEFAULT_MESH;
            case PART_LEFT_ARM:
                return slimArms ? EspGeometry.LEFT_ARM_SLIM_MESH : EspGeometry.LEFT_ARM_DEFAULT_MESH;
            case PART_RIGHT_LEG:
                return EspGeometry.RIGHT_LEG_MESH;
            case PART_LEFT_LEG:
                return EspGeometry.LEFT_LEG_MESH;
            default:
                return EspGeometry.TORSO_MESH;
        }
    }

    private static float evaluateFallbackFacetShade(float nx, float ny, float nz, float floorShade) {
        float ndotl = nx * LIGHT_MODEL_X + ny * LIGHT_MODEL_Y + nz * LIGHT_MODEL_Z;
        float rawBand = clamp01(ndotl * 0.5F + 0.5F);
        float s = clamp01((rawBand - 0.32F) / (0.66F - 0.32F));
        float smooth = s * s * (3.0F - 2.0F * s);
        float twoTone = rawBand + (smooth - rawBand) * 0.72F;
        return floorShade + (1.04F - floorShade) * twoTone;
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

    private static int normalizeStyle(int styleMode) {
        if (styleMode == STYLE_OVERLAY || styleMode == STYLE_WIREFRAME) {
            return styleMode;
        }
        return STYLE_COCKPIT;
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

    private static float clamp(float value, float min, float max) {
        if (value <= min || Float.isNaN(value)) {
            return min;
        }
        if (value >= max) {
            return max;
        }
        return value;
    }

    /**
     * Pre-allocated per-frame target transform and solved limb pose cache.
     */
    private static final class TargetRenderState {
        double dx;
        double dy;
        double dz;
        double ringDy;
        float bodyYaw;
        float deathTiltDeg;
        float bedOrientationDeg;
        float entityHeight;
        boolean sleeping;
        boolean flipped;
        boolean slimArms;
        boolean sneak;
        int visibleArgb;
        int occludedArgb;

        final float[] rotPtX = new float[PART_COUNT];
        final float[] rotPtY = new float[PART_COUNT];
        final float[] rotPtZ = new float[PART_COUNT];
        final float[] rotX = new float[PART_COUNT];
        final float[] rotY = new float[PART_COUNT];
        final float[] rotZ = new float[PART_COUNT];

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
