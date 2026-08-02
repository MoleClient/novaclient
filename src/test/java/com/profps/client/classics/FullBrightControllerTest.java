package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FullBrightControllerTest {
	@Test
	void disabledLeavesVanillaLightmapUntouched() {
		ProFPSConfig config = new ProFPSConfig();
		FullBrightController.initialize(config);

		assertEquals(0.24F, FullBrightController.adjustNightVision(0.24F));
		assertEquals(0.62F, FullBrightController.adjustGamma(0.62F));
	}

	@Test
	void maximumLevelProducesFullLightmapStrength() {
		ProFPSConfig config = new ProFPSConfig();
		config.fullBrightEnabled = true;
		config.fullBrightLevel = 10;
		FullBrightController.initialize(config);

		assertEquals(1.0F, FullBrightController.adjustNightVision(0.0F));
		assertEquals(1.0F, FullBrightController.adjustGamma(0.0F));
	}

	@Test
	void lowerLevelsAreSmoothAndNeverReduceRealEffects() {
		ProFPSConfig config = new ProFPSConfig();
		config.fullBrightEnabled = true;
		config.fullBrightLevel = 1;
		FullBrightController.initialize(config);

		assertEquals(0.19F, FullBrightController.adjustNightVision(0.0F), 0.0001F);
		assertEquals(0.415F, FullBrightController.adjustGamma(0.0F), 0.0001F);
		assertEquals(0.85F, FullBrightController.adjustNightVision(0.85F));
		assertEquals(0.90F, FullBrightController.adjustGamma(0.90F));
	}
}
