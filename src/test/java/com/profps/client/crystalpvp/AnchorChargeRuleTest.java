package com.profps.client.crystalpvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the detonation rule recovered from {@code RespawnAnchorBlock.onUseWithItem} bytecode:
 *
 * <pre>
 *   if (isChargeItem(main) &amp;&amp; canCharge(state))                       -> charge
 *   if (hand == MAIN_HAND &amp;&amp; isChargeItem(offhand) &amp;&amp; canCharge(state)) -> PASS
 *   else                                                            -> explode
 * </pre>
 *
 * with {@code isChargeItem} = glowstone and {@code canCharge} = {@code charges < 4}.
 */
class AnchorChargeRuleTest {

	@Test
	void anUnchargedAnchorAlwaysNeedsGlowstone() {
		assertTrue(AnchorMacroController.needsChargeFor(0, false));
		assertTrue(AnchorMacroController.needsChargeFor(0, true));
	}

	@Test
	void oneChargeIsEnoughWhenTheOffhandIsNotGlowstone() {
		// The ordinary case: a totem or empty offhand, so a main-hand click reaches the explode path.
		for (int charges = 1; charges <= 4; charges++) {
			assertFalse(AnchorMacroController.needsChargeFor(charges, false),
					"charges=" + charges);
		}
	}

	@Test
	void glowstoneInTheOffhandForcesAFullAnchor() {
		// This is the bug that read as "it doesn't explode": vanilla defers the main-hand click to
		// the offhand while the anchor can still take a charge, so it quietly charges instead.
		// Only a full anchor makes canCharge false and lets the click detonate.
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
}
