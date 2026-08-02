package com.profps.client.mixin;

import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the sign screen's own text buffer.
 *
 * <p>Vanilla sends the sign from {@code removed()}, building an UpdateSignC2SPacket out of
 * this exact array. Writing the lines here and then calling {@code close()} means the text
 * leaves on vanilla's own path — no hand-rolled packet, no ordering to get wrong.</p>
 */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
	@Accessor("messages")
	String[] profps$getMessages();
}
