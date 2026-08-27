package com.profps.client.combatmode;

/** Immutable effective tuning records returned by {@link CombatModePolicy}. */
public final class CombatModeProfile {
	private CombatModeProfile() {}

	public record MeleeAim(
			boolean retargeting,
			int strengthPct,
			int durationMs,
			int fovDeg,
			int reactionMs,
			int retargetIntervalMs,
			int lookHoldMs,
			int engagementRampMs,
			double missChance,
			double hitchChancePerSecond,
			double leadMinSeconds,
			double leadMaxSeconds) {}

	public record Strafe(
			boolean randomAngle,
			boolean backwardStep,
			int strengthPct,
			int reachMs,
			int skipChancePct,
			int intervalMs,
			int reactionMinMs,
			int reactionMaxMs,
			double doubleTapChance,
			double pivotChance,
			int maxBurstMs) {}

	public record Trigger(
			boolean patient,
			boolean disableWhileSneaking,
			boolean sprintAwareCooldown,
			boolean critTiming,
			int reactionMinMs,
			int reactionMaxMs,
			int skipChancePct,
			int cooldownPct,
			int followupMs,
			int axePostDelayMs,
			int settleMinMs,
			int settleMaxMs,
			double naturalSweepChance) {}

	public record SwordAi(
			boolean aim,
			boolean jump,
			double aimSpeedScale,
			double acquireDistance,
			double holdDistance,
			double acquireYawDeg,
			double acquirePitchDeg,
			double tooCloseDistance,
			double tooFarDistance,
			double strafeWeight,
			int strafeFlipMinMs,
			int strafeFlipMaxMs,
			double critChance,
			int critCooldownMs,
			double catchupChanceBase,
			double catchupChanceScale,
			int catchupCooldownMs) {}

	public record Axe(
			int stunReactionMs,
			int stunReactionJitterMs,
			int switchToSwordMs,
			int switchJitterMs,
			int restMinMs,
			int restMaxMs,
			int followupWindowMs,
			int minimumAttackChargePct) {}

	public record ProjectileAim(
			int strengthPct,
			int fovDeg,
			int pointHoldMinMs,
			int pointHoldMaxMs,
			int recoveryMinMs,
			int recoveryMaxMs,
			int predictionIterations,
			double maxTrackDistance,
			double aimTremorYaw,
			double aimTremorPitch) {}

	public record Mace(
			int fovDeg,
			int trackingRange,
			int turnSpeedPct,
			int groundSettleMs,
			int smashSpeedPct,
			int stunGapMs,
			int retargetMs,
			double targetStickiness,
			int groundChargePct,
			int smashChargePct,
			double yawBiasStdDev,
			double pitchBiasStdDev) {}

	public record Breach(
			int chargePct,
			int disengageMs,
			boolean requireCachedAndFreshCrosshair) {}

	public record PearlCatch(
			int delayMs,
			int angleDeg,
			int aimSpeedPct,
			int reactionMinMs,
			int reactionMaxMs,
			int simulationTicks,
			int solveSubsteps,
			int reacquireMs,
			double alternateSolutionChance) {}
}
