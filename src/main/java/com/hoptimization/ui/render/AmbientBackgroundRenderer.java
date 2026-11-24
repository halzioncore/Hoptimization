package com.hoptimization.ui.render;

import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeMode;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Lightweight 2D geometric ambient background combining slowly drifting,
 * restrained triangles, circles/rings, and rounded rectangles with subtle mouse parallax.
 */
public final class AmbientBackgroundRenderer {
    private static final int SHAPE_COUNT = 21;
    private static final int CIRCLE_SEGMENTS = 36;

    private static float smoothMouseX = -1000.0F;
    private static float smoothMouseY = -1000.0F;
    private static long lastFrameNanos;

    private AmbientBackgroundRenderer() {
    }

    public static void renderBackground(float width, float height, int mouseX, int mouseY, ThemeData theme) {
        if (width <= 0.0F || height <= 0.0F || theme == null) {
            return;
        }

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016F : Math.min(0.05F, (now - lastFrameNanos) / 1_000_000_000.0F);
        lastFrameNanos = now;

        boolean reduceMotion = Animation.isReduceMotion();
        if (reduceMotion) {
            smoothMouseX = width * 0.5F;
            smoothMouseY = height * 0.5F;
        } else if (smoothMouseX < -500.0F) {
            smoothMouseX = mouseX >= 0 ? mouseX : width * 0.5F;
            smoothMouseY = mouseY >= 0 ? mouseY : height * 0.5F;
        } else {
            float lerp = 1.0F - (float) Math.exp(-7.5F * dt);
            smoothMouseX += (mouseX - smoothMouseX) * lerp;
            smoothMouseY += (mouseY - smoothMouseY) * lerp;
        }

        float time = reduceMotion ? 0.0F : ((now % 3_600_000_000_000L) / 1_000_000_000.0F);
        boolean lightMode = theme.getMode() == ThemeMode.LIGHT;
        int accent = theme.accent();
        int muted = theme.textSecondary();

        // 1. Calm base vertical gradient
        RenderUtil.drawVerticalGradient(0.0F, 0.0F, width, height, theme.surface0(), theme.surface1());

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        // 2. Subtle ambient cursor glow
        float ar = ((accent >>> 16) & 0xFF) / 255.0F;
        float ag = ((accent >>> 8) & 0xFF) / 255.0F;
        float ab = (accent & 0xFF) / 255.0F;
        if (mouseX >= 0 && mouseY >= 0 && mouseX <= width && mouseY <= height) {
            drawSoftDisc(smoothMouseX, smoothMouseY, 170.0F, ar, ag, ab, lightMode ? 0.055F : 0.042F);
        }

        float normMouseX = (smoothMouseX / Math.max(1.0F, width)) - 0.5F;
        float normMouseY = (smoothMouseY / Math.max(1.0F, height)) - 0.5F;

        // 3. Geometric composition: triangles (0), circles/rings (1), rounded rectangles (2)
        for (int i = 0; i < SHAPE_COUNT; i++) {
            float h1 = hash(i * 19.37F + 1.13F);
            float h2 = hash(i * 37.71F + 2.59F);
            float h3 = hash(i * 53.17F + 4.21F);
            float h4 = hash(i * 71.83F + 6.07F);
            float h5 = hash(i * 89.41F + 8.33F);

            float depth = 0.35F + h4 * 0.65F;
            float size = 14.0F + h3 * 36.0F;
            float pad = size + 24.0F;

            float vx = (h4 - 0.5F) * 9.0F;
            float vy = -(4.5F + h5 * 7.5F);
            float cx = wrap(h1 * (width + pad * 2.0F) + time * vx, width + pad * 2.0F) - pad;
            float cy = wrap(h2 * (height + pad * 2.0F) + time * vy, height + pad * 2.0F) - pad;

            cx -= normMouseX * 18.0F * depth;
            cy -= normMouseY * 14.0F * depth;

            float rotationDeg = (h5 * 360.0F) + time * ((h3 - 0.5F) * 14.0F);
            float breath = reduceMotion
                    ? 0.85F
                    : (0.72F + 0.28F * (float) Math.sin(time * (0.55F + h4 * 0.5F) + i * 1.4F));

            int baseColor = (i % 3 == 0) ? accent : muted;
            float r = ((baseColor >>> 16) & 0xFF) / 255.0F;
            float g = ((baseColor >>> 8) & 0xFF) / 255.0F;
            float b = (baseColor & 0xFF) / 255.0F;

            float strokeAlpha = (lightMode ? 0.16F : 0.14F) * breath * (0.65F + 0.35F * depth);
            float fillAlpha = (lightMode ? 0.042F : 0.032F) * breath;

            int kind = i % 3;
            if (kind == 0) {
                drawTriangle(cx, cy, size, rotationDeg, r, g, b, fillAlpha, strokeAlpha);
            } else if (kind == 1) {
                drawCircleOrRing(cx, cy, size * 0.58F, h5 > 0.45F, r, g, b, fillAlpha, strokeAlpha);
            } else {
                drawRotatedRect(
                        cx, cy, size * 1.18F, size * 0.78F, 5.0F,
                        rotationDeg, baseColor, fillAlpha, strokeAlpha
                );
            }
        }

        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void drawTriangle(
            float cx,
            float cy,
            float size,
            float rotationDeg,
            float r,
            float g,
            float b,
            float fillAlpha,
            float strokeAlpha) {
        float radius = size * 0.62F;
        double baseRad = Math.toRadians(rotationDeg);

        GlStateManager.pushMatrix();
        GL11.glColor4f(r, g, b, fillAlpha);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int k = 0; k < 3; k++) {
            double a = baseRad + k * (Math.PI * 2.0 / 3.0);
            GL11.glVertex2f(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius);
        }
        GL11.glEnd();

        GL11.glLineWidth(1.2F);
        GL11.glColor4f(r, g, b, strokeAlpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int k = 0; k < 3; k++) {
            double a = baseRad + k * (Math.PI * 2.0 / 3.0);
            GL11.glVertex2f(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius);
        }
        GL11.glEnd();
        GlStateManager.popMatrix();
    }

