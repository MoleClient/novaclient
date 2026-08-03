package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.aim.SilentAimController;
import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * AutoMace — a mace-only auto-attack that actually turns your head onto the target so
 * the hit is legitimate (no rotation-spoofing, which anti-cheats flag). It locks the
 * nearest player in range (you often turn away mid-swoop) and, frame by frame, whips
 * your view onto a RANDOMIZED point inside their hitbox — fast and near-instant, but
 * smoothly capped per frame so it's a flick, not a teleport. A little tremor, the
 * occasional small overshoot, and a slow wandering bias keep it off dead-centre and
 * make it read like a real hand. Once the attack-time vanilla ray genuinely names the
 * target it attacks. Ground follow-ups retain their configured cooldown; first contact
 * overlaps reaction with charge and takes a legal low-charge click rather than tracking
 * through an entire mace recharge. A genuine descending smash takes its contact window.
 *
 * When an axe is in the hotbar it can run a stun-slam combo against a confirmed shielding
 * target: a quick axe tap, then a controlled swap back to the mace for the smash.
 */
public final class AutoMaceController {
	private static final long DISENGAGE_NANOS = 120_000_000L; // brief post-hit gap
	/** Vanilla pays the smash bonus only past ~1.5 blocks of fall. */
	private static final float SMASH_FALL_BLOCKS = 1.5F;
	/** Arm the handoff a hair earlier so the mace is in hand in time — but above a flat jump (~1.25). */
	private static final float DIVE_FALL_BLOCKS = 1.3F;
	/** Keep the mace this long after the dive ends, so a landing follow-up still has it. */
	private static final long DIVE_HOLD_NANOS = 250_000_000L;
	/** Mid-air (a Wind Burst launch of your own) the mace is kept for the rest of the arc. */
	private static final long AIRBORNE_HOLD_NANOS = 1_250_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd(); // shared rotation grid → valid deltas

	private UUID targetUuid;
	private double fx, fy, fz; // relative hitbox offsets (0..1), never centre
	private long disengageUntilNanos;
	private long nextRetargetNanos;
	private long lastFrameNanos;
	private long onTargetSinceNanos; // when the crosshair first settled on the target this approach
	private long settleNeededNanos;  // dwell required before the swing — decouples hit from the snap
	private long chargeHoldBudgetNanos; // maximum first-contact wait for a stronger cooldown
	private long lastAttackNanos;
	private int attacksThisEngagement;
	private boolean autoEquippedThisEngagement;

	// Slow wandering aim bias + reroll timer — the "imperfect hand" mistake factor.
	private double biasYaw, biasPitch, biasYawTarget, biasPitchTarget;
	private long nextBiasNanos;

	// Stun-slam combo: tap the target with an axe (disables a raised shield + lands a hit),
	// then ~1 tick later swap straight back to the mace and smash before their hurt-invuln
	// resets. The axe slot is the same `shieldPhase` state machine; `maceSlot` is what we
	// swap back to. Fired when they're guarding a shield OR we're falling (a smash dive).
	private int shieldPhase;          // 0 idle, 1 axe selected, 2 waiting after the axe hit
	private int shieldActionAge;
	private long shieldWaitUntilNanos;
	private long shieldComboUntilNanos; // brief rest after a slam before re-initiating (stops a re-axe loop)
	private int maceSlot = -1;        // the hotbar slot we swap back to for the mace hit
	private int maceReadyAge;         // never select a mace and attack in the same client tick
	private boolean stunSmashFollowup; // bypass is permitted only inside the same genuine falling smash

	// The mace is a dive weapon, so the auto handoff is a loan: remember what was in hand before it
	// and give that back once the fall is over. Without this the Wind Burst mace stayed out while you
	// hopped around on the ground — and, worse, kept the sword out of your hand so Auto Breachswap
	// (which resolves with SWORD attributes) could never set up its next jump-crit swap.
	private int autoSwitchSlot = -1;       // the mace slot this controller selected, or -1
	private int autoSwitchReturnSlot = -1; // what was held before that handoff, or -1
	private long diveHoldUntilNanos;       // keep the loan until this passes

