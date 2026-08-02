package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/**
 * Water Walker — lets you walk on the surface of water.
 *
 * <p>Each tick, while you're at a water surface (and not sneaking — sneak to dive),
 * it holds you at the top of the water instead of letting you sink. The trick to
 * stay <b>wetted</b>: it keeps your feet just in the surface block rather than
 * hovering cleanly above it. A small per-tick bob keeps the surface height from
 * being a rigid pin.
 *
 * <p>Sneak to sink/dive, and it yields to real flight/elytra. Like all movement
 * mods it changes physics, so multiplayer movement checks can directly flag it.
 */
public final class WaterWalkController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	public WaterWalkController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || !config.waterWalkEnabled || player == null || client.world == null
				|| !player.isAlive() || player.isSpectator()) {
			return;
		}
		// Sneak to dive, and never fight real flight / gliding.
		if (player.isSneaking() || player.getAbilities().flying || player.isGliding()) {
			return;
		}

		BlockPos feet = player.getBlockPos();
		boolean inWater    = client.world.getFluidState(feet).isIn(FluidTags.WATER);
		boolean waterBelow = client.world.getFluidState(feet.down()).isIn(FluidTags.WATER);
		if (!inWater && !waterBelow) {
			return; // not over water
		}
		// If your head is underwater you went under on purpose — let normal
		// swimming take over.
		if (client.world.getFluidState(feet.up()).isIn(FluidTags.WATER)) {
			return;
		}

		Vec3d v = player.getVelocity();
		double vy;
		if (inWater) {
			// Feet are in the surface block — rise gently toward the top, capped so
			// you don't pop clean out of the water (staying wetted is what keeps a
			// vanilla server from falsely flagging the float as flight).
			vy = MathHelper.clamp(v.y + 0.045, -0.02, 0.07);
		} else {
			// Just above the surface with water under you — settle back down to touch
			// it rather than hover above it.
			vy = Math.min(v.y, -0.012);
		}
		// Humanized bob — small variance so the surface height isn't a rigid pin.
		vy += (rng.nextDouble() - 0.5) * 0.012;

		player.setVelocity(v.x, vy, v.z);
		player.fallDistance = 0.0F;
	}
}
