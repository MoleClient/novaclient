package com.profps.client.instants;

import com.profps.client.aim.MouseGcd;
import com.profps.client.combatmode.CombatFeature;
import com.profps.client.combatmode.CombatModePolicy;
import com.profps.client.combatmode.CombatModeProfile;
import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.AbstractWindChargeEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mace-mode pearl catch: visibly aims and throws one player wind charge onto the swept path of
 * the local player's own ender pearl. The older pearl can collide with the redirectable charge,
 * causing the pearl to resolve at the mid-air catch point.
 *
 * <p>All actions are server-valid: the real camera moves on the mouse GCD, the normal selected-slot
 * packet precedes one normal interact-item packet, the camera must have remained on the solution
 * across movement ticks, and the original slot is restored on the following tick. A solution is
 * abandoned when either projected path is obstructed; there is no blind throw or retry spam.
 */
public final class PearlCatchController {
	private static final int MAX_HANDLED_PEARLS = 32;
	private static final int MAX_AIM_TICKS = 34;
	private static final long ATTEMPT_COOLDOWN_NS = 900_000_000L;

	private enum Phase { IDLE, AIMING, RESTORE }

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final MouseGcd mouse = new MouseGcd();
	private final Set<UUID> handledPearls = new HashSet<>();
	private final ArrayDeque<UUID> handledOrder = new ArrayDeque<>();

	private Phase phase = Phase.IDLE;
	private UUID activePearlUuid;
	private PearlInterceptSolver.Solution solution;
	private int originalSlot = -1;
	private int windSlot = -1;
	private int aimTicks;
	private int alignedTicks;
	private int noSolutionTicks;
	private int earliestFireAge;
	private int restoreAtAge = -1;
	private long cooldownUntilNanos;
	private long lastFrameNanos;
	private float desiredYaw;
	private float desiredPitch;
	private float previousDesiredYaw;
	private float previousDesiredPitch;
	private boolean havePreviousAim;
	private boolean preferAlternateSolution;

	// A short visible return after the throw; it never emits an extra look packet.
	private float recoveryFromYaw, recoveryFromPitch, recoveryToYaw, recoveryToPitch;
	private long recoveryStartNanos, recoveryUntilNanos;

	public PearlCatchController(ProFPSConfig config) {
		this.config = config;
	}

