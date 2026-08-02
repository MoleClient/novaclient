package com.profps.client.extras;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.util.math.MathHelper;

/**
 * Ping Spoofer — makes the server report whatever ping you choose.
 *
 * <p>The server measures your latency purely by how long you take to answer its
 * KeepAlive packets. This holds that one reply for the configured number of
 * milliseconds (and ONLY that reply — your movement and everything else still flows
 * normally, so there's no actual lag), so the round-trip the server times comes back
 * as your spoofed ping. The held reply is always sent well within the server's
 * timeout window, and immediately if you switch the module off, so it never
 * disconnects you.
 *
 * <p>The KeepAlive arrives on the network thread; the mixin hands the id here and we
 * fire the delayed reply from the client tick (main thread). Fields are volatile for
 * that hand-off — there's only ever one KeepAlive in flight at a time.
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
	 * Called from the network thread the instant a KeepAlive lands. When spoofing,
	 * we take ownership of the reply (returning true) so the vanilla immediate answer
	 * is cancelled, and send it ourselves after the delay.
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
	 * How long to hold this reply. The Ping Equalizer (match your opponent) wins when
	 * it has a live combat target; otherwise the fixed Ping Spoofer value applies. A
	 * negative result means "don't spoof — answer immediately".
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
