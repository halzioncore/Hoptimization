package com.hoptimization.mixin.client;

import com.hoptimization.modules.visual.KillEffects;
import com.hoptimization.modules.visual.TimeChanger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
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

    /**
     * Target: {@code NetHandlerPlayClient.handleEntityMetadata(S1CPacketEntityMetadata)} RETURN.
     * Purpose: Captures lethal health metadata updates even when {@code S19(3)} is suppressed by plugins.
     * Controlled by: {@link KillEffects#isActive()}.
     *
     * @param packetIn incoming entity metadata packet
     * @param ci mixin callback info
     */
    @Inject(
        method = "handleEntityMetadata(Lnet/minecraft/network/play/server/S1CPacketEntityMetadata;)V",
        at = @At("RETURN"),
        require = 0
    )
    private void hoptimization$onHandleEntityMetadata(S1CPacketEntityMetadata packetIn, CallbackInfo ci) {
        if (packetIn == null || !KillEffects.isActive()) {
            return;
        }
        if (this.gameController == null || !this.gameController.isCallingFromMinecraftThread()) {
            return;
        }
        KillEffects.onClientEntityMetadata(packetIn.getEntityId());
    }

    /**
     * Target: {@code NetHandlerPlayClient.handleEntityTeleport(S18PacketEntityTeleport)} HEAD.
     * Purpose: Snapshots pre-teleport combat coordinates before server spectator/respawn teleports.
     * Controlled by: {@link KillEffects#isActive()}.
     *
     * @param packetIn incoming entity teleport packet
     * @param ci mixin callback info
     */
    @Inject(
        method = "handleEntityTeleport(Lnet/minecraft/network/play/server/S18PacketEntityTeleport;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void hoptimization$onHandleEntityTeleport(S18PacketEntityTeleport packetIn, CallbackInfo ci) {
        if (packetIn == null || !KillEffects.isActive()) {
            return;
        }
        if (this.gameController == null || !this.gameController.isCallingFromMinecraftThread()) {
            return;
        }
        KillEffects.onClientEntityPreTeleport(packetIn);
    }

    /**
     * Target: {@code NetHandlerPlayClient.handlePlayerListItem(S38PacketPlayerListItem)} HEAD.
     * Purpose: Captures final kills signaled via Spectator transition or player list removal.
     * Controlled by: {@link KillEffects#isActive()}.
     *
     * @param packetIn incoming player list item packet
     * @param ci mixin callback info
     */
    @Inject(
        method = "handlePlayerListItem(Lnet/minecraft/network/play/server/S38PacketPlayerListItem;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void hoptimization$onHandlePlayerListItem(S38PacketPlayerListItem packetIn, CallbackInfo ci) {
        if (packetIn == null || !KillEffects.isActive()) {
            return;
        }
        if (this.gameController == null || !this.gameController.isCallingFromMinecraftThread()) {
            return;
        }
        KillEffects.onClientPlayerListItem(packetIn);
    }
}
