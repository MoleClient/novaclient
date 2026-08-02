package com.profps.client.inventory;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

/** Shared semantic scoring used by every inventory automation module. */
public final class InventoryItemScorer {
	private InventoryItemScorer() {}

	public static EquipmentSlot equipmentSlot(ItemStack stack) {
		if (stack.isEmpty()) return null;
		EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
		if (equippable == null) return null;
		EquipmentSlot slot = equippable.slot();
		return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? slot : null;
	}

	public static double armorScore(ItemStack stack, EquipmentSlot slot, boolean durabilityTieBreak) {
		if (stack.isEmpty() || equipmentSlot(stack) != slot) return Double.NEGATIVE_INFINITY;
		AttributeModifiersComponent modifiers = stack.getOrDefault(
				DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
		double armor = modifiers.applyOperations(EntityAttributes.ARMOR, 0.0D, slot);
		double toughness = modifiers.applyOperations(EntityAttributes.ARMOR_TOUGHNESS, 0.0D, slot);
		if (armor <= 0.0D && toughness <= 0.0D) return Double.NEGATIVE_INFINITY;
		double score = armor * 100.0D + toughness * 12.0D;
		if (durabilityTieBreak && stack.isDamageable()) {
			double remaining = 1.0D - (double) stack.getDamage() / Math.max(1, stack.getMaxDamage());
			score += remaining;
		}
		return score;
	}

	public static double weaponScore(ItemStack stack) {
		if (stack.isEmpty()) return Double.NEGATIVE_INFINITY;
		AttributeModifiersComponent modifiers = stack.getOrDefault(
				DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
		return modifiers.applyOperations(EntityAttributes.ATTACK_DAMAGE, 1.0D, EquipmentSlot.MAINHAND);
	}

	public static boolean isHealing(ItemStack stack, boolean pots, boolean soup) {
		return (pots && isHealingSplashPotion(stack)) || (soup && isSoup(stack));
	}

	public static boolean isHealingSplashPotion(ItemStack stack) {
		if (!stack.isOf(Items.SPLASH_POTION)) return false;
		PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
		if (contents == null) return false;
		for (StatusEffectInstance effect : contents.getEffects()) {
			if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)
					|| effect.getEffectType().equals(StatusEffects.REGENERATION)) return true;
		}
		return false;
	}

	public static boolean isHarmfulSplashPotion(ItemStack stack) {
		if (!stack.isOf(Items.SPLASH_POTION)) return false;
		PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
		if (contents == null) return false;
		for (StatusEffectInstance effect : contents.getEffects()) {
			if (!effect.getEffectType().value().isBeneficial()) return true;
		}
		return false;
	}

	public static boolean isSoup(ItemStack stack) {
		return stack.isOf(Items.MUSHROOM_STEW) || stack.isOf(Items.RABBIT_STEW)
				|| stack.isOf(Items.BEETROOT_SOUP) || stack.isOf(Items.SUSPICIOUS_STEW);
	}

	public static boolean isTool(ItemStack stack) {
		String path = itemId(stack);
		return path.endsWith("_pickaxe") || path.endsWith("_axe") || path.endsWith("_shovel")
				|| path.endsWith("_hoe") || path.endsWith("shears") || path.endsWith("fishing_rod");
	}

	public static boolean isWeapon(ItemStack stack) {
		String path = itemId(stack);
		return path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("mace")
				|| path.endsWith("spear") || path.endsWith("trident");
	}

	public static boolean isBlock(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
	}

	public static boolean isFood(ItemStack stack) {
		return !stack.isEmpty() && stack.contains(DataComponentTypes.FOOD);
	}

	public static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString();
	}
}
