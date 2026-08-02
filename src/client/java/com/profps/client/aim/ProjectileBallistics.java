package com.profps.client.aim;

/**
 * Pure 1.21.11 projectile math shared by Auto Aim and its regression tests.
 *
 * <p>Persistent projectiles move once, then apply 0.99 air drag and 0.05 gravity.
 * The solver searches the natural low arc and rejects solutions whose vertical
 * miss is wider than a player hitbox can safely absorb.</p>
 */
final class ProjectileBallistics {
	static final double AIR_DRAG = 0.99D;
	static final double ARROW_GRAVITY = 0.05D;

	record ArrowArc(double angle, double ticks, double error) {}
	private record ArrowSample(double height, double ticks) {}

	private ProjectileBallistics() {}

	static ArrowArc solveLowArc(double horizontalDistance, double verticalDistance, double speed) {
		if (!Double.isFinite(horizontalDistance) || !Double.isFinite(verticalDistance)
				|| !Double.isFinite(speed) || horizontalDistance < 0.0D || speed <= 0.0D) {
			return null;
		}
		ArrowArc best = null;
		final int samples = 144;
		final double minAngle = -1.30D;
		final double maxAngle = 1.30D;
		for (int i = 0; i <= samples; i++) {
			double angle = minAngle + (maxAngle - minAngle) * i / samples;
			ArrowSample sample = sampleArrow(horizontalDistance, speed, angle);
			if (sample == null) continue;
			double error = Math.abs(sample.height() - verticalDistance);
			ArrowArc arc = new ArrowArc(angle, sample.ticks(), error);
			// Prefer accuracy first and, among effectively equal roots, the shorter
			// low arc that a player would naturally choose.
			if (best == null || error + sample.ticks() * 0.00020D
					< best.error() + best.ticks() * 0.00020D) {
				best = arc;
			}
		}
		if (best == null) return null;

		double step = (maxAngle - minAngle) / samples;
		double lo = Math.max(minAngle, best.angle() - step);
		double hi = Math.min(maxAngle, best.angle() + step);
		for (int i = 0; i < 22; i++) {
			double a = lo + (hi - lo) / 3.0D;
			double b = hi - (hi - lo) / 3.0D;
			ArrowSample sa = sampleArrow(horizontalDistance, speed, a);
			ArrowSample sb = sampleArrow(horizontalDistance, speed, b);
			double ea = sa == null ? Double.MAX_VALUE : Math.abs(sa.height() - verticalDistance);
			double eb = sb == null ? Double.MAX_VALUE : Math.abs(sb.height() - verticalDistance);
			if (ea <= eb) hi = b;
			else lo = a;
		}
		double angle = (lo + hi) * 0.5D;
		ArrowSample sample = sampleArrow(horizontalDistance, speed, angle);
		if (sample == null || Math.abs(sample.height() - verticalDistance) > 0.18D) return null;
		return new ArrowArc(angle, sample.ticks(), Math.abs(sample.height() - verticalDistance));
	}

	static double inheritedDisplacement(double ticks) {
		if (!Double.isFinite(ticks) || ticks <= 0.0D) return 0.0D;
		return (1.0D - Math.pow(AIR_DRAG, ticks)) / (1.0D - AIR_DRAG);
	}

	private static ArrowSample sampleArrow(double horizontalDistance, double speed, double angle) {
		double vy = speed * Math.sin(angle);
		double vx = speed * Math.cos(angle);
		if (vx <= 1.0E-6D) return null;
		double x = 0.0D;
		double y = 0.0D;
		for (int i = 0; i < 240 && x <= horizontalDistance; i++) {
			double previousX = x;
			double previousY = y;
			x += vx;
			y += vy;
			vx *= AIR_DRAG;
			vy = vy * AIR_DRAG - ARROW_GRAVITY;
			if (x >= horizontalDistance) {
				double fraction = (horizontalDistance - previousX)
						/ Math.max(1.0E-6D, x - previousX);
				return new ArrowSample(
						previousY + (y - previousY) * fraction,
						i + fraction);
			}
		}
		return null;
	}
}
