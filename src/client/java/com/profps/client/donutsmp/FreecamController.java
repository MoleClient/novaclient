package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Detached flying camera; the body stays exactly where it was toggled on.
 *
 * <p>Mechanics are a faithful port of Xenon Client's freecam, which is what the
 * previous implementation was rebuilt against:
 * <ul>
 *   <li>The camera advances on the RENDER clock, not the tick clock: movement is
 *       integrated per frame from wall-clock delta time inside the camera-update
 *       hook, then the drawn transform interpolates the last frame step by
 *       tickProgress. That is what makes it glassy at any fps.</li>
 *   <li>Velocity eases toward the input direction with an exponential smoothing
 *       factor of {@code 1 - 0.001^dt}, and snaps dead the moment no movement key
 *       is held — no drift, no coast.</li>
 *   <li>The scroll wheel is a live speed trim (±0.25 per notch, total speed
 *       clamped to 0.1..50); releasing all keys also drops the trim back to the
 *       configured base speed.</li>
 *   <li>The body is left standing: its input is zeroed at the KeyboardInput
 *       layer, its yaw/pitch/head/body rotation is re-pinned every tick to the
 *       values saved at activation, and sneak/sprint flags are stripped so the
 *       pose cannot change under the camera.</li>
 *   <li>Chunk culling is disabled while flying (the camera leaves the body's
 *       frustum) and the camera reports third-person so your own body renders.
 *       No sound-listener games, no body repositioning — the two things that
 *       made the old version glitch.</li>
 * </ul>
 */
public final class FreecamController {
	// Tuning lifted from the reference client, kept verbatim so it flies identically.
	private static final float MIN_TOTAL_SPEED = 0.1f;
	private static final float MAX_CONFIG_SPEED = 10.0f;
	private static final float SCROLL_STEP = 0.25f;
	private static final float MAX_TOTAL_SPEED = 50.0f;
	private static final double VELOCITY_SCALE = 5.0;
	private static final float LOOK_SENSITIVITY = 0.5f;
	/** Entities within this squared distance of the camera are forced to render (160m). */
	public static final double FORCED_RENDER_DISTANCE_SQ = 25_600.0;

	private static FreecamController instance;

	private final ProFPSConfig config;

	private boolean active;

	// Camera transform. previous/current pairs exist for the per-frame
	// interpolation the camera hook reads.
	private double x, y, z;
	private double prevX, prevY, prevZ;
	private double velX, velY, velZ;
	private float yaw, pitch;
	private float prevYaw, prevPitch;

	private float currentSpeed;
	private float scrollBoost;
	private long lastFrameMs;

	// World state to put back when the camera comes home.
	private float savedYaw, savedPitch;
	private Perspective savedPerspective;
	private boolean savedChunkCulling;

	public FreecamController(ProFPSConfig config) {
		this.config = config;
		// Camera state must never survive a restart: booting into a frozen body
		// with the camera somewhere else would read as a broken game.
		config.donutFreecam = false;
		instance = this;
	}

	public static boolean isActive() {
		return instance != null && instance.active;
	}

	/** Toggle hook for the module keybind path. */
	public void toggle() {
		config.donutFreecam = !config.donutFreecam;
	}

	public void tick(MinecraftClient client) {
		boolean worldReady = client.player != null && client.world != null;
		if (config.donutFreecam && !worldReady) {
			// Toggled on from a menu (or the world unloaded under us): refuse the
			// toggle rather than activating into nothing.
			config.donutFreecam = false;
		}
		boolean wanted = config.enabled && config.donutFreecam && worldReady;
		if (wanted && !active) {
			activate(client);
		} else if (!wanted && active) {
			deactivate(client);
		}
		if (active) {
			// Re-pin every tick. Movement packets keep reporting the rotation the
			// player had when the camera detached, so nothing the mouse does while
			// flying is visible server-side — and the rendered body doesn't twitch.
			ClientPlayerEntity player = client.player;
			player.setYaw(savedYaw);
			player.setPitch(savedPitch);
			player.setHeadYaw(savedYaw);
			player.setBodyYaw(savedYaw);
		}
	}

	private void activate(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		savedPerspective = client.options.getPerspective();
		savedChunkCulling = client.chunkCullingEnabled;
		// The camera routinely looks back at terrain the body's frustum culled.
		client.chunkCullingEnabled = false;
		savedYaw = player.getYaw();
		savedPitch = player.getPitch();
		yaw = savedYaw;
		pitch = savedPitch;
		Vec3d eye = player.getCameraPosVec(1.0f);
		x = prevX = eye.x;
		y = prevY = eye.y;
		z = prevZ = eye.z;
		prevYaw = yaw;
		prevPitch = pitch;
		lastFrameMs = System.currentTimeMillis();
		velX = velY = velZ = 0.0;
		currentSpeed = configuredSpeed();
		scrollBoost = 0.0f;
		// Kill horizontal momentum so the body doesn't slide off a ledge the
		// moment its input goes dead; gravity keeps working on purpose.
		player.setVelocity(0.0, player.getVelocity().y, 0.0);
		active = true;
	}

