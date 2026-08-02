package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpearCombatPolicyTest {
	@Test
	void mirrorsTheVanillaSpearRangeComponent() {
		assertEquals(2.0F, SpearCombatPolicy.MIN_JAB_REACH);
		assertEquals(4.5F, SpearCombatPolicy.MAX_JAB_REACH);
	}

	@Test
	void lungeRequiresSixFoodAndOrdinaryMovementState() {
		assertFalse(SpearCombatPolicy.canStartLunge(5, false, false, false));
		assertTrue(SpearCombatPolicy.canStartLunge(6, false, false, false));
		assertFalse(SpearCombatPolicy.canStartLunge(20, true, false, false));
		assertFalse(SpearCombatPolicy.canStartLunge(20, false, true, false));
		assertFalse(SpearCombatPolicy.canStartLunge(20, false, false, true));
	}

	@Test
	void jabNeverFiresBelowTheFullChargeComponentGate() {
		assertFalse(SpearCombatPolicy.jabCharged(0.994F));
		assertTrue(SpearCombatPolicy.jabCharged(0.995F));
		assertFalse(SpearCombatPolicy.jabCharged(Float.NaN));
	}
}
