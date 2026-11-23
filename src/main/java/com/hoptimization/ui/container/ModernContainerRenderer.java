package com.hoptimization.ui.container;

import com.hoptimization.mixin.render.AccessorGuiContainer;
import com.hoptimization.mixin.render.AccessorGuiContainerCreative;
import com.hoptimization.modules.visual.ModernInventory;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.BackdropBlurRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.ThemeMode;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
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
 * (Survival Inventory, Creative Inventory, Chests, Crafting Table, Furnace, etc.) and inventory potion cards.
 */
public final class ModernContainerRenderer {
    private static final float OUTER_PAD = 6.0f;
    private static final float SLOT_SIZE = 18.0f;
    private static final float POTION_CARD_W = 122.0f;
    private static final float POTION_CARD_H = 32.0f;
    private static final float POTION_CARD_GAP = 5.0f;
    private static final float POTION_RING_RADIUS = 10.0f;
    private static final float POTION_RING_THICKNESS = 2.0f;
    private static final float POTION_ICON_SIZE = 12.0f;
    private static final int MAX_POTION_SLOTS = 64;
    private static final ResourceLocation INVENTORY_TEXTURE =
        new ResourceLocation("textures/gui/container/inventory.png");

    private static final int[] MAX_INV_POTION_DURATION = new int[MAX_POTION_SLOTS];
    private static final boolean[] SEEN_INV_POTION = new boolean[MAX_POTION_SLOTS];
    private static final String[] ROMAN_LEVELS = new String[] {
        "", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"
    };

    private static boolean renderingForeground = false;

    private ModernContainerRenderer() {
    }

    public static void setRenderingForeground(boolean active) {
        renderingForeground = active;
    }

