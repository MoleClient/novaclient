package com.profps.client.instants;

/**
 * Pure first-contact cooldown policy for AutoMace.
 *
 * <p>A mace slot change resets vanilla attack charge and a full recharge is about
 * 1.6 seconds. Waiting that entire period after the reticle has already crossed a
 * briefly exposed player misses the interaction completely. One initial low-charge
 * click is legal vanilla behavior; subsequent swings in the same engagement retain
 * the normal configured charge gate.</p>
 */
final class MaceAttackTimingPolicy {
	static final long MIN_INITIAL_REATTACK_NANOS = 320_000_000L;

	private MaceAttackTimingPolicy() {}

	static boolean shouldAttack(float cooldown, float configuredThreshold,
			boolean firstContact, boolean genuineSmash, double remainingChargeMs,
			double holdBudgetMs) {
		float threshold = Math.max(0.0F, Math.min(1.0F, configuredThreshold));
		if (cooldown >= threshold) return true;
		if (!firstContact) return false;
		if (genuineSmash) return true;
		// If only a short wait remains, take the stronger hit. If the mace has
		// effectively just reset, use the current legal contact instead of tracking
		// the opponent for a full recharge after the opportunity has passed.
		return Math.max(0.0D, remainingChargeMs) > Math.max(0.0D, holdBudgetMs);
	}
}
