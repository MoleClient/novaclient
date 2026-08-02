package com.profps.client.mixin;

import com.profps.client.assists.SlowController;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slow module — stretches the local player's hand-swing animation.
 *
 * <p>{@code getHandSwingDuration()} controls how many ticks one swing animation
 * plays over (and how long {@code handSwinging} stays true). Multiplying the
 * returned value for the local player alone makes mining/attacking/placing look
 * far slower without changing the attack, block-break or use packets the
 * interaction manager sends — those run on their own clock, so the server keeps
 * registering everything at full speed. Haste / mining-fatigue scaling is
 * preserved because we hook the value vanilla already computed at RETURN.
 */
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
