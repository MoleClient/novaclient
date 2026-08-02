package com.profps.client.aim;

import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatMode;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.security.SecureRandom;
import java.util.UUID;

public final class AimImprovementsController {
	// Keep assist within ~4 blocks — same range the player fights in.
	// Correcting rotation toward players at 5-6 blocks while no attack follows
	// is a recognisable AimAssist pattern for server-side ACs.
	private static final double MAX_DISTANCE_SQUARED = 16.0D;
	// Acquire the player you're AIMING at: must be within this cone of your look ray so it
	// only engages when you're genuinely pointing at someone (not a blatant snap-to-target).
	private static final long HITCH_BASE_NANOS = 40_000_000L;
	private static final long HITCH_RANDOM_NANOS = 70_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom random = new SecureRandom();

	/**
	 * Simulated mouse-sensitivity GCD. Real mouse input always produces rotation
	 * deltas that are whole multiples of a sensitivity-derived base unit; a raw
	 * float spring output fails that check on Grim/Intave instantly. Every delta
	 * we emit is snapped to this unit, with the sub-unit remainder carried to the
	 * next frame exactly like fractional mouse counts accumulate in a real sensor.
	 */
	private float yawCarry;
	private float pitchCarry;

	private UUID targetUuid;
	private Vec3d aimPoint;
	private long activeUntilNanos;
	private long nextRetargetNanos;
	private long lastFrameNanos;
	private long reactionReadyNanos;
	private long hitchUntilNanos;

	private double driftX, driftY, driftZ;
	private double driftVX, driftVY, driftVZ;

	private float yawVelocity;
	private float pitchVelocity;

	// Flick overshoot/settle — a real wrist flick blows slightly past a fast target
	// and corrects back. Decaying angular impulse (deg/sec) layered on the spring.
	private double overshootYaw;
	private double overshootPitch;
	private long overshootReadyNanos;
	private double arcBias;

	private double tremorPhase1, tremorPhase2, tremorPhase3;
	private double tremorPhaseAlt1, tremorPhaseAlt2;
	private double tremorRate1, tremorRate2, tremorRate3;
	private double tremorRateAlt1, tremorRateAlt2;

	private Vec3d lastTargetCenter;
	private Vec3d targetVelocity = Vec3d.ZERO;

	public AimImprovementsController(ProFPSConfig config) {
		this.config = config;
		rerollTremor();
	}

