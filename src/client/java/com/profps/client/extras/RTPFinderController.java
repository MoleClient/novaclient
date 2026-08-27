package com.profps.client.extras;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/** Repeats a random-teleport command until a landing falls within the configured radius of the target. */
public final class RTPFinderController {
	private static final int[] RADII = {5000, 10000, 20000};

	private final ProFPSConfig config;

	private boolean running;
	private long nextSendNanos;
	private int attempts;
	private double bestDistance = Double.MAX_VALUE;

	public RTPFinderController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.rtpFinderEnabled) {
			running = false;
			return;
		}
		if (client == null || client.player == null || client.world == null) return;
		ClientPlayerEntity player = client.player;
		if (player.networkHandler == null) return;

		String command = config.rtpFinderCommand == null ? "" : config.rtpFinderCommand.strip();
		if (!running) {
			if (command.isEmpty()) {
				stop(client, "RTP Finder: set a command first");
				return;
			}
			if (config.rtpFinderTargetX == 0 && config.rtpFinderTargetZ == 0) {
				stop(client, "RTP Finder: set your target X/Z first");
				return;
			}
			running = true;
			attempts = 0;
			bestDistance = Double.MAX_VALUE;
			nextSendNanos = 0L;
			chat(client, "RTP Finder started — target " + config.rtpFinderTargetX + ", "
					+ config.rtpFinderTargetZ + " (stopping within " + radius() + " blocks)");
		}

		long now = System.nanoTime();
		if (now < nextSendNanos) return;

		double distance = distanceToTarget(player);
		if (distance <= radius()) {
			chat(client, "RTP Finder: landed " + (int) distance + " blocks from target at "
					+ (int) player.getX() + ", " + (int) player.getZ() + " after " + attempts
					+ " tries — staying here.");
			stop(client, null);
			return;
		}
		if (attempts > 0) {
			if (distance < bestDistance) {
				bestDistance = distance;
				chat(client, "RTP Finder: closer — " + (int) player.getX() + ", " + (int) player.getZ()
						+ " (" + (int) distance + " blocks)");
			}
			overlay(client, "RTP Finder: try " + attempts + " · " + (int) distance + " blocks · best "
					+ (int) bestDistance);
		}

		attempts++;
		player.networkHandler.sendChatCommand(command.startsWith("/") ? command.substring(1) : command);
		nextSendNanos = now + MathHelper.clamp(config.rtpFinderIntervalMs, 250, 10000) * 1_000_000L;
	}

	private double distanceToTarget(ClientPlayerEntity player) {
		double dx = player.getX() - config.rtpFinderTargetX;
		double dz = player.getZ() - config.rtpFinderTargetZ;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private int radius() {
		return RADII[MathHelper.clamp(config.rtpFinderRadius, 0, RADII.length - 1)];
	}

	/** Switches the module off; {@code reason} is shown on the action bar when present. */
	private void stop(MinecraftClient client, String reason) {
		running = false;
		attempts = 0;
		bestDistance = Double.MAX_VALUE;
		nextSendNanos = 0L;
		config.rtpFinderEnabled = false;
		if (reason != null) overlay(client, reason);
	}

	private void chat(MinecraftClient client, String message) {
		if (client.player == null) return;
		client.player.sendMessage(Text.literal("[ProFPS] " + message), false);
	}

	private void overlay(MinecraftClient client, String message) {
		if (client.inGameHud == null) return;
		client.inGameHud.setOverlayMessage(Text.literal(message), false);
	}
}
