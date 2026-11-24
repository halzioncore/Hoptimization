package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Replaces the vanilla hotbar, health hearts, and hunger drumsticks with
 * theme-matched modern cards, smooth slot selection, and smooth vector icons.
 */
public class ModernHotbar extends Module {
    public static final String ID = "modern_hotbar";

    private static final float HOTBAR_WIDTH = 182.0F;
    private static final float HOTBAR_HEIGHT = 22.0F;
    private static final int ICON_COUNT = 10;
    private static final float ICON_SIZE = 8.4F;
    private static final float ICON_STEP = 8.8F;
    private static final int HEART_VERTICES = 28;
    private static final int FOOD_VERTICES = 24;
    private static final int HIGHLIGHT_SEGMENTS = 10;

    private static final float[] HEART_UNIT_X = new float[HEART_VERTICES];
    private static final float[] HEART_UNIT_Y = new float[HEART_VERTICES];
    private static final float[] FOOD_UNIT_X = new float[FOOD_VERTICES];
    private static final float[] FOOD_UNIT_Y = new float[FOOD_VERTICES];
    private static final float[] HIGHLIGHT_COS = new float[HIGHLIGHT_SEGMENTS + 1];
    private static final float[] HIGHLIGHT_SIN = new float[HIGHLIGHT_SEGMENTS + 1];

    static {
        for (int i = 0; i < HEART_VERTICES; i++) {
            double t = (Math.PI * 2.0 * i) / HEART_VERTICES;
            double sinT = Math.sin(t);
            HEART_UNIT_X[i] = (float) (16.0 * sinT * sinT * sinT);
            HEART_UNIT_Y[i] = (float) (-(13.0 * Math.cos(t)
                    - 5.0 * Math.cos(2.0 * t)
                    - 2.0 * Math.cos(3.0 * t)
                    - Math.cos(4.0 * t)));
        }
        for (int i = 0; i < FOOD_VERTICES; i++) {
            double t = (Math.PI * 2.0 * i) / FOOD_VERTICES;
            double pinch = 1.0 - 0.28 * Math.sin(t);
            double px = Math.cos(t) * 0.40 * pinch;
            double py = Math.sin(t) * 0.42;
            FOOD_UNIT_X[i] = (float) (px * 0.8829 - py * 0.4695);
            FOOD_UNIT_Y[i] = (float) (px * 0.4695 + py * 0.8829);
        }
        for (int i = 0; i <= HIGHLIGHT_SEGMENTS; i++) {
            double a = (Math.PI * 2.0 * i) / HIGHLIGHT_SEGMENTS;
            HIGHLIGHT_COS[i] = (float) Math.cos(a);
            HIGHLIGHT_SIN[i] = (float) Math.sin(a);
        }
    }

    private final BooleanSetting modernHotbar = addSetting(
            new BooleanSetting("modern_hotbar", "setting.hoptimization.modern_hotbar.hotbar.name", true));
    private final BooleanSetting modernBars = addSetting(
            new BooleanSetting("modern_bars", "setting.hoptimization.modern_hotbar.bars.name", true));

    private float smoothSlot = -1.0F;
    private float smoothHealth = -1.0F;
    private float smoothFood = -1.0F;
    private long lastHotbarNanos;
    private long lastHealthNanos;
    private long lastFoodNanos;

