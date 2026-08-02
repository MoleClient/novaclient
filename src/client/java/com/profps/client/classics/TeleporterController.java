package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Teleporter — right-click a block you're looking at to teleport onto it.
 *
 * <p>On each fresh right-click it raycasts where you're aiming (up to the configured
 * range) and moves your player to the face you clicked. Because this directly changes
 * client position rather than performing a vanilla teleport, multiplayer movement
 * checks can directly flag or reject it.
 */
public final class TeleporterController {
	private final ProFPSConfig config;
	private boolean prevUse;
	private long cooldownUntilNanos;

	public TeleporterController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || !config.teleporterEnabled || player == null || client.world == null
				|| client.currentScreen != null || !player.isAlive() || player.isSpectator()) {
			prevUse = false;
			return;
		}

		boolean use = client.options.useKey.isPressed();
		boolean clicked = use && !prevUse; // rising edge = one fresh right-click
		prevUse = use;
		if (!clicked) return;

		long now = System.nanoTime();
		if (now < cooldownUntilNanos) return;

		double range = MathHelper.clamp(config.teleporterRange, 8, 256);
		HitResult hit = player.raycast(range, 1.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK) return;

		BlockHitResult block = (BlockHitResult) hit;
		BlockPos dest = block.getBlockPos().offset(block.getSide()); // the open space against the clicked face

		player.setVelocity(0.0, 0.0, 0.0);
		player.setPosition(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
		player.setVelocity(0.0, 0.0, 0.0);
		player.resetPosition();   // snap render to the new spot (don't lerp the whole way)
		player.fallDistance = 0.0F;
		cooldownUntilNanos = now + 250_000_000L;
	}
}
