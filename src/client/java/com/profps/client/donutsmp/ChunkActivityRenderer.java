package com.profps.client.donutsmp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.vehicle.AbstractChestBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ground-based base-finding engine.
 *
 * <p>The server hides block data below the deepslate line, but it still leaks
 * everything a player <i>does</i> down there. This module fuses every
 * ground-observable signal into a single per-chunk suspicion score:
 *
 * <ul>
 *   <li><b>Block scan</b> — any storage/redstone/crafting/light blocks that are
 *       actually visible in the chunk (works above the mask, and for sloppy
 *       bases).</li>
 *   <li><b>Block entities</b> — chests/furnaces/hoppers leak as block-entity
 *       data even when their blocks are visually masked.</li>
 *   <li><b>Entity census</b> — armor stands, (hopper/chest) minecarts, item
 *       frames, dropped-item piles, XP orbs, villager halls and dense animal
 *       farms are <i>always</i> sent to the client regardless of depth or
 *       occlusion. A cluster straight down = a base straight down.</li>
 *   <li><b>Live activity</b> — sounds (chests opening, anvils, pistons, mining)
 *       and deep block-update packets from {@link WorldSignalMonitor}. This is
 *       the strongest "someone is active below you right now" tell.</li>
 * </ul>
 *
 * This class is the shared detection ENGINE plus the Base Heat radar HUD. The
 * flat colored chunk pads (red = very sure, yellow = kind of sure, green =
 * possible) are rendered by the separate Chunk Finder module
 * ({@link ChunkFinderRenderer}), which reads this engine's results. The engine
 * scans while either module is enabled.
 */
public final class ChunkActivityRenderer implements HudRenderCallback {
	private static final int SCAN_INTERVAL_TICKS = 40;
	private static final int MAX_ACTIVE_CHUNKS = 128;
	private static final int FADE_TICKS = 18;
	/** Raised from 12 → 18 so a single natural chest / a few ambient blocks never crosses on its own. */
	private static final double SCORE_THRESHOLD = 26.0;
	/** Fat per-tick time budget right after activation, so pads appear instantly. */
	private static final long BURST_BUDGET_NANOS = 14_000_000L;
	/** Activation burst length: enough ticks to chew the whole radius before the shared budget takes over. */
	private static final int BURST_TICKS = 10;
	/** Hot chunks (fresh live signals) scanned out-of-band per tick. */
	private static final int MAX_HOT_SCANS_PER_TICK = 6;

	private static final Gson GSON = new Gson();
	private static final Type SAVED_LIST_TYPE = new TypeToken<List<SavedBase>>() {}.getType();
	private static final int MAX_SAVED_BASES = 64;
	/** Confirmed (red-tier) chunks are archived to disk and survive restarts. */
	private static final double SAVE_THRESHOLD = 110.0;

	private final ProFPSConfig config;
	private final Map<Long, ActivityChunk> chunks = new HashMap<>();
	// Score-ordered view of `chunks`, rebuilt only when the map mutates. Both
	// the world overlay and the radar HUD read it every FRAME — re-sorting and
	// re-copying per frame was measurable allocation/CPU churn.
	private final List<ActivityChunk> orderedCache = new ArrayList<>();
	private int chunksVersion;
	private int orderedVersion = -1;
	/** Chunks already announced in chat, so a found base alerts once, not every scan. */
	private final java.util.Set<Long> alertedBases = new java.util.HashSet<>();
	private final List<SavedBase> savedBases = new ArrayList<>();
	private String loadedFor = "";
	private boolean savedDirty;
	private int savedCooldown;
	private int nextScanTick;
	private boolean failedClosed;

	// ── Incremental scan state (a cycle is spread across many ticks) ────────────
	private final List<long[]> scanQueue = new ArrayList<>();
	private Map<Long, EntityTally> pendingCensus;
	private int scanCycleTick;
	/** Tick a full sweep last finished; the stall watchdog is level-triggered on this. */
	private int lastCompletedTick;
	private int burstTicks;
	private boolean wasActive;
	private ClientWorld lastWorld;
	private boolean wasChunkStreamBusy;
	private int failureCount;
	private int lastFailureTick = Integer.MIN_VALUE;

	public ChunkActivityRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		boolean on = config.enabled && (config.donutChunkActivity || config.donutChunkFinder);
		WorldSignalMonitor.get().setActive(on);
		RevealedIntel.get().setActive(on);
		if (!on) {
			if (!chunks.isEmpty()) {
				chunks.clear();
				chunksVersion++;
			}
			alertedBases.clear();
			abortScan();
			failedClosed = false;
			wasActive = false;
			return;
		}
		if (failedClosed || client.world == null || client.player == null) return;
		WorldSignalMonitor.get().prune();
		RevealedIntel.get().tick(client);

		// Fresh activation or world change: forget stale results and burst-scan
		// the whole radius right away — the module must feel instant, not
		// trickle in over the shared budget half a minute later.
		if (!wasActive || client.world != lastWorld) {
			if (client.world != lastWorld) {
				chunks.clear();
				chunksVersion++;
				alertedBases.clear();
			}
			abortScan();
			nextScanTick = 0;
			burstTicks = BURST_TICKS;
			wasActive = true;
			lastWorld = client.world;
			failureCount = 0;
		}

		// A chunk-stream wave just ended (login, teleport, walking into fresh
		// terrain): burst-rescan immediately. The activation burst alone kept
		// firing BEFORE the server had streamed the terrain — it scanned air,
		// found nothing, and everything after trickled in on the reduced
		// budget. That was the "sometimes it just doesn't work" failure.
		boolean streaming = ScanBudget.isChunkLoadBusy(client);
		if (wasChunkStreamBusy && !streaming) {
			burstTicks = Math.max(burstTicks, 5);
			nextScanTick = 0;
		}
		wasChunkStreamBusy = streaming;

		// Stall watchdog. The stream edge above is the only prompt full rescan in
		// steady state, and it can only fire when fresh chunk data arrives — so
		// standing in terrain that is already loaded means it never fires again
		// for the rest of the session, and pads age out until the overlay is
		// empty. Recovering "only after an RTP" was exactly that. This is level
		// triggered on how long it has been since a cycle actually completed, so
		// it keeps working with no chunk traffic at all.
		if (client.player.age - lastCompletedTick > SCAN_INTERVAL_TICKS * 4) {
			scanQueue.clear();
			pendingCensus = null;
			nextScanTick = 0;
			burstTicks = Math.max(burstTicks, 5);
			lastCompletedTick = client.player.age - SCAN_INTERVAL_TICKS * 2;
		}

