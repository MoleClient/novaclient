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

	@Test
	void theApexStillWaitsBecauseTheArcIsNotOverYet() {
		// The middle argument is airborne, not falling; vertical velocity is zero at the apex.
		assertTrue(MaceShieldComboPolicy.waitForSmash(true, true, false),
				"an armed follow-up mid-arc must hold until the smash threshold");
	}

	@Test
	void landingIsTheOnlyThingThatEndsTheWaitWithoutASmash() {
		assertFalse(MaceShieldComboPolicy.waitForSmash(true, false, false),
				"on the ground there is no smash coming, so stop holding the hit");
	}
}
