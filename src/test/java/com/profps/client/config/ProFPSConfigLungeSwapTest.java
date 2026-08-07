package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 94: Auto Lunge becomes Auto Lunge Swap, humanized by default. */
final class ProFPSConfigLungeSwapTest {
	@Test
	void migrationTurnsHumanizationOnByDefault() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 93;
		config.lungeSwapHumanize = false;

		sanitize(config);

		assertAll(
				() -> assertEquals(98, config.configVersion),
				// A swap that fires on the same frame offset every time is a
				// stronger tell than the swap itself, so the sampled delays are
				// the default rather than an opt-in.
				() -> assertTrue(config.lungeSwapHumanize),
				() -> assertTrue(config.lungeSpamScaling));
	}

	@Test
	void aCurrentConfigKeepsAnIntentionalOptOut() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.lungeSwapHumanize = false;   // deliberately turned off after the migration

		sanitize(config);

		assertAll(
				() -> assertEquals(98, config.configVersion),
				() -> org.junit.jupiter.api.Assertions.assertFalse(config.lungeSwapHumanize,
						"a re-run must not re-stamp the user's own choice"));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
