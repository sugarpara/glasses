package com.example.glasses.obstacle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ObstacleGridFrameTest {
    @Test
    fun exposesSharedGridDimensions() {
        assertEquals(64, OBSTACLE_GRID_ROWS)
        assertEquals(64, OBSTACLE_GRID_COLUMNS)
        assertEquals(4_096, OBSTACLE_GRID_CELL_COUNT)
    }

    @Test
    fun acceptsBoundaryOccupancyValues() {
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        occupancy[0] = 0f
        occupancy[occupancy.lastIndex] = 1f
        val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        distance[distance.lastIndex] = OBSTACLE_EXIT_DISTANCE_METERS

        val frame = ObstacleGridFrame(
            occupancy = occupancy,
            distanceMeters = distance,
            timestampMs = 10L,
            fitSucceeded = true,
        )

        assertEquals(occupancy, frame.occupancy)
    }

    @Test
    fun rejectsWrongOccupancyLength() {
        assertThrows(IllegalArgumentException::class.java) {
            ObstacleGridFrame(
                occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT + 1),
                distanceMeters = FloatArray(OBSTACLE_GRID_CELL_COUNT),
                timestampMs = 0L,
                fitSucceeded = true,
            )
        }
    }

    @Test
    fun rejectsNonFiniteOrOutOfRangeOccupancy() {
        listOf(-0.01f, 1.01f, Float.NaN, Float.NEGATIVE_INFINITY).forEach { value ->
            val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
            occupancy[1] = value
            assertThrows(IllegalArgumentException::class.java) {
                ObstacleGridFrame(
                    occupancy,
                    FloatArray(OBSTACLE_GRID_CELL_COUNT),
                    timestampMs = 0L,
                    fitSucceeded = false,
                )
            }
        }
    }

    @Test
    fun rejectsInvalidDistanceGrid() {
        assertThrows(IllegalArgumentException::class.java) {
            ObstacleGridFrame(
                occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT),
                distanceMeters = FloatArray(OBSTACLE_GRID_CELL_COUNT - 1),
                timestampMs = 0L,
                fitSucceeded = true,
            )
        }

        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        occupancy[0] = 0.5f
        assertThrows(IllegalArgumentException::class.java) {
            ObstacleGridFrame(
                occupancy = occupancy,
                distanceMeters = FloatArray(OBSTACLE_GRID_CELL_COUNT),
                timestampMs = 0L,
                fitSucceeded = true,
            )
        }
        val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        distance[0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            ObstacleGridFrame(
                occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT),
                distanceMeters = distance,
                timestampMs = 0L,
                fitSucceeded = true,
            )
        }
    }

    @Test
    fun rejectsNegativeTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            ObstacleGridFrame(
                occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT),
                distanceMeters = FloatArray(OBSTACLE_GRID_CELL_COUNT),
                timestampMs = -1L,
                fitSucceeded = true,
            )
        }
    }
}
