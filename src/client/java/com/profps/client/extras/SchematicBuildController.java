package com.profps.client.extras;

import com.profps.client.combatmode.CombatModeRuntime;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.donutsmp.FreecamController;
import com.profps.client.donutsmp.TunnelController;
import com.profps.client.mixin.BlockItemInvoker;
import com.profps.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.Set;

/**
 * Places the schematic cell under the crosshair while the player does the aiming and the
 * walking. Sources are a Remember capture or an enabled Litematica placement.
 *
 * <p>Every placement is an ordinary click: the crosshair ray picks the cell, the material is
 * switched to with a real slot-change packet a tick before the use, the placement is verified
 * against vanilla's own placement prediction, and the use goes through {@code interactBlock}
 * from the same pre-movement phase vanilla handles a click in, so the packet order matches a
 * hand-placed block. Placements pace at vanilla's use cadence with jitter; nothing here
 * outruns a held right click.
 */
public final class SchematicBuildController {
	/** Vanilla block interaction range. */
	private static final double MAX_REACH = 4.5D;
	private static final double MAX_REACH_SQUARED = MAX_REACH * MAX_REACH;
	/** How long a just-placed cell is left alone while the server confirms it. */
	private static final long RECENT_POSITION_NS = 900_000_000L;
	/** Vanilla holds 4 ticks between uses; the floor sits just above it. */
	private static final int PLACE_GAP_MIN_MS = 205;
	private static final int PLACE_GAP_MAX_MS = 285;

	/** State properties a placement must reproduce for the cell to count as built. */
	private static final Set<String> PLACEMENT_PROPERTIES = Set.of(
			"facing", "horizontal_facing", "axis", "rotation", "half", "type", "shape",
			"hinge", "attachment", "face", "orientation", "layers", "candles", "pickles",
			"eggs", "open", "mode", "delay", "note", "inverted");
	private static final Set<String> STACKING_PROPERTIES = Set.of("layers", "candles", "pickles", "eggs");
	private static final Set<String> INTERACTION_PROPERTIES = Set.of("open", "mode", "delay", "note", "inverted");

	private final ProFPSConfig config;
	private final RememberController remember;
	private final Random random = new Random();
	private final LitematicaBridge litematica = new LitematicaBridge();

	private HoverTarget hoverTarget;
	private long hoverReadyNanos;
	private long nextPlaceNanos;
	private long pullSettleNanos;
	private int slotReadyAge;
	private BlockPos recentPosition;
	private long recentPositionUntil;

	public SchematicBuildController(ProFPSConfig config, RememberController remember) {
		this.config = config;
		this.remember = remember;
	}

