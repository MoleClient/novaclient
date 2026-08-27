package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.aim.SilentAimController;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Holds a spear's kinetic charge and aims it at the current target. Contact is resolved by
 * {@code KineticWeaponComponent.usageTick} as a ray along the look vector, so the aim is the
 * attack. Arming costs 8 to 10 ticks depending on tier, which is why the charge is held for the
 * whole engagement rather than started on arrival. Closing speed is the player's to supply.
 */
public final class AutoSpearController {
	private static final int HOTBAR_SLOTS = 9;
	/** Ticks a lost target is kept before disengaging. */
	private static final int LOST_TARGET_GRACE_TICKS = 10;
	/** Minimum ticks between checks for a lapsed charge. */
	private static final int REARM_INTERVAL_TICKS = 2;
	/** Ticks in range under the speed bar before the overlay notice fires. */
	private static final int TOO_SLOW_NOTICE_TICKS = 20;
	/** Distance inside the contact band at which the turn speeds up. */
	private static final double URGENT_CONTACT_MARGIN = 2.0D;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private UUID targetUuid;
	private double fx, fy, fz; // relative hitbox offsets, 0 to 1
	private long nextRetargetNanos;
	private long lastFrameNanos;

	// Slow wandering aim bias.
	private double biasYaw, biasPitch, biasYawTarget, biasPitchTarget;
	private long nextBiasNanos;

	private boolean engaged;
	private boolean aiming;
	private boolean holdingUseKey;
	private boolean originalUseKey;
	private int returnSlot = -1;
	private int rearmAge;
	private int lostTargetTicks;
	private int tooSlowTicks;
	private boolean noticedThisEngagement;
	private String status = "Idle";

	public AutoSpearController(ProFPSConfig config) {
		this.config = config;
	}

