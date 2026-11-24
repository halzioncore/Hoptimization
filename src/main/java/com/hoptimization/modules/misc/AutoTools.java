package com.hoptimization.modules.misc;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.Collection;

/**
 * Cheat-gated utility module that automatically switches to the fastest hotbar tool
 * when mining blocks (and optionally best weapon when attacking entities).
 */
public class AutoTools extends Module {
    public static final String ID = "auto_tools";

    private final BooleanSetting switchBack;
    private final BooleanSetting sneakOnly;
    private final BooleanSetting bestWeapon;

    private int previousSlot = -1;

    /**
     * Creates the Auto Tools module (disabled by default, requires Cheat Mode).
     */
    public AutoTools() {
        super(ID, Category.MISC, Keyboard.KEY_NONE, true, true);
        this.switchBack = addSetting(
            new BooleanSetting("switch_back", "setting.hoptimization.auto_tools.switch_back.name", true)
        );
        this.sneakOnly = addSetting(
            new BooleanSetting("sneak_only", "setting.hoptimization.auto_tools.sneak_only.name", false)
        );
        this.bestWeapon = addSetting(
            new BooleanSetting("best_weapon", "setting.hoptimization.auto_tools.best_weapon.name", false)
        );
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    protected void onDisable() {
        this.previousSlot = -1;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.START || !isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (mc.currentScreen != null || player.capabilities.isCreativeMode) {
            restoreSlot(player);
            return;
        }

        boolean attackDown = mc.gameSettings != null && mc.gameSettings.keyBindAttack.isKeyDown();
        if (!attackDown || (this.sneakOnly.isEnabled() && !player.isSneaking())) {
            restoreSlot(player);
            return;
        }

        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null) {
            restoreSlot(player);
            return;
        }

        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (pos == null) {
                restoreSlot(player);
                return;
            }
            Block block = mc.theWorld.getBlockState(pos).getBlock();
            if (block == null || block.getMaterial().isReplaceable() || block.getBlockHardness(mc.theWorld, pos) < 0.0F) {
                restoreSlot(player);
                return;
            }
            switchToBestTool(player, block);
        } else if (this.bestWeapon.isEnabled() && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            switchToBestWeapon(player);
        } else {
            restoreSlot(player);
        }
    }

    private void switchToBestTool(EntityPlayerSP player, Block block) {
        int current = player.inventory.currentItem;
        float currentSpeed = getMiningSpeed(player.inventory.getStackInSlot(current), block);
        int bestSlot = current;
        float bestSpeed = currentSpeed;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            float speed = getMiningSpeed(stack, block);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }

        if (bestSlot != current && bestSpeed > currentSpeed) {
            if (this.previousSlot == -1) {
                this.previousSlot = current;
            }
            player.inventory.currentItem = bestSlot;
        }
    }

    private void switchToBestWeapon(EntityPlayerSP player) {
        int current = player.inventory.currentItem;
        double currentDamage = getWeaponDamage(player.inventory.getStackInSlot(current));
        int bestSlot = current;
        double bestDamage = currentDamage;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            double damage = getWeaponDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }

        if (bestSlot != current && bestDamage > currentDamage) {
            if (this.previousSlot == -1) {
                this.previousSlot = current;
            }
            player.inventory.currentItem = bestSlot;
        }
    }

    private void restoreSlot(EntityPlayerSP player) {
        if (this.previousSlot != -1) {
            if (this.switchBack.isEnabled() && this.previousSlot >= 0 && this.previousSlot < 9) {
                player.inventory.currentItem = this.previousSlot;
            }
            this.previousSlot = -1;
        }
    }

    private static float getMiningSpeed(ItemStack stack, Block block) {
        if (stack == null) {
            return 1.0F;
        }
        float speed = stack.getStrVsBlock(block);
        if (speed > 1.0F) {
            int eff = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, stack);
            if (eff > 0) {
                speed += (float) (eff * eff + 1);
            }
        }
        return speed;
    }

    private static double getWeaponDamage(ItemStack stack) {
        if (stack == null) {
            return 0.0D;
        }
        double base = 0.0D;
        Collection<AttributeModifier> modifiers = stack.getAttributeModifiers().get("generic.attackDamage");
        if (modifiers != null) {
            for (AttributeModifier mod : modifiers) {
                base += mod.getAmount();
            }
        }
        int sharpness = EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack);
        if (sharpness > 0) {
            base += sharpness * 1.25D;
        }
        return base;
    }
}
