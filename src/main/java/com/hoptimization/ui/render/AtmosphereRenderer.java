package com.hoptimization.ui.render;

import com.hoptimization.modules.visual.Atmosphere;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

/**
 * Custom 3D world atmosphere renderer:
 * <ul>
 *   <li>Abstract minimalist 3-stop gradient sky dome with soft horizon aurora glow and geometric starlight.</li>
 *   <li>Realistic, slender wind-slanted 3D rain streaks with expanding circular ground splash ripples.</li>
 * </ul>
 * Pre-allocates all geometry & particle pools for zero per-frame heap allocations.
 */
public final class AtmosphereRenderer {
    private static final int SKY_LAT_RINGS = 16;
    private static final int SKY_LON_SEGS = 32;
    private static final float SKY_RADIUS = 120.0f;
    private static final int STAR_COUNT = 110;

    private static final int MAX_RAINDROPS = 220;
    private static final int MAX_RIPPLES = 72;
    private static final float RAIN_AREA_RADIUS = 22.0f;
    private static final float RAIN_HEIGHT_SPAN = 20.0f;
    private static final float WIND_VEL_X = 1.65f;
    private static final float FALL_VEL_Y = -25.5f;
    private static final float WIND_VEL_Z = 0.75f;
    private static final float RIPPLE_LIFETIME = 0.45f;
    private static final int RIPPLE_SEGMENTS = 20;

    private static final int[] SKY_PALETTE = new int[4];
    private static final float[] STAR_X = new float[STAR_COUNT];
    private static final float[] STAR_Y = new float[STAR_COUNT];
    private static final float[] STAR_Z = new float[STAR_COUNT];
    private static final float[] STAR_PHASE = new float[STAR_COUNT];
    private static final float[] STAR_SIZE = new float[STAR_COUNT];

    private static final float[] DROP_X = new float[MAX_RAINDROPS];
    private static final float[] DROP_Y = new float[MAX_RAINDROPS];
    private static final float[] DROP_Z = new float[MAX_RAINDROPS];
    private static final float[] DROP_SPEED = new float[MAX_RAINDROPS];
    private static final float[] DROP_LEN = new float[MAX_RAINDROPS];

    private static final float[] RIPPLE_X = new float[MAX_RIPPLES];
    private static final float[] RIPPLE_Y = new float[MAX_RIPPLES];
    private static final float[] RIPPLE_Z = new float[MAX_RIPPLES];
    private static final float[] RIPPLE_AGE = new float[MAX_RIPPLES];
    private static final float[] RIPPLE_COS = new float[RIPPLE_SEGMENTS + 1];
    private static final float[] RIPPLE_SIN = new float[RIPPLE_SEGMENTS + 1];

    private static final BlockPos.MutableBlockPos MUTABLE_POS = new BlockPos.MutableBlockPos();
    private static boolean rainInitialized = false;
    private static long lastRainNanos = 0L;
    private static int nextRippleSlot = 0;
    private static int rngState = 0x5A17C3E9;

