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
 * What a schematic cell needs, and when it may be built.
 *
 * <p>A schematic is not a pile of independent blocks. A rail needs the block
 * under it, a wall torch needs the wall beside it, a hanging lantern needs the
 * ceiling <em>above</em> it — which is a cell of the next layer up, so a strict
 * bottom-to-top sweep can never finish that column on its own. A repeater's
 * delay is set by right-clicking it, so any block placed against a repeater has
 * to be placed sneaking or the click re-times the circuit instead. Water placed
 * before the redstone around it is finished floods the circuit and washes the
 * components out.
 *
 * <p>Every rule here answers one of those, and all of them are read off the
 * desired {@link BlockState} rather than a block whitelist wherever the state
 * carries the answer, so modded or newly added blocks behave sensibly by
 * default.
 */
final class SchematicBlockRules {
	/**
	 * Build phases, lowest first. A cell is only started once every cell it
	 * depends on is complete, and phases are the coarse version of that: the
	 * skeleton exists before anything attaches to it, components go on before
	 * anything delicate is disturbed, and fluids go last of all.
	 */
	enum Phase {
		/** Plain blocks. The skeleton everything else attaches to. */
		STRUCTURE,
		/** Needs one specific neighbour: rails, dust, torches, ladders, plates. */
		ATTACHED,
		/** Redstone whose state a stray click or a neighbour update ruins. */
		DELICATE,
		/** Water, lava, and waterlogging. Always last — fluids spread. */
		FLUID
	}

	/**
	 * Blocks that answer a bare right-click with something other than a
	 * placement. Anything placed against one of these must be placed sneaking,
	 * or the click opens the container / re-times the repeater / flips the
	 * lever instead of putting a block down. Block entities cover most of it
	 * (chests, furnaces, hoppers, signs, lecterns); these are the rest.
	 */
	private static final Set<Block> INTERACTIVE_WITHOUT_BLOCK_ENTITY = Set.of(
			Blocks.REPEATER, Blocks.NOTE_BLOCK, Blocks.LEVER, Blocks.DAYLIGHT_DETECTOR,
			Blocks.CAKE, Blocks.CAULDRON, Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON,
			Blocks.POWDER_SNOW_CAULDRON, Blocks.FLOWER_POT, Blocks.DRAGON_EGG,
			Blocks.RESPAWN_ANCHOR, Blocks.TRIPWIRE_HOOK, Blocks.CRAFTING_TABLE,
			Blocks.STONECUTTER, Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
			Blocks.LOOM, Blocks.GRINDSTONE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
			Blocks.DAMAGED_ANVIL, Blocks.COMPOSTER, Blocks.LODESTONE);

	/**
	 * Redstone whose behaviour depends on state a later action can silently
	 * change — delay, mode, direction, power. These go on late, and nothing is
	 * placed against them without sneaking.
	 */
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
	 * The neighbour this block physically hangs off, or null when any solid
	 * face will do.
	 *
	 * <p>Read as a direction <em>from the block toward its support</em>: a
	 * floor-standing lever answers DOWN, a wall torch answers the opposite of
	 * its facing, a hanging lantern answers UP. UP is the interesting one —
	 * see {@link #needsSupportAbove}.
	 */
	static Direction supportDirection(BlockState state) {
		// Wall/floor/ceiling mounts (levers, buttons, grindstones) carry the
		// mount in one property and the direction it points in another.
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

		// Ladders, wall signs, wall banners, wall heads, wall torches, cocoa —
		// blocks whose facing points away from the surface they cling to.
		if (clingsToFacingSurface(state)) {
			Direction facing = direction(propertyValue(state, "facing"));
			if (facing != null) return facing.getOpposite();
		}

		if (state.getBlock() instanceof FallingBlock) return Direction.DOWN;
		if (needsFloor(state)) return Direction.DOWN;
		return null;
	}

	/**
	 * True when this cell cannot exist until the cell above it does. This is
	 * the case a bottom-to-top sweep cannot serve by itself, and the reason the
	 * builder pulls a block forward out of a later layer.
	 */
	static boolean needsSupportAbove(BlockState state) {
		return supportDirection(state) == Direction.UP;
	}

	/** True for a block whose right-click does something other than place. */
	static boolean isInteractive(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof BlockEntityProvider) return true;
		if (INTERACTIVE_WITHOUT_BLOCK_ENTITY.contains(block)) return true;
		// Doors, trapdoors, fence gates, buttons: all carry "open" or "powered"
		// and all answer a bare click by toggling.
		return hasProperty(state, "open") || hasProperty(state, "delay")
				|| hasProperty(state, "note") || hasProperty(state, "mode");
	}

