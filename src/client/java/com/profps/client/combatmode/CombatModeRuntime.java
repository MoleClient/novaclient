package com.profps.client.combatmode;

import com.profps.client.config.ProFPSConfig;

import java.util.UUID;

/**
 * Session-only coordination state shared by combat controllers.
 *
 * <p>{@link #beginPreMovementTick(ProFPSConfig)} must be called once at the start of the
 * pre-movement dispatch. Controllers call {@link #tryClaim(ActionOwner)} before emitting an
 * attack, use, or swap sequence; the first claimant wins, so dispatch order sets priority.</p>
 */
public final class CombatModeRuntime {
	public enum ActionOwner {
		NONE,
		EXPANDED_HITBOX,
		AUTO_CREEPER,
		TRIGGER,
		AXE_STUN,
		AXE_CRIT,
		AUTO_MACE,
		BREACH_SWAP,
		PEARL_CATCH,
		AUTO_CLICKER,
		AUTO_CRYSTAL,
		AUTO_ANCHOR,
		AUTO_LUNGE,
		AUTO_SPEAR,
		SCHEMATIC_BUILD,
		AUTO_MOVE,
		ANTI_FIREBALL,
		KB_DISPLACE,
		AUTO_TOTEM
	}

	/** How long a published Breach Swap setup keeps the hotbar. */
	private static final long BREACH_HOLD_NANOS = 300_000_000L;

	private static ActionOwner claimedBy = ActionOwner.NONE;
	private static long dispatchSequence;
	private static CombatMode observedMode = CombatMode.OFF;

	private static UUID axeFollowupTarget;
	private static long axeFollowupUntilNanos;

	private static long breachSwapHoldUntilNanos;
	private static UUID spearMaceTarget;
	private static long spearMaceUntilNanos;

	private CombatModeRuntime() {}

	/** Reset the single-action claim for a new pre-movement dispatch. */
	public static void beginPreMovementTick(ProFPSConfig config) {
		claimedBy = ActionOwner.NONE;
		dispatchSequence++;
		syncMode(config, System.nanoTime());
	}

	/**
	 * Claim this pre-movement tick. Repeating the same owner's claim is idempotent;
	 * a different owner is rejected once a claim exists.
	 */
	public static boolean tryClaim(ActionOwner owner) {
		if (owner == null || owner == ActionOwner.NONE) return false;
		if (claimedBy == ActionOwner.NONE) {
			claimedBy = owner;
			return true;
		}
		return claimedBy == owner;
	}

	public static boolean actionClaimed() {
		return claimedBy != ActionOwner.NONE;
	}

	public static ActionOwner claimedBy() {
		return claimedBy;
	}

	/** Increments once per {@link #beginPreMovementTick}. */
	public static long dispatchSequence() {
		return dispatchSequence;
	}

	/**
	 * Publishes the Breach Swap hotbar hold. The swap resolves with the sword's attributes,
	 * so no other controller may change slots while the hold is active. It expires on its own.
	 */
	public static void markBreachSwapHold(boolean holding) {
		breachSwapHoldUntilNanos = holding ? System.nanoTime() + BREACH_HOLD_NANOS : 0L;
	}

	/** True while an armed Breach Swap owns the hotbar. */
	public static boolean breachSwapHoldsHotbar() {
		return System.nanoTime() < breachSwapHoldUntilNanos;
	}

	/** Hands a Lunge jab to AutoMace for one target, bounded to 250..3000 ms. */
	public static void armSpearMace(UUID targetUuid, long durationMillis) {
		if (targetUuid == null) {
			clearSpearMace();
			return;
		}
		spearMaceTarget = targetUuid;
		spearMaceUntilNanos = System.nanoTime()
				+ Math.max(250L, Math.min(3_000L, durationMillis)) * 1_000_000L;
	}

	public static boolean spearMaceActive() {
		if (spearMaceTarget == null || System.nanoTime() >= spearMaceUntilNanos) {
			clearSpearMace();
			return false;
		}
		return true;
	}

