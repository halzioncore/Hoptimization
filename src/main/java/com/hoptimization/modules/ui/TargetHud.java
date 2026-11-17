package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.clickgui.ClickGuiIcons;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Target HUD module displaying attacked or crosshair-targeted entity health, animated health bar,
 * optional numeric health, and optional armor equipment/protection info.
 */
public class TargetHud extends Module implements HudElement {
    private static final float HUD_WIDTH = 176.0f;
    private static final float HUD_HEIGHT = 52.0f;
    private static final float AVATAR_SIZE = 32.0f;
    private static final float BAR_HEIGHT = 6.0f;
    private static final float ARMOR_ICON_SCALE = 0.75f;
    private static final float ARMOR_ICON_STEP = 14.0f;
    private static final double MAX_TARGET_DISTANCE_SQ = 64.0D * 64.0D;
    private static final long LINGER_NANOS = 3_000_000_000L;
    private static final long ATTACK_PRIORITY_NANOS = 600_000_000L;

    private static final String DUMMY_FALLBACK_NAME = "Player";

    private final BooleanSetting showArmor;
    private final BooleanSetting showHealthNumber;
    private final BooleanSetting animate;
    private final ModeSetting style;

    private final Animation fadeAnim = new Animation(0.0f, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);
    private final Animation healthAnim = new Animation(1.0f, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);

    private final ItemStack[] snapshotArmor = new ItemStack[4];
    private final ItemStack[] dummyArmor = new ItemStack[4];
    private boolean dummyArmorInitialized = false;

    private final StringBuilder healthScratch = new StringBuilder(12);
    private int lastHealthTenths = -1;
    private String cachedHealthText = "20.0";

    private final StringBuilder armorTextScratch = new StringBuilder(8);
    private int lastArmorValue = -1;
    private String cachedArmorText = "0";

    private EntityLivingBase trackedTarget = null;
    private long lastAttackNanos = 0L;
    private long lastSeenNanos = 0L;

    private boolean hasSnapshot = false;
    private String snapshotName = DUMMY_FALLBACK_NAME;
    private float snapshotHealth = 20.0f;
    private float snapshotMaxHealth = 20.0f;
    private int snapshotArmorValue = 0;
    private ResourceLocation snapshotSkin = null;

