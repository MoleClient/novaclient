package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScanBudgetTest {
	@Test
	void advancedEspCannotStarveStorageOrAmethyst() {
		ProFPSConfig config = new ProFPSConfig();
		config.enabled = true;
		config.donutAdvancedEsp = true;
		config.donutStorageEsp = true;
		config.donutAmethystDetector = true;

		long advanced = ScanBudget.laneBudget(config, ScanBudget.Lane.ADVANCED_ESP, false);
		long storage = ScanBudget.laneBudget(config, ScanBudget.Lane.STORAGE_ESP, false);
		long amethyst = ScanBudget.laneBudget(config, ScanBudget.Lane.AMETHYST, false);

		assertEquals(8_000_000L / 3L, advanced);
		assertEquals(advanced, storage);
		assertEquals(advanced, amethyst);
		assertTrue(storage > 0L);
		assertTrue(amethyst > 0L);
	}

	@Test
	void reducedModeStillProgressesEveryEnabledScanner() {
		ProFPSConfig config = new ProFPSConfig();
		config.enabled = true;
		config.donutAdvancedEsp = true;
		config.donutStorageEsp = true;
		config.donutAmethystDetector = true;
		config.donutPrimeChunk = true;
		config.donutStashPinger = true;

		int lanes = ScanBudget.Lane.values().length;
		for (ScanBudget.Lane lane : ScanBudget.Lane.values()) {
			assertEquals(4_000_000L / lanes, ScanBudget.laneBudget(config, lane, true));
		}
	}

	@Test
	void aSingleScannerKeepsTheFullPool() {
		ProFPSConfig config = new ProFPSConfig();
		config.enabled = true;
		config.donutAmethystDetector = true;

		assertEquals(8_000_000L,
				ScanBudget.laneBudget(config, ScanBudget.Lane.AMETHYST, false));
	}
}
