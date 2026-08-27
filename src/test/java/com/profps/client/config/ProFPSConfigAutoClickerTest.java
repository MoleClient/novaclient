package com.profps.client.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Version 99: Auto Clicker clicks continuously, not only on a target. */
final class ProFPSConfigAutoClickerTest {
	@Test
	void clickingIsNotNarrowedToTargetsByDefault() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 98;
		config.instantClickTargetOnly = true;

		sanitize(config);

		assertAll(
				() -> assertEquals(112, config.configVersion),
				() -> assertFalse(config.instantClickTargetOnly));
	}

	@Test
	void aFreshConfigAlsoClicksWithoutATarget() {
		ProFPSConfig config = new ProFPSConfig();

		assertFalse(config.instantClickTargetOnly,
				"a new install must not start in target-only mode");
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
