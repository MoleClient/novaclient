package com.profps.client.crystalpvp;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/** Vape-compatible explosion exposure and reduction model, isolated for crystals, anchors and beds. */
public final class ExplosionDamageService {
	private static final float CRYSTAL_POWER = 6.0F;

	private ExplosionDamageService() {}

	public static float crystalDamage(World world, LivingEntity entity, Vec3d explosion) {
		return crystalDamage(world, entity, explosion, entity.getBoundingBox());
	}

	public static float anchorDamage(World world, LivingEntity entity, Vec3d explosion) {
		float raw = rawDamage(world, entity.getBoundingBox(), explosion, 5.0F);
		if (raw <= 0.0F) return 0.0F;
		float reduced = reduceByArmor(raw, entity.getArmor(),
				(float) entity.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS));
		var resistance = entity.getStatusEffect(StatusEffects.RESISTANCE);
		if (resistance != null) reduced *= Math.max(0, 25 - (resistance.getAmplifier() + 1) * 5) / 25.0F;
		return Math.max(0.0F, reduced);
	}

	public static float crystalDamage(World world, LivingEntity entity, Vec3d explosion, Box predictedBox) {
		float raw = rawDamage(world, predictedBox, explosion, CRYSTAL_POWER);
		if (raw <= 0.0F) return 0.0F;
		float reduced = reduceByArmor(raw, entity.getArmor(),
				(float) entity.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS));
		var resistance = entity.getStatusEffect(StatusEffects.RESISTANCE);
		if (resistance != null) {
			int remaining = Math.max(0, 25 - (resistance.getAmplifier() + 1) * 5);
			reduced *= remaining / 25.0F;
		}
		int protection = 0;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			var stack = entity.getEquippedStack(slot);
			if (stack.isEmpty()) continue;
			var enchantments = EnchantmentHelper.getEnchantments(stack);
			for (var enchantment : enchantments.getEnchantments()) {
				int level = enchantments.getLevel(enchantment);
				if (enchantment.matchesKey(Enchantments.PROTECTION)) protection += level;
				else if (enchantment.matchesKey(Enchantments.BLAST_PROTECTION)) protection += level * 2;
			}
		}
		protection = MathHelper.clamp(protection, 0, 20);
		return Math.max(0.0F, reduced * (1.0F - protection / 25.0F));
	}

	/** Raw modern-client formula used by the recovered implementation for its efficiency percentage. */
	public static float rawCrystalDamage(World world, Box box, Vec3d explosion) {
		return rawDamage(world, box, explosion, CRYSTAL_POWER);
	}

	public static float maximumRawCrystalDamage() {
		float diameter = CRYSTAL_POWER * 2.0F;
		return 8.0F * diameter + 1.0F;
	}

	private static float rawDamage(World world, Box box, Vec3d explosion, float power) {
		if (world == null || box == null) return 0.0F;
		float diameter = power * 2.0F;
		Vec3d center = box.getCenter();
		double normalizedDistance = center.distanceTo(explosion) / diameter;
		if (normalizedDistance > 1.0D) return 0.0F;
		float exposure = exposure(world, box, explosion);
		double impact = (1.0D - normalizedDistance) * exposure;
		return (float) ((impact * impact + impact) * 0.5D * 8.0D * diameter + 1.0D);
	}

	private static float exposure(World world, Box box, Vec3d explosion) {
		int clear = 0;
		int total = 0;
		// Same bounding-box density idea as Vape, with a bounded 3x5x3 sample grid so an aura
		// scan cannot turn one client tick into hundreds of thousands of raycasts.
		for (int xi = 0; xi < 3; xi++) {
			for (int yi = 0; yi < 5; yi++) {
				for (int zi = 0; zi < 3; zi++) {
					double x = MathHelper.lerp(xi / 2.0D, box.minX, box.maxX);
					double y = MathHelper.lerp(yi / 4.0D, box.minY, box.maxY);
					double z = MathHelper.lerp(zi / 2.0D, box.minZ, box.maxZ);
					HitResult hit = world.raycast(new RaycastContext(new Vec3d(x, y, z), explosion,
							RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
							net.minecraft.block.ShapeContext.absent()));
					if (hit.getType() == HitResult.Type.MISS) clear++;
					total++;
				}
			}
		}
		return total == 0 ? 0.0F : clear / (float) total;
	}

	static float reduceByArmor(float damage, float armor, float toughness) {
		float scale = 2.0F + toughness / 4.0F;
		float effectiveArmor = MathHelper.clamp(armor - damage / scale, armor * 0.2F, 20.0F);
		return damage * (1.0F - effectiveArmor / 25.0F);
	}
}
