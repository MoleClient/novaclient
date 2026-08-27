package com.profps.client.data;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Records one row of player state per client tick and hands it to {@link ContributionUploader}.
 *
 * <p>Runs from {@code firePreMovement}, the tail of {@code handleInputEvents()}, after the keyboard
 * is read and before the player tick sends movement. Positions are relative to a per-session origin
 * unless the player opted into location data.
 */
public final class DataContribution {
	/** Bump when the field order below changes. Rows are positional. */
	static final int SCHEMA = 3;

	private static final int MAX_TRACKED = 4;
	private static final double TRACK_RADIUS = 32.0D;

	private static DataContribution instance;

	/**
	 * Positional field names for the local-player row. Order is the wire format: append to the end
	 * and bump {@link #SCHEMA}, never insert into the middle.
	 */
	static final String[] FIELDS = {
			"tick", "ms",
			// Offset from the session origin.
			"rel_x", "rel_y", "rel_z",
			"dx", "dy", "dz",
			"vx", "vy", "vz", "speed",
			"yaw", "pitch", "head_yaw", "body_yaw", "d_yaw", "d_pitch",
			"on_ground", "h_collide", "v_collide", "fall_dist", "climbing",
			"in_water", "submerged", "in_lava", "swimming", "gliding", "jumping",
			"sprint", "sneak", "using_item", "use_ticks", "blocking",
			"health", "absorption", "food", "saturation", "air", "hurt_time",
			// Raw key state this tick.
			"key_forward", "key_back", "key_left", "key_right",
			"key_jump", "key_sneak", "key_sprint", "key_attack", "key_use",
			// Input the body was given. Lags key_* by one tick: written during the previous player tick.
			"eff_forward", "eff_back", "eff_left", "eff_right", "eff_jump", "eff_sneak", "eff_sprint",
			// Computed from two samples aligned to the same tick, not from the two columns above.
			"overridden",
			"mv_side", "mv_fwd",
			"slot", "main_item", "off_item", "attack_cd",
			"ping", "dim", "light",
			"block_below", "block_feet", "block_head",
			"ledge_n", "ledge_e", "ledge_s", "ledge_w", "wall_dist", "head_room",
			"near_count", "player_count",
			// Zero unless the player opted into location data; the batch header says which.
			"abs_x", "abs_y", "abs_z",
			// Activity label plus a segment id that also breaks across any gap the module gate left.
			"activity", "segment", "ms_in_activity", "pvp", "threat_dist"
	};

	/**
	 * Positional field names for each tracked nearby entity. Same append-only rule.
	 *
	 * <p>Slots are sorted players-first then by distance, so {@code id} is what identifies an entity
	 * across ticks when slots reorder.
	 */
	static final String[] ENTITY_FIELDS = {
			"id", "type", "is_player", "dx", "dy", "dz", "dist",
			"vx", "vy", "vz",
			"yaw", "pitch", "bearing", "facing_us",
			"health", "on_ground", "sprint", "sneak", "using", "blocking", "hurt", "swinging"
	};

	private final ProFPSConfig config;
	private final ContributionUploader uploader;
	private final ContributionGate gate = new ContributionGate();
	private final ActivityClassifier activity = new ActivityClassifier();

	private ClientWorld sessionWorld;
	private long sessionStartMs;
	private long tickIndex;
	private Vec3d origin;

	// Edge-detection state: events are derived from state transitions, not from call-site hooks.
	private Vec3d lastPos;
	private float lastYaw;
	private float lastPitch;
	private boolean lastOnGround = true;
	private boolean lastSwinging;
	private boolean lastUsing;
	private boolean lastBreaking;
	private int lastHurtTime;
	private int lastSlot = -1;
	/** Previous tick's raw key state, so {@code overridden} compares against the same tick's input. */
	private boolean[] lastKeys;
	private long recorded;
	private long skipped;
	/** Last gate verdict, so only transitions are logged. */
	private boolean lastBlocked;
	private final List<String> pendingEvents = new ArrayList<>(4);

