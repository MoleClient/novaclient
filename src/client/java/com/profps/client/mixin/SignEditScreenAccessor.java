package com.profps.client.mixin;

import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the sign screen's text buffer, which vanilla turns into an UpdateSignC2SPacket
 * from {@code removed()}.
 */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
	@Accessor("messages")
	String[] profps$getMessages();
}
