package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the arithmetic behind the stun slam, all of it read off 1.21.11 bytecode.
 *
 * <p>The combo failed for years of tuning because the timing was never the problem: below about
 * 3.8 blocks of fall the mace cannot beat the axe hit it has to overwrite inside the
 * invulnerability window, so it deals nothing at all. These tests exist so a future "improvement"
 * that lowers the trigger has to argue with the damage numbers first.
 */
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

	/** A full-charge netherite axe crit is the floor the slam has to clear: 10 × 1.0 × 1.5. */
	@Test
	void theAxeTapSetsAFifteenDamageFloor() {
		assertEquals(15.0D, StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true), 1e-9);
		assertEquals(10.0D, StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, false), 1e-9);
	}

	/** The number this whole rework turns on. */
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

	/** Gravity projection: a dive committed at 1.3 blocks is still short when the mace swings. */
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

	/**
	 * A weaker axe lowers the floor, so the same fall becomes worth committing. The rule reads the
	 * real weapon rather than assuming netherite, which is what keeps it from being either too
	 * eager with a strong axe or needlessly shy with a wooden one.
	 */
	@Test
	void aWeakerAxeLowersTheRequiredFall() {
		double netherite = StunSlamPolicy.breakEvenFall(
				StunSlamPolicy.axeTapDamage(NETHERITE_AXE, 1.0D, true), MACE, MACE_CHARGE_AT_SLAM);
		double wooden = StunSlamPolicy.breakEvenFall(
				StunSlamPolicy.axeTapDamage(7.0D, 1.0D, true), MACE, MACE_CHARGE_AT_SLAM);
		assertTrue(wooden < netherite, wooden + " should be less than " + netherite);
	}

	/** Not critting the axe halves the problem — worth knowing if the sprint rule ever changes. */
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
