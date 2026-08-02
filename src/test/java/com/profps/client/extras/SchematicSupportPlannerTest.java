package com.profps.client.extras;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchematicSupportPlannerTest {
	@Test
	void unsupportedTargetGetsAContiguousAnchorToSupportChain() {
		TestSpace space = new TestSpace();
		space.anchors.add(new SchematicSupportPlanner.Cell(0, 0, 0));
		SchematicSupportPlanner.Cell target = new SchematicSupportPlanner.Cell(6, 2, 0);

		List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.plan(
				target, space, 8_192, 32, 16, 64);

		assertFalse(plan.isEmpty());
		assertTrue(plan.getFirst().manhattan(new SchematicSupportPlanner.Cell(0, 0, 0)) <= 1);
		assertTrue(plan.stream().anyMatch(cell -> cell.manhattan(target) == 1));
		for (int i = 1; i < plan.size(); i++) {
			SchematicSupportPlanner.Cell current = plan.get(i);
			assertTrue(plan.subList(0, i).stream().anyMatch(previous -> previous.manhattan(current) == 1),
					"every temporary placement must touch an earlier placement face");
		}
	}

	@Test
	void reservedSchematicCellsAreNeverUsedAsScaffolding() {
		TestSpace space = new TestSpace();
		space.anchors.add(new SchematicSupportPlanner.Cell(0, 0, 0));
		SchematicSupportPlanner.Cell reserved = new SchematicSupportPlanner.Cell(2, 0, 0);
		space.unavailable.add(reserved);

		List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.plan(
				new SchematicSupportPlanner.Cell(5, 0, 0), space, 4_096, 24, 8, 48);

		assertFalse(plan.isEmpty());
		assertFalse(plan.contains(reserved));
	}

	@Test
	void noAnchorFailsWithoutInventingFloatingPlacements() {
		TestSpace space = new TestSpace();

		List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.plan(
				new SchematicSupportPlanner.Cell(4, 20, 4), space, 1_024, 12, 10, 32);

		assertTrue(plan.isEmpty());
	}

	@Test
	void insufficientMaterialRejectsTheWholePlanBeforePlacement() {
		TestSpace space = new TestSpace();
		space.anchors.add(new SchematicSupportPlanner.Cell(0, 0, 0));

		List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.plan(
				new SchematicSupportPlanner.Cell(12, 4, 0), space, 8_192, 32, 16, 2);

		assertTrue(plan.isEmpty());
	}

	@Test
	void callerCanRequireAnOverheadSupportForHangingPlacements() {
		TestSpace space = new TestSpace();
		SchematicSupportPlanner.Cell target = new SchematicSupportPlanner.Cell(0, 5, 0);
		SchematicSupportPlanner.Cell overhead = new SchematicSupportPlanner.Cell(0, 6, 0);
		space.anchors.add(new SchematicSupportPlanner.Cell(1, 6, 0));

		List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.planFromSupports(
				target, List.of(overhead), space, 1_024, 12, 8, 16);

		assertFalse(plan.isEmpty());
		assertEquals(overhead, plan.getFirst());
	}

	@Test
	void tallFloatingTargetCanReachTerrainWithALongStaircase() {
		TestSpace space = new TestSpace();
		space.groundY = 0;
		SchematicSupportPlanner.Cell target = new SchematicSupportPlanner.Cell(0, 90, 0);
		SchematicSupportPlanner.Cell belowTarget = target.add(0, -1, 0);

		List<SchematicSupportPlanner.Cell> plan = SchematicSupportPlanner.planFromSupports(
				target, List.of(belowTarget), space, 32_768, 128, 96, 220);

		assertFalse(plan.isEmpty());
		assertEquals(1, plan.getFirst().y());
		assertTrue(plan.contains(belowTarget));
		assertTrue(plan.size() > 150, "the emitted route should include the legal staircase connectors");
		assertTrue(plan.stream().filter(cell -> cell.y() == belowTarget.y()
						&& cell.manhattan(belowTarget) <= 1).count() >= 3,
				"the top of a long route should include a usable staging platform");
	}

	private static final class TestSpace implements SchematicSupportPlanner.Space {
		final Set<SchematicSupportPlanner.Cell> anchors = new HashSet<>();
		final Set<SchematicSupportPlanner.Cell> unavailable = new HashSet<>();
		Integer groundY;

		@Override
		public boolean available(SchematicSupportPlanner.Cell cell) {
			return !unavailable.contains(cell);
		}

		@Override
		public boolean anchored(SchematicSupportPlanner.Cell cell) {
			return (groundY != null && cell.y() == groundY + 1)
					|| anchors.stream().anyMatch(anchor -> anchor.manhattan(cell) == 1);
		}
	}
}
