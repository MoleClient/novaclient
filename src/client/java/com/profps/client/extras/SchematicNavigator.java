package com.profps.client.extras;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Random;

/**
 * Walks the body along a planned route by publishing a {@link PlayerInput} and letting
 * vanilla physics move the player. Keys use hysteresis bands, sprint runs in stretches,
 * and arrival is tested across the tick's travel segment rather than its end position.
 */
final class SchematicNavigator {
	/** Ticks without measurable progress before trying to shake loose. */
	private static final int UNSTICK_TRIGGER_TICKS = 16;
	private static final int UNSTICK_BURST_TICKS = 9;
	/** Ticks without measurable progress before the route is declared dead. */
	private static final int STUCK_GIVE_UP_TICKS = 48;
	private static final double PROGRESS_EPSILON = 0.025D;
	/** Waypoints one tick may consume, so speed cannot outrun the cursor. */
	private static final int MAX_WAYPOINT_ADVANCE = 3;
	/** Distance from the end of the route at which sprint is released. */
	private static final double SPRINT_RELEASE_DISTANCE = 2.6D;
	/** Distance at which forward is released and the body coasts in. */
	private static final double COAST_DISTANCE = 0.55D;

	enum State {
		/** No route loaded. */
		IDLE,
		/** Still walking. */
		TRAVELLING,
		/** Standing on the final node. */
		ARRIVED,
		/** The route cannot be walked; ask for a new one. */
		STUCK
	}

	private final Random random;

	private List<SchematicPathfinder.Node> path = List.of();
	private int pathIndex;
	private boolean arriveSneaking;

	private PlayerInput input = PlayerInput.DEFAULT;
	private Vec3d lookGoal;
	private float lookSpeed = 1.65F;

	// Progress / stuck tracking.
	private double lastWaypointDistance = Double.POSITIVE_INFINITY;
	private Vec3d lastPosition;
	private int stuckTicks;
	private int unstickTicks;
	private int unstickStrafe;

	// Key hysteresis: the previous tick's decision feeds into this one.
	private boolean heldForward;
	private boolean heldBackward;
	private boolean heldLeft;
	private boolean heldRight;

	// Humanization state.
	private boolean sprintPhase = true;
	private int sprintDwell;
	private int pauseTicks;

	SchematicNavigator(Random random) {
		this.random = random;
	}

	/** Loads a route; {@code arriveSneaking} crouches for the last stretch. */
	void startRoute(List<SchematicPathfinder.Node> route, boolean arriveSneaking) {
		this.path = route == null ? List.of() : route;
		// Node 0 is the cell the body already occupies.
		this.pathIndex = Math.min(1, path.size());
		this.arriveSneaking = arriveSneaking;
		this.lastWaypointDistance = Double.POSITIVE_INFINITY;
		this.lastPosition = null;
		this.stuckTicks = 0;
		this.unstickTicks = 0;
		this.pauseTicks = 0;
		releaseKeys();
	}

	/**
	 * Keeps the route but forgets the last position, for a tick the navigator did not drive.
	 * A stale position would make the next arrival segment sweep up several waypoints.
	 */
	void pause() {
		lastPosition = null;
		lastWaypointDistance = Double.POSITIVE_INFINITY;
		releaseKeys();
	}

	void clear() {
		path = List.of();
		pathIndex = 0;
		input = PlayerInput.DEFAULT;
		lookGoal = null;
		releaseKeys();
	}

	boolean isRouting() {
		return pathIndex < path.size();
	}

	PlayerInput input() {
		return input;
	}

	/** Where the view should be steered this frame, or null to leave it alone. */
	Vec3d lookGoal() {
		return lookGoal;
	}

	float lookSpeed() {
		return lookSpeed;
	}

	/** The final node of the loaded route, or null when there is none. */
	SchematicPathfinder.Node destination() {
		return path.isEmpty() ? null : path.getLast();
	}

	State tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (path.isEmpty()) return State.IDLE;

		Vec3d position = player.getEntityPos();
		Vec3d previous = lastPosition == null ? position : lastPosition;
		lastPosition = position;

		// Consume every waypoint this tick's travel segment passed through.
		int advanced = 0;
		while (pathIndex < path.size() && advanced < MAX_WAYPOINT_ADVANCE
				&& reached(previous, position, path.get(pathIndex), pathIndex == path.size() - 1)) {
			pathIndex++;
			advanced++;
			stuckTicks = 0;
			unstickTicks = 0;
			lastWaypointDistance = Double.POSITIVE_INFINITY;
			if (pathIndex < path.size() && random.nextInt(50) == 0) pauseTicks = 2 + random.nextInt(4);
		}

		if (pathIndex >= path.size()) {
			input = arriveSneaking
					? new PlayerInput(false, false, false, false, false, true, false)
					: PlayerInput.DEFAULT;
			lookGoal = null;
			releaseKeys();
			return State.ARRIVED;
		}

		if (pauseTicks > 0) {
			pauseTicks--;
			input = PlayerInput.DEFAULT;
			releaseKeys();
			return State.TRAVELLING;
		}

		SchematicPathfinder.Node waypoint = path.get(pathIndex);
		Vec3d point = new Vec3d(waypoint.x() + 0.5D, waypoint.y(), waypoint.z() + 0.5D);
		double horizontalSq = horizontalDistanceSq(position, point);
		double vertical = point.y - player.getY();
		double distance = Math.sqrt(horizontalSq + vertical * vertical);

		if (distance + PROGRESS_EPSILON < lastWaypointDistance) {
			lastWaypointDistance = distance;
			stuckTicks = 0;
		} else if (++stuckTicks == UNSTICK_TRIGGER_TICKS) {
			unstickTicks = UNSTICK_BURST_TICKS;
			unstickStrafe = random.nextBoolean() ? 1 : -1;
		} else if (stuckTicks > STUCK_GIVE_UP_TICKS) {
			return State.STUCK;
		}

