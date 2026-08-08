package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.aim.SilentAimController;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Auto Spear — lands the kinetic hit when you fly through somebody.
 *
 * <p>A spear is not a click weapon. Holding its charge amplifies forward
 * movement and the hit resolves on <em>contact</em>, once the charge has been
 * held past the item's own {@code delayTicks}. Damage scales with the movement
 * the attack lands with, which is why an elytra swoop hits far harder than the
 * same spear used standing still.
 *
 * <p>So the module does what a player does on a swoop, in this order:
 * <ol>
 *   <li><b>Get the spear out early.</b> Once a target is in range and you are
 *       closing, it switches to the spear from the hotbar. Waiting until the
 *       last moment is the mistake — the charge has to already be running when
 *       you arrive, and the amplified movement only helps if it applies for the
 *       whole approach rather than the final tick.</li>
 *   <li><b>Hold the charge for the entire run in</b>, not for one armed instant.
 *       If the use ever lapses — the duration completes, a hit interrupts it —
 *       it is restarted, so the charge is live continuously from acquisition to
 *       contact.</li>
 *   <li><b>Steer into them.</b> The hit needs the hitboxes to actually meet, so
 *       the aim runs for the whole approach and leads the target: at swoop speed
 *       the gap between where they are and where they will be is several blocks.
 *       A spear that arms perfectly and passes a metre wide does nothing.</li>
 * </ol>
 *
 * <p>The use is started through vanilla's own {@code interactItem} and then kept
 * alive by holding the real use key. Both are needed: this runs at the tail of
 * {@code handleInputEvents}, after vanilla has already looked at the key for
 * this tick, so pressing the key alone would not begin anything until the next
 * one — a tick of the approach thrown away at speed.
 */
public final class AutoSpearController {
	/** Below this the pass is a walk-up, not a swoop, and holding just burns the charge. */
	private static final double MIN_CLOSING_SPEED = 0.28D;
	/** How long the charge is kept after the pass stops closing, for a near miss. */
	private static final int OVERRUN_GRACE_TICKS = 8;
	/** Re-check for a lapsed use no more often than this. */
	private static final int REARM_INTERVAL_TICKS = 2;
	/** Nobody travels in a straight line for a second, so a longer lead is noise. */
	private static final double MAX_LEAD_TICKS = 8.0D;
	/** Ceiling on the vertical part of the lead, in blocks. */
	private static final double MAX_VERTICAL_LEAD = 1.0D;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();

	private boolean engaged;
	private boolean holding;
	private boolean originalUse;
	private int returnSlot = -1;
	private int overrunTicks;
	private int rearmTick;
	private UUID targetUuid;
	private long lastFrameNanos;
	private boolean ownsRotation;
	private String status = "Idle";

