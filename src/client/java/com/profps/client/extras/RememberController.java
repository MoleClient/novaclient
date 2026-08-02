package com.profps.client.extras;

import com.profps.client.config.ProFPSConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers multiple connected builds as real-block translucent ghosts.
 *
 * <p>With Remember enabled, left-click any uncaptured connected build to add it.
 * Captures remain available in the current world even if the overlay is toggled
 * off, allowing Schematic Build to consume them independently. Look at any real
 * or ghost block belonging to a capture and press Delete to remove only that
 * capture. A world change clears coordinates so captures can never leak into a
 * different server or dimension instance.
 */
public final class RememberController {
	private static final int MAX_BLOCKS_PER_BUILD = 8192;
	private static final int MAX_TOTAL_BLOCKS = 32768;
	private static final int MAX_BUILDS = 64;
	private static final int MAX_RADIUS = 40;
	private static final int FLOOR_REACH = 7;
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float GHOST_ALPHA = 0.6F;
	private static final float GHOST_SCALE = 0.92F;
	private static final double DELETE_RAY_LENGTH = 5.0D;

	private final ProFPSConfig config;
	private final List<RememberedBuild> builds = new ArrayList<>();
	private ClientWorld capturedWorld;
	private boolean prevAttack;
	private boolean prevDelete;
	private int nextBuildId = 1;
	private long revision;

	public RememberController(ProFPSConfig config) {
		this.config = config;
	}

	public void tick(MinecraftClient client) {
		if (client.world != capturedWorld) {
			builds.clear();
			capturedWorld = client.world;
			nextBuildId = 1;
			revision++;
		}
		if (!config.enabled || !config.rememberEnabled) {
			prevAttack = false;
			prevDelete = false;
			return;
		}

		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.currentScreen != null) {
			prevAttack = false;
			prevDelete = false;
			return;
		}

