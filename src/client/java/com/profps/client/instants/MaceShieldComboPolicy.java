package com.profps.client.instants;

/** Pure transition rule for the axe-break → falling mace follow-up. */
final class MaceShieldComboPolicy {
	private MaceShieldComboPolicy() {}

	/**
	 * Hold the armed mace when the axe connected early in the descent but vanilla's
	 * 1.5-block smash threshold has not been crossed yet.
	 */
	static boolean waitForSmash(boolean armedFollowup, boolean falling, boolean smashing) {
		return armedFollowup && falling && !smashing;
	}
}
