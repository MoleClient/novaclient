package com.profps.client.extras;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchematicPathfinderTest {
	@Test
	void walksAroundARealObstacleInsteadOfCrossingIt() {
		TestSpace space = new TestSpace();
		space.blocked.add(new SchematicPathfinder.Node(1, 0, 0));

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPath(
				new SchematicPathfinder.Node(0, 0, 0), new SchematicPathfinder.Node(3, 0, 0), space, 512);

		assertFalse(path.isEmpty());
		assertEquals(new SchematicPathfinder.Node(3, 0, 0), path.getLast());
		assertFalse(path.contains(new SchematicPathfinder.Node(1, 0, 0)));
	}

	@Test
	void findsAOneBlockStepUp() {
		TestSpace space = new TestSpace();
		space.forcedY.put("1,0", 1);
		space.forcedY.put("2,0", 1);

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPath(
				new SchematicPathfinder.Node(0, 0, 0), new SchematicPathfinder.Node(2, 1, 0), space, 512);

		assertFalse(path.isEmpty());
		assertEquals(1, path.getLast().y());
	}

	@Test
	void creativeFlightCanChangeAltitudeWithoutTeleporting() {
		TestSpace space = new TestSpace();
		List<SchematicPathfinder.Node> path = SchematicPathfinder.flightPath(
				new SchematicPathfinder.Node(0, 2, 0), new SchematicPathfinder.Node(2, 5, 1), space, 512);

		assertFalse(path.isEmpty());
		assertEquals(new SchematicPathfinder.Node(2, 5, 1), path.getLast());
		assertTrue(path.size() >= 2);
	}

	@Test
	void diagonalMovementDoesNotCutThroughSolidCorners() {
		TestSpace space = new TestSpace();
		space.blocked.add(new SchematicPathfinder.Node(1, 0, 0));
		space.blocked.add(new SchematicPathfinder.Node(0, 0, 1));

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPath(
				new SchematicPathfinder.Node(0, 0, 0), new SchematicPathfinder.Node(1, 0, 1), space, 512);

		assertFalse(path.isEmpty());
		assertTrue(path.size() > 2, "the blocked diagonal must route around the corner");
	}

	@Test
	void refusesAFourBlockGroundDrop() {
		TestSpace space = new TestSpace();
		space.forcedY.put("0,0", 4);
		space.forcedY.put("1,0", 0);

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPath(
				new SchematicPathfinder.Node(0, 4, 0), new SchematicPathfinder.Node(1, 0, 0), space, 128);

		assertTrue(path.isEmpty());
	}

	@Test
	void routesAroundHazardousGround() {
		TestSpace space = new TestSpace();
		space.hazards.add(new SchematicPathfinder.Node(1, 0, 0));

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPath(
				new SchematicPathfinder.Node(0, 0, 0), new SchematicPathfinder.Node(2, 0, 0), space, 512);

		assertFalse(path.isEmpty());
		assertFalse(path.contains(new SchematicPathfinder.Node(1, 0, 0)));
	}

	@Test
	void groundHorizonExtendsWellPastTheOldSeventyTwoBlockLimit() {
		TestSpace space = new TestSpace();

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPath(
				new SchematicPathfinder.Node(0, 0, 0), new SchematicPathfinder.Node(150, 0, 0),
				space, 8_192);

		assertFalse(path.isEmpty());
		assertEquals(new SchematicPathfinder.Node(150, 0, 0), path.getLast());
		assertTrue(path.size() > 100, "raw cells must remain available for lookahead and obstruction checks");
	}

	@Test
	void oneSearchChoosesAReachableGoalInsteadOfRetryingNearestStands() {
		TestSpace space = new TestSpace();
		SchematicPathfinder.Node blockedGoal = new SchematicPathfinder.Node(2, 0, 0);
		space.blocked.add(blockedGoal);
		SchematicPathfinder.Node reachableGoal = new SchematicPathfinder.Node(4, 0, 2);

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPathToAny(
				new SchematicPathfinder.Node(0, 0, 0), List.of(blockedGoal, reachableGoal), space, 1_024);

		assertFalse(path.isEmpty());
		assertEquals(reachableGoal, path.getLast());
	}

	@Test
	void creativeFlightUsesDiagonalCellsInsteadOfAxisOnlyRightAngles() {
		TestSpace space = new TestSpace();

		List<SchematicPathfinder.Node> path = SchematicPathfinder.flightPath(
				new SchematicPathfinder.Node(0, 2, 0), new SchematicPathfinder.Node(8, 10, 8),
				space, 2_048);

		assertFalse(path.isEmpty());
		assertEquals(new SchematicPathfinder.Node(8, 10, 8), path.getLast());
		assertTrue(path.size() <= 10, "26-way flight should follow the direct diagonal");
	}

	@Test
	void nearbyUnreachableGoalStillYieldsAShortApproachHop() {
		// The Auto Move approach fallback: a stand can be unprovable from afar
		// (occlusion, missing floor) while still worth walking toward. With the
		// preferred progress at 8, the search's minimum-progress clamp sits at
		// its 2-block floor, so even a player a few blocks behind the build
		// gets a route instead of a refusal.
		TestSpace space = new TestSpace();
		SchematicPathfinder.Node floating = new SchematicPathfinder.Node(6, 4, 0);
		space.forcedY.put("6,0", 4);

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPathTowardAny(
				new SchematicPathfinder.Node(0, 0, 0), List.of(floating), space, 1_024, 8.0D);

		assertFalse(path.isEmpty());
		assertTrue(path.getLast().x() >= 3, "the hop must close real distance toward the goal");
		assertFalse(path.contains(floating));
	}

	@Test
	void distantGoalReturnsAProgressRouteToTheLoadedFrontier() {
		TestSpace space = new TestSpace();
		space.maximumLoadedX = 64;
		SchematicPathfinder.Node distant = new SchematicPathfinder.Node(400, 0, 0);

		List<SchematicPathfinder.Node> path = SchematicPathfinder.groundPathTowardAny(
				new SchematicPathfinder.Node(0, 0, 0), List.of(distant), space, 8_192, 96.0D);

		assertFalse(path.isEmpty());
		assertTrue(path.getLast().x() >= 40, "the partial route should make material forward progress");
		assertTrue(path.getLast().x() <= 64, "the route must not enter an unloaded cell");
		assertFalse(path.getLast().equals(distant));
	}

	private static final class TestSpace implements SchematicPathfinder.Space {
		final Set<SchematicPathfinder.Node> blocked = new HashSet<>();
		final Set<SchematicPathfinder.Node> hazards = new HashSet<>();
		final java.util.Map<String, Integer> forcedY = new java.util.HashMap<>();
		int maximumLoadedX = Integer.MAX_VALUE;

		@Override
		public boolean standable(int x, int y, int z) {
			if (x > maximumLoadedX) return false;
			if (blocked.contains(new SchematicPathfinder.Node(x, y, z))) return false;
			return forcedY.getOrDefault(x + "," + z, 0) == y;
		}

		@Override
		public boolean passable(int x, int y, int z) {
			return x <= maximumLoadedX && !blocked.contains(new SchematicPathfinder.Node(x, y, z));
		}

		@Override
		public boolean hazardous(int x, int y, int z) {
			return hazards.contains(new SchematicPathfinder.Node(x, y, z));
		}
	}
}
