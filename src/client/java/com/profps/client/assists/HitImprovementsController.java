package com.profps.client.assists;

import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatMode;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Trigger-bot hit assist: locks a target under the crosshair and swings once the attack
 * cooldown, reaction delay and settle dwell are all satisfied. Swings are only sent on a
 * real client tick with vanilla's crosshair target confirming the locked player.
 */
public final class HitImprovementsController {

	private static final long   TARGET_MEMORY_NS = 3_000_000_000L; // how long a target UUID is remembered
	private static final long   ARM_RETENTION_NS = 700_000_000L;   // how long an armed shot survives aim loss
	private static final double BASE_REACH    = 3.0D;         // vanilla attack reach; the Reach module raises it
	private static final double TRACKING_MARGIN = 0.45D;      // arm-only pre-contact margin
	private static final double HIT_EXPAND    = 0.20D;        // arm-only drift tolerance
	private static final long   SETTLE_GRACE_NS = 35_000_000L; // aim flicker that does not reset the settle
	private static final long   PREP_RETENTION_NS = 160_000_000L; // keep pre-hit input through a short flicker
	private static final float  NORMAL_HIT_PREP_LEAD = 0.18F; // sprint prep lead, in cooldown progress
	private static final int    MAX_NORMAL_HIT_PREP_ATTEMPTS = 2; // one soft attempt plus one hard retry
	private static final double NORMAL_HIT_STEP = 0.32D;      // collision-checked forward tap distance

	private enum WeaponClass { SWORD, AXE, OTHER }

	private static HitImprovementsController instance;

	private final ProFPSConfig  config;
	private final SecureRandom  rng = new SecureRandom();

	private UUID   lockedTarget;
	private long   targetLastSeenNanos;
	private long   lastAttackNanos;
	private float  lastObservedCooldown = -1.0F;

	// Two-phase timing: arm when the cooldown is ready, fire after the per-cycle delay.
	private boolean armed;
	private long    fireAfterNanos;
	private long    skipUntilNanos;   // a skip sits out this whole window
	private long    commitAtNanos;    // the swing lands this beat after the charge fills
	private boolean commitScheduled;  // one commit beat per cooldown cycle
	private float   cooldownThresholdThisCycle;
	private double  cooldownSampleThisCycle;
	private WeaponClass plannedWeapon = WeaponClass.OTHER;
	private boolean plannedSprinting;
	private boolean skipThisCycle;
	private boolean allowNaturalSweepThisCycle;
	private boolean normalHitPreparedThisCycle;
	private boolean normalHitConversionRequiredThisCycle;
	private int normalHitScheduledAttempts;

	// A full-charge grounded slow sword swing is a sweep in vanilla, so normal-hit cycles
	// publish a forward+sprint input before the attack tick and wait for sprint to establish.
	private long normalHitInputStartNanos;
	private long normalHitInputUntilNanos;
	private long normalHitAttackAfterNanos;
	private long normalHitFallbackNanos;

	// Settle dwell: the aim must rest on the target before a hit fires, decoupling the
	// attack from the frame an aim correction lands.
	private long    settledSinceNanos;
	private long    fireDwellNanos = 25_000_000L;

	private int     hitStreak;

	// Slowly drifting scalar that varies timing across a fight.
	private double  focus    = 0.82D;
	private long    lastNanos;

	private boolean cyclePlanReady;

	public HitImprovementsController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/**
	 * Layers the pre-hit forward and sprint tap onto whichever module currently owns input.
	 *
	 * @return null when no pre-hit tap is active, so input passes through untouched
	 */
	public static PlayerInput normalHitOverride(PlayerInput current) {
		HitImprovementsController controller = instance;
		if (controller == null || current == null) return null;
		long now = System.nanoTime();
		if (now < controller.normalHitInputStartNanos || now >= controller.normalHitInputUntilNanos) return null;

		MinecraftClient client = MinecraftClient.getInstance();
		// Manual retreat, sneak or jump wins; a jump is already a non-sweep state.
		if (current.backward() || current.sneak() || current.jump() || client.options.backKey.isPressed()) return null;
		PlayerInput prepared = new PlayerInput(true, false, current.left(), current.right(), false, false, true);
		if (!controller.canSafelyPrepareNormalHit(client, prepared)) return null;
		return prepared;
	}

