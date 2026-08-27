package com.profps.client.mixin;

import com.profps.client.instants.FastbreakController;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scales the per-tick block-breaking progress for the local player. */
@Mixin(targets = "net.minecraft.block.AbstractBlock$AbstractBlockState")
public abstract class FastbreakMixin {
	@Inject(method = "calcBlockBreakingDelta", at = @At("RETURN"), cancellable = true)
	private void profps$fastbreak(PlayerEntity player, BlockView world, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (FastbreakController.affects(player)) {
			cir.setReturnValue(FastbreakController.boost(cir.getReturnValueF()));
		}
	}
}
