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
 * Records one row of player state per client tick and hands it to {@link ContributionUploader},
 * which batches, compresses and ships it. The point of the corpus is to learn what real human
 * movement looks like at tick resolution, so this runs from {@code firePreMovement} — the tail of
 * {@code handleInputEvents()}, after the keyboard has been read and before the player tick sends
 * movement. That is the only phase where the raw human input and the state it was a response to
 * are both on hand and neither has been rewritten by a module yet.
 *
 * <p>Everything is spatially relative by default. Rows carry an offset from a per-session origin
 * rather than world coordinates, which is both what a movement model wants (motion generalises,
 * map positions do not) and what keeps a contributor's base off the wire. Absolute coordinates and
 * the server address ride along only when the player has separately opted into location data.
 */
public final class DataContribution {
	/** Bump when the field order below changes. Rows are positional, so readers key off this. */
	static final int SCHEMA = 3;

	private static final int MAX_TRACKED = 4;
	private static final double TRACK_RADIUS = 32.0D;

	private static DataContribution instance;

	/**
	 * Positional field names for the local-player row. Order is the wire format — append to the
	 * end and bump {@link #SCHEMA}, never insert into the middle.
	 */
	static final String[] FIELDS = {
			"tick", "ms",
			// Offset from the session origin: full trajectory shape, no real place.
			"rel_x", "rel_y", "rel_z",
			"dx", "dy", "dz",
			"vx", "vy", "vz", "speed",
			"yaw", "pitch", "head_yaw", "body_yaw", "d_yaw", "d_pitch",
			"on_ground", "h_collide", "v_collide", "fall_dist", "climbing",
			"in_water", "submerged", "in_lava", "swimming", "gliding", "jumping",
			"sprint", "sneak", "using_item", "use_ticks", "blocking",
			"health", "absorption", "food", "saturation", "air", "hurt_time",
			// The human's actual key state this tick — the label a movement model predicts.
			"key_forward", "key_back", "key_left", "key_right",
			"key_jump", "key_sneak", "key_sprint", "key_attack", "key_use",
			// What the body was actually given. Note this lags key_* by one tick by construction:
			// it was written during the previous player tick, before this sample was taken.
			"eff_forward", "eff_back", "eff_left", "eff_right", "eff_jump", "eff_sneak", "eff_sprint",
			// A module rewrote the movement input. Computed from the two aligned to the SAME tick,
			// not from the two columns above — those are a tick apart and differ on every keypress.
			"overridden",
			"mv_side", "mv_fwd",
			"slot", "main_item", "off_item", "attack_cd",
			"ping", "dim", "light",
			"block_below", "block_feet", "block_head",
			"ledge_n", "ledge_e", "ledge_s", "ledge_w", "wall_dist", "head_room",
			"near_count", "player_count",
			// Zero unless the player opted into location data; the batch header says which.
			"abs_x", "abs_y", "abs_z",
			// What the player is doing, and a segment id that groups consecutive ticks of it.
			// The segment also breaks across any gap the module gate left, so nothing downstream
			// reads across dropped ticks as though the motion were continuous.
			"activity", "segment", "ms_in_activity", "pvp", "threat_dist"
	};

	/**
	 * Positional field names for each tracked nearby entity. Same append-only rule.
	 *
	 * <p>{@code id} is what makes the four slots followable. They are sorted players-first then by
	 * distance, so when two opponents swap ranks between ticks slot 0 silently becomes a different
	 * person — without an identity you cannot track one opponent through a fight, which is exactly
	 * what a combat model has to learn from. The network id is stable for as long as the entity is
	 * tracked in the session and says nothing about who it belongs to.
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

	// Edge-detection state: events are derived from state transitions rather than by hooking
	// every call site, so the recorder stays a leaf and cannot perturb what it is measuring.
	private Vec3d lastPos;
	private float lastYaw;
	private float lastPitch;
	private boolean lastOnGround = true;
	private boolean lastSwinging;
	private boolean lastUsing;
	private boolean lastBreaking;
	private int lastHurtTime;
	private int lastSlot = -1;
	/** Previous tick's raw key state, so `overridden` compares like with like. See sample(). */
	private boolean[] lastKeys;
	private long recorded;
	private long skipped;
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

	/** Called from the attack path so a swing that actually landed is distinguishable from a miss. */
	public static void noteAttack() {
		if (instance != null) instance.pendingEvents.add("attack");
	}

