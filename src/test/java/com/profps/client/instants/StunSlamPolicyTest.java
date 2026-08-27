package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the stun slam damage arithmetic, read off 1.21.11 bytecode. */
class StunSlamPolicyTest {
	private static final double NETHERITE_AXE = 10.0D;
	private static final double MACE = 7.0D;
	/** One tick of mace recharge after the axe tap reset the shared attack clock. */
	private static final double MACE_CHARGE_AT_SLAM = 0.03D;

	@Test
	void bonusFollowsTheVanillaThreeSegmentCurve() {
		assertAll(
				() -> assertEquals(0.0D, StunSlamPolicy.bonusDamage(0.0D), 1e-9),
				() -> assertEquals(6.0D, StunSlamPolicy.bonusDamage(1.5D), 1e-9),
				() -> assertEquals(12.0D, StunSlamPolicy.bonusDamage(3.0D), 1e-9),
				() -> assertEquals(22.0D, StunSlamPolicy.bonusDamage(8.0D), 1e-9),
				() -> assertEquals(24.0D, StunSlamPolicy.bonusDamage(10.0D), 1e-9));
	}

	@Test
	void chargeMultiplierMatchesVanilla() {
		assertAll(
				() -> assertEquals(0.2D, StunSlamPolicy.chargeMultiplier(0.0D), 1e-9),
				() -> assertEquals(1.0D, StunSlamPolicy.chargeMultiplier(1.0D), 1e-9),
				// clamped, not extrapolated
				() -> assertEquals(1.0D, StunSlamPolicy.chargeMultiplier(2.0D), 1e-9));
	}

	/** A full-charge netherite axe crit is the floor the slam has to clear: 10 x 1.0 x 1.5. */
	@Test
	void theAxeTapSetsAFifteenDamageFloor() {
		assertEquals(15.0D, StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true), 1e-9);
		assertEquals(10.0D, StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, false), 1e-9);
	}

	@Test
	void breakEvenIsAboutThreePointEightBlocks() {
		double breakEven = StunSlamPolicy.breakEvenFall(15.0D, MACE, MACE_CHARGE_AT_SLAM);
		assertEquals(3.80D, breakEven, 0.02D);
	}

	@Test
	void belowBreakEvenTheSlamIsAbsorbedEntirely() {
		double axe = StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true);
		for (double fall : new double[] {1.3D, 2.0D, 3.0D, 3.5D}) {
			double slam = StunSlamPolicy.slamDamage(MACE, MACE_CHARGE_AT_SLAM, fall);
			assertEquals(0.0D, StunSlamPolicy.netSlamDamage(slam, axe), 1e-9,
					"a slam from " + fall + " blocks must read as doing nothing");
		}
	}

	@Test
	void aboveBreakEvenTheSlamLands() {
		double axe = StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true);
		double slam = StunSlamPolicy.slamDamage(MACE, MACE_CHARGE_AT_SLAM, 8.0D);
		assertTrue(StunSlamPolicy.netSlamDamage(slam, axe) > 8.0D);
	}

	/** A dive committed at 1.3 blocks is still short of break-even when the mace swings. */
	@Test
	void theOldTriggerCouldNotReachBreakEvenInTime() {
		double fallAtSlam = StunSlamPolicy.projectFall(1.3D, -0.5D, StunSlamPolicy.SLAM_DELAY_TICKS);
		assertTrue(fallAtSlam < 3.8D,
				"1.3 blocks projects to " + fallAtSlam + ", which is below the 3.8 break-even");
		assertFalse(StunSlamPolicy.worthCommitting(1.3D, -0.5D,
				NETHERITE_AXE, 1.0D, true, MACE, MACE_CHARGE_AT_SLAM));
	}

	@Test
	void aRealDiveCommits() {
		assertTrue(StunSlamPolicy.worthCommitting(4.5D, -1.0D,
				NETHERITE_AXE, 1.0D, true, MACE, MACE_CHARGE_AT_SLAM));
	}

	@Test
	void aWeakerAxeLowersTheRequiredFall() {
		double netherite = StunSlamPolicy.breakEvenFall(
				StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true), MACE, MACE_CHARGE_AT_SLAM);
		double wooden = StunSlamPolicy.breakEvenFall(
				StunSlamPolicy.axeTapDamage(7.0D, 1.0D, true), MACE, MACE_CHARGE_AT_SLAM);
		assertTrue(wooden < netherite, wooden + " should be less than " + netherite);
	}

	/** Sprint is kept below 0.9 charge so the axe tap does not crit and the floor drops. */
	@Test
	void keepingSprintBelowTheLaunchThresholdMakesOrdinaryDivesLand() {
		double axeSprinted = StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 0.9D, false);
		double axeCrit = StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true);
		assertAll(
				() -> assertTrue(StunSlamPolicy.breakEvenFall(axeSprinted, MACE, MACE_CHARGE_AT_SLAM) < 2.0D),
				() -> assertTrue(StunSlamPolicy.breakEvenFall(axeCrit, MACE, MACE_CHARGE_AT_SLAM) > 3.5D),
				// A 1.5-block dive is refused with a crit tap and committed without one.
				() -> assertFalse(StunSlamPolicy.worthCommitting(1.5D, -0.6D,
						NETHERITE_AXE, 1.0D, true, MACE, MACE_CHARGE_AT_SLAM)),
				() -> assertTrue(StunSlamPolicy.worthCommitting(1.5D, -0.6D,
						NETHERITE_AXE, 0.9D, false, MACE, MACE_CHARGE_AT_SLAM)));
	}

	@Test
	void aNonCritAxeIsMuchEasierToBeat() {
		double crit = StunSlamPolicy.breakEvenFall(
				StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true), MACE, MACE_CHARGE_AT_SLAM);
		double flat = StunSlamPolicy.breakEvenFall(
				StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, false), MACE, MACE_CHARGE_AT_SLAM);
		assertAll(
				() -> assertEquals(3.80D, crit, 0.02D),
				() -> assertEquals(2.15D, flat, 0.02D));
	}
}
