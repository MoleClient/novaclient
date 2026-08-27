package com.profps.client.donutsmp;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.ConcretePowderBlock;
import net.minecraft.block.GlazedTerracottaBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StainedGlassPaneBlock;
import net.minecraft.registry.tag.BlockTags;

import java.util.Set;

/**
 * Shared classifier for "a player built this" blocks, used by the base-finding
 * scan in Hole/Tunnel/Stairs ESP.
 *
 * <p>The original detectors only knew about functional blocks (chests,
 * furnaces, redstone). A raided/destroyed base keeps none of those — what
 * remains is the SHELL: stained glass, concrete, wool, glowstone, candles,
 * quartz... none of which generate naturally underground. This classifier is
 * what lets the scanners see that shell.
 */
public final class PlayerPlacedBlocks {

	private static final Set<Block> CONCRETE = Set.of(
			Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE,
			Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE, Blocks.PINK_CONCRETE, Blocks.GRAY_CONCRETE,
			Blocks.LIGHT_GRAY_CONCRETE, Blocks.CYAN_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE,
			Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE, Blocks.BLACK_CONCRETE);

	private static final Set<Block> DECOR = Set.of(
			Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.REDSTONE_LAMP, Blocks.END_ROD,
			Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT,
			Blocks.LANTERN, Blocks.SOUL_LANTERN,
			Blocks.GLASS, Blocks.GLASS_PANE, Blocks.TINTED_GLASS,
			Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF, Blocks.DECORATED_POT,
			Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
			Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR,
			Blocks.CRYING_OBSIDIAN, Blocks.RESPAWN_ANCHOR, Blocks.ENDER_CHEST, Blocks.LODESTONE,
			Blocks.IRON_BARS, Blocks.IRON_CHAIN, Blocks.SMOOTH_STONE, Blocks.BRICKS,
			Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.TARGET, Blocks.SCAFFOLDING, Blocks.TNT,
			Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
			Blocks.NETHERITE_BLOCK);

	private PlayerPlacedBlocks() {}

	/**
	 * Build/decor blocks that essentially never occur in natural underground
	 * generation. Glowstone IS natural in the nether — callers scanning nether
	 * chunks should pass {@code inNether = true} so it doesn't false-ping.
	 */
	public static boolean isBuildDecor(BlockState state, boolean inNether) {
		Block block = state.getBlock();
		if (inNether && (block == Blocks.GLOWSTONE || block == Blocks.SHROOMLIGHT)) return false;
		if (DECOR.contains(block) || CONCRETE.contains(block)) return true;
		if (block instanceof StainedGlassBlock || block instanceof StainedGlassPaneBlock
				|| block instanceof CandleBlock || block instanceof BedBlock
				|| block instanceof GlazedTerracottaBlock || block instanceof ConcretePowderBlock
				|| block instanceof ShulkerBoxBlock) {
			return true;
		}
		return state.isIn(BlockTags.WOOL) || state.isIn(BlockTags.WOOL_CARPETS);
	}
}
