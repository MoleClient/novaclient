package com.profps.client.instants;

import net.minecraft.util.math.MathHelper;

/**
 * Pure timing policy for Auto Clicker. Kept separate from Minecraft state so the
 * click distribution and its hard safety limits can be regression-tested.
 */
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

	/**
	 * Samples the next whole-tick interval. Fractional intervals are stochastically
	 * rounded instead of accumulated, so timing error is never paid back as a burst.
	 */
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

		// ±19% local variation around a slowly drifting pace. The controller feeds
		// a triangular sample, making ordinary timing much more common than extremes.
		double exactTicks = (20.0 / cps) * pace * (0.81 + timing * 0.38);
		if (hesitation) exactTicks += 1.0 + rounding;

		int wholeTicks = (int) Math.floor(exactTicks);
		double fraction = exactTicks - wholeTicks;
		int interval = wholeTicks + (rounding < fraction ? 1 : 0);
		interval = MathHelper.clamp(interval, 1, MAX_INTERVAL_TICKS);

		// Never sustain a one-packet-per-tick run. Three quick clicks are possible;
		// the fourth interval must breathe.
		if (interval == 1 && consecutiveFastIntervals >= 3) interval = 2;
		return interval;
	}
}
