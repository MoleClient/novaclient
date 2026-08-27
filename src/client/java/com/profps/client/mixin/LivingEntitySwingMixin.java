package com.profps.client.mixin;

import com.profps.client.assists.SlowController;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stretches the local player's hand-swing animation duration. Visual only; packets are unaffected. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
	@Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true)
	private void profps$slowSwingAnimation(CallbackInfoReturnable<Integer> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (SlowController.affects(self)) {
			cir.setReturnValue(SlowController.scaleDuration(cir.getReturnValueI()));
		}
	}
}
