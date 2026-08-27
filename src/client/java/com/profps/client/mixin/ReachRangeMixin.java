package com.profps.client.mixin;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raises {@link PlayerEntity#getEntityInteractionRange()} for the local player while Reach
 * is on, which is the range the crosshair raycast uses. Vanilla default is 3.0.
 * The separate weapon attack-range gate is handled by {@link ReachAttackMixin}.
 */
@Mixin(PlayerEntity.class)
public abstract class ReachRangeMixin {

	@Inject(method = "getEntityInteractionRange", at = @At("RETURN"), cancellable = true)
	private void profps$reach(CallbackInfoReturnable<Double> cir) {
		ProFPSConfig cfg = ProFPSClient.config();
		if (cfg == null || !cfg.enabled || !cfg.reach) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if ((Object) this != client.player) return;
		double want = MathHelper.clamp(cfg.reachCm, 300, 600) / 100.0;
		if (want > cir.getReturnValueD()) cir.setReturnValue(want);
	}
}
