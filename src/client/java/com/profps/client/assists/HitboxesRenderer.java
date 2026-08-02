package com.profps.client.assists;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import com.profps.client.mixin.RenderLayerInvoker;
import com.profps.client.mixin.RenderPipelinesInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Player hitbox overlay rendered with dedicated no-depth pipelines. Both the
 * translucent faces and outline remain visible through blocks and entities.
 */
public final class HitboxesRenderer {
	private static final RenderLayer THROUGH_WALL_LINES = createLineLayer();
	private static final RenderLayer THROUGH_WALL_FILLS = createFillLayer();

	private final ProFPSConfig config;
	// WorldRenderContext.matrices() is nullable; coordinates are camera-relative,
	// so an identity stack is a safe fallback for this simple overlay.
	private final MatrixStack identity = new MatrixStack();
	private boolean renderFailed;

	public HitboxesRenderer(ProFPSConfig config) {
		this.config = config;
	}

	public void render(WorldRenderContext ctx) {
		if (!config.enabled || !config.hitboxes) {
			renderFailed = false;
			return;
		}
		if (renderFailed) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;

		var consumers = ctx.consumers();
		if (consumers == null) return;

		try {
			Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
			MatrixStack matrices = ctx.matrices();
			MatrixStack.Entry entry = (matrices != null ? matrices : identity).peek();
			Matrix4fc position = entry.getPositionMatrix();

			float red = MathHelper.clamp(config.hitboxRed, 0, 255) / 255.0F;
			float green = MathHelper.clamp(config.hitboxGreen, 0, 255) / 255.0F;
			float blue = MathHelper.clamp(config.hitboxBlue, 0, 255) / 255.0F;
			float outlineAlpha = MathHelper.clamp(config.hitboxOutlineOpacity, 10, 100) / 100.0F;
			float fillAlpha = MathHelper.clamp(config.hitboxFillOpacity, 0, 80) / 100.0F;
			float lineWidth = MathHelper.clamp(config.hitboxLineWidth, 1, 5);

			float tickProgress = mc.getRenderTickCounter().getTickProgress(false);
			List<Box> visible = new ArrayList<>();

			for (PlayerEntity player : mc.world.getPlayers()) {
				if (player == mc.player || !player.isAlive() || player.isSpectator()) continue;

				// Offset the authoritative hitbox to the entity's interpolated render
				// position so fast-moving players do not leave a one-tick-jittering box.
				Vec3d renderPos = player.getLerpedPos(tickProgress);
				Box box = player.getBoundingBox().offset(
						renderPos.x - player.getX(),
						renderPos.y - player.getY(),
						renderPos.z - player.getZ()).expand(0.025D);
				visible.add(box);
			}

			// Request and finish one layer at a time. getBuffer(lines) flushes the
			// fill buffer on Minecraft's immediate provider.
			if (fillAlpha > 0.0F) {
				VertexConsumer fills = consumers.getBuffer(THROUGH_WALL_FILLS);
				for (Box box : visible) {
					drawFaces(fills, position, box, camera, red, green, blue, fillAlpha);
				}
			}
			VertexConsumer lines = consumers.getBuffer(THROUGH_WALL_LINES);
			for (Box box : visible) {
				drawEdges(lines, position, entry, box, camera, red, green, blue, outlineAlpha, lineWidth);
			}
		} catch (RuntimeException exception) {
			renderFailed = true;
			ProFPS.LOGGER.error("Hitboxes render failed; disabling its overlay for this session.", exception);
		}
	}

	private void drawFaces(VertexConsumer buffer, Matrix4fc position, Box box, Vec3d camera,
			float red, float green, float blue, float alpha) {
		float x0 = (float) (box.minX - camera.x);
		float y0 = (float) (box.minY - camera.y);
		float z0 = (float) (box.minZ - camera.z);
		float x1 = (float) (box.maxX - camera.x);
		float y1 = (float) (box.maxY - camera.y);
		float z1 = (float) (box.maxZ - camera.z);

		quad(buffer, position, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, red, green, blue, alpha);
		quad(buffer, position, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, red, green, blue, alpha);
		quad(buffer, position, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, red, green, blue, alpha);
		quad(buffer, position, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, red, green, blue, alpha);
		quad(buffer, position, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, red, green, blue, alpha);
		quad(buffer, position, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, red, green, blue, alpha);
	}

