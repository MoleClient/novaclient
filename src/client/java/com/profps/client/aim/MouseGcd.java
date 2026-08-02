package com.profps.client.aim;

import net.minecraft.client.MinecraftClient;

import java.util.Random;

/**
 * Mouse-sensitivity GCD — the rotation grid every assist must stay on.
 *
 * <p>Real mouse input only ever rotates the camera by whole multiples of a
 * sensitivity-derived base unit (raw hardware counts × a constant), and server
 * anti-cheats (Grim, Intave, Vulcan, …) reconstruct that unit from your recent
 * rotation deltas and reject anything off it.
 *
 * <p>Crucially, the grid is the one YOUR OWN mouse uses — derived from your actual
 * sensitivity setting. An earlier version snapped to a <i>random</i> simulated grid;
 * that worked only while the assist did all the turning, but the moment you also
 * moved your mouse (which you do constantly in a fight) your real deltas and the
 * assist's deltas were on two different grids, and their sum matched neither — an
 * invalid-rotation flag. So we compute the unit from {@code getMouseSensitivity()}
 * live, exactly as {@code Mouse} does ({@code (sens·0.6+0.2)³ · 8 · 0.15} per count),
 * and every assist quantizes to it. Now the mod's rotations are indistinguishable
 * from extra mouse counts on your own grid, even mixed with your real movement.
 */
public final class MouseGcd {
	private float yawCarry;
	private float pitchCarry;

	public MouseGcd() {}

	/** Kept for call-site compatibility; the grid comes from the live sensitivity. */
	public MouseGcd(Random rng) {}

	/**
	 * The rotation produced by a single raw mouse count at the player's CURRENT
	 * sensitivity — the exact grid their own look packets land on.
	 */
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

	/** Quantize a raw yaw delta (degrees) to the mouse grid, carrying the remainder. */
	public float yaw(float delta) {
		float wanted = delta + yawCarry;
		float applied = quantize(wanted);
		yawCarry = wanted - applied;
		return applied;
	}

	/** Quantize a raw pitch delta (degrees) to the mouse grid, carrying the remainder. */
	public float pitch(float delta) {
		float wanted = delta + pitchCarry;
		float applied = quantize(wanted);
		pitchCarry = wanted - applied;
		return applied;
	}

	/** Snap a raw degree delta to the nearest whole multiple of the live mouse grid. */
	public static float quantize(float delta) {
		if (!Float.isFinite(delta)) return 0.0F; // never let NaN/Infinity reach a rotation packet
		double gcd = currentGcd();
		if (gcd <= 0.0D) return delta;
		return (float) (Math.round(delta / gcd) * gcd);
	}
}
