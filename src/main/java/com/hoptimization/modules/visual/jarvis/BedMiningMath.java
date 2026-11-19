package com.hoptimization.modules.visual.jarvis;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Pure MC 1.8.9 mining physics, block classification, and player movement speed math for bed defense analysis.
 */
public final class BedMiningMath {
    private static final float BASE_SPRINT_SPEED = 5.61F;

    private BedMiningMath() {
    }

    /**
     * Categorized BedWars defense block types with tactical weights, blast resistance, and HUD icons.
     */
    public enum DefenseBlockKind {
        AIR(0.0F, false, "air"),
        WOOL(1.0F, false, "wool"),
        GLASS(1.2F, true, "glass"),
        WATER(0.8F, true, "water"),
        CLAY(2.2F, false, "clay"),
        WOOD(3.0F, false, "wood"),
        END_STONE(4.5F, false, "end_stone"),
        OBSIDIAN(18.0F, true, "obsidian"),
        OTHER_SOLID(2.5F, false, "other");

        private final float weight;
        private final boolean blastResistant;
        private final String id;
        private ItemStack iconStack;

        DefenseBlockKind(float weight, boolean blastResistant, String id) {
            this.weight = weight;
            this.blastResistant = blastResistant;
            this.id = id;
        }

        /**
         * Returns the static tactical defense weight of this block kind.
         *
         * @return defense weight
         */
        public float getWeight() {
            return this.weight;
        }

        /**
         * Returns whether this block resists BedWars fireballs or TNT blasts.
         *
         * @return {@code true} if blast-resistant
         */
        public boolean isBlastResistant() {
            return this.blastResistant;
        }

        /**
         * Returns the stable lowercase identifier for this block kind.
         *
         * @return kind id
         */
        public String getId() {
            return this.id;
        }

        /**
         * Lazily creates and caches a GUI/HUD {@link ItemStack} icon, or {@code null} for {@link #AIR}.
         *
         * @return cached icon stack or {@code null}
         */
        public ItemStack getIconStack() {
            if (this == AIR) {
                return null;
            }
            if (this.iconStack == null) {
                this.iconStack = createIconStack();
            }
            return this.iconStack;
        }

        private ItemStack createIconStack() {
            switch (this) {
                case WOOL:
                    return Blocks.wool != null ? new ItemStack(Blocks.wool, 1, 0) : null;
                case GLASS:
                    return Blocks.glass != null ? new ItemStack(Blocks.glass) : null;
                case WATER:
                    return Items.water_bucket != null ? new ItemStack(Items.water_bucket) : null;
                case CLAY:
                    return Blocks.hardened_clay != null ? new ItemStack(Blocks.hardened_clay) : null;
                case WOOD:
                    return Blocks.planks != null ? new ItemStack(Blocks.planks, 1, 0) : null;
                case END_STONE:
                    return Blocks.end_stone != null ? new ItemStack(Blocks.end_stone) : null;
                case OBSIDIAN:
                    return Blocks.obsidian != null ? new ItemStack(Blocks.obsidian) : null;
                case OTHER_SOLID:
                    return Blocks.cobblestone != null ? new ItemStack(Blocks.cobblestone) : null;
                default:
                    return null;
            }
        }
    }