	private DataContribution(ProFPSConfig config) {
		this.config = config;
		this.uploader = new ContributionUploader(config);
	}

	public static void init(ProFPSConfig config) {
		if (instance == null) instance = new DataContribution(config);
	}

	public static DataContribution instance() {
		return instance;
	}

	/** Called from the attack path when a swing lands. */
	public static void noteAttack() {
		if (instance != null) instance.pendingEvents.add("attack");
	}

	/** Called from the block-use path; flips the activity label to building. */
	public static void noteBlockPlace() {
		if (instance != null) instance.pendingEvents.add("place");
	}

	/** Seals the session's last batch. Called on disconnect; the JVM exit path spools separately. */
	public static void endSession() {
		if (instance != null) {
			instance.uploader.endSession();
			instance.sessionWorld = null;
		}
	}

	public void tick(MinecraftClient client) {
		if (!config.dataContribution) {
			// Mid-session opt-out drops the buffer instead of shipping it.
			if (sessionWorld != null) {
				uploader.discard();
				sessionWorld = null;
			}
			pendingEvents.clear();
			return;
		}
		ClientPlayerEntity self = client.player;
		ClientWorld world = client.world;
		if (self == null || world == null) {
			pendingEvents.clear();
			return;
		}
		if (world != sessionWorld) {
			beginSession(client, self, world);
		}

		try {
			sample(client, self, world);
		} catch (RuntimeException exception) {
			// Drop the bad row rather than let the recorder crash the client.
			ProFPS.LOGGER.warn("Data contribution row failed; skipping this tick.", exception);
		} finally {
			pendingEvents.clear();
			tickIndex++;
		}
	}

	/**
	 * Classifies the tick, then decides whether to keep it. Deltas advance whether or not the row is
	 * kept, so a delta never spans a gap.
	 */
	private void sample(MinecraftClient client, ClientPlayerEntity self, ClientWorld world) {
		Vec3d pos = self.getEntityPos();
		Vec3d velocity = self.getVelocity();
		PlayerInput input = self.input == null ? PlayerInput.DEFAULT : self.input.playerInput;
		GameOptions keys = client.options;
		boolean[] pressed = {
				keys.forwardKey.isPressed(), keys.backKey.isPressed(),
				keys.leftKey.isPressed(), keys.rightKey.isPressed(),
				keys.jumpKey.isPressed(), keys.sneakKey.isPressed(), keys.sprintKey.isPressed()
		};

		// input.playerInput was written by KeyboardInput.tick() during the previous player tick, so
		// it must be compared against the previous tick's keys, not the keys held now.
		boolean[] applied = {
				input.forward(), input.backward(), input.left(), input.right(),
				input.jump(), input.sneak(), input.sprint()
		};
		boolean overridden = inputOverridden(lastKeys, applied);
		boolean[] previousKeys = lastKeys;
		lastKeys = pressed;

		detectEdges(client, self, velocity);
		List<Entity> tracked = nearby(world, self);
		long nowMs = System.currentTimeMillis();
		activity.update(client, self, tracked, velocity, tickIndex, nowMs, pendingEvents);
		gate.update(client, config, overridden);

		boolean blocked = !gate.allows(activity.activity());
		// Log both sides of every transition rather than sampling blocked ticks.
		if (blocked != lastBlocked) {
			ProFPS.LOGGER.info("Data contribution {}: reason={} activity={} overridden={}{} kept={} skipped={}",
					blocked ? "PAUSED" : "resumed",
					gate.reason(), activity.activity(), overridden,
					overridden ? " " + describeOverride(previousKeys, applied) : "",
					recorded, skipped);
			lastBlocked = blocked;
		}
		if (blocked) {
			// Not recorded, but the deltas still advance so the next kept row is not stale.
			skipped++;
			lastPos = pos;
			lastYaw = self.getYaw();
			lastPitch = self.getPitch();
			return;
		}
		recorded++;
		uploader.submit(row(client, self, world, tracked, input, pressed, overridden, nowMs));
	}

