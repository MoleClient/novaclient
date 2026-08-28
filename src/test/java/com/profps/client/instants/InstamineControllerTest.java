package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstamineControllerTest {
	private static final float NO_JITTER = 1.0F;

	@Test
	void targetTicksSpanEightDownToOne() {
		assertEquals(8.0F, InstamineController.targetTicks(1), 1.0E-4F);
		assertEquals(1.0F, InstamineController.targetTicks(10), 1.0E-4F);
		assertTrue(InstamineController.targetTicks(5) < InstamineController.targetTicks(4));
	}

	@Test
	void levelIsClampedRatherThanExtrapolated() {
		assertEquals(InstamineController.targetTicks(1), InstamineController.targetTicks(-5), 1.0E-4F);
		assertEquals(InstamineController.targetTicks(10), InstamineController.targetTicks(99), 1.0E-4F);
	}

	@Test
	void cooldownFallsFromNearVanillaToNone() {
		assertEquals(4, InstamineController.cooldownTicks(1));
		assertEquals(0, InstamineController.cooldownTicks(10));
		assertTrue(InstamineController.cooldownTicks(1) < InstamineController.VANILLA_COOLDOWN_TICKS);
	}

	@Test
	void fallingBlocksKeepTheirVanillaRate() {
		float vanilla = 0.05F;
		assertEquals(vanilla, InstamineController.breakDelta(vanilla, 10, true, NO_JITTER), 1.0E-6F);
	}

	@Test
	void aHardBlockIsLiftedToTheTargetRate() {
		// Obsidian is a very small vanilla delta; the target decides the time instead.
		float delta = InstamineController.breakDelta(0.004F, 10, false, NO_JITTER);
		assertEquals(1.0F, delta, 1.0E-4F);
	}

	@Test
	void aSoftBlockIsNeverSlowedDown() {
		// Vanilla already breaks this in one tick; the target must not drag it to eight.
		float vanilla = 1.0F;
		assertEquals(vanilla, InstamineController.breakDelta(vanilla, 1, false, NO_JITTER), 1.0E-6F);
	}

	@Test
	void onlyTheTopLevelReachesASingleTickBreak() {
		// Vanilla short-circuits to an instant break at a delta of 1.0, so every level below
		// the top has to stay under it and take at least two ticks.
		assertTrue(InstamineController.breakDelta(0.0F, 9, false, NO_JITTER) < 1.0F);
		assertEquals(1.0F, InstamineController.breakDelta(0.0F, 10, false, NO_JITTER), 1.0E-4F);
	}
}
