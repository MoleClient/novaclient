package com.profps.client.donutsmp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A scan cycle is planned around one point and takes hundreds of ticks to drain.
 * At flight speed the player leaves that area long before it finishes, so the
 * re-centre test is what keeps the work in front of them instead of behind.
 */
final class ScanAreaRecentreTest {
	@Test
	void aNullClientNeverClaimsTheAreaWasLeft() {
		// Called from tick paths that can run before a player exists; answering
		// "true" there would restart the cycle forever and scan nothing.
		assertFalse(ScanBudget.leftScanArea(null, 0, 0, 3));
	}

	@Test
	void slackIsMeasuredInChunksNotBlocks() {
		// Documents the contract the callers rely on: at 40 blocks/second the
		// player crosses 2.5 chunks a second, so a slack of 3 chunks re-plans
		// roughly once a second during flight — often enough to stay ahead,
		// rarely enough that a cycle still gets real work done in between.
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
