package com.profps.client.ui.nova;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlatantModuleWarningTest {
	@Test
	void everyPreviouslyMultiplayerGatedModuleRequiresConfirmation() {
		assertEquals(Set.of(
				"reach", "velocity", "flight", "noclip", "waterwalk", "boatfly", "teleporter"),
				BlatantModuleWarning.moduleIds());
		for (String id : BlatantModuleWarning.moduleIds()) {
			assertTrue(BlatantModuleWarning.requiresConfirmation(id));
		}
	}

	@Test
	void ordinaryModulesAreNotInterrupted() {
		assertFalse(BlatantModuleWarning.requiresConfirmation("autoaim"));
		assertFalse(BlatantModuleWarning.requiresConfirmation(null));
	}
}
