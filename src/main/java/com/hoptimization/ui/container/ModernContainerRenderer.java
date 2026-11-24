package com.hoptimization.ui.container;

import com.hoptimization.mixin.render.AccessorGuiContainer;
import com.hoptimization.mixin.render.AccessorGuiContainerCreative;
import com.hoptimization.modules.visual.ModernInventory;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.FontStyle;
import com.hoptimization.ui.font.FontWeight;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.BackdropBlurRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.ThemeMode;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Collection;
import java.util.List;

/**
 * Clean, restrained, theme-matched custom renderer for Minecraft's {@link GuiContainer} screens
 * (Survival Inventory, Creative Inventory, Chests, Crafting Table, Furnace, etc.) and responsive
 * left-side potion cards while keeping {@code guiLeft} strictly centered.
 */
public final class ModernContainerRenderer {
    private static final float OUTER_PAD = UiTokens.SPACE_6;
    private static final float SLOT_SIZE = 18.0f;
    private static final float SCREEN_MARGIN = UiTokens.SPACE_4;

    private static final float POTION_CARD_MAX_W = 120.0f;
    private static final float POTION_CARD_MIN_FULL_W = 104.0f;
    private static final float POTION_CARD_COMPACT_MIN_W = 60.0f;
    private static final float POTION_CARD_BADGE_W = 32.0f;
    private static final float POTION_CARD_H = 32.0f;
    private static final float POTION_CARD_GAP = UiTokens.SPACE_4;
    private static final float POTION_PAD_H = UiTokens.SPACE_6;
    private static final float POTION_RING_RADIUS = 10.0f;
    private static final float POTION_RING_THICKNESS = 2.0f;
    private static final float POTION_ICON_SIZE = 12.0f;
    private static final int MAX_POTION_SLOTS = 64;

    private static final FontStyle POTION_TITLE_STYLE = FontStyle.of(8.5f, FontWeight.MEDIUM);
    private static final FontStyle POTION_TIME_STYLE = FontStyle.of(7.5f, FontWeight.REGULAR);

    private static final ResourceLocation INVENTORY_TEXTURE =
        new ResourceLocation("textures/gui/container/inventory.png");

    private static final int[] MAX_INV_POTION_DURATION = new int[MAX_POTION_SLOTS];
    private static final boolean[] SEEN_INV_POTION = new boolean[MAX_POTION_SLOTS];
    private static final String[] ROMAN_LEVELS = new String[] {
        "", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"
    };

    private static boolean renderingForeground = false;

    // 120ms real-time slot hover interpolation state (zero allocations).
    private static float lastHoverSlotX = Float.NaN;
    private static float lastHoverSlotY = Float.NaN;
    private static float slotHoverAlpha = 0.0f;
    private static long lastHoverNanos = 0L;
    private static boolean slotHoveredLastFrame = false;
    private static boolean slotHoveredThisFrame = false;

    private ModernContainerRenderer() {
    }

    public static void setRenderingForeground(boolean active) {
        renderingForeground = active;
    }

    public static boolean isRenderingForeground() {
        return renderingForeground;
    }

