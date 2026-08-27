package com.profps.client.aim;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Decouples the rendered camera from the body rotation while a combat module owns the aim.
 *
 * <p>Rotations are not spoofed into outbound packets. All of this runs before physics: the
 * entity's real yaw and pitch are the aim rotation and movement is recomputed from it, so only
 * the rendered rotation differs.</p>
 */
public final class SilentAimController {
	/** Degrees per tick the body may travel while returning to the view. */
	private static final float HANDBACK_DEGREES_PER_TICK = 11.0F;
	private static final float HANDBACK_DONE_DEGREES = 0.6F;
	// Silent aim is held by continuous request; silence must always release the camera.
	private static final long ENGAGE_TIMEOUT_NANOS = 200_000_000L;

	private static final SilentAimController INSTANCE = new SilentAimController();

	// Ceiling on the walk back so a body that cannot reach the view still returns the camera.
	private static final long HANDBACK_TIMEOUT_NANOS = 1_000_000_000L;

	private final MouseGcd mouse = new MouseGcd();
	private boolean engaged;
	private boolean handingBack;
	private long lastEngageNanos;
	private long handbackStartNanos;
	private float viewYaw;
	private float viewPitch;

	private SilentAimController() {
	}

	public static SilentAimController instance() {
		return INSTANCE;
	}

	/** True while the camera is decoupled from the body. Both states expire on their own clock. */
	public static boolean isActive() {
		SilentAimController self = INSTANCE;
		long now = System.nanoTime();
		if (self.engaged && now - self.lastEngageNanos <= ENGAGE_TIMEOUT_NANOS) return true;
		return self.handingBack && now - self.handbackStartNanos <= HANDBACK_TIMEOUT_NANOS;
	}

	public static float viewYaw() {
		return INSTANCE.viewYaw;
	}

	public static float viewPitch() {
		return INSTANCE.viewPitch;
	}

	/** Called every tick a module wants silent aim; the first call snapshots the current rotation as the view. */
	public void engage(ClientPlayerEntity player) {
		if (player == null) return;
		if (!engaged && !handingBack) {
			viewYaw = player.getYaw();
			viewPitch = player.getPitch();
		}
		engaged = true;
		handingBack = false;
		lastEngageNanos = System.nanoTime();
	}

	/** Called when the module stops aiming; starts the walk back to the view. */
	public void release() {
		if (!engaged) return;
		engaged = false;
		handingBack = true;
		handbackStartNanos = System.nanoTime();
	}

	/** Drops the decoupling immediately. */
	public void reset() {
		engaged = false;
		handingBack = false;
		handbackStartNanos = 0L;
	}

	/** Steers the body back under the view after a module releases it. Runs from the render loop. */
	public void frame(MinecraftClient client, float dtTicks) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isAlive()) {
			reset();
			return;
		}
		if (engaged && System.nanoTime() - lastEngageNanos > ENGAGE_TIMEOUT_NANOS) release();
		if (!handingBack) return;
		if (System.nanoTime() - handbackStartNanos > HANDBACK_TIMEOUT_NANOS) {
			reset();
			return;
		}
		float yawError = MathHelper.wrapDegrees(viewYaw - player.getYaw());
		float pitchError = MathHelper.wrapDegrees(viewPitch - player.getPitch());
		if (Math.abs(yawError) < HANDBACK_DONE_DEGREES && Math.abs(pitchError) < HANDBACK_DONE_DEGREES) {
			player.setYaw(viewYaw);
			player.setPitch(viewPitch);
			player.headYaw = player.getYaw();
			handingBack = false;
			return;
		}
		float cap = HANDBACK_DEGREES_PER_TICK * MathHelper.clamp(dtTicks, 0.05F, 3.0F);
		float yawStep = mouse.yaw(MathHelper.clamp(yawError, -cap, cap));
		float pitchStep = mouse.pitch(MathHelper.clamp(pitchError, -cap * 0.8F, cap * 0.8F));
		player.setYaw(player.getYaw() + yawStep);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0F, 90.0F));
		player.headYaw = player.getYaw();
	}

	/** Applies raw mouse motion to the view instead of the body, using vanilla's sensitivity curve. */
	public void handleMouse(MinecraftClient client, double deltaX, double deltaY) {
		if (client.options == null) return;
		double sensitivity = client.options.getMouseSensitivity().getValue();
		double scaleBase = sensitivity * 0.6D + 0.2D;
		double scale = scaleBase * scaleBase * scaleBase * 8.0D;
		double x = deltaX * scale;
		double y = deltaY * scale;
		if (client.options.getInvertMouseX().getValue()) x = -x;
		if (client.options.getInvertMouseY().getValue()) y = -y;
		viewYaw = (float) MathHelper.wrapDegrees(viewYaw + x);
		viewPitch = MathHelper.clamp((float) (viewPitch + y), -90.0F, 90.0F);
	}

	/**
	 * Re-picks the movement keys so travel follows the view while the body points elsewhere.
	 *
	 * @return {@code null} when body and view agree closely enough to leave the input alone
	 */
	public static PlayerInput movementOverride(PlayerInput current) {
		SilentAimController self = INSTANCE;
		if (!isActive() || current == null) return null;
		if (!current.forward() && !current.backward() && !current.left() && !current.right()) return null;
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) return null;

		float offset = MathHelper.wrapDegrees(self.viewYaw - player.getYaw());
		// Inside one key-step the body and view resolve to the same combination.
		if (Math.abs(offset) < 22.5F) return null;

		// The requested world direction, expressed against the view.
		double forward = (current.forward() == current.backward()) ? 0.0D : (current.forward() ? 1.0D : -1.0D);
		double left = (current.left() == current.right()) ? 0.0D : (current.left() ? 1.0D : -1.0D);
		double viewYawRadians = Math.toRadians(self.viewYaw);
		Vec3d viewForward = new Vec3d(-Math.sin(viewYawRadians), 0.0D, Math.cos(viewYawRadians));
		Vec3d viewLeft = new Vec3d(Math.cos(viewYawRadians), 0.0D, Math.sin(viewYawRadians));
		Vec3d desired = viewForward.multiply(forward).add(viewLeft.multiply(left));
		if (desired.lengthSquared() < 1.0E-6D) return null;
		desired = desired.normalize();

		// Only the eight real key combinations are candidates; a synthesised vector is unreachable by keyboard.
		double bodyYawRadians = Math.toRadians(player.getYaw());
		Vec3d bodyForward = new Vec3d(-Math.sin(bodyYawRadians), 0.0D, Math.cos(bodyYawRadians));
		Vec3d bodyLeft = new Vec3d(Math.cos(bodyYawRadians), 0.0D, Math.sin(bodyYawRadians));
		double bestDot = -Double.MAX_VALUE;
		int bestForward = 0;
		int bestLeft = 0;
		for (int f = -1; f <= 1; f++) {
			for (int l = -1; l <= 1; l++) {
				if (f == 0 && l == 0) continue;
				Vec3d candidate = bodyForward.multiply(f).add(bodyLeft.multiply(l)).normalize();
				double dot = candidate.dotProduct(desired);
				if (dot > bestDot) {
					bestDot = dot;
					bestForward = f;
					bestLeft = l;
				}
			}
		}
		return new PlayerInput(bestForward > 0, bestForward < 0, bestLeft > 0, bestLeft < 0,
				current.jump(), current.sneak(), current.sprint());
	}
}