		steerLook(player, point);
		input = keysFor(player, point, vertical, remainingDistance(position));
		return State.TRAVELLING;
	}

	/** Looks a little way down the route rather than at the next waypoint; pitch is clamped. */
	private void steerLook(ClientPlayerEntity player, Vec3d fallback) {
		Vec3d steering = lookahead(player, fallback);
		lookGoal = new Vec3d(steering.x,
				player.getEyeY() + MathHelper.clamp(steering.y - player.getY(), -0.75D, 0.75D),
				steering.z);
		lookSpeed = 1.65F;
	}

	private Vec3d lookahead(ClientPlayerEntity player, Vec3d fallback) {
		double budget = 2.8D;
		Vec3d previous = player.getEntityPos();
		Vec3d result = fallback;
		for (int i = pathIndex; i < path.size(); i++) {
			SchematicPathfinder.Node node = path.get(i);
			Vec3d point = new Vec3d(node.x() + 0.5D, node.y(), node.z() + 0.5D);
			double segment = previous.distanceTo(point);
			if (segment > budget) {
				return previous.lerp(point, budget / Math.max(1.0E-6D, segment));
			}
			result = point;
			budget -= segment;
			previous = point;
			if (budget <= 0.0D) break;
		}
		return result;
	}

	/** Movement keys for this tick; every direction runs through a hysteresis band. */
	private PlayerInput keysFor(ClientPlayerEntity player, Vec3d point, double vertical, double remaining) {
		double desiredYaw = Math.toDegrees(Math.atan2(point.z - player.getZ(), point.x - player.getX())) - 90.0D;
		double yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		double absolute = Math.abs(yawError);

		heldForward = hold(heldForward, absolute, 60.0D, 75.0D);
		heldBackward = hold(heldBackward, 180.0D - absolute, 60.0D, 75.0D);
		heldLeft = holdSigned(heldLeft, -yawError);
		heldRight = holdSigned(heldRight, yawError);

		boolean jump = vertical > 0.65D && player.isOnGround();
		boolean sneak = arriveSneaking && remaining < SPRINT_RELEASE_DISTANCE;

		if (unstickTicks > 0) {
			// Hold the heading, add a hop and a sidestep, drop sprint.
			unstickTicks--;
			return new PlayerInput(heldForward, heldBackward,
					unstickStrafe < 0, unstickStrafe > 0, true, sneak, false);
		}

		// Coast the last fraction of a block instead of stopping instantly.
		boolean forward = heldForward && !(remaining < COAST_DISTANCE);
		boolean sprint = forward && !heldBackward && !sneak
				&& !player.isTouchingWater()
				&& remaining > SPRINT_RELEASE_DISTANCE
				&& sprintPhase();

		return new PlayerInput(forward, heldBackward, heldLeft, heldRight, jump, sneak, sprint);
	}

	/** Sprint held in stretches with occasional short releases. */
	private boolean sprintPhase() {
		if (--sprintDwell > 0) return sprintPhase;
		if (sprintPhase) {
			sprintPhase = false;
			sprintDwell = 4 + random.nextInt(9);
		} else {
			sprintPhase = true;
			sprintDwell = 40 + random.nextInt(90);
		}
		return sprintPhase;
	}

	private static boolean hold(boolean previous, double value, double pressBelow, double releaseAbove) {
		return previous ? value < releaseAbove : value < pressBelow;
	}

	/** Strafe band: press outside 22.5°, release only once back inside 15°. */
	private static boolean holdSigned(boolean previous, double signedError) {
		if (signedError <= 0.0D) return false;
		return previous ? signedError > 15.0D && signedError < 165.0D
				: signedError > 22.5D && signedError < 157.5D;
	}

	private void releaseKeys() {
		heldForward = false;
		heldBackward = false;
		heldLeft = false;
		heldRight = false;
	}

	/**
	 * Arrival tested across the tick's whole travel segment; the final node uses a tighter
	 * horizontal band than intermediate ones.
	 */
	private boolean reached(Vec3d previous, Vec3d position, SchematicPathfinder.Node waypoint, boolean finalNode) {
		Vec3d point = new Vec3d(waypoint.x() + 0.5D, waypoint.y(), waypoint.z() + 0.5D);
		Vec3d closest = closestOnSegment(previous, position, point);
		double horizontal = finalNode ? 0.16D : 0.30D;
		// The vertical band is loose because a node's Y is the body cell, which sits up to
		// a block above the surface for thin blocks like carpet or a snow layer.
		return horizontalDistanceSq(closest, point) < horizontal
				&& Math.abs(point.y - closest.y) < 1.05D;
	}

	private double remainingDistance(Vec3d position) {
		double distance = 0.0D;
		Vec3d previous = position;
		for (int i = pathIndex; i < path.size(); i++) {
			SchematicPathfinder.Node node = path.get(i);
			Vec3d next = new Vec3d(node.x() + 0.5D, node.y(), node.z() + 0.5D);
			distance += previous.distanceTo(next);
			previous = next;
		}
		return distance;
	}

	private static Vec3d closestOnSegment(Vec3d start, Vec3d end, Vec3d target) {
		Vec3d segment = end.subtract(start);
		double lengthSq = segment.lengthSquared();
		if (lengthSq < 1.0E-9D) return end;
		double t = MathHelper.clamp(target.subtract(start).dotProduct(segment) / lengthSq, 0.0D, 1.0D);
		return start.add(segment.multiply(t));
	}

	private static double horizontalDistanceSq(Vec3d a, Vec3d b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return dx * dx + dz * dz;
	}
}
