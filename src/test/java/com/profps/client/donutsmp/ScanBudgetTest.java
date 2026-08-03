package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScanBudgetTest {
	@Test
	void advancedEspCannotStarveStashOrChunkFinder() {
		ProFPSConfig config = new ProFPSConfig();
		config.enabled = true;
		config.donutAdvancedEsp = true;
		// Stash Pinger reads Storage ESP's containers now, not the terrain scan,
		// so that is what has to be on for its lane to be live.
		config.donutStorageEsp = true;
		config.donutStashPinger = true;
		config.donutChunkFinder = true;

		long advanced = ScanBudget.laneBudget(config, ScanBudget.Lane.ADVANCED_ESP, false);
		long stash = ScanBudget.laneBudget(config, ScanBudget.Lane.STASH_PINGER, false);
		long finder = ScanBudget.laneBudget(config, ScanBudget.Lane.BASE_FINDER, false);

		assertEquals(8_000_000L / 4L, advanced);
		assertEquals(advanced, stash);
		assertEquals(advanced, finder);
		assertTrue(stash > 0L);
		assertTrue(finder > 0L);
	}

	@Test
	void reducedModeStillProgressesEveryEnabledScanner() {
		ProFPSConfig config = new ProFPSConfig();
		config.enabled = true;
		config.donutAdvancedEsp = true;
		config.donutStorageEsp = true;
		config.donutSuspiciousChunks = true;
		config.donutStashPinger = true;
		config.donutChunkFinder = true;
		config.donutAmethystDetector = true;
		config.donutNetherPortalMapper = true;

		int lanes = ScanBudget.Lane.values().length;
		for (ScanBudget.Lane lane : ScanBudget.Lane.values()) {
			assertEquals(4_000_000L / lanes, ScanBudget.laneBudget(config, lane, true));
		}
	}

	@Test
	void aSingleScannerKeepsTheFullPool() {
		ProFPSConfig config = new ProFPSConfig();
		config.enabled = true;
		config.donutChunkFinder = true;

		assertEquals(8_000_000L,
				ScanBudget.laneBudget(config, ScanBudget.Lane.BASE_FINDER, false));
	}
}
