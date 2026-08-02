package com.profps.client.donutsmp;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk Finder — the flat chunk-footprint pads (red = very sure it's a base,
 * yellow = kind of sure, green = possible activity) drawn at y=0 / the deepest
 * evidence level, visible through terrain like a chunk-finder overlay.
 *
 * <p>Detection comes from the shared {@link ChunkActivityRenderer} engine;
 * this module owns only the world overlay, so the pads can be toggled
 * independently of the Base Heat radar HUD.
 */
public final class ChunkFinderRenderer {
	private final ProFPSConfig config;
	private final ChunkActivityRenderer engine;
	private boolean failedClosed;

	public ChunkFinderRenderer(ProFPSConfig config, ChunkActivityRenderer engine) {
		this.config = config;
		this.engine = engine;
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutChunkFinder) {
			failedClosed = false;
			return;
		}
		if (failedClosed || engine.engineFailed()) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;

			Camera cam = mc.gameRenderer.getCamera();
			Vec3d camera = cam.getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();
			float renderTick = mc.player.age + mc.getRenderTickCounter().getTickProgress(false);
			double range = MathHelper.clamp(config.donutChunkFinderRange, 48, 1024);

			// Range-filter around the camera (matters in freecam, where the
			// camera scouts far from the player's anchored body). One base often
			// flags several touching chunks of DIFFERENT tiers; drawing each chunk
			// in its own colour produced ugly red-boxes-inside-yellow-boxes. We
			// group touching chunks into one cluster, paint it as a single flat
			// translucent sheet in one unified colour (the strongest tier), and
			// outline only the cluster's outer perimeter — one clean region.
			List<ChunkActivityRenderer.Pad> live = engine.livePads(camera.x, camera.z, range, renderTick);
			List<Cluster> clusters = buildClusters(live);
			List<Cluster> archived = buildClusters(engine.archivedPads(camera.x, camera.z, range, renderTick));
			List<RevealedIntel.Marker> intel = RevealedIntel.get().markersNear(camera.x, camera.z, range, 32);

			Vec3d look = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
			Vec3d traceFrom = camera.add(look.multiply(2.0));

			// Snapshot labels before submitting geometry. The immediate provider supports one
			// active layer, so all fills must finish before lines, and text must be last.
			List<LabelReq> pendingLabels = new ArrayList<>();
			if (config.donutChunkFinderLabels) {
				for (Cluster cluster : clusters) {
					pendingLabels.add(new LabelReq(cluster.anchor, cluster.color, cluster.anchor.fade()));
				}
				for (Cluster cluster : archived) {
					pendingLabels.add(new LabelReq(cluster.anchor, cluster.color, 0.85F));
				}
			}

			VertexConsumer fills = ctx.consumers().getBuffer(DonutWorldRenderer.FILLS);
			for (Cluster cluster : clusters) {
				float fade = cluster.anchor.fade();
				float pulse = 0.82F + 0.18F * (float) Math.sin(renderTick * 0.11F + cluster.anchor.seed());
				int color = cluster.color;

				// Fade the translucent FILL out with distance so far-off regions
				// don't stack their see-through sheets into a mush that reads as
				// "overlapping chunks". The crisp perimeter outline always stays,
				// so distant bases are still clearly marked, just not filled.
				double dist = Math.sqrt(camera.squaredDistanceTo(anchorPoint(cluster.anchor.box())));
				float fillDist = (float) MathHelper.clamp(1.2 - dist / 96.0, 0.0, 1.0);

				if (fillDist > 0.01F) {
					for (ChunkActivityRenderer.Pad member : cluster.members) {
						DonutWorldRenderer.drawFlatTop(fills, pos, member.box(), camera, color, (0.07F + 0.05F * pulse) * fade * fillDist);
					}
				}
			}

			// Archived bases from previous sessions: steady amber regions.
			for (Cluster cluster : archived) {
				for (ChunkActivityRenderer.Pad member : cluster.members) {
					DonutWorldRenderer.drawFlatTop(fills, pos, member.box(), camera, cluster.color, 0.05F);
				}
			}

