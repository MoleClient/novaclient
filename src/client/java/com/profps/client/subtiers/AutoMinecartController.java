package com.profps.client.subtiers;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Confirmed rail placement -> confirmed TNT minecart -> movement-aware ballistic
 * bow shot. Every transition is acknowledged or retried; no phase advances from
 * a client-predicted interaction alone.
 */
public final class AutoMinecartController {
	private static final int MAX_CART_ATTEMPTS = 3;
	private static final int MAX_DRAW_ATTEMPTS = 3;
	private static final double ARROW_DRAG = 0.99D;
	private static final double ARROW_GRAVITY = 0.05D;

	private enum Phase { IDLE, WAIT_RAIL, WAIT_CART, AIMING, DRAWING, RECOVERING }

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final HumanizedRotation rotation = new HumanizedRotation();

	private Phase phase = Phase.IDLE;
	private BlockPos railHint;
	private BlockPos railFallback;
	private BlockPos railPos;
	private UUID minecartUuid;
	private final Set<Long> railsPresentBeforePlacement = new HashSet<>();
	private final Set<UUID> minecartsPresentBeforePlacement = new HashSet<>();

	private int minecartSlot = -1;
	private int bowSlot = -1;
	private int originalSlot = -1;
	private int placementAttempts;
	private int minecartCountBefore;
	private int drawAttempts;
	private int releaseUseTicks;
	private int lastObservedUseTicks;
	private int aimReadyMovementTicks;

	private boolean placementConsumed;
	private boolean placementAcceptedLocally;
	private boolean ownsUseKey;
	private boolean drawConfirmed;
	private boolean trajectoryReady;

	private long actionAtNanos;
	private long expireAtNanos;
	private long cartConfirmDeadlineNanos;
	private long lastPlacementAttemptNanos;
	private long drawConfirmByNanos;
	private long hardReleaseAtNanos;
	private long abortDrawAtNanos;
	private long restoreAtNanos;
	private long recoverUntilNanos;
	private long lastRailTriggerNanos;

	private volatile float aimError = Float.MAX_VALUE;
	private double hitX;
	private double hitY;
	private double hitZ;

	public AutoMinecartController(ProFPSConfig config) {
		this.config = config;
	}

	public ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
		if (!config.enabled || !config.subTiersAutoMinecart || !world.isClient() || hand != Hand.MAIN_HAND) {
			return ActionResult.PASS;
		}
		ItemStack held = player == null ? ItemStack.EMPTY : player.getStackInHand(hand);
		if (!isRailItem(held)) return ActionResult.PASS;

		long now = System.nanoTime();
		// Holding the physical placement click can invoke the callback again. Never let
		// that second callback erase an in-flight placement/shot sequence.
		if (phase != Phase.IDLE && phase != Phase.RECOVERING) return ActionResult.PASS;
		if (now - lastRailTriggerNanos < ns(180D)) return ActionResult.PASS;

		MinecraftClient client = MinecraftClient.getInstance();
		if (phase == Phase.RECOVERING) reset(client, true);
		lastRailTriggerNanos = now;
		originalSlot = player.getInventory().getSelectedSlot();

		ItemPlacementContext placement = new ItemPlacementContext(player, hand, held, hit);
		railHint = placement.getBlockPos().toImmutable();
		railFallback = hit.getBlockPos().offset(hit.getSide()).toImmutable();
		rememberNearbyObjects(world, railHint);

