package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.security.SecureRandom;

/** Repeatedly sends a configured chat message; a leading "/" sends it as a command. */
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

		// Speed 1..10 maps to ~1500ms down to ~140ms between sends, jittered.
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
