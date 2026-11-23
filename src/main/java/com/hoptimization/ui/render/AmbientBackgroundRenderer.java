package com.hoptimization.ui.render;

import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeMode;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Lightweight 2D ambient background renderer with slow-flowing soft silk lines,
 * sparse breathing light motes, and gentle 2D mouse proximity interaction.
 */
public final class AmbientBackgroundRenderer {
    private static final int LINE_COUNT = 4;
    private static final int LINE_SEGMENTS = 48;
    private static final int MOTE_COUNT = 28;
    private static final int CIRCLE_SEGMENTS = 14;

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

        if (smoothMouseX < -500.0F) {
            smoothMouseX = mouseX;
            smoothMouseY = mouseY;
        } else {
            float lerp = 1.0F - (float) Math.exp(-8.5F * dt);
            smoothMouseX += (mouseX - smoothMouseX) * lerp;
            smoothMouseY += (mouseY - smoothMouseY) * lerp;
        }

        float time = (now % 3_600_000_000_000L) / 1_000_000_000.0F;
        boolean lightMode = theme.getMode() == ThemeMode.LIGHT;
        int accent = theme.accent();
        float ar = ((accent >>> 16) & 0xFF) / 255.0F;
        float ag = ((accent >>> 8) & 0xFF) / 255.0F;
        float ab = (accent & 0xFF) / 255.0F;

        // 1. Calm base vertical gradient
        RenderUtil.drawVerticalGradient(0.0F, 0.0F, width, height, theme.surface0(), theme.surface1());

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

        // 2. Subtle cursor ambient halo (flat 2D)
        if (mouseX >= 0 && mouseY >= 0 && mouseX <= width && mouseY <= height) {
            drawSoftDisc(smoothMouseX, smoothMouseY, 150.0F, ar, ag, ab, lightMode ? 0.055F : 0.045F);
        }

        // 3. Soft slow-flowing 2D silk lines
        renderFlowLines(width, height, time, ar, ag, ab, lightMode);

