package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk-stream stamp decides whether scanners run reduced, and the modules'
 * burst refresh is edge-triggered on it. Getting it wrong is invisible in game —
 * the overlay just quietly stops updating — so the state machine is pinned here.
 */
final class ScanBudgetStreamStateTest {
	@BeforeEach
	void reset() {
		ScanBudget.resetForWorldChange();
	}

	@Test
	void neverStreamedIsNotReportedAsStreaming() {
		// chunkLoadedAt starts at Integer.MIN_VALUE. Subtracting that from a
		// small player age overflows to a negative number, which used to read as
		// "chunks are streaming" before a single chunk packet had arrived — so
		// every scanner started life throttled for no reason.
		assertFalse(streaming(0));
		assertFalse(streaming(20));
	}

	@Test
	void aFreshChunkLoadStreamsThenSettles() {
		ScanBudget.notifyChunkLoaded(1_000);

		assertTrue(streaming(1_000), "the load tick itself is streaming");
		assertTrue(streaming(1_004), "still inside the quiet window");
		assertFalse(streaming(1_100), "well past the window");
	}

	@Test
	void anAgeResetCannotLeaveTheStampInTheFuture() {
		ScanBudget.notifyChunkLoaded(50_000);
		// Respawn/dimension change restarts player.age near zero while the stamp
		// still holds a large tick. Subtraction would go negative and latch
		// "streaming" on forever; the comparison has to reject a future stamp.
		assertFalse(streaming(5));

		ScanBudget.resetForWorldChange();
		assertFalse(streaming(5));
	}

	@Test
	void everyEnabledLaneKeepsANonZeroSlice() {
		ProFPSConfig config = new ProFPSConfig();
		config.enabled = true;
		config.donutAdvancedEsp = true;
		config.donutStorageEsp = true;
		config.donutAmethystDetector = true;
		config.donutPrimeChunk = true;
		config.donutStashPinger = true;

		// A lane that can be handed zero nanoseconds never pops a chunk, so its
		// cycle would never finish and its results would age out on screen.
		for (ScanBudget.Lane lane : ScanBudget.Lane.values()) {
			assertTrue(ScanBudget.laneBudget(config, lane, true) > 0L, lane + " must get time");
		}
	}

	@Test
	void sustainedStreamingStopsThrottlingTheScanners() {
		// A wave starts: throttled, so chunk geometry compiles smoothly.
		ScanBudget.notifyChunkLoaded(0);
		assertTrue(ScanBudget.shouldReduceFor(0));

		// Flight keeps chunks arriving every tick. The old rule kept the pool
		// halved for the entire flight — exactly when there is the most ground
		// to cover — because it only ever asked "did a chunk arrive recently".
		for (int tick = 1; tick <= 200; tick++) ScanBudget.notifyChunkLoaded(tick);

		assertTrue(streaming(200), "chunks really are still arriving");
		assertFalse(ScanBudget.shouldReduceFor(200), "sustained streaming is steady state, not a spike");
	}

	@Test
	void aQuietGapRearmsTheThrottleForTheNextWave() {
		ScanBudget.notifyChunkLoaded(0);
		for (int tick = 1; tick <= 200; tick++) ScanBudget.notifyChunkLoaded(tick);
		assertFalse(ScanBudget.shouldReduceFor(200));

		// Land, stop moving, then teleport somewhere new. That is a fresh spike
		// and has to be protected again.
		ScanBudget.notifyChunkLoaded(1_000);
		assertTrue(ScanBudget.shouldReduceFor(1_000));
	}

	private static boolean streaming(int tick) {
		return ScanBudget.laneBudgetReducedFor(tick);
	}
}
