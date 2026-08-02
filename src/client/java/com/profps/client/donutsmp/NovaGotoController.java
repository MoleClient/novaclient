package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public final class NovaGotoController {
	private final ProFPSConfig config;
	private final StashPinger stashPinger;
	private final Random random = new Random();
	private final HumanizedAim aim = new HumanizedAim();
	private Vec3d target;
	private BlockPos aimTarget;
	private Vec3d aimPoint = Vec3d.ZERO;
	private Vec3d displayedAimPoint = Vec3d.ZERO;
	private int aimRerollTicks;

	public NovaGotoController(ProFPSConfig config, StashPinger stashPinger) {
		this.config = config;
		this.stashPinger = stashPinger;
	}

	public int startNear(MinecraftClient client) {
		if (client == null || client.player == null || client.world == null) return 0;
		StashPinger.BaseTarget base = stashPinger.bestBaseTarget(client);
		if (base == null) {
			chat(client, Text.literal("[Nova] No Base Was Located").formatted(Formatting.RED));
			return 0;
		}
		if (findDrillSlot(client.player) < 0) {
			chat(client, Text.literal("[Nova] Drill required: netherite pickaxe in hotbar").formatted(Formatting.RED));
			return 0;
		}
		target = base.center();
		BlockPos pos = BlockPos.ofFloored(target);
		chat(client, Text.literal("[Nova] Going to base near ")
				.formatted(Formatting.GRAY)
				.append(Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).formatted(Formatting.GOLD)));
		return 1;
	}

	public int stop(MinecraftClient client) {
		target = null;
		release(client);
		if (client != null) {
			chat(client, Text.literal("[Nova] Goto stopped").formatted(Formatting.YELLOW));
		}
		return 1;
	}

	public void tick(MinecraftClient client) {
		if (target == null) return;
		if (!config.enabled || client.world == null || client.player == null || client.currentScreen != null) {
			release(client);
			return;
		}
		ClientPlayerEntity player = client.player;
		int drillSlot = findDrillSlot(player);
		if (drillSlot < 0) {
			chat(client, Text.literal("[Nova] Drill required: netherite pickaxe in hotbar").formatted(Formatting.RED));
			stop(client);
			return;
		}
		if (player.getInventory().getSelectedSlot() != drillSlot) {
			player.getInventory().setSelectedSlot(drillSlot);
			release(client);
			return;
		}

		if (player.squaredDistanceTo(target) <= 9.0) {
			chat(client, Text.literal("[Nova] Arrived near base target").formatted(Formatting.GREEN));
			stop(client);
			return;
		}

		Direction travel = directionToTarget(player);
		if (hazardAhead(client, player, travel)) {
			release(client);
			client.options.sneakKey.setPressed(true);
			return;
		}

		BlockPos mineTarget = mineTarget(client, player, travel);
		if (mineTarget != null) {
			Vec3d aimVec = aimPointFor(mineTarget);
			aim.aimAt(player, aimVec, 1.65F);
			releaseMovement(client);
			client.options.forwardKey.setPressed(!isTouchingMiningFace(player, mineTarget, travel));
			client.options.attackKey.setPressed(true);
			if (client.interactionManager != null) {
				client.interactionManager.updateBlockBreakingProgress(mineTarget, miningSideFor(player, mineTarget, travel));
				player.swingHand(Hand.MAIN_HAND);
			}
			return;
		}

		releaseMovement(client);
		client.options.forwardKey.setPressed(true);
		// Sprint across open ground, dropping it occasionally so it isn't a
		// perfectly held key the way a bot would leave it.
		client.options.sprintKey.setPressed(random.nextInt(12) != 0);
		client.options.attackKey.setPressed(false);
		// Steer toward the target at eye level; the aim engine's tremor supplies
		// the subtle sway, so we don't re-randomize the look point each tick.
		Vec3d walkAim = new Vec3d(target.x, player.getEyeY(), target.z);
		aim.aimAt(player, walkAim, 0.85F);
	}

	private int findDrillSlot(ClientPlayerEntity player) {
		for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isOf(Items.NETHERITE_PICKAXE)) return slot;
		}
		return -1;
	}

	private Direction directionToTarget(ClientPlayerEntity player) {
		double dx = target.x - player.getX();
		double dz = target.z - player.getZ();
		if (Math.abs(dx) > Math.abs(dz)) return dx >= 0.0 ? Direction.EAST : Direction.WEST;
		return dz >= 0.0 ? Direction.SOUTH : Direction.NORTH;
	}

	private BlockPos mineTarget(MinecraftClient client, ClientPlayerEntity player, Direction travel) {
		BlockPos feet = player.getBlockPos();
		if (target.y < player.getY() - 1.4) {
			BlockPos down = feet.down();
			if (isMineable(client, down)) return down;
		}
		if (target.y > player.getY() + 1.8) {
			BlockPos up = feet.up(2);
			if (isMineable(client, up)) return up;
			client.options.jumpKey.setPressed(true);
		}
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int distance = 1; distance <= 2; distance++) {
			BlockPos base = feet.offset(travel, distance);
			for (int y = 1; y >= -1; y--) {
				for (int lateral = -1; lateral <= 1; lateral++) {
					pos.set(base.getX(), player.getBlockY() + y, base.getZ());
					pos.move(travel.rotateYClockwise(), lateral);
					if (isMineable(client, pos)) return pos.toImmutable();
				}
			}
		}
		return null;
	}

	private boolean isMineable(MinecraftClient client, BlockPos pos) {
		BlockState state = client.world.getBlockState(pos);
		return !state.isAir() && !state.getFluidState().isIn(FluidTags.LAVA) && state.getHardness(client.world, pos) >= 0.0F;
	}

	private boolean hazardAhead(MinecraftClient client, ClientPlayerEntity player, Direction travel) {
		BlockPos base = player.getBlockPos().offset(travel, 2);
		for (int y = -2; y <= 2; y++) {
			for (int lateral = -1; lateral <= 1; lateral++) {
				BlockPos pos = base.up(y).offset(travel.rotateYClockwise(), lateral);
				if (isLava(client, pos)) return true;
			}
		}
		return isLava(client, base.down()) || client.world.getBlockState(base.down()).isAir();
	}

	private boolean isLava(MinecraftClient client, BlockPos pos) {
		BlockState state = client.world.getBlockState(pos);
		return state.isOf(Blocks.LAVA) || state.getFluidState().isIn(FluidTags.LAVA);
	}

	private Vec3d aimPointFor(BlockPos block) {
		// One stable point per block; no per-second re-roll that makes the head wander.
		if (!block.equals(aimTarget)) {
			aimTarget = block;
			aimPoint = new Vec3d(
					block.getX() + 0.43 + random.nextDouble() * 0.14,
					block.getY() + 0.43 + random.nextDouble() * 0.14,
					block.getZ() + 0.43 + random.nextDouble() * 0.14
			);
		}
		return aimPoint;
	}

	private boolean isTouchingMiningFace(ClientPlayerEntity player, BlockPos block, Direction forward) {
		double playerCoord = forward.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
		return switch (forward) {
			case NORTH -> playerCoord - 0.31 <= block.getZ() + 1.04;
			case SOUTH -> playerCoord + 0.31 >= block.getZ() - 0.04;
			case WEST -> playerCoord - 0.31 <= block.getX() + 1.04;
			case EAST -> playerCoord + 0.31 >= block.getX() - 0.04;
			default -> true;
		};
	}

	private Direction miningSideFor(ClientPlayerEntity player, BlockPos block, Direction travel) {
		if (block.getX() == player.getBlockX() && block.getZ() == player.getBlockZ()) {
			if (block.getY() < player.getBlockY()) return Direction.UP;
			if (block.getY() > player.getBlockY() + 1) return Direction.DOWN;
		}
		return travel.getOpposite();
	}

	private void release(MinecraftClient client) {
		if (client == null || client.options == null) return;
		releaseMovement(client);
		client.options.attackKey.setPressed(false);
		client.options.useKey.setPressed(false);
	}

	private void releaseMovement(MinecraftClient client) {
		client.options.forwardKey.setPressed(false);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sprintKey.setPressed(false);
	}

	private void chat(MinecraftClient client, Text text) {
		client.inGameHud.getChatHud().addMessage(text);
	}
}
