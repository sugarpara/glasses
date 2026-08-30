package com.example.glasses.ground

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.glasses.depth.MetricDepthFrame
import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGroundFilterTest {
    @Test
    fun createsProcessesResetsAndClosesOneHundredTimes() {
        val frame = MetricDepthFrame(
            values = floatArrayOf(1f, 2f, 3f, 4f),
            width = 2,
            height = 2,
            timestampMs = 123L,
        )

        repeat(100) { iteration ->
            val filter = NativeGroundFilter(
                GroundFilterConfig(
                    fitRoiTop = 0.45f,
                    classificationRoiTop = 0f,
                ),
            )
            val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT) { 1f }
            val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT) { 1f }
            val classMap = ByteArray(frame.values.size) { 3 }
            val metrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT) { 1.0 }

            val fitSucceeded = filter.process(
                frame = frame,
                obstacleOccupancy = occupancy,
                obstacleDistanceMeters = distance,
                classMap = if (iteration % 2 == 0) classMap else null,
                metrics = metrics,
            )

            assertFalse(fitSucceeded)
            assertEquals(3, occupancy.count { it > 0f })
            assertEquals(3, distance.count { it > 0f })
            if (iteration % 2 == 0) {
                assertArrayEquals(
                    byteArrayOf(
                        GROUND_CLASS_OBSTACLE,
                        GROUND_CLASS_OBSTACLE,
                        GROUND_CLASS_OBSTACLE,
                        GROUND_CLASS_UNKNOWN,
                    ),
                    classMap,
                )
            }
            assertEquals(0.0, metrics[NATIVE_GROUND_FILTER_GROUND_FRACTION_INDEX], 0.0)
            assertEquals(0.75, metrics[NATIVE_GROUND_FILTER_OBSTACLE_FRACTION_INDEX], 0.0)
            assertEquals(0.25, metrics[NATIVE_GROUND_FILTER_UNKNOWN_FRACTION_INDEX], 0.0)
            assertTrue(metrics[NATIVE_GROUND_FILTER_PROCESSING_MS_INDEX] >= 0.0)

            filter.reset()
            filter.close()
            filter.close()
            assertThrows(IllegalStateException::class.java) {
                filter.process(frame, occupancy, distance, null, metrics)
            }
        }
    }

    @Test
    fun rejectsCallerBuffersWithWrongSizes() {
        val frame = MetricDepthFrame(
            values = floatArrayOf(1f),
            width = 1,
            height = 1,
            timestampMs = 0L,
        )

        NativeGroundFilter().use { filter ->
            assertThrows(IllegalArgumentException::class.java) {
                filter.process(
                    frame,
                    FloatArray(OBSTACLE_GRID_CELL_COUNT - 1),
                    FloatArray(OBSTACLE_GRID_CELL_COUNT),
                    null,
                    DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                filter.process(
                    frame,
                    FloatArray(OBSTACLE_GRID_CELL_COUNT),
                    FloatArray(OBSTACLE_GRID_CELL_COUNT - 1),
                    null,
                    DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                filter.process(
                    frame,
                    FloatArray(OBSTACLE_GRID_CELL_COUNT),
                    FloatArray(OBSTACLE_GRID_CELL_COUNT),
                    ByteArray(2),
                    DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                filter.process(
                    frame,
                    FloatArray(OBSTACLE_GRID_CELL_COUNT),
                    FloatArray(OBSTACLE_GRID_CELL_COUNT),
                    null,
                    DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT - 1),
                )
            }
        }
    }
}
