package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 95: Spear Charge Assist is replaced by Auto Spear. */
final class ProFPSConfigAutoSpearTest {
	@Test
	void autoSpearArrivesOffAndWithSilentAimOff() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 94;

		sanitize(config);

		assertAll(
				() -> assertEquals(98, config.configVersion),
				() -> assertFalse(config.autoSpearEnabled, "a module that aims for you is opt-in"),
				// Silent aim decouples the camera from the body; it must never be
				// switched on for somebody who did not choose it.
				() -> assertFalse(config.autoSpearSilentAim));
	}

	@Test
	void theDefaultReachCoversASwoopNotJustMeleeRange() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 94;

		sanitize(config);

		// The whole point is arming during an approach. At elytra speed a player
		// covers a couple of blocks per tick, so a melee-sized range would leave
		// no ticks at all between acquiring and contact.
		assertTrue(config.autoSpearRange >= 24, "was " + config.autoSpearRange);
	}

	@Test
	void outOfRangeValuesAreClamped() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.autoSpearRange = 9_999;
		config.autoSpearFov = -30;
		config.autoSpearTurnSpeed = 1_000;

		sanitize(config);

		assertAll(
				() -> assertEquals(96, config.autoSpearRange),
				() -> assertEquals(20, config.autoSpearFov),
				() -> assertEquals(90, config.autoSpearTurnSpeed));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
