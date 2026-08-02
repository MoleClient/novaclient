package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AxeStunPolicyTest {
	@Test
	void shieldBreakNeverWaitsForTheOldSeventyTwoPercentFloor() {
		assertEquals(0.55F, AxeStunPolicy.requiredCharge(72, false), 1.0E-6F);
		assertEquals(0.48F, AxeStunPolicy.requiredCharge(72, true), 1.0E-6F);
	}

	@Test
	void boundedFallbackStillRequiresARealChargedHit() {
		assertFalse(AxeStunPolicy.readyToHit(0.34F, 0.55F, 106, 106));
		assertTrue(AxeStunPolicy.readyToHit(0.35F, 0.55F, 106, 106));
		assertFalse(AxeStunPolicy.readyToHit(0.50F, 0.55F, 105, 106));
		assertTrue(AxeStunPolicy.readyToHit(0.55F, 0.55F, 105, 106));
	}
}
