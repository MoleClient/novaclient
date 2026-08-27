package com.profps.client.donutsmp;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.profps.ProFPS;
import com.profps.client.mixin.RenderLayerInvoker;
import com.profps.client.mixin.RenderPipelinesInvoker;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4fc;

final class DonutWorldRenderer {
	static final RenderLayer FILLS = createFillLayer();
	/** {@link RenderLayers#lines()} with the depth test off, so outlines show through terrain. */
	static final RenderLayer LINES = createLineLayer();

	private DonutWorldRenderer() {
	}

	static void drawFilledBox(VertexConsumer buf, Matrix4fc pos, Box box, Vec3d camera, int color, float alpha) {
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >> 8) & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;
		float x0 = (float) (box.minX - camera.x), y0 = (float) (box.minY - camera.y), z0 = (float) (box.minZ - camera.z);
		float x1 = (float) (box.maxX - camera.x), y1 = (float) (box.maxY - camera.y), z1 = (float) (box.maxZ - camera.z);
		quad(buf, pos, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b, alpha);
		quad(buf, pos, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, r, g, b, alpha);
		quad(buf, pos, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, r, g, b, alpha);
		quad(buf, pos, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, alpha);
		quad(buf, pos, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, alpha);
		quad(buf, pos, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, r, g, b, alpha);
	}

	/** Draws a single quad at the box's top face; tiles seamlessly across touching chunks. */
	static void drawFlatTop(VertexConsumer buf, Matrix4fc pos, Box box, Vec3d camera, int color, float alpha) {
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >> 8) & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;
		float x0 = (float) (box.minX - camera.x), y = (float) (box.maxY - camera.y), z0 = (float) (box.minZ - camera.z);
		float x1 = (float) (box.maxX - camera.x), z1 = (float) (box.maxZ - camera.z);
		quad(buf, pos, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, r, g, b, alpha);
	}

	/** Box outline in three passes of decreasing width and rising alpha, approximating antialiasing. */
	static void drawSoftOutline(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d camera, int color, float alpha) {
		drawOutline(buf, pos, entry, box, camera, color, alpha * 0.22F, 5.0F);
		drawOutline(buf, pos, entry, box, camera, color, alpha * 0.55F, 3.0F);
		drawOutline(buf, pos, entry, box, camera, color, alpha, 1.5F);
	}

	static void drawOutline(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d camera, int color, float alpha, float width) {
		line(buf, pos, entry, camera, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, color, alpha, width);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, color, alpha, width);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, color, alpha, width);
		line(buf, pos, entry, camera, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ, color, alpha, width);
		line(buf, pos, entry, camera, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, color, alpha, width);
		line(buf, pos, entry, camera, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, color, alpha, width);
		line(buf, pos, entry, camera, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color, alpha, width);
		line(buf, pos, entry, camera, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, color, alpha, width);
		line(buf, pos, entry, camera, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, color, alpha, width);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, color, alpha, width);
		line(buf, pos, entry, camera, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, color, alpha, width);
		line(buf, pos, entry, camera, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, color, alpha, width);
	}

	static void drawOutline(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry,
			Box box, Vec3d camera, int color, float alpha) {
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

	private static void quad(VertexConsumer buf, Matrix4fc pos,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz,
			float r, float g, float b, float alpha) {
		buf.vertex(pos, ax, ay, az).color(r, g, b, alpha);
		buf.vertex(pos, bx, by, bz).color(r, g, b, alpha);
		buf.vertex(pos, cx, cy, cz).color(r, g, b, alpha);
		buf.vertex(pos, dx, dy, dz).color(r, g, b, alpha);
	}

	private static void line(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry, Vec3d camera,
			double ax, double ay, double az, double bx, double by, double bz, int color, float alpha) {
		line(buf, pos, entry, camera, ax, ay, az, bx, by, bz, color, alpha, 1.0F);
	}

	private static void line(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry, Vec3d camera,
			double ax, double ay, double az, double bx, double by, double bz, int color, float alpha, float width) {
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >> 8) & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;
		float x0 = (float) (ax - camera.x);
		float y0 = (float) (ay - camera.y);
		float z0 = (float) (az - camera.z);
		float x1 = (float) (bx - camera.x);
		float y1 = (float) (by - camera.y);
		float z1 = (float) (bz - camera.z);
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0E-6F) return;
		buf.vertex(pos, x0, y0, z0).color(r, g, b, alpha).normal(entry, dx / len, dy / len, dz / len).lineWidth(width);
		buf.vertex(pos, x1, y1, z1).color(r, g, b, alpha).normal(entry, dx / len, dy / len, dz / len).lineWidth(width);
	}

	/** Straight world-space line, e.g. base-finder tracers. */
	static void drawLine(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry, Vec3d camera,
			double ax, double ay, double az, double bx, double by, double bz, int color, float alpha) {
		line(buf, pos, entry, camera, ax, ay, az, bx, by, bz, color, alpha, 1.0F);
	}

	/** Straight world-space line at a chosen pixel width. */
	static void drawLine(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry, Vec3d camera,
			double ax, double ay, double az, double bx, double by, double bz, int color, float alpha, float width) {
		line(buf, pos, entry, camera, ax, ay, az, bx, by, bz, color, alpha, width);
	}

	private static RenderLayer createLineLayer() {
		// Mirrors RenderPipelines.LINES but with NO_DEPTH_TEST and no depth write.
		RenderPipeline pipeline = RenderPipelinesInvoker.profps$register(RenderPipeline.builder()
				.withLocation(Identifier.of(ProFPS.MOD_ID, "pipeline/donut_detector_lines"))
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
		return RenderLayerInvoker.profps$of("profps_donut_detector_lines",
				RenderSetup.builder(pipeline).translucent()
						.expectedBufferSize(VertexFormats.POSITION_COLOR_NORMAL_LINE_WIDTH.getVertexSize() * 1024)
						.build());
	}

	private static RenderLayer createFillLayer() {
		RenderPipeline pipeline = RenderPipelinesInvoker.profps$register(RenderPipeline.builder()
				.withLocation(Identifier.of(ProFPS.MOD_ID, "pipeline/donut_detector_fills"))
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
		return RenderLayerInvoker.profps$of("profps_donut_detector_fills",
				RenderSetup.builder(pipeline).translucent()
						.expectedBufferSize(RenderPipelines.DEBUG_FILLED_BOX.getVertexFormat().getVertexSize() * 1024)
						.build());
	}
}
