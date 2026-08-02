package com.profps.client.assists;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.security.SecureRandom;

/**
 * Auto Jump Reset — when someone hits you, it jumps for you to cut the knockback,
 * the way a good PvPer jump-resets.
 *
 * <p>It jumps by briefly holding the real JUMP KEY, not by calling {@code jump()}
 * directly. That distinction matters for anti-cheat: a direct {@code jump()} changes
 * your velocity while your input packet still says you never pressed jump, and a
 * movement-prediction anti-cheat (Grim, etc.) flags "jumped without input" as an
 * invalid movement. Pressing the key drives the jump through the normal pipeline, so
 * the input packet, the velocity and the position all agree — it's byte-for-byte a
 * real jump. Timing is jittered and it skips the odd reset so it's never robotic.
 */
public final class JumpResetController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private int prevHurtTime;
	private long jumpAtNanos;          // 0 = nothing queued
	private long expireAtNanos;        // queued jump is abandoned past this (window missed)
	private long cooldownUntilNanos;
	private long jumpKeyHeldUntilNanos; // we auto-pressed jump; release it at this time

	public JumpResetController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		long now = System.nanoTime();

		// Release the auto-held jump key once its short window passes (or whenever we
		// stop running) so we never latch the player's jump on.
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

		// A fresh hit bumps hurtTime up from whatever it was decaying through.
		int hurt = player.hurtTime;
		boolean freshHit = hurt > prevHurtTime;
		prevHurtTime = hurt;

		if (freshHit && jumpAtNanos == 0L && now >= cooldownUntilNanos
				&& player.isOnGround() && !player.getAbilities().flying) {
			// Humanize: skip the odd reset and react with a small jittered delay rather than
			// firing on the exact hit tick every time. Kept snappy so it jumps before the
			// knockback fully commits.
			if (rng.nextInt(100) < config.jumpResetSkipPct) return;
			double delayMs = config.jumpResetReactionMs + rng.nextDouble() * 30.0; // set reaction + jitter
			jumpAtNanos = now + (long) (delayMs * 1_000_000.0);
			// Generous window: if the hit's knockback launches you off the ground before the
			// jump fires, DON'T give up — wait and jump the instant you land again. That
			// land-and-jump IS the reset rhythm. The old 180ms window expired mid-air and so
			// the reset simply never happened — which is why it "didn't work".
			expireAtNanos = jumpAtNanos + 650_000_000L;
		}

		if (jumpAtNanos != 0L && now >= jumpAtNanos) {
			if (player.isOnGround()) {
				// Hold the jump key for ~1.5 ticks: vanilla reads it, jumps once (jump
				// cooldown stops a double), and sends the input packet WITH jump set —
				// so the movement and the input agree.
				client.options.jumpKey.setPressed(true);
				jumpKeyHeldUntilNanos = now + 80_000_000L;
				cooldownUntilNanos = now + (long) ((230.0 + rng.nextDouble() * 140.0) * 1_000_000.0);
				jumpAtNanos = 0L;
			} else if (now >= expireAtNanos) {
				jumpAtNanos = 0L; // left the ground before the window — missed, like a human would
			}
		}
	}
}
