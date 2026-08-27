package com.profps.client.donutsmp;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Rotation engine that emits mouse-like view deltas, driven per tick ({@link #aimAt})
 * or per render frame ({@link #aimFrame}).
 */
public final class HumanizedAim {
	private final Random random = new Random();


	// Low-pass filtered angular velocity, degrees per tick.
	private float yawVelocity;
	private float pitchVelocity;

	// Sub-GCD remainders carried between updates so small steps accumulate into whole mouse counts.
	private float yawCarry;
	private float pitchCarry;

	// Smoothed sub-degree drift layered on top of the goal.
	private float tremorYaw;
	private float tremorPitch;
	private float tremorYawTarget;
	private float tremorPitchTarget;
	private float tremorTicks;

	// Drifting responsiveness so turn speed is never constant.
	private float responsiveness;
	private float responsivenessTicks;

	// Overshoot impulse that decays back toward the target.
	private float overshootYaw;
	private float overshootPitch;
	private float overshootCooldown;

	// Reaction delay, curved turn path, mid-turn hesitation.
	private float lastGoalYaw = Float.NaN;
	private float lastGoalPitch;
	private float reactionTicks;
	private float arcBias;
	private float hesitationTicks;
	private float hesitationCooldown;

	public HumanizedAim() {

		this.responsiveness = 0.85f + random.nextFloat() * 0.35f;
		this.responsivenessTicks = 18 + random.nextInt(28);
		this.tremorTicks = 6 + random.nextInt(18);
		rerollTremor();
	}

	/** Tick-cadence wrapper for controllers that only update 20 times a second. */
	public boolean aimAt(ClientPlayerEntity player, Vec3d target, float speedScale) {
		return aimFrame(player, target, speedScale, 1.0F);
	}

	/**
	 * Advances the player's view toward {@code target} by one update.
	 *
	 * @param speedScale 1.0 = normal mining cadence, &lt;1.0 = calmer tracking
	 * @param dtTicks    elapsed time in tick units; all internal rates scale with this
	 * @return true once the view is on target
	 */
	public boolean aimFrame(ClientPlayerEntity player, Vec3d target, float speedScale, float dtTicks) {
		float dt = MathHelper.clamp(dtTicks, 0.01F, 2.0F);
		Vec3d eye = player.getEyePos();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		float wantedYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float wantedPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

		float realYawError = MathHelper.wrapDegrees(wantedYaw - player.getYaw());
		float realPitchError = MathHelper.wrapDegrees(wantedPitch - player.getPitch());
		float error = Math.max(Math.abs(realYawError), Math.abs(realPitchError));
		boolean onTarget = Math.abs(realYawError) < 8.0F && Math.abs(realPitchError) < 8.0F;

		// A goal jump past 12 degrees starts a reaction delay and rerolls the arc bias.
		if (!Float.isNaN(lastGoalYaw)) {
			float goalJump = Math.max(
					Math.abs(MathHelper.wrapDegrees(wantedYaw - lastGoalYaw)),
					Math.abs(wantedPitch - lastGoalPitch));
			if (goalJump > 12.0F) {
				reactionTicks = 1 + random.nextInt(4);
				arcBias = (random.nextFloat() - 0.5F) * 0.16F;
			}
		}
		lastGoalYaw = wantedYaw;
		lastGoalPitch = wantedPitch;

		if (reactionTicks > 0.0F) {
			reactionTicks -= dt;
			// Coast during the reaction gap; no new steering input.
			float coast = (float) Math.pow(0.72, dt);
			yawVelocity *= coast;
			pitchVelocity *= coast;
			apply(player, yawVelocity * dt, pitchVelocity * dt);
			return onTarget;
		}

		// Tremor applies only once settled; active turns steer at the bare target.
		updateTremor(dt);
		boolean settled = error < 2.2F;
		float goalYaw = wantedYaw + (settled ? tremorYaw : 0.0F);
		float goalPitch = wantedPitch + (settled ? tremorPitch : 0.0F);
		float yawDelta = MathHelper.wrapDegrees(goalYaw - player.getYaw());
		float pitchDelta = MathHelper.wrapDegrees(goalPitch - player.getPitch());

		updateResponsiveness(dt);
		updateHesitation(dt, error);

		// Ease-out: pull a fraction of the remaining delta that grows with distance.
		float ease = MathHelper.clamp(error / 35.0F, 0.05F, 1.0F);
		float factor = MathHelper.clamp(0.22F * speedScale * responsiveness * ease, 0.0F, 0.5F);
		if (hesitationTicks > 0.0F) factor *= 0.30F;
		float desiredYawStep = yawDelta * factor;
		float desiredPitchStep = pitchDelta * factor * 0.85F;

		// Momentum low-pass, time-scaled so any frame rate traces the same curve.
		float blend = 1.0F - (float) Math.pow(0.60, dt);
		yawVelocity += (desiredYawStep - yawVelocity) * blend;
		pitchVelocity += (desiredPitchStep - pitchVelocity) * blend;

		// Bleed off residual velocity once settled so the view does not hunt around the target.
		if (settled) {
			float bleed = (float) Math.pow(0.45, dt);
			yawVelocity *= bleed;
			pitchVelocity *= bleed;
		}

		// Angular-speed ceiling in degrees per tick; pitch is capped slightly lower.
		float maxYaw = 9.0F * speedScale;
		float maxPitch = 7.0F * speedScale;
		float yawStep = MathHelper.clamp(yawVelocity, -maxYaw, maxYaw);
		float pitchStep = MathHelper.clamp(pitchVelocity, -maxPitch, maxPitch);

		// While turning, bow pitch with the yaw drag so the path arcs.
		if (error > 5.0F) {
			pitchStep = MathHelper.clamp(pitchStep + arcBias * Math.abs(yawStep), -maxPitch, maxPitch);
		}

		// Overshoot applies to fast, large turns only.
		updateOvershoot(dt, error);
		yawStep += overshootYaw;
		pitchStep += overshootPitch;

		apply(player, yawStep * dt, pitchStep * dt);
		return onTarget;
	}

	/** Snaps the delta to the mouse GCD and applies it, carrying the sub-unit remainder forward. */
	private void apply(ClientPlayerEntity player, float yawStep, float pitchStep) {
		float yawWanted = yawStep + yawCarry;
		float pitchWanted = pitchStep + pitchCarry;
		float yawApplied = quantize(yawWanted);
		float pitchApplied = quantize(pitchWanted);
		yawCarry = yawWanted - yawApplied;
		pitchCarry = pitchWanted - pitchApplied;

		if (yawApplied != 0.0F) player.setYaw(MathHelper.wrapDegrees(player.getYaw() + yawApplied));
		if (pitchApplied != 0.0F) player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -89.0F, 89.0F));
	}

	private void updateHesitation(float dt, float error) {
		if (hesitationCooldown > 0.0F) hesitationCooldown -= dt;
		if (hesitationTicks > 0.0F) {
			hesitationTicks -= dt;
			return;
		}
		if (error > 6.0F && hesitationCooldown <= 0.0F && random.nextFloat() < 0.05F * dt) {
			hesitationTicks = 1 + random.nextInt(2);
			hesitationCooldown = 18 + random.nextInt(24);
		}
	}

	/** Snaps a raw degree delta to the nearest whole multiple of the mouse GCD. */
	private float quantize(float delta) {
		return com.profps.client.aim.MouseGcd.quantize(delta);
	}

	private void updateTremor(float dt) {
		tremorTicks -= dt;
		if (tremorTicks <= 0.0F) {
			rerollTremor();
			tremorTicks = 6 + random.nextInt(20);
		}
		// Lerp toward the current tremor target so motion stays smooth.
		float blend = 1.0F - (float) Math.pow(0.88, dt);
		tremorYaw += (tremorYawTarget - tremorYaw) * blend;
		tremorPitch += (tremorPitchTarget - tremorPitch) * blend;
	}

	private void rerollTremor() {
		// Sub-degree amplitude.
		tremorYawTarget = (random.nextFloat() - 0.5F) * 0.22F;
		tremorPitchTarget = (random.nextFloat() - 0.5F) * 0.16F;
	}

	private void updateResponsiveness(float dt) {
		responsivenessTicks -= dt;
		if (responsivenessTicks <= 0.0F) {
			float goal = 0.78f + random.nextFloat() * 0.42f;
			responsiveness += (goal - responsiveness) * 0.5f;
			responsivenessTicks = 16 + random.nextInt(30);
		}
	}

	private void updateOvershoot(float dt, float error) {
		// Decay any active overshoot.
		float decay = (float) Math.pow(0.55, dt);
		overshootYaw *= decay;
		overshootPitch *= decay;
		if (Math.abs(overshootYaw) < 0.02F) overshootYaw = 0.0F;
		if (Math.abs(overshootPitch) < 0.02F) overshootPitch = 0.0F;

		if (overshootCooldown > 0.0F) {
			overshootCooldown -= dt;
			return;
		}
		// Gate on error above 14 degrees and velocity above 4 degrees per tick.
		float speed = Math.max(Math.abs(yawVelocity), Math.abs(pitchVelocity));
		if (error > 14.0F && speed > 4.0F && random.nextFloat() < 0.18F * dt) {
			overshootYaw = Math.signum(yawVelocity) * (0.6F + random.nextFloat() * 1.6F);
			overshootPitch = Math.signum(pitchVelocity) * (0.3F + random.nextFloat() * 0.9F);
			overshootCooldown = 14 + random.nextInt(26);
		}
	}
}
