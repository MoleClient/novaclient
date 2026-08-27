package com.profps.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.profps.ProFPS;
import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatMode;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.donutsmp.HumanizedAim;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class SwordAiController {
	private static final String MODEL_RESOURCE = "/assets/profps/models/combat_move_model.json";
	private static final int HISTORY = 20;
	private static final String[] FEATURE_NAMES = {
			"dist", "rel_dx", "rel_dy", "rel_dz", "d_dist", "d_rel_dx", "d_rel_dz", "s_pitch",
			"s_ground", "s_fall", "s_hurt", "s_hp", "s_weapon", "o_ground", "o_sprint", "o_fall",
			"o_block", "o_hurt", "o_hp", "align_self", "align_opp", "m_aggression", "m_spacing",
			"m_interval_ms", "m_hit_range", "ms_since_attack", "s_attacked", "aim_yaw_err",
			"aim_pitch_err", "aim_err", "on_target", "box_dist", "eye_dist", "opp_aim_yaw_err",
			"opp_aim_pitch_err", "opp_aim_err", "opp_on_us", "opp_box_dist", "opp_eye_dist",
			"aim_disadvantage", "s_ping", "o_ping", "ping_gap", "o_vx", "o_vz", "o_speed",
			"o_using", "o_sneak", "ms_since_opp_swing", "opp_swing_recent", "o_side_speed",
			"o_closing_speed", "o_side_abs", "s_attack_strength", "s_sneak", "o_attack_strength"
	};

	private static SwordAiController instance;

	private final ProFPSConfig config;
	private final double[][] history = new double[HISTORY][FEATURE_NAMES.length];
	private final Map<String, Integer> featureIndex = new HashMap<>();
	private final HumanizedAim aim = new HumanizedAim();
	private final Random random = new Random();
	private Model model;
	private boolean loadAttempted;
	private boolean loadFailedAnnounced;
	private PlayerEntity target;
	private Vec3d lastSelfPos;
	private Vec3d lastOppPos;
	private double lastDist;
	private long lastAttackNanos;
	private long lastOppSwingNanos;
	private long lastTargetNoticeNanos;
	private long lastJumpNanos;
	private long lastAimFrameNanos;
	private long nextStrafeFlipNanos;
	private int strafeDirection = 1;
	private double lastTargetDistance;
	private PlayerInput decision = PlayerInput.DEFAULT;

	public SwordAiController(ProFPSConfig config) {
		this.config = config;
		for (int i = 0; i < FEATURE_NAMES.length; i++) {
			featureIndex.put(FEATURE_NAMES[i], i);
		}
		instance = this;
	}

	public void tick(MinecraftClient client) {
		if (!CombatModePolicy.enabled(config, CombatFeature.SWORD_AI) || !allowed(client)) {
			release();
			return;
		}
		if (!ensureLoaded(client)) {
			release();
			return;
		}

		ClientPlayerEntity player = client.player;
		PlayerEntity opponent = lockedTarget(client, player);
		if (opponent == null) {
			release();
			return;
		}
		target = opponent;
		lastTargetDistance = player.distanceTo(opponent);
		announceTarget(client, opponent);
		pushHistory(features(client, player, opponent));
		ModelOutput out = model.run(flattenWindow());
		decision = inputFrom(player, opponent, out);
	}

	public void frame(MinecraftClient client) {
		// Aim is a separate toggle; with it off the AI still drives movement and jumps.
		if (!CombatModePolicy.enabled(config, CombatFeature.SWORD_AI_AIM) || !allowed(client)) {
			lastAimFrameNanos = 0L;
			return;
		}
		PlayerEntity opponent = target;
		if (opponent == null || !opponent.isAlive() || opponent.isSpectator()
				|| opponent.squaredDistanceTo(client.player) > square(tuning().holdDistance())) {
			lastAimFrameNanos = 0L;
			return;
		}

		long now = System.nanoTime();
		if (lastAimFrameNanos == 0L) {
			lastAimFrameNanos = now;
			return;
		}
		float dtTicks = (float) MathHelper.clamp((now - lastAimFrameNanos) / 50_000_000.0D, 0.02D, 1.5D);
		lastAimFrameNanos = now;

		Vec3d aimPoint = opponent.getEyePos().add(0.0D, -0.26D, 0.0D);
		aim.aimFrame(client.player, aimPoint, (float) tuning().aimSpeedScale(), dtTicks);
		client.player.headYaw = client.player.getYaw();
	}

	public void markAttack(MinecraftClient client, net.minecraft.entity.Entity entity) {
		if (client.player != null && entity instanceof PlayerEntity && entity != client.player) {
			lastAttackNanos = System.nanoTime();
		}
	}

	public static boolean isControlling() {
		SwordAiController controller = instance;
		return controller != null
				&& CombatModePolicy.enabled(controller.config, CombatFeature.SWORD_AI)
				&& controller.model != null
				&& controller.decision != PlayerInput.DEFAULT;
	}

	public static PlayerInput movementInput(PlayerInput current) {
		SwordAiController controller = instance;
		if (controller == null || controller.decision == PlayerInput.DEFAULT) return null;
		PlayerInput d = controller.decision;
		return new PlayerInput(d.forward(), d.backward(), d.left(), d.right(), d.jump(), current.sneak(), d.sprint());
	}

	private boolean ensureLoaded(MinecraftClient client) {
		if (model != null) return true;
		if (loadAttempted) return false;
		loadAttempted = true;
		try (InputStream stream = SwordAiController.class.getResourceAsStream(MODEL_RESOURCE)) {
			if (stream == null) {
				throw new IllegalStateException("missing " + MODEL_RESOURCE);
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			model = Model.from(root);
			loadFailedAnnounced = false;
			ProFPS.LOGGER.info("Sword AI loaded distilled combat model with {} heads.", model.heads.size());
			return true;
		} catch (Exception exception) {
			if (!loadFailedAnnounced && client.player != null) {
				client.player.sendMessage(net.minecraft.text.Text.literal("[ProFPS] Sword AI load failed: " + exception.getMessage()), false);
				loadFailedAnnounced = true;
			}
			ProFPS.LOGGER.warn("Sword AI failed to load distilled model.", exception);
			if (CombatModePolicy.mode(config) == CombatMode.SWORD) {
				config.swordModeAiBot = false;
			} else {
				config.swordAiEnabled = false;
			}
			config.save();
			return false;
		}
	}

	private boolean allowed(MinecraftClient client) {
		if (client == null || client.player == null || client.world == null || client.currentScreen != null) return false;
		if (CombatModePolicy.mode(config) == CombatMode.SWORD
				&& !client.player.getMainHandStack().isIn(ItemTags.SWORDS)) return false;
		return client.interactionManager != null && client.player.isAlive() && !client.player.isSpectator()
				&& !client.player.hasVehicle();
	}

	private PlayerEntity lockedTarget(MinecraftClient client, ClientPlayerEntity player) {
		CombatModeProfile.SwordAi tuning = tuning();
		if (target != null && target.isAlive() && !target.isSpectator()
				&& player.squaredDistanceTo(target) <= square(tuning.holdDistance())) {
			return target;
		}
		target = null;
		PlayerEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (AbstractClientPlayerEntity candidate : client.world.getPlayers()) {
			if (candidate == player || !candidate.isAlive() || candidate.isSpectator()) continue;
			double dist = player.squaredDistanceTo(candidate);
			if (dist > square(tuning.acquireDistance())) continue;
			double yawErr = Math.abs(yawError(player, candidate));
			double pitchErr = Math.abs(pitchError(player, candidate));
			if (yawErr > tuning.acquireYawDeg() || pitchErr > tuning.acquirePitchDeg()) continue;
			double score = yawErr * 2.0D + pitchErr + Math.sqrt(dist) * 0.35D;
			if (score < bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	private double[] features(MinecraftClient client, ClientPlayerEntity self, PlayerEntity opp) {
		double[] f = new double[FEATURE_NAMES.length];
		Vec3d selfPos = self.getEntityPos();
		Vec3d oppPos = opp.getEntityPos();
		Vec3d rel = oppPos.subtract(selfPos);
		double dist = Math.sqrt(rel.x * rel.x + rel.y * rel.y + rel.z * rel.z);
		Vec3d selfDelta = lastSelfPos == null ? Vec3d.ZERO : selfPos.subtract(lastSelfPos);
		Vec3d oppDelta = lastOppPos == null ? Vec3d.ZERO : oppPos.subtract(lastOppPos);
		double prevDist = lastDist <= 0.0D ? dist : lastDist;
		lastSelfPos = selfPos;
		lastOppPos = oppPos;
		lastDist = dist;

		set(f, "dist", dist);
		set(f, "rel_dx", rel.x);
		set(f, "rel_dy", rel.y);
		set(f, "rel_dz", rel.z);
		set(f, "d_dist", dist - prevDist);
		set(f, "d_rel_dx", oppDelta.x - selfDelta.x);
		set(f, "d_rel_dz", oppDelta.z - selfDelta.z);
		set(f, "s_pitch", self.getPitch());
		set(f, "s_ground", self.isOnGround() ? 1.0D : 0.0D);
		set(f, "s_fall", self.fallDistance);
		set(f, "s_hurt", self.hurtTime);
		set(f, "s_hp", self.getHealth() + self.getAbsorptionAmount());
		set(f, "s_weapon", weaponScore(self.getMainHandStack().getItem()));
		set(f, "o_ground", opp.isOnGround() ? 1.0D : 0.0D);
		set(f, "o_sprint", opp.isSprinting() ? 1.0D : 0.0D);
		set(f, "o_fall", opp.fallDistance);
		set(f, "o_block", opp.isBlocking() ? 1.0D : 0.0D);
		set(f, "o_hurt", opp.hurtTime);
		set(f, "o_hp", opp.getHealth() + opp.getAbsorptionAmount());
		set(f, "align_self", alignment(self.getYaw(), rel.x, rel.z));
		set(f, "align_opp", alignment(opp.getYaw(), -rel.x, -rel.z));
		set(f, "m_aggression", MathHelper.clamp((6.0D - dist) / 6.0D, 0.0D, 1.0D));
		set(f, "m_spacing", dist - 3.0D);
		set(f, "m_interval_ms", 50.0D);
		set(f, "m_hit_range", 3.0D);
		set(f, "ms_since_attack", elapsedMs(lastAttackNanos, 5000.0D));
		set(f, "s_attacked", elapsedMs(lastAttackNanos, 5000.0D) < 250.0D ? 1.0D : 0.0D);
		double aimYaw = yawError(self, opp);
		double aimPitch = pitchError(self, opp);
		set(f, "aim_yaw_err", aimYaw);
		set(f, "aim_pitch_err", aimPitch);
		set(f, "aim_err", Math.sqrt(aimYaw * aimYaw + aimPitch * aimPitch));
		set(f, "on_target", Math.abs(aimYaw) < 8.0D && Math.abs(aimPitch) < 8.0D ? 1.0D : 0.0D);
		set(f, "box_dist", Math.max(0.0D, dist - 0.6D));
		set(f, "eye_dist", self.getEyePos().distanceTo(opp.getEyePos()));
		double oppYaw = yawError(opp, self);
		double oppPitch = pitchError(opp, self);
		set(f, "opp_aim_yaw_err", oppYaw);
		set(f, "opp_aim_pitch_err", oppPitch);
		set(f, "opp_aim_err", Math.sqrt(oppYaw * oppYaw + oppPitch * oppPitch));
		set(f, "opp_on_us", Math.abs(oppYaw) < 10.0D && Math.abs(oppPitch) < 10.0D ? 1.0D : 0.0D);
		set(f, "opp_box_dist", Math.max(0.0D, dist - 0.6D));
		set(f, "opp_eye_dist", opp.getEyePos().distanceTo(self.getEyePos()));
		set(f, "aim_disadvantage", Math.abs(aimYaw) - Math.abs(oppYaw));
		int selfPing = ping(client, self);
		int oppPing = ping(client, opp);
		set(f, "s_ping", selfPing);
		set(f, "o_ping", oppPing);
		set(f, "ping_gap", selfPing - oppPing);
		set(f, "o_vx", oppDelta.x);
		set(f, "o_vz", oppDelta.z);
		set(f, "o_speed", Math.sqrt(oppDelta.x * oppDelta.x + oppDelta.z * oppDelta.z));
		set(f, "o_using", opp.isUsingItem() ? 1.0D : 0.0D);
		set(f, "o_sneak", opp.isSneaking() ? 1.0D : 0.0D);
		if (opp.handSwinging) lastOppSwingNanos = System.nanoTime();
		set(f, "ms_since_opp_swing", elapsedMs(lastOppSwingNanos, 5000.0D));
		set(f, "opp_swing_recent", elapsedMs(lastOppSwingNanos, 5000.0D) < 250.0D ? 1.0D : 0.0D);
		double len = Math.max(1.0E-4D, Math.sqrt(rel.x * rel.x + rel.z * rel.z));
		double nx = rel.x / len;
		double nz = rel.z / len;
		double side = oppDelta.x * -nz + oppDelta.z * nx;
		double close = -(oppDelta.x * nx + oppDelta.z * nz);
		set(f, "o_side_speed", side);
		set(f, "o_closing_speed", close);
		set(f, "o_side_abs", Math.abs(side));
		set(f, "s_attack_strength", self.getAttackCooldownProgress(0.0F));
		set(f, "s_sneak", self.isSneaking() ? 1.0D : 0.0D);
		set(f, "o_attack_strength", opp.getAttackCooldownProgress(0.0F));
		return f;
	}

	private void set(double[] f, String name, double value) {
		Integer index = featureIndex.get(name);
		if (index != null) f[index] = Double.isFinite(value) ? value : 0.0D;
	}

	private void pushHistory(double[] features) {
		for (int i = history.length - 1; i > 0; i--) {
			System.arraycopy(history[i - 1], 0, history[i], 0, FEATURE_NAMES.length);
		}
		System.arraycopy(features, 0, history[0], 0, FEATURE_NAMES.length);
	}

	private double[] flattenWindow() {
		double[] input = new double[model.mean.length];
		int pos = 0;
		for (int offset : model.offsets) {
			int safeOffset = Math.min(Math.max(offset, 0), history.length - 1);
			System.arraycopy(history[safeOffset], 0, input, pos, FEATURE_NAMES.length);
			pos += FEATURE_NAMES.length;
		}
		return input;
	}

	private PlayerInput inputFrom(ClientPlayerEntity player, PlayerEntity opponent, ModelOutput out) {
		if (target == null) return PlayerInput.DEFAULT;
		CombatModeProfile.SwordAi tuning = tuning();
		int forward = out.classIndex("forward");
		int strafe = out.classIndex("strafe");
		double risk = out.probability("risk");
		double opening = out.probability("opening");
		boolean tooClose = lastTargetDistance < tuning.tooCloseDistance();
		boolean tooFar = lastTargetDistance > tuning.tooFarDistance();
		boolean back = tooClose || ((forward == 0 || risk > 0.68D) && lastTargetDistance < 2.7D);
		boolean ahead = !back && (tooFar || (forward == 2 && opening > 0.58D && lastTargetDistance > 2.95D));
		if (lastTargetDistance >= 2.55D && lastTargetDistance <= 3.25D && opening < 0.62D) {
			ahead = false;
		}
		int sideIntent = stableStrafeDirection(out, lastTargetDistance);

		Vec3d rel = opponent.getEntityPos().subtract(player.getEntityPos());
		double len = Math.max(1.0E-4D, Math.sqrt(rel.x * rel.x + rel.z * rel.z));
		double toX = rel.x / len;
		double toZ = rel.z / len;
		double desiredX = 0.0D;
		double desiredZ = 0.0D;
		if (ahead) {
			desiredX += toX;
			desiredZ += toZ;
		}
		if (back) {
			desiredX -= toX;
			desiredZ -= toZ;
		}
		if (sideIntent != 0) {
			double side = sideIntent * tuning.strafeWeight();
			desiredX += -toZ * side;
			desiredZ += toX * side;
		}

		PlayerInput directional = keysForWorldVector(player, desiredX, desiredZ);
		boolean sprint = directional.forward() && out.probability("sprint") > 0.42D && lastTargetDistance > 3.15D;
		boolean jump = shouldJump(player, opponent, out, directional.forward(), sprint);
		if (directional == PlayerInput.DEFAULT && !jump && !sprint) return PlayerInput.DEFAULT;
		return new PlayerInput(directional.forward(), directional.backward(), directional.left(), directional.right(), jump, false, sprint);
	}

	private int stableStrafeDirection(ModelOutput out, double distance) {
		if (distance < 2.15D || distance > 3.85D) return 0;
		int strafe = out.classIndex("strafe");
		boolean wantsSide = strafe == 0 || strafe == 2 || out.probability("risk") > 0.58D;
		if (!wantsSide) return 0;
		long now = System.nanoTime();
		if (now >= nextStrafeFlipNanos) {
			if (strafe == 0) {
				strafeDirection = -1;
			} else if (strafe == 2) {
				strafeDirection = 1;
			} else if (random.nextDouble() < 0.35D) {
				strafeDirection = -strafeDirection;
			}
			CombatModeProfile.SwordAi tuning = tuning();
			int min = tuning.strafeFlipMinMs();
			int spread = Math.max(1, tuning.strafeFlipMaxMs() - min + 1);
			nextStrafeFlipNanos = now + (min + random.nextInt(spread)) * 1_000_000L;
		}
		return strafeDirection;
	}

	private PlayerInput keysForWorldVector(ClientPlayerEntity player, double x, double z) {
		double mag = Math.sqrt(x * x + z * z);
		if (mag < 0.12D) return PlayerInput.DEFAULT;
		x /= mag;
		z /= mag;
		double yawRad = Math.toRadians(player.getYaw());
		double forwardX = -Math.sin(yawRad);
		double forwardZ = Math.cos(yawRad);
		double rightX = Math.cos(yawRad);
		double rightZ = Math.sin(yawRad);
		double f = x * forwardX + z * forwardZ;
		double r = x * rightX + z * rightZ;
		boolean forward = f > 0.35D;
		boolean backward = f < -0.35D;
		boolean right = r > 0.35D;
		boolean left = r < -0.35D;
		return new PlayerInput(forward, backward, left, right, false, false, forward);
	}

	/** Jumps only for a charged in-range crit or a sprint-chase catch-up, each on its own cooldown. */
	private boolean shouldJump(ClientPlayerEntity player, PlayerEntity opponent, ModelOutput out,
			boolean movingForward, boolean sprinting) {
		if (!CombatModePolicy.enabled(config, CombatFeature.SWORD_AI_JUMP) || !player.isOnGround()) return false;
		CombatModeProfile.SwordAi tuning = tuning();
		long now = System.nanoTime();
		double dist = lastTargetDistance;
		boolean facing = Math.abs(yawError(player, opponent)) < 30.0D;
		if (!facing) return false;

		// Jump-crit: in reach and charged.
		if (dist >= 1.8D && dist <= 3.6D) {
			if (now - lastJumpNanos < tuning.critCooldownMs() * 1_000_000L) return false;
			if (player.getAttackCooldownProgress(0.0F) <= 0.86F) return false;
			double chance = tuning.critChance();
			if (out.probability("bad_crit_jump") > 0.82D) chance *= 0.25D;
			if (random.nextDouble() < chance) { lastJumpNanos = now; return true; }
			return false;
		}

		// Catch-up: beyond reach while sprint-chasing.
		if (dist > 4.2D && dist < tuning.acquireDistance() && movingForward && sprinting) {
			if (now - lastJumpNanos < tuning.catchupCooldownMs() * 1_000_000L) return false;
			double chance = tuning.catchupChanceBase()
					+ tuning.catchupChanceScale() * out.probability("opening");
			if (random.nextDouble() < chance) { lastJumpNanos = now; return true; }
			return false;
		}
		return false;
	}

	private void release() {
		decision = PlayerInput.DEFAULT;
		target = null;
		lastTargetDistance = 0.0D;
		lastSelfPos = null;
		lastOppPos = null;
		lastDist = 0.0D;
		lastAimFrameNanos = 0L;
		nextStrafeFlipNanos = 0L;
	}

	private void announceTarget(MinecraftClient client, PlayerEntity opponent) {
		if (client.player == null) return;
		long now = System.nanoTime();
		if (now - lastTargetNoticeNanos < 2_000_000_000L) return;
		lastTargetNoticeNanos = now;
		client.player.sendMessage(net.minecraft.text.Text.literal(
				String.format("[ProFPS] Sword AI target: %s %.1fm", opponent.getName().getString(), lastTargetDistance)), true);
	}

	private static double yawError(PlayerEntity from, PlayerEntity to) {
		Vec3d delta = to.getEyePos().subtract(from.getEyePos());
		double yaw = Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D;
		return MathHelper.wrapDegrees(yaw - from.getYaw());
	}

	private static double pitchError(PlayerEntity from, PlayerEntity to) {
		Vec3d delta = to.getEyePos().subtract(from.getEyePos());
		double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		double pitch = -Math.toDegrees(Math.atan2(delta.y, horiz));
		return pitch - from.getPitch();
	}

	private static double alignment(float yaw, double dx, double dz) {
		double yawRad = Math.toRadians(yaw);
		double fx = -Math.sin(yawRad);
		double fz = Math.cos(yawRad);
		double len = Math.max(1.0E-4D, Math.sqrt(dx * dx + dz * dz));
		return (fx * dx + fz * dz) / len;
	}

	private static int ping(MinecraftClient client, PlayerEntity player) {
		if (client.getNetworkHandler() == null) return 0;
		PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(player.getUuid());
		return entry == null ? 0 : entry.getLatency();
	}

	private static double elapsedMs(long sinceNanos, double fallback) {
		return sinceNanos <= 0L ? fallback : Math.min(fallback, (System.nanoTime() - sinceNanos) / 1_000_000.0D);
	}

	private CombatModeProfile.SwordAi tuning() {
		return CombatModePolicy.swordAi(config);
	}

	private static double square(double value) {
		return value * value;
	}

	private static double weaponScore(Item item) {
		if (item == Items.NETHERITE_SWORD || item == Items.NETHERITE_AXE || item == Items.MACE) return 1.0D;
		if (item == Items.DIAMOND_SWORD || item == Items.DIAMOND_AXE) return 0.85D;
		if (item == Items.IRON_SWORD || item == Items.IRON_AXE) return 0.65D;
		if (item == Items.STONE_SWORD || item == Items.STONE_AXE) return 0.45D;
		if (item == Items.WOODEN_SWORD || item == Items.WOODEN_AXE) return 0.25D;
		return 0.0D;
	}

	private record Layer(double[][] weights, double[] bias) {
		double[] run(double[] input, boolean relu) {
			double[] out = new double[bias.length];
			for (int row = 0; row < weights.length; row++) {
				double sum = bias[row];
				double[] w = weights[row];
				for (int col = 0; col < w.length; col++) sum += w[col] * input[col];
				out[row] = relu ? Math.max(0.0D, sum) : sum;
			}
			return out;
		}
	}

	private static final class Model {
		final int[] offsets;
		final double[] mean;
		final double[] std;
		final List<Layer> trunk;
		final Map<String, Layer> heads;

		Model(int[] offsets, double[] mean, double[] std, List<Layer> trunk, Map<String, Layer> heads) {
			this.offsets = offsets;
			this.mean = mean;
			this.std = std;
			this.trunk = trunk;
			this.heads = heads;
		}

		static Model from(JsonObject root) {
			int[] offsets = ints(root.getAsJsonArray("offsets"));
			double[] mean = doubles(root.getAsJsonArray("mean"));
			double[] std = doubles(root.getAsJsonArray("std"));
			java.util.ArrayList<Layer> trunk = new java.util.ArrayList<>();
			for (JsonElement element : root.getAsJsonArray("trunk")) trunk.add(layer(element.getAsJsonObject()));
			Map<String, Layer> heads = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("heads").entrySet()) {
				heads.put(entry.getKey(), layer(entry.getValue().getAsJsonObject()));
			}
			if (mean.length != offsets.length * FEATURE_NAMES.length || std.length != mean.length) {
				throw new IllegalArgumentException("model input shape does not match feature window");
			}
			return new Model(offsets, mean, std, trunk, heads);
		}

		ModelOutput run(double[] input) {
			double[] x = new double[input.length];
			for (int i = 0; i < x.length; i++) {
				double denom = Math.abs(std[i]) < 1.0E-6D ? 1.0D : std[i];
				x[i] = (input[i] - mean[i]) / denom;
			}
			for (Layer layer : trunk) x = layer.run(x, true);
			Map<String, double[]> outputs = new HashMap<>();
			for (Map.Entry<String, Layer> entry : heads.entrySet()) {
				outputs.put(entry.getKey(), entry.getValue().run(x, false));
			}
			return new ModelOutput(outputs);
		}

		private static Layer layer(JsonObject object) {
			return new Layer(matrix(object.getAsJsonArray("W")), doubles(object.getAsJsonArray("b")));
		}

		private static double[][] matrix(JsonArray array) {
			double[][] out = new double[array.size()][];
			for (int i = 0; i < array.size(); i++) out[i] = doubles(array.get(i).getAsJsonArray());
			return out;
		}

		private static double[] doubles(JsonArray array) {
			double[] out = new double[array.size()];
			for (int i = 0; i < array.size(); i++) out[i] = array.get(i).getAsDouble();
			return out;
		}

		private static int[] ints(JsonArray array) {
			int[] out = new int[array.size()];
			for (int i = 0; i < array.size(); i++) out[i] = array.get(i).getAsInt();
			return out;
		}
	}

	private record ModelOutput(Map<String, double[]> heads) {
		int classIndex(String name) {
			double[] logits = heads.get(name);
			if (logits == null || logits.length == 0) return 1;
			int best = 0;
			for (int i = 1; i < logits.length; i++) {
				if (logits[i] > logits[best]) best = i;
			}
			return best;
		}

		double probability(String name) {
			double[] logits = heads.get(name);
			if (logits == null || logits.length == 0) return 0.0D;
			return 1.0D / (1.0D + Math.exp(-MathHelper.clamp(logits[0], -40.0D, 40.0D)));
		}
	}
}
