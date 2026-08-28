package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auto Totem, silent aim, lunge spam scaling and crit sprint release defaults. */
final class ProFPSConfigTotemMaceTest {
	@Test
	void oldAutoTotemDefaultsMigrateForward() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 91;

		sanitize(config);

		assertAll(
				() -> assertEquals(113, config.configVersion),
				() -> assertTrue(config.lungeSpamScaling),
				() -> assertTrue(config.hitCritSprintRelease));
	}

	@Test
	void silentAimStaysOptInSoNothingChangesUntilItIsAskedFor() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 91;

		sanitize(config);

		assertFalse(config.maceSilentAim);
	}

	@Test
	void anAlreadyCurrentConfigKeepsTheUsersOwnChoices() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.maceSilentAim = true;

		sanitize(config);

		// Migrations are keyed on the version, so a current config is never re-stamped.
		assertAll(
				() -> assertEquals(113, config.configVersion),
				() -> assertTrue(config.maceSilentAim));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
