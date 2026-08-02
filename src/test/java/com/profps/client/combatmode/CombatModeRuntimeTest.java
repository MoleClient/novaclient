package com.profps.client.combatmode;

import com.profps.client.combatmode.CombatModeRuntime.ActionOwner;
import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatModeRuntimeTest {
	private ProFPSConfig config;

	@BeforeEach
	void setUp() {
		CombatModeRuntime.reset();
		config = new ProFPSConfig();
	}

	@AfterEach
	void tearDown() {
		CombatModeRuntime.reset();
	}

	@Test
	void onlyOneControllerOwnsAPreMovementDispatch() {
		CombatModeRuntime.beginPreMovementTick(config);
		long firstSequence = CombatModeRuntime.dispatchSequence();

		assertTrue(CombatModeRuntime.tryClaim(ActionOwner.AXE_STUN));
		assertTrue(CombatModeRuntime.tryClaim(ActionOwner.AXE_STUN)); // idempotent for the owner
		assertFalse(CombatModeRuntime.tryClaim(ActionOwner.TRIGGER));
		assertEquals(ActionOwner.AXE_STUN, CombatModeRuntime.claimedBy());

		CombatModeRuntime.beginPreMovementTick(config);
		assertFalse(CombatModeRuntime.actionClaimed());
		assertTrue(CombatModeRuntime.dispatchSequence() > firstSequence);
		assertTrue(CombatModeRuntime.tryClaim(ActionOwner.TRIGGER));
	}

	@Test
	void axeFollowupIsModeBoundTargetScopedAndConsumable() {
		UUID stunned = UUID.randomUUID();
		UUID bystander = UUID.randomUUID();
		config.combatMode = CombatMode.AXE.configValue();
		// The continuation is what you get when the mode's own Triggerbot switch is OFF: the only
		// swings allowed are the ones inside the window a stun just opened, on that one target.
		config.axeModeTrigger = false;

		assertFalse(CombatModeRuntime.triggerEnabledFor(config, stunned));
		assertTrue(CombatModeRuntime.armAxeFollowup(config, stunned));
		assertTrue(CombatModeRuntime.axeFollowupMatches(config, stunned));
		assertFalse(CombatModeRuntime.axeFollowupMatches(config, bystander));
		assertTrue(CombatModeRuntime.triggerEnabledFor(config, stunned));
		assertFalse(CombatModeRuntime.triggerEnabledFor(config, bystander));
		assertTrue(CombatModeRuntime.axeFollowupRemainingMs() > 0L);

		CombatModeRuntime.consumeAxeFollowup(stunned);
		assertFalse(CombatModeRuntime.triggerEnabledFor(config, stunned));
	}

	@Test
	void changingModeOrDisablingFollowupClearsTheTarget() {
		UUID target = UUID.randomUUID();
		config.combatMode = CombatMode.AXE.configValue();
		assertTrue(CombatModeRuntime.armAxeFollowup(config, target));

		config.combatMode = CombatMode.SWORD.configValue();
		CombatModeRuntime.beginPreMovementTick(config);
		assertNull(CombatModeRuntime.axeFollowupTarget());

		config.combatMode = CombatMode.AXE.configValue();
		assertTrue(CombatModeRuntime.armAxeFollowup(config, target));
		config.axeModeTriggerFollowup = false;
		assertFalse(CombatModeRuntime.axeFollowupMatches(config, target));
		assertNull(CombatModeRuntime.axeFollowupTarget());
	}

	@Test
	void spearMaceHandoffIsTargetScopedAndConsumable() {
		UUID target = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		CombatModeRuntime.armSpearMace(target, 2_000L);
		assertTrue(CombatModeRuntime.spearMaceActive());
		assertEquals(target, CombatModeRuntime.spearMaceTarget());

		CombatModeRuntime.consumeSpearMace(other);
		assertTrue(CombatModeRuntime.spearMaceActive());
		CombatModeRuntime.consumeSpearMace(target);
		assertFalse(CombatModeRuntime.spearMaceActive());
		assertNull(CombatModeRuntime.spearMaceTarget());
	}
}
