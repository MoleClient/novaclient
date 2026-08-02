package com.profps.client.subtiers;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * After the player places a creeper egg, visibly lines up a KB-II-or-better
 * sword hit that sends the creeper along the player-to-target corridor.
 */
public final class AutoCreeperController {
	private enum Phase { IDLE, WAIT_CREEPER, REACTION, AIMING, RECOVERING }

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private final HumanizedRotation rotation = new HumanizedRotation();

	private Phase phase = Phase.IDLE;
	private BlockPos spawnHint;
	private UUID creeperUuid;
	private UUID targetUuid;
	private final Set<UUID> creepersPresentBeforePlacement = new HashSet<>();
	private int swordSlot = -1;
	private int originalSlot = -1;
	private long actionAtNanos;
	private long expireAtNanos;
	private long settledSinceNanos;
	private long settleNeededNanos;
	private long restoreAtNanos;
	private long recoverUntilNanos;
	private float cooldownThreshold;
	private volatile float aimError = Float.MAX_VALUE;
	private double hitX;
	private double hitY;
	private double hitZ;

	public AutoCreeperController(ProFPSConfig config) {
		this.config = config;
	}

	public ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
		if (!config.enabled || !config.subTiersAutoCreeper || !world.isClient() || hand != Hand.MAIN_HAND) {
			return ActionResult.PASS;
		}
		if (player == null || !player.getStackInHand(hand).isOf(Items.CREEPER_SPAWN_EGG)) {
			return ActionResult.PASS;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		reset(client, true);
		long now = System.nanoTime();
		spawnHint = hit.getBlockPos().offset(hit.getSide()).toImmutable();
		rememberNearbyCreepers(world, spawnHint);
		phase = Phase.WAIT_CREEPER;
		actionAtNanos = now + ns(18D + rng.nextDouble() * 42D);
		expireAtNanos = now + ns(1900D + rng.nextDouble() * 450D);
		return ActionResult.PASS;
	}

