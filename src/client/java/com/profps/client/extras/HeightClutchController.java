package com.profps.client.extras;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.security.SecureRandom;

/**
 * Height Clutch — a survival fall-save built around a held item rather than a
 * placed block, so it works where the normal block Clutch can't. It only acts when
 * the right item is in your HOTBAR and you're genuinely falling. Neither mode ever
 * moves your view.
 *
 * <p><b>Water bucket (preferred).</b> Falling onto solid ground while looking down
 * with a water bucket in your hotbar, it MLGs for you: switches to the bucket,
 * places the water below you, waits until you've landed in it, pauses a human beat,
 * scoops the water back up, and switches your hand back — spread over frames. It
 * never aims for you, so you have to be looking down for the water to land where you
 * fall.
 *
 * <p><b>Ladder (backup).</b> If instead you have ladders and you're falling beside a
 * wall, it silently places a ladder against the nearest reachable wall block in your
 * path (recomputed at the moment of placement so it lands below where you actually
 * are) so you catch and climb to safety — no head turn.
 */
public final class HeightClutchController {
	private static final int DANGER_FALL = 4;          // blocks fallen before it bothers to clutch
	private static final int GROUND_REACH = 4;         // water: solid ground must be within this far below
	private static final int LADDER_SCAN = 5;          // ladder: search this far down for a grabbable wall
	private static final float LOOK_DOWN_PITCH = 42.0F; // water: you must be looking at least this far down
	private static final long CLUTCH_COOLDOWN_NS = 400_000_000L;

	private enum Mode { NONE, WATER, LADDER }
	private enum Phase { IDLE, SWITCH, PLACE, WAIT_PICKUP, RESTORE }

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private Mode mode = Mode.NONE;
	private Phase phase = Phase.IDLE;
	private int savedSlot = -1;
	private int actionSlot = -1;
	private long phaseReadyNanos;
	private int phaseTicks;
	private boolean landedForPickup;
	private long cooldownUntilNanos;

