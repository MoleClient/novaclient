package com.profps.client.instants;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure trajectory solver for the ender-pearl and player-wind-charge catch. The collision is
 * modelled from the pearl's side, since only the older pearl's swept collision can select the
 * charge. Reads no world state, so callers must reject solutions whose path is obstructed.
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

	/** Returns centre-line solutions in quality order, best first. */
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

			// The direction is only meaningful once the charge has completed a movement tick.
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
		// The charge inherits shooter motion, so reachable centres after N ticks lie on a sphere
		// of radius 1.5*N around (origin + shooterVelocity*N).
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

		// ProjectileUtil grows a thrown projectile's entity tolerance from 0 to 0.30 over ages 2..8.
		int ageAtCatch = request.pearlAge() + pearlStep;
		double vanillaMargin = MathHelper.clamp((ageAtCatch - 2) / 20.0D, 0.0D, 0.30D);
		double collisionRadius = WIND_HALF_SIZE + vanillaMargin;
		// Uncapped on purpose: past the collision radius the shot must fail closed.
		double divergenceReserve = 0.03D + windMoves * 0.0105D;
		double usableTolerance = collisionRadius - divergenceReserve;
		if (usableTolerance < 0.10D || miss > usableTolerance) return null;

		// Prefer a deep intersection, then a shorter flight.
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
