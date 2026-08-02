package com.profps.client.extras;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchematicLayerOrderTest {
	private static final int[][] NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	@Test
	void shellIsDepthOneAndDepthRisesInward() {
		Map<Long, Integer> depth = SchematicLayerOrder.depths(solidSquare(0, 0, 7));

		assertAll(
				() -> assertEquals(1, depth.get(SchematicLayerOrder.key(0, 0))),
				() -> assertEquals(1, depth.get(SchematicLayerOrder.key(3, 0))),
				() -> assertEquals(2, depth.get(SchematicLayerOrder.key(1, 1))),
				() -> assertEquals(3, depth.get(SchematicLayerOrder.key(2, 2))),
				() -> assertEquals(4, depth.get(SchematicLayerOrder.key(3, 3))));
	}

	@Test
	void everyFootprintCellIsRanked() {
		Set<Long> footprint = solidSquare(-40, 12, 24);
		Map<Long, Integer> depth = SchematicLayerOrder.depths(footprint);

		assertEquals(footprint.size(), depth.size());
		assertTrue(depth.values().stream().allMatch(value -> value >= 1));
	}

	@Test
	void cellsOutsideTheFootprintAreUnranked() {
		Map<Long, Integer> depth = SchematicLayerOrder.depths(solidSquare(0, 0, 4));

		assertAll(
				() -> assertNull(depth.get(SchematicLayerOrder.key(-1, 0))),
				() -> assertNull(depth.get(SchematicLayerOrder.key(4, 4))));
	}

	@Test
	void aHollowRoomLeavesItsWallsOnTheShell() {
		// A 9x9 wall ring: every wall cell touches either the outside or the
		// room, so nothing is ever treated as buried.
		Set<Long> footprint = new HashSet<>();
		for (int x = 0; x < 9; x++) {
			for (int z = 0; z < 9; z++) {
				if (x == 0 || z == 0 || x == 8 || z == 8) footprint.add(SchematicLayerOrder.key(x, z));
			}
		}
		Map<Long, Integer> depth = SchematicLayerOrder.depths(footprint);

		assertEquals(footprint.size(), depth.size());
		assertTrue(depth.values().stream().allMatch(value -> value == 1));
	}

	@Test
	void disconnectedRegionsAreRankedIndependently() {
		Set<Long> footprint = solidSquare(0, 0, 5);
		footprint.addAll(solidSquare(60, 60, 3));
		Map<Long, Integer> depth = SchematicLayerOrder.depths(footprint);

		assertAll(
				() -> assertEquals(3, depth.get(SchematicLayerOrder.key(2, 2))),
				() -> assertEquals(2, depth.get(SchematicLayerOrder.key(61, 61))));
	}

	@Test
	void negativeCoordinatesRoundTripThroughTheKey() {
		long key = SchematicLayerOrder.key(-1234, -5678);
		assertAll(
				() -> assertEquals(-1234, SchematicLayerOrder.unpackX(key)),
				() -> assertEquals(-5678, SchematicLayerOrder.unpackZ(key)),
				() -> assertNotEquals(key, SchematicLayerOrder.key(-5678, -1234)));
	}

	/**
	 * The property the whole ordering exists for. A builder has to stand in an
	 * empty cell beside the one it is filling, and has to be able to walk there
	 * through cells that are still empty. Placing strictly deepest-first keeps
	 * that true for every cell of a 16x16 solid layer.
	 */
	@Test
	void descendingDepthBuildsAWideLayerWithoutStranding() {
		Set<Long> footprint = solidSquare(-3, -3, 16);
		Map<Long, Integer> depth = SchematicLayerOrder.depths(footprint);

		List<Long> order = new ArrayList<>(footprint);
		order.sort((first, second) -> Integer.compare(depth.get(second), depth.get(first)));

		assertNull(strandedBy(order, footprint, SchematicLayerOrder.key(5, 5)),
				"deepest-first stranded the builder");
	}

	@Test
	void descendingDepthHandlesAThickWallAndABigSlab() {
		// The two shapes that were failing in game: something long and several
		// blocks thick, and something wide enough to walk around inside.
		Set<Long> wall = new HashSet<>();
		for (int x = 0; x < 40; x++) {
			for (int z = 0; z < 5; z++) wall.add(SchematicLayerOrder.key(x, z));
		}
		assertNull(strandedBy(descending(wall), wall, SchematicLayerOrder.key(20, 2)));

		Set<Long> slab = solidSquare(100, -60, 24);
		assertNull(strandedBy(descending(slab), slab, SchematicLayerOrder.key(112, -48)));
	}

	/**
	 * The ordering rule the builder leans on: a cell is always placed before any
	 * shallower neighbour that could wall it off. Nearest-first offers no such
	 * guarantee, which is how interiors were being closed in.
	 */
	@Test
	void deeperCellsAlwaysPrecedeTheirShallowerNeighbours() {
		Set<Long> footprint = solidSquare(-3, -3, 16);
		Map<Long, Integer> depth = SchematicLayerOrder.depths(footprint);
		List<Long> order = descending(footprint);

		Map<Long, Integer> placedAt = new HashMap<>();
		for (int i = 0; i < order.size(); i++) placedAt.put(order.get(i), i);

		for (long cell : footprint) {
			int x = SchematicLayerOrder.unpackX(cell);
			int z = SchematicLayerOrder.unpackZ(cell);
			for (int[] step : NEIGHBORS) {
				long neighbor = SchematicLayerOrder.key(x + step[0], z + step[1]);
				if (!footprint.contains(neighbor) || depth.get(neighbor) >= depth.get(cell)) continue;
				assertTrue(placedAt.get(cell) < placedAt.get(neighbor),
						"a shallower neighbour was placed first, which can seal the deeper cell in");
			}
		}
	}

	private static List<Long> descending(Set<Long> footprint) {
		Map<Long, Integer> depth = SchematicLayerOrder.depths(footprint);
		List<Long> order = new ArrayList<>(footprint);
		order.sort((first, second) -> Integer.compare(depth.get(second), depth.get(first)));
		return order;
	}

	/**
	 * Replays a placement order as a builder walks it. Returns the first cell it
	 * could not reach an empty neighbouring stand for, or null if it built the
	 * whole layer.
	 */
	private static Long strandedBy(List<Long> order, Set<Long> footprint, long start) {
		Set<Long> placed = new HashSet<>();
		long builder = start;
		for (long cell : order) {
			Long stand = null;
			Set<Long> reachable = walkable(builder, placed, footprint);
			for (int[] step : NEIGHBORS) {
				long candidate = SchematicLayerOrder.key(
						SchematicLayerOrder.unpackX(cell) + step[0],
						SchematicLayerOrder.unpackZ(cell) + step[1]);
				if (reachable.contains(candidate)) {
					stand = candidate;
					break;
				}
			}
			if (stand == null) return cell;
			builder = stand;
			placed.add(cell);
		}
		return null;
	}

	/** Empty cells the builder can walk to, bounded to just outside the layer. */
	private static Set<Long> walkable(long from, Set<Long> placed, Set<Long> footprint) {
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (long cell : footprint) {
			minX = Math.min(minX, SchematicLayerOrder.unpackX(cell));
			maxX = Math.max(maxX, SchematicLayerOrder.unpackX(cell));
			minZ = Math.min(minZ, SchematicLayerOrder.unpackZ(cell));
			maxZ = Math.max(maxZ, SchematicLayerOrder.unpackZ(cell));
		}

		Set<Long> seen = new HashSet<>();
		List<Long> frontier = new ArrayList<>(List.of(from));
		seen.add(from);
		while (!frontier.isEmpty()) {
			long cell = frontier.remove(frontier.size() - 1);
			int x = SchematicLayerOrder.unpackX(cell);
			int z = SchematicLayerOrder.unpackZ(cell);
			for (int[] step : NEIGHBORS) {
				int nextX = x + step[0];
				int nextZ = z + step[1];
				if (nextX < minX - 1 || nextX > maxX + 1 || nextZ < minZ - 1 || nextZ > maxZ + 1) continue;
				long next = SchematicLayerOrder.key(nextX, nextZ);
				if (placed.contains(next) || !seen.add(next)) continue;
				frontier.add(next);
			}
		}
		return seen;
	}


	private static Set<Long> solidSquare(int originX, int originZ, int size) {
		Set<Long> cells = new HashSet<>();
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) cells.add(SchematicLayerOrder.key(originX + x, originZ + z));
		}
		return cells;
	}
}