	public HeightClutchController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!config.enabled || !config.heightClutchAssist || player == null || client.world == null
				|| !player.isAlive() || player.isSpectator() || player.getAbilities().flying) {
			reset();
			return;
		}

		// A clutch already in progress just advances, frame by frame.
		if (phase != Phase.IDLE) {
			advance(client, player);
			return;
		}

		long now = System.nanoTime();
		if (now < cooldownUntilNanos) return;
		if (player.isClimbing() || player.hasVehicle()) return;
		if (player.getVelocity().y >= -0.15) return;   // not actually falling
		if (player.fallDistance < DANGER_FALL) return; // not far enough to hurt yet

		// Water first — it doesn't leave a trail of ladders. Skip in the Nether,
		// where placed water just evaporates.
		int waterSlot = hotbarSlot(player, Items.WATER_BUCKET);
		if (waterSlot >= 0 && client.world.getRegistryKey() != World.NETHER
				&& player.getPitch() >= LOOK_DOWN_PITCH && groundWithinReach(client, player)) {
			begin(player, Mode.WATER, waterSlot, now);
			return;
		}

		// Ladder backup — needs a wall in your fall path. (Target is recomputed at
		// placement time; here we only check one exists.)
		int ladderSlot = hotbarSlot(player, Items.LADDER);
		if (ladderSlot >= 0 && ladderTarget(client, player) != null) {
			begin(player, Mode.LADDER, ladderSlot, now);
		}
	}

	private void begin(ClientPlayerEntity player, Mode m, int slot, long now) {
		mode = m;
		phase = Phase.SWITCH;
		savedSlot = player.getInventory().getSelectedSlot();
		actionSlot = slot;
		phaseReadyNanos = now;
		phaseTicks = 0;
		landedForPickup = false;
	}

	private void advance(MinecraftClient client, ClientPlayerEntity player) {
		long now = System.nanoTime();
		phaseTicks++;
		if (phaseTicks > 80) { // safety net — never get stuck mid-sequence
			restoreSlot(player);
			reset();
			return;
		}

		switch (phase) {
			case SWITCH -> {
				if (now < phaseReadyNanos) return;
				player.getInventory().setSelectedSlot(actionSlot);
				phase = Phase.PLACE;
				phaseReadyNanos = now + jitterMs(18, 50);
			}
			case PLACE -> {
				if (now < phaseReadyNanos) return;
				if (mode == Mode.WATER) {
					((MinecraftClientInvoker) client).invokeDoItemUse(); // place water you're looking at
					phase = Phase.WAIT_PICKUP;
					phaseTicks = 0;
				} else {
					// Recompute now so the ladder lands below where you actually are,
					// not where you were a couple ticks ago — and silently, no aim.
					BlockHitResult target = ladderTarget(client, player);
					if (target != null) {
						client.interactionManager.interactBlock(player, Hand.MAIN_HAND, target);
						player.swingHand(Hand.MAIN_HAND);
					}
					phase = Phase.RESTORE;
					phaseReadyNanos = now + jitterMs(30, 70);
				}
			}
			case WAIT_PICKUP -> {
				// Wait until you're actually in the water, THEN pause a human beat
				// before scooping — picking it back up the instant you land reads as
				// a bot. The bucket raycasts the source itself, so you stay looking
				// down and don't move your view.
				boolean landed = player.isOnGround() || player.isTouchingWater();
				if (!landedForPickup) {
					if (landed) {
						landedForPickup = true;
						phaseReadyNanos = now + jitterMs(300, 700);
					}
					return;
				}
				if (now < phaseReadyNanos) return;
				((MinecraftClientInvoker) client).invokeDoItemUse(); // scoop the water back
				phase = Phase.RESTORE;
				phaseReadyNanos = now + jitterMs(30, 70);
			}
			case RESTORE -> {
				if (now < phaseReadyNanos) return;
				restoreSlot(player);
				cooldownUntilNanos = now + CLUTCH_COOLDOWN_NS;
				reset();
			}
			default -> reset();
		}
	}

	private void restoreSlot(ClientPlayerEntity player) {
		if (savedSlot >= 0 && savedSlot < 9) {
			player.getInventory().setSelectedSlot(savedSlot);
		}
	}

	private void reset() {
		mode = Mode.NONE;
		phase = Phase.IDLE;
		savedSlot = -1;
		actionSlot = -1;
		phaseTicks = 0;
		landedForPickup = false;
	}

	private long jitterMs(int lo, int hi) {
		return (long) ((lo + rng.nextInt(hi - lo + 1)) * 1_000_000.0);
	}

	private int hotbarSlot(ClientPlayerEntity player, Item item) {
		for (int i = 0; i <= 8; i++) {
			if (player.getInventory().getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	/** Solid ground straight below within bucket reach — where the water will land. */
	private boolean groundWithinReach(MinecraftClient client, ClientPlayerEntity player) {
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int feetY = player.getBlockY();
		for (int dy = 1; dy <= GROUND_REACH; dy++) {
			if (isSolid(client, new BlockPos(x, feetY - dy, z))) return true;
		}
		return false;
	}

	/**
	 * The nearest reachable wall block to ladder onto, scanning your fall path from
	 * just below your feet downward — so on a tower it grabs the first wall block you
	 * can still reach as you fall, not only the very last one. Returns the click on
	 * that wall's face, or null if nothing's in reach.
	 */
	private BlockHitResult ladderTarget(MinecraftClient client, ClientPlayerEntity player) {
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int feetY = player.getBlockY();
		for (int dy = 1; dy <= LADDER_SCAN; dy++) {
			BlockPos p = new BlockPos(x, feetY - dy, z);
			if (!client.world.getBlockState(p).isReplaceable()) continue; // need air to hold the ladder
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos wall = p.offset(d);
				if (!isSolid(client, wall)) continue;
				Direction face = d.getOpposite(); // the wall face that points at the ladder block
				Vec3d hit = new Vec3d(
						wall.getX() + 0.5 + face.getOffsetX() * 0.5,
						wall.getY() + 0.5,
						wall.getZ() + 0.5 + face.getOffsetZ() * 0.5);
				return new BlockHitResult(hit, face, wall, false);
			}
		}
		return null;
	}

	private boolean isSolid(MinecraftClient client, BlockPos pos) {
		return !client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
	}
}
