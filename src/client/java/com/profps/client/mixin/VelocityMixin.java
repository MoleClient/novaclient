package com.profps.client.mixin;

import com.profps.client.assists.VelocityController;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;

/**
 * Velocity (anti-knockback). When you take a hit, the server resolves the knockback and sends a
 * {@link EntityVelocityUpdateS2CPacket} carrying your new velocity; the client applies it via
 * {@code Entity.setVelocityClient}. We redirect that ONE call for the local player and scale the
 * knockback by your Horizontal / Vertical percentages (0% = fully negated), on a configurable
 * chance of hits. Altering server-authored knockback is not human input and can
 * be directly identified by multiplayer simulation checks.
 *
 * <p>The redirect runs after {@code NetworkThreadUtils.forceMainThread}, i.e. on the main thread,
 * so reading the player / config here is safe.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class VelocityMixin {

	@Redirect(method = "onEntityVelocityUpdate",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/entity/Entity;setVelocityClient(Lnet/minecraft/util/math/Vec3d;)V"))
	private void profps$velocity(Entity entity, Vec3d velocity) {
		entity.setVelocityClient(VelocityController.transformIncoming(entity, velocity));
	}
}
