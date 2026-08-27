package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/** Holds the player at a water surface instead of sinking; sneaking dives. */
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
		if (player.isSneaking() || player.getAbilities().flying || player.isGliding()) {
			return;
		}

		BlockPos feet = player.getBlockPos();
		boolean inWater    = client.world.getFluidState(feet).isIn(FluidTags.WATER);
		boolean waterBelow = client.world.getFluidState(feet.down()).isIn(FluidTags.WATER);
		if (!inWater && !waterBelow) {
			return;
		}
		// Head submerged: leave it to normal swimming.
		if (client.world.getFluidState(feet.up()).isIn(FluidTags.WATER)) {
			return;
		}

		Vec3d v = player.getVelocity();
		double vy;
		if (inWater) {
			// Rise toward the top, capped so the player stays touching water.
			vy = MathHelper.clamp(v.y + 0.045, -0.02, 0.07);
		} else {
			// Above the surface: settle back down onto it.
			vy = Math.min(v.y, -0.012);
		}
		vy += (rng.nextDouble() - 0.5) * 0.012;

		player.setVelocity(v.x, vy, v.z);
		player.fallDistance = 0.0F;
	}
}
