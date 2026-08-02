package com.profps.client.assists;

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
import net.minecraft.world.RaycastContext;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

/**
 * Expanded Hitbox turns a near-miss click into a real, server-valid player ray.
 *
 * <p>Acquisition uses the player's current view against a configurable expansion
 * around each player box. It never attacks that expanded geometry. Instead it
 * chooses a point inside the real box, visibly advances a bounded mouse-grid
 * rotation, and attacks only after the player's real camera ray intersects the real
 * box with clear terrain line of sight. There is no silent/server-only rotation.
 */
public final class ExpandedHitboxController {
	private static final long MAX_AIM_NANOS = 1_500_000_000L;
	private static final double BOX_MOTION_MARGIN = 0.035D;

	private enum Phase { IDLE, AIMING, RECOVERING }

	private static ExpandedHitboxController instance;

	private final ProFPSConfig config;
	private final SecureRandom random = new SecureRandom();

	private Phase phase = Phase.IDLE;
	private UUID targetUuid;
	private double aimFractionX;
	private double aimFractionY;
	private double aimFractionZ;
	private long reactionReadyNanos;
	private long deadlineNanos;

	private boolean rotationAdvanced;
	private long lastFrameNanos;

	public ExpandedHitboxController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	public static ExpandedHitboxController get() {
		return instance;
	}

	/** True while a captured manual click owns the server-facing rotation path. */
	public boolean isBusy() {
		return phase != Phase.IDLE;
	}

	/**
	 * Called at MinecraftClient#doAttack HEAD. Returns true only when vanilla's
	 * miss/block click should be consumed and completed through this controller.
	 */
	public boolean interceptAttack(MinecraftClient client) {
		if (!allowed(client) || !config.expandedHitbox) return false;

		// A real entity under the crosshair belongs to vanilla. In particular, do not
		// turn a deliberate mob/entity click into a silent player-selection click.
		if (client.crosshairTarget instanceof EntityHitResult entityHit
				&& entityHit.getType() == HitResult.Type.ENTITY) {
			return false;
		}

		// Repeated clicks while a captured hit is already travelling should not emit
		// vanilla misses or restart the humanized path.
		if (phase == Phase.AIMING) return true;

		Candidate candidate = findCandidate(client);
		if (candidate == null) return false;

		ClientPlayerEntity self = client.player;
		targetUuid = candidate.player.getUuid();
		aimFractionX = candidate.fractionX;
		aimFractionY = candidate.fractionY;
		aimFractionZ = candidate.fractionZ;
		phase = Phase.AIMING;
		rotationAdvanced = false;

		long now = System.nanoTime();
		double configured = MathHelper.clamp(config.expandedHitboxReactionMs, 0, 180);
		double reactionMs = configured * (0.72D + random.nextDouble() * 0.56D);
		reactionReadyNanos = now + (long) (reactionMs * 1_000_000.0D);
		deadlineNanos = now + MAX_AIM_NANOS;
		return true;
	}

