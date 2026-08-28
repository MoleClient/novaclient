package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 112: Auto Spear defaults, range migration and clamping. */
final class ProFPSConfigAutoSpearTest {
	@Test
	void autoSpearArrivesOffAndWithSilentAimOff() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 94;

		sanitize(config);

		assertAll(
				() -> assertEquals(114, config.configVersion),
				() -> assertFalse(config.autoSpearEnabled, "a module that aims for you is opt-in"),
				() -> assertFalse(config.autoSpearSilentAim));
	}

	@Test
	void theDefaultRangeIsTheFightYouAreInNotTheOneAcrossTheMap() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 94;

		sanitize(config);

		// Contact resolves between 2 and 4.5 blocks; range only decides how early the spear comes out.
		assertTrue(config.autoSpearRange <= 24, "was " + config.autoSpearRange);
		assertTrue(config.autoSpearRange >= 12, "was " + config.autoSpearRange);
	}

	@Test
	void aProfileFromTheOldRangeIsMigratedRatherThanLeftEager() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 111;
		config.autoSpearRange = 42;
		config.autoSpearFov = 75;
		config.autoSpearTurnSpeed = 48;

		sanitize(config);

		assertAll(
				() -> assertEquals(20, config.autoSpearRange),
				() -> assertEquals(90, config.autoSpearFov),
				() -> assertEquals(55, config.autoSpearTurnSpeed));
	}

	@Test
	void outOfRangeValuesAreClamped() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.autoSpearRange = 9_999;
		config.autoSpearFov = -30;
		config.autoSpearTurnSpeed = 1_000;

		sanitize(config);

		assertAll(
				() -> assertEquals(64, config.autoSpearRange),
				() -> assertEquals(20, config.autoSpearFov),
				() -> assertEquals(90, config.autoSpearTurnSpeed));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
