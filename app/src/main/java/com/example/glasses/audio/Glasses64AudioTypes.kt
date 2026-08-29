package com.example.glasses.audio

import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import com.example.glasses.obstacle.OBSTACLE_GRID_COLUMNS
import com.example.glasses.obstacle.OBSTACLE_GRID_ROWS

internal const val GLASSES64_ROWS = OBSTACLE_GRID_ROWS
internal const val GLASSES64_COLUMNS = OBSTACLE_GRID_COLUMNS
internal const val GLASSES64_CELL_COUNT = OBSTACLE_GRID_CELL_COUNT
internal const val GLASSES64_SAMPLE_RATE = 48_000
internal const val GLASSES64_HRIR_LENGTH = 256
internal const val TOTAL_SCAN_DURATION_SECONDS = 1.0
internal const val GLASSES64_SCAN_UNIT_COUNT = GLASSES64_COLUMNS
internal const val GLASSES64_SCAN_UNIT_DURATION_SECONDS =
    TOTAL_SCAN_DURATION_SECONDS / GLASSES64_SCAN_UNIT_COUNT
internal const val GLASSES64_MAX_REGIONS_PER_COLUMN = 3
internal const val GLASSES64_INPUT_TIMEOUT_MS = 350L

internal data class Glasses64VerticalRegion(
    val startRow: Int,
    val endRow: Int,
    val representativeRow: Int,
    val strength: Float,
) {
    init {
        require(startRow in 0 until GLASSES64_ROWS)
        require(endRow in startRow until GLASSES64_ROWS)
        require(representativeRow in startRow..endRow)
        require(strength.isFinite() && strength in 0.0f..1.0f)
    }
}

internal data class Glasses64ActiveCell(
    val row: Int,
    val strength: Float,
) {
    init {
        require(row in 0 until GLASSES64_ROWS)
        require(strength.isFinite() && strength in 0.0f..1.0f)
    }
}

internal data class Glasses64ColumnRequest(
    val column: Int,
    val regions: List<Glasses64VerticalRegion>,
    val activeCells: List<Glasses64ActiveCell>,
) {
    init {
        require(column in 0 until GLASSES64_COLUMNS)
    }
}

internal val HRTF64_CALIBRATION_REPRESENTATIVE_COLUMNS = intArrayOf(
    0, 8, 16, 24, 39, 47, 55, 63,
)

internal val HRTF64_CALIBRATION_REPRESENTATIVE_ROWS = intArrayOf(
    0, 14, 29, 43, 54, 63,
)

internal fun hrtf64CalibrationAzimuthDegrees(column: Int): Float =
    -60f + column.coerceIn(0, GLASSES64_COLUMNS - 1) * (120f / 63f)

internal fun hrtf64CalibrationElevationDegrees(row: Int): Float =
    60f - row.coerceIn(0, GLASSES64_ROWS - 1) * (105f / 63f)
