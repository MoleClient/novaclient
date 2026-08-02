package com.profps.client.mixin;

import com.profps.client.donutsmp.RevealedIntel;
import com.profps.client.donutsmp.WorldSignalMonitor;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds leaked server side-effects into the ground-intel pipeline. Injected at
 * TAIL so it runs on the client thread after vanilla has applied the packet.
 *
 * <p>Three leak families:
 * <ul>
 *   <li>Sounds → {@link WorldSignalMonitor} (live activity, decaying).</li>
 *   <li>Block updates → activity counter AND {@link RevealedIntel}: the packet
 *       carries the REAL block state, even inside the anti-xray masked zone.</li>
 *   <li>Block events (chest lids, pistons) → {@link RevealedIntel}: exact
 *       coordinates of active containers, broadcast through the mask.</li>
 * </ul>
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class PacketSignalMixin {

	@Inject(method = "onPlaySound", at = @At("TAIL"))
	private void profps$onPlaySound(PlaySoundS2CPacket packet, CallbackInfo ci) {
		WorldSignalMonitor monitor = WorldSignalMonitor.get();
		if (!monitor.isActive()) return;
		String id = packet.getSound().getKey().map(key -> key.getValue().toString()).orElse(null);
		if (id == null) return;
		monitor.recordSound(packet.getX(), packet.getY(), packet.getZ(), packet.getCategory(), id);
	}

	@Inject(method = "onBlockUpdate", at = @At("TAIL"))
	private void profps$onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
		WorldSignalMonitor monitor = WorldSignalMonitor.get();
		if (!monitor.isActive()) return;
		BlockPos pos = packet.getPos();
		monitor.recordBlockChange(pos.getX(), pos.getY(), pos.getZ());
		RevealedIntel.get().recordRevealedBlock(pos, packet.getState());
	}

	@Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
	private void profps$onChunkDeltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
		WorldSignalMonitor monitor = WorldSignalMonitor.get();
		if (!monitor.isActive()) return;
		packet.visitUpdates((pos, state) -> {
			monitor.recordBlockChange(pos.getX(), pos.getY(), pos.getZ());
			RevealedIntel.get().recordRevealedBlock(pos, state);
		});
	}

	@Inject(method = "onBlockEvent", at = @At("TAIL"))
	private void profps$onBlockEvent(BlockEventS2CPacket packet, CallbackInfo ci) {
		WorldSignalMonitor monitor = WorldSignalMonitor.get();
		if (!monitor.isActive()) return;
		RevealedIntel.get().recordBlockEvent(packet.getPos(), packet.getBlock());
	}
}
