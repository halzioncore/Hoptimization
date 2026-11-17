package com.hoptimization.ui.hud;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight HUD overlay dispatcher rendering enabled {@link HudElement} modules during
 * {@link RenderGameOverlayEvent.Text} using independent {@link UiScale#getScale()} scaling.
 */
public final class HudOverlayRenderer {
    public static final float MIN_ELEMENT_SCALE = 0.5f;
    public static final float MAX_ELEMENT_SCALE = 2.0f;

    private static final HudOverlayRenderer INSTANCE = new HudOverlayRenderer();

    private final List<HudElement> elements = new ArrayList<HudElement>(8);
    private final List<HudElement> elementsView = Collections.unmodifiableList(this.elements);
    private final Map<String, HudElement> byId = new LinkedHashMap<String, HudElement>(8);
    private final float[] layoutBoundsScratch = new float[5];

    private boolean busRegistered = false;

    private HudOverlayRenderer() {
    }

    /**
     * Returns the global {@link HudOverlayRenderer} singleton.
     *
     * @return overlay renderer instance
     */
    public static HudOverlayRenderer getInstance() {
        return INSTANCE;
    }

    /**
     * Idempotently registers this overlay renderer onto {@link MinecraftForge#EVENT_BUS}.
     */
    public static void ensureRegistered() {
        INSTANCE.registerEventBusIfNeeded();
    }

    /**
     * Registers or replaces a {@link HudElement} by its stable {@link HudElement#getHudId()}.
     *
     * @param element HUD element to register
     */
    public static void registerElement(HudElement element) {
        INSTANCE.registerElementInternal(element);
    }

    /**
     * Returns all registered HUD elements in display order.
     *
     * @return unmodifiable list of registered HUD elements
     */
    public static List<HudElement> getRegisteredElements() {
        INSTANCE.syncFromModuleManager();
        return INSTANCE.elementsView;
    }

    /**
     * Opens the interactive HUD layout editor screen ({@link HudEditScreen}).
     */
    public static void openEditor() {
        openEditor(null);
    }

    /**
     * Opens the interactive HUD layout editor screen with an optional parent screen to return to on exit.
     *
     * @param parentScreen optional screen to reopen when closing the HUD editor, or {@code null}
     */
    public static void openEditor(GuiScreen parentScreen) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        ensureRegistered();
        mc.displayGuiScreen(new HudEditScreen(parentScreen));
    }

    /**
     * Resolves an element's clamped logical bounds {@code [x, y, scaledWidth, scaledHeight, elementScale]}
     * from {@link UiConfig} or falls back cleanly to its default layout when {@code getHudLayout(id)} is null.
     *
     * @param element   target HUD element
     * @param uiConfig  UI configuration (may be {@code null})
     * @param logicalW  logical screen width at {@link UiScale#getScale()}
     * @param logicalH  logical screen height at {@link UiScale#getScale()}
     * @param outBounds pre-allocated {@code float[5]} receiving {@code [x, y, scaledW, scaledH, scale]}
     */
    public static void resolveElementBounds(
        HudElement element,
        UiConfig uiConfig,
        float logicalW,
        float logicalH,
        float[] outBounds
    ) {
        String id = element.getHudId();
        UiConfig.HudLayout saved = uiConfig != null ? uiConfig.getHudLayout(id) : null;
        float elemScale = saved != null ? clampElementScale(saved.getScale()) : 1.0f;
        float baseW = Math.max(8.0f, element.getHudWidth());
        float baseH = Math.max(8.0f, element.getHudHeight());
        float scaledW = baseW * elemScale;
        float scaledH = baseH * elemScale;

        float x = saved != null ? saved.getX() : element.getDefaultHudX(logicalW, logicalH);
        float y = saved != null ? saved.getY() : element.getDefaultHudY(logicalW, logicalH);

        float maxX = Math.max(0.0f, logicalW - scaledW);
        float maxY = Math.max(0.0f, logicalH - scaledH);
        if (!Float.isFinite(x) || x < 0.0f) {
            x = 0.0f;
        } else if (x > maxX) {
            x = maxX;
        }
        if (!Float.isFinite(y) || y < 0.0f) {
            y = 0.0f;
        } else if (y > maxY) {
            y = maxY;
        }

        outBounds[0] = x;
        outBounds[1] = y;
        outBounds[2] = scaledW;
        outBounds[3] = scaledH;
        outBounds[4] = elemScale;
    }

    /**
     * Clamps an individual HUD element scale multiplier to [{@link #MIN_ELEMENT_SCALE}, {@link #MAX_ELEMENT_SCALE}].
     *
     * @param scale candidate scale factor
     * @return clamped finite scale factor
     */
    public static float clampElementScale(float scale) {
        if (!Float.isFinite(scale) || scale <= 0.0f) {
            return 1.0f;
        }
        if (scale < MIN_ELEMENT_SCALE) {
            return MIN_ELEMENT_SCALE;
        }
        if (scale > MAX_ELEMENT_SCALE) {
            return MAX_ELEMENT_SCALE;
        }
        return scale;
    }

    @SubscribeEvent
    public void onRenderOverlayText(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.gameSettings.showDebugInfo || mc.gameSettings.hideGUI) {
            return;
        }
        if (mc.currentScreen instanceof HudEditScreen) {
            return;
        }

        syncFromModuleManager();
        if (!hasAnyEnabledElement()) {
            return;
        }

        ConfigManager configManager = HoptimizationMod.getConfigManager();
        UiConfig uiConfig = configManager != null ? configManager.getUiConfig() : null;
        if (uiConfig != null) {
            UiScale.setUserMultiplier(uiConfig.getUiScaleMultiplier());
            Animation.setReduceMotion(uiConfig.isReduceMotion());
            ThemeManager.getInstance().setReduceMotion(uiConfig.isReduceMotion());
        }

        float hudScale = UiScale.getScale();
        ScaledResolution sr = event.resolution != null ? event.resolution : new ScaledResolution(mc);
        int srFactor = Math.max(1, sr.getScaleFactor());
        float scaleRatio = hudScale / (float) srFactor;
        float logicalW = UiScale.getLogicalWidth(hudScale);
        float logicalH = UiScale.getLogicalHeight(hudScale);
        ThemeData theme = ThemeManager.current();

        boolean preserveScreenFontScale = mc.currentScreen != null;
        float prevFontScale = FontManager.getUiScale();

        HudRenderHelper.GlStateSnapshot glSnapshot = HudRenderHelper.acquireSnapshot();
        glSnapshot.capture();
        GlStateManager.pushMatrix();
        try {
            FontManager.setUiScale(hudScale);
            GlStateManager.scale(scaleRatio, scaleRatio, 1.0f);

            for (int i = 0; i < this.elements.size(); i++) {
                HudElement element = this.elements.get(i);
                if (!element.isHudEnabled()) {
                    continue;
                }
                resolveElementBounds(element, uiConfig, logicalW, logicalH, this.layoutBoundsScratch);
                float x = this.layoutBoundsScratch[0];
                float y = this.layoutBoundsScratch[1];
                float elemScale = this.layoutBoundsScratch[4];

                if (Math.abs(elemScale - 1.0f) > 0.001f) {
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(x, y, 0.0f);
                    GlStateManager.scale(elemScale, elemScale, 1.0f);
                    FontManager.setUiScale(hudScale * elemScale);
                    try {
                        element.renderHud(0.0f, 0.0f, event.partialTicks, theme, false);
                    } finally {
                        GlStateManager.popMatrix();
                        FontManager.setUiScale(hudScale);
                    }
                } else {
                    element.renderHud(x, y, event.partialTicks, theme, false);
                }
            }
        } finally {
            ScissorStack.clear();
            GlStateManager.popMatrix();
            FontManager.setUiScale(preserveScreenFontScale ? prevFontScale : hudScale);
            glSnapshot.restore();
            HudRenderHelper.releaseSnapshot();
        }
    }

    private synchronized void registerEventBusIfNeeded() {
        if (this.busRegistered || Minecraft.getMinecraft() == null) {
            return;
        }
        try {
            MinecraftForge.EVENT_BUS.register(this);
            this.busRegistered = true;
        } catch (RuntimeException e) {
            HoptimizationMod.LOGGER.error("Failed to register HudOverlayRenderer on Forge EVENT_BUS", e);
        }
    }

    private void registerElementInternal(HudElement element) {
        if (element == null || element.getHudId() == null) {
            return;
        }
        String id = element.getHudId();
        HudElement existing = this.byId.get(id);
        if (existing == element) {
            return;
        }
        if (existing != null) {
            for (int i = 0; i < this.elements.size(); i++) {
                if (this.elements.get(i).getHudId().equals(id)) {
                    this.elements.set(i, element);
                    break;
                }
            }
        } else {
            this.elements.add(element);
        }
        this.byId.put(id, element);
    }

    private void syncFromModuleManager() {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        if (manager == null) {
            return;
        }
        List<Module> modules = manager.getModules();
        for (int i = 0; i < modules.size(); i++) {
            Module mod = modules.get(i);
            if (mod instanceof HudElement) {
                HudElement elem = (HudElement) mod;
                if (!this.byId.containsKey(elem.getHudId())) {
                    registerElementInternal(elem);
                }
            }
        }
    }

    private boolean hasAnyEnabledElement() {
        for (int i = 0; i < this.elements.size(); i++) {
            if (this.elements.get(i).isHudEnabled()) {
                return true;
            }
        }
        return false;
    }
}
