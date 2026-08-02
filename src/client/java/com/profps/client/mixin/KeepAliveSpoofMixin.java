package com.profps.client.mixin;

import com.profps.client.ProFPSClient;
import com.profps.client.extras.PingSpoofController;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Ping Spoofer hold the KeepAlive reply. When spoofing, the controller takes
 * ownership of the response and we cancel the vanilla immediate answer; otherwise
 * the packet is handled normally. KeepAlive is part of the common protocol, so this
 * targets {@link ClientCommonNetworkHandler}.
 */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class KeepAliveSpoofMixin {
	@Inject(method = "onKeepAlive", at = @At("HEAD"), cancellable = true)
	private void profps$spoofPing(KeepAliveS2CPacket packet, CallbackInfo ci) {
		PingSpoofController controller = ProFPSClient.pingSpoof();
		if (controller != null && controller.captureIfSpoofing(packet.getId())) {
			ci.cancel();
		}
	}
}
