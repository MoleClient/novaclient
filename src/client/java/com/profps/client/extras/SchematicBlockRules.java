package com.profps.client.extras;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * Support, interaction and ordering rules for schematic cells, read off the desired
 * {@link BlockState} rather than a block whitelist wherever the state carries the answer.
 */
final class SchematicBlockRules {
	/** Build phases, lowest first. */
	enum Phase {
		/** Plain blocks. */
		STRUCTURE,
		/** Needs one specific neighbour: rails, dust, torches, ladders, plates. */
		ATTACHED,
		/** Redstone whose state a stray click or neighbour update ruins. */
		DELICATE,
		/** Water, lava, and waterlogging; last because fluids spread. */
		FLUID
	}

	/**
	 * Interactive blocks that are not block entities. Placing against any of these
	 * requires sneaking or the click triggers their use action instead.
	 */
	private static final Set<Block> INTERACTIVE_WITHOUT_BLOCK_ENTITY = Set.of(
			Blocks.REPEATER, Blocks.NOTE_BLOCK, Blocks.LEVER, Blocks.DAYLIGHT_DETECTOR,
			Blocks.CAKE, Blocks.CAULDRON, Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON,
			Blocks.POWDER_SNOW_CAULDRON, Blocks.FLOWER_POT, Blocks.DRAGON_EGG,
			Blocks.RESPAWN_ANCHOR, Blocks.TRIPWIRE_HOOK, Blocks.CRAFTING_TABLE,
			Blocks.STONECUTTER, Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
			Blocks.LOOM, Blocks.GRINDSTONE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
			Blocks.DAMAGED_ANVIL, Blocks.COMPOSTER, Blocks.LODESTONE);

	/** Redstone whose delay, mode, direction or power a later action can change. */
	private static final Set<Block> DELICATE_REDSTONE = Set.of(
			Blocks.REDSTONE_WIRE, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.OBSERVER,
			Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.PISTON,
			Blocks.STICKY_PISTON, Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER,
			Blocks.LEVER, Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK, Blocks.TARGET,
			Blocks.NOTE_BLOCK, Blocks.DAYLIGHT_DETECTOR, Blocks.CRAFTER,
			Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL);

	private SchematicBlockRules() {
	}

	/**
	 * Direction from the block toward the neighbour it hangs off, or null when any
	 * solid face will do.
	 */
	static Direction supportDirection(BlockState state) {
		// Wall/floor/ceiling mounts carry the mount and the facing in separate properties.
		String face = firstValue(state, "face", "block_face", "attachment");
		if (!face.isEmpty()) {
			switch (face) {
				case "floor", "standing" -> {
					return Direction.DOWN;
				}
				case "ceiling", "hanging" -> {
					return Direction.UP;
				}
				case "wall", "single_wall", "double_wall" -> {
					Direction facing = direction(propertyValue(state, "facing"));
					return facing == null ? null : facing.getOpposite();
				}
				default -> {
				}
			}
		}

		String hanging = propertyValue(state, "hanging");
		if (hanging.equals("true")) return Direction.UP;
		if (hanging.equals("false")) return Direction.DOWN;

		// Pointed dripstone: a downward tip grows from the ceiling.
		String vertical = propertyValue(state, "vertical_direction");
		if (vertical.equals("down")) return Direction.UP;
		if (vertical.equals("up")) return Direction.DOWN;

		// Blocks whose facing points away from the surface they cling to.
		if (clingsToFacingSurface(state)) {
			Direction facing = direction(propertyValue(state, "facing"));
			if (facing != null) return facing.getOpposite();
		}

		if (state.getBlock() instanceof FallingBlock) return Direction.DOWN;
		if (needsFloor(state)) return Direction.DOWN;
		return null;
	}

	/** True when this cell cannot exist until the cell above it does. */
	static boolean needsSupportAbove(BlockState state) {
		return supportDirection(state) == Direction.UP;
	}

