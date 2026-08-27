package com.profps.client.extras;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.AbstractFireballEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Tracks an incoming fireball and deflects it with a sword. */
public final class AntiFireballController {
	private static final double SCAN_RANGE = 18.0;
	private static final double THREAT_RADIUS = 3.0;
	private static final double HIT_RANGE = 3.0; // server attack reach
	private static final long HIT_COOLDOWN_NS = 180_000_000L;

	private final ProFPSConfig config;
	private final com.profps.client.aim.MouseGcd mouse = new com.profps.client.aim.MouseGcd();
	private int savedSlot = -1;
	private boolean swapped;
	private long hitCooldownUntilNanos;
	private int swordReadyAge;

	public AntiFireballController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || !config.antiFireballAssist || player == null || client.world == null
				|| client.interactionManager == null || !player.isAlive() || player.isSpectator()
				|| client.currentScreen != null) {
			restore(player);
			return;
		}

		AbstractFireballEntity threat = findIncomingFireball(client, player);
		if (threat == null) {
			restore(player);
			return;
		}

		int swordSlot = hotbarSword(player);
		if (swordSlot < 0) return;

		Vec3d aimAt = threat.getEntityPos().add(0.0, threat.getHeight() * 0.5, 0.0);
		Vec3d eye = player.getEyePos();
		double dx = aimAt.x - eye.x;
		double dy = aimAt.y - eye.y;
		double dz = aimAt.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float wantPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horiz)), -90.0F, 90.0F);
		// Turn is emitted as carried, sensitivity-grid-quantized mouse deltas.
		float yawError = MathHelper.wrapDegrees(wantYaw - player.getYaw());
		float pitchError = MathHelper.wrapDegrees(wantPitch - player.getPitch());
		player.setYaw(player.getYaw() + mouse.yaw(MathHelper.clamp(yawError, -34.0F, 34.0F)));
		player.setPitch(MathHelper.clamp(player.getPitch()
				+ mouse.pitch(MathHelper.clamp(pitchError, -26.0F, 26.0F)), -90.0F, 90.0F));

		if (!swapped) {
			savedSlot = player.getInventory().getSelectedSlot();
			swapped = true;
		}
		if (player.getInventory().getSelectedSlot() != swordSlot) {
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.ANTI_FIREBALL)) return;
			player.getInventory().setSelectedSlot(swordSlot);
			swordReadyAge = player.age + 1;
			return;
		}

		long now = System.nanoTime();
		if (eye.distanceTo(threat.getEntityPos()) <= HIT_RANGE
				&& player.age >= swordReadyAge
				&& now >= hitCooldownUntilNanos
				&& freshTarget(client, player) == threat
				&& CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.ANTI_FIREBALL)) {
			client.interactionManager.attackEntity(player, threat);
			player.swingHand(Hand.MAIN_HAND);
			player.resetTicksSinceLastAttack();
			hitCooldownUntilNanos = now + HIT_COOLDOWN_NS;
		}
	}

	private Entity freshTarget(MinecraftClient client, ClientPlayerEntity player) {
		Entity camera = client.getCameraEntity();
		HitResult hit = player.getCrosshairTarget(1.0F, camera == null ? player : camera);
		return hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
	}

	/** Nearest fireball whose flight path passes within {@link #THREAT_RADIUS} of the player. */
	private AbstractFireballEntity findIncomingFireball(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d center = player.getEntityPos().add(0.0, 1.0, 0.0); // chest height
		AbstractFireballEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : client.world.getEntities()) {
			if (!(e instanceof AbstractFireballEntity fb)) continue;
			double dist = fb.getEntityPos().distanceTo(center);
			if (dist > SCAN_RANGE) continue;

			Vec3d vel = fb.getVelocity();
			if (vel.lengthSquared() < 1.0E-4) continue;
			Vec3d toCenter = center.subtract(fb.getEntityPos());
			if (toCenter.dotProduct(vel) <= 0.0) continue; // travelling away

			Vec3d dir = vel.normalize();
			double along = toCenter.dotProduct(dir);
			Vec3d closest = fb.getEntityPos().add(dir.multiply(along));
			if (closest.distanceTo(center) > THREAT_RADIUS) continue;

			if (dist < bestDist) {
				bestDist = dist;
				best = fb;
			}
		}
		return best;
	}

	private int hotbarSword(ClientPlayerEntity player) {
		for (int i = 0; i <= 8; i++) {
			if (player.getInventory().getStack(i).isIn(ItemTags.SWORDS)) return i;
		}
		return -1;
	}

	private void restore(ClientPlayerEntity player) {
		if (swapped && player != null && savedSlot >= 0 && savedSlot < 9) {
			player.getInventory().setSelectedSlot(savedSlot);
		}
		swapped = false;
		savedSlot = -1;
		swordReadyAge = 0;
	}
}
