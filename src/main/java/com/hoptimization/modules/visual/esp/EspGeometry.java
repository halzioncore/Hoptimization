package com.hoptimization.modules.visual.esp;

/**
 * Precomputed futuristic mech-cockpit ESP geometry in Minecraft {@code 1/16} model units.
 * All vertex, normal, structural edge, accent, skeleton, and ground-ring arrays are built
 * once in {@code <clinit>} with self-checked outward triangle winding for {@code GL_CULL_FACE}.
 */
public final class EspGeometry {
    static final int GROUND_RING_SEGMENTS = 8;
    static final float[] GROUND_RING_COS = new float[GROUND_RING_SEGMENTS + 1];
    static final float[] GROUND_RING_SIN = new float[GROUND_RING_SEGMENTS + 1];
    static final float[] GROUND_TICK_X = new float[8];
    static final float[] GROUND_TICK_Z = new float[8];

    static final PartMesh HEAD_MESH;
    static final PartMesh TORSO_MESH;
    static final PartMesh RIGHT_ARM_DEFAULT_MESH;
    static final PartMesh LEFT_ARM_DEFAULT_MESH;
    static final PartMesh RIGHT_ARM_SLIM_MESH;
    static final PartMesh LEFT_ARM_SLIM_MESH;
    static final PartMesh RIGHT_LEG_MESH;
    static final PartMesh LEFT_LEG_MESH;

    static {
        // Rotate octagon by 22.5 deg so cardinal sides sit flat at front/back/left/right.
        double angleOffset = Math.PI / 8.0D;
        for (int i = 0; i <= GROUND_RING_SEGMENTS; i++) {
            double theta = angleOffset + (Math.PI * 2.0D * i) / GROUND_RING_SEGMENTS;
            GROUND_RING_COS[i] = (float) Math.cos(theta);
            GROUND_RING_SIN[i] = (float) Math.sin(theta);
        }
        // Four diagonal reticle corner ticks radiating outward from 45, 135, 225, 315 deg.
        for (int i = 0; i < 4; i++) {
            double diag = (Math.PI * 0.25D) + i * (Math.PI * 0.5D);
            float c = (float) Math.cos(diag);
            float s = (float) Math.sin(diag);
            GROUND_TICK_X[i * 2] = c * 0.96F;
            GROUND_TICK_Z[i * 2] = s * 0.96F;
            GROUND_TICK_X[i * 2 + 1] = c * 1.18F;
            GROUND_TICK_Z[i * 2 + 1] = s * 1.18F;
        }

        HEAD_MESH = buildHeadMesh();
        TORSO_MESH = buildTorsoMesh();
        RIGHT_ARM_DEFAULT_MESH = buildArmMesh(-1.0F, 2.0F, -1.0F);
        LEFT_ARM_DEFAULT_MESH = buildArmMesh(1.0F, 2.0F, 1.0F);
        RIGHT_ARM_SLIM_MESH = buildArmMesh(-0.5F, 1.5F, -1.0F);
        LEFT_ARM_SLIM_MESH = buildArmMesh(0.5F, 1.5F, 1.0F);
        RIGHT_LEG_MESH = buildLegMesh(-1.0F);
        LEFT_LEG_MESH = buildLegMesh(1.0F);
    }

    private EspGeometry() {
    }

