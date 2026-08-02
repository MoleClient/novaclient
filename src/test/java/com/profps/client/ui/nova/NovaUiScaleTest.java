package com.profps.client.ui.nova;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NovaUiScaleTest {
	@Test
	void automaticScaleKeepsTheCompleteVirtualLayoutInsideEveryViewport() {
		int[][] viewports = {
				{320, 180}, {320, 240}, {480, 270}, {640, 360},
				{854, 480}, {960, 540}, {1280, 720}, {1920, 1080}
		};

		for (int[] viewport : viewports) {
			float scale = NovaUiScale.resolve(viewport[0], viewport[1], true, 100);
			assertAll(
					() -> assertTrue(NovaUiScale.MIN_LAYOUT_WIDTH * scale <= viewport[0] - 15.9F),
					() -> assertTrue(NovaUiScale.MIN_LAYOUT_HEIGHT * scale <= viewport[1] - 15.9F),
					() -> assertTrue(NovaUiScale.virtualWidth(viewport[0], scale) >= NovaUiScale.MIN_LAYOUT_WIDTH),
					() -> assertTrue(NovaUiScale.virtualHeight(viewport[1], scale) >= NovaUiScale.MIN_LAYOUT_HEIGHT));
		}
	}

	@Test
	void manualScaleHonorsThePreferenceUntilTheWindowFitCeilingIsReached() {
		assertEquals(1.20F, NovaUiScale.resolve(1920, 1080, false, 120), 0.0001F);

		float constrained = NovaUiScale.resolve(480, 270, false, 140);
		assertTrue(constrained < 1.40F);
		assertTrue(NovaUiScale.MIN_LAYOUT_WIDTH * constrained <= 464.1F);
		assertTrue(NovaUiScale.MIN_LAYOUT_HEIGHT * constrained <= 254.1F);
	}
}
