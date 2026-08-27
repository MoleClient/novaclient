package com.profps.client.extras;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.util.math.MathHelper;

/**
 * Delays the KeepAlive reply so the server reports a chosen ping. Fields are volatile
 * because the id arrives on the network thread and the reply is sent from the client tick.
 */
public final class PingSpoofController {
	private final ProFPSConfig config;
	private final PingEqualizerController equalizer;
	private volatile boolean hasPending;
	private volatile long pendingId;
	private volatile long sendAtNanos;

	public PingSpoofController(ProFPSConfig config, PingEqualizerController equalizer) {
		this.config = config;
		this.equalizer = equalizer;
	}

	/**
	 * Called from the network thread when a KeepAlive lands.
	 *
	 * @return true if the reply is taken over here and the vanilla immediate answer should be cancelled
	 */
	public boolean captureIfSpoofing(long id) {
		int ms = effectiveDelayMs();
		if (ms < 0) return false;
		pendingId = id;
		sendAtNanos = System.nanoTime() + ms * 1_000_000L;
		hasPending = true;
		return true;
	}

	/**
	 * How long to hold the reply; the equalizer takes precedence over the fixed spoof value.
	 *
	 * @return delay in ms, or negative to answer immediately
	 */
	private int effectiveDelayMs() {
		if (!config.enabled) return -1;
		if (equalizer != null && equalizer.isActive()) {
			return MathHelper.clamp(equalizer.targetMs(), 20, 1000);
		}
		if (config.pingSpoofEnabled) {
			return MathHelper.clamp(config.pingSpoofMs, 20, 1000);
		}
		return -1;
	}

	public void tick(MinecraftClient client) {
		if (!hasPending) return;
		ClientPlayNetworkHandler net = client.getNetworkHandler();
		if (net == null) {
			hasPending = false;
			return;
		}
		boolean spoofingOff = effectiveDelayMs() < 0;
		if (spoofingOff || System.nanoTime() >= sendAtNanos) {
			net.sendPacket(new KeepAliveC2SPacket(pendingId));
			hasPending = false;
		}
	}
}
