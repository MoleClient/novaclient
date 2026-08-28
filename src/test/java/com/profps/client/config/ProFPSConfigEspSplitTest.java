package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 93: Storage ESP splits out and Advanced ESP becomes terrain-only. */
final class ProFPSConfigEspSplitTest {
	@Test
	void migrationInstallsTheSplitWithStorageEspOff() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 92;

		sanitize(config);

		assertAll(
				() -> assertEquals(114, config.configVersion),
				() -> assertFalse(config.donutStorageEsp),
				() -> assertTrue(config.donutStorageShowChests),
				() -> assertTrue(config.donutStorageShowShulkers),
				() -> assertTrue(config.donutStorageShowRedstone),
				() -> assertTrue(config.donutAdvancedShowStairs));
	}

	@Test
	void outOfRangeEspValuesAreClampedRatherThanTrusted() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.donutStorageEspRange = 99_999;
		config.donutStorageEspOpacity = -4;

		sanitize(config);

		assertAll(
				() -> assertEquals(512, config.donutStorageEspRange),
				() -> assertEquals(5, config.donutStorageEspOpacity));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
