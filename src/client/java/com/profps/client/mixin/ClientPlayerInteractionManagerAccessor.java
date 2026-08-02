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

	// Vanilla resends a held-slot packet whenever the inventory's selected slot differs from this
	// remembered value. After we send our own slot packet we set this to match, so vanilla doesn't
	// fire a duplicate (same-slot) packet next tick — which anti-cheats flag as BadPacketsA.
	@Accessor("lastSelectedSlot")
	void profps$setLastSelectedSlot(int slot);
}
