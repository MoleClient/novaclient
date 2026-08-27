package com.profps.client.instants;

/**
 * Decides whether an axe-then-mace stun slam will deal net damage. Inside the invulnerability
 * window {@code LivingEntity.damage} only applies the amount above {@code lastDamageTaken}, so the
 * mace must strictly beat the axe tap that precedes it.
 */
public final class StunSlamPolicy {
	/** Vanilla player gravity per tick, and the drag applied after it. */
	private static final double GRAVITY = 0.08D;
	private static final double DRAG = 0.98D;

	/** Ticks between committing to the combo and the mace actually swinging. */
	public static final int SLAM_DELAY_TICKS = 3;

	/** Net damage below which the combo is not worth committing to. */
	public static final double MIN_NET_DAMAGE = 2.0D;

	private StunSlamPolicy() {
	}

	/**
	 * {@code MaceItem.getBonusAttackDamage}: a fall-distance curve added after the charge
	 * multiplier and independent of base damage.
	 */
	public static double bonusDamage(double fallDistance) {
		if (fallDistance <= 0.0D) return 0.0D;
		if (fallDistance <= 3.0D) return 4.0D * fallDistance;
		if (fallDistance <= 8.0D) return 12.0D + 2.0D * (fallDistance - 3.0D);
		return 22.0D + (fallDistance - 8.0D);
	}

	/** {@code PlayerEntity.attack}: {@code 0.2 + progress * progress * 0.8}. */
	public static double chargeMultiplier(double cooldownProgress) {
		double p = Math.clamp(cooldownProgress, 0.0D, 1.0D);
		return 0.2D + p * p * 0.8D;
	}

	/** Vertical distance descended before the mace swings, assuming an uninterrupted fall. */
	public static double dropBeforeSlam(double velocityY) {
		double v = velocityY;
		double drop = 0.0D;
		for (int i = 0; i < SLAM_DELAY_TICKS; i++) {
			v = (v - GRAVITY) * DRAG;
			if (v < 0.0D) drop += -v;
		}
		return drop;
	}

	/** Projects {@code fallDistance} forward {@code ticks} by stepping vanilla gravity. */
	public static double projectFall(double fallDistance, double velocityY, int ticks) {
		double fall = Math.max(0.0D, fallDistance);
		double v = velocityY;
		for (int i = 0; i < ticks; i++) {
			v = (v - GRAVITY) * DRAG;
			if (v < 0.0D) fall += -v;
		}
		return fall;
	}

	/** Damage of the axe tap, which is the floor the slam has to clear. */
	public static double axeTapDamage(double axeAttackDamage, double cooldownProgress, boolean crit) {
		double damage = axeAttackDamage * chargeMultiplier(cooldownProgress);
		return crit ? damage * 1.5D : damage;
	}

	/** Mace damage: charge-scaled base plus the unscaled fall bonus. */
	public static double slamDamage(double maceAttackDamage, double cooldownProgress, double fallDistance) {
		return maceAttackDamage * chargeMultiplier(cooldownProgress) + bonusDamage(fallDistance);
	}

	/**
	 * Damage the slam applies given the axe already consumed the invulnerability window.
	 * Zero when the slam does not beat the axe.
	 */
	public static double netSlamDamage(double slamDamage, double axeDamage) {
		return slamDamage <= axeDamage ? 0.0D : slamDamage - axeDamage;
	}

	/** Fall distance at which the slam starts beating the axe. Exact inverse of {@link #bonusDamage}. */
	public static double breakEvenFall(double axeDamage, double maceAttackDamage, double maceCooldownProgress) {
		double needed = axeDamage - maceAttackDamage * chargeMultiplier(maceCooldownProgress);
		if (needed <= 0.0D) return 0.0D;
		if (needed <= 12.0D) return needed / 4.0D;
		if (needed <= 22.0D) return 3.0D + (needed - 12.0D) / 2.0D;
		return 8.0D + (needed - 22.0D);
	}

	/**
	 * Whether to commit to the combo. {@code fallDistance} and {@code velocityY} are sampled at
	 * commit time and projected forward to the tick the mace swings.
	 */
	public static boolean worthCommitting(double fallDistance, double velocityY,
			double axeAttackDamage, double axeCooldownProgress, boolean axeWillCrit,
			double maceAttackDamage, double maceCooldownProgressAtSlam) {
		double fallAtSlam = projectFall(fallDistance, velocityY, SLAM_DELAY_TICKS);
		double axe = axeTapDamage(axeAttackDamage, axeCooldownProgress, axeWillCrit);
		double slam = slamDamage(maceAttackDamage, maceCooldownProgressAtSlam, fallAtSlam);
		return netSlamDamage(slam, axe) >= MIN_NET_DAMAGE;
	}
}
