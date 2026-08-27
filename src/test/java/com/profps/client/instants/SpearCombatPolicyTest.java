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

	@Test
	void closingSpeedSubtractsWhatTheTargetCarriesAway() {
		// Stationary target: the full attacker speed counts.
		assertEquals(5.6D, SpearCombatPolicy.closingSpeed(5.6D, 0.0D), 1.0E-9D);
		// Equal speeds in the same direction close nothing.
		assertEquals(0.0D, SpearCombatPolicy.closingSpeed(5.6D, 5.6D), 1.0E-9D);
		// A faster target clamps to zero rather than going negative.
		assertEquals(0.0D, SpearCombatPolicy.closingSpeed(5.6D, 9.0D), 1.0E-9D);
	}

	@Test
	void theSpearIsInertUntilTheChargeHasBeenHeldItsArmDelay() {
		assertFalse(SpearCombatPolicy.armed(9, 10));
		assertTrue(SpearCombatPolicy.armed(10, 10));
		// Netherite arms two ticks sooner, so the delay is read off the stack.
		assertTrue(SpearCombatPolicy.armed(8, 8));
	}

	@Test
	void contactOnlyDamagesInsideTheWindowAndAboveTheClosingBar() {
		// Armed, in the window, and fast enough.
		assertTrue(SpearCombatPolicy.contactDamages(0, 200, 5.6D, 4.6D));
		assertTrue(SpearCombatPolicy.contactDamages(200, 200, 4.6D, 4.6D));
		// Walking pace of 4.3 m/s sits below the 4.6 closing bar.
		assertFalse(SpearCombatPolicy.contactDamages(20, 200, 4.3D, 4.6D));
		// Past the damage window the held charge no longer damages.
		assertFalse(SpearCombatPolicy.contactDamages(201, 200, 9.0D, 4.6D));
	}

	@Test
	void aTargetInsideTheMinimumRangeIsUnreachableNotJustAwkward() {
		assertFalse(SpearCombatPolicy.withinContactBand(1.9D, 2.0D, 4.5D));
		assertTrue(SpearCombatPolicy.withinContactBand(2.0D, 2.0D, 4.5D));
		assertTrue(SpearCombatPolicy.withinContactBand(4.5D, 2.0D, 4.5D));
		assertFalse(SpearCombatPolicy.withinContactBand(4.6D, 2.0D, 4.5D));
	}
}
