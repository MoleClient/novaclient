package com.profps.client.extras;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pure temporary-support planner used by Auto Schematic Build.
 *
 * <p>The search runs backwards from each legal cell beside the desired block
 * until it finds a replaceable cell touching a confirmed solid anchor. It may
 * travel horizontally or descend one block per horizontal step. Reversing that
 * route produces a walkable bridge/staircase; every rise is expanded into a
 * horizontal connector followed by the upper block, so every emitted placement
 * has a real face on an earlier block. A small staging pad is appended beside
 * the target when space and materials allow.
 */
final class SchematicSupportPlanner {
	private static final int[][] CARDINAL = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1}
	};
	private static final int[][] TARGET_NEIGHBORS = {
			{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}
	};

	private SchematicSupportPlanner() {
	}

	record Cell(int x, int y, int z) {
		Cell add(int dx, int dy, int dz) {
			return new Cell(x + dx, y + dy, z + dz);
		}

		int manhattan(Cell other) {
			return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
		}
	}

	interface Space {
		/** True only for an empty, fluid-free, loaded cell Auto Build may occupy. */
		boolean available(Cell cell);

		/** True when this empty cell touches a confirmed solid placement face. */
		boolean anchored(Cell cell);
	}

	static List<Cell> plan(Cell target, Space space, int maxNodes, int horizontalHorizon,
			int downwardHorizon, int maxBlocks) {
		List<Cell> supports = new ArrayList<>(TARGET_NEIGHBORS.length);
		for (int[] offset : TARGET_NEIGHBORS) supports.add(target.add(offset[0], offset[1], offset[2]));
		return planFromSupports(target, supports, space, maxNodes, horizontalHorizon, downwardHorizon, maxBlocks);
	}

	static List<Cell> planFromSupports(Cell target, List<Cell> orderedSupports, Space space,
			int maxNodes, int horizontalHorizon, int downwardHorizon, int maxBlocks) {
		if (maxBlocks <= 0) return List.of();

		PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::score));
		Map<Cell, Double> cost = new HashMap<>();
		Map<Cell, Cell> parentTowardTarget = new HashMap<>();
		Set<Cell> starts = new HashSet<>();

		for (int i = 0; i < orderedSupports.size(); i++) {
			Cell start = orderedSupports.get(i);
			if (!space.available(start) || !starts.add(start)) continue;
			double preference = i * 0.18D;
			cost.put(start, preference);
			open.add(new Open(start, preference));
		}
		if (open.isEmpty()) return List.of();

		Cell anchor = null;
		int expanded = 0;
		while (!open.isEmpty() && expanded++ < Math.max(64, maxNodes)) {
			Open currentOpen = open.poll();
			Cell current = currentOpen.cell();
			if (currentOpen.cost() > cost.getOrDefault(current, Double.POSITIVE_INFINITY) + 1.0E-6D) continue;
			if (space.anchored(current)) {
				anchor = current;
				break;
			}

			for (int[] direction : CARDINAL) {
				// Same-height bridge.
				relax(target, current, current.add(direction[0], 0, direction[1]), null,
						1.0D, horizontalHorizon, downwardHorizon, space, cost, parentTowardTarget, open);

				// Reverse-search one step down. Forward construction becomes:
				// lower cell -> horizontal connector -> upper cell.
				Cell lower = current.add(direction[0], -1, direction[1]);
				Cell connector = current.add(0, -1, 0);
				relax(target, current, lower, connector, 0.82D,
						horizontalHorizon, downwardHorizon, space, cost, parentTowardTarget, open);
			}
		}
		if (anchor == null) return List.of();

		List<Cell> surface = new ArrayList<>();
		for (Cell cursor = anchor; cursor != null; cursor = parentTowardTarget.get(cursor)) {
			surface.add(cursor);
			if (starts.contains(cursor)) break;
		}
		if (surface.isEmpty() || !starts.contains(surface.getLast())) return List.of();

		LinkedHashSet<Cell> placements = new LinkedHashSet<>();
		placements.add(surface.getFirst());
		for (int i = 1; i < surface.size(); i++) {
			Cell previous = surface.get(i - 1);
			Cell next = surface.get(i);
			if (next.y() == previous.y() + 1) {
				placements.add(new Cell(next.x(), previous.y(), next.z()));
			}
			placements.add(next);
		}

		// Up to four extra cells around the final support produce a small, reusable
		// staging platform instead of balancing the player on a one-wide pillar.
		Cell support = surface.getLast();
		int stagingLimit = Math.min(maxBlocks, placements.size() + 4);
		for (int[] direction : CARDINAL) {
			if (placements.size() >= stagingLimit) break;
			Cell pad = support.add(direction[0], 0, direction[1]);
			if (!pad.equals(target) && space.available(pad)) placements.add(pad);
		}

		if (placements.size() > maxBlocks) return List.of();
		return List.copyOf(placements);
	}

	private static void relax(Cell target, Cell from, Cell next, Cell connector, double edgeCost,
			int horizontalHorizon, int downwardHorizon, Space space,
			Map<Cell, Double> cost, Map<Cell, Cell> parentTowardTarget, PriorityQueue<Open> open) {
		if (Math.abs(next.x() - target.x()) > horizontalHorizon
				|| Math.abs(next.z() - target.z()) > horizontalHorizon
				|| next.y() < target.y() - downwardHorizon || next.y() > target.y() + 2) return;
		if (!space.available(next) || (connector != null && !space.available(connector))) return;

		double tentative = cost.get(from) + edgeCost;
		if (tentative + 1.0E-6D >= cost.getOrDefault(next, Double.POSITIVE_INFINITY)) return;
		cost.put(next, tentative);
		parentTowardTarget.put(next, from);

		// Once none of the requested support cells is already anchored, strongly
		// prefer descending toward terrain so tall floating builds do not flood
		// every same-height cell before discovering a usable foundation.
		double heightBias = Math.max(0, next.y() - (target.y() - downwardHorizon)) * 0.85D;
		open.add(new Open(next, tentative, tentative + heightBias));
	}

	private record Open(Cell cell, double cost, double score) {
		private Open(Cell cell, double cost) {
			this(cell, cost, cost);
		}
	}
}