	/** Called from the block-use path; also what flips the activity label to building. */
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
			// A mid-session opt-out drops the buffer rather than shipping what it already holds.
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
			// Telemetry must never be able to take the client down with it. One bad row is
			// dropped and the session carries on; a broken recorder is not worth a crash.
			ProFPS.LOGGER.warn("Data contribution row failed; skipping this tick.", exception);
		} finally {
			pendingEvents.clear();
			tickIndex++;
		}
	}

	/**
	 * Works out what this tick is before deciding whether to keep it. The order matters: events
	 * and the entity list feed the activity label, the label plus the module gate decide whether
	 * the row is real play, and only then is anything encoded. Per-tick deltas are advanced either
	 * way, so a delta is always "since the previous tick" rather than silently spanning a gap.
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

		// The two samples are a tick apart and have to be aligned before they can be compared.
		// This runs at the tail of handleInputEvents(); `input.playerInput` was last written by
		// KeyboardInput.tick() during the PREVIOUS player tick, so it reflects the keyboard as it
		// was then, not as it is now. Comparing it against the keys held right now flags every
		// press and every release as an override — symmetric, transition-only noise that has
		// nothing to do with modules. So compare last tick's keys against last tick's applied
		// input, which is like for like.
		boolean[] applied = {
				input.forward(), input.backward(), input.left(), input.right(),
				input.jump(), input.sneak(), input.sprint()
		};
		boolean overridden = inputOverridden(lastKeys, applied);
		lastKeys = pressed;

		detectEdges(client, self, velocity);
		List<Entity> tracked = nearby(world, self);
		long nowMs = System.currentTimeMillis();
		activity.update(client, self, tracked, velocity, tickIndex, nowMs, pendingEvents);
		gate.update(client, config, overridden);

		if (!gate.allows(activity.activity())) {
			// Deliberately not recorded: a module is driving this. Advancing the deltas here is
			// what keeps the next kept row honest instead of carrying a stale reference point.
			skipped++;
			lastPos = pos;
			lastYaw = self.getYaw();
			lastPitch = self.getPitch();
			return;
		}
		recorded++;
		uploader.submit(row(client, self, world, tracked, input, pressed, overridden, nowMs));
	}

	/** What the Data settings page shows: null while recording, otherwise why it is paused. */
	public String pausedReason() {
		if (!config.dataContribution) return "Turned off";
		String reason = gate.reason();
		return reason == null ? null : reason + " is on";
	}

	/**
	 * Live counters for {@code /nova data}. Telemetry that fails quietly is worse than none —
	 * a wrong endpoint and an empty corpus look exactly the same from the outside — so the state
	 * that decides whether anything is being collected is readable in-game.
	 */
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

	/**
	 * A stable per-install identity that is not the account. Recordings from one contributor group
	 * together — which train/test splits need, or the model learns to recognise individuals across
	 * the split — while the UUID itself never leaves the machine. Deleting the config re-rolls it.
	 */
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

	/** Only ever non-null with location data on; otherwise the collector cannot tell servers apart. */
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
		// The same snapshot sample() took, not a fresh read: re-reading here could land on the
		// other side of a keypress and disagree with the flag computed moments ago.
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

	/**
	 * Whether a module rewrote the movement input, given the keys held on the previous tick and the
	 * input that was actually applied for that same tick.
	 *
	 * <p>Both arguments must describe the SAME tick. Comparing the keys held right now against the
	 * already-applied input is off by one and flags every press and release, which is symmetric,
	 * transition-only noise — and because an override suppresses recording for 40 ticks, it starved
	 * the corpus to about 2% of real play before this was caught.
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

	/**
	 * Turns state transitions into the sparse event list. Kept to edges the client can see for
	 * itself so that no combat module has to call into the recorder and change its own timing.
	 */
	private void detectEdges(MinecraftClient client, ClientPlayerEntity self, Vec3d velocity) {
		if (self.handSwinging && !lastSwinging) pendingEvents.add("swing");
		lastSwinging = self.handSwinging;

		boolean breaking = client.interactionManager != null && client.interactionManager.isBreakingBlock();
		if (breaking && !lastBreaking) pendingEvents.add("break_start");
		// The falling edge is "stopped breaking", which covers both finishing the block and
		// letting go of it. The mining label already says which stretch was the dig.
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

	/** The nearest few entities, players first, so the tracked slots do not churn every tick. */
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
			// Bearing is the target's direction in the player's own frame, so a model sees
			// "to my left" rather than a world-axis offset it would have to rotate itself.
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

	/**
	 * How far the floor falls away one block out in a direction. Distinguishes "walked to the edge
	 * and stopped" from "walked into a wall", which are the same velocity trace but different
	 * decisions. Capped at four so a void edge does not dominate the feature.
	 */
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

	/** Open blocks above the head, up to four — the ceiling that decides whether a jump is possible. */
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

	/**
	 * Builds one positional row. Fields are appended in order and the count is checked on the way
	 * out, so a field added to the name array but not written here fails loudly at the first tick
	 * rather than silently shifting every later column in the corpus.
	 */
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

		/** Strings go in as an index into the batch dictionary, never as repeated literals. */
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
