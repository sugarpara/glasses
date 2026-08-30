package com.example.glasses.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GroundFilterConfigTest {
    @Test
    fun defaultsFitOnlyOnLowerRegionAndClassifyFullFrame() {
        val config = GroundFilterConfig()

        assertEquals(0.45f, config.fitRoiTop, 0f)
        assertEquals(0f, config.classificationRoiTop, 0f)
        assertEquals(8, config.sampleStep)
    }

    @Test
    fun rejectsRoiOutsideImage() {
        listOf(-0.01f, 1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { roi ->
            assertThrows(IllegalArgumentException::class.java) {
                GroundFilterConfig(fitRoiTop = roi)
            }
            assertThrows(IllegalArgumentException::class.java) {
                GroundFilterConfig(classificationRoiTop = roi)
            }
        }
    }

    @Test
    fun rejectsNonPositiveOrNonFiniteDistances() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { distance ->
            assertThrows(IllegalArgumentException::class.java) {
                GroundFilterConfig(obstacleMaxDepthMeters = distance)
            }
            assertThrows(IllegalArgumentException::class.java) {
                GroundFilterConfig(fitMaxDepthMeters = distance)
            }
        }
    }

    @Test
    fun rejectsFitDistanceBelowObstacleDistance() {
        assertThrows(IllegalArgumentException::class.java) {
            GroundFilterConfig(obstacleMaxDepthMeters = 6f, fitMaxDepthMeters = 5f)
        }
    }

    @Test
    fun rejectsNonPositiveSamplingAndIterationCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            GroundFilterConfig(sampleStep = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroundFilterConfig(maxIterations = -1)
        }
    }
}
