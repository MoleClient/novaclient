package com.profps.client.mixin;

import com.profps.client.donutsmp.BasicEspRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes Mob ESP targets into vanilla's entity-outline pass. */
@Mixin(MinecraftClient.class)
public abstract class EntityOutlineMixin {
	@Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
	private void profps$outlineEspTargets(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		// Only ever add outlines; returning false would suppress genuine glowing entities.
		if (BasicEspRenderer.shouldOutline(entity)) cir.setReturnValue(true);
	}
}
