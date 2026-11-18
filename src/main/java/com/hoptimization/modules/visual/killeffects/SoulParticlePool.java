package com.hoptimization.modules.visual.killeffects;

import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Zero-allocation primitive-backed particle pool (capacity 256) for Soul Sever wisps,
 * cleave/impale sparks, Annihilation dissolution motes, and Abyss Vortex suction particles.
 */
public final class SoulParticlePool {
    public static final int CAPACITY = 256;

    public static final int KIND_SOUL_WISP = 0;
    public static final int KIND_CLEAVE_SPARK = 1;
    public static final int KIND_ANNIHILATION_MOTE = 2;
    public static final int KIND_ABYSS_VORTEX = 3;

    private static final float NANOS_TO_SECONDS = 1.0E-9F;
    private static final float MAX_DT_SECONDS = 0.05F;
    private static final double VORTEX_ABSORB_DIST_SQ = 0.0081D;

    private final boolean[] active = new boolean[CAPACITY];
    private final byte[] kind = new byte[CAPACITY];
    private final double[] x = new double[CAPACITY];
    private final double[] y = new double[CAPACITY];
    private final double[] z = new double[CAPACITY];
    private final double[] targetX = new double[CAPACITY];
    private final double[] targetY = new double[CAPACITY];
    private final double[] targetZ = new double[CAPACITY];
    private final float[] vx = new float[CAPACITY];
    private final float[] vy = new float[CAPACITY];
    private final float[] vz = new float[CAPACITY];
    private final float[] drag = new float[CAPACITY];
    private final float[] gravity = new float[CAPACITY];
    private final float[] size = new float[CAPACITY];
    private final int[] argb = new int[CAPACITY];
    private final long[] spawnNanos = new long[CAPACITY];
    private final long[] lifeNanos = new long[CAPACITY];

    private int activeCount = 0;
    private int nextCursor = 0;
    private long lastUpdateNanos = 0L;
    private int rngState = 0x6D2B79F5;

