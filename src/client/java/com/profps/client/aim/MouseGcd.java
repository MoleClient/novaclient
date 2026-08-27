package com.profps.client.aim;

import net.minecraft.client.MinecraftClient;

import java.util.Random;

/** Quantizes rotation deltas to the live mouse-sensitivity grid. */
public final class MouseGcd {
	private float yawCarry;
	private float pitchCarry;

	public MouseGcd() {}

	/** Kept for call-site compatibility; the grid comes from the live sensitivity. */
	public MouseGcd(Random rng) {}

	/** Degrees produced by one raw mouse count. Mirrors vanilla Mouse: (sens*0.6+0.2)^3 * 8 * 0.15. */
	public static double currentGcd() {
		MinecraftClient mc = MinecraftClient.getInstance();
		double sensitivity = 0.5D;
		try {
			if (mc != null && mc.options != null) {
				sensitivity = mc.options.getMouseSensitivity().getValue();
			}
		} catch (RuntimeException ignored) {
			// fall back to a middle sensitivity if options aren't ready
		}
		double d = sensitivity * 0.6D + 0.2D;
		return d * d * d * 8.0D * 0.15D;
	}

	/** Quantizes a yaw delta in degrees to the mouse grid, carrying the remainder. */
	public float yaw(float delta) {
		float wanted = delta + yawCarry;
		float applied = quantize(wanted);
		yawCarry = wanted - applied;
		return applied;
	}

	/** Quantizes a pitch delta in degrees to the mouse grid, carrying the remainder. */
	public float pitch(float delta) {
		float wanted = delta + pitchCarry;
		float applied = quantize(wanted);
		pitchCarry = wanted - applied;
		return applied;
	}

	/** Snaps a degree delta to the nearest whole multiple of the live mouse grid. */
	public static float quantize(float delta) {
		if (!Float.isFinite(delta)) return 0.0F; // NaN/Infinity must not reach a rotation packet
		double gcd = currentGcd();
		if (gcd <= 0.0D) return delta;
		return (float) (Math.round(delta / gcd) * gcd);
	}
}