    private static void drawCircleOrRing(
            float cx,
            float cy,
            float radius,
            boolean ringOnly,
            float r,
            float g,
            float b,
            float fillAlpha,
            float strokeAlpha) {
        if (!ringOnly) {
            drawSoftDisc(cx, cy, radius, r, g, b, fillAlpha * 1.6F);
        } else {
            GL11.glColor4f(r, g, b, fillAlpha * 0.7F);
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glVertex2f(cx, cy);
            for (int s = 0; s <= CIRCLE_SEGMENTS; s++) {
                double a = (Math.PI * 2.0 * s) / CIRCLE_SEGMENTS;
                GL11.glVertex2f(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius);
            }
            GL11.glEnd();
        }

        GL11.glLineWidth(1.2F);
        GL11.glColor4f(r, g, b, strokeAlpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int s = 0; s < CIRCLE_SEGMENTS; s++) {
            double a = (Math.PI * 2.0 * s) / CIRCLE_SEGMENTS;
            GL11.glVertex2f(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius);
        }
        GL11.glEnd();
    }

    private static void drawRotatedRect(
            float cx,
            float cy,
            float w,
            float h,
            float radius,
            float rotationDeg,
            int baseColor,
            float fillAlpha,
            float strokeAlpha) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0.0F);
        GlStateManager.rotate(rotationDeg, 0.0F, 0.0F, 1.0F);
        RenderUtil.drawRoundedRect(-w * 0.5F, -h * 0.5F, w, h, radius, RenderUtil.withAlpha(baseColor, fillAlpha));
        RenderUtil.drawRoundedBorder(
                -w * 0.5F, -h * 0.5F, w, h, radius, 1.15F, RenderUtil.withAlpha(baseColor, strokeAlpha)
        );
        GlStateManager.popMatrix();
    }

    private static void drawSoftDisc(float cx, float cy, float radius, float r, float g, float b, float centerAlpha) {
        if (centerAlpha <= 0.002F || radius <= 0.0F) {
            return;
        }
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, centerAlpha);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(r, g, b, 0.0F);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double angle = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
            GL11.glVertex2f(
                    cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius);
        }
        GL11.glEnd();
    }

    private static float wrap(float value, float max) {
        float m = value % max;
        return m < 0.0F ? m + max : m;
    }

    private static float hash(float n) {
        float x = (float) Math.sin(n) * 43758.5453123F;
        return x - (float) Math.floor(x);
    }
}
