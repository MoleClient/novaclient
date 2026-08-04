package com.profps.client.combatmode;

import com.profps.client.combatmode.CombatModeProfile.Axe;
import com.profps.client.combatmode.CombatModeProfile.Breach;
import com.profps.client.combatmode.CombatModeProfile.Mace;
import com.profps.client.combatmode.CombatModeProfile.MeleeAim;
import com.profps.client.combatmode.CombatModeProfile.ProjectileAim;
import com.profps.client.combatmode.CombatModeProfile.Strafe;
import com.profps.client.combatmode.CombatModeProfile.Trigger;
import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatModePolicyTest {
	@Test
	void requestedDefaultsAreStable() {
		ProFPSConfig config = new ProFPSConfig();

		assertAll(
					() -> assertEquals(94, config.configVersion),
				() -> assertEquals(CombatMode.OFF, CombatModePolicy.mode(config)),
				() -> assertEquals(CombatTier.HT4, CombatTier.fromIndex(config.swordModeTier)),
				() -> assertEquals(CombatTier.LT4, CombatTier.fromIndex(config.axeModeTier)),
				() -> assertEquals(CombatTier.LT3, CombatTier.fromIndex(config.maceModeTier)),
				() -> assertEquals(93, config.hitCooldownPct),
				() -> assertTrue(config.swordModeAutoSprint),
				() -> assertFalse(config.swordModeAiBot),
				() -> assertTrue(config.axeModeBowAim),
				() -> assertTrue(config.axeModeCrossbowAim),
				() -> assertTrue(config.axeModeTriggerFollowup),
				() -> assertFalse(config.axeStunRestorePrevious),
				() -> assertTrue(config.maceModeStunSlam));
	}

	@Test
	void modeToggleIsExclusiveAndARepeatedSelectionTurnsItOff() {
		ProFPSConfig config = new ProFPSConfig();

		CombatModePolicy.toggleMode(config, CombatMode.SWORD);
		assertEquals(CombatMode.SWORD, CombatModePolicy.mode(config));

		CombatModePolicy.toggleMode(config, CombatMode.AXE);
		assertEquals(CombatMode.AXE, CombatModePolicy.mode(config));

		CombatModePolicy.toggleMode(config, CombatMode.AXE);
		assertEquals(CombatMode.OFF, CombatModePolicy.mode(config));
	}

	@Test
	void swordHt4ExactlyMatchesExistingDefaults() {
		ProFPSConfig config = new ProFPSConfig();
		config.combatMode = CombatMode.SWORD.configValue();
		config.swordModeTier = CombatTier.HT4.index();

		MeleeAim aim = CombatModePolicy.meleeAim(config);
		Strafe strafe = CombatModePolicy.strafe(config);
		Trigger trigger = CombatModePolicy.trigger(config);

		assertAll(
				() -> assertEquals(54, aim.strengthPct()),
				() -> assertEquals(950, aim.durationMs()),
				() -> assertEquals(32, aim.fovDeg()),
				() -> assertEquals(60, aim.reactionMs()),
				() -> assertEquals(50, strafe.strengthPct()),
				() -> assertEquals(360, strafe.reachMs()),
				() -> assertEquals(8, strafe.skipChancePct()),
				() -> assertEquals(420, strafe.intervalMs()),
				() -> assertEquals(8, trigger.reactionMinMs()),
				() -> assertEquals(55, trigger.reactionMaxMs()),
				() -> assertEquals(2, trigger.skipChancePct()),
				() -> assertEquals(93, trigger.cooldownPct()),
				() -> assertEquals(45, trigger.followupMs()),
				() -> assertEquals(120, trigger.axePostDelayMs()),
				() -> assertTrue(trigger.naturalSweepChance() > 0.0D));
	}

	@Test
	void axeLt4ExactlyMatchesExistingDefaults() {
		ProFPSConfig config = new ProFPSConfig();
		config.combatMode = CombatMode.AXE.configValue();
		config.axeModeTier = CombatTier.LT4.index();

		MeleeAim aim = CombatModePolicy.meleeAim(config);
		Axe axe = CombatModePolicy.axe(config);
		ProjectileAim projectile = CombatModePolicy.projectileAim(config);
		Trigger trigger = CombatModePolicy.trigger(config);

		assertAll(
				() -> assertEquals(54, aim.strengthPct()),
				() -> assertEquals(950, aim.durationMs()),
				() -> assertEquals(32, aim.fovDeg()),
				() -> assertEquals(60, aim.reactionMs()),
				() -> assertEquals(110, axe.stunReactionMs()),
				() -> assertEquals(90, axe.switchToSwordMs()),
				() -> assertEquals(45, projectile.strengthPct()),
				() -> assertEquals(70, projectile.fovDeg()),
				() -> assertEquals(8, trigger.reactionMinMs()),
				() -> assertEquals(55, trigger.reactionMaxMs()),
				() -> assertEquals(93, trigger.cooldownPct()),
				() -> assertEquals(45, trigger.followupMs()),
				() -> assertTrue(axe.stunReactionJitterMs() > 0),
				() -> assertTrue(projectile.aimTremorYaw() > 0.0D));
	}

	@Test
	void maceLt3ExactlyMatchesExistingDefaultsAndPearlCatchIsRetired() {
		ProFPSConfig config = new ProFPSConfig();
		config.combatMode = CombatMode.MACE.configValue();
		config.maceModeTier = CombatTier.LT3.index();

		Mace mace = CombatModePolicy.mace(config);
		Breach breach = CombatModePolicy.breach(config);
		assertAll(
				() -> assertEquals(45, mace.fovDeg()),
				() -> assertEquals(6, mace.trackingRange()),
				() -> assertEquals(45, mace.turnSpeedPct()),
				() -> assertEquals(70, mace.groundSettleMs()),
				() -> assertEquals(75, mace.smashSpeedPct()),
				() -> assertEquals(60, mace.stunGapMs()),
				() -> assertEquals(90, breach.chargePct()),
				() -> assertFalse(CombatModePolicy.enabled(config, CombatFeature.PEARL_CATCH)));

		// Pearl Catch is retired: no tier, mode or legacy flag can arm it again.
		for (CombatTier tier : CombatTier.values()) {
			config.maceModeTier = tier.index();
			assertFalse(CombatModePolicy.enabled(config, CombatFeature.PEARL_CATCH));
		}
		config.combatMode = CombatMode.OFF.configValue();
		assertFalse(CombatModePolicy.enabled(config, CombatFeature.PEARL_CATCH));
	}

	@Test
	void maceAutoSwitchFollowsTheActiveProfile() {
		ProFPSConfig config = new ProFPSConfig();
		config.autoMace = true;
		assertTrue(CombatModePolicy.autoMaceAutoSwitch(config));
		config.autoMaceAutoSwitch = false;
		assertFalse(CombatModePolicy.autoMaceAutoSwitch(config));

		config.combatMode = CombatMode.MACE.configValue();
		assertTrue(CombatModePolicy.autoMaceAutoSwitch(config));
		config.maceModeAutoSwitch = false;
		assertFalse(CombatModePolicy.autoMaceAutoSwitch(config));

		// Under another mode AutoMace runs as the standalone module, so its switch follows suit.
		config.autoMaceAutoSwitch = true;
		config.combatMode = CombatMode.SWORD.configValue();
		assertTrue(CombatModePolicy.autoMaceAutoSwitch(config));
		config.autoMaceAutoSwitch = false;
		assertFalse(CombatModePolicy.autoMaceAutoSwitch(config));
	}

	@Test
	void modesOwnCoveredFeaturesWithoutMutatingLegacyFlags() {
		ProFPSConfig config = new ProFPSConfig();
		config.aimImprovements = true;
		config.strafeImprovements = true;
		config.hitImprovements = true;
		config.autoAim = true;

		assertAll(
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.MELEE_AIM)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.STRAFE)),
				() -> assertFalse(CombatModePolicy.enabled(config, CombatFeature.SWORD_AUTO_SPRINT)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.TRIGGER)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.FIREBALL_AIM)));

		config.combatMode = CombatMode.SWORD.configValue();
		assertAll(
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.MELEE_AIM)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.STRAFE)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.SWORD_AUTO_SPRINT)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.TRIGGER)),
				// Sword mode does not drive projectiles, so Auto Aim keeps running on its own.
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.PROJECTILE_AIM)));

		config.swordModeAiBot = true;
		assertAll(
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.SWORD_AI)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.SWORD_AI_AIM)),
				() -> assertFalse(CombatModePolicy.enabled(config, CombatFeature.MELEE_AIM)),
				() -> assertFalse(CombatModePolicy.enabled(config, CombatFeature.STRAFE)),
				() -> assertFalse(CombatModePolicy.enabled(config, CombatFeature.SWORD_AUTO_SPRINT)));

		config.combatMode = CombatMode.AXE.configValue();
		assertAll(
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.BOW_AIM)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.CROSSBOW_AIM)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.FIREBALL_AIM)),
				// Axe mode drives the Triggerbot from its own switch, like Sword mode does.
				() -> assertTrue(CombatModeRuntime.triggerEnabledFor(config, java.util.UUID.randomUUID())));

		// With that switch off, the only way in is the post-stun continuation.
		config.axeModeTrigger = false;
		assertFalse(CombatModeRuntime.triggerEnabledFor(config, java.util.UUID.randomUUID()));
		config.axeModeTrigger = true;

		config.enabled = false;
		assertFalse(CombatModePolicy.enabled(config, CombatFeature.AXE_STUN));
	}

	@Test
	void aModeOnlyTakesOverTheModulesItActuallyDrives() {
		ProFPSConfig config = new ProFPSConfig();
		config.combatMode = CombatMode.MACE.configValue();
		config.hitImprovements = true;
		config.axeStun = true;
		config.autoAim = true;
		config.aimImprovements = true;
		config.swordAiEnabled = true;

		// Mace mode owns the mace and nothing else: everything it does not drive keeps running from
		// its own module, including the Triggerbot.
		assertAll(
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.TRIGGER)),
				() -> assertTrue(CombatModeRuntime.triggerEnabledFor(config, java.util.UUID.randomUUID())),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.AXE_STUN)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.PROJECTILE_AIM)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.MELEE_AIM)),
				() -> assertTrue(CombatModePolicy.enabled(config, CombatFeature.SWORD_AI)));

		// The mace itself comes from the mode, not from the standalone AutoMace flag.
		config.autoMace = false;
		config.maceModeAutoMace = true;
		assertTrue(CombatModePolicy.enabled(config, CombatFeature.AUTO_MACE));
		config.maceModeAutoMace = false;
		assertFalse(CombatModePolicy.enabled(config, CombatFeature.AUTO_MACE));
	}
}
