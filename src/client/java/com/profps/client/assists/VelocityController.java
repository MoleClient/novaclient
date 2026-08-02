package com.profps.client.assists;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Stateful part of Vape-style Velocity. The packet mixin delegates each local
 * knockback vector here; delayed reduction is completed from the client tick.
 */
public final class VelocityController {
	private static VelocityController active;
	private static final double FACE_TO_FACE_DOT = Math.cos(Math.toRadians(55.0D));

	private final ProFPSConfig config;
	private Vec3d pendingVelocity;
	private int ticksRemaining;

	public VelocityController(ProFPSConfig config) {
		this.config = config;
		active = this;
	}

	public static Vec3d transformIncoming(Entity entity, Vec3d incoming) {
		VelocityController controller = active;
		return controller == null ? incoming : controller.transform(entity, incoming);
	}

	private Vec3d transform(Entity entity, Vec3d incoming) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!canRun(client, entity)) return incoming;

		FacingState facing = facingState(client);
		if (config.velocityOnlyWhenTargeting && !(facing.playerFacing && facing.targetFacing)) {
			return incoming;
		}
		if (ThreadLocalRandom.current().nextInt(100)
				>= MathHelper.clamp(config.velocityChance, 0, 100)) return incoming;

		if (config.velocityKiteMode && facing.playerFacing
				&& (config.velocityAlwaysKite || !facing.targetFacing)) {
			return scale(incoming, config.velocityKiteHorizontal, config.velocityKiteVertical, false);
		}
		if (config.velocityTicks > 0) {
			pendingVelocity = incoming;
			ticksRemaining = MathHelper.clamp(config.velocityTicks, 0, 10);
			return incoming;
		}
		return scale(incoming, config.velocityHorizontal, config.velocityVertical, true);
	}

	public void tick(MinecraftClient client) {
		if (pendingVelocity == null) return;
		if (!canRun(client, client == null ? null : client.player)) {
			clearPending();
			return;
		}
		if (ticksRemaining-- > 0) return;

		ClientPlayerEntity player = client.player;
		double[] percent = reducedPercents(config.velocityHorizontal, config.velocityVertical,
				ThreadLocalRandom.current().nextDouble());
		Vec3d current = player.getVelocity();
		double y = current.y;
		if (pendingVelocity.y != 0.0D && y > 0.0D) y *= percent[1] / 100.0D;
		player.setVelocity(current.x * percent[0] / 100.0D, y,
				current.z * percent[0] / 100.0D);
		clearPending();
	}

	private boolean canRun(MinecraftClient client, Entity entity) {
		if (!config.enabled || !config.velocity || client == null || client.player == null
				|| entity != client.player || !client.player.isAlive()) return false;
		return !config.velocityWaterCheck || !client.player.isTouchingWater();
	}

	private FacingState facingState(MinecraftClient client) {
		if (!(client.crosshairTarget instanceof EntityHitResult hit)) return FacingState.NONE;
		Entity target = hit.getEntity();
		if (target == null || target == client.player || !target.isAlive()) return FacingState.NONE;
		Vec3d towardPlayer = client.player.getEyePos().subtract(target.getEyePos());
		if (towardPlayer.lengthSquared() < 1.0E-6D) return new FacingState(true, true);
		double dot = target.getRotationVec(1.0F).normalize().dotProduct(towardPlayer.normalize());
		return new FacingState(true, dot >= FACE_TO_FACE_DOT);
	}

	private Vec3d scale(Vec3d motion, int horizontal, int vertical, boolean jitter) {
		double[] percent = jitter
				? reducedPercents(horizontal, vertical, ThreadLocalRandom.current().nextDouble())
				: new double[] {horizontal, vertical};
		return new Vec3d(motion.x * percent[0] / 100.0D,
				motion.y * percent[1] / 100.0D,
				motion.z * percent[0] / 100.0D);
	}

	static double[] reducedPercents(int horizontal, int vertical, double jitterSample) {
		double sample = MathHelper.clamp(jitterSample, 0.0D, 1.0D);
		double h = MathHelper.clamp(horizontal, 0, 100);
		double v = MathHelper.clamp(vertical, 0, 100);
		if (h > 0.0D) h = Math.min(100.0D, h + 5.0D * sample);
		if (v > 0.0D) {
			v += 5.0D * sample;
			if (v >= 90.0D) v = 100.0D;
		}
		return new double[] {h, Math.min(100.0D, v)};
	}

	private void clearPending() {
		pendingVelocity = null;
		ticksRemaining = 0;
	}

	private record FacingState(boolean playerFacing, boolean targetFacing) {
		private static final FacingState NONE = new FacingState(false, false);
	}
}