		// Archived bases are per-server; reload when the server changes.
		String key = NetherPortalMapper.serverKey(client);
		if (!key.equals(loadedFor)) {
			loadSavedBases(key);
			loadedFor = key;
		}
		if (savedCooldown > 0) savedCooldown--;
		if (savedDirty && savedCooldown <= 0) {
			saveSavedBases();
			savedDirty = false;
			savedCooldown = 100;
		}
		// player.age resets on world change; never let a stale clock stall scans.
		if (nextScanTick > client.player.age + SCAN_INTERVAL_TICKS) nextScanTick = 0;
		try {
			// Live-signal fast path: a chunk that JUST produced a strong leaked
			// signal (chest opened, deep mining) is scanned this very tick, out
			// of band, instead of waiting up to 2s for the next sweep. This is
			// what makes "someone is active right below you" feel instant.
			scanHotChunks(client);

			if (scanQueue.isEmpty()) {
				// Idle: wait for the interval, then open a fresh scan cycle.
				if (client.player.age < nextScanTick) return;
				if (burstTicks <= 0 && !ScanBudget.tryClaim(client.player.age)) return;
				nextScanTick = client.player.age + SCAN_INTERVAL_TICKS;
				beginScan(client);
				stepScan(client); // start chewing the queue this very tick
			} else {
				// A cycle is in flight: draw from this tick's shared scan budget.
				stepScan(client);
			}
		} catch (RuntimeException exception) {
			// Consecutive failures, not lifetime ones. Three unrelated hiccups
			// spread across hours of play are not a broken module, and counting
			// them that way eventually turned the module off on its own.
			if (client.player.age - lastFailureTick > 1200) failureCount = 0;
			lastFailureTick = client.player.age;
			failureCount++;
			if (failureCount < 3) {
				// Transient hiccup: drop this cycle and retry shortly instead of
				// silently turning the module off (that silent fail-close was
				// the other "sometimes it just stops working" cause).
				ProFPS.LOGGER.warn("Chunk Activity scan failed (attempt {}); retrying.", failureCount, exception);
				abortScan();
				nextScanTick = client.player.age + 100;
				return;
			}
			ProFPS.LOGGER.error("Chunk Activity scan failed repeatedly; disabling Base Heat + Chunk Finder.", exception);
			chunks.clear();
			abortScan();
			config.donutChunkActivity = false;
			config.donutChunkFinder = false;
			// Deliberately not saved. Persisting a self-disable means one bad
			// session leaves the module switched off in the file, and the player
			// has to work out why it never came back.
			failedClosed = true;
			announceDisabled(client, "Base Heat + Chunk Finder");
		}
	}

	/** Self-disabling must be LOUD — a silent toggle-off reads as "randomly stopped working". */
	static void announceDisabled(MinecraftClient client, String module) {
		if (client == null || client.inGameHud == null) return;
		client.inGameHud.getChatHud().addMessage(Text.literal("[ProFPS] ").formatted(Formatting.DARK_GRAY)
				.append(Text.literal(module + " hit an error and was disabled — toggle it back on to retry.")
						.formatted(Formatting.RED)));
	}

	/** Widest range any enabled consumer module wants the engine to cover. */
	private int scanRange() {
		int range = 48;
		if (config.donutChunkActivity) range = Math.max(range, config.donutChunkActivityRange);
		if (config.donutChunkFinder) range = Math.max(range, config.donutChunkFinderRange);
		return MathHelper.clamp(range, 48, 1024);
	}

	private void abortScan() {
		scanQueue.clear();
		pendingCensus = null;
	}

	// ── Pad snapshots (consumed by the Chunk Finder overlay module) ────────────

	/**
	 * One renderable chunk pad: the flat chunk-footprint box at the deepest
	 * evidence Y, its tier color, fade, pulse seed and the short evidence
	 * summary ("3snd y-12 blocks"). Chunk coords ride along so the overlay can
	 * de-duplicate tracers across touching flagged chunks.
	 */
	public record Pad(int chunkX, int chunkZ, Box box, int color, float fade, float seed, String why) {}

	public boolean engineFailed() {
		return failedClosed;
	}

	/** Live suspicion pads near (x,z), strongest first, capped and range-filtered. */
	public List<Pad> livePads(double x, double z, double range, float renderTick) {
		List<Pad> pads = new ArrayList<>();
		for (ActivityChunk chunk : ordered()) {
			if (pads.size() >= MAX_ACTIVE_CHUNKS) break;
			if (chunk.horizontalDistanceSq(x, z) > range * range) continue;
			float fade = chunk.fade(renderTick);
			if (fade <= 0.01F) continue;
			pads.add(new Pad(chunk.chunkX, chunk.chunkZ, chunk.footprint(), chunk.tierColor(), fade, chunk.seed(), chunk.why()));
		}
		return pads;
	}

	/** Archived (confirmed, persisted) base pads, skipping chunks already drawn live. */
	public List<Pad> archivedPads(double x, double z, double range, float renderTick) {
		List<Pad> pads = new ArrayList<>();
		for (SavedBase base : savedBases) {
			ActivityChunk live = chunks.get(ChunkPos.toLong(base.x, base.z));
			if (live != null && live.fade(renderTick) > 0.2F) continue;
			double dx = ((base.x << 4) + 8.0) - x;
			double dz = ((base.z << 4) + 8.0) - z;
			if (dx * dx + dz * dz > range * range) continue;
			double bx = base.x << 4;
			double bz = base.z << 4;
			pads.add(new Pad(base.x, base.z, new Box(bx, 0.0, bz, bx + 16.0, 1.0, bz + 16.0),
					0xFFFFA22E, 1.0F, 0.0F, base.why == null ? "saved" : "saved · " + base.why));
		}
		return pads;
	}

	/** One archived base, exposed for the {@code /nova bases} chat listing. */
	public record BaseSummary(int blockX, int blockZ, double score, String why) {}

	/** Archived bases for the current server, strongest first. */
	public List<BaseSummary> baseSummaries() {
		List<BaseSummary> out = new ArrayList<>(savedBases.size());
		for (SavedBase base : savedBases) {
			out.add(new BaseSummary((base.x << 4) + 8, (base.z << 4) + 8, base.score,
					base.why == null ? "activity" : base.why));
		}
		out.sort(Comparator.comparingDouble(BaseSummary::score).reversed());
		return out;
	}

	// ── Radar HUD ────────────────────────────────────────────────────────────

	private static final int RADAR_RADIUS = 56;
	private static final int RADAR_MARGIN = 8;
	/** Top offset so the radar sits below the FPS counter (drawn at y=5..14). */
	private static final int RADAR_TOP = 20;

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		if (!config.enabled || !config.donutChunkActivity || failedClosed) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null || mc.options.hudHidden) return;

		double range = MathHelper.clamp(config.donutChunkActivityRange, 48, 1024);
		int cx = RADAR_MARGIN + RADAR_RADIUS;
		int cy = RADAR_TOP + 12 + RADAR_RADIUS;
		float renderTick = mc.player.age + tickCounter.getTickProgress(false);

		// Heading: radar is rotated so the top of the scope = where the player looks.
		float yawRad = (float) Math.toRadians(mc.player.getYaw());
		float fx = -MathHelper.sin(yawRad), fz = MathHelper.cos(yawRad); // forward (look) axis
		float rx = -MathHelper.cos(yawRad), rz = -MathHelper.sin(yawRad); // right axis
		float scale = (float) (RADAR_RADIUS / range);

		// Scope backdrop + range rings. (Sprite-based discs: 2 quads instead of
		// ~240 per-row fills every frame — this radar was a real FPS cost.)
		com.profps.client.ui.nova.NovaRender.fillCircle(context, cx, cy, RADAR_RADIUS + 2, 0xB0070B10);
		com.profps.client.ui.nova.NovaRender.fillCircle(context, cx, cy, RADAR_RADIUS, 0x9012202C);
		drawRing(context, cx, cy, RADAR_RADIUS, 0xFF2FE38A);
		drawRing(context, cx, cy, (RADAR_RADIUS * 2) / 3, 0x402FE38A);
		drawRing(context, cx, cy, RADAR_RADIUS / 3, 0x402FE38A);
		context.fill(cx - RADAR_RADIUS, cy, cx + RADAR_RADIUS + 1, cy + 1, 0x202FE38A);
		context.fill(cx, cy - RADAR_RADIUS, cx + 1, cy + RADAR_RADIUS + 1, 0x202FE38A);

		// Sweep line (rotating, with a short fading trail).
		float sweep = (renderTick * 0.06F) % ((float) Math.PI * 2.0F);
		for (int t = 0; t < 14; t++) {
			float a = sweep - t * 0.05F;
			int alpha = (int) (0x66 * (1.0F - t / 14.0F));
			plotRay(context, cx, cy, a, RADAR_RADIUS, (alpha << 24) | 0x2FE38A);
		}

		// Contacts — heat-colored blips, brightest first (cached order; no per-frame sort).
		ActivityChunk nearest = null;
		double nearestSq = Double.MAX_VALUE;
		for (ActivityChunk chunk : ordered()) {
			double distSq = chunk.horizontalDistanceSq(mc.player);
			if (distSq > range * range) continue;
			if (distSq < nearestSq) { nearestSq = distSq; nearest = chunk; }

			double dx = ((chunk.chunkX << 4) + 8.0) - mc.player.getX();
			double dz = ((chunk.chunkZ << 4) + 8.0) - mc.player.getZ();
			float u = (float) (dx * rx + dz * rz); // screen right
			float v = (float) (dx * fx + dz * fz); // screen forward (up)
			int bx = cx + Math.round(u * scale);
			int by = cy - Math.round(v * scale);

			int color = chunk.tierColor();
			float pulse = 0.6F + 0.4F * MathHelper.sin(renderTick * 0.18F + chunk.seed());
			int size = chunk.score() >= 90 ? 2 : 1;
			context.fill(bx - size - 1, by - size - 1, bx + size + 2, by + size + 2, 0xC0000000);
			context.fill(bx - size, by - size, bx + size + 1, by + size + 1,
					(((int) (0x60 + 0x9F * pulse)) << 24) | (color & 0xFFFFFF));
		}

		// Heading marker (white pip at the top edge = forward) + player dot at center.
		context.fill(cx - 1, cy - RADAR_RADIUS - 1, cx + 2, cy - RADAR_RADIUS + 2, 0xFFFFFFFF);
		context.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

		// North label, rotated with the scope so the player can orient.
		float nu = -rz, nv = -fz; // world north (-Z) projected into screen axes
		int nx = cx + Math.round(nu * (RADAR_RADIUS - 8));
		int ny = cy - Math.round(nv * (RADAR_RADIUS - 8));
		context.drawText(mc.textRenderer, Text.literal("N").formatted(Formatting.RED), nx - 2, ny - 4, 0xFFFF5555, false);

		// Title + readout.
		context.drawText(mc.textRenderer, Text.literal("BASE HEAT").formatted(Formatting.GOLD, Formatting.BOLD),
				RADAR_MARGIN, RADAR_TOP, 0xFFFFD44A, false);
		String footer = chunks.isEmpty() ? "scanning…" : nearest == null ? "no contacts" :
				MathHelper.floor(Math.sqrt(nearestSq)) + "m  " + nearest.why();
		context.drawText(mc.textRenderer, Text.literal(footer).formatted(Formatting.GRAY),
				RADAR_MARGIN, cy + RADAR_RADIUS + 4, 0xFFBBBBBB, false);
	}

	/** Circle outline by plotting points around the rim. */
	private static void drawRing(DrawContext ctx, int cx, int cy, int radius, int color) {
		int steps = Math.max(48, radius * 4);
		for (int i = 0; i < steps; i++) {
			double a = i * (Math.PI * 2.0) / steps;
			int px = cx + (int) Math.round(Math.cos(a) * radius);
			int py = cy + (int) Math.round(Math.sin(a) * radius);
			ctx.fill(px, py, px + 1, py + 1, color);
		}
	}

	/** Dotted ray from center outward at the given angle (0 = +screenX, clockwise). */
	private static void plotRay(DrawContext ctx, int cx, int cy, double angle, int radius, int color) {
		float ca = (float) Math.cos(angle), sa = (float) Math.sin(angle);
		for (int r = 3; r <= radius; r += 3) {
			int px = cx + Math.round(ca * r);
			int py = cy + Math.round(sa * r);
			ctx.fill(px, py, px + 1, py + 1, color);
		}
	}

	/** Open a scan cycle: take one entity census and enqueue every in-range chunk for incremental scanning. */
	private void beginScan(MinecraftClient client) {
		ClientWorld world = client.world;
		// In freecam, scan around the flying camera so the area being scouted
		// keeps resolving instead of only the player's anchor point.
		Vec3d center = FreecamController.isActive()
				? FreecamController.cameraPosition()
				: client.player.getEntityPos();
		int centerChunkX = MathHelper.floor(center.x) >> 4;
		int centerChunkZ = MathHelper.floor(center.z) >> 4;
		int range = scanRange();
		int viewDistance = client.options == null ? 12 : client.options.getViewDistance().getValue();
		int radius = MathHelper.clamp(MathHelper.ceil(range / 16.0F), 2, Math.min(12, viewDistance + 1));

		pendingCensus = censusEntities(world, centerChunkX, centerChunkZ, radius);
		scanQueue.clear();
		scanCycleTick = client.player.age;

		for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
			for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
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
	 * Process a batch of queued chunks. Detections commit IMMEDIATELY per chunk
	 * (the queue resolves nearest-first), so pads appear as the scan progresses
	 * instead of all at once when the whole cycle finally drains.
	 */
	private void stepScan(MinecraftClient client) {
		ClientWorld world = client.world;
		int playerY = client.player.getBlockY();
		int tick = scanCycleTick;

		long pool;
		boolean burst = burstTicks > 0;
		if (burst) {
			burstTicks--;
			pool = BURST_BUDGET_NANOS;
		} else {
			pool = ScanBudget.takeBudget(client.player.age, ScanBudget.Lane.BASE_FINDER, config);
			if (pool <= 0L) return;
		}
		long start = System.nanoTime();
		while (!scanQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) break;
			long[] coord = scanQueue.remove(scanQueue.size() - 1);
			scanChunk(world, (int) coord[0], (int) coord[1], playerY, tick);
		}
		if (!burst) ScanBudget.reportUsed(client.player.age, ScanBudget.Lane.BASE_FINDER, System.nanoTime() - start);

		if (!scanQueue.isEmpty()) return; // more batches to go

		// Cycle complete — expire stale chunks and trim the weakest extras.
		failureCount = 0;
		if (chunks.values().removeIf(chunk -> tick - chunk.lastSeenTick > chunk.ttlTicks())) {
			chunksVersion++;
		}
		if (chunks.size() > MAX_ACTIVE_CHUNKS) {
			// Trim the STALEST chunks (least-recently-seen = ones we've left
			// behind), not the lowest-score ones — trimming a low-but-nearby
			// chunk and re-detecting it next pass was itself a flicker source.
			List<ActivityChunk> ordered = new ArrayList<>(chunks.values());
			ordered.sort(Comparator.comparingInt((ActivityChunk chunk) -> chunk.lastSeenTick).reversed());
			for (int i = MAX_ACTIVE_CHUNKS; i < ordered.size(); i++) {
				chunks.remove(ordered.get(i).key());
			}
			chunksVersion++;
		}
		pendingCensus = null;
		// A completed sweep is the only thing that satisfies the stall watchdog,
		// and it also clears the consecutive-failure count.
		failureCount = 0;
		if (client != null && client.player != null) lastCompletedTick = client.player.age;
	}

	/**
	 * Score one chunk and commit it if it crosses the threshold AND the
	 * hard-evidence gate. Shared by the sweeping scan (which carries an entity
	 * census) and the live-signal hot path (which usually doesn't — its evidence
	 * IS the signal).
	 */
	private void scanChunk(ClientWorld world, int chunkX, int chunkZ, int playerY, int tick) {
		if (!world.isChunkLoaded(chunkX, chunkZ)) return;
		WorldChunk chunk = world.getChunk(chunkX, chunkZ);
		if (chunk == null || chunk.isEmpty()) return;

		// EXPERIMENTAL: pull exact container/spawner positions straight out of the
		// loaded chunk's block-entity list (anti-xray masks the block palette, not
		// the block-entity list), pinning real deep loot without anyone touching it.
		if (config.donutChunkExperimental) {
			registerLeakedBlockEntities(chunk);
		}

		long key = ChunkPos.toLong(chunkX, chunkZ);
		EntityTally tally = pendingCensus == null ? null : pendingCensus.get(key);
		WorldSignalMonitor.Snapshot signal = WorldSignalMonitor.get().snapshot(chunkX, chunkZ);

		BlockScan blocks = blockScore(chunk, world, playerY);
		double signalScore = signal == null ? 0.0 : signal.total();
		LightLeak leak = lightLeakScan(chunk, world);
		double intelScore = RevealedIntel.get().chunkScore(chunkX, chunkZ);
		// Loose items/XP only count alongside real world evidence — event
		// loot drops and streamer item showers are piles of ItemEntities
		// with NO matching blocks/signals, and they kept flagging chunks.
		boolean corroborated = blocks.score() >= 6.0 || signalScore > 0.0 || leak.score() > 0.0 || intelScore > 0.0;
		double entityScore = tally == null ? 0.0 : tally.score(corroborated);
		double total = blocks.score() + entityScore + signalScore + leak.score() + intelScore;

		// ── Already-flagged chunk: keep it SOLIDLY marked while we're near it. ──
		// Re-running the detection gates every scan (and dropping the chunk the
		// moment a bursty signal decays) is what made the overlay flicker in/out
		// and flip colour. Once a base is found we refresh it, let its score and
		// tier only CLIMB, and never re-gate it — so it stays put until we leave
		// (then it ages out via the TTL). Detection gates apply on FIRST flag only.
		// Strong leaked CONTAINER intel (2+ chests/barrels/etc. pinned through the
		// deepslate mask via block-entity/event packets — exactly the "storage
		// blocks through walls" you see) = a real base, the moment its chunk
		// loads. This is the FAST chat alert; it no longer waits on the slow
		// block scan to unmask. Container-specific, so signs/decor never trip it.
		boolean strongBase = intelScore >= 24.0;

		ActivityChunk existing = chunks.get(key);
		if (existing != null) {
			existing.lastSeenTick = tick;
			if (total > existing.score) {
				existing.score = total;
				existing.why = buildWhy(blocks.score(), tally, signal, leak, intelScore);
				int padY = evidenceY(signal, leak);
				existing.padY = padY;
			}
			existing.updateTier();
			if (existing.score() >= SAVE_THRESHOLD) upsertSavedBase(existing);
			if (strongBase) alertBaseFound(chunkX, chunkZ);
			chunksVersion++;
			return;
		}

		if (total < SCORE_THRESHOLD) return;

		// False-flag gate (first flag only). A pad must rest on at least one HARD,
		// player-made tell: leaked container block-entities, real storage/redstone/
		// crafting infrastructure, live world signals, mask-piercing intel, a lit
		// hidden cavity, or an unmistakable player-built entity fingerprint (armor
		// stands, item frames, paintings, chest boats, tamed pets, packed
		// kill-chambers, fixed collection piles). Natural worldgen — ore, lava
		// light, villages, mineshafts (incl. their chest minecarts), dungeons,
		// ruined portals — trips none of these on its own.
		// Hard evidence must be a REAL base tell, not "any block entity" — a lone sign,
		// banner, bed or flower pot is decor and was flagging half the map. Require actual
		// storage/redstone/crafting infrastructure, a live world signal, mask-piercing
		// container intel, a lit hidden cavity, or an unmistakable player-built entity rig.
		boolean hardEvidence = blocks.functional() > 0
				|| signalScore > 0.0
				|| intelScore > 0.0
				|| leak.score() >= 18.0
				|| (tally != null && tally.builtFingerprint());
		if (!hardEvidence) return;

		// A blown-up PvP fight underground (crystal/anchor craters with loose
		// loot) is not a base. If grief signatures are on-site — live crystals/TNT
		// or a scatter of respawn anchors / crying obsidian — and there's no
		// intact storage to justify the chunk, drop it. A real base still has its
		// containers, so it survives this gate.
		boolean intactStorage = blocks.blockEntities() > 0 || blocks.functional() > 0 || intelScore > 0.0;
		boolean combat = (tally != null && tally.combatSignature()) || blocks.combatResidue() >= 2;
		if (combat && !intactStorage) return;

		commitChunk(new ActivityChunk(chunkX, chunkZ, total, tick,
				buildWhy(blocks.score(), tally, signal, leak, intelScore), evidenceY(signal, leak)), tick);
		if (strongBase) alertBaseFound(chunkX, chunkZ);
	}

	/** Fast one-time chat alert when a chunk's leaked container evidence confirms a base. */
	private void alertBaseFound(int chunkX, int chunkZ) {
		long key = ChunkPos.toLong(chunkX, chunkZ);
		if (alertedBases.contains(key)) return;
		// Same base often spans touching chunks; if a neighbour already alerted, stay quiet.
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dz == 0) continue;
				if (alertedBases.contains(ChunkPos.toLong(chunkX + dx, chunkZ + dz))) {
					alertedBases.add(key);
					return;
				}
			}
		}
		alertedBases.add(key);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		int bx = (chunkX << 4) + 8;
		int bz = (chunkZ << 4) + 8;
		int dist = (int) Math.sqrt(client.player.squaredDistanceTo(bx, client.player.getY(), bz));
		client.player.sendMessage(Text.literal("[").formatted(Formatting.WHITE)
				.append(Text.literal("NovaClient").formatted(Formatting.GOLD))
				.append(Text.literal("] ").formatted(Formatting.WHITE))
				.append(Text.literal("Base Detected At ").formatted(Formatting.GOLD, Formatting.BOLD))
				.append(Text.literal(bx + ", " + bz).formatted(Formatting.GREEN))
				.append(Text.literal("  " + dist + "m").formatted(Formatting.GRAY)), false);
	}

	/** Deepest evidence Y for the pad anchor (defaults to 0). */
	private static int evidenceY(WorldSignalMonitor.Snapshot signal, LightLeak leak) {
		int padY = 0;
		if (signal != null && signal.deepestY() < padY) padY = signal.deepestY();
		if (leak.score() > 0.0 && leak.minY() < padY) padY = leak.minY();
		return padY;
	}

	/** A hot chunk is not re-scanned out-of-band more often than this. */
	private static final int HOT_RESCAN_COOLDOWN_TICKS = 40;
	private final Map<Long, Integer> hotScanTicks = new HashMap<>();

	/** Scan chunks whose live signal just spiked, without waiting for the next sweep. */
	private void scanHotChunks(MinecraftClient client) {
		List<Long> hot = WorldSignalMonitor.get().drainHotChunks();
		if (hot.isEmpty()) return;
		ClientWorld world = client.world;
		int playerY = client.player.getBlockY();
		int range = scanRange();
		double centerX = client.player.getX();
		double centerZ = client.player.getZ();
		int tick = client.player.age;
		// player.age resets on world change; a stale stamp must never block scans.
		hotScanTicks.values().removeIf(at -> at > tick || tick - at > HOT_RESCAN_COOLDOWN_TICKS);
		int scanned = 0;
		for (Long key : hot) {
			if (scanned >= MAX_HOT_SCANS_PER_TICK) break;
			// A chunk under sustained noise stays hot continuously; without this
			// cooldown it would burn a full block scan every single tick.
			if (hotScanTicks.containsKey(key)) continue;
			int chunkX = ChunkPos.getPackedX(key);
			int chunkZ = ChunkPos.getPackedZ(key);
			double dx = ((chunkX << 4) + 8.0) - centerX;
			double dz = ((chunkZ << 4) + 8.0) - centerZ;
			if (dx * dx + dz * dz > (double) range * range) continue;
			hotScanTicks.put(key, tick);
			scanChunk(world, chunkX, chunkZ, playerY, tick);
			scanned++;
		}
	}

	/** Experimental: register every container/spawner in the masked deep zone of a loaded chunk as exact intel. */
	private void registerLeakedBlockEntities(WorldChunk chunk) {
		for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
			BlockPos pos = entry.getKey();
			if (pos.getY() > 16) continue; // only the masked deep zone is intel worth pinning
			RevealedIntel.get().recordLeakedBlockEntity(pos, entry.getValue().getType());
		}
	}

	/** Merge one freshly scanned chunk into the live map right away. */
	private void commitChunk(ActivityChunk raw, int tick) {
		ActivityChunk existing = chunks.get(raw.key());
		if (existing == null) {
			chunks.put(raw.key(), raw);
			existing = raw;
		} else {
			existing.score = Math.max(existing.score * 0.86, raw.score);
			existing.why = raw.why;
			existing.padY = raw.padY;
			existing.lastSeenTick = tick;
			existing.updateTier();
		}
		chunksVersion++;
		if (existing.score() >= SAVE_THRESHOLD) {
			upsertSavedBase(existing);
		}
	}

	/** Score-ordered chunk view, rebuilt only when the underlying map has changed. */
	private List<ActivityChunk> ordered() {
		if (orderedVersion != chunksVersion) {
			orderedCache.clear();
			orderedCache.addAll(chunks.values());
			orderedCache.sort(Comparator.comparingDouble(ActivityChunk::score).reversed());
			orderedVersion = chunksVersion;
		}
		return orderedCache;
	}

	// ── Light-leak scanner ─────────────────────────────────────────────────────

	private record LightLeak(double score, int minY) {}

	/**
	 * Anti-xray rewrites the BLOCK palette, but light data is computed from the
	 * real blocks and ships unmasked. A lit cavity below the deepslate line with
	 * no visible light source is a hidden, player-lit build — and the bigger the
	 * build, the bigger its leaked light footprint. Sampled on a coarse grid so
	 * a chunk costs ~250 light reads.
	 */
	private LightLeak lightLeakScan(WorldChunk chunk, ClientWorld world) {
		if (!world.getRegistryKey().equals(World.OVERWORLD)) return new LightLeak(0.0, 0);
		// EXPERIMENTAL boosts sensitivity: a finer 2-block grid, a lower light
		// floor (catches dim/lantern-lit hidey-holes), and a higher leak cap, so
		// smaller hidden builds register that the coarse default scan misses.
		boolean experimental = config.donutChunkExperimental;
		int step = experimental ? 2 : 4;
		int minLight = experimental ? 7 : 10;
		int leakCap = experimental ? 40 : 24;
		int bottom = Math.max(world.getBottomY() + 2, -58);
		int top = 8; // the masked zone
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		BlockPos.Mutable pos = new BlockPos.Mutable();
		double score = 0.0;
		int minY = 0;
		int leaks = 0;

		for (int y = bottom; y <= top && leaks < leakCap; y += step) {
			for (int z = startZ + 1; z < startZ + 16 && leaks < leakCap; z += step) {
				for (int x = startX + 1; x < startX + 16; x += step) {
					pos.set(x, y, z);
					if (world.getLightLevel(LightType.BLOCK, pos) < minLight) continue;
					if (hasVisibleEmitter(world, pos)) continue;
					leaks++;
					if (y < minY) minY = y;
					// The deepest band is full of natural lava light; weight it low.
					score += y < -42 ? 2.0 : 9.0;
					if (leaks >= leakCap) break;
				}
			}
		}
		return new LightLeak(Math.min(score, 130.0), minY);
	}

	/** True when any VISIBLE nearby block explains the light (torch, lava, lichen...). */
	private boolean hasVisibleEmitter(ClientWorld world, BlockPos center) {
		BlockPos.Mutable probe = new BlockPos.Mutable();
		for (int dy = -3; dy <= 3; dy++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dx = -3; dx <= 3; dx++) {
					probe.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (world.getBlockState(probe).getLuminance() >= 5) return true;
				}
			}
		}
		return false;
	}

	// ── Archived bases (persist across sessions) ───────────────────────────────

	private void upsertSavedBase(ActivityChunk chunk) {
		for (SavedBase base : savedBases) {
			if (base.x == chunk.chunkX && base.z == chunk.chunkZ) {
				if (chunk.score() > base.score) {
					base.score = chunk.score();
					base.why = chunk.why();
					base.padY = chunk.padY;
					savedDirty = true;
				}
				base.t = System.currentTimeMillis();
				return;
			}
		}
		SavedBase base = new SavedBase();
		base.x = chunk.chunkX;
		base.z = chunk.chunkZ;
		base.score = chunk.score();
		base.why = chunk.why();
		base.padY = chunk.padY;
		base.t = System.currentTimeMillis();
		savedBases.add(base);
		savedDirty = true;
		if (savedBases.size() > MAX_SAVED_BASES) {
			savedBases.sort(Comparator.comparingDouble((SavedBase entry) -> entry.score).reversed());
			savedBases.subList(MAX_SAVED_BASES, savedBases.size()).clear();
		}
	}

	private Path savedBasesFile(String key) {
		return FabricLoader.getInstance().getConfigDir().resolve("profps_bases").resolve(key + ".json");
	}

	private void loadSavedBases(String key) {
		savedBases.clear();
		Path path = savedBasesFile(key);
		if (!Files.exists(path)) return;
		try {
			List<SavedBase> loaded = GSON.fromJson(Files.newBufferedReader(path), SAVED_LIST_TYPE);
			if (loaded != null) savedBases.addAll(loaded);
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to load archived bases for {}.", key, exception);
		}
	}

	private void saveSavedBases() {
		Path path = savedBasesFile(loadedFor);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(savedBases, SAVED_LIST_TYPE));
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to save archived bases.", exception);
		}
	}

	// ── Entity census ──────────────────────────────────────────────────────────

	private Map<Long, EntityTally> censusEntities(ClientWorld world, int cx, int cz, int radius) {
		Map<Long, EntityTally> map = new HashMap<>();
		for (Entity entity : world.getEntities()) {
			int ex = entity.getBlockX() >> 4;
			int ez = entity.getBlockZ() >> 4;
			if (Math.abs(ex - cx) > radius || Math.abs(ez - cz) > radius) continue;
			EntityTally tally = map.computeIfAbsent(ChunkPos.toLong(ex, ez), k -> new EntityTally());
			tally.accept(entity);
		}
		return map;
	}

	// ── Block scan ─────────────────────────────────────────────────────────────

	/** Block-scan result: the score plus the hard-evidence + combat-residue counts the gates read. */
	private record BlockScan(double score, int blockEntities, int functional, int combatResidue) {}

	private BlockScan blockScore(WorldChunk chunk, ClientWorld world, int playerY) {
		int minY = world.getBottomY() + 2;
		int maxY = Math.min(world.getBottomY() + world.getHeight() - 2, playerY + 80);
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		int blockEntities = chunk.getBlockEntities().size();
		// Generic block entities (signs, banners, skulls, beds, pots, lecterns, bells…) are
		// mostly DECOR and were the main false-flag source in built areas — weight them
		// lightly and cap them. Real CONTAINERS get their proper weight via storage below.
		double score = Math.min(blockEntities * 1.5, 12.0);
		int crafted = 0, storage = 0, redstone = 0, light = 0, structure = 0, placed = 0, combat = 0;
		boolean nether = world.getRegistryKey() == World.NETHER;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		var sections = chunk.getSectionArray();

		for (int y = minY; y <= maxY; y += 2) {
			// Empty sections are pure air — skip 16 Y levels at a time.
			int sectionIndex = chunk.getSectionIndex(y);
			if (sectionIndex < 0 || sectionIndex >= sections.length
					|| sections[sectionIndex] == null || sections[sectionIndex].isEmpty()) {
				y = ((Math.floorDiv(y, 16) + 1) << 4) - 2;
				continue;
			}
			for (int z = startZ; z < startZ + 16; z += 2) {
				for (int x = startX; x < startX + 16; x += 2) {
					pos.set(x, y, z);
					BlockState state = chunk.getBlockState(pos);
					if (state.isAir()) continue;
					// Respawn anchors and crying obsidian are anchor-bomb / crystal
					// crater residue, NOT base infrastructure. They score ZERO and
					// feed the combat gate instead, so an anchor-war crater with
					// "respawn anchors everywhere and nothing else" never flags.
					if (isCombatResidue(state)) combat++;
					else if (isStorage(state)) storage++;
					else if (isRedstone(state)) redstone++;
					else if (isCrafted(state)) crafted++;
					else if (isLight(state)) light++;
					else if (isPlacedAnomaly(state) || PlayerPlacedBlocks.isBuildDecor(state, nether)) placed++;
					else if (isStructureNoise(state)) structure++;
				}
			}
		}

		// Cap the "ambient build" signals (loose placed blocks, torches/lamps): a heavily
		// BUILT but storage-less area shouldn't inflate its way over the threshold — only a
		// real storage/redstone/crafting concentration should carry a chunk.
		score += storage * 9.0 + redstone * 7.0 + crafted * 5.0
				+ Math.min(light * 3.0, 15.0) + Math.min(placed * 4.0, 20.0);
		score -= Math.min(30.0, structure * 4.0);
		return new BlockScan(Math.max(0.0, score), blockEntities, storage + redstone + crafted, combat);
	}

	/** Crystal / anchor-bomb crater residue — a PvP blow-up, never base storage. */
	private boolean isCombatResidue(BlockState state) {
		return state.isOf(Blocks.RESPAWN_ANCHOR) || state.isOf(Blocks.CRYING_OBSIDIAN);
	}

	private String buildWhy(double blockScore, EntityTally tally, WorldSignalMonitor.Snapshot signal,
			LightLeak leak, double intelScore) {
		List<String> parts = new ArrayList<>();
		if (leak.score() >= 18.0) parts.add("light✦y" + leak.minY());
		if (intelScore >= 10.0) parts.add("intel");
		if (signal != null) {
			if (signal.hasClock()) parts.add("clock");
			if (signal.blockUpdates() > 0) parts.add(signal.blockUpdates() + "Δblk");
			if (signal.soundEvents() > 0) parts.add(signal.soundEvents() + "snd");
			if (signal.deepestY() < 16 && signal.deepestY() != Integer.MAX_VALUE) parts.add("y" + signal.deepestY());
		}
		if (tally != null) {
			if (tally.farmMobs >= 5) parts.add(tally.farmMobs + (tally.killChamber ? "xfarm" : "mob"));
			if (tally.golems > 0) parts.add(tally.golems + "golem");
			if (tally.minecarts > 0) parts.add(tally.minecarts + "cart");
			if (tally.chestBoats > 0) parts.add(tally.chestBoats + "boat");
			if (tally.armorStands > 0) parts.add(tally.armorStands + "stand");
			if (tally.itemFrames > 0) parts.add(tally.itemFrames + "frame");
			if (tally.paintings > 0) parts.add(tally.paintings + "art");
			if (tally.pets > 0) parts.add(tally.pets + "pet");
			if (tally.villagers > 0) parts.add(tally.villagers + "vil");
			if (tally.items > 4) parts.add(tally.items + (tally.collectionPoint ? "drops!" : "item"));
			if (tally.xpOrbs > 6) parts.add(tally.xpOrbs + "xp");
			if (tally.animals > 8) parts.add(tally.animals + "mob");
		}
		if (blockScore >= 9.0) parts.add("blocks");
		if (parts.isEmpty()) return "activity";
		return String.join(" ", parts);
	}

	// ── Confidence tiers ───────────────────────────────────────────────────────
	// Tier colours (red = very sure, yellow = kind of sure, green = possible) are
	// chosen per-chunk with hysteresis in ActivityChunk.tierColor().

	private boolean isStorage(BlockState state) {
		return state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)
				|| state.isOf(Blocks.ENDER_CHEST)
				|| state.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock;
	}

	private boolean isRedstone(BlockState state) {
		return state.isOf(Blocks.REDSTONE_BLOCK) || state.isOf(Blocks.REPEATER) || state.isOf(Blocks.COMPARATOR)
				|| state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON) || state.isOf(Blocks.OBSERVER)
				|| state.isOf(Blocks.HOPPER) || state.isOf(Blocks.DISPENSER) || state.isOf(Blocks.DROPPER);
	}

	private boolean isCrafted(BlockState state) {
		return state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE)
				|| state.isOf(Blocks.SMOKER) || state.isOf(Blocks.ENCHANTING_TABLE) || state.isOf(Blocks.ANVIL)
				|| state.isOf(Blocks.BREWING_STAND) || state.isOf(Blocks.BEACON)
				|| state.isOf(Blocks.LODESTONE) || state.isOf(Blocks.JUKEBOX);
	}

	private boolean isLight(BlockState state) {
		return state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH) || state.isOf(Blocks.SOUL_TORCH)
				|| state.isOf(Blocks.SOUL_WALL_TORCH) || state.isOf(Blocks.LANTERN) || state.isOf(Blocks.SOUL_LANTERN);
	}

	/** Blocks that never appear in natural generation — a sure sign of a player. */
	private boolean isPlacedAnomaly(BlockState state) {
		return state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.SCAFFOLDING)
				|| state.isOf(Blocks.LADDER) || state.isOf(Blocks.GLASS) || state.isOf(Blocks.GLASS_PANE)
				|| state.isOf(Blocks.OAK_TRAPDOOR) || state.isOf(Blocks.IRON_TRAPDOOR)
				|| state.isOf(Blocks.IRON_DOOR) || state.isOf(Blocks.OAK_DOOR)
				|| state.isOf(Blocks.IRON_BARS) || state.isOf(Blocks.WHITE_BED)
				|| state.isOf(Blocks.NETHERITE_BLOCK) || state.isOf(Blocks.DIAMOND_BLOCK)
				|| state.isOf(Blocks.IRON_BLOCK) || state.isOf(Blocks.GOLD_BLOCK)
				|| state.isOf(Blocks.SEA_LANTERN) || state.isOf(Blocks.GLOWSTONE)
				|| state.isOf(Blocks.SMOOTH_STONE) || state.isOf(Blocks.STONE_BRICKS)
				|| state.isOf(Blocks.BOOKSHELF);
	}

	private boolean isStructureNoise(BlockState state) {
		return state.isOf(Blocks.RAIL) || state.isOf(Blocks.POWERED_RAIL) || state.isOf(Blocks.DETECTOR_RAIL)
				|| state.isOf(Blocks.ACTIVATOR_RAIL) || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.MOSSY_COBBLESTONE)
				|| state.isOf(Blocks.MOSSY_STONE_BRICKS) || state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.TRIAL_SPAWNER);
	}

	// ── Per-chunk entity tally ──────────────────────────────────────────────────

	private static final class EntityTally {
		int items, armorStands, minecarts, xpOrbs, itemFrames, villagers, animals;
		int paintings, golems, pets, chestBoats;
		int endCrystals, tnt;
		int farmMobs;
		boolean killChamber;
		boolean collectionPoint;
		private final Map<EntityType<?>, MobCluster> monsterClusters = new HashMap<>();
		private final MobCluster itemCluster = new MobCluster();

		void accept(Entity entity) {
			if (entity instanceof ItemEntity) {
				items++;
				itemCluster.add(entity);
			} else if (entity instanceof EndCrystalEntity) endCrystals++;
			else if (entity instanceof TntEntity) tnt++;
			else if (entity instanceof ArmorStandEntity) armorStands++;
			else if (entity instanceof AbstractMinecartEntity) minecarts++;
			else if (entity instanceof ExperienceOrbEntity) xpOrbs++;
			else if (entity instanceof ItemFrameEntity) itemFrames++;
			else if (entity instanceof PaintingEntity) paintings++;
			else if (entity instanceof AbstractChestBoatEntity) chestBoats++;
			else if (entity instanceof VillagerEntity) villagers++;
			else if (entity instanceof IronGolemEntity) golems++;
			else if (entity instanceof TameableEntity tameable && tameable.isTamed()) pets++;
			else if (entity instanceof Monster) {
				// Farms are made of entities by design — and entities are never
				// masked at any depth. Same-type concentration is the fingerprint.
				monsterClusters.computeIfAbsent(entity.getType(), ignored -> new MobCluster()).add(entity);
			} else if (entity instanceof AnimalEntity) animals++;
		}

		/**
		 * @param corroborated true when the chunk shows independent evidence
		 *        (blocks, sounds, light leaks). Without it, loose items and XP
		 *        orbs score ZERO — event loot drops and streamer item showers
		 *        are exactly "many ItemEntities, nothing else".
		 */
		double score(boolean corroborated) {
			double s = 0.0;
			s += armorStands * 9.0;
			s += minecarts * 12.0;
			s += itemFrames * 8.0;
			s += paintings * 7.0;
			s += Math.min(golems, 6) * 8.0;       // iron farm / guarded base
			s += Math.min(pets, 5) * 6.0;          // tamed pets stand at home
			s += chestBoats * 12.0;
			s += Math.min(villagers, 10) * 6.0;
			if (corroborated) {
				s += Math.min(xpOrbs, 40) * 0.8;
				s += Math.min(items, 60) * 1.2;
			}
			if (animals > 8) s += Math.min(animals - 8, 40) * 1.6;  // concentrated farm

			for (MobCluster cluster : monsterClusters.values()) {
				if (cluster.count < 5) continue;
				farmMobs = Math.max(farmMobs, cluster.count);
				s += Math.min(cluster.count, 30) * 3.5;
				boolean tight = cluster.spreadX() <= 4.5 && cluster.spreadZ() <= 4.5;
				if (tight && cluster.count >= 6) {
					killChamber = true;
					s += 30.0; // many same-type hostiles packed into a tiny space
				}
				if (tight && cluster.spreadY() >= 10.0) {
					s += 15.0; // vertical drop shaft above the chamber
				}
			}
			if (corroborated && items >= 8 && itemCluster.spreadX() <= 4.5 && itemCluster.spreadZ() <= 4.5) {
				collectionPoint = true;
				s += 24.0; // farm output piling at a fixed collection point
			}
			return s;
		}

		/**
		 * An unmistakable player-built entity tell — never produced by unmodified
		 * worldgen. Deliberately excludes villagers, iron golems and chest
		 * minecarts: those ride along with natural villages and mineshafts, so
		 * they only count toward the score, not toward the hard-evidence gate.
		 * Must be read AFTER {@link #score} so the cluster-derived flags are set.
		 */
		boolean builtFingerprint() {
			return armorStands > 0 || itemFrames > 0 || paintings > 0 || chestBoats > 0
					|| pets > 0 || killChamber || collectionPoint;
		}

		/** Live crystal/TNT grief hardware — the fingerprint of a PvP blow-up, not a base. */
		boolean combatSignature() {
			return endCrystals >= 1 || tnt >= 1;
		}
	}

	/** Bounding stats for one entity group inside a chunk. */
	private static final class MobCluster {
		int count;
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

		void add(Entity entity) {
			count++;
			minX = Math.min(minX, entity.getX());
			maxX = Math.max(maxX, entity.getX());
			minY = Math.min(minY, entity.getY());
			maxY = Math.max(maxY, entity.getY());
			minZ = Math.min(minZ, entity.getZ());
			maxZ = Math.max(maxZ, entity.getZ());
		}

		double spreadX() {
			return count == 0 ? 0.0 : maxX - minX;
		}

		double spreadY() {
			return count == 0 ? 0.0 : maxY - minY;
		}

		double spreadZ() {
			return count == 0 ? 0.0 : maxZ - minZ;
		}
	}

	private static final class ActivityChunk {
		private final int chunkX;
		private final int chunkZ;
		private double score;
		private String why;
		private int padY;
		private int tier; // 0 green · 1 yellow · 2 red — hysteresis-stabilized
		private final int firstSeenTick;
		private int lastSeenTick;
		private final float seed;

		ActivityChunk(int chunkX, int chunkZ, double score, int tick, String why, int padY) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.score = score;
			this.why = why;
			this.padY = padY;
			this.firstSeenTick = tick;
			this.lastSeenTick = tick;
			this.seed = (float) ((chunkX * 0.73 + chunkZ * 0.41) % (Math.PI * 2.0));
			updateTier();
		}

		long key() {
			return ChunkPos.toLong(chunkX, chunkZ);
		}

		/**
		 * Tier climbs only — once a chunk reaches a confidence band it stays
		 * there for its display life. A chunk's evidence is bursty (sounds,
		 * passing entities) so the raw tier oscillated and the pad flipped
		 * green↔yellow↔red every rescan; a monotonic tier ends that flicker for
		 * good (the chunk ages out via the TTL when we leave, not by downgrading).
		 */
		void updateTier() {
			int raw = score >= 110.0 ? 2 : score >= 45.0 ? 1 : 0;
			if (raw > tier) tier = raw;
		}

		int tierColor() {
			return tier == 2 ? 0xFFFF3B30 : tier == 1 ? 0xFFFFD44A : 0xFF38FF7A;
		}

		/**
		 * Flat chunk-footprint outline pinned to the y=0 plane. Earlier it sat at
		 * the deepest evidence Y, which could drop below y=0 — pads at mismatched
		 * heights then crossed over each other through walls and hid each other's
		 * edges. Keeping every pad coplanar makes overlapping regions read cleanly.
		 * (The evidence depth still shows in the label, e.g. "y-37".)
		 */
		Box footprint() {
			double x = chunkX << 4;
			double z = chunkZ << 4;
			return new Box(x, 0.0, z, x + 16.0, 1.0, z + 16.0);
		}

		/** Map distance to the player, ignoring vertical offset (this is a top-down base finder). */
		double horizontalDistanceSq(Entity player) {
			return horizontalDistanceSq(player.getX(), player.getZ());
		}

		double horizontalDistanceSq(double x, double z) {
			double dx = ((chunkX << 4) + 8.0) - x;
			double dz = ((chunkZ << 4) + 8.0) - z;
			return dx * dx + dz * dz;
		}

		/**
		 * Confidence-tiered memory. Detections come from bursty signals (sounds,
		 * passing entities) that legitimately go quiet between rescans — a pad
		 * must survive that quiet phase, not blink out while the player is just
		 * mining or walking nearby. Red holds 10 minutes (and is archived to
		 * disk), yellow 5 minutes, green 2 minutes.
		 */
		int ttlTicks() {
			if (score >= 110.0) return 12000;
			if (score >= 45.0) return 6000;
			return 2400;
		}

		double score() {
			return score;
		}

		String why() {
			return why;
		}

		float seed() {
			return seed;
		}

		float fade(float renderTick) {
			float in = MathHelper.clamp((renderTick - firstSeenTick) / FADE_TICKS, 0.0F, 1.0F);
			float out = MathHelper.clamp((lastSeenTick + ttlTicks() - renderTick) / 30.0F, 0.0F, 1.0F);
			return smooth(in) * smooth(out);
		}

		private static float smooth(float value) {
			return value * value * (3.0F - 2.0F * value);
		}
	}

	/** Disk-archived confirmed base (per server). */
	private static final class SavedBase {
		int x;
		int z;
		double score;
		String why;
		int padY;
		long t;
	}
}
