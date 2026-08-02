package com.profps.client.instants;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Fastbreak — speeds up the per-tick block-breaking progress so blocks break
 * noticeably faster than vanilla.
 *
 * <p>In singleplayer the client owns break timing, so the speed-up is dramatic.
 * On a server the break time is re-validated, so this stays deliberately
 * RESTRAINED and humanized: the multiplier is modest and every per-tick sample
 * is jittered, so the resulting break time is never a clean constant ratio of
 * vanilla (which is exactly the fingerprint anti-cheats pattern-match on). It's
 * "much quicker than vanilla but not impossibly quick". Higher Speed levels push
 * the ratio up and are correspondingly riskier on strict anti-cheats.
 */
public final class FastbreakController {
	private static final Random RNG = new Random();

	private FastbreakController() {}

	/**
	 * Only YOUR own breaking is boosted — the client copy of you, or the
	 * integrated-server copy (same UUID) in singleplayer so the server's break
	 * validation is sped up to match. Never another player.
	 */
	public static boolean affects(PlayerEntity breaker) {
		ProFPSConfig config = ProFPSClient.config();
		if (config == null || !config.enabled || !config.instantFastBreak || breaker == null) return false;
		PlayerEntity self = MinecraftClient.getInstance().player;
		return self != null && (breaker == self || breaker.getUuid().equals(self.getUuid()));
	}

	/** Scale the vanilla per-tick break delta by a modest, jittered factor. */
	public static float boost(float vanillaDelta) {
		ProFPSConfig config = ProFPSClient.config();
		int level = config == null ? 4 : MathHelper.clamp(config.instantFastBreakLevel, 1, 10);
		float factor = 1.15F + level * 0.14F;                 // ~1.3x (lvl 1) … ~2.55x (lvl 10)
		float jitter = 1.0F + (RNG.nextFloat() - 0.5F) * 0.12F; // ±6% per sample — no flat ratio
		return vanillaDelta * factor * jitter;
	}
}
