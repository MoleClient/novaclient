package com.profps.client.crystalpvp;

import net.minecraft.util.math.MathHelper;

/** Pure timing policy shared by the controller and unit tests. */
final class CrystalInteractionPolicy {
	private CrystalInteractionPolicy() {}

	/**
	 * Ticks to wait <em>after the world has already confirmed the last action</em> before the next
	 * one. This is padding on top of the unavoidable server round trip. Even the top
	 * setting keeps a one-tick floor: acting on the exact confirmation tick creates
	 * a latency-locked response cadence that physical input cannot reproduce.
	 */
	static int intervalTicks(int configuredSpeed, int sample) {
		int speed = MathHelper.clamp(configuredSpeed, 1, 10);
		int roll = MathHelper.clamp(sample, 0, 99);
		return switch (speed) {
			case 1 -> 6 + (roll < 45 ? 1 : 0);
			case 2 -> 5 + (roll < 40 ? 1 : 0);
			case 3 -> 4 + (roll < 40 ? 1 : 0);
			case 4 -> 3 + (roll < 45 ? 1 : 0);
			case 5 -> 3 + (roll < 15 ? 1 : 0);
			case 6 -> 2 + (roll < 40 ? 1 : 0);
			case 7 -> 2 + (roll < 12 ? 1 : 0);
			case 8 -> 1 + (roll < 55 ? 1 : 0);
			case 9 -> 1 + (roll < 32 ? 1 : 0);
			default -> 1 + (roll < 18 ? 1 : 0);
		};
	}
}
