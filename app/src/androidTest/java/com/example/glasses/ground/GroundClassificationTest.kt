package com.example.glasses.ground

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.glasses.depth.MetricDepthFrame
import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroundClassificationTest {
    @Test
    fun detectsObstacleInTopTwentyPercentWhileFittingOnlyLowerRoi() {
        val depth = makeGroundDepth()
        fillRect(depth, top = 6, bottom = 22, left = 58, right = 102, value = 0.8f)

        val result = process(depth)

        assertTrue(result.fitSucceeded)
        assertTrue(classFraction(result.classMap, 6, 22, 58, 102, GROUND_CLASS_OBSTACLE) > 0.95)
        assertTrue(result.metrics[NATIVE_GROUND_FILTER_OBSTACLE_FRACTION_INDEX] > 0.0)
    }

    @Test
    fun invalidSkyDoesNotProduceObstacle() {
        val depth = makeGroundDepth()
        fillRect(depth, top = 0, bottom = 30, left = 0, right = WIDTH, value = Float.NaN)

        val result = process(depth)

        assertTrue(result.fitSucceeded)
        assertEquals(1.0, classFraction(result.classMap, 0, 30, 0, WIDTH, GROUND_CLASS_INVALID), 0.0)
        assertEquals(0.0, classFraction(result.classMap, 0, 30, 0, WIDTH, GROUND_CLASS_OBSTACLE), 0.0)
    }

    @Test
    fun verticalWallUsesDepthOnlyFallbackWhenGroundFitFails() {
        val result = process(FloatArray(WIDTH * HEIGHT) { 2f })

        assertFalse(result.fitSucceeded)
        assertEquals(0.0, classFraction(result.classMap, 0, HEIGHT, 0, WIDTH, GROUND_CLASS_GROUND), 0.0)
        assertEquals(1.0, classFraction(result.classMap, 0, HEIGHT, 0, WIDTH, GROUND_CLASS_OBSTACLE), 0.0)
    }

    @Test
    fun fartherLowerPatchIsUnknownInsteadOfObstacle() {
        val depth = makeGroundDepth()
        for (row in 70 until 94) {
            for (column in 48 until 112) {
                val index = row * WIDTH + column
                depth[index] *= 1.45f
            }
        }

        val result = process(depth)

        assertTrue(result.fitSucceeded)
        assertTrue(classFraction(result.classMap, 70, 94, 48, 112, GROUND_CLASS_UNKNOWN) > 0.80)
        assertTrue(classFraction(result.classMap, 70, 94, 48, 112, GROUND_CLASS_OBSTACLE) < 0.05)
    }

    @Test
    fun failedFitKeepsNearPixelsAsDepthOnlyObstacles() {
        val depth = FloatArray(WIDTH * HEIGHT) { Float.NaN }
        fillRect(depth, top = 100, bottom = HEIGHT, left = 70, right = 90, value = 2f)

        val result = process(depth)

        assertFalse(result.fitSucceeded)
        assertEquals(0.0, classFraction(result.classMap, 100, HEIGHT, 70, 90, GROUND_CLASS_UNKNOWN), 0.0)
        assertEquals(1.0, classFraction(result.classMap, 100, HEIGHT, 70, 90, GROUND_CLASS_OBSTACLE), 0.0)
    }

    @Test
    fun modelConsistentIslandWithoutBottomConnectionIsNotGround() {
        val plane = makeGroundDepth()
        val depth = FloatArray(WIDTH * HEIGHT) { Float.NaN }
        copyRect(plane, depth, top = 60, bottom = HEIGHT, left = 0, right = WIDTH)
        copyRect(plane, depth, top = 26, bottom = 48, left = 54, right = 106)

        val result = process(depth)

        assertTrue(result.fitSucceeded)
        assertTrue(classFraction(result.classMap, 64, HEIGHT, 4, WIDTH - 4, GROUND_CLASS_GROUND) > 0.95)
        assertEquals(0.0, classFraction(result.classMap, 28, 46, 56, 104, GROUND_CLASS_GROUND), 0.0)
        assertTrue(classFraction(result.classMap, 28, 46, 56, 104, GROUND_CLASS_OBSTACLE) > 0.95)
    }

    @Test
    fun distanceRangeUsesEnterExitHysteresisAndResetClearsIt() {
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val classMap = ByteArray(WIDTH * HEIGHT)
        val metrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT)
        val filter = NativeGroundFilter(
            GroundFilterConfig(
                fitRoiTop = 0.45f,
                classificationRoiTop = 0f,
                sampleStep = 2,
            ),
        )
        try {
            fun process(leftDepth: Float, rightDepth: Float? = null): Boolean {
                val values = makeGroundDepth()
                fillRect(values, top = 6, bottom = 22, left = 16, right = 48, value = leftDepth)
                rightDepth?.let { value ->
                    fillRect(values, top = 6, bottom = 22, left = 112, right = 144, value = value)
                }
                return filter.process(
                    MetricDepthFrame(values, WIDTH, HEIGHT, timestampMs = 1L),
                    occupancy,
                    distance,
                    classMap,
                    metrics,
                )
            }

            assertTrue(process(leftDepth = 2.9f))
            assertTrue(classFraction(classMap, 6, 22, 16, 48, GROUND_CLASS_OBSTACLE) > 0.65)
            assertTrue(distance.any { it in 2.8f..3.1f })

            assertTrue(process(leftDepth = 3.1f, rightDepth = 3.1f))
            assertTrue(classFraction(classMap, 6, 22, 16, 48, GROUND_CLASS_OBSTACLE) > 0.40)
            assertEquals(0.0, classFraction(classMap, 6, 22, 112, 144, GROUND_CLASS_OBSTACLE), 0.0)

            assertTrue(process(leftDepth = 3.4f))
            assertEquals(0.0, classFraction(classMap, 6, 22, 16, 48, GROUND_CLASS_OBSTACLE), 0.0)

            assertTrue(process(leftDepth = 2.9f))
            filter.reset()
            assertTrue(process(leftDepth = 3.1f))
            assertEquals(0.0, classFraction(classMap, 6, 22, 16, 48, GROUND_CLASS_OBSTACLE), 0.0)
        } finally {
            filter.close()
        }
    }

    @Test
    fun depthOnlyFallbackUsesEnterExitHysteresisAndResetClearsIt() {
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val classMap = ByteArray(WIDTH * HEIGHT)
        val metrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT)
        val filter = NativeGroundFilter(
            GroundFilterConfig(
                fitRoiTop = 0.45f,
                classificationRoiTop = 0f,
                sampleStep = 2,
            ),
        )
        try {
            fun process(value: Float): Boolean {
                val depth = FloatArray(WIDTH * HEIGHT) { Float.NaN }
                fillRect(depth, top = 100, bottom = HEIGHT, left = 70, right = 90, value = value)
                return filter.process(
                    MetricDepthFrame(depth, WIDTH, HEIGHT, timestampMs = 1L),
                    occupancy,
                    distance,
                    classMap,
                    metrics,
                )
            }

            assertFalse(process(3.1f))
            assertEquals(0.0, classFraction(classMap, 100, HEIGHT, 70, 90, GROUND_CLASS_OBSTACLE), 0.0)

            assertFalse(process(2.9f))
            assertEquals(1.0, classFraction(classMap, 100, HEIGHT, 70, 90, GROUND_CLASS_OBSTACLE), 0.0)

            assertFalse(process(3.1f))
            assertEquals(1.0, classFraction(classMap, 100, HEIGHT, 70, 90, GROUND_CLASS_OBSTACLE), 0.0)

            assertFalse(process(3.4f))
            assertEquals(0.0, classFraction(classMap, 100, HEIGHT, 70, 90, GROUND_CLASS_OBSTACLE), 0.0)

            assertFalse(process(2.9f))
            filter.reset()
            assertFalse(process(3.1f))
            assertEquals(0.0, classFraction(classMap, 100, HEIGHT, 70, 90, GROUND_CLASS_OBSTACLE), 0.0)
        } finally {
            filter.close()
        }
    }

    private fun process(depth: FloatArray): ClassificationResult {
        val frame = MetricDepthFrame(depth, WIDTH, HEIGHT, timestampMs = 1L)
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val classMap = ByteArray(depth.size)
        val metrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT)
        val fitSucceeded = NativeGroundFilter(
            GroundFilterConfig(
                fitRoiTop = 0.45f,
                classificationRoiTop = 0f,
                sampleStep = 2,
            ),
        ).use { filter ->
            filter.process(frame, occupancy, distance, classMap, metrics)
        }
        assertTrue(metrics[NATIVE_GROUND_FILTER_PROCESSING_MS_INDEX] >= 0.0)
        return ClassificationResult(fitSucceeded, classMap, metrics)
    }

    private fun makeGroundDepth(): FloatArray {
        return FloatArray(WIDTH * HEIGHT) { index ->
            val row = index / WIDTH
            val column = index % WIDTH
            val x = 2.0 * column / (WIDTH - 1) - 1.0
            val y = row.toDouble() / (HEIGHT - 1)
            (1.0 / (0.035 * x + 0.72 * y + 0.24)).toFloat()
        }
    }

    private fun fillRect(
        values: FloatArray,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        value: Float,
    ) {
        for (row in top until bottom) {
            for (column in left until right) values[row * WIDTH + column] = value
        }
    }

    private fun copyRect(
        source: FloatArray,
        destination: FloatArray,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
    ) {
        for (row in top until bottom) {
            for (column in left until right) {
                destination[row * WIDTH + column] = source[row * WIDTH + column]
            }
        }
    }

    private fun classFraction(
        classMap: ByteArray,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        classification: Byte,
    ): Double {
        var matches = 0
        var count = 0
        for (row in top until bottom) {
            for (column in left until right) {
                if (classMap[row * WIDTH + column] == classification) ++matches
                ++count
            }
        }
        return matches.toDouble() / count
    }

    private data class ClassificationResult(
        val fitSucceeded: Boolean,
        val classMap: ByteArray,
        val metrics: DoubleArray,
    )

    companion object {
        private const val WIDTH = 160
        private const val HEIGHT = 120
    }
}
