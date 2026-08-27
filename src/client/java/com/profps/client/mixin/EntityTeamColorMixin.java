package com.profps.client.mixin;

import com.profps.client.donutsmp.BasicEspRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies the outline tint for Mob ESP targets, leaving real team colours alone. */
@Mixin(Entity.class)
public abstract class EntityTeamColorMixin {
	@Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
	private void profps$espOutlineColor(CallbackInfoReturnable<Integer> cir) {
		Entity self = (Entity) (Object) this;
		if (BasicEspRenderer.shouldOutline(self)) {
			cir.setReturnValue(BasicEspRenderer.outlineColor(self));
		}
	}
}
