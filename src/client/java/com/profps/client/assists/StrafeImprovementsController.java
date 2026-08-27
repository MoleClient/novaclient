package com.profps.client.assists;

import com.profps.client.aim.MouseGcd;
import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatMode;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Box;

import java.security.SecureRandom;

/**
 * Side-steps after a landed hit by publishing a {@link PlayerInput} burst that
 * {@code InputMixin} applies at the tail of the keyboard tick. An optional
 * GCD-quantized yaw pivot turns the head with the body.
 */
public final class StrafeImprovementsController {
	private static StrafeImprovementsController instance;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	/** Remainder carried between frames so emitted yaw deltas stay on the mouse GCD. */
	private float yawCarry;

	// Two burst windows: the primary step and an optional opposite follow-up.
	private long b1Start, b1End, b2Start, b2End;
	private int  dir1, dir2;          // -1 = step left, +1 = step right
	private boolean back1, back2;     // include a backward component in the step
	private int lastDir = 1;          // consecutive steps tend to alternate sides
	private long lastJukeNanos;       // throttle between steps
	private long sprintForwardSinceNanos;
	private long sprintReadyNanos;
	private long sprintPauseUntilNanos;

	private double yawOffsetDeg;

	// Pivot state, scheduled independently of the burst and applied per frame.
	private float pivotTotalDeg;     // signed total angle: +right, -left
	private float pivotAppliedDeg;   // cumulative amount applied so far
	private long  pivotStartNanos;
	private long  pivotDurationNanos;

	public StrafeImprovementsController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/** True while a step should be overriding the player's movement input. */
	public static boolean isStrafing() {
		StrafeImprovementsController s = instance;
		return s != null && s.allowedForMovement() && s.burstActiveNow();
	}

	/**
	 * The movement input to apply during a step: forward dropped, side-step and optional
	 * back added, jump and sneak preserved.
	 *
	 * @return null when no burst is live, so the real input passes through
	 */
	public static PlayerInput strafeOverride(PlayerInput current) {
		StrafeImprovementsController s = instance;
		if (s == null || !s.allowedForMovement() || !s.burstActiveNow()) return null;
		// A physical jump or sneak is higher-priority manual intent.
		if (current.jump() || current.sneak()) return null;
		MinecraftClient client = MinecraftClient.getInstance();
		int dir = s.activeDir();
		boolean back = s.activeBack();
		if (!s.safeInputStep(client, dir, back)) {
			// Without backward clearance, try the lateral step alone, else yield to the player.
			back = false;
			if (dir == 0 || !s.safeInputStep(client, dir, false)) return null;
		}
		return new PlayerInput(false, back, dir < 0, dir > 0, current.jump(), current.sneak(), false);
	}

	/**
	 * Sword-mode sprint layer. Adds only the sprint bit after forward has been held
	 * for a short tiered delay; it never supplies forward movement itself.
	 */
	public static PlayerInput swordSprintOverride(PlayerInput current) {
		StrafeImprovementsController s = instance;
		if (s == null || current == null || !s.allowedForSwordSprint()) return null;
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		long now = System.nanoTime();

		if (!current.forward() || current.backward() || current.sneak()
				|| client.options.backKey.isPressed() || s.burstActiveNow()) {
			s.resetSprintCadence();
			return null;
		}
		if (!s.canUseSwordSprint(client, player, current)) {
			s.resetSprintCadence();
			return null;
		}

		if (s.sprintForwardSinceNanos == 0L) {
			s.sprintForwardSinceNanos = now;
			int tier = CombatModePolicy.tier(s.config, CombatMode.SWORD).index();
			double minMs = Math.max(16.0D, 30.0D - tier);
			double maxMs = Math.max(minMs + 10.0D, 58.0D - tier * 2.0D);
			s.sprintReadyNanos = now + s.ns(minMs + s.rng.nextDouble() * (maxMs - minMs));
		}
		if (current.sprint() || now < s.sprintReadyNanos || now < s.sprintPauseUntilNanos) return null;
		return new PlayerInput(true, false, current.left(), current.right(), current.jump(), false, true);
	}

	private boolean burstActiveNow() {
		long n = System.nanoTime();
		return (n >= b1Start && n < b1End) || (n >= b2Start && n < b2End);
	}

	private int activeDir() {
		long n = System.nanoTime();
		if (n >= b1Start && n < b1End) return dir1;
		if (n >= b2Start && n < b2End) return dir2;
		return 0;
	}

	private boolean activeBack() {
		long n = System.nanoTime();
		if (n >= b1Start && n < b1End) return back1;
		if (n >= b2Start && n < b2End) return back2;
		return false;
	}

