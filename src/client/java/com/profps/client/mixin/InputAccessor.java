package com.profps.client.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Setters for the movement fields declared on {@link Input}. They can't be
 * {@code @Shadow}-ed from the {@code KeyboardInput} mixin (a field shadow has to
 * live in the class that declares it), so the override goes through these
 * accessors instead.
 */
@Mixin(Input.class)
public interface InputAccessor {
	@Accessor("playerInput")
	void profps$setPlayerInput(PlayerInput playerInput);

	@Accessor("playerInput")
	PlayerInput profps$getPlayerInput();

	@Accessor("movementVector")
	void profps$setMovementVector(Vec2f movementVector);
}
