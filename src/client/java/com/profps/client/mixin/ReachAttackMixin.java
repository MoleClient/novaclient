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
 * Reach — part 2 of 2: passes the attack-range gate. In 1.21 the weapon carries an
 * {@link AttackRangeComponent} whose {@code isWithinRange} (called from {@code doAttack}) caps
 * attack reach at the item's max range (~3.0) independent of the player's interaction-range
 * attribute. So even after {@link ReachRangeMixin} lets the crosshair lock a further target,
 * {@code doAttack} would reject the swing without this.
 *
 * <p>We only force it true for the local player while Reach is on. The crosshair
 * raycast remains capped at the configured reach ({@link ReachRangeMixin}).
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
