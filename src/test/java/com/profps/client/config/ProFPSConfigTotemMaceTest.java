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

		// Auto Totem no longer has an inventory setting at all. A hotbar totem
		// reaches the offhand with the swap-hands key, which needs no screen; a
		// totem stored deeper leaves no legal alternative to a real inventory,
		// and the only other outcome there is doing nothing — not a choice worth
		// offering, so the field is gone rather than defaulted.
		assertAll(
				() -> assertEquals(102, config.configVersion),
				() -> assertTrue(config.lungeSpamScaling),
				() -> assertTrue(config.hitCritSprintRelease));
	}

	@Test
	void silentAimStaysOptInSoNothingChangesUntilItIsAskedFor() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 91;

		sanitize(config);

		// Silent aim decouples the camera from the body; it must never arrive
		// switched on for someone who did not choose it.
		assertFalse(config.maceSilentAim);
	}

	@Test
	void anAlreadyCurrentConfigKeepsTheUsersOwnChoices() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.maceSilentAim = true;

		sanitize(config);

		// Migrations are keyed on the version, so a config already at 92 must not
		// have its settings re-stamped back to the defaults on every launch.
		assertAll(
				() -> assertEquals(102, config.configVersion),
				() -> assertTrue(config.maceSilentAim));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
