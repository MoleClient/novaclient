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
		STORAGE_ESP,
		SUSPICIOUS_CHUNKS,
		STASH_PINGER,
		BASE_FINDER,
		AMETHYST,
		NETHER_PORTAL
	}

	// How many ticks chunk-load activity keeps the pool in reduced mode.
	private static final int CHUNK_LOAD_QUIET_TICKS = 8;
	/** Past this much unbroken streaming, chunk traffic is steady state, not a spike. */
	private static final int SUSTAINED_STREAM_TICKS = 60;

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
	private static int streamingSince = Integer.MIN_VALUE;
	private static int poolTick      = Integer.MIN_VALUE;
	private static final long[] laneUsedNanos = new long[Lane.values().length];

	private ScanBudget() {}

	/**
	 * Call this from a chunk-load event or wherever new chunk geometry arrives.
	 * Supplying the current player age stamps the quiet window.
	 */
	public static void notifyChunkLoaded(int playerAge) {
		// The start of a fresh wave, rather than the continuation of one.
		if (!recentlyStreamed(playerAge)) streamingSince = playerAge;
		chunkLoadedAt = playerAge;
	}

	/**
	 * Whether the pool should run reduced right now.
	 *
	 * <p>Shrinking the pool protects frame time through the spiky part of a chunk
	 * wave — a login, a teleport, the first seconds of arriving somewhere new.
	 * But it was keyed purely on "a chunk arrived recently", and while flying,
	 * chunks arrive every single tick, so the pool stayed halved for the entire
	 * flight. That is precisely when the scanners have the most ground to cover
	 * and the least time to cover it. Sustained streaming is steady state, not a
	 * spike, so the protection expires and the full pool comes back.
	 */
	/** Exposed for regression tests; production reads it via {@link #takeBudget}. */
	static boolean shouldReduceFor(int tick) {
		return shouldReduce(tick);
	}

	private static boolean shouldReduce(int tick) {
		if (!recentlyStreamed(tick)) return false;
		return streamingSince == Integer.MIN_VALUE || tick - streamingSince < SUSTAINED_STREAM_TICKS;
	}

	/**
	 * True while chunk data has arrived recently.
	 *
	 * <p>Compared against an explicit "never" sentinel rather than by subtraction.
	 * {@code chunkLoadedAt} starts at {@link Integer#MIN_VALUE}, so
	 * {@code age - chunkLoadedAt} overflows to a negative number and reads as
	 * "streaming" before the first chunk packet ever arrives — and again after a
	 * respawn resets {@code player.age} while the stamp is still large.
	 */
	private static boolean recentlyStreamed(int tick) {
		return chunkLoadedAt != Integer.MIN_VALUE
				&& tick >= chunkLoadedAt
				&& tick - chunkLoadedAt < CHUNK_LOAD_QUIET_TICKS;
	}

	/**
	 * True once the player has left the area a scan cycle was planned around.
	 *
	 * <p>Every scanner snapshots a centre when it opens a cycle and then works a
	 * fixed square around it. A full cycle is hundreds of ticks, so at flight
	 * speed — 40 blocks a second is two and a half chunks a second — the queue
	 * is still grinding through terrain far behind by the time it finishes, and
	 * everything ahead is never queued at all. Re-planning around the new
	 * position keeps the work where the player actually is.
	 */
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
			case SUSPICIOUS_CHUNKS -> config.donutSuspiciousChunks;
			case STASH_PINGER -> config.donutStorageEsp && config.donutStashPinger;
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
		return recentlyStreamed(client.player.age);
	}
}
