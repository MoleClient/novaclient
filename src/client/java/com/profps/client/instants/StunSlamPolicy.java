package com.profps.client.instants;

/**
 * Whether an axe→mace stun slam will actually deal damage, rather than being swallowed whole.
 *
 * <p>This exists because the combo was failing for a reason no amount of timing work could fix.
 * Verified against 1.21.11 bytecode, {@code LivingEntity.damage} does this inside the
 * invulnerability window:
 *
 * <pre>
 * if (timeUntilRegen &gt; 10 &amp;&amp; !source.isIn(BYPASSES_COOLDOWN)) {
 *     if (amount &lt;= lastDamageTaken) return false;          // absorbed entirely
 *     applyDamage(amount - lastDamageTaken);                // only the difference lands
 * }
 * </pre>
 *
 * <p>The axe tap sets {@code lastDamageTaken}, so the mace has to <em>strictly beat the axe hit</em>
 * a tick or two later or it does literally nothing. And the axe tap is the worst possible thing to
 * have to beat: it lands at full charge, and because the combo deliberately drops sprint while
 * falling it also lands as a 1.5× crit. A netherite axe therefore sets a floor of 15 damage, while
 * the mace — whose clock the axe just reset — swings at about 20% charge and is carried almost
 * entirely by its fall bonus.
 *
 * <p>Solving {@code maceBase × 0.2 + bonus(fall) &gt; 15} puts the break-even at <b>3.8 blocks of
 * fall at the moment of the slam</b>. The old trigger started the combo at 1.3 blocks, which
 * reaches roughly 3.2 by the time the mace swings three ticks later — permanently below the line.
 * That is the whole bug: the slam was mathematically a no-op nearly every time, so what came out
 * was an axe tap and nothing else.
 *
 * <p>Everything here is plain arithmetic on numbers the caller supplies, so the rule can be tested
 * without a client.
 */
public final class StunSlamPolicy {
	/** Vanilla player gravity per tick, and the drag applied after it. */
	private static final double GRAVITY = 0.08D;
	private static final double DRAG = 0.98D;

	/** Ticks between committing to the combo and the mace actually swinging. */
	public static final int SLAM_DELAY_TICKS = 3;

	/**
	 * Net damage below which the combo is not worth the dive. Zero would technically "land", but a
	 * slam that nets a fraction of a heart has spent the fall, the shield break and the attack
	 * clock to accomplish nothing visible.
	 */
	public static final double MIN_NET_DAMAGE = 2.0D;

	private StunSlamPolicy() {
	}

	/**
	 * {@code MaceItem.getBonusAttackDamage} — a pure fall-distance curve. It ignores the weapon's
	 * base damage entirely and is added <em>after</em> the charge multiplier, which is why a
	 * near-zero-charge mace still hits hard off a real fall.
	 */
	public static double bonusDamage(double fallDistance) {
		if (fallDistance <= 0.0D) return 0.0D;
		if (fallDistance <= 3.0D) return 4.0D * fallDistance;
		if (fallDistance <= 8.0D) return 12.0D + 2.0D * (fallDistance - 3.0D);
		return 22.0D + (fallDistance - 8.0D);
	}

	/** {@code PlayerEntity.attack}: {@code 0.2 + progress² × 0.8}. */
	public static double chargeMultiplier(double cooldownProgress) {
		double p = Math.clamp(cooldownProgress, 0.0D, 1.0D);
		return 0.2D + p * p * 0.8D;
	}

	/**
	 * Where {@code fallDistance} will be in {@code ticks}, stepping vanilla gravity. Used to decide
	 * at commit time whether there will still be enough fall left when the mace finally swings —
	 * the old check asked about the fall <em>now</em>, which is the wrong moment by three ticks.
	 */
	public static double projectFall(double fallDistance, double velocityY, int ticks) {
		double fall = Math.max(0.0D, fallDistance);
		double v = velocityY;
		for (int i = 0; i < ticks; i++) {
			v = (v - GRAVITY) * DRAG;
			if (v < 0.0D) fall += -v;
		}
		return fall;
	}

	/** What the axe tap will deal, and therefore the floor the slam has to clear. */
	public static double axeTapDamage(double axeAttackDamage, double cooldownProgress, boolean crit) {
		double damage = axeAttackDamage * chargeMultiplier(cooldownProgress);
		return crit ? damage * 1.5D : damage;
	}

	/** What the mace will deal: charge-scaled base plus the unscaled fall bonus. */
	public static double slamDamage(double maceAttackDamage, double cooldownProgress, double fallDistance) {
		return maceAttackDamage * chargeMultiplier(cooldownProgress) + bonusDamage(fallDistance);
	}

	/**
	 * Damage the slam actually applies, given the axe already consumed the invulnerability window.
	 * Zero when the slam fails to beat the axe — which is the case the whole class exists for.
	 */
	public static double netSlamDamage(double slamDamage, double axeDamage) {
		return slamDamage <= axeDamage ? 0.0D : slamDamage - axeDamage;
	}

	/**
	 * The fall distance at which the slam starts beating the axe. Inverts {@link #bonusDamage}
	 * rather than searching, so it stays exact.
	 */
	public static double breakEvenFall(double axeDamage, double maceAttackDamage, double maceCooldownProgress) {
		double needed = axeDamage - maceAttackDamage * chargeMultiplier(maceCooldownProgress);
		if (needed <= 0.0D) return 0.0D;
		if (needed <= 12.0D) return needed / 4.0D;
		if (needed <= 22.0D) return 3.0D + (needed - 12.0D) / 2.0D;
		return 8.0D + (needed - 22.0D);
	}

	/**
	 * The decision. {@code fallDistance}/{@code velocityY} are sampled at commit time and projected
	 * forward, so this answers "will the mace still be high enough when it swings", not "is the
	 * player falling right now".
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
