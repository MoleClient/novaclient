package com.profps.client.combatmode;

import com.profps.client.config.ProFPSConfig;

import static com.profps.client.combatmode.CombatModeProfile.*;

/**
 * Resolves the persisted mode/tier controls into effective controller settings.
 *
 * <p>Never mutates the legacy module fields. With Modes off the legacy fields are returned
 * clamped to their supported ranges; with a mode active a fixed tier profile is returned for
 * the features that mode drives, and everything else falls through to the standalone module.
 * Legality gates (reach, crosshair, packet phase, collision, mouse GCD) are not represented
 * here and stay owned by the controllers.</p>
 */
public final class CombatModePolicy {
	private static final double NATURAL_SWEEP_CHANCE = 0.045D;

	private CombatModePolicy() {}

	public static CombatMode mode(ProFPSConfig config) {
		return config == null ? CombatMode.OFF : CombatMode.fromConfig(config.combatMode);
	}

	public static CombatTier tier(ProFPSConfig config) {
		return tier(config, mode(config));
	}

	public static CombatTier tier(ProFPSConfig config, CombatMode mode) {
		if (config == null) return CombatTier.HT4;
		return CombatTier.fromIndex(switch (mode) {
			case SWORD -> config.swordModeTier;
			case AXE -> config.axeModeTier;
			case MACE -> config.maceModeTier;
			case OFF -> CombatTier.HT4.index();
		});
	}

	/** Select a mode without touching or saving any standalone module setting. */
	public static void setMode(ProFPSConfig config, CombatMode mode) {
		if (config != null) config.combatMode = (mode == null ? CombatMode.OFF : mode).configValue();
	}

	/** Selecting the active mode turns Modes off; any other mode replaces it. */
	public static void toggleMode(ProFPSConfig config, CombatMode requested) {
		if (config == null) return;
		CombatMode safe = requested == null ? CombatMode.OFF : requested;
		config.combatMode = mode(config) == safe ? CombatMode.OFF.configValue() : safe.configValue();
	}

	public static boolean enabled(ProFPSConfig config, CombatFeature feature) {
		if (config == null || !config.enabled || feature == null) return false;
		return switch (mode(config)) {
			case OFF -> legacyEnabled(config, feature);
			case SWORD -> switch (feature) {
				case MELEE_AIM -> config.swordModeAim && !config.swordModeAiBot;
				case STRAFE -> config.swordModeStrafe && !config.swordModeAiBot;
				case SWORD_AUTO_SPRINT -> config.swordModeAutoSprint && !config.swordModeAiBot;
				case TRIGGER -> config.swordModeTrigger;
				case SWORD_AI -> config.swordModeAiBot;
				case SWORD_AI_AIM -> config.swordModeAiBot && config.swordModeAiAim;
				case SWORD_AI_JUMP -> config.swordModeAiBot && config.swordModeAiJump;
				default -> legacyEnabled(config, feature);
			};
			case AXE -> switch (feature) {
				case MELEE_AIM -> config.axeModeAim;
				case TRIGGER -> config.axeModeTrigger;
				case AXE_STUN -> config.axeModeStun;
				case AXE_CRIT -> config.axeModeCrit;
				case PROJECTILE_AIM -> config.axeModeProjectileAim
						&& (config.axeModeBowAim || config.axeModeCrossbowAim);
				case BOW_AIM -> config.axeModeProjectileAim && config.axeModeBowAim;
				case CROSSBOW_AIM -> config.axeModeProjectileAim && config.axeModeCrossbowAim;
				case AXE_SWORD_FOLLOWUP -> config.axeModeSwordFollowup;
				case AXE_TRIGGER_FOLLOWUP -> config.axeModeSwordFollowup && config.axeModeTriggerFollowup;
				default -> legacyEnabled(config, feature);
			};
			case MACE -> switch (feature) {
				case AUTO_MACE -> config.maceModeAutoMace;
				case MACE_AIM -> config.maceModeAutoMace && config.maceModeAim;
				case MACE_STUN_SLAM -> config.maceModeAutoMace && config.maceModeStunSlam;
				case BREACH_SWAP -> config.maceModeBreachSwap;
				default -> legacyEnabled(config, feature);
			};
		};
	}

