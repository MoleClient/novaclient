package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class FreecamController {
	private static FreecamController instance;

	private final ProFPSConfig config;
	private boolean active;
	private Vec3d anchor = Vec3d.ZERO;
	private Vec3d previousPosition = Vec3d.ZERO;
	private Vec3d position = Vec3d.ZERO;
	private float yaw;
	private float pitch;

	public FreecamController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	public void toggle() {
		config.donutFreecam = !config.donutFreecam;
		config.save();
		if (!config.donutFreecam) {
			active = false;
		}
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.donutFreecam || client.world == null || client.player == null) {
			active = false;
			return;
		}
		if (!active) {
			active = true;
			anchor = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
			position = new Vec3d(client.player.getX(), client.player.getEyeY(), client.player.getZ());
			previousPosition = position;
			yaw = client.player.getYaw();
			pitch = client.player.getPitch();
		}

		if (TunnelController.isControlling()) {
			// The tunnel bot is walking and mining the body; don't fight it.
			// Just keep the loaded-area anchor on the body so the camera clamp
			// follows it as it tunnels forward.
			anchor = client.player.getEntityPos();
		} else {
			// Pin the body dead-still: zero its velocity, snap it to the anchor,
			// and reset render interpolation so neither the body nor the chunks
			// streamed around it jitter — including while a GUI is open. (Input
			// is frozen via InputMixin, so this no longer fights player input.)
			client.player.setVelocity(Vec3d.ZERO);
			client.player.setPos(anchor.x, anchor.y, anchor.z);
			client.player.resetPosition();
		}

		// Don't move the camera while a screen (GUI) is open.
		if (client.currentScreen != null) return;

		double forward = 0.0;
		double strafe  = 0.0;
		double vertical = 0.0;
		if (client.options.forwardKey.isPressed()) forward  += 1.0;
		if (client.options.backKey.isPressed())    forward  -= 1.0;
		if (client.options.rightKey.isPressed())   strafe   += 1.0;
		if (client.options.leftKey.isPressed())    strafe   -= 1.0;
		if (client.options.jumpKey.isPressed())    vertical += 1.0;
		if (client.options.sneakKey.isPressed())   vertical -= 1.0;

		previousPosition = position;
		double len = Math.sqrt(forward * forward + strafe * strafe + vertical * vertical);
		if (len < 1.0E-5) return;

		forward  /= len;
		strafe   /= len;
		vertical /= len;

		double yawRad = Math.toRadians(yaw);
		Vec3d fwd = new Vec3d(-Math.sin(yawRad), 0.0,  Math.cos(yawRad));
		Vec3d rgt = new Vec3d(-Math.cos(yawRad), 0.0, -Math.sin(yawRad));
		// Speed meter (1..10): base glides from a slow, precise 0.12 up to a fast
		// 1.20 per tick; level 5 reproduces the classic 0.58/1.45 feel. Sprint adds
		// the usual ~2.5x boost on top.
		double level = MathHelper.clamp(config.donutFreecamSpeed, 1, 10);
		double base = 0.12 + (level - 1) * 0.12;
		double speed = client.options.sprintKey.isPressed() ? base * 2.5 : base;
		Vec3d delta = fwd.multiply(forward * speed)
				.add(rgt.multiply(strafe * speed))
				.add(0.0, vertical * speed, 0.0);

		position = clampToLoadedArea(client, position.add(delta));
	}

	public static boolean isActive() {
		return instance != null && instance.active;
	}

	public static Vec3d cameraPosition() {
		return instance == null ? Vec3d.ZERO : instance.position;
	}

	public static Vec3d cameraPosition(float tickProgress) {
		if (instance == null) return Vec3d.ZERO;
		float t = smooth(MathHelper.clamp(tickProgress, 0.0F, 1.0F));
		return instance.previousPosition.lerp(instance.position, t);
	}

	public static float cameraYaw() {
		return instance == null ? 0.0F : instance.yaw;
	}

	public static float cameraPitch() {
		return instance == null ? 0.0F : instance.pitch;
	}

	public static void handleMouse(MinecraftClient client, double deltaX, double deltaY) {
		if (instance == null || !instance.active || client.options == null) return;
		double sensitivity = client.options.getMouseSensitivity().getValue();
		double scaleBase = sensitivity * 0.6 + 0.2;
		double scale = scaleBase * scaleBase * scaleBase * 1.2;
		double x = deltaX * scale;
		double y = deltaY * scale;
		if (client.options.getInvertMouseX().getValue()) x = -x;
		if (client.options.getInvertMouseY().getValue()) y = -y;
		instance.yaw   = (float) MathHelper.wrapDegrees(instance.yaw + x);
		instance.pitch = MathHelper.clamp((float) (instance.pitch + y), -90.0F, 90.0F);
	}

	/**
	 * Keep the camera inside the WELL-loaded area. Two layers:
	 *
	 * <ol>
	 *   <li>A smooth radius cap around the anchored player at (view distance
	 *       - 2) chunks. The server only streams chunks around the body, so
	 *       past this line there is nothing but void to render — flying there
	 *       is what made chunks pop in/out at the edge.</li>
	 *   <li>A per-axis loaded-chunk check that SLIDES along the boundary
	 *       instead of freezing both axes — the old all-or-nothing clamp is
	 *       what felt like getting stuck mid-air.</li>
	 * </ol>
	 */
	private Vec3d clampToLoadedArea(MinecraftClient client, Vec3d next) {
		ClientWorld world = client.world;
		int viewChunks = Math.max(2, (client.options == null ? 8 : client.options.getViewDistance().getValue()) - 2);
		double maxRange = viewChunks * 16.0;
		double x = MathHelper.clamp(next.x, anchor.x - maxRange, anchor.x + maxRange);
		double z = MathHelper.clamp(next.z, anchor.z - maxRange, anchor.z + maxRange);

		if (!isChunkAreaLoaded(world, MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4, 1)) {
			// Try each axis on its own so the camera slides along the edge.
			if (isChunkAreaLoaded(world, MathHelper.floor(x) >> 4, MathHelper.floor(position.z) >> 4, 1)) {
				z = position.z;
			} else if (isChunkAreaLoaded(world, MathHelper.floor(position.x) >> 4, MathHelper.floor(z) >> 4, 1)) {
				x = position.x;
			} else {
				x = position.x;
				z = position.z;
			}
		}

		double minY = world.getBottomY() + 1.0;
		double maxY = world.getBottomY() + world.getHeight() - 2.0;
		return new Vec3d(x, MathHelper.clamp(next.y, minY, maxY), z);
	}

	/** Returns true only if the chunk at (cx,cz) AND all chunks within radius r are loaded. */
	private boolean isChunkAreaLoaded(ClientWorld world, int cx, int cz, int r) {
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (!world.isChunkLoaded(cx + dx, cz + dz)) return false;
			}
		}
		return true;
	}

	private static float smooth(float value) {
		return value * value * (3.0F - 2.0F * value);
	}
}