	// ── Tick ──────────────────────────────────────────────────────────────────

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			disengage(client);
			return;
		}
		ClientPlayerEntity player = client.player;

		// Keep the committed target: re-acquiring restarts the charge and its arm delay.
		PlayerEntity target = byUuid(client, targetUuid);
		if (target != null && !trackable(player, target)) target = null;
		if (target == null) target = acquire(client, player);
		if (target == null) {
			if (engaged && ++lostTargetTicks <= LOST_TARGET_GRACE_TICKS) {
				sustain(client, player);
				return;
			}
			disengage(client);
			status = "No target";
			return;
		}
		lostTargetTicks = 0;
		long now = System.nanoTime();
		if (!target.getUuid().equals(targetUuid)) {
			targetUuid = target.getUuid();
			tooSlowTicks = 0;
			noticedThisEngagement = false;
			pickPoint();
			nextRetargetNanos = now + retargetDelayNanos();
		} else if (now >= nextRetargetNanos) {
			pickPoint();
			nextRetargetNanos = now + retargetDelayNanos();
		}

		if (!isSpear(player.getMainHandStack())) {
			releaseUseKey(client);
			int slot = findSpear(player);
			if (slot < 0) {
				disengage(client);
				status = "No spear in the hotbar";
				return;
			}
			if (!config.autoSpearAutoSwitch) {
				status = "Hold a spear";
				return;
			}
			// Only the slot change is claimed; the charge itself is an ordinary use.
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_SPEAR)) return;
			if (returnSlot < 0) returnSlot = player.getInventory().getSelectedSlot();
			select(client, player, slot);
			return;
		}

		engaged = true;
		sustain(client, player);
		describe(client, player, target);
	}

	/**
	 * Holds the use key and restarts a lapsed charge. Vanilla ends the use when the key reads
	 * released, and it also finishes on its own after {@code delayTicks} plus the damage window.
	 */
	private void sustain(MinecraftClient client, ClientPlayerEntity player) {
		// The use key must never be pressed for a non-spear; this is the only path that presses it.
		if (!isSpear(player.getMainHandStack())) {
			releaseUseKey(client);
			return;
		}
		if (!holdingUseKey) {
			originalUseKey = client.options.useKey.isPressed();
			holdingUseKey = true;
		}
		client.options.useKey.setPressed(true);

		// Any other active item, such as an offhand shield, is not a spear charge.
		if (chargingSpear(player)) return;
		if (player.isUsingItem()) client.interactionManager.stopUsingItem(player);
		if (player.age < rearmAge) return;
		rearmAge = player.age + REARM_INTERVAL_TICKS;
		// This runs at the tail of handleInputEvents, after vanilla read the key, so the use is
		// started directly rather than waiting a tick for the held key to be picked up.
		client.interactionManager.interactItem(player, Hand.MAIN_HAND);
	}

	/** Updates the status string from the held stack's own kinetic component values. */
	private void describe(MinecraftClient client, ClientPlayerEntity player, PlayerEntity target) {
		ItemStack spear = player.getMainHandStack();
		KineticWeaponComponent kinetic = spear.get(DataComponentTypes.KINETIC_WEAPON);
		int armTicks = kinetic == null ? SpearCombatPolicy.FALLBACK_ARM_TICKS : kinetic.delayTicks();
		int windowTicks = kinetic == null ? SpearCombatPolicy.FALLBACK_DAMAGE_WINDOW_TICKS
				: kinetic.damageConditions()
						.map(KineticWeaponComponent.Condition::maxDurationTicks)
						.orElse(SpearCombatPolicy.FALLBACK_DAMAGE_WINDOW_TICKS);
		double minClosing = kinetic == null ? SpearCombatPolicy.FALLBACK_MIN_CLOSING_SPEED
				: kinetic.damageConditions()
						.map(KineticWeaponComponent.Condition::minRelativeSpeed)
						.orElse(SpearCombatPolicy.FALLBACK_MIN_CLOSING_SPEED);

		int held = chargingSpear(player) ? player.getItemUseTime() : 0;
		if (!SpearCombatPolicy.armed(held, armTicks)) {
			status = "Arming " + (armTicks - held) + "t";
			tooSlowTicks = 0;
			return;
		}

		Vec3d look = player.getRotationVector();
		double closing = SpearCombatPolicy.closingSpeed(
				look.dotProduct(KineticWeaponComponent.getAmplifiedMovement(player)),
				look.dotProduct(KineticWeaponComponent.getAmplifiedMovement(target)));
		double distance = target.getBoundingBox().getCenter().distanceTo(player.getEyePos());
		double near = minRange(player);
		boolean inBand = SpearCombatPolicy.withinContactBand(distance, near, maxRange(player));
		boolean wouldDamage = SpearCombatPolicy.contactDamages(
				held - armTicks, windowTicks, closing, minClosing);

		if (!inBand && distance < near) {
			status = String.format("Too close · %.1fm", distance);
			tooSlowTicks = 0;
			return;
		}
		status = String.format("Charged · %.1fm · %.1f m/s", distance, closing);
		if (!inBand) {
			tooSlowTicks = 0;
			return;
		}

		// Notify once per engagement when in range but under the closing-speed bar.
		if (wouldDamage) {
			tooSlowTicks = 0;
			return;
		}
		if (++tooSlowTicks >= TOO_SLOW_NOTICE_TICKS && !noticedThisEngagement) {
			noticedThisEngagement = true;
			client.inGameHud.setOverlayMessage(Text.literal(String.format(
					"Auto Spear: in reach but closing at %.1f of %.1f m/s — sprint into them",
					closing, minClosing)), false);
		}
	}

	// ── Frame: the aim ────────────────────────────────────────────────────────

	/**
	 * Steps the view toward the target by a capped fraction of the remaining error each frame.
	 *
	 * @return true while this controller owns rotation
	 */
	public boolean frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0 * 20.0, 0.05, 4.0);
		lastFrameNanos = now;

		aiming = false;
		if (!engaged || targetUuid == null || !allowed(client)) return false;
		ClientPlayerEntity player = client.player;
		// The contact ray comes from the held stack, so there is nothing to aim without a spear.
		if (!isSpear(player.getMainHandStack())) return false;
		PlayerEntity target = byUuid(client, targetUuid);
		if (target == null || !trackable(player, target)) return false;

		// Silent aim is held by continuous request; skipping a frame releases the body.
		if (config.autoSpearSilentAim) SilentAimController.instance().engage(player);

		updateBias(now, dt);
		Vec3d eye = player.getEyePos();
		Vec3d point = aimPoint(target);
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0) + (float) biasYaw;
		float desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal))) + (float) biasPitch;

		float yawErr = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchErr = MathHelper.wrapDegrees(desiredPitch - player.getPitch());

		// Inside the contact band the ray is about to be evaluated, so the turn speeds up.
		double distance = target.getBoundingBox().getCenter().distanceTo(eye);
		boolean urgent = distance <= maxRange(player) + URGENT_CONTACT_MARGIN;
		float base = MathHelper.clamp(config.autoSpearTurnSpeed, 20, 90) / 100.0F;
		float speed = urgent ? Math.min(0.92F, base * 1.7F) : base;
		float k = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawStep = yawErr * k + (float) (rng.nextGaussian() * 0.30D);
		float pitchStep = pitchErr * k + (float) (rng.nextGaussian() * 0.22D);

		// Occasional overshoot on a large turn.
		if (Math.abs(yawErr) > 28.0F && rng.nextFloat() < 0.16F * dt) {
			yawStep += Math.signum(yawErr) * (2.0F + rng.nextFloat() * 4.0F);
		}

		// Per-frame rotation cap, then snapped to the mouse GCD grid.
		float cap = speed * 100.0F * (urgent ? 0.6F : 0.5F) * dt;
		float yawApplied = mouse.yaw(MathHelper.clamp(yawStep, -cap, cap));
		float pitchApplied = mouse.pitch(MathHelper.clamp(pitchStep, -cap * 0.8F, cap * 0.8F));

		player.setYaw(player.getYaw() + yawApplied);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -90.0F, 90.0F));
		aiming = true;
		return true;
	}

	/** Re-rolls and blends the slow aim bias. */
	private void updateBias(long now, float dt) {
		if (now >= nextBiasNanos) {
			biasYawTarget = rng.nextGaussian() * 0.55D;
			biasPitchTarget = rng.nextGaussian() * 0.40D;
			nextBiasNanos = now + 180_000_000L + (long) (rng.nextDouble() * 320_000_000L);
		}
		double blend = 1.0D - Math.pow(0.86D, dt);
		biasYaw += (biasYawTarget - biasYaw) * blend;
		biasPitch += (biasPitchTarget - biasPitch) * blend;
	}

	/**
	 * Randomized upper-chest point plus a fraction of a tick of lead. Aiming high keeps the ray
	 * flat, since closing speed is measured along the look vector and pitch subtracts from it.
	 */
	private Vec3d aimPoint(PlayerEntity target) {
		Box box = target.getBoundingBox();
		Vec3d point = new Vec3d(
				box.minX + (box.maxX - box.minX) * fx,
				box.minY + (box.maxY - box.minY) * fy,
				box.minZ + (box.maxZ - box.minZ) * fz);
		Vec3d velocity = target.getVelocity();
		Vec3d lead = new Vec3d(velocity.x, velocity.y * 0.45D, velocity.z).multiply(0.55D);
		double horizontal = Math.sqrt(lead.x * lead.x + lead.z * lead.z);
		if (horizontal > 0.18D) {
			double scale = 0.18D / horizontal;
			lead = new Vec3d(lead.x * scale, MathHelper.clamp(lead.y, -0.12D, 0.12D), lead.z * scale);
		}
		return point.add(lead);
	}

	private long retargetDelayNanos() {
		return 240_000_000L + (long) (rng.nextDouble() * 420_000_000L);
	}

	/** Samples off-centre hitbox fractions, biased high. */
	private void pickPoint() {
		fx = 0.32 + rng.nextDouble() * 0.36;
		fy = 0.52 + rng.nextDouble() * 0.24; // upper chest keeps the ray flat
		fz = 0.32 + rng.nextDouble() * 0.36;
	}

	// ── Targeting ─────────────────────────────────────────────────────────────

	/** Nearest visible player inside the configured range and cone, biased toward the current target. */
	private PlayerEntity acquire(MinecraftClient client, ClientPlayerEntity self) {
		double range = range();
		PlayerEntity best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (!trackable(self, other)) continue;
			double distance = other.getBoundingBox().getCenter().distanceTo(self.getEyePos());
			double score = (range - distance) / range
					+ (other.getUuid().equals(targetUuid) ? 0.35D : 0.0D);
			if (score > bestScore) {
				bestScore = score;
				best = other;
			}
		}
		return best;
	}

	/** Whether the target is alive, in range, in the configured cone, and in line of sight. */
	private boolean trackable(ClientPlayerEntity self, PlayerEntity target) {
		if (target == self || !target.isAlive() || target.isSpectator()) return false;
		double range = range();
		Vec3d delta = target.getBoundingBox().getCenter().subtract(self.getEyePos());
		if (delta.lengthSquared() > range * range) return false;
		if (!self.canSee(target)) return false;
		if (delta.lengthSquared() < 1.0E-6D) return true;
		double minDot = Math.cos(Math.toRadians(MathHelper.clamp(config.autoSpearFov, 20, 140)));
		return delta.normalize().dotProduct(self.getRotationVec(1.0F)) >= minDot;
	}

	/**
	 * Acquisition range, not reach. Contact still resolves between 2 and about 4.5 blocks; this
	 * only decides how early the charge starts.
	 */
	private double range() {
		return MathHelper.clamp(config.autoSpearRange, 4, 64);
	}

	private PlayerEntity byUuid(MinecraftClient client, UUID uuid) {
		if (uuid == null) return null;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (uuid.equals(other.getUuid())) return other;
		}
		return null;
	}

	// ── Spear handling ────────────────────────────────────────────────────────

	private boolean isSpear(ItemStack stack) {
		return !stack.isEmpty()
				&& stack.contains(DataComponentTypes.PIERCING_WEAPON)
				&& stack.contains(DataComponentTypes.KINETIC_WEAPON);
	}

	private boolean chargingSpear(ClientPlayerEntity player) {
		return player.isUsingItem()
				&& player.getActiveHand() == Hand.MAIN_HAND
				&& isSpear(player.getActiveItem());
	}

	private int findSpear(ClientPlayerEntity player) {
		for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
			if (isSpear(player.getInventory().getStack(slot))) return slot;
		}
		return -1;
	}

	/** Where vanilla's contact ray starts and ends, read off the held stack. */
	private double minRange(ClientPlayerEntity player) {
		AttackRangeComponent range = player.getAttackRange();
		return range == null ? SpearCombatPolicy.MIN_JAB_REACH : range.getEffectiveMinRange(player);
	}

	private double maxRange(ClientPlayerEntity player) {
		AttackRangeComponent range = player.getAttackRange();
		if (range == null) return SpearCombatPolicy.MAX_JAB_REACH;
		// Vanilla extends the far end by the forward component of this tick's movement.
		double lunge = Math.max(0.0D,
				player.getMovement().dotProduct(player.getHeadRotationVector()));
		return range.getEffectiveMaxRange(player) + lunge;
	}

	private void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot >= HOTBAR_SLOTS || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	/** Restores the use key to the state it had before the charge. */
	private void releaseUseKey(MinecraftClient client) {
		if (!holdingUseKey || client == null || client.options == null) return;
		client.options.useKey.setPressed(originalUseKey);
		holdingUseKey = false;
	}

	/** Stops the charge, restores the use key, and returns the borrowed hotbar slot. */
	private void disengage(MinecraftClient client) {
		if (client != null && client.player != null) {
			if (holdingUseKey && client.interactionManager != null
					&& chargingSpear(client.player)) {
				client.interactionManager.stopUsingItem(client.player);
			}
			releaseUseKey(client);
			// Outside the use-key branch: the spear is selected one tick before it is charged,
			// so the slot can be borrowed without the key ever having been taken.
			if (returnSlot >= 0 && config.autoSpearAutoSwitch
					&& isSpear(client.player.getMainHandStack())) {
				select(client, client.player, returnSlot);
			}
		}
		engaged = false;
		aiming = false;
		holdingUseKey = false;
		returnSlot = -1;
		rearmAge = 0;
		lostTargetTicks = 0;
		tooSlowTicks = 0;
		noticedThisEngagement = false;
		targetUuid = null;
		status = "Idle";
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || !config.autoSpearEnabled) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null
				|| client.getOverlay() != null || !client.isWindowFocused()) return false;
		ClientPlayerEntity player = client.player;
		// Water caps movement well under the closing-speed bar, so no contact can land.
		return player.isAlive() && !player.isSpectator() && !player.isTouchingWater();
	}

	public boolean ownsRotation() {
		return aiming;
	}

	public boolean isBusy() {
		return engaged;
	}

	/** Current status text, including the not-engaged reasons. */
	public String status() {
		return status;
	}
}
