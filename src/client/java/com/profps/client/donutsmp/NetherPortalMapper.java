package com.profps.client.donutsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4fc;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Nether Portal Mapper.
 *
 * <p>The nether is the single biggest force-multiplier for base hunting: 1 block
 * of nether travel = 8 overworld blocks, so 6 chunks of nether render distance
 * covers 768 overworld blocks. This module scans the nether for active portals,
 * multiplies their coordinates by 8 to infer the linked overworld position, and
 * persists those inferred coordinates per-server to disk so the map grows across
 * sessions. In the overworld it renders a beam at each inferred portal so you can
 * fly straight to it.
 */
public final class NetherPortalMapper implements HudRenderCallback {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<PortalEntry>>() {}.getType();
	private static final int SCAN_INTERVAL_TICKS = 100;
	private static final int CLUSTER_DISTANCE = 6;
	private static final int COLOR = 0xFFB14BFF;

	private final ProFPSConfig config;
	private final List<PortalEntry> portals = new ArrayList<>();
	private String loadedFor = "";
	private int nextScanTick;
	private boolean failedClosed;
	private boolean dirty;
	private int saveCooldown;

	// ── Incremental scan state (one cycle is spread across many ticks) ─────────
	private final List<long[]> scanQueue = new ArrayList<>();
	private int scanMinY;
	private int scanMaxY;

	public NetherPortalMapper(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.donutNetherPortalMapper) {
			failedClosed = false;
			return;
		}
		if (failedClosed || client.world == null || client.player == null) return;

		String key = serverKey(client);
		if (!key.equals(loadedFor)) {
			load(key);
			loadedFor = key;
		}

		if (saveCooldown > 0) saveCooldown--;
		if (dirty && saveCooldown <= 0) {
			save();
			dirty = false;
			saveCooldown = 60;
		}