	private void deactivate(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player != null) {
			player.setYaw(savedYaw);
			player.setPitch(savedPitch);
			player.setHeadYaw(savedYaw);
			player.setBodyYaw(savedYaw);
		}
		client.options.setPerspective(savedPerspective != null ? savedPerspective : Perspective.FIRST_PERSON);
		client.chunkCullingEnabled = savedChunkCulling;
		velX = velY = velZ = 0.0;
		currentSpeed = configuredSpeed();
		scrollBoost = 0.0f;
		active = false;
	}

	// ── Per-frame camera drive (called from the Camera.update hook) ───────────

	public static void frame() {
		if (instance != null && instance.active) instance.updateCameraMovement();
	}

	private void updateCameraMovement() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;

		prevX = x;
		prevY = y;
		prevZ = z;
		prevYaw = yaw;
		prevPitch = pitch;

		long now = System.currentTimeMillis();
		float dt = (now - lastFrameMs) / 1000.0f;
		lastFrameMs = now;
		// A hitch must not teleport the camera; a sub-millisecond frame gets the
		// nominal 60fps step instead of a degenerate zero.
		dt = Math.min(dt, 0.1f);
		if (dt < 0.001f) dt = 0.016f;

		// Direction comes from yaw alone — pitch never bleeds into travel, so
		// looking down doesn't dive the camera. Vertical is jump/sneak only.
		float yawRad = (float) Math.toRadians(yaw);
		double forwardX = -Math.sin(yawRad);
		double forwardZ = Math.cos(yawRad);
		double rightX = -Math.cos(yawRad);
		double rightZ = -Math.sin(yawRad);

		double inX = 0.0, inY = 0.0, inZ = 0.0;
		double move = (currentSpeed + scrollBoost) * 2.0;
		if (config.donutFreecamTurbo) {
			move *= 2.5;
		}
		if (client.options != null && client.options.sprintKey.isPressed()) {
			move *= 2.0;
		}
		boolean moving = false;
		if (client.options.forwardKey.isPressed()) {
			inX += forwardX * move;
			inZ += forwardZ * move;
			moving = true;
		}
		if (client.options.backKey.isPressed()) {
			inX -= forwardX * move;
			inZ -= forwardZ * move;
			moving = true;
		}
		if (client.options.rightKey.isPressed()) {
			inX += rightX * move;
			inZ += rightZ * move;
			moving = true;
		}
		if (client.options.leftKey.isPressed()) {
			inX -= rightX * move;
			inZ -= rightZ * move;
			moving = true;
		}
		if (client.options.jumpKey.isPressed()) {
			inY += move;
			moving = true;
		}
		if (client.options.sneakKey.isPressed()) {
			inY -= move;
			moving = true;
		}
		if (!moving) {
			// Hard stop, and the scroll trim resets so the next take-off starts
			// from the configured base speed.
			scrollBoost = 0.0f;
			velX = velY = velZ = 0.0;
			return;
		}

		// Ease velocity toward the input direction. The 0.001 base means ~99.9%
		// of the gap closes per second — snappy but never stepped.
		double t = 1.0 - Math.pow(0.001, dt);
		velX = MathHelper.lerp(t, velX, inX * VELOCITY_SCALE);
		velY = MathHelper.lerp(t, velY, inY * VELOCITY_SCALE);
		velZ = MathHelper.lerp(t, velZ, inZ * VELOCITY_SCALE);

		x += velX * dt;
		y += velY * dt;
		z += velZ * dt;
	}

	// ── Input redirects (called from mixins) ──────────────────────────────────

	/** Raw cursor deltas; vanilla's 0.15 scale and the freecam sensitivity are applied here. */
	public static void onMouseLook(double cursorDeltaX, double cursorDeltaY) {
		if (instance == null || !instance.active) return;
		instance.yaw = MathHelper.wrapDegrees(instance.yaw + (float) (cursorDeltaX * 0.15 * LOOK_SENSITIVITY));
		instance.pitch = MathHelper.clamp(instance.pitch + (float) (cursorDeltaY * 0.15 * LOOK_SENSITIVITY), -90.0f, 90.0f);
	}

	/** Scroll wheel trims flight speed instead of switching hotbar slots. */
	public static void onScroll(double amount) {
		if (instance == null || !instance.active) return;
		FreecamController self = instance;
		self.scrollBoost += (float) amount * SCROLL_STEP;
		// The trim may slow below base but never below the global floor, and the
		// combined speed is capped well above the config slider's own ceiling.
		self.scrollBoost = Math.max(self.scrollBoost, -self.currentSpeed + MIN_TOTAL_SPEED);
		if (self.currentSpeed + self.scrollBoost > MAX_TOTAL_SPEED) {
			self.scrollBoost = MAX_TOTAL_SPEED - self.currentSpeed;
		}
	}

	// ── Camera transform readback (called from the Camera.update hook) ────────

	public static double cameraX(float tickProgress) {
		return MathHelper.lerp(tickProgress, instance.prevX, instance.x);
	}

	public static double cameraY(float tickProgress) {
		return MathHelper.lerp(tickProgress, instance.prevY, instance.y);
	}

	public static double cameraZ(float tickProgress) {
		return MathHelper.lerp(tickProgress, instance.prevZ, instance.z);
	}

	public static float cameraYaw(float tickProgress) {
		return MathHelper.lerp(tickProgress, instance.prevYaw, instance.yaw);
	}

	public static float cameraPitch(float tickProgress) {
		return MathHelper.lerp(tickProgress, instance.prevPitch, instance.pitch);
	}

	private float configuredSpeed() {
		return MathHelper.clamp(config.donutFreecamSpeed / 10.0f, MIN_TOTAL_SPEED, MAX_CONFIG_SPEED);
	}
}
