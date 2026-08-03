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
 * Trigger-bot hit assist.
 *
 * Architecture:
 *   tick() — manages lock, cooldown watching, arm/skip scheduling
 *   getAimedPlayer() — custom raycast using CURRENT rotation vector
 *                       (more accurate than client.crosshairTarget which lags
 *                       one frame behind aim-assist adjustments)
 *
 * Key design rules:
 *   - the reaction clock starts ONCE, when the lock is acquired, and nothing after it
 *     (arming, cooldown refill, a failed crosshair confirm) ever restarts it. The old
 *     flow chained lock-delay THEN reaction THEN re-arm penalties sequentially, which
 *     is why the trigger lost the first-hit race in even duels.
 *   - fresh target acquisition samples one bounded, distance-aware reaction delay;
 *     reacquisition during the same exchange uses faster click-stream alignment
 *   - armed is NOT reset on temporary aim loss; it survives the retention window
 *     so the shot fires the moment aim returns without a re-arm penalty
 *   - skip is sampled once per cycle and produces one bounded hesitation
 *   - the SWING is emitted only with a confirmed entity attack on a real tick:
 *     vanilla crosshairTarget must be the locked player and the internal attack
 *     cooldown must be clear
 */
public final class HitImprovementsController {

	// Remember the UUID for three seconds, but retain a charged shot for only the short
	// aim-flick grace. A literal three-second armed lock would pre-charge attacks off target.
	private static final long   TARGET_MEMORY_NS = 3_000_000_000L;
	private static final long   ARM_RETENTION_NS = 700_000_000L;
	private static final double BASE_REACH    = 3.0D;         // vanilla attack reach (fire is vanilla-gated anyway); Reach module raises it
	private static final double TRACKING_MARGIN = 0.45D;      // soft pre-contact lock only; the vanilla fire gate still enforces real reach
	private static final double HIT_EXPAND    = 0.20D;        // arm-only drift tolerance — the swing itself is still vanilla-confirmed
	private static final long   SETTLE_GRACE_NS = 35_000_000L; // brief aim flicker that doesn't reset the settle
	private static final long   PREP_RETENTION_NS = 160_000_000L; // keep pre-hit input through a short S-tap/ray flicker
	private static final float  NORMAL_HIT_PREP_LEAD = 0.18F; // begin sprint prep about two sword-cooldown ticks before ready
	private static final int    MAX_NORMAL_HIT_PREP_ATTEMPTS = 2; // one soft attempt + one hard-contact retry
	private static final double NORMAL_HIT_STEP = 0.32D;      // short, collision-checked forward tap used to establish a real sprint hit

	private enum WeaponClass { SWORD, AXE, OTHER }

	private static HitImprovementsController instance;

	private final ProFPSConfig  config;
	private final SecureRandom  rng = new SecureRandom();

	private UUID   lockedTarget;
	private long   targetLastSeenNanos;
	private long   lastAttackNanos;
	private float  lastObservedCooldown = -1.0F;

	// Two-phase timing: arm fires when cooldown ready, fire after per-cycle delay
	private boolean armed;
	private long    fireAfterNanos;
	private long    skipUntilNanos;   // skip sits out this whole window
	private long    commitAtNanos;    // the swing lands a human beat AFTER the charge fills, not the exact instant
	private boolean commitScheduled;  // one commit-beat per cooldown cycle (re-armed each cycle)
	private float   cooldownThresholdThisCycle;
	private double  cooldownSampleThisCycle;
	private WeaponClass plannedWeapon = WeaponClass.OTHER;
	private boolean plannedSprinting;
	private boolean skipThisCycle;
	private boolean allowNaturalSweepThisCycle;
	private boolean normalHitPreparedThisCycle;
	private boolean normalHitConversionRequiredThisCycle;
	private int normalHitScheduledAttempts;

	// A full-charge, grounded, slow sword swing is unavoidably a sweep in vanilla.
	// For normal-hit cycles we publish a short forward+sprint input BEFORE the attack
	// tick, then wait until sprinting is genuinely established. No velocity or packet
	// state is forged, and the vanilla attack remains in its existing packet phase.
	private long normalHitInputStartNanos;
	private long normalHitInputUntilNanos;
	private long normalHitAttackAfterNanos;
	private long normalHitFallbackNanos;

	// Settle dwell: the aim must rest ON the target before a hit fires, so the
	// attack never lands on the exact frame the assist snaps the crosshair on
	// (that rotation↔attack correlation is what aim-pattern checks catch).
	private long    settledSinceNanos;
	private long    fireDwellNanos = 25_000_000L;