    private static PartMesh buildHeadMesh() {
        MeshBuilder b = new MeshBuilder(0.0F, -4.10F, 0.0F);
        float[][] rings = {
            makeOctagonRing(0.0F, -7.85F, 0.0F, 2.65F, 2.65F, 0.80F, 0.80F),
            makeOctagonRing(0.0F, -6.55F, 0.0F, 3.85F, 3.85F, 1.10F, 1.10F),
            makeOctagonRing(0.0F, -2.15F, 0.0F, 3.70F, 3.75F, 1.05F, 1.05F),
            makeOctagonRing(0.0F, -0.35F, 0.0F, 2.55F, 2.60F, 0.75F, 0.75F)
        };
        addOctagonPrism(b, rings);

        // Front (-Z) tactical visor slit proud of the helmet face.
        float[] v0 = {-2.45F, -4.42F, -3.92F};
        float[] v1 = {-1.95F, -4.95F, -3.93F};
        float[] v2 = {1.95F, -4.95F, -3.93F};
        float[] v3 = {2.45F, -4.42F, -3.92F};
        float[] v4 = {1.98F, -3.88F, -3.90F};
        float[] v5 = {-1.98F, -3.88F, -3.90F};
        b.addAccentQuad(v1, v2, v3, v0);
        b.addAccentQuad(v0, v3, v4, v5);
        b.addAccentLoop(v0, v1, v2, v3, v4, v5);
        // Subtle brow sensor bridge and side temple ticks.
        b.addAccentLine(0.0F, -5.28F, -3.92F, 0.0F, -4.95F, -3.93F);
        b.addAccentLine(-3.10F, -4.35F, -3.35F, -2.45F, -4.42F, -3.92F);
        b.addAccentLine(3.10F, -4.35F, -3.35F, 2.45F, -4.42F, -3.92F);

        // Internal cranial core strut.
        b.addSkeletonLine(0.0F, -6.80F, 0.0F, 0.0F, -0.80F, 0.0F);
        b.addSkeletonLine(-2.20F, -4.40F, 0.0F, 2.20F, -4.40F, 0.0F);
        return b.build();
    }

    private static PartMesh buildTorsoMesh() {
        MeshBuilder b = new MeshBuilder(0.0F, 5.90F, 0.0F);
        float[][] rings = {
            makeOctagonRing(0.0F, 0.20F, 0.0F, 3.25F, 1.65F, 0.75F, 0.50F),
            makeOctagonRing(0.0F, 2.00F, 0.0F, 4.05F, 2.15F, 0.90F, 0.65F),
            makeOctagonRing(0.0F, 6.20F, 0.0F, 3.65F, 1.95F, 0.85F, 0.60F),
            makeOctagonRing(0.0F, 11.60F, 0.0F, 3.25F, 1.75F, 0.75F, 0.55F)
        };
        addOctagonPrism(b, rings);

        // Front (-Z) glowing reactor core diamond proud of the chest plate.
        float[] cTop = {0.0F, 2.55F, -2.24F};
        float[] cRight = {1.18F, 4.10F, -2.17F};
        float[] cBot = {0.0F, 5.65F, -2.10F};
        float[] cLeft = {-1.18F, 4.10F, -2.17F};
        b.addAccentQuad(cTop, cRight, cBot, cLeft);
        b.addAccentLoop(cTop, cRight, cBot, cLeft);
        b.addAccentLine(-2.55F, 4.10F, -2.12F, -1.18F, 4.10F, -2.17F);
        b.addAccentLine(1.18F, 4.10F, -2.17F, 2.55F, 4.10F, -2.12F);

        // Dorsal (+Z) spine accent fin and shoulder thruster lines.
        b.addAccentLine(0.0F, 1.90F, 2.23F, 0.0F, 9.80F, 1.91F);
        b.addAccentLine(0.0F, 2.10F, 2.23F, -0.60F, 4.60F, 2.12F);
        b.addAccentLine(0.0F, 2.10F, 2.23F, 0.60F, 4.60F, 2.12F);
        b.addAccentLine(-0.60F, 4.60F, 2.12F, 0.0F, 9.40F, 1.93F);
        b.addAccentLine(0.60F, 4.60F, 2.12F, 0.0F, 9.40F, 1.93F);
        b.addAccentLine(-0.55F, 2.60F, 2.21F, -2.35F, 1.95F, 2.22F);
        b.addAccentLine(0.55F, 2.60F, 2.21F, 2.35F, 1.95F, 2.22F);

        // Internal mecha frame struts (spine, clavicle yoke, pelvis bar).
        b.addSkeletonLine(0.0F, 0.60F, 0.0F, 0.0F, 11.00F, 0.0F);
        b.addSkeletonLine(-3.30F, 2.00F, 0.0F, 3.30F, 2.00F, 0.0F);
        b.addSkeletonLine(-2.10F, 10.80F, 0.0F, 2.10F, 10.80F, 0.0F);
        return b.build();
    }