    /**
     * Creates the Target HUD module, registers its settings, and attaches it to {@link HudOverlayRenderer}.
     */
    public TargetHud() {
        super("target_hud", Category.UI);
        this.showArmor = addSetting(
            new BooleanSetting("show_armor", "setting.hoptimization.target_hud.show_armor.name", true)
        );
        this.showHealthNumber = addSetting(
            new BooleanSetting("show_health_number", "setting.hoptimization.target_hud.show_health_number.name", true)
        );
        this.animate = addSetting(
            new BooleanSetting("animate", "setting.hoptimization.target_hud.animate.name", true)
        );
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.target_hud.style.name", "card", "card", "text")
        );
        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.ensureRegistered();
    }

    /**
     * Returns the armor display toggle setting.
     *
     * @return show armor setting
     */
    public BooleanSetting getShowArmorSetting() {
        return this.showArmor;
    }

    /**
     * Returns the numeric health display toggle setting.
     *
     * @return show health number setting
     */
    public BooleanSetting getShowHealthNumberSetting() {
        return this.showHealthNumber;
    }

    /**
     * Returns the health bar animation toggle setting.
     *
     * @return animation setting
     */
    public BooleanSetting getAnimateSetting() {
        return this.animate;
    }

    /**
     * Returns the HUD rendering style setting ({@code card} or {@code text}).
     *
     * @return style setting
     */
    public ModeSetting getStyleSetting() {
        return this.style;
    }

    @Override
    protected void onDisable() {
        clearTrackingState();
        this.fadeAnim.snapTo(0.0f);
        this.healthAnim.snapTo(1.0f);
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || event.entityPlayer != mc.thePlayer) {
            return;
        }
        if (isValidTarget(event.target, mc)) {
            long now = System.nanoTime();
            this.trackedTarget = (EntityLivingBase) event.target;
            this.lastAttackNanos = now;
            this.lastSeenNanos = now;
            captureSnapshot(this.trackedTarget);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!isEnabled() || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            clearTrackingState();
            return;
        }

        long now = System.nanoTime();

        // Check crosshair target for seamless acquisition before/between hits.
        if (mc.objectMouseOver != null && isValidTarget(mc.objectMouseOver.entityHit, mc)) {
            EntityLivingBase hovered = (EntityLivingBase) mc.objectMouseOver.entityHit;
            boolean canSwitch = this.trackedTarget == null
                || this.trackedTarget == hovered
                || !this.trackedTarget.isEntityAlive()
                || (now - this.lastAttackNanos) > ATTACK_PRIORITY_NANOS;
            if (canSwitch) {
                this.trackedTarget = hovered;
                this.lastSeenNanos = now;
            }
        }

        if (this.trackedTarget != null) {
            if (this.trackedTarget.worldObj != mc.theWorld
                || mc.thePlayer.getDistanceSqToEntity(this.trackedTarget) > MAX_TARGET_DISTANCE_SQ) {
                this.trackedTarget = null;
            } else if (!this.trackedTarget.isEntityAlive() || this.trackedTarget.getHealth() <= 0.0f) {
                this.snapshotHealth = 0.0f;
                this.trackedTarget = null;
            } else if ((now - this.lastSeenNanos) > LINGER_NANOS) {
                this.trackedTarget = null;
            } else {
                captureSnapshot(this.trackedTarget);
            }
        }
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
        return HUD_WIDTH;
    }

    @Override
    public float getHudHeight() {
        return HUD_HEIGHT;
    }

    @Override
    public float getDefaultHudX(float screenWidth, float screenHeight) {
        return Math.min(screenWidth - HUD_WIDTH - UiTokens.HUD_SCREEN_MARGIN, screenWidth * 0.5f + 20.0f);
    }

    @Override
    public float getDefaultHudY(float screenWidth, float screenHeight) {
        return Math.min(screenHeight - HUD_HEIGHT - UiTokens.HUD_SCREEN_MARGIN, screenHeight * 0.5f + 16.0f);
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        long now = System.nanoTime();
        boolean useAnimate = this.animate.isEnabled();
        boolean activeLive = this.trackedTarget != null
            && this.trackedTarget.isEntityAlive()
            && this.trackedTarget.getHealth() > 0.0f
            && (now - this.lastSeenNanos) <= LINGER_NANOS;

        if (activeLive) {
            captureSnapshot(this.trackedTarget);
        }

        String displayName;
        float health;
        float maxHealth;
        int armorValue;
        ResourceLocation skin;
        ItemStack[] armorItems;
        float alpha;

        if (editMode && !activeLive) {
            // Render a crisp, realistic preview inside HudEditScreen when no combat target is active.
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                displayName = mc.thePlayer.getName();
                skin = mc.thePlayer.getLocationSkin();
                armorItems = hasAnyArmor(mc.thePlayer) ? extractPlayerArmor(mc.thePlayer) : getDummyArmor();
                armorValue = Math.max(16, mc.thePlayer.getTotalArmorValue());
            } else {
                displayName = DUMMY_FALLBACK_NAME;
                skin = null;
                armorItems = getDummyArmor();
                armorValue = 16;
            }
            health = 15.0f;
            maxHealth = 20.0f;
            alpha = 1.0f;
            this.healthAnim.snapTo(health / maxHealth);
        } else {
            if (!this.hasSnapshot) {
                return;
            }
            if (useAnimate) {
                if (activeLive) {
                    this.fadeAnim.setTarget(1.0f, AnimDuration.NORMAL.getMillis());
                } else {
                    this.fadeAnim.setTarget(0.0f, AnimDuration.NORMAL.getCloseMillis());
                }
                alpha = this.fadeAnim.get(now);
            } else {
                alpha = activeLive ? 1.0f : 0.0f;
                this.fadeAnim.snapTo(alpha);
            }

            if (alpha <= 0.01f) {
                if (!activeLive) {
                    this.hasSnapshot = false;
                }
                return;
            }

            displayName = this.snapshotName;
            health = this.snapshotHealth;
            maxHealth = this.snapshotMaxHealth;
            armorValue = this.snapshotArmorValue;
            skin = this.snapshotSkin;
            armorItems = this.snapshotArmor;
        }

        float targetRatio = maxHealth > 0.0f ? clamp01(health / maxHealth) : 0.0f;
        float animatedRatio;
        if (useAnimate && !editMode) {
            this.healthAnim.setTarget(targetRatio);
            animatedRatio = clamp01(this.healthAnim.get(now));
        } else {
            this.healthAnim.snapTo(targetRatio);
            animatedRatio = targetRatio;
        }

        boolean cardMode = !this.style.is("text");
        if (cardMode) {
            HudRenderHelper.drawHudCard(x, y, HUD_WIDTH, HUD_HEIGHT, theme, alpha);
        }

        float padH = UiTokens.HUD_PADDING_H;
        float avatarX = x + padH;
        float avatarY = y + (HUD_HEIGHT - AVATAR_SIZE) * 0.5f;

        renderAvatar(avatarX, avatarY, skin, theme, alpha);

        float contentX = avatarX + AVATAR_SIZE + UiTokens.SPACE_8;
        float contentRight = x + HUD_WIDTH - padH;
        float contentW = Math.max(24.0f, contentRight - contentX);

        TtfFontRenderer nameFont = FontManager.body();
        TtfFontRenderer metaFont = FontManager.caption();
        boolean textShadow = !cardMode;

        // Top row: Target name + optional numeric health
        float nameY = y + UiTokens.HUD_PADDING_V;
        float nameMaxW = contentW;
        if (this.showHealthNumber.isEnabled()) {
            String hpText = getFormattedHealth(health);
            float hpW = metaFont.getWidth(hpText);
            nameMaxW = Math.max(24.0f, contentW - hpW - UiTokens.SPACE_6);
            int hpColor = RenderUtil.withAlpha(theme.textSecondary(), alpha);
            float hpY = nameY + (nameFont.getHeight() - metaFont.getHeight()) * 0.5f;
            HudRenderHelper.drawHudTextRight(metaFont, hpText, contentRight, hpY, hpColor, textShadow);
        }

        int nameColor = RenderUtil.withAlpha(theme.textPrimary(), alpha);
        nameFont.drawStringWithEllipsis(displayName, contentX, nameY, nameMaxW, nameColor, textShadow);

        // Middle row (armor) + Bottom row (health bar)
        float barY = y + HUD_HEIGHT - UiTokens.HUD_PADDING_V - BAR_HEIGHT;
        if (this.showArmor.isEnabled()) {
            float armorY = nameY + nameFont.getHeight() + UiTokens.SPACE_2;
            boolean drewIcons = false;
            if (alpha >= 0.35f) {
                float iconX = contentX;
                for (int i = 0; i < 4; i++) {
                    ItemStack stack = armorItems[i];
                    if (stack != null && stack.getItem() != null) {
                        HudRenderHelper.drawItemStack(stack, iconX, armorY, ARMOR_ICON_SCALE);
                        iconX += ARMOR_ICON_STEP;
                        drewIcons = true;
                    }
                }
            }
            if (!drewIcons && armorValue > 0) {
                String armorStr = getFormattedArmor(armorValue);
                HudRenderHelper.drawHudText(
                    metaFont,
                    armorStr,
                    contentX,
                    armorY,
                    RenderUtil.withAlpha(theme.textSecondary(), alpha),
                    textShadow
                );
            }
        } else {
            // Center the health bar neatly in remaining space when armor row is hidden.
            barY = y + HUD_HEIGHT - UiTokens.SPACE_12 - BAR_HEIGHT;
        }

        float barRadius = theme.radiusPill(BAR_HEIGHT);
        int trackColor = RenderUtil.withAlpha(theme.surface3(), alpha);
        RenderUtil.drawRoundedRect(contentX, barY, contentW, BAR_HEIGHT, barRadius, trackColor);

        float fillW = contentW * animatedRatio;
        if (fillW > 0.5f) {
            int fillColor = RenderUtil.withAlpha(theme.accent(), alpha);
            RenderUtil.drawRoundedRect(contentX, barY, fillW, BAR_HEIGHT, barRadius, fillColor);
        }
    }

    private void renderAvatar(
        float x,
        float y,
        ResourceLocation skin,
        ThemeData theme,
        float alpha
    ) {
        if (skin != null) {
            RenderUtil.drawRoundedRect(
                x - 1.0f, y - 1.0f, AVATAR_SIZE + 2.0f, AVATAR_SIZE + 2.0f,
                theme.radiusControl(),
                0,
                RenderUtil.withAlpha(theme.border(), alpha),
                UiTokens.BORDER_WIDTH
            );
            HudRenderHelper.drawPlayerHead(skin, x, y, AVATAR_SIZE, alpha);
        } else {
            RenderUtil.drawRoundedRect(
                x, y, AVATAR_SIZE, AVATAR_SIZE,
                theme.radiusControl(),
                RenderUtil.withAlpha(theme.surface2(), alpha),
                RenderUtil.withAlpha(theme.border(), alpha),
                UiTokens.BORDER_WIDTH
            );
            float iconSize = UiTokens.SPACE_16;
            RenderUtil.drawTintedIcon(
                ClickGuiIcons.SWORD,
                x + (AVATAR_SIZE - iconSize) * 0.5f,
                y + (AVATAR_SIZE - iconSize) * 0.5f,
                iconSize,
                RenderUtil.withAlpha(theme.accent(), alpha)
            );
        }
    }

    private void captureSnapshot(EntityLivingBase target) {
        if (target == null) {
            return;
        }
        this.hasSnapshot = true;
        String name = target.getName();
        this.snapshotName = (name != null && !name.isEmpty()) ? name : DUMMY_FALLBACK_NAME;
        float maxHp = target.getMaxHealth();
        this.snapshotMaxHealth = maxHp > 0.0f ? maxHp : 20.0f;
        this.snapshotHealth = Math.max(0.0f, target.getHealth() + target.getAbsorptionAmount());
        this.snapshotArmorValue = target.getTotalArmorValue();
        this.snapshotSkin = (target instanceof AbstractClientPlayer)
            ? ((AbstractClientPlayer) target).getLocationSkin()
            : null;
        // Slots 4..1 = helmet, chestplate, leggings, boots
        for (int i = 0; i < 4; i++) {
            this.snapshotArmor[i] = target.getEquipmentInSlot(4 - i);
        }
    }

    private ItemStack[] extractPlayerArmor(EntityLivingBase entity) {
        for (int i = 0; i < 4; i++) {
            this.snapshotArmor[i] = entity.getEquipmentInSlot(4 - i);
        }
        return this.snapshotArmor;
    }

    private ItemStack[] getDummyArmor() {
        if (!this.dummyArmorInitialized) {
            this.dummyArmorInitialized = true;
            if (Items.diamond_helmet != null) {
                this.dummyArmor[0] = new ItemStack(Items.diamond_helmet);
                this.dummyArmor[1] = new ItemStack(Items.diamond_chestplate);
                this.dummyArmor[2] = new ItemStack(Items.diamond_leggings);
                this.dummyArmor[3] = new ItemStack(Items.diamond_boots);
            }
        }
        return this.dummyArmor;
    }

    private String getFormattedHealth(float health) {
        int tenths = Math.max(0, Math.round(health * 10.0f));
        if (tenths != this.lastHealthTenths) {
            this.lastHealthTenths = tenths;
            this.cachedHealthText = HudRenderHelper.formatTenths(tenths, this.healthScratch);
        }
        return this.cachedHealthText;
    }

    private String getFormattedArmor(int armorValue) {
        if (armorValue != this.lastArmorValue) {
            this.lastArmorValue = armorValue;
            this.cachedArmorText = HudRenderHelper.formatInt(armorValue, this.armorTextScratch);
        }
        return this.cachedArmorText;
    }

    private void clearTrackingState() {
        this.trackedTarget = null;
        this.hasSnapshot = false;
        this.snapshotSkin = null;
        for (int i = 0; i < 4; i++) {
            this.snapshotArmor[i] = null;
        }
    }

    private static boolean hasAnyArmor(EntityLivingBase entity) {
        for (int slot = 1; slot <= 4; slot++) {
            if (entity.getEquipmentInSlot(slot) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidTarget(Entity entity, Minecraft mc) {
        if (!(entity instanceof EntityLivingBase)) {
            return false;
        }
        if (entity == mc.thePlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        return living.isEntityAlive()
            && living.getHealth() > 0.0f
            && (mc.thePlayer == null || !living.isInvisibleToPlayer(mc.thePlayer));
    }

    private static float clamp01(float v) {
        if (v <= 0.0f) {
            return 0.0f;
        }
        if (v >= 1.0f) {
            return 1.0f;
        }
        return v;
    }
}
