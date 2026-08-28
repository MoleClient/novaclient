package com.profps.client.extras;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Interior-first ordering for a single build layer, by depth inward from the shell. */
final class SchematicLayerOrder {
	private static final int[][] NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private SchematicLayerOrder() {}

	/** Packs one layer column; the layer Y is implicit in the caller's map. */
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
	 * Depth of every footprint cell, counting inward from open space; depth 1 is the outer shell.
	 * The footprint must cover the whole layer, including cells already placed.
	 */
	static Map<Long, Integer> depths(Set<Long> footprint) {
		Map<Long, Integer> depth = new HashMap<>(Math.max(16, footprint.size() * 2));
		ArrayDeque<Long> frontier = new ArrayDeque<>();

		// Seed with the shell: footprint cells touching a cell outside the footprint.
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
