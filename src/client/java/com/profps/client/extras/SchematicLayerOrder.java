package com.profps.client.extras;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Interior-first ordering for a single build layer.
 *
 * <p>A wide or thick layer seals its own interior off when it is filled
 * nearest-first: the shell closes around the player, and every cell behind it
 * loses its walking route, its line of sight, and often its support face at the
 * same moment. Those cells are then missed for the rest of the build.
 *
 * <p>This ranks every cell of the layer footprint by how deep inside it sits:
 * depth 1 is the outer shell and the depth rises inward. Placing in strictly
 * descending depth keeps a monotonically decreasing corridor of still-empty
 * cells between every remaining cell and open space, so the builder always
 * paints itself out of the layer instead of into it.
 */
final class SchematicLayerOrder {
	private static final int[][] NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private SchematicLayerOrder() {}

	/** Packs one layer column; the layer's Y is implicit in the caller's map. */
	static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	static int unpackX(long key) {
		return (int) (key >> 32);
	}

	static int unpackZ(long key) {
		return (int) key;
	}

	/**
	 * Depth of every footprint cell, counting inward from open space. Cells that
	 * are not part of the footprint are absent, which reads as depth 0.
	 *
	 * <p>The footprint must describe the whole layer the schematic wants, not
	 * just the cells still missing: an already-placed cell is a wall the builder
	 * has to route around exactly like one it is about to place.
	 */
	static Map<Long, Integer> depths(Set<Long> footprint) {
		Map<Long, Integer> depth = new HashMap<>(Math.max(16, footprint.size() * 2));
		ArrayDeque<Long> frontier = new ArrayDeque<>();

		// Seed with the shell: any footprint cell touching a cell the layer does
		// not want. A finite footprint always has one, so the sweep always runs.
		for (long cell : footprint) {
			int x = unpackX(cell);
			int z = unpackZ(cell);
			boolean enclosed = true;
			for (int[] step : NEIGHBORS) {
				if (!footprint.contains(key(x + step[0], z + step[1]))) {
					enclosed = false;
					break;
				}
			}
			if (enclosed) continue;
			depth.put(cell, 1);
			frontier.addLast(cell);
		}

		while (!frontier.isEmpty()) {
			long cell = frontier.removeFirst();
			int next = depth.get(cell) + 1;
			int x = unpackX(cell);
			int z = unpackZ(cell);
			for (int[] step : NEIGHBORS) {
				long neighbor = key(x + step[0], z + step[1]);
				if (!footprint.contains(neighbor) || depth.containsKey(neighbor)) continue;
				depth.put(neighbor, next);
				frontier.addLast(neighbor);
			}
		}
		return depth;
	}
}
