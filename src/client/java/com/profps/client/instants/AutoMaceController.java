package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.aim.SilentAimController;
import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Mace-only auto-attack. Locks the nearest player in range, turns the real view onto a
 * randomized point in their hitbox frame by frame, and attacks once the attack-time vanilla ray
 * names the target.
 *
 * <p>With an axe in the hotbar it can run a stun-slam combo: an axe tap, then a swap back to the
 * mace for the smash. Inside the invulnerability window vanilla discards a follow-up unless it
 * strictly exceeds the previous hit, so the axe tap's damage is the bar the slam must clear.
 * The tap is therefore kept weak and the combo only starts from a real descent, where
 * {@code MaceItem.getBonusAttackDamage} can carry a low-charge mace past it. Sprint is kept
 * below 0.9 charge, since that is where vanilla pays the knockback bonus. See
 * {@link StunSlamPolicy}. Flat-ground shielders belong to Axe Stun.
 */
public final class AutoMaceController {
	private static final long DISENGAGE_NANOS = 120_000_000L; // brief post-hit gap
	/** Vanilla pays the smash bonus only past 1.5 blocks of fall. */
	private static final float SMASH_FALL_BLOCKS = 1.5F;
	/** Handoff threshold, set above a flat jump's 1.25 so the mace is in hand in time. */
	private static final float DIVE_FALL_BLOCKS = 1.3F;
	/** How long the mace is kept after the dive ends. */
	private static final long DIVE_HOLD_NANOS = 250_000_000L;
	/** Extra hold while still airborne. */
	private static final long AIRBORNE_HOLD_NANOS = 1_250_000_000L;
	/** How long an armed stun-slam follow-up stays valid. */
	private static final long STUN_SMASH_WINDOW_NANOS = 1_200_000_000L;
	/** Ticks a committed combo tolerates the ray not naming the target. */
	private static final int STUN_RAY_GRACE_TICKS = 3;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private UUID targetUuid;
	private double fx, fy, fz; // relative hitbox offsets, 0 to 1
	private long disengageUntilNanos;
	private long nextRetargetNanos;
	private long lastFrameNanos;
	private long onTargetSinceNanos; // when the crosshair first settled on the target
	private long settleNeededNanos;  // dwell required before the swing
	private long chargeHoldBudgetNanos; // maximum first-contact wait for a stronger cooldown
	private long lastAttackNanos;
	private int attacksThisEngagement;
	private boolean autoEquippedThisEngagement;

	// Slow wandering aim bias and its reroll timer.
	private double biasYaw, biasPitch, biasYawTarget, biasPitchTarget;
	private long nextBiasNanos;

	// Stun-slam combo state: axe tap, then a swap back to the mace before the hurt-invulnerability
	// window resets.
	private int shieldPhase;          // 0 idle, 1 axe selected, 2 waiting after the axe hit
	private int shieldActionAge;
	private long shieldWaitUntilNanos;
	private long shieldComboUntilNanos; // rest after a slam, preventing a re-axe loop
	private int maceSlot = -1;        // hotbar slot to swap back to for the mace hit
	private int maceReadyAge;         // a mace select and attack must not share a client tick
	private boolean stunSmashFollowup; // bypass permitted only inside the same falling smash
	private long stunSmashUntilNanos;
	private volatile boolean sprintDropRequest; // published W-tap, only above 0.9 charge

	// The auto handoff is a loan: the pre-handoff item is restored once the fall is over.
	private int autoSwitchSlot = -1;       // the mace slot this controller selected, or -1
	private int autoSwitchReturnSlot = -1; // what was held before that handoff, or -1
	private long diveHoldUntilNanos;
	private int lastSlamRefusalAge;        // so a declined slam logs its reason once

	private static AutoMaceController instance;

	public AutoMaceController(ProFPSConfig config) {
		this.config = config;
		instance = this;
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
			// Restore the mace if interrupted while holding the axe.
			if (shieldPhase != 0 && client.player != null) {
				if (!restoreMace(client, client.player)) return;
				endCombo();
			}
			releaseAutoSwitch(client.player);
			clearEngagement();
			return;
		}
		ClientPlayerEntity player = client.player;
		CombatModeProfile.Mace tuning = CombatModePolicy.mace(config);
		long now = System.nanoTime();

