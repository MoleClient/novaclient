package com.profps.client.instants;

import net.minecraft.util.math.MathHelper;

/** Pure timing policy for Auto Clicker. */
public final class AutoClickRhythm {
	static final int MAX_LEFT_CPS = 16;
	static final int MAX_RIGHT_CPS = 12;
	private static final int MAX_INTERVAL_TICKS = 25;

	private AutoClickRhythm() {}

	static int effectiveCps(int configuredCps, boolean rightClick) {
		return MathHelper.clamp(
				configuredCps,
				1,
				rightClick ? MAX_RIGHT_CPS : MAX_LEFT_CPS
		);
	}

	/** Samples the next whole-tick interval, stochastically rounding the fractional part. */
	static int intervalTicks(
			int configuredCps,
			boolean rightClick,
			double paceScale,
			double timingSample,
			double roundingSample,
			boolean hesitation,
			int consecutiveFastIntervals
	) {
		int cps = effectiveCps(configuredCps, rightClick);
		double pace = MathHelper.clamp(paceScale, 0.90, 1.14);
		double timing = MathHelper.clamp(timingSample, 0.0, 1.0);
		double rounding = MathHelper.clamp(roundingSample, 0.0, 1.0);

		// +/-19% variation around the drifting pace; the caller supplies a triangular sample.
		double exactTicks = (20.0 / cps) * pace * (0.81 + timing * 0.38);
		if (hesitation) exactTicks += 1.0 + rounding;

		int wholeTicks = (int) Math.floor(exactTicks);
		double fraction = exactTicks - wholeTicks;
		int interval = wholeTicks + (rounding < fraction ? 1 : 0);
		interval = MathHelper.clamp(interval, 1, MAX_INTERVAL_TICKS);

		// Cap sustained one-tick intervals at three in a row.
		if (interval == 1 && consecutiveFastIntervals >= 3) interval = 2;
		return interval;
	}
}