	/**
	 * Call at the vanilla click phase (the existing firePreMovement hook), before the player's
	 * normal movement packet. Requiring two aligned ticks means the server already saw the aimed
	 * rotation before the use packet is sent.
	 */
	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			clearState();
			return;
		}

		if (phase == Phase.RESTORE) {
			if (player.age >= restoreAtAge) {
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.PEARL_CATCH)) return;
				restoreOriginalSlot(player);
				phase = Phase.IDLE;
				restoreAtAge = -1;
				activePearlUuid = null;
				solution = null;
				preferAlternateSolution = false;
			}
			return;
		}

		if (!enabled(client)) {
			abort(player, false);
			return;
		}
		long now = System.nanoTime();
		if (now < cooldownUntilNanos) return;

		if (phase == Phase.IDLE) {
			EnderPearlEntity pearl = findNewLocalPearl(client, player);
			if (pearl == null) return;
			rememberHandled(pearl.getUuid());
			windSlot = findWindCharge(player);
			if (windSlot < 0) return;
			activePearlUuid = pearl.getUuid();
			originalSlot = player.getInventory().getSelectedSlot();
			recoveryToYaw = player.getYaw();
			recoveryToPitch = player.getPitch();
			aimTicks = alignedTicks = noSolutionTicks = 0;
			havePreviousAim = false;
			CombatModeProfile.PearlCatch tuning = tuning();
			preferAlternateSolution = rng.nextDouble() < tuning.alternateSolutionChance();
			double reactionMs = tuning.reactionMinMs()
					+ rng.nextDouble() * Math.max(0, tuning.reactionMaxMs() - tuning.reactionMinMs());
			reactionMs += tuning.delayMs();
			int reactionTicks = Math.max(1, (int) Math.ceil(reactionMs / 50.0D));
			earliestFireAge = player.age + reactionTicks;
			phase = Phase.AIMING;
		}

		EnderPearlEntity pearl = findActivePearl(client, player);
		if (pearl == null || pearl.age > 90 || aimTicks++ > MAX_AIM_TICKS) {
			abort(player, true);
			return;
		}
		// Respect a manual scroll/use/attack instead of fighting the player's intent.
		if (player.getInventory().getSelectedSlot() != originalSlot
				|| player.isUsingItem() || client.options.attackKey.isPressed()) {
			abort(player, true);
			return;
		}

		PearlInterceptSolver.Solution next = plan(client, player, pearl);
		if (next == null) {
			solution = null;
			alignedTicks = 0;
			if (++noSolutionTicks > Math.max(12,
					(int) Math.ceil(tuning().reacquireMs() / 50.0D) * 4)) {
				abort(player, true);
			}
			return;
		}
		noSolutionTicks = 0;
		solution = next;
		setDesiredRotation(player, next.aimDirection());

		float yawError = Math.abs(MathHelper.wrapDegrees(desiredYaw - player.getYaw()));
		float pitchError = Math.abs(desiredPitch - player.getPitch());
		float aimChange = havePreviousAim
				? Math.max(Math.abs(MathHelper.wrapDegrees(desiredYaw - previousDesiredYaw)),
						Math.abs(desiredPitch - previousDesiredPitch))
				: Float.MAX_VALUE;
		previousDesiredYaw = desiredYaw;
		previousDesiredPitch = desiredPitch;
		havePreviousAim = true;
		if (yawError <= 0.85F && pitchError <= 0.70F && aimChange <= 1.25F) alignedTicks++;
		else alignedTicks = 0;

		if (player.age < earliestFireAge || alignedTicks < 2) return;
		if (client.options.useKey.isPressed()) return; // wait for the physical pearl click to release
		fire(client, player, now);
	}

	/** Smooth visible camera work; register beside AutoMace/AutoAim in WorldRenderEvents. */
	public void frame(MinecraftClient client) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1.0F
				: (float) MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000.0D * 20.0D, 0.05D, 4.0D);
		lastFrameNanos = now;
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || client.currentScreen != null) return;

		if (phase == Phase.AIMING && solution != null && enabled(client)) {
			float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
			float pitchError = desiredPitch - player.getPitch();
			CombatModeProfile.PearlCatch tuning = tuning();
			float pull = 0.12F + tuning.aimSpeedPct() / 100.0F * 0.20F;
			float k = 1.0F - (float) Math.pow(1.0F - pull, dt);
			float cap = (12.0F + tuning.aimSpeedPct() * 0.16F) * dt;
			float yawStep = MathHelper.clamp(yawError * k + (float) rng.nextGaussian() * 0.075F, -cap, cap);
			float pitchStep = MathHelper.clamp(pitchError * k + (float) rng.nextGaussian() * 0.055F,
					-cap * 0.82F, cap * 0.82F);
			applyRotation(player, mouse.yaw(yawStep), mouse.pitch(pitchStep));
			return;
		}

		if (recoveryUntilNanos > now && now >= recoveryStartNanos) {
			float progress = MathHelper.clamp((float) (now - recoveryStartNanos)
					/ Math.max(1.0F, (float) (recoveryUntilNanos - recoveryStartNanos)), 0.0F, 1.0F);
			float eased = (float) (0.5D - 0.5D * Math.cos(Math.PI * progress));
			float targetYaw = recoveryFromYaw
					+ MathHelper.wrapDegrees(recoveryToYaw - recoveryFromYaw) * eased;
			float targetPitch = MathHelper.lerp(eased, recoveryFromPitch, recoveryToPitch);
			float yawStep = mouse.yaw(MathHelper.wrapDegrees(targetYaw - player.getYaw()));
			float pitchStep = mouse.pitch(targetPitch - player.getPitch());
			applyRotation(player, yawStep, pitchStep);
		}
	}

	public boolean isBusy() {
		return phase != Phase.IDLE;
	}

	/** True while this controller owns the visible camera, including its short return motion. */
	public boolean ownsRotation() {
		return phase == Phase.AIMING || System.nanoTime() < recoveryUntilNanos;
	}

	/** Clear session-only trajectory and recovery state after a disconnect. */
	public void reset() {
		clearState();
		handledPearls.clear();
		handledOrder.clear();
		cooldownUntilNanos = 0L;
	}

	private PearlInterceptSolver.Solution plan(MinecraftClient client, ClientPlayerEntity player,
			EnderPearlEntity pearl) {
		int latencyTicks = latencyTicks(client, player);
		Vec3d inheritedMovement = player.getMovement();
		if (player.isOnGround()) inheritedMovement = new Vec3d(inheritedMovement.x, 0.0D, inheritedMovement.z);
		Vec3d origin = new Vec3d(player.getX(), player.getEyePos().y, player.getZ());
		// Keep every enabled tier capable of solving long throws. Tiers alter how quickly the
		// visible aim settles, not whether the physical pearl/charge intersection is considered.
		CombatModeProfile.PearlCatch tuning = tuning();
		int horizon = MathHelper.clamp(50 + tuning.simulationTicks(), 120, 160);
		PearlInterceptSolver.Request request = new PearlInterceptSolver.Request(
				pearl.getEntityPos(), pearl.getVelocity(), pearl.age, origin, inheritedMovement,
				latencyTicks, horizon, 128.0D);
		List<PearlInterceptSolver.Solution> candidates = PearlInterceptSolver.solveCandidates(
				request, Math.max(4, tuning.solveSubsteps() * 2));
		// Human variance chooses a different still-valid interception occasionally. This is
		// deliberately not a miss chance: every selected route must pass the same world checks.
		int start = candidates.size() > 1 && preferAlternateSolution ? 1 : 0;
		for (int i = 0; i < candidates.size(); i++) {
			PearlInterceptSolver.Solution candidate = candidates.get((start + i) % candidates.size());
			if (pathsAreClear(client, player, pearl, origin, candidate)) return candidate;
		}
		return null;
	}

	private boolean pathsAreClear(MinecraftClient client, ClientPlayerEntity player,
			EnderPearlEntity pearl, Vec3d origin, PearlInterceptSolver.Solution candidate) {
		if (!physicalPathClear(client, player, origin, candidate.windPosition())) return false;

		// Reject a currently visible entity that would consume/explode the wind charge first.
		Box windPath = new Box(origin, candidate.windPosition()).expand(0.42D);
		for (Entity entity : client.world.getOtherEntities(player, windPath, Entity::canBeHitByProjectile)) {
			if (entity == pearl || entity instanceof AbstractWindChargeEntity) continue;
			if (entity.getBoundingBox().expand(0.30D).raycast(origin, candidate.windPosition()).isPresent()) {
				return false;
			}
		}

		// The pearl also has to survive its own physical path until the planned catch step.
		// We intentionally fail closed through fluids because their drag differs from this air
		// solver, and reject any entity that would consume the pearl before the charge can.
		Vec3d pos = pearl.getEntityPos();
		Vec3d velocity = pearl.getVelocity();
		for (int i = 0; i < candidate.pearlSteps(); i++) {
			velocity = new Vec3d(
					velocity.x * PearlInterceptSolver.PEARL_DRAG,
					(velocity.y - PearlInterceptSolver.PEARL_GRAVITY) * PearlInterceptSolver.PEARL_DRAG,
					velocity.z * PearlInterceptSolver.PEARL_DRAG);
			Vec3d next = pos.add(velocity);
			if (!physicalPathClear(client, pearl, pos, next)) return false;
			Box sweptPearl = new Box(pos, next).expand(0.46D);
			for (Entity entity : client.world.getOtherEntities(pearl, sweptPearl,
					Entity::canBeHitByProjectile)) {
				if (entity == player || entity instanceof AbstractWindChargeEntity) continue;
				if (entity.getBoundingBox().expand(0.30D).raycast(pos, next).isPresent()) return false;
			}
			pos = next;
		}
		return true;
	}

	private boolean physicalPathClear(MinecraftClient client, Entity source, Vec3d from, Vec3d to) {
		BlockHitResult hit = client.world.raycast(new RaycastContext(from, to,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, source));
		return hit.getType() == HitResult.Type.MISS;
	}

	private void fire(MinecraftClient client, ClientPlayerEntity player, long now) {
		windSlot = findWindCharge(player);
		if (windSlot < 0) { abort(player, true); return; }
		ItemStack wind = player.getInventory().getStack(windSlot);
		if (player.getItemCooldownManager().isCoolingDown(wind)) return;
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.PEARL_CATCH)) return;

		selectForUse(client, player, windSlot);
		ActionResult result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
		if (result instanceof ActionResult.Success success
				&& success.swingSource() == ActionResult.SwingSource.CLIENT) {
			player.swingHand(Hand.MAIN_HAND);
		}
		((MinecraftClientInvoker) client).profps$setItemUseCooldown(4); // vanilla doItemUse cadence

		recoveryFromYaw = player.getYaw();
		recoveryFromPitch = player.getPitch();
		long recoveryDelay = 70_000_000L + (long) (rng.nextDouble() * 45_000_000L);
		recoveryStartNanos = now + recoveryDelay;
		recoveryUntilNanos = recoveryStartNanos + 220_000_000L + (long) (rng.nextDouble() * 100_000_000L);
		restoreAtAge = player.age + 1;
		cooldownUntilNanos = now + ATTEMPT_COOLDOWN_NS;
		phase = Phase.RESTORE;
		solution = null;
	}

	private void setDesiredRotation(ClientPlayerEntity player, Vec3d direction) {
		double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
		desiredYaw = horizontal < 1.0E-6D ? player.getYaw()
				: (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0D);
		desiredPitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
		desiredPitch += tuning().angleDeg();
		desiredPitch = MathHelper.clamp(desiredPitch, -89.8F, 89.8F);
	}

	private void applyRotation(ClientPlayerEntity player, float yawStep, float pitchStep) {
		if (yawStep != 0.0F) player.setYaw(player.getYaw() + yawStep);
		if (pitchStep != 0.0F) player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0F, 90.0F));
		player.headYaw = player.getYaw();
		player.bodyYaw = player.getYaw();
	}

	private EnderPearlEntity findNewLocalPearl(MinecraftClient client, ClientPlayerEntity player) {
		EnderPearlEntity newest = null;
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof EnderPearlEntity pearl) || pearl.getOwner() != player
					|| handledPearls.contains(pearl.getUuid()) || pearl.age > 16) continue;
			if (newest == null || pearl.age < newest.age) newest = pearl;
		}
		return newest;
	}

	private EnderPearlEntity findActivePearl(MinecraftClient client, ClientPlayerEntity player) {
		if (activePearlUuid == null) return null;
		for (Entity entity : client.world.getEntities()) {
			if (entity instanceof EnderPearlEntity pearl && pearl.getUuid().equals(activePearlUuid)
					&& pearl.getOwner() == player && !pearl.isRemoved()) return pearl;
		}
		return null;
	}

	private int findWindCharge(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getStack(slot).isOf(Items.WIND_CHARGE)) return slot;
		}
		return -1;
	}

	private void selectForUse(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	private void restoreOriginalSlot(ClientPlayerEntity player) {
		if (originalSlot >= 0 && originalSlot < 9
				&& player.getInventory().getSelectedSlot() == windSlot) {
			// Restoration is an actual ordered slot change in this input phase. Relying on a
			// later vanilla inventory poll could leave the server holding the charge for a tick.
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.interactionManager != null) selectForUse(client, player, originalSlot);
			else player.getInventory().setSelectedSlot(originalSlot);
		}
		originalSlot = windSlot = -1;
	}

	private void abort(ClientPlayerEntity player, boolean rest) {
		if (phase == Phase.RESTORE) restoreOriginalSlot(player);
		phase = Phase.IDLE;
		activePearlUuid = null;
		solution = null;
		alignedTicks = noSolutionTicks = aimTicks = 0;
		havePreviousAim = false;
		preferAlternateSolution = false;
		if (rest) cooldownUntilNanos = System.nanoTime() + 350_000_000L;
	}

	private void clearState() {
		phase = Phase.IDLE;
		activePearlUuid = null;
		solution = null;
		originalSlot = windSlot = restoreAtAge = -1;
		alignedTicks = noSolutionTicks = aimTicks = 0;
		havePreviousAim = false;
		preferAlternateSolution = false;
		recoveryUntilNanos = 0L;
	}

	private void rememberHandled(UUID uuid) {
		if (!handledPearls.add(uuid)) return;
		handledOrder.addLast(uuid);
		while (handledOrder.size() > MAX_HANDLED_PEARLS) {
			handledPearls.remove(handledOrder.removeFirst());
		}
	}

	private int latencyTicks(MinecraftClient client, ClientPlayerEntity player) {
		if (client.getNetworkHandler() == null) return 0;
		var entry = client.getNetworkHandler().getPlayerListEntry(player.getUuid());
		if (entry == null) return 0;
		// Half the round-trip latency, expressed in 50 ms server ticks.
		return MathHelper.clamp(Math.round(entry.getLatency() / 100.0F), 0, 4);
	}

	private boolean enabled(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		return CombatModePolicy.enabled(config, CombatFeature.PEARL_CATCH)
				&& client.world != null && client.interactionManager != null
				&& client.currentScreen == null && player != null && player.isAlive() && !player.isSpectator();
	}

	private CombatModeProfile.PearlCatch tuning() {
		return CombatModePolicy.pearlCatch(config);
	}
}
