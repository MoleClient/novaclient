package com.profps.client.crystalpvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionDamageServiceTest {
	@Test
	void armorReductionMatchesTheRecoveredFormula() {
		assertEquals(20.0F, ExplosionDamageService.reduceByArmor(20.0F, 0.0F, 0.0F), 0.0001F);
		assertEquals(4.5F, ExplosionDamageService.reduceByArmor(5.0F, 5.0F, 0.0F), 0.0001F);
	}

	@Test
	void armorNeverIncreasesDamage() {
		for (int damage = 1; damage <= 100; damage++) {
			for (int armor = 0; armor <= 30; armor++) {
				float reduced = ExplosionDamageService.reduceByArmor(damage, armor, 12.0F);
				assertTrue(reduced >= 0.0F && reduced <= damage,
						"damage=" + damage + ", armor=" + armor + ", reduced=" + reduced);
			}
		}
	}

	@Test
	void modernCrystalMaximumIsStable() {
		assertEquals(97.0F, ExplosionDamageService.maximumRawCrystalDamage(), 0.0001F);
	}
}
