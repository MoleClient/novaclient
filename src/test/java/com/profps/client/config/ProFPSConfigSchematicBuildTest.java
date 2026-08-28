package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Schematic Build speed: default 5, clamped to 1..10. */
final class ProFPSConfigSchematicBuildTest {
	@Test
	void speedDefaultsToTheVanillaCadence() {
		assertEquals(5, new ProFPSConfig().schematicBuildSpeed);
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

	@Test
	void autoMoveBuilderArrivesOff() {
		assertFalse(new ProFPSConfig().autoMoveEnabled, "a module that moves for you is opt-in");
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
