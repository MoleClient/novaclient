package com.profps.client.mixin;

import com.profps.client.ProFPSClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the melee auto-attack modules at the tail of {@link MinecraftClient#handleInputEvents()},
 * the tick phase where vanilla processes a left-click. Running here keeps the attack and swing
 * packets ahead of the tick's movement packet and before movement is simulated.
 */
@Mixin(MinecraftClient.class)
public abstract class TriggerbotPreMovementMixin {

	@Inject(method = "handleInputEvents", at = @At("TAIL"))
	private void profps$fireMeleeAtVanillaClickTime(CallbackInfo ci) {
		ProFPSClient.firePreMovement((MinecraftClient) (Object) this);
	}
}
