package com.profps.client.instants;

/** Pure transition rule for the axe-break → falling mace follow-up. */
final class MaceShieldComboPolicy {
	private MaceShieldComboPolicy() {}

	/**
	 * Hold the armed mace while the combo is still in the air but vanilla's
	 * 1.5-block smash threshold has not been crossed yet.
	 *
	 * <p>The airborne test is deliberately not "falling". Between the axe tap and
	 * the swap back the arc can still be rising, or sitting at the apex where
	 * vertical velocity passes through zero; treating those ticks as ground
	 * combat spent the mace hit early, at no fall distance, for none of the smash
	 * bonus the whole combo is for.
	 */
	static boolean waitForSmash(boolean armedFollowup, boolean airborne, boolean smashing) {
		return armedFollowup && airborne && !smashing;
	}
}
