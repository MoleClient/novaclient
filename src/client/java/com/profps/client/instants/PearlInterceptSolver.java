package com.profps.client.instants;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure trajectory solver for the vanilla ender-pearl / player-wind-charge catch.
 *
 * <p>The collision is deliberately modelled in the direction vanilla evaluates it. An ender
 * pearl is not a redirectable projectile, so a wind charge cannot select it as an entity hit.
 * A player wind charge <em>is</em> redirectable, however, and the older pearl's swept collision
 * can select the charge. Since the pearl was spawned first, it normally ticks first: during a
 * pearl step it sees the charge at the position reached after {@code windMoves} completed charge
 * ticks. This is why {@code windMoves} starts at zero on the first pearl step after launch.
 *
 * <p>No world state is read here. Callers must separately reject solutions whose pearl or wind
 * path intersects a block/entity before the planned catch.
 */
public final class PearlInterceptSolver {
	public static final double PEARL_DRAG = 0.99D;
	public static final double PEARL_GRAVITY = 0.03D;
	public static final double WIND_SPEED = 1.5D;
	public static final double WIND_HALF_SIZE = 0.3125D * 0.5D;

	private PearlInterceptSolver() {}

	public record Request(
			Vec3d pearlPosition,
			Vec3d pearlVelocity,
			int pearlAge,
			Vec3d windOrigin,
			Vec3d shooterVelocity,
			int launchDelayTicks,
			int maxPearlSteps,
			double maxWindDistance) {
		public Request {
			if (pearlPosition == null || pearlVelocity == null || windOrigin == null || shooterVelocity == null) {
				throw new IllegalArgumentException("Trajectory vectors must be non-null");
			}
			launchDelayTicks = Math.max(0, launchDelayTicks);
			maxPearlSteps = MathHelper.clamp(maxPearlSteps, 4, 160);
			maxWindDistance = MathHelper.clamp(maxWindDistance, 4.0D, 128.0D);
		}
	}

	public record Solution(
			Vec3d aimDirection,
			Vec3d interceptPoint,
			Vec3d windPosition,
			int pearlSteps,
			int windMoves,
			double missDistance,
			double usableTolerance,
			double score) {}

	/** Returns the strongest feasible centre-line solution, or {@code null} when none exists. */
	public static Solution solve(Request request) {
		List<Solution> candidates = solveCandidates(request, 1);
		return candidates.isEmpty() ? null : candidates.getFirst();
	}

	/**
	 * Returns centre-line solutions in quality order. Exposing a short candidate list lets the
	 * world-aware controller discard a mathematically good route hidden behind a block and use the
	 * next clear one without coupling block access into this solver.
	 */
	public static List<Solution> solveCandidates(Request request, int limit) {
		Vec3d pearlPos = request.pearlPosition();
		Vec3d pearlVelocity = request.pearlVelocity();
		List<Solution> candidates = new ArrayList<>();

		for (int pearlStep = 1; pearlStep <= request.maxPearlSteps(); pearlStep++) {
			Vec3d nextVelocity = new Vec3d(
					pearlVelocity.x * PEARL_DRAG,
					(pearlVelocity.y - PEARL_GRAVITY) * PEARL_DRAG,
					pearlVelocity.z * PEARL_DRAG);
			Vec3d nextPos = pearlPos.add(nextVelocity);

			// On the first pearl tick after the charge is spawned, the older pearl sees the
			// not-yet-ticked charge at its launch origin. A direction becomes meaningful once
			// the charge has completed at least one movement tick.
			int windMoves = pearlStep - request.launchDelayTicks() - 1;
			if (windMoves >= 1) {
				Solution candidate = candidate(request, pearlPos, nextPos, pearlStep, windMoves);
				if (candidate != null) candidates.add(candidate);
			}

			pearlPos = nextPos;
			pearlVelocity = nextVelocity;
		}
		candidates.sort(Comparator.comparingDouble(Solution::score));
		int boundedLimit = MathHelper.clamp(limit, 1, 32);
		return candidates.size() <= boundedLimit
				? List.copyOf(candidates)
				: List.copyOf(candidates.subList(0, boundedLimit));
	}

	private static Solution candidate(Request request, Vec3d pearlFrom, Vec3d pearlTo,
			int pearlStep, int windMoves) {
		// Shooter motion is inherited by the charge after the 1.5-block/tick launch vector.
		// Therefore every reachable charge centre after N ticks lies on a sphere of radius
		// 1.5*N around (origin + shooterVelocity*N). Find the point of the swept pearl segment
		// nearest that sphere, then aim along the corresponding radius.
		Vec3d reachableCenter = request.windOrigin().add(request.shooterVelocity().multiply(windMoves));
		Vec3d closest = closestPointOnSegment(reachableCenter, pearlFrom, pearlTo);
		Vec3d radial = closest.subtract(reachableCenter);
		double radialDistance = radial.length();
		if (!Double.isFinite(radialDistance) || radialDistance < 1.0E-7D) return null;

		double travelRadius = WIND_SPEED * windMoves;
		Vec3d direction = radial.multiply(1.0D / radialDistance);
		Vec3d windPosition = reachableCenter.add(direction.multiply(travelRadius));
		Vec3d intercept = closestPointOnSegment(windPosition, pearlFrom, pearlTo);
		double miss = windPosition.distanceTo(intercept);
		double windDistance = windPosition.distanceTo(request.windOrigin());
		if (!Double.isFinite(miss) || windDistance > request.maxWindDistance()) return null;

		// ProjectileUtil grows a thrown projectile's entity tolerance from 0 to 0.30 over
		// ages 2..8. It expands the charge's 0.3125-wide box by that amount. Reserve part of
		// the mathematical radius for vanilla's divergence and small client/server phase error;
		// this makes the caller fail closed instead of spending charges on edge-only solutions.
		int ageAtCatch = request.pearlAge() + pearlStep;
		double vanillaMargin = MathHelper.clamp((ageAtCatch - 2) / 20.0D, 0.0D, 0.30D);
		double collisionRadius = WIND_HALF_SIZE + vanillaMargin;
		// Divergence grows with flight time. Do not cap this reserve: once accumulated
		// uncertainty exceeds the collision radius, a long shot is no longer reliable and
		// must fail closed instead of being presented as a guaranteed interception.
		double divergenceReserve = 0.03D + windMoves * 0.0105D;
		double usableTolerance = collisionRadius - divergenceReserve;
		if (usableTolerance < 0.10D || miss > usableTolerance) return null;

		// Prefer a deep intersection, then a shorter flight. The time term is deliberately
		// small: an older pearl has the larger vanilla targeting margin and can be more robust.
		double score = miss / usableTolerance + pearlStep * 0.004D + divergenceReserve * 0.10D;
		return new Solution(direction, intercept, windPosition, pearlStep, windMoves,
				miss, usableTolerance, score);
	}

	private static Vec3d closestPointOnSegment(Vec3d point, Vec3d from, Vec3d to) {
		Vec3d segment = to.subtract(from);
		double lengthSquared = segment.lengthSquared();
		if (lengthSquared < 1.0E-12D) return from;
		double t = MathHelper.clamp(point.subtract(from).dotProduct(segment) / lengthSquared, 0.0D, 1.0D);
		return from.add(segment.multiply(t));
	}
}
