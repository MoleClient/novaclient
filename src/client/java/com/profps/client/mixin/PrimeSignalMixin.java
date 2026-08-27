package com.profps.client.mixin;

import com.profps.client.donutsmp.PrimeChunkFinder;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds sound, block update, block event and particle packets to Prime Chunk Finder.
 * Injected at TAIL so vanilla has already applied the packet on the client thread.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class PrimeSignalMixin {

	@Inject(method = "onPlaySound", at = @At("TAIL"))
	private void profps$primeSound(PlaySoundS2CPacket packet, CallbackInfo ci) {
		if (!PrimeChunkFinder.listening()) return;
		String id = packet.getSound().getKey().map(key -> key.getValue().toString()).orElse(null);
		PrimeChunkFinder.recordSound(packet.getX(), packet.getY(), packet.getZ(), id);
	}

	@Inject(method = "onBlockUpdate", at = @At("TAIL"))
	private void profps$primeBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
		if (!PrimeChunkFinder.listening()) return;
		PrimeChunkFinder.recordBlockUpdate(packet.getPos());
	}

	@Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
	private void profps$primeChunkDelta(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
		if (!PrimeChunkFinder.listening()) return;
		packet.visitUpdates((pos, state) -> PrimeChunkFinder.recordBlockUpdate(pos));
	}

	@Inject(method = "onBlockEvent", at = @At("TAIL"))
	private void profps$primeBlockEvent(BlockEventS2CPacket packet, CallbackInfo ci) {
		if (!PrimeChunkFinder.listening()) return;
		PrimeChunkFinder.recordBlockEvent(packet.getPos());
	}

	@Inject(method = "onParticle", at = @At("TAIL"))
	private void profps$primeParticle(ParticleS2CPacket packet, CallbackInfo ci) {
		if (!PrimeChunkFinder.listening()) return;
		PrimeChunkFinder.recordParticle(packet.getX(), packet.getY(), packet.getZ());
	}
}
