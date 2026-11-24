package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Modern compact Potion Effects HUD displaying each active effect as a sleek rounded/frosted card
 * with a circular remaining-time progress ring, vanilla status icon, amplifier, and timer.
 */
public class PotionHud extends Module implements HudElement {
    public static final String ID = "potion_hud";

    private static final float CARD_HEIGHT = 32.0f;
    private static final float CARD_GAP = UiTokens.SPACE_4;
    private static final float MIN_CARD_WIDTH = Keystrokes.TOTAL_WIDTH;
    private static final float PAD_LEFT = 6.0f;
    private static final float PAD_RIGHT = 6.0f;
    private static final float RING_RADIUS = 8.5f;
    private static final float RING_THICKNESS = 2.0f;
    private static final float ICON_SIZE = 10.5f;
    private static final float TEXT_GAP = 6.0f;
    private static final int LOW_DURATION_TICKS = 200; // 10 seconds
    private static final int MAX_POTION_SLOTS = 64;
    private static final ResourceLocation INVENTORY_TEXTURE =
        new ResourceLocation("textures/gui/container/inventory.png");

    private static final String[] ROMAN_LEVELS = new String[] {
        "", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"
    };

    private final ModeSetting style;
    private final BooleanSetting usePotionColor;
    private final BooleanSetting showAmbient;

    private final int[] maxDurationById = new int[MAX_POTION_SLOTS];
    private final boolean[] seenThisFrame = new boolean[MAX_POTION_SLOTS];
    private final List<PotionEntry> activeScratch = new ArrayList<PotionEntry>(8);

    private float cachedWidth = MIN_CARD_WIDTH;
    private float cachedHeight = CARD_HEIGHT * 2.0f + CARD_GAP;

    private static final class PotionEntry {
        String title;
        String durationText;
        float progress;
        int ringColor;
        int iconIndex;
        boolean lowDuration;
    }

