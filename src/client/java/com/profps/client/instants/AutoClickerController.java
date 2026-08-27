package com.profps.client.instants;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.Hand;

import java.security.SecureRandom;

/**
 * Drives clicks through vanilla's attack and use handlers at the pre-movement input phase.
 * A blocked click is discarded rather than credited, so intervals never accumulate.
 */
public final class AutoClickerController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private boolean streamActive;
	private boolean previousPhysicalHeld;
	private int nextClickAge;
	private int nextPaceChangeAge;
	private int consecutiveFastIntervals;
	private double paceScale = 1.0;
	private double targetPaceScale = 1.0;
	private long blockHoverSinceNanos;

	public AutoClickerController(ProFPSConfig config) {
		this.config = config;
	}

	/**
	 * Called from {@code MinecraftClient.handleInputEvents()} TAIL, before movement packets
	 * are sent. Must not also be called from an end-of-tick event.
	 */
	public void tickPreMovement(MinecraftClient client) {
		if (!canRun(client)) {
			reset();
			return;
		}

		boolean rightClick = config.instantClickRight;
		boolean physicalHeld = rightClick
				? client.options.useKey.isPressed()
				: client.options.attackKey.isPressed();
		if (config.instantClickHoldToClick && !physicalHeld) {
			reset();
			return;
		}
		if (config.instantClickLimitItems && !config.instantClickAllowedItems.contains(
				Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString())) {
			reset();
			return;
		}

		int age = client.player.age;
		updatePace(age);

		// A physical rising edge consumes this beat: vanilla already handled that click
		// earlier in the same dispatch, so clicking again would double-act.
		if (!streamActive || (physicalHeld && !previousPhysicalHeld)) {
			streamActive = true;
			previousPhysicalHeld = physicalHeld;
			scheduleNext(age, rightClick);
			return;
		}
		previousPhysicalHeld = physicalHeld;

		if (age < nextClickAge) return;

		// Advance first so a rejected click still consumes its beat instead of catching up.
		scheduleNext(age, rightClick);

		MinecraftClientInvoker minecraft = (MinecraftClientInvoker) client;
		Entity target = null;
		if (rightClick) {
			if (client.player.isUsingItem()
					|| client.interactionManager.isBreakingBlock()
					|| minecraft.profps$getItemUseCooldown() > 0) return;
		} else {
			if (client.player.isUsingItem()
					|| client.interactionManager.isBreakingBlock()) return;
			// The type check is required: a miss is also a BlockHitResult, built by
			// BlockHitResult.createMissed, so the class alone does not distinguish air.
			if (client.crosshairTarget instanceof BlockHitResult blockHit
					&& blockHit.getType() == HitResult.Type.BLOCK) {
				long now = System.nanoTime();
				if (blockHoverSinceNanos == 0L) blockHoverSinceNanos = now;
				if (!config.instantClickBreakBlocks
						|| (now - blockHoverSinceNanos) / 1_000_000L >= config.instantClickBreakBlocksDelayMs) return;
			} else {
				blockHoverSinceNanos = 0L;
			}
			target = freshEntityTarget(client);
			if (config.instantClickTargetOnly && target == null) return;
		}

		// One action owner per pre-movement dispatch.
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CLICKER)) return;

		if (rightClick) {
			minecraft.invokeDoItemUse();
		} else if (target != null) {
			applyJitter(client);
			// attackEntity rather than doAttack: doAttack silently drops clicks below its
			// local charge threshold. attackEntity already resets the charge clock.
			client.interactionManager.attackEntity(client.player, target);
			client.player.swingHand(Hand.MAIN_HAND);
		} else {
			// Swing only. An air click has no server-side target, and the server's charge
			// clock keeps running, so the local counter is deliberately not reset here.
			client.player.swingHand(Hand.MAIN_HAND);
		}
	}

	/** Clears timing state while a higher-priority multi-tick action owns input. */
	public void suspend() {
		reset();
	}

	private boolean canRun(MinecraftClient client) {
		return config.enabled
				&& config.instantAutoClicker
				&& client.player != null
				&& client.world != null
				&& client.interactionManager != null
				&& client.currentScreen == null
				&& client.getOverlay() == null
				&& client.isWindowFocused()
				&& client.player.isAlive()
				&& !client.player.isSpectator()
				&& !client.player.isRiding();
	}

	private Entity freshEntityTarget(MinecraftClient client) {
		Entity camera = client.getCameraEntity();
		HitResult fresh = client.player.getCrosshairTarget(
				1.0F,
				camera == null ? client.player : camera
		);
		if (!(fresh instanceof EntityHitResult freshHit)) return null;
		Entity target = freshHit.getEntity();
		if (target == client.player || !target.isAlive() || target.isRemoved()) return null;
		return target;
	}

	private void updatePace(int age) {
		if (nextPaceChangeAge == 0 || age >= nextPaceChangeAge) {
			double spread = switch (Math.max(0, Math.min(2, config.instantClickRandomization))) {
				case 0 -> 0.06D;
				case 2 -> 0.30D;
				default -> 0.19D;
			};
			targetPaceScale = 1.0D - spread * 0.5D + rng.nextDouble() * spread;
			nextPaceChangeAge = age + 14 + rng.nextInt(31);
		}
		paceScale += (targetPaceScale - paceScale) * 0.12;
	}

	private void scheduleNext(int age, boolean rightClick) {
		double centeredTimingSample = (rng.nextDouble() + rng.nextDouble()) * 0.5;
		boolean hesitation = rng.nextDouble() < 0.04;
		int minCps = Math.max(1, Math.min(config.instantClickMinCps, config.instantClickCps));
		int maxCps = Math.max(minCps, Math.min(20, config.instantClickCps));
		int sampledCps = minCps + (maxCps == minCps ? 0 : rng.nextInt(maxCps - minCps + 1));
		double hesitationChance = switch (Math.max(0, Math.min(2, config.instantClickRandomization))) {
			case 0 -> 0.015D;
			case 2 -> 0.075D;
			default -> 0.04D;
		};
		int interval = AutoClickRhythm.intervalTicks(
				sampledCps,
				rightClick,
				paceScale,
				centeredTimingSample,
				rng.nextDouble(),
				hesitation || rng.nextDouble() < hesitationChance,
				consecutiveFastIntervals
		);
		consecutiveFastIntervals = interval == 1 ? consecutiveFastIntervals + 1 : 0;
		nextClickAge = age + interval;
	}

	private void applyJitter(MinecraftClient client) {
		if (!config.instantClickJitter) return;
		float yaw = (float) ((rng.nextDouble() - 0.5D) * 0.9D);
		float pitch = (float) ((rng.nextDouble() - 0.5D) * 0.55D);
		client.player.setYaw(client.player.getYaw() + com.profps.client.aim.MouseGcd.quantize(yaw));
		client.player.setPitch(Math.max(-90.0F, Math.min(90.0F,
				client.player.getPitch() + com.profps.client.aim.MouseGcd.quantize(pitch))));
	}

	private void reset() {
		streamActive = false;
		previousPhysicalHeld = false;
		nextClickAge = 0;
		nextPaceChangeAge = 0;
		consecutiveFastIntervals = 0;
		paceScale = 1.0;
		targetPaceScale = 1.0;
		blockHoverSinceNanos = 0L;
	}
}