	/** Whether Auto Mace may select a mace before attempting a hit. */
	public static boolean autoMaceAutoSwitch(ProFPSConfig config) {
		if (config == null || !config.enabled) return false;
		return switch (mode(config)) {
			case MACE -> config.maceModeAutoMace && config.maceModeAutoSwitch;
			default -> config.autoMace && config.autoMaceAutoSwitch;
		};
	}

	private static boolean legacyEnabled(ProFPSConfig c, CombatFeature feature) {
		return switch (feature) {
			case MELEE_AIM -> c.aimImprovements;
			case STRAFE -> c.strafeImprovements;
			case SWORD_AUTO_SPRINT -> false;
			case TRIGGER -> c.hitImprovements;
			case SWORD_AI -> c.swordAiEnabled;
			case SWORD_AI_AIM -> c.swordAiEnabled && c.swordAiAim;
			case SWORD_AI_JUMP -> c.swordAiEnabled && c.swordAiJump;
			case AXE_STUN -> c.axeStun;
			case AXE_CRIT -> c.axeCrit;
			case PROJECTILE_AIM, BOW_AIM, CROSSBOW_AIM, FIREBALL_AIM -> c.autoAim;
			case AUTO_MACE -> c.autoMace;
			case MACE_AIM -> c.autoMace;
			case MACE_STUN_SLAM -> c.autoMace && c.autoMaceShieldBreak;
			case BREACH_SWAP -> c.autoBreachSwap;
			// Pearl Catch has no setting; this is the single gate that keeps it disarmed.
			case PEARL_CATCH -> false;
			case AXE_SWORD_FOLLOWUP, AXE_TRIGGER_FOLLOWUP -> false;
		};
	}

	public static MeleeAim meleeAim(ProFPSConfig c) {
		if (c == null) return rawMeleeAim(new ProFPSConfig());
		int i = tier(c).index();
		return switch (mode(c)) {
			case SWORD -> new MeleeAim(true,
					at(i, 34, 40, 47, 54, 58, 62, 66, 70, 74, 78),
					at(i, 700, 780, 870, 950, 1040, 1120, 1210, 1300, 1400, 1500),
					at(i, 20, 23, 27, 32, 33, 34, 35, 36, 37, 38),
					at(i, 95, 85, 72, 60, 55, 50, 46, 42, 38, 35),
					at(i, 145, 130, 110, 95, 90, 85, 80, 76, 73, 70),
					at(i, 300, 330, 365, 400, 420, 440, 460, 480, 500, 520),
					at(i, 130, 118, 104, 90, 85, 80, 76, 72, 68, 65),
					at(i, .100, .090, .075, .065, .062, .060, .058, .056, .054, .052),
					at(i, .42, .38, .32, .28, .27, .26, .25, .24, .23, .22),
					at(i, .035, .040, .045, .050, .052, .054, .056, .058, .060, .060),
					at(i, .080, .090, .100, .110, .115, .118, .122, .125, .128, .130));
			case AXE -> new MeleeAim(true,
					at(i, 38, 46, 54, 58, 62, 66, 70, 74, 77, 80),
					at(i, 750, 850, 950, 1020, 1090, 1160, 1240, 1320, 1410, 1500),
					at(i, 24, 28, 32, 33, 34, 35, 36, 37, 38, 39),
					at(i, 82, 70, 60, 55, 50, 46, 42, 39, 37, 35),
					at(i, 125, 110, 95, 90, 85, 80, 76, 73, 70, 68),
					at(i, 330, 365, 400, 420, 440, 460, 480, 500, 520, 540),
					at(i, 112, 100, 90, 86, 82, 78, 74, 70, 67, 65),
					at(i, .085, .075, .065, .062, .060, .058, .056, .054, .052, .050),
					at(i, .36, .32, .28, .27, .26, .25, .24, .23, .22, .21),
					at(i, .040, .045, .050, .052, .054, .056, .058, .060, .060, .062),
					at(i, .090, .100, .110, .114, .118, .122, .125, .128, .130, .132));
			default -> rawMeleeAim(c);
		};
	}

	private static MeleeAim rawMeleeAim(ProFPSConfig c) {
		return new MeleeAim(c.aimRetargeting, clamp(c.aimAssistStrength, 15, 90),
				clamp(c.aimAssistDurationMs, 250, 2200), clamp(c.aimFovDeg, 10, 90),
				clamp(c.aimReactionMs, 0, 200), 95, 400, 90, .065D, .28D, .05D, .11D);
	}

