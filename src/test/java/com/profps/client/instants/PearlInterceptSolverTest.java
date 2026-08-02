package com.profps.client.instants;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PearlInterceptSolverTest {
	@Test
	void solvesStraightUpPearlWithAnAscendingCharge() {
		PearlInterceptSolver.Solution solution = PearlInterceptSolver.solve(request(
				new Vec3d(0.0D, 2.0D, 0.0D),
				new Vec3d(0.0D, 1.5D, 0.0D),
				3,
				new Vec3d(0.0D, 1.62D, 0.0D),
				Vec3d.ZERO,
				0));

		assertNotNull(solution);
		assertAll(
				() -> assertTrue(solution.aimDirection().y > 0.98D),
				() -> assertTrue(solution.interceptPoint().y > 5.0D),
				() -> assertTrue(solution.pearlSteps() <= 12),
				() -> assertTrue(solution.missDistance() <= solution.usableTolerance()));
	}

	@Test
	void solvesAUsefulLongHorizontalThrow() {
		PearlInterceptSolver.Solution solution = PearlInterceptSolver.solve(request(
				new Vec3d(5.0D, 3.0D, 0.0D),
				new Vec3d(1.4D, 0.35D, 0.0D),
				4,
				new Vec3d(0.0D, 1.62D, 0.0D),
				Vec3d.ZERO,
				0));

		assertNotNull(solution);
		assertAll(
				() -> assertTrue(solution.interceptPoint().x > 20.0D),
				() -> assertTrue(solution.windMoves() > 10),
				() -> assertTrue(solution.windPosition().distanceTo(new Vec3d(0.0D, 1.62D, 0.0D)) <= 128.0D),
				() -> assertTrue(solution.missDistance() <= solution.usableTolerance()));
	}

	@Test
	void solvesDescendingPearlWithOneTickLaunchDelay() {
		PearlInterceptSolver.Solution solution = PearlInterceptSolver.solve(request(
				new Vec3d(4.0D, 8.0D, 0.0D),
				new Vec3d(1.2D, -0.1D, 0.0D),
				10,
				new Vec3d(0.0D, 1.62D, 0.0D),
				Vec3d.ZERO,
				1));

		assertNotNull(solution);
		assertAll(
				() -> assertTrue(solution.interceptPoint().y < 8.0D),
				() -> assertTrue(solution.interceptPoint().x > 10.0D),
				() -> assertTrue(solution.pearlSteps() > solution.windMoves()),
				() -> assertTrue(solution.missDistance() <= solution.usableTolerance()));
	}

	@Test
	void rejectsAnOutOfRangeDivergingPearl() {
		PearlInterceptSolver.Solution solution = PearlInterceptSolver.solve(request(
				new Vec3d(300.0D, 2.0D, 0.0D),
				new Vec3d(1.5D, 0.0D, 0.0D),
				3,
				new Vec3d(0.0D, 1.62D, 0.0D),
				Vec3d.ZERO,
				0));

		assertNull(solution);
	}

	private PearlInterceptSolver.Request request(Vec3d pearlPosition, Vec3d pearlVelocity,
			int pearlAge, Vec3d windOrigin, Vec3d shooterVelocity, int launchDelayTicks) {
		return new PearlInterceptSolver.Request(pearlPosition, pearlVelocity, pearlAge,
				windOrigin, shooterVelocity, launchDelayTicks, 160, 128.0D);
	}
}