    static {
        // Deterministic starfield on the upper sky hemisphere.
        for (int i = 0; i < STAR_COUNT; i++) {
            float u = nextFloat();
            float v = 0.16f + nextFloat() * 0.84f;
            double theta = u * Math.PI * 2.0D;
            double phi = Math.acos(v);
            float r = SKY_RADIUS * 0.92f;
            STAR_X[i] = (float) (Math.sin(phi) * Math.cos(theta)) * r;
            STAR_Y[i] = (float) Math.cos(phi) * r;
            STAR_Z[i] = (float) (Math.sin(phi) * Math.sin(theta)) * r;
            STAR_PHASE[i] = nextFloat() * 6.283185f;
            STAR_SIZE[i] = 0.24f + nextFloat() * 0.34f;
        }
        for (int i = 0; i <= RIPPLE_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0D * i) / RIPPLE_SEGMENTS;
            RIPPLE_COS[i] = (float) Math.cos(ang);
            RIPPLE_SIN[i] = (float) Math.sin(ang);
        }
        for (int i = 0; i < MAX_RIPPLES; i++) {
            RIPPLE_AGE[i] = -1.0f;
        }
    }

    private AtmosphereRenderer() {
    }

    /**
     * Renders the abstract minimalist 3-stop gradient sky dome, soft horizon aurora glow, and geometric starlight.
     */
    public static void renderAbstractSky() {
        Atmosphere.resolveSkyPalette(SKY_PALETTE);
        int zenith = SKY_PALETTE[0];
        int mid = SKY_PALETTE[1];
        int horizon = SKY_PALETTE[2];
        int aurora = SKY_PALETTE[3];

        boolean wasTexture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasFog = GL11.glIsEnabled(GL11.GL_FOG);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);

        GlStateManager.disableTexture2D();
        GlStateManager.disableFog();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        try {
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();

            // 1. Smooth 3-stop spherical sky dome (-0.35..1.0 elevation).
            for (int ring = 0; ring < SKY_LAT_RINGS; ring++) {
                float t0 = (ring / (float) SKY_LAT_RINGS) * 1.35f - 0.35f;
                float t1 = ((ring + 1) / (float) SKY_LAT_RINGS) * 1.35f - 0.35f;
                double phi0 = Math.asin(clamp(t0, -1.0f, 1.0f));
                double phi1 = Math.asin(clamp(t1, -1.0f, 1.0f));

                float y0 = (float) Math.sin(phi0) * SKY_RADIUS;
                float r0 = (float) Math.cos(phi0) * SKY_RADIUS;
                float y1 = (float) Math.sin(phi1) * SKY_RADIUS;
                float r1 = (float) Math.cos(phi1) * SKY_RADIUS;

                int c0 = evalSkyColor(t0, horizon, mid, zenith);
                int c1 = evalSkyColor(t1, horizon, mid, zenith);
                int r0c = (c0 >>> 16) & 0xFF, g0c = (c0 >>> 8) & 0xFF, b0c = c0 & 0xFF;
                int r1c = (c1 >>> 16) & 0xFF, g1c = (c1 >>> 8) & 0xFF, b1c = c1 & 0xFF;

                wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
                for (int seg = 0; seg <= SKY_LON_SEGS; seg++) {
                    double theta = (Math.PI * 2.0D * seg) / SKY_LON_SEGS;
                    float cosT = (float) Math.cos(theta);
                    float sinT = (float) Math.sin(theta);
                    wr.pos(cosT * r1, y1, sinT * r1).color(r1c, g1c, b1c, 255).endVertex();
                    wr.pos(cosT * r0, y0, sinT * r0).color(r0c, g0c, b0c, 255).endVertex();
                }
                tess.draw();
            }

            // 2. Soft breathing aurora / horizon mist ribbon.
            double timeSec = System.nanoTime() / 1_000_000_000.0D;
            int ar = (aurora >>> 16) & 0xFF;
            int ag = (aurora >>> 8) & 0xFF;
            int ab = aurora & 0xFF;
            float auroraR = SKY_RADIUS * 0.96f;

            wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int seg = 0; seg <= SKY_LON_SEGS; seg++) {
                double theta = (Math.PI * 2.0D * seg) / SKY_LON_SEGS;
                float cosT = (float) Math.cos(theta);
                float sinT = (float) Math.sin(theta);
                float wave = (float) (Math.sin(theta * 2.0D + timeSec * 0.25D) * 7.5D
                    + Math.cos(theta * 3.0D - timeSec * 0.18D) * 4.0D);
                float baseY = 6.0f + wave;
                int peakAlpha = Math.round(34.0f + 14.0f * (float) Math.sin(theta + timeSec * 0.2D));
                wr.pos(cosT * auroraR, baseY + 26.0f, sinT * auroraR).color(ar, ag, ab, 0).endVertex();
                wr.pos(cosT * auroraR, baseY, sinT * auroraR).color(ar, ag, ab, peakAlpha).endVertex();
            }
            tess.draw();

            // 3. Restrained geometric starlight points.
            if (Atmosphere.isStarsEnabled()) {
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                for (int i = 0; i < STAR_COUNT; i++) {
                    float sx = STAR_X[i];
                    float sy = STAR_Y[i];
                    float sz = STAR_Z[i];
                    float s = STAR_SIZE[i];
                    float twinkle = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(timeSec * 1.4D + STAR_PHASE[i]));
                    int alpha = Math.round(170.0f * twinkle);
                    wr.pos(sx - s, sy, sz - s).color(235, 242, 255, alpha).endVertex();
                    wr.pos(sx + s, sy, sz - s).color(235, 242, 255, alpha).endVertex();
                    wr.pos(sx + s, sy, sz + s).color(235, 242, 255, alpha).endVertex();
                    wr.pos(sx - s, sy, sz + s).color(235, 242, 255, alpha).endVertex();
                }
                tess.draw();
            }
        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.depthMask(true);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            if (wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (wasLighting) {
                GlStateManager.enableLighting();
            }
            if (wasCull) {
                GlStateManager.enableCull();
            }
            if (wasAlpha) {
                GlStateManager.enableAlpha();
            }
            if (wasFog) {
                GlStateManager.enableFog();
            }
            if (wasTexture) {
                GlStateManager.enableTexture2D();
            }
        }
    }

    /**
     * Renders realistic, wind-slanted slender 3D rain streaks and expanding ground splash ripples.
     */
    public static void renderCustomRain(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        World world = mc.theWorld;
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

        long now = System.nanoTime();
        float dt = lastRainNanos > 0L ? Math.min(0.05f, Math.max(0.0f, (now - lastRainNanos) / 1_000_000_000.0f)) : 0.016f;
        lastRainNanos = now;

        int activeCount = Atmosphere.isHeavyRain() ? MAX_RAINDROPS : 120;
        if (!rainInitialized) {
            for (int i = 0; i < MAX_RAINDROPS; i++) {
                respawnDrop(i, (float) camX, (float) camY, (float) camZ, true);
            }
            rainInitialized = true;
        }

        boolean ripples = Atmosphere.isRipplesEnabled();
        for (int i = 0; i < activeCount; i++) {
            float speedMul = DROP_SPEED[i];
            DROP_X[i] += WIND_VEL_X * speedMul * dt;
            DROP_Y[i] += FALL_VEL_Y * speedMul * dt;
            DROP_Z[i] += WIND_VEL_Z * speedMul * dt;

            float dx = DROP_X[i] - (float) camX;
            float dz = DROP_Z[i] - (float) camZ;
            if (Math.abs(dx) > RAIN_AREA_RADIUS || Math.abs(dz) > RAIN_AREA_RADIUS) {
                respawnDrop(i, (float) camX, (float) camY, (float) camZ, false);
                continue;
            }

            MUTABLE_POS.set((int) Math.floor(DROP_X[i]), 0, (int) Math.floor(DROP_Z[i]));
            int groundY = world.getPrecipitationHeight(MUTABLE_POS).getY();
            if (DROP_Y[i] <= groundY) {
                if (ripples && Math.abs(DROP_Y[i] - camY) < 16.0f) {
                    spawnRipple(DROP_X[i], groundY + 0.03f, DROP_Z[i]);
                }
                respawnDrop(i, (float) camX, (float) camY, (float) camZ, false);
            } else if (DROP_Y[i] < camY - 12.0f) {
                respawnDrop(i, (float) camX, (float) camY, (float) camZ, false);
            }
        }

        if (ripples) {
            for (int i = 0; i < MAX_RIPPLES; i++) {
                if (RIPPLE_AGE[i] >= 0.0f) {
                    RIPPLE_AGE[i] += dt;
                    if (RIPPLE_AGE[i] >= RIPPLE_LIFETIME) {
                        RIPPLE_AGE[i] = -1.0f;
                    }
                }
            }
        }

        boolean wasTexture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.depthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.25f);

        try {
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();

            // Draw slender, tapered translucent rain streaks.
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < activeCount; i++) {
                float rx = (float) (DROP_X[i] - camX);
                float ry = (float) (DROP_Y[i] - camY);
                float rz = (float) (DROP_Z[i] - camZ);
                float len = DROP_LEN[i];
                float tailX = rx - WIND_VEL_X * (len * 0.04f);
                float tailY = ry + len;
                float tailZ = rz - WIND_VEL_Z * (len * 0.04f);

                float distSq = rx * rx + rz * rz;
                float distFade = 1.0f - Math.min(1.0f, distSq / (RAIN_AREA_RADIUS * RAIN_AREA_RADIUS));
                int headA = Math.round(115.0f * distFade);
                if (headA <= 2) {
                    continue;
                }
                wr.pos(tailX, tailY, tailZ).color(185, 205, 228, 0).endVertex();
                wr.pos(rx, ry, rz).color(205, 222, 242, headA).endVertex();
            }
            tess.draw();

            // Draw expanding ground splash ripples.
            if (ripples) {
                GL11.glLineWidth(1.1f);
                for (int i = 0; i < MAX_RIPPLES; i++) {
                    float age = RIPPLE_AGE[i];
                    if (age < 0.0f) {
                        continue;
                    }
                    float t = age / RIPPLE_LIFETIME;
                    float radius = 0.06f + t * 0.34f;
                    int alpha = Math.round(95.0f * (1.0f - t) * (1.0f - t));
                    if (alpha <= 2) {
                        continue;
                    }
                    float rx = (float) (RIPPLE_X[i] - camX);
                    float ry = (float) (RIPPLE_Y[i] - camY);
                    float rz = (float) (RIPPLE_Z[i] - camZ);
                    wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
                    for (int seg = 0; seg < RIPPLE_SEGMENTS; seg++) {
                        wr.pos(rx + RIPPLE_COS[seg] * radius, ry, rz + RIPPLE_SIN[seg] * radius)
                            .color(205, 222, 242, alpha)
                            .endVertex();
                    }
                    tess.draw();
                }
            }
        } finally {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.0f);
            GlStateManager.depthMask(true);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            if (wasCull) {
                GlStateManager.enableCull();
            }
            if (wasAlpha) {
                GlStateManager.enableAlpha();
            }
            if (!wasBlend) {
                GlStateManager.disableBlend();
            }
            if (wasLighting) {
                GlStateManager.enableLighting();
            }
            if (wasTexture) {
                GlStateManager.enableTexture2D();
            }
        }
    }

    private static void respawnDrop(int index, float camX, float camY, float camZ, boolean randomY) {
        DROP_X[index] = camX + (nextFloat() * 2.0f - 1.0f) * RAIN_AREA_RADIUS;
        DROP_Z[index] = camZ + (nextFloat() * 2.0f - 1.0f) * RAIN_AREA_RADIUS;
        DROP_Y[index] = randomY
            ? (camY - 6.0f + nextFloat() * RAIN_HEIGHT_SPAN)
            : (camY + 10.0f + nextFloat() * 6.0f);
        DROP_SPEED[index] = 0.85f + nextFloat() * 0.35f;
        DROP_LEN[index] = 0.85f + nextFloat() * 0.65f;
    }

    private static void spawnRipple(float x, float y, float z) {
        int slot = nextRippleSlot;
        nextRippleSlot = (nextRippleSlot + 1) % MAX_RIPPLES;
        RIPPLE_X[slot] = x;
        RIPPLE_Y[slot] = y;
        RIPPLE_Z[slot] = z;
        RIPPLE_AGE[slot] = 0.0f;
    }

    private static int evalSkyColor(float t, int horizon, int mid, int zenith) {
        if (t <= 0.0f) {
            return horizon;
        }
        if (t >= 1.0f) {
            return zenith;
        }
        if (t < 0.40f) {
            return lerpRgb(horizon, mid, t / 0.40f);
        }
        return lerpRgb(mid, zenith, (t - 0.40f) / 0.60f);
    }

    private static int lerpRgb(int c1, int c2, float t) {
        float inv = 1.0f - t;
        int r = Math.round(((c1 >>> 16) & 0xFF) * inv + ((c2 >>> 16) & 0xFF) * t);
        int g = Math.round(((c1 >>> 8) & 0xFF) * inv + ((c2 >>> 8) & 0xFF) * t);
        int b = Math.round((c1 & 0xFF) * inv + (c2 & 0xFF) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float nextFloat() {
        rngState ^= (rngState << 13);
        rngState ^= (rngState >>> 17);
        rngState ^= (rngState << 5);
        return (rngState & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }
}
