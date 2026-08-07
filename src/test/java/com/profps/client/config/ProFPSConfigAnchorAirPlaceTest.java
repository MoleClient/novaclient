package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 96: Anchor Macro gains a real air place. */
final class ProFPSConfigAnchorAirPlaceTest {
	@Test
	void migrationTurnsAirPlaceOn() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 95;
		config.anchorAirPlace = false;

		sanitize(config);

		// There was no air place at all before this: every click waited on the
		// crosshair confirming the face, so a placement the server would have
		// accepted was refused whenever the view happened to be blocked.
		assertAll(
				() -> assertEquals(97, config.configVersion),
				() -> assertTrue(config.anchorAirPlace));
	}

	@Test
	void aCurrentConfigKeepsAnIntentionalOptOut() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.anchorAirPlace = false;   // turned off deliberately, e.g. on a strict server

		sanitize(config);

		assertAll(
				() -> assertEquals(97, config.configVersion),
				() -> assertFalse(config.anchorAirPlace,
						"a re-run must not re-stamp the user's own choice"));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
