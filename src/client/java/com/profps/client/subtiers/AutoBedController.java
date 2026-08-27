package com.profps.client.subtiers;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BedItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/** Sends a delayed second right-click on a bed the player just placed. */
public final class AutoBedController {
	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();

	private BlockPos placementHint;
	private BlockPos bedPos;
	private final Set<Long> bedsPresentBeforePlacement = new HashSet<>();
	private long scanAtNanos;
	private long detonateAtNanos;
	private long expireAtNanos;

	public AutoBedController(ProFPSConfig config) {
		this.config = config;
	}

	public ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
		if (!config.enabled || !config.subTiersAutoBed || !world.isClient() || hand != Hand.MAIN_HAND) {
			return ActionResult.PASS;
		}
		if (player == null || !(player.getStackInHand(hand).getItem() instanceof BedItem)) {
			return ActionResult.PASS;
		}
		// Clicking an existing bed is the automatic second click, not a new placement.
		if (world.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock) {
			return ActionResult.PASS;
		}

		long now = System.nanoTime();
		placementHint = hit.getBlockPos().offset(hit.getSide()).toImmutable();
		rememberNearbyBeds(world, placementHint);
		bedPos = null;
		scanAtNanos = now + ns(12D + rng.nextDouble() * 32D);
		detonateAtNanos = 0L;
		expireAtNanos = now + ns(1250D);
		return ActionResult.PASS;
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) { reset(); return; }
		if (placementHint == null) return;
		long now = System.nanoTime();
		if (now > expireAtNanos) { reset(); return; }

		if (bedPos == null) {
			if (now < scanAtNanos) return;
			bedPos = findPlacedBed(client, placementHint);
			if (bedPos == null) {
				scanAtNanos = now + ns(18D + rng.nextDouble() * 34D);
				return;
			}
			BedRule rule = client.world.getEnvironmentAttributes()
					.getAttributeValue(EnvironmentAttributes.BED_RULE_GAMEPLAY, bedPos);
			if (rule == null || !rule.explodes()) { reset(); return; }

			double delayMs = 32D + rng.nextDouble() * 68D + Math.abs(rng.nextGaussian()) * 7D;
			if (rng.nextDouble() < 0.07D) delayMs += 38D + rng.nextDouble() * 95D;
			detonateAtNanos = now + ns(delayMs);
			return;
		}

		if (now < detonateAtNanos) return;
		BlockState state = client.world.getBlockState(bedPos);
		if (!(state.getBlock() instanceof BedBlock)) { reset(); return; }
		ClientPlayerEntity player = client.player;
		if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(bedPos)) > 25.0D) { reset(); return; }

		BlockHitResult click = new BlockHitResult(
				Vec3d.ofCenter(bedPos).add(0.0D, 0.18D, 0.0D), Direction.UP, bedPos, false);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, click);
		if (result.isAccepted()) player.swingHand(Hand.MAIN_HAND);
		reset();
	}

	private BlockPos findPlacedBed(MinecraftClient client, BlockPos center) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos pos = center.add(dx, dy, dz);
					if (!(client.world.getBlockState(pos).getBlock() instanceof BedBlock)
							|| bedsPresentBeforePlacement.contains(pos.asLong())) continue;
					double distance = pos.getSquaredDistance(center);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos.toImmutable();
					}
				}
			}
		}
		return best;
	}

	private void rememberNearbyBeds(World world, BlockPos center) {
		bedsPresentBeforePlacement.clear();
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos pos = center.add(dx, dy, dz);
					if (world.getBlockState(pos).getBlock() instanceof BedBlock) {
						bedsPresentBeforePlacement.add(pos.asLong());
					}
				}
			}
		}
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || !config.subTiersAutoBed) return false;
		ClientPlayerEntity player = client == null ? null : client.player;
		return player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == null && player.isAlive() && !player.isSpectator();
	}

	private long ns(double ms) { return (long) (ms * 1_000_000D); }

	private void reset() {
		placementHint = null;
		bedPos = null;
		bedsPresentBeforePlacement.clear();
		scanAtNanos = 0L;
		detonateAtNanos = 0L;
		expireAtNanos = 0L;
	}
}
