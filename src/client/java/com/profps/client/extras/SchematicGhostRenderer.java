package com.profps.client.extras;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the loaded schematic as a translucent hologram of what is still missing.
 *
 * <p>Only cells that are not yet built are drawn, so the ghost is a live picture
 * of the remaining work rather than a copy of the schematic laid over the world:
 * as the builder fills a layer, that layer fades away block by block. The layer
 * the builder is currently working is drawn brighter than the rest, which is
 * what makes the bottom-to-top progression legible from a distance.
 *
 * <p>Deciding what is missing means testing every cell of the schematic against
 * the world, which is far too much to do per frame — the schematics this was
 * built against run to tens of thousands of cells. So the visible set is
 * computed on a timer and on every load, then replayed each frame. That also
 * bounds the cost of a very large schematic: the scan is capped, and cells are
 * clipped to a radius around the camera before anything is collected.
 *
 * <p>This is a separate renderer from the Remember ghosts on purpose. Remember
 * draws captured builds; this draws a file. They can be on at the same time and
 * neither knows about the other.
 */
public final class SchematicGhostRenderer {
	private static final int FULL_BRIGHT = 0xF000F0;
	/** Dimmer than the Remember ghosts, so the two are told apart at a glance. */
	private static final float GHOST_ALPHA = 0.42F;
	/** The layer being built right now, lifted out of the stack. */
	private static final float ACTIVE_ALPHA = 0.80F;
	private static final float GHOST_SCALE = 0.92F;
	/** Cells further than this from the camera are not collected. */
	private static final int RENDER_RADIUS = 80;
	/** Ceiling on drawn ghosts, so a huge schematic cannot sink the frame rate. */
	private static final int MAX_GHOSTS = 20_000;
	/** Ceiling on cells examined per rebuild. */
	private static final int MAX_SCANNED = 600_000;
	private static final int REBUILD_INTERVAL_TICKS = 10;

	private final ProFPSConfig config;

	private List<Ghost> ghosts = List.of();
	private long builtRevision = Long.MIN_VALUE;
	private int nextRebuildTick;
	// One warning, not one per rebuild: a schematic big enough to truncate
	// truncates every time.
	private boolean warnedTruncated;

	public SchematicGhostRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void render(WorldRenderContext ctx) {
		if (!config.enabled || !config.schematicShowGhost || !SchematicLibrary.isLoaded()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) return;
		try {
			refresh(client);
			if (ghosts.isEmpty()) return;

			MatrixStack matrices = ctx.matrices();
			if (matrices == null || ctx.consumers() == null) return;
			Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
			BlockRenderManager blockRenderer = client.getBlockRenderManager();
			int activeLayer = SchematicBuildController.activeLayerY();

			// Two consumers rather than one so the active layer can be brighter
			// without re-sorting anything: each vertex simply goes to the buffer
			// carrying the alpha it should have.
			VertexConsumer base = ctx.consumers().getBuffer(TexturedRenderLayers.getBlockTranslucentCull());
			GhostConsumer pale = new GhostConsumer(base, GHOST_ALPHA);
			GhostConsumer bright = new GhostConsumer(base, ACTIVE_ALPHA);

			for (Ghost ghost : ghosts) {
				BlockPos pos = ghost.pos();
				BlockStateModel model = blockRenderer.getModel(ghost.state());
				matrices.push();
				matrices.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
				matrices.translate(0.5, 0.5, 0.5);
				matrices.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
				matrices.translate(-0.5, -0.5, -0.5);
				BlockModelRenderer.render(matrices.peek(),
						pos.getY() == activeLayer ? bright : pale, model,
						1.0F, 1.0F, 1.0F, FULL_BRIGHT, OverlayTexture.DEFAULT_UV);
				matrices.pop();
			}
		} catch (RuntimeException ignored) {
			// A ghost render failure must never take down the client.
		}
	}

	// ── Visible-set rebuild ────────────────────────────────────────────────────

	private void refresh(MinecraftClient client) {
		int tick = client.player.age;
		long revision = SchematicLibrary.revision();
		if (revision == builtRevision && tick < nextRebuildTick) return;
		builtRevision = revision;
		nextRebuildTick = tick + REBUILD_INTERVAL_TICKS;
		rebuild(client);
	}

