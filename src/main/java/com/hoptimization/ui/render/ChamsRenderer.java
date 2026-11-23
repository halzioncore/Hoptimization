package com.hoptimization.ui.render;

import com.hoptimization.modules.visual.Chams;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders dynamic, randomly spawning glowing 3D rift polylines on living entities,
 * with an optional soft outer silhouette aura.
 */
public final class ChamsRenderer {
    private static final Map<Integer, EntityRiftState> RIFT_STATES = new ConcurrentHashMap<Integer, EntityRiftState>();
    private static final Random RNG = new Random();

    private static boolean renderingChams;
    private static long lastCleanupMs;

    private ChamsRenderer() {
    }

    public interface ModelDrawCallback {
        void draw();
    }

    private static final class RiftBolt {
        float[][] points;
        long startMs;
        long durationMs;
    }

    private static final class EntityRiftState {
        final List<RiftBolt> bolts = new ArrayList<RiftBolt>();
        long lastSeenMs;
    }

    public static boolean isRenderingChams() {
        return renderingChams;
    }

    public static void renderOverlay(EntityLivingBase entity, ModelDrawCallback drawCallback) {
        if (renderingChams || !Chams.shouldRenderEntity(entity)) {
            return;
        }
        Chams chams = Chams.get();
        if (chams == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        cleanupStaleStates(nowMs);

        int riftCount = chams.getRiftCount();
        EntityRiftState state = RIFT_STATES.get(entity.getEntityId());
        if (state == null) {
            state = new EntityRiftState();
            RIFT_STATES.put(entity.getEntityId(), state);
        }
        state.lastSeenMs = nowMs;
        updateBolts(state, riftCount, nowMs);

        int argb = chams.getEffectColor();
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float glow = chams.getGlowStrength();
        boolean outerAura = chams.isOuterAuraEnabled();

        renderingChams = true;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.depthMask(false);
            GlStateManager.disableAlpha();

            if (chams.isThroughWalls()) {
                GL11.glDepthFunc(GL11.GL_GREATER);
                renderPass(state, drawCallback, nowMs, r, g, b, glow * 0.65F, outerAura, true);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
            }

            renderPass(state, drawCallback, nowMs, r, g, b, glow, outerAura, false);
        } finally {
            GL11.glLineWidth(1.0F);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glPopAttrib();
            GlStateManager.depthMask(true);
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.enableLighting();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            renderingChams = false;
        }
    }

    private static void renderPass(
            EntityRiftState state,
            ModelDrawCallback drawCallback,
            long nowMs,
            float r,
            float g,
            float b,
            float glow,
            boolean outerAura,
            boolean occluded) {
        // 1. Optional soft outer silhouette aura
        if (outerAura && glow > 0.05F) {
            GlStateManager.enableCull();
            GL11.glCullFace(GL11.GL_FRONT);
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

            float alpha1 = (occluded ? 0.08F : 0.12F) * glow;
            float alpha2 = (occluded ? 0.04F : 0.06F) * glow;
            drawScaledSilhouette(drawCallback, 1.028F, 1.014F, 1.028F, r, g, b, alpha1);
            drawScaledSilhouette(drawCallback, 1.058F, 1.029F, 1.058F, r, g, b, alpha2);

            GL11.glCullFace(GL11.GL_BACK);
        }

        // 2. Dynamic random glowing 3D rift polylines
        GlStateManager.disableCull();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        float wr = Math.min(1.0F, r + (1.0F - r) * 0.45F);
        float wg = Math.min(1.0F, g + (1.0F - g) * 0.45F);
        float wb = Math.min(1.0F, b + (1.0F - b) * 0.45F);

        float coreR = Math.min(1.0F, r + (1.0F - r) * 0.85F);
        float coreG = Math.min(1.0F, g + (1.0F - g) * 0.85F);
        float coreB = Math.min(1.0F, b + (1.0F - b) * 0.85F);

        for (int i = 0; i < state.bolts.size(); i++) {
            RiftBolt bolt = state.bolts.get(i);
            if (nowMs < bolt.startMs || bolt.points == null || bolt.points.length < 2) {
                continue;
            }
            float progress = (nowMs - bolt.startMs) / (float) Math.max(1L, bolt.durationMs);
            if (progress < 0.0F || progress >= 1.0F) {
                continue;
            }
            float lifeAlpha = (float) Math.pow(Math.sin(Math.PI * progress), 0.60D);
            if (occluded) {
                lifeAlpha *= 0.72F;
            }

            // Outer soft glow
            drawPolyline(bolt.points, 4.6F, r, g, b, lifeAlpha * 0.25F * glow);
            // Mid bloom
            drawPolyline(bolt.points, 2.3F, wr, wg, wb, lifeAlpha * 0.58F * glow);
            // Razor-crisp bright rift core
            drawPolyline(bolt.points, 1.1F, coreR, coreG, coreB, Math.min(1.0F, lifeAlpha * 0.96F));
        }

        GlStateManager.enableCull();
    }