    private static PartMesh buildArmMesh(float centerX, float halfW, float outerSign) {
        MeshBuilder b = new MeshBuilder(centerX, 3.95F, 0.0F);
        float[][] rings = {
            makeOctagonRing(centerX, -1.80F, 0.0F, halfW * 0.78F, 1.50F, halfW * 0.24F, 0.42F),
            makeOctagonRing(centerX, -0.30F, 0.0F, halfW, 1.95F, halfW * 0.28F, 0.55F),
            makeOctagonRing(centerX, 4.00F, 0.0F, halfW * 0.88F, 1.72F, halfW * 0.25F, 0.48F),
            makeOctagonRing(centerX, 7.90F, 0.0F, halfW * 0.80F, 1.56F, halfW * 0.22F, 0.44F),
            makeOctagonRing(centerX, 9.70F, 0.0F, halfW * 0.66F, 1.28F, halfW * 0.18F, 0.36F)
        };
        addOctagonPrism(b, rings);

        // Wrist cuff accent collar at y = 7.90.
        float[] cuffRing = makeOctagonRing(centerX, 7.90F, 0.0F, halfW * 0.84F, 1.63F, halfW * 0.23F, 0.46F);
        for (int i = 0; i < 8; i++) {
            int next = (i + 1) & 7;
            b.addAccentLine(
                cuffRing[i * 3], cuffRing[i * 3 + 1], cuffRing[i * 3 + 2],
                cuffRing[next * 3], cuffRing[next * 3 + 1], cuffRing[next * 3 + 2]
            );
        }

        // Shoulder and elbow diamond markers on outer arm face.
        float shoulderX = centerX + outerSign * (halfW + 0.08F);
        b.addAccentLoop(
            new float[]{shoulderX, -0.80F, 0.0F},
            new float[]{shoulderX, -0.10F, 0.65F},
            new float[]{shoulderX, 0.60F, 0.0F},
            new float[]{shoulderX, -0.10F, -0.65F}
        );
        float elbowX = centerX + outerSign * (halfW * 0.88F + 0.08F);
        b.addAccentLoop(
            new float[]{elbowX, 3.50F, 0.0F},
            new float[]{elbowX, 4.00F, 0.50F},
            new float[]{elbowX, 4.50F, 0.0F},
            new float[]{elbowX, 4.00F, -0.50F}
        );

        // Internal skeleton bone line.
        b.addSkeletonLine(centerX, 0.00F, 0.0F, centerX, 4.00F, 0.0F);
        b.addSkeletonLine(centerX, 4.00F, 0.0F, centerX, 8.90F, 0.0F);
        return b.build();
    }

    private static PartMesh buildLegMesh(float outerSign) {
        MeshBuilder b = new MeshBuilder(0.0F, 6.00F, 0.0F);
        float[][] rings = {
            makeOctagonRing(0.0F, 0.20F, 0.0F, 1.62F, 1.62F, 0.45F, 0.45F),
            makeOctagonRing(0.0F, 1.40F, 0.0F, 1.95F, 1.95F, 0.55F, 0.55F),
            makeOctagonRing(0.0F, 5.60F, 0.0F, 1.78F, 1.78F, 0.50F, 0.50F),
            makeOctagonRing(0.0F, 10.40F, 0.0F, 1.58F, 1.58F, 0.45F, 0.45F),
            makeOctagonRing(0.0F, 11.80F, 0.0F, 1.45F, 1.55F, 0.40F, 0.42F)
        };
        addOctagonPrism(b, rings);

        // Knee cuff collar and front (-Z) knee guard chevron.
        float[] kneeRing = makeOctagonRing(0.0F, 5.60F, 0.0F, 1.84F, 1.84F, 0.52F, 0.52F);
        for (int i = 0; i < 8; i++) {
            int next = (i + 1) & 7;
            b.addAccentLine(
                kneeRing[i * 3], kneeRing[i * 3 + 1], kneeRing[i * 3 + 2],
                kneeRing[next * 3], kneeRing[next * 3 + 1], kneeRing[next * 3 + 2]
            );
        }
        b.addAccentLine(-0.85F, 5.20F, -1.86F, 0.0F, 6.15F, -1.86F);
        b.addAccentLine(0.0F, 6.15F, -1.86F, 0.85F, 5.20F, -1.86F);

        // Outer thigh/hip diamond marker.
        float outerX = outerSign * 1.98F;
        b.addAccentLoop(
            new float[]{outerX, 1.80F, 0.0F},
            new float[]{outerX, 2.35F, 0.50F},
            new float[]{outerX, 2.90F, 0.0F},
            new float[]{outerX, 2.35F, -0.50F}
        );

        // Internal skeleton bone line.
        b.addSkeletonLine(0.0F, 0.80F, 0.0F, 0.0F, 5.60F, 0.0F);
        b.addSkeletonLine(0.0F, 5.60F, 0.0F, 0.0F, 11.00F, 0.0F);
        return b.build();
    }

