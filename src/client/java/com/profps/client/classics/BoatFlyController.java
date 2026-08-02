package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * Boat Fly — fly a boat you're riding, even over solid ground.
 *
 * <p>While you're sitting in a boat, each tick this drives the boat's position
 * straight from your movement keys (WASD relative to where you're looking, jump up,
 * sneak down) and holds it there, fully overriding the boat's gravity and water
 * physics — so press nothing and you hover, press keys and you cruise. Purely
 * client-side motion; multiplayer movement checks can directly observe and flag it.
 */
public final class BoatFlyController {
	private final ProFPSConfig config;
	private Entity lastBoat;
	private double bx;
	private double by;
	private double bz;

	public BoatFlyController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || !config.boatFlyEnabled || player == null || client.world == null
				|| !player.isAlive()) {
			lastBoat = null;
			return;
		}
		Entity vehicle = player.getVehicle();
		if (!(vehicle instanceof AbstractBoatEntity boat)) {
			lastBoat = null;
			return;
		}

		// Take over from the boat's real position when we first mount / activate,
		// rather than snapping it somewhere.
		if (boat != lastBoat) {
			bx = boat.getX();
			by = boat.getY();
			bz = boat.getZ();
			lastBoat = boat;
		}

		// blocks per tick: level 1 ≈ a slow drift, level 10 ≈ a fast cruise.
		double speed = 0.13 * MathHelper.clamp(config.boatFlySpeed, 1, 10);

		// Horizontal direction from your movement input, relative to where you look.
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

		// We own the boat's position now — advance our authoritative point and force
		// the boat onto it every tick, ignoring whatever gravity did in between.
		bx += moveX * speed;
		by += vy * speed;
		bz += moveZ * speed;

		boat.setVelocity(Vec3d.ZERO);
		boat.setPos(bx, by, bz);
		boat.resetPosition();   // sync render/prev fields so it doesn't jitter
		boat.fallDistance = 0.0F;
	}
}