		phase = Phase.WAIT_RAIL;
		actionAtNanos = now; // the predicted block is normally visible by END_CLIENT_TICK
		expireAtNanos = now + ns(2800D);
		return ActionResult.PASS;
	}

	/**
	 * Runs at START_CLIENT_TICK, before vanilla input handling and the movement
	 * packet. It prevents the physical rail-click release from cancelling our bow
	 * and ensures the server receives the solved rotation before RELEASE_USE_ITEM.
	 */
	public void preTick(MinecraftClient client) {
		if (!allowed(client)) return;
		if (ownsUseKey && (phase == Phase.AIMING || phase == Phase.DRAWING)) {
			client.options.useKey.setPressed(true);
		}
		if (phase != Phase.AIMING && phase != Phase.DRAWING) return;
		TntMinecartEntity minecart = minecartByUuid(client, minecartUuid);
		if (minecart == null || minecart.isPrimed()) return;

		updateAim(client, minecart, true);
		if (trajectoryReady && aimError <= 1.35F) {
			aimReadyMovementTicks++;
		} else {
			aimReadyMovementTicks = 0;
		}
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) { reset(client, true); return; }
		if (phase == Phase.IDLE) return;
		long now = System.nanoTime();
		if (now > expireAtNanos) { startRecovery(client, now); return; }
		ClientPlayerEntity player = client.player;

		if (phase == Phase.RECOVERING) {
			if (originalSlot >= 0 && now >= restoreAtNanos) {
				selectSlotImmediate(client, originalSlot);
				originalSlot = -1;
			}
			if (now >= recoverUntilNanos) reset(client, false);
			return;
		}

		if (phase == Phase.WAIT_RAIL) {
			if (now < actionAtNanos) return;
			railPos = findPlacedRail(client);
			if (railPos == null) {
				actionAtNanos = now; // one poll per client tick; no fake sub-tick delay
				return;
			}

			TntMinecartEntity alreadyPlaced = findFreshMinecart(client);
			if (alreadyPlaced != null) {
				prepareBow(client, alreadyPlaced, now);
				return;
			}

			minecartSlot = findHotbarItem(player, Items.TNT_MINECART);
			if (minecartSlot < 0) { startRecovery(client, now); return; }
			minecartCountBefore = player.getInventory().getStack(minecartSlot).getCount();
			placementAttempts = 0;
			placementConsumed = false;
			placementAcceptedLocally = false;
			cartConfirmDeadlineNanos = now + ns(1500D);
			attemptCartPlacement(client, now); // same tick as rail confirmation
			return;
		}

		if (phase == Phase.WAIT_CART) {
			TntMinecartEntity minecart = findFreshMinecart(client);
			if (minecart != null) {
				prepareBow(client, minecart, now);
				return;
			}
			if (now >= cartConfirmDeadlineNanos) { startRecovery(client, now); return; }
			if (now < actionAtNanos) return;

			int currentCount = stackCount(player, minecartSlot, Items.TNT_MINECART);
			if (placementConsumed && currentCount < minecartCountBefore) {
				// Client/server still agree that the cart item was consumed. Await its
				// spawn packet instead of risking a duplicate placement.
				actionAtNanos = now + ns(45D);
				return;
			}
			if (placementAcceptedLocally && now - lastPlacementAttemptNanos < ns(180D)) {
				actionAtNanos = lastPlacementAttemptNanos + ns(180D);
				return;
			}
			if (placementAttempts < MAX_CART_ATTEMPTS) {
				minecartSlot = findHotbarItem(player, Items.TNT_MINECART);
				if (minecartSlot >= 0) {
					minecartCountBefore = player.getInventory().getStack(minecartSlot).getCount();
					placementConsumed = false;
					placementAcceptedLocally = false;
					attemptCartPlacement(client, now);
					return;
				}
			}
			actionAtNanos = now + ns(50D);
			return;
		}

		TntMinecartEntity minecart = minecartByUuid(client, minecartUuid);
		if (minecart == null || minecart.isPrimed()) { startRecovery(client, now); return; }

		if (phase == Phase.AIMING) {
			if (now < actionAtNanos) return;
			if (isDrawingBow(player)) {
				confirmDraw(client, player, now);
				return;
			}
			if (player.isUsingItem()) { startRecovery(client, now); return; }
			tryStartBowDraw(client, now);
			return;
		}

		if (phase == Phase.DRAWING) {
			// END tick runs after other controllers: keep ownership asserted here too.
			if (ownsUseKey) client.options.useKey.setPressed(true);
			if (isDrawingBow(player)) {
				drawConfirmed = true;
				lastObservedUseTicks = Math.max(lastObservedUseTicks, player.getItemUseTime());
			} else if (!drawConfirmed && now < drawConfirmByNanos) {
				return;
			} else if (!drawConfirmed && drawAttempts < MAX_DRAW_ATTEMPTS) {
				client.options.useKey.setPressed(false);
				ownsUseKey = false;
				phase = Phase.AIMING;
				actionAtNanos = now;
				return;
			} else if (!drawConfirmed) {
				startRecovery(client, now);
				return;
			} else {
				// The active use vanished before our intentional release. Retry only if
				// it was too short to have produced a valid arrow.
				if (lastObservedUseTicks < 3 && drawAttempts < MAX_DRAW_ATTEMPTS) {
					drawConfirmed = false;
					ownsUseKey = false;
					phase = Phase.AIMING;
					actionAtNanos = now;
				} else {
					startRecovery(client, now);
				}
				return;
			}

			int usedTicks = player.getItemUseTime();
			boolean settled = trajectoryReady && aimReadyMovementTicks >= 1 && aimError <= 1.35F;
			if (usedTicks >= releaseUseTicks && settled) {
				fireBow(client, player, now);
				return;
			}
			if (now >= hardReleaseAtNanos && trajectoryReady
					&& aimReadyMovementTicks >= 1 && aimError <= 2.5F) {
				fireBow(client, player, now);
				return;
			}
			if (now >= abortDrawAtNanos) {
				// Never solve a stuck bow by firing a known miss.
				startRecovery(client, now);
			}
		}
	}

	public void frame(MinecraftClient client) {
		if (!allowed(client) || phase == Phase.IDLE || phase == Phase.WAIT_RAIL || phase == Phase.WAIT_CART) return;
		ClientPlayerEntity player = client.player;
		if (phase == Phase.RECOVERING) {
			aimError = rotation.recover(player);
			return;
		}
		TntMinecartEntity minecart = minecartByUuid(client, minecartUuid);
		if (minecart != null && !minecart.isPrimed()) updateAim(client, minecart, false);
	}

	private void attemptCartPlacement(MinecraftClient client, long now) {
		if (!validRail(client) || placementAttempts >= MAX_CART_ATTEMPTS) {
			startRecovery(client, now);
			return;
		}
		TntMinecartEntity existing = findFreshMinecart(client);
		if (existing != null) {
			prepareBow(client, existing, now);
			return;
		}

		ClientPlayerEntity player = client.player;
		if (minecartSlot < 0 || !player.getInventory().getStack(minecartSlot).isOf(Items.TNT_MINECART)) {
			minecartSlot = findHotbarItem(player, Items.TNT_MINECART);
		}
		if (minecartSlot < 0) {
			phase = Phase.WAIT_CART; // an accepted last item may be awaiting its spawn packet
			actionAtNanos = now + ns(45D);
			return;
		}

		selectSlotForAction(player, minecartSlot);
		int before = player.getInventory().getStack(minecartSlot).getCount();
		BlockHitResult railClick = new BlockHitResult(
				new Vec3d(railPos.getX() + 0.5D, railPos.getY() + 0.0625D, railPos.getZ() + 0.5D),
				Direction.UP, railPos, false);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, railClick);
		// Consume the physical rail-placement hold so vanilla cannot issue a second
		// minecart interaction on the following tick.
		client.options.useKey.setPressed(false);
		int after = stackCount(player, minecartSlot, Items.TNT_MINECART);
		placementAttempts++;
		lastPlacementAttemptNanos = now;
		placementAcceptedLocally = result.isAccepted();
		placementConsumed = after < before;
		if (result.isAccepted()) player.swingHand(Hand.MAIN_HAND);
		phase = Phase.WAIT_CART;
		actionAtNanos = result.isAccepted() ? now + ns(45D) : now;
	}

	private void prepareBow(MinecraftClient client, TntMinecartEntity minecart, long now) {
		ClientPlayerEntity player = client.player;
		bowSlot = findBestBow(player);
		if (bowSlot < 0) { startRecovery(client, now); return; }

		minecartUuid = minecart.getUuid();
		hitX = 0.47D + rng.nextDouble() * 0.06D;
		hitY = 0.60D + rng.nextDouble() * 0.10D;
		hitZ = 0.47D + rng.nextDouble() * 0.06D;
		releaseUseTicks = 5;
		drawAttempts = 0;
		aimReadyMovementTicks = 0;
		lastObservedUseTicks = 0;
		trajectoryReady = false;
		rotation.begin(player, rng, true, bowAimSpeedScale());
		selectSlotForAction(player, bowSlot);
		phase = Phase.AIMING;
		actionAtNanos = now;
		updateAim(client, minecart, false);
		tryStartBowDraw(client, now); // slot sync + bow use are ordered in this interaction
	}

	private void tryStartBowDraw(MinecraftClient client, long now) {
		ClientPlayerEntity player = client.player;
		if (bowSlot < 0 || !usableBow(player, player.getInventory().getStack(bowSlot))) {
			startRecovery(client, now);
			return;
		}
		selectSlotForAction(player, bowSlot);
		drawAttempts++;
		ownsUseKey = true;
		client.options.useKey.setPressed(true);
		ActionResult result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
		if (!result.isAccepted()) {
			client.options.useKey.setPressed(false);
			ownsUseKey = false;
			if (drawAttempts < MAX_DRAW_ATTEMPTS) {
				phase = Phase.AIMING;
				actionAtNanos = now;
			} else {
				startRecovery(client, now);
			}
			return;
		}
		beginDrawWindow(player, now);
	}

	private void confirmDraw(MinecraftClient client, ClientPlayerEntity player, long now) {
		ownsUseKey = true;
		client.options.useKey.setPressed(true);
		beginDrawWindow(player, now);
	}

	private void beginDrawWindow(ClientPlayerEntity player, long now) {
		drawConfirmed = isDrawingBow(player);
		lastObservedUseTicks = drawConfirmed ? player.getItemUseTime() : 0;
		double windowScale = bowTimingScale();
		drawConfirmByNanos = now + ns(150D * windowScale);
		hardReleaseAtNanos = now + ns((650D + rng.nextDouble() * 55D) * windowScale);
		abortDrawAtNanos = now + ns(900D * windowScale);
		phase = Phase.DRAWING;
	}

	private void updateAim(MinecraftClient client, TntMinecartEntity minecart, boolean anticipateUseTick) {
		ClientPlayerEntity player = client.player;
		int actualUse = isDrawingBow(player) ? player.getItemUseTime() : 0;
		int predictedUse = actualUse + (anticipateUseTick && isDrawingBow(player) ? 1 : 0);
		int minimum = Math.max(5, Math.max(releaseUseTicks, predictedUse));

		AimSolution solution = null;
		int solutionTicks = -1;
		for (int charge = Math.min(minimum, 20); charge <= 20; charge++) {
			solution = solveBallisticAim(player, minecart, charge, anticipateUseTick);
			if (solution != null) { solutionTicks = charge; break; }
		}
		if (solution == null) {
			trajectoryReady = false;
			aimError = Float.MAX_VALUE;
			return;
		}
		releaseUseTicks = Math.max(releaseUseTicks, solutionTicks);
		trajectoryReady = true;
		aimError = rotation.aimAt(player, player.getEyePos().add(solution.direction().multiply(12.0D)));
	}

	/**
	 * Solves the first low-time ballistic intercept under vanilla arrow physics.
	 * The required launch velocity subtracts the movement inherited from the
	 * shooter, which is the critical correction while walking backward.
	 */
	private AimSolution solveBallisticAim(ClientPlayerEntity player, TntMinecartEntity minecart,
			int chargeTicks, boolean anticipateMovement) {
		double speed = BowItem.getPullProgress(chargeTicks) * 3.0D;
		if (speed < 0.30D) return null;

		Vec3d movement = player.getMovement();
		Vec3d eye = anticipateMovement ? player.getEyePos().add(movement) : player.getEyePos();
		Box box = minecart.getBoundingBox();
		Vec3d target = new Vec3d(
				MathHelper.lerp(hitX, box.minX, box.maxX),
				MathHelper.lerp(hitY, box.minY, box.maxY),
				MathHelper.lerp(hitZ, box.minZ, box.maxZ));
		Vec3d targetVelocity = minecart.getMovement();
		if (anticipateMovement) target = target.add(targetVelocity);
		Vec3d inherited = new Vec3d(movement.x, player.isOnGround() ? 0.0D : movement.y, movement.z);

		double previousTime = 0.35D;
		double previousError = requiredAimVelocity(eye, target, targetVelocity, inherited, previousTime).length() - speed;
		for (double time = 0.55D; time <= 36.0D; time += 0.20D) {
			Vec3d required = requiredAimVelocity(eye, target, targetVelocity, inherited, time);
			double error = required.length() - speed;
			if (previousError > 0.0D && error <= 0.0D) {
				double lo = previousTime;
				double hi = time;
				for (int i = 0; i < 20; i++) {
					double mid = (lo + hi) * 0.5D;
					double midError = requiredAimVelocity(eye, target, targetVelocity, inherited, mid).length() - speed;
					if (midError > 0.0D) lo = mid; else hi = mid;
				}
				double flightTime = (lo + hi) * 0.5D;
				Vec3d aimVelocity = requiredAimVelocity(eye, target, targetVelocity, inherited, flightTime);
				if (aimVelocity.lengthSquared() < 1.0E-8D) return null;
				return new AimSolution(aimVelocity.normalize(), flightTime);
			}
			previousTime = time;
			previousError = error;
		}
		return null;
	}

	private Vec3d requiredAimVelocity(Vec3d eye, Vec3d target, Vec3d targetVelocity,
			Vec3d inheritedMovement, double flightTicks) {
		double dragPow = Math.pow(ARROW_DRAG, flightTicks);
		double velocitySum = (1.0D - dragPow) / (1.0D - ARROW_DRAG);
		double gravityDrop = ARROW_GRAVITY * ARROW_DRAG / (1.0D - ARROW_DRAG)
				* (flightTicks - velocitySum);
		Vec3d future = target.add(targetVelocity.multiply(flightTicks));
		Vec3d requiredTotal = new Vec3d(
				(future.x - eye.x) / velocitySum,
				(future.y - eye.y + gravityDrop) / velocitySum,
				(future.z - eye.z) / velocitySum);
		return requiredTotal.subtract(inheritedMovement);
	}

	private void fireBow(MinecraftClient client, ClientPlayerEntity player, long now) {
		client.options.useKey.setPressed(false);
		ownsUseKey = false;
		if (isDrawingBow(player)) client.interactionManager.stopUsingItem(player);
		startRecovery(client, now);
	}

	private void startRecovery(MinecraftClient client, long now) {
		cancelOwnedDraw(client);
		phase = Phase.RECOVERING;
		restoreAtNanos = now + ns(55D + rng.nextDouble() * 75D);
		recoverUntilNanos = now + ns(230D + rng.nextDouble() * 210D);
		expireAtNanos = recoverUntilNanos + ns(120D);
	}

	/** Cancel an invalid draw by changing slots, never by releasing a stray arrow. */
	private void cancelOwnedDraw(MinecraftClient client) {
		if (!ownsUseKey || client == null || client.options == null) return;
		client.options.useKey.setPressed(false);
		ownsUseKey = false;
		if (client.player == null || !isDrawingBow(client.player)) return;

		int cancelSlot = originalSlot >= 0 && originalSlot != bowSlot ? originalSlot : firstSlotOtherThan(bowSlot);
		if (cancelSlot >= 0) selectSlotImmediate(client, cancelSlot);
		client.player.clearActiveItem();
	}

	private BlockPos findPlacedRail(MinecraftClient client) {
		BlockPos direct = newRailAt(client, railHint);
		if (direct != null) return direct;
		direct = newRailAt(client, railFallback);
		if (direct != null) return direct;

		BlockPos best = null;
		double bestSq = Double.MAX_VALUE;
		BlockPos center = railHint != null ? railHint : railFallback;
		if (center == null) return null;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos pos = center.add(dx, dy, dz);
					if (newRailAt(client, pos) == null) continue;
					double sq = pos.getSquaredDistance(center);
					if (sq < bestSq) { bestSq = sq; best = pos.toImmutable(); }
				}
			}
		}
		return best;
	}

	private BlockPos newRailAt(MinecraftClient client, BlockPos pos) {
		if (pos == null || railsPresentBeforePlacement.contains(pos.asLong())) return null;
		return client.world.getBlockState(pos).isIn(BlockTags.RAILS) ? pos.toImmutable() : null;
	}

	private TntMinecartEntity findFreshMinecart(MinecraftClient client) {
		if (railPos == null) return null;
		Box search = new Box(railPos).expand(6.5D, 3.0D, 6.5D);
		TntMinecartEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (TntMinecartEntity cart : client.world.getEntitiesByClass(
				TntMinecartEntity.class, search, Entity::isAlive)) {
			if (minecartsPresentBeforePlacement.contains(cart.getUuid())) continue;
			double sq = cart.squaredDistanceTo(Vec3d.ofCenter(railPos));
			if (sq < bestSq) { bestSq = sq; best = cart; }
		}
		return best;
	}

	private void rememberNearbyObjects(World world, BlockPos center) {
		railsPresentBeforePlacement.clear();
		minecartsPresentBeforePlacement.clear();
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockPos pos = center.add(dx, dy, dz);
					if (world.getBlockState(pos).isIn(BlockTags.RAILS)) railsPresentBeforePlacement.add(pos.asLong());
				}
			}
		}
		Box search = new Box(center).expand(8.0D, 4.0D, 8.0D);
		for (TntMinecartEntity cart : world.getEntitiesByClass(
				TntMinecartEntity.class, search, Entity::isAlive)) {
			minecartsPresentBeforePlacement.add(cart.getUuid());
		}
	}

	private TntMinecartEntity minecartByUuid(MinecraftClient client, UUID uuid) {
		if (uuid == null || client.world == null) return null;
		Entity entity = client.world.getEntity(uuid);
		return entity instanceof TntMinecartEntity cart && cart.isAlive() ? cart : null;
	}

	private int findBestBow(ClientPlayerEntity player) {
		int best = -1;
		int bestFlame = -1;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!usableBow(player, stack)) continue;
			int flame = enchantmentLevel(stack, Enchantments.FLAME);
			if (flame > bestFlame) { bestFlame = flame; best = slot; }
		}
		return best;
	}

	private boolean usableBow(ClientPlayerEntity player, ItemStack stack) {
		return stack.isOf(Items.BOW)
				&& (player.isInCreativeMode() || !player.getProjectileType(stack).isEmpty());
	}

	private int enchantmentLevel(ItemStack stack,
			net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key) {
		var enchantments = EnchantmentHelper.getEnchantments(stack);
		for (var enchantment : enchantments.getEnchantments()) {
			if (enchantment.matchesKey(key)) return enchantments.getLevel(enchantment);
		}
		return 0;
	}

	private int findHotbarItem(ClientPlayerEntity player, net.minecraft.item.Item item) {
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getStack(slot).isOf(item)) return slot;
		}
		return -1;
	}

	private int stackCount(ClientPlayerEntity player, int slot, net.minecraft.item.Item item) {
		if (slot < 0 || slot > 8) return 0;
		ItemStack stack = player.getInventory().getStack(slot);
		return stack.isOf(item) ? stack.getCount() : 0;
	}

	private int firstSlotOtherThan(int excluded) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return -1;
		for (int slot = 0; slot < 9; slot++) {
			if (slot != excluded && !client.player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		return -1;
	}

	private boolean isRailItem(ItemStack stack) {
		return stack.isOf(Items.RAIL) || stack.isOf(Items.POWERED_RAIL)
				|| stack.isOf(Items.DETECTOR_RAIL) || stack.isOf(Items.ACTIVATOR_RAIL);
	}

	private boolean isDrawingBow(ClientPlayerEntity player) {
		return player.isUsingItem() && player.getActiveHand() == Hand.MAIN_HAND
				&& player.getActiveItem().isOf(Items.BOW);
	}

	private boolean validRail(MinecraftClient client) {
		return railPos != null && client.world != null && client.world.getBlockState(railPos).isIn(BlockTags.RAILS);
	}

	private void selectSlotForAction(ClientPlayerEntity player, int slot) {
		if (slot >= 0 && slot <= 8 && player.getInventory().getSelectedSlot() != slot) {
			player.getInventory().setSelectedSlot(slot);
		}
	}

	private void selectSlotImmediate(MinecraftClient client, int slot) {
		if (slot < 0 || slot > 8 || client.player == null) return;
		if (client.player.getInventory().getSelectedSlot() != slot) {
			client.player.getInventory().setSelectedSlot(slot);
		}
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		}
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || !config.subTiersAutoMinecart) return false;
		ClientPlayerEntity player = client == null ? null : client.player;
		return player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == null && player.isAlive() && !player.isSpectator();
	}

	/** Level 4 is the original 1.45x camera response; higher levels ramp quickly. */
	private double bowAimSpeedScale() {
		int speed = MathHelper.clamp(config.subTiersMinecartBowSpeed, 1, 10);
		return MathHelper.clamp(1.45D * Math.pow(1.24D, speed - 4), 0.72D, 4.0D);
	}

	/** Shorten retry/failsafe windows without bypassing the accurate trajectory gate. */
	private double bowTimingScale() {
		int speed = MathHelper.clamp(config.subTiersMinecartBowSpeed, 1, 10);
		return MathHelper.clamp(Math.pow(0.88D, speed - 4), 0.48D, 1.5D);
	}

	private long ns(double ms) { return (long) (ms * 1_000_000D); }

	private void reset(MinecraftClient client, boolean restoreSlot) {
		cancelOwnedDraw(client);
		if (restoreSlot && originalSlot >= 0 && client != null && client.player != null) {
			selectSlotImmediate(client, originalSlot);
		}
		phase = Phase.IDLE;
		railHint = null;
		railFallback = null;
		railPos = null;
		minecartUuid = null;
		railsPresentBeforePlacement.clear();
		minecartsPresentBeforePlacement.clear();
		minecartSlot = -1;
		bowSlot = -1;
		originalSlot = -1;
		placementAttempts = 0;
		minecartCountBefore = 0;
		drawAttempts = 0;
		releaseUseTicks = 0;
		lastObservedUseTicks = 0;
		aimReadyMovementTicks = 0;
		placementConsumed = false;
		placementAcceptedLocally = false;
		drawConfirmed = false;
		trajectoryReady = false;
		actionAtNanos = 0L;
		expireAtNanos = 0L;
		cartConfirmDeadlineNanos = 0L;
		lastPlacementAttemptNanos = 0L;
		drawConfirmByNanos = 0L;
		hardReleaseAtNanos = 0L;
		abortDrawAtNanos = 0L;
		restoreAtNanos = 0L;
		recoverUntilNanos = 0L;
		aimError = Float.MAX_VALUE;
		rotation.reset();
	}

	private record AimSolution(Vec3d direction, double flightTicks) {}
}