	public static Strafe strafe(ProFPSConfig c) {
		if (c == null || mode(c) != CombatMode.SWORD) return rawStrafe(c == null ? new ProFPSConfig() : c);
		int i = tier(c, CombatMode.SWORD).index();
		return new Strafe(true, true,
				at(i, 28, 35, 43, 50, 55, 60, 64, 68, 72, 76),
				at(i, 260, 290, 325, 360, 380, 400, 420, 440, 460, 480),
				at(i, 20, 16, 12, 8, 7, 6, 5, 5, 4, 4),
				at(i, 650, 580, 500, 420, 400, 380, 360, 345, 330, 320),
				at(i, 28, 24, 18, 12, 12, 11, 10, 10, 9, 8),
				at(i, 115, 108, 100, 92, 88, 84, 81, 78, 75, 72),
				at(i, .025, .035, .045, .060, .060, .060, .065, .065, .070, .070),
				at(i, .45, .50, .58, .70, .70, .72, .72, .73, .74, .75),
				at(i, 220, 240, 260, 280, 280, 280, 280, 280, 280, 280));
	}

	private static Strafe rawStrafe(ProFPSConfig c) {
		return new Strafe(c.strafeRandomAngle, c.strafeBackstep,
				clamp(c.strafeStrength, 15, 90), clamp(c.strafeReachMs, 180, 720),
				clamp(c.strafeSkipPct, 0, 30), clamp(c.strafeIntervalMs, 150, 800),
				12, 92, .06D, c.strafeRandomAngle ? .70D : .30D, 280);
	}

	public static Trigger trigger(ProFPSConfig c) {
		if (c == null || mode(c) == CombatMode.OFF || mode(c) == CombatMode.MACE) {
			return rawTrigger(c == null ? new ProFPSConfig() : c);
		}
		int i = tier(c).index();
		int baseline = mode(c) == CombatMode.SWORD ? 3 : 2;
		int[] min = baseline == 3
				? new int[]{30, 24, 16, 8, 8, 7, 7, 6, 6, 5}
				: new int[]{24, 16, 8, 8, 7, 7, 6, 6, 5, 5};
		int[] max = baseline == 3
				? new int[]{95, 82, 68, 55, 52, 49, 46, 43, 40, 38}
				: new int[]{82, 68, 55, 52, 49, 46, 43, 40, 38, 36};
		int[] skip = baseline == 3
				? new int[]{7, 5, 3, 2, 2, 2, 2, 1, 1, 1}
				: new int[]{5, 3, 2, 2, 2, 2, 1, 1, 1, 1};
		int[] cooldown = baseline == 3
				? new int[]{97, 96, 95, 93, 93, 93, 94, 94, 95, 95}
				: new int[]{96, 95, 93, 93, 93, 94, 94, 95, 95, 95};
		int[] followup = baseline == 3
				? new int[]{85, 72, 60, 45, 43, 41, 39, 37, 35, 33}
				: new int[]{72, 60, 45, 43, 41, 39, 37, 35, 33, 32};
		int[] axeDelay = baseline == 3
				? new int[]{160, 145, 132, 120, 116, 112, 108, 104, 100, 96}
				: new int[]{145, 132, 120, 116, 112, 108, 104, 100, 96, 92};
		int settleMin = baseline == 3
				? at(i, 14, 12, 10, 8, 8, 8, 7, 7, 6, 6)
				: at(i, 12, 10, 8, 8, 8, 7, 7, 6, 6, 5);
		int settleMax = baseline == 3
				? at(i, 32, 30, 27, 24, 24, 23, 22, 21, 20, 20)
				: at(i, 30, 27, 24, 24, 23, 22, 21, 20, 20, 19);
		return new Trigger(true, true, true, true, min[i], max[i], skip[i], cooldown[i],
				followup[i], axeDelay[i], settleMin, settleMax, NATURAL_SWEEP_CHANCE);
	}

	private static Trigger rawTrigger(ProFPSConfig c) {
		return new Trigger(c.hitPatient, c.hitDisableWhileSneaking, c.hitSprintAwareCooldown,
				c.hitCritTiming, clamp(c.hitReactionMinMs, 0, 300), clamp(c.hitReactionMs, 5, 300),
				clamp(c.hitSkipChancePct, 0, 15), clamp(c.hitCooldownPct, 60, 100),
				clamp(c.hitFollowupMs, 20, 200), clamp(c.hitAxePostDelayMs, 0, 300),
				8, 24, NATURAL_SWEEP_CHANCE);
	}

