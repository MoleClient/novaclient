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
 * Draws the unbuilt cells of the loaded schematic as a translucent hologram. The visible
 * set is rebuilt on a timer rather than per frame.
 */
public final class SchematicGhostRenderer {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float GHOST_ALPHA = 0.42F;
	/** Alpha for the layer currently being built. */
	private static final float ACTIVE_ALPHA = 0.80F;
	private static final float GHOST_SCALE = 0.92F;
	/** Cells further than this from the camera are not collected. */
	private static final int RENDER_RADIUS = 80;
	private static final int MAX_GHOSTS = 20_000;
	/** Ceiling on cells examined per rebuild. */
	private static final int MAX_SCANNED = 600_000;
	private static final int REBUILD_INTERVAL_TICKS = 10;

	private final ProFPSConfig config;

	private List<Ghost> ghosts = List.of();
	private long builtRevision = Long.MIN_VALUE;
	private int nextRebuildTick;
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

			// Two consumers over one buffer so the active layer draws brighter without re-sorting.
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
			// Never let a ghost render failure take down the client.
		}
	}

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
		// Clip to a cube around the camera so cost is bounded by view distance, not schematic size.
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

		// Bottom-up so a truncated ghost keeps the layers the builder reaches first.
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
	 * Whether the world already holds this cell's block. Compares only the properties a
	 * player controls; connection state and redstone power are world-computed and ignored.
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

	/** Forces one alpha onto every vertex so a solid model draws translucent. */
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