	public static UUID spearMaceTarget() {
		return spearMaceActive() ? spearMaceTarget : null;
	}

	public static void consumeSpearMace(UUID targetUuid) {
		if (targetUuid != null && targetUuid.equals(spearMaceTarget)) clearSpearMace();
	}

	public static void clearSpearMace() {
		spearMaceTarget = null;
		spearMaceUntilNanos = 0L;
	}

	/**
	 * Opens the Axe mode sword-trigger continuation for one target.
	 *
	 * @return false when the current mode or switches do not permit it
	 */
	public static boolean armAxeFollowup(ProFPSConfig config, UUID targetUuid) {
		long now = System.nanoTime();
		syncMode(config, now);
		if (targetUuid == null
				|| CombatModePolicy.mode(config) != CombatMode.AXE
				|| !CombatModePolicy.enabled(config, CombatFeature.AXE_TRIGGER_FOLLOWUP)) {
			return false;
		}
		axeFollowupTarget = targetUuid;
		axeFollowupUntilNanos = now + CombatModePolicy.axe(config).followupWindowMs() * 1_000_000L;
		return true;
	}

	/** True only for the stunned target and only during its bounded follow-up window. */
	public static boolean axeFollowupMatches(ProFPSConfig config, UUID targetUuid) {
		long now = System.nanoTime();
		syncMode(config, now);
		return targetUuid != null
				&& axeFollowupTarget != null
				&& targetUuid.equals(axeFollowupTarget)
				&& now < axeFollowupUntilNanos
				&& CombatModePolicy.enabled(config, CombatFeature.AXE_TRIGGER_FOLLOWUP);
	}

	/** Effective Triggerbot gate for a concrete target. */
	public static boolean triggerEnabledFor(ProFPSConfig config, UUID targetUuid) {
		return switch (CombatModePolicy.mode(config)) {
			case OFF, SWORD, MACE -> CombatModePolicy.enabled(config, CombatFeature.TRIGGER);
			// The post-stun continuation is an extra window on top of the mode switch, not the only way in.
			case AXE -> CombatModePolicy.enabled(config, CombatFeature.TRIGGER)
					|| axeFollowupMatches(config, targetUuid);
		};
	}

	/** Consumes a successful follow-up so it cannot spill into another cooldown cycle. */
	public static void consumeAxeFollowup(UUID targetUuid) {
		if (targetUuid != null && targetUuid.equals(axeFollowupTarget)) clearAxeFollowup();
	}

	public static UUID axeFollowupTarget() {
		if (System.nanoTime() >= axeFollowupUntilNanos) clearAxeFollowup();
		return axeFollowupTarget;
	}

	public static long axeFollowupRemainingMs() {
		long remaining = axeFollowupUntilNanos - System.nanoTime();
		if (remaining <= 0L) {
			clearAxeFollowup();
			return 0L;
		}
		return remaining / 1_000_000L;
	}

	public static void clearAxeFollowup() {
		axeFollowupTarget = null;
		axeFollowupUntilNanos = 0L;
	}

	/** Clears all transient combat-mode state. */
	public static void reset() {
		claimedBy = ActionOwner.NONE;
		dispatchSequence = 0L;
		observedMode = CombatMode.OFF;
		breachSwapHoldUntilNanos = 0L;
		clearAxeFollowup();
		clearSpearMace();
	}

	private static void syncMode(ProFPSConfig config, long now) {
		CombatMode current = CombatModePolicy.mode(config);
		if (current != observedMode) {
			observedMode = current;
			clearAxeFollowup();
		}
		if (now >= axeFollowupUntilNanos
				|| current != CombatMode.AXE
				|| !CombatModePolicy.enabled(config, CombatFeature.AXE_TRIGGER_FOLLOWUP)) {
			clearAxeFollowup();
		}
	}
}