    public ModernHotbar() {
        super(ID, Category.UI);
        setEnabled(true);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void resetToDefaults() {
        super.resetToDefaults();
        setEnabled(true);
    }

    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (!isEnabled() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || mc.gameSettings.hideGUI) {
            return;
        }
        EntityPlayer player = mc.getRenderViewEntity() instanceof EntityPlayer
                ? (EntityPlayer) mc.getRenderViewEntity()
                : mc.thePlayer;
        if (player == null) {
            return;
        }

        ScaledResolution sr = event.resolution;
        float screenW = sr.getScaledWidth();
        float screenH = sr.getScaledHeight();
        float barX = (screenW - HOTBAR_WIDTH) * 0.5F;
        float barY = screenH - HOTBAR_HEIGHT - 2.0F;
        ThemeData theme = ThemeManager.current();

        if (event.type == RenderGameOverlayEvent.ElementType.HOTBAR && this.modernHotbar.isEnabled()) {
            event.setCanceled(true);
            renderHotbar(mc, player, barX, barY, theme);
        } else if (event.type == RenderGameOverlayEvent.ElementType.HEALTH
                && this.modernBars.isEnabled()
                && mc.playerController != null
                && mc.playerController.shouldDrawHUD()) {
            event.setCanceled(true);
            float rowY = screenH - GuiIngameForge.left_height;
            GuiIngameForge.left_height += 11;
            renderHearts(mc, sr, player, barX, rowY, theme);
        } else if (event.type == RenderGameOverlayEvent.ElementType.FOOD
                && this.modernBars.isEnabled()
                && mc.playerController != null
                && mc.playerController.shouldDrawHUD()) {
            event.setCanceled(true);
            float rowY = screenH - GuiIngameForge.right_height;
            GuiIngameForge.right_height += 11;
            renderFood(mc, sr, player, barX + HOTBAR_WIDTH, rowY, theme);
        }
    }

    private float computeDelta(long now, long prev) {
        return prev == 0L ? 0.016F : Math.min(0.05F, Math.max(0.0005F, (now - prev) / 1_000_000_000.0F));
    }

