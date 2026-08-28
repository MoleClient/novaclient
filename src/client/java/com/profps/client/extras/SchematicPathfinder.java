package com.profps.client.extras;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bounded A* for walking between schematic placement stands. Neighbors are walk,
 * one-block step, short drop, or collision-free flight cells; it never mines.
 */
final class SchematicPathfinder {
	private static final int GROUND_HORIZONTAL_HORIZON = 192;
	private static final int GROUND_VERTICAL_HORIZON = 64;
	private static final int FLIGHT_HORIZONTAL_HORIZON = 256;
	private static final int FLIGHT_VERTICAL_HORIZON = 128;
	private static final int[][] HORIZONTAL = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private SchematicPathfinder() {}

	record Node(int x, int y, int z) {
		double squaredDistanceTo(Node other) {
			double dx = x - other.x;
			double dy = y - other.y;
			double dz = z - other.z;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	interface Space {
		/** Player-sized clearance plus a safe supporting surface. */
		boolean standable(int x, int y, int z);

		/** Player-sized collision-free cell, used by creative flight. */
		boolean passable(int x, int y, int z);

		/** Lava, fire, cactus, void edge, or any other cell treated as impassable. */
		boolean hazardous(int x, int y, int z);
	}

	static List<Node> groundPath(Node start, Node goal, Space space, int maxNodes) {
		return groundPathToAny(start, List.of(goal), space, maxNodes);
	}

	static List<Node> flightPath(Node start, Node goal, Space space, int maxNodes) {
		return flightPathToAny(start, List.of(goal), space, maxNodes);
	}

	static List<Node> groundPathToAny(Node start, Collection<Node> goals, Space space, int maxNodes) {
		return search(start, goals, space, Math.max(64, maxNodes), false, false, 0.0D);
	}

	static List<Node> flightPathToAny(Node start, Collection<Node> goals, Space space, int maxNodes) {
		return search(start, goals, space, Math.max(64, maxNodes), true, false, 0.0D);
	}

	static List<Node> groundPathTowardAny(Node start, Collection<Node> goals, Space space,
			int maxNodes, double preferredProgress) {
		return search(start, goals, space, Math.max(64, maxNodes), false, true, preferredProgress);
	}

	static List<Node> flightPathTowardAny(Node start, Collection<Node> goals, Space space,
			int maxNodes, double preferredProgress) {
		return search(start, goals, space, Math.max(64, maxNodes), true, true, preferredProgress);
	}

	private static List<Node> search(Node start, Collection<Node> rawGoals, Space space, int maxNodes,
			boolean flight, boolean allowPartial, double preferredProgress) {
		Set<Node> goals = new HashSet<>(rawGoals);
		if (goals.isEmpty()) return List.of();
		if (goals.contains(start)) return List.of(start);

		PriorityQueue<OpenNode> open = new PriorityQueue<>(Comparator.comparingDouble(OpenNode::score));
		Map<Node, Double> gScore = new HashMap<>();
		Map<Node, Node> parent = new HashMap<>();
		Set<Node> closed = new HashSet<>();
		gScore.put(start, 0.0D);
		double startingHeuristic = heuristicToAny(start, goals, flight);
		double minimumPartialProgress = Math.min(8.0D, Math.max(2.0D, preferredProgress * 0.25D));
		Node bestPartial = start;
		double bestPartialHeuristic = startingHeuristic;
		open.add(new OpenNode(start, startingHeuristic));

		int expanded = 0;
		while (!open.isEmpty() && expanded < maxNodes) {
			Node current = open.poll().node();
			if (!closed.add(current)) continue;
			expanded++;
			if (goals.contains(current)) return reconstruct(parent, current);
			double currentHeuristic = heuristicToAny(current, goals, flight);
			if (currentHeuristic + 1.0E-6D < bestPartialHeuristic) {
				bestPartial = current;
				bestPartialHeuristic = currentHeuristic;
			}
			if (allowPartial && startingHeuristic - currentHeuristic >= preferredProgress) {
				return reconstruct(parent, current);
			}

			List<Neighbor> neighbors = flight
					? flightNeighbors(current, start, space)
					: groundNeighbors(current, start, space);
			for (Neighbor neighbor : neighbors) {
				if (closed.contains(neighbor.node())) continue;
				double tentative = gScore.get(current) + neighbor.cost();
				if (tentative + 1.0E-6D >= gScore.getOrDefault(neighbor.node(), Double.POSITIVE_INFINITY)) continue;
				parent.put(neighbor.node(), current);
				gScore.put(neighbor.node(), tentative);
				open.add(new OpenNode(neighbor.node(), tentative + heuristicToAny(neighbor.node(), goals, flight)));
			}
		}
		if (allowPartial && startingHeuristic - bestPartialHeuristic >= minimumPartialProgress) {
			return reconstruct(parent, bestPartial);
		}
		return List.of();
	}

	private static List<Neighbor> groundNeighbors(Node current, Node origin, Space space) {
		List<Neighbor> out = new ArrayList<>(8);
		for (int[] direction : HORIZONTAL) {
			int dx = direction[0];
			int dz = direction[1];
			int nx = current.x + dx;
			int nz = current.z + dz;
			if (Math.abs(nx - origin.x) > GROUND_HORIZONTAL_HORIZON
					|| Math.abs(nz - origin.z) > GROUND_HORIZONTAL_HORIZON) continue;

			boolean diagonal = dx != 0 && dz != 0;
			if (diagonal && (!space.passable(current.x + dx, current.y, current.z)
					|| !space.passable(current.x, current.y, current.z + dz))) continue;

			int ny = resolveGroundY(nx, current.y, nz, space);
			if (ny == Integer.MIN_VALUE || Math.abs(ny - origin.y) > GROUND_VERTICAL_HORIZON
					|| space.hazardous(nx, ny, nz)) continue;
			double cost = diagonal ? Math.sqrt(2.0D) : 1.0D;
			if (ny > current.y) cost += 0.65D;
			if (ny < current.y) cost += (current.y - ny) * 0.18D;
			out.add(new Neighbor(new Node(nx, ny, nz), cost));
		}
		return out;
	}

	private static int resolveGroundY(int x, int y, int z, Space space) {
		if (space.standable(x, y, z)) return y;
		if (space.standable(x, y + 1, z)) return y + 1;
		for (int drop = 1; drop <= 3; drop++) {
			if (!space.passable(x, y - drop + 1, z)) break;
			if (space.standable(x, y - drop, z)) return y - drop;
		}
		return Integer.MIN_VALUE;
	}

	private static List<Neighbor> flightNeighbors(Node current, Node origin, Space space) {
		List<Neighbor> out = new ArrayList<>(26);
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					Node next = new Node(current.x + dx, current.y + dy, current.z + dz);
					if (Math.abs(next.x - origin.x) > FLIGHT_HORIZONTAL_HORIZON
							|| Math.abs(next.z - origin.z) > FLIGHT_HORIZONTAL_HORIZON
							|| Math.abs(next.y - origin.y) > FLIGHT_VERTICAL_HORIZON) continue;
					if (!space.passable(next.x, next.y, next.z) || space.hazardous(next.x, next.y, next.z)
							|| !flightTransitionClear(current, dx, dy, dz, space)) continue;
					double vertical = dy * 1.15D;
					out.add(new Neighbor(next, Math.sqrt(dx * dx + dz * dz + vertical * vertical)));
				}
			}
		}
		return out;
	}

	/** A diagonal flight edge is allowed only when every axis-aligned substep is collision-free. */
	private static boolean flightTransitionClear(Node current, int dx, int dy, int dz, Space space) {
		if (dx != 0 && !space.passable(current.x + dx, current.y, current.z)) return false;
		if (dy != 0 && !space.passable(current.x, current.y + dy, current.z)) return false;
		if (dz != 0 && !space.passable(current.x, current.y, current.z + dz)) return false;
		if (dx != 0 && dy != 0 && !space.passable(current.x + dx, current.y + dy, current.z)) return false;
		if (dx != 0 && dz != 0 && !space.passable(current.x + dx, current.y, current.z + dz)) return false;
		return dy == 0 || dz == 0 || space.passable(current.x, current.y + dy, current.z + dz);
	}

	private static double heuristicToAny(Node from, Set<Node> goals, boolean flight) {
		double best = Double.POSITIVE_INFINITY;
		for (Node goal : goals) best = Math.min(best, heuristic(from, goal, flight));
		return best;
	}

	private static double heuristic(Node from, Node to, boolean flight) {
		double dx = Math.abs(from.x - to.x);
		double dz = Math.abs(from.z - to.z);
		double dy = to.y - from.y;
		if (flight) {
			double vertical = Math.abs(dy) * 1.15D;
			return Math.sqrt(dx * dx + dz * dz + vertical * vertical);
		}
		double diagonal = Math.min(dx, dz);
		double straight = Math.max(dx, dz) - diagonal;
		double horizontal = diagonal * Math.sqrt(2.0D) + straight;
		double vertical = dy >= 0.0D ? dy * 0.65D : -dy * 0.18D;
		return horizontal + vertical;
	}

	private static List<Node> reconstruct(Map<Node, Node> parent, Node end) {
		List<Node> path = new ArrayList<>();
		for (Node cursor = end; cursor != null; cursor = parent.get(cursor)) path.add(cursor);
		java.util.Collections.reverse(path);
		return path;
	}

	private record Neighbor(Node node, double cost) {}
	private record OpenNode(Node node, double score) {}
}
