package com.profps.client.mixin;

import com.profps.client.ProFPSClient;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Passes the weapon attack-range gate for the local player while Reach is on.
 * {@link AttackRangeComponent} caps attack reach at the item's max range independent of
 * the interaction-range attribute raised by {@link ReachRangeMixin}.
 */
@Mixin(AttackRangeComponent.class)
public abstract class ReachAttackMixin {

	@Inject(method = "isWithinRange(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/util/math/Vec3d;)Z",
			at = @At("HEAD"), cancellable = true)
	private void profps$reach(LivingEntity entity, Vec3d pos, CallbackInfoReturnable<Boolean> cir) {
		ProFPSConfig cfg = ProFPSClient.config();
		MinecraftClient client = MinecraftClient.getInstance();
		if (cfg != null && cfg.enabled && cfg.reach && entity == client.player) {
			cir.setReturnValue(true);
		}
	}
}
