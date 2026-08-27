package com.profps.client.crystalpvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastUsePolicyTest {

	@Test
	void onlyPhysicalUseInputCanActivateFastUse() {
		assertFalse(FastUseController.canAccelerate(false, false));
		assertTrue(FastUseController.canAccelerate(true, false));
	}

	@Test
	void anchorSequenceAlwaysKeepsVanillaCooldown() {
		assertFalse(FastUseController.canAccelerate(false, true));
		assertFalse(FastUseController.canAccelerate(true, true));
	}
}
