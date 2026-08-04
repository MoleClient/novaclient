package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.aim.SilentAimController;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/**
 * Auto Spear — lands the kinetic hit when you fly through somebody.
 *
 * <p>A spear is not a click weapon. Holding its charge amplifies your forward
 * movement, and the hit resolves on <em>contact</em> once the charge has been
 * held past its own delay: the item carries {@code delayTicks} before the
 * kinetic attack arms, {@code contactCooldownTicks} between hits, and a damage
 * multiplier applied against the movement the attack lands with. That is why a
 * spear rewards speed — an elytra swoop or a straight flight through a player
 * hits far harder than the same spear used standing still.
 *
 * <p>So the only thing worth automating is <em>when to start the charge</em>.
 * Start it too late and the attack has not armed by the time you reach them and
 * you fly straight through for nothing; start it far too early and you burn the
 * charge, arrive with it spent, and telegraph the whole approach. This predicts
 * the moment of contact from closing speed and begins the charge exactly one
 * arming delay before it — read from the item itself rather than hardcoded, so
 * it stays correct if the values are retuned.
 *
 * <p>The charge is held by pressing the real use key, which is what makes the
 * whole vanilla use loop run: the movement amplification, the packets, the
 * sounds and the contact resolution are all the game's own, not reimplemented
 * here. Aim help only nudges the approach line so the hitboxes actually meet;
 * a spear that arms perfectly and passes a metre wide still does nothing.
 */
public final class AutoSpearController {
	/** Below this the hit is a poke, not a swoop, and arming just wastes the charge. */
	private static final double MIN_CLOSING_SPEED = 0.32D;
	/** How far past the target we keep the charge before conceding the pass missed. */
	private static final int OVERRUN_GRACE_TICKS = 6;
	private static final int MAX_HOLD_TICKS = 60;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private boolean holding;
	private boolean originalUse;
	private int holdTicks;
	private int overrunTicks;
	private java.util.UUID targetUuid;
	private long lastFrameNanos;
	private boolean ownsRotation;
	private int leadJitter;
	private String status = "Idle";

