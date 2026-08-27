package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

/** Creative-style flight: drives player velocity from movement input every tick. */
public final class FlightController {
	private final ProFPSConfig config;

	public FlightController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || !config.flightEnabled || player == null || client.world == null
				|| !player.isAlive() || player.isSpectator()) {
			return;
		}
		// Blocks per tick.
		double speed = 0.13 * MathHelper.clamp(config.flightSpeed, 1, 10);

		// Horizontal direction from movement input, rotated into yaw space.
		Vec2f mv = player.input.getMovementInput();
		double yawRad = Math.toRadians(player.getYaw());
		double sin = Math.sin(yawRad);
		double cos = Math.cos(yawRad);
		double moveX = mv.x * cos - mv.y * sin;
		double moveZ = mv.y * cos + mv.x * sin;
		double len = Math.sqrt(moveX * moveX + moveZ * moveZ);
		if (len > 1.0E-4) {
			moveX /= len;
			moveZ /= len;
		}

		double vy = 0.0;
		if (client.options.jumpKey.isPressed())  vy += 1.0;
		if (client.options.sneakKey.isPressed()) vy -= 1.0;

		// Overwriting velocity every tick, including to zero, cancels gravity.
		player.setVelocity(moveX * speed, vy * speed, moveZ * speed);
		player.fallDistance = 0.0F;
	}
}
