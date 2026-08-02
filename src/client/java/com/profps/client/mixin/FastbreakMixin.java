package com.profps.client.mixin;

import com.profps.client.instants.FastbreakController;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fastbreak — scales the per-tick block-breaking progress for the local player.
 * {@code BlockState.calcBlockBreakingDelta} is the fraction of a block broken
 * each tick; boosting it makes breaking faster. The boost is modest + jittered
 * (see {@link FastbreakController}) so it stays plausible on servers.
 */
@Mixin(targets = "net.minecraft.block.AbstractBlock$AbstractBlockState")
public abstract class FastbreakMixin {
	@Inject(method = "calcBlockBreakingDelta", at = @At("RETURN"), cancellable = true)
	private void profps$fastbreak(PlayerEntity player, BlockView world, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (FastbreakController.affects(player)) {
			cir.setReturnValue(FastbreakController.boost(cir.getReturnValueF()));
		}
	}
}
