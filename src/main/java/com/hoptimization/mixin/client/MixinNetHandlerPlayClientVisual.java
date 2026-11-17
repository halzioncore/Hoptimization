package com.hoptimization.mixin.client;

import com.hoptimization.modules.visual.TimeChanger;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts server time sync packets on the client thread for {@link TimeChanger}.
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClientVisual {

    /**
     * Target: {@code NetHandlerPlayClient.handleTimeUpdate(S03PacketTimeUpdate)} RETURN.
     * Purpose: Captures server time for clean disable restoration and reapplies {@link TimeChanger} override.
     * Controlled by: {@link TimeChanger#isEnabled()}.
     */
    @Inject(method = "handleTimeUpdate", at = @At("RETURN"), require = 0)
    private void hoptimization$onHandleTimeUpdate(S03PacketTimeUpdate packetIn, CallbackInfo ci) {
        if (packetIn != null) {
            TimeChanger.onServerTimePacket(packetIn.getWorldTime());
        }
    }
}