    /**
     * Checks whether the local player currently has at least one renderable active potion effect.
     *
     * @param mc Minecraft client instance
     * @return {@code true} if at least one active effect should render
     */
    public static boolean hasVisiblePotionEffects(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        Collection<PotionEffect> effects = mc.thePlayer.getActivePotionEffects();
        if (effects == null || effects.isEmpty()) {
            return false;
        }
        for (PotionEffect effect : effects) {
            if (effect == null) {
                continue;
            }
            int id = effect.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length) {
                continue;
            }
            Potion potion = Potion.potionTypes[id];
            if (potion != null && potion.shouldRender(effect)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders a restrained backdrop behind container screens, capturing blur when frosted glass is enabled.
     */
    public static void renderScreenBackdrop(GuiContainer screen) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        float w = (float) sr.getScaledWidth_double();
        float h = (float) sr.getScaledHeight_double();
        ThemeData theme = ThemeManager.current();
        boolean frosted = ThemeManager.isFrostedGlass() && ModernInventory.isFrostedBackdropEnabled();
        if (frosted) {
            BackdropBlurRenderer.captureAndBlur(ThemeManager.getBlurStrength());
            RenderUtil.drawRect(0.0f, 0.0f, w, h, RenderUtil.withAlpha(theme.backdrop(), 0.44f));
        } else {
            RenderUtil.drawRect(0.0f, 0.0f, w, h, theme.backdrop());
        }
    }

    /**
     * Renders the complete modern background layer for any {@link GuiContainer}:
     * rounded/frosted panel, noise-free slot cells, aligned creative tabs/search/scrollbar, and paperdoll frame.
     */
    public static void renderContainerBackground(GuiContainer screen, float partialTicks, int mouseX, int mouseY) {
        if (!slotHoveredThisFrame && slotHoveredLastFrame) {
            slotHoverAlpha = 0.0f;
            lastHoverSlotX = Float.NaN;
            lastHoverSlotY = Float.NaN;
        }
        slotHoveredLastFrame = slotHoveredThisFrame;
        slotHoveredThisFrame = false;

        if (!(screen instanceof AccessorGuiContainer)) {
            return;
        }
        AccessorGuiContainer acc = (AccessorGuiContainer) screen;
        float guiLeft = acc.hoptimization$getGuiLeft();
        float guiTop = acc.hoptimization$getGuiTop();
        float xSize = acc.hoptimization$getXSize();
        float ySize = acc.hoptimization$getYSize();

        ThemeData theme = ThemeManager.current();
        boolean frosted = ThemeManager.isFrostedGlass() && ModernInventory.isFrostedBackdropEnabled();
        boolean isLight = theme.getMode() == ThemeMode.LIGHT;
        float winAlpha = theme.style().getWindowOpacity();

        if (screen instanceof GuiContainerCreative) {
            renderCreativeTabs((GuiContainerCreative) screen, guiLeft, guiTop, xSize, ySize, theme, frosted, false);
        }

        float panelX = guiLeft - OUTER_PAD;
        float panelY = guiTop - OUTER_PAD;
        float panelW = xSize + OUTER_PAD * 2.0f;
        float panelH = ySize + OUTER_PAD * 2.0f;
        float radius = theme.radiusWindow();

        if (frosted) {
            RenderUtil.drawFrostedRoundedRectWithShadow(
                panelX, panelY, panelW, panelH,
                radius,
                theme.frostedWindowTint(),
                theme.frostedBorder(),
                UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength(),
                winAlpha,
                0.0f,
                UiTokens.SHADOW_WINDOW_OFFSET_Y,
                UiTokens.SHADOW_WINDOW_BLUR,
                theme.windowShadowColor()
            );
        } else {
            RenderUtil.drawRoundedRectWithShadow(
                panelX, panelY, panelW, panelH,
                radius,
                RenderUtil.withAlpha(theme.surface0(), winAlpha),
                RenderUtil.withAlpha(theme.border(), winAlpha),
                UiTokens.BORDER_WIDTH,
                0.0f,
                UiTokens.SHADOW_WINDOW_OFFSET_Y,
                UiTokens.SHADOW_WINDOW_BLUR,
                RenderUtil.withAlpha(theme.windowShadowColor(), winAlpha)
            );
        }

        renderContainerSlots(screen, guiLeft, guiTop, theme, frosted, isLight, winAlpha);

        if (screen instanceof GuiInventory) {
            renderSurvivalExtras(guiLeft, guiTop, mouseX, mouseY, theme, frosted, isLight, winAlpha);
        } else if (screen instanceof GuiContainerCreative) {
            renderCreativeExtras(
                (GuiContainerCreative) screen,
                guiLeft, guiTop, xSize, ySize,
                mouseX, mouseY,
                theme, frosted, winAlpha
            );
        }
    }

    /**
     * Renders a smooth 120ms interpolated rounded slot hover highlight replacing vanilla's harsh white box.
     */
    public static void renderSlotHoverHighlight(float x, float y, float size) {
        slotHoveredThisFrame = true;
        long now = System.nanoTime();
        float dtSec = lastHoverNanos == 0L
            ? 0.016f
            : Math.min(0.05f, Math.max(0.0005f, (now - lastHoverNanos) / 1_000_000_000.0f));
        lastHoverNanos = now;

        if (Float.isNaN(lastHoverSlotX)
            || Math.abs(x - lastHoverSlotX) > 0.5f
            || Math.abs(y - lastHoverSlotY) > 0.5f) {
            lastHoverSlotX = x;
            lastHoverSlotY = y;
            slotHoverAlpha = Animation.isReduceMotion() ? 1.0f : (slotHoveredLastFrame ? 0.42f : 0.15f);
        }

        if (Animation.isReduceMotion()) {
            slotHoverAlpha = 1.0f;
        } else {
            float factor = 1.0f - (float) Math.exp(-dtSec / 0.055f);
            slotHoverAlpha += (1.0f - slotHoverAlpha) * factor;
            if (slotHoverAlpha > 0.995f) {
                slotHoverAlpha = 1.0f;
            }
        }

        ThemeData theme = ThemeManager.current();
        float radius = Math.min(UiTokens.RADIUS_MIN_NESTED, theme.radiusControl());
        int fill = RenderUtil.withAlpha(theme.accentMuted(), 0.90f * slotHoverAlpha);
        int border = RenderUtil.withAlpha(theme.accent(), 0.65f * slotHoverAlpha);
        RenderUtil.drawRoundedRect(
            x - 1.0f,
            y - 1.0f,
            size + 2.0f,
            size + 2.0f,
            radius,
            fill,
            border,
            UiTokens.BORDER_WIDTH
        );
    }

    /**
     * Renders responsive, theme-matched potion effect cards to the left of the centered inventory:
     * full card on wide viewports, compact timer capsule on medium viewports, and circular icon badge on narrow ones.
     */
    public static void renderInventoryPotions(GuiContainer screen, float guiLeft, float guiTop) {
        if (!ModernInventory.isShowPotionsEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        Collection<PotionEffect> effects = mc.thePlayer.getActivePotionEffects();
        if (effects == null || effects.isEmpty()) {
            return;
        }

        for (int i = 0; i < MAX_POTION_SLOTS; i++) {
            SEEN_INV_POTION[i] = false;
        }

        int visibleCount = 0;
        for (PotionEffect effect : effects) {
            if (effect == null) {
                continue;
            }
            int id = effect.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length) {
                continue;
            }
            Potion potion = Potion.potionTypes[id];
            if (potion != null && potion.shouldRender(effect)) {
                visibleCount++;
            }
        }
        if (visibleCount == 0) {
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        float srScale = Math.max(1.0f, (float) sr.getScaleFactor());
        TtfFontRenderer titleFont = FontManager.getInstance().getRenderer(POTION_TITLE_STYLE, srScale);
        TtfFontRenderer timeFont = FontManager.getInstance().getRenderer(POTION_TIME_STYLE, srScale);

        float panelLeft = guiLeft - OUTER_PAD;
        float availLeftW = panelLeft - SCREEN_MARGIN * 2.0f;

        // 0 = badge (32x32), 1 = compact ring + timer, 2 = full card (ring + title + timer)
        int layoutMode;
        float cardW;
        if (availLeftW >= POTION_CARD_MIN_FULL_W) {
            layoutMode = 2;
            cardW = Math.min(POTION_CARD_MAX_W, availLeftW);
        } else if (availLeftW >= POTION_CARD_COMPACT_MIN_W) {
            layoutMode = 1;
            cardW = availLeftW;
        } else {
            layoutMode = 0;
            cardW = POTION_CARD_BADGE_W;
        }
        float cardX = Math.max(SCREEN_MARGIN, panelLeft - SCREEN_MARGIN - cardW);

        ThemeData theme = ThemeManager.current();
        boolean frosted = ThemeManager.isFrostedGlass() && ModernInventory.isFrostedBackdropEnabled();
        boolean hasBorder = theme.style().isCardBorder();
        float stepY = visibleCount > 5
            ? (136.0f / Math.max(1, visibleCount - 1))
            : (POTION_CARD_H + POTION_CARD_GAP);
        float curY = guiTop - OUTER_PAD;
        int trackColor = RenderUtil.withAlpha(theme.borderStrong(), 0.55f);

        for (PotionEffect effect : effects) {
            if (effect == null) {
                continue;
            }
            int id = effect.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length) {
                continue;
            }
            Potion potion = Potion.potionTypes[id];
            if (potion == null || !potion.shouldRender(effect)) {
                continue;
            }

            int duration = Math.max(0, effect.getDuration());
            float progress = resolvePotionProgress(effect, id, duration);

            if (frosted) {
                RenderUtil.drawFrostedRoundedRect(
                    cardX, curY, cardW, POTION_CARD_H,
                    theme.radiusCard(),
                    theme.frostedHudTint(),
                    theme.frostedBorder(),
                    UiTokens.BORDER_WIDTH,
                    theme.frostedHighlightStrength(),
                    1.0f
                );
            } else {
                RenderUtil.drawRoundedRect(
                    cardX, curY, cardW, POTION_CARD_H,
                    theme.radiusCard(),
                    theme.hudBackground(),
                    hasBorder ? theme.border() : 0,
                    hasBorder ? UiTokens.BORDER_WIDTH : 0.0f
                );
            }

            float ringX = layoutMode == 0
                ? (cardX + cardW * 0.5f)
                : (cardX + POTION_PAD_H + POTION_RING_RADIUS);
            float ringY = curY + POTION_CARD_H * 0.5f;
            boolean low = !effect.getIsPotionDurationMax() && duration <= 200;
            int ringColor = low ? theme.danger() : brightenColor(potion.getLiquidColor());

            RenderUtil.drawCircularProgressRing(
                ringX, ringY, POTION_RING_RADIUS, POTION_RING_THICKNESS,
                progress, trackColor, ringColor
            );

            if (potion.hasStatusIcon()) {
                drawPotionIcon(
                    potion.getStatusIconIndex(),
                    ringX - POTION_ICON_SIZE * 0.5f,
                    ringY - POTION_ICON_SIZE * 0.5f,
                    POTION_ICON_SIZE
                );
            }

            if (layoutMode >= 1 && potion.shouldRenderInvText(effect)) {
                String timeStr = Potion.getDurationString(effect);
                int timeColor = low ? theme.danger() : theme.textSecondary();
                float textLeft = cardX + POTION_PAD_H + POTION_RING_RADIUS * 2.0f + POTION_PAD_H;
                float maxTextW = Math.max(0.0f, cardX + cardW - POTION_PAD_H - textLeft);

                if (layoutMode == 2 && maxTextW > 12.0f) {
                    String title = I18n.format(potion.getName()) + formatAmplifier(effect.getAmplifier());
                    float blockH = titleFont.getHeight() + 1.0f + timeFont.getHeight();
                    float titleY = curY + (POTION_CARD_H - blockH) * 0.5f;
                    float timeY = titleY + titleFont.getHeight() + 1.0f;
                    titleFont.drawStringWithEllipsis(title, textLeft, titleY, maxTextW, theme.textPrimary());
                    timeFont.drawStringWithEllipsis(timeStr, textLeft, timeY, maxTextW, timeColor);
                } else if (maxTextW > 8.0f) {
                    float timeY = curY + (POTION_CARD_H - timeFont.getHeight()) * 0.5f;
                    timeFont.drawStringWithEllipsis(timeStr, textLeft, timeY, maxTextW, timeColor);
                }
            }

            curY += stepY;
        }

        for (int i = 0; i < MAX_POTION_SLOTS; i++) {
            if (!SEEN_INV_POTION[i]) {
                MAX_INV_POTION_DURATION[i] = 0;
            }
        }
    }

    private static float resolvePotionProgress(PotionEffect effect, int id, int duration) {
        if (effect.getIsPotionDurationMax() || duration > 32000) {
            return 1.0f;
        }
        int slot = id < MAX_POTION_SLOTS ? id : -1;
        if (slot >= 0) {
            SEEN_INV_POTION[slot] = true;
            if (duration > MAX_INV_POTION_DURATION[slot]) {
                MAX_INV_POTION_DURATION[slot] = duration;
            }
            int maxDur = Math.max(1, MAX_INV_POTION_DURATION[slot]);
            return Math.min(1.0f, Math.max(0.0f, duration / (float) maxDur));
        }
        return Math.min(1.0f, duration / 1200.0f);
    }

    private static void renderContainerSlots(
        GuiContainer screen,
        float guiLeft,
        float guiTop,
        ThemeData theme,
        boolean frosted,
        boolean isLight,
        float winAlpha
    ) {
        if (screen.inventorySlots == null || screen.inventorySlots.inventorySlots == null) {
            return;
        }
        boolean outlined = ModernInventory.isOutlinedSlots();
        int slotFill = outlined
            ? 0
            : RenderUtil.withAlpha(theme.surface2(), (frosted ? (isLight ? 0.52f : 0.44f) : 0.88f) * winAlpha);
        int slotBorder = outlined
            ? RenderUtil.withAlpha(theme.borderStrong(), (frosted ? 0.85f : 1.0f) * winAlpha)
            : 0;
        float borderW = outlined ? UiTokens.BORDER_WIDTH : 0.0f;
        float slotRadius = Math.min(UiTokens.RADIUS_MIN_NESTED, theme.radiusControl());

        List<Slot> slots = screen.inventorySlots.inventorySlots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot == null || !slot.canBeHovered()) {
                continue;
            }
            // Skip vanilla offscreen hidden creative survival slots (placed at -2000).
            if (slot.xDisplayPosition < -100 || slot.yDisplayPosition < -100) {
                continue;
            }
            float sx = guiLeft + slot.xDisplayPosition - 1.0f;
            float sy = guiTop + slot.yDisplayPosition - 1.0f;
            RenderUtil.drawRoundedRect(sx, sy, SLOT_SIZE, SLOT_SIZE, slotRadius, slotFill, slotBorder, borderW);
        }
    }

    private static void renderSurvivalExtras(
        float guiLeft,
        float guiTop,
        int mouseX,
        int mouseY,
        ThemeData theme,
        boolean frosted,
        boolean isLight,
        float winAlpha
    ) {
        float boxX = guiLeft + 26.0f;
        float boxY = guiTop + 8.0f;
        float boxW = 52.0f;
        float boxH = 70.0f;
        float frameOpacity = (frosted ? (isLight ? 0.50f : 0.42f) : 0.92f) * winAlpha;
        int frameFill = RenderUtil.withAlpha(theme.surface1(), frameOpacity);
        int frameBorder = theme.style().isCardBorder() ? RenderUtil.withAlpha(theme.border(), winAlpha) : 0;
        RenderUtil.drawRoundedRect(
            boxX, boxY, boxW, boxH,
            theme.radiusCard(),
            frameFill,
            frameBorder,
            frameBorder != 0 ? UiTokens.BORDER_WIDTH : 0.0f
        );

        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            GuiInventory.drawEntityOnScreen(
                Math.round(guiLeft + 52.0f),
                Math.round(guiTop + 73.0f),
                30,
                (guiLeft + 52.0f) - mouseX,
                (guiTop + 73.0f - 50.0f) - mouseY,
                mc.thePlayer
            );
        }
    }

    private static void renderCreativeExtras(
        GuiContainerCreative creative,
        float guiLeft,
        float guiTop,
        float xSize,
        float ySize,
        int mouseX,
        int mouseY,
        ThemeData theme,
        boolean frosted,
        float winAlpha
    ) {
        AccessorGuiContainerCreative acc = (AccessorGuiContainerCreative) creative;
        int selectedIdx = AccessorGuiContainerCreative.hoptimization$getSelectedTabIndex();
        CreativeTabs[] tabs = CreativeTabs.creativeTabArray;
        CreativeTabs activeTab = (selectedIdx >= 0 && selectedIdx < tabs.length)
            ? tabs[selectedIdx]
            : CreativeTabs.tabBlock;

        // Render active tab docked flush against container edge.
        renderCreativeTabs(creative, guiLeft, guiTop, xSize, ySize, theme, frosted, true);

        // Clean search field background & theme-matched text color for all searchable tabs.
        GuiTextField searchField = acc.hoptimization$getSearchField();
        if (searchField != null
            && (activeTab == CreativeTabs.tabAllSearch || activeTab.hasSearchBar() || searchField.getVisible())) {
            float fieldX = searchField.xPosition - 3.0f;
            float fieldY = searchField.yPosition - 2.0f;
            float fieldW = Math.max(48.0f, searchField.width + 6.0f);
            float fieldH = 12.0f;
            RenderUtil.drawRoundedRect(
                fieldX,
                fieldY,
                fieldW,
                fieldH,
                Math.min(UiTokens.RADIUS_MIN_NESTED, theme.radiusControl()),
                RenderUtil.withAlpha(theme.surface2(), (frosted ? 0.78f : 1.0f) * winAlpha),
                searchField.isFocused() ? theme.accent() : theme.borderStrong(),
                UiTokens.BORDER_WIDTH
            );
            searchField.setTextColor(theme.textPrimary() & 0x00FFFFFF);
            searchField.setDisabledTextColour(theme.textTertiary() & 0x00FFFFFF);
            searchField.drawTextBox();
        }

        // 4px modern pill scrollbar replacing vanilla's textured knob.
        if (activeTab.shouldHidePlayerInventory()) {
            float trackX = guiLeft + 178.0f;
            float trackY = guiTop + 18.0f;
            float trackW = UiTokens.SPACE_4;
            float trackH = 112.0f;
            float pillR = theme.radiusPill(trackW);
            RenderUtil.drawRoundedRect(
                trackX, trackY, trackW, trackH,
                pillR,
                RenderUtil.withAlpha(theme.surface2(), 0.72f * winAlpha)
            );
            boolean canScroll = acc.hoptimization$needsScrollBars();
            float thumbH = UiTokens.SPACE_16;
            float rawScroll = acc.hoptimization$getCurrentScroll();
            float clamped = rawScroll < 0.0f ? 0.0f : (rawScroll > 1.0f ? 1.0f : rawScroll);
            float thumbY = trackY + (trackH - thumbH) * clamped;
            int thumbColor = canScroll
                ? RenderUtil.withAlpha(theme.accent(), winAlpha)
                : RenderUtil.withAlpha(theme.textTertiary(), 0.45f * winAlpha);
            RenderUtil.drawRoundedRect(trackX, thumbY, trackW, thumbH, pillR, thumbColor);
        }

        // Player paperdoll inside Creative Survival Inventory tab.
        if (activeTab == CreativeTabs.tabInventory) {
            int paperBorder = theme.style().isCardBorder() ? RenderUtil.withAlpha(theme.border(), winAlpha) : 0;
            RenderUtil.drawRoundedRect(
                guiLeft + 26.0f,
                guiTop + 6.0f,
                34.0f,
                44.0f,
                theme.radiusControl(),
                RenderUtil.withAlpha(theme.surface1(), (frosted ? 0.48f : 0.92f) * winAlpha),
                paperBorder,
                paperBorder != 0 ? UiTokens.BORDER_WIDTH : 0.0f
            );
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                GuiInventory.drawEntityOnScreen(
                    Math.round(guiLeft + 43.0f),
                    Math.round(guiTop + 45.0f),
                    20,
                    (guiLeft + 43.0f) - mouseX,
                    (guiTop + 45.0f - 30.0f) - mouseY,
                    mc.thePlayer
                );
            }
        }
    }

    private static void renderCreativeTabs(
        GuiContainerCreative creative,
        float guiLeft,
        float guiTop,
        float xSize,
        float ySize,
        ThemeData theme,
        boolean frosted,
        boolean drawActiveOnly
    ) {
        int selectedIdx = AccessorGuiContainerCreative.hoptimization$getSelectedTabIndex();
        int tabPage = AccessorGuiContainerCreative.hoptimization$getTabPage();
        CreativeTabs[] tabs = CreativeTabs.creativeTabArray;
        int start = tabPage * 10;
        int end = Math.min(tabs.length, (tabPage + 1) * 10 + 2);
        if (tabPage != 0) {
            start += 2;
        }

        for (int i = start; i < end; i++) {
            CreativeTabs tab = tabs[i];
            if (tab == null) {
                continue;
            }
            boolean isSelected = tab.getTabIndex() == selectedIdx;
            if (isSelected != drawActiveOnly) {
                continue;
            }
            drawSingleCreativeTab(tab, isSelected, guiLeft, guiTop, xSize, ySize, theme, frosted);
        }

        if (tabPage != 0) {
            CreativeTabs searchTab = CreativeTabs.tabAllSearch;
            CreativeTabs invTab = CreativeTabs.tabInventory;
            if ((searchTab.getTabIndex() == selectedIdx) == drawActiveOnly) {
                drawSingleCreativeTab(
                    searchTab, searchTab.getTabIndex() == selectedIdx,
                    guiLeft, guiTop, xSize, ySize, theme, frosted
                );
            }
            if ((invTab.getTabIndex() == selectedIdx) == drawActiveOnly) {
                drawSingleCreativeTab(
                    invTab, invTab.getTabIndex() == selectedIdx,
                    guiLeft, guiTop, xSize, ySize, theme, frosted
                );
            }
        }
    }

    private static void drawSingleCreativeTab(
        CreativeTabs tab,
        boolean selected,
        float guiLeft,
        float guiTop,
        float xSize,
        float ySize,
        ThemeData theme,
        boolean frosted
    ) {
        int col = tab.getTabColumn();
        boolean topRow = tab.isTabInFirstRow();
        // Match vanilla 28x28 click hitbox [guiLeft + 28*col, guiTop - 28 .. guiTop] without overflowing vertically.
        float slotX = guiLeft + (col == 5 ? (xSize - 28.0f) : (28.0f * col));
        float tabW = 26.0f;
        float tabH = 22.0f;
        float tabX = slotX + 1.0f;
        float tabY = topRow ? (guiTop - OUTER_PAD - tabH) : (guiTop + ySize + OUTER_PAD);

        float r = theme.radiusControl();
        float rTL = topRow ? r : 0.0f;
        float rTR = topRow ? r : 0.0f;
        float rBR = topRow ? 0.0f : r;
        float rBL = topRow ? 0.0f : r;

        int bg;
        int border;
        if (frosted) {
            bg = selected ? theme.frostedCardTint() : theme.frostedPanelTint();
            border = selected ? theme.accent() : theme.frostedBorder();
            RenderUtil.drawFrostedRoundedRect(
                tabX, tabY, tabW, tabH,
                r,
                bg, border, UiTokens.BORDER_WIDTH,
                theme.frostedHighlightStrength(),
                1.0f
            );
        } else {
            bg = selected ? theme.surface2() : RenderUtil.withAlpha(theme.surface1(), 0.92f);
            border = selected ? theme.accent() : theme.border();
            RenderUtil.drawRoundedRect(tabX, tabY, tabW, tabH, rTL, rTR, rBR, rBL, bg, border, UiTokens.BORDER_WIDTH);
        }

        if (selected) {
            float barW = UiTokens.SPACE_12;
            float barX = tabX + (tabW - barW) * 0.5f;
            float barY = topRow ? (tabY + 1.5f) : (tabY + tabH - 3.5f);
            RenderUtil.drawRoundedRect(barX, barY, barW, 2.0f, 1.0f, theme.accent());
        }

        ItemStack iconStack = tab.getIconItemStack();
        if (iconStack != null) {
            Minecraft mc = Minecraft.getMinecraft();
            RenderItem itemRenderer = mc != null ? mc.getRenderItem() : null;
            if (itemRenderer != null) {
                GlStateManager.pushMatrix();
                RenderHelper.enableGUIStandardItemLighting();
                GlStateManager.enableRescaleNormal();
                GlStateManager.enableDepth();
                itemRenderer.zLevel = 100.0f;
                int iconX = Math.round(tabX + (tabW - 16.0f) * 0.5f);
                int iconY = Math.round(tabY + (tabH - 16.0f) * 0.5f + (topRow ? 1.0f : -1.0f));
                itemRenderer.renderItemAndEffectIntoGUI(iconStack, iconX, iconY);
                itemRenderer.zLevel = 0.0f;
                RenderHelper.disableStandardItemLighting();
                GlStateManager.disableLighting();
                GlStateManager.disableDepth();
                GlStateManager.popMatrix();
            }
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

    private static int brightenColor(int rgb) {
        int r = Math.min(255, Math.round(((rgb >>> 16) & 0xFF) * 0.80f + 56.0f));
        int g = Math.min(255, Math.round(((rgb >>> 8) & 0xFF) * 0.80f + 56.0f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * 0.80f + 56.0f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void drawPotionIcon(int iconIndex, float x, float y, float size) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getTextureManager() == null) {
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

            mc.getTextureManager().bindTexture(INVENTORY_TEXTURE);
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
