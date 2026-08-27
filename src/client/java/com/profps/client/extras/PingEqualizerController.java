package com.profps.client.extras;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.UUID;

/**
 * Chooses a spoof delay matching the current combat opponent's ping, applied by
 * {@link PingSpoofController}.
 */
public final class PingEqualizerController {
	private static final long COMBAT_WINDOW_NANOS = 6_000_000_000L; // match for 6s after the last hit
	private static final int MIN_MS = 20; // spoof engine floor
	private static final int MAX_MS = 1000;

	private final ProFPSConfig config;
	private volatile UUID targetUuid;
	private volatile long activeUntilNanos;
	private volatile int targetMs = -1; // -1 means nothing to match

	public PingEqualizerController(ProFPSConfig config) {
		this.config = config;
	}

	/** Records the player just hit as the current combat opponent. */
	public void markAttack(MinecraftClient client, Entity entity) {
		if (!config.enabled || !config.pingEqualizerEnabled) return;
		if (client == null || client.player == null) return;
		if (!(entity instanceof PlayerEntity player) || entity == client.player) return;
		targetUuid = player.getUuid();
		activeUntilNanos = System.nanoTime() + COMBAT_WINDOW_NANOS;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.pingEqualizerEnabled) {
			targetUuid = null;
			targetMs = -1;
			return;
		}
		if (targetUuid == null || System.nanoTime() >= activeUntilNanos) {
			targetMs = -1; // combat window expired
			return;
		}
		ClientPlayNetworkHandler net = client == null ? null : client.getNetworkHandler();
		if (net == null) {
			targetMs = -1;
			return;
		}
		PlayerListEntry entry = net.getPlayerListEntry(targetUuid);
		if (entry == null) {
			targetMs = -1; // opponent left the tab list
			return;
		}
		int latency = entry.getLatency();
		if (latency <= 0) {
			targetMs = -1; // server has not reported their ping yet
			return;
		}
		targetMs = MathHelper.clamp(latency, MIN_MS, MAX_MS);
	}

	/** True while there is a live opponent ping to mirror. */
	public boolean isActive() {
		return config.enabled && config.pingEqualizerEnabled
				&& targetMs > 0 && System.nanoTime() < activeUntilNanos;
	}

	/** The ping in ms to make the server report, or -1 when not matching anyone. */
	public int targetMs() {
		return targetMs;
	}
}