	public static SwordAi swordAi(ProFPSConfig c) {
		int i = c != null && mode(c) == CombatMode.SWORD
				? tier(c, CombatMode.SWORD).index() : CombatTier.HT4.index();
		boolean aim = c == null || (mode(c) == CombatMode.SWORD ? c.swordModeAiAim : c.swordAiAim);
		boolean jump = c == null || (mode(c) == CombatMode.SWORD ? c.swordModeAiJump : c.swordAiJump);
		return new SwordAi(aim, jump,
				at(i, .82, .90, .98, 1.05, 1.07, 1.09, 1.11, 1.13, 1.15, 1.16),
				at(i, 7.5, 8.5, 9.2, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
				at(i, 11.0, 12.0, 13.0, 14.0, 14.0, 14.0, 14.0, 14.0, 14.0, 14.0),
				at(i, 22, 25, 28, 30, 30, 30, 30, 30, 30, 30),
				at(i, 20, 23, 26, 28, 28, 28, 28, 28, 28, 28),
				at(i, 2.55, 2.48, 2.40, 2.35, 2.34, 2.33, 2.32, 2.31, 2.30, 2.30),
				at(i, 3.55, 3.48, 3.40, 3.35, 3.34, 3.33, 3.32, 3.31, 3.30, 3.30),
				at(i, .28, .32, .35, .38, .39, .40, .41, .42, .43, .44),
				at(i, 820, 760, 700, 650, 640, 630, 620, 610, 600, 590),
				at(i, 1600, 1500, 1420, 1350, 1330, 1310, 1290, 1270, 1250, 1230),
				at(i, .10, .12, .14, .16, .16, .17, .17, .18, .18, .18),
				at(i, 1350, 1250, 1175, 1100, 1100, 1075, 1050, 1025, 1000, 1000),
				at(i, .035, .040, .045, .050, .052, .054, .056, .058, .060, .060),
				at(i, .045, .050, .055, .060, .060, .062, .064, .066, .068, .070),
				at(i, 1900, 1800, 1700, 1600, 1580, 1560, 1540, 1520, 1500, 1480));
	}

	public static Axe axe(ProFPSConfig c) {
		if (c == null || mode(c) != CombatMode.AXE) return rawAxe(c == null ? new ProFPSConfig() : c);
		int i = tier(c, CombatMode.AXE).index();
		return new Axe(
				at(i, 170, 135, 110, 96, 86, 78, 70, 64, 59, 55),
				at(i, 65, 58, 50, 48, 46, 44, 42, 40, 38, 35),
				at(i, 140, 110, 90, 82, 76, 72, 68, 64, 60, 58),
				at(i, 60, 50, 40, 38, 36, 34, 32, 30, 28, 26),
				at(i, 380, 330, 280, 275, 270, 265, 260, 255, 250, 245),
				at(i, 620, 560, 500, 490, 480, 470, 460, 450, 435, 420),
				at(i, 1150, 1050, 950, 920, 900, 880, 860, 840, 820, 800),
				at(i, 88, 84, 80, 76, 72, 70, 68, 66, 64, 62));
	}

	private static Axe rawAxe(ProFPSConfig c) {
		return new Axe(clamp(c.axeStunReactionMs, 0, 300), 50,
				clamp(c.axeStunSwitchBackMs, 30, 250), 40, 280, 500, 950, 72);
	}

	public static ProjectileAim projectileAim(ProFPSConfig c) {
		if (c == null || mode(c) != CombatMode.AXE) return rawProjectile(c == null ? new ProFPSConfig() : c);
		int i = tier(c, CombatMode.AXE).index();
		return new ProjectileAim(
				at(i, 30, 37, 45, 50, 55, 60, 65, 69, 73, 77),
				at(i, 45, 55, 70, 70, 68, 66, 64, 62, 60, 58),
				at(i, 420, 360, 300, 290, 280, 270, 260, 250, 245, 240),
				at(i, 720, 630, 550, 530, 515, 500, 485, 470, 460, 450),
				at(i, 300, 270, 240, 235, 230, 225, 220, 215, 210, 205),
				at(i, 500, 450, 400, 390, 380, 370, 360, 350, 340, 330),
				at(i, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6), 64.0D,
				at(i, .15, .13, .10, .095, .090, .085, .080, .075, .072, .070),
				at(i, .11, .09, .07, .068, .064, .060, .058, .055, .052, .050));
	}

	private static ProjectileAim rawProjectile(ProFPSConfig c) {
		return new ProjectileAim(clamp(c.autoAimStrength, 10, 90), clamp(c.autoAimFov, 20, 120),
				300, 550, 240, 400, 4, 64.0D, .10D, .07D);
	}

	public static Mace mace(ProFPSConfig c) {
		if (c == null || mode(c) != CombatMode.MACE) return rawMace(c == null ? new ProFPSConfig() : c);
		int i = tier(c, CombatMode.MACE).index();
		return new Mace(
				at(i, 28, 32, 36, 40, 45, 47, 49, 51, 53, 55),
				at(i, 4, 4, 5, 5, 6, 6, 6, 6, 6, 6),
				at(i, 30, 34, 38, 42, 45, 49, 53, 57, 61, 65),
				at(i, 110, 100, 90, 80, 70, 64, 58, 52, 46, 40),
				at(i, 58, 62, 66, 71, 75, 79, 82, 85, 88, 90),
				at(i, 100, 90, 80, 70, 60, 58, 56, 54, 52, 50),
				at(i, 340, 310, 280, 250, 220, 210, 200, 190, 185, 180),
				at(i, .30, .38, .46, .53, .60, .63, .66, .69, .72, .75),
				at(i, 96, 95, 94, 93, 92, 92, 92, 92, 92, 92),
				at(i, 84, 83, 82, 80, 78, 78, 78, 78, 78, 78),
				at(i, 1.75, 1.65, 1.55, 1.48, 1.40, 1.36, 1.32, 1.28, 1.24, 1.20),
				at(i, 1.15, 1.08, 1.02, .96, .90, .87, .84, .81, .78, .75));
	}

	private static Mace rawMace(ProFPSConfig c) {
		return new Mace(clamp(c.autoMaceFov, 20, 90), clamp(c.autoMaceRange, 3, 7),
				clamp(c.autoMaceTurnSpeed, 20, 90), clamp(c.autoMaceSettleMs, 0, 150),
				clamp(c.autoMaceSmashSpeed, 30, 95), clamp(c.autoMaceShieldBreakMs, 0, 200),
				220, .60D, 92, 78, 1.40D, .90D);
	}

	public static Breach breach(ProFPSConfig c) {
		if (c == null || mode(c) != CombatMode.MACE) return rawBreach(c == null ? new ProFPSConfig() : c);
		int i = tier(c, CombatMode.MACE).index();
		return new Breach(at(i, 97, 96, 94, 92, 90, 90, 90, 90, 90, 90),
				at(i, 280, 250, 230, 215, 200, 200, 195, 190, 185, 180), true);
	}

	private static Breach rawBreach(ProFPSConfig c) {
		return new Breach(clamp(c.autoBreachSwapCharge, 50, 100), 200, true);
	}

	public static PearlCatch pearlCatch(ProFPSConfig c) {
		if (c == null || mode(c) != CombatMode.MACE) return rawPearl(c == null ? new ProFPSConfig() : c);
		int i = tier(c, CombatMode.MACE).index();
		return new PearlCatch(
				at(i, 80, 65, 50, 35, 0, 0, 0, 0, 0, 0),
				0,
				at(i, 58, 62, 66, 70, 80, 82, 84, 86, 88, 90),
				at(i, 80, 72, 65, 58, 52, 48, 45, 43, 41, 40),
				at(i, 145, 132, 120, 108, 98, 92, 87, 83, 79, 75),
				at(i, 36, 42, 48, 54, 60, 70, 80, 90, 100, 110),
				at(i, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8),
				at(i, 130, 120, 110, 100, 95, 90, 85, 80, 76, 72),
				at(i, .10, .09, .08, .07, .06, .05, .045, .04, .035, .03));
	}

	private static PearlCatch rawPearl(ProFPSConfig c) {
		return new PearlCatch(clamp(c.pearlCatchDelayMs, 0, 1000), clamp(c.pearlCatchAngle, -45, 45),
				clamp(c.pearlCatchAimSpeed, 10, 95), 52, 98, 60, 5, 95, .06D);
	}

	private static int at(int index, int... values) {
		return values[Math.max(0, Math.min(values.length - 1, index))];
	}

	private static double at(int index, double... values) {
		return values[Math.max(0, Math.min(values.length - 1, index))];
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
