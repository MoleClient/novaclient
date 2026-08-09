package com.profps.client.data;

import com.profps.client.data.ContributionGate.Taint;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate is the whole reason the corpus is worth anything, so its decision table is pinned here.
 * A leak in either direction is expensive: letting module output through poisons the training data,
 * and over-blocking silently collects nothing.
 */
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

	/**
	 * The regression this test exists for: the scopes were once compared as a severity ladder, so
	 * a combat-scoped module masked a blocks-scoped one and contaminated mining ticks were kept.
	 * They are independent vetoes.
	 */
	@Test
	void narrowTaintsStackRatherThanMasking() {
		Set<Taint> taints = EnumSet.of(Taint.COMBAT, Taint.BLOCKS);
		assertAll(
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.COMBAT)),
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.MINING)),
				() -> assertFalse(ContributionGate.allows(taints, ActivityClassifier.BUILDING)),
				() -> assertTrue(ContributionGate.allows(taints, ActivityClassifier.TRAVELING)));
	}

	/** An unclassified module must exclude everything — new modules are guilty until listed. */
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
	void classifiedModulesKeepTheirNarrowScope() {
		assertAll(
				() -> assertEquals(Taint.NONE, ContributionGate.scopeOf("fullbright")),
				() -> assertEquals(Taint.BLOCKS, ContributionGate.scopeOf("autotool")),
				() -> assertEquals(Taint.BLOCKS, ContributionGate.scopeOf("fastbreak")),
				() -> assertEquals(Taint.COMBAT, ContributionGate.scopeOf("totem")));
	}
}
