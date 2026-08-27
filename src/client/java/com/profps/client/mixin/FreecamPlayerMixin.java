package com.profps.client.mixin;

import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears the latched sneak and sprint state flags each movement tick while freecam is active. */
@Mixin(ClientPlayerEntity.class)
public abstract class FreecamPlayerMixin {
	@Inject(method = "tickMovement", at = @At("HEAD"))
	private void profps$dropLatchedPoseFlags(CallbackInfo ci) {
		if (!FreecamController.isActive()) return;
		ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
		self.setSneaking(false);
		self.setSprinting(false);
	}
}
