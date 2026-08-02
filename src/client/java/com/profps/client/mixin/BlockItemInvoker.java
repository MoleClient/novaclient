package com.profps.client.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla's own placement prediction and legality checks. */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
	@Invoker("getPlacementState")
	BlockState profps$getPlacementState(ItemPlacementContext context);

	@Invoker("canPlace")
	boolean profps$canPlace(ItemPlacementContext context, BlockState state);
}