		// Refresh the mace loan while the dive lasts, then return the previous weapon.
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
			// Do not rotate on a tick that could not legally end in a mace hit.
			clearEngagement();
			return;
		}
		// A committed combo follows the same opponent by identity: the axe tap knocks them back,
		// which can push them outside the acquisition scan. Reach is still enforced by the
		// vanilla ray at the moment of the hit.
		PlayerEntity target = shieldPhase != 0 && targetUuid != null
				? byUuid(client, targetUuid)
				: acquireTarget(client, player, tuning);
		if (target != null && (!target.isAlive() || target.isSpectator())) target = null;
		if (target == null) {
			// Go back to the mace if the target vanishes mid shield-break.
			if (shieldPhase != 0) {
				if (!restoreMace(client, player)) return;
				endCombo();
			}
			clearEngagement();
			return;
		}

		if (!target.getUuid().equals(targetUuid)) {
			if (shieldPhase != 0) {
				if (!restoreMace(client, player)) return;
				endCombo();
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

		// Spear-to-mace shield route: pre-arm the axe directly rather than selecting the mace
		// first and swapping again a tick later.
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
			armStunSmash(player, now);
			selectSlot(client, player, axe);
			shieldPhase = 1;
			shieldActionAge = player.age + 1;
			onTargetSinceNanos = 0L;
			return;
		}

		// ── Stun-slam state machine ──
		// Runs before the ordinary range and cooldown gates, so a tick of aim drift cannot strand
		// the sequence holding the axe.
		if (shieldPhase == 1) {
			if (player.age < shieldActionAge) return;
			if (!isAxe(player.getMainHandStack().getItem()) || !isHoldingShield(target)) {
				if (!restoreMace(client, player)) return;
				endCombo();
				return;
			}
			// Tolerate a bounded number of ticks where the ray does not name the target; on a
			// fast dive the crosshair crosses the hitbox edge constantly.
			if (!confirmedVanillaTarget(client, player, target)) {
				if (player.age < shieldActionAge + STUN_RAY_GRACE_TICKS) return;
				if (!restoreMace(client, player)) return;
				endCombo();
				return;
			}
			// Sprint only matters above 0.9 charge, where vanilla pays the knockback bonus that
			// would push the target out of the slam's reach. Below it, staying sprinted denies
			// the 1.5x crit that would raise the bar the slam has to beat.
			boolean wouldLaunch = player.getAttackCooldownProgress(0.0F) > 0.9F;
			sprintDropRequest = wouldLaunch;
			if (wouldLaunch && player.isSprinting() && player.age < shieldActionAge + 2) return;

			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
			attackShieldWithEquippedAxe(client, player, target);
			// Swap back on the same tick. The attack packet is already sent, so the slot change
			// is ordered after it and the hit still resolves as an axe hit.
			restoreMace(client, player);
			sprintDropRequest = false;
			shieldPhase = 0;
			shieldComboUntilNanos = now + 500_000_000L;
			maceReadyAge = player.age + 1;
			// Any configured gap beyond the movement tick already carried above.
			shieldWaitUntilNanos = now
					+ Math.max(0, Math.max(50, tuning.stunGapMs()) - 50) * 1_000_000L;
			onTargetSinceNanos = 0L;
			return;
		}

		// Mace handoff: select the slot locally, let vanilla sync it, then wait a full client
		// tick before any attack. Only on a descent, and never while Breach Swap holds the hotbar.
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
		if (player.age < maceReadyAge || now < shieldWaitUntilNanos) return;

		// The attack-time vanilla ray is the authority; the cached render ray is a different frame.
		boolean confirmed = confirmedVanillaTarget(client, player, target);
		boolean smash = isSmashing(player);
		if (!confirmed) {
			// Drop a stale smash bypass so the next hit resolves under ordinary ground rules.
			if (stunSmashFollowup && player.age >= maceReadyAge + STUN_RAY_GRACE_TICKS) {
				stunSmashFollowup = false;
			}
			return;
		}
		// ── Stun-slam initiation (axe tap on a shielder) ─────────────────────────
		// Requires a real descent: the axe tap zeroes the shared attack clock, so without a fall
		// bonus the mace follow-up cannot beat the tap inside the invulnerability window. Ground
		// shielders belong to Axe Stun. The swap back to the mace runs at the top of the tick.
		if (stunSlamEnabled()
				&& shieldPhase == 0 && now >= shieldComboUntilNanos
				&& isDiving(player)) {
			int axe = findAxe(player);
			// Commit only when the slam will still beat the axe tap at the tick it swings.
			if (axe >= 0 && isHoldingShield(target) && slamWillLand(player, axe)) {
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
				maceSlot = player.getInventory().getSelectedSlot();
				// Select the axe now, let one movement packet carry that state, and attack on
				// the next input tick. A same-tick slot change plus attack is a BadPackets flag.
				armStunSmash(player, now);
				selectSlot(client, player, axe);
				shieldPhase = 1;
				shieldActionAge = player.age + 1;
				onTargetSinceNanos = 0L;
				return;
			}
		}


		// The axe may connect just before fallDistance reaches the smash threshold, so the armed
		// follow-up is preserved through that part of the descent.
		boolean followupLive = stunSmashFollowup && now < stunSmashUntilNanos;
		if (MaceShieldComboPolicy.waitForSmash(followupLive, !player.isOnGround(), smash)) return;

		// Ground combat keeps an acquisition dwell, started at acquisition so aiming, equipping
		// and reaction overlap.
		if (!smash) {
			stunSmashFollowup = false;
			if (onTargetSinceNanos == 0L) {
				beginGroundSettle(now, tuning);
			}
			if (now - onTargetSinceNanos < settleNeededNanos) return;
		}

		float threshold = MathHelper.clamp(
				smash ? tuning.smashChargePct() : tuning.groundChargePct(), 0, 100) / 100.0F;
		boolean responsiveStunSmash = followupLive && smash;
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

		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return;
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		// attackEntity does not reset the local cooldown clock the way doAttack does.
		player.resetTicksSinceLastAttack();
		attacksThisEngagement++;
		lastAttackNanos = now;
		stunSmashFollowup = false;
		onTargetSinceNanos = 0L;
		disengageUntilNanos = now + DISENGAGE_NANOS;
		CombatModeRuntime.consumeSpearMace(target.getUuid());
	}

	/** Steps the view toward the target by a capped fraction of the remaining error each frame. */
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

		// Silent aim is held by continuous request; skipping a frame releases the body.
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

		// Fraction of the remaining error closed this tick. A smash uses the faster rate so the
		// hit still lands inside the fall window.
		boolean smash = isSmashing(player);
		float speed = (smash ? MathHelper.clamp(tuning.smashSpeedPct(), 30, 95)
				: MathHelper.clamp(tuning.turnSpeedPct(), 20, 90)) / 100.0F;
		float k = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawStep = yawErr * k + (float) (rng.nextGaussian() * 0.30D);
		float pitchStep = pitchErr * k + (float) (rng.nextGaussian() * 0.22D);

		// Occasional overshoot on a large turn.
		if (Math.abs(yawErr) > 28.0F && rng.nextFloat() < 0.16F * dt) {
			yawStep += Math.signum(yawErr) * (2.0F + rng.nextFloat() * 4.0F);
		}

		// Per-frame rotation cap, then snapped to the mouse GCD grid.
		float cap = (smash ? MathHelper.clamp(tuning.smashSpeedPct(), 30, 95) * 0.6F
				: MathHelper.clamp(tuning.turnSpeedPct(), 20, 90) * 0.5F) * dt;
		float yawApplied = mouse.yaw(MathHelper.clamp(yawStep, -cap, cap));
		float pitchApplied = mouse.pitch(MathHelper.clamp(pitchStep, -cap * 0.8F, cap * 0.8F));

		player.setYaw(player.getYaw() + yawApplied);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchApplied, -90.0F, 90.0F));
	}

	/** Re-rolls and blends the slow aim bias. */
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
		// A fraction of a tick of lead, so the camera does not trail a strafing target. The
		// vanilla ray still has to intersect the real hitbox.
		Vec3d velocity = target.getVelocity();
		Vec3d lead = new Vec3d(velocity.x, velocity.y * 0.45D, velocity.z).multiply(0.55D);
		double horizontal = Math.sqrt(lead.x * lead.x + lead.z * lead.z);
		if (horizontal > 0.18D) {
			double scale = 0.18D / horizontal;
			lead = new Vec3d(lead.x * scale, MathHelper.clamp(lead.y, -0.12D, 0.12D), lead.z * scale);
		}
		return point.add(lead);
	}

	/** Falling with more than {@link #SMASH_FALL_BLOCKS} behind it, which is where vanilla pays the bonus. */
	private boolean isSmashing(ClientPlayerEntity player) {
		return isFalling(player) && player.fallDistance > SMASH_FALL_BLOCKS;
	}

	/**
	 * A descent past a flat jump's 1.25-block ceiling, and the only state in which the mace may be
	 * taken out. Set below the smash line so the one-tick handoff finishes before the bonus applies.
	 */
	private boolean isDiving(ClientPlayerEntity player) {
		return isFalling(player) && player.fallDistance > DIVE_FALL_BLOCKS;
	}

	private boolean isFalling(ClientPlayerEntity player) {
		return !player.isOnGround() && player.getVelocity().y < 0.0D;
	}

	/**
	 * How far the player can still fall before landing, up to {@code max}, stepped in half blocks
	 * from the feet. Landing early drops the combo off its smash bypass onto the ground rule.
	 */
	private static double groundClearance(ClientPlayerEntity player, double max) {
		net.minecraft.world.World world = player.getEntityWorld();
		if (world == null) return max;
		Vec3d pos = player.getEntityPos();
		for (double drop = 0.5D; drop <= max; drop += 0.5D) {
			BlockPos probe = BlockPos.ofFloored(pos.x, pos.y - drop, pos.z);
			if (!world.getBlockState(probe).getCollisionShape(world, probe).isEmpty()) return drop;
		}
		return max;
	}

	/** Base attack damage a weapon grants in the main hand, read off its attribute modifiers. */
	private static double attackDamageOf(ItemStack stack) {
		AttributeModifiersComponent modifiers = stack.getOrDefault(
				DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
		// The player's own 1.0 base is included, so this is total damage rather than the bonus.
		return modifiers.applyOperations(EntityAttributes.ATTACK_DAMAGE, 1.0D, EquipmentSlot.MAINHAND);
	}

	/**
	 * Whether committing now ends in a slam that deals net damage. Both weapons are measured from
	 * their own attribute modifiers rather than assumed.
	 */
	private boolean slamWillLand(ClientPlayerEntity player, int axeSlot) {
		if (!isFalling(player)) return false;
		ItemStack axe = player.getInventory().getStack(axeSlot);
		ItemStack mace = player.getMainHandStack();
		if (axe.isEmpty() || mace.isEmpty()) return false;
		// The tap only crits when sprint has to be dropped to avoid the knockback launch, which
		// is above 0.9 charge. Predict the same rule the tap itself will use.
		float charge = player.getAttackCooldownProgress(0.0F);
		boolean willCrit = charge > 0.9F;
		double axeDamage = attackDamageOf(axe);
		double maceDamage = attackDamageOf(mace);
		double maceCharge = player.getAttackCooldownProgressPerTick();

		// There has to be air left to fall through, or the slam tick arrives on the ground.
		double needed = StunSlamPolicy.dropBeforeSlam(player.getVelocity().y);
		double clearance = groundClearance(player, needed + 1.0D);
		boolean roomToFall = clearance > needed;

		boolean worth = StunSlamPolicy.worthCommitting(
				player.fallDistance, player.getVelocity().y,
				axeDamage, charge, willCrit, maceDamage, maceCharge);
		if (!worth || !roomToFall) {
			if (player.age != lastSlamRefusalAge) {
				lastSlamRefusalAge = player.age;
				ProFPS.LOGGER.info(
						"Stun slam declined: fall={} v={} clearance={} needs={} axe={} (charge {}, crit {}) -> {}",
						String.format("%.2f", player.fallDistance),
						String.format("%.2f", player.getVelocity().y),
						String.format("%.2f", clearance), String.format("%.2f", needed),
						String.format("%.1f", StunSlamPolicy.axeTapDamage(axeDamage, charge, willCrit)),
						String.format("%.2f", charge), willCrit,
						!roomToFall ? "would land before the slam" : "slam would be absorbed");
			}
			return false;
		}
		ProFPS.LOGGER.info("Stun slam committed: fall={} clearance={} projected={} net={}",
				String.format("%.2f", player.fallDistance), String.format("%.2f", clearance),
				String.format("%.2f", StunSlamPolicy.projectFall(player.fallDistance,
						player.getVelocity().y, StunSlamPolicy.SLAM_DELAY_TICKS)),
				String.format("%.1f", StunSlamPolicy.netSlamDamage(
						StunSlamPolicy.slamDamage(maceDamage, maceCharge,
								StunSlamPolicy.projectFall(player.fallDistance,
										player.getVelocity().y, StunSlamPolicy.SLAM_DELAY_TICKS)),
						StunSlamPolicy.axeTapDamage(axeDamage, charge, willCrit))));
		return true;
	}

	/**
	 * Restores the slot held before the mace handoff. A manual scroll in the meantime cancels the
	 * loan instead. Set locally so vanilla's own sync sends the one slot change.
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
	 * Whether the tick-current vanilla ray names {@code expected}. The ray is produced in the same
	 * pre-movement phase as the attack, so it carries vanilla occlusion and held-item range.
	 */
	private boolean confirmedVanillaTarget(MinecraftClient client, ClientPlayerEntity self,
			PlayerEntity expected) {
		Entity camera = client.getCameraEntity();
		HitResult freshHit = self.getCrosshairTarget(1.0F, camera == null ? self : camera);
		PlayerEntity fresh = vanillaPlayer(freshHit, self);
		return fresh == expected;
	}

	/**
	 * Arms the stun-slam follow-up for this airtime. Gated on airborne rather than falling, since
	 * vertical velocity passes through zero at the apex. The mace tick still requires a real smash.
	 */
	private void armStunSmash(ClientPlayerEntity player, long now) {
		stunSmashFollowup = !player.isOnGround();
		stunSmashUntilNanos = now + STUN_SMASH_WINDOW_NANOS;
		// Published a tick early: this runs at the tail of handleInputEvents and
		// KeyboardInput#tick is later in the same tick, so the sprint is gone before the tap.
		sprintDropRequest = true;
	}

	/** Clears all combo state. */
	private void endCombo() {
		shieldPhase = 0;
		stunSmashFollowup = false;
		stunSmashUntilNanos = 0L;
		sprintDropRequest = false;
	}

	/**
	 * Releases forward input to drop the sprint before the axe tap, since a sprinting hit above
	 * 0.9 charge pays a knockback bonus that would push the target out of the slam's reach.
	 */
	public static net.minecraft.util.PlayerInput stunSprintOverride(net.minecraft.util.PlayerInput current) {
		AutoMaceController controller = instance;
		// The shieldPhase test makes the request self-limiting, so no stale flag can tap W.
		if (controller == null || current == null
				|| !controller.sprintDropRequest || controller.shieldPhase == 0) {
			return null;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isSprinting()) return null;
		if (current.backward() || current.sneak() || !current.forward()) return null;
		return new net.minecraft.util.PlayerInput(false, false, current.left(), current.right(),
				current.jump(), false, false);
	}

	/** Starts the ground dwell at acquisition, so reaction overlaps aiming rather than following it. */
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
	 * True when the target is raising a shield. Blocking is driven by the {@code BLOCKS_ATTACKS}
	 * component, and {@code isBlocking()} only flips after the roughly 5-tick warmup, so an
	 * actively-used blocking item counts too.
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
	 * Prefers Wind Burst, then a non-Breach mace, then any mace, so this and Breach Swap do not
	 * pick each other's weapon.
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
	 * Sets the held slot locally and sends the packet immediately, before any attack this tick.
	 * No-op changes are skipped and vanilla's slot-sync is updated to avoid a duplicate packet.
	 */
	private void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

	/** Restores the combo mace, or returns false if another controller owns the action this tick. */
	private boolean restoreMace(MinecraftClient client, ClientPlayerEntity player) {
		if (maceSlot < 0 || maceSlot > 8) return true;
		if (player.getInventory().getSelectedSlot() == maceSlot) return true;
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_MACE)) return false;
		selectSlot(client, player, maceSlot);
		return true;
	}

	public boolean isBusy() {
		return shieldPhase != 0;
	}

	public boolean ownsActionThisTick() {
		return CombatModeRuntime.claimedBy() == CombatModeRuntime.ActionOwner.AUTO_MACE;
	}

	/** Samples off-centre hitbox fractions, biased high. */
	private void pickPoint() {
		fx = 0.30 + rng.nextDouble() * 0.40;
		fy = 0.45 + rng.nextDouble() * 0.30; // upper chest
		fz = 0.30 + rng.nextDouble() * 0.40;
	}
}
