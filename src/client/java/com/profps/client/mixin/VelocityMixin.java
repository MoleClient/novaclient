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
 * Scales incoming knockback from {@link EntityVelocityUpdateS2CPacket}.
 * The redirect runs after {@code NetworkThreadUtils.forceMainThread}, so main-thread state is safe to read.
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
