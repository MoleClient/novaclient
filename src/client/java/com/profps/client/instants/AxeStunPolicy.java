package com.profps.client.instants;

/** Pure shield-break timing rules shared by Axe Stun and regression tests. */
final class AxeStunPolicy {
	static final float FALLBACK_CHARGE = 0.35F;

	private AxeStunPolicy() {}

	static float requiredCharge(int configuredPercent, boolean criticalFall) {
		float ordinary = Math.min(
				Math.max(35, Math.min(100, configuredPercent)) / 100.0F,
				0.55F);
		return criticalFall ? Math.min(ordinary, 0.48F) : ordinary;
	}

	static boolean readyToHit(float cooldown, float required,
			int currentAge, int deadlineAge) {
		if (!Float.isFinite(cooldown)) return false;
		return cooldown >= required
				|| (currentAge >= deadlineAge && cooldown >= FALLBACK_CHARGE);
	}
}
