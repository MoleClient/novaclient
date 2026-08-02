package com.profps.client.assists;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

/**
 * "Slow" module — purely cosmetic, client-side held-item animation slowdown.
 *
 * <p>The held item's swing (mining with a pickaxe, attacking with a sword,
 * placing a block) is driven by {@code LivingEntity.getHandSwingDuration()}.
 * Inflating that duration <i>only for the local player</i> stretches the swing
 * animation out so it looks far slower, while leaving the actual attack /
 * block-break / use packets — which the interaction manager sends on their own
 * cadence — completely untouched. The server still registers hits and mining at
 * full speed; this is strictly a look on the player's own screen.
 *
 * <p>Static accessors so the {@code LivingEntity} swing mixin can consult it
 * without holding an instance.
 */
public final class SlowController {
	private SlowController() {}

	/** True only for the local player while the module (and the master switch) is on. */
	public static boolean affects(LivingEntity entity) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (entity != client.player) return false; // never touch other players' arms
		ProFPSConfig config = ProFPSClient.config();
		return config != null && config.enabled && config.slowAnimations;
	}

	/** Stretch a base swing duration (in ticks) by the configured factor. */
	public static int scaleDuration(int base) {
		ProFPSConfig config = ProFPSClient.config();
		int factor = config == null ? 4 : MathHelper.clamp(config.slowAnimationStrength, 2, 8);
		// Never return less than the real duration — this only ever slows down.
		return Math.max(base, base * factor);
	}
}
