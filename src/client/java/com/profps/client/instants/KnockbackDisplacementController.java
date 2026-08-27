package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Keybind-fired sprint-hit displacement. Taps sprint to reset it, aims at the nearest player over
 * several frames, then lands a sprint attack so vanilla applies the sprint knockback bonus.
 */
public final class KnockbackDisplacementController {
	private static final double ENGAGE_RANGE = 4.5D; // acquisition range; swings only within 3.0
	private static final long WINDOW_NANOS = 850_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private enum Phase { IDLE, RESET, ENGAGE }
	private Phase phase = Phase.IDLE;
	private UUID targetUuid;
	private long nextActionNanos;
	private long deadlineNanos;
	private long lastFrameNanos;
	private double fx, fy, fz; // hitbox aim fractions
	private boolean originalForward;
	private boolean originalSprint;

	private volatile boolean aiming;

	public KnockbackDisplacementController(ProFPSConfig config) {
		this.config = config;
	}

	// ── Tick ────────────────────────────────────────────────────────────────────

	public void tick(MinecraftClient client) {
		if (config.kbDisplaceRequested) {
			config.kbDisplaceRequested = false;
			if (phase == Phase.IDLE && allowed(client)) begin(client);
		}
		if (phase == Phase.IDLE) return;
		if (!allowed(client)) { reset(client); return; }

		long now = System.nanoTime();
		PlayerEntity target = byUuid(client, targetUuid);
		if (target == null || now > deadlineNanos) { reset(client); return; }

		applyKeys(client);

		if (now < nextActionNanos) return;

		switch (phase) {
			case RESET -> {
				// The sprint-tap window has elapsed; applyKeys re-presses on the next tick.
				phase = Phase.ENGAGE;
			}
			case ENGAGE -> {
				ClientPlayerEntity player = client.player;
				player.setSprinting(true); // the server needs the sprint flag set at attack time
				Vec3d eye = player.getEyePos();
				Vec3d point = aimPoint(target);
				double dist = point.distanceTo(eye);
				if (dist > 3.0D) return; // keep closing and aiming

				Vec3d look = player.getRotationVec(1.0F);
				double dot = dist < 0.1 ? 1.0 : point.subtract(eye).multiply(1.0 / dist).dotProduct(look);
				boolean crosshairOn = client.crosshairTarget instanceof EntityHitResult ehr
						&& ehr.getType() == HitResult.Type.ENTITY && ehr.getEntity() == target;
				EntityHitResult fresh = freshEntityHit(client, player);
				if (!crosshairOn || fresh == null || fresh.getEntity() != target || dot < 0.992D) return;

				if (player.getAttackCooldownProgress(0.0F) >= 0.9F) {
					if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.KB_DISPLACE)) return;
					client.interactionManager.attackEntity(player, target);
					player.swingHand(Hand.MAIN_HAND);
					player.resetTicksSinceLastAttack();
					reset(client);
				}
			}
			case IDLE -> { }
		}
	}

	// ── Smooth aim (frame) ────────────────────────────────────────────────────────

	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0 * 20.0, 0.05, 4.0);
		lastFrameNanos = now;
		if (!aiming || !allowed(client)) return;
		ClientPlayerEntity player = client.player;
		PlayerEntity target = byUuid(client, targetUuid);
		if (target == null) return;

		Vec3d eye = player.getEyePos();
		Vec3d point = aimPoint(target);
		double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

		float yawErr = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchErr = MathHelper.wrapDegrees(desiredPitch - player.getPitch());

		float speed = MathHelper.clamp(config.kbDisplaceAimSpeed, 20, 95) / 100.0F;
		float k = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float cap = MathHelper.clamp(config.kbDisplaceAimSpeed, 20, 95) * 0.6F * dt;

		float yawStep = yawErr * k + (float) (rng.nextGaussian() * 0.28D);
		float pitchStep = pitchErr * k + (float) (rng.nextGaussian() * 0.20D);

		float yawApplied = mouse.yaw(MathHelper.clamp(yawStep, -cap, cap));
		float pitchApplied = mouse.pitch(MathHelper.clamp(pitchStep, -cap * 0.85F, cap * 0.85F));

		player.setYaw(player.getYaw() + yawApplied);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -90.0F, 90.0F));
	}

	// ── Sequence ──────────────────────────────────────────────────────────────────

	private void begin(MinecraftClient client) {
		PlayerEntity target = acquireTarget(client);
		if (target == null) {
			client.inGameHud.setOverlayMessage(net.minecraft.text.Text.literal("Displacement: no player in range"), false);
			return;
		}
		targetUuid = target.getUuid();
		pickPoint();
		aiming = true;
		originalForward = client.options.forwardKey.isPressed();
		originalSprint = client.options.sprintKey.isPressed();
		long now = System.nanoTime();
		deadlineNanos = now + WINDOW_NANOS;
		if (config.kbDisplaceReset) {
			phase = Phase.RESET;
			nextActionNanos = now + 55_000_000L; // roughly one tick of sprint-tap
		} else {
			phase = Phase.ENGAGE;
			nextActionNanos = now;
		}
	}

	/** Holds forward and sprint, releasing both during the reset tap. */
	private void applyKeys(MinecraftClient client) {
		boolean resetTap = phase == Phase.RESET;
		client.options.forwardKey.setPressed(!resetTap);
		client.options.sprintKey.setPressed(!resetTap);
		if (client.player != null && !resetTap) client.player.setSprinting(true);
	}

	private void releaseKeys(MinecraftClient client) {
		if (client.options == null) return;
		client.options.forwardKey.setPressed(originalForward);
		client.options.sprintKey.setPressed(originalSprint);
	}

	// ── Targeting ──────────────────────────────────────────────────────────────────

	private PlayerEntity acquireTarget(MinecraftClient client) {
		ClientPlayerEntity self = client.player;
		Vec3d eye = self.getEyePos();
		PlayerEntity best = null;
		double bestDist = ENGAGE_RANGE + 0.5D;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator() || !self.canSee(other)) continue;
			Vec3d delta = other.getBoundingBox().getCenter().subtract(eye);
			double dist = delta.length();
			if (dist > 1.0E-4D
					&& delta.normalize().dotProduct(self.getRotationVec(1.0F))
					< Math.cos(Math.toRadians(70.0D))) continue;
			if (dist < bestDist) { bestDist = dist; best = other; }
		}
		return best;
	}

	private EntityHitResult freshEntityHit(MinecraftClient client, ClientPlayerEntity player) {
		net.minecraft.entity.Entity camera = client.getCameraEntity();
		HitResult hit = player.getCrosshairTarget(1.0F, camera == null ? player : camera);
		return hit instanceof EntityHitResult entityHit ? entityHit : null;
	}

	private PlayerEntity byUuid(MinecraftClient client, UUID uuid) {
		if (uuid == null || client.world == null) return null;
		for (PlayerEntity p : client.world.getPlayers()) {
			if (p.getUuid().equals(uuid)) return p;
		}
		return null;
	}

	private Vec3d aimPoint(PlayerEntity target) {
		Box box = target.getBoundingBox();
		return new Vec3d(
				box.minX + (box.maxX - box.minX) * fx,
				box.minY + (box.maxY - box.minY) * fy,
				box.minZ + (box.maxZ - box.minZ) * fz);
	}

	private void pickPoint() {
		fx = 0.35 + rng.nextDouble() * 0.30;
		fy = 0.50 + rng.nextDouble() * 0.25;
		fz = 0.35 + rng.nextDouble() * 0.30;
	}

	// ── Misc ────────────────────────────────────────────────────────────────────────

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null) return false;
		return client.player.isAlive() && !client.player.isSpectator() && !client.player.hasVehicle();
	}

	private void reset(MinecraftClient client) {
		releaseKeys(client);
		phase = Phase.IDLE;
		aiming = false;
		targetUuid = null;
		nextActionNanos = 0L;
	}
}
