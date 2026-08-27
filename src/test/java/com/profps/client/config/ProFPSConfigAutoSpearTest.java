package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 112: Auto Spear is rebuilt around the charge it holds and the aim it drives. */
final class ProFPSConfigAutoSpearTest {
	@Test
	void autoSpearArrivesOffAndWithSilentAimOff() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 94;

		sanitize(config);

		assertAll(
				() -> assertEquals(112, config.configVersion),
				() -> assertFalse(config.autoSpearEnabled, "a module that aims for you is opt-in"),
				// Silent aim decouples the camera from the body; it must never be
				// switched on for somebody who did not choose it.
				() -> assertFalse(config.autoSpearSilentAim));
	}

	@Test
	void theDefaultRangeIsTheFightYouAreInNotTheOneAcrossTheMap() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 94;

		sanitize(config);

		// Contact resolves between 2 and ~4.5 blocks, so the range only decides how early
		// the spear comes out. The old 42m default armed a charge at elytra distance and
		// held the use key down across half a server; 20m is the engagement itself.
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
