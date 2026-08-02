package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaceAttackTimingPolicyTest {
	@Test
	void chargedHitAlwaysFires() {
		assertTrue(MaceAttackTimingPolicy.shouldAttack(
				0.93F, 0.92F, false, false, 0.0D, 90.0D));
	}

	@Test
	void firstContactDoesNotWaitThroughAResetMaceCooldown() {
		assertTrue(MaceAttackTimingPolicy.shouldAttack(
				0.04F, 0.92F, true, false, 1_450.0D, 70.0D));
	}

	@Test
	void nearlyReadyFirstContactWaitsForTheStrongerHit() {
		assertFalse(MaceAttackTimingPolicy.shouldAttack(
				0.88F, 0.92F, true, false, 65.0D, 90.0D));
	}

	@Test
	void subsequentGroundHitsKeepTheConfiguredCooldown() {
		assertFalse(MaceAttackTimingPolicy.shouldAttack(
				0.40F, 0.92F, false, false, 866.0D, 70.0D));
	}

	@Test
	void genuineSmashTakesItsOneFrameWindow() {
		assertTrue(MaceAttackTimingPolicy.shouldAttack(
				0.12F, 0.78F, true, true, 1_100.0D, 70.0D));
	}
}
