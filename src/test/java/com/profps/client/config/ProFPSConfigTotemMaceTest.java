package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 92: Auto Totem silent-by-default, silent aim, lunge spam scaling, crit sprint release. */
final class ProFPSConfigTotemMaceTest {
	@Test
	void migrationMovesAutoTotemOffTheInventoryScreen() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 91;
		config.totemOpenInventory = true;

		sanitize(config);

		assertAll(
				() -> assertEquals(95, config.configVersion),
				// The visible-inventory refill locks the player's own movement and
				// clicks for as long as the screen is up, which is the worst moment
				// to do it. Silent swap is the default the migration installs.
				() -> assertFalse(config.totemOpenInventory),
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
		config.totemOpenInventory = true;   // a deliberate opt-in after the migration
		config.maceSilentAim = true;

		sanitize(config);

		// Migrations are keyed on the version, so a config already at 92 must not
		// have its settings re-stamped back to the defaults on every launch.
		assertAll(
				() -> assertEquals(95, config.configVersion),
				() -> assertTrue(config.totemOpenInventory, "a re-run must not undo the user's own choice"),
				() -> assertTrue(config.maceSilentAim));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
