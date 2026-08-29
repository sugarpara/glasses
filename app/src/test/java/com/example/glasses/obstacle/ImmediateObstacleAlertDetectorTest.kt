package com.example.glasses.obstacle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateObstacleAlertDetectorTest {

    @Test
    fun processedOccupancyFrameIsConsumedDirectly() {
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        occupancy[40 * OBSTACLE_GRID_COLUMNS + 12] = 0.9f
        val processed = ObstacleGridTransform().process(
            ObstacleGridFrame(
                occupancy = occupancy,
                timestampMs = 1_234L,
                fitSucceeded = true,
            ),
        )

        val alert = ImmediateObstacleAlertDetector(cooldownMs = 0L).detect(
            frame = processed,
            nowMs = 2_000L,
        )

        assertEquals(1_234L, alert?.inputTimestampMs)
        assertEquals(1, alert?.activeObstacleCount)
        assertEquals(40, alert?.targets?.single()?.row)
        assertEquals(12, alert?.targets?.single()?.column)
    }

    @Test
    fun existingObstacleDoesNotProduceRepeatedAlerts() {
        val detector = ImmediateObstacleAlertDetector(cooldownMs = 180L)
        val active = BooleanArray(OBSTACLE_GRID_CELL_COUNT)
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        setBlock(active, occupancy, 40..44, 28..32)

        val first = detector.detect(active, occupancy, 1_000L, 100L, 1_000L)
        val repeated = detector.detect(active, occupancy, 1_100L, 200L, 1_200L)

        assertEquals(1, first?.targets?.size)
        assertNull(repeated)
    }

    @Test
    fun newDisconnectedObstacleAlertsAfterCooldownWithoutAQueue() {
        val detector = ImmediateObstacleAlertDetector(cooldownMs = 180L)
        val firstActive = BooleanArray(OBSTACLE_GRID_CELL_COUNT)
        val firstOccupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        setBlock(firstActive, firstOccupancy, 20..22, 8..10)
        detector.detect(firstActive, firstOccupancy, 1_000L, 100L, 1_000L)

        val secondActive = firstActive.copyOf()
        val secondOccupancy = firstOccupancy.copyOf()
        setBlock(secondActive, secondOccupancy, 50..54, 48..52)

        assertNull(detector.detect(secondActive, secondOccupancy, 1_100L, 200L, 1_100L))
        val alert = detector.detect(secondActive, secondOccupancy, 1_200L, 300L, 1_200L)

        assertEquals(1, alert?.targets?.size)
        assertTrue(alert!!.targets.single().row >= 50)
        assertTrue(alert.targets.single().column >= 48)
    }

    @Test
    fun expandingKnownObstacleDoesNotRetrigger() {
        val detector = ImmediateObstacleAlertDetector(cooldownMs = 0L)
        val active = BooleanArray(OBSTACLE_GRID_CELL_COUNT)
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        setBlock(active, occupancy, 30..32, 30..32)
        detector.detect(active, occupancy, 1_000L, null, 1_000L)

        setBlock(active, occupancy, 28..34, 28..34)

        assertNull(detector.detect(active, occupancy, 1_100L, null, 1_100L))
    }

    private fun setBlock(
        active: BooleanArray,
        occupancy: FloatArray,
        rows: IntRange,
        columns: IntRange
    ) {
        for (row in rows) {
            for (column in columns) {
                val index = row * OBSTACLE_GRID_COLUMNS + column
                active[index] = true
                occupancy[index] = 0.9f
            }
        }
    }
}