	/** Called in the vanilla pre-movement attack phase. */
	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			if (originalSlot >= 0
					&& !CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CREEPER)) return;
			reset(client, true);
			return;
		}
		if (phase == Phase.IDLE) return;
		long now = System.nanoTime();
		if (now > expireAtNanos) { reset(client, true); return; }

		ClientPlayerEntity player = client.player;
		if (phase == Phase.RECOVERING) {
			if (originalSlot >= 0 && now >= restoreAtNanos) {
				if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CREEPER)) return;
				selectSlot(client, originalSlot);
				originalSlot = -1;
			}
			if (now >= recoverUntilNanos) reset(client, false);
			return;
		}

		if (phase == Phase.WAIT_CREEPER) {
			if (now < actionAtNanos) return;
			CreeperEntity creeper = findFreshCreeper(client);
			if (creeper == null) {
				actionAtNanos = now + ns(18D + rng.nextDouble() * 36D);
				return;
			}
			PlayerEntity target = nearestTarget(client, player);
			int slot = findKnockbackSword(player);
			if (target == null || slot < 0 || !launchCorridor(player, creeper, target, 0.78D)) {
				reset(client, false);
				return;
			}

			creeperUuid = creeper.getUuid();
			targetUuid = target.getUuid();
			swordSlot = slot;
			originalSlot = player.getInventory().getSelectedSlot();
			hitX = 0.36D + rng.nextDouble() * 0.28D;
			hitY = 0.42D + rng.nextDouble() * 0.26D;
			hitZ = 0.36D + rng.nextDouble() * 0.28D;
			rotation.begin(player, rng, true);
			cooldownThreshold = 0.86F + rng.nextFloat() * 0.10F;
			double reactionMs = 24D + rng.nextDouble() * 67D + Math.abs(rng.nextGaussian()) * 6D;
			if (rng.nextDouble() < 0.09D) reactionMs += 45D + rng.nextDouble() * 100D;
			actionAtNanos = now + ns(reactionMs);
			phase = Phase.REACTION;
			return;
		}

		CreeperEntity creeper = byUuid(client, creeperUuid);
		PlayerEntity target = playerByUuid(client, targetUuid);
		if (creeper == null || target == null || !launchCorridor(player, creeper, target, 0.70D)) {
			startRecovery(now);
			return;
		}

		if (phase == Phase.REACTION) {
			if (now < actionAtNanos) return;
			if (player.isUsingItem()) { startRecovery(now); return; }
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CREEPER)) return;
			selectSlot(client, swordSlot);
			double switchPause = 34D + rng.nextDouble() * 62D + Math.abs(rng.nextGaussian()) * 5D;
			actionAtNanos = now + ns(switchPause);
			settledSinceNanos = 0L;
			settleNeededNanos = ns(18D + rng.nextDouble() * 52D);
			phase = Phase.AIMING;
			return;
		}

		if (player.getInventory().getSelectedSlot() != swordSlot) {
			// A manual slot change always wins over the automation.
			startRecovery(now);
			return;
		}
		if (aimError < 1.75F) {
			if (settledSinceNanos == 0L) settledSinceNanos = now;
		} else {
			settledSinceNanos = 0L;
		}
		if (now < actionAtNanos || settledSinceNanos == 0L
				|| now - settledSinceNanos < settleNeededNanos) return;
		if (player.getAttackCooldownProgress(0.0F) < cooldownThreshold) return;

		Entity cached = client.crosshairTarget instanceof EntityHitResult hit ? hit.getEntity() : null;
		Entity fresh = freshTarget(client);
		if (cached != creeper || fresh != creeper) return;
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CREEPER)) return;

		((MinecraftClientInvoker) (Object) client).invokeDoAttack();
		startRecovery(now);
	}

	public void frame(MinecraftClient client) {
		if (!allowed(client) || phase == Phase.IDLE || phase == Phase.WAIT_CREEPER) return;
		ClientPlayerEntity player = client.player;
		if (phase == Phase.RECOVERING) {
			aimError = rotation.recover(player);
			return;
		}
		if (phase != Phase.AIMING) return; // genuine reaction pause before the hand starts moving
		CreeperEntity creeper = byUuid(client, creeperUuid);
		if (creeper == null) return;
		Box box = creeper.getBoundingBox();
		Vec3d point = new Vec3d(
				MathHelper.lerp(hitX, box.minX, box.maxX),
				MathHelper.lerp(hitY, box.minY, box.maxY),
				MathHelper.lerp(hitZ, box.minZ, box.maxZ));
		aimError = rotation.aimAt(player, point);
	}

	/** True while this explicit creeper sequence owns the visible camera. */
	public boolean ownsRotation() {
		return phase == Phase.AIMING || phase == Phase.RECOVERING;
	}

	private CreeperEntity findFreshCreeper(MinecraftClient client) {
		Box search = new Box(spawnHint).expand(3.0D, 2.0D, 3.0D);
		CreeperEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (CreeperEntity creeper : client.world.getEntitiesByClass(
				CreeperEntity.class, search, entity -> entity.isAlive() && entity.age < 35)) {
			if (creepersPresentBeforePlacement.contains(creeper.getUuid())) continue;
			double sq = creeper.squaredDistanceTo(Vec3d.ofCenter(spawnHint));
			if (sq < bestSq) { bestSq = sq; best = creeper; }
		}
		return best;
	}

	private void rememberNearbyCreepers(World world, BlockPos center) {
		creepersPresentBeforePlacement.clear();
		Box search = new Box(center).expand(3.0D, 2.0D, 3.0D);
		for (CreeperEntity creeper : world.getEntitiesByClass(
				CreeperEntity.class, search, Entity::isAlive)) {
			creepersPresentBeforePlacement.add(creeper.getUuid());
		}
	}

	private PlayerEntity nearestTarget(MinecraftClient client, ClientPlayerEntity self) {
		PlayerEntity best = null;
		double bestSq = 20.0D * 20.0D;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == self || !other.isAlive() || other.isSpectator()) continue;
			double sq = self.squaredDistanceTo(other);
			if (sq < 4.0D || sq > bestSq || !self.canSee(other)) continue;
			bestSq = sq;
			best = other;
		}
		return best;
	}

	private boolean launchCorridor(ClientPlayerEntity self, CreeperEntity creeper, PlayerEntity target, double minDot) {
		Vec3d launch = creeper.getEntityPos().subtract(self.getEntityPos()).multiply(1.0D, 0.0D, 1.0D);
		Vec3d goal = target.getEntityPos().subtract(creeper.getEntityPos()).multiply(1.0D, 0.0D, 1.0D);
		double launchLength = launch.length();
		double goalLength = goal.length();
		return launchLength > 0.35D && goalLength > 0.5D
				&& launch.dotProduct(goal) / (launchLength * goalLength) >= minDot;
	}

	private int findKnockbackSword(ClientPlayerEntity player) {
		int bestSlot = -1;
		int bestLevel = 1;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isIn(ItemTags.SWORDS)) continue;
			int level = knockbackLevel(stack);
			if (level >= 2 && level > bestLevel) {
				bestLevel = level;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	private int knockbackLevel(ItemStack stack) {
		var enchantments = EnchantmentHelper.getEnchantments(stack);
		for (var enchantment : enchantments.getEnchantments()) {
			if (enchantment.matchesKey(Enchantments.KNOCKBACK)) {
				return enchantments.getLevel(enchantment);
			}
		}
		return 0;
	}

	private CreeperEntity byUuid(MinecraftClient client, UUID uuid) {
		if (uuid == null) return null;
		Entity entity = client.world.getEntity(uuid);
		return entity instanceof CreeperEntity creeper && creeper.isAlive() ? creeper : null;
	}

	private PlayerEntity playerByUuid(MinecraftClient client, UUID uuid) {
		if (uuid == null) return null;
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player.getUuid().equals(uuid) && player.isAlive() && !player.isSpectator()) return player;
		}
		return null;
	}

	private Entity freshTarget(MinecraftClient client) {
		Entity camera = client.getCameraEntity();
		HitResult hit = client.player.getCrosshairTarget(
				client.getRenderTickCounter().getTickProgress(false), camera == null ? client.player : camera);
		return hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
	}

	private void startRecovery(long now) {
		phase = Phase.RECOVERING;
		restoreAtNanos = now + ns(75D + rng.nextDouble() * 125D);
		recoverUntilNanos = now + ns(270D + rng.nextDouble() * 260D);
		expireAtNanos = recoverUntilNanos + ns(100D);
	}

	private void selectSlot(MinecraftClient client, int slot) {
		if (slot < 0 || slot > 8 || client.player.getInventory().getSelectedSlot() == slot) return;
		client.player.getInventory().setSelectedSlot(slot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		}
	}

	private boolean allowed(MinecraftClient client) {
		if (!config.enabled || !config.subTiersAutoCreeper) return false;
		ClientPlayerEntity player = client == null ? null : client.player;
		return player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == null && player.isAlive() && !player.isSpectator();
	}

	private long ns(double ms) { return (long) (ms * 1_000_000D); }

	private void reset(MinecraftClient client, boolean restoreSlot) {
		if (restoreSlot && originalSlot >= 0 && client != null && client.player != null) {
			selectSlot(client, originalSlot);
		}
		phase = Phase.IDLE;
		spawnHint = null;
		creeperUuid = null;
		targetUuid = null;
		creepersPresentBeforePlacement.clear();
		swordSlot = -1;
		originalSlot = -1;
		actionAtNanos = 0L;
		expireAtNanos = 0L;
		settledSinceNanos = 0L;
		restoreAtNanos = 0L;
		recoverUntilNanos = 0L;
		aimError = Float.MAX_VALUE;
		rotation.reset();
	}
}