			// Acquiring lines flushes fills; the fill consumer is never touched again.
			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			int tracers = 0;
			for (Cluster cluster : clusters) {
				float fade = cluster.anchor.fade();
				float pulse = 0.82F + 0.18F * (float) Math.sin(renderTick * 0.11F + cluster.anchor.seed());
				// Bright, near-opaque perimeter so edges stay readable even where
				// several regions overlap on screen (the faint fill never hides them).
				drawRegionPerimeter(lines, pos, entry, camera, cluster, cluster.color,
						(0.92F + 0.08F * pulse) * fade);
				if (config.donutChunkFinderTracers && tracers < 12) {
					Vec3d target = anchorPoint(cluster.anchor.box());
					DonutWorldRenderer.drawLine(lines, pos, entry, camera,
							traceFrom.x, traceFrom.y, traceFrom.z, target.x, target.y, target.z,
							cluster.color, 0.42F * fade);
					tracers++;
				}
			}
			for (Cluster cluster : archived) {
				drawRegionPerimeter(lines, pos, entry, camera, cluster, cluster.color, 0.70F);
			}

			// Mask-piercing intel: exact revealed container/utility positions.
			for (RevealedIntel.Marker marker : intel) {
				DonutWorldRenderer.drawOutline(lines, pos, entry, marker.box(), camera, 0xFFFFD44A, 0.85F);
			}

