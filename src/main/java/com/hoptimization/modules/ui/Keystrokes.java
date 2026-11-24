package com.hoptimization.modules.ui;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.config.ConfigManager;
import com.hoptimization.core.config.UiConfig;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.ColorAnimation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.ClickCpsTracker;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.ThemeMode;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.function.Supplier;

/**
 * Keystrokes HUD module displaying {@code W / A / S / D}, optional {@code LMB / RMB} with live CPS,
 * and optional {@code SPACE} bar with smooth theme-token press transitions.
 */
public class Keystrokes extends Module implements HudElement {
    private static final float KEY_SIZE = 28.0f;
    private static final float KEY_GAP = UiTokens.SPACE_4;
    public static final float TOTAL_WIDTH = KEY_SIZE * 3.0f + KEY_GAP * 2.0f;
    private static final float MOUSE_WIDTH = (TOTAL_WIDTH - KEY_GAP) * 0.5f;
    private static final float MOUSE_HEIGHT_PLAIN = 24.0f;
    private static final float MOUSE_HEIGHT_CPS = 32.0f;
    private static final float SPACE_HEIGHT = 20.0f;

    private static final String LABEL_LMB = "LMB";
    private static final String LABEL_RMB = "RMB";
    private static final String LABEL_SPACE = "SPACE";

    private final BooleanSetting showMouse;
    private final BooleanSetting showSpace;
    private final BooleanSetting showCps;

