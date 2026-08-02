package com.profps.client.donutsmp;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BasicEspRenderer {
	private static final int MAX_WORLD_TARGETS = 192;
	private static final int MAX_HUD_TARGETS = 96;
	private static final RenderLayer ESP_LINES = createEspLines();

	private final ProFPSConfig config;
	private boolean renderFailed; // set if the world render ever throws — fail safe, never crash the game

	public BasicEspRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void renderWorld(WorldRenderContext ctx) {
		if (!config.enabled || !config.donutBasicEsp || renderFailed) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		MatrixStack matrices = ctx.matrices();
		if (matrices == null) return;

		try {
			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack.Entry entry = matrices.peek();
			Matrix4fc position = entry.getPositionMatrix();
			List<Entity> targets = collectTargets(mc, MAX_WORLD_TARGETS);

			// PASS 1 — all line geometry into ONE lines buffer, with NO text in between.
			// Drawing a nameplate pulls a different layer from the same immediate provider,
			// which flushes (ends) this lines buffer; writing to it afterwards throws
			// "Not building!" and crashes the game (the 10-player-server crash report). So
			// every box/cross/marker goes down first, then the nameplates.
			VertexConsumer lines = ctx.consumers().getBuffer(ESP_LINES);
			for (Entity entity : targets) {
				EspColor color = colorFor(entity);
				Box box = entity.getBoundingBox().expand(0.055);
				drawBox(lines, position, entry, box, camera, color, 0.96F);
				drawCenterCross(lines, position, entry, box, camera, color, 0.72F);
				drawLevelMarker(lines, position, entry, box, camera, mc.player.getY(), color);
			}

			// PASS 2 — nameplates (text). Safe now: the lines buffer is fully submitted, so
			// the text-layer flush can't strand a half-built lines buffer.
			double labelRange = Math.min(config.donutBasicEspRange, 192.0);
			for (Entity entity : targets) {
				Box box = entity.getBoundingBox().expand(0.055);
				if (entity instanceof PlayerEntity || mc.player.squaredDistanceTo(entity) <= labelRange * labelRange) {
					drawWorldNameplate(ctx, matrices, camera, entity, box, mc.player.getY(), colorFor(entity));
				}
			}
		} catch (RuntimeException e) {
			renderFailed = true; // never let an ESP render hiccup take down the whole game
			ProFPS.LOGGER.error("Mob ESP world render failed; disabling its overlay for this session.", e);
		}
	}

	public void renderHud(DrawContext context, RenderTickCounter tickCounter) {
		if (!config.enabled || !config.donutBasicEsp) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;

		List<HudTarget> targets = new ArrayList<>();
		Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
		for (Entity entity : collectTargets(mc, MAX_HUD_TARGETS)) {
			Box box = entity.getBoundingBox();
			double markerY = markerY(box, mc.player.getY());
			Vec3d marker = new Vec3d((box.minX + box.maxX) * 0.5, markerY, (box.minZ + box.maxZ) * 0.5);
			double distanceSq = mc.player.squaredDistanceTo(entity);
			targets.add(new HudTarget(entity, marker, distanceSq, colorFor(entity)));
		}

		targets.sort(Comparator.comparingDouble(HudTarget::distanceSq));
		int drawn = 0;
		for (HudTarget target : targets) {
			if (drawn++ >= MAX_HUD_TARGETS) break;
			drawHudTarget(context, mc, camera, target);
		}
	}

	private List<Entity> collectTargets(MinecraftClient mc, int limit) {
		List<Entity> targets = new ArrayList<>();
		for (Entity entity : mc.world.getEntities()) {
			if (shouldRenderEntity(mc, entity)) {
				targets.add(entity);
			}
		}
		targets.sort(Comparator.comparingDouble(entity -> mc.player.squaredDistanceTo(entity)));
		if (targets.size() > limit) {
			return targets.subList(0, limit);
		}
		return targets;
	}

	private void drawHudTarget(DrawContext context, MinecraftClient mc, Vec3d camera, HudTarget target) {
		int width = context.getScaledWindowWidth();
		int height = context.getScaledWindowHeight();
		Vec3d projected = mc.gameRenderer.project(target.marker());

		double dx = target.marker().x - camera.x;
		double dz = target.marker().z - camera.z;
		double yaw = Math.toRadians(mc.gameRenderer.getCamera().getYaw());
		double forwardX = -Math.sin(yaw);
		double forwardZ = Math.cos(yaw);
		boolean inFront = dx * forwardX + dz * forwardZ > 0.1;
		boolean onScreen = inFront
				&& projected.x >= -1.0 && projected.x <= 1.0
				&& projected.y >= -1.0 && projected.y <= 1.0
				&& projected.z >= -1.0 && projected.z <= 1.0;

		double screenX;
		double screenY;
		if (onScreen) {
			screenX = (projected.x + 1.0) * 0.5 * width;
			screenY = (1.0 - projected.y) * 0.5 * height;
		} else {
			double relative = Math.atan2(dx, dz) - yaw;
			double radiusX = width * 0.5 - 42.0;
			double radiusY = height * 0.5 - 34.0;
			screenX = width * 0.5 + Math.sin(relative) * radiusX;
			screenY = height * 0.5 - Math.cos(relative) * radiusY;
		}

		int color = target.color().argb();
		String label = labelFor(target.entity());
		String range = Math.round(Math.sqrt(target.distanceSq())) + "m";
		String text = label + " " + range;
		drawTargetBadge(context, text, (int) Math.round(screenX), (int) Math.round(screenY), color);
	}

	private void drawTargetBadge(DrawContext context, String text, int centerX, int centerY, int color) {
		int width = context.getScaledWindowWidth();
		int height = context.getScaledWindowHeight();
		int textWidth = MinecraftClient.getInstance().textRenderer.getWidth(text);
		int boxW = Math.min(width - 8, textWidth + 14);
		int boxH = 17;
		int x = MathHelper.clamp(centerX - boxW / 2, 4, Math.max(4, width - boxW - 4));
		int y = MathHelper.clamp(centerY - boxH - 7, 4, Math.max(4, height - boxH - 4));

		context.fill(x, y, x + boxW, y + boxH, 0xB0000000);
		context.fill(x, y, x + 3, y + boxH, color);
		context.fill(x + 3, y, x + boxW, y + 1, color & 0x88FFFFFF);
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x + 7, y + 4, 0xFFFFFFFF);
	}

	private boolean shouldRenderEntity(MinecraftClient mc, Entity entity) {
		if (entity == mc.player || !(entity instanceof LivingEntity) || entity.isRemoved() || !entity.isAlive()) {
			return false;
		}
		if (!isEntityTypeEnabled(entity)) return false;
		double range = MathHelper.clamp(config.donutBasicEspRange, 32, 1024);
		return mc.player.squaredDistanceTo(entity) <= range * range;
	}

	private boolean isEntityTypeEnabled(Entity entity) {
		if (entity instanceof PlayerEntity) {
			return config.donutBasicShowPlayers;
		}
		SpawnGroup group = entity.getType().getSpawnGroup();
		if (group == SpawnGroup.MONSTER) {
			return config.donutBasicShowMonsters;
		}
		if (group == SpawnGroup.WATER_CREATURE
				|| group == SpawnGroup.WATER_AMBIENT
				|| group == SpawnGroup.UNDERGROUND_WATER_CREATURE
				|| group == SpawnGroup.AXOLOTLS) {
			return config.donutBasicShowAquatic;
		}
		return config.donutBasicShowPassive;
	}

	private String labelFor(Entity entity) {
		if (entity instanceof PlayerEntity) {
			return entity.getName().getString();
		}
		return entity.getType().getName().getString();
	}

	private EspColor colorFor(Entity entity) {
		if (entity instanceof PlayerEntity) {
			return new EspColor(1.00F, 0.22F, 0.34F, 0xFFFF3855);
		}

		SpawnGroup group = entity.getType().getSpawnGroup();
		if (group == SpawnGroup.MONSTER) {
			return new EspColor(1.00F, 0.18F, 0.12F, 0xFFFF2E1F);
		}
		if (group == SpawnGroup.WATER_CREATURE
				|| group == SpawnGroup.WATER_AMBIENT
				|| group == SpawnGroup.UNDERGROUND_WATER_CREATURE
				|| group == SpawnGroup.AXOLOTLS) {
			return new EspColor(0.17F, 0.82F, 1.00F, 0xFF2BD1FF);
		}
		if (group == SpawnGroup.AMBIENT) {
			return new EspColor(0.75F, 0.48F, 1.00F, 0xFFBF7AFF);
		}
		if (group == SpawnGroup.CREATURE) {
			return new EspColor(0.25F, 0.95F, 0.34F, 0xFF40F257);
		}
		return new EspColor(1.00F, 0.80F, 0.28F, 0xFFFFCC47);
	}

	private void drawLevelMarker(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d camera, double playerY, EspColor color) {
		double cx = (box.minX + box.maxX) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		double topY = box.maxY + 0.18;
		double markerY = markerY(box, playerY);
		line(buf, pos, entry, camera, cx, topY, cz, cx, markerY, cz, color, 0.58F);
		double wing = MathHelper.clamp(box.getLengthX() * 0.95, 0.35, 0.9);
		line(buf, pos, entry, camera, cx - wing, markerY, cz, cx, markerY + 0.34, cz, color, 0.72F);
		line(buf, pos, entry, camera, cx + wing, markerY, cz, cx, markerY + 0.34, cz, color, 0.72F);
		line(buf, pos, entry, camera, cx, markerY, cz - wing, cx, markerY + 0.34, cz, color, 0.72F);
		line(buf, pos, entry, camera, cx, markerY, cz + wing, cx, markerY + 0.34, cz, color, 0.72F);
	}

	private void drawWorldNameplate(WorldRenderContext ctx, MatrixStack matrices, Vec3d camera,
			Entity entity, Box box, double playerY, EspColor color) {
		MinecraftClient mc = MinecraftClient.getInstance();
		String label = labelFor(entity);
		int textWidth = mc.textRenderer.getWidth(label);
		double markerY = markerY(box, playerY) + 0.46;
		double x = (box.minX + box.maxX) * 0.5 - camera.x;
		double y = markerY - camera.y;
		double z = (box.minZ + box.maxZ) * 0.5 - camera.z;

		matrices.push();
		matrices.translate(x, y, z);
		matrices.multiply(mc.gameRenderer.getCamera().getRotation());
		float scale = entity instanceof PlayerEntity ? 0.038F : 0.029F;
		matrices.scale(-scale, -scale, scale);
		Matrix4f position = new Matrix4f(matrices.peek().getPositionMatrix());
		mc.textRenderer.draw(label, -textWidth * 0.5F, 0.0F, color.argb(), true, position,
				ctx.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, 0x7A000000, 0x00F000F0);
		matrices.pop();
	}

	private double markerY(Box box, double playerY) {
		return Math.max(box.maxY + 0.95, playerY + 2.25);
	}

	private void drawCenterCross(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d camera, EspColor color, float alpha) {
		double cx = (box.minX + box.maxX) * 0.5;
		double cy = (box.minY + box.maxY) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		line(buf, pos, entry, camera, box.minX, cy, cz, box.maxX, cy, cz, color, alpha);
		line(buf, pos, entry, camera, cx, cy, box.minZ, cx, cy, box.maxZ, color, alpha);
		line(buf, pos, entry, camera, cx, box.minY, cz, cx, box.maxY, cz, color, alpha);
	}

	private void drawBox(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d camera, EspColor color, float alpha) {
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
			double ax, double ay, double az, double bx, double by, double bz, EspColor color, float alpha) {
		float x0 = (float) (ax - camera.x);
		float y0 = (float) (ay - camera.y);
		float z0 = (float) (az - camera.z);
		float x1 = (float) (bx - camera.x);
		float y1 = (float) (by - camera.y);
		float z1 = (float) (bz - camera.z);
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0E-6F) return;
		float nx = dx / len, ny = dy / len, nz = dz / len;
		buf.vertex(pos, x0, y0, z0).color(color.r(), color.g(), color.b(), alpha).normal(entry, nx, ny, nz).lineWidth(1.0F);
		buf.vertex(pos, x1, y1, z1).color(color.r(), color.g(), color.b(), alpha).normal(entry, nx, ny, nz).lineWidth(1.0F);
	}

	private static RenderLayer createEspLines() {
		return RenderLayers.lines();
	}

	private record EspColor(float r, float g, float b, int argb) {
	}

	private record HudTarget(Entity entity, Vec3d marker, double distanceSq, EspColor color) {
	}
}