	private void rebuild(MinecraftClient client) {
		int[] bounds = SchematicLibrary.bounds();
		if (bounds == null) {
			ghosts = List.of();
			return;
		}
		ClientWorld world = client.world;
		Vec3d eye = client.player.getEntityPos();
		// Clip the schematic box to a cube around the camera before scanning, so
		// the cost is bounded by view distance and not by schematic size.
		int minX = Math.max(bounds[0], (int) Math.floor(eye.x) - RENDER_RADIUS);
		int minY = Math.max(bounds[1], (int) Math.floor(eye.y) - RENDER_RADIUS);
		int minZ = Math.max(bounds[2], (int) Math.floor(eye.z) - RENDER_RADIUS);
		int maxX = Math.min(bounds[3], (int) Math.floor(eye.x) + RENDER_RADIUS);
		int maxY = Math.min(bounds[4], (int) Math.floor(eye.y) + RENDER_RADIUS);
		int maxZ = Math.min(bounds[5], (int) Math.floor(eye.z) + RENDER_RADIUS);

		List<Ghost> collected = new ArrayList<>();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		int scanned = 0;
		boolean capped = false;

		// Bottom-up, so a truncated ghost keeps the layers the builder will reach
		// first — the ones worth seeing.
		outer:
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					if (++scanned > MAX_SCANNED || collected.size() >= MAX_GHOSTS) {
						capped = true;
						break outer;
					}
					cursor.set(x, y, z);
					BlockState desired = SchematicLibrary.stateAt(cursor);
					if (desired == null || desired.isAir()) continue;
					if (alreadyBuilt(world.getBlockState(cursor), desired)) continue;
					collected.add(new Ghost(cursor.toImmutable(), desired));
				}
			}
		}
		ghosts = List.copyOf(collected);
		if (capped && !warnedTruncated) {
			warnedTruncated = true;
			ProFPS.LOGGER.info("[AutoBuild] hologram capped at {} blocks; it shows the layers "
					+ "nearest you and the builder still builds the whole schematic", collected.size());
		}
	}

	/**
	 * Whether the world already holds this cell's block.
	 *
	 * <p>Compares the block plus the properties a player controls by where they
	 * stand and what they click. Connection state, redstone power and the rest
	 * are deliberately ignored: the world computes those itself, and demanding
	 * they match would leave ghosts hanging over finished blocks forever.
	 */
	private static boolean alreadyBuilt(BlockState placed, BlockState desired) {
		if (placed.getBlock() != desired.getBlock()) return false;
		for (String property : new String[]{"facing", "axis", "half", "type", "rotation",
				"face", "attachment", "hanging", "vertical_direction", "layers", "waterlogged"}) {
			String want = SchematicBlockRules.propertyValue(desired, property);
			if (want.isEmpty()) continue;
			if (!want.equals(SchematicBlockRules.propertyValue(placed, property))) return false;
		}
		return true;
	}

	private record Ghost(BlockPos pos, BlockState state) {
	}

	/** Forces one alpha onto every vertex, so a solid model draws as a hologram. */
	private static final class GhostConsumer implements VertexConsumer {
		private final VertexConsumer delegate;
		private final int alpha;

		private GhostConsumer(VertexConsumer delegate, float alpha) {
			this.delegate = delegate;
			this.alpha = Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);
		}

		@Override public VertexConsumer vertex(float x, float y, float z) { delegate.vertex(x, y, z); return this; }
		@Override public VertexConsumer color(int red, int green, int blue, int ignored) { delegate.color(red, green, blue, alpha); return this; }
		@Override public VertexConsumer color(int argb) { return color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >> 24) & 0xFF); }
		@Override public VertexConsumer texture(float u, float v) { delegate.texture(u, v); return this; }
		@Override public VertexConsumer overlay(int u, int v) { delegate.overlay(u, v); return this; }
		@Override public VertexConsumer light(int u, int v) { delegate.light(u, v); return this; }
		@Override public VertexConsumer normal(float x, float y, float z) { delegate.normal(x, y, z); return this; }
		@Override public VertexConsumer lineWidth(float width) { delegate.lineWidth(width); return this; }
	}
}
