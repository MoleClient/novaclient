package com.profps.client.mixin;

import com.profps.client.ProFPSClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onEntityStatus", at = @At("TAIL"))
	private void profps$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || packet.getStatus() != EntityStatuses.USE_TOTEM_OF_UNDYING) {
			return;
		}
		Entity entity = packet.getEntity(client.world);
		ProFPSClient.totemTweaks().markTotemPop(client, entity);
	}
}
