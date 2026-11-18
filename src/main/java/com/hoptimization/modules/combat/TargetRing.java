package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTBlendFuncSeparate;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

/**
 * Combat feedback module rendering a single-target lock ring with damped hit impulse,
 * angular acceleration burst, color flash, and optional shockwave ripple.
 */
public class TargetRing extends Module {
    public static final String ID = "target_ring";

    public static final String STYLE_PULSE_RING = "pulse_ring";
    public static final String STYLE_BLADE_ORBIT = "blade_orbit";
    public static final String STYLE_SCAN_PILLAR = "scan_pillar";

    private static final double MAX_LOCK_RANGE = 16.0D;
    private static final double MAX_LOCK_RANGE_SQ = MAX_LOCK_RANGE * MAX_LOCK_RANGE;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;
    private static final float NANOS_TO_SECONDS = 1.0E-9F;
    private static final float MAX_DT_SECONDS = 0.05F;

    private static final long HIT_IMPULSE_NANOS = 260_000_000L;
    private static final long RIPPLE_DURATION_NANOS = 320_000_000L;
    private static final long MIN_RIPPLE_INTERVAL_NANOS = 60_000_000L;
    private static final long HURT_EDGE_DEBOUNCE_NANOS = 75_000_000L;
    private static final long FADE_OUT_NANOS = 220_000_000L;

    private static final float BASE_ORBIT_SPEED_RAD = 1.85F;
    private static final float BLADE_ORBIT_SPEED_RAD = 2.65F;
    private static final float SCAN_SPEED_RAD = 3.10F;
    private static final float HIT_SPEED_BOOST = 2.50F; // 1.0 + 2.5 = 3.5x peak angular speed

    private static final int RING_SEGMENTS = 64;
    private static final int BLADE_SEGMENTS = 14;
    private static final int BRACKET_SEGMENTS = 8;
    private static final int TICK_COUNT = 24;
    private static final int MAX_RIPPLES = 4;

    private static final int TRIG_SIZE = 256;
    private static final int TRIG_MASK = TRIG_SIZE - 1;
    private static final float TWO_PI = (float) (Math.PI * 2.0D);
    private static final float RAD_TO_INDEX = TRIG_SIZE / TWO_PI;
    private static final float[] SIN_TABLE = new float[TRIG_SIZE];
    private static final float[] COS_TABLE = new float[TRIG_SIZE];

    static {
        for (int i = 0; i < TRIG_SIZE; i++) {
            double angle = (TWO_PI * i) / TRIG_SIZE;
            SIN_TABLE[i] = (float) Math.sin(angle);
            COS_TABLE[i] = (float) Math.cos(angle);
        }
    }

    private static TargetRing instance;

    private final ModeSetting style;
    private final BooleanSetting playersOnly;
    private final BooleanSetting filterBots;
    private final NumberSetting keepTime;
    private final NumberSetting radiusScale;
    private final NumberSetting lineWidth;
    private final BooleanSetting hitFeedback;
    private final BooleanSetting hitRipple;
    private final BooleanSetting followTheme;
    private final ColorSetting ringColor;
    private final ColorSetting hitColor;

    private int lockedEntityId = -1;
    private EntityLivingBase lockedEntity = null;
    private int lastHurtTime = 0;
    private long lastAttackNanos = 0L;

    private boolean fadingOut = false;
    private long fadeOutStartNanos = 0L;
    private float fadeOutStartAlpha = 0.0F;
    private float fadeAlpha = 0.0F;

    private long hitImpulseStartNanos = 0L;
    private float impulseEntryOffset = 0.0F;
    private long lastRippleNanos = 0L;

    private long lastRenderNanos = 0L;
    private float orbitAngleRad = 0.0F;
    private float scanPhaseRad = 0.0F;

    private double cachedTargetX = 0.0D;
    private double cachedTargetY = 0.0D;
    private double cachedTargetZ = 0.0D;
    private float cachedTargetWidth = 0.6F;
    private float cachedTargetHeight = 1.8F;
    private double cachedRingY = 0.8D;
    private float cachedBaseRadius = 0.65F;

    private final boolean[] rippleActive = new boolean[MAX_RIPPLES];
    private final long[] rippleStartNanos = new long[MAX_RIPPLES];
    private final double[] rippleX = new double[MAX_RIPPLES];
    private final double[] rippleY = new double[MAX_RIPPLES];
    private final double[] rippleZ = new double[MAX_RIPPLES];
    private final float[] rippleBaseRadius = new float[MAX_RIPPLES];
    private final int[] rippleEntityId = new int[MAX_RIPPLES];
    private int nextRippleCursor = 0;

