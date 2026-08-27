package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/** Drives a ridden boat's position from movement input, overriding its gravity and water physics. */
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

		// Seed from the boat's real position on mount so it doesn't snap.
		if (boat != lastBoat) {
			bx = boat.getX();
			by = boat.getY();
			bz = boat.getZ();
			lastBoat = boat;
		}

		// Blocks per tick.
		double speed = 0.13 * MathHelper.clamp(config.boatFlySpeed, 1, 10);

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

		bx += moveX * speed;
		by += vy * speed;
		bz += moveZ * speed;

		boat.setVelocity(Vec3d.ZERO);
		boat.setPos(bx, by, bz);
		boat.resetPosition();   // sync render/prev fields to avoid jitter
		boat.fallDistance = 0.0F;
	}
}
