package com.profps.client.crystalpvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrystalInteractionPolicyTest {
	@Test
	void intervalsStayBoundedAcrossTheSlider() {
		for (int speed = -3; speed <= 14; speed++) {
			for (int sample = -5; sample <= 105; sample++) {
				int interval = CrystalInteractionPolicy.intervalTicks(speed, sample);
				assertTrue(interval >= 1 && interval <= 7,
						"speed=" + speed + ", sample=" + sample + ", interval=" + interval);
			}
		}
	}

	@Test
	void fasterSettingsNeverHaveASlowerBestCase() {
		int previousBest = Integer.MAX_VALUE;
		for (int speed = 1; speed <= 10; speed++) {
			int best = Integer.MAX_VALUE;
			for (int sample = 0; sample < 100; sample++) {
				best = Math.min(best, CrystalInteractionPolicy.intervalTicks(speed, sample));
			}
			assertTrue(best <= previousBest);
			previousBest = best;
		}
	}

	@Test
	void topSpeedKeepsAHumanReactionFloor() {
		assertEquals(1, CrystalInteractionPolicy.intervalTicks(10, 99));
		assertEquals(2, CrystalInteractionPolicy.intervalTicks(10, 0));
		for (int sample = 0; sample < 100; sample++) {
			assertTrue(CrystalInteractionPolicy.intervalTicks(10, sample) >= 1);
			assertTrue(CrystalInteractionPolicy.intervalTicks(10, sample) <= 2);
		}
	}

	@Test
	void topSpeedIsAtLeastThreeTicksLeanerThanTheOldFloor() {
		// Two intervals per place/break cycle.
		int worstCycle = CrystalInteractionPolicy.intervalTicks(10, 0) * 2;
		assertTrue(worstCycle <= 4, "worstCycle=" + worstCycle);
	}
}
