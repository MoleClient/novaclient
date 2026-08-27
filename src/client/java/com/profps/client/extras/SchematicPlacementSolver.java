package com.profps.client.extras;

import com.profps.client.mixin.BlockItemInvoker;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves the stand, aim point and click that produce a given block state. Vanilla
 * derives horizontal facing from the yaw at click time, so each candidate is evaluated
 * as a body position plus the rotation it implies, raycast from a hypothetical eye.
 */
final class SchematicPlacementSolver {
	/** Survival block reach, measured eye to hit point as vanilla does. */
	static final double MAX_REACH = 4.5D;
	static final double MAX_REACH_SQUARED = MAX_REACH * MAX_REACH;
	private static final double EYE_STANDING = 1.62D;
	private static final double EYE_SNEAKING = 1.27D;
	/** Face sample points, centre first. */
	private static final int FACE_SAMPLES = 5;

	private SchematicPlacementSolver() {
	}

	/** A body position and posture a placement could be made from. */
	record Stand(int x, int y, int z, boolean sneak) {
		Vec3d eye() {
			return new Vec3d(x + 0.5D, y + (sneak ? EYE_SNEAKING : EYE_STANDING), z + 0.5D);
		}

		SchematicPathfinder.Node node() {
			return new SchematicPathfinder.Node(x, y, z);
		}

		static Stand of(SchematicPathfinder.Node node, boolean sneak) {
			return new Stand(node.x(), node.y(), node.z(), sneak);
		}
	}

	/**
	 * A proved placement. {@code sneak} is the posture the click needs, separate from the
	 * stand's own posture.
	 */
	record Solution(Stand stand, Vec3d aimPoint, BlockHitResult hit, BlockState predicted, boolean sneak) {
	}

	/** Supplied by the controller so its property-matching rules stay in one place. */
	interface StateMatcher {
		boolean matches(BlockState desired, BlockState predicted, BlockState current);
	}

	/** Proves a placement from one specific body position, or returns null. */
	static Solution solveFrom(MinecraftClient client, Stand stand, BlockPos target,
			BlockState desired, StateMatcher matcher) {
		return solveFromEye(client, stand, stand.eye(), target, desired, matcher);
	}

	/** Proves a placement from the body's real eye rather than a stand's idealised one. */
	static Solution solveHere(MinecraftClient client, BlockPos target, BlockState desired, StateMatcher matcher) {
		ClientPlayerEntity player = client.player;
		Stand approximate = new Stand(MathHelper.floor(player.getX()),
				MathHelper.floor(player.getBoundingBox().minY + 0.50D),
				MathHelper.floor(player.getZ()), player.isSneaking());
		return solveFromEye(client, approximate, player.getEyePos(), target, desired, matcher);
	}

	private static Solution solveFromEye(MinecraftClient client, Stand stand, Vec3d eye, BlockPos target,
			BlockState desired, StateMatcher matcher) {
		Item item = desired.getBlock().asItem();
		if (item == Items.AIR || !(item instanceof BlockItem blockItem)) return null;
		ItemStack stack = new ItemStack(item);
		BlockState current = client.world.getBlockState(target);

		for (Direction towardSupport : Direction.values()) {
			BlockPos support = target.offset(towardSupport);
			BlockState supportState = client.world.getBlockState(support);
			if (supportState.isReplaceable()
					|| supportState.getCollisionShape(client.world, support).isEmpty()) continue;
			Direction clickedSide = towardSupport.getOpposite();
			boolean sneak = SchematicBlockRules.mustSneakAgainst(supportState);

			for (int sample = 0; sample < FACE_SAMPLES; sample++) {
				Vec3d point = facePoint(support, clickedSide, sample);
				if (eye.squaredDistanceTo(point) > MAX_REACH_SQUARED) continue;
				BlockHitResult hit = castFrom(client, eye, point);
				if (hit == null || !hit.getBlockPos().equals(support) || hit.getSide() != clickedSide) continue;

				BlockState predicted = predictUnder(client, blockItem, stack, hit, eye, point, target);
				if (predicted == null || !matcher.matches(desired, predicted, current)) continue;
				return new Solution(stand, point, hit, predicted, sneak);
			}
		}
		return null;
	}

	/** The first stand in the already-ranked {@code stands} that can place {@code target} correctly. */
	static Solution solve(MinecraftClient client, BlockPos target, BlockState desired,
			List<Stand> stands, StateMatcher matcher, int examineLimit) {
		int examined = 0;
		for (Stand stand : stands) {
			if (examined++ >= examineLimit) break;
			Solution solution = solveFrom(client, stand, target, desired, matcher);
			if (solution != null) return solution;
		}
		return null;
	}

