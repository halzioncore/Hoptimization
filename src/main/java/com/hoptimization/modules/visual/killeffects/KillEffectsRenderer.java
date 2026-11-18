package com.hoptimization.modules.visual.killeffects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntitySlime;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.DoubleBuffer;

/**
 * Renders active Kill Effects in {@code RenderWorldLastEvent}, including two-pass translucent
 * entity rendering (Soul Sever, Annihilation, Phantom Swords, and Abyss Vortex), phantom weapon
 * geometry, black hole portal rendering with clip-plane slicing, and pooled soul particles.
 */
public final class KillEffectsRenderer {
    private static final float NANOS_TO_SECONDS = 1.0E-9F;
    private static final float MAX_DT_SECONDS = 0.05F;
    private static final float WISP_EMIT_INTERVAL = 0.055F;
    private static final float MOTE_EMIT_INTERVAL = 0.045F;
    private static final float ABYSS_EMIT_INTERVAL = 0.032F;
    private static final int SLASH_SEGMENTS = 22;
    private static final int DISC_SEGMENTS = 24;
    private static final int BLADE_SEGMENTS = 14;
    private static final int PHANTOM_SWORD_COUNT = 6;
    private static final int ABYSS_SPIRAL_ARMS = 4;
    private static final int ABYSS_SPIRAL_STEPS = 20;
    private static final int ABYSS_STREAM_COUNT = 8;
    private static final int ABYSS_STREAM_STEPS = 10;

    private static final DoubleBuffer CLIP_PLANE_BUF = BufferUtils.createDoubleBuffer(4);
    private static final SoulParticlePool PARTICLE_POOL = new SoulParticlePool();
    private static final float[] SLOT_PARTICLE_ACCUM = new float[KillEffectsTracker.MAX_EFFECTS];
    private static final int[] SLOT_LAST_ENTITY_ID = new int[KillEffectsTracker.MAX_EFFECTS];
    private static final long[] SLOT_LAST_START_NANOS = new long[KillEffectsTracker.MAX_EFFECTS];

    private static long lastRenderNanos = 0L;
    private static boolean internalRenderPass = false;
    private static boolean internalColorPass = false;

    private KillEffectsRenderer() {
    }

