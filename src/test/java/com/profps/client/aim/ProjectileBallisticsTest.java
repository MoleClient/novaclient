package com.profps.client.aim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectileBallisticsTest {
	@Test
	void flatFullBowShotUsesAStableLowArc() {
		ProjectileBallistics.ArrowArc arc =
				ProjectileBallistics.solveLowArc(24.0D, 0.0D, 3.0D);
		assertNotNull(arc);
		assertTrue(arc.angle() > 0.0D);
		assertTrue(arc.angle() < 0.35D);
		assertTrue(arc.error() < 0.18D);
	}

	@Test
	void inheritedMotionUsesTheSameArrowDragSeries() {
		assertEquals(1.0D, ProjectileBallistics.inheritedDisplacement(1.0D), 1.0E-9D);
		assertEquals(1.99D, ProjectileBallistics.inheritedDisplacement(2.0D), 1.0E-9D);
		assertEquals(0.0D, ProjectileBallistics.inheritedDisplacement(Double.NaN), 0.0D);
	}

	@Test
	void impossibleInputsDoNotFabricateAnArc() {
		assertNull(ProjectileBallistics.solveLowArc(12.0D, 0.0D, 0.0D));
		assertNull(ProjectileBallistics.solveLowArc(Double.NaN, 0.0D, 3.0D));
	}
}
