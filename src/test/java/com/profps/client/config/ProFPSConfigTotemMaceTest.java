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
	void inventoryIsKeptOnlyAsAFallbackForATotemOutsideTheHotbar() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 91;
		config.totemOpenInventory = false;

		sanitize(config);

		// The refill itself no longer uses a screen: a hotbar totem goes to the
		// offhand with the swap-hands key, which needs none. This flag now only
		// governs whether a totem stored deeper than the hotbar may be staged
		// through a real inventory, so leaving it on costs nothing in the normal
		// case and is the only way to recover in the abnormal one.
		assertAll(
				() -> assertEquals(100, config.configVersion),
				() -> assertTrue(config.totemOpenInventory),
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
				() -> assertEquals(100, config.configVersion),
				() -> assertTrue(config.totemOpenInventory, "a re-run must not undo the user's own choice"),
				() -> assertTrue(config.maceSilentAim));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
