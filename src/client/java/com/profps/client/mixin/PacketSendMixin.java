package com.profps.client.mixin;

import com.profps.client.packet.PacketManager;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single choke-point for Packet Utils' outbound control. Every client-to-server packet
 * (movement, clicks, chat, keep-alive replies…) runs through {@code sendPacket} on the common
 * network handler, so intercepting here catches them all. {@link PacketManager} decides whether
 * to drop, park or pass each one; when it says "cancel" we skip the vanilla send.
 */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class PacketSendMixin {

	@Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
	private void profps$interceptOutbound(Packet<?> packet, CallbackInfo ci) {
		if (PacketManager.INSTANCE.interceptOutbound(packet)) {
			ci.cancel();
		}
	}
}
