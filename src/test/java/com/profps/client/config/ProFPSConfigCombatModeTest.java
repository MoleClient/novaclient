package com.profps.client.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ProFPSConfigCombatModeTest {
	@Test
	void v70MigrationInitializesModesWithoutOverwritingStandaloneModules() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 68;
		config.combatMode = 3;
		config.swordModeTier = 9;
		config.axeModeTier = 9;
		config.maceModeTier = 9;
		config.swordModeAiBot = true;
		config.aimImprovements = true;
		config.hitImprovements = true;
		config.autoMace = true;

		assertTrue(sanitize(config));

		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertEquals(0, config.combatMode),
				() -> assertEquals(3, config.swordModeTier),
				() -> assertEquals(2, config.axeModeTier),
				() -> assertEquals(4, config.maceModeTier),
				() -> assertFalse(config.swordModeAiBot),
				() -> assertTrue(config.swordModeAutoSprint),
				() -> assertTrue(config.aimImprovements),
				() -> assertTrue(config.hitImprovements),
				() -> assertTrue(config.autoMace));
	}

	@Test
	void sanitizeBoundsModesTiersAndCombatTiming() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.combatMode = 99;
		config.swordModeTier = -4;
		config.axeModeTier = 40;
		config.maceModeTier = -1;
		config.axeStunReactionMs = -20;
		config.autoMaceSettleMs = 999;
		config.autoBreachSwapCharge = 1;
		config.pearlCatchAimSpeed = 999;

		assertTrue(sanitize(config));

		assertAll(
				() -> assertEquals(0, config.combatMode),
				() -> assertEquals(0, config.swordModeTier),
				() -> assertEquals(9, config.axeModeTier),
				() -> assertEquals(0, config.maceModeTier),
				() -> assertEquals(0, config.axeStunReactionMs),
				() -> assertEquals(150, config.autoMaceSettleMs),
				() -> assertEquals(50, config.autoBreachSwapCharge),
				() -> assertEquals(95, config.pearlCatchAimSpeed));
	}

	@Test
	void v70AddsSwordSprintWithoutResettingTheSelectedMode() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 69;
		config.combatMode = 2;
		config.swordModeAutoSprint = false;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertEquals(2, config.combatMode),
				() -> assertTrue(config.swordModeAutoSprint));
	}

	@Test
	void v71AddsResponsiveUiDefaultsAndBoundsManualSize() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 70;
		config.guiAutoScale = false;
		config.guiScalePct = 140;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertTrue(config.guiAutoScale),
				() -> assertEquals(100, config.guiScalePct));

		config.guiScalePct = 999;
		assertTrue(sanitize(config));
		assertEquals(140, config.guiScalePct);
	}

	@Test
	void currentMigrationLeavesSchematicAutoMoveActive() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 71;
		config.schematicAutoMove = false;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertTrue(config.schematicAutoMove));
	}

	@Test
	void v73AddsFullBrightOffWithBalancedLevel() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 72;
		config.fullBrightEnabled = true;
		config.fullBrightLevel = 1;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertFalse(config.fullBrightEnabled),
				() -> assertEquals(7, config.fullBrightLevel));
	}

	@Test
	void v74EnablesTemporarySupportPlanning() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 73;
		config.schematicTemporaryBlocks = false;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertTrue(config.schematicTemporaryBlocks));
	}

	@Test
	void v75EnablesVisibleMaceHandoffsAndStrictCrystalRays() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 74;
		config.maceModeAutoSwitch = false;
		config.autoMaceAutoSwitch = false;
		config.autoCrystalStrictRay = false;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertTrue(config.maceModeAutoSwitch),
				() -> assertTrue(config.autoMaceAutoSwitch),
				() -> assertTrue(config.autoCrystalStrictRay));
	}

	@Test
	void v76MovesOnlyTheOldAutoMaceSettleDefault() throws Exception {
		ProFPSConfig migratedDefault = new ProFPSConfig();
		migratedDefault.configVersion = 75;
		migratedDefault.autoMaceSettleMs = 70;
		assertTrue(sanitize(migratedDefault));
		assertAll(
					() -> assertEquals(97, migratedDefault.configVersion),
				() -> assertEquals(35, migratedDefault.autoMaceSettleMs));

		ProFPSConfig custom = new ProFPSConfig();
		custom.configVersion = 75;
		custom.autoMaceSettleMs = 55;
		assertTrue(sanitize(custom));
		assertEquals(55, custom.autoMaceSettleMs);
	}

	@Test
	void v77StandsAutoMoveDownAndV78BringsItBack() throws Exception {
		ProFPSConfig heldDown = new ProFPSConfig();
		heldDown.configVersion = 76;
		heldDown.schematicAutoMove = true;
		assertTrue(sanitize(heldDown));
		assertAll(
					() -> assertEquals(97, heldDown.configVersion),
				() -> assertTrue(heldDown.schematicAutoMove));

		// A profile that already sat at v77 with the toggle gone also gets it
		// back: hover-to-place alone cannot finish a thick or wide schematic.
		ProFPSConfig dormant = new ProFPSConfig();
		dormant.configVersion = 77;
		dormant.schematicAutoMove = false;
		assertTrue(sanitize(dormant));
		assertAll(
					() -> assertEquals(97, dormant.configVersion),
				() -> assertTrue(dormant.schematicAutoMove));
	}

	@Test
	void v79AndV82MigrateAutoClickerToAutonomousDefaults() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 78;
		config.instantClickCps = 20;
		config.instantClickTargetOnly = false;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertEquals(20, config.instantClickCps),
				() -> assertFalse(config.instantClickTargetOnly));
	}

	@Test
	void v80AddsServerOrderedSpearDefaults() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 79;
		config.lungeAim = false;
		config.lungeSpearMace = false;
		config.lungeShieldBreak = false;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertTrue(config.lungeAim),
				() -> assertTrue(config.lungeSpearMace),
				() -> assertTrue(config.lungeShieldBreak),
				// Spear Charge Assist became Auto Spear in v95, and a module that
				// aims for you must never arrive switched on.
				() -> assertFalse(config.autoSpearEnabled),
				() -> assertFalse(config.autoSpearSilentAim));
	}

	@Test
	void v81KeepsPreviousSlotRestoreExplicitAndOff() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 80;
		config.axeStunRestorePrevious = true;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
				() -> assertFalse(config.axeStunRestorePrevious));
	}

	@Test
	void v82MakesAutoClickerImmediatelyObservableWithoutARequiredTarget() throws Exception {
		ProFPSConfig config = new ProFPSConfig();
		config.configVersion = 81;
		config.instantClickTargetOnly = true;

		assertTrue(sanitize(config));
		assertAll(
					() -> assertEquals(97, config.configVersion),
					() -> assertFalse(config.instantClickTargetOnly));
	}

	@Test
	void v83ReadsTheLegacyAnchorToggleIntoAnchorMacro() throws Exception {
		ProFPSConfig config = new Gson().fromJson(
				"{\"configVersion\":82,\"anchorTweaks\":true}", ProFPSConfig.class);

		assertTrue(config.anchorMacro);
		assertTrue(sanitize(config));
		assertEquals(97, config.configVersion);
	}

	@Test
	void v84DropsTheRetiredClassicUiToggle() throws Exception {
		// The classic panel is gone and guiExperimental with it. A config still carrying the key
		// has to load cleanly — Gson must ignore the unknown field rather than choking on it.
		ProFPSConfig config = new Gson().fromJson(
				"{\"configVersion\":83,\"guiExperimental\":false}", ProFPSConfig.class);

		assertTrue(sanitize(config));
		assertEquals(97, config.configVersion);
	}

	private static boolean sanitize(ProFPSConfig config) throws Exception {
		Method method = ProFPSConfig.class.getDeclaredMethod("sanitize");
		method.setAccessible(true);
		return (boolean) method.invoke(config);
	}
}
