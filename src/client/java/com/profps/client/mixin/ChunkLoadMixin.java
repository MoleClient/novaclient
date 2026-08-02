package com.profps.client.mixin;

import com.profps.client.donutsmp.ScanBudget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ChunkLoadMixin {
	@Inject(method = "onChunkData", at = @At("TAIL"))
	private void profps$notifyChunkLoad(ChunkDataS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			ScanBudget.notifyChunkLoaded(client.player.age);
		}
	}
}
