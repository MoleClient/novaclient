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

/** Emergency block place during a fall. Never rotates the view. */
public final class ClutchController {
	private static final int DANGER_DROP = 3;         // no floor within this many blocks below counts as a fall
	private static final int MAX_PLACES_PER_FALL = 2;
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
		if (player.getAbilities().flying || player.isGliding()) {
			reset();
			return;
		}
		// Landed: re-arm for the next fall.
		if (player.isOnGround()) {
			placesThisFall = 0;
			placeReadyNanos = 0L;
			return;
		}

		long now = System.nanoTime();
		boolean falling = player.getVelocity().y < -0.08;
		if (!falling || now < cooldownUntilNanos || placesThisFall >= MAX_PLACES_PER_FALL) {
			placeReadyNanos = 0L;
			return;
		}
		if (!(player.getMainHandStack().getItem() instanceof BlockItem)) {
			placeReadyNanos = 0L;
			return;
		}
		if (!inDangerousFall(client, player)) {
			placeReadyNanos = 0L;
			return;
		}

		// aimed: crosshair already on a catch face. silent: fall back to the nearest
		// reachable solid block below. Neither rotates the view.
		boolean aimed = client.crosshairTarget instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& isCatch(client, player, hit.getBlockPos().offset(hit.getSide()));
		BlockHitResult silent = aimed ? null : silentCatch(client, player);

		if (!aimed && silent == null) {
			placeReadyNanos = 0L; // nothing reachable to place against
			return;
		}

		// Short jittered reaction delay.
		if (placeReadyNanos == 0L) {
			double ms = 22.0 + rng.nextDouble() * 56.0 + Math.abs(rng.nextGaussian()) * 11.0;
			placeReadyNanos = now + (long) (ms * 1_000_000.0);
			return;
		}
		if (now < placeReadyNanos) return;

		if (aimed) {
			((MinecraftClientInvoker) client).invokeDoItemUse();
		} else {
			client.interactionManager.interactBlock(player, Hand.MAIN_HAND, silent);
			player.swingHand(Hand.MAIN_HAND);
		}
		placesThisFall++;
		placeReadyNanos = 0L;
		cooldownUntilNanos = now + (placesThisFall >= MAX_PLACES_PER_FALL
				? POST_EVENT_COOLDOWN_NS
				: (long) ((130.0 + rng.nextDouble() * 90.0) * 1_000_000.0));
	}

	/** True when there is no block to land on within {@link #DANGER_DROP} blocks below. */
	private boolean inDangerousFall(MinecraftClient client, ClientPlayerEntity player) {
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int feetY = player.getBlockY();
		int checkTo = Math.max(client.world.getBottomY(), feetY - DANGER_DROP);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = feetY - 1; y >= checkTo; y--) {
			pos.set(x, y, z);
			if (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** A placement catches the fall if it lands a replaceable block below the feet, within reach. */
	private boolean isCatch(MinecraftClient client, ClientPlayerEntity player, BlockPos placeAt) {
		return client.world.getBlockState(placeAt).isReplaceable()
				&& placeAt.getY() < player.getBlockY()
				&& placeAt.getY() >= player.getBlockY() - 7
				&& Math.abs(placeAt.getX() + 0.5 - player.getX()) <= 2.6
				&& Math.abs(placeAt.getZ() + 0.5 - player.getZ()) <= 2.6;
	}

	/**
	 * No-rotation fallback placement against a reachable solid block below or beside the player.
	 *
	 * @return the placement, or null when nothing reachable can anchor it
	 */
	private BlockHitResult silentCatch(MinecraftClient client, ClientPlayerEntity player) {
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int feetY = player.getBlockY();
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos p = new BlockPos(x, feetY - dy, z);
			if (!client.world.getBlockState(p).isReplaceable()) continue;

			// Solid pillar directly under p: click its top face.
			BlockPos below = p.down();
			if (isSolid(client, below)) {
				return new BlockHitResult(new Vec3d(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5),
						Direction.UP, below, false);
			}
			// Otherwise a solid block beside p: click its face toward p.
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos n = p.offset(d);
				if (!isSolid(client, n)) continue;
				Direction face = d.getOpposite();
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