	/**
	 * Body positions worth trying for {@code target}, cheapest first: overhead stands,
	 * then stands above the cell, then the nearest. Each is offered standing and sneaking.
	 */
	static List<Stand> candidateStands(SchematicPathfinder.Space space, BlockPos target,
			SchematicPathfinder.Node from, int radius, int minDy, int maxDy) {
		List<Ranked> ranked = new ArrayList<>();
		Vec3d centre = Vec3d.ofCenter(target);

		for (int dy = minDy; dy <= maxDy; dy++) {
			int y = target.getY() + dy;
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					boolean overhead = dx == 0 && dz == 0;
					if (overhead && dy < 1) continue;
					if (Math.abs(dx) + Math.abs(dz) > radius + 2) continue;
					int x = target.getX() + dx;
					int z = target.getZ() + dz;
					if (!space.standable(x, y, z) || space.hazardous(x, y, z)) continue;

					SchematicPathfinder.Node node = new SchematicPathfinder.Node(x, y, z);
					double cost = Math.sqrt(node.squaredDistanceTo(from));
					if (overhead) cost -= 3.0D;
					else if (dy >= 1) cost -= 1.0D;

					for (boolean sneak : new boolean[]{false, true}) {
						Stand stand = new Stand(x, y, z, sneak);
						if (stand.eye().squaredDistanceTo(centre) > MAX_REACH_SQUARED) continue;
						ranked.add(new Ranked(stand, cost + (sneak ? 0.75D : 0.0D)));
					}
				}
			}
		}
		ranked.sort(Comparator.comparingDouble(Ranked::cost));
		List<Stand> out = new ArrayList<>(ranked.size());
		for (Ranked entry : ranked) out.add(entry.stand());
		return out;
	}

	/** Yaw/pitch that looks from {@code eye} straight at {@code target}. */
	static float[] rotationTo(Vec3d eye, Vec3d target) {
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return new float[]{
				(float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
				MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -89.0F, 89.0F)
		};
	}

	/** One of five points on a block face; sample 0 is the centre, the rest step off-centre. */
	static Vec3d facePoint(BlockPos block, Direction side, int sample) {
		double a = sample == 1 ? 0.28D : sample == 2 ? 0.72D : 0.5D;
		double b = sample == 3 ? 0.28D : sample == 4 ? 0.72D : 0.5D;
		double x = side.getAxis() == Direction.Axis.X ? (side == Direction.EAST ? 1.0D : 0.0D) : a;
		double y = side.getAxis() == Direction.Axis.Y ? (side == Direction.UP ? 1.0D : 0.0D) : b;
		double z = side.getAxis() == Direction.Axis.Z ? (side == Direction.SOUTH ? 1.0D : 0.0D)
				: side.getAxis() == Direction.Axis.X ? a : b;
		return new Vec3d(block.getX() + x, block.getY() + y, block.getZ() + z);
	}

	/** Line of sight from an arbitrary eye, independent of where the body is. */
	private static BlockHitResult castFrom(MinecraftClient client, Vec3d eye, Vec3d point) {
		BlockHitResult hit = client.world.raycast(new RaycastContext(
				eye, point, RaycastContext.ShapeType.OUTLINE,
				RaycastContext.FluidHandling.NONE, client.player));
		return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	/**
	 * Vanilla's placement prediction, evaluated under the rotation the stand and aim point
	 * imply. The player's rotation is set for the prediction and restored afterwards.
	 */
	private static BlockState predictUnder(MinecraftClient client, BlockItem blockItem, ItemStack stack,
			BlockHitResult hit, Vec3d eye, Vec3d aimPoint, BlockPos expected) {
		ClientPlayerEntity player = client.player;
		float yaw = player.getYaw();
		float pitch = player.getPitch();
		float[] rotation = rotationTo(eye, aimPoint);
		try {
			player.setYaw(rotation[0]);
			player.setPitch(rotation[1]);
			ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
			if (!context.getBlockPos().equals(expected) || !context.canPlace()) return null;
			BlockState predicted = ((BlockItemInvoker) blockItem).profps$getPlacementState(context);
			if (predicted == null || !((BlockItemInvoker) blockItem).profps$canPlace(context, predicted)) return null;
			return predicted;
		} finally {
			player.setYaw(yaw);
			player.setPitch(pitch);
		}
	}

	private record Ranked(Stand stand, double cost) {
	}
}
