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
 * Ping Equalizer — makes the server report YOUR ping as the same value as the player
 * you're currently fighting, so neither side has a latency advantage in the trade.
 *
 * <p>It doesn't hold packets itself — it just decides the target delay and lets the
 * shared {@link PingSpoofController} KeepAlive-hold engine apply it. Each time you hit
 * a player they become the combat opponent for a few seconds; every tick we read that
 * opponent's ping from the tab list and expose it as the delay to match. When the fight
 * goes quiet (no hit within the window) or their ping is unknown, we stop matching and
 * your real ping shows through.
 *
 * <p>Because the server only re-measures latency on its periodic KeepAlive, a freshly
 * matched ping takes effect on the next KeepAlive — same inherent limitation as any
 * ping spoof.
 */
public final class PingEqualizerController {
	private static final long COMBAT_WINDOW_NANOS = 6_000_000_000L; // keep matching for 6s after the last hit
	private static final int MIN_MS = 20;   // matches the spoof engine's floor (tick granularity makes sub-20 meaningless)
	private static final int MAX_MS = 1000;

	private final ProFPSConfig config;
	private volatile UUID targetUuid;
	private volatile long activeUntilNanos;
	private volatile int targetMs = -1; // opponent's last known ping to mirror; -1 = nothing to match

	public PingEqualizerController(ProFPSConfig config) {
		this.config = config;
	}

	/** Record the player you just hit as the current combat opponent. */
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
			targetMs = -1; // fight went quiet → let real ping show
			return;
		}
		ClientPlayNetworkHandler net = client == null ? null : client.getNetworkHandler();
		if (net == null) {
			targetMs = -1;
			return;
		}
		PlayerListEntry entry = net.getPlayerListEntry(targetUuid);
		if (entry == null) {
			targetMs = -1; // opponent left the tab list (out of range / gone)
			return;
		}
		int latency = entry.getLatency();
		if (latency <= 0) {
			targetMs = -1; // server hasn't reported their ping yet
			return;
		}
		targetMs = MathHelper.clamp(latency, MIN_MS, MAX_MS);
	}

	/** True while we have a live opponent ping to mirror right now. */
	public boolean isActive() {
		return config.enabled && config.pingEqualizerEnabled
				&& targetMs > 0 && System.nanoTime() < activeUntilNanos;
	}

	/** The ping (ms) to make the server report, or -1 when not matching anyone. */
	public int targetMs() {
		return targetMs;
	}
}