	/** True for a block whose right-click does something other than place. */
	static boolean isInteractive(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof BlockEntityProvider) return true;
		if (INTERACTIVE_WITHOUT_BLOCK_ENTITY.contains(block)) return true;
		// Doors, trapdoors, fence gates and buttons all toggle on a bare click.
		return hasProperty(state, "open") || hasProperty(state, "delay")
				|| hasProperty(state, "note") || hasProperty(state, "mode");
	}

	/** True for redstone whose configured state a later action can disturb. */
	static boolean isDelicate(BlockState state) {
		return DELICATE_REDSTONE.contains(state.getBlock());
	}

	/**
	 * True when the block is itself a spreading fluid. Not a {@code getFluidState} check,
	 * which would also match waterlogged blocks; see {@link #isWaterlogged}.
	 */
	static boolean isFluid(BlockState state) {
		return state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA) || state.isOf(Blocks.BUBBLE_COLUMN);
	}

	/** True when the desired state carries water inside another block. */
	static boolean isWaterlogged(BlockState state) {
		return propertyValue(state, "waterlogged").equals("true");
	}

	/** The same state without waterlogging, so it can be placed dry and filled on the fluid pass. */
	static BlockState dewatered(BlockState state) {
		return state.withIfExists(Properties.WATERLOGGED, false);
	}

	/** The build phase this cell belongs to. */
	static Phase phaseOf(BlockState state) {
		if (isFluid(state) || isWaterlogged(state)) return Phase.FLUID;
		if (isDelicate(state)) return Phase.DELICATE;
		if (supportDirection(state) != null) return Phase.ATTACHED;
		return Phase.STRUCTURE;
	}

	/** True when a block clicked against {@code support} must be placed sneaking. */
	static boolean mustSneakAgainst(BlockState support) {
		return isInteractive(support);
	}

	// Property lookup is by name so unknown or version-renamed properties simply miss.

	static String propertyValue(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) return valueOf(state, property);
		}
		return "";
	}

	private static String firstValue(BlockState state, String... names) {
		for (String name : names) {
			String value = propertyValue(state, name);
			if (!value.isEmpty()) return value;
		}
		return "";
	}

	private static boolean hasProperty(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) return true;
		}
		return false;
	}

	private static <T extends Comparable<T>> String valueOf(BlockState state, Property<T> property) {
		return property.name(state.get(property));
	}

	static Direction direction(String name) {
		return switch (name) {
			case "north" -> Direction.NORTH;
			case "south" -> Direction.SOUTH;
			case "east" -> Direction.EAST;
			case "west" -> Direction.WEST;
			case "up" -> Direction.UP;
			case "down" -> Direction.DOWN;
			default -> null;
		};
	}

	private static boolean clingsToFacingSurface(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.LADDER || block == Blocks.COCOA || block == Blocks.TRIPWIRE_HOOK
				|| block == Blocks.REDSTONE_WALL_TORCH || block == Blocks.WALL_TORCH
				|| block == Blocks.SOUL_WALL_TORCH) return true;
		// Vanilla wall-mounted families spell the mount into the block name.
		String id = block.getTranslationKey();
		return id.contains("wall_sign") || id.contains("wall_banner") || id.contains("wall_head")
				|| id.contains("wall_skull") || id.contains("wall_fan") || id.contains("wall_hanging")
				|| id.contains("amethyst_cluster") || id.contains("_bud");
	}

	/** Blocks that sit on a floor and pop the instant it goes away. */
	private static boolean needsFloor(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.REDSTONE_WIRE || block == Blocks.REPEATER || block == Blocks.COMPARATOR
				|| block == Blocks.RAIL || block == Blocks.POWERED_RAIL || block == Blocks.DETECTOR_RAIL
				|| block == Blocks.ACTIVATOR_RAIL || block == Blocks.SNOW || block == Blocks.CACTUS
				|| block == Blocks.SUGAR_CANE || block == Blocks.TRIPWIRE) return true;
		// Wall variants are resolved by clingsToFacingSurface before this runs.
		String id = block.getTranslationKey();
		return id.contains("pressure_plate") || id.contains("carpet") || id.contains("_door")
				|| id.contains("sapling") || id.contains("_rail") || id.contains("torch")
				|| id.contains("flower") || id.contains("_tulip") || id.contains("mushroom")
				|| id.contains("candle") || id.contains("banner") || id.contains("_sign");
	}
}