	private int     hitStreak;

	// Focus drifts slowly to produce natural variance in timing across a fight
	private double  focus    = 0.82D;
	private long    lastNanos;

	private boolean cyclePlanReady;

	public HitImprovementsController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/**
	 * Layers the triggerbot's short pre-hit W+sprint tap onto whichever legitimate
	 * movement path currently owns input (raw keys, Sword AI, or Strafe Assist).
	 * Returns {@code null} when no pre-hit tap is active so input passes untouched.
	 */
	public static PlayerInput normalHitOverride(PlayerInput current) {
		HitImprovementsController controller = instance;
		if (controller == null || current == null) return null;
		long now = System.nanoTime();
		if (now < controller.normalHitInputStartNanos || now >= controller.normalHitInputUntilNanos) return null;

		MinecraftClient client = MinecraftClient.getInstance();
		// Manual retreat, sneak, or jump always wins. Jumping already creates a
		// legitimate non-sweep state, so forcing sprint on top would only spoil a crit.
		if (current.backward() || current.sneak() || current.jump() || client.options.backKey.isPressed()) return null;
		PlayerInput prepared = new PlayerInput(true, false, current.left(), current.right(), false, false, true);
		if (!controller.canSafelyPrepareNormalHit(client, prepared)) return null;
		return prepared;
	}

	/**
	 * Frees an airborne swing to land as a crit by tapping off the forward key.
	 *
	 * <p>Vanilla refuses a crit outright while the attacker is sprinting, and a
	 * sprint only ends when the published input stops carrying forward movement —
	 * dropping the sprint key alone leaves an established sprint running
	 * (verified against {@code ClientPlayerEntity.shouldStopSprinting}). So the
	 * only thing that actually converts a sprinting jump into a crit is the same
	 * W-tap a player does by hand, which is exactly what this publishes. Air
	 * control means momentum carries through the tap, so the jump still travels.
	 *
	 * <p>It only fires while airborne with a real target in front: on the ground
	 * there is no crit to protect, and away from a fight this would be an
	 * unexplained stutter in ordinary movement.
	 */
	public static PlayerInput critSprintOverride(PlayerInput current) {
		HitImprovementsController controller = instance;
		if (controller == null || current == null) return null;
		if (!controller.config.hitCritTiming || !controller.config.hitCritSprintRelease) return null;

		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isSprinting() || player.isOnGround()) return null;
		// Manual retreat or sneak is the player's own intent and already ends the
		// sprint; never layer a tap on top of it.
		if (current.backward() || current.sneak() || !current.forward()) return null;
		if (player.isClimbing() || player.isTouchingWater() || player.isInLava()
				|| player.hasVehicle() || player.isGliding() || player.getAbilities().flying) return null;
		if (controller.getCrosshairPlayer(client) == null) return null;

