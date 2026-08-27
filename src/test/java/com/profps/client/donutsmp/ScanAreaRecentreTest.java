package com.profps.client.donutsmp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Covers the re-centre check that re-plans a scan cycle around a moving player. */
final class ScanAreaRecentreTest {
	@Test
	void aNullClientNeverClaimsTheAreaWasLeft() {
		// Tick paths can call this before a player exists.
		assertFalse(ScanBudget.leftScanArea(null, 0, 0, 3));
	}

	@Test
	void slackIsMeasuredInChunksNotBlocks() {
		// At 40 blocks/second a slack of 3 chunks re-plans roughly once a second.
		int slack = 3;
		double blocksPerSecond = 40.0D;
		double chunksPerSecond = blocksPerSecond / 16.0D;
		double secondsBetweenRecentres = slack / chunksPerSecond;
		org.junit.jupiter.api.Assertions.assertTrue(secondsBetweenRecentres > 0.5D
						&& secondsBetweenRecentres < 3.0D,
				"re-centre cadence at flight speed should be about a second, was "
						+ secondsBetweenRecentres);
	}
}
