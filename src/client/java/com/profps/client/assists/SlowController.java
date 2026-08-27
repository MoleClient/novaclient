package com.profps.client.assists;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

/** Client-side cosmetic slowdown of the local player's held-item swing animation. */
public final class SlowController {
	private SlowController() {}

	/** True only for the local player while the module and the master switch are on. */
	public static boolean affects(LivingEntity entity) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (entity != client.player) return false;
		ProFPSConfig config = ProFPSClient.config();
		return config != null && config.enabled && config.slowAnimations;
	}

	/** Stretch a base swing duration (in ticks) by the configured factor. */
	public static int scaleDuration(int base) {
		ProFPSConfig config = ProFPSClient.config();
		int factor = config == null ? 4 : MathHelper.clamp(config.slowAnimationStrength, 2, 8);
		return Math.max(base, base * factor);
	}
}
