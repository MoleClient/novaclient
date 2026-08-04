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
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/**
 * Auto Lunge Swap — the attribute-swap form of a spear lunge.
 *
 * <p>Held normally, a Lunge spear is nearly useless as movement. The lunge only
 * fires at full attack charge, and the charge bar divides by the <em>held</em>
 * item's attack speed, so a spear in hand means waiting out its own long
 * recharge for every single burst.
 *
 * <p>The swap gets around that. The server drains its packet queue before it
 * ticks entities, and it is {@code LivingEntity#tick -> sendEquipmentChanges}
 * that applies a newly-held item's {@code ATTACK_SPEED} modifier. So an attack
 * arriving in the same tick as the slot change resolves against a mixed state:
 * the <em>carry</em> item's attack speed — which is why the bar reads full — with
 * the <em>spear's</em> stack, which is what supplies Lunge. Charge on a bare
 * fist refills in about five ticks instead of the spear's own recharge, so the
 * bursts chain far faster than holding the weapon ever allows.
 *
 * <p>Three details make it reliable rather than occasional:
 * <ul>
 *   <li><b>Carry first.</b> The lunge cannot be swapped <em>into</em> a full bar
 *       that does not exist yet, so the module puts a fast item in hand and lets
 *       the bar fill before it ever tries.</li>
 *   <li><b>Gate on the outgoing item.</b> The charge that matters is the one the
 *       server will divide by — the carry item's, not the spear's. Gating on the
 *       spear's own bar is what would make it fire almost never.</li>
 *   <li><b>Vanilla packet order.</b> The slot change is emitted immediately
 *       before the attack, in the same dispatch, with nothing hand-rolled in
 *       between, and the return swap waits for a later tick so it cannot
 *       overtake the attack it is recovering from.</li>
 * </ul>
 *
 * <p>Every delay here is sampled per activation rather than fixed: reaction
 * before the burst, a short overshoot past full charge, and the recovery gap.
 * A macro that fires on exactly the same frame offset every time is a stronger
 * tell than the swap itself.
 */
public final class AutoLungeSwapController {
	/** Safety net so a swap can never strand the player holding the spear. */
	private static final int RESTORE_DEADLINE_TICKS = 34;
	/** Give up waiting for lift-off rather than hanging the sequence on it. */
	private static final int LAUNCH_TIMEOUT_TICKS = 8;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private Phase phase = Phase.IDLE;
	private int spearSlot = -1;
	private int carrySlot = -1;
	private int returnSlot = -1;
	private int nextActionAge;
	private int deadlineAge;
	private int launchDeadlineAge;
	private long lastFrameNanos;
	private boolean ownsRotation;
	private String status = "Idle";

	// The player's own keys, restored the moment the burst is over.
	private boolean originalForward;
	private boolean originalSprint;
	private boolean originalJump;
	private boolean holdingInput;

	// Spam scaling: the burst is paced by how fast the key is actually pressed.
	private long lastPressNanos;
	private double pressRateHz;
	private boolean queuedRequest;

	public AutoLungeSwapController(ProFPSConfig config) {
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
			abort(client);
			return;
		}

		ClientPlayerEntity player = client.player;
		int age = player.age;
		if (age > deadlineAge) {
			// Never leave the spear in hand because a phase stopped advancing.
			abort(client);
			return;
		}

