package com.profps.client.data;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Labels each tick with what the player is actually doing, and groups consecutive ticks of the
 * same label into numbered segments.
 *
 * <p>Without this the corpus is one undifferentiated stream and every query over it starts with
 * "find the fights". With it, pulling every PvP segment or every traversal segment is a filter on
 * two columns. Segments also make gaps legible: a run of ticks dropped by the module gate starts a
 * fresh segment, so nothing downstream ever reads across a hole as if it were continuous motion.
 *
 * <p>Combat is held for a few seconds past the last exchange rather than being decided per tick.
 * A fight is not over the instant nobody is swinging — the disengage, the reposition and the heal
 * are part of the same behaviour, and cutting them off mid-motion would teach a model that fights
 * end abruptly.
 */
final class ActivityClassifier {
	static final String COMBAT = "combat";
	static final String MINING = "mining";
	static final String BUILDING = "building";
	static final String TRAVELING = "traveling";
	static final String FALLING = "falling";
	static final String SWIMMING = "swimming";
	static final String RIDING = "riding";
	static final String IDLE = "idle";
	static final String MENU = "menu";

	/** How long a fight stays a fight after the last exchange. */
	private static final long COMBAT_HOLD_MS = 3_000L;
	private static final long BUILD_HOLD_MS = 1_000L;
	/** A target has to be at least this close to count as being in a fight with you. */
	private static final double ENGAGE_RANGE = 8.0D;
	private static final double MOVING_SPEED = 0.03D;

	private String activity = IDLE;
	private int segment;
	private long segmentStartMs;
	private long lastCombatMs;
	private long lastBuildMs;
	private long lastTick = Long.MIN_VALUE;

	String activity() {
		return activity;
	}

	int segment() {
		return segment;
	}

	long msInActivity(long nowMs) {
		return nowMs - segmentStartMs;
	}

	/** True while the player is close enough to somebody who is fighting back to call it PvP. */
	private boolean pvp;

	boolean pvp() {
		return pvp;
	}

	/**
	 * Recomputes the label. {@code tick} is the recorder's monotonic index — a jump in it means
	 * rows were dropped in between, which forces a new segment.
	 */
	void update(MinecraftClient client, ClientPlayerEntity self, List<Entity> nearby,
			Vec3d velocity, long tick, long nowMs, List<String> events) {
		if (events.contains("attack") || events.contains("damaged")) lastCombatMs = nowMs;
		if (events.contains("place")) lastBuildMs = nowMs;

		Entity threat = nearestThreat(self, nearby);
		pvp = threat instanceof PlayerEntity;
		if (threat != null && nowMs - lastCombatMs < COMBAT_HOLD_MS) lastCombatMs = nowMs;

		String next = classify(client, self, threat, velocity, nowMs);
		boolean gap = lastTick != Long.MIN_VALUE && tick != lastTick + 1;
		if (gap || !next.equals(activity)) {
			segment++;
			segmentStartMs = nowMs;
			activity = next;
		}
		lastTick = tick;
	}

	private String classify(MinecraftClient client, ClientPlayerEntity self, Entity threat,
			Vec3d velocity, long nowMs) {
		if (client.currentScreen != null) return MENU;
		if (self.hasVehicle()) return RIDING;
		if (threat != null && nowMs - lastCombatMs < COMBAT_HOLD_MS) return COMBAT;
		if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) return MINING;
		if (nowMs - lastBuildMs < BUILD_HOLD_MS) return BUILDING;
		if (self.isTouchingWater() || self.isSwimming()) return SWIMMING;
		if (!self.isOnGround() && self.fallDistance > 1.0F) return FALLING;
		double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		return speed > MOVING_SPEED ? TRAVELING : IDLE;
	}

	/** Closest living thing inside engage range, preferring players over mobs. */
	private static Entity nearestThreat(ClientPlayerEntity self, List<Entity> nearby) {
		Entity best = null;
		double bestScore = Double.MAX_VALUE;
		Vec3d eye = self.getEyePos();
		for (Entity entity : nearby) {
			double distance = Math.sqrt(entity.getEntityPos().squaredDistanceTo(eye));
			if (distance > ENGAGE_RANGE) continue;
			// Players outrank mobs at any distance inside the range: a skeleton across the room
			// should not relabel a duel as PvE.
			double score = entity instanceof PlayerEntity ? distance : distance + ENGAGE_RANGE;
			if (score < bestScore) {
				bestScore = score;
				best = entity;
			}
		}
		return best;
	}
}