    /**
     * Classifies a Minecraft block into a BedWars defense category.
     *
     * @param block target block
     * @return classified {@link DefenseBlockKind}
     */
    public static DefenseBlockKind classifyBlock(Block block) {
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return DefenseBlockKind.AIR;
        }
        // Water is passable in pathfinding, yet still tracked explicitly as a blast-shielding defense layer.
        if (block == Blocks.water || block == Blocks.flowing_water) {
            return DefenseBlockKind.WATER;
        }
        if (isPassableAir(block)) {
            return DefenseBlockKind.AIR;
        }
        if (block == Blocks.wool) {
            return DefenseBlockKind.WOOL;
        }
        if (block == Blocks.glass
            || block == Blocks.stained_glass
            || block == Blocks.glass_pane
            || block == Blocks.stained_glass_pane) {
            return DefenseBlockKind.GLASS;
        }
        if (block == Blocks.hardened_clay || block == Blocks.stained_hardened_clay || block == Blocks.clay) {
            return DefenseBlockKind.CLAY;
        }
        if (block == Blocks.planks || block == Blocks.log || block == Blocks.log2
            || block.getMaterial() == Material.wood) {
            return DefenseBlockKind.WOOD;
        }
        if (block == Blocks.end_stone) {
            return DefenseBlockKind.END_STONE;
        }
        if (block == Blocks.obsidian) {
            return DefenseBlockKind.OBSIDIAN;
        }
        return DefenseBlockKind.OTHER_SOLID;
    }

    /**
     * Returns whether a block is air-like or non-obstructing for tactical pathfinding entry and traversal.
     *
     * @param block target block
     * @return {@code true} if passable
     */
    public static boolean isPassableAir(Block block) {
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return true;
        }
        return block == Blocks.carpet
            || block == Blocks.tallgrass
            || block == Blocks.deadbush
            || block == Blocks.vine
            || block == Blocks.snow_layer
            || block == Blocks.torch
            || block == Blocks.redstone_torch
            || block == Blocks.unlit_redstone_torch
            || block == Blocks.redstone_wire
            || block == Blocks.ladder
            || block == Blocks.water
            || block == Blocks.flowing_water
            || block == Blocks.standing_sign
            || block == Blocks.wall_sign
            || block == Blocks.stone_button
            || block == Blocks.wooden_button
            || block == Blocks.stone_pressure_plate
            || block == Blocks.wooden_pressure_plate
            || block == Blocks.light_weighted_pressure_plate
            || block == Blocks.heavy_weighted_pressure_plate
            || block == Blocks.lever;
    }

    /**
     * Computes the minimum seconds required to break the given block using bare hand or any hotbar slot {@code 0..8}.
     *
     * @param world       client world
     * @param pos         block position
     * @param state       block state at {@code pos}
     * @param player      local player (may be {@code null} for bare-hand fallback)
     * @param outBestSlot optional 1-element array receiving the winning slot ({@code -1} for bare hand, {@code 0..8}
     *                    for hotbar)
     * @return minimum break time in seconds, or {@link Float#POSITIVE_INFINITY} if unbreakable
     */
    public static float computeBestBreakSeconds(
        World world,
        BlockPos pos,
        IBlockState state,
        EntityPlayer player,
        int[] outBestSlot
    ) {
        if (outBestSlot != null && outBestSlot.length > 0) {
            outBestSlot[0] = -1;
        }
        if (world == null || pos == null) {
            return Float.POSITIVE_INFINITY;
        }
        IBlockState resolvedState = state != null ? state : world.getBlockState(pos);
        if (resolvedState == null) {
            return Float.POSITIVE_INFINITY;
        }
        Block block = resolvedState.getBlock();
        if (block == null) {
            return Float.POSITIVE_INFINITY;
        }

        float hardness = block.getBlockHardness(world, pos);
        if (hardness < 0.0F || Float.isNaN(hardness)) {
            return Float.POSITIVE_INFINITY;
        }
        if (hardness == 0.0F) {
            return 0.05F;
        }

        int hasteLevel = 0;
        int fatigueLevel = 0;
        if (player != null) {
            if (player.isPotionActive(Potion.digSpeed)) {
                PotionEffect hasteEffect = player.getActivePotionEffect(Potion.digSpeed);
                if (hasteEffect != null) {
                    hasteLevel = hasteEffect.getAmplifier() + 1;
                }
            }
            if (player.isPotionActive(Potion.digSlowdown)) {
                PotionEffect fatigueEffect = player.getActivePotionEffect(Potion.digSlowdown);
                if (fatigueEffect != null) {
                    fatigueLevel = fatigueEffect.getAmplifier() + 1;
                }
            }
        }

        boolean toolNotRequired = block.getMaterial().isToolNotRequired();
        int bestSlot = -1;
        int bestTicks = computePureBlockBreakTicks(
            hardness,
            toolNotRequired,
            1.0F,
            0,
            false,
            hasteLevel,
            fatigueLevel
        );

        if (player != null && player.inventory != null) {
            int effId = Enchantment.efficiency.effectId;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = player.inventory.getStackInSlot(slot);
                if (stack == null) {
                    continue;
                }
                float speed = stack.getStrVsBlock(block);
                int eff = speed > 1.0F ? EnchantmentHelper.getEnchantmentLevel(effId, stack) : 0;
                boolean canHarvest = toolNotRequired || stack.canHarvestBlock(block);
                int ticks = computePureBlockBreakTicks(
                    hardness,
                    toolNotRequired,
                    speed,
                    eff,
                    canHarvest,
                    hasteLevel,
                    fatigueLevel
                );
                if (ticks < bestTicks) {
                    bestTicks = ticks;
                    bestSlot = slot;
                }
            }
        }

        if (bestTicks == Integer.MAX_VALUE) {
            return Float.POSITIVE_INFINITY;
        }
        if (outBestSlot != null && outBestSlot.length > 0) {
            outBestSlot[0] = bestSlot;
        }
        return bestTicks * 0.05F;
    }

    /**
     * Pure MC 1.8.9 block break time calculation without {@link World} or {@link EntityPlayer} dependencies.
     *
     * @param hardness           block hardness ({@code < 0} means unbreakable)
     * @param toolNotRequired    whether {@code material.isToolNotRequired()} holds
     * @param toolSpeed          base tool dig speed ({@code 1.0F} for bare hand)
     * @param effLevel           Efficiency enchantment level ({@code 0} for none)
     * @param canHarvest         whether the tool can harvest item drops (only switches {@code 30.0F} vs {@code 100.0F}
     *                           divisor; never prevents breaking)
     * @param hasteLevel1Based   Haste level ({@code 0} none, {@code 1} Haste I, {@code 2} Haste II, ...)
     * @param fatigueLevel1Based Mining Fatigue level ({@code 0} none, {@code 1} Fatigue I, ...)
     * @return break time in seconds, or {@link Float#POSITIVE_INFINITY} if unbreakable
     */
    public static float computePureBlockBreakSeconds(
        float hardness,
        boolean toolNotRequired,
        float toolSpeed,
        int effLevel,
        boolean canHarvest,
        int hasteLevel1Based,
        int fatigueLevel1Based
    ) {
        int ticks = computePureBlockBreakTicks(
            hardness,
            toolNotRequired,
            toolSpeed,
            effLevel,
            canHarvest,
            hasteLevel1Based,
            fatigueLevel1Based
        );
        if (ticks == Integer.MAX_VALUE) {
            return Float.POSITIVE_INFINITY;
        }
        return ticks * 0.05F;
    }

    private static int computePureBlockBreakTicks(
        float hardness,
        boolean toolNotRequired,
        float toolSpeed,
        int effLevel,
        boolean canHarvest,
        int hasteLevel1Based,
        int fatigueLevel1Based
    ) {
        if (hardness < 0.0F || Float.isNaN(hardness)) {
            return Integer.MAX_VALUE;
        }
        if (hardness == 0.0F) {
            return 1;
        }
        float speed = toolSpeed;
        if (speed <= 0.0F || Float.isNaN(speed)) {
            return Integer.MAX_VALUE;
        }
        if (speed > 1.0F && effLevel > 0) {
            speed += (float) (effLevel * effLevel + 1);
        }
        if (hasteLevel1Based > 0) {
            speed *= 1.0F + hasteLevel1Based * 0.2F;
        }
        if (fatigueLevel1Based > 0) {
            if (fatigueLevel1Based == 1) {
                speed *= 0.3F;
            } else if (fatigueLevel1Based == 2) {
                speed *= 0.09F;
            } else if (fatigueLevel1Based == 3) {
                speed *= 0.0027F;
            } else {
                speed *= 0.00081F;
            }
        }
        boolean effectiveHarvest = toolNotRequired || canHarvest;
        float dmgPerTick = speed / hardness / (effectiveHarvest ? 30.0F : 100.0F);
        if (dmgPerTick <= 0.0F) {
            return Integer.MAX_VALUE;
        }
        return dmgPerTick >= 1.0F ? 1 : (int) Math.ceil(1.0F / dmgPerTick);
    }

    /**
     * Estimates horizontal sprint speed in blocks/second, accounting for active Speed potion effects.
     *
     * @param player local player (may be {@code null})
     * @return sprint speed in blocks/second
     */
    public static float computePlayerMoveSpeed(EntityPlayer player) {
        if (player != null && player.isPotionActive(Potion.moveSpeed)) {
            PotionEffect speedEffect = player.getActivePotionEffect(Potion.moveSpeed);
            if (speedEffect != null) {
                return BASE_SPRINT_SPEED * (1.0F + (speedEffect.getAmplifier() + 1) * 0.2F);
            }
        }
        return BASE_SPRINT_SPEED;
    }
}
