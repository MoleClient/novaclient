package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.security.SecureRandom;

/**
 * Spam — repeatedly sends a message you type into the chat. Speed (1..10) sets how
 * fast, with a little jitter so it isn't a perfect metronome. A message starting
 * with "/" is sent as a command. Pauses while any screen is open so you can edit
 * the message or chat without it talking over you. Servers rate-limit / mute chat
 * spam, so the top speed is bounded to something a server will actually accept.
 */
public final class SpamController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private long nextSendNanos;

	public SpamController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || !config.spamEnabled || player == null
				|| player.networkHandler == null || client.currentScreen != null) {
			return;
		}
		String msg = config.spamMessage == null ? "" : config.spamMessage.trim();
		if (msg.isEmpty()) return;

		long now = System.nanoTime();
		if (nextSendNanos != 0L && now < nextSendNanos) return;

		// Speed 1..10 → ~1500ms down to ~140ms between sends, lightly jittered.
		int speed = MathHelper.clamp(config.spamSpeed, 1, 10);
		double baseMs = 1500.0 - (speed - 1) * 151.0;
		double ms = baseMs * (0.9 + rng.nextDouble() * 0.2);
		nextSendNanos = now + (long) (ms * 1_000_000.0);

		if (msg.startsWith("/")) {
			player.networkHandler.sendChatCommand(msg.substring(1));
		} else {
			player.networkHandler.sendChatMessage(msg);
		}
	}
}
