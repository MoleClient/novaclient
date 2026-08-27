package com.profps.client.inventory;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

/**
 * Serialized slot-click queue shared by AutoArmor, ChestSteal, Refill, AutoHotbar and InvCleaner.
 * Only one owner runs at a time and every click is validated against the live sync id.
 */
public final class InventoryAutomationController {
	private final ProFPSConfig config;
	private final Random random = new Random();
	private final Queue<Click> clicks = new ArrayDeque<>();
	private String owner = "";
	private int queueSyncId = -1;
	private int delayMinMs;
	private int delayMaxMs;
	private long nextClickNanos;
	private boolean closeWhenDone;
	private boolean openedInventory;
	private int processedContainerSync = -1;
	private long lastCombatNanos;

	public InventoryAutomationController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!usable(client)) {
			reset(client, false);
			return;
		}
		if (client.player.hurtTime > 0) lastCombatNanos = System.nanoTime();
		if (!clicks.isEmpty() || !owner.isEmpty()) {
			processQueue(client);
			return;
		}

		if (client.currentScreen instanceof GenericContainerScreen) {
			if (config.inventoryChestSteal) beginChestSteal(client);
			return;
		}
		processedContainerSync = -1;

		if (config.inventoryRefillRequested) {
			config.inventoryRefillRequested = false;
			beginRefill(client);
			return;
		}
		if (config.inventoryCleanerRequested) {
			config.inventoryCleanerRequested = false;
			beginCleaner(client);
			return;
		}
		if (config.inventoryAutoArmor && beginAutoArmor(client)) return;
		if (config.inventoryAutoHotbar) beginAutoHotbar(client);
	}

	private void processQueue(MinecraftClient client) {
		if (client.player.currentScreenHandler.syncId != queueSyncId) {
			reset(client, false);
			return;
		}
		if (clicks.isEmpty()) {
			finish(client);
			return;
		}
		long now = System.nanoTime();
		if (now < nextClickNanos) return;
		Click click = clicks.poll();
		if (click.slot >= -999 && click.slot < client.player.currentScreenHandler.slots.size()) {
			client.interactionManager.clickSlot(queueSyncId, click.slot, click.button, click.type, client.player);
		}
		nextClickNanos = now + randomDelayNanos(delayMinMs, delayMaxMs);
	}

	private boolean beginAutoArmor(MinecraftClient client) {
		if (client.player.isGliding()) return false;
		if (config.inventoryAutoArmorCombatCheck
				&& System.nanoTime() - lastCombatNanos < 1_000_000_000L) return false;
		if (config.inventoryAutoArmorOnly && !(client.currentScreen instanceof InventoryScreen)) {
			if (!config.inventoryAutoArmorOpen || client.currentScreen != null) return false;
		}

		ArmorUpgrade upgrade = bestArmorUpgrade(client.player);
		if (upgrade == null) return false;
		if (client.currentScreen == null && config.inventoryAutoArmorOpen) {
			client.setScreen(new InventoryScreen(client.player));
			openedInventory = true;
		}
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) return false;

		List<Click> actions = new ArrayList<>();
		int armorScreen = armorScreenSlot(upgrade.slot);
		if (!client.player.getEquippedStack(upgrade.slot).isEmpty()) {
			actions.add(new Click(armorScreen, config.inventoryAutoArmorDropEquipped ? 1 : 0,
					config.inventoryAutoArmorDropEquipped ? SlotActionType.THROW : SlotActionType.QUICK_MOVE));
		}
		actions.add(new Click(invToScreen(upgrade.inventorySlot), 0, SlotActionType.QUICK_MOVE));
		start(client, "AutoArmor", config.inventoryAutoArmorDelayMinMs,
				config.inventoryAutoArmorDelayMaxMs, actions, openedInventory);
		return true;
	}

	private ArmorUpgrade bestArmorUpgrade(ClientPlayerEntity player) {
		ArmorUpgrade best = null;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			double equipped = InventoryItemScorer.armorScore(player.getEquippedStack(slot), slot,
					config.inventoryAutoArmorDurability);
			int candidate = -1;
			double candidateScore = equipped;
			for (int inv = 0; inv < 36; inv++) {
				ItemStack stack = player.getInventory().getStack(inv);
				double score = InventoryItemScorer.armorScore(stack, slot,
						config.inventoryAutoArmorDurability);
				if (score > candidateScore + 1.0E-6D) {
					candidateScore = score;
					candidate = inv;
				}
			}
			if (candidate >= 0 && (best == null || candidateScore - equipped > best.improvement)) {
				best = new ArmorUpgrade(slot, candidate, candidateScore - equipped);
			}
		}
		return best;
	}

	private void beginChestSteal(MinecraftClient client) {
		ScreenHandler handler = client.player.currentScreenHandler;
		if (handler.syncId == processedContainerSync) return;
		if (config.inventoryChestStealCheckMenu && looksLikeMenu(client.currentScreen.getTitle().getString())) {
			processedContainerSync = handler.syncId;
			return;
		}
		int containerSlots = Math.max(0, handler.slots.size() - 36);
		List<Integer> eligible = new ArrayList<>();
		for (int slot = 0; slot < containerSlots; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty() || blacklisted(stack, config.inventoryChestStealBlacklist)
					|| InventoryItemScorer.isHarmfulSplashPotion(stack)) continue;
			if (config.inventoryChestStealBestOnly && !isInventoryUpgrade(client.player, stack)) continue;
			eligible.add(slot);
		}
		if (config.inventoryChestStealShuffle) Collections.shuffle(eligible, random);
		List<Click> actions = eligible.stream()
				.map(slot -> new Click(slot, 0, SlotActionType.QUICK_MOVE)).toList();
		processedContainerSync = handler.syncId;
		start(client, "ChestSteal", config.inventoryChestStealDelayMinMs,
				config.inventoryChestStealDelayMaxMs, actions, !config.inventoryChestStealKeepOpen);
	}

	private boolean isInventoryUpgrade(ClientPlayerEntity player, ItemStack candidate) {
		EquipmentSlot armorSlot = InventoryItemScorer.equipmentSlot(candidate);
		if (armorSlot != null) {
			double candidateScore = InventoryItemScorer.armorScore(candidate, armorSlot, true);
			double best = InventoryItemScorer.armorScore(player.getEquippedStack(armorSlot), armorSlot, true);
			for (int i = 0; i < 36; i++) best = Math.max(best,
					InventoryItemScorer.armorScore(player.getInventory().getStack(i), armorSlot, true));
			return candidateScore > best;
		}
		if (InventoryItemScorer.isWeapon(candidate)) {
			double best = 1.0D;
			for (int i = 0; i < 36; i++) best = Math.max(best,
					InventoryItemScorer.weaponScore(player.getInventory().getStack(i)));
			return InventoryItemScorer.weaponScore(candidate) > best;
		}
		return true;
	}

	private void beginRefill(MinecraftClient client) {
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) return;
		if (client.currentScreen == null) {
			client.setScreen(new InventoryScreen(client.player));
			openedInventory = true;
		}
		boolean pots = config.inventoryRefillType != 2;
		boolean soup = config.inventoryRefillType != 1;
		List<Integer> sources = new ArrayList<>();
		for (int inv = 9; inv < 36; inv++) {
			if (InventoryItemScorer.isHealing(client.player.getInventory().getStack(inv), pots, soup)) sources.add(inv);
		}
		if (config.inventoryRefillVertical) sources.sort(Comparator.comparingInt(i -> (i % 9) * 4 + i / 9));
		if (config.inventoryRefillScatter) Collections.shuffle(sources, random);
		List<Integer> targets = new ArrayList<>();
		for (int hotbar = 0; hotbar < 9; hotbar++) {
			ItemStack stack = client.player.getInventory().getStack(hotbar);
			if (stack.isEmpty() || (config.inventoryRefillHotbarClear && isRefillJunk(stack))) targets.add(hotbar);
		}
		List<Click> actions = new ArrayList<>();
		for (int i = 0; i < Math.min(sources.size(), targets.size()); i++) {
			actions.add(new Click(invToScreen(sources.get(i)), targets.get(i), SlotActionType.SWAP));
		}
		start(client, "Refill", config.inventoryRefillDelayMinMs,
				config.inventoryRefillDelayMaxMs, actions, true);
	}

	private boolean isRefillJunk(ItemStack stack) {
		if (InventoryItemScorer.isHealing(stack, true, true)) return false;
		return !config.inventoryRefillAllowedItems.contains(InventoryItemScorer.itemId(stack));
	}

	private void beginAutoHotbar(MinecraftClient client) {
		if (client.currentScreen != null) return;
		List<Click> actions = new ArrayList<>();
		Set<Integer> usedTargets = new HashSet<>();
		queueBestWeaponRule(client.player, actions, usedTargets, config.inventoryAutoHotbarWeaponSlot - 1);
		queueHotbarRule(client.player, actions, usedTargets, config.inventoryAutoHotbarBlocksSlot - 1,
				stack -> InventoryItemScorer.isBlock(stack));
		queueHotbarRule(client.player, actions, usedTargets, config.inventoryAutoHotbarHealSlot - 1,
				stack -> InventoryItemScorer.isHealing(stack, true, true));
		queueHotbarRule(client.player, actions, usedTargets, config.inventoryAutoHotbarPearlSlot - 1,
				stack -> stack.isOf(Items.ENDER_PEARL));
		if (!actions.isEmpty()) start(client, "AutoHotbar", config.inventoryAutoHotbarDelayMs,
				config.inventoryAutoHotbarDelayMs, List.of(actions.get(0)), false);
	}

	private void queueHotbarRule(ClientPlayerEntity player, List<Click> actions, Set<Integer> used,
			int target, java.util.function.Predicate<ItemStack> predicate) {
		if (target < 0 || target > 8 || !used.add(target) || predicate.test(player.getInventory().getStack(target))) return;
		int source = -1;
		for (int inv = 9; inv < 36; inv++) if (predicate.test(player.getInventory().getStack(inv))) { source = inv; break; }
		if (source >= 0) actions.add(new Click(invToScreen(source), target, SlotActionType.SWAP));
	}

	private void queueBestWeaponRule(ClientPlayerEntity player, List<Click> actions,
			Set<Integer> used, int target) {
		if (target < 0 || target > 8 || !used.add(target)) return;
		int best = -1;
		double bestScore = 1.0D;
		for (int inv = 0; inv < 36; inv++) {
			ItemStack stack = player.getInventory().getStack(inv);
			if (!InventoryItemScorer.isWeapon(stack)) continue;
			double score = InventoryItemScorer.weaponScore(stack);
			if (score > bestScore) { bestScore = score; best = inv; }
		}
		if (best >= 0 && best != target) actions.add(new Click(invToScreen(best), target, SlotActionType.SWAP));
	}

	private void beginCleaner(MinecraftClient client) {
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) return;
		if (client.currentScreen == null) {
			client.setScreen(new InventoryScreen(client.player));
			openedInventory = true;
		}
		List<Click> actions = new ArrayList<>();
		for (int inv = 0; inv < 36; inv++) {
			ItemStack stack = client.player.getInventory().getStack(inv);
			if (!stack.isEmpty() && isJunk(stack)) actions.add(new Click(invToScreen(inv), 1, SlotActionType.THROW));
		}
		start(client, "InvCleaner", config.inventoryCleanerDelayMs,
				config.inventoryCleanerDelayMs, actions, true);
	}

	private boolean isJunk(ItemStack stack) {
		if (InventoryItemScorer.equipmentSlot(stack) != null || InventoryItemScorer.isWeapon(stack)) return false;
		if (config.inventoryCleanerKeepBlocks && InventoryItemScorer.isBlock(stack)) return false;
		if (config.inventoryCleanerKeepFood && InventoryItemScorer.isFood(stack)) return false;
		if (config.inventoryCleanerKeepTools && InventoryItemScorer.isTool(stack)) return false;
		if (config.inventoryCleanerKeepPotions && (stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION)
				|| stack.isOf(Items.LINGERING_POTION))) return false;
		if (stack.isOf(Items.ENDER_PEARL) || stack.isOf(Items.TOTEM_OF_UNDYING)
				|| stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)
				|| stack.isOf(Items.ARROW) || stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW)) return false;
		String id = InventoryItemScorer.itemId(stack);
		return id.endsWith("rotten_flesh") || id.endsWith("poisonous_potato") || id.endsWith("bowl")
				|| id.endsWith("stick") || id.endsWith("string") || id.endsWith("spider_eye")
				|| id.endsWith("bone") || id.endsWith("feather") || id.endsWith("flint");
	}

	private void start(MinecraftClient client, String owner, int minDelay, int maxDelay,
			List<Click> actions, boolean close) {
		this.owner = owner;
		this.queueSyncId = client.player.currentScreenHandler.syncId;
		this.delayMinMs = Math.max(0, minDelay);
		this.delayMaxMs = Math.max(this.delayMinMs, maxDelay);
		this.clicks.addAll(actions);
		this.closeWhenDone = close;
		this.nextClickNanos = System.nanoTime() + randomDelayNanos(this.delayMinMs, this.delayMaxMs);
		if (actions.isEmpty()) finish(client);
	}

	private void finish(MinecraftClient client) {
		boolean shouldClose = closeWhenDone && client.currentScreen != null;
		clicks.clear();
		owner = "";
		queueSyncId = -1;
		closeWhenDone = false;
		if (shouldClose) {
			client.player.closeHandledScreen();
			client.setScreen(null);
		}
		openedInventory = false;
	}

	private void reset(MinecraftClient client, boolean close) {
		clicks.clear();
		owner = "";
		queueSyncId = -1;
		if (close && openedInventory && client != null && client.player != null) {
			client.player.closeHandledScreen();
			client.setScreen(null);
		}
		openedInventory = false;
		closeWhenDone = false;
	}

	private boolean usable(MinecraftClient client) {
		return config.enabled && client != null && client.player != null && client.world != null
				&& client.interactionManager != null && client.player.isAlive() && !client.player.isSpectator();
	}

	private boolean blacklisted(ItemStack stack, List<String> blacklist) {
		return blacklist.contains(InventoryItemScorer.itemId(stack));
	}

	private boolean looksLikeMenu(String title) {
		String lower = title.toLowerCase(Locale.ROOT);
		return lower.contains("menu") || lower.contains("selector") || lower.contains("settings")
				|| lower.contains("shop") || lower.contains("server") || lower.contains("profile");
	}

	private long randomDelayNanos(int min, int max) {
		int millis = min + (max <= min ? 0 : random.nextInt(max - min + 1));
		return millis * 1_000_000L;
	}

	private int invToScreen(int inventorySlot) {
		return inventorySlot < PlayerInventory.getHotbarSize() ? 36 + inventorySlot : inventorySlot;
	}

	private int armorScreenSlot(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> 5;
			case CHEST -> 6;
			case LEGS -> 7;
			case FEET -> 8;
			default -> -1;
		};
	}

	private record Click(int slot, int button, SlotActionType type) {}
	private record ArmorUpgrade(EquipmentSlot slot, int inventorySlot, double improvement) {}
}
