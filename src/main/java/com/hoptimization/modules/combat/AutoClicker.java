package com.hoptimization.modules.combat;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.mixin.combat.AccessorMinecraftCombat;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Random;

/**
 * Cheat-gated module that dispatches randomized left and right mouse clicks while held in-world.
 */
public class AutoClicker extends Module {
    public static final String ID = "auto_clicker";
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;

    private final BooleanSetting leftClick;
    private final NumberSetting minCps;
    private final NumberSetting maxCps;
    private final BooleanSetting rightClick;
    private final NumberSetting rightCps;
    private final BooleanSetting breakBlocks;
    private final BooleanSetting weaponsOnly;

    private final Random random = new Random();
    private boolean adjustingBounds;
    private boolean wasLeftHeld;
    private boolean wasRightHeld;
    private long nextLeftClickNanos;
    private long nextRightClickNanos;

    /**
     * Creates the Auto Clicker module (disabled by default, requires Cheat Mode).
     */
    public AutoClicker() {
        super(ID, Category.COMBAT, Keyboard.KEY_NONE, true, true);
        this.leftClick = addSetting(
            new BooleanSetting("left_click", "setting.hoptimization.auto_clicker.left_click.name", true)
        );
        this.minCps = addSetting(
            new NumberSetting("min_cps", "setting.hoptimization.auto_clicker.min_cps.name", 9.0D, 1.0D, 20.0D, 0.5D)
                .visibleWhen(this.leftClick::isEnabled)
        );
        this.maxCps = addSetting(
            new NumberSetting("max_cps", "setting.hoptimization.auto_clicker.max_cps.name", 13.0D, 1.0D, 20.0D, 0.5D)
                .visibleWhen(this.leftClick::isEnabled)
        );
        this.rightClick = addSetting(
            new BooleanSetting("right_click", "setting.hoptimization.auto_clicker.right_click.name", false)
        );
        this.rightCps = addSetting(
            new NumberSetting(
                "right_cps",
                "setting.hoptimization.auto_clicker.right_cps.name",
                10.0D,
                1.0D,
                20.0D,
                0.5D
            ).visibleWhen(this.rightClick::isEnabled)
        );
        this.breakBlocks = addSetting(
            new BooleanSetting("break_blocks", "setting.hoptimization.auto_clicker.break_blocks.name", true)
        );
        this.weaponsOnly = addSetting(
            new BooleanSetting("weapons_only", "setting.hoptimization.auto_clicker.weapons_only.name", false)
        );

        this.minCps.addChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!adjustingBounds && minCps.getDoubleValue() > maxCps.getDoubleValue()) {
                    adjustingBounds = true;
                    try {
                        maxCps.setValue(Double.valueOf(minCps.getDoubleValue()));
                    } finally {
                        adjustingBounds = false;
                    }
                }
            }
        });
        this.maxCps.addChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!adjustingBounds && maxCps.getDoubleValue() < minCps.getDoubleValue()) {
                    adjustingBounds = true;
                    try {
                        minCps.setValue(Double.valueOf(maxCps.getDoubleValue()));
                    } finally {
                        adjustingBounds = false;
                    }
                }
            }
        });
    }

    /**
     * Returns the left-click auto-clicker toggle setting.
     *
     * @return left-click setting
     */
    public BooleanSetting getLeftClickSetting() {
        return this.leftClick;
    }

    /**
     * Returns the minimum left-click CPS setting.
     *
     * @return minimum CPS setting
     */
    public NumberSetting getMinCpsSetting() {
        return this.minCps;
    }

    /**
     * Returns the maximum left-click CPS setting.
     *
     * @return maximum CPS setting
     */
    public NumberSetting getMaxCpsSetting() {
        return this.maxCps;
    }

    /**
     * Returns the right-click auto-clicker toggle setting.
     *
     * @return right-click setting
     */
    public BooleanSetting getRightClickSetting() {
        return this.rightClick;
    }

    /**
     * Returns the target right-click CPS setting.
     *
     * @return right-click CPS setting
     */
    public NumberSetting getRightCpsSetting() {
        return this.rightCps;
    }

    /**
     * Returns the pause-while-breaking-blocks toggle setting.
     *
     * @return break-blocks setting
     */
    public BooleanSetting getBreakBlocksSetting() {
        return this.breakBlocks;
    }

    /**
     * Returns the swords-and-axes-only toggle setting for left clicking.
     *
     * @return weapons-only setting
     */
    public BooleanSetting getWeaponsOnlySetting() {
        return this.weaponsOnly;
    }

    @Override
    protected void onEnable() {
        resetTimers();
    }

    @Override
    protected void onDisable() {
        resetTimers();
    }

    /**
     * Evaluates held mouse buttons per render frame for sub-tick CPS accuracy without allocation.
     *
     * @param event Forge render tick event
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (!isEnabled() || !isAvailable()) {
            resetTimers();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || mc.playerController == null
            || mc.currentScreen != null || !mc.inGameHasFocus || mc.isGamePaused()) {
            resetTimers();
            return;
        }

        long now = System.nanoTime();
        handleLeftClick(mc, now);
        handleRightClick(mc, now);
    }

    private void handleLeftClick(Minecraft mc, long now) {
        if (!this.leftClick.isEnabled() || !isKeyBindingDown(mc.gameSettings.keyBindAttack)) {
            this.wasLeftHeld = false;
            this.nextLeftClickNanos = 0L;
            return;
        }
        if (!this.wasLeftHeld) {
            // Let vanilla handle the initial press click and schedule the first synthetic repeat.
            this.wasLeftHeld = true;
            this.nextLeftClickNanos = now + computeLeftIntervalNanos();
            return;
        }
        if (now < this.nextLeftClickNanos) {
            return;
        }
        this.nextLeftClickNanos = now + computeLeftIntervalNanos();

        if (mc.objectMouseOver == null || mc.thePlayer.isUsingItem()) {
            return;
        }
        if (this.weaponsOnly.isEnabled() && !isHoldingWeapon(mc.thePlayer)) {
            return;
        }
        if (this.breakBlocks.isEnabled() && isBreakingOrHoveringBlock(mc)) {
            return;
        }

        AccessorMinecraftCombat accessor = (AccessorMinecraftCombat) (Object) mc;
        accessor.hoptimization$setLeftClickCounter(0);
        accessor.hoptimization$clickMouse();
    }

    private void handleRightClick(Minecraft mc, long now) {
        if (!this.rightClick.isEnabled() || !isKeyBindingDown(mc.gameSettings.keyBindUseItem)) {
            this.wasRightHeld = false;
            this.nextRightClickNanos = 0L;
            return;
        }
        if (!this.wasRightHeld) {
            this.wasRightHeld = true;
            this.nextRightClickNanos = now + computeRightIntervalNanos();
            return;
        }
        if (now < this.nextRightClickNanos) {
            return;
        }
        this.nextRightClickNanos = now + computeRightIntervalNanos();

        if (mc.objectMouseOver == null || mc.thePlayer.isUsingItem() || mc.playerController.getIsHittingBlock()) {
            return;
        }

        AccessorMinecraftCombat accessor = (AccessorMinecraftCombat) (Object) mc;
        accessor.hoptimization$rightClickMouse();
    }

    private long computeLeftIntervalNanos() {
        double minVal = this.minCps.getDoubleValue();
        double maxVal = this.maxCps.getDoubleValue();
        double low = Math.min(minVal, maxVal);
        double high = Math.max(minVal, maxVal);
        double cps = low;
        if (high > low) {
            cps = low + this.random.nextDouble() * (high - low);
        }
        if (cps < 1.0D) {
            cps = 1.0D;
        }
        return (long) (NANOS_PER_SECOND / cps);
    }

    private long computeRightIntervalNanos() {
        double cps = this.rightCps.getDoubleValue();
        if (cps < 1.0D) {
            cps = 1.0D;
        }
        return (long) (NANOS_PER_SECOND / cps);
    }

    private void resetTimers() {
        this.wasLeftHeld = false;
        this.wasRightHeld = false;
        this.nextLeftClickNanos = 0L;
        this.nextRightClickNanos = 0L;
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

    private static boolean isHoldingWeapon(EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        if (held == null) {
            return false;
        }
        Item item = held.getItem();
        return item instanceof ItemSword || item instanceof ItemAxe;
    }

    private static boolean isBreakingOrHoveringBlock(Minecraft mc) {
        if (mc.playerController.getIsHittingBlock()) {
            return true;
        }
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (pos != null && mc.theWorld.getBlockState(pos).getBlock().getMaterial() != Material.air) {
                return true;
            }
        }
        return false;
    }
}
