package com.profps.client.instants;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/** Scales the per-tick block-breaking progress above vanilla. */
public final class FastbreakController {
	private static final Random RNG = new Random();

	private FastbreakController() {}

	/**
	 * Whether the boost applies to this breaker. Matches on UUID so the integrated-server
	 * copy of the local player is covered too.
	 */
	public static boolean affects(PlayerEntity breaker) {
		ProFPSConfig config = ProFPSClient.config();
		if (config == null || !config.enabled || !config.instantFastBreak || breaker == null) return false;
		PlayerEntity self = MinecraftClient.getInstance().player;
		return self != null && (breaker == self || breaker.getUuid().equals(self.getUuid()));
	}

	/** Scales the vanilla per-tick break delta by a jittered factor. */
	public static float boost(float vanillaDelta) {
		ProFPSConfig config = ProFPSClient.config();
		int level = config == null ? 4 : MathHelper.clamp(config.instantFastBreakLevel, 1, 10);
		float factor = 1.15F + level * 0.14F;                  // ~1.3x at level 1, ~2.55x at level 10
		float jitter = 1.0F + (RNG.nextFloat() - 0.5F) * 0.12F; // +/-6% per sample
		return vanillaDelta * factor * jitter;
	}
}