		if (!client.world.getRegistryKey().equals(World.NETHER)) {
			scanQueue.clear();
			return;
		}
		// player.age resets on world change; never let a stale clock stall scans.
		if (nextScanTick > client.player.age + SCAN_INTERVAL_TICKS) nextScanTick = 0;
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
			ProFPS.LOGGER.error("Nether Portal Mapper scan failed; disabling to protect the client.", exception);
			scanQueue.clear();
			config.donutNetherPortalMapper = false;
			config.save();
			failedClosed = true;
		}
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutNetherPortalMapper || failedClosed || portals.isEmpty()) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;
			boolean overworld = mc.world.getRegistryKey().equals(World.OVERWORLD);
			boolean nether = mc.world.getRegistryKey().equals(World.NETHER);
			if (!overworld && !nether) return;

			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();
			double bottom = mc.world.getBottomY() + 1.0;
			double top = mc.player.getY() + 40.0;
			List<Box> beams = new ArrayList<>(portals.size());

			for (PortalEntry portal : portals) {
				double px = overworld ? portal.overworldX + 0.5 : portal.netherX + 0.5;
				double pz = overworld ? portal.overworldZ + 0.5 : portal.netherZ + 0.5;
				beams.add(new Box(px - 1.5, bottom, pz - 1.5, px + 1.5, top, pz + 1.5));
			}
			VertexConsumer fills = ctx.consumers().getBuffer(DonutWorldRenderer.FILLS);
			for (Box beam : beams) {
				DonutWorldRenderer.drawFilledBox(fills, pos, beam, camera, COLOR, 0.10F);
			}
			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			for (Box beam : beams) {
				DonutWorldRenderer.drawOutline(lines, pos, entry, beam, camera, COLOR, 0.80F);
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Nether Portal Mapper render failed; disabling to protect the client.", exception);
			config.donutNetherPortalMapper = false;
			config.save();
			failedClosed = true;
		}
	}

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		if (!config.enabled || !config.donutNetherPortalMapper || failedClosed || portals.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null || mc.options.hudHidden) return;
		boolean nether = mc.world.getRegistryKey().equals(World.NETHER);

		int x = context.getScaledWindowWidth() - 158;
		int y = 6;
		context.fill(x - 4, y - 2, context.getScaledWindowWidth() - 2, y + 11 + Math.min(portals.size(), 8) * 10, 0xA0000000);
		context.drawText(mc.textRenderer, Text.literal("Portals → Overworld").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), x, y, 0xFFB14BFF, false);
		y += 11;
		List<PortalEntry> sorted = new ArrayList<>(portals);
		sorted.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
		int shown = 0;
		for (PortalEntry portal : sorted) {
			if (shown >= 8) break;
			String line = portal.overworldX + ", " + portal.overworldZ + (nether ? "" : "");
			context.drawText(mc.textRenderer, Text.literal(line).formatted(Formatting.WHITE), x, y, 0xFFE7D7FF, false);
			y += 10;
			shown++;
		}
	}

	/** Open a scan cycle: enqueue every in-range chunk, nearest first. */
	private void beginScan(MinecraftClient client) {
		ClientWorld world = client.world;
		int cx = client.player.getBlockX() >> 4;
		int cz = client.player.getBlockZ() >> 4;
		int viewDistance = client.options == null ? 8 : client.options.getViewDistance().getValue();
		int radius = MathHelper.clamp(viewDistance, 2, 8);
		scanMinY = world.getBottomY() + 1;
		scanMaxY = Math.min(world.getBottomY() + world.getHeight() - 1, 126);

		scanQueue.clear();
		for (int chunkZ = cz - radius; chunkZ <= cz + radius; chunkZ++) {
			for (int chunkX = cx - radius; chunkX <= cx + radius; chunkX++) {
				scanQueue.add(new long[]{chunkX, chunkZ});
			}
		}
		// Farthest first in the list — chunks pop off the tail, so nearest resolve soonest.
		final int fcx = cx, fcz = cz;
		scanQueue.sort(Comparator.comparingInt(c -> {
			int dx = (int) c[0] - fcx;
			int dz = (int) c[1] - fcz;
			return -(dx * dx + dz * dz);
		}));
	}

	/** Process queued chunks until the shared time budget runs out; portals upsert live. */
	private void stepScan(MinecraftClient client) {
		ClientWorld world = client.world;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		long now = System.currentTimeMillis();
		long pool = ScanBudget.takeBudget(client.player.age, ScanBudget.Lane.NETHER_PORTAL, config);
		if (pool <= 0L) return;
		long start = System.nanoTime();

		while (!scanQueue.isEmpty()) {
			if (System.nanoTime() - start > pool) break;
			long[] coord = scanQueue.remove(scanQueue.size() - 1);
			int chunkX = (int) coord[0];
			int chunkZ = (int) coord[1];
			if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
			WorldChunk chunk = world.getChunk(chunkX, chunkZ);
			if (chunk == null || chunk.isEmpty()) continue;
			int startX = chunkX << 4;
			int startZ = chunkZ << 4;
			var sections = chunk.getSectionArray();
			for (int y = scanMinY; y <= scanMaxY; y++) {
				int sectionIndex = chunk.getSectionIndex(y);
				if (sectionIndex < 0 || sectionIndex >= sections.length
						|| sections[sectionIndex] == null || sections[sectionIndex].isEmpty()) {
					y = ((Math.floorDiv(y, 16) + 1) << 4) - 1;
					continue;
				}
				for (int z = startZ; z < startZ + 16; z++) {
					for (int bx = startX; bx < startX + 16; bx++) {
						pos.set(bx, y, z);
						if (chunk.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
							record(bx, y, z, now);
						}
					}
				}
			}
		}
		ScanBudget.reportUsed(client.player.age, ScanBudget.Lane.NETHER_PORTAL, System.nanoTime() - start);
	}

	private void record(int nx, int ny, int nz, long now) {
		for (PortalEntry portal : portals) {
			if (Math.abs(portal.netherX - nx) <= CLUSTER_DISTANCE
					&& Math.abs(portal.netherZ - nz) <= CLUSTER_DISTANCE
					&& Math.abs(portal.netherY - ny) <= 16) {
				portal.lastSeen = now;
				return;
			}
		}
		PortalEntry portal = new PortalEntry();
		portal.netherX = nx;
		portal.netherY = ny;
		portal.netherZ = nz;
		portal.overworldX = nx * 8;
		portal.overworldZ = nz * 8;
		portal.firstSeen = now;
		portal.lastSeen = now;
		portals.add(portal);
		dirty = true;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			mc.inGameHud.getChatHud().addMessage(Text.literal("[Nova] ").formatted(Formatting.DARK_GRAY)
					.append(Text.literal("Portal mapped → overworld ").formatted(Formatting.LIGHT_PURPLE))
					.append(Text.literal(portal.overworldX + ", " + portal.overworldZ).formatted(Formatting.WHITE)));
		}
	}

	// ── Persistence ─────────────────────────────────────────────────────────────

	private Path fileFor(String key) {
		return FabricLoader.getInstance().getConfigDir().resolve("profps_portals").resolve(key + ".json");
	}

	private void load(String key) {
		portals.clear();
		Path path = fileFor(key);
		if (!Files.exists(path)) return;
		try {
			List<PortalEntry> loaded = GSON.fromJson(Files.newBufferedReader(path), LIST_TYPE);
			if (loaded != null) portals.addAll(loaded);
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to load portal map for {}.", key, exception);
		}
	}

	private void save() {
		Path path = fileFor(loadedFor);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(portals, LIST_TYPE));
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to save portal map.", exception);
		}
	}

	static String serverKey(MinecraftClient client) {
		ServerInfo info = client.getCurrentServerEntry();
		String raw = info != null ? info.address : (client.isInSingleplayer() ? "singleplayer" : "unknown");
		return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	private static final class PortalEntry {
		int netherX, netherY, netherZ;
		int overworldX, overworldZ;
		long firstSeen, lastSeen;
	}
}