		// macOS labels Backspace as "delete"; full keyboards may send the distinct
		// forward-Delete code. Accept both so the control matches the physical key.
		boolean delete = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_DELETE)
				|| InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_BACKSPACE);
		boolean deletePressed = delete && !prevDelete;
		prevDelete = delete;
		if (deletePressed) {
			RememberedBuild build = lookedAtBuild(client, player);
			if (build != null) {
				builds.remove(build);
				revision++;
				showAction(client, "Unsaved build " + build.id() + " (" + build.blocks().size() + " blocks)", Formatting.RED);
			} else {
				showAction(client, "No remembered build under crosshair", Formatting.GRAY);
			}
			return;
		}

		boolean attack = client.options.attackKey.isPressed();
		boolean clicked = attack && !prevAttack;
		prevAttack = attack;
		if (!clicked || builds.size() >= MAX_BUILDS || totalBlocks() >= MAX_TOTAL_BLOCKS) return;

		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos start = hit.getBlockPos().toImmutable();
		if (buildContaining(start) != null) return;

		Map<BlockPos, BlockState> captured = captureBuild(client.world, start);
		captured.keySet().removeIf(this::isAlreadyRemembered);
		int room = MAX_TOTAL_BLOCKS - totalBlocks();
		if (captured.isEmpty() || room <= 0) return;
		if (captured.size() > room) {
			Map<BlockPos, BlockState> trimmed = new HashMap<>();
			for (Map.Entry<BlockPos, BlockState> entry : captured.entrySet()) {
				if (trimmed.size() >= room) break;
				trimmed.put(entry.getKey(), entry.getValue());
			}
			captured = trimmed;
		}

		RememberedBuild build = new RememberedBuild(nextBuildId++, captured);
		builds.add(build);
		revision++;
		showAction(client, "Saved build " + build.id() + " (" + captured.size() + " blocks)", Formatting.GREEN);
	}

	/** The newest remembered block state at this world position, or {@code null}. */
	public BlockState desiredStateAt(BlockPos pos) {
		for (int i = builds.size() - 1; i >= 0; i--) {
			BlockState state = builds.get(i).blocks().get(pos);
			if (state != null) return state;
		}
		return null;
	}

	public boolean hasRememberedBuilds() {
		return !builds.isEmpty();
	}

	/** Immutable-coordinate snapshot consumed by the layer-by-layer auto builder. */
	public Map<BlockPos, BlockState> desiredStatesSnapshot() {
		Map<BlockPos, BlockState> snapshot = new HashMap<>();
		for (RememberedBuild build : builds) snapshot.putAll(build.blocks());
		return Map.copyOf(snapshot);
	}

	/** Changes whenever the captured source set or world changes. */
	public long revision() {
		return revision;
	}

	private Map<BlockPos, BlockState> captureBuild(ClientWorld world, BlockPos start) {
		Map<BlockPos, BlockState> captured = new HashMap<>();
		if (world.getBlockState(start).isAir()) return captured;

		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		visited.add(start);

		while (!queue.isEmpty() && captured.size() < MAX_BLOCKS_PER_BUILD) {
			BlockPos pos = queue.poll();
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || isPlatform(world, pos)) continue;
			captured.put(pos.toImmutable(), state);

			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						BlockPos next = pos.add(dx, dy, dz);
						if (Math.abs(next.getX() - start.getX()) > MAX_RADIUS
								|| Math.abs(next.getY() - start.getY()) > MAX_RADIUS
								|| Math.abs(next.getZ() - start.getZ()) > MAX_RADIUS) continue;
						BlockPos immutable = next.toImmutable();
						if (visited.add(immutable) && !world.getBlockState(immutable).isAir()) queue.add(immutable);
					}
				}
			}
		}
		return captured;
	}

	private RememberedBuild lookedAtBuild(MinecraftClient client, ClientPlayerEntity player) {
		if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			RememberedBuild direct = buildContaining(hit.getBlockPos());
			if (direct != null) return direct;
		}

		// Ghost blocks are not part of the real world's raycast. Ray-test their unit
		// boxes only on the Delete edge so a fully removed build can still be unsaved.
		Vec3d start = player.getEyePos();
		Vec3d end = start.add(player.getRotationVec(1.0F).multiply(DELETE_RAY_LENGTH));
		RememberedBuild nearest = null;
		double nearestSq = Double.MAX_VALUE;
		for (RememberedBuild build : builds) {
			for (BlockPos pos : build.blocks().keySet()) {
				var hit = new Box(pos).raycast(start, end);
				if (hit.isEmpty()) continue;
				double distanceSq = start.squaredDistanceTo(hit.get());
				if (distanceSq < nearestSq) {
					nearestSq = distanceSq;
					nearest = build;
				}
			}
		}
		return nearest;
	}

	private RememberedBuild buildContaining(BlockPos pos) {
		for (int i = builds.size() - 1; i >= 0; i--) {
			RememberedBuild build = builds.get(i);
			if (build.blocks().containsKey(pos)) return build;
		}
		return null;
	}

	private boolean isAlreadyRemembered(BlockPos pos) {
		return buildContaining(pos) != null;
	}

	private int totalBlocks() {
		int total = 0;
		for (RememberedBuild build : builds) total += build.blocks().size();
		return total;
	}

	private boolean isPlatform(ClientWorld world, BlockPos pos) {
		return solidRun(world, pos, 1, 0) && solidRun(world, pos, -1, 0)
				&& solidRun(world, pos, 0, 1) && solidRun(world, pos, 0, -1);
	}

	private boolean solidRun(ClientWorld world, BlockPos pos, int sx, int sz) {
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int step = 2; step <= FLOOR_REACH; step++) {
			mutable.set(pos.getX() + sx * step, pos.getY(), pos.getZ() + sz * step);
			if (world.getBlockState(mutable).isAir()) return false;
		}
		return true;
	}

	private void showAction(MinecraftClient client, String message, Formatting color) {
		client.inGameHud.setOverlayMessage(Text.literal("Remember ").formatted(Formatting.YELLOW, Formatting.BOLD)
				.append(Text.literal("• " + message).formatted(color)), false);
	}

	public void render(WorldRenderContext ctx) {
		if (!config.enabled || !config.rememberEnabled || builds.isEmpty()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) return;
		try {
			Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
			MatrixStack matrices = ctx.matrices();
			if (matrices == null) return;

			BlockRenderManager blockRenderer = client.getBlockRenderManager();
			AlphaGhostConsumer ghost = new AlphaGhostConsumer(
					ctx.consumers().getBuffer(TexturedRenderLayers.getBlockTranslucentCull()), GHOST_ALPHA);
			for (RememberedBuild build : builds) {
				for (Map.Entry<BlockPos, BlockState> entry : build.blocks().entrySet()) {
					BlockPos pos = entry.getKey();
					BlockStateModel model = blockRenderer.getModel(entry.getValue());
					matrices.push();
					matrices.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
					matrices.translate(0.5, 0.5, 0.5);
					matrices.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
					matrices.translate(-0.5, -0.5, -0.5);
					BlockModelRenderer.render(matrices.peek(), ghost, model, 1.0F, 1.0F, 1.0F,
							FULL_BRIGHT, OverlayTexture.DEFAULT_UV);
					matrices.pop();
				}
			}
		} catch (RuntimeException ignored) {
			// A ghost render failure must never take down the client.
		}
	}

	private record RememberedBuild(int id, Map<BlockPos, BlockState> blocks) {}

	private static final class AlphaGhostConsumer implements VertexConsumer {
		private final VertexConsumer delegate;
		private final int alpha;

		private AlphaGhostConsumer(VertexConsumer delegate, float alpha) {
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