	private boolean allowedForMovement() {
		if (!CombatModePolicy.enabled(config, CombatFeature.STRAFE)) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null || mc.currentScreen != null) return false;
		if (CombatModePolicy.mode(config) == CombatMode.SWORD
				&& !player.getMainHandStack().isIn(ItemTags.SWORDS)) return false;
		if (!player.isAlive() || player.isSpectator() || player.hasVehicle()) return false;
		return true;
	}

	/** Advances the pivot rotation; called from WorldRenderEvents.END_MAIN each frame. */
	public void frame(MinecraftClient client) {
		if (!CombatModePolicy.enabled(config, CombatFeature.STRAFE)) return;
		if (client == null || client.player == null) return;
		if (pivotTotalDeg == 0F || pivotStartNanos == 0L) return;

		long now = System.nanoTime();
		if (now < pivotStartNanos) return;

		long elapsed = now - pivotStartNanos;
		double t = Math.min(1.0D, elapsed / (double) pivotDurationNanos);

		// Sine ease-in-out.
		double eased = 0.5D - 0.5D * Math.cos(Math.PI * t);
		float targetApplied = (float) (pivotTotalDeg * eased);
		float step = targetApplied - pivotAppliedDeg;

		step += (float) (rng.nextGaussian() * 0.18D);

		// Track intended progress so the ease curve completes; emit only quantized deltas.
		pivotAppliedDeg += step;
		float wanted = step + yawCarry;
		float applied = quantize(wanted);
		yawCarry = wanted - applied;
		if (applied != 0.0F) {
			ClientPlayerEntity player = client.player;
			player.setYaw(player.getYaw() + applied);
			player.headYaw = player.getYaw();
		}

		if (t >= 1.0D) {
			clearPivot();
		}
	}

	private float quantize(float delta) {
		return MouseGcd.quantize(delta);
	}

	public void tick(MinecraftClient client) {
		if (!isAllowed(client)) {
			cancel();
		}
		if (!allowedForSwordSprint()) resetSprintCadence();
	}

	/** Schedules a strafe burst and optional pivot after a landed hit on a player. */
	public void markAttack(MinecraftClient client, Entity entity) {
		if (!isAllowed(client) || !(entity instanceof PlayerEntity) || entity == client.player) {
			return;
		}
		CombatModeProfile.Strafe tuning = tuning();
		ClientPlayerEntity player = client.player;
		if (isCrit(player)) return;
		if (rng.nextDouble() < tuning.skipChancePct() / 100.0D) return;

		long now = System.nanoTime();
		// Throttle repositions so rapid hits do not each trigger a step.
		if (now - lastJukeNanos < tuning.intervalMs() * 1_000_000L) return;
		lastJukeNanos = now;
		double strength = tuning.strengthPct() / 100.0D;

		double reactionMs = tuning.reactionMinMs()
				+ rng.nextDouble() * Math.max(0, tuning.reactionMaxMs() - tuning.reactionMinMs())
				+ rng.nextGaussian() * 8D;
		reactionMs = Math.max(tuning.reactionMinMs(), reactionMs);
		boolean swordMode = CombatModePolicy.mode(config) == CombatMode.SWORD;
		if (swordMode) {
			// The post-hit tap must land inside the trade window.
			reactionMs = Math.min(reactionMs, 14D + Math.abs(rng.nextGaussian()) * 13D);
		}
		if (tuning.randomAngle()) {
			yawOffsetDeg = (rng.nextDouble() - 0.5D) * 24.0D + rng.nextGaussian() * 4.0D;
		} else {
			yawOffsetDeg = rng.nextGaussian() * 4.0D;
		}

		// Step length scales with reach and strength; direction tends to alternate.
		int dir = (rng.nextDouble() < 0.72D) ? -lastDir : (rng.nextBoolean() ? 1 : -1);
		if (dir == 0) dir = rng.nextBoolean() ? 1 : -1;
		lastDir = dir;

		double durMs = tuning.reachMs() * (0.22D + rng.nextDouble() * 0.26D) * (0.85D + 0.4D * strength);
		durMs = Math.max(80D, Math.min(tuning.maxBurstMs(), durMs));

		// Vanilla drops sprint on its own when the backward input arrives.
		boolean canBack = tuning.backwardStep();

		long start = now + ns(reactionMs);
		b1Start = start;
		b1End   = start + ns(durMs);
		dir1    = swordMode && rng.nextDouble() < 0.68D ? 0 : dir;
		back1   = canBack;

		if (rng.nextDouble() < tuning.doubleTapChance()) {
			// Opposite-side counter-step a beat later.
			long s2 = b1End + ns(60D + rng.nextDouble() * 170D);
			double d2 = durMs * (0.55D + rng.nextDouble() * 0.45D);
			b2Start = s2;
			b2End   = s2 + ns(d2);
			dir2    = -dir;
			back2   = !swordMode && canBack;
		} else {
			b2Start = 0L;
			b2End   = 0L;
		}
		long movementEnd = Math.max(b1End, b2End);
		double resumeMs = 32D + rng.nextDouble() * 38D;
		sprintPauseUntilNanos = movementEnd + ns(resumeMs);
		resetSprintCadence();

		// Suppressed entirely when melee aim is on, so only one module owns the yaw.
		double pivotOdds = CombatModePolicy.enabled(config, CombatFeature.MELEE_AIM)
				? 0.0D : tuning.pivotChance();
		if (dir1 != 0 && rng.nextDouble() < pivotOdds) {
			double direction = rng.nextDouble() < 0.75D ? dir : -dir;

			double roll = rng.nextDouble();
			double angleDeg;
			if (roll < 0.15D) {
				angleDeg = 4D + rng.nextDouble() * 6D;
			} else if (roll < 0.75D) {
				angleDeg = 10D + rng.nextDouble() * 12D;
			} else {
				angleDeg = 18D + rng.nextDouble() * 12D;
			}

			pivotTotalDeg    = (float) (angleDeg * direction);
			pivotAppliedDeg  = 0F;
			// Pivot starts slightly after the step so the body leads.
			double pivotDelayMs = reactionMs * 0.55D + rng.nextDouble() * 55D;
			pivotStartNanos    = now + ns(pivotDelayMs);
			pivotDurationNanos = ns(130D + rng.nextDouble() * 200D
					+ (angleDeg > 18D ? rng.nextDouble() * 70D : 0D));
		} else {
			clearPivot();
		}
	}

	public String status(MinecraftClient client) {
		if (!CombatModePolicy.enabled(config, CombatFeature.STRAFE)) return "Off";
		if (client == null || client.player == null) return "Idle";
		if (burstActiveNow())    return "Strafing";
		if (pivotTotalDeg != 0F) return "Pivoting";
		return "Idle";
	}

	private boolean isAllowed(MinecraftClient client) {
		return CombatModePolicy.enabled(config, CombatFeature.STRAFE)
				&& client != null
				&& client.player != null
				&& client.world != null
				&& (CombatModePolicy.mode(config) != CombatMode.SWORD
						|| client.player.getMainHandStack().isIn(ItemTags.SWORDS));
	}

	private boolean allowedForSwordSprint() {
		if (!CombatModePolicy.enabled(config, CombatFeature.SWORD_AUTO_SPRINT)) return false;
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		return player != null && client.world != null && client.currentScreen == null
				&& player.isAlive() && !player.isSpectator() && !player.hasVehicle()
				&& player.getMainHandStack().isIn(ItemTags.SWORDS);
	}

	private boolean canUseSwordSprint(MinecraftClient client, ClientPlayerEntity player,
			PlayerInput input) {
		if (player.isSneaking() || player.isUsingItem() || player.isClimbing()
				|| player.isTouchingWater() || player.isInLava() || player.isGliding()
				|| player.getAbilities().flying || player.horizontalCollision
				|| !player.getHungerManager().canSprint()) return false;
		// Keep an existing sprint through a jump, but never start one mid-air; that breaks crits.
		if (!player.isOnGround() && !player.isSprinting()) return false;
		return !player.isOnGround() || safeInputStep(client,
				input.left() == input.right() ? 0 : input.left() ? -1 : 1, false, true);
	}

	private boolean safeInputStep(MinecraftClient client, int dir, boolean back) {
		return safeInputStep(client, dir, back, false);
	}

	private boolean safeInputStep(MinecraftClient client, int dir, boolean back, boolean forward) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || client.world == null || !player.isOnGround()
				|| player.isClimbing() || player.isTouchingWater() || player.isInLava()
				|| player.hasVehicle() || player.isGliding() || player.getAbilities().flying) return false;
		double f = forward == back ? 0.0D : forward ? 1.0D : -1.0D;
		double side = dir == 0 ? 0.0D : dir < 0 ? 1.0D : -1.0D;
		double magnitude = Math.sqrt(f * f + side * side);
		if (magnitude < 1.0E-6D) return false;
		f /= magnitude;
		side /= magnitude;
		double yaw = Math.toRadians(player.getYaw());
		double stepX = (side * Math.cos(yaw) - f * Math.sin(yaw)) * 0.36D;
		double stepZ = (f * Math.cos(yaw) + side * Math.sin(yaw)) * 0.36D;
		Box next = player.getBoundingBox().offset(stepX, 0.0D, stepZ);
		if (!client.world.isSpaceEmpty(player, next)) return false;
		double centerX = (next.minX + next.maxX) * 0.5D;
		double centerZ = (next.minZ + next.maxZ) * 0.5D;
		Box support = new Box(centerX - 0.18D, next.minY - 0.18D, centerZ - 0.18D,
				centerX + 0.18D, next.minY - 0.02D, centerZ + 0.18D);
		return !client.world.isSpaceEmpty(player, support);
	}

	private boolean isCrit(ClientPlayerEntity player) {
		return player.fallDistance > 0.0F
				&& !player.isOnGround()
				&& !player.isClimbing()
				&& !player.isTouchingWater()
				&& !player.isSprinting()
				&& !player.hasVehicle();
	}

	private void clearPivot() {
		pivotTotalDeg     = 0F;
		pivotAppliedDeg   = 0F;
		pivotStartNanos   = 0L;
		pivotDurationNanos = 0L;
	}

	private void cancel() {
		b1Start = b1End = 0L;
		b2Start = b2End = 0L;
		clearPivot();
	}

	private void resetSprintCadence() {
		sprintForwardSinceNanos = 0L;
		sprintReadyNanos = 0L;
	}

	private long ns(double ms) {
		return (long) (ms * 1_000_000D);
	}

	private CombatModeProfile.Strafe tuning() {
		return CombatModePolicy.strafe(config);
	}
}
