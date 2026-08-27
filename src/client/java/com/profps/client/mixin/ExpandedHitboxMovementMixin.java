package com.profps.client.mixin;

import com.profps.client.assists.ExpandedHitboxController;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the Expanded Hitbox packet rotation around movement packet construction. */
@Mixin(ClientPlayerEntity.class)
public abstract class ExpandedHitboxMovementMixin {

	@Inject(method = "sendMovementPackets", at = @At("HEAD"))
	private void profps$beforeExpandedHitboxMovement(CallbackInfo ci) {
		ExpandedHitboxController.beforeMovementPacket((ClientPlayerEntity) (Object) this);
	}

	@Inject(method = "sendMovementPackets", at = @At("RETURN"))
	private void profps$afterExpandedHitboxMovement(CallbackInfo ci) {
		ExpandedHitboxController.afterMovementPacket((ClientPlayerEntity) (Object) this);
	}
}
