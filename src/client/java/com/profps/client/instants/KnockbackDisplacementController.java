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
 * Knockback Displacement — a momentary, keybind-fired PvP move (no toggle; like Auto Lunge).
 *
 * <p>This is NOT anti-knockback or packet trickery. It's the legit vanilla displacement technique
 * automated: tap your sprint (W-reset) so the swing counts as a fresh SPRINT attack, frame-smoothly
 * put your view on the nearest player, then land a sprint hit while you're moving into them. Vanilla
 * knockback is server-side velocity along the attacker→victim line plus the sprint bonus, so a sprint
 * hit taken head-on shoves them away from you — backwards, against their approach — instead of letting
 * them walk through you. Everything here is real input (keys + a normal attack) with a humanized aim,
 * so there's nothing fake for movement/velocity checks to catch.
 *
 * <p>Press the bound key (default none): if a player is in range it fires once.
 */
public final class KnockbackDisplacementController {
	private static final double ENGAGE_RANGE = 4.5D; // acquire within this; only SWING within ~3.0
	private static final long WINDOW_NANOS = 850_000_000L; // give up if it can't land in time

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

		applyKeys(client); // forward+sprint, except the brief sprint-reset tap

		if (now < nextActionNanos) return;

		switch (phase) {
			case RESET -> {
				// the sprint-tap window just elapsed (sprint was released by applyKeys) — re-engage
				phase = Phase.ENGAGE;
			}
			case ENGAGE -> {
				ClientPlayerEntity player = client.player;
				player.setSprinting(true); // make sure the server sees a sprint hit
				Vec3d eye = player.getEyePos();
				Vec3d point = aimPoint(target);
				double dist = point.distanceTo(eye);
				if (dist > 3.0D) return; // not in swing range yet — keep closing + aiming

				Vec3d look = player.getRotationVec(1.0F);
				double dot = dist < 0.1 ? 1.0 : point.subtract(eye).multiply(1.0 / dist).dotProduct(look);
				boolean crosshairOn = client.crosshairTarget instanceof EntityHitResult ehr
						&& ehr.getType() == HitResult.Type.ENTITY && ehr.getEntity() == target;
				EntityHitResult fresh = freshEntityHit(client, player);
				if (!crosshairOn || fresh == null || fresh.getEntity() != target || dot < 0.992D) return;

				if (player.getAttackCooldownProgress(0.0F) >= 0.9F) {
					if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.KB_DISPLACE)) return;
					client.interactionManager.attackEntity(player, target);
					player.swingHand(Hand.MAIN_HAND); // sprint hit → knockback away from us (backwards for them)
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

		float yawStep = yawErr * k + (float) (rng.nextGaussian() * 0.28D);   // micro-tremor
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
			nextActionNanos = now + 55_000_000L; // ~1 tick sprint-tap (W-reset)
		} else {
			phase = Phase.ENGAGE;
			nextActionNanos = now;
		}
	}

	/** Hold forward + sprint through the move; drop sprint only for the brief reset tap. */
	private void applyKeys(MinecraftClient client) {
		boolean resetTap = phase == Phase.RESET;
		client.options.forwardKey.setPressed(!resetTap); // release W during the tap, then re-press
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
		fy = 0.50 + rng.nextDouble() * 0.25; // upper chest
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
