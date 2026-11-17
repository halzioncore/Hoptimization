package com.hoptimization.modules.movement;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.mixin.client.AccessorEntityLivingBaseMove;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Cheat-gated movement module that eliminates the 10-tick held-space jump delay.
 */
public class NoJumpDelay extends Module {
    public static final String ID = "no_jump_delay";

    /**
     * Creates the No Jump Delay module (requires Cheat Mode).
     */
    public NoJumpDelay() {
        super(ID, Category.MOVEMENT, Keyboard.KEY_NONE, true, true);
    }

    /**
     * Clears {@code jumpTicks} before player movement updates when enabled and unlocked.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled() || !isAvailable()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            ((AccessorEntityLivingBaseMove) mc.thePlayer).hoptimization$setJumpTicks(0);
        }
    }
}