    /**
     * Renders all active kill effect instances and pooled particles for the current world frame.
     *
     * @param tracker      active kill effects tracker
     * @param partialTicks current render partial ticks
     */
    public static void render(KillEffectsTracker tracker, float partialTicks) {
        if (tracker == null) {
            return;
        }
        long nowNanos = System.nanoTime();
        tracker.updateActive(nowNanos);

        KillEffectInstance[] instances = tracker.getInstances();
        boolean hasActiveInstance = false;
        if (instances != null) {
            for (int i = 0; i < instances.length; i++) {
                if (instances[i] != null && instances[i].active) {
                    hasActiveInstance = true;
                    break;
                }
            }
        }

        if (!hasActiveInstance && !PARTICLE_POOL.hasActive()) {
            lastRenderNanos = nowNanos;
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

        float dt = 0.0F;
        if (lastRenderNanos > 0L && nowNanos > lastRenderNanos) {
            dt = (nowNanos - lastRenderNanos) * NANOS_TO_SECONDS;
            if (dt > MAX_DT_SECONDS) {
                dt = MAX_DT_SECONDS;
            }
        }
        lastRenderNanos = nowNanos;

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

        boolean prevRenderShadow = renderManager.isRenderShadow();
        int prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        float prevBrightX = OpenGlHelper.lastBrightnessX;
        float prevBrightY = OpenGlHelper.lastBrightnessY;
        float prevLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);

        try {
            PARTICLE_POOL.update(nowNanos);

            if (hasActiveInstance && instances != null) {
                mc.entityRenderer.enableLightmap();
                RenderHelper.enableStandardItemLighting();
                renderManager.setRenderShadow(false);
                internalRenderPass = true;

                int count = instances.length;
                for (int i = 0; i < count; i++) {
                    KillEffectInstance inst = instances[i];
                    if (inst == null || !inst.active) {
                        continue;
                    }
                    syncSlotTimer(i, inst);
                    float progress = clamp01(inst.getProgress(nowNanos));
                    if (inst.mode == KillEffectInstance.MODE_SOUL_SEVER) {
                        renderSoulSever(mc, renderManager, inst, i, progress, dt, nowNanos, camX, camY, camZ);
                    } else if (inst.mode == KillEffectInstance.MODE_PHANTOM_SWORDS) {
                        renderPhantomSwords(mc, renderManager, inst, i, progress, dt, nowNanos, camX, camY, camZ);
                    } else if (inst.mode == KillEffectInstance.MODE_ABYSS_VORTEX) {
                        renderAbyssVortex(mc, renderManager, inst, i, progress, dt, nowNanos, camX, camY, camZ);
                    } else {
                        renderAnnihilation(mc, renderManager, inst, i, progress, dt, nowNanos, camX, camY, camZ);
                    }
                }

                internalRenderPass = false;
                RenderHelper.disableStandardItemLighting();
                mc.entityRenderer.disableLightmap();
            }

            PARTICLE_POOL.render(camX, camY, camZ, nowNanos);
        } finally {
            internalColorPass = false;
            internalRenderPass = false;
            KillSoulShader.end();
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
            renderManager.setRenderShadow(prevRenderShadow);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevBrightX, prevBrightY);
            RenderHelper.disableStandardItemLighting();
            mc.entityRenderer.disableLightmap();
            GL11.glLineWidth(prevLineWidth);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.disableBlend();
            GlStateManager.shadeModel(prevShadeModel);
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
            if (wasDepth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if (wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * Clears transient particle and pass-tracking state on module disable or world unload.
     */
    public static void clearState() {
        internalColorPass = false;
        internalRenderPass = false;
        lastRenderNanos = 0L;
        PARTICLE_POOL.clear();
        for (int i = 0; i < SLOT_PARTICLE_ACCUM.length; i++) {
            SLOT_PARTICLE_ACCUM[i] = 0.0F;
            SLOT_LAST_ENTITY_ID[i] = 0;
            SLOT_LAST_START_NANOS[i] = 0L;
        }
        KillSoulShader.end();
    }

    /**
     * Returns whether the renderer is currently inside an internal Kill Effects entity render pass.
     *
     * @return {@code true} when rendering an effect entity copy
     */
    public static boolean isInternalRenderPass() {
        return internalRenderPass;
    }

    /**
     * Restores translucent blending after {@code RendererLivingEntity.renderLayers} finishes
     * during the internal color pass.
     */
    public static void onAfterRenderLayers() {
        if (!internalRenderPass || !internalColorPass) {
            return;
        }
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
    }

    private static void renderSoulSever(
        Minecraft mc,
        RenderManager renderManager,
        KillEffectInstance inst,
        int slot,
        float t,
        float dt,
        long nowNanos,
        double camX,
        double camY,
        double camZ
    ) {
        float safeW = inst.entityWidth > 0.3F ? inst.entityWidth : 0.6F;
        float safeH = inst.entityHeight > 0.4F ? inst.entityHeight : 1.8F;

        float behindX = inst.behindDirX;
        float behindZ = inst.behindDirZ;
        float bLenSq = behindX * behindX + behindZ * behindZ;
        if (bLenSq < 1.0E-6F) {
            double yawRad = Math.toRadians(inst.yaw);
            behindX = (float) -Math.sin(yawRad);
            behindZ = (float) Math.cos(yawRad);
        } else {
            float invLen = (float) (1.0D / Math.sqrt(bLenSq));
            behindX *= invLen;
            behindZ *= invLen;
        }
        float rightX = -behindZ;
        float rightZ = behindX;
        float yawToBehindDeg = (float) Math.toDegrees(Math.atan2(behindX, behindZ));

        float soulLift = 0.08F + 0.44F * easeOutCubic(t);
        float soulAlpha;
        if (t < 0.08F) {
            soulAlpha = 0.35F + 0.50F * (t / 0.08F);
        } else if (t < 0.62F) {
            soulAlpha = 0.85F;
        } else {
            float p3 = (t - 0.62F) / 0.38F;
            soulAlpha = 0.85F * (1.0F - easeInOutCubic(p3));
        }

        float soulMix = (t < 0.35F) ? (0.68F + 0.20F * (t / 0.35F)) : 0.88F;
        float flash = computeCleaveFlash(t);

        double dx = inst.x - camX;
        double dy = inst.y - camY;
        double dz = inst.z - camZ;

        if (inst.entity != null && soulAlpha > 0.004F) {
            renderTranslucentEntity(
                renderManager,
                inst.entity,
                dx, dy + soulLift, dz,
                inst.yaw,
                inst.soulArgb,
                soulMix,
                soulAlpha,
                flash,
                true
            );
        }

        float baseDist = safeW * 0.85F + 0.55F;
        float baseHeight = safeH * 0.55F + soulLift;
        float weaponTargetScale = inst.weaponScale > 0.2F ? inst.weaponScale : 1.8F;

        float wx;
        float wy;
        float wz;
        float wScale;
        float pitchX;
        float yawY;
        float rollZ;
        float wAlpha;

        if (t < 0.35F) {
            float p1 = t / 0.35F;
            float e1 = easeOutCubic(p1);
            wx = -0.52F * e1;
            wy = baseHeight + 0.42F * e1;
            wz = baseDist + 0.22F * e1;
            wScale = 0.30F + (weaponTargetScale - 0.30F) * e1;
            pitchX = 32.0F * e1;
            yawY = 18.0F * e1;
            rollZ = 35.0F * e1;
            wAlpha = clamp01(p1 * 3.5F) * 0.92F;
        } else if (t < 0.62F) {
            float p2 = (t - 0.35F) / 0.27F;
            float s2 = easeInOutCubic(p2);
            float invS2 = 1.0F - s2;
            // Quadratic Bezier arc: wind-up behind right -> lunge forward through soul -> follow-through left.
            float p0x = -0.52F;
            float p0y = baseHeight + 0.42F;
            float p0z = baseDist + 0.22F;
            float p1x = 0.0F;
            float p1y = baseHeight + 0.04F;
            float p1z = -0.15F;
            float p2x = 0.58F;
            float p2y = baseHeight - 0.34F;
            float p2z = baseDist * 0.55F;

            wx = invS2 * invS2 * p0x + 2.0F * invS2 * s2 * p1x + s2 * s2 * p2x;
            wy = invS2 * invS2 * p0y + 2.0F * invS2 * s2 * p1y + s2 * s2 * p2y;
            wz = invS2 * invS2 * p0z + 2.0F * invS2 * s2 * p1z + s2 * s2 * p2z;

            wScale = weaponTargetScale * (1.0F + 0.12F * (float) Math.sin(Math.PI * s2));
            pitchX = 32.0F + (-90.0F) * s2;
            yawY = 18.0F + (-46.0F) * s2;
            rollZ = 35.0F + (-150.0F) * s2;
            wAlpha = 0.95F;
        } else {
            float p3 = (t - 0.62F) / 0.38F;
            float e3 = easeOutCubic(p3);
            wx = 0.58F + 0.12F * e3;
            wy = baseHeight - 0.34F - 0.08F * e3;
            wz = baseDist * (0.55F + 0.10F * e3);
            wScale = weaponTargetScale * (1.0F - 0.28F * e3);
            pitchX = -58.0F - 10.0F * e3;
            yawY = -28.0F - 6.0F * e3;
            rollZ = -115.0F - 12.0F * e3;
            wAlpha = 0.95F * (1.0F - easeInOutCubic(p3));
        }

        if (t >= 0.48F) {
            if (!inst.cleaveTriggered) {
                inst.cleaveTriggered = true;
                if (inst.soundEnabled && mc.getRenderViewEntity() != null) {
                    double sy = inst.y + safeH * 0.5D;
                    mc.theWorld.playSound(inst.x, sy, inst.z, "random.orb", 0.35F, 1.65F, false);
                    mc.theWorld.playSound(inst.x, sy, inst.z, "mob.wither.shoot", 0.25F, 1.85F, false);
                }
            }
            if (!inst.burstSpawned) {
                inst.burstSpawned = true;
                if (inst.particlesEnabled) {
                    PARTICLE_POOL.spawnCleaveBurst(
                        inst.x, inst.y + baseHeight, inst.z,
                        rightX, rightZ, behindX, behindZ,
                        Math.max(safeW * 0.85F, 0.70F),
                        inst.soulArgb,
                        nowNanos
                    );
                }
            }
        }

        if (inst.particlesEnabled && t < 0.86F && slot < SLOT_PARTICLE_ACCUM.length) {
            SLOT_PARTICLE_ACCUM[slot] += dt;
            int emitted = 0;
            while (SLOT_PARTICLE_ACCUM[slot] >= WISP_EMIT_INTERVAL && emitted < 2) {
                SLOT_PARTICLE_ACCUM[slot] -= WISP_EMIT_INTERVAL;
                PARTICLE_POOL.spawnSoulWisp(
                    inst.x, inst.y + soulLift * 0.4D, inst.z,
                    safeW, safeH,
                    inst.soulArgb,
                    nowNanos
                );
                emitted++;
            }
        }

        if (t < 0.56F) {
            float tetherFade = (t < 0.12F) ? (t / 0.12F) : (t > 0.40F ? 1.0F - (t - 0.40F) / 0.16F : 1.0F);
            renderSpiritTethers(dx, dy, dz, yawToBehindDeg, wx, wy, wz, baseHeight, inst.soulArgb, tetherFade);
        }

        if (wAlpha > 0.004F) {
            renderBehindWeapon(
                mc, inst, dx, dy, dz, yawToBehindDeg,
                wx, wy, wz, wScale, pitchX, yawY, rollZ, wAlpha, flash
            );
        }

        if (t >= 0.38F && t <= 0.78F) {
            float slashRadius = Math.max(safeW * 0.95F, 0.78F);
            renderCrescentSlashRibbon(
                dx, dy, dz, yawToBehindDeg, baseHeight,
                slashRadius, t, inst.soulArgb
            );
        }
    }

    private static void renderAnnihilation(
        Minecraft mc,
        RenderManager renderManager,
        KillEffectInstance inst,
        int slot,
        float t,
        float dt,
        long nowNanos,
        double camX,
        double camY,
        double camZ
    ) {
        float safeW = inst.entityWidth > 0.3F ? inst.entityWidth : 0.6F;
        float safeH = inst.entityHeight > 0.4F ? inst.entityHeight : 1.8F;
        float alpha = 1.0F - easeInOutCubic(t);

        double dx = inst.x - camX;
        double dy = inst.y - camY;
        double dz = inst.z - camZ;

        if (alpha > 0.004F) {
            float discRadius = Math.max(safeW * 0.65F, 0.35F) * (1.0F - 0.35F * t);
            renderGroundShadowDisc(dx, dy + 0.015D, dz, discRadius, 0.42F * alpha);
        }

        if (inst.entity != null && alpha > 0.004F) {
            renderTranslucentEntity(
                renderManager,
                inst.entity,
                dx, dy, dz,
                inst.yaw,
                inst.soulArgb,
                0.0F,
                alpha,
                0.0F,
                false
            );
        }

        if (inst.particlesEnabled) {
            if (!inst.burstSpawned) {
                inst.burstSpawned = true;
                for (int i = 0; i < 10; i++) {
                    PARTICLE_POOL.spawnAnnihilationMote(inst.x, inst.y, inst.z, safeW, safeH, nowNanos);
                }
            }
            if (t < 0.88F && slot < SLOT_PARTICLE_ACCUM.length) {
                SLOT_PARTICLE_ACCUM[slot] += dt;
                int emitted = 0;
                while (SLOT_PARTICLE_ACCUM[slot] >= MOTE_EMIT_INTERVAL && emitted < 2) {
                    SLOT_PARTICLE_ACCUM[slot] -= MOTE_EMIT_INTERVAL;
                    PARTICLE_POOL.spawnAnnihilationMote(inst.x, inst.y, inst.z, safeW, safeH, nowNanos);
                    emitted++;
                }
            }
        }
    }

    private static void renderPhantomSwords(
        Minecraft mc,
        RenderManager renderManager,
        KillEffectInstance inst,
        int slot,
        float t,
        float dt,
        long nowNanos,
        double camX,
        double camY,
        double camZ
    ) {
        float safeW = inst.entityWidth > 0.3F ? inst.entityWidth : 0.6F;
        float safeH = inst.entityHeight > 0.4F ? inst.entityHeight : 1.8F;
        float coreY = safeH * 0.50F;
        float soulLift = 0.04F + 0.12F * easeOutCubic(clamp01(t / 0.45F));

        float soulAlpha;
        if (t < 0.08F) {
            soulAlpha = 0.40F + 0.45F * (t / 0.08F);
        } else if (t < 0.52F) {
            soulAlpha = 0.85F;
        } else {
            float fadeP = clamp01((t - 0.52F) / 0.48F);
            soulAlpha = 0.85F * (1.0F - easeInOutCubic(fadeP));
        }

        float soulMix = 0.72F + 0.18F * easeOutCubic(clamp01(t / 0.30F));
        float flash = computeImpaleFlash(t);

        double dx = inst.x - camX;
        double dy = inst.y - camY;
        double dz = inst.z - camZ;

        if (inst.entity != null && soulAlpha > 0.004F) {
            renderTranslucentEntity(
                renderManager,
                inst.entity,
                dx, dy + soulLift, dz,
                inst.yaw,
                inst.soulArgb,
                soulMix,
                soulAlpha,
                flash,
                true
            );
        }

        if (t >= 0.50F) {
            if (!inst.cleaveTriggered) {
                inst.cleaveTriggered = true;
                if (inst.soundEnabled && mc.getRenderViewEntity() != null) {
                    double sy = inst.y + coreY;
                    mc.theWorld.playSound(inst.x, sy, inst.z, "random.anvil_land", 0.30F, 1.85F, false);
                    mc.theWorld.playSound(inst.x, sy, inst.z, "random.orb", 0.35F, 1.65F, false);
                }
            }
            if (!inst.burstSpawned) {
                inst.burstSpawned = true;
                if (inst.particlesEnabled) {
                    PARTICLE_POOL.spawnPhantomImpaleBurst(
                        inst.x, inst.y + coreY, inst.z,
                        Math.max(safeW * 0.85F, 0.70F),
                        inst.soulArgb,
                        nowNanos
                    );
                }
            }
        }

        if (inst.particlesEnabled && t < 0.78F && slot < SLOT_PARTICLE_ACCUM.length) {
            SLOT_PARTICLE_ACCUM[slot] += dt;
            int emitted = 0;
            float ambientInterval = WISP_EMIT_INTERVAL * 1.4F;
            while (SLOT_PARTICLE_ACCUM[slot] >= ambientInterval && emitted < 2) {
                SLOT_PARTICLE_ACCUM[slot] -= ambientInterval;
                PARTICLE_POOL.spawnSoulWisp(
                    inst.x, inst.y + soulLift * 0.4D, inst.z,
                    safeW, safeH,
                    inst.soulArgb,
                    nowNanos
                );
                emitted++;
            }
        }

        float ringY = safeH + 0.78F;
        float ringR = Math.max(safeW * 1.15F, 0.85F);

        float haloAlpha;
        if (t < 0.14F) {
            haloAlpha = t / 0.14F;
        } else if (t < 0.68F) {
            haloAlpha = 1.0F;
        } else {
            haloAlpha = 1.0F - easeInOutCubic((t - 0.68F) / 0.32F);
        }
        if (haloAlpha > 0.004F) {
            float haloExpand = 0.38F + 0.62F * easeOutCubic(clamp01(t / 0.18F));
            renderPhantomHalo(
                dx, dy + ringY, dz,
                ringR * haloExpand,
                t * 135.0F,
                inst.soulArgb,
                haloAlpha
            );
        }

        float weaponTargetScale = inst.weaponScale > 0.2F ? inst.weaponScale : 1.8F;
        float globalFadeOut = (t > 0.65F) ? (1.0F - easeInOutCubic((t - 0.65F) / 0.35F)) : 1.0F;
        if (globalFadeOut <= 0.004F) {
            return;
        }

        for (int i = 0; i < PHANTOM_SWORD_COUNT; i++) {
            float appearP = clamp01((t - i * 0.04F) / 0.12F);
            if (appearP <= 0.004F) {
                continue;
            }
            float baseAngle = (float) (i * (Math.PI * 2.0D / PHANTOM_SWORD_COUNT));
            float plungeStart = 0.40F + i * 0.02F;
            float plungeDuration = 0.15F;
            float plungeEnd = plungeStart + plungeDuration;

            float orbitTime = t < plungeStart ? t : plungeStart;
            float theta = baseAngle + 4.4F * (orbitTime / 0.40F);

            float startX = (float) Math.cos(theta) * ringR;
            float startY = ringY + 0.18F * (1.0F - appearP);
            float startZ = (float) Math.sin(theta) * ringR;

            float aimX = -startX;
            float aimY = coreY - ringY;
            float aimZ = -startZ;
            float aimLen = (float) Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);
            float invAim = aimLen > 1.0E-4F ? (1.0F / aimLen) : 1.0F;
            float dirX = aimX * invAim;
            float dirY = aimY * invAim;
            float dirZ = aimZ * invAim;

            float diveP = clamp01((t - plungeStart) / plungeDuration);
            float diveE = diveP * diveP;

            float endX = dirX * 0.16F;
            float endY = coreY + dirY * 0.16F;
            float endZ = dirZ * 0.16F;

            float sx = startX + (endX - startX) * diveE;
            float sy = startY + (endY - startY) * diveE;
            float sz = startZ + (endZ - startZ) * diveE;

            float swordAlpha = appearP * 0.94F * globalFadeOut;
            float swordScale = weaponTargetScale * (0.45F + 0.55F * easeOutCubic(appearP));
            float swordFlash = (t >= plungeStart + plungeDuration * 0.65F) ? flash : 0.0F;

            if (diveP > 0.02F && t < plungeEnd + 0.16F) {
                float trailFade = clamp01(1.0F - Math.max(0.0F, t - plungeEnd) / 0.16F) * globalFadeOut;
                if (trailFade > 0.004F) {
                    float tailProgress = clamp01((t - (plungeStart + 0.05F)) / (plungeDuration + 0.11F));
                    float tailE = tailProgress * tailProgress;
                    float tailX = startX + (endX - startX) * tailE;
                    float tailY = startY + (endY - startY) * tailE;
                    float tailZ = startZ + (endZ - startZ) * tailE;
                    float headOffset = 0.54F * swordScale;
                    float headX = sx + dirX * headOffset;
                    float headY = sy + dirY * headOffset;
                    float headZ = sz + dirZ * headOffset;
                    renderSwordThrustTrail(
                        dx, dy, dz,
                        tailX, tailY, tailZ,
                        headX, headY, headZ,
                        inst.soulArgb,
                        trailFade
                    );
                }
            }

            float horiz = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            float yawDeg = (float) Math.toDegrees(Math.atan2(dirX, dirZ));
            float pitchFromUpDeg = (float) Math.toDegrees(Math.atan2(horiz, dirY));
            float bladeRollDeg = (float) Math.toDegrees(theta) + 90.0F;

            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(dx + sx, dy + sy, dz + sz);
                GlStateManager.rotate(yawDeg, 0.0F, 1.0F, 0.0F);
                GlStateManager.rotate(pitchFromUpDeg, 1.0F, 0.0F, 0.0F);
                GlStateManager.rotate(bladeRollDeg, 0.0F, 1.0F, 0.0F);
                drawPhantomLongsword(inst.soulArgb, swordAlpha, swordFlash, swordScale);
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    private static void renderAbyssVortex(
        Minecraft mc,
        RenderManager renderManager,
        KillEffectInstance inst,
        int slot,
        float t,
        float dt,
        long nowNanos,
        double camX,
        double camY,
        double camZ
    ) {
        float safeW = inst.entityWidth > 0.3F ? inst.entityWidth : 0.6F;
        float safeH = inst.entityHeight > 0.4F ? inst.entityHeight : 1.8F;

        double dx = inst.x - camX;
        double dy = inst.y - camY;
        double dz = inst.z - camZ;

        float maxRadius = Math.max(safeW * 1.45F, 1.05F);
        float portalRadius;
        float portalAlpha;
        if (t < 0.18F) {
            float openP = easeOutCubic(t / 0.18F);
            portalRadius = maxRadius * openP;
            portalAlpha = clamp01(t / 0.08F);
        } else if (t < 0.78F) {
            float pulse = 1.0F + 0.04F * (float) Math.sin((t - 0.18F) * 28.0F);
            portalRadius = maxRadius * pulse;
            portalAlpha = 1.0F;
        } else {
            float closeP = clamp01((t - 0.78F) / 0.22F);
            portalRadius = maxRadius * (1.0F - easeInCubic(closeP));
            portalAlpha = 1.0F - closeP * closeP;
        }

        float suctionT = clamp01((t - 0.10F) / 0.76F);
        float suctionEase = suctionT * suctionT * (0.45F + 0.55F * suctionT);
        float spinDeg = 480.0F * suctionT * suctionT;
        float scaleXZ = 1.0F - 0.84F * suctionEase;
        float scaleY = 1.0F + 0.08F * (float) Math.sin(Math.PI * suctionT);
        float sinkOffset = -safeH * 1.12F * suctionEase;
        float soulMix = 0.22F + 0.66F * suctionT;
        float entityAlpha = (t < 0.86F)
            ? (0.96F - 0.34F * suctionT)
            : (0.62F * (1.0F - clamp01((t - 0.86F) / 0.14F)));

        // Draw portal floor before the entity so the entity's depth prepass does not punch a cutout in the disc.
        renderAbyssPortalDisc(dx, dy + 0.02D, dz, portalRadius, t, portalAlpha);

        if (inst.entity != null && entityAlpha > 0.004F) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(dx, dy + 0.02D, dz);
            CLIP_PLANE_BUF.clear();
            CLIP_PLANE_BUF.put(0.0D).put(1.0D).put(0.0D).put(0.0D).flip();
            GL11.glClipPlane(GL11.GL_CLIP_PLANE0, CLIP_PLANE_BUF);
            GlStateManager.popMatrix();

            GL11.glEnable(GL11.GL_CLIP_PLANE0);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(dx, dy + sinkOffset, dz);
                GlStateManager.rotate(spinDeg, 0.0F, 1.0F, 0.0F);
                GlStateManager.scale(scaleXZ, scaleY, scaleXZ);
                renderTranslucentEntity(
                    renderManager,
                    inst.entity,
                    0.0D, 0.0D, 0.0D,
                    inst.yaw,
                    0xFF5A189A,
                    soulMix,
                    entityAlpha,
                    0.0F,
                    false
                );
            } finally {
                GlStateManager.popMatrix();
                GL11.glDisable(GL11.GL_CLIP_PLANE0);
            }
        }

        // Draw 3D gravitational stream lines after the entity for proper front/back depth occlusion.
        renderAbyssGravityStreams(dx, dy + 0.02D, dz, portalRadius, safeH, t, portalAlpha);

        if (t >= 0.72F && !inst.cleaveTriggered) {
            inst.cleaveTriggered = true;
            if (inst.soundEnabled && mc.getRenderViewEntity() != null) {
                mc.theWorld.playSound(inst.x, inst.y + 0.2D, inst.z, "mob.wither.shoot", 0.28F, 0.55F, false);
            }
        }

        if (inst.particlesEnabled) {
            if (!inst.burstSpawned && t >= 0.04F) {
                inst.burstSpawned = true;
                for (int i = 0; i < 6; i++) {
                    PARTICLE_POOL.spawnAbyssSuctionParticle(inst.x, inst.y, inst.z, safeW, safeH, nowNanos);
                }
            }
            if (t < 0.86F && slot < SLOT_PARTICLE_ACCUM.length) {
                SLOT_PARTICLE_ACCUM[slot] += dt;
                int emitted = 0;
                while (SLOT_PARTICLE_ACCUM[slot] >= ABYSS_EMIT_INTERVAL && emitted < 2) {
                    SLOT_PARTICLE_ACCUM[slot] -= ABYSS_EMIT_INTERVAL;
                    PARTICLE_POOL.spawnAbyssSuctionParticle(inst.x, inst.y, inst.z, safeW, safeH, nowNanos);
                    emitted++;
                }
            }
        }
    }

    private static void renderTranslucentEntity(
        RenderManager renderManager,
        EntityLivingBase entity,
        double dx,
        double dy,
        double dz,
        float yaw,
        int soulArgb,
        float soulMix,
        float alpha,
        float flash,
        boolean fullbrightLightmap
    ) {
        if (fullbrightLightmap) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        } else {
            int bright = entity.getBrightnessForRender(0.0F);
            if (entity.isBurning()) {
                bright = 0xF000F0;
            }
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                (float) (bright % 65536),
                (float) (bright / 65536)
            );
        }

        boolean isSlime = entity instanceof EntitySlime;
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableDepth();

        if (!isSlime) {
            internalColorPass = false;
            GlStateManager.colorMask(false, false, false, false);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.disableBlend();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            renderManager.doRenderEntity(entity, dx, dy, dz, yaw, 0.0F, true);
        }

        internalColorPass = true;
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.depthMask(false);
        GlStateManager.depthFunc(isSlime ? GL11.GL_LEQUAL : GL11.GL_EQUAL);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        KillSoulShader.begin(soulArgb, soulMix, alpha, flash, true);
        try {
            renderManager.doRenderEntity(entity, dx, dy, dz, yaw, 0.0F, true);
        } finally {
            KillSoulShader.end();
            internalColorPass = false;
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            GlStateManager.colorMask(true, true, true, true);
        }
    }

