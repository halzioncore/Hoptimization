package com.hoptimization.mixin.client;

import com.hoptimization.modules.visual.KillEffects;
import com.hoptimization.modules.visual.TimeChanger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts server time sync and entity destroy packets on the client thread for
 * {@link TimeChanger} and {@link KillEffects}.
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClientVisual {

    @Shadow
    private Minecraft gameController;

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

    /**
     * Target: {@code NetHandlerPlayClient.handleDestroyEntities(S13PacketDestroyEntities)} HEAD.
     * Purpose: Captures killed entities before world removal on the client main thread for {@link KillEffects}.
     * Controlled by: {@link KillEffects#isActive()}.
     *
     * @param packetIn incoming destroy entities packet
     * @param ci mixin callback info
     */
    @Inject(
        method = "handleDestroyEntities(Lnet/minecraft/network/play/server/S13PacketDestroyEntities;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void hoptimization$onHandleDestroyEntities(S13PacketDestroyEntities packetIn, CallbackInfo ci) {
        if (packetIn == null || !KillEffects.isActive()) {
            return;
        }
        if (this.gameController == null || !this.gameController.isCallingFromMinecraftThread()) {
            return;
        }
        int[] ids = packetIn.getEntityIDs();
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            KillEffects.onClientEntityDestroy(ids[i]);
        }
    }
}