    /**
     * Creates the Potion Effects HUD module (enabled by default) and registers it with {@link HudOverlayRenderer}.
     */
    public PotionHud() {
        super(ID, Category.UI);
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.potion_hud.style.name", "card", "card", "text")
        );
        this.usePotionColor = addSetting(
            new BooleanSetting("use_potion_color", "setting.hoptimization.potion_hud.use_potion_color.name", true)
        );
        this.showAmbient = addSetting(
            new BooleanSetting("show_ambient", "setting.hoptimization.potion_hud.show_ambient.name", true)
        );
        setEnabled(true);
        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.ensureRegistered();
    }

    @Override
    public void resetToDefaults() {
        super.resetToDefaults();
        setEnabled(true);
    }

    @Override
    public String getHudId() {
        return getId();
    }

    @Override
    public String getHudDisplayName() {
        return getDisplayName();
    }

    @Override
    public boolean isHudEnabled() {
        return isEnabled();
    }

    @Override
    public float getHudWidth() {
        return this.cachedWidth;
    }

    @Override
    public float getHudHeight() {
        return this.cachedHeight;
    }

    @Override
    public float getDefaultHudX(float screenWidth, float screenHeight) {
        return UiTokens.HUD_SCREEN_MARGIN;
    }

    @Override
    public float getDefaultHudY(float screenWidth, float screenHeight) {
        return Math.max(UiTokens.HUD_SCREEN_MARGIN, screenHeight - this.cachedHeight - 56.0f);
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        collectEntries(editMode, theme);
        int count = this.activeScratch.size();
        if (count == 0) {
            return;
        }

        TtfFontRenderer titleFont = FontManager.body();
        TtfFontRenderer timeFont = FontManager.caption();
        boolean cardMode = !this.style.is("text");

        float ringAreaW = PAD_LEFT + RING_RADIUS * 2.0f + TEXT_GAP;
        float maxW = MIN_CARD_WIDTH;
        float maxTextW = Math.max(12.0f, maxW - ringAreaW - PAD_RIGHT);

        float totalH = count * CARD_HEIGHT + (count - 1) * CARD_GAP;
        this.cachedWidth = maxW;
        this.cachedHeight = totalH;

        int trackColor = RenderUtil.withAlpha(theme.borderStrong(), 0.55f);
        float curY = y;
        for (int i = 0; i < count; i++) {
            PotionEntry entry = this.activeScratch.get(i);
            if (cardMode) {
                HudRenderHelper.drawHudCard(x, curY, maxW, CARD_HEIGHT, theme, 1.0f);
            }

            float ringCenterX = x + PAD_LEFT + RING_RADIUS;
            float ringCenterY = curY + CARD_HEIGHT * 0.5f;
            int ringColor = entry.lowDuration ? theme.danger() : entry.ringColor;

            RenderUtil.drawCircularProgressRing(
                ringCenterX,
                ringCenterY,
                RING_RADIUS,
                RING_THICKNESS,
                entry.progress,
                trackColor,
                ringColor
            );

            if (entry.iconIndex >= 0) {
                drawPotionIcon(
                    entry.iconIndex,
                    ringCenterX - ICON_SIZE * 0.5f,
                    ringCenterY - ICON_SIZE * 0.5f,
                    ICON_SIZE
                );
            }

            float textX = x + ringAreaW;
            float textBlockH = titleFont.getHeight() + UiTokens.SPACE_2 + timeFont.getHeight();
            float titleY = curY + (CARD_HEIGHT - textBlockH) * 0.5f;
            float timeY = titleY + titleFont.getHeight() + UiTokens.SPACE_2;

            titleFont.drawStringWithEllipsis(entry.title, textX, titleY, maxTextW, theme.textPrimary());
            int timeColor = entry.lowDuration ? theme.danger() : theme.textSecondary();
            HudRenderHelper.drawHudText(timeFont, entry.durationText, textX, timeY, timeColor, !cardMode);

            curY += CARD_HEIGHT + CARD_GAP;
        }
    }

    private void collectEntries(boolean editMode, ThemeData theme) {
        this.activeScratch.clear();
        for (int i = 0; i < MAX_POTION_SLOTS; i++) {
            this.seenThisFrame[i] = false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        boolean useColor = this.usePotionColor.isEnabled();
        boolean includeAmbient = this.showAmbient.isEnabled();

        if (mc != null && mc.thePlayer != null) {
            Collection<PotionEffect> effects = mc.thePlayer.getActivePotionEffects();
            if (effects != null && !effects.isEmpty()) {
                for (PotionEffect effect : effects) {
                    if (effect == null || (!includeAmbient && effect.getIsAmbient())) {
                        continue;
                    }
                    int id = effect.getPotionID();
                    if (id < 0 || id >= Potion.potionTypes.length) {
                        continue;
                    }
                    Potion potion = Potion.potionTypes[id];
                    if (potion == null) {
                        continue;
                    }

                    int duration = Math.max(0, effect.getDuration());
                    float progress;
                    if (effect.getIsPotionDurationMax() || duration > 32000) {
                        progress = 1.0f;
                    } else {
                        int slot = id < MAX_POTION_SLOTS ? id : -1;
                        if (slot >= 0) {
                            this.seenThisFrame[slot] = true;
                            if (duration > this.maxDurationById[slot]) {
                                this.maxDurationById[slot] = duration;
                            }
                            int maxDur = Math.max(1, this.maxDurationById[slot]);
                            progress = Math.min(1.0f, Math.max(0.0f, duration / (float) maxDur));
                        } else {
                            progress = Math.min(1.0f, duration / 1200.0f);
                        }
                    }

                    PotionEntry entry = new PotionEntry();
                    String baseName = I18n.format(effect.getEffectName());
                    entry.title = baseName + formatAmplifier(effect.getAmplifier());
                    entry.durationText = Potion.getDurationString(effect);
                    entry.progress = progress;
                    entry.ringColor = useColor ? brightenLiquidColor(potion.getLiquidColor()) : theme.accent();
                    entry.iconIndex = potion.hasStatusIcon() ? potion.getStatusIconIndex() : -1;
                    entry.lowDuration = !effect.getIsPotionDurationMax() && duration <= LOW_DURATION_TICKS;
                    this.activeScratch.add(entry);
                }
            }
        }

        for (int i = 0; i < MAX_POTION_SLOTS; i++) {
            if (!this.seenThisFrame[i]) {
                this.maxDurationById[i] = 0;
            }
        }

        if (this.activeScratch.isEmpty() && editMode) {
            PotionEntry speed = new PotionEntry();
            speed.title = I18n.format("potion.moveSpeed") + " II";
            speed.durationText = "2:15";
            speed.progress = 0.72f;
            speed.ringColor = useColor ? brightenLiquidColor(Potion.moveSpeed.getLiquidColor()) : theme.accent();
            speed.iconIndex = Potion.moveSpeed.getStatusIconIndex();
            speed.lowDuration = false;
            this.activeScratch.add(speed);

            PotionEntry regen = new PotionEntry();
            regen.title = I18n.format("potion.regeneration");
            regen.durationText = "0:18";
            regen.progress = 0.38f;
            regen.ringColor = useColor ? brightenLiquidColor(Potion.regeneration.getLiquidColor()) : theme.accent();
            regen.iconIndex = Potion.regeneration.getStatusIconIndex();
            regen.lowDuration = false;
            this.activeScratch.add(regen);
        }
    }

    private static String formatAmplifier(int amplifier) {
        if (amplifier <= 0) {
            return "";
        }
        if (amplifier < ROMAN_LEVELS.length) {
            return ROMAN_LEVELS[amplifier];
        }
        return " " + (amplifier + 1);
    }

    private static int brightenLiquidColor(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        // Gently lift dark potion colors toward pastel brightness so the ring pops cleanly on dark glass.
        r = Math.min(255, Math.round(r * 0.80f + 56.0f));
        g = Math.min(255, Math.round(g * 0.80f + 56.0f));
        b = Math.min(255, Math.round(b * 0.80f + 56.0f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void drawPotionIcon(int iconIndex, float x, float y, float size) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        TextureManager texManager = mc.getTextureManager();
        if (texManager == null) {
            return;
        }

        int texX = (iconIndex % 8) * 18;
        int texY = 198 + (iconIndex / 8) * 18;
        float u0 = texX / 256.0f;
        float v0 = texY / 256.0f;
        float u1 = (texX + 18) / 256.0f;
        float v1 = (texY + 18) / 256.0f;

        HudRenderHelper.GlStateSnapshot snapshot = HudRenderHelper.acquireSnapshot();
        snapshot.capture();
        try {
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            );
            GlStateManager.disableAlpha();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.disableCull();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            texManager.bindTexture(INVENTORY_TEXTURE);
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            wr.pos(x, y + size, 0.0D).tex(u0, v1).color(255, 255, 255, 255).endVertex();
            wr.pos(x + size, y + size, 0.0D).tex(u1, v1).color(255, 255, 255, 255).endVertex();
            wr.pos(x + size, y, 0.0D).tex(u1, v0).color(255, 255, 255, 255).endVertex();
            wr.pos(x, y, 0.0D).tex(u0, v0).color(255, 255, 255, 255).endVertex();
            tessellator.draw();
        } finally {
            snapshot.restore();
            HudRenderHelper.releaseSnapshot();
        }
    }
}
