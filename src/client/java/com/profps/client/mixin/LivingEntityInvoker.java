package com.profps.client.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the protected vanilla jump so Auto Jump Reset can perform a real jump. */
@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {
	@Invoker("jump")
	void profps$invokeJump();
}