    private void renderHotbar(Minecraft mc, EntityPlayer player, float x, float y, ThemeData theme) {
        long now = System.nanoTime();
        float dt = computeDelta(now, this.lastHotbarNanos);
        this.lastHotbarNanos = now;

        HudRenderHelper.drawHudCard(x, y, HOTBAR_WIDTH, HOTBAR_HEIGHT, theme, 1.0F);

        int currentSlot = Math.max(0, Math.min(8, player.inventory.currentItem));
        if (this.smoothSlot < 0.0F || Animation.isReduceMotion()) {
            this.smoothSlot = currentSlot;
        } else {
            float lerp = 1.0F - (float) Math.exp(-22.0F * dt);
            this.smoothSlot += (currentSlot - this.smoothSlot) * lerp;
            if (Math.abs(currentSlot - this.smoothSlot) < 0.002F) {
                this.smoothSlot = currentSlot;
            }
        }

        float selX = x + 1.5F + this.smoothSlot * 20.0F;
        float selY = y + 1.5F;
        float selSize = 19.0F;
        float selRadius = theme.nestedRadius(theme.radiusCard(), 1.5F);
        int selFill = RenderUtil.withAlpha(theme.accent(), 0.22F);
        int selBorder = RenderUtil.withAlpha(theme.accent(), 0.88F);
        RenderUtil.drawRoundedRect(selX, selY, selSize, selSize, selRadius, selFill, selBorder, UiTokens.BORDER_WIDTH);

        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderHelper.enableGUIStandardItemLighting();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null) {
                int itemX = Math.round(x + 3.0F + i * 20.0F);
                int itemY = Math.round(y + 3.0F);
                mc.getRenderItem().renderItemAndEffectIntoGUI(stack, itemX, itemY);
                mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, stack, itemX, itemY);
            }
        }

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderHearts(
            Minecraft mc,
            ScaledResolution sr,
            EntityPlayer player,
            float startX,
            float y,
            ThemeData theme) {
        long now = System.nanoTime();
        float dt = computeDelta(now, this.lastHealthNanos);
        this.lastHealthNanos = now;

        float maxHealth = Math.max(1.0F, player.getMaxHealth());
        float health = Math.max(0.0F, Math.min(maxHealth, player.getHealth()));
        float absorption = Math.max(0.0F, player.getAbsorptionAmount());

        boolean reduceMotion = Animation.isReduceMotion();
        if (this.smoothHealth < 0.0F || reduceMotion) {
            this.smoothHealth = health;
        } else {
            float lerp = 1.0F - (float) Math.exp(-14.0F * dt);
            this.smoothHealth += (health - this.smoothHealth) * lerp;
        }

        boolean lowHealth = (health / maxHealth) <= 0.25F;
        float pulse = (lowHealth && !reduceMotion)
                ? 1.0F + 0.08F * (float) Math.sin((now / 1_000_000_000.0D) * 7.0D)
                : 1.0F;

        int trackFill = RenderUtil.withAlpha(theme.surface1(), 0.72F);
        int trackBorder = RenderUtil.withAlpha(theme.borderStrong(), 0.75F);
        int heartColor = theme.danger();
        int absColor = theme.warning();

        float hpPerHeart = Math.max(2.0F, maxHealth / ICON_COUNT);

        beginVector2D();
        for (int i = 0; i < ICON_COUNT; i++) {
            float hx = startX + i * ICON_STEP;
            float hy = y;
            float size = ICON_SIZE * (lowHealth && i < Math.ceil(health / hpPerHeart) ? pulse : 1.0F);
            float cx = hx + ICON_SIZE * 0.5F;
            float cy = hy + ICON_SIZE * 0.5F;

            drawHeartShape(cx, cy, size, trackFill, trackBorder);

            float fill = clamp01((this.smoothHealth - i * hpPerHeart) / hpPerHeart);
            if (fill > 0.01F) {
                drawClippedHeart(mc, sr, cx, cy, size, fill, heartColor);
            }

            if (absorption > 0.0F) {
                float absFill = clamp01((absorption - i * 2.0F) / 2.0F);
                if (absFill > 0.01F) {
                    drawClippedHeart(mc, sr, cx, cy - 0.5F, size * 0.92F, absFill, absColor);
                }
            }
        }
        endVector2D();
    }

    private void renderFood(
            Minecraft mc,
            ScaledResolution sr,
            EntityPlayer player,
            float rightX,
            float y,
            ThemeData theme) {
        long now = System.nanoTime();
        float dt = computeDelta(now, this.lastFoodNanos);
        this.lastFoodNanos = now;

        int foodLevel = Math.max(0, Math.min(20, player.getFoodStats().getFoodLevel()));
        if (this.smoothFood < 0.0F || Animation.isReduceMotion()) {
            this.smoothFood = foodLevel;
        } else {
            float lerp = 1.0F - (float) Math.exp(-14.0F * dt);
            this.smoothFood += (foodLevel - this.smoothFood) * lerp;
        }

        int trackFill = RenderUtil.withAlpha(theme.surface1(), 0.72F);
        int trackBorder = RenderUtil.withAlpha(theme.borderStrong(), 0.75F);
        boolean hasHunger = player.isPotionActive(Potion.hunger);
        int foodColor = hasHunger ? ColorAnimation.lerpArgb(theme.success(), theme.warning(), 0.35F) : theme.warning();

        beginVector2D();
        for (int i = 0; i < ICON_COUNT; i++) {
            float cx = rightX - (i + 0.5F) * ICON_STEP;
            float cy = y + ICON_SIZE * 0.5F;

            drawFoodShape(cx, cy, ICON_SIZE, trackFill, trackBorder);

            float fill = clamp01((this.smoothFood - i * 2.0F) / 2.0F);
            if (fill > 0.01F) {
                drawClippedFood(mc, sr, cx, cy, ICON_SIZE, fill, foodColor);
            }
        }
        endVector2D();
    }

    private static void beginVector2D() {
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
    }

    private static void endVector2D() {
        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void drawClippedHeart(
            Minecraft mc,
            ScaledResolution sr,
            float cx,
            float cy,
            float size,
            float fillRatio,
            int color) {
        if (fillRatio >= 0.98F) {
            drawHeartShape(cx, cy, size, RenderUtil.withAlpha(color, 0.92F), RenderUtil.withAlpha(color, 0.98F));
            drawHeartHighlight(cx, cy, size);
            return;
        }
        enableHorizontalScissor(mc, sr, cx - size * 0.55F, size * 1.10F * fillRatio);
        drawHeartShape(cx, cy, size, RenderUtil.withAlpha(color, 0.92F), RenderUtil.withAlpha(color, 0.98F));
        drawHeartHighlight(cx, cy, size);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private static void drawClippedFood(
            Minecraft mc,
            ScaledResolution sr,
            float cx,
            float cy,
            float size,
            float fillRatio,
            int color) {
        if (fillRatio >= 0.98F) {
            drawFoodShape(cx, cy, size, RenderUtil.withAlpha(color, 0.92F), RenderUtil.withAlpha(color, 0.98F));
            drawFoodHighlight(cx, cy, size);
            return;
        }
        float totalW = size * 1.10F;
        float clipW = totalW * fillRatio;
        enableHorizontalScissor(mc, sr, cx + size * 0.55F - clipW, clipW);
        drawFoodShape(cx, cy, size, RenderUtil.withAlpha(color, 0.92F), RenderUtil.withAlpha(color, 0.98F));
        drawFoodHighlight(cx, cy, size);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private static void enableHorizontalScissor(Minecraft mc, ScaledResolution sr, float x, float width) {
        int scale = Math.max(1, sr.getScaleFactor());
        int sx = Math.max(0, Math.round(x * scale));
        int sw = Math.max(1, Math.round(width * scale));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, 0, sw, mc.displayHeight);
    }

    private static void drawHeartShape(float cx, float cy, float size, int fillColor, int borderColor) {
        float scale = size * 0.028F;
        float centerY = cy - size * 0.05F;
        setColor(fillColor);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, centerY);
        emitHeartVertices(cx, centerY, scale);
        GL11.glVertex2f(cx + HEART_UNIT_X[0] * scale, centerY + HEART_UNIT_Y[0] * scale);
        GL11.glEnd();

        GL11.glLineWidth(1.05F);
        setColor(borderColor);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        emitHeartVertices(cx, centerY, scale);
        GL11.glEnd();
    }

    private static void emitHeartVertices(float cx, float cy, float scale) {
        for (int i = 0; i < HEART_VERTICES; i++) {
            GL11.glVertex2f(cx + HEART_UNIT_X[i] * scale, cy + HEART_UNIT_Y[i] * scale);
        }
    }

    private static void drawHeartHighlight(float cx, float cy, float size) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.28F);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        float hx = cx - size * 0.16F;
        float hy = cy - size * 0.16F;
        float r = size * 0.12F;
        GL11.glVertex2f(hx, hy);
        for (int i = 0; i <= HIGHLIGHT_SEGMENTS; i++) {
            GL11.glVertex2f(hx + HIGHLIGHT_COS[i] * r, hy + HIGHLIGHT_SIN[i] * r * 0.72F);
        }
        GL11.glEnd();
    }

    private static void drawFoodShape(float cx, float cy, float size, int fillColor, int borderColor) {
        setColor(fillColor);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        emitFoodVertices(cx, cy, size);
        GL11.glVertex2f(cx + FOOD_UNIT_X[0] * size, cy + FOOD_UNIT_Y[0] * size);
        GL11.glEnd();

        GL11.glLineWidth(1.05F);
        setColor(borderColor);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        emitFoodVertices(cx, cy, size);
        GL11.glEnd();
    }

    private static void emitFoodVertices(float cx, float cy, float size) {
        for (int i = 0; i < FOOD_VERTICES; i++) {
            GL11.glVertex2f(cx + FOOD_UNIT_X[i] * size, cy + FOOD_UNIT_Y[i] * size);
        }
    }

    private static void drawFoodHighlight(float cx, float cy, float size) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.28F);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        float hx = cx - size * 0.11F;
        float hy = cy - size * 0.12F;
        float r = size * 0.11F;
        GL11.glVertex2f(hx, hy);
        for (int i = 0; i <= HIGHLIGHT_SEGMENTS; i++) {
            GL11.glVertex2f(hx + HIGHLIGHT_COS[i] * r, hy + HIGHLIGHT_SIN[i] * r);
        }
        GL11.glEnd();
    }

    private static void setColor(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0F;
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        GL11.glColor4f(r, g, b, a);
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }
}
