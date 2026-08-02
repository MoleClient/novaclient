package com.profps.client.donutsmp;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StashPinger implements HudRenderCallback {
	private static final int SCAN_INTERVAL_TICKS = 40;
	private static final int PING_TTL_TICKS = 520;
	private static final int MAX_ACTIVE_PINGS = 6;
	private static final int MAX_SCAN_BLOCKS_PER_AREA = 28 * 16 * 28;
	private static final int BASE_COLOR = 0xFF38FF7A;
	private static final int SPAWNER_COLOR = 0xFF2EECFF;
	/** Pings closer together than this are the SAME base (stops one build pinging 4×). */
	private static final double BASE_MERGE_SQ = 48.0 * 48.0;

	/** One chat line per found position per ~5 minutes — rescans must not spam. */
	private static final int CHAT_ALERT_COOLDOWN_TICKS = 6000;
	/** Bases alert on the FIRST solid detection now — the engine's own fast intel
	 *  alert already covers mask-pierced finds, so the slow double-confirm just
	 *  added the multi-minute lag the user saw. */
	private static final int CONFIRMATIONS_REQUIRED = 1;

	private final ProFPSConfig config;
	private final AdvancedEspRenderer advancedEsp;
	private final List<StashPing> pings = new ArrayList<>();
	private final List<AdvancedEspRenderer.AreaSnapshot> areaQueue = new ArrayList<>();
	private final List<PendingConfirm> pendingConfirms = new ArrayList<>();
	private final java.util.Map<BlockPos, Integer> recentChatAlerts = new java.util.HashMap<>();
	private int nextScanTick;
	private int nextActionBarTick;
	private int nextMusicTick;
	private boolean failedClosed;
	private boolean wasActive;
	private ClientWorld lastWorld;

	public StashPinger(ProFPSConfig config, AdvancedEspRenderer advancedEsp) {
		this.config = config;
		this.advancedEsp = advancedEsp;
	}

	public void tick(MinecraftClient client) {
		if (!isActive()) {
			pings.clear();
			areaQueue.clear();
			failedClosed = false;
			wasActive = false;
			return;
		}
		if (failedClosed || client.world == null || client.player == null) return;

		int tick = client.player.age;
		if (!wasActive || client.world != lastWorld) {
			areaQueue.clear();
			pendingConfirms.clear();
			recentChatAlerts.clear();
			nextScanTick = 0;
			wasActive = true;
			lastWorld = client.world;
		}
		// player.age resets on world change; never let a stale clock stall scans.
		if (nextScanTick > tick + SCAN_INTERVAL_TICKS) nextScanTick = 0;
		pings.removeIf(ping -> tick - ping.lastSeenTick > PING_TTL_TICKS || !shouldShowPing(ping));
		pendingConfirms.removeIf(confirm -> tick < confirm.lastTick || tick - confirm.lastTick > SCAN_INTERVAL_TICKS * 6);
		if (!collapseNotifications() && !pings.isEmpty() && tick >= nextActionBarTick) {
			client.inGameHud.setOverlayMessage(baseFoundMessage(), false);
			nextActionBarTick = tick + 12;
		}

		try {
			if (areaQueue.isEmpty()) {
				if (tick < nextScanTick) return;
				// Defer if another heavy scanner already owns this tick, so scans never stack.
				if (!ScanBudget.tryClaim(tick)) return;
				nextScanTick = tick + SCAN_INTERVAL_TICKS;
				beginScan(client);
				if (!areaQueue.isEmpty()) {
					stepScan(client, tick);
				} else {
					// Advanced ESP may still be resolving its first nearby chunks.
					// Poll candidates soon instead of looking dead for two seconds.
					nextScanTick = tick + 5;
				}
			} else {
				stepScan(client, tick);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Stash Pinger scan failed; disabling Stash Pinger to protect the client.", exception);
			pings.clear();
			areaQueue.clear();
			config.donutStashPinger = false;
			config.save();
			failedClosed = true;
		}
	}

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		if (!isActive() || failedClosed || pings.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null || mc.options.hudHidden) return;

		int width = context.getScaledWindowWidth();
		int height = context.getScaledWindowHeight();
		int crossX = width / 2;
		int crossY = height / 2;
		Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
		float renderTick = mc.player.age + tickCounter.getTickProgress(false);

		int drawn = 0;
		for (StashPing ping : sortedPings(mc)) {
			if (!shouldShowPing(ping)) continue;
			double range = MathHelper.clamp(config.donutStashPingerRange, 48, 1024);
			if (mc.player.squaredDistanceTo(ping.center) > range * range) continue;
			if (drawn++ >= MAX_ACTIVE_PINGS) break;
			ScreenPoint point = ping.displayPoint(projectToScreen(mc, ping.center, camera, width, height), 0.28F);
			float fade = ping.fade(renderTick);
			int color = withAlpha(ping.color(), fade);
			drawTracer(context, crossX, crossY, point.x, point.y, color, fade);
			String label = ping.hudLabel(mc);
			int textWidth = mc.textRenderer.getWidth(label);
			context.fill(point.x - textWidth / 2 - 5, point.y - 18, point.x + textWidth / 2 + 5, point.y - 6, withAlpha(0xB0000000, fade));
			context.drawText(mc.textRenderer, label, point.x - textWidth / 2, point.y - 16, color, false);
		}
	}

	private boolean isActive() {
		// Stash Pinger reads the Advanced ESP block scan for its data, so it needs
		// that running — but it has nothing to do with Mob ESP (entity ESP), which
		// is why toggling Mob ESP must NOT affect it.
		return config.enabled && config.donutStashPinger && config.donutAdvancedEsp;
	}

	public BaseTarget bestBaseTarget(MinecraftClient client) {
		if (client == null || client.player == null || pings.isEmpty()) return null;
		StashPing best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (StashPing ping : pings) {
			if (ping.isSpawner() || !shouldShowPing(ping)) continue;
			double distance = Math.sqrt(client.player.squaredDistanceTo(ping.center));
			double score = ping.score - distance * 0.12;
			if (score > bestScore) {
				best = ping;
				bestScore = score;
			}
		}
		return best == null ? null : new BaseTarget(best.center, best.score);
	}

	private boolean collapseNotifications() {
		return config.donutTunnel;
	}

	private Text baseFoundMessage() {
		boolean spawner = false;
		boolean base = false;
		for (StashPing ping : pings) {
			if (ping.isSpawner()) spawner = true;
			else base = true;
		}
		String label = base && spawner ? "Base + Spawner Detected"
				: spawner ? "Spawner Detected"
				: "Base Detected";
		String count = pings.size() > 1 ? " x" + pings.size() : "";
		Formatting color = spawner && !base ? Formatting.AQUA : Formatting.GOLD;
		return Text.literal(label + count).formatted(color, Formatting.BOLD);
	}

	/** Open a scan cycle over the Advanced ESP findings, nearest areas first. */
	private void beginScan(MinecraftClient client) {
		areaQueue.clear();
		List<AdvancedEspRenderer.AreaSnapshot> areas = advancedEsp.areaSnapshots();
		if (areas.isEmpty()) return;
		double range = MathHelper.clamp(config.donutStashPingerRange, 48, 1024);
		for (AdvancedEspRenderer.AreaSnapshot area : areas) {
			if (client.player.squaredDistanceTo(area.center()) > range * range) continue;
			areaQueue.add(area);
		}
		// Farthest first in the list — areas pop off the tail, so nearest resolve soonest.
		areaQueue.sort(Comparator.comparingDouble(
				(AdvancedEspRenderer.AreaSnapshot area) -> client.player.squaredDistanceTo(area.center())).reversed());
	}

	/** Verify queued areas until the shared time budget runs out; commit when drained. */
	private void stepScan(MinecraftClient client, int tick) {
		long pool = ScanBudget.takeBudget(tick, ScanBudget.Lane.STASH_PINGER, config);
		if (pool <= 0L) return;
		long start = System.nanoTime();
		boolean expired = false;
		while (!areaQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) {
				expired = true;
				break;
			}
			AdvancedEspRenderer.AreaSnapshot area = areaQueue.remove(areaQueue.size() - 1);
			if (isSpawnerArea(area)) {
				if (!config.donutStashShowSpawners) continue;
				upsertPing(client, new StashPing(area.center(), "Spawner", 120.0, tick), tick);
				continue;
			}
			if (!config.donutStashShowBases) continue;
			StashStats stats = scanArea(client.world, area.box().expand(2.0, 1.0, 2.0));
			if (!stats.isLikelyBase()) continue;
			upsertPing(client, new StashPing(area.center(), "Base", stats.score(), tick), tick);
		}
		ScanBudget.reportUsed(tick, ScanBudget.Lane.STASH_PINGER, System.nanoTime() - start);
		if (expired || !areaQueue.isEmpty()) return;

		pings.sort(Comparator.comparingDouble((StashPing ping) -> ping.score).reversed());
		while (pings.size() > MAX_ACTIVE_PINGS) {
			pings.remove(pings.size() - 1);
		}
	}

	private boolean isSpawnerArea(AdvancedEspRenderer.AreaSnapshot area) {
		return "Spawner".equals(area.label());
	}

	private boolean shouldShowPing(StashPing ping) {
		return ping.isSpawner() ? config.donutStashShowSpawners : config.donutStashShowBases;
	}

	private void upsertPing(MinecraftClient client, StashPing raw, int tick) {
		StashPing existing = findMatching(raw);
		if (existing != null) {
			existing.center = existing.center.lerp(raw.center, 0.34);
			existing.score = Math.max(existing.score * 0.88, raw.score);
			existing.lastSeenTick = tick;
			return;
		}
		if (!raw.isSpawner()) {
			// A base must be confirmed twice before it may alert — one-off
			// misreads used to flash "Base Found" and fade seconds later.
			// Confirmations match by DISTANCE: detection centers drift a few
			// blocks between passes, and the old fixed-grid keying could land
			// each pass in a different cell so nothing ever confirmed at all.
			PendingConfirm match = null;
			for (PendingConfirm confirm : pendingConfirms) {
				if (confirm.center.squaredDistanceTo(raw.center) < BASE_MERGE_SQ) {
					match = confirm;
					break;
				}
			}
			if (match == null) {
				match = new PendingConfirm();
				match.center = raw.center;
				pendingConfirms.add(match);
			} else {
				match.center = match.center.lerp(raw.center, 0.4);
			}
			if (tick < match.lastTick || tick - match.lastTick > SCAN_INTERVAL_TICKS * 5) match.hits = 0;
			match.hits++;
			match.lastTick = tick;
			if (match.hits < CONFIRMATIONS_REQUIRED) return;
			pendingConfirms.remove(match);
		}
		pings.add(raw);
		alert(client, raw, tick);
	}

	private static final class PendingConfirm {
		Vec3d center;
		int hits;
		int lastTick;
	}

	private StashPing findMatching(StashPing raw) {
		// Bases merge generously — the same build kept pinging 3-4 times from
		// several nearby detection areas, each with its own tracer. Spawners
		// stay tight so genuine double-dungeons keep both pings.
		double mergeSq = raw.isSpawner() ? 64.0 : BASE_MERGE_SQ;
		for (StashPing ping : pings) {
			if (!ping.type.equals(raw.type)) continue;
			if (ping.center.squaredDistanceTo(raw.center) < mergeSq) return ping;
		}
		return null;
	}

	private void alert(MinecraftClient client, StashPing ping, int tick) {
		if (client.player == null || client.world == null) return;
		// Every find goes to chat with exact coordinates (the action bar keeps
		// rolling alongside) — a located base/spawner is worth a permanent line.
		chatAlertPing(client, ping, tick);
		if (collapseNotifications()) {
			nextActionBarTick = tick + 12;
			return;
		}
		client.inGameHud.setOverlayMessage(baseFoundMessage(), false);
		client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0F, 1.25F);
		if (tick >= nextMusicTick) {
			client.world.playSoundClient(client.player.getX(), client.player.getY(), client.player.getZ(),
					SoundEvents.MUSIC_DISC_OTHERSIDE.value(), SoundCategory.RECORDS, 0.55F, 1.0F, false);
			nextMusicTick = tick + 260;
		}
		nextActionBarTick = tick + 12;
	}

	private void chatAlertPing(MinecraftClient client, StashPing ping, int tick) {
		BlockPos pos = BlockPos.ofFloored(ping.center);
		// Base centers drift between rescans; dedupe on a 32-block cell so the
		// same base can't alert again from a slightly shifted center.
		BlockPos key = new BlockPos(pos.getX() >> 5, pos.getY() >> 5, pos.getZ() >> 5);
		Integer last = recentChatAlerts.get(key);
		if (last != null && tick >= last && tick - last < CHAT_ALERT_COOLDOWN_TICKS) return;
		recentChatAlerts.put(key, tick);
		if (recentChatAlerts.size() > 128) recentChatAlerts.clear();
		String label = ping.displayName();
		Formatting accent = ping.isSpawner() ? Formatting.AQUA : Formatting.GOLD;
		Text message = Text.literal("[").formatted(Formatting.WHITE)
				.append(Text.literal(label).formatted(accent))
				.append(Text.literal("] ").formatted(Formatting.WHITE))
				.append(Text.literal(label + " Detected At ").formatted(Formatting.GRAY, Formatting.BOLD))
				.append(Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).formatted(Formatting.GREEN));
		client.inGameHud.getChatHud().addMessage(message);
	}

	private StashStats scanArea(ClientWorld world, Box box) {
		int minX = MathHelper.floor(box.minX);
		int minY = MathHelper.floor(Math.max(world.getBottomY(), box.minY));
		int minZ = MathHelper.floor(box.minZ);
		int maxX = MathHelper.floor(box.maxX);
		int maxY = MathHelper.floor(Math.min(world.getBottomY() + world.getHeight() - 1, box.maxY));
		int maxZ = MathHelper.floor(box.maxZ);
		int estimatedBlocks = Math.max(1, (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
		if (estimatedBlocks > MAX_SCAN_BLOCKS_PER_AREA) {
			int trim = MathHelper.ceil((estimatedBlocks - MAX_SCAN_BLOCKS_PER_AREA) / 512.0F);
			minX += trim;
			maxX -= trim;
			minZ += trim;
			maxZ -= trim;
		}

		StashStats stats = new StashStats();
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
					pos.set(x, y, z);
					stats.accept(world.getBlockState(pos));
				}
			}
		}

		// Anti-xray masks the block PALETTE at distance, so the loop above sees
		// deepslate where the chests are — bases only flagged once the player
		// was practically inside and the real blocks streamed. Block ENTITIES
		// are never masked: count them too and take the larger of the two
		// views, so storage-heavy bases ping from the full scan range.
		int beStorage = 0, beShulkers = 0, beEnderChests = 0, beRedstone = 0, beCrafted = 0;
		for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
			for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
				if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
				WorldChunk chunk = world.getChunk(chunkX, chunkZ);
				if (chunk == null) continue;
				for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
					BlockPos bePos = entry.getKey();
					if (bePos.getX() < minX || bePos.getX() > maxX || bePos.getY() < minY || bePos.getY() > maxY
							|| bePos.getZ() < minZ || bePos.getZ() > maxZ) {
						continue;
					}
					BlockEntityType<?> type = entry.getValue().getType();
					if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
							|| type == BlockEntityType.BARREL) beStorage++;
					else if (type == BlockEntityType.SHULKER_BOX) beShulkers++;
					else if (type == BlockEntityType.ENDER_CHEST) beEnderChests++;
					else if (type == BlockEntityType.HOPPER || type == BlockEntityType.DISPENSER
							|| type == BlockEntityType.DROPPER) beRedstone++;
					else if (type == BlockEntityType.FURNACE || type == BlockEntityType.BLAST_FURNACE
							|| type == BlockEntityType.SMOKER || type == BlockEntityType.BREWING_STAND
							|| type == BlockEntityType.ENCHANTING_TABLE || type == BlockEntityType.BEACON) beCrafted++;
				}
			}
		}
		stats.storage = Math.max(stats.storage, beStorage);
		stats.shulkers = Math.max(stats.shulkers, beShulkers);
		stats.enderChests = Math.max(stats.enderChests, beEnderChests);
		stats.redstone = Math.max(stats.redstone, beRedstone);
		stats.crafted = Math.max(stats.crafted, beCrafted);
		return stats;
	}

	private List<StashPing> sortedPings(MinecraftClient mc) {
		List<StashPing> sorted = new ArrayList<>(pings);
		sorted.sort(Comparator.comparingDouble((StashPing ping) -> mc.player.squaredDistanceTo(ping.center)));
		return sorted;
	}

	private ScreenPoint projectToScreen(MinecraftClient mc, StashPing ping, Vec3d camera, int width, int height) {
		return projectToScreen(mc, ping.center, camera, width, height);
	}

	private ScreenPoint projectToScreen(MinecraftClient mc, Vec3d marker, Vec3d camera, int width, int height) {
		Vec3d projected = mc.gameRenderer.project(marker);
		double dx = marker.x - camera.x;
		double dz = marker.z - camera.z;
		double yaw = Math.toRadians(mc.gameRenderer.getCamera().getYaw());
		double forwardX = -Math.sin(yaw);
		double forwardZ = Math.cos(yaw);
		boolean inFront = dx * forwardX + dz * forwardZ > 0.1;
		boolean onScreen = inFront
				&& projected.x >= -1.0 && projected.x <= 1.0
				&& projected.y >= -1.0 && projected.y <= 1.0
				&& projected.z >= -1.0 && projected.z <= 1.0;
		if (onScreen) {
			return new ScreenPoint(
					(int) Math.round((projected.x + 1.0) * 0.5 * width),
					(int) Math.round((1.0 - projected.y) * 0.5 * height)
			);
		}

		double relative = Math.atan2(dx, dz) - yaw;
		double radiusX = width * 0.5 - 22.0;
		double radiusY = height * 0.5 - 22.0;
		return new ScreenPoint(
				(int) Math.round(width * 0.5 + Math.sin(relative) * radiusX),
				(int) Math.round(height * 0.5 - Math.cos(relative) * radiusY)
		);
	}

	private void drawTracer(DrawContext context, int x0, int y0, int x1, int y1, int color, float fade) {
		drawLine(context, x0, y0, x1, y1, 2, withAlpha(0x66000000, fade));
		drawLine(context, x0, y0, x1, y1, 1, color);
	}

	/**
	 * A genuinely straight segment: one quad rotated to the target angle,
	 * instead of the old per-pixel staircase plot.
	 */
	private void drawLine(DrawContext context, int x0, int y0, int x1, int y1, int thickness, int color) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length < 1.0F) return;
		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(x0, y0);
		matrices.rotate((float) Math.atan2(dy, dx));
		context.fill(0, -(thickness / 2), Math.round(length), thickness - thickness / 2, color);
		matrices.popMatrix();
	}

	private int withAlpha(int color, float alphaScale) {
		int alpha = MathHelper.clamp(Math.round(((color >>> 24) & 0xFF) * alphaScale), 0, 255);
		return (color & 0x00FFFFFF) | (alpha << 24);
	}

	private static final class StashStats {
		private int storage;
		private int shulkers;
		private int enderChests;
		private int redstone;
		private int crafted;
		private int dungeon;
		private int decor;

		void accept(BlockState state) {
			if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) storage++;
			if (isShulker(state)) shulkers++;
			if (state.isOf(Blocks.ENDER_CHEST)) enderChests++;
			if (isRedstoneBaseBlock(state)) redstone++;
			if (isCraftedBaseBlock(state)) crafted++;
			if (isDungeonBlock(state)) dungeon++;
			if (PlayerPlacedBlocks.isBuildDecor(state, false)) decor++;
		}

		boolean isLikelyBase() {
			int special = shulkers + enderChests;
			if (dungeon >= 2 && storage <= 2 && special == 0 && redstone == 0 && decor < 12) return false;
			if (special >= 1 && storage + redstone + crafted >= 1) return true;
			if (storage >= 3) return true;
			if (storage >= 2 && redstone + crafted >= 2) return true;
			if (redstone >= 3 && crafted >= 2) return true;
			// A raided/destroyed base keeps none of its loot blocks — but the
			// built shell (stained glass, concrete, glowstone, candles...) stays.
			if (special >= 1 && decor >= 6) return true;
			return decor >= 24;
		}

		double score() {
			return storage * 8.0 + shulkers * 18.0 + enderChests * 18.0 + redstone * 5.0 + crafted * 4.0
					+ Math.min(decor, 120) * 1.1 - dungeon * 7.0;
		}

		private static boolean isShulker(BlockState state) {
			return state.isOf(Blocks.SHULKER_BOX)
					|| state.isOf(Blocks.WHITE_SHULKER_BOX)
					|| state.isOf(Blocks.ORANGE_SHULKER_BOX)
					|| state.isOf(Blocks.MAGENTA_SHULKER_BOX)
					|| state.isOf(Blocks.LIGHT_BLUE_SHULKER_BOX)
					|| state.isOf(Blocks.YELLOW_SHULKER_BOX)
					|| state.isOf(Blocks.LIME_SHULKER_BOX)
					|| state.isOf(Blocks.PINK_SHULKER_BOX)
					|| state.isOf(Blocks.GRAY_SHULKER_BOX)
					|| state.isOf(Blocks.LIGHT_GRAY_SHULKER_BOX)
					|| state.isOf(Blocks.CYAN_SHULKER_BOX)
					|| state.isOf(Blocks.PURPLE_SHULKER_BOX)
					|| state.isOf(Blocks.BLUE_SHULKER_BOX)
					|| state.isOf(Blocks.BROWN_SHULKER_BOX)
					|| state.isOf(Blocks.GREEN_SHULKER_BOX)
					|| state.isOf(Blocks.RED_SHULKER_BOX)
					|| state.isOf(Blocks.BLACK_SHULKER_BOX);
		}

		private static boolean isRedstoneBaseBlock(BlockState state) {
			return state.isOf(Blocks.REDSTONE_BLOCK)
					|| state.isOf(Blocks.REPEATER)
					|| state.isOf(Blocks.COMPARATOR)
					|| state.isOf(Blocks.PISTON)
					|| state.isOf(Blocks.STICKY_PISTON)
					|| state.isOf(Blocks.OBSERVER)
					|| state.isOf(Blocks.HOPPER)
					|| state.isOf(Blocks.DISPENSER)
					|| state.isOf(Blocks.DROPPER)
					|| state.isOf(Blocks.REDSTONE_TORCH)
					|| state.isOf(Blocks.REDSTONE_WALL_TORCH);
		}

		private static boolean isCraftedBaseBlock(BlockState state) {
			return state.isOf(Blocks.CRAFTING_TABLE)
					|| state.isOf(Blocks.FURNACE)
					|| state.isOf(Blocks.BLAST_FURNACE)
					|| state.isOf(Blocks.SMOKER)
					|| state.isOf(Blocks.ENCHANTING_TABLE)
					|| state.isOf(Blocks.ANVIL)
					|| state.isOf(Blocks.CHIPPED_ANVIL)
					|| state.isOf(Blocks.DAMAGED_ANVIL)
					|| state.isOf(Blocks.BREWING_STAND)
					|| state.isOf(Blocks.BEACON)
					|| state.isOf(Blocks.RESPAWN_ANCHOR)
					|| state.isOf(Blocks.LODESTONE)
					|| state.isOf(Blocks.JUKEBOX);
		}

		private static boolean isDungeonBlock(BlockState state) {
			return state.isOf(Blocks.SPAWNER)
					|| state.isOf(Blocks.TRIAL_SPAWNER)
					|| state.isOf(Blocks.COBWEB)
					|| state.isOf(Blocks.MOSSY_COBBLESTONE)
					|| state.isOf(Blocks.MOSSY_STONE_BRICKS)
					|| state.isOf(Blocks.RAIL)
					|| state.isOf(Blocks.POWERED_RAIL)
					|| state.isOf(Blocks.DETECTOR_RAIL)
					|| state.isOf(Blocks.ACTIVATOR_RAIL);
		}
	}

	private static final class StashPing {
		private Vec3d center;
		private final String type;
		private double score;
		private final int firstSeenTick;
		private int lastSeenTick;
		private boolean hasScreenPoint;
		private float screenX;
		private float screenY;

		StashPing(Vec3d center, String type, double score, int tick) {
			this.center = center;
			this.type = type;
			this.score = score;
			this.firstSeenTick = tick;
			this.lastSeenTick = tick;
		}

		boolean isSpawner() {
			return "Spawner".equals(type);
		}

		int color() {
			return isSpawner() ? SPAWNER_COLOR : BASE_COLOR;
		}

		String displayName() {
			return isSpawner() ? "Spawner" : "Base";
		}

		String hudLabel(MinecraftClient mc) {
			int distance = mc.player == null ? 0 : MathHelper.floor(Math.sqrt(mc.player.squaredDistanceTo(center)));
			return displayName() + " " + distance + "m";
		}

		ScreenPoint displayPoint(ScreenPoint raw, float blend) {
			if (!hasScreenPoint) {
				screenX = raw.x();
				screenY = raw.y();
				hasScreenPoint = true;
			} else {
				screenX = MathHelper.lerp(blend, screenX, raw.x());
				screenY = MathHelper.lerp(blend, screenY, raw.y());
			}
			return new ScreenPoint(Math.round(screenX), Math.round(screenY));
		}

		float fade(float renderTick) {
			float in = MathHelper.clamp((renderTick - firstSeenTick) / 14.0F, 0.0F, 1.0F);
			float out = MathHelper.clamp((lastSeenTick + PING_TTL_TICKS - renderTick) / 30.0F, 0.0F, 1.0F);
			return smooth(in) * smooth(out);
		}

		private static float smooth(float value) {
			return value * value * (3.0F - 2.0F * value);
		}
	}

	private record ScreenPoint(int x, int y) {
	}

	public record BaseTarget(Vec3d center, double score) {
	}
}
