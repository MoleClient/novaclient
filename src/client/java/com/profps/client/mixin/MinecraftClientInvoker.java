package com.profps.client.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftClient.class)
public interface MinecraftClientInvoker {
	@Invoker("doAttack")
	boolean invokeDoAttack();

	@Invoker("doItemUse")
	void invokeDoItemUse();

	@Accessor("itemUseCooldown")
	int profps$getItemUseCooldown();

	@Accessor("itemUseCooldown")
	void profps$setItemUseCooldown(int cooldown);

	@Accessor("attackCooldown")
	int profps$getAttackCooldown();

	@Accessor("attackCooldown")
	void profps$setAttackCooldown(int cooldown);
}
