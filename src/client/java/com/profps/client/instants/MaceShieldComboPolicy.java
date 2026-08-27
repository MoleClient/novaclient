package com.profps.client.instants;

/** Pure transition rule for the axe-break to falling-mace follow-up. */
final class MaceShieldComboPolicy {
	private MaceShieldComboPolicy() {}

	/**
	 * Holds the armed mace while airborne until vanilla's 1.5-block smash threshold is crossed.
	 * The test is "airborne" rather than "falling" so the rising and apex ticks still count.
	 */
	static boolean waitForSmash(boolean armedFollowup, boolean airborne, boolean smashing) {
		return armedFollowup && airborne && !smashing;
	}
}
