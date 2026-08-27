package com.profps.client.instants;

/**
 * Pure first-contact cooldown policy for AutoMace. A mace slot change resets vanilla
 * attack charge and a full recharge takes about 1.6 seconds, so the first contact may
 * swing under-charged; later swings use the configured charge gate.
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
		// Wait for the stronger hit only when the remaining recharge fits the hold budget.
		return Math.max(0.0D, remainingChargeMs) > Math.max(0.0D, holdBudgetMs);
	}
}