	/** True for redstone whose configured state a later action can disturb. */
	static boolean isDelicate(BlockState state) {
		return DELICATE_REDSTONE.contains(state.getBlock());
	}

	/**
	 * True when the block <em>is</em> a fluid that will spread once placed.
	 * Deliberately not a check on {@code getFluidState}: a waterlogged stair
	 * has a water fluid state but is a stair, and stairs go in with the dry
	 * build. See {@link #isWaterlogged}.
	 */
	static boolean isFluid(BlockState state) {
		return state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA) || state.isOf(Blocks.BUBBLE_COLUMN);
	}

	/** True when the desired state carries water inside another block. */
	static boolean isWaterlogged(BlockState state) {
		return propertyValue(state, "waterlogged").equals("true");
	}

	/**
	 * The same block with its water taken out, so it can be placed dry during
	 * the main build and filled with a bucket on the fluid pass.
	 *
	 * <p>Without this a fresh waterlogged cell can never be built at all: the
	 * placement prediction is dry, so it never matches a waterlogged goal, and
	 * the bucket branch that would fix it only triggers once the block already
	 * exists. The cell retries forever. Splitting the goal in two breaks that.
	 */
	static BlockState dewatered(BlockState state) {
		return state.withIfExists(Properties.WATERLOGGED, false);
	}

	/**
	 * When this cell may be built. Fluids and waterlogging sort last because
	 * water spreads the instant it exists: a source placed while the circuit
	 * beside it is still open flows into it and washes out every component it
	 * touches. Delicate redstone sorts after plain structure for the same
	 * reason in miniature — the fewer neighbour updates a repeater sees after
	 * it is placed, the fewer chances it has to be left in the wrong state.
	 */
	static Phase phaseOf(BlockState state) {
		if (isFluid(state) || isWaterlogged(state)) return Phase.FLUID;
		if (isDelicate(state)) return Phase.DELICATE;
		if (supportDirection(state) != null) return Phase.ATTACHED;
		return Phase.STRUCTURE;
	}

	/**
	 * True when a block clicked against {@code support} must be placed while
	 * sneaking. Sneaking is what tells vanilla to skip the block's own use
	 * action, so this is the difference between putting a block beside a
	 * repeater and re-timing it.
	 */
	static boolean mustSneakAgainst(BlockState support) {
		return isInteractive(support);
	}

	// ── Property reading ───────────────────────────────────────────────────────
	// String-based on purpose: the same block property is spelled differently
	// across versions and mods, and a missing constant here would be a compile
	// error for a property this code is happy to simply not find.

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

	/** Manual so a renamed {@code Direction.byName} can never break the build. */
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
		// Wall signs, wall banners, wall heads, wall fans, wall skulls: every
		// vanilla family spells the mount into the block name itself.
		String id = block.getTranslationKey();
		return id.contains("wall_sign") || id.contains("wall_banner") || id.contains("wall_head")
				|| id.contains("wall_skull") || id.contains("wall_fan") || id.contains("wall_hanging")
				// Amethyst grows out of whatever face it points away from, which
				// is as often a ceiling as a floor.
				|| id.contains("amethyst_cluster") || id.contains("_bud");
	}

	/** Blocks that sit on a floor and pop the instant it goes away. */
	private static boolean needsFloor(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.REDSTONE_WIRE || block == Blocks.REPEATER || block == Blocks.COMPARATOR
				|| block == Blocks.RAIL || block == Blocks.POWERED_RAIL || block == Blocks.DETECTOR_RAIL
				|| block == Blocks.ACTIVATOR_RAIL || block == Blocks.SNOW || block == Blocks.CACTUS
				|| block == Blocks.SUGAR_CANE || block == Blocks.TRIPWIRE) return true;
		// Wall variants are resolved by clingsToFacingSurface before this runs,
		// so a bare "torch" match here only ever catches the floor-standing one.
		String id = block.getTranslationKey();
		return id.contains("pressure_plate") || id.contains("carpet") || id.contains("_door")
				|| id.contains("sapling") || id.contains("_rail") || id.contains("torch")
				|| id.contains("flower") || id.contains("_tulip") || id.contains("mushroom")
				|| id.contains("candle") || id.contains("banner") || id.contains("_sign");
	}
}
