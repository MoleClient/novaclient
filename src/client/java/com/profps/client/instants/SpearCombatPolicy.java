package com.profps.client.instants;

/** Verified 1.21.11 gates used by the spear controllers. */
final class SpearCombatPolicy {
	static final float MIN_JAB_REACH = 2.0F;
	static final float MAX_JAB_REACH = 4.5F;
	static final int MIN_LUNGE_FOOD = 6;
	static final float FULL_JAB_CHARGE = 0.995F;

	/**
	 * Fallback kinetic gates matching the diamond spear, used only when the held stack has no
	 * {@code KINETIC_WEAPON} component; the real values are read off the stack and vary by tier.
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
	 * Closing speed for a kinetic contact: blocks per second along the look axis, minus the
	 * target's speed along the same axis. Both terms come from
	 * {@code KineticWeaponComponent.getAmplifiedMovement}, per-tick movement scaled by 20.
	 */
	static double closingSpeed(double ownSpeedAlongLook, double targetSpeedAlongLook) {
		return Math.max(0.0D, ownSpeedAlongLook - targetSpeedAlongLook);
	}

	/** Whether the charge has been held long enough for the spear to deal damage. */
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
	 * Whether the distance falls in the contact band. Vanilla's contact ray starts
	 * {@link #MIN_JAB_REACH} blocks in front of the eyes, so closer targets are unreachable.
	 */
	static boolean withinContactBand(double distance, double minRange, double maxRange) {
		return distance >= minRange && distance <= maxRange;
	}
}
