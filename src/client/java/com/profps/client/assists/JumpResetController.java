package com.profps.client.assists;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.security.SecureRandom;

/** Jumps after taking a hit to reduce knockback. */
public final class JumpResetController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private int prevHurtTime;
	private long jumpAtNanos;          // 0 = nothing queued
	private long expireAtNanos;        // queued jump is abandoned past this
	private long cooldownUntilNanos;
	private long jumpKeyHeldUntilNanos; // release the auto-pressed jump key at this time

	public JumpResetController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		long now = System.nanoTime();

		// Release the auto-held jump key once its window passes so it never latches on.
		if (jumpKeyHeldUntilNanos != 0L && (now >= jumpKeyHeldUntilNanos || player == null)) {
			if (client.options != null) client.options.jumpKey.setPressed(false);
			jumpKeyHeldUntilNanos = 0L;
		}

		if (!config.enabled || !config.jumpResetAssist || player == null || client.world == null
				|| !player.isAlive()) {
			prevHurtTime = 0;
			jumpAtNanos = 0L;
			if (jumpKeyHeldUntilNanos != 0L && client.options != null) {
				client.options.jumpKey.setPressed(false);
				jumpKeyHeldUntilNanos = 0L;
			}
			return;
		}

		// A fresh hit bumps hurtTime up from its decaying value.
		int hurt = player.hurtTime;
		boolean freshHit = hurt > prevHurtTime;
		prevHurtTime = hurt;

		if (freshHit && jumpAtNanos == 0L && now >= cooldownUntilNanos
				&& player.isOnGround() && !player.getAbilities().flying) {
			if (rng.nextInt(100) < config.jumpResetSkipPct) return;
			double delayMs = config.jumpResetReactionMs + rng.nextDouble() * 30.0;
			jumpAtNanos = now + (long) (delayMs * 1_000_000.0);
			// Window spans the airtime so a knockback-launched player still jumps on landing.
			expireAtNanos = jumpAtNanos + 650_000_000L;
		}

		if (jumpAtNanos != 0L && now >= jumpAtNanos) {
			if (player.isOnGround()) {
				// Hold the jump key ~1.5 ticks so vanilla jumps once and the input packet matches.
				client.options.jumpKey.setPressed(true);
				jumpKeyHeldUntilNanos = now + 80_000_000L;
				cooldownUntilNanos = now + (long) ((230.0 + rng.nextDouble() * 140.0) * 1_000_000.0);
				jumpAtNanos = 0L;
			} else if (now >= expireAtNanos) {
				jumpAtNanos = 0L;
			}
		}
	}
}
