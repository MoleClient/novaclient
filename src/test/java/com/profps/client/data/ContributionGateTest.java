package com.profps.client.data;

import com.profps.client.data.ContributionGate.Taint;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the contribution gate decision table for taint scopes and activities. */
class ContributionGateTest {
	private static final Set<Taint> CLEAN = EnumSet.noneOf(Taint.class);

	@Test
	void nothingEnabledKeepsEveryActivity() {
		assertAll(
				() -> assertTrue(ContributionGate.allows(CLEAN, ActivityClassifier.COMBAT)),
				() -> assertTrue(ContributionGate.allows(CLEAN, ActivityClassifier.MINING)),
				() -> assertTrue(ContributionGate.allows(CLEAN, ActivityClassifier.TRAVELING)));
	}

	@Test
	void allTaintSuppressesEverything() {
		Set<Taint> taints = EnumSet.of(Taint.ALL);
		assertAll(
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.TRAVELING)),
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.COMBAT)),
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.IDLE)));
	}

	@Test
	void combatTaintKeepsTravelling() {
		Set<Taint> taints = EnumSet.of(Taint.COMBAT);
		assertAll(
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.COMBAT)),
				() -> assertTrue(ContributionGate.allows(taints, ActivityClassifier.TRAVELING)),
				() -> assertTrue(ContributionGate.allows(taints, ActivityClassifier.MINING)));
	}

	@Test
	void blocksTaintSuppressesMiningAndBuildingOnly() {
		Set<Taint> taints = EnumSet.of(Taint.BLOCKS);
		assertAll(
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.MINING)),
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.BUILDING)),
				() -> assertTrue(ContributionGate.allows(taints, ActivityClassifier.COMBAT)),
				() -> assertTrue(ContributionGate.allows(taints, ActivityClassifier.TRAVELING)));
	}

	@Test
	void narrowTaintsStackRatherThanMasking() {
		Set<Taint> taints = EnumSet.of(Taint.COMBAT, Taint.BLOCKS);
		assertAll(
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.COMBAT)),
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.MINING)),
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.BUILDING)),
				() -> assertTrue(ContributionGate.allows(taints, ActivityClassifier.TRAVELING)));
	}

	@Test
	void unknownModulesTaintEverything() {
		assertAll(
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("some_module_added_next_year")),
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("")),
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("flight")),
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("freecam")),
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("hit")),
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("aim")));
	}

	@Test
	void hitboxesIsTheDeliberateRenderExemption() {
		assertAll(
				() -> assertEquals(Taint.NONE, ContributionGate.scopeOf("hitboxes")),
				() -> assertTrue(ContributionGate.allows(EnumSet.noneOf(Taint.class),
						ActivityClassifier.COMBAT)),
				// The exemption does not extend to the ESP family.
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("mobesp")),
				() -> assertEquals(Taint.ALL, ContributionGate.scopeOf("storageesp")));
	}

	@Test
	void classifiedModulesKeepTheirNarrowScope() {
		assertAll(
				() -> assertEquals(Taint.NONE, ContributionGate.scopeOf("fullbright")),
				() -> assertEquals(Taint.BLOCKS, ContributionGate.scopeOf("autotool")),
				() -> assertEquals(Taint.BLOCKS, ContributionGate.scopeOf("instamine")),
				() -> assertEquals(Taint.COMBAT, ContributionGate.scopeOf("totem")));
	}
}