	/**
	 * Runs at vanilla's click phase, before the current tick's movement update.
	 * Therefore an attack is sent only after a previous movement update has already
	 * delivered the spoofed real-box rotation to the server.
	 */
	public void tickPreMovement(MinecraftClient client) {
		if (phase == Phase.IDLE) return;
		if (client == null || client.player == null || client.world == null || client.interactionManager == null) {
			reset();
			return;
		}
		if (!config.enabled || !config.expandedHitbox || !client.player.isAlive()) {
			beginRecovery();
			return;
		}
		if (phase != Phase.AIMING) return;

		long now = System.nanoTime();
		PlayerEntity target = target(client);
		if (target == null || now > deadlineNanos) {
			beginRecovery();
			return;
		}
		if (!rotationAdvanced || now < reactionReadyNanos) return;
		if (!liveRayHitsTarget(client, target)) return;
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.EXPANDED_HITBOX)) return;

		client.interactionManager.attackEntity(client.player, target);
		client.player.swingHand(Hand.MAIN_HAND);
		client.player.resetTicksSinceLastAttack();
		reset();
	}

	/** Retained as no-ops for mixin compatibility; silent packet rotation is retired. */
	public static void beforeMovementPacket(ClientPlayerEntity player) {
	}

	public static void afterMovementPacket(ClientPlayerEntity player) {
	}

	/** Advance the real camera frame-by-frame; called from the world-render coordinator. */
	public void frame(MinecraftClient client) {
		if (phase != Phase.AIMING || !allowed(client)) return;
		PlayerEntity target = target(client);
		if (target == null) {
			reset();
			return;
		}
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp(
						(now - lastFrameNanos) / 1_000_000_000.0D * 20.0D,
						0.05D, 3.0D);
		lastFrameNanos = now;
		ClientPlayerEntity player = client.player;
		float[] wanted = rotationTo(player.getEyePos(), aimPoint(target));
		float yawError = MathHelper.wrapDegrees(wanted[0] - player.getYaw());
		float pitchError = MathHelper.wrapDegrees(wanted[1] - player.getPitch());
		float speed = MathHelper.clamp(config.expandedHitboxTurnSpeed, 10, 100);
		float response = 0.24F + speed * 0.0062F;
		float maxStep = (1.5F + speed * 0.285F) * dt;

		float yawStep = MathHelper.clamp(yawError * response, -maxStep, maxStep);
		float pitchStep = MathHelper.clamp(pitchError * response, -maxStep * 0.82F, maxStep * 0.82F);
		if (Math.hypot(yawError, pitchError) > 2.0D) {
			yawStep += (float) (random.nextGaussian() * 0.055D);
			pitchStep += (float) (random.nextGaussian() * 0.040D);
		}

		player.setYaw(player.getYaw() + MouseGcd.quantize(yawStep));
		player.setPitch(MathHelper.clamp(
				player.getPitch() + MouseGcd.quantize(pitchStep), -90.0F, 90.0F));
		rotationAdvanced = true;
	}

	private Candidate findCandidate(MinecraftClient client) {
		ClientPlayerEntity self = client.player;
		Vec3d eye = self.getEyePos();
		Vec3d look = self.getRotationVector().normalize();
		double reach = attackReach();
		Vec3d end = eye.add(look.multiply(reach));
		double expansion = MathHelper.clamp(config.expandedHitboxAmountCm, 5, 150) / 100.0D;

		Candidate best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator()) continue;

			Box realBox = other.getBoundingBox().expand(BOX_MOTION_MARGIN);
			if (realBox.raycast(eye, end).isPresent()) continue;
			Optional<Vec3d> expandedHit = realBox.expand(expansion).raycast(eye, end);
			if (expandedHit.isEmpty()) continue;

			double projected = MathHelper.clamp(
					other.getBoundingBox().getCenter().subtract(eye).dotProduct(look), 0.0D, reach);
			Vec3d nearRay = eye.add(look.multiply(projected));
			Vec3d point = insidePoint(other.getBoundingBox(), nearRay);
			if (point.distanceTo(eye) > reach + 0.02D || !clearTerrainRay(client, eye, point, self)) continue;

			double width = Math.max(0.05D, other.getBoundingBox().getLengthX());
			double height = Math.max(0.05D, other.getBoundingBox().getLengthY());
			double depth = Math.max(0.05D, other.getBoundingBox().getLengthZ());
			double fx = MathHelper.clamp((point.x - other.getBoundingBox().minX) / width
					+ random.nextGaussian() * 0.018D, 0.10D, 0.90D);
			double fy = MathHelper.clamp((point.y - other.getBoundingBox().minY) / height
					+ random.nextGaussian() * 0.012D, 0.14D, 0.86D);
			double fz = MathHelper.clamp((point.z - other.getBoundingBox().minZ) / depth
					+ random.nextGaussian() * 0.018D, 0.10D, 0.90D);

			float[] wanted = rotationTo(eye, point);
			double angularCorrection = Math.hypot(
					MathHelper.wrapDegrees(wanted[0] - self.getYaw()),
					MathHelper.wrapDegrees(wanted[1] - self.getPitch()));
			double score = angularCorrection * 4.0D + expandedHit.get().distanceTo(eye);
			if (score < bestScore) {
				bestScore = score;
				best = new Candidate(other, fx, fy, fz);
			}
		}
		return best;
	}

	private boolean liveRayHitsTarget(MinecraftClient client, PlayerEntity target) {
		ClientPlayerEntity self = client.player;
		Vec3d eye = self.getEyePos();
		Vec3d look = self.getRotationVec(1.0F);
		Vec3d end = eye.add(look.multiply(attackReach()));
		Optional<Vec3d> targetHit = target.getBoundingBox().expand(BOX_MOTION_MARGIN).raycast(eye, end);
		if (targetHit.isEmpty() || !clearTerrainRay(client, eye, targetHit.get(), self)) return false;

		// Do not silently attack through another player standing on the same ray.
		double targetDistance = targetHit.get().squaredDistanceTo(eye);
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || other == target || !other.isAlive() || other.isSpectator()) continue;
			Optional<Vec3d> otherHit = other.getBoundingBox().expand(BOX_MOTION_MARGIN).raycast(eye, end);
			if (otherHit.isPresent() && otherHit.get().squaredDistanceTo(eye) + 1.0E-4D < targetDistance) {
				return false;
			}
		}
		return true;
	}

	private boolean clearTerrainRay(MinecraftClient client, Vec3d start, Vec3d end, PlayerEntity self) {
		HitResult terrain = client.world.raycast(new RaycastContext(
				start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self));
		return terrain.getType() == HitResult.Type.MISS
				|| terrain.getPos().squaredDistanceTo(start) + 1.0E-4D >= end.squaredDistanceTo(start);
	}

	private Vec3d aimPoint(PlayerEntity target) {
		Box box = target.getBoundingBox();
		return new Vec3d(
				MathHelper.lerp(aimFractionX, box.minX, box.maxX),
				MathHelper.lerp(aimFractionY, box.minY, box.maxY),
				MathHelper.lerp(aimFractionZ, box.minZ, box.maxZ));
	}

	private Vec3d insidePoint(Box box, Vec3d nearRay) {
		double insetX = Math.min(0.055D, box.getLengthX() * 0.18D);
		double insetY = Math.min(0.10D, box.getLengthY() * 0.12D);
		double insetZ = Math.min(0.055D, box.getLengthZ() * 0.18D);
		return new Vec3d(
				MathHelper.clamp(nearRay.x, box.minX + insetX, box.maxX - insetX),
				MathHelper.clamp(nearRay.y, box.minY + insetY, box.maxY - insetY),
				MathHelper.clamp(nearRay.z, box.minZ + insetZ, box.maxZ - insetZ));
	}

	private PlayerEntity target(MinecraftClient client) {
		if (targetUuid == null || client.world == null) return null;
		for (PlayerEntity player : client.world.getPlayers()) {
			if (targetUuid.equals(player.getUuid())) return player;
		}
		return null;
	}

	private double attackReach() {
		double reach = 3.0D;
		if (config.reach) {
			reach = Math.max(reach, MathHelper.clamp(config.reachCm, 300, 600) / 100.0D);
		}
		return reach;
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null) return false;
		return client.player.isAlive() && !client.player.isSpectator() && !client.player.hasVehicle();
	}

	private void beginRecovery() {
		reset();
	}

	private void reset() {
		phase = Phase.IDLE;
		targetUuid = null;
		reactionReadyNanos = 0L;
		deadlineNanos = 0L;
		rotationAdvanced = false;
		lastFrameNanos = 0L;
	}

	private static float[] rotationTo(Vec3d eye, Vec3d point) {
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(1.0E-6D, horizontal))));
		return new float[]{yaw, MathHelper.clamp(pitch, -90.0F, 90.0F)};
	}

	private record Candidate(PlayerEntity player, double fractionX, double fractionY, double fractionZ) {}
}
