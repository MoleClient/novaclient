package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Straight-line tunnel bot. Tick logic decides WHAT to do (which block to
 * mine, which keys to hold); the per-frame {@link #frame} hook steers the view
 * through {@link HumanizedAim} with real frame timing, so first-person motion
 * is mouse-smooth instead of stepping at 20Hz.
 *
 * <p>When the tunnel face opens into a cave, the bot does what a player would:
 * it walks the cave floor along the same heading, jumps up one-block ledges,
 * hops small gaps, and when it meets the far wall it carves a staircase back
 * to the original tunnel Y so the line continues exactly where it left off.
 * Only lava (or a sheer drop with no landing) makes it detour.
 */
public final class TunnelController {
	private static TunnelController instance;

	private final Random random = new Random();
	private final HumanizedAim aim = new HumanizedAim();
	private final ProFPSConfig config;

	// Movement is PUBLISHED as a PlayerInput rather than pressed into the
	// keybindings, so the bot never fights the player's own key state.
	private boolean mForward, mBack, mLeft, mRight, mJump, mSneak, mSprint;

	private int avoidTicks;
	private int escapeTicks;
	private int eatTicks;
	private int returnSlot = -1;
	private Direction avoidDirection = Direction.NORTH;
	private int recentAvoids;
	private int avoidDecayTicks;
	private BlockPos currentTarget;
	private BlockPos aimTarget;
	private Vec3d aimPoint = Vec3d.ZERO;
	private int sameTargetTicks;
	private int noHitTicks;
	private boolean controlling;
	private Direction tunnelDirection;
	/** Feet Y of the tunnel line; cave crossings stair-carve back to this level. */
	private int tunnelFloorY;
	/** Fixed perpendicular (lateral) coordinate of the tunnel line — the column never leaves it. */
	private int tunnelPerp;
	private BlockPos firstTarget;
	private Vec3d firstTargetPoint;

	// Tick logic publishes the look goal; the render-frame hook consumes it.
	private volatile Vec3d aimGoal;
	private volatile float aimSpeed = 1.0F;
	private long lastFrameNanos;

	public TunnelController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/** True while the bot is actively driving the body (used by the input mixin). */
	public static boolean isControlling() {
		return instance != null && instance.controlling;
	}

	/** The body movement the bot wants this tick, applied by the input mixin. */
	public static PlayerInput movementInput() {
		TunnelController t = instance;
		if (t == null) return PlayerInput.DEFAULT;
		return new PlayerInput(t.mForward, t.mBack, t.mLeft, t.mRight, t.mJump, t.mSneak, t.mSprint);
	}

	private void clearMove() {
		mForward = mBack = mLeft = mRight = mJump = mSneak = mSprint = false;
	}

	/** Called every render frame: smooth, real-time view steering. */
	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dtTicks = lastFrameNanos == 0L ? 1.0F : (float) ((now - lastFrameNanos) / 1_000_000_000.0 * 20.0);
		lastFrameNanos = now;
		Vec3d goal = aimGoal;
		if (!controlling || goal == null || client.player == null) return;
		aim.aimFrame(client.player, goal, aimSpeed, dtTicks);
	}

	public void tick(MinecraftClient client) {
		if (!isReady(client)) {
			if (controlling) {
				release(client);
				controlling = false;
				tunnelDirection = null;
				firstTarget = null;
				firstTargetPoint = null;
				avoidTicks = 0;
				escapeTicks = 0;
			}
			currentTarget = null;
			aimGoal = null;
			return;
		}

		ClientPlayerEntity player = client.player;
		if (player == null) return;
		if (!controlling || tunnelDirection == null) {
			tunnelDirection = horizontalFacing(player.getYaw());
			tunnelFloorY = player.getBlockY();
			Direction perp = tunnelDirection.rotateYClockwise();
			tunnelPerp = perp.getAxis() == Direction.Axis.X ? player.getBlockX() : player.getBlockZ();
			recentAvoids = 0;
			captureFirstTarget(client);
		}
		controlling = true;
		if (avoidDecayTicks > 0) avoidDecayTicks--;
		else recentAvoids = 0;

		if (handleEating(client, player)) {
			currentTarget = null;
			return;
		}

		int pickSlot = findTunnelPickSlot(player);
		if (pickSlot < 0) {
			release(client);
			aimGoal = null;
			client.inGameHud.setOverlayMessage(Text.literal("Tunnel needs the netherite amethyst/shard pickaxe in your hotbar"), false);
			return;
		}
		if (player.getInventory().getSelectedSlot() != pickSlot) {
			player.getInventory().setSelectedSlot(pickSlot);
			release(client);
			return;
		}

		// Boxed-in recovery takes priority over everything else.
		if (escapeTicks > 0) {
			escape(client, player);
			return;
		}

		Direction forward = tunnelDirection;
		if (avoidTicks > 0) {
			avoidTicks--;
			driveAroundHazard(client, avoidDirection);
			aimGoal = null; // hold the head steady while sidestepping
			return;
		}

		if (lavaAhead(client, player, forward)) {
			startAvoid(client, player, forward);
			return;
		}

		int feetY = player.getBlockY();

		// Re-anchor the line after an INTENTIONAL lateral move (a hazard detour
		// or a cave crossing left us well off the old line). Small aim drift
		// (< 1.5 blocks) is held and trimmed by re-centering instead, so the
		// column stays dead straight without snapping back into a hazard we
		// just walked around.
		if (feetY == tunnelFloorY && lateralDeviation(player) > 1.5) {
			recaptureLine(player);
		}

		// Off the tunnel line (a cave dragged us up or down): carve a staircase
		// back toward the original Y so the line continues where it left off.
		if (feetY != tunnelFloorY) {
			BlockPos stair = stairTarget(client, player, forward, feetY);
			if (stair != null) {
				mineBlock(client, player, forward, stair);
				return;
			}
		}

		BlockPos target = feetY == tunnelFloorY ? targetBlock(player, forward) : null;
		if (target != null) {
			mineBlock(client, player, forward, target);
			return;
		}

		traverse(client, player, forward);
	}

	/** Aim at the block, walk up to its face, and mine through the vanilla attack key. */
	private void mineBlock(MinecraftClient client, ClientPlayerEntity player, Direction forward, BlockPos target) {
		if (!target.equals(currentTarget)) noHitTicks = 0;
		currentTarget = target;
		aimGoal = aimPointFor(target, player);
		aimSpeed = 1.3F;

		releaseMovement(client);
		mForward = !isTouchingMiningFace(player, target, forward);
		recenterOnLine(player); // strafe back to the line so we never drift off it

		// Mine ONLY through the vanilla attack key, and ONLY once the crosshair is
		// genuinely on a mineable block. The old code force-fed the interaction
		// manager a block the player often wasn't even looking at yet (while ALSO
		// holding attack) — the server saw double-speed mining on an off-ray block,
		// which anti-cheats flag instantly. This way every packet the server sees
		// is exactly what a real player looking at that block would produce.
		boolean onMineable = isCrosshairOnMineable(client, target);
		if (onMineable) {
			noHitTicks = 0;
		} else if (++noHitTicks > 18) {
			// The click won't land (point occluded by a rim/neighbour block).
			// Re-settle on a fresh visible spot, and when stuck below the
			// tunnel line, hop — exactly what a player does to clear the pit
			// edge and reach the block above.
			if (noHitTicks % 9 == 0) {
				aimPoint = facePoint(target, player);
			}
			if (noHitTicks > 24 && player.getBlockY() < tunnelFloorY && player.isOnGround()) {
				mJump = true;
			}
		}
		client.options.attackKey.setPressed(onMineable);
	}

	/**
	 * No wall to mine: we're inside open space (a cave the tunnel broke into).
	 * Walk it like a player would — same heading, jump one-block ledges, hop
	 * small gaps — until the far wall gives the miner something to chew again.
	 */
	private void traverse(MinecraftClient client, ClientPlayerEntity player, Direction forward) {
		currentTarget = null;
		BlockPos feet = player.getBlockPos();
		BlockPos front = feet.offset(forward);

		// Look down the heading; when the cave floor dropped us below the
		// tunnel line, the gaze eases back up toward the line like a player
		// picking their route.
		Vec3d ahead = player.getEyePos().add(Vec3d.of(forward.getVector()).multiply(4.5));
		double lineEye = tunnelFloorY + 1.4;
		aimGoal = new Vec3d(ahead.x, MathHelper.lerp(0.45, ahead.y, lineEye), ahead.z);
		aimSpeed = 0.85F;

		releaseMovement(client);
		client.options.attackKey.setPressed(false);
		client.options.useKey.setPressed(false);

		// Lava on the walking path: detour.
		if (isLava(client, front) || isLava(client, front.up())
				|| isLava(client, feet.offset(forward, 2)) || isLava(client, feet.offset(forward, 2).down())) {
			startAvoid(client, player, forward);
			return;
		}

		// One-block ledge ahead: jump up it (this is also how the bot climbs
		// back toward the tunnel line after crossing a low cave floor).
		if (isSolid(client, front) && isPassable(client, front.up()) && isPassable(client, front.up(2))
				&& isPassable(client, feet.up(2))) {
			mForward = true;
			mSprint = true;
			mJump = true;
			return;
		}

		if (!isPassable(client, front) || !isPassable(client, front.up())) {
			// A wall with nothing mineable (bedrock, fluid...) — route around it.
			startAvoid(client, player, forward);
			return;
		}

		int drop = dropDepth(client, front);
		if (drop < 0) {
			// Sheer pit or lava under the next step: try to clear it with a
			// sprint-jump onto a landing 2-3 blocks out, otherwise detour.
			if (gapJumpLanding(client, feet, forward) > 0
					&& isPassable(client, feet.up(2)) && isPassable(client, front.up(2))) {
				mForward = true;
				mSprint = true;
				mJump = true;
				return;
			}
			startAvoid(client, player, forward);
			return;
		}

		mForward = true;
		mSprint = true;
	}

	/**
	 * Staircase carving back to the tunnel line. Below the line, clear the
	 * headroom + step blocks and let traversal jump each step; above it, dig
	 * the floor out ahead and walk down.
	 */
	private BlockPos stairTarget(MinecraftClient client, ClientPlayerEntity player, Direction forward, int feetY) {
		BlockPos feet = player.getBlockPos();
		BlockPos front = feet.offset(forward);
		if (feetY < tunnelFloorY) {
			// Bottom-up order matters: from down in the hole the eye can SEE
			// the overhead and the lower front block; the upper front block
			// only becomes visible once the one below it is gone.
			if (isMineable(client, feet.up(2))) return feet.up(2);   // headroom to jump
			if (isMineable(client, front.up())) return front.up();   // feet slot after the step
			if (isMineable(client, front.up(2))) return front.up(2); // head slot after the step
			return null; // step is clear — traversal jumps onto it
		}
		if (isMineable(client, front.up())) return front.up();
		if (isMineable(client, front)) return front;
		if (isMineable(client, front.down())) return front.down();   // dig the step down
		return null; // cleared — traversal walks forward and drops one
	}

	/** Air gaps below the next step: 0-3 = walkable drop, -1 = lava or sheer pit. */
	private int dropDepth(MinecraftClient client, BlockPos front) {
		for (int depth = 0; depth <= 3; depth++) {
			BlockPos pos = front.down(1 + depth);
			BlockState state = client.world.getBlockState(pos);
			if (state.getFluidState().isIn(FluidTags.LAVA)) return -1;
			if (state.getFluidState().isIn(FluidTags.WATER)) return depth; // water catches the fall
			if (!isPassable(client, pos)) return depth;
		}
		return -1;
	}

	/** A solid, clear landing 2-3 blocks out that a sprint-jump can reach. */
	private int gapJumpLanding(MinecraftClient client, BlockPos feet, Direction forward) {
		for (int distance = 2; distance <= 3; distance++) {
			BlockPos land = feet.offset(forward, distance);
			if (isLava(client, land) || isLava(client, land.down())) return 0;
			if (isSolid(client, land.down()) && isPassable(client, land) && isPassable(client, land.up())) {
				return distance;
			}
		}
		return 0;
	}

	private void startAvoid(MinecraftClient client, ClientPlayerEntity player, Direction forward) {
		release(client);
		avoidDirection = saferSide(client, player, forward);
		avoidTicks = 18 + random.nextInt(8);
		recentAvoids++;
		avoidDecayTicks = 200;
		if (recentAvoids >= 4) {
			// Boxed in (lava lake, ravine, sealed pocket). Instead of giving up,
			// switch to escape mode and climb out through the ceiling — there is
			// always a way up, so the bot never strands itself.
			avoidTicks = 0;
			recentAvoids = 0;
			escapeTicks = 80;
		}
	}

	/**
	 * Never-give-up recovery: carve straight up and pillar-jump out of any
	 * pocket the sidestep logic couldn't solve, then adopt the higher, open
	 * level as the new tunnel line and resume. Climbing the ceiling always frees
	 * the bot, so the module never disables itself mid-run.
	 */
	private void escape(MinecraftClient client, ClientPlayerEntity player) {
		escapeTicks--;
		currentTarget = null;
		Direction forward = tunnelDirection != null ? tunnelDirection : horizontalFacing(player.getYaw());
		BlockPos feet = player.getBlockPos();
		BlockPos ceiling = feet.up(2);

		if (isMineable(client, ceiling)) {
			// Open the headroom directly overhead first.
			aimGoal = aimPointFor(ceiling, player);
			aimSpeed = 1.2F;
			clearMove();
			client.options.attackKey.setPressed(isCrosshairOnMineable(client, ceiling));
			return;
		}

		// Headroom is clear: jump up, and edge forward if the next-level opening
		// is passable so we land on solid ground instead of dropping back in.
		client.options.attackKey.setPressed(false);
		clearMove();
		mJump = true;
		if (isPassable(client, feet.offset(forward).up()) && isPassable(client, feet.offset(forward).up(2))) {
			mForward = true;
		}

		// Reached a higher, open level: make it the new line and resume normal mining.
		if (player.getBlockY() > tunnelFloorY && player.isOnGround()) {
			tunnelFloorY = player.getBlockY();
			escapeTicks = 0;
			recentAvoids = 0;
		}
	}

	private boolean isCrosshairOnMineable(MinecraftClient client, BlockPos target) {
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		BlockPos looked = hit.getBlockPos();
		if (!isMineable(client, looked)) return false;
		if (looked.equals(target)) return true;
		// Only mine blocks on the captured tunnel LINE — never a side wall. A
		// 1-wide column shares the line's perpendicular coordinate; anything off
		// it is a wall the aim sway drifted onto, and breaking it is the mishit
		// that wandered the tunnel sideways.
		Direction forward = tunnelDirection != null ? tunnelDirection : Direction.NORTH;
		Direction perp = forward.rotateYClockwise();
		int lookedPerp = perp.getAxis() == Direction.Axis.X ? looked.getX() : looked.getZ();
		if (lookedPerp != tunnelPerp) return false;
		// On the line and at most one block off along the tunnel/vertical axis.
		return looked.getManhattanDistance(target) <= 1;
	}

	private boolean isReady(MinecraftClient client) {
		return config.enabled && config.donutTunnel
				&& client.world != null
				&& client.player != null
				&& client.currentScreen == null;
	}

	private boolean handleEating(MinecraftClient client, ClientPlayerEntity player) {
		float health = player.getHealth() + player.getAbsorptionAmount();
		if (health > config.donutTunnelHpThreshold && eatTicks <= 0) return false;

		if (eatTicks <= 0) {
			int slot = findFoodSlot(player);
			if (slot < 0) return false;
			returnSlot = player.getInventory().getSelectedSlot();
			player.getInventory().setSelectedSlot(slot);
			// Eating duration jitters a few ticks like a real use-key hold.
			eatTicks = 42 + random.nextInt(8);
		}

		// Keep a relaxed gaze down the tunnel while chewing.
		if (tunnelDirection != null) {
			aimGoal = player.getEyePos().add(Vec3d.of(tunnelDirection.getVector()).multiply(5.0));
			aimSpeed = 0.5F;
		}
		releaseMovement(client);
		client.options.attackKey.setPressed(false);
		client.options.useKey.setPressed(true);
		eatTicks--;
		if (eatTicks <= 0) {
			client.options.useKey.setPressed(false);
			if (returnSlot >= 0 && PlayerInventory.isValidHotbarIndex(returnSlot)) {
				player.getInventory().setSelectedSlot(returnSlot);
			}
			returnSlot = -1;
		}
		return true;
	}

	private int findTunnelPickSlot(ClientPlayerEntity player) {
		for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
			if (isTunnelPick(player.getInventory().getStack(slot))) return slot;
		}
		return -1;
	}

	private boolean isTunnelPick(ItemStack stack) {
		if (stack.isEmpty() || !stack.isOf(Items.NETHERITE_PICKAXE)) return false;
		String name = stack.getName().getString().toLowerCase(java.util.Locale.ROOT);
		return name.contains("amethyst")
				|| name.contains("amythyst")
				|| name.contains("shard");
	}

	private int findFoodSlot(ClientPlayerEntity player) {
		for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && stack.get(DataComponentTypes.FOOD) != null) return slot;
		}
		return -1;
	}

	/**
	 * Strictly the straight 1×2 tunnel column: head then feet, one and two
	 * blocks ahead. No lateral targets and no floor digging — aiming at a side
	 * block angled the head, and since walking follows the look direction the
	 * bot kept drifting out of its own tunnel line. The only sanctioned
	 * deviation from the line is {@link #stairTarget}: after dropping a block,
	 * mine the headroom above to climb back up.
	 */
	private BlockPos targetBlock(ClientPlayerEntity player, Direction forward) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (firstTarget != null) {
			if (isMineable(client, firstTarget)) return firstTarget;
			firstTarget = null;
			firstTargetPoint = null;
		}

		// Locked to the captured line: forward steps advance, but the lateral
		// (perp) and vertical coordinates are PINNED to the tunnel line. Reading
		// them off the player's live block position let lateral drift march the
		// column sideways — which is why it kept mining the block beside centre.
		boolean alongX = forward.getAxis() == Direction.Axis.X;
		int fwdBase = alongX ? player.getBlockX() : player.getBlockZ();
		int step = alongX ? forward.getOffsetX() : forward.getOffsetZ();
		for (int distance = 1; distance <= 2; distance++) {
			int fwd = fwdBase + step * distance;
			for (int y = 1; y >= 0; y--) {
				BlockPos pos = alongX
						? new BlockPos(fwd, tunnelFloorY + y, tunnelPerp)
						: new BlockPos(tunnelPerp, tunnelFloorY + y, fwd);
				if (isMineable(client, pos)) return pos;
			}
		}
		return null;
	}

	private void captureFirstTarget(MinecraftClient client) {
		firstTarget = null;
		firstTargetPoint = null;
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		if (!isMineable(client, pos)) return;
		firstTarget = pos.toImmutable();
		firstTargetPoint = hit.getPos();
	}

	private boolean isMineable(MinecraftClient client, BlockPos pos) {
		if (client.world == null) return false;
		BlockState state = client.world.getBlockState(pos);
		return !state.isAir() && !state.getFluidState().isIn(FluidTags.LAVA) && state.getHardness(client.world, pos) >= 0.0F;
	}

	/** Walkable space: no collision and no lava (water is wadeable). */
	private boolean isPassable(MinecraftClient client, BlockPos pos) {
		BlockState state = client.world.getBlockState(pos);
		if (state.getFluidState().isIn(FluidTags.LAVA)) return false;
		return state.getCollisionShape(client.world, pos).isEmpty();
	}

	private boolean isSolid(MinecraftClient client, BlockPos pos) {
		BlockState state = client.world.getBlockState(pos);
		return !state.getCollisionShape(client.world, pos).isEmpty();
	}

	private Vec3d aimPointFor(BlockPos target, ClientPlayerEntity player) {
		if (target.equals(firstTarget) && firstTargetPoint != null) {
			return firstTargetPoint;
		}
		// Pick ONE point per block and keep it. Re-rolling a new random spot
		// inside the same block every second is what made the head wander/spam.
		if (!target.equals(aimTarget)) {
			aimTarget = target;
			sameTargetTicks = 0;
			aimPoint = facePoint(target, player);
		}
		// ...but on a stubborn block (obsidian, deepslate with a worn pick) a
		// real hand re-settles now and then instead of pinning one pixel.
		if (++sameTargetTicks > 50 && random.nextInt(40) == 0) {
			aimPoint = facePoint(target, player);
			sameTargetTicks = 0;
		}
		return aimPoint;
	}

	/**
	 * A point close to the block's CENTRE, nudged just onto the face most exposed
	 * to the eye (so a neighbouring block can't occlude it). Kept tight — the old
	 * 0.48 offset + wide jitter made the head sweep way out around the block;
	 * aiming near the middle keeps mining concise and dead-on.
	 */
	private Vec3d facePoint(BlockPos target, ClientPlayerEntity player) {
		Vec3d eye = player.getEyePos();
		Vec3d center = Vec3d.ofCenter(target);
		Vec3d toEye = eye.subtract(center);
		double ax = Math.abs(toEye.x), ay = Math.abs(toEye.y), az = Math.abs(toEye.z);
		double face = 0.30;  // toward the near face but well inside the block — concise
		double jitter = 0.05; // a whisper of imprecision, not the old wide sweep
		double jitterA = (random.nextDouble() - 0.5) * jitter;
		double jitterB = (random.nextDouble() - 0.5) * jitter;
		if (ay >= ax && ay >= az) {
			return new Vec3d(center.x + jitterA, center.y + Math.signum(toEye.y) * face, center.z + jitterB);
		}
		if (ax >= az) {
			return new Vec3d(center.x + Math.signum(toEye.x) * face, center.y + jitterA, center.z + jitterB);
		}
		return new Vec3d(center.x + jitterA, center.y + jitterB, center.z + Math.signum(toEye.z) * face);
	}

	/** How far (blocks) the body is from the tunnel centre line, laterally. */
	private double lateralDeviation(ClientPlayerEntity player) {
		if (tunnelDirection == null) return 0.0;
		Direction perp = tunnelDirection.rotateYClockwise();
		double pos = perp.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
		return Math.abs(pos - (tunnelPerp + 0.5));
	}

	/** Adopt the body's current lateral position as the new tunnel line. */
	private void recaptureLine(ClientPlayerEntity player) {
		if (tunnelDirection == null) return;
		Direction perp = tunnelDirection.rotateYClockwise();
		tunnelPerp = perp.getAxis() == Direction.Axis.X ? player.getBlockX() : player.getBlockZ();
	}

	/** Strafe gently back onto the tunnel centre line so forward drift can't accumulate. */
	private void recenterOnLine(ClientPlayerEntity player) {
		if (tunnelDirection == null) return;
		Direction right = tunnelDirection.rotateYClockwise();
		boolean axisX = right.getAxis() == Direction.Axis.X;
		double posOnAxis = axisX ? player.getX() : player.getZ();
		double center = tunnelPerp + 0.5;
		int rightSign = axisX ? right.getOffsetX() : right.getOffsetZ();
		double towardRight = (posOnAxis - center) * rightSign; // >0 = displaced to the right
		if (towardRight > 0.22) {
			mLeft = true;
			mRight = false;
		} else if (towardRight < -0.22) {
			mRight = true;
			mLeft = false;
		}
	}

	private boolean isTouchingMiningFace(ClientPlayerEntity player, BlockPos target, Direction forward) {
		double playerCoord = forward.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
		double faceCoord;
		return switch (forward) {
			case NORTH -> {
				faceCoord = target.getZ() + 1.0;
				yield playerCoord - 0.31 <= faceCoord + 0.04;
			}
			case SOUTH -> {
				faceCoord = target.getZ();
				yield playerCoord + 0.31 >= faceCoord - 0.04;
			}
			case WEST -> {
				faceCoord = target.getX() + 1.0;
				yield playerCoord - 0.31 <= faceCoord + 0.04;
			}
			case EAST -> {
				faceCoord = target.getX();
				yield playerCoord + 0.31 >= faceCoord - 0.04;
			}
			default -> true;
		};
	}

	/** Lava in the mining volume ahead — the one hazard that still forces a detour. */
	private boolean lavaAhead(MinecraftClient client, ClientPlayerEntity player, Direction forward) {
		BlockPos base = player.getBlockPos().offset(forward, 2);
		for (int depth = 0; depth <= 2; depth++) {
			BlockPos center = base.offset(forward, depth);
			for (int y = -2; y <= 2; y++) {
				for (int lateral = -1; lateral <= 1; lateral++) {
					BlockPos pos = center.up(y).offset(forward.rotateYClockwise(), lateral);
					if (isLava(client, pos)) return true;
				}
			}
			if (isLava(client, center.down())) return true;
		}
		return false;
	}

	private Direction saferSide(MinecraftClient client, ClientPlayerEntity player, Direction forward) {
		Direction left = forward.rotateYCounterclockwise();
		Direction right = forward.rotateYClockwise();
		int leftRisk = sideRisk(client, player.getBlockPos(), left, forward);
		int rightRisk = sideRisk(client, player.getBlockPos(), right, forward);
		return leftRisk <= rightRisk ? left : right;
	}

	private int sideRisk(MinecraftClient client, BlockPos origin, Direction side, Direction forward) {
		int risk = 0;
		for (int step = 1; step <= 3; step++) {
			BlockPos pos = origin.offset(side, step).offset(forward, 1);
			if (isLava(client, pos) || isLava(client, pos.down())) risk += 6;
			if (client.world.getBlockState(pos.down()).isAir()) risk += 4;
			if (!client.world.getBlockState(pos).isAir()) risk++;
		}
		return risk;
	}

	private boolean isLava(MinecraftClient client, BlockPos pos) {
		BlockState state = client.world.getBlockState(pos);
		return state.isOf(Blocks.LAVA) || state.getFluidState().isIn(FluidTags.LAVA);
	}

	private void driveAroundHazard(MinecraftClient client, Direction side) {
		releaseMovement(client);
		Direction forward = tunnelDirection != null ? tunnelDirection : horizontalFacing(client.player.getYaw());
		if (side == forward.rotateYCounterclockwise()) {
			mLeft = true;
		} else {
			mRight = true;
		}
		mSneak = true;
		client.options.attackKey.setPressed(false);
		client.options.useKey.setPressed(false);
	}

	private Direction horizontalFacing(float yaw) {
		return Direction.fromHorizontalDegrees(yaw);
	}

	private void release(MinecraftClient client) {
		if (client == null || client.options == null) return;
		releaseMovement(client);
		client.options.attackKey.setPressed(false);
		client.options.useKey.setPressed(false);
	}

	/** Stop all movement. Attack/use stay on the keybindings; movement is published. */
	private void releaseMovement(MinecraftClient client) {
		clearMove();
	}
}
