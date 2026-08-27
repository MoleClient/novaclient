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

/** Places and breaks end crystals while right-click is held, driven entirely by the crosshair target. */
public final class AutoCrystalController {
	private static final double REACH_SQUARED = 4.5D * 4.5D;

	private final ProFPSConfig config;
	private final SecureRandom rng = new SecureRandom();
	private long nextActionNanos;
	private String status = "Hold right click on obsidian";

	public AutoCrystalController(ProFPSConfig config) {
		this.config = config;
	}

	/** Keeps the module registered on the use-block event without intercepting it. */
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
		// Requires crystals already in hand; the hotbar slot is never changed.
		Hand hand = crystalHand(player);
		if (hand == null) {
			status = "Hold end crystals";
			return;
		}
		long now = System.nanoTime();
		if (now < nextActionNanos) return;

		HitResult fresh = player.getCrosshairTarget(1.0F,
				client.getCameraEntity() == null ? player : client.getCameraEntity());

		// A crystal under the ray is the one just placed, so break it.
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

		// Otherwise a clear base under the crosshair: place a crystal.
		if (fresh instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK
				&& isCrystalBase(client.world, blockHit.getBlockPos())) {
			if (crystalOn(client, blockHit.getBlockPos()) != null) {
				// Occupied but not under the ray; do not stack.
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

	/** Jittered gap before the next action, derived from the configured speed. */
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