    @SuppressWarnings("deprecation")
    private static void renderBehindWeapon(
        Minecraft mc,
        KillEffectInstance inst,
        double dx,
        double dy,
        double dz,
        float yawToBehindDeg,
        float wx,
        float wy,
        float wz,
        float wScale,
        float pitchX,
        float yawY,
        float rollZ,
        float wAlpha,
        float flash
    ) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(dx, dy, dz);
        GlStateManager.rotate(yawToBehindDeg, 0.0F, 1.0F, 0.0F);
        GlStateManager.translate(wx, wy, wz);
        GlStateManager.rotate(yawY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(rollZ, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(pitchX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(wScale, wScale, wScale);

        if (inst.heldItem != null && mc.getRenderItem() != null) {
            GlStateManager.translate(0.0F, 0.18F, 0.0F);
            GlStateManager.rotate(45.0F, 0.0F, 0.0F, 1.0F);

            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            // Disable alpha test so RenderItem's internal alphaFunc(0.1F) does not hard-clip the fade below 0.1.
            GlStateManager.disableAlpha();
            GlStateManager.depthMask(false);
            GlStateManager.disableCull();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            KillSoulShader.begin(inst.soulArgb, 0.72F, wAlpha, flash, true);
            try {
                mc.getRenderItem().renderItem(
                    inst.heldItem,
                    net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType.FIXED
                );
            } finally {
                KillSoulShader.end();
                GlStateManager.depthFunc(GL11.GL_LEQUAL);
                GlStateManager.depthMask(true);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
                GlStateManager.enableCull();
            }
        } else {
            drawSpectralCrescentBlade(inst.soulArgb, wAlpha, flash);
        }

        GlStateManager.popMatrix();
    }

    private static void drawSpectralCrescentBlade(int soulArgb, float alpha, float flash) {
        float r = ((soulArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((soulArgb >>> 8) & 0xFF) / 255.0F;
        float b = (soulArgb & 0xFF) / 255.0F;

        float bodyR = r + (1.0F - r) * (0.20F + flash * 0.55F);
        float bodyG = g + (1.0F - g) * (0.20F + flash * 0.55F);
        float bodyB = b + (1.0F - b) * (0.20F + flash * 0.55F);
        float edgeR = r + (1.0F - r) * 0.85F;
        float edgeG = g + (1.0F - g) * 0.85F;
        float edgeB = b + (1.0F - b) * 0.85F;

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();

        // Outer spectral crescent arc blade spanning from hilt (0, -0.10) up to tip (0.14, +0.62).
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= BLADE_SEGMENTS; i++) {
            float u = (float) i / (float) BLADE_SEGMENTS;
            float theta = (-0.55F + u * 2.15F);
            float cosT = (float) Math.cos(theta);
            float sinT = (float) Math.sin(theta);

            float spineX = -0.14F + 0.36F * cosT;
            float spineY = 0.22F + 0.42F * sinT;
            float thickness = (float) Math.sin(Math.PI * u);
            thickness = 0.015F + 0.135F * thickness;

            float outerX = spineX + thickness * cosT;
            float outerY = spineY + thickness * sinT;
            float innerX = spineX - thickness * 0.35F * cosT;
            float innerY = spineY - thickness * 0.35F * sinT;

            wr.pos(outerX, outerY, 0.0D).color(edgeR, edgeG, edgeB, alpha * 0.96F).endVertex();
            wr.pos(innerX, innerY, 0.0D).color(bodyR, bodyG, bodyB, alpha * 0.55F).endVertex();
        }
        tessellator.draw();

        // Central spine and cross-guard tang for crisp weapon silhouette.
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(-0.025D, -0.22D, 0.0D).color(bodyR, bodyG, bodyB, alpha * 0.70F).endVertex();
        wr.pos(0.025D, -0.22D, 0.0D).color(bodyR, bodyG, bodyB, alpha * 0.70F).endVertex();
        wr.pos(0.020D, 0.42D, 0.0D).color(edgeR, edgeG, edgeB, alpha * 0.90F).endVertex();
        wr.pos(-0.020D, 0.42D, 0.0D).color(edgeR, edgeG, edgeB, alpha * 0.90F).endVertex();

        wr.pos(-0.14D, 0.02D, 0.0D).color(bodyR, bodyG, bodyB, alpha * 0.45F).endVertex();
        wr.pos(0.14D, 0.02D, 0.0D).color(bodyR, bodyG, bodyB, alpha * 0.45F).endVertex();
        wr.pos(0.11D, 0.06D, 0.0D).color(edgeR, edgeG, edgeB, alpha * 0.85F).endVertex();
        wr.pos(-0.11D, 0.06D, 0.0D).color(edgeR, edgeG, edgeB, alpha * 0.85F).endVertex();
        tessellator.draw();

        // Additive core edge glint along the crescent blade rim.
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= BLADE_SEGMENTS; i++) {
            float u = (float) i / (float) BLADE_SEGMENTS;
            float theta = -0.55F + u * 2.15F;
            float cosT = (float) Math.cos(theta);
            float sinT = (float) Math.sin(theta);

            float spineX = -0.14F + 0.36F * cosT;
            float spineY = 0.22F + 0.42F * sinT;
            float thickness = 0.015F + 0.135F * (float) Math.sin(Math.PI * u);

            float outerX = spineX + thickness * cosT;
            float outerY = spineY + thickness * sinT;
            wr.pos(outerX, outerY, 0.015D).color(edgeR, edgeG, edgeB, alpha * 0.65F).endVertex();
            wr.pos(spineX, spineY, -0.015D).color(bodyR, bodyG, bodyB, 0.0F).endVertex();
        }
        tessellator.draw();

        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
    }

    private static void renderSpiritTethers(
        double dx,
        double dy,
        double dz,
        float yawToBehindDeg,
        float wx,
        float wy,
        float wz,
        float soulCenterY,
        int soulArgb,
        float fade
    ) {
        if (fade <= 0.01F) {
            return;
        }
        float r = ((soulArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((soulArgb >>> 8) & 0xFF) / 255.0F;
        float b = (soulArgb & 0xFF) / 255.0F;
        float brightR = r + (1.0F - r) * 0.45F;
        float brightG = g + (1.0F - g) * 0.45F;
        float brightB = b + (1.0F - b) * 0.45F;

        GlStateManager.pushMatrix();
        GlStateManager.translate(dx, dy, dz);
        GlStateManager.rotate(yawToBehindDeg, 0.0F, 1.0F, 0.0F);

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GL11.glLineWidth(1.75F);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();

        for (int strand = 0; strand < 3; strand++) {
            float yOffset = (strand - 1) * 0.16F;
            float xArch = (strand - 1) * 0.14F;
            float midX = (wx * 0.5F) + xArch;
            float midY = ((wy + soulCenterY + yOffset) * 0.5F) + 0.14F;
            float midZ = wz * 0.52F;

            wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int step = 0; step <= 6; step++) {
                float u = step / 6.0F;
                float inv = 1.0F - u;
                float px = inv * inv * wx + 2.0F * inv * u * midX;
                float py = inv * inv * wy + 2.0F * inv * u * midY + u * u * (soulCenterY + yOffset);
                float pz = inv * inv * wz + 2.0F * inv * u * midZ;
                float a = fade * 0.55F * (float) Math.sin(Math.PI * (0.12D + 0.76D * u));
                wr.pos(px, py, pz).color(brightR, brightG, brightB, a).endVertex();
            }
            tessellator.draw();
        }

        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
    }

    private static void renderCrescentSlashRibbon(
        double dx,
        double dy,
        double dz,
        float yawToBehindDeg,
        float centerY,
        float baseRadius,
        float t,
        int soulArgb
    ) {
        float slashProg = clamp01((t - 0.38F) / 0.24F);
        float fadeOut = clamp01((0.78F - t) / 0.22F);
        if (fadeOut <= 0.004F) {
            return;
        }

        float r = ((soulArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((soulArgb >>> 8) & 0xFF) / 255.0F;
        float b = (soulArgb & 0xFF) / 255.0F;
        float coreR = r + (1.0F - r) * 0.78F;
        float coreG = g + (1.0F - g) * 0.78F;
        float coreB = b + (1.0F - b) * 0.78F;

        float radius = baseRadius * (0.95F + 0.32F * slashProg);
        float headDeg = -85.0F + 180.0F * easeOutCubic(slashProg);
        float tailSpanDeg = 105.0F * (0.45F + 0.55F * fadeOut);
        float startRad = (float) Math.toRadians(headDeg - tailSpanDeg);
        float endRad = (float) Math.toRadians(headDeg);

        GlStateManager.pushMatrix();
        GlStateManager.translate(dx, dy + centerY, dz);
        GlStateManager.rotate(yawToBehindDeg, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-36.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(14.0F, 1.0F, 0.0F, 0.0F);

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();

        // Outer translucent soul aura ribbon.
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
        emitSlashStrip(wr, tessellator, startRad, endRad, radius, radius * 0.42F, r, g, b, 0.65F * fadeOut);

        // Inner additive hot crescent core.
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
        emitSlashStrip(wr, tessellator, startRad, endRad, radius, radius * 0.18F, coreR, coreG, coreB, 0.95F * fadeOut);

        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
    }

    private static void emitSlashStrip(
        WorldRenderer wr,
        Tessellator tessellator,
        float startRad,
        float endRad,
        float radius,
        float maxHalfWidth,
        float r,
        float g,
        float b,
        float maxAlpha
    ) {
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= SLASH_SEGMENTS; i++) {
            float u = (float) i / (float) SLASH_SEGMENTS;
            float theta = startRad + (endRad - startRad) * u;
            float sinT = (float) Math.sin(theta);
            float cosT = (float) Math.cos(theta);

            // Taper width and opacity smoothly at tail and sharp tip.
            float envelope = (float) Math.sin(Math.PI * u);
            float width = maxHalfWidth * envelope;
            float outerRadius = radius + width * 0.45F;
            float innerRadius = Math.max(0.05F, radius - width);
            float edgeAlpha = maxAlpha * (float) Math.pow(envelope, 0.70D);

            wr.pos(outerRadius * sinT, 0.0D, -outerRadius * cosT).color(r, g, b, edgeAlpha).endVertex();
            wr.pos(innerRadius * sinT, 0.0D, -innerRadius * cosT).color(r, g, b, 0.0F).endVertex();
        }
        tessellator.draw();
    }

    private static void renderGroundShadowDisc(double dx, double dy, double dz, float radius, float centerAlpha) {
        if (radius <= 0.01F || centerAlpha <= 0.004F) {
            return;
        }

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(dx, dy, dz).color(0.0F, 0.0F, 0.0F, centerAlpha).endVertex();
        for (int i = 0; i <= DISC_SEGMENTS; i++) {
            double angle = (Math.PI * 2.0D * i) / DISC_SEGMENTS;
            double ox = Math.cos(angle) * radius;
            double oz = Math.sin(angle) * radius;
            wr.pos(dx + ox, dy, dz + oz).color(0.0F, 0.0F, 0.0F, 0.0F).endVertex();
        }
        tessellator.draw();

        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
    }

    private static void renderPhantomHalo(
        double cx,
        double cy,
        double cz,
        float radius,
        float spinDeg,
        int soulArgb,
        float alpha
    ) {
        if (radius <= 0.02F || alpha <= 0.004F) {
            return;
        }
        float r = ((soulArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((soulArgb >>> 8) & 0xFF) / 255.0F;
        float b = (soulArgb & 0xFF) / 255.0F;
        float coreR = r + (1.0F - r) * 0.65F;
        float coreG = g + (1.0F - g) * 0.65F;
        float coreB = b + (1.0F - b) * 0.65F;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(cx, cy, cz);
            GlStateManager.rotate(spinDeg, 0.0F, 1.0F, 0.0F);

            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.shadeModel(GL11.GL_SMOOTH);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            // Outer translucent spectral ring.
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            float outerR = radius + 0.032F;
            float innerR = Math.max(0.02F, radius - 0.032F);
            wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= 36; i++) {
                double angle = (Math.PI * 2.0D * i) / 36.0D;
                double cosA = Math.cos(angle);
                double sinA = Math.sin(angle);
                wr.pos(outerR * cosA, 0.0D, outerR * sinA).color(r, g, b, alpha * 0.60F).endVertex();
                wr.pos(innerR * cosA, 0.0D, innerR * sinA).color(coreR, coreG, coreB, alpha * 0.85F).endVertex();
            }
            tessellator.draw();

            // Additive inner ring & hexagram star lines.
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
            GL11.glLineWidth(1.6F);

            wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
            float hairlineR = radius * 0.88F;
            for (int i = 0; i < 36; i++) {
                double angle = (Math.PI * 2.0D * i) / 36.0D;
                wr.pos(hairlineR * Math.cos(angle), 0.0D, hairlineR * Math.sin(angle))
                    .color(coreR, coreG, coreB, alpha * 0.55F)
                    .endVertex();
            }
            tessellator.draw();

            float hexR = radius * 0.96F;
            for (int tri = 0; tri < 2; tri++) {
                wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
                for (int v = 0; v < 3; v++) {
                    int idx = tri + v * 2;
                    double angle = (Math.PI * 2.0D * idx) / 6.0D;
                    wr.pos(hexR * Math.cos(angle), 0.0D, hexR * Math.sin(angle))
                        .color(coreR, coreG, coreB, alpha * 0.72F)
                        .endVertex();
                }
                tessellator.draw();
            }

            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.disableBlend();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.depthMask(true);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private static void renderSwordThrustTrail(
        double dx,
        double dy,
        double dz,
        float tailX,
        float tailY,
        float tailZ,
        float headX,
        float headY,
        float headZ,
        int soulArgb,
        float fade
    ) {
        float vx = headX - tailX;
        float vy = headY - tailY;
        float vz = headZ - tailZ;
        if ((vx * vx + vy * vy + vz * vz) < 1.0E-5F) {
            return;
        }

        double midX = dx + (tailX + headX) * 0.5D;
        double midY = dy + (tailY + headY) * 0.5D;
        double midZ = dz + (tailZ + headZ) * 0.5D;

        double sideX = vy * midZ - vz * midY;
        double sideY = vz * midX - vx * midZ;
        double sideZ = vx * midY - vy * midX;
        double sideLenSq = sideX * sideX + sideY * sideY + sideZ * sideZ;
        if (sideLenSq < 1.0E-8D) {
            return;
        }
        double invSide = 1.0D / Math.sqrt(sideLenSq);
        float sx = (float) (sideX * invSide);
        float sy = (float) (sideY * invSide);
        float sz = (float) (sideZ * invSide);

        float r = ((soulArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((soulArgb >>> 8) & 0xFF) / 255.0F;
        float b = (soulArgb & 0xFF) / 255.0F;
        float coreR = r + (1.0F - r) * 0.72F;
        float coreG = g + (1.0F - g) * 0.72F;
        float coreB = b + (1.0F - b) * 0.72F;

        float neckX = tailX + vx * 0.72F;
        float neckY = tailY + vy * 0.72F;
        float neckZ = tailZ + vz * 0.72F;
        float neckHalfW = 0.085F;
        float headHalfW = 0.015F;

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(dx + tailX, dy + tailY, dz + tailZ).color(r, g, b, 0.0F).endVertex();
        wr.pos(dx + tailX, dy + tailY, dz + tailZ).color(r, g, b, 0.0F).endVertex();
        wr.pos(dx + neckX + sx * neckHalfW, dy + neckY + sy * neckHalfW, dz + neckZ + sz * neckHalfW)
            .color(coreR, coreG, coreB, fade * 0.75F).endVertex();
        wr.pos(dx + neckX - sx * neckHalfW, dy + neckY - sy * neckHalfW, dz + neckZ - sz * neckHalfW)
            .color(coreR, coreG, coreB, fade * 0.75F).endVertex();
        wr.pos(dx + headX + sx * headHalfW, dy + headY + sy * headHalfW, dz + headZ + sz * headHalfW)
            .color(coreR, coreG, coreB, fade * 0.92F).endVertex();
        wr.pos(dx + headX - sx * headHalfW, dy + headY - sy * headHalfW, dz + headZ - sz * headHalfW)
            .color(coreR, coreG, coreB, fade * 0.92F).endVertex();
        tessellator.draw();

        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        );
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawPhantomLongsword(int soulArgb, float alpha, float flash, float scale) {
        if (alpha <= 0.004F || scale <= 0.01F) {
            return;
        }

        float r = ((soulArgb >>> 16) & 0xFF) / 255.0F;
        float g = ((soulArgb >>> 8) & 0xFF) / 255.0F;
        float b = (soulArgb & 0xFF) / 255.0F;

        float flashMix = 0.18F + flash * 0.70F;
        float litR = r + (1.0F - r) * Math.min(1.0F, flashMix + 0.25F);
        float litG = g + (1.0F - g) * Math.min(1.0F, flashMix + 0.25F);
        float litB = b + (1.0F - b) * Math.min(1.0F, flashMix + 0.25F);

        float shadeR = r + (1.0F - r) * (flash * 0.55F);
        float shadeG = g + (1.0F - g) * (flash * 0.55F);
        float shadeB = b + (1.0F - b) * (flash * 0.55F);

        float spineR = r + (1.0F - r) * 0.88F;
        float spineG = g + (1.0F - g) * 0.88F;
        float spineB = b + (1.0F - b) * 0.88F;

        float yTip = 0.58F;
        float yMid = 0.34F;
        float yGuard = -0.14F;
        float wMid = 0.044F;
        float wBase = 0.056F;
        float zMid = 0.017F;
        float zBase = 0.023F;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.scale(scale, scale, scale);

            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.shadeModel(GL11.GL_SMOOTH);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            // 1) 3D diamond-cross-section blade body & tip bevels, plus crossguard and grip/pommel.
            wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

            // Tip pyramid faces (yMid -> yTip).
            emitSwordTri(wr, 0.0F, yTip, 0.0F, wMid, yMid, 0.0F, 0.0F, yMid, zMid,
                litR, litG, litB, alpha * 0.94F, shadeR, shadeG, shadeB, alpha * 0.80F,
                spineR, spineG, spineB, alpha * 0.96F);
            emitSwordTri(wr, 0.0F, yTip, 0.0F, 0.0F, yMid, zMid, -wMid, yMid, 0.0F,
                litR, litG, litB, alpha * 0.94F, spineR, spineG, spineB, alpha * 0.96F,
                shadeR, shadeG, shadeB, alpha * 0.72F);
            emitSwordTri(wr, 0.0F, yTip, 0.0F, -wMid, yMid, 0.0F, 0.0F, yMid, -zMid,
                litR, litG, litB, alpha * 0.94F, shadeR, shadeG, shadeB, alpha * 0.72F,
                spineR, spineG, spineB, alpha * 0.90F);
            emitSwordTri(wr, 0.0F, yTip, 0.0F, 0.0F, yMid, -zMid, wMid, yMid, 0.0F,
                litR, litG, litB, alpha * 0.94F, spineR, spineG, spineB, alpha * 0.90F,
                shadeR, shadeG, shadeB, alpha * 0.80F);

            // Main blade bevels (yGuard -> yMid).
            emitSwordQuadAsTris(wr,
                0.0F, yGuard, zBase, 0.0F, yMid, zMid, wMid, yMid, 0.0F, wBase, yGuard, 0.0F,
                spineR, spineG, spineB, alpha * 0.94F, litR, litG, litB, alpha * 0.84F);
            emitSwordQuadAsTris(wr,
                -wBase, yGuard, 0.0F, -wMid, yMid, 0.0F, 0.0F, yMid, zMid, 0.0F, yGuard, zBase,
                shadeR, shadeG, shadeB, alpha * 0.74F, spineR, spineG, spineB, alpha * 0.94F);
            emitSwordQuadAsTris(wr,
                0.0F, yGuard, -zBase, 0.0F, yMid, -zMid, -wMid, yMid, 0.0F, -wBase, yGuard, 0.0F,
                spineR, spineG, spineB, alpha * 0.88F, shadeR, shadeG, shadeB, alpha * 0.72F);
            emitSwordQuadAsTris(wr,
                wBase, yGuard, 0.0F, wMid, yMid, 0.0F, 0.0F, yMid, -zMid, 0.0F, yGuard, -zBase,
                litR, litG, litB, alpha * 0.80F, spineR, spineG, spineB, alpha * 0.88F);

            // Crossguard bar & flared quillons.
            float gTop = -0.125F;
            float gBot = -0.175F;
            float gHalfW = 0.145F;
            float gHalfZ = 0.025F;
            emitSwordQuadAsTris(wr,
                -gHalfW, gBot, gHalfZ, -gHalfW, gTop, gHalfZ, gHalfW, gTop, gHalfZ, gHalfW, gBot, gHalfZ,
                shadeR, shadeG, shadeB, alpha * 0.85F, litR, litG, litB, alpha * 0.92F);
            emitSwordQuadAsTris(wr,
                gHalfW, gBot, -gHalfZ, gHalfW, gTop, -gHalfZ, -gHalfW, gTop, -gHalfZ, -gHalfW, gBot, -gHalfZ,
                shadeR, shadeG, shadeB, alpha * 0.80F, litR, litG, litB, alpha * 0.88F);
            emitSwordQuadAsTris(wr,
                -gHalfW, gTop, gHalfZ, -gHalfW, gTop, -gHalfZ, gHalfW, gTop, -gHalfZ, gHalfW, gTop, gHalfZ,
                litR, litG, litB, alpha * 0.92F, spineR, spineG, spineB, alpha * 0.95F);
            emitSwordQuadAsTris(wr,
                -gHalfW, gBot, -gHalfZ, -gHalfW, gBot, gHalfZ, gHalfW, gBot, gHalfZ, gHalfW, gBot, -gHalfZ,
                shadeR, shadeG, shadeB, alpha * 0.78F, shadeR, shadeG, shadeB, alpha * 0.78F);

            // Flared quillon tips.
            emitSwordTri(wr, gHalfW, gTop, 0.0F, gHalfW + 0.035F, gTop + 0.028F, 0.0F, gHalfW, gBot, gHalfZ,
                litR, litG, litB, alpha * 0.90F, spineR, spineG, spineB, alpha * 0.95F,
                shadeR, shadeG, shadeB, alpha * 0.75F);
            emitSwordTri(wr, gHalfW, gTop, 0.0F, gHalfW, gBot, -gHalfZ, gHalfW + 0.035F, gTop + 0.028F, 0.0F,
                litR, litG, litB, alpha * 0.90F, shadeR, shadeG, shadeB, alpha * 0.75F,
                spineR, spineG, spineB, alpha * 0.95F);
            emitSwordTri(wr, -gHalfW, gTop, 0.0F, -gHalfW, gBot, gHalfZ, -gHalfW - 0.035F, gTop + 0.028F, 0.0F,
                litR, litG, litB, alpha * 0.90F, shadeR, shadeG, shadeB, alpha * 0.75F,
                spineR, spineG, spineB, alpha * 0.95F);
            emitSwordTri(wr, -gHalfW, gTop, 0.0F, -gHalfW - 0.035F, gTop + 0.028F, 0.0F, -gHalfW, gBot, -gHalfZ,
                litR, litG, litB, alpha * 0.90F, spineR, spineG, spineB, alpha * 0.95F,
                shadeR, shadeG, shadeB, alpha * 0.75F);

            // Grip (y in [-0.34, -0.175]).
            float hW = 0.018F;
            float hBot = -0.34F;
            emitSwordQuadAsTris(wr, -hW, hBot, hW, -hW, gBot, hW, hW, gBot, hW, hW, hBot, hW,
                shadeR, shadeG, shadeB, alpha * 0.80F, litR, litG, litB, alpha * 0.88F);
            emitSwordQuadAsTris(wr, hW, hBot, -hW, hW, gBot, -hW, -hW, gBot, -hW, -hW, hBot, -hW,
                shadeR, shadeG, shadeB, alpha * 0.78F, litR, litG, litB, alpha * 0.86F);
            emitSwordQuadAsTris(wr, hW, hBot, hW, hW, gBot, hW, hW, gBot, -hW, hW, hBot, -hW,
                shadeR, shadeG, shadeB, alpha * 0.76F, litR, litG, litB, alpha * 0.84F);
            emitSwordQuadAsTris(wr, -hW, hBot, -hW, -hW, gBot, -hW, -hW, gBot, hW, -hW, hBot, hW,
                shadeR, shadeG, shadeB, alpha * 0.76F, litR, litG, litB, alpha * 0.84F);

            // Diamond pommel (y in [-0.395, -0.34]).
            float pW = 0.032F;
            float pMid = -0.365F;
            float pBot = -0.398F;
            emitSwordTri(wr, 0.0F, pBot, 0.0F, pW, pMid, 0.0F, 0.0F, pMid, pW,
                spineR, spineG, spineB, alpha * 0.90F, litR, litG, litB, alpha * 0.80F,
                litR, litG, litB, alpha * 0.86F);
            emitSwordTri(wr, 0.0F, pBot, 0.0F, 0.0F, pMid, pW, -pW, pMid, 0.0F,
                spineR, spineG, spineB, alpha * 0.90F, litR, litG, litB, alpha * 0.86F,
                shadeR, shadeG, shadeB, alpha * 0.75F);
            emitSwordTri(wr, 0.0F, pBot, 0.0F, -pW, pMid, 0.0F, 0.0F, pMid, -pW,
                spineR, spineG, spineB, alpha * 0.90F, shadeR, shadeG, shadeB, alpha * 0.75F,
                litR, litG, litB, alpha * 0.82F);
            emitSwordTri(wr, 0.0F, pBot, 0.0F, 0.0F, pMid, -pW, pW, pMid, 0.0F,
                spineR, spineG, spineB, alpha * 0.90F, litR, litG, litB, alpha * 0.82F,
                litR, litG, litB, alpha * 0.80F);
            tessellator.draw();

            // 2) Additive central blade spine highlight & feathered outer edge glow.
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);

            wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(0.0D, yGuard, zBase + 0.002D).color(spineR, spineG, spineB, alpha * 0.85F).endVertex();
            wr.pos(0.0D, yGuard, -zBase - 0.002D).color(spineR, spineG, spineB, alpha * 0.85F).endVertex();
            wr.pos(0.0D, yMid, zMid + 0.002D).color(spineR, spineG, spineB, alpha * 0.92F).endVertex();
            wr.pos(0.0D, yMid, -zMid - 0.002D).color(spineR, spineG, spineB, alpha * 0.92F).endVertex();
            wr.pos(0.0D, yTip + 0.01D, 0.0D).color(1.0F, 1.0F, 1.0F, alpha).endVertex();
            wr.pos(0.0D, yTip + 0.01D, 0.0D).color(1.0F, 1.0F, 1.0F, alpha).endVertex();
            tessellator.draw();

            float auraMidW = wMid * 1.65F;
            float auraBaseW = wBase * 1.65F;
            wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
            emitSwordQuadAsTris(wr,
                0.0F, yGuard, 0.0F, 0.0F, yMid, 0.0F, auraMidW, yMid, 0.0F, auraBaseW, yGuard, 0.0F,
                litR, litG, litB, alpha * 0.45F, r, g, b, 0.0F);
            emitSwordQuadAsTris(wr,
                -auraBaseW, yGuard, 0.0F, -auraMidW, yMid, 0.0F, 0.0F, yMid, 0.0F, 0.0F, yGuard, 0.0F,
                r, g, b, 0.0F, litR, litG, litB, alpha * 0.45F);
            emitSwordTri(wr, 0.0F, yTip + 0.045F, 0.0F, auraMidW, yMid, 0.0F, 0.0F, yMid, 0.0F,
                r, g, b, 0.0F, r, g, b, 0.0F, litR, litG, litB, alpha * 0.45F);
            emitSwordTri(wr, 0.0F, yTip + 0.045F, 0.0F, 0.0F, yMid, 0.0F, -auraMidW, yMid, 0.0F,
                r, g, b, 0.0F, litR, litG, litB, alpha * 0.45F, r, g, b, 0.0F);
            tessellator.draw();

            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.disableBlend();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.depthMask(true);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private static void emitSwordTri(
        WorldRenderer wr,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float r0, float g0, float b0, float a0,
        float r1, float g1, float b1, float a1,
        float r2, float g2, float b2, float a2
    ) {
        wr.pos(x0, y0, z0).color(r0, g0, b0, a0).endVertex();
        wr.pos(x1, y1, z1).color(r1, g1, b1, a1).endVertex();
        wr.pos(x2, y2, z2).color(r2, g2, b2, a2).endVertex();
    }

    private static void emitSwordQuadAsTris(
        WorldRenderer wr,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3,
        float rA, float gA, float bA, float aA,
        float rB, float gB, float bB, float aB
    ) {
        wr.pos(x0, y0, z0).color(rA, gA, bA, aA).endVertex();
        wr.pos(x1, y1, z1).color(rA, gA, bA, aA).endVertex();
        wr.pos(x2, y2, z2).color(rB, gB, bB, aB).endVertex();

        wr.pos(x0, y0, z0).color(rA, gA, bA, aA).endVertex();
        wr.pos(x2, y2, z2).color(rB, gB, bB, aB).endVertex();
        wr.pos(x3, y3, z3).color(rB, gB, bB, aB).endVertex();
    }

    private static void renderAbyssPortalDisc(
        double cx,
        double cy,
        double cz,
        float radius,
        float t,
        float alpha
    ) {
        if (radius <= 0.01F || alpha <= 0.004F) {
            return;
        }

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(cx, cy, cz);

            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.shadeModel(GL11.GL_SMOOTH);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            // Layer 1: Wide outer shadow diffusion disc.
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            float shadowR = radius * 1.24F;
            wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(0.0D, 0.0D, 0.0D).color(0.03F, 0.005F, 0.06F, 0.85F * alpha).endVertex();
            for (int i = 0; i <= DISC_SEGMENTS; i++) {
                double angle = (Math.PI * 2.0D * i) / DISC_SEGMENTS;
                wr.pos(Math.cos(angle) * shadowR, 0.0D, Math.sin(angle) * shadowR)
                    .color(0.02F, 0.004F, 0.04F, 0.0F).endVertex();
            }
            tessellator.draw();

            // Layer 2: 4 counter-rotating logarithmic spiral accretion arms (#24063B -> #6C22C8 -> #C45BFF).
            float spinRad = (float) Math.toRadians(-680.0F * t);
            float armBaseY = 0.003F;

            // Dark-purple alpha under-ribbon for high contrast against bright terrain.
            for (int arm = 0; arm < ABYSS_SPIRAL_ARMS; arm++) {
                float armOffset = (float) (arm * (Math.PI * 2.0D / ABYSS_SPIRAL_ARMS));
                wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
                for (int s = 0; s <= ABYSS_SPIRAL_STEPS; s++) {
                    float u = (float) s / (float) ABYSS_SPIRAL_STEPS;
                    float rCurve = radius * (0.16F * (float) Math.exp(1.88F * u));
                    float theta = armOffset + spinRad + 2.15F * (float) Math.log(0.16F + 0.84F * u);
                    float cosT = (float) Math.cos(theta);
                    float sinT = (float) Math.sin(theta);

                    float env = (float) Math.sin(Math.PI * u);
                    float halfW = radius * (0.04F + 0.14F * env);
                    float rIn = Math.max(0.02F, rCurve - halfW);
                    float rOut = rCurve + halfW;

                    float cr = mixFloat(0.424F, 0.141F, u);
                    float cg = mixFloat(0.133F, 0.024F, u);
                    float cb = mixFloat(0.784F, 0.231F, u);
                    float armA = alpha * 0.78F * (1.0F - 0.65F * u) * (0.35F + 0.65F * env);

                    wr.pos(rOut * cosT, armBaseY, rOut * sinT).color(cr, cg, cb, armA * 0.35F).endVertex();
                    wr.pos(rIn * cosT, armBaseY, rIn * sinT).color(cr, cg, cb, armA).endVertex();
                }
                tessellator.draw();
            }

            // Luminous additive magenta-violet core spirals.
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
            for (int arm = 0; arm < ABYSS_SPIRAL_ARMS; arm++) {
                float armOffset = (float) (arm * (Math.PI * 2.0D / ABYSS_SPIRAL_ARMS));
                wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
                for (int s = 0; s <= ABYSS_SPIRAL_STEPS; s++) {
                    float u = (float) s / (float) ABYSS_SPIRAL_STEPS;
                    float rCurve = radius * (0.16F * (float) Math.exp(1.88F * u));
                    float theta = armOffset + spinRad + 2.15F * (float) Math.log(0.16F + 0.84F * u);
                    float cosT = (float) Math.cos(theta);
                    float sinT = (float) Math.sin(theta);

                    float env = (float) Math.sin(Math.PI * u);
                    float halfW = radius * (0.022F + 0.075F * env);
                    float rIn = Math.max(0.02F, rCurve - halfW);
                    float rOut = rCurve + halfW;

                    float cr = mixFloat(0.769F, 0.424F, u);
                    float cg = mixFloat(0.357F, 0.133F, u);
                    float cb = mixFloat(1.000F, 0.784F, u);
                    float armA = alpha * 0.88F * (1.0F - 0.75F * u) * (0.25F + 0.75F * env);

                    wr.pos(rOut * cosT, armBaseY + 0.002F, rOut * sinT).color(cr, cg, cb, 0.0F).endVertex();
                    wr.pos(rIn * cosT, armBaseY + 0.002F, rIn * sinT).color(cr, cg, cb, armA).endVertex();
                }
                tessellator.draw();
            }

            // Layer 3: Pure black event horizon core (#040108) with glowing violet photon rim.
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            float coreR = radius * 0.28F;
            float coreY = 0.007F;
            wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(0.0D, coreY, 0.0D).color(0.016F, 0.004F, 0.031F, 0.98F * alpha).endVertex();
            for (int i = 0; i <= DISC_SEGMENTS; i++) {
                double angle = (Math.PI * 2.0D * i) / DISC_SEGMENTS;
                wr.pos(Math.cos(angle) * coreR, coreY, Math.sin(angle) * coreR)
                    .color(0.016F, 0.004F, 0.031F, 0.94F * alpha).endVertex();
            }
            tessellator.draw();

            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
            float rimInner = coreR * 0.90F;
            float rimOuter = coreR * 1.32F;
            wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= DISC_SEGMENTS; i++) {
                double angle = (Math.PI * 2.0D * i) / DISC_SEGMENTS;
                double cosA = Math.cos(angle);
                double sinA = Math.sin(angle);
                wr.pos(rimInner * cosA, coreY + 0.001F, rimInner * sinA)
                    .color(0.769F, 0.357F, 1.0F, 0.85F * alpha).endVertex();
                wr.pos(rimOuter * cosA, coreY + 0.001F, rimOuter * sinA)
                    .color(0.424F, 0.133F, 0.784F, 0.0F).endVertex();
            }
            tessellator.draw();

            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.disableBlend();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.depthMask(true);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private static void renderAbyssGravityStreams(
        double cx,
        double cy,
        double cz,
        float radius,
        float safeH,
        float t,
        float alpha
    ) {
        if (radius <= 0.02F || alpha <= 0.004F) {
            return;
        }

        float topH = safeH * 0.75F * (0.45F + 0.55F * clamp01(t / 0.18F));
        float spinRad = (float) Math.toRadians(-420.0F * t);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(cx, cy, cz);

            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            GL11.glLineWidth(1.65F);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();

            for (int k = 0; k < ABYSS_STREAM_COUNT; k++) {
                float baseAngle = (float) (k * (Math.PI * 2.0D / ABYSS_STREAM_COUNT));
                wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
                for (int s = 0; s <= ABYSS_STREAM_STEPS; s++) {
                    float u = (float) s / (float) ABYSS_STREAM_STEPS;
                    float invU = 1.0F - u;
                    float r = radius * 0.92F * (invU * (float) Math.sqrt(invU));
                    float y = topH * (invU * invU);
                    float angle = baseAngle + spinRad + u * 2.2F;

                    float px = r * (float) Math.cos(angle);
                    float pz = r * (float) Math.sin(angle);

                    float cr = mixFloat(0.424F, 0.769F, u);
                    float cg = mixFloat(0.133F, 0.357F, u);
                    float cb = mixFloat(0.784F, 1.000F, u);
                    float lineA = alpha * 0.68F * (float) Math.sin(Math.PI * (0.08D + 0.84D * u));

                    wr.pos(px, y, pz).color(cr, cg, cb, lineA).endVertex();
                }
                tessellator.draw();
            }

            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            GlStateManager.disableBlend();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.depthMask(true);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private static void syncSlotTimer(int slot, KillEffectInstance inst) {
        if (slot < 0 || slot >= SLOT_PARTICLE_ACCUM.length) {
            return;
        }
        if (SLOT_LAST_ENTITY_ID[slot] != inst.entityId || SLOT_LAST_START_NANOS[slot] != inst.startNanos) {
            SLOT_LAST_ENTITY_ID[slot] = inst.entityId;
            SLOT_LAST_START_NANOS[slot] = inst.startNanos;
            SLOT_PARTICLE_ACCUM[slot] = 0.0F;
        }
    }

    private static float computeCleaveFlash(float t) {
        if (t < 0.44F || t > 0.72F) {
            return 0.0F;
        }
        if (t < 0.49F) {
            return clamp01((t - 0.44F) / 0.05F);
        }
        float decay = 1.0F - clamp01((t - 0.49F) / 0.23F);
        return decay * decay;
    }

    private static float computeImpaleFlash(float t) {
        if (t < 0.49F || t > 0.84F) {
            return 0.0F;
        }
        if (t < 0.53F) {
            return clamp01((t - 0.49F) / 0.04F);
        }
        float decay = 1.0F - clamp01((t - 0.53F) / 0.31F);
        return decay * decay;
    }

    private static float mixFloat(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float easeInCubic(float t) {
        float x = clamp01(t);
        return x * x * x;
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
