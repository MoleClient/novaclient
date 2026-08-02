package com.profps.client.instants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoClickRhythmTest {
	@Test
	void hardCapsLeftAndRightClickRates() {
		assertEquals(1, AutoClickRhythm.effectiveCps(-50, false));
		assertEquals(16, AutoClickRhythm.effectiveCps(20, false));
		assertEquals(12, AutoClickRhythm.effectiveCps(20, true));
	}

	@Test
	void intervalIsAlwaysPositiveAndBounded() {
		for (int cps = -5; cps <= 30; cps++) {
			for (int sample = 0; sample <= 100; sample++) {
				int interval = AutoClickRhythm.intervalTicks(
						cps,
						false,
						0.90 + sample / 100.0 * 0.24,
						sample / 100.0,
						(100 - sample) / 100.0,
						false,
						0
				);
				assertTrue(interval >= 1 && interval <= 25);
			}
		}
	}

	@Test
	void fourthFastIntervalMustBreathe() {
		int interval = AutoClickRhythm.intervalTicks(
				16,
				false,
				0.90,
				0.0,
				0.99,
				false,
				3
		);
		assertEquals(2, interval);
	}

	@Test
	void hesitationNeverShortensAnEquivalentInterval() {
		int ordinary = AutoClickRhythm.intervalTicks(10, false, 1.0, 0.5, 0.4, false, 0);
		int hesitation = AutoClickRhythm.intervalTicks(10, false, 1.0, 0.5, 0.4, true, 0);
		assertTrue(hesitation > ordinary);
	}
}
