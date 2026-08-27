package com.profps.client.hypixel;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/** Breaks the bed under the player's crosshair on Hypixel. */
public final class BedBreakerController {
	private final ProFPSConfig config;

	public BedBreakerController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.hypixelBedBreaker || client == null || client.player == null
				|| client.world == null || client.interactionManager == null || client.currentScreen != null) return;
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
				|| !(client.world.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock)) return;
		if (client.interactionManager.updateBlockBreakingProgress(hit.getBlockPos(), hit.getSide())) {
			client.player.swingHand(Hand.MAIN_HAND);
		}
	}
}
