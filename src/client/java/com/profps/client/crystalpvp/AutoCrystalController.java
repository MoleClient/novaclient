package com.profps.client.crystalpvp;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.security.SecureRandom;

/**
 * Hold right-click on obsidian to place a crystal and break it, over and over.
 *
 * <p>There is no state machine here, and that is the point. The previous version
 * tracked a base position, a pending entity id, a saved hotbar slot and five
 * phases with confirmation timeouts — and it switched your hotbar slot for you,
 * which is what produced towers of obsidian: holding right-click with obsidian
 * selected let vanilla place a block on every beat while the module was still
 * trying to arrange a crystal on the same spot.
 *
 * <p>What replaces it is the observation that the crosshair already carries all
 * the state needed. Look at obsidian and there is no crystal yet, so place one;
 * the crystal you just placed is now the thing under the crosshair, so break it;
 * breaking it puts the obsidian back under the crosshair. The alternation falls
 * out of what you are looking at, needs nothing remembered between actions, and
 * cannot desynchronise from the world.
 *
 * <p>It never changes your hotbar slot. Crystals have to be in hand, which is
 * both simpler and the actual fix for the obsidian towers: with anything else
 * held the module does nothing at all and your clicks stay your own.
 *
 * <p>Both actions are the ones vanilla sends for a real click — {@code
 * interactBlock} against the live ray, {@code attackEntity} on a crystal the ray
 * genuinely hits — spaced by a sampled interval rather than fired every tick, so
 * the rhythm is a fast player's rather than a machine's.
 */
public final class AutoCrystalController {
	private static final double REACH_SQUARED = 4.5D * 4.5D;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private long nextActionNanos;
	private String status = "Hold right click on obsidian";

	public AutoCrystalController(ProFPSConfig config) {
		this.config = config;
	}

	/**
	 * Kept so the module can stay registered on the use-block event, but it no
	 * longer intercepts anything: the loop below drives itself from the crosshair,
	 * and letting a real click through unchanged is what keeps the two in step.
	 */
	public ActionResult onUseBlock(net.minecraft.entity.player.PlayerEntity player, World world,
			Hand hand, BlockHitResult hit) {
		return ActionResult.PASS;
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			status = "Off";
			return;
		}
		ClientPlayerEntity player = client.player;
		if (!client.options.useKey.isPressed()) {
			status = "Hold right click on obsidian";
			return;
		}
		// Crystals in hand or nothing happens. Switching slots for you is what
		// let a held right-click spam whatever else was selected.
		Hand hand = crystalHand(player);
		if (hand == null) {
			status = "Hold end crystals";
			return;
		}
		long now = System.nanoTime();
		if (now < nextActionNanos) return;

		HitResult fresh = player.getCrosshairTarget(1.0F,
				client.getCameraEntity() == null ? player : client.getCameraEntity());

		// A crystal is in the way: that is the one just placed, so break it.
		if (fresh instanceof EntityHitResult entityHit
				&& entityHit.getEntity() instanceof EndCrystalEntity crystal && crystal.isAlive()) {
			if (player.getEyePos().squaredDistanceTo(crystal.getBoundingBox().getCenter()) > REACH_SQUARED) {
				status = "Crystal out of reach";
				return;
			}
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CRYSTAL)) return;
			client.interactionManager.attackEntity(player, crystal);
			player.swingHand(Hand.MAIN_HAND);
			nextActionNanos = now + actionGapNanos();
			status = "Breaking";
			return;
		}

		// Otherwise a clear base under the crosshair: place one.
		if (fresh instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK
				&& isCrystalBase(client.world, blockHit.getBlockPos())) {
			if (crystalOn(client, blockHit.getBlockPos()) != null) {
				// Occupied but not under the ray — aiming past it. Do not stack.
				status = "Crystal already there";
				return;
			}
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CRYSTAL)) return;
			ActionResult result = client.interactionManager.interactBlock(player, hand, blockHit);
			if (!result.isAccepted()) {
				status = "Placement refused";
				nextActionNanos = now + actionGapNanos();
				return;
			}
			player.swingHand(hand);
			nextActionNanos = now + actionGapNanos();
			status = "Placing";
			return;
		}
		status = "Aim at obsidian";
	}

	/**
	 * Gap before the next action, sampled every time. The speed setting sets the
	 * pace; the spread around it is what stops the stream being a metronome,
	 * which is the part of a fast rhythm that does not occur naturally.
	 */
	private long actionGapNanos() {
		int speed = MathHelper.clamp(config.autoCrystalSpeed, 1, 10);
		double base = 300.0D - (speed - 1) * 26.0D;      // 300ms at 1, ~66ms at 10
		double jittered = base * (0.82D + rng.nextDouble() * 0.36D);
		return (long) (jittered * 1_000_000D);
	}

	private EndCrystalEntity crystalOn(MinecraftClient client, BlockPos base) {
		var crystals = client.world.getEntitiesByClass(EndCrystalEntity.class,
				new Box(base.up()).expand(0.55D, 1.2D, 0.55D), Entity::isAlive);
		return crystals.isEmpty() ? null : crystals.get(0);
	}

	private boolean isCrystalBase(World world, BlockPos pos) {
		var state = world.getBlockState(pos);
		return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
	}

	private Hand crystalHand(ClientPlayerEntity player) {
		if (player.getMainHandStack().isOf(Items.END_CRYSTAL)) return Hand.MAIN_HAND;
		if (player.getOffHandStack().isOf(Items.END_CRYSTAL)) return Hand.OFF_HAND;
		return null;
	}

	public String status(MinecraftClient client) {
		return config.autoCrystal ? status : "Off";
	}

	private boolean allowed(MinecraftClient client) {
		return config.enabled && config.autoCrystal && client != null && client.player != null
				&& client.world != null && client.interactionManager != null && client.currentScreen == null
				&& client.isWindowFocused() && client.player.isAlive() && !client.player.isSpectator()
				&& client.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
	}
}
