package com.profps.client.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
	@Accessor("blockBreakingCooldown")
	int profps$getBlockBreakingCooldown();

	@Accessor("blockBreakingCooldown")
	void profps$setBlockBreakingCooldown(int cooldown);

	// Must be synced after sending a manual slot packet, or vanilla sends a duplicate next tick.
	@Accessor("lastSelectedSlot")
	void profps$setLastSelectedSlot(int slot);
}
