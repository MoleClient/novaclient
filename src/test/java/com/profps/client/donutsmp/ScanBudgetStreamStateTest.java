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
		config.donutSuspiciousChunks = true;
		config.donutStashPinger = true;
		config.donutChunkFinder = true;
		config.donutAmethystDetector = true;
		config.donutNetherPortalMapper = true;

		// A lane that can be handed zero nanoseconds never pops a chunk, so its
		// cycle would never finish and its results would age out on screen.
		for (ScanBudget.Lane lane : ScanBudget.Lane.values()) {
			assertTrue(ScanBudget.laneBudget(config, lane, true) > 0L, lane + " must get time");
		}
	}

	private static boolean streaming(int tick) {
		return ScanBudget.laneBudgetReducedFor(tick);
	}
}
