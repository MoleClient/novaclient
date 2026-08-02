package com.profps.client.aim;

import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Auto Aim — a projectile aim assist for the bow, crossbow and (server) fireball.
 *
 * <p>While you hold one of those and the module is on, it picks the player you're
 * most likely aiming at (nearest to your look ray, inside a cone), shows
 * "Selected: name" on the action bar so you know who it locked when people are
 * bunched up, and <b>gently</b> follows them — a low-strength, humanized pull you
 * can break out of just by turning away (turn past the cone and it lets go).
 *
 * <p>A direct bow aim is usually mis-led and mis-dropped. This computes a candidate
 * using the real projectile origin, gravity, drag and shooter inheritance, then
 * accepts it only when a swept simulation intersects the predicted moving hitbox
 * before terrain. A render-frame acceleration-limited camera spring follows that
 * tick-stable solution; firing never causes a last-instant snap or extra look packet.
 */
public final class AutoAimController {
	private static AutoAimController instance;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private UUID targetUuid;
	private String targetName = "";
	private double hx, hy, hz;          // randomized hitbox fractions (off-centre)
	private long nextPointNanos;
	private float solvedYaw, solvedPitch; // ballistic aim for the current target
	private boolean haveSolution;
	private long lastFrameNanos;
	private long lastAssistedFrameNanos;
	private long lastVisibleTargetNanos;
	private UUID pendingTargetUuid;
	private long pendingTargetSinceNanos;
	private UUID motionTargetUuid;
	private Vec3d lastTargetPosition = Vec3d.ZERO;
	private Vec3d filteredTargetVelocity = Vec3d.ZERO;
	private int lastTargetAge = -1;

	// Time-based camera spring. Independent per-frame noise was FPS-dependent:
	// 240 FPS received four times as many random kicks as 60 FPS and looked both
	// shaky and artificial when the server sampled the final tick rotation.
	private UUID rotationTargetUuid;
	private float yawVelocity;
	private float pitchVelocity;

	// Post-shot view recovery so the camera doesn't stay frozen at the ballistic up-tilt.
	private float preDrawPitch;          // pitch when you started drawing — where we return to
	private boolean wasUsing;
	private long recoverStartNanos, recoverUntilNanos;
	private float recoverFromPitch, recoverToPitch;