	public void tick(MinecraftClient client) {
		if (!isAllowed(client)) {
			clear();
			return;
		}

		PlayerEntity target = target(client);
		if (target == null || !target.isAlive() || target.squaredDistanceTo(client.player) > MAX_DISTANCE_SQUARED) {
			clear();
		}
	}

	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
		}
		double dt = Math.min(0.05D, (now - lastFrameNanos) / 1_000_000_000.0D);
		lastFrameNanos = now;

		advanceTremor(dt);

		if (!isAllowed(client)) {
			float decay = (float) Math.exp(-dt * 5.5D);
			yawVelocity *= decay;
			pitchVelocity *= decay;
			return;
		}

		// Engage the player you're AIMING at — this is what makes it an aim ASSIST and not
		// a do-nothing-until-you-hit. markAttack still locks/extends after a landed hit.
		acquireByLook(client, now);

		PlayerEntity target = target(client);
		if (target == null || !target.isAlive() || now > activeUntilNanos) {
			float decay = (float) Math.exp(-dt * 5.5D);
			yawVelocity *= decay;
			pitchVelocity *= decay;
			return;
		}

		Vec3d center = target.getBoundingBox().getCenter();
		if (lastTargetCenter != null && dt > 0.0001D) {
			Vec3d instant = center.subtract(lastTargetCenter).multiply(1.0D / dt);
			double blend = 1.0D - Math.exp(-dt * 9.0D);
			targetVelocity = targetVelocity.add(instant.subtract(targetVelocity).multiply(blend));
		}
		lastTargetCenter = center;

		if (now < reactionReadyNanos) {
			return;
		}

		CombatModeProfile.MeleeAim tuning = tuning();
		if (aimPoint == null || (tuning.retargeting() && now >= nextRetargetNanos)) {
				aimPoint = randomizedPoint(target.getBoundingBox());
				seedDrift();
				rerollArc();
				nextRetargetNanos = now + tuning.retargetIntervalMs() * 1_000_000L
						+ random.nextLong(70_000_000L);
			}

		updateDrift(dt, target.getBoundingBox());

		if (now >= hitchUntilNanos && random.nextDouble() < tuning.hitchChancePerSecond() * dt) {
			hitchUntilNanos = now + HITCH_BASE_NANOS + random.nextLong(HITCH_RANDOM_NANOS);
		}
		if (now < hitchUntilNanos) {
			float decay = (float) Math.exp(-dt * 7.0D);
			yawVelocity *= decay;
			pitchVelocity *= decay;
			return;
		}

		double leadFactor = tuning.leadMinSeconds()
				+ random.nextDouble() * (tuning.leadMaxSeconds() - tuning.leadMinSeconds());
		Vec3d leaded = aimPoint
				.add(targetVelocity.multiply(leadFactor))
				.add(driftX, driftY, driftZ);

		double engagement = MathHelper.clamp(
				(now - reactionReadyNanos) / (double) (tuning.engagementRampMs() * 1_000_000L), 0.0D, 1.0D);
		engagement = engagement * engagement * (3.0D - 2.0D * engagement);

		// Soft-stop behind cover: you can't precisely track someone through a
		// wall, so when the aim point is occluded the assist nearly lets go (it
		// keeps a faint pull so it re-acquires cleanly when they reappear). A
		// laser tracking a target through terrain is an obvious assist tell.
		if (occluded(client, client.player, leaded)) {
			engagement *= 0.22D;
		}

		turnToward(client.player, leaded, dt, engagement);
	}

	/** True when a solid block sits between the eye and the aim point. */
	private boolean occluded(MinecraftClient client, ClientPlayerEntity player, Vec3d point) {
		net.minecraft.util.hit.HitResult block = client.world.raycast(new RaycastContext(
				player.getEyePos(), point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
		return block.getType() != net.minecraft.util.hit.HitResult.Type.MISS;
	}

	public void markAttack(MinecraftClient client, Entity entity) {
		if (!isAllowed(client) || !(entity instanceof PlayerEntity player) || entity == client.player) {
			return;
		}
		if (client.player.isUsingItem()) {
			return;
		}

		long now = System.nanoTime();
		boolean sameFight = player.getUuid().equals(targetUuid) && now < activeUntilNanos;

		targetUuid = player.getUuid();
		aimPoint = randomizedPoint(player.getBoundingBox());
		seedDrift();
		CombatModeProfile.MeleeAim tuning = tuning();
		activeUntilNanos = now + tuning.durationMs() * 1_000_000L;
		nextRetargetNanos = now + Math.min(45L, tuning.retargetIntervalMs()) * 1_000_000L;

		if (sameFight) {
			// Mid-fight follow-up: keep guiding seamlessly. Resetting the
			// reaction delay here made aim go soft right after every hit —
			// exactly when a strafing target slips off the crosshair.
			return;
		}

		reactionReadyNanos = now + reactionNanos();
		hitchUntilNanos = 0L;
		rerollTremor();
		rerollArc();
		lastTargetCenter = null;
		targetVelocity = Vec3d.ZERO;
		yawVelocity = 0.0F;
		pitchVelocity = 0.0F;
	}

	public String status(MinecraftClient client) {
		if (!CombatModePolicy.enabled(config, CombatFeature.MELEE_AIM)) {
			return "Off";
		}
		if (!isAllowed(client)) {
			return "Idle";
		}
		if (targetUuid == null) {
			return "Waiting for hit";
		}
		if (System.nanoTime() < reactionReadyNanos) {
			return "Reacting";
		}
		return "Guiding";
	}

	/** Reaction delay (nanos) before the assist starts pulling onto a fresh target — from the Reaction slider. */
	private long reactionNanos() {
		double ms = Math.max(0, tuning().reactionMs()) * (0.6D + random.nextDouble() * 0.9D);
		return (long) (ms * 1_000_000.0D);
	}

	private boolean isAllowed(MinecraftClient client) {
		if (!CombatModePolicy.enabled(config, CombatFeature.MELEE_AIM)) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null) return false;
		// Don't adjust aim in spectator — it sends rotation packets for no reason
		if (client.player.isSpectator()) return false;
		CombatMode mode = CombatModePolicy.mode(config);
		if (mode == CombatMode.SWORD && !client.player.getMainHandStack().isIn(ItemTags.SWORDS)) return false;
		if (mode == CombatMode.AXE
				&& !client.player.getMainHandStack().isIn(ItemTags.AXES)
				&& !client.player.getMainHandStack().isIn(ItemTags.SWORDS)) return false;
		return true;
	}

	/**
	 * Pick the player closest to your look ray, inside the effective tier FOV and melee
	 * range, and engage them. Fresh acquisition stamps a human reaction delay; while you
	 * keep aiming at the same player the engagement is renewed so it tracks continuously.
	 */
	private void acquireByLook(MinecraftClient client, long now) {
		ClientPlayerEntity self = client.player;
		if (self == null || client.world == null) return;

		Vec3d eye = self.getEyePos();
		Vec3d look = self.getRotationVector();
		PlayerEntity best = null;
		CombatModeProfile.MeleeAim tuning = tuning();
		double bestDot = Math.cos(Math.toRadians(tuning.fovDeg()));
		for (PlayerEntity pl : client.world.getPlayers()) {
			if (pl == self || !pl.isAlive()) continue;
			if (pl.squaredDistanceTo(self) > MAX_DISTANCE_SQUARED) continue;
			Vec3d to = pl.getBoundingBox().getCenter().subtract(eye);
			double len = to.length();
			if (len < 1.0E-4D) continue;
			double dot = look.dotProduct(to.multiply(1.0D / len));
			if (dot > bestDot) {
				bestDot = dot;
				best = pl;
			}
		}
		if (best == null) return;

		if (!best.getUuid().equals(targetUuid)) {
			// New target picked up by aim — react like a person noticing them.
			targetUuid = best.getUuid();
			aimPoint = randomizedPoint(best.getBoundingBox());
			seedDrift();
			reactionReadyNanos = now + reactionNanos();
			hitchUntilNanos = 0L;
			rerollTremor();
			rerollArc();
			lastTargetCenter = null;
			targetVelocity = Vec3d.ZERO;
			yawVelocity = 0.0F;
			pitchVelocity = 0.0F;
		}
		// Keep the engagement alive while you keep aiming at them (markAttack may extend it further).
		long lookHoldNanos = tuning.lookHoldMs() * 1_000_000L;
		if (activeUntilNanos < now + lookHoldNanos) {
			activeUntilNanos = now + lookHoldNanos;
		}
	}

	private PlayerEntity target(MinecraftClient client) {
		if (targetUuid == null || client.world == null) {
			return null;
		}
		for (PlayerEntity player : client.world.getPlayers()) {
			if (targetUuid.equals(player.getUuid())) {
				return player;
			}
		}
		return null;
	}

	private Vec3d randomizedPoint(Box box) {
		double width  = Math.max(0.05D, box.maxX - box.minX);
		double depth  = Math.max(0.05D, box.maxZ - box.minZ);
		double height = Math.max(0.05D, box.maxY - box.minY);

		// Prefer centre-body / upper-chest (y 40-75% from bottom) — keeps the
		// aim inside the hitbox where both the ray-confirm and the server
		// reach-check are most stable, rather than targeting the head or feet
		// which can clip out of the box during natural movement.
		double x = box.minX + width * biasedUnit(0.35D);
		double y = box.minY + height * (0.40D + random.nextDouble() * 0.35D);
		double z = box.minZ + depth * biasedUnit(0.35D);

		if (random.nextDouble() < tuning().missChance()) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			double miss  = 0.06D + random.nextDouble() * 0.12D; // smaller miss offset
			x += Math.cos(angle) * miss;
			z += Math.sin(angle) * miss;
			y += (random.nextDouble() - 0.5D) * 0.08D;
		}

		return new Vec3d(x, MathHelper.clamp(y, box.minY + 0.08D, box.maxY - 0.08D), z);
	}

	private double biasedUnit(double centerAvoidance) {
		double value = random.nextDouble();
		if (Math.abs(value - 0.5D) < centerAvoidance * 0.25D) {
			value += value < 0.5D ? -centerAvoidance * random.nextDouble() : centerAvoidance * random.nextDouble();
		}
		return MathHelper.clamp(value, 0.08D, 0.92D);
	}

	private void seedDrift() {
		driftX = (random.nextDouble() - 0.5D) * 0.04D;
		driftY = (random.nextDouble() - 0.5D) * 0.04D;
		driftZ = (random.nextDouble() - 0.5D) * 0.04D;
		driftVX = (random.nextDouble() - 0.5D) * 0.06D;
		driftVY = (random.nextDouble() - 0.5D) * 0.06D;
		driftVZ = (random.nextDouble() - 0.5D) * 0.06D;
	}

	private void updateDrift(double dt, Box box) {
		// Tighter drift: stays closer to the aim point so the hit-confirm
		// raycast stays well inside the hitbox between aim-point updates.
		double scale = 0.09D * Math.max(box.maxY - box.minY, 0.5D);
		double restore = 6.0D;
		double impulse = 0.9D;

		driftVX += ((random.nextDouble() - 0.5D) * impulse - driftX * restore) * dt;
		driftVY += ((random.nextDouble() - 0.5D) * impulse - driftY * restore) * dt;
		driftVZ += ((random.nextDouble() - 0.5D) * impulse - driftZ * restore) * dt;

		double damp = Math.exp(-dt * 4.0D);
		driftVX *= damp;
		driftVY *= damp;
		driftVZ *= damp;

		driftX = MathHelper.clamp(driftX + driftVX * dt, -scale, scale);
		driftY = MathHelper.clamp(driftY + driftVY * dt * 0.6D, -scale * 0.5D, scale * 0.5D);
		driftZ = MathHelper.clamp(driftZ + driftVZ * dt, -scale, scale);
	}

	private void rerollTremor() {
		tremorRate1 = 1.6D + random.nextDouble() * 1.4D;
		tremorRate2 = 3.4D + random.nextDouble() * 3.0D;
		tremorRate3 = 7.5D + random.nextDouble() * 5.5D;
		tremorRateAlt1 = 1.9D + random.nextDouble() * 1.5D;
		tremorRateAlt2 = 4.8D + random.nextDouble() * 3.5D;
		tremorPhase1 = random.nextDouble() * Math.PI * 2.0D;
		tremorPhase2 = random.nextDouble() * Math.PI * 2.0D;
		tremorPhase3 = random.nextDouble() * Math.PI * 2.0D;
		tremorPhaseAlt1 = random.nextDouble() * Math.PI * 2.0D;
		tremorPhaseAlt2 = random.nextDouble() * Math.PI * 2.0D;
	}

	private void rerollArc() {
		arcBias = (random.nextDouble() - 0.5D) * 0.18D;
	}

	private void advanceTremor(double dt) {
		tremorPhase1 += dt * tremorRate1;
		tremorPhase2 += dt * tremorRate2;
		tremorPhase3 += dt * tremorRate3;
		tremorPhaseAlt1 += dt * tremorRateAlt1;
		tremorPhaseAlt2 += dt * tremorRateAlt2;
	}

	private double tremorYaw() {
		return Math.sin(tremorPhase1) * 0.55D
				+ Math.sin(tremorPhase2 * 1.27D + 0.7D) * 0.30D
				+ Math.sin(tremorPhase3 * 0.91D + 1.3D) * 0.15D;
	}

	private double tremorPitch() {
		return Math.sin(tremorPhaseAlt1 * 1.13D + 1.9D) * 0.55D
				+ Math.sin(tremorPhaseAlt2 * 0.83D + 2.6D) * 0.30D
				+ Math.sin(tremorPhase3 * 1.07D + 0.4D) * 0.15D;
	}

	private void turnToward(ClientPlayerEntity player, Vec3d point, double dt, double engagement) {
		Vec3d eye = player.getEyePos();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (MathHelper.atan2(dz, dx) * 57.2957763671875D) - 90.0F;
		float desiredPitch = (float) (-(MathHelper.atan2(dy, horizontal) * 57.2957763671875D));
		float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchDelta = MathHelper.wrapDegrees(desiredPitch - player.getPitch());

		double strength = effectiveStrength();

		float deltaMag = (float) Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
		// Pull onto the body harder than before. Floor raised (0.30 → 0.46) and the
		// base spring frequency bumped so the crosshair actually closes the last few
		// degrees onto a strafing target — "move to the player more" — instead of
		// politely hovering beside them. The tremor/breath/overshoot below keep it
		// from ever sitting dead-centre, which is what the soft floor used to buy.
		double closeness = MathHelper.clamp(deltaMag / 14.0F, 0.46D, 1.0D);

		double omegaYaw = (5.4D + strength * 12.0D) * closeness * (1.0D + 0.10D * tremorYaw()) * engagement;
		double omegaPitch = (4.8D + strength * 10.4D) * closeness * (1.0D + 0.10D * tremorPitch()) * engagement;

		double yawAccel = omegaYaw * omegaYaw * yawDelta - 2.0D * omegaYaw * yawVelocity;
		double pitchAccel = omegaPitch * omegaPitch * pitchDelta - 2.0D * omegaPitch * pitchVelocity;

		yawVelocity += (float) (yawAccel * dt);
		pitchVelocity += (float) (pitchAccel * dt);

		float yawStep = (float) (yawVelocity * dt);
		float pitchStep = (float) (pitchVelocity * dt);

		double breathYaw = tremorYaw() * (1.35D + 0.85D * (1.0D - strength)) * engagement;
		double breathPitch = tremorPitch() * (1.05D + 0.65D * (1.0D - strength)) * engagement;
		yawStep += (float) (breathYaw * dt);
		pitchStep += (float) (breathPitch * dt);

		// Subtle curved approach: while turning across the target, couple a little
		// yaw drag into pitch so the path bows instead of drawing a straight line.
		if (deltaMag > 3.0F && Math.abs(yawDelta) > 1.2F) {
			pitchStep += (float) (arcBias * Math.abs(yawStep) * engagement);
		}

		// Flick overshoot on big, fast turns: blow slightly past, then the spring
		// drags it back — the small "weird" wobble a real hand makes on a snap.
		updateOvershoot(dt, deltaMag);
		yawStep += (float) (overshootYaw * dt);
		pitchStep += (float) (overshootPitch * dt);

		// Cap to a human-plausible rotation speed that varies per frame via tremor,
		// so it doesn't produce a fixed-magnitude fingerprint
		double speedScale = 0.55D + strength * 0.50D + tremorYaw() * 0.04D;
		float maxStep = (float) (220.0D * dt * speedScale);
		yawStep = MathHelper.clamp(yawStep, -maxStep, maxStep);
		pitchStep = MathHelper.clamp(pitchStep, -maxStep * 0.76F, maxStep * 0.76F);

		// Snap the emitted delta to the simulated mouse GCD (carry the remainder),
		// so the rotation packet looks like genuine integer mouse counts.
		float yawWanted = yawStep + yawCarry;
		float pitchWanted = pitchStep + pitchCarry;
		float yawApplied = quantize(yawWanted);
		float pitchApplied = quantize(pitchWanted);
		yawCarry = yawWanted - yawApplied;
		pitchCarry = pitchWanted - pitchApplied;

		if (yawApplied != 0.0F) player.setYaw(player.getYaw() + yawApplied);
		if (pitchApplied != 0.0F) player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -90.0F, 90.0F));
		player.headYaw = player.getYaw();
		player.bodyYaw = smoothBodyYaw(player.bodyYaw, player.getYaw(), dt);
	}

	/**
	 * Decaying flick overshoot. Only fires on large, fast turns and only sometimes,
	 * so most tracking is clean; when it does, the head briefly carries past the
	 * target and the spring reels it back — organic motion a pure ease never makes.
	 */
	private void updateOvershoot(double dt, float errorMag) {
		double decay = Math.exp(-dt * 8.0D);
		overshootYaw *= decay;
		overshootPitch *= decay;
		if (Math.abs(overshootYaw) < 0.4D) overshootYaw = 0.0D;
		if (Math.abs(overshootPitch) < 0.4D) overshootPitch = 0.0D;

		long now = System.nanoTime();
		if (now < overshootReadyNanos) return;
		float speed = Math.max(Math.abs(yawVelocity), Math.abs(pitchVelocity));
		if (errorMag > 12.0F && speed > 60.0F && random.nextDouble() < 0.9D * dt) {
			double mag = 18.0D + random.nextDouble() * 40.0D; // deg/sec
			overshootYaw = Math.signum(yawVelocity) * mag;
			overshootPitch = Math.signum(pitchVelocity) * mag * 0.5D;
			overshootReadyNanos = now + 220_000_000L + (long) (random.nextDouble() * 280_000_000L);
		}
	}

	private double effectiveStrength() {
		return MathHelper.clamp(tuning().strengthPct(), 15, 90) / 100.0D;
	}

	private CombatModeProfile.MeleeAim tuning() {
		return CombatModePolicy.meleeAim(config);
	}

	/** Snap a raw degree delta to the nearest whole multiple of the mouse GCD. */
	private float quantize(float delta) {
		return MouseGcd.quantize(delta); // snap to the player's real live mouse grid
	}

	private float smoothBodyYaw(float current, float target, double dt) {
		float delta = MathHelper.wrapDegrees(target - current);
		float step = (float) (delta * (1.0D - Math.exp(-dt * 6.5D)));
		return current + MathHelper.clamp(step, -7.5F, 7.5F);
	}

	private void clear() {
		targetUuid = null;
		aimPoint = null;
		activeUntilNanos = 0L;
		nextRetargetNanos = 0L;
		reactionReadyNanos = 0L;
		hitchUntilNanos = 0L;
		lastTargetCenter = null;
		targetVelocity = Vec3d.ZERO;
		overshootYaw = 0.0D;
		overshootPitch = 0.0D;
		overshootReadyNanos = 0L;
		arcBias = 0.0D;
	}
}