	public AutoSpearController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			release(client);
			return;
		}
		ClientPlayerEntity player = client.player;
		ItemStack spear = player.getMainHandStack();
		KineticWeaponComponent kinetic = spear.get(DataComponentTypes.KINETIC_WEAPON);
		if (kinetic == null) {
			// Only ever acts with the spear genuinely in hand; it never switches
			// slots for you, because the swoop is something you set up yourself.
			release(client);
			status = "Hold a spear";
			return;
		}

		PlayerEntity target = acquireTarget(client, player);
		if (target == null) {
			release(client);
			status = "No target";
			return;
		}
		targetUuid = target.getUuid();

		Approach approach = predict(player, target);
		if (approach == null) {
			// Not closing on them. Keep any charge already running — the pass may
			// still be in progress — but do not start a new one.
			if (holding && ++overrunTicks > OVERRUN_GRACE_TICKS) release(client);
			return;
		}
		overrunTicks = 0;

		// Arm exactly one delay before contact, plus a sampled tick of lead so the
		// start of the charge is never a fixed offset from the approach.
		int armAt = kinetic.delayTicks() + leadJitter;
		if (!holding && approach.ticksToContact() > armAt) {
			status = "Closing " + (int) approach.distance() + "m";
			return;
		}

		hold(client);
		status = "Charging";
		if (++holdTicks > MAX_HOLD_TICKS) release(client);
	}

	/**
	 * Aim help, applied per render frame like the other combat modules.
	 *
	 * <p>Aims at where the target <em>will be</em>, not where they are: at swoop
	 * speed the gap between those is several blocks, and contact is what the hit
	 * depends on.
	 */
	public boolean frame(MinecraftClient client) {
		ownsRotation = false;
		if (!allowed(client) || !holding) {
			if (config.autoSpearSilentAim) SilentAimController.instance().release();
			return false;
		}
		ClientPlayerEntity player = client.player;
		PlayerEntity target = targetById(client, player);
		if (target == null) return false;

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0D * 20.0D, 0.05D, 3.0D);
		lastFrameNanos = now;

		if (config.autoSpearSilentAim) SilentAimController.instance().engage(player);

		Vec3d aimAt = interceptPoint(player, target);
		Vec3d eye = player.getEyePos();
		double dx = aimAt.x - eye.x;
		double dz = aimAt.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		float desiredPitch = (float) -Math.toDegrees(Math.atan2(aimAt.y - eye.y, horizontal));

		float speed = MathHelper.clamp(config.autoSpearTurnSpeed, 20, 90) / 100.0F;
		float blend = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchError = MathHelper.wrapDegrees(desiredPitch - player.getPitch());
		float cap = MathHelper.clamp(config.autoSpearTurnSpeed, 20, 90) * 0.55F * dt;

		player.setYaw(player.getYaw() + mouse.yaw(MathHelper.clamp(
				yawError * blend + (float) rng.nextGaussian() * 0.16F, -cap, cap)));
		player.setPitch(MathHelper.clamp(player.getPitch() + mouse.pitch(MathHelper.clamp(
				pitchError * blend + (float) rng.nextGaussian() * 0.11F, -cap * 0.75F, cap * 0.75F)),
				-90.0F, 90.0F));
		player.headYaw = player.getYaw();
		ownsRotation = true;
		return true;
	}

	// ── Prediction ────────────────────────────────────────────────────────────

	/**
	 * Ticks until the two hitboxes meet, from the closing component of the
	 * relative velocity. Returns null when the pass is not actually closing —
	 * standing still, drifting away, or already through them.
	 */
	private Approach predict(ClientPlayerEntity player, PlayerEntity target) {
		Vec3d self = player.getEntityPos();
		Vec3d other = target.getEntityPos();
		Vec3d toTarget = other.subtract(self);
		double distance = toTarget.length();
		if (distance < 1.0E-4D) return new Approach(0, 0.0D);

		// Relative velocity along the line between us. Their movement counts:
		// a target flying at you halves the time you have to arm.
		Vec3d relative = player.getVelocity().subtract(target.getVelocity());
		double closing = relative.dotProduct(toTarget.normalize());
		if (closing < MIN_CLOSING_SPEED) return null;

		// Contact is hitbox to hitbox, not centre to centre.
		double contactGap = (player.getWidth() + target.getWidth()) * 0.5D + 0.35D;
		double travel = Math.max(0.0D, distance - contactGap);
		return new Approach((int) Math.ceil(travel / closing), distance);
	}

	/** Where to point so the hitboxes meet, accounting for both of you moving. */
	private Vec3d interceptPoint(ClientPlayerEntity player, PlayerEntity target) {
		Vec3d self = player.getEntityPos();
		Vec3d other = target.getBoundingBox().getCenter();
		double distance = other.subtract(self).length();
		Vec3d relative = player.getVelocity().subtract(target.getVelocity());
		double closing = Math.max(MIN_CLOSING_SPEED, relative.length());
		double ticks = MathHelper.clamp(distance / closing, 0.0D, 20.0D);
		return other.add(target.getVelocity().multiply(ticks));
	}

	private record Approach(int ticksToContact, double distance) {}

	// ── Targeting ─────────────────────────────────────────────────────────────

	private PlayerEntity acquireTarget(MinecraftClient client, ClientPlayerEntity self) {
		double range = MathHelper.clamp(config.autoSpearRange, 8, 96);
		double minDot = Math.cos(Math.toRadians(MathHelper.clamp(config.autoSpearFov, 20, 140)));
		PlayerEntity best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		Vec3d heading = self.getVelocity().lengthSquared() > 1.0E-4D
				? self.getVelocity().normalize()
				: self.getRotationVec(1.0F);

		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator()) continue;
			Vec3d delta = other.getBoundingBox().getCenter().subtract(self.getEyePos());
			double distance = delta.length();
			if (distance < 1.0E-4D || distance > range) continue;
			// Scored against where you are actually travelling, not where you are
			// looking: on a swoop the flight path is what decides who you can
			// reach, and the view is free to be somewhere else entirely.
			double dot = delta.normalize().dotProduct(heading);
			if (dot < minDot) continue;
			double score = dot * 6.0D + (range - distance) / range;
			if (score > bestScore) {
				bestScore = score;
				best = other;
			}
		}
		return best;
	}

	private PlayerEntity targetById(MinecraftClient client, ClientPlayerEntity self) {
		if (targetUuid == null) return null;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (targetUuid.equals(other.getUuid()) && other.isAlive() && !other.isSpectator()) return other;
		}
		return null;
	}

	// ── Charge handling ───────────────────────────────────────────────────────

	/**
	 * Holds the real use key. Everything the charge does — the forward
	 * amplification, the packets, the contact resolution — is vanilla's own use
	 * loop; nothing here reimplements it.
	 */
	private void hold(MinecraftClient client) {
		if (holding) return;
		originalUse = client.options.useKey.isPressed();
		client.options.useKey.setPressed(true);
		holding = true;
		holdTicks = 0;
	}

	private void release(MinecraftClient client) {
		if (holding && client != null && client.options != null) {
			client.options.useKey.setPressed(originalUse);
		}
		if (holding && config.autoSpearSilentAim) SilentAimController.instance().release();
		holding = false;
		holdTicks = 0;
		overrunTicks = 0;
		targetUuid = null;
		ownsRotation = false;
		// Re-sample the lead so consecutive passes never arm on the same offset.
		leadJitter = rng.nextInt(2);
		if (status.equals("Charging")) status = "Idle";
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || !config.autoSpearEnabled) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null
				|| client.getOverlay() != null || !client.isWindowFocused()) return false;
		ClientPlayerEntity player = client.player;
		return player.isAlive() && !player.isSpectator() && !player.isTouchingWater();
	}

	public boolean ownsRotation() {
		return ownsRotation;
	}

	public boolean isBusy() {
		return holding;
	}

	public String status() {
		return status;
	}

	/** Player-sized volume used only to reason about contact in tests. */
	static Box contactVolume(Vec3d centre, double width, double height) {
		return new Box(centre.x - width * 0.5D, centre.y, centre.z - width * 0.5D,
				centre.x + width * 0.5D, centre.y + height, centre.z + width * 0.5D);
	}
}