			// All geometry submitted — NOW draw the text labels (safe to flush the buffers).
			for (LabelReq label : pendingLabels) {
				drawPadLabel(ctx, matrices, mc, camera, label.pad(), label.color(), label.fade());
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Chunk Finder render failed; disabling Chunk Finder to protect the client.", exception);
			config.donutChunkFinder = false;
			config.save();
			failedClosed = true;
			ChunkActivityRenderer.announceDisabled(MinecraftClient.getInstance(), "Chunk Finder");
		}
	}

	/** A deferred text label, drawn after all geometry so it can't strand a half-built buffer. */
	private record LabelReq(ChunkActivityRenderer.Pad pad, int color, float fade) {}

	/** One connected group of flagged chunks = one base. */
	private static final class Cluster {
		final List<ChunkActivityRenderer.Pad> members = new ArrayList<>();
		final Set<Long> keys = new HashSet<>();
		ChunkActivityRenderer.Pad anchor; // strongest chunk (markers go here)
		int color;
	}

	/**
	 * Group the (score-ordered) pads into 8-connected clusters. Each cluster's
	 * colour is its strongest tier and its anchor is its highest-score chunk
	 * (the first one reached, since the input is score-ordered).
	 */
	private static List<Cluster> buildClusters(List<ChunkActivityRenderer.Pad> pads) {
		Map<Long, ChunkActivityRenderer.Pad> byKey = new HashMap<>(pads.size() * 2);
		for (ChunkActivityRenderer.Pad pad : pads) {
			byKey.put(ChunkPos.toLong(pad.chunkX(), pad.chunkZ()), pad);
		}
		Set<Long> visited = new HashSet<>();
		List<Cluster> clusters = new ArrayList<>();
		for (ChunkActivityRenderer.Pad seed : pads) {
			long sk = ChunkPos.toLong(seed.chunkX(), seed.chunkZ());
			if (!visited.add(sk)) continue;
			Cluster cluster = new Cluster();
			cluster.anchor = seed;
			cluster.keys.add(sk);
			ArrayDeque<Long> frontier = new ArrayDeque<>();
			frontier.add(sk);
			while (!frontier.isEmpty()) {
				long key = frontier.poll();
				ChunkActivityRenderer.Pad pad = byKey.get(key);
				if (pad != null) {
					cluster.members.add(pad);
					cluster.color = strongerTier(cluster.color, pad.color());
				}
				int cx = ChunkPos.getPackedX(key);
				int cz = ChunkPos.getPackedZ(key);
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dz == 0) continue;
						long neighbor = ChunkPos.toLong(cx + dx, cz + dz);
						if (byKey.containsKey(neighbor) && visited.add(neighbor)) {
							cluster.keys.add(neighbor);
							frontier.add(neighbor);
						}
					}
				}
			}
			clusters.add(cluster);
		}
		return clusters;
	}

	private static int strongerTier(int current, int candidate) {
		return tierRank(candidate) >= tierRank(current) ? candidate : current;
	}

	/** red > yellow > green > amber(archived). */
	private static int tierRank(int color) {
		return switch (color) {
			case 0xFFFF3B30 -> 4; // red — very sure
			case 0xFFFFD44A -> 3; // yellow — kind of sure
			case 0xFF38FF7A -> 2; // green — possible
			case 0xFFFFA22E -> 1; // amber — archived
			default -> 0;
		};
	}

	/** Outline only the cluster's outer perimeter: a face is drawn unless the chunk across it is also in the cluster. */
	private static void drawRegionPerimeter(VertexConsumer lines, Matrix4fc pos, MatrixStack.Entry entry,
			Vec3d camera, Cluster cluster, int color, float alpha) {
		for (ChunkActivityRenderer.Pad member : cluster.members) {
			Box b = member.box();
			int cx = member.chunkX();
			int cz = member.chunkZ();
			double y0 = b.minY;
			double y1 = b.maxY;
			if (!cluster.keys.contains(ChunkPos.toLong(cx - 1, cz))) {
				faceRect(lines, pos, entry, camera, b.minX, b.minZ, b.minX, b.maxZ, y0, y1, color, alpha);
			}
			if (!cluster.keys.contains(ChunkPos.toLong(cx + 1, cz))) {
				faceRect(lines, pos, entry, camera, b.maxX, b.minZ, b.maxX, b.maxZ, y0, y1, color, alpha);
			}
			if (!cluster.keys.contains(ChunkPos.toLong(cx, cz - 1))) {
				faceRect(lines, pos, entry, camera, b.minX, b.minZ, b.maxX, b.minZ, y0, y1, color, alpha);
			}
			if (!cluster.keys.contains(ChunkPos.toLong(cx, cz + 1))) {
				faceRect(lines, pos, entry, camera, b.minX, b.maxZ, b.maxX, b.maxZ, y0, y1, color, alpha);
			}
		}
	}

	/** A vertical rectangle outline between horizontal endpoints (hx0,hz0)→(hx1,hz1), height y0..y1. */
	private static void faceRect(VertexConsumer lines, Matrix4fc pos, MatrixStack.Entry entry, Vec3d cam,
			double hx0, double hz0, double hx1, double hz1, double y0, double y1, int color, float alpha) {
		float w = 2.2F; // thicker than 1px so edges stay legible amid overlapping regions
		DonutWorldRenderer.drawLine(lines, pos, entry, cam, hx0, y0, hz0, hx1, y0, hz1, color, alpha, w); // bottom
		DonutWorldRenderer.drawLine(lines, pos, entry, cam, hx0, y1, hz0, hx1, y1, hz1, color, alpha, w); // top
		DonutWorldRenderer.drawLine(lines, pos, entry, cam, hx0, y0, hz0, hx0, y1, hz0, color, alpha, w); // post A
		DonutWorldRenderer.drawLine(lines, pos, entry, cam, hx1, y0, hz1, hx1, y1, hz1, color, alpha, w); // post B
	}

	/** Point a tracer/label aims at: the centre of the flat pad, just above its face. */
	private static Vec3d anchorPoint(Box box) {
		return new Vec3d((box.minX + box.maxX) / 2.0, box.maxY, (box.minZ + box.maxZ) / 2.0);
	}

	/**
	 * Floating see-through evidence label — "<distance>m · <evidence>" — hovering
	 * just above the pad. Text scales up with distance to hold a near-constant
	 * on-screen size.
	 */
	private void drawPadLabel(WorldRenderContext ctx, MatrixStack matrices, MinecraftClient mc,
			Vec3d camera, ChunkActivityRenderer.Pad pad, int tierColor, float fade) {
		Vec3d anchor = anchorPoint(pad.box());
		double dist = Math.sqrt(camera.squaredDistanceTo(anchor));
		if (dist < 12.0 || dist > 512.0) return; // on top of the pad or unreadably far
		String label = (int) dist + "m · " + pad.why();
		float scale = 0.045F * (float) Math.max(1.0, dist / 28.0);
		int alpha = MathHelper.clamp(Math.round(255 * fade), 0, 255);
		int color = (alpha << 24) | (tierColor & 0xFFFFFF);
		int bg = (Math.round(0x70 * fade) << 24);

		int textWidth = mc.textRenderer.getWidth(label);
		matrices.push();
		matrices.translate(anchor.x - camera.x, anchor.y - camera.y + 1.1, anchor.z - camera.z);
		matrices.multiply(mc.gameRenderer.getCamera().getRotation());
		matrices.scale(-scale, -scale, scale);
		Matrix4f matrix = new Matrix4f(matrices.peek().getPositionMatrix());
		mc.textRenderer.draw(label, -textWidth * 0.5F, 0.0F, color, true, matrix,
				ctx.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, bg, 0x00F000F0);
		matrices.pop();
	}
}
