package com.profps.client.donutsmp;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Humanized rotation engine shared by the automated mining controllers.
 *
 * <p>Every rotation that leaves the client passes through here so that, from the
 * server's perspective, it is indistinguishable from a real player dragging a
 * mouse. The major tells that anti-cheats (Grim, NCP, Vulcan, Matrix, ...) look
 * for are addressed explicitly:
 *
 * <ul>
 *   <li><b>GCD / sensitivity quantization</b> - real mouse input always produces
 *       rotation deltas that are integer multiples of a sensitivity-derived base
 *       unit. Perfectly-computed float rotations fail this check instantly. Every
 *       delta we emit is snapped to a per-session simulated mouse GCD, with the
 *       sub-unit remainder carried to the next update exactly like fractional
 *       mouse counts accumulate in a real sensor.</li>
 *   <li><b>No head snapping</b> - angular velocity is momentum-smoothed (low-pass)
 *       with a human acceleration/deceleration curve and a hard per-tick cap, so
 *       the head never teleports to a target.</li>
 *   <li><b>Tremor / breathing</b> - the aim never sits perfectly still; a slow
 *       smoothed sub-degree drift is always layered on top, like a real hand.</li>
 *   <li><b>Overshoot &amp; settle</b> - fast turns occasionally overshoot the
 *       target slightly and correct back, the way a human flick does.</li>
 *   <li><b>Non-deterministic timing</b> - responsiveness, tremor and reroll
 *       cadence all drift continuously, so no two ticks (or two sessions) look
 *       the same.</li>
 * </ul>
 *
 * <p>The engine is time-based: callers can drive it once per tick
 * ({@link #aimAt}) or once per render frame ({@link #aimFrame}) with the real
 * elapsed time. Frame-driven rotation is what real mouse input looks like —
 * the camera moves smoothly every frame instead of stepping 20 times a second.
 */
public final class HumanizedAim {
	private final Random random = new Random();


	// momentum (low-pass filtered angular velocity in degrees/tick)
	private float yawVelocity;
	private float pitchVelocity;

	// sub-GCD remainders carried between updates so tiny per-frame steps still
	// accumulate into real mouse counts instead of rounding away to nothing
	private float yawCarry;
	private float pitchCarry;

	// smoothed tremor ("breathing") layered on top of the goal
	private float tremorYaw;
	private float tremorPitch;
	private float tremorYawTarget;
	private float tremorPitchTarget;
	private float tremorTicks;

	// drifting responsiveness so turn speed is never constant
	private float responsiveness;
	private float responsivenessTicks;

	// overshoot impulse that decays back toward the target
	private float overshootYaw;
	private float overshootPitch;
	private float overshootCooldown;

	// human reaction delay + curved turn path + mid-turn hesitation
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
	 * Nudge the player's view toward {@code target} by one humanized update.
	 *
	 * @param speedScale 1.0 = normal mining cadence, &lt;1.0 = calmer tracking.
	 * @param dtTicks    elapsed time in tick units (1.0 = one full tick; a 60fps
	 *                   frame is ~0.33). All internal rates scale with this.
	 * @return true once the view is essentially on target (real error small).
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

		// Human reaction time: when the goal jumps to a meaningfully different
		// direction, a real hand takes a few ticks to respond. Each new turn also
		// rolls a fresh arc bias so the path bows like a wrist drag instead of
		// tracing a perfectly straight line to the target.
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
			// Momentum coasts down during the reaction gap; no new steering input.
			float coast = (float) Math.pow(0.72, dt);
			yawVelocity *= coast;
			pitchVelocity *= coast;
			apply(player, yawVelocity * dt, pitchVelocity * dt);
			return onTarget;
		}

		// Tremor is a gentle "resting hand" sway, applied ONLY when we are already
		// on the block. While actively turning we steer at the bare target so the
		// motion is clean, not wobbly.
		updateTremor(dt);
		boolean settled = error < 2.2F;
		float goalYaw = wantedYaw + (settled ? tremorYaw : 0.0F);
		float goalPitch = wantedPitch + (settled ? tremorPitch : 0.0F);
		float yawDelta = MathHelper.wrapDegrees(goalYaw - player.getYaw());
		float pitchDelta = MathHelper.wrapDegrees(goalPitch - player.getPitch());

		updateResponsiveness(dt);
		updateHesitation(dt, error);

		// Smooth ease-out: pull a steady fraction of the remaining delta. The
		// fraction grows with distance (quick when far, gentle as it arrives).
		// No per-update randomness on the curve itself — that was the twitch.
		float ease = MathHelper.clamp(error / 35.0F, 0.05F, 1.0F);
		float factor = MathHelper.clamp(0.22F * speedScale * responsiveness * ease, 0.0F, 0.5F);
		// A brief mid-turn slowdown, like a hand checking its travel.
		if (hesitationTicks > 0.0F) factor *= 0.30F;
		float desiredYawStep = yawDelta * factor;
		float desiredPitchStep = pitchDelta * factor * 0.85F;

		// Momentum low-pass for natural acceleration/deceleration (time-scaled
		// so a 144fps frame and a 20tps tick trace the same curve).
		float blend = 1.0F - (float) Math.pow(0.60, dt);
		yawVelocity += (desiredYawStep - yawVelocity) * blend;
		pitchVelocity += (desiredPitchStep - pitchVelocity) * blend;

		// Bleed off residual velocity once we're basically there, so the head
		// comes to rest instead of hunting around the target forever.
		if (settled) {
			float bleed = (float) Math.pow(0.45, dt);
			yawVelocity *= bleed;
			pitchVelocity *= bleed;
		}

		// Human angular-speed ceiling (degrees per tick); pitch a touch slower.
		float maxYaw = 9.0F * speedScale;
		float maxPitch = 7.0F * speedScale;
		float yawStep = MathHelper.clamp(yawVelocity, -maxYaw, maxYaw);
		float pitchStep = MathHelper.clamp(pitchVelocity, -maxPitch, maxPitch);

		// Curved approach: while still turning, the pitch bows with the yaw drag
		// (sign and strength fixed per turn), so the path arcs instead of beelining.
		if (error > 5.0F) {
			pitchStep = MathHelper.clamp(pitchStep + arcBias * Math.abs(yawStep), -maxPitch, maxPitch);
		}

		// Occasional flick overshoot + settle on fast, large turns only.
		updateOvershoot(dt, error);
		yawStep += overshootYaw;
		pitchStep += overshootPitch;

		apply(player, yawStep * dt, pitchStep * dt);
		return onTarget;
	}

	/**
	 * Snap the emitted delta to the simulated mouse GCD and apply it. This is
	 * what makes the rotation packet look like genuine integer mouse counts.
	 * Sub-unit remainders carry over to the next update — small per-frame steps
	 * accumulate into a count every few frames, exactly like a slowly dragged
	 * mouse — while a settled head still emits real no-movement frames.
	 */
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

	/** Snap a raw degree delta to the nearest whole multiple of the mouse GCD. */
	private float quantize(float delta) {
		return com.profps.client.aim.MouseGcd.quantize(delta); // player's real live mouse grid
	}

	private void updateTremor(float dt) {
		tremorTicks -= dt;
		if (tremorTicks <= 0.0F) {
			rerollTremor();
			tremorTicks = 6 + random.nextInt(20);
		}
		// Lerp toward the current tremor target so motion is smooth, not jumpy.
		float blend = 1.0F - (float) Math.pow(0.88, dt);
		tremorYaw += (tremorYawTarget - tremorYaw) * blend;
		tremorPitch += (tremorPitchTarget - tremorPitch) * blend;
	}

	private void rerollTremor() {
		// Sub-degree breathing amplitude.
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
		// Decay any active overshoot continuously.
		float decay = (float) Math.pow(0.55, dt);
		overshootYaw *= decay;
		overshootPitch *= decay;
		if (Math.abs(overshootYaw) < 0.02F) overshootYaw = 0.0F;
		if (Math.abs(overshootPitch) < 0.02F) overshootPitch = 0.0F;

		if (overshootCooldown > 0.0F) {
			overshootCooldown -= dt;
			return;
		}
		// Only flick-overshoot on reasonably large, fast turns, and only sometimes.
		float speed = Math.max(Math.abs(yawVelocity), Math.abs(pitchVelocity));
		if (error > 14.0F && speed > 4.0F && random.nextFloat() < 0.18F * dt) {
			overshootYaw = Math.signum(yawVelocity) * (0.6F + random.nextFloat() * 1.6F);
			overshootPitch = Math.signum(pitchVelocity) * (0.3F + random.nextFloat() * 0.9F);
			overshootCooldown = 14 + random.nextInt(26);
		}
	}
}
