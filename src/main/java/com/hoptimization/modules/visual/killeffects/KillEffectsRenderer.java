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
import org.lwjgl.opengl.GL11;

/**
 * Renders active Kill Effects in {@code RenderWorldLastEvent}, including two-pass translucent
 * entity rendering (Soul Sever and Annihilation), behind-entity phantom weapon execution,
 * 3D crescent slash arcs, and pooled soul particles.
 */
public final class KillEffectsRenderer {
    private static final float NANOS_TO_SECONDS = 1.0E-9F;
    private static final float MAX_DT_SECONDS = 0.05F;
    private static final float WISP_EMIT_INTERVAL = 0.055F;
    private static final float MOTE_EMIT_INTERVAL = 0.045F;
    private static final int SLASH_SEGMENTS = 22;
    private static final int DISC_SEGMENTS = 24;
    private static final int BLADE_SEGMENTS = 14;

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
