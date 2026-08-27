package com.profps.client.donutsmp;

import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Mob ESP target and colour selection; vanilla's entity-outline pass does the drawing.
 */
public final class BasicEspRenderer {
	private static final int HOSTILE = 0xFF3B30;
	private static final int PASSIVE = 0x3BE86A;
	private static final int PLAYER = 0xC77DFF;

	private static BasicEspRenderer instance;

	private final ProFPSConfig config;

	public BasicEspRenderer(ProFPSConfig config) {
		this.config = config;
		instance = this;
	}

	/** True when vanilla's outline pass should trace this entity for Mob ESP. */
	public static boolean shouldOutline(Entity entity) {
		BasicEspRenderer self = instance;
		if (self == null || entity == null) return false;
		if (!self.config.enabled || !self.config.donutBasicEsp) return false;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || entity == client.player) return false;
		if (!(entity instanceof LivingEntity) || entity.isRemoved() || !entity.isAlive()) return false;
		if (!self.isEnabledType(entity)) return false;
		double range = MathHelper.clamp(self.config.donutBasicEspRange, 32, 1024);
		return client.player.squaredDistanceTo(entity) <= range * range;
	}

	/** Outline colour for a target, as vanilla's packed team colour. */
	public static int outlineColor(Entity entity) {
		if (entity instanceof PlayerEntity) return PLAYER;
		return entity.getType().getSpawnGroup() == SpawnGroup.MONSTER ? HOSTILE : PASSIVE;
	}

	private boolean isEnabledType(Entity entity) {
		if (entity instanceof PlayerEntity) return config.donutBasicShowPlayers;
		SpawnGroup group = entity.getType().getSpawnGroup();
		if (group == SpawnGroup.MONSTER) return config.donutBasicShowMonsters;
		if (group == SpawnGroup.WATER_CREATURE || group == SpawnGroup.WATER_AMBIENT
				|| group == SpawnGroup.UNDERGROUND_WATER_CREATURE || group == SpawnGroup.AXOLOTLS) {
			return config.donutBasicShowAquatic;
		}
		return config.donutBasicShowPassive;
	}
}