    /**
     * Clears all active particles from the pool without allocating.
     */
    public void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            this.active[i] = false;
            this.targetX[i] = 0.0D;
            this.targetY[i] = 0.0D;
            this.targetZ[i] = 0.0D;
        }
        this.activeCount = 0;
        this.nextCursor = 0;
        this.lastUpdateNanos = 0L;
    }

    /**
     * Returns whether any particle is currently alive in the pool.
     *
     * @return {@code true} if at least one particle is active
     */
    public boolean hasActive() {
        return this.activeCount > 0;
    }

    /**
     * Advances particle simulation to {@code nowNanos} and expires finished particles.
     *
     * @param nowNanos current monotonic timestamp from {@link System#nanoTime()}
     */
    public void update(long nowNanos) {
        if (this.activeCount <= 0) {
            this.lastUpdateNanos = nowNanos;
            return;
        }
        float dt = 0.0F;
        if (this.lastUpdateNanos > 0L && nowNanos > this.lastUpdateNanos) {
            dt = (nowNanos - this.lastUpdateNanos) * NANOS_TO_SECONDS;
            if (dt > MAX_DT_SECONDS) {
                dt = MAX_DT_SECONDS;
            }
        }
        this.lastUpdateNanos = nowNanos;

        int alive = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (!this.active[i]) {
                continue;
            }
            long ageNanos = nowNanos - this.spawnNanos[i];
            if (ageNanos < 0L) {
                ageNanos = 0L;
            }
            if (ageNanos >= this.lifeNanos[i]) {
                this.active[i] = false;
                continue;
            }
            alive++;
            if (dt <= 0.0F) {
                continue;
            }

            float vyVal = this.vy[i] + this.gravity[i] * dt;
            float damping = 1.0F - this.drag[i] * dt;
            if (damping < 0.0F) {
                damping = 0.0F;
            }
            float vxVal = this.vx[i] * damping;
            float vzVal = this.vz[i] * damping;
            vyVal *= damping;

            if (this.kind[i] == KIND_SOUL_WISP) {
                float swirlAngle = (ageNanos * NANOS_TO_SECONDS) * 5.2F + (i * 0.73F);
                vxVal += (float) Math.cos(swirlAngle) * (0.28F * dt);
                vzVal += (float) Math.sin(swirlAngle) * (0.28F * dt);
            } else if (this.kind[i] == KIND_ABYSS_VORTEX) {
                double toX = this.targetX[i] - this.x[i];
                double toY = this.targetY[i] - this.y[i];
                double toZ = this.targetZ[i] - this.z[i];
                double distSq = toX * toX + toY * toY + toZ * toZ;
                if (distSq < VORTEX_ABSORB_DIST_SQ) {
                    this.active[i] = false;
                    alive--;
                    continue;
                }
                double dist = Math.sqrt(distSq);
                float invDist = (float) (1.0D / dist);
                float dirX = (float) (toX * invDist);
                float dirY = (float) (toY * invDist);
                float dirZ = (float) (toZ * invDist);

                float proximity = 1.0F + 1.5F / (0.40F + (float) dist);
                float pullAccel = 4.6F * proximity;
                vxVal += dirX * pullAccel * dt;
                vyVal += dirY * pullAccel * dt;
                vzVal += dirZ * pullAccel * dt;

                double horizSq = toX * toX + toZ * toZ;
                if (horizSq > 1.0E-6D) {
                    double horizDist = Math.sqrt(horizSq);
                    float invHoriz = (float) (1.0D / horizDist);
                    float tangX = (float) (-toZ * invHoriz);
                    float tangZ = (float) (toX * invHoriz);
                    float swirlFactor = horizDist < 0.45D ? (float) (horizDist / 0.45D) : 1.0F;
                    float swirlAccel = 6.8F * swirlFactor * (0.65F + 0.35F * proximity);
                    vxVal += tangX * swirlAccel * dt;
                    vzVal += tangZ * swirlAccel * dt;
                }

                if (this.y[i] > this.targetY[i] + 0.03D) {
                    vyVal -= 2.1F * dt;
                }

                this.vx[i] = vxVal;
                this.vy[i] = vyVal;
                this.vz[i] = vzVal;
                this.x[i] += vxVal * dt;
                this.y[i] += vyVal * dt;
                this.z[i] += vzVal * dt;
                if (this.y[i] < this.targetY[i] - 0.02D) {
                    this.y[i] = this.targetY[i] - 0.02D;
                    if (this.vy[i] < 0.0F) {
                        this.vy[i] = 0.0F;
                    }
                }

                double postX = this.targetX[i] - this.x[i];
                double postY = this.targetY[i] - this.y[i];
                double postZ = this.targetZ[i] - this.z[i];
                double postDistSq = postX * postX + postY * postY + postZ * postZ;
                boolean crossedCenter = distSq < 0.09D && (toX * postX + toY * postY + toZ * postZ) <= 0.0D;
                if (postDistSq < VORTEX_ABSORB_DIST_SQ || crossedCenter) {
                    this.active[i] = false;
                    alive--;
                }
                continue;
            }

            this.vx[i] = vxVal;
            this.vy[i] = vyVal;
            this.vz[i] = vzVal;
            this.x[i] += vxVal * dt;
            this.y[i] += vyVal * dt;
            this.z[i] += vzVal * dt;
        }
        this.activeCount = alive;
    }

    /**
     * Spawns a single particle into the pool, overwriting the oldest particle if full.
     *
     * @param particleKind kind constant ({@code KIND_SOUL_WISP}, {@code KIND_CLEAVE_SPARK},
     *                     or {@code KIND_ANNIHILATION_MOTE})
     * @param px           world X coordinate
     * @param py           world Y coordinate
     * @param pz           world Z coordinate
     * @param pvx          velocity X in blocks/sec
     * @param pvy          velocity Y in blocks/sec
     * @param pvz          velocity Z in blocks/sec
     * @param pGravity     vertical acceleration in blocks/sec^2
     * @param pDrag        linear damping rate per second
     * @param pSize        billboard half-size in blocks
     * @param pArgb        packed ARGB color
     * @param nowNanos     spawn timestamp in nanoseconds
     * @param pLifeNanos   lifetime duration in nanoseconds
     */
    public void spawn(
        int particleKind,
        double px,
        double py,
        double pz,
        float pvx,
        float pvy,
        float pvz,
        float pGravity,
        float pDrag,
        float pSize,
        int pArgb,
        long nowNanos,
        long pLifeNanos
    ) {
        spawnTargeted(
            particleKind,
            px, py, pz,
            px, py, pz,
            pvx, pvy, pvz,
            pGravity, pDrag, pSize,
            pArgb, nowNanos, pLifeNanos
        );
    }

    private void spawnTargeted(
        int particleKind,
        double px,
        double py,
        double pz,
        double tx,
        double ty,
        double tz,
        float pvx,
        float pvy,
        float pvz,
        float pGravity,
        float pDrag,
        float pSize,
        int pArgb,
        long nowNanos,
        long pLifeNanos
    ) {
        int slot = acquireSlot(nowNanos);
        if (!this.active[slot]) {
            this.activeCount++;
        }
        this.active[slot] = true;
        this.kind[slot] = (byte) particleKind;
        this.x[slot] = px;
        this.y[slot] = py;
        this.z[slot] = pz;
        this.targetX[slot] = tx;
        this.targetY[slot] = ty;
        this.targetZ[slot] = tz;
        this.vx[slot] = pvx;
        this.vy[slot] = pvy;
        this.vz[slot] = pvz;
        this.gravity[slot] = pGravity;
        this.drag[slot] = pDrag;
        this.size[slot] = pSize;
        this.argb[slot] = pArgb;
        this.spawnNanos[slot] = nowNanos;
        this.lifeNanos[slot] = pLifeNanos > 1L ? pLifeNanos : 1L;
    }

    /**
     * Spawns a burst of diagonal cleave sparks and soul wisps at the sever impact point.
     *
     * @param cx        impact center world X
     * @param cy        impact center world Y
     * @param cz        impact center world Z
     * @param rightX    unit horizontal viewer-right X component
     * @param rightZ    unit horizontal viewer-right Z component
     * @param behindX   unit horizontal behind-entity X component
     * @param behindZ   unit horizontal behind-entity Z component
     * @param radius    entity scale radius in blocks
     * @param soulArgb  soul color in ARGB format
     * @param nowNanos  current timestamp in nanoseconds
     */
    public void spawnCleaveBurst(
        double cx,
        double cy,
        double cz,
        float rightX,
        float rightZ,
        float behindX,
        float behindZ,
        float radius,
        int soulArgb,
        long nowNanos
    ) {
        float safeRadius = radius > 0.4F ? radius : 0.4F;
        // Diagonal slash direction: from upper-right toward lower-left across the soul.
        float slashDirX = -rightX * 0.72F;
        float slashDirY = -0.69F;
        float slashDirZ = -rightZ * 0.72F;

        for (int i = 0; i < 24; i++) {
            float along = (nextRandomFloat() - 0.5F) * safeRadius * 1.4F;
            double sx = cx + rightX * along * 0.7F + (nextRandomFloat() - 0.5F) * 0.16F;
            double sy = cy - along * 0.55F + (nextRandomFloat() - 0.5F) * 0.16F;
            double sz = cz + rightZ * along * 0.7F + (nextRandomFloat() - 0.5F) * 0.16F;

            float speed = 2.6F + nextRandomFloat() * 3.4F;
            float spreadR = (nextRandomFloat() - 0.5F) * 1.9F;
            float spreadU = (nextRandomFloat() - 0.35F) * 1.6F;
            float spreadB = (nextRandomFloat() - 0.5F) * 2.2F;

            float pvx = slashDirX * speed + rightX * spreadR + behindX * spreadB;
            float pvy = slashDirY * speed * 0.55F + spreadU;
            float pvz = slashDirZ * speed + rightZ * spreadR + behindZ * spreadB;

            int color = (i % 3 == 0) ? brightenArgb(soulArgb, 0.55F) : soulArgb;
            long life = (long) ((0.30F + nextRandomFloat() * 0.28F) * 1.0E9F);
            float sparkSize = 0.055F + nextRandomFloat() * 0.045F;

            spawn(
                KIND_CLEAVE_SPARK,
                sx, sy, sz,
                pvx, pvy, pvz,
                -1.6F, 2.8F, sparkSize,
                color, nowNanos, life
            );
        }

        for (int i = 0; i < 10; i++) {
            float angle = nextRandomFloat() * 6.2831855F;
            float r = (0.2F + nextRandomFloat() * 0.7F) * safeRadius;
            double wx = cx + Math.cos(angle) * r;
            double wy = cy + (nextRandomFloat() - 0.5F) * safeRadius * 0.7F;
            double wz = cz + Math.sin(angle) * r;
            float outSpeed = 0.45F + nextRandomFloat() * 0.75F;
            long life = (long) ((0.45F + nextRandomFloat() * 0.40F) * 1.0E9F);
            spawn(
                KIND_SOUL_WISP,
                wx, wy, wz,
                (float) Math.cos(angle) * outSpeed,
                0.55F + nextRandomFloat() * 0.65F,
                (float) Math.sin(angle) * outSpeed,
                0.35F, 1.4F, 0.085F + nextRandomFloat() * 0.055F,
                brightenArgb(soulArgb, 0.25F), nowNanos, life
            );
        }
    }

    /**
     * Spawns a gently rising ambient soul wisp within the entity's bounding volume.
     *
     * @param baseX    entity base world X
     * @param baseY    entity base world Y
     * @param baseZ    entity base world Z
     * @param width    entity bounding width
     * @param height   entity bounding height
     * @param soulArgb soul color in ARGB format
     * @param nowNanos current timestamp in nanoseconds
     */
    public void spawnSoulWisp(
        double baseX,
        double baseY,
        double baseZ,
        float width,
        float height,
        int soulArgb,
        long nowNanos
    ) {
        float halfW = (width > 0.3F ? width : 0.6F) * 0.55F;
        float h = height > 0.4F ? height : 1.8F;
        double px = baseX + (nextRandomFloat() - 0.5F) * 2.0F * halfW;
        double py = baseY + nextRandomFloat() * h * 0.85F;
        double pz = baseZ + (nextRandomFloat() - 0.5F) * 2.0F * halfW;

        long life = (long) ((0.45F + nextRandomFloat() * 0.45F) * 1.0E9F);
        spawn(
            KIND_SOUL_WISP,
            px, py, pz,
            (nextRandomFloat() - 0.5F) * 0.22F,
            0.42F + nextRandomFloat() * 0.48F,
            (nextRandomFloat() - 0.5F) * 0.22F,
            0.25F, 0.9F, 0.065F + nextRandomFloat() * 0.045F,
            soulArgb, nowNanos, life
        );
    }

    /**
     * Spawns a rising dissolution mote for Annihilation mode.
     *
     * @param baseX    entity base world X
     * @param baseY    entity base world Y
     * @param baseZ    entity base world Z
     * @param width    entity bounding width
     * @param height   entity bounding height
     * @param nowNanos current timestamp in nanoseconds
     */
    public void spawnAnnihilationMote(
        double baseX,
        double baseY,
        double baseZ,
        float width,
        float height,
        long nowNanos
    ) {
        float halfW = (width > 0.3F ? width : 0.6F) * 0.55F;
        float h = height > 0.4F ? height : 1.8F;
        double px = baseX + (nextRandomFloat() - 0.5F) * 2.0F * halfW;
        double py = baseY + 0.05D + nextRandomFloat() * h * 0.90F;
        double pz = baseZ + (nextRandomFloat() - 0.5F) * 2.0F * halfW;

        long life = (long) ((0.38F + nextRandomFloat() * 0.42F) * 1.0E9F);
        int tint = (nextRandomFloat() > 0.45F) ? 0xFFE4ECF7 : 0xFFB8C7DB;
        spawn(
            KIND_ANNIHILATION_MOTE,
            px, py, pz,
            (nextRandomFloat() - 0.5F) * 0.30F,
            0.48F + nextRandomFloat() * 0.55F,
            (nextRandomFloat() - 0.5F) * 0.30F,
            0.30F, 1.2F, 0.050F + nextRandomFloat() * 0.035F,
            tint, nowNanos, life
        );
    }

    /**
     * Spawns a 6-way radial impale spark burst and rising soul wisps for Phantom Swords mode.
     *
     * @param cx       impale core world X
     * @param cy       impale core world Y
     * @param cz       impale core world Z
     * @param radius   entity scale radius in blocks
     * @param soulArgb soul color in ARGB format
     * @param nowNanos current timestamp in nanoseconds
     */
    public void spawnPhantomImpaleBurst(
        double cx,
        double cy,
        double cz,
        float radius,
        int soulArgb,
        long nowNanos
    ) {
        float safeRadius = radius > 0.4F ? radius : 0.4F;
        for (int arm = 0; arm < 6; arm++) {
            double armAngle = arm * (Math.PI * 2.0D / 6.0D);
            float dirX = (float) Math.cos(armAngle);
            float dirZ = (float) Math.sin(armAngle);
            for (int j = 0; j < 4; j++) {
                float offset = (0.08F + nextRandomFloat() * 0.14F) * safeRadius;
                double sx = cx + dirX * offset + (nextRandomFloat() - 0.5F) * 0.10F;
                double sy = cy + (nextRandomFloat() - 0.5F) * 0.24F;
                double sz = cz + dirZ * offset + (nextRandomFloat() - 0.5F) * 0.10F;

                float speed = 2.4F + nextRandomFloat() * 2.8F;
                float pvx = dirX * speed + (nextRandomFloat() - 0.5F) * 0.85F;
                float pvy = (nextRandomFloat() - 0.35F) * 1.9F;
                float pvz = dirZ * speed + (nextRandomFloat() - 0.5F) * 0.85F;

                int color = (j == 0) ? brightenArgb(soulArgb, 0.55F) : soulArgb;
                long life = (long) ((0.28F + nextRandomFloat() * 0.26F) * 1.0E9F);
                float sparkSize = 0.055F + nextRandomFloat() * 0.040F;

                spawn(
                    KIND_CLEAVE_SPARK,
                    sx, sy, sz,
                    pvx, pvy, pvz,
                    -1.4F, 2.6F, sparkSize,
                    color, nowNanos, life
                );
            }
        }

        for (int i = 0; i < 10; i++) {
            float angle = nextRandomFloat() * 6.2831855F;
            float r = (0.18F + nextRandomFloat() * 0.65F) * safeRadius;
            double wx = cx + Math.cos(angle) * r;
            double wy = cy + (nextRandomFloat() - 0.45F) * safeRadius * 0.6F;
            double wz = cz + Math.sin(angle) * r;
            float outSpeed = 0.35F + nextRandomFloat() * 0.60F;
            long life = (long) ((0.45F + nextRandomFloat() * 0.38F) * 1.0E9F);
            spawn(
                KIND_SOUL_WISP,
                wx, wy, wz,
                (float) Math.cos(angle) * outSpeed,
                0.65F + nextRandomFloat() * 0.75F,
                (float) Math.sin(angle) * outSpeed,
                0.35F, 1.3F, 0.080F + nextRandomFloat() * 0.050F,
                brightenArgb(soulArgb, 0.30F), nowNanos, life
            );
        }
    }

    /**
     * Spawns a dark-purple or violet suction particle in a cylindrical volume around the entity,
     * drawn inward toward the ground portal center {@code (cx, groundY + 0.02, cz)}.
     *
     * @param cx       portal center world X
     * @param groundY  portal base world Y
     * @param cz       portal center world Z
     * @param radius   entity scale radius in blocks
     * @param height   entity bounding height in blocks
     * @param nowNanos current timestamp in nanoseconds
     */
    public void spawnAbyssSuctionParticle(
        double cx,
        double groundY,
        double cz,
        float radius,
        float height,
        long nowNanos
    ) {
        float safeR = radius > 0.35F ? radius : 0.35F;
        float safeH = height > 0.40F ? height : 1.8F;

        float angle = nextRandomFloat() * 6.2831855F;
        float radialDist = safeR * (0.45F + nextRandomFloat() * 1.15F);
        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);

        double px = cx + cosA * radialDist;
        double py = groundY + 0.08D + nextRandomFloat() * (safeH * 0.92F);
        double pz = cz + sinA * radialDist;

        float tangX = (float) -sinA;
        float tangZ = (float) cosA;
        float tangSpeed = 1.1F + nextRandomFloat() * 1.1F;
        float pvx = tangX * tangSpeed;
        float pvy = -0.25F - nextRandomFloat() * 0.45F;
        float pvz = tangZ * tangSpeed;

        float colorRoll = nextRandomFloat();
        int tint;
        if (colorRoll < 0.33F) {
            tint = 0xFF24063B;
        } else if (colorRoll < 0.72F) {
            tint = 0xFF6C22C8;
        } else {
            tint = 0xFFC45BFF;
        }

        long life = (long) ((0.44F + nextRandomFloat() * 0.34F) * 1.0E9F);
        float pSize = 0.050F + nextRandomFloat() * 0.040F;

        spawnTargeted(
            KIND_ABYSS_VORTEX,
            px, py, pz,
            cx, groundY + 0.02D, cz,
            pvx, pvy, pvz,
            0.0F, 2.8F, pSize,
            tint, nowNanos, life
        );
    }

    /**
     * Renders all active particles in batched zero-allocation {@link Tessellator} passes.
     *
     * @param camX     camera world X
     * @param camY     camera world Y
     * @param camZ     camera world Z
     * @param nowNanos current timestamp in nanoseconds
     */
    public void render(double camX, double camY, double camZ, long nowNanos) {
        if (this.activeCount <= 0) {
            return;
        }

        boolean hasAlphaPass = false;
        boolean hasAdditivePass = false;
        for (int i = 0; i < CAPACITY; i++) {
            if (!this.active[i]) {
                continue;
            }
            if (isAlphaPassParticle(i)) {
                hasAlphaPass = true;
            } else {
                hasAdditivePass = true;
            }
            if (hasAlphaPass && hasAdditivePass) {
                break;
            }
        }
        if (!hasAlphaPass && !hasAdditivePass) {
            return;
        }

        float rightX = ActiveRenderInfo.getRotationX();
        float rightZ = ActiveRenderInfo.getRotationZ();
        float upX = ActiveRenderInfo.getRotationYZ();
        float upY = ActiveRenderInfo.getRotationXZ();
        float upZ = ActiveRenderInfo.getRotationXY();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();

        if (hasAlphaPass) {
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < CAPACITY; i++) {
                if (!this.active[i] || !isAlphaPassParticle(i)) {
                    continue;
                }
                if (this.kind[i] == KIND_ABYSS_VORTEX) {
                    emitVelocitySpark(
                        wr, i, camX, camY, camZ, nowNanos,
                        rightX, rightZ, upX, upY, upZ
                    );
                } else {
                    emitBillboardDiamond(
                        wr, i, camX, camY, camZ, nowNanos,
                        rightX, rightZ, upX, upY, upZ
                    );
                }
            }
            tessellator.draw();
        }

        if (hasAdditivePass) {
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ONE, GL11.GL_ZERO
            );
            wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < CAPACITY; i++) {
                if (!this.active[i] || isAlphaPassParticle(i)) {
                    continue;
                }
                if (this.kind[i] == KIND_CLEAVE_SPARK || this.kind[i] == KIND_ABYSS_VORTEX) {
                    emitVelocitySpark(
                        wr, i, camX, camY, camZ, nowNanos,
                        rightX, rightZ, upX, upY, upZ
                    );
                } else {
                    emitBillboardDiamond(
                        wr, i, camX, camY, camZ, nowNanos,
                        rightX, rightZ, upX, upY, upZ
                    );
                }
            }
            tessellator.draw();
        }

        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
    }

    private void emitBillboardDiamond(
        WorldRenderer wr,
        int slot,
        double camX,
        double camY,
        double camZ,
        long nowNanos,
        float rightX,
        float rightZ,
        float upX,
        float upY,
        float upZ
    ) {
        float progress = getProgress(slot, nowNanos);
        float alpha = computeAlphaCurve(progress) * 0.85F;
        float distScale = getVortexProximityScale(slot);
        alpha *= (0.25F + 0.75F * distScale);
        if (alpha <= 0.004F) {
            return;
        }
        float radius = this.size[slot] * (0.65F + 0.55F * (1.0F - progress)) * (0.35F + 0.65F * distScale);
        float px = (float) (this.x[slot] - camX);
        float py = (float) (this.y[slot] - camY);
        float pz = (float) (this.z[slot] - camZ);

        int c = this.argb[slot];
        float r = ((c >>> 16) & 0xFF) / 255.0F;
        float g = ((c >>> 8) & 0xFF) / 255.0F;
        float b = (c & 0xFF) / 255.0F;

        float rx = rightX * radius;
        float rz = rightZ * radius;
        float ux = upX * radius;
        float uy = upY * radius;
        float uz = upZ * radius;

        float p1x = px + rx;
        float p1y = py;
        float p1z = pz + rz;

        float p2x = px + ux;
        float p2y = py + uy;
        float p2z = pz + uz;

        float p3x = px - rx;
        float p3y = py;
        float p3z = pz - rz;

        float p4x = px - ux;
        float p4y = py - uy;
        float p4z = pz - uz;

        emitDiamondTriangles(
            wr, px, py, pz,
            p1x, p1y, p1z,
            p2x, p2y, p2z,
            p3x, p3y, p3z,
            p4x, p4y, p4z,
            r, g, b, alpha
        );
    }

    private void emitVelocitySpark(
        WorldRenderer wr,
        int slot,
        double camX,
        double camY,
        double camZ,
        long nowNanos,
        float rightX,
        float rightZ,
        float upX,
        float upY,
        float upZ
    ) {
        float progress = getProgress(slot, nowNanos);
        float distScale = getVortexProximityScale(slot);
        float alpha = computeAlphaCurve(progress) * (0.20F + 0.80F * distScale);
        if (alpha <= 0.004F) {
            return;
        }

        float px = (float) (this.x[slot] - camX);
        float py = (float) (this.y[slot] - camY);
        float pz = (float) (this.z[slot] - camZ);

        float vxVal = this.vx[slot];
        float vyVal = this.vy[slot];
        float vzVal = this.vz[slot];
        float speedSq = vxVal * vxVal + vyVal * vyVal + vzVal * vzVal;

        if (speedSq < 0.04F) {
            emitBillboardDiamond(wr, slot, camX, camY, camZ, nowNanos, rightX, rightZ, upX, upY, upZ);
            return;
        }

        float invSpeed = (float) (1.0D / Math.sqrt(speedSq));
        float dirX = vxVal * invSpeed;
        float dirY = vyVal * invSpeed;
        float dirZ = vzVal * invSpeed;

        // Cross velocity with camera-to-particle ray to build a screen-facing spark ribbon.
        float sx = dirY * pz - dirZ * py;
        float sy = dirZ * px - dirX * pz;
        float sz = dirX * py - dirY * px;
        float sideLenSq = sx * sx + sy * sy + sz * sz;
        if (sideLenSq < 1.0E-6F) {
            emitBillboardDiamond(wr, slot, camX, camY, camZ, nowNanos, rightX, rightZ, upX, upY, upZ);
            return;
        }
        float invSide = (float) (1.0D / Math.sqrt(sideLenSq));
        sx *= invSide;
        sy *= invSide;
        sz *= invSide;

        float speed = speedSq * invSpeed;
        float radius = this.size[slot] * (1.0F - 0.45F * progress) * (0.35F + 0.65F * distScale);
        float halfWidth = radius * 0.45F;
        float headLen = radius * 1.4F;
        float tailLen = radius * (2.2F + Math.min(speed * 0.45F, 2.4F));

        int c = this.argb[slot];
        float baseR = ((c >>> 16) & 0xFF) / 255.0F;
        float baseG = ((c >>> 8) & 0xFF) / 255.0F;
        float baseB = (c & 0xFF) / 255.0F;
        float coreLift = 0.55F;
        if (this.kind[slot] == KIND_ABYSS_VORTEX) {
            coreLift = isAlphaPassParticle(slot) ? 0.08F : 0.25F;
        }
        float coreR = baseR + (1.0F - baseR) * coreLift;
        float coreG = baseG + (1.0F - baseG) * coreLift;
        float coreB = baseB + (1.0F - baseB) * coreLift;

        float p1x = px + dirX * headLen;
        float p1y = py + dirY * headLen;
        float p1z = pz + dirZ * headLen;

        float p2x = px + sx * halfWidth;
        float p2y = py + sy * halfWidth;
        float p2z = pz + sz * halfWidth;

        float p3x = px - dirX * tailLen;
        float p3y = py - dirY * tailLen;
        float p3z = pz - dirZ * tailLen;

        float p4x = px - sx * halfWidth;
        float p4y = py - sy * halfWidth;
        float p4z = pz - sz * halfWidth;

        emitDiamondTriangles(
            wr, px, py, pz,
            p1x, p1y, p1z,
            p2x, p2y, p2z,
            p3x, p3y, p3z,
            p4x, p4y, p4z,
            coreR, coreG, coreB, alpha
        );
    }

    private static void emitDiamondTriangles(
        WorldRenderer wr,
        float cx, float cy, float cz,
        float p1x, float p1y, float p1z,
        float p2x, float p2y, float p2z,
        float p3x, float p3y, float p3z,
        float p4x, float p4y, float p4z,
        float r, float g, float b, float alpha
    ) {
        wr.pos(cx, cy, cz).color(r, g, b, alpha).endVertex();
        wr.pos(p1x, p1y, p1z).color(r, g, b, 0.0F).endVertex();
        wr.pos(p2x, p2y, p2z).color(r, g, b, 0.0F).endVertex();

        wr.pos(cx, cy, cz).color(r, g, b, alpha).endVertex();
        wr.pos(p2x, p2y, p2z).color(r, g, b, 0.0F).endVertex();
        wr.pos(p3x, p3y, p3z).color(r, g, b, 0.0F).endVertex();

        wr.pos(cx, cy, cz).color(r, g, b, alpha).endVertex();
        wr.pos(p3x, p3y, p3z).color(r, g, b, 0.0F).endVertex();
        wr.pos(p4x, p4y, p4z).color(r, g, b, 0.0F).endVertex();

        wr.pos(cx, cy, cz).color(r, g, b, alpha).endVertex();
        wr.pos(p4x, p4y, p4z).color(r, g, b, 0.0F).endVertex();
        wr.pos(p1x, p1y, p1z).color(r, g, b, 0.0F).endVertex();
    }

    private boolean isAlphaPassParticle(int slot) {
        byte k = this.kind[slot];
        if (k == KIND_ANNIHILATION_MOTE) {
            return true;
        }
        return k == KIND_ABYSS_VORTEX && ((this.argb[slot] >>> 16) & 0xFF) < 0x50;
    }

    private float getVortexProximityScale(int slot) {
        if (this.kind[slot] != KIND_ABYSS_VORTEX) {
            return 1.0F;
        }
        double dx = this.targetX[slot] - this.x[slot];
        double dy = this.targetY[slot] - this.y[slot];
        double dz = this.targetZ[slot] - this.z[slot];
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq >= 0.1225D) {
            return 1.0F;
        }
        return (float) Math.sqrt(distSq) / 0.35F;
    }

    private float getProgress(int slot, long nowNanos) {
        long elapsed = nowNanos - this.spawnNanos[slot];
        if (elapsed <= 0L) {
            return 0.0F;
        }
        long life = this.lifeNanos[slot];
        if (elapsed >= life) {
            return 1.0F;
        }
        return (float) elapsed / (float) life;
    }

    private static float computeAlphaCurve(float progress) {
        if (progress <= 0.15F) {
            return progress / 0.15F;
        }
        float fade = (progress - 0.15F) / 0.85F;
        float inv = 1.0F - fade;
        return inv * inv;
    }

    private int acquireSlot(long nowNanos) {
        for (int n = 0; n < CAPACITY; n++) {
            int idx = (this.nextCursor + n) & (CAPACITY - 1);
            if (!this.active[idx]) {
                this.nextCursor = (idx + 1) & (CAPACITY - 1);
                return idx;
            }
        }
        int oldestSlot = 0;
        float maxProgress = -1.0F;
        for (int i = 0; i < CAPACITY; i++) {
            float p = getProgress(i, nowNanos);
            if (p > maxProgress) {
                maxProgress = p;
                oldestSlot = i;
            }
        }
        this.nextCursor = (oldestSlot + 1) & (CAPACITY - 1);
        return oldestSlot;
    }

    private float nextRandomFloat() {
        int xVal = this.rngState;
        xVal ^= (xVal << 13);
        xVal ^= (xVal >>> 17);
        xVal ^= (xVal << 5);
        this.rngState = xVal;
        return (xVal & 0x00FFFFFF) / 16777216.0F;
    }

    private static int brightenArgb(int argb, float amount) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        int nr = Math.min(255, Math.round(r + (255 - r) * amount));
        int ng = Math.min(255, Math.round(g + (255 - g) * amount));
        int nb = Math.min(255, Math.round(b + (255 - b) * amount));
        return (a << 24) | (nr << 16) | (ng << 8) | nb;
    }
}
