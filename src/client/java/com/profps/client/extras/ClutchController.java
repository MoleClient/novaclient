package com.profps.client.extras;

import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/**
 * Clutch — an emergency block-place that saves you from a void death.
 *
 * <p>The scenario it's built for: in bedwars you get knocked barely over the
 * edge and you're plummeting straight into the void. If you place one block on
 * the way down you live — so it does exactly that, but only as a panic save, not
 * a tool. Like {@link ScaffoldController} it NEVER rotates your view and NEVER
 * speeds placement up: you look down at a reachable catch face yourself and it
 * presses right-click, with a heavily-jittered human reaction.
 *
 * <p>It only ever fires while you are genuinely <b>falling into a drop you won't
 * walk off of</b> — into the void, or off a tower/ledge with no floor right under
 * you — and only if your own crosshair is on a placeable face that lands a block
 * under you. It is hard-capped at TWO blocks per fall and won't re-fire until
 * you've landed (plus a short cooldown), so it can't be abused to spam-bridge off
 * repeated hits — it's strictly a 1-2 block clutch.
 */
public final class ClutchController {
	private static final int DANGER_DROP = 3;               // no floor within this many blocks below = a fall, not a step-down
	private static final int MAX_PLACES_PER_FALL = 2;       // a clutch is 1-2 blocks, never a bridge
	private static final long POST_EVENT_COOLDOWN_NS = 650_000_000L;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private long placeReadyNanos;
	private long cooldownUntilNanos;
	private int placesThisFall;

	public ClutchController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.clutchAssist) {
			reset();
			return;
		}
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.interactionManager == null
				|| client.currentScreen != null || !player.isAlive() || player.isSpectator()) {
			reset();
			return;
		}
		// Not for gliding or creative flight — this is a "got knocked" save.
		if (player.getAbilities().flying || player.isGliding()) {
			reset();
			return;
		}
		// Landed: this fall's clutch is over, re-arm for the next one.
		if (player.isOnGround()) {
			placesThisFall = 0;
			placeReadyNanos = 0L;
			return;
		}

		long now = System.nanoTime();
		boolean falling = player.getVelocity().y < -0.08; // going down (armed on the first real falling tick)
		if (!falling || now < cooldownUntilNanos || placesThisFall >= MAX_PLACES_PER_FALL) {
			placeReadyNanos = 0L;
			return;
		}
		if (!(player.getMainHandStack().getItem() instanceof BlockItem)) {
			placeReadyNanos = 0L;
			return;
		}
		// Only when you're genuinely over a drop — if there's a floor within a few
		// blocks you'll just set down on your own, no clutch needed. The 2-block cap
		// keeps it from ever being a bridge however it fires.
		if (!inDangerousFall(client, player)) {
			placeReadyNanos = 0L;
			return;
		}

		// What we'd place this tick:
		//   • aimed   — your OWN crosshair is on a face that lands a block under you
		//               (placed via doItemUse, no rotation).
		//   • silent  — no valid aim, so catch you against the nearest reachable
		//               solid block under your feet (a wall / tower / pillar you're
		//               falling beside). Still NO rotation, still capped at 2 blocks.
		// The silent fallback is what makes it actually fire in a panic, when your
		// crosshair isn't perfectly on the catch face.
		boolean aimed = client.crosshairTarget instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& isCatch(client, player, hit.getBlockPos().offset(hit.getSide()));
		BlockHitResult silent = aimed ? null : silentCatch(client, player);

		if (!aimed && silent == null) {
			placeReadyNanos = 0L; // nothing reachable to place against — open void, can't be saved
			return;
		}

		// Humanized but FAST — the window is tiny. A real panic clutch is a
		// near-reflex flick, not a considered reaction, so this is short and
		// jittered, never a fixed value, but quick enough to catch the block while
		// it's still reachable.
		if (placeReadyNanos == 0L) {
			double ms = 22.0 + rng.nextDouble() * 56.0 + Math.abs(rng.nextGaussian()) * 11.0;
			placeReadyNanos = now + (long) (ms * 1_000_000.0);
			return;
		}
		if (now < placeReadyNanos) return;

		// A real right-click against the face (normal use cooldown applies).
		if (aimed) {
			((MinecraftClientInvoker) client).invokeDoItemUse();
		} else {
			client.interactionManager.interactBlock(player, Hand.MAIN_HAND, silent);
			player.swingHand(Hand.MAIN_HAND);
		}
		placesThisFall++;
		placeReadyNanos = 0L;
		// Space the (rare) second block, and after the cap sit out a cooldown so
		// repeated hits can't be chained into a bridge.
		cooldownUntilNanos = now + (placesThisFall >= MAX_PLACES_PER_FALL
				? POST_EVENT_COOLDOWN_NS
				: (long) ((130.0 + rng.nextDouble() * 90.0) * 1_000_000.0));
	}

	/** True when there's no block to land on within {@link #DANGER_DROP} blocks below — a real fall, not a step-down. */
	private boolean inDangerousFall(MinecraftClient client, ClientPlayerEntity player) {
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int feetY = player.getBlockY();
		int checkTo = Math.max(client.world.getBottomY(), feetY - DANGER_DROP);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = feetY - 1; y >= checkTo; y--) {
			pos.set(x, y, z);
			if (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()) {
				return false; // floor within reach — you'll set down on your own
			}
		}
		return true;
	}

	/** A placement catches the fall if it lands a replaceable block below your feet, within reach. */
	private boolean isCatch(MinecraftClient client, ClientPlayerEntity player, BlockPos placeAt) {
		return client.world.getBlockState(placeAt).isReplaceable()
				&& placeAt.getY() < player.getBlockY()        // below you — catches the fall
				&& placeAt.getY() >= player.getBlockY() - 7    // catch band (deep — you fall fast)
				&& Math.abs(placeAt.getX() + 0.5 - player.getX()) <= 2.6
				&& Math.abs(placeAt.getZ() + 0.5 - player.getZ()) <= 2.6;
	}

	/**
	 * No-rotation fallback: fill the block under your feet (or a couple below, since
	 * you fall fast) by clicking a reachable solid block next to it — the pillar
	 * under you, or the wall/tower you're falling beside. Returns the placement, or
	 * null in fully open void where nothing is reachable to anchor to.
	 */
	private BlockHitResult silentCatch(MinecraftClient client, ClientPlayerEntity player) {
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int feetY = player.getBlockY();
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos p = new BlockPos(x, feetY - dy, z);
			if (!client.world.getBlockState(p).isReplaceable()) continue; // need air to fill under you

			// A solid pillar directly under p? Click its top face.
			BlockPos below = p.down();
			if (isSolid(client, below)) {
				return new BlockHitResult(new Vec3d(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5),
						Direction.UP, below, false);
			}
			// A solid block beside p (wall / tower)? Click its face toward p.
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos n = p.offset(d);
				if (!isSolid(client, n)) continue;
				Direction face = d.getOpposite(); // n's face that points at p
				return new BlockHitResult(new Vec3d(
						n.getX() + 0.5 + face.getOffsetX() * 0.5,
						n.getY() + 0.5,
						n.getZ() + 0.5 + face.getOffsetZ() * 0.5),
						face, n, false);
			}
		}
		return null;
	}

	private boolean isSolid(MinecraftClient client, BlockPos pos) {
		return !client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
	}

	private void reset() {
		placeReadyNanos = 0L;
		placesThisFall = 0;
	}
}
