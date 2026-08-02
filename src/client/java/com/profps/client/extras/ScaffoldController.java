package com.profps.client.extras;

import com.profps.client.aim.MouseGcd;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scaffold with Vape's three mode contracts.
 *
 * <p>Legit only edge-sneaks. GodBridge and TellyBridge arm after the configured number of
 * manual placements made while Back is held. Once armed, the bridge heading is fixed in world
 * space, so visible placement rotations cannot bend the movement path. Automated movement is
 * released only after the next landing block exists; Telly jump is a one-tick pulse following a
 * confirmed placement instead of a permanently-held jump key.</p>
 */
public final class ScaffoldController {
	public static final int LEGIT = 0;
	public static final int GOD_BRIDGE = 1;
	public static final int TELLY_BRIDGE = 2;
	private static final double REACH = 4.5D;
	private static final long ACTIVATION_TIMEOUT_NS = 2_000_000_000L;
	private static final long PLACE_INTERVAL_NS = 46_000_000L;
	private static final double[] TARGET_DISTANCES = {0.35D, 0.78D, 1.18D, 1.58D};
	private static ScaffoldController instance;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();
	private final Set<BlockPos> supportSnapshot = new HashSet<>();

	private int lastMode = -1;
	private int previousSlot = -1;
	private int lastBlockCount = -1;
	private int activationBlocks;
	private int pendingActivationPlacements;
	private BlockPos firstActivation;
	private BlockPos lastActivation;
	private long lastActivationNanos;
	private long lastPlaceNanos;
	private long sneakReleaseNanos;
	private int tellyPlacements;
	private int tellyBaseY = Integer.MIN_VALUE;
	private BlockPos awaitingPlacement;
	private BlockPos legitAwaitingPlacement;
	private boolean awaitingTellyJump;
	private Vec3d bridgeDirection = Vec3d.ZERO;
	private Vec3d legitDirection = Vec3d.ZERO;
	private boolean activeAutomation;
	private boolean activationSneak;
	private boolean legitPlacementActive;
	private boolean movementReady;
	private boolean tellyJumpPending;
	private String status = "Idle";

	public ScaffoldController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/** Layered from KeyboardInput after physical keys have been read. */
	public static PlayerInput movementOverride(PlayerInput physical) {
		ScaffoldController self = instance;
		if (self == null || physical == null || !self.config.enabled || !self.config.scaffoldAssist) return null;
		return self.override(physical);
	}

	private PlayerInput override(PlayerInput physical) {
		if (config.scaffoldMode == LEGIT) {
			if (System.nanoTime() >= sneakReleaseNanos) return null;
			if (legitPlacementActive && physical.backward() && legitDirection.lengthSquared() >= 0.5D) {
				PlayerInput directed = inputForWorldDirection(legitDirection, false);
				return new PlayerInput(directed.forward(), directed.backward(), directed.left(), directed.right(),
						physical.jump(), true, physical.sprint());
			}
			return new PlayerInput(physical.forward(), physical.backward(), physical.left(), physical.right(),
					physical.jump(), true, physical.sprint());
		}
		if (!activeAutomation) {
			if (!activationSneak || !physical.backward()) return null;
			return new PlayerInput(physical.forward(), physical.backward(), physical.left(), physical.right(),
					physical.jump(), true, physical.sprint());
		}
		if (!physical.backward()) return null;
		if (config.scaffoldMode == TELLY_BRIDGE && config.scaffoldTellyRequireRightClick
				&& !MinecraftClient.getInstance().options.useKey.isPressed()) return null;
		if (!movementReady || bridgeDirection.lengthSquared() < 0.5D) {
			// The physical Back key remains the activation/deactivation contract, but it may not
			// walk the player over an unplaced edge while rotation is still converging.
			return new PlayerInput(false, false, false, false, false, true, false);
		}
		boolean jump = config.scaffoldMode == TELLY_BRIDGE && tellyJumpPending;
		if (jump) tellyJumpPending = false;
		return inputForWorldDirection(bridgeDirection, jump);
	}

