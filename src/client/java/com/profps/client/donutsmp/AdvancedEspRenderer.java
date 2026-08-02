package com.profps.client.donutsmp;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.RenderLayerInvoker;
import com.profps.client.mixin.RenderPipelinesInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AdvancedEspRenderer {
	private static final int SCAN_INTERVAL_TICKS = 60;
	/** Fat per-tick time budget right after activation, so findings appear instantly. */
	private static final long BURST_BUDGET_NANOS = 12_000_000L;
	private static final int MIN_CHUNK_RADIUS = 2;
	private static final int MAX_CHUNK_RADIUS = 12;
	private static final int MAX_FINDINGS = 160;
	private static final int MAX_SCAN_HEIGHT = 128;
	private static final int FADE_IN_TICKS = 6;
	private static final int FADE_OUT_TICKS = 26;
	private static final int STALE_TICKS = SCAN_INTERVAL_TICKS + FADE_OUT_TICKS;
	// Referencing the shared layers here forces their registered pipelines to be
	// created during client initialization, before Minecraft compiles pipelines
	// in the first resource reload.
	private static final RenderLayer ADVANCED_LINES = DonutWorldRenderer.LINES;
	private static final RenderLayer ADVANCED_FILLS = createFillLayer();

	private final ProFPSConfig config;
	private final List<Finding> findings = new ArrayList<>();
	private int nextScanTick;
	private boolean failedClosed;
	private boolean wasActive;
	private ClientWorld lastWorld;

	// ── Incremental scan state (one cycle is spread across many ticks) ─────────
	private final List<long[]> scanQueue = new ArrayList<>();
	private final List<Finding> chunkBuffer = new ArrayList<>();
	private int scanMinY;
	private int scanMaxY;
	private int scanCycleStartTick;
	private int lastCycleTicks = SCAN_INTERVAL_TICKS;
	private boolean scanNether;
	private int burstTicks;
	private boolean wasChunkStreamBusy;
	private int failureCount;
	private int lastCompletedTick; // age the last full cycle finished — feeds the stall watchdog

	public AdvancedEspRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.donutAdvancedEsp) {
			if (!findings.isEmpty()) findings.clear();
			scanQueue.clear();
			failedClosed = false;
			wasActive = false;
			return;
		}
		if (failedClosed) return;
		if (client.world == null || client.player == null) return;

		// Start scanning IMMEDIATELY when the module is switched on, and forget
		// everything from a previous world — player.age resets across worlds, so
		// a stale nextScanTick could otherwise postpone the first scan by minutes.
		if (!wasActive || client.world != lastWorld) {
			if (client.world != lastWorld) findings.clear();
			scanQueue.clear();
			nextScanTick = 0;
			burstTicks = 10;
			wasActive = true;
			lastWorld = client.world;
			failureCount = 0;
			lastCompletedTick = client.player.age;
		}
		if (nextScanTick > client.player.age + SCAN_INTERVAL_TICKS) {
			nextScanTick = 0;
		}

		// Manual "Reload" button (Advanced ESP settings) — wipe and re-scan the area
		// around you right now, at full burst budget.
		if (config.donutAdvancedEspReloadRequested) {
			config.donutAdvancedEspReloadRequested = false;
			findings.clear();
			scanQueue.clear();
			nextScanTick = 0;
			burstTicks = 10;
			lastCompletedTick = client.player.age;
		}

		// Stall watchdog. The periodic rescan can wedge when you sit still on a busy
		// server — chunk packets never stop arriving, so the burst-rescan edge below
		// never fires and findings quietly fade out until you reload chunks. If no
		// full cycle has COMPLETED in a few intervals, force a fresh burst scan so
		// what's around you keeps refreshing on its own.
		if (scanQueue.isEmpty() && client.player.age - lastCompletedTick > SCAN_INTERVAL_TICKS * 3) {
			nextScanTick = 0;
			burstTicks = Math.max(burstTicks, 3);
			lastCompletedTick = client.player.age;
		}

		// A chunk-stream wave just ended (login, teleport, walking into fresh
		// terrain): burst-rescan immediately. The activation burst alone kept
		// firing BEFORE the server had streamed the terrain in — it scanned
		// air and the area then trickled in on the reduced budget.
		boolean streaming = ScanBudget.isChunkLoadBusy(client);
		if (wasChunkStreamBusy && !streaming) {
			burstTicks = Math.max(burstTicks, 5);
			nextScanTick = 0;
		}
		wasChunkStreamBusy = streaming;

		try {
			if (scanQueue.isEmpty()) {
				if (client.player.age < nextScanTick) return;
				if (burstTicks <= 0 && !ScanBudget.tryClaim(client.player.age)) return;
				nextScanTick = client.player.age + SCAN_INTERVAL_TICKS;
				beginScan(client);
				stepScan(client); // start chewing the queue this very tick
			} else {
				stepScan(client);
			}
		} catch (RuntimeException exception) {
			failureCount++;
			if (failureCount < 3) {
				// Transient hiccup: drop this cycle and retry shortly instead of
				// silently turning the module off mid-session.
				ProFPS.LOGGER.warn("Advanced ESP scan failed (attempt {}); retrying.", failureCount, exception);
				scanQueue.clear();
				nextScanTick = client.player.age + 100;
				return;
			}
			ProFPS.LOGGER.error("Advanced ESP scan failed repeatedly; disabling Advanced ESP.", exception);
			findings.clear();
			scanQueue.clear();
			config.donutAdvancedEsp = false;
			config.save();
			failedClosed = true;
			ChunkActivityRenderer.announceDisabled(client, "Advanced ESP");
		}
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutAdvancedEsp || failedClosed) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null || findings.isEmpty()) return;

		try {
			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc position = entry.getPositionMatrix();
			float renderTick = mc.player.age + mc.getRenderTickCounter().getTickProgress(false);
			// The context's immediate provider has exactly one active buffer. Render requests
			// are collected first, then submitted in layer-wide passes: fills, lines, labels.
			// Requesting a new layer flushes the previous one, so retaining both consumers and
			// interleaving writes causes IllegalStateException: "Not building!".
			List<BaseLabelReq> baseLabels = new ArrayList<>();
			List<WorldLabelReq> worldLabels = new ArrayList<>();
			List<BaseRenderReq> visibleBases = new ArrayList<>();
			List<FindingRenderReq> visibleFindings = new ArrayList<>();

			double range = MathHelper.clamp(config.donutAdvancedEspRange, 48, 1024);
			int stale = staleWindowTicks();

			// ── Base region ──────────────────────────────────────────────────
			// Container-confirmed Base chunks SEED a region; adjacent player-built
			// (PLACED) chunks are flood-pulled into it — a built room with no
			// visible chest still belongs to the base. The region renders as one
			// merged shape of full-chunk tiles, so the WHOLE irregular base is
			// covered instead of only the chunks that happen to hold a container.
			Map<Long, BaseTile> baseRegion = new HashMap<>();
			for (Finding finding : findings) {
				if (finding.type() == FindingType.BASE) {
					baseRegion.putIfAbsent(baseChunkKey(finding), new BaseTile(finding.box(), finding.fade(renderTick, stale)));
				}
			}
			Map<Long, BaseTile> placedTiles = new HashMap<>();
			for (Finding finding : findings) {
				if (finding.type() != FindingType.PLACED) continue;
				long key = baseChunkKey(finding);
				if (baseRegion.containsKey(key)) continue;
				placedTiles.computeIfAbsent(key, k -> new BaseTile(fullChunkBox(k, finding.box()), finding.fade(renderTick, stale)));
			}
			ArrayDeque<Long> frontier = new ArrayDeque<>(baseRegion.keySet());
			while (!frontier.isEmpty()) {
				long k = frontier.poll();
				int cx = ChunkPos.getPackedX(k);
				int cz = ChunkPos.getPackedZ(k);
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dz == 0) continue;
						long n = ChunkPos.toLong(cx + dx, cz + dz);
						BaseTile placed = placedTiles.get(n);
						if (placed != null && !baseRegion.containsKey(n)) {
							baseRegion.put(n, placed);
							frontier.add(n);
						}
					}
				}
			}

			// Flatten each connected region to a shared Y span so the merged
			// shape has a clean even top/bottom instead of a jagged one.
			unifyRegionY(baseRegion);

			// Pockets that swallow PLACED markers. The base region is handled by
			// CHUNK membership below (so every red marker in a base chunk is
			// hidden, not just those whose box overlaps a tile) — that's what
			// kills the red-boxes-inside-yellow-boxes nesting.
			List<Box> pocketBoxes = new ArrayList<>();
			for (Finding finding : findings) {
				if (finding.type() == FindingType.POCKET) pocketBoxes.add(finding.box());
			}

			Set<Long> baseAnchors = regionAnchors(baseRegion.keySet());
			AreaColor baseColor = FindingType.BASE.color;
			for (Map.Entry<Long, BaseTile> entryTile : baseRegion.entrySet()) {
				BaseTile tile = entryTile.getValue();
				if (tile.fade() <= 0.01F) continue;
				Vec3d center = new Vec3d((tile.box().minX + tile.box().maxX) * 0.5,
						(tile.box().minY + tile.box().maxY) * 0.5, (tile.box().minZ + tile.box().maxZ) * 0.5);
				if (camera.squaredDistanceTo(center) > range * range) continue;
				float pulse = 0.82F + 0.18F * (float) Math.sin(renderTick * 0.14F + (entryTile.getKey() & 0x3F));
				visibleBases.add(new BaseRenderReq(tile, pulse));
				if (baseAnchors.contains(entryTile.getKey())) {
					baseLabels.add(new BaseLabelReq(tile.box(), tile.fade()));
				}
			}

			// ── Everything else (tunnels, shafts, pockets, spawners, stray placed) ──
			int rendered = 0;
			for (Finding finding : findings) {
				if (rendered >= MAX_FINDINGS) break;
				if (finding.type() == FindingType.BASE) continue; // handled by the region pass
				if (!isVisibleInAdvanced(finding.type())) continue;
				if (finding.type() == FindingType.PLACED
						&& (baseRegion.containsKey(baseChunkKey(finding)) || isInsideArea(finding.center(), pocketBoxes))) {
					continue;
				}
				// Camera-relative so findings keep rendering while freecam scouts
				// far from the player's anchored body.
				if (camera.squaredDistanceTo(finding.center()) > range * range) continue;
				float fade = finding.fade(renderTick, stale);
				if (fade <= 0.01F) continue;
				float pulse = 0.82F + 0.18F * (float) Math.sin(renderTick * 0.14F + finding.animationSeed());
				visibleFindings.add(new FindingRenderReq(finding, fade, pulse));
				worldLabels.add(new WorldLabelReq(finding, fade));
				rendered++;
			}

			VertexConsumer fills = ctx.consumers().getBuffer(ADVANCED_FILLS);
			for (BaseRenderReq render : visibleBases) {
				BaseTile tile = render.tile();
				drawFilledBox(fills, position, tile.box(), camera, baseColor,
						(0.07F + 0.05F * render.pulse()) * tile.fade());
			}
			for (FindingRenderReq render : visibleFindings) {
				drawFilledBox(fills, position, render.finding().box(), camera, render.finding().color(),
						(0.10F + 0.07F * render.pulse()) * render.fade());
			}

			// Acquiring the line layer flushes fills. No fill consumer is used below.
			VertexConsumer lines = ctx.consumers().getBuffer(ADVANCED_LINES);
			for (BaseRenderReq render : visibleBases) {
				BaseTile tile = render.tile();
				drawBaseRegionFaces(lines, position, entry, tile.box(), camera, baseColor,
						(0.82F + 0.18F * render.pulse()) * tile.fade(), baseRegion.keySet());
			}
			for (FindingRenderReq render : visibleFindings) {
				drawOutline(lines, position, entry, render.finding().box(), camera, render.finding().color(),
						(0.76F + 0.20F * render.pulse()) * render.fade());
			}

			// All geometry submitted — text is the final layer transition.
			for (BaseLabelReq label : baseLabels) {
				drawBaseLabel(ctx, matrices, camera, mc, label.box(), label.fade());
			}
			for (WorldLabelReq label : worldLabels) {
				drawWorldLabel(ctx, matrices, label.finding(), camera, mc, renderTick, label.fade());
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Advanced ESP render failed; disabling Advanced ESP to protect the client.", exception);
			findings.clear();
			config.donutAdvancedEsp = false;
			config.save();
			failedClosed = true;
			ChunkActivityRenderer.announceDisabled(mc, "Advanced ESP");
		}
	}

	/** True when a marker centre sits within any area (Base/Pocket) box — it's nested. */
	private static boolean isInsideArea(Vec3d center, List<Box> areaBoxes) {
		for (Box box : areaBoxes) {
			if (box.contains(center)) return true;
		}
		return false;
	}

	/** A base-region tile: a full-chunk box plus its current fade. */
	private record BaseTile(Box box, float fade) {}

	/** Deferred text labels, drawn after all geometry so they can't strand a half-built buffer. */
	private record BaseLabelReq(Box box, float fade) {}

	private record WorldLabelReq(Finding finding, float fade) {}

	private record BaseRenderReq(BaseTile tile, float pulse) {}

	private record FindingRenderReq(Finding finding, float fade, float pulse) {}

	private static long baseChunkKey(Finding finding) {
		return chunkKey(finding.box());
	}

	private static long chunkKey(Box box) {
		return ChunkPos.toLong(MathHelper.floor(box.minX) >> 4, MathHelper.floor(box.minZ) >> 4);
	}

	/** Full-chunk box for a chunk key, taking the Y span from a member (placed) box. */
	private static Box fullChunkBox(long chunkKey, Box member) {
		int x0 = ChunkPos.getPackedX(chunkKey) << 4;
		int z0 = ChunkPos.getPackedZ(chunkKey) << 4;
		return new Box(x0, member.minY - 1.0, z0, x0 + 16.0, member.maxY + 1.0, z0 + 16.0);
	}

	/** Give every tile in a connected region the same Y span (the region's overall min/max). */
	private static void unifyRegionY(Map<Long, BaseTile> region) {
		Set<Long> visited = new HashSet<>();
		for (Long start : new ArrayList<>(region.keySet())) {
			if (!visited.add(start)) continue;
			List<Long> members = new ArrayList<>();
			ArrayDeque<Long> frontier = new ArrayDeque<>();
			frontier.add(start);
			double minY = Double.MAX_VALUE;
			double maxY = -Double.MAX_VALUE;
			while (!frontier.isEmpty()) {
				long k = frontier.poll();
				members.add(k);
				Box b = region.get(k).box();
				minY = Math.min(minY, b.minY);
				maxY = Math.max(maxY, b.maxY);
				int cx = ChunkPos.getPackedX(k);
				int cz = ChunkPos.getPackedZ(k);
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dz == 0) continue;
						long n = ChunkPos.toLong(cx + dx, cz + dz);
						if (region.containsKey(n) && visited.add(n)) frontier.add(n);
					}
				}
			}
			for (long k : members) {
				BaseTile tile = region.get(k);
				Box b = tile.box();
				region.put(k, new BaseTile(new Box(b.minX, minY, b.minZ, b.maxX, maxY, b.maxZ), tile.fade()));
			}
		}
	}

	/** One anchor chunk per connected region (8-way), for a single label. */
	private static Set<Long> regionAnchors(Set<Long> regionKeys) {
		Set<Long> anchors = new HashSet<>();
		Set<Long> visited = new HashSet<>();
		for (long start : regionKeys) {
			if (!visited.add(start)) continue;
			anchors.add(start);
			ArrayDeque<Long> frontier = new ArrayDeque<>();
			frontier.add(start);
			while (!frontier.isEmpty()) {
				long k = frontier.poll();
				int cx = ChunkPos.getPackedX(k);
				int cz = ChunkPos.getPackedZ(k);
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dz == 0) continue;
						long n = ChunkPos.toLong(cx + dx, cz + dz);
						if (regionKeys.contains(n) && visited.add(n)) frontier.add(n);
					}
				}
			}
		}
		return anchors;
	}

	/** Outline only the region's outer perimeter — a side face is skipped if the chunk across it is in the region. */
	private void drawBaseRegionFaces(VertexConsumer lines, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d cam, AreaColor color, float alpha, Set<Long> regionKeys) {
		int cx = MathHelper.floor(box.minX) >> 4;
		int cz = MathHelper.floor(box.minZ) >> 4;
		double y0 = box.minY;
		double y1 = box.maxY;
		if (!regionKeys.contains(ChunkPos.toLong(cx - 1, cz))) {
			baseFaceRect(lines, pos, entry, cam, box.minX, box.minZ, box.minX, box.maxZ, y0, y1, color, alpha);
		}
		if (!regionKeys.contains(ChunkPos.toLong(cx + 1, cz))) {
			baseFaceRect(lines, pos, entry, cam, box.maxX, box.minZ, box.maxX, box.maxZ, y0, y1, color, alpha);
		}
		if (!regionKeys.contains(ChunkPos.toLong(cx, cz - 1))) {
			baseFaceRect(lines, pos, entry, cam, box.minX, box.minZ, box.maxX, box.minZ, y0, y1, color, alpha);
		}
		if (!regionKeys.contains(ChunkPos.toLong(cx, cz + 1))) {
			baseFaceRect(lines, pos, entry, cam, box.minX, box.maxZ, box.maxX, box.maxZ, y0, y1, color, alpha);
		}
	}

	/** Single "Base" label hovering over a region anchor tile. */
	private void drawBaseLabel(WorldRenderContext ctx, MatrixStack matrices, Vec3d camera, MinecraftClient mc,
			Box box, float fade) {
		double cx = (box.minX + box.maxX) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		double y = Math.max(box.maxY + 1.15, mc.player.getY() + 2.8);
		int color = withAlpha(FindingType.BASE.color.argb(), fade);
		drawBillboardText(ctx, matrices, camera, mc, "Base", cx, y, cz, color, 0.045F, fade);
	}

	private void baseFaceRect(VertexConsumer lines, Matrix4fc pos, MatrixStack.Entry entry, Vec3d cam,
			double hx0, double hz0, double hx1, double hz1, double y0, double y1, AreaColor color, float alpha) {
		line(lines, pos, entry, cam, hx0, y0, hz0, hx1, y0, hz1, color, alpha); // bottom
		line(lines, pos, entry, cam, hx0, y1, hz0, hx1, y1, hz1, color, alpha); // top
		line(lines, pos, entry, cam, hx0, y0, hz0, hx0, y1, hz0, color, alpha); // post A
		line(lines, pos, entry, cam, hx1, y0, hz1, hx1, y1, hz1, color, alpha); // post B
	}

	public List<AreaSnapshot> areaSnapshots() {
		List<AreaSnapshot> snapshots = new ArrayList<>();
		for (Finding finding : findings) {
			if (finding.type() == FindingType.PLACED) continue;
			if (finding.type() == FindingType.SPAWNER && !config.donutStashShowSpawners) continue;
			if (finding.type() != FindingType.SPAWNER && !config.donutStashShowBases) continue;
			snapshots.add(new AreaSnapshot(finding.box(), finding.label(), finding.center(), finding.score()));
		}
		return snapshots;
	}

	/** Open a scan cycle: enqueue every in-range chunk, nearest first. */
	private void beginScan(MinecraftClient client) {
		ClientWorld world = client.world;
		// In freecam, scan around the flying camera so newly scouted ground
		// resolves instead of only the player's anchor point.
		Vec3d center = FreecamController.isActive()
				? FreecamController.cameraPosition()
				: client.player.getEntityPos();
		int centerChunkX = MathHelper.floor(center.x) >> 4;
		int centerChunkZ = MathHelper.floor(center.z) >> 4;
		int centerY = MathHelper.floor(center.y);
		scanMinY = Math.max(world.getBottomY() + 2, centerY - MAX_SCAN_HEIGHT);
		scanMaxY = Math.min(world.getBottomY() + world.getHeight() - 3, centerY + 48);
		scanCycleStartTick = client.player.age;
		scanNether = world.getRegistryKey() == net.minecraft.world.World.NETHER;
		int chunkRadius = scanChunkRadius();

		scanQueue.clear();
		for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
			for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
				scanQueue.add(new long[]{chunkX, chunkZ});
			}
		}
		// Farthest first in the list — chunks pop off the tail, so nearest resolve soonest.
		scanQueue.sort(Comparator.comparingInt(c -> {
			int dx = (int) c[0] - centerChunkX;
			int dz = (int) c[1] - centerChunkZ;
			return -(dx * dx + dz * dz);
		}));
	}

	/**
	 * Process queued chunks until this tick's shared budget runs out.
	 * Findings commit IMMEDIATELY per chunk (nearest chunks were queued last,
	 * so what's around the player shows up within a tick or two of cycle start).
	 */
	private void stepScan(MinecraftClient client) {
		int tick = client.player.age;
		long pool;
		boolean burst = burstTicks > 0;
		if (burst) {
			burstTicks--;
			pool = BURST_BUDGET_NANOS;
		} else {
			pool = ScanBudget.takeBudget(tick, ScanBudget.Lane.ADVANCED_ESP, config);
			if (pool <= 0L) return;
		}
		ClientWorld world = client.world;
		long start = System.nanoTime();

		while (!scanQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) break;
			long[] coord = scanQueue.remove(scanQueue.size() - 1);
			int chunkX = (int) coord[0];
			int chunkZ = (int) coord[1];
			if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
			WorldChunk chunk = world.getChunk(chunkX, chunkZ);
			if (chunk == null || chunk.isEmpty()) continue;

			chunkBuffer.clear();
			scanChunk(chunk, scanMinY, scanMaxY, chunkBuffer);
			if (!chunkBuffer.isEmpty()) {
				chunkBuffer.sort(Comparator.comparingDouble(Finding::score).reversed());
				// Neighbouring same-type findings get ABSORBED (boxes unioned),
				// not dropped — dropping them is why a long tunnel or a wide
				// excavated room used to keep only one small fragment of outline.
				List<Finding> deduped = new ArrayList<>();
				for (Finding finding : chunkBuffer) {
					Finding match = duplicateOf(finding, deduped);
					if (match == null) deduped.add(finding);
					else match.absorb(finding);
				}
				mergeChunk(deduped, tick);
			}
		}
		if (!burst) ScanBudget.reportUsed(tick, ScanBudget.Lane.ADVANCED_ESP, System.nanoTime() - start);

		if (scanQueue.isEmpty()) {
			finishCycle(tick);
		}
	}

	/** All per-chunk detectors, sharing one section classification. */
	private void scanChunk(WorldChunk chunk, int minY, int maxY, List<Finding> out) {
		SectionFlags flags = classifySections(chunk);
		boolean wantSpawners = config.donutAdvancedShowSpawners || (config.donutStashPinger && config.donutStashShowSpawners);
		boolean wantStashBases = config.donutStashPinger && config.donutStashShowBases;
		if (wantSpawners || config.donutAdvancedShowPlaced || wantStashBases) {
			scanBlocksOfInterest(chunk, flags, minY, maxY, out, wantSpawners, config.donutAdvancedShowPlaced);
			// DonutSMP can replace the block palette with deepslate while still
			// transmitting the real block-entity map. Consume that authoritative
			// map too; otherwise Advanced ESP supplies no candidates to Stash
			// Pinger even when several loaded storage blocks are right below us.
			scanBlockEntitiesOfInterest(chunk, minY, maxY, out, wantSpawners, config.donutAdvancedShowPlaced);
		}
		if (config.donutAdvancedShowShafts) scanVerticalShafts(chunk, flags, minY, maxY, out);
		if (config.donutAdvancedShowTunnels) scanHorizontalTunnels(chunk, flags, minY, maxY, out);
		if (config.donutAdvancedShowPockets) scanExcavatedPockets(chunk, flags, minY, maxY, out);
	}

	/**
	 * Mask-resistant container/spawner scan. A lone chest is shown only as a
	 * placed marker; a yellow Base region still requires a conservative stash
	 * pattern, matching the Stash Pinger's false-positive gates.
	 */
	private void scanBlockEntitiesOfInterest(WorldChunk chunk, int minY, int maxY, List<Finding> out,
			boolean wantSpawners, boolean wantPlaced) {
		int storage = 0;
		int special = 0;
		int utility = 0;
		int minEvidenceY = Integer.MAX_VALUE;
		int maxEvidenceY = Integer.MIN_VALUE;

		for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
			BlockPos pos = entry.getKey();
			if (pos.getY() < minY || pos.getY() > maxY) continue;
			BlockEntityType<?> type = entry.getValue().getType();

			if (type == BlockEntityType.MOB_SPAWNER || type == BlockEntityType.TRIAL_SPAWNER) {
				if (wantSpawners) {
					out.add(new Finding(new Box(pos).expand(0.32, 0.32, 0.32),
							"Spawner", FindingType.SPAWNER, 110.0));
				}
				continue;
			}

			boolean isStorage = type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
					|| type == BlockEntityType.BARREL;
			boolean isSpecial = type == BlockEntityType.SHULKER_BOX || type == BlockEntityType.ENDER_CHEST;
			boolean isUtility = type == BlockEntityType.HOPPER || type == BlockEntityType.DISPENSER
					|| type == BlockEntityType.DROPPER || type == BlockEntityType.FURNACE
					|| type == BlockEntityType.BLAST_FURNACE || type == BlockEntityType.SMOKER
					|| type == BlockEntityType.BREWING_STAND || type == BlockEntityType.ENCHANTING_TABLE
					|| type == BlockEntityType.BEACON;
			if (!isStorage && !isSpecial && !isUtility) continue;

			if (isStorage) storage++;
			if (isSpecial) special++;
			if (isUtility) utility++;
			minEvidenceY = Math.min(minEvidenceY, pos.getY());
			maxEvidenceY = Math.max(maxEvidenceY, pos.getY());
			if (wantPlaced) {
				String label = isSpecial ? "Rare Storage" : isStorage ? "Storage" : "Utility";
				double score = isSpecial ? 92.0 : isStorage ? 80.0 : 70.0;
				out.add(new Finding(new Box(pos).expand(0.10, 0.10, 0.10), label, FindingType.PLACED, score));
			}
		}

		if (minEvidenceY == Integer.MAX_VALUE || maxEvidenceY > 48) return;
		boolean likelyBase = (special >= 1 && storage + utility >= 1)
				|| storage >= 3
				|| (storage >= 2 && utility >= 2)
				|| (utility >= 3 && storage >= 1);
		if (!likelyBase) return;

		int x0 = chunk.getPos().getStartX();
		int z0 = chunk.getPos().getStartZ();
		double score = 132.0 + storage * 8.0 + special * 18.0 + utility * 5.0;
		out.add(new Finding(new Box(x0, minEvidenceY - 1.0, z0,
				x0 + 16.0, maxEvidenceY + 2.0, z0 + 16.0), "Base", FindingType.BASE, score));
	}

	/** Merge one chunk's findings into the live list right away (no prune — that happens at cycle end). */
	private void mergeChunk(List<Finding> detected, int tick) {
		for (Finding raw : detected) {
			Finding existing = findMatching(raw);
			if (existing == null) {
				raw.markSeen(tick);
				findings.add(raw);
			} else {
				existing.updateFrom(raw, tick);
			}
		}
		// Soft cap during a cycle; the hard cap + sort happens in finishCycle.
		while (findings.size() > MAX_FINDINGS * 2) {
			findings.remove(findings.size() - 1);
		}
	}

	private void finishCycle(int tick) {
		failureCount = 0;
		lastCompletedTick = tick;
		lastCycleTicks = Math.max(1, tick - scanCycleStartTick);
		int stale = staleWindowTicks();
		findings.removeIf(finding -> tick - finding.lastSeenTick > stale);
		findings.sort(Comparator.comparingDouble((Finding finding) -> finding.displayScore(tick, stale)).reversed());
		while (findings.size() > MAX_FINDINGS) {
			findings.remove(findings.size() - 1);
		}
	}

	/** Findings stay alive for at least two scan cycles so slow incremental cycles never flicker. */
	private int staleWindowTicks() {
		return Math.max(STALE_TICKS, lastCycleTicks * 2 + FADE_OUT_TICKS);
	}

	private int scanChunkRadius() {
		MinecraftClient client = MinecraftClient.getInstance();
		int range = MathHelper.clamp(config.donutAdvancedEspRange, 48, 1024);
		int requested = MathHelper.ceil(range / 16.0F);
		int viewDistance = client.options == null ? MAX_CHUNK_RADIUS : client.options.getViewDistance().getValue();
		return MathHelper.clamp(requested, MIN_CHUNK_RADIUS, Math.min(MAX_CHUNK_RADIUS, viewDistance + 1));
	}

	private boolean isVisibleInAdvanced(FindingType type) {
		return switch (type) {
			case SHAFT -> config.donutAdvancedShowShafts;
			case TUNNEL -> config.donutAdvancedShowTunnels;
			case POCKET -> config.donutAdvancedShowPockets;
			case PLACED -> config.donutAdvancedShowPlaced;
			case SPAWNER -> config.donutAdvancedShowSpawners;
			case BASE -> true; // headline finding — always shown while the module is on
		};
	}

	private Finding findMatching(Finding raw) {
		Vec3d center = raw.center();
		Finding best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Finding existing : findings) {
			if (existing.type() != raw.type()) continue;
			double distance = center.squaredDistanceTo(existing.center());
			if (raw.type() == FindingType.PLACED) {
				if (distance < 1.25 && distance < bestDistance) {
					best = existing;
					bestDistance = distance;
				}
				continue;
			}
			if (raw.type() == FindingType.BASE) {
				// Bases merge only on genuine overlap — distance-merging let
				// neighbouring detections union into one giant runaway box.
				if (raw.box().intersects(existing.box()) && distance < bestDistance) {
					best = existing;
					bestDistance = distance;
				}
				continue;
			}
			if ((distance < 36.0 || raw.box().intersects(existing.box())) && distance < bestDistance) {
				best = existing;
				bestDistance = distance;
			}
		}
		return best;
	}

	// ── Per-section classification ──────────────────────────────────────────────

	/** Per-section facts used to skip work 16 Y levels at a time. */
	private static final class SectionFlags {
		final boolean[] empty;     // all air
		final boolean[] noAir;     // zero air blocks (untouched solid)
		final boolean[] hasTarget; // palette contains spawner/artificial candidates

		SectionFlags(int sections) {
			empty = new boolean[sections];
			noAir = new boolean[sections];
			hasTarget = new boolean[sections];
		}
	}

	private SectionFlags classifySections(WorldChunk chunk) {
		ChunkSection[] sections = chunk.getSectionArray();
		SectionFlags flags = new SectionFlags(sections.length);
		for (int i = 0; i < sections.length; i++) {
			ChunkSection section = sections[i];
			if (section == null || section.isEmpty()) {
				flags.empty[i] = true;
				continue;
			}
			// Palette-level checks: near-free, conservative (stale palette entries report as present).
			flags.noAir[i] = !section.hasAny(BlockState::isAir);
			flags.hasTarget[i] = section.hasAny(state -> isSpawnerBlock(state) || isArtificialBlock(state));
		}
		return flags;
	}

	private int sectionIndexAt(WorldChunk chunk, int y) {
		int bottom = chunk.getBottomY();
		if (y < bottom || y >= bottom + chunk.getHeight()) return -1;
		return chunk.getSectionIndex(y);
	}

	private boolean flagAt(WorldChunk chunk, boolean[] flags, int y, boolean outOfBounds) {
		int index = sectionIndexAt(chunk, y);
		if (index < 0 || index >= flags.length) return outOfBounds;
		return flags[index];
	}

	/** Single pass for spawners + player-placed blocks, restricted to sections whose palette can contain them. */
	private void scanBlocksOfInterest(WorldChunk chunk, SectionFlags flags, int minY, int maxY, List<Finding> out,
			boolean wantSpawners, boolean wantPlaced) {
		Map<CellKey, Cluster> clusters = wantPlaced ? new HashMap<>() : null;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		ChunkSection[] sections = chunk.getSectionArray();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		StructureTally structures = new StructureTally();

		for (int y = minY; y <= maxY; y++) {
			int index = sectionIndexAt(chunk, y);
			if (index < 0 || index >= sections.length || !flags.hasTarget[index]) {
				y = sectionTop(y);
				continue;
			}
			ChunkSection section = sections[index];
			int localY = y & 15;
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					BlockState state = section.getBlockState(lx, localY, lz);
					structures.accept(state);
					if (isSpawnerBlock(state)) {
						if (wantSpawners) {
							int x = startX + lx;
							int z = startZ + lz;
							Box box = new Box(x, y, z, x + 1, y + 1, z + 1).expand(0.32, 0.32, 0.32);
							out.add(new Finding(box, "Spawner", FindingType.SPAWNER, 98.0));
						}
					} else if (clusters != null && isArtificialBlock(state)) {
						int x = startX + lx;
						int z = startZ + lz;
						CellKey key = new CellKey(Math.floorDiv(x, 8), Math.floorDiv(y, 8), Math.floorDiv(z, 8));
						Cluster cluster = clusters.computeIfAbsent(key, ignored -> new Cluster());
						cluster.add(x, y, z);
						if (isStrongPlayerBlock(state)) cluster.strong++;
						if (isContainerBlock(state)) cluster.containers++;
					}
				}
			}
		}

		if (clusters == null) return;
		// Generated structures (dungeons, mineshafts, strongholds, ruined
		// portals, ancient cities, trail ruins...) are full of blocks that read
		// as "player-placed". When the chunk carries a structure fingerprint,
		// none of its clusters are trusted — only spawner findings survive.
		if (structures.isGeneratedStructure(scanNether)) return;

		int baseBlocks = 0;
		int baseStrong = 0;
		int baseContainers = 0;
		Box baseBox = null;
		for (Cluster cluster : clusters.values()) {
			if (cluster.count < 2) continue;
			Box box = cluster.box().expand(1.5, 1.0, 1.5);
			if (hasGeneratedStructureSignature(chunk, pos, box)) continue;
			// Cobble/planks/stone-brick-only clusters are too common in natural
			// generation to flag alone; demand a strong player block (chest,
			// torch, glass...) or a genuinely substantial pile of material.
			if (cluster.strong == 0 && cluster.count < 8) continue;
			if (cluster.count <= 6) {
				for (BlockPos block : cluster.blocks) {
					Box blockBox = new Box(block).expand(0.08, 0.08, 0.08);
					out.add(new Finding(blockBox, "Block", FindingType.PLACED, 54.0 + cluster.count));
				}
			} else {
				out.add(new Finding(cluster.box().expand(0.12, 0.12, 0.12), "Blocks", FindingType.PLACED, 58.0 + cluster.count * 2.0));
			}
			// Underground clusters feed the chunk-level Base detector. (Capped at
			// y<=48 so surface villages don't paint Base boxes everywhere.)
			if (cluster.maxY <= 48) {
				baseBlocks += cluster.count;
				baseStrong += cluster.strong;
				baseContainers += cluster.containers;
				baseBox = baseBox == null ? cluster.box() : baseBox.union(cluster.box());
			}
		}

		// Lots of player-placed material in one chunk = a built area. A Base
		// headline needs real evidence though: plenty of material, several blocks
		// worldgen never arranges this way, AND at least one actual container.
		// The container requirement is what stops crystal/anchor PvP craters
		// (obsidian + crying obsidian everywhere, no chests) from being boxed
		// yellow as "bases".
		if (baseBox != null && baseBlocks >= 14 && baseStrong >= 4 && baseContainers >= 1) {
			// Box the FULL chunk footprint (Y from the clusters), not the tight
			// cluster AABB. A base is rarely a neat rectangle — the tight box kept
			// flagging only the cluster cores and left the rest of the build
			// uncovered. Full-chunk boxes also tile seamlessly across chunks, and
			// the renderer merges touching base chunks into one region, so a
			// multi-chunk irregular base is covered whole instead of in parts.
			int x0 = chunk.getPos().getStartX();
			int z0 = chunk.getPos().getStartZ();
			Box fullChunk = new Box(x0, baseBox.minY - 1.0, z0, x0 + 16.0, baseBox.maxY + 1.0, z0 + 16.0);
			out.add(new Finding(fullChunk, "Base", FindingType.BASE, 130.0 + baseBlocks));
		}
	}

	/** Real storage / utility containers — the load-bearing evidence for a Base. */
	private boolean isContainerBlock(BlockState state) {
		return state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)
				|| state.isOf(Blocks.ENDER_CHEST) || state.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock
				|| state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)
				|| state.isOf(Blocks.HOPPER) || state.isOf(Blocks.DISPENSER) || state.isOf(Blocks.DROPPER)
				|| state.isOf(Blocks.BREWING_STAND);
	}

	/**
	 * Whole-chunk tally of blocks that fingerprint world-generated structures.
	 * The old per-cluster check kept missing dungeons/mineshafts whose mossy
	 * cobble or spawner sat just outside one cluster's small search box; this
	 * sees the fingerprint anywhere in the chunk's scanned sections.
	 */
	private static final class StructureTally {
		int spawners, mossy, cobwebs, rails, suspicious, cryingObsidian, netherrack,
				crackedStoneBricks, infested, sculk, reinforced, tnt, stoneBricks;

		void accept(BlockState state) {
			Block block = state.getBlock();
			if (block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER || block == Blocks.VAULT) spawners++;
			else if (MOSSY_STRUCTURE_BLOCKS.contains(block)) mossy++;
			else if (block == Blocks.COBWEB) cobwebs++;
			else if (RAIL_BLOCKS.contains(block)) rails++;
			else if (block == Blocks.SUSPICIOUS_SAND || block == Blocks.SUSPICIOUS_GRAVEL) suspicious++;
			else if (block == Blocks.CRYING_OBSIDIAN) cryingObsidian++;
			else if (block == Blocks.NETHERRACK) netherrack++;
			else if (block == Blocks.CRACKED_STONE_BRICKS) crackedStoneBricks++;
			else if (block == Blocks.INFESTED_STONE || block == Blocks.INFESTED_COBBLESTONE
					|| block == Blocks.INFESTED_STONE_BRICKS || block == Blocks.INFESTED_MOSSY_STONE_BRICKS
					|| block == Blocks.INFESTED_CRACKED_STONE_BRICKS || block == Blocks.INFESTED_CHISELED_STONE_BRICKS) infested++;
			else if (block == Blocks.SCULK || block == Blocks.SCULK_CATALYST || block == Blocks.SCULK_SHRIEKER
					|| block == Blocks.SCULK_SENSOR || block == Blocks.SCULK_VEIN) sculk++;
			else if (block == Blocks.REINFORCED_DEEPSLATE) reinforced++;
			else if (block == Blocks.TNT) tnt++;
			else if (block == Blocks.STONE_BRICKS || block == Blocks.CHISELED_STONE_BRICKS) stoneBricks++;
		}

		boolean isGeneratedStructure(boolean nether) {
			// Dungeons, mineshafts, strongholds, desert temples, ancient cities.
			if (spawners >= 1 || cobwebs >= 1 || suspicious >= 1 || reinforced >= 1) return true;
			if (mossy >= 2 || rails >= 2 || sculk >= 3 || infested >= 1) return true;
			if (crackedStoneBricks >= 2 || (stoneBricks >= 10 && mossy + crackedStoneBricks >= 1)) return true;
			if (tnt >= 3) return true;
			// Ruined portals leak netherrack/crying obsidian into the overworld.
			if (!nether && (netherrack >= 2 || (cryingObsidian >= 1 && netherrack >= 1))) return true;
			return false;
		}
	}

	private void scanVerticalShafts(WorldChunk chunk, SectionFlags flags, int minY, int maxY, List<Finding> out) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();

		for (int z = startZ + 1; z < startZ + 15; z++) {
			for (int x = startX + 1; x < startX + 15; x++) {
				int runStart = Integer.MIN_VALUE;
				int runScore = 0;
				for (int y = minY; y <= maxY; y++) {
					// All-air sections have no walls; all-solid sections have no air —
					// neither can hold a shaft slice. Close any run and jump past.
					int index = sectionIndexAt(chunk, y);
					if (index < 0 || index >= flags.empty.length || flags.empty[index] || flags.noAir[index]) {
						if (runStart != Integer.MIN_VALUE) {
							addShaftIfStrong(chunk, pos, out, x, z, runStart, y - 1, runScore);
							runStart = Integer.MIN_VALUE;
							runScore = 0;
						}
						y = sectionTop(y);
						continue;
					}
					boolean shaft = isShaftSlice(chunk, pos, x, y, z);
					if (shaft) {
						if (runStart == Integer.MIN_VALUE) runStart = y;
						runScore++;
					} else if (runStart != Integer.MIN_VALUE) {
						addShaftIfStrong(chunk, pos, out, x, z, runStart, y - 1, runScore);
						runStart = Integer.MIN_VALUE;
						runScore = 0;
					}
				}
				if (runStart != Integer.MIN_VALUE) {
					addShaftIfStrong(chunk, pos, out, x, z, runStart, maxY, runScore);
				}
			}
		}
	}

	private void addShaftIfStrong(WorldChunk chunk, BlockPos.Mutable pos, List<Finding> out, int x, int z, int y0, int y1, int score) {
		int height = y1 - y0 + 1;
		if (height < 6 || score < height - 1) return;
		Box box = new Box(x, y0, z, x + 1, y1 + 1, z + 1).expand(0.22, 0.0, 0.22);
		if (hasGeneratedStructureSignature(chunk, pos, box)) return;
		out.add(new Finding(box, "Shaft", FindingType.SHAFT, 78.0 + height * 2.5));
	}

	private boolean isShaftSlice(WorldChunk chunk, BlockPos.Mutable pos, int x, int y, int z) {
		pos.set(x, y, z);
		if (!blockAt(chunk, pos).isAir()) return false;
		int nearbyAir = 0;
		int wallSolid = 0;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dz == 0) continue;
				pos.set(x + dx, y, z + dz);
				BlockState state = blockAt(chunk, pos);
				if (state.isAir()) nearbyAir++;
				if (isNaturalSolid(state)) wallSolid++;
			}
		}
		return nearbyAir <= 2 && wallSolid >= 5;
	}

	private void scanHorizontalTunnels(WorldChunk chunk, SectionFlags flags, int minY, int maxY, List<Finding> out) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();

		for (int y = minY; y <= maxY - 2; y++) {
			// A tunnel slice needs 2-high air at y..y+1 and solid floor/walls —
			// skip Y rows where either is impossible at the section level.
			boolean airImpossible = flagAt(chunk, flags.noAir, y, false) && flagAt(chunk, flags.noAir, y + 1, false);
			boolean solidImpossible = flagAt(chunk, flags.empty, y - 1, true) && flagAt(chunk, flags.empty, y, true)
					&& flagAt(chunk, flags.empty, y + 1, true) && flagAt(chunk, flags.empty, y + 2, true);
			if (airImpossible || solidImpossible) {
				continue;
			}
			for (int z = startZ + 1; z < startZ + 15; z++) {
				int x = startX + 1;
				while (x < startX + 15) {
					if (!isTunnelSliceX(chunk, pos, x, y, z)) {
						x++;
						continue;
					}
					int x0 = x;
					while (x < startX + 15 && isTunnelSliceX(chunk, pos, x, y, z)) x++;
					if (x - x0 >= 6) {
						Box box = new Box(x0, y, z, x, y + 2, z + 1).expand(0.08, 0.05, 0.28);
						if (hasGeneratedStructureSignature(chunk, pos, box)) continue;
						out.add(new Finding(box, "Tunnel", FindingType.TUNNEL, 70.0 + (x - x0) * 2.0));
					}
				}
			}

			for (int x = startX + 1; x < startX + 15; x++) {
				int z = startZ + 1;
				while (z < startZ + 15) {
					if (!isTunnelSliceZ(chunk, pos, x, y, z)) {
						z++;
						continue;
					}
					int z0 = z;
					while (z < startZ + 15 && isTunnelSliceZ(chunk, pos, x, y, z)) z++;
					if (z - z0 >= 6) {
						Box box = new Box(x, y, z0, x + 1, y + 2, z).expand(0.28, 0.05, 0.08);
						if (hasGeneratedStructureSignature(chunk, pos, box)) continue;
						out.add(new Finding(box, "Tunnel", FindingType.TUNNEL, 70.0 + (z - z0) * 2.0));
					}
				}
			}
		}
	}

	private boolean isTunnelSliceX(WorldChunk chunk, BlockPos.Mutable pos, int x, int y, int z) {
		return isTwoHighAir(chunk, pos, x, y, z)
				&& isTwoHighAir(chunk, pos, x - 1, y, z)
				&& isTwoHighAir(chunk, pos, x + 1, y, z)
				&& sideWallSolid(chunk, pos, x, y, z - 1)
				&& sideWallSolid(chunk, pos, x, y, z + 1)
				&& compactAirNeighborhood(chunk, pos, x, y, z);
	}

	private boolean isTunnelSliceZ(WorldChunk chunk, BlockPos.Mutable pos, int x, int y, int z) {
		return isTwoHighAir(chunk, pos, x, y, z)
				&& isTwoHighAir(chunk, pos, x, y, z - 1)
				&& isTwoHighAir(chunk, pos, x, y, z + 1)
				&& sideWallSolid(chunk, pos, x - 1, y, z)
				&& sideWallSolid(chunk, pos, x + 1, y, z)
				&& compactAirNeighborhood(chunk, pos, x, y, z);
	}

	private boolean isTwoHighAir(WorldChunk chunk, BlockPos.Mutable pos, int x, int y, int z) {
		pos.set(x, y, z);
		if (!blockAt(chunk, pos).isAir()) return false;
		pos.set(x, y + 1, z);
		if (!blockAt(chunk, pos).isAir()) return false;
		pos.set(x, y - 1, z);
		return isNaturalSolid(blockAt(chunk, pos));
	}

	private boolean sideWallSolid(WorldChunk chunk, BlockPos.Mutable pos, int x, int y, int z) {
		pos.set(x, y, z);
		boolean lower = isNaturalSolid(blockAt(chunk, pos));
		pos.set(x, y + 1, z);
		boolean upper = isNaturalSolid(blockAt(chunk, pos));
		pos.set(x, y + 2, z);
		boolean ceiling = isNaturalSolid(blockAt(chunk, pos));
		return lower && upper && ceiling;
	}

	private boolean compactAirNeighborhood(WorldChunk chunk, BlockPos.Mutable pos, int x, int y, int z) {
		int air = 0;
		int fluid = 0;
		for (int dy = -1; dy <= 2; dy++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					pos.set(x + dx, y + dy, z + dz);
					BlockState state = blockAt(chunk, pos);
					if (state.isAir()) air++;
					if (!state.getFluidState().isEmpty()) fluid++;
				}
			}
		}
		return fluid == 0 && air <= 28;
	}

	private void scanExcavatedPockets(WorldChunk chunk, SectionFlags flags, int minY, int maxY, List<Finding> out) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		int yStart = Math.max(minY, chunk.getBottomY() + 2);

		for (int y = yStart; y <= maxY - 3; y += 3) {
			// A pocket needs both air (the room) and solids (floor/ceiling/walls);
			// skip windows that are entirely air sections or entirely solid sections.
			boolean allEmpty = true;
			for (int dy = -1; dy <= 4 && allEmpty; dy++) {
				if (!flagAt(chunk, flags.empty, y + dy, true)) allEmpty = false;
			}
			boolean allSolid = true;
			for (int dy = 0; dy <= 2 && allSolid; dy++) {
				if (!flagAt(chunk, flags.noAir, y + dy, false)) allSolid = false;
			}
			if (allEmpty || allSolid) continue;
			for (int z = startZ; z <= startZ + 12; z += 4) {
				for (int x = startX; x <= startX + 12; x += 4) {
					PocketStats stats = pocketStats(chunk, pos, x, y, z);
					if (!stats.isStrongPocket()) continue;
					Box box = new Box(x, y, z, x + 4, y + 3, z + 4);
					if (hasGeneratedStructureSignature(chunk, pos, box.expand(1.0, 1.0, 1.0))) continue;
					out.add(new Finding(box, "Pocket", FindingType.POCKET, stats.score()));
				}
			}
		}
	}

	private PocketStats pocketStats(WorldChunk chunk, BlockPos.Mutable pos, int x0, int y0, int z0) {
		int air = 0;
		int fluids = 0;
		int artificial = 0;
		int floorSolid = 0;
		int ceilingSolid = 0;
		int boundarySolid = 0;
		int expandedAir = 0;
		int layerAir0 = 0;
		int layerAir1 = 0;
		int layerAir2 = 0;

		for (int y = y0; y < y0 + 3; y++) {
			for (int z = z0; z < z0 + 4; z++) {
				for (int x = x0; x < x0 + 4; x++) {
					pos.set(x, y, z);
					BlockState state = blockAt(chunk, pos);
					if (state.isAir()) {
						air++;
						if (y == y0) layerAir0++;
						else if (y == y0 + 1) layerAir1++;
						else layerAir2++;
					}
					if (!state.getFluidState().isEmpty()) fluids++;
					if (isArtificialBlock(state)) artificial++;
					if (x == x0 || x == x0 + 3 || z == z0 || z == z0 + 3) {
						if (isNaturalSolid(state)) boundarySolid++;
					}
				}
			}
		}

		for (int z = z0; z < z0 + 4; z++) {
			for (int x = x0; x < x0 + 4; x++) {
				pos.set(x, y0 - 1, z);
				if (isNaturalSolid(blockAt(chunk, pos))) floorSolid++;
				pos.set(x, y0 + 3, z);
				if (isNaturalSolid(blockAt(chunk, pos))) ceilingSolid++;
			}
		}

		for (int y = y0 - 1; y <= y0 + 4; y++) {
			for (int z = z0 - 1; z <= z0 + 4; z++) {
				for (int x = x0 - 1; x <= x0 + 4; x++) {
					pos.set(x, y, z);
					if (blockAt(chunk, pos).isAir()) expandedAir++;
				}
			}
		}
		return new PocketStats(air, fluids, artificial, floorSolid, ceilingSolid, boundarySolid,
				expandedAir, layerAir0, layerAir1, layerAir2);
	}

	private Finding duplicateOf(Finding finding, List<Finding> existing) {
		Vec3d center = finding.center();
		for (Finding other : existing) {
			if (finding.type() != other.type()) continue;
			if (finding.type() == FindingType.PLACED) {
				if (center.squaredDistanceTo(other.center()) < 1.25) return other;
				continue;
			}
			if (center.squaredDistanceTo(other.center()) < 24.0) return other;
			if (finding.box().intersects(other.box())) return other;
		}
		return null;
	}

	/** Last Y level inside the 16-block section containing {@code y}. */
	private static int sectionTop(int y) {
		return ((Math.floorDiv(y, 16) + 1) << 4) - 1;
	}

	private BlockState blockAt(WorldChunk chunk, BlockPos pos) {
		if ((pos.getX() >> 4) != chunk.getPos().x || (pos.getZ() >> 4) != chunk.getPos().z) {
			return Blocks.BEDROCK.getDefaultState();
		}
		int bottom = chunk.getBottomY();
		if (pos.getY() < bottom || pos.getY() >= bottom + chunk.getHeight()) {
			return Blocks.BEDROCK.getDefaultState();
		}
		return chunk.getBlockState(pos);
	}

	private boolean hasGeneratedStructureSignature(WorldChunk chunk, BlockPos.Mutable pos, Box box) {
		int minX = MathHelper.floor(box.minX);
		int minY = MathHelper.floor(Math.max(chunk.getBottomY(), box.minY));
		int minZ = MathHelper.floor(box.minZ);
		int maxX = MathHelper.floor(box.maxX);
		int maxY = MathHelper.floor(Math.min(chunk.getBottomY() + chunk.getHeight() - 1, box.maxY));
		int maxZ = MathHelper.floor(box.maxZ);
		int rails = 0;
		int cobwebs = 0;
		int mossy = 0;
		int suspicious = 0;
		int water = 0;
		int structureWood = 0;
		int structureStone = 0;
		int chests = 0;

		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					pos.set(x, y, z);
					BlockState state = blockAt(chunk, pos);
					if (isSpawnerBlock(state)) return true;
					if (isRailBlock(state)) rails++;
					if (state.isOf(Blocks.COBWEB)) cobwebs++;
					if (isMossyStructureBlock(state)) mossy++;
					if (isSuspiciousStructureBlock(state)) suspicious++;
					if (!state.getFluidState().isEmpty()) water++;
					if (isShipwreckWoodBlock(state)) structureWood++;
					if (isStructureStoneBlock(state)) structureStone++;
					if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) chests++;
				}
			}
		}

		return rails >= 2
				|| cobwebs >= 1
				|| mossy >= 2
				|| suspicious >= 1
				|| (structureWood >= 6 && water >= 4)
				|| (structureStone >= 10 && (mossy >= 1 || chests >= 1));
	}

	private boolean isSpawnerBlock(BlockState state) {
		return state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.TRIAL_SPAWNER);
	}

	// Set lookups instead of isOf chains — these run for every block in MIXED
	// sections, so a single hash probe beats up to 26 reference compares.
	private static final Set<Block> RAIL_BLOCKS = Set.of(
			Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL);

	private static final Set<Block> MOSSY_STRUCTURE_BLOCKS = Set.of(
			Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_STONE_BRICKS,
			Blocks.MOSSY_COBBLESTONE_SLAB, Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_WALL,
			Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_WALL);

	private static final Set<Block> SHIPWRECK_WOOD_BLOCKS = Set.of(
			Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_PLANKS,
			Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.DARK_OAK_LOG,
			Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_DARK_OAK_LOG);

	private static final Set<Block> STRUCTURE_STONE_BLOCKS = Set.of(
			Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
			Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS,
			Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);

	private static final Set<Block> ARTIFICIAL_BLOCKS = Set.of(
			Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
			Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.LADDER,
			Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL,
			Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
			Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE,
			Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.COBBLESTONE,
			Blocks.COBBLED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS,
			Blocks.STONE_BRICKS, Blocks.GLASS, Blocks.OBSIDIAN);

	private boolean isRailBlock(BlockState state) {
		return RAIL_BLOCKS.contains(state.getBlock());
	}

	private boolean isMossyStructureBlock(BlockState state) {
		return MOSSY_STRUCTURE_BLOCKS.contains(state.getBlock());
	}

	private boolean isSuspiciousStructureBlock(BlockState state) {
		return state.isOf(Blocks.SUSPICIOUS_SAND) || state.isOf(Blocks.SUSPICIOUS_GRAVEL);
	}

	private boolean isShipwreckWoodBlock(BlockState state) {
		return SHIPWRECK_WOOD_BLOCKS.contains(state.getBlock());
	}

	private boolean isStructureStoneBlock(BlockState state) {
		return STRUCTURE_STONE_BLOCKS.contains(state.getBlock());
	}

	// Decor blocks that worldgen also uses (ruined portals, strongholds, trail
	// ruins, ancient cities, bastions) — they still count as placed material,
	// but they can't CONFIRM a base on their own.
	private static final Set<Block> STRUCTURE_PRONE_DECOR = Set.of(
			Blocks.SMOOTH_STONE, Blocks.BRICKS, Blocks.IRON_BARS, Blocks.IRON_CHAIN,
			Blocks.CRYING_OBSIDIAN, Blocks.GOLD_BLOCK, Blocks.TNT, Blocks.DECORATED_POT,
			Blocks.LANTERN, Blocks.SOUL_LANTERN);

	private static final Set<Block> STRONG_PLAYER_BLOCKS = Set.of(
			Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
			Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.LADDER,
			Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
			Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE,
			Blocks.GLASS, Blocks.OBSIDIAN);

	private boolean isArtificialBlock(BlockState state) {
		return ARTIFICIAL_BLOCKS.contains(state.getBlock())
				|| PlayerPlacedBlocks.isBuildDecor(state, scanNether);
	}

	/** Blocks that essentially only a player places underground — these CONFIRM a base. */
	private boolean isStrongPlayerBlock(BlockState state) {
		Block block = state.getBlock();
		if (STRUCTURE_PRONE_DECOR.contains(block)) return false;
		if (STRONG_PLAYER_BLOCKS.contains(block)) return true;
		return PlayerPlacedBlocks.isBuildDecor(state, scanNether);
	}

	private boolean isNaturalSolid(BlockState state) {
		return !state.isAir()
				&& state.getFluidState().isEmpty()
				&& !isArtificialBlock(state)
				&& state.isSolid();
	}

	private void drawWorldLabel(WorldRenderContext ctx, MatrixStack matrices,
			Finding finding, Vec3d camera, MinecraftClient mc, float renderTick, float fade) {
		Vec3d center = finding.center();
		float bob = bob(renderTick, finding.animationSeed());
		double y = labelY(finding, mc.player.getY()) + 0.58 + bob;
		int color = withAlpha(finding.color().argb(), fade);
		drawBillboardText(ctx, matrices, camera, mc, finding.label(), center.x, y, center.z, color, 0.045F, fade);
	}

	private float bob(float renderTick, float seed) {
		float wave = 0.5F + 0.5F * (float) Math.sin(renderTick * 0.18F + seed);
		float eased = wave * wave * (3.0F - 2.0F * wave);
		return MathHelper.lerp(eased, -0.18F, 0.22F);
	}

	private void drawBillboardText(WorldRenderContext ctx, MatrixStack matrices, Vec3d camera, MinecraftClient mc,
			String text, double x, double y, double z, int color, float scale, float fade) {
		int textWidth = mc.textRenderer.getWidth(text);
		matrices.push();
		matrices.translate(x - camera.x, y - camera.y, z - camera.z);
		matrices.multiply(mc.gameRenderer.getCamera().getRotation());
		matrices.scale(-scale, -scale, scale);
		Matrix4f matrix = new Matrix4f(matrices.peek().getPositionMatrix());
		mc.textRenderer.draw(text, -textWidth * 0.5F, 0.0F, color, true, matrix,
				ctx.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, withAlpha(0x85000000, fade), 0x00F000F0);
		matrices.pop();
	}

	private int withAlpha(int color, float alphaScale) {
		int alpha = MathHelper.clamp(Math.round(((color >>> 24) & 0xFF) * alphaScale), 0, 255);
		return (color & 0x00FFFFFF) | (alpha << 24);
	}

	private double labelY(Finding finding, double playerY) {
		return Math.max(finding.box().maxY + 1.15, playerY + 2.8);
	}

	private void drawFilledBox(VertexConsumer buf, Matrix4fc pos, Box box, Vec3d camera, AreaColor color, float alpha) {
		float x0 = (float) (box.minX - camera.x), y0 = (float) (box.minY - camera.y), z0 = (float) (box.minZ - camera.z);
		float x1 = (float) (box.maxX - camera.x), y1 = (float) (box.maxY - camera.y), z1 = (float) (box.maxZ - camera.z);
		quad(buf, pos, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, color, alpha);
		quad(buf, pos, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, color, alpha);
		quad(buf, pos, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, color, alpha);
		quad(buf, pos, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, color, alpha);
		quad(buf, pos, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, color, alpha);
		quad(buf, pos, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, color, alpha);
	}

	private void quad(VertexConsumer buf, Matrix4fc pos,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz,
			AreaColor color, float alpha) {
		buf.vertex(pos, ax, ay, az).color(color.r(), color.g(), color.b(), alpha);
		buf.vertex(pos, bx, by, bz).color(color.r(), color.g(), color.b(), alpha);
		buf.vertex(pos, cx, cy, cz).color(color.r(), color.g(), color.b(), alpha);
		buf.vertex(pos, dx, dy, dz).color(color.r(), color.g(), color.b(), alpha);
	}

	private void drawOutline(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d camera, AreaColor color, float alpha) {
		line(buf, pos, entry, camera, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, color, alpha);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, color, alpha);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, color, alpha);
		line(buf, pos, entry, camera, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ, color, alpha);
		line(buf, pos, entry, camera, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, color, alpha);
		line(buf, pos, entry, camera, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, color, alpha);
		line(buf, pos, entry, camera, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color, alpha);
		line(buf, pos, entry, camera, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, color, alpha);
		line(buf, pos, entry, camera, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, color, alpha);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, color, alpha);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, color, alpha);
		line(buf, pos, entry, camera, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, color, alpha);
	}

	private void line(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry, Vec3d camera,
			double ax, double ay, double az, double bx, double by, double bz, AreaColor color, float alpha) {
		float x0 = (float) (ax - camera.x);
		float y0 = (float) (ay - camera.y);
		float z0 = (float) (az - camera.z);
		float x1 = (float) (bx - camera.x);
		float y1 = (float) (by - camera.y);
		float z1 = (float) (bz - camera.z);
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0E-6F) return;
		buf.vertex(pos, x0, y0, z0).color(color.r(), color.g(), color.b(), alpha).normal(entry, dx / len, dy / len, dz / len).lineWidth(1.0F);
		buf.vertex(pos, x1, y1, z1).color(color.r(), color.g(), color.b(), alpha).normal(entry, dx / len, dy / len, dz / len).lineWidth(1.0F);
	}

	private static RenderLayer createFillLayer() {
		RenderPipeline pipeline = RenderPipelinesInvoker.profps$register(RenderPipeline.builder()
				.withLocation(Identifier.of(ProFPS.MOD_ID, "pipeline/advanced_esp_fills"))
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withVertexShader("core/position_color")
				.withFragmentShader("core/position_color")
				.withBlend(BlendFunction.TRANSLUCENT)
				.withCull(false)
				.withDepthWrite(false)
				.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
				.withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
				.build());
		return RenderLayerInvoker.profps$of("profps_advanced_esp_fills",
				RenderSetup.builder(pipeline).translucent()
						.expectedBufferSize(RenderPipelines.DEBUG_FILLED_BOX.getVertexFormat().getVertexSize() * 2048)
						.build());
	}

	private enum FindingType {
		SHAFT(new AreaColor(1.0F, 0.22F, 0.78F, 0xFFFF38C7)),
		TUNNEL(new AreaColor(0.12F, 0.86F, 1.0F, 0xFF1FDCFF)),
		POCKET(new AreaColor(1.0F, 0.70F, 0.18F, 0xFFFFB32E)),
		PLACED(new AreaColor(1.0F, 0.18F, 0.14F, 0xFFFF2E24)),
		SPAWNER(new AreaColor(0.18F, 0.92F, 1.0F, 0xFF2EECFF)),
		BASE(new AreaColor(1.0F, 0.83F, 0.29F, 0xFFFFD44A));

		private final AreaColor color;

		FindingType(AreaColor color) {
			this.color = color;
		}
	}

	private static final class Finding {
		private Box box;
		private final String label;
		private final FindingType type;
		private double score;
		private int firstSeenTick;
		private int lastSeenTick;
		private final float animationSeed;

		Finding(Box box, String label, FindingType type, double score) {
			this.box = box;
			this.label = label;
			this.type = type;
			this.score = score;
			Vec3d center = center();
			this.animationSeed = (float) ((center.x * 0.37 + center.y * 0.11 + center.z * 0.23) % (Math.PI * 2.0));
		}

		Vec3d center() {
			return new Vec3d((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5);
		}

		Box box() {
			return box;
		}

		String label() {
			return label;
		}

		FindingType type() {
			return type;
		}

		double score() {
			return score;
		}

		AreaColor color() {
			return type.color;
		}

		float animationSeed() {
			return animationSeed;
		}

		void markSeen(int tick) {
			firstSeenTick = tick;
			lastSeenTick = tick;
		}

		void updateFrom(Finding raw, int tick) {
			if (type == FindingType.BASE) {
				// Overlapping base areas union so one box covers the built area,
				// but capped — unbounded unions were the giant yellow boxes. Past
				// the cap, the freshest evidence box wins outright.
				Box merged = box.union(raw.box);
				box = merged.getLengthX() <= 36.0 && merged.getLengthZ() <= 36.0 ? merged : raw.box;
			} else if (type == FindingType.PLACED) {
				box = blend(box, raw.box, 0.35);
			} else {
				// Tunnel/shaft/pocket re-detections EXTEND the outline — blending
				// pulled the box toward each new segment and left most of the
				// area uncovered. Only ballooning merges (e.g. perpendicular
				// crossings) fall back to tracking the freshest box.
				Box merged = tryUnion(type, box, raw.box);
				box = merged != null ? merged : blend(box, raw.box, 0.35);
			}
			score = Math.max(score * 0.82, raw.score);
			lastSeenTick = tick;
		}

		/** Merge a same-area detection from the same scan pass into this one. */
		void absorb(Finding other) {
			if (type != FindingType.PLACED) {
				Box merged = tryUnion(type, box, other.box);
				if (merged != null) box = merged;
			}
			score = Math.max(score, other.score);
		}

		/**
		 * Union two boxes when the result still tightly describes them (roughly
		 * collinear/adjacent areas); null when the union would mostly be empty
		 * space, so callers can fall back instead of painting air.
		 */
		private static Box tryUnion(FindingType type, Box a, Box b) {
			Box union = a.union(b);
			double combined = volume(a) + volume(b);
			if (volume(union) > combined * 2.5 + 16.0) return null;
			// A tunnel is a thin line by definition — merging two PARALLEL
			// tunnels into one fat slab looked goofy. Keep unions tunnel-shaped.
			if (type == FindingType.TUNNEL && Math.min(union.getLengthX(), union.getLengthZ()) > 3.4) return null;
			return union;
		}

		private static double volume(Box box) {
			return box.getLengthX() * box.getLengthY() * box.getLengthZ();
		}

		float fade(float renderTick, int staleTicks) {
			float in = MathHelper.clamp((renderTick - firstSeenTick) / FADE_IN_TICKS, 0.0F, 1.0F);
			float out = MathHelper.clamp((lastSeenTick + staleTicks - renderTick) / FADE_OUT_TICKS, 0.0F, 1.0F);
			return smooth(in) * smooth(out);
		}

		double displayScore(int tick, int staleTicks) {
			return score * fade(tick, staleTicks);
		}

		private static float smooth(float value) {
			return value * value * (3.0F - 2.0F * value);
		}

		private static Box blend(Box from, Box to, double t) {
			return new Box(
					MathHelper.lerp(t, from.minX, to.minX),
					MathHelper.lerp(t, from.minY, to.minY),
					MathHelper.lerp(t, from.minZ, to.minZ),
					MathHelper.lerp(t, from.maxX, to.maxX),
					MathHelper.lerp(t, from.maxY, to.maxY),
					MathHelper.lerp(t, from.maxZ, to.maxZ)
			);
		}
	}

	private record AreaColor(float r, float g, float b, int argb) {
	}

	public record AreaSnapshot(Box box, String label, Vec3d center, double score) {
	}

	private record CellKey(int x, int y, int z) {
	}

	private static final class Cluster {
		private final List<BlockPos> blocks = new ArrayList<>();
		private int minX = Integer.MAX_VALUE;
		private int minY = Integer.MAX_VALUE;
		private int minZ = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE;
		private int maxY = Integer.MIN_VALUE;
		private int maxZ = Integer.MIN_VALUE;
		private int count;
		private int strong;
		private int containers;

		void add(int x, int y, int z) {
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
			if (blocks.size() < 12) {
				blocks.add(new BlockPos(x, y, z));
			}
			count++;
		}

		Box box() {
			return new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
		}
	}

	private record PocketStats(int air, int fluids, int artificial, int floorSolid,
			int ceilingSolid, int boundarySolid, int expandedAir,
			int layerAir0, int layerAir1, int layerAir2) {
		boolean isStrongPocket() {
			if (fluids > 0) return false;
			if (air < 22 || air > 40) return false;
			int outsideAir = expandedAir - air;
			if (outsideAir > 14) return false;
			int minLayerAir = Math.min(layerAir0, Math.min(layerAir1, layerAir2));
			int maxLayerAir = Math.max(layerAir0, Math.max(layerAir1, layerAir2));
			if (minLayerAir < 6 || maxLayerAir - minLayerAir > 7) return false;
			if (floorSolid < 12 || ceilingSolid < 10 || boundarySolid < 20) return false;
			if (artificial > 0) return true;
			return outsideAir <= 8 && floorSolid >= 14 && ceilingSolid >= 12 && boundarySolid >= 24 && maxLayerAir - minLayerAir <= 4;
		}

		double score() {
			int outsideAir = expandedAir - air;
			return 54.0 + air * 0.55 + floorSolid + ceilingSolid + boundarySolid * 0.9 + artificial * 6.0 - outsideAir * 1.5;
		}
	}
}