    private static void drawPolyline(float[][] pts, float width, float r, float g, float b, float alpha) {
        if (alpha <= 0.004F) {
            return;
        }
        GL11.glLineWidth(width);
        GL11.glColor4f(r, g, b, alpha);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i < pts.length; i++) {
            GL11.glVertex3f(pts[i][0], pts[i][1], pts[i][2]);
        }
        GL11.glEnd();
    }

    private static void drawScaledSilhouette(
            ModelDrawCallback drawCallback,
            float sx,
            float sy,
            float sz,
            float r,
            float g,
            float b,
            float alpha) {
        if (alpha <= 0.002F) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, -1.501F * (sy - 1.0F) * 0.5F, 0.0F);
        GlStateManager.scale(sx, sy, sz);
        GlStateManager.color(r, g, b, alpha);
        drawCallback.draw();
        GlStateManager.popMatrix();
    }

    private static void updateBolts(EntityRiftState state, int targetCount, long nowMs) {
        while (state.bolts.size() > targetCount) {
            state.bolts.remove(state.bolts.size() - 1);
        }
        while (state.bolts.size() < targetCount) {
            RiftBolt bolt = createRandomBolt(nowMs + RNG.nextInt(320));
            state.bolts.add(bolt);
        }
        for (int i = 0; i < state.bolts.size(); i++) {
            RiftBolt bolt = state.bolts.get(i);
            if (nowMs >= bolt.startMs + bolt.durationMs) {
                state.bolts.set(i, createRandomBolt(nowMs + RNG.nextInt(140)));
            }
        }
    }

    private static RiftBolt createRandomBolt(long startMs) {
        RiftBolt bolt = new RiftBolt();
        bolt.startMs = startMs;
        bolt.durationMs = 460L + RNG.nextInt(520);

        int segments = 4 + RNG.nextInt(3);
        bolt.points = new float[segments + 1][3];

        // Pick a specific body-part bounding box in renderModel local space (1 unit = 16px, scaleFactor = 0.0625)
        float minX;
        float maxX;
        float minY;
        float maxY;
        float minZ;
        float maxZ;
        int part = RNG.nextInt(6);
        if (part == 0) {
            // Head
            minX = -0.24F; maxX = 0.24F;
            minY = -0.46F; maxY = -0.02F;
            minZ = -0.24F; maxZ = 0.24F;
        } else if (part == 1) {
            // Torso
            minX = -0.24F; maxX = 0.24F;
            minY = 0.02F;  maxY = 0.72F;
            minZ = -0.12F; maxZ = 0.12F;
        } else if (part == 2) {
            // Right arm
            minX = -0.37F; maxX = -0.25F;
            minY = 0.04F;  maxY = 0.68F;
            minZ = -0.12F; maxZ = 0.12F;
        } else if (part == 3) {
            // Left arm
            minX = 0.25F;  maxX = 0.37F;
            minY = 0.04F;  maxY = 0.68F;
            minZ = -0.12F; maxZ = 0.12F;
        } else if (part == 4) {
            // Right leg
            minX = -0.23F; maxX = -0.01F;
            minY = 0.76F;  maxY = 1.44F;
            minZ = -0.12F; maxZ = 0.12F;
        } else {
            // Left leg
            minX = 0.01F;  maxX = 0.23F;
            minY = 0.76F;  maxY = 1.44F;
            minZ = -0.12F; maxZ = 0.12F;
        }

        // Pin the polyline onto one of the 4 vertical faces (0: front -Z, 1: back +Z, 2: left -X, 3: right +X)
        int face = RNG.nextInt(4);
        float offset = 0.008F;
        float x = minX + RNG.nextFloat() * (maxX - minX);
        float y = minY + RNG.nextFloat() * (maxY - minY);
        float z = minZ + RNG.nextFloat() * (maxZ - minZ);
        if (face == 0) {
            z = minZ - offset;
        } else if (face == 1) {
            z = maxZ + offset;
        } else if (face == 2) {
            x = minX - offset;
        } else {
            x = maxX + offset;
        }

        bolt.points[0][0] = x;
        bolt.points[0][1] = y;
        bolt.points[0][2] = z;

        float stepU = (RNG.nextFloat() - 0.5F) * 0.11F;
        float stepY = (RNG.nextFloat() - 0.5F) * 0.14F;

        for (int s = 1; s <= segments; s++) {
            y = clamp(y + stepY + (RNG.nextFloat() - 0.5F) * 0.07F, minY, maxY);
            if (face == 0 || face == 1) {
                x = clamp(x + stepU + (RNG.nextFloat() - 0.5F) * 0.07F, minX, maxX);
            } else {
                z = clamp(z + stepU + (RNG.nextFloat() - 0.5F) * 0.07F, minZ, maxZ);
            }
            bolt.points[s][0] = x;
            bolt.points[s][1] = y;
            bolt.points[s][2] = z;
        }
        return bolt;
    }

    private static float clamp(float val, float min, float max) {
        return val < min ? min : (val > max ? max : val);
    }

    private static void cleanupStaleStates(long nowMs) {
        if (nowMs - lastCleanupMs < 3000L) {
            return;
        }
        lastCleanupMs = nowMs;
        Iterator<Map.Entry<Integer, EntityRiftState>> it = RIFT_STATES.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue().lastSeenMs > 5000L) {
                it.remove();
            }
        }
    }
}
