package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * One-shot, keybind-fired Lunge jab with an optional Spear→Mace handoff.
 *
 * <p>The sequence uses only ordinary client state: visible mouse-grid rotation,
 * forward/sprint/jump input, a visible hotbar selection, one full server tick for
 * the selection to register, and vanilla {@code doAttack}. It never spoofs a hidden
 * pitch or emits the STAB action after the movement packet.</p>
 */
public final class AutoLungeController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private Phase phase = Phase.IDLE;
	private UUID targetUuid;
	private int spearSlot = -1;
	private int originalSlot = -1;
	private int nextActionAge;
	private int deadlineAge;
	private boolean originalForward;
	private boolean originalSprint;
	private boolean originalJump;
	private long lastFrameNanos;
	private boolean ownsRotation;
	private String status = "Idle";
	// Spam scaling: the jab is paced by how fast the key is actually pressed.
	// Presses are counted over a rolling window, and a press that arrives while a
	// jab is still finishing is queued rather than dropped, so holding a fast
	// rhythm chains jabs back to back instead of restarting the wind-up.
	private long lastPressNanos;
	private double pressRateHz;
	private boolean queuedRequest;

	public AutoLungeController(ProFPSConfig config) {
		this.config = config;
	}

	/** Runs at vanilla's input phase, before this tick's movement packet. */
	public void tickPreMovement(MinecraftClient client) {
		if (config.autoLungeRequested) {
			config.autoLungeRequested = false;
			notePress();
			if (phase == Phase.IDLE && allowed(client)) begin(client);
			else queuedRequest = true;
		}

		if (phase == Phase.IDLE) {
			if (queuedRequest) {
				queuedRequest = false;
				if (allowed(client)) begin(client);
			}
			return;
		}
		if (!allowed(client)) {
			reset(client, true);
			return;
		}

		ClientPlayerEntity player = client.player;
		int age = player.age;
		switch (phase) {
			case REACTION -> {
				if (age < nextActionAge) return;
				applyMovement(client, false);
				phase = Phase.SPRINT;
				nextActionAge = age + windUpTicks();
				status = "Building speed";
			}
			case SPRINT -> {
				applyMovement(client, false);
				if (age < nextActionAge) return;
				applyMovement(client, true);
				phase = Phase.JUMP;
				deadlineAge = age + 6;
				status = "Springing";
			}
			case JUMP -> {
				applyMovement(client, player.isOnGround());
				if (player.isOnGround()) {
					if (age >= deadlineAge) reset(client, true);
					return;
				}
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_LUNGE)) return;
				selectSlot(client, player, spearSlot);
				phase = Phase.AIM;
				nextActionAge = age + 1; // at least one movement packet with the spear selected
				deadlineAge = age + 10;
				status = "Charging jab";
			}
			case AIM -> {
				applyMovement(client, false);
				if (player.getInventory().getSelectedSlot() != spearSlot
						|| !isSpear(player.getMainHandStack())) {
					// A manual scroll wins; do not fight it by restoring another slot.
					reset(client, false);
					return;
				}
				if (age < nextActionAge) return;

				PlayerEntity target = target(client);
				if (config.lungeAim && target != null && !rotationReady(player, target)) {
					if (age >= deadlineAge) reset(client, true);
					return;
				}
				// Spears have MINIMUM_ATTACK_CHARGE=1.0 in 1.21.11. Calling doAttack
				// early does not jab at all, which was the old silent-failure path.
				if (!SpearCombatPolicy.jabCharged(player.getAttackCooldownProgress(0.0F))) {
					if (age >= deadlineAge) {
						notify(client, "Auto Lunge: spear was not fully charged");
						reset(client, true);
					}
					return;
				}
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_LUNGE)) return;
				boolean stabbed = ((MinecraftClientInvoker) client).invokeDoAttack();
				if (!stabbed) {
					nextActionAge = age + 1;
					return;
				}
				if (config.lungeSpearMace && target != null) {
					CombatModeRuntime.armSpearMace(target.getUuid(), 2_400L);
				}
				phase = Phase.RECOVER;
				nextActionAge = age + recoverTicks();
				status = config.lungeSpearMace && target != null
						? "Spear → Mace armed"
						: "Recovering";
			}
			case RECOVER -> {
				applyMovement(client, false);
				if (age < nextActionAge) return;
				// A press that arrived mid-jab restarts the moment recovery ends,
				// so a fast rhythm chains instead of replaying the whole wind-up.
				boolean chain = queuedRequest && allowed(client);
				reset(client, !chain);
				if (chain) {
					queuedRequest = false;
					begin(client);
				}
			}
			case IDLE -> { }
		}
	}

	/**
	 * Visible rotation for both the one-shot Lunge and manual spear Charge Assist.
	 * Returns true while this controller owns the camera this frame.
	 */
	public boolean frame(MinecraftClient client) {
		ownsRotation = false;
		if (!allowed(client)) return false;

		ClientPlayerEntity player = client.player;
		PlayerEntity target;
		boolean lungeAim = phase != Phase.IDLE && phase != Phase.RECOVER && config.lungeAim;
		boolean chargeAim = phase == Phase.IDLE && config.spearChargeAssist
				&& player.isUsingItem()
				&& player.getActiveItem().contains(DataComponentTypes.KINETIC_WEAPON);
		if (!lungeAim && !chargeAim) return false;

		target = lungeAim ? target(client) : acquireTarget(client, config.spearChargeRange,
				config.spearChargeFov);
		if (target == null || !player.canSee(target)) return false;

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp(
						(now - lastFrameNanos) / 1_000_000_000.0D * 20.0D,
						0.05D, 3.0D);
		lastFrameNanos = now;

		Vec3d eye = player.getEyePos();
		Vec3d velocity = target.getVelocity().multiply(chargeAim ? 0.45D : 0.75D);
		Vec3d point = target.getBoundingBox().getCenter().add(velocity);
		double dx = point.x - eye.x;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		// Lunge distance is maximized at level pitch. Charge Assist instead follows
		// the opponent because kinetic damage uses the view direction.
		float desiredPitch = lungeAim ? 0.0F
				: (float) -Math.toDegrees(Math.atan2(point.y - eye.y, horizontal));

		int configuredSpeed = lungeAim ? config.lungeTurnSpeed : config.spearChargeTurnSpeed;
		float speed = MathHelper.clamp(configuredSpeed, 20, 85) / 100.0F;
		float blend = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchError = MathHelper.wrapDegrees(desiredPitch - player.getPitch());
		float cap = (lungeAim ? 34.0F : 22.0F) * dt;

		float yaw = mouse.yaw(MathHelper.clamp(
				yawError * blend + (float) rng.nextGaussian() * 0.16F,
				-cap, cap));
		float pitch = mouse.pitch(MathHelper.clamp(
				pitchError * blend + (float) rng.nextGaussian() * 0.11F,
				-cap * 0.72F, cap * 0.72F));
		player.setYaw(player.getYaw() + yaw);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitch, -90.0F, 90.0F));
		ownsRotation = true;
		return true;
	}

	/** Records a keypress and keeps a decaying estimate of the spam rate. */
	private void notePress() {
		long now = System.nanoTime();
		if (lastPressNanos != 0L) {
			double gapSeconds = (now - lastPressNanos) / 1_000_000_000.0D;
			if (gapSeconds > 1.5D) {
				pressRateHz = 0.0D;
			} else {
				double instant = 1.0D / Math.max(0.03D, gapSeconds);
				// Smoothed so one stray double-tap does not read as sustained spam.
				pressRateHz = pressRateHz * 0.45D + instant * 0.55D;
			}
		}
		lastPressNanos = now;
	}

	/** True once the key is being pressed faster than roughly four times a second. */
	private boolean spamming() {
		if (!config.lungeSpamScaling || lastPressNanos == 0L) return false;
		return (System.nanoTime() - lastPressNanos) < 1_500_000_000L && pressRateHz >= 4.0D;
	}

	/**
	 * Ticks spent building speed before the jump. Hard spam collapses this to the
	 * single tick the sprint needs to register; a normal press keeps the varied
	 * human wind-up.
	 */
	private int windUpTicks() {
		if (!config.lungeSpamScaling) return 1 + rng.nextInt(2);
		if (spamming()) return 1;
		return pressRateHz >= 2.0D ? 1 + rng.nextInt(2) : 1 + rng.nextInt(3);
	}

	/** Cool-down before the sequence releases the keys back to the player. */
	private int recoverTicks() {
		return config.lungeSpamScaling && spamming() ? 1 : 2;
	}

	private void begin(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!SpearCombatPolicy.canStartLunge(
				player.getHungerManager().getFoodLevel(),
				player.hasVehicle(),
				player.isGliding(),
				player.isTouchingWater())) {
			if (player.getHungerManager().getFoodLevel() < SpearCombatPolicy.MIN_LUNGE_FOOD) {
				notify(client, "Auto Lunge: need at least 3 hunger bars");
				return;
			}
			notify(client, "Auto Lunge: unavailable while riding, gliding, or in water");
			return;
		}
		spearSlot = findLungeSpear(player);
		if (spearSlot < 0) {
			notify(client, "Auto Lunge: need a Lunge spear in the hotbar");
			return;
		}

		PlayerEntity target = acquireTarget(client, config.lungeRange, config.lungeFov);
		targetUuid = target == null ? null : target.getUuid();
		originalSlot = player.getInventory().getSelectedSlot();
		originalForward = client.options.forwardKey.isPressed();
		originalSprint = client.options.sprintKey.isPressed();
		originalJump = client.options.jumpKey.isPressed();
		phase = Phase.REACTION;
		// Under sustained spam the reaction beat is already paid by the player's
		// own timing, so the jab starts on the next tick instead of adding one.
		nextActionAge = player.age + (spamming() ? 0 : 1 + rng.nextInt(2));
		status = target == null ? "Lunge ready" : "Tracking lunge";
	}

	private PlayerEntity acquireTarget(MinecraftClient client, int configuredRange, int configuredFov) {
		ClientPlayerEntity self = client.player;
		double range = MathHelper.clamp(configuredRange, 4, 24);
		double minDot = Math.cos(Math.toRadians(MathHelper.clamp(configuredFov, 20, 120)));
		PlayerEntity best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator() || !self.canSee(other)) continue;
			Vec3d delta = other.getBoundingBox().getCenter().subtract(self.getEyePos());
			double distance = delta.length();
			if (distance < 1.0E-4D || distance > range) continue;
			double dot = delta.normalize().dotProduct(self.getRotationVec(1.0F));
			if (dot < minDot) continue;
			double score = dot * 4.0D + (range - distance) / range;
			if (score > bestScore) {
				bestScore = score;
				best = other;
			}
		}
		return best;
	}

	private PlayerEntity target(MinecraftClient client) {
		if (targetUuid == null) return null;
		for (PlayerEntity player : client.world.getPlayers()) {
			if (targetUuid.equals(player.getUuid()) && player.isAlive() && !player.isSpectator()) {
				return player;
			}
		}
		return null;
	}

	private boolean rotationReady(ClientPlayerEntity player, PlayerEntity target) {
		Vec3d delta = target.getBoundingBox().getCenter().subtract(player.getEyePos());
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
		return Math.abs(MathHelper.wrapDegrees(desiredYaw - player.getYaw())) <= 6.0F
				&& Math.abs(player.getPitch()) <= 7.0F;
	}

	private void applyMovement(MinecraftClient client, boolean jump) {
		client.options.forwardKey.setPressed(true);
		client.options.sprintKey.setPressed(true);
		client.options.jumpKey.setPressed(jump);
		client.player.setSprinting(true);
	}

	private void restoreInput(MinecraftClient client) {
		client.options.forwardKey.setPressed(originalForward);
		client.options.sprintKey.setPressed(originalSprint);
		client.options.jumpKey.setPressed(originalJump);
	}

	private int findLungeSpear(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (isSpear(stack) && hasLunge(stack)) return slot;
		}
		return -1;
	}

	private void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
	}

	private boolean isSpear(ItemStack stack) {
		return !stack.isEmpty()
				&& stack.contains(DataComponentTypes.PIERCING_WEAPON)
				&& stack.contains(DataComponentTypes.KINETIC_WEAPON);
	}

	private boolean hasLunge(ItemStack stack) {
		if (stack.isEmpty() || !stack.hasEnchantments()) return false;
		for (RegistryEntry<Enchantment> enchantment : stack.getEnchantments().getEnchantments()) {
			if (enchantment.matchesKey(Enchantments.LUNGE)) return true;
		}
		return false;
	}

	public boolean ownsRotation() {
		return ownsRotation;
	}

	public boolean isBusy() {
		return phase != Phase.IDLE;
	}

	public String status() {
		return phase == Phase.IDLE ? "Idle" : status;
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null
				|| client.getOverlay() != null || !client.isWindowFocused()) return false;
		return client.player.isAlive() && !client.player.isSpectator();
	}

	private void notify(MinecraftClient client, String message) {
		if (client.player != null) client.inGameHud.setOverlayMessage(Text.literal(message), false);
	}

	private void reset(MinecraftClient client, boolean restoreSlot) {
		if (client != null && client.options != null) restoreInput(client);
		if (restoreSlot && client != null && client.player != null
				&& originalSlot >= 0 && originalSlot < 9
				&& client.player.getInventory().getSelectedSlot() == spearSlot) {
			client.player.getInventory().setSelectedSlot(originalSlot);
		}
		phase = Phase.IDLE;
		targetUuid = null;
		spearSlot = -1;
		originalSlot = -1;
		nextActionAge = 0;
		deadlineAge = 0;
		lastFrameNanos = 0L;
		ownsRotation = false;
		status = "Idle";
		// The spam estimate deliberately survives a reset: it describes the
		// player's rhythm across jabs, which is the whole point of scaling to it.
	}

	private enum Phase {
		IDLE,
		REACTION,
		SPRINT,
		JUMP,
		AIM,
		RECOVER
	}
}
