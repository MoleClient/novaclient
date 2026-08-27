package com.profps.client.instants;

/** Verified 1.21.11 gates used by the spear controllers. */
final class SpearCombatPolicy {
	static final float MIN_JAB_REACH = 2.0F;
	static final float MAX_JAB_REACH = 4.5F;
	static final int MIN_LUNGE_FOOD = 6;
	static final float FULL_JAB_CHARGE = 0.995F;

	/**
	 * Fallback kinetic gates, matching the diamond spear.
	 *
	 * <p>Every real spear carries its own {@code KINETIC_WEAPON} component and Auto Spear
	 * reads the numbers off the held stack, because they differ by tier — netherite arms in
	 * 8 ticks where diamond takes 10. These only stand in for a stack that somehow has none.
	 */
	static final int FALLBACK_ARM_TICKS = 10;
	static final int FALLBACK_DAMAGE_WINDOW_TICKS = 200;
	static final float FALLBACK_MIN_CLOSING_SPEED = 4.6F;

	private SpearCombatPolicy() {}

	static boolean canStartLunge(int food, boolean riding, boolean gliding, boolean touchingWater) {
		return food >= MIN_LUNGE_FOOD && !riding && !gliding && !touchingWater;
	}

	static boolean jabCharged(float cooldownProgress) {
		return Float.isFinite(cooldownProgress) && cooldownProgress >= FULL_JAB_CHARGE;
	}

	/**
	 * The closing speed vanilla actually measures for a kinetic contact: blocks per second
	 * along the <em>look</em> axis, less however much of that the target is carrying away by
	 * travelling the same direction.
	 *
	 * <p>Both terms come from {@code KineticWeaponComponent.getAmplifiedMovement}, which is
	 * per-tick movement scaled by 20. Two things fall out of it being measured along the look
	 * vector rather than toward the target: pitch costs you speed (aiming down at somebody
	 * throws away a cosine of your run), and a target sprinting away can put the hit out of
	 * reach without ever leaving the crosshair.
	 */
	static double closingSpeed(double ownSpeedAlongLook, double targetSpeedAlongLook) {
		return Math.max(0.0D, ownSpeedAlongLook - targetSpeedAlongLook);
	}

	/**
	 * Whether the charge has been held long enough to hit anything at all. Under this the
	 * spear is inert — which is the entire reason the charge has to be running before you
	 * arrive rather than started when you get there.
	 */
	static boolean armed(int heldTicks, int armTicks) {
		return heldTicks >= armTicks;
	}

	/** Whether a contact right now would deal damage rather than pass straight through. */
	static boolean contactDamages(int ticksSinceArmed, int windowTicks,
			double closingSpeed, double minClosingSpeed) {
		return ticksSinceArmed >= 0 && ticksSinceArmed <= windowTicks
				&& closingSpeed >= minClosingSpeed;
	}

	/**
	 * Vanilla's contact ray starts {@link #MIN_JAB_REACH} blocks in front of the eyes, so a
	 * target standing on top of you is not merely hard to hit, it is unreachable.
	 */
	static boolean withinContactBand(double distance, double minRange, double maxRange) {
		return distance >= minRange && distance <= maxRange;
	}
}
