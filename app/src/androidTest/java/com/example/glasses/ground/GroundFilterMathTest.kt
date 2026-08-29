package com.example.glasses.ground

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroundFilterMathTest {
    @Test
    fun convertsOnlyFinitePositiveDepthToInverseDepth() {
        val output = nativeInverseDepth(
            floatArrayOf(2f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f),
        )

        assertEquals(0.5, output[0], 0.0)
        assertEquals(2.0, output[1], 0.0)
        output.drop(2).forEach { assertTrue(it.isNaN()) }
    }

    @Test
    fun precomputesPythonCompatibleNormalizedCoordinates() {
        val coordinates = nativeNormalizedCoordinates(width = 5, height = 3)
        val cellCount = 15

        assertArrayEquals(
            doubleArrayOf(
                -1.0, -0.5, 0.0, 0.5, 1.0,
                -1.0, -0.5, 0.0, 0.5, 1.0,
                -1.0, -0.5, 0.0, 0.5, 1.0,
            ),
            coordinates.copyOfRange(0, cellCount),
            0.0,
        )
        assertArrayEquals(
            doubleArrayOf(
                0.0, 0.0, 0.0, 0.0, 0.0,
                0.5, 0.5, 0.5, 0.5, 0.5,
                1.0, 1.0, 1.0, 1.0, 1.0,
            ),
            coordinates.copyOfRange(cellCount, cellCount * 2),
            0.0,
        )
    }

    @Test
    fun solvesThreeByThreeSystemWithPivoting() {
        val solution = nativeSolveThreeByThree(
            matrix = doubleArrayOf(
                3.0, 2.0, -1.0,
                2.0, -2.0, 4.0,
                -1.0, 0.5, -1.0,
            ),
            rightHandSide = doubleArrayOf(1.0, -2.0, 0.0),
        )

        assertArrayEquals(doubleArrayOf(1.0, -2.0, -2.0), solution, 1e-12)
    }

    @Test
    fun weightedLeastSquaresMatchesPythonReference() {
        val x = doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0, -0.75, 0.25, 0.8)
        val y = doubleArrayOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0, 0.9, 0.1)
        val response = DoubleArray(x.size) { index ->
            0.035 * x[index] + 0.72 * y[index] + 0.24 +
                doubleArrayOf(0.0, 0.002, -0.001, 0.001, 0.0, -0.002, 0.003, -0.001)[index]
        }
        val weights = doubleArrayOf(1.0, 0.8, 0.5, 1.2, 1.0, 0.3, 1.5, 0.7)

        val coefficients = nativeWeightedLeastSquares(x, y, response, weights)

        assertArrayEquals(
            doubleArrayOf(
                0.034507922933835,
                0.7220776434388153,
                0.23978118196435352,
            ),
            coefficients,
            1e-9,
        )
    }

    @Test
    fun deterministicRansacRecoversPythonSyntheticSlope() {
        val width = 40
        val height = 30
        val count = width * height
        val x = DoubleArray(count)
        val y = DoubleArray(count)
        val response = DoubleArray(count)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                x[index] = 2.0 * column / (width - 1) - 1.0
                y[index] = row.toDouble() / (height - 1)
                response[index] = 0.035 * x[index] + 0.72 * y[index] + 0.24
                if (index % 17 == 0) response[index] += 0.18
                if (index >= 5 && (index - 5) % 29 == 0) response[index] -= 0.12
            }
        }

        val result = nativeFitRansac(
            x = x,
            y = y,
            response = response,
            iterations = 64,
            residualThreshold = 0.025,
            fallbackInlierQuantile = 0.65,
        )

        assertArrayEquals(
            doubleArrayOf(0.035, 0.72, 0.24),
            result.copyOfRange(0, 3),
            1e-9,
        )
        assertEquals(1090.0, result[3], 0.0)
    }

    @Test
    fun madSigmaMatchesPythonAndAppliesBounds() {
        val residuals = doubleArrayOf(-0.02, -0.01, 0.0, 0.01, 0.02, 0.5)
        val inliers = booleanArrayOf(true, true, true, true, true, true)

        assertEquals(
            0.022239,
            nativeEstimateMadSigma(residuals, inliers, minSigma = 0.008, maxSigma = 0.18),
            1e-12,
        )
        assertEquals(
            0.008,
            nativeEstimateMadSigma(
                DoubleArray(4),
                BooleanArray(4) { true },
                minSigma = 0.008,
                maxSigma = 0.18,
            ),
            0.0,
        )
    }

    private external fun nativeInverseDepth(depth: FloatArray): DoubleArray

    private external fun nativeNormalizedCoordinates(width: Int, height: Int): DoubleArray

    private external fun nativeSolveThreeByThree(
        matrix: DoubleArray,
        rightHandSide: DoubleArray,
    ): DoubleArray

    private external fun nativeWeightedLeastSquares(
        x: DoubleArray,
        y: DoubleArray,
        response: DoubleArray,
        weights: DoubleArray,
    ): DoubleArray

    private external fun nativeFitRansac(
        x: DoubleArray,
        y: DoubleArray,
        response: DoubleArray,
        iterations: Int,
        residualThreshold: Double,
        fallbackInlierQuantile: Double,
    ): DoubleArray

    private external fun nativeEstimateMadSigma(
        residuals: DoubleArray,
        inliers: BooleanArray,
        minSigma: Double,
        maxSigma: Double,
    ): Double

    companion object {
        init {
            System.loadLibrary("ground_filter")
        }
    }
}
