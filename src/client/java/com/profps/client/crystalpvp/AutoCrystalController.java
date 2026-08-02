package com.profps.client.crystalpvp;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.List;

/** Quick manual crystal placement/break helper for obsidian and bedrock. */
public final class AutoCrystalController {
	private static final double REACH_SQUARED = 4.5D * 4.5D;
	private static final int CONFIRM_TIMEOUT_TICKS = 20;
	private final ProFPSConfig config;

	private Phase phase = Phase.IDLE;
	private BlockPos basePos;
	private int pendingCrystalId = -1;
	private int previousSlot = -1;
	private int deadlineTick;
	private boolean useReleased = true;
	private boolean selfInteracting;
	private String status = "Aim at obsidian";

	public AutoCrystalController(ProFPSConfig config) {
		this.config = config;
	}

	/** Records the real crystal click, then lets vanilla place it exactly once. */
	public ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
		if (!config.autoCrystal || !world.isClient() || selfInteracting || player == null
				|| !player.getStackInHand(hand).isOf(Items.END_CRYSTAL)) return ActionResult.PASS;
		if (!isCrystalBase(world, hit.getBlockPos())) return ActionResult.PASS;
		if (phase != Phase.IDLE) return ActionResult.FAIL;

		begin(player, hit.getBlockPos());
		phase = Phase.WAIT_CRYSTAL;
		status = "Confirming crystal";
		return ActionResult.PASS;
	}

	public void tick(MinecraftClient client) {
		if (!allowed(client)) {
			reset(client, true);
			return;
		}

		ClientPlayerEntity player = client.player;
		if (!client.options.useKey.isPressed()) useReleased = true;
		if (phase != Phase.IDLE && player.age > deadlineTick) {
			reset(client, true);
			status = "Timed out";
			return;
		}

		switch (phase) {
			case IDLE -> startFromHeldClick(client, player);
			case PLACE_CRYSTAL -> placeCrystal(client, player);
			case WAIT_CRYSTAL -> waitCrystal(client, player);
			case BREAK_CRYSTAL -> breakCrystal(client, player);
			case WAIT_BREAK -> waitBreak(client);
		}
	}

	private void startFromHeldClick(MinecraftClient client, ClientPlayerEntity player) {
		if (!client.options.useKey.isPressed() || !useReleased) {
			status = "Aim at obsidian";
			return;
		}
		HitResult fresh = freshHit(client, player);
		if (!(fresh instanceof BlockHitResult hit) || !isCrystalBase(client.world, hit.getBlockPos())) {
			status = "Aim at obsidian";
			return;
		}

		begin(player, hit.getBlockPos());
		EndCrystalEntity existing = crystalAbove(client, basePos);
		if (existing != null) {
			pendingCrystalId = existing.getId();
			phase = Phase.BREAK_CRYSTAL;
			status = "Breaking crystal";
		} else {
			phase = Phase.PLACE_CRYSTAL;
			status = "Placing crystal";
		}
	}

	private void begin(PlayerEntity player, BlockPos base) {
		if (previousSlot < 0) previousSlot = player.getInventory().getSelectedSlot();
		basePos = base.toImmutable();
		pendingCrystalId = -1;
		deadlineTick = player.age + CONFIRM_TIMEOUT_TICKS;
		useReleased = false;
	}

	private void placeCrystal(MinecraftClient client, ClientPlayerEntity player) {
		if (basePos == null || !isCrystalBase(client.world, basePos)) {
			reset(client, true);
			status = "Aim at obsidian";
			return;
		}
		EndCrystalEntity existing = crystalAbove(client, basePos);
		if (existing != null) {
			pendingCrystalId = existing.getId();
			phase = Phase.BREAK_CRYSTAL;
			breakCrystal(client, player);
			return;
		}

		HitResult fresh = freshHit(client, player);
		if (!(fresh instanceof BlockHitResult hit) || !hit.getBlockPos().equals(basePos)) {
			status = "Keep aim on obsidian";
			return;
		}
		Hand hand = crystalHand(player);
		if (hand == null) {
			int slot = findCrystalSlot(player);
			if (slot < 0) {
				reset(client, true);
				status = "No crystals";
				return;
			}
			selectSlot(client, slot);
			hand = Hand.MAIN_HAND;
		}
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CRYSTAL)) return;
		ActionResult result;
		selfInteracting = true;
		try {
			result = client.interactionManager.interactBlock(player, hand, hit);
		} finally {
			selfInteracting = false;
		}
		if (!result.isAccepted()) {
			status = "Crystal refused";
			return;
		}
		player.swingHand(hand);
		phase = Phase.WAIT_CRYSTAL;
		deadlineTick = player.age + CONFIRM_TIMEOUT_TICKS;
		status = "Confirming crystal";
	}

	private void waitCrystal(MinecraftClient client, ClientPlayerEntity player) {
		EndCrystalEntity crystal = crystalAbove(client, basePos);
		if (crystal == null) {
			status = "Confirming crystal";
			return;
		}
		pendingCrystalId = crystal.getId();
		phase = Phase.BREAK_CRYSTAL;
		deadlineTick = player.age + CONFIRM_TIMEOUT_TICKS;
		breakCrystal(client, player);
	}

	private void breakCrystal(MinecraftClient client, ClientPlayerEntity player) {
		EndCrystalEntity crystal = pendingCrystal(client);
		if (crystal == null) {
			reset(client, true);
			status = "Ready";
			return;
		}
		if (player.getEyePos().squaredDistanceTo(crystal.getBoundingBox().getCenter()) > REACH_SQUARED) {
			status = "Crystal out of reach";
			return;
		}
		HitResult fresh = freshHit(client, player);
		if (config.autoCrystalStrictRay
				&& (!(fresh instanceof EntityHitResult entityHit) || entityHit.getEntity() != crystal)) {
			status = "Keep aim on crystal";
			return;
		}
		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.AUTO_CRYSTAL)) return;
		client.crosshairTarget = fresh;
		client.interactionManager.attackEntity(player, crystal);
		player.swingHand(Hand.MAIN_HAND);
		phase = Phase.WAIT_BREAK;
		deadlineTick = player.age + CONFIRM_TIMEOUT_TICKS;
		status = "Confirming break";
	}

	private void waitBreak(MinecraftClient client) {
		EndCrystalEntity crystal = pendingCrystal(client);
		if (crystal != null) {
			status = "Confirming break";
			return;
		}
		reset(client, true);
		status = "Ready";
	}

	private EndCrystalEntity pendingCrystal(MinecraftClient client) {
		Entity entity = pendingCrystalId < 0 ? null : client.world.getEntityById(pendingCrystalId);
		if (entity instanceof EndCrystalEntity crystal && crystal.isAlive()) return crystal;
		return crystalAbove(client, basePos);
	}

	private EndCrystalEntity crystalAbove(MinecraftClient client, BlockPos base) {
		if (base == null) return null;
		List<EndCrystalEntity> crystals = client.world.getEntitiesByClass(EndCrystalEntity.class,
				new Box(base.up()).expand(0.75D, 1.25D, 0.75D), Entity::isAlive);
		return crystals.isEmpty() ? null : crystals.get(0);
	}

	private HitResult freshHit(MinecraftClient client, ClientPlayerEntity player) {
		Entity camera = client.getCameraEntity();
		return player.getCrosshairTarget(1.0F, camera == null ? player : camera);
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

	private int findCrystalSlot(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getStack(slot).isOf(Items.END_CRYSTAL)) return slot;
		}
		return -1;
	}

	private void selectSlot(MinecraftClient client, int slot) {
		if (slot < 0 || slot > 8 || client.player.getInventory().getSelectedSlot() == slot) return;
		client.player.getInventory().setSelectedSlot(slot);
		client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	private void reset(MinecraftClient client, boolean restoreSlot) {
		if (restoreSlot && previousSlot >= 0 && client != null && client.player != null
				&& client.interactionManager != null) selectSlot(client, previousSlot);
		phase = Phase.IDLE;
		basePos = null;
		pendingCrystalId = -1;
		previousSlot = -1;
		deadlineTick = 0;
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

	private enum Phase {
		IDLE, PLACE_CRYSTAL, WAIT_CRYSTAL, BREAK_CRYSTAL, WAIT_BREAK
	}
}
