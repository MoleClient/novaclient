package com.profps.client.classics;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * Phases the player through geometry by clearing collision and driving velocity directly.
 *
 * <p>Built for singleplayer and servers you run. Collision is resolved by the server as well
 * as the client, so a server that does not permit this simply rubber-bands you back; nothing
 * here tries to change that.
 *
 * <p>Two details do most of the work for how it feels. Velocity eases toward the target
 * instead of snapping to it, so starting and stopping have weight rather than the rigid
 * on/off of {@link FlightController}. And releasing is deferred while the body is still
 * inside a block: dropping collision back on inside geometry is what traps you in a wall, so
 * the module keeps phasing until there is somewhere safe to stand.
 */
public final class NoClipController {
	/** Blocks per tick at speed 10, before the sprint multiplier. */
	private static final double SPEED_PER_LEVEL = 0.088D;
	private static final double SPRINT_MULTIPLIER = 1.9D;
	/** Fraction of the remaining velocity error closed per tick; a short ramp, not a snap. */
	private static final double EASE = 0.34D;
	/** Half-extents of the free space a release needs, slightly inside the player box. */
	private static final double RELEASE_MARGIN = 0.02D;

	private final ProFPSConfig config;

	private boolean engaged;
	private boolean releasePending;
	private Vec3d velocity = Vec3d.ZERO;
	private int noticeTicks;

	public NoClipController(ProFPSConfig config) {
		this.config = config;
	}

	/** True while collision is off, so other movement modules can stand down. */
	public boolean isActive() {
		return engaged;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || client.world == null) {
			engaged = false;
			releasePending = false;
			velocity = Vec3d.ZERO;
			return;
		}

		boolean wanted = config.enabled && config.noClipEnabled
				&& player.isAlive() && !player.isSpectator()
				&& !player.hasVehicle() && !player.isGliding();

		if (!wanted) {
			// Never hand collision back while the body is inside a block; that is what leaves
			// you stuck in a wall. Keep phasing until the box is clear, then let go.
			if (engaged && insideGeometry(client, player)) {
				releasePending = true;
				if (noticeTicks++ % 40 == 0) {
					client.inGameHud.setOverlayMessage(Text.literal("NoClip ")
							.formatted(Formatting.AQUA, Formatting.BOLD)
							.append(Text.literal("• holding until you are clear of blocks")
									.formatted(Formatting.YELLOW)), false);
				}
				drive(client, player);
				return;
			}
			if (engaged) disengage(client, player);
			return;
		}

		releasePending = false;
		noticeTicks = 0;
		engage(client, player);
		drive(client, player);
	}

	private void engage(MinecraftClient client, ClientPlayerEntity player) {
		if (!engaged) {
			engaged = true;
			velocity = player.getVelocity();
		}
		player.noClip = true;
		// The integrated server keeps its own copy of the player, and its collision is what
		// produces suffocation damage. Mirroring the flag there is only possible, and only
		// appropriate, on a world you are hosting yourself.
		mirrorToIntegratedServer(client, true);
	}

	private void disengage(MinecraftClient client, ClientPlayerEntity player) {
		engaged = false;
		releasePending = false;
		noticeTicks = 0;
		velocity = Vec3d.ZERO;
		player.noClip = false;
		mirrorToIntegratedServer(client, false);
	}

	/** Eases velocity toward the input direction and cancels gravity by owning velocity outright. */
	private void drive(MinecraftClient client, ClientPlayerEntity player) {
		double speed = SPEED_PER_LEVEL * MathHelper.clamp(config.noClipSpeed, 1, 10);
		if (client.options.sprintKey.isPressed()) speed *= SPRINT_MULTIPLIER;

		Vec2f input = player.input.getMovementInput();
		double yawRadians = Math.toRadians(player.getYaw());
		double sin = Math.sin(yawRadians);
		double cos = Math.cos(yawRadians);
		double moveX = input.x * cos - input.y * sin;
		double moveZ = input.y * cos + input.x * sin;
		double length = Math.sqrt(moveX * moveX + moveZ * moveZ);
		if (length > 1.0E-4D) {
			moveX /= length;
			moveZ /= length;
		}

		double vertical = 0.0D;
		if (client.options.jumpKey.isPressed()) vertical += 1.0D;
		if (client.options.sneakKey.isPressed()) vertical -= 1.0D;

		Vec3d target = new Vec3d(moveX * speed, vertical * speed, moveZ * speed);
		velocity = velocity.add(target.subtract(velocity).multiply(EASE));
		if (velocity.lengthSquared() < 1.0E-8D) velocity = Vec3d.ZERO;

		player.setVelocity(velocity);
		player.fallDistance = 0.0F;
		player.setOnGround(false);
		player.verticalCollision = false;
		player.horizontalCollision = false;
	}

	/** Whether the player box currently overlaps anything solid. */
	private boolean insideGeometry(MinecraftClient client, ClientPlayerEntity player) {
		return !client.world.isSpaceEmpty(player, player.getBoundingBox().contract(RELEASE_MARGIN));
	}

	/**
	 * Sets the same flag on the integrated server's copy of the player. Without it the server
	 * still resolves the body as buried and applies suffocation damage. Scheduled onto the
	 * server thread rather than written across it.
	 */
	private void mirrorToIntegratedServer(MinecraftClient client, boolean noClip) {
		if (client.getServer() == null || client.player == null) return;
		java.util.UUID uuid = client.player.getUuid();
		client.getServer().execute(() -> {
			ServerPlayerEntity server = client.getServer().getPlayerManager().getPlayer(uuid);
			if (server != null) server.noClip = noClip;
		});
	}
}