	/** Null while recording, otherwise why recording is paused. */
	public String pausedReason() {
		if (!config.dataContribution) return "Turned off";
		String reason = gate.reason();
		return reason == null ? null : reason + " is on";
	}

	/** Live counters for {@code /nova data}. */
	public List<String> status() {
		String paused = pausedReason();
		List<String> lines = new ArrayList<>();
		lines.add(paused == null ? "Recording" : "Paused — " + paused);
		lines.add("This session: " + recorded + " ticks kept, " + skipped + " skipped"
				+ (skipped > 0 ? " (module active)" : ""));
		lines.add("Activity: " + activity.activity() + " · segment " + activity.segment());
		lines.add("Uploads: " + uploader.batchesSent() + " ok, " + uploader.batchesFailed()
				+ " failed, " + uploader.rowsSent() + " rows sent");
		lines.add("Queued: " + uploader.queued() + " · spooled: " + ContributionUploader.spooled()
				+ " · dropped: " + uploader.dropped());
		lines.add("Endpoint: " + config.dataContributionEndpoint + " → " + uploader.lastStatus());
		lines.add("Location data: " + (config.dataContributionLocation ? "on" : "off"));
		return lines;
	}

	private void beginSession(MinecraftClient client, ClientPlayerEntity self, ClientWorld world) {
		uploader.flush();
		sessionWorld = world;
		sessionStartMs = System.currentTimeMillis();
		tickIndex = 0L;
		origin = self.getEntityPos();
		lastPos = origin;
		lastYaw = self.getYaw();
		lastPitch = self.getPitch();
		lastOnGround = self.isOnGround();
		lastSlot = self.getInventory().getSelectedSlot();
		lastHurtTime = self.hurtTime;
		lastSwinging = false;
		lastUsing = false;
		uploader.beginSession(UUID.randomUUID().toString(), pseudonym(self), serverLabel(client));
	}

