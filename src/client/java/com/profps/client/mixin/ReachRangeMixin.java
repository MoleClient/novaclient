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
 * Reach — part 1 of 2: extends the entity-interaction range the client raycasts with, so the
 * crosshair (and thus an attack/use target) can lock onto players a little further out.
 *
 * <p>{@code ClientPlayerEntity.getCrosshairTarget} raycasts entities out to
 * {@link PlayerEntity#getEntityInteractionRange()}. We raise that for the LOCAL player only when
 * the Reach module is on. The attack-range gate in {@code MinecraftClient.doAttack} is governed
 * separately by the weapon's AttackRangeComponent — {@link ReachAttackMixin} handles that half.
 *
 * <p>Default reach is 3.0 (vanilla). Extending that gate has no legitimate
 * survival-server equivalent and can be directly flagged in multiplayer.
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
