package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaceShieldComboPolicyTest {
	@Test
	void earlyAxeTapWaitsForTheRealSmashThreshold() {
		assertTrue(MaceShieldComboPolicy.waitForSmash(true, true, false));
	}

	@Test
	void smashWindowAndLandingBothReleaseTheFollowup() {
		assertFalse(MaceShieldComboPolicy.waitForSmash(true, true, true));
		assertFalse(MaceShieldComboPolicy.waitForSmash(true, false, false));
	}

	@Test
	void ordinaryMaceCombatNeverWaitsOnTheComboRule() {
		assertFalse(MaceShieldComboPolicy.waitForSmash(false, true, false));
	}
}