    private static float[] makeOctagonRing(
        float ox,
        float y,
        float oz,
        float hx,
        float hz,
        float cx,
        float cz
    ) {
        float xInner = hx - cx;
        float zInner = hz - cz;
        return new float[] {
            ox - xInner, y, oz - hz,
            ox + xInner, y, oz - hz,
            ox + hx,     y, oz - zInner,
            ox + hx,     y, oz + zInner,
            ox + xInner, y, oz + hz,
            ox - xInner, y, oz + hz,
            ox - hx,     y, oz + zInner,
            ox - hx,     y, oz - zInner
        };
    }

    private static void addOctagonPrism(MeshBuilder b, float[][] rings) {
        int ringCount = rings.length;
        // Top cap fan from vertex 0 of first ring.
        float[] top = rings[0];
        for (int i = 1; i < 7; i++) {
            b.addTriangle(
                top[0], top[1], top[2],
                top[i * 3], top[i * 3 + 1], top[i * 3 + 2],
                top[(i + 1) * 3], top[(i + 1) * 3 + 1], top[(i + 1) * 3 + 2]
            );
        }

        // Side bands between consecutive rings.
        for (int r = 0; r < ringCount - 1; r++) {
            float[] upper = rings[r];
            float[] lower = rings[r + 1];
            for (int i = 0; i < 8; i++) {
                int next = (i + 1) & 7;
                int i3 = i * 3;
                int n3 = next * 3;
                b.addQuad(
                    upper[i3], upper[i3 + 1], upper[i3 + 2],
                    upper[n3], upper[n3 + 1], upper[n3 + 2],
                    lower[n3], lower[n3 + 1], lower[n3 + 2],
                    lower[i3], lower[i3 + 1], lower[i3 + 2]
                );
            }
        }

        // Bottom cap fan from vertex 0 of last ring.
        float[] bot = rings[ringCount - 1];
        for (int i = 1; i < 7; i++) {
            b.addTriangle(
                bot[0], bot[1], bot[2],
                bot[(i + 1) * 3], bot[(i + 1) * 3 + 1], bot[(i + 1) * 3 + 2],
                bot[i * 3], bot[i * 3 + 1], bot[i * 3 + 2]
            );
        }

        // Structural wireframe edges: ring perimeters + vertical ribs (no triangle diagonals).
        for (int r = 0; r < ringCount; r++) {
            float[] ring = rings[r];
            for (int i = 0; i < 8; i++) {
                int next = (i + 1) & 7;
                int i3 = i * 3;
                int n3 = next * 3;
                b.addEdgeLine(
                    ring[i3], ring[i3 + 1], ring[i3 + 2],
                    ring[n3], ring[n3 + 1], ring[n3 + 2]
                );
            }
        }
        for (int r = 0; r < ringCount - 1; r++) {
            float[] upper = rings[r];
            float[] lower = rings[r + 1];
            for (int i = 0; i < 8; i++) {
                int i3 = i * 3;
                b.addEdgeLine(
                    upper[i3], upper[i3 + 1], upper[i3 + 2],
                    lower[i3], lower[i3 + 1], lower[i3 + 2]
                );
            }
        }
    }

    /**
     * Immutable precomputed triangle, normal, edge, accent, and skeleton geometry for one limb part.
     */
    static final class PartMesh {
        final float centerX;
        final float centerY;
        final float centerZ;
        final float[] triVerts;
        final float[] triNormals;
        final int triCount;
        final float[] edgeLines;
        final int edgeLineCount;
        final float[] accentTriVerts;
        final float[] accentTriNormals;
        final int accentTriCount;
        final float[] accentLines;
        final int accentLineCount;
        final float[] skeletonLines;
        final int skeletonLineCount;

