package com.example.glasses.ground

import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GroundFilterFrameTest {
    @Test
    fun acceptsFrameWithoutDebugClassMap() {
        val frame = validFrame(classMap = null)

        assertNull(frame.classMap)
        assertEquals(OBSTACLE_GRID_CELL_COUNT, frame.obstacleOccupancy.size)
        assertEquals(OBSTACLE_GRID_CELL_COUNT, frame.obstacleDistanceMeters.size)
    }

    @Test
    fun acceptsAllClassificationCodes() {
        val frame = validFrame(classMap = byteArrayOf(0, 1, 2, 3), width = 2, height = 2)

        assertEquals(byteArrayOf(0, 1, 2, 3).toList(), frame.classMap!!.toList())
    }

    @Test
    fun rejectsWrongOccupancyLength() {
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(obstacleOccupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT - 1))
        }
    }

    @Test
    fun rejectsWrongOrInvalidDistanceGrid() {
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(obstacleDistanceMeters = FloatArray(OBSTACLE_GRID_CELL_COUNT - 1))
        }
        val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        distance[0] = -0.1f
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(obstacleDistanceMeters = distance)
        }
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        occupancy[0] = 0.5f
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(obstacleOccupancy = occupancy)
        }
    }

    @Test
    fun rejectsNonFiniteOrOutOfRangeOccupancy() {
        listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY).forEach { value ->
            val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
            occupancy[0] = value
            assertThrows(IllegalArgumentException::class.java) {
                validFrame(obstacleOccupancy = occupancy)
            }
        }
    }

    @Test
    fun rejectsClassMapWithWrongLengthOrUnknownCode() {
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(classMap = ByteArray(3), width = 2, height = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(classMap = byteArrayOf(0, 1, 2, 4), width = 2, height = 2)
        }
    }

    @Test
    fun rejectsInvalidDimensionsTimestampFractionsAndProcessingTime() {
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(width = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(timestampMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(groundFraction = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(obstacleFraction = 1.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(unknownFraction = -0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(processingMs = Double.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validFrame(processingMs = -0.01)
        }
    }

    private fun validFrame(
        classMap: ByteArray? = null,
        obstacleOccupancy: FloatArray = FloatArray(OBSTACLE_GRID_CELL_COUNT),
        obstacleDistanceMeters: FloatArray = FloatArray(OBSTACLE_GRID_CELL_COUNT),
        width: Int = 2,
        height: Int = 2,
        timestampMs: Long = 0L,
        groundFraction: Float = 0.5f,
        obstacleFraction: Float = 0.25f,
        unknownFraction: Float = 0.25f,
        processingMs: Double = 1.0,
    ) = GroundFilterFrame(
        classMap = classMap,
        obstacleOccupancy = obstacleOccupancy,
        obstacleDistanceMeters = obstacleDistanceMeters,
        width = width,
        height = height,
        timestampMs = timestampMs,
        fitSucceeded = true,
        groundFraction = groundFraction,
        obstacleFraction = obstacleFraction,
        unknownFraction = unknownFraction,
        processingMs = processingMs,
    )
}
