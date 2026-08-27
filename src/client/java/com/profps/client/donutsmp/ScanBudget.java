package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;

/** Frame-pacing coordinator that divides per-tick scan time between the world scanners. */
public final class ScanBudget {
	public enum Lane {
		ADVANCED_ESP,
		STORAGE_ESP,
		AMETHYST,
		PRIME_CHUNK,
		STASH_PINGER
	}

	// Ticks of chunk-load activity that keep the pool in reduced mode.
	private static final int CHUNK_LOAD_QUIET_TICKS = 8;
	// Past this much unbroken streaming, chunk traffic is steady state, not a spike.
	private static final int SUSTAINED_STREAM_TICKS = 60;

	// Shared wall-clock pool per tick, split into one lane per enabled scanner.
	// Both values stay well inside a 50ms tick with every scanner enabled.
	private static final long TICK_POOL_NANOS = 8_000_000L;
	private static final long REDUCED_POOL_NANOS = 4_000_000L;

	private static int claimedTick   = Integer.MIN_VALUE;
	private static int chunkLoadedAt = Integer.MIN_VALUE;
	private static int streamingSince = Integer.MIN_VALUE;
	private static int poolTick      = Integer.MIN_VALUE;
	private static final long[] laneUsedNanos = new long[Lane.values().length];

	private ScanBudget() {}

	/** Stamps the chunk-load quiet window; call with the current player age when chunk geometry arrives. */
	public static void notifyChunkLoaded(int playerAge) {
		// Start of a fresh wave, rather than the continuation of one.
		if (!recentlyStreamed(playerAge)) streamingSince = playerAge;
		chunkLoadedAt = playerAge;
	}

	/** Exposed for regression tests; production reads it via {@link #takeBudget}. */
	static boolean shouldReduceFor(int tick) {
		return shouldReduce(tick);
	}

	private static boolean shouldReduce(int tick) {
		if (!recentlyStreamed(tick)) return false;
		return streamingSince == Integer.MIN_VALUE || tick - streamingSince < SUSTAINED_STREAM_TICKS;
	}

	// Tests the MIN_VALUE sentinel explicitly: subtraction overflows before the first
	// chunk packet, and again after a respawn resets player.age below the stamp.
	private static boolean recentlyStreamed(int tick) {
		return chunkLoadedAt != Integer.MIN_VALUE
				&& tick >= chunkLoadedAt
				&& tick - chunkLoadedAt < CHUNK_LOAD_QUIET_TICKS;
	}

	/** True once the player has moved far enough from the centre a scan cycle was planned around. */
	public static boolean leftScanArea(MinecraftClient client, int centreChunkX, int centreChunkZ, int slackChunks) {
		if (client == null || client.player == null) return false;
		int chunkX = client.player.getBlockX() >> 4;
		int chunkZ = client.player.getBlockZ() >> 4;
		return Math.abs(chunkX - centreChunkX) >= slackChunks
				|| Math.abs(chunkZ - centreChunkZ) >= slackChunks;
	}

	/** Exposes the stream state for regression tests; production reads it via {@link #takeBudget}. */
	static boolean laneBudgetReducedFor(int tick) {
		return recentlyStreamed(tick);
	}

	/** Clears the stream stamp so a world change cannot leave a stale future tick behind. */
	public static void resetForWorldChange() {
		chunkLoadedAt = Integer.MIN_VALUE;
		streamingSince = Integer.MIN_VALUE;
		claimedTick = Integer.MIN_VALUE;
		poolTick = Integer.MIN_VALUE;
		Arrays.fill(laneUsedNanos, 0L);
	}

	/**
	 * Tries to reserve this tick for starting a heavy scan cycle.
	 *
	 * @return true if the caller may scan; false if another scanner already owns this tick
	 */
	public static boolean tryClaim(int tick) {
		if (claimedTick == tick) return false;
		claimedTick = tick;
		return true;
	}

	/** Remaining scan-step budget for one lane this tick, in nanoseconds. */
	public static long takeBudget(int tick, Lane lane, ProFPSConfig config) {
		if (tick != poolTick) {
			poolTick = tick;
			Arrays.fill(laneUsedNanos, 0L);
		}
		boolean reduced = shouldReduce(tick);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.currentScreen != null) reduced = true;
		long allocation = laneBudget(config, lane, reduced);
		return Math.max(0L, allocation - laneUsedNanos[lane.ordinal()]);
	}

	/** Report time consumed against this scanner's own fair share. */
	public static void reportUsed(int tick, Lane lane, long nanos) {
		if (tick == poolTick && nanos > 0L) {
			laneUsedNanos[lane.ordinal()] += nanos;
		}
	}

	/** Pure allocation helper kept package-visible for scheduler regression tests. */
	static long laneBudget(ProFPSConfig config, Lane requestedLane, boolean reduced) {
		long pool = reduced ? REDUCED_POOL_NANOS : TICK_POOL_NANOS;
		int active = 0;
		for (Lane lane : Lane.values()) {
			if (isActive(config, lane) || lane == requestedLane) active++;
		}
		return pool / Math.max(1, active);
	}

	private static boolean isActive(ProFPSConfig config, Lane lane) {
		if (config == null || !config.enabled) return false;
		return switch (lane) {
			case ADVANCED_ESP -> config.donutAdvancedEsp;
			case STORAGE_ESP -> config.donutStorageEsp;
			case AMETHYST -> config.donutAmethystDetector;
			case PRIME_CHUNK -> config.donutPrimeChunk;
			case STASH_PINGER -> config.donutStashPinger;
		};
	}

	/** True while inside a chunk-load quiet window. */
	public static boolean isChunkLoadBusy(MinecraftClient client) {
		if (client.player == null) return false;
		return recentlyStreamed(client.player.age);
	}
}
