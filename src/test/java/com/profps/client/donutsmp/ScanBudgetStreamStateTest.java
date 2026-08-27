package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the chunk-stream state machine that decides when scanners run reduced. */
final class ScanBudgetStreamStateTest {
	@BeforeEach
	void reset() {
		ScanBudget.resetForWorldChange();
	}

	@Test
	void neverStreamedIsNotReportedAsStreaming() {
		// chunkLoadedAt starts at Integer.MIN_VALUE, so the subtraction must not overflow.
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
		// A respawn restarts player.age near zero while the stamp still holds a large tick.
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

		// A lane handed zero nanoseconds would never pop a chunk.
		for (ScanBudget.Lane lane : ScanBudget.Lane.values()) {
			assertTrue(ScanBudget.laneBudget(config, lane, true) > 0L, lane + " must get time");
		}
	}

	@Test
	void sustainedStreamingStopsThrottlingTheScanners() {
		// A fresh wave is throttled.
		ScanBudget.notifyChunkLoaded(0);
		assertTrue(ScanBudget.shouldReduceFor(0));

		// Flight keeps chunks arriving every tick.
		for (int tick = 1; tick <= 200; tick++) ScanBudget.notifyChunkLoaded(tick);

		assertTrue(streaming(200), "chunks really are still arriving");
		assertFalse(ScanBudget.shouldReduceFor(200), "sustained streaming is steady state, not a spike");
	}

	@Test
	void aQuietGapRearmsTheThrottleForTheNextWave() {
		ScanBudget.notifyChunkLoaded(0);
		for (int tick = 1; tick <= 200; tick++) ScanBudget.notifyChunkLoaded(tick);
		assertFalse(ScanBudget.shouldReduceFor(200));

		// A new spike after a quiet gap is throttled again.
		ScanBudget.notifyChunkLoaded(1_000);
		assertTrue(ScanBudget.shouldReduceFor(1_000));
	}

	private static boolean streaming(int tick) {
		return ScanBudget.laneBudgetReducedFor(tick);
	}
}
