package com.example.glasses.ground

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.glasses.depth.MetricDepthFrame
import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import java.util.Random
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroundPlaneFitTest {
    @Test
    fun productionNativeContextUsesReusableMleFitter() {
        val depth = makeGroundDepth()
        val frame = MetricDepthFrame(depth, WIDTH, HEIGHT, timestampMs = 1L)
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val classMap = ByteArray(depth.size)
        val metrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT)

        NativeGroundFilter(
            GroundFilterConfig(fitRoiTop = 0.35f, sampleStep = 2),
        ).use { filter ->
            assertTrue(filter.process(frame, occupancy, classMap, metrics))
            filter.reset()
            assertTrue(filter.process(frame, occupancy, null, metrics))
        }

        assertArrayEquals(FloatArray(OBSTACLE_GRID_CELL_COUNT), occupancy, 0f)
        assertArrayEquals(ByteArray(depth.size) { GROUND_CLASS_GROUND }, classMap)
        assertEquals(1.0, metrics[NATIVE_GROUND_FILTER_GROUND_FRACTION_INDEX], 0.0)
        assertEquals(0.0, metrics[NATIVE_GROUND_FILTER_OBSTACLE_FRACTION_INDEX], 0.0)
        assertEquals(0.0, metrics[NATIVE_GROUND_FILTER_UNKNOWN_FRACTION_INDEX], 0.0)
        assertTrue(metrics[NATIVE_GROUND_FILTER_PROCESSING_MS_INDEX] >= 0.0)
    }

    @Test
    fun gaussianUniformPosteriorMatchesPythonReference() {
        val result = nativeGroundPosterior(
            residuals = doubleArrayOf(0.0, 0.008, 0.025, 0.1),
            sigma = 0.008,
            groundPrior = 0.8,
            outlierDensity = 1.4,
        )

        assertEquals(0.993030358, result[0], 1e-9)
        assertEquals(0.988560723, result[1], 1e-9)
        assertEquals(0.519086928, result[2], 1e-9)
        assertEquals(1.67685548e-32, result[3], 1e-39)
    }

    @Test
    fun cleanSlopeMatchesPythonMleModel() {
        val result = fit(makeGroundDepth())

        assertSucceeded(result)
        assertEquals(0.03499999970099511, result[A_INDEX], 1e-9)
        assertEquals(0.7200000039936051, result[B_INDEX], 1e-9)
        assertEquals(0.23999999790300397, result[C_INDEX], 1e-9)
        assertEquals(0.008, result[SIGMA_INDEX], 0.0)
        assertEquals(0.95, result[PRIOR_INDEX], 0.0)
        assertEquals(1.8889603986310508, result[OUTLIER_DENSITY_INDEX], 1e-8)
        assertEquals(1.0, result[ITERATIONS_INDEX], 0.0)
        assertEquals(3120.0, result[CANDIDATE_COUNT_INDEX], 0.0)
    }

    @Test
    fun slopeWithNearObstacleMatchesPythonMleModel() {
        val depth = makeGroundDepth()
        for (row in 48 until HEIGHT) {
            for (column in 62 until 96) {
                depth[row * WIDTH + column] = 0.85f
            }
        }

        val result = fit(depth)

        assertSucceeded(result)
        assertEquals(0.034999999725671395, result[A_INDEX], 1e-9)
        assertEquals(0.7200000042011933, result[B_INDEX], 1e-9)
        assertEquals(0.2399999990769916, result[C_INDEX], 1e-9)
        assertEquals(0.008, result[SIGMA_INDEX], 0.0)
        assertEquals(0.7982053758157819, result[PRIOR_INDEX], 1e-8)
        assertEquals(1.3940139707318355, result[OUTLIER_DENSITY_INDEX], 1e-8)
        assertEquals(3.0, result[ITERATIONS_INDEX], 0.0)
    }

    @Test
    fun rejectsFramesWithTooFewCandidates() {
        val depth = FloatArray(WIDTH * HEIGHT) { Float.NaN }
        repeat(99) { depth[(HEIGHT - 1) * WIDTH + it] = 2f }

        assertEquals(STATUS_INSUFFICIENT_CANDIDATES, fit(depth)[STATUS_INDEX].toInt())
    }

    @Test
    fun rejectsNonPositiveGroundSlope() {
        val depth = makeDepthFromPlane(a = 0.035, b = -0.2, c = 0.5)

        assertEquals(STATUS_NON_POSITIVE_SLOPE, fit(depth)[STATUS_INDEX].toInt())
    }

    @Test
    fun rejectsExcessiveResidualSigma() {
        val depth = makeGroundDepth()
        for (index in depth.indices) {
            val inverseDepth = 1.0 / depth[index] + 0.045 * sin(index * 0.73)
            depth[index] = (1.0 / inverseDepth).toFloat()
        }

        assertEquals(STATUS_SIGMA_TOO_LARGE, fit(depth)[STATUS_INDEX].toInt())
    }

    @Test
    fun rejectsInsufficientGlobalGroundSupport() {
        val random = Random(7L)
        val depth = FloatArray(WIDTH * HEIGHT) {
            (1.0 / (0.2 + random.nextDouble())).toFloat()
        }

        assertEquals(STATUS_INSUFFICIENT_INITIAL_SUPPORT, fit(depth)[STATUS_INDEX].toInt())
    }

    @Test
    fun rejectsPlaneWithoutBottomSupport() {
        val depth = makeGroundDepth()
        for (row in 100 until HEIGHT) {
            for (column in 0 until WIDTH) {
                val index = row * WIDTH + column
                depth[index] = (1.0 / (1.0 / depth[index] + 0.2)).toFloat()
            }
        }

        assertEquals(STATUS_INSUFFICIENT_BOTTOM_SUPPORT, fit(depth)[STATUS_INDEX].toInt())
    }

    @Test
    fun rejectsSupportedHorizontalDiscontinuity() {
        val depth = makeGroundDepth()
        for (row in 0 until HEIGHT) {
            for (column in WIDTH / 2 until WIDTH) {
                val index = row * WIDTH + column
                depth[index] = (1.0 / (1.0 / depth[index] + 0.04)).toFloat()
            }
        }

        assertEquals(STATUS_SUPPORTED_DISCONTINUITY, fit(depth)[STATUS_INDEX].toInt())
    }

    private fun fit(depth: FloatArray): DoubleArray = nativeFitGroundPlane(
        depth = depth,
        width = WIDTH,
        height = HEIGHT,
        config = defaultConfig(),
    )

    private fun assertSucceeded(result: DoubleArray) {
        assertEquals(STATUS_SUCCEEDED, result[STATUS_INDEX].toInt())
        assertTrue(result[GROUND_SUPPORT_INDEX] >= 0.12)
        assertTrue(result[BOTTOM_SUPPORT_INDEX] >= 0.20)
    }

    private fun makeGroundDepth(): FloatArray = makeDepthFromPlane(a = 0.035, b = 0.72, c = 0.24)

    private fun makeDepthFromPlane(a: Double, b: Double, c: Double): FloatArray {
        return FloatArray(WIDTH * HEIGHT) { index ->
            val row = index / WIDTH
            val column = index % WIDTH
            val x = 2.0 * column / (WIDTH - 1) - 1.0
            val y = row.toDouble() / (HEIGHT - 1)
            (1.0 / (a * x + b * y + c)).toFloat()
        }
    }

    private fun defaultConfig(): DoubleArray = doubleArrayOf(
        0.35, // fit ROI top
        2.0, // sample step
        20.0, // maximum MLE iterations
        1e-5, // convergence tolerance
        0.008, // minimum sigma
        0.18, // maximum sigma
        0.020, // maximum accepted sigma
        0.65, // initial inlier quantile
        64.0, // RANSAC iterations
        0.025, // RANSAC residual threshold
        3.0, // final sigma multiplier
        0.12, // minimum ground support
        0.20, // minimum bottom support
        0.025, // discontinuity threshold
        0.12, // maximum discontinuity threshold
        0.55, // maximum discontinuity support
        100.0, // minimum candidate count
        0.1, // minimum depth
        30.0, // maximum fit depth
    )

    private external fun nativeGroundPosterior(
        residuals: DoubleArray,
        sigma: Double,
        groundPrior: Double,
        outlierDensity: Double,
    ): DoubleArray

    private external fun nativeFitGroundPlane(
        depth: FloatArray,
        width: Int,
        height: Int,
        config: DoubleArray,
    ): DoubleArray

    companion object {
        private const val WIDTH = 160
        private const val HEIGHT = 120

        private const val STATUS_INDEX = 0
        private const val A_INDEX = 1
        private const val B_INDEX = 2
        private const val C_INDEX = 3
        private const val SIGMA_INDEX = 4
        private const val PRIOR_INDEX = 5
        private const val OUTLIER_DENSITY_INDEX = 6
        private const val ITERATIONS_INDEX = 7
        private const val CANDIDATE_COUNT_INDEX = 8
        private const val GROUND_SUPPORT_INDEX = 9
        private const val BOTTOM_SUPPORT_INDEX = 10

        private const val STATUS_SUCCEEDED = 0
        private const val STATUS_INSUFFICIENT_CANDIDATES = 2
        private const val STATUS_INSUFFICIENT_INITIAL_SUPPORT = 4
        private const val STATUS_NON_POSITIVE_SLOPE = 6
        private const val STATUS_SIGMA_TOO_LARGE = 7
        private const val STATUS_INSUFFICIENT_BOTTOM_SUPPORT = 9
        private const val STATUS_SUPPORTED_DISCONTINUITY = 10

        init {
            System.loadLibrary("ground_filter")
        }
    }
}
