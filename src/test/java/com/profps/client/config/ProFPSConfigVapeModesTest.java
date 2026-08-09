package com.profps.client.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProFPSConfigVapeModesTest {
	@Test
	void newProfilesUseTheVapeModeDefaults() {
		ProFPSConfig config = new ProFPSConfig();
		assertEquals(0, config.scaffoldMode);
		assertTrue(config.scaffoldBlacklist);
		assertEquals(2, config.scaffoldGodActivationBlocks);
		assertEquals(0, config.crystalAuraMode);
		assertEquals(0, config.crystalTargetMode);
		assertTrue(config.crystalAntiSuicide);
		assertEquals(1, config.anchorMode);
		assertTrue(config.anchorAimAssist);
		assertTrue(config.anchorDetonate);
		assertEquals(0, config.anchorDelayMinMs);
		assertEquals(25, config.anchorDelayMaxMs);
		assertEquals(0, config.velocityTicks);
		assertEquals(120, config.velocityKiteHorizontal);
		assertEquals(50, config.instantAutoToolSwapToDelayMs);
		assertTrue(config.instantAutoToolSwapWeapon);
		assertEquals(0, config.instantFastPlaceHeldItem);
		assertEquals(1, config.instantFastPlaceDelay);
		assertEquals(6, config.instantClickMinCps);
		assertFalse(config.instantClickHoldToClick);
		assertTrue(config.inventoryAutoArmorOpen);
		assertEquals(150, config.inventoryChestStealDelayMinMs);
		assertFalse(config.hypixelBedBreaker);
		assertEquals(101, config.configVersion);
	}

	@Test
	void recoveredModeValuesAreClamped() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.scaffoldMode = 99;
		config.crystalAuraMode = -8;
		config.crystalTargetMode = 12;
		config.crystalOptimization = 9;
		config.anchorMode = -4;
		config.velocityTicks = 40;
		config.velocityKiteHorizontal = 900;
		config.velocityKiteVertical = -20;
		config.instantAutoToolSwapToDelayMs = -5;
		config.instantAutoToolSwapBackDelayMs = 9000;
		config.instantFastPlaceHeldItem = 99;
		config.instantFastPlaceDelay = -2;
		config.instantClickMinCps = 50;
		config.instantClickRandomization = 20;
		config.inventoryAutoHotbarWeaponSlot = 90;
		config.inventoryRefillType = -3;
		var sanitize = ProFPSConfig.class.getDeclaredMethod("sanitize");
		sanitize.setAccessible(true);
		assertTrue((Boolean) sanitize.invoke(config));
		assertEquals(2, config.scaffoldMode);
		assertEquals(0, config.crystalAuraMode);
		assertEquals(3, config.crystalTargetMode);
		assertEquals(2, config.crystalOptimization);
		assertEquals(0, config.anchorMode);
		assertEquals(10, config.velocityTicks);
		assertEquals(300, config.velocityKiteHorizontal);
		assertEquals(100, config.velocityKiteVertical);
		assertEquals(0, config.instantAutoToolSwapToDelayMs);
		assertEquals(1000, config.instantAutoToolSwapBackDelayMs);
		assertEquals(2, config.instantFastPlaceHeldItem);
		assertEquals(0, config.instantFastPlaceDelay);
		assertEquals(config.instantClickCps, config.instantClickMinCps);
		assertEquals(2, config.instantClickRandomization);
		assertEquals(9, config.inventoryAutoHotbarWeaponSlot);
		assertEquals(0, config.inventoryRefillType);
	}

	@Test
	void v86AddsUtilityDefaultsWithoutEnablingModules() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 85;
		config.velocityKiteMode = true;
		config.instantAutoToolSwapBack = true;
		config.instantFastPlace = false;
		var sanitize = ProFPSConfig.class.getDeclaredMethod("sanitize");
		sanitize.setAccessible(true);
		assertTrue((Boolean) sanitize.invoke(config));
		assertAll(
				() -> assertEquals(101, config.configVersion),
				() -> assertFalse(config.velocityKiteMode),
				() -> assertFalse(config.instantAutoToolSwapBack),
				() -> assertFalse(config.instantFastPlace),
				() -> assertEquals(1, config.instantFastPlaceDelay));
	}

	@Test
	void v87AddsRemainingParityModulesDisabled() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 86;
		config.inventoryAutoArmor = true;
		config.inventoryChestSteal = true;
		config.hypixelBedBreaker = true;
		var sanitize = ProFPSConfig.class.getDeclaredMethod("sanitize");
		sanitize.setAccessible(true);
		assertTrue((Boolean) sanitize.invoke(config));
		assertAll(
				() -> assertEquals(101, config.configVersion),
				() -> assertFalse(config.inventoryAutoArmor),
				() -> assertFalse(config.inventoryChestSteal),
				() -> assertFalse(config.hypixelBedBreaker));
	}

	@Test
	void v89SpeedsAnchorActionsWithoutChangingDetonationChoice() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 88;
		config.anchorDelayMinMs = 50;
		config.anchorDelayMaxMs = 100;
		config.anchorDetonate = false;
		var sanitize = ProFPSConfig.class.getDeclaredMethod("sanitize");
		sanitize.setAccessible(true);
		assertTrue((Boolean) sanitize.invoke(config));
		assertAll(
				() -> assertEquals(101, config.configVersion),
				() -> assertEquals(0, config.anchorDelayMinMs),
				() -> assertEquals(25, config.anchorDelayMaxMs),
				() -> assertFalse(config.anchorDetonate));
	}
}
