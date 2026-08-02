package com.profps.client.donutsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4fc;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent Player Sighting Log.
 *
 * <p>Players have to physically travel to and from their base, so over time the
 * chunks where you repeatedly see the same people form a heatmap that points at
 * their home. This module buckets every sighting of another player by chunk and
 * dimension, decays old data, and persists per-server to disk so the heatmap
 * survives across sessions. Hot chunks are rendered as beams and listed on the
 * HUD. Most powerful when paired with the Nether Portal Mapper.
 */
public final class PlayerSightingLog implements HudRenderCallback {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<Sighting>>() {}.getType();
	private static final int SAMPLE_INTERVAL_TICKS = 20;     // log at most ~1/s per chunk
	private static final int MAX_RENDERED = 24;
	private static final int COLOR = 0xFFFF5BD0;

	private final ProFPSConfig config;
	private final Map<String, Sighting> byKey = new HashMap<>();
	private String loadedFor = "";
	private int nextSampleTick;
	private boolean failedClosed;
	private boolean dirty;
	private int saveCooldown;

	public PlayerSightingLog(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (!config.enabled || !config.donutPlayerSightings) {
			failedClosed = false;
			return;
		}
		if (failedClosed || client.world == null || client.player == null) return;

		String server = NetherPortalMapper.serverKey(client);
		if (!server.equals(loadedFor)) {
			load(server);
			loadedFor = server;
		}

		if (saveCooldown > 0) saveCooldown--;
		if (dirty && saveCooldown <= 0) {
			save();
			dirty = false;
			saveCooldown = 80;
		}

		if (client.player.age < nextSampleTick) return;
		nextSampleTick = client.player.age + SAMPLE_INTERVAL_TICKS;
		try {
			sample(client);
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Player Sighting Log failed; disabling to protect the client.", exception);
			config.donutPlayerSightings = false;
			config.save();
			failedClosed = true;
		}
	}

	private void sample(MinecraftClient client) {
		String dim = client.world.getRegistryKey().getValue().toString();
		long now = System.currentTimeMillis();
		for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
			if (player == client.player || !player.isAlive()) continue;
			if (player.isSpectator()) continue;
			int chunkX = player.getBlockX() >> 4;
			int chunkZ = player.getBlockZ() >> 4;
			String key = dim + ":" + chunkX + ":" + chunkZ;
			Sighting sighting = byKey.get(key);
			if (sighting == null) {
				sighting = new Sighting();
				sighting.dimension = dim;
				sighting.chunkX = chunkX;
				sighting.chunkZ = chunkZ;
				sighting.firstSeen = now;
				byKey.put(key, sighting);
			}
			sighting.count++;
			sighting.lastSeen = now;
			sighting.lastName = player.getName().getString();
			dirty = true;
		}
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutPlayerSightings || failedClosed || byKey.isEmpty()) return;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;
			String dim = mc.world.getRegistryKey().getValue().toString();

			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc pos = entry.getPositionMatrix();
			double bottom = mc.world.getBottomY() + 1.0;
			double top = mc.player.getY() + 24.0;
			double range = MathHelper.clamp(config.donutChunkActivityRange, 48, 1024);

			List<Sighting> sorted = topSightings();
			List<SightingRender> visible = new ArrayList<>();
			for (Sighting sighting : sorted) {
				if (visible.size() >= MAX_RENDERED) break;
				if (!sighting.dimension.equals(dim)) continue;
				Vec3d center = sighting.center();
				if (mc.player.squaredDistanceTo(center) > range * range) continue;
				float intensity = MathHelper.clamp(sighting.count / 40.0F, 0.12F, 0.85F);
				double x = sighting.chunkX << 4;
				double z = sighting.chunkZ << 4;
				visible.add(new SightingRender(new Box(x + 5.0, bottom, z + 5.0, x + 11.0, top, z + 11.0), intensity));
			}
			VertexConsumer fills = ctx.consumers().getBuffer(DonutWorldRenderer.FILLS);
			for (SightingRender render : visible) {
				DonutWorldRenderer.drawFilledBox(fills, pos, render.beam(), camera, COLOR,
						0.06F + 0.10F * render.intensity());
			}
			VertexConsumer lines = ctx.consumers().getBuffer(DonutWorldRenderer.LINES);
			for (SightingRender render : visible) {
				DonutWorldRenderer.drawOutline(lines, pos, entry, render.beam(), camera, COLOR,
						0.45F + 0.40F * render.intensity());
			}
		} catch (RuntimeException exception) {
			ProFPS.LOGGER.error("Player Sighting render failed; disabling to protect the client.", exception);
			config.donutPlayerSightings = false;
			config.save();
			failedClosed = true;
		}
	}

	private record SightingRender(Box beam, float intensity) {}

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		if (!config.enabled || !config.donutPlayerSightings || failedClosed || byKey.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null || mc.options.hudHidden) return;

		int x = context.getScaledWindowWidth() - 158;
		int y = context.getScaledWindowHeight() - 96;
		List<Sighting> sorted = topSightings();
		context.fill(x - 4, y - 2, context.getScaledWindowWidth() - 2, y + 11 + Math.min(sorted.size(), 6) * 10, 0xA0000000);
		context.drawText(mc.textRenderer, Text.literal("Player Heatmap").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), x, y, 0xFFFF5BD0, false);
		y += 11;
		int shown = 0;
		for (Sighting sighting : sorted) {
			if (shown >= 6) break;
			int bx = (sighting.chunkX << 4) + 8;
			int bz = (sighting.chunkZ << 4) + 8;
			String name = sighting.lastName == null ? "?" : sighting.lastName;
			String line = bx + "," + bz + " x" + sighting.count + " " + name;
			context.drawText(mc.textRenderer, Text.literal(line).formatted(Formatting.WHITE), x, y, 0xFFFFCDEF, false);
			y += 10;
			shown++;
		}
	}

	private List<Sighting> topSightings() {
		List<Sighting> list = new ArrayList<>(byKey.values());
		list.sort((a, b) -> Integer.compare(b.count, a.count));
		return list;
	}

	// ── Persistence ─────────────────────────────────────────────────────────────

	private Path fileFor(String key) {
		return FabricLoader.getInstance().getConfigDir().resolve("profps_sightings").resolve(key + ".json");
	}

	private void load(String key) {
		byKey.clear();
		Path path = fileFor(key);
		if (!Files.exists(path)) return;
		try {
			List<Sighting> loaded = GSON.fromJson(Files.newBufferedReader(path), LIST_TYPE);
			if (loaded != null) {
				for (Sighting sighting : loaded) {
					byKey.put(sighting.dimension + ":" + sighting.chunkX + ":" + sighting.chunkZ, sighting);
				}
			}
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to load player sightings for {}.", key, exception);
		}
	}

	private void save() {
		Path path = fileFor(loadedFor);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(new ArrayList<>(byKey.values()), LIST_TYPE));
		} catch (Exception exception) {
			ProFPS.LOGGER.warn("Failed to save player sightings.", exception);
		}
	}

	private static final class Sighting {
		String dimension;
		int chunkX, chunkZ;
		int count;
		long firstSeen, lastSeen;
		String lastName;

		Vec3d center() {
			return new Vec3d((chunkX << 4) + 8.0, 64.0, (chunkZ << 4) + 8.0);
		}
	}
}
