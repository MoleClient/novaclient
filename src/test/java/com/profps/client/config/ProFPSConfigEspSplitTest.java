package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version 93: Storage ESP splits out, Advanced ESP becomes terrain-only, Suspicious Chunks arrives. */
final class ProFPSConfigEspSplitTest {
	@Test
	void migrationInstallsTheSplitWithBothNewModulesOff() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 92;

		sanitize(config);

		assertAll(
				() -> assertEquals(98, config.configVersion),
				// Both new overlays draw through terrain, so neither may switch
				// itself on for somebody who never asked for it.
				() -> assertFalse(config.donutStorageEsp),
				() -> assertFalse(config.donutSuspiciousChunks),
				() -> assertTrue(config.donutStorageShowChests),
				() -> assertTrue(config.donutStorageShowShulkers),
				() -> assertTrue(config.donutStorageShowRedstone),
				// The module is named Hole/Tunnel/Stairs ESP, so stairs have to
				// actually be on by default for the name to be honest.
				() -> assertTrue(config.donutAdvancedShowStairs));
	}

	@Test
	void suspiciousChunksDefaultsToDeepslateAndBelow() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 92;

		sanitize(config);

		// The whole point is the depth a player cannot see from the surface.
		// A ceiling up at build height would just re-flag everything visible.
		assertTrue(config.donutSuspiciousChunksCeiling <= 16);
	}

	@Test
	void outOfRangeEspValuesAreClampedRatherThanTrusted() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.donutStorageEspRange = 99_999;
		config.donutStorageEspOpacity = -4;
		config.donutSuspiciousChunksRange = 1;
		config.donutSuspiciousChunksCeiling = 5_000;

		sanitize(config);

		assertAll(
				() -> assertEquals(512, config.donutStorageEspRange),
				() -> assertEquals(5, config.donutStorageEspOpacity),
				() -> assertEquals(48, config.donutSuspiciousChunksRange),
				() -> assertEquals(64, config.donutSuspiciousChunksCeiling));
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
