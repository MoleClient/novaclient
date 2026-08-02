package com.profps.client.assists;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class VelocityControllerTest {
	@Test
	void recoveredJitterAddsAtMostFivePercent() {
		assertArrayEquals(new double[] {80.0D, 100.0D},
				VelocityController.reducedPercents(80, 100, 0.0D));
		assertArrayEquals(new double[] {85.0D, 100.0D},
				VelocityController.reducedPercents(80, 100, 1.0D));
	}

	@Test
	void recoveredVerticalRuleRoundsNinetyAndAboveToFull() {
		assertArrayEquals(new double[] {0.0D, 100.0D},
				VelocityController.reducedPercents(0, 88, 0.5D));
	}
}