	private void drawEdges(VertexConsumer buffer, Matrix4fc position, MatrixStack.Entry entry,
			Box box, Vec3d camera, float red, float green, float blue, float alpha, float width) {
		float x0 = (float) (box.minX - camera.x);
		float y0 = (float) (box.minY - camera.y);
		float z0 = (float) (box.minZ - camera.z);
		float x1 = (float) (box.maxX - camera.x);
		float y1 = (float) (box.maxY - camera.y);
		float z1 = (float) (box.maxZ - camera.z);

		line(buffer, position, entry, x0, y0, z0, x1, y0, z0, red, green, blue, alpha, width);
		line(buffer, position, entry, x1, y0, z0, x1, y0, z1, red, green, blue, alpha, width);
		line(buffer, position, entry, x1, y0, z1, x0, y0, z1, red, green, blue, alpha, width);
		line(buffer, position, entry, x0, y0, z1, x0, y0, z0, red, green, blue, alpha, width);
		line(buffer, position, entry, x0, y1, z0, x1, y1, z0, red, green, blue, alpha, width);
		line(buffer, position, entry, x1, y1, z0, x1, y1, z1, red, green, blue, alpha, width);
		line(buffer, position, entry, x1, y1, z1, x0, y1, z1, red, green, blue, alpha, width);
		line(buffer, position, entry, x0, y1, z1, x0, y1, z0, red, green, blue, alpha, width);
		line(buffer, position, entry, x0, y0, z0, x0, y1, z0, red, green, blue, alpha, width);
		line(buffer, position, entry, x1, y0, z0, x1, y1, z0, red, green, blue, alpha, width);
		line(buffer, position, entry, x1, y0, z1, x1, y1, z1, red, green, blue, alpha, width);
		line(buffer, position, entry, x0, y0, z1, x0, y1, z1, red, green, blue, alpha, width);
	}

	private void line(VertexConsumer buffer, Matrix4fc position, MatrixStack.Entry entry,
			float ax, float ay, float az, float bx, float by, float bz,
			float red, float green, float blue, float alpha, float width) {
		float dx = bx - ax;
		float dy = by - ay;
		float dz = bz - az;
		float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0E-6F) return;
		float nx = dx / length;
		float ny = dy / length;
		float nz = dz / length;
		buffer.vertex(position, ax, ay, az).color(red, green, blue, alpha).normal(entry, nx, ny, nz).lineWidth(width);
		buffer.vertex(position, bx, by, bz).color(red, green, blue, alpha).normal(entry, nx, ny, nz).lineWidth(width);
	}

	private void quad(VertexConsumer buffer, Matrix4fc position,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz,
			float red, float green, float blue, float alpha) {
		buffer.vertex(position, ax, ay, az).color(red, green, blue, alpha);
		buffer.vertex(position, bx, by, bz).color(red, green, blue, alpha);
		buffer.vertex(position, cx, cy, cz).color(red, green, blue, alpha);
		buffer.vertex(position, dx, dy, dz).color(red, green, blue, alpha);
	}

	private static RenderLayer createLineLayer() {
		RenderPipeline pipeline = RenderPipelinesInvoker.profps$register(RenderPipeline.builder()
				.withLocation(Identifier.of(ProFPS.MOD_ID, "pipeline/hitboxes_lines"))
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withUniform("Fog", UniformType.UNIFORM_BUFFER)
				.withUniform("Globals", UniformType.UNIFORM_BUFFER)
				.withVertexShader("core/rendertype_lines")
				.withFragmentShader("core/rendertype_lines")
				.withBlend(BlendFunction.TRANSLUCENT)
				.withCull(false)
				.withDepthWrite(false)
				.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
				.withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.DrawMode.LINES)
				.build());
		return RenderLayerInvoker.profps$of("profps_hitboxes_lines",
				RenderSetup.builder(pipeline).translucent()
						.expectedBufferSize(VertexFormats.POSITION_COLOR_NORMAL_LINE_WIDTH.getVertexSize() * 2048)
						.build());
	}

	private static RenderLayer createFillLayer() {
		RenderPipeline pipeline = RenderPipelinesInvoker.profps$register(RenderPipeline.builder()
				.withLocation(Identifier.of(ProFPS.MOD_ID, "pipeline/hitboxes_fills"))
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
		return RenderLayerInvoker.profps$of("profps_hitboxes_fills",
				RenderSetup.builder(pipeline).translucent()
						.expectedBufferSize(VertexFormats.POSITION_COLOR.getVertexSize() * 2048)
						.build());
	}
}