	/**
	 * Drops forward input while airborne so a sprinting jump can land as a crit.
	 * Vanilla ends a sprint only when the published input stops carrying forward movement,
	 * per {@code ClientPlayerEntity.shouldStopSprinting}; clearing the sprint key alone does not.
	 */
	public static PlayerInput critSprintOverride(PlayerInput current) {
		HitImprovementsController controller = instance;
		if (controller == null || current == null) return null;
		if (!controller.config.hitCritTiming || !controller.config.hitCritSprintRelease) return null;

		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isSprinting() || player.isOnGround()) return null;
		// Manual retreat or sneak already ends the sprint.
		if (current.backward() || current.sneak() || !current.forward()) return null;
		if (player.isClimbing() || player.isTouchingWater() || player.isInLava()
				|| player.hasVehicle() || player.isGliding() || player.getAbilities().flying) return null;
		if (controller.getCrosshairPlayer(client) == null) return null;

		return new PlayerInput(false, false, current.left(), current.right(),
				current.jump(), false, false);
	}

	public void tick(MinecraftClient client) {
		update(client, true);   // only a real client tick may send the swing
	}

	/** Tracks and arms between ticks; never sends a packet off-tick. */
	public void frame(MinecraftClient client) {
		update(client, false);
	}

	private void update(MinecraftClient client, boolean canFire) {
		if (!isAllowed(client)) { release(); return; }

		long now = System.nanoTime();
		CombatModeProfile.Trigger tuning = tuning();
		driftFocus(now);

		PlayerEntity aimed = getAimedPlayer(client);
		if (aimed != null && !CombatModeRuntime.triggerEnabledFor(config, aimed.getUuid())) {
			aimed = null;
		}

		// Retention and release: the UUID is held longer than the armed shot.
		if (aimed == null) {
			if (lockedTarget != null) {
				long unseenNanos = now - targetLastSeenNanos;
				if (unseenNanos > TARGET_MEMORY_NS) {
					release();
				} else if (unseenNanos > ARM_RETENTION_NS) {
					clearCyclePlan();
					skipUntilNanos = 0L;
					settledSinceNanos = 0L;
				} else {
					if (unseenNanos > PREP_RETENTION_NS) clearNormalHitPrep();
					if (unseenNanos > SETTLE_GRACE_NS) {
						settledSinceNanos = 0L; // off target long enough to require a re-settle
					}
				}
			}
			return;
		}

		long unseenNanos = lockedTarget == null ? Long.MAX_VALUE : now - targetLastSeenNanos;
		targetLastSeenNanos = now;

		// Acquire or switch lock.
		if (!aimed.getUuid().equals(lockedTarget)) {
			lockedTarget    = aimed.getUuid();
			hitStreak       = 0;
			clearCyclePlan();
			skipUntilNanos  = 0L;
			settledSinceNanos = now;
			fireDwellNanos  = ns(tuning.settleMinMs()
					+ rng.nextDouble() * Math.max(0, tuning.settleMaxMs() - tuning.settleMinMs()));

			// The reaction clock starts here once; nothing downstream restarts it.
			fireAfterNanos = now + ns(firstHitDelay(now, client.player, aimed));
			commitScheduled = false;
			WeaponClass weapon = weaponClass(client.player);
			planAttackCycle(client.player, weapon, client.player.isSprinting());
			maybePrepareNormalHit(client, aimed, now,
					client.player.getAttackCooldownProgress(0.0F), true);
			return;
		}

		// Reacquiring after a long aim loss keeps the identity but resets the cycle timers.
		if (unseenNanos > ARM_RETENTION_NS) {
			hitStreak = 0;
			clearCyclePlan();
			skipUntilNanos = 0L;
			settledSinceNanos = now;
			fireDwellNanos = ns(tuning.settleMinMs()
					+ rng.nextDouble() * Math.max(0, tuning.settleMaxMs() - tuning.settleMinMs()));
			fireAfterNanos = now + ns(firstHitDelay(now, client.player, aimed));
			WeaponClass weapon = weaponClass(client.player);
			planAttackCycle(client.player, weapon, client.player.isSprinting());
			maybePrepareNormalHit(client, aimed, now,
					client.player.getAttackCooldownProgress(0.0F), true);
			return;
		}

		// Same target: begin/continue the settle-on-target timer.
		if (settledSinceNanos == 0L) settledSinceNanos = now;

		// Track the cooldown and prepare sprint input while the reaction delay runs.
		float cd = client.player.getAttackCooldownProgress(0.0F);
		if (lastObservedCooldown >= 0.0F && cd + 0.10F < lastObservedCooldown) {
			// Another attack consumed the previous cycle, so discard its plan.
			clearCyclePlan();
			skipUntilNanos = 0L;
		}
		lastObservedCooldown = cd;

		// A fired skip sits out this window before the same cycle resumes.
		if (now < skipUntilNanos) {
			return;
		}

		// Respect vanilla's miss/decline click cooldown without rerolling the plan.
		MinecraftClientInvoker mc = (MinecraftClientInvoker)(Object)client;
		if (mc.profps$getAttackCooldown() > 0) {
			clearCyclePlan();
			return;
		}

		WeaponClass weapon = weaponClass(client.player);
		boolean sprinting = client.player.isSprinting();
		if (cyclePlanReady && plannedWeapon != weapon) {
			// A weapon swap needs a new threshold, but the sampled skip decision stands.
			boolean preserveSkip = skipThisCycle;
			clearCyclePlan();
			planAttackCycle(client.player, weapon, sprinting);
			skipThisCycle = preserveSkip;
		} else if (cyclePlanReady
				&& tuning.sprintAwareCooldown()
				&& (weapon == WeaponClass.SWORD || weapon == WeaponClass.AXE)
				&& !normalHitPreparedThisCycle
				&& plannedSprinting != sprinting) {
			// Recompute only the context-dependent threshold from the same per-cycle percentile.
			plannedSprinting = sprinting;
			cooldownThresholdThisCycle = thresholdFromSample(client.player, weapon, cooldownSampleThisCycle);
		}
		if (!cyclePlanReady) planAttackCycle(client.player, weapon, sprinting);
		maybePrepareNormalHit(client, aimed, now, cd,
				hitStreak == 0 || isClosingWindow(client.player, aimed));

		if (!armed && now < fireAfterNanos) return;

		float threshold = cooldownThresholdThisCycle;
		// Vanilla only crits above ~0.9 charge, so an air-crit cycle floors the threshold there.
		if (tuning.critTiming() && canAttemptAirCrit(client.player)) threshold = Math.max(threshold, 0.92F);

		if (cd < threshold) {
			// Cooldown not ready; disarm so it re-arms fresh when it fills.
			armed = false;
			commitScheduled = false;
			return;
		}

		// Once the cooldown fills, schedule a short beat before the swing so the
		// inter-hit interval is not locked to the cooldown period.
		if (!armed) {
			armed = true;
			// The first hit already waited firstHitDelay; an axe always gets its own beat.
			double commitMs = hitStreak > 0 ? commitDelayMs(isClosingWindow(client.player, aimed)) : 0D;
			if (plannedWeapon == WeaponClass.AXE) commitMs = Math.max(commitMs, axePostDelayMs());
			commitScheduled = commitMs > 0D;
			if (commitScheduled) commitAtNanos = now + ns(commitMs);
		}

		// The crosshair must have rested on the target, decoupling the attack from aim corrections.
		if (now - settledSinceNanos < fireDwellNanos) return;

		// Swings are sent only on a real tick so the attack packet keeps vanilla's
		// flying-then-action ordering.
		if (!canFire) return;

		if (commitScheduled && now < commitAtNanos) return;

		// Vanilla crits only while falling with real fall distance, not on the ground or at
		// the apex, so hold the swing until then.
		if (tuning.critTiming() && canAttemptAirCrit(client.player) && !inCritWindow(client.player)) return;

		// The server validates the hit with the same raycast as crosshairTarget, so require both.
		PlayerEntity crosshairNow = getCrosshairPlayer(client);
		PlayerEntity freshCrosshairNow = getFreshCrosshairPlayer(client);
		if (crosshairNow == null || freshCrosshairNow == null
				|| !crosshairNow.getUuid().equals(lockedTarget)
				|| !freshCrosshairNow.getUuid().equals(lockedTarget)) {
			// Stay committed and fire on the first tick the vanilla ray agrees.
			return;
		}

		// Consume the sampled skip only once a legal target is under the vanilla reticle.
		if (skipThisCycle) {
			skipThisCycle = false;
			armed = true; // stay loaded; the skipped click is the whole delay
			commitScheduled = false;
			double skipMs = isClosingWindow(client.player, aimed)
					? 55D + rng.nextDouble() * 45D
					: 80D + rng.nextDouble() * 80D;
			if (rng.nextDouble() < 0.08D) skipMs += 70D + rng.nextDouble() * 100D;
			skipUntilNanos = now + ns(skipMs);
			return;
		}

		// Most cycles wait for the sprint prep so the hit is not classified as a sweep.
		if (holdForNormalHit(client, now)) return;
		if (!CombatModeRuntime.triggerEnabledFor(config, lockedTarget)) return;
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.TRIGGER)) return;

		// Let vanilla perform the attack so its range, charge and item gates all apply.
		float attackProgressBefore = client.player.getAttackCooldownProgress(0.0F);
		mc.invokeDoAttack(); // the returned boolean is not a success result in 1.21.11
		float attackProgressAfter = client.player.getAttackCooldownProgress(0.0F);
		lastObservedCooldown = attackProgressAfter;
		if (attackProgressAfter >= attackProgressBefore - 1.0E-4F) {
			clearCyclePlan();
			return;
		}
		hitStreak++;
		lastAttackNanos = now;
		if (CombatModePolicy.mode(config) == CombatMode.AXE) {
			CombatModeRuntime.consumeAxeFollowup(lockedTarget);
		}
		clearCyclePlan();
	}

	/**
	 * Delay from crosshair-cross to the first swing of a lock, sampled from the configured
	 * reaction window and scaled by distance, fight continuity and focus.
	 */
	private double firstHitDelay(long now, ClientPlayerEntity self, PlayerEntity target) {
		CombatModeProfile.Trigger tuning = tuning();
		double min = MathHelper.clamp(Math.min(tuning.reactionMinMs(), tuning.reactionMaxMs()), 0, 300);
		double max = MathHelper.clamp(Math.max(tuning.reactionMinMs(), tuning.reactionMaxMs()), 5, 300);

		// Two averaged samples give a soft centre; distance then scales 0.66x to 1.0x.
		double sample = min + (max - min) * ((rng.nextDouble() + rng.nextDouble()) * 0.5D);
		double distance = Math.sqrt(self.squaredDistanceTo(target));
		double distanceT = MathHelper.clamp(distance / attackReach(), 0.0D, 1.0D);
		double distanceMultiplier = 0.66D + 0.34D * distanceT;

		// Re-crossing during the same exchange uses a shorter delay.
		boolean midFight = now - lastAttackNanos < 3_500_000_000L;
		double fightMultiplier = midFight ? 0.72D : 1.0D;
		double focusMultiplier = 1.08D - focus * 0.16D;
		double scaled = sample * distanceMultiplier * fightMultiplier * focusMultiplier;
		return MathHelper.clamp(scaled, min, max);
	}

	/**
	 * The beat between the charge topping off and a follow-up swing landing.
	 * Half-normal distribution scaled by the Follow-up setting and tightened by focus.
	 */
	private double commitDelayMs(boolean closingWindow) {
		double scale = MathHelper.clamp(tuning().followupMs(), 20, 200) / 80.0D;
		if (closingWindow) {
			double urgent = (4D + Math.abs(rng.nextGaussian()) * 12D) * scale;
			if (rng.nextDouble() < 0.05D) urgent += (20D + rng.nextDouble() * 35D) * scale;
			return Math.min(60D, urgent * (1.08D - focus * 0.16D));
		}
		double base = (12D + Math.abs(rng.nextGaussian()) * 44D) * scale;
		if (rng.nextDouble() < 0.16D) base += (65D + rng.nextDouble() * 205D) * scale;
		return Math.min(150D, base * (1.12D - focus * 0.24D));
	}

	private float thresholdFromSample(ClientPlayerEntity player, WeaponClass weapon, double sample) {
		CombatModeProfile.Trigger tuning = tuning();
		// The Cooldown setting is the top of the sampled band; getAttackCooldownProgress
		// already accounts for each weapon's attack speed.
		float high = MathHelper.clamp(tuning.cooldownPct(), 60, 100) / 100.0F;
		float spread;
		if (tuning.sprintAwareCooldown() && (weapon == WeaponClass.SWORD || weapon == WeaponClass.AXE)) {
			spread = player.isSprinting() ? 0.10F : 0.05F;
		} else {
			spread = tuning.patient() ? 0.05F : 0.12F;
		}
		float low = Math.max(0.55F, high - spread);
		if (tuning.patient()) sample = Math.sqrt(sample); // bias toward the strong end of the band
		return (float) (low + (high - low) * sample);
	}

	private void planAttackCycle(ClientPlayerEntity player, WeaponClass weapon, boolean sprinting) {
		CombatModeProfile.Trigger tuning = tuning();
		cyclePlanReady = true;
		plannedWeapon = weapon;
		plannedSprinting = sprinting;
		cooldownSampleThisCycle = rng.nextDouble();
		cooldownThresholdThisCycle = thresholdFromSample(player, weapon, cooldownSampleThisCycle);
		double skipP = MathHelper.clamp(tuning.skipChancePct() / 100.0D
				+ (rng.nextDouble() - 0.5D) * 0.025D, 0.0D, 0.25D);
		skipThisCycle = rng.nextDouble() < skipP;
		allowNaturalSweepThisCycle = weapon == WeaponClass.SWORD
				&& rng.nextDouble() < tuning.naturalSweepChance();
		normalHitConversionRequiredThisCycle = weapon == WeaponClass.SWORD
				&& player.isOnGround() && !allowNaturalSweepThisCycle;
	}

	/** Starts the sprint prep input while the reaction and cooldown are still running. */
	private void maybePrepareNormalHit(MinecraftClient client, PlayerEntity target,
			long now, float cooldown, boolean urgent) {
		if (normalHitFallbackNanos != 0L) {
			if (now < normalHitFallbackNanos) return;
			clearNormalHitPrep();
			return; // do not emit repeated forward/sprint pulses
		}
		if (plannedWeapon != WeaponClass.SWORD
				|| allowNaturalSweepThisCycle
				|| normalHitScheduledAttempts > 0
				|| !normalHitConversionRequiredThisCycle
				|| isSprintClassifiedHitReady(client.player)
				|| cooldown + NORMAL_HIT_PREP_LEAD < cooldownThresholdThisCycle) {
			return;
		}
		scheduleNormalHitPrep(now, urgent || isClosingWindow(client.player, target));
	}

	private boolean scheduleNormalHitPrep(long now, boolean urgent) {
		if (normalHitScheduledAttempts >= MAX_NORMAL_HIT_PREP_ATTEMPTS) return false;
		normalHitScheduledAttempts++;
		normalHitPreparedThisCycle = true;
		normalHitInputStartNanos = urgent ? now : now + ns(rng.nextDouble() * 18D);
		normalHitAttackAfterNanos = normalHitInputStartNanos + ns(urgent
				? 12D + rng.nextDouble() * 33D
				: 25D + rng.nextDouble() * 55D);
		// Hold the input across the final cooldown ticks and the follow-up beat.
		normalHitInputUntilNanos = normalHitAttackAfterNanos + ns(urgent
				? 180D + rng.nextDouble() * 50D
				: 260D + rng.nextDouble() * 70D);
		normalHitFallbackNanos = normalHitInputUntilNanos + ns(50D + rng.nextDouble() * 50D);
		return true;
	}

	private boolean wouldSweepIfCharged(ClientPlayerEntity player) {
		if (plannedWeapon != WeaponClass.SWORD || !player.isOnGround() || player.isSprinting()) return false;
		double movementLimit = player.getMovementSpeed() * 2.5D;
		return player.getMovement().horizontalLengthSquared() < movementLimit * movementLimit;
	}

	/** Mirrors vanilla 1.21.11 grounded sword sweep predicate. */
	private boolean wouldVanillaSweep(ClientPlayerEntity player) {
		return player.getAttackCooldownProgress(0.5F) > 0.9F && wouldSweepIfCharged(player);
	}

	private boolean isClosingWindow(ClientPlayerEntity self, PlayerEntity target) {
		Vec3d separation = target.getEntityPos().subtract(self.getEntityPos());
		double distance = separation.length();
		if (distance < 1.0E-4D) return false;
		double radialVelocity = separation.dotProduct(target.getVelocity().subtract(self.getVelocity())) / distance;
		return distance > attackReach() - 0.22D || radialVelocity > 0.035D;
	}

	private boolean holdForNormalHit(MinecraftClient client, long now) {
		ClientPlayerEntity player = client.player;
		boolean genuineCrit = inCritWindow(player);
		boolean sprintConversionPending = normalHitConversionRequiredThisCycle
				&& !allowNaturalSweepThisCycle
				&& !genuineCrit
				&& !isSprintClassifiedHitReady(player);
		if (!wouldVanillaSweep(player) && !sprintConversionPending) {
			// A prep-started sprint still waits out its sampled minimum beat.
			if (normalHitAttackAfterNanos != 0L
					&& player.isSprinting()
					&& now < normalHitAttackAfterNanos) {
				return true;
			}
			clearNormalHitPrep();
			return false;
		}
		if (allowNaturalSweepThisCycle) {
			clearNormalHitPrep();
			return false;
		}

		// The soft attempt's window ended without sprint, so spend the one permitted retry now.
		if (normalHitFallbackNanos != 0L
				&& now >= normalHitInputUntilNanos
				&& !player.isSprinting()) {
			clearNormalHitPrep();
			if (!scheduleNormalHitPrep(now, true)) abandonNormalHitCycle(now);
			return true;
		}
		if (normalHitFallbackNanos == 0L) {
			if (!scheduleNormalHitPrep(now, true)) abandonNormalHitCycle(now);
			return true;
		}
		return true;
	}

	/** True once vanilla sees both a real sprint and a full charge. */
	private boolean isSprintClassifiedHitReady(ClientPlayerEntity player) {
		return player.isSprinting() && player.getAttackCooldownProgress(0.5F) > 0.9F;
	}

	/** Abandons an unsafe conversion and re-plans after a short pause. */
	private void abandonNormalHitCycle(long now) {
		clearCyclePlan();
		skipUntilNanos = now + ns(45D + rng.nextDouble() * 55D);
	}

	private boolean canSafelyPrepareNormalHit(MinecraftClient client, PlayerInput input) {
		if (client == null || client.player == null || client.world == null || client.currentScreen != null) return false;
		ClientPlayerEntity player = client.player;
		if (!CombatModeRuntime.triggerEnabledFor(config, lockedTarget)
				|| !player.isAlive()
				|| !player.isOnGround()
				|| player.isSneaking()
				|| player.isClimbing()
				|| player.isTouchingWater()
				|| player.isInLava()
				|| player.hasVehicle()
				|| player.isGliding()
				|| player.getAbilities().flying
				|| !player.getHungerManager().canSprint()
				|| player.horizontalCollision
				|| client.options.backKey.isPressed()) {
			return false;
		}

		double forward = input.forward() == input.backward() ? 0.0D : (input.forward() ? 1.0D : -1.0D);
		double sideways = input.left() == input.right() ? 0.0D : (input.left() ? 1.0D : -1.0D);
		double magnitude = Math.sqrt(forward * forward + sideways * sideways);
		if (magnitude < 1.0E-6D) return false;
		forward /= magnitude;
		sideways /= magnitude;
		double yaw = Math.toRadians(player.getYaw());
		double stepX = (sideways * Math.cos(yaw) - forward * Math.sin(yaw)) * NORMAL_HIT_STEP;
		double stepZ = (forward * Math.cos(yaw) + sideways * Math.sin(yaw)) * NORMAL_HIT_STEP;
		Box next = player.getBoundingBox().offset(stepX, 0.0D, stepZ);
		if (!client.world.isSpaceEmpty(player, next)) return false;
		// Probe under the projected centre; support under the trailing edge alone is not enough.
		double centerX = (next.minX + next.maxX) * 0.5D;
		double centerZ = (next.minZ + next.maxZ) * 0.5D;
		Box support = new Box(centerX - 0.18D, next.minY - 0.18D, centerZ - 0.18D,
				centerX + 0.18D, next.minY - 0.02D, centerZ + 0.18D);
		return !client.world.isSpaceEmpty(player, support);
	}

	private double axePostDelayMs() {
		double center = MathHelper.clamp(tuning().axePostDelayMs(), 0, 300);
		if (center <= 0D) return 0D;
		double min = Math.max(0D, center - 20D);
		double max = Math.min(320D, center + 20D);
		return min + rng.nextDouble() * (max - min);
	}

	private WeaponClass weaponClass(ClientPlayerEntity player) {
		if (player.getMainHandStack().isIn(ItemTags.SWORDS)) return WeaponClass.SWORD;
		if (player.getMainHandStack().isIn(ItemTags.AXES)) return WeaponClass.AXE;
		return WeaponClass.OTHER;
	}

	private double attackReach() {
		if (!config.reach) return BASE_REACH;
		return Math.max(BASE_REACH, MathHelper.clamp(config.reachCm, 300, 600) / 100.0D);
	}

	private void driftFocus(long now) {
		if (lastNanos == 0L) { lastNanos = now; return; }
		double dt = (now - lastNanos) / 1_000_000_000.0D;
		lastNanos = now;
		focus += rng.nextGaussian() * 0.13D * dt;
		focus = Math.max(0.28D, Math.min(1.0D, focus));
	}

	private long ns(double ms) { return (long)(ms * 1_000_000D); }

	/**
	 * Raycasts from the eye along the current rotation against the union of each target's
	 * rendered and current-tick hitboxes. Extends {@link #TRACKING_MARGIN} past attack reach;
	 * this ray can only arm, since the swing is gated on both vanilla crosshair rays.
	 */
	private PlayerEntity getAimedPlayer(MinecraftClient client) {
		if (client.world == null) return null;
		ClientPlayerEntity self = client.player;
		double reach = attackReach() + TRACKING_MARGIN;
		Vec3d eye  = self.getEyePos();
		Vec3d end  = eye.add(self.getRotationVector().multiply(reach));
		double maxSq = (reach + 1.0D) * (reach + 1.0D);
		HitResult terrain = client.world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self));
		double terrainSq = terrain.getType() == HitResult.Type.MISS
				? Double.POSITIVE_INFINITY : terrain.getPos().squaredDistanceTo(eye);

		PlayerEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (PlayerEntity pl : client.world.getPlayers()) {
			if (!isValidTarget(client, pl)) continue;
			if (self.squaredDistanceTo(pl) > maxSq) continue;
			Box box = pl.getBoundingBox()
					.union(pl.getBoundingBox().offset(
							pl.lastRenderX - pl.getX(),
							pl.lastRenderY - pl.getY(),
							pl.lastRenderZ - pl.getZ()))
					.expand(HIT_EXPAND);
			if (box.contains(eye)) return pl; // a ray starting inside a box never intersects it
			java.util.Optional<Vec3d> hit = box.raycast(eye, end);
			if (hit.isPresent()) {
				double sq = hit.get().squaredDistanceTo(eye);
				if (sq <= terrainSq + 1.0E-7D && sq < bestSq) {
					bestSq = sq;
					best = pl; // nearest visible crossed player
				}
			}
		}
		if (best != null) return best;

		// targetedEntity is derived from the cached crosshairTarget, so use a fresh ray instead.
		return getFreshCrosshairPlayer(client);
	}

	/** Eligibility for a vanilla air crit. */
	private boolean canAttemptAirCrit(ClientPlayerEntity player) {
		return !player.isOnGround()
				&& !player.isSprinting()
				&& !player.isClimbing()
				&& !player.isTouchingWater()
				&& !player.isInLava()
				&& !player.hasVehicle()
				&& !player.isGliding()
				&& !player.getAbilities().flying
				&& !player.hasStatusEffect(StatusEffects.BLINDNESS)
				&& !player.hasStatusEffect(StatusEffects.LEVITATION)
				&& !player.hasStatusEffect(StatusEffects.SLOW_FALLING);
	}

	private boolean inCritWindow(ClientPlayerEntity player) {
		return canAttemptAirCrit(player)
				&& player.fallDistance > 0.065F
				&& player.getVelocity().y < 0.0D; // descending, past the apex
	}

	/** Returns the player under the vanilla crosshair ray, or null. */
	private PlayerEntity getCrosshairPlayer(MinecraftClient client) {
		HitResult hit = client.crosshairTarget;
		if (!(hit instanceof EntityHitResult ehr)) return null;
		Entity ent = ehr.getEntity();
		if (ent instanceof PlayerEntity pl && isValidTarget(client, pl)) return pl;
		return null;
	}

	private PlayerEntity getFreshCrosshairPlayer(MinecraftClient client) {
		ClientPlayerEntity self = client.player;
		Entity camera = client.getCameraEntity();
		// Fire runs in the tick input phase, where a render-partial ray can be one step behind.
		HitResult fresh = self.getCrosshairTarget(1.0F, camera == null ? self : camera);
		if (!(fresh instanceof EntityHitResult entityHit)) return null;
		Entity entity = entityHit.getEntity();
		return entity instanceof PlayerEntity player && isValidTarget(client, player) ? player : null;
	}

	private boolean isValidTarget(MinecraftClient client, PlayerEntity target) {
		return target != client.player
				&& target != client.getCameraEntity()
				&& target.isAlive()
				&& target.getHealth() > 0.0F
				&& !target.isSpectator();
	}

	public String status(MinecraftClient client) {
		if (!triggerConfigured()) return "Off";
		if (client == null || client.player == null) return "Idle";
		if (lockedTarget == null) return "Watching";
		long now = System.nanoTime();
		if (now < skipUntilNanos) return "Skipping";
		if (armed) return "Loaded";
		return "Tracking";
	}

	private boolean isAllowed(MinecraftClient client) {
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null) return false;
		if (client.currentScreen != null) return false;
		if (!client.player.isAlive() || client.player.getHealth() <= 0.0F) return false;
		if (client.player.isUsingItem()) return false;
		if (tuning().disableWhileSneaking() && client.player.isSneaking()) return false;
		if (client.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) return false;

		CombatMode mode = CombatModePolicy.mode(config);
		if (mode == CombatMode.MACE) return false;
		if (mode == CombatMode.AXE) {
			UUID followup = CombatModeRuntime.axeFollowupTarget();
			return weaponClass(client.player) == WeaponClass.SWORD
					&& CombatModeRuntime.triggerEnabledFor(config, followup);
		}
		if (mode == CombatMode.SWORD && weaponClass(client.player) != WeaponClass.SWORD) return false;
		return CombatModePolicy.enabled(config, CombatFeature.TRIGGER);
	}

	private boolean triggerConfigured() {
		return switch (CombatModePolicy.mode(config)) {
			case OFF, SWORD -> CombatModePolicy.enabled(config, CombatFeature.TRIGGER);
			case AXE -> CombatModePolicy.enabled(config, CombatFeature.AXE_TRIGGER_FOLLOWUP);
			case MACE -> false;
		};
	}

	private CombatModeProfile.Trigger tuning() {
		return CombatModePolicy.trigger(config);
	}

	private void clearCyclePlan() {
		clearNormalHitPrep();
		armed = false;
		commitScheduled = false;
		cyclePlanReady = false;
		plannedWeapon = WeaponClass.OTHER;
		plannedSprinting = false;
		skipThisCycle = false;
		allowNaturalSweepThisCycle = false;
		normalHitPreparedThisCycle = false;
		normalHitConversionRequiredThisCycle = false;
		normalHitScheduledAttempts = 0;
	}

	private void clearNormalHitPrep() {
		normalHitInputStartNanos = 0L;
		normalHitInputUntilNanos = 0L;
		normalHitAttackAfterNanos = 0L;
		normalHitFallbackNanos = 0L;
	}

	private void release() {
		lockedTarget        = null;
		targetLastSeenNanos = 0L;
		fireAfterNanos      = 0L;
		skipUntilNanos      = 0L;
		settledSinceNanos   = 0L;
		hitStreak           = 0;
		lastObservedCooldown = -1.0F;
		clearCyclePlan();
	}

}