	private PlayerInput inputForWorldDirection(Vec3d desired, boolean jump) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return PlayerInput.DEFAULT;
		double yaw = Math.toRadians(client.player.getYaw());
		Vec3d forward = new Vec3d(-Math.sin(yaw), 0.0D, Math.cos(yaw));
		Vec3d left = new Vec3d(Math.cos(yaw), 0.0D, Math.sin(yaw));
		double bestDot = -Double.MAX_VALUE;
		int bestForward = 0;
		int bestLeft = 0;
		for (int f = -1; f <= 1; f++) {
			for (int l = -1; l <= 1; l++) {
				if (f == 0 && l == 0) continue;
				Vec3d candidate = forward.multiply(f).add(left.multiply(l)).normalize();
				double dot = candidate.dotProduct(desired);
				if (dot > bestDot) {
					bestDot = dot;
					bestForward = f;
					bestLeft = l;
				}
			}
		}
		return new PlayerInput(bestForward > 0, bestForward < 0, bestLeft > 0, bestLeft < 0,
				jump, false, true);
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			reset(client, true);
			return;
		}
		ClientPlayerEntity player = client.player;
		int mode = MathHelper.clamp(config.scaffoldMode, LEGIT, TELLY_BRIDGE);
		if (mode != lastMode) {
			reset(client, true);
			lastMode = mode;
		}
		if (config.scaffoldPitchCheck && player.getPitch() < config.scaffoldPitch) {
			status = "Pitch check";
			activeAutomation = false;
			movementReady = false;
			return;
		}
		if (mode == LEGIT) {
			tickLegit(client, player);
			return;
		}

		observeActivation(client, player);
		int needed = mode == GOD_BRIDGE ? config.scaffoldGodActivationBlocks : config.scaffoldTellyActivationBlocks;
		if (activationBlocks < needed) {
			activeAutomation = false;
			movementReady = false;
			activationSneak = client.options.backKey.isPressed() && player.isOnGround() && isOverEdge(client, player);
			status = client.options.backKey.isPressed()
					? "Place blocks " + activationBlocks + "/" + needed
					: "Hold Back + place " + needed;
			return;
		}
		if (System.nanoTime() - lastActivationNanos > ACTIVATION_TIMEOUT_NS && !activeAutomation) {
			resetActivation();
			return;
		}
		if (!activeAutomation) beginAutomation(client, player, mode);
		if (!client.options.backKey.isPressed()) {
			reset(client, true);
			status = "Hold Back";
			return;
		}
		if (mode == TELLY_BRIDGE && config.scaffoldTellyRequireRightClick && !client.options.useKey.isPressed()) {
			movementReady = false;
			status = "Hold Back + right click";
			return;
		}
		tickAutomation(client, player, mode);
	}

	private void tickLegit(MinecraftClient client, ClientPlayerEntity player) {
		activeAutomation = false;
		activationSneak = false;
		movementReady = false;
		if (config.scaffoldRequireSneak && !client.options.sneakKey.isPressed()) {
			status = "Require sneak";
			return;
		}
		PlayerInput input = player.input.playerInput;
		if (input.forward() || !player.isOnGround()) {
			legitPlacementActive = false;
			legitDirection = Vec3d.ZERO;
			status = "Legit";
			return;
		}
		if (!isOverEdge(client, player)) {
			legitPlacementActive = false;
			legitDirection = Vec3d.ZERO;
			status = "Legit";
			return;
		}
		int min = MathHelper.clamp(config.scaffoldSneakDelayMinMs, 0, 500);
		int max = MathHelper.clamp(config.scaffoldSneakDelayMaxMs, min, 500);
		long delayMs = min + (max <= min ? 0 : rng.nextInt(max - min + 1));
		sneakReleaseNanos = System.nanoTime() + delayMs * 1_000_000L;
		status = "Edge sneak";

		// Legit Auto Place deliberately uses only the selected main-hand stack. It never
		// silently changes slots: walk backward with an allowed block held and the same
		// visible, legal ray a real right-click would use performs the placement.
		if (!input.backward() || !usableBlock(player.getMainHandStack())) {
			legitPlacementActive = false;
			legitDirection = Vec3d.ZERO;
			return;
		}
		if (legitAwaitingPlacement != null) {
			if (!client.world.getBlockState(legitAwaitingPlacement).isReplaceable()) {
				legitAwaitingPlacement = null;
				legitPlacementActive = false;
				legitDirection = Vec3d.ZERO;
				status = "Legit placed";
				return;
			}
			if (System.nanoTime() - lastPlaceNanos < 350_000_000L) {
				status = "Confirming block";
				return;
			}
			legitAwaitingPlacement = null;
		}

		Vec3d direction = player.getMovement().multiply(1.0D, 0.0D, 1.0D);
		if (direction.lengthSquared() < 1.0E-4D) {
			Vec3d look = player.getRotationVector();
			direction = new Vec3d(-look.x, 0.0D, -look.z);
		}
		if (direction.lengthSquared() < 1.0E-4D) return;
		direction = direction.normalize();
		legitDirection = direction;
		legitPlacementActive = true;
		Placement placement = nextPlacement(client, player, placementY(player), direction);
		if (placement == null) {
			legitPlacementActive = false;
			legitDirection = Vec3d.ZERO;
			return;
		}
		long now = System.nanoTime();
		if (now - lastPlaceNanos < PLACE_INTERVAL_NS) return;

		// Legit places silently against the support face, exactly like Clutch and Height
		// Clutch do. It must never steal yaw/pitch: the whole point of Legit is that you
		// keep aiming wherever you like while it edge-sneaks and bridges under you.
		BlockHitResult ray = new BlockHitResult(placement.point(), placement.face(),
				placement.support(), false);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, ray);
		if (!result.isAccepted()) {
			status = "Placement refused";
			return;
		}
		player.swingHand(Hand.MAIN_HAND);
		lastPlaceNanos = now;
		legitAwaitingPlacement = placement.placeAt();
		status = "Confirming block";
	}

	private boolean isOverEdge(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d motion = player.getMovement();
		Box probe = player.getBoundingBox().contract(0.20D, 0.0D, 0.20D)
				.offset(motion.x, -0.08D, motion.z);
		return !client.world.getBlockCollisions(player, probe).iterator().hasNext();
	}

	private void observeActivation(MinecraftClient client, ClientPlayerEntity player) {
		int count = countUsableBlocks(player);
		Set<BlockPos> current = nearbySupports(client, player);
		if (!client.options.backKey.isPressed()) {
			if (!activeAutomation) resetActivationProgress(count, current);
			return;
		}
		if (lastBlockCount < 0) {
			lastBlockCount = count;
			supportSnapshot.clear();
			supportSnapshot.addAll(current);
			return;
		}
		if (!activeAutomation && count < lastBlockCount) {
			pendingActivationPlacements += Math.min(4, lastBlockCount - count);
		}
		if (!activeAutomation && pendingActivationPlacements > 0) {
			List<BlockPos> additions = new ArrayList<>();
			for (BlockPos pos : current) if (!supportSnapshot.contains(pos)) additions.add(pos);
			additions.sort(Comparator.comparingDouble(pos -> Vec3d.ofCenter(pos)
					.squaredDistanceTo(player.getX(), player.getY(), player.getZ())));
			for (BlockPos pos : additions) {
				if (pendingActivationPlacements <= 0) break;
				if (pos.equals(lastActivation)) continue;
				if (firstActivation == null) firstActivation = pos;
				lastActivation = pos;
				activationBlocks++;
				pendingActivationPlacements--;
				lastActivationNanos = System.nanoTime();
			}
		}
		lastBlockCount = count;
		supportSnapshot.clear();
		supportSnapshot.addAll(current);
	}

	private Set<BlockPos> nearbySupports(MinecraftClient client, ClientPlayerEntity player) {
		Set<BlockPos> supports = new HashSet<>();
		BlockPos feet = player.getBlockPos();
		int baseY = placementY(player);
		for (int y = baseY - 1; y <= baseY + 1; y++) {
			for (int x = -3; x <= 3; x++) {
				for (int z = -3; z <= 3; z++) {
					BlockPos pos = new BlockPos(feet.getX() + x, y, feet.getZ() + z);
					if (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()) {
						supports.add(pos.toImmutable());
					}
				}
			}
		}
		return supports;
	}

	private void resetActivationProgress(int blockCount, Set<BlockPos> supports) {
		activationBlocks = 0;
		pendingActivationPlacements = 0;
		firstActivation = null;
		lastActivation = null;
		lastActivationNanos = 0L;
		lastBlockCount = blockCount;
		supportSnapshot.clear();
		supportSnapshot.addAll(supports);
	}

	private void beginAutomation(MinecraftClient client, ClientPlayerEntity player, int mode) {
		activeAutomation = true;
		activationSneak = false;
		movementReady = false;
		tellyJumpPending = false;
		previousSlot = player.getInventory().getSelectedSlot();
		Vec3d raw = firstActivation != null && lastActivation != null && !firstActivation.equals(lastActivation)
				? Vec3d.ofCenter(lastActivation).subtract(Vec3d.ofCenter(firstActivation))
				: player.getRotationVector().multiply(-1.0D);
		bridgeDirection = snapHorizontal(raw, mode == GOD_BRIDGE);
		tellyBaseY = lastActivation == null ? placementY(player) : lastActivation.getY();
		tellyPlacements = 0;
		status = mode == GOD_BRIDGE ? "GodBridge armed" : "TellyBridge armed";
	}

	private Vec3d snapHorizontal(Vec3d raw, boolean diagonal) {
		double angle = Math.atan2(raw.z, raw.x);
		double step = diagonal ? Math.PI / 4.0D : Math.PI / 2.0D;
		double snapped = Math.round(angle / step) * step;
		return new Vec3d(Math.cos(snapped), 0.0D, Math.sin(snapped));
	}

	private void tickAutomation(MinecraftClient client, ClientPlayerEntity player, int mode) {
		if (awaitingPlacement != null) {
			if (!client.world.getBlockState(awaitingPlacement).isReplaceable()) {
				awaitingPlacement = null;
				movementReady = true;
				if (mode == TELLY_BRIDGE) {
					tellyPlacements++;
					tellyJumpPending = awaitingTellyJump && player.isOnGround();
				}
				awaitingTellyJump = false;
				status = mode == GOD_BRIDGE ? "GodBridge" : "TellyBridge";
				return;
			} else if (System.nanoTime() - lastPlaceNanos < 350_000_000L) {
				movementReady = false;
				status = "Confirming block";
				return;
			} else {
				awaitingPlacement = null;
				awaitingTellyJump = false;
			}
		}
		int slot = findUsableBlockSlot(player);
		if (slot < 0) {
			movementReady = false;
			status = "No blocks";
			return;
		}
		int targetY = mode == TELLY_BRIDGE ? tellyTargetY() : placementY(player);
		Placement placement = nextPlacement(client, player, targetY);
		if (placement == null) {
			movementReady = hasSafeLanding(client, player, targetY);
			status = movementReady ? (mode == GOD_BRIDGE ? "GodBridge" : "TellyBridge") : "No placement";
			return;
		}

		movementReady = false;
		turnToward(player, placement.point());
		BlockHitResult ray = liveBlockHit(player);
		if (ray == null || !ray.getBlockPos().equals(placement.support()) || ray.getSide() != placement.face()) {
			status = "Aiming";
			return;
		}
		long now = System.nanoTime();
		if (now - lastPlaceNanos < PLACE_INTERVAL_NS) return;
		selectSlot(client, slot);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, ray);
		if (!result.isAccepted()) {
			status = "Placement refused";
			return;
		}
		player.swingHand(Hand.MAIN_HAND);
		lastPlaceNanos = now;
		lastBlockCount = countUsableBlocks(player);
		awaitingPlacement = placement.placeAt();
		awaitingTellyJump = mode == TELLY_BRIDGE && player.isOnGround();
		movementReady = false;
		status = "Confirming block";
	}

	private Placement nextPlacement(MinecraftClient client, ClientPlayerEntity player, int targetY) {
		return nextPlacement(client, player, targetY, bridgeDirection);
	}

	private Placement nextPlacement(MinecraftClient client, ClientPlayerEntity player, int targetY,
			Vec3d direction) {
		Set<BlockPos> checked = new HashSet<>();
		for (double distance : TARGET_DISTANCES) {
			BlockPos target = BlockPos.ofFloored(player.getX() + direction.x * distance, targetY,
					player.getZ() + direction.z * distance);
			if (!checked.add(target) || !client.world.getBlockState(target).isReplaceable()) continue;
			Placement placement = placementFor(client, player, target);
			if (placement != null) return placement;
		}
		return null;
	}

	private boolean hasSafeLanding(MinecraftClient client, ClientPlayerEntity player, int targetY) {
		for (double distance : new double[] {0.45D, 0.85D}) {
			BlockPos support = BlockPos.ofFloored(player.getX() + bridgeDirection.x * distance, targetY,
					player.getZ() + bridgeDirection.z * distance);
			if (!client.world.getBlockState(support).getCollisionShape(client.world, support).isEmpty()) return true;
		}
		return false;
	}

	private int tellyTargetY() {
		int increase = MathHelper.clamp(config.scaffoldTellyYIncrease, 0, 3);
		if (increase == 0) return tellyBaseY;
		int span = Math.max(3, 7 - increase);
		return tellyBaseY + tellyPlacements / span;
	}

	private int placementY(ClientPlayerEntity player) {
		double y = player.getY();
		double fraction = y - Math.floor(y);
		return Math.abs(fraction - 0.5D) < 1.0E-6D ? MathHelper.floor(y) : MathHelper.floor(y - 1.0D);
	}

	private Placement placementFor(MinecraftClient client, ClientPlayerEntity player, BlockPos placeAt) {
		if (!client.world.getBlockState(placeAt).isReplaceable()) return null;
		Box blockBox = new Box(placeAt).contract(1.0E-4D);
		if (blockBox.intersects(player.getBoundingBox())) return null;
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
		Placement best = null;
		double bestSq = Double.MAX_VALUE;
		for (Direction face : order) {
			BlockPos support = placeAt.offset(face.getOpposite());
			if (client.world.getBlockState(support).getCollisionShape(client.world, support).isEmpty()) continue;
			Vec3d point = Vec3d.ofCenter(support).add(Vec3d.of(face.getVector()).multiply(0.5D));
			double sq = player.getEyePos().squaredDistanceTo(point);
			if (sq > REACH * REACH || sq >= bestSq) continue;
			bestSq = sq;
			best = new Placement(placeAt.toImmutable(), support.toImmutable(), face, point);
		}
		return best;
	}

	private void turnToward(ClientPlayerEntity player, Vec3d point) {
		Vec3d delta = point.subtract(player.getEyePos());
		float wantedYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
		float wantedPitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
		float yawError = MathHelper.wrapDegrees(wantedYaw - player.getYaw());
		float pitchError = wantedPitch - player.getPitch();
		float speed = MathHelper.clamp(2.0F + Math.abs(yawError) / 8.0F, 2.0F, 12.0F);
		player.setYaw(player.getYaw() + mouse.yaw(MathHelper.clamp(yawError, -speed, speed)));
		player.setPitch(MathHelper.clamp(player.getPitch()
				+ mouse.pitch(MathHelper.clamp(pitchError, -speed, speed)), -90.0F, 90.0F));
		player.headYaw = player.getYaw();
	}

	private BlockHitResult liveBlockHit(ClientPlayerEntity player) {
		HitResult hit = player.raycast(REACH, 1.0F, false);
		return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block : null;
	}

	private int findUsableBlockSlot(ClientPlayerEntity player) {
		int selected = player.getInventory().getSelectedSlot();
		if (usableBlock(player.getInventory().getStack(selected))) return selected;
		for (int slot = 0; slot < 9; slot++) if (usableBlock(player.getInventory().getStack(slot))) return slot;
		return -1;
	}

	private int countUsableBlocks(ClientPlayerEntity player) {
		int count = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (usableBlock(stack)) count += stack.getCount();
		}
		return count;
	}

	private boolean usableBlock(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item) || stack.isEmpty()) return false;
		Block block = item.getBlock();
		String id = Registries.BLOCK.getId(block).toString();
		if (config.scaffoldWhitelist && !config.scaffoldWhitelistBlocks.contains(id)) return false;
		return !config.scaffoldBlacklist || !config.scaffoldBlacklistBlocks.contains(id);
	}

	private void selectSlot(MinecraftClient client, int slot) {
		if (slot < 0 || slot > 8 || client.player.getInventory().getSelectedSlot() == slot) return;
		client.player.getInventory().setSelectedSlot(slot);
		client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	public String status() {
		return status;
	}

	public void renderHud(DrawContext context, RenderTickCounter tickCounter) {
		if (!config.enabled || !config.scaffoldAssist || !config.scaffoldBlockCount) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		String count = Integer.toString(countUsableBlocks(client.player));
		int x = (context.getScaledWindowWidth() - client.textRenderer.getWidth(count)) / 2;
		int y = context.getScaledWindowHeight() / 2 + 17;
		context.drawTextWithShadow(client.textRenderer, count, x, y, 0xFFFFFFFF);
		if (config.scaffoldMode != LEGIT) {
			int statusX = (context.getScaledWindowWidth() - client.textRenderer.getWidth(status)) / 2;
			context.drawTextWithShadow(client.textRenderer, status, statusX, y + 11, 0xFFB8D8FF);
		}
	}

	private boolean allowed(MinecraftClient client) {
		return config.enabled && config.scaffoldAssist && client != null && client.player != null
				&& client.world != null && client.interactionManager != null && client.currentScreen == null
				&& client.isWindowFocused() && client.player.isAlive() && !client.player.isSpectator()
				&& client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}

	private void reset(MinecraftClient client, boolean restoreSlot) {
		if (restoreSlot && previousSlot >= 0 && client != null && client.player != null
				&& client.interactionManager != null) selectSlot(client, previousSlot);
		previousSlot = -1;
		activeAutomation = false;
		activationSneak = false;
		movementReady = false;
		tellyJumpPending = false;
		bridgeDirection = Vec3d.ZERO;
		legitDirection = Vec3d.ZERO;
		legitPlacementActive = false;
		awaitingPlacement = null;
		legitAwaitingPlacement = null;
		awaitingTellyJump = false;
		sneakReleaseNanos = 0L;
		status = "Idle";
		resetActivation();
	}

	private void resetActivation() {
		activationBlocks = 0;
		pendingActivationPlacements = 0;
		firstActivation = null;
		lastActivation = null;
		lastActivationNanos = 0L;
		lastBlockCount = -1;
		supportSnapshot.clear();
		tellyPlacements = 0;
		tellyBaseY = Integer.MIN_VALUE;
	}

	private record Placement(BlockPos placeAt, BlockPos support, Direction face, Vec3d point) {}
}
