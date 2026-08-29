package com.example.glasses.ground

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.glasses.depth.MetricDepthFrame
import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroundOccupancyTest {
    @Test
    fun mapsEach640CellToExactlyTenByTenPixelsAndMatchesPythonGolden() {
        val width = 640
        val height = 640
        val gridRow = 48
        val gridColumn = 30
        val depth = makeGroundDepth(width, height)
        fillGridCell(depth, width, height, gridRow, gridColumn, 0.8f)

        val result = process(depth, width, height, withClassMap = false, sampleStep = 4)

        assertTrue(result.fitSucceeded)
        assertEquals(1f, result.occupancy[gridRow * GRID_SIZE + gridColumn], 0f)
        assertEquals(0f, result.occupancy[gridRow * GRID_SIZE + gridColumn - 1], 0f)
        assertEquals(0f, result.occupancy[gridRow * GRID_SIZE + gridColumn + 1], 0f)
        assertEquals(1, result.occupancy.count { it > 0f })
    }

    @Test
    fun usesIntegerCellBoundariesForNonDivisibleDimensions() {
        val width = 130
        val height = 97
        val gridRow = 8
        val gridColumn = 20
        val depth = makeGroundDepth(width, height)
        fillGridCell(depth, width, height, gridRow, gridColumn, 0.8f)

        val result = process(depth, width, height, withClassMap = true, sampleStep = 2)

        assertTrue(result.fitSucceeded)
        assertEquals(1f, result.occupancy[gridRow * GRID_SIZE + gridColumn], 0f)
        assertArrayEquals(expectedOccupancy(result.classMap!!, width, height), result.occupancy, 1e-6f)
    }

    @Test
    fun nativeOccupancyMatchesOfflineClassMapStatistics() {
        val width = 160
        val height = 120
        val depth = makeGroundDepth(width, height)
        fillRect(depth, width, top = 5, bottom = 28, left = 42, right = 78, value = 0.75f)
        fillRect(depth, width, top = 72, bottom = 98, left = 105, right = 136, value = 0.9f)
        fillRect(depth, width, top = 0, bottom = 12, left = 0, right = 30, value = Float.NaN)

        val result = process(depth, width, height, withClassMap = true, sampleStep = 2)
        val expected = expectedOccupancy(result.classMap!!, width, height)

        assertTrue(result.fitSucceeded)
        assertArrayEquals(expected, result.occupancy, 1e-6f)
        assertTrue(result.occupancy.all { it.isFinite() && it in 0f..1f })
        assertTrue(result.occupancy.take(GRID_SIZE * 20).any { it > 0f })
    }

    @Test
    fun classMapCanBeOmittedWithoutChangingOccupancy() {
        val width = 160
        val height = 120
        val depth = makeGroundDepth(width, height)
        fillRect(depth, width, top = 8, bottom = 36, left = 52, right = 108, value = 0.8f)

        val withClassMap = process(depth, width, height, withClassMap = true, sampleStep = 2)
        val withoutClassMap = process(depth, width, height, withClassMap = false, sampleStep = 2)

        assertTrue(withClassMap.fitSucceeded)
        assertTrue(withoutClassMap.fitSucceeded)
        assertArrayEquals(withClassMap.occupancy, withoutClassMap.occupancy, 0f)
    }

    private fun process(
        depth: FloatArray,
        width: Int,
        height: Int,
        withClassMap: Boolean,
        sampleStep: Int,
    ): OccupancyResult {
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT) { Float.NaN }
        val classMap = if (withClassMap) ByteArray(depth.size) else null
        val metrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT)
        val fitSucceeded = NativeGroundFilter(
            GroundFilterConfig(
                fitRoiTop = 0.45f,
                classificationRoiTop = 0f,
                sampleStep = sampleStep,
            ),
        ).use { filter ->
            filter.process(
                MetricDepthFrame(depth, width, height, timestampMs = 1L),
                occupancy,
                classMap,
                metrics,
            )
        }
        return OccupancyResult(fitSucceeded, occupancy, classMap)
    }

    private fun makeGroundDepth(width: Int, height: Int): FloatArray {
        return FloatArray(width * height) { index ->
            val row = index / width
            val column = index % width
            val x = 2.0 * column / (width - 1).coerceAtLeast(1) - 1.0
            val y = row.toDouble() / (height - 1).coerceAtLeast(1)
            (1.0 / (0.035 * x + 0.72 * y + 0.24)).toFloat()
        }
    }

    private fun fillGridCell(
        depth: FloatArray,
        width: Int,
        height: Int,
        gridRow: Int,
        gridColumn: Int,
        value: Float,
    ) {
        val top = gridRow * height / GRID_SIZE
        val bottom = (gridRow + 1) * height / GRID_SIZE
        val left = gridColumn * width / GRID_SIZE
        val right = (gridColumn + 1) * width / GRID_SIZE
        fillRect(depth, width, top, bottom, left, right, value)
    }

    private fun fillRect(
        depth: FloatArray,
        width: Int,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        value: Float,
    ) {
        for (row in top until bottom) {
            for (column in left until right) depth[row * width + column] = value
        }
    }

    private fun expectedOccupancy(classMap: ByteArray, width: Int, height: Int): FloatArray {
        return FloatArray(OBSTACLE_GRID_CELL_COUNT) { cell ->
            val gridRow = cell / GRID_SIZE
            val gridColumn = cell % GRID_SIZE
            val top = gridRow * height / GRID_SIZE
            val bottom = (gridRow + 1) * height / GRID_SIZE
            val left = gridColumn * width / GRID_SIZE
            val right = (gridColumn + 1) * width / GRID_SIZE
            var obstacles = 0
            var total = 0
            for (row in top until bottom) {
                for (column in left until right) {
                    if (classMap[row * width + column] == GROUND_CLASS_OBSTACLE) ++obstacles
                    ++total
                }
            }
            if (total == 0) 0f else obstacles.toFloat() / total
        }
    }

    private data class OccupancyResult(
        val fitSucceeded: Boolean,
        val occupancy: FloatArray,
        val classMap: ByteArray?,
    )

    companion object {
        private const val GRID_SIZE = 64
    }
}
