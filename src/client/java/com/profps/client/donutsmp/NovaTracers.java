package com.profps.client.donutsmp;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4fc;

/**
 * Shared tracer geometry for the DonutSMP overlays. Lines run from a point on the camera
 * forward axis (which projects to the crosshair) out toward the target's screen direction.
 */
final class NovaTracers {
	/** Distance of the shared origin point along the camera forward axis. */
	private static final double ORIGIN_DISTANCE = 150.0;
	/** Every tracer end sits exactly this far from the camera. */
	private static final double END_DISTANCE = 24.0;
	/** Minimum projected offset for a target behind the camera plane. */
	private static final double MIN_BEHIND_SPREAD = 2.75;
	static final float LINE_WIDTH = 2.0F;

	private NovaTracers() {}

	/** Per-frame camera basis; build once, draw many. */
	record Basis(Vec3d cameraPos, Vec3d forward, Vec3d right, Vec3d up, Vec3d origin) {}

	static Basis basisFor(Camera camera) {
		float pitchRad = (float) Math.toRadians(camera.getPitch());
		float yawRad = (float) Math.toRadians(camera.getYaw());
		Vec3d forward = new Vec3d(0.0, 0.0, 1.0).rotateX(-pitchRad).rotateY(-yawRad).normalize();
		Vec3d right = new Vec3d(1.0, 0.0, 0.0).rotateY(-yawRad).normalize();
		Vec3d up = forward.crossProduct(right).normalize();
		return new Basis(camera.getCameraPos(), forward, right, up, forward.multiply(ORIGIN_DISTANCE));
	}

	/** Draws one tracer toward a world position; {@code buf} must be {@link DonutWorldRenderer#LINES}. */
	static void draw(VertexConsumer buf, Matrix4fc pos, MatrixStack.Entry entry, Basis basis,
			Vec3d target, int color, float alpha) {
		Vec3d end = endFor(basis, target);
		// Endpoints are already camera-relative, so pass a zero camera position.
		DonutWorldRenderer.drawLine(buf, pos, entry, Vec3d.ZERO,
				basis.origin().x, basis.origin().y, basis.origin().z,
				end.x, end.y, end.z, color, alpha, LINE_WIDTH);
	}

	/** Camera-relative end point: END_DISTANCE out, in the direction that projects onto the target. */
	private static Vec3d endFor(Basis basis, Vec3d target) {
		double dx = target.x - basis.cameraPos().x;
		double dy = target.y - basis.cameraPos().y;
		double dz = target.z - basis.cameraPos().z;

		double alongRight = dx * basis.right().x + dy * basis.right().y + dz * basis.right().z;
		double alongUp = dx * basis.up().x + dy * basis.up().y + dz * basis.up().z;
		double alongForward = dx * basis.forward().x + dy * basis.forward().y + dz * basis.forward().z;

		// Lateral offsets over forward depth give screen-space proportions; the floor avoids blow-up.
		double depth = Math.max(Math.abs(alongForward), 0.25);
		double spreadRight = alongRight / depth;
		double spreadUp = alongUp / depth;

		if (alongForward <= 0.0) {
			double offset = Math.hypot(spreadRight, spreadUp);
			if (offset < MIN_BEHIND_SPREAD) {
				if (offset < 1.0E-4) {
					// Directly behind: no direction to preserve, pick screen-right.
					spreadRight = MIN_BEHIND_SPREAD;
					spreadUp = 0.0;
				} else {
					double scale = MIN_BEHIND_SPREAD / offset;
					spreadRight *= scale;
					spreadUp *= scale;
				}
			}
		}

		return basis.forward()
				.add(basis.right().multiply(spreadRight))
				.add(basis.up().multiply(spreadUp))
				.normalize()
				.multiply(END_DISTANCE);
	}
}
