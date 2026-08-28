package com.profps.client.instants;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Drives block breaking toward a target tick count instead of scaling the vanilla rate.
 *
 * <p>Two things gate mining speed, and the old multiplier only touched one of them. The
 * per-tick delta decides how long a single block takes, but vanilla also sets a 5-tick
 * {@code blockBreakingCooldown} after every break, capping continuous mining at roughly three
 * blocks a second no matter how fast an individual block falls. Both are handled here.
 *
 * <p>Targeting a tick count rather than multiplying normalizes across hardness: a multiplier
 * leaves obsidian slow and makes stone instant, whereas a target makes every block take about
 * the same time. The result is never slower than vanilla, so soft blocks are not penalized.
 *
 * <p>Falling blocks are deliberately left at vanilla speed. Gravel, sand, concrete powder,
 * anvils and the dragon egg all spawn falling-block entities on break, and tearing through a
 * column faster than the server spawns them desyncs the column.
 */
public final class InstamineController {
	private static final Random RNG = new Random();
	/** Vanilla's post-break gap, for reference; level 1 stays close to it. */
	static final int VANILLA_COOLDOWN_TICKS = 5;
	/** Ticks a block should take at level 1 and at level 10. */
	private static final float SLOWEST_TARGET_TICKS = 8.0F;
	private static final float FASTEST_TARGET_TICKS = 1.0F;

	private InstamineController() {}

	/**
	 * Whether the boost applies to this breaker. Matches on UUID so the integrated-server copy
	 * of the local player is covered too, which is what keeps singleplayer client and server
	 * progress in agreement.
	 */
	public static boolean affects(PlayerEntity breaker) {
		ProFPSConfig config = ProFPSClient.config();
		if (config == null || !config.enabled || !config.instamineEnabled || breaker == null) return false;
		PlayerEntity self = MinecraftClient.getInstance().player;
		return self != null && (breaker == self || breaker.getUuid().equals(self.getUuid()));
	}

	/** Entry point for the mixin; reads the level and rolls the jitter. */
	public static float boost(float vanillaDelta, Block block) {
		ProFPSConfig config = ProFPSClient.config();
		int level = config == null ? 8 : config.instamineLevel;
		float jitter = 1.0F + (RNG.nextFloat() - 0.5F) * 0.12F;
		return breakDelta(vanillaDelta, level, block instanceof FallingBlock, jitter);
	}

	/**
	 * Clamps the post-break cooldown so continuous mining is not held to vanilla's three
	 * blocks a second. Run once per client tick, after mining has been handled.
	 */
	public static void tick(MinecraftClient client) {
		ProFPSConfig config = ProFPSClient.config();
		if (config == null || !config.enabled || !config.instamineEnabled) return;
		if (client == null || client.interactionManager == null) return;
		ClientPlayerInteractionManagerAccessor accessor =
				(ClientPlayerInteractionManagerAccessor) client.interactionManager;
		int allowed = cooldownTicks(config.instamineLevel);
		if (accessor.profps$getBlockBreakingCooldown() > allowed) {
			accessor.profps$setBlockBreakingCooldown(allowed);
		}
	}

	// ── Pure tier maths ───────────────────────────────────────────────────────

	/** Ticks one block should take at this level: 8 at level 1, 1 at level 10. */
	static float targetTicks(int level) {
		int clamped = MathHelper.clamp(level, 1, 10);
		return SLOWEST_TARGET_TICKS
				- (clamped - 1) * ((SLOWEST_TARGET_TICKS - FASTEST_TARGET_TICKS) / 9.0F);
	}

	/** Post-break gap: 4 ticks at level 1, none at level 10. */
	static int cooldownTicks(int level) {
		int clamped = MathHelper.clamp(level, 1, 10);
		return Math.round(4.0F - (clamped - 1) * (4.0F / 9.0F));
	}

	/**
	 * The per-tick breaking fraction. A falling block is returned untouched, and nothing is
	 * ever made slower than vanilla.
	 *
	 * <p>Only level 10 reaches a delta of 1.0, which is the one case vanilla short-circuits
	 * into a true single-tick break. Every level below it takes at least two ticks.
	 */
	static float breakDelta(float vanillaDelta, int level, boolean fallingBlock, float jitter) {
		if (fallingBlock) return vanillaDelta;
		float target = jitter / targetTicks(level);
		return Math.max(vanillaDelta, target);
	}
}