    /**
     * Creates the Target Ring module and registers its settings in display order.
     */
    public TargetRing() {
        super(ID, Category.COMBAT);
        this.style = addSetting(
            new ModeSetting(
                "style",
                "setting.hoptimization.target_ring.style.name",
                STYLE_PULSE_RING,
                STYLE_PULSE_RING,
                STYLE_BLADE_ORBIT,
                STYLE_SCAN_PILLAR
            )
        );
        this.playersOnly = addSetting(
            new BooleanSetting("players_only", "setting.hoptimization.target_ring.players_only.name", false)
        );
        this.filterBots = addSetting(
            new BooleanSetting("filter_bots", "setting.hoptimization.target_ring.filter_bots.name", true)
        );
        this.keepTime = addSetting(
            new NumberSetting(
                "keep_time",
                "setting.hoptimization.target_ring.keep_time.name",
                3.0D,
                1.0D,
                8.0D,
                0.5D,
                "s"
            )
        );
        this.radiusScale = addSetting(
            new NumberSetting(
                "radius_scale",
                "setting.hoptimization.target_ring.radius_scale.name",
                1.0D,
                0.6D,
                1.8D,
                0.05D,
                "x"
            )
        );
        this.lineWidth = addSetting(
            new NumberSetting(
                "line_width",
                "setting.hoptimization.target_ring.line_width.name",
                2.0D,
                1.0D,
                4.0D,
                0.25D
            )
        );
        this.hitFeedback = addSetting(
            new BooleanSetting("hit_feedback", "setting.hoptimization.target_ring.hit_feedback.name", true)
        );
        this.hitRipple = addSetting(
            new BooleanSetting("hit_ripple", "setting.hoptimization.target_ring.hit_ripple.name", true)
                .visibleWhen(() -> Boolean.valueOf(this.hitFeedback.isEnabled()))
        );
        this.followTheme = addSetting(
            new BooleanSetting("follow_theme", "setting.hoptimization.target_ring.follow_theme.name", true)
        );
        this.ringColor = addSetting(
            new ColorSetting("ring_color", "setting.hoptimization.target_ring.ring_color.name", 0xFFD4AF37, true)
                .visibleWhen(() -> Boolean.valueOf(!this.followTheme.isEnabled()))
        );
        this.hitColor = addSetting(
            new ColorSetting("hit_color", "setting.hoptimization.target_ring.hit_color.name", 0xFFFF4D5E, true)
                .visibleWhen(() -> Boolean.valueOf(this.hitFeedback.isEnabled()))
        );
        instance = this;
    }

    /**
     * Returns the shared {@link TargetRing} instance if initialized.
     *
     * @return module instance or null
     */
    public static TargetRing getInstance() {
        return instance;
    }

    public ModeSetting getStyleSetting() {
        return this.style;
    }

    public BooleanSetting getPlayersOnlySetting() {
        return this.playersOnly;
    }

    public BooleanSetting getFilterBotsSetting() {
        return this.filterBots;
    }

    public NumberSetting getKeepTimeSetting() {
        return this.keepTime;
    }

    public NumberSetting getRadiusScaleSetting() {
        return this.radiusScale;
    }

    public NumberSetting getLineWidthSetting() {
        return this.lineWidth;
    }

    public BooleanSetting getHitFeedbackSetting() {
        return this.hitFeedback;
    }

    public BooleanSetting getHitRippleSetting() {
        return this.hitRipple;
    }

    public BooleanSetting getFollowThemeSetting() {
        return this.followTheme;
    }

    public ColorSetting getRingColorSetting() {
        return this.ringColor;
    }

    public ColorSetting getHitColorSetting() {
        return this.hitColor;
    }

    /**
     * Triggers the 260ms damped hit impulse and optional shockwave ripple at timestamp {@code now}.
     *
     * @param now current monotonic timestamp in nanoseconds
     */
    public void triggerHitImpulse(long now) {
        if (!this.hitFeedback.isEnabled()) {
            return;
        }
        this.impulseEntryOffset = computePulseOffset(now);
        this.hitImpulseStartNanos = now;

        if (this.hitRipple.isEnabled() && (now - this.lastRippleNanos) >= MIN_RIPPLE_INTERVAL_NANOS) {
            this.lastRippleNanos = now;
            spawnRipple(
                now,
                this.cachedTargetX,
                this.cachedRingY,
                this.cachedTargetZ,
                this.cachedBaseRadius,
                this.lockedEntityId
            );
        }
    }

    @Override
    protected void onEnable() {
        resetAllState();
    }

    @Override
    protected void onDisable() {
        resetAllState();
    }

