package com.profps.client.crystalpvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the detonation rule from {@code RespawnAnchorBlock.onUseWithItem}. */
class AnchorChargeRuleTest {

	@Test
	void anUnchargedAnchorAlwaysNeedsGlowstone() {
		assertTrue(AnchorMacroController.needsChargeFor(0, false));
		assertTrue(AnchorMacroController.needsChargeFor(0, true));
	}

	@Test
	void oneChargeIsEnoughWhenTheOffhandIsNotGlowstone() {
		// A totem or empty offhand lets a main-hand click reach the explode path.
		for (int charges = 1; charges <= 4; charges++) {
			assertFalse(AnchorMacroController.needsChargeFor(charges, false),
					"charges=" + charges);
		}
	}

	@Test
	void glowstoneInTheOffhandForcesAFullAnchor() {
		// Vanilla defers the main-hand click to the offhand while the anchor can still charge.
		assertTrue(AnchorMacroController.needsChargeFor(1, true));
		assertTrue(AnchorMacroController.needsChargeFor(2, true));
		assertTrue(AnchorMacroController.needsChargeFor(3, true));
		assertFalse(AnchorMacroController.needsChargeFor(4, true));
	}

	@Test
	void anOverFullAnchorIsNeverToppedUp() {
		assertFalse(AnchorMacroController.needsChargeFor(5, true));
		assertFalse(AnchorMacroController.needsChargeFor(5, false));
	}

	@Test
	void anchorSpeedIsFastButAlwaysRandomized() {
		assertEquals(0, AnchorMacroController.actionDelayMinMsForSpeed(10));
		assertEquals(52, AnchorMacroController.actionDelayMaxMsForSpeed(10));
		assertEquals(10, AnchorMacroController.actionDelayMinMsForSpeed(8));
		assertEquals(82, AnchorMacroController.actionDelayMaxMsForSpeed(8));
		assertEquals(35, AnchorMacroController.actionDelayMinMsForSpeed(6));
		assertEquals(115, AnchorMacroController.actionDelayMaxMsForSpeed(6));
		assertEquals(205, AnchorMacroController.actionDelayMinMsForSpeed(1));
		assertEquals(320, AnchorMacroController.actionDelayMaxMsForSpeed(1));
	}

	@Test
	void blockUsesNeedSeparateClientTicks() {
		assertTrue(AnchorMacroController.useSeparatedByTick(Integer.MIN_VALUE, 5));
		assertFalse(AnchorMacroController.useSeparatedByTick(20, 20));
		assertTrue(AnchorMacroController.useSeparatedByTick(20, 21));
	}
}
