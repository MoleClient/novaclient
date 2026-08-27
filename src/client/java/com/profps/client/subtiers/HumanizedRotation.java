package com.profps.client.subtiers;

import com.profps.client.aim.MouseGcd;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/** Mouse-grid-aligned camera spring shared by the SubTiers sequences. */
final class HumanizedRotation {
	private final MouseGcd mouse = new MouseGcd();

	private float originalYaw;
	private float originalPitch;
	private double yawVelocity;
	private double pitchVelocity;
	private double omega;
	private double maxDegreesPerSecond;
	private double biasYaw;
	private double biasPitch;
	private double phaseYaw;
	private double phasePitch;
	private double rateYaw;
	private double ratePitch;
	private double tremor;
	private long lastFrameNanos;
	private boolean started;

	void begin(ClientPlayerEntity player, SecureRandom rng, boolean fast) {
		begin(player, rng, fast, 1.0D);
	}

	void begin(ClientPlayerEntity player, SecureRandom rng, boolean fast, double speedScale) {
		originalYaw = player.getYaw();
		originalPitch = player.getPitch();
		yawVelocity = rng.nextGaussian() * 2.5D;
		pitchVelocity = rng.nextGaussian() * 1.8D;
		double scale = MathHelper.clamp(speedScale, 0.65D, 4.0D);
		omega = ((fast ? 17.0D : 13.0D) + rng.nextDouble() * (fast ? 7.0D : 5.0D)) * Math.sqrt(scale);
		maxDegreesPerSecond = ((fast ? 260.0D : 190.0D)
				+ rng.nextDouble() * (fast ? 190.0D : 120.0D)) * scale;
		biasYaw = rng.nextGaussian() * 0.12D;
		biasPitch = rng.nextGaussian() * 0.09D;
		phaseYaw = rng.nextDouble() * Math.PI * 2.0D;
		phasePitch = rng.nextDouble() * Math.PI * 2.0D;
		rateYaw = 6.0D + rng.nextDouble() * 7.0D;
		ratePitch = 5.0D + rng.nextDouble() * 6.0D;
		tremor = 0.035D + rng.nextDouble() * 0.075D;
		lastFrameNanos = 0L;
		started = true;
	}

	float aimAt(ClientPlayerEntity player, Vec3d point) {
		Vec3d eye = player.getEyePos();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(1.0E-5D, horizontal))));
		return step(player, yaw, pitch, true);
	}

	float recover(ClientPlayerEntity player) {
		return step(player, originalYaw, originalPitch, false);
	}

	private float step(ClientPlayerEntity player, float targetYaw, float targetPitch, boolean addTremor) {
		if (!started) return Float.MAX_VALUE;
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0L ? 1.0D / 60.0D
				: MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0D, 1.0D / 240.0D, 0.05D);
		lastFrameNanos = now;

		double seconds = now / 1_000_000_000.0D;
		double noiseYaw = addTremor ? Math.sin(seconds * rateYaw + phaseYaw) * tremor : 0.0D;
		double noisePitch = addTremor ? Math.sin(seconds * ratePitch + phasePitch) * tremor * 0.72D : 0.0D;
		float wantedYaw = targetYaw + (float) (addTremor ? biasYaw + noiseYaw : 0.0D);
		float wantedPitch = targetPitch + (float) (addTremor ? biasPitch + noisePitch : 0.0D);
		double yawError = MathHelper.wrapDegrees(wantedYaw - player.getYaw());
		double pitchError = MathHelper.wrapDegrees(wantedPitch - player.getPitch());

		// Critically damped spring on remaining error and current velocity.
		yawVelocity += (omega * omega * yawError - 2.0D * omega * yawVelocity) * dt;
		pitchVelocity += (omega * omega * pitchError - 2.0D * omega * pitchVelocity) * dt;
		double maxStep = maxDegreesPerSecond * dt;
		float yawStep = mouse.yaw((float) MathHelper.clamp(yawVelocity * dt, -maxStep, maxStep));
		float pitchStep = mouse.pitch((float) MathHelper.clamp(pitchVelocity * dt, -maxStep * 0.72D, maxStep * 0.72D));
		player.setYaw(player.getYaw() + yawStep);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0F, 90.0F));
		player.headYaw = player.getYaw();

		float yawRemaining = Math.abs(MathHelper.wrapDegrees(targetYaw - player.getYaw()));
		float pitchRemaining = Math.abs(MathHelper.wrapDegrees(targetPitch - player.getPitch()));
		return (float) Math.hypot(yawRemaining, pitchRemaining);
	}

	void reset() {
		started = false;
		lastFrameNanos = 0L;
		yawVelocity = 0.0D;
		pitchVelocity = 0.0D;
	}
}
