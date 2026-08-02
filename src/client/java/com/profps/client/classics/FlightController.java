package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

/**
 * Flight — the classic creative-style fly.
 *
 * <p>Each tick it drives the player's velocity straight from your movement keys —
 * WASD to move the way you're looking, jump to rise, sneak to descend — and zeroes
 * it when you press nothing, so gravity never pulls you down (set every tick, the
 * velocity overwrite is what cancels the fall). Fall damage is cleared while it's
 * on.
 *
 * <p>This is purely client-side motion. Multiplayer movement checks can directly
 * observe the impossible velocity and may immediately flag or rubber-band it.
 */
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
		// Blocks per tick: level 1 ≈ a walk, level 10 ≈ a fast creative fly.
		double speed = 0.13 * MathHelper.clamp(config.flightSpeed, 1, 10);

		// Horizontal direction from your movement input, relative to where you look.
		// (Zeroed automatically when no screen is captured / no keys are down.)
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

		// Drive velocity directly. Setting it every tick (to zero when idle) is what
		// holds you up — press nothing and you hover in place.
		player.setVelocity(moveX * speed, vy * speed, moveZ * speed);
		player.fallDistance = 0.0F;
	}
}
