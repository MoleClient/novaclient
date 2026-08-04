package com.profps.client.donutsmp;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Storage ESP — every container and every piece of redstone machinery, drawn as
 * its own outlined, semi-translucent box.
 *
 * <p>Deliberately standalone. Finding stashes used to require Advanced ESP, which
 * also meant carrying its hole/tunnel/stairs terrain scan; the two answer
 * completely different questions and only one of them is usually wanted.
 *
 * <p>Each block gets its own box rather than a merged cluster volume. A stash is
 * read by its shape — a double wall of chests, a barrel row, a hopper line under
 * a farm — and merging that into one blob throws away exactly the detail worth
 * seeing. The outlines are what keep touching containers legible as separate
 * blocks, so they are drawn brighter than the fills.
 *
 * <p>Containers are found through the chunk's block-entity map, which is both far
 * cheaper than a block sweep and more reliable: a server that obfuscates block
 * states still has to send block entities for its containers. Redstone parts are
 * not block entities, so those need a real sweep — but a section can be dismissed
 * in one palette test, so the common case never touches a block.
 */
public final class StorageEspRenderer {
	private static final int SCAN_INTERVAL_TICKS = 40;
	private static final int MAX_MARKERS = 4096;

	private static final int CHEST_COLOR = 0xB35CFF;   // chests / trapped chests
	private static final int ENDER_COLOR = 0x8A4BFF;   // ender chests, a deeper violet
	private static final int SHULKER_COLOR = 0xE3A44E; // shulker boxes and barrels share this
	private static final int REDSTONE_COLOR = 0x4FD971;
	private static final int FURNACE_COLOR = 0xE0663C;

	private final ProFPSConfig config;
	private final List<Marker> markers = new ArrayList<>();
	private volatile List<AreaSnapshot> areas = List.of();
	private int nextScanTick;
	private boolean failedClosed;

	private final List<long[]> scanQueue = new ArrayList<>();
	private int scanCentreChunkX = Integer.MIN_VALUE;
	private int scanCentreChunkZ = Integer.MIN_VALUE;
	private List<Marker> pending;
	private java.util.Map<Long, Cluster> pendingClusters;
	private double scanRangeSq;

	/** Clustered storage worth investigating; consumed by Stash Pinger. */
	public record AreaSnapshot(Box box, String label, Vec3d center, double score) {}

	/**
	 * Grouped storage rather than individual blocks. Stash Pinger asks "is there
	 * a stash here", which is a question about a place, not about one chest.
	 */
	public List<AreaSnapshot> areaSnapshots() {
		return areas;
	}

