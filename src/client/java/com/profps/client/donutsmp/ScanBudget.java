package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;

/**
 * Frame-pacing coordinator for the heavy world scanners.
 *
 * Two jobs:
 *   1. Stagger the start of heavy scan cycles so their setup work never stacks.
 *   2. Fairly divide per-tick scan time between every enabled scanner. A single
 *      large Advanced ESP scan must not starve Stash Pinger or Chunk Finder.
 *   3. When the client is actively receiving new chunks (the render-thread is
 *      busy compiling geometry), skip heavy scans entirely until things settle.
 *      This eliminates the ~500ms freeze that appeared every time a new chunk
 *      loaded in near the player.
 */
public final class ScanBudget {
	public enum Lane {
		ADVANCED_ESP,
		STASH_PINGER,
		BASE_FINDER,
		AMETHYST,
		NETHER_PORTAL
	}

	// How many ticks chunk-load activity keeps the pool in reduced mode.
	private static final int CHUNK_LOAD_QUIET_TICKS = 8;

	/**
	 * Shared wall-clock pool for ALL incremental scan steps within one tick. The
	 * pool is divided into independent lanes for the enabled scanners. Previously
	 * the first scanner in the tick received the entire remaining pool, so an
	 * in-flight Advanced ESP cycle could leave every later scanner at 0 ns.
	 *
	 * <p>While chunks are streaming in (or a screen is open and UI smoothness
	 * matters most) the pool SHRINKS instead of closing entirely. On a busy
	 * server chunk packets can arrive nearly every tick, and a hard block here
	 * starved the scanners forever — ESP results only appeared once the player
	 * tabbed out and chunk loading stopped.
	 */
	// 8ms/4ms (was 6/3): results land noticeably sooner and the cost stays
	// well inside one 50ms tick even with every scanner module enabled.
	private static final long TICK_POOL_NANOS = 8_000_000L;
	private static final long REDUCED_POOL_NANOS = 4_000_000L;

	private static int claimedTick   = Integer.MIN_VALUE;
	private static int chunkLoadedAt = Integer.MIN_VALUE;
	private static int poolTick      = Integer.MIN_VALUE;
	private static final long[] laneUsedNanos = new long[Lane.values().length];

	private ScanBudget() {}

	/**
	 * Call this from a chunk-load event or wherever new chunk geometry arrives.
	 * Supplying the current player age stamps the quiet window.
	 */
	public static void notifyChunkLoaded(int playerAge) {
		chunkLoadedAt = playerAge;
	}

	/**
	 * Try to reserve this tick for starting a heavy scan cycle.
	 *
	 * @return true if the caller may scan; false if another scanner already owns
	 *         this tick. Chunk loading no longer blocks the claim — it only
	 *         shrinks the per-tick budget — so scans always make progress.
	 */
	public static boolean tryClaim(int tick) {
		if (claimedTick == tick) return false;
		claimedTick = tick;
		return true;
	}

	/**
	 * Remaining scan-step budget for one scanner lane this tick, in nanoseconds.
	 * Every enabled lane gets a non-zero fair share, regardless of tick order.
	 * The pool runs reduced (not closed) while chunks stream in or a screen is
	 * open.
	 */
	public static long takeBudget(int tick, Lane lane, ProFPSConfig config) {
		if (tick != poolTick) {
			poolTick = tick;
			Arrays.fill(laneUsedNanos, 0L);
		}
		boolean reduced = tick - chunkLoadedAt < CHUNK_LOAD_QUIET_TICKS;
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
			case STASH_PINGER -> config.donutAdvancedEsp && config.donutStashPinger;
			case BASE_FINDER -> config.donutChunkActivity || config.donutChunkFinder;
			case AMETHYST -> config.donutAmethystDetector;
			case NETHER_PORTAL -> config.donutNetherPortalMapper;
		};
	}

	/**
	 * Convenience: returns true when we are inside a chunk-load quiet window.
	 * Can be used by renderers that want to skip non-essential work too.
	 */
	public static boolean isChunkLoadBusy(MinecraftClient client) {
		if (client.player == null) return false;
		return client.player.age - chunkLoadedAt < CHUNK_LOAD_QUIET_TICKS;
	}
}
