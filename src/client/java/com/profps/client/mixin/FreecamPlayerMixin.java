package com.profps.client.mixin;

import com.profps.client.donutsmp.FreecamController;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Strips sneak and sprint from the local player at the top of every movement
 * tick while the freecam flies. Input is already zeroed at the KeyboardInput
 * layer, but these are latched STATE flags, not inputs — whatever was held at
 * the moment of toggling would otherwise stay latched on the frozen body
 * (a permanent crouch pose, or sprint FOV stuck on the detached camera).
 */
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