    /**
     * Locks the single target onto the attacked living entity and fires an immediate hit impulse.
     *
     * @param event Forge attack entity event
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        // Ignore integrated-server EntityPlayerMP attack events in singleplayer.
        if (event.entityPlayer != mc.thePlayer) {
            return;
        }
        if (!(event.target instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase target = (EntityLivingBase) event.target;
        if (!isEligibleTarget(target, mc)) {
            return;
        }

        long now = System.nanoTime();
        int targetId = target.getEntityId();
        boolean switchedTarget = targetId != this.lockedEntityId || this.lockedEntity != target;
        boolean wasFadingOut = this.fadingOut;
        if (switchedTarget || wasFadingOut) {
            this.lockedEntityId = targetId;
            this.lockedEntity = target;
            this.lastHurtTime = target.hurtTime;
            if (this.fadeAlpha < 0.25F) {
                this.fadeAlpha = 0.25F;
            }
        }
        this.fadingOut = false;
        this.lastAttackNanos = now;
        updateCachedTargetGeometry(target, 1.0F);
        triggerHitImpulse(now);
    }

    /**
     * Maintains single-target lock validity, timeout fade-out, and damage rising-edge detection each client tick.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!isEnabled() || event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            resetAllState();
            return;
        }
        if (mc.isGamePaused()) {
            return;
        }
        long now = System.nanoTime();
        updateTargetLockState(mc, now, 1.0F);
        if (this.fadingOut) {
            advanceFadeOut(now);
        }
    }

    /**
     * Clears locked target and ripple pool when the client world unloads.
     *
     * @param event Forge world unload event
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event != null && event.world != null && event.world.isRemote) {
            resetAllState();
        }
    }

    /**
     * Renders the active single-target lock ring and expanding shockwave ripples in world space.
     *
     * @param event Forge render world last event
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            resetAllState();
            return;
        }
        RenderManager renderManager = mc.getRenderManager();
        if (renderManager == null) {
            return;
        }

        long now = System.nanoTime();
        float dt = computeDeltaSeconds(now);
        if (mc.isGamePaused()) {
            dt = 0.0F;
        }

        if (this.lockedEntityId != -1 && !mc.isGamePaused()) {
            updateTargetLockState(mc, now, event.partialTicks);
        } else if (this.lockedEntityId != -1 && this.lockedEntity != null && !this.lockedEntity.isDead) {
            updateCachedTargetGeometry(this.lockedEntity, event.partialTicks);
        }

        if (this.fadingOut) {
            advanceFadeOut(now);
        } else if (this.lockedEntityId != -1) {
            // Smooth fast fade-in when acquiring a target.
            this.fadeAlpha = Math.min(1.0F, this.fadeAlpha + (dt > 0.0F ? dt * 9.0F : 0.25F));
        }

        boolean hasRipples = updateAndTrackRipples(mc, now, event.partialTicks);
        boolean renderRing = this.lockedEntityId != -1 && this.fadeAlpha > 0.004F;
        if (!renderRing && !hasRipples) {
            return;
        }

        float impulseProgress = computeImpulseProgress(now);
        float hitMix = (impulseProgress >= 0.0F) ? computeHitColorMix(impulseProgress) : 0.0F;
        float speedMult = (impulseProgress >= 0.0F)
            ? (1.0F + HIT_SPEED_BOOST * (1.0F - easeOutCubic(impulseProgress)))
            : 1.0F;
        float pulseOffset = (impulseProgress >= 0.0F) ? computePulseOffsetFromProgress(impulseProgress) : 0.0F;

        int styleIndex = this.style.getIndex();
        float baseOrbitSpeed = styleIndex == 1 ? BLADE_ORBIT_SPEED_RAD : BASE_ORBIT_SPEED_RAD;
        this.orbitAngleRad = wrapAngle(this.orbitAngleRad + baseOrbitSpeed * speedMult * dt);
        this.scanPhaseRad = wrapAngle(this.scanPhaseRad + SCAN_SPEED_RAD * speedMult * dt);

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

        int baseArgb = resolveBaseColorArgb();
        int hitArgb = this.hitColor.getArgb();
        int activeArgb = ThemeData.lerpColor(baseArgb, hitArgb, hitMix);
        float baseAlpha = ((activeArgb >>> 24) & 0xFF) / 255.0F;
        float r = ((activeArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((activeArgb >>> 8) & 0xFF) / 255.0F;
        float b = (activeArgb & 0xFF) / 255.0F;
        float strokeWidth = this.lineWidth.getFloatValue();

        ContextCapabilities caps = Display.isCreated() ? GLContext.getCapabilities() : null;
        boolean hasGl14 = caps != null && caps.OpenGL14;
        boolean hasExtBlendSep = caps != null && caps.GL_EXT_blend_func_separate;

        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasLineSmooth = GL11.glIsEnabled(GL11.GL_LINE_SMOOTH);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        float prevLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        int prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        int prevBlendSrcAlpha = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
            : (hasExtBlendSep ? GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_SRC_ALPHA_EXT) : prevBlendSrc);
        int prevBlendDstAlpha = hasGl14
            ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
            : (hasExtBlendSep ? GL11.glGetInteger(EXTBlendFuncSeparate.GL_BLEND_DST_ALPHA_EXT) : prevBlendDst);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

            if (renderRing && baseAlpha > 0.004F) {
                float masterAlpha = baseAlpha * this.fadeAlpha;
                float effectiveRadius = this.cachedBaseRadius * (1.0F + pulseOffset);
                double dx = this.cachedTargetX - camX;
                double dy = this.cachedTargetY - camY;
                double dz = this.cachedTargetZ - camZ;

                if (styleIndex == 1) {
                    renderBladeOrbit(
                        dx, dy, dz, this.cachedTargetHeight,
                        effectiveRadius, strokeWidth, r, g, b, masterAlpha
                    );
                } else if (styleIndex == 2) {
                    renderScanPillar(
                        dx, dy, dz, this.cachedTargetHeight,
                        effectiveRadius, strokeWidth, r, g, b, masterAlpha
                    );
                } else {
                    renderPulseRing(
                        dx, dy, dz, this.cachedTargetHeight,
                        effectiveRadius, pulseOffset, strokeWidth, r, g, b, masterAlpha
                    );
                }
            }

            if (hasRipples) {
                renderRipples(camX, camY, camZ, now, hitArgb, strokeWidth);
            }
        } finally {
            GlStateManager.popMatrix();
            if (wasLineSmooth) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
            } else {
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
            }
            GL11.glLineWidth(prevLineWidth);
            GlStateManager.shadeModel(prevShadeModel);
            GlStateManager.depthMask(prevDepthMask);
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

    private void updateTargetLockState(Minecraft mc, long now, float partialTicks) {
        if (this.lockedEntityId == -1) {
            return;
        }
        Entity raw = mc.theWorld.getEntityByID(this.lockedEntityId);
        EntityLivingBase target = (raw instanceof EntityLivingBase) ? (EntityLivingBase) raw : null;

        long keepNanos = (long) (this.keepTime.getDoubleValue() * NANOS_PER_SECOND);
        boolean timedOut = (now - this.lastAttackNanos) > keepNanos;
        boolean invalidOrDead = target == null
            || target.isDead
            || !target.isEntityAlive()
            || target.getHealth() <= 0.0F
            || target.deathTime > 0
            || !isEligibleTarget(target, mc);
        boolean outOfRange = target != null && mc.thePlayer.getDistanceSqToEntity(target) > MAX_LOCK_RANGE_SQ;

        if (invalidOrDead || outOfRange || timedOut) {
            if (!this.fadingOut) {
                this.fadingOut = true;
                this.fadeOutStartNanos = now;
                this.fadeOutStartAlpha = this.fadeAlpha;
            }
            // Keep tracking world coordinates if the entity still exists during timeout/range fade-out.
            if (target != null && !target.isDead && target.worldObj == mc.theWorld) {
                updateCachedTargetGeometry(target, partialTicks);
            }
            return;
        }

        if (this.fadingOut) {
            return;
        }

        this.lockedEntity = target;
        updateCachedTargetGeometry(target, partialTicks);

        int hurtTime = target.hurtTime;
        if (hurtTime > 0 && hurtTime > this.lastHurtTime && hurtTime >= target.maxHurtTime - 1) {
            if ((now - this.lastAttackNanos) >= HURT_EDGE_DEBOUNCE_NANOS) {
                triggerHitImpulse(now);
            }
        }
        this.lastHurtTime = hurtTime;
    }

    private void updateCachedTargetGeometry(EntityLivingBase target, float partialTicks) {
        this.cachedTargetX = target.lastTickPosX + (target.posX - target.lastTickPosX) * (double) partialTicks;
        this.cachedTargetY = target.lastTickPosY + (target.posY - target.lastTickPosY) * (double) partialTicks;
        this.cachedTargetZ = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * (double) partialTicks;
        this.cachedTargetWidth = target.width > 0.1F ? target.width : 0.6F;
        this.cachedTargetHeight = target.height > 0.2F ? target.height : 1.8F;
        this.cachedBaseRadius = Math.max(this.cachedTargetWidth * 1.05F, 0.65F) * this.radiusScale.getFloatValue();
        this.cachedRingY = this.cachedTargetY + computeRingLocalY(this.cachedTargetHeight);
    }

    private float computeRingLocalY(float targetHeight) {
        int styleIdx = this.style.getIndex();
        if (styleIdx == 1) {
            return targetHeight * 0.50F;
        }
        if (styleIdx == 2) {
            float wave = 0.5F - 0.5F * cosLookup(this.scanPhaseRad);
            return 0.08F + wave * Math.max(0.4F, targetHeight - 0.14F);
        }
        return targetHeight * 0.46F;
    }

    private void advanceFadeOut(long now) {
        long elapsed = now - this.fadeOutStartNanos;
        if (elapsed >= FADE_OUT_NANOS) {
            clearLockState();
            return;
        }
        float p = elapsed <= 0L ? 0.0F : (float) elapsed / (float) FADE_OUT_NANOS;
        this.fadeAlpha = this.fadeOutStartAlpha * (1.0F - easeOutCubic(p));
        if (this.fadeAlpha <= 0.004F) {
            clearLockState();
        }
    }

    private void clearLockState() {
        this.lockedEntityId = -1;
        this.lockedEntity = null;
        this.lastHurtTime = 0;
        this.fadingOut = false;
        this.fadeOutStartNanos = 0L;
        this.fadeOutStartAlpha = 0.0F;
        this.fadeAlpha = 0.0F;
        this.hitImpulseStartNanos = 0L;
        this.impulseEntryOffset = 0.0F;
    }

    private void resetAllState() {
        clearLockState();
        this.lastAttackNanos = 0L;
        this.lastRippleNanos = 0L;
        this.lastRenderNanos = 0L;
        this.orbitAngleRad = 0.0F;
        this.scanPhaseRad = 0.0F;
        for (int i = 0; i < MAX_RIPPLES; i++) {
            this.rippleActive[i] = false;
            this.rippleEntityId[i] = -1;
        }
    }

    private boolean isEligibleTarget(EntityLivingBase target, Minecraft mc) {
        if (target == null || target == mc.thePlayer || target instanceof EntityArmorStand) {
            return false;
        }
        if (!target.isEntityAlive() || target.getHealth() <= 0.0F) {
            return false;
        }
        if (this.playersOnly.isEnabled() && !(target instanceof EntityPlayer)) {
            return false;
        }
        if (this.filterBots.isEnabled() && isAntiCheatBot(target, mc)) {
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

    private float computeDeltaSeconds(long now) {
        float dt = 0.0F;
        if (this.lastRenderNanos > 0L && now > this.lastRenderNanos) {
            dt = (now - this.lastRenderNanos) * NANOS_TO_SECONDS;
            if (dt > MAX_DT_SECONDS) {
                dt = MAX_DT_SECONDS;
            }
        }
        this.lastRenderNanos = now;
        return dt;
    }

    private float computeImpulseProgress(long now) {
        if (!this.hitFeedback.isEnabled() || this.hitImpulseStartNanos <= 0L) {
            return -1.0F;
        }
        long elapsed = now - this.hitImpulseStartNanos;
        if (elapsed < 0L || elapsed >= HIT_IMPULSE_NANOS) {
            return -1.0F;
        }
        return (float) elapsed / (float) HIT_IMPULSE_NANOS;
    }

    private float computePulseOffset(long now) {
        float p = computeImpulseProgress(now);
        return p >= 0.0F ? computePulseOffsetFromProgress(p) : 0.0F;
    }

    private float computePulseOffsetFromProgress(float p) {
        if (p < 0.16F) {
            float u = p / 0.16F;
            return mixFloat(this.impulseEntryOffset, -0.18F, easeOutQuad(u));
        }
        if (p < 0.48F) {
            float u = (p - 0.16F) / 0.32F;
            return mixFloat(-0.18F, 0.15F, easeInOutCubic(u));
        }
        float u = (p - 0.48F) / 0.52F;
        return mixFloat(0.15F, 0.0F, easeOutCubic(u));
    }

    private static float computeHitColorMix(float p) {
        float inv = 1.0F - clamp01(p);
        return inv * inv;
    }

    private int resolveBaseColorArgb() {
        if (this.followTheme.isEnabled()) {
            ThemeData theme = ThemeManager.current();
            if (theme != null) {
                return theme.accent();
            }
        }
        return this.ringColor.getArgb();
    }

    private void spawnRipple(long now, double x, double y, double z, float baseRadius, int entityId) {
        int slot = this.nextRippleCursor;
        this.nextRippleCursor = (this.nextRippleCursor + 1) % MAX_RIPPLES;
        this.rippleActive[slot] = true;
        this.rippleStartNanos[slot] = now;
        this.rippleX[slot] = x;
        this.rippleY[slot] = y;
        this.rippleZ[slot] = z;
        this.rippleBaseRadius[slot] = baseRadius;
        this.rippleEntityId[slot] = entityId;
    }

    private boolean updateAndTrackRipples(Minecraft mc, long now, float partialTicks) {
        boolean any = false;
        for (int i = 0; i < MAX_RIPPLES; i++) {
            if (!this.rippleActive[i]) {
                continue;
            }
            long elapsed = now - this.rippleStartNanos[i];
            if (elapsed < 0L || elapsed >= RIPPLE_DURATION_NANOS) {
                this.rippleActive[i] = false;
                continue;
            }
            if (this.rippleEntityId[i] == this.lockedEntityId && this.lockedEntity != null
                && !this.lockedEntity.isDead && this.lockedEntity.worldObj == mc.theWorld) {
                this.rippleX[i] = this.cachedTargetX;
                this.rippleZ[i] = this.cachedTargetZ;
            }
            any = true;
        }
        return any;
    }

    private void renderPulseRing(
        double dx,
        double dy,
        double dz,
        float targetHeight,
        float radius,
        float pulseOffset,
        float strokeWidth,
        float r,
        float g,
        float b,
        float alpha
    ) {
        double ringY = dy + (double) (targetHeight * 0.46F);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();

        // 1. Soft translucent glow aura band hugging the main ring.
        float auraInnerR = Math.max(0.08F, radius * 0.88F);
        float auraOuterR = radius * 1.14F;
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            float angle = (TWO_PI * i) / RING_SEGMENTS;
            float cosA = cosLookup(angle);
            float sinA = sinLookup(angle);
            wr.pos(dx + auraInnerR * cosA, ringY, dz + auraInnerR * sinA).color(r, g, b, 0.0F).endVertex();
            wr.pos(dx + radius * cosA, ringY, dz + radius * sinA).color(r, g, b, alpha * 0.24F).endVertex();
        }
        tessellator.draw();

        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            float angle = (TWO_PI * i) / RING_SEGMENTS;
            float cosA = cosLookup(angle);
            float sinA = sinLookup(angle);
            wr.pos(dx + radius * cosA, ringY, dz + radius * sinA).color(r, g, b, alpha * 0.24F).endVertex();
            wr.pos(dx + auraOuterR * cosA, ringY, dz + auraOuterR * sinA).color(r, g, b, 0.0F).endVertex();
        }
        tessellator.draw();

        // 2. Crisp primary ring.
        GL11.glLineWidth(strokeWidth);
        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float angle = this.orbitAngleRad + (TWO_PI * i) / RING_SEGMENTS;
            wr.pos(dx + radius * cosLookup(angle), ringY, dz + radius * sinLookup(angle))
                .color(r, g, b, alpha * 0.92F)
                .endVertex();
        }
        tessellator.draw();

        // 3. Counter-rotating inner graduated tick ring (major ticks every 90 deg, minor ticks every 15 deg).
        float innerTickBaseR = radius * 0.86F;
        float minorInnerR = radius * 0.80F;
        float majorInnerR = radius * 0.74F;
        float innerRot = -this.orbitAngleRad * 0.65F;
        GL11.glLineWidth(Math.max(1.0F, strokeWidth * 0.72F));
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < TICK_COUNT; i++) {
            float angle = innerRot + (TWO_PI * i) / TICK_COUNT;
            float cosA = cosLookup(angle);
            float sinA = sinLookup(angle);
            boolean major = (i % 6) == 0;
            float rIn = major ? majorInnerR : minorInnerR;
            float tickAlpha = alpha * (major ? 0.82F : 0.45F);
            wr.pos(dx + rIn * cosA, ringY, dz + rIn * sinA).color(r, g, b, tickAlpha).endVertex();
            wr.pos(dx + innerTickBaseR * cosA, ringY, dz + innerTickBaseR * sinA).color(r, g, b, tickAlpha).endVertex();
        }
        tessellator.draw();

        // 4. Four-quadrant lock brackets around the exterior.
        float bracketR = radius * (1.08F + pulseOffset * 0.25F);
        float bracketTipR = bracketR - radius * 0.075F;
        float bracketRot = this.orbitAngleRad * 0.5F;
        float halfSpan = 0.24F; // ~13.7 deg half-arc per quadrant
        GL11.glLineWidth(Math.max(1.25F, strokeWidth * 1.05F));
        for (int q = 0; q < 4; q++) {
            float centerAngle = bracketRot + q * (TWO_PI * 0.25F);
            float startA = centerAngle - halfSpan;
            float endA = centerAngle + halfSpan;
            float startCos = cosLookup(startA);
            float startSin = sinLookup(startA);
            float endCos = cosLookup(endA);
            float endSin = sinLookup(endA);

            wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(dx + bracketTipR * startCos, ringY, dz + bracketTipR * startSin)
                .color(r, g, b, alpha * 0.95F)
                .endVertex();
            for (int s = 0; s <= BRACKET_SEGMENTS; s++) {
                float a = startA + (endA - startA) * ((float) s / (float) BRACKET_SEGMENTS);
                wr.pos(dx + bracketR * cosLookup(a), ringY, dz + bracketR * sinLookup(a))
                    .color(r, g, b, alpha * 0.95F)
                    .endVertex();
            }
            wr.pos(dx + bracketTipR * endCos, ringY, dz + bracketTipR * endSin)
                .color(r, g, b, alpha * 0.95F)
                .endVertex();
            tessellator.draw();
        }
    }

    private void renderBladeOrbit(
        double dx,
        double dy,
        double dz,
        float targetHeight,
        float radius,
        float strokeWidth,
        float r,
        float g,
        float b,
        float alpha
    ) {
        double centerY = dy + (double) (targetHeight * 0.50F);
        float coreR = mixFloat(r, 1.0F, 0.45F);
        float coreG = mixFloat(g, 1.0F, 0.45F);
        float coreB = mixFloat(b, 1.0F, 0.45F);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(dx, centerY, dz);
            GlStateManager.rotate(16.0F, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(10.0F, 0.0F, 0.0F, 1.0F);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            // Subtle inner anchor guide ring.
            float guideR = radius * 0.85F;
            GL11.glLineWidth(Math.max(1.0F, strokeWidth * 0.6F));
            wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < RING_SEGMENTS; i++) {
                float angle = (TWO_PI * i) / RING_SEGMENTS;
                wr.pos(guideR * cosLookup(angle), 0.0D, guideR * sinLookup(angle))
                    .color(r, g, b, alpha * 0.28F)
                    .endVertex();
            }
            tessellator.draw();

            // 4 high-speed tilted star-blade arcs spaced 90 degrees apart.
            float bladeSpan = 0.96F; // ~55 deg per crescent blade
            float bladeOuterR = radius * 1.04F;
            float maxThickness = Math.max(0.06F, radius * 0.14F);

            for (int k = 0; k < 4; k++) {
                float headAngle = this.orbitAngleRad + k * (TWO_PI * 0.25F);
                float tailAngle = headAngle - bladeSpan;

                wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
                for (int s = 0; s <= BLADE_SEGMENTS; s++) {
                    float u = (float) s / (float) BLADE_SEGMENTS; // 0 = trailing tail, 1 = sharp leading tip
                    float angle = tailAngle + bladeSpan * u;
                    float cosA = cosLookup(angle);
                    float sinA = sinLookup(angle);

                    // Asymmetric crescent taper: smooth swell toward the leading 75%, razor-sharp point at u=1.
                    float envelope = (u < 0.78F)
                        ? (u / 0.78F)
                        : (1.0F - (u - 0.78F) / 0.22F);
                    float width = maxThickness * (0.12F + 0.88F * envelope);
                    float outerR = bladeOuterR + width * 0.25F * envelope;
                    float innerR = Math.max(0.05F, bladeOuterR - width);
                    float yTilt = 0.035F * (u - 0.5F);
                    float trailFade = u * u;

                    wr.pos(outerR * cosA, yTilt, outerR * sinA)
                        .color(coreR, coreG, coreB, alpha * (0.15F + 0.82F * trailFade))
                        .endVertex();
                    wr.pos(innerR * cosA, -yTilt, innerR * sinA)
                        .color(r, g, b, alpha * (0.04F + 0.34F * trailFade))
                        .endVertex();
                }
                tessellator.draw();

                // Crisp leading-edge highlight curve.
                GL11.glLineWidth(strokeWidth);
                wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
                for (int s = 0; s <= BLADE_SEGMENTS; s++) {
                    float u = (float) s / (float) BLADE_SEGMENTS;
                    float angle = tailAngle + bladeSpan * u;
                    float cosA = cosLookup(angle);
                    float sinA = sinLookup(angle);
                    float envelope = (u < 0.78F) ? (u / 0.78F) : (1.0F - (u - 0.78F) / 0.22F);
                    float outerR = bladeOuterR + maxThickness * 0.25F * envelope;
                    float yTilt = 0.035F * (u - 0.5F);
                    wr.pos(outerR * cosA, yTilt, outerR * sinA)
                        .color(coreR, coreG, coreB, alpha * (0.12F + 0.88F * u))
                        .endVertex();
                }
                tessellator.draw();
            }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void renderScanPillar(
        double dx,
        double dy,
        double dz,
        float targetHeight,
        float radius,
        float strokeWidth,
        float r,
        float g,
        float b,
        float alpha
    ) {
        float minLocalY = 0.06F;
        float maxLocalY = Math.max(minLocalY + 0.4F, targetHeight - 0.04F);
        float wave = 0.5F - 0.5F * cosLookup(this.scanPhaseRad);
        float scanLocalY = minLocalY + wave * (maxLocalY - minLocalY);
        double scanWorldY = dy + (double) scanLocalY;

        // Vertical scan direction velocity (+1 moving up, -1 moving down).
        float dirSin = sinLookup(this.scanPhaseRad);
        float trailOffset = -dirSin * Math.min(0.36F, targetHeight * 0.24F);
        double trailEdgeY = scanWorldY + (double) trailOffset;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();

        // 1. Subtle ground boundary anchor ring.
        float anchorR = radius * 0.92F;
        GL11.glLineWidth(Math.max(1.0F, strokeWidth * 0.55F));
        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float angle = (TWO_PI * i) / RING_SEGMENTS;
            wr.pos(dx + anchorR * cosLookup(angle), dy + 0.03D, dz + anchorR * sinLookup(angle))
                .color(r, g, b, alpha * 0.22F)
                .endVertex();
        }
        tessellator.draw();

        // 2. Vertical translucent scan energy curtain trailing behind the sweep ring.
        if (Math.abs(trailOffset) > 0.008F) {
            wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= RING_SEGMENTS; i++) {
                float angle = (TWO_PI * i) / RING_SEGMENTS;
                float cosA = cosLookup(angle);
                float sinA = sinLookup(angle);
                wr.pos(dx + radius * cosA, scanWorldY, dz + radius * sinA)
                    .color(r, g, b, alpha * 0.36F)
                    .endVertex();
                wr.pos(dx + radius * cosA, trailEdgeY, dz + radius * sinA)
                    .color(r, g, b, 0.0F)
                    .endVertex();
            }
            tessellator.draw();
        }

        // 3. Primary scanner ring + inner hairline ring.
        GL11.glLineWidth(strokeWidth);
        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float angle = (TWO_PI * i) / RING_SEGMENTS;
            wr.pos(dx + radius * cosLookup(angle), scanWorldY, dz + radius * sinLookup(angle))
                .color(r, g, b, alpha * 0.96F)
                .endVertex();
        }
        tessellator.draw();

        float innerR = radius * 0.91F;
        GL11.glLineWidth(Math.max(1.0F, strokeWidth * 0.60F));
        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float angle = (TWO_PI * i) / RING_SEGMENTS;
            wr.pos(dx + innerR * cosLookup(angle), scanWorldY, dz + innerR * sinLookup(angle))
                .color(r, g, b, alpha * 0.42F)
                .endVertex();
        }
        tessellator.draw();

        // 4. Four rotating cardinal scanner ticks bridging inner and outer ring.
        GL11.glLineWidth(Math.max(1.2F, strokeWidth * 0.90F));
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int q = 0; q < 4; q++) {
            float angle = this.orbitAngleRad + q * (TWO_PI * 0.25F);
            float cosA = cosLookup(angle);
            float sinA = sinLookup(angle);
            wr.pos(dx + (innerR * 0.95F) * cosA, scanWorldY, dz + (innerR * 0.95F) * sinA)
                .color(r, g, b, alpha * 0.90F)
                .endVertex();
            wr.pos(dx + (radius * 1.06F) * cosA, scanWorldY, dz + (radius * 1.06F) * sinA)
                .color(r, g, b, alpha * 0.90F)
                .endVertex();
        }
        tessellator.draw();
    }

    private void renderRipples(double camX, double camY, double camZ, long now, int hitArgb, float strokeWidth) {
        float baseHitAlpha = ((hitArgb >>> 24) & 0xFF) / 255.0F;
        if (baseHitAlpha <= 0.004F) {
            return;
        }
        float r = ((hitArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((hitArgb >>> 8) & 0xFF) / 255.0F;
        float b = (hitArgb & 0xFF) / 255.0F;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GL11.glLineWidth(Math.max(1.0F, strokeWidth * 0.85F));

        for (int i = 0; i < MAX_RIPPLES; i++) {
            if (!this.rippleActive[i]) {
                continue;
            }
            long elapsed = now - this.rippleStartNanos[i];
            if (elapsed < 0L || elapsed >= RIPPLE_DURATION_NANOS) {
                continue;
            }
            float p = (float) elapsed / (float) RIPPLE_DURATION_NANOS;
            float expand = easeOutCubic(p);
            float fade = (1.0F - p) * (1.0F - p) * 0.85F * baseHitAlpha;
            if (fade <= 0.004F) {
                continue;
            }

            float radius = this.rippleBaseRadius[i] * (0.95F + 0.90F * expand);
            float innerTrailR = radius * 0.84F;
            double rx = this.rippleX[i] - camX;
            double ry = this.rippleY[i] - camY;
            double rz = this.rippleZ[i] - camZ;

            wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int s = 0; s <= RING_SEGMENTS; s++) {
                float angle = (TWO_PI * s) / RING_SEGMENTS;
                float cosA = cosLookup(angle);
                float sinA = sinLookup(angle);
                wr.pos(rx + innerTrailR * cosA, ry, rz + innerTrailR * sinA)
                    .color(r, g, b, 0.0F)
                    .endVertex();
                wr.pos(rx + radius * cosA, ry, rz + radius * sinA)
                    .color(r, g, b, fade * 0.32F)
                    .endVertex();
            }
            tessellator.draw();

            wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
            for (int s = 0; s < RING_SEGMENTS; s++) {
                float angle = (TWO_PI * s) / RING_SEGMENTS;
                wr.pos(rx + radius * cosLookup(angle), ry, rz + radius * sinLookup(angle))
                    .color(r, g, b, fade)
                    .endVertex();
            }
            tessellator.draw();
        }
    }

    private static float sinLookup(float radians) {
        float idxF = wrapIndexFloat(radians * RAD_TO_INDEX);
        int idx0 = (int) idxF & TRIG_MASK;
        int idx1 = (idx0 + 1) & TRIG_MASK;
        float frac = idxF - (int) idxF;
        float s0 = SIN_TABLE[idx0];
        return s0 + (SIN_TABLE[idx1] - s0) * frac;
    }

    private static float cosLookup(float radians) {
        float idxF = wrapIndexFloat(radians * RAD_TO_INDEX);
        int idx0 = (int) idxF & TRIG_MASK;
        int idx1 = (idx0 + 1) & TRIG_MASK;
        float frac = idxF - (int) idxF;
        float c0 = COS_TABLE[idx0];
        return c0 + (COS_TABLE[idx1] - c0) * frac;
    }

    private static float wrapIndexFloat(float idxF) {
        float mod = idxF % TRIG_SIZE;
        return mod < 0.0F ? mod + TRIG_SIZE : mod;
    }

    private static float wrapAngle(float angle) {
        float mod = angle % TWO_PI;
        return mod < 0.0F ? mod + TWO_PI : mod;
    }

    private static float mixFloat(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float easeOutQuad(float t) {
        float x = clamp01(t);
        float inv = 1.0F - x;
        return 1.0F - inv * inv;
    }

    private static float easeOutCubic(float t) {
        float inv = 1.0F - clamp01(t);
        return 1.0F - inv * inv * inv;
    }

    private static float easeInOutCubic(float t) {
        float x = clamp01(t);
        if (x < 0.5F) {
            return 4.0F * x * x * x;
        }
        float inv = -2.0F * x + 2.0F;
        return 1.0F - (inv * inv * inv) * 0.5F;
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
