package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Schematic Build speed: default 10, migrated to 10, clamped to 1..10. */
final class ProFPSConfigSchematicBuildTest {
	@Test
	void speedDefaultsToQuickest() {
		assertEquals(10, new ProFPSConfig().schematicBuildSpeed);
	}

	@Test
	void v113MovesExistingProfilesToTheQuickestPace() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 112;
		config.schematicBuildSpeed = 5;

		sanitize(config);

		assertEquals(113, config.configVersion);
		assertEquals(10, config.schematicBuildSpeed);
	}

	@Test
	void speedIsClamped() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.schematicBuildSpeed = 99;
		sanitize(config);
		assertEquals(10, config.schematicBuildSpeed);

		config.schematicBuildSpeed = 0;
		sanitize(config);
		assertEquals(1, config.schematicBuildSpeed);
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