        // 4. Sparse breathing light motes with gentle 2D mouse avoidance + highlight
        renderLightMotes(width, height, time, ar, ag, ab, lightMode);

        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void renderFlowLines(
            float width,
            float height,
            float time,
            float ar,
            float ag,
            float ab,
            boolean lightMode) {
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        for (int i = 0; i < LINE_COUNT; i++) {
            float baseFrac = 0.24F + i * 0.17F;
            float baseY = height * baseFrac;
            float amp1 = height * (0.025F + i * 0.006F);
            float amp2 = height * 0.012F;
            float speed = 0.22F + i * 0.05F;
            float phase = i * 1.65F;

            // Soft feathered ribbon glow around the line
            float ribbonHalfWidth = 14.0F + i * 3.0F;
            float ribbonAlpha = (lightMode ? 0.032F : 0.026F) * (1.0F - i * 0.12F);
            drawSoftRibbon(width, baseY, amp1, amp2, speed, phase, time, ribbonHalfWidth, ar, ag, ab, ribbonAlpha);

            // Thin crisp hairline
            float lineAlpha = (lightMode ? 0.16F : 0.13F) * (1.0F - i * 0.14F);
            GL11.glLineWidth(1.25F);
            GL11.glColor4f(ar, ag, ab, lineAlpha);
            GL11.glBegin(GL11.GL_LINE_STRIP);
            for (int s = 0; s <= LINE_SEGMENTS; s++) {
                float u = (float) s / LINE_SEGMENTS;
                float x = u * width;
                float y = computeLineY(x, baseY, u, amp1, amp2, speed, phase, time);
                GL11.glVertex2f(x, y);
            }
            GL11.glEnd();
        }

        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private static void drawSoftRibbon(
            float width,
            float baseY,
            float amp1,
            float amp2,
            float speed,
            float phase,
            float time,
            float halfWidth,
            float r,
            float g,
            float b,
            float centerAlpha) {
        // Upper feather
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int s = 0; s <= LINE_SEGMENTS; s++) {
            float u = (float) s / LINE_SEGMENTS;
            float x = u * width;
            float y = computeLineY(x, baseY, u, amp1, amp2, speed, phase, time);
            GL11.glColor4f(r, g, b, 0.0F);
            GL11.glVertex2f(x, y - halfWidth);
            GL11.glColor4f(r, g, b, centerAlpha);
            GL11.glVertex2f(x, y);
        }
        GL11.glEnd();

        // Lower feather
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int s = 0; s <= LINE_SEGMENTS; s++) {
            float u = (float) s / LINE_SEGMENTS;
            float x = u * width;
            float y = computeLineY(x, baseY, u, amp1, amp2, speed, phase, time);
            GL11.glColor4f(r, g, b, centerAlpha);
            GL11.glVertex2f(x, y);
            GL11.glColor4f(r, g, b, 0.0F);
            GL11.glVertex2f(x, y + halfWidth);
        }
        GL11.glEnd();
    }

    private static float computeLineY(
            float x,
            float baseY,
            float u,
            float amp1,
            float amp2,
            float speed,
            float phase,
            float time) {
        float y = baseY
                + (float) Math.sin(u * 4.2F + time * speed + phase) * amp1
                + (float) Math.cos(u * 7.5F - time * speed * 0.7F + phase * 1.3F) * amp2;

        float dx = x - smoothMouseX;
        float dy = y - smoothMouseY;
        float distSq = dx * dx + dy * dy;
        float radius = 140.0F;
        if (distSq < radius * radius && distSq > 0.01F) {
            float dist = (float) Math.sqrt(distSq);
            float falloff = 1.0F - dist / radius;
            float smooth = falloff * falloff * (3.0F - 2.0F * falloff);
            y += (dy / dist) * smooth * 9.0F;
        }
        return y;
    }

    private static void renderLightMotes(
            float width,
            float height,
            float time,
            float ar,
            float ag,
            float ab,
            boolean lightMode) {
        for (int i = 0; i < MOTE_COUNT; i++) {
            float h1 = hash(i * 17.31F + 1.13F);
            float h2 = hash(i * 31.73F + 2.71F);
            float h3 = hash(i * 47.19F + 4.09F);
            float h4 = hash(i * 61.43F + 5.87F);

            // Slow gentle drift across screen, wrapping seamlessly
            float speedX = (h3 - 0.5F) * 14.0F;
            float speedY = -(6.0F + h4 * 10.0F);
            float x = wrap(h1 * width + time * speedX + (float) Math.sin(time * 0.45F + i) * 14.0F, width);
            float y = wrap(h2 * height + time * speedY, height);

            // Gentle 2D mouse repulsion + proximity glow
            float dx = x - smoothMouseX;
            float dy = y - smoothMouseY;
            float distSq = dx * dx + dy * dy;
            float interactRadius = 125.0F;
            float proximity = 0.0F;
            if (distSq < interactRadius * interactRadius && distSq > 0.01F) {
                float dist = (float) Math.sqrt(distSq);
                float t = 1.0F - dist / interactRadius;
                proximity = t * t;
                float push = proximity * 14.0F;
                x += (dx / dist) * push;
                y += (dy / dist) * push;
            }

            float breath = 0.55F + 0.45F * (float) Math.sin(time * (0.75F + h3 * 0.8F) + i * 2.1F);
            float baseRadius = 2.0F + h4 * 2.6F + proximity * 1.4F;
            float coreAlpha = ((lightMode ? 0.24F : 0.28F) * breath + proximity * 0.28F);
            float haloAlpha = ((lightMode ? 0.075F : 0.09F) * breath + proximity * 0.12F);

            float mr = Math.min(1.0F, ar + (1.0F - ar) * (0.28F + proximity * 0.35F));
            float mg = Math.min(1.0F, ag + (1.0F - ag) * (0.28F + proximity * 0.35F));
            float mb = Math.min(1.0F, ab + (1.0F - ab) * (0.28F + proximity * 0.35F));

            drawSoftDisc(x, y, baseRadius * 4.2F, ar, ag, ab, haloAlpha);
            drawSoftDisc(x, y, baseRadius, mr, mg, mb, coreAlpha);
        }
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