		return new PlayerInput(false, false, current.left(), current.right(),
				current.jump(), false, false);
	}

	// ── Main update ──────────────────────────────────────────────────────────

	public void tick(MinecraftClient client) {
		update(client, true);   // a real client tick may SEND the swing (vanilla packet order)
	}

	/**
	 * Also driven once per render frame: at 20Hz tick cadence alone, just the
	 * sampling quantization added up to 50ms before the trigger even SAW the
	 * crosshair cross the hitbox — in a strafing duel that window is the
	 * whole opportunity.
	 */
	public void frame(MinecraftClient client) {
		update(client, false);  // frames only track + arm; never send a packet off-tick (Grim "Post")
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

		// ── Retention / release ───────────────────────────────────────────
		if (aimed == null) {
			// Don't immediately disarm — aim naturally drifts off for a tick due to
			// spring momentum or rendering interpolation. The target UUID remains a
			// soft three-second memory, but an attack cannot stay pre-charged that long.
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
						settledSinceNanos = 0L; // off the target long enough — must re-settle before firing
					}
				}
			}
			return;
		}

		long unseenNanos = lockedTarget == null ? Long.MAX_VALUE : now - targetLastSeenNanos;
		targetLastSeenNanos = now;

		// ── Acquire or switch lock ────────────────────────────────────────
		if (!aimed.getUuid().equals(lockedTarget)) {
			lockedTarget    = aimed.getUuid();
			hitStreak       = 0;
			clearCyclePlan();
			skipUntilNanos  = 0L;
			settledSinceNanos = now;
			fireDwellNanos  = ns(tuning.settleMinMs()
					+ rng.nextDouble() * Math.max(0, tuning.settleMaxMs() - tuning.settleMinMs()));

			// The reaction clock starts HERE, once. Everything downstream (cooldown
			// refill, arming, a failed crosshair confirm) waits on or extends this
			// clock — nothing restarts it. The old flow added a lock delay AND a full
			// arm-time reaction back to back, which alone cost 110-190 ms per exchange.
			fireAfterNanos = now + ns(firstHitDelay(now, client.player, aimed));
			commitScheduled = false;
			WeaponClass weapon = weaponClass(client.player);
			planAttackCycle(client.player, weapon, client.player.isSprinting());
			maybePrepareNormalHit(client, aimed, now,
					client.player.getAttackCooldownProgress(0.0F), true);
			return;
		}

		// Reacquiring the remembered UUID after a meaningful aim loss is a fresh
		// visual acquisition. Keep the target identity, but never inherit an elapsed
		// reaction timer or a charged axe delay from when it was off-screen.
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

		// Track the cooldown and prepare real sprint input while reaction is still running.
		float cd = client.player.getAttackCooldownProgress(0.0F);
		if (lastObservedCooldown >= 0.0F && cd + 0.10F < lastObservedCooldown) {
			// A manual or another module's attack consumed the previous cycle.
			// Never carry its sampled threshold, skip, or axe timer into the next one.
			clearCyclePlan();
			skipUntilNanos = 0L;
		}
		lastObservedCooldown = cd;

		// ── Skip window ───────────────────────────────────────────────────
		// When a skip fires we sit out a visible beat before resuming the same cycle.
		// Without this, armed=false + cooldown still ready = immediate re-arm.
		if (now < skipUntilNanos) {
			return;
		}

		// Respect vanilla's short miss/decline click cooldown, but do not let it
		// repeatedly reroll a planned threshold while it counts down.
		MinecraftClientInvoker mc = (MinecraftClientInvoker)(Object)client;
		if (mc.profps$getAttackCooldown() > 0) {
			clearCyclePlan();
			return;
		}

		// ── Cooldown gate ─────────────────────────────────────────────────
		WeaponClass weapon = weaponClass(client.player);
		boolean sprinting = client.player.isSprinting();
		if (cyclePlanReady && plannedWeapon != weapon) {
			// A weapon swap needs a new threshold/post-delay, but a planned skipped
			// click remains the same once-per-cycle decision.
			boolean preserveSkip = skipThisCycle;
			clearCyclePlan();
			planAttackCycle(client.player, weapon, sprinting);
			skipThisCycle = preserveSkip;
		} else if (cyclePlanReady
				&& tuning.sprintAwareCooldown()
				&& (weapon == WeaponClass.SWORD || weapon == WeaponClass.AXE)
				&& !normalHitPreparedThisCycle
				&& plannedSprinting != sprinting) {
			// Recalculate only the context-dependent threshold from the same per-cycle
			// percentile. If an axe was already
			// ready, its running post-delay is preserved across a normal W-tap.
			plannedSprinting = sprinting;
			cooldownThresholdThisCycle = thresholdFromSample(client.player, weapon, cooldownSampleThisCycle);
		}
		if (!cyclePlanReady) planAttackCycle(client.player, weapon, sprinting);
		maybePrepareNormalHit(client, aimed, now, cd,
				hitStreak == 0 || isClosingWindow(client.player, aimed));

		// Planning and genuine sprint input above advance during the look-delay so
		// reaction, cooldown, and anti-sweep preparation overlap instead of stack.
		if (!armed && now < fireAfterNanos) return;

		float threshold = cooldownThresholdThisCycle;
		// A crit only triggers above ~0.9 charge. When crit-timing is on and you're airborne
		// (lining up a jump-crit), never let the randomized threshold dip below that — a swing
		// during the fall on a weak charge lands as a non-crit and wastes the jump.
		if (tuning.critTiming() && canAttemptAirCrit(client.player)) threshold = Math.max(threshold, 0.92F);

		if (cd < threshold) {
			// Cooldown not ready — disarm so we re-arm fresh when it is
			armed = false;
			commitScheduled = false;
			return;
		}

		// Cooldown just filled this cycle → commit to a short human beat before the swing.
		// A real player's click does not land the exact frame the charge bar tops off; it
		// lands a variable moment later. That beat (added to the cooldown time) IS the
		// inter-hit interval, so hits spread into a human distribution instead of firing on a
		// fixed period locked to the cooldown — the metronome tell that read as blatant.
		if (!armed) {
			armed = true;
			// The first sword hit already waited firstHitDelay, but an axe always gets
			// its own post-ready beat. Follow-up motor jitter and axe delay overlap
			// rather than stack, avoiding a needlessly sluggish double-delay.
			double commitMs = hitStreak > 0 ? commitDelayMs(isClosingWindow(client.player, aimed)) : 0D;
			if (plannedWeapon == WeaponClass.AXE) commitMs = Math.max(commitMs, axePostDelayMs());
			commitScheduled = commitMs > 0D;
			if (commitScheduled) commitAtNanos = now + ns(commitMs);
		}

		// ── Settle dwell ──────────────────────────────────────────────────
		// The crosshair must have RESTED on the target, not just snapped on
		// this instant. Decouples the attack from the rotation correction.
		if (now - settledSinceNanos < fireDwellNanos) return;

		// Only ever SEND a swing on a real client tick, so the attack packet stays in the
		// vanilla flying→action order. Firing from a render frame drops an action packet
		// between ticks with no flying packet bracketing it — that's Grim's "Post". All the
		// arm/reaction/settle timing above still advances on frames, so nothing slows down.
		if (!canFire) return;

		// ── Human commit beat ──────────────────────────────────────────────
		// The swing lands this beat after the charge filled (scheduled once per cycle above).
		if (commitScheduled && now < commitAtNanos) return;

		// ── Crit timing ───────────────────────────────────────────────────
		// Hold the swing until the hit will ACTUALLY land as a crit. Vanilla only crits while you're
		// FALLING with real fall distance — not on the ground, and NOT at the jump's apex where
		// fallDistance is still 0. The old gate released the instant you stopped rising (≈ the apex),
		// so the held swing burned on a normal hit and the cooldown wasn't ready again by the time you
		// were truly falling — which is why repeated jump-crits almost never crit. Now we stay armed
		// through the rise AND the apex and fire the moment you're genuinely in the crit window, so
		// crit-spam connects. On the ground we never hold (no crit possible) and fire as usual.
		if (tuning.critTiming() && canAttemptAirCrit(client.player) && !inCritWindow(client.player)) return;

		// ── Confirm aim at fire moment ────────────────────────────────────
		// ONLY fire when the real vanilla crosshair is on the locked player. The old
		// fallback called attackEntity() on the target whenever our OWN ray hit it even though
		// crosshairTarget didn't; but
		// the server validates the hit with the SAME raycast as crosshairTarget, so any time
		// they disagreed we were attacking a player the server doesn't see under our cursor —
		// a hard hitbox/aimbot flag (the "2 hits and banned" on strict ACs like Minemen).
		// If the crosshair isn't genuinely on them, we simply don't swing this cycle.
		PlayerEntity crosshairNow = getCrosshairPlayer(client);
		PlayerEntity freshCrosshairNow = getFreshCrosshairPlayer(client);
		if (crosshairNow == null || freshCrosshairNow == null
				|| !crosshairNow.getUuid().equals(lockedTarget)
				|| !freshCrosshairNow.getUuid().equals(lockedTarget)) {
			// Committed, but the vanilla ray isn't genuinely on them this instant — hold and
			// fire the moment it is (a legal, crosshair-confirmed hit) rather than forcing a wait.
			return;
		}

		// ── Skip decision ─────────────────────────────────────────────────
		// Commit the one sampled decision only after a legal target is actually
		// under the vanilla reticle. Expanded-box acquisition can never consume it.
		if (skipThisCycle) {
			skipThisCycle = false;
			armed = true; // remain loaded; the skipped click itself is the whole delay
			commitScheduled = false;
			double skipMs = isClosingWindow(client.player, aimed)
					? 55D + rng.nextDouble() * 45D
					: 80D + rng.nextDouble() * 80D;
			if (rng.nextDouble() < 0.08D) skipMs += 70D + rng.nextDouble() * 100D;
			skipUntilNanos = now + ns(skipMs);
			return;
		}

		// A slow, grounded, >90%-charged sword hit is a vanilla sweep. Most cycles
		// take a delayed real-input W+sprint path so the server classifies the same
		// vanilla attack as a full-strength single-target sprint hit. A small choice,
		// sampled once with the rest of the cycle plan, leaves naturally eligible
		// sweeps alone so the fight does not become mechanically perfect. If sprint
		// preparation is unsafe or impossible, treat it as a missed human opportunity
		// and retry later rather than moving over an edge or falling through to a sweep.
		if (holdForNormalHit(client, now)) return;
		if (!CombatModeRuntime.triggerEnabledFor(config, lockedTarget)) return;
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.TRIGGER)) return;

		// Let vanilla perform the already-confirmed entity attack. This retains its
		// riding, enabled-item, minimum-charge, per-item range and piercing-weapon
		// gates while still producing exactly the normal attack + swing sequence.
		float attackProgressBefore = client.player.getAttackCooldownProgress(0.0F);
		mc.invokeDoAttack(); // its boolean is not a success result in 1.21.11
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

	// ── Timing helpers ───────────────────────────────────────────────────────

	/**
	 * Delay from crosshair-cross to the first swing of a lock. Modeled as CLICK-STREAM
	 * ALIGNMENT, not a fresh visual reaction: a PvPer in a fight is already clicking at
	 * 8-14 CPS while tracking, so the hit lands wherever the next click in the stream
	 * falls after the cross. Reaction Min/Max define the sampled acquisition window;
	 * distance, fight continuity, and slowly drifting focus scale it without stacking a
	 * second reaction after the cooldown fills.
	 */
	private double firstHitDelay(long now, ClientPlayerEntity self, PlayerEntity target) {
		CombatModeProfile.Trigger tuning = tuning();
		double min = MathHelper.clamp(Math.min(tuning.reactionMinMs(), tuning.reactionMaxMs()), 0, 300);
		double max = MathHelper.clamp(Math.max(tuning.reactionMinMs(), tuning.reactionMaxMs()), 5, 300);

		// Average two samples for a soft centre instead of a flat/robotic uniform
		// distribution, then scale smoothly from 0.66x at point blank to 1.0x at reach.
		double sample = min + (max - min) * ((rng.nextDouble() + rng.nextDouble()) * 0.5D);
		double distance = Math.sqrt(self.squaredDistanceTo(target));
		double distanceT = MathHelper.clamp(distance / attackReach(), 0.0D, 1.0D);
		double distanceMultiplier = 0.66D + 0.34D * distanceT;

		// Re-crossing in the same exchange is click-stream alignment, not a second
		// full visual reaction. Keep it faster without ever becoming a zero-delay hit.
		boolean midFight = now - lastAttackNanos < 3_500_000_000L;
		double fightMultiplier = midFight ? 0.72D : 1.0D;
		double focusMultiplier = 1.08D - focus * 0.16D;
		double scaled = sample * distanceMultiplier * fightMultiplier * focusMultiplier;
		return MathHelper.clamp(scaled, min, max);
	}

	/**
	 * The beat between the charge topping off and the swing actually landing, for a follow-up
	 * hit in a combo. A real player isn't frame-perfect on the cooldown indicator: the click
	 * lands a short, variable moment after full charge, and every so often a good bit later (a
	 * read, a reposition, a block). Modelled as a half-normal — most beats short, with a fat
	 * tail — and scaled by the Follow-up slider. Added to the (charge-dependent) cooldown time,
	 * this makes the interval between hits a spread-out human distribution instead of a fixed
	 * period locked to the cooldown, which is what looked robotic. Focus tightens it.
	 */
	private double commitDelayMs(boolean closingWindow) {
		double scale = MathHelper.clamp(tuning().followupMs(), 20, 200) / 80.0D;
		if (closingWindow) {
			double urgent = (4D + Math.abs(rng.nextGaussian()) * 12D) * scale;
			if (rng.nextDouble() < 0.05D) urgent += (20D + rng.nextDouble() * 35D) * scale;
			return Math.min(60D, urgent * (1.08D - focus * 0.16D));
		}
		double base = (12D + Math.abs(rng.nextGaussian()) * 44D) * scale;       // ~12-100ms typical
		if (rng.nextDouble() < 0.16D) base += (65D + rng.nextDouble() * 205D) * scale; // occasional longer beat
		return Math.min(150D, base * (1.12D - focus * 0.24D));
	}

	private float thresholdFromSample(ClientPlayerEntity player, WeaponClass weapon, double sample) {
		CombatModeProfile.Trigger tuning = tuning();
		// The Cooldown setting is the top of the sampled band. At the 93% default,
		// swords and axes use 83-93% while sprinting and 88-93% while walking.
		// getAttackCooldownProgress already includes each weapon's attack speed.
		float high = MathHelper.clamp(tuning.cooldownPct(), 60, 100) / 100.0F;
		float spread;
		if (tuning.sprintAwareCooldown() && (weapon == WeaponClass.SWORD || weapon == WeaponClass.AXE)) {
			spread = player.isSprinting() ? 0.10F : 0.05F;
		} else {
			spread = tuning.patient() ? 0.05F : 0.12F;
		}
		float low = Math.max(0.55F, high - spread);
		if (tuning.patient()) sample = Math.sqrt(sample); // patient biases toward the strong end, within the same band
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

	/**
	 * Start the real-input sprint reset while reaction/cooldown are still running.
	 * At fire time this should already be server-visible, so hard confirmation adds
	 * no new anti-sweep timer to a short reach window.
	 */
	private void maybePrepareNormalHit(MinecraftClient client, PlayerEntity target,
			long now, float cooldown, boolean urgent) {
		if (normalHitFallbackNanos != 0L) {
			if (now < normalHitFallbackNanos) return;
			clearNormalHitPrep();
			return; // never turn a soft lock into repeated forward/sprint pulses
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
		// Keep the real input alive long enough to overlap the final cooldown ticks
		// and the usual short follow-up beat; it is cleared immediately on attack.
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

	/** Mirrors vanilla 1.21.11's grounded sword sweep predicate. */
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
			// A prep-started sprint keeps a tiny sampled minimum beat. In a closing
			// window it normally releases on the first legal tick after START_SPRINT/W.
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

		// The soft attempt's input window has ended and hard legal contact is here:
		// use the one permitted hard retry immediately instead of waiting through the
		// bookkeeping fallback grace and losing a one-tick S-tap window.
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

	/** Fire converted grounded sword cycles only after vanilla sees real sprint + full charge. */
	private boolean isSprintClassifiedHitReady(ClientPlayerEntity player) {
		return player.isSprinting() && player.getAttackCooldownProgress(0.5F) > 0.9F;
	}

	/** Give up this unsafe conversion briefly, then plan a fresh human opportunity. */
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
		// Probe under the projected centre instead of the whole (overlapping) box;
		// support under only the trailing edge must not green-light a step into a drop.
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

	// ── Target detection ────────────────────────────────────────────────────

	/**
	 * Raycasts from the player's eye using the current rotation vector (which includes
	 * aim-assist adjustments from the previous WorldRenderEvents.END_MAIN) against a SWEPT
	 * box — the union of the target's previous-tick (rendered) and current-tick hitboxes.
	 * You aim at the RENDERED box you see; the tick box a plain raycast tests sits up to a
	 * whole tick of motion away (~0.28 blocks on a sprinting target), and at 2.8-3.0 blocks
	 * that offset was the whole miss. The union covers both. This arm-only ray extends 0.45
	 * blocks beyond the active attack reach so reaction and input prep can begin just before
	 * contact; the distance cull keeps its extra eye-to-box allowance. The swing remains gated
	 * on both vanilla crosshair rays, so the tracking margin cannot produce an out-of-range hit.
	 */
	private PlayerEntity getAimedPlayer(MinecraftClient client) {
		if (client.world == null) return null;
		ClientPlayerEntity self = client.player;
		// Track a narrowly extended, block-aware ray so reaction/cooldown/sprint prep
		// can finish as an approaching player crosses into legal reach. This ray can
		// only arm; both vanilla rays below still gate every actual attack.
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
			if (box.contains(eye)) return pl; // point blank: a ray from inside a box never "enters" it
			java.util.Optional<Vec3d> hit = box.raycast(eye, end);
			if (hit.isPresent()) {
				double sq = hit.get().squaredDistanceTo(eye);
				if (sq <= terrainSq + 1.0E-7D && sq < bestSq) {
					bestSq = sq;
					best = pl; // nearest visible crossed player, not first-in-list
				}
			}
		}
		if (best != null) return best;

		// Vanilla's targetedEntity is derived from the cached crosshairTarget in the
		// same update (not an independent ray). The useful fallback is a fresh,
		// block-aware vanilla ray using the current rotation.
		return getFreshCrosshairPlayer(client);
	}

	/**
	 * Eligibility for a normal vanilla air crit. Invalid environments fall back to an
	 * ordinary attack rather than freezing the trigger indefinitely.
	 */
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
				&& player.getVelocity().y < 0.0D; // actually descending, past the apex
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
		// Fire runs in the tick input phase. A render-partial ray can still be one
		// interpolated movement step behind exactly when an S-tap target crosses reach,
		// while vanilla's cached doAttack target is already tick-current.
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


	// ── Bookkeeping ──────────────────────────────────────────────────────────

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
