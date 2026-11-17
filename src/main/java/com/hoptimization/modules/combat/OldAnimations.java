package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Purely visual 1.7-style first-person sword block-hit, swing-while-using, and bow animations.
 */
public class OldAnimations extends Module {
    private static OldAnimations instance;

    private final BooleanSetting swing;
    private final BooleanSetting block;
    private final BooleanSetting bow;
    private boolean wasAttackDown;

    /**
     * Creates the Old Animations module and registers its animation toggles.
     */
    public OldAnimations() {
        super("old_animations", Category.COMBAT);
        this.swing = addSetting(
            new BooleanSetting("swing", "setting.hoptimization.old_animations.swing.name", true)
        );
        this.block = addSetting(
            new BooleanSetting("block", "setting.hoptimization.old_animations.block.name", true)
        );
        this.bow = addSetting(
            new BooleanSetting("bow", "setting.hoptimization.old_animations.bow.name", true)
        );
        instance = this;
    }

    /**
     * Returns the swing animation toggle setting.
     *
     * @return swing setting
     */
    public BooleanSetting getSwingSetting() {
        return this.swing;
    }

    /**
     * Returns the block-hit animation toggle setting.
     *
     * @return block setting
     */
    public BooleanSetting getBlockSetting() {
        return this.block;
    }

    /**
     * Returns the bow pull animation toggle setting.
     *
     * @return bow setting
     */
    public BooleanSetting getBowSetting() {
        return this.bow;
    }

    /**
     * Fast hot-path check queried by {@link com.hoptimization.mixin.combat.MixinItemRendererCombat}.
     *
     * @return true when enabled
     */
    public static boolean isActive() {
        OldAnimations module = instance;
        return module != null && module.isEnabled();
    }

    /**
     * Returns whether 1.7 block-hit positioning should be applied in {@code doBlockTransformations}.
     *
     * @return true when module and block toggle are enabled
     */
    public static boolean isBlockTransformActive() {
        OldAnimations module = instance;
        return module != null && module.isEnabled() && module.block.isEnabled();
    }

    /**
     * Returns whether swing progress should be preserved while using an item with the given action.
     *
     * @param action active item use action
     * @return true if enabled for that action
     */
    public static boolean shouldSwingItemInUse(EnumAction action) {
        OldAnimations module = instance;
        if (module == null || !module.isEnabled() || action == null) {
            return false;
        }
        switch (action) {
            case BLOCK:
                return module.block.isEnabled();
            case BOW:
                return module.bow.isEnabled();
            case EAT:
            case DRINK:
            case NONE:
                return module.swing.isEnabled();
            default:
                return false;
        }
    }

    @Override
    protected void onEnable() {
        this.wasAttackDown = false;
    }

    @Override
    protected void onDisable() {
        this.wasAttackDown = false;
    }

    /**
     * Triggers client-only visual swings when left-clicking while blocking, eating, or drawing a bow.
     * Never dispatches extra attack or animation packets.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
            || mc.currentScreen != null || !mc.inGameHasFocus || mc.isGamePaused()) {
            this.wasAttackDown = false;
            return;
        }

        boolean attackDown = isKeyBindingDown(mc.gameSettings.keyBindAttack);
        boolean justPressed = attackDown && !this.wasAttackDown;
        this.wasAttackDown = attackDown;

        if (!attackDown || !mc.thePlayer.isUsingItem()) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !shouldSwingItemInUse(held.getItemUseAction())) {
            return;
        }
        if (justPressed || isTargetingSolidBlock(mc)) {
            triggerVisualSwing(mc.thePlayer);
        }
    }

    private static void triggerVisualSwing(EntityPlayerSP player) {
        // Mirror EntityLivingBase.swingItem animation state locally without sending C0APacketAnimation.
        int swingEnd = player.isPotionActive(Potion.digSpeed)
            ? 6 - (1 + player.getActivePotionEffect(Potion.digSpeed).getAmplifier())
            : (player.isPotionActive(Potion.digSlowdown)
                ? 6 + (1 + player.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) * 2
                : 6);
        if (!player.isSwingInProgress || player.swingProgressInt >= swingEnd / 2 || player.swingProgressInt < 0) {
            player.swingProgressInt = -1;
            player.isSwingInProgress = true;
        }
    }

    private static boolean isTargetingSolidBlock(Minecraft mc) {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        return pos != null && mc.theWorld.getBlockState(pos).getBlock().getMaterial() != Material.air;
    }

    private static boolean isKeyBindingDown(KeyBinding binding) {
        if (binding == null) {
            return false;
        }
        int code = binding.getKeyCode();
        if (code == 0) {
            return false;
        }
        if (code < 0) {
            int button = code + 100;
            return button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
        }
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }
}
