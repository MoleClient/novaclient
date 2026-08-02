package com.profps.client.donutsmp;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.ui.NovaToast;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AmethystDetectorRenderer {
	private static final int SCAN_INTERVAL_TICKS = 130;
	private static final int MAX_MARKERS = 128;
	/** Any confirmed geode triggers the alert; the cooldown + same-count dedupe stop spam. */
	private static final int ALERT_THRESHOLD = 1;
	private static final int ALERT_COOLDOWN_TICKS = 420;
	private static final int CRYSTAL_COLOR = 0xFFCB74FF;
	private static final int BUDDING_COLOR = 0xFFFFA7F2;

	private final ProFPSConfig config;
	private final List<AmethystMarker> markers = new ArrayList<>();
	private int nextScanTick;
	private int nextAlertTick;
	private int lastAlertCount;
	private boolean failedClosed;

	// ── Incremental scan state (one cycle is spread across many ticks) ─────────
	private final List<long[]> scanQueue = new ArrayList<>();
	private Map<CellKey, AmethystCluster> pendingClusters;
	private int scanMinY;
	private int scanMaxY;
	private double scanRangeSq;

	public AmethystDetectorRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.donutAmethystDetector) {
			markers.clear();
			scanQueue.clear();
			pendingClusters = null;
			failedClosed = false;
			return;
		}
		if (failedClosed || client.world == null || client.player == null) return;
		// player.age resets on world change; never let a stale clock stall scans.
		if (nextScanTick > client.player.age + SCAN_INTERVAL_TICKS) nextScanTick = 0;
		try {
			if (scanQueue.isEmpty()) {
				if (client.player.age < nextScanTick) return;
				// Defer if another heavy scanner already owns this tick, so scans never stack.
				if (!ScanBudget.tryClaim(client.player.age)) return;
				nextScanTick = client.player.age + SCAN_INTERVAL_TICKS;
				beginScan(client);
			} else {
				stepScan(client);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Amethyst Detector scan failed; disabling Amethyst Detector to protect the client.", exception);
			markers.clear();
			scanQueue.clear();
			pendingClusters = null;
			config.donutAmethystDetector = false;
			config.save();
			failedClosed = true;
		}
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutAmethystDetector || failedClosed || markers.isEmpty()) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;

			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();
			float renderTick = mc.player.age + mc.getRenderTickCounter().getTickProgress(false);
			double range = MathHelper.clamp(config.donutAmethystDetectorRange, 48, 1024);
			List<MarkerRender> visible = new ArrayList<>();

			for (AmethystMarker marker : markers) {
				if (visible.size() >= MAX_MARKERS) break;
				if (mc.player.squaredDistanceTo(marker.center()) > range * range) continue;
				float pulse = 0.74F + 0.26F * (float) Math.sin(renderTick * 0.16F + marker.seed());
				visible.add(new MarkerRender(marker, pulse));
			}

			// The immediate provider owns one active buffer at a time. Finish every
			// fill before requesting the lines layer, which flushes the fill buffer.
			VertexConsumer fills = ctx.consumers().getBuffer(DonutWorldRenderer.FILLS);
			for (MarkerRender render : visible) {
				AmethystMarker marker = render.marker();
				DonutWorldRenderer.drawFilledBox(fills, pos, marker.box(), camera, marker.color(),
						0.12F + 0.08F * render.pulse());
			}
			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			for (MarkerRender render : visible) {
				AmethystMarker marker = render.marker();
				DonutWorldRenderer.drawOutline(lines, pos, entry, marker.box(), camera, marker.color(),
						0.72F + 0.22F * render.pulse());
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Amethyst Detector render failed; disabling Amethyst Detector to protect the client.", exception);
			markers.clear();
			config.donutAmethystDetector = false;
			config.save();
			failedClosed = true;
		}
	}

	private record MarkerRender(AmethystMarker marker, float pulse) {}

	/** Open a scan cycle: snapshot bounds and enqueue every in-range chunk, nearest first. */
	private void beginScan(MinecraftClient client) {
		ClientWorld world = client.world;
		int centerChunkX = client.player.getBlockX() >> 4;
		int centerChunkZ = client.player.getBlockZ() >> 4;
		int range = MathHelper.clamp(config.donutAmethystDetectorRange, 48, 1024);
		int viewDistance = client.options == null ? 12 : client.options.getViewDistance().getValue();
		int radius = MathHelper.clamp(MathHelper.ceil(range / 16.0F), 2, Math.min(12, viewDistance + 1));
		scanMinY = world.getBottomY();
		scanMaxY = Math.min(world.getBottomY() + world.getHeight() - 1, Math.max(96, client.player.getBlockY() + 32));
		scanRangeSq = range * (double) range;
		pendingClusters = new HashMap<>();

		scanQueue.clear();
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

	/** Process queued chunks until the shared time budget runs out; commit when drained. */
	private void stepScan(MinecraftClient client) {
		ClientWorld world = client.world;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		long pool = ScanBudget.takeBudget(client.player.age, ScanBudget.Lane.AMETHYST, config);
		if (pool <= 0L) return;
		long start = System.nanoTime();
		boolean expired = false;

		while (!scanQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) {
				expired = true;
				break;
			}
			long[] coord = scanQueue.remove(scanQueue.size() - 1);
			int chunkX = (int) coord[0];
			int chunkZ = (int) coord[1];
			if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
			WorldChunk chunk = world.getChunk(chunkX, chunkZ);
			if (chunk == null || chunk.isEmpty()) continue;
			int startX = chunk.getPos().getStartX();
			int startZ = chunk.getPos().getStartZ();
			var sections = chunk.getSectionArray();
			for (int y = scanMinY; y <= scanMaxY; y++) {
				int sectionIndex = chunk.getSectionIndex(y);
				if (sectionIndex < 0 || sectionIndex >= sections.length
						|| sections[sectionIndex] == null || sections[sectionIndex].isEmpty()) {
					y = ((Math.floorDiv(y, 16) + 1) << 4) - 1;
					continue;
				}
				for (int z = startZ; z < startZ + 16; z++) {
					for (int x = startX; x < startX + 16; x++) {
						if (client.player.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5) > scanRangeSq) continue;
						pos.set(x, y, z);
						BlockState state = chunk.getBlockState(pos);
						if (!isAmethystTarget(state)) continue;
						CellKey key = new CellKey(Math.floorDiv(x, 8), Math.floorDiv(y, 8), Math.floorDiv(z, 8));
						pendingClusters.computeIfAbsent(key, ignored -> new AmethystCluster()).add(x, y, z, state);
					}
				}
			}
		}

		ScanBudget.reportUsed(client.player.age, ScanBudget.Lane.AMETHYST, System.nanoTime() - start);
		if (expired || !scanQueue.isEmpty()) return;

		// Cycle complete — publish markers.
		List<AmethystMarker> next = new ArrayList<>();
		for (AmethystCluster cluster : pendingClusters.values()) {
			if (!cluster.isLikelyGeode()) continue;
			next.add(cluster.marker());
		}
		pendingClusters = null;
		next.sort(Comparator.comparingDouble(marker -> client.player.squaredDistanceTo(marker.center())));
		markers.clear();
		for (int i = 0; i < next.size() && i < MAX_MARKERS; i++) {
			markers.add(next.get(i));
		}
		alertIfDense(client, markers.size());
	}

	private void alertIfDense(MinecraftClient client, int clusterCount) {
		if (clusterCount < ALERT_THRESHOLD || client.player == null || client.player.age < nextAlertTick) return;
		if (clusterCount == lastAlertCount && client.player.age < nextAlertTick + ALERT_COOLDOWN_TICKS) return;

		// Chat line in the same style as the base alert, NovaClient-branded and pink.
		BlockPos nearest = BlockPos.ofFloored(markers.get(0).center());
		Text message = Text.literal("[").formatted(Formatting.WHITE)
				.append(Text.literal("NovaClient").formatted(Formatting.LIGHT_PURPLE))
				.append(Text.literal("] ").formatted(Formatting.WHITE))
				.append(Text.literal("Amethyst Detected At ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
				.append(Text.literal(nearest.getX() + ", " + nearest.getY() + ", " + nearest.getZ())
						.withColor(0xFFA7F2));
		client.inGameHud.getChatHud().addMessage(message);

		// Achievement-style popup with the amethyst cluster item.
		client.getToastManager().add(new NovaToast(
				new ItemStack(Items.AMETHYST_CLUSTER),
				Text.literal("Amethyst Finder Triggered"),
				Text.literal(clusterCount + " Detected Nearby"),
				0xFFE49AFF));

		lastAlertCount = clusterCount;
		nextAlertTick = client.player.age + ALERT_COOLDOWN_TICKS;
	}

	private boolean isAmethystTarget(BlockState state) {
		return isCluster(state) || isBudding(state) || state.isOf(Blocks.AMETHYST_BLOCK);
	}

	private boolean isCluster(BlockState state) {
		return state.isOf(Blocks.AMETHYST_CLUSTER) || state.isOf(Blocks.LARGE_AMETHYST_BUD)
				|| state.isOf(Blocks.MEDIUM_AMETHYST_BUD) || state.isOf(Blocks.SMALL_AMETHYST_BUD);
	}

	private boolean isBudding(BlockState state) {
		return state.isOf(Blocks.BUDDING_AMETHYST);
	}

	private record CellKey(int x, int y, int z) {
	}

	private final class AmethystCluster {
		private int minX = Integer.MAX_VALUE;
		private int minY = Integer.MAX_VALUE;
		private int minZ = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE;
		private int maxY = Integer.MIN_VALUE;
		private int maxZ = Integer.MIN_VALUE;
		private int clusters;
		private int budding;
		private int amethystBlocks;

		void add(int x, int y, int z, BlockState state) {
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
			if (isCluster(state)) clusters++;
			else if (isBudding(state)) budding++;
			else amethystBlocks++;
		}

		boolean isLikelyGeode() {
			int total = clusters + budding + amethystBlocks;
			if (budding > 0 && clusters >= 2 && total >= 6) return true;
			return clusters >= 8 && amethystBlocks >= 8 && total >= 18;
		}

		AmethystMarker marker() {
			Box box = new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).expand(0.18, 0.18, 0.18);
			return new AmethystMarker(box, budding > 0);
		}
	}

	private record AmethystMarker(Box box, boolean budding) {
		Vec3d center() {
			return new Vec3d((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5);
		}

		int color() {
			return budding ? BUDDING_COLOR : CRYSTAL_COLOR;
		}

		float seed() {
			Vec3d center = center();
			return (float) ((center.x * 0.19 + center.y * 0.31 + center.z * 0.27) % (Math.PI * 2.0));
		}
	}
}