	public AutoAimController(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	public static AutoAimController get() {
		return instance;
	}

	// ── Tick: select target, predict, advertise ────────────────────────────────

	public void tick(MinecraftClient client) {
		if (!active(client)) {
			targetUuid = null;
			pendingTargetUuid = null;
			haveSolution = false;
			resetTracking();
			return;
		}
		ClientPlayerEntity player = client.player;
		PlayerEntity target = acquire(client, player);
		if (target == null) {
			haveSolution = false;
			if (System.nanoTime() - lastVisibleTargetNanos > 180_000_000L) targetUuid = null;
			return;
		}

		long now = System.nanoTime();
		lastVisibleTargetNanos = now;
		if (targetUuid != null && !target.getUuid().equals(targetUuid)) {
			// A brief candidate dwell prevents two nearby players from making the assist flick
			// between targets as their centre-angle scores trade places.
			if (!target.getUuid().equals(pendingTargetUuid)) {
				pendingTargetUuid = target.getUuid();
				pendingTargetSinceNanos = now;
				haveSolution = false;
				return;
			}
			if (now - pendingTargetSinceNanos < 85_000_000L) {
				haveSolution = false;
				return;
			}
		}
		pendingTargetUuid = null;
		if (!target.getUuid().equals(targetUuid) || now >= nextPointNanos) {
			targetUuid = target.getUuid();
			targetName = target.getGameProfile().name();
			pickPoint();
			CombatModeProfile.ProjectileAim tuning = tuning();
			nextPointNanos = now + (tuning.pointHoldMinMs()
					+ (long) (rng.nextDouble() * Math.max(0, tuning.pointHoldMaxMs() - tuning.pointHoldMinMs())))
					* 1_000_000L;
		}

		updateTargetMotion(target);
		float[] aim = solve(player, target);
		if (aim != null) {
			solvedYaw = aim[0];
			solvedPitch = aim[1];
			haveSolution = true;
		} else haveSolution = false;

		// "Selected:" on the action bar, refreshed so it stays up while aiming.
		client.inGameHud.setOverlayMessage(Text.empty()
				.append(Text.literal("Selected: ").withColor(0x55E07A))
				.append(Text.literal(targetName).withColor(0xFFFFFF)), false);
	}

	// ── Frame: gentle, breakable follow ────────────────────────────────────────

	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dtSeconds = lastFrameNanos == 0L ? 1.0F / 60.0F
				: (float) MathHelper.clamp(
						(now - lastFrameNanos) / 1_000_000_000.0D,
						1.0D / 500.0D, 1.0D / 15.0D);
		lastFrameNanos = now;
		if (!active(client)) {
			wasUsing = false;
			recoverUntilNanos = 0L;
			resetRotationSpring();
			return;
		}

		ClientPlayerEntity player = client.player;
		boolean using = wantsAim(player, client);

		// Capture where you were looking the instant you start drawing, so after the shot we
		// can ease the view back there instead of leaving it tilted up at the launch angle.
		if (using && !wasUsing) {
			preDrawPitch = player.getPitch();
			recoverUntilNanos = 0L;
		}
		wasUsing = using;

		if (using && targetUuid != null && haveSolution) {
			PlayerEntity target = byUuid(client, targetUuid);
			if (target == null || !hasLineOfSight(client, player, target)) {
				haveSolution = false;
				resetRotationSpring();
				return;
			}
			if (!targetUuid.equals(rotationTargetUuid)) {
				rotationTargetUuid = targetUuid;
				yawVelocity = 0.0F;
				pitchVelocity = 0.0F;
			}
			float yawErr = MathHelper.wrapDegrees(solvedYaw - player.getYaw());
			float pitchErr = MathHelper.wrapDegrees(solvedPitch - player.getPitch());

			// A velocity/acceleration-limited spring is frame-rate independent and has
			// continuous momentum. It cannot jump directly to a new ballistic solution,
			// but still converges during the normal draw/loaded-crossbow aiming window.
			CombatModeProfile.ProjectileAim tuning = tuning();
			float strength = MathHelper.clamp(tuning.strengthPct(), 10, 90);
			float maxYawSpeed = 105.0F + strength * 1.75F;       // degrees / second
			float maxPitchSpeed = maxYawSpeed * 0.78F;
			float acceleration = 620.0F + strength * 7.0F;       // degrees / second²
			float gain = 6.0F + strength * 0.035F;
			float wantedYawVelocity = MathHelper.clamp(yawErr * gain, -maxYawSpeed, maxYawSpeed);
			float wantedPitchVelocity = MathHelper.clamp(
					pitchErr * gain, -maxPitchSpeed, maxPitchSpeed);
			yawVelocity = approach(yawVelocity, wantedYawVelocity, acceleration * dtSeconds);
			pitchVelocity = approach(
					pitchVelocity, wantedPitchVelocity, acceleration * 0.82F * dtSeconds);

			float yawStep = clampToError(yawVelocity * dtSeconds, yawErr);
			float pitchStep = clampToError(pitchVelocity * dtSeconds, pitchErr);
			player.setYaw(player.getYaw() + mouse.yaw(yawStep));
			player.setPitch(MathHelper.clamp(
					player.getPitch() + mouse.pitch(pitchStep), -90.0F, 90.0F));
			lastAssistedFrameNanos = now;
			return;
		}
		resetRotationSpring();

		// After the shot: ease the pitch from the ballistic up-tilt back to your pre-draw aim.
		if (recoverUntilNanos != 0L && now < recoverUntilNanos) {
			float span = (float) (recoverUntilNanos - recoverStartNanos);
			float prog = span <= 0F ? 1.0F : MathHelper.clamp((now - recoverStartNanos) / span, 0.0F, 1.0F);
			float eased = (float) (0.5D - 0.5D * Math.cos(Math.PI * prog)); // sine ease in/out
			float targetPitch = recoverFromPitch + (recoverToPitch - recoverFromPitch) * eased;
			float bounded = MathHelper.clamp(
					targetPitch - player.getPitch(),
					-110.0F * dtSeconds, 110.0F * dtSeconds);
			float step = mouse.pitch(bounded);
			if (step != 0.0F) player.setPitch(MathHelper.clamp(player.getPitch() + step, -90.0F, 90.0F));
			if (prog >= 1.0F) recoverUntilNanos = 0L;
		}
	}