		switch (phase) {
			case PREPARE -> prepare(client, player, age);
			case LAUNCH -> launch(client, player, age);
			case FIRE -> fire(client, player, age);
			case RECOVER -> recover(client, player, age);
			case IDLE -> { }
		}
	}

	/**
	 * Sprint-jump, then wait for the player to actually leave the ground.
	 *
	 * <p>This is what turns the burst into distance. A lunge landing while the
	 * player is still standing is scrubbed off almost immediately: on the ground
	 * vanilla multiplies horizontal velocity by the block's slipperiness every
	 * tick, which bleeds a burst away in two or three ticks and reads as sliding
	 * along the floor. In the air only the much gentler drag applies, so the same
	 * velocity carries for the whole arc.
	 *
	 * <p>Sprinting matters too: vanilla adds a forward impulse to a jump taken
	 * while sprinting, and that stacks with the lunge instead of replacing it.
	 */
	private void launch(MinecraftClient client, ClientPlayerEntity player, int age) {
		if (!player.isOnGround()) {
			applyMovement(client, player, false);
			if (age < nextActionAge) return;
			// Fire in this same tick rather than scheduling the next one. Lift-off
			// is only observable a tick after the jump input, and every further
			// tick spent arranging the swap is airtime the burst does not get to
			// travel through — waiting one more turns a long arc into a hop.
			phase = Phase.FIRE;
			fire(client, player, age);
			return;
		}
		if (age > launchDeadlineAge) {
			// Something is holding the player down — a ceiling, a slab, cobweb.
			// Lunge from the ground rather than abandoning the press entirely.
			phase = Phase.FIRE;
			fire(client, player, age);
			return;
		}
		applyMovement(client, player, true);
		status = "Launching";
		// One tick of airtime before the swap, plus an occasional extra so the
		// burst never lands on the same frame offset twice running.
		nextActionAge = age + 1 + (humanize() && rng.nextInt(4) == 0 ? 1 : 0);
	}

	/** Presses forward+sprint (and optionally jump) without losing the player's own keys. */
	private void applyMovement(MinecraftClient client, ClientPlayerEntity player, boolean jump) {
		if (!config.lungeSwapJump) return;
		if (!holdingInput) {
			originalForward = client.options.forwardKey.isPressed();
			originalSprint = client.options.sprintKey.isPressed();
			originalJump = client.options.jumpKey.isPressed();
			holdingInput = true;
		}
		client.options.forwardKey.setPressed(true);
		client.options.sprintKey.setPressed(true);
		client.options.jumpKey.setPressed(jump);
		player.setSprinting(true);
	}

	private void restoreInput(MinecraftClient client) {
		if (!holdingInput || client == null || client.options == null) return;
		client.options.forwardKey.setPressed(originalForward);
		client.options.sprintKey.setPressed(originalSprint);
		client.options.jumpKey.setPressed(originalJump);
		holdingInput = false;
	}

	/**
	 * Puts a fast item in hand and waits for its bar to fill. This is the whole
	 * reason the technique is quick: the bar that the swap hands to the spear is
	 * the carry item's, and a bare fist refills in about five ticks.
	 */
	private void prepare(MinecraftClient client, ClientPlayerEntity player, int age) {
		if (player.getInventory().getSelectedSlot() != carrySlot) {
			select(client, player, carrySlot);
			status = "Priming";
			return;
		}
		// The bar the server will divide by is this item's, so this is the gate.
		if (!SpearCombatPolicy.jabCharged(player.getAttackCooldownProgress(0.0F))) {
			status = "Charging";
			return;
		}
		if (age < nextActionAge) return;
		// Charge is full and the carry item is in hand. Take off first; the swap
		// itself is worthless against ground friction.
		phase = config.lungeSwapJump && player.isOnGround() ? Phase.LAUNCH : Phase.FIRE;
		launchDeadlineAge = age + LAUNCH_TIMEOUT_TICKS;
		nextActionAge = age;
	}

	/**
	 * The swap itself, in one dispatch: select the spear locally, emit the slot
	 * packet, then attack. Both land in the same server tick, in vanilla's order.
	 */
	private void fire(MinecraftClient client, ClientPlayerEntity player, int age) {
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_LUNGE)) return;
		ItemStack spear = player.getInventory().getStack(spearSlot);
		if (!isSpear(spear) || !hasLunge(spear)) {
			// Someone moved it mid-sequence; do not swing a random item.
			abort(client);
			return;
		}

		returnSlot = carrySlot;
		select(client, player, spearSlot);
		boolean swung = ((MinecraftClientInvoker) client).invokeDoAttack();
		if (!swung) player.swingHand(Hand.MAIN_HAND);

		PlayerEntity target = nearestTarget(client, player);
		if (config.lungeSpearMace && target != null) {
			CombatModeRuntime.armSpearMace(target.getUuid(), 2_400L);
		}
		phase = Phase.RECOVER;
		// At least one tick, so the return slot change cannot overtake the attack.
		// The swap back to the carry item is also what keeps the swing short, so
		// it should not wait any longer than that.
		nextActionAge = age + 1 + (humanize() ? rng.nextInt(2) : 0);
		status = config.lungeSpearMace && target != null ? "Spear → Mace armed" : "Recovering";
	}

	/**
	 * Back to the fast item. Holding the spear after the burst would drag the
	 * swing animation and the next bar through the spear's own slow recharge,
	 * which is exactly what the swap exists to avoid.
	 */
	private void recover(MinecraftClient client, ClientPlayerEntity player, int age) {
		// Keep carrying the burst while it is still in the air; letting go of
		// forward mid-arc costs the air control that steers the landing.
		if (!player.isOnGround()) applyMovement(client, player, false);
		if (age < nextActionAge) return;
		// Manual scrolling wins; only restore while our own spear is still up.
		if (player.getInventory().getSelectedSlot() == spearSlot) {
			select(client, player, returnSlot);
		}
		restoreInput(client);
		boolean chain = queuedRequest && allowed(client);
		reset();
		if (chain) {
			queuedRequest = false;
			begin(client);
		}
	}

	private void begin(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!SpearCombatPolicy.canStartLunge(
				player.getHungerManager().getFoodLevel(),
				player.hasVehicle(),
				player.isGliding(),
				player.isTouchingWater())) {
			if (player.getHungerManager().getFoodLevel() < SpearCombatPolicy.MIN_LUNGE_FOOD) {
				notify(client, "Auto Lunge Swap: need at least 3 hunger bars");
				return;
			}
			notify(client, "Auto Lunge Swap: unavailable while riding, gliding, or in water");
			return;
		}
		spearSlot = findLungeSpear(player);
		if (spearSlot < 0) {
			notify(client, "Auto Lunge Swap: need a Lunge spear in the hotbar");
			return;
		}
		carrySlot = findCarrySlot(player, spearSlot);
		if (carrySlot < 0) {
			notify(client, "Auto Lunge Swap: keep one hotbar slot free to swap from");
			return;
		}

		phase = Phase.PREPARE;
		// Human reaction, skipped once the player is clearly spamming — at that
		// point the rhythm is theirs and the module should not add to it.
		nextActionAge = player.age + (spamming() ? 0 : reactionTicks());
		deadlineAge = player.age + RESTORE_DEADLINE_TICKS;
		status = "Priming";
	}

	/** Sampled per activation so no two bursts share a frame offset. */
	private int reactionTicks() {
		if (!humanize()) return 0;
		return 1 + rng.nextInt(3);
	}

	private boolean humanize() {
		return config.lungeSwapHumanize;
	}

	/**
	 * The slot to swap out of and back into. An empty slot is the ideal — a bare
	 * fist carries no attack-speed modifier at all, so its bar fills fastest and
	 * the recovery swing is the shortest available.
	 */
	private int findCarrySlot(ClientPlayerEntity player, int excludeSlot) {
		for (int slot = 0; slot < 9; slot++) {
			if (slot == excludeSlot) continue;
			if (player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		// No free slot: fall back to something that at least is not another
		// weapon, so the bar still fills faster than the spear's own.
		for (int slot = 0; slot < 9; slot++) {
			if (slot == excludeSlot) continue;
			ItemStack stack = player.getInventory().getStack(slot);
			if (!isSpear(stack) && !stack.contains(DataComponentTypes.TOOL)) return slot;
		}
		return -1;
	}

	/** Best Lunge spear in the hotbar; a higher tier is a bigger burst. */
	private int findLungeSpear(ClientPlayerEntity player) {
		int best = -1;
		int bestLevel = 0;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!isSpear(stack)) continue;
			int level = lungeLevel(stack);
			if (level > bestLevel) {
				bestLevel = level;
				best = slot;
			}
		}
		return best;
	}

	private int lungeLevel(ItemStack stack) {
		if (stack.isEmpty() || !stack.hasEnchantments()) return 0;
		var enchantments = stack.getEnchantments();
		for (RegistryEntry<Enchantment> enchantment : enchantments.getEnchantments()) {
			if (enchantment.matchesKey(Enchantments.LUNGE)) return Math.max(1, enchantments.getLevel(enchantment));
		}
		return 0;
	}

	private boolean isSpear(ItemStack stack) {
		return !stack.isEmpty()
				&& stack.contains(DataComponentTypes.PIERCING_WEAPON)
				&& stack.contains(DataComponentTypes.KINETIC_WEAPON);
	}

	private boolean hasLunge(ItemStack stack) {
		return lungeLevel(stack) > 0;
	}

	// ── Spam scaling ──────────────────────────────────────────────────────────

	private void notePress() {
		long now = System.nanoTime();
		if (lastPressNanos != 0L) {
			double gapSeconds = (now - lastPressNanos) / 1_000_000_000.0D;
			if (gapSeconds > 1.5D) {
				pressRateHz = 0.0D;
			} else {
				double instant = 1.0D / Math.max(0.03D, gapSeconds);
				pressRateHz = pressRateHz * 0.45D + instant * 0.55D;
			}
		}
		lastPressNanos = now;
	}

	private boolean spamming() {
		if (!config.lungeSpamScaling || lastPressNanos == 0L) return false;
		return (System.nanoTime() - lastPressNanos) < 1_500_000_000L && pressRateHz >= 4.0D;
	}

	// ── Spear charge assist (unchanged, and independent of the swap) ──────────

	/**
	 * Visible aim support while the player manually holds a spear charge. This
	 * has nothing to do with the swap — a lunge is aimed where you want to go,
	 * not at a target — so the burst itself never steals the camera.
	 */
	public boolean frame(MinecraftClient client) {
		ownsRotation = false;
		if (!allowed(client) || !config.spearChargeAssist) return false;
		ClientPlayerEntity player = client.player;
		if (!player.isUsingItem() || !player.getActiveItem().contains(DataComponentTypes.KINETIC_WEAPON)) {
			return false;
		}
		PlayerEntity target = acquireTarget(client, config.spearChargeRange, config.spearChargeFov);
		if (target == null || !player.canSee(target)) return false;

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0D * 20.0D, 0.05D, 3.0D);
		lastFrameNanos = now;

		Vec3d eye = player.getEyePos();
		Vec3d point = target.getBoundingBox().getCenter().add(target.getVelocity().multiply(0.45D));
		double dx = point.x - eye.x;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		float desiredPitch = (float) -Math.toDegrees(Math.atan2(point.y - eye.y, horizontal));

		float speed = MathHelper.clamp(config.spearChargeTurnSpeed, 20, 85) / 100.0F;
		float blend = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchError = MathHelper.wrapDegrees(desiredPitch - player.getPitch());
		float cap = 22.0F * dt;

		player.setYaw(player.getYaw() + mouse.yaw(MathHelper.clamp(
				yawError * blend + (float) rng.nextGaussian() * 0.16F, -cap, cap)));
		player.setPitch(MathHelper.clamp(player.getPitch() + mouse.pitch(MathHelper.clamp(
				pitchError * blend + (float) rng.nextGaussian() * 0.11F, -cap * 0.72F, cap * 0.72F)),
				-90.0F, 90.0F));
		ownsRotation = true;
		return true;
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

	/** Nearest player in front, used only to arm the optional Spear → Mace follow-up. */
	private PlayerEntity nearestTarget(MinecraftClient client, ClientPlayerEntity self) {
		PlayerEntity best = null;
		double bestSq = 36.0D;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator()) continue;
			double sq = self.squaredDistanceTo(other);
			if (sq < bestSq) {
				bestSq = sq;
				best = other;
			}
		}
		return best;
	}

	// ── Plumbing ──────────────────────────────────────────────────────────────

	private void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((com.profps.client.mixin.ClientPlayerInteractionManagerAccessor) client.interactionManager)
				.profps$setLastSelectedSlot(slot);
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

	/** Gives up and puts the carry item back, whatever state the sequence was in. */
	private void abort(MinecraftClient client) {
		if (client != null && client.player != null && returnSlot >= 0
				&& client.player.getInventory().getSelectedSlot() == spearSlot) {
			select(client, client.player, returnSlot);
		}
		// Whatever went wrong, the player gets their own keys back.
		restoreInput(client);
		reset();
	}

	private void reset() {
		phase = Phase.IDLE;
		spearSlot = -1;
		carrySlot = -1;
		returnSlot = -1;
		nextActionAge = 0;
		deadlineAge = 0;
		ownsRotation = false;
		status = "Idle";
		// The spam estimate deliberately survives: it describes the player's
		// rhythm across bursts, which is the whole point of scaling to it.
	}

	private enum Phase {
		IDLE,
		/** Carry item in hand, letting its fast bar fill. */
		PREPARE,
		/** Sprint-jump, then wait for lift-off so the burst is not eaten by friction. */
		LAUNCH,
		/** One tick: slot packet then attack, same dispatch. */
		FIRE,
		/** Back to the carry item so the swing and the next bar stay fast. */
		RECOVER
	}
}