    private final Animation animW = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation animA = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation animS = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation animD = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation animLmb = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation animRmb = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);
    private final Animation animSpace = new Animation(0.0f, AnimDuration.FAST, Easing.EASE_OUT_CUBIC);

    private final StringBuilder cpsScratch = new StringBuilder(12);

    private int cachedCodeW = Integer.MIN_VALUE;
    private int cachedCodeA = Integer.MIN_VALUE;
    private int cachedCodeS = Integer.MIN_VALUE;
    private int cachedCodeD = Integer.MIN_VALUE;
    private String labelW = "W";
    private String labelA = "A";
    private String labelS = "S";
    private String labelD = "D";

    private boolean cpsAcquired = false;

    /**
     * Creates the Keystrokes module, registers its settings, and attaches it to {@link HudOverlayRenderer}.
     */
    public Keystrokes() {
        super("keystrokes", Category.UI);
        this.showMouse = addSetting(
            new BooleanSetting("show_mouse", "setting.hoptimization.keystrokes.show_mouse.name", true)
        );
        this.showSpace = addSetting(
            new BooleanSetting("show_space", "setting.hoptimization.keystrokes.show_space.name", true)
        );
        this.showCps = addSetting(
            new BooleanSetting("show_cps", "setting.hoptimization.keystrokes.show_cps.name", false)
                .visibleWhen(new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return showMouse.isEnabled();
                    }
                })
        );

        Runnable cpsSyncListener = new Runnable() {
            @Override
            public void run() {
                syncCpsSubscription();
            }
        };
        this.showMouse.addChangeListener(cpsSyncListener);
        this.showCps.addChangeListener(cpsSyncListener);

        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.ensureRegistered();
        ClickCpsTracker.ensureRegistered();
    }

    /**
     * Returns the mouse button boxes toggle setting.
     *
     * @return show mouse setting
     */
    public BooleanSetting getShowMouseSetting() {
        return this.showMouse;
    }

    /**
     * Returns the spacebar box toggle setting.
     *
     * @return show space setting
     */
    public BooleanSetting getShowSpaceSetting() {
        return this.showSpace;
    }

    /**
     * Returns the LMB/RMB CPS sub-label toggle setting.
     *
     * @return show CPS setting
     */
    public BooleanSetting getShowCpsSetting() {
        return this.showCps;
    }

    @Override
    protected void onEnable() {
        syncCpsSubscription();
    }

    @Override
    protected void onDisable() {
        syncCpsSubscription();
        this.animW.snapTo(0.0f);
        this.animA.snapTo(0.0f);
        this.animS.snapTo(0.0f);
        this.animD.snapTo(0.0f);
        this.animLmb.snapTo(0.0f);
        this.animRmb.snapTo(0.0f);
        this.animSpace.snapTo(0.0f);
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

    public static float getAlignedWidthFor(String hudId) {
        ConfigManager cm = HoptimizationMod.getConfigManager();
        UiConfig ui = cm != null ? cm.getUiConfig() : null;
        if (ui == null) {
            return TOTAL_WIDTH;
        }
        UiConfig.HudLayout ksLayout = ui.getHudLayout("keystrokes");
        float ksScale = ksLayout != null ? HudOverlayRenderer.clampElementScale(ksLayout.getScale()) : 1.0f;
        UiConfig.HudLayout myLayout = hudId != null ? ui.getHudLayout(hudId) : null;
        float myScale = myLayout != null ? HudOverlayRenderer.clampElementScale(myLayout.getScale()) : 1.0f;
        return TOTAL_WIDTH * (ksScale / myScale);
    }

    @Override
    public float getHudWidth() {
        return TOTAL_WIDTH;
    }

    @Override
    public float getHudHeight() {
        float height = KEY_SIZE * 2.0f + KEY_GAP;
        if (this.showMouse.isEnabled()) {
            height += KEY_GAP + (this.showCps.isEnabled() ? MOUSE_HEIGHT_CPS : MOUSE_HEIGHT_PLAIN);
        }
        if (this.showSpace.isEnabled()) {
            height += KEY_GAP + SPACE_HEIGHT;
        }
        return height;
    }

    @Override
    public float getDefaultHudX(float screenWidth, float screenHeight) {
        return Math.max(UiTokens.HUD_SCREEN_MARGIN, screenWidth - getHudWidth() - UiTokens.HUD_SCREEN_MARGIN);
    }

    @Override
    public float getDefaultHudY(float screenWidth, float screenHeight) {
        return UiTokens.HUD_SCREEN_MARGIN;
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        Minecraft mc = Minecraft.getMinecraft();
        GameSettings gs = mc != null ? mc.gameSettings : null;
        boolean inWorldActive = !editMode && mc != null && mc.currentScreen == null && gs != null;

        refreshMovementLabels(gs);

        boolean pressW = inWorldActive && isKeyBindingPressed(gs.keyBindForward);
        boolean pressA = inWorldActive && isKeyBindingPressed(gs.keyBindLeft);
        boolean pressS = inWorldActive && isKeyBindingPressed(gs.keyBindBack);
        boolean pressD = inWorldActive && isKeyBindingPressed(gs.keyBindRight);
        boolean pressLmb = inWorldActive && isKeyBindingPressed(gs.keyBindAttack);
        boolean pressRmb = inWorldActive && isKeyBindingPressed(gs.keyBindUseItem);
        boolean pressSpace = inWorldActive && isKeyBindingPressed(gs.keyBindJump);

        TtfFontRenderer keyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        TtfFontRenderer overlineFont = FontManager.overline();

        // Row 1: W
        drawKeyBox(
            x + KEY_SIZE + KEY_GAP, y, KEY_SIZE, KEY_SIZE,
            this.labelW, null, pressW, this.animW, keyFont, overlineFont, theme
        );

        // Row 2: A, S, D
        float row2Y = y + KEY_SIZE + KEY_GAP;
        drawKeyBox(x, row2Y, KEY_SIZE, KEY_SIZE, this.labelA, null, pressA, this.animA, keyFont, overlineFont, theme);
        drawKeyBox(
            x + KEY_SIZE + KEY_GAP, row2Y, KEY_SIZE, KEY_SIZE,
            this.labelS, null, pressS, this.animS, keyFont, overlineFont, theme
        );
        drawKeyBox(
            x + (KEY_SIZE + KEY_GAP) * 2.0f, row2Y, KEY_SIZE, KEY_SIZE,
            this.labelD, null, pressD, this.animD, keyFont, overlineFont, theme
        );

        float nextY = row2Y + KEY_SIZE + KEY_GAP;

        // Row 3: LMB / RMB
        if (this.showMouse.isEnabled()) {
            boolean displayCps = this.showCps.isEnabled();
            float mouseH = displayCps ? MOUSE_HEIGHT_CPS : MOUSE_HEIGHT_PLAIN;
            String leftSub = null;
            String rightSub = null;
            if (displayCps) {
                int lCps = ClickCpsTracker.getInstance().getLeftCps();
                int rCps = ClickCpsTracker.getInstance().getRightCps();
                if (editMode && lCps == 0 && rCps == 0 && (mc == null || mc.thePlayer == null)) {
                    lCps = 8;
                    rCps = 4;
                }
                leftSub = HudRenderHelper.formatCpsLabel(lCps, this.cpsScratch);
                rightSub = HudRenderHelper.formatCpsLabel(rCps, this.cpsScratch);
            }
            drawKeyBox(
                x, nextY, MOUSE_WIDTH, mouseH,
                LABEL_LMB, leftSub, pressLmb, this.animLmb, captionFont, overlineFont, theme
            );
            drawKeyBox(
                x + MOUSE_WIDTH + KEY_GAP, nextY, MOUSE_WIDTH, mouseH,
                LABEL_RMB, rightSub, pressRmb, this.animRmb, captionFont, overlineFont, theme
            );
            nextY += mouseH + KEY_GAP;
        }

        // Row 4: SPACE
        if (this.showSpace.isEnabled()) {
            drawKeyBox(
                x, nextY, TOTAL_WIDTH, SPACE_HEIGHT,
                LABEL_SPACE, null, pressSpace, this.animSpace, captionFont, overlineFont, theme
            );
        }
    }

    private void drawKeyBox(
        float x,
        float y,
        float width,
        float height,
        String label,
        String subLabel,
        boolean pressed,
        Animation anim,
        TtfFontRenderer primaryFont,
        TtfFontRenderer subFont,
        ThemeData theme
    ) {
        anim.setTarget(pressed ? 1.0f : 0.0f);
        float t = anim.get();
        float radius = theme.radiusControl();
        boolean isLight = theme.getMode() == ThemeMode.LIGHT;
        boolean frosted = ThemeManager.isFrostedGlass();

        int pressedBorder = RenderUtil.withAlpha(theme.accent(), isLight ? 0.78f : 0.85f);
        if (frosted) {
            int baseGlassTint = theme.frostedHudTint();
            int pressedGlassRgb = ColorAnimation.lerpArgb(theme.surface0(), theme.accent(), isLight ? 0.24f : 0.30f);
            int pressedGlassTint = RenderUtil.withAlpha(pressedGlassRgb, isLight ? 0.62f : 0.54f);
            int glassTint = ColorAnimation.lerpArgb(baseGlassTint, pressedGlassTint, t);
            int glassBorder = ColorAnimation.lerpArgb(theme.frostedBorder(), pressedBorder, t);
            float highlight = theme.frostedHighlightStrength() + 0.06f * t;
            RenderUtil.drawFrostedRoundedRect(
                x, y, width, height,
                radius,
                glassTint,
                glassBorder,
                UiTokens.BORDER_WIDTH,
                highlight,
                1.0f
            );
        } else {
            boolean hasCardBorder = theme.style().isCardBorder();
            int pressedSoftBg = ColorAnimation.lerpArgb(theme.hudBackground(), theme.accent(), isLight ? 0.22f : 0.28f);
            int bg = ColorAnimation.lerpArgb(theme.hudBackground(), pressedSoftBg, t);
            int unpressedBorder = hasCardBorder ? theme.border() : RenderUtil.withAlpha(theme.accent(), 0.0f);
            int border = ColorAnimation.lerpArgb(unpressedBorder, pressedBorder, t);
            float borderWidth = (hasCardBorder || t > 0.01f) ? UiTokens.BORDER_WIDTH : 0.0f;
            RenderUtil.drawRoundedRect(
                x, y, width, height,
                radius,
                bg,
                border,
                borderWidth
            );
        }

        int pressedFg = ColorAnimation.lerpArgb(theme.textPrimary(), theme.accent(), isLight ? 0.68f : 0.36f);
        int fg = ColorAnimation.lerpArgb(theme.textPrimary(), pressedFg, t);

        float centerX = x + width * 0.5f;
        if (subLabel == null || subLabel.isEmpty()) {
            float textY = y + (height - primaryFont.getHeight()) * 0.5f;
            primaryFont.drawStringCentered(label, centerX, textY, fg);
        } else {
            float totalTextH = primaryFont.getHeight() + UiTokens.SPACE_2 + subFont.getHeight();
            float startY = y + (height - totalTextH) * 0.5f;
            primaryFont.drawStringCentered(label, centerX, startY, fg);
            int subFg = RenderUtil.withAlpha(fg, 0.85f);
            subFont.drawStringCentered(subLabel, centerX, startY + primaryFont.getHeight() + UiTokens.SPACE_2, subFg);
        }
    }

    private void refreshMovementLabels(GameSettings gs) {
        if (gs == null) {
            return;
        }
        int wCode = gs.keyBindForward.getKeyCode();
        if (wCode != this.cachedCodeW) {
            this.cachedCodeW = wCode;
            this.labelW = resolveKeyLabel(gs.keyBindForward, "W");
        }
        int aCode = gs.keyBindLeft.getKeyCode();
        if (aCode != this.cachedCodeA) {
            this.cachedCodeA = aCode;
            this.labelA = resolveKeyLabel(gs.keyBindLeft, "A");
        }
        int sCode = gs.keyBindBack.getKeyCode();
        if (sCode != this.cachedCodeS) {
            this.cachedCodeS = sCode;
            this.labelS = resolveKeyLabel(gs.keyBindBack, "S");
        }
        int dCode = gs.keyBindRight.getKeyCode();
        if (dCode != this.cachedCodeD) {
            this.cachedCodeD = dCode;
            this.labelD = resolveKeyLabel(gs.keyBindRight, "D");
        }
    }

    private static String resolveKeyLabel(KeyBinding binding, String fallback) {
        if (binding == null) {
            return fallback;
        }
        int code = binding.getKeyCode();
        if (code <= 0) {
            return fallback;
        }
        String keyName = Keyboard.getKeyName(code);
        if (keyName == null || keyName.isEmpty() || keyName.length() > 3) {
            return fallback;
        }
        return keyName;
    }

    private static boolean isKeyBindingPressed(KeyBinding binding) {
        if (binding == null) {
            return false;
        }
        if (binding.isKeyDown()) {
            return true;
        }
        int code = binding.getKeyCode();
        if (code < 0) {
            int btn = code + 100;
            return btn >= 0 && Mouse.isCreated() && Mouse.isButtonDown(btn);
        }
        return code > 0 && Keyboard.isCreated() && Keyboard.isKeyDown(code);
    }

    private void syncCpsSubscription() {
        boolean needCps = isEnabled() && this.showMouse.isEnabled() && this.showCps.isEnabled();
        if (needCps && !this.cpsAcquired) {
            ClickCpsTracker.getInstance().acquire();
            this.cpsAcquired = true;
        } else if (!needCps && this.cpsAcquired) {
            ClickCpsTracker.getInstance().release();
            this.cpsAcquired = false;
        }
    }
}