        PartMesh(
            float centerX,
            float centerY,
            float centerZ,
            float[] triVerts,
            float[] triNormals,
            float[] edgeLines,
            float[] accentTriVerts,
            float[] accentTriNormals,
            float[] accentLines,
            float[] skeletonLines
        ) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.triVerts = triVerts;
            this.triNormals = triNormals;
            this.triCount = triVerts.length / 9;
            this.edgeLines = edgeLines;
            this.edgeLineCount = edgeLines.length / 6;
            this.accentTriVerts = accentTriVerts;
            this.accentTriNormals = accentTriNormals;
            this.accentTriCount = accentTriVerts.length / 9;
            this.accentLines = accentLines;
            this.accentLineCount = accentLines.length / 6;
            this.skeletonLines = skeletonLines;
            this.skeletonLineCount = skeletonLines.length / 6;
        }
    }

    /**
     * One-time {@code <clinit>} builder that enforces outward triangle winding via centroid dot check.
     */
    private static final class MeshBuilder {
        private final float centerX;
        private final float centerY;
        private final float centerZ;

        private float[] triVerts = new float[512];
        private float[] triNormals = new float[256];
        private int triCount = 0;

        private float[] edgeLines = new float[512];
        private int edgeCount = 0;

        private float[] accentTriVerts = new float[64];
        private float[] accentTriNormals = new float[32];
        private int accentTriCount = 0;

        private float[] accentLines = new float[256];
        private int accentLineCount = 0;

        private float[] skeletonLines = new float[64];
        private int skeletonLineCount = 0;

        MeshBuilder(float centerX, float centerY, float centerZ) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
        }

        void addQuad(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3
        ) {
            addTriangle(x0, y0, z0, x1, y1, z1, x2, y2, z2);
            addTriangle(x0, y0, z0, x2, y2, z2, x3, y3, z3);
        }

        void addTriangle(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2
        ) {
            ensureTriCapacity(this.triCount + 1);
            emitCheckedTriangle(
                this.triVerts, this.triNormals, this.triCount,
                x0, y0, z0, x1, y1, z1, x2, y2, z2
            );
            this.triCount++;
        }

        void addAccentQuad(float[] p0, float[] p1, float[] p2, float[] p3) {
            addAccentTriangle(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]);
            addAccentTriangle(p0[0], p0[1], p0[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2]);
        }

        void addAccentTriangle(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2
        ) {
            ensureAccentTriCapacity(this.accentTriCount + 1);
            emitCheckedTriangle(
                this.accentTriVerts, this.accentTriNormals, this.accentTriCount,
                x0, y0, z0, x1, y1, z1, x2, y2, z2
            );
            this.accentTriCount++;
        }

        private void emitCheckedTriangle(
            float[] verts,
            float[] normals,
            int index,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2
        ) {
            float e1x = x1 - x0;
            float e1y = y1 - y0;
            float e1z = z1 - z0;
            float e2x = x2 - x0;
            float e2y = y2 - y0;
            float e2z = z2 - z0;

            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;

            float fcx = (x0 + x1 + x2) / 3.0F - this.centerX;
            float fcy = (y0 + y1 + y2) / 3.0F - this.centerY;
            float fcz = (z0 + z1 + z2) / 3.0F - this.centerZ;

            float dot = nx * fcx + ny * fcy + nz * fcz;
            if (dot < 0.0F) {
                float tx = x1;
                float ty = y1;
                float tz = z1;
                x1 = x2;
                y1 = y2;
                z1 = z2;
                x2 = tx;
                y2 = ty;
                z2 = tz;
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }

            float lenSq = nx * nx + ny * ny + nz * nz;
            if (lenSq > 1.0E-8F) {
                float invLen = (float) (1.0D / Math.sqrt(lenSq));
                nx *= invLen;
                ny *= invLen;
                nz *= invLen;
            } else {
                nx = 0.0F;
                ny = 0.0F;
                nz = 1.0F;
            }

            int vBase = index * 9;
            verts[vBase] = x0;
            verts[vBase + 1] = y0;
            verts[vBase + 2] = z0;
            verts[vBase + 3] = x1;
            verts[vBase + 4] = y1;
            verts[vBase + 5] = z1;
            verts[vBase + 6] = x2;
            verts[vBase + 7] = y2;
            verts[vBase + 8] = z2;

            int nBase = index * 3;
            normals[nBase] = nx;
            normals[nBase + 1] = ny;
            normals[nBase + 2] = nz;
        }

        void addEdgeLine(float x0, float y0, float z0, float x1, float y1, float z1) {
            if ((this.edgeCount + 1) * 6 > this.edgeLines.length) {
                this.edgeLines = grow(this.edgeLines, this.edgeLines.length * 2);
            }
            int base = this.edgeCount * 6;
            this.edgeLines[base] = x0;
            this.edgeLines[base + 1] = y0;
            this.edgeLines[base + 2] = z0;
            this.edgeLines[base + 3] = x1;
            this.edgeLines[base + 4] = y1;
            this.edgeLines[base + 5] = z1;
            this.edgeCount++;
        }

        void addAccentLoop(float[]... pts) {
            for (int i = 0; i < pts.length; i++) {
                float[] a = pts[i];
                float[] b = pts[(i + 1) % pts.length];
                addAccentLine(a[0], a[1], a[2], b[0], b[1], b[2]);
            }
        }

        void addAccentLine(float x0, float y0, float z0, float x1, float y1, float z1) {
            if ((this.accentLineCount + 1) * 6 > this.accentLines.length) {
                this.accentLines = grow(this.accentLines, this.accentLines.length * 2);
            }
            int base = this.accentLineCount * 6;
            this.accentLines[base] = x0;
            this.accentLines[base + 1] = y0;
            this.accentLines[base + 2] = z0;
            this.accentLines[base + 3] = x1;
            this.accentLines[base + 4] = y1;
            this.accentLines[base + 5] = z1;
            this.accentLineCount++;
        }

        void addSkeletonLine(float x0, float y0, float z0, float x1, float y1, float z1) {
            if ((this.skeletonLineCount + 1) * 6 > this.skeletonLines.length) {
                this.skeletonLines = grow(this.skeletonLines, this.skeletonLines.length * 2);
            }
            int base = this.skeletonLineCount * 6;
            this.skeletonLines[base] = x0;
            this.skeletonLines[base + 1] = y0;
            this.skeletonLines[base + 2] = z0;
            this.skeletonLines[base + 3] = x1;
            this.skeletonLines[base + 4] = y1;
            this.skeletonLines[base + 5] = z1;
            this.skeletonLineCount++;
        }

        PartMesh build() {
            return new PartMesh(
                this.centerX,
                this.centerY,
                this.centerZ,
                trim(this.triVerts, this.triCount * 9),
                trim(this.triNormals, this.triCount * 3),
                trim(this.edgeLines, this.edgeCount * 6),
                trim(this.accentTriVerts, this.accentTriCount * 9),
                trim(this.accentTriNormals, this.accentTriCount * 3),
                trim(this.accentLines, this.accentLineCount * 6),
                trim(this.skeletonLines, this.skeletonLineCount * 6)
            );
        }

        private void ensureTriCapacity(int nextTris) {
            if (nextTris * 9 > this.triVerts.length) {
                this.triVerts = grow(this.triVerts, this.triVerts.length * 2);
                this.triNormals = grow(this.triNormals, this.triNormals.length * 2);
            }
        }

        private void ensureAccentTriCapacity(int nextTris) {
            if (nextTris * 9 > this.accentTriVerts.length) {
                this.accentTriVerts = grow(this.accentTriVerts, this.accentTriVerts.length * 2);
                this.accentTriNormals = grow(this.accentTriNormals, this.accentTriNormals.length * 2);
            }
        }

        private static float[] grow(float[] src, int newLen) {
            float[] dst = new float[newLen];
            System.arraycopy(src, 0, dst, 0, src.length);
            return dst;
        }

        private static float[] trim(float[] src, int len) {
            float[] dst = new float[len];
            System.arraycopy(src, 0, dst, 0, len);
            return dst;
        }
    }
}