	// ── Fire interception (called from the interaction mixin) ───────────────────

	/** Bow release fires the shot — perfect it (only for a bow). */
	public void onStopUsing() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.player != null && client.player.getMainHandStack().isOf(Items.BOW)) {
			perfectShot();
		}
	}

	/** A right-click fires a fireball or a loaded crossbow — perfect those. */
	public void onInteractItem() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) return;
		ItemStack held = client.player.getMainHandStack();
		if (held.isOf(Items.FIRE_CHARGE)) {
			perfectShot();
		} else if (held.isOf(Items.CROSSBOW)) {
			var charged = held.get(net.minecraft.component.DataComponentTypes.CHARGED_PROJECTILES);
			if (charged != null && !charged.isEmpty()) perfectShot();
		}
	}

	/**
	 * Called the instant a shot fires. The aim is already on target from the smooth draw-time
	 * follow (your real rotation, sent by the normal per-tick flying packet) — so there is NO
	 * last-instant snap and NO extra packet here, which is what was getting brutally flagged.
	 * All this does is start easing the view back down from the ballistic up-tilt.
	 */
	public void perfectShot() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) return;
		if (!enabledFor(client.player.getMainHandStack())) return;
		long now = System.nanoTime();
		if (targetUuid == null || !haveSolution || now - lastAssistedFrameNanos > 250_000_000L) return;
		CombatModeProfile.ProjectileAim tuning = tuning();
		recoverFromPitch  = client.player.getPitch();
		recoverToPitch    = preDrawPitch;
		recoverStartNanos = now;
		recoverUntilNanos = now + (tuning.recoveryMinMs()
				+ (long) (rng.nextDouble() * Math.max(0, tuning.recoveryMaxMs() - tuning.recoveryMinMs())))
				* 1_000_000L;
	}

	// ── Ballistic solve ────────────────────────────────────────────────────────

	/** Returns {yaw, pitch} to hit the target's predicted position, or null. */
	private float[] solve(ClientPlayerEntity player, PlayerEntity target) {
		// Persistent projectiles spawn 0.1 blocks below eye height in 1.21.11.
		// Solving from getEyePos() was a small but systematic high/low error.
		Vec3d origin = new Vec3d(player.getX(), player.getEyeY() - 0.1D, player.getZ());
		ItemStack weapon = player.getMainHandStack();
		boolean fireball = weapon.isOf(Items.FIRE_CHARGE);
		boolean rocket = isRocketCrossbow(weapon);
		boolean direct = fireball || rocket;
		double speed = fireball ? 1.5D : rocket ? 1.6D : arrowSpeed(player);
		if (speed <= 0.01D) speed = 3.0D;

		Vec3d vel = motionTargetUuid != null && motionTargetUuid.equals(target.getUuid())
				? filteredTargetVelocity : boundedVelocity(target.getVelocity(), target.isOnGround());
		Vec3d shooterVelocity = player.getMovement();
		if (player.isOnGround()) shooterVelocity = new Vec3d(shooterVelocity.x, 0.0D, shooterVelocity.z);
		boolean inheritsShooterVelocity = weapon.isOf(Items.BOW);
		Box box = target.getBoundingBox();
		Vec3d basePoint = hitPoint(box);

		// Iterate flight time: predict the target forward, re-solve, repeat.
		double t = origin.distanceTo(box.getCenter()) / speed;
		float yaw = 0.0F, pitch = 0.0F;
		for (int i = 0; i < tuning().predictionIterations(); i++) {
			Vec3d future = basePoint.add(vel.multiply(t));
			double inheritedTicks = inheritsShooterVelocity
					? ProjectileBallistics.inheritedDisplacement(t) : 0.0D;
			Vec3d relative = future.subtract(origin).subtract(
					shooterVelocity.multiply(inheritedTicks));
			double dx = relative.x;
			double dy = relative.y;
			double dz = relative.z;
			double dh = Math.sqrt(dx * dx + dz * dz);
			yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);

			if (direct || dh < 0.01D) {
				pitch = (float) (-Math.toDegrees(Math.atan2(dy, dh)));
				t = relative.length() / speed;
			} else {
				ProjectileBallistics.ArrowArc arc =
						ProjectileBallistics.solveLowArc(dh, dy, speed);
				if (arc == null) return null;
				pitch = (float) (-Math.toDegrees(arc.angle()));
				t = arc.ticks();
			}
		}
		if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 89.5F) return null;
		return validatedSolution(player, target, vel, origin, yaw, pitch, speed,
				direct, inheritsShooterVelocity, t);
	}

	/**
	 * Validate against the actual swept projectile path and moving target box.
	 * The previous check only proved that terrain was clear; a path could miss the
	 * player by a full block and still be advertised as a valid solution.
	 */
	private float[] validatedSolution(ClientPlayerEntity player, PlayerEntity target,
			Vec3d targetVelocity, Vec3d origin, float yaw, float pitch, double speed,
			boolean direct, boolean inheritsShooterVelocity, double flightTicks) {
		if (trajectoryHits(player, target, targetVelocity, origin, yaw, pitch, speed,
				direct, inheritsShooterVelocity, flightTicks)) {
			return new float[]{yaw, pitch};
		}
		double[] offsets = {0.0D, 0.12D, -0.12D, 0.25D, -0.25D, 0.42D, -0.42D};
		float[] best = null;
		double bestCost = Double.MAX_VALUE;
		for (double yawOffset : offsets) {
			for (double pitchOffset : offsets) {
				if (yawOffset == 0.0D && pitchOffset == 0.0D) continue;
				double cost = yawOffset * yawOffset + pitchOffset * pitchOffset;
				if (cost >= bestCost) continue;
				float candidateYaw = yaw + (float) yawOffset;
				float candidatePitch = pitch + (float) pitchOffset;
				if (trajectoryHits(player, target, targetVelocity, origin,
						candidateYaw, candidatePitch, speed, direct,
						inheritsShooterVelocity, flightTicks)) {
					best = new float[]{candidateYaw, candidatePitch};
					bestCost = cost;
				}
			}
		}
		return best;
	}

	private boolean trajectoryHits(ClientPlayerEntity player, PlayerEntity target,
			Vec3d targetVelocity, Vec3d origin, float yaw, float pitch,
			double speed, boolean direct, boolean inheritsShooterVelocity,
			double flightTicks) {
		if (player.getEntityWorld() == null) return false;
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		double horizontal = Math.cos(pitchRad);
		Vec3d velocity = new Vec3d(
				-Math.sin(yawRad) * horizontal,
				-Math.sin(pitchRad),
				 Math.cos(yawRad) * horizontal).multiply(speed);
		if (inheritsShooterVelocity) {
			Vec3d movement = player.getMovement();
			if (player.isOnGround()) movement = new Vec3d(movement.x, 0.0D, movement.z);
			velocity = velocity.add(movement);
		}
		Vec3d position = origin;
		Box initialTargetBox = target.getBoundingBox();
		int steps = MathHelper.clamp((int) Math.ceil(flightTicks) + 3, 1, 240);
		for (int i = 0; i < steps; i++) {
			Vec3d next = position.add(velocity);
			HitResult blockHit = player.getEntityWorld().raycast(new RaycastContext(position, next,
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
			Box movingBox = initialTargetBox
					.offset(targetVelocity.multiply(i + 0.5D))
					.expand(0.14D);
			var entityHit = movingBox.raycast(position, next);
			if (entityHit.isPresent()) {
				if (blockHit.getType() == HitResult.Type.MISS
						|| position.squaredDistanceTo(entityHit.get())
						<= position.squaredDistanceTo(blockHit.getPos()) + 1.0E-6D) {
					return true;
				}
			}
			if (blockHit.getType() != HitResult.Type.MISS) return false;
			position = next;
			if (!direct) {
				velocity = velocity.multiply(ProjectileBallistics.AIR_DRAG)
						.add(0.0D, -ProjectileBallistics.ARROW_GRAVITY, 0.0D);
			}
		}
		return false;
	}

	private double arrowSpeed(ClientPlayerEntity player) {
		ItemStack stack = player.getMainHandStack();
		if (stack.isOf(Items.CROSSBOW)) return 3.15D;
		// Bow draw power → arrow speed (max 3.0 at full draw).
		int useTicks = player.getItemUseTime();
		float p = useTicks / 20.0F;
		p = (p * p + p * 2.0F) / 3.0F;
		if (p > 1.0F) p = 1.0F;
		if (p < 0.1F) p = 0.1F; // never zero so the solve stays sane before full draw
		return p * 3.0D;
	}

	private boolean isRocketCrossbow(ItemStack stack) {
		if (!stack.isOf(Items.CROSSBOW)) return false;
		var charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
		return charged != null && charged.contains(Items.FIREWORK_ROCKET);
	}

	// ── Target selection ───────────────────────────────────────────────────────

	private PlayerEntity acquire(MinecraftClient client, ClientPlayerEntity player) {
		double fov = MathHelper.clamp(tuning().fovDeg(), 20, 120);
		double halfAngle = fov * 0.5D;
		double minDot = Math.cos(Math.toRadians(halfAngle));
		Vec3d eye = player.getEyePos();
		Vec3d look = player.getRotationVec(1.0F);

		// Keep a valid visible lock inside a slightly relaxed cone. This hysteresis matters more
		// than a tiny score advantage when two targets cross, and reads like one human decision.
		PlayerEntity current = byUuid(client, targetUuid);
		if (current != null && eligible(client, player, current, eye, look,
				Math.cos(Math.toRadians(Math.min(70.0D, halfAngle + 6.0D))))) return current;

		PlayerEntity best = null;
		double bestScore = -2.0D;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (!eligible(client, player, other, eye, look, minDot)) continue;
			Vec3d to = other.getBoundingBox().getCenter().subtract(eye);
			double dist = to.length();
			double dot = to.multiply(1.0 / dist).dotProduct(look);
			double score = dot - dist * 0.0008D;
			if (score > bestScore) {
				bestScore = score;
				best = other;
			}
		}
		return best;
	}

	private boolean eligible(MinecraftClient client, ClientPlayerEntity player, PlayerEntity other,
			Vec3d eye, Vec3d look, double minDot) {
		if (other == player || !other.isAlive() || other.isSpectator()) return false;
		Vec3d to = other.getBoundingBox().getCenter().subtract(eye);
		double dist = to.length();
		if (dist > tuning().maxTrackDistance() || dist < 0.5D
				|| to.multiply(1.0D / dist).dotProduct(look) < minDot) return false;
		return hasLineOfSight(client, player, other);
	}

	private boolean hasLineOfSight(MinecraftClient client, ClientPlayerEntity player, PlayerEntity target) {
		Vec3d eye = player.getEyePos();
		Box box = target.getBoundingBox();
		Vec3d center = box.getCenter();
		Vec3d upper = new Vec3d(center.x, box.minY + box.getLengthY() * 0.72D, center.z);
		return clearBlockRay(client, player, eye, center) || clearBlockRay(client, player, eye, upper);
	}

	private boolean clearBlockRay(MinecraftClient client, ClientPlayerEntity player, Vec3d from, Vec3d to) {
		return client.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, player)).getType() == HitResult.Type.MISS;
	}

	private Vec3d hitPoint(Box box) {
		return new Vec3d(
				box.minX + (box.maxX - box.minX) * hx,
				box.minY + (box.maxY - box.minY) * hy,
				box.minZ + (box.maxZ - box.minZ) * hz);
	}

	private void pickPoint() {
		hx = 0.32 + rng.nextDouble() * 0.36; // off-centre
		hy = 0.55 + rng.nextDouble() * 0.30; // upper body
		hz = 0.32 + rng.nextDouble() * 0.36;
	}

	/**
	 * Blend packet/interpolation movement with observed position deltas once per
	 * entity tick. Raw remote velocity is noisy around sprint starts and knockback;
	 * feeding it directly into a render-frame solve made the destination jump.
	 */
	private void updateTargetMotion(PlayerEntity target) {
		UUID uuid = target.getUuid();
		Vec3d position = target.getEntityPos();
		Vec3d raw = boundedVelocity(target.getVelocity(), target.isOnGround());
		if (!uuid.equals(motionTargetUuid)) {
			motionTargetUuid = uuid;
			lastTargetPosition = position;
			lastTargetAge = target.age;
			filteredTargetVelocity = raw;
			return;
		}
		int elapsedTicks = target.age - lastTargetAge;
		if (elapsedTicks <= 0) return;
		Vec3d measured = position.subtract(lastTargetPosition)
				.multiply(1.0D / elapsedTicks);
		Vec3d candidate = boundedVelocity(
				measured.multiply(0.72D).add(raw.multiply(0.28D)),
				target.isOnGround());
		double blend = 1.0D - Math.pow(0.52D, elapsedTicks);
		filteredTargetVelocity = filteredTargetVelocity.add(
				candidate.subtract(filteredTargetVelocity).multiply(blend));
		lastTargetPosition = position;
		lastTargetAge = target.age;
	}

	private Vec3d boundedVelocity(Vec3d velocity, boolean grounded) {
		double x = MathHelper.clamp(velocity.x, -0.85D, 0.85D);
		double z = MathHelper.clamp(velocity.z, -0.85D, 0.85D);
		double horizontal = Math.sqrt(x * x + z * z);
		if (horizontal > 0.85D) {
			double scale = 0.85D / horizontal;
			x *= scale;
			z *= scale;
		}
		double y = grounded ? 0.0D : MathHelper.clamp(velocity.y, -1.2D, 1.2D);
		return new Vec3d(x, y, z);
	}

	private float approach(float current, float target, float maximumChange) {
		if (current < target) return Math.min(target, current + maximumChange);
		return Math.max(target, current - maximumChange);
	}

	private float clampToError(float step, float error) {
		if (Math.signum(step) != Math.signum(error)) return 0.0F;
		return Math.abs(step) > Math.abs(error) ? error : step;
	}

	private void resetRotationSpring() {
		rotationTargetUuid = null;
		yawVelocity = 0.0F;
		pitchVelocity = 0.0F;
	}

	private void resetTracking() {
		motionTargetUuid = null;
		lastTargetPosition = Vec3d.ZERO;
		filteredTargetVelocity = Vec3d.ZERO;
		lastTargetAge = -1;
		resetRotationSpring();
		lastFrameNanos = 0L;
	}

	private PlayerEntity byUuid(MinecraftClient client, UUID uuid) {
		if (client.world == null) return null;
		for (PlayerEntity p : client.world.getPlayers()) {
			if (p.getUuid().equals(uuid)) return p;
		}
		return null;
	}

	// ── Gate ───────────────────────────────────────────────────────────────────

	private boolean active(MinecraftClient client) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || client.world == null || client.interactionManager == null) return false;
		if (client.currentScreen != null || client.getOverlay() != null
				|| !client.isWindowFocused() || !player.isAlive() || player.isSpectator()) return false;
		return enabledFor(player.getMainHandStack());
	}

	private boolean enabledFor(ItemStack held) {
		if (held.isOf(Items.BOW)) return CombatModePolicy.enabled(config, CombatFeature.BOW_AIM);
		if (held.isOf(Items.CROSSBOW)) return CombatModePolicy.enabled(config, CombatFeature.CROSSBOW_AIM);
		if (held.isOf(Items.FIRE_CHARGE)) return CombatModePolicy.enabled(config, CombatFeature.FIREBALL_AIM);
		return false;
	}

	private CombatModeProfile.ProjectileAim tuning() {
		return CombatModePolicy.projectileAim(config);
	}

	private boolean wantsAim(ClientPlayerEntity player, MinecraftClient client) {
		ItemStack held = player.getMainHandStack();
		if (held.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(held)) return true;
		return player.isUsingItem() || client.options.useKey.isPressed();
	}
}
