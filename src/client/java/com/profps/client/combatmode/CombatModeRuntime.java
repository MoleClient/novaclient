package com.profps.client.combatmode;

import com.profps.client.config.ProFPSConfig;

import java.util.UUID;

/**
 * Small, session-only coordination surface for combat controllers.
 *
 * <p>Call {@link #beginPreMovementTick(ProFPSConfig)} exactly once at the start of
 * the pre-movement dispatch, then let controllers call {@link #tryClaim(ActionOwner)}
 * immediately before they emit an attack/use/swap sequence. The first claimant wins;
 * callers must therefore be dispatched in the desired priority order. This prevents
 * two independent controllers from attacking or fighting over the hotbar in one tick.</p>
 *
 * <p>The Axe follow-up is target-scoped and expires automatically. It deliberately
 * does not toggle the persisted standalone Triggerbot field.</p>
 */
public final class CombatModeRuntime {
	public enum ActionOwner {
		NONE,
		EXPANDED_HITBOX,
		AUTO_CREEPER,
		TRIGGER,
		AXE_STUN,
		AUTO_MACE,
		BREACH_SWAP,
		PEARL_CATCH,
		AUTO_CLICKER,
		AUTO_CRYSTAL,
		AUTO_ANCHOR,
		AUTO_LUNGE,
		ANTI_FIREBALL,
		KB_DISPLACE
	}

	/** How long a published Breach Swap setup keeps the hotbar (a handful of client ticks). */
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

	/** Useful for diagnostics; increments once per {@link #beginPreMovementTick}. */
	public static long dispatchSequence() {
		return dispatchSequence;
	}

	/**
	 * Auto Breachswap publishes its live setup here every pre-movement tick, before its own
	 * crosshair gate. While it holds the hotbar, AutoMace must not swap a mace in: the swap
	 * resolves with the SWORD's attributes, so a mace handoff even one tick early silently
	 * cancels the whole thing — that is the "Auto Mace interrupts Breach Swap" case. The hold
	 * expires on its own, so a stale flag can never wedge AutoMace off permanently.
	 */
	public static void markBreachSwapHold(boolean holding) {
		breachSwapHoldUntilNanos = holding ? System.nanoTime() + BREACH_HOLD_NANOS : 0L;
	}

	/** True while an armed Breach Swap owns the hotbar and no other controller may change slots. */
	public static boolean breachSwapHoldsHotbar() {
		return System.nanoTime() < breachSwapHoldUntilNanos;
	}

	/**
	 * Hand a successful Lunge jab into AutoMace without toggling its persisted module.
	 * The handoff is target-scoped and deliberately short: it exists only for the
	 * airborne arc produced by this one spear action.
	 */
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
	 * Open the Axe mode's sword-trigger continuation for exactly one opponent.
	 * Returns false when the mode/switches do not permit the continuation.
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

	/**
	 * Effective Triggerbot gate for a concrete target. Sword and Axe modes drive the Triggerbot from
	 * their own switch (Axe additionally opens a target-scoped window after a stun); Mace mode does
	 * not use it at all, so there it falls through to the standalone module.
	 */
	public static boolean triggerEnabledFor(ProFPSConfig config, UUID targetUuid) {
		return switch (CombatModePolicy.mode(config)) {
			case OFF, SWORD, MACE -> CombatModePolicy.enabled(config, CombatFeature.TRIGGER);
			// Axe mode runs the Triggerbot from its own switch; the post-stun continuation is an
			// EXTRA window on top, not the only way in. Gating solely on the continuation meant the
			// Triggerbot never fired at all unless a shield stun had just landed.
			case AXE -> CombatModePolicy.enabled(config, CombatFeature.TRIGGER)
					|| axeFollowupMatches(config, targetUuid);
		};
	}

	/** Consume a successful follow-up so it cannot spill into another cooldown cycle. */
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

	/** Clear all transient combat-mode state, for disconnects or a hard reset. */
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
