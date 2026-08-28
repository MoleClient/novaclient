package com.profps.client.mixin;

import com.profps.client.instants.InstamineController;
import net.minecraft.block.AbstractBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drives the per-tick block-breaking fraction toward Instamine's target tick count. */
@Mixin(targets = "net.minecraft.block.AbstractBlock$AbstractBlockState")
public abstract class InstamineMixin {
	@Inject(method = "calcBlockBreakingDelta", at = @At("RETURN"), cancellable = true)
	private void profps$instamine(PlayerEntity player, BlockView world, BlockPos pos,
			CallbackInfoReturnable<Float> cir) {
		if (!InstamineController.affects(player)) return;
		cir.setReturnValue(InstamineController.boost(cir.getReturnValueF(),
				((AbstractBlock.AbstractBlockState) (Object) this).getBlock()));
	}
}
