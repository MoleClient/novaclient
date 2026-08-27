package com.profps.client.extras;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Random;

/**
 * Walks the body along a planned route using ordinary movement keys.
 *
 * <p>Nothing here writes velocity or position. It publishes a {@link PlayerInput}
 * — the same seven booleans a keyboard produces — and lets vanilla physics do
 * the rest, so the resulting movement is legal by construction.
 *
 * <p>Most of the work is in <em>not</em> looking like a program. Three things
 * give a naive path-follower away, and each has an answer here:
 *
 * <ul>
 * <li><b>Key chatter.</b> Deriving keys from a yaw error with a hard threshold
 * makes left/right flip every single tick whenever the error happens to sit on
 * the boundary. Real hands cannot do that. Every key here has a hysteresis
 * band: a wider angle is required to release a key than to press it.</li>
 * <li><b>A perfectly held sprint.</b> Sprint held unbroken for a thousand ticks
 * is not what a player produces. Sprint runs in dwelling stretches with
 * occasional short releases.</li>
 * <li><b>Binary arrival.</b> Stopping dead on a coordinate is a teleport in
 * disguise. Sprint drops, then forward drops, and the body coasts the last
 * fraction of a block in.</li>
 * </ul>
 *
 * <p>Arrival itself is tested over the tick's whole travel <em>segment</em>
 * rather than its sampled end position: a sprinting body covers more than a
 * third of a block per tick, so a radius test around a waypoint can be stepped
 * clean over, leaving the navigator convinced it never arrived while the body
 * sails past the build.
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

	// Key hysteresis — the previous tick's decision is an input to this one.
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

	/**
	 * Loads a route. {@code arriveSneaking} makes the last stretch a crouch,
	 * which is what a stand on the rim of a drop needs.
	 */
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
	 * Keeps the route but forgets where the body was, for a tick the navigator
	 * did not drive. Arrival is measured across the segment travelled since the
	 * last tick, so a stale position turns a pause into one enormous phantom
	 * segment that sweeps up several waypoints at once — including the last —
	 * and reports arrival with the body still metres away.
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
			// A player crossing a corner does not pivot instantly; a rare beat
			// of hesitation is cheaper than looking metronomic.
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
			// A lip, a fence post, a corner the route did not model. Shake free
			// before throwing away a route that is otherwise sound.
			unstickTicks = UNSTICK_BURST_TICKS;
			unstickStrafe = random.nextBoolean() ? 1 : -1;
		} else if (stuckTicks > STUCK_GIVE_UP_TICKS) {
			return State.STUCK;
		}

		steerLook(player, point);
		input = keysFor(player, point, vertical, remainingDistance(position));
		return State.TRAVELLING;
	}

	// ── Steering ───────────────────────────────────────────────────────────────

	/**
	 * Looks a little way down the route rather than at the next waypoint, so
	 * corners are rounded instead of pivoted through. Pitch is clamped: a body
	 * walking a staircase should not be staring at its own feet.
	 */
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

	/**
	 * Movement keys for this tick. Every direction runs through a hysteresis
	 * band so a yaw error resting on a threshold cannot make the keys flutter.
	 */
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
			// Hold the heading, add a hop and a sidestep, drop sprint so the
			// body can round whatever the route walked into.
			unstickTicks--;
			return new PlayerInput(heldForward, heldBackward,
					unstickStrafe < 0, unstickStrafe > 0, true, sneak, false);
		}

		// Coast the last fraction of a block rather than stopping on a dime.
		boolean forward = heldForward && !(remaining < COAST_DISTANCE);
		boolean sprint = forward && !heldBackward && !sneak
				&& !player.isTouchingWater()
				&& remaining > SPRINT_RELEASE_DISTANCE
				&& sprintPhase();

		return new PlayerInput(forward, heldBackward, heldLeft, heldRight, jump, sneak, sprint);
	}

	/**
	 * Sprint held in stretches with occasional short releases. A sprint bit
	 * that is simply always true for the length of a build is a stronger tell
	 * than the movement itself.
	 */
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

	// ── Geometry ───────────────────────────────────────────────────────────────

	/**
	 * Arrival tested across the tick's whole travel segment. The final node
	 * keeps a tight band because the placement phase depends on standing where
	 * the solver proved the placement from; intermediate nodes accept a wider
	 * pass, since the lookahead steering rounds them off anyway.
	 */
	private boolean reached(Vec3d previous, Vec3d position, SchematicPathfinder.Node waypoint, boolean finalNode) {
		Vec3d point = new Vec3d(waypoint.x() + 0.5D, waypoint.y(), waypoint.z() + 0.5D);
		Vec3d closest = closestOnSegment(previous, position, point);
		double horizontal = finalNode ? 0.16D : 0.30D;
		// Generous vertically on purpose. A node's Y is the cell the body box
		// occupies, which for anything thinner than a slab underfoot — carpet,
		// a single snow layer — sits almost a full block above where the body
		// actually rests. A tight band there is unreachable by construction.
		// Waypoints stay distinct because the horizontal band is what separates
		// them: a ground route never stacks two nodes in one column.
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