	/**
	 * Runs at the tail of {@code handleInputEvents} via the pre-movement dispatch, the phase
	 * vanilla processes a real click in, so the use packet precedes this tick's movement.
	 */
	public void tickPreMovement(MinecraftClient client) {
		if (!ready(client)) {
			resetHover();
			return;
		}
		ClientPlayerEntity player = client.player;
		// The player's own clicks always win, and a detached or module-owned camera is not
		// the player's aim.
		if (client.options.attackKey.isPressed() || client.options.useKey.isPressed()
				|| player.isUsingItem() || FreecamController.isActive()
				|| TunnelController.isControlling()) {
			resetHover();
			return;
		}

		long now = System.nanoTime();
		HoverTarget target = targetUnderCrosshair(client, player, now);
		if (target == null) {
			resetHover();
			return;
		}
		if (!sameTarget(target, hoverTarget)) {
			hoverTarget = target;
			// A short sight-line settle before the click; a hand does not fire the same
			// frame the crosshair arrives.
			hoverReadyNanos = now + jitterMs(60, 140);
		}
		if (now < hoverReadyNanos || now < nextPlaceNanos || now < pullSettleNanos) return;

		// The material is pulled to the hotbar as its own claimed action, never as a side
		// effect of scanning, and placement waits for the shuffle to settle.
		if (target.hotbarSlot() < 0) {
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.SCHEMATIC_BUILD)) return;
			if (!pullMaterial(client, target.desired())) {
				resetHover();
				return;
			}
			pullSettleNanos = now + jitterMs(180, 320);
			return;
		}

		// A real slot change: local state, the slot packet, and vanilla's sync kept in step.
		// The use goes out on a later tick, the way a scroll precedes a click.
		if (player.getInventory().getSelectedSlot() != target.hotbarSlot()) {
			if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.SCHEMATIC_BUILD)) return;
			selectSlot(client, player, target.hotbarSlot());
			slotReadyAge = player.age + 1;
			return;
		}
		if (player.age < slotReadyAge) return;

		HoverTarget live = targetUnderCrosshair(client, player, now);
		if (!sameTarget(target, live)) {
			resetHover();
			return;
		}

		if (!CombatModeRuntime.tryClaim(CombatModeRuntime.ActionOwner.SCHEMATIC_BUILD)) return;
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, live.hit());
		if (!result.isAccepted()) {
			hoverReadyNanos = now + jitterMs(120, 260);
			return;
		}
		player.swingHand(Hand.MAIN_HAND);
		long gap = jitterMs(PLACE_GAP_MIN_MS, PLACE_GAP_MAX_MS);
		// The occasional longer hesitation of a hand re-checking its line.
		if (random.nextInt(100) < 7) gap += jitterMs(140, 420);
		nextPlaceNanos = now + gap;
		recentPosition = live.placeAt();
		recentPositionUntil = now + RECENT_POSITION_NS;
		resetHover();
	}

	// ── Targeting ──────────────────────────────────────────────────────────────

	private HoverTarget targetUnderCrosshair(MinecraftClient client, ClientPlayerEntity player, long now) {
		HitResult raw = player.raycast(MAX_REACH, 1.0F, false);
		if (!(raw instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
		if (player.getEyePos().squaredDistanceTo(hit.getPos()) > MAX_REACH_SQUARED) return null;
		BlockState supportState = client.world.getBlockState(hit.getBlockPos());
		BlockPos placeAt = (supportState.isReplaceable() ? hit.getBlockPos()
				: hit.getBlockPos().offset(hit.getSide())).toImmutable();
		if (recentPosition != null && recentPosition.equals(placeAt) && now < recentPositionUntil) return null;
		BlockState desired = remember.desiredStateAt(placeAt);
		if (desired == null || desired.isAir()) desired = litematica.stateAt(placeAt);
		if (desired == null || desired.isAir()) return null;
		return placementForHit(client, player, hit, placeAt, desired);
	}

	/**
	 * Verifies the click would produce the desired block, using vanilla's own placement
	 * prediction. Scanning is read-only: with the material not yet on the hotbar this returns
	 * a pull request, and the tick loop does the moving as a claimed action.
	 */
	private HoverTarget placementForHit(MinecraftClient client, ClientPlayerEntity player, BlockHitResult hit,
			BlockPos expectedPos, BlockState desired) {
		Item item = desired.getBlock().asItem();
		if (item == Items.AIR) return null;
		int slot = findBlockSlot(player, desired);
		BlockHitResult copy = new BlockHitResult(hit.getPos(), hit.getSide(), hit.getBlockPos().toImmutable(), hit.isInsideBlock());
		if (slot < 0) {
			if (!materialPullable(client, item)) return null;
			ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, new ItemStack(item), hit);
			if (!context.getBlockPos().equals(expectedPos) || !context.canPlace()) return null;
			return new HoverTarget(expectedPos, copy, -1, desired);
		}
		ItemStack stack = player.getInventory().getStack(slot);
		if (!(stack.getItem() instanceof BlockItem blockItem) || blockItem.getBlock() != desired.getBlock()) return null;
		ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
		BlockPos placeAt = context.getBlockPos().toImmutable();
		if (!context.canPlace()) return null;
		BlockState predicted = ((BlockItemInvoker) blockItem).profps$getPlacementState(context);
		BlockState current = client.world.getBlockState(placeAt);
		if (!placeAt.equals(expectedPos) || predicted == null || !placementMatches(desired, predicted, current)
				|| !((BlockItemInvoker) blockItem).profps$canPlace(context, predicted)) return null;
		return new HoverTarget(placeAt, copy, slot, desired);
	}

	// ── Materials ──────────────────────────────────────────────────────────────

	private int findBlockSlot(ClientPlayerEntity player, BlockState desired) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
					&& blockItem.getBlock() == desired.getBlock()) return slot;
		}
		return -1;
	}

	/** Read-only availability check; scanning must never mutate the inventory. */
	private boolean materialPullable(MinecraftClient client, Item item) {
		if (findInventoryItemSlot(client.player, item) >= 0) return true;
		if (!client.player.isCreative()) return false;
		return findEmptyHotbarSlot(client.player) >= 0 || findEmptyInventorySlot(client.player) >= 0;
	}

	/** Moves the material onto the hotbar, from the main inventory or a creative lease. */
	private boolean pullMaterial(MinecraftClient client, BlockState desired) {
		Item item = desired.getBlock().asItem();
		if (item == Items.AIR) return false;
		int inventorySlot = findInventoryItemSlot(client.player, item);
		if (inventorySlot >= 0) return moveInventoryItemToHotbar(client, inventorySlot) >= 0;
		if (!client.player.isCreative()) return false;

		int emptyHotbar = findEmptyHotbarSlot(client.player);
		if (emptyHotbar >= 0) {
			ItemStack stack = new ItemStack(item, 64);
			client.interactionManager.clickCreativeStack(stack, 36 + emptyHotbar);
			client.player.getInventory().setStack(emptyHotbar, stack);
			return true;
		}

		// A full hotbar can still lease a material created in an empty main inventory slot;
		// SWAP preserves the displaced selected-slot stack.
		int emptyInventory = findEmptyInventorySlot(client.player);
		if (emptyInventory >= 0) {
			ItemStack stack = new ItemStack(item, 64);
			client.interactionManager.clickCreativeStack(stack, emptyInventory);
			client.player.getInventory().setStack(emptyInventory, stack);
			return moveInventoryItemToHotbar(client, emptyInventory) >= 0;
		}
		return false;
	}

	private int findInventoryItemSlot(ClientPlayerEntity player, Item item) {
		for (int slot = 9; slot < 36; slot++) {
			if (player.getInventory().getStack(slot).isOf(item)) return slot;
		}
		return -1;
	}

	private int findEmptyHotbarSlot(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		return -1;
	}

	private int findEmptyInventorySlot(ClientPlayerEntity player) {
		for (int slot = 9; slot < 36; slot++) {
			if (player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		return -1;
	}

	/**
	 * Uses the vanilla player-handler SWAP action. With a full hotbar the current slot is
	 * leased: its stack moves into the source inventory slot, so nothing is destroyed.
	 */
	private int moveInventoryItemToHotbar(MinecraftClient client, int inventorySlot) {
		int hotbar = findEmptyHotbarSlot(client.player);
		if (hotbar < 0) hotbar = client.player.getInventory().getSelectedSlot();
		client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId,
				inventorySlot, hotbar, SlotActionType.SWAP, client.player);
		return hotbar;
	}

	/** Sets the slot locally and sends the packet, keeping vanilla's slot sync in step. */
	private void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
		player.getInventory().setSelectedSlot(slot);
		player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		((ClientPlayerInteractionManagerAccessor) client.interactionManager).profps$setLastSelectedSlot(slot);
	}

	// ── State property matching ────────────────────────────────────────────────

	private boolean placementMatches(BlockState desired, BlockState predicted, BlockState current) {
		if (desired.getBlock() != predicted.getBlock()) return false;
		for (Property<?> property : desired.getProperties()) {
			String name = property.getName();
			if (!PLACEMENT_PROPERTIES.contains(name) || !predictionControlsProperty(desired, name)
					|| sameProperty(desired, predicted, property)) continue;
			if (!isPlacementProgress(current, desired, predicted, name)) return false;
		}
		return true;
	}

	private boolean predictionControlsProperty(BlockState desired, String name) {
		if (name.equals("shape") || INTERACTION_PROPERTIES.contains(name)) return false;
		return !name.equals("type") || (!desired.isOf(Blocks.CHEST) && !desired.isOf(Blocks.TRAPPED_CHEST));
	}

	private boolean isPlacementProgress(BlockState current, BlockState desired, BlockState predicted, String name) {
		if (STACKING_PROPERTIES.contains(name)) {
			int before = integerProperty(current, name, 0);
			int after = integerProperty(predicted, name, -1);
			int goal = integerProperty(desired, name, -1);
			return after > before && after <= goal;
		}
		return name.equals("type") && propertyValue(desired, "type").equals("double")
				&& current.getBlock() != desired.getBlock();
	}

	private int integerProperty(BlockState state, String name, int fallback) {
		try {
			String value = propertyValue(state, name);
			return value.isEmpty() ? fallback : Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private String propertyValue(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) return String.valueOf(state.get(property));
		}
		return "";
	}

	private <T extends Comparable<T>> boolean sameProperty(BlockState first, BlockState second, Property<T> property) {
		return first.get(property).equals(second.get(property));
	}

	// ── Plumbing ───────────────────────────────────────────────────────────────

	private boolean ready(MinecraftClient client) {
		return config.enabled && config.schematicBuildEnabled && client != null && client.player != null
				&& client.world != null && client.interactionManager != null && client.currentScreen == null
				&& client.player.isAlive() && !client.player.isSpectator() && !client.player.hasVehicle()
				&& !client.player.isGliding();
	}

	private long jitterMs(int minimum, int maximum) {
		return (minimum + random.nextInt(maximum - minimum + 1)) * 1_000_000L;
	}

	private boolean sameTarget(HoverTarget first, HoverTarget second) {
		return first != null && second != null
				&& first.placeAt().equals(second.placeAt())
				&& first.hit().getBlockPos().equals(second.hit().getBlockPos())
				&& first.hit().getSide() == second.hit().getSide()
				&& first.hotbarSlot() == second.hotbarSlot()
				&& first.desired().equals(second.desired());
	}

	private void resetHover() {
		hoverTarget = null;
		hoverReadyNanos = 0L;
	}

	private record HoverTarget(BlockPos placeAt, BlockHitResult hit, int hotbarSlot, BlockState desired) {}

	/** Optional integration: absent or changing Litematica simply yields no source. */
	private static final class LitematicaBridge {
		private Method worldGetter;
		private boolean initialized;

		BlockState stateAt(BlockPos pos) {
			if (!initialize()) return null;
			try {
				Object schematicWorld = worldGetter.invoke(null);
				if (schematicWorld instanceof BlockView view) return view.getBlockState(pos);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// A placement reload can briefly make the schematic world unavailable.
			}
			return null;
		}

		private boolean initialize() {
			if (initialized) return worldGetter != null;
			initialized = true;
			if (!FabricLoader.getInstance().isModLoaded("litematica")) return false;
			try {
				Class<?> handler = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
				worldGetter = handler.getMethod("getSchematicWorld");
			} catch (ReflectiveOperationException | LinkageError ignored) {
				worldGetter = null;
			}
			return worldGetter != null;
		}
	}
}