	public StorageEspRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.donutStorageEsp) {
			markers.clear();
			scanQueue.clear();
			pending = null;
			failedClosed = false;
			return;
		}
		if (failedClosed || client.world == null || client.player == null) return;
		if (nextScanTick > client.player.age + SCAN_INTERVAL_TICKS) nextScanTick = 0;

		// Flying outruns the cycle. A sweep is planned around one point and takes
		// hundreds of ticks; at 40 blocks a second the player is two and a half
		// chunks further on every second, so by the end the queue is grinding
		// terrain far behind and nothing ahead was ever queued. Re-plan around
		// the new position instead. The queue is ordered nearest-first, so what
		// is closest always gets scanned first no matter how often this fires.
		if (!scanQueue.isEmpty()
				&& ScanBudget.leftScanArea(client, scanCentreChunkX, scanCentreChunkZ, 3)) {
			scanQueue.clear();
			nextScanTick = 0;
		}
		try {
			if (scanQueue.isEmpty()) {
				if (client.player.age < nextScanTick) return;
				if (!ScanBudget.tryClaim(client.player.age)) return;
				nextScanTick = client.player.age + SCAN_INTERVAL_TICKS;
				beginScan(client);
			} else {
				stepScan(client);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Storage ESP scan failed; disabling Storage ESP to protect the client.", exception);
			disable();
		}
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutStorageEsp || failedClosed || markers.isEmpty()) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;

			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();
			double range = MathHelper.clamp(config.donutStorageEspRange, 32, 512);
			double rangeSq = range * range;
			float fill = MathHelper.clamp(config.donutStorageEspOpacity, 5, 60) / 100.0F;

			List<Marker> visible = new ArrayList<>();
			for (Marker marker : markers) {
				if (mc.player.squaredDistanceTo(marker.center()) > rangeSq) continue;
				visible.add(marker);
			}

			// The immediate provider owns one active buffer at a time: every fill
			// has to be emitted before the lines layer is requested, because
			// asking for it flushes the fill buffer.
			VertexConsumer fills = ctx.consumers().getBuffer(DonutWorldRenderer.FILLS);
			for (Marker marker : visible) {
				DonutWorldRenderer.drawFilledBox(fills, pos, marker.box(), camera, marker.color(), fill);
			}
			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			for (Marker marker : visible) {
				DonutWorldRenderer.drawOutline(lines, pos, entry, marker.box(), camera, marker.color(), 0.90F);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Storage ESP render failed; disabling Storage ESP to protect the client.", exception);
			disable();
		}
	}

	private void disable() {
		markers.clear();
		scanQueue.clear();
		pending = null;
		config.donutStorageEsp = false;
		config.save();
		failedClosed = true;
	}

	private void beginScan(MinecraftClient client) {
		int centerChunkX = client.player.getBlockX() >> 4;
		int centerChunkZ = client.player.getBlockZ() >> 4;
		int range = MathHelper.clamp(config.donutStorageEspRange, 32, 512);
		int viewDistance = client.options == null ? 12 : client.options.getViewDistance().getValue();
		int radius = MathHelper.clamp(MathHelper.ceil(range / 16.0F), 2, Math.min(16, viewDistance + 1));
		scanRangeSq = range * (double) range;
		pending = new ArrayList<>();
		pendingClusters = new java.util.HashMap<>();
		scanCentreChunkX = centerChunkX;
		scanCentreChunkZ = centerChunkZ;

		scanQueue.clear();
		for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
			for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
				scanQueue.add(new long[]{chunkX, chunkZ});
			}
		}
		// Farthest first: chunks pop off the tail, so the nearest resolve soonest.
		scanQueue.sort(Comparator.comparingInt(c -> {
			int dx = (int) c[0] - centerChunkX;
			int dz = (int) c[1] - centerChunkZ;
			return -(dx * dx + dz * dz);
		}));
	}

	private void stepScan(MinecraftClient client) {
		ClientWorld world = client.world;
		long pool = ScanBudget.takeBudget(client.player.age, ScanBudget.Lane.STORAGE_ESP, config);
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
			scanContainers(client, chunk);
			if (config.donutStorageShowRedstone) scanRedstone(client, chunk);
		}

		ScanBudget.reportUsed(client.player.age, ScanBudget.Lane.STORAGE_ESP, System.nanoTime() - start);
		if (expired || !scanQueue.isEmpty()) return;

		List<Marker> next = pending;
		pending = null;
		next.sort(Comparator.comparingDouble(marker -> client.player.squaredDistanceTo(marker.center())));
		markers.clear();
		for (int i = 0; i < next.size() && i < MAX_MARKERS; i++) markers.add(next.get(i));

		List<AreaSnapshot> snapshots = new ArrayList<>();
		for (Cluster cluster : pendingClusters.values()) {
			AreaSnapshot snapshot = cluster.snapshot();
			if (snapshot != null) snapshots.add(snapshot);
		}
		pendingClusters = null;
		areas = List.copyOf(snapshots);
	}

	/** Containers come from the block-entity map — cheaper and harder to hide. */
	private void scanContainers(MinecraftClient client, WorldChunk chunk) {
		for (Map.Entry<BlockPos, BlockEntity> found : chunk.getBlockEntities().entrySet()) {
			BlockPos pos = found.getKey();
			if (client.player.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
					> scanRangeSq) continue;
			int color = containerColor(found.getValue());
			if (color == 0) continue;
			add(pos, color);
		}
	}

	private int containerColor(BlockEntity blockEntity) {
		if (blockEntity instanceof EnderChestBlockEntity) {
			return config.donutStorageShowChests ? ENDER_COLOR : 0;
		}
		if (blockEntity instanceof ChestBlockEntity) {
			return config.donutStorageShowChests ? CHEST_COLOR : 0;
		}
		if (blockEntity instanceof ShulkerBoxBlockEntity || blockEntity instanceof BarrelBlockEntity) {
			return config.donutStorageShowShulkers ? SHULKER_COLOR : 0;
		}
		// Hoppers, droppers, dispensers and crafters are containers, but on a
		// farm they are the machinery — group them with redstone so hiding the
		// machinery hides all of it at once.
		if (blockEntity instanceof HopperBlockEntity || blockEntity instanceof DispenserBlockEntity
				|| blockEntity instanceof CrafterBlockEntity) {
			return config.donutStorageShowRedstone ? REDSTONE_COLOR : 0;
		}
		if (blockEntity instanceof AbstractFurnaceBlockEntity || blockEntity instanceof BrewingStandBlockEntity
				|| blockEntity instanceof BeaconBlockEntity) {
			return config.donutStorageShowFurnaces ? FURNACE_COLOR : 0;
		}
		return 0;
	}

	/**
	 * Redstone parts are plain block states, so this is a real sweep — but a
	 * whole 16-cube section is dismissed by one palette test, so sections without
	 * any redstone in them never cost a block lookup.
	 */
	private void scanRedstone(MinecraftClient client, WorldChunk chunk) {
		ChunkSection[] sections = chunk.getSectionArray();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		for (int index = 0; index < sections.length; index++) {
			ChunkSection section = sections[index];
			if (section == null || section.isEmpty()) continue;
			if (!section.hasAny(StorageEspRenderer::isRedstonePart)) continue;
			int baseY = chunk.sectionIndexToCoord(index) << 4;
			BlockPos.Mutable pos = new BlockPos.Mutable();
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!isRedstonePart(state)) continue;
						int worldX = startX + x;
						int worldY = baseY + y;
						int worldZ = startZ + z;
						if (client.player.squaredDistanceTo(worldX + 0.5D, worldY + 0.5D, worldZ + 0.5D)
								> scanRangeSq) continue;
						pos.set(worldX, worldY, worldZ);
						add(pos, REDSTONE_COLOR);
					}
				}
			}
		}
	}

	/**
	 * Machinery, not wiring. Redstone dust and repeated torches carpet a farm in
	 * hundreds of boxes and bury the thing you are trying to see, so the sweep
	 * looks for the parts that mark out what a contraption actually is.
	 */
	private static boolean isRedstonePart(BlockState state) {
		return state.isOf(Blocks.OBSERVER) || state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON)
				|| state.isOf(Blocks.REPEATER) || state.isOf(Blocks.COMPARATOR)
				|| state.isOf(Blocks.REDSTONE_BLOCK) || state.isOf(Blocks.REDSTONE_LAMP)
				|| state.isOf(Blocks.NOTE_BLOCK) || state.isOf(Blocks.TARGET)
				|| state.isOf(Blocks.DAYLIGHT_DETECTOR) || state.isOf(Blocks.SLIME_BLOCK)
				|| state.isOf(Blocks.HONEY_BLOCK) || state.isOf(Blocks.TNT);
	}

	private void add(BlockPos pos, int color) {
		if (pending == null) return;
		if (pending.size() < MAX_MARKERS) {
			// A hair of inflation so a box on a shared face still reads as two
			// outlines rather than one doubled line.
			pending.add(new Marker(new Box(pos).expand(0.012D), color));
		}
		if (pendingClusters == null) return;
		long key = clusterKey(pos);
		pendingClusters.computeIfAbsent(key, ignored -> new Cluster()).add(pos, color);
	}

	/** Storage within the same 12-block cell counts as one place. */
	private static long clusterKey(BlockPos pos) {
		long x = Math.floorDiv(pos.getX(), 12) & 0x1FFFFFL;
		long y = Math.floorDiv(pos.getY(), 12) & 0x3FFL;
		long z = Math.floorDiv(pos.getZ(), 12) & 0x1FFFFFL;
		return (x << 32) | (y << 21) | z;
	}

	private record Marker(Box box, int color) {
		Vec3d center() {
			return new Vec3d((box.minX + box.maxX) * 0.5D, (box.minY + box.maxY) * 0.5D,
					(box.minZ + box.maxZ) * 0.5D);
		}
	}

	private static final class Cluster {
		private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		private int containers;
		private int special;

		void add(BlockPos pos, int color) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
			if (color == SHULKER_COLOR || color == ENDER_COLOR) special++;
			if (color != REDSTONE_COLOR) containers++;
		}

		/** Null unless this is enough storage in one place to be worth a ping. */
		AreaSnapshot snapshot() {
			if (containers < 3 && special == 0) return null;
			Box box = new Box(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
			Vec3d center = new Vec3d((box.minX + box.maxX) * 0.5D, (box.minY + box.maxY) * 0.5D,
					(box.minZ + box.maxZ) * 0.5D);
			return new AreaSnapshot(box, "Storage", center, containers * 8.0D + special * 20.0D);
		}
	}
}