    public static boolean isRenderingForeground() {
        return renderingForeground;
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
            RenderUtil.drawRect(0.0f, 0.0f, w, h, RenderUtil.withAlpha(theme.backdrop(), 0.42f));
        } else {
            RenderUtil.drawRect(0.0f, 0.0f, w, h, RenderUtil.withAlpha(theme.backdrop(), 0.72f));
        }
    }

    /**
     * Renders the complete modern background layer for any {@link GuiContainer}:
     * rounded/frosted panel, slot cells, creative tabs/search/scrollbar, and survival paperdoll frame.
     */
    public static void renderContainerBackground(GuiContainer screen, float partialTicks, int mouseX, int mouseY) {
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

        if (screen instanceof GuiContainerCreative) {
            renderCreativeTabs(screen, (GuiContainerCreative) screen, guiLeft, guiTop, xSize, ySize, theme, frosted, isLight, false);
        }

        // Main container card (slightly padded around vanilla bounds so outer slots have breathing room).
        float panelX = guiLeft - OUTER_PAD;
        float panelY = guiTop - OUTER_PAD;
        float panelW = xSize + OUTER_PAD * 2.0f;
        float panelH = ySize + OUTER_PAD * 2.0f;
        float radius = theme.radiusWindow();

        if (frosted) {
            int glassTint = RenderUtil.withAlpha(theme.surface0(), isLight ? 0.68f : 0.56f);
            int glassBorder = isLight ? theme.borderStrong() : 0x38FFFFFF;
            RenderUtil.drawFrostedRoundedRectWithShadow(
                panelX, panelY, panelW, panelH,
                radius,
                glassTint,
                glassBorder,
                UiTokens.BORDER_WIDTH,
                isLight ? 0.14f : 0.09f,
                1.0f,
                0.0f,
                UiTokens.SHADOW_WINDOW_OFFSET_Y,
                UiTokens.SHADOW_WINDOW_BLUR,
                theme.windowShadowColor()
            );
        } else {
            RenderUtil.drawRoundedRectWithShadow(
                panelX, panelY, panelW, panelH,
                radius,
                theme.surface0(),
                theme.border(),
                UiTokens.BORDER_WIDTH,
                0.0f,
                UiTokens.SHADOW_WINDOW_OFFSET_Y,
                UiTokens.SHADOW_WINDOW_BLUR,
                theme.windowShadowColor()
            );
        }

        // Render clean slot cells for every visible container slot.
        renderContainerSlots(screen, guiLeft, guiTop, theme, frosted, isLight);

        if (screen instanceof GuiInventory) {
            renderSurvivalExtras((GuiInventory) screen, guiLeft, guiTop, mouseX, mouseY, theme, frosted, isLight);
        } else if (screen instanceof GuiContainerCreative) {
            renderCreativeExtras(screen, (GuiContainerCreative) screen, guiLeft, guiTop, xSize, ySize, mouseX, mouseY, theme, frosted, isLight);
        }
    }

    /**
     * Renders a restrained rounded slot hover highlight replacing vanilla's harsh white box.
     */
    public static void renderSlotHoverHighlight(float x, float y, float size) {
        ThemeData theme = ThemeManager.current();
        float radius = Math.min(3.5f, theme.radiusControl());
        RenderUtil.drawRoundedRect(
            x - 1.0f,
            y - 1.0f,
            size + 2.0f,
            size + 2.0f,
            radius,
            RenderUtil.withAlpha(theme.accentMuted(), 0.75f),
            RenderUtil.withAlpha(theme.accent(), 0.75f),
            1.0f
        );
    }

    /**
     * Renders clean, theme-matched potion effect cards to the left of {@link net.minecraft.client.renderer.InventoryEffectRenderer}
     * with high-resolution SDF circular progress rings and no overlap against the inventory card.
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

        ThemeData theme = ThemeManager.current();
        boolean frosted = ThemeManager.isFrostedGlass() && ModernInventory.isFrostedBackdropEnabled();
        boolean isLight = theme.getMode() == ThemeMode.LIGHT;
        float cardX = guiLeft - OUTER_PAD - POTION_CARD_W - 6.0f;
        float stepY = visibleCount > 5 ? (136.0f / Math.max(1, visibleCount - 1)) : (POTION_CARD_H + POTION_CARD_GAP);
        float curY = guiTop - OUTER_PAD;
        int trackColor = RenderUtil.withAlpha(theme.borderStrong(), 0.55f);
        FontRenderer fr = mc.fontRendererObj;

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
            float progress;
            if (effect.getIsPotionDurationMax() || duration > 32000) {
                progress = 1.0f;
            } else {
                int slot = id < MAX_POTION_SLOTS ? id : -1;
                if (slot >= 0) {
                    SEEN_INV_POTION[slot] = true;
                    if (duration > MAX_INV_POTION_DURATION[slot]) {
                        MAX_INV_POTION_DURATION[slot] = duration;
                    }
                    int maxDur = Math.max(1, MAX_INV_POTION_DURATION[slot]);
                    progress = Math.min(1.0f, Math.max(0.0f, duration / (float) maxDur));
                } else {
                    progress = Math.min(1.0f, duration / 1200.0f);
                }
            }

            if (frosted) {
                RenderUtil.drawFrostedRoundedRect(
                    cardX, curY, POTION_CARD_W, POTION_CARD_H,
                    theme.radiusCard(),
                    RenderUtil.withAlpha(theme.surface0(), isLight ? 0.64f : 0.52f),
                    isLight ? theme.borderStrong() : 0x36FFFFFF,
                    UiTokens.BORDER_WIDTH,
                    isLight ? 0.14f : 0.10f,
                    1.0f
                );
            } else {
                RenderUtil.drawRoundedRect(
                    cardX, curY, POTION_CARD_W, POTION_CARD_H,
                    theme.radiusCard(),
                    theme.surface0(),
                    theme.border(),
                    UiTokens.BORDER_WIDTH
                );
            }

            float ringX = cardX + 7.0f + POTION_RING_RADIUS;
            float ringY = curY + POTION_CARD_H * 0.5f;
            boolean low = !effect.getIsPotionDurationMax() && duration <= 200;
            int ringColor = low ? theme.danger() : brightenColor(potion.getLiquidColor());

            RenderUtil.drawCircularProgressRing(
                ringX, ringY, POTION_RING_RADIUS, POTION_RING_THICKNESS,
                progress, trackColor, ringColor
            );

            if (potion.hasStatusIcon()) {
                drawPotionIcon(potion.getStatusIconIndex(), ringX - POTION_ICON_SIZE * 0.5f, ringY - POTION_ICON_SIZE * 0.5f, POTION_ICON_SIZE);
            }

            if (potion.shouldRenderInvText(effect) && fr != null) {
                String title = I18n.format(potion.getName()) + formatAmplifier(effect.getAmplifier());
                String timeStr = Potion.getDurationString(effect);
                int textX = Math.round(cardX + 7.0f + POTION_RING_RADIUS * 2.0f + 7.0f);
                fr.drawStringWithShadow(title, textX, Math.round(curY + 6.0f), theme.textPrimary());
                fr.drawStringWithShadow(timeStr, textX, Math.round(curY + 17.0f), low ? theme.danger() : theme.textSecondary());
            }

            curY += stepY;
        }

        for (int i = 0; i < MAX_POTION_SLOTS; i++) {
            if (!SEEN_INV_POTION[i]) {
                MAX_INV_POTION_DURATION[i] = 0;
            }
        }
    }

    private static void renderContainerSlots(
        GuiContainer screen,
        float guiLeft,
        float guiTop,
        ThemeData theme,
        boolean frosted,
        boolean isLight
    ) {
        if (screen.inventorySlots == null || screen.inventorySlots.inventorySlots == null) {
            return;
        }
        boolean outlined = ModernInventory.isOutlinedSlots();
        int slotFill = outlined
            ? 0
            : RenderUtil.withAlpha(theme.surface2(), frosted ? (isLight ? 0.50f : 0.42f) : 0.85f);
        int slotBorder = RenderUtil.withAlpha(
            outlined ? theme.borderStrong() : theme.border(),
            frosted ? 0.80f : 1.0f
        );
        float slotRadius = Math.min(3.5f, theme.radiusControl());

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
            RenderUtil.drawRoundedRect(sx, sy, SLOT_SIZE, SLOT_SIZE, slotRadius, slotFill, slotBorder, 1.0f);
        }
    }

    private static void renderSurvivalExtras(
        GuiInventory screen,
        float guiLeft,
        float guiTop,
        int mouseX,
        int mouseY,
        ThemeData theme,
        boolean frosted,
        boolean isLight
    ) {
        // Restrained recessed frame for the 3D player model preview.
        float boxX = guiLeft + 26.0f;
        float boxY = guiTop + 8.0f;
        float boxW = 52.0f;
        float boxH = 70.0f;
        int frameFill = RenderUtil.withAlpha(theme.surface1(), frosted ? (isLight ? 0.48f : 0.40f) : 0.90f);
        RenderUtil.drawRoundedRect(boxX, boxY, boxW, boxH, theme.radiusCard(), frameFill, theme.border(), 1.0f);

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
        GuiContainer screen,
        GuiContainerCreative creative,
        float guiLeft,
        float guiTop,
        float xSize,
        float ySize,
        int mouseX,
        int mouseY,
        ThemeData theme,
        boolean frosted,
        boolean isLight
    ) {
        AccessorGuiContainerCreative acc = (AccessorGuiContainerCreative) creative;
        int selectedIdx = AccessorGuiContainerCreative.hoptimization$getSelectedTabIndex();
        CreativeTabs[] tabs = CreativeTabs.creativeTabArray;
        CreativeTabs activeTab = (selectedIdx >= 0 && selectedIdx < tabs.length) ? tabs[selectedIdx] : CreativeTabs.tabBlock;

        // Render active tab pill above/below container.
        renderCreativeTabs(screen, creative, guiLeft, guiTop, xSize, ySize, theme, frosted, isLight, true);

        // Clean search field background when Search tab is active.
        GuiTextField searchField = acc.hoptimization$getSearchField();
        if (searchField != null && activeTab == CreativeTabs.tabAllSearch) {
            RenderUtil.drawRoundedRect(
                guiLeft + 80.0f,
                guiTop + 4.0f,
                90.0f,
                12.0f,
                theme.radiusControl(),
                RenderUtil.withAlpha(theme.surface2(), frosted ? 0.72f : 1.0f),
                theme.borderStrong(),
                1.0f
            );
            searchField.drawTextBox();
        }

        // Sleek modern scrollbar pill replacing vanilla's textured knob.
        if (activeTab.shouldHidePlayerInventory()) {
            float trackX = guiLeft + 178.0f;
            float trackY = guiTop + 18.0f;
            float trackW = 5.0f;
            float trackH = 112.0f;
            RenderUtil.drawRoundedRect(
                trackX, trackY, trackW, trackH,
                trackW * 0.5f,
                RenderUtil.withAlpha(theme.surface2(), 0.70f)
            );
            boolean canScroll = acc.hoptimization$needsScrollBars();
            float thumbH = 18.0f;
            float scrollRatio = acc.hoptimization$getCurrentScroll();
            float thumbY = trackY + (trackH - thumbH) * (scrollRatio < 0.0f ? 0.0f : (scrollRatio > 1.0f ? 1.0f : scrollRatio));
            int thumbColor = canScroll ? theme.accent() : RenderUtil.withAlpha(theme.textTertiary(), 0.45f);
            RenderUtil.drawRoundedRect(trackX, thumbY, trackW, thumbH, trackW * 0.5f, thumbColor);
        }

        // Render player paperdoll inside Creative Survival Inventory tab.
        if (activeTab == CreativeTabs.tabInventory) {
            RenderUtil.drawRoundedRect(
                guiLeft + 27.0f,
                guiTop + 6.0f,
                32.0f,
                43.0f,
                theme.radiusControl(),
                RenderUtil.withAlpha(theme.surface1(), frosted ? 0.45f : 0.90f),
                theme.border(),
                1.0f
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
        GuiContainer screen,
        GuiContainerCreative creative,
        float guiLeft,
        float guiTop,
        float xSize,
        float ySize,
        ThemeData theme,
        boolean frosted,
        boolean isLight,
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
            drawSingleCreativeTab(tab, isSelected, guiLeft, guiTop, xSize, ySize, theme, frosted, isLight);
        }

        if (tabPage != 0) {
            CreativeTabs searchTab = CreativeTabs.tabAllSearch;
            CreativeTabs invTab = CreativeTabs.tabInventory;
            if ((searchTab.getTabIndex() == selectedIdx) == drawActiveOnly) {
                drawSingleCreativeTab(searchTab, searchTab.getTabIndex() == selectedIdx, guiLeft, guiTop, xSize, ySize, theme, frosted, isLight);
            }
            if ((invTab.getTabIndex() == selectedIdx) == drawActiveOnly) {
                drawSingleCreativeTab(invTab, invTab.getTabIndex() == selectedIdx, guiLeft, guiTop, xSize, ySize, theme, frosted, isLight);
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
        boolean frosted,
        boolean isLight
    ) {
        int col = tab.getTabColumn();
        boolean topRow = tab.isTabInFirstRow();
        float tabX = guiLeft + (col == 5 ? (xSize - 26.0f) : (28.0f * col + (col > 0 ? col : 0)));
        float tabW = 26.0f;
        float tabH = 24.0f;
        float tabY = topRow ? (guiTop - OUTER_PAD - tabH - 2.0f) : (guiTop + ySize + OUTER_PAD + 2.0f);

        int bg = selected
            ? RenderUtil.withAlpha(theme.surface2(), frosted ? (isLight ? 0.82f : 0.78f) : 1.0f)
            : RenderUtil.withAlpha(theme.surface1(), frosted ? (isLight ? 0.56f : 0.48f) : 0.88f);
        int border = selected ? theme.accent() : theme.border();
        RenderUtil.drawRoundedRect(tabX, tabY, tabW, tabH, theme.radiusControl(), bg, border, 1.0f);

        if (selected) {
            float barW = 12.0f;
            float barX = tabX + (tabW - barW) * 0.5f;
            float barY = topRow ? (tabY + tabH - 2.0f) : tabY;
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
                itemRenderer.renderItemAndEffectIntoGUI(iconStack, Math.round(tabX + 5.0f), Math.round(tabY + 4.0f));
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