	public AutoSpearController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			disengage(client);
			return;
		}
		ClientPlayerEntity player = client.player;

		PlayerEntity target = acquireTarget(client, player);
		if (target == null) {
			// Keep the charge briefly: a target that just left the cone is often
			// one you are already committed to passing through.
			if (engaged && ++overrunTicks <= OVERRUN_GRACE_TICKS) {
				sustain(client, player);
				return;
			}
			disengage(client);
			status = "No target";
			return;
		}

		Approach approach = predict(player, target);
		if (approach == null) {
			if (engaged && ++overrunTicks <= OVERRUN_GRACE_TICKS) {
				sustain(client, player);
				return;
			}
			disengage(client);
			status = "Not closing";
			return;
		}
		overrunTicks = 0;
		targetUuid = target.getUuid();

		// The spear has to be out for the run in, not produced on arrival.
		if (!isSpear(player.getMainHandStack())) {
			int slot = findSpearSlot(player);
			if (slot < 0) {
				disengage(client);
				status = "No spear in hotbar";
				return;
			}
			if (!config.autoSpearAutoSwitch) {
				status = "Hold a spear";
				return;
			}
			if (returnSlot < 0) returnSlot = player.getInventory().getSelectedSlot();
			select(client, player, slot);
		}

		engaged = true;
		sustain(client, player);
		status = "Charging · " + (int) approach.distance() + "m";
	}

	/**
	 * Keeps the charge running. Vanilla ends a use the moment the key reads
	 * released, and the use can also simply finish on its own, so this both holds
	 * the key and restarts the use whenever it has lapsed.
	 */
	private void sustain(MinecraftClient client, ClientPlayerEntity player) {
		if (!holding) {
			originalUse = client.options.useKey.isPressed();
			holding = true;
		}
		client.options.useKey.setPressed(true);
		if (player.isUsingItem()) return;
		// Started here rather than left to the key, because this runs after
		// vanilla has already read the key for this tick.
		if (player.age < rearmTick) return;
		rearmTick = player.age + REARM_INTERVAL_TICKS;
		client.interactionManager.interactItem(player, Hand.MAIN_HAND);
	}

	/**
	 * Aim help, per render frame.
	 *
	 * <p>Runs for the whole approach rather than only once the charge is armed:
	 * steering is what makes the hitboxes meet, and by the time a last-moment
	 * arm would fire there is no approach left to steer.
	 */
	public boolean frame(MinecraftClient client) {
		ownsRotation = false;
		if (!allowed(client) || !engaged) {
			// Only ever release an engagement this module actually made. Silent
			// aim is shared, so releasing unconditionally would hand back a
			// camera the mace was still holding.
			if (config.autoSpearSilentAim) SilentAimController.instance().release();
			return false;
		}
		ClientPlayerEntity player = client.player;
		PlayerEntity target = targetById(client, player);
		if (target == null) return false;

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0D * 20.0D, 0.05D, 3.0D);
		lastFrameNanos = now;

		if (config.autoSpearSilentAim) SilentAimController.instance().engage(player);

		Vec3d aimAt = interceptPoint(player, target);
		Vec3d eye = player.getEyePos();
		double dx = aimAt.x - eye.x;
		double dz = aimAt.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		float desiredPitch = (float) -Math.toDegrees(Math.atan2(aimAt.y - eye.y, horizontal));

		float speed = MathHelper.clamp(config.autoSpearTurnSpeed, 20, 90) / 100.0F;
		float blend = 1.0F - (float) Math.pow(1.0F - speed, dt);
		float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchError = MathHelper.wrapDegrees(desiredPitch - player.getPitch());
		float cap = MathHelper.clamp(config.autoSpearTurnSpeed, 20, 90) * 0.6F * dt;

		player.setYaw(player.getYaw() + mouse.yaw(MathHelper.clamp(
				yawError * blend + (float) rng.nextGaussian() * 0.14F, -cap, cap)));
		player.setPitch(MathHelper.clamp(player.getPitch() + mouse.pitch(MathHelper.clamp(
				pitchError * blend + (float) rng.nextGaussian() * 0.10F, -cap * 0.8F, cap * 0.8F)),
				-90.0F, 90.0F));
		player.headYaw = player.getYaw();
		ownsRotation = true;
		return true;
	}

	// ── Prediction ────────────────────────────────────────────────────────────

	/**
	 * Ticks until the two hitboxes meet, from the closing component of the
	 * relative velocity. Null when the pass is not closing at all — standing
	 * still, drifting away, or already through them.
	 */
	private Approach predict(ClientPlayerEntity player, PlayerEntity target) {
		Vec3d self = player.getEntityPos();
		Vec3d other = target.getEntityPos();
		Vec3d toTarget = other.subtract(self);
		double distance = toTarget.length();
		if (distance < 1.0E-4D) return new Approach(0, 0.0D);

		// Their movement counts: a target flying at you halves the time you have.
		Vec3d relative = player.getVelocity().subtract(target.getVelocity());
		double closing = relative.dotProduct(toTarget.normalize());
		if (closing < MIN_CLOSING_SPEED) return null;

		double contactGap = (player.getWidth() + target.getWidth()) * 0.5D + 0.35D;
		double travel = Math.max(0.0D, distance - contactGap);
		return new Approach((int) Math.ceil(travel / closing), distance);
	}

	/** Where to point so the hitboxes meet, accounting for both of you moving. */
	private Vec3d interceptPoint(ClientPlayerEntity player, PlayerEntity target) {
		Vec3d self = player.getEyePos();
		Vec3d centre = target.getBoundingBox().getCenter();
		Vec3d toTarget = centre.subtract(self);
		double distance = toTarget.length();
		if (distance < 1.0E-4D) return centre;

		// Time to contact from the component of relative velocity along the line
		// between us. Using the raw magnitude instead counts sideways drift as
		// closing speed, which under-reads the time and inflates the lead.
		Vec3d relative = player.getVelocity().subtract(target.getVelocity());
		double closing = relative.dotProduct(toTarget.normalize());
		if (closing < MIN_CLOSING_SPEED) return centre;
		double ticks = MathHelper.clamp(distance / closing, 0.0D, MAX_LEAD_TICKS);

		Vec3d velocity = target.getVelocity();
		// Vertical velocity is the noisiest channel a remote player has: gravity
		// oscillates it every tick, a jump spikes it to about +0.42, and a stale
		// knockback packet can leave it large long after the motion stopped.
		// Extrapolating that for a whole second was aiming several blocks over
		// their head. Lead horizontally, where the motion is actually sustained,
		// and allow only a token vertical correction.
		double verticalLead = target.isOnGround() ? 0.0D
				: MathHelper.clamp(velocity.y * ticks, -MAX_VERTICAL_LEAD, MAX_VERTICAL_LEAD);
		return centre.add(velocity.x * ticks, verticalLead, velocity.z * ticks);
	}

	private record Approach(int ticksToContact, double distance) {}

	// ── Targeting ─────────────────────────────────────────────────────────────

	private PlayerEntity acquireTarget(MinecraftClient client, ClientPlayerEntity self) {
		double range = MathHelper.clamp(config.autoSpearRange, 8, 96);
		double minDot = Math.cos(Math.toRadians(MathHelper.clamp(config.autoSpearFov, 20, 140)));
		// Scored against where you are actually travelling, not where you are
		// looking: on a swoop the flight path decides who you can reach, and the
		// view is free to be somewhere else entirely.
		Vec3d heading = self.getVelocity().lengthSquared() > 1.0E-4D
				? self.getVelocity().normalize()
				: self.getRotationVec(1.0F);

		PlayerEntity best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator()) continue;
			Vec3d delta = other.getBoundingBox().getCenter().subtract(self.getEyePos());
			double distance = delta.length();
			if (distance < 1.0E-4D || distance > range) continue;
			if (delta.normalize().dotProduct(heading) < minDot) continue;
			// Stay on the target already committed to rather than flipping to a
			// marginally better one mid-approach.
			double score = (range - distance) / range + (other.getUuid().equals(targetUuid) ? 0.45D : 0.0D);
			if (score > bestScore) {
				bestScore = score;
				best = other;
			}
		}
		return best;
	}

	private PlayerEntity targetById(MinecraftClient client, ClientPlayerEntity self) {
		if (targetUuid == null) return null;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (targetUuid.equals(other.getUuid()) && other.isAlive() && !other.isSpectator()) return other;
		}
		return null;
	}

	// ── Spear handling ────────────────────────────────────────────────────────

	private int findSpearSlot(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			if (isSpear(player.getInventory().getStack(slot))) return slot;
		}
		return -1;
	}

	private boolean isSpear(ItemStack stack) {
		return !stack.isEmpty() && stack.contains(DataComponentTypes.KINETIC_WEAPON);
	}

	private void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	/** Stops the charge, hands back the use key, and returns the previous slot. */
	private void disengage(MinecraftClient client) {
		if (client != null && client.player != null) {
			if (holding) {
				client.options.useKey.setPressed(originalUse);
				if (client.player.isUsingItem() && client.interactionManager != null) {
					client.interactionManager.stopUsingItem(client.player);
				}
			}
			if (returnSlot >= 0 && config.autoSpearAutoSwitch
					&& isSpear(client.player.getMainHandStack())) {
				select(client, client.player, returnSlot);
			}
		}
		// Same rule as in frame(): never hand back a camera this module did not take.
		if (config.autoSpearSilentAim) SilentAimController.instance().release();
		engaged = false;
		holding = false;
		returnSlot = -1;
		overrunTicks = 0;
		rearmTick = 0;
		targetUuid = null;
		ownsRotation = false;
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || !config.autoSpearEnabled) return false;
		if (client == null || client.player == null || client.world == null) return false;
		if (client.interactionManager == null || client.currentScreen != null
				|| client.getOverlay() != null || !client.isWindowFocused()) return false;
		ClientPlayerEntity player = client.player;
		return player.isAlive() && !player.isSpectator() && !player.isTouchingWater();
	}

	public boolean ownsRotation() {
		return ownsRotation;
	}

	public boolean isBusy() {
		return engaged;
	}

	public String status() {
		return engaged ? status : "Idle";
	}
}