	public AutoMaceController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!ready(client)) {
			if (client.player == null || client.world == null || client.interactionManager == null) {
				shieldPhase = 0;
				maceSlot = -1;
				stunSmashFollowup = false;
				autoSwitchSlot = -1;
				autoSwitchReturnSlot = -1;
				diveHoldUntilNanos = 0L;
				clearEngagement();
				return;
			}
			// If interrupted mid shield-break (e.g. toggled off while holding the axe), restore the mace.
			if (shieldPhase != 0 && client.player != null) {
				if (!restoreMace(client, client.player)) return;
				shieldPhase = 0;
				stunSmashFollowup = false;
			}
			// Hand the pre-handoff item back even when the module goes quiet mid-dive.
			releaseAutoSwitch(client.player);
			clearEngagement();
			return;
		}
		ClientPlayerEntity player = client.player;
		CombatModeProfile.Mace tuning = CombatModePolicy.mace(config);
		long now = System.nanoTime();

		// A dive is the only thing that justifies holding the mace. Refresh the loan while it lasts,
		// and once it has expired put the previous weapon back — a mace left in hand between fights
		// is exactly what made this module feel stubborn and what starved Auto Breachswap of the sword.
		// A Wind Burst launch keeps it for the rest of the arc rather than swapping twice mid-air.
		boolean diving = isDiving(player);
		if (diving) diveHoldUntilNanos = now + DIVE_HOLD_NANOS;
		boolean grounded = player.isOnGround() || player.isTouchingWater();
		long holdUntilNanos = grounded ? diveHoldUntilNanos : diveHoldUntilNanos + AIRBORNE_HOLD_NANOS;
		if (shieldPhase == 0 && !diving && now >= holdUntilNanos) releaseAutoSwitch(player);

		boolean spearShieldDive = CombatModeRuntime.spearMaceActive()
				&& stunSlamEnabled()
				&& isFalling(player)
				&& findAxe(player) >= 0
				&& findMace(player) >= 0;
		if (shieldPhase == 0 && !player.getMainHandStack().isOf(Items.MACE)
				&& (!autoSwitchEnabled()
					|| !diving
					|| CombatModeRuntime.breachSwapHoldsHotbar()
					|| findMace(player) < 0)
				&& !spearShieldDive) {
			// Do not rotate toward players when this tick could not legally end in a mace hit:
			// no mace in hand and either nothing to equip, the handoff switched off, an armed
			// Breach Swap owning the hotbar, or simply no fall to smash with. This was also the
			// second "aims but never hits" path — tracking could run indefinitely even though the
			// attack stage had no item it could legally equip.
			clearEngagement();
			return;
		}
		PlayerEntity target = acquireTarget(client, player, tuning);
		if (target == null) {
			// Never get stranded on the axe if the target vanishes mid shield-break: go back to the mace.
			if (shieldPhase != 0) {
				if (!restoreMace(client, player)) return;
				shieldPhase = 0;
				stunSmashFollowup = false;
			}
			clearEngagement();
			return;
		}

		if (!target.getUuid().equals(targetUuid)) {
			if (shieldPhase != 0) {
				if (!restoreMace(client, player)) return;
				shieldPhase = 0;
				stunSmashFollowup = false;
				clearEngagement();
				return;
			}
			targetUuid = target.getUuid();
			attacksThisEngagement = 0;
			autoEquippedThisEngagement = false;
			beginGroundSettle(now, tuning);
			pickPoint();
			nextRetargetNanos = now + Math.max(1, tuning.retargetMs()) * 1_000_000L;
		} else if (now >= nextRetargetNanos) {
			pickPoint();
			long base = Math.max(1, tuning.retargetMs()) * 1_000_000L;
			nextRetargetNanos = now + base + (long) (rng.nextDouble() * base * 0.72D);
		}

		if (now < disengageUntilNanos) return;

		// Spear→Mace shield route: do not waste a tick selecting the mace only to
		// select the axe immediately afterward. While the Lunge target is genuinely
		// falling in front of us with a shield raised, pre-arm the axe directly.
		// Each later stage still gets its own complete server tick.
		if (shieldPhase == 0
				&& !player.getMainHandStack().isOf(Items.MACE)
				&& spearShieldDive
				&& isHoldingShield(target)
				&& confirmedVanillaTarget(client, player, target)) {
			int axe = findAxe(player);
			int plannedMace = findMace(player);
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
			int previous = player.getInventory().getSelectedSlot();
			maceSlot = plannedMace;
			autoSwitchSlot = plannedMace;
			if (autoSwitchReturnSlot < 0) autoSwitchReturnSlot = previous;
			autoEquippedThisEngagement = true;
			stunSmashFollowup = true;
			selectSlot(client, player, axe);
			shieldPhase = 1;
			shieldActionAge = player.age + 1;
			onTargetSinceNanos = 0L;
			return;
		}

		// ── Stun-slam state machine (BEFORE ordinary mace range/cooldown gates) ──
		// This is the fix for the inconsistency: the swap-back used to sit AFTER the distance and
		// crosshair gates, so a single tick of the aim drifting off (which happens constantly on a
		// fast dive) left us stranded holding the axe and the fall — the smash window — was wasted.
		// Once the axe tap has had its configured gap to register the shield-disable, we swap straight
		// back to the mace no matter what the aim/range is doing. Only a continuation of that same real
		// descending smash can use the narrow responsive follow-up; a ground acquisition never can.
		if (shieldPhase == 1) {
			if (player.age < shieldActionAge) return;
			if (!isHoldingShield(target) || !confirmedVanillaTarget(client, player, target)
					|| !isAxe(player.getMainHandStack().getItem())) {
				if (!restoreMace(client, player)) return;
				shieldPhase = 0;
				stunSmashFollowup = false;
				return;
			}
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
			attackShieldWithEquippedAxe(client, player, target);
			shieldPhase = 2;
			shieldActionAge = player.age + 1;
			// The tick gate below already carries a full 50 ms movement tick, so the
			// configured gap is the TOTAL wait, not an extra one stacked on top of it.
			// Counting it from zero made a 60 ms setting cost two ticks instead of the
			// one the gate intends — a whole tick out of a combo that has to finish
			// before the fall ends.
			shieldWaitUntilNanos = now
					+ Math.max(0, Math.max(50, tuning.stunGapMs()) - 50) * 1_000_000L;
			onTargetSinceNanos = 0L;
			return;
		}
		if (shieldPhase == 2) {
			if (player.age < shieldActionAge) return;
			if (now < shieldWaitUntilNanos) return; // let the axe hit + shield-disable register (~1 tick)
			if (!restoreMace(client, player)) return;
			shieldPhase = 0;
			shieldComboUntilNanos = now + 500_000_000L;
			maceReadyAge = player.age + 1;
			return;
		}

		// Mace mode used to look enabled while doing nothing unless the player had
		// already selected a mace. Make the handoff visible and ordinary: select a
		// real hotbar slot locally, let vanilla synchronize it, then wait a full
		// client tick before any attack can be emitted. It only ever happens on a
		// genuine descent, and never while Breach Swap is set up on the sword.
		if (!player.getMainHandStack().isOf(Items.MACE)) {
			if (!autoSwitchEnabled() || !diving
					|| CombatModeRuntime.breachSwapHoldsHotbar()) {
				onTargetSinceNanos = 0L;
				return;
			}
			int slot = findMace(player);
			if (slot < 0 || !CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
			int previous = player.getInventory().getSelectedSlot();
			selectSlot(client, player, slot);
			if (previous != slot) {
				autoSwitchSlot = slot;
				if (autoSwitchReturnSlot < 0) autoSwitchReturnSlot = previous;
			}
			maceSlot = slot;
			maceReadyAge = player.age + 1;
			autoEquippedThisEngagement = true;
			return;
		}
		if (player.age < maceReadyAge) return;

		// The attack-time vanilla ray is the authority. Requiring the cached render
		// ray as well sampled two different frames; during a close pass they often
		// disagreed forever even while the live ray was legally on the player.
		boolean confirmed = confirmedVanillaTarget(client, player, target);
		boolean smash = isSmashing(player);
		if (!confirmed) {
			return;
		}
		// The axe may connect a fraction before fallDistance reaches the vanilla
		// smash threshold. Preserve the armed follow-up through that part of the
		// descent instead of treating it as ground combat and spending the mace hit
		// too early. If the player lands first, it safely falls back to ground rules.
		if (MaceShieldComboPolicy.waitForSmash(
				stunSmashFollowup, isFalling(player), smash)) return;

		// Ground combat retains an acquisition dwell, including a shield-breaking
		// axe tap. It starts when the player is acquired so aiming, equipping and
		// reaction overlap instead of stacking three independent delays.
		if (!smash) {
			stunSmashFollowup = false;
			if (onTargetSinceNanos == 0L) {
				beginGroundSettle(now, tuning);
			}
			if (now - onTargetSinceNanos < settleNeededNanos) return;
		}

		float threshold = MathHelper.clamp(
				smash ? tuning.smashChargePct() : tuning.groundChargePct(), 0, 100) / 100.0F;
		boolean responsiveStunSmash = stunSmashFollowup && smash;
		float cooldown = player.getAttackCooldownProgress(0.0F);
		double remainingChargeMs = Math.max(0.0D, threshold - cooldown)
				* player.getAttackCooldownProgressPerTick() * 50.0D;
		double holdBudgetMs = chargeHoldBudgetNanos / 1_000_000.0D;
		if (autoEquippedThisEngagement) holdBudgetMs = Math.min(holdBudgetMs, 70.0D);
		boolean firstContact = attacksThisEngagement == 0
				&& now - lastAttackNanos >= MaceAttackTimingPolicy.MIN_INITIAL_REATTACK_NANOS;
		boolean attackReady = responsiveStunSmash || MaceAttackTimingPolicy.shouldAttack(
				cooldown, threshold, firstContact, smash, remainingChargeMs,
				holdBudgetMs);
		if (!attackReady) return;

		// ── Stun-slam initiation (axe tap on a shielder) ─────────────────────────
		// Only when you're genuinely aimed at a shielder with an axe in the hotbar (crosshair-
		// gated just above, so it's a legal hit). The axe tap disables their raised shield AND
		// lands a stun; the swap-back to the mace + the smash is handled at the TOP of the tick
		// (so a mid-dive drift can't strand us on the axe). Done on the way down, the axe lands as
		// a crit and the mace as a falling smash — the two connecting together in one fall. The
		// combo cooldown stops it re-firing before the mace has come out.
		//
		// Net damage is preserved even with no shield: the small axe hit sets the target's
		// last-damage, and the far bigger mace smash overrides it within the invuln window
		// (dealing smash − axe), so the total still equals the full smash — plus the stun.
		if (stunSlamEnabled()
				&& shieldPhase == 0 && now >= shieldComboUntilNanos) {
			int axe = findAxe(player);
			if (axe >= 0 && isHoldingShield(target)) {
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
				maceSlot = player.getInventory().getSelectedSlot(); // remember the mace slot
				// A legal, ordered handoff: select the axe now, let one movement
				// packet carry that state, and attack on the next input tick. The
				// previous same-tick slot+attack was fast but a BadPackets signature.
				stunSmashFollowup = isFalling(player);
				selectSlot(client, player, axe);
				shieldPhase = 1;
				shieldActionAge = player.age + 1;
				onTargetSinceNanos = 0L;
				return;
			}
		}

		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		// interactionManager sends the ordinary attack packet but, unlike
		// MinecraftClient#doAttack, does not reset the local cooldown clock.
		// Mirror vanilla here so the next cycle cannot spam against a stale 100% bar.
		player.resetTicksSinceLastAttack();
		attacksThisEngagement++;
		lastAttackNanos = now;
		stunSmashFollowup = false;
		onTargetSinceNanos = 0L;
		disengageUntilNanos = now + DISENGAGE_NANOS;
		CombatModeRuntime.consumeSpearMace(target.getUuid());
	}

	/**
	 * Whip the real view onto the target every frame: a large fraction of the remaining
	 * error each tick (near-instant) but capped per frame so it stays a smooth flick,
	 * with tremor + occasional overshoot + a slow wandering bias for a human feel.
	 */
	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F : (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0 * 20.0, 0.05, 4.0);
		lastFrameNanos = now;
		if (!ready(client) || !maceAimEnabled()
				|| targetUuid == null || now < disengageUntilNanos) return;

		ClientPlayerEntity player = client.player;
		CombatModeProfile.Mace tuning = CombatModePolicy.mace(config);
		PlayerEntity target = byUuid(client, targetUuid);
		if (target == null || !trackable(player, target, tuning)) {
			clearEngagement();
			return;
		}

		// Ask for silent aim for as long as this controller is actually turning
		// the body. It is held by continuous request, so simply not asking on a
		// frame where the mace is not aiming is what hands the body back.
		if (config.maceSilentAim) SilentAimController.instance().engage(player);

		updateBias(now, dt, tuning);
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

		// Fraction of the remaining error to close this tick. Kept WELL below the old
		// near-instant 0.58-0.72: a turn that closes ~95% of a big angle in one tick is a
		// rotation-speed/aim-snap fingerprint. This eases on over several ticks instead.
		// During a SMASH the whole approach is a fast dive — whip on quickly so the hit lands
		// while you're still falling (fallDistance > 0) and the smash bonus actually applies;
		// on the ground, ease on slowly so it isn't an aim-snap fingerprint.
		boolean smash = isSmashing(player);
		float speed = (smash ? MathHelper.clamp(tuning.smashSpeedPct(), 30, 95)
				: MathHelper.clamp(tuning.turnSpeedPct(), 20, 90)) / 100.0F;
		float k = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawStep = yawErr * k + (float) (rng.nextGaussian() * 0.30D);   // micro-tremor
		float pitchStep = pitchErr * k + (float) (rng.nextGaussian() * 0.22D);

		// Occasional small flick overshoot on big turns — a human snap blows slightly past.
		if (Math.abs(yawErr) > 28.0F && rng.nextFloat() < 0.16F * dt) {
			yawStep += Math.signum(yawErr) * (2.0F + rng.nextFloat() * 4.0F);
		}

		// Per-frame rotation cap: a fast flick during a smash (so the dive connects in its
		// short fall window), a calmer human-plausible sweep on the ground. Snapped to the
		// mouse grid below so the packet still looks like real mouse counts.
		float cap = (smash ? MathHelper.clamp(tuning.smashSpeedPct(), 30, 95) * 0.6F
				: MathHelper.clamp(tuning.turnSpeedPct(), 20, 90) * 0.5F) * dt;
		float yawApplied = mouse.yaw(MathHelper.clamp(yawStep, -cap, cap));
		float pitchApplied = mouse.pitch(MathHelper.clamp(pitchStep, -cap * 0.8F, cap * 0.8F));

		player.setYaw(player.getYaw() + yawApplied);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -90.0F, 90.0F));
	}

	/** Re-roll the slow aim bias every so often — a drifting "not perfectly on" wander. */
	private void updateBias(long now, float dt, CombatModeProfile.Mace tuning) {
		if (now >= nextBiasNanos) {
			biasYawTarget = rng.nextGaussian() * tuning.yawBiasStdDev();
			biasPitchTarget = rng.nextGaussian() * tuning.pitchBiasStdDev();
			nextBiasNanos = now + 180_000_000L + (long) (rng.nextDouble() * 320_000_000L);
		}
		double blend = 1.0D - Math.pow(0.86D, dt);
		biasYaw += (biasYawTarget - biasYaw) * blend;
		biasPitch += (biasPitchTarget - biasPitch) * blend;
	}

	private Vec3d aimPoint(PlayerEntity target) {
		Box box = target.getBoundingBox();
		Vec3d point = new Vec3d(
				box.minX + (box.maxX - box.minX) * fx,
				box.minY + (box.maxY - box.minY) * fy,
				box.minZ + (box.maxZ - box.minZ) * fz);
		// A fraction of one tick of visible lead prevents the camera spring from
		// forever following the target's previous position in a close strafe. The
		// final vanilla ray still has to intersect the current, real hitbox.
		Vec3d velocity = target.getVelocity();
		Vec3d lead = new Vec3d(velocity.x, velocity.y * 0.45D, velocity.z).multiply(0.55D);
		double horizontal = Math.sqrt(lead.x * lead.x + lead.z * lead.z);
		if (horizontal > 0.18D) {
			double scale = 0.18D / horizontal;
			lead = new Vec3d(lead.x * scale, MathHelper.clamp(lead.y, -0.12D, 0.12D), lead.z * scale);
		}
		return point.add(lead);
	}

	/**
	 * Falling onto the target with enough height behind it that this hit really is a smash.
	 * The old check was any downward motion at all, so an ordinary hop on flat ground counted
	 * as a smash: it skipped the ground settle, swung at smash charge, and pulled the mace out
	 * while you were just bouncing around. Vanilla pays the bonus past ~1.5 blocks of fall.
	 */
	private boolean isSmashing(ClientPlayerEntity player) {
		return isFalling(player) && player.fallDistance > SMASH_FALL_BLOCKS;
	}

	/**
	 * A descent already past a flat jump's ~1.25-block ceiling — a real drop or swoop, and the
	 * only state in which this controller may take the mace out. Slightly below the smash line
	 * so the one-tick handoff finishes before the fall is deep enough to pay the bonus.
	 */
	private boolean isDiving(ClientPlayerEntity player) {
		return isFalling(player) && player.fallDistance > DIVE_FALL_BLOCKS;
	}

	private boolean isFalling(ClientPlayerEntity player) {
		return !player.isOnGround() && player.getVelocity().y < 0.0D;
	}

	/**
	 * Give back whatever was in hand before the mace handoff, now that the dive is over. A manual
	 * scroll in the meantime wins outright — we just forget the loan. The slot is set locally and
	 * vanilla's own sync sends it, exactly like the handoff, so it stays one orderly slot change.
	 */
	private boolean releaseAutoSwitch(ClientPlayerEntity player) {
		if (autoSwitchReturnSlot < 0 || player == null) return false;
		if (player.getInventory().getSelectedSlot() != autoSwitchSlot) {
			autoSwitchSlot = -1;
			autoSwitchReturnSlot = -1;
			return false;
		}
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return false;
		player.getInventory().setSelectedSlot(autoSwitchReturnSlot);
		autoSwitchSlot = -1;
		autoSwitchReturnSlot = -1;
		autoEquippedThisEngagement = false;
		return true;
	}

	private boolean ready(MinecraftClient client) {
		if (!config.enabled) return false;
		if (!CombatModePolicy.enabled(config, CombatFeature.AUTO_MACE)
				&& !CombatModeRuntime.spearMaceActive()) return false;
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.interactionManager == null
				|| client.currentScreen != null || !player.isAlive() || player.isSpectator()) {
			return false;
		}
		return true;
	}

	/** Nearest visible player inside the configured range and FOV, with bounded stickiness. */
	private PlayerEntity acquireTarget(MinecraftClient client, ClientPlayerEntity player,
			CombatModeProfile.Mace tuning) {
		double range = MathHelper.clamp(tuning.trackingRange(), 3, 7);
		Vec3d eye = player.getEyePos();
		UUID spearTarget = CombatModeRuntime.spearMaceTarget();
		if (spearTarget != null) {
			PlayerEntity target = byUuid(client, spearTarget);
			if (target != null && target.isAlive() && !target.isSpectator()
					&& target.getBoundingBox().getCenter().distanceTo(eye) <= range
					&& player.canSee(target)) {
				return target;
			}
		}
		PlayerEntity best = null;
		double bestScore = -1.0D;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == player || !other.isAlive() || other.isSpectator()) continue;
			double dist = other.getBoundingBox().getCenter().distanceTo(eye);
			if (dist > range || !trackable(player, other, tuning)) continue;
			double score = (range - dist)
					+ (other.getUuid().equals(targetUuid) ? tuning.targetStickiness() : 0.0D);
			if (score > bestScore) {
				bestScore = score;
				best = other;
			}
		}
		return best;
	}

	/** Configured acquisition cone and real line of sight; neither is weakened by tier. */
	private boolean trackable(ClientPlayerEntity player, PlayerEntity target, CombatModeProfile.Mace tuning) {
		if (!target.isAlive() || target.isSpectator() || !player.canSee(target)) return false;
		Vec3d delta = target.getBoundingBox().getCenter().subtract(player.getEyePos());
		if (delta.lengthSquared() < 1.0E-6D) return true;
		double minDot = Math.cos(Math.toRadians(MathHelper.clamp(tuning.fovDeg(), 1, 179)));
		return delta.normalize().dotProduct(player.getRotationVec(1.0F)) >= minDot;
	}

	/**
	 * Exact tick-current vanilla entity target equality. This ray is produced in
	 * the same pre-movement phase as the attack and retains vanilla block occlusion
	 * and held-item range; a previous render-frame ray is not an additional gate.
	 */
	private boolean confirmedVanillaTarget(MinecraftClient client, ClientPlayerEntity self,
			PlayerEntity expected) {
		Entity camera = client.getCameraEntity();
		HitResult freshHit = self.getCrosshairTarget(1.0F, camera == null ? self : camera);
		PlayerEntity fresh = vanillaPlayer(freshHit, self);
		return fresh == expected;
	}

	/** Reaction starts when the player enters the acquisition cone, not after aiming finishes. */
	private void beginGroundSettle(long now, CombatModeProfile.Mace tuning) {
		onTargetSinceNanos = now;
		settleNeededNanos = Math.max(0, tuning.groundSettleMs()) * 1_000_000L
				+ (long) (rng.nextDouble() * 14_000_000L);
		chargeHoldBudgetNanos = 65_000_000L + (long) (rng.nextDouble() * 60_000_000L);
	}

	private void clearEngagement() {
		targetUuid = null;
		onTargetSinceNanos = 0L;
		settleNeededNanos = 0L;
		chargeHoldBudgetNanos = 0L;
		attacksThisEngagement = 0;
		autoEquippedThisEngagement = false;
	}

	private PlayerEntity vanillaPlayer(HitResult hit, ClientPlayerEntity self) {
		if (!(hit instanceof EntityHitResult entityHit) || entityHit.getType() != HitResult.Type.ENTITY) return null;
		if (!(entityHit.getEntity() instanceof PlayerEntity target)) return null;
		return target != self && target.isAlive() && target.getHealth() > 0.0F && !target.isSpectator()
				? target : null;
	}

	private PlayerEntity byUuid(MinecraftClient client, UUID uuid) {
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player.getUuid().equals(uuid)) return player;
		}
		return null;
	}

	/**
	 * True when the target is raising a shield (so a mace hit would be blocked). In 1.21.11
	 * blocking is driven by the {@code BLOCKS_ATTACKS} data component, NOT the SHIELD item id,
	 * and {@code isBlocking()} only flips true AFTER the item's block-delay warmup (~5 ticks).
	 * We also catch that warmup window — any actively-used item that can block attacks — so the
	 * axe-stun fires the instant they start raising, not a quarter-second late.
	 */
	private boolean isHoldingShield(PlayerEntity target) {
		if (target.isBlocking()) return true;
		return target.isUsingItem()
				&& target.getActiveItem().contains(net.minecraft.component.DataComponentTypes.BLOCKS_ATTACKS);
	}

	/** First hotbar slot (0..8) holding any axe, or -1. */
	private int findAxe(ClientPlayerEntity player) {
		for (int s = 0; s < 9; s++) {
			if (isAxe(player.getInventory().getStack(s).getItem())) return s;
		}
		return -1;
	}

	/**
	 * Prefer Wind Burst for AutoMace, then an unenchanted/non-Breach mace, and
	 * use a Breach mace only as a last resort. This stops AutoMace and the
	 * dedicated Breach Swap controller from silently choosing each other's mace.
	 */
	private int findMace(ClientPlayerEntity player) {
		int nonBreach = -1;
		int fallback = -1;
		for (int s = 0; s < 9; s++) {
			ItemStack stack = player.getInventory().getStack(s);
			if (!stack.isOf(Items.MACE)) continue;
			if (fallback < 0) fallback = s;
			if (hasEnchantment(stack, Enchantments.WIND_BURST)) return s;
			if (nonBreach < 0 && !hasEnchantment(stack, Enchantments.BREACH)) nonBreach = s;
		}
		return nonBreach >= 0 ? nonBreach : fallback;
	}

	private boolean hasEnchantment(ItemStack stack,
			net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key) {
		for (var enchantment : EnchantmentHelper.getEnchantments(stack).getEnchantments()) {
			if (enchantment.matchesKey(key)) return true;
		}
		return false;
	}

	private boolean isAxe(Item item) {
		return item == Items.WOODEN_AXE || item == Items.STONE_AXE || item == Items.COPPER_AXE
				|| item == Items.IRON_AXE || item == Items.GOLDEN_AXE || item == Items.DIAMOND_AXE
				|| item == Items.NETHERITE_AXE;
	}

	/**
	 * Select {@code slot} and attack {@code target} in one shot, sending the slot-change packet
	 * BEFORE the attack packet so the server sees the new held item when the hit resolves. The
	 * local selected slot is kept in sync (so vanilla's own slot sync sends no conflicting packet).
	 */
	private void attackShieldWithEquippedAxe(MinecraftClient client, ClientPlayerEntity player,
			PlayerEntity target) {
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		player.resetTicksSinceLastAttack();
	}

	private boolean autoSwitchEnabled() {
		return CombatModePolicy.autoMaceAutoSwitch(config) || CombatModeRuntime.spearMaceActive();
	}

	private boolean maceAimEnabled() {
		return CombatModePolicy.enabled(config, CombatFeature.MACE_AIM)
				|| CombatModeRuntime.spearMaceActive();
	}

	private boolean stunSlamEnabled() {
		return CombatModePolicy.enabled(config, CombatFeature.MACE_STUN_SLAM)
				|| (CombatModeRuntime.spearMaceActive() && config.lungeShieldBreak);
	}

	/**
	 * Set the held hotbar slot locally and tell the server immediately (so it's ordered before any
	 * attack this same tick). Skips a no-op change and keeps vanilla's slot-sync in step so it never
	 * fires a duplicate packet — both of which would otherwise flag as BadPacketsA.
	 */
	private void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

	/** Restore the combo mace without fighting another controller's action this tick. */
	private boolean restoreMace(MinecraftClient client, ClientPlayerEntity player) {
		if (maceSlot < 0 || maceSlot > 8) return true;
		if (player.getInventory().getSelectedSlot() == maceSlot) return true;
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return false;
		selectSlot(client, player, maceSlot);
		return true;
	}

	/** Read-only coordination state for diagnostics and future controller ordering. */
	public boolean isBusy() {
		return shieldPhase != 0;
	}

	public boolean ownsActionThisTick() {
		return CombatModeRuntime.claimedBy() == CombatModeRuntime.ActionOwner.AUTO_MACE;
	}

	/** Upper-chest, off-centre hitbox fractions — never dead-centre. */
	private void pickPoint() {
		fx = 0.30 + rng.nextDouble() * 0.40; // 0.30..0.70
		fy = 0.45 + rng.nextDouble() * 0.30; // upper chest
		fz = 0.30 + rng.nextDouble() * 0.40;
	}
}