	/** Stable per-install identity derived from a local salt and the account UUID. */
	private String pseudonym(ClientPlayerEntity self) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(config.dataContributionSalt.getBytes(StandardCharsets.UTF_8));
			digest.update(self.getUuid().toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
		} catch (Exception exception) {
			return "anonymous";
		}
	}

	/** Non-null only when location data is enabled. */
	private String serverLabel(MinecraftClient client) {
		if (!config.dataContributionLocation) return null;
		if (client.isInSingleplayer()) return "singleplayer";
		var entry = client.getCurrentServerEntry();
		return entry == null ? "unknown" : entry.address;
	}

	private String row(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
			List<Entity> tracked, PlayerInput input, boolean[] pressed, boolean overridden, long nowMs) {
		Vec3d pos = self.getEntityPos();
		Vec3d delta = lastPos == null ? Vec3d.ZERO : pos.subtract(lastPos);
		Vec3d velocity = self.getVelocity();
		Vec3d rel = pos.subtract(origin);
		float yaw = self.getYaw();
		float pitch = self.getPitch();

		Vec2f movement = self.input == null ? Vec2f.ZERO : self.input.getMovementInput();
		BlockPos feet = self.getBlockPos();

		RowWriter w = new RowWriter(FIELDS.length, uploader);
		w.n(tickIndex);
		w.n(System.currentTimeMillis() - sessionStartMs);
		w.n(rel.x); w.n(rel.y); w.n(rel.z);
		w.n(delta.x); w.n(delta.y); w.n(delta.z);
		w.n(velocity.x); w.n(velocity.y); w.n(velocity.z);
		w.n(Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z));
		w.n(yaw); w.n(pitch); w.n(self.headYaw); w.n(self.bodyYaw);
		w.n(MathHelper.wrapDegrees(yaw - lastYaw)); w.n(pitch - lastPitch);
		w.b(self.isOnGround()); w.b(self.horizontalCollision); w.b(self.verticalCollision);
		w.n(self.fallDistance); w.b(self.isClimbing());
		w.b(self.isTouchingWater()); w.b(self.isSubmergedInWater()); w.b(self.isInLava());
		w.b(self.isSwimming()); w.b(self.isGliding()); w.b(self.isJumping());
		w.b(self.isSprinting()); w.b(self.isSneaking()); w.b(self.isUsingItem());
		w.n(self.getItemUseTime()); w.b(self.isBlocking());
		w.n(self.getHealth()); w.n(self.getAbsorptionAmount());
		w.n(self.getHungerManager().getFoodLevel()); w.n(self.getHungerManager().getSaturationLevel());
		w.n(self.getAir()); w.n(self.hurtTime);
		// The snapshot sample() took, not a fresh read, so it agrees with the overridden flag.
		for (boolean held : pressed) w.b(held);
		w.b(client.options.attackKey.isPressed()); w.b(client.options.useKey.isPressed());
		w.b(input.forward()); w.b(input.backward()); w.b(input.left()); w.b(input.right());
		w.b(input.jump()); w.b(input.sneak()); w.b(input.sprint());
		w.b(overridden);
		w.n(movement.x); w.n(movement.y);
		w.n(self.getInventory().getSelectedSlot());
		w.s(itemId(self.getMainHandStack())); w.s(itemId(self.getOffHandStack()));
		w.n(self.getAttackCooldownProgress(0.0F));
		w.n(ping(client, self));
		w.s(world.getRegistryKey().getValue().toString());
		w.n(world.getLightLevel(feet));
		w.s(blockId(world, feet.down())); w.s(blockId(world, feet)); w.s(blockId(world, feet.up()));
		w.n(drop(world, feet, 0, -1)); w.n(drop(world, feet, 1, 0));
		w.n(drop(world, feet, 0, 1)); w.n(drop(world, feet, -1, 0));
		w.n(wallDistance(world, self, pos));
		w.n(headRoom(world, feet));

		int players = 0;
		double threat = -1.0D;
		for (Entity entity : tracked) {
			if (entity instanceof PlayerEntity) players++;
			double distance = Math.sqrt(entity.getEntityPos().squaredDistanceTo(self.getEyePos()));
			if (threat < 0.0D || distance < threat) threat = distance;
		}
		w.n(tracked.size()); w.n(players);

		boolean location = config.dataContributionLocation;
		w.n(location ? pos.x : 0.0D);
		w.n(location ? pos.y : 0.0D);
		w.n(location ? pos.z : 0.0D);

		w.s(activity.activity());
		w.n(activity.segment());
		w.n(activity.msInActivity(nowMs));
		w.b(activity.pvp());
		w.n(threat);

		lastPos = pos;
		lastYaw = yaw;
		lastPitch = pitch;
		return w.finish(tickIndex, entityRows(client, self, pos, tracked), pendingEvents);
	}

	private static final String[] INPUT_NAMES = {"forward","back","left","right","jump","sneak","sprint"};

	/** Names which inputs disagreed and in which direction. */
	static String describeOverride(boolean[] lastKeys, boolean[] applied) {
		if (lastKeys == null) return "[no previous tick]";
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < lastKeys.length; i++) {
			if (lastKeys[i] == applied[i]) continue;
			if (out.length() > 1) out.append(", ");
			out.append(INPUT_NAMES[i]).append(lastKeys[i] ? ": held but not applied" : ": applied but not held");
		}
		return out.append(']').toString();
	}

	/**
	 * Whether a module rewrote the movement input. Both arguments must describe the same tick.
	 *
	 * @param lastKeys previous tick's raw keyboard state, or null on the very first tick
	 * @param applied  the input actually given to the body for that same tick
	 */
	static boolean inputOverridden(boolean[] lastKeys, boolean[] applied) {
		if (lastKeys == null) return false;
		for (int i = 0; i < lastKeys.length; i++) {
			if (lastKeys[i] != applied[i]) return true;
		}
		return false;
	}

	/** Turns state transitions the client can already see into the sparse event list. */
	private void detectEdges(MinecraftClient client, ClientPlayerEntity self, Vec3d velocity) {
		if (self.handSwinging && !lastSwinging) pendingEvents.add("swing");
		lastSwinging = self.handSwinging;

		boolean breaking = client.interactionManager != null && client.interactionManager.isBreakingBlock();
		if (breaking && !lastBreaking) pendingEvents.add("break_start");
		// Falling edge covers both finishing the block and releasing it.
		if (!breaking && lastBreaking) pendingEvents.add("break_end");
		lastBreaking = breaking;

		if (self.isUsingItem() && !lastUsing) pendingEvents.add("use_start");
		if (!self.isUsingItem() && lastUsing) pendingEvents.add("use_end");
		lastUsing = self.isUsingItem();

		if (self.hurtTime > lastHurtTime) pendingEvents.add("damaged");
		lastHurtTime = self.hurtTime;

		if (lastOnGround && !self.isOnGround() && velocity.y > 0.0D) pendingEvents.add("jump");
		if (!lastOnGround && self.isOnGround()) pendingEvents.add("land");
		lastOnGround = self.isOnGround();

		int slot = self.getInventory().getSelectedSlot();
		if (slot != lastSlot && lastSlot >= 0) pendingEvents.add("slot_change");
		lastSlot = slot;
	}

	/** The nearest few living entities, players first. */
	private List<Entity> nearby(ClientWorld world, ClientPlayerEntity self) {
		Box box = self.getBoundingBox().expand(TRACK_RADIUS);
		List<Entity> found = new ArrayList<>(world.getOtherEntities(self, box,
				entity -> entity instanceof LivingEntity && entity.isAlive()));
		Vec3d eye = self.getEyePos();
		found.sort(Comparator
				.comparingInt((Entity e) -> e instanceof PlayerEntity ? 0 : 1)
				.thenComparingDouble(e -> e.getEntityPos().squaredDistanceTo(eye)));
		return found.size() > MAX_TRACKED ? found.subList(0, MAX_TRACKED) : found;
	}

	private List<String> entityRows(MinecraftClient client, ClientPlayerEntity self,
			Vec3d selfPos, List<Entity> tracked) {
		List<String> rows = new ArrayList<>(tracked.size());
		for (Entity entity : tracked) {
			Vec3d pos = entity.getEntityPos();
			Vec3d rel = pos.subtract(selfPos);
			Vec3d velocity = entity.getVelocity();
			double dist = rel.length();
			// Bearing is the target's direction in the player's own frame, not world axes.
			double bearing = MathHelper.wrapDegrees(
					Math.toDegrees(Math.atan2(-rel.x, rel.z)) - self.getYaw());
			double facing = MathHelper.wrapDegrees(
					Math.toDegrees(Math.atan2(rel.x, -rel.z)) - entity.getYaw());

			RowWriter w = new RowWriter(ENTITY_FIELDS.length, uploader);
			w.n(entity.getId());
			w.s(Registries.ENTITY_TYPE.getId(entity.getType()).toString());
			w.b(entity instanceof PlayerEntity);
			w.n(rel.x); w.n(rel.y); w.n(rel.z); w.n(dist);
			w.n(velocity.x); w.n(velocity.y); w.n(velocity.z);
			w.n(entity.getYaw()); w.n(entity.getPitch()); w.n(bearing);
			w.b(Math.abs(facing) < 12.0D);
			if (entity instanceof LivingEntity living) {
				w.n(living.getHealth() + living.getAbsorptionAmount());
				w.b(living.isOnGround()); w.b(living.isSprinting()); w.b(living.isSneaking());
				w.b(living.isUsingItem()); w.b(living.isBlocking());
				w.n(living.hurtTime); w.b(living.handSwinging);
			} else {
				w.n(0.0D);
				w.b(false); w.b(false); w.b(false); w.b(false); w.b(false);
				w.n(0.0D); w.b(false);
			}
			rows.add(w.fields());
		}
		return rows;
	}

	/** How far the floor falls away one block out in a direction. Returns -1 for a wall, capped at 4. */
	private static double drop(ClientWorld world, BlockPos feet, int dx, int dz) {
		BlockPos probe = feet.add(dx, 0, dz);
		if (!world.getBlockState(probe).getCollisionShape(world, probe).isEmpty()) return -1.0D;
		for (int depth = 1; depth <= 4; depth++) {
			BlockPos below = probe.down(depth);
			if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) return depth - 1;
		}
		return 4.0D;
	}

	/** Distance to the first solid block along the look vector, out to three blocks. */
	private static double wallDistance(ClientWorld world, ClientPlayerEntity self, Vec3d pos) {
		Vec3d look = Vec3d.fromPolar(0.0F, self.getYaw());
		for (double step = 0.25D; step <= 3.0D; step += 0.25D) {
			BlockPos probe = BlockPos.ofFloored(pos.add(look.multiply(step)).add(0.0D, 0.6D, 0.0D));
			if (!world.getBlockState(probe).getCollisionShape(world, probe).isEmpty()) return step;
		}
		return 3.0D;
	}

	/** Open blocks above the head, up to four. */
	private static double headRoom(ClientWorld world, BlockPos feet) {
		for (int height = 2; height <= 5; height++) {
			BlockPos probe = feet.up(height);
			if (!world.getBlockState(probe).getCollisionShape(world, probe).isEmpty()) return height - 2;
		}
		return 4.0D;
	}

	private static String blockId(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return Registries.BLOCK.getId(state.getBlock()).toString();
	}

	private static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static int ping(MinecraftClient client, PlayerEntity player) {
		if (client.getNetworkHandler() == null) return 0;
		var entry = client.getNetworkHandler().getPlayerListEntry(player.getUuid());
		return entry == null ? 0 : entry.getLatency();
	}

	/** Builds one positional row and checks the field count against the schema on the way out. */
	static final class RowWriter {
		private final StringBuilder out = new StringBuilder(512);
		private final ContributionUploader uploader;
		private final int expected;
		private int written;

		RowWriter(int expected, ContributionUploader uploader) {
			this.expected = expected;
			this.uploader = uploader;
			out.append('[');
		}

		void n(double value) {
			separator();
			out.append(ContributionUploader.num(value));
		}

		void b(boolean value) {
			separator();
			out.append(value ? '1' : '0');
		}

		/** Writes a string as an index into the batch dictionary. */
		void s(String value) {
			separator();
			out.append(uploader.intern(value));
		}

		private void separator() {
			if (written++ > 0) out.append(',');
		}

		String fields() {
			check();
			return out.append(']').toString();
		}

		String finish(long tick, List<String> entities, List<String> events) {
			StringBuilder row = new StringBuilder(768);
			row.append("{\"n\":").append(tick).append(",\"f\":").append(fields());
			if (!entities.isEmpty()) {
				row.append(",\"e\":[");
				for (int i = 0; i < entities.size(); i++) {
					if (i > 0) row.append(',');
					row.append(entities.get(i));
				}
				row.append(']');
			}
			if (!events.isEmpty()) {
				row.append(",\"v\":[");
				for (int i = 0; i < events.size(); i++) {
					if (i > 0) row.append(',');
					row.append(uploader.intern(events.get(i)));
				}
				row.append(']');
			}
			return row.append('}').toString();
		}

		private void check() {
			if (written != expected) {
				throw new IllegalStateException(
						"Data contribution row wrote " + written + " fields, schema declares " + expected);
			}
		}
	}
}
