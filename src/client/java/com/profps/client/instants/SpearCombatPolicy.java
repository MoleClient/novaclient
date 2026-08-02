package com.profps.client.instants;

/** Verified 1.21.11 gates used by the spear controllers. */
final class SpearCombatPolicy {
	static final float MIN_JAB_REACH = 2.0F;
	static final float MAX_JAB_REACH = 4.5F;
	static final int MIN_LUNGE_FOOD = 6;
	static final float FULL_JAB_CHARGE = 0.995F;

	private SpearCombatPolicy() {}

	static boolean canStartLunge(int food, boolean riding, boolean gliding, boolean touchingWater) {
		return food >= MIN_LUNGE_FOOD && !riding && !gliding && !touchingWater;
	}

	static boolean jabCharged(float cooldownProgress) {
		return Float.isFinite(cooldownProgress) && cooldownProgress >= FULL_JAB_CHARGE;
	}
}
